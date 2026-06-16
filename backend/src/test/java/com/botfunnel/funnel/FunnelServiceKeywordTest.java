package com.botfunnel.funnel;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.common.AppException;
import com.botfunnel.funnel.dto.FunnelResponse;
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
 * Decision 3: {@code keyword} funnels carry a normalized {@code keywords} list (lowercase, trimmed,
 * de-duplicated, order-preserved), required non-empty iff {@code triggerType=keyword}. Exercised
 * through {@link FunnelService} (it needs Mongo + ProjectService, so this is an IT seam, not an
 * isolated unit test).
 */
class FunnelServiceKeywordTest extends AbstractIntegrationTest {

    private static final String USER_ID = "fk-user";

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
        u.setEmail("fk@test.com");
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
        return funnelService.create(USER_ID, projectId,
                new com.botfunnel.funnel.dto.CreateFunnelRequest("f", null)).id();
    }

    // Phase 8 (17-funnel-multi-entry): keywords now live per-trigger inside a TriggerDto element, not at the
    // top level. A keyword funnel is a single keyword trigger element.
    private UpdateFunnelRequest keywordReq(List<String> keywords, List<com.botfunnel.funnel.dto.FunnelStepDto> steps) {
        return new UpdateFunnelRequest("f", null, false,
                List.of(new com.botfunnel.funnel.dto.TriggerDto("keyword", null, keywords, null, null)), steps, null);
    }

    @Test
    void keywords_normalizedLowercaseTrimmedDeduped() {
        String id = createDraft();
        FunnelResponse resp = funnelService.update(USER_ID, projectId, id,
                keywordReq(List.of("Bonus", " SALE ", "bonus"), List.of()));

        // "Bonus" + " SALE " + "bonus" → ["bonus", "sale"] (lowercase, trimmed, deduped, order kept).
        assertThat(resp.triggers()).hasSize(1);
        assertThat(resp.triggers().get(0).keywords()).containsExactly("bonus", "sale");
        assertThat(funnelRepository.findById(id).orElseThrow().getTriggers().get(0).getKeywords())
                .containsExactly("bonus", "sale");
    }

    private void assertInvalidKeywords(String id, UpdateFunnelRequest req) {
        assertThatThrownBy(() -> funnelService.update(USER_ID, projectId, id, req))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException ae = (AppException) ex;
                    assertThat(ae.getStatus().value()).isEqualTo(422);
                    assertThat(ae.getCode()).isEqualTo(FunnelService.CODE_INVALID_KEYWORDS);
                });
    }

    // A keyword trigger with a single valid MESSAGE step, used to exercise the activate-only presence gate.
    private com.botfunnel.funnel.dto.FunnelStepDto messageStep() {
        return new com.botfunnel.funnel.dto.FunnelStepDto(
                StepType.MESSAGE, null, null, null, null, null, null,
                List.of(new com.botfunnel.funnel.dto.ContentBlockDto("TEXT", "hi", null, null, null, null)),
                null, null, null, null, null, null, null, null, false,
                null, null, null, null, null, null);
    }

    // draft-validation: the non-empty keyword PRESENCE requirement is now deferred to activate. A draft save
    // with absent/empty/blank keywords succeeds (200, round-trips); activation surfaces the SAME
    // funnel_invalid_keywords 422.
    @Test
    void keyword_emptyKeywords_savesAsDraft_butRejectedOnActivate() {
        // Absent / empty / blank-only keyword lists all save freely as a draft.
        for (List<String> keywords : List.<List<String>>of(List.of(), List.of("  ", ""))) {
            String id = createDraft();
            FunnelResponse resp = funnelService.update(USER_ID, projectId, id,
                    keywordReq(keywords, List.of(messageStep())));
            assertThat(resp.triggers()).hasSize(1);
            assertThat(resp.triggers().get(0).keywords()).isEmpty();

            // Activation now enforces the presence gate → 422 funnel_invalid_keywords.
            assertThatThrownBy(() -> funnelService.activate(USER_ID, projectId, id))
                    .isInstanceOf(AppException.class)
                    .satisfies(ex -> assertThat(((AppException) ex).getCode())
                            .isEqualTo(FunnelService.CODE_INVALID_KEYWORDS));
        }

        // null keywords also save as a draft and fail on activate.
        String id = createDraft();
        funnelService.update(USER_ID, projectId, id, keywordReq(null, List.of(messageStep())));
        assertThatThrownBy(() -> funnelService.activate(USER_ID, projectId, id))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getCode())
                        .isEqualTo(FunnelService.CODE_INVALID_KEYWORDS));
    }

    @Test
    void keywords_rejectedForNonKeywordTrigger() {
        String id = createDraft();
        // Sending keywords on an on_start trigger → 422 (tight contract; Decision 3 reject-vs-ignore).
        assertInvalidKeywords(id, new UpdateFunnelRequest("f", null, false,
                List.of(new com.botfunnel.funnel.dto.TriggerDto("on_start", "", List.of("bonus"), null, null)),
                List.of(), null));
    }
}
