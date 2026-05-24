package com.botfunnel.bot;

import com.botfunnel.bot.dto.SentMessage;
import com.botfunnel.bot.dto.TelegramSendParameters;
import com.botfunnel.bot.dto.TelegramSendResult;
import com.botfunnel.common.AppException;
import com.botfunnel.common.crypto.TokenEncryptor;
import com.botfunnel.events.EventService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Outbound sender for Telegram {@code sendMessage}. Owns: per-call AES-GCM token decrypt, 5xx
 * exponential backoff (1s/2s/4s, 3 retries), 429 {@code retry_after} loop wrapping the 5xx loop,
 * overall 30s timeout, typed-exception mapping, audit-event emission, and token-scrubbed logging.
 *
 * <p>Plaintext-token lifetime: decrypted token is captured in a {@link #sendOnce} local variable
 * for the duration of one HTTP attempt and reachable only until that synchronous call returns.
 * Never stored as a field, never logged, never serialized, never escapes the calling virtual thread.
 */
@Component
public class TelegramSender {

    private static final Logger log = LoggerFactory.getLogger(TelegramSender.class);

    static final String EVENT_TELEGRAM_MESSAGE_SENT = "telegram_message_sent";
    static final String EVENT_TELEGRAM_SEND_FAILED = "telegram_send_failed";

    // Anti-enumeration 404 message. Kept in sync with BotService.java's MESSAGE_BOT_NOT_FOUND
    // constant — both must produce identical wire shape ({status: 404, code: null,
    // message: "Bot not found"}) so a system-context caller can't distinguish "bot does not exist"
    // from "bot exists but is not CONNECTED".
    static final String MESSAGE_BOT_NOT_FOUND = "Bot not found";

    private static final Duration DEFAULT_OVERALL_TIMEOUT = Duration.ofSeconds(30);
    private static final int RATE_LIMIT_DELAY_CAP_SECONDS = 30;
    private static final int RATE_LIMIT_JITTER_MAX_MS = 200;
    private static final int MAX_RETRIES = 3;
    private static final Duration INITIAL_5XX_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_5XX_BACKOFF = Duration.ofSeconds(4);

    private static final ObjectMapper STATIC_OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<TelegramSendResult<JsonNode>> SEND_RESULT_TYPE =
            new TypeReference<>() {};

    private final RestClient restClient;
    private final BotRepository botRepository;
    private final TokenEncryptor tokenEncryptor;
    private final EventService eventService;
    private final Duration overallTimeout;

    @Autowired
    public TelegramSender(RestClient.Builder builder,
                          @Value("${app.telegram.base-url}") String baseUrl,
                          BotRepository botRepository,
                          TokenEncryptor tokenEncryptor,
                          EventService eventService) {
        this(builder, baseUrl, TelegramApiClient.DEFAULT_RESPONSE_TIMEOUT, DEFAULT_OVERALL_TIMEOUT,
                botRepository, tokenEncryptor, eventService);
    }

    // Package-private test constructor: lets unit tests dial down responseTimeout (for the
    // read-timeout scenario) and overallTimeout (for the 30s-budget scenarios — overall timeout,
    // 429 large retry_after, 429 missing retry_after, 429 negative retry_after) so the suite
    // finishes in seconds instead of minutes.
    TelegramSender(RestClient.Builder builder,
                   String baseUrl,
                   Duration responseTimeout,
                   Duration overallTimeout,
                   BotRepository botRepository,
                   TokenEncryptor tokenEncryptor,
                   EventService eventService) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(TelegramApiClient.CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(responseTimeout);
        DefaultUriBuilderFactory uriBuilderFactory = new DefaultUriBuilderFactory(baseUrl);
        uriBuilderFactory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);
        this.restClient = builder
                .uriBuilderFactory(uriBuilderFactory)
                .requestFactory(requestFactory)
                .build();
        this.botRepository = botRepository;
        this.tokenEncryptor = tokenEncryptor;
        this.eventService = eventService;
        this.overallTimeout = overallTimeout;
    }

    public SentMessage sendText(String botId, Long chatId, String text,
                                String parseMode, String ownerId) {
        // Outermost AtomicInteger. Persists across BOTH retry loops: each HTTP attempt increments
        // it once at the request site. The [5xx, 429, 5xx, 200] interleaving invariant asserts
        // attempts==4 — the counter survives 429 outer-loop re-entry into the 5xx inner loop.
        AtomicInteger attempts = new AtomicInteger(0);
        // Captured ONCE at the start of sendText — never re-derived inside the retry loops.
        Instant deadline = Instant.now().plus(overallTimeout);

        try {
            Bot bot = botRepository.findById(botId)
                    .filter(b -> b.getStatus() == BotStatus.CONNECTED)
                    .orElseThrow(() -> AppException.notFound(MESSAGE_BOT_NOT_FOUND));

            SentMessage sm = sendWithRateLimitRetry(bot, botId, chatId, text, parseMode,
                    attempts, deadline);
            eventService.logEvent(ownerId, EVENT_TELEGRAM_MESSAGE_SENT,
                    null, null, sentMetadata(botId, sm));
            return sm;
        } catch (RuntimeException ex) {
            if (isTerminalAuditable(ex)) {
                log.error("Telegram send terminal failure attempts={}: {}",
                        attempts.get(), TelegramApiClient.scrubTokens(ex.getMessage()));
                eventService.logEvent(ownerId, EVENT_TELEGRAM_SEND_FAILED,
                        null, null, failedMetadata(botId, chatId, ex, attempts.get()));
            }
            throw ex;
        }
    }

    // Outer 429 loop wrapping the inner 5xx loop. Per Decision 2, 429 wraps 5xx — Telegram's
    // rate-limit window resets the per-second budget, so we retry from scratch on retry_after.
    private SentMessage sendWithRateLimitRetry(Bot bot, String botId, Long chatId, String text,
                                               String parseMode, AtomicInteger attempts,
                                               Instant deadline) {
        while (true) {
            try {
                return sendWith5xxRetry(bot, botId, chatId, text, parseMode, attempts, deadline);
            } catch (TelegramRateLimitException rate) {
                long delaySeconds = Math.max(0,
                        Math.min(rate.getRetryAfterSeconds(), RATE_LIMIT_DELAY_CAP_SECONDS));
                long jitterMs = ThreadLocalRandom.current().nextLong(0, RATE_LIMIT_JITTER_MAX_MS);
                log.warn("Telegram 429 — waiting {}s + {}ms jitter", delaySeconds, jitterMs);
                long totalSleepMs = delaySeconds * 1000L + jitterMs;
                sleepWithDeadline(totalSleepMs, deadline, attempts);
            }
        }
    }

    private SentMessage sendWith5xxRetry(Bot bot, String botId, Long chatId, String text,
                                         String parseMode, AtomicInteger attempts,
                                         Instant deadline) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            checkDeadline(deadline, attempts);
            try {
                return sendOnce(bot, botId, chatId, text, parseMode, attempts);
            } catch (TelegramRateLimitException re) {
                // 429 is handled by the outer loop, NOT a transient.
                throw re;
            } catch (RuntimeException ex) {
                if (!TelegramApiClient.isTransient(ex)) {
                    throw ex;
                }
                if (attempt == MAX_RETRIES) {
                    throw new TelegramSendException(null, "transient_failure_exhausted",
                            attempts.get());
                }
                log.warn("Telegram transient retry #{} after error: {}",
                        attempt + 1, TelegramApiClient.scrubTokens(ex.getMessage()));
                long backoffMs = Math.min(
                        INITIAL_5XX_BACKOFF.toMillis() << attempt,
                        MAX_5XX_BACKOFF.toMillis());
                sleepWithDeadline(backoffMs, deadline, attempts);
            }
        }
        // Unreachable: the attempt==MAX_RETRIES branch always throws.
        throw new TelegramSendException(null, "transient_failure_exhausted", attempts.get());
    }

    private SentMessage sendOnce(Bot bot, String botId, Long chatId, String text,
                                 String parseMode, AtomicInteger attempts) {
        byte[] iv;
        byte[] ct;
        try {
            iv = Base64.getDecoder().decode(bot.getEncryptedTokenIv());
            ct = Base64.getDecoder().decode(bot.getEncryptedTokenCiphertext());
        } catch (IllegalArgumentException e) {
            // Base64 decode failed = data corruption at storage boundary → user-fixable 422.
            throw new BotTokenInvalidException(botId, "decryption failed");
        }
        // tokenEncryptor.decrypt deliberately OUTSIDE the catch: IllegalStateException
        // (AEAD-tag failure or misconfigured bean) and IllegalArgumentException from wrong-length
        // IV must propagate as 500 — indistinguishable failure modes that ops monitoring must see.
        String token = tokenEncryptor.decrypt(iv, ct);

        try {
            TelegramApiClient.requireValidTokenShape(token);
        } catch (IllegalArgumentException e) {
            throw new BotTokenInvalidException(botId, "invalid token shape after decrypt");
        }

        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        if (parseMode != null) {
            body.put("parse_mode", parseMode);
        }

        attempts.incrementAndGet();
        TelegramSendResult<JsonNode> result = restClient.post()
                .uri("/bot{token}/sendMessage", token)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                    throw map4xx(resp, botId, attempts);
                })
                .body(new ParameterizedTypeReference<TelegramSendResult<JsonNode>>() {});
        return mapBodyToSentMessage(result, chatId, attempts);
    }

    private RuntimeException map4xx(ClientHttpResponse response, String botId, AtomicInteger attempts) {
        int rawStatus = response.getStatusCode().value();
        HttpStatus status = HttpStatus.resolve(rawStatus);
        TelegramSendResult<JsonNode> result = readBodyOrEmpty(response);
        return toThrowable(status, rawStatus, result, botId, attempts);
    }

    private TelegramSendResult<JsonNode> readBodyOrEmpty(ClientHttpResponse response) {
        try {
            byte[] bytes = response.getBody().readAllBytes();
            if (bytes.length == 0) {
                return emptyResult();
            }
            return STATIC_OBJECT_MAPPER.readValue(bytes, SEND_RESULT_TYPE);
        } catch (Exception ex) {
            return emptyResult();
        }
    }

    private RuntimeException toThrowable(HttpStatus status, int rawStatus,
                                         TelegramSendResult<JsonNode> result,
                                         String botId, AtomicInteger attempts) {
        String scrubbed = TelegramApiClient.scrubTokens(result.description());
        if (status == HttpStatus.UNAUTHORIZED) {
            return new BotTokenInvalidException(botId, "Token is invalid or revoked");
        }
        if (rawStatus == 429) {
            int retryAfter = 0;
            TelegramSendParameters params = result.parameters();
            if (params != null && params.retry_after() != null) {
                retryAfter = params.retry_after();
            }
            log.warn("Telegram 429 retry_after={}s: {}", retryAfter, scrubbed);
            return new TelegramRateLimitException(retryAfter);
        }
        log.warn("Telegram 4xx ({}): {}", rawStatus, scrubbed);
        return new TelegramSendException(rawStatus, scrubbed, attempts.get());
    }

    private SentMessage mapBodyToSentMessage(TelegramSendResult<JsonNode> result,
                                             Long fallbackChatId,
                                             AtomicInteger attempts) {
        if (result == null) {
            // RestClient.body(...) returns null on 204 No Content / empty body. Telegram never
            // sends this on /sendMessage, but defend defensively rather than NPE.
            log.warn("Telegram empty response body");
            throw new TelegramSendException(null, "empty response body", attempts.get());
        }
        JsonNode resultNode = result.result();
        if (result.ok() && resultNode != null && resultNode.has("message_id")) {
            Long messageId = resultNode.get("message_id").asLong();
            Long chatId = extractChatId(resultNode, fallbackChatId);
            return new SentMessage(chatId, messageId, Instant.now());
        }
        String scrubbed = TelegramApiClient.scrubTokens(result.description());
        log.warn("Telegram ok=false or missing message_id: {}", scrubbed);
        throw new TelegramSendException(null, scrubbed, attempts.get());
    }

    private static Long extractChatId(JsonNode resultNode, Long fallback) {
        // JsonNode.get(String) returns Jackson's MissingNode (not Java null) for absent fields;
        // .has(...) is the correct presence check.
        if (resultNode.has("chat") && resultNode.get("chat").has("id")) {
            return resultNode.get("chat").get("id").asLong();
        }
        return fallback;
    }

    // Deadline check: throws TelegramSendException("timeout") if the wall-clock budget is gone.
    // Checked before each sleep and before each new retry attempt (per Decision 2 ordering rule).
    private void checkDeadline(Instant deadline, AtomicInteger attempts) {
        if (Instant.now().isAfter(deadline)) {
            throw new TelegramSendException(null, "timeout", attempts.get());
        }
    }

    // Sleep with deadline check before AND after. Pre-check fires if the budget is already past;
    // sleep is clamped to the remaining budget so a long retry_after never overshoots the 30s
    // wall-clock ceiling; post-check guards against clock drift / VT-scheduling latency.
    private void sleepWithDeadline(long durationMs, Instant deadline, AtomicInteger attempts) {
        checkDeadline(deadline, attempts);
        long remainingMs = Duration.between(Instant.now(), deadline).toMillis();
        long actualMs = Math.min(durationMs, remainingMs);
        if (actualMs > 0) {
            try {
                Thread.sleep(actualMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new TelegramSendException(null, "interrupted", attempts.get());
            }
        }
        checkDeadline(deadline, attempts);
    }

    private static TelegramSendResult<JsonNode> emptyResult() {
        return new TelegramSendResult<>(false, null, null, null, null);
    }

    private static boolean isTerminalAuditable(Throwable ex) {
        // Audit emission is reserved for terminal send failures. Pre-HTTP AppException(404)
        // (bot-not-found / disconnected) and any leaked internal TelegramRateLimitException
        // are NOT auditable as "send" failures.
        if (ex instanceof BotTokenInvalidException) return true;
        return ex instanceof TelegramSendException;
    }

    private static Map<String, Object> sentMetadata(String botId, SentMessage sm) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("botId", botId);
        meta.put("chatId", sm.chatId());
        meta.put("messageId", sm.messageId());
        return meta;
    }

    private static Map<String, Object> failedMetadata(String botId, Long chatId, Throwable ex, int attempts) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("botId", botId);
        meta.put("chatId", chatId);
        meta.put("attempts", attempts);
        // For TelegramSendException we always insert both keys (errorCode/errorDescription) even
        // when null — keeps the metadata schema predictable for event consumers that may rely on
        // .containsKey(...). The "timeout" / "transient_failure_exhausted" / "interrupted"
        // sentinel descriptions are deliberate internal markers (not upstream Telegram strings).
        // BotTokenInvalidException omits both keys per tech-spec error-mapping table.
        if (ex instanceof TelegramSendException tse) {
            meta.put("errorCode", tse.getErrorCode());
            meta.put("errorDescription", tse.getMessage());
        }
        return meta;
    }
}
