package com.botfunnel.webhook;

import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.bot.TelegramApiClient;
import com.botfunnel.events.EventService;
import com.botfunnel.funnel.ExecutionStatus;
import com.botfunnel.funnel.FunnelEventService;
import com.botfunnel.funnel.FunnelExecution;
import com.botfunnel.funnel.FunnelTriggerService;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberService;
import com.botfunnel.webhook.dto.CallbackQuery;
import com.botfunnel.webhook.dto.Chat;
import com.botfunnel.webhook.dto.Message;
import com.botfunnel.webhook.dto.TelegramUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

// JobRunr dispatches handle(rawUpdateId) on a worker thread (blocking-safe — same convention as
// HardDeleteJob / ProjectHardDeleteJob). Three correctness mechanisms (Decisions 5, 9, 12):
//   1. Re-entry guard — first action after load is "if processingStatus == DONE return".
//   2. Cascade ordering — every event.logEvent(...) finishes BEFORE the rawUpdate status flip
//      to DONE; a mid-flight worker crash leaves NO DONE row without its audit event.
//   3. Failure path — catch Throwable, atomically write FAILED + scrubbed/truncated error via
//      findAndModify, increment failure counter, and RETHROW so JobRunr's default retry kicks in.
@Component
public class ProcessTelegramUpdateJob {

    private static final Logger log = LoggerFactory.getLogger(ProcessTelegramUpdateJob.class);

    private static final String EVT_COMMAND_START = "telegram_command_start";
    private static final String EVT_COMMAND_STOP = "telegram_command_stop";
    private static final String EVT_MESSAGE_RECEIVED = "telegram_message_received";
    private static final String EVT_UPDATE_OTHER = "telegram_update_other";
    private static final String EVT_CALLBACK_QUERY = "telegram_callback_query";
    private static final String COUNTER = "telegram_worker_outcome_total";
    private static final int ERROR_MAX_LEN = 1024;

    // Greppable markers for the keyword dispatch block (Decision 12 error-isolation + Decision 3
    // menu precedence). ids/codes only — never the subscriber's message text (Decision 16 PII rule).
    static final String LOG_KEYWORD_DISPATCH_ERROR = "KEYWORD_DISPATCH_ERROR";
    static final String LOG_KEYWORD_SUPPRESSED_WAITING_FOR_REPLY = "KEYWORD_SUPPRESSED_WAITING_FOR_REPLY";

    private final RawUpdateRepository rawUpdateRepository;
    private final BotRepository botRepository;
    private final ProjectRepository projectRepository;
    private final MongoTemplate mongoTemplate;
    private final EventService eventService;
    private final SubscriberService subscriberService;
    private final FunnelTriggerService funnelTriggerService;
    private final FunnelEventService funnelEventService;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public ProcessTelegramUpdateJob(RawUpdateRepository rawUpdateRepository,
                                    BotRepository botRepository,
                                    ProjectRepository projectRepository,
                                    MongoTemplate mongoTemplate,
                                    EventService eventService,
                                    SubscriberService subscriberService,
                                    FunnelTriggerService funnelTriggerService,
                                    FunnelEventService funnelEventService,
                                    MeterRegistry meterRegistry,
                                    ObjectMapper objectMapper) {
        this.rawUpdateRepository = rawUpdateRepository;
        this.botRepository = botRepository;
        this.projectRepository = projectRepository;
        this.mongoTemplate = mongoTemplate;
        this.eventService = eventService;
        this.subscriberService = subscriberService;
        this.funnelTriggerService = funnelTriggerService;
        this.funnelEventService = funnelEventService;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    public void handle(String rawUpdateId) {
        RawUpdate rawUpdate = rawUpdateRepository.findById(rawUpdateId).orElse(null);
        if (rawUpdate == null) {
            // Deterministic-UUID enqueue happens AFTER the save commit, so a missing row at
            // worker time should not happen in production. Log and exit — better than throwing
            // and looping forever on a row that genuinely is not coming.
            log.warn("ProcessTelegramUpdateJob - rawUpdate not found, skipping (rawUpdateId={})",
                    TelegramApiClient.scrubTokens(rawUpdateId));
            return;
        }
        if (rawUpdate.getProcessingStatus() == RawUpdateStatus.DONE) {
            // Re-entry guard (Decision 9): JobRunr retried a job whose previous attempt succeeded
            // but whose ack didn't observe in time. Without this short-circuit events double-fire.
            return;
        }
        // processingStatus == PENDING or FAILED — FAILED means the prior attempt failed AFTER our
        // failure-write committed; JobRunr's retry now lands here and re-runs the dispatch matrix.

        log.info("ProcessTelegramUpdateJob - start (rawUpdateId={}, projectId={})",
                rawUpdateId, rawUpdate.getProjectId());
        try {
            TelegramUpdate update = objectMapper.convertValue(rawUpdate.getPayload(), TelegramUpdate.class);
            Project project = projectRepository.findById(rawUpdate.getProjectId()).orElse(null);
            String userId = project == null ? null : project.getOwnerId();
            dispatch(rawUpdate.getProjectId(), userId, update);

            rawUpdate.setProcessingStatus(RawUpdateStatus.DONE);
            rawUpdateRepository.save(rawUpdate);
            meterRegistry.counter(COUNTER, "outcome", "success").increment();
            log.info("ProcessTelegramUpdateJob - success (rawUpdateId={}, projectId={})",
                    rawUpdateId, rawUpdate.getProjectId());
        } catch (Throwable t) {
            handleFailure(rawUpdateId, t);
        }
    }

    private void handleFailure(String rawUpdateId, Throwable t) {
        String message = t.getMessage() == null ? t.getClass().getName() : t.getMessage();
        String scrubbed = TelegramApiClient.scrubTokens(message);
        String truncated = scrubbed.substring(0, Math.min(scrubbed.length(), ERROR_MAX_LEN));
        // Atomic findAndModify — NEVER findById + setter + save here; two retries could trample
        // each other in the race window between read and write. Persist enum.name() explicitly:
        // matches the partial-filter index literal `'FAILED'` byte-identical (Bot precedent
        // uses the same pattern).
        mongoTemplate.findAndModify(
                Query.query(Criteria.where("_id").is(rawUpdateId)),
                new Update()
                        .set("processingStatus", RawUpdateStatus.FAILED.name())
                        .set("processingError", truncated),
                RawUpdate.class);
        meterRegistry.counter(COUNTER, "outcome", "failure").increment();
        log.error("ProcessTelegramUpdateJob - worker failed (rawUpdateId={}): {}",
                rawUpdateId, truncated);
        // Rethrow a NEW RuntimeException with ONLY the scrubbed+truncated message so JobRunr's
        // failure pipeline (jobrunr_jobs collection + ERROR log) cannot re-leak the raw token
        // via t.getMessage() OR t.getCause().getMessage(). Stack trace is copied across for
        // debuggability; no cause chain by design. The original simple-class-name is prefixed
        // (and re-truncated) so operators reading the jobrunr_jobs row still see what TYPE of
        // failure happened — class names are compile-time constants and can never carry payload.
        String rethrowMessage = t.getClass().getSimpleName() + ": " + truncated;
        if (rethrowMessage.length() > ERROR_MAX_LEN) {
            rethrowMessage = rethrowMessage.substring(0, ERROR_MAX_LEN);
        }
        RuntimeException toRethrow = new RuntimeException(rethrowMessage);
        toRethrow.setStackTrace(t.getStackTrace());
        throw toRethrow;
    }

    private void dispatch(String projectId, String userId, TelegramUpdate update) {
        // callback_query branch — Phase 2. Must run BEFORE the message-fallback: a callback_query
        // update has message == null at the top level, so without this it would fall through to
        // logEventOther("callback_query"/"unknown") and never reach the funnel engine.
        if (update.callback_query() != null) {
            handleCallbackQuery(projectId, userId, update.callback_query());
            return;
        }

        Message message = update.message();
        if (message == null) {
            // Modeled non-message slot OR truly unknown — resolveUpdateKind picks the first
            // populated slot or returns "unknown".
            String kind = resolveUpdateKind(update);
            logEventOther(projectId, userId, kind);
            return;
        }

        Chat chat = message.chat();
        String chatType = chat == null ? null : chat.type();
        Long chatId = chat == null ? null : chat.id();
        boolean isPrivate = "private".equals(chatType);
        String text = message.text();

        if (text == null) {
            // Media-only message (sticker, image, document) — not enumerated in AC11 alongside the
            // modeled slots, but the same "no upsertable text content" classification applies.
            // Per task edge-case resolution: updateKind="message", no stub calls.
            logEventOther(projectId, userId, "message");
            return;
        }

        if (text.startsWith("/")) {
            ParsedCommand parsed = TelegramCommandParser.parse(text);
            String command = parsed.command().toLowerCase();
            switch (command) {
                case "start" -> handleStart(projectId, userId, isPrivate, chatId, chatType, message, parsed.payload());
                case "stop" -> handleStop(projectId, userId, isPrivate, chatId, chatType);
                default -> logEventMessageReceived(projectId, userId, chatId);
            }
            return;
        }

        // Plain text — private-chat upserts subscriber; non-private logs event only.
        if (isPrivate && chatId != null) {
            Bot bot = botRepository.findByProjectIdAndStatus(projectId, BotStatus.CONNECTED).orElse(null);
            if (bot == null) {
                logEventOther(projectId, userId, "unknown");
                return;
            }
            com.botfunnel.webhook.dto.User from = message.from();
            subscriberService.upsertFromTelegramUpdate(
                    projectId, bot.getTelegramBotId(), chatId, chatType,
                    from == null ? null : from.id(),
                    from == null ? null : from.first_name(),
                    from == null ? null : from.last_name(),
                    from == null ? null : from.username(),
                    from == null ? null : from.language_code());
            dispatchKeyword(projectId, bot.getTelegramBotId(), chatId, text);
        }
        logEventMessageReceived(projectId, userId, chatId);
    }

    // Keyword trigger (Phase 3 / Decisions 3, 12). Resolves the subscriber, enforces menu precedence
    // (an in-flight waiting_for_reply execution suppresses keyword), then fans out keyword matching via
    // FunnelEventService. The ENTIRE block is best-effort swallow-all (Decision 12): any fault is
    // logged (greppable WARN, ids/codes only) and swallowed so the worker still flips the rawUpdate to
    // DONE (HTTP 200) and the JobRunr job does not fail. Mirrors the advanceOnCallback convention — but
    // here we DO add a local swallow because keyword is additive (not the worker's primary job) and a
    // keyword fault must never poison the message-received audit write that follows.
    private void dispatchKeyword(String projectId, Long telegramBotId, Long chatId, String text) {
        try {
            Subscriber subscriber = subscriberService.findByChat(projectId, telegramBotId, chatId).orElse(null);
            if (subscriber == null) {
                // Should not happen right after the upsert above, but a concurrent delete is possible —
                // skip keyword (the message-received event still fires in the caller).
                return;
            }
            if (hasWaitingForReplyExecution(projectId, subscriber.getId())) {
                // Menu precedence (Decision 3): the subscriber is parked in a MENU step waiting for a
                // button press; keyword is suppressed and the menu execution is left untouched.
                log.info("{} projectId={} subscriberId={}", LOG_KEYWORD_SUPPRESSED_WAITING_FOR_REPLY,
                        projectId, subscriber.getId());
                return;
            }
            // keyword is a human-root → originDepth 0. The dispatcher contains-matches the text against
            // each active keyword funnel's keywords list and fans out (Task 4).
            funnelEventService.dispatchForSubscriber(
                    projectId, subscriber.getId(), FunnelEventService.TRIGGER_KEYWORD, text, 0);
        } catch (Throwable t) {
            // Decision 12 error-isolation: a keyword fault must NOT poison the webhook pipeline. Log a
            // greppable WARN (ids/codes only — never the message text) and swallow so the worker returns
            // 200 and the JobRunr job is not failed.
            log.warn("{} projectId={} error={}", LOG_KEYWORD_DISPATCH_ERROR,
                    projectId, t.getClass().getSimpleName());
        }
    }

    // Read-only precedence probe: does the subscriber have an in-flight waiting_for_reply execution?
    // Status persisted as lowercase .name() (Decision 14) — UPPERCASE would match zero rows and break
    // precedence. mongoTemplate.exists(...) only — NEVER an update (cancelActiveFor's updateMulti is
    // destructive and must not run here).
    private boolean hasWaitingForReplyExecution(String projectId, String subscriberId) {
        Query query = Query.query(Criteria.where("projectId").is(projectId)
                .and("subscriberId").is(subscriberId)
                .and("status").is(ExecutionStatus.waiting_for_reply.name()));
        return mongoTemplate.exists(query, FunnelExecution.class);
    }

    private void handleStart(String projectId, String userId, boolean isPrivate, Long chatId,
                             String chatType, Message message, String startPayload) {
        if (!isPrivate || chatId == null) {
            // AC9 — group/supergroup/channel /start: event only, NO ownerChatId populate, NO stubs.
            logEventCommandStart(projectId, userId, chatId, chatType, startPayload);
            return;
        }

        Bot bot = botRepository.findByProjectIdAndStatus(projectId, BotStatus.CONNECTED).orElse(null);
        if (bot == null) {
            // Bot lookup races with a concurrent disconnect — treat as unknown per Edge cases.
            log.warn("ProcessTelegramUpdateJob - bot lookup missed in /start handler (projectId={}, chatId={})",
                    projectId, chatId);
            logEventOther(projectId, userId, "unknown");
            return;
        }
        Long telegramBotId = bot.getTelegramBotId();

        if (bot.getOwnerChatId() == null) {
            // Decision 5 — atomic CAS. Predicate-fail (another worker already populated, or status
            // changed CONNECTED→DISCONNECTED between lookup and write) returns null — treated as
            // a benign no-op; the populate-or-not race is intentional.
            Bot updated = mongoTemplate.findAndModify(
                    Query.query(Criteria.where("_id").is(bot.getId())
                            .and("status").is(BotStatus.CONNECTED.name())
                            .and("ownerChatId").isNull()),
                    new Update().set("ownerChatId", chatId),
                    Bot.class);
            if (updated != null) {
                log.info("ProcessTelegramUpdateJob - ownerChatId populated (botId={}, chatId={})",
                        bot.getId(), TelegramApiClient.scrubTokens(String.valueOf(chatId)));
            }
        }

        com.botfunnel.webhook.dto.User from = message.from();
        subscriberService.upsertFromTelegramUpdate(
                projectId, telegramBotId, chatId, "private",
                from == null ? null : from.id(),
                from == null ? null : from.first_name(),
                from == null ? null : from.last_name(),
                from == null ? null : from.username(),
                from == null ? null : from.language_code());
        funnelTriggerService.fire(projectId, chatId, "on_start", startPayload);
        logEventCommandStart(projectId, userId, chatId, chatType, startPayload);
    }

    private void handleStop(String projectId, String userId, boolean isPrivate, Long chatId, String chatType) {
        if (isPrivate && chatId != null) {
            Bot bot = botRepository.findByProjectIdAndStatus(projectId, BotStatus.CONNECTED).orElse(null);
            if (bot == null) {
                logEventOther(projectId, userId, "unknown");
                return;
            }
            subscriberService.markUnsubscribed(projectId, bot.getTelegramBotId(), chatId);
            funnelTriggerService.cancelActiveFor(projectId, chatId);
        }
        logEventCommandStop(projectId, userId, chatId, chatType);
    }

    private void handleCallbackQuery(String projectId, String userId, CallbackQuery cq) {
        Bot bot = botRepository.findByProjectIdAndStatus(projectId, BotStatus.CONNECTED).orElse(null);
        if (bot == null) {
            // Bot lookup races a concurrent disconnect — degrade to telegram_update_other, do NOT
            // advance (mirrors the bot-missing pattern in handleStart). updateKind reuses the slot
            // name "callback_query" so the analytics taxonomy stays consistent.
            log.warn("ProcessTelegramUpdateJob - bot lookup missed in callback_query handler (projectId={})",
                    projectId);
            logEventOther(projectId, userId, "callback_query");
            return;
        }

        Message cqMessage = cq.message();
        Chat chat = cqMessage == null ? null : cqMessage.chat();
        Long chatId = chat == null ? null : chat.id();
        String data = cq.data();
        String callbackQueryId = cq.id();

        // advanceOnCallback is best-effort swallow-all (Task 5) — a failed advance must NOT poison
        // the worker. It stays inside the worker's try/catch(Throwable) so a genuine worker fault
        // still surfaces; we add NO extra local swallow that would hide a real failure.
        funnelTriggerService.advanceOnCallback(projectId, chatId, data, callbackQueryId);
        // Event AFTER the advance but BEFORE the status flip in handle() (event-before-flip). Carries
        // ids/codes only — no PII (no from.first_name / username), Decision 9.
        logEventCallbackQuery(projectId, userId, chatId, data);
    }

    private void logEventCommandStart(String projectId, String userId, Long chatId, String chatType, String startPayload) {
        // LinkedHashMap allows null values; Map.of does not — chatId can be null in malformed
        // payloads even though our dispatch usually filters those upstream.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("projectId", projectId);
        metadata.put("startPayload", startPayload);
        metadata.put("chatId", chatId);
        metadata.put("chatType", chatType);
        eventService.logEvent(userId, EVT_COMMAND_START, null, null, metadata);
    }

    private void logEventCommandStop(String projectId, String userId, Long chatId, String chatType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("projectId", projectId);
        metadata.put("chatId", chatId);
        metadata.put("chatType", chatType);
        eventService.logEvent(userId, EVT_COMMAND_STOP, null, null, metadata);
    }

    private void logEventMessageReceived(String projectId, String userId, Long chatId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("projectId", projectId);
        metadata.put("chatId", chatId);
        eventService.logEvent(userId, EVT_MESSAGE_RECEIVED, null, null, metadata);
    }

    private void logEventCallbackQuery(String projectId, String userId, Long chatId, String dataMarker) {
        // LinkedHashMap allows null values (chatId/dataMarker can be null on inline-mode callbacks
        // or absent data). ids/codes ONLY — never from.first_name / last_name / username (Decision 9).
        // dataMarker is the raw callback_data ("{executionId}:{buttonIndex}") — an internal code,
        // not PII; strict parse/validation is advanceOnCallback's job (Task 5), not the worker's.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("projectId", projectId);
        metadata.put("chatId", chatId);
        metadata.put("data", dataMarker);
        eventService.logEvent(userId, EVT_CALLBACK_QUERY, null, null, metadata);
    }

    private void logEventOther(String projectId, String userId, String updateKind) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("projectId", projectId);
        metadata.put("updateKind", updateKind);
        eventService.logEvent(userId, EVT_UPDATE_OTHER, null, null, metadata);
    }

    private static String resolveUpdateKind(TelegramUpdate update) {
        if (update.edited_message() != null) return "edited_message";
        if (update.channel_post() != null) return "channel_post";
        if (update.edited_channel_post() != null) return "edited_channel_post";
        // callback_query is now a typed dispatch branch (handleCallbackQuery), not an opaque slot —
        // but a bot-missing degrade still routes here, so keep it classified for that path.
        if (update.callback_query() != null) return "callback_query";
        JsonNode n;
        if ((n = update.my_chat_member()) != null && !n.isNull()) return "my_chat_member";
        if ((n = update.chat_member()) != null && !n.isNull()) return "chat_member";
        if ((n = update.inline_query()) != null && !n.isNull()) return "inline_query";
        if ((n = update.shipping_query()) != null && !n.isNull()) return "shipping_query";
        if ((n = update.pre_checkout_query()) != null && !n.isNull()) return "pre_checkout_query";
        if ((n = update.poll_answer()) != null && !n.isNull()) return "poll_answer";
        return "unknown";
    }

}
