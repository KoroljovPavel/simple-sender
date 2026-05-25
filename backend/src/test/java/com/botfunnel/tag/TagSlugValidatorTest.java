package com.botfunnel.tag;

import com.botfunnel.tag.dto.CreateTagRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// Bean-validation unit test on the Decision 11 slug regex ^[a-z0-9_-]{1,32}$ as applied to
// CreateTagRequest.slug. No Spring context — drives the Jakarta Validator directly.
class TagSlugValidatorTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void accepts_validSlugs() {
        List<String> valid = List.of("vip", "course_buyer", "paid-2024", "a", "a".repeat(32));
        for (String slug : valid) {
            assertThat(slugViolations(slug))
                    .as("slug '%s' must be accepted", slug)
                    .isEmpty();
        }
    }

    @Test
    void rejects_invalidSlugs() {
        List<String> invalid = new ArrayList<>(List.of(
                "VIP",          // uppercase
                "vi p",         // space
                "vip.2024",     // dot
                "",             // empty
                "a".repeat(33)  // 33 chars (> 32)
        ));
        invalid.add(null);      // null

        for (String slug : invalid) {
            assertThat(slugViolations(slug))
                    .as("slug '%s' must be rejected", slug)
                    .isNotEmpty();
        }
    }

    private static List<ConstraintViolation<CreateTagRequest>> slugViolations(String slug) {
        Set<ConstraintViolation<CreateTagRequest>> all = validator.validate(new CreateTagRequest(slug, "label"));
        return all.stream()
                .filter(v -> "slug".equals(v.getPropertyPath().toString()))
                .toList();
    }
}
