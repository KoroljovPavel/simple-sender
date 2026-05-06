package com.botfunnel.jobs;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HardDeleteJobTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired HardDeleteJob hardDeleteJob;

    @BeforeEach
    void cleanState() {
        userRepository.deleteAll().block();
    }

    private User seedDeleted(String email, Instant deletedAt) {
        User u = new User();
        u.setEmail(email);
        u.setName("Test " + email);
        u.setPasswordHash("$2a$12$dummyhash");
        u.setStatus(UserStatus.deleted);
        u.setDeletedAt(deletedAt);
        u.setCreatedAt(deletedAt.minus(Duration.ofDays(60)));
        u.setUpdatedAt(deletedAt);
        return userRepository.save(u).block();
    }

    private User seedActive(String email) {
        User u = new User();
        u.setEmail(email);
        u.setName("Active " + email);
        u.setPasswordHash("$2a$12$dummyhash");
        u.setStatus(UserStatus.active);
        u.setCreatedAt(Instant.now().minus(Duration.ofDays(40)));
        u.setUpdatedAt(Instant.now().minus(Duration.ofDays(40)));
        return userRepository.save(u).block();
    }

    @Test
    void hardDelete_onlyDeletesUsersOlderThan30Days() {
        User old = seedDeleted("old@test.com", Instant.now().minus(Duration.ofDays(31)));
        User recent = seedDeleted("recent@test.com", Instant.now().minus(Duration.ofDays(29)));

        hardDeleteJob.hardDeleteSoftDeletedUsers();

        List<User> remaining = userRepository.findAll().collectList().block();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getId()).isEqualTo(recent.getId());
        assertThat(userRepository.findById(old.getId()).block()).isNull();
    }

    @Test
    void hardDelete_doesNotTouchActiveOrPendingUsers() {
        // Even an active user older than 30 days must not be deleted — only soft-deleted users
        // qualify (status filter on the repository query).
        User active = seedActive("alive@test.com");

        hardDeleteJob.hardDeleteSoftDeletedUsers();

        assertThat(userRepository.findById(active.getId()).block()).isNotNull();
    }

    @Test
    void hardDelete_emptyResultSet_doesNotThrow() {
        // Standalone "no work to do" run — must complete without error and leave state untouched.
        hardDeleteJob.hardDeleteSoftDeletedUsers();
        assertThat(userRepository.count().block()).isZero();
    }

    @Test
    void hardDelete_exactly30DaysAgo_isRemoved() {
        // AC line 81: deletedAt <= now - 30d. The job adds 1ns to the cutoff so a user whose
        // deletedAt timestamp is within "30 days minus 1ns to 30 days plus 1ns" of the current
        // time is correctly captured by the strict-less-than finder. Without the +1ns bump,
        // a user soft-deleted 30d ago to the second would survive an extra day.
        User exactly = seedDeleted("exact@test.com", Instant.now().minus(Duration.ofDays(30)));

        hardDeleteJob.hardDeleteSoftDeletedUsers();

        assertThat(userRepository.findById(exactly.getId()).block())
                .as("user soft-deleted exactly 30d ago must be hard-deleted on this run")
                .isNull();
    }
}
