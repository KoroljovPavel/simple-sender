package com.botfunnel.profile.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// @JsonIgnoreProperties(ignoreUnknown = true) silently discards `isSuperAdmin` and any
// other unknown fields posted by the client — without it, mass assignment would let a
// client elevate their own role through PATCH /api/profile (Decision: whitelist DTO).
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateProfileRequest {

    @NotBlank
    @Size(max = 200)
    private String name;

    public UpdateProfileRequest() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
