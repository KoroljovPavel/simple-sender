package com.botfunnel.subscriber;

import com.botfunnel.common.AppException;
import com.botfunnel.project.CustomFieldType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Pure unit coverage of the per-type validation primitive (no Spring context).
class CustomFieldValueValidatorTest {

    private final CustomFieldValueValidator validator = new CustomFieldValueValidator();

    // ─── STRING ────────────────────────────────────────────────────────────

    @Test
    void string_trimsWhitespace_andRejectsOver1024Chars() {
        assertThat(validator.validate(CustomFieldType.STRING, "  hello  ")).isEqualTo("hello");

        String exactly1024 = "a".repeat(1024);
        assertThat(validator.validate(CustomFieldType.STRING, exactly1024)).isEqualTo(exactly1024);

        // 1025 non-blank chars survive trim → over cap → rejected.
        String over = "b".repeat(1025);
        assertThatThrownBy(() -> validator.validate(CustomFieldType.STRING, over))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getCode())
                        .isEqualTo("custom_field_type_mismatch"));
    }

    @Test
    void string_acceptsNull() {
        assertThat(validator.validate(CustomFieldType.STRING, null)).isNull();
    }

    @Test
    void string_rejectsNonStringValue() {
        assertThatThrownBy(() -> validator.validate(CustomFieldType.STRING, 42))
                .isInstanceOf(AppException.class);
    }

    // ─── NUMBER ──────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"3.14", "-0", "1e10"})
    void number_parsesFiniteDoubles_includingNegativeAndExponent(String raw) {
        Object result = validator.validate(CustomFieldType.NUMBER, raw);
        assertThat(result).isInstanceOf(Double.class);
        assertThat((Double) result).isEqualTo(Double.parseDouble(raw));
    }

    @Test
    void number_acceptsNumericJsonValue() {
        assertThat(validator.validate(CustomFieldType.NUMBER, 42)).isEqualTo(42.0d);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "-Infinity", "abc", ""})
    void number_rejectsNaN_Infinity_andNonNumericStrings(String raw) {
        assertThatThrownBy(() -> validator.validate(CustomFieldType.NUMBER, raw))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getCode())
                        .isEqualTo("custom_field_type_mismatch"));
    }

    @Test
    void number_rejectsNaNAndInfinityAsDoubles() {
        assertThatThrownBy(() -> validator.validate(CustomFieldType.NUMBER, Double.NaN))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> validator.validate(CustomFieldType.NUMBER, Double.POSITIVE_INFINITY))
                .isInstanceOf(AppException.class);
    }

    // ─── BOOLEAN ──────────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
            "true,true", "false,false", "yes,true", "no,false",
            "TRUE,true", "False,false", "Yes,true", "NO,false"
    })
    void boolean_acceptsTrueFalseYesNo_caseInsensitive(String raw, boolean expected) {
        assertThat(validator.validate(CustomFieldType.BOOLEAN, raw)).isEqualTo(expected);
    }

    @Test
    void boolean_acceptsNativeBooleanValues() {
        assertThat(validator.validate(CustomFieldType.BOOLEAN, Boolean.TRUE)).isEqualTo(true);
        assertThat(validator.validate(CustomFieldType.BOOLEAN, Boolean.FALSE)).isEqualTo(false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"maybe", "1", "0", "y", "n", "truthy"})
    void boolean_rejectsArbitraryStrings(String raw) {
        assertThatThrownBy(() -> validator.validate(CustomFieldType.BOOLEAN, raw))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getCode())
                        .isEqualTo("custom_field_type_mismatch"));
    }

    // ─── DATE ──────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"2024-01-15T10:30:00Z", "2024-12-31T23:59:59+02:00"})
    void date_acceptsIso8601(String raw) {
        assertThat(validator.validate(CustomFieldType.DATE, raw))
                .isEqualTo(OffsetDateTime.parse(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"tomorrow", "2024-01-15", "15/01/2024", "not a date"})
    void date_rejectsFreeFormText(String raw) {
        assertThatThrownBy(() -> validator.validate(CustomFieldType.DATE, raw))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getCode())
                        .isEqualTo("custom_field_type_mismatch"));
    }
}
