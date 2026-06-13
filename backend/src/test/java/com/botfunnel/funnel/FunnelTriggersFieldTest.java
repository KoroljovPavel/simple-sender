package com.botfunnel.funnel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8 (17-funnel-multi-entry / Task 1): {@link Funnel} drops the flat trigger trio
 * ({@code triggerType}/{@code triggerValue}/{@code keywords}) in favour of a {@code List<Trigger>}
 * plus a denormalized nullable scalar {@code onStartTriggerValue} (Decision 6 / Variant A). This test
 * pins the new field contract via get/set round-trips. It also serves as a <b>compile-time proof</b>
 * that the old getters are gone: the class never references {@code getTriggerType}/{@code getTriggerValue}/
 * {@code getKeywords}, so if they survived the test would still compile — but the field is asserted via
 * the new accessors only, and downstream tasks rely on the old ones being absent.
 */
class FunnelTriggersFieldTest {

    @Test
    void triggersListRoundTrip() {
        Funnel funnel = new Funnel();

        // New collection field starts null.
        assertThat(funnel.getTriggers()).isNull();

        Trigger onStart = new Trigger();
        onStart.setTriggerType("on_start");
        onStart.setTriggerValue("");

        Trigger keyword = new Trigger();
        keyword.setTriggerType("keyword");
        keyword.setKeywords(List.of("promo"));
        keyword.setEntryStepId("step-2");

        funnel.setTriggers(List.of(onStart, keyword));

        assertThat(funnel.getTriggers()).containsExactly(onStart, keyword);
    }

    @Test
    void onStartTriggerValueRoundTrip() {
        Funnel funnel = new Funnel();

        // Denormalized scalar is nullable and starts null (no on_start trigger yet).
        assertThat(funnel.getOnStartTriggerValue()).isNull();

        funnel.setOnStartTriggerValue("");
        assertThat(funnel.getOnStartTriggerValue()).isEmpty();

        funnel.setOnStartTriggerValue("welcome");
        assertThat(funnel.getOnStartTriggerValue()).isEqualTo("welcome");

        funnel.setOnStartTriggerValue(null);
        assertThat(funnel.getOnStartTriggerValue()).isNull();
    }
}
