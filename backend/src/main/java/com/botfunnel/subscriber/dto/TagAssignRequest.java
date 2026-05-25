package com.botfunnel.subscriber.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Pattern;

/**
 * Tag-assign body for {@code POST /subscribers/{id}/tags}. Slug regex matches the {@code Tag.slug}
 * contract (Decision 11). Unknown body keys are dropped (mass-assignment defense).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TagAssignRequest(
        @Pattern(regexp = "^[a-z0-9_-]{1,32}$", message = "{validation.slug.pattern}") String slug) {
}
