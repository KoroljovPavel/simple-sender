package com.botfunnel.funnel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the {@code EMIT_EVENT} step-kind discriminator (Decision 4). {@code valueOf} resolving the
 * constant pins both the enum constant and its persisted {@code name()} — a silent rename would make
 * existing persisted {@code stepType: "EMIT_EVENT"} documents fail to deserialise.
 */
class StepTypeTest {

    @Test
    void emitEvent_present() {
        assertThat(StepType.valueOf("EMIT_EVENT")).isEqualTo(StepType.EMIT_EVENT);
        assertThat(StepType.EMIT_EVENT.name()).isEqualTo("EMIT_EVENT");
    }

    @Test
    void subscribeToFunnel_present() {
        assertThat(StepType.valueOf("SUBSCRIBE_TO_FUNNEL")).isEqualTo(StepType.SUBSCRIBE_TO_FUNNEL);
        assertThat(StepType.SUBSCRIBE_TO_FUNNEL.name()).isEqualTo("SUBSCRIBE_TO_FUNNEL");
    }

    /**
     * Guards the new {@code MESSAGE} composer step-kind discriminator (Decision 1 / Task 1). Pins both
     * the enum constant and its persisted {@code name()} so a silent rename would surface as a failing
     * test rather than {@code stepType: "MESSAGE"} documents failing to deserialise.
     */
    @Test
    void messageStep_present() {
        assertThat(StepType.valueOf("MESSAGE")).isEqualTo(StepType.MESSAGE);
        assertThat(StepType.MESSAGE.name()).isEqualTo("MESSAGE");
    }
}
