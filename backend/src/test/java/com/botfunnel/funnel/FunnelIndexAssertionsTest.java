package com.botfunnel.funnel;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Phase 8 (17-funnel-multi-entry / Task 1): pins the new {@link Funnel} index shape and the surviving
 * static-block defensive assertion. The flat-trio partial-unique index
 * ({@code projectId_triggerType_triggerValue_unique_active}) is replaced by a partial-unique index over
 * the denormalized {@code onStartTriggerValue} scalar (Decision 6). The static block keeps only the
 * {@code 'active'} == {@code FunnelStatus.active.name()} guard (the {@code 'on_start'} literal is gone
 * from the new partialFilter, so its assertion would be dead/misleading).
 */
class FunnelIndexAssertionsTest {

    @Test
    void staticInitializerDoesNotThrow() {
        // Forcing class init runs the static block; the surviving 'active' literal must still byte-match
        // FunnelStatus.active.name(), otherwise this throws ExceptionInInitializerError.
        assertThatCode(() -> Class.forName("com.botfunnel.funnel.Funnel"))
                .doesNotThrowAnyException();
    }

    @Test
    void onStartTriggerValuePartialUniqueIndexDeclared() {
        CompoundIndex[] indexes = Funnel.class.getAnnotation(CompoundIndexes.class).value();

        // The old flat-trio index must be gone.
        assertThat(Arrays.stream(indexes).map(CompoundIndex::name))
                .doesNotContain("projectId_triggerType_triggerValue_unique_active");

        // The (projectId, status) lookup index survives.
        assertThat(Arrays.stream(indexes).map(CompoundIndex::name))
                .contains("projectId_status");

        // The new partial-unique index over onStartTriggerValue exists with the expected shape.
        CompoundIndex onStartIndex = Arrays.stream(indexes)
                .filter(ix -> ix.name().equals("projectId_onStartTriggerValue_unique_active"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing projectId_onStartTriggerValue_unique_active index. Found: "
                                + Arrays.stream(indexes).map(CompoundIndex::name).toList()));

        assertThat(onStartIndex.unique()).as("onStartTriggerValue index must be unique").isTrue();
        assertThat(onStartIndex.def())
                .contains("projectId")
                .contains("onStartTriggerValue");
        assertThat(onStartIndex.partialFilter())
                .as("partialFilter must restrict to active funnels that actually have an on_start value")
                .contains("onStartTriggerValue")
                .contains("$exists")
                .contains("active");
    }
}
