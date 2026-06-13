package com.botfunnel.funnel.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

// One free-floating canvas note in the UpdateFunnelRequest / FunnelResponse notes array (18-funnel-canvas
// / Task 1, Decision 7). Notes live OUTSIDE steps[] and are never executed — pure editor metadata. Mirrors
// the embedded domain Note (id, text, canvasPosition) so the FunnelService mapping is trivial.
//
// `id` is null on create → server-minted (ObjectId hex) in FunnelService; an echoed non-null id is
// preserved. The body's id is never trusted for shape — the server controls minting (mirrors the step-id
// minting in FunnelService.toSteps).
//
// @Size(max=NOTE_TEXT_MAX) is the first-line text length cap (→ 400); FunnelService re-checks the same cap
// (→ 422). @Valid cascades the CanvasPositionDto finite-value check. @JsonIgnoreProperties keeps the
// mass-assignment defense consistent with the other funnel DTOs.
@JsonIgnoreProperties(ignoreUnknown = true)
public record NoteDto(
        String id,
        @Size(max = NOTE_TEXT_MAX) String text,
        @Valid CanvasPositionDto canvasPosition
) {
    // Note text length ceiling. Generous enough for a real annotation, bounded so a hostile array of huge
    // notes cannot bloat the document. The service re-checks this bound (mirrors the MAX_TRIGGERS pattern);
    // the annotation is the first-line contract guard.
    public static final int NOTE_TEXT_MAX = 2000;
}
