package com.botfunnel.subscriber.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Cursor-paginated page (Decision 5). {@code nextCursor} is the opaque base64url cursor for the next
 * page, or {@code null} on the final page — presence of {@code nextCursor} is the sole "has more"
 * signal (no total count is computed; it is heavy under text-search).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PageResponse<T>(List<T> items, String nextCursor) {
}
