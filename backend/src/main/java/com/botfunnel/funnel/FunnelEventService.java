package com.botfunnel.funnel;

import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
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

    private static final String RATE_KEY_PREFIX = "bf:rate:auto-enroll:";
    private static final Duration RATE_TTL = Duration.ofMinutes(1);

    private final BotRepository botRepository;
    private final SubscriberService subscriberService;
    private final FunnelRepository funnelRepository;
    private final FunnelExecutionFactory executionFactory;
    private final StringRedisTemplate redisTemplate;
    private final int maxFanoutPerEvent;
    private final int autoEnrollRatePerMin;
    private final int maxEnrollDepth;

    public FunnelEventService(BotRepository botRepository,
                              SubscriberService subscriberService,
                              FunnelRepository funnelRepository,
                              FunnelExecutionFactory executionFactory,
                              StringRedisTemplate redisTemplate,
                              @Value("${app.funnel.max-fanout-per-event}") int maxFanoutPerEvent,
                              @Value("${app.funnel.auto-enroll-rate-per-min}") int autoEnrollRatePerMin,
                              @Value("${app.funnel.max-enroll-depth}") int maxEnrollDepth) {
        this.botRepository = botRepository;
        this.subscriberService = subscriberService;
        this.funnelRepository = funnelRepository;
        this.executionFactory = executionFactory;
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
            int inserted = 0;
            for (Funnel funnel : matches) {
                if (inserted >= maxFanoutPerEvent) {
                    int dropped = matches.size() - inserted;
                    log.warn("{} projectId={} ceiling={} dropped={}", LOG_DROP_FANOUT,
                            projectId, maxFanoutPerEvent, dropped);
                    break;
                }
                if (insertOneFunnel(projectId, funnel, subscriber.getId(), telegramBotId, originDepth)) {
                    inserted++;
                }
            }
        } catch (Throwable t) {
            // Decision 12: dispatchForSubscriber never throws outward — a dispatch fault must not poison
            // the webhook pipeline or surface a 5xx to an external caller.
            log.warn("{} projectId={} error={}", LOG_DISPATCH_ERROR, projectId, t.getClass().getSimpleName());
        }
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
        String triggerValue = matchKey == null ? "" : matchKey;
        return funnelRepository.findAllByProjectIdAndTriggerTypeAndTriggerValueAndStatus(
                projectId, triggerType, triggerValue, FunnelStatus.active);
    }

    private List<Funnel> matchingKeywordFunnels(String projectId, String matchKey) {
        String text = matchKey == null ? "" : matchKey.toLowerCase();
        List<Funnel> keywordFunnels = funnelRepository.findByProjectIdAndTriggerTypeAndStatus(
                projectId, FunnelService.TRIGGER_KEYWORD, FunnelStatus.active);
        List<Funnel> matches = new ArrayList<>();
        for (Funnel funnel : keywordFunnels) {
            if (containsAnyKeyword(text, funnel.getKeywords())) {
                matches.add(funnel);
            }
        }
        return matches;
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
