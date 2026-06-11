package com.botfunnel.funnel.dto;

import com.botfunnel.funnel.StepType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

import java.util.List;

// One step in the UpdateFunnelRequest steps array. stepType is the discriminator; the remaining
// fields are type-specific and nullable (flat shape, Decision 12 — no _class discriminator). Only
// stepType is bean-validated here (@NotNull → 400): all per-type required-field / format checks
// (composer blocks 1–10, per-type block fields, album size/type-mixing, http(s) media URL, tagSlug
// slug shape, delay >= 1 min, button/edge rules) are conditional on stepType and therefore live in
// FunnelService → 422 with a business code, not as field-level bean validation (which would fire
// regardless of stepType and could only produce 400).
// `order` is intentionally absent: the server rewrites it from the array index (position = order).
//
// Phase 2 (graph model, Decision 2): `id` is the stable step id round-tripped to/from the client
// (minted server-side in toSteps when absent); `next` is the default outgoing edge; `buttons` +
// timeout fields apply to the MESSAGE composer step (attached to the last non-album block — Decision 2).
// Targets (`next`, button targetStepId, timeoutTargetStepId) are stable ids, never array indices, so
// reorder in the editor is safe without remapping.
//
// 15-message-composer (Decision 1): the former flat msg fields (text/parseMode/imageUrl/caption) are
// replaced by `blocks` — an ordered List<ContentBlockDto> carrying the composer's content blocks.
@JsonIgnoreProperties(ignoreUnknown = true)
public record FunnelStepDto(
        @NotNull StepType stepType,
        String id,
        String next,
        List<ButtonDto> buttons,
        Integer timeoutValue,
        String timeoutUnit,
        String timeoutTargetStepId,
        List<ContentBlockDto> blocks,
        Integer delayValue,
        String delayUnit,
        String tagSlug,
        String customFieldKey,
        Object customFieldValue,
        // EMIT_EVENT (Phase 3 / Decision 4): the named event this step emits (slug ^[A-Za-z0-9_-]{1,64}$).
        // Required for EMIT_EVENT, null for every other step type. Validated in FunnelService (→ 422).
        String eventName,
        // SUBSCRIBE_TO_FUNNEL (Phase 5 / composition): targetFunnelId = the project funnel to enroll the
        // subscriber into; targetEntryStepId = optional entry step inside the target (null = start the
        // target from its first step); endParentAfter = mark the parent execution completed right after
        // enroll. Required/target checks live in FunnelService (→ 422, Task 2); null for other step types.
        String targetFunnelId,
        String targetEntryStepId,
        boolean endParentAfter,
        // SET_KEYBOARD / CLEAR_KEYBOARD (Phase 7 / 16-persistent-keyboard / Decision 1, 2, 3): both steps
        // carry a mandatory text (keyboardText + keyboardParseMode). keyboardRows / isPersistent /
        // oneTimeKeyboard are SET_KEYBOARD-only (CLEAR_KEYBOARD rejects them — Decision 6); null for every
        // other step type. All per-type required-field / cap / duplicate checks live in FunnelService
        // (→ 422 funnel_step_invalid), not as bean validation here.
        String keyboardText,
        String keyboardParseMode,
        List<KeyboardRowDto> keyboardRows,
        Boolean isPersistent,
        Boolean oneTimeKeyboard
) {}
