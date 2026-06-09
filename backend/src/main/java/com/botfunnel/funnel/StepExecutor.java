package com.botfunnel.funnel;

import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotTokenInvalidException;
import com.botfunnel.bot.TelegramSendException;
import com.botfunnel.bot.TelegramSender;
import com.botfunnel.bot.dto.AlbumItem;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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

    // Telegram media-group element type for an ALBUM block. The Task 1 model carries no per-item media
    // type (MediaItem is just url+caption, ContentBlock.type is the single ALBUM discriminator), so the
    // executor cannot distinguish photo vs video per item — an ALBUM sends as a homogeneous photo group.
    // Per-type album mixing (photo+video) is deferred until the model carries an element-type hint.
    private static final String ALBUM_ELEMENT_TYPE = "photo";

    // callback_data wire contract with Task 5 (Decision 6): EXACTLY "{executionId}:{buttonIndex}" — the
    // ObjectId-hex execution id + a single ':' separator + the 0-based button index. Task 5's parser is
    // strict (one ':', ObjectId-hex shape, bounded index); any deviation here silently drops real taps.
    static final String CALLBACK_DATA_SEPARATOR = ":";

    // Dynamic-value sentinel for a SET_CUSTOM_FIELD step on a DATE field: instead of a fixed ISO date the
    // author can store this token, and the engine substitutes the execution-time instant. Resolved ONLY
    // for DATE fields (the editor offers "current date" only there); any other type treats it literally.
    // The frontend mirrors this literal as CURRENT_DATE_TOKEN in types/funnel.ts.
    public static final String CURRENT_DATE_TOKEN = "@now";

    private final TelegramSender sender;
    private final SubscriberService subscriberService;
    private final SubscriberCustomFieldsService customFieldsService;
    private final ProjectRepository projectRepository;
    private final FunnelEventService funnelEventService;
    private final Clock clock;

    public StepExecutor(TelegramSender sender,
                        SubscriberService subscriberService,
                        SubscriberCustomFieldsService customFieldsService,
                        ProjectRepository projectRepository,
                        FunnelEventService funnelEventService,
                        Clock clock) {
        this.sender = sender;
        this.subscriberService = subscriberService;
        this.customFieldsService = customFieldsService;
        this.projectRepository = projectRepository;
        this.funnelEventService = funnelEventService;
        this.clock = clock;
    }

    /**
     * Runs one step against the (already status-gated) subscriber on the pinned CONNECTED bot. Never
     * mutates execution state — returns a {@link StepResult} for the runner to apply.
     */
    public StepResult execute(FunnelStep step, FunnelExecution execution, Subscriber subscriber, Bot bot) {
        return switch (step.getStepType()) {
            case MESSAGE -> message(step, execution, subscriber, bot);
            case DELAY -> StepResult.delay(delayDuration(step));
            case ADD_TAG -> {
                // Funnel-step-originated write → child enroll depth (parent + 1) so a tag_added trigger
                // it fires is counted as auto (depth > 0) toward the volume rate-limit + depth cap.
                subscriberService.addTag(execution.getProjectId(), execution.getSubscriberId(),
                        step.getTagSlug(), execution.getEnrollDepth() + 1);
                yield StepResult.cont();
            }
            case REMOVE_TAG -> {
                // REMOVE_TAG is not a trigger (only tag_added fires) — signature unchanged, no depth.
                subscriberService.removeTag(execution.getProjectId(), execution.getSubscriberId(), step.getTagSlug());
                yield StepResult.cont();
            }
            case SET_CUSTOM_FIELD -> setCustomField(step, execution, subscriber);
            case EMIT_EVENT -> emitEvent(step, execution);
            case SUBSCRIBE_TO_FUNNEL -> subscribeToFunnel(step, execution);
        };
    }

    // SUBSCRIBE_TO_FUNNEL (Phase 5 / composition): enroll the SAME subscriber into step.targetFunnelId as a
    // fresh execution (fire-and-forget — the next sweep picks it up), then either end the parent (COMPLETE
    // when endParentAfter) or let it continue in parallel (CONTINUE). The enroll runs at child depth
    // (parent.enrollDepth + 1) and INHERITS the parent's telegramBotId (Decision 9 — no fresh bot lookup).
    // The enroll is a side-effect BEFORE the engine's claim-conditional advance/complete (like EMIT_EVENT),
    // so a crash-replay re-enrolls — harmless, the re-enter guard makes the duplicate a no-op (at-most-once
    // effective). enrollSpecificFunnel is error-isolated and owns the depth-cap + rate-limit backstops + the
    // fail-closed projectId resolve — no second try/catch here (it would mask a genuine engine fault). No
    // FunnelExecutionFactory/Engine injection: the enroll rides the existing StepExecutor → FunnelEventService
    // edge (Decision 2, anti-bean-cycle).
    private StepResult subscribeToFunnel(FunnelStep step, FunnelExecution execution) {
        funnelEventService.enrollSpecificFunnel(execution.getProjectId(), execution.getSubscriberId(),
                step.getTargetFunnelId(), step.getTargetEntryStepId(), execution.getEnrollDepth() + 1,
                execution.getTelegramBotId());
        return step.isEndParentAfter() ? StepResult.complete() : StepResult.cont();
    }

    // EMIT_EVENT (Phase 3 / Decision 4): synchronously dispatch the step's event (the shared `event`
    // namespace, keyed by eventName) for the current subscriber at child enroll depth (parent + 1), then
    // CONTINUE so the parent funnel advances. The dispatcher is error-isolated and owns the three loop
    // backstops (Task 4) — no second try/catch here (it would swallow a genuine engine fault
    // differently); a stray blank eventName degrades to a no-op fan-out, never a thrown step. Logs stay
    // id/code-only (Decision 16) — the eventName is the matchKey, never logged here.
    private StepResult emitEvent(FunnelStep step, FunnelExecution execution) {
        funnelEventService.dispatchForSubscriber(execution.getProjectId(), execution.getSubscriberId(),
                FunnelEventService.TRIGGER_EVENT, step.getEventName(), execution.getEnrollDepth() + 1);
        return StepResult.cont();
    }

    // MESSAGE composer (15-message-composer / Decision 1+2+3): send the step's ordered List<ContentBlock>
    // as N separate Telegram messages, one per block, under a SINGLE engine claim (per-node at-most-once —
    // Decision 3: a crash mid-block leaves the step in_progress with the already-sent blocks lost-forward,
    // never re-sent). Each TEXT text and each media caption (incl. the FIRST album item's caption) is
    // rendered through VariableTemplateRenderer with the BLOCK's own parseMode (not a step-level mode), then
    // trimmed+WARNed over the Telegram hard limit (4096 text / 1024 caption); over-length is NOT a step
    // failure. The inline keyboard + timeout park-on-reply (MENU parity) attach to the LAST non-album block
    // only (Decision 2 — buttons live on the step, never on a block, and an album never carries a keyboard);
    // with buttons → WAIT_FOR_REPLY(deadline), without → CONTINUE. The whole loop is wrapped in ONE
    // try/catch (NOT per-block) so the failure mapping and lost-forward semantics match the former flat
    // sendMessage/sendImage/menu branches exactly: TelegramSendException → fromTerminalReason (cancel/fail),
    // BotTokenInvalidException → fail("invalid_bot_token"), AppException → fail(codeOrStatus).
    private StepResult message(FunnelStep step, FunnelExecution execution, Subscriber subscriber, Bot bot) {
        List<ContentBlock> blocks = step.getBlocks();
        boolean hasButtons = step.getButtons() != null && !step.getButtons().isEmpty();
        // Index of the last NON-ALBUM block — the only block that may carry the inline keyboard (Decision 2).
        // Computed once via a reverse scan; -1 means every block is an album (then no keyboard attaches).
        int lastNonAlbumIndex = lastNonAlbumIndex(blocks);
        try {
            for (int i = 0; i < blocks.size(); i++) {
                ContentBlock block = blocks.get(i);
                // Only the last non-album block carries the keyboard, and only when the step has buttons.
                Object replyMarkup = (hasButtons && i == lastNonAlbumIndex)
                        ? buildReplyMarkup(step, execution)
                        : null;
                sendBlock(block, execution, subscriber, bot, replyMarkup);
            }
            // All blocks sent. Park-on-reply only when the step has buttons (they were attached to the last
            // non-album block above); otherwise advance. menuDeadline yields null for an untimed park.
            return hasButtons ? StepResult.waitForReply(menuDeadline(step)) : StepResult.cont();
        } catch (TelegramSendException ex) {
            return fromTerminalReason(ex);
        } catch (BotTokenInvalidException ex) {
            return StepResult.fail("invalid_bot_token");
        } catch (AppException ex) {
            // e.g. bot disconnected between the engine's pin-check and a send (404 bot-not-found).
            return StepResult.fail(codeOrStatus(ex));
        }
    }

    // Dispatch one block to its Telegram send method. replyMarkup is non-null only for the keyboard-bearing
    // block (the caller enforces "last non-album block only"). The per-block switch is exhaustive over
    // BlockType (no default). Media value (mediaUrl / item URL) is passed as-is — Telegram fetches it; the
    // backend never dereferences it (no SSRF, Decision 6).
    private void sendBlock(ContentBlock block, FunnelExecution execution, Subscriber subscriber, Bot bot,
                           Object replyMarkup) {
        Long chatId = subscriber.getTelegramChatId();
        switch (block.type()) {
            case TEXT -> {
                String text = renderTrimmed(block.text(), block.parseMode(), subscriber, execution,
                        MAX_MESSAGE_LENGTH, LOG_TEXT_TRIMMED);
                sender.sendText(bot.getId(), chatId, text, block.parseMode(), null, replyMarkup);
            }
            case IMAGE -> sender.sendPhoto(bot.getId(), chatId, block.mediaUrl(),
                    renderCaption(block, subscriber, execution), block.parseMode(), null);
            case VIDEO -> sender.sendVideo(bot.getId(), chatId, block.mediaUrl(),
                    renderCaption(block, subscriber, execution), block.parseMode(), null);
            case AUDIO -> sender.sendAudio(bot.getId(), chatId, block.mediaUrl(),
                    renderCaption(block, subscriber, execution), block.parseMode(), null);
            case FILE -> sender.sendDocument(bot.getId(), chatId, block.mediaUrl(),
                    renderCaption(block, subscriber, execution), block.parseMode(), null);
            case ALBUM -> sender.sendMediaGroup(bot.getId(), chatId, buildAlbum(block, subscriber, execution),
                    null);
        }
    }

    // The single-media caption (IMAGE/VIDEO/AUDIO/FILE): render + trim only when present. A null caption is
    // passed through as null (the sender omits the body key) — never rendered or trimmed.
    private String renderCaption(ContentBlock block, Subscriber subscriber, FunnelExecution execution) {
        if (block.caption() == null) {
            return null;
        }
        return renderTrimmed(block.caption(), block.parseMode(), subscriber, execution,
                MAX_CAPTION_LENGTH, LOG_CAPTION_TRIMMED);
    }

    // Build the AlbumItem list for /sendMediaGroup. The caption is meaningful only on the FIRST element
    // (Decision 5): render+trim that one (when present); later elements carry their stored caption as-is
    // (normally null — save-validation in Task 4 guarantees caption only on the first). The Telegram
    // media-group element type is mapped from the album's per-type discriminator hint via albumElementType.
    private List<AlbumItem> buildAlbum(ContentBlock block, Subscriber subscriber, FunnelExecution execution) {
        List<MediaItem> items = block.items();
        List<AlbumItem> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            MediaItem item = items.get(i);
            String caption = null;
            if (i == 0 && item.caption() != null) {
                caption = renderTrimmed(item.caption(), block.parseMode(), subscriber, execution,
                        MAX_CAPTION_LENGTH, LOG_CAPTION_TRIMMED);
            }
            out.add(new AlbumItem(ALBUM_ELEMENT_TYPE, item.mediaUrl(), caption, block.parseMode()));
        }
        return out;
    }

    // Render a text/caption through the variable renderer with the given parseMode, then trim to the limit
    // with a codes-only WARN (Decision 16 / PII — the rendered payload is NEVER logged). Shared by TEXT
    // (4096 / LOG_TEXT_TRIMMED) and every media caption (1024 / LOG_CAPTION_TRIMMED) so the trim+WARN logic
    // lives in one place. Over-length is NOT a step failure — it trims and continues.
    private String renderTrimmed(String template, String parseMode, Subscriber subscriber,
                                 FunnelExecution execution, int limit, String logCode) {
        String rendered = VariableTemplateRenderer.render(template, parseMode, subscriber);
        if (rendered.length() > limit) {
            log.warn("{} executionId={} stepIndex={} originalLength={} trimmedTo={}", logCode,
                    execution.getId(), execution.getCurrentStepIndex(), rendered.length(), limit);
            rendered = rendered.substring(0, limit);
        }
        return rendered;
    }

    // Reverse scan for the last non-album block index (the keyboard anchor, Decision 2). Returns -1 when the
    // list is empty or every block is an album (then no block carries the keyboard).
    private static int lastNonAlbumIndex(List<ContentBlock> blocks) {
        for (int i = blocks.size() - 1; i >= 0; i--) {
            if (blocks.get(i).type() != BlockType.ALBUM) {
                return i;
            }
        }
        return -1;
    }

    // Build a Telegram inline_keyboard ({"inline_keyboard":[[{text, callback_data|url}]]}) — one button
    // per row, mirroring the editor's vertical layout. callback_data is the Task 5 wire contract
    // "{executionId}:{buttonIndex}" (Decision 6); URL buttons carry a "url" field and NO callback_data.
    // A null/empty button list yields no reply_markup (null) so the send carries no keyboard.
    private static Object buildReplyMarkup(FunnelStep step, FunnelExecution execution) {
        List<Button> buttons = step.getButtons();
        if (buttons == null || buttons.isEmpty()) {
            return null;
        }
        List<Object> rows = new ArrayList<>(buttons.size());
        for (int i = 0; i < buttons.size(); i++) {
            Button button = buttons.get(i);
            Map<String, Object> tgButton = new LinkedHashMap<>();
            tgButton.put("text", button.label());
            if ("url".equals(button.type())) {
                tgButton.put("url", button.url());
            } else {
                // callback (or any non-url type) → callback_data per Decision 6 wire contract.
                tgButton.put("callback_data", execution.getId() + CALLBACK_DATA_SEPARATOR + i);
            }
            rows.add(List.of(tgButton));
        }
        Map<String, Object> markup = new LinkedHashMap<>();
        markup.put("inline_keyboard", rows);
        return markup;
    }

    // Timeout deadline for a MENU park: now + timeoutValue/timeoutUnit, or null when no timeout is set
    // (wait indefinitely). timeoutUnit reuses the existing delayUnit convention {"MIN","HOUR","DAY"}
    // (Decision: timeoutUnit format) for a shared switch with delayDuration.
    private Instant menuDeadline(FunnelStep step) {
        Integer value = step.getTimeoutValue();
        String unit = step.getTimeoutUnit();
        if (value == null || unit == null) {
            return null;
        }
        return Instant.now(clock).plus(durationOf(value, unit));
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
            // Funnel-step-originated write → child enroll depth (parent + 1) so a custom_field_set
            // trigger it fires is counted as auto (depth > 0) toward the volume rate-limit + depth cap.
            subscriberService.recordCustomFieldsSet(execution.getProjectId(), execution.getSubscriberId(),
                    oldValues, newValues, execution.getEnrollDepth() + 1);
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
        return durationOf(step.getDelayValue(), step.getDelayUnit());
    }

    // Shared unit→Duration mapping for both delayUnit and timeoutUnit (Decision: timeoutUnit reuses the
    // delayUnit convention {"MIN","HOUR","DAY"} for consistency).
    private static Duration durationOf(int value, String unit) {
        return switch (unit) {
            case "MIN" -> Duration.ofMinutes(value);
            case "HOUR" -> Duration.ofHours(value);
            case "DAY" -> Duration.ofDays(value);
            default -> throw new IllegalStateException("Unknown time unit: " + unit);
        };
    }

    /**
     * Outcome of one step, telling the runner how to advance the execution. {@link #COMPLETE} (Phase 5 /
     * composition) is a terminal success: the runner marks the execution {@code completed} without
     * executing any further step — used by a {@code SUBSCRIBE_TO_FUNNEL} step with {@code endParentAfter}.
     */
    public enum Outcome { CONTINUE, DELAY, CANCEL, FAIL, WAIT_FOR_REPLY, COMPLETE }

    /**
     * Result of executing one step. {@code delay} is non-null only for {@link Outcome#DELAY};
     * {@code nextRunAt} is the timeout deadline for {@link Outcome#WAIT_FOR_REPLY} (or {@code null} =
     * wait indefinitely); {@code reasonCode} is a non-PII code for terminal outcomes (terminal reason /
     * error code), used only for structured logging. The canonical constructor is private — callers use
     * the static factories so the field defaults stay in one place.
     */
    public record StepResult(Outcome outcome, Duration delay, String reasonCode, Instant nextRunAt) {
        public static StepResult cont() {
            return new StepResult(Outcome.CONTINUE, null, null, null);
        }

        // Terminal success (SUBSCRIBE_TO_FUNNEL with endParentAfter): the runner completes the execution
        // and runs no further steps. Mirror of cont() — all non-outcome fields null.
        public static StepResult complete() {
            return new StepResult(Outcome.COMPLETE, null, null, null);
        }

        public static StepResult delay(Duration delay) {
            return new StepResult(Outcome.DELAY, delay, null, null);
        }

        public static StepResult cancel(String reasonCode) {
            return new StepResult(Outcome.CANCEL, null, reasonCode, null);
        }

        public static StepResult fail(String reasonCode) {
            return new StepResult(Outcome.FAIL, null, reasonCode, null);
        }

        // Park-on-reply (MENU): the runner sets status=waiting_for_reply, leaves currentStepId on the
        // MENU step, and parks with nextRunAt = the timeout deadline, or null to wait indefinitely.
        public static StepResult waitForReply(Instant nextRunAt) {
            return new StepResult(Outcome.WAIT_FOR_REPLY, null, null, nextRunAt);
        }
    }
}
