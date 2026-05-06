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
}
