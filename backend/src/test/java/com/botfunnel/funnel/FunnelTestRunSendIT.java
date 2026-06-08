package com.botfunnel.funnel;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.common.crypto.EncryptedValue;
import com.botfunnel.common.crypto.TokenEncryptor;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberRepository;
import com.botfunnel.subscriber.SubscriberStatus;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

// Test-run send-assertion IT (Task 2). Reuses the FunnelExecutionEngineIT MockWebServer harness shape:
// a JVM-singleton MockWebServer wired into app.telegram.base-url via @DynamicPropertySource, enqueueOk(n),
// and a direct engine.sweep() (the sweep is normally JobRunr-scheduled). Proves that test-run enrolls the
// owner (depth=0) and that a subsequent sweep actually issues a Telegram sendMessage (request-count delta).
// Tagged slow per the engine-harness convention (run with -PrunSlow=true).
@Tag("slow")
class FunnelTestRunSendIT extends AbstractIntegrationTest {

    private static final String TOKEN = "1234567890:ABCdefGHI_jklMNOpqrSTUvwxYZ0123456789xyz";
    private static final Long TELEGRAM_BOT_ID = 778811L;
    private static final Long OWNER_CHAT_ID = 424242L;
    private static final String USER_ID = "testrun-send-user";

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

    @Autowired FunnelService funnelService;
    @Autowired FunnelExecutionEngine engine;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired FunnelRepository funnelRepository;
    @Autowired BotRepository botRepository;
    @Autowired SubscriberRepository subscriberRepository;
    @Autowired TokenEncryptor tokenEncryptor;
    @Autowired org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    private final AtomicLong seq = new AtomicLong(1);
    private String projectId;
    private int sendBaseline;

    @BeforeEach
    void cleanAndSeed() {
        TELEGRAM.setDispatcher(new QueueDispatcher());
        mongoTemplate.remove(new org.springframework.data.mongodb.core.query.Query(), FunnelExecution.class);
        userRepository.deleteAll();
        projectRepository.deleteAll();
        funnelRepository.deleteAll();
        botRepository.deleteAll();
        subscriberRepository.deleteAll();

        seedUser();
        projectId = saveProject();
        sendBaseline = TELEGRAM.getRequestCount();
    }

    @AfterEach
    void drain() {
        TELEGRAM.setDispatcher(new QueueDispatcher());
    }

    private int sentCount() {
        return TELEGRAM.getRequestCount() - sendBaseline;
    }

    @Test
    void testRunLinkedBotSends() throws InterruptedException {
        seedConnectedBot();
        Subscriber owner = seedActiveOwnerSubscriber();
        Funnel f = seedDraftFunnel("hello from test-run");
        enqueueOk(1);

        funnelService.testRun(USER_ID, projectId, f.getId());

        // The enroll created a fresh depth-0 execution from step 0, pinned to the bot.
        List<FunnelExecution> execs = mongoTemplate.find(
                org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("funnelId").is(f.getId())
                                .and("subscriberId").is(owner.getId())),
                FunnelExecution.class);
        assertThat(execs).hasSize(1);
        assertThat(execs.get(0).getEnrollDepth()).isZero();
        assertThat(execs.get(0).getTelegramBotId()).isEqualTo(TELEGRAM_BOT_ID);

        engine.sweep();

        // The sweep actually issued a Telegram sendMessage and the execution completed.
        assertThat(sentCount()).isEqualTo(1);
        assertThat(mongoTemplate.findById(execs.get(0).getId(), FunnelExecution.class).getStatus())
                .isEqualTo(ExecutionStatus.completed);

        // Delivery proof: inspect the recorded request — it must be a Telegram /bot<token>/sendMessage
        // carrying the owner's chat_id and the rendered step text (not merely "a request happened").
        RecordedRequest sent = TELEGRAM.takeRequest();
        assertThat(sent).isNotNull();
        assertThat(sent.getPath()).isEqualTo("/bot" + TOKEN + "/sendMessage");
        String body = sent.getBody().readUtf8();
        assertThat(body).contains("\"chat_id\":" + OWNER_CHAT_ID);
        assertThat(body).contains("hello from test-run");
    }

    @Test
    void testRunParentWithSubscribeStepEnrollsTargetAtChildDepth() throws InterruptedException {
        // A depth-0 root test-run of a parent funnel whose ONLY step is SUBSCRIBE_TO_FUNNEL(target, end=true):
        // the test-run enrolls the owner into the parent (depth 0); the first sweep runs the SUBSCRIBE step,
        // which enrolls the SAME owner into the active target at child depth 1; the child-send is observed on
        // the next sweep (the test-run HTTP response itself only confirms the parent enroll).
        seedConnectedBot();
        Subscriber owner = seedActiveOwnerSubscriber();
        Funnel target = seedActiveTargetFunnel("child step text");
        Funnel parent = seedDraftSubscribeFunnel(target.getId());

        funnelService.testRun(USER_ID, projectId, parent.getId());

        // The test-run created a fresh depth-0 parent execution, pinned to the bot.
        FunnelExecution parentExec = onlyExecution(parent.getId(), owner.getId());
        assertThat(parentExec.getEnrollDepth()).isZero();

        // First sweep: the parent's SUBSCRIBE step enrolls the target (no parent send — SUBSCRIBE end=true).
        engine.sweep();
        assertThat(mongoTemplate.findById(parentExec.getId(), FunnelExecution.class).getStatus())
                .isEqualTo(ExecutionStatus.completed);

        FunnelExecution childExec = onlyExecution(target.getId(), owner.getId());
        assertThat(childExec.getEnrollDepth()).isEqualTo(1);                  // parent 0 + 1
        assertThat(childExec.getTelegramBotId()).isEqualTo(TELEGRAM_BOT_ID);  // inherited from parent

        // Second sweep: the child actually sends (request-count delta proves a real Telegram send).
        enqueueOk(1);
        engine.sweep();
        assertThat(sentCount()).isEqualTo(1);
        assertThat(mongoTemplate.findById(childExec.getId(), FunnelExecution.class).getStatus())
                .isEqualTo(ExecutionStatus.completed);

        // Drain the recorded request from the JVM-singleton MockWebServer so it does not bleed into
        // another test's takeRequest() (the server's request log is cumulative across the test class).
        TELEGRAM.takeRequest();
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private FunnelExecution onlyExecution(String funnelId, String subscriberId) {
        List<FunnelExecution> execs = mongoTemplate.find(
                org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("funnelId").is(funnelId)
                                .and("subscriberId").is(subscriberId)),
                FunnelExecution.class);
        assertThat(execs).hasSize(1);
        return execs.get(0);
    }

    private Funnel seedActiveTargetFunnel(String text) {
        FunnelStep step = new FunnelStep();
        step.setStepType(StepType.SEND_MESSAGE);
        step.setId("c1");
        step.setText(text);
        step.setOrder(0);
        Funnel f = new Funnel();
        f.setProjectId(projectId);
        f.setName("Target");
        f.setStatus(FunnelStatus.active);
        f.setTriggerType(FunnelService.TRIGGER_ON_START);
        f.setTriggerValue("target-trigger");
        f.setSteps(new ArrayList<>(List.of(step)));
        f.setCreatedAt(Instant.now());
        f.setUpdatedAt(Instant.now());
        return funnelRepository.save(f);
    }

    private Funnel seedDraftSubscribeFunnel(String targetFunnelId) {
        FunnelStep step = new FunnelStep();
        step.setStepType(StepType.SUBSCRIBE_TO_FUNNEL);
        step.setId("p1");
        step.setTargetFunnelId(targetFunnelId);
        step.setEndParentAfter(true);
        step.setOrder(0);
        Funnel f = new Funnel();
        f.setProjectId(projectId);
        f.setName("Parent");
        f.setStatus(FunnelStatus.draft);
        f.setTriggerType(FunnelService.TRIGGER_ON_START);
        f.setTriggerValue("");
        f.setSteps(new ArrayList<>(List.of(step)));
        f.setCreatedAt(Instant.now());
        f.setUpdatedAt(Instant.now());
        return funnelRepository.save(f);
    }

    private void enqueueOk(int count) {
        for (int i = 0; i < count; i++) {
            TELEGRAM.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"ok\":true,\"result\":{\"message_id\":1,\"chat\":{\"id\":99}}}"));
        }
    }

    private void seedConnectedBot() {
        EncryptedValue ev = tokenEncryptor.encrypt(TOKEN);
        Bot bot = new Bot();
        bot.setProjectId(projectId);
        bot.setTelegramBotId(TELEGRAM_BOT_ID);
        bot.setTelegramUsername("testrun_bot");
        bot.setStatus(BotStatus.CONNECTED);
        bot.setOwnerChatId(OWNER_CHAT_ID);
        bot.setEncryptedTokenIv(Base64.getEncoder().encodeToString(ev.iv()));
        bot.setEncryptedTokenCiphertext(Base64.getEncoder().encodeToString(ev.ciphertext()));
        bot.setConnectedAt(Instant.now());
        botRepository.save(bot);
    }

    private Subscriber seedActiveOwnerSubscriber() {
        Subscriber s = new Subscriber();
        s.setProjectId(projectId);
        s.setTelegramUserId(seq.incrementAndGet());
        s.setTelegramChatId(OWNER_CHAT_ID);
        s.setTelegramBotId(TELEGRAM_BOT_ID);
        s.setFirstName("Owner");
        s.setStatus(SubscriberStatus.ACTIVE);
        s.setSubscribedAt(Instant.now());
        s.setLastSeenAt(Instant.now());
        return subscriberRepository.save(s);
    }

    private Funnel seedDraftFunnel(String text) {
        FunnelStep step = new FunnelStep();
        step.setStepType(StepType.SEND_MESSAGE);
        step.setId("s1");
        step.setText(text);
        step.setOrder(0);
        Funnel f = new Funnel();
        f.setProjectId(projectId);
        f.setName("TestRun");
        f.setStatus(FunnelStatus.draft);
        f.setTriggerType(FunnelService.TRIGGER_ON_START);
        f.setTriggerValue("");
        f.setSteps(new ArrayList<>(List.of(step)));
        f.setCreatedAt(Instant.now());
        f.setUpdatedAt(Instant.now());
        return funnelRepository.save(f);
    }

    private void seedUser() {
        User u = new User();
        u.setId(USER_ID);
        u.setEmail("testrun-send@test.com");
        u.setName("Owner");
        u.setPasswordHash("x");
        u.setStatus(UserStatus.active);
        u.setSuperAdmin(false);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);
    }

    private String saveProject() {
        Project p = new Project();
        p.setOwnerId(USER_ID);
        p.setName("Proj-" + System.nanoTime());
        p.setTimezone("UTC");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return projectRepository.save(p).getId();
    }
}
