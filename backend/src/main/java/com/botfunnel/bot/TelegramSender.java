package com.botfunnel.bot;

import com.botfunnel.bot.dto.AlbumItem;
import com.botfunnel.bot.dto.SentMessage;
import com.botfunnel.bot.dto.TelegramSendParameters;
import com.botfunnel.bot.dto.TelegramSendResult;
import com.botfunnel.common.AppException;
import com.botfunnel.common.crypto.TokenEncryptor;
import com.botfunnel.events.EventService;
import com.botfunnel.subscriber.SubscriberService;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Outbound sender for Telegram {@code sendMessage} / {@code sendPhoto} / {@code sendVideo} /
 * {@code sendAudio} / {@code sendDocument} / {@code sendMediaGroup}. Owns: per-call AES-GCM token decrypt, 5xx
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

    // Greppable WARN constant for the Decision 4 subscriber-hook fail path: a downstream
    // mark-*ByChatId failure (Mongo down, etc.) must NOT swallow or alter the original send
    // exception (mirrors the SUBSCRIBER_START_RATE_REDIS_FAIL_OPEN convention in SubscriberServiceImpl).
    static final String TELEGRAM_SENDER_SUBSCRIBER_HOOK_FAILED =
            "TELEGRAM_SENDER_SUBSCRIBER_HOOK_FAILED: subscriber CRM flip failed, original send error preserved: {}";

    // Telegram 400 description marker for a removed chat / deleted account (case-insensitive match
    // on the post-scrub description). Observed as both "chat not found" and "Bad Request: chat not found".
    private static final String CHAT_NOT_FOUND_MARKER = "chat not found";

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
    private final SubscriberService subscriberService;
    private final Duration overallTimeout;

    @Autowired
    public TelegramSender(RestClient.Builder builder,
                          @Value("${app.telegram.base-url}") String baseUrl,
                          BotRepository botRepository,
                          TokenEncryptor tokenEncryptor,
                          EventService eventService,
                          SubscriberService subscriberService) {
        this(builder, baseUrl, TelegramApiClient.DEFAULT_RESPONSE_TIMEOUT, DEFAULT_OVERALL_TIMEOUT,
                botRepository, tokenEncryptor, eventService, subscriberService);
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
                   EventService eventService,
                   SubscriberService subscriberService) {
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
        this.subscriberService = subscriberService;
        this.overallTimeout = overallTimeout;
    }

    public SentMessage sendText(String botId, Long chatId, String text,
                                String parseMode, String ownerId) {
        // Backward-compatible 5-arg path (3 production call-sites + many tests). Delegates to the
        // 6-arg overload with replyMarkup=null so the only-if-non-null body logic lives in one place.
        return sendText(botId, chatId, text, parseMode, ownerId, null);
    }

    /**
     * 6-arg overload adding an optional inline keyboard. {@code replyMarkup} is a serializable
     * object (typically a {@code Map}) shaped as {@code {"inline_keyboard":[[{text, callback_data|url}]]}};
     * it is placed into the {@code /sendMessage} body under the {@code reply_markup} key
     * <strong>only when non-null</strong> — the same only-if-non-null idiom as {@code parse_mode}.
     * This is a body field, not a method-tail parameter: {@code ownerId} stays last so existing
     * call-sites and the audit/retry/subscriber-hook pipeline are untouched.
     */
    public SentMessage sendText(String botId, Long chatId, String text,
                                String parseMode, String ownerId, Object replyMarkup) {
        // Body for /sendMessage. parse_mode + reply_markup added only when non-null (mirrored by /sendPhoto).
        Map<String, Object> contentFields = new HashMap<>();
        contentFields.put("text", text);
        if (parseMode != null) {
            contentFields.put("parse_mode", parseMode);
        }
        if (replyMarkup != null) {
            contentFields.put("reply_markup", replyMarkup);
        }
        return send(botId, chatId, "/bot{token}/sendMessage", contentFields, ownerId);
    }

    // Greppable WARN constant for the Decision 8 best-effort answerCallbackQuery path. A failed ack
    // (Telegram 5xx exhausted, timeout, bot-not-found/disconnected, token issue) must NOT propagate
    // — it would otherwise block funnel advance on a callback. Mirrors the
    // TELEGRAM_SENDER_SUBSCRIBER_HOOK_FAILED swallow-and-warn convention above.
    static final String TELEGRAM_ANSWER_CALLBACK_FAILED =
            "TELEGRAM_ANSWER_CALLBACK_FAILED: best-effort spinner ack failed (funnel advance not blocked): {}";

    /**
     * Best-effort {@code answerCallbackQuery} (Decision 8): clears the spinner on a tapped inline
     * button. Reuses the class's per-call AES-GCM decrypt, CONNECTED filter, 5xx/429 retry-backoff
     * loop, 30s deadline, and token-scrubbed logging. Unlike content sends, this is a non-content
     * ack: no audit event and no subscriber hook. Any failure is scrubbed, WARN-logged, and
     * swallowed so the funnel keeps advancing.
     *
     * @param text optional toast text; the {@code text} body key is omitted when null.
     */
    public void answerCallbackQuery(String botId, String callbackQueryId, String text) {
        Map<String, Object> contentFields = new HashMap<>();
        contentFields.put("callback_query_id", callbackQueryId);
        if (text != null) {
            contentFields.put("text", text);
        }
        try {
            AtomicInteger attempts = new AtomicInteger(0);
            Instant deadline = Instant.now().plus(overallTimeout);
            Bot bot = botRepository.findById(botId)
                    .filter(b -> b.getStatus() == BotStatus.CONNECTED)
                    .orElseThrow(() -> AppException.notFound(MESSAGE_BOT_NOT_FOUND));
            // Reuses the same retry/deadline seam as content sends; the ack response
            // ({"ok":true,"result":true}) carries no message_id, so we discard the raw result.
            sendWithRateLimitRetry(bot, botId, null, "/bot{token}/answerCallbackQuery",
                    contentFields, attempts, deadline);
        } catch (RuntimeException ex) {
            log.warn(TELEGRAM_ANSWER_CALLBACK_FAILED, TelegramApiClient.scrubTokens(ex.getMessage()));
        }
    }

    public SentMessage sendPhoto(String botId, Long chatId, String imageUrl, String caption,
                                 String parseMode, String ownerId) {
        return sendSingleMedia(botId, chatId, "/bot{token}/sendPhoto", "photo",
                imageUrl, caption, parseMode, ownerId);
    }

    /**
     * {@code /sendVideo} — thin wrapper over the shared {@link #send} path (Decision 5: no new heavy
     * resources). Inherits the CONNECTED filter, 5xx/429 retry-backoff loop, 30s deadline, audit
     * events, Decision-4 subscriber hook, and token-scrubbed logging unchanged. {@code videoUrl} is a
     * URL or {@code file_id} — Telegram fetches it; the backend never dereferences it (Decision 6).
     */
    public SentMessage sendVideo(String botId, Long chatId, String videoUrl, String caption,
                                 String parseMode, String ownerId) {
        return sendSingleMedia(botId, chatId, "/bot{token}/sendVideo", "video",
                videoUrl, caption, parseMode, ownerId);
    }

    /** {@code /sendAudio} — thin wrapper over {@link #send} (see {@link #sendVideo}). */
    public SentMessage sendAudio(String botId, Long chatId, String audioUrl, String caption,
                                 String parseMode, String ownerId) {
        return sendSingleMedia(botId, chatId, "/bot{token}/sendAudio", "audio",
                audioUrl, caption, parseMode, ownerId);
    }

    /** {@code /sendDocument} — thin wrapper over {@link #send} (see {@link #sendVideo}). */
    public SentMessage sendDocument(String botId, Long chatId, String fileUrl, String caption,
                                    String parseMode, String ownerId) {
        return sendSingleMedia(botId, chatId, "/bot{token}/sendDocument", "document",
                fileUrl, caption, parseMode, ownerId);
    }

    // Shared single-media body shape for sendPhoto/sendVideo/sendAudio/sendDocument. Only the
    // endpoint path and the media content key (photo/video/audio/document) differ; caption/parse_mode
    // are added only when non-null (the sendText idiom). The media value is a URL or file_id —
    // Telegram fetches it; the backend never dereferences it (no SSRF, Decision 6).
    private SentMessage sendSingleMedia(String botId, Long chatId, String endpoint, String mediaKey,
                                        String mediaUrl, String caption, String parseMode, String ownerId) {
        Map<String, Object> contentFields = new HashMap<>();
        contentFields.put(mediaKey, mediaUrl);
        if (caption != null) {
            contentFields.put("caption", caption);
        }
        if (parseMode != null) {
            contentFields.put("parse_mode", parseMode);
        }
        return send(botId, chatId, endpoint, contentFields, ownerId);
    }

    /**
     * {@code /sendMediaGroup} — sends a Telegram media group (album, 2–10 items) and maps the
     * <strong>array</strong> {@code Message} response (Decision 5). Reuses the exact same shared seam
     * as {@link #send}: CONNECTED filter, 5xx/429 retry-backoff loop, 30s deadline, audit events,
     * Decision-4 subscriber hook, and token-scrubbed logging. The <em>only</em> delta is the response
     * mapper — {@link #mapBodyToSentAlbum} instead of {@link #mapBodyToSentMessage} (the latter assumes
     * a single {@code message_id} and would fail on the array response).
     *
     * <p>{@code caption}/{@code parse_mode} are meaningful only on the first item (Decision 5); the
     * builder drops them on later items. Each item's media value is a URL or {@code file_id} — Telegram
     * fetches it; the backend never dereferences it (no SSRF, Decision 6).
     *
     * @return one {@link SentMessage} per delivered album element, in order.
     */
    public java.util.List<SentMessage> sendMediaGroup(String botId, Long chatId,
                                                      java.util.List<AlbumItem> items, String ownerId) {
        Map<String, Object> contentFields = new HashMap<>();
        contentFields.put("media", buildMediaArray(items));
        return sendMapped(botId, chatId, "/bot{token}/sendMediaGroup", contentFields, ownerId,
                (result, attempts) -> mapBodyToSentAlbum(result, chatId, attempts),
                sent -> albumSentMetadata(botId, sent));
    }

    // Builds the Telegram media-group "media" JSON array. caption/parse_mode are emitted only on the
    // FIRST element (Decision 5) regardless of what later items carry — defensive, so a caller that
    // populated later captions can't leak them onto the wire.
    private static java.util.List<Map<String, Object>> buildMediaArray(java.util.List<AlbumItem> items) {
        java.util.List<Map<String, Object>> media = new java.util.ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            AlbumItem item = items.get(i);
            Map<String, Object> element = new HashMap<>();
            element.put("type", item.type());
            element.put("media", item.mediaUrl());
            if (i == 0) {
                if (item.caption() != null) {
                    element.put("caption", item.caption());
                }
                if (item.parseMode() != null) {
                    element.put("parse_mode", item.parseMode());
                }
            }
            media.add(element);
        }
        return media;
    }

    // Shared orchestration for the single-message sends (sendText / sendPhoto / sendVideo /
    // sendAudio / sendDocument). Delegates to the generic sendMapped seam with the single-message
    // mapper + metadata; kept as a named method so existing call-sites and tests are untouched.
    private SentMessage send(String botId, Long chatId, String endpoint,
                             Map<String, Object> contentFields, String ownerId) {
        return sendMapped(botId, chatId, endpoint, contentFields, ownerId,
                (result, attempts) -> mapBodyToSentMessage(result, chatId, attempts),
                sm -> sentMetadata(botId, sm));
    }

    // Generic orchestration for every outbound content send. The only per-method differences are the
    // endpoint path, the request body content fields, the response MAPPER, and the success-event
    // metadata builder — everything else (CONNECTED filter, retry/backoff/429 loop, 30s deadline,
    // audit, Decision-4 subscriber hook, typed failure mapping, token scrubbing) is identical and
    // lives here so the failure-matrix is byte-identical across single-message and album sends.
    private <R> R sendMapped(String botId, Long chatId, String endpoint,
                             Map<String, Object> contentFields, String ownerId,
                             java.util.function.BiFunction<TelegramSendResult<JsonNode>, AtomicInteger, R> mapper,
                             java.util.function.Function<R, Map<String, Object>> successMetadata) {
        // Outermost AtomicInteger. Persists across BOTH retry loops: each HTTP attempt increments
        // it once at the request site. The [5xx, 429, 5xx, 200] interleaving invariant asserts
        // attempts==4 — the counter survives 429 outer-loop re-entry into the 5xx inner loop.
        AtomicInteger attempts = new AtomicInteger(0);
        // Captured ONCE at the start of the send — never re-derived inside the retry loops.
        Instant deadline = Instant.now().plus(overallTimeout);

        // Promoted to method scope so the catch block can resolve projectId from the same Bot
        // instance (no extra findById round-trip). null only if findById threw before assignment —
        // in which case the exception is AppException(404), not TelegramSendException, so the hook
        // dispatch is naturally skipped.
        Bot bot = null;
        try {
            bot = botRepository.findById(botId)
                    .filter(b -> b.getStatus() == BotStatus.CONNECTED)
                    .orElseThrow(() -> AppException.notFound(MESSAGE_BOT_NOT_FOUND));

            TelegramSendResult<JsonNode> result = sendWithRateLimitRetry(bot, botId, chatId, endpoint,
                    contentFields, attempts, deadline);
            R mapped = mapper.apply(result, attempts);
            eventService.logEvent(ownerId, EVENT_TELEGRAM_MESSAGE_SENT,
                    null, null, successMetadata.apply(mapped));
            return mapped;
        } catch (RuntimeException ex) {
            if (isTerminalAuditable(ex)) {
                log.error("Telegram send terminal failure attempts={}: {}",
                        attempts.get(), TelegramApiClient.scrubTokens(ex.getMessage()));
                eventService.logEvent(ownerId, EVENT_TELEGRAM_SEND_FAILED,
                        null, null, failedMetadata(botId, chatId, ex, attempts.get()));
            }
            // Decision 4: AFTER the audit (the durable record), flip the subscriber on a terminal
            // 403/400-chat-not-found. The mark-* call is the secondary side-effect and must never
            // alter or swallow the original send exception.
            dispatchSubscriberHook(ex, bot, chatId);
            throw ex;
        }
    }

    // Decision 4 subscriber hook. Routes on the typed TerminalReason carried by the exception (no
    // substring sniffing here). A failure inside mark-* is caught locally, WARN-logged with a
    // greppable constant, and never propagates — the original send exception is what the caller sees.
    private void dispatchSubscriberHook(RuntimeException ex, Bot bot, Long chatId) {
        if (bot == null || !(ex instanceof TelegramSendException tse)) {
            return;
        }
        TelegramSendException.TerminalReason reason = tse.getTerminalReason();
        if (reason != TelegramSendException.TerminalReason.BLOCKED_BY_USER
                && reason != TelegramSendException.TerminalReason.CHAT_NOT_FOUND) {
            return;
        }
        try {
            // bot.getTelegramBotId() (Long) — NOT the Mongo String botId. Subscribers are keyed by
            // (projectId, telegramBotId, telegramChatId); passing the String id would never match.
            if (reason == TelegramSendException.TerminalReason.BLOCKED_BY_USER) {
                subscriberService.markBlockedByChatId(bot.getProjectId(), bot.getTelegramBotId(), chatId);
            } else {
                subscriberService.markDeletedByChatId(bot.getProjectId(), bot.getTelegramBotId(), chatId);
            }
        } catch (RuntimeException hookEx) {
            log.warn(TELEGRAM_SENDER_SUBSCRIBER_HOOK_FAILED,
                    TelegramApiClient.scrubTokens(hookEx.getMessage()));
        }
    }

    // Outer 429 loop wrapping the inner 5xx loop. Per Decision 2, 429 wraps 5xx — Telegram's
    // rate-limit window resets the per-second budget, so we retry from scratch on retry_after.
    private TelegramSendResult<JsonNode> sendWithRateLimitRetry(Bot bot, String botId, Long chatId,
                                               String endpoint, Map<String, Object> contentFields,
                                               AtomicInteger attempts, Instant deadline) {
        while (true) {
            try {
                return sendWith5xxRetry(bot, botId, chatId, endpoint, contentFields, attempts, deadline);
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

    private TelegramSendResult<JsonNode> sendWith5xxRetry(Bot bot, String botId, Long chatId,
                                         String endpoint, Map<String, Object> contentFields,
                                         AtomicInteger attempts, Instant deadline) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            checkDeadline(deadline, attempts);
            try {
                return sendOnce(bot, botId, chatId, endpoint, contentFields, attempts);
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

    private TelegramSendResult<JsonNode> sendOnce(Bot bot, String botId, Long chatId, String endpoint,
                                 Map<String, Object> contentFields, AtomicInteger attempts) {
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

        // chat_id is common to content endpoints; the caller-supplied contentFields carry the
        // endpoint-specific keys (text / photo+caption + parse_mode + reply_markup). Copy into a
        // fresh map so the shared contentFields instance is never mutated across retry attempts.
        // chatId is null for non-content acks (answerCallbackQuery) — omit the key in that case.
        Map<String, Object> body = new HashMap<>(contentFields);
        if (chatId != null) {
            body.put("chat_id", chatId);
        }

        attempts.incrementAndGet();
        return restClient.post()
                .uri(endpoint, token)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                    throw map4xx(resp, botId, attempts);
                })
                .body(new ParameterizedTypeReference<TelegramSendResult<JsonNode>>() {});
    }

    private RuntimeException map4xx(ClientHttpResponse response, String botId, AtomicInteger attempts) throws java.io.IOException {
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
        return new TelegramSendException(rawStatus, scrubbed, attempts.get(),
                terminalReasonFor(rawStatus, scrubbed));
    }

    // Decision 4 mapping (colocated with the rest of toThrowable): 403 → BLOCKED_BY_USER;
    // 400 whose post-scrub description contains "chat not found" (case-insensitive) → CHAT_NOT_FOUND;
    // everything else (incl. 400 with empty/null/other description) → OTHER. 401 (token invalid) and
    // 429 (rate limit) never reach here — they are mapped to dedicated exception types above.
    private static TelegramSendException.TerminalReason terminalReasonFor(int rawStatus, String description) {
        if (rawStatus == 403) {
            return TelegramSendException.TerminalReason.BLOCKED_BY_USER;
        }
        if (rawStatus == 400 && description != null
                && description.toLowerCase(Locale.ROOT).contains(CHAT_NOT_FOUND_MARKER)) {
            return TelegramSendException.TerminalReason.CHAT_NOT_FOUND;
        }
        return TelegramSendException.TerminalReason.OTHER;
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

    // Album array-mapper (Decision 5). /sendMediaGroup returns result as a JSON ARRAY of Message
    // objects, not a single message_id — mapBodyToSentMessage would fail on it. Maps each element
    // into a SentMessage (reusing extractChatId). On null body / ok=false / non-array / empty array /
    // an element missing message_id → throws TelegramSendException AFTER running the raw
    // description through scrubTokens, exactly mirroring mapBodyToSentMessage's token-scrub parity.
    private java.util.List<SentMessage> mapBodyToSentAlbum(TelegramSendResult<JsonNode> result,
                                                          Long fallbackChatId,
                                                          AtomicInteger attempts) {
        if (result == null) {
            log.warn("Telegram empty response body");
            throw new TelegramSendException(null, "empty response body", attempts.get());
        }
        JsonNode resultNode = result.result();
        if (result.ok() && resultNode != null && resultNode.isArray() && !resultNode.isEmpty()) {
            java.util.List<SentMessage> sent = new java.util.ArrayList<>(resultNode.size());
            for (JsonNode element : resultNode) {
                if (!element.has("message_id")) {
                    String scrubbed = TelegramApiClient.scrubTokens(result.description());
                    log.warn("Telegram sendMediaGroup element missing message_id: {}", scrubbed);
                    throw new TelegramSendException(null, scrubbed, attempts.get());
                }
                Long messageId = element.get("message_id").asLong();
                Long chatId = extractChatId(element, fallbackChatId);
                sent.add(new SentMessage(chatId, messageId, Instant.now()));
            }
            return sent;
        }
        String scrubbed = TelegramApiClient.scrubTokens(result.description());
        log.warn("Telegram sendMediaGroup ok=false or missing/empty result array: {}", scrubbed);
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

    // Success metadata for an album send. Mirrors sentMetadata but carries the list of delivered
    // message-ids (one per album element) plus the common chatId — keeps the sent-event schema
    // predictable for consumers while reflecting the multi-message shape.
    private static Map<String, Object> albumSentMetadata(String botId, java.util.List<SentMessage> sent) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("botId", botId);
        if (!sent.isEmpty()) {
            meta.put("chatId", sent.get(0).chatId());
        }
        meta.put("messageIds", sent.stream().map(SentMessage::messageId).toList());
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
