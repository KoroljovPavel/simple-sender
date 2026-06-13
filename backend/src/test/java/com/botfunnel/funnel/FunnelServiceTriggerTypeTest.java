package com.botfunnel.funnel;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.common.AppException;
import com.botfunnel.funnel.dto.ContentBlockDto;
import com.botfunnel.funnel.dto.CreateFunnelRequest;
import com.botfunnel.funnel.dto.FunnelResponse;
import com.botfunnel.funnel.dto.FunnelStepDto;
import com.botfunnel.funnel.dto.TriggerDto;
import com.botfunnel.funnel.dto.UpdateFunnelRequest;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 8 (17-funnel-multi-entry / Decision 1, 6, 10, 14): trigger-array validation through
 * {@link FunnelService}. Covers per-element value rules (carried over from the flat-trio era), the
 * cross-trigger rules (≤1 on_start, duplicate event_name reject, entryStepId resolution, event-only
 * mid-entry, array-size + per-trigger keyword caps), the onStartTriggerValue scalar sync, and the
 * duplicate-resets-trigger precedent.
 */
class FunnelServiceTriggerTypeTest extends AbstractIntegrationTest {

    private static final String USER_ID = "ftt-user";

    private static final Long TELEGRAM_BOT_ID = 990011L;
    private static final Long OWNER_CHAT_ID = 555111L;
    private static final String TEST_TOKEN = "1234567890:ABCdefGHI_jklMNOpqrSTUvwxYZ0123456789xyz";

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired FunnelRepository funnelRepository;
    @Autowired FunnelService funnelService;
    @Autowired com.botfunnel.bot.BotRepository botRepository;
    @Autowired com.botfunnel.subscriber.SubscriberRepository subscriberRepository;
    @Autowired org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    @Autowired com.botfunnel.common.crypto.TokenEncryptor tokenEncryptor;

    private String projectId;

    @BeforeEach
    void seed() {
        userRepository.deleteAll();
        projectRepository.deleteAll();
        funnelRepository.deleteAll();
        botRepository.deleteAll();
        subscriberRepository.deleteAll();
        mongoTemplate.remove(new org.springframework.data.mongodb.core.query.Query(), FunnelExecution.class);

        User u = new User();
        u.setId(USER_ID);
        u.setEmail("ftt@test.com");
        u.setName("Owner");
        u.setPasswordHash("x");
        u.setStatus(UserStatus.active);
        u.setSuperAdmin(false);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);

        Project p = new Project();
        p.setOwnerId(USER_ID);
        p.setName("P");
        p.setTimezone("UTC");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        projectId = projectRepository.save(p).getId();
    }

    // ─── builders ──────────────────────────────────────────────────────────────

    private String createDraft() {
        return funnelService.create(USER_ID, projectId, new CreateFunnelRequest("f", null)).id();
    }

    private static TriggerDto onStart(String value) {
        return new TriggerDto("on_start", value, null, null, null);
    }

    private static TriggerDto event(String value, String entryStepId) {
        return new TriggerDto("event", value, null, entryStepId, null);
    }

    private static TriggerDto keyword(List<String> keywords) {
        return new TriggerDto("keyword", null, keywords, null, null);
    }

    // A minimal valid MESSAGE step (one TEXT block) carrying a caller-chosen stable id, so an event
    // trigger's entryStepId can resolve to it.
    private static FunnelStepDto messageStep(String id, String text) {
        ContentBlockDto block = new ContentBlockDto("TEXT", text, null, null, null, null);
        return new FunnelStepDto(StepType.MESSAGE, id, null, null, null, null, null,
                List.of(block), null, null, null, null, null, null, null, null, false,
                null, null, null, null, null, null);
    }

    private UpdateFunnelRequest req(List<TriggerDto> triggers, List<FunnelStepDto> steps) {
        return new UpdateFunnelRequest("f", null, false, triggers, steps, null);
    }

    private FunnelResponse update(String id, List<TriggerDto> triggers, List<FunnelStepDto> steps) {
        return funnelService.update(USER_ID, projectId, id, req(triggers, steps));
    }

    private void assert422(String id, List<TriggerDto> triggers, List<FunnelStepDto> steps,
                           String expectedCode) {
        assertThatThrownBy(() -> update(id, triggers, steps))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException ae = (AppException) ex;
                    assertThat(ae.getStatus().value()).isEqualTo(422);
                    assertThat(ae.getCode()).isEqualTo(expectedCode);
                });
    }

    // ─── per-type value rules (carried over) ─────────────────────────────────────

    @Test
    void triggerType_unknownRejected() {
        String id = createDraft();
        assert422(id, List.of(new TriggerDto("totally_bogus", "x", null, null, null)), List.of(),
                FunnelService.CODE_INVALID_TRIGGER_TYPE);
    }

    @Test
    void onStart_stillDefaultsWhenTriggersNull() {
        // A null triggers array must normalise to a single on_start (no regression on the old default).
        String id = createDraft();
        update(id, null, List.of());
        Funnel reloaded = funnelRepository.findById(id).orElseThrow();
        assertThat(reloaded.getTriggers()).hasSize(1);
        assertThat(reloaded.getTriggers().get(0).getTriggerType()).isEqualTo("on_start");
    }

    // ─── cross-trigger rules (TDD anchor) ────────────────────────────────────────

    @Test
    void triggers_duplicateEventNameRejected() {
        String id = createDraft();
        FunnelStepDto s = messageStep("step-1", "hi");
        assert422(id,
                List.of(onStart(""), event("purchase", "step-1"), event("purchase", "step-1")),
                List.of(s),
                FunnelService.CODE_DUPLICATE_EVENT_NAME);
    }

    @Test
    void triggers_atMostOneOnStart() {
        String id = createDraft();
        assert422(id, List.of(onStart(""), onStart("promo")), List.of(),
                FunnelService.CODE_MULTIPLE_ON_START);
    }

    @Test
    void triggers_danglingEntryStepIdRejected() {
        String id = createDraft();
        FunnelStepDto s = messageStep("step-1", "hi");
        assert422(id, List.of(onStart(""), event("purchase", "no-such-step")), List.of(s),
                FunnelService.CODE_INVALID_ENTRY_STEP);
    }

    @Test
    void triggers_eventRequiresEntryStepId() {
        String id = createDraft();
        FunnelStepDto s = messageStep("step-1", "hi");
        assert422(id, List.of(onStart(""), event("purchase", null)), List.of(s),
                FunnelService.CODE_INVALID_ENTRY_STEP);
    }

    @Test
    void triggers_nonEventNonOnStartEntryStepIdRejected() {
        // 18-funnel-canvas / Decision 5: mid-entry entryStepId is allowed for event AND on_start (the start
        // node's drawn edge). A NON-event NON-on_start type (keyword) carrying an entryStepId is still → 422.
        String id = createDraft();
        FunnelStepDto s = messageStep("step-1", "hi");
        assert422(id,
                List.of(onStart(""), new TriggerDto("keyword", null, List.of("hello"), "step-1", null)),
                List.of(s),
                FunnelService.CODE_INVALID_ENTRY_STEP);
    }

    @Test
    void triggers_onStartEntryStepIdAcceptedWhenResolves() {
        // 18-funnel-canvas / Decision 5: the start node's drawn `next` edge is persisted on the on_start
        // trigger's entryStepId — accepted (and persisted) when it resolves to a step of THIS funnel.
        String id = createDraft();
        FunnelStepDto s = messageStep("step-1", "hi");
        assertThatCode(() -> update(id,
                List.of(new TriggerDto("on_start", "", null, "step-1", null)),
                List.of(s)))
                .doesNotThrowAnyException();
        Funnel reloaded = funnelRepository.findById(id).orElseThrow();
        Trigger onStart = reloaded.getTriggers().stream()
                .filter(t -> "on_start".equals(t.getTriggerType())).findFirst().orElseThrow();
        assertThat(onStart.getEntryStepId()).isEqualTo("step-1");
    }

    @Test
    void triggers_onStartDanglingEntryStepIdRejected() {
        // 18-funnel-canvas / Decision 5: an on_start entryStepId that does NOT resolve to a step of this
        // funnel (deleted target) → 422 (same funnel-scoped check as event).
        String id = createDraft();
        FunnelStepDto s = messageStep("step-1", "hi");
        assert422(id,
                List.of(new TriggerDto("on_start", "", null, "no-such-step", null)),
                List.of(s),
                FunnelService.CODE_INVALID_ENTRY_STEP);
    }

    @Test
    void triggers_onStartNullEntryStepIdAccepted() {
        // 18-funnel-canvas / Decision 5: a null on_start entryStepId is a broken/absent-edge state (start
        // node with no drawn next), surfaced elsewhere — NOT a hard 422 here (unlike event).
        String id = createDraft();
        FunnelStepDto s = messageStep("step-1", "hi");
        assertThatCode(() -> update(id,
                List.of(new TriggerDto("on_start", "", null, null, null)),
                List.of(s)))
                .doesNotThrowAnyException();
        Funnel reloaded = funnelRepository.findById(id).orElseThrow();
        Trigger onStart = reloaded.getTriggers().stream()
                .filter(t -> "on_start".equals(t.getTriggerType())).findFirst().orElseThrow();
        assertThat(onStart.getEntryStepId()).isNull();
    }

    @Test
    void triggers_multipleEventsSameEntryStepAllowed() {
        // Two DIFFERENT event_names on the SAME entry step is allowed (NOT a duplicate — duplicate is same
        // event_name).
        String id = createDraft();
        FunnelStepDto s = messageStep("step-1", "hi");
        assertThatCode(() -> update(id,
                List.of(onStart(""), event("purchase", "step-1"), event("refund", "step-1")),
                List.of(s)))
                .doesNotThrowAnyException();
        Funnel reloaded = funnelRepository.findById(id).orElseThrow();
        assertThat(reloaded.getTriggers()).hasSize(3);
    }

    @Test
    void triggers_arraySizeCapRejected() {
        // 51 triggers (1 on_start + 50 distinct events) exceeds MAX_TRIGGERS=50.
        String id = createDraft();
        FunnelStepDto s = messageStep("step-1", "hi");
        List<TriggerDto> triggers = new ArrayList<>();
        triggers.add(onStart(""));
        IntStream.range(0, 50).forEach(i -> triggers.add(event("e" + i, "step-1")));
        assert422(id, triggers, List.of(s), FunnelService.CODE_TRIGGER_LIMIT);
    }

    @Test
    void triggers_arraySizeCapBoundaryAccepted() {
        // Exactly MAX_TRIGGERS=50 (1 on_start + 49 distinct events) is the inclusive-valid boundary — accepted.
        String id = createDraft();
        FunnelStepDto s = messageStep("step-1", "hi");
        List<TriggerDto> triggers = new ArrayList<>();
        triggers.add(onStart(""));
        IntStream.range(0, 49).forEach(i -> triggers.add(event("e" + i, "step-1")));
        assertThatCode(() -> update(id, triggers, List.of(s))).doesNotThrowAnyException();
        assertThat(funnelRepository.findById(id).orElseThrow().getTriggers()).hasSize(50);
    }

    @Test
    void triggers_perTriggerKeywordCapsEnforced() {
        String id = createDraft();
        // 51 keywords exceeds MAX_KEYWORDS=50.
        List<String> tooMany = IntStream.range(0, 51).mapToObj(i -> "k" + i).toList();
        assert422(id, List.of(onStart(""), keyword(tooMany)), List.of(),
                FunnelService.CODE_INVALID_KEYWORDS);

        // a single over-length keyword (>64 chars) is also rejected.
        assert422(id, List.of(onStart(""), keyword(List.of("x".repeat(65)))), List.of(),
                FunnelService.CODE_INVALID_KEYWORDS);
    }

    // ─── onStartTriggerValue scalar sync (TDD anchor) ────────────────────────────

    @Test
    void onStartTriggerValue_syncedFromOnStartElement() {
        String id = createDraft();

        // Non-empty on_start value → scalar equals it.
        update(id, List.of(onStart("promo")), List.of());
        assertThat(funnelRepository.findById(id).orElseThrow().getOnStartTriggerValue()).isEqualTo("promo");

        // Bare on_start ("") → scalar null (stays out of the partial-unique index, Variant A).
        update(id, List.of(onStart("")), List.of());
        assertThat(funnelRepository.findById(id).orElseThrow().getOnStartTriggerValue()).isNull();

        // No on_start element (event-only) → scalar null.
        FunnelStepDto s = messageStep("step-1", "hi");
        update(id, List.of(event("purchase", "step-1")), List.of(s));
        assertThat(funnelRepository.findById(id).orElseThrow().getOnStartTriggerValue()).isNull();
    }

    // ─── duplicate resets triggers (TDD anchor) ──────────────────────────────────

    @Test
    void duplicate_resetsTriggersToSingleOnStartAndClearsScalar() {
        // Original has a non-empty on_start + an event trigger; the clone must be reset to a single bare
        // on_start with a null scalar regardless.
        String id = createDraft();
        FunnelStepDto s = messageStep("step-1", "hi");
        update(id, List.of(onStart("promo"), event("purchase", "step-1")), List.of(s));

        String copyId = funnelService.duplicate(USER_ID, projectId, id).id();
        Funnel copy = funnelRepository.findById(copyId).orElseThrow();

        assertThat(copy.getStatus()).isEqualTo(FunnelStatus.draft);
        assertThat(copy.getTriggers()).hasSize(1);
        assertThat(copy.getTriggers().get(0).getTriggerType()).isEqualTo("on_start");
        assertThat(copy.getTriggers().get(0).getTriggerValue()).isEqualTo("");
        assertThat(copy.getOnStartTriggerValue()).isNull();
    }

    // ─── 18-funnel-canvas / Task 3: testRun enters the on_start entryStepId ──────

    @Test
    void test_run_enters_on_start_entryStepId() {
        // APPROVED deviation (Task 3): a test-run enters the SAME step a live /start would — the on_start
        // trigger's drawn-edge entryStepId — not array[0]. Array order: [first, entry]; the edge targets
        // "entry" (the SECOND element).
        seedConnectedBotAndOwner();
        String id = createDraft();
        FunnelStepDto first = messageStep("first", "array-zero");
        FunnelStepDto entry = messageStep("entry", "real-entry");
        update(id, List.of(new TriggerDto("on_start", "", null, "entry", null)),
                List.of(first, entry));

        funnelService.testRun(USER_ID, projectId, id);

        FunnelExecution created = mongoTemplate.find(
                org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria
                                .where("funnelId").is(id)),
                FunnelExecution.class).stream().findFirst().orElse(null);
        assertThat(created).isNotNull();
        assertThat(created.getCurrentStepId()).isEqualTo("entry"); // entered the edge target, not array[0]
    }

    // Seed a CONNECTED bot (with ownerChatId) + the matching ACTIVE owner subscriber so testRun's
    // resolveOwnerSubscriber resolves and insertExecution can enroll the author.
    private void seedConnectedBotAndOwner() {
        com.botfunnel.common.crypto.EncryptedValue ev = tokenEncryptor.encrypt(TEST_TOKEN);
        com.botfunnel.bot.Bot bot = new com.botfunnel.bot.Bot();
        bot.setProjectId(projectId);
        bot.setTelegramBotId(TELEGRAM_BOT_ID);
        bot.setTelegramUsername("ftt_bot");
        bot.setOwnerChatId(OWNER_CHAT_ID);
        bot.setStatus(com.botfunnel.bot.BotStatus.CONNECTED);
        bot.setEncryptedTokenIv(java.util.Base64.getEncoder().encodeToString(ev.iv()));
        bot.setEncryptedTokenCiphertext(java.util.Base64.getEncoder().encodeToString(ev.ciphertext()));
        bot.setConnectedAt(Instant.now());
        botRepository.save(bot);

        com.botfunnel.subscriber.Subscriber owner = new com.botfunnel.subscriber.Subscriber();
        owner.setProjectId(projectId);
        owner.setTelegramUserId(OWNER_CHAT_ID);
        owner.setTelegramChatId(OWNER_CHAT_ID);
        owner.setTelegramBotId(TELEGRAM_BOT_ID);
        owner.setStatus(com.botfunnel.subscriber.SubscriberStatus.ACTIVE);
        owner.setSubscribedAt(Instant.now());
        owner.setLastSeenAt(Instant.now());
        subscriberRepository.save(owner);
    }
}
