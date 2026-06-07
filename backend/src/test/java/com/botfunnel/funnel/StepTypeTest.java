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
}
