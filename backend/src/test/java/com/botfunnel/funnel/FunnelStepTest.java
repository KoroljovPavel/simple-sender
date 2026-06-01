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
    }

    @Test
    void copyOfNullIsNull() {
        assertThat(FunnelStep.copyOf(null)).isNull();
    }
}
