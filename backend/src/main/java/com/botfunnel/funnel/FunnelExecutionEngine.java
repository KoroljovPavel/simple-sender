package com.botfunnel.funnel;

import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberRepository;
import com.botfunnel.subscriber.SubscriberStatus;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Heart of Phase-1 funnels: a JobRunr {@code @Recurring} sweep that periodically picks up overdue
 * {@code funnel_executions}, atomically claims each one, and advances its steps via {@link StepExecutor}.
 *
 * <p><strong>At-most-once per step</strong> across replicas / JobRunr retries is guaranteed by a single
 * {@code findAndModify} CAS claim (Decision 4): the predicate {@code stepRunStatus == pending} flips to
 * {@code in_progress} atomically, so only one tick on one replica can own an execution at a time. Enum
 * statuses are written as lowercase {@code .name()} literals (Decision 14) to byte-match the indexes.
 *
 * <p><strong>Consecutive non-Delay steps run under that single claim</strong> within one tick — the
 * execution stays {@code in_progress} (NOT reset to {@code pending}) until it yields, so no second
 * replica can grab a half-processed execution and no per-step re-claim is needed. {@code stepRunStatus}
 * returns to {@code pending} only when the execution yields on a Delay (→ {@code waiting}); a crash
 * mid-tick leaves it {@code in_progress} (stuck, never re-sent) — the deliberate at-most-once trade-off
 * over liveness. Delay is expressed purely via {@code nextRunAt}, never {@code Thread.sleep} (Decision 9).
 *
 * <p>Observability (Decision 16): every transition logs a named constant with identifiers / codes only —
 * never the rendered text, caption, or custom-field value (PII).
 */
@Component
public class FunnelExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(FunnelExecutionEngine.class);

    static final String LOG_SWEEP_SATURATED = "FUNNEL_SWEEP_SATURATED";
    static final String LOG_SWEEP_EXECUTION_ERROR = "FUNNEL_SWEEP_EXECUTION_ERROR";
    static final String LOG_CLAIM_WON = "FUNNEL_CLAIM_WON";
    static final String LOG_CLAIM_LOST = "FUNNEL_CLAIM_LOST";
    static final String LOG_STEP_ADVANCED = "FUNNEL_STEP_ADVANCED";
    static final String LOG_EXECUTION_COMPLETED = "FUNNEL_EXECUTION_COMPLETED";
    static final String LOG_EXECUTION_CANCELLED = "FUNNEL_EXECUTION_CANCELLED";
    static final String LOG_EXECUTION_FAILED = "FUNNEL_EXECUTION_FAILED";
    static final String LOG_DELAY_SCHEDULED = "FUNNEL_DELAY_SCHEDULED";
    static final String LOG_SUBSCRIBER_INACTIVE = "FUNNEL_SUBSCRIBER_INACTIVE_CANCELLED";
    static final String LOG_BOT_NOT_CONNECTED = "FUNNEL_BOT_NOT_CONNECTED_FAILED";

    private final MongoTemplate mongoTemplate;
    private final SubscriberRepository subscriberRepository;
    private final BotRepository botRepository;
    private final StepExecutor stepExecutor;
    private final Clock clock;
    private final int batchSize;

    public FunnelExecutionEngine(MongoTemplate mongoTemplate,
                                 SubscriberRepository subscriberRepository,
                                 BotRepository botRepository,
                                 StepExecutor stepExecutor,
                                 Clock clock,
                                 @Value("${app.funnel.sweep-batch-size:200}") int batchSize) {
        this.mongoTemplate = mongoTemplate;
        this.subscriberRepository = subscriberRepository;
        this.botRepository = botRepository;
        this.stepExecutor = stepExecutor;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Recurring(id = "funnel-sweep", interval = "${app.funnel.scheduler-interval:PT30S}")
    @Job(name = "Funnel execution sweep")
    public void sweep() {
        Instant now = Instant.now(clock);
        // Oldest-first (nextRunAt asc) so no execution starves; capped at batchSize per tick. Backed by
        // the (status, nextRunAt) index. Statuses as lowercase .name() literals (Decision 14).
        Query query = Query.query(Criteria.where("status")
                        .in(ExecutionStatus.running.name(), ExecutionStatus.waiting.name())
                        .and("nextRunAt").lte(now))
                .with(Sort.by(Sort.Direction.ASC, "nextRunAt"))
                .limit(batchSize);
        List<FunnelExecution> due = mongoTemplate.find(query, FunnelExecution.class);

        if (due.size() >= batchSize) {
            log.warn("{} claimed={} batchSize={}", LOG_SWEEP_SATURATED, due.size(), batchSize);
        }

        for (FunnelExecution execution : due) {
            try {
                runExecution(execution.getId());
            } catch (RuntimeException ex) {
                // One bad execution must never abort the whole sweep tick.
                log.error("{} executionId={} error={}", LOG_SWEEP_EXECUTION_ERROR,
                        execution.getId(), ex.getClass().getSimpleName());
            }
        }
    }

    // Step-runner for ONE execution. Holds a single in_progress claim across all consecutive non-Delay
    // steps in this tick.
    private void runExecution(String executionId) {
        Instant now = Instant.now(clock);
        FunnelExecution exec = claim(executionId, now);
        if (exec == null) {
            // Another tick / replica won the CAS, or the row is no longer pending/due — benign no-op.
            log.debug("{} executionId={}", LOG_CLAIM_LOST, executionId);
            return;
        }
        log.info("{} executionId={} funnelId={} stepIndex={}", LOG_CLAIM_WON,
                exec.getId(), exec.getFunnelId(), exec.getCurrentStepIndex());

        // Resume-from-delay: a claimed 'waiting' execution means the Delay it parked on has elapsed.
        // The Delay step intentionally did NOT advance currentStepIndex when it yielded, so advance
        // past it now and resume as running.
        if (exec.getStatus() == ExecutionStatus.waiting) {
            exec.setCurrentStepIndex(exec.getCurrentStepIndex() + 1);
            exec.setStatus(ExecutionStatus.running);
        }

        // Pre-step status gate: a non-active (or vanished) subscriber cancels the execution before any send.
        Subscriber subscriber = subscriberRepository.findById(exec.getSubscriberId()).orElse(null);
        if (subscriber == null || subscriber.getStatus() != SubscriberStatus.ACTIVE) {
            terminate(exec, ExecutionStatus.cancelled, now, LOG_SUBSCRIBER_INACTIVE, null);
            return;
        }

        // Bot-pin check: the pinned telegramBotId must still resolve to a CONNECTED bot (Decision 7).
        Optional<Bot> bot = botRepository.findFirstByTelegramBotIdAndStatus(
                exec.getTelegramBotId(), BotStatus.CONNECTED);
        if (bot.isEmpty()) {
            terminate(exec, ExecutionStatus.failed, now, LOG_BOT_NOT_CONNECTED, null);
            return;
        }

        List<FunnelStep> snapshot = exec.getStepsSnapshot();
        int size = snapshot == null ? 0 : snapshot.size();
        // Iteration ceiling = number of steps + 1 (the trailing completion check) — Delay always exits
        // the loop, so the only way to iterate is a strictly increasing currentStepIndex (Decision 17).
        int guard = size + 1;
        while (exec.getStatus() == ExecutionStatus.running && guard-- > 0) {
            if (exec.getCurrentStepIndex() >= size) {
                complete(exec, now);
                return;
            }
            FunnelStep step = snapshot.get(exec.getCurrentStepIndex());
            StepExecutor.StepResult result = stepExecutor.execute(step, exec, subscriber, bot.get());
            switch (result.outcome()) {
                case CONTINUE -> {
                    exec.setCurrentStepIndex(exec.getCurrentStepIndex() + 1);
                    // Commit progress while STILL holding the in_progress claim — a crash after the
                    // side effect but before this write leaves the step un-advanced + in_progress
                    // (stuck, never re-sent), not re-executed. The write is conditional on the claim
                    // still being ours: if a concurrent terminal cancel (FunnelService.delete /
                    // FunnelTriggerService.cancelActiveFor/cancelExistingForPair) flipped the row, the
                    // CAS no-ops and we stop advancing — the cancel wins, no resurrection.
                    if (!persistProgress(exec, now)) {
                        log.debug("{} executionId={}", LOG_CLAIM_LOST, exec.getId());
                        return;
                    }
                    log.info("{} executionId={} stepType={} newStepIndex={}", LOG_STEP_ADVANCED,
                            exec.getId(), step.getStepType(), exec.getCurrentStepIndex());
                }
                case DELAY -> {
                    scheduleDelay(exec, now.plus(result.delay()));
                    return;
                }
                case CANCEL -> {
                    terminate(exec, ExecutionStatus.cancelled, now, LOG_EXECUTION_CANCELLED, result.reasonCode());
                    return;
                }
                case FAIL -> {
                    terminate(exec, ExecutionStatus.failed, now, LOG_EXECUTION_FAILED, result.reasonCode());
                    return;
                }
            }
        }
        // Reached only if the guard tripped (defensive) or the loop fell through at end-of-steps.
        if (exec.getStatus() == ExecutionStatus.running && exec.getCurrentStepIndex() >= size) {
            complete(exec, now);
        }
    }

    // ONE findAndModify CAS: claim the execution iff it is due (status running|waiting, nextRunAt<=now)
    // AND not already being processed (stepRunStatus pending). Flips pending→in_progress, returns the
    // claimed doc (returnNew). null = lost the race / no longer eligible.
    private FunnelExecution claim(String executionId, Instant now) {
        Query query = Query.query(Criteria.where("_id").is(executionId)
                .and("status").in(ExecutionStatus.running.name(), ExecutionStatus.waiting.name())
                .and("nextRunAt").lte(now)
                .and("stepRunStatus").is(StepRunStatus.pending.name()));
        Update update = new Update()
                .set("stepRunStatus", StepRunStatus.in_progress.name())
                .set("updatedAt", now);
        return mongoTemplate.findAndModify(query, update,
                new FindAndModifyOptions().returnNew(true), FunnelExecution.class);
    }

    // Every in-tick write is a CAS predicated on the claim still being ours (stepRunStatus=in_progress)
    // rather than a blind full-document save. The terminal cancellers set stepRunStatus=done, so once a
    // concurrent cancel lands this predicate no longer matches and the engine's write is a no-op it
    // detects (returnNew == null) — the cancel deterministically wins and a cancelled/deleted execution
    // can never be resurrected by the engine. Statuses written as lowercase .name() literals (Decision 14).
    private static Query stillClaimed(String executionId) {
        return Query.query(Criteria.where("_id").is(executionId)
                .and("stepRunStatus").is(StepRunStatus.in_progress.name()));
    }

    // Advance within the tick: keep stepRunStatus=in_progress so no other replica can claim mid-run.
    // Persists status too (a resumed-from-waiting execution flips waiting→running in memory and must
    // commit that). Returns false if the claim was lost to a concurrent cancel — caller stops the tick.
    private boolean persistProgress(FunnelExecution exec, Instant now) {
        Update update = new Update()
                .set("status", exec.getStatus().name())
                .set("currentStepIndex", exec.getCurrentStepIndex())
                .set("stepRunStatus", StepRunStatus.in_progress.name())
                .set("updatedAt", now);
        FunnelExecution updated = mongoTemplate.findAndModify(stillClaimed(exec.getId()), update,
                new FindAndModifyOptions().returnNew(true), FunnelExecution.class);
        if (updated == null) {
            return false;
        }
        exec.setUpdatedAt(now);
        return true;
    }

    // Yield on a Delay: park until nextRunAt, status=waiting, stepRunStatus=pending so the next tick can
    // re-claim. currentStepIndex is left on the Delay step (advanced on resume). No-op if the claim was
    // lost to a concurrent cancel.
    private void scheduleDelay(FunnelExecution exec, Instant nextRunAt) {
        Update update = new Update()
                .set("status", ExecutionStatus.waiting.name())
                .set("stepRunStatus", StepRunStatus.pending.name())
                .set("currentStepIndex", exec.getCurrentStepIndex())
                .set("nextRunAt", nextRunAt)
                .set("updatedAt", Instant.now(clock));
        FunnelExecution updated = mongoTemplate.findAndModify(stillClaimed(exec.getId()), update,
                new FindAndModifyOptions().returnNew(true), FunnelExecution.class);
        if (updated == null) {
            log.debug("{} executionId={}", LOG_CLAIM_LOST, exec.getId());
            return;
        }
        log.info("{} executionId={} stepIndex={}", LOG_DELAY_SCHEDULED,
                exec.getId(), exec.getCurrentStepIndex());
    }

    private void complete(FunnelExecution exec, Instant now) {
        Update update = new Update()
                .set("status", ExecutionStatus.completed.name())
                .set("stepRunStatus", StepRunStatus.done.name())
                .set("currentStepIndex", exec.getCurrentStepIndex())
                .set("completedAt", now)
                .set("updatedAt", now);
        FunnelExecution updated = mongoTemplate.findAndModify(stillClaimed(exec.getId()), update,
                new FindAndModifyOptions().returnNew(true), FunnelExecution.class);
        if (updated == null) {
            log.debug("{} executionId={}", LOG_CLAIM_LOST, exec.getId());
            return;
        }
        log.info("{} executionId={} funnelId={}", LOG_EXECUTION_COMPLETED, exec.getId(), exec.getFunnelId());
    }

    private void terminate(FunnelExecution exec, ExecutionStatus status, Instant now,
                           String logConstant, String reasonCode) {
        Update update = new Update()
                .set("status", status.name())
                .set("stepRunStatus", StepRunStatus.done.name())
                .set("currentStepIndex", exec.getCurrentStepIndex())
                .set("updatedAt", now);
        FunnelExecution updated = mongoTemplate.findAndModify(stillClaimed(exec.getId()), update,
                new FindAndModifyOptions().returnNew(true), FunnelExecution.class);
        if (updated == null) {
            log.debug("{} executionId={}", LOG_CLAIM_LOST, exec.getId());
            return;
        }
        log.info("{} executionId={} funnelId={} stepIndex={} reason={}", logConstant,
                exec.getId(), exec.getFunnelId(), exec.getCurrentStepIndex(), reasonCode);
    }
}
