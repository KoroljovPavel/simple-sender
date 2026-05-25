package com.botfunnel.subscriber;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository for CRM lifecycle events. Surface kept bare in Wave 1 — query methods for the profile
 * history feed are added by the task that owns that endpoint (Wave 2+), per the narrow-surface rule.
 */
public interface SubscriberEventRepository extends MongoRepository<SubscriberEvent, String> {
}
