package com.botfunnel.funnel;

import java.util.List;
import java.util.Objects;

/**
 * Flat embedded persistence POJO for a single funnel entry trigger (Phase 8 / 17-funnel-multi-entry).
 * Mirrors the {@link FunnelStep} precedent: no {@code _class} discriminator (Decision 12 of prior phases —
 * Spring Data serialises it as a plain nested document inside {@code Funnel.triggers} because the field
 * type {@code List<Trigger>} is statically known), no {@code @Document}/{@code @Id} (this is an embedded
 * element, not a collection root), and no bean-validation (validation lives in the DTO/service layer, as
 * with {@code FunnelStep}).
 *
 * <p>Field semantics:
 * <ul>
 *   <li>{@code triggerType} — {@code "on_start" | "keyword" | "tag_added" | "custom_field_set" | "event"}.</li>
 *   <li>{@code triggerValue} — exact-match key for non-keyword triggers; {@code ""} (empty string, NOT
 *       null) for {@code on_start} (bare /start). Unused by {@code keyword}.</li>
 *   <li>{@code keywords} — keyword-trigger only: lowercase, trimmed, de-duplicated list (contains-match,
 *       case-insensitive, any-of-many — runs in code). Null/empty for every non-keyword trigger type.</li>
 *   <li>{@code entryStepId} — {@code null} for an {@code on_start} trigger (it leads a START from the
 *       funnel's first step); an existing step id for an {@code event} mid-entry trigger (it leads a
 *       REDIRECT into that step).</li>
 * </ul>
 *
 * <p>Snapshot-irrelevant: triggers drive START/REDIRECT routing and are NOT copied into
 * {@code FunnelExecution.stepsSnapshot} (unlike {@link FunnelStep}); there is therefore no {@code copyOf}
 * here.
 */
public class Trigger {

    private String triggerType;
    private String triggerValue;
    private List<String> keywords;
    private String entryStepId;

    // Canvas node coordinate (18-funnel-canvas / Task 1, Decision 8): the start/entry node's {x,y} on the
    // editor surface. Additive + nullable. DELIBERATELY EXCLUDED from equals/hashCode below — those compare
    // and hash EXACTLY the four routing fields (triggerType, triggerValue, keywords, entryStepId).
    // canvasPosition is rendering-only metadata; keeping it out preserves the structural equality the
    // 17-funnel-multi-entry redirect re-scan / dedupe relies on (a moved node must still equal its prior
    // self for routing).
    private CanvasPosition canvasPosition;

    public Trigger() {
    }

    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }

    public String getTriggerValue() { return triggerValue; }
    public void setTriggerValue(String triggerValue) { this.triggerValue = triggerValue; }

    public List<String> getKeywords() { return keywords; }
    public void setKeywords(List<String> keywords) { this.keywords = keywords; }

    public String getEntryStepId() { return entryStepId; }
    public void setEntryStepId(String entryStepId) { this.entryStepId = entryStepId; }

    public CanvasPosition getCanvasPosition() { return canvasPosition; }
    public void setCanvasPosition(CanvasPosition canvasPosition) { this.canvasPosition = canvasPosition; }

    // Value equality over all four fields. Mongo re-hydrates triggers as fresh instances (so reference
    // identity is useless across a round-trip), and Task 5's redirect re-scans triggers[] to locate the
    // matched element — both rely on structural equality. keywords is compared as a List (order- and
    // content-sensitive), which matches its persisted shape.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Trigger trigger)) return false;
        return Objects.equals(triggerType, trigger.triggerType)
                && Objects.equals(triggerValue, trigger.triggerValue)
                && Objects.equals(keywords, trigger.keywords)
                && Objects.equals(entryStepId, trigger.entryStepId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(triggerType, triggerValue, keywords, entryStepId);
    }
}
