package com.botfunnel.tag;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for tags. Query surface added by Task 4 (the task that owns the tag service +
 * controller), per the narrow-surface rule. {@code findByProjectIdAndSlug} backs find-or-create
 * and the standalone CRUD lookups; {@code findByProjectIdOrderBySlugAsc} backs the list endpoint
 * (slug-asc is the stable UI ordering chosen in Task 4 — tech-spec does not mandate a sort).
 */
public interface TagRepository extends MongoRepository<Tag, String> {

    Optional<Tag> findByProjectIdAndSlug(String projectId, String slug);

    List<Tag> findByProjectIdOrderBySlugAsc(String projectId);
}
