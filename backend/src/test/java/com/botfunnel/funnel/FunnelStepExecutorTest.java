package com.botfunnel.funnel;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.bot.TelegramSendException;
import com.botfunnel.bot.TelegramSender;
import com.botfunnel.bot.dto.AlbumItem;
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
import org.mockito.InOrder;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    // ─── MESSAGE composer (15-message-composer) ────────────────────────────────

    @Test
    void message_sendsBlocksInOrder() {
        // A composer step with [text, image, file] sends exactly three messages in block order.
        when(sender.sendText(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 1L, Instant.now()));
        when(sender.sendPhoto(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 2L, Instant.now()));
        when(sender.sendDocument(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 3L, Instant.now()));
        FunnelStep step = messageStep(
                textBlock("hi", null),
                imageBlock("https://example.com/p.png", null, null),
                fileBlock("https://example.com/d.pdf", null, null));

        StepExecutor.StepResult result = executor.execute(step, execution(0), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        InOrder inOrder = inOrder(sender);
        inOrder.verify(sender).sendText(eq(BOT_ID), eq(CHAT_ID), eq("hi"), any(), eq(null), eq(null));
        inOrder.verify(sender).sendPhoto(eq(BOT_ID), eq(CHAT_ID), eq("https://example.com/p.png"),
                eq(null), any(), any());
        inOrder.verify(sender).sendDocument(eq(BOT_ID), eq(CHAT_ID), eq("https://example.com/d.pdf"),
                eq(null), any(), any());
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    void message_substitutesVariablesInTextAndAllCaptions() {
        // {user.first_name} is substituted (and HTML-escaped) in the TEXT text AND in every media caption
        // type — image/video/audio/file. Each caption is captured and verified.
        when(sender.sendText(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 1L, Instant.now()));
        when(sender.sendPhoto(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 2L, Instant.now()));
        when(sender.sendVideo(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 3L, Instant.now()));
        when(sender.sendAudio(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 4L, Instant.now()));
        when(sender.sendDocument(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 5L, Instant.now()));
        Subscriber sub = activeSubscriber();
        sub.setFirstName("<b>");
        FunnelStep step = messageStep(
                textBlock("hi {user.first_name}", "HTML"),
                imageBlock("u1", "img {user.first_name}", "HTML"),
                videoBlock("u2", "vid {user.first_name}", "HTML"),
                audioBlock("u3", "aud {user.first_name}", "HTML"),
                fileBlock("u4", "file {user.first_name}", "HTML"));

        StepExecutor.StepResult result = executor.execute(step, execution(0), sub, connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        verify(sender).sendText(eq(BOT_ID), eq(CHAT_ID), eq("hi &lt;b&gt;"), eq("HTML"), eq(null), eq(null));
        verify(sender).sendPhoto(eq(BOT_ID), eq(CHAT_ID), eq("u1"), eq("img &lt;b&gt;"), eq("HTML"), any());
        verify(sender).sendVideo(eq(BOT_ID), eq(CHAT_ID), eq("u2"), eq("vid &lt;b&gt;"), eq("HTML"), any());
        verify(sender).sendAudio(eq(BOT_ID), eq(CHAT_ID), eq("u3"), eq("aud &lt;b&gt;"), eq("HTML"), any());
        verify(sender).sendDocument(eq(BOT_ID), eq(CHAT_ID), eq("u4"), eq("file &lt;b&gt;"), eq("HTML"), any());
    }

    @Test
    void message_trimsTextOver4096AndWarns() {
        // A TEXT block >4096 is trimmed to MAX_MESSAGE_LENGTH, LOG_TEXT_TRIMMED is logged, the step does
        // NOT fail (CONTINUE), and the WARN carries no rendered payload (PII, Decision 16).
        when(sender.sendText(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 1L, Instant.now()));
        FunnelStep step = messageStep(textBlock("a".repeat(5000), null));

        StepExecutor.StepResult result = executor.execute(step, execution(0), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(sender).sendText(eq(BOT_ID), eq(CHAT_ID), textCaptor.capture(), any(), eq(null), eq(null));
        assertThat(textCaptor.getValue()).hasSize(StepExecutor.MAX_MESSAGE_LENGTH);
        assertThat(warnMessages()).anyMatch(m -> m.contains(StepExecutor.LOG_TEXT_TRIMMED));
        assertThat(warnMessages()).noneMatch(m -> m.contains("aaaa"));
    }

    @Test
    void message_trimsCaptionOver1024PerType() {
        // The caption of EACH media type (image/video/audio/file) >1024 is trimmed to MAX_CAPTION_LENGTH
        // with LOG_CAPTION_TRIMMED; the substituted value is HTML-escaped and survives the trim.
        when(sender.sendPhoto(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 1L, Instant.now()));
        when(sender.sendVideo(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 2L, Instant.now()));
        when(sender.sendAudio(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 3L, Instant.now()));
        when(sender.sendDocument(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 4L, Instant.now()));
        Subscriber sub = activeSubscriber();
        sub.setFirstName("<b>");
        String longCaption = "{user.first_name}" + "a".repeat(2000);
        FunnelStep step = messageStep(
                imageBlock("u1", longCaption, "HTML"),
                videoBlock("u2", longCaption, "HTML"),
                audioBlock("u3", longCaption, "HTML"),
                fileBlock("u4", longCaption, "HTML"));

        StepExecutor.StepResult result = executor.execute(step, execution(0), sub, connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        for (String url : List.of("u1", "u2", "u3", "u4")) {
            ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
            switch (url) {
                case "u1" -> verify(sender).sendPhoto(eq(BOT_ID), eq(CHAT_ID), eq(url), cap.capture(), any(), any());
                case "u2" -> verify(sender).sendVideo(eq(BOT_ID), eq(CHAT_ID), eq(url), cap.capture(), any(), any());
                case "u3" -> verify(sender).sendAudio(eq(BOT_ID), eq(CHAT_ID), eq(url), cap.capture(), any(), any());
                default -> verify(sender).sendDocument(eq(BOT_ID), eq(CHAT_ID), eq(url), cap.capture(), any(), any());
            }
            assertThat(cap.getValue()).hasSize(StepExecutor.MAX_CAPTION_LENGTH);
            assertThat(cap.getValue()).startsWith("&lt;b&gt;");
        }
        assertThat(warnMessages()).anyMatch(m -> m.contains(StepExecutor.LOG_CAPTION_TRIMMED));
        assertThat(warnMessages()).noneMatch(m -> m.contains("aaaa"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void message_albumCaptionOnlyOnFirstItem() {
        // An ALBUM block is sent via sendMediaGroup; only the FIRST MediaItem carries a (rendered) caption,
        // the rest carry none; the first caption substitutes variables.
        when(sender.sendMediaGroup(anyString(), any(), any(), any()))
                .thenReturn(List.of(new SentMessage(CHAT_ID, 1L, Instant.now())));
        Subscriber sub = activeSubscriber();
        sub.setFirstName("Ann");
        FunnelStep step = messageStep(albumBlock("HTML",
                new MediaItem(BlockType.IMAGE, "a1", "first {user.first_name}"),
                new MediaItem(BlockType.IMAGE, "a2", "ignored-second"),
                new MediaItem(BlockType.IMAGE, "a3", null)));

        StepExecutor.StepResult result = executor.execute(step, execution(0), sub, connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        ArgumentCaptor<List<AlbumItem>> itemsCap = ArgumentCaptor.forClass(List.class);
        verify(sender).sendMediaGroup(eq(BOT_ID), eq(CHAT_ID), itemsCap.capture(), eq(null));
        List<AlbumItem> items = itemsCap.getValue();
        assertThat(items).hasSize(3);
        assertThat(items.get(0).caption()).isEqualTo("first Ann"); // rendered on the first item
        assertThat(items.get(1).caption()).isNull();
        assertThat(items.get(2).caption()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void message_albumElementTypeDerivesFromItemKind() {
        // Decision 5: each album element's Telegram media-group type derives from the item's own media kind
        // (IMAGE→photo, VIDEO→video, AUDIO→audio, FILE→document) — not a hardcoded homogeneous "photo" group.
        when(sender.sendMediaGroup(anyString(), any(), any(), any()))
                .thenReturn(List.of(new SentMessage(CHAT_ID, 1L, Instant.now())));
        FunnelStep step = messageStep(albumBlock(null,
                new MediaItem(BlockType.IMAGE, "a1", null),
                new MediaItem(BlockType.VIDEO, "a2", null)));

        StepExecutor.StepResult result = executor.execute(step, execution(0), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        ArgumentCaptor<List<AlbumItem>> itemsCap = ArgumentCaptor.forClass(List.class);
        verify(sender).sendMediaGroup(eq(BOT_ID), eq(CHAT_ID), itemsCap.capture(), eq(null));
        List<AlbumItem> items = itemsCap.getValue();
        assertThat(items).extracting(AlbumItem::type).containsExactly("photo", "video");
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

    // ─── MESSAGE composer — keyboard on last non-album block (Decision 2) ───────

    @Test
    @SuppressWarnings("unchecked")
    void message_keyboardOnlyOnLastNonAlbumBlock_waitForReply() {
        // Step [text, album, text] with non-empty buttons: the keyboard attaches ONLY to the send of the
        // LAST text block (not the album, not the first text); result WAIT_FOR_REPLY with deadline now+timeout.
        when(sender.sendText(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 1L, Instant.now()));
        when(sender.sendMediaGroup(anyString(), any(), any(), any()))
                .thenReturn(List.of(new SentMessage(CHAT_ID, 2L, Instant.now())));
        FunnelStep step = messageStep(
                List.of(new Button("callback", "Yes", "step-yes", null)), 5, "MIN",
                textBlock("first", null),
                albumBlock(null, new MediaItem(BlockType.IMAGE, "a1", null),
                        new MediaItem(BlockType.IMAGE, "a2", null)),
                textBlock("last", null));

        StepExecutor.StepResult result = executor.execute(step, execution(0), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.WAIT_FOR_REPLY);
        assertThat(result.nextRunAt()).isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(5)));
        // First text → no keyboard.
        verify(sender).sendText(eq(BOT_ID), eq(CHAT_ID), eq("first"), any(), eq(null), eq(null));
        // Last text → keyboard.
        ArgumentCaptor<Object> markupCaptor = ArgumentCaptor.forClass(Object.class);
        verify(sender).sendText(eq(BOT_ID), eq(CHAT_ID), eq("last"), any(), eq(null), markupCaptor.capture());
        assertThat(markupCaptor.getValue()).isNotNull();
        // Sanity: the markup encodes the Task 5 callback-data contract for the step's buttons.
        Map<String, Object> markup = (Map<String, Object>) markupCaptor.getValue();
        List<List<Map<String, Object>>> rows = (List<List<Map<String, Object>>>) markup.get("inline_keyboard");
        assertThat(rows.get(0).get(0).get("callback_data")).isEqualTo("exec-1:0");
    }

    @Test
    void message_noButtons_returnsContinue() {
        // No buttons → no send carries reply_markup, result CONTINUE.
        when(sender.sendText(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 1L, Instant.now()));
        FunnelStep step = messageStep(textBlock("plain", null));

        StepExecutor.StepResult result = executor.execute(step, execution(0), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        verify(sender).sendText(eq(BOT_ID), eq(CHAT_ID), eq("plain"), any(), eq(null), eq(null));
    }

    @Test
    void message_lastBlockIsAlbum_keyboardNotAttached() {
        // Step [text, album] with non-empty buttons. Negative: the album send (sendMediaGroup) takes no
        // reply_markup (an album never carries a keyboard). Positive: the preceding last NON-album text
        // block DOES get the keyboard, and the branch returns WAIT_FOR_REPLY parked on that text block.
        when(sender.sendText(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 1L, Instant.now()));
        when(sender.sendMediaGroup(anyString(), any(), any(), any()))
                .thenReturn(List.of(new SentMessage(CHAT_ID, 2L, Instant.now())));
        FunnelStep step = messageStep(
                List.of(new Button("callback", "Yes", "s", null)), 3, "MIN",
                textBlock("only-text", null),
                albumBlock(null, new MediaItem(BlockType.IMAGE, "a1", null),
                        new MediaItem(BlockType.IMAGE, "a2", null)));

        StepExecutor.StepResult result = executor.execute(step, execution(0), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.WAIT_FOR_REPLY);
        assertThat(result.nextRunAt()).isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(3)));
        // Album never carries a keyboard — sendMediaGroup's 4-arg signature has no reply_markup slot.
        verify(sender).sendMediaGroup(eq(BOT_ID), eq(CHAT_ID), any(), eq(null));
        // The keyboard attached to the preceding text block (park anchored there, not on the album).
        ArgumentCaptor<Object> markupCaptor = ArgumentCaptor.forClass(Object.class);
        verify(sender).sendText(eq(BOT_ID), eq(CHAT_ID), eq("only-text"), any(), eq(null), markupCaptor.capture());
        assertThat(markupCaptor.getValue()).isNotNull();
    }

    @Test
    void message_blockKThrowsTelegramSend_returnsCancelOrFail_stepNotAdvanced() {
        // Block K's send throws TelegramSendException(BLOCKED_BY_USER) → CANCEL with reasonCode; blocks
        // 1..K-1 already sent; step NOT advanced. A different terminal reason → FAIL.
        when(sender.sendText(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 1L, Instant.now()));
        // doThrow (not when().thenThrow) so re-stubbing the throwing method later doesn't re-invoke it.
        doThrow(new TelegramSendException(403, "blocked", 1,
                TelegramSendException.TerminalReason.BLOCKED_BY_USER))
                .when(sender).sendPhoto(anyString(), any(), anyString(), any(), any(), any());
        FunnelStep step = messageStep(
                textBlock("ok-1", null),
                imageBlock("boom", null, null),
                textBlock("never-reached", null));

        StepExecutor.StepResult cancel = executor.execute(step, execution(0), activeSubscriber(), connectedBot());

        assertThat(cancel.outcome()).isEqualTo(StepExecutor.Outcome.CANCEL);
        assertThat(cancel.reasonCode()).isEqualTo("BLOCKED_BY_USER");
        // Block 1 (text) sent; block 3 never reached (send threw on block 2).
        verify(sender, times(1)).sendText(eq(BOT_ID), eq(CHAT_ID), eq("ok-1"), any(), eq(null), eq(null));
        verify(sender, never()).sendText(eq(BOT_ID), eq(CHAT_ID), eq("never-reached"), any(), any(), any());

        // Other terminal reason → FAIL.
        doThrow(new TelegramSendException(null, "transient_failure_exhausted", 4))
                .when(sender).sendPhoto(anyString(), any(), anyString(), any(), any(), any());
        StepExecutor.StepResult fail = executor.execute(
                messageStep(imageBlock("boom2", null, null)), execution(0), activeSubscriber(), connectedBot());
        assertThat(fail.outcome()).isEqualTo(StepExecutor.Outcome.FAIL);
        assertThat(fail.reasonCode()).isEqualTo("OTHER");
    }

    @Test
    void message_invalidBotToken_fails() {
        // BotTokenInvalidException mid-blocks → FAIL with reasonCode "invalid_bot_token".
        when(sender.sendText(anyString(), any(), anyString(), any(), any(), any()))
                .thenThrow(new com.botfunnel.bot.BotTokenInvalidException(BOT_ID, "Token is invalid or revoked"));

        StepExecutor.StepResult result = executor.execute(
                messageStep(textBlock("hi", null)), execution(0), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.FAIL);
        assertThat(result.reasonCode()).isEqualTo("invalid_bot_token");
    }

    @Test
    void message_appException_fails() {
        // AppException (e.g. 404 bot-not-found) → FAIL with codeOrStatus.
        when(sender.sendText(anyString(), any(), anyString(), any(), any(), any()))
                .thenThrow(AppException.notFound("Bot not found"));

        StepExecutor.StepResult result = executor.execute(
                messageStep(textBlock("hi", null)), execution(0), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.FAIL);
        assertThat(result.reasonCode()).isEqualTo("404"); // notFound has null code → HTTP status string
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

    // ─── SUBSCRIBE_TO_FUNNEL (Phase 5 / composition) ─────────────────────────

    @Test
    void subscribeStepEndParentFalseContinuesAndEnrolls() {
        // endParentAfter=false → CONTINUE (parent runs on in parallel); the enroll is dispatched at child
        // depth (parent enrollDepth 0 + 1 = 1) and inherits the parent execution's telegramBotId.
        StepExecutor.StepResult result = executor.execute(
                subscribeStep("target-f", "entry-s", false), execution(0), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        verify(funnelEventService).enrollSpecificFunnel(PROJECT_ID, SUBSCRIBER_ID,
                "target-f", "entry-s", 1, TELEGRAM_BOT_ID);
    }

    @Test
    void subscribeStepEndParentTrueCompletesAndEnrolls() {
        // endParentAfter=true → COMPLETE (parent ends after the enroll); same enroll call (child depth +
        // inherited bot). Here the parent sits at depth 2 → child enroll depth 3.
        StepExecutor.StepResult result = executor.execute(
                subscribeStep("target-f", null, true), execution(0, 2), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.COMPLETE);
        verify(funnelEventService).enrollSpecificFunnel(PROJECT_ID, SUBSCRIBER_ID,
                "target-f", null, 3, TELEGRAM_BOT_ID);
    }

    @Test
    void removeTagDelegatesToSubscriberService() {
        StepExecutor.StepResult result = executor.execute(
                tagStep(StepType.REMOVE_TAG, "vip"), execution(0), activeSubscriber(), connectedBot());
        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        verify(subscriberService).removeTag(PROJECT_ID, SUBSCRIBER_ID, "vip");
    }

    // ─── SET_KEYBOARD / CLEAR_KEYBOARD (16-persistent-keyboard / Task 3) ──────

    @Test
    @SuppressWarnings("unchecked")
    void setKeyboard_sendsTextWithReplyKeyboardMarkup_returnsContinue() {
        // SET_KEYBOARD sends one text message carrying a ReplyKeyboardMarkup: rows of {"text": label}
        // objects in author order, is_persistent/one_time_keyboard from the step, resize_keyboard hardcoded
        // true; outcome CONTINUE (never WAIT_FOR_REPLY — fire-and-forget).
        when(sender.sendText(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 1L, Instant.now()));
        FunnelStep step = setKeyboardStep("Меню", null, true, false,
                row("A", "B"), row("C"));

        StepExecutor.StepResult result = executor.execute(step, execution(0), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        ArgumentCaptor<Object> markupCaptor = ArgumentCaptor.forClass(Object.class);
        verify(sender).sendText(eq(BOT_ID), eq(CHAT_ID), eq("Меню"), eq(null), eq(null),
                markupCaptor.capture());
        Map<String, Object> markup = (Map<String, Object>) markupCaptor.getValue();
        List<List<Map<String, Object>>> rows = (List<List<Map<String, Object>>>) markup.get("keyboard");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).extracting(b -> b.get("text")).containsExactly("A", "B");
        assertThat(rows.get(1)).extracting(b -> b.get("text")).containsExactly("C");
        // Buttons are object form {"text": ...}, never bare strings.
        assertThat(rows.get(0).get(0)).containsExactlyInAnyOrderEntriesOf(Map.of("text", "A"));
        assertThat(markup.get("is_persistent")).isEqualTo(true);
        assertThat(markup.get("resize_keyboard")).isEqualTo(true);
        assertThat(markup.get("one_time_keyboard")).isEqualTo(false);
        assertThat(markup).doesNotContainKey("remove_keyboard");
    }

    @Test
    @SuppressWarnings("unchecked")
    void setKeyboard_rendersVariablesAndParseMode() {
        // keyboardText with {user.first_name} is rendered (substituted + HTML-escaped per parse mode) and
        // the step's keyboardParseMode is passed through to sendText.
        when(sender.sendText(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 1L, Instant.now()));
        Subscriber sub = activeSubscriber();
        sub.setFirstName("<b>");
        FunnelStep step = setKeyboardStep("hi {user.first_name}", "HTML", true, false, row("A"));

        StepExecutor.StepResult result = executor.execute(step, execution(0), sub, connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        verify(sender).sendText(eq(BOT_ID), eq(CHAT_ID), eq("hi &lt;b&gt;"), eq("HTML"), eq(null), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void setKeyboard_persistentAndOneTimeNullDefaults() {
        // Null isPersistent/oneTimeKeyboard on the snapshot emit the documented form defaults
        // (is_persistent:true, one_time_keyboard:false) — never a JSON null.
        when(sender.sendText(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 1L, Instant.now()));
        FunnelStep step = setKeyboardStep("x", null, null, null, row("A"));

        executor.execute(step, execution(0), activeSubscriber(), connectedBot());

        ArgumentCaptor<Object> markupCaptor = ArgumentCaptor.forClass(Object.class);
        verify(sender).sendText(eq(BOT_ID), eq(CHAT_ID), eq("x"), eq(null), eq(null), markupCaptor.capture());
        Map<String, Object> markup = (Map<String, Object>) markupCaptor.getValue();
        assertThat(markup.get("is_persistent")).isEqualTo(true);
        assertThat(markup.get("one_time_keyboard")).isEqualTo(false);
        assertThat(markup.values()).doesNotContainNull();
    }

    @Test
    void setKeyboard_nullOrEmptyRows_failsTerminally() {
        // Defensive guard (mirrors empty_message_blocks): a malformed SET_KEYBOARD snapshot with null or
        // empty keyboardRows terminal-fails with the non-PII reason code, and never sends.
        FunnelStep nullRows = setKeyboardStep("text", null, true, false);
        nullRows.setKeyboardRows(null);
        StepExecutor.StepResult r1 = executor.execute(nullRows, execution(0), activeSubscriber(), connectedBot());
        assertThat(r1.outcome()).isEqualTo(StepExecutor.Outcome.FAIL);
        assertThat(r1.reasonCode()).isEqualTo("empty_keyboard_rows");

        FunnelStep emptyRows = setKeyboardStep("text", null, true, false);
        emptyRows.setKeyboardRows(List.of());
        StepExecutor.StepResult r2 = executor.execute(emptyRows, execution(0), activeSubscriber(), connectedBot());
        assertThat(r2.outcome()).isEqualTo(StepExecutor.Outcome.FAIL);
        assertThat(r2.reasonCode()).isEqualTo("empty_keyboard_rows");

        verify(sender, never()).sendText(anyString(), any(), anyString(), any(), any(), any());
    }

    @Test
    void setKeyboard_rowWithNoButtons_failsTerminally() {
        // Defensive guard third branch (isEmptyRow): a non-empty rows list containing a row whose buttons
        // list is empty/null terminal-fails with empty_keyboard_rows and never sends — stops a malformed
        // snapshot from emitting an empty wire row [[]].
        FunnelStep emptyButtonsRow = setKeyboardStep("text", null, true, false, row("A"), row());
        StepExecutor.StepResult r = executor.execute(
                emptyButtonsRow, execution(0), activeSubscriber(), connectedBot());
        assertThat(r.outcome()).isEqualTo(StepExecutor.Outcome.FAIL);
        assertThat(r.reasonCode()).isEqualTo("empty_keyboard_rows");
        verify(sender, never()).sendText(anyString(), any(), anyString(), any(), any(), any());
    }

    @Test
    void setKeyboard_trimsTextOver4096AndWarns_pii() {
        // keyboardText over 4096 after rendering is trimmed to MAX_MESSAGE_LENGTH with the codes-only
        // LOG_TEXT_TRIMMED WARN — NOT a failure (CONTINUE), and the WARN carries no rendered payload (PII).
        when(sender.sendText(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 1L, Instant.now()));
        FunnelStep step = setKeyboardStep("a".repeat(5000), null, true, false, row("A"));

        StepExecutor.StepResult result = executor.execute(step, execution(0), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(sender).sendText(eq(BOT_ID), eq(CHAT_ID), textCaptor.capture(), any(), eq(null), any());
        assertThat(textCaptor.getValue()).hasSize(StepExecutor.MAX_MESSAGE_LENGTH);
        assertThat(warnMessages()).anyMatch(m -> m.contains(StepExecutor.LOG_TEXT_TRIMMED));
        assertThat(warnMessages()).noneMatch(m -> m.contains("aaaa"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void clearKeyboard_sendsTextWithRemoveKeyboard_returnsContinue() {
        // CLEAR_KEYBOARD sends one text message with markup exactly {"remove_keyboard": true}; CONTINUE.
        when(sender.sendText(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new SentMessage(CHAT_ID, 1L, Instant.now()));
        FunnelStep step = clearKeyboardStep("Меню сховано", null);

        StepExecutor.StepResult result = executor.execute(step, execution(0), activeSubscriber(), connectedBot());

        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.CONTINUE);
        ArgumentCaptor<Object> markupCaptor = ArgumentCaptor.forClass(Object.class);
        verify(sender).sendText(eq(BOT_ID), eq(CHAT_ID), eq("Меню сховано"), eq(null), eq(null),
                markupCaptor.capture());
        Map<String, Object> markup = (Map<String, Object>) markupCaptor.getValue();
        assertThat(markup).containsExactlyInAnyOrderEntriesOf(Map.of("remove_keyboard", true));
    }

    @Test
    void setKeyboard_blockedByUser_cancels() {
        // TelegramSendException(BLOCKED_BY_USER) → CANCEL; CHAT_NOT_FOUND → CANCEL (same mapping as every
        // send step).
        doThrow(new TelegramSendException(403, "blocked", 1,
                TelegramSendException.TerminalReason.BLOCKED_BY_USER))
                .when(sender).sendText(anyString(), any(), anyString(), any(), any(), any());
        StepExecutor.StepResult blocked = executor.execute(
                setKeyboardStep("t", null, true, false, row("A")), execution(0), activeSubscriber(), connectedBot());
        assertThat(blocked.outcome()).isEqualTo(StepExecutor.Outcome.CANCEL);
        assertThat(blocked.reasonCode()).isEqualTo("BLOCKED_BY_USER");

        doThrow(new TelegramSendException(400, "chat not found", 1,
                TelegramSendException.TerminalReason.CHAT_NOT_FOUND))
                .when(sender).sendText(anyString(), any(), anyString(), any(), any(), any());
        StepExecutor.StepResult notFound = executor.execute(
                setKeyboardStep("t", null, true, false, row("A")), execution(0), activeSubscriber(), connectedBot());
        assertThat(notFound.outcome()).isEqualTo(StepExecutor.Outcome.CANCEL);
        assertThat(notFound.reasonCode()).isEqualTo("CHAT_NOT_FOUND");
    }

    @Test
    void setKeyboard_otherTelegramError_fails() {
        // Other terminal reason (OTHER) → FAIL with the reason name.
        doThrow(new TelegramSendException(null, "transient_failure_exhausted", 4))
                .when(sender).sendText(anyString(), any(), anyString(), any(), any(), any());
        StepExecutor.StepResult result = executor.execute(
                setKeyboardStep("t", null, true, false, row("A")), execution(0), activeSubscriber(), connectedBot());
        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.FAIL);
        assertThat(result.reasonCode()).isEqualTo("OTHER");
    }

    @Test
    void setKeyboard_invalidBotToken_fails() {
        // BotTokenInvalidException → FAIL with reasonCode "invalid_bot_token".
        when(sender.sendText(anyString(), any(), anyString(), any(), any(), any()))
                .thenThrow(new com.botfunnel.bot.BotTokenInvalidException(BOT_ID, "Token is invalid or revoked"));
        StepExecutor.StepResult result = executor.execute(
                setKeyboardStep("t", null, true, false, row("A")), execution(0), activeSubscriber(), connectedBot());
        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.FAIL);
        assertThat(result.reasonCode()).isEqualTo("invalid_bot_token");
    }

    @Test
    void clearKeyboard_appException_fails() {
        // AppException (e.g. 404 bot-not-found) → FAIL with codeOrStatus — covers the trio for CLEAR too.
        when(sender.sendText(anyString(), any(), anyString(), any(), any(), any()))
                .thenThrow(AppException.notFound("Bot not found"));
        StepExecutor.StepResult result = executor.execute(
                clearKeyboardStep("bye", null), execution(0), activeSubscriber(), connectedBot());
        assertThat(result.outcome()).isEqualTo(StepExecutor.Outcome.FAIL);
        assertThat(result.reasonCode()).isEqualTo("404");
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

    // ─── composer (MESSAGE) block-builders ──────────────────────────────────────

    private static ContentBlock textBlock(String text, String parseMode) {
        return new ContentBlock(BlockType.TEXT, text, parseMode, null, null, null);
    }

    private static ContentBlock imageBlock(String url, String caption, String parseMode) {
        return new ContentBlock(BlockType.IMAGE, null, parseMode, url, caption, null);
    }

    private static ContentBlock videoBlock(String url, String caption, String parseMode) {
        return new ContentBlock(BlockType.VIDEO, null, parseMode, url, caption, null);
    }

    private static ContentBlock audioBlock(String url, String caption, String parseMode) {
        return new ContentBlock(BlockType.AUDIO, null, parseMode, url, caption, null);
    }

    private static ContentBlock fileBlock(String url, String caption, String parseMode) {
        return new ContentBlock(BlockType.FILE, null, parseMode, url, caption, null);
    }

    private static ContentBlock albumBlock(String parseMode, MediaItem... items) {
        return new ContentBlock(BlockType.ALBUM, null, parseMode, null, null, List.of(items));
    }

    // A MESSAGE composer step with the given blocks and no keyboard/timeout.
    private static FunnelStep messageStep(ContentBlock... blocks) {
        return messageStep(null, null, null, blocks);
    }

    // A MESSAGE composer step with step-level buttons + optional timeout (the executor attaches them to the
    // last non-album block, Decision 2).
    private static FunnelStep messageStep(List<Button> buttons, Integer timeoutValue, String timeoutUnit,
                                          ContentBlock... blocks) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.MESSAGE);
        s.setBlocks(List.of(blocks));
        s.setButtons(buttons);
        s.setTimeoutValue(timeoutValue);
        s.setTimeoutUnit(timeoutUnit);
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

    private static FunnelStep emitEventStep(String eventName) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.EMIT_EVENT);
        s.setEventName(eventName);
        return s;
    }

    // A SET_KEYBOARD step: mandatory text + parse mode + the is_persistent/one_time_keyboard flags + rows.
    private static FunnelStep setKeyboardStep(String text, String parseMode, Boolean isPersistent,
                                              Boolean oneTimeKeyboard, KeyboardRow... rows) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.SET_KEYBOARD);
        s.setKeyboardText(text);
        s.setKeyboardParseMode(parseMode);
        s.setIsPersistent(isPersistent);
        s.setOneTimeKeyboard(oneTimeKeyboard);
        s.setKeyboardRows(List.of(rows));
        return s;
    }

    // A CLEAR_KEYBOARD step: mandatory text + parse mode only (no rows / flags).
    private static FunnelStep clearKeyboardStep(String text, String parseMode) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.CLEAR_KEYBOARD);
        s.setKeyboardText(text);
        s.setKeyboardParseMode(parseMode);
        return s;
    }

    private static KeyboardRow row(String... labels) {
        List<KeyboardButton> buttons = new java.util.ArrayList<>(labels.length);
        for (String label : labels) {
            buttons.add(new KeyboardButton(label));
        }
        return new KeyboardRow(buttons);
    }

    private static FunnelStep subscribeStep(String targetFunnelId, String targetEntryStepId,
                                            boolean endParentAfter) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.SUBSCRIBE_TO_FUNNEL);
        s.setTargetFunnelId(targetFunnelId);
        s.setTargetEntryStepId(targetEntryStepId);
        s.setEndParentAfter(endParentAfter);
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
