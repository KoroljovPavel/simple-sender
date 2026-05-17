package com.botfunnel.webhook;

import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.bot.TelegramApiClient;
import com.botfunnel.events.EventService;
import com.botfunnel.funnel.FunnelTriggerService;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.subscriber.SubscriberService;
import com.botfunnel.webhook.dto.Chat;
import com.botfunnel.webhook.dto.Message;
import com.botfunnel.webhook.dto.TelegramUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

// JobRunr dispatches handle(rawUpdateId) on a worker thread (blocking-safe — same convention as
// HardDeleteJob / ProjectHardDeleteJob). Three correctness mechanisms (Decisions 5, 9, 12):
//   1. Re-entry guard — first action after load is "if processingStatus == DONE return".
//   2. Cascade ordering — every event.logEventBlocking(...).block() finishes BEFORE the rawUpdate
//      status flip to DONE; a mid-flight worker crash leaves NO DONE row without its audit event.
//   3. Failure path — catch Throwable, atomically write FAILED + scrubbed/truncated error via
//      findAndModify, increment failure counter, and RETHROW so JobRunr's default retry kicks in.
@Component
public class ProcessTelegramUpdateJob {

    private static final Logger log = LoggerFactory.getLogger(ProcessTelegramUpdateJob.class);

    private static final String EVT_COMMAND_START = "telegram_command_start";
    private static final String EVT_COMMAND_STOP = "telegram_command_stop";
    private static final String EVT_MESSAGE_RECEIVED = "telegram_message_received";
    private static final String EVT_UPDATE_OTHER = "telegram_update_other";
    private static final String COUNTER = "telegram_worker_outcome_total";
    private static final int ERROR_MAX_LEN = 1024;

    private final RawUpdateRepository rawUpdateRepository;
    private final BotRepository botRepository;
    private final ProjectRepository projectRepository;
    private final ReactiveMongoTemplate reactiveMongoTemplate;
    private final EventService eventService;
    private final SubscriberService subscriberService;
    private final FunnelTriggerService funnelTriggerService;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public ProcessTelegramUpdateJob(RawUpdateRepository rawUpdateRepository,
                                    BotRepository botRepository,
                                    ProjectRepository projectRepository,
                                    ReactiveMongoTemplate reactiveMongoTemplate,
                                    EventService eventService,
                                    SubscriberService subscriberService,
                                    FunnelTriggerService funnelTriggerService,
                                    MeterRegistry meterRegistry,
                                    ObjectMapper objectMapper) {
        this.rawUpdateRepository = rawUpdateRepository;
        this.botRepository = botRepository;
        this.projectRepository = projectRepository;
        this.reactiveMongoTemplate = reactiveMongoTemplate;
        this.eventService = eventService;
        this.subscriberService = subscriberService;
        this.funnelTriggerService = funnelTriggerService;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    public void handle(String rawUpdateId) {
        RawUpdate rawUpdate = rawUpdateRepository.findById(rawUpdateId).block();
        if (rawUpdate == null) {
            // Deterministic-UUID enqueue happens AFTER the reactive save commit (Decision 11), so
            // a missing row at worker time should not happen in production. Log and exit — better
            // than throwing and looping forever on a row that genuinely is not coming.
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
            Project project = projectRepository.findById(rawUpdate.getProjectId()).block();
            String userId = project == null ? null : project.getOwnerId();
            dispatch(rawUpdate.getProjectId(), userId, update);

            rawUpdate.setProcessingStatus(RawUpdateStatus.DONE);
            rawUpdateRepository.save(rawUpdate).block();
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
        // line 210 uses the same pattern).
        reactiveMongoTemplate.findAndModify(
                Query.query(Criteria.where("_id").is(rawUpdateId)),
                new Update()
                        .set("processingStatus", RawUpdateStatus.FAILED.name())
                        .set("processingError", truncated),
                RawUpdate.class).block();
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
                case "start" -> handleStart(projectId, userId, isPrivate, chatId, message, parsed.payload());
                case "stop" -> handleStop(projectId, userId, isPrivate, chatId);
                default -> logEventMessageReceived(projectId, userId, chatId);
            }
            return;
        }

        // Plain text — private-chat upserts subscriber; non-private logs event only.
        if (isPrivate && chatId != null) {
            Bot bot = botRepository.findByProjectIdAndStatus(projectId, BotStatus.CONNECTED).block();
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
                    from == null ? null : from.language_code()).block();
        }
        logEventMessageReceived(projectId, userId, chatId);
    }

    private void handleStart(String projectId, String userId, boolean isPrivate, Long chatId,
                             Message message, String startPayload) {
        if (!isPrivate || chatId == null) {
            // AC9 — group/supergroup/channel /start: event only, NO ownerChatId populate, NO stubs.
            logEventCommandStart(projectId, userId, chatId, startPayload);
            return;
        }

        Bot bot = botRepository.findByProjectIdAndStatus(projectId, BotStatus.CONNECTED).block();
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
            // changed CONNECTED→DISCONNECTED between lookup and write) returns Mono.empty(), which
            // .block() resolves to null. Treated as no-op; the populate-or-not race is intentional.
            Bot updated = reactiveMongoTemplate.findAndModify(
                    Query.query(Criteria.where("_id").is(bot.getId())
                            .and("status").is(BotStatus.CONNECTED.name())
                            .and("ownerChatId").isNull()),
                    new Update().set("ownerChatId", chatId),
                    Bot.class).block();
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
                from == null ? null : from.language_code()).block();
        funnelTriggerService.fire(projectId, chatId, "on_start", startPayload).block();
        logEventCommandStart(projectId, userId, chatId, startPayload);
    }

    private void handleStop(String projectId, String userId, boolean isPrivate, Long chatId) {
        if (isPrivate && chatId != null) {
            Bot bot = botRepository.findByProjectIdAndStatus(projectId, BotStatus.CONNECTED).block();
            if (bot == null) {
                logEventOther(projectId, userId, "unknown");
                return;
            }
            subscriberService.markUnsubscribed(projectId, bot.getTelegramBotId(), chatId).block();
            funnelTriggerService.cancelActiveFor(projectId, chatId).block();
        }
        logEventCommandStop(projectId, userId, chatId);
    }

    private void logEventCommandStart(String projectId, String userId, Long chatId, String startPayload) {
        // LinkedHashMap allows null values; Map.of does not — chatId can be null in malformed
        // payloads even though our dispatch usually filters those upstream.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("projectId", projectId);
        metadata.put("startPayload", startPayload);
        metadata.put("chatId", chatId);
        eventService.logEventBlocking(userId, EVT_COMMAND_START, null, null, metadata).block();
    }

    private void logEventCommandStop(String projectId, String userId, Long chatId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("projectId", projectId);
        metadata.put("chatId", chatId);
        eventService.logEventBlocking(userId, EVT_COMMAND_STOP, null, null, metadata).block();
    }

    private void logEventMessageReceived(String projectId, String userId, Long chatId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("projectId", projectId);
        metadata.put("chatId", chatId);
        eventService.logEventBlocking(userId, EVT_MESSAGE_RECEIVED, null, null, metadata).block();
    }

    private void logEventOther(String projectId, String userId, String updateKind) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("projectId", projectId);
        metadata.put("updateKind", updateKind);
        eventService.logEventBlocking(userId, EVT_UPDATE_OTHER, null, null, metadata).block();
    }

    private static String resolveUpdateKind(TelegramUpdate update) {
        if (update.edited_message() != null) return "edited_message";
        if (update.channel_post() != null) return "channel_post";
        if (update.edited_channel_post() != null) return "edited_channel_post";
        JsonNode n;
        if ((n = update.callback_query()) != null && !n.isNull()) return "callback_query";
        if ((n = update.my_chat_member()) != null && !n.isNull()) return "my_chat_member";
        if ((n = update.chat_member()) != null && !n.isNull()) return "chat_member";
        if ((n = update.inline_query()) != null && !n.isNull()) return "inline_query";
        if ((n = update.shipping_query()) != null && !n.isNull()) return "shipping_query";
        if ((n = update.pre_checkout_query()) != null && !n.isNull()) return "pre_checkout_query";
        if ((n = update.poll_answer()) != null && !n.isNull()) return "poll_answer";
        return "unknown";
    }

}
