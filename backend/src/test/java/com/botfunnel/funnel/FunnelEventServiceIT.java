package com.botfunnel.funnel;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberRepository;
import com.botfunnel.subscriber.SubscriberStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Embedded-Mongo IT for the fan-out dispatcher (sibling to {@code FunnelTriggerServiceIT}). Boots a real
 * Spring context (so it doubles as the bean-graph startup check: {@link FunnelEventService} +
 * {@link FunnelExecutionFactory} wire with no {@code BeanCurrentlyInCreationException}). Each trigger
 * type starts the right funnel; fan-out proves the Task-1 index relax; the depth-cap and fan-out-ceiling
 * backstops are exercised with Redis unavailable (Redis-independent), and the volume limit's Redis-down
 * fail-open is asserted.
 *
 * <p>Redis-down cases spy {@link StringRedisTemplate} and stub {@code opsForValue()} for the one call —
 * NEVER {@code REDIS.stop()}, which would poison the shared Testcontainers singleton for every
 * downstream IT.
 */
class FunnelEventServiceIT extends AbstractIntegrationTest {

    private static final Long TELEGRAM_BOT_ID = 778899L;

    @Autowired FunnelEventService funnelEventService;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired BotRepository botRepository;
    @Autowired SubscriberRepository subscriberRepository;
    @Autowired FunnelRepository funnelRepository;
    @Autowired ProjectRepository projectRepository;

    @MockitoSpyBean StringRedisTemplate redisTemplate;

    private final AtomicLong seq = new AtomicLong(1);
    private String projectId;
    private String subscriberId;

    @BeforeEach
    void cleanAndSeed() {
        Mockito.reset(redisTemplate); // drop any per-test stub so the spy delegates to live Redis
        mongoTemplate.remove(new Query(), FunnelExecution.class);
        funnelRepository.deleteAll();
        subscriberRepository.deleteAll();
        botRepository.deleteAll();
        projectRepository.deleteAll();

        Project p = new Project();
        p.setOwnerId("owner-" + seq.incrementAndGet());
        p.setName("Test");
        p.setTimezone("UTC");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        projectId = projectRepository.save(p).getId();

        seedConnectedBot();
        subscriberId = seedActiveSubscriber();
    }

    @AfterEach
    void resetSpy() {
        Mockito.reset(redisTemplate);
    }

    // ─── each trigger type starts the correct funnel ────────────────────────

    @Test
    void eachTriggerTypeStartsCorrectFunnelForSubscriber() {
        Funnel eventFunnel = seedFunnel("event", "purchase", null);
        Funnel tagFunnel = seedFunnel("tag_added", "vip", null);
        Funnel fieldFunnel = seedFunnel("custom_field_set", "plan", null);
        Funnel keywordFunnel = seedFunnel("keyword", "", List.of("promo"));

        funnelEventService.dispatchForSubscriber(projectId, subscriberId, "event", "purchase", 0);
        funnelEventService.dispatchForSubscriber(projectId, subscriberId, "tag_added", "vip", 0);
        funnelEventService.dispatchForSubscriber(projectId, subscriberId, "custom_field_set", "plan", 0);
        funnelEventService.dispatchForSubscriber(projectId, subscriberId, "keyword", "grab the PROMO now", 0);

        assertThat(funnelIdsOfExecutions())
                .containsExactlyInAnyOrder(eventFunnel.getId(), tagFunnel.getId(),
                        fieldFunnel.getId(), keywordFunnel.getId());
    }

    @Test
    void dispatch_unknownTriggerValue_startsNothing() {
        seedFunnel("event", "purchase", null);

        funnelEventService.dispatchForSubscriber(projectId, subscriberId, "event", "refund", 0);

        assertThat(allExecutions()).isEmpty();
    }

    // ─── fan-out: one firing → N executions (proves the Task-1 index relax) ──

    @Test
    void fanout_oneFiringStartsNExecutions() {
        Funnel a = seedFunnel("event", "purchase", null);
        Funnel b = seedFunnel("event", "purchase", null); // same trigger value — coexist post index-relax

        funnelEventService.dispatchForSubscriber(projectId, subscriberId, "event", "purchase", 0);

        List<FunnelExecution> executions = allExecutions();
        assertThat(executions).hasSize(2);
        assertThat(funnelIdsOfExecutions()).containsExactlyInAnyOrder(a.getId(), b.getId());
        assertThat(executions).allSatisfy(e -> assertThat(e.getSubscriberId()).isEqualTo(subscriberId));
        assertThat(executions).allSatisfy(e -> assertThat(e.getEnrollDepth()).isZero());
    }

    @Test
    void fanout_stampsOriginDepthOnEachExecution() {
        seedFunnel("event", "purchase", null);
        seedFunnel("event", "purchase", null);

        funnelEventService.dispatchForSubscriber(projectId, subscriberId, "event", "purchase", 4);

        assertThat(allExecutions()).hasSize(2)
                .allSatisfy(e -> assertThat(e.getEnrollDepth()).isEqualTo(4));
    }

    // ─── anti-cycle (depth) — Redis-independent ─────────────────────────────

    @Test
    void depthCap_dropsAboveCap_evenWhenRedisDown() {
        seedFunnel("event", "purchase", null);
        breakRedis();

        // originDepth 11 > cap 10 → dropped before any insert, with Redis unavailable.
        funnelEventService.dispatchForSubscriber(projectId, subscriberId, "event", "purchase", 11);

        assertThat(allExecutions()).isEmpty();
    }

    // ─── anti-cycle (fan-out width) — Redis-independent ─────────────────────

    @Test
    void fanoutCeiling_insertsAtMostN_evenWhenRedisDown() {
        // Default ceiling is 50; seed 51 listener funnels sharing the trigger value.
        for (int i = 0; i < 51; i++) {
            seedFunnel("event", "purchase", null);
        }
        breakRedis();

        // depth 0 → Redis never consulted anyway; ceiling still bounds width regardless.
        funnelEventService.dispatchForSubscriber(projectId, subscriberId, "event", "purchase", 0);

        assertThat(allExecutions()).hasSize(50);
    }

    // ─── anti-cycle (volume) — Redis fail-open ──────────────────────────────

    @Test
    void volumeLimit_redisDown_failsOpen() {
        seedFunnel("event", "purchase", null);
        breakRedis();

        // depth>0 would consult Redis; with Redis down the limiter fails open → dispatch proceeds.
        funnelEventService.dispatchForSubscriber(projectId, subscriberId, "event", "purchase", 2);

        assertThat(allExecutions()).hasSize(1);
        assertThat(allExecutions().get(0).getEnrollDepth()).isEqualTo(2);
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private void breakRedis() {
        ValueOperations<String, String> ops = mock();
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenThrow(new RuntimeException("redis down"));
    }

    private List<FunnelExecution> allExecutions() {
        return mongoTemplate.findAll(FunnelExecution.class);
    }

    private List<String> funnelIdsOfExecutions() {
        return allExecutions().stream().map(FunnelExecution::getFunnelId).toList();
    }

    private Funnel seedFunnel(String triggerType, String triggerValue, List<String> keywords) {
        Funnel f = new Funnel();
        f.setProjectId(projectId);
        f.setName("f-" + seq.incrementAndGet());
        f.setStatus(FunnelStatus.active);
        f.setTriggerType(triggerType);
        f.setTriggerValue(triggerValue);
        f.setKeywords(keywords);
        f.setAllowReEnter(false);
        FunnelStep step = new FunnelStep();
        step.setStepType(StepType.MESSAGE);
        step.setBlocks(List.of(new ContentBlock(BlockType.TEXT, "hi", null, null, null, null)));
        f.setSteps(new ArrayList<>(List.of(step)));
        f.setCreatedAt(Instant.now());
        f.setUpdatedAt(Instant.now());
        return funnelRepository.save(f);
    }

    private String seedActiveSubscriber() {
        Subscriber s = new Subscriber();
        s.setProjectId(projectId);
        s.setTelegramUserId(100L);
        s.setTelegramChatId(100L);
        s.setTelegramBotId(TELEGRAM_BOT_ID);
        s.setStatus(SubscriberStatus.ACTIVE);
        s.setSubscribedAt(Instant.now());
        s.setLastSeenAt(Instant.now());
        return subscriberRepository.save(s).getId();
    }

    private String seedConnectedBot() {
        Bot bot = new Bot();
        bot.setProjectId(projectId);
        bot.setTelegramBotId(TELEGRAM_BOT_ID);
        bot.setTelegramUsername("event_bot");
        bot.setStatus(BotStatus.CONNECTED);
        bot.setConnectedAt(Instant.now());
        return botRepository.save(bot).getId();
    }
}
