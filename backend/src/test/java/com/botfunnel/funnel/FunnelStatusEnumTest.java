package com.botfunnel.funnel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decision 14: funnel/execution/step-run status enums are LOWERCASE constants. Spring Data MongoDB
 * persists enums via {@code name()}, and the partial-filter index literals byte-match these names —
 * this test pins that contract so a silent rename surfaces here before it voids the indexes.
 */
class FunnelStatusEnumTest {

    @Test
    void statusEnumsArePersistedLowercase() {
        assertThat(FunnelStatus.draft.name()).isEqualTo("draft");
        assertThat(FunnelStatus.active.name()).isEqualTo("active");
        assertThat(FunnelStatus.paused.name()).isEqualTo("paused");

        assertThat(ExecutionStatus.running.name()).isEqualTo("running");
        assertThat(ExecutionStatus.waiting.name()).isEqualTo("waiting");
        assertThat(ExecutionStatus.completed.name()).isEqualTo("completed");
        assertThat(ExecutionStatus.cancelled.name()).isEqualTo("cancelled");
        assertThat(ExecutionStatus.failed.name()).isEqualTo("failed");
        assertThat(ExecutionStatus.waiting_for_reply.name()).isEqualTo("waiting_for_reply");

        assertThat(StepRunStatus.pending.name()).isEqualTo("pending");
        assertThat(StepRunStatus.in_progress.name()).isEqualTo("in_progress");
        assertThat(StepRunStatus.done.name()).isEqualTo("done");
    }

    @Test
    void stepTypeDiscriminatorIsUppercase() {
        assertThat(StepType.MESSAGE.name()).isEqualTo("MESSAGE");
        assertThat(StepType.DELAY.name()).isEqualTo("DELAY");
        assertThat(StepType.ADD_TAG.name()).isEqualTo("ADD_TAG");
        assertThat(StepType.REMOVE_TAG.name()).isEqualTo("REMOVE_TAG");
        assertThat(StepType.SET_CUSTOM_FIELD.name()).isEqualTo("SET_CUSTOM_FIELD");
    }
}
