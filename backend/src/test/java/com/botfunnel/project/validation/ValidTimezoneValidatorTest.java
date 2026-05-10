package com.botfunnel.project.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValidTimezoneValidatorTest {

    private final ValidTimezoneValidator validator = new ValidTimezoneValidator();

    @Test
    void valid_iana_kyiv_returns_true() {
        assertThat(validator.isValid("Europe/Kyiv", null)).isTrue();
    }

    @Test
    void valid_iana_utc_returns_true() {
        assertThat(validator.isValid("UTC", null)).isTrue();
    }

    @Test
    void invalid_offset_format_gmt5_returns_false() {
        // ZoneId.of("GMT+5") parses; the strict-IANA invariant (Decision 3) rejects it.
        assertThat(validator.isValid("GMT+5", null)).isFalse();
    }

    @Test
    void invalid_offset_format_plus_0200_returns_false() {
        assertThat(validator.isValid("+02:00", null)).isFalse();
    }

    @Test
    void invalid_abbreviation_ut_returns_false() {
        // "UT" parses via ZoneId.of as a legacy abbreviation; not in IANA list.
        assertThat(validator.isValid("UT", null)).isFalse();
    }

    @Test
    void invalid_nonexistent_zone_returns_false() {
        assertThat(validator.isValid("NotAZone", null)).isFalse();
    }

    @Test
    void valid_null_returns_true() {
        // Delegate to @NotBlank (matches ValidPasswordValidator precedent).
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    void valid_empty_returns_true() {
        assertThat(validator.isValid("", null)).isTrue();
    }

    @Test
    void valid_whitespace_only_returns_true() {
        assertThat(validator.isValid("   ", null)).isTrue();
    }
}
