package com.botfunnel.subscriber;

/**
 * Subscriber lifecycle state. Persisted via {@code name()} (Spring Data default) — UPPERCASE.
 * Used in the {@code subscribers} {@code (projectId, status)} compound index.
 */
public enum SubscriberStatus {
    ACTIVE,
    UNSUBSCRIBED,
    BLOCKED,
    DELETED
}
