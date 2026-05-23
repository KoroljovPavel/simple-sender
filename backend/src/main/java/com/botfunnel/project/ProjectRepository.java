package com.botfunnel.project;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends MongoRepository<Project, String> {

    List<Project> findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(String ownerId);

    List<Project> findByOwnerIdOrderByCreatedAtDesc(String ownerId);

    long countByOwnerIdAndDeletedAtIsNull(String ownerId);

    Optional<Project> findByOwnerIdAndNameAndDeletedAtIsNull(String ownerId, String name);

    Optional<Project> findByOwnerIdAndNameAndIdNotAndDeletedAtIsNull(String ownerId, String name, String id);

    List<Project> findByDeletedAtBefore(Instant cutoff);
}
