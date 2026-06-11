package com.botfunnel.funnel.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// One reply-keyboard button on a SET_KEYBOARD step (16-persistent-keyboard). Mirrors the immutable
// KeyboardButton record. Like FunnelStepDto, fields are NOT bean-validated here: the button rules
// (non-blank text, ≤64 chars, no duplicate-within-keyboard) are conditional on the step type and
// therefore live in FunnelService → 422 with a business code, not as field-level bean validation
// (which would fire regardless of type and could only produce a 400).
// @JsonIgnoreProperties(ignoreUnknown = true) keeps the mass-assignment defense consistent with the
// other funnel DTOs (a hostile payload with unknown fields is ignored, never a 400/500).
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeyboardButtonDto(
        String text
) {}
