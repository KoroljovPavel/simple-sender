package com.botfunnel.funnel.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Create body for a funnel: only name (+ optional description). The funnel is born in status=draft
// with an empty step list; steps/trigger are filled in later via PUT/PATCH (UpdateFunnelRequest).
// @JsonIgnoreProperties(ignoreUnknown = true) is the mass-assignment defense (patterns.md): a hostile
// {"status":"active"} / {"steps":[...]} body field is silently dropped — status transitions go only
// through the explicit activate/pause endpoints. @NotBlank is required because @Size alone accepts null.
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateFunnelRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 1024) String description
) {}
