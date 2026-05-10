package com.botfunnel.project;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.events.Event;
import com.botfunnel.events.EventRepository;
import com.botfunnel.profile.WithMockAppUser;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
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
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;

class ProjectControllerIT extends AbstractIntegrationTest {

    private static final String USER_ID = "test-user-fixed-id";
    private static final String OTHER_USER_ID = "other-user-id";

    @Autowired UserRepository userRepository;
    @Autowired EventRepository eventRepository;
    @Autowired ProjectRepository projectRepository;

    @BeforeEach
    void cleanAndSeed() {
        userRepository.deleteAll().block();
        eventRepository.deleteAll().block();
        projectRepository.deleteAll().block();

        User u = new User();
        u.setId(USER_ID);
        u.setEmail("projects@test.com");
        u.setName("Alice");
        u.setPasswordHash("not-used");
        u.setStatus(UserStatus.active);
        u.setSuperAdmin(false);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u).block();
    }

    private Project saveActive(String ownerId, String name) {
        return saveActive(ownerId, name, "Europe/Kyiv");
    }

    private Project saveActive(String ownerId, String name, String timezone) {
        Project p = new Project();
        p.setOwnerId(ownerId);
        p.setName(name);
        p.setTimezone(timezone);
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return projectRepository.save(p).block();
    }

    private Project saveSoftDeleted(String ownerId, String name) {
        Project p = new Project();
        p.setOwnerId(ownerId);
        p.setName(name);
        p.setTimezone("Europe/Kyiv");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        p.setDeletedAt(Instant.now());
        return projectRepository.save(p).block();
    }

    private void awaitEvent(Predicate<Event> predicate) {
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> eventRepository.findAll().filter(predicate).hasElements().block());
    }

    private Event findEvent(Predicate<Event> predicate) {
        return eventRepository.findAll().filter(predicate).blockFirst();
    }

    // ---------- POST happy + validation ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_validBody_returns201AndEmitsProjectCreatedEvent() {
        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Acme", "timezone", "Europe/Kyiv"))
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.id").exists()
                .jsonPath("$.name").isEqualTo("Acme")
                .jsonPath("$.timezone").isEqualTo("Europe/Kyiv")
                .jsonPath("$.deletedAt").doesNotExist()
                .jsonPath("$.ownerId").doesNotExist();

        awaitEvent(e -> "project_created".equals(e.getEventType())
                && USER_ID.equals(e.getUserId()));
        Event evt = findEvent(e -> "project_created".equals(e.getEventType()));
        assertThat(evt.getMetadata()).containsEntry("name", "Acme");
        assertThat(evt.getMetadata().get("projectId")).isNotNull();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_blankName_returns400WithNameInMessage() {
        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "", "timezone", "Europe/Kyiv"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.message").value(s -> assertThat((String) s).contains("name"))
                .jsonPath("$.code").doesNotExist();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_nameTooShort_returns400() {
        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "ab", "timezone", "Europe/Kyiv"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.message").value(s -> assertThat((String) s).contains("name"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_nameTooLong_returns400() {
        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "a".repeat(51), "timezone", "Europe/Kyiv"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.message").value(s -> assertThat((String) s).contains("name"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_invalidTimezoneGmtPlus5_returns400WithTimezoneInMessage() {
        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Acme", "timezone", "GMT+5"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.message").value(s -> assertThat((String) s).contains("timezone"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_invalidTimezoneOffset_returns400() {
        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Acme", "timezone", "+02:00"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.message").value(s -> assertThat((String) s).contains("timezone"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_invalidTimezoneNotAZone_returns400() {
        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Acme", "timezone", "NotAZone"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.message").value(s -> assertThat((String) s).contains("timezone"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_blankTimezone_returns400() {
        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Acme", "timezone", ""))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.message").value(s -> assertThat((String) s).contains("timezone"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_descriptionTooLong_returns400WithDescriptionInMessage() {
        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Acme", "timezone", "Europe/Kyiv",
                        "description", "x".repeat(201)))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.message").value(s -> assertThat((String) s).contains("description"));
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_sixthProject_returns422WithProjectLimitReachedCode() {
        for (int i = 1; i <= 5; i++) {
            saveActive(USER_ID, "Proj" + i);
        }

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Proj6", "timezone", "Europe/Kyiv"))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("project_limit_reached")
                .jsonPath("$.message").value(s -> assertThat((String) s).isNotBlank());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_duplicateActiveName_returns409WithProjectNameTakenCode() {
        saveActive(USER_ID, "Acme");

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Acme", "timezone", "Europe/Kyiv"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("project_name_taken")
                .jsonPath("$.message").value(s -> assertThat((String) s).isNotBlank());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void postProject_hostileBodyWithOwnerId_savesAuthenticatedOwnerIdAndResponseHasNoOwnerIdField() {
        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "name", "Acme",
                        "timezone", "Europe/Kyiv",
                        "ownerId", OTHER_USER_ID))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.ownerId").doesNotExist();

        Project saved = projectRepository.findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(USER_ID)
                .blockFirst();
        assertThat(saved).isNotNull();
        assertThat(saved.getOwnerId())
                .as("hostile body's ownerId must NOT overwrite authenticated user")
                .isEqualTo(USER_ID);

        long otherCount = projectRepository.findByOwnerIdOrderByCreatedAtDesc(OTHER_USER_ID).count().block();
        assertThat(otherCount).isZero();
    }

    // ---------- GET list ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void getProjects_returnsOnlyOwnActiveSortedDesc() {
        Project a = saveActive(USER_ID, "A");
        // Add a tiny gap so createdAt-desc ordering is deterministic on fast hardware.
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        Project b = saveActive(USER_ID, "B");
        saveSoftDeleted(USER_ID, "Deleted");
        saveActive(OTHER_USER_ID, "Foreign");

        webTestClient.get().uri("/api/v1/projects")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].name").isEqualTo("B")
                .jsonPath("$[1].name").isEqualTo("A")
                .jsonPath("$[0].ownerId").doesNotExist();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void getProjects_includeDeletedTrue_returnsActivePlusSoftDeleted() {
        saveActive(USER_ID, "Active");
        saveSoftDeleted(USER_ID, "Deleted");

        webTestClient.get().uri("/api/v1/projects?include_deleted=true")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2);
    }

    // ---------- GET single ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void getProject_foreignId_returns404() {
        Project foreign = saveActive(OTHER_USER_ID, "Foreign");

        webTestClient.get().uri("/api/v1/projects/" + foreign.getId())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void getProject_malformedId_returns404() {
        webTestClient.get().uri("/api/v1/projects/zzz-not-an-objectid")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void getProject_softDeletedOwnId_returns404() {
        Project deleted = saveSoftDeleted(USER_ID, "Gone");

        webTestClient.get().uri("/api/v1/projects/" + deleted.getId())
                .exchange()
                .expectStatus().isNotFound();
    }

    // ---------- PATCH ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchProject_softDeletedOwnId_returns404() {
        Project deleted = saveSoftDeleted(USER_ID, "Gone");

        webTestClient.mutateWith(csrf())
                .patch().uri("/api/v1/projects/" + deleted.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Renamed"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchProject_foreignId_returns404() {
        Project foreign = saveActive(OTHER_USER_ID, "Foreign");

        webTestClient.mutateWith(csrf())
                .patch().uri("/api/v1/projects/" + foreign.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Renamed"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchProject_malformedId_returns404() {
        webTestClient.mutateWith(csrf())
                .patch().uri("/api/v1/projects/zzz-not-an-objectid")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Renamed"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void deleteProject_foreignId_returns404() {
        Project foreign = saveActive(OTHER_USER_ID, "Foreign");

        webTestClient.mutateWith(csrf())
                .delete().uri("/api/v1/projects/" + foreign.getId())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void deleteProject_malformedId_returns404() {
        webTestClient.mutateWith(csrf())
                .delete().uri("/api/v1/projects/zzz-not-an-objectid")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void restoreProject_foreignId_returns404() {
        Project foreign = saveSoftDeleted(OTHER_USER_ID, "Foreign");

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + foreign.getId() + "/restore")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void restoreProject_malformedId_returns404() {
        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/zzz-not-an-objectid/restore")
                .exchange()
                .expectStatus().isNotFound();
    }

    // ---------- 401 unauthenticated ----------

    @Test
    void anyEndpoint_unauthenticatedBareClient_returns401() {
        // No @WithMockAppUser → SecurityContext is empty. The shared `webTestClient` from
        // AbstractIntegrationTest is bound to the application context, so SecurityConfig's
        // pathMatchers("/api/**").authenticated() rule fires. State-changing verbs without csrf()
        // mutator may be intercepted by the CSRF filter first, so we only assert "not 200" for
        // those — the GET case definitively confirms 401 from the auth filter chain.
        webTestClient.get().uri("/api/v1/projects")
                .exchange()
                .expectStatus().isUnauthorized();

        webTestClient.get().uri("/api/v1/projects/any-id")
                .exchange()
                .expectStatus().isUnauthorized();

        // For POST/PATCH/DELETE/POST-restore we expect a 4xx security gate (401 from auth, or
        // 403 from CSRF when the order resolves CSRF first). The point is that the request
        // never reaches the controller — assert with .is4xxClientError() per anti-enumeration
        // intent.
        webTestClient.post().uri("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Acme", "timezone", "Europe/Kyiv"))
                .exchange()
                .expectStatus().is4xxClientError();

        webTestClient.patch().uri("/api/v1/projects/some-id")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "X"))
                .exchange()
                .expectStatus().is4xxClientError();

        webTestClient.delete().uri("/api/v1/projects/some-id")
                .exchange()
                .expectStatus().is4xxClientError();

        webTestClient.post().uri("/api/v1/projects/some-id/restore")
                .exchange()
                .expectStatus().is4xxClientError();
    }

    // ---------- PATCH happy + audit ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchProject_renameHappy_returns200AndEmitsProjectRenamedWithPreviousName() {
        Project p = saveActive(USER_ID, "Old");

        webTestClient.mutateWith(csrf())
                .patch().uri("/api/v1/projects/" + p.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "New"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("New")
                .jsonPath("$.ownerId").doesNotExist();

        awaitEvent(e -> "project_renamed".equals(e.getEventType()));
        Event evt = findEvent(e -> "project_renamed".equals(e.getEventType()));
        assertThat(evt.getMetadata())
                .containsEntry("projectId", p.getId())
                .containsEntry("previousName", "Old")
                .containsEntry("name", "New");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchProject_noNameChange_emitsProjectUpdatedWithoutNameInMetadata() {
        Project p = saveActive(USER_ID, "Acme");

        webTestClient.mutateWith(csrf())
                .patch().uri("/api/v1/projects/" + p.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("description", "fresh"))
                .exchange()
                .expectStatus().isOk();

        awaitEvent(e -> "project_updated".equals(e.getEventType()));
        Event evt = findEvent(e -> "project_updated".equals(e.getEventType()));
        assertThat(evt.getMetadata()).containsOnlyKeys("projectId");

        boolean hasRenamed = eventRepository.findAll()
                .filter(e -> "project_renamed".equals(e.getEventType())).hasElements().block();
        assertThat(hasRenamed).isFalse();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchProject_renameToExistingActiveName_returns409WithProjectNameTakenCode() {
        saveActive(USER_ID, "Taken");
        Project p = saveActive(USER_ID, "Old");

        webTestClient.mutateWith(csrf())
                .patch().uri("/api/v1/projects/" + p.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Taken"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("project_name_taken")
                .jsonPath("$.message").value(s -> assertThat((String) s).isNotBlank());
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchProject_noOpRenameSameName_returns200() {
        Project p = saveActive(USER_ID, "Acme");

        webTestClient.mutateWith(csrf())
                .patch().uri("/api/v1/projects/" + p.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Acme"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("Acme");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchProject_blankDescription_setsDbDescriptionToNull() {
        Project p = new Project();
        p.setOwnerId(USER_ID);
        p.setName("Acme");
        p.setDescription("preset");
        p.setTimezone("Europe/Kyiv");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        p = projectRepository.save(p).block();

        webTestClient.mutateWith(csrf())
                .patch().uri("/api/v1/projects/" + p.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(buildPatchBody("description", ""))
                .exchange()
                .expectStatus().isOk();

        Project reread = projectRepository.findById(p.getId()).block();
        assertThat(reread.getDescription()).isNull();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void patchProject_blankTimezone_doesNotOverwriteExistingTimezone() {
        Project p = saveActive(USER_ID, "Acme", "Europe/Kyiv");

        webTestClient.mutateWith(csrf())
                .patch().uri("/api/v1/projects/" + p.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(buildPatchBody("timezone", ""))
                .exchange()
                .expectStatus().isOk();

        Project reread = projectRepository.findById(p.getId()).block();
        assertThat(reread.getTimezone()).isEqualTo("Europe/Kyiv");
    }

    // ---------- DELETE / RESTORE ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void deleteProject_happyPath_returns200AndEmitsProjectSoftDeletedAndAppearsInIncludeDeleted() {
        Project p = saveActive(USER_ID, "Acme");

        webTestClient.mutateWith(csrf())
                .delete().uri("/api/v1/projects/" + p.getId())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.deletedAt").exists()
                .jsonPath("$.ownerId").doesNotExist();

        awaitEvent(e -> "project_soft_deleted".equals(e.getEventType()));
        Event evt = findEvent(e -> "project_soft_deleted".equals(e.getEventType()));
        assertThat(evt.getMetadata())
                .containsEntry("projectId", p.getId())
                .containsEntry("name", "Acme");

        // Disappears from default list…
        webTestClient.get().uri("/api/v1/projects")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(0);
        // …appears in include_deleted list.
        webTestClient.get().uri("/api/v1/projects?include_deleted=true")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void deleteProject_alreadySoftDeleted_returns404() {
        Project deleted = saveSoftDeleted(USER_ID, "Acme");

        webTestClient.mutateWith(csrf())
                .delete().uri("/api/v1/projects/" + deleted.getId())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void restoreProject_happyPath_returns200AndEmitsProjectRestored() {
        Project deleted = saveSoftDeleted(USER_ID, "Acme");

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + deleted.getId() + "/restore")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.deletedAt").doesNotExist()
                .jsonPath("$.name").isEqualTo("Acme");

        awaitEvent(e -> "project_restored".equals(e.getEventType()));
        Event evt = findEvent(e -> "project_restored".equals(e.getEventType()));
        assertThat(evt.getMetadata())
                .containsEntry("projectId", deleted.getId())
                .containsEntry("name", "Acme");
        assertThat(evt.getMetadata()).doesNotContainKey("renamedDueToConflict");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void restoreProject_withNameCollision_appendsSuffixAndSetsRenamedDueToConflictMetadata() {
        saveActive(USER_ID, "Acme");
        Project deleted = saveSoftDeleted(USER_ID, "Acme");

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + deleted.getId() + "/restore")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("Acme (restored)");

        awaitEvent(e -> "project_restored".equals(e.getEventType()));
        Event evt = findEvent(e -> "project_restored".equals(e.getEventType()));
        assertThat(evt.getMetadata())
                .containsEntry("renamedDueToConflict", true)
                .containsEntry("name", "Acme (restored)");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void restoreProject_atQuotaLimit_returns422() {
        for (int i = 1; i <= 5; i++) saveActive(USER_ID, "Proj" + i);
        Project deleted = saveSoftDeleted(USER_ID, "Proj-deleted");

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + deleted.getId() + "/restore")
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("project_limit_reached");
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void restoreProject_onActiveProject_returns404AndDoesNotEmitRenameOrRestoreEvent() {
        Project active = saveActive(USER_ID, "Acme");

        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects/" + active.getId() + "/restore")
                .exchange()
                .expectStatus().isNotFound();

        // Decision 14 deletedAt-FIRST guard: NEITHER of these events should be persisted for
        // this project's id.
        List<Event> all = eventRepository.findAll().collectList().block();
        assertThat(all).noneMatch(e -> "project_renamed".equals(e.getEventType())
                && active.getId().equals(e.getMetadata() == null ? null : e.getMetadata().get("projectId")));
        assertThat(all).noneMatch(e -> "project_restored".equals(e.getEventType())
                && active.getId().equals(e.getMetadata() == null ? null : e.getMetadata().get("projectId")));
    }

    // ---------- AC-T1 error body shape ----------

    @Test
    @WithMockAppUser(userId = USER_ID)
    void errorBodies_alwaysHaveMessageAndCode() {
        // 400 (bean validation): message non-blank; code field absent or null.
        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "", "timezone", "Europe/Kyiv"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.message").value(s -> assertThat((String) s).isNotBlank());

        // 404 (anti-enumeration): message non-blank; code null.
        webTestClient.get().uri("/api/v1/projects/zzz-not-an-objectid")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.message").value(s -> assertThat((String) s).isNotBlank());

        // 409 (name conflict): message non-blank; code = project_name_taken.
        saveActive(USER_ID, "Acme");
        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Acme", "timezone", "Europe/Kyiv"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.message").value(s -> assertThat((String) s).isNotBlank())
                .jsonPath("$.code").isEqualTo("project_name_taken");
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
