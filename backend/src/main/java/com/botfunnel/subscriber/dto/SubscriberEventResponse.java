package com.botfunnel.subscriber.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

/**
 * Profile history-feed row (AC14) — projection over a {@code subscriber_events} document. The
 * subscriberId/projectId are implied by the request path and intentionally omitted from the wire.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubscriberEventResponse(
        String id,
        String eventType,
        Map<String, Object> metadata,
        Instant createdAt) {
}
