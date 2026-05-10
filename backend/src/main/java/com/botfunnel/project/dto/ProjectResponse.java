package com.botfunnel.project.dto;

import java.time.Instant;

// NO ownerId field — response-shape lock for Risk R2 mass-assignment defense.
// IT asserts $.ownerId does not exist on any /api/v1/projects response.
public record ProjectResponse(
        String id,
        String name,
        String description,
        String timezone,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {}
