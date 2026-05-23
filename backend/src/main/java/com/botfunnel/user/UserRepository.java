package com.botfunnel.user;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends MongoRepository<User, String> {

    Optional<User> findByEmail(String email);

    Optional<User> findByEmailVerificationTokenHash(String hash);

    Optional<User> findByPasswordResetTokenHash(String hash);

    List<User> findByStatusAndDeletedAtBefore(UserStatus status, Instant cutoff);
}
