package com.botfunnel.subscriber;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.common.test.ConcurrencyTestUtils;
import com.botfunnel.events.Event;
import com.botfunnel.events.EventRepository;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.webhook.ProcessTelegramUpdateJob;
import com.botfunnel.webhook.RawUpdate;
import com.botfunnel.webhook.RawUpdateRepository;
import com.botfunnel.webhook.RawUpdateStatus;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Rate-limit (Decision 9) integration: real Redis INCR via ConcurrencyTestUtils, per-project
// isolation, existing-subscriber bypass, and fail-open. The Redis-down case spies StringRedisTemplate
// and stubs opsForValue() for that one method only — NEVER REDIS.stop(), which would poison the
// shared Testcontainers singleton for every downstream IT subclass.
class SubscriberRateLimitIT extends AbstractIntegrationTest {

    private static final String OWNER_ID = "owner-ratelimit";
    private static final Long TELEGRAM_BOT_ID = 8000001L;

    @Autowired RawUpdateRepository rawUpdateRepository;
    @Autowired BotRepository botRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired SubscriberRepository subscriberRepository;
    @Autowired SubscriberEventRepository subscriberEventRepository;
    @Autowired EventRepository eventRepository;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired ProcessTelegramUpdateJob job;

    @MockitoSpyBean StringRedisTemplate redisTemplate;

    private String projectId;
    private ListAppender<ILoggingEvent> appender;
    private Logger serviceLogger;

    @BeforeEach
    void cleanAndSeed() {
        Mockito.reset(redisTemplate); // drop any per-test stub so the spy delegates to live Redis
        rawUpdateRepository.deleteAll();
        botRepository.deleteAll();
        projectRepository.deleteAll();
        subscriberRepository.deleteAll();
        subscriberEventRepository.deleteAll();
        eventRepository.deleteAll();

        projectId = seedProjectWithBot(TELEGRAM_BOT_ID);

        serviceLogger = (Logger) LoggerFactory.getLogger(SubscriberServiceImpl.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        if (serviceLogger != null && appender != null) {
            serviceLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void parallel101DistinctUsers_persists100AndOneRateLimitEvent() {
        // 101 distinct new /start in one project. INCR returns 1..101; only count==101 (> threshold
        // 100) is dropped → 100 persisted, exactly one rate_limit_exceeded.
        ConcurrentLinkedQueue<String> ids = new ConcurrentLinkedQueue<>(
                seedStarts(projectId, 100_000L, 101));

        ConcurrencyTestUtils.parallelInvoke(101, () -> {
            job.handle(ids.poll());
            return null;
        });

        assertThat(countSubscribers(projectId)).isEqualTo(100L);
        assertThat(rateLimitEvents()).isEqualTo(1L);
    }

    @Test
    void existingSubscriberRestart200Times_noRateLimitEvents() {
        // Register once, then 200 re-/start by the same user. Existing-subscriber lookup happens
        // BEFORE the INCR, so the bucket is never touched — zero rate_limit_exceeded even past 100.
        job.handle(seedStart(projectId, 500_000L, 1L));
        assertThat(countSubscribers(projectId)).isEqualTo(1L);

        for (int i = 0; i < 200; i++) {
            job.handle(seedStart(projectId, 500_000L, 1000L + i));
        }

        assertThat(countSubscribers(projectId)).isEqualTo(1L);
        assertThat(rateLimitEvents()).isZero();
    }

    @Test
    void rateLimit_isolatedPerProject() {
        // 100 new users in each of two projects. The bucket key carries {projectId}, so neither
        // project trips the 100 threshold — all 200 persist, zero rate_limit_exceeded.
        String projectB = seedProjectWithBot(TELEGRAM_BOT_ID + 1);

        List<String> all = new ArrayList<>(seedStarts(projectId, 100_000L, 100));
        all.addAll(seedStarts(projectB, 200_000L, 100));
        ConcurrentLinkedQueue<String> ids = new ConcurrentLinkedQueue<>(all);

        ConcurrencyTestUtils.parallelInvoke(all.size(), () -> {
            job.handle(ids.poll());
            return null;
        });

        assertThat(countSubscribers(projectId)).isEqualTo(100L);
        assertThat(countSubscribers(projectB)).isEqualTo(100L);
        assertThat(rateLimitEvents()).isZero();
    }

    @Test
    void redisDown_failsOpen_proceedsAndLogsWarn() {
        // Stub opsForValue().increment to throw a transport error for THIS test only — the spy keeps
        // the shared Testcontainers Redis alive for every other test.
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> failing = mock(ValueOperations.class);
        when(failing.increment(anyString())).thenThrow(new RedisConnectionFailureException("simulated"));
        doReturn(failing).when(redisTemplate).opsForValue();

        job.handle(seedStart(projectId, 600_000L, 1L));

        assertThat(subscriberRepository.findByProjectIdAndTelegramUserId(projectId, 600_000L))
                .as("fail-open: subscriber must still be persisted when Redis is down")
                .isPresent();
        boolean warned = appender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("SUBSCRIBER_START_RATE_REDIS_FAIL_OPEN"));
        assertThat(warned).as("fail-open path must emit the greppable WARN constant").isTrue();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private String seedProjectWithBot(long telegramBotId) {
        Project p = new Project();
        p.setOwnerId(OWNER_ID);
        p.setName("RateLimit Project " + System.nanoTime());
        p.setTimezone("UTC");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        String id = projectRepository.save(p).getId();

        Bot bot = new Bot();
        bot.setProjectId(id);
        bot.setTelegramBotId(telegramBotId);
        bot.setStatus(BotStatus.CONNECTED);
        bot.setConnectedAt(Instant.now());
        botRepository.save(bot);
        return id;
    }

    private List<String> seedStarts(String project, long baseUserId, int n) {
        List<String> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ids.add(seedStart(project, baseUserId + i, i + 1L));
        }
        return ids;
    }

    private String seedStart(String project, long fromId, long updateId) {
        RawUpdate raw = new RawUpdate();
        raw.setProjectId(project);
        raw.setUpdateId(updateId);
        raw.setPayload(privateStartPayload(fromId));
        raw.setProcessingStatus(RawUpdateStatus.PENDING);
        raw.setCreatedAt(Instant.now());
        return rawUpdateRepository.save(raw).getId();
    }

    private Document privateStartPayload(Long fromId) {
        Document chat = new Document().append("id", fromId).append("type", "private");
        Document message = new Document()
                .append("message_id", 1L)
                .append("chat", chat)
                .append("date", 1700000000L)
                .append("text", "/start")
                .append("from", new Document()
                        .append("id", fromId)
                        .append("is_bot", false)
                        .append("first_name", "Test")
                        .append("language_code", "en"));
        return new Document().append("update_id", fromId).append("message", message);
    }

    private long countSubscribers(String project) {
        return mongoTemplate.count(Query.query(Criteria.where("projectId").is(project)), Subscriber.class);
    }

    private long rateLimitEvents() {
        return eventRepository.findAll().stream()
                .filter(e -> "rate_limit_exceeded".equals(e.getEventType()))
                .count();
    }
}
