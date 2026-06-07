package com.botfunnel.funnel;

/**
 * Discriminator for a {@link FunnelStep} (Decision 12: flat POJO, no {@code _class}). UPPERCASE — this
 * is a step-kind discriminator, not a status, and has no partial-index literal dependency; it follows
 * the codebase convention for enum discriminators.
 */
public enum StepType {
    SEND_MESSAGE,
    SEND_IMAGE,
    DELAY,
    ADD_TAG,
    REMOVE_TAG,
    SET_CUSTOM_FIELD,
    // Phase 2 (Decision 1): composite step — message + inline keyboard + park-on-reply + branch.
    MENU,
    // Phase 3 (Decision 4): emit a named event for the current subscriber from inside a running funnel.
    // Shares the `event` trigger namespace with the external POST /events; the target is `eventName`.
    EMIT_EVENT
}
