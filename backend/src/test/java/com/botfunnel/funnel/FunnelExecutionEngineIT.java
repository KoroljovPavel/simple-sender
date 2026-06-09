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
import com.botfunnel.events.Event;
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
import static org.assertj.core.api.Assertions.assertThatCode;

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
    @Autowired FunnelTriggerService triggerService;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired BotRepository botRepository;
    @Autowired SubscriberRepository subscriberRepository;
    @Autowired FunnelRepository funnelRepository;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
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
        mongoTemplate.remove(new org.springframework.data.mongodb.core.query.Query(), Event.class);
        subscriberRepository.deleteAll();
        botRepository.deleteAll();
        funnelRepository.deleteAll();
        // Clear the per-subscriber auto-enroll rate-limit keys so a prior test's enrolls do not leak into
        // this one (the rate-limit IT seeds its own counter explicitly).
        java.util.Set<String> rateKeys = redisTemplate.keys("bf:rate:auto-enroll:*");
        if (rateKeys != null && !rateKeys.isEmpty()) {
            redisTemplate.delete(rateKeys);
        }

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
    void multiBlockMessage_crashMidBlocks_doesNotResendOnReClaim() {
        // Per-node at-most-once for the composer (Decision 3): a multi-block MESSAGE sends all its blocks
        // under ONE claim. We crash mid-send — the FIRST two blocks deliver, then the THIRD send fails
        // terminally (400-other → fail), leaving the step terminal-failed without advancing. A second sweep
        // must NOT re-send the already-delivered blocks (lost-forward) — the failed execution is terminal so
        // it is never re-claimed: sentCount stays exactly K (the 3 attempted sends), not 3 + a replay.
        String subId = seedActiveSubscriber();
        String execId = seedExecution(subId, BASE,
                multiTextMessageIndexed("b1", "b2", "b3"));
        // Blocks 1+2 succeed; block 3 fails 400-other (TerminalReason.OTHER) → execution failed mid-step.
        enqueueOk(2);
        TELEGRAM.enqueue(json(400, "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: boom\"}"));

        engine.sweep(); // 3 send attempts (b1, b2, b3-fails) under one claim → failed
        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.failed);
        assertThat(sentCount()).isEqualTo(3); // K=3 send attempts, blocks 1+2 lost-forward

        engine.sweep(); // terminal → not re-claimed → no replay of any block
        assertThat(sentCount()).isEqualTo(3);
    }

    @Test
    void multiBlockMessage_leftInProgress_isNotReClaimed() {
        // A genuine crash mid-multiblock leaves the row claimed (stepRunStatus=in_progress) but not advanced.
        // The claim predicate requires pending → the next sweep cannot re-claim → ZERO blocks re-sent
        // (already-delivered blocks are lost-forward, never duplicated).
        String subId = seedActiveSubscriber();
        String execId = seedExecution(subId, BASE,
                multiTextMessageIndexed("b1", "b2", "b3"));
        FunnelExecution e = reload(execId);
        e.setStepRunStatus(StepRunStatus.in_progress); // simulate crash after a partial multi-block send
        mongoTemplate.save(e);

        engine.sweep();

        assertThat(sentCount()).isZero(); // no block re-sent
        FunnelExecution after = reload(execId);
        assertThat(after.getStatus()).isEqualTo(ExecutionStatus.running);
        assertThat(after.getStepRunStatus()).isEqualTo(StepRunStatus.in_progress);
    }

    @Test
    void multiBlockMessage_runsAllBlocksInOneTickAndCompletes() {
        // Happy path for the composer: a 3-block MESSAGE sends exactly 3 messages in one sweep and completes.
        String subId = seedActiveSubscriber();
        String execId = seedExecution(subId, BASE,
                multiTextMessageIndexed("b1", "b2", "b3"));
        enqueueOk(3);

        engine.sweep();

        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.completed);
        assertThat(sentCount()).isEqualTo(3);
    }

    // ─── tolerant-read fail-safe (MAJ-1 / F-MINOR-1) ─────────────────────────────

    @Test
    void legacyRemovedStepType_doesNotCrashSweep_andOtherExecutionsStillProcess() {
        // Fail-safe (tech-spec Testing Strategy / MAJ-1): a legacy funnel_executions document whose
        // stepsSnapshot[0].stepType is a now-REMOVED value (e.g. "MENU", killed in 15-message-composer) that
        // survived the manual wipe (Decision 4) must NOT crash the engine. Spring Data's default enum reader
        // would throw ConversionFailedException on the unknown name during sweep()'s find(...) — OUTSIDE the
        // per-execution try/catch — aborting the WHOLE tick. The tolerant StepTypeReadConverter maps the
        // unknown value to the StepType.UNKNOWN sentinel so the read succeeds and the engine isolates the bad
        // row: it terminal-FAILS just that execution while every other valid execution in the same batch
        // still processes.
        String subId = seedActiveSubscriber();

        // 1) Seed a valid single-block MESSAGE execution via the normal helper, then RAW-mutate its persisted
        //    stepType to the removed literal "MENU" — exactly the shape a pre-composer document would have.
        String legacyId = seedExecution(subId, BASE, sendMessage("legacy"));
        org.bson.Document setUnknown = new org.bson.Document("$set",
                new org.bson.Document("stepsSnapshot.0.stepType", "MENU"));
        long matched = mongoTemplate.getCollection("funnel_executions")
                .updateOne(new org.bson.Document("_id", new org.bson.types.ObjectId(legacyId)), setUnknown)
                .getMatchedCount();
        assertThat(matched).isEqualTo(1L); // guard: the raw mutation actually hit the legacy doc

        // 2) Seed a second, fully VALID execution that must still process in the same tick.
        String validId = seedExecution(subId, BASE, sendMessage("valid"));
        enqueueOk(1); // exactly ONE send expected (the valid one); the legacy one never sends

        // The sweep must NOT throw even though one document carries a removed enum value.
        assertThatCode(() -> engine.sweep()).doesNotThrowAnyException();

        // The valid execution completed and sent its one message — the tick was NOT aborted.
        FunnelExecution valid = reload(validId);
        assertThat(valid.getStatus()).isEqualTo(ExecutionStatus.completed);
        assertThat(sentCount()).isEqualTo(1);

        // The legacy/unknown-type execution loaded tolerantly (UNKNOWN sentinel) and was terminal-failed —
        // NOT left stuck in_progress and NOT re-claimed forever.
        FunnelExecution legacy = reload(legacyId);
        assertThat(legacy.getStatus()).isEqualTo(ExecutionStatus.failed);
        assertThat(legacy.getStepRunStatus()).isEqualTo(StepRunStatus.done);
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
    void editingFunnelDuringWait_doesNotChangeInFlightExecution() {
        // Snapshot isolation at RUNTIME (tech-spec Testing Strategy): a MENU execution parks
        // waiting_for_reply carrying its own stepsSnapshot. We then MUTATE the source funnel definition in
        // the funnels collection (delete the branch target s2, repoint the button). On resume the engine
        // must follow the SNAPSHOT (advance into s2 → completed), proving the live edit is ignored.
        String subId = seedActiveSubscriber();
        FunnelStep menu = menu("m1", "Pick", null, null, null, callbackButton("Yes", "s2"));
        FunnelStep s2 = sendMessageStep("s2", "branch!", null);
        String execId = seedGraphExecution(subId, BASE, menu, s2);
        String funnelId = reload(execId).getFunnelId();
        enqueueOk(2);

        engine.sweep(); // parks on the menu (snapshot frozen)
        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.waiting_for_reply);

        // Author edits the LIVE funnel while the subscriber waits: the menu now points its button at a
        // non-existent step and s2 is gone entirely. The in-flight snapshot must not be affected.
        FunnelStep liveMenu = menu("m1", "EDITED", null, null, null, callbackButton("Yes", "ghost"));
        Funnel live = new Funnel();
        live.setId(funnelId);
        live.setProjectId(projectId);
        live.setName("edited-mid-wait");
        live.setStatus(FunnelStatus.active);
        live.setTriggerType(FunnelService.TRIGGER_ON_START);
        live.setTriggerValue("");
        live.setSteps(new ArrayList<>(List.of(liveMenu)));
        live.setCreatedAt(BASE);
        live.setUpdatedAt(BASE);
        mongoTemplate.save(live);

        // Resume along the SNAPSHOT's button target (s2), which no longer exists in the live funnel.
        boolean moved = engine.resumeOnCallback(execId, subId, "s2");

        assertThat(moved).isTrue();
        // Followed the snapshot (s2 still reachable there) → branch sent + completed, despite the live edit.
        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.completed);
        assertThat(sentCount()).isEqualTo(2); // menu + snapshot branch send
    }

    @Test
    void multiBlockMessage_snapshotIsolation_ignoresLiveBlockEdit() {
        // Snapshot isolation on the List<ContentBlock> (Decision 1+3): an execution runs on the blocks frozen
        // at enroll. We seed a parked 2-block MESSAGE, then MUTATE the live funnel definition to a single
        // 1-block message. On resume the engine drains the SNAPSHOT (2 blocks → 2 sends), proving the live
        // edit to blocks is ignored by the in-flight run.
        String subId = seedActiveSubscriber();
        // m1 (menu, button Yes → s2) ; s2 is a TWO-block MESSAGE in the snapshot.
        FunnelStep menu = menu("m1", "Pick", null, null, null, callbackButton("Yes", "s2"));
        FunnelStep s2 = multiTextMessage("s2", null, "snap-1", "snap-2");
        String execId = seedGraphExecution(subId, BASE, menu, s2);
        String funnelId = reload(execId).getFunnelId();
        enqueueOk(3); // menu + 2 snapshot blocks

        engine.sweep(); // park on menu (snapshot frozen with the 2-block s2)
        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.waiting_for_reply);

        // Author edits the LIVE funnel: s2 now has a SINGLE block. The in-flight snapshot must be unaffected.
        FunnelStep liveMenu = menu("m1", "Pick", null, null, null, callbackButton("Yes", "s2"));
        FunnelStep liveS2 = multiTextMessage("s2", null, "EDITED-single");
        Funnel live = new Funnel();
        live.setId(funnelId);
        live.setProjectId(projectId);
        live.setName("edited-blocks");
        live.setStatus(FunnelStatus.active);
        live.setTriggerType(FunnelService.TRIGGER_ON_START);
        live.setTriggerValue("");
        live.setSteps(new ArrayList<>(List.of(liveMenu, liveS2)));
        live.setCreatedAt(BASE);
        live.setUpdatedAt(BASE);
        mongoTemplate.save(live);

        boolean moved = engine.resumeOnCallback(execId, subId, "s2");

        assertThat(moved).isTrue();
        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.completed);
        // Snapshot s2 had TWO blocks → menu + 2 sends = 3 (NOT 2, which the single-block live edit would give).
        assertThat(sentCount()).isEqualTo(3);
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

    // ─── Task 5: FunnelTriggerService.advanceOnCallback (full callback path) ────────
    // These ITs drive the PUBLIC advanceOnCallback (trigger → engine → Mongo/Telegram) end-to-end,
    // resolving bot+subscriber from the project/chatId exactly as the webhook worker (Task 7) will.
    // Deliberately co-located in the engine IT to reuse the full-context harness (per task spec).

    @Test
    void advanceOnCallback_validClick_advancesBranch_writesEvent() {
        Subscriber sub = seedActiveSubscriberDoc();
        // m1 (menu, button 0 "Yes" → s2) ; s2 (send) → End
        FunnelStep menu = menu("m1", "Pick", null, null, null, callbackButton("Yes", "s2"));
        FunnelStep s2 = sendMessageStep("s2", "branch!", null);
        String execId = seedParkedExecution(sub.getId(), "m1", menu, s2);
        enqueueOk(2); // branch send (s2) + answerCallbackQuery ack

        triggerService.advanceOnCallback(projectId, sub.getTelegramChatId(), execId + ":0", "cbq-1");

        FunnelExecution done = reload(execId);
        assertThat(done.getStatus()).isEqualTo(ExecutionStatus.completed);
        assertThat(done.getLastButtonClicked()).isEqualTo("m1:0");

        List<Event> clicks = funnelButtonClickedEvents();
        assertThat(clicks).hasSize(1);
        Event ev = clicks.get(0);
        assertThat(ev.getUserId()).isEqualTo(sub.getId());
        assertThat(ev.getMetadata()).containsEntry("funnelId", done.getFunnelId())
                .containsEntry("executionId", execId)
                .containsEntry("currentStepId", "m1")
                .containsEntry("buttonIndex", 0);
        // No-PII (Decision 9): the event carries ids/codes only — never the subscriber's display name.
        assertThat(ev.getMetadata().values()).doesNotContain(sub.getFirstName());
        // answerCallbackQuery was issued (branch send + ack = 2 requests).
        assertThat(sentCount()).isEqualTo(2);
    }

    @Test
    void advanceOnCallback_foreignExecution_idorRejected_noAdvance() {
        // Subscriber A owns execution; subscriber B (a legitimate project subscriber) forges the click.
        Subscriber owner = seedActiveSubscriberDoc();
        Subscriber attacker = seedActiveSubscriberDoc();
        FunnelStep menu = menu("m1", "Pick", null, null, null, callbackButton("Yes", "s2"));
        FunnelStep s2 = sendMessageStep("s2", "branch", null);
        String execId = seedParkedExecution(owner.getId(), "m1", menu, s2);
        enqueueOk(1); // only the ack — no branch send must fire

        triggerService.advanceOnCallback(projectId, attacker.getTelegramChatId(), execId + ":0", "cbq-idor");

        // IDOR: the owner's execution is untouched.
        FunnelExecution after = reload(execId);
        assertThat(after.getStatus()).isEqualTo(ExecutionStatus.waiting_for_reply);
        assertThat(after.getCurrentStepId()).isEqualTo("m1");
        assertThat(after.getLastButtonClicked()).isNull();
        assertThat(funnelButtonClickedEvents()).isEmpty();
        // answerCallbackQuery still issued for the attacker's chat (spinner clears even on the no-op).
        assertThat(sentCount()).isEqualTo(1);
    }

    @Test
    void advanceOnCallback_doubleClick_advancesOnce() {
        Subscriber sub = seedActiveSubscriberDoc();
        FunnelStep menu = menu("m1", "Pick", null, null, null, callbackButton("Yes", "s2"));
        FunnelStep s2 = sendMessageStep("s2", "branch", null);
        String execId = seedParkedExecution(sub.getId(), "m1", menu, s2);
        enqueueOk(3); // 1 branch send + 2 acks (one per click)

        triggerService.advanceOnCallback(projectId, sub.getTelegramChatId(), execId + ":0", "cbq-a");
        triggerService.advanceOnCallback(projectId, sub.getTelegramChatId(), execId + ":0", "cbq-b");

        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.completed);
        // Exactly ONE advance → exactly ONE event (claim-CAS; the second click loses).
        assertThat(funnelButtonClickedEvents()).hasSize(1);
    }

    @Test
    void advanceOnCallback_staleOnCompleted_noOp() {
        Subscriber sub = seedActiveSubscriberDoc();
        FunnelStep menu = menu("m1", "Pick", null, null, null, callbackButton("Yes", "s2"));
        FunnelStep s2 = sendMessageStep("s2", "branch", null);
        String execId = seedParkedExecution(sub.getId(), "m1", menu, s2);
        // Force-terminal: the click arrives after the execution already completed.
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(execId)),
                new Update().set("status", ExecutionStatus.completed.name())
                        .set("stepRunStatus", StepRunStatus.done.name()),
                FunnelExecution.class);
        enqueueOk(1); // ack only

        triggerService.advanceOnCallback(projectId, sub.getTelegramChatId(), execId + ":0", "cbq-stale");

        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.completed);
        assertThat(funnelButtonClickedEvents()).isEmpty();
        assertThat(sentCount()).isEqualTo(1); // answerCallbackQuery still called
    }

    @Test
    void advanceOnCallback_malformedOrOversizedData_noOp() {
        Subscriber sub = seedActiveSubscriberDoc();
        FunnelStep menu = menu("m1", "Pick", null, null, null, callbackButton("Yes", "s2"));
        FunnelStep s2 = sendMessageStep("s2", "branch", null);
        String execId = seedParkedExecution(sub.getId(), "m1", menu, s2);

        String[] bad = {
                execId,                       // no ':' separator
                execId + ":0:1",              // extra segment
                "not-an-objectid:0",          // non-hex / wrong-length left segment
                execId + ":-1",               // negative index
                execId + ":x",                // non-numeric index
                "z".repeat(64) + ":0",        // 64-hex left part → 66-byte data (oversized, also wrong shape)
        };
        enqueueOk(bad.length); // one ack per malformed click

        for (int i = 0; i < bad.length; i++) {
            triggerService.advanceOnCallback(projectId, sub.getTelegramChatId(), bad[i], "cbq-bad-" + i);
        }

        // Nothing parsed/advanced; execution stays parked; no events.
        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.waiting_for_reply);
        assertThat(funnelButtonClickedEvents()).isEmpty();
        // answerCallbackQuery fired for every malformed click (spinner clears).
        assertThat(sentCount()).isEqualTo(bad.length);
    }

    @Test
    void advanceOnCallback_urlButtonOrOutOfRange_noOp() {
        Subscriber sub = seedActiveSubscriberDoc();
        // button 0 = URL (must not advance), button 1 = callback. Index 2 is out of range.
        Button urlBtn = new Button("url", "Open", null, "https://example.com");
        FunnelStep menu = menu("m1", "Pick", null, null, null, urlBtn, callbackButton("Yes", "s2"));
        FunnelStep s2 = sendMessageStep("s2", "branch", null);
        String execId = seedParkedExecution(sub.getId(), "m1", menu, s2);
        enqueueOk(2); // ack for the URL click + ack for the out-of-range click

        triggerService.advanceOnCallback(projectId, sub.getTelegramChatId(), execId + ":0", "cbq-url");
        triggerService.advanceOnCallback(projectId, sub.getTelegramChatId(), execId + ":2", "cbq-oob");

        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.waiting_for_reply);
        assertThat(funnelButtonClickedEvents()).isEmpty();
        assertThat(sentCount()).isEqualTo(2); // both acks issued
    }

    @Test
    void advanceOnCallback_currentStepIdMismatch_noOp() {
        // The execution is waiting_for_reply but its cursor sits on a NON-menu step (it already passed
        // the menu) — the click must be a no-op.
        Subscriber sub = seedActiveSubscriberDoc();
        FunnelStep menu = menu("m1", "Pick", null, null, null, callbackButton("Yes", "s2"));
        FunnelStep s2 = sendMessageStep("s2", "non-menu", null);
        // Parked, but cursor is on s2 (not the menu) — a contrived mismatch shape.
        String execId = seedParkedExecution(sub.getId(), "s2", menu, s2);
        enqueueOk(1);

        triggerService.advanceOnCallback(projectId, sub.getTelegramChatId(), execId + ":0", "cbq-mismatch");

        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.waiting_for_reply);
        assertThat(reload(execId).getCurrentStepId()).isEqualTo("s2");
        assertThat(funnelButtonClickedEvents()).isEmpty();
        assertThat(sentCount()).isEqualTo(1);
    }

    @Test
    void advanceOnCallback_answerCallbackQuery5xx_advanceNotBlocked() {
        Subscriber sub = seedActiveSubscriberDoc();
        FunnelStep menu = menu("m1", "Pick", null, null, null, callbackButton("Yes", "s2"));
        FunnelStep s2 = sendMessageStep("s2", "branch", null);
        String execId = seedParkedExecution(sub.getId(), "m1", menu, s2);
        // Branch send (s2) succeeds; the answerCallbackQuery ack exhausts on 5xx (best-effort, 4 attempts).
        TELEGRAM.enqueue(json(200, "{\"ok\":true,\"result\":{\"message_id\":1,\"chat\":{\"id\":99}}}")); // s2 send
        for (int i = 0; i < 4; i++) {
            TELEGRAM.enqueue(json(500, "{\"ok\":false,\"error_code\":500,\"description\":\"Internal Server Error\"}"));
        }

        triggerService.advanceOnCallback(projectId, sub.getTelegramChatId(), execId + ":0", "cbq-5xx");

        // The 5xx ack did NOT block the branch advance.
        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.completed);
        assertThat(funnelButtonClickedEvents()).hasSize(1);
    }

    @Test
    void advanceOnCallback_pausedFunnel_drainsToCompleted() {
        // Decision 11: no paused-gate in the resume path. advanceOnCallback reads no funnel status, so an
        // in-flight waiting_for_reply drains to completed regardless of the (absent here) funnel's status.
        Subscriber sub = seedActiveSubscriberDoc();
        FunnelStep menu = menu("m1", "Pick", null, null, null, callbackButton("Yes", "s2"));
        FunnelStep s2 = sendMessageStep("s2", "drained", null);
        String execId = seedParkedExecution(sub.getId(), "m1", menu, s2);
        enqueueOk(2);

        triggerService.advanceOnCallback(projectId, sub.getTelegramChatId(), execId + ":0", "cbq-paused");

        assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.completed);
    }

    @Test
    void subMinuteRecurringJobIsRegistered() {
        RecurringJob sweepJob = storageProvider.getRecurringJobs().stream()
                .filter(rj -> "funnel-sweep".equals(rj.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("funnel-sweep recurring job is not registered"));
        assertThat(sweepJob.getScheduleExpression()).isEqualTo("PT30S");
    }

    // ─── Phase 5: SUBSCRIBE_TO_FUNNEL composition (enroll path) ─────────────────

    @Test
    void subscribeEnrollsTargetWithEntryStepAndInheritedBot() {
        String subId = seedActiveSubscriber();
        // Target has two steps; we enter at the SECOND (entry-2), proving the entry-step is honoured.
        Funnel target = seedActiveTargetFunnel(
                sendMessageStep("entry-1", "t1", null),
                sendMessageStep("entry-2", "t2", null));
        // Parent: SUBSCRIBE(target, entry=entry-2, end=true) at depth 0.
        FunnelStep sub = subscribeStep("p1", target.getId(), "entry-2", true);
        String parentId = seedGraphExecution(subId, BASE, sub);

        engine.sweep();

        FunnelExecution child = childExecution(target.getId(), subId);
        assertThat(child).isNotNull();
        assertThat(child.getCurrentStepId()).isEqualTo("entry-2");
        assertThat(child.getEnrollDepth()).isEqualTo(1);                 // parent 0 + 1
        assertThat(child.getTelegramBotId()).isEqualTo(TELEGRAM_BOT_ID); // inherited from the parent
        assertThat(child.getSubscriberId()).isEqualTo(subId);
        assertThat(reload(parentId).getStatus()).isEqualTo(ExecutionStatus.completed);
    }

    @Test
    void subscribeEndParentTrueCompletesParentNoFurtherSteps() {
        String subId = seedActiveSubscriber();
        Funnel target = seedActiveTargetFunnel(sendMessageStep("t1", "target", null));
        // Parent: SUBSCRIBE(end=true) --next--> after (a send that must NOT run because the parent completed).
        FunnelStep sub = subscribeStep("p1", target.getId(), null, true);
        sub.setNext("after");
        FunnelStep after = sendMessageStep("after", "should-not-send", null);
        String parentId = seedGraphExecution(subId, BASE, sub, after);

        engine.sweep();

        assertThat(reload(parentId).getStatus()).isEqualTo(ExecutionStatus.completed);
        // The parent had no send step before SUBSCRIBE, and 'after' must be skipped → zero parent sends.
        assertThat(sentCount()).isZero();
        assertThat(childExecution(target.getId(), subId)).isNotNull();
    }

    @Test
    void subscribeEndParentFalseParentContinuesParallel() {
        String subId = seedActiveSubscriber();
        Funnel target = seedActiveTargetFunnel(sendMessageStep("t1", "child-send", null));
        // Parent: SUBSCRIBE(end=false) --next--> after (a parent send that MUST run in the same tick).
        FunnelStep sub = subscribeStep("p1", target.getId(), null, false);
        sub.setNext("after");
        FunnelStep after = sendMessageStep("after", "parent-continues", null);
        String parentId = seedGraphExecution(subId, BASE, sub, after);
        enqueueOk(2); // parent 'after' send (this tick) + child send (next tick)

        engine.sweep(); // parent enrolls child, continues to 'after', completes
        assertThat(reload(parentId).getStatus()).isEqualTo(ExecutionStatus.completed);
        assertThat(sentCount()).isEqualTo(1); // only the parent 'after' send so far

        FunnelExecution child = childExecution(target.getId(), subId);
        assertThat(child).isNotNull();
        assertThat(child.getStatus()).isEqualTo(ExecutionStatus.running);

        engine.sweep(); // child progresses on the next sweep
        assertThat(reload(child.getId()).getStatus()).isEqualTo(ExecutionStatus.completed);
        assertThat(sentCount()).isEqualTo(2); // parent + child sends → both funnels progressed
    }

    @Test
    void subscribeReturnToMenuLandsOnEntryStep() {
        // The author's real case: funnel B calls sub-funnel A and returns the subscriber to A's menu (M).
        String subId = seedActiveSubscriber();
        Funnel target = seedActiveTargetFunnel(
                sendMessageStep("intro", "A intro", null),
                menu("M", "A menu", null, null, null, callbackButton("Yes", "intro")));
        FunnelStep sub = subscribeStep("p1", target.getId(), "M", true); // enter A at its menu M
        String parentId = seedGraphExecution(subId, BASE, sub);
        enqueueOk(1); // child menu send on the next sweep

        engine.sweep(); // parent enrolls child at M, completes
        assertThat(reload(parentId).getStatus()).isEqualTo(ExecutionStatus.completed);
        FunnelExecution child = childExecution(target.getId(), subId);
        assertThat(child).isNotNull();
        assertThat(child.getCurrentStepId()).isEqualTo("M");

        engine.sweep(); // child sends the menu and parks
        FunnelExecution parked = reload(child.getId());
        assertThat(parked.getStatus()).isEqualTo(ExecutionStatus.waiting_for_reply);
        assertThat(parked.getCurrentStepId()).isEqualTo("M");
    }

    @Test
    void subscribeDepthCapSkipsAndParentSurvives() {
        Logger eventLogger = (Logger) LoggerFactory.getLogger(FunnelEventService.class);
        ListAppender<ILoggingEvent> appender = attach(eventLogger);
        try {
            String subId = seedActiveSubscriber();
            Funnel target = seedActiveTargetFunnel(sendMessageStep("t1", "x", null));
            // Parent at enrollDepth = max (10) → child depth 11 > cap → enroll skipped.
            FunnelStep sub = subscribeStep("p1", target.getId(), null, false);
            String parentId = seedGraphExecutionAtDepth(subId, BASE, 10, sub);

            engine.sweep();

            assertThat(childExecution(target.getId(), subId)).isNull(); // no child enrolled
            assertThat(reload(parentId).getStatus()).isEqualTo(ExecutionStatus.completed); // parent NOT failed
            assertThat(warn(appender)).anyMatch(m -> m.contains(FunnelEventService.LOG_ENROLL_DROP_DEPTH_CAP));
        } finally {
            detach(eventLogger, appender);
        }
    }

    @Test
    void subscribeRateLimitBackstopSkipsAndParentSurvives() {
        Logger eventLogger = (Logger) LoggerFactory.getLogger(FunnelEventService.class);
        ListAppender<ILoggingEvent> appender = attach(eventLogger);
        try {
            String subId = seedActiveSubscriber();
            Funnel target = seedActiveTargetFunnel(sendMessageStep("t1", "x", null));
            // Pre-seed the auto-enroll counter past the per-minute limit (default 20) for this subscriber so
            // the next enroll trips the volume backstop (the SECOND backstop, separate from depth-cap).
            seedRateLimitExhausted(subId);
            FunnelStep sub = subscribeStep("p1", target.getId(), null, false);
            String parentId = seedGraphExecution(subId, BASE, sub);

            engine.sweep();

            assertThat(childExecution(target.getId(), subId)).isNull();
            assertThat(reload(parentId).getStatus()).isEqualTo(ExecutionStatus.completed); // parent NOT failed
            assertThat(warn(appender)).anyMatch(m -> m.contains(FunnelEventService.LOG_ENROLL_DROP_VOLUME));
        } finally {
            detach(eventLogger, appender);
        }
    }

    @Test
    void subscribeTargetDraftSkipsGracefully() {
        Logger eventLogger = (Logger) LoggerFactory.getLogger(FunnelEventService.class);
        ListAppender<ILoggingEvent> appender = attach(eventLogger);
        try {
            String subId = seedActiveSubscriber();
            // Target exists but is DRAFT (not active) → skip.
            Funnel target = seedTargetFunnel(FunnelStatus.draft, sendMessageStep("t1", "x", null));
            FunnelStep sub = subscribeStep("p1", target.getId(), null, false);
            String parentId = seedGraphExecution(subId, BASE, sub);

            engine.sweep();

            assertThat(childExecution(target.getId(), subId)).isNull();
            assertThat(reload(parentId).getStatus()).isEqualTo(ExecutionStatus.completed);
            assertThat(warn(appender)).anyMatch(m -> m.contains(FunnelEventService.LOG_ENROLL_SKIP_TARGET_INACTIVE));
        } finally {
            detach(eventLogger, appender);
        }
    }

    @Test
    void subscribeTargetDeletedSkipsGracefully() {
        Logger eventLogger = (Logger) LoggerFactory.getLogger(FunnelEventService.class);
        ListAppender<ILoggingEvent> appender = attach(eventLogger);
        try {
            String subId = seedActiveSubscriber();
            // Reference a funnel id that does not exist (deleted) → target missing skip.
            FunnelStep sub = subscribeStep("p1", "missing-funnel-id", null, false);
            String parentId = seedGraphExecution(subId, BASE, sub);

            engine.sweep();

            assertThat(reload(parentId).getStatus()).isEqualTo(ExecutionStatus.completed);
            assertThat(warn(appender)).anyMatch(m -> m.contains(FunnelEventService.LOG_ENROLL_SKIP_TARGET_MISSING));
        } finally {
            detach(eventLogger, appender);
        }
    }

    @Test
    void subscribeTargetWrongProjectSkipsGracefully() {
        Logger eventLogger = (Logger) LoggerFactory.getLogger(FunnelEventService.class);
        ListAppender<ILoggingEvent> appender = attach(eventLogger);
        try {
            String subId = seedActiveSubscriber();
            // Active target, but in ANOTHER project → fail-closed (IDOR guard) skip.
            Funnel foreign = new Funnel();
            foreign.setProjectId("other-project-" + seq.incrementAndGet());
            foreign.setName("foreign");
            foreign.setStatus(FunnelStatus.active);
            foreign.setTriggerType(FunnelService.TRIGGER_ON_START);
            foreign.setTriggerValue("");
            foreign.setSteps(new ArrayList<>(List.of(sendMessageStep("t1", "x", null))));
            foreign.setCreatedAt(BASE);
            foreign.setUpdatedAt(BASE);
            foreign = funnelRepository.save(foreign);

            FunnelStep sub = subscribeStep("p1", foreign.getId(), null, false);
            String parentId = seedGraphExecution(subId, BASE, sub);

            engine.sweep();

            assertThat(childExecution(foreign.getId(), subId)).isNull();
            assertThat(reload(parentId).getStatus()).isEqualTo(ExecutionStatus.completed);
            assertThat(warn(appender)).anyMatch(m ->
                    m.contains(FunnelEventService.LOG_ENROLL_SKIP_TARGET_WRONG_PROJECT));
        } finally {
            detach(eventLogger, appender);
        }
    }

    @Test
    void subscribeMissingEntryStepFallsBackToStart() {
        Logger eventLogger = (Logger) LoggerFactory.getLogger(FunnelEventService.class);
        ListAppender<ILoggingEvent> appender = attach(eventLogger);
        try {
            String subId = seedActiveSubscriber();
            Funnel target = seedActiveTargetFunnel(
                    sendMessageStep("first", "t1", null),
                    sendMessageStep("second", "t2", null));
            // Entry step "ghost" does not exist in the target → fall back to step 0 (first) + WARN.
            FunnelStep sub = subscribeStep("p1", target.getId(), "ghost", true);
            String parentId = seedGraphExecution(subId, BASE, sub);

            engine.sweep();

            FunnelExecution child = childExecution(target.getId(), subId);
            assertThat(child).isNotNull();
            assertThat(child.getCurrentStepId()).isEqualTo("first"); // fell back to step 0
            assertThat(reload(parentId).getStatus()).isEqualTo(ExecutionStatus.completed);
            assertThat(warn(appender)).anyMatch(m -> m.contains(FunnelEventService.LOG_ENROLL_ENTRY_STEP_FALLBACK));
        } finally {
            detach(eventLogger, appender);
        }
    }

    @Test
    void subscribeReEnterNoOpWhenTargetInflight() {
        Logger eventLogger = (Logger) LoggerFactory.getLogger(FunnelEventService.class);
        ListAppender<ILoggingEvent> appender = attach(eventLogger);
        try {
            String subId = seedActiveSubscriber();
            // allowReEnter=false target, already in-flight for this subscriber → re-enter guard no-op.
            Funnel target = seedActiveTargetFunnel(false, sendMessageStep("t1", "x", null));
            String existingChildId = seedRunningChild(target.getId(), subId, "t1");
            FunnelStep sub = subscribeStep("p1", target.getId(), null, false);
            String parentId = seedGraphExecution(subId, BASE, sub);

            engine.sweep();

            // Still exactly one (running|waiting) child execution for the pair → no duplicate enrolled.
            assertThat(activeChildren(target.getId(), subId)).hasSize(1);
            assertThat(activeChildren(target.getId(), subId).get(0).getId()).isEqualTo(existingChildId);
            assertThat(reload(parentId).getStatus()).isEqualTo(ExecutionStatus.completed);
            assertThat(warn(appender)).anyMatch(m -> m.contains(FunnelEventService.LOG_ENROLL_REENTER_IGNORED));
        } finally {
            detach(eventLogger, appender);
        }
    }

    @Test
    void subscribeReEnterTrueRestartsTargetFromEntry() {
        String subId = seedActiveSubscriber();
        // allowReEnter=true target, already in-flight → cancel-then-insert (restart) at the entry step.
        Funnel target = seedActiveTargetFunnel(true,
                sendMessageStep("intro", "t1", null),
                sendMessageStep("mid", "t2", null));
        String oldChildId = seedRunningChild(target.getId(), subId, "intro");
        FunnelStep sub = subscribeStep("p1", target.getId(), "mid", true); // restart at "mid"
        String parentId = seedGraphExecution(subId, BASE, sub);

        engine.sweep();

        // Old child cancelled; a fresh child started at the entry step "mid".
        assertThat(reload(oldChildId).getStatus()).isEqualTo(ExecutionStatus.cancelled);
        List<FunnelExecution> active = activeChildren(target.getId(), subId);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getId()).isNotEqualTo(oldChildId);
        assertThat(active.get(0).getCurrentStepId()).isEqualTo("mid");
        assertThat(reload(parentId).getStatus()).isEqualTo(ExecutionStatus.completed);
    }

    @Test
    void subscribeSelfTargetReEnterFalseNoOp() {
        // Self-target A→A with allowReEnter=false: the parent IS the in-flight execution for (funnelId,
        // subscriberId), so the enroll hits the re-enter guard and is a no-op — no second A execution.
        String subId = seedActiveSubscriber();
        Funnel selfFunnel = seedActiveTargetFunnel(false,
                subscribeStep("p1", null, null, false), // will be repointed to its own id below
                sendMessageStep("after", "tail", null));
        // Repoint the SUBSCRIBE step's target to the funnel's own id and persist; also seed the parent
        // execution AS this funnel's running execution so the pair is already in-flight.
        selfFunnel.getSteps().get(0).setTargetFunnelId(selfFunnel.getId());
        selfFunnel.getSteps().get(0).setNext("after");
        funnelRepository.save(selfFunnel);

        FunnelStep selfSub = subscribeStep("p1", selfFunnel.getId(), null, false);
        selfSub.setNext("after");
        FunnelStep after = sendMessageStep("after", "tail", null);
        String parentId = seedRunningChildGraph(selfFunnel.getId(), subId, BASE, selfSub, after);
        enqueueOk(1); // the parent's own 'after' send

        engine.sweep();

        // Only the original (now completed) execution remains for the pair — no duplicate self-enroll.
        List<FunnelExecution> all = mongoTemplate.find(
                Query.query(Criteria.where("funnelId").is(selfFunnel.getId()).and("subscriberId").is(subId)),
                FunnelExecution.class);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getId()).isEqualTo(parentId);
        assertThat(reload(parentId).getStatus()).isEqualTo(ExecutionStatus.completed);
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

    // Composer (MESSAGE) seed-helpers — replace the former flat SEND_MESSAGE/SEND_IMAGE/MENU types
    // (15-message-composer / Decision 1). Each builds a single-block MESSAGE step so the engine sends ONE
    // Telegram message per step, preserving each IT's send-count assertions. Graph form (id/next) and the
    // step-level buttons/timeout (Decision 2) are unchanged.
    private FunnelStep sendMessage(String text) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.MESSAGE);
        s.setBlocks(List.of(new ContentBlock(BlockType.TEXT, text, null, null, null, null)));
        return s;
    }

    private FunnelStep sendImage(String url) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.MESSAGE);
        s.setBlocks(List.of(new ContentBlock(BlockType.IMAGE, null, null, url, null, null)));
        return s;
    }

    private FunnelStep delay(int value, String unit) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.DELAY);
        s.setDelayValue(value);
        s.setDelayUnit(unit);
        return s;
    }

    // A multi-block MESSAGE composer step WITH a stable step id (GRAPH mode): N TEXT blocks sent as N separate
    // messages under ONE engine claim (Decision 3 per-node at-most-once). The id makes the snapshot graph-mode
    // (FunnelExecutionEngine.graphMode probes snapshot.get(0).getId() != null), so it MUST be seeded via
    // seedGraphExecution (which sets currentStepId). Used by the snapshot-isolation test that drives
    // resumeOnCallback by step id.
    private FunnelStep multiTextMessage(String id, String next, String... texts) {
        FunnelStep s = multiTextMessageBlocks(texts);
        s.setId(id);
        s.setNext(next);
        return s;
    }

    // A multi-block MESSAGE composer step WITHOUT a step id (INDEX/linear mode). Used by the index-seeded
    // crash + happy-path tests below: seedExecution drives by currentStepIndex and never sets currentStepId.
    // Setting an id here would flip the snapshot into graph mode where a null currentStepId is the explicit
    // "End" marker — the engine would complete the run WITHOUT executing the step (zero sends). Keeping the
    // first step id-less keeps the run in index-drain mode so currentStepIndex=0 actually executes the step.
    // Distinct name (not a multiTextMessage overload) so a call like ("b1","b2","b3") can never silently bind
    // to the id-setting (String,String,String...) variant and re-introduce the graph-mode mismatch.
    private FunnelStep multiTextMessageIndexed(String... texts) {
        return multiTextMessageBlocks(texts);
    }

    // Shared block-builder: N TEXT blocks (no id/next). Each block sends ONE Telegram message; the step is
    // sent under a single engine claim (Decision 3) so the send-count assertions hold across both modes.
    private FunnelStep multiTextMessageBlocks(String... texts) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.MESSAGE);
        List<ContentBlock> blocks = new ArrayList<>(texts.length);
        for (String t : texts) {
            blocks.add(new ContentBlock(BlockType.TEXT, t, null, null, null, null));
        }
        s.setBlocks(blocks);
        return s;
    }

    // Graph-shaped step builders (Phase 2): steps carry stable ids so the engine navigates by
    // currentStepId. seedGraphExecution seeds currentStepId to the first step's id.
    private FunnelStep sendMessageStep(String id, String text, String next) {
        FunnelStep s = new FunnelStep();
        s.setId(id);
        s.setNext(next);
        s.setStepType(StepType.MESSAGE);
        s.setBlocks(List.of(new ContentBlock(BlockType.TEXT, text, null, null, null, null)));
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

    // Composer (MESSAGE) menu-equivalent: a single TEXT block with the step-level inline keyboard + timeout
    // attached to that (last non-album) block by the executor (Decision 2). Replaces the former MENU type;
    // park-on-reply + callback wire-contract {executionId}:{buttonIndex} are unchanged.
    private FunnelStep menu(String id, String text, Integer timeoutValue, String timeoutUnit,
                            String timeoutTargetStepId, Button... buttons) {
        FunnelStep s = new FunnelStep();
        s.setId(id);
        s.setStepType(StepType.MESSAGE);
        s.setBlocks(List.of(new ContentBlock(BlockType.TEXT, text, null, null, null, null)));
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

    // SUBSCRIBE_TO_FUNNEL step builder (Phase 5 / composition) — graph-shaped (carries a stable id).
    private FunnelStep subscribeStep(String id, String targetFunnelId, String targetEntryStepId,
                                     boolean endParentAfter) {
        FunnelStep s = new FunnelStep();
        s.setId(id);
        s.setStepType(StepType.SUBSCRIBE_TO_FUNNEL);
        s.setTargetFunnelId(targetFunnelId);
        s.setTargetEntryStepId(targetEntryStepId);
        s.setEndParentAfter(endParentAfter);
        return s;
    }

    // Seed an ACTIVE target funnel in THIS project's funnels collection (allowReEnter=false by default).
    private Funnel seedActiveTargetFunnel(FunnelStep... steps) {
        return seedTargetFunnel(FunnelStatus.active, false, steps);
    }

    private Funnel seedActiveTargetFunnel(boolean allowReEnter, FunnelStep... steps) {
        return seedTargetFunnel(FunnelStatus.active, allowReEnter, steps);
    }

    private Funnel seedTargetFunnel(FunnelStatus status, FunnelStep... steps) {
        return seedTargetFunnel(status, false, steps);
    }

    private Funnel seedTargetFunnel(FunnelStatus status, boolean allowReEnter, FunnelStep... steps) {
        Funnel f = new Funnel();
        f.setProjectId(projectId);
        f.setName("target-" + seq.incrementAndGet());
        f.setStatus(status);
        f.setTriggerType(FunnelService.TRIGGER_ON_START);
        f.setTriggerValue("t-" + seq.incrementAndGet());
        f.setAllowReEnter(allowReEnter);
        f.setSteps(new ArrayList<>(List.of(steps)));
        f.setCreatedAt(BASE);
        f.setUpdatedAt(BASE);
        return funnelRepository.save(f);
    }

    // A graph execution seeded at an explicit enrollDepth (depth-cap test).
    private String seedGraphExecutionAtDepth(String subscriberId, Instant nextRunAt, int enrollDepth,
                                             FunnelStep... steps) {
        String id = seedGraphExecution(subscriberId, nextRunAt, steps);
        FunnelExecution e = reload(id);
        e.setEnrollDepth(enrollDepth);
        return mongoTemplate.save(e).getId();
    }

    // Drive the auto-enroll rate-limit counter past the per-minute limit for a subscriber so the next
    // enroll trips the volume backstop (mirrors FunnelEventService's Redis key + the default limit of 20).
    private void seedRateLimitExhausted(String subscriberId) {
        String key = "bf:rate:auto-enroll:" + subscriberId;
        redisTemplate.opsForValue().set(key, "1000"); // far above the per-minute cap
    }

    // The single running|waiting child execution for the (targetFunnelId, subscriberId) pair, or null.
    private FunnelExecution childExecution(String targetFunnelId, String subscriberId) {
        List<FunnelExecution> children = activeChildren(targetFunnelId, subscriberId);
        return children.isEmpty() ? null : children.get(0);
    }

    private List<FunnelExecution> activeChildren(String targetFunnelId, String subscriberId) {
        return mongoTemplate.find(
                Query.query(Criteria.where("funnelId").is(targetFunnelId)
                        .and("subscriberId").is(subscriberId)
                        .and("status").in(ExecutionStatus.running.name(), ExecutionStatus.waiting.name(),
                                ExecutionStatus.waiting_for_reply.name())),
                FunnelExecution.class);
    }

    // Seed an already-running child execution for the (funnelId, subscriberId) pair (re-enter tests), with
    // a snapshot so the engine could progress it — but the tests assert pre-/post-enroll, not its progress.
    private String seedRunningChild(String funnelId, String subscriberId, String currentStepId) {
        FunnelExecution e = new FunnelExecution();
        e.setProjectId(projectId);
        e.setFunnelId(funnelId);
        e.setSubscriberId(subscriberId);
        e.setTelegramBotId(TELEGRAM_BOT_ID);
        e.setStatus(ExecutionStatus.running);
        e.setStepRunStatus(StepRunStatus.pending);
        // Far-future nextRunAt so the sweep does not claim/progress this seeded child within the test tick.
        e.setNextRunAt(BASE.plus(Duration.ofDays(365)));
        e.setCurrentStepId(currentStepId);
        e.setCurrentStepIndex(0);
        e.setStepsSnapshot(new ArrayList<>());
        e.setCreatedAt(BASE);
        e.setUpdatedAt(BASE);
        return mongoTemplate.save(e).getId();
    }

    // Seed a running execution for (funnelId, subscriberId) that IS the parent being swept (self-target
    // test): due now, with the given snapshot so the sweep drives it.
    private String seedRunningChildGraph(String funnelId, String subscriberId, Instant nextRunAt,
                                         FunnelStep... steps) {
        FunnelExecution e = new FunnelExecution();
        e.setProjectId(projectId);
        e.setFunnelId(funnelId);
        e.setSubscriberId(subscriberId);
        e.setTelegramBotId(TELEGRAM_BOT_ID);
        e.setStatus(ExecutionStatus.running);
        e.setStepRunStatus(StepRunStatus.pending);
        e.setNextRunAt(nextRunAt);
        List<FunnelStep> snapshot = new ArrayList<>(List.of(steps));
        e.setCurrentStepId(snapshot.isEmpty() ? null : snapshot.get(0).getId());
        e.setCurrentStepIndex(0);
        e.setStepsSnapshot(snapshot);
        e.setCreatedAt(BASE);
        e.setUpdatedAt(BASE);
        return mongoTemplate.save(e).getId();
    }

    private static ListAppender<ILoggingEvent> attach(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(Logger logger, ListAppender<ILoggingEvent> appender) {
        logger.detachAppender(appender);
        appender.stop();
    }

    private static List<String> warn(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
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

    // Returns the saved ACTIVE Subscriber doc (with a distinctive firstName so no-PII assertions are
    // meaningful) — advanceOnCallback ITs need the telegramChatId + id to drive the public method.
    private Subscriber seedActiveSubscriberDoc() {
        Subscriber s = new Subscriber();
        s.setProjectId(projectId);
        s.setTelegramUserId(seq.incrementAndGet());
        s.setTelegramChatId(seq.incrementAndGet());
        s.setTelegramBotId(TELEGRAM_BOT_ID);
        s.setFirstName("PII_FIRST_NAME_" + seq.incrementAndGet());
        s.setStatus(SubscriberStatus.ACTIVE);
        s.setSubscribedAt(BASE);
        s.setLastSeenAt(BASE);
        return subscriberRepository.save(s);
    }

    // Seed an execution parked on a MENU (status=waiting_for_reply, stepRunStatus=pending), cursor on
    // currentStepId, nextRunAt=null (untimed menu) — the post-park shape a callback resumes from.
    private String seedParkedExecution(String subscriberId, String currentStepId, FunnelStep... steps) {
        FunnelExecution e = new FunnelExecution();
        e.setProjectId(projectId);
        e.setFunnelId("funnel-" + seq.incrementAndGet());
        e.setSubscriberId(subscriberId);
        e.setTelegramBotId(TELEGRAM_BOT_ID);
        e.setStatus(ExecutionStatus.waiting_for_reply);
        e.setStepRunStatus(StepRunStatus.pending);
        e.setNextRunAt(null);
        List<FunnelStep> snapshot = new ArrayList<>(List.of(steps));
        e.setCurrentStepId(currentStepId);
        e.setCurrentStepIndex(0);
        e.setStepsSnapshot(snapshot);
        e.setCreatedAt(BASE);
        e.setUpdatedAt(BASE);
        return mongoTemplate.save(e).getId();
    }

    private List<Event> funnelButtonClickedEvents() {
        return mongoTemplate.find(
                Query.query(Criteria.where("eventType").is("funnel_button_clicked")), Event.class);
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
