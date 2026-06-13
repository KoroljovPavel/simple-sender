package com.botfunnel.funnel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8 (17-funnel-multi-entry / Task 1): {@link Trigger} is a flat embedded POJO holding one entry
 * trigger of a funnel — {@code (triggerType, triggerValue, keywords, entryStepId)}. No bean-validation,
 * no Spring annotations (validation lives in the DTO/service layer). These tests pin the field contract:
 * a get/set round-trip per field and the {@code on_start} shape (no {@code entryStepId}).
 */
class TriggerTest {

    @Test
    void gettersAndSettersRoundTrip() {
        Trigger trigger = new Trigger();

        // Nullable fields start null (no implicit defaults).
        assertThat(trigger.getTriggerType()).isNull();
        assertThat(trigger.getTriggerValue()).isNull();
        assertThat(trigger.getKeywords()).isNull();
        assertThat(trigger.getEntryStepId()).isNull();

        trigger.setTriggerType("keyword");
        trigger.setTriggerValue("promo");
        trigger.setKeywords(List.of("buy", "purchase"));
        trigger.setEntryStepId("step-7");

        assertThat(trigger.getTriggerType()).isEqualTo("keyword");
        assertThat(trigger.getTriggerValue()).isEqualTo("promo");
        assertThat(trigger.getKeywords()).containsExactly("buy", "purchase");
        assertThat(trigger.getEntryStepId()).isEqualTo("step-7");
    }

    /**
     * Contract: an {@code on_start} trigger has {@code triggerValue == ""} (empty string, not null) and
     * {@code entryStepId == null} (it leads START, not a mid-funnel REDIRECT). The POJO must hold a null
     * {@code entryStepId} without coercing it.
     */
    @Test
    void onStartShapeAllowsNullEntryStepId() {
        Trigger trigger = new Trigger();
        trigger.setTriggerType("on_start");
        trigger.setTriggerValue("");
        trigger.setEntryStepId(null);

        assertThat(trigger.getTriggerType()).isEqualTo("on_start");
        assertThat(trigger.getTriggerValue()).isEmpty();
        assertThat(trigger.getEntryStepId()).isNull();
    }

    /**
     * Value equality over all four fields (Mongo re-hydrates triggers as fresh instances; Task 5's
     * redirect re-scans {@code triggers[]} to find the matched element — both depend on structural
     * equality, not reference identity).
     */
    @Test
    void equalsAndHashCodeOverAllFourFields() {
        Trigger a = trigger("event", "purchase_done", List.of("buy"), "step-2");
        Trigger b = trigger("event", "purchase_done", List.of("buy"), "step-2");

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);

        // A difference in any single field breaks equality.
        assertThat(a).isNotEqualTo(trigger("keyword", "purchase_done", List.of("buy"), "step-2"));
        assertThat(a).isNotEqualTo(trigger("event", "other", List.of("buy"), "step-2"));
        assertThat(a).isNotEqualTo(trigger("event", "purchase_done", List.of("sell"), "step-2"));
        assertThat(a).isNotEqualTo(trigger("event", "purchase_done", List.of("buy"), "step-9"));

        // Two all-null triggers are equal (reflexive null handling).
        assertThat(new Trigger()).isEqualTo(new Trigger());
    }

    private static Trigger trigger(String type, String value, List<String> keywords, String entryStepId) {
        Trigger t = new Trigger();
        t.setTriggerType(type);
        t.setTriggerValue(value);
        t.setKeywords(keywords);
        t.setEntryStepId(entryStepId);
        return t;
    }
}
