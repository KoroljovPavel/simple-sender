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
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
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
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        engineLogger.addAppender(appender);
        try {
            String subId = seedActiveSubscriber();
            String pii = "PII_SECRET_PAYLOAD_42";
            String execId = seedExecution(subId, BASE, sendMessage(pii));
            enqueueOk(1);

            engine.sweep();

            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).anyMatch(m -> m.contains(FunnelExecutionEngine.LOG_CLAIM_WON));
            assertThat(messages).anyMatch(m -> m.contains(FunnelExecutionEngine.LOG_EXECUTION_COMPLETED));
            assertThat(messages).as("no rendered payload (PII) in engine logs")
                    .noneMatch(m -> m.contains(pii));
            assertThat(reload(execId).getStatus()).isEqualTo(ExecutionStatus.completed);
        } finally {
            engineLogger.detachAppender(appender);
            appender.stop();
        }
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
