package com.botfunnel.funnel.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure DTO contract test for the multi-entry trigger array (Task 3 / 17-funnel-multi-entry). Two
 * concerns only — Jackson (de)serialization round-trip of {@code List<TriggerDto>} and the
 * bean-validation surface of {@code UpdateFunnelRequest} (Decision 14 @Size ceiling + @Valid cascade).
 * No Spring context: a plain {@link ObjectMapper} (the project round-trip idiom) and a programmatic
 * {@link Validator}. Service-level composition validation (duplicate event_name, entryStepId resolve,
 * onStartTriggerValue sync) is Task 4 and deliberately out of scope here.
 */
class TriggerDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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
    void roundTripsTriggerArray() throws Exception {
        List<TriggerDto> triggers = List.of(
                new TriggerDto("on_start", "", null, null),
                new TriggerDto("event", "purchase", null, "step-7"),
                new TriggerDto("keyword", "", List.of("hello", "hi"), "step-9"));
        UpdateFunnelRequest request = new UpdateFunnelRequest(
                "My funnel", "desc", false, triggers, List.of());

        String json = objectMapper.writeValueAsString(request);
        UpdateFunnelRequest back = objectMapper.readValue(json, UpdateFunnelRequest.class);

        assertThat(back.triggers()).hasSize(3);
        assertThat(back.triggers().get(0).triggerType()).isEqualTo("on_start");
        assertThat(back.triggers().get(0).triggerValue()).isEqualTo("");
        assertThat(back.triggers().get(0).entryStepId()).isNull();
        assertThat(back.triggers().get(1).triggerType()).isEqualTo("event");
        assertThat(back.triggers().get(1).triggerValue()).isEqualTo("purchase");
        assertThat(back.triggers().get(1).entryStepId()).isEqualTo("step-7");
        assertThat(back.triggers().get(2).keywords()).containsExactly("hello", "hi");
        assertThat(back.triggers().get(2).entryStepId()).isEqualTo("step-9");
    }

    @Test
    void deserializeIgnoresUnknownProperties() throws Exception {
        // A hostile body smuggles a `status` field (mass-assignment attempt) and an unknown field inside
        // a TriggerDto element. @JsonIgnoreProperties(ignoreUnknown=true) must drop both without error.
        String json = """
                {
                  "status": "active",
                  "name": "x",
                  "triggers": [
                    {"triggerType": "on_start", "triggerValue": "", "bogus": 1}
                  ],
                  "steps": []
                }
                """;

        UpdateFunnelRequest back = objectMapper.readValue(json, UpdateFunnelRequest.class);

        assertThat(back.name()).isEqualTo("x");
        assertThat(back.triggers()).hasSize(1);
        assertThat(back.triggers().get(0).triggerType()).isEqualTo("on_start");
        // `status` is not a field of UpdateFunnelRequest — it cannot leak in (record has no such accessor),
        // and the unknown `bogus` field on the trigger did not break deserialization.
    }

    @Test
    void funnelResponseRoundTrip() throws Exception {
        List<TriggerDto> triggers = List.of(
                new TriggerDto("on_start", "promo", null, null),
                new TriggerDto("event", "signup", null, "step-2"));

        FunnelResponse response = new FunnelResponse(
                "f1", "p1", "Name", "Desc", null, false, triggers, List.of(),
                "t.me/bot?start=promo", null, null);
        FunnelResponse backResponse =
                objectMapper.readValue(objectMapper.writeValueAsString(response), FunnelResponse.class);
        assertThat(backResponse.triggers()).hasSize(2);
        assertThat(backResponse.triggers().get(1).entryStepId()).isEqualTo("step-2");
        assertThat(backResponse.deepLink()).isEqualTo("t.me/bot?start=promo");

        FunnelSummaryResponse summary = new FunnelSummaryResponse(
                "f1", "p1", "Name", "Desc", null, false, triggers, 0, null, null);
        FunnelSummaryResponse backSummary = objectMapper.readValue(
                objectMapper.writeValueAsString(summary), FunnelSummaryResponse.class);
        assertThat(backSummary.triggers()).hasSize(2);
        assertThat(backSummary.triggers().get(0).triggerValue()).isEqualTo("promo");
    }

    @Test
    void sizeCapViolationFlaggedByValidator() {
        // One trigger over the @Size ceiling → a constraint violation on the `triggers` field.
        List<TriggerDto> overCap = IntStream.range(0, UpdateFunnelRequest.MAX_TRIGGERS + 1)
                .mapToObj(i -> new TriggerDto("event", "e" + i, null, null))
                .collect(Collectors.toList());
        UpdateFunnelRequest request = new UpdateFunnelRequest(
                "n", null, false, overCap, List.of());

        Set<ConstraintViolation<UpdateFunnelRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("triggers");
    }

    @Test
    void sizeCapBoundaryAccepted() {
        // Exactly MAX_TRIGGERS is allowed (off-by-one guard on the ceiling).
        List<TriggerDto> atCap = IntStream.range(0, UpdateFunnelRequest.MAX_TRIGGERS)
                .mapToObj(i -> new TriggerDto("event", "e" + i, null, null))
                .collect(Collectors.toList());
        UpdateFunnelRequest request = new UpdateFunnelRequest(
                "n", null, false, atCap, List.of());

        Set<ConstraintViolation<UpdateFunnelRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .doesNotContain("triggers");
    }

    @Test
    void validCascadesIntoTriggerDto() {
        // @Valid on `triggers` cascades bean-validation into each element: an over-cap keyword list on a
        // TriggerDto surfaces as a nested violation (triggers[i].keywords), proving the cascade is wired.
        List<String> tooManyKeywords = IntStream.range(0, TriggerDto.MAX_KEYWORDS + 1)
                .mapToObj(i -> "k" + i)
                .collect(Collectors.toList());
        List<TriggerDto> triggers = new ArrayList<>();
        triggers.add(new TriggerDto("keyword", "", tooManyKeywords, null));
        UpdateFunnelRequest request = new UpdateFunnelRequest(
                "n", null, false, triggers, List.of());

        Set<ConstraintViolation<UpdateFunnelRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.startsWith("triggers[0].keywords"));
    }
}
