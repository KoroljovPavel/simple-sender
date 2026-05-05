package com.botfunnel.auth.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ValidPasswordValidatorTest {

    private final ValidPasswordValidator validator = new ValidPasswordValidator();

    @Test
    void valid_8charsLetterAndDigit_returnsTrue() {
        assertThat(validator.isValid("abcdefg1", null)).isTrue();
    }

    @Test
    void valid_longPassword_withDigitAndLetter_returnsTrue() {
        assertThat(validator.isValid("MyStrongPassword123!", null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "short1A",      // 7 chars — too short
            "abcdefgh",     // no digit
            "12345678",     // no letter
            "       1",     // 8 chars but no letter (only spaces and a digit)
            "пароль123"     // ASCII rule — Cyrillic letters do not satisfy [a-zA-Z]
    })
    void invalid_variousBadInputs_returnsFalse(String input) {
        assertThat(validator.isValid(input, null)).isFalse();
    }

    @Test
    void valid_minimumBoundary_8charsLastDigit_returnsTrue() {
        assertThat(validator.isValid("1234567a", null)).isTrue();
    }

    @Test
    void invalid_null_returnsTrue_delegatesToNotBlank() {
        // @ValidPassword does not duplicate @NotBlank — null/blank is the responsibility of
        // @NotBlank on the same field. isValid(null) returns true so a null doesn't show two errors.
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    void invalid_emptyString_returnsTrue_delegatesToNotBlank() {
        assertThat(validator.isValid("", null)).isTrue();
    }
}
