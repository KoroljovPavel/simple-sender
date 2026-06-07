package com.botfunnel.funnel;

import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotTokenInvalidException;
import com.botfunnel.bot.TelegramSendException;
import com.botfunnel.bot.TelegramSender;
import com.botfunnel.common.AppException;
import com.botfunnel.project.CustomFieldDefinition;
import com.botfunnel.project.CustomFieldType;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberCustomFieldsService;
import com.botfunnel.subscriber.SubscriberService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Per-{@link StepType} executor for the funnel engine (Decision 12: flat dispatch, no polymorphic
 * {@code _class}). Stateless: the step-runner ({@link FunnelExecutionEngine}) owns all
 * {@code FunnelExecution} state mutation; this class only performs the side effect of one step and
 * returns a {@link StepResult} telling the runner how to advance.
 *
 * <p>Send semantics mirror {@link com.botfunnel.bot.TelegramSender}: success returns normally, a
 * terminal failure THROWS {@link TelegramSendException} (carrying a {@link TelegramSendException.TerminalReason})
 * or {@link BotTokenInvalidException}. The blocked/deleted subscriber flip already happens inside the
 * sender — the engine never duplicates it, it only maps the terminal reason to an execution outcome.
 *
 * <p>Observability (Decision 16): logs carry ONLY identifiers / codes — never the rendered text,
 * caption, or custom-field value (PII).
 */
@Component
public class StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(StepExecutor.class);

    // Telegram hard limits: message text 4096, photo caption 1024 (Decision 10).
    static final int MAX_MESSAGE_LENGTH = 4096;
    static final int MAX_CAPTION_LENGTH = 1024;

    // Named log constants — codes only, no rendered payload (Decision 16 / PII).
    static final String LOG_TEXT_TRIMMED = "FUNNEL_STEP_TEXT_TRIMMED";
    static final String LOG_CAPTION_TRIMMED = "FUNNEL_STEP_CAPTION_TRIMMED";
    static final String LOG_CUSTOM_FIELD_SKIPPED = "FUNNEL_STEP_CUSTOM_FIELD_SKIPPED_DELETED_DEFINITION";

    // Dynamic-value sentinel for a SET_CUSTOM_FIELD step on a DATE field: instead of a fixed ISO date the
    // author can store this token, and the engine substitutes the execution-time instant. Resolved ONLY
    // for DATE fields (the editor offers "current date" only there); any other type treats it literally.
    // The frontend mirrors this literal as CURRENT_DATE_TOKEN in types/funnel.ts.
    public static final String CURRENT_DATE_TOKEN = "@now";

    private final TelegramSender sender;
    private final SubscriberService subscriberService;
    private final SubscriberCustomFieldsService customFieldsService;
    private final ProjectRepository projectRepository;
    private final Clock clock;

    public StepExecutor(TelegramSender sender,
                        SubscriberService subscriberService,
                        SubscriberCustomFieldsService customFieldsService,
                        ProjectRepository projectRepository,
                        Clock clock) {
        this.sender = sender;
        this.subscriberService = subscriberService;
        this.customFieldsService = customFieldsService;
        this.projectRepository = projectRepository;
        this.clock = clock;
    }

    /**
     * Runs one step against the (already status-gated) subscriber on the pinned CONNECTED bot. Never
     * mutates execution state — returns a {@link StepResult} for the runner to apply.
     */
    public StepResult execute(FunnelStep step, FunnelExecution execution, Subscriber subscriber, Bot bot) {
        return switch (step.getStepType()) {
            case SEND_MESSAGE -> sendMessage(step, execution, subscriber, bot);
            case SEND_IMAGE -> sendImage(step, execution, subscriber, bot);
            case DELAY -> StepResult.delay(delayDuration(step));
            case ADD_TAG -> {
                subscriberService.addTag(execution.getProjectId(), execution.getSubscriberId(), step.getTagSlug());
                yield StepResult.cont();
            }
            case REMOVE_TAG -> {
                subscriberService.removeTag(execution.getProjectId(), execution.getSubscriberId(), step.getTagSlug());
                yield StepResult.cont();
            }
            case SET_CUSTOM_FIELD -> setCustomField(step, execution, subscriber);
            // MENU is wired in Task 3 (park-on-reply). Until then no MENU step can exist (the type is
            // not yet offered by the editor/validator), so reaching this arm is a programming error.
            case MENU -> throw new UnsupportedOperationException(
                    "MENU step execution is not implemented until Task 3 (Phase 2 engine)");
        };
    }

    private StepResult sendMessage(FunnelStep step, FunnelExecution execution, Subscriber subscriber, Bot bot) {
        String rendered = VariableTemplateRenderer.render(step.getText(), step.getParseMode(), subscriber);
        if (rendered.length() > MAX_MESSAGE_LENGTH) {
            // Trim, WARN, and CONTINUE — over-length after substitution is NOT a step failure.
            log.warn("{} executionId={} stepIndex={} originalLength={} trimmedTo={}", LOG_TEXT_TRIMMED,
                    execution.getId(), execution.getCurrentStepIndex(), rendered.length(), MAX_MESSAGE_LENGTH);
            rendered = rendered.substring(0, MAX_MESSAGE_LENGTH);
        }
        try {
            sender.sendText(bot.getId(), subscriber.getTelegramChatId(), rendered, step.getParseMode(), null);
            return StepResult.cont();
        } catch (TelegramSendException ex) {
            return fromTerminalReason(ex);
        } catch (BotTokenInvalidException ex) {
            return StepResult.fail("invalid_bot_token");
        } catch (AppException ex) {
            // e.g. bot disconnected between the engine's pin-check and the send (404 bot-not-found).
            return StepResult.fail(codeOrStatus(ex));
        }
    }

    private StepResult sendImage(FunnelStep step, FunnelExecution execution, Subscriber subscriber, Bot bot) {
        String caption = step.getCaption();
        if (caption != null) {
            caption = VariableTemplateRenderer.render(caption, step.getParseMode(), subscriber);
            if (caption.length() > MAX_CAPTION_LENGTH) {
                log.warn("{} executionId={} stepIndex={} originalLength={} trimmedTo={}", LOG_CAPTION_TRIMMED,
                        execution.getId(), execution.getCurrentStepIndex(), caption.length(), MAX_CAPTION_LENGTH);
                caption = caption.substring(0, MAX_CAPTION_LENGTH);
            }
        }
        try {
            // Telegram fetches the imageUrl itself — the backend never dereferences it (no SSRF).
            sender.sendPhoto(bot.getId(), subscriber.getTelegramChatId(), step.getImageUrl(),
                    caption, step.getParseMode(), null);
            return StepResult.cont();
        } catch (TelegramSendException ex) {
            return fromTerminalReason(ex);
        } catch (BotTokenInvalidException ex) {
            return StepResult.fail("invalid_bot_token");
        } catch (AppException ex) {
            return StepResult.fail(codeOrStatus(ex));
        }
    }

    private StepResult setCustomField(FunnelStep step, FunnelExecution execution, Subscriber subscriber) {
        CustomFieldType type = resolveFieldType(execution.getProjectId(), step.getCustomFieldKey());
        if (type == null) {
            // Definition was deleted from the project → silently skip this step, execution continues.
            log.info("{} executionId={} stepIndex={}", LOG_CUSTOM_FIELD_SKIPPED,
                    execution.getId(), execution.getCurrentStepIndex());
            return StepResult.cont();
        }
        try {
            // Mirror SubscriberCustomFieldsController's sole-writer cycle (Decision 11): validate→apply
            // through SubscriberCustomFieldsService, then record the audit event via the sole writer.
            // setOne deliberately skips the audit, so the engine — like the controller — owns the
            // recordCustomFieldsSet call. We validate here (not via setOne) because the event must carry
            // the NORMALIZED value (e.g. 30.0, not "30") for recordCustomFieldsSet's old/new diff.
            String key = step.getCustomFieldKey();
            // DATE + "@now" → resolve to the execution-time instant (clock-driven for testability); every
            // other case goes through the per-type validator exactly as before.
            Object normalized = type == CustomFieldType.DATE && CURRENT_DATE_TOKEN.equals(step.getCustomFieldValue())
                    ? Instant.now(clock)
                    : customFieldsService.validateAndNormalize(type, step.getCustomFieldValue());
            Map<String, Object> oldValues = Collections.singletonMap(key, currentValue(subscriber, key));
            Map<String, Object> newValues = Collections.singletonMap(key, normalized);
            customFieldsService.applyAll(execution.getProjectId(), execution.getSubscriberId(), newValues);
            // oldValues is read from the tick-start subscriber snapshot — accurate for the common single
            // SET_CUSTOM_FIELD-per-key case (the controller likewise reads old once). recordCustomFieldsSet
            // is a no-op when nothing actually changed (empty changedKeys).
            subscriberService.recordCustomFieldsSet(execution.getProjectId(), execution.getSubscriberId(),
                    oldValues, newValues);
            return StepResult.cont();
        } catch (AppException ex) {
            // 422 type mismatch (custom_field_type_mismatch) → execution failed.
            return StepResult.fail(codeOrStatus(ex));
        }
    }

    private static Object currentValue(Subscriber subscriber, String key) {
        Map<String, Object> fields = subscriber.getCustomFields();
        return fields == null ? null : fields.get(key);
    }

    private CustomFieldType resolveFieldType(String projectId, String key) {
        if (key == null) {
            return null;
        }
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            return null;
        }
        List<CustomFieldDefinition> defs = project.getCustomFieldDefinitions();
        if (defs == null) {
            return null;
        }
        return defs.stream()
                .filter(d -> key.equals(d.name()))
                .map(CustomFieldDefinition::type)
                .findFirst()
                .orElse(null);
    }

    // BLOCKED_BY_USER / CHAT_NOT_FOUND → cancelled (sender already flipped the subscriber, no dup);
    // OTHER (5xx-exhausted, other 4xx, bad image URL) → failed. No funnel-level retries.
    private static StepResult fromTerminalReason(TelegramSendException ex) {
        TelegramSendException.TerminalReason reason = ex.getTerminalReason();
        if (reason == TelegramSendException.TerminalReason.BLOCKED_BY_USER
                || reason == TelegramSendException.TerminalReason.CHAT_NOT_FOUND) {
            return StepResult.cancel(reason.name());
        }
        return StepResult.fail(reason.name());
    }

    private static String codeOrStatus(AppException ex) {
        return ex.getCode() != null ? ex.getCode() : String.valueOf(ex.getStatus().value());
    }

    private static Duration delayDuration(FunnelStep step) {
        int value = step.getDelayValue();
        return switch (step.getDelayUnit()) {
            case "MIN" -> Duration.ofMinutes(value);
            case "HOUR" -> Duration.ofHours(value);
            case "DAY" -> Duration.ofDays(value);
            default -> throw new IllegalStateException("Unknown delayUnit: " + step.getDelayUnit());
        };
    }

    /** Outcome of one step, telling the runner how to advance the execution. */
    public enum Outcome { CONTINUE, DELAY, CANCEL, FAIL }

    /**
     * Result of executing one step. {@code delay} is non-null only for {@link Outcome#DELAY};
     * {@code reasonCode} is a non-PII code for terminal outcomes (terminal reason / error code),
     * used only for structured logging.
     */
    public record StepResult(Outcome outcome, Duration delay, String reasonCode) {
        public static StepResult cont() {
            return new StepResult(Outcome.CONTINUE, null, null);
        }

        public static StepResult delay(Duration delay) {
            return new StepResult(Outcome.DELAY, delay, null);
        }

        public static StepResult cancel(String reasonCode) {
            return new StepResult(Outcome.CANCEL, null, reasonCode);
        }

        public static StepResult fail(String reasonCode) {
            return new StepResult(Outcome.FAIL, null, reasonCode);
        }
    }
}
