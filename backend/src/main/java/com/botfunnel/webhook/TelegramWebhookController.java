package com.botfunnel.webhook;

import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.bot.TelegramApiClient;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

// Decisions 1, 2, 3, 4, 11, 16. Synchronous controller — returns ResponseEntity<Void> with
// explicit status, never throws AppException, never goes through GlobalErrorHandler. Lookup
// order is bot-CONNECTED → project (existence + soft-delete) → secret verify → persist +
// enqueue. Owns 4 counters + 1 Timer; the FIFTH counter (oversize-body reason) lives on
// WebhookPayloadSizeFilter — the filter short-circuits before the controller and the counter
// must tick where the rejection happens. Grep guard: this file must NOT contain the oversize
// reason string (per tech-spec AC).
@RestController
@RequestMapping("/webhooks/telegram")
public class TelegramWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookController.class);

    private static final String RECEIVED_TOTAL = "telegram_webhook_received_total";
    private static final String REJECTED_TOTAL = "telegram_webhook_rejected_total";
    private static final String DURATION_SECONDS = "telegram_webhook_duration_seconds";

    private final BotRepository botRepository;
    private final ProjectRepository projectRepository;
    private final RawUpdateRepository rawUpdateRepository;
    private final WebhookSecretVerifier webhookSecretVerifier;
    private final MeterRegistry meterRegistry;
    private final JobScheduler jobScheduler;

    // Counters cached in @PostConstruct so Tag.of allocations stay off the hot path. The success
    // counter remains tag-per-request (projectId) because user-spec AC16 implies per-project
    // visibility; SimpleMeterRegistry tolerates the cardinality at our expected volume.
    private Counter rejectedInvalidSecret;
    private Counter rejectedProjectNotFound;
    private Counter rejectedDuplicate;
    private Timer durationTimer;

    // Package-private seam for tests — lets the self-heal IT swap in a failing enqueue while
    // production routes through the injected JobScheduler. Injecting JobScheduler (instead of
    // calling static BackgroundJob.enqueue) keeps the test's autowired StorageProvider on the
    // hot path; the static API uses whichever JobScheduler was registered last, which under
    // multi-context Spring Test caching can be a different StorageProvider than the one the
    // test autowires (silent enqueue → different store → count() returns 0).
    private BiConsumer<UUID, String> enqueuer;

    public TelegramWebhookController(BotRepository botRepository,
                                     ProjectRepository projectRepository,
                                     RawUpdateRepository rawUpdateRepository,
                                     WebhookSecretVerifier webhookSecretVerifier,
                                     MeterRegistry meterRegistry,
                                     JobScheduler jobScheduler) {
        this.botRepository = botRepository;
        this.projectRepository = projectRepository;
        this.rawUpdateRepository = rawUpdateRepository;
        this.webhookSecretVerifier = webhookSecretVerifier;
        this.meterRegistry = meterRegistry;
        this.jobScheduler = jobScheduler;
        this.enqueuer = (jobId, rawUpdateId) ->
                jobScheduler.<ProcessTelegramUpdateJob>enqueue(jobId, j -> j.handle(rawUpdateId));
    }

    @PostConstruct
    void cacheCounters() {
        this.rejectedInvalidSecret = Counter.builder(REJECTED_TOTAL)
                .tag("reason", "invalid_secret").register(meterRegistry);
        this.rejectedProjectNotFound = Counter.builder(REJECTED_TOTAL)
                .tag("reason", "project_not_found").register(meterRegistry);
        this.rejectedDuplicate = Counter.builder(REJECTED_TOTAL)
                .tag("reason", "duplicate").register(meterRegistry);
        this.durationTimer = Timer.builder(DURATION_SECONDS).register(meterRegistry);
    }

    // Test seam — override the enqueue strategy for failure-injection scenarios.
    void setEnqueuer(BiConsumer<UUID, String> enqueuer) {
        this.enqueuer = enqueuer;
    }

    @PostMapping("/{projectId}")
    public ResponseEntity<Void> receive(
            @PathVariable String projectId,
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String headerSecret,
            @RequestBody Document body) {

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // Decision 1 ordering: bot CONNECTED first (most-frequent miss), then project existence +
            // soft-delete, then secret verify, then persist + enqueue. IllegalArgumentException from
            // Spring Data's ObjectId parse collapses into the same 404 path so an attacker cannot
            // distinguish "malformed id" from "missing project" by status.
            Optional<Bot> botOpt;
            try {
                botOpt = botRepository.findByProjectIdAndStatus(projectId, BotStatus.CONNECTED);
            } catch (IllegalArgumentException ex) {
                botOpt = Optional.empty();
            }
            if (botOpt.isEmpty()) {
                rejectedProjectNotFound.increment();
                return ResponseEntity.notFound().build();
            }

            Optional<Project> projectOpt;
            try {
                projectOpt = projectRepository.findById(projectId)
                        .filter(p -> p.getDeletedAt() == null);
            } catch (IllegalArgumentException ex) {
                projectOpt = Optional.empty();
            }
            if (projectOpt.isEmpty()) {
                rejectedProjectNotFound.increment();
                return ResponseEntity.notFound().build();
            }

            Bot bot = botOpt.get();
            if (!webhookSecretVerifier.verify(headerSecret, bot.getWebhookSecretHash())) {
                rejectedInvalidSecret.increment();
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            Long updateId = extractUpdateId(body);
            if (updateId == null) {
                // Telegram contract guarantees update_id. Without it, the (projectId, updateId)
                // unique index cannot enforce idempotency — refuse rather than persist a bad row.
                log.warn("TelegramWebhookController - rejecting payload with missing update_id (projectId={})",
                        TelegramApiClient.scrubTokens(projectId));
                return ResponseEntity.badRequest().build();
            }

            RawUpdate row = new RawUpdate();
            row.setProjectId(projectId);
            row.setUpdateId(updateId);
            row.setPayload(body);
            row.setProcessingStatus(RawUpdateStatus.PENDING);
            row.setCreatedAt(Instant.now());

            try {
                RawUpdate saved = rawUpdateRepository.save(row);
                enqueueIdempotent(saved.getId());
                meterRegistry.counter(RECEIVED_TOTAL, "projectId", projectId).increment();
                return ResponseEntity.ok().build();
            } catch (DuplicateKeyException ex) {
                // Decision 4 self-heal: prior insert succeeded but enqueue may have failed.
                // Telegram retries → DuplicateKey on the (projectId, updateId) unique index
                // → still re-enqueue (deterministic UUID makes it idempotent at JobRunr) →
                // return 200 so Telegram stops retrying.
                rejectedDuplicate.increment();
                log.warn("TelegramWebhookController - duplicate update (projectId={}, updateId={})",
                        TelegramApiClient.scrubTokens(projectId),
                        TelegramApiClient.scrubTokens(String.valueOf(updateId)));
                Optional<RawUpdate> existing =
                        rawUpdateRepository.findFirstByProjectIdAndUpdateId(projectId, updateId);
                if (existing.isPresent()) {
                    enqueueIdempotent(existing.get().getId());
                }
                return ResponseEntity.ok().build();
            }
        } catch (Throwable t) {
            // Per Decision 3, the webhook MUST NOT surface through GlobalErrorHandler — every
            // response shape stays ResponseEntity<Void> with explicit status. Catch any
            // non-DuplicateKey error (the most common case: enqueue failed and propagated up from
            // enqueueIdempotent) and respond with an empty 500 so Telegram retries against an
            // explicit status rather than receiving the GlobalErrorHandler JSON body — which would
            // also bypass the scrubber chain. The enqueue site itself has already logged the
            // scrubbed cause.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } finally {
            sample.stop(durationTimer);
        }
    }

    private void enqueueIdempotent(String rawUpdateId) {
        UUID jobId = UUID.nameUUIDFromBytes(rawUpdateId.getBytes(StandardCharsets.UTF_8));
        try {
            enqueuer.accept(jobId, rawUpdateId);
        } catch (RuntimeException ex) {
            // Decision 11: enqueue failure propagates so Telegram retries; the retry hits the
            // DuplicateKey self-heal next time. The outer try/catch on the caller converts the
            // exception to an empty 500 so the GlobalErrorHandler never gets a chance to write a
            // body. Token-scrub both the rawUpdateId and the exception message (a Telegram-token-
            // shaped string could conceivably end up in a payload-derived exception).
            log.error("TelegramWebhookController - enqueue failed (rawUpdateId={}): {}",
                    TelegramApiClient.scrubTokens(rawUpdateId),
                    TelegramApiClient.scrubTokens(ex.getMessage()));
            throw ex;
        }
    }

    private static Long extractUpdateId(Document body) {
        // body.get("update_id") may surface as Integer or Long depending on Mongo's BSON coercion.
        // toString() + Long.valueOf is the safe path; the Telegram contract guarantees update_id
        // is a 32/64-bit integer.
        Object raw = body.get("update_id");
        if (raw == null) return null;
        return Long.valueOf(raw.toString());
    }

}
