package com.botfunnel.tag.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// @JsonIgnoreProperties(ignoreUnknown = true) silently drops ownerId, projectId, subscriberCount and
// any other unknown field posted by a hostile client — mass-assignment defense (patterns.md). slug is
// validated by the Decision 11 regex; @NotBlank is required because @Pattern alone accepts null.
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateTagRequest(
        @NotBlank @Pattern(regexp = "^[a-z0-9_-]{1,32}$") String slug,
        @Size(max = 64) String label
) {}
