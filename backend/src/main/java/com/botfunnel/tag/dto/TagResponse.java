package com.botfunnel.tag.dto;

import java.time.Instant;

// Read-only response DTO. Deliberately omits id and projectId (internal details) — the client keys
// tags by slug within a project scope.
public record TagResponse(
        String slug,
        String label,
        long subscriberCount,
        Instant createdAt
) {}
