package com.botfunnel.funnel.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// One inline-keyboard button on a MENU step (Phase 2). Mirrors the immutable Button record. Like
// FunnelStepDto, fields are NOT bean-validated here: button validation is conditional on the button
// type (callback target must exist in the graph; url must be http(s)) and therefore lives in
// FunnelService → 422 with a business code, not as field-level bean validation (which would fire
// regardless of type and could only produce a 400). @JsonIgnoreProperties(ignoreUnknown = true) keeps
// the mass-assignment defense consistent with the other funnel DTOs.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ButtonDto(
        String type,
        String label,
        String targetStepId,
        String url
) {}
