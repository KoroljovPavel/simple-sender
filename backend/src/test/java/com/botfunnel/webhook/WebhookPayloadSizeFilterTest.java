package com.botfunnel.webhook;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

// Unit-style filter test — invokes WebFilter#filter directly on a MockServerWebExchange built from
// MockServerHttpRequest. Avoids @SpringBootTest overhead while still exercising the full filter
// branch matrix (path scope, chunked, missing/oversize Content-Length, boundary).
class WebhookPayloadSizeFilterTest {

    private MeterRegistry meterRegistry;
    private WebhookPayloadSizeFilter filter;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        filter = new WebhookPayloadSizeFilter(meterRegistry);
        filter.cacheCounter();
    }

    private double counter() {
        Counter c = meterRegistry.find("telegram_webhook_rejected_total")
                .tag("reason", "payload_too_large").counter();
        return c == null ? 0.0 : c.count();
    }

    private MockServerWebExchange exchange(MockServerHttpRequest request) {
        return MockServerWebExchange.from(request);
    }

    private MockServerHttpRequest.BodyBuilder post(String path) {
        return MockServerHttpRequest.post(path);
    }

    @Test
    void oversizedContentLength_rejects413_incrementsCounter() {
        MockServerWebExchange exchange = exchange(
                post("/webhooks/telegram/abc")
                        .header(HttpHeaders.CONTENT_LENGTH,
                                String.valueOf(WebhookPayloadSizeFilter.MAX_BODY_BYTES + 1))
                        .build());

        AtomicBoolean downstream = new AtomicBoolean(false);
        StepVerifier.create(filter.filter(exchange, ex -> {
            downstream.set(true);
            return Mono.empty();
        })).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(downstream.get()).as("chain.filter must NOT be invoked when rejecting").isFalse();
        assertThat(counter()).isEqualTo(1.0);
    }

    @Test
    void chunkedEncoding_rejects413_incrementsCounter() {
        MockServerWebExchange exchange = exchange(
                post("/webhooks/telegram/abc")
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(counter()).isEqualTo(1.0);
    }

    @Test
    void missingContentLength_rejects413_incrementsCounter() {
        // No Content-Length header at all.
        MockServerWebExchange exchange = exchange(post("/webhooks/telegram/abc").build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(counter()).isEqualTo(1.0);
    }

    @Test
    void contentLengthAtBoundary_passesThrough() {
        MockServerWebExchange exchange = exchange(
                post("/webhooks/telegram/abc")
                        .header(HttpHeaders.CONTENT_LENGTH,
                                String.valueOf(WebhookPayloadSizeFilter.MAX_BODY_BYTES))
                        .build());

        AtomicBoolean downstream = new AtomicBoolean(false);
        StepVerifier.create(filter.filter(exchange, ex -> {
            downstream.set(true);
            return Mono.empty();
        })).verifyComplete();

        assertThat(downstream.get()).isTrue();
        assertThat(counter()).isZero();
        // Response status not pre-set on pass-through.
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void apiPathWithChunkedEncoding_passesThrough() {
        // Filter scope is webhook-only — /api/** must not be touched.
        MockServerWebExchange exchange = exchange(
                MockServerHttpRequest.post("/api/v1/projects")
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .build());

        AtomicBoolean downstream = new AtomicBoolean(false);
        StepVerifier.create(filter.filter(exchange, ex -> {
            downstream.set(true);
            return Mono.empty();
        })).verifyComplete();

        assertThat(downstream.get()).isTrue();
        assertThat(counter()).isZero();
    }

    @Test
    void multiSegmentWebhookPath_doesNotMatch() {
        // /webhooks/telegram/abc/def is OUTSIDE the single-segment scope per security M4.
        MockServerWebExchange exchange = exchange(
                post("/webhooks/telegram/abc/def")
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .build());

        AtomicBoolean downstream = new AtomicBoolean(false);
        StepVerifier.create(filter.filter(exchange, ex -> {
            downstream.set(true);
            return Mono.empty();
        })).verifyComplete();

        assertThat(downstream.get()).isTrue();
        assertThat(counter()).isZero();
    }

    @Test
    void zeroContentLength_passesThrough() {
        // Per task edge case: CL: 0 is a valid empty body. Filter passes through; controller's
        // @RequestBody surfaces a Spring 400 default downstream.
        MockServerWebExchange exchange = exchange(
                post("/webhooks/telegram/abc")
                        .header(HttpHeaders.CONTENT_LENGTH, "0")
                        .build());

        AtomicBoolean downstream = new AtomicBoolean(false);
        StepVerifier.create(filter.filter(exchange, ex -> {
            downstream.set(true);
            return Mono.empty();
        })).verifyComplete();
        assertThat(downstream.get()).isTrue();
        assertThat(counter()).isZero();
    }
}
