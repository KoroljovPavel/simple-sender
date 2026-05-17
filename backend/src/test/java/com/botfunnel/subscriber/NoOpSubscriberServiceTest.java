package com.botfunnel.subscriber;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;

class NoOpSubscriberServiceTest {

    private final NoOpSubscriberService service = new NoOpSubscriberService();

    @Test
    void upsertFromTelegramUpdate_returnsMonoEmpty() {
        StepVerifier.create(service.upsertFromTelegramUpdate(
                        "proj-A", 100L, 200L, "private", 300L,
                        "Alice", "Liddell", "alice", "en"))
                .expectComplete()
                .verify(Duration.ofMillis(100));
    }

    @Test
    void markUnsubscribed_returnsMonoEmpty() {
        StepVerifier.create(service.markUnsubscribed("proj-A", 100L, 200L))
                .expectComplete()
                .verify(Duration.ofMillis(100));
    }

    @Test
    void upsertFromTelegramUpdate_acceptsNullOptionalFields() {
        StepVerifier.create(service.upsertFromTelegramUpdate(
                        "proj-A", 100L, 200L, "private", 300L,
                        null, null, null, null))
                .expectComplete()
                .verify(Duration.ofMillis(100));
    }
}
