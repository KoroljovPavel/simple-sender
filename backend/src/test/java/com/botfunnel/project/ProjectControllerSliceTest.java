package com.botfunnel.project;

import com.botfunnel.common.GlobalErrorHandler;
import com.botfunnel.profile.WithMockAppUser;
import com.botfunnel.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

// Slice test: bootstraps just ProjectController + SecurityConfig + GlobalErrorHandler. No live
// MongoDB / Redis bootstrap needed because @WebFluxTest disables most autoconfig — the
// AuthControllerSliceTest in this codebase confirms the pattern works without connection-factory
// mocks. Scope is intentionally narrow: response-shape lock (no ownerId field). Behavioral
// coverage lives in ProjectControllerIT.
@WebFluxTest(controllers = ProjectController.class)
@Import({SecurityConfig.class, GlobalErrorHandler.class})
class ProjectControllerSliceTest {

    @Autowired WebTestClient webTestClient;
    @MockitoBean ProjectService projectService;

    @Test
    @WithMockAppUser
    void getProject_happyPath_responseShapeMatches() {
        Project p = new Project();
        p.setId("p-1");
        p.setOwnerId("ignored-by-response");
        p.setName("Acme");
        p.setDescription("desc");
        p.setTimezone("Europe/Kyiv");
        p.setCreatedAt(Instant.parse("2026-05-10T10:00:00Z"));
        p.setUpdatedAt(Instant.parse("2026-05-10T10:00:00Z"));
        p.setDeletedAt(null);

        when(projectService.requireOwned(anyString(), any(), anyBoolean())).thenReturn(Mono.just(p));

        webTestClient.get().uri("/api/v1/projects/p-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("p-1")
                .jsonPath("$.name").isEqualTo("Acme")
                .jsonPath("$.description").isEqualTo("desc")
                .jsonPath("$.timezone").isEqualTo("Europe/Kyiv")
                .jsonPath("$.createdAt").exists()
                .jsonPath("$.updatedAt").exists()
                .jsonPath("$.deletedAt").doesNotExist()
                // Response-shape lock: ownerId must NEVER appear in the response body.
                .jsonPath("$.ownerId").doesNotExist();
    }
}
