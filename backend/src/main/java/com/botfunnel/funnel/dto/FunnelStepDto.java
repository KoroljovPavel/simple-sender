package com.botfunnel.funnel.dto;

import com.botfunnel.funnel.StepType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

import java.util.List;

// One step in the UpdateFunnelRequest steps array. stepType is the discriminator; the remaining
// fields are type-specific and nullable (flat shape, Decision 12 — no _class discriminator). Only
// stepType is bean-validated here (@NotNull → 400): all per-type required-field / format checks
// (empty text, http(s) imageUrl, tagSlug slug shape, delay >= 1 min, MENU button/edge rules) are
// conditional on stepType and therefore live in FunnelService → 422 with a business code, not as
// field-level bean validation (which would fire regardless of stepType and could only produce 400).
// `order` is intentionally absent: the server rewrites it from the array index (position = order).
//
// Phase 2 (graph model, Decision 2): `id` is the stable step id round-tripped to/from the client
// (minted server-side in toSteps when absent); `next` is the default outgoing edge; `buttons` +
// timeout fields apply to MENU steps. Targets (`next`, button targetStepId, timeoutTargetStepId) are
// stable ids, never array indices, so reorder in the editor is safe without remapping.
@JsonIgnoreProperties(ignoreUnknown = true)
public record FunnelStepDto(
        @NotNull StepType stepType,
        String id,
        String next,
        List<ButtonDto> buttons,
        Integer timeoutValue,
        String timeoutUnit,
        String timeoutTargetStepId,
        String text,
        String parseMode,
        String imageUrl,
        String caption,
        Integer delayValue,
        String delayUnit,
        String tagSlug,
        String customFieldKey,
        Object customFieldValue,
        // EMIT_EVENT (Phase 3 / Decision 4): the named event this step emits (slug ^[A-Za-z0-9_-]{1,64}$).
        // Required for EMIT_EVENT, null for every other step type. Validated in FunnelService (→ 422).
        String eventName
) {}
