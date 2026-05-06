package com.botfunnel.profile.dto;

import com.botfunnel.auth.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ChangePasswordRequest {

    // Hard upper bound prevents an attacker from forcing the BCrypt encoder to hash
    // megabyte-sized strings on the change-password path.
    @NotBlank
    @Size(max = 200)
    private String currentPassword;

    @NotBlank
    @Size(max = 200)
    @ValidPassword
    private String newPassword;

    public ChangePasswordRequest() {}

    public String getCurrentPassword() { return currentPassword; }
    public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }

    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
}
