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

    // ─── 16-persistent-keyboard (Task 1): keyboard fields survive copyOf ──────────────────────────
    // User-spec Risk 1 (load-bearing): if copyOf drops a keyboard field, the keyboard silently vanishes
    // from the execution snapshot AND from a funnel duplicate.

    /**
     * The keyboard scalar fields ({@code keyboardText}/{@code keyboardParseMode}/{@code isPersistent}/
     * {@code oneTimeKeyboard}) are immutable value types (String / boxed Boolean), so {@code copyOf}
     * carries them onto the execution snapshot by reference — otherwise a SET_KEYBOARD step would lose
     * its text/flags when the funnel fires.
     */
    @Test
    void copyOf_preservesKeyboardScalars() {
        FunnelStep original = new FunnelStep();
        original.setStepType(StepType.SET_KEYBOARD);
        original.setKeyboardText("Головне меню {user.first_name}");
        original.setKeyboardParseMode("HTML");
        original.setIsPersistent(true);
        original.setOneTimeKeyboard(false);

        FunnelStep copy = FunnelStep.copyOf(original);

        assertThat(copy.getKeyboardText()).isEqualTo("Головне меню {user.first_name}");
        assertThat(copy.getKeyboardParseMode()).isEqualTo("HTML");
        assertThat(copy.getIsPersistent()).isTrue();
        assertThat(copy.getOneTimeKeyboard()).isFalse();

        // Mutating the copy does not touch the source.
        copy.setKeyboardText("changed");
        copy.setKeyboardParseMode("MarkdownV2");
        copy.setIsPersistent(false);
        copy.setOneTimeKeyboard(true);

        assertThat(original.getKeyboardText()).isEqualTo("Головне меню {user.first_name}");
        assertThat(original.getKeyboardParseMode()).isEqualTo("HTML");
        assertThat(original.getIsPersistent()).isTrue();
        assertThat(original.getOneTimeKeyboard()).isFalse();
    }

    /**
     * {@code keyboardRows} is a mutable list of immutable {@link KeyboardRow}/{@link KeyboardButton}
     * records. {@code copyOf} must defensively copy the list into a <b>new instance</b> while sharing
     * the (immutable) elements — pattern of {@code copyOf_copiesBlocksToNewListInstance}.
     */
    @Test
    void copyOf_copiesKeyboardRowsToNewListInstance() {
        FunnelStep original = new FunnelStep();
        original.setStepType(StepType.SET_KEYBOARD);
        List<KeyboardRow> rows = new ArrayList<>(List.of(
                new KeyboardRow(List.of(new KeyboardButton("Згенерувати бонус"), new KeyboardButton("Профіль"))),
                new KeyboardRow(List.of(new KeyboardButton("Допомога")))
        ));
        original.setKeyboardRows(rows);

        FunnelStep copy = FunnelStep.copyOf(original);

        // New list instance — not the same reference (defensive copy).
        assertThat(copy.getKeyboardRows()).isNotSameAs(original.getKeyboardRows());
        // ...but content-equal element-wise (KeyboardRow is an immutable record → value equality).
        assertThat(copy.getKeyboardRows()).containsExactlyElementsOf(original.getKeyboardRows());
        // Elements shared by reference — KeyboardRow is immutable, so a shallow element copy is intended.
        assertThat(copy.getKeyboardRows().get(0)).isSameAs(original.getKeyboardRows().get(0));
    }

    /**
     * Edge case: a step with {@code keyboardRows == null} (CLEAR_KEYBOARD, or any non-keyboard step)
     * must copy to a {@code null} list, not an empty one, and must not NPE — parity with {@code buttons}/
     * {@code blocks}.
     */
    @Test
    void copyOf_nullKeyboardRowsStaysNull() {
        FunnelStep original = new FunnelStep();
        original.setStepType(StepType.CLEAR_KEYBOARD);
        assertThat(original.getKeyboardRows()).isNull();

        FunnelStep copy = FunnelStep.copyOf(original);

        assertThat(copy.getKeyboardRows()).isNull();
    }

    /**
     * Snapshot isolation at the list level (user-spec Risk 1, load-bearing): adding/removing an element
     * on the copy's {@code keyboardRows} must not affect the source list, and vice versa.
     */
    @Test
    void copyOf_mutatingCopyKeyboardRowsDoesNotAffectSource() {
        FunnelStep original = new FunnelStep();
        original.setStepType(StepType.SET_KEYBOARD);
        List<KeyboardRow> rows = new ArrayList<>(List.of(
                new KeyboardRow(List.of(new KeyboardButton("a"))),
                new KeyboardRow(List.of(new KeyboardButton("b")))
        ));
        original.setKeyboardRows(rows);

        FunnelStep copy = FunnelStep.copyOf(original);

        // Mutating the copy list does not touch the source.
        copy.getKeyboardRows().add(new KeyboardRow(List.of(new KeyboardButton("c"))));
        assertThat(original.getKeyboardRows()).hasSize(2);

        // Mutating the source list does not touch the copy.
        original.getKeyboardRows().clear();
        assertThat(copy.getKeyboardRows()).hasSize(3);
    }

    /**
     * 18-funnel-canvas / Task 1: {@code canvasPosition} is an immutable {@link CanvasPosition} record, so
     * {@code copyOf} carries it onto the execution snapshot by reference; a null position stays null. The
     * coordinate is rendering-only metadata — it survives the snapshot but the engine never reads it.
     */
    @Test
    void copyOf_carries_canvasPosition() {
        FunnelStep original = new FunnelStep();
        original.setStepType(StepType.MESSAGE);
        original.setCanvasPosition(new CanvasPosition(120.5, -42.0));

        FunnelStep copy = FunnelStep.copyOf(original);

        assertThat(copy.getCanvasPosition()).isEqualTo(new CanvasPosition(120.5, -42.0));
        // Immutable record → reference copy is the intended behaviour (no needless deep copy).
        assertThat(copy.getCanvasPosition()).isSameAs(original.getCanvasPosition());

        // A null canvasPosition copies to null (additive-nullable; the node auto-layouts in the editor).
        FunnelStep noPosition = new FunnelStep();
        noPosition.setStepType(StepType.MESSAGE);
        assertThat(noPosition.getCanvasPosition()).isNull();
        assertThat(FunnelStep.copyOf(noPosition).getCanvasPosition()).isNull();
    }
}
