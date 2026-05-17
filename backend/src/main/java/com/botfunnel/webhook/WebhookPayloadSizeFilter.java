package com.botfunnel.webhook;

import com.botfunnel.bot.TelegramApiClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;

// Decision 13. Path-scoped 413 gate that runs BEFORE the security chain so an attacker cannot
// exhaust Netty buffers with a chunked or oversize body. Owns the rejected{payload_too_large}
// counter (single source of truth — the controller deliberately does NOT reference this reason).
// Single-segment path scope ({projectId}) prevents future sub-path leak per security M4.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class WebhookPayloadSizeFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(WebhookPayloadSizeFilter.class);

    static final long MAX_BODY_BYTES = 1_048_576L;
    // Use Spring's PathPattern (same parser that drives SecurityConfig matchers) so request-path
    // normalisation rules (percent-decoding, dot-segment handling) match the security chain. A
    // hand-rolled regex would risk divergence on edge cases (`/webhooks/telegram/%2E%2E`).
    private static final PathPattern WEBHOOK_PATH = PathPatternParser.defaultInstance
            .parse("/webhooks/telegram/{projectId}");
    private static final String PROJECT_ID_VAR = "projectId";

    private final MeterRegistry meterRegistry;
    private Counter rejectedPayloadTooLarge;

    public WebhookPayloadSizeFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void cacheCounter() {
        this.rejectedPayloadTooLarge = Counter.builder("telegram_webhook_rejected_total")
                .tag("reason", "payload_too_large").register(meterRegistry);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        PathContainer pathContainer = exchange.getRequest().getPath().pathWithinApplication();
        if (!WEBHOOK_PATH.matches(pathContainer)) {
            return chain.filter(exchange);
        }

        HttpHeaders headers = exchange.getRequest().getHeaders();
        List<String> transferEncodings = headers.get(HttpHeaders.TRANSFER_ENCODING);
        long contentLength = headers.getContentLength();

        // Multi-value Transfer-Encoding (HTTP/1.1 allows comma-separated and repeated headers).
        // Locale.ROOT for case-fold so a Turkish-locale JVM doesn't dotless-i the "chunked" token.
        boolean chunked = transferEncodings != null && transferEncodings.stream()
                .anyMatch(v -> v != null && v.toLowerCase(Locale.ROOT).contains("chunked"));
        // getContentLength() == -1 means missing or unparseable per Spring's HttpHeaders contract.
        // Telegram always sends a Content-Length per Bot API contract; missing it on the webhook
        // path is anomalous and rejected. CL: 0 is a valid empty body and passes through — the
        // controller's @RequestBody required=true surfaces a Spring 400 downstream.
        boolean missingContentLength = contentLength < 0;
        boolean oversize = contentLength > MAX_BODY_BYTES;

        if (chunked || missingContentLength || oversize) {
            String reason = chunked ? "chunked"
                    : missingContentLength ? "missing_content_length"
                    : "oversize_" + contentLength;
            String projectId = WEBHOOK_PATH.matchAndExtract(pathContainer) != null
                    ? WEBHOOK_PATH.matchAndExtract(pathContainer).getUriVariables().get(PROJECT_ID_VAR)
                    : "n/a";
            // Scrubber on the log site — header values cannot legitimately carry tokens but the
            // scrub is defense-in-depth per Risks "Token-scrubber sites" enumeration.
            log.warn("WebhookPayloadSizeFilter - rejecting (projectId={}, reason={}, contentLength={}, transferEncoding={})",
                    TelegramApiClient.scrubTokens(projectId),
                    reason,
                    contentLength,
                    TelegramApiClient.scrubTokens(transferEncodings == null ? null
                            : String.join(",", transferEncodings)));
            rejectedPayloadTooLarge.increment();
            exchange.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }
}
