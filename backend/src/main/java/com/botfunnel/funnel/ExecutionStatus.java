package com.botfunnel.funnel;

/**
 * Funnel-execution lifecycle state. Decision 14: LOWERCASE constants — Spring Data MongoDB persists
 * the enum via {@code name()}, so the {@code funnel_executions} sweep predicate and the re-enter
 * guard partial filter {@code { status: { $in: ['running', 'waiting', 'waiting_for_reply'] } }}
 * byte-match {@link #running} / {@link #waiting} / {@link #waiting_for_reply}. Already lowercase, so no
 * {@code @JsonValue} is needed for HTTP serialisation.
 *
 * <p>{@link #waiting_for_reply} (Phase 2 / Decision 3): the execution is parked on a {@code MENU} step
 * awaiting a callback. It is included in the sweep/claim {@code $in} (so a timed-out menu with a
 * {@code nextRunAt} deadline is picked up), but a menu without a timeout has {@code nextRunAt = null}
 * and is never matched by the {@code nextRunAt <= now} predicate. Callback wake-up is a separate
 * resume path, not the sweep.
 */
public enum ExecutionStatus {
    running,
    waiting,
    completed,
    cancelled,
    failed,
    waiting_for_reply
}
