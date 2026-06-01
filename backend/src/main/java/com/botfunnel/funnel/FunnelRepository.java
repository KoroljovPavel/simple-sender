package com.botfunnel.funnel;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Narrow lookup surface for Wave 2 consumers only. {@code findByProjectId} / the status-filtered
 * variant back the CRUD list endpoint (Task 5); {@code findByProjectIdAndTriggerTypeAndTriggerValueAndStatus}
 * resolves the active funnel for a given trigger at fire() time (Task 7). Engine state mutations go
 * through {@code MongoTemplate.findAndModify} (Task 6), not this repository.
 */
public interface FunnelRepository extends MongoRepository<Funnel, String> {

    List<Funnel> findByProjectId(String projectId);

    List<Funnel> findByProjectIdAndStatus(String projectId, FunnelStatus status);

    Optional<Funnel> findByProjectIdAndTriggerTypeAndTriggerValueAndStatus(
            String projectId, String triggerType, String triggerValue, FunnelStatus status);
}
