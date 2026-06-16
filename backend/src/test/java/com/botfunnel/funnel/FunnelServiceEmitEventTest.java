package com.botfunnel.funnel;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.common.AppException;
import com.botfunnel.funnel.dto.ContentBlockDto;
import com.botfunnel.funnel.dto.CreateFunnelRequest;
import com.botfunnel.funnel.dto.FunnelResponse;
import com.botfunnel.funnel.dto.FunnelStepDto;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Decision 4: the {@code EMIT_EVENT} step carries an {@code eventName} slug
 * ({@code ^[A-Za-z0-9_-]{1,64}$}, required). {@link FunnelService} validates it in {@code validateSteps}
 * and round-trips it through the step DTO ({@code toSteps} / {@code toStepDto}).
 */
class FunnelServiceEmitEventTest extends AbstractIntegrationTest {

    private static final String USER_ID = "fee-user";

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired FunnelRepository funnelRepository;
    @Autowired FunnelService funnelService;

    private String projectId;

    @BeforeEach
    void seed() {
        userRepository.deleteAll();
        projectRepository.deleteAll();
        funnelRepository.deleteAll();

        User u = new User();
        u.setId(USER_ID);
        u.setEmail("fee@test.com");
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

    private String createDraft() {
        return funnelService.create(USER_ID, projectId, new CreateFunnelRequest("f", null)).id();
    }

    private FunnelStepDto emitStep(String eventName) {
        // FunnelStepDto positional: (stepType, id, next, buttons, timeoutValue, timeoutUnit,
        // timeoutTargetStepId, blocks, delayValue, delayUnit, tagSlug, customFieldKey,
        // customFieldValue, eventName, targetFunnelId, targetEntryStepId, endParentAfter,
        // keyboardText, keyboardParseMode, keyboardRows, isPersistent, oneTimeKeyboard).
        return new FunnelStepDto(
                StepType.EMIT_EVENT, null, null, null, null, null, null,
                null, null, null, null, null, null, eventName, null, null, false,
                null, null, null, null, null, null);
    }

    // SUBSCRIBE_TO_FUNNEL step (Task 2): only the three composition fields are set; every other
    // type-specific field is null/false.
    private FunnelStepDto subscribeStep(String targetFunnelId, String targetEntryStepId, boolean endParentAfter) {
        return new FunnelStepDto(
                StepType.SUBSCRIBE_TO_FUNNEL, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                targetFunnelId, targetEntryStepId, endParentAfter,
                null, null, null, null, null, null);
    }

    // Phase 8 (17-funnel-multi-entry): a single on_start trigger element replaces the former flat trio.
    private static com.botfunnel.funnel.dto.TriggerDto onStart(String value) {
        return new com.botfunnel.funnel.dto.TriggerDto("on_start", value, null, null, null);
    }

    private UpdateFunnelRequest reqWithStep(FunnelStepDto step) {
        return new UpdateFunnelRequest("f", null, false, List.of(onStart("")), List.of(step), null);
    }

    // A minimal valid one-step message funnel used as a SUBSCRIBE target (so it can be activated). Returns
    // its id. The owning project defaults to the test's projectId; pass another project to seed a foreign
    // target for the cross-tenant fail-closed test.
    private FunnelStepDto messageStep() {
        return new FunnelStepDto(
                StepType.MESSAGE, null, null, null, null, null, null,
                List.of(new ContentBlockDto("TEXT", "hello", null, null, null, null)),
                null, null, null, null, null, null, null, null, false,
                null, null, null, null, null, null);
    }

    private String seedTarget(String ownerProjectId) {
        String id = funnelService.create(USER_ID, ownerProjectId, new CreateFunnelRequest("target", null)).id();
        funnelService.update(USER_ID, ownerProjectId, id, reqWithStep(messageStep()));
        return id;
    }

    private void assert422(FunnelStepDto step) {
        String id = createDraft();
        assertThatThrownBy(() -> funnelService.update(USER_ID, projectId, id, reqWithStep(step)))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException ae = (AppException) ex;
                    assertThat(ae.getStatus().value()).isEqualTo(422);
                    // A bad eventName is a per-type step validation failure → funnel_step_invalid.
                    assertThat(ae.getCode()).isEqualTo(FunnelService.CODE_INVALID_STEP);
                });
    }

    @Test
    void emitEvent_malformedEventName_rejectedEvenOnDraftSave() {
        // draft-validation: a PRESENT-but-malformed eventName is a stored-injection vector → STILL 422 on a
        // plain draft save (format-if-present is always-on, never deferred).
        assert422(emitStep("bad event!"));
        assert422(emitStep("x".repeat(65)));
    }

    @Test
    void emitEvent_missingEventName_savesAsDraft_butRejectedOnActivate() {
        // draft-validation: a null/blank eventName is a PRESENCE failure → now saves freely (200), then
        // surfaces funnel_step_invalid only at activate.
        for (String name : new String[]{null, ""}) {
            String id = createDraft();
            FunnelResponse resp = funnelService.update(USER_ID, projectId, id, reqWithStep(emitStep(name)));
            assertThat(resp.steps()).hasSize(1);
            assertThat(resp.steps().get(0).stepType()).isEqualTo(StepType.EMIT_EVENT);

            assertThatThrownBy(() -> funnelService.activate(USER_ID, projectId, id))
                    .isInstanceOf(AppException.class)
                    .satisfies(ex -> {
                        AppException ae = (AppException) ex;
                        assertThat(ae.getStatus().value()).isEqualTo(422);
                        assertThat(ae.getCode()).isEqualTo(FunnelService.CODE_INVALID_STEP);
                    });
        }
    }

    @Test
    void emitEvent_validSlug_savesAndRoundTrips() {
        // A valid slug passes and round-trips through toStepDto.
        String id = createDraft();
        FunnelResponse resp = funnelService.update(USER_ID, projectId, id,
                reqWithStep(emitStep("Purchase_Done-1")));

        assertThat(resp.steps()).hasSize(1);
        assertThat(resp.steps().get(0).stepType()).isEqualTo(StepType.EMIT_EVENT);
        assertThat(resp.steps().get(0).eventName()).isEqualTo("Purchase_Done-1");

        // ...and is persisted on the funnel step.
        FunnelStep persisted = funnelRepository.findById(id).orElseThrow().getSteps().get(0);
        assertThat(persisted.getEventName()).isEqualTo("Purchase_Done-1");
    }

    // ---- SUBSCRIBE_TO_FUNNEL save/activation validation (Task 2) ----

    // draft-validation: SUBSCRIBE target validation (required / not_found / step_not_found) is content
    // completeness — now DEFERRED to activate. The draft save succeeds; the SAME 422 code surfaces only on
    // activation. The funnel uses a DISTINCT on_start value per call so two activations never collide on the
    // single-active-per-trigger conflict guard.
    private void assertActivate422(FunnelStepDto step, String onStartValue, String expectedCode) {
        String funnelId = createDraft();
        // Draft save accepts the incomplete SUBSCRIBE step (200, no throw).
        funnelService.update(USER_ID, projectId, funnelId, new UpdateFunnelRequest(
                "f", null, false, List.of(onStart(onStartValue)), List.of(step), null));
        assertThatThrownBy(() -> funnelService.activate(USER_ID, projectId, funnelId))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException ae = (AppException) ex;
                    assertThat(ae.getStatus().value()).isEqualTo(422);
                    assertThat(ae.getCode()).isEqualTo(expectedCode);
                });
    }

    @Test
    void subscribe_activate_requiresTargetFunnelId() {
        // null and blank targetFunnelId both → funnel_subscribe_target_required (deferred to activate).
        assertActivate422(subscribeStep(null, null, false), "req1",
                FunnelService.CODE_SUBSCRIBE_TARGET_REQUIRED);
        assertActivate422(subscribeStep("   ", null, false), "req2",
                FunnelService.CODE_SUBSCRIBE_TARGET_REQUIRED);
    }

    @Test
    void subscribe_activate_targetNotFound_missingOrMalformedOrForeignProject() {
        // (a) Non-existent (well-formed) id → not_found.
        assertActivate422(subscribeStep("000000000000000000000000", null, false), "nf1",
                FunnelService.CODE_SUBSCRIBE_TARGET_NOT_FOUND);

        // (b) Malformed ObjectId hex → not_found (422), NOT a 500.
        assertActivate422(subscribeStep("not-a-valid-objectid", null, false), "nf2",
                FunnelService.CODE_SUBSCRIBE_TARGET_NOT_FOUND);

        // (c) Target in a DIFFERENT project (cross-tenant fail-closed) → same not_found code (no leak).
        Project other = new Project();
        other.setOwnerId(USER_ID);
        other.setName("Other");
        other.setTimezone("UTC");
        other.setCreatedAt(Instant.now());
        other.setUpdatedAt(Instant.now());
        String otherProjectId = projectRepository.save(other).getId();
        String foreignTarget = seedTarget(otherProjectId);
        assertActivate422(subscribeStep(foreignTarget, null, false), "nf3",
                FunnelService.CODE_SUBSCRIBE_TARGET_NOT_FOUND);
    }

    @Test
    void subscribe_activate_targetEntryStepNotInTarget() {
        String target = seedTarget(projectId);
        // The target must be ACTIVE so activation reaches the entry-step check (not the inactive-target gate).
        funnelService.activate(USER_ID, projectId, target);
        assertActivate422(subscribeStep(target, "deadbeefdeadbeefdeadbeef", false), "stepnf",
                FunnelService.CODE_SUBSCRIBE_TARGET_STEP_NOT_FOUND);
    }

    @Test
    void subscribe_save_draftTargetAllowed_andRoundTrips() {
        // The target is a draft funnel; referencing it at SAVE time must NOT raise (active is only checked
        // at activation — Decision 6, chicken-and-egg).
        String target = seedTarget(projectId);
        assertThat(funnelRepository.findById(target).orElseThrow().getStatus()).isEqualTo(FunnelStatus.draft);

        // Resolve a real entry step id in the target to exercise the entry-step-exists path.
        String entryStepId = funnelRepository.findById(target).orElseThrow().getSteps().get(0).getId();

        String parent = createDraft();
        FunnelResponse resp = funnelService.update(USER_ID, projectId, parent,
                reqWithStep(subscribeStep(target, entryStepId, true)));

        // Round-trips through toStepDto on the response.
        assertThat(resp.steps()).hasSize(1);
        FunnelStepDto outStep = resp.steps().get(0);
        assertThat(outStep.stepType()).isEqualTo(StepType.SUBSCRIBE_TO_FUNNEL);
        assertThat(outStep.targetFunnelId()).isEqualTo(target);
        assertThat(outStep.targetEntryStepId()).isEqualTo(entryStepId);
        assertThat(outStep.endParentAfter()).isTrue();

        // ...and is persisted on the funnel step.
        FunnelStep persisted = funnelRepository.findById(parent).orElseThrow().getSteps().get(0);
        assertThat(persisted.getTargetFunnelId()).isEqualTo(target);
        assertThat(persisted.getTargetEntryStepId()).isEqualTo(entryStepId);
        assertThat(persisted.isEndParentAfter()).isTrue();
    }

    @Test
    void subscribe_activate_blocksOnInactiveTarget() {
        // Parent points at a DRAFT (inactive) target. Save succeeds (above), but activation is blocked.
        // Parent uses a DISTINCT on_start trigger value so activating both does not trip the single
        // active-per-(triggerType, triggerValue) conflict guard (target keeps the default "" value).
        String target = seedTarget(projectId);
        String parent = createDraft();
        UpdateFunnelRequest parentReq = new UpdateFunnelRequest(
                "f", null, false, List.of(onStart("parent")), List.of(subscribeStep(target, null, false)), null);
        funnelService.update(USER_ID, projectId, parent, parentReq);

        assertThatThrownBy(() -> funnelService.activate(USER_ID, projectId, parent))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException ae = (AppException) ex;
                    assertThat(ae.getStatus().value()).isEqualTo(422);
                    assertThat(ae.getCode()).isEqualTo(FunnelService.CODE_SUBSCRIBE_TARGET_INACTIVE);
                });

        // Activate the target first, then the parent activation passes.
        funnelService.activate(USER_ID, projectId, target);
        FunnelResponse activated = funnelService.activate(USER_ID, projectId, parent);
        assertThat(activated.status()).isEqualTo(FunnelStatus.active);
    }

    @Test
    void subscribe_activate_blocksWhenAnySecondTargetInactive() {
        // Two SUBSCRIBE steps: the first target is active, the second is draft. Activation must still fail
        // (the loop validates every target, not just the first) → funnel_subscribe_target_inactive.
        String activeTarget = seedTarget(projectId);
        funnelService.activate(USER_ID, projectId, activeTarget);
        String draftTarget = seedTarget(projectId);

        String parent = createDraft();
        UpdateFunnelRequest parentReq = new UpdateFunnelRequest(
                "f", null, false, List.of(onStart("parent2")),
                List.of(subscribeStep(activeTarget, null, false), subscribeStep(draftTarget, null, false)), null);
        funnelService.update(USER_ID, projectId, parent, parentReq);

        assertThatThrownBy(() -> funnelService.activate(USER_ID, projectId, parent))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException ae = (AppException) ex;
                    assertThat(ae.getStatus().value()).isEqualTo(422);
                    assertThat(ae.getCode()).isEqualTo(FunnelService.CODE_SUBSCRIBE_TARGET_INACTIVE);
                });
    }
}
