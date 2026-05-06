package com.botfunnel.admin;

import com.botfunnel.user.User;
import com.botfunnel.user.UserRepository;
import com.botfunnel.user.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;

// Idempotent on every startup. Errors are logged and never propagated — the app must boot
// even if seeding fails (e.g., MongoDB transiently unavailable). Promotion path is needed so
// an existing operator account can be granted superadmin without recreating the user.
@Component
public class SuperAdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;

    public SuperAdminSeeder(UserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            @Value("${app.super-admin.email:}") String adminEmail,
                            @Value("${app.super-admin.password:}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seed();
        } catch (Exception ex) {
            log.error("Super admin seeding failed: {}", ex.getMessage(), ex);
        }
    }

    private void seed() {
        if (adminEmail == null || adminEmail.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            log.warn("Super admin seed skipped — SUPER_ADMIN_EMAIL or SUPER_ADMIN_PASSWORD not set");
            return;
        }
        String email = adminEmail.trim().toLowerCase(Locale.ROOT);

        User existing = userRepository.findByEmail(email).block();
        if (existing == null) {
            User user = new User();
            Instant now = Instant.now();
            user.setEmail(email);
            user.setName("Super Admin");
            user.setPasswordHash(passwordEncoder.encode(adminPassword));
            user.setStatus(UserStatus.active);
            user.setSuperAdmin(true);
            user.setCreatedAt(now);
            user.setUpdatedAt(now);
            userRepository.save(user).block();
            log.info("Super admin created: {}", email);
            return;
        }
        if (existing.isSuperAdmin()) {
            log.info("Super admin already present: {}", email);
            return;
        }
        existing.setSuperAdmin(true);
        existing.setUpdatedAt(Instant.now());
        userRepository.save(existing).block();
        log.info("Existing user promoted to super admin: {}", email);
    }
}
