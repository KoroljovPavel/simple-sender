package com.botfunnel.subscriber;

import com.botfunnel.common.AppException;
import com.botfunnel.project.CustomFieldType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Per-type validation + normalization for custom-field values (AC11, closes security F9). Reused by
 * {@code CustomFieldsController} ({@code defaultValue} vs {@code type} at create/update) and
 * {@code SubscriberCustomFieldsController} (subscriber value PATCH). Pure logic — no Spring deps
 * beyond the stereotype, so it is unit-testable via {@code new CustomFieldValueValidator()}.
 *
 * <p>Null is accepted for every type (a definition may carry a null {@code defaultValue}; a
 * subscriber value may be cleared by passing {@code null}) and returned unchanged. Any rule
 * violation throws 422 {@code custom_field_type_mismatch}. The returned value is the normalized /
 * coerced form: trimmed {@link String}, parsed {@link Double}, {@link Boolean}, or a parsed
 * {@link Instant} (the ISO-8601 input is parsed as an {@link OffsetDateTime} then reduced to its
 * UTC instant — the MongoDB driver has no codec for {@code OffsetDateTime}, but does for
 * {@code Instant}, which is how every other date in the schema is stored).
 */
@Component
public class CustomFieldValueValidator {

    static final String CODE_TYPE_MISMATCH = "custom_field_type_mismatch";
    static final int MAX_STRING_LENGTH = 1024;

    public Object validate(CustomFieldType type, Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        return switch (type) {
            case STRING -> validateString(rawValue);
            case NUMBER -> validateNumber(rawValue);
            case BOOLEAN -> validateBoolean(rawValue);
            case DATE -> validateDate(rawValue);
        };
    }

    private static String validateString(Object raw) {
        if (!(raw instanceof String s)) {
            throw mismatch("expected a string value");
        }
        String trimmed = s.trim();
        if (trimmed.length() > MAX_STRING_LENGTH) {
            throw mismatch("string exceeds " + MAX_STRING_LENGTH + " characters");
        }
        return trimmed;
    }

    private static Double validateNumber(Object raw) {
        double value;
        if (raw instanceof Number n) {
            value = n.doubleValue();
        } else if (raw instanceof String s) {
            try {
                value = Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw mismatch("expected a finite number");
            }
        } else {
            throw mismatch("expected a finite number");
        }
        // Rejects NaN / +Infinity / -Infinity whether they arrived as a Double or as the strings
        // "NaN" / "Infinity" (Double.parseDouble accepts both, Double.isFinite filters them out).
        if (!Double.isFinite(value)) {
            throw mismatch("number must be finite (no NaN / Infinity)");
        }
        return value;
    }

    private static Boolean validateBoolean(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            String v = s.trim().toLowerCase(Locale.ROOT);
            if (v.equals("true") || v.equals("yes")) {
                return Boolean.TRUE;
            }
            if (v.equals("false") || v.equals("no")) {
                return Boolean.FALSE;
            }
        }
        throw mismatch("expected one of true|false|yes|no");
    }

    private static Instant validateDate(Object raw) {
        if (!(raw instanceof String s)) {
            throw mismatch("expected an ISO-8601 date-time string");
        }
        try {
            // Parse as OffsetDateTime (accepts any offset, e.g. +02:00) then reduce to the UTC instant
            // — OffsetDateTime has no MongoDB codec; Instant does.
            return OffsetDateTime.parse(s.trim()).toInstant();
        } catch (DateTimeParseException e) {
            throw mismatch("expected an ISO-8601 date-time string");
        }
    }

    private static AppException mismatch(String detail) {
        return AppException.unprocessableEntity(CODE_TYPE_MISMATCH,
                "Custom field value type mismatch: " + detail);
    }
}
