package com.botfunnel.project;

/**
 * Type of a custom field definition. Immutable after create (Decision 11). Drives per-type
 * validation of both the definition's {@code defaultValue} and subscriber custom-field values.
 */
public enum CustomFieldType {
    STRING,
    NUMBER,
    BOOLEAN,
    DATE
}
