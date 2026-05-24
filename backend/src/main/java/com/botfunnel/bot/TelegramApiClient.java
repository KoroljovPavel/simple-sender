package com.botfunnel.bot;

import com.botfunnel.bot.dto.TelegramResult;
import com.botfunnel.bot.dto.TelegramUser;
import com.botfunnel.common.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Component
public class TelegramApiClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramApiClient.class);

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\d{1,20}:[A-Za-z0-9_-]{30,50}");
    private static final Pattern TOKEN_SHAPE = Pattern.compile("^\\d{1,20}:[A-Za-z0-9_-]{30,50}$");
    // Promoted to public for shared use by TelegramSender RestClient construction —
    // single source of truth for the response-read timeout.
    public static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(10);
    // Promoted to public for shared use by TelegramSender RestClient construction —
    // single source of truth for the TCP connect timeout.
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final int CAUSE_CHAIN_MAX_HOPS = 16;
    private static final int MAX_RETRIES = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(200);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(2);

    private final RestClient restClient;

    @Autowired
    public TelegramApiClient(RestClient.Builder builder,
                             @Value("${app.telegram.base-url}") String baseUrl) {
        this(builder, baseUrl, DEFAULT_RESPONSE_TIMEOUT);
    }

    TelegramApiClient(RestClient.Builder builder, String baseUrl, Duration responseTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(responseTimeout);
        DefaultUriBuilderFactory uriBuilderFactory = new DefaultUriBuilderFactory(baseUrl);
        uriBuilderFactory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);
        this.restClient = builder
                .uriBuilderFactory(uriBuilderFactory)
                .requestFactory(requestFactory)
                .build();
    }

    public TelegramUser getMe(String token) {
        requireValidTokenShape(token);
        TelegramResult<TelegramUser> result = executeWithRetry(() -> restClient.get()
                .uri("/bot{token}/getMe", token)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                    throw mapClientError(resp);
                })
                .body(new ParameterizedTypeReference<TelegramResult<TelegramUser>>() {}));
        if (result != null && result.result() != null) {
            return result.result();
        }
        throw new AppException(
                HttpStatus.BAD_GATEWAY,
                "telegram_unavailable",
                "Telegram returned empty result");
    }

    /**
     * Caller is responsible for ensuring {@code url} is platform-controlled
     * (typically {@code ${app.url}/webhooks/telegram/{projectId}}). Never
     * forward a user-supplied URL — Telegram would POST update payloads there.
     */
    public boolean setWebhook(String token, String url, String secret) {
        requireValidTokenShape(token);
        Map<String, String> body = Map.of(
                "url", url,
                "secret_token", secret);
        TelegramResult<Boolean> result = executeWithRetry(() -> restClient.post()
                .uri("/bot{token}/setWebhook", token)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                    throw mapClientError(resp);
                })
                .body(new ParameterizedTypeReference<TelegramResult<Boolean>>() {}));
        return result != null && Boolean.TRUE.equals(result.result());
    }

    public boolean deleteWebhook(String token) {
        requireValidTokenShape(token);
        TelegramResult<Boolean> result = executeWithRetry(() -> restClient.post()
                .uri("/bot{token}/deleteWebhook", token)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                    throw mapClientError(resp);
                })
                .body(new ParameterizedTypeReference<TelegramResult<Boolean>>() {}));
        return result != null && Boolean.TRUE.equals(result.result());
    }

    // Promoted to public for shared use by TelegramSender — single source of truth
    // for the bot-token regex shape check, invoked after AES-GCM decrypt before RestClient URI build.
    public static void requireValidTokenShape(String token) {
        if (token == null || !TOKEN_SHAPE.matcher(token).matches()) {
            throw new IllegalArgumentException("invalid token shape");
        }
    }

    private <T> T executeWithRetry(Supplier<T> call) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException ex) {
                if (!isTransient(ex)) {
                    throw ex;
                }
                if (attempt == MAX_RETRIES) {
                    throw new AppException(
                            HttpStatus.BAD_GATEWAY,
                            "telegram_unavailable",
                            "Telegram is currently unavailable. Try again in a minute.");
                }
                long backoffMs = Math.min(
                        INITIAL_BACKOFF.toMillis() << attempt,
                        MAX_BACKOFF.toMillis());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new AppException(
                            HttpStatus.BAD_GATEWAY,
                            "telegram_unavailable",
                            "Interrupted while waiting to retry");
                }
            }
        }
        // Unreachable: the attempt==MAX_RETRIES branch throws inside the loop.
        throw new AppException(
                HttpStatus.BAD_GATEWAY,
                "telegram_unavailable",
                "Telegram is currently unavailable. Try again in a minute.");
    }

    private AppException mapClientError(org.springframework.http.client.ClientHttpResponse response) throws IOException {
        int rawStatus = response.getStatusCode().value();
        HttpStatus status = HttpStatus.resolve(rawStatus);
        TelegramResult<Object> result = readBodyOrEmpty(response);
        return toAppException(status, rawStatus, result.description());
    }

    private TelegramResult<Object> readBodyOrEmpty(org.springframework.http.client.ClientHttpResponse response) {
        try {
            byte[] bytes = response.getBody().readAllBytes();
            if (bytes.length == 0) {
                return new TelegramResult<>(false, null, null, null);
            }
            return ObjectMapperHolder.INSTANCE.readValue(bytes, ObjectMapperHolder.TYPE);
        } catch (Exception ex) {
            return new TelegramResult<>(false, null, null, null);
        }
    }

    private AppException toAppException(HttpStatus status, int rawStatus, String description) {
        String scrubbed = scrubTokens(description);
        if (status == HttpStatus.UNAUTHORIZED) {
            return AppException.unprocessableEntity("invalid_bot_token", "Token is invalid or revoked");
        }
        if (status != null && status.is4xxClientError() && scrubbed != null && !scrubbed.isBlank()) {
            log.warn("Telegram config error: {}", scrubbed);
            return new AppException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "webhook_config_error",
                    "Webhook configuration error");
        }
        log.warn("Telegram client error (status {}): {}", rawStatus, scrubbed);
        return AppException.badRequest("Telegram client error");
    }

    // Promoted to public for shared use by TelegramSender's 5xx retry filter —
    // single source of truth for the transient-failure classifier (walks the cause chain).
    public static boolean isTransient(Throwable ex) {
        Throwable cur = ex;
        int hops = 0;
        while (cur != null && hops++ < CAUSE_CHAIN_MAX_HOPS) {
            if (cur instanceof HttpServerErrorException) return true;
            if (cur instanceof RestClientResponseException r && r.getStatusCode().is5xxServerError()) return true;
            if (cur instanceof IOException) return true;
            if (cur instanceof TimeoutException) return true;
            Throwable next = cur.getCause();
            if (next == cur) break;
            cur = next;
        }
        return false;
    }

    // Promoted to public for shared use by BotService log sites — the same regex must scrub
    // Telegram error messages before they reach any logger, including downstream callers'.
    public static String scrubTokens(String input) {
        if (input == null) return null;
        return TOKEN_PATTERN.matcher(input).replaceAll("[REDACTED_TOKEN]");
    }

    // Lazy ObjectMapper holder. Spring's default ObjectMapper is the one MessageConverters use to
    // body-deserialize TelegramResult<T>; reading the 4xx body inside the status handler bypasses
    // those converters, so we keep a standalone mapper here for the fallback parse.
    private static final class ObjectMapperHolder {
        static final com.fasterxml.jackson.databind.ObjectMapper INSTANCE =
                new com.fasterxml.jackson.databind.ObjectMapper();
        static final com.fasterxml.jackson.core.type.TypeReference<TelegramResult<Object>> TYPE =
                new com.fasterxml.jackson.core.type.TypeReference<>() {};
    }
}
