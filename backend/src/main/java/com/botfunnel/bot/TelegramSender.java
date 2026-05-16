package com.botfunnel.bot;

import com.botfunnel.bot.dto.SentMessage;
import com.botfunnel.bot.dto.TelegramSendParameters;
import com.botfunnel.bot.dto.TelegramSendResult;
import com.botfunnel.common.AppException;
import com.botfunnel.common.crypto.TokenEncryptor;
import com.botfunnel.events.EventService;
import com.fasterxml.jackson.databind.JsonNode;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.DefaultUriBuilderFactory;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Outbound sender for Telegram {@code sendMessage}. Owns: per-call AES-GCM token decrypt, 5xx
 * exponential backoff (1s/2s/4s, 3 retries), 429 {@code retry_after} loop wrapping the 5xx loop,
 * overall 30s timeout, typed-exception mapping, audit-event emission, and token-scrubbed logging.
 *
 * <p>Plaintext-token lifetime: decrypted token is captured in the chain closure (Mono pipeline)
 * and reachable until subscription completes (worst case ~30s). Never stored, never logged,
 * never serialized, never pushed to background schedulers. Closure capture follows the existing
 * BotService convention (BotService.java:216-220).
 */
@Component
public class TelegramSender {

    private static final Logger log = LoggerFactory.getLogger(TelegramSender.class);

    static final String EVENT_TELEGRAM_MESSAGE_SENT = "telegram_message_sent";
    static final String EVENT_TELEGRAM_SEND_FAILED = "telegram_send_failed";

    // Anti-enumeration 404 message. Kept in sync with BotService.java:57's MESSAGE_BOT_NOT_FOUND
    // constant — both must produce identical wire shape ({status: 404, code: null,
    // message: "Bot not found"}) so a system-context caller can't distinguish "bot does not exist"
    // from "bot exists but is not CONNECTED" (Decision 7).
    static final String MESSAGE_BOT_NOT_FOUND = "Bot not found";

    private static final Duration DEFAULT_OVERALL_TIMEOUT = Duration.ofSeconds(30);
    private static final int RATE_LIMIT_DELAY_CAP_SECONDS = 30;
    private static final int RATE_LIMIT_JITTER_MAX_MS = 200;

    private final WebClient webClient;
    private final BotRepository botRepository;
    private final TokenEncryptor tokenEncryptor;
    private final EventService eventService;
    private final Duration overallTimeout;

    @Autowired
    public TelegramSender(WebClient.Builder builder,
                          @Value("${app.telegram.base-url}") String baseUrl,
                          BotRepository botRepository,
                          TokenEncryptor tokenEncryptor,
                          EventService eventService) {
        this(builder, baseUrl, TelegramApiClient.DEFAULT_RESPONSE_TIMEOUT, DEFAULT_OVERALL_TIMEOUT,
                botRepository, tokenEncryptor, eventService);
    }

    // Package-private test constructor: lets unit tests dial down responseTimeout (for the
    // read-timeout scenario) and overallTimeout (for the 30s-budget scenarios — overall-timeout,
    // 429-large-retry-after, 429-missing-retry-after, 429-negative-retry-after) so the suite
    // finishes in seconds instead of minutes. Mirrors TelegramApiClient.java:55's pattern.
    TelegramSender(WebClient.Builder builder,
                   String baseUrl,
                   Duration responseTimeout,
                   Duration overallTimeout,
                   BotRepository botRepository,
                   TokenEncryptor tokenEncryptor,
                   EventService eventService) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(responseTimeout)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) TelegramApiClient.CONNECT_TIMEOUT.toMillis());
        DefaultUriBuilderFactory uriBuilderFactory = new DefaultUriBuilderFactory(baseUrl);
        uriBuilderFactory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);
        this.webClient = builder
                .uriBuilderFactory(uriBuilderFactory)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        this.botRepository = botRepository;
        this.tokenEncryptor = tokenEncryptor;
        this.eventService = eventService;
        this.overallTimeout = overallTimeout;
    }

    public Mono<SentMessage> sendText(String botId, Long chatId, String text,
                                      String parseMode, String ownerId) {
        // Outermost-closure AtomicInteger. Persists across BOTH retry loops; a counter declared
        // inside the per-attempt request supplier would reset on every 429 resubscription.
        AtomicInteger attempts = new AtomicInteger(0);

        return botRepository.findById(botId)
                .filter(bot -> bot.getStatus() == BotStatus.CONNECTED)
                .switchIfEmpty(Mono.error(AppException.notFound(MESSAGE_BOT_NOT_FOUND)))
                .flatMap(bot -> sendOnce(bot, botId, chatId, text, parseMode, attempts))
                .timeout(overallTimeout)
                .onErrorMap(TimeoutException.class,
                        ex -> new TelegramSendException(null, "timeout", attempts.get()))
                .doOnSuccess(sm -> {
                    if (sm != null) {
                        eventService.logEvent(ownerId, EVENT_TELEGRAM_MESSAGE_SENT,
                                null, null, sentMetadata(botId, sm));
                    }
                })
                .doOnError(ex -> {
                    if (isTerminalAuditable(ex)) {
                        log.error("Telegram send terminal failure attempts={}: {}",
                                attempts.get(), TelegramApiClient.scrubTokens(ex.getMessage()));
                        eventService.logEvent(ownerId, EVENT_TELEGRAM_SEND_FAILED,
                                null, null, failedMetadata(botId, chatId, ex, attempts.get()));
                    }
                });
    }

    private Mono<SentMessage> sendOnce(Bot bot, String botId, Long chatId, String text,
                                       String parseMode, AtomicInteger attempts) {
        byte[] iv;
        byte[] ct;
        try {
            iv = Base64.getDecoder().decode(bot.getEncryptedTokenIv());
            ct = Base64.getDecoder().decode(bot.getEncryptedTokenCiphertext());
        } catch (IllegalArgumentException e) {
            // Base64 decode failed = data corruption at storage boundary → user-fixable 422.
            return Mono.error(new BotTokenInvalidException(botId, "decryption failed"));
        }
        // tokenEncryptor.decrypt deliberately OUTSIDE the catch (Risk R7): IllegalStateException
        // (AEAD-tag failure or misconfigured bean) and IllegalArgumentException from wrong-length
        // IV must propagate as 500 — indistinguishable failure modes that ops monitoring must see.
        String token = tokenEncryptor.decrypt(iv, ct);

        try {
            TelegramApiClient.requireValidTokenShape(token);
        } catch (IllegalArgumentException e) {
            return Mono.error(new BotTokenInvalidException(botId, "invalid token shape after decrypt"));
        }

        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        if (parseMode != null) {
            body.put("parse_mode", parseMode);
        }

        return webClient.post()
                .uri("/bot{token}/sendMessage", token)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response -> map4xx(response, botId, attempts))
                .bodyToMono(new ParameterizedTypeReference<TelegramSendResult<JsonNode>>() {})
                .doOnSubscribe(s -> attempts.incrementAndGet())
                .flatMap(result -> mapBodyToSentMessage(result, chatId, attempts))
                .retryWhen(buildTransientRetry(attempts))
                .retryWhen(buildRateLimitRetry());
    }

    private Mono<? extends Throwable> map4xx(ClientResponse response, String botId, AtomicInteger attempts) {
        HttpStatus status = HttpStatus.resolve(response.statusCode().value());
        int rawStatus = response.statusCode().value();
        return response.bodyToMono(new ParameterizedTypeReference<TelegramSendResult<JsonNode>>() {})
                .onErrorResume(ex -> Mono.just(emptyResult()))
                .defaultIfEmpty(emptyResult())
                .map(result -> toThrowable(status, rawStatus, result, botId, attempts));
    }

    private Throwable toThrowable(HttpStatus status, int rawStatus,
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

    private Mono<SentMessage> mapBodyToSentMessage(TelegramSendResult<JsonNode> result,
                                                   Long fallbackChatId,
                                                   AtomicInteger attempts) {
        JsonNode resultNode = result.result();
        if (result.ok() && resultNode != null && resultNode.has("message_id")) {
            Long messageId = resultNode.get("message_id").asLong();
            Long chatId = extractChatId(resultNode, fallbackChatId);
            return Mono.just(new SentMessage(chatId, messageId, Instant.now()));
        }
        String scrubbed = TelegramApiClient.scrubTokens(result.description());
        log.warn("Telegram ok=false or missing message_id: {}", scrubbed);
        return Mono.error(new TelegramSendException(null, scrubbed, attempts.get()));
    }

    private static Long extractChatId(JsonNode resultNode, Long fallback) {
        // JsonNode.get(String) returns Jackson's MissingNode (not Java null) for absent fields;
        // .has(...) is the correct presence check.
        if (resultNode.has("chat") && resultNode.get("chat").has("id")) {
            return resultNode.get("chat").get("id").asLong();
        }
        return fallback;
    }

    private Retry buildTransientRetry(AtomicInteger attempts) {
        return Retry.backoff(3, Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(4))
                .filter(TelegramApiClient::isTransient)
                .doBeforeRetry(rs -> log.warn("Telegram transient retry #{} after error: {}",
                        rs.totalRetries() + 1,
                        TelegramApiClient.scrubTokens(rs.failure().getMessage())))
                .onRetryExhaustedThrow((spec, signal) ->
                        new TelegramSendException(null, "transient_failure_exhausted", attempts.get()));
    }

    private Retry buildRateLimitRetry() {
        return Retry.from(companion -> companion.flatMap(rs -> {
            if (!(rs.failure() instanceof TelegramRateLimitException tr)) {
                return Mono.error(rs.failure());
            }
            long delaySeconds = Math.max(0, Math.min(tr.getRetryAfterSeconds(), RATE_LIMIT_DELAY_CAP_SECONDS));
            long jitterMs = ThreadLocalRandom.current().nextLong(0, RATE_LIMIT_JITTER_MAX_MS);
            log.warn("Telegram 429 — waiting {}s + {}ms jitter", delaySeconds, jitterMs);
            return Mono.delay(Duration.ofSeconds(delaySeconds).plusMillis(jitterMs));
        }));
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
        if (ex instanceof TelegramSendException tse) {
            meta.put("errorCode", tse.getErrorCode());
            meta.put("errorDescription", tse.getMessage());
        }
        return meta;
    }
}
