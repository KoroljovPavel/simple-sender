package com.botfunnel.funnel;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.events.Event;
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
//
// Composer migration (15-message-composer / Task 5): seed steps are now StepType.MESSAGE composer steps
// carrying a List<ContentBlock> blocks; the inline keyboard + park-on-reply timeout stay step-level
// fields (Decision 2), with the keyboard conceptually pinned to the last non-album block. The
// callback-path tests below cover advanceOnCallback's full negative matrix + positive park+branch after
// the guard flipped from "cursor must be a MENU step" to the positive "cursor must be a MESSAGE step".
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
        mongoTemplate.remove(new Query(), Event.class);
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
        seedActiveFunnel("ref_x", false, message("hello"), delay(5, "MIN"), message("world"));

        Instant before = Instant.now();
        funnelTriggerService.fire(projectId, CHAT_ID, "on_start", "ref_x");
        Instant after = Instant.now();

        List<FunnelExecution> executions = allExecutions();
        assertThat(executions).hasSize(1);
        FunnelExecution exec = executions.get(0);
        assertThat(exec.getStatus()).isEqualTo(ExecutionStatus.running);
        assertThat(exec.getStepRunStatus()).isEqualTo(StepRunStatus.pending);
        assertThat(exec.getCurrentStepIndex()).isZero();
        assertThat(exec.getSubscriberId()).isEqualTo(subId);
        assertThat(exec.getTelegramBotId()).isEqualTo(TELEGRAM_BOT_ID);
        // nextRunAt = fire-time "now" so the nearest sweep picks it up — pin it to the [before, after]
        // window (kills a mutant that leaves it null/epoch/far-future), not merely non-null.
        assertThat(exec.getNextRunAt()).isNotNull().isBetween(before, after);
        assertThat(exec.getStepsSnapshot()).hasSize(3);
        assertThat(exec.getStepsSnapshot().get(0).getStepType()).isEqualTo(StepType.MESSAGE);
        assertThat(exec.getStepsSnapshot().get(0).getBlocks().get(0).text()).isEqualTo("hello");
        assertThat(exec.getStepsSnapshot().get(1).getStepType()).isEqualTo(StepType.DELAY);
    }

    @Test
    void snapshotIsDeepCopyDecoupledFromFunnelEdits() {
        seedActiveSubscriber();
        Funnel funnel = seedActiveFunnel("ref_x", false, message("original"));

        funnelTriggerService.fire(projectId, CHAT_ID, "on_start", "ref_x");

        // Mutate the funnel's blocks AFTER firing — the snapshot must not change. blocks is a defensively
        // copied list of immutable ContentBlock records, so swapping an element on the source list must
        // not bleed into the already-snapshotted execution.
        funnel.getSteps().get(0).setBlocks(new ArrayList<>(List.of(textBlock("edited"))));
        funnelRepository.save(funnel);

        FunnelExecution exec = allExecutions().get(0);
        assertThat(exec.getStepsSnapshot().get(0).getBlocks().get(0).text()).isEqualTo("original");
        // Identity guard: the snapshot must NOT alias the source funnel's step instances — a shallow
        // copy (e.g. new ArrayList<>(steps)) would share them. Per-field deep-copy independence is
        // additionally pinned by FunnelStepTest.deepCopyProducesIndependentStep.
        assertThat(exec.getStepsSnapshot().get(0)).isNotSameAs(funnel.getSteps().get(0));
    }

    // ─── exact match incl empty payload ─────────────────────────────────────────

    @Test
    void exactMatchIncludingEmptyPayload() {
        seedActiveSubscriber();
        seedActiveFunnel("", false, message("bare"));

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
        seedActiveFunnel("", false, message("bare"));

        funnelTriggerService.fire(projectId, CHAT_ID, "on_start", null);

        assertThat(allExecutions()).hasSize(1);
    }

    @Test
    void nonEmptyPayloadMatchesOnlyExactTriggerValue() {
        seedActiveSubscriber();
        seedActiveFunnel("ref_x", false, message("x"));

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
        Funnel draft = baseFunnel("ref_x", false, message("x"));
        draft.setStatus(FunnelStatus.draft);
        funnelRepository.save(draft);

        funnelTriggerService.fire(projectId, CHAT_ID, "on_start", "ref_x");

        assertThat(allExecutions()).isEmpty();
    }

    // ─── re-enter guard ─────────────────────────────────────────────────────────

    @Test
    void reEnterFalseDuplicateKeySwallowedNoop() {
        seedActiveSubscriber();
        seedActiveFunnel("ref_x", false, message("x"));

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
        seedActiveFunnel("ref_x", true, message("x"));

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
        seedActiveFunnel("ref_x", false, message("x"));
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
        seedActiveFunnel("ref_x", false, message("x"));

        // Force a fault deep inside fire() by making the funnel lookup throw. Phase 8: fire()'s on_start
        // lookup now goes through the array-aware $elemMatch query over triggers[] (Task 5 — the flat
        // findByProjectIdAndTriggerTypeAndTriggerValueAndStatus was removed in Task 2).
        Mockito.doThrow(new RuntimeException("boom inside fire"))
                .when(funnelRepositorySpy)
                .findByProjectIdAndTriggersTriggerTypeAndTriggersTriggerValueAndStatus(
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

        seedActiveFunnel("", false, message("welcome-back"));

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

    // ─── advanceOnCallback: positive park + branch (composer MESSAGE step) ───────

    @Test
    void advanceOnCallback_messageStepWithButtons_parksAndBranchesViaResumeOnCallback() {
        String subId = seedActiveSubscriber();
        // Parked MESSAGE composer step with 2 callback buttons; button[0] branches to a DELAY target step
        // (non-sending, so the branch advance is provable without depending on the MESSAGE send path).
        FunnelStep menuStep = messageWithButtons("m1", "m2",
                callbackButton("Go", "m2"), callbackButton("Stop", null));
        FunnelStep target = delayStep("m2", 5, "MIN");
        String execId = seedParkedExecution(subId, menuStep, target);

        funnelTriggerService.advanceOnCallback(projectId, CHAT_ID, execId + ":0", "cbq-1");

        FunnelExecution after = reload(execId);
        // Branch navigated to button[0].targetStepId == "m2" (the DELAY step) → engine parked it waiting.
        assertThat(after.getStatus()).isEqualTo(ExecutionStatus.waiting);
        assertThat(after.getCurrentStepId()).isEqualTo("m2");
        // No longer parked waiting_for_reply on the message step — the callback was consumed.
        assertThat(after.getStatus()).isNotEqualTo(ExecutionStatus.waiting_for_reply);
    }

    @Test
    void advanceOnCallback_recordsLastButtonClickedAsCurrentStepColonIndex() {
        String subId = seedActiveSubscriber();
        FunnelStep menuStep = messageWithButtons("m1", "m2",
                callbackButton("A", null), callbackButton("B", null));
        String execId = seedParkedExecution(subId, menuStep);

        funnelTriggerService.advanceOnCallback(projectId, CHAT_ID, execId + ":1", "cbq-2");

        // lastButtonClicked is the clicked button's coordinate: the parked step id ("m1") + ":" + index.
        assertThat(reload(execId).getLastButtonClicked()).isEqualTo("m1:1");
    }

    @Test
    void advanceOnCallback_recordsFunnelButtonClickedExactlyOnce() {
        String subId = seedActiveSubscriber();
        FunnelStep menuStep = messageWithButtons("m1", "m2", callbackButton("A", null));
        String execId = seedParkedExecution(subId, menuStep);

        // First click wins the CAS → exactly one funnel_button_clicked event.
        funnelTriggerService.advanceOnCallback(projectId, CHAT_ID, execId + ":0", "cbq-1");
        // Second (duplicate) click loses the CAS (no longer waiting_for_reply) → no extra event.
        funnelTriggerService.advanceOnCallback(projectId, CHAT_ID, execId + ":0", "cbq-2");

        assertThat(buttonClickedEvents(subId)).hasSize(1);
    }

    // ─── advanceOnCallback: negative matrix (all reject, no advance) ─────────────

    @Test
    void advanceOnCallback_executionNotWaitingForReply_ignored() {
        String subId = seedActiveSubscriber();
        FunnelStep menuStep = messageWithButtons("m1", "m2", callbackButton("A", null));
        // Seed a RUNNING (not parked) execution — a stale/duplicate webhook for an already-resumed run.
        String execId = seedExecutionWithSnapshot(subId, ExecutionStatus.running, "m1", menuStep);

        funnelTriggerService.advanceOnCallback(projectId, CHAT_ID, execId + ":0", "cbq-1");

        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.running);
        assertThat(reload(execId).getCurrentStepId()).isEqualTo("m1");
        assertThat(buttonClickedEvents(subId)).isEmpty();
    }

    @Test
    void advanceOnCallback_brokenFormat_rejected() {
        String subId = seedActiveSubscriber();
        FunnelStep menuStep = messageWithButtons("m1", "m2", callbackButton("A", null));
        String execId = seedParkedExecution(subId, menuStep);

        // No ':' / extra segment / empty — each must reject up front, no advance.
        for (String data : List.of(execId, execId + ":0:0", "")) {
            funnelTriggerService.advanceOnCallback(projectId, CHAT_ID, data, "cbq");
        }

        assertParked(execId);
        assertThat(buttonClickedEvents(subId)).isEmpty();
    }

    @Test
    void advanceOnCallback_over64Bytes_rejected() {
        String subId = seedActiveSubscriber();
        FunnelStep menuStep = messageWithButtons("m1", "m2", callbackButton("A", null));
        String execId = seedParkedExecution(subId, menuStep);

        // 24-hex execId + ':' + a long numeric tail pushes total > 64 bytes → reject before any DB lookup.
        String oversized = execId + ":" + "1".repeat(64);
        funnelTriggerService.advanceOnCallback(projectId, CHAT_ID, oversized, "cbq");

        assertParked(execId);
        assertThat(buttonClickedEvents(subId)).isEmpty();
    }

    @Test
    void advanceOnCallback_nonHexExecutionId_rejected() {
        String subId = seedActiveSubscriber();
        FunnelStep menuStep = messageWithButtons("m1", "m2", callbackButton("A", null));
        seedParkedExecution(subId, menuStep);

        // Left segment is 24 chars but not hex ('z') → fails the ObjectId-hex shape check.
        funnelTriggerService.advanceOnCallback(projectId, CHAT_ID, "z".repeat(24) + ":0", "cbq");

        // The real execution is untouched; assert globally no event fired.
        assertThat(buttonClickedEvents(subId)).isEmpty();
        assertThat(allExecutions()).allSatisfy(
                e -> assertThat(e.getStatus()).isEqualTo(ExecutionStatus.waiting_for_reply));
    }

    @Test
    void advanceOnCallback_buttonIndexOutOfRange_rejected() {
        String subId = seedActiveSubscriber();
        // Only 2 buttons (index 0..1); index 5 is in-shape (<= MAX_BUTTON_INDEX) but out of range.
        FunnelStep menuStep = messageWithButtons("m1", "m2",
                callbackButton("A", null), callbackButton("B", null));
        String execId = seedParkedExecution(subId, menuStep);

        funnelTriggerService.advanceOnCallback(projectId, CHAT_ID, execId + ":5", "cbq");

        assertParked(execId);
        assertThat(buttonClickedEvents(subId)).isEmpty();
    }

    @Test
    void advanceOnCallback_nonCallbackButton_url_rejected() {
        String subId = seedActiveSubscriber();
        // Index 0 is a URL button (type != "callback") → must NOT advance the funnel.
        FunnelStep menuStep = messageWithButtons("m1", "m2", urlButton("Open", "https://example.com"));
        String execId = seedParkedExecution(subId, menuStep);

        funnelTriggerService.advanceOnCallback(projectId, CHAT_ID, execId + ":0", "cbq");

        assertParked(execId);
        assertThat(buttonClickedEvents(subId)).isEmpty();
    }

    @Test
    void advanceOnCallback_idorCrossSubscriber_rejected() {
        String ownerId = seedActiveSubscriber();
        // A second, different subscriber in the SAME project forges a callback with the owner's execId.
        String attackerId = seedActiveSubscriber(200L, 200L);
        FunnelStep menuStep = messageWithButtons("m1", "m2", callbackButton("A", null));
        String execId = seedParkedExecution(ownerId, menuStep);

        // chatId=200 resolves to the attacker subscriber; owner check (subscriberId mismatch) → no-op.
        funnelTriggerService.advanceOnCallback(projectId, 200L, execId + ":0", "cbq");

        assertParked(execId);
        assertThat(buttonClickedEvents(ownerId)).isEmpty();
        assertThat(buttonClickedEvents(attackerId)).isEmpty();
    }

    @Test
    void advanceOnCallback_idorCrossProject_rejected() {
        String subId = seedActiveSubscriber();
        FunnelStep menuStep = messageWithButtons("m1", "m2", callbackButton("A", null));
        String execId = seedParkedExecution(subId, menuStep);

        // A second project (with its own CONNECTED bot + subscriber on the same chatId) attempts to
        // resume an execution that belongs to the first project → projectId mismatch → no-op. The second
        // bot needs a DISTINCT telegramBotId (the bots collection has a unique-CONNECTED index on it).
        Project p2 = new Project();
        p2.setOwnerId("owner-" + seq.incrementAndGet());
        p2.setName("Other");
        p2.setTimezone("UTC");
        p2.setCreatedAt(Instant.now());
        p2.setUpdatedAt(Instant.now());
        String otherProjectId = projectRepository.save(p2).getId();
        Long otherBotId = TELEGRAM_BOT_ID + 1;
        seedConnectedBotFor(otherProjectId, otherBotId);
        seedActiveSubscriberFor(otherProjectId, otherBotId, CHAT_ID, CHAT_ID);

        funnelTriggerService.advanceOnCallback(otherProjectId, CHAT_ID, execId + ":0", "cbq");

        assertParked(execId);
        assertThat(buttonClickedEvents(subId)).isEmpty();
    }

    @Test
    void advanceOnCallback_cursorNotMessageStep_rejected() {
        String subId = seedActiveSubscriber();
        // Cursor points at a DELAY step (non-MESSAGE) but the execution is somehow parked
        // waiting_for_reply — the new positive MESSAGE guard rejects fail-closed.
        FunnelStep delayCursor = delayStep("m1", 5, "MIN");
        delayCursor.setButtons(List.of(callbackButton("A", null)));
        String execId = seedParkedExecution(subId, delayCursor);

        funnelTriggerService.advanceOnCallback(projectId, CHAT_ID, execId + ":0", "cbq");

        assertParked(execId);
        assertThat(buttonClickedEvents(subId)).isEmpty();
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private List<FunnelExecution> allExecutions() {
        return mongoTemplate.findAll(FunnelExecution.class);
    }

    private FunnelExecution reload(String id) {
        return mongoTemplate.findById(id, FunnelExecution.class);
    }

    private void assertParked(String execId) {
        FunnelExecution e = reload(execId);
        assertThat(e.getStatus()).isEqualTo(ExecutionStatus.waiting_for_reply);
        assertThat(e.getLastButtonClicked()).isNull();
    }

    private List<Event> buttonClickedEvents(String subscriberId) {
        return mongoTemplate.find(
                Query.query(Criteria.where("userId").is(subscriberId)
                        .and("eventType").is(FunnelTriggerServiceImpl.EVENT_FUNNEL_BUTTON_CLICKED)),
                Event.class);
    }

    private Funnel baseFunnel(String triggerValue, boolean allowReEnter, FunnelStep... steps) {
        Funnel f = new Funnel();
        f.setProjectId(projectId);
        f.setName("funnel-" + seq.incrementAndGet());
        f.setStatus(FunnelStatus.active);
        // Phase 8: a funnel carries List<Trigger> + a denormalized onStartTriggerValue scalar. Build a
        // single on_start element with the given value; the scalar syncs to null for a bare "" (Variant A).
        Trigger t = new Trigger();
        t.setTriggerType("on_start");
        t.setTriggerValue(triggerValue);
        f.setTriggers(new ArrayList<>(List.of(t)));
        f.setOnStartTriggerValue(triggerValue == null || triggerValue.isBlank() ? null : triggerValue);
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

    private static ContentBlock textBlock(String text) {
        return new ContentBlock(BlockType.TEXT, text, null, null, null, null);
    }

    // Composer MESSAGE step (15-message-composer / Decision 1): a single TEXT block. The keyboard, if any,
    // is conceptually pinned to the last non-album block — here it stays a step-level field (Decision 2).
    private FunnelStep message(String text) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.MESSAGE);
        s.setBlocks(new ArrayList<>(List.of(textBlock(text))));
        return s;
    }

    // Graph-shaped composer MESSAGE step with step-level callback buttons (Decision 2). The single TEXT
    // block is the last non-album block to which the keyboard attaches at send time (Task 3); here only
    // the step-level getButtons() index-addressing matters to advanceOnCallback.
    private FunnelStep messageWithButtons(String id, String next, Button... buttons) {
        FunnelStep s = new FunnelStep();
        s.setId(id);
        s.setNext(next);
        s.setStepType(StepType.MESSAGE);
        s.setBlocks(new ArrayList<>(List.of(textBlock("pick one"))));
        s.setButtons(new ArrayList<>(List.of(buttons)));
        return s;
    }

    private FunnelStep delay(int value, String unit) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.DELAY);
        s.setDelayValue(value);
        s.setDelayUnit(unit);
        return s;
    }

    private FunnelStep delayStep(String id, int value, String unit) {
        FunnelStep s = delay(value, unit);
        s.setId(id);
        return s;
    }

    private static Button callbackButton(String label, String targetStepId) {
        return new Button("callback", label, targetStepId, null);
    }

    private static Button urlButton(String label, String url) {
        return new Button("url", label, null, url);
    }

    // Seed an execution parked waiting_for_reply on the FIRST step (its id = currentStepId,
    // stepRunStatus=pending) so claimForCallback can win the CAS. Snapshot carries all passed steps.
    private String seedParkedExecution(String subscriberId, FunnelStep... steps) {
        return seedExecutionWithSnapshot(subscriberId, ExecutionStatus.waiting_for_reply,
                steps[0].getId(), steps);
    }

    private String seedExecutionWithSnapshot(String subscriberId, ExecutionStatus status,
                                             String currentStepId, FunnelStep... steps) {
        FunnelExecution e = new FunnelExecution();
        e.setProjectId(projectId);
        e.setFunnelId("funnel-" + seq.incrementAndGet());
        e.setSubscriberId(subscriberId);
        e.setTelegramBotId(TELEGRAM_BOT_ID);
        e.setStatus(status);
        e.setCurrentStepIndex(0);
        e.setCurrentStepId(currentStepId);
        e.setStepRunStatus(StepRunStatus.pending);
        e.setNextRunAt(Instant.now());
        e.setStepsSnapshot(new ArrayList<>(List.of(steps)));
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return mongoTemplate.insert(e).getId();
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
        return seedActiveSubscriberFor(projectId, TELEGRAM_BOT_ID, CHAT_ID, CHAT_ID);
    }

    private String seedActiveSubscriber(Long telegramUserId, Long telegramChatId) {
        return seedActiveSubscriberFor(projectId, TELEGRAM_BOT_ID, telegramUserId, telegramChatId);
    }

    private String seedActiveSubscriberFor(String project, Long telegramBotId,
                                           Long telegramUserId, Long telegramChatId) {
        Subscriber s = new Subscriber();
        s.setProjectId(project);
        s.setTelegramUserId(telegramUserId);
        s.setTelegramChatId(telegramChatId);
        s.setTelegramBotId(telegramBotId);
        s.setStatus(SubscriberStatus.ACTIVE);
        s.setSubscribedAt(Instant.now());
        s.setLastSeenAt(Instant.now());
        return subscriberRepository.save(s).getId();
    }

    private String seedConnectedBot() {
        return seedConnectedBotFor(projectId, TELEGRAM_BOT_ID);
    }

    private String seedConnectedBotFor(String project, Long telegramBotId) {
        Bot bot = new Bot();
        bot.setProjectId(project);
        bot.setTelegramBotId(telegramBotId);
        bot.setTelegramUsername("trigger_bot_" + seq.incrementAndGet());
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
