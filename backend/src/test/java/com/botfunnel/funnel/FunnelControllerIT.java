package com.botfunnel.funnel;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.bot.Bot;
import com.botfunnel.bot.BotRepository;
import com.botfunnel.bot.BotStatus;
import com.botfunnel.common.AppException;
import com.botfunnel.common.test.ConcurrencyTestUtils;
import com.botfunnel.profile.WithMockAppUser;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberRepository;
import com.botfunnel.subscriber.SubscriberStatus;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FunnelControllerIT extends AbstractIntegrationTest {

    private static final String USER_ID = "funnel-it-user";
    private static final String OTHER_USER_ID = "funnel-it-other";

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired FunnelRepository funnelRepository;
    @Autowired BotRepository botRepository;
    @Autowired SubscriberRepository subscriberRepository;
    @Autowired FunnelService funnelService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String projectId;

    @BeforeEach
    void cleanAndSeed() {
        userRepository.deleteAll();
        projectRepository.deleteAll();
        funnelRepository.deleteAll();
        botRepository.deleteAll();
        subscriberRepository.deleteAll();

        seedUser(USER_ID, "owner@test.com");
        projectId = saveProject(USER_ID, null).getId();
    }

    // ─── create ──────────────────────────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void createReturns201Draft() throws Exception {
        mockMvc.perform(post(url()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "t"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("t"))
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.steps.length()").value(0))
                .andExpect(jsonPath("$.deepLink").value(org.hamcrest.Matchers.nullValue()));
    }

    // ─── list ────────────────────────────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void listFiltersByStatus() throws Exception {
        seedFunnel("Draft one", FunnelStatus.draft, "", List.of());
        seedFunnel("Active one", FunnelStatus.active, "promo", List.of(messageStep("hi")));

        mockMvc.perform(get(url() + "?status=active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Active one"))
                .andExpect(jsonPath("$[0].status").value("active"))
                // FunnelSummaryResponse carries NO steps array (lightweight list view).
                .andExpect(jsonPath("$[0].steps").doesNotExist())
                .andExpect(jsonPath("$[0].stepCount").value(1));
    }

    // ─── get ─────────────────────────────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void getReturnsFullFunnelWithSteps() throws Exception {
        seedConnectedBot("my_bot");
        Funnel f = seedFunnel("Active", FunnelStatus.active, "promo",
                List.of(messageStep("hello"), delayStep(5, "MIN")));

        mockMvc.perform(get(url() + "/" + f.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(2))
                .andExpect(jsonPath("$.steps[0].stepType").value("MESSAGE"))
                .andExpect(jsonPath("$.steps[0].blocks[0].type").value("TEXT"))
                .andExpect(jsonPath("$.steps[0].blocks[0].text").value("hello"))
                .andExpect(jsonPath("$.steps[1].stepType").value("DELAY"))
                // deepLink present for an active funnel (survives page reload, not only activate resp).
                .andExpect(jsonPath("$.deepLink").value("t.me/my_bot?start=promo"));
    }

    // ─── update ──────────────────────────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void updateRewritesStepOrderByIndex() throws Exception {
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());

        Map<String, Object> body = Map.of(
                "name", "Draft",
                "triggerValue", "",
                "steps", List.of(
                        messageStepMap(textBlock("first")),
                        stepMap("DELAY", Map.of("delayValue", 2, "delayUnit", "HOUR")),
                        stepMap("ADD_TAG", Map.of("tagSlug", "vip"))));

        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(3));

        // Server rewrites FunnelStep.order from the array index, ignoring any client-supplied order.
        Funnel reloaded = funnelRepository.findById(f.getId()).orElseThrow();
        List<FunnelStep> steps = reloaded.getSteps();
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0).getOrder()).isZero();
        assertThat(steps.get(1).getOrder()).isEqualTo(1);
        assertThat(steps.get(2).getOrder()).isEqualTo(2);
        assertThat(steps.get(0).getStepType()).isEqualTo(StepType.MESSAGE);
        assertThat(steps.get(2).getStepType()).isEqualTo(StepType.ADD_TAG);
    }

    // ─── 16-persistent-keyboard round-trip (Task 1) ───────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void keyboardStepsRoundTripThroughUpdateAndGet() throws Exception {
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());

        Map<String, Object> body = Map.of(
                "name", "Draft",
                "triggerValue", "",
                "steps", List.of(
                        setKeyboardStepMap("Меню {user.first_name}", true, false,
                                keyboardRow("Згенерувати бонус", "Профіль"),
                                keyboardRow("Допомога")),
                        clearKeyboardStepMap("Меню сховано")));

        // PUT → all keyboard fields survive in the response.
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(2))
                .andExpect(jsonPath("$.steps[0].stepType").value("SET_KEYBOARD"))
                .andExpect(jsonPath("$.steps[0].keyboardText").value("Меню {user.first_name}"))
                .andExpect(jsonPath("$.steps[0].isPersistent").value(true))
                .andExpect(jsonPath("$.steps[0].oneTimeKeyboard").value(false))
                .andExpect(jsonPath("$.steps[0].keyboardRows.length()").value(2))
                .andExpect(jsonPath("$.steps[0].keyboardRows[0].buttons[0].text").value("Згенерувати бонус"))
                .andExpect(jsonPath("$.steps[0].keyboardRows[0].buttons[1].text").value("Профіль"))
                .andExpect(jsonPath("$.steps[0].keyboardRows[1].buttons[0].text").value("Допомога"))
                .andExpect(jsonPath("$.steps[1].stepType").value("CLEAR_KEYBOARD"))
                .andExpect(jsonPath("$.steps[1].keyboardText").value("Меню сховано"))
                .andExpect(jsonPath("$.steps[1].keyboardRows").value(org.hamcrest.Matchers.nullValue()));

        // GET → same fields survive the full HTTP round-trip (toStepDto wiring, both directions).
        mockMvc.perform(get(url() + "/" + f.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].keyboardText").value("Меню {user.first_name}"))
                .andExpect(jsonPath("$.steps[0].keyboardRows[0].buttons[1].text").value("Профіль"))
                .andExpect(jsonPath("$.steps[0].isPersistent").value(true))
                .andExpect(jsonPath("$.steps[1].keyboardText").value("Меню сховано"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void invalidKeyboardStepReturns422() throws Exception {
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());

        // Duplicate button texts in one keyboard → 422 funnel_step_invalid.
        Map<String, Object> body = Map.of(
                "name", "Draft",
                "triggerValue", "",
                "steps", List.of(setKeyboardStepMap("Меню", true, false,
                        keyboardRow("Бонус"), keyboardRow("Бонус"))));

        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    // ─── delete ──────────────────────────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void deleteReturns204AndCancelsActiveExecutions() throws Exception {
        Funnel f = seedFunnel("Active", FunnelStatus.active, "promo", List.of(messageStep("hi")));
        String runningId = seedExecution(f.getId(), ExecutionStatus.running);
        String waitingId = seedExecution(f.getId(), ExecutionStatus.waiting);
        // A parked composer execution (waiting_for_reply) must be cancelled by delete too (audit-fix F5).
        String waitingForReplyId = seedExecution(f.getId(), ExecutionStatus.waiting_for_reply);
        String completedId = seedExecution(f.getId(), ExecutionStatus.completed);

        mockMvc.perform(delete(url() + "/" + f.getId()).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(funnelRepository.findById(f.getId())).isEmpty();
        assertThat(execStatus(runningId)).isEqualTo(ExecutionStatus.cancelled);
        assertThat(execStatus(waitingId)).isEqualTo(ExecutionStatus.cancelled);
        assertThat(execStatus(waitingForReplyId)).isEqualTo(ExecutionStatus.cancelled);
        // A terminal execution is untouched by the cancel sweep.
        assertThat(execStatus(completedId)).isEqualTo(ExecutionStatus.completed);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void deleteDraftWithoutExecutionsReturns204() throws Exception {
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        mockMvc.perform(delete(url() + "/" + f.getId()).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(funnelRepository.findById(f.getId())).isEmpty();
    }

    // ─── duplicate ───────────────────────────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void duplicateReturns201DraftWithResetTrigger() throws Exception {
        // An ACTIVE funnel with a non-default trigger (on_start, "promo"); the copy must be born draft
        // with the trigger reset to (on_start, "") regardless of the original's trigger.
        Funnel f = seedFunnel("Promo", FunnelStatus.active, "promo", List.of(messageStep("hi")));

        mockMvc.perform(post(url() + "/" + f.getId() + "/duplicate").with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.triggerType").value("on_start"))
                .andExpect(jsonPath("$.triggerValue").value(""))
                .andExpect(jsonPath("$.name").value("Promo (копія)"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void duplicateCopiesGraphVerbatim() throws Exception {
        // Build a two-step graph with explicit ids + edges: a composer MESSAGE (with a callback
        // targetStepId on its last-block keyboard + a timeout edge) and its MESSAGE target. The copy must
        // preserve step ids and every edge verbatim, copy keywords/allowReEnter/description, leave original.
        FunnelStep target = textStep("target-1", "branch");

        FunnelStep menu = composerWithButtons("menu-1", "menu", callbackButton("Go", "target-1"));
        menu.setNext("target-1");
        menu.setTimeoutValue(2);
        menu.setTimeoutUnit("HOUR");
        menu.setTimeoutTargetStepId("target-1");

        Funnel f = seedFunnel("Graph", FunnelStatus.draft, "",
                new ArrayList<>(List.of(menu, target)));
        f.setDescription("the original description");
        f.setAllowReEnter(true);
        f.setKeywords(List.of("alpha", "beta"));
        funnelRepository.save(f);

        String body = mockMvc.perform(post(url() + "/" + f.getId() + "/duplicate").with(csrf()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String copyId = objectMapper.readTree(body).get("id").asText();

        // The copy is a distinct document.
        assertThat(copyId).isNotEqualTo(f.getId());

        Funnel copy = funnelRepository.findById(copyId).orElseThrow();
        assertThat(copy.getDescription()).isEqualTo("the original description");
        assertThat(copy.isAllowReEnter()).isTrue();
        assertThat(copy.getKeywords()).containsExactly("alpha", "beta");

        List<FunnelStep> steps = copy.getSteps();
        assertThat(steps).hasSize(2);
        FunnelStep copiedMenu = steps.get(0);
        FunnelStep copiedTarget = steps.get(1);
        assertThat(copiedMenu.getId()).isEqualTo("menu-1");
        assertThat(copiedMenu.getNext()).isEqualTo("target-1");
        assertThat(copiedMenu.getTimeoutTargetStepId()).isEqualTo("target-1");
        assertThat(copiedMenu.getButtons()).hasSize(1);
        assertThat(copiedMenu.getButtons().get(0).targetStepId()).isEqualTo("target-1");
        assertThat(copiedTarget.getId()).isEqualTo("target-1");

        // Original is unchanged.
        Funnel original = funnelRepository.findById(f.getId()).orElseThrow();
        assertThat(original.getName()).isEqualTo("Graph");
        assertThat(original.getSteps()).hasSize(2);
        assertThat(original.getSteps().get(0).getId()).isEqualTo("menu-1");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void duplicateNameAt128NotTruncated() throws Exception {
        // Suffix " (копія)" is 8 UTF-16 chars; choose a base so "<base> (копія)" == exactly 128 → no
        // truncation, full name persists.
        String base = "x".repeat(128 - " (копія)".length());
        Funnel f = seedFunnel(base, FunnelStatus.draft, "", List.of());
        String expected = base + " (копія)";
        assertThat(expected.length()).isEqualTo(128);

        mockMvc.perform(post(url() + "/" + f.getId() + "/duplicate").with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(expected));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void duplicateNameAt129TruncatedStill201() throws Exception {
        // "<base> (копія)" == 129 → must truncate to 128 (still 201, not 400 from @Size).
        String base = "x".repeat(129 - " (копія)".length());
        Funnel f = seedFunnel(base, FunnelStatus.draft, "", List.of());

        String body = mockMvc.perform(post(url() + "/" + f.getId() + "/duplicate").with(csrf()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String name = objectMapper.readTree(body).get("name").asText();
        assertThat(name.length()).isEqualTo(128);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void duplicateOfDraftOrEmptyFunnelResetsTrigger() throws Exception {
        // Empty (no steps) draft funnel with a non-default trigger value → copy stays draft with the
        // trigger reset; copyOf over an empty list must not NPE.
        Funnel f = seedFunnel("Empty", FunnelStatus.draft, "leftover", List.of());

        mockMvc.perform(post(url() + "/" + f.getId() + "/duplicate").with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.triggerType").value("on_start"))
                .andExpect(jsonPath("$.triggerValue").value(""))
                .andExpect(jsonPath("$.steps.length()").value(0));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void duplicateForeignFunnelReturns404() throws Exception {
        seedUser(OTHER_USER_ID, "other@test.com");
        String foreignProject = saveProject(OTHER_USER_ID, null).getId();
        Funnel foreign = new Funnel();
        foreign.setProjectId(foreignProject);
        foreign.setName("Foreign");
        foreign.setStatus(FunnelStatus.draft);
        foreign.setTriggerType(FunnelService.TRIGGER_ON_START);
        foreign.setTriggerValue("");
        foreign.setSteps(new ArrayList<>());
        foreign.setCreatedAt(Instant.now());
        foreign.setUpdatedAt(Instant.now());
        String foreignId = funnelRepository.save(foreign).getId();

        mockMvc.perform(post(url() + "/" + foreignId + "/duplicate").with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ─── stop-all ─────────────────────────────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void stopAllCancelsActiveAndSetsStepRunDone() throws Exception {
        Funnel f = seedFunnel("Active", FunnelStatus.active, "promo", List.of(messageStep("hi")));
        String runningId = seedExecution(f.getId(), ExecutionStatus.running);
        String waitingId = seedExecution(f.getId(), ExecutionStatus.waiting);
        String waitingForReplyId = seedExecution(f.getId(), ExecutionStatus.waiting_for_reply);
        String completedId = seedExecution(f.getId(), ExecutionStatus.completed);
        String failedId = seedExecution(f.getId(), ExecutionStatus.failed);
        String cancelledId = seedExecution(f.getId(), ExecutionStatus.cancelled);

        mockMvc.perform(post(url() + "/" + f.getId() + "/executions/stop").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled").value(3));

        // All three in-flight rows → cancelled AND stepRunStatus=done (claim-CAS contract, Decision 7).
        for (String id : List.of(runningId, waitingId, waitingForReplyId)) {
            assertThat(execStatus(id)).isEqualTo(ExecutionStatus.cancelled);
            assertThat(stepRunStatus(id)).isEqualTo(StepRunStatus.done);
        }
        // Terminal rows untouched.
        assertThat(execStatus(completedId)).isEqualTo(ExecutionStatus.completed);
        assertThat(execStatus(failedId)).isEqualTo(ExecutionStatus.failed);
        assertThat(execStatus(cancelledId)).isEqualTo(ExecutionStatus.cancelled);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void stopAllWithNoActiveReturnsZero() throws Exception {
        Funnel f = seedFunnel("Active", FunnelStatus.active, "promo", List.of(messageStep("hi")));
        seedExecution(f.getId(), ExecutionStatus.completed);

        mockMvc.perform(post(url() + "/" + f.getId() + "/executions/stop").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled").value(0));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void stopAllScopedByProjectAndFunnel() throws Exception {
        Funnel f = seedFunnel("Target", FunnelStatus.active, "promo", List.of(messageStep("hi")));
        String mineRunning = seedExecution(f.getId(), ExecutionStatus.running);

        // Sibling execution under ANOTHER funnel in the SAME project — must NOT be cancelled.
        Funnel sibling = seedFunnel("Sibling", FunnelStatus.active, "other", List.of(messageStep("x")));
        String siblingRunning = seedExecution(sibling.getId(), ExecutionStatus.running);

        // Execution under ANOTHER project but the SAME funnelId — must NOT be cancelled (tenant scope).
        String foreignProject = "ffffffffffffffffffffffff";
        String foreignSameFunnel = seedExecutionFor(foreignProject, f.getId(), ExecutionStatus.running);

        mockMvc.perform(post(url() + "/" + f.getId() + "/executions/stop").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled").value(1));

        assertThat(execStatus(mineRunning)).isEqualTo(ExecutionStatus.cancelled);
        assertThat(execStatus(siblingRunning)).isEqualTo(ExecutionStatus.running);
        assertThat(execStatus(foreignSameFunnel)).isEqualTo(ExecutionStatus.running);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void stopAllForeignFunnelReturns404() throws Exception {
        seedUser(OTHER_USER_ID, "other@test.com");
        String foreignProject = saveProject(OTHER_USER_ID, null).getId();
        Funnel foreign = new Funnel();
        foreign.setProjectId(foreignProject);
        foreign.setName("Foreign");
        foreign.setStatus(FunnelStatus.draft);
        foreign.setTriggerType(FunnelService.TRIGGER_ON_START);
        foreign.setTriggerValue("");
        foreign.setSteps(new ArrayList<>());
        foreign.setCreatedAt(Instant.now());
        foreign.setUpdatedAt(Instant.now());
        String foreignId = funnelRepository.save(foreign).getId();

        mockMvc.perform(post(url() + "/" + foreignId + "/executions/stop").with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ─── activate ──────────────────────────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void activateValidatesAtLeastOneStep() throws Exception {
        Funnel f = seedFunnel("Empty", FunnelStatus.draft, "promo", List.of());
        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_no_steps"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void activateValidatesRequiredFields() throws Exception {
        // Seed (directly) a draft whose MESSAGE composer step has an empty blocks list — activate must
        // re-validate per-type fields and reject with 422 (defense-in-depth beyond the update path).
        FunnelStep broken = new FunnelStep();
        broken.setStepType(StepType.MESSAGE);
        broken.setOrder(0);
        Funnel f = seedFunnel("Broken", FunnelStatus.draft, "promo",
                new ArrayList<>(List.of(broken)));

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void activateSucceedsAndReturnsDeepLink() throws Exception {
        seedConnectedBot("promo_bot");
        Funnel f = seedFunnel("Ready", FunnelStatus.draft, "go", List.of(messageStep("hi")));

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.deepLink").value("t.me/promo_bot?start=go"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void pauseTransitionsActiveToPaused() throws Exception {
        Funnel f = seedFunnel("Active", FunnelStatus.active, "promo", List.of(messageStep("hi")));
        mockMvc.perform(post(url() + "/" + f.getId() + "/pause").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("paused"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void activateTriggerConflictReturns422() throws Exception {
        // An already-active funnel owns (on_start, "promo"); activating a second draft with the same
        // trigger trips the SERVICE pre-check → 422 funnel_trigger_conflict (first line of Decision 8).
        seedFunnel("First", FunnelStatus.active, "promo", List.of(messageStep("a")));
        Funnel second = seedFunnel("Second", FunnelStatus.draft, "promo", List.of(messageStep("b")));

        mockMvc.perform(post(url() + "/" + second.getId() + "/activate").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_trigger_conflict"));
    }

    @Test
    void activateTriggerConflictRaceMapsIndexDuplicateKeyTo422() {
        // Both funnels start in draft, so two simultaneous activate() calls BOTH pass the service
        // pre-check (no active funnel exists yet) and race to save status=active. The partial-unique
        // index lets exactly one win; the loser's DuplicateKeyException MUST be mapped to 422
        // funnel_trigger_conflict (NOT surface as a 500). Driven at the service layer to isolate the index.
        Funnel a = seedFunnel("A", FunnelStatus.draft, "race", List.of(messageStep("a")));
        Funnel b = seedFunnel("B", FunnelStatus.draft, "race", List.of(messageStep("b")));

        java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger();
        List<Object> outcomes = ConcurrencyTestUtils.parallelInvoke(2, () ->
                activateCatching(idx.getAndIncrement() == 0 ? a.getId() : b.getId()));

        long activated = outcomes.stream().filter(o -> "active".equals(o)).count();
        long conflicts = outcomes.stream()
                .filter(o -> FunnelService.CODE_TRIGGER_CONFLICT.equals(o)).count();
        assertThat(activated).as("exactly one funnel becomes active").isEqualTo(1L);
        assertThat(conflicts).as("the loser is mapped to 422 funnel_trigger_conflict, not 500")
                .isEqualTo(1L);
        assertThat(funnelRepository.findByProjectIdAndTriggerTypeAndTriggerValueAndStatus(
                projectId, FunnelService.TRIGGER_ON_START, "race", FunnelStatus.active)).isPresent();
    }

    // ─── test-run ──────────────────────────────────────────────────────────────

    private static final Long OWNER_CHAT_ID = 555_000L;
    private static final Long BOT_TG_ID = 9000L;

    @Test
    @WithMockAppUser(userId = USER_ID)
    void testRunOwnerChatIdNullReturns422() throws Exception {
        // Bot connected but ownerChatId not captured → 422 funnel_owner_not_linked, NOT 500 (HTTP-shape).
        seedConnectedBot("my_bot"); // no ownerChatId
        Funnel f = seedFunnel("Ready", FunnelStatus.draft, "go", List.of(messageStep("hi")));

        mockMvc.perform(post(url() + "/" + f.getId() + "/test-run").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_owner_not_linked"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void testRunSubscriberMissingReturns422() throws Exception {
        // ownerChatId set but no subscriber doc for it → 422 funnel_owner_not_linked.
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        Funnel f = seedFunnel("Ready", FunnelStatus.draft, "go", List.of(messageStep("hi")));

        mockMvc.perform(post(url() + "/" + f.getId() + "/test-run").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_owner_not_linked"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void testRunSubscriberNotActiveReturns422() throws Exception {
        // Subscriber exists for ownerChatId but is BLOCKED (not ACTIVE) → 422 funnel_owner_not_linked.
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.BLOCKED);
        Funnel f = seedFunnel("Ready", FunnelStatus.draft, "go", List.of(messageStep("hi")));

        mockMvc.perform(post(url() + "/" + f.getId() + "/test-run").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_owner_not_linked"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void testRunEmptyFunnelReturns422() throws Exception {
        // Linked owner but empty funnel → same 422 funnel_no_steps as activate (HTTP-shape, Decision 4).
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.ACTIVE);
        Funnel f = seedFunnel("Empty", FunnelStatus.draft, "go", List.of());

        mockMvc.perform(post(url() + "/" + f.getId() + "/test-run").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_no_steps"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void testRunInvalidStepsReturns422() throws Exception {
        // Linked owner but a MESSAGE step with no blocks → same 422 funnel_step_invalid.
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.ACTIVE);
        FunnelStep broken = new FunnelStep();
        broken.setStepType(StepType.MESSAGE);
        broken.setOrder(0);
        Funnel f = seedFunnel("Broken", FunnelStatus.draft, "go", new ArrayList<>(List.of(broken)));

        mockMvc.perform(post(url() + "/" + f.getId() + "/test-run").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void testRunDraftFunnelReturns2xx() throws Exception {
        // A DRAFT (valid, non-empty) funnel is test-runnable — the status-gate does not block (Decision 2).
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        Subscriber owner = seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.ACTIVE);
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "go", List.of(messageStep("hi")));

        mockMvc.perform(post(url() + "/" + f.getId() + "/test-run").with(csrf()))
                .andExpect(status().is2xxSuccessful());

        List<FunnelExecution> execs = inFlightExecutions(f.getId(), owner.getId());
        assertThat(execs).hasSize(1);
        FunnelExecution e = execs.get(0);
        assertThat(e.getStatus()).isEqualTo(ExecutionStatus.running);
        assertThat(e.getEnrollDepth()).isZero();
        assertThat(e.getCurrentStepIndex()).isZero();
        assertThat(e.getTelegramBotId()).isEqualTo(BOT_TG_ID);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void testRunRestartCancelsPrevious() throws Exception {
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        Subscriber owner = seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.ACTIVE);
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "go", List.of(messageStep("hi")));

        mockMvc.perform(post(url() + "/" + f.getId() + "/test-run").with(csrf()))
                .andExpect(status().is2xxSuccessful());
        String firstExecId = inFlightExecutions(f.getId(), owner.getId()).get(0).getId();

        mockMvc.perform(post(url() + "/" + f.getId() + "/test-run").with(csrf()))
                .andExpect(status().is2xxSuccessful());

        assertThat(execStatus(firstExecId)).isEqualTo(ExecutionStatus.cancelled);
        List<FunnelExecution> inFlight = inFlightExecutions(f.getId(), owner.getId());
        assertThat(inFlight).hasSize(1);
        assertThat(inFlight.get(0).getId()).isNotEqualTo(firstExecId);
        assertThat(inFlight.get(0).getStatus()).isEqualTo(ExecutionStatus.running);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void testRunForeignFunnelReturns404() throws Exception {
        // Anti-IDOR: a foreign funnel under MY project path → uniform 404 (requireFunnel FIRST).
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.ACTIVE);
        String foreignId = seedForeignFunnel();

        mockMvc.perform(post(url() + "/" + foreignId + "/test-run").with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ─── preview (multiblock) ──────────────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void previewParseModeNullNoEscape() throws Exception {
        // parseMode=null → substituted value with special chars is NOT escaped (plain text, safest mode).
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        Subscriber owner = seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.ACTIVE);
        owner.setFirstName("a & b < c > d");
        subscriberRepository.save(owner);
        String stepId = "s1";
        Funnel f = seedFunnel("F", FunnelStatus.draft, "go",
                new ArrayList<>(List.of(textStep(stepId, "saved"))));

        mockMvc.perform(post(previewUrl(f, stepId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewBody("MESSAGE", textBlock("Hi {user.first_name}!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderedBlocks.length()").value(1))
                .andExpect(jsonPath("$.renderedBlocks[0].type").value("TEXT"))
                .andExpect(jsonPath("$.renderedBlocks[0].text").value("Hi a & b < c > d!"))
                .andExpect(jsonPath("$.sampleData").value(false))
                .andExpect(jsonPath("$.kind").value("message"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void previewMediaCaptionRendersAsMessage() throws Exception {
        // An IMAGE block: its on-the-fly caption renders with substitution; mediaUrl passes through
        // verbatim (never dereferenced).
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        Subscriber owner = seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.ACTIVE);
        owner.setFirstName("Фотоклієнт");
        subscriberRepository.save(owner);
        String stepId = "img-1";
        FunnelStep image = composer(stepId, imageBlock("https://example.com/x.png", "saved caption"));
        Funnel f = seedFunnel("F", FunnelStatus.draft, "go", new ArrayList<>(List.of(image)));

        mockMvc.perform(post(previewUrl(f, stepId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewBody("MESSAGE",
                                imageBlockMap("https://example.com/x.png", "Привіт, {user.first_name}!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderedBlocks[0].type").value("IMAGE"))
                .andExpect(jsonPath("$.renderedBlocks[0].mediaUrl").value("https://example.com/x.png"))
                .andExpect(jsonPath("$.renderedBlocks[0].caption").value("Привіт, Фотоклієнт!"))
                .andExpect(jsonPath("$.kind").value("message"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void previewMultiblockReturnsRenderedArray() throws Exception {
        // A multiblock composer (TEXT + IMAGE + ALBUM) → renderedBlocks array preserves order + types,
        // renders text/caption with substitution, passes media verbatim (no dereference).
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        Subscriber owner = seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.ACTIVE);
        owner.setFirstName("Ірина");
        subscriberRepository.save(owner);
        String stepId = "s1";
        Funnel f = seedFunnel("F", FunnelStatus.draft, "go",
                new ArrayList<>(List.of(textStep(stepId, "saved"))));

        List<Map<String, Object>> blocks = List.of(
                textBlock("Привіт {user.first_name}"),
                imageBlockMap("https://example.com/a.jpg", "img {user.first_name}"),
                albumBlock(
                        mediaItem("https://example.com/1.jpg", "first {user.first_name}"),
                        mediaItem("https://example.com/2.jpg", null)));

        mockMvc.perform(post(previewUrl(f, stepId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewBodyBlocks("MESSAGE", blocks)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("message"))
                .andExpect(jsonPath("$.renderedBlocks.length()").value(3))
                .andExpect(jsonPath("$.renderedBlocks[0].type").value("TEXT"))
                .andExpect(jsonPath("$.renderedBlocks[0].text").value("Привіт Ірина"))
                .andExpect(jsonPath("$.renderedBlocks[1].type").value("IMAGE"))
                .andExpect(jsonPath("$.renderedBlocks[1].mediaUrl").value("https://example.com/a.jpg"))
                .andExpect(jsonPath("$.renderedBlocks[1].caption").value("img Ірина"))
                .andExpect(jsonPath("$.renderedBlocks[2].type").value("ALBUM"))
                .andExpect(jsonPath("$.renderedBlocks[2].items.length()").value(2))
                .andExpect(jsonPath("$.renderedBlocks[2].items[0].caption").value("first Ірина"))
                .andExpect(jsonPath("$.renderedBlocks[2].items[0].mediaUrl").value("https://example.com/1.jpg"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void previewEscapesHostilePayload() throws Exception {
        // XSS-negative (OWASP A03, Decision 8): a hostile <script> payload in a substituted value with
        // parseMode=HTML must be escaped (&lt;script&gt;); no unescaped markup leaks. Author markup <b>
        // in the template stays untouched (only substituted values are escaped).
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        Subscriber owner = seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.ACTIVE);
        owner.setFirstName("<script>alert(1)</script>");
        subscriberRepository.save(owner);
        String stepId = "s1";
        Funnel f = seedFunnel("F", FunnelStatus.draft, "go",
                new ArrayList<>(List.of(textStep(stepId, "saved"))));

        Map<String, Object> block = textBlock("<b>{user.first_name}</b>");
        block.put("parseMode", "HTML");

        String body = mockMvc.perform(post(previewUrl(f, stepId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewBody("MESSAGE", block)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderedBlocks[0].text")
                        .value("<b>&lt;script&gt;alert(1)&lt;/script&gt;</b>"))
                .andReturn().getResponse().getContentAsString();
        // Defense-in-depth: the raw response must NOT contain the unescaped hostile tag.
        assertThat(body).doesNotContain("<script>");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void previewMarkdownV2EscapesFullSet() throws Exception {
        // parseMode=MarkdownV2 → every special char AND the backslash itself gets a preceding backslash.
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        Subscriber owner = seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.ACTIVE);
        String specials = "_*[]()~`>#+-=|{}.!\\";
        owner.setFirstName(specials);
        subscriberRepository.save(owner);
        String stepId = "s1";
        Funnel f = seedFunnel("F", FunnelStatus.draft, "go",
                new ArrayList<>(List.of(textStep(stepId, "saved"))));

        StringBuilder expected = new StringBuilder();
        for (char c : specials.toCharArray()) {
            expected.append('\\').append(c);
        }

        Map<String, Object> block = textBlock("{user.first_name}");
        block.put("parseMode", "MarkdownV2");

        mockMvc.perform(post(previewUrl(f, stepId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewBody("MESSAGE", block)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderedBlocks[0].text").value(expected.toString()));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void previewUnlinkedBotUsesStubSubscriber() throws Exception {
        // No linked owner → render on sample stub (Іван/Петренко/ivan), sampleData=true, no NPE/500.
        String stepId = "s1";
        Funnel f = seedFunnel("F", FunnelStatus.draft, "go",
                new ArrayList<>(List.of(textStep(stepId, "saved"))));

        mockMvc.perform(post(previewUrl(f, stepId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewBody("MESSAGE",
                                textBlock("{user.first_name} {user.last_name} @{user.username}"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderedBlocks[0].text").value("Іван Петренко @ivan"))
                .andExpect(jsonPath("$.sampleData").value(true))
                .andExpect(jsonPath("$.kind").value("message"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void previewNonMessageStepReturnsEmptyArray() throws Exception {
        // A non-message step (DELAY) → kind=non_message, renderedBlocks=[] — placeholder, not 500.
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.ACTIVE);
        String stepId = "d1";
        FunnelStep delay = new FunnelStep();
        delay.setStepType(StepType.DELAY);
        delay.setId(stepId);
        delay.setDelayValue(5);
        delay.setDelayUnit("MIN");
        Funnel f = seedFunnel("F", FunnelStatus.draft, "go", new ArrayList<>(List.of(delay)));

        mockMvc.perform(post(previewUrl(f, stepId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("stepType", "DELAY"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("non_message"))
                .andExpect(jsonPath("$.renderedBlocks.length()").value(0));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void previewSubscribeToFunnelStepReturnsNonMessagePlaceholder() throws Exception {
        // SUBSCRIBE_TO_FUNNEL is a non-message step → kind=non_message, renderedBlocks=[].
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.ACTIVE);
        String stepId = "s1";
        FunnelStep subscribe = new FunnelStep();
        subscribe.setStepType(StepType.SUBSCRIBE_TO_FUNNEL);
        subscribe.setId(stepId);
        subscribe.setTargetFunnelId("target-funnel");
        Funnel f = seedFunnel("F", FunnelStatus.draft, "go", new ArrayList<>(List.of(subscribe)));

        mockMvc.perform(post(previewUrl(f, stepId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("stepType", "SUBSCRIBE_TO_FUNNEL"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("non_message"))
                .andExpect(jsonPath("$.renderedBlocks.length()").value(0));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void previewSetKeyboardReturnsKeyboardKindWithRenderedTextAndRawRows() throws Exception {
        // Saved SET_KEYBOARD step: the request's keyboardText renders through VariableTemplateRenderer
        // (variable substituted), kind="keyboard", exactly one TEXT block, and keyboardRows echoes the
        // request's raw label rows verbatim (row/button structure preserved).
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        Subscriber owner = seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.ACTIVE);
        owner.setFirstName("Олена");
        subscriberRepository.save(owner);
        String stepId = "kb1";
        Funnel f = seedFunnel("F", FunnelStatus.draft, "go",
                new ArrayList<>(List.of(setKeyboardStep(stepId, "saved"))));

        mockMvc.perform(post(previewUrl(f, stepId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewKeyboardBody("SET_KEYBOARD", "Привіт {user.first_name}!", null,
                                keyboardRow("Меню", "Бонус"), keyboardRow("Допомога"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("keyboard"))
                .andExpect(jsonPath("$.renderedBlocks.length()").value(1))
                .andExpect(jsonPath("$.renderedBlocks[0].type").value("TEXT"))
                .andExpect(jsonPath("$.renderedBlocks[0].text").value("Привіт Олена!"))
                .andExpect(jsonPath("$.sampleData").value(false))
                .andExpect(jsonPath("$.keyboardRows.length()").value(2))
                .andExpect(jsonPath("$.keyboardRows[0].length()").value(2))
                .andExpect(jsonPath("$.keyboardRows[0][0]").value("Меню"))
                .andExpect(jsonPath("$.keyboardRows[0][1]").value("Бонус"))
                .andExpect(jsonPath("$.keyboardRows[1].length()").value(1))
                .andExpect(jsonPath("$.keyboardRows[1][0]").value("Допомога"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void previewSetKeyboardLabelsNotVariableRendered() throws Exception {
        // A button label containing {user.first_name} comes back VERBATIM — labels are the keyword link,
        // never passed through the variable renderer (rendering would break keyword matching).
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        Subscriber owner = seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.ACTIVE);
        owner.setFirstName("Олена");
        subscriberRepository.save(owner);
        String stepId = "kb1";
        Funnel f = seedFunnel("F", FunnelStatus.draft, "go",
                new ArrayList<>(List.of(setKeyboardStep(stepId, "saved"))));

        mockMvc.perform(post(previewUrl(f, stepId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewKeyboardBody("SET_KEYBOARD", "Текст", null,
                                keyboardRow("Привіт {user.first_name}"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("keyboard"))
                .andExpect(jsonPath("$.keyboardRows[0][0]").value("Привіт {user.first_name}"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void previewSetKeyboardClampsOversizedRows() throws Exception {
        // Preview is non-validating: an oversized payload (>10 rows, a row with >4 buttons, a label >64)
        // is silently clamped (10 rows × 4 buttons, label truncated to 64) — never 422, never echoed unclamped.
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.ACTIVE);
        String stepId = "kb1";
        Funnel f = seedFunnel("F", FunnelStatus.draft, "go",
                new ArrayList<>(List.of(setKeyboardStep(stepId, "saved"))));

        // 12 rows; the FIRST row has 6 buttons (the first of which is a 70-char label).
        String longLabel = "x".repeat(70);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(keyboardRow(longLabel, "b", "c", "d", "e", "f"));
        for (int i = 1; i < 12; i++) {
            rows.add(keyboardRow("r" + i));
        }

        mockMvc.perform(post(previewUrl(f, stepId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewKeyboardBodyRows("SET_KEYBOARD", "Текст", null, rows)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("keyboard"))
                .andExpect(jsonPath("$.keyboardRows.length()").value(10))
                .andExpect(jsonPath("$.keyboardRows[0].length()").value(4))
                .andExpect(jsonPath("$.keyboardRows[0][0]").value("x".repeat(64)));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void previewClearKeyboardReturnsNullRows() throws Exception {
        // Saved CLEAR_KEYBOARD step: kind="keyboard", one rendered TEXT block (variables substituted),
        // keyboardRows is null (no rows on a clear).
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        Subscriber owner = seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.ACTIVE);
        owner.setFirstName("Олена");
        subscriberRepository.save(owner);
        String stepId = "clr1";
        Funnel f = seedFunnel("F", FunnelStatus.draft, "go",
                new ArrayList<>(List.of(clearKeyboardStep(stepId, "saved"))));

        mockMvc.perform(post(previewUrl(f, stepId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewKeyboardBody("CLEAR_KEYBOARD", "Меню сховано, {user.first_name}",
                                null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("keyboard"))
                .andExpect(jsonPath("$.renderedBlocks.length()").value(1))
                .andExpect(jsonPath("$.renderedBlocks[0].type").value("TEXT"))
                .andExpect(jsonPath("$.renderedBlocks[0].text").value("Меню сховано, Олена"))
                .andExpect(jsonPath("$.keyboardRows").doesNotExist());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void previewSetKeyboardEscapesTextPerParseMode() throws Exception {
        // Security-relevant AC: keyboard TEXT is rendered through VariableTemplateRenderer with escaping per
        // keyboardParseMode — a hostile <script> substituted value with parseMode=HTML must be escaped
        // (&lt;script&gt;), exactly like the message branch (previewEscapesHostilePayload). Guards against a
        // regression where the keyboard render path silently drops the parseMode argument.
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        Subscriber owner = seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.ACTIVE);
        owner.setFirstName("<script>alert(1)</script>");
        subscriberRepository.save(owner);
        String stepId = "kb1";
        Funnel f = seedFunnel("F", FunnelStatus.draft, "go",
                new ArrayList<>(List.of(setKeyboardStep(stepId, "saved"))));

        String body = mockMvc.perform(post(previewUrl(f, stepId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewKeyboardBody("SET_KEYBOARD", "<b>{user.first_name}</b>", "HTML",
                                keyboardRow("Меню"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("keyboard"))
                .andExpect(jsonPath("$.renderedBlocks[0].type").value("TEXT"))
                .andExpect(jsonPath("$.renderedBlocks[0].parseMode").value("HTML"))
                .andExpect(jsonPath("$.renderedBlocks[0].text")
                        .value("<b>&lt;script&gt;alert(1)&lt;/script&gt;</b>"))
                .andReturn().getResponse().getContentAsString();
        // Defense-in-depth: the raw response must NOT contain the unescaped hostile tag.
        assertThat(body).doesNotContain("<script>");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void previewSetKeyboardBlankTextStillReturnsOneTextBlockNullRows() throws Exception {
        // Preview is non-validating: a SET_KEYBOARD request with null keyboardText AND no keyboardRows still
        // returns kind=keyboard, exactly one TEXT block (text rendered null-tolerantly → empty), and
        // keyboardRows=null (the author has typed no rows → absent, not []). No 422, no NPE.
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.ACTIVE);
        String stepId = "kb1";
        Funnel f = seedFunnel("F", FunnelStatus.draft, "go",
                new ArrayList<>(List.of(setKeyboardStep(stepId, "saved"))));

        mockMvc.perform(post(previewUrl(f, stepId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewKeyboardBody("SET_KEYBOARD", null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("keyboard"))
                .andExpect(jsonPath("$.renderedBlocks.length()").value(1))
                .andExpect(jsonPath("$.renderedBlocks[0].type").value("TEXT"))
                .andExpect(jsonPath("$.renderedBlocks[0].text").value(""))
                .andExpect(jsonPath("$.keyboardRows").doesNotExist());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void previewSetKeyboardUnlinkedBotUsesStubSubscriber() throws Exception {
        // No linked owner → keyboard text renders on the sample stub (Іван), sampleData=true, no NPE/500 —
        // same stub fallback as the message branch (previewUnlinkedBotUsesStubSubscriber).
        String stepId = "kb1";
        Funnel f = seedFunnel("F", FunnelStatus.draft, "go",
                new ArrayList<>(List.of(setKeyboardStep(stepId, "saved"))));

        mockMvc.perform(post(previewUrl(f, stepId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewKeyboardBody("SET_KEYBOARD", "Привіт {user.first_name}", null,
                                keyboardRow("Меню"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("keyboard"))
                .andExpect(jsonPath("$.renderedBlocks[0].text").value("Привіт Іван"))
                .andExpect(jsonPath("$.sampleData").value(true));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void previewSetKeyboardLabelAtCapNotTruncated() throws Exception {
        // Clamp boundary: a label of exactly 64 chars (== MAX_KEYBOARD_BUTTON_TEXT) is NOT truncated
        // (truncation is strictly >, not >=). Pins the off-by-one boundary.
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.ACTIVE);
        String stepId = "kb1";
        Funnel f = seedFunnel("F", FunnelStatus.draft, "go",
                new ArrayList<>(List.of(setKeyboardStep(stepId, "saved"))));
        String exactly64 = "y".repeat(64);

        mockMvc.perform(post(previewUrl(f, stepId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewKeyboardBody("SET_KEYBOARD", "Текст", null,
                                keyboardRow(exactly64))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyboardRows[0][0]").value(exactly64));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void previewMessageStepHasNullKeyboardRows() throws Exception {
        // Regression guard: a MESSAGE step preview never carries keyboardRows (the new field is null there).
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.ACTIVE);
        String stepId = "s1";
        Funnel f = seedFunnel("F", FunnelStatus.draft, "go",
                new ArrayList<>(List.of(textStep(stepId, "saved"))));

        mockMvc.perform(post(previewUrl(f, stepId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewBody("MESSAGE", textBlock("hi"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("message"))
                .andExpect(jsonPath("$.keyboardRows").doesNotExist());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void previewUnknownStepIdReturns404() throws Exception {
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.ACTIVE);
        Funnel f = seedFunnel("F", FunnelStatus.draft, "go",
                new ArrayList<>(List.of(textStep("s1", "saved"))));

        mockMvc.perform(post(previewUrl(f, "no-such-step")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewBody("MESSAGE", textBlock("hi"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void previewOnTheFlyContentNotSavedStep() throws Exception {
        // The request block text differs from the saved step text → the REQUEST text renders.
        seedConnectedBotWithOwner("my_bot", OWNER_CHAT_ID);
        seedOwnerSubscriber(OWNER_CHAT_ID, SubscriberStatus.ACTIVE);
        String stepId = "s1";
        Funnel f = seedFunnel("F", FunnelStatus.draft, "go",
                new ArrayList<>(List.of(textStep(stepId, "SAVED-TEXT"))));

        mockMvc.perform(post(previewUrl(f, stepId)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewBody("MESSAGE", textBlock("LIVE-TEXT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderedBlocks[0].text").value("LIVE-TEXT"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void previewForeignFunnelReturns404() throws Exception {
        // Anti-IDOR: a foreign funnel → uniform 404 (requireFunnel FIRST).
        String foreignId = seedForeignFunnel();

        mockMvc.perform(post(url() + "/" + foreignId + "/steps/s1/preview").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewBody("MESSAGE", textBlock("hi"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void previewIdorPrecedenceOver422AndStepId404() throws Exception {
        // A foreign funnel + an unknown stepId: the requireFunnel-404 PRECEDES the stepId-404, so the
        // 404-vs-404 result never becomes an existence oracle (Decision 9).
        String foreignId = seedForeignFunnel();

        mockMvc.perform(post(url() + "/" + foreignId + "/steps/does-not-exist/preview").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(previewBody("MESSAGE", textBlock("hi"))))
                .andExpect(status().isNotFound());
    }

    // ─── access guards (uniform 404) ───────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void pauseNonActiveReturns422() throws Exception {
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of(messageStep("hi")));
        mockMvc.perform(post(url() + "/" + f.getId() + "/pause").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_invalid_state"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void updateActiveFunnelToCollidingTriggerReturns422() throws Exception {
        seedFunnel("First", FunnelStatus.active, "taken", List.of(messageStep("a")));
        Funnel second = seedFunnel("Second", FunnelStatus.active, "free", List.of(messageStep("b")));

        Map<String, Object> body = Map.of("name", "Second", "triggerValue", "taken",
                "steps", List.of(messageStepMap(textBlock("b"))));
        mockMvc.perform(put(url() + "/" + second.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_trigger_conflict"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void malformedFunnelIdReturns404() throws Exception {
        mockMvc.perform(get(url() + "/not-a-valid-objectid"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void crossProjectFunnelReturnsUniform404() throws Exception {
        seedUser(OTHER_USER_ID, "other@test.com");
        String foreignProject = saveProject(OTHER_USER_ID, null).getId();
        Funnel foreign = new Funnel();
        foreign.setProjectId(foreignProject);
        foreign.setName("Foreign");
        foreign.setStatus(FunnelStatus.draft);
        foreign.setTriggerType(FunnelService.TRIGGER_ON_START);
        foreign.setTriggerValue("");
        foreign.setSteps(new ArrayList<>());
        foreign.setCreatedAt(Instant.now());
        foreign.setUpdatedAt(Instant.now());
        String foreignId = funnelRepository.save(foreign).getId();

        mockMvc.perform(get(url() + "/" + foreignId)).andExpect(status().isNotFound());
        mockMvc.perform(put(url() + "/" + foreignId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "x", "steps", List.of()))))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(url() + "/" + foreignId).with(csrf())).andExpect(status().isNotFound());
        mockMvc.perform(post(url() + "/" + foreignId + "/activate").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void foreignProjectReturns404() throws Exception {
        seedUser(OTHER_USER_ID, "other@test.com");
        String foreign = saveProject(OTHER_USER_ID, null).getId();
        mockMvc.perform(get("/api/v1/projects/" + foreign + "/funnels"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void malformedProjectIdReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/projects/not-a-valid-objectid/funnels"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void missingFunnelIdReturns404() throws Exception {
        mockMvc.perform(get(url() + "/0123456789abcdef01234567"))
                .andExpect(status().isNotFound());
    }

    // ─── bean-validation / per-type validation ─────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void beanValidationMissingNameReturns400() throws Exception {
        mockMvc.perform(post(url()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("description", "no name"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void emptyTextInTextBlockReturns422() throws Exception {
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        Map<String, Object> body = Map.of(
                "name", "Draft", "triggerValue", "",
                "steps", List.of(messageStepMap(textBlock("  "))));
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void invalidTriggerValueWithSpaceReturns422() throws Exception {
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        Map<String, Object> body = Map.of(
                "name", "Draft", "triggerValue", "has space",
                "steps", List.of(messageStepMap(textBlock("ok"))));
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_invalid_trigger_value"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void exceedingMaxStepsReturns422() throws Exception {
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < 51; i++) { // default max-steps = 50
            steps.add(messageStepMap(textBlock("s" + i)));
        }
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Draft", "triggerValue", "", "steps", steps))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_limit_reached"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void invalidTagSlugReturns422() throws Exception {
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        Map<String, Object> body = Map.of("name", "Draft", "triggerValue", "",
                "steps", List.of(stepMap("ADD_TAG", Map.of("tagSlug", "Has Space!"))));
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    // ─── 15-message-composer: save-validation ───────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void updateEmptyComposerReturns422() throws Exception {
        // MESSAGE step with blocks: [] → 422 (empty composer).
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("stepType", "MESSAGE");
        step.put("blocks", List.of());
        Map<String, Object> body = Map.of("name", "Draft", "triggerValue", "",
                "steps", List.of(step));
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void updateOverTenBlocksReturns422() throws Exception {
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            blocks.add(textBlock("b" + i));
        }
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Draft", "triggerValue", "",
                                "steps", List.of(messageStepMapBlocks(blocks))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void updateAlbumUnderTwoReturns422() throws Exception {
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        Map<String, Object> album = albumBlock(mediaItem("https://example.com/1.jpg", "only one"));
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Draft", "triggerValue", "",
                                "steps", List.of(messageStepMap(album))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void updateAlbumOverTenReturns422() throws Exception {
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        Map<String, Object>[] items = new Map[11];
        for (int i = 0; i < 11; i++) {
            items[i] = mediaItem("https://example.com/" + i + ".jpg", i == 0 ? "first" : null);
        }
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Draft", "triggerValue", "",
                                "steps", List.of(messageStepMap(albumBlock(items)))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void updateAlbumCaptionOnNonFirstReturns422() throws Exception {
        // Decision 5: a caption on a non-first album element is rejected (not silently dropped).
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        Map<String, Object> album = albumBlock(
                mediaItem("https://example.com/1.jpg", "first"),
                mediaItem("https://example.com/2.jpg", "second-not-allowed"));
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Draft", "triggerValue", "",
                                "steps", List.of(messageStepMap(album))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void updateAlbumInvalidTypeMixReturns422() throws Exception {
        // Decision 5: audio/document never mix with another kind in the same group. An IMAGE + AUDIO album
        // is an invalid mix → 422 with the funnel_step_invalid business code.
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        Map<String, Object> album = albumBlock(
                mediaItem("IMAGE", "https://example.com/1.jpg", "first"),
                mediaItem("AUDIO", "https://example.com/2.mp3", null));
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Draft", "triggerValue", "",
                                "steps", List.of(messageStepMap(album))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void updateAlbumImageFileMixReturns422() throws Exception {
        // Decision 5: document (FILE) never mixes with a visual kind → 422.
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        Map<String, Object> album = albumBlock(
                mediaItem("IMAGE", "https://example.com/1.jpg", "first"),
                mediaItem("FILE", "https://example.com/2.pdf", null));
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Draft", "triggerValue", "",
                                "steps", List.of(messageStepMap(album))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void updateAlbumMissingItemTypeReturns422() throws Exception {
        // Decision 5: an album item without a media kind is rejected (null discriminator → 422).
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        Map<String, Object> album = albumBlock(
                mediaItem(null, "https://example.com/1.jpg", "first"),
                mediaItem(null, "https://example.com/2.jpg", null));
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Draft", "triggerValue", "",
                                "steps", List.of(messageStepMap(album))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void updateAlbumAllAudioSucceeds() throws Exception {
        // Decision 5: all-audio is a valid homogeneous group → 200.
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        Map<String, Object> album = albumBlock(
                mediaItem("AUDIO", "https://example.com/1.mp3", "first"),
                mediaItem("AUDIO", "https://example.com/2.mp3", null));
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Draft", "triggerValue", "",
                                "steps", List.of(messageStepMap(album))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].blocks[0].items[0].type").value("AUDIO"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void updateAlbumPhotoVideoMixSucceeds() throws Exception {
        // Decision 5: a photo+video mix is a valid media group → 200. Round-trips the per-item type.
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        Map<String, Object> album = albumBlock(
                mediaItem("IMAGE", "https://example.com/1.jpg", "first"),
                mediaItem("VIDEO", "https://example.com/2.mp4", null));
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Draft", "triggerValue", "",
                                "steps", List.of(messageStepMap(album))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].blocks[0].items[0].type").value("IMAGE"))
                .andExpect(jsonPath("$.steps[0].blocks[0].items[1].type").value("VIDEO"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void updateButtonsOnNonLastBlockReturns422() throws Exception {
        // Buttons attach only to the LAST non-album block. Here the last block is an album → 422 even
        // though a text block precedes it (the keyboard cannot land on the album).
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        Map<String, Object> album = albumBlock(
                mediaItem("https://example.com/1.jpg", "first"),
                mediaItem("https://example.com/2.jpg", null));
        Map<String, Object> step = messageStepMapBlocks(List.of(textBlock("hi"), album));
        step.put("buttons", List.of(Map.of("type", "callback", "label", "Go")));
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Draft", "triggerValue", "",
                                "steps", List.of(step)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void updateButtonsOnAlbumBlockReturns422() throws Exception {
        // A single-block composer whose only (= last) block is an album, with buttons → 422.
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        Map<String, Object> album = albumBlock(
                mediaItem("https://example.com/1.jpg", "first"),
                mediaItem("https://example.com/2.jpg", null));
        Map<String, Object> step = messageStepMap(album);
        step.put("buttons", List.of(Map.of("type", "callback", "label", "Go")));
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Draft", "triggerValue", "",
                                "steps", List.of(step)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void updateButtonsOnLastTextBlockSucceeds() throws Exception {
        // Buttons on the last NON-album block (text) with a callback button → valid save (200).
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        Map<String, Object> step = messageStepMapBlocks(List.of(
                imageBlockMap("https://example.com/a.jpg", "cap"),
                textBlock("choose")));
        step.put("buttons", List.of(Map.of("type", "callback", "label", "Go")));
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Draft", "triggerValue", "",
                                "steps", List.of(step)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].buttons.length()").value(1));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void updateNonHttpMediaUrlReturns422() throws Exception {
        // A media URL with the file:// scheme (looks like a URL via scheme separator) → 422.
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Draft", "triggerValue", "",
                                "steps", List.of(messageStepMap(
                                        imageBlockMap("file:///etc/passwd", null)))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void updateJavascriptMediaUrlReturns422() throws Exception {
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Draft", "triggerValue", "",
                                "steps", List.of(messageStepMap(
                                        imageBlockMap("javascript:alert(1)", null)))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void updateFileIdMediaSourceAccepted() throws Exception {
        // A bare file_id token (no scheme separator) is accepted as an opaque media source → 200.
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Draft", "triggerValue", "",
                                "steps", List.of(messageStepMap(
                                        imageBlockMap("AgACAgIAAxkBAAE_file_id_token-123", "cap")))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].blocks[0].mediaUrl")
                        .value("AgACAgIAAxkBAAE_file_id_token-123"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void updateOverLengthTextFlaggedNotBlocking() throws Exception {
        // text >4096 and caption >1024 are warnings — save SUCCEEDS (not 422).
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        String longText = "x".repeat(5000);
        String longCaption = "y".repeat(2000);
        List<Map<String, Object>> blocks = List.of(
                imageBlockMap("https://example.com/a.jpg", longCaption),
                textBlock(longText));
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Draft", "triggerValue", "",
                                "steps", List.of(messageStepMapBlocks(blocks))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].blocks.length()").value(2));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void saveFetchRoundTripPreservesBlocks() throws Exception {
        // DTO round-trip: save a MESSAGE step with a full set of block types → GET returns blocks with no
        // field dropped (incl. album items + per-element caption).
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());

        Map<String, Object> text = textBlock("hello {user.first_name}");
        text.put("parseMode", "HTML");
        Map<String, Object> image = imageBlockMap("https://example.com/i.png", "img cap");
        Map<String, Object> video = mediaBlockMap("VIDEO", "https://example.com/v.mp4", "vid cap");
        Map<String, Object> audio = mediaBlockMap("AUDIO", "file_id_audio_123", null);
        Map<String, Object> file = mediaBlockMap("FILE", "https://example.com/doc.pdf", "doc");
        Map<String, Object> album = albumBlock(
                mediaItem("https://example.com/1.jpg", "first only"),
                mediaItem("https://example.com/2.jpg", null),
                mediaItem("https://example.com/3.jpg", null));

        Map<String, Object> step = messageStepMapBlocks(
                List.of(text, image, video, audio, file, album));

        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Draft", "triggerValue", "",
                                "steps", List.of(step)))))
                .andExpect(status().isOk());

        mockMvc.perform(get(url() + "/" + f.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].stepType").value("MESSAGE"))
                .andExpect(jsonPath("$.steps[0].blocks.length()").value(6))
                .andExpect(jsonPath("$.steps[0].blocks[0].type").value("TEXT"))
                .andExpect(jsonPath("$.steps[0].blocks[0].text").value("hello {user.first_name}"))
                .andExpect(jsonPath("$.steps[0].blocks[0].parseMode").value("HTML"))
                .andExpect(jsonPath("$.steps[0].blocks[1].type").value("IMAGE"))
                .andExpect(jsonPath("$.steps[0].blocks[1].mediaUrl").value("https://example.com/i.png"))
                .andExpect(jsonPath("$.steps[0].blocks[1].caption").value("img cap"))
                .andExpect(jsonPath("$.steps[0].blocks[2].type").value("VIDEO"))
                .andExpect(jsonPath("$.steps[0].blocks[3].type").value("AUDIO"))
                .andExpect(jsonPath("$.steps[0].blocks[3].mediaUrl").value("file_id_audio_123"))
                .andExpect(jsonPath("$.steps[0].blocks[4].type").value("FILE"))
                .andExpect(jsonPath("$.steps[0].blocks[5].type").value("ALBUM"))
                .andExpect(jsonPath("$.steps[0].blocks[5].items.length()").value(3))
                .andExpect(jsonPath("$.steps[0].blocks[5].items[0].type").value("IMAGE"))
                .andExpect(jsonPath("$.steps[0].blocks[5].items[0].mediaUrl")
                        .value("https://example.com/1.jpg"))
                .andExpect(jsonPath("$.steps[0].blocks[5].items[0].caption").value("first only"))
                .andExpect(jsonPath("$.steps[0].blocks[5].items[1].caption")
                        .value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void composerButtonsRoundTripThroughPutAndGet() throws Exception {
        // PUT a funnel: a MESSAGE target step + a MESSAGE composer with a text block + 2 callback buttons
        // (one → the target step, one → End). GET must return the same buttons/targets + minted ids.
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());

        Map<String, Object> sendStep = messageStepMap(textBlock("branch"));
        sendStep.put("id", "target-1"); // client-supplied stable id, preserved by toSteps
        Map<String, Object> menuStep = messageStepMap(textBlock("choose"));
        menuStep.put("buttons", List.of(
                Map.of("type", "callback", "label", "Go", "targetStepId", "target-1"),
                Map.of("type", "callback", "label", "Quit"))); // no target = End

        Map<String, Object> body = Map.of("name", "Draft", "triggerValue", "",
                "steps", List.of(menuStep, sendStep));

        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(2));

        mockMvc.perform(get(url() + "/" + f.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].stepType").value("MESSAGE"))
                .andExpect(jsonPath("$.steps[0].id").isNotEmpty())
                .andExpect(jsonPath("$.steps[0].buttons.length()").value(2))
                .andExpect(jsonPath("$.steps[0].buttons[0].label").value("Go"))
                .andExpect(jsonPath("$.steps[0].buttons[0].targetStepId").value("target-1"))
                .andExpect(jsonPath("$.steps[0].buttons[1].label").value("Quit"))
                .andExpect(jsonPath("$.steps[0].buttons[1].targetStepId")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.steps[1].id").value("target-1"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void duplicateStepIdReturns422() throws Exception {
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());

        Map<String, Object> a = messageStepMap(textBlock("a"));
        a.put("id", "dup");
        Map<String, Object> b = messageStepMap(textBlock("b"));
        b.put("id", "dup");
        Map<String, Object> body = Map.of("name", "Draft", "triggerValue", "",
                "steps", List.of(a, b));

        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    // ─── Phase 2: graph validation (buttons + edges) ───────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void activateBrokenEdgeReturns422() throws Exception {
        // A composer whose callback button points at a step id that is NOT in the funnel → 422.
        FunnelStep menu = composerWithButtons("step-menu", "menu",
                callbackButton("Go", "deleted-step-id"),
                callbackButton("Stay", null));
        Funnel f = seedFunnel("Broken", FunnelStatus.draft, "promo",
                new ArrayList<>(List.of(menu)));

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_broken_edge"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void activateBrokenNextEdgeReturns422() throws Exception {
        // A step whose `next` points at a non-existent id → 422 funnel_broken_edge.
        FunnelStep send = textStep("step-send", "hi");
        send.setNext("ghost-step");
        Funnel f = seedFunnel("BrokenNext", FunnelStatus.draft, "promo",
                new ArrayList<>(List.of(send)));

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_broken_edge"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void activateComposerWithoutCallbackReturns422() throws Exception {
        // A composer keyboard with only a URL button (0 callback) would strand the subscriber → 422.
        FunnelStep menu = composerWithButtons("step-menu", "menu",
                urlButton("Site", "https://example.com"));
        Funnel f = seedFunnel("NoCallback", FunnelStatus.draft, "promo",
                new ArrayList<>(List.of(menu)));

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void activateComposerUrlButtonRejectsNonHttpScheme() throws Exception {
        FunnelStep menu = composerWithButtons("step-menu", "menu",
                callbackButton("Ok", null),
                urlButton("Evil", "javascript:alert(1)"));
        Funnel f = seedFunnel("BadScheme", FunnelStatus.draft, "promo",
                new ArrayList<>(List.of(menu)));

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void activateComposerUrlButtonRejectsLeadingWhitespace() throws Exception {
        FunnelStep menu = composerWithButtons("step-menu", "menu",
                callbackButton("Ok", null),
                urlButton("Sneaky", " http://example.com"));
        Funnel f = seedFunnel("Whitespace", FunnelStatus.draft, "promo",
                new ArrayList<>(List.of(menu)));

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void activateComposerUrlButtonRejectsEmptyHost() throws Exception {
        FunnelStep menu = composerWithButtons("step-menu", "menu",
                callbackButton("Ok", null),
                urlButton("NoHost", "http:///path"));
        Funnel f = seedFunnel("EmptyHost", FunnelStatus.draft, "promo",
                new ArrayList<>(List.of(menu)));

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void activateComposerRejectsTooManyButtons() throws Exception {
        FunnelStep menu = composer("step-menu", textBlockDomain("pick"));
        List<Button> buttons = new ArrayList<>();
        buttons.add(new Button("callback", "first", null, null));
        for (int i = 0; i < 8; i++) { // 1 callback + 8 url = 9 > MAX_BUTTONS(8)
            buttons.add(new Button("url", "u" + i, null, "https://example.com/" + i));
        }
        menu.setButtons(buttons);
        Funnel f = seedFunnel("TooMany", FunnelStatus.draft, "promo",
                new ArrayList<>(List.of(menu)));

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void activateComposerWithValidTimeoutSucceeds() throws Exception {
        FunnelStep menu = composerWithButtons("step-menu", "menu", callbackButton("Go", null));
        menu.setTimeoutValue(2);
        menu.setTimeoutUnit("HOUR");
        Funnel f = seedFunnel("WithTimeout", FunnelStatus.draft, "promo",
                new ArrayList<>(List.of(menu)));
        seedConnectedBot("validtimeout_bot");

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void activateComposerRejectsUnknownTimeoutUnit() throws Exception {
        FunnelStep menu = composerWithButtons("step-menu", "menu", callbackButton("Go", null));
        menu.setTimeoutValue(2);
        menu.setTimeoutUnit("hours");
        Funnel f = seedFunnel("BadUnit", FunnelStatus.draft, "promo",
                new ArrayList<>(List.of(menu)));

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void activateComposerRejectsTimeoutValueBelowOne() throws Exception {
        FunnelStep menu = composerWithButtons("step-menu", "menu", callbackButton("Go", null));
        menu.setTimeoutValue(0);
        menu.setTimeoutUnit("MIN");
        Funnel f = seedFunnel("ZeroTimeout", FunnelStatus.draft, "promo",
                new ArrayList<>(List.of(menu)));

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void activateComposerRejectsHalfTimeoutPair() throws Exception {
        FunnelStep menu = composerWithButtons("step-menu", "menu", callbackButton("Go", null));
        menu.setTimeoutValue(5);
        menu.setTimeoutUnit(null);
        Funnel f = seedFunnel("HalfPair", FunnelStatus.draft, "promo",
                new ArrayList<>(List.of(menu)));

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void reorderPreservesButtonTargetsById() throws Exception {
        seedConnectedBot("promo_bot");
        FunnelStep target = textStep("target-x", "hi");
        FunnelStep menu = composerWithButtons("menu-x", "menu", callbackButton("Go", "target-x"));
        Funnel f = seedFunnel("Reorder", FunnelStatus.draft, "go",
                new ArrayList<>(List.of(menu, target)));

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void menuFanInAndLoopTargetsValidate() throws Exception {
        seedConnectedBot("promo_bot");
        FunnelStep shared = textStep("shared-step", "shared");
        FunnelStep menu = composerWithButtons("menu-loop", "menu",
                callbackButton("A", "shared-step"),
                callbackButton("B", "shared-step"),   // fan-in
                callbackButton("Back", "menu-loop"));  // loop to self
        Funnel f = seedFunnel("FanIn", FunnelStatus.draft, "go",
                new ArrayList<>(List.of(menu, shared)));

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void activateComposerLabelTooLongReturns422() throws Exception {
        String label65 = "x".repeat(65);
        FunnelStep menu = composerWithButtons("step-menu", "menu", callbackButton(label65, null));
        Funnel f = seedFunnel("LongLabel", FunnelStatus.draft, "promo",
                new ArrayList<>(List.of(menu)));

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void activateComposerLabelExactly64IsAccepted() throws Exception {
        seedConnectedBot("promo_bot");
        String label64 = "x".repeat(64);
        FunnelStep menu = composerWithButtons("step-menu", "menu", callbackButton(label64, null));
        Funnel f = seedFunnel("MaxLabel", FunnelStatus.draft, "go",
                new ArrayList<>(List.of(menu)));

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    // ─── domain-object helpers (direct seeding) ────────────────────────────────

    private static FunnelStep textStep(String id, String text) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.MESSAGE);
        s.setId(id);
        s.setBlocks(new ArrayList<>(List.of(textBlockDomain(text))));
        return s;
    }

    private static FunnelStep composer(String id, ContentBlock... blocks) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.MESSAGE);
        s.setId(id);
        s.setBlocks(new ArrayList<>(List.of(blocks)));
        return s;
    }

    // Directly-seeded SET_KEYBOARD domain step (16-persistent-keyboard / Task 4). One minimal valid row
    // so the saved step is well-formed; preview reads only the SAVED step's TYPE (the content comes from
    // the request body), so the seeded rows/text are placeholders.
    private static FunnelStep setKeyboardStep(String id, String text) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.SET_KEYBOARD);
        s.setId(id);
        s.setKeyboardText(text);
        s.setKeyboardRows(new ArrayList<>(List.of(
                new KeyboardRow(new ArrayList<>(List.of(new KeyboardButton("saved-btn")))))));
        return s;
    }

    private static FunnelStep clearKeyboardStep(String id, String text) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.CLEAR_KEYBOARD);
        s.setId(id);
        s.setKeyboardText(text);
        return s;
    }

    private static FunnelStep composerWithButtons(String id, String text, Button... buttons) {
        FunnelStep s = composer(id, textBlockDomain(text));
        s.setButtons(new ArrayList<>(List.of(buttons)));
        return s;
    }

    private static ContentBlock textBlockDomain(String text) {
        return new ContentBlock(BlockType.TEXT, text, null, null, null, null);
    }

    private static ContentBlock imageBlock(String mediaUrl, String caption) {
        return new ContentBlock(BlockType.IMAGE, null, null, mediaUrl, caption, null);
    }

    private static Button callbackButton(String label, String targetStepId) {
        return new Button("callback", label, targetStepId, null);
    }

    private static Button urlButton(String label, String url) {
        return new Button("url", label, null, url);
    }

    // ─── JSON-map helpers (request bodies) ──────────────────────────────────────

    private static Map<String, Object> textBlock(String text) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "TEXT");
        b.put("text", text);
        return b;
    }

    private static Map<String, Object> imageBlockMap(String mediaUrl, String caption) {
        return mediaBlockMap("IMAGE", mediaUrl, caption);
    }

    private static Map<String, Object> mediaBlockMap(String type, String mediaUrl, String caption) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", type);
        b.put("mediaUrl", mediaUrl);
        if (caption != null) {
            b.put("caption", caption);
        }
        return b;
    }

    @SafeVarargs
    private static Map<String, Object> albumBlock(Map<String, Object>... items) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "ALBUM");
        b.put("items", List.of(items));
        return b;
    }

    // Album item with an implicit IMAGE kind (the common photo-group case).
    private static Map<String, Object> mediaItem(String mediaUrl, String caption) {
        return mediaItem("IMAGE", mediaUrl, caption);
    }

    private static Map<String, Object> mediaItem(String type, String mediaUrl, String caption) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (type != null) {
            m.put("type", type);
        }
        m.put("mediaUrl", mediaUrl);
        if (caption != null) {
            m.put("caption", caption);
        }
        return m;
    }

    private static Map<String, Object> messageStep(String text) {
        return messageStepMap(textBlock(text));
    }

    private static Map<String, Object> messageStepMap(Map<String, Object> block) {
        return messageStepMapBlocks(List.of(block));
    }

    private static Map<String, Object> messageStepMapBlocks(List<Map<String, Object>> blocks) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("stepType", "MESSAGE");
        step.put("blocks", blocks);
        return step;
    }

    // ─── 16-persistent-keyboard step maps (Task 1) ───────────────────────────────
    private static Map<String, Object> keyboardRow(String... labels) {
        List<Map<String, Object>> buttons = new ArrayList<>();
        for (String label : labels) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("text", label);
            buttons.add(b);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("buttons", buttons);
        return row;
    }

    @SafeVarargs
    private static Map<String, Object> setKeyboardStepMap(String text, Boolean isPersistent,
                                                          Boolean oneTime, Map<String, Object>... rows) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("stepType", "SET_KEYBOARD");
        step.put("keyboardText", text);
        step.put("keyboardRows", List.of(rows));
        if (isPersistent != null) {
            step.put("isPersistent", isPersistent);
        }
        if (oneTime != null) {
            step.put("oneTimeKeyboard", oneTime);
        }
        return step;
    }

    private static Map<String, Object> clearKeyboardStepMap(String text) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("stepType", "CLEAR_KEYBOARD");
        step.put("keyboardText", text);
        return step;
    }

    // Preview request body for a SET_KEYBOARD / CLEAR_KEYBOARD step (16-persistent-keyboard / Task 4):
    // the live form state — keyboardText, keyboardParseMode, and keyboardRows (each row as
    // {"buttons":[{"text":...}]}, matching the KeyboardRowDto JSON shape).
    @SafeVarargs
    private String previewKeyboardBody(String stepType, String text, String parseMode,
                                       Map<String, Object>... rows) throws Exception {
        return previewKeyboardBodyRows(stepType, text, parseMode,
                rows.length == 0 ? null : new ArrayList<>(List.of(rows)));
    }

    private String previewKeyboardBodyRows(String stepType, String text, String parseMode,
                                           List<Map<String, Object>> rows) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stepType", stepType);
        body.put("keyboardText", text);
        if (parseMode != null) {
            body.put("keyboardParseMode", parseMode);
        }
        if (rows != null) {
            body.put("keyboardRows", rows);
        }
        return json(body);
    }

    private String previewBody(String stepType, Map<String, Object> block) throws Exception {
        return previewBodyBlocks(stepType, List.of(block));
    }

    private String previewBodyBlocks(String stepType, List<Map<String, Object>> blocks) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stepType", stepType);
        body.put("blocks", blocks);
        return json(body);
    }

    // ─── generic helpers ────────────────────────────────────────────────────────

    private Object activateCatching(String funnelId) {
        try {
            funnelService.activate(USER_ID, projectId, funnelId);
            return "active";
        } catch (AppException ex) {
            return ex.getCode();
        }
    }

    private String url() {
        return "/api/v1/projects/" + projectId + "/funnels";
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private static Map<String, Object> delayStep(int value, String unit) {
        return stepMap("DELAY", Map.of("delayValue", value, "delayUnit", unit));
    }

    private static Map<String, Object> stepMap(String stepType, Map<String, Object> fields) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("stepType", stepType);
        step.putAll(fields);
        return step;
    }

    private Funnel seedFunnel(String name, FunnelStatus status, String triggerValue,
                              List<?> stepSpecs) {
        Funnel f = new Funnel();
        f.setProjectId(projectId);
        f.setName(name);
        f.setStatus(status);
        f.setTriggerType(FunnelService.TRIGGER_ON_START);
        f.setTriggerValue(triggerValue);
        f.setAllowReEnter(false);
        List<FunnelStep> steps = new ArrayList<>();
        int i = 0;
        for (Object spec : stepSpecs) {
            FunnelStep step = spec instanceof FunnelStep fs ? fs : buildStep(castMap(spec));
            step.setOrder(i++);
            steps.add(step);
        }
        f.setSteps(steps);
        f.setCreatedAt(Instant.now());
        f.setUpdatedAt(Instant.now());
        return funnelRepository.save(f);
    }

    // Builds a directly-seeded domain step from a request-shaped map (used only by seedFunnel for the
    // MESSAGE/DELAY specs the seed helpers produce). MESSAGE → one TEXT block from the map's text.
    @SuppressWarnings("unchecked")
    private FunnelStep buildStep(Map<String, Object> dto) {
        FunnelStep s = new FunnelStep();
        StepType type = StepType.valueOf((String) dto.get("stepType"));
        s.setStepType(type);
        if (type == StepType.MESSAGE) {
            List<Map<String, Object>> blocks = (List<Map<String, Object>>) dto.get("blocks");
            List<ContentBlock> domain = new ArrayList<>();
            if (blocks != null) {
                for (Map<String, Object> b : blocks) {
                    domain.add(new ContentBlock(
                            BlockType.valueOf((String) b.get("type")),
                            (String) b.get("text"),
                            (String) b.get("parseMode"),
                            (String) b.get("mediaUrl"),
                            (String) b.get("caption"),
                            null));
                }
            }
            s.setBlocks(domain);
        }
        s.setDelayValue((Integer) dto.get("delayValue"));
        s.setDelayUnit((String) dto.get("delayUnit"));
        s.setTagSlug((String) dto.get("tagSlug"));
        s.setCustomFieldKey((String) dto.get("customFieldKey"));
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    private final java.util.concurrent.atomic.AtomicLong subscriberSeq =
            new java.util.concurrent.atomic.AtomicLong(1);

    @Autowired org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    private String seedExecution(String funnelId, ExecutionStatus status) {
        return seedExecutionFor(projectId, funnelId, status);
    }

    private String seedExecutionFor(String projectId, String funnelId, ExecutionStatus status) {
        FunnelExecution e = new FunnelExecution();
        e.setProjectId(projectId);
        e.setFunnelId(funnelId);
        e.setSubscriberId("sub-" + subscriberSeq.incrementAndGet());
        e.setTelegramBotId(9000L);
        e.setStatus(status);
        e.setCurrentStepIndex(0);
        e.setStepRunStatus(StepRunStatus.pending);
        e.setNextRunAt(Instant.now());
        e.setStepsSnapshot(new ArrayList<>());
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return mongoTemplate.save(e).getId();
    }

    private ExecutionStatus execStatus(String id) {
        return mongoTemplate.findById(id, FunnelExecution.class).getStatus();
    }

    private StepRunStatus stepRunStatus(String id) {
        return mongoTemplate.findById(id, FunnelExecution.class).getStepRunStatus();
    }

    private void seedConnectedBot(String username) {
        seedConnectedBotWithOwner(username, null);
    }

    private void seedConnectedBotWithOwner(String username, Long ownerChatId) {
        Bot bot = new Bot();
        bot.setProjectId(projectId);
        bot.setTelegramBotId(BOT_TG_ID);
        bot.setTelegramUsername(username);
        bot.setStatus(BotStatus.CONNECTED);
        bot.setOwnerChatId(ownerChatId);
        bot.setConnectedAt(Instant.now());
        botRepository.save(bot);
    }

    private Subscriber seedOwnerSubscriber(Long chatId, SubscriberStatus status) {
        Subscriber s = new Subscriber();
        s.setProjectId(projectId);
        s.setTelegramUserId(chatId);
        s.setTelegramChatId(chatId);
        s.setTelegramBotId(BOT_TG_ID);
        s.setFirstName("Owner");
        s.setStatus(status);
        s.setSubscribedAt(Instant.now());
        s.setLastSeenAt(Instant.now());
        return subscriberRepository.save(s);
    }

    private String seedForeignFunnel() {
        seedUser(OTHER_USER_ID, "other-" + System.nanoTime() + "@test.com");
        String foreignProject = saveProject(OTHER_USER_ID, null).getId();
        Funnel foreign = new Funnel();
        foreign.setProjectId(foreignProject);
        foreign.setName("Foreign");
        foreign.setStatus(FunnelStatus.draft);
        foreign.setTriggerType(FunnelService.TRIGGER_ON_START);
        foreign.setTriggerValue("");
        foreign.setSteps(new ArrayList<>());
        foreign.setCreatedAt(Instant.now());
        foreign.setUpdatedAt(Instant.now());
        return funnelRepository.save(foreign).getId();
    }

    private String previewUrl(Funnel f, String stepId) {
        return url() + "/" + f.getId() + "/steps/" + stepId + "/preview";
    }

    private List<FunnelExecution> inFlightExecutions(String funnelId, String subscriberId) {
        return mongoTemplate.find(
                org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("funnelId").is(funnelId)
                                .and("subscriberId").is(subscriberId)
                                .and("status").in(ExecutionStatus.running.name(),
                                        ExecutionStatus.waiting.name(),
                                        ExecutionStatus.waiting_for_reply.name())),
                FunnelExecution.class);
    }

    private void seedUser(String id, String email) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setName("Owner");
        u.setPasswordHash("x");
        u.setStatus(UserStatus.active);
        u.setSuperAdmin(false);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);
    }

    private Project saveProject(String ownerId, Instant deletedAt) {
        Project p = new Project();
        p.setOwnerId(ownerId);
        p.setName("Proj-" + System.nanoTime());
        p.setTimezone("UTC");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        p.setDeletedAt(deletedAt);
        return projectRepository.save(p);
    }
}
