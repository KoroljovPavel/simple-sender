package com.botfunnel.funnel.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 18-funnel-canvas / Task 1: the finite-value bean-validation surface of {@link CanvasPositionDto}.
 *
 * <p>The DTO is constructed DIRECTLY in Java (not via a JSON body) on purpose: a bare {@code NaN} /
 * {@code Infinity} JSON token is invalid JSON, so Jackson rejects it at parse time (HTTP 400) BEFORE bean
 * validation runs — a JSON-level test would never exercise the {@code Double.isFinite} constraint and would
 * pass for the wrong reason. Constructing the record + running the {@link Validator} is the path that
 * actually reaches the @AssertTrue finite check.
 */
class CanvasPositionDtoTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        validatorFactory.close();
    }

    @Test
    void rejects_non_finite_coordinates() {
        // NaN in x → violation.
        assertThat(validator.validate(new CanvasPositionDto(Double.NaN, 0.0)))
                .as("NaN x is rejected")
                .isNotEmpty();

        // +Infinity in y → violation.
        assertThat(validator.validate(new CanvasPositionDto(0.0, Double.POSITIVE_INFINITY)))
                .as("+Infinity y is rejected")
                .isNotEmpty();

        // -Infinity in x → violation.
        assertThat(validator.validate(new CanvasPositionDto(Double.NEGATIVE_INFINITY, 0.0)))
                .as("-Infinity x is rejected")
                .isNotEmpty();

        // A finite {x,y} passes (including negative + fractional).
        Set<ConstraintViolation<CanvasPositionDto>> finite =
                validator.validate(new CanvasPositionDto(120.5, -42.0));
        assertThat(finite).as("finite {x,y} passes").isEmpty();

        // A fully-null position passes (additive-nullable; an unpositioned node).
        assertThat(validator.validate(new CanvasPositionDto(null, null)))
                .as("null {x,y} passes").isEmpty();
    }
}
