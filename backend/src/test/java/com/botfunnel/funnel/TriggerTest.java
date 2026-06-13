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
}
