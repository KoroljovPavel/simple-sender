package com.botfunnel.project.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.ZoneId;

public class ValidTimezoneValidator implements ConstraintValidator<ValidTimezone, String> {

    // Null/blank is the responsibility of @NotBlank on the same field; returning true here
    // avoids stacking two error messages for the same missing value (matches ValidPasswordValidator).
    @Override
    public boolean isValid(String tz, ConstraintValidatorContext context) {
        if (tz == null || tz.isBlank()) {
            return true;
        }
        // Strict IANA: ZoneId.of accepts offset forms like "GMT+5" / "+02:00" / "UT" which are
        // not IANA names and break DST-aware "send daily at 09:00 local time" semantics
        // (Decision 3). Use the available-zone-id set instead.
        return ZoneId.getAvailableZoneIds().contains(tz);
    }
}
