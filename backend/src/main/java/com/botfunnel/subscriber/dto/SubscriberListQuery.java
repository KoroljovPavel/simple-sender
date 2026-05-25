package com.botfunnel.subscriber.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;
import java.util.List;

/**
 * List query params, bound via {@code @Valid @ModelAttribute} on {@code GET /subscribers}.
 *
 * <p>{@code search} caps at 120 chars (security F7) and the controller additionally rejects inputs
 * shorter than 2 chars with an explicit 400 (text-index whole-word match needs a meaningful token).
 * {@code tagsInclude}/{@code tagsExclude} are bound as plain string lists — the slugs become exact
 * MATCH VALUES inside Mongo {@code $all}/{@code $nin} (never field names or operators), so they carry
 * no injection surface; per-element {@code @Pattern} is intentionally omitted to keep {@code
 * @ModelAttribute} list binding reliable. {@code cursor} is opaque base64url decoded + type-validated
 * server-side (Decision 5, security F6). {@code limit} defaults to 50 and is clamped to 200.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubscriberListQuery(
        @Size(min = 2, max = 120) String search,
        String status,
        List<String> tagsInclude,
        List<String> tagsExclude,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant subscribedFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant subscribedTo,
        String sort,
        @Size(max = 256) String cursor,
        @Positive @Max(200) Integer limit) {
}
