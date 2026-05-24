package com.botfunnel.webhook;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.common.crypto.Sha256Hex;
import com.botfunnel.common.test.ConcurrencyTestUtils;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// Full-stack IT for T10 — covers user-spec AC1, AC2, AC3, AC5, AC6 (controller-side enqueue
// assertion only — ownerChatId populate is in ProcessTelegramUpdateJobTest), AC15, AC16, AC18.
// JobRunrInMemoryConfig means no worker thread runs; we read ENQUEUED counts directly from the
// in-memory StorageProvider so the test asserts only what the controller commits, not the
// downstream worker. After the Wave 2 servlet flip, dispatches via MockMvc (autowired by
// AbstractIntegrationTest) rather than the prior reactive WebTestClient pipeline.
class TelegramWebhookControllerIT extends AbstractIntegrationTest {

    private static final String SECRET_PLAIN = "secretToken_AAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String SECRET_HASH = Sha256Hex.hex(SECRET_PLAIN);
    private static final String SECRET_HASH_OTHER = Sha256Hex.hex("other_secret_plain_value");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\d{1,20}:[A-Za-z0-9_-]{30,50}");

    @Autowired BotRepository botRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired RawUpdateRepository rawUpdateRepository;
    @Autowired MeterRegistry meterRegistry;
    @Autowired StorageProvider storageProvider;
    @Autowired JobScheduler jobScheduler;
    @Autowired TelegramWebhookController controller;
    @Autowired WebhookPayloadSizeFilter payloadFilter;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ListAppender<ILoggingEvent> appender;
    private Logger controllerLogger;

    @BeforeEach
    void resetMeterAndJobs() {
        rawUpdateRepository.deleteAll();
        botRepository.deleteAll();
        projectRepository.deleteAll();
        storageProvider.deleteJobsPermanently(StateName.ENQUEUED, Instant.now().plusSeconds(60));
        storageProvider.deleteJobsPermanently(StateName.SUCCEEDED, Instant.now().plusSeconds(60));
        storageProvider.deleteJobsPermanently(StateName.FAILED, Instant.now().plusSeconds(60));
        // Reset counters between tests so per-test assertions on counter() == N are deterministic.
        meterRegistry.clear();
        // Recreate the cached counters in the controller and filter — clear() removed them.
        controller.cacheCounters();
        payloadFilter.cacheCounter();

        controllerLogger = (Logger) LoggerFactory.getLogger(TelegramWebhookController.class);
        appender = new ListAppender<>();
        appender.start();
        controllerLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        if (controllerLogger != null && appender != null) {
            controllerLogger.detachAppender(appender);
            appender.stop();
        }
        // Restore default enqueuer in case a test swapped it. Use the autowired JobScheduler
        // (NOT the static BackgroundJob) so the controller writes into the same StorageProvider
        // bean the test reads from via @Autowired — see TelegramWebhookController#enqueuer comment.
        controller.setEnqueuer((jobId, rawUpdateId) ->
                jobScheduler.<ProcessTelegramUpdateJob>enqueue(
                        jobId, j -> j.handle(rawUpdateId)));
    }

    private Project seedProject() {
        Project p = new Project();
        p.setOwnerId("ownerX");
        p.setName("Project " + System.nanoTime());
        p.setTimezone("UTC");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return projectRepository.save(p);
    }

    private Project seedSoftDeletedProject() {
        Project p = seedProject();
        p.setDeletedAt(Instant.now());
        return projectRepository.save(p);
    }

    private Bot seedBot(String projectId, BotStatus status, String webhookSecretHash) {
        Bot b = new Bot();
        b.setProjectId(projectId);
        b.setTelegramBotId(1234567890L);
        b.setStatus(status);
        b.setWebhookSecretHash(webhookSecretHash);
        b.setConnectedAt(Instant.now());
        return botRepository.save(b);
    }

    private Document samplePayload(long updateId) {
        Document chat = new Document("id", 100L).append("type", "private");
        Document message = new Document()
                .append("message_id", 1L)
                .append("chat", chat)
                .append("date", 1700000000L)
                .append("text", "/start");
        return new Document().append("update_id", updateId).append("message", message);
    }

    private String bodyJson(Document body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int doPost(String projectId, String secretHeader, Document body) {
        try {
            MvcResult result = mockMvc.perform(post("/webhooks/telegram/{projectId}", projectId)
                            .header("X-Telegram-Bot-Api-Secret-Token", secretHeader == null ? "" : secretHeader)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bodyJson(body)))
                    .andReturn();
            return result.getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long enqueuedCount() {
        return storageProvider.countJobs(StateName.ENQUEUED);
    }

    // Used by happy-path / dup tests to settle the in-memory JobRunr storage after the controller
    // returns. BackgroundJob.enqueue can return before the InMemoryStorageProvider's notify chain
    // settles under shared-context test runs — Awaitility polls deterministically. 5s upper bound
    // tolerates JVM busy-state when running the full test class against testcontainers.
    private void awaitEnqueuedCount(long expected) {
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(20))
                .untilAsserted(() -> assertThat(enqueuedCount()).isEqualTo(expected));
    }

    private double counter(String reason) {
        Counter c = meterRegistry.find("telegram_webhook_rejected_total")
                .tag("reason", reason).counter();
        return c == null ? 0.0 : c.count();
    }

    // ---------- happy path / AC1 controller-side enqueue ----------

    @Test
    void receive_happyPath_returns200_persistsAndEnqueues() throws Exception {
        // AC6 (controller-side) — single POST → single RawUpdate, single ENQUEUED job, success
        // counter incremented exactly once. Bigger AC6 (ownerChatId populate) is owned by
        // ProcessTelegramUpdateJobTest in T9.
        Project p = seedProject();
        seedBot(p.getId(), BotStatus.CONNECTED, SECRET_HASH);

        mockMvc.perform(post("/webhooks/telegram/{projectId}", p.getId())
                        .header("X-Telegram-Bot-Api-Secret-Token", SECRET_PLAIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson(samplePayload(42L))))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200))
                .andExpect(result -> assertThat(result.getResponse().getContentLength()).isZero());

        assertThat(rawUpdateRepository.count()).isEqualTo(1L);
        awaitEnqueuedCount(1L);
        Counter rec = meterRegistry.find("telegram_webhook_received_total").counter();
        assertThat(rec).isNotNull();
        assertThat(rec.count()).isEqualTo(1.0);
    }

    // ---------- AC2 — invalid secret ----------

    @Test
    void receive_invalidSecret_returns401EmptyBody_counterTicked() {
        Project p = seedProject();
        seedBot(p.getId(), BotStatus.CONNECTED, SECRET_HASH);

        assertThat(doPost(p.getId(), "wrong_secret_value", samplePayload(1L))).isEqualTo(401);
        assertThat(doPost(p.getId(), "", samplePayload(2L))).isEqualTo(401);
        // Missing header — WebSecretVerifier maps null → false → 401.
        assertThat(doPost(p.getId(), null, samplePayload(3L))).isEqualTo(401);

        assertThat(counter("invalid_secret")).isEqualTo(3.0);
        assertThat(rawUpdateRepository.count()).isZero();
        assertThat(enqueuedCount()).isZero();
    }

    // ---------- AC3 — 404 anti-enumeration ----------

    @Test
    void receive_missingProject_returns404EmptyBody_counterTicked() {
        String missingId = new org.bson.types.ObjectId().toHexString();

        assertThat(doPost(missingId, SECRET_PLAIN, samplePayload(1L))).isEqualTo(404);
        assertThat(counter("project_not_found")).isEqualTo(1.0);
    }

    @Test
    void receive_softDeletedProject_returns404() {
        Project p = seedSoftDeletedProject();
        seedBot(p.getId(), BotStatus.CONNECTED, SECRET_HASH);

        assertThat(doPost(p.getId(), SECRET_PLAIN, samplePayload(1L))).isEqualTo(404);
        assertThat(counter("project_not_found")).isEqualTo(1.0);
        assertThat(rawUpdateRepository.count()).isZero();
    }

    @Test
    void receive_disconnectedBot_returns404() {
        Project p = seedProject();
        seedBot(p.getId(), BotStatus.DISCONNECTED, SECRET_HASH);

        assertThat(doPost(p.getId(), SECRET_PLAIN, samplePayload(1L))).isEqualTo(404);
        assertThat(counter("project_not_found")).isEqualTo(1.0);
    }

    @Test
    void receive_malformedObjectId_returns404() {
        // Malformed projectId — Bot.id is an ObjectId; Spring Data raises IllegalArgumentException
        // BEFORE the Mongo round-trip. The controller maps that to 404 via try/catch so an
        // attacker cannot distinguish "bad id" from "missing project" by status code.
        assertThat(doPost("not-an-objectid", SECRET_PLAIN, samplePayload(1L))).isEqualTo(404);
        assertThat(counter("project_not_found")).isEqualTo(1.0);
    }

    // ---------- AC5 — idempotency on duplicate updateId ----------

    @Test
    void receive_duplicateUpdateId_returns200_singleRowSingleJob() {
        Project p = seedProject();
        seedBot(p.getId(), BotStatus.CONNECTED, SECRET_HASH);

        // Run two POSTs with the same updateId in parallel via the VT barrier utility (D10).
        // With deterministic UUID, even if both DuplicateKey self-heal through to enqueue,
        // BackgroundJob.enqueue(UUID, ...) is idempotent — collapse to one job.
        List<Integer> statuses = ConcurrencyTestUtils.parallelInvoke(2,
                () -> doPost(p.getId(), SECRET_PLAIN, samplePayload(99L)));

        assertThat(statuses).containsOnly(200);
        assertThat(rawUpdateRepository.count()).isEqualTo(1L);
        awaitEnqueuedCount(1L);
        // Pin self-heal branch fired exactly once — proves the (projectId, updateId) unique
        // index rejected the second insert AND the catch(DuplicateKeyException) ran. Without
        // this, an absent index could pass (both inserts succeed → counter stays 0 → row count
        // could still be 1 by upsert semantics if anyone introduces them later).
        assertThat(counter("duplicate"))
                .as("exactly one of the two parallel POSTs must hit the self-heal branch")
                .isEqualTo(1.0);
    }

    @Test
    void receive_enqueueFailsThenRetries_selfHealsToSingleJob() {
        // Decision 4/11 self-heal scenario — first attempt: save commits but enqueue throws;
        // second attempt: Telegram retries the same updateId → DuplicateKey path → still enqueue
        // → succeeds. One row, one ENQUEUED job after both attempts.
        Project p = seedProject();
        seedBot(p.getId(), BotStatus.CONNECTED, SECRET_HASH);

        boolean[] firstAttempt = {true};
        controller.setEnqueuer((jobId, rawUpdateId) -> {
            if (firstAttempt[0]) {
                firstAttempt[0] = false;
                throw new RuntimeException("simulated enqueue failure");
            }
            jobScheduler.<ProcessTelegramUpdateJob>enqueue(
                    jobId, j -> j.handle(rawUpdateId));
        });

        // First POST fails 500 — Telegram retries. The controller's outer try/catch converts the
        // enqueue failure into an empty 500 (Decision 3 forbids GlobalErrorHandler bodies).
        int s1 = doPost(p.getId(), SECRET_PLAIN, samplePayload(123L));
        assertThat(s1).isEqualTo(500);

        // Retry — DuplicateKey path re-enqueues successfully.
        int s2 = doPost(p.getId(), SECRET_PLAIN, samplePayload(123L));
        assertThat(s2).isEqualTo(200);

        assertThat(rawUpdateRepository.count()).isEqualTo(1L);
        awaitEnqueuedCount(1L);
        assertThat(counter("duplicate")).isEqualTo(1.0);
    }

    // ---------- AC15 — CSRF regression ----------

    @Test
    void api_csrfRegression_postWithoutXsrfToken_rejected() throws Exception {
        // The scoped CSRF disable (Decision 13, T11) must NOT leak past /webhooks/telegram. A
        // /api/v1/projects POST from a bare client (no auth, no CSRF token) is rejected by the
        // security chain — Spring Security 6.x serves the CSRF check first for state-changing
        // verbs, so the rejection status is 403 (not 401). The strong invariant: NOT 200/2xx.
        int status = mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson(new Document("name", "anything"))))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(403);
    }

    // ---------- AC16 — counter shape after mixed traffic ----------

    @Test
    void countersAfterMixedTraffic_exactValues() {
        Project p = seedProject();
        seedBot(p.getId(), BotStatus.CONNECTED, SECRET_HASH);

        // 1 happy path
        doPost(p.getId(), SECRET_PLAIN, samplePayload(1L));
        // 2 invalid-secret
        doPost(p.getId(), "wrong1", samplePayload(2L));
        doPost(p.getId(), "wrong2", samplePayload(3L));
        // 1 not-found (missing project)
        doPost(new org.bson.types.ObjectId().toHexString(), SECRET_PLAIN, samplePayload(4L));
        // 1 duplicate of update_id=1 → DuplicateKey path
        doPost(p.getId(), SECRET_PLAIN, samplePayload(1L));

        Counter received = meterRegistry.find("telegram_webhook_received_total").counter();
        assertThat(received).isNotNull();
        assertThat(received.count()).isEqualTo(1.0);

        assertThat(counter("invalid_secret")).isEqualTo(2.0);
        assertThat(counter("project_not_found")).isEqualTo(1.0);
        assertThat(counter("duplicate")).isEqualTo(1.0);
    }

    // ---------- AC18 — token-scrubber per log site ----------

    @Test
    void warnLogOnDuplicate_noTokenInOutput_andNoPayloadEcho() {
        // Token regex won't naturally match projectId/updateId, so a plain scrub-regex assertion
        // is too weak (the WARN line could lose scrubTokens entirely and still pass). Two-part
        // assertion makes the test non-vacuous:
        //  (a) the WARN line must NOT contain any field from the request body (no payload echo),
        //  (b) the WARN line must NOT contain a Telegram-token-shaped substring even when one
        //      sneaks into the projectId via the path (mid-request injection).
        Project p = seedProject();
        seedBot(p.getId(), BotStatus.CONNECTED, SECRET_HASH);
        String tokenShaped = "1234567890:ABCdefGHI_jklMNOpqrSTUvwxYZ0123456789xyz";
        Document body = samplePayload(1L);
        body.put("sentinel_field", "PAYLOAD-SENTINEL-" + tokenShaped);

        doPost(p.getId(), SECRET_PLAIN, body);
        doPost(p.getId(), SECRET_PLAIN, body); // dup → WARN

        List<String> warnLines = appender.list.stream()
                .filter(e -> e.getLoggerName()
                        .equals("com.botfunnel.webhook.TelegramWebhookController"))
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(warnLines).isNotEmpty();
        for (String line : warnLines) {
            assertThat(line)
                    .as("WARN log must NOT echo the request body: <%s>", line)
                    .doesNotContain("PAYLOAD-SENTINEL");
            assertThat(TOKEN_PATTERN.matcher(line).find())
                    .as("WARN log must NOT contain a Telegram-token-shaped substring: <%s>", line)
                    .isFalse();
        }
    }

    @Test
    void errorLogOnEnqueueFail_noTokenInOutput() {
        Project p = seedProject();
        seedBot(p.getId(), BotStatus.CONNECTED, SECRET_HASH);
        // Force enqueue failure with a token-shaped error message — the scrubber must redact.
        String tokenShaped = "1234567890:ABCdefGHI_jklMNOpqrSTUvwxYZ0123456789xyz";
        controller.setEnqueuer((jobId, rawUpdateId) -> {
            throw new RuntimeException("enqueue failure carrying " + tokenShaped + " inline");
        });

        doPost(p.getId(), SECRET_PLAIN, samplePayload(7L));

        List<String> errLines = appender.list.stream()
                .filter(e -> e.getLoggerName()
                        .equals("com.botfunnel.webhook.TelegramWebhookController"))
                .filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(errLines).isNotEmpty();
        for (String line : errLines) {
            assertThat(TOKEN_PATTERN.matcher(line).find())
                    .as("ERROR log must NOT contain a Telegram-token-shaped substring: <%s>", line)
                    .isFalse();
        }
    }

    // ---------- AC4 — 413 end-to-end (audit T14 F4) ----------

    @Test
    void receive_chunkedEncoding_returns413_endToEnd() throws Exception {
        // AC4 (chunked) end-to-end IT companion to the unit-scope WebhookPayloadSizeFilterTest.
        // Proves the filter is registered in the chain at the controller's actual path AND that
        // the SecurityConfig permitAll did not bypass it (filter runs at HIGHEST_PRECEDENCE+10).
        Project p = seedProject();
        seedBot(p.getId(), BotStatus.CONNECTED, SECRET_HASH);

        int status = mockMvc.perform(post("/webhooks/telegram/{projectId}", p.getId())
                        .header("X-Telegram-Bot-Api-Secret-Token", SECRET_PLAIN)
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson(samplePayload(1L))))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(413);

        Counter c = meterRegistry.find("telegram_webhook_rejected_total")
                .tag("reason", "payload_too_large").counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isGreaterThanOrEqualTo(1.0);
    }

    // ---------- AC16 — Timer present (audit T14 F7) ----------

    @Test
    void receive_happyPath_recordsDurationTimer() {
        // AC16 requires telegram_webhook_duration_seconds to be present and recording. The unit
        // tests cover the rejected counters; this IT pins the Timer's existence + record count.
        Project p = seedProject();
        seedBot(p.getId(), BotStatus.CONNECTED, SECRET_HASH);

        doPost(p.getId(), SECRET_PLAIN, samplePayload(1L));

        io.micrometer.core.instrument.Timer timer = meterRegistry
                .find("telegram_webhook_duration_seconds").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isGreaterThanOrEqualTo(1L);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS))
                .as("Timer must record positive duration")
                .isGreaterThan(0.0);
    }

    @Test
    void receive_anotherBotsSecret_returns401() {
        // Defense-in-depth: a CONNECTED bot exists with a different secret hash. Pre-image
        // matching MUST fail; we MUST hit the invalid_secret counter, not the project_not_found.
        Project p = seedProject();
        seedBot(p.getId(), BotStatus.CONNECTED, SECRET_HASH_OTHER);

        assertThat(doPost(p.getId(), SECRET_PLAIN, samplePayload(1L))).isEqualTo(401);
        assertThat(counter("invalid_secret")).isEqualTo(1.0);
        assertThat(counter("project_not_found")).isZero();
    }

    // ---------- missing update_id → 400 (per TDD anchor) ----------

    @Test
    void receive_missingUpdateId_returns400EmptyBody() throws Exception {
        // Telegram contract guarantees update_id. A payload without it cannot satisfy the
        // (projectId, updateId) unique index — controller rejects with 400 + scrubbed WARN log,
        // never persists a row, never enqueues a job.
        Project p = seedProject();
        seedBot(p.getId(), BotStatus.CONNECTED, SECRET_HASH);

        Document noUpdateId = new Document().append("message",
                new Document("chat", new Document("id", 100L).append("type", "private"))
                        .append("text", "/start"));

        int status = mockMvc.perform(post("/webhooks/telegram/{projectId}", p.getId())
                        .header("X-Telegram-Bot-Api-Secret-Token", SECRET_PLAIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson(noUpdateId)))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(400);

        assertThat(rawUpdateRepository.count()).isZero();
        assertThat(enqueuedCount()).isZero();

        // Decision 3 pins: scrubbed WARN log site exists for the missing-update_id rejection.
        List<String> warnLines = appender.list.stream()
                .filter(e -> e.getLoggerName()
                        .equals("com.botfunnel.webhook.TelegramWebhookController"))
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(warnLines)
                .as("controller must emit a scrubbed WARN line on missing update_id")
                .anySatisfy(line -> assertThat(line).contains("missing update_id"));
        for (String line : warnLines) {
            assertThat(TOKEN_PATTERN.matcher(line).find())
                    .as("WARN log must NOT contain a Telegram-token-shaped substring: <%s>", line)
                    .isFalse();
        }
    }
}
