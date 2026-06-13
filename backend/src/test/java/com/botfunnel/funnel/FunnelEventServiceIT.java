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

    // ─── Phase 8 (17-funnel-multi-entry / Task 5): redirect-vs-start decision ───

    @Test
    void event_inFlightExecution_redirectsNotStarts() {
        // An event funnel with a mid-entry trigger (entryStepId=entry). The subscriber already has an
        // in-flight execution of this funnel → the dispatch REDIRECTS it (no second row).
        Funnel f = seedEventFunnel("purchase", "entry", "s1", "entry");
        String execId = seedInFlightExecution(f.getId(), ExecutionStatus.waiting_for_reply, "s1");

        funnelEventService.dispatchForSubscriber(projectId, subscriberId, "event", "purchase", 0);

        List<FunnelExecution> rows = executionsFor(f.getId());
        assertThat(rows).hasSize(1);                          // mutated existing, not a new row
        assertThat(rows.get(0).getId()).isEqualTo(execId);
        assertThat(rows.get(0).getCurrentStepId()).isEqualTo("entry"); // cursor moved onto the entry step
    }

    @Test
    void event_noInFlight_startsFreshAtEntryStep() {
        // No in-flight execution → start a fresh one AT the entry step (not step 0).
        Funnel f = seedEventFunnel("purchase", "entry", "s1", "entry");

        funnelEventService.dispatchForSubscriber(projectId, subscriberId, "event", "purchase", 0);

        List<FunnelExecution> started = executionsFor(f.getId());
        assertThat(started).hasSize(1);
        assertThat(started.get(0).getCurrentStepId()).isEqualTo("entry");
        assertThat(started.get(0).getStatus()).isEqualTo(ExecutionStatus.running);
    }

    @Test
    void event_noInFlight_allowReEnterFalse_terminalRowDoesNotBlockFreshInsert() {
        // allowReEnter=false: a TERMINAL (completed) execution does NOT hold the re-enter unique index, so a
        // fresh insert-at-entry succeeds (the swallow only fires on a genuine running|waiting duplicate).
        Funnel f = seedEventFunnel("purchase", "entry", "s1", "entry");
        String terminalId = seedTerminalExecution(f.getId(), ExecutionStatus.completed, "s1");

        funnelEventService.dispatchForSubscriber(projectId, subscriberId, "event", "purchase", 0);

        List<FunnelExecution> active = mongoTemplate.find(Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("funnelId").is(f.getId())
                        .and("status").in("running", "waiting", "waiting_for_reply")), FunnelExecution.class);
        assertThat(active).hasSize(1);                          // a fresh in-flight execution exists
        assertThat(active.get(0).getId()).isNotEqualTo(terminalId);
        assertThat(active.get(0).getCurrentStepId()).isEqualTo("entry");
    }

    @Test
    void event_noInFlight_allowReEnterTrue_cancelThenInsertAtEntry() {
        // allowReEnter=true start-at-entry: cancelExistingForPair then insert a fresh row at the entry step.
        // (No in-flight here, but the cancel-then-insert branch still runs and produces a fresh entry row.)
        Funnel f = seedEventFunnelAllowReEnter("purchase", "entry", "s1", "entry");

        funnelEventService.dispatchForSubscriber(projectId, subscriberId, "event", "purchase", 0);

        List<FunnelExecution> rows = executionsFor(f.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getCurrentStepId()).isEqualTo("entry");
        assertThat(rows.get(0).getStatus()).isEqualTo(ExecutionStatus.running);
    }

    @Test
    void event_crossFunnelIsolation_startsTriggerFunnel_leavesOtherUntouched() {
        // In-flight in funnel B; an event for funnel A starts A and does NOT touch B.
        Funnel a = seedEventFunnel("purchase", "a-entry", "a1", "a-entry");
        Funnel b = seedEventFunnel("other", "b-entry", "b1", "b-entry");
        String bExecId = seedInFlightExecution(b.getId(), ExecutionStatus.waiting_for_reply, "b1");

        funnelEventService.dispatchForSubscriber(projectId, subscriberId, "event", "purchase", 0);

        // A started fresh at its entry; B is untouched (still parked on b1).
        assertThat(executionsFor(a.getId())).hasSize(1);
        assertThat(executionsFor(a.getId()).get(0).getCurrentStepId()).isEqualTo("a-entry");
        FunnelExecution bAfter = mongoTemplate.findById(bExecId, FunnelExecution.class);
        assertThat(bAfter.getStatus()).isEqualTo(ExecutionStatus.waiting_for_reply);
        assertThat(bAfter.getCurrentStepId()).isEqualTo("b1");
    }

    @Test
    void oneEvent_twoFunnels_decidedIndependently() {
        // One event matches two funnels: one has an in-flight execution (→ redirect), the other does not
        // (→ start), decided independently within the fan-out ceiling.
        Funnel inflightFunnel = seedEventFunnel("purchase", "entry-1", "s1", "entry-1");
        Funnel freshFunnel = seedEventFunnel("purchase", "entry-2", "t1", "entry-2");
        String inflightId = seedInFlightExecution(inflightFunnel.getId(),
                ExecutionStatus.waiting_for_reply, "s1");

        funnelEventService.dispatchForSubscriber(projectId, subscriberId, "event", "purchase", 0);

        // Redirected one: still the same single row, cursor moved.
        List<FunnelExecution> redirected = executionsFor(inflightFunnel.getId());
        assertThat(redirected).hasSize(1);
        assertThat(redirected.get(0).getId()).isEqualTo(inflightId);
        assertThat(redirected.get(0).getCurrentStepId()).isEqualTo("entry-1");
        // Started one: a fresh row at its entry step.
        List<FunnelExecution> started = executionsFor(freshFunnel.getId());
        assertThat(started).hasSize(1);
        assertThat(started.get(0).getCurrentStepId()).isEqualTo("entry-2");
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    // Seed an active EVENT funnel with one event trigger (eventName → entryStepId). The two steps carry the
    // given ids so the redirect target resolves in the (deep-copied) snapshot. No CONNECTED-bot send is
    // exercised here — these tests assert the cursor/row decision, not the Telegram send.
    private Funnel seedEventFunnel(String eventName, String entryStepId, String step1Id, String step2Id) {
        Funnel f = new Funnel();
        f.setProjectId(projectId);
        f.setName("ev-" + seq.incrementAndGet());
        f.setStatus(FunnelStatus.active);
        Trigger t = new Trigger();
        t.setTriggerType("event");
        t.setTriggerValue(eventName);
        t.setEntryStepId(entryStepId);
        f.setTriggers(new ArrayList<>(List.of(t)));
        f.setOnStartTriggerValue(null);
        f.setAllowReEnter(false);
        // DELAY steps (non-sending): a redirect drives the entry step, which parks the execution on a delay
        // (status→waiting, cursor stays on the entry step) WITHOUT a Telegram send — this IT has no
        // MockWebServer, so the decision (cursor/row) is asserted without exercising the network.
        f.setSteps(new ArrayList<>(List.of(graphDelayStep(step1Id), graphDelayStep(step2Id))));
        f.setCreatedAt(Instant.now());
        f.setUpdatedAt(Instant.now());
        return funnelRepository.save(f);
    }

    // allowReEnter=true variant of seedEventFunnel.
    private Funnel seedEventFunnelAllowReEnter(String eventName, String entryStepId, String step1Id,
                                               String step2Id) {
        Funnel f = seedEventFunnel(eventName, entryStepId, step1Id, step2Id);
        f.setAllowReEnter(true);
        return funnelRepository.save(f);
    }

    // Seed a TERMINAL execution (completed/cancelled/failed) — it does NOT hold the running|waiting partial
    // unique re-enter index, so a fresh insert for the same pair can proceed.
    private String seedTerminalExecution(String funnelId, ExecutionStatus status, String currentStepId) {
        FunnelExecution e = new FunnelExecution();
        e.setProjectId(projectId);
        e.setFunnelId(funnelId);
        e.setSubscriberId(subscriberId);
        e.setTelegramBotId(TELEGRAM_BOT_ID);
        e.setStatus(status);
        e.setStepRunStatus(StepRunStatus.done);
        e.setCurrentStepId(currentStepId);
        e.setCurrentStepIndex(0);
        e.setStepsSnapshot(new ArrayList<>());
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return mongoTemplate.save(e).getId();
    }

    private static FunnelStep graphDelayStep(String id) {
        FunnelStep s = new FunnelStep();
        s.setId(id);
        s.setStepType(StepType.DELAY);
        s.setDelayValue(1);
        s.setDelayUnit("HOUR");
        return s;
    }

    // Seed an in-flight execution for (funnelId, subscriberId) parked far in the future so the (absent here)
    // sweep never claims it during the test. Snapshot carries the funnel's step ids so the redirect target
    // resolves.
    private String seedInFlightExecution(String funnelId, ExecutionStatus status, String currentStepId) {
        Funnel f = funnelRepository.findById(funnelId).orElseThrow();
        FunnelExecution e = new FunnelExecution();
        e.setProjectId(projectId);
        e.setFunnelId(funnelId);
        e.setSubscriberId(subscriberId);
        e.setTelegramBotId(TELEGRAM_BOT_ID);
        e.setStatus(status);
        e.setStepRunStatus(StepRunStatus.pending);
        e.setNextRunAt(Instant.now().plusSeconds(86400)); // far future — not due
        List<FunnelStep> snapshot = new ArrayList<>();
        for (FunnelStep s : f.getSteps()) {
            snapshot.add(FunnelStep.copyOf(s));
        }
        e.setStepsSnapshot(snapshot);
        e.setCurrentStepId(currentStepId);
        e.setCurrentStepIndex(0);
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return mongoTemplate.save(e).getId();
    }

    private List<FunnelExecution> executionsFor(String funnelId) {
        return mongoTemplate.find(Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("funnelId").is(funnelId)),
                FunnelExecution.class);
    }

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
        // Phase 8: a funnel carries List<Trigger> now. Build the single matching element.
        Trigger t = new Trigger();
        t.setTriggerType(triggerType);
        t.setTriggerValue(triggerValue);
        t.setKeywords(keywords);
        f.setTriggers(new ArrayList<>(List.of(t)));
        f.setOnStartTriggerValue(FunnelService.TRIGGER_ON_START.equals(triggerType)
                && triggerValue != null && !triggerValue.isBlank() ? triggerValue : null);
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
