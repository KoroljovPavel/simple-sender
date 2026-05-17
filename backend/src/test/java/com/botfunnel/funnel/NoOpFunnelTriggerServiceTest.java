package com.botfunnel.funnel;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;

class NoOpFunnelTriggerServiceTest {

    private final NoOpFunnelTriggerService service = new NoOpFunnelTriggerService();

    @Test
    void fire_returnsMonoEmpty() {
        StepVerifier.create(service.fire("proj-A", 200L, "on_start", "ref_smoke_001"))
                .expectComplete()
                .verify(Duration.ofMillis(100));
    }

    @Test
    void cancelActiveFor_returnsMonoEmpty() {
        StepVerifier.create(service.cancelActiveFor("proj-A", 200L))
                .expectComplete()
                .verify(Duration.ofMillis(100));
    }

    @Test
    void fire_acceptsNullPayload() {
        StepVerifier.create(service.fire("proj-A", 200L, "on_start", null))
                .expectComplete()
                .verify(Duration.ofMillis(100));
    }

    @Test
    void fire_acceptsEmptyPayload() {
        StepVerifier.create(service.fire("proj-A", 200L, "on_start", ""))
                .expectComplete()
                .verify(Duration.ofMillis(100));
    }
}
