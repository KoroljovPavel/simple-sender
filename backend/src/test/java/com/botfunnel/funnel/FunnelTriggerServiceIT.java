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
import com.botfunnel.webhook.ProcessTelegramUpdateJob;
import com.botfunnel.webhook.RawUpdate;
import com.botfunnel.webhook.RawUpdateRepository;
import com.botfunnel.webhook.RawUpdateStatus;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// Full-context trigger IT: Testcontainers Mongo + real FunnelTriggerServiceImpl bean. The real service
// resolves the CONNECTED bot, the subscriber (via SubscriberService.findByChat), and the active funnel,
// then inserts a funnel_execution honouring the re-enter guard (unique partial index). Error isolation
// is exercised end-to-end through ProcessTelegramUpdateJob.handle (Decision 6).
class FunnelTriggerServiceIT extends AbstractIntegrationTest {

    private static final Long TELEGRAM_BOT_ID = 778899L;
    private static final Long CHAT_ID = 100L;

    @Autowired FunnelTriggerService funnelTriggerService;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired BotRepository botRepository;
    @Autowired SubscriberRepository subscriberRepository;
    @Autowired FunnelRepository funnelRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired RawUpdateRepository rawUpdateRepository;
    @Autowired ProcessTelegramUpdateJob job;

    @MockitoSpyBean FunnelRepository funnelRepositorySpy;

    private final AtomicLong seq = new AtomicLong(1);
    private String projectId;
    private String botId;

    @BeforeEach
    void cleanAndSeed() {
        mongoTemplate.remove(new Query(), FunnelExecution.class);
        funnelRepository.deleteAll();
        subscriberRepository.deleteAll();
        botRepository.deleteAll();
        projectRepository.deleteAll();
        rawUpdateRepository.deleteAll();
        Mockito.reset(funnelRepositorySpy);

        Project p = new Project();
        p.setOwnerId("owner-" + seq.incrementAndGet());
        p.setName("Test");
        p.setTimezone("UTC");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        projectId = projectRepository.save(p).getId();

        botId = seedConnectedBot();
    }

    // ─── happy path: snapshot + pinned bot ──────────────────────────────────────

    @Test
    void fireCreatesExecutionWithSnapshotAndPinnedBot() {
        String subId = seedActiveSubscriber();
        seedActiveFunnel("ref_x", false, sendMessage("hello"), delay(5, "MIN"), sendMessage("world"));

        funnelTriggerService.fire(projectId, CHAT_ID, "on_start", "ref_x");

        List<FunnelExecution> executions = allExecutions();
        assertThat(executions).hasSize(1);
        FunnelExecution exec = executions.get(0);
        assertThat(exec.getStatus()).isEqualTo(ExecutionStatus.running);
        assertThat(exec.getStepRunStatus()).isEqualTo(StepRunStatus.pending);
        assertThat(exec.getCurrentStepIndex()).isZero();
        assertThat(exec.getSubscriberId()).isEqualTo(subId);
        assertThat(exec.getTelegramBotId()).isEqualTo(TELEGRAM_BOT_ID);
        assertThat(exec.getNextRunAt()).isNotNull();
        assertThat(exec.getStepsSnapshot()).hasSize(3);
        assertThat(exec.getStepsSnapshot().get(0).getText()).isEqualTo("hello");
        assertThat(exec.getStepsSnapshot().get(1).getStepType()).isEqualTo(StepType.DELAY);
    }

    @Test
    void snapshotIsDeepCopyDecoupledFromFunnelEdits() {
        seedActiveSubscriber();
        Funnel funnel = seedActiveFunnel("ref_x", false, sendMessage("original"));

        funnelTriggerService.fire(projectId, CHAT_ID, "on_start", "ref_x");

        // Mutate the funnel's steps AFTER firing — the snapshot must not change.
        funnel.getSteps().get(0).setText("edited");
        funnelRepository.save(funnel);

        FunnelExecution exec = allExecutions().get(0);
        assertThat(exec.getStepsSnapshot().get(0).getText()).isEqualTo("original");
    }

    // ─── exact match incl empty payload ─────────────────────────────────────────

    @Test
    void exactMatchIncludingEmptyPayload() {
        seedActiveSubscriber();
        seedActiveFunnel("", false, sendMessage("bare"));

        // Empty payload matches the empty-triggerValue funnel.
        funnelTriggerService.fire(projectId, CHAT_ID, "on_start", "");
        assertThat(allExecutions()).hasSize(1);

        // A non-empty payload with no matching funnel → no-op.
        mongoTemplate.remove(new Query(), FunnelExecution.class);
        funnelTriggerService.fire(projectId, CHAT_ID, "on_start", "ref_other");
        assertThat(allExecutions()).isEmpty();
    }

    @Test
    void nullPayloadMatchesEmptyTriggerValue() {
        seedActiveSubscriber();
        seedActiveFunnel("", false, sendMessage("bare"));

        funnelTriggerService.fire(projectId, CHAT_ID, "on_start", null);

        assertThat(allExecutions()).hasSize(1);
    }

    @Test
    void nonEmptyPayloadMatchesOnlyExactTriggerValue() {
        seedActiveSubscriber();
        seedActiveFunnel("ref_x", false, sendMessage("x"));

        funnelTriggerService.fire(projectId, CHAT_ID, "on_start", "ref_x");
        assertThat(allExecutions()).hasSize(1);

        mongoTemplate.remove(new Query(), FunnelExecution.class);
        // Different payload → no exact match → no-op.
        funnelTriggerService.fire(projectId, CHAT_ID, "on_start", "ref_y");
        assertThat(allExecutions()).isEmpty();
    }

    // ─── no match → no-op ───────────────────────────────────────────────────────

    @Test
    void noMatchingFunnelIsNoop() {
        seedActiveSubscriber();
        // No funnel seeded at all.
        assertThatCode(() -> funnelTriggerService.fire(projectId, CHAT_ID, "on_start", "ref_x"))
                .doesNotThrowAnyException();
        assertThat(allExecutions()).isEmpty();
    }

    @Test
    void draftFunnelIsNotMatched() {
        seedActiveSubscriber();
        Funnel draft = baseFunnel("ref_x", false, sendMessage("x"));
        draft.setStatus(FunnelStatus.draft);
        funnelRepository.save(draft);

        funnelTriggerService.fire(projectId, CHAT_ID, "on_start", "ref_x");

        assertThat(allExecutions()).isEmpty();
    }

    // ─── re-enter guard ─────────────────────────────────────────────────────────

    @Test
    void reEnterFalseDuplicateKeySwallowedNoop() {
        seedActiveSubscriber();
        seedActiveFunnel("ref_x", false, sendMessage("x"));

        funnelTriggerService.fire(projectId, CHAT_ID, "on_start", "ref_x");
        assertThat(allExecutions()).hasSize(1);
        String firstId = allExecutions().get(0).getId();

        // Second /start with an existing running execution → DuplicateKey from the unique partial index
        // → swallowed; no second execution, no exception.
        assertThatCode(() -> funnelTriggerService.fire(projectId, CHAT_ID, "on_start", "ref_x"))
                .doesNotThrowAnyException();
        List<FunnelExecution> after = allExecutions();
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getId()).isEqualTo(firstId);
    }

    @Test
    void reEnterTrueCancelsAndRestarts() {
        seedActiveSubscriber();
        seedActiveFunnel("ref_x", true, sendMessage("x"));

        funnelTriggerService.fire(projectId, CHAT_ID, "on_start", "ref_x");
        String oldId = allExecutions().get(0).getId();

        funnelTriggerService.fire(projectId, CHAT_ID, "on_start", "ref_x");

        List<FunnelExecution> all = allExecutions();
        assertThat(all).hasSize(2);
        FunnelExecution oldExec = mongoTemplate.findById(oldId, FunnelExecution.class);
        assertThat(oldExec.getStatus()).isEqualTo(ExecutionStatus.cancelled);
        // The new one is running from step 0.
        FunnelExecution fresh = all.stream()
                .filter(e -> !e.getId().equals(oldId)).findFirst().orElseThrow();
        assertThat(fresh.getStatus()).isEqualTo(ExecutionStatus.running);
        assertThat(fresh.getCurrentStepIndex()).isZero();
    }

    // ─── cancelActiveFor ────────────────────────────────────────────────────────

    @Test
    void cancelActiveForCancelsRunningAndWaiting() {
        String subId = seedActiveSubscriber();
        String running = seedExecution(subId, ExecutionStatus.running);
        String waiting = seedExecution(subId, ExecutionStatus.waiting);
        String completed = seedExecution(subId, ExecutionStatus.completed);
        String failed = seedExecution(subId, ExecutionStatus.failed);
        String alreadyCancelled = seedExecution(subId, ExecutionStatus.cancelled);

        funnelTriggerService.cancelActiveFor(projectId, CHAT_ID);

        assertThat(reload(running).getStatus()).isEqualTo(ExecutionStatus.cancelled);
        assertThat(reload(waiting).getStatus()).isEqualTo(ExecutionStatus.cancelled);
        // Terminal states are untouched.
        assertThat(reload(completed).getStatus()).isEqualTo(ExecutionStatus.completed);
        assertThat(reload(failed).getStatus()).isEqualTo(ExecutionStatus.failed);
        assertThat(reload(alreadyCancelled).getStatus()).isEqualTo(ExecutionStatus.cancelled);
    }

    // ─── no bot / no subscriber → log + skip ────────────────────────────────────

    @Test
    void noConnectedBotOrNoSubscriberIsNoop() {
        // No CONNECTED bot.
        botRepository.deleteAll();
        seedActiveFunnel("ref_x", false, sendMessage("x"));
        assertThatCode(() -> funnelTriggerService.fire(projectId, CHAT_ID, "on_start", "ref_x"))
                .doesNotThrowAnyException();
        assertThat(allExecutions()).isEmpty();

        // Bot present, but no subscriber.
        seedConnectedBot();
        assertThatCode(() -> funnelTriggerService.fire(projectId, CHAT_ID, "on_start", "ref_x"))
                .doesNotThrowAnyException();
        assertThat(allExecutions()).isEmpty();
    }

    // ─── HIGH: fire() error isolation (Decision 6) ──────────────────────────────

    @Test
    void fireErrorIsolationSwallowsAndDoesNotFailWebhook() {
        seedActiveSubscriber();
        seedActiveFunnel("ref_x", false, sendMessage("x"));

        // Force a fault deep inside fire() by making the funnel lookup throw.
        Mockito.doThrow(new RuntimeException("boom inside fire"))
                .when(funnelRepositorySpy)
                .findByProjectIdAndTriggerTypeAndTriggerValueAndStatus(
                        Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                        Mockito.any(FunnelStatus.class));

        // 1) Direct call: fire() must NOT throw outward.
        assertThatCode(() -> funnelTriggerService.fire(projectId, CHAT_ID, "on_start", "ref_x"))
                .doesNotThrowAnyException();
        assertThat(allExecutions()).isEmpty();

        // 2) Through the webhook worker: the raw_update must finish DONE (not FAILED), no retry-storm.
        RawUpdate raw = seedRawUpdate(privateStart("ref_x"));
        assertThatCode(() -> job.handle(raw.getId())).doesNotThrowAnyException();
        RawUpdate reloaded = rawUpdateRepository.findById(raw.getId()).orElseThrow();
        assertThat(reloaded.getProcessingStatus()).isEqualTo(RawUpdateStatus.DONE);
        assertThat(reloaded.getProcessingError()).isNull();
    }

    // ─── HIGH: reactivation ordering — first send not self-cancelled ────────────

    @Test
    void reactivationOrderingFirstSendNotSelfCancelled() {
        // A previously-unsubscribed subscriber sends /start. The worker upserts (status→ACTIVE) BEFORE
        // fire(), so the execution fire() creates references an ACTIVE subscriber and would pass the
        // engine's pre-send status gate (not self-cancel on first send).
        Subscriber sub = new Subscriber();
        sub.setProjectId(projectId);
        sub.setTelegramUserId(CHAT_ID);
        sub.setTelegramChatId(CHAT_ID);
        sub.setTelegramBotId(TELEGRAM_BOT_ID);
        sub.setStatus(SubscriberStatus.UNSUBSCRIBED);
        sub.setSubscribedAt(Instant.now());
        sub.setLastSeenAt(Instant.now());
        subscriberRepository.save(sub);

        seedActiveFunnel("", false, sendMessage("welcome-back"));

        RawUpdate raw = seedRawUpdate(privateStart(""));
        job.handle(raw.getId());

        // Worker order guarantee: upsert flipped the subscriber back to ACTIVE before fire().
        Subscriber after = subscriberRepository
                .findByProjectIdAndTelegramBotIdAndTelegramChatId(projectId, TELEGRAM_BOT_ID, CHAT_ID)
                .orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SubscriberStatus.ACTIVE);

        // The execution exists, running, NOT cancelled — its subscriber is ACTIVE so the engine's
        // pre-send gate would pass.
        List<FunnelExecution> all = allExecutions();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getStatus()).isEqualTo(ExecutionStatus.running);
        assertThat(all.get(0).getSubscriberId()).isEqualTo(after.getId());
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private List<FunnelExecution> allExecutions() {
        return mongoTemplate.findAll(FunnelExecution.class);
    }

    private FunnelExecution reload(String id) {
        return mongoTemplate.findById(id, FunnelExecution.class);
    }

    private Funnel baseFunnel(String triggerValue, boolean allowReEnter, FunnelStep... steps) {
        Funnel f = new Funnel();
        f.setProjectId(projectId);
        f.setName("funnel-" + seq.incrementAndGet());
        f.setStatus(FunnelStatus.active);
        f.setTriggerType("on_start");
        f.setTriggerValue(triggerValue);
        f.setAllowReEnter(allowReEnter);
        List<FunnelStep> list = new ArrayList<>(List.of(steps));
        f.setSteps(list);
        f.setCreatedAt(Instant.now());
        f.setUpdatedAt(Instant.now());
        return f;
    }

    private Funnel seedActiveFunnel(String triggerValue, boolean allowReEnter, FunnelStep... steps) {
        return funnelRepository.save(baseFunnel(triggerValue, allowReEnter, steps));
    }

    private FunnelStep sendMessage(String text) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.SEND_MESSAGE);
        s.setText(text);
        return s;
    }

    private FunnelStep delay(int value, String unit) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.DELAY);
        s.setDelayValue(value);
        s.setDelayUnit(unit);
        return s;
    }

    private String seedExecution(String subscriberId, ExecutionStatus status) {
        FunnelExecution e = new FunnelExecution();
        e.setProjectId(projectId);
        e.setFunnelId("funnel-" + seq.incrementAndGet());
        e.setSubscriberId(subscriberId);
        e.setTelegramBotId(TELEGRAM_BOT_ID);
        e.setStatus(status);
        e.setCurrentStepIndex(0);
        e.setStepRunStatus(StepRunStatus.pending);
        e.setNextRunAt(Instant.now());
        e.setStepsSnapshot(new ArrayList<>());
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return mongoTemplate.insert(e).getId();
    }

    private String seedActiveSubscriber() {
        Subscriber s = new Subscriber();
        s.setProjectId(projectId);
        s.setTelegramUserId(CHAT_ID);
        s.setTelegramChatId(CHAT_ID);
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
        bot.setTelegramUsername("trigger_bot");
        bot.setStatus(BotStatus.CONNECTED);
        bot.setConnectedAt(Instant.now());
        return botRepository.save(bot).getId();
    }

    private RawUpdate seedRawUpdate(Document payload) {
        RawUpdate raw = new RawUpdate();
        raw.setProjectId(projectId);
        raw.setUpdateId(seq.incrementAndGet());
        raw.setPayload(payload);
        raw.setProcessingStatus(RawUpdateStatus.PENDING);
        raw.setCreatedAt(Instant.now());
        return rawUpdateRepository.save(raw);
    }

    private Document privateStart(String payload) {
        String text = payload == null || payload.isEmpty() ? "/start" : "/start " + payload;
        Document chat = new Document().append("id", CHAT_ID).append("type", "private");
        Document message = new Document()
                .append("message_id", 1L)
                .append("chat", chat)
                .append("date", 1700000000L)
                .append("text", text)
                .append("from", new Document()
                        .append("id", CHAT_ID)
                        .append("is_bot", false)
                        .append("first_name", "Test")
                        .append("username", "testuser")
                        .append("language_code", "en"));
        return new Document().append("update_id", seq.incrementAndGet()).append("message", message);
    }
}
