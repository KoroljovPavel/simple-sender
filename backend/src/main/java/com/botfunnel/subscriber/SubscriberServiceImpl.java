package com.botfunnel.subscriber;

import com.botfunnel.common.AppException;
import com.botfunnel.events.EventService;
import com.botfunnel.tag.TagService;
import com.mongodb.client.result.UpdateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Owns the Subscriber CRM lifecycle (Epic 05) — replaces the Epic 04b placeholder stub.
 * Sole {@code @Service} implementing {@link SubscriberService} (Decision 1) and the SOLE writer of
 * CRM lifecycle events to {@code subscriber_events} (Decision 10): idempotent auto-registration from
 * Telegram updates, the full status state machine ({@code active ↔ unsubscribed}, {@code * → blocked},
 * {@code * → deleted}, reactivation on {@code /start}), the Redis {@code /start} rate-limit
 * (Decision 9, fail-open mirroring {@code BotService.incrementBruteForceCounter}), and the
 * custom-field / tag audit-event entry points consumed by Task 5 / Task 8.
 */
@Service
public class SubscriberServiceImpl implements SubscriberService {

    private static final Logger log = LoggerFactory.getLogger(SubscriberServiceImpl.class);

    // CRM lifecycle event types → subscriber_events (Decision 10).
    private static final String EVT_REGISTERED = "subscriber_registered";
    private static final String EVT_REACTIVATED = "subscriber_reactivated";
    private static final String EVT_UNSUBSCRIBED = "subscriber_unsubscribed";
    private static final String EVT_BLOCKED = "subscriber_blocked";
    private static final String EVT_DELETED = "subscriber_deleted";
    private static final String EVT_TAG_ADDED = "subscriber_tag_added";
    private static final String EVT_TAG_REMOVED = "subscriber_tag_removed";
    private static final String EVT_CUSTOM_FIELD_SET = "subscriber_custom_field_set";

    // Platform-level event (events collection) — no subscriber exists yet when the /start flood trips.
    private static final String EVT_RATE_LIMIT_EXCEEDED = "rate_limit_exceeded";

    private static final String CODE_ALREADY_UNSUBSCRIBED = "already_unsubscribed";
    private static final String MESSAGE_ALREADY_UNSUBSCRIBED = "Already unsubscribed";
    private static final String MESSAGE_SUBSCRIBER_NOT_FOUND = "Subscriber not found";

    private static final String RATE_KEY_PREFIX = "bf:rate:start:";
    private static final Duration RATE_TTL = Duration.ofMinutes(1);

    // Greppable WARN constant for the Redis fail-open path (Decision 9). Kept stable so log-based
    // alerts and tests can pin to it; e.getMessage() is the SLF4J argument.
    static final String SUBSCRIBER_START_RATE_REDIS_FAIL_OPEN =
            "SUBSCRIBER_START_RATE_REDIS_FAIL_OPEN: Redis rate-limit unavailable — failing open ({})";

    private final SubscriberRepository subscriberRepository;
    private final SubscriberEventRepository subscriberEventRepository;
    private final TagService tagService;
    private final MongoTemplate mongoTemplate;
    private final StringRedisTemplate redisTemplate;
    private final EventService eventService;
    private final Clock clock;
    private final int startRateLimitPerMin;

    public SubscriberServiceImpl(SubscriberRepository subscriberRepository,
                                 SubscriberEventRepository subscriberEventRepository,
                                 TagService tagService,
                                 MongoTemplate mongoTemplate,
                                 StringRedisTemplate redisTemplate,
                                 EventService eventService,
                                 Clock clock,
                                 @Value("${app.subscriber.rate-limit.start-per-min}") int startRateLimitPerMin) {
        this.subscriberRepository = subscriberRepository;
        this.subscriberEventRepository = subscriberEventRepository;
        this.tagService = tagService;
        this.mongoTemplate = mongoTemplate;
        this.redisTemplate = redisTemplate;
        this.eventService = eventService;
        this.clock = clock;
        this.startRateLimitPerMin = startRateLimitPerMin;
    }

    // ─── auto-registration ─────────────────────────────────────────────────

    @Override
    public void upsertFromTelegramUpdate(String projectId, Long telegramBotId, Long chatId, String chatType,
                                         Long telegramUserId, String firstName, String lastName,
                                         String username, String languageCode) {
        if (telegramUserId == null) {
            // Service messages omit message.from — no unique key, nothing to persist.
            return;
        }
        Optional<Subscriber> existing =
                subscriberRepository.findByProjectIdAndTelegramUserId(projectId, telegramUserId);
        if (existing.isPresent()) {
            refreshOrReactivate(existing.get(), telegramBotId, chatId, firstName, lastName, username, languageCode);
            return;
        }

        // New subscriber: rate-limit BEFORE insert (existing-subscriber lookup above bypasses it).
        if (startRateLimitExceeded(projectId)) {
            // No subscriber exists yet — platform-level event, not subscriber_events. Drop silently
            // (a Telegram update is one-shot; user-spec chose dropping over a 4xx the user never sees).
            eventService.logEvent(null, EVT_RATE_LIMIT_EXCEEDED, null, null,
                    Map.of("projectId", projectId));
            return;
        }
        try {
            Subscriber inserted = insertNew(projectId, telegramBotId, chatId, telegramUserId,
                    firstName, lastName, username, languageCode);
            writeSubscriberEvent(inserted.getId(), projectId, EVT_REGISTERED, Map.of());
        } catch (DuplicateKeyException race) {
            // Concurrent /start from the same telegramUserId raced the unique (projectId,
            // telegramUserId) index. Re-read and apply refresh-or-reactivate — idempotent upsert.
            Subscriber reread = subscriberRepository.findByProjectIdAndTelegramUserId(projectId, telegramUserId)
                    .orElseThrow(() -> race);
            refreshOrReactivate(reread, telegramBotId, chatId, firstName, lastName, username, languageCode);
        }
    }

    private void refreshOrReactivate(Subscriber existing, Long telegramBotId, Long chatId,
                                     String firstName, String lastName, String username, String languageCode) {
        Instant now = clock.instant();
        Update update = identityRefresh(now, telegramBotId, chatId, firstName, lastName, username, languageCode);
        if (existing.getStatus() == SubscriberStatus.ACTIVE) {
            // Known active subscriber: refresh lastSeenAt + identity only — no event, no rate-limit.
            mongoTemplate.findAndModify(byId(existing.getId()), update, Subscriber.class);
            return;
        }
        // Reactivation: flip to ACTIVE, preserve subscribedAt + tags + customFields, clear the
        // terminal-state timestamps. Then record subscriber_reactivated with the prior status.
        SubscriberStatus previous = existing.getStatus();
        update.set("status", SubscriberStatus.ACTIVE)
                .unset("unsubscribedAt")
                .unset("blockedAt")
                .unset("deletedAt");
        mongoTemplate.findAndModify(byId(existing.getId()), update, Subscriber.class);
        writeSubscriberEvent(existing.getId(), existing.getProjectId(), EVT_REACTIVATED,
                Map.of("previousStatus", previous.name()));
    }

    private Subscriber insertNew(String projectId, Long telegramBotId, Long chatId, Long telegramUserId,
                                 String firstName, String lastName, String username, String languageCode) {
        Instant now = clock.instant();
        Subscriber s = new Subscriber();
        s.setProjectId(projectId);
        s.setTelegramBotId(telegramBotId);
        s.setTelegramChatId(chatId);
        s.setTelegramUserId(telegramUserId);
        s.setFirstName(firstName);
        s.setLastName(lastName);
        s.setUsername(username);
        s.setLanguageCode(languageCode);
        s.setStatus(SubscriberStatus.ACTIVE);
        s.setSubscribedAt(now);
        s.setLastSeenAt(now);
        s.setTags(new ArrayList<>());
        s.setCustomFields(new HashMap<>());
        // insert (not save) so a concurrent duplicate surfaces as DuplicateKeyException.
        return subscriberRepository.insert(s);
    }

    // ─── status state machine ──────────────────────────────────────────────

    @Override
    public void markUnsubscribed(String projectId, Long telegramBotId, Long chatId) {
        Subscriber s = findByChat(projectId, telegramBotId, chatId).orElse(null);
        if (s == null || s.getStatus() == SubscriberStatus.UNSUBSCRIBED) {
            return; // never-registered chat, or already unsubscribed → idempotent no-op
        }
        flip(s.getId(), SubscriberStatus.UNSUBSCRIBED, "unsubscribedAt");
        writeSubscriberEvent(s.getId(), projectId, EVT_UNSUBSCRIBED, Map.of("reason", "command_stop"));
    }

    @Override
    public void markBlockedByChatId(String projectId, Long telegramBotId, Long chatId) {
        Subscriber s = findByChat(projectId, telegramBotId, chatId).orElse(null);
        if (s == null || s.getStatus() == SubscriberStatus.BLOCKED) {
            return;
        }
        flip(s.getId(), SubscriberStatus.BLOCKED, "blockedAt");
        writeSubscriberEvent(s.getId(), projectId, EVT_BLOCKED, Map.of());
    }

    @Override
    public void markDeletedByChatId(String projectId, Long telegramBotId, Long chatId) {
        Subscriber s = findByChat(projectId, telegramBotId, chatId).orElse(null);
        if (s == null || s.getStatus() == SubscriberStatus.DELETED) {
            return;
        }
        flip(s.getId(), SubscriberStatus.DELETED, "deletedAt");
        writeSubscriberEvent(s.getId(), projectId, EVT_DELETED, Map.of());
    }

    @Override
    public void unsubscribeManual(String projectId, String subscriberId) {
        // projectId scope is anti-IDOR defense-in-depth — a foreign-project subscriber collapses to
        // the same uniform 404 as a missing one (mirrors the addTag/removeTag (projectId, _id) scope).
        Subscriber s = subscriberRepository.findById(subscriberId)
                .filter(sub -> projectId.equals(sub.getProjectId()))
                .orElseThrow(() -> AppException.notFound(MESSAGE_SUBSCRIBER_NOT_FOUND));
        if (s.getStatus() != SubscriberStatus.ACTIVE) {
            // UNSUBSCRIBED/BLOCKED/DELETED — terminal states cannot be manually unsubscribed.
            // Two-arg form so the error code is "already_unsubscribed", not null.
            throw AppException.conflict(CODE_ALREADY_UNSUBSCRIBED, MESSAGE_ALREADY_UNSUBSCRIBED);
        }
        flip(s.getId(), SubscriberStatus.UNSUBSCRIBED, "unsubscribedAt");
        writeSubscriberEvent(s.getId(), s.getProjectId(), EVT_UNSUBSCRIBED, Map.of("reason", "manual"));
    }

    // ─── Decision 10 audit-event writers (custom fields + tags) ─────────────

    @Override
    public void recordCustomFieldsSet(String projectId, String subscriberId,
                                      Map<String, Object> oldValues, Map<String, Object> newValues) {
        List<String> changedKeys = changedKeys(oldValues, newValues);
        if (changedKeys.isEmpty()) {
            return; // no-op — nothing actually changed
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("oldValues", nullSafe(oldValues));
        metadata.put("newValues", nullSafe(newValues));
        metadata.put("changedKeys", changedKeys);
        writeSubscriberEvent(subscriberId, projectId, EVT_CUSTOM_FIELD_SET, metadata);
    }

    @Override
    public void addTag(String projectId, String subscriberId, String slug) {
        UpdateResult result = mongoTemplate.update(Subscriber.class)
                .matching(Query.query(Criteria.where("_id").is(subscriberId).and("projectId").is(projectId)))
                .apply(new Update().addToSet("tags", slug))
                .first();
        if (result.getModifiedCount() == 0L) {
            return; // tag already present (or no matching subscriber) → idempotent no-op
        }
        tagService.incrementCounter(projectId, slug, 1);
        writeSubscriberEvent(subscriberId, projectId, EVT_TAG_ADDED, Map.of("slug", slug));
    }

    @Override
    public void removeTag(String projectId, String subscriberId, String slug) {
        UpdateResult result = mongoTemplate.update(Subscriber.class)
                .matching(Query.query(Criteria.where("_id").is(subscriberId).and("projectId").is(projectId)))
                .apply(new Update().pull("tags", slug))
                .first();
        if (result.getModifiedCount() == 0L) {
            return; // tag absent (or no matching subscriber) → idempotent no-op
        }
        tagService.incrementCounter(projectId, slug, -1);
        writeSubscriberEvent(subscriberId, projectId, EVT_TAG_REMOVED, Map.of("slug", slug));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private boolean startRateLimitExceeded(String projectId) {
        // Mirrors BotService.incrementBruteForceCounter: INCR, EXPIRE only on first set, fail-open
        // with a greppable WARN on any Redis transport error.
        String key = RATE_KEY_PREFIX + projectId;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, RATE_TTL);
            }
            return count != null && count > startRateLimitPerMin;
        } catch (Exception e) {
            log.warn(SUBSCRIBER_START_RATE_REDIS_FAIL_OPEN, e.getMessage());
            return false;
        }
    }

    @Override
    public Optional<Subscriber> findByChat(String projectId, Long telegramBotId, Long chatId) {
        // Pure exposure of the existing repository lookup — no new query logic. Reused internally by
        // the status-machine writers below (markUnsubscribed / markBlocked / markDeleted).
        return subscriberRepository
                .findByProjectIdAndTelegramBotIdAndTelegramChatId(projectId, telegramBotId, chatId);
    }

    @Override
    public Optional<Subscriber> findById(String projectId, String subscriberId) {
        // Project-scoped boundary lookup (Task 4). Anti-IDOR: filter the repo result by projectId so a
        // subscriberId belonging to a different project collapses to empty — never leaks/enrolls another
        // tenant's subscriber (mirrors unsubscribeManual's (projectId, _id) scope).
        if (subscriberId == null) {
            return Optional.empty();
        }
        return subscriberRepository.findById(subscriberId)
                .filter(sub -> projectId.equals(sub.getProjectId()));
    }

    private void flip(String subscriberId, SubscriberStatus target, String timestampField) {
        // Atomic findAndModify — never findById + setter + save (two retries could trample).
        mongoTemplate.findAndModify(byId(subscriberId),
                new Update().set("status", target).set(timestampField, clock.instant()),
                Subscriber.class);
    }

    private Update identityRefresh(Instant now, Long telegramBotId, Long chatId,
                                   String firstName, String lastName, String username, String languageCode) {
        // Identity fields pass through verbatim (including null) — never default to empty strings.
        return new Update()
                .set("lastSeenAt", now)
                .set("telegramBotId", telegramBotId)
                .set("telegramChatId", chatId)
                .set("firstName", firstName)
                .set("lastName", lastName)
                .set("username", username)
                .set("languageCode", languageCode);
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

    private static Query byId(String id) {
        return Query.query(Criteria.where("_id").is(id));
    }

    // Symmetric diff: keys whose values differ (Objects.equals handles null) OR present in only one map.
    private static List<String> changedKeys(Map<String, Object> oldValues, Map<String, Object> newValues) {
        Map<String, Object> left = nullSafe(oldValues);
        Map<String, Object> right = nullSafe(newValues);
        Set<String> union = new LinkedHashSet<>();
        union.addAll(left.keySet());
        union.addAll(right.keySet());
        List<String> changed = new ArrayList<>();
        for (String key : union) {
            if (!Objects.equals(left.get(key), right.get(key))) {
                changed.add(key);
            }
        }
        return changed;
    }

    private static Map<String, Object> nullSafe(Map<String, Object> map) {
        return map == null ? Map.of() : map;
    }
}
