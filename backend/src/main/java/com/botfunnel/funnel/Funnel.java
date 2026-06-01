package com.botfunnel.funnel;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

// Indexes auto-created via spring.data.mongodb.auto-index-creation=true.
// (projectId, status) backs project lookup/list. The partial-unique (projectId, triggerType,
// triggerValue) filtered status='active' is the defense-in-depth guard against two active funnels
// claiming the same trigger (the service check is the first line). Decision 14: status is persisted
// as the LOWERCASE name() ('active'), which is exactly what the partialFilter literal matches.
@Document(collection = "funnels")
@CompoundIndexes({
        @CompoundIndex(name = "projectId_status",
                def = "{'projectId': 1, 'status': 1}"),
        @CompoundIndex(name = "projectId_triggerType_triggerValue_unique_active",
                def = "{'projectId': 1, 'triggerType': 1, 'triggerValue': 1}",
                unique = true,
                partialFilter = "{ 'status': 'active' }")
})
public class Funnel {

    // Defensive class-load assertion: the partialFilter literal 'active' must stay byte-identical with
    // FunnelStatus.active.name() (Spring Data persists the enum as name()). A silent enum rename would
    // make the partial-unique index match zero rows, voiding the trigger-conflict guard.
    static {
        if (!"active".equals(FunnelStatus.active.name())) {
            throw new IllegalStateException(
                    "Partial-filter literal 'active' diverged from FunnelStatus.active.name() = "
                            + FunnelStatus.active.name());
        }
    }

    @Id
    private String id;

    @Indexed
    private String projectId;

    private String name;
    private String description;
    private FunnelStatus status;

    private String triggerType;   // "on_start" (Phase 1)
    private String triggerValue;  // exact-match key; "" = bare /start
    private boolean allowReEnter = false;

    private List<FunnelStep> steps;

    private Instant createdAt;
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public FunnelStatus getStatus() { return status; }
    public void setStatus(FunnelStatus status) { this.status = status; }

    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }

    public String getTriggerValue() { return triggerValue; }
    public void setTriggerValue(String triggerValue) { this.triggerValue = triggerValue; }

    public boolean isAllowReEnter() { return allowReEnter; }
    public void setAllowReEnter(boolean allowReEnter) { this.allowReEnter = allowReEnter; }

    public List<FunnelStep> getSteps() { return steps; }
    public void setSteps(List<FunnelStep> steps) { this.steps = steps; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
