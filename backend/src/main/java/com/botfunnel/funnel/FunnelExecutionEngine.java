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
    static final String LOG_STEP_BUDGET_EXCEEDED = "FUNNEL_STEP_BUDGET_EXCEEDED";
    static final String LOG_MENU_PARKED = "FUNNEL_MENU_PARKED";
    static final String LOG_RESUME_CALLBACK = "FUNNEL_RESUME_CALLBACK";
    static final String LOG_BROKEN_CURSOR = "FUNNEL_BROKEN_CURSOR_FAILED";

    private final MongoTemplate mongoTemplate;
    private final SubscriberRepository subscriberRepository;
    private final BotRepository botRepository;
    private final StepExecutor stepExecutor;
    private final Clock clock;
    private final int batchSize;
    private final int maxStepsPerTick;

    public FunnelExecutionEngine(MongoTemplate mongoTemplate,
                                 SubscriberRepository subscriberRepository,
                                 BotRepository botRepository,
                                 StepExecutor stepExecutor,
                                 Clock clock,
                                 @Value("${app.funnel.sweep-batch-size:200}") int batchSize,
                                 @Value("${app.funnel.max-steps-per-tick:100}") int maxStepsPerTick) {
        this.mongoTemplate = mongoTemplate;
        this.subscriberRepository = subscriberRepository;
        this.botRepository = botRepository;
        this.stepExecutor = stepExecutor;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxStepsPerTick = maxStepsPerTick;
    }

    @Recurring(id = "funnel-sweep", interval = "${app.funnel.scheduler-interval:PT30S}")
    @Job(name = "Funnel execution sweep")
    public void sweep() {
        Instant now = Instant.now(clock);
        // Oldest-first (nextRunAt asc) so no execution starves; capped at batchSize per tick. Backed by
        // the (status, nextRunAt) index. Statuses as lowercase .name() literals (Decision 14).
        Query query = Query.query(Criteria.where("status")
                        .in(ExecutionStatus.running.name(), ExecutionStatus.waiting.name(),
                                ExecutionStatus.waiting_for_reply.name())
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

    // Step-runner for ONE execution. Holds a single in_progress claim across all consecutive non-yielding
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

        // Resume-from-delay seam (Phase 1): a claimed 'waiting' execution means the Delay it parked on
        // has elapsed. The Delay step intentionally did NOT advance the cursor when it yielded, so
        // advance past it now (index +1 / next graph edge) and resume as running. This advance happens
        // BEFORE the subscriber/bot gates (preserved ordering), so an inactive-subscriber/disconnected-
        // bot terminates already on the new position.
        if (exec.getStatus() == ExecutionStatus.waiting) {
            advancePastDelay(exec);
            exec.setStatus(ExecutionStatus.running);
        } else if (exec.getStatus() == ExecutionStatus.waiting_for_reply) {
            // Timeout-resume seam (Phase 2): a claimed 'waiting_for_reply' execution that the sweep
            // picked up means its MENU timeout deadline elapsed (nextRunAt <= now). Follow the menu's
            // timeoutTargetStepId; null target completes. SAME pre-gate ordering as the delay seam:
            // the cursor is moved here, before the subscriber/bot gates. resumeOnCallback (button tap)
            // does NOT go through this seam — it has its own claim-CAS and sets the target directly.
            FunnelStep menuStep = currentStep(exec);
            if (menuStep != null && menuStep.getTimeoutTargetStepId() != null) {
                exec.setCurrentStepId(menuStep.getTimeoutTargetStepId());
                exec.setStatus(ExecutionStatus.running);
            } else {
                // No timeout target (or broken cursor) → completed on timeout.
                exec.setStatus(ExecutionStatus.running);
                complete(exec, now);
                return;
            }
        }

        StepContext ctx = preStepGates(exec, now);
        if (ctx == null) {
            return;
        }
        drive(exec, now, ctx);
    }

    /**
     * Callback-resume entry point (Decision 6, anti-IDOR). Wakes a parked {@code waiting_for_reply}
     * execution from a button tap, advancing its cursor directly to {@code targetStepId} (or completing
     * when the target is the "End" marker {@code null}). The claim-CAS is <strong>scoped by
     * {@code subscriberId}</strong>: only the execution's owner can resume it, so a forged
     * {@code callback_data} pointing at another subscriber's execution is a no-op. NOT routed through the
     * delay/timeout sweep seam — this is its own claim with its own navigation.
     *
     * <p>No paused-gate (Decision 11): an in-flight {@code waiting_for_reply} drains to completion even
     * on a paused funnel — pause only blocks starting new executions at the trigger.
     *
     * @return {@code true} if the claim was won and the execution was driven; {@code false} on a no-op
     *         (double click / stale / foreign subscriber / no longer parked) — Task 5 still calls
     *         {@code answerCallbackQuery} on a {@code false} return.
     */
    public boolean resumeOnCallback(String executionId, String subscriberId, String targetStepId) {
        Instant now = Instant.now(clock);
        FunnelExecution exec = claimForCallback(executionId, subscriberId, targetStepId, now);
        if (exec == null) {
            // Lost the CAS: double click (second tap loses), stale, foreign subscriber (IDOR), or no
            // longer waiting_for_reply/pending. Benign no-op.
            log.debug("{} executionId={}", LOG_CLAIM_LOST, executionId);
            return false;
        }
        log.info("{} executionId={} funnelId={}", LOG_RESUME_CALLBACK, exec.getId(), exec.getFunnelId());
        // The CAS already set status=running, currentStepId=targetStepId, stepRunStatus=in_progress in
        // the DB; mirror it onto the in-memory doc (returnNew already reflects it). Apply the pre-step
        // gates exactly as runExecution, then drive the chosen branch linearly.
        StepContext ctx = preStepGates(exec, now);
        if (ctx == null) {
            return true;
        }
        drive(exec, now, ctx);
        return true;
    }

    // Resolved (subscriber, bot) pair for one drive call. Passed by value so the singleton engine holds
    // NO per-execution mutable state — runExecution (sweep thread) and resumeOnCallback (webhook worker
    // thread) can run concurrently without cross-talk.
    private record StepContext(Subscriber subscriber, Bot bot) {}

    // Pre-step gates shared by runExecution and resumeOnCallback: a non-active (or vanished) subscriber
    // cancels; a pinned bot that no longer resolves to CONNECTED fails. Returns null (and terminates the
    // execution) when a gate blocks; otherwise the resolved (subscriber, bot) for the drive loop.
    private StepContext preStepGates(FunnelExecution exec, Instant now) {
        Subscriber subscriber = subscriberRepository.findById(exec.getSubscriberId()).orElse(null);
        if (subscriber == null || subscriber.getStatus() != SubscriberStatus.ACTIVE) {
            terminate(exec, ExecutionStatus.cancelled, now, LOG_SUBSCRIBER_INACTIVE, null);
            return null;
        }
        Optional<Bot> bot = botRepository.findFirstByTelegramBotIdAndStatus(
                exec.getTelegramBotId(), BotStatus.CONNECTED);
        if (bot.isEmpty()) {
            terminate(exec, ExecutionStatus.failed, now, LOG_BOT_NOT_CONNECTED, null);
            return null;
        }
        return new StepContext(subscriber, bot.get());
    }

    // Shared graph step-loop for runExecution and resumeOnCallback. Every CALLER does its own
    // resume-advance (delay index+1 / timeout targetStepId / callback targetStepId) BEFORE calling drive;
    // drive contains NO resume auto-advance. It resolves the current step by currentStepId (stepById),
    // executes it, and advances along step.next (default = next in list). The per-tick budget
    // (maxStepsPerTick) replaces the old size+1 guard because graph edges are not monotonic (loops).
    private void drive(FunnelExecution exec, Instant now, StepContext ctx) {
        List<FunnelStep> snapshot = exec.getStepsSnapshot();
        int size = snapshot == null ? 0 : snapshot.size();
        int budget = maxStepsPerTick;
        while (exec.getStatus() == ExecutionStatus.running) {
            if (budget-- <= 0) {
                // Loop without a park/delay burned the per-tick budget (Decision 4) → fail with a
                // greppable marker rather than spin forever.
                log.error("{} executionId={} funnelId={}", LOG_STEP_BUDGET_EXCEEDED,
                        exec.getId(), exec.getFunnelId());
                terminate(exec, ExecutionStatus.failed, now, LOG_STEP_BUDGET_EXCEEDED, "step_budget_exceeded");
                return;
            }
            FunnelStep step = currentStep(exec);
            if (step == null) {
                // End of graph (no cursor target) → completed. An empty snapshot also lands here.
                if (endOfGraph(exec, size)) {
                    complete(exec, now);
                    return;
                }
                // currentStepId set but resolves to no step in the snapshot (broken cursor / dangling
                // edge after a backfill anomaly or invalid graph) → fail-safe, never NPE.
                log.error("{} executionId={} funnelId={}", LOG_BROKEN_CURSOR, exec.getId(), exec.getFunnelId());
                terminate(exec, ExecutionStatus.failed, now, LOG_BROKEN_CURSOR, "broken_cursor");
                return;
            }
            StepExecutor.StepResult result = stepExecutor.execute(step, exec, ctx.subscriber(), ctx.bot());
            switch (result.outcome()) {
                case CONTINUE -> {
                    advanceToNext(exec, step);
                    // Commit progress while STILL holding the in_progress claim — a crash after the side
                    // effect but before this write leaves the step un-advanced + in_progress (stuck, never
                    // re-sent), not re-executed. The write is conditional on the claim still being ours
                    // (stillClaimed by _id+stepRunStatus, NOT by index): a concurrent terminal cancel
                    // no-ops it and the engine stops advancing — the cancel wins, no resurrection.
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
                case WAIT_FOR_REPLY -> {
                    parkForReply(exec, result.nextRunAt());
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
                case COMPLETE -> {
                    // Terminal success from a SUBSCRIBE_TO_FUNNEL step with endParentAfter: the enroll
                    // side-effect already ran inside the step; complete the parent (claim-conditional CAS,
                    // like the end-of-graph path) and run no further steps. drive is a switch STATEMENT, so
                    // this branch must be added explicitly — a missing case would fall through to a silent
                    // no-op, leaving the execution stuck in_progress.
                    complete(exec, now);
                    return;
                }
            }
        }
    }

    // ONE findAndModify CAS: claim the execution iff it is due (status running|waiting, nextRunAt<=now)
    // AND not already being processed (stepRunStatus pending). Flips pending→in_progress, returns the
    // claimed doc (returnNew). null = lost the race / no longer eligible.
    private FunnelExecution claim(String executionId, Instant now) {
        Query query = Query.query(Criteria.where("_id").is(executionId)
                .and("status").in(ExecutionStatus.running.name(), ExecutionStatus.waiting.name(),
                        ExecutionStatus.waiting_for_reply.name())
                .and("nextRunAt").lte(now)
                .and("stepRunStatus").is(StepRunStatus.pending.name()));
        Update update = new Update()
                .set("stepRunStatus", StepRunStatus.in_progress.name())
                .set("updatedAt", now);
        return mongoTemplate.findAndModify(query, update,
                new FindAndModifyOptions().returnNew(true), FunnelExecution.class);
    }

    // Callback-resume claim (Decision 6, anti-IDOR): atomically claim iff the execution is the SUBSCRIBER's
    // OWN parked menu (subscriberId scope), currently waiting_for_reply with stepRunStatus=pending. Flips
    // status→running, stepRunStatus→in_progress, and sets currentStepId=targetStepId directly (no auto
    // index +1). A forged callback_data with a foreign executionId fails the subscriberId predicate →
    // null → no-op. A double click loses the second CAS (status no longer waiting_for_reply). null target
    // ("End") is stored as-is so the drive loop resolves no step and completes.
    private FunnelExecution claimForCallback(String executionId, String subscriberId,
                                             String targetStepId, Instant now) {
        Query query = Query.query(Criteria.where("_id").is(executionId)
                .and("subscriberId").is(subscriberId)
                .and("status").is(ExecutionStatus.waiting_for_reply.name())
                .and("stepRunStatus").is(StepRunStatus.pending.name()));
        Update update = new Update()
                .set("status", ExecutionStatus.running.name())
                .set("currentStepId", targetStepId)
                .set("stepRunStatus", StepRunStatus.in_progress.name())
                .set("updatedAt", now);
        return mongoTemplate.findAndModify(query, update,
                new FindAndModifyOptions().returnNew(true), FunnelExecution.class);
    }

    // ─── graph navigation (Decision 2) ───────────────────────────────────────────

    // Resolve the step the cursor currently points at. Source of truth is currentStepId (stepById). When
    // currentStepId is null AND the run is a legacy linear Phase-1 run (graph mode off — see graphMode),
    // we fall back to currentStepIndex so the run drains (Decision 7). In graph mode a null currentStepId
    // is the explicit "End" marker, NOT an index fallback. Returns null at end-of-graph or on a broken
    // cursor (caller distinguishes the two via endOfGraph).
    private static FunnelStep currentStep(FunnelExecution exec) {
        List<FunnelStep> snapshot = exec.getStepsSnapshot();
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }
        String cursorId = exec.getCurrentStepId();
        if (cursorId == null) {
            if (graphMode(snapshot)) {
                // Graph run reached "End" (null target) → no current step.
                return null;
            }
            int idx = exec.getCurrentStepIndex();
            return idx >= 0 && idx < snapshot.size() ? snapshot.get(idx) : null;
        }
        return stepById(snapshot, cursorId);
    }

    // Linear lookup by stable step id (lists are small, <=50 steps — no caching needed).
    private static FunnelStep stepById(List<FunnelStep> snapshot, String id) {
        for (FunnelStep s : snapshot) {
            if (id.equals(s.getId())) {
                return s;
            }
        }
        return null;
    }

    // A run is in graph mode iff its snapshot carries stable step ids (backfill is all-or-nothing, so the
    // first step's id is a sufficient probe). Legacy Phase-1 snapshots with no ids drain by index instead.
    private static boolean graphMode(List<FunnelStep> snapshot) {
        return !snapshot.isEmpty() && snapshot.get(0).getId() != null;
    }

    // True when the cursor genuinely points past the end of the graph (vs. a broken cursor). Empty
    // snapshot → end. In graph mode a null currentStepId is the "End" marker → end (regardless of index;
    // e.g. a callback-resume to the End target leaves the index on the MENU step). In legacy index-drain
    // mode (no ids) end = index >= size.
    private static boolean endOfGraph(FunnelExecution exec, int size) {
        List<FunnelStep> snapshot = exec.getStepsSnapshot();
        if (snapshot == null || snapshot.isEmpty()) {
            return true;
        }
        if (graphMode(snapshot)) {
            return exec.getCurrentStepId() == null;
        }
        return exec.getCurrentStepIndex() >= size;
    }

    // Advance the cursor along a non-MENU step's default edge: currentStepId = step.next, or — when next
    // is null — the NEXT step in the snapshot list (default-next). null next at the last list position
    // means end-of-graph (currentStepId stays null → completes next loop turn). currentStepIndex is kept
    // approximately in sync as a secondary field for logs/drain (source of truth is currentStepId).
    private static void advanceToNext(FunnelExecution exec, FunnelStep step) {
        List<FunnelStep> snapshot = exec.getStepsSnapshot();
        if (step.getNext() != null) {
            exec.setCurrentStepId(step.getNext());
            exec.setCurrentStepIndex(indexOfId(snapshot, step.getNext()));
            return;
        }
        // Default-next = the step after this one in the list.
        int here = indexOfStep(snapshot, step);
        int nextIdx = here + 1;
        if (snapshot != null && nextIdx >= 0 && nextIdx < snapshot.size()) {
            FunnelStep nextStep = snapshot.get(nextIdx);
            exec.setCurrentStepId(nextStep.getId());
            exec.setCurrentStepIndex(nextIdx);
        } else {
            // End of the list with no explicit next → end of graph.
            exec.setCurrentStepId(null);
            exec.setCurrentStepIndex(snapshot == null ? 0 : snapshot.size());
        }
    }

    // Delay-resume advance (Phase 1 seam): move past the parked step along its default edge. Reuses
    // advanceToNext when a cursor/step resolves; falls back to the legacy index +1 for a null-cursor
    // linear drain.
    private static void advancePastDelay(FunnelExecution exec) {
        FunnelStep step = currentStep(exec);
        if (step != null) {
            advanceToNext(exec, step);
        } else {
            // Legacy linear run with no resolvable cursor → keep the Phase-1 index +1 behaviour.
            exec.setCurrentStepIndex(exec.getCurrentStepIndex() + 1);
        }
    }

    private static int indexOfId(List<FunnelStep> snapshot, String id) {
        if (snapshot == null) {
            return -1;
        }
        for (int i = 0; i < snapshot.size(); i++) {
            if (id.equals(snapshot.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfStep(List<FunnelStep> snapshot, FunnelStep step) {
        if (snapshot == null) {
            return -1;
        }
        for (int i = 0; i < snapshot.size(); i++) {
            if (snapshot.get(i) == step) {
                return i;
            }
        }
        return -1;
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
                .set("currentStepId", exec.getCurrentStepId())
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
    // re-claim. The cursor (currentStepId/currentStepIndex) is left on the Delay step (advanced on
    // resume). No-op if the claim was lost to a concurrent cancel.
    private void scheduleDelay(FunnelExecution exec, Instant nextRunAt) {
        Update update = new Update()
                .set("status", ExecutionStatus.waiting.name())
                .set("stepRunStatus", StepRunStatus.pending.name())
                .set("currentStepId", exec.getCurrentStepId())
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

    // Park-on-reply (MENU): status=waiting_for_reply, stepRunStatus=pending so a callback (resumeOnCallback)
    // or the timeout sweep can re-claim. The cursor stays on the MENU step (currentStepId unchanged).
    // nextRunAt = the timeout deadline, or null to wait indefinitely — sweep's predicate nextRunAt <= now
    // never matches a null, so an untimed menu is never auto-resumed. No-op if the claim was lost to a
    // concurrent cancel.
    private void parkForReply(FunnelExecution exec, Instant nextRunAt) {
        Update update = new Update()
                .set("status", ExecutionStatus.waiting_for_reply.name())
                .set("stepRunStatus", StepRunStatus.pending.name())
                .set("currentStepId", exec.getCurrentStepId())
                .set("currentStepIndex", exec.getCurrentStepIndex())
                .set("nextRunAt", nextRunAt)
                .set("updatedAt", Instant.now(clock));
        FunnelExecution updated = mongoTemplate.findAndModify(stillClaimed(exec.getId()), update,
                new FindAndModifyOptions().returnNew(true), FunnelExecution.class);
        if (updated == null) {
            log.debug("{} executionId={}", LOG_CLAIM_LOST, exec.getId());
            return;
        }
        log.info("{} executionId={} funnelId={}", LOG_MENU_PARKED, exec.getId(), exec.getFunnelId());
    }

    private void complete(FunnelExecution exec, Instant now) {
        Update update = new Update()
                .set("status", ExecutionStatus.completed.name())
                .set("stepRunStatus", StepRunStatus.done.name())
                .set("currentStepId", exec.getCurrentStepId())
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
                .set("currentStepId", exec.getCurrentStepId())
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
