package com.botfunnel.bot;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.botfunnel.bot.dto.TelegramUser;
import com.botfunnel.common.AppException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramApiClientTest {

    private static final String TOKEN = "1234567890:ABCdefGHI_jklMNOpqrSTUvwxYZ0123456789";

    private MockWebServer mockServer;
    private TelegramApiClient client;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = new MockWebServer();
        mockServer.start();
        client = new TelegramApiClient(WebClient.builder(), mockServer.url("/").toString());

        logger = (Logger) LoggerFactory.getLogger(TelegramApiClient.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.detachAppender(logAppender);
        logAppender.stop();
        mockServer.shutdown();
    }

    @Test
    void getMe_happyPath_returnsPopulatedTelegramUser() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"ok\":true,\"result\":{\"id\":123,\"is_bot\":true,\"first_name\":\"x\",\"username\":\"x_bot\"}}"));

        TelegramUser user = client.getMe(TOKEN).block();

        assertThat(user).isNotNull();
        assertThat(user.id()).isEqualTo(123L);
        assertThat(user.is_bot()).isTrue();
        assertThat(user.first_name()).isEqualTo("x");
        assertThat(user.username()).isEqualTo("x_bot");

        RecordedRequest req = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/bot" + TOKEN + "/getMe");
    }

    @Test
    void getMe_401_mapsToInvalidBotToken() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}"));

        StepVerifier.create(client.getMe(TOKEN))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(AppException.class);
                    AppException app = (AppException) err;
                    assertThat(app.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(app.getCode()).isEqualTo("invalid_bot_token");
                })
                .verify();

        assertThat(mockServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    void getMe_5xxRetryExhausted_returns502TelegramUnavailable() {
        for (int i = 0; i < 4; i++) {
            mockServer.enqueue(new MockResponse()
                    .setResponseCode(503)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"ok\":false,\"error_code\":503,\"description\":\"Service Unavailable\"}"));
        }

        StepVerifier.create(client.getMe(TOKEN))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(AppException.class);
                    AppException app = (AppException) err;
                    assertThat(app.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(app.getCode()).isEqualTo("telegram_unavailable");
                })
                .verify(Duration.ofSeconds(10));

        assertThat(mockServer.getRequestCount()).isEqualTo(4);
    }

    @Test
    void setWebhook_4xxConfigError_mapsTo500WebhookConfigError() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"ok\":false,\"error_code\":400,\"description\":\"HTTPS url must be provided for webhook\"}"));

        StepVerifier.create(client.setWebhook(TOKEN, "http://example.com/hook", "secret-xyz"))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(AppException.class);
                    AppException app = (AppException) err;
                    assertThat(app.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(app.getCode()).isEqualTo("webhook_config_error");
                })
                .verify();

        assertThat(mockServer.getRequestCount()).isEqualTo(1);
        assertThat(logAppender.list)
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(msg -> msg.contains("HTTPS url must be provided"));
    }

    @Test
    void setWebhook_4xxConfigError_scrubsTokenInWarnLog() {
        String description = "Bad webhook for token " + TOKEN + " on chat";
        mockServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"ok\":false,\"error_code\":400,\"description\":\"" + description + "\"}"));

        StepVerifier.create(client.setWebhook(TOKEN, "https://example.com/hook", "secret-xyz"))
                .expectError(AppException.class)
                .verify();

        assertThat(logAppender.list)
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(msg -> msg.contains("[REDACTED_TOKEN]") && !msg.contains(TOKEN));
    }

    @Test
    void getMe_4xxBlankDescription_fallsBackToBadRequest() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"ok\":false,\"error_code\":403,\"description\":\"\"}"));

        StepVerifier.create(client.getMe(TOKEN))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(AppException.class);
                    AppException app = (AppException) err;
                    assertThat(app.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(app.getMessage()).isEqualTo("Telegram client error");
                    assertThat(app.getCode()).isNull();
                })
                .verify();

        assertThat(mockServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    void getMe_4xxNonJsonBody_fallsBackToBadRequest() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(418)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>I am a teapot</body></html>"));

        StepVerifier.create(client.getMe(TOKEN))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(AppException.class);
                    AppException app = (AppException) err;
                    assertThat(app.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(app.getMessage()).isEqualTo("Telegram client error");
                })
                .verify();

        assertThat(mockServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    void scrubber_redactsTokenShapedSubstring() {
        String token = "1234567890:ABCdefGHI_jklMNOpqrSTUvwxYZ0123456789";
        assertThat(TelegramApiClient.scrubTokens("foo " + token + " bar"))
                .isEqualTo("foo [REDACTED_TOKEN] bar");
        assertThat(TelegramApiClient.scrubTokens(token + " trailing"))
                .isEqualTo("[REDACTED_TOKEN] trailing");
        assertThat(TelegramApiClient.scrubTokens("leading " + token))
                .isEqualTo("leading [REDACTED_TOKEN]");
        assertThat(TelegramApiClient.scrubTokens(token + " and " + token))
                .isEqualTo("[REDACTED_TOKEN] and [REDACTED_TOKEN]");
        assertThat(TelegramApiClient.scrubTokens("no tokens here"))
                .isEqualTo("no tokens here");
        assertThat(TelegramApiClient.scrubTokens(null)).isNull();
        assertThat(TelegramApiClient.scrubTokens("")).isEqualTo("");
        assertThat(TelegramApiClient.scrubTokens("   ")).isEqualTo("   ");
    }

    @Test
    void scrubber_regexBoundaries() {
        String idPrefix = "1234567890";
        String suffix30 = "a".repeat(30);
        String suffix29 = "a".repeat(29);
        String suffix50 = "a".repeat(50);
        String suffix51 = "a".repeat(51);
        String digits20 = "12345678901234567890";
        String digits21 = "123456789012345678901";

        assertThat(TelegramApiClient.scrubTokens(idPrefix + ":" + suffix30))
                .isEqualTo("[REDACTED_TOKEN]");
        assertThat(TelegramApiClient.scrubTokens(idPrefix + ":" + suffix29))
                .isEqualTo(idPrefix + ":" + suffix29);
        assertThat(TelegramApiClient.scrubTokens(idPrefix + ":" + suffix50))
                .isEqualTo("[REDACTED_TOKEN]");
        assertThat(TelegramApiClient.scrubTokens(idPrefix + ":" + suffix51))
                .isEqualTo("[REDACTED_TOKEN]a");
        assertThat(TelegramApiClient.scrubTokens(digits20 + ":" + suffix30))
                .isEqualTo("[REDACTED_TOKEN]");
        assertThat(TelegramApiClient.scrubTokens(digits21 + ":" + suffix30))
                .isEqualTo("1[REDACTED_TOKEN]");
        assertThat(TelegramApiClient.scrubTokens("1234567890:short"))
                .isEqualTo("1234567890:short");
    }

    @Test
    void deleteWebhook_5xxRetryExhausted_returns502TelegramUnavailable() {
        for (int i = 0; i < 4; i++) {
            mockServer.enqueue(new MockResponse()
                    .setResponseCode(503)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"ok\":false,\"error_code\":503,\"description\":\"Service Unavailable\"}"));
        }

        StepVerifier.create(client.deleteWebhook(TOKEN))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(AppException.class);
                    AppException app = (AppException) err;
                    assertThat(app.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(app.getCode()).isEqualTo("telegram_unavailable");
                })
                .verify(Duration.ofSeconds(10));

        assertThat(mockServer.getRequestCount()).isEqualTo(4);
    }

    @Test
    void deleteWebhook_happyPath_returnsTrueAndHitsCorrectPath() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"ok\":true,\"result\":true}"));

        Boolean result = client.deleteWebhook(TOKEN).block();

        assertThat(result).isTrue();
        RecordedRequest req = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/bot" + TOKEN + "/deleteWebhook");
        assertThat(req.getMethod()).isEqualTo("POST");
    }

    @Test
    void getMe_nettyReadTimeout_retriesThenReturns502TelegramUnavailable() throws Exception {
        mockServer.shutdown();
        mockServer = new MockWebServer();
        mockServer.start();
        client = new TelegramApiClient(
                WebClient.builder(),
                mockServer.url("/").toString(),
                Duration.ofMillis(200));

        for (int i = 0; i < 4; i++) {
            mockServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"ok\":true,\"result\":{\"id\":1,\"is_bot\":true,\"first_name\":\"x\",\"username\":\"x_bot\"}}")
                    .setBodyDelay(500, TimeUnit.MILLISECONDS));
        }

        StepVerifier.create(client.getMe(TOKEN))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(AppException.class);
                    AppException app = (AppException) err;
                    assertThat(app.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(app.getCode()).isEqualTo("telegram_unavailable");
                })
                .verify(Duration.ofSeconds(15));

        assertThat(mockServer.getRequestCount()).isEqualTo(4);
    }

    @Test
    void setWebhook_passesSecretTokenAndUrlInBody() throws Exception {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"ok\":true,\"result\":true}"));

        Boolean ok = client.setWebhook(TOKEN, "https://example.com/hook", "secret-xyz").block();
        assertThat(ok).isTrue();

        RecordedRequest req = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/bot" + TOKEN + "/setWebhook");

        Map<String, String> parsed = new ObjectMapper().readValue(
                req.getBody().readUtf8(),
                new TypeReference<Map<String, String>>() {});
        assertThat(parsed)
                .hasSize(2)
                .containsEntry("url", "https://example.com/hook")
                .containsEntry("secret_token", "secret-xyz");
    }
}
