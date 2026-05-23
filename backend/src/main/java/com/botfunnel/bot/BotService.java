package com.botfunnel.bot;

import com.botfunnel.bot.dto.SentMessage;
import com.botfunnel.bot.dto.TelegramUser;
import com.botfunnel.common.AppException;
import com.botfunnel.common.crypto.EncryptedValue;
import com.botfunnel.common.crypto.Sha256Hex;
import com.botfunnel.common.crypto.TokenEncryptor;
import com.botfunnel.events.EventService;
import com.botfunnel.project.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

@Service
public class BotService {

    private static final Logger log = LoggerFactory.getLogger(BotService.class);

    private static final String EVENT_BOT_CONNECTED = "bot_connected";
    private static final String EVENT_BOT_DISCONNECTED = "bot_disconnected";
    private static final String EVENT_BOT_TEST_MESSAGE_SENT = "bot_test_message_sent";

    // Exact string from user-spec Сценарій 2 — trailing space + U+2705 check-mark emoji preserved
    // byte-for-byte. Editor must keep the codepoint intact (no NFD normalisation).
    static final String TEST_MESSAGE_BODY = "Hello from Bot Funnel Service! Bot connected ✅";

    private static final int BRUTE_FORCE_THRESHOLD = 10;
    private static final Duration BRUTE_TTL = Duration.ofSeconds(900);
    private static final int WEBHOOK_SECRET_BYTES = 16;

    // Dedicated, greppable WARN line for the Redis fail-open path (D14). The project has no
    // Micrometer / actuator dependency, so a sustained Redis outage must be observable via logs
    // alone — keep this string stable so log-based alerts and tests can pin to it.
    static final String REDIS_FAIL_OPEN_WARN =
            "bot-connect brute-force counter Redis failure (fail-open): {}";

    // Pinned WARN message for the Disconnect-with-Telegram-down fail-open path (AC13b). Kept as a
    // constant so tests can assert it exactly without duplicating the literal across modules.
    static final String TELEGRAM_DISCONNECT_WARN =
            "Telegram deleteWebhook failed during Disconnect; proceeding to local update: {}";

    private static final String CODE_BOT_ALREADY_IN_PROJECT = "bot_already_in_project";
    private static final String CODE_BOT_ALREADY_CONNECTED = "bot_already_connected";
    private static final String MESSAGE_BOT_ALREADY_IN_PROJECT =
            "another bot is already connected — disconnect it first";
    private static final String MESSAGE_BOT_ALREADY_CONNECTED =
            "This bot is already connected to another project";
    private static final String MESSAGE_BOT_NOT_FOUND = "Bot not found";

    private final BotRepository botRepository;
    private final ProjectService projectService;
    private final TokenEncryptor tokenEncryptor;
    private final TelegramApiClient telegramApiClient;
    private final TelegramSender telegramSender;
    private final EventService eventService;
    private final StringRedisTemplate redisTemplate;
    private final String appUrl;
    private final SecureRandom secureRandom = new SecureRandom();

    public BotService(BotRepository botRepository,
                      ProjectService projectService,
                      TokenEncryptor tokenEncryptor,
                      TelegramApiClient telegramApiClient,
                      TelegramSender telegramSender,
                      EventService eventService,
                      StringRedisTemplate redisTemplate,
                      @Value("${app.url}") String appUrl) {
        this.botRepository = botRepository;
        this.projectService = projectService;
        this.tokenEncryptor = tokenEncryptor;
        this.telegramApiClient = telegramApiClient;
        this.telegramSender = telegramSender;
        this.eventService = eventService;
        this.redisTemplate = redisTemplate;
        this.appUrl = appUrl;
    }

    public Bot getByProject(String ownerId, String projectId) {
        return requireConnectedBot(ownerId, projectId);
    }

    public Bot connect(String ownerId, String projectId, String token, String ip, String userAgent) {
        // Straight-line sequential calls preserving the documented order from the reactive chain:
        // (1) ownership guard → (2) brute-force INCR (D14 INCR-every-attempt — closes the
        // Connect-then-Disconnect bypass) → (3) per-project pre-check → (4) Telegram getMe →
        // (5) platform-wide telegramBotId pre-check → (6) setWebhook + persist + compensation
        // → (7) audit event AFTER save success → (8) brute-force counter reset.
        projectService.requireOwned(ownerId, projectId, false);
        incrementBruteForceCounter(ownerId);
        ensureNoConnectedBotForProject(projectId);
        TelegramUser user = telegramApiClient.getMe(token);
        ensureTelegramBotIdNotConnectedAnywhere(user.id());
        Bot saved = connectAfterPreChecks(projectId, token, user);
        eventService.logEvent(ownerId, EVENT_BOT_CONNECTED, ip, userAgent, connectedMetadata(saved));
        resetBruteForceCounter(ownerId);
        return saved;
    }

    public void disconnect(String ownerId, String projectId, String ip, String userAgent) {
        Bot bot = requireConnectedBot(ownerId, projectId);
        doDisconnect(bot, ownerId, projectId, ip, userAgent);
    }

    public void sendTestMessage(String ownerId, String projectId, String ip, String userAgent) {
        Bot bot = requireConnectedBot(ownerId, projectId);
        // Null branch preserved until Epic 04b webhook ingestion populates ownerChatId.
        // Verbatim 422 contract from the pre-Wave-3 stub — message, code, and HTTP status
        // must NOT drift; the regression test pins all three.
        if (bot.getOwnerChatId() == null) {
            throw AppException.unprocessableEntity(
                    "owner_chat_id_unknown",
                    "Send /start to your bot in Telegram first, then try again");
        }
        SentMessage sm = telegramSender.sendText(bot.getId(), bot.getOwnerChatId(),
                TEST_MESSAGE_BODY, null, ownerId);
        // Decision 3: BotService writes bot_test_message_sent ONLY on success.
        // On failure the exception propagates; sender already wrote telegram_send_failed
        // — no double-write.
        eventService.logEvent(ownerId, EVENT_BOT_TEST_MESSAGE_SENT, ip, userAgent,
                testMessageMetadata(bot, sm));
    }

    private Bot requireConnectedBot(String ownerId, String projectId) {
        projectService.requireOwned(ownerId, projectId, false);
        return botRepository.findByProjectIdAndStatus(projectId, BotStatus.CONNECTED)
                .orElseThrow(() -> AppException.notFound(MESSAGE_BOT_NOT_FOUND));
    }

    private Bot connectAfterPreChecks(String projectId, String token, TelegramUser user) {
        byte[] secretBytes = new byte[WEBHOOK_SECRET_BYTES];
        secureRandom.nextBytes(secretBytes);
        String secretHex = HexFormat.of().formatHex(secretBytes);
        String secretHash = Sha256Hex.hex(secretHex);
        String webhookUrl = appUrl + "/webhooks/telegram/" + projectId;

        telegramApiClient.setWebhook(token, webhookUrl, secretHex);

        // BotService owns the Base64 boundary (D15): TokenEncryptor returns raw bytes;
        // Bot.encryptedToken{Iv,Ciphertext} are declared as String (Base64) per
        // tech-spec Data Models lines 292-293.
        EncryptedValue encrypted = tokenEncryptor.encrypt(token);
        String ivB64 = Base64.getEncoder().encodeToString(encrypted.iv());
        String ctB64 = Base64.getEncoder().encodeToString(encrypted.ciphertext());
        String tokenSuffix = token.substring(token.length() - 3);

        Bot bot = new Bot();
        bot.setProjectId(projectId);
        bot.setTelegramBotId(user.id());
        bot.setTelegramUsername(user.username());
        bot.setTelegramFirstName(user.first_name());
        bot.setStatus(BotStatus.CONNECTED);
        bot.setEncryptedTokenCiphertext(ctB64);
        bot.setEncryptedTokenIv(ivB64);
        bot.setTokenSuffix(tokenSuffix);
        bot.setWebhookSecretHash(secretHash);
        bot.setConnectedAt(Instant.now());

        try {
            return botRepository.save(bot);
        } catch (RuntimeException persistErr) {
            // D4: setWebhook already succeeded — best-effort deleteWebhook to roll Telegram back.
            // The compensation must never shadow the original persist error, so we swallow any
            // failure from the compensation itself before rethrowing. RestClient transport-error
            // messages typically embed the request URI which carries the token; scrub at the log
            // site (R1 / AC17).
            try {
                telegramApiClient.deleteWebhook(token);
            } catch (Exception compErr) {
                log.warn("Compensating deleteWebhook failed during Connect rollback: {}",
                        TelegramApiClient.scrubTokens(compErr.getMessage()));
            }
            throw mapPersistError(persistErr, user.id());
        }
    }

    private RuntimeException mapPersistError(RuntimeException persistErr, Long telegramBotId) {
        if (!(persistErr instanceof DuplicateKeyException dke)) {
            return persistErr;
        }
        String message = dke.getMessage() == null ? "" : dke.getMessage();
        if (message.contains("projectId_unique_connected")) {
            return AppException.conflict(CODE_BOT_ALREADY_IN_PROJECT, MESSAGE_BOT_ALREADY_IN_PROJECT);
        }
        if (message.contains("telegramBotId_unique_connected")) {
            return AppException.conflict(CODE_BOT_ALREADY_CONNECTED, MESSAGE_BOT_ALREADY_CONNECTED);
        }
        // Driver fallback: if the exception message omits the index name, disambiguate by
        // re-querying the platform-wide partial unique index — a row with this telegramBotId
        // means the platform-wide index fired; otherwise the per-project index fired.
        Optional<Bot> existing = botRepository.findFirstByTelegramBotIdAndStatus(telegramBotId, BotStatus.CONNECTED);
        if (existing.isPresent()) {
            return AppException.conflict(CODE_BOT_ALREADY_CONNECTED, MESSAGE_BOT_ALREADY_CONNECTED);
        }
        return AppException.conflict(CODE_BOT_ALREADY_IN_PROJECT, MESSAGE_BOT_ALREADY_IN_PROJECT);
    }

    private void ensureNoConnectedBotForProject(String projectId) {
        if (botRepository.findByProjectIdAndStatus(projectId, BotStatus.CONNECTED).isPresent()) {
            throw AppException.conflict(CODE_BOT_ALREADY_IN_PROJECT, MESSAGE_BOT_ALREADY_IN_PROJECT);
        }
    }

    private void ensureTelegramBotIdNotConnectedAnywhere(Long telegramBotId) {
        if (botRepository.findFirstByTelegramBotIdAndStatus(telegramBotId, BotStatus.CONNECTED).isPresent()) {
            throw AppException.conflict(CODE_BOT_ALREADY_CONNECTED, MESSAGE_BOT_ALREADY_CONNECTED);
        }
    }

    private void doDisconnect(Bot bot, String ownerId, String projectId, String ip, String userAgent) {
        // Plaintext token: decode → decrypt → pass to deleteWebhook. Local variable lifetime is
        // bounded to this method scope; the token is never logged, returned, or serialised.
        byte[] iv = Base64.getDecoder().decode(bot.getEncryptedTokenIv());
        byte[] ct = Base64.getDecoder().decode(bot.getEncryptedTokenCiphertext());
        String plaintextToken = tokenEncryptor.decrypt(iv, ct);

        boolean deleted;
        try {
            deleted = telegramApiClient.deleteWebhook(plaintextToken);
        } catch (Exception err) {
            // AC13b: persistent Telegram failure must NEVER block the local update.
            // RestClient transport-error messages embed the request URI (with token); scrub.
            log.warn(TELEGRAM_DISCONNECT_WARN, TelegramApiClient.scrubTokens(err.getMessage()));
            deleted = false;
        }

        bot.setStatus(BotStatus.DISCONNECTED);
        bot.setEncryptedTokenCiphertext(null);
        bot.setEncryptedTokenIv(null);
        bot.setTokenSuffix(null);
        bot.setWebhookSecretHash(null);
        bot.setDisconnectedAt(Instant.now());
        Bot saved = botRepository.save(bot);
        eventService.logEvent(ownerId, EVENT_BOT_DISCONNECTED, ip, userAgent,
                disconnectedMetadata(saved, projectId, deleted));
    }

    private void incrementBruteForceCounter(String userId) {
        // D14: INCR every attempt (not only on failure) — closes the Connect-then-Disconnect
        // bypass that an INCR-on-failure-only counter (auth pattern) would leave open. The
        // null guards are defensive against a non-spec emit; a genuine null would still surface
        // via the catch (fail-open) — never as a 429.
        String key = bruteForceKey(userId);
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, BRUTE_TTL);
            }
            if (count != null && count > BRUTE_FORCE_THRESHOLD) {
                throw AppException.tooManyRequests("Too many Connect attempts. Try again later.");
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.warn(REDIS_FAIL_OPEN_WARN, e.getMessage());
        }
    }

    private void resetBruteForceCounter(String userId) {
        String key = bruteForceKey(userId);
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn(REDIS_FAIL_OPEN_WARN, e.getMessage());
        }
    }

    private static String bruteForceKey(String userId) {
        return "brute:bot-connect:" + userId;
    }

    private static Map<String, Object> connectedMetadata(Bot saved) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("projectId", saved.getProjectId());
        meta.put("telegramBotId", saved.getTelegramBotId());
        meta.put("telegramUsername", saved.getTelegramUsername());
        return meta;
    }

    private static Map<String, Object> disconnectedMetadata(Bot saved, String projectId, boolean webhookDeleted) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("projectId", projectId);
        meta.put("telegramBotId", saved.getTelegramBotId());
        meta.put("webhookDeleted", webhookDeleted);
        return meta;
    }

    private static Map<String, Object> testMessageMetadata(Bot bot, SentMessage sm) {
        // projectId comes from the Bot doc (not the controller path-variable) for consistency
        // with disconnectedMetadata's pattern — requireConnectedBot has already proven they match.
        Map<String, Object> meta = new HashMap<>();
        meta.put("projectId", bot.getProjectId());
        meta.put("telegramBotId", bot.getTelegramBotId());
        meta.put("chatId", sm.chatId());
        meta.put("messageId", sm.messageId());
        return meta;
    }

}
