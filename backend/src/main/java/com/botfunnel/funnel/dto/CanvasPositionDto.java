package com.botfunnel.funnel.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.AssertTrue;

// One canvas node coordinate {x,y} (18-funnel-canvas / Task 1). The inbound shape for the editor's
// node positions, shared by FunnelStepDto / TriggerDto / NoteDto. @JsonIgnoreProperties(ignoreUnknown=true)
// keeps the mass-assignment defense consistent with ButtonDto / the other funnel DTOs.
//
// Finite-value bean validation (reject NaN / Infinity): a non-finite coordinate is a corrupt/hostile value
// that would poison rendering and persist as a non-portable token, so it is rejected at the contract
// boundary (@Valid cascade from the parent → 400). A coordinate present and not finite (NaN/Infinity in
// either x OR y) fails; an absent coordinate (null) passes (additive-nullable, Decision 8). All-or-nothing
// is NOT enforced here — {x:1, y:null} is a partial position that maps through harmlessly; only present
// values are required to be finite.
//
// NOTE: this validator only fires when the JSON parsed to a CanvasPositionDto at all. A bare NaN/Infinity
// token is invalid JSON, so Jackson rejects it at parse time (HTTP 400) BEFORE bean validation runs — the
// finite check is exercised by constructing the DTO directly in Java (CanvasPositionDtoTest), not via a
// JSON body.
@JsonIgnoreProperties(ignoreUnknown = true)
public record CanvasPositionDto(Double x, Double y) {

    @JsonIgnore
    @AssertTrue(message = "canvas coordinates must be finite numbers")
    public boolean isCoordinatesFinite() {
        return (x == null || Double.isFinite(x)) && (y == null || Double.isFinite(y));
    }
}
