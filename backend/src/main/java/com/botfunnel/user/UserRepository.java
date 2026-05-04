package com.botfunnel.user;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface UserRepository extends ReactiveMongoRepository<User, String> {

    Mono<User> findByEmail(String email);

    Mono<User> findByEmailVerificationTokenHash(String hash);

    Mono<User> findByPasswordResetTokenHash(String hash);

    Flux<User> findByStatusAndDeletedAtBefore(UserStatus status, Instant cutoff);
}
