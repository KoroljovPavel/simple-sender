package com.botfunnel.project.dto;

import com.botfunnel.project.validation.ValidTimezone;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Size;

// @JsonIgnoreProperties(ignoreUnknown = true) — mass assignment defense, see CreateProjectRequest.
// Patch semantics: every field is optional. Service layer (Task 3) interprets:
//   name        — null = no change; non-null = rename (must satisfy size).
//   description — null = no change; blank = clear to null (AC-22b); non-blank = set.
//   timezone    — null/blank = no change; non-blank IANA = set.
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateProjectRequest(
        @Size(min = 3, max = 50) String name,
        @Size(max = 200) String description,
        @ValidTimezone String timezone
) {}
