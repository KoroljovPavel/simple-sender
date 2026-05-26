package com.botfunnel.subscriber.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Body of a successful {@code POST /subscribers/exports/{exportId}/refresh-url} (Decision 15):
 * a freshly-minted signed download URL plus its new absolute expiry.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RefreshUrlResponse(String downloadUrl, Instant expiresAt) {
}
