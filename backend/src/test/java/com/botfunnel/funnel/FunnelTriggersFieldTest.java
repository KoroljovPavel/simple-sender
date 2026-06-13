package com.botfunnel.funnel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8 (17-funnel-multi-entry / Task 1): {@link Funnel} drops the flat trigger trio
 * ({@code triggerType}/{@code triggerValue}/{@code keywords}) in favour of a {@code List<Trigger>}
 * plus a denormalized nullable scalar {@code onStartTriggerValue} (Decision 6 / Variant A). This test
 * pins the new field contract via get/set round-trips and proves the old flat-trio accessors are gone
 * via a reflection-based negative assertion ({@link #oldFlatTriggerGettersRemoved()}), mirroring the
 * negative index-name assertion in {@link FunnelIndexAssertionsTest}.
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

        // Per Decision 10, only `event` triggers carry a non-null entryStepId (a keyword trigger leads via
        // its keywords list, not a stored entry step). Use an event trigger to exercise entryStepId.
        Trigger event = new Trigger();
        event.setTriggerType("event");
        event.setTriggerValue("purchase_done");
        event.setEntryStepId("step-2");

        funnel.setTriggers(List.of(onStart, event));

        // Trigger now has value equality (equals/hashCode over all four fields), so assert the persisted
        // shape structurally — independent of reference identity (Mongo re-hydrates fresh instances).
        Trigger expectedOnStart = new Trigger();
        expectedOnStart.setTriggerType("on_start");
        expectedOnStart.setTriggerValue("");

        Trigger expectedEvent = new Trigger();
        expectedEvent.setTriggerType("event");
        expectedEvent.setTriggerValue("purchase_done");
        expectedEvent.setEntryStepId("step-2");

        assertThat(funnel.getTriggers()).containsExactly(expectedOnStart, expectedEvent);
    }

    @Test
    void oldFlatTriggerGettersRemoved() {
        // Negative proof that the flat trigger trio is gone from Funnel — the trigger trio now lives on
        // Trigger, not on Funnel (Decision 6). Reflection (not compile-time absence) so a re-added getter
        // is caught even if no production code references it. Mirrors the negative index-name assertion.
        for (String removed : List.of("getTriggerType", "getTriggerValue", "getKeywords")) {
            assertThat(catchNoSuchMethod(removed))
                    .as("Funnel must no longer expose %s (moved to Trigger)", removed)
                    .isTrue();
        }
    }

    private static boolean catchNoSuchMethod(String getter) {
        try {
            Funnel.class.getMethod(getter);
            return false;
        } catch (NoSuchMethodException expected) {
            return true;
        }
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
