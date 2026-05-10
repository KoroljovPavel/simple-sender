package com.botfunnel.project.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {
            "GMT+5",     // offset format — parses via ZoneId.of but is NOT IANA
            "+02:00",    // offset format
            "UT",        // legacy abbreviation parses but is not IANA
            "NotAZone"   // pure garbage
    })
    void offset_or_invalid_returns_false(String tz) {
        assertThat(validator.isValid(tz, null)).isFalse();
    }

    @Test
    void offset_format_gmt5_returns_false() {
        assertThat(validator.isValid("GMT+5", null)).isFalse();
    }

    @Test
    void offset_format_plus_0200_returns_false() {
        assertThat(validator.isValid("+02:00", null)).isFalse();
    }

    @Test
    void abbreviation_ut_returns_false() {
        assertThat(validator.isValid("UT", null)).isFalse();
    }

    @Test
    void nonexistent_zone_returns_false() {
        assertThat(validator.isValid("NotAZone", null)).isFalse();
    }

    @Test
    void null_returns_true() {
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    void blank_returns_true() {
        assertThat(validator.isValid("", null)).isTrue();
        assertThat(validator.isValid("   ", null)).isTrue();
    }
}
