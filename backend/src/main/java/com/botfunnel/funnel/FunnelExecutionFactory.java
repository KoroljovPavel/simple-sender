package com.botfunnel.funnel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Cycle-breaking execution-creation collaborator (Task 4 / Decision 2). Owns the depth-aware
 * {@code insertExecution} + {@code cancelExistingForPair} logic that was formerly private on
 * {@link FunnelTriggerServiceImpl}. BOTH {@link FunnelTriggerServiceImpl} (chatId-keyed {@code on_start}
 * {@code fire()}) and {@link FunnelEventService} (subscriber-keyed fan-out) inject THIS bean instead of
 * one another.
 *
 * <p><strong>Why a separate bean and not a method on {@code FunnelTriggerServiceImpl}.</strong>
 * {@code FunnelTriggerServiceImpl} injects {@code FunnelExecutionEngine} → {@code StepExecutor}. In
 * Task 5 {@code StepExecutor} injects {@code FunnelEventService} (to dispatch {@code EMIT_EVENT}). Had
 * {@code FunnelEventService} injected {@code FunnelTriggerServiceImpl}, the bean graph would close the
 * fatal cycle {@code FunnelEventService → FunnelTriggerServiceImpl → FunnelExecutionEngine →
 * StepExecutor → FunnelEventService} and Spring would throw {@code BeanCurrentlyInCreationException} at
 * startup. This factory has <strong>no</strong> edge into the engine/step-executor, so no cycle can
 * form — that absence is what keeps the Task-5 edge safe.
 *
 * <p>Package-private by design: an internal funnel collaborator, not part of any module's public API.
 */
@Component
class FunnelExecutionFactory {

    private static final Logger log = LoggerFactory.getLogger(FunnelExecutionFactory.class);

    // Greppable marker for a started execution. Kept identical to the former
    // FunnelTriggerServiceImpl.LOG_FIRE_STARTED literal so existing log-based alerts/tests still pin.
    static final String LOG_EXECUTION_STARTED = "FUNNEL_FIRE_EXECUTION_STARTED";

    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    FunnelExecutionFactory(MongoTemplate mongoTemplate, Clock clock) {
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    // Atomically cancel the existing running|waiting execution for the (funnelId, subscriberId) pair so
    // the partial-unique index frees up before a fresh insert (allowReEnter=true). updateMulti is
    // defensive — the unique index guarantees at most one such row. Moved verbatim from
    // FunnelTriggerServiceImpl (Decision 2 — no forked second writer).
    void cancelExistingForPair(String projectId, String funnelId, String subscriberId) {
        Instant now = Instant.now(clock);
        mongoTemplate.updateMulti(
                Query.query(Criteria.where("projectId").is(projectId)
                        .and("funnelId").is(funnelId)
                        .and("subscriberId").is(subscriberId)
                        .and("status").in(ExecutionStatus.running.name(), ExecutionStatus.waiting.name(),
                                ExecutionStatus.waiting_for_reply.name())),
                new Update()
                        .set("status", ExecutionStatus.cancelled.name())
                        .set("stepRunStatus", StepRunStatus.done.name())
                        .set("updatedAt", now),
                FunnelExecution.class);
    }

    // Build and insert a fresh execution from step 0 with a deep-copy steps snapshot (Decision 3) and the
    // pinned telegramBotId (Decision 7), stamping the explicit enrollDepth (Phase 3 / Decision 6 — the
    // Redis-independent depth backstop). on_start fire() passes depth 0; the dispatcher passes originDepth.
    // Uses MongoTemplate.insert so a unique-index collision surfaces as DuplicateKeyException (the
    // re-enter guard for allowReEnter=false).
    void insertExecution(String projectId, Funnel funnel, String subscriberId, Long telegramBotId,
                         int enrollDepth) {
        Instant now = Instant.now(clock);
        FunnelExecution execution = new FunnelExecution();
        execution.setProjectId(projectId);
        execution.setFunnelId(funnel.getId());
        execution.setSubscriberId(subscriberId);
        execution.setTelegramBotId(telegramBotId);
        execution.setStatus(ExecutionStatus.running);
        execution.setCurrentStepIndex(0);
        execution.setStepRunStatus(StepRunStatus.pending);
        execution.setNextRunAt(now);
        execution.setEnrollDepth(enrollDepth);
        List<FunnelStep> snapshot = deepCopySteps(funnel.getSteps());
        execution.setStepsSnapshot(snapshot);
        // Seed the graph cursor (Decision 2/7) to the first step's id so the engine navigates by
        // currentStepId from the start. null-safe for an empty snapshot. currentStepIndex stays 0 for
        // drain compatibility.
        execution.setCurrentStepId(snapshot.isEmpty() ? null : snapshot.get(0).getId());
        execution.setCreatedAt(now);
        execution.setUpdatedAt(now);
        mongoTemplate.insert(execution);
        log.info("{} funnelId={} subscriberId={} executionId={} enrollDepth={}", LOG_EXECUTION_STARTED,
                funnel.getId(), subscriberId, execution.getId(), enrollDepth);
    }

    // Deep copy of the funnel's steps via FunnelStep.copyOf (Decision 3 — snapshot isolation from later
    // funnel edits). null steps → empty snapshot.
    private static List<FunnelStep> deepCopySteps(List<FunnelStep> steps) {
        List<FunnelStep> snapshot = new ArrayList<>();
        if (steps != null) {
            for (FunnelStep step : steps) {
                snapshot.add(FunnelStep.copyOf(step));
            }
        }
        return snapshot;
    }
}
