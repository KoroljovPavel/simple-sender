package com.botfunnel.funnel;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.bot.TelegramSendException;
import com.botfunnel.bot.TelegramSender;
import com.botfunnel.bot.dto.SentMessage;
import com.botfunnel.common.AppException;
import com.botfunnel.project.CustomFieldDefinition;
import com.botfunnel.project.CustomFieldType;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberCustomFieldsService;
import com.botfunnel.subscriber.SubscriberService;
import com.botfunnel.subscriber.SubscriberStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Unit tests for StepExecutor — per-type side effects + the renderer-integration trim/escape rules.
// All collaborators are mocked (no Spring, no DB); the renderer is exercised for real (static util).
class FunnelStepExecutorTest {

    private static final String BOT_ID = "bot-1";
    private static final Long TELEGRAM_BOT_ID = 555L;
    private static final Long CHAT_ID = 99L;
    private static final String PROJECT_ID = "proj-1";
    private static final String SUBSCRIBER_ID = "sub-1";

    // Fixed clock so the "@now" DATE-resolution test asserts an exact instant.
    private static final Instant FIXED_NOW = Instant.parse("2026-06-06T10:15:30Z");

    private TelegramSender sender;
    private SubscriberService subscriberService;
    private SubscriberCustomFieldsService customFieldsService;
    private ProjectRepository projectRepository;
    private FunnelEventService funnelEventService;
    private StepExecutor executor;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        sender = mock(TelegramSender.class);
        subscriberService = mock(SubscriberService.class);
        customFieldsService = mock(SubscriberCustomFieldsService.class);
        projectRepository = mock(ProjectRepository.class);
        funnelEventService = mock(FunnelEventService.class);
        executor = new StepExecutor(sender, subscriberService, customFieldsService, projectRepository,
                funnelEventService, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        logger = (Logger) LoggerFactory.getLogger(StepExecutor.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void sendMessageOver4096TrimsAndContinues() {
        when(sender.sendText(anyString(), any(), anyString(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 1L, Instant.now()));
        FunnelStep step = sendMessageStep("a".repeat(5000), null);

        StepExecutor.StepResult result = executor.execute(step, execution(0), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(sender).sendText(eq(BOT_ID), eq(CHAT_ID), textCaptor.capture(), any(), any());
        assertThat(textCaptor.getValue()).hasSize(StepExecutor.MAX_MESSAGE_LENGTH);
        // WARN constant logged, and NO rendered payload in the message (PII, Decision 16).
        assertThat(warnMessages()).anyMatch(m -> m.contains(StepExecutor.LOG_TEXT_TRIMMED));
        assertThat(warnMessages()).noneMatch(m -> m.contains("aaaa"));
    }

    @Test
    void sendImageCaptionEscapedAndTrimmedTo1024() {
        when(sender.sendPhoto(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 1L, Instant.now()));
        // Placeholder up front so the HTML-escaped value survives the 1024 trim and is observable.
        Subscriber sub = activeSubscriber();
        sub.setFirstName("<b>");
        FunnelStep step = sendImageStep("https://example.com/p.png",
                "{user.first_name}" + "a".repeat(2000), "HTML");

        StepExecutor.StepResult result = executor.execute(step, execution(0), sub, connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        ArgumentCaptor<String> captionCaptor = ArgumentCaptor.forClass(String.class);
        verify(sender).sendPhoto(eq(BOT_ID), eq(CHAT_ID), eq("https://example.com/p.png"),
                captionCaptor.capture(), eq("HTML"), any());
        String caption = captionCaptor.getValue();
        assertThat(caption).hasSize(StepExecutor.MAX_CAPTION_LENGTH);
        assertThat(caption).startsWith("&lt;b&gt;"); // substituted value HTML-escaped
    }

    @Test
    void delayComputesDurationFromUnit() {
        assertThat(executor.execute(delayStep(5, "MIN"), execution(0), activeSubscriber(), connectedBot()))
                .extracting(StepExecutor.StepResult::outcome, StepExecutor.StepResult::delay)
                .containsExactly(StepExecutor.Outcome.DELAY, Duration.ofMinutes(5));
        assertThat(executor.execute(delayStep(3, "HOUR"), execution(0), activeSubscriber(), connectedBot()).delay())
                .isEqualTo(Duration.ofHours(3));
        assertThat(executor.execute(delayStep(2, "DAY"), execution(0), activeSubscriber(), connectedBot()).delay())
                .isEqualTo(Duration.ofDays(2));
    }

    @Test
    void setCustomFieldInvalidValueFailsExecution() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(projectWithField("age", CustomFieldType.NUMBER)));
        doThrow(AppException.unprocessableEntity("custom_field_type_mismatch", "expected a finite number"))
                .when(customFieldsService).validateAndNormalize(eq(CustomFieldType.NUMBER), any());
        FunnelStep step = setCustomFieldStep("age", "not-a-number");

        StepExecutor.StepResult result = executor.execute(step, execution(0), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.FAIL);
        assertThat(result.reasonCode()).isEqualTo("custom_field_type_mismatch");
        // Validation failed before any write/audit — neither must happen.
        verify(customFieldsService, never()).applyAll(any(), any(), any());
        verify(subscriberService, never()).recordCustomFieldsSet(any(), any(), any(), any(), anyInt());
    }

    @Test
    void setCustomFieldDeletedDefinitionSkips() {
        // Project carries NO definition for the key (deleted) → silent skip, execution continues.
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(projectWithField("other", CustomFieldType.STRING)));
        FunnelStep step = setCustomFieldStep("age", 30.0);

        StepExecutor.StepResult result = executor.execute(step, execution(0), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        verify(customFieldsService, never()).applyAll(any(), any(), any());
        verify(subscriberService, never()).recordCustomFieldsSet(any(), any(), any(), any(), anyInt());
        assertThat(logAppender.list).anyMatch(e -> e.getFormattedMessage().contains(StepExecutor.LOG_CUSTOM_FIELD_SKIPPED));
    }

    @Test
    void setCustomFieldAppliesAndRecordsAuditEvent() {
        // F1: a funnel SET_CUSTOM_FIELD must (a) apply the NORMALIZED value and (b) record the
        // subscriber_custom_field_set audit via the sole writer (SubscriberService) — symmetric to
        // ADD_TAG/REMOVE_TAG and to the controller's PATCH path.
        when(projectRepository.findById(PROJECT_ID))
                .thenReturn(Optional.of(projectWithField("age", CustomFieldType.NUMBER)));
        when(customFieldsService.validateAndNormalize(eq(CustomFieldType.NUMBER), eq("30")))
                .thenReturn(30.0); // validator normalizes "30" → 30.0
        Subscriber sub = activeSubscriber();
        Map<String, Object> existing = new HashMap<>();
        existing.put("age", 10.0); // prior value → recorded as oldValues
        sub.setCustomFields(existing);

        StepExecutor.StepResult result = executor.execute(setCustomFieldStep("age", "30"), execution(0), sub, connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);

        ArgumentCaptor<Map<String, Object>> applied = mapCaptor();
        verify(customFieldsService).applyAll(eq(PROJECT_ID), eq(SUBSCRIBER_ID), applied.capture());
        assertThat(applied.getValue()).containsExactly(entry("age", 30.0)); // normalized, not "30"

        ArgumentCaptor<Map<String, Object>> oldCap = mapCaptor();
        ArgumentCaptor<Map<String, Object>> newCap = mapCaptor();
        // Funnel-step write → child enroll depth (execution(0).enrollDepth 0 + 1 = 1).
        verify(subscriberService).recordCustomFieldsSet(eq(PROJECT_ID), eq(SUBSCRIBER_ID),
                oldCap.capture(), newCap.capture(), eq(1));
        assertThat(oldCap.getValue()).containsExactly(entry("age", 10.0));
        assertThat(newCap.getValue()).containsExactly(entry("age", 30.0));
    }

    @Test
    void setCustomFieldDateNowTokenResolvesToClockInstant() {
        // "@now" on a DATE field must bypass the per-type validator and apply the execution-time instant
        // (from the fixed clock), so the author can stamp "the moment this block runs".
        when(projectRepository.findById(PROJECT_ID))
                .thenReturn(Optional.of(projectWithField("signed_at", CustomFieldType.DATE)));
        Subscriber sub = activeSubscriber();
        sub.setCustomFields(new HashMap<>());

        StepExecutor.StepResult result = executor.execute(
                setCustomFieldStep("signed_at", StepExecutor.CURRENT_DATE_TOKEN), execution(0), sub, connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        // The token is resolved by the engine — the validator is never asked to parse "@now".
        verify(customFieldsService, never()).validateAndNormalize(eq(CustomFieldType.DATE), eq(StepExecutor.CURRENT_DATE_TOKEN));

        ArgumentCaptor<Map<String, Object>> applied = mapCaptor();
        verify(customFieldsService).applyAll(eq(PROJECT_ID), eq(SUBSCRIBER_ID), applied.capture());
        assertThat(applied.getValue()).containsExactly(entry("signed_at", FIXED_NOW));
    }

    @Test
    void setCustomFieldFirstTimeRecordsNullOldValue() {
        // Edge case from the task: subscriber has no customFields map yet (null) → oldValues carries a
        // null entry (singletonMap allows it; recordCustomFieldsSet is null-safe), newValues the normalized.
        when(projectRepository.findById(PROJECT_ID))
                .thenReturn(Optional.of(projectWithField("age", CustomFieldType.NUMBER)));
        when(customFieldsService.validateAndNormalize(eq(CustomFieldType.NUMBER), eq("30")))
                .thenReturn(30.0);
        Subscriber sub = activeSubscriber(); // customFields == null

        StepExecutor.StepResult result = executor.execute(setCustomFieldStep("age", "30"), execution(0), sub, connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        ArgumentCaptor<Map<String, Object>> oldCap = mapCaptor();
        verify(subscriberService).recordCustomFieldsSet(eq(PROJECT_ID), eq(SUBSCRIBER_ID),
                oldCap.capture(), eq(Collections.singletonMap("age", (Object) 30.0)), eq(1));
        assertThat(oldCap.getValue()).containsExactly(entry("age", null));
    }

    // ─── MENU (Phase 2) ────────────────────────────────────────────────────────

    @Test
    void menu_sends_text_with_reply_markup() {
        // MENU step → 6-arg sendText with a non-null reply_markup, outcome WAIT_FOR_REPLY.
        when(sender.sendText(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 1L, Instant.now()));
        FunnelStep step = menuStep("Pick one", List.of(
                new Button("callback", "Yes", "step-yes", null)), null, null);

        StepExecutor.StepResult result = executor.execute(step, execution(0), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.WAIT_FOR_REPLY);
        ArgumentCaptor<Object> markupCaptor = ArgumentCaptor.forClass(Object.class);
        verify(sender).sendText(eq(BOT_ID), eq(CHAT_ID), eq("Pick one"), any(), eq(null),
                markupCaptor.capture());
        assertThat(markupCaptor.getValue()).isNotNull();
    }

    @Test
    void menu_with_timeout_returns_deadline() {
        when(sender.sendText(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 1L, Instant.now()));
        FunnelStep withTimeout = menuStep("Hurry", List.of(
                new Button("callback", "Go", "step-go", null)), 5, "MIN");

        StepExecutor.StepResult timed = executor.execute(withTimeout, execution(0), activeSubscriber(), connectedBot());

        assertThat(timed.outcome()).isEqualTo(StepExecutor.Outcome.WAIT_FOR_REPLY);
        assertThat(timed.nextRunAt()).isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(5)));

        FunnelStep noTimeout = menuStep("Relax", List.of(
                new Button("callback", "Go", "step-go", null)), null, null);

        StepExecutor.StepResult untimed = executor.execute(noTimeout, execution(0), activeSubscriber(), connectedBot());

        assertThat(untimed.outcome()).isEqualTo(StepExecutor.Outcome.WAIT_FOR_REPLY);
        assertThat(untimed.nextRunAt()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void menu_callbackButtons_encodeCorrectCallbackData() {
        // Build-side of the Task 5 round-trip contract (Decision 6): each callback button carries
        // callback_data = "{executionId}:{index}" (0-based); URL button carries url, NO callback_data.
        when(sender.sendText(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 1L, Instant.now()));
        FunnelStep step = menuStep("Menu", List.of(
                new Button("callback", "First", "s1", null),
                new Button("callback", "Second", "s2", null),
                new Button("url", "Site", null, "https://example.com")), null, null);
        FunnelExecution exec = execution(0); // id == "exec-1"

        executor.execute(step, exec, activeSubscriber(), connectedBot());

        ArgumentCaptor<Object> markupCaptor = ArgumentCaptor.forClass(Object.class);
        verify(sender).sendText(eq(BOT_ID), eq(CHAT_ID), eq("Menu"), any(), eq(null), markupCaptor.capture());
        Map<String, Object> markup = (Map<String, Object>) markupCaptor.getValue();
        List<List<Map<String, Object>>> rows = (List<List<Map<String, Object>>>) markup.get("inline_keyboard");
        assertThat(rows).hasSize(3);

        Map<String, Object> b0 = rows.get(0).get(0);
        assertThat(b0.get("text")).isEqualTo("First");
        assertThat(b0.get("callback_data")).isEqualTo("exec-1:0");
        assertThat(b0).doesNotContainKey("url");

        Map<String, Object> b1 = rows.get(1).get(0);
        assertThat(b1.get("callback_data")).isEqualTo("exec-1:1");

        Map<String, Object> b2 = rows.get(2).get(0);
        assertThat(b2.get("text")).isEqualTo("Site");
        assertThat(b2.get("url")).isEqualTo("https://example.com");
        assertThat(b2).doesNotContainKey("callback_data");
    }

    @Test
    void sendMessageBlockedByUserCancels() {
        when(sender.sendText(anyString(), any(), anyString(), any(), any()))
                .thenThrow(new TelegramSendException(403, "blocked", 1,
                        TelegramSendException.TerminalReason.BLOCKED_BY_USER));

        StepExecutor.StepResult result = executor.execute(
                sendMessageStep("hi", null), execution(0), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CANCEL);
        assertThat(result.reasonCode()).isEqualTo("BLOCKED_BY_USER");
    }

    @Test
    void sendMessageOtherTerminalFails() {
        when(sender.sendText(anyString(), any(), anyString(), any(), any()))
                .thenThrow(new TelegramSendException(null, "transient_failure_exhausted", 4));

        StepExecutor.StepResult result = executor.execute(
                sendMessageStep("hi", null), execution(0), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.FAIL);
        assertThat(result.reasonCode()).isEqualTo("OTHER");
    }

    @Test
    void addTagDelegatesToSubscriberService() {
        StepExecutor.StepResult result = executor.execute(
                tagStep(StepType.ADD_TAG, "vip"), execution(0), activeSubscriber(), connectedBot());
        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        // Funnel-step ADD_TAG → child enroll depth (execution(0).enrollDepth 0 + 1 = 1).
        verify(subscriberService).addTag(PROJECT_ID, SUBSCRIBER_ID, "vip", 1);
    }

    @Test
    void addTagStep_passesParentDepthPlusOne() {
        // The ADD_TAG step forwards execution.enrollDepth + 1 to addTag — here parent depth 2 → 3.
        StepExecutor.StepResult result = executor.execute(
                tagStep(StepType.ADD_TAG, "vip"), execution(0, 2), activeSubscriber(), connectedBot());
        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        verify(subscriberService).addTag(PROJECT_ID, SUBSCRIBER_ID, "vip", 3);
    }

    // ─── EMIT_EVENT (Phase 3 / Decision 4) ───────────────────────────────────

    @Test
    void emitEvent_returnsContinue_dispatchesEventWithChildDepth() {
        // EMIT_EVENT dispatches event=step.eventName for the current subscriber at child depth
        // (parent enrollDepth 0 + 1 = 1) and returns CONTINUE so the parent funnel advances.
        StepExecutor.StepResult result = executor.execute(
                emitEventStep("welcome_done"), execution(0), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        verify(funnelEventService).dispatchForSubscriber(PROJECT_ID, SUBSCRIBER_ID,
                FunnelEventService.TRIGGER_EVENT, "welcome_done", 1);
    }

    @Test
    void emitEvent_carriesParentDepthPlusOne() {
        // A nested EMIT_EVENT inside a depth-2 execution dispatches at depth 3.
        StepExecutor.StepResult result = executor.execute(
                emitEventStep("ping"), execution(0, 2), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        verify(funnelEventService).dispatchForSubscriber(PROJECT_ID, SUBSCRIBER_ID,
                FunnelEventService.TRIGGER_EVENT, "ping", 3);
    }

    @Test
    void removeTagDelegatesToSubscriberService() {
        StepExecutor.StepResult result = executor.execute(
                tagStep(StepType.REMOVE_TAG, "vip"), execution(0), activeSubscriber(), connectedBot());
        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        verify(subscriberService).removeTag(PROJECT_ID, SUBSCRIBER_ID, "vip");
    }

    @Test
    void sendMessageInvalidBotTokenFails() {
        when(sender.sendText(anyString(), any(), anyString(), any(), any()))
                .thenThrow(new com.botfunnel.bot.BotTokenInvalidException(BOT_ID, "Token is invalid or revoked"));

        StepExecutor.StepResult result = executor.execute(
                sendMessageStep("hi", null), execution(0), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.FAIL);
        assertThat(result.reasonCode()).isEqualTo("invalid_bot_token");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    private List<String> warnMessages() {
        return logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static FunnelStep sendMessageStep(String text, String parseMode) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.SEND_MESSAGE);
        s.setText(text);
        s.setParseMode(parseMode);
        return s;
    }

    private static FunnelStep sendImageStep(String url, String caption, String parseMode) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.SEND_IMAGE);
        s.setImageUrl(url);
        s.setCaption(caption);
        s.setParseMode(parseMode);
        return s;
    }

    private static FunnelStep delayStep(int value, String unit) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.DELAY);
        s.setDelayValue(value);
        s.setDelayUnit(unit);
        return s;
    }

    private static FunnelStep tagStep(StepType type, String slug) {
        FunnelStep s = new FunnelStep();
        s.setStepType(type);
        s.setTagSlug(slug);
        return s;
    }

    private static FunnelStep menuStep(String text, List<Button> buttons, Integer timeoutValue, String timeoutUnit) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.MENU);
        s.setText(text);
        s.setButtons(buttons);
        s.setTimeoutValue(timeoutValue);
        s.setTimeoutUnit(timeoutUnit);
        return s;
    }

    private static FunnelStep setCustomFieldStep(String key, Object value) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.SET_CUSTOM_FIELD);
        s.setCustomFieldKey(key);
        s.setCustomFieldValue(value);
        return s;
    }

    private static FunnelStep emitEventStep(String eventName) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.EMIT_EVENT);
        s.setEventName(eventName);
        return s;
    }

    private static FunnelExecution execution(int stepIndex) {
        return execution(stepIndex, 0);
    }

    private static FunnelExecution execution(int stepIndex, int enrollDepth) {
        FunnelExecution e = new FunnelExecution();
        e.setId("exec-1");
        e.setProjectId(PROJECT_ID);
        e.setFunnelId("funnel-1");
        e.setSubscriberId(SUBSCRIBER_ID);
        e.setTelegramBotId(TELEGRAM_BOT_ID);
        e.setStatus(ExecutionStatus.running);
        e.setCurrentStepIndex(stepIndex);
        e.setStepRunStatus(StepRunStatus.in_progress);
        e.setEnrollDepth(enrollDepth);
        return e;
    }

    private static Subscriber activeSubscriber() {
        Subscriber s = new Subscriber();
        s.setId(SUBSCRIBER_ID);
        s.setProjectId(PROJECT_ID);
        s.setTelegramChatId(CHAT_ID);
        s.setTelegramBotId(TELEGRAM_BOT_ID);
        s.setStatus(SubscriberStatus.ACTIVE);
        return s;
    }

    private static Bot connectedBot() {
        Bot b = new Bot();
        b.setId(BOT_ID);
        b.setProjectId(PROJECT_ID);
        b.setTelegramBotId(TELEGRAM_BOT_ID);
        b.setStatus(BotStatus.CONNECTED);
        return b;
    }

    private static Project projectWithField(String name, CustomFieldType type) {
        Project p = new Project();
        p.setId(PROJECT_ID);
        p.setCustomFieldDefinitions(List.of(
                new CustomFieldDefinition(name, name, type, null, Instant.now())));
        return p;
    }
}
