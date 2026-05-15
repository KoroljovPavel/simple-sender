package com.botfunnel.bot;

import com.botfunnel.bot.dto.TelegramUser;
import com.botfunnel.common.AppException;
import com.botfunnel.common.crypto.EncryptedValue;
import com.botfunnel.common.crypto.TokenEncryptor;
import com.botfunnel.events.EventService;
import com.botfunnel.project.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

@Service
public class BotService {

    private static final Logger log = LoggerFactory.getLogger(BotService.class);

    private static final String EVENT_BOT_CONNECTED = "bot_connected";
    private static final String EVENT_BOT_DISCONNECTED = "bot_disconnected";

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
    private final EventService eventService;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final String appUrl;
    private final SecureRandom secureRandom = new SecureRandom();

    public BotService(BotRepository botRepository,
                      ProjectService projectService,
                      TokenEncryptor tokenEncryptor,
                      TelegramApiClient telegramApiClient,
                      EventService eventService,
                      ReactiveRedisTemplate<String, String> redisTemplate,
                      @Value("${app.url}") String appUrl) {
        this.botRepository = botRepository;
        this.projectService = projectService;
        this.tokenEncryptor = tokenEncryptor;
        this.telegramApiClient = telegramApiClient;
        this.eventService = eventService;
        this.redisTemplate = redisTemplate;
        this.appUrl = appUrl;
    }

    public Mono<Bot> getByProject(String ownerId, String projectId) {
        return requireConnectedBot(ownerId, projectId);
    }

    public Mono<Bot> connect(String ownerId, String projectId, String token, String ip, String userAgent) {
        // Mono.defer wraps each subsequent step so a short-circuit upstream (e.g. 429 from the
        // brute-force counter) never constructs the downstream Monos — keeps the no-Telegram-call
        // guarantee (AC11) testable and mirrors the AuthService chain pattern.
        return projectService.requireOwned(ownerId, projectId, false)
                .then(Mono.defer(() -> incrementBruteForceCounter(ownerId)))
                .then(Mono.defer(() -> ensureNoConnectedBotForProject(projectId)))
                .then(Mono.defer(() -> telegramApiClient.getMe(token)))
                .flatMap(user -> ensureTelegramBotIdNotConnectedAnywhere(user.id()).thenReturn(user))
                .flatMap(user -> connectAfterPreChecks(projectId, token, user))
                .doOnSuccess(saved -> eventService.logEvent(ownerId, EVENT_BOT_CONNECTED,
                        ip, userAgent, connectedMetadata(saved)))
                .flatMap(saved -> resetBruteForceCounter(ownerId).thenReturn(saved));
    }

    public Mono<Void> disconnect(String ownerId, String projectId, String ip, String userAgent) {
        return requireConnectedBot(ownerId, projectId)
                .flatMap(bot -> doDisconnect(bot, ownerId, projectId, ip, userAgent))
                .then();
    }

    public Mono<Void> sendTestMessage(String ownerId, String projectId, String ip, String userAgent) {
        return requireConnectedBot(ownerId, projectId)
                // D7: in feature 06 the endpoint short-circuits with 422 and emits NO event.
                // The success branch + bot_test_message_sent event arrive in 06b.
                .flatMap(bot -> Mono.<Void>error(AppException.unprocessableEntity(
                        "owner_chat_id_unknown",
                        "Send /start to your bot in Telegram first, then try again")));
    }

    private Mono<Bot> requireConnectedBot(String ownerId, String projectId) {
        return projectService.requireOwned(ownerId, projectId, false)
                .then(Mono.defer(() -> botRepository.findByProjectIdAndStatus(projectId, BotStatus.CONNECTED)))
                .switchIfEmpty(Mono.error(AppException.notFound(MESSAGE_BOT_NOT_FOUND)));
    }

    private Mono<Bot> connectAfterPreChecks(String projectId, String token, TelegramUser user) {
        byte[] secretBytes = new byte[WEBHOOK_SECRET_BYTES];
        secureRandom.nextBytes(secretBytes);
        String secretHex = HexFormat.of().formatHex(secretBytes);
        String secretHash = sha256Hex(secretHex);
        String webhookUrl = appUrl + "/webhooks/telegram/" + projectId;

        return telegramApiClient.setWebhook(token, webhookUrl, secretHex)
                .then(Mono.defer(() -> {
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

                    return botRepository.save(bot)
                            .onErrorResume(persistErr ->
                                    compensateAndPropagate(token, user.id(), persistErr));
                }));
    }

    private Mono<Bot> compensateAndPropagate(String token, Long telegramBotId, Throwable persistErr) {
        // D4: setWebhook already succeeded — best-effort deleteWebhook to roll Telegram back.
        // The compensation must never shadow the original persist error, so we swallow any
        // failure from the compensation itself before rethrowing. WebClient transport-error
        // messages from TelegramApiClient typically embed the request URI which carries the
        // token; scrub at the log site (R1 / AC17).
        return telegramApiClient.deleteWebhook(token)
                .onErrorResume(compErr -> {
                    log.warn("Compensating deleteWebhook failed during Connect rollback: {}",
                            TelegramApiClient.scrubTokens(compErr.getMessage()));
                    return Mono.just(false);
                })
                .then(mapPersistError(persistErr, telegramBotId))
                .flatMap(Mono::error);
    }

    private Mono<Throwable> mapPersistError(Throwable persistErr, Long telegramBotId) {
        if (!(persistErr instanceof DuplicateKeyException dke)) {
            return Mono.just(persistErr);
        }
        String message = dke.getMessage() == null ? "" : dke.getMessage();
        if (message.contains("projectId_unique_connected")) {
            return Mono.just(AppException.conflict(
                    CODE_BOT_ALREADY_IN_PROJECT, MESSAGE_BOT_ALREADY_IN_PROJECT));
        }
        if (message.contains("telegramBotId_unique_connected")) {
            return Mono.just(AppException.conflict(
                    CODE_BOT_ALREADY_CONNECTED, MESSAGE_BOT_ALREADY_CONNECTED));
        }
        // Driver fallback: if the exception message omits the index name, disambiguate by
        // re-querying the platform-wide partial unique index — a row with this telegramBotId
        // means the platform-wide index fired; otherwise the per-project index fired.
        return botRepository.findFirstByTelegramBotIdAndStatus(telegramBotId, BotStatus.CONNECTED)
                .<Throwable>map(existing -> AppException.conflict(
                        CODE_BOT_ALREADY_CONNECTED, MESSAGE_BOT_ALREADY_CONNECTED))
                .defaultIfEmpty(AppException.conflict(
                        CODE_BOT_ALREADY_IN_PROJECT, MESSAGE_BOT_ALREADY_IN_PROJECT));
    }

    private Mono<Void> ensureNoConnectedBotForProject(String projectId) {
        return botRepository.findByProjectIdAndStatus(projectId, BotStatus.CONNECTED)
                .flatMap(existing -> Mono.<Void>error(AppException.conflict(
                        CODE_BOT_ALREADY_IN_PROJECT, MESSAGE_BOT_ALREADY_IN_PROJECT)))
                .then();
    }

    private Mono<Void> ensureTelegramBotIdNotConnectedAnywhere(Long telegramBotId) {
        return botRepository.findFirstByTelegramBotIdAndStatus(telegramBotId, BotStatus.CONNECTED)
                .flatMap(existing -> Mono.<Void>error(AppException.conflict(
                        CODE_BOT_ALREADY_CONNECTED, MESSAGE_BOT_ALREADY_CONNECTED)))
                .then();
    }

    private Mono<Bot> doDisconnect(Bot bot, String ownerId, String projectId, String ip, String userAgent) {
        // Plaintext token: decode → decrypt → pass to deleteWebhook. Closure capture by the
        // downstream lambdas keeps the reference reachable until the subscription completes —
        // not stack-only — but the token is never logged, never returned, and never serialized.
        byte[] iv = Base64.getDecoder().decode(bot.getEncryptedTokenIv());
        byte[] ct = Base64.getDecoder().decode(bot.getEncryptedTokenCiphertext());
        String plaintextToken = tokenEncryptor.decrypt(iv, ct);

        return telegramApiClient.deleteWebhook(plaintextToken)
                // AC13b: persistent Telegram failure must NEVER block the local update.
                // WebClient transport-error messages embed the request URI (with token); scrub.
                .onErrorResume(err -> {
                    log.warn(TELEGRAM_DISCONNECT_WARN, TelegramApiClient.scrubTokens(err.getMessage()));
                    return Mono.just(false);
                })
                .flatMap(deleted -> {
                    bot.setStatus(BotStatus.DISCONNECTED);
                    bot.setEncryptedTokenCiphertext(null);
                    bot.setEncryptedTokenIv(null);
                    bot.setTokenSuffix(null);
                    bot.setWebhookSecretHash(null);
                    bot.setDisconnectedAt(Instant.now());
                    return botRepository.save(bot)
                            .doOnSuccess(saved -> eventService.logEvent(ownerId, EVENT_BOT_DISCONNECTED,
                                    ip, userAgent, disconnectedMetadata(saved, projectId,
                                            Boolean.TRUE.equals(deleted))));
                });
    }

    private Mono<Void> incrementBruteForceCounter(String userId) {
        // D14: INCR every attempt (not only on failure) — closes the Connect-then-Disconnect
        // bypass that an INCR-on-failure-only counter (auth pattern) would leave open. The
        // `count != null` guards are defensive against a non-spec emit; a genuine null would
        // still surface via the outer .onErrorResume (fail-open) — never as a 429.
        String key = bruteForceKey(userId);
        return redisTemplate.opsForValue().increment(key)
                .flatMap(count -> {
                    Mono<Void> ttl = (count != null && count == 1L)
                            ? redisTemplate.expire(key, BRUTE_TTL).then()
                            : Mono.empty();
                    if (count != null && count > BRUTE_FORCE_THRESHOLD) {
                        return Mono.<Void>error(AppException.tooManyRequests(
                                "Too many Connect attempts. Try again later."));
                    }
                    return ttl;
                })
                .onErrorResume(err -> {
                    if (err instanceof AppException) {
                        return Mono.error(err);
                    }
                    log.warn(REDIS_FAIL_OPEN_WARN, err.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> resetBruteForceCounter(String userId) {
        String key = bruteForceKey(userId);
        return redisTemplate.delete(key)
                .then()
                .onErrorResume(err -> {
                    log.warn(REDIS_FAIL_OPEN_WARN, err.getMessage());
                    return Mono.empty();
                });
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

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
