package com.botfunnel.bot;

import com.botfunnel.bot.dto.TelegramResult;
import com.botfunnel.bot.dto.TelegramUser;
import com.botfunnel.common.AppException;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutException;
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
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.DefaultUriBuilderFactory;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

@Component
public class TelegramApiClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramApiClient.class);

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\d{1,20}:[A-Za-z0-9_-]{30,50}");
    private static final Pattern TOKEN_SHAPE = Pattern.compile("^\\d{1,20}:[A-Za-z0-9_-]{30,50}$");
    private static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MONO_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final int CAUSE_CHAIN_MAX_HOPS = 16;

    private final WebClient webClient;

    @Autowired
    public TelegramApiClient(WebClient.Builder builder,
                             @Value("${app.telegram.base-url}") String baseUrl) {
        this(builder, baseUrl, DEFAULT_RESPONSE_TIMEOUT);
    }

    TelegramApiClient(WebClient.Builder builder, String baseUrl, Duration responseTimeout) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(responseTimeout)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis());
        DefaultUriBuilderFactory uriBuilderFactory = new DefaultUriBuilderFactory(baseUrl);
        uriBuilderFactory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);
        this.webClient = builder
                .uriBuilderFactory(uriBuilderFactory)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    public Mono<TelegramUser> getMe(String token) {
        requireValidTokenShape(token);
        return webClient.get()
                .uri("/bot{token}/getMe", token)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::mapClientError)
                .bodyToMono(new ParameterizedTypeReference<TelegramResult<TelegramUser>>() {})
                .timeout(MONO_TIMEOUT)
                .retryWhen(buildRetry())
                .flatMap(r -> r.result() != null
                        ? Mono.just(r.result())
                        : Mono.error(new AppException(
                                HttpStatus.BAD_GATEWAY,
                                "telegram_unavailable",
                                "Telegram returned empty result")));
    }

    /**
     * Caller is responsible for ensuring {@code url} is platform-controlled
     * (typically {@code ${app.url}/webhooks/telegram/{projectId}}). Never
     * forward a user-supplied URL — Telegram would POST update payloads there.
     */
    public Mono<Boolean> setWebhook(String token, String url, String secret) {
        requireValidTokenShape(token);
        Map<String, String> body = Map.of(
                "url", url,
                "secret_token", secret);
        return webClient.post()
                .uri("/bot{token}/setWebhook", token)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::mapClientError)
                .bodyToMono(new ParameterizedTypeReference<TelegramResult<Boolean>>() {})
                .timeout(MONO_TIMEOUT)
                .retryWhen(buildRetry())
                .map(r -> Boolean.TRUE.equals(r.result()));
    }

    public Mono<Boolean> deleteWebhook(String token) {
        requireValidTokenShape(token);
        return webClient.post()
                .uri("/bot{token}/deleteWebhook", token)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::mapClientError)
                .bodyToMono(new ParameterizedTypeReference<TelegramResult<Boolean>>() {})
                .timeout(MONO_TIMEOUT)
                .retryWhen(buildRetry())
                .map(r -> Boolean.TRUE.equals(r.result()));
    }

    private static void requireValidTokenShape(String token) {
        if (token == null || !TOKEN_SHAPE.matcher(token).matches()) {
            throw new IllegalArgumentException("invalid token shape");
        }
    }

    private Mono<? extends Throwable> mapClientError(ClientResponse response) {
        HttpStatus status = HttpStatus.resolve(response.statusCode().value());
        int rawStatus = response.statusCode().value();
        return response.bodyToMono(new ParameterizedTypeReference<TelegramResult<Object>>() {})
                .onErrorResume(ex -> Mono.just(new TelegramResult<>(false, null, null, null)))
                .defaultIfEmpty(new TelegramResult<>(false, null, null, null))
                .map(result -> toAppException(status, rawStatus, result.description()));
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

    private Retry buildRetry() {
        return Retry.backoff(3, Duration.ofMillis(200))
                .maxBackoff(Duration.ofSeconds(2))
                .filter(TelegramApiClient::isTransient)
                .onRetryExhaustedThrow((spec, signal) -> new AppException(
                        HttpStatus.BAD_GATEWAY,
                        "telegram_unavailable",
                        "Telegram is currently unavailable. Try again in a minute."));
    }

    private static boolean isTransient(Throwable ex) {
        Throwable cur = ex;
        int hops = 0;
        while (cur != null && hops++ < CAUSE_CHAIN_MAX_HOPS) {
            if (cur instanceof WebClientResponseException w && w.getStatusCode().is5xxServerError()) return true;
            if (cur instanceof IOException) return true;
            if (cur instanceof TimeoutException) return true;
            if (cur instanceof ReadTimeoutException) return true;
            Throwable next = cur.getCause();
            if (next == cur) break;
            cur = next;
        }
        return false;
    }

    static String scrubTokens(String input) {
        if (input == null) return null;
        return TOKEN_PATTERN.matcher(input).replaceAll("[REDACTED_TOKEN]");
    }
}
