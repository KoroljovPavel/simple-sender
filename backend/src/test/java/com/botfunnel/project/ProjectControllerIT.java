package com.botfunnel.project;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.events.Event;
import com.botfunnel.events.EventRepository;
import com.botfunnel.profile.WithMockAppUser;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProjectControllerIT extends AbstractIntegrationTest {

    private static final String USER_ID = "test-user-fixed-id";
    private static final String OTHER_USER_ID = "other-user-id";

    @Autowired UserRepository userRepository;
    @Autowired EventRepository eventRepository;
    @Autowired ProjectRepository projectRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    @BeforeEach
    void cleanAndSeed() {
        userRepository.deleteAll();
        eventRepository.deleteAll();
        projectRepository.deleteAll();

        User u = new User();
        u.setId(USER_ID);
        u.setEmail("projects@test.com");
        u.setName("Alice");
        u.setPasswordHash("not-used");
        u.setStatus(UserStatus.active);
        u.setSuperAdmin(false);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);
    }

    private Project saveActive(String ownerId, String name) {
        return saveActive(ownerId, name, "Europe/Kyiv");
    }

    private Project saveActive(String ownerId, String name, String timezone) {
        return saveActiveAt(ownerId, name, timezone, Instant.now());
    }

    private Project saveActiveAt(String ownerId, String name, String timezone, Instant createdAt) {
        Project p = new Project();
        p.setOwnerId(ownerId);
        p.setName(name);
        p.setTimezone(timezone);
        p.setCreatedAt(createdAt);
        p.setUpdatedAt(createdAt);
        return projectRepository.save(p);
    }

    private Project saveSoftDeleted(String ownerId, String name) {
        Project p = new Project();
        p.setOwnerId(ownerId);
        p.setName(name);
        p.setTimezone("Europe/Kyiv");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        p.setDeletedAt(Instant.now());
        return projectRepository.save(p);
    }

    private void awaitEvent(Predicate<Event> predicate) {
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> eventRepository.findAll().stream().anyMatch(predicate));
    }

    private Event findEvent(Predicate<Event> predicate) {
        return eventRepository.findAll().stream().filter(predicate).findFirst().orElseThrow();
    }

    // ---------- POST happy + validation ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_validBody_returns201AndEmitsProjectCreatedEvent() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Acme", "timezone", "Europe/Kyiv"))))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Acme"))
                .andExpect(jsonPath("$.timezone").value("Europe/Kyiv"))
                .andExpect(jsonPath("$.deletedAt").doesNotExist())
                .andExpect(jsonPath("$.ownerId").doesNotExist());

        awaitEvent(e -> "project_created".equals(e.getEventType())
                && USER_ID.equals(e.getUserId()));
        Event evt = findEvent(e -> "project_created".equals(e.getEventType()));
        assertThat(evt.getMetadata()).containsEntry("name", "Acme");
        assertThat(evt.getMetadata().get("projectId")).isNotNull();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_blankName_returns400WithNameInMessage() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "", "timezone", "Europe/Kyiv"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name")))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_nameTooShort_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "ab", "timezone", "Europe/Kyiv"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name")));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_nameTooLong_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "a".repeat(51), "timezone", "Europe/Kyiv"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name")));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_invalidTimezoneGmtPlus5_returns400WithTimezoneInMessage() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Acme", "timezone", "GMT+5"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("timezone")));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_invalidTimezoneOffset_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Acme", "timezone", "+02:00"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("timezone")));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_invalidTimezoneNotAZone_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Acme", "timezone", "NotAZone"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("timezone")));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_blankTimezone_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Acme", "timezone", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("timezone")));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_descriptionTooLong_returns400WithDescriptionInMessage() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Acme", "timezone", "Europe/Kyiv",
                                "description", "x".repeat(201)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("description")));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_sixthProject_returns422WithProjectLimitReachedCode() throws Exception {
        for (int i = 1; i <= 5; i++) {
            saveActive(USER_ID, "Proj" + i);
        }

        mockMvc.perform(post("/api/v1/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Proj6", "timezone", "Europe/Kyiv"))))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("project_limit_reached"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString())));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_duplicateActiveName_returns409WithProjectNameTakenCode() throws Exception {
        saveActive(USER_ID, "Acme");

        mockMvc.perform(post("/api/v1/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Acme", "timezone", "Europe/Kyiv"))))
                .andExpect(status().is(409))
                .andExpect(jsonPath("$.code").value("project_name_taken"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString())));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_hostileBodyWithOwnerId_savesAuthenticatedOwnerIdAndResponseHasNoOwnerIdField() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Acme",
                                "timezone", "Europe/Kyiv",
                                "ownerId", OTHER_USER_ID))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").doesNotExist());

        List<Project> mine = projectRepository.findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(USER_ID);
        assertThat(mine).isNotEmpty();
        assertThat(mine.get(0).getOwnerId())
                .as("hostile body's ownerId must NOT overwrite authenticated user")
                .isEqualTo(USER_ID);

        List<Project> others = projectRepository.findByOwnerIdOrderByCreatedAtDesc(OTHER_USER_ID);
        assertThat(others).isEmpty();
    }

    // ---------- GET list ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void getProjects_returnsOnlyOwnActiveSortedDesc() throws Exception {
        // Explicit createdAt timestamps make the desc sort deterministic regardless of clock
        // resolution on the host (Instant.now() is millisecond-granular on some kernels, so
        // back-to-back saves can collide and let the repository's tie-break leak through).
        saveActiveAt(USER_ID, "A", "Europe/Kyiv", Instant.parse("2026-05-10T10:00:00Z"));
        saveActiveAt(USER_ID, "B", "Europe/Kyiv", Instant.parse("2026-05-10T10:00:01Z"));
        saveSoftDeleted(USER_ID, "Deleted");
        saveActive(OTHER_USER_ID, "Foreign");

        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("B"))
                .andExpect(jsonPath("$[1].name").value("A"))
                .andExpect(jsonPath("$[0].ownerId").doesNotExist());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void getProjects_includeDeletedTrue_returnsActivePlusSoftDeleted() throws Exception {
        saveActive(USER_ID, "Active");
        saveSoftDeleted(USER_ID, "Deleted");

        mockMvc.perform(get("/api/v1/projects").param("include_deleted", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ---------- GET single ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void getProject_singleHappyPath_returns200WithProjectResponseShape() throws Exception {
        // End-to-end response-shape lock against the real DB → entity → DTO path. The slice
        // test mocks ProjectService and would not catch a regression that serializes the Project
        // entity directly (leaking ownerId).
        Project p = saveActive(USER_ID, "Acme");

        mockMvc.perform(get("/api/v1/projects/" + p.getId()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(p.getId()))
                .andExpect(jsonPath("$.name").value("Acme"))
                .andExpect(jsonPath("$.timezone").value("Europe/Kyiv"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.ownerId").doesNotExist());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void getProject_foreignId_returns404() throws Exception {
        Project foreign = saveActive(OTHER_USER_ID, "Foreign");

        mockMvc.perform(get("/api/v1/projects/" + foreign.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void getProject_malformedId_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/projects/zzz-not-an-objectid"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void getProject_softDeletedOwnId_returns404() throws Exception {
        Project deleted = saveSoftDeleted(USER_ID, "Gone");

        mockMvc.perform(get("/api/v1/projects/" + deleted.getId()))
                .andExpect(status().isNotFound());
    }

    // ---------- PATCH ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchProject_softDeletedOwnId_returns404() throws Exception {
        Project deleted = saveSoftDeleted(USER_ID, "Gone");

        mockMvc.perform(patch("/api/v1/projects/" + deleted.getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Renamed"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchProject_foreignId_returns404() throws Exception {
        Project foreign = saveActive(OTHER_USER_ID, "Foreign");

        mockMvc.perform(patch("/api/v1/projects/" + foreign.getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Renamed"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchProject_malformedId_returns404() throws Exception {
        mockMvc.perform(patch("/api/v1/projects/zzz-not-an-objectid")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Renamed"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void deleteProject_foreignId_returns404() throws Exception {
        Project foreign = saveActive(OTHER_USER_ID, "Foreign");

        mockMvc.perform(delete("/api/v1/projects/" + foreign.getId()).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void deleteProject_malformedId_returns404() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/zzz-not-an-objectid").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void restoreProject_foreignId_returns404() throws Exception {
        Project foreign = saveSoftDeleted(OTHER_USER_ID, "Foreign");

        mockMvc.perform(post("/api/v1/projects/" + foreign.getId() + "/restore").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void restoreProject_malformedId_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/projects/zzz-not-an-objectid/restore").with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ---------- 401 unauthenticated ----------

    @Test
    void anyEndpoint_unauthenticated_returns401() throws Exception {
        // No @WithMockAppUser — the production filter chain must return 401 across every verb
        // shape, regardless of test wiring. State-changing arms still attach csrf() so the
        // CSRF filter is satisfied; auth then fires and produces 401 (Decision 2 anti-enumeration:
        // auth-before-CSRF semantics on MVC stack — MVC enforces 401 before 403 for unauth users).
        mockMvc.perform(get("/api/v1/projects")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/projects/any-id")).andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/projects").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Acme", "timezone", "Europe/Kyiv"))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/v1/projects/some-id").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Some"))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/v1/projects/some-id").with(csrf()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/projects/some-id/restore").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    // ---------- PATCH happy + audit ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchProject_renameHappy_returns200AndEmitsProjectRenamedWithPreviousName() throws Exception {
        Project p = saveActive(USER_ID, "Old");

        mockMvc.perform(patch("/api/v1/projects/" + p.getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "New"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New"))
                .andExpect(jsonPath("$.ownerId").doesNotExist());

        awaitEvent(e -> "project_renamed".equals(e.getEventType()));
        Event evt = findEvent(e -> "project_renamed".equals(e.getEventType()));
        assertThat(evt.getMetadata())
                .containsEntry("projectId", p.getId())
                .containsEntry("previousName", "Old")
                .containsEntry("name", "New");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchProject_noNameChange_emitsProjectUpdatedWithoutNameInMetadata() throws Exception {
        Project p = saveActive(USER_ID, "Acme");

        mockMvc.perform(patch("/api/v1/projects/" + p.getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("description", "fresh"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").doesNotExist());

        awaitEvent(e -> "project_updated".equals(e.getEventType()));
        Event evt = findEvent(e -> "project_updated".equals(e.getEventType()));
        assertThat(evt.getMetadata()).containsOnlyKeys("projectId");

        boolean hasRenamed = eventRepository.findAll().stream()
                .anyMatch(e -> "project_renamed".equals(e.getEventType()));
        assertThat(hasRenamed).isFalse();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchProject_renameToExistingActiveName_returns409WithProjectNameTakenCode() throws Exception {
        saveActive(USER_ID, "Taken");
        Project p = saveActive(USER_ID, "Old");

        mockMvc.perform(patch("/api/v1/projects/" + p.getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Taken"))))
                .andExpect(status().is(409))
                .andExpect(jsonPath("$.code").value("project_name_taken"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString())));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchProject_noOpRenameSameName_returns200() throws Exception {
        Project p = saveActive(USER_ID, "Acme");

        mockMvc.perform(patch("/api/v1/projects/" + p.getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Acme"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Acme"))
                .andExpect(jsonPath("$.ownerId").doesNotExist());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchProject_blankDescription_setsDbDescriptionToNull() throws Exception {
        Project p = new Project();
        p.setOwnerId(USER_ID);
        p.setName("Acme");
        p.setDescription("preset");
        p.setTimezone("Europe/Kyiv");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        p = projectRepository.save(p);

        mockMvc.perform(patch("/api/v1/projects/" + p.getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(buildPatchBody("description", ""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").doesNotExist());

        Project reread = projectRepository.findById(p.getId()).orElseThrow();
        assertThat(reread.getDescription()).isNull();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchProject_blankTimezone_doesNotOverwriteExistingTimezone() throws Exception {
        Project p = saveActive(USER_ID, "Acme", "Europe/Kyiv");

        mockMvc.perform(patch("/api/v1/projects/" + p.getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(buildPatchBody("timezone", ""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").doesNotExist());

        Project reread = projectRepository.findById(p.getId()).orElseThrow();
        assertThat(reread.getTimezone()).isEqualTo("Europe/Kyiv");
    }

    // ---------- DELETE / RESTORE ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void deleteProject_happyPath_returns200AndEmitsProjectSoftDeletedAndAppearsInIncludeDeleted() throws Exception {
        Project p = saveActive(USER_ID, "Acme");

        mockMvc.perform(delete("/api/v1/projects/" + p.getId()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedAt").exists())
                .andExpect(jsonPath("$.ownerId").doesNotExist());

        awaitEvent(e -> "project_soft_deleted".equals(e.getEventType()));
        Event evt = findEvent(e -> "project_soft_deleted".equals(e.getEventType()));
        assertThat(evt.getMetadata())
                .containsEntry("projectId", p.getId())
                .containsEntry("name", "Acme");

        // Disappears from default list…
        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        // …appears in include_deleted list.
        mockMvc.perform(get("/api/v1/projects").param("include_deleted", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void deleteProject_alreadySoftDeleted_returns404() throws Exception {
        Project deleted = saveSoftDeleted(USER_ID, "Acme");

        mockMvc.perform(delete("/api/v1/projects/" + deleted.getId()).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void restoreProject_happyPath_returns200AndEmitsProjectRestored() throws Exception {
        Project deleted = saveSoftDeleted(USER_ID, "Acme");

        mockMvc.perform(post("/api/v1/projects/" + deleted.getId() + "/restore").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedAt").doesNotExist())
                .andExpect(jsonPath("$.name").value("Acme"))
                .andExpect(jsonPath("$.ownerId").doesNotExist());

        awaitEvent(e -> "project_restored".equals(e.getEventType()));
        Event evt = findEvent(e -> "project_restored".equals(e.getEventType()));
        assertThat(evt.getMetadata())
                .containsEntry("projectId", deleted.getId())
                .containsEntry("name", "Acme");
        assertThat(evt.getMetadata()).doesNotContainKey("renamedDueToConflict");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void restoreProject_withNameCollision_appendsSuffixAndSetsRenamedDueToConflictMetadata() throws Exception {
        saveActive(USER_ID, "Acme");
        Project deleted = saveSoftDeleted(USER_ID, "Acme");

        mockMvc.perform(post("/api/v1/projects/" + deleted.getId() + "/restore").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Acme (restored)"))
                .andExpect(jsonPath("$.ownerId").doesNotExist());

        awaitEvent(e -> "project_restored".equals(e.getEventType()));
        Event evt = findEvent(e -> "project_restored".equals(e.getEventType()));
        assertThat(evt.getMetadata())
                .containsEntry("renamedDueToConflict", true)
                .containsEntry("name", "Acme (restored)");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void restoreProject_atQuotaLimit_returns422() throws Exception {
        for (int i = 1; i <= 5; i++) saveActive(USER_ID, "Proj" + i);
        Project deleted = saveSoftDeleted(USER_ID, "Proj-deleted");

        mockMvc.perform(post("/api/v1/projects/" + deleted.getId() + "/restore").with(csrf()))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("project_limit_reached"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void restoreProject_onActiveProject_returns404AndDoesNotEmitRenameOrRestoreEvent() throws Exception {
        Project active = saveActive(USER_ID, "Acme");

        mockMvc.perform(post("/api/v1/projects/" + active.getId() + "/restore").with(csrf()))
                .andExpect(status().isNotFound());

        // Decision 14 deletedAt-FIRST guard: ZERO project_* events should be persisted for this
        // project's id — the guard short-circuits before the save, so the event emission never
        // fires. Broader assertion (any project_* type) catches future regressions where a new
        // event type is added to the restore path.
        List<Event> all = eventRepository.findAll();
        assertThat(all).noneMatch(e -> eventMatchesProject(e, active.getId()));
    }

    private static boolean eventMatchesProject(Event e, String projectId) {
        if (e.getEventType() == null || !e.getEventType().startsWith("project_")) return false;
        if (e.getMetadata() == null) return false;
        return projectId.equals(e.getMetadata().get("projectId"));
    }

    // ---------- AC-T1 error body shape ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void errorBodies_alwaysHaveMessageAndCode() throws Exception {
        // 400 (bean validation): message non-blank; code null per GlobalErrorHandler contract.
        mockMvc.perform(post("/api/v1/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "", "timezone", "Europe/Kyiv"))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString())))
                .andExpect(jsonPath("$.code").doesNotExist());

        // 404 (anti-enumeration): message non-blank; code null (regression guard against a
        // future change that adds a code like "project_not_found" — Decision 2 anti-enumeration
        // requires the body shape stay uniform with Java/JS 404s elsewhere).
        mockMvc.perform(get("/api/v1/projects/zzz-not-an-objectid"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString())))
                .andExpect(jsonPath("$.code").doesNotExist());

        // 409 (name conflict): message non-blank; code = project_name_taken.
        saveActive(USER_ID, "Acme");
        mockMvc.perform(post("/api/v1/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Acme", "timezone", "Europe/Kyiv"))))
                .andExpect(status().is(409))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString())))
                .andExpect(jsonPath("$.code").value("project_name_taken"));

        // 422 (quota): message non-blank; code = project_limit_reached. Seed up to the cap so
        // the next POST tips into the limit branch.
        for (int i = 1; i <= 4; i++) saveActive(USER_ID, "Quota" + i);
        mockMvc.perform(post("/api/v1/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Quota5", "timezone", "Europe/Kyiv"))))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString())))
                .andExpect(jsonPath("$.code").value("project_limit_reached"));
    }

    // The Map.of(...) factory rejects null values. PATCH body construction sometimes needs a
    // single explicit empty-string value (description = "" / timezone = "") to exercise the
    // blank-clearing branch — use a small mutable Map for those bodies.
    private static Map<String, Object> buildPatchBody(String key, String value) {
        Map<String, Object> body = new HashMap<>();
        body.put(key, value);
        return body;
    }
}
