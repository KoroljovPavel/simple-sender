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
    @Autowired FunnelService funnelService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String projectId;

    @BeforeEach
    void cleanAndSeed() {
        userRepository.deleteAll();
        projectRepository.deleteAll();
        funnelRepository.deleteAll();
        botRepository.deleteAll();

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
        seedFunnel("Active one", FunnelStatus.active, "promo", List.of(sendMessage("hi")));

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
                List.of(sendMessage("hello"), delayStep(5, "MIN")));

        mockMvc.perform(get(url() + "/" + f.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(2))
                .andExpect(jsonPath("$.steps[0].stepType").value("SEND_MESSAGE"))
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
                        stepMap("SEND_MESSAGE", Map.of("text", "first")),
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
        assertThat(steps.get(0).getStepType()).isEqualTo(StepType.SEND_MESSAGE);
        assertThat(steps.get(2).getStepType()).isEqualTo(StepType.ADD_TAG);
    }

    // ─── delete ──────────────────────────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void deleteReturns204AndCancelsActiveExecutions() throws Exception {
        Funnel f = seedFunnel("Active", FunnelStatus.active, "promo", List.of(sendMessage("hi")));
        String runningId = seedExecution(f.getId(), ExecutionStatus.running);
        String waitingId = seedExecution(f.getId(), ExecutionStatus.waiting);
        // A parked MENU execution (waiting_for_reply) must be cancelled by delete too (audit-fix F5).
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
        Funnel f = seedFunnel("Promo", FunnelStatus.active, "promo", List.of(sendMessage("hi")));

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
        // Build a two-step graph with explicit ids + edges: a MENU (with a callback targetStepId + a
        // timeout edge) and its SEND_MESSAGE target. The copy must preserve step ids and every edge
        // verbatim, copy keywords/allowReEnter/description, and leave the original untouched.
        FunnelStep target = new FunnelStep();
        target.setStepType(StepType.SEND_MESSAGE);
        target.setText("branch");
        target.setId("target-1");

        FunnelStep menu = menuStep("menu-1", callbackButton("Go", "target-1"));
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

        // Original is unchanged: still draft was its seed status here, still its own id, steps intact.
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
        Funnel f = seedFunnel("Active", FunnelStatus.active, "promo", List.of(sendMessage("hi")));
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
        Funnel f = seedFunnel("Active", FunnelStatus.active, "promo", List.of(sendMessage("hi")));
        seedExecution(f.getId(), ExecutionStatus.completed);

        mockMvc.perform(post(url() + "/" + f.getId() + "/executions/stop").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled").value(0));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void stopAllScopedByProjectAndFunnel() throws Exception {
        Funnel f = seedFunnel("Target", FunnelStatus.active, "promo", List.of(sendMessage("hi")));
        String mineRunning = seedExecution(f.getId(), ExecutionStatus.running);

        // Sibling execution under ANOTHER funnel in the SAME project — must NOT be cancelled.
        Funnel sibling = seedFunnel("Sibling", FunnelStatus.active, "other", List.of(sendMessage("x")));
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
        // Seed (directly) a draft whose SEND_MESSAGE step is missing its required text — activate must
        // re-validate per-type fields and reject with 422 (defense-in-depth beyond the update path).
        FunnelStep broken = new FunnelStep();
        broken.setStepType(StepType.SEND_MESSAGE);
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
        Funnel f = seedFunnel("Ready", FunnelStatus.draft, "go", List.of(sendMessage("hi")));

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.deepLink").value("t.me/promo_bot?start=go"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void pauseTransitionsActiveToPaused() throws Exception {
        Funnel f = seedFunnel("Active", FunnelStatus.active, "promo", List.of(sendMessage("hi")));
        mockMvc.perform(post(url() + "/" + f.getId() + "/pause").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("paused"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void activateTriggerConflictReturns422() throws Exception {
        // An already-active funnel owns (on_start, "promo"); activating a second draft with the same
        // trigger trips the SERVICE pre-check → 422 funnel_trigger_conflict (first line of Decision 8).
        seedFunnel("First", FunnelStatus.active, "promo", List.of(sendMessage("a")));
        Funnel second = seedFunnel("Second", FunnelStatus.draft, "promo", List.of(sendMessage("b")));

        mockMvc.perform(post(url() + "/" + second.getId() + "/activate").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_trigger_conflict"));
    }

    @Test
    void activateTriggerConflictRaceMapsIndexDuplicateKeyTo422() {
        // Both funnels start in draft, so two simultaneous activate() calls BOTH pass the service
        // pre-check (no active funnel exists yet) and race to save status=active. The partial-unique
        // index lets exactly one win; the loser's DuplicateKeyException MUST be mapped to 422
        // funnel_trigger_conflict (NOT surface as a 500). Driven at the service layer (no HTTP/session
        // needed — activate takes ownerId directly) to isolate the index branch deterministically.
        Funnel a = seedFunnel("A", FunnelStatus.draft, "race", List.of(sendMessage("a")));
        Funnel b = seedFunnel("B", FunnelStatus.draft, "race", List.of(sendMessage("b")));

        // parallelInvoke runs the SAME callable n times; an atomic counter routes invocation 0 → a,
        // invocation 1 → b so the two parked VTs activate distinct funnels on simultaneous release.
        java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger();
        List<Object> outcomes = ConcurrencyTestUtils.parallelInvoke(2, () ->
                activateCatching(idx.getAndIncrement() == 0 ? a.getId() : b.getId()));

        long activated = outcomes.stream().filter(o -> "active".equals(o)).count();
        long conflicts = outcomes.stream()
                .filter(o -> FunnelService.CODE_TRIGGER_CONFLICT.equals(o)).count();
        assertThat(activated).as("exactly one funnel becomes active").isEqualTo(1L);
        assertThat(conflicts).as("the loser is mapped to 422 funnel_trigger_conflict, not 500")
                .isEqualTo(1L);
        // And the index left exactly one active row for this trigger.
        assertThat(funnelRepository.findByProjectIdAndTriggerTypeAndTriggerValueAndStatus(
                projectId, FunnelService.TRIGGER_ON_START, "race", FunnelStatus.active)).isPresent();
    }

    // ─── access guards (uniform 404) ───────────────────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void pauseNonActiveReturns422() throws Exception {
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of(sendMessage("hi")));
        mockMvc.perform(post(url() + "/" + f.getId() + "/pause").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_invalid_state"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void updateActiveFunnelToCollidingTriggerReturns422() throws Exception {
        // Editing an active funnel's trigger to collide with ANOTHER active funnel must be 422
        // (defense-in-depth on the update path), never a 500 from the partial-unique index.
        seedFunnel("First", FunnelStatus.active, "taken", List.of(sendMessage("a")));
        Funnel second = seedFunnel("Second", FunnelStatus.active, "free", List.of(sendMessage("b")));

        Map<String, Object> body = Map.of("name", "Second", "triggerValue", "taken",
                "steps", List.of(stepMap("SEND_MESSAGE", Map.of("text", "b"))));
        mockMvc.perform(put(url() + "/" + second.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_trigger_conflict"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void malformedFunnelIdReturns404() throws Exception {
        // Non-hex funnelId must collapse to the uniform anti-enumeration 404, not a 500 from the
        // ObjectId conversion inside findById.
        mockMvc.perform(get(url() + "/not-a-valid-objectid"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void crossProjectFunnelReturnsUniform404() throws Exception {
        // A funnel that belongs to ANOTHER project must collapse to 404 on every verb, even though the
        // caller owns the project in the path — never 403, never 500, never a leak of existence.
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

        // GET, PUT, DELETE, activate of the foreign funnel under MY project path → uniform 404.
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
    void emptyTextInSendMessageReturns422() throws Exception {
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        Map<String, Object> body = Map.of(
                "name", "Draft", "triggerValue", "",
                "steps", List.of(stepMap("SEND_MESSAGE", Map.of("text", "  "))));
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
                "steps", List.of(stepMap("SEND_MESSAGE", Map.of("text", "ok"))));
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
            steps.add(stepMap("SEND_MESSAGE", Map.of("text", "s" + i)));
        }
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Draft", "triggerValue", "", "steps", steps))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_limit_reached"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void invalidImageUrlReturns422() throws Exception {
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());
        Map<String, Object> body = Map.of("name", "Draft", "triggerValue", "",
                "steps", List.of(stepMap("SEND_IMAGE", Map.of("imageUrl", "ftp://evil/x.png"))));
        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
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

    // ─── Phase 2: graph validation (MENU + edges) ──────────────────────────────

    @Test
    @WithMockAppUser(userId = USER_ID)
    void activateBrokenEdgeReturns422() throws Exception {
        // A MENU whose callback button points at a step id that is NOT in the funnel (e.g. the target
        // was deleted) → 422 funnel_broken_edge on activate.
        FunnelStep menu = menuStep("step-menu",
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
        // A non-MENU step whose `next` points at a non-existent id → 422 funnel_broken_edge.
        FunnelStep send = new FunnelStep();
        send.setStepType(StepType.SEND_MESSAGE);
        send.setText("hi");
        send.setId("step-send");
        send.setNext("ghost-step");
        Funnel f = seedFunnel("BrokenNext", FunnelStatus.draft, "promo",
                new ArrayList<>(List.of(send)));

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_broken_edge"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void activateMenuWithoutCallbackReturns422() throws Exception {
        // A MENU with only a URL button (0 callback) would strand the subscriber → 422.
        FunnelStep menu = menuStep("step-menu", urlButton("Site", "https://example.com"));
        Funnel f = seedFunnel("NoCallback", FunnelStatus.draft, "promo",
                new ArrayList<>(List.of(menu)));

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void activateMenuUrlButtonRejectsNonHttpScheme() throws Exception {
        // javascript: scheme on a URL button → 422 (Decision 10 strict scheme check).
        FunnelStep menu = menuStep("step-menu",
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
    void activateMenuUrlButtonRejectsLeadingWhitespace() throws Exception {
        // Leading-whitespace-obfuscated " http://..." must NOT slip past the scheme check → 422.
        FunnelStep menu = menuStep("step-menu",
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
    void activateMenuUrlButtonRejectsEmptyHost() throws Exception {
        // http:/// has scheme http but an empty host → 422.
        FunnelStep menu = menuStep("step-menu",
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
    void activateMenuRejectsTooManyButtons() throws Exception {
        FunnelStep menu = new FunnelStep();
        menu.setStepType(StepType.MENU);
        menu.setId("step-menu");
        menu.setText("pick");
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
    void activateMenuWithValidTimeoutSucceeds() throws Exception {
        // A MENU with a valid timeout pair (value >= 1, unit in {MIN,HOUR,DAY}) and null target (= End)
        // activates cleanly (audit-fix F1 — the pair is validated but legitimate values pass).
        FunnelStep menu = menuStep("step-menu", callbackButton("Go", null));
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
    void activateMenuRejectsUnknownTimeoutUnit() throws Exception {
        // An invalid/legacy timeoutUnit (e.g. the old "hours") must be rejected at activate with a 422 —
        // NOT pass through to the engine where StepExecutor.durationOf would throw and strand the run
        // (audit-fix F1).
        FunnelStep menu = menuStep("step-menu", callbackButton("Go", null));
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
    void activateMenuRejectsTimeoutValueBelowOne() throws Exception {
        FunnelStep menu = menuStep("step-menu", callbackButton("Go", null));
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
    void activateMenuRejectsHalfTimeoutPair() throws Exception {
        // Only timeoutValue present (unit missing) → 422: a half-configured pair is ambiguous.
        FunnelStep menu = menuStep("step-menu", callbackButton("Go", null));
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
    void menuButtonsRoundTripThroughPatchAndGet() throws Exception {
        // PATCH a funnel: a SEND_MESSAGE target step + a MENU with 2 callback buttons (one → the send
        // step, one → End). GET must return the same buttons/targets, and every step must have a
        // server-minted id.
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());

        Map<String, Object> sendStep = stepMap("SEND_MESSAGE", Map.of("text", "branch"));
        sendStep.put("id", "target-1"); // client-supplied stable id, preserved by toSteps
        Map<String, Object> menuStep = stepMap("MENU", Map.of(
                "text", "choose",
                "buttons", List.of(
                        Map.of("type", "callback", "label", "Go", "targetStepId", "target-1"),
                        Map.of("type", "callback", "label", "Quit")))); // no target = End

        Map<String, Object> body = Map.of("name", "Draft", "triggerValue", "",
                "steps", List.of(menuStep, sendStep));

        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(2));

        mockMvc.perform(get(url() + "/" + f.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].stepType").value("MENU"))
                .andExpect(jsonPath("$.steps[0].id").isNotEmpty())
                .andExpect(jsonPath("$.steps[0].buttons.length()").value(2))
                .andExpect(jsonPath("$.steps[0].buttons[0].label").value("Go"))
                .andExpect(jsonPath("$.steps[0].buttons[0].targetStepId").value("target-1"))
                .andExpect(jsonPath("$.steps[0].buttons[1].label").value("Quit"))
                .andExpect(jsonPath("$.steps[0].buttons[1].targetStepId").value(org.hamcrest.Matchers.nullValue()))
                // The preserved client id round-trips; the MENU got a server-minted id (non-empty).
                .andExpect(jsonPath("$.steps[1].id").value("target-1"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void duplicateStepIdReturns422() throws Exception {
        // Client sends two steps with the same id → corrupt graph → 422.
        Funnel f = seedFunnel("Draft", FunnelStatus.draft, "", List.of());

        Map<String, Object> a = stepMap("SEND_MESSAGE", Map.of("text", "a"));
        a.put("id", "dup");
        Map<String, Object> b = stepMap("SEND_MESSAGE", Map.of("text", "b"));
        b.put("id", "dup");
        Map<String, Object> body = Map.of("name", "Draft", "triggerValue", "",
                "steps", List.of(a, b));

        mockMvc.perform(put(url() + "/" + f.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void reorderPreservesButtonTargetsById() throws Exception {
        // Reorder the array (MENU first vs target first). Because targets are stable ids (not indices),
        // the edge stays valid and activate succeeds regardless of array position.
        seedConnectedBot("promo_bot");
        FunnelStep target = new FunnelStep();
        target.setStepType(StepType.SEND_MESSAGE);
        target.setText("hi");
        target.setId("target-x");
        FunnelStep menu = menuStep("menu-x", callbackButton("Go", "target-x"));
        // Array order: MENU before its target — forward edge is irrelevant, id resolves either way.
        Funnel f = seedFunnel("Reorder", FunnelStatus.draft, "go",
                new ArrayList<>(List.of(menu, target)));

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void menuFanInAndLoopTargetsValidate() throws Exception {
        // fan-in: two callback buttons → the same step; loop: a button → back to the MENU itself.
        // Both are valid graph edges → activate passes.
        seedConnectedBot("promo_bot");
        FunnelStep shared = new FunnelStep();
        shared.setStepType(StepType.SEND_MESSAGE);
        shared.setText("shared");
        shared.setId("shared-step");
        FunnelStep menu = menuStep("menu-loop",
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
    void activateMenuLabelTooLongReturns422() throws Exception {
        // 65-char label is over the 64 limit → 422; 64 is the boundary (accepted elsewhere).
        String label65 = "x".repeat(65);
        FunnelStep menu = menuStep("step-menu", callbackButton(label65, null));
        Funnel f = seedFunnel("LongLabel", FunnelStatus.draft, "promo",
                new ArrayList<>(List.of(menu)));

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("funnel_step_invalid"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void activateMenuLabelExactly64IsAccepted() throws Exception {
        // Boundary: a 64-char label is exactly at the limit and must be accepted (off-by-one guard:
        // the check is `> 64`, not `>= 64`).
        seedConnectedBot("promo_bot");
        String label64 = "x".repeat(64);
        FunnelStep menu = menuStep("step-menu", callbackButton(label64, null));
        Funnel f = seedFunnel("MaxLabel", FunnelStatus.draft, "go",
                new ArrayList<>(List.of(menu)));

        mockMvc.perform(post(url() + "/" + f.getId() + "/activate").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private static FunnelStep menuStep(String id, Button... buttons) {
        FunnelStep step = new FunnelStep();
        step.setStepType(StepType.MENU);
        step.setId(id);
        step.setText("menu");
        step.setButtons(new ArrayList<>(List.of(buttons)));
        return step;
    }

    private static Button callbackButton(String label, String targetStepId) {
        return new Button("callback", label, targetStepId, null);
    }

    private static Button urlButton(String label, String url) {
        return new Button("url", label, null, url);
    }


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

    private static Map<String, Object> sendMessage(String text) {
        return stepMap("SEND_MESSAGE", Map.of("text", text));
    }

    private static Map<String, Object> delayStep(int value, String unit) {
        return stepMap("DELAY", Map.of("delayValue", value, "delayUnit", unit));
    }

    private static Map<String, Object> stepMap(String stepType, Map<String, Object> fields) {
        Map<String, Object> step = new java.util.HashMap<>();
        step.put("stepType", stepType);
        step.putAll(fields);
        return step;
    }

    private FunnelStep buildStep(Map<String, Object> dto) {
        FunnelStep s = new FunnelStep();
        s.setStepType(StepType.valueOf((String) dto.get("stepType")));
        s.setText((String) dto.get("text"));
        s.setImageUrl((String) dto.get("imageUrl"));
        s.setCaption((String) dto.get("caption"));
        s.setDelayValue((Integer) dto.get("delayValue"));
        s.setDelayUnit((String) dto.get("delayUnit"));
        s.setTagSlug((String) dto.get("tagSlug"));
        s.setCustomFieldKey((String) dto.get("customFieldKey"));
        return s;
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
        Bot bot = new Bot();
        bot.setProjectId(projectId);
        bot.setTelegramBotId(9000L);
        bot.setTelegramUsername(username);
        bot.setStatus(BotStatus.CONNECTED);
        bot.setConnectedAt(Instant.now());
        botRepository.save(bot);
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
