package com.botfunnel.project.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Size;

// PATCH body for a custom-field definition. Carries ONLY the editable fields (label, defaultValue):
// name (slug) and type are immutable (Decision 11) and are intentionally absent from the schema.
// Combined with @JsonIgnoreProperties(ignoreUnknown = true), a hostile {"name":...}/{"type":...}
// body field is silently dropped by Jackson — the path variable {name} is the only source of truth
// for which definition to edit. Null label / null defaultValue mean "no change" (PATCH semantics).
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateCustomFieldRequest(
        @Size(max = 64) String label,
        Object defaultValue
) {}
