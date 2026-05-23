package com.botfunnel.webhook;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.events.Event;
import com.botfunnel.events.EventRepository;
import com.botfunnel.funnel.FunnelTriggerService;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.subscriber.SubscriberService;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// Direct-invocation worker test (mirror of HardDeleteJobTest style) — calls handle(rawUpdateId)
// directly instead of routing through JobRunr's scheduler. @MockitoSpyBean wraps the real
// no-op stubs so call-counts can be asserted while the real void methods return without effect.
class ProcessTelegramUpdateJobTest extends AbstractIntegrationTest {

    private static final String OWNER_ID = "owner-user-id";
    private static final Long TELEGRAM_BOT_ID = 1234567890L;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\d{1,20}:[A-Za-z0-9_-]{30,50}");

    @Autowired RawUpdateRepository rawUpdateRepository;
    @Autowired BotRepository botRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired EventRepository eventRepository;
    @Autowired MeterRegistry meterRegistry;
    @Autowired ProcessTelegramUpdateJob job;

    @MockitoSpyBean SubscriberService subscriberService;
    @MockitoSpyBean FunnelTriggerService funnelTriggerService;

    private String projectId;
    private String botId;
    private ListAppender<ILoggingEvent> jobAppender;
    private Logger jobLogger;

    @BeforeEach
    void attachLogAppender() {
        jobLogger = (Logger) LoggerFactory.getLogger(ProcessTelegramUpdateJob.class);
        jobAppender = new ListAppender<>();
        jobAppender.start();
        jobLogger.addAppender(jobAppender);
    }

    @AfterEach
    void detachLogAppender() {
        if (jobLogger != null && jobAppender != null) {
            jobLogger.detachAppender(jobAppender);
            jobAppender.stop();
        }
    }

    @BeforeEach
    void cleanAndSeed() {
        eventRepository.deleteAll().block();
        rawUpdateRepository.deleteAll().block();
        botRepository.deleteAll().block();
        projectRepository.deleteAll().block();
        Mockito.reset(subscriberService, funnelTriggerService);

        Project p = new Project();
        p.setOwnerId(OWNER_ID);
        p.setName("Test Project");
        p.setTimezone("UTC");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        projectId = projectRepository.save(p).block().getId();

        Bot bot = new Bot();
        bot.setProjectId(projectId);
        bot.setTelegramBotId(TELEGRAM_BOT_ID);
        bot.setStatus(BotStatus.CONNECTED);
        bot.setConnectedAt(Instant.now());
        botId = botRepository.save(bot).block().getId();
    }

    // ─── re-entry guard ────────────────────────────────────────────────────────

    @Test
    void reentryGuard_doneStatus_noOp() {
        // Seeding DONE proves the worker short-circuits before any side effect even when a perfectly
        // valid /start payload is present — JobRunr retry overlap simulation.
        RawUpdate raw = seedRawUpdate(RawUpdateStatus.DONE,
                privateStartPayload(100L, 100L, "/start ref_X"), 1L);

        long beforeSuccess = successCount();
        long beforeFailure = failureCount();

        job.handle(raw.getId());

        assertThat(eventRepository.count().block()).isZero();
        verify(subscriberService, never())
                .upsertFromTelegramUpdate(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(funnelTriggerService, never()).fire(any(), any(), any(), any());
        assertThat(successCount()).isEqualTo(beforeSuccess);
        assertThat(failureCount()).isEqualTo(beforeFailure);
        RawUpdate reloaded = rawUpdateRepository.findById(raw.getId()).block();
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getProcessingStatus())
                .as("DONE row must stay DONE under re-entry — no status mutation")
                .isEqualTo(RawUpdateStatus.DONE);
        assertThat(reloaded.getProcessingError())
                .as("DONE row's processingError must not be mutated under re-entry")
                .isNull();
    }

    // ─── AC6 — ownerChatId atomic populate ─────────────────────────────────────

    @Test
    void ownerChatIdPopulate_firstStart_atomicWrite() {
        RawUpdate raw = seedRawUpdate(RawUpdateStatus.PENDING,
                privateStartPayload(555L, 555L, "/start"), 1L);
        long beforeSuccess = successCount();

        job.handle(raw.getId());

        Bot reloaded = botRepository.findById(botId).block();
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getOwnerChatId()).isEqualTo(555L);
        // AC16 audit T14 F7 — happy-path worker success ticks telegram_worker_outcome_total{outcome=success}.
        assertThat(successCount())
                .as("happy-path worker must tick success counter")
                .isEqualTo(beforeSuccess + 1L);
    }

    @Test
    void ownerChatIdPopulate_secondStartDifferentChat_doesNotOverwrite() {
        // AC6 second-half: atomic predicate (ownerChatId=null) MUST fail on the second start,
        // so the first chatId stays pinned even after subsequent /start from a different chat.
        RawUpdate first = seedRawUpdate(RawUpdateStatus.PENDING,
                privateStartPayload(100L, 100L, "/start"), 1L);
        job.handle(first.getId());

        RawUpdate second = seedRawUpdate(RawUpdateStatus.PENDING,
                privateStartPayload(200L, 200L, "/start"), 2L);
        job.handle(second.getId());

        Bot reloaded = botRepository.findById(botId).block();
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getOwnerChatId())
                .as("ownerChatId must remain pinned to the first /start chatId")
                .isEqualTo(100L);
    }

    // ─── AC7 — /start payload variants ─────────────────────────────────────────

    @Test
    void startPrivateWithPayload_callsSubscriberAndFunnel_writesEvent() {
        RawUpdate raw = seedRawUpdate(RawUpdateStatus.PENDING,
                privateStartPayload(100L, 100L, "/start ref_X"), 1L);

        job.handle(raw.getId());

        Event event = onlyEvent();
        assertThat(event.getEventType()).isEqualTo("telegram_command_start");
        assertThat(event.getUserId()).isEqualTo(OWNER_ID);
        assertThat(event.getMetadata()).containsEntry("startPayload", "ref_X");
        assertThat(event.getMetadata()).containsEntry("chatId", 100L);
        assertThat(event.getMetadata()).containsEntry("projectId", projectId);
        assertThat(event.getMetadata()).containsEntry("chatType", "private");
        verify(subscriberService, times(1)).upsertFromTelegramUpdate(
                eq(projectId), eq(TELEGRAM_BOT_ID), eq(100L), eq("private"),
                eq(100L), anyString(), anyString(), anyString(), anyString());
        verify(funnelTriggerService, times(1)).fire(eq(projectId), eq(100L), eq("on_start"), eq("ref_X"));
        assertRawUpdateDone(raw.getId());
    }

    @Test
    void startPrivateNoPayload_startPayloadIsEmpty() {
        RawUpdate raw = seedRawUpdate(RawUpdateStatus.PENDING,
                privateStartPayload(100L, 100L, "/start"), 1L);

        job.handle(raw.getId());

        Event event = onlyEvent();
        assertThat(event.getMetadata()).containsEntry("startPayload", "");
        verify(funnelTriggerService, times(1)).fire(any(), any(), eq("on_start"), eq(""));
    }

    @Test
    void startWithBotSuffix_payloadExtracted() {
        RawUpdate raw = seedRawUpdate(RawUpdateStatus.PENDING,
                privateStartPayload(100L, 100L, "/start@SomeBot ref_X"), 1L);

        job.handle(raw.getId());

        Event event = onlyEvent();
        assertThat(event.getMetadata()).containsEntry("startPayload", "ref_X");
        verify(funnelTriggerService, times(1)).fire(any(), any(), eq("on_start"), eq("ref_X"));
    }

    // ─── AC8 — /stop ───────────────────────────────────────────────────────────

    @Test
    void stopPrivate_callsMarkUnsubscribedAndCancel_writesEvent() {
        RawUpdate raw = seedRawUpdate(RawUpdateStatus.PENDING,
                privateStartPayload(100L, 100L, "/stop"), 1L);

        job.handle(raw.getId());

        Event event = onlyEvent();
        assertThat(event.getEventType()).isEqualTo("telegram_command_stop");
        assertThat(event.getMetadata()).containsEntry("chatId", 100L);
        assertThat(event.getMetadata()).containsEntry("chatType", "private");
        verify(subscriberService, times(1)).markUnsubscribed(projectId, TELEGRAM_BOT_ID, 100L);
        verify(funnelTriggerService, times(1)).cancelActiveFor(projectId, 100L);
        verify(subscriberService, never())
                .upsertFromTelegramUpdate(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(funnelTriggerService, never()).fire(any(), any(), any(), any());
    }

    // ─── AC9 — /start in group → event only, no stubs, no populate ────────────

    @Test
    void startGroup_eventOnly_noStubsNoOwnerPopulate() {
        RawUpdate raw = seedRawUpdate(RawUpdateStatus.PENDING,
                messagePayload(-100200300L, "group", 999L, "/start", 1L), 1L);

        job.handle(raw.getId());

        Event event = onlyEvent();
        assertThat(event.getEventType()).isEqualTo("telegram_command_start");
        assertThat(event.getMetadata()).containsEntry("chatType", "group");
        verify(subscriberService, never())
                .upsertFromTelegramUpdate(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(funnelTriggerService, never()).fire(any(), any(), any(), any());
        Bot reloaded = botRepository.findById(botId).block();
        assertThat(reloaded.getOwnerChatId()).as("group /start must NOT populate ownerChatId").isNull();
    }

    // ─── AC10 — plain text ─────────────────────────────────────────────────────

    @Test
    void plainTextPrivate_callsSubscriber_writesMessageReceivedEvent() {
        RawUpdate raw = seedRawUpdate(RawUpdateStatus.PENDING,
                privateStartPayload(100L, 100L, "hello world"), 1L);

        job.handle(raw.getId());

        Event event = onlyEvent();
        assertThat(event.getEventType()).isEqualTo("telegram_message_received");
        verify(subscriberService, times(1)).upsertFromTelegramUpdate(
                eq(projectId), eq(TELEGRAM_BOT_ID), eq(100L), eq("private"),
                eq(100L), anyString(), anyString(), anyString(), anyString());
        verify(funnelTriggerService, never()).fire(any(), any(), any(), any());
    }

    @Test
    void plainTextGroup_eventOnly_noStubCall() {
        RawUpdate raw = seedRawUpdate(RawUpdateStatus.PENDING,
                messagePayload(-100L, "group", 999L, "hello group", 1L), 1L);

        job.handle(raw.getId());

        Event event = onlyEvent();
        assertThat(event.getEventType()).isEqualTo("telegram_message_received");
        verify(subscriberService, never())
                .upsertFromTelegramUpdate(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ─── AC11 — non-message update kinds ───────────────────────────────────────

    static Stream<String> jsonNodeSlots() {
        return Stream.of("callback_query", "my_chat_member", "chat_member",
                "inline_query", "shipping_query", "pre_checkout_query", "poll_answer");
    }

    @ParameterizedTest
    @MethodSource("jsonNodeSlots")
    void nonMessageJsonNodeSlot_writesUpdateOther_noStubs(String slot) {
        RawUpdate raw = seedRawUpdate(RawUpdateStatus.PENDING, slotPayload(slot), 1L);

        job.handle(raw.getId());

        Event event = onlyEvent();
        assertThat(event.getEventType()).isEqualTo("telegram_update_other");
        assertThat(event.getMetadata()).containsEntry("updateKind", slot);
        verify(subscriberService, never())
                .upsertFromTelegramUpdate(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(funnelTriggerService, never()).fire(any(), any(), any(), any());
        verify(funnelTriggerService, never()).cancelActiveFor(any(), any());
    }

    static Stream<String> typedMessageSlots() {
        return Stream.of("edited_message", "channel_post", "edited_channel_post");
    }

    @ParameterizedTest
    @MethodSource("typedMessageSlots")
    void typedMessageSlot_writesUpdateOther_noStubs(String slot) {
        RawUpdate raw = seedRawUpdate(RawUpdateStatus.PENDING, typedMessageSlotPayload(slot), 1L);

        job.handle(raw.getId());

        Event event = onlyEvent();
        assertThat(event.getEventType()).isEqualTo("telegram_update_other");
        assertThat(event.getMetadata()).containsEntry("updateKind", slot);
        verify(subscriberService, never())
                .upsertFromTelegramUpdate(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void trulyUnknownUpdate_updateKindUnknown() {
        RawUpdate raw = seedRawUpdate(RawUpdateStatus.PENDING,
                new Document().append("update_id", 1L), 1L);

        job.handle(raw.getId());

        Event event = onlyEvent();
        assertThat(event.getEventType()).isEqualTo("telegram_update_other");
        assertThat(event.getMetadata()).containsEntry("updateKind", "unknown");
    }

    @Test
    void messageNullAndNoSlot_safeNavigate() {
        // Same shape as trulyUnknownUpdate but explicit on the "no NPE" contract per AC11a.
        RawUpdate raw = seedRawUpdate(RawUpdateStatus.PENDING,
                new Document().append("update_id", 999L), 1L);

        job.handle(raw.getId());

        Event event = onlyEvent();
        assertThat(event.getEventType()).isEqualTo("telegram_update_other");
        assertThat(event.getMetadata()).containsEntry("updateKind", "unknown");
        assertRawUpdateDone(raw.getId());
    }

    // ─── AC12 — unknown command ────────────────────────────────────────────────

    @Test
    void unknownCommandPrivate_telegramMessageReceivedEvent_noStubs() {
        RawUpdate raw = seedRawUpdate(RawUpdateStatus.PENDING,
                privateStartPayload(100L, 100L, "/foo bar"), 1L);

        job.handle(raw.getId());

        Event event = onlyEvent();
        assertThat(event.getEventType()).isEqualTo("telegram_message_received");
        verify(subscriberService, never())
                .upsertFromTelegramUpdate(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(funnelTriggerService, never()).fire(any(), any(), any(), any());
    }

    @Test
    void unknownCommandGroup_telegramMessageReceivedEvent_noStubs() {
        RawUpdate raw = seedRawUpdate(RawUpdateStatus.PENDING,
                messagePayload(-100L, "group", 999L, "/foo bar", 1L), 1L);

        job.handle(raw.getId());

        Event event = onlyEvent();
        assertThat(event.getEventType()).isEqualTo("telegram_message_received");
        verify(subscriberService, never())
                .upsertFromTelegramUpdate(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ─── AC14 — worker exception path ──────────────────────────────────────────

    @Test
    void workerException_writesFailedStatus_scrubbedTruncated_incrementsFailureCounter_rethrows() {
        // Inject a token-like string into the exception message + pad over 1024 chars to assert
        // BOTH the scrubber AND the truncator on processingError. Force the throw inside the
        // upsert call so it fires from inside the worker's try/catch.
        String token = "9876543210:ZYXwvuTSR_qpoNMLkjiHGFedcba9876543210abc";
        StringBuilder filler = new StringBuilder(token);
        while (filler.length() < 2000) filler.append("-padding-");
        String message = filler.toString();
        Mockito.doThrow(new RuntimeException(message))
                .when(subscriberService).upsertFromTelegramUpdate(
                        any(), any(), any(), any(), any(), any(), any(), any(), any());

        RawUpdate raw = seedRawUpdate(RawUpdateStatus.PENDING,
                privateStartPayload(100L, 100L, "/start ref_X"), 1L);

        long beforeFailure = failureCount();
        long beforeSuccess = successCount();

        Throwable rethrown = org.assertj.core.api.Assertions.catchThrowable(() -> job.handle(raw.getId()));
        assertThat(rethrown).isInstanceOf(RuntimeException.class);
        // Critical: the rethrown exception's message MUST also be scrubbed — JobRunr writes it
        // into jobrunr_jobs + ERROR log, and we don't want the original token leaking there.
        assertThat(rethrown.getMessage()).isNotNull();
        assertThat(rethrown.getMessage().length()).isLessThanOrEqualTo(1024);
        assertThat(TOKEN_PATTERN.matcher(rethrown.getMessage()).find())
                .as("rethrown exception message MUST NOT contain a raw bot-token regex match")
                .isFalse();
        assertThat(rethrown.getCause())
                .as("rethrown exception MUST have no cause chain — cause.getMessage() would re-leak the token")
                .isNull();

        RawUpdate reloaded = rawUpdateRepository.findById(raw.getId()).block();
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getProcessingStatus()).isEqualTo(RawUpdateStatus.FAILED);
        assertThat(reloaded.getProcessingError()).isNotNull();
        assertThat(reloaded.getProcessingError().length()).isLessThanOrEqualTo(1024);
        assertThat(TOKEN_PATTERN.matcher(reloaded.getProcessingError()).find())
                .as("processingError must NOT contain a raw bot-token regex match — scrubber failed")
                .isFalse();
        // ListAppender pin: the ERROR log line is the third token-scrubber site — assert no
        // token regex match appears in any captured log event.
        assertThat(jobAppender.list)
                .as("no log event may carry a raw token through the appender")
                .allSatisfy(e -> assertThat(TOKEN_PATTERN.matcher(e.getFormattedMessage()).find()).isFalse());
        assertThat(failureCount() - beforeFailure).isEqualTo(1L);
        assertThat(successCount()).as("failure path must NOT tick success counter").isEqualTo(beforeSuccess);
    }

    // ─── added coverage from review round 1 ────────────────────────────────────

    @Test
    void startPrivateMultiWordPayload_joinedAfterFirstWhitespace() {
        // AC7 third bullet — `/start ref_a b c` → startPayload="ref_a b c" (everything after the
        // first whitespace, leading whitespace trimmed by parser).
        RawUpdate raw = seedRawUpdate(RawUpdateStatus.PENDING,
                privateStartPayload(100L, 100L, "/start ref_a b c"), 1L);

        job.handle(raw.getId());

        Event event = onlyEvent();
        assertThat(event.getMetadata()).containsEntry("startPayload", "ref_a b c");
        verify(funnelTriggerService, times(1)).fire(any(), any(), eq("on_start"), eq("ref_a b c"));
    }

    @Test
    void stopGroup_eventOnly_noStubCall() {
        // Symmetric to startGroup test — /stop in a group chat writes the event but invokes no stubs.
        RawUpdate raw = seedRawUpdate(RawUpdateStatus.PENDING,
                messagePayload(-100L, "group", 999L, "/stop", 1L), 1L);

        job.handle(raw.getId());

        Event event = onlyEvent();
        assertThat(event.getEventType()).isEqualTo("telegram_command_stop");
        assertThat(event.getMetadata()).containsEntry("chatType", "group");
        verify(subscriberService, never()).markUnsubscribed(any(), any(), any());
        verify(funnelTriggerService, never()).cancelActiveFor(any(), any());
    }

    // ─── Bug #2 — metadata.chatType across all chat.type values ────────────────

    static Stream<String> chatTypeVariants() {
        return Stream.of("private", "group", "supergroup", "channel");
    }

    @ParameterizedTest
    @MethodSource("chatTypeVariants")
    void commandStart_eventCarriesChatType(String chatType) {
        // Bug #2 — metadata.chatType MUST be present on telegram_command_start for every chat.type
        // value Telegram emits. Manual-test checklist 2.6 (private) and 2.15 (group/supergroup)
        // assert it; AC9 verification depends on the field appearing for non-private chats.
        Long chatId = "private".equals(chatType) ? 100L : -100200300L;
        RawUpdate raw = seedRawUpdate(RawUpdateStatus.PENDING,
                messagePayload(chatId, chatType, 999L, "/start ref_X", 1L), 1L);

        job.handle(raw.getId());

        Event event = onlyEvent();
        assertThat(event.getEventType()).isEqualTo("telegram_command_start");
        assertThat(event.getMetadata())
                .as("metadata.chatType must mirror chat.type from the Telegram update")
                .containsEntry("chatType", chatType);
    }

    @ParameterizedTest
    @MethodSource("chatTypeVariants")
    void commandStop_eventCarriesChatType(String chatType) {
        // Bug #2 mirror — telegram_command_stop must carry metadata.chatType for every chat.type.
        // Manual-test checklist 2.13 asserts the private variant; group/supergroup/channel are
        // implied by the same contract.
        Long chatId = "private".equals(chatType) ? 100L : -100200300L;
        RawUpdate raw = seedRawUpdate(RawUpdateStatus.PENDING,
                messagePayload(chatId, chatType, 999L, "/stop", 1L), 1L);

        job.handle(raw.getId());

        Event event = onlyEvent();
        assertThat(event.getEventType()).isEqualTo("telegram_command_stop");
        assertThat(event.getMetadata())
                .as("metadata.chatType must mirror chat.type from the Telegram update")
                .containsEntry("chatType", chatType);
    }

    @Test
    void startPrivateBotMissing_logsWarnAndWritesUpdateOther() {
        // Edge case from task: bot lookup races a concurrent disconnect — worker degrades to
        // telegram_update_other with updateKind="unknown" and emits a WARN log site that must
        // be scrubber-safe.
        botRepository.deleteAll().block();
        RawUpdate raw = seedRawUpdate(RawUpdateStatus.PENDING,
                privateStartPayload(100L, 100L, "/start"), 1L);

        job.handle(raw.getId());

        Event event = onlyEvent();
        assertThat(event.getEventType()).isEqualTo("telegram_update_other");
        assertThat(event.getMetadata()).containsEntry("updateKind", "unknown");
        verify(subscriberService, never())
                .upsertFromTelegramUpdate(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(funnelTriggerService, never()).fire(any(), any(), any(), any());
        boolean warnEmitted = jobAppender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("bot lookup missed"));
        assertThat(warnEmitted).as("bot==null path must emit a WARN log line").isTrue();
    }

    @Test
    void mediaOnlyMessagePrivate_updateKindMessage_noStubs() {
        // Edge case from task Details: a sticker/image/document message has message != null but
        // text == null. Classify as telegram_update_other with updateKind="message", no stub calls.
        RawUpdate raw = seedRawUpdate(RawUpdateStatus.PENDING,
                messagePayload(100L, "private", 100L, null, 1L), 1L);

        job.handle(raw.getId());

        Event event = onlyEvent();
        assertThat(event.getEventType()).isEqualTo("telegram_update_other");
        assertThat(event.getMetadata()).containsEntry("updateKind", "message");
        verify(subscriberService, never())
                .upsertFromTelegramUpdate(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(funnelTriggerService, never()).fire(any(), any(), any(), any());
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private RawUpdate seedRawUpdate(RawUpdateStatus status, Document payload, long updateId) {
        RawUpdate raw = new RawUpdate();
        raw.setProjectId(projectId);
        raw.setUpdateId(updateId);
        raw.setPayload(payload);
        raw.setProcessingStatus(status);
        raw.setCreatedAt(Instant.now());
        return rawUpdateRepository.save(raw).block();
    }

    private Document privateStartPayload(Long chatId, Long fromId, String text) {
        return messagePayload(chatId, "private", fromId, text, 1L);
    }

    private Document messagePayload(Long chatId, String chatType, Long fromId, String text, Long messageId) {
        Document chat = new Document().append("id", chatId).append("type", chatType);
        Document message = new Document()
                .append("message_id", messageId)
                .append("chat", chat)
                .append("date", 1700000000L)
                .append("text", text);
        if (fromId != null) {
            message.append("from", new Document()
                    .append("id", fromId)
                    .append("is_bot", false)
                    .append("first_name", "Test")
                    .append("last_name", "User")
                    .append("username", "testuser")
                    .append("language_code", "en"));
        }
        return new Document().append("update_id", 1L).append("message", message);
    }

    private Document slotPayload(String slotName) {
        return new Document()
                .append("update_id", 1L)
                .append(slotName, new Document("id", 1L).append("note", "stub"));
    }

    private Document typedMessageSlotPayload(String slotName) {
        Document chat = new Document().append("id", -100200300L).append("type", "channel");
        Document message = new Document()
                .append("message_id", 1L)
                .append("chat", chat)
                .append("date", 1700000000L)
                .append("text", "channel post body");
        return new Document().append("update_id", 1L).append(slotName, message);
    }

    private Event onlyEvent() {
        List<Event> events = eventRepository.findAll().collectList().block();
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    private void assertRawUpdateDone(String id) {
        RawUpdate reloaded = rawUpdateRepository.findById(id).block();
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getProcessingStatus()).isEqualTo(RawUpdateStatus.DONE);
    }

    private long successCount() {
        return (long) meterRegistry.counter("telegram_worker_outcome_total", "outcome", "success").count();
    }

    private long failureCount() {
        return (long) meterRegistry.counter("telegram_worker_outcome_total", "outcome", "failure").count();
    }
}
