package com.botfunnel.tag.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Size;

// Label-only PATCH body. slug is intentionally absent: Tag.slug is immutable (Decision 11), and the
// schema enforces it — a hostile {"slug": "..."} field is silently dropped by @JsonIgnoreProperties.
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateTagRequest(
        @Size(max = 64) String label
) {}
