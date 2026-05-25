package com.botfunnel.subscriber.export;

/**
 * Export lifecycle state. Persisted via {@code name()} (Spring Data default) — UPPERCASE. The
 * {@code PENDING} / {@code RUNNING} literals appear in the {@code exports_in_flight_unique} partial
 * filter; {@link SubscriberExport}'s class-load guard asserts these names never drift.
 */
public enum ExportStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    PURGED
}
