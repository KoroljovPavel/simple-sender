package com.botfunnel.funnel;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

// Indexes auto-created via spring.data.mongodb.auto-index-creation=true.
// (projectId, status) backs project lookup/list. The partial-unique (projectId, onStartTriggerValue)
// index is the defense-in-depth guard against two active funnels claiming the same bare-/start payload
// (the service check is the first line).
//
// Phase 8 (17-funnel-multi-entry / Decision 6, Variant A): a funnel now carries a List<Trigger> instead
// of a flat trigger trio. Uniqueness applies to the ON_START entry ONLY, and is enforced via a
// DENORMALIZED nullable scalar `onStartTriggerValue` (synced from the on_start Trigger by FunnelService —
// Task 4): the partialFilter is { status:'active', onStartTriggerValue:{$exists:true} }. The $exists guard
// keeps funnels WITHOUT an on_start entry (onStartTriggerValue == null) out of the index, so they never
// collide on a shared null. Fan-out (one event/word/tag/field → N funnels) is unaffected — only the
// on_start scalar is indexed. Two active funnels with the same on_start payload still collide.
//
// NOTE: auto-index-creation only CREATES indexes — it never drops/alters an existing one. So changing
// this annotation does NOT relax the live index on an existing DB; the actual drop/recreate of the old
// trio index and creation of this shape on an existing DB is owned by the startup migration, and the
// runtime proof of the new shape lives in FunnelIndexesIT. This annotation only fixes the shape
// auto-creation builds on a fresh DB.
//
// Decision 14: status is persisted as the LOWERCASE name() ('active'), which is exactly what the
// partialFilter literal matches (asserted in the static block as defense-in-depth). The partialFilter no
// longer carries a triggerType literal — uniqueness is keyed on the onStartTriggerValue scalar's
// existence, not on a 'on_start' string match.
@Document(collection = "funnels")
@CompoundIndexes({
        @CompoundIndex(name = "projectId_status",
                def = "{'projectId': 1, 'status': 1}"),
        @CompoundIndex(name = "projectId_onStartTriggerValue_unique_active",
                def = "{'projectId': 1, 'onStartTriggerValue': 1}",
                unique = true,
                partialFilter = "{ 'status': 'active', 'onStartTriggerValue': { '$exists': true } }")
})
public class Funnel {

    // Defensive class-load assertion: the partialFilter 'active' literal must stay byte-identical with
    // the value it matches. 'active' must equal FunnelStatus.active.name() (Spring Data persists the enum
    // as name()) — a silent rename would make the partial-unique index match zero rows, voiding the
    // on_start trigger-conflict guard. (Phase 8: the old 'on_start' assertion was dropped — the new
    // partialFilter keys on onStartTriggerValue's $exists, not on a 'on_start' triggerType literal, so an
    // assertion against TRIGGER_ON_START would be dead and misleading here.)
    static {
        if (!"active".equals(FunnelStatus.active.name())) {
            throw new IllegalStateException(
                    "Partial-filter literal 'active' diverged from FunnelStatus.active.name() = "
                            + FunnelStatus.active.name());
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

    // Phase 8 (17-funnel-multi-entry / Decision 6): a funnel has a list of entry triggers (on_start +
    // mid-entry event/keyword/tag/field redirects) instead of a single flat trigger. Each Trigger is a
    // flat embedded POJO; per-trigger validation lives in the DTO/service layer.
    private List<Trigger> triggers;

    // Denormalized projection of the on_start Trigger's triggerValue (or null when the funnel has no
    // on_start entry). Synced from `triggers` by FunnelService (Task 4). Exists solely so the
    // partial-unique index { status:'active', onStartTriggerValue:{$exists:true} } can enforce one active
    // funnel per (projectId, on_start payload) — it is nullable so non-on_start funnels stay out of that
    // index (Variant A).
    private String onStartTriggerValue;

    private boolean allowReEnter = false;

    private List<FunnelStep> steps;

    // Free-floating canvas notes (18-funnel-canvas / Task 1, Decision 7): editor-only annotations that live
    // OUTSIDE steps[] and are never executed by the engine. Additive + nullable (Decision 8) — an old
    // document without this field reads back as null, no migration. Each Note is a flat embedded record.
    private List<Note> notes;

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

    public List<Trigger> getTriggers() { return triggers; }
    public void setTriggers(List<Trigger> triggers) { this.triggers = triggers; }

    public String getOnStartTriggerValue() { return onStartTriggerValue; }
    public void setOnStartTriggerValue(String onStartTriggerValue) { this.onStartTriggerValue = onStartTriggerValue; }

    public boolean isAllowReEnter() { return allowReEnter; }
    public void setAllowReEnter(boolean allowReEnter) { this.allowReEnter = allowReEnter; }

    // Decision 3 (snapshot isolation): the returned list is the live backing reference, NOT a copy.
    // Callers must not mutate it in place. The execution snapshot is produced separately via
    // FunnelStep.copyOf at fire() time, so reads here never feed engine state.
    public List<FunnelStep> getSteps() { return steps; }
    public void setSteps(List<FunnelStep> steps) { this.steps = steps; }

    public List<Note> getNotes() { return notes; }
    public void setNotes(List<Note> notes) { this.notes = notes; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
