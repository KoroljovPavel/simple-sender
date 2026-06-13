package com.botfunnel.funnel.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

// Full-replace update body for a funnel's metadata + entry triggers + entire step list. Both arrays are
// authoritative full-replace (no patch/delta endpoint): the steps position IS the step order (the server
// rewrites FunnelStep.order from the index), and `triggers` replaces the funnel's whole entry-trigger set
// in one shot (Phase 8 / 17-funnel-multi-entry / Decision 1, 12).
//
// `triggers` is the multi-entry array (on_start + mid-entry event/keyword/tag/field redirects) that
// replaces the former flat triggerType / triggerValue / keywords trio. Each element is a flat TriggerDto;
// keywords now live per-trigger inside the element (Decision 12), not at the top level. A null/empty array
// is shape-valid here — composition validation (triggerType in the valid set, value slug shape, keyword
// required-iff-keyword, duplicate event_name, entryStepId resolves to a step, on_start uniqueness /
// onStartTriggerValue sync) is conditional on triggerType and lives in FunnelService (-> 422, Task 4).
//
// @JsonIgnoreProperties drops a hostile {"status":...} body field (mass-assignment defense). @Valid
// cascades bean-validation into each TriggerDto (its @Size keyword cap) and each FunnelStepDto (its
// @NotNull stepType). @Size(max=MAX_TRIGGERS) is the Decision 14 array ceiling so the now-repeatable
// triggers array cannot open a DoS surface; a service-side re-check of the same cap is Task 4.
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateFunnelRequest(
        @Size(max = 128) String name,
        @Size(max = 1024) String description,
        Boolean allowReEnter,
        @Valid @Size(max = MAX_TRIGGERS) List<TriggerDto> triggers,
        @Valid List<FunnelStepDto> steps,
        // Free-floating canvas notes (18-funnel-canvas / Task 1, Decision 7). @Size(max=MAX_NOTES) is the
        // first-line DoS ceiling (mirrors the MAX_TRIGGERS guard); a service-side re-check of the same cap
        // runs in FunnelService. @Valid cascades each NoteDto's @Size text cap + the CanvasPositionDto
        // finite-value check. Null/empty is shape-valid (a funnel may carry no notes).
        @Valid @Size(max = MAX_NOTES) List<NoteDto> notes
) {
    // Decision 14: ceiling on the entry-trigger array. Aligned with the same-order caps in FunnelService
    // (max-steps / fan-out = 50). The service re-checks this bound (Task 4); the annotation is the
    // first-line contract guard.
    public static final int MAX_TRIGGERS = 50;

    // 18-funnel-canvas / Task 1 (Decision 7): ceiling on the free-floating notes array. Same order of
    // magnitude as MAX_TRIGGERS / max-steps so a hostile notes array cannot bloat the document. The service
    // re-checks this bound (mirrors the MAX_TRIGGERS DoS guard); this annotation is the first-line guard.
    public static final int MAX_NOTES = 50;
}
