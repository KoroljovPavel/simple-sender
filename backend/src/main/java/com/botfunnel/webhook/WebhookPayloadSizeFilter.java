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
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Decision 13. Path-scoped 413 gate that runs BEFORE the security chain so an attacker cannot
// exhaust Netty buffers with a chunked or oversize body. Owns the rejected{payload_too_large}
// counter (single source of truth — the controller deliberately does NOT reference this reason).
// Single-segment path scope ({projectId}) prevents future sub-path leak per security M4.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class WebhookPayloadSizeFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(WebhookPayloadSizeFilter.class);

    static final long MAX_BODY_BYTES = 1_048_576L;
    private static final Pattern PATH_PATTERN = Pattern.compile("^/webhooks/telegram/([^/]+)$");

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
        String path = exchange.getRequest().getPath().value();
        Matcher m = PATH_PATTERN.matcher(path);
        if (!m.matches()) {
            return chain.filter(exchange);
        }

        HttpHeaders headers = exchange.getRequest().getHeaders();
        String transferEncoding = headers.getFirst(HttpHeaders.TRANSFER_ENCODING);
        long contentLength = headers.getContentLength();

        boolean chunked = transferEncoding != null
                && transferEncoding.toLowerCase().contains("chunked");
        // getContentLength() == -1 means missing or unparseable per Spring's HttpHeaders contract.
        // Telegram always sends a Content-Length per Bot API contract (verified in Telegram docs);
        // missing it on the webhook path is treated as anomalous and rejected. Note: 0-byte body
        // (Content-Length: 0) is passed through — the controller's @RequestBody required=true
        // will surface a Spring default 400 from there, which Telegram retries against. Returning
        // a 413 for 0 would be misleading.
        boolean missingContentLength = contentLength < 0;
        boolean oversize = contentLength > MAX_BODY_BYTES;

        if (chunked || missingContentLength || oversize) {
            String reason = chunked ? "chunked"
                    : missingContentLength ? "missing_content_length"
                    : "oversize_" + contentLength;
            String projectId = m.groupCount() >= 1 ? m.group(1) : "n/a";
            // Scrubber on the log site — header values cannot legitimately carry tokens but the
            // scrub is defense-in-depth per Risks "Token-scrubber sites" enumeration.
            log.warn("WebhookPayloadSizeFilter - rejecting (projectId={}, reason={}, contentLength={}, transferEncoding={})",
                    TelegramApiClient.scrubTokens(projectId),
                    reason,
                    contentLength,
                    TelegramApiClient.scrubTokens(transferEncoding));
            rejectedPayloadTooLarge.increment();
            exchange.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }
}
