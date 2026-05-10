package com.botfunnel.project;

import com.botfunnel.common.AppException;
import com.botfunnel.events.EventService;
import com.botfunnel.project.dto.CreateProjectRequest;
import com.botfunnel.project.dto.UpdateProjectRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    private static final String OWNER_ID = "owner-123";
    private static final String PROJECT_ID = "project-abc";
    private static final String IP = "127.0.0.1";
    private static final String UA = "JUnit";
    private static final int MAX_PER_USER = 5;

    @Mock ProjectRepository projectRepository;
    @Mock EventService eventService;

    ProjectService projectService;

    @BeforeEach
    void initService() {
        projectService = new ProjectService(projectRepository, eventService, MAX_PER_USER);
    }

    private Project sampleActive(String name) {
        Project p = new Project();
        p.setId(PROJECT_ID);
        p.setOwnerId(OWNER_ID);
        p.setName(name);
        p.setDescription(null);
        p.setTimezone("Europe/Kyiv");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        p.setDeletedAt(null);
        return p;
    }

    private Project sampleSoftDeleted(String name) {
        Project p = sampleActive(name);
        p.setDeletedAt(Instant.now());
        return p;
    }

    // --------- requireOwned ---------

    @Test
    void requireOwned_ownProjectActive_returnsProject() {
        Project p = sampleActive("Acme");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Mono.just(p));

        StepVerifier.create(projectService.requireOwned(OWNER_ID, PROJECT_ID, false))
                .expectNext(p)
                .verifyComplete();
    }

    @Test
    void requireOwned_foreignProject_throws404() {
        Project foreign = sampleActive("Foreign");
        foreign.setOwnerId("someone-else");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Mono.just(foreign));

        StepVerifier.create(projectService.requireOwned(OWNER_ID, PROJECT_ID, false))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(AppException.class);
                    assertThat(((AppException) err).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                })
                .verify();
    }

    @Test
    void requireOwned_softDeletedWithoutFlag_throws404() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Mono.just(sampleSoftDeleted("Acme")));

        StepVerifier.create(projectService.requireOwned(OWNER_ID, PROJECT_ID, false))
                .expectErrorSatisfies(err -> assertThat(((AppException) err).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    @Test
    void requireOwned_softDeletedWithFlag_returnsProject() {
        Project p = sampleSoftDeleted("Acme");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Mono.just(p));

        StepVerifier.create(projectService.requireOwned(OWNER_ID, PROJECT_ID, true))
                .expectNext(p)
                .verifyComplete();
    }

    @Test
    void requireOwned_missingProject_throws404() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(projectService.requireOwned(OWNER_ID, PROJECT_ID, false))
                .expectErrorSatisfies(err -> assertThat(((AppException) err).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();
    }

    @Test
    void requireOwned_malformedObjectId_throws404() {
        when(projectRepository.findById("malformed"))
                .thenReturn(Mono.error(new IllegalArgumentException("invalid id")));

        StepVerifier.create(projectService.requireOwned(OWNER_ID, "malformed", false))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(AppException.class);
                    assertThat(((AppException) err).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                })
                .verify();
    }

    // --------- create ---------

    @Test
    void create_happyPath_savesAndEmitsProjectCreatedEvent() {
        when(projectRepository.countByOwnerIdAndDeletedAtIsNull(OWNER_ID)).thenReturn(Mono.just(0L));
        when(projectRepository.findByOwnerIdAndNameAndDeletedAtIsNull(OWNER_ID, "Acme"))
                .thenReturn(Mono.empty());
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            p.setId(PROJECT_ID);
            return Mono.just(p);
        });

        CreateProjectRequest dto = new CreateProjectRequest("Acme", null, "Europe/Kyiv");

        StepVerifier.create(projectService.create(OWNER_ID, dto, IP, UA))
                .assertNext(saved -> {
                    assertThat(saved.getId()).isEqualTo(PROJECT_ID);
                    assertThat(saved.getOwnerId()).isEqualTo(OWNER_ID);
                    assertThat(saved.getName()).isEqualTo("Acme");
                    assertThat(saved.getDeletedAt()).isNull();
                })
                .verifyComplete();

        ArgumentCaptor<Map<String, Object>> meta = metadataCaptor();
        verify(eventService).logEvent(eq(OWNER_ID), eq("project_created"),
                eq(IP), eq(UA), meta.capture());
        assertThat(meta.getValue()).containsEntry("projectId", PROJECT_ID).containsEntry("name", "Acme");
    }

    @Test
    void create_atQuotaLimit_throws422WithProjectLimitReachedCode() {
        when(projectRepository.countByOwnerIdAndDeletedAtIsNull(OWNER_ID))
                .thenReturn(Mono.just((long) MAX_PER_USER));

        CreateProjectRequest dto = new CreateProjectRequest("Acme", null, "Europe/Kyiv");

        StepVerifier.create(projectService.create(OWNER_ID, dto, IP, UA))
                .expectErrorSatisfies(err -> {
                    AppException ex = (AppException) err;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getCode()).isEqualTo("project_limit_reached");
                })
                .verify();
        verify(eventService, never()).logEvent(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void create_duplicateActiveName_throws409WithProjectNameTakenCode() {
        when(projectRepository.countByOwnerIdAndDeletedAtIsNull(OWNER_ID)).thenReturn(Mono.just(2L));
        when(projectRepository.findByOwnerIdAndNameAndDeletedAtIsNull(OWNER_ID, "Acme"))
                .thenReturn(Mono.just(sampleActive("Acme")));

        CreateProjectRequest dto = new CreateProjectRequest("Acme", null, "Europe/Kyiv");

        StepVerifier.create(projectService.create(OWNER_ID, dto, IP, UA))
                .expectErrorSatisfies(err -> {
                    AppException ex = (AppException) err;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("project_name_taken");
                })
                .verify();
    }

    @Test
    void create_duplicateSoftDeletedName_succeeds() {
        // Pre-check (findByOwnerIdAndNameAndDeletedAtIsNull) only matches active rows; an existing
        // soft-deleted row with the same name does not collide — service let it through.
        when(projectRepository.countByOwnerIdAndDeletedAtIsNull(OWNER_ID)).thenReturn(Mono.just(0L));
        when(projectRepository.findByOwnerIdAndNameAndDeletedAtIsNull(OWNER_ID, "Acme"))
                .thenReturn(Mono.empty());
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            p.setId(PROJECT_ID);
            return Mono.just(p);
        });

        CreateProjectRequest dto = new CreateProjectRequest("Acme", null, "Europe/Kyiv");

        StepVerifier.create(projectService.create(OWNER_ID, dto, IP, UA))
                .assertNext(saved -> assertThat(saved.getName()).isEqualTo("Acme"))
                .verifyComplete();
    }

    // --------- update ---------

    @Test
    void update_renameHappy_emitsProjectRenamedWithPreviousName() {
        Project before = sampleActive("Old");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Mono.just(before));
        when(projectRepository.findByOwnerIdAndNameAndIdNotAndDeletedAtIsNull(OWNER_ID, "New", PROJECT_ID))
                .thenReturn(Mono.empty());
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        UpdateProjectRequest dto = new UpdateProjectRequest("New", null, null);

        StepVerifier.create(projectService.update(OWNER_ID, PROJECT_ID, dto, IP, UA))
                .assertNext(saved -> assertThat(saved.getName()).isEqualTo("New"))
                .verifyComplete();

        ArgumentCaptor<Map<String, Object>> meta = metadataCaptor();
        verify(eventService).logEvent(eq(OWNER_ID), eq("project_renamed"),
                eq(IP), eq(UA), meta.capture());
        assertThat(meta.getValue())
                .containsEntry("projectId", PROJECT_ID)
                .containsEntry("previousName", "Old")
                .containsEntry("name", "New");
    }

    @Test
    void update_noNameChange_emitsProjectUpdatedWithoutNameOrPreviousName() {
        Project before = sampleActive("Acme");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Mono.just(before));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        UpdateProjectRequest dto = new UpdateProjectRequest(null, "fresh", null);

        StepVerifier.create(projectService.update(OWNER_ID, PROJECT_ID, dto, IP, UA))
                .assertNext(saved -> assertThat(saved.getDescription()).isEqualTo("fresh"))
                .verifyComplete();

        ArgumentCaptor<Map<String, Object>> meta = metadataCaptor();
        verify(eventService).logEvent(eq(OWNER_ID), eq("project_updated"),
                eq(IP), eq(UA), meta.capture());
        assertThat(meta.getValue()).containsOnlyKeys("projectId");
        verify(eventService, never()).logEvent(anyString(), eq("project_renamed"),
                anyString(), anyString(), any());
    }

    @Test
    void update_renameToExistingActiveName_throws409WithProjectNameTakenCode() {
        Project before = sampleActive("Old");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Mono.just(before));
        when(projectRepository.findByOwnerIdAndNameAndIdNotAndDeletedAtIsNull(OWNER_ID, "Taken", PROJECT_ID))
                .thenReturn(Mono.just(sampleActive("Taken")));

        UpdateProjectRequest dto = new UpdateProjectRequest("Taken", null, null);

        StepVerifier.create(projectService.update(OWNER_ID, PROJECT_ID, dto, IP, UA))
                .expectErrorSatisfies(err -> {
                    AppException ex = (AppException) err;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("project_name_taken");
                })
                .verify();
    }

    @Test
    void update_noOpRenameSameName_doesNotThrow409() {
        // AC-12b allowance: rename to current name does not invoke conflict check (still hits
        // the "no name change" branch via .equals — this also doubles as the "case-equivalent"
        // no-op assertion). Metadata shape is locked to projectId-only and the rename branch
        // is verified to never fire — distinct from the "name field absent from DTO" path.
        Project before = sampleActive("Acme");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Mono.just(before));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        UpdateProjectRequest dto = new UpdateProjectRequest("Acme", null, null);

        StepVerifier.create(projectService.update(OWNER_ID, PROJECT_ID, dto, IP, UA))
                .assertNext(saved -> assertThat(saved.getName()).isEqualTo("Acme"))
                .verifyComplete();

        ArgumentCaptor<Map<String, Object>> meta = metadataCaptor();
        verify(eventService).logEvent(eq(OWNER_ID), eq("project_updated"),
                eq(IP), eq(UA), meta.capture());
        assertThat(meta.getValue()).containsOnlyKeys("projectId");
        verify(eventService, never()).logEvent(anyString(), eq("project_renamed"),
                anyString(), anyString(), any());
    }

    // --------- softDelete ---------

    @Test
    void softDelete_happy_setsDeletedAtAndEmitsEvent() {
        Project active = sampleActive("Acme");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Mono.just(active));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(projectService.softDelete(OWNER_ID, PROJECT_ID, IP, UA))
                .assertNext(saved -> assertThat(saved.getDeletedAt()).isNotNull())
                .verifyComplete();

        ArgumentCaptor<Map<String, Object>> meta = metadataCaptor();
        verify(eventService).logEvent(eq(OWNER_ID), eq("project_soft_deleted"),
                eq(IP), eq(UA), meta.capture());
        assertThat(meta.getValue()).containsEntry("projectId", PROJECT_ID).containsEntry("name", "Acme");
    }

    @Test
    void softDelete_alreadySoftDeleted_throws404() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Mono.just(sampleSoftDeleted("Acme")));

        StepVerifier.create(projectService.softDelete(OWNER_ID, PROJECT_ID, IP, UA))
                .expectErrorSatisfies(err -> assertThat(((AppException) err).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verify(eventService, never()).logEvent(anyString(), anyString(),
                anyString(), anyString(), any());
    }

    // --------- restore ---------

    @Test
    void restore_happyPath_clearsDeletedAtAndEmitsEvent() {
        Project deleted = sampleSoftDeleted("Acme");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Mono.just(deleted));
        when(projectRepository.countByOwnerIdAndDeletedAtIsNull(OWNER_ID)).thenReturn(Mono.just(0L));
        when(projectRepository.findByOwnerIdAndNameAndIdNotAndDeletedAtIsNull(OWNER_ID, "Acme", PROJECT_ID))
                .thenReturn(Mono.empty());
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(projectService.restore(OWNER_ID, PROJECT_ID, IP, UA))
                .assertNext(saved -> {
                    assertThat(saved.getDeletedAt()).isNull();
                    assertThat(saved.getName()).isEqualTo("Acme");
                })
                .verifyComplete();

        ArgumentCaptor<Map<String, Object>> meta = metadataCaptor();
        verify(eventService).logEvent(eq(OWNER_ID), eq("project_restored"),
                eq(IP), eq(UA), meta.capture());
        assertThat(meta.getValue())
                .containsEntry("projectId", PROJECT_ID)
                .containsEntry("name", "Acme")
                .doesNotContainKey("renamedDueToConflict");
    }

    @Test
    void restore_onActiveProject_throws404AndDoesNotEmitRenameOrRestore() {
        // Decision 14: deletedAt-FIRST guard. An active project hitting restore must 404 with
        // ZERO mutations and ZERO audit events — verifies the guard runs BEFORE quota check,
        // BEFORE rename logic, and BEFORE save.
        Project active = sampleActive("Acme");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Mono.just(active));

        StepVerifier.create(projectService.restore(OWNER_ID, PROJECT_ID, IP, UA))
                .expectErrorSatisfies(err -> assertThat(((AppException) err).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .verify();

        verify(eventService, never()).logEvent(anyString(), eq("project_renamed"),
                anyString(), anyString(), any());
        verify(eventService, never()).logEvent(anyString(), eq("project_restored"),
                anyString(), anyString(), any());
    }

    @Test
    void restore_atQuotaLimit_throws422() {
        Project deleted = sampleSoftDeleted("Acme");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Mono.just(deleted));
        when(projectRepository.countByOwnerIdAndDeletedAtIsNull(OWNER_ID))
                .thenReturn(Mono.just((long) MAX_PER_USER));

        StepVerifier.create(projectService.restore(OWNER_ID, PROJECT_ID, IP, UA))
                .expectErrorSatisfies(err -> {
                    AppException ex = (AppException) err;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getCode()).isEqualTo("project_limit_reached");
                })
                .verify();
        verify(eventService, never()).logEvent(anyString(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    void restore_withNameConflict_appendsRestoredSuffixAndSetsMetadataFlag() {
        Project deleted = sampleSoftDeleted("Acme");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Mono.just(deleted));
        when(projectRepository.countByOwnerIdAndDeletedAtIsNull(OWNER_ID)).thenReturn(Mono.just(1L));
        when(projectRepository.findByOwnerIdAndNameAndIdNotAndDeletedAtIsNull(OWNER_ID, "Acme", PROJECT_ID))
                .thenReturn(Mono.just(sampleActive("Acme")));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(projectService.restore(OWNER_ID, PROJECT_ID, IP, UA))
                .assertNext(saved -> {
                    assertThat(saved.getName()).isEqualTo("Acme (restored)");
                    assertThat(saved.getDeletedAt()).isNull();
                })
                .verifyComplete();

        ArgumentCaptor<Map<String, Object>> meta = metadataCaptor();
        verify(eventService).logEvent(eq(OWNER_ID), eq("project_restored"),
                eq(IP), eq(UA), meta.capture());
        assertThat(meta.getValue()).containsEntry("renamedDueToConflict", true);
        assertThat(meta.getValue()).containsEntry("name", "Acme (restored)");
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> metadataCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
