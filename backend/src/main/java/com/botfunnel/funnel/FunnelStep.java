package com.botfunnel.funnel;

/**
 * Flat embedded persistence POJO for a single funnel step (Decision 12: no {@code _class}
 * discriminator). {@code stepType} + {@code order} are always set; the remaining fields are
 * type-specific and nullable. No bean-validation here — validation lives in the DTO/service layer
 * (Task 5). {@link #copyOf(FunnelStep)} produces a deep copy for the execution snapshot (Decision 3);
 * all fields are immutable value types (String / boxed primitives / enum), so a field-wise copy is a
 * genuine deep copy.
 */
public class FunnelStep {

    private StepType stepType;
    private int order;

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
    private Object customFieldValue;

    public FunnelStep() {
    }

    /**
     * Deep copy for the execution snapshot (Decision 3). Returns {@code null} for a {@code null} input
     * so callers can map a list element-wise without null-guarding each entry.
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
}
