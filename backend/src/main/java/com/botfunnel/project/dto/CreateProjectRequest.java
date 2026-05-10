package com.botfunnel.project.dto;

import com.botfunnel.project.validation.ValidTimezone;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// @JsonIgnoreProperties(ignoreUnknown = true) silently discards `ownerId`, `id`, `deletedAt` and
// any other unknown fields posted by the client — defense against mass assignment so a client
// cannot set ownerId on a foreign user's project (Risk R2).
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateProjectRequest(
        @NotBlank @Size(min = 3, max = 50) String name,
        @Size(max = 200) String description,
        @NotBlank @ValidTimezone String timezone
) {}
