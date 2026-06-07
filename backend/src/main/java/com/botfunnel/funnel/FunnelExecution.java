package com.botfunnel.funnel;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

// Indexes auto-created via spring.data.mongodb.auto-index-creation=true.
// (status, nextRunAt) is the critical sweep-claim predicate. @Indexed projectId backs the hard-delete
// cascade (the partial-unique compound does NOT cover a projectId lookup). The unique partial
// (funnelId, subscriberId) filtered status IN [running, waiting] is the SOLE re-enter guard
// (Decision 8) — unique=true is what makes it atomic. Decision 14: statuses persist as LOWERCASE
// name(), so the partialFilter $in literals byte-match ExecutionStatus.{running,waiting}.name().
// The $in form mirrors RawUpdate's partialFilter syntax, but the literals here are lowercase
// (Decision 14) — UPPERCASE literals would match zero rows and silently disable the guard.
@Document(collection = "funnel_executions")
@CompoundIndexes({
        @CompoundIndex(name = "status_nextRunAt",
                def = "{'status': 1, 'nextRunAt': 1}"),
        @CompoundIndex(name = "funnelId_subscriberId_unique_active",
                def = "{'funnelId': 1, 'subscriberId': 1}",
                unique = true,
                partialFilter = "{ 'status': { $in: ['running', 'waiting', 'waiting_for_reply'] } }")
})
public class FunnelExecution {

    // Defensive class-load assertion: the partialFilter literals 'running'/'waiting'/'waiting_for_reply'
    // must stay byte-identical with ExecutionStatus.name() (Spring Data persists via name()). A silent
    // rename would make the unique partial index match zero rows, voiding the at-most-once re-enter guard.
    static {
        if (!"running".equals(ExecutionStatus.running.name())
                || !"waiting".equals(ExecutionStatus.waiting.name())
                || !"waiting_for_reply".equals(ExecutionStatus.waiting_for_reply.name())) {
            throw new IllegalStateException(
                    "ExecutionStatus name() drifted from partial-filter literals: "
                            + ExecutionStatus.running.name() + "/" + ExecutionStatus.waiting.name()
                            + "/" + ExecutionStatus.waiting_for_reply.name());
        }
    }

    @Id
    private String id;

    @Indexed
    private String projectId;   // top-level for hard-delete cascade

    private String funnelId;
    private String subscriberId;
    private Long telegramBotId;  // pinned at start (Decision 7)

    private ExecutionStatus status;
    private int currentStepIndex;
    // Graph cursor (Phase 2 / Decision 2). Kept alongside currentStepIndex, which remains for draining
    // legacy linear Phase-1 runs (Decision 7). The engine reads currentStepId with a fallback to
    // currentStepIndex when null.
    private String currentStepId;
    private StepRunStatus stepRunStatus;
    private Instant nextRunAt;

    // Last button clicked on a MENU step (Phase 2 / Decision 9 — analytics; ids/codes only, no PII).
    private String lastButtonClicked;

    private List<FunnelStep> stepsSnapshot;  // deep copy at fire() (Decision 3)

    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getFunnelId() { return funnelId; }
    public void setFunnelId(String funnelId) { this.funnelId = funnelId; }

    public String getSubscriberId() { return subscriberId; }
    public void setSubscriberId(String subscriberId) { this.subscriberId = subscriberId; }

    public Long getTelegramBotId() { return telegramBotId; }
    public void setTelegramBotId(Long telegramBotId) { this.telegramBotId = telegramBotId; }

    public ExecutionStatus getStatus() { return status; }
    public void setStatus(ExecutionStatus status) { this.status = status; }

    public int getCurrentStepIndex() { return currentStepIndex; }
    public void setCurrentStepIndex(int currentStepIndex) { this.currentStepIndex = currentStepIndex; }

    public String getCurrentStepId() { return currentStepId; }
    public void setCurrentStepId(String currentStepId) { this.currentStepId = currentStepId; }

    public String getLastButtonClicked() { return lastButtonClicked; }
    public void setLastButtonClicked(String lastButtonClicked) { this.lastButtonClicked = lastButtonClicked; }

    public StepRunStatus getStepRunStatus() { return stepRunStatus; }
    public void setStepRunStatus(StepRunStatus stepRunStatus) { this.stepRunStatus = stepRunStatus; }

    public Instant getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(Instant nextRunAt) { this.nextRunAt = nextRunAt; }

    // Decision 3 (snapshot isolation): the returned list is the live backing reference, NOT a copy.
    // Callers must not mutate it in place. The snapshot itself is built at fire() time from
    // independent FunnelStep.copyOf deep copies, decoupling it from later edits to the source funnel.
    public List<FunnelStep> getStepsSnapshot() { return stepsSnapshot; }
    public void setStepsSnapshot(List<FunnelStep> stepsSnapshot) { this.stepsSnapshot = stepsSnapshot; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
