package com.botfunnel.funnel;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.common.crypto.EncryptedValue;
import com.botfunnel.common.crypto.TokenEncryptor;
import com.botfunnel.common.test.ConcurrencyTestUtils;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberRepository;
import com.botfunnel.subscriber.SubscriberStatus;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.jobrunr.jobs.RecurringJob;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

// Full-context engine IT: Testcontainers Mongo + in-memory JobRunr + MockWebServer Telegram + a mutable
// @Primary test Clock (declared LOCALLY here so it never leaks into other IT contexts). sweep() is
// invoked directly (like ProjectHardDeleteJobIT calls the job method); timing is moved via the clock.
// Tagged slow: the 5xx-exhausted case incurs the real sender backoff ladder. Run with -PrunSlow=true.
@Tag("slow")
@Import(FunnelExecutionEngineIT.TestClockConfig.class)
class FunnelExecutionEngineIT extends AbstractIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-06-01T12:00:00Z");
    private static final String TOKEN = "1234567890:ABCdefGHI_jklMNOpqrSTUvwxYZ0123456789xyz";
    private static final Long TELEGRAM_BOT_ID = 778899L;

    // Mutable, advanceable clock shared with the engine via the @Primary bean below. Reset per test.
    static final MutableClock CLOCK = new MutableClock(BASE);

    private static final MockWebServer TELEGRAM = new MockWebServer();
    static {
        try {
            TELEGRAM.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void telegramProps(DynamicPropertyRegistry registry) {
        registry.add("app.telegram.base-url", () -> TELEGRAM.url("/").toString());
        // Tiny batch cap so the saturation/sort test can prove the WARN + oldest-first ordering with a
        // handful of rows instead of 200. A single-execution test still sees size 1 < 2 → no false WARN.
        registry.add("app.funnel.sweep-batch-size", () -> "2");
    }

    @Autowired FunnelExecutionEngine engine;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired BotRepository botRepository;
    @Autowired SubscriberRepository subscriberRepository;
    @Autowired TokenEncryptor tokenEncryptor;
    @Autowired StorageProvider storageProvider;

    private final AtomicLong seq = new AtomicLong(1);
    private String projectId;
    private String botId;
    // MockWebServer.getRequestCount() is cumulative for the JVM-lifetime singleton server, so each test
    // measures sends as a delta from this per-test baseline.
    private int sendBaseline;

    @BeforeEach
    void cleanAndSeed() {
        CLOCK.set(BASE);
        TELEGRAM.setDispatcher(new QueueDispatcher()); // drop any leftover enqueued responses
        mongoTemplate.remove(new org.springframework.data.mongodb.core.query.Query(), FunnelExecution.class);
        subscriberRepository.deleteAll();
        botRepository.deleteAll();

        projectId = "proj-" + seq.incrementAndGet();
        botId = seedConnectedBot();
        sendBaseline = TELEGRAM.getRequestCount();
    }

    // Sends fired during the current test (delta from the per-test baseline).
    private int sentCount() {
        return TELEGRAM.getRequestCount() - sendBaseline;
    }

    @AfterEach
    void drain() {
        // Consume any responses a test enqueued but did not use, so they don't bleed into the next test.
        TELEGRAM.setDispatcher(new QueueDispatcher());
    }

    // ─── happy paths ───────────────────────────────────────────────────────────

    @Test
    void stepsRunEndToEnd() {
        String subId = seedActiveSubscriber();
        String execId = seedExecution(subId, BASE, sendMessage("hello"), sendMessage("world"));
        enqueueOk(2);

        engine.sweep();

        FunnelExecution done = reload(execId);
        assertThat(done.getStatus()).isEqualTo(ExecutionStatus.completed);
        assertThat(done.getCompletedAt()).isNotNull();
        assertThat(sentCount()).isEqualTo(2);
    }

    @Test
    void consecutiveNonDelayStepsRunInSingleTick() {
        String subId = seedActiveSubscriber();
        String execId = seedExecution(subId, BASE,
                sendMessage("a"), sendMessage("b"), sendMessage("c"));
        enqueueOk(3);

        engine.sweep(); // ONE tick

        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.completed);
        assertThat(sentCount()).isEqualTo(3);
    }

    @Test
    void delaySetsNextRunAtAndResumesOnNextSweep() {
        String subId = seedActiveSubscriber();
        String execId = seedExecution(subId, BASE,
                sendMessage("first"), delay(5, "MIN"), sendMessage("second"));
        enqueueOk(2);

        engine.sweep(); // sends first, parks on the delay
        FunnelExecution parked = reload(execId);
        assertThat(parked.getStatus()).isEqualTo(ExecutionStatus.waiting);
        assertThat(parked.getNextRunAt()).isEqualTo(BASE.plus(Duration.ofMinutes(5)));
        assertThat(sentCount()).isEqualTo(1);

        engine.sweep(); // not yet due → no progress
        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.waiting);
        assertThat(sentCount()).isEqualTo(1);

        CLOCK.advance(Duration.ofMinutes(5));
        engine.sweep(); // delay elapsed → resume, send second, complete
        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.completed);
        assertThat(sentCount()).isEqualTo(2);
    }

    // ─── at-most-once ────────────────────────────────────────────────────────────

    @Test
    void reRunningClaimedStepDoesNotDuplicateSend() {
        String subId = seedActiveSubscriber();
        String execId = seedExecution(subId, BASE, sendMessage("once"));
        enqueueOk(1);

        engine.sweep(); // sends + completes
        engine.sweep(); // execution terminal → not picked up

        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.completed);
        assertThat(sentCount()).isEqualTo(1);
    }

    @Test
    void crashAfterSendBeforeDoneFlipDoesNotResend() {
        // Simulate a crash mid-step: the row is left in_progress (claimed but not advanced). The claim
        // predicate requires stepRunStatus=pending, so the next sweep cannot re-claim → no resend.
        String subId = seedActiveSubscriber();
        String execId = seedExecution(subId, BASE, sendMessage("never-resent"));
        FunnelExecution e = reload(execId);
        e.setStepRunStatus(StepRunStatus.in_progress);
        mongoTemplate.save(e);

        engine.sweep();

        assertThat(sentCount()).isZero();
        FunnelExecution after = reload(execId);
        assertThat(after.getStatus()).isEqualTo(ExecutionStatus.running);
        assertThat(after.getStepRunStatus()).isEqualTo(StepRunStatus.in_progress);
    }

    @Test
    void atomicClaimRaceAcrossTwoReplicasRunsStepOnce() {
        String subId = seedActiveSubscriber();
        String execId = seedExecution(subId, BASE, sendMessage("once-only"));
        enqueueOk(2); // buffer; only one send must actually fire

        // Two concurrent sweeps (two "replicas"). The findAndModify CAS lets exactly one claim the step.
        ConcurrencyTestUtils.parallelInvoke(2, () -> {
            engine.sweep();
            return null;
        });

        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.completed);
        assertThat(sentCount()).isEqualTo(1);
    }

    @Test
    void concurrentCancelMidTickWinsOverEngineWrite() {
        // F2: a terminal cancel that lands AFTER the engine claimed the row but BEFORE its in-tick write
        // must win — the engine's claim-conditional CAS (stepRunStatus=in_progress) no-ops, so the
        // cancelled execution is never resurrected and the remaining step never fires. We trigger the
        // cancel from inside step-1's Telegram send (engine is then mid-tick, holding the claim),
        // mirroring FunnelService.delete / FunnelTriggerService.cancelActiveFor racing the sweep.
        String subId = seedActiveSubscriber();
        String execId = seedExecution(subId, BASE, sendMessage("step-1"), sendMessage("step-2"));

        TELEGRAM.setDispatcher(new Dispatcher() {
            private boolean cancelled = false;

            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (!cancelled) {
                    cancelled = true;
                    mongoTemplate.updateFirst(
                            Query.query(Criteria.where("_id").is(execId)),
                            new Update()
                                    .set("status", ExecutionStatus.cancelled.name())
                                    .set("stepRunStatus", StepRunStatus.done.name()),
                            FunnelExecution.class);
                }
                return json(200, "{\"ok\":true,\"result\":{\"message_id\":1,\"chat\":{\"id\":99}}}");
            }
        });

        engine.sweep();

        FunnelExecution after = reload(execId);
        assertThat(after.getStatus()).isEqualTo(ExecutionStatus.cancelled); // not resurrected to running/completed
        assertThat(after.getStepRunStatus()).isEqualTo(StepRunStatus.done);
        assertThat(after.getCurrentStepIndex()).isZero();                   // never advanced past step-1
        assertThat(sentCount()).isEqualTo(1);                               // step-2 never sent
    }

    @Test
    void concurrentCancelMidTickWinsOverTerminalWrite() {
        // F2, terminal-write path: a send that fails terminally drives the engine into terminate(failed).
        // If a cancel races in during that same send, terminate()'s claim-conditional CAS must no-op so
        // the row stays the cancel's terminal state, NOT the engine's. We make the engine WANT 'failed'
        // (400-other → TerminalReason.OTHER) while the dispatcher concurrently sets 'cancelled' — a blind
        // save would clobber to 'failed', the CAS keeps 'cancelled'. This races terminate() specifically;
        // complete()/scheduleDelay() are unreachable under a synchronous race (no I/O precedes them — they
        // only run with the claim already held), so they are defense-in-depth verified by code structure.
        String subId = seedActiveSubscriber();
        String execId = seedExecution(subId, BASE, sendMessage("doomed"));

        TELEGRAM.setDispatcher(new Dispatcher() {
            private boolean cancelled = false;

            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (!cancelled) {
                    cancelled = true;
                    mongoTemplate.updateFirst(
                            Query.query(Criteria.where("_id").is(execId)),
                            new Update()
                                    .set("status", ExecutionStatus.cancelled.name())
                                    .set("stepRunStatus", StepRunStatus.done.name()),
                            FunnelExecution.class);
                }
                // 400 WITHOUT "chat not found" → TerminalReason.OTHER → engine wants terminate(failed).
                return json(400, "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: bad something\"}");
            }
        });

        engine.sweep();

        // CAS held: the cancel wins. A blind save would have flipped this to failed.
        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.cancelled);
    }

    // ─── send-fail matrix ──────────────────────────────────────────────────────

    @Test
    void sendImageInvalidUrlFailsExecution() {
        String subId = seedActiveSubscriber();
        String execId = seedExecution(subId, BASE, sendImage("https://example.com/missing.png"));
        // 400 WITHOUT "chat not found" → TerminalReason.OTHER → execution failed.
        TELEGRAM.enqueue(json(400, "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: wrong file identifier/HTTP URL specified\"}"));

        engine.sweep();

        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.failed);
    }

    @Test
    void sendImageBlockedFlipsAndCancels() {
        String subId = seedActiveSubscriber();
        String execId = seedExecution(subId, BASE, sendImage("https://example.com/p.png"));
        TELEGRAM.enqueue(json(403, "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by the user\"}"));

        engine.sweep();

        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.cancelled);
        // The sender's own hook flipped the subscriber to BLOCKED — the engine does NOT duplicate it.
        assertThat(subscriberRepository.findById(subId).orElseThrow().getStatus())
                .isEqualTo(SubscriberStatus.BLOCKED);
    }

    @Test
    void sendMessageTerminalErrorFailsExecution() {
        String subId = seedActiveSubscriber();
        String execId = seedExecution(subId, BASE, sendMessage("boom"));
        // 4 attempts (initial + 3 retries) all 5xx → transient_failure_exhausted (OTHER) → failed.
        for (int i = 0; i < 4; i++) {
            TELEGRAM.enqueue(json(500, "{\"ok\":false,\"error_code\":500,\"description\":\"Internal Server Error\"}"));
        }

        engine.sweep();

        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.failed);
    }

    // ─── pre-send gates ────────────────────────────────────────────────────────

    @Test
    void inactiveSubscriberCancelsBeforeSend() {
        String subId = seedSubscriber(SubscriberStatus.UNSUBSCRIBED);
        String execId = seedExecution(subId, BASE, sendMessage("never"));

        engine.sweep();

        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.cancelled);
        assertThat(sentCount()).isZero();
    }

    @Test
    void pinnedBotNotConnectedFailsExecution() {
        // Flip the pinned bot to DISCONNECTED so the pin-check resolves no CONNECTED bot.
        Bot bot = botRepository.findById(botId).orElseThrow();
        bot.setStatus(BotStatus.DISCONNECTED);
        botRepository.save(bot);

        String subId = seedActiveSubscriber();
        String execId = seedExecution(subId, BASE, sendMessage("never"));

        engine.sweep();

        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.failed);
        assertThat(sentCount()).isZero();
    }

    // ─── observability ───────────────────────────────────────────────────────────

    @Test
    void stateTransitionsEmitNamedLogConstantsWithoutPayload() {
        Logger engineLogger = (Logger) LoggerFactory.getLogger(FunnelExecutionEngine.class);
        Logger executorLogger = (Logger) LoggerFactory.getLogger(StepExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        engineLogger.addAppender(appender);
        executorLogger.addAppender(appender);
        try {
            String subId = seedActiveSubscriber();
            String pii = "PII_SECRET_PAYLOAD_42";
            // Over-4096 so StepExecutor's trim-WARN site also fires — proving even that path omits payload.
            String execId = seedExecution(subId, BASE, sendMessage(pii + "x".repeat(5000)));
            enqueueOk(1);

            engine.sweep();

            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).anyMatch(m -> m.contains(FunnelExecutionEngine.LOG_CLAIM_WON));
            assertThat(messages).anyMatch(m -> m.contains(FunnelExecutionEngine.LOG_EXECUTION_COMPLETED));
            assertThat(messages).anyMatch(m -> m.contains(StepExecutor.LOG_TEXT_TRIMMED));
            assertThat(messages).as("no rendered payload (PII) in engine OR executor logs")
                    .noneMatch(m -> m.contains(pii));
            assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.completed);
        } finally {
            engineLogger.detachAppender(appender);
            executorLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void sweepRespectsBatchCapOldestFirstAndWarnsOnSaturation() {
        // batchSize=2 (set via @DynamicPropertySource). Three due executions with distinct nextRunAt →
        // one tick claims the 2 OLDEST (sort nextRunAt asc), logs the saturation WARN, and leaves the
        // newest for the next tick.
        Logger engineLogger = (Logger) LoggerFactory.getLogger(FunnelExecutionEngine.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        engineLogger.addAppender(appender);
        try {
            String s1 = seedActiveSubscriber();
            String s2 = seedActiveSubscriber();
            String s3 = seedActiveSubscriber();
            String oldest = seedExecution(s1, BASE.minusSeconds(20), sendMessage("a"));
            String middle = seedExecution(s2, BASE.minusSeconds(10), sendMessage("b"));
            String newest = seedExecution(s3, BASE.minusSeconds(1), sendMessage("c"));
            enqueueOk(2);

            engine.sweep();

            assertThat(reload(oldest).getStatus()).isEqualTo(ExecutionStatus.completed);
            assertThat(reload(middle).getStatus()).isEqualTo(ExecutionStatus.completed);
            // The newest exceeded the per-tick cap → untouched (still running, pending).
            assertThat(reload(newest).getStatus()).isEqualTo(ExecutionStatus.running);
            assertThat(reload(newest).getStepRunStatus()).isEqualTo(StepRunStatus.pending);
            assertThat(sentCount()).isEqualTo(2);
            assertThat(appender.list).anyMatch(e ->
                    e.getFormattedMessage().contains(FunnelExecutionEngine.LOG_SWEEP_SATURATED));
        } finally {
            engineLogger.detachAppender(appender);
            appender.stop();
        }
    }

    // ─── Phase 2: MENU park / resume / timeout / graph navigation ───────────────

    @Test
    void menu_parks_on_reply() {
        String subId = seedActiveSubscriber();
        FunnelStep menu = menu("m1", "Pick", null, null, null,
                callbackButton("Yes", "end-yes"));
        String execId = seedGraphExecution(subId, BASE, menu);
        enqueueOk(1);

        engine.sweep();

        FunnelExecution parked = reload(execId);
        assertThat(parked.getStatus()).isEqualTo(ExecutionStatus.waiting_for_reply);
        assertThat(parked.getCurrentStepId()).isEqualTo("m1");
        assertThat(parked.getNextRunAt()).isNull();
        assertThat(sentCount()).isEqualTo(1);
    }

    @Test
    void sweep_does_not_resume_menu_without_timeout() {
        String subId = seedActiveSubscriber();
        FunnelStep menu = menu("m1", "Pick", null, null, null, callbackButton("Yes", "end"));
        String execId = seedGraphExecution(subId, BASE, menu);
        enqueueOk(1);

        engine.sweep(); // parks, nextRunAt=null
        CLOCK.advance(Duration.ofHours(48));
        engine.sweep(); // still must not resume (no deadline)

        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.waiting_for_reply);
        assertThat(sentCount()).isEqualTo(1);
    }

    @Test
    void resumeOnCallback_advances_into_button_branch() {
        String subId = seedActiveSubscriber();
        // m1 (menu) --Yes--> s2 (send) --next--> End
        FunnelStep menu = menu("m1", "Pick", null, null, null, callbackButton("Yes", "s2"));
        FunnelStep s2 = sendMessageStep("s2", "branch!", null);
        String execId = seedGraphExecution(subId, BASE, menu, s2);
        enqueueOk(2);

        engine.sweep(); // parks on menu
        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.waiting_for_reply);

        boolean moved = engine.resumeOnCallback(execId, subId, "s2");

        assertThat(moved).isTrue();
        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.completed);
        assertThat(sentCount()).isEqualTo(2); // menu + branch send
    }

    @Test
    void resumeOnCallback_rejects_foreign_subscriber() {
        String ownerSub = seedActiveSubscriber();
        String foreignSub = seedActiveSubscriber();
        FunnelStep menu = menu("m1", "Pick", null, null, null, callbackButton("Yes", "s2"));
        FunnelStep s2 = sendMessageStep("s2", "branch", null);
        String execId = seedGraphExecution(ownerSub, BASE, menu, s2);
        enqueueOk(2);

        engine.sweep(); // parks

        boolean moved = engine.resumeOnCallback(execId, foreignSub, "s2");

        assertThat(moved).isFalse();
        // IDOR: the owner's execution did not move.
        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.waiting_for_reply);
        assertThat(reload(execId).getCurrentStepId()).isEqualTo("m1");
        assertThat(sentCount()).isEqualTo(1);
    }

    @Test
    void resumeOnCallback_double_click_advances_once() {
        String subId = seedActiveSubscriber();
        FunnelStep menu = menu("m1", "Pick", null, null, null, callbackButton("Yes", "s2"));
        FunnelStep s2 = sendMessageStep("s2", "branch", null);
        String execId = seedGraphExecution(subId, BASE, menu, s2);
        enqueueOk(2);

        engine.sweep(); // parks

        boolean first = engine.resumeOnCallback(execId, subId, "s2");
        boolean second = engine.resumeOnCallback(execId, subId, "s2");

        assertThat(first).isTrue();
        assertThat(second).isFalse(); // second tap is a no-op (already resumed/terminal)
        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.completed);
        assertThat(sentCount()).isEqualTo(2);
    }

    @Test
    void loop_without_wait_trips_step_budget() {
        Logger engineLogger = (Logger) LoggerFactory.getLogger(FunnelExecutionEngine.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        engineLogger.addAppender(appender);
        try {
            String subId = seedActiveSubscriber();
            // a --next--> b --next--> a : an infinite ADD_TAG loop with no park/delay.
            FunnelStep a = tagStep("a", "ADD_TAG", "x", "b");
            FunnelStep b = tagStep("b", "ADD_TAG", "y", "a");
            String execId = seedGraphExecution(subId, BASE, a, b);

            engine.sweep();

            assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.failed);
            assertThat(appender.list).anyMatch(e ->
                    e.getFormattedMessage().contains(FunnelExecutionEngine.LOG_STEP_BUDGET_EXCEEDED));
        } finally {
            engineLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void fan_in_multiple_buttons_one_target() {
        String subId = seedActiveSubscriber();
        // Two buttons both point to s2 (fan-in). Resuming via either reaches s2.
        FunnelStep menu = menu("m1", "Pick", null, null, null,
                callbackButton("A", "s2"), callbackButton("B", "s2"));
        FunnelStep s2 = sendMessageStep("s2", "converged", null);
        String execId = seedGraphExecution(subId, BASE, menu, s2);
        enqueueOk(2);

        engine.sweep();
        boolean moved = engine.resumeOnCallback(execId, subId, "s2");

        assertThat(moved).isTrue();
        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.completed);
    }

    @Test
    void timeout_resume_follows_target() {
        String subId = seedActiveSubscriber();
        // m1 has a 10-MIN timeout → s3 (explicit jump past s2). s3 -> End. The button branch (s2) is
        // NOT taken; the timeout jumps directly to s3, proving the engine follows timeoutTargetStepId
        // rather than the menu's list-order default-next.
        FunnelStep menu = menu("m1", "Pick", 10, "MIN", "s3", callbackButton("Yes", "s2"));
        FunnelStep s2 = sendMessageStep("s2", "clicked", "s3");
        FunnelStep s3 = sendMessageStep("s3", "timed-out", null);
        String execId = seedGraphExecution(subId, BASE, menu, s2, s3);
        enqueueOk(2);

        engine.sweep(); // parks with deadline BASE+10min
        assertThat(reload(execId).getNextRunAt()).isEqualTo(BASE.plus(Duration.ofMinutes(10)));

        CLOCK.advance(Duration.ofMinutes(10));
        engine.sweep(); // timeout fires → jump to s3 (skipping s2) → completes

        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.completed);
        assertThat(reload(execId).getCurrentStepId()).isNull(); // s3 had no next → End
        assertThat(sentCount()).isEqualTo(2); // menu + s3 only (s2 skipped)
    }

    @Test
    void timeout_null_target_completes() {
        String subId = seedActiveSubscriber();
        FunnelStep menu = menu("m1", "Pick", 5, "MIN", null, callbackButton("Yes", "s2"));
        FunnelStep s2 = sendMessageStep("s2", "branch", null);
        String execId = seedGraphExecution(subId, BASE, menu, s2);
        enqueueOk(1);

        engine.sweep(); // parks with deadline
        CLOCK.advance(Duration.ofMinutes(5));
        engine.sweep(); // timeout, null target → completed (no branch send)

        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.completed);
        assertThat(sentCount()).isEqualTo(1);
    }

    @Test
    void loop_resend_menu_works() {
        String subId = seedActiveSubscriber();
        // Button loops back to the same menu; resume re-sends the menu and re-parks.
        FunnelStep menu = menu("m1", "Again?", null, null, null, callbackButton("Loop", "m1"));
        String execId = seedGraphExecution(subId, BASE, menu);
        enqueueOk(3);

        engine.sweep(); // send menu #1, park
        assertThat(sentCount()).isEqualTo(1);

        boolean moved = engine.resumeOnCallback(execId, subId, "m1"); // back to menu
        assertThat(moved).isTrue();
        // Re-sent the menu and re-parked.
        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.waiting_for_reply);
        assertThat(reload(execId).getCurrentStepId()).isEqualTo("m1");
        assertThat(sentCount()).isEqualTo(2);

        // A subsequent callback still works (not stuck).
        boolean moved2 = engine.resumeOnCallback(execId, subId, "m1");
        assertThat(moved2).isTrue();
        assertThat(sentCount()).isEqualTo(3);
    }

    @Test
    void paused_funnel_in_flight_resumes_to_completed() {
        // Decision 11: no paused-gate in resume — an in-flight waiting_for_reply drains even on a paused
        // funnel. The engine reads no funnel status; this verifies resume just advances the in-flight run.
        String subId = seedActiveSubscriber();
        FunnelStep menu = menu("m1", "Pick", null, null, null, callbackButton("Yes", "s2"));
        FunnelStep s2 = sendMessageStep("s2", "drained", null);
        String execId = seedGraphExecution(subId, BASE, menu, s2);
        enqueueOk(2);

        engine.sweep();
        boolean moved = engine.resumeOnCallback(execId, subId, "s2");

        assertThat(moved).isTrue();
        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.completed);
    }

    @Test
    void legacy_linear_run_drains_via_index_fallback() {
        // A legacy Phase-1 run: snapshot steps carry NO ids, currentStepId == null → drain by index.
        String subId = seedActiveSubscriber();
        String execId = seedExecution(subId, BASE, sendMessage("legacy-1"), sendMessage("legacy-2"));
        // seedExecution leaves currentStepId null (legacy shape).
        enqueueOk(2);

        engine.sweep();

        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.completed);
        assertThat(sentCount()).isEqualTo(2);
    }

    @Test
    void blocked_bot_during_wait_cancels_on_resume() {
        String subId = seedActiveSubscriber();
        FunnelStep menu = menu("m1", "Pick", null, null, null, callbackButton("Yes", "s2"));
        FunnelStep s2 = sendMessageStep("s2", "first-in-branch", null);
        String execId = seedGraphExecution(subId, BASE, menu, s2);
        TELEGRAM.enqueue(json(200, "{\"ok\":true,\"result\":{\"message_id\":1,\"chat\":{\"id\":99}}}")); // menu
        TELEGRAM.enqueue(json(403, "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by the user\"}")); // branch send

        engine.sweep(); // park on menu
        boolean moved = engine.resumeOnCallback(execId, subId, "s2"); // branch send → 403 → cancelled

        assertThat(moved).isTrue();
        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.cancelled);
    }

    @Test
    void subMinuteRecurringJobIsRegistered() {
        RecurringJob sweepJob = storageProvider.getRecurringJobs().stream()
                .filter(rj -> "funnel-sweep".equals(rj.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("funnel-sweep recurring job is not registered"));
        assertThat(sweepJob.getScheduleExpression()).isEqualTo("PT30S");
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private FunnelExecution reload(String id) {
        return mongoTemplate.findById(id, FunnelExecution.class);
    }

    private void enqueueOk(int count) {
        for (int i = 0; i < count; i++) {
            TELEGRAM.enqueue(json(200, "{\"ok\":true,\"result\":{\"message_id\":1,\"chat\":{\"id\":99}}}"));
        }
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .setHeader("Content-Type", "application/json").setBody(body);
    }

    private FunnelStep sendMessage(String text) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.SEND_MESSAGE);
        s.setText(text);
        return s;
    }

    private FunnelStep sendImage(String url) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.SEND_IMAGE);
        s.setImageUrl(url);
        return s;
    }

    private FunnelStep delay(int value, String unit) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.DELAY);
        s.setDelayValue(value);
        s.setDelayUnit(unit);
        return s;
    }

    // Graph-shaped step builders (Phase 2): steps carry stable ids so the engine navigates by
    // currentStepId. seedGraphExecution seeds currentStepId to the first step's id.
    private FunnelStep sendMessageStep(String id, String text, String next) {
        FunnelStep s = new FunnelStep();
        s.setId(id);
        s.setNext(next);
        s.setStepType(StepType.SEND_MESSAGE);
        s.setText(text);
        return s;
    }

    private FunnelStep tagStep(String id, String type, String slug, String next) {
        FunnelStep s = new FunnelStep();
        s.setId(id);
        s.setNext(next);
        s.setStepType(StepType.valueOf(type));
        s.setTagSlug(slug);
        return s;
    }

    private FunnelStep menu(String id, String text, Integer timeoutValue, String timeoutUnit,
                            String timeoutTargetStepId, Button... buttons) {
        FunnelStep s = new FunnelStep();
        s.setId(id);
        s.setStepType(StepType.MENU);
        s.setText(text);
        s.setButtons(List.of(buttons));
        s.setTimeoutValue(timeoutValue);
        s.setTimeoutUnit(timeoutUnit);
        s.setTimeoutTargetStepId(timeoutTargetStepId);
        return s;
    }

    private static Button callbackButton(String label, String targetStepId) {
        return new Button("callback", label, targetStepId, null);
    }

    private String seedGraphExecution(String subscriberId, Instant nextRunAt, FunnelStep... steps) {
        FunnelExecution e = new FunnelExecution();
        e.setProjectId(projectId);
        e.setFunnelId("funnel-" + seq.incrementAndGet());
        e.setSubscriberId(subscriberId);
        e.setTelegramBotId(TELEGRAM_BOT_ID);
        e.setStatus(ExecutionStatus.running);
        e.setCurrentStepIndex(0);
        List<FunnelStep> snapshot = new ArrayList<>();
        for (FunnelStep s : steps) {
            snapshot.add(s);
        }
        e.setCurrentStepId(snapshot.isEmpty() ? null : snapshot.get(0).getId());
        e.setStepRunStatus(StepRunStatus.pending);
        e.setNextRunAt(nextRunAt);
        e.setStepsSnapshot(snapshot);
        e.setCreatedAt(BASE);
        e.setUpdatedAt(BASE);
        return mongoTemplate.save(e).getId();
    }

    private String seedExecution(String subscriberId, Instant nextRunAt, FunnelStep... steps) {
        FunnelExecution e = new FunnelExecution();
        e.setProjectId(projectId);
        e.setFunnelId("funnel-" + seq.incrementAndGet());
        e.setSubscriberId(subscriberId);
        e.setTelegramBotId(TELEGRAM_BOT_ID);
        e.setStatus(ExecutionStatus.running);
        e.setCurrentStepIndex(0);
        e.setStepRunStatus(StepRunStatus.pending);
        e.setNextRunAt(nextRunAt);
        List<FunnelStep> snapshot = new ArrayList<>();
        for (FunnelStep s : steps) {
            snapshot.add(s);
        }
        e.setStepsSnapshot(snapshot);
        e.setCreatedAt(BASE);
        e.setUpdatedAt(BASE);
        return mongoTemplate.save(e).getId();
    }

    private String seedActiveSubscriber() {
        return seedSubscriber(SubscriberStatus.ACTIVE);
    }

    private String seedSubscriber(SubscriberStatus status) {
        Subscriber s = new Subscriber();
        s.setProjectId(projectId);
        s.setTelegramUserId(seq.incrementAndGet());
        s.setTelegramChatId(seq.incrementAndGet());
        s.setTelegramBotId(TELEGRAM_BOT_ID);
        s.setStatus(status);
        s.setSubscribedAt(BASE);
        s.setLastSeenAt(BASE);
        return subscriberRepository.save(s).getId();
    }

    private String seedConnectedBot() {
        EncryptedValue ev = tokenEncryptor.encrypt(TOKEN);
        Bot bot = new Bot();
        bot.setProjectId(projectId);
        bot.setTelegramBotId(TELEGRAM_BOT_ID);
        bot.setTelegramUsername("engine_bot");
        bot.setStatus(BotStatus.CONNECTED);
        bot.setEncryptedTokenIv(Base64.getEncoder().encodeToString(ev.iv()));
        bot.setEncryptedTokenCiphertext(Base64.getEncoder().encodeToString(ev.ciphertext()));
        bot.setConnectedAt(BASE);
        return botRepository.save(bot).getId();
    }

    // Mutable, advanceable Clock for deterministic delay/resume timing. Declared LOCAL to this IT and
    // wired @Primary only in this context (TestClockConfig) so it never leaks into other IT contexts
    // and corrupts their timestamp assertions.
    static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant) {
            this(instant, ZoneOffset.UTC);
        }

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void set(Instant i) {
            this.instant = i;
        }

        void advance(Duration d) {
            this.instant = this.instant.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return new MutableClock(instant, z);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }
}
