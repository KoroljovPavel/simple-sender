package com.botfunnel.funnel.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

// One entry trigger in the UpdateFunnelRequest / FunnelResponse triggers array (Phase 8 /
// 17-funnel-multi-entry / Decision 1, 12). Mirrors the FunnelStepDto precedent: a flat record (no
// _class discriminator), @JsonIgnoreProperties(ignoreUnknown=true) as mass-assignment defense, and
// fields nullable so the on_start element (triggerValue=""/null, entryStepId=null) and a keyword
// element (triggerValue=""/null) are both expressible by shape alone.
//
// Mirrors the domain Trigger (Task 1) field-for-field so Task 4's entity<->DTO mapping is trivial:
//   - triggerType   — "on_start" | "keyword" | "tag_added" | "custom_field_set" | "event".
//   - triggerValue  — exact-match key for non-keyword triggers; "" (or null) for on_start; unused by keyword.
//   - keywords      — keyword-trigger only: lowercase/trimmed/de-duped list. Null/empty for other types.
//   - entryStepId   — null for on_start (leads a START); a step id for an event mid-entry (leads a REDIRECT).
//
// Per-type and cross-trigger validation (triggerType in the five-value set, triggerValue slug shape,
// keyword required-iff-keyword, duplicate event_name across the array, entryStepId resolves to a real
// step, on_start uniqueness / onStartTriggerValue sync) is conditional on triggerType and therefore
// lives in FunnelService (-> 422 with a business code, Task 4), NOT as field-level bean validation
// (which would fire regardless of triggerType and could only produce 400).
//
// The ONE bean-validation constraint kept here is the per-trigger keyword count cap (Decision 14): a
// hard ceiling so the now-repeatable triggers array cannot multiply the keyword DoS surface. The
// per-keyword length cap + normalization stay in FunnelService (-> 422, Task 4).
@JsonIgnoreProperties(ignoreUnknown = true)
public record TriggerDto(
        String triggerType,
        // 18-funnel-canvas round-1 review (security/DoS): bound the free-form trigger value at the contract
        // boundary (→ 400). The longest valid content is a 64-char event slug; 128 gives headroom while
        // capping a hostile oversized string (consistent with the name/description @Size caps). Per-type
        // FORMAT validation (tag/event/custom-field-key slug shapes) stays in FunnelService (→ 422).
        @Size(max = 128) String triggerValue,
        @Size(max = MAX_KEYWORDS) List<String> keywords,
        String entryStepId,
        // Canvas node coordinate (18-funnel-canvas / Task 1): the start/entry node's {x,y} on the editor
        // surface. Nullable; @Valid cascades the DTO's finite-value check. Rendering-only metadata.
        @Valid CanvasPositionDto canvasPosition
) {
    // Decision 14 / mirrors FunnelService.MAX_KEYWORDS: the per-trigger keyword count cap. Annotated here
    // (vs only in the service) so the cap is enforced at the contract boundary across every element of the
    // triggers array. The per-keyword LENGTH cap and normalization remain service-side (Task 4).
    public static final int MAX_KEYWORDS = 50;
}
