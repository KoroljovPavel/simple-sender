package com.botfunnel.api;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ApiKeyRepository extends MongoRepository<ApiKey, String> {

    /** Authentication lookup for the public-API key filter (Task 7). */
    Optional<ApiKey> findByKeyHash(String keyHash);

    /** Settings read / regenerate path: one primary key per project. */
    Optional<ApiKey> findByProjectId(String projectId);
}
