package com.botfunnel.admin;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SuperAdminSeederTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private static final String EMAIL = "admin@test.com";
    private static final String PASSWORD = "test-admin-password";

    @BeforeEach
    void cleanState() {
        userRepository.deleteAll().block();
    }

    private SuperAdminSeeder seeder() {
        return new SuperAdminSeeder(userRepository, passwordEncoder, EMAIL, PASSWORD);
    }

    @Test
    void seeder_runTwice_exactlyOneAdminRecord() {
        seeder().run(null);
        seeder().run(null);

        long total = userRepository.count().block();
        long admins = userRepository.findAll()
                .filter(u -> u.isSuperAdmin() && EMAIL.equals(u.getEmail()))
                .count()
                .block();
        assertThat(total).isEqualTo(1);
        assertThat(admins).isEqualTo(1);
    }

    @Test
    void seeder_promotesExistingNonAdminUser() {
        // A regular user with the configured admin email already exists.
        User existing = new User();
        existing.setEmail(EMAIL);
        existing.setName("Existing Operator");
        existing.setPasswordHash(passwordEncoder.encode("Other1Pass"));
        existing.setStatus(UserStatus.active);
        existing.setSuperAdmin(false);
        existing.setCreatedAt(Instant.now());
        existing.setUpdatedAt(Instant.now());
        String oldId = userRepository.save(existing).block().getId();

        seeder().run(null);

        long total = userRepository.count().block();
        assertThat(total).as("must NOT create a duplicate user").isEqualTo(1);
        User reread = userRepository.findById(oldId).block();
        assertThat(reread).isNotNull();
        assertThat(reread.isSuperAdmin()).isTrue();
        // Existing data must be preserved — promotion does not rewrite name/password.
        assertThat(reread.getName()).isEqualTo("Existing Operator");
    }

    @Test
    void seeder_missingEmail_doesNotThrow_doesNotCreateRecord() {
        SuperAdminSeeder s = new SuperAdminSeeder(userRepository, passwordEncoder, "", PASSWORD);
        s.run(null);
        assertThat(userRepository.count().block()).isEqualTo(0);
    }

    @Test
    void seeder_missingPassword_doesNotThrow_doesNotCreateRecord() {
        SuperAdminSeeder s = new SuperAdminSeeder(userRepository, passwordEncoder, EMAIL, "");
        s.run(null);
        assertThat(userRepository.count().block()).isEqualTo(0);
    }

    @Test
    void seeder_createsActiveSuperAdminWithBcryptHashedPassword() {
        seeder().run(null);

        User u = userRepository.findByEmail(EMAIL).block();
        assertThat(u).isNotNull();
        assertThat(u.getStatus()).isEqualTo(UserStatus.active);
        assertThat(u.isSuperAdmin()).isTrue();
        // BCrypt hash format ensures plaintext is never persisted.
        assertThat(u.getPasswordHash()).startsWith("$2");
        assertThat(passwordEncoder.matches(PASSWORD, u.getPasswordHash())).isTrue();
    }
}
