package com.botfunnel.auth.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ValidPasswordValidator implements ConstraintValidator<ValidPassword, String> {

    // Null/blank is the responsibility of @NotBlank on the same field; returning true here
    // avoids stacking two error messages for the same missing value.
    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        if (password == null || password.isEmpty()) {
            return true;
        }
        if (password.length() < 8) {
            return false;
        }
        // Spec mandates ASCII letter [a-zA-Z] and ASCII digit [0-9] — not Unicode classes.
        // Keeping the check ASCII-only matches the regex form documented in tech-spec Decision 15.
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                hasLetter = true;
            } else if (c >= '0' && c <= '9') {
                hasDigit = true;
            }
            if (hasLetter && hasDigit) {
                return true;
            }
        }
        return false;
    }
}
