package com.botfunnel.funnel;

/**
 * Per-step claim flag within a {@link FunnelExecution}. Decision 4 / 14: LOWERCASE constants drive the
 * at-most-once {@code pending -> in_progress -> done} transition, written as {@code .name()} literals in
 * the engine's {@code findAndModify} criteria (Task 6). Persisted via {@code name()} (Spring Data default).
 */
public enum StepRunStatus {
    pending,
    in_progress,
    done
}
