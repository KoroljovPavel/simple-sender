package com.botfunnel.subscriber;

import com.botfunnel.subscriber.dto.SendMessageRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Telegram text bounds 1..4096 are enforced by Bean Validation on SendMessageRequest (@NotBlank +
// @Size). Exercises the constraints directly via a Validator — no Spring context needed.
class TelegramTextValidatorTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private boolean valid(String text) {
        return validator.validate(new SendMessageRequest(text)).isEmpty();
    }

    @Test
    void validate_1char_accepted() {
        assertThat(valid("a")).isTrue();
    }

    @Test
    void validate_4096chars_accepted() {
        assertThat(valid("a".repeat(4096))).isTrue();
    }

    @Test
    void validate_4097chars_rejected() {
        assertThat(valid("a".repeat(4097))).isFalse();
    }

    @Test
    void validate_blank_rejected() {
        assertThat(valid("   ")).isFalse();
    }

    @Test
    void validate_null_rejected() {
        assertThat(valid(null)).isFalse();
    }
}
