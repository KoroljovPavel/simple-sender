package com.botfunnel.project.dto;

import com.botfunnel.project.CustomFieldType;

import java.time.Instant;

// Read-only projection of a CustomFieldDefinition. Straight 1:1 mapping of the embedded record.
public record CustomFieldResponse(
        String name,
        String label,
        CustomFieldType type,
        Object defaultValue,
        Instant createdAt
) {}
