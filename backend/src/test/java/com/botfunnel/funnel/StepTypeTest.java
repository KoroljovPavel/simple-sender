package com.botfunnel.funnel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * Pins the acceptance criterion "StepType no longer contains SEND_MESSAGE/SEND_IMAGE/MENU"
     * (15-message-composer / Decision 1, F-MINOR-2). An accidental re-add of any removed constant would
     * flip these assertions red. {@code valueOf} of a removed name must throw — the tolerant Mongo read
     * path (StepTypeReadConverter) maps such legacy persisted values to the distinct {@link StepType#UNKNOWN}
     * sentinel instead, but the enum itself must NOT carry the old constants.
     */
    @Test
    void removedTypesAreAbsent() {
        assertThatThrownBy(() -> StepType.valueOf("SEND_MESSAGE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StepType.valueOf("SEND_IMAGE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StepType.valueOf("MENU"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(StepType.values())
                .extracting(Enum::name)
                .doesNotContain("SEND_MESSAGE", "SEND_IMAGE", "MENU");
    }
}
