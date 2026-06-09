package com.botfunnel.funnel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.lang.NonNull;

/**
 * Tolerant Spring Data read converter for {@link StepType} (15-message-composer / MAJ-1 fail-safe).
 *
 * <p>Spring Data's default enum reader does {@code Enum.valueOf} and throws {@code ConversionFailedException}
 * on an unknown name. That throw happens during {@code mongoTemplate.find(...)} in
 * {@link FunnelExecutionEngine#sweep()} — OUTSIDE the per-execution {@code try/catch} — so a single legacy
 * document carrying a now-removed {@code stepType} value (e.g. {@code "SEND_MESSAGE"} / {@code "SEND_IMAGE"} /
 * {@code "MENU"} that survived the manual wipe, Decision 4) would abort the ENTIRE sweep tick, stalling the
 * whole engine.
 *
 * <p>This converter maps any unrecognised value to the {@link StepType#UNKNOWN} sentinel instead of throwing.
 * Deserialisation then succeeds, the row loads, and the engine's per-execution guard isolates the bad
 * document: {@link StepExecutor#execute} terminal-fails an {@code UNKNOWN} step (it never re-sends and never
 * loops), and every other valid execution in the same batch still processes.
 *
 * <p>It does NOT re-introduce the removed constants — the sentinel is distinct and the engine/validator both
 * reject it. Author input never reaches this converter, but NOT because of Jackson: {@code UNKNOWN} is a valid
 * enum constant, so a request with {@code stepType="UNKNOWN"} deserialises fine and passes {@code @NotNull}.
 * The real author-input gate is {@link FunnelService#validateSteps} (case {@code UNKNOWN -> throw}), which
 * rejects it with a 422 on every create/update/activate/test-run path before persistence. This converter only
 * runs on the Mongo read path (legacy documents that bypass validateSteps).
 */
@ReadingConverter
public class StepTypeReadConverter implements Converter<String, StepType> {

    private static final Logger log = LoggerFactory.getLogger(StepTypeReadConverter.class);

    static final String LOG_UNKNOWN_STEP_TYPE = "FUNNEL_UNKNOWN_STEP_TYPE_TOLERATED";

    @Override
    public StepType convert(@NonNull String source) {
        try {
            return StepType.valueOf(source);
        } catch (IllegalArgumentException ex) {
            // Legacy / removed value — degrade to the sentinel so the read does not crash the sweep tick.
            // Log the raw value: a step-kind discriminator is not PII (no rendered text/caption/field value).
            log.warn("{} value={}", LOG_UNKNOWN_STEP_TYPE, source);
            return StepType.UNKNOWN;
        }
    }
}
