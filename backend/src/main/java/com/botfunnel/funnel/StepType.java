package com.botfunnel.funnel;

/**
 * Discriminator for a {@link FunnelStep} (Decision 12: flat POJO, no {@code _class}). UPPERCASE — this
 * is a step-kind discriminator, not a status, and has no partial-index literal dependency; it follows
 * the codebase convention for enum discriminators.
 */
public enum StepType {
    // Phase 6 (15-message-composer / Decision 1): composer step — sends an ordered List<ContentBlock> as
    // N separate Telegram messages; optional inline keyboard + timeout park-on-reply attach to the last
    // non-album block. Replaces the former flat SEND_MESSAGE / SEND_IMAGE / MENU step-kinds.
    MESSAGE,
    DELAY,
    ADD_TAG,
    REMOVE_TAG,
    SET_CUSTOM_FIELD,
    // Phase 3 (Decision 4): emit a named event for the current subscriber from inside a running funnel.
    // Shares the `event` trigger namespace with the external POST /events; the target is `eventName`.
    EMIT_EVENT,
    // Phase 5 (composition): enroll the same subscriber into another funnel of the project. The target is
    // the pair (targetFunnelId, targetEntryStepId) — null entry step means start the target from its first step.
    SUBSCRIBE_TO_FUNNEL
}
