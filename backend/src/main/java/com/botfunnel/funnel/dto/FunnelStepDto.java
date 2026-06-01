package com.botfunnel.funnel.dto;

import com.botfunnel.funnel.StepType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

// One step in the UpdateFunnelRequest steps array. stepType is the discriminator; the remaining
// fields are type-specific and nullable (flat shape, Decision 12 — no _class discriminator). Only
// stepType is bean-validated here (@NotNull → 400): all per-type required-field / format checks
// (empty text, http(s) imageUrl, tagSlug slug shape, delay >= 1 min) are conditional on stepType and
// therefore live in FunnelService → 422 with a business code, not as field-level bean validation
// (which would fire regardless of stepType and could only produce 400). `order` is intentionally
// absent: the server rewrites it from the array index (position = order).
@JsonIgnoreProperties(ignoreUnknown = true)
public record FunnelStepDto(
        @NotNull StepType stepType,
        String text,
        String parseMode,
        String imageUrl,
        String caption,
        Integer delayValue,
        String delayUnit,
        String tagSlug,
        String customFieldKey,
        Object customFieldValue
) {}
