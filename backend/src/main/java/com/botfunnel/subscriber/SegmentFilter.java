package com.botfunnel.subscriber;

import java.time.Instant;
import java.util.List;

/**
 * Immutable description of a subscriber-list segment (Decision 5). One source of truth shared by the
 * list endpoint ({@code SubscriberListQuery} → this) and, later, the export pipeline (Task 9). Carries
 * only the query intent — translation to a Mongo {@code Query} lives in {@link SegmentFilterBuilder},
 * which performs no I/O.
 *
 * @param search        text-search input (whole-word match); {@code <2} chars is dropped by the builder
 * @param status        single status filter, or {@code null} for any
 * @param tagsInclude   subscriber must carry ALL these slugs ({@code $all})
 * @param tagsExclude   subscriber must carry NONE of these slugs ({@code $nin})
 * @param subscribedFrom inclusive lower bound on {@code subscribedAt}, or {@code null}
 * @param subscribedTo  inclusive upper bound on {@code subscribedAt}, or {@code null}
 * @param sort          ordering key (also selects the cursor tie-break field)
 * @param cursor        decoded pagination cursor, or {@code null} for the first page
 * @param limit         requested page size; clamped to {@code [1,200]} (default 50) by the builder
 */
public record SegmentFilter(
        String search,
        SubscriberStatus status,
        List<String> tagsInclude,
        List<String> tagsExclude,
        Instant subscribedFrom,
        Instant subscribedTo,
        SortKey sort,
        Cursor cursor,
        int limit) {

    /** Sort key — also names the field the cursor tie-break compares on. */
    public enum SortKey {
        CREATED_DESC("subscribedAt"),
        LAST_SEEN_DESC("lastSeenAt");

        private final String field;

        SortKey(String field) {
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    /**
     * Decoded keyset cursor. {@code v} is the sort field's value as epoch-milli; {@code id} is the
     * last subscriber's 24-char hex {@code _id}. Together they break ties when many subscribers share
     * the same sort value (broadcast burst / funnel re-fire).
     */
    public record Cursor(long v, String id) {
    }
}
