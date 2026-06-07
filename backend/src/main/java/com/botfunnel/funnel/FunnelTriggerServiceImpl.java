package com.botfunnel.funnel;

import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    static final String LOG_FIRE_STARTED = "FUNNEL_FIRE_EXECUTION_STARTED";
    static final String LOG_FIRE_REENTER_IGNORED = "FUNNEL_FIRE_REENTER_IGNORED";
    static final String LOG_FIRE_REENTER_RESTARTED = "FUNNEL_FIRE_REENTER_RESTARTED";
    static final String LOG_FIRE_ERROR = "FUNNEL_FIRE_ERROR";
    static final String LOG_CANCEL_NO_BOT = "FUNNEL_CANCEL_SKIP_NO_CONNECTED_BOT";
    static final String LOG_CANCEL_NO_SUBSCRIBER = "FUNNEL_CANCEL_SKIP_NO_SUBSCRIBER";
    static final String LOG_CANCEL_DONE = "FUNNEL_CANCEL_ACTIVE";
    static final String LOG_CANCEL_ERROR = "FUNNEL_CANCEL_ERROR";

    private final BotRepository botRepository;
    private final SubscriberService subscriberService;
    private final FunnelRepository funnelRepository;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    public FunnelTriggerServiceImpl(BotRepository botRepository,
                                    SubscriberService subscriberService,
                                    FunnelRepository funnelRepository,
                                    MongoTemplate mongoTemplate,
                                    Clock clock) {
        this.botRepository = botRepository;
        this.subscriberService = subscriberService;
        this.funnelRepository = funnelRepository;
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
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

            // Step 3: exact-match the active funnel on (triggerType, triggerValue == payload). An empty
            // payload matches a funnel with an empty triggerValue. No match → no-op.
            String triggerValue = payload == null ? "" : payload;
            Funnel funnel = funnelRepository
                    .findByProjectIdAndTriggerTypeAndTriggerValueAndStatus(
                            projectId, triggerType, triggerValue, FunnelStatus.active)
                    .orElse(null);
            if (funnel == null) {
                log.info("{} projectId={} triggerType={}", LOG_FIRE_NO_MATCH, projectId, triggerType);
                return;
            }

            // Step 4: re-enter guard (Decision 8).
            if (funnel.isAllowReEnter()) {
                cancelExistingForPair(projectId, funnel.getId(), subscriber.getId());
                insertExecution(projectId, funnel, subscriber.getId(), telegramBotId);
                log.info("{} funnelId={} subscriberId={}", LOG_FIRE_REENTER_RESTARTED,
                        funnel.getId(), subscriber.getId());
                return;
            }
            try {
                insertExecution(projectId, funnel, subscriber.getId(), telegramBotId);
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

    // Atomically cancel the existing running|waiting execution for the (funnelId, subscriberId) pair so
    // the partial-unique index frees up before the fresh insert (allowReEnter=true). updateMulti is
    // defensive — the unique index guarantees at most one such row.
    private void cancelExistingForPair(String projectId, String funnelId, String subscriberId) {
        Instant now = Instant.now(clock);
        mongoTemplate.updateMulti(
                Query.query(Criteria.where("projectId").is(projectId)
                        .and("funnelId").is(funnelId)
                        .and("subscriberId").is(subscriberId)
                        .and("status").in(ExecutionStatus.running.name(), ExecutionStatus.waiting.name(),
                                ExecutionStatus.waiting_for_reply.name())),
                new Update()
                        .set("status", ExecutionStatus.cancelled.name())
                        .set("stepRunStatus", StepRunStatus.done.name())
                        .set("updatedAt", now),
                FunnelExecution.class);
    }

    // Build and insert a fresh execution from step 0 with a deep-copy steps snapshot (Decision 3) and the
    // pinned telegramBotId (Decision 7). Uses MongoTemplate.insert so a unique-index collision surfaces as
    // DuplicateKeyException (the re-enter guard for allowReEnter=false).
    private void insertExecution(String projectId, Funnel funnel, String subscriberId, Long telegramBotId) {
        Instant now = Instant.now(clock);
        FunnelExecution execution = new FunnelExecution();
        execution.setProjectId(projectId);
        execution.setFunnelId(funnel.getId());
        execution.setSubscriberId(subscriberId);
        execution.setTelegramBotId(telegramBotId);
        execution.setStatus(ExecutionStatus.running);
        execution.setCurrentStepIndex(0);
        execution.setStepRunStatus(StepRunStatus.pending);
        execution.setNextRunAt(now);
        List<FunnelStep> snapshot = deepCopySteps(funnel.getSteps());
        execution.setStepsSnapshot(snapshot);
        // Seed the graph cursor (Decision 2/7) to the first step's id so the engine navigates by
        // currentStepId from the start. null-safe for an empty snapshot. currentStepIndex stays 0 for
        // drain compatibility.
        execution.setCurrentStepId(snapshot.isEmpty() ? null : snapshot.get(0).getId());
        execution.setCreatedAt(now);
        execution.setUpdatedAt(now);
        mongoTemplate.insert(execution);
        log.info("{} funnelId={} subscriberId={} executionId={}", LOG_FIRE_STARTED,
                funnel.getId(), subscriberId, execution.getId());
    }

    // Deep copy of the funnel's steps via FunnelStep.copyOf (Decision 3 — snapshot isolation from later
    // funnel edits). null steps → empty snapshot.
    private static List<FunnelStep> deepCopySteps(List<FunnelStep> steps) {
        List<FunnelStep> snapshot = new ArrayList<>();
        if (steps != null) {
            for (FunnelStep step : steps) {
                snapshot.add(FunnelStep.copyOf(step));
            }
        }
        return snapshot;
    }
}
