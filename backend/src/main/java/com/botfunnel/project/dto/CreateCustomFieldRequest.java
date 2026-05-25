package com.botfunnel.project.dto;

import com.botfunnel.project.CustomFieldType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Create body for a custom-field schema definition. @JsonIgnoreProperties(ignoreUnknown = true)
// silently drops any unknown field a hostile client posts (mass-assignment defense, patterns.md).
// name is the immutable slug (Decision 11): @NotBlank is required because @Pattern alone accepts
// null. defaultValue is validated against type by CustomFieldValueValidator (closes security F9).
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateCustomFieldRequest(
        @NotBlank @Pattern(regexp = "^[a-z0-9_-]{1,32}$") String name,
        @NotBlank @Size(max = 64) String label,
        @NotNull CustomFieldType type,
        Object defaultValue
) {}
