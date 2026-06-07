package com.botfunnel.funnel;

import java.util.ArrayList;
import java.util.List;

/**
 * Flat embedded persistence POJO for a single funnel step (Decision 12: no {@code _class}
 * discriminator). {@code stepType} + {@code order} are always set; the remaining fields are
 * type-specific and nullable. No bean-validation here — validation lives in the DTO/service layer
 * (Task 5). {@link #copyOf(FunnelStep)} produces a deep copy for the execution snapshot (Decision 3);
 * all scalar fields are immutable value types (String / boxed primitives / enum) and copy field-wise
 * — including {@code eventName} (Phase 3 / Decision 4, the EMIT_EVENT target) — but the {@code buttons}
 * list (Phase 2 / Decision 5) is a mutable container and must be copied defensively (see
 * {@link #copyOf(FunnelStep)}).
 */
public class FunnelStep {

    private StepType stepType;
    private int order;

    // Graph model (Phase 2 / Decision 2): stable id + outgoing edge(s). id is minted server-side
    // (ObjectId hex / UUID); next is the default outgoing target (null = next step in list).
    private String id;
    private String next;

    // MENU only (Phase 2): inline-keyboard buttons + optional timeout edge. buttons is a mutable
    // list of immutable Button records — deep-copied in copyOf (Decision 5).
    private List<Button> buttons;
    private Integer timeoutValue;
    private String timeoutUnit;          // "MIN" | "HOUR" | "DAY" (reuses delayUnit convention, Task 3 deviation)
    private String timeoutTargetStepId;  // null = completed

    // SEND_MESSAGE
    private String text;
    private String parseMode;     // null | HTML | MarkdownV2 (null = None)

    // SEND_IMAGE
    private String imageUrl;
    private String caption;

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

    public FunnelStep() {
    }

    /**
     * Deep copy for the execution snapshot (Decision 3). Returns {@code null} for a {@code null} input
     * so callers can map a list element-wise without null-guarding each entry.
     *
     * <p>Deep-copy contract: every scalar field is an immutable value type — {@code String} / boxed
     * primitive / enum — and {@link #customFieldValue} is a validated immutable scalar (Double /
     * Boolean / Instant / String, enforced by CustomFieldValueValidator in Task 4/5), so all scalars
     * copy by reference and still satisfy the contract. The one exception is {@link #buttons} (Phase 2
     * / Decision 5): it is a mutable {@code List}, so it is copied <b>defensively</b>
     * ({@code new ArrayList<>(buttons)}). A shallow list copy suffices because the elements
     * ({@link Button}) are immutable records. A {@code null} list copies to {@code null} (not an empty
     * list). Storing a mutable collection in {@link #customFieldValue} would violate Decision 3 and is
     * disallowed by the validator. {@link #eventName} (Phase 3 / Decision 4) is an immutable String, so
     * it copies by reference; a {@code null} eventName stays null.
     */
    public static FunnelStep copyOf(FunnelStep source) {
        if (source == null) {
            return null;
        }
        FunnelStep copy = new FunnelStep();
        copy.stepType = source.stepType;
        copy.order = source.order;
        copy.text = source.text;
        copy.parseMode = source.parseMode;
        copy.imageUrl = source.imageUrl;
        copy.caption = source.caption;
        copy.delayValue = source.delayValue;
        copy.delayUnit = source.delayUnit;
        copy.tagSlug = source.tagSlug;
        copy.customFieldKey = source.customFieldKey;
        copy.customFieldValue = source.customFieldValue;
        // EMIT_EVENT target (Phase 3 / Decision 4): immutable String — reference copy, null stays null.
        copy.eventName = source.eventName;
        // Graph scalars (immutable Strings/boxed) — reference copy.
        copy.id = source.id;
        copy.next = source.next;
        copy.timeoutValue = source.timeoutValue;
        copy.timeoutUnit = source.timeoutUnit;
        copy.timeoutTargetStepId = source.timeoutTargetStepId;
        // Mutable list of immutable records — defensive shallow copy (Decision 5), null stays null.
        copy.buttons = source.buttons == null ? null : new ArrayList<>(source.buttons);
        return copy;
    }

    public StepType getStepType() { return stepType; }
    public void setStepType(StepType stepType) { this.stepType = stepType; }

    public int getOrder() { return order; }
    public void setOrder(int order) { this.order = order; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getParseMode() { return parseMode; }
    public void setParseMode(String parseMode) { this.parseMode = parseMode; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public String getCaption() { return caption; }
    public void setCaption(String caption) { this.caption = caption; }

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
}
