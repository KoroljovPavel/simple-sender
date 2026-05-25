package com.botfunnel.project;

import java.time.Instant;

/**
 * A custom field schema entry embedded in {@link Project#getCustomFieldDefinitions()}. The
 * {@code name} is an immutable slug ({@code ^[a-z0-9_-]{1,32}$}); {@code label} and
 * {@code defaultValue} are editable. {@code defaultValue} is validated against {@code type} at
 * create + update (Task 5). Max 20 per project enforced via atomic conditional push (Decision 3).
 */
public record CustomFieldDefinition(
        String name,
        String label,
        CustomFieldType type,
        Object defaultValue,
        Instant createdAt
) {}
