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

    private UpdateFunnelRequest keywordReq(List<String> keywords, List<com.botfunnel.funnel.dto.FunnelStepDto> steps) {
        return new UpdateFunnelRequest("f", null, "keyword", null, false, keywords, steps);
    }

    @Test
    void keywords_normalizedLowercaseTrimmedDeduped() {
        String id = createDraft();
        FunnelResponse resp = funnelService.update(USER_ID, projectId, id,
                keywordReq(List.of("Bonus", " SALE ", "bonus"), List.of()));

        // "Bonus" + " SALE " + "bonus" → ["bonus", "sale"] (lowercase, trimmed, deduped, order kept).
        assertThat(resp.keywords()).containsExactly("bonus", "sale");
        assertThat(funnelRepository.findById(id).orElseThrow().getKeywords())
                .containsExactly("bonus", "sale");
    }

    @Test
    void keyword_requiresNonEmptyKeywords() {
        String id = createDraft();

        // Absent keywords → 422.
        assertThatThrownBy(() -> funnelService.update(USER_ID, projectId, id, keywordReq(null, List.of())))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getStatus().value()).isEqualTo(422));

        // Empty list → 422.
        assertThatThrownBy(() -> funnelService.update(USER_ID, projectId, id, keywordReq(List.of(), List.of())))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getStatus().value()).isEqualTo(422));

        // List of only blanks normalizes to empty → 422.
        assertThatThrownBy(() -> funnelService.update(USER_ID, projectId, id, keywordReq(List.of("  ", ""), List.of())))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getStatus().value()).isEqualTo(422));
    }

    @Test
    void keywords_rejectedForNonKeywordTrigger() {
        String id = createDraft();
        // Sending keywords on an on_start trigger → 422 (tight contract; Decision 3 reject-vs-ignore).
        UpdateFunnelRequest req = new UpdateFunnelRequest("f", null, "on_start", "", false,
                List.of("bonus"), List.of());
        assertThatThrownBy(() -> funnelService.update(USER_ID, projectId, id, req))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getStatus().value()).isEqualTo(422));
    }
}
