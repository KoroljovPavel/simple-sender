package com.botfunnel.subscriber;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class NoOpSubscriberServiceTest {

    private final NoOpSubscriberService service = new NoOpSubscriberService();

    @Test
    void upsertFromTelegramUpdate_completesWithoutError() {
        assertThatCode(() -> service.upsertFromTelegramUpdate(
                "proj-A", 100L, 200L, "private", 300L,
                "Alice", "Liddell", "alice", "en"))
                .doesNotThrowAnyException();
    }

    @Test
    void markUnsubscribed_completesWithoutError() {
        assertThatCode(() -> service.markUnsubscribed("proj-A", 100L, 200L))
                .doesNotThrowAnyException();
    }

    @Test
    void upsertFromTelegramUpdate_acceptsNullOptionalFields() {
        assertThatCode(() -> service.upsertFromTelegramUpdate(
                "proj-A", 100L, 200L, "private", 300L,
                null, null, null, null))
                .doesNotThrowAnyException();
    }
}
