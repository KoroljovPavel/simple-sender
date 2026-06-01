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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    private TelegramSender sender;
    private SubscriberService subscriberService;
    private SubscriberCustomFieldsService customFieldsService;
    private ProjectRepository projectRepository;
    private StepExecutor executor;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        sender = mock(TelegramSender.class);
        subscriberService = mock(SubscriberService.class);
        customFieldsService = mock(SubscriberCustomFieldsService.class);
        projectRepository = mock(ProjectRepository.class);
        executor = new StepExecutor(sender, subscriberService, customFieldsService, projectRepository);

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
                .when(customFieldsService).setOne(eq(PROJECT_ID), eq(SUBSCRIBER_ID), eq(CustomFieldType.NUMBER), eq("age"), any());
        FunnelStep step = setCustomFieldStep("age", "not-a-number");

        StepExecutor.StepResult result = executor.execute(step, execution(0), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.FAIL);
        assertThat(result.reasonCode()).isEqualTo("custom_field_type_mismatch");
    }

    @Test
    void setCustomFieldDeletedDefinitionSkips() {
        // Project carries NO definition for the key (deleted) → silent skip, execution continues.
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(projectWithField("other", CustomFieldType.STRING)));
        FunnelStep step = setCustomFieldStep("age", 30.0);

        StepExecutor.StepResult result = executor.execute(step, execution(0), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        verify(customFieldsService, never()).setOne(any(), any(), any(), any(), any());
        assertThat(logAppender.list).anyMatch(e -> e.getFormattedMessage().contains(StepExecutor.LOG_CUSTOM_FIELD_SKIPPED));
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
        verify(subscriberService).addTag(PROJECT_ID, SUBSCRIBER_ID, "vip");
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

    private static FunnelStep setCustomFieldStep(String key, Object value) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.SET_CUSTOM_FIELD);
        s.setCustomFieldKey(key);
        s.setCustomFieldValue(value);
        return s;
    }

    private static FunnelExecution execution(int stepIndex) {
        FunnelExecution e = new FunnelExecution();
        e.setId("exec-1");
        e.setProjectId(PROJECT_ID);
        e.setFunnelId("funnel-1");
        e.setSubscriberId(SUBSCRIBER_ID);
        e.setTelegramBotId(TELEGRAM_BOT_ID);
        e.setStatus(ExecutionStatus.running);
        e.setCurrentStepIndex(stepIndex);
        e.setStepRunStatus(StepRunStatus.in_progress);
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
