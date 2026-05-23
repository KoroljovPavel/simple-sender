package com.botfunnel.funnel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class NoOpFunnelTriggerServiceTest {

    private final NoOpFunnelTriggerService service = new NoOpFunnelTriggerService();

    @Test
    void fire_completesWithoutError() {
        assertThatCode(() -> service.fire("proj-A", 200L, "on_start", "ref_smoke_001"))
                .doesNotThrowAnyException();
    }

    @Test
    void cancelActiveFor_completesWithoutError() {
        assertThatCode(() -> service.cancelActiveFor("proj-A", 200L))
                .doesNotThrowAnyException();
    }

    @Test
    void fire_acceptsNullPayload() {
        assertThatCode(() -> service.fire("proj-A", 200L, "on_start", null))
                .doesNotThrowAnyException();
    }

    @Test
    void fire_acceptsEmptyPayload() {
        assertThatCode(() -> service.fire("proj-A", 200L, "on_start", ""))
                .doesNotThrowAnyException();
    }
}
