package com.botfunnel.subscriber.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Export record as returned by {@code POST /subscribers/export} (202) and {@code GET /exports}.
 * {@code downloadUrl} is the signed URL for DONE rows whose token is still valid
 * ({@code expiresAt > now}); it is {@code null} otherwise — the UI flips the button to
 * "Refresh URL" when the URL is null (Decision 15).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExportResponse(String exportId, String status, Long rowCount,
                             Instant createdAt, Instant completedAt,
                             String downloadUrl, Instant expiresAt) {
}
