package com.botfunnel.funnel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decision 3: {@link FunnelExecution#getStepsSnapshot()} is a deep copy of {@code Funnel.steps} taken
 * at fire() time. The copy must be independent — mutating a snapshot step (or the source) must not
 * leak into the other, otherwise editing a funnel would corrupt in-flight executions.
 */
class FunnelStepTest {

    @Test
    void deepCopyProducesIndependentStep() {
        // Populate EVERY field so the independence contract is pinned for each one.
        FunnelStep original = new FunnelStep();
        original.setStepType(StepType.SEND_MESSAGE);
        original.setOrder(0);
        original.setText("hello {user.first_name}");
        original.setParseMode("HTML");
        original.setImageUrl("https://example.com/a.png");
        original.setCaption("caption-a");
        original.setDelayValue(10);
        original.setDelayUnit("MIN");
        original.setTagSlug("vip");
        original.setCustomFieldKey("plan");
        original.setCustomFieldValue("gold"); // validated immutable scalar (String)
        // Graph scalars (Task 1 / Decision 2).
        original.setId("step-1");
        original.setNext("step-2");
        original.setTimeoutValue(15);
        original.setTimeoutUnit("minutes");
        original.setTimeoutTargetStepId("step-3");

        FunnelStep copy = FunnelStep.copyOf(original);

        // Copy starts field-equal to the source...
        assertThat(copy.getStepType()).isEqualTo(StepType.SEND_MESSAGE);
        assertThat(copy.getOrder()).isEqualTo(0);
        assertThat(copy.getText()).isEqualTo("hello {user.first_name}");
        assertThat(copy.getParseMode()).isEqualTo("HTML");
        assertThat(copy.getImageUrl()).isEqualTo("https://example.com/a.png");
        assertThat(copy.getCaption()).isEqualTo("caption-a");
        assertThat(copy.getDelayValue()).isEqualTo(10);
        assertThat(copy.getDelayUnit()).isEqualTo("MIN");
        assertThat(copy.getTagSlug()).isEqualTo("vip");
        assertThat(copy.getCustomFieldKey()).isEqualTo("plan");
        assertThat(copy.getCustomFieldValue()).isEqualTo("gold");
        assertThat(copy.getId()).isEqualTo("step-1");
        assertThat(copy.getNext()).isEqualTo("step-2");
        assertThat(copy.getTimeoutValue()).isEqualTo(15);
        assertThat(copy.getTimeoutUnit()).isEqualTo("minutes");
        assertThat(copy.getTimeoutTargetStepId()).isEqualTo("step-3");

        // ...but mutating the copy does not touch the source.
        copy.setStepType(StepType.SEND_IMAGE);
        copy.setOrder(5);
        copy.setText("changed");
        copy.setParseMode("MarkdownV2");
        copy.setImageUrl("https://example.com/b.png");
        copy.setCaption("caption-b");
        copy.setDelayValue(99);
        copy.setDelayUnit("HOUR");
        copy.setTagSlug("blocked");
        copy.setCustomFieldKey("tier");
        copy.setCustomFieldValue("silver");
        copy.setId("step-1-copy");
        copy.setNext("step-2-copy");
        copy.setTimeoutValue(99);
        copy.setTimeoutUnit("hours");
        copy.setTimeoutTargetStepId("step-3-copy");

        assertThat(original.getStepType()).isEqualTo(StepType.SEND_MESSAGE);
        assertThat(original.getOrder()).isEqualTo(0);
        assertThat(original.getText()).isEqualTo("hello {user.first_name}");
        assertThat(original.getParseMode()).isEqualTo("HTML");
        assertThat(original.getImageUrl()).isEqualTo("https://example.com/a.png");
        assertThat(original.getCaption()).isEqualTo("caption-a");
        assertThat(original.getDelayValue()).isEqualTo(10);
        assertThat(original.getDelayUnit()).isEqualTo("MIN");
        assertThat(original.getTagSlug()).isEqualTo("vip");
        assertThat(original.getCustomFieldKey()).isEqualTo("plan");
        assertThat(original.getCustomFieldValue()).isEqualTo("gold");
        assertThat(original.getId()).isEqualTo("step-1");
        assertThat(original.getNext()).isEqualTo("step-2");
        assertThat(original.getTimeoutValue()).isEqualTo(15);
        assertThat(original.getTimeoutUnit()).isEqualTo("minutes");
        assertThat(original.getTimeoutTargetStepId()).isEqualTo("step-3");

        // ...and mutating the source does not touch the copy.
        original.setStepType(StepType.DELAY);
        original.setOrder(7);
        original.setText("source changed");
        original.setParseMode(null);
        original.setImageUrl("https://example.com/src.png");
        original.setCaption("caption-src");
        original.setDelayValue(1);
        original.setDelayUnit("DAY");
        original.setTagSlug("src-tag");
        original.setCustomFieldKey("src-key");
        original.setCustomFieldValue("src-value");

        assertThat(copy.getStepType()).isEqualTo(StepType.SEND_IMAGE);
        assertThat(copy.getOrder()).isEqualTo(5);
        assertThat(copy.getText()).isEqualTo("changed");
        assertThat(copy.getParseMode()).isEqualTo("MarkdownV2");
        assertThat(copy.getImageUrl()).isEqualTo("https://example.com/b.png");
        assertThat(copy.getCaption()).isEqualTo("caption-b");
        assertThat(copy.getDelayValue()).isEqualTo(99);
        assertThat(copy.getDelayUnit()).isEqualTo("HOUR");
        assertThat(copy.getTagSlug()).isEqualTo("blocked");
        assertThat(copy.getCustomFieldKey()).isEqualTo("tier");
        assertThat(copy.getCustomFieldValue()).isEqualTo("silver");
        assertThat(copy.getId()).isEqualTo("step-1-copy");
        assertThat(copy.getNext()).isEqualTo("step-2-copy");
        assertThat(copy.getTimeoutValue()).isEqualTo(99);
        assertThat(copy.getTimeoutUnit()).isEqualTo("hours");
        assertThat(copy.getTimeoutTargetStepId()).isEqualTo("step-3-copy");
    }

    @Test
    void copyOfNullIsNull() {
        assertThat(FunnelStep.copyOf(null)).isNull();
    }

    /**
     * Decision 4: the {@code eventName} scalar (for EMIT_EVENT) is an immutable String, so {@code copyOf}
     * carries it onto the execution snapshot by reference — otherwise an EMIT_EVENT step would lose its
     * target event when the funnel fires. A null {@code eventName} stays null in the copy.
     */
    @Test
    void copyOf_preservesEventName() {
        FunnelStep original = new FunnelStep();
        original.setStepType(StepType.EMIT_EVENT);
        original.setEventName("purchase_done");

        FunnelStep copy = FunnelStep.copyOf(original);

        assertThat(copy.getEventName()).isEqualTo("purchase_done");

        // A null eventName must stay null (not become "" or NPE).
        FunnelStep noEvent = new FunnelStep();
        noEvent.setStepType(StepType.SEND_MESSAGE);
        assertThat(noEvent.getEventName()).isNull();
        assertThat(FunnelStep.copyOf(noEvent).getEventName()).isNull();
    }

    /**
     * Decision 5: {@code buttons} is a mutable list, so {@code copyOf} must defensively copy it —
     * mutating the source list (or the copy) after copyOf must not leak into the other, otherwise
     * editing a funnel's buttons would corrupt an in-flight execution snapshot.
     */
    @Test
    void deepCopyButtonsAreIndependent() {
        FunnelStep original = new FunnelStep();
        original.setStepType(StepType.MENU);
        List<Button> buttons = new ArrayList<>();
        buttons.add(new Button("callback", "Buy", "step-buy", null));
        buttons.add(new Button("url", "Site", null, "https://example.com"));
        original.setButtons(buttons);

        FunnelStep copy = FunnelStep.copyOf(original);

        assertThat(copy.getButtons())
                .as("copy starts list-equal to source")
                .containsExactlyElementsOf(original.getButtons());
        // ...but the list reference is distinct.
        assertThat(copy.getButtons()).isNotSameAs(original.getButtons());
        // Elements are shared by reference — Button is immutable, so a shallow element copy is the
        // intended Decision 5 behaviour (no needless per-record deep copy).
        assertThat(copy.getButtons().get(0)).isSameAs(original.getButtons().get(0));

        // Mutating the source list does not touch the copy.
        original.getButtons().add(new Button("callback", "Extra", "step-x", null));
        assertThat(copy.getButtons()).hasSize(2);

        // Mutating the copy list does not touch the source.
        copy.getButtons().clear();
        assertThat(original.getButtons()).hasSize(3);
    }

    /**
     * Edge case: a step with {@code buttons == null} (any non-MENU step) must copy to a {@code null}
     * list, not an empty one, and must not NPE.
     */
    @Test
    void copyOfNullButtonsStaysNull() {
        FunnelStep original = new FunnelStep();
        original.setStepType(StepType.SEND_MESSAGE);
        assertThat(original.getButtons()).isNull();

        FunnelStep copy = FunnelStep.copyOf(original);

        assertThat(copy.getButtons()).isNull();
    }
}
