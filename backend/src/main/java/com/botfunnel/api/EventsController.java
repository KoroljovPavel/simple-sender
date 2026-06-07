package com.botfunnel.api;

import com.botfunnel.common.AppException;
import com.botfunnel.funnel.FunnelEventService;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Public external-event ingress (Decision 7). {@code POST /api/integrations/v1/events} lets an external
 * system (broadcast platform, CRM webhook) drive funnels via the shared {@code event} trigger namespace
 * (Decision 4) using a per-project API key.
 *
 * <p>Authentication is handled entirely by the {@code /api/integrations/**} security chain + the
 * {@link ApiKeyAuthFilter} (Decision 7): by the time a request reaches this controller the project is
 * already resolved from the key and pinned in the {@link Authentication}. The controller reads that
 * project — there is NO {@code {projectId}} in the path — so every subscriber lookup is project-scoped
 * (anti-IDOR).
 *
 * <p><strong>Response matrix</strong> (never a 5xx — Decision 12):
 * <ul>
 *   <li>{@code 202} — event accepted (a funnel started, OR a no-op: subscriber exists but no listener).</li>
 *   <li>{@code 400} — malformed body (bad {@code event_name} slug, or neither identifier present).</li>
 *   <li>{@code 401} — any key problem (handled upstream by the filter, uniform).</li>
 *   <li>{@code 404} — unknown subscriber (also cross-project → 404, anti-IDOR). Never auto-creates one.</li>
 *   <li>{@code 429} — global {@code /events} rate-limit exceeded (Redis fail-open).</li>
 * </ul>
 * An engine fault inside dispatch is swallowed by {@link FunnelEventService} (error-isolated) so the
 * endpoint still returns 202; this controller additionally never lets a dispatch path produce a 5xx.
 */
@RestController
@RequestMapping("/api/integrations/v1")
public class EventsController {

    private static final Logger log = LoggerFactory.getLogger(EventsController.class);

    // Global fixed-window rate-limit key (Decision 10). One bucket for the whole endpoint — distinct
    // prefix from the per-subscriber auto-enroll counter (bf:rate:auto-enroll:).
    static final String RATE_KEY = "bf:rate:events";
    private static final Duration RATE_TTL = Duration.ofMinutes(1);

    // Greppable markers — ids/codes only, never the event_name (PII rule) or key material.
    static final String LOG_RATE_REDIS_FAIL_OPEN = "EVENTS_RATE_REDIS_FAIL_OPEN";
    static final String LOG_RATE_EXCEEDED = "EVENTS_RATE_LIMIT_EXCEEDED";
    static final String LOG_UNKNOWN_SUBSCRIBER = "EVENTS_UNKNOWN_SUBSCRIBER";
    static final String LOG_DISPATCH_FAULT = "EVENTS_DISPATCH_FAULT_SWALLOWED";

    private static final String MESSAGE_UNKNOWN_SUBSCRIBER = "Subscriber not found";
    private static final String MESSAGE_RATE_LIMITED = "Rate limit exceeded";

    private final SubscriberService subscriberService;
    private final FunnelEventService funnelEventService;
    private final StringRedisTemplate redisTemplate;
    private final int rateLimitPerMin;

    public EventsController(SubscriberService subscriberService,
                           FunnelEventService funnelEventService,
                           StringRedisTemplate redisTemplate,
                           @Value("${app.api.events.rate-limit-per-min}") int rateLimitPerMin) {
        this.subscriberService = subscriberService;
        this.funnelEventService = funnelEventService;
        this.redisTemplate = redisTemplate;
        this.rateLimitPerMin = rateLimitPerMin;
    }

    @PostMapping("/events")
    public ResponseEntity<Void> ingest(@Valid @RequestBody EventIngressRequest request,
                                       Authentication authentication) {
        // The project is pinned by the key filter — never trust a path/body project. @Valid already
        // mapped a malformed event_name slug / missing-both-identifiers to a 400 before this body runs.
        String projectId = projectId(authentication);

        // Global rate-limit (429), fail-open. Checked after validation (a malformed body is a 400
        // regardless) but before resolution so a flood cannot hammer Mongo.
        if (rateLimitExceeded()) {
            log.warn("{} projectId={}", LOG_RATE_EXCEEDED, projectId);
            throw AppException.tooManyRequests(MESSAGE_RATE_LIMITED);
        }

        // Resolve project-scoped: subscriber_id wins when both present (anti-IDOR — both lookups pinned
        // to the key's project). Unknown / cross-project subscriber → uniform 404; NEVER auto-created.
        Subscriber subscriber = resolveSubscriber(projectId, request);
        if (subscriber == null) {
            log.info("{} projectId={}", LOG_UNKNOWN_SUBSCRIBER, projectId);
            throw AppException.notFound(MESSAGE_UNKNOWN_SUBSCRIBER);
        }

        // External event = root → depth 0. The dispatcher is error-isolated (Decision 12: a fault is
        // swallowed → WARN), so a no-op (no listener) and a successful start both return 202. The extra
        // try/catch is belt-and-braces so this endpoint can never surface a 5xx.
        try {
            funnelEventService.dispatchForSubscriber(projectId, subscriber.getId(),
                    FunnelEventService.TRIGGER_EVENT, request.getEventName(), 0);
        } catch (Throwable t) {
            log.warn("{} projectId={} error={}", LOG_DISPATCH_FAULT, projectId, t.getClass().getSimpleName());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private Subscriber resolveSubscriber(String projectId, EventIngressRequest request) {
        String subscriberId = request.getSubscriberId();
        if (subscriberId != null && !subscriberId.isBlank()) {
            // subscriber_id precedence: resolve by it ONLY (do not fall back to telegram_user_id).
            return subscriberService.findById(projectId, subscriberId).orElse(null);
        }
        return subscriberService.findByTelegramUserId(projectId, request.getTelegramUserId()).orElse(null);
    }

    // Fixed-window global limit. Mirrors SubscriberServiceImpl.startRateLimitExceeded: INCR, EXPIRE only
    // on first set, fail-open with a greppable WARN on any Redis transport error (Decision 10) — a Redis
    // outage must not 5xx the public endpoint (the Redis-independent fan-out ceiling still bounds width).
    private boolean rateLimitExceeded() {
        try {
            Long count = redisTemplate.opsForValue().increment(RATE_KEY);
            if (count != null && count == 1L) {
                redisTemplate.expire(RATE_KEY, RATE_TTL);
            }
            return count != null && count > rateLimitPerMin;
        } catch (Exception e) {
            log.warn("{} error={}", LOG_RATE_REDIS_FAIL_OPEN, e.getClass().getSimpleName());
            return false;
        }
    }

    private static String projectId(Authentication authentication) {
        if (authentication instanceof ApiKeyAuthentication apiKeyAuth) {
            return apiKeyAuth.getProjectId();
        }
        // Defensive: the integrations chain guarantees an ApiKeyAuthentication. Reaching here means a
        // misconfiguration, not an attacker input — fail closed with a uniform 401 rather than a 5xx.
        throw AppException.unauthorized("Unauthorized");
    }
}
