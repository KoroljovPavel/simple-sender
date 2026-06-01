package com.botfunnel.funnel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decision 3: {@link FunnelExecution#getStepsSnapshot()} is a deep copy of {@code Funnel.steps} taken
 * at fire() time. The copy must be independent — mutating a snapshot step (or the source) must not
 * leak into the other, otherwise editing a funnel would corrupt in-flight executions.
 */
class FunnelStepTest {

    @Test
    void deepCopyProducesIndependentStep() {
        FunnelStep original = new FunnelStep();
        original.setStepType(StepType.SEND_MESSAGE);
        original.setOrder(0);
        original.setText("hello {user.first_name}");
        original.setParseMode("HTML");

        FunnelStep copy = FunnelStep.copyOf(original);

        // Copy starts equal to source...
        assertThat(copy.getStepType()).isEqualTo(StepType.SEND_MESSAGE);
        assertThat(copy.getOrder()).isEqualTo(0);
        assertThat(copy.getText()).isEqualTo("hello {user.first_name}");
        assertThat(copy.getParseMode()).isEqualTo("HTML");

        // ...but mutating the copy does not touch the source.
        copy.setText("changed");
        copy.setOrder(5);
        assertThat(original.getText()).isEqualTo("hello {user.first_name}");
        assertThat(original.getOrder()).isEqualTo(0);

        // ...and mutating the source does not touch the copy.
        original.setText("source changed");
        assertThat(copy.getText()).isEqualTo("changed");
    }

    @Test
    void copyOfNullIsNull() {
        assertThat(FunnelStep.copyOf(null)).isNull();
    }
}
