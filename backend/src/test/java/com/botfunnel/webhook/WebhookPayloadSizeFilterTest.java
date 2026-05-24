package com.botfunnel.webhook;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

// Unit-style filter test — invokes doFilter directly on a MockHttpServletRequest/Response pair.
// Avoids @SpringBootTest overhead while still exercising the full filter branch matrix (path
// scope, chunked, missing/oversize Content-Length, boundary).
class WebhookPayloadSizeFilterTest {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\d{1,20}:[A-Za-z0-9_-]{30,50}");

    private MeterRegistry meterRegistry;
    private WebhookPayloadSizeFilter filter;
    private ListAppender<ILoggingEvent> appender;
    private Logger filterLogger;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        filter = new WebhookPayloadSizeFilter(meterRegistry);
        filter.cacheCounter();
        filterLogger = (Logger) LoggerFactory.getLogger(WebhookPayloadSizeFilter.class);
        appender = new ListAppender<>();
        appender.start();
        filterLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        if (filterLogger != null && appender != null) {
            filterLogger.detachAppender(appender);
            appender.stop();
        }
    }

    private double counter() {
        Counter c = meterRegistry.find("telegram_webhook_rejected_total")
                .tag("reason", "payload_too_large").counter();
        return c == null ? 0.0 : c.count();
    }

    private MockHttpServletRequest post(String path) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setRequestURI(path);
        return req;
    }

    private void invoke(MockHttpServletRequest req, MockHttpServletResponse resp,
                        AtomicBoolean downstream) throws Exception {
        FilterChain chain = new FilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) {
                downstream.set(true);
            }
        };
        filter.doFilter(req, resp, chain);
    }

    @Test
    void oversizedContentLength_rejects413_incrementsCounter() throws Exception {
        MockHttpServletRequest req = post("/webhooks/telegram/abc");
        req.addHeader(HttpHeaders.CONTENT_LENGTH,
                String.valueOf(WebhookPayloadSizeFilter.MAX_BODY_BYTES + 1));
        req.setContentType("application/json");
        req.setContent(new byte[0]);
        // setContentType sets the content length on MockHttpServletRequest only when content is
        // supplied — force the header explicitly via the underlying field.
        req.setContent(new byte[(int) (WebhookPayloadSizeFilter.MAX_BODY_BYTES + 1)]);

        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicBoolean downstream = new AtomicBoolean(false);
        invoke(req, resp, downstream);

        assertThat(resp.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(downstream.get()).as("chain.doFilter must NOT be invoked when rejecting").isFalse();
        assertThat(counter()).isEqualTo(1.0);
    }

    @Test
    void chunkedEncoding_rejects413_incrementsCounter() throws Exception {
        MockHttpServletRequest req = post("/webhooks/telegram/abc");
        req.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        AtomicBoolean downstream = new AtomicBoolean(false);
        invoke(req, resp, downstream);

        assertThat(resp.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(downstream.get()).isFalse();
        assertThat(counter()).isEqualTo(1.0);
    }

    @Test
    void missingContentLength_rejects413_incrementsCounter() throws Exception {
        // No Content-Length header at all → MockHttpServletRequest.getContentLengthLong() returns -1.
        MockHttpServletRequest req = post("/webhooks/telegram/abc");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        AtomicBoolean downstream = new AtomicBoolean(false);
        invoke(req, resp, downstream);

        assertThat(resp.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(downstream.get()).isFalse();
        assertThat(counter()).isEqualTo(1.0);
    }

    @Test
    void contentLengthAtBoundary_passesThrough() throws Exception {
        MockHttpServletRequest req = post("/webhooks/telegram/abc");
        req.setContent(new byte[(int) WebhookPayloadSizeFilter.MAX_BODY_BYTES]);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        AtomicBoolean downstream = new AtomicBoolean(false);
        invoke(req, resp, downstream);

        assertThat(downstream.get()).isTrue();
        assertThat(counter()).isZero();
        // Status untouched on pass-through (200 is the servlet default OK).
        assertThat(resp.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void apiPathWithChunkedEncoding_passesThrough() throws Exception {
        // Filter scope is webhook-only — /api/** must not be touched.
        MockHttpServletRequest req = post("/api/v1/projects");
        req.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        AtomicBoolean downstream = new AtomicBoolean(false);
        invoke(req, resp, downstream);

        assertThat(downstream.get()).isTrue();
        assertThat(counter()).isZero();
    }

    @Test
    void multiSegmentWebhookPath_doesNotMatch() throws Exception {
        // /webhooks/telegram/abc/def is OUTSIDE the single-segment scope per security M4.
        MockHttpServletRequest req = post("/webhooks/telegram/abc/def");
        req.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        AtomicBoolean downstream = new AtomicBoolean(false);
        invoke(req, resp, downstream);

        assertThat(downstream.get()).isTrue();
        assertThat(counter()).isZero();
    }

    @Test
    void warnLogOn413_noTokenInOutput() throws Exception {
        // AC18 per-site filter (audit T14 F1). Even though header values cannot legitimately
        // carry tokens, the scrubber is defense-in-depth. Plant a token-shaped Transfer-Encoding
        // value (deliberately non-real) and assert it is redacted by the WARN line.
        String tokenShaped = "1234567890:ABCdefGHI_jklMNOpqrSTUvwxYZ0123456789xyz";
        MockHttpServletRequest req = post("/webhooks/telegram/abc");
        req.addHeader(HttpHeaders.TRANSFER_ENCODING, "chunked, x-" + tokenShaped);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        AtomicBoolean downstream = new AtomicBoolean(false);
        invoke(req, resp, downstream);

        var warnLines = appender.list.stream()
                .filter(e -> e.getLoggerName()
                        .equals("com.botfunnel.webhook.WebhookPayloadSizeFilter"))
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(warnLines).isNotEmpty();
        for (String line : warnLines) {
            assertThat(TOKEN_PATTERN.matcher(line).find())
                    .as("WARN log must NOT contain a Telegram-token-shaped substring: <%s>", line)
                    .isFalse();
        }
    }

    @Test
    void multiValueTransferEncodingCaseFold_rejectsWith413() throws Exception {
        // HTTP/1.1 legal: comma-separated multi-value `Transfer-Encoding`, mixed case. Filter
        // must still reject. Pins Locale.ROOT case-fold (Turkish-locale JVM dotless-i bug) AND
        // substring `.contains("chunked")` (so equality checks like equals("chunked") regress
        // here).
        MockHttpServletRequest req = post("/webhooks/telegram/abc");
        req.addHeader(HttpHeaders.TRANSFER_ENCODING, "gzip,Chunked");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        AtomicBoolean downstream = new AtomicBoolean(false);
        invoke(req, resp, downstream);

        assertThat(resp.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(downstream.get()).as("chain must NOT be invoked on chunked reject").isFalse();
        assertThat(counter()).isEqualTo(1.0);
    }

    @Test
    void allUppercaseTransferEncoding_rejectsWith413() throws Exception {
        // All-uppercase variant — same Locale.ROOT case-fold contract.
        MockHttpServletRequest req = post("/webhooks/telegram/abc");
        req.addHeader(HttpHeaders.TRANSFER_ENCODING, "GZIP, CHUNKED");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        AtomicBoolean downstream = new AtomicBoolean(false);
        invoke(req, resp, downstream);

        assertThat(resp.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(downstream.get()).isFalse();
        assertThat(counter()).isEqualTo(1.0);
    }

    @Test
    void zeroContentLength_passesThrough() throws Exception {
        // Per task edge case: CL: 0 is a valid empty body. Filter passes through; controller's
        // @RequestBody surfaces a Spring 400 default downstream.
        MockHttpServletRequest req = post("/webhooks/telegram/abc");
        req.setContent(new byte[0]);
        req.addHeader(HttpHeaders.CONTENT_LENGTH, "0");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        AtomicBoolean downstream = new AtomicBoolean(false);
        invoke(req, resp, downstream);

        assertThat(downstream.get()).isTrue();
        assertThat(counter()).isZero();
    }
}
