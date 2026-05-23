package com.botfunnel.project;

import com.botfunnel.common.GlobalErrorHandler;
import com.botfunnel.common.metrics.MeterRegistryConfig;
import com.botfunnel.profile.WithMockAppUser;
import com.botfunnel.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// MVC slice test: bootstraps just ProjectController + SecurityConfig + GlobalErrorHandler.
// @WebMvcTest disables most autoconfig — no live MongoDB / Redis bootstrap needed. Scope is
// intentionally narrow: response-shape lock (no ownerId field). Behavioral coverage lives in
// ProjectControllerIT.
@WebMvcTest(controllers = ProjectController.class)
@Import({SecurityConfig.class, GlobalErrorHandler.class, MeterRegistryConfig.class})
class ProjectControllerSliceTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ProjectService projectService;

    @Test
    @WithMockAppUser
    void getProject_happyPath_responseShapeMatches() throws Exception {
        Project p = new Project();
        p.setId("p-1");
        p.setOwnerId("ignored-by-response");
        p.setName("Acme");
        p.setDescription("desc");
        p.setTimezone("Europe/Kyiv");
        p.setCreatedAt(Instant.parse("2026-05-10T10:00:00Z"));
        p.setUpdatedAt(Instant.parse("2026-05-10T10:00:00Z"));
        p.setDeletedAt(null);

        when(projectService.requireOwned(anyString(), any(), anyBoolean())).thenReturn(p);

        mockMvc.perform(get("/api/v1/projects/p-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("p-1"))
                .andExpect(jsonPath("$.name").value("Acme"))
                .andExpect(jsonPath("$.description").value("desc"))
                .andExpect(jsonPath("$.timezone").value("Europe/Kyiv"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.deletedAt").doesNotExist())
                // Response-shape lock: ownerId must NEVER appear in the response body.
                .andExpect(jsonPath("$.ownerId").doesNotExist());
    }
}
