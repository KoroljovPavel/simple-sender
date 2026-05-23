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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(p));

        assertThat(projectService.requireOwned(OWNER_ID, PROJECT_ID, false)).isSameAs(p);
    }

    @Test
    void requireOwned_foreignProject_throws404() {
        Project foreign = sampleActive("Foreign");
        foreign.setOwnerId("someone-else");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> projectService.requireOwned(OWNER_ID, PROJECT_ID, false))
                .isInstanceOf(AppException.class)
                .extracting(t -> ((AppException) t).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void requireOwned_softDeletedWithoutFlag_throws404() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(sampleSoftDeleted("Acme")));

        assertThatThrownBy(() -> projectService.requireOwned(OWNER_ID, PROJECT_ID, false))
                .isInstanceOf(AppException.class)
                .extracting(t -> ((AppException) t).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void requireOwned_softDeletedWithFlag_returnsProject() {
        Project p = sampleSoftDeleted("Acme");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(p));

        assertThat(projectService.requireOwned(OWNER_ID, PROJECT_ID, true)).isSameAs(p);
    }

    @Test
    void requireOwned_missingProject_throws404() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.requireOwned(OWNER_ID, PROJECT_ID, false))
                .isInstanceOf(AppException.class)
                .extracting(t -> ((AppException) t).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void requireOwned_malformedObjectId_throws404() {
        when(projectRepository.findById("malformed"))
                .thenThrow(new IllegalArgumentException("invalid id"));

        assertThatThrownBy(() -> projectService.requireOwned(OWNER_ID, "malformed", false))
                .isInstanceOf(AppException.class)
                .extracting(t -> ((AppException) t).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --------- create ---------

    @Test
    void create_happyPath_savesAndEmitsProjectCreatedEvent() {
        when(projectRepository.countByOwnerIdAndDeletedAtIsNull(OWNER_ID)).thenReturn(0L);
        when(projectRepository.findByOwnerIdAndNameAndDeletedAtIsNull(OWNER_ID, "Acme"))
                .thenReturn(Optional.empty());
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            p.setId(PROJECT_ID);
            return p;
        });

        CreateProjectRequest dto = new CreateProjectRequest("Acme", null, "Europe/Kyiv");

        Project saved = projectService.create(OWNER_ID, dto, IP, UA);
        assertThat(saved.getId()).isEqualTo(PROJECT_ID);
        assertThat(saved.getOwnerId()).isEqualTo(OWNER_ID);
        assertThat(saved.getName()).isEqualTo("Acme");
        assertThat(saved.getDeletedAt()).isNull();

        ArgumentCaptor<Map<String, Object>> meta = metadataCaptor();
        verify(eventService).logEvent(eq(OWNER_ID), eq("project_created"),
                eq(IP), eq(UA), meta.capture());
        assertThat(meta.getValue()).containsEntry("projectId", PROJECT_ID).containsEntry("name", "Acme");
    }

    @Test
    void create_atQuotaLimit_throws422WithProjectLimitReachedCode() {
        when(projectRepository.countByOwnerIdAndDeletedAtIsNull(OWNER_ID))
                .thenReturn((long) MAX_PER_USER);

        CreateProjectRequest dto = new CreateProjectRequest("Acme", null, "Europe/Kyiv");

        assertThatThrownBy(() -> projectService.create(OWNER_ID, dto, IP, UA))
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getCode()).isEqualTo("project_limit_reached");
                });
        verify(eventService, never()).logEvent(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void create_duplicateActiveName_throws409WithProjectNameTakenCode() {
        when(projectRepository.countByOwnerIdAndDeletedAtIsNull(OWNER_ID)).thenReturn(2L);
        when(projectRepository.findByOwnerIdAndNameAndDeletedAtIsNull(OWNER_ID, "Acme"))
                .thenReturn(Optional.of(sampleActive("Acme")));

        CreateProjectRequest dto = new CreateProjectRequest("Acme", null, "Europe/Kyiv");

        assertThatThrownBy(() -> projectService.create(OWNER_ID, dto, IP, UA))
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("project_name_taken");
                });
    }

    @Test
    void create_duplicateSoftDeletedName_succeeds() {
        // Pre-check (findByOwnerIdAndNameAndDeletedAtIsNull) only matches active rows; an existing
        // soft-deleted row with the same name does not collide — service let it through.
        when(projectRepository.countByOwnerIdAndDeletedAtIsNull(OWNER_ID)).thenReturn(0L);
        when(projectRepository.findByOwnerIdAndNameAndDeletedAtIsNull(OWNER_ID, "Acme"))
                .thenReturn(Optional.empty());
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            p.setId(PROJECT_ID);
            return p;
        });

        CreateProjectRequest dto = new CreateProjectRequest("Acme", null, "Europe/Kyiv");

        Project saved = projectService.create(OWNER_ID, dto, IP, UA);
        assertThat(saved.getName()).isEqualTo("Acme");
    }

    // --------- list ---------

    @Test
    void list_excludeDeleted_returnsActiveOnly() {
        Project a = sampleActive("A");
        Project b = sampleActive("B");
        when(projectRepository.findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(OWNER_ID))
                .thenReturn(List.of(b, a));

        List<Project> result = projectService.list(OWNER_ID, false);
        assertThat(result).containsExactly(b, a);
    }

    @Test
    void list_includeDeleted_returnsAll() {
        Project a = sampleActive("A");
        Project deleted = sampleSoftDeleted("Deleted");
        when(projectRepository.findByOwnerIdOrderByCreatedAtDesc(OWNER_ID))
                .thenReturn(List.of(a, deleted));

        List<Project> result = projectService.list(OWNER_ID, true);
        assertThat(result).containsExactly(a, deleted);
    }

    // --------- update ---------

    @Test
    void update_renameHappy_emitsProjectRenamedWithPreviousName() {
        Project before = sampleActive("Old");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(before));
        when(projectRepository.findByOwnerIdAndNameAndIdNotAndDeletedAtIsNull(OWNER_ID, "New", PROJECT_ID))
                .thenReturn(Optional.empty());
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateProjectRequest dto = new UpdateProjectRequest("New", null, null);

        Project saved = projectService.update(OWNER_ID, PROJECT_ID, dto, IP, UA);
        assertThat(saved.getName()).isEqualTo("New");

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
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(before));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateProjectRequest dto = new UpdateProjectRequest(null, "fresh", null);

        Project saved = projectService.update(OWNER_ID, PROJECT_ID, dto, IP, UA);
        assertThat(saved.getDescription()).isEqualTo("fresh");

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
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(before));
        when(projectRepository.findByOwnerIdAndNameAndIdNotAndDeletedAtIsNull(OWNER_ID, "Taken", PROJECT_ID))
                .thenReturn(Optional.of(sampleActive("Taken")));

        UpdateProjectRequest dto = new UpdateProjectRequest("Taken", null, null);

        assertThatThrownBy(() -> projectService.update(OWNER_ID, PROJECT_ID, dto, IP, UA))
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("project_name_taken");
                });
    }

    @Test
    void update_noOpRenameSameName_doesNotThrow409() {
        // AC-12b allowance: rename to current name does not invoke conflict check (still hits
        // the "no name change" branch via .equals — this also doubles as the "case-equivalent"
        // no-op assertion). Metadata shape is locked to projectId-only and the rename branch
        // is verified to never fire — distinct from the "name field absent from DTO" path.
        Project before = sampleActive("Acme");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(before));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateProjectRequest dto = new UpdateProjectRequest("Acme", null, null);

        Project saved = projectService.update(OWNER_ID, PROJECT_ID, dto, IP, UA);
        assertThat(saved.getName()).isEqualTo("Acme");

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
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(active));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        Project saved = projectService.softDelete(OWNER_ID, PROJECT_ID, IP, UA);
        assertThat(saved.getDeletedAt()).isNotNull();

        ArgumentCaptor<Map<String, Object>> meta = metadataCaptor();
        verify(eventService).logEvent(eq(OWNER_ID), eq("project_soft_deleted"),
                eq(IP), eq(UA), meta.capture());
        assertThat(meta.getValue()).containsEntry("projectId", PROJECT_ID).containsEntry("name", "Acme");
    }

    @Test
    void softDelete_alreadySoftDeleted_throws404() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(sampleSoftDeleted("Acme")));

        assertThatThrownBy(() -> projectService.softDelete(OWNER_ID, PROJECT_ID, IP, UA))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(eventService, never()).logEvent(anyString(), anyString(),
                anyString(), anyString(), any());
    }

    // --------- restore ---------

    @Test
    void restore_happyPath_clearsDeletedAtAndEmitsEvent() {
        Project deleted = sampleSoftDeleted("Acme");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(deleted));
        when(projectRepository.countByOwnerIdAndDeletedAtIsNull(OWNER_ID)).thenReturn(0L);
        when(projectRepository.findByOwnerIdAndNameAndIdNotAndDeletedAtIsNull(OWNER_ID, "Acme", PROJECT_ID))
                .thenReturn(Optional.empty());
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        Project saved = projectService.restore(OWNER_ID, PROJECT_ID, IP, UA);
        assertThat(saved.getDeletedAt()).isNull();
        assertThat(saved.getName()).isEqualTo("Acme");

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
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> projectService.restore(OWNER_ID, PROJECT_ID, IP, UA))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(eventService, never()).logEvent(anyString(), eq("project_renamed"),
                anyString(), anyString(), any());
        verify(eventService, never()).logEvent(anyString(), eq("project_restored"),
                anyString(), anyString(), any());
    }

    @Test
    void restore_atQuotaLimit_throws422() {
        Project deleted = sampleSoftDeleted("Acme");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(deleted));
        when(projectRepository.countByOwnerIdAndDeletedAtIsNull(OWNER_ID))
                .thenReturn((long) MAX_PER_USER);

        assertThatThrownBy(() -> projectService.restore(OWNER_ID, PROJECT_ID, IP, UA))
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getCode()).isEqualTo("project_limit_reached");
                });
        verify(eventService, never()).logEvent(anyString(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    void restore_withNameConflict_appendsRestoredSuffixAndSetsMetadataFlag() {
        Project deleted = sampleSoftDeleted("Acme");
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(deleted));
        when(projectRepository.countByOwnerIdAndDeletedAtIsNull(OWNER_ID)).thenReturn(1L);
        when(projectRepository.findByOwnerIdAndNameAndIdNotAndDeletedAtIsNull(OWNER_ID, "Acme", PROJECT_ID))
                .thenReturn(Optional.of(sampleActive("Acme")));
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        Project saved = projectService.restore(OWNER_ID, PROJECT_ID, IP, UA);
        assertThat(saved.getName()).isEqualTo("Acme (restored)");
        assertThat(saved.getDeletedAt()).isNull();

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
