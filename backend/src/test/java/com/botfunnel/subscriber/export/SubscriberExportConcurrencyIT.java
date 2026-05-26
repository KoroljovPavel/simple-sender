package com.botfunnel.subscriber.export;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.auth.AppUserDetails;
import com.botfunnel.common.AppException;
import com.botfunnel.common.test.ConcurrencyTestUtils;
import com.botfunnel.project.Project;
import com.botfunnel.project.ProjectRepository;
import com.botfunnel.subscriber.dto.CreateExportRequest;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriberExportConcurrencyIT extends AbstractIntegrationTest {

    private static final String USER_ID = "exp-conc-user";

    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired MongoTemplate mongoTemplate;
    @Autowired StorageProvider storageProvider;
    @Autowired SubscriberExportController controller;

    private String projectId;

    @BeforeEach
    void cleanAndSeed() {
        userRepository.deleteAll();
        projectRepository.deleteAll();
        mongoTemplate.remove(new Query(), SubscriberExport.class);

        User u = new User();
        u.setId(USER_ID);
        u.setEmail("conc@test.com");
        u.setName("Owner");
        u.setPasswordHash("x");
        u.setStatus(UserStatus.active);
        u.setSuperAdmin(false);
        u.setCreatedAt(Instant.now());
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);

        Project p = new Project();
        p.setOwnerId(USER_ID);
        p.setName("Proj-" + System.nanoTime());
        p.setTimezone("UTC");
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        projectId = projectRepository.save(p).getId();
    }

    @Test
    void parallel2Posts_status202_409_oneRowOneJob() {
        // Two simultaneous POST /export: the (projectId) partial-unique index lets exactly one PENDING
        // row exist, so one request wins (202 + enqueue) and the other maps DuplicateKey → 409.
        long jobsBefore = storageProvider.countJobs(StateName.ENQUEUED);

        List<Integer> statuses = ConcurrencyTestUtils.parallelInvoke(2, () -> {
            SecurityContextHolder.setContext(new org.springframework.security.core.context.SecurityContextImpl(authFor()));
            try {
                ResponseEntity<?> resp = controller.createExport(projectId, new CreateExportRequest(null));
                return resp.getStatusCode().value();
            } catch (AppException e) {
                return e.getStatus().value();
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        assertThat(statuses).containsExactlyInAnyOrder(
                HttpStatus.ACCEPTED.value(), HttpStatus.CONFLICT.value());

        long pending = mongoTemplate.count(
                Query.query(Criteria.where("status").is(ExportStatus.PENDING.name())), SubscriberExport.class);
        assertThat(pending).as("exactly one PENDING export row survives the race").isEqualTo(1L);

        assertThat(storageProvider.countJobs(StateName.ENQUEUED) - jobsBefore)
                .as("only the winning POST enqueues a job").isEqualTo(1L);
    }

    private static Authentication authFor() {
        AppUserDetails principal = new AppUserDetails(USER_ID, "conc@test.com", "Owner", "active");
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }
}
