package com.botfunnel.funnel;

import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Internal subscriber-keyed fan-out dispatcher (Phase 3 / Decision 2) — the single entrypoint every
 * Phase-3 trigger source calls: {@code keyword} (webhook, Task 6), {@code tag_added} /
 * {@code custom_field_set} (subscriber writers, Task 5), and the shared {@code event} namespace fed by
 * the public {@code /events} API (Task 7) and the {@code EMIT_EVENT} step (Task 5).
 *
 * <p>Deliberately named distinct from the {@code events} audit collection and the
 * {@code subscriber_events} CRM feed (Decision 4): it is an in-process dispatcher, not a bus anything
 * reads back.
 *
 * <p><strong>Fan-out.</strong> Unlike {@code FunnelTriggerServiceImpl.fire} (chatId-keyed,
 * single-result), one firing here can match N active funnels sharing a trigger value and start N
 * executions for the one subscriber (Decision 1, enabled by the Task-1 index relax). Each created
 * execution carries {@code enrollDepth = originDepth}.
 *
 * <p><strong>Three independent loop backstops</strong> (Decision 6) bound runaway cascades
 * (tag→funnel→tag, {@code EMIT_EVENT} fan-out, A→B→A):
 * <ol>
 *   <li><em>Volume</em> — per-subscriber auto-enroll rate-limit (Redis fail-open, counts {@code depth>0}
 *       enrolls only; {@code depth==0} human/external roots never touch Redis).</li>
 *   <li><em>Depth</em> — Redis-independent {@code enrollDepth} cap (drop when {@code originDepth > cap}),
 *       which holds even when Redis is down.</li>
 *   <li><em>Fan-out width</em> — Redis-independent per-dispatch ceiling
 *       ({@code app.funnel.max-fanout-per-event}); excess matched funnels are dropped.</li>
 * </ol>
 *
 * <p><strong>Error-isolated</strong> (Decision 12): the whole {@code dispatchForSubscriber} body is
 * {@code try/catch(Throwable)} — a fault is logged (greppable WARN) and swallowed; it NEVER throws
 * outward (a dispatch fault must not become a 5xx, a failed webhook job, or a JobRunr retry-storm).
 *
 * <p><strong>No bean cycle.</strong> Execution creation reuses {@link FunnelExecutionFactory} (Decision
 * 2 — no forked writer). This service has ZERO dependency on {@code FunnelTriggerServiceImpl}; both
 * inject the factory. The factory has no edge into {@code FunnelExecutionEngine}/{@code StepExecutor},
 * so the {@code StepExecutor → FunnelEventService} edge Task 5 adds cannot close a cycle.
 *
 * <p>Logging is ids/codes only — never the {@code matchKey}, keyword text, or any subscriber field
 * (Decision 16 PII rule).
 */
@Service
public class FunnelEventService {

    private static final Logger log = LoggerFactory.getLogger(FunnelEventService.class);

    // Public trigger-type constants — the cross-package dispatch contract. Cross-package callers
    // (SubscriberServiceImpl tag/field hooks, StepExecutor EMIT_EVENT, EventsController) pass these to
    // dispatchForSubscriber's triggerType param instead of re-typing the literals. Mirror the
    // package-private FunnelService.TRIGGER_* values (same module, single source of truth).
    public static final String TRIGGER_KEYWORD = FunnelService.TRIGGER_KEYWORD;
    public static final String TRIGGER_TAG_ADDED = FunnelService.TRIGGER_TAG_ADDED;
    public static final String TRIGGER_CUSTOM_FIELD_SET = FunnelService.TRIGGER_CUSTOM_FIELD_SET;
    public static final String TRIGGER_EVENT = FunnelService.TRIGGER_EVENT;

    // Greppable markers — identifiers/codes only (Decision 16), mirroring FunnelTriggerServiceImpl's
    // LOG_* convention. One per no-op path and one per backstop drop.
    static final String LOG_DISPATCH_NO_BOT = "FUNNEL_DISPATCH_SKIP_NO_CONNECTED_BOT";
    static final String LOG_DISPATCH_NO_SUBSCRIBER = "FUNNEL_DISPATCH_SKIP_NO_SUBSCRIBER";
    static final String LOG_DISPATCH_NO_MATCH = "FUNNEL_DISPATCH_NO_MATCHING_FUNNEL";
    static final String LOG_DISPATCH_REENTER_IGNORED = "FUNNEL_DISPATCH_REENTER_IGNORED";
    static final String LOG_DISPATCH_ERROR = "FUNNEL_DISPATCH_ERROR";
    static final String LOG_DROP_DEPTH_CAP = "FUNNEL_DISPATCH_DROP_DEPTH_CAP_EXCEEDED";
    static final String LOG_DROP_VOLUME = "FUNNEL_DISPATCH_DROP_AUTO_ENROLL_RATE_EXCEEDED";
    static final String LOG_DROP_FANOUT = "FUNNEL_DISPATCH_DROP_FANOUT_CEILING_EXCEEDED";
    static final String LOG_AUTO_ENROLL_RATE_REDIS_FAIL_OPEN =
            "FUNNEL_DISPATCH_AUTO_ENROLL_RATE_REDIS_FAIL_OPEN";

    // Phase 8 (17-funnel-multi-entry / Task 5) redirect-vs-start markers — ids/codes only (Decision 16),
    // never the event_name/triggerValue. One per redirect decision, one when the matched element cannot be
    // re-scanned (defensive — the multikey query matched but the in-memory triggers[] does not), and one
    // when a single funnel's redirect/start branch faults inside the otherwise error-isolated fan-out.
    static final String LOG_DISPATCH_REDIRECT = "FUNNEL_DISPATCH_REDIRECT";
    static final String LOG_DISPATCH_NO_MATCHED_ELEMENT = "FUNNEL_DISPATCH_NO_MATCHED_TRIGGER_ELEMENT";
    static final String LOG_DISPATCH_FUNNEL_ERROR = "FUNNEL_DISPATCH_FUNNEL_ERROR";

    // Phase 5 (composition) — SUBSCRIBE_TO_FUNNEL enroll markers. One distinct, greppable id per skip
    // reason so an operator can tell apart a missing/foreign/inactive target, a re-enter no-op, an
    // entry-step fallback, the two reused backstops (depth-cap / rate-limit), and a swallowed fault.
    // Ids/codes only (Decision 16) — never the target name or any subscriber field.
    static final String LOG_ENROLL_SKIP_TARGET_MISSING = "FUNNEL_ENROLL_SKIP_TARGET_MISSING";
    static final String LOG_ENROLL_SKIP_TARGET_WRONG_PROJECT = "FUNNEL_ENROLL_SKIP_TARGET_WRONG_PROJECT";
    static final String LOG_ENROLL_SKIP_TARGET_INACTIVE = "FUNNEL_ENROLL_SKIP_TARGET_INACTIVE";
    static final String LOG_ENROLL_DROP_DEPTH_CAP = "FUNNEL_ENROLL_DROP_DEPTH_CAP_EXCEEDED";
    static final String LOG_ENROLL_DROP_VOLUME = "FUNNEL_ENROLL_DROP_AUTO_ENROLL_RATE_EXCEEDED";
    static final String LOG_ENROLL_REENTER_IGNORED = "FUNNEL_ENROLL_REENTER_IGNORED";
    static final String LOG_ENROLL_ENTRY_STEP_FALLBACK = "FUNNEL_ENROLL_ENTRY_STEP_FALLBACK";
    static final String LOG_ENROLL_ERROR = "FUNNEL_ENROLL_ERROR";

    private static final String RATE_KEY_PREFIX = "bf:rate:auto-enroll:";
    private static final Duration RATE_TTL = Duration.ofMinutes(1);

    private final BotRepository botRepository;
    private final SubscriberService subscriberService;
    private final FunnelRepository funnelRepository;
    private final FunnelExecutionFactory executionFactory;
    private final FunnelExecutionEngine executionEngine;
    private final MongoTemplate mongoTemplate;
    private final StringRedisTemplate redisTemplate;
    private final int maxFanoutPerEvent;
    private final int autoEnrollRatePerMin;
    private final int maxEnrollDepth;

    public FunnelEventService(BotRepository botRepository,
                              SubscriberService subscriberService,
                              FunnelRepository funnelRepository,
                              FunnelExecutionFactory executionFactory,
                              // @Lazy breaks the REAL bean cycle this Task-5 edge closes:
                              // FunnelEventService → FunnelExecutionEngine → StepExecutor → FunnelEventService.
                              // Spring injects a deferred proxy and resolves the engine on first use, so no
                              // BeanCurrentlyInCreationException at context startup. (The execution-creation
                              // path stays on FunnelExecutionFactory, which has no edge into the engine — see
                              // its class-doc — so START never needs the engine; only REDIRECT does.)
                              @Lazy FunnelExecutionEngine executionEngine,
                              MongoTemplate mongoTemplate,
                              StringRedisTemplate redisTemplate,
                              @Value("${app.funnel.max-fanout-per-event}") int maxFanoutPerEvent,
                              @Value("${app.funnel.auto-enroll-rate-per-min}") int autoEnrollRatePerMin,
                              @Value("${app.funnel.max-enroll-depth}") int maxEnrollDepth) {
        this.botRepository = botRepository;
        this.subscriberService = subscriberService;
        this.funnelRepository = funnelRepository;
        this.executionFactory = executionFactory;
        this.executionEngine = executionEngine;
        this.mongoTemplate = mongoTemplate;
        this.redisTemplate = redisTemplate;
        this.maxFanoutPerEvent = maxFanoutPerEvent;
        this.autoEnrollRatePerMin = autoEnrollRatePerMin;
        this.maxEnrollDepth = maxEnrollDepth;
    }

    /**
     * Dispatch a Phase-3 trigger for one subscriber, fanning out to every active matching funnel
     * (subject to the three backstops). NEVER throws outward (error-isolated).
     *
     * @param projectId    the project the firing belongs to (already trusted/pinned by the caller)
     * @param subscriberId the subscriber to enroll (resolved project-scoped via the service boundary)
     * @param triggerType  one of {@code keyword} / {@code tag_added} / {@code custom_field_set} / {@code event}
     * @param matchKey      tag slug / field key / event_name for exact triggers; the message text for keyword
     * @param originDepth  0 for human/external roots; {@code parent.enrollDepth + 1} for auto children
     */
    public void dispatchForSubscriber(String projectId, String subscriberId, String triggerType,
                                      String matchKey, int originDepth) {
        try {
            // Backstop (b) Depth cap — Redis-independent, gated up front before any insert. Holds even
            // when Redis is down (closes the fail-open hole the volume limit leaves during an outage).
            if (originDepth > maxEnrollDepth) {
                log.warn("{} projectId={} originDepth={} cap={}", LOG_DROP_DEPTH_CAP,
                        projectId, originDepth, maxEnrollDepth);
                return;
            }

            // Backstop (a) Volume — per-subscriber auto-enroll rate-limit. Only auto enrolls (depth>0)
            // count; human/external roots (depth==0) are exempt and never even consult Redis (Decision
            // 6 / volume-limit exemption).
            if (originDepth > 0 && autoEnrollRateLimitExceeded(subscriberId)) {
                log.warn("{} projectId={} subscriberId={} originDepth={}", LOG_DROP_VOLUME,
                        projectId, subscriberId, originDepth);
                return;
            }

            // Resolve the project's CONNECTED bot to pin telegramBotId on created executions (mirror
            // fire()'s bot resolution). No connected bot → WARN no-op.
            Bot bot = botRepository.findByProjectIdAndStatus(projectId, BotStatus.CONNECTED).orElse(null);
            if (bot == null) {
                log.warn("{} projectId={}", LOG_DISPATCH_NO_BOT, projectId);
                return;
            }
            Long telegramBotId = bot.getTelegramBotId();

            // Resolve the subscriber by id via the service boundary (project-scoped, anti-IDOR). Never
            // reach into SubscriberRepository from the funnel package. Missing → WARN no-op.
            Subscriber subscriber = subscriberService.findById(projectId, subscriberId).orElse(null);
            if (subscriber == null) {
                log.warn("{} projectId={}", LOG_DISPATCH_NO_SUBSCRIBER, projectId);
                return;
            }

            // Query the LIST of active matching funnels (Task-1 queries, read-only consumer).
            List<Funnel> matches = matchingFunnels(projectId, triggerType, matchKey);
            if (matches.isEmpty()) {
                log.info("{} projectId={} triggerType={}", LOG_DISPATCH_NO_MATCH, projectId, triggerType);
                return;
            }

            // Fan-out loop, bounded by backstop (c) the per-dispatch fan-out ceiling. A per-funnel
            // DuplicateKeyException (re-enter guard) is swallowed so one duplicate cannot abort the
            // other matched funnels (Decision 8 / re-enter interplay).
            int handled = 0;
            for (Funnel funnel : matches) {
                if (handled >= maxFanoutPerEvent) {
                    int dropped = matches.size() - handled;
                    log.warn("{} projectId={} ceiling={} dropped={}", LOG_DROP_FANOUT,
                            projectId, maxFanoutPerEvent, dropped);
                    break;
                }
                // The fan-out ceiling counts every funnel we ACT on (redirect OR start), not only fresh
                // inserts — a redirect is still per-dispatch work that must be bounded (Decision 6c). The
                // decision is made INDEPENDENTLY per matched funnel (one event, two funnels → one may
                // redirect, the other start). Each branch is error-isolated so a single funnel's fault
                // never aborts the rest of the fan-out (Decision 12).
                if (redirectOrStart(projectId, funnel, triggerType, matchKey,
                        subscriber.getId(), telegramBotId, originDepth)) {
                    handled++;
                }
            }
        } catch (Throwable t) {
            // Decision 12: dispatchForSubscriber never throws outward — a dispatch fault must not poison
            // the webhook pipeline or surface a 5xx to an external caller.
            log.warn("{} projectId={} error={}", LOG_DISPATCH_ERROR, projectId, t.getClass().getSimpleName());
        }
    }

    // Redirect-or-start decision for ONE matched funnel (Phase 8 / Task 5, Decision 2 + 11). Returns true
    // iff this funnel was ACTED on (redirected or a fresh execution started) so the caller's fan-out ceiling
    // counts it. The whole body is error-isolated (Decision 12) — a fault for one funnel logs a greppable
    // WARN and returns false (do not act / do not abort the rest of the fan-out), it NEVER throws outward.
    //
    // Routing:
    //   - re-scan the funnel's triggers[] for the element that matched (type + value/keyword) to read its
    //     entryStepId (the multikey/$elemMatch query returned the funnel, not the element — Decision 12);
    //   - entryStepId == null (every non-event trigger — keyword/tag/field is start-from-beginning,
    //     Decision 10) → start at step 0 exactly as before (insertOneFunnel);
    //   - entryStepId != null (an event mid-entry trigger) → if the subscriber has an in-flight execution
    //     of THIS funnel (running|waiting|waiting_for_reply) → REDIRECT it onto entryStepId; otherwise →
    //     start a FRESH execution AT entryStepId honouring allowReEnter (insertAtEntryStep).
    private boolean redirectOrStart(String projectId, Funnel funnel, String triggerType, String matchKey,
                                    String subscriberId, Long telegramBotId, int originDepth) {
        try {
            String entryStepId = matchedEntryStepId(funnel, triggerType, matchKey);
            if (entryStepId == null) {
                // Non-event trigger (or an on_start element, which carries no entryStepId): start from step 0.
                // A missing matched element also lands here (entryStepId null) — logged inside the re-scan.
                return insertOneFunnel(projectId, funnel, subscriberId, telegramBotId, originDepth);
            }
            // Event mid-entry: redirect an in-flight execution, else start fresh at the entry step. The
            // executionId comes ONLY from this trusted in-flight probe (never request input); the engine's
            // CAS re-checks (subscriberId, funnelId) as a second anti-IDOR layer (Decision 3).
            String inFlightId = inFlightExecutionId(projectId, funnel.getId(), subscriberId);
            if (inFlightId != null) {
                // The engine's LOG_REDIRECT (info) is the single authoritative record of a WON redirect claim;
                // this dispatch-side line fires BEFORE the CAS even runs (so it cannot assert success) — keep it
                // at debug for cross-class correlation only, not as a second info-level success log (Decision 16).
                log.debug("{} projectId={} funnelId={}", LOG_DISPATCH_REDIRECT, projectId, funnel.getId());
                executionEngine.redirectExecution(inFlightId, subscriberId, funnel.getId(), entryStepId);
                return true;
            }
            return insertAtEntryStep(projectId, funnel, subscriberId, telegramBotId, originDepth, entryStepId);
        } catch (Throwable t) {
            // Per-funnel error isolation (Decision 12): one funnel's redirect/start fault must not abort the
            // rest of the fan-out, nor escape dispatchForSubscriber. ids/codes only.
            log.warn("{} projectId={} funnelId={} error={}", LOG_DISPATCH_FUNNEL_ERROR,
                    projectId, funnel.getId(), t.getClass().getSimpleName());
            return false;
        }
    }

    // Matched-element re-scan (Decision 12 keyword-re-scan idiom): the array-aware fan-out query narrows to
    // funnels whose triggers[] contains a matching element but does NOT say WHICH element matched. Re-scan
    // the funnel's triggers[] in-memory to find that element and return its entryStepId. For an exact
    // trigger (event/tag_added/custom_field_set) the match is triggerType + triggerValue == matchKey; for a
    // keyword trigger it is triggerType + any keyword contained in the (lowercased) matchKey (case-
    // insensitive, any-of-many — same contains-match the query candidates were filtered by). A null/absent
    // element returns null (treated as start-from-step-0) with a greppable WARN — the multikey query said the
    // funnel matched, so a missing in-memory element is an unexpected drift worth surfacing.
    private String matchedEntryStepId(Funnel funnel, String triggerType, String matchKey) {
        // Decision 10: only an `event` trigger carries a mid-entry entryStepId; keyword/tag_added/
        // custom_field_set/on_start are start-from-beginning. Short-circuit so the redirect path is
        // reachable ONLY for event triggers, independent of any persisted-data assumption.
        if (!FunnelService.TRIGGER_EVENT.equals(triggerType)) {
            return null;
        }
        List<Trigger> triggers = funnel.getTriggers();
        if (triggers == null || triggers.isEmpty()) {
            // No triggers to re-scan → start-from-beginning (entryStepId null). Not WARN-worthy on its own.
            return null;
        }
        // event is an EXACT trigger: the matched element is the one whose triggerValue == matchKey
        // (event_name). null matchKey coerced to "" to mirror the query's coercion.
        String exactValue = matchKey == null ? "" : matchKey;
        for (Trigger trigger : triggers) {
            if (trigger == null || !FunnelService.TRIGGER_EVENT.equals(trigger.getTriggerType())) {
                continue;
            }
            if (exactValue.equals(trigger.getTriggerValue() == null ? "" : trigger.getTriggerValue())) {
                return trigger.getEntryStepId();
            }
        }
        // The multikey/$elemMatch query said this funnel matched, but no in-memory element re-scanned —
        // an unexpected drift between the DB query and the loaded triggers[]. Surface it (ids/codes only,
        // never the matchKey) and fall back to start-from-beginning.
        // SECURITY (Decision 16, SEC-T5-001): triggerType is a fixed taxonomy CODE (on_start/event/keyword/
        // tag_added/custom_field_set), safe to log. matchKey/triggerValue/event_name MUST NOT be added to this
        // line — they are business-defined slugs (PII-adjacent) that would leak the internal event taxonomy to
        // log aggregators. Keep this WARN ids/codes only.
        log.warn("{} funnelId={} triggerType={}", LOG_DISPATCH_NO_MATCHED_ELEMENT,
                funnel.getId(), triggerType);
        return null;
    }

    // Trusted in-flight probe (Phase 8 / Task 5) — the executionId for a redirect comes ONLY from here,
    // NEVER from request input. Modeled on ProcessTelegramUpdateJob.hasWaitingForReplyExecution (a DIFFERENT
    // class/package — its idiom is COPIED, not extended), but: (a) a find/findOne (we need the id itself, not
    // a boolean exists), (b) a WIDER status predicate (running|waiting|waiting_for_reply, not just
    // waiting_for_reply), (c) scoped by funnelId, (d) fail-closed by projectId. At most one such row exists
    // for the (funnelId, subscriberId) pair (the unique partial re-enter index, Decision 8). Statuses are
    // lowercase .name() literals (Decision 14). Returns the id, or null when the subscriber is not in-flight
    // on this funnel (absent / completed / cancelled / failed).
    private String inFlightExecutionId(String projectId, String funnelId, String subscriberId) {
        Query query = Query.query(Criteria.where("projectId").is(projectId)
                .and("funnelId").is(funnelId)
                .and("subscriberId").is(subscriberId)
                .and("status").in(ExecutionStatus.running.name(), ExecutionStatus.waiting.name(),
                        ExecutionStatus.waiting_for_reply.name()));
        FunnelExecution exec = mongoTemplate.findOne(query, FunnelExecution.class);
        return exec == null ? null : exec.getId();
    }

    // Start a FRESH execution AT entryStepId (event mid-entry, no in-flight execution), honouring the
    // funnel's re-enter policy — the entry-step analogue of insertOneFunnel. allowReEnter=true →
    // cancel-then-insert-at-entry (restart from the entry step); allowReEnter=false → insert-at-entry in a
    // try and swallow a DuplicateKeyException as a benign re-enter no-op. Returns true iff a fresh execution
    // was actually inserted (a swallowed duplicate → false). (No in-flight row exists here — the probe
    // returned null — but a concurrent insert can still race the unique index, so the swallow is kept.)
    private boolean insertAtEntryStep(String projectId, Funnel funnel, String subscriberId,
                                      Long telegramBotId, int originDepth, String entryStepId) {
        if (funnel.isAllowReEnter()) {
            executionFactory.cancelExistingForPair(projectId, funnel.getId(), subscriberId);
            executionFactory.insertExecutionAt(projectId, funnel, subscriberId, telegramBotId,
                    originDepth, entryStepId);
            return true;
        }
        try {
            executionFactory.insertExecutionAt(projectId, funnel, subscriberId, telegramBotId,
                    originDepth, entryStepId);
            return true;
        } catch (DuplicateKeyException dup) {
            log.info("{} funnelId={} subscriberId={}", LOG_DISPATCH_REENTER_IGNORED,
                    funnel.getId(), subscriberId);
            return false;
        }
    }

    /**
     * Phase 5 (composition) — non-matching enroll for a {@code SUBSCRIBE_TO_FUNNEL} step. Unlike
     * {@link #dispatchForSubscriber} this skips trigger matching and fan-out (the target is named
     * explicitly), but it reuses the SAME per-subscriber backstops (Decision 6) and re-enter semantics so
     * one dispatcher still owns the runaway/DoS limits. NEVER throws outward (error-isolated) — an enroll
     * fault must not fail the parent execution; the engine treats the step as a no-op continue/complete.
     *
     * <p>Order mirrors {@code dispatchForSubscriber}: <b>(a)</b> depth cap → <b>(b)</b> auto-enroll
     * rate-limit → <b>(c)</b> resolve the target and FAIL CLOSED on {@code projectId} (the main IDOR
     * guard: {@link FunnelRepository#findById} is NOT project-scoped) + active-status check → <b>(d)</b>
     * inherited {@code telegramBotId} (no fresh CONNECTED-bot lookup, Decision 9) → <b>(e)</b> entry-step
     * fallback (handled inside the factory) → <b>(f)</b> re-enter policy. Every skip logs a distinct
     * greppable id (ids/codes only, Decision 16) and is a benign no-op.
     *
     * @param projectId         the parent execution's project (trusted/pinned by the engine)
     * @param subscriberId      the subscriber to enroll into the target
     * @param targetFunnelId    the funnel to enroll into (may be null/malformed → skip)
     * @param targetEntryStepId optional entry step inside the target (null / unresolved → start at step 0)
     * @param originDepth       {@code parent.enrollDepth + 1} — the child's depth and the cap/rate input
     * @param telegramBotId     inherited from the parent execution (Decision 9 — one CONNECTED bot/project)
     */
    public void enrollSpecificFunnel(String projectId, String subscriberId, String targetFunnelId,
                                     String targetEntryStepId, int originDepth, Long telegramBotId) {
        try {
            // (a) Depth cap — Redis-independent, gated up front (mirror dispatchForSubscriber). Holds even
            // when Redis is down. Same maxEnrollDepth — reused, not a forked second limit.
            if (originDepth > maxEnrollDepth) {
                log.warn("{} projectId={} originDepth={} cap={}", LOG_ENROLL_DROP_DEPTH_CAP,
                        projectId, originDepth, maxEnrollDepth);
                return;
            }

            // (b) Volume — per-subscriber auto-enroll rate-limit. A SUBSCRIBE_TO_FUNNEL enroll is always an
            // auto child (originDepth = parent + 1 >= 1), so it always counts (unlike depth==0 roots). Same
            // private autoEnrollRateLimitExceeded / Redis key — reused, not duplicated.
            if (autoEnrollRateLimitExceeded(subscriberId)) {
                log.warn("{} projectId={} subscriberId={} originDepth={}", LOG_ENROLL_DROP_VOLUME,
                        projectId, subscriberId, originDepth);
                return;
            }

            // (c) Resolve the target by id (NOT project-scoped — MongoRepository.findById) then FAIL CLOSED
            // on projectId before doing anything else. A null/malformed targetFunnelId yields an empty
            // Optional (or throws, caught below) → skip. This explicit projectId check is the main IDOR
            // guard against a cross-project funnel reference smuggled in via the step.
            Funnel target = targetFunnelId == null
                    ? null
                    : funnelRepository.findById(targetFunnelId).orElse(null);
            if (target == null) {
                log.warn("{} projectId={}", LOG_ENROLL_SKIP_TARGET_MISSING, projectId);
                return;
            }
            if (!projectId.equals(target.getProjectId())) {
                // Fail-closed: the referenced funnel belongs to another project. Log only the CALLER's
                // projectId (never the target's, which is foreign data) — anti-IDOR + anti-log-leak.
                log.warn("{} projectId={} targetFunnelId={}", LOG_ENROLL_SKIP_TARGET_WRONG_PROJECT,
                        projectId, targetFunnelId);
                return;
            }
            if (target.getStatus() != FunnelStatus.active) {
                // draft / paused / (effectively) deleted target → skip. The status is an enum code, safe to log.
                log.warn("{} projectId={} targetFunnelId={} status={}", LOG_ENROLL_SKIP_TARGET_INACTIVE,
                        projectId, targetFunnelId, target.getStatus());
                return;
            }

            // (e) Entry-step fallback observability: if a non-null entry step does not resolve in the
            // target's steps, the factory silently falls back to step 0 — emit a greppable WARN here so the
            // fallback is visible (the factory itself stays log-quiet on the start-cursor choice).
            if (targetEntryStepId != null && !stepExists(target, targetEntryStepId)) {
                log.warn("{} projectId={} targetFunnelId={} targetEntryStepId={}",
                        LOG_ENROLL_ENTRY_STEP_FALLBACK, projectId, targetFunnelId, targetEntryStepId);
            }

            // (d)+(f) Insert at the entry step, inheriting the parent's telegramBotId, honouring re-enter.
            insertTargetExecution(projectId, target, subscriberId, telegramBotId, originDepth, targetEntryStepId);
        } catch (Throwable t) {
            // Decision 12: enrollSpecificFunnel never throws outward — an enroll fault must not fail the
            // parent execution. Log a greppable WARN (exception class only, no message/PII) and swallow.
            log.warn("{} projectId={} error={}", LOG_ENROLL_ERROR, projectId, t.getClass().getSimpleName());
        }
    }

    // Insert the target execution at targetEntryStepId via the factory, honouring the target's re-enter
    // policy (same pattern as insertOneFunnel, but starting at the entry step instead of step 0):
    // allowReEnter=true → cancel-then-insert (restart from the entry step); allowReEnter=false → insert in
    // a try and swallow a DuplicateKeyException (the re-enter guard) as a benign no-op + greppable WARN.
    // Self-target A→A needs no special branch: it hits the same (funnelId, subscriberId) re-enter guard.
    private void insertTargetExecution(String projectId, Funnel target, String subscriberId,
                                       Long telegramBotId, int originDepth, String targetEntryStepId) {
        if (target.isAllowReEnter()) {
            executionFactory.cancelExistingForPair(projectId, target.getId(), subscriberId);
            executionFactory.insertExecutionAt(projectId, target, subscriberId, telegramBotId,
                    originDepth, targetEntryStepId);
            return;
        }
        try {
            executionFactory.insertExecutionAt(projectId, target, subscriberId, telegramBotId,
                    originDepth, targetEntryStepId);
        } catch (DuplicateKeyException dup) {
            // Re-enter disabled and a running|waiting execution already exists for this (funnelId,
            // subscriberId) — benign no-op (the subscriber is already in the target). Distinct id so this
            // re-enter no-op is greppable apart from the dispatch-path one.
            log.warn("{} targetFunnelId={} subscriberId={}", LOG_ENROLL_REENTER_IGNORED,
                    target.getId(), subscriberId);
        }
    }

    // True iff the target funnel carries a step with the given id (entry-step resolution probe). Mirrors
    // the factory's own resolveStartCursor scan so the WARN and the actual fallback agree.
    private static boolean stepExists(Funnel funnel, String stepId) {
        List<FunnelStep> steps = funnel.getSteps();
        if (steps == null) {
            return false;
        }
        for (FunnelStep step : steps) {
            if (stepId.equals(step.getId())) {
                return true;
            }
        }
        return false;
    }

    // Insert one execution via the factory, honouring the funnel's re-enter policy. Returns true iff a
    // fresh execution was actually inserted (allowReEnter=false + a duplicate is a benign no-op → false).
    private boolean insertOneFunnel(String projectId, Funnel funnel, String subscriberId,
                                    Long telegramBotId, int originDepth) {
        if (funnel.isAllowReEnter()) {
            executionFactory.cancelExistingForPair(projectId, funnel.getId(), subscriberId);
            executionFactory.insertExecution(projectId, funnel, subscriberId, telegramBotId, originDepth);
            return true;
        }
        try {
            executionFactory.insertExecution(projectId, funnel, subscriberId, telegramBotId, originDepth);
            return true;
        } catch (DuplicateKeyException dup) {
            // Re-enter disabled: a running|waiting execution already exists for this (funnelId,
            // subscriberId). Benign no-op for THIS funnel — do not abort the rest of the fan-out.
            log.info("{} funnelId={} subscriberId={}", LOG_DISPATCH_REENTER_IGNORED,
                    funnel.getId(), subscriberId);
            return false;
        }
    }

    // Active matching funnels for the trigger. keyword scans the project's active keyword funnels and
    // contains-matches the (lowercased) text against each funnel's keywords list (case-insensitive,
    // any-of-many — Decision 3, code-side scan). The exact triggers (tag_added/custom_field_set/event)
    // list-match on triggerValue == matchKey (null coerced to "" — mirror fire()'s coercion).
    private List<Funnel> matchingFunnels(String projectId, String triggerType, String matchKey) {
        if (FunnelService.TRIGGER_KEYWORD.equals(triggerType)) {
            return matchingKeywordFunnels(projectId, matchKey);
        }
        // Array-aware $elemMatch over triggers[] (Task 2): both triggerType AND triggerValue must match the
        // SAME embedded element (no cross-element false positive). Returns the funnel; the caller re-scans
        // the matched element for entryStepId (Decision 12).
        String triggerValue = matchKey == null ? "" : matchKey;
        return funnelRepository.findByProjectIdAndTriggersTriggerTypeAndTriggersTriggerValueAndStatus(
                projectId, triggerType, triggerValue, FunnelStatus.active);
    }

    private List<Funnel> matchingKeywordFunnels(String projectId, String matchKey) {
        String text = matchKey == null ? "" : matchKey.toLowerCase();
        // Array-aware candidate scan (Task 2): all active funnels whose triggers[] carries a keyword
        // element. The exact-match index cannot express "contains, multiple keywords", so the
        // contains-match runs in code over each candidate's keyword trigger elements.
        List<Funnel> keywordFunnels = funnelRepository.findByProjectIdAndTriggersTriggerTypeAndStatus(
                projectId, FunnelService.TRIGGER_KEYWORD, FunnelStatus.active);
        List<Funnel> matches = new ArrayList<>();
        for (Funnel funnel : keywordFunnels) {
            if (funnelHasMatchingKeyword(funnel, text)) {
                matches.add(funnel);
            }
        }
        return matches;
    }

    // A funnel matches the keyword text iff ANY of its keyword trigger elements contains-matches it
    // (keywords now live per-Trigger, not on the funnel). Scans triggers[] for keyword-type elements and
    // reuses the case-insensitive any-of-many contains-match.
    private static boolean funnelHasMatchingKeyword(Funnel funnel, String lowercasedText) {
        List<Trigger> triggers = funnel.getTriggers();
        if (triggers == null) {
            return false;
        }
        for (Trigger trigger : triggers) {
            if (trigger != null && FunnelService.TRIGGER_KEYWORD.equals(trigger.getTriggerType())
                    && containsAnyKeyword(lowercasedText, trigger.getKeywords())) {
                return true;
            }
        }
        return false;
    }

    // case-insensitive contains, any-of-many. keywords are stored lowercase (FunnelService normalizes
    // on save); text is lowercased here, so the contains() is plain. Empty/null keyword list → no match.
    private static boolean containsAnyKeyword(String lowercasedText, List<String> keywords) {
        if (keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isEmpty() && lowercasedText.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    // Per-subscriber auto-enroll rate-limit (Decision 6a / 10). Reuses the
    // SubscriberServiceImpl.startRateLimitExceeded idiom verbatim: INCR, EXPIRE only on first set,
    // fail-open with a greppable WARN on any Redis transport error. Key prefix bf:rate:auto-enroll:.
    private boolean autoEnrollRateLimitExceeded(String subscriberId) {
        String key = RATE_KEY_PREFIX + subscriberId;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, RATE_TTL);
            }
            return count != null && count > autoEnrollRatePerMin;
        } catch (Exception e) {
            // Fail-open (availability over a hard limit during a Redis outage). The Redis-independent
            // depth cap + fan-out ceiling still bound the cascade.
            log.warn("{} error={}", LOG_AUTO_ENROLL_RATE_REDIS_FAIL_OPEN, e.getClass().getSimpleName());
            return false;
        }
    }
}
