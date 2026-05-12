package com.botfunnel.project;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface ProjectRepository extends ReactiveMongoRepository<Project, String> {

    Flux<Project> findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(String ownerId);

    Flux<Project> findByOwnerIdOrderByCreatedAtDesc(String ownerId);

    Mono<Long> countByOwnerIdAndDeletedAtIsNull(String ownerId);

    Mono<Project> findByOwnerIdAndNameAndDeletedAtIsNull(String ownerId, String name);

    Mono<Project> findByOwnerIdAndNameAndIdNotAndDeletedAtIsNull(String ownerId, String name, String id);

    Flux<Project> findByDeletedAtBefore(Instant cutoff);
}
