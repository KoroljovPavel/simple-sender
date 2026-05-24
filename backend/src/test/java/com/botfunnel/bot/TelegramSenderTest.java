package com.botfunnel.bot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.botfunnel.bot.dto.SentMessage;
import com.botfunnel.common.AppException;
import com.botfunnel.common.crypto.EncryptedValue;
import com.botfunnel.common.crypto.TokenEncryptor;
import com.botfunnel.events.EventService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramSenderTest {

    private static final String TOKEN = "1234567890:ABCdefGHI_jklMNOpqrSTUvwxYZ0123456789";
    private static final String BOT_ID = "bot-sender-test-1";
    private static final String OWNER_ID = "owner-sender-test-1";
    private static final Long CALLER_CHAT_ID = 999L;
    private static final String TEXT = "Hello from sender test";
    private static final String HEX_KEY_64 = "0".repeat(64);
    // Short overall timeout for the four ~30s-budget scenarios (overall timeout, 429 large
    // retry_after, 429 missing retry_after, 429 negative retry_after). Production constant
    // is 30s; tests dial down to keep the suite fast.
    private static final Duration SHORT_OVERALL_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration SHORT_RESPONSE_TIMEOUT = Duration.ofMillis(200);

    private MockWebServer mockServer;
    private TokenEncryptor encryptor;
    private BotRepository botRepository;
    private EventService eventService;
    private TelegramSender sender;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;
    private String ivB64;
    private String ctB64;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();

        encryptor = new TokenEncryptor(HEX_KEY_64);
        EncryptedValue ev = encryptor.encrypt(TOKEN);
        ivB64 = Base64.getEncoder().encodeToString(ev.iv());
        ctB64 = Base64.getEncoder().encodeToString(ev.ciphertext());

        botRepository = mock(BotRepository.class);
        eventService = mock(EventService.class);

        sender = newSender(TelegramApiClient.DEFAULT_RESPONSE_TIMEOUT, Duration.ofSeconds(30));

        logger = (Logger) LoggerFactory.getLogger(TelegramSender.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        logAppender.stop();
        // Some scenarios cancel mid-request (overall timeout, transport-error sender). Swallow
        // shutdown failures so the test result reflects the actual assertion, not cleanup noise.
        try {
            mockServer.shutdown();
        } catch (Exception ignored) {
            // ignored
        }
    }

    private TelegramSender newSender(Duration responseTimeout, Duration overallTimeout) {
        return new TelegramSender(RestClient.builder(), mockServer.url("/").toString(),
                responseTimeout, overallTimeout, botRepository, encryptor, eventService);
    }

    private Bot connectedBot() {
        Bot bot = new Bot();
        bot.setId(BOT_ID);
        bot.setEncryptedTokenIv(ivB64);
        bot.setEncryptedTokenCiphertext(ctB64);
        bot.setStatus(BotStatus.CONNECTED);
        return bot;
    }

    private void stubFindReturns(Bot bot) {
        when(botRepository.findById(BOT_ID)).thenReturn(Optional.of(bot));
    }

    private void stubFindEmpty() {
        when(botRepository.findById(BOT_ID)).thenReturn(Optional.empty());
    }

    private static MockResponse jsonResponse(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static MockResponse okSendMessage(long messageId, long chatId) {
        return jsonResponse(200, String.format(
                "{\"ok\":true,\"result\":{\"message_id\":%d,\"chat\":{\"id\":%d}}}",
                messageId, chatId));
    }

    private static MockResponse status5xx(int code) {
        return jsonResponse(code, String.format(
                "{\"ok\":false,\"error_code\":%d,\"description\":\"Service Unavailable\"}", code));
    }

    private static MockResponse status5xxWithToken(int code) {
        return jsonResponse(code, String.format(
                "{\"ok\":false,\"error_code\":%d,\"description\":\"failure for token %s\"}",
                code, TOKEN));
    }

    private static MockResponse status429(Integer retryAfter) {
        String paramsBlock = retryAfter == null
                ? ""
                : ",\"parameters\":{\"retry_after\":" + retryAfter + "}";
        return jsonResponse(429, String.format(
                "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests\"%s}", paramsBlock));
    }

    private static MockResponse status429WithToken(int retryAfter) {
        return jsonResponse(429, String.format(
                "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests token %s\","
                        + "\"parameters\":{\"retry_after\":%d}}",
                TOKEN, retryAfter));
    }

    private boolean anyMessageMatches(Level level, Predicate<String> predicate) {
        return logAppender.list.stream()
                .filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(predicate);
    }

    // ---------- Happy path and field shape ----------

    @Test
    void sendText_happyPath_returnsSentMessage_attemptsEqualsOne() throws Exception {
        stubFindReturns(connectedBot());
        mockServer.enqueue(okSendMessage(42L, 5L));

        SentMessage sm = sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID);

        assertThat(sm).isNotNull();
        assertThat(sm.messageId()).isEqualTo(42L);
        assertThat(sm.chatId()).isEqualTo(5L);
        assertThat(mockServer.getRequestCount()).isEqualTo(1);

        RecordedRequest req = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/bot" + TOKEN + "/sendMessage");
        assertThat(req.getMethod()).isEqualTo("POST");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCap = ArgumentCaptor.forClass(Map.class);
        verify(eventService).logEvent(eq(OWNER_ID), eq(TelegramSender.EVENT_TELEGRAM_MESSAGE_SENT),
                isNull(), isNull(), metaCap.capture());
        Map<String, Object> meta = metaCap.getValue();
        assertThat(meta).containsEntry("botId", BOT_ID);
        assertThat(meta).containsEntry("chatId", 5L);
        assertThat(meta).containsEntry("messageId", 42L);
    }

    @Test
    void sendText_parseModeNull_omitsFieldFromBody() throws Exception {
        stubFindReturns(connectedBot());
        mockServer.enqueue(okSendMessage(1L, CALLER_CHAT_ID));

        sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID);

        RecordedRequest req = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        Map<String, Object> body = new ObjectMapper().readValue(
                req.getBody().readUtf8(), new TypeReference<>() {});
        assertThat(body).doesNotContainKey("parse_mode");
        assertThat(body).containsEntry("text", TEXT);
        // Jackson deserialises numeric chat_id as Integer for small values; coerce for comparison.
        assertThat(((Number) body.get("chat_id")).longValue()).isEqualTo(CALLER_CHAT_ID);
    }

    @Test
    void sendText_parseModeHtml_includesFieldInBody() throws Exception {
        stubFindReturns(connectedBot());
        mockServer.enqueue(okSendMessage(1L, CALLER_CHAT_ID));

        sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, "HTML", OWNER_ID);

        RecordedRequest req = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        Map<String, Object> body = new ObjectMapper().readValue(
                req.getBody().readUtf8(), new TypeReference<>() {});
        assertThat(body).containsEntry("parse_mode", "HTML");
    }

    @Test
    void sendText_ownerIdNull_succeeds_eventWrittenWithNullUserId() {
        stubFindReturns(connectedBot());
        mockServer.enqueue(okSendMessage(7L, CALLER_CHAT_ID));

        SentMessage sm = sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, null);

        assertThat(sm).isNotNull();
        verify(eventService).logEvent(isNull(), eq(TelegramSender.EVENT_TELEGRAM_MESSAGE_SENT),
                isNull(), isNull(), any());
    }

    // ---------- 5xx retry semantics ----------

    @Test
    void sendText_5xxThenSuccess_retriesAndReturnsSentMessage_attemptsEqualsTwo() {
        stubFindReturns(connectedBot());
        mockServer.enqueue(status5xx(503));
        mockServer.enqueue(okSendMessage(42L, 5L));

        SentMessage sm = sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID);

        assertThat(sm).isNotNull();
        assertThat(sm.messageId()).isEqualTo(42L);
        assertThat(mockServer.getRequestCount()).isEqualTo(2);
        // Pin success-metadata shape per tech-spec Data Models:
        // sentMetadata = {botId, chatId, messageId} — NO attempts key on success.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCap = ArgumentCaptor.forClass(Map.class);
        verify(eventService).logEvent(eq(OWNER_ID), eq(TelegramSender.EVENT_TELEGRAM_MESSAGE_SENT),
                isNull(), isNull(), metaCap.capture());
        assertThat(metaCap.getValue()).doesNotContainKey("attempts");
        verify(eventService, never()).logEvent(any(), eq(TelegramSender.EVENT_TELEGRAM_SEND_FAILED),
                any(), any(), any());
    }

    @Test
    void sendText_5xxRetryExhausted_throwsTelegramSendException_attemptsEqualsFour() {
        stubFindReturns(connectedBot());
        for (int i = 0; i < 4; i++) {
            mockServer.enqueue(status5xx(503));
        }

        assertThatThrownBy(() -> sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(TelegramSendException.class)
                .satisfies(err -> {
                    TelegramSendException tse = (TelegramSendException) err;
                    assertThat(tse.getErrorCode()).isNull();
                    assertThat(tse.getAttempts()).isEqualTo(4);
                    assertThat(tse.getMessage()).isEqualTo("transient_failure_exhausted");
                });

        assertThat(mockServer.getRequestCount()).isEqualTo(4);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCap = ArgumentCaptor.forClass(Map.class);
        verify(eventService).logEvent(eq(OWNER_ID), eq(TelegramSender.EVENT_TELEGRAM_SEND_FAILED),
                isNull(), isNull(), metaCap.capture());
        assertThat(metaCap.getValue()).containsEntry("attempts", 4);
        assertThat(metaCap.getValue()).containsEntry("errorCode", null);
    }

    @Test
    void sendText_ioExceptionRetriedThenSuccess_attemptsEqualsTwo() {
        stubFindReturns(connectedBot());
        mockServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        mockServer.enqueue(okSendMessage(42L, 5L));

        SentMessage sm = sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID);

        assertThat(sm).isNotNull();
        assertThat(mockServer.getRequestCount()).isEqualTo(2);
        // Success path → no failure event.
        verify(eventService, never()).logEvent(any(), eq(TelegramSender.EVENT_TELEGRAM_SEND_FAILED),
                any(), any(), any());
    }

    // ---------- 429 rate-limit loop ----------

    @Test
    void sendText_429NormalRetryAfter_waitsAndRetries() {
        stubFindReturns(connectedBot());
        mockServer.enqueue(status429(1));
        mockServer.enqueue(okSendMessage(42L, 5L));

        long start = System.nanoTime();
        SentMessage sm = sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(sm).isNotNull();
        assertThat(mockServer.getRequestCount()).isEqualTo(2);
        assertThat(elapsedMs).isGreaterThanOrEqualTo(1000).isLessThan(3000);
    }

    @Test
    void sendText_429RateLimitExceptionNotPropagated() {
        // Override sender to use a short overall timeout so the assertion completes fast: a
        // single 120s retry_after wait would block until the 30s production timeout otherwise.
        sender = newSender(TelegramApiClient.DEFAULT_RESPONSE_TIMEOUT, SHORT_OVERALL_TIMEOUT);
        stubFindReturns(connectedBot());
        mockServer.enqueue(status429(120));

        assertThatThrownBy(() -> sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(TelegramSendException.class)
                .isNotInstanceOf(TelegramRateLimitException.class);
    }

    @Test
    void sendText_429LargeRetryAfter_capsAtOverallTimeout() {
        sender = newSender(TelegramApiClient.DEFAULT_RESPONSE_TIMEOUT, SHORT_OVERALL_TIMEOUT);
        stubFindReturns(connectedBot());
        // Enqueue several to ensure subsequent retries (after delay) also see 429; the test
        // asserts the overall timeout fires before exhaustion.
        for (int i = 0; i < 10; i++) {
            mockServer.enqueue(status429(120));
        }

        long start = System.nanoTime();
        assertThatThrownBy(() -> sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(TelegramSendException.class)
                .hasMessage("timeout");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        // Overall timeout is 3s in this test config; allow generous upper bound for CI variance.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(2500).isLessThan(8000);
    }

    @Test
    void sendText_429MissingRetryAfter_capsAtOverallTimeout() {
        sender = newSender(TelegramApiClient.DEFAULT_RESPONSE_TIMEOUT, SHORT_OVERALL_TIMEOUT);
        stubFindReturns(connectedBot());
        for (int i = 0; i < 50; i++) {
            mockServer.enqueue(status429(null));
        }

        assertThatThrownBy(() -> sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(TelegramSendException.class)
                .hasMessage("timeout");
    }

    @Test
    void sendText_429NegativeRetryAfter_clampsToZero_thenCapsAtOverallTimeout() {
        sender = newSender(TelegramApiClient.DEFAULT_RESPONSE_TIMEOUT, SHORT_OVERALL_TIMEOUT);
        stubFindReturns(connectedBot());
        for (int i = 0; i < 100; i++) {
            mockServer.enqueue(status429(-5));
        }

        assertThatThrownBy(() -> sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(TelegramSendException.class)
                .hasMessage("timeout");

        // No spin loop: under the 3s overall timeout with negative retry_after clamped to 0
        // (plus jitter 0..200ms), there's enough room for a handful of attempts but the
        // enqueued-100 ceiling is never approached.
        assertThat(mockServer.getRequestCount()).isGreaterThanOrEqualTo(2).isLessThanOrEqualTo(100);
    }

    @Test
    void sendText_429And5xxInterleaving_succeeds_attemptsEqualsFour() {
        stubFindReturns(connectedBot());
        mockServer.enqueue(status5xx(503));
        mockServer.enqueue(status429(1));
        mockServer.enqueue(status5xx(503));
        mockServer.enqueue(okSendMessage(42L, 5L));

        SentMessage sm = sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID);

        assertThat(sm).isNotNull();
        // Request count == 4 pins the AtomicInteger semantic: each HTTP attempt increments the
        // counter at the request site, and the counter survives the 429 outer-loop re-entry into
        // the 5xx inner loop (Decision 2: 429 outer wraps 5xx inner). The "no failed event"
        // assertion guards against accidental double-emission across the retry boundary.
        assertThat(mockServer.getRequestCount()).isEqualTo(4);
        // Success-shape: sent-event present without "attempts" key, no failed-event.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCap = ArgumentCaptor.forClass(Map.class);
        verify(eventService).logEvent(eq(OWNER_ID), eq(TelegramSender.EVENT_TELEGRAM_MESSAGE_SENT),
                isNull(), isNull(), metaCap.capture());
        assertThat(metaCap.getValue()).doesNotContainKey("attempts");
        verify(eventService, never()).logEvent(any(), eq(TelegramSender.EVENT_TELEGRAM_SEND_FAILED),
                any(), any(), any());
    }

    // ---------- Terminal 4xx and payload errors ----------

    @Test
    void sendText_401_throwsBotTokenInvalidException_noRetry() {
        stubFindReturns(connectedBot());
        mockServer.enqueue(jsonResponse(401,
                "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}"));

        assertThatThrownBy(() -> sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(BotTokenInvalidException.class)
                .satisfies(err -> {
                    BotTokenInvalidException bti = (BotTokenInvalidException) err;
                    assertThat(bti.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(bti.getCode()).isEqualTo("invalid_bot_token");
                    assertThat(bti.getBotId()).isEqualTo(BOT_ID);
                });

        assertThat(mockServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    void sendText_otherClientError_throwsTelegramSendException_noRetry() {
        stubFindReturns(connectedBot());
        mockServer.enqueue(jsonResponse(400,
                "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat not found\"}"));

        assertThatThrownBy(() -> sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(TelegramSendException.class)
                .satisfies(err -> {
                    TelegramSendException tse = (TelegramSendException) err;
                    assertThat(tse.getErrorCode()).isEqualTo(400);
                    assertThat(tse.getMessage()).contains("chat not found");
                });

        assertThat(mockServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    void sendText_okFalse_throwsTelegramSendException() {
        stubFindReturns(connectedBot());
        mockServer.enqueue(jsonResponse(200,
                "{\"ok\":false,\"description\":\"something bad happened\"}"));

        assertThatThrownBy(() -> sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(TelegramSendException.class)
                .satisfies(err -> {
                    TelegramSendException tse = (TelegramSendException) err;
                    assertThat(tse.getErrorCode()).isNull();
                    assertThat(tse.getMessage()).contains("something bad happened");
                });
    }

    // ---------- Pre-wire guards ----------

    @Test
    void sendText_malformedTokenInBot_throwsBotTokenInvalidException_beforeHttp() {
        // Encrypt a non-token-shape plaintext so decrypt succeeds but requireValidTokenShape fails.
        EncryptedValue ev = encryptor.encrypt("not-a-valid-telegram-token");
        Bot bot = connectedBot();
        bot.setEncryptedTokenIv(Base64.getEncoder().encodeToString(ev.iv()));
        bot.setEncryptedTokenCiphertext(Base64.getEncoder().encodeToString(ev.ciphertext()));
        stubFindReturns(bot);

        assertThatThrownBy(() -> sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(BotTokenInvalidException.class)
                .satisfies(err -> {
                    BotTokenInvalidException bti = (BotTokenInvalidException) err;
                    assertThat(bti.getMessage()).isEqualTo("invalid token shape after decrypt");
                    assertThat(bti.getBotId()).isEqualTo(BOT_ID);
                });

        assertThat(mockServer.getRequestCount()).isZero();

        // Pre-HTTP BotTokenInvalidException is auditable per tech-spec error-mapping table:
        // emits telegram_send_failed WITHOUT errorCode/errorDescription keys.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCap = ArgumentCaptor.forClass(Map.class);
        verify(eventService).logEvent(eq(OWNER_ID), eq(TelegramSender.EVENT_TELEGRAM_SEND_FAILED),
                isNull(), isNull(), metaCap.capture());
        assertThat(metaCap.getValue())
                .containsEntry("botId", BOT_ID)
                .containsEntry("attempts", 0)
                .doesNotContainKey("errorCode")
                .doesNotContainKey("errorDescription");
    }

    @Test
    void sendText_invalidBase64Ciphertext_throwsBotTokenInvalidException_beforeHttp() {
        Bot bot = connectedBot();
        // "!!!!" contains chars outside the Base64 alphabet → Base64.getDecoder().decode throws
        // IllegalArgumentException, which must be caught and translated to a 422.
        bot.setEncryptedTokenCiphertext("!!!!");
        stubFindReturns(bot);

        assertThatThrownBy(() -> sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(BotTokenInvalidException.class)
                .satisfies(err -> {
                    BotTokenInvalidException bti = (BotTokenInvalidException) err;
                    assertThat(bti.getMessage()).isEqualTo("decryption failed");
                    assertThat(bti.getBotId()).isEqualTo(BOT_ID);
                });

        assertThat(mockServer.getRequestCount()).isZero();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCap = ArgumentCaptor.forClass(Map.class);
        verify(eventService).logEvent(eq(OWNER_ID), eq(TelegramSender.EVENT_TELEGRAM_SEND_FAILED),
                isNull(), isNull(), metaCap.capture());
        assertThat(metaCap.getValue())
                .containsEntry("botId", BOT_ID)
                .containsEntry("attempts", 0)
                .doesNotContainKey("errorCode")
                .doesNotContainKey("errorDescription");
    }

    @Test
    void sendText_decryptThrowsIllegalState_propagatesAsInternalError_notBotTokenInvalid() {
        // Stub TokenEncryptor to throw IllegalStateException — covers BOTH tampered-ciphertext
        // (AEAD-tag failure wrapped) AND misconfigured-bean. The two are indistinguishable at
        // this call site, so the sender must NOT translate either to 422 BotTokenInvalidException;
        // both must propagate as-is to surface as 500 via GlobalErrorHandler.handleThrowable.
        TokenEncryptor stubEncryptor = mock(TokenEncryptor.class);
        when(stubEncryptor.decrypt(any(), any()))
                .thenThrow(new IllegalStateException("AES-GCM decryption failed"));
        TelegramSender stubSender = new TelegramSender(
                RestClient.builder(), mockServer.url("/").toString(),
                TelegramApiClient.DEFAULT_RESPONSE_TIMEOUT, Duration.ofSeconds(30),
                botRepository, stubEncryptor, eventService);
        stubFindReturns(connectedBot());

        assertThatThrownBy(() -> stubSender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(BotTokenInvalidException.class);

        assertThat(mockServer.getRequestCount()).isZero();
    }

    @Test
    void sendText_botNotFound_throwsNotFound_noHttp() {
        stubFindEmpty();

        assertThatThrownBy(() -> sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(AppException.class)
                .satisfies(err -> {
                    AppException app = (AppException) err;
                    assertThat(app.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(app.getCode()).isNull();
                    assertThat(app.getMessage()).isEqualTo("Bot not found");
                });

        assertThat(mockServer.getRequestCount()).isZero();
    }

    @Test
    void sendText_botDisconnected_throwsNotFound_noHttp() {
        Bot bot = connectedBot();
        bot.setStatus(BotStatus.DISCONNECTED);
        stubFindReturns(bot);

        assertThatThrownBy(() -> sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(AppException.class)
                .satisfies(err -> {
                    AppException app = (AppException) err;
                    assertThat(app.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(app.getCode()).isNull();
                    assertThat(app.getMessage()).isEqualTo("Bot not found");
                });

        assertThat(mockServer.getRequestCount()).isZero();
    }

    // ---------- Timeouts ----------

    @Test
    void sendText_readTimeout_isTransient_retriedThenTerminal() {
        sender = newSender(SHORT_RESPONSE_TIMEOUT, Duration.ofSeconds(30));
        stubFindReturns(connectedBot());
        for (int i = 0; i < 4; i++) {
            mockServer.enqueue(okSendMessage(42L, 5L)
                    .setBodyDelay(500, TimeUnit.MILLISECONDS));
        }

        assertThatThrownBy(() -> sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(TelegramSendException.class)
                .hasMessage("transient_failure_exhausted");

        assertThat(mockServer.getRequestCount()).isEqualTo(4);
    }

    @Test
    void sendText_overallTimeout_throwsTelegramSendException() {
        sender = newSender(TelegramApiClient.DEFAULT_RESPONSE_TIMEOUT, SHORT_OVERALL_TIMEOUT);
        stubFindReturns(connectedBot());
        mockServer.enqueue(okSendMessage(42L, 5L)
                .setBodyDelay(35, TimeUnit.SECONDS));

        long start = System.nanoTime();
        assertThatThrownBy(() -> sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(TelegramSendException.class)
                .hasMessage("timeout");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        // SHORT_OVERALL_TIMEOUT is 3s in this test config (production: 30s wall-clock).
        // Allow generous upper bound for the 35s server-side body delay path: the test sender's
        // response timeout is the production 10s, so an attempt may stall up to that before the
        // deadline fires post-sleep.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(2500).isLessThan(15000);

        verify(eventService, times(1)).logEvent(eq(OWNER_ID),
                eq(TelegramSender.EVENT_TELEGRAM_SEND_FAILED),
                isNull(), isNull(), any());
    }

    // ---------- Token-leak (per log site) ----------

    @Test
    void sendText_tokenIn4xxDescription_scrubbed_warnLevel() {
        stubFindReturns(connectedBot());
        mockServer.enqueue(jsonResponse(400, "{\"ok\":false,\"error_code\":400,\"description\":\""
                + "Bad request for token " + TOKEN + " on chat\"}"));

        assertThatThrownBy(() -> sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(TelegramSendException.class);

        assertThat(anyMessageMatches(Level.WARN, m -> m.contains("[REDACTED_TOKEN]"))).isTrue();
        assertThat(anyMessageMatches(Level.WARN, m -> m.contains(TOKEN))).isFalse();
    }

    @Test
    void sendText_tokenIn5xxRetryLog_scrubbed_warnLevel() {
        stubFindReturns(connectedBot());
        mockServer.enqueue(status5xxWithToken(503));
        mockServer.enqueue(okSendMessage(42L, 5L));

        SentMessage sm = sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID);

        assertThat(sm).isNotNull();
        // Retry observer logs the failure message at WARN level. RestClient's
        // HttpServerErrorException message embeds the URI (carrying the token) + body excerpt;
        // scrubTokens must remove any token-shaped substring before the message reaches the
        // appender.
        List<String> warnMessages = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(warnMessages).isNotEmpty();
        assertThat(warnMessages).noneMatch(m -> m.contains(TOKEN));
        assertThat(warnMessages).anyMatch(m -> m.contains("[REDACTED_TOKEN]"));
    }

    @Test
    void sendText_tokenIn429RetryLog_scrubbed_warnLevel() {
        stubFindReturns(connectedBot());
        mockServer.enqueue(status429WithToken(1));
        mockServer.enqueue(okSendMessage(42L, 5L));

        SentMessage sm = sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID);

        assertThat(sm).isNotNull();
        List<String> warnMessages = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(warnMessages).noneMatch(m -> m.contains(TOKEN));
        assertThat(warnMessages).anyMatch(m -> m.contains("[REDACTED_TOKEN]"));
    }

    @Test
    void sendText_tokenInTerminalFailureLog_scrubbed_errorLevel() {
        stubFindReturns(connectedBot());
        for (int i = 0; i < 4; i++) {
            mockServer.enqueue(status5xxWithToken(503));
        }

        assertThatThrownBy(() -> sender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(TelegramSendException.class);

        List<String> errorMessages = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(errorMessages).isNotEmpty();
        assertThat(errorMessages).noneMatch(m -> m.contains(TOKEN));
        // Note: a positive [REDACTED_TOKEN] check is intentionally omitted here. The terminal
        // ERROR log emits "transient_failure_exhausted" as the exception message (the original
        // 5xx description was swallowed by the retry loop); there is nothing token-shaped to
        // redact at THIS site. The WARN-level retry observer (sibling test above) is what
        // exercises scrubbing of the 5xx body description.
    }

    @Test
    void sendText_tokenInTransportErrorMessage_scrubbed() throws Exception {
        // Close the server before issuing the call so the RestClient hits a transport error whose
        // message may embed the request URI (with the token).
        int port = mockServer.getPort();
        mockServer.shutdown();
        TelegramSender deadServerSender = new TelegramSender(
                RestClient.builder(), "http://localhost:" + port + "/",
                Duration.ofMillis(500), Duration.ofSeconds(15),
                botRepository, encryptor, eventService);
        stubFindReturns(connectedBot());

        assertThatThrownBy(() -> deadServerSender.sendText(BOT_ID, CALLER_CHAT_ID, TEXT, null, OWNER_ID))
                .isInstanceOf(TelegramSendException.class);

        // Re-init mockServer so @AfterEach's mockServer.shutdown() is a clean no-op. Without
        // this, the field still references the already-shut-down instance and the AfterEach
        // try/catch swallows the resulting IOException — which works, but obscures intent.
        mockServer = new MockWebServer();
        mockServer.start();

        List<String> leakSites = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN || e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(leakSites).isNotEmpty();
        assertThat(leakSites).noneMatch(m -> m.contains(TOKEN));
        // Negative-only assertion: ResourceAccessException's message format is
        // "I/O error on POST request for ...: Connection refused" — defensive scrubbing is
        // still applied at the log site; the assertion that matters here is the negative one
        // (raw TOKEN absent across WARN+ERROR).
    }
}
