package com.botfunnel.tag;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository for tags. Surface kept bare in Wave 1 — lookup / CRUD query methods are added by the
 * task that owns the tag service + controller (Task 4), per the narrow-surface rule.
 */
public interface TagRepository extends MongoRepository<Tag, String> {
}
