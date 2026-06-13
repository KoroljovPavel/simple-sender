package com.botfunnel.funnel;

import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.bot.TelegramSender;
import com.botfunnel.events.EventService;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Real funnel trigger implementation (replaces the former NoOp; Decision 5 — exactly one
 * {@code @Service} of this interface, otherwise the context fails with
 * {@code NoUniqueBeanDefinitionException}). Called from inside the webhook worker
 * ({@code ProcessTelegramUpdateJob.handleStart} / {@code handleStop}).
 *
 * <p><strong>{@code fire()} is error-isolated</strong> (Decision 6): the entire body is wrapped in a
 * {@code try/catch(Throwable)} that swallows + logs. {@code fire()} NEVER throws outward — a funnel
 * fault must not poison the surrounding {@code dispatch()} (which catch-Throwable + rethrows + lets
 * JobRunr retry), or a single bad funnel would flip the raw_update to FAILED and trigger a retry-storm
 * (re-upsert + re-fire).
 *
 * <p><strong>Re-enter guard</strong> (Decision 8) is the unique partial index
 * {@code (funnelId, subscriberId)} filtered {@code status IN [running, waiting]} alone — no query-time
 * check (that would race two concurrent {@code /start}s). {@code allowReEnter=false}: a duplicate insert
 * raises {@link DuplicateKeyException}, swallowed as a benign re-enter no-op. {@code allowReEnter=true}:
 * atomically cancel the existing running|waiting execution for the pair, THEN insert a fresh one from
 * step 0.
 *
 * <p>Enum statuses in Mongo criteria are written as lowercase {@code .name()} literals (Decision 14),
 * byte-matching the indexes. Logging uses named constants with identifiers / codes only — never the
 * trigger payload or any subscriber field (Decision 16).
 */
@Service
public class FunnelTriggerServiceImpl implements FunnelTriggerService {

    private static final Logger log = LoggerFactory.getLogger(FunnelTriggerServiceImpl.class);

    static final String LOG_FIRE_NO_BOT = "FUNNEL_FIRE_SKIP_NO_CONNECTED_BOT";
    static final String LOG_FIRE_NO_SUBSCRIBER = "FUNNEL_FIRE_SKIP_NO_SUBSCRIBER";
    static final String LOG_FIRE_NO_MATCH = "FUNNEL_FIRE_NO_MATCHING_FUNNEL";
    static final String LOG_FIRE_REENTER_IGNORED = "FUNNEL_FIRE_REENTER_IGNORED";
    static final String LOG_FIRE_REENTER_RESTARTED = "FUNNEL_FIRE_REENTER_RESTARTED";
    static final String LOG_FIRE_ERROR = "FUNNEL_FIRE_ERROR";
    static final String LOG_CANCEL_NO_BOT = "FUNNEL_CANCEL_SKIP_NO_CONNECTED_BOT";
    static final String LOG_CANCEL_NO_SUBSCRIBER = "FUNNEL_CANCEL_SKIP_NO_SUBSCRIBER";
    static final String LOG_CANCEL_DONE = "FUNNEL_CANCEL_ACTIVE";
    static final String LOG_CANCEL_ERROR = "FUNNEL_CANCEL_ERROR";

    // Callback-path (Phase 2) greppable markers — identifiers/codes only, never the callback payload or
    // any subscriber field (Decision 9, mirrors the fire()/cancel() convention).
    static final String LOG_CALLBACK_NO_BOT = "FUNNEL_CALLBACK_SKIP_NO_CONNECTED_BOT";
    static final String LOG_CALLBACK_NO_SUBSCRIBER = "FUNNEL_CALLBACK_SKIP_NO_SUBSCRIBER";
    static final String LOG_CALLBACK_MALFORMED = "FUNNEL_CALLBACK_MALFORMED_DATA";
    static final String LOG_CALLBACK_NO_EXECUTION = "FUNNEL_CALLBACK_NO_EXECUTION";
    static final String LOG_CALLBACK_NOT_WAITING = "FUNNEL_CALLBACK_NOT_WAITING_FOR_REPLY";
    static final String LOG_CALLBACK_IDOR = "FUNNEL_CALLBACK_OWNER_MISMATCH";
    static final String LOG_CALLBACK_STEP_MISMATCH = "FUNNEL_CALLBACK_CURRENT_STEP_NOT_MESSAGE";
    static final String LOG_CALLBACK_BAD_BUTTON = "FUNNEL_CALLBACK_BUTTON_NOT_CALLBACK_OR_OUT_OF_RANGE";
    static final String LOG_CALLBACK_ADVANCED = "FUNNEL_CALLBACK_ADVANCED";
    static final String LOG_CALLBACK_NO_OP = "FUNNEL_CALLBACK_NO_OP";
    static final String LOG_CALLBACK_ERROR = "FUNNEL_CALLBACK_ERROR";

    static final String EVENT_FUNNEL_BUTTON_CLICKED = "funnel_button_clicked";

    // Strict callback_data parse (Decision 6): the left segment must be a 24-char ObjectId hex (the
    // execution id shape) and the right an unsigned, bounded button index. Checked BEFORE any DB lookup.
    // MAX_BUTTON_INDEX mirrors the editor's 8-button cap (indexes 0..7); the per-step buttons.size() is the
    // authoritative bound enforced after the snapshot resolves. MAX_CALLBACK_DATA_BYTES is Telegram's hard
    // 64-byte ceiling — oversized data is rejected up front (cannot be a value we ever minted).
    private static final Pattern OBJECT_ID_HEX = Pattern.compile("^[0-9a-fA-F]{24}$");
    private static final int MAX_BUTTON_INDEX = 7;
    private static final int MAX_CALLBACK_DATA_BYTES = 64;

    private final BotRepository botRepository;
    private final SubscriberService subscriberService;
    private final FunnelRepository funnelRepository;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;
    private final TelegramSender telegramSender;
    private final EventService eventService;
    private final FunnelExecutionEngine executionEngine;
    private final FunnelExecutionFactory executionFactory;

    public FunnelTriggerServiceImpl(BotRepository botRepository,
                                    SubscriberService subscriberService,
                                    FunnelRepository funnelRepository,
                                    MongoTemplate mongoTemplate,
                                    Clock clock,
                                    TelegramSender telegramSender,
                                    EventService eventService,
                                    FunnelExecutionEngine executionEngine,
                                    FunnelExecutionFactory executionFactory) {
        this.botRepository = botRepository;
        this.subscriberService = subscriberService;
        this.funnelRepository = funnelRepository;
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
        this.telegramSender = telegramSender;
        this.eventService = eventService;
        this.executionEngine = executionEngine;
        this.executionFactory = executionFactory;
    }

    @Override
    public void fire(String projectId, Long chatId, String triggerType, String payload) {
        try {
            // Step 1: resolve the project's CONNECTED bot (mirrors the webhook worker) and pin its id.
            Bot bot = botRepository.findByProjectIdAndStatus(projectId, BotStatus.CONNECTED).orElse(null);
            if (bot == null) {
                log.info("{} projectId={}", LOG_FIRE_NO_BOT, projectId);
                return;
            }
            Long telegramBotId = bot.getTelegramBotId();

            // Step 2: resolve the subscriber by (projectId, telegramBotId, chatId) via the public
            // SubscriberService lookup — NEVER the repository directly (keeps the funnel→subscriber
            // module boundary).
            Subscriber subscriber = subscriberService.findByChat(projectId, telegramBotId, chatId).orElse(null);
            if (subscriber == null) {
                log.info("{} projectId={}", LOG_FIRE_NO_SUBSCRIBER, projectId);
                return;
            }

            // Step 3: exact-match the active funnel whose triggers[] contains an on_start element with this
            // payload. An empty payload matches an on_start element with an empty triggerValue (bare /start).
            // No match → no-op. The enrolment enters the on_start node's DRAWN edge (entryStepId, reused
            // field — 18-funnel-canvas / Decision 5) instead of the hard step 0; a null/dangling entryStepId
            // degrades to step 0 in FunnelExecutionFactory.resolveStartCursor (defence-in-depth). Never a
            // redirect.
            //
            // Phase 8 deviation from the literal task wording: the task says "move to onStartTriggerValue
            // (findByProjectIdAndOnStartTriggerValueAndStatus)", but that denormalized scalar is NULL for a
            // bare /start ("" syncs to null so bare-start funnels stay out of the partial-unique index —
            // Variant A), and its repository method forbids a null argument (it would match every event-only
            // funnel). A bare /start MUST still resolve its funnel, so the lookup uses the array-aware
            // $elemMatch query over triggers[] on (on_start, payload) — exact old semantics for both bare ""
            // and a deep-link payload. fan-out is irrelevant here: at most one active on_start funnel per
            // (project, payload) is the invariant; .findFirst() keeps the single-result contract.
            // .findFirst() is safe (no silent drop of a second match): on_start uniqueness is enforced by the
            // onStartTriggerValue unique partial index (Decision 6 / FunnelTriggerIndexReconciliation), so at
            // most one active on_start funnel exists per (projectId, payload) — the list never carries >1 row.
            String triggerValue = payload == null ? "" : payload;
            Funnel funnel = funnelRepository
                    .findByProjectIdAndTriggersTriggerTypeAndTriggersTriggerValueAndStatus(
                            projectId, triggerType, triggerValue, FunnelStatus.active)
                    .stream().findFirst().orElse(null);
            if (funnel == null) {
                log.info("{} projectId={} triggerType={}", LOG_FIRE_NO_MATCH, projectId, triggerType);
                return;
            }

            // Step 4: re-enter guard (Decision 8). on_start roots are depth 0 (Phase 3 / Decision 6) —
            // the depth-aware insert lives behind FunnelExecutionFactory (Task 4: no forked writer, no
            // FunnelEventService → FunnelTriggerServiceImpl bean edge). The execution starts at the on_start
            // node's drawn entryStepId (18-funnel-canvas / Decision 5), not the hard step 0.
            String entryStepId = onStartEntryStepId(funnel);
            if (funnel.isAllowReEnter()) {
                executionFactory.cancelExistingForPair(projectId, funnel.getId(), subscriber.getId());
                executionFactory.insertExecutionAt(projectId, funnel, subscriber.getId(), telegramBotId, 0,
                        entryStepId);
                log.info("{} funnelId={} subscriberId={}", LOG_FIRE_REENTER_RESTARTED,
                        funnel.getId(), subscriber.getId());
                return;
            }
            try {
                executionFactory.insertExecutionAt(projectId, funnel, subscriber.getId(), telegramBotId, 0,
                        entryStepId);
            } catch (DuplicateKeyException dup) {
                // Re-enter disabled: the unique partial index already has a running|waiting execution for
                // this (funnelId, subscriberId). Repeated /start is an atomic no-op — swallow.
                log.info("{} funnelId={} subscriberId={}", LOG_FIRE_REENTER_IGNORED,
                        funnel.getId(), subscriber.getId());
            }
        } catch (Throwable t) {
            // Decision 6: fire() never throws outward — a funnel fault must not fail the webhook update.
            log.warn("{} projectId={} error={}", LOG_FIRE_ERROR, projectId, t.getClass().getSimpleName());
        }
    }

    @Override
    public void cancelActiveFor(String projectId, Long chatId) {
        try {
            Bot bot = botRepository.findByProjectIdAndStatus(projectId, BotStatus.CONNECTED).orElse(null);
            if (bot == null) {
                log.info("{} projectId={}", LOG_CANCEL_NO_BOT, projectId);
                return;
            }
            Subscriber subscriber = subscriberService
                    .findByChat(projectId, bot.getTelegramBotId(), chatId).orElse(null);
            if (subscriber == null) {
                log.info("{} projectId={}", LOG_CANCEL_NO_SUBSCRIBER, projectId);
                return;
            }
            // Transition every running|waiting execution of this subscriber to cancelled. Scoped by
            // projectId too (fail-closed tenant hardening), statuses as lowercase .name() literals
            // (Decision 14).
            Instant now = Instant.now(clock);
            mongoTemplate.updateMulti(
                    Query.query(Criteria.where("projectId").is(projectId)
                            .and("subscriberId").is(subscriber.getId())
                            .and("status").in(ExecutionStatus.running.name(), ExecutionStatus.waiting.name(),
                                ExecutionStatus.waiting_for_reply.name())),
                    new Update()
                            .set("status", ExecutionStatus.cancelled.name())
                            .set("stepRunStatus", StepRunStatus.done.name())
                            .set("updatedAt", now),
                    FunnelExecution.class);
            log.info("{} projectId={} subscriberId={}", LOG_CANCEL_DONE, projectId, subscriber.getId());
        } catch (Throwable t) {
            log.warn("{} projectId={} error={}", LOG_CANCEL_ERROR, projectId, t.getClass().getSimpleName());
        }
    }

    @Override
    public void advanceOnCallback(String projectId, Long chatId, String callbackData, String callbackQueryId) {
        // botId is captured as soon as the CONNECTED bot resolves so the best-effort answerCallbackQuery
        // can fire on EVERY no-op path below (Decision 8). It stays null only when no CONNECTED bot exists
        // — the one path where the spinner genuinely cannot be cleared (no token to ack with).
        String botId = null;
        try {
            // Step 1: resolve the project's CONNECTED bot (mirrors fire()). No bot → no token to ack with
            // → silent no-op (the only path that skips answerCallbackQuery, by necessity).
            Bot bot = botRepository.findByProjectIdAndStatus(projectId, BotStatus.CONNECTED).orElse(null);
            if (bot == null) {
                log.info("{} projectId={}", LOG_CALLBACK_NO_BOT, projectId);
                return;
            }
            botId = bot.getId();
            Long telegramBotId = bot.getTelegramBotId();

            // Step 2: STRICT parse of callback_data "{executionId}:{buttonIndex}" BEFORE any DB lookup
            // (Decision 6). Malformed / oversized / extra-segment / non-hex / out-of-shape → reject (no
            // advance) but the spinner is still cleared in finally.
            ParsedCallback parsed = parseCallbackData(callbackData);
            if (parsed == null) {
                log.info("{} projectId={}", LOG_CALLBACK_MALFORMED, projectId);
                return;
            }

            // Step 3: resolve the subscriber via the public boundary (never the repository directly).
            Subscriber subscriber = subscriberService.findByChat(projectId, telegramBotId, chatId).orElse(null);
            if (subscriber == null) {
                log.info("{} projectId={}", LOG_CALLBACK_NO_SUBSCRIBER, projectId);
                return;
            }

            // Step 4: load the parked execution by id. null → no-op (stale/forged id with valid shape).
            FunnelExecution exec = mongoTemplate.findById(parsed.executionId(), FunnelExecution.class);
            if (exec == null) {
                log.info("{} projectId={}", LOG_CALLBACK_NO_EXECUTION, projectId);
                return;
            }

            // Step 5: owner authorization (anti-IDOR, Decision 6). The execution must belong to THIS
            // subscriber AND project — a legitimate project subscriber could otherwise forge a
            // callback_data with another subscriber's executionId. Mismatch → silent no-op. The CAS in
            // resumeOnCallback is scoped by subscriberId as a second, atomic layer.
            if (!subscriber.getId().equals(exec.getSubscriberId())
                    || !projectId.equals(exec.getProjectId())) {
                log.info("{} projectId={}", LOG_CALLBACK_IDOR, projectId);
                return;
            }

            // Step 6: the execution must be parked waiting_for_reply (a prior advance / cancel / complete
            // already moved it → stale → no-op).
            if (exec.getStatus() != ExecutionStatus.waiting_for_reply) {
                log.info("{} projectId={}", LOG_CALLBACK_NOT_WAITING, projectId);
                return;
            }

            // Step 7: the current cursor must point at a MESSAGE composer step in the snapshot (Decision 2 —
            // positive guard, replacing the former "must be a MENU step": only a MESSAGE step parks
            // waiting_for_reply, so a null cursor or any non-MESSAGE step → no-op, fail-closed). The
            // inline keyboard is attached by StepExecutor to the last non-album block of this composer step;
            // here that block only matters as the invariant "this step carries the parked keyboard" — the
            // button index-addressing is resolved from the step-level getButtons() (Decision 2), unchanged.
            FunnelStep step = stepById(exec.getStepsSnapshot(), exec.getCurrentStepId());
            if (step == null || step.getStepType() != StepType.MESSAGE) {
                log.info("{} projectId={}", LOG_CALLBACK_STEP_MISMATCH, projectId);
                return;
            }

            // Step 8: the button index must be in range AND a callback-type button (a URL button or an
            // out-of-range index does NOT advance the funnel — Decision 10). Index-addressing is unchanged:
            // buttons stay at the step level (Decision 2), the keyboard is pinned to the composer's last
            // non-album block but the 0-based index maps onto getButtons() exactly as before.
            List<Button> buttons = step.getButtons();
            int idx = parsed.buttonIndex();
            if (buttons == null || idx >= buttons.size()) {
                log.info("{} projectId={}", LOG_CALLBACK_BAD_BUTTON, projectId);
                return;
            }
            Button button = buttons.get(idx);
            if (button == null || !"callback".equals(button.type())) {
                log.info("{} projectId={}", LOG_CALLBACK_BAD_BUTTON, projectId);
                return;
            }

            // Step 9: delegate to the engine's callback-resume (claim-CAS scoped by subscriberId). target
            // = button.targetStepId() (null = "End"). Only when the claim is WON (double click loses the
            // second CAS) do we record the analytics event + lastButtonClicked — so a double click never
            // duplicates the event. resumeOnCallback NEVER throws outward; it returns true iff it won.
            String funnelId = exec.getFunnelId();
            String currentStepId = exec.getCurrentStepId();
            boolean advanced = executionEngine.resumeOnCallback(
                    exec.getId(), subscriber.getId(), button.targetStepId());
            if (advanced) {
                recordButtonClick(subscriber.getId(), projectId, funnelId, exec.getId(), currentStepId, idx);
                log.info("{} funnelId={} executionId={} buttonIndex={}", LOG_CALLBACK_ADVANCED,
                        funnelId, exec.getId(), idx);
            } else {
                // Lost the CAS (double click / concurrent cancel / no longer parked) → no event, no
                // lastButtonClicked. Benign no-op.
                log.info("{} projectId={}", LOG_CALLBACK_NO_OP, projectId);
            }
        } catch (Throwable t) {
            // Decision 6/error-isolation: advanceOnCallback never throws outward — a callback fault must
            // not poison the webhook dispatch.
            log.warn("{} projectId={} error={}", LOG_CALLBACK_ERROR, projectId, t.getClass().getSimpleName());
        } finally {
            // answerCallbackQuery is called on EVERY exit path where the bot resolved (Decision 8):
            // success, stale, malformed, oversized, foreign, URL-button, null-lookup, AND the catch above.
            // Best-effort with its own swallow inside TelegramSender (5xx → WARN, never blocks advance);
            // this guard only skips when there is no bot (hence no token) to ack with.
            answerCallbackBestEffort(botId, callbackQueryId);
        }
    }

    // Best-effort spinner ack (Decision 8). TelegramSender.answerCallbackQuery already swallows its own
    // RuntimeExceptions (token-scrubbed WARN); this extra guard defends against a non-RuntimeException
    // Throwable so a failed ack can never escape advanceOnCallback's finally. No-op when botId is null
    // (no CONNECTED bot resolved — nothing to ack with). text=null → no toast, just clear the spinner.
    private void answerCallbackBestEffort(String botId, String callbackQueryId) {
        if (botId == null) {
            return;
        }
        try {
            telegramSender.answerCallbackQuery(botId, callbackQueryId, null);
        } catch (Throwable ignored) {
            // Already swallowed inside the sender; belt-and-suspenders so finally never throws.
        }
    }

    // Strict callback_data parse (Decision 6). Returns null (reject) on: null/blank input, oversized
    // (> 64 bytes), not EXACTLY one ':' separator, a left segment that is not a 24-char ObjectId hex, or a
    // right segment that is not a non-negative integer within 0..MAX_BUTTON_INDEX. All shape checks happen
    // here, before any DB lookup. The buttons.size() upper bound is enforced by the caller once the
    // snapshot resolves.
    private static ParsedCallback parseCallbackData(String callbackData) {
        if (callbackData == null) {
            return null;
        }
        if (callbackData.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CALLBACK_DATA_BYTES) {
            return null;
        }
        // EXACTLY one ':' — split with a hard limit so extra segments are caught (limit=-1 keeps trailing
        // empties so "a:b:" / "a:" / ":b" all yield != 2 parts → reject).
        String[] parts = callbackData.split(":", -1);
        if (parts.length != 2) {
            return null;
        }
        String execId = parts[0];
        if (!OBJECT_ID_HEX.matcher(execId).matches()) {
            return null;
        }
        int index;
        try {
            index = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (index < 0 || index > MAX_BUTTON_INDEX) {
            return null;
        }
        return new ParsedCallback(execId, index);
    }

    // Parsed, shape-validated callback_data: the 24-hex execution id + a 0..MAX_BUTTON_INDEX button index.
    private record ParsedCallback(String executionId, int buttonIndex) {}

    // Linear lookup by stable step id over the (small, <=50) snapshot. Mirrors FunnelExecutionEngine's
    // private stepById — duplicated here rather than exposed because the engine keeps it package-private
    // static and the two modules navigate independently. null id or no match → null.
    // The matched funnel's on_start node drawn-edge entryStepId (18-funnel-canvas / Decision 5), or null when
    // there is no on_start element or its edge is absent/deleted. Scans triggers[] by TRIGGER_ON_START
    // (mirrors FunnelService.syncOnStartTriggerValue). A null/dangling id degrades to step 0 in
    // FunnelExecutionFactory.resolveStartCursor (defence-in-depth) — never strands the cursor.
    private static String onStartEntryStepId(Funnel funnel) {
        if (funnel.getTriggers() == null) {
            return null;
        }
        for (Trigger t : funnel.getTriggers()) {
            if (t != null && FunnelService.TRIGGER_ON_START.equals(t.getTriggerType())) {
                return t.getEntryStepId();
            }
        }
        return null;
    }

    private static FunnelStep stepById(List<FunnelStep> snapshot, String id) {
        if (snapshot == null || id == null) {
            return null;
        }
        for (FunnelStep s : snapshot) {
            if (id.equals(s.getId())) {
                return s;
            }
        }
        return null;
    }

    // funnel_button_clicked analytics event (Decision 9): ids/codes ONLY — no from.first_name / username /
    // message text. Written once, only after a WON claim, so a double click never duplicates it. Also
    // stamps lastButtonClicked on the execution (best-effort secondary write — the advance already
    // committed via the engine's CAS). EventService.logEvent throws on persistence failure; the outer
    // try/catch(Throwable) isolates it so a logging fault never poisons the (already-committed) advance.
    private void recordButtonClick(String subscriberId, String projectId, String funnelId,
                                   String executionId, String currentStepId, int buttonIndex) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("projectId", projectId);
        metadata.put("funnelId", funnelId);
        metadata.put("executionId", executionId);
        metadata.put("subscriberId", subscriberId);
        metadata.put("currentStepId", currentStepId);
        metadata.put("buttonIndex", buttonIndex);
        eventService.logEvent(subscriberId, EVENT_FUNNEL_BUTTON_CLICKED, null, null, metadata);
        // lastButtonClicked = the clicked button's stable coordinate (menu step id + index), ids only.
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(executionId)),
                new Update().set("lastButtonClicked", currentStepId + ":" + buttonIndex),
                FunnelExecution.class);
    }

}
