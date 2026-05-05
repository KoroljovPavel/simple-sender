package com.botfunnel.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class ResendVerificationRequest {

    @NotBlank
    @Email
    private String email;

    public ResendVerificationRequest() {}

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
