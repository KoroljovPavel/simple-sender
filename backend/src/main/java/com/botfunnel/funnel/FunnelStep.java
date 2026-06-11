package com.botfunnel.funnel;

import java.util.ArrayList;
import java.util.List;

/**
 * Flat embedded persistence POJO for a single funnel step (Decision 12: no {@code _class}
 * discriminator). {@code stepType} + {@code order} are always set; the remaining fields are
 * type-specific and nullable. No bean-validation here — validation lives in the DTO/service layer
 * (Task 4). {@link #copyOf(FunnelStep)} produces a deep copy for the execution snapshot (Decision 3);
 * all scalar fields are immutable value types (String / boxed primitives / enum) and copy field-wise
 * — including {@code eventName} (Phase 3 / Decision 4, the EMIT_EVENT target) — but the {@code buttons}
 * (Phase 2 / Decision 5), {@code blocks} (15-message-composer / Decision 1) and {@code keyboardRows}
 * (16-persistent-keyboard / Decision 2, the SET_KEYBOARD rows) lists are mutable containers and must be
 * copied defensively (see {@link #copyOf(FunnelStep)}).
 *
 * <p>The {@code buttons} + {@code timeout*} fields now belong to the {@link StepType#MESSAGE} composer
 * step (Decision 2): the inline keyboard and park-on-reply timeout attach to the last non-album block
 * of {@code blocks}. (Previously these were MENU-step fields.)
 */
public class FunnelStep {

    private StepType stepType;
    private int order;

    // Graph model (Phase 2 / Decision 2): stable id + outgoing edge(s). id is minted server-side
    // (ObjectId hex / UUID); next is the default outgoing target (null = next step in list).
    private String id;
    private String next;

    // MESSAGE composer (15-message-composer / Decision 2): inline-keyboard buttons + optional timeout edge,
    // attached to the last non-album block. buttons is a mutable list of immutable Button records —
    // defensively copied in copyOf (Decision 5).
    private List<Button> buttons;
    private Integer timeoutValue;
    private String timeoutUnit;          // "MIN" | "HOUR" | "DAY" (reuses delayUnit convention, Task 3 deviation)
    private String timeoutTargetStepId;  // null = completed

    // MESSAGE composer (15-message-composer / Decision 1): ordered list of content blocks sent as N
    // separate Telegram messages. Mutable list of immutable ContentBlock records — defensively copied in
    // copyOf (Decision 3). Per-block validation (count, per-type fields, album 2–10, type-mixing) lives in
    // the DTO/service layer (Task 4).
    private List<ContentBlock> blocks;

    // DELAY
    private Integer delayValue;   // total >= 1 minute
    private String delayUnit;     // MIN | HOUR | DAY

    // ADD_TAG / REMOVE_TAG
    private String tagSlug;

    // SET_CUSTOM_FIELD
    private String customFieldKey;
    /**
     * Typed {@code Object} to mirror the subscriber {@code customFields} map: values are validated
     * scalars — {@code Double} / {@code Boolean} / {@code Instant} / {@code String} (all immutable),
     * enforced by CustomFieldValueValidator (Task 4/5). Because the value is always an immutable
     * scalar, {@link #copyOf(FunnelStep)} can copy it by reference and still satisfy the deep-copy
     * contract. Storing a mutable collection here would violate Decision 3 (snapshot isolation) and
     * is disallowed.
     */
    private Object customFieldValue;

    // EMIT_EVENT (Phase 3 / Decision 4): the named event this step emits for the current subscriber.
    // Immutable String slug (^[A-Za-z0-9_-]{1,64}$); shares the `event` namespace with the API event.
    private String eventName;

    // SUBSCRIBE_TO_FUNNEL (Phase 5 / composition): enroll the same subscriber into another funnel of the
    // project. targetFunnelId = the funnel to enroll into; targetEntryStepId = optional entry step inside
    // the target (null = start the target from its first step). Named targetEntryStepId (NOT targetStepId)
    // to avoid colliding with Button.targetStepId (intra-funnel MENU target) and to keep it out of the
    // generic edge-pass in FunnelService.validateSteps (Decision 5). endParentAfter (primitive boolean,
    // default false): true = mark the parent execution completed immediately after enroll.
    private String targetFunnelId;
    private String targetEntryStepId;
    private boolean endParentAfter;

    // SET_KEYBOARD / CLEAR_KEYBOARD (Phase 7 / 16-persistent-keyboard / Decision 1, 2, 3). Both step
    // types send a mandatory text message (keyboardText + keyboardParseMode — same domain as a TEXT
    // block). keyboardRows / isPersistent / oneTimeKeyboard are SET_KEYBOARD-only (CLEAR_KEYBOARD rejects
    // them — Decision 6). keyboardRows is a mutable list of immutable KeyboardRow records — defensively
    // copied in copyOf (snapshot isolation, Decision 3 / user-spec Risk 1). resize_keyboard is NOT stored
    // — it is hardcoded true in the StepExecutor builder (Decision 5, Task 3).
    private String keyboardText;            // mandatory by validation, <=4096, variables + parse mode
    private String keyboardParseMode;       // null | "HTML" | "MarkdownV2"
    private List<KeyboardRow> keyboardRows; // SET_KEYBOARD only: 1..10 rows
    private Boolean isPersistent;           // SET_KEYBOARD only: Telegram is_persistent
    private Boolean oneTimeKeyboard;        // SET_KEYBOARD only: Telegram one_time_keyboard

    public FunnelStep() {
    }

    /**
     * Deep copy for the execution snapshot (Decision 3). Returns {@code null} for a {@code null} input
     * so callers can map a list element-wise without null-guarding each entry.
     *
     * <p>Deep-copy contract: every scalar field is an immutable value type — {@code String} / boxed
     * primitive / enum — and {@link #customFieldValue} is a validated immutable scalar (Double /
     * Boolean / Instant / String, enforced by CustomFieldValueValidator in Task 4/5), so all scalars
     * copy by reference and still satisfy the contract. The exceptions are the mutable {@code List}
     * fields {@link #buttons} (Phase 2 / Decision 5), {@link #blocks} (15-message-composer /
     * Decision 1) and {@link #keyboardRows} (16-persistent-keyboard / Decision 2): each is copied
     * <b>defensively</b> ({@code new ArrayList<>(...)}). A shallow list copy suffices because the elements
     * ({@link Button} / {@link ContentBlock} / {@link KeyboardRow}) are immutable records. A
     * {@code null} list copies to {@code null} (not an empty list). The keyboard scalars
     * ({@link #keyboardText} / {@link #keyboardParseMode} immutable Strings, {@link #isPersistent} /
     * {@link #oneTimeKeyboard} immutable boxed {@code Boolean}) copy by reference; null stays null.
     * Storing a mutable collection in
     * {@link #customFieldValue} would violate Decision 3 and is
     * disallowed by the validator. {@link #eventName} (Phase 3 / Decision 4) is an immutable String, so
     * it copies by reference; a {@code null} eventName stays null. The SUBSCRIBE_TO_FUNNEL scalars (Phase 5
     * / composition) — {@link #targetFunnelId} / {@link #targetEntryStepId} (immutable Strings) and
     * {@link #endParentAfter} (primitive {@code boolean}) — are immutable value types and copy field-wise
     * (Strings by reference, the boolean by value), preserving snapshot isolation (Decision 3).
     */
    public static FunnelStep copyOf(FunnelStep source) {
        if (source == null) {
            return null;
        }
        FunnelStep copy = new FunnelStep();
        copy.stepType = source.stepType;
        copy.order = source.order;
        copy.delayValue = source.delayValue;
        copy.delayUnit = source.delayUnit;
        copy.tagSlug = source.tagSlug;
        copy.customFieldKey = source.customFieldKey;
        copy.customFieldValue = source.customFieldValue;
        // EMIT_EVENT target (Phase 3 / Decision 4): immutable String — reference copy, null stays null.
        copy.eventName = source.eventName;
        // SUBSCRIBE_TO_FUNNEL target (Phase 5 / composition): immutable Strings — reference copy (null stays
        // null); endParentAfter is a primitive boolean — value copy. Snapshot isolation preserved (Decision 3).
        copy.targetFunnelId = source.targetFunnelId;
        copy.targetEntryStepId = source.targetEntryStepId;
        copy.endParentAfter = source.endParentAfter;
        // Graph scalars (immutable Strings/boxed) — reference copy.
        copy.id = source.id;
        copy.next = source.next;
        copy.timeoutValue = source.timeoutValue;
        copy.timeoutUnit = source.timeoutUnit;
        copy.timeoutTargetStepId = source.timeoutTargetStepId;
        // SET_KEYBOARD / CLEAR_KEYBOARD scalars (Phase 7 / 16-persistent-keyboard): keyboardText /
        // keyboardParseMode are immutable Strings, isPersistent / oneTimeKeyboard are immutable boxed
        // Booleans — reference copy, null stays null.
        copy.keyboardText = source.keyboardText;
        copy.keyboardParseMode = source.keyboardParseMode;
        copy.isPersistent = source.isPersistent;
        copy.oneTimeKeyboard = source.oneTimeKeyboard;
        // Mutable lists of immutable records — defensive shallow copy (buttons: Decision 5; blocks:
        // Decision 1; keyboardRows: 16-persistent-keyboard / Decision 2), null stays null.
        copy.buttons = source.buttons == null ? null : new ArrayList<>(source.buttons);
        copy.blocks = source.blocks == null ? null : new ArrayList<>(source.blocks);
        copy.keyboardRows = source.keyboardRows == null ? null : new ArrayList<>(source.keyboardRows);
        return copy;
    }

    public StepType getStepType() { return stepType; }
    public void setStepType(StepType stepType) { this.stepType = stepType; }

    public int getOrder() { return order; }
    public void setOrder(int order) { this.order = order; }

    public List<ContentBlock> getBlocks() { return blocks; }
    public void setBlocks(List<ContentBlock> blocks) { this.blocks = blocks; }

    public Integer getDelayValue() { return delayValue; }
    public void setDelayValue(Integer delayValue) { this.delayValue = delayValue; }

    public String getDelayUnit() { return delayUnit; }
    public void setDelayUnit(String delayUnit) { this.delayUnit = delayUnit; }

    public String getTagSlug() { return tagSlug; }
    public void setTagSlug(String tagSlug) { this.tagSlug = tagSlug; }

    public String getCustomFieldKey() { return customFieldKey; }
    public void setCustomFieldKey(String customFieldKey) { this.customFieldKey = customFieldKey; }

    public Object getCustomFieldValue() { return customFieldValue; }
    public void setCustomFieldValue(Object customFieldValue) { this.customFieldValue = customFieldValue; }

    public String getEventName() { return eventName; }
    public void setEventName(String eventName) { this.eventName = eventName; }

    public String getTargetFunnelId() { return targetFunnelId; }
    public void setTargetFunnelId(String targetFunnelId) { this.targetFunnelId = targetFunnelId; }

    public String getTargetEntryStepId() { return targetEntryStepId; }
    public void setTargetEntryStepId(String targetEntryStepId) { this.targetEntryStepId = targetEntryStepId; }

    public boolean isEndParentAfter() { return endParentAfter; }
    public void setEndParentAfter(boolean endParentAfter) { this.endParentAfter = endParentAfter; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getNext() { return next; }
    public void setNext(String next) { this.next = next; }

    public List<Button> getButtons() { return buttons; }
    public void setButtons(List<Button> buttons) { this.buttons = buttons; }

    public Integer getTimeoutValue() { return timeoutValue; }
    public void setTimeoutValue(Integer timeoutValue) { this.timeoutValue = timeoutValue; }

    public String getTimeoutUnit() { return timeoutUnit; }
    public void setTimeoutUnit(String timeoutUnit) { this.timeoutUnit = timeoutUnit; }

    public String getTimeoutTargetStepId() { return timeoutTargetStepId; }
    public void setTimeoutTargetStepId(String timeoutTargetStepId) { this.timeoutTargetStepId = timeoutTargetStepId; }

    public String getKeyboardText() { return keyboardText; }
    public void setKeyboardText(String keyboardText) { this.keyboardText = keyboardText; }

    public String getKeyboardParseMode() { return keyboardParseMode; }
    public void setKeyboardParseMode(String keyboardParseMode) { this.keyboardParseMode = keyboardParseMode; }

    public List<KeyboardRow> getKeyboardRows() { return keyboardRows; }
    public void setKeyboardRows(List<KeyboardRow> keyboardRows) { this.keyboardRows = keyboardRows; }

    public Boolean getIsPersistent() { return isPersistent; }
    public void setIsPersistent(Boolean isPersistent) { this.isPersistent = isPersistent; }

    public Boolean getOneTimeKeyboard() { return oneTimeKeyboard; }
    public void setOneTimeKeyboard(Boolean oneTimeKeyboard) { this.oneTimeKeyboard = oneTimeKeyboard; }
}
