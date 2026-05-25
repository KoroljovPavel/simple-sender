package com.botfunnel.subscriber;

import com.botfunnel.auth.AppUserDetails;
import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.bot.TelegramSendException;
import com.botfunnel.bot.TelegramSender;
import com.botfunnel.bot.dto.SentMessage;
import com.botfunnel.common.AppException;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectService;
import com.botfunnel.subscriber.dto.PageResponse;
import com.botfunnel.subscriber.dto.SendMessageRequest;
import com.botfunnel.subscriber.dto.SendMessageResponse;
import com.botfunnel.subscriber.dto.SubscriberEventResponse;
import com.botfunnel.subscriber.dto.SubscriberListQuery;
import com.botfunnel.subscriber.dto.SubscriberResponse;
import com.botfunnel.subscriber.dto.TagAssignRequest;
import com.botfunnel.tag.TagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP entry point for the Subscriber CRM (Epic 05, Wave 4). Every handler's FIRST statement is
 * {@code projectService.requireOwned(currentUserId(), projectId, false)} — anti-IDOR / anti-enumeration
 * (user-spec AC21): foreign-owned, soft-deleted, and malformed projectIds all collapse to a uniform 404.
 *
 * <p>Event ownership (Decision 10): this controller is the SOLE writer of {@code personal_message_sent}
 * (on a 2xx send) and {@code personal_message_failed} (on a terminal {@code OTHER} send failure). The
 * {@code BLOCKED_BY_USER} / {@code CHAT_NOT_FOUND} terminal reasons are owned by the {@code TelegramSender}
 * hook (Task 6), which writes {@code subscriber_blocked}/{@code subscriber_deleted} itself — the controller
 * does NOT double-write those.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/subscribers")
public class SubscriberController {

    private static final Logger log = LoggerFactory.getLogger(SubscriberController.class);

    private static final String EVT_PERSONAL_MESSAGE_SENT = "personal_message_sent";
    private static final String EVT_PERSONAL_MESSAGE_FAILED = "personal_message_failed";

    private static final String MESSAGE_SUBSCRIBER_NOT_FOUND = "Subscriber not found";
    private static final String MESSAGE_BOT_NOT_FOUND = "Bot not found";

    private static final String CODE_PERSONAL_MESSAGE_RATE_LIMITED = "personal_message_rate_limited";
    private static final String CODE_TELEGRAM_RATE_LIMITED = "telegram_rate_limited";

    private static final String RATE_KEY_PREFIX = "bf:rate:personal_message:";
    private static final Duration RATE_TTL = Duration.ofMinutes(1);

    // Greppable WARN constant for the personal-message Redis fail-open path (Decision 9). Mirrors
    // SUBSCRIBER_START_RATE_REDIS_FAIL_OPEN — stable so log-based alerts and tests can pin to it.
    static final String PERSONAL_MESSAGE_RATE_REDIS_FAIL_OPEN =
            "PERSONAL_MESSAGE_RATE_REDIS_FAIL_OPEN: Redis rate-limit unavailable — failing open ({})";

    private static final int DEFAULT_EVENTS_LIMIT = 50;
    private static final int MAX_EVENTS_LIMIT = 200;

    private final ProjectService projectService;
    private final SubscriberService subscriberService;
    private final SubscriberRepository subscriberRepository;
    private final SubscriberEventRepository subscriberEventRepository;
    private final TagService tagService;
    private final TelegramSender telegramSender;
    private final BotRepository botRepository;
    private final MongoTemplate mongoTemplate;
    private final StringRedisTemplate redisTemplate;
    private final SegmentFilterBuilder segmentFilterBuilder;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int personalMessageRateLimitPerMin;

    public SubscriberController(ProjectService projectService,
                                SubscriberService subscriberService,
                                SubscriberRepository subscriberRepository,
                                SubscriberEventRepository subscriberEventRepository,
                                TagService tagService,
                                TelegramSender telegramSender,
                                BotRepository botRepository,
                                MongoTemplate mongoTemplate,
                                StringRedisTemplate redisTemplate,
                                SegmentFilterBuilder segmentFilterBuilder,
                                ObjectMapper objectMapper,
                                Clock clock,
                                @Value("${app.subscriber.rate-limit.personal-message-per-min}")
                                int personalMessageRateLimitPerMin) {
        this.projectService = projectService;
        this.subscriberService = subscriberService;
        this.subscriberRepository = subscriberRepository;
        this.subscriberEventRepository = subscriberEventRepository;
        this.tagService = tagService;
        this.telegramSender = telegramSender;
        this.botRepository = botRepository;
        this.mongoTemplate = mongoTemplate;
        this.redisTemplate = redisTemplate;
        this.segmentFilterBuilder = segmentFilterBuilder;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.personalMessageRateLimitPerMin = personalMessageRateLimitPerMin;
    }

    // ─── list ────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<PageResponse<SubscriberResponse>> list(@PathVariable String projectId,
                                                                 @Valid @ModelAttribute SubscriberListQuery query) {
        Project project = projectService.requireOwned(currentUserId(), projectId, false);

        // Explicit server-side guard: a <2-char search must be a 400, not a silent no-op. This counts
        // TRIMMED length, whereas the DTO's @Size(min=2) counts raw length — the two layers overlap but
        // are not redundant (this also rejects "  a  " which @Size would accept). Bean-validation fires
        // first during binding; this is the deterministic fallback at the handler.
        if (query.search() != null && query.search().trim().length() < SegmentFilterBuilder.MIN_SEARCH_CHARS) {
            throw AppException.badRequest("search must be at least 2 characters");
        }

        SegmentFilter filter = toFilter(query);
        int effectiveLimit = SegmentFilterBuilder.clampLimit(query.limit());
        Query mongoQuery = segmentFilterBuilder.build(project.getId(), filter);
        // Fetch one extra row to detect a next page without an O(N) count.
        mongoQuery.limit(effectiveLimit + 1);

        List<Subscriber> rows = mongoTemplate.find(mongoQuery, Subscriber.class);
        boolean hasNext = rows.size() > effectiveLimit;
        if (hasNext) {
            rows = rows.subList(0, effectiveLimit);
        }
        String nextCursor = hasNext ? encodeCursorFor(rows.get(rows.size() - 1), filter.sort()) : null;

        List<SubscriberResponse> items = rows.stream().map(SubscriberController::toResponse).toList();
        return ResponseEntity.ok(new PageResponse<>(items, nextCursor));
    }

    // ─── profile + history ─────────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<SubscriberResponse> getOne(@PathVariable String projectId,
                                                     @PathVariable String id) {
        Project project = projectService.requireOwned(currentUserId(), projectId, false);
        Subscriber subscriber = requireOwnedSubscriber(project.getId(), id);
        return ResponseEntity.ok(toResponse(subscriber));
    }

    @GetMapping("/{id}/events")
    public ResponseEntity<List<SubscriberEventResponse>> events(@PathVariable String projectId,
                                                                @PathVariable String id,
                                                                @RequestParam(defaultValue = "50") int limit) {
        Project project = projectService.requireOwned(currentUserId(), projectId, false);
        requireOwnedSubscriber(project.getId(), id);

        // limit <= 0 → documented default 50 (symmetric with the list endpoint's clampLimit); max 200.
        int effectiveLimit = limit <= 0 ? DEFAULT_EVENTS_LIMIT : Math.min(limit, MAX_EVENTS_LIMIT);
        // Self-scoping by projectId too (not just subscriberId): defense-in-depth so the feed query
        // cannot leak cross-project rows even if a future caller reaches it without requireOwnedSubscriber.
        Query feed = Query.query(Criteria.where("subscriberId").is(id).and("projectId").is(project.getId()))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .limit(effectiveLimit);
        List<SubscriberEventResponse> body = mongoTemplate.find(feed, SubscriberEvent.class).stream()
                .map(e -> new SubscriberEventResponse(e.getId(), e.getEventType(), e.getMetadata(), e.getCreatedAt()))
                .toList();
        return ResponseEntity.ok(body);
    }

    // ─── manual unsubscribe ──────────────────────────────────────────────────

    @PostMapping("/{id}/unsubscribe")
    public ResponseEntity<SubscriberResponse> unsubscribe(@PathVariable String projectId,
                                                          @PathVariable String id) {
        Project project = projectService.requireOwned(currentUserId(), projectId, false);
        // Throws 404 (not in project) or 409 already_unsubscribed (non-ACTIVE) from the service.
        subscriberService.unsubscribeManual(project.getId(), id);
        return ResponseEntity.ok(toResponse(requireOwnedSubscriber(project.getId(), id)));
    }

    // ─── tag assign / unassign ──────────────────────────────────────────────

    @PostMapping("/{id}/tags")
    public ResponseEntity<SubscriberResponse> addTag(@PathVariable String projectId,
                                                     @PathVariable String id,
                                                     @Valid @RequestBody TagAssignRequest request) {
        Project project = projectService.requireOwned(currentUserId(), projectId, false);
        requireOwnedSubscriber(project.getId(), id);
        // find-or-create the project tag, then atomic $addToSet + conditional counter $inc (service).
        tagService.findOrCreate(project.getId(), request.slug());
        subscriberService.addTag(project.getId(), id, request.slug());
        return ResponseEntity.ok(toResponse(requireOwnedSubscriber(project.getId(), id)));
    }

    @DeleteMapping("/{id}/tags/{slug}")
    public ResponseEntity<Void> removeTag(@PathVariable String projectId,
                                          @PathVariable String id,
                                          @PathVariable String slug) {
        Project project = projectService.requireOwned(currentUserId(), projectId, false);
        requireOwnedSubscriber(project.getId(), id);
        subscriberService.removeTag(project.getId(), id, slug);
        return ResponseEntity.noContent().build();
    }

    // ─── send personal message ──────────────────────────────────────────────

    @PostMapping("/{id}/messages")
    public ResponseEntity<SendMessageResponse> sendMessage(@PathVariable String projectId,
                                                           @PathVariable String id,
                                                           @Valid @RequestBody SendMessageRequest request) {
        Project project = projectService.requireOwned(currentUserId(), projectId, false);
        Subscriber subscriber = requireOwnedSubscriber(project.getId(), id);

        if (personalMessageRateLimited(project.getId())) {
            throw new AppException(HttpStatus.TOO_MANY_REQUESTS, CODE_PERSONAL_MESSAGE_RATE_LIMITED,
                    "Too many personal messages — try again in a minute.");
        }

        // Resolve the CONNECTED bot's Mongo id for TelegramSender.sendText(botId, ...). Telegram is the
        // source of truth — we do NOT pre-block on subscriber status; a 403/400 reaction refreshes it.
        Bot bot = botRepository.findByProjectIdAndStatus(project.getId(), BotStatus.CONNECTED)
                .orElseThrow(() -> AppException.notFound(MESSAGE_BOT_NOT_FOUND));

        try {
            SentMessage sent = telegramSender.sendText(bot.getId(), subscriber.getTelegramChatId(),
                    request.text(), null, project.getOwnerId());
            writeSubscriberEvent(subscriber.getId(), project.getId(), EVT_PERSONAL_MESSAGE_SENT,
                    sentMetadata(sent));
            return ResponseEntity.ok(new SendMessageResponse("sent", "Message sent"));
        } catch (TelegramSendException ex) {
            return handleSendFailure(ex, subscriber, project.getId());
        }
    }

    private ResponseEntity<SendMessageResponse> handleSendFailure(TelegramSendException ex,
                                                                  Subscriber subscriber,
                                                                  String projectId) {
        switch (ex.getTerminalReason()) {
            case BLOCKED_BY_USER:
                // Sender hook already wrote subscriber_blocked + flipped status. No personal_message_failed.
                return ResponseEntity.ok(new SendMessageResponse("blocked", "Subscriber has blocked the bot"));
            case CHAT_NOT_FOUND:
                // Sender hook already wrote subscriber_deleted + flipped status. No personal_message_failed.
                return ResponseEntity.ok(new SendMessageResponse("deleted", "Telegram chat no longer exists"));
            case OTHER:
            default:
                // Decision 10: the controller owns personal_message_failed for every OTHER terminal
                // failure (the sender did not flip the subscriber). errorCode distinguishes the HTTP map:
                //   - null   → retry-exhaustion / overall-timeout (429 loop included) → 503 telegram_rate_limited (AC5)
                //   - 4xx    → concrete Telegram client error → 400 telegram_send_failed (existing mapping)
                Map<String, Object> meta = new HashMap<>();
                meta.put("terminalReason", "OTHER");
                meta.put("error", ex.getMessage());
                writeSubscriberEvent(subscriber.getId(), projectId, EVT_PERSONAL_MESSAGE_FAILED, meta);
                if (ex.getErrorCode() == null) {
                    throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, CODE_TELEGRAM_RATE_LIMITED,
                            "Telegram is temporarily unavailable — try again later.");
                }
                throw ex; // GlobalErrorHandler → 400 telegram_send_failed
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private boolean personalMessageRateLimited(String projectId) {
        // Decision 9: INCR every attempt, EXPIRE only on the first bucket, fail-open with a greppable
        // WARN. Counts BEFORE the send so the (limit+1)th request is rejected without calling Telegram.
        String key = RATE_KEY_PREFIX + projectId;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, RATE_TTL);
            }
            return count != null && count > personalMessageRateLimitPerMin;
        } catch (Exception e) {
            log.warn(PERSONAL_MESSAGE_RATE_REDIS_FAIL_OPEN, e.getMessage());
            return false;
        }
    }

    private Subscriber requireOwnedSubscriber(String projectId, String subscriberId) {
        // projectId-scoped lookup is anti-IDOR defense-in-depth: requireOwned proves project ownership
        // but not that this subscriberId belongs to that project. A foreign subscriber → uniform 404.
        return subscriberRepository.findById(subscriberId)
                .filter(s -> projectId.equals(s.getProjectId()))
                .orElseThrow(() -> AppException.notFound(MESSAGE_SUBSCRIBER_NOT_FOUND));
    }

    private void writeSubscriberEvent(String subscriberId, String projectId, String eventType,
                                      Map<String, Object> metadata) {
        SubscriberEvent event = new SubscriberEvent();
        event.setSubscriberId(subscriberId);
        event.setProjectId(projectId);
        event.setEventType(eventType);
        event.setMetadata(metadata);
        event.setCreatedAt(clock.instant());
        subscriberEventRepository.save(event);
    }

    private SegmentFilter toFilter(SubscriberListQuery query) {
        SubscriberStatus status = parseStatus(query.status());
        SegmentFilter.SortKey sort = parseSort(query.sort());
        SegmentFilter.Cursor cursor = SegmentFilterBuilder.decodeCursor(query.cursor(), objectMapper);
        int limit = query.limit() == null ? 0 : query.limit();
        return new SegmentFilter(query.search(), status, query.tagsInclude(), query.tagsExclude(),
                query.subscribedFrom(), query.subscribedTo(), sort, cursor, limit);
    }

    private static SubscriberStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return SubscriberStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw AppException.badRequest("invalid status: " + status);
        }
    }

    private static SegmentFilter.SortKey parseSort(String sort) {
        if ("last_seen_desc".equalsIgnoreCase(sort)) {
            return SegmentFilter.SortKey.LAST_SEEN_DESC;
        }
        return SegmentFilter.SortKey.CREATED_DESC; // default + lenient fallback
    }

    private String encodeCursorFor(Subscriber last, SegmentFilter.SortKey sort) {
        Instant sortValue = sort == SegmentFilter.SortKey.LAST_SEEN_DESC
                ? last.getLastSeenAt() : last.getSubscribedAt();
        long epochMilli = sortValue == null ? 0L : sortValue.toEpochMilli();
        return SegmentFilterBuilder.encodeCursor(epochMilli, last.getId(), objectMapper);
    }

    private static Map<String, Object> sentMetadata(SentMessage sent) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("chatId", sent.chatId());
        meta.put("messageId", sent.messageId());
        return meta;
    }

    private static SubscriberResponse toResponse(Subscriber s) {
        return new SubscriberResponse(
                s.getId(),
                s.getTelegramUserId(),
                s.getTelegramChatId(),
                s.getTelegramBotId(),
                s.getFirstName(),
                s.getLastName(),
                s.getUsername(),
                s.getLanguageCode(),
                s.getStatus() == null ? null : s.getStatus().name().toLowerCase(Locale.ROOT),
                s.getTags(),
                s.getCustomFields(),
                s.getSubscribedAt(),
                s.getUnsubscribedAt(),
                s.getBlockedAt(),
                s.getDeletedAt(),
                s.getLastSeenAt());
    }

    private static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AppUserDetails details)) {
            throw AppException.unauthorized("Not authenticated");
        }
        return details.id();
    }
}
