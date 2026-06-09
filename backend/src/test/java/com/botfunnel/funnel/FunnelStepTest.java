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
        // Populate the surviving scalar fields so the independence contract is pinned for each one.
        FunnelStep original = new FunnelStep();
        original.setStepType(StepType.MESSAGE);
        original.setOrder(0);
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
        assertThat(copy.getStepType()).isEqualTo(StepType.MESSAGE);
        assertThat(copy.getOrder()).isEqualTo(0);
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
        copy.setStepType(StepType.DELAY);
        copy.setOrder(5);
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

        assertThat(original.getStepType()).isEqualTo(StepType.MESSAGE);
        assertThat(original.getOrder()).isEqualTo(0);
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
        original.setDelayValue(1);
        original.setDelayUnit("DAY");
        original.setTagSlug("src-tag");
        original.setCustomFieldKey("src-key");
        original.setCustomFieldValue("src-value");

        assertThat(copy.getStepType()).isEqualTo(StepType.DELAY);
        assertThat(copy.getOrder()).isEqualTo(5);
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
        noEvent.setStepType(StepType.MESSAGE);
        assertThat(noEvent.getEventName()).isNull();
        assertThat(FunnelStep.copyOf(noEvent).getEventName()).isNull();
    }

    /**
     * Decision 5: {@code buttons} is a mutable list, so {@code copyOf} must defensively copy it —
     * mutating the source list (or the copy) after copyOf must not leak into the other, otherwise
     * editing a funnel's buttons would corrupt an in-flight execution snapshot. Buttons now live on the
     * MESSAGE composer step (Decision 2 — attached to the last non-album block at send time).
     */
    @Test
    void deepCopyButtonsAreIndependent() {
        FunnelStep original = new FunnelStep();
        original.setStepType(StepType.MESSAGE);
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
     * Edge case: a step with {@code buttons == null} (any step without an inline keyboard) must copy to
     * a {@code null} list, not an empty one, and must not NPE.
     */
    @Test
    void copyOfNullButtonsStaysNull() {
        FunnelStep original = new FunnelStep();
        original.setStepType(StepType.MESSAGE);
        assertThat(original.getButtons()).isNull();

        FunnelStep copy = FunnelStep.copyOf(original);

        assertThat(copy.getButtons()).isNull();
    }

    /**
     * Decision 3 (Task 1): {@code blocks} is a mutable list of immutable {@link ContentBlock} records.
     * {@code copyOf} must defensively copy the list into a <b>new instance</b> while sharing the
     * (immutable) elements — otherwise editing a funnel's composer blocks would corrupt an in-flight
     * execution snapshot.
     */
    @Test
    void copyOf_copiesBlocksToNewListInstance() {
        FunnelStep original = new FunnelStep();
        original.setStepType(StepType.MESSAGE);
        List<ContentBlock> blocks = new ArrayList<>(List.of(
                new ContentBlock(BlockType.TEXT, "hello {user.first_name}", "HTML", null, null, null),
                new ContentBlock(BlockType.IMAGE, null, null, "https://example.com/a.png", "cap", null),
                new ContentBlock(BlockType.ALBUM, null, "HTML", null, null,
                        List.of(new MediaItem(BlockType.IMAGE, "https://example.com/1.png", "first"),
                                new MediaItem(BlockType.IMAGE, "https://example.com/2.png", null)))
        ));
        original.setBlocks(blocks);

        FunnelStep copy = FunnelStep.copyOf(original);

        // New list instance — not the same reference (defensive copy, Decision 3).
        assertThat(copy.getBlocks()).isNotSameAs(original.getBlocks());
        // ...but content-equal element-wise (ContentBlock is an immutable record → value equality).
        assertThat(copy.getBlocks()).containsExactlyElementsOf(original.getBlocks());
        // Elements shared by reference — ContentBlock is immutable, so a shallow element copy is intended.
        assertThat(copy.getBlocks().get(0)).isSameAs(original.getBlocks().get(0));
    }

    /**
     * Edge case: a step with {@code blocks == null} (any non-MESSAGE step) must copy to a {@code null}
     * list, not an empty one, and must not NPE — parity with the {@code buttons} treatment.
     */
    @Test
    void copyOf_nullBlocksStaysNull() {
        FunnelStep original = new FunnelStep();
        original.setStepType(StepType.DELAY);
        assertThat(original.getBlocks()).isNull();

        FunnelStep copy = FunnelStep.copyOf(original);

        assertThat(copy.getBlocks()).isNull();
    }

    /**
     * Snapshot isolation at the list level: adding/removing an element on the copy's {@code blocks} must
     * not affect the source list, and vice versa.
     */
    @Test
    void copyOf_mutatingCopyBlocksDoesNotAffectSource() {
        FunnelStep original = new FunnelStep();
        original.setStepType(StepType.MESSAGE);
        List<ContentBlock> blocks = new ArrayList<>(List.of(
                new ContentBlock(BlockType.TEXT, "a", null, null, null, null),
                new ContentBlock(BlockType.TEXT, "b", null, null, null, null)
        ));
        original.setBlocks(blocks);

        FunnelStep copy = FunnelStep.copyOf(original);

        // Mutating the copy list does not touch the source.
        copy.getBlocks().add(new ContentBlock(BlockType.TEXT, "c", null, null, null, null));
        assertThat(original.getBlocks()).hasSize(2);

        // Mutating the source list does not touch the copy.
        original.getBlocks().clear();
        assertThat(copy.getBlocks()).hasSize(3);
    }
}
