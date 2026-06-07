package com.botfunnel.funnel;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.common.AppException;
import com.botfunnel.funnel.dto.CreateFunnelRequest;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Trigger-type validation (Decisions 1, 3): the five-value set {on_start, keyword, tag_added,
 * custom_field_set, event}; per-type {@code triggerValue} validation (tag slug / field key / event
 * slug). Exercised through {@link FunnelService}.
 */
class FunnelServiceTriggerTypeTest extends AbstractIntegrationTest {

    private static final String USER_ID = "ftt-user";

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

    private String createDraft() {
        return funnelService.create(USER_ID, projectId, new CreateFunnelRequest("f", null)).id();
    }

    private UpdateFunnelRequest req(String triggerType, String triggerValue) {
        return new UpdateFunnelRequest("f", null, triggerType, triggerValue, false, null, List.of());
    }

    private void update(String id, String triggerType, String triggerValue) {
        funnelService.update(USER_ID, projectId, id, req(triggerType, triggerValue));
    }

    private void assert422(String id, String triggerType, String triggerValue, String expectedCode) {
        assertThatThrownBy(() -> update(id, triggerType, triggerValue))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException ae = (AppException) ex;
                    assertThat(ae.getStatus().value()).isEqualTo(422);
                    assertThat(ae.getCode()).isEqualTo(expectedCode);
                });
    }

    @Test
    void triggerType_unknownRejected() {
        String id = createDraft();
        assert422(id, "totally_bogus", "x", FunnelService.CODE_INVALID_TRIGGER_TYPE);
    }

    @Test
    void triggerValue_validatedPerType() {
        String id = createDraft();

        // tag_added: triggerValue is a tag slug ^[a-z0-9_-]{1,32}$.
        assertThatCode(() -> update(id, "tag_added", "paid")).doesNotThrowAnyException();
        // uppercase + space + special → invalid trigger value.
        assert422(id, "tag_added", "Paid Tag!", FunnelService.CODE_INVALID_TRIGGER_VALUE);

        // custom_field_set: triggerValue is a non-blank field key.
        assertThatCode(() -> update(id, "custom_field_set", "plan")).doesNotThrowAnyException();
        assert422(id, "custom_field_set", "   ", FunnelService.CODE_INVALID_TRIGGER_VALUE); // blank key

        // event: triggerValue is an event slug ^[A-Za-z0-9_-]{1,64}$.
        assertThatCode(() -> update(id, "event", "Purchase_Done-1")).doesNotThrowAnyException();
        assert422(id, "event", "bad slug!", FunnelService.CODE_INVALID_TRIGGER_VALUE); // space + special
        assert422(id, "event", "", FunnelService.CODE_INVALID_TRIGGER_VALUE);          // empty (1..64)
    }

    @Test
    void onStart_stillDefaultsAndAccepts() {
        String id = createDraft();
        // null/blank triggerType must still normalise to on_start (no regression).
        assertThatCode(() -> funnelService.update(USER_ID, projectId, id,
                new UpdateFunnelRequest("f", null, null, "", false, null, List.of())))
                .doesNotThrowAnyException();
        assertThat(funnelRepository.findById(id).orElseThrow().getTriggerType()).isEqualTo("on_start");
    }
}
