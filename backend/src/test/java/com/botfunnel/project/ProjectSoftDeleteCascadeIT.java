package com.botfunnel.project;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.common.AppException;
import com.botfunnel.jobs.ProjectHardDeleteJob;
import com.botfunnel.profile.WithMockAppUser;
import com.botfunnel.subscriber.Subscriber;
import com.botfunnel.subscriber.SubscriberStatus;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import com.mongodb.client.gridfs.model.GridFSFile;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsOperations;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression guard for the user-spec AC22 soft-delete invariant: while a project is soft-deleted
 * (but not yet hard-deleted), every subscriber-CRM list endpoint collapses to HTTP 404 via
 * {@link ProjectService#requireOwned} (the standard anti-enumeration not-found mapping for
 * soft-deleted / foreign / missing projects).
 *
 * <p>See {@code SubscriberExportSignedUrlIT} (Task 9) for the signed-download 410
 * {@code export_project_unavailable} case — that branch lives in the download endpoint's
 * post-token pre-stream check, NOT in {@code requireOwned} (which returns 404). It is deliberately
 * out of scope here.
 */
class ProjectSoftDeleteCascadeIT extends AbstractIntegrationTest {

    private static final String USER_ID = "soft-delete-cascade-user";

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectService projectService;
    @Autowired ProjectHardDeleteJob job;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired GridFsOperations gridFsOperations;

    private String softDeletedProjectId;

    @BeforeEach
    void cleanAndSeed() {
        userRepository.deleteAll();
        projectRepository.deleteAll();
        mongoTemplate.remove(new Query(), Subscriber.class);
        gridFsOperations.delete(new Query());

        User u = new User();
        u.setId(USER_ID);
        u.setEmail("soft-delete@test.com");
        u.setName("Owner");
        u.setPasswordHash("not-used");
        u.setStatus(UserStatus.active);
        u.setSuperAdmin(false);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);

        // Owned by USER_ID + soft-deleted (deletedAt set) so the assertions prove the soft-delete
        // branch of requireOwned specifically — not the foreign-owner branch (both map to 404).
        softDeletedProjectId = saveSoftDeleted(USER_ID, Instant.now()).getId();
    }

    private Project saveSoftDeleted(String ownerId, Instant deletedAt) {
        Project p = new Project();
        p.setOwnerId(ownerId);
        p.setName("Proj-" + System.nanoTime());
        p.setTimezone("Europe/Kyiv");
        p.setCreatedAt(deletedAt.minus(30, ChronoUnit.DAYS));
        p.setUpdatedAt(deletedAt);
        p.setDeletedAt(deletedAt);
        return projectRepository.save(p);
    }

    @Test
    @WithMockAppUser(userId = USER_ID)
    void softDeletedProject_tagsListEndpoint_returns404() throws Exception {
        // Tag list endpoint exists (Task 4) — assert the real HTTP surface returns 404 for a
        // soft-deleted project via requireOwned's not-found mapping.
        mockMvc.perform(get("/api/v1/projects/" + softDeletedProjectId + "/tags"))
                .andExpect(status().isNotFound());
    }

    @Test
    void softDeletedProject_subscribersListEndpoint_returns404() {
        // TODO Task 8 — replace with a full controller IT (GET /subscribers) once the subscriber
        // list endpoint lands. The subscriber list controller is not wired at this task's execution
        // time, so assert the actual requireOwned contract directly: soft-deleted project → 404.
        assertThatThrownBy(() -> projectService.requireOwned(USER_ID, softDeletedProjectId, false))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void softThenHardDelete_drainsAllCascadeCollections() {
        // End-to-end linkage between the two halves of AC22: a soft-deleted project past the 7d
        // grace window, once hard-deleted, drags its subscriber-domain rows + GridFS blob with it.
        // The exhaustive per-collection cascade proof lives in ProjectHardDeleteJobIT; this is the
        // soft → hard → drained smoke that ties the soft-delete contract to the cascade job.
        Project old = saveSoftDeleted(USER_ID, Instant.now().minus(8, ChronoUnit.DAYS));

        Subscriber s = new Subscriber();
        s.setProjectId(old.getId());
        s.setTelegramUserId(System.nanoTime());
        s.setTelegramChatId(System.nanoTime());
        s.setStatus(SubscriberStatus.ACTIVE);
        s.setSubscribedAt(Instant.now());
        s.setLastSeenAt(Instant.now());
        mongoTemplate.save(s);

        gridFsOperations.store(
                new ByteArrayInputStream("col1\nval\n".getBytes(StandardCharsets.UTF_8)),
                "export.csv", new Document("projectId", old.getId()).append("exportId", "e1"));

        job.hardDeleteSoftDeletedProjects();

        assertThat(projectRepository.findById(old.getId()).orElse(null)).isNull();
        assertThat(mongoTemplate.count(
                Query.query(Criteria.where("projectId").is(old.getId())), Subscriber.class)).isZero();
        GridFSFile blob = gridFsOperations.findOne(
                Query.query(Criteria.where("metadata.projectId").is(old.getId())));
        assertThat(blob).isNull();
    }
}
