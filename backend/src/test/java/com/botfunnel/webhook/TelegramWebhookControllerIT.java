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
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

// Full-stack IT for T10 — covers user-spec AC1, AC2, AC3, AC5, AC6 (controller-side enqueue
// assertion only — ownerChatId populate is in ProcessTelegramUpdateJobTest), AC15, AC16, AC18.
// JobRunrInMemoryConfig means no worker thread runs; we read ENQUEUED counts directly from the
// in-memory StorageProvider so the test asserts only what the controller commits, not the
// downstream worker.
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
    @Autowired TelegramWebhookController controller;

    private ListAppender<ILoggingEvent> appender;
    private Logger controllerLogger;

    @BeforeEach
    void resetMeterAndJobs() {
        rawUpdateRepository.deleteAll().block();
        botRepository.deleteAll().block();
        projectRepository.deleteAll().block();
        storageProvider.deleteJobsPermanently(StateName.ENQUEUED, Instant.now().plusSeconds(60));
        storageProvider.deleteJobsPermanently(StateName.SUCCEEDED, Instant.now().plusSeconds(60));
        storageProvider.deleteJobsPermanently(StateName.FAILED, Instant.now().plusSeconds(60));
        // Reset counters between tests so per-test assertions on counter() == N are deterministic.
        meterRegistry.clear();
        // Recreate the cached counters in the controller — clear() removed them.
        controller.cacheCounters();

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
        // Restore default enqueuer in case a test swapped it.
        controller.setEnqueuer((jobId, rawUpdateId) ->
                org.jobrunr.scheduling.BackgroundJob.<ProcessTelegramUpdateJob>enqueue(
                        jobId, j -> j.handle(rawUpdateId)));
    }

    private Project seedProject() {
        Project p = new Project();
        p.setOwnerId("ownerX");
        p.setName("Project " + System.nanoTime());
        p.setTimezone("UTC");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return projectRepository.save(p).block();
    }

    private Project seedSoftDeletedProject() {
        Project p = seedProject();
        p.setDeletedAt(Instant.now());
        return projectRepository.save(p).block();
    }

    private Bot seedBot(String projectId, BotStatus status, String webhookSecretHash) {
        Bot b = new Bot();
        b.setProjectId(projectId);
        b.setTelegramBotId(1234567890L);
        b.setStatus(status);
        b.setWebhookSecretHash(webhookSecretHash);
        b.setConnectedAt(Instant.now());
        return botRepository.save(b).block();
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

    private int post(String projectId, String secretHeader, Document body) {
        return webTestClient.post()
                .uri("/webhooks/telegram/" + projectId)
                .header("X-Telegram-Bot-Api-Secret-Token", secretHeader == null ? "" : secretHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .returnResult(Void.class)
                .getStatus().value();
    }

    private long enqueuedCount() {
        // BackgroundJob.enqueue can return before the InMemoryStorageProvider's notify chain
        // settles when running under shared-context tests; await briefly until count stabilises.
        long latest = storageProvider.countJobs(StateName.ENQUEUED);
        long deadline = System.nanoTime() + 1_000_000_000L; // 1s safety bound
        while (latest == 0L && System.nanoTime() < deadline) {
            try { Thread.sleep(20); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            latest = storageProvider.countJobs(StateName.ENQUEUED);
        }
        return latest;
    }

    private double counter(String reason) {
        Counter c = meterRegistry.find("telegram_webhook_rejected_total")
                .tag("reason", reason).counter();
        return c == null ? 0.0 : c.count();
    }

    // ---------- happy path / AC1 controller-side enqueue ----------

    @Test
    void receive_happyPath_returns200_persistsAndEnqueues() {
        Project p = seedProject();
        seedBot(p.getId(), BotStatus.CONNECTED, SECRET_HASH);

        webTestClient.post()
                .uri("/webhooks/telegram/" + p.getId())
                .header("X-Telegram-Bot-Api-Secret-Token", SECRET_PLAIN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(samplePayload(42L))
                .exchange()
                .expectStatus().isOk()
                .expectBody().isEmpty();

        assertThat(rawUpdateRepository.count().block()).isEqualTo(1L);
        assertThat(enqueuedCount()).isEqualTo(1L);
        Counter rec = meterRegistry.find("telegram_webhook_received_total").counter();
        assertThat(rec).isNotNull();
        assertThat(rec.count()).isEqualTo(1.0);
    }

    @Test
    void receive_happyPath_postEnqueuedJobCountEqualsOne() {
        // AC6 — controller side: enqueue happens exactly once on happy path.
        Project p = seedProject();
        seedBot(p.getId(), BotStatus.CONNECTED, SECRET_HASH);

        assertThat(post(p.getId(), SECRET_PLAIN, samplePayload(1L))).isEqualTo(200);
        assertThat(enqueuedCount()).isEqualTo(1L);
    }

    // ---------- AC2 — invalid secret ----------

    @Test
    void receive_invalidSecret_returns401EmptyBody_counterTicked() {
        Project p = seedProject();
        seedBot(p.getId(), BotStatus.CONNECTED, SECRET_HASH);

        // Wrong header value.
        webTestClient.post()
                .uri("/webhooks/telegram/" + p.getId())
                .header("X-Telegram-Bot-Api-Secret-Token", "wrong_secret_value")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(samplePayload(1L))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().isEmpty();

        // Empty header value.
        webTestClient.post()
                .uri("/webhooks/telegram/" + p.getId())
                .header("X-Telegram-Bot-Api-Secret-Token", "")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(samplePayload(2L))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().isEmpty();

        // Missing header entirely — WebSecretVerifier maps null → false → 401.
        webTestClient.post()
                .uri("/webhooks/telegram/" + p.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(samplePayload(3L))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().isEmpty();

        assertThat(counter("invalid_secret")).isEqualTo(3.0);
        assertThat(rawUpdateRepository.count().block()).isZero();
        assertThat(enqueuedCount()).isZero();
    }

    // ---------- AC3 — 404 anti-enumeration ----------

    @Test
    void receive_missingProject_returns404EmptyBody_counterTicked() {
        String missingId = new org.bson.types.ObjectId().toHexString();

        webTestClient.post()
                .uri("/webhooks/telegram/" + missingId)
                .header("X-Telegram-Bot-Api-Secret-Token", SECRET_PLAIN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(samplePayload(1L))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().isEmpty();

        assertThat(counter("project_not_found")).isEqualTo(1.0);
    }

    @Test
    void receive_softDeletedProject_returns404() {
        Project p = seedSoftDeletedProject();
        seedBot(p.getId(), BotStatus.CONNECTED, SECRET_HASH);

        assertThat(post(p.getId(), SECRET_PLAIN, samplePayload(1L))).isEqualTo(404);
        assertThat(counter("project_not_found")).isEqualTo(1.0);
        assertThat(rawUpdateRepository.count().block()).isZero();
    }

    @Test
    void receive_disconnectedBot_returns404() {
        Project p = seedProject();
        seedBot(p.getId(), BotStatus.DISCONNECTED, SECRET_HASH);

        assertThat(post(p.getId(), SECRET_PLAIN, samplePayload(1L))).isEqualTo(404);
        assertThat(counter("project_not_found")).isEqualTo(1.0);
    }

    @Test
    void receive_malformedObjectId_returns404() {
        // Malformed projectId — Bot.id is an ObjectId; Spring Data raises IllegalArgumentException
        // BEFORE the Mongo round-trip. The controller maps that to 404 via onErrorResume so an
        // attacker cannot distinguish "bad id" from "missing project" by status code.
        assertThat(post("not-an-objectid", SECRET_PLAIN, samplePayload(1L))).isEqualTo(404);
        assertThat(counter("project_not_found")).isEqualTo(1.0);
    }

    // ---------- AC5 — idempotency on duplicate updateId ----------

    @Test
    void receive_duplicateUpdateId_returns200_singleRowSingleJob() {
        Project p = seedProject();
        seedBot(p.getId(), BotStatus.CONNECTED, SECRET_HASH);

        // Run two POSTs with the same updateId in parallel. runOn(boundedElastic) is required so
        // the in-process WebTestClient.bindToApplicationContext path doesn't serialise them
        // (BotControllerIT precedent). With deterministic UUID, even if both DuplicateKey self-heal
        // through to enqueue, BackgroundJob.enqueue(UUID, ...) is idempotent — collapse to one job.
        List<Integer> statuses = Flux.range(0, 2)
                .parallel(2).runOn(Schedulers.boundedElastic())
                .flatMap(i -> Mono.fromCallable(() -> post(p.getId(), SECRET_PLAIN, samplePayload(99L))))
                .sequential()
                .collectList()
                .block();

        assertThat(statuses).containsOnly(200);
        assertThat(rawUpdateRepository.count().block()).isEqualTo(1L);
        assertThat(enqueuedCount()).isEqualTo(1L);
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
            org.jobrunr.scheduling.BackgroundJob.<ProcessTelegramUpdateJob>enqueue(
                    jobId, j -> j.handle(rawUpdateId));
        });

        // First POST fails 500 — Telegram retries.
        int s1 = post(p.getId(), SECRET_PLAIN, samplePayload(123L));
        assertThat(s1).isGreaterThanOrEqualTo(500);

        // Retry — DuplicateKey path re-enqueues successfully.
        int s2 = post(p.getId(), SECRET_PLAIN, samplePayload(123L));
        assertThat(s2).isEqualTo(200);

        assertThat(rawUpdateRepository.count().block()).isEqualTo(1L);
        assertThat(enqueuedCount()).isEqualTo(1L);
        assertThat(counter("duplicate")).isEqualTo(1.0);
    }

    // ---------- AC1 latency P99 < 100ms ----------

    @Test
    @Tag("slow")
    void p99Latency_under100msAt100ParallelRequests() {
        Project p = seedProject();
        seedBot(p.getId(), BotStatus.CONNECTED, SECRET_HASH);

        WebTestClient client = webTestClient.mutate()
                .responseTimeout(java.time.Duration.ofSeconds(30))
                .build();

        List<Long> latenciesNanos = Flux.range(0, 100)
                .parallel(10).runOn(Schedulers.boundedElastic())
                .flatMap(i -> Mono.fromCallable(() -> {
                    long start = System.nanoTime();
                    client.post()
                            .uri("/webhooks/telegram/" + p.getId())
                            .header("X-Telegram-Bot-Api-Secret-Token", SECRET_PLAIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(samplePayload(System.nanoTime() + i))
                            .exchange()
                            .expectStatus().isOk();
                    return System.nanoTime() - start;
                }))
                .sequential()
                .collectList()
                .block();

        assertThat(latenciesNanos).isNotNull().hasSize(100);
        latenciesNanos.sort(Long::compareTo);
        long p99 = latenciesNanos.get(98);
        long p99Ms = p99 / 1_000_000L;
        // P99 < 100ms per user-spec AC1 — measures the WebFlux pipeline (no transport overhead
        // since WebTestClient binds to ApplicationContext).
        assertThat(p99Ms).as("P99 latency in ms — got %d", p99Ms).isLessThan(100L);
    }

    // ---------- AC15 — CSRF regression ----------

    @Test
    void api_csrfRegression_postWithoutXsrfToken_rejected() {
        // The scoped CSRF disable (Decision 13, T11) must NOT leak past /webhooks/telegram. A
        // /api/v1/projects POST from a bare client (no auth, no CSRF token) is rejected by the
        // security chain — Spring Security 6.x serves the CSRF check first for state-changing
        // verbs, so the rejection status is 403 (not 401). The strong invariant: NOT 200/2xx.
        // WebhookSecurityBlockTest (T11) covers the more nuanced 401 (authed-no-CSRF) split.
        webTestClient.post()
                .uri("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new Document("name", "anything"))
                .exchange()
                .expectStatus().isForbidden();
    }

    // ---------- AC16 — counter shape after mixed traffic ----------

    @Test
    void countersAfterMixedTraffic_exactValues() {
        Project p = seedProject();
        seedBot(p.getId(), BotStatus.CONNECTED, SECRET_HASH);

        // 1 happy path
        post(p.getId(), SECRET_PLAIN, samplePayload(1L));
        // 2 invalid-secret
        post(p.getId(), "wrong1", samplePayload(2L));
        post(p.getId(), "wrong2", samplePayload(3L));
        // 1 not-found (missing project)
        post(new org.bson.types.ObjectId().toHexString(), SECRET_PLAIN, samplePayload(4L));
        // 1 duplicate of update_id=1 → DuplicateKey path
        post(p.getId(), SECRET_PLAIN, samplePayload(1L));

        Counter received = meterRegistry.find("telegram_webhook_received_total").counter();
        assertThat(received).isNotNull();
        assertThat(received.count()).isEqualTo(1.0);

        assertThat(counter("invalid_secret")).isEqualTo(2.0);
        assertThat(counter("project_not_found")).isEqualTo(1.0);
        assertThat(counter("duplicate")).isEqualTo(1.0);
    }

    // ---------- AC18 — token-scrubber per log site ----------

    @Test
    void warnLogOnDuplicate_noTokenInOutput() {
        Project p = seedProject();
        seedBot(p.getId(), BotStatus.CONNECTED, SECRET_HASH);
        // Embed a Telegram-token-shaped value in projectId-like position is impossible (projectId
        // is path-derived), but we still ensure the WARN line carries scrubbed output. The token
        // scrub regex would NOT match plain projectId/updateId; the scrubTokens call site is the
        // defense-in-depth — assert no token regex appears in the captured log line for this site.
        post(p.getId(), SECRET_PLAIN, samplePayload(1L));
        post(p.getId(), SECRET_PLAIN, samplePayload(1L)); // dup → WARN

        List<String> warnLines = appender.list.stream()
                .filter(e -> e.getLoggerName()
                        .equals("com.botfunnel.webhook.TelegramWebhookController"))
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
    void errorLogOnEnqueueFail_noTokenInOutput() {
        Project p = seedProject();
        seedBot(p.getId(), BotStatus.CONNECTED, SECRET_HASH);
        // Force enqueue failure with a token-shaped error message — the scrubber must redact.
        String tokenShaped = "1234567890:ABCdefGHI_jklMNOpqrSTUvwxYZ0123456789xyz";
        controller.setEnqueuer((jobId, rawUpdateId) -> {
            throw new RuntimeException("enqueue failure carrying " + tokenShaped + " inline");
        });

        post(p.getId(), SECRET_PLAIN, samplePayload(7L));

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

    @Test
    void receive_anotherBotsSecret_returns401() {
        // Defense-in-depth: a CONNECTED bot exists with a different secret hash. Pre-image
        // matching MUST fail; we MUST hit the invalid_secret counter, not the project_not_found.
        Project p = seedProject();
        seedBot(p.getId(), BotStatus.CONNECTED, SECRET_HASH_OTHER);

        assertThat(post(p.getId(), SECRET_PLAIN, samplePayload(1L))).isEqualTo(401);
        assertThat(counter("invalid_secret")).isEqualTo(1.0);
        assertThat(counter("project_not_found")).isZero();
    }
}
