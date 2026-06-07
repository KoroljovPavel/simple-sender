package com.botfunnel.funnel;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.common.AppException;
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
        return new FunnelStepDto(
                StepType.EMIT_EVENT, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, eventName);
    }

    private UpdateFunnelRequest reqWithStep(FunnelStepDto step) {
        return new UpdateFunnelRequest("f", null, "on_start", "", false, null, List.of(step));
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
    void emitEvent_requiresValidEventNameSlug() {
        // Malformed / blank eventName → 422.
        assert422(emitStep(null));
        assert422(emitStep(""));
        assert422(emitStep("bad event!"));
        assert422(emitStep("x".repeat(65)));

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
}
