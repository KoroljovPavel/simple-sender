package com.botfunnel.funnel;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

// Indexes auto-created via spring.data.mongodb.auto-index-creation=true.
// (projectId, status) backs project lookup/list. The partial-unique (projectId, triggerType,
// triggerValue) index is the defense-in-depth guard against two active funnels claiming the same
// trigger (the service check is the first line).
//
// Phase 3 (Decision 1): uniqueness now applies to `on_start` ONLY — the partialFilter is
// { status:'active', triggerType:'on_start' }. Fan-out (one event/word/tag/field → N funnels) is
// achieved by N active funnels sharing the same triggerValue, which the old { status:'active' } filter
// forbade. Two active `on_start` funnels with the same payload still collide; two active `event`
// (etc.) funnels with the same triggerValue now coexist.
//
// NOTE: auto-index-creation only CREATES indexes — it never drops/alters an existing one. So changing
// this annotation does NOT relax the live index on an existing DB; the actual drop/recreate is owned by
// the Task-2 startup migration (FunnelTriggerIndexReconciliation), and the runtime proof of the new
// shape lives in Task 2's FunnelIndexesIT. This annotation only fixes the shape auto-creation builds on
// a fresh DB.
//
// Decision 14: status is persisted as the LOWERCASE name() ('active'), which is exactly what the
// partialFilter literal matches; triggerType is a plain String ('on_start') — the service constant
// TRIGGER_ON_START holds the same literal (asserted in the static block as defense-in-depth).
@Document(collection = "funnels")
@CompoundIndexes({
        @CompoundIndex(name = "projectId_status",
                def = "{'projectId': 1, 'status': 1}"),
        @CompoundIndex(name = "projectId_triggerType_triggerValue_unique_active",
                def = "{'projectId': 1, 'triggerType': 1, 'triggerValue': 1}",
                unique = true,
                partialFilter = "{ 'status': 'active', 'triggerType': 'on_start' }")
})
public class Funnel {

    // Defensive class-load assertion: the partialFilter literals must stay byte-identical with the
    // values they match. 'active' must equal FunnelStatus.active.name() (Spring Data persists the enum
    // as name()); 'on_start' (Phase 3 / Decision 1) must equal the service's TRIGGER_ON_START constant
    // (triggerType is a plain persisted String). A silent rename of either would make the partial-unique
    // index match zero rows, voiding the on_start trigger-conflict guard.
    static {
        if (!"active".equals(FunnelStatus.active.name())) {
            throw new IllegalStateException(
                    "Partial-filter literal 'active' diverged from FunnelStatus.active.name() = "
                            + FunnelStatus.active.name());
        }
        if (!"on_start".equals(FunnelService.TRIGGER_ON_START)) {
            throw new IllegalStateException(
                    "Partial-filter literal 'on_start' diverged from FunnelService.TRIGGER_ON_START = "
                            + FunnelService.TRIGGER_ON_START);
        }
    }

    @Id
    private String id;

    // No standalone @Indexed: the non-partial compound (projectId, status) already covers
    // projectId-prefix queries. (Contrast FunnelExecution, whose other compound is partial, so its
    // projectId cascade lookup needs its own @Indexed.)
    private String projectId;

    private String name;
    private String description;
    private FunnelStatus status;

    private String triggerType;   // "on_start" | "keyword" | "tag_added" | "custom_field_set" | "event"
    private String triggerValue;  // exact-match key; "" = bare /start. Unused by "keyword".
    private boolean allowReEnter = false;

    // Phase 3 (Decision 3): keyword trigger only. Lowercase, trimmed, de-duplicated list — contains-match
    // (case-insensitive, any-of-many) runs in code, because the exact-match triggerValue index cannot
    // express "contains, multiple keywords". Null/empty for every non-keyword trigger type.
    private List<String> keywords;

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

    public List<String> getKeywords() { return keywords; }
    public void setKeywords(List<String> keywords) { this.keywords = keywords; }

    // Decision 3 (snapshot isolation): the returned list is the live backing reference, NOT a copy.
    // Callers must not mutate it in place. The execution snapshot is produced separately via
    // FunnelStep.copyOf at fire() time, so reads here never feed engine state.
    public List<FunnelStep> getSteps() { return steps; }
    public void setSteps(List<FunnelStep> steps) { this.steps = steps; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
