package com.botfunnel.funnel.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

// One row of a persistent reply keyboard on a SET_KEYBOARD step (16-persistent-keyboard). Mirrors the
// immutable KeyboardRow record. Like FunnelStepDto, fields are NOT bean-validated here: row/button
// rules (1..10 rows, 1..4 buttons per row, button text non-blank/≤64, no duplicates) are conditional
// on the step type and therefore live in FunnelService → 422 with a business code, not as field-level
// bean validation. @JsonIgnoreProperties(ignoreUnknown = true) keeps the mass-assignment defense
// consistent with the other funnel DTOs.
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeyboardRowDto(
        List<KeyboardButtonDto> buttons
) {}
