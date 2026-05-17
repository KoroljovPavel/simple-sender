package com.botfunnel.webhook;

import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.bot.TelegramApiClient;
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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BiConsumer;

// Decisions 1, 2, 3, 4, 11, 16. Returns Mono<ResponseEntity<Void>> with explicit status — never
// throws AppException, never goes through GlobalErrorHandler. Lookup order is bot-CONNECTED →
// project (existence + soft-delete) → secret verify → persist + enqueue. Owns 4 counters + 1 Timer;
// the FIFTH counter (oversize-body reason) lives on WebhookPayloadSizeFilter (T11) — the filter
// short-circuits before the controller and the counter must tick where the rejection happens.
// Grep guard: this file must NOT contain the oversize reason string (per tech-spec AC).
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
    public Mono<ResponseEntity<Void>> receive(
            @PathVariable String projectId,
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String headerSecret,
            @RequestBody Document body) {

        Timer.Sample sample = Timer.start(meterRegistry);

        // Decision 1 ordering: bot CONNECTED first (most-frequent miss), then project existence +
        // soft-delete, then secret verify, then persist + enqueue. onErrorMap collapses malformed
        // ObjectId (IllegalArgumentException from Spring Data's ObjectId parse) into the same 404
        // path so an attacker cannot distinguish "malformed id" from "missing project" by status.
        return botRepository.findByProjectIdAndStatus(projectId, BotStatus.CONNECTED)
                .onErrorResume(IllegalArgumentException.class, ex -> Mono.empty())
                .flatMap(bot -> projectRepository.findById(projectId)
                        .filter(p -> p.getDeletedAt() == null)
                        .map(p -> bot))
                .flatMap(bot -> handleVerified(projectId, headerSecret, body, bot))
                .switchIfEmpty(Mono.defer(() -> {
                    rejectedProjectNotFound.increment();
                    return Mono.just(ResponseEntity.notFound().<Void>build());
                }))
                .doFinally(signal -> sample.stop(durationTimer));
    }

    private Mono<ResponseEntity<Void>> handleVerified(String projectId, String headerSecret,
                                                      Document body, Bot bot) {
        if (!webhookSecretVerifier.verify(headerSecret, bot.getWebhookSecretHash())) {
            rejectedInvalidSecret.increment();
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).<Void>build());
        }

        Long updateId = extractUpdateId(body);
        if (updateId == null) {
            // Telegram contract guarantees update_id. Without it, the (projectId, updateId)
            // unique index cannot enforce idempotency — refuse rather than persist a bad row.
            log.warn("TelegramWebhookController - rejecting payload with missing update_id (projectId={})",
                    TelegramApiClient.scrubTokens(projectId));
            return Mono.just(ResponseEntity.badRequest().<Void>build());
        }

        RawUpdate row = new RawUpdate();
        row.setProjectId(projectId);
        row.setUpdateId(updateId);
        row.setPayload(body);
        row.setProcessingStatus(RawUpdateStatus.PENDING);
        row.setCreatedAt(Instant.now());

        return rawUpdateRepository.save(row)
                .flatMap(saved -> enqueueIdempotent(saved.getId())
                        .doOnSuccess(unused -> meterRegistry.counter(RECEIVED_TOTAL,
                                "projectId", projectId).increment())
                        .thenReturn(ResponseEntity.ok().<Void>build()))
                .onErrorResume(DuplicateKeyException.class, ex -> {
                    // Decision 4 self-heal: prior insert succeeded but enqueue may have failed.
                    // Telegram retries → DuplicateKey on the (projectId, updateId) unique index
                    // → still re-enqueue (deterministic UUID makes it idempotent at JobRunr) →
                    // return 200 so Telegram stops retrying.
                    rejectedDuplicate.increment();
                    log.warn("TelegramWebhookController - duplicate update (projectId={}, updateId={})",
                            TelegramApiClient.scrubTokens(projectId),
                            TelegramApiClient.scrubTokens(String.valueOf(updateId)));
                    return rawUpdateRepository.findFirstByProjectIdAndUpdateId(projectId, updateId)
                            .flatMap(existing -> enqueueIdempotent(existing.getId())
                                    .thenReturn(ResponseEntity.ok().<Void>build()));
                })
                // Per Decision 3, the webhook MUST NOT surface through GlobalErrorHandler — every
                // response shape stays Mono<ResponseEntity<Void>>. Catch any non-DuplicateKey error
                // (the most common case: enqueue failed and propagated up from enqueueIdempotent)
                // and respond with an empty 500 so Telegram retries against an explicit status
                // rather than receiving the GlobalErrorHandler JSON body — which would also bypass
                // the scrubber chain. The enqueue site itself has already logged the scrubbed cause.
                .onErrorResume(ex -> Mono.just(
                        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).<Void>build()));
    }

    private Mono<Void> enqueueIdempotent(String rawUpdateId) {
        UUID jobId = UUID.nameUUIDFromBytes(rawUpdateId.getBytes(StandardCharsets.UTF_8));
        return Mono.fromRunnable(() -> enqueuer.accept(jobId, rawUpdateId))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ex -> {
                    // Decision 11: enqueue failure propagates so Telegram retries (via the
                    // Mono.error path); the retry hits the DuplicateKey self-heal next time.
                    // The outer handleVerified.onErrorResume converts the error to an empty 5xx
                    // so the GlobalErrorHandler never gets a chance to write a body. Token-scrub
                    // both the rawUpdateId and the exception message (a Telegram-token-shaped
                    // string could conceivably end up in a payload-derived exception).
                    log.error("TelegramWebhookController - enqueue failed (rawUpdateId={}): {}",
                            TelegramApiClient.scrubTokens(rawUpdateId),
                            TelegramApiClient.scrubTokens(ex.getMessage()));
                    return Mono.error(ex);
                })
                .then();
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
