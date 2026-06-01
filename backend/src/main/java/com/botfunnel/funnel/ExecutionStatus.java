package com.botfunnel.funnel;

/**
 * Funnel-execution lifecycle state. Decision 14: LOWERCASE constants — Spring Data MongoDB persists
 * the enum via {@code name()}, so the {@code funnel_executions} sweep predicate and the re-enter
 * guard partial filter {@code { status: { $in: ['running', 'waiting'] } }} byte-match {@link #running}
 * / {@link #waiting}. Already lowercase, so no {@code @JsonValue} is needed for HTTP serialisation.
 */
public enum ExecutionStatus {
    running,
    waiting,
    completed,
    cancelled,
    failed
}
