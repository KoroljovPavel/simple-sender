package com.botfunnel.funnel;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Narrow lookup surface for Wave 2 consumers only. {@code findByProjectId} / the status-filtered
 * variant back the CRUD list endpoint (Task 5); {@code findByProjectIdAndTriggerTypeAndTriggerValueAndStatus}
 * resolves the active funnel for a given trigger at fire() time (Task 7). Engine state mutations go
 * through {@code MongoTemplate.findAndModify} (Task 6), not this repository.
 *
 * <p>Phase 3: this interface is the SOLE owner of every {@code FunnelRepository} change for the
 * 12-funnels-triggers feature — the Task-4 dispatcher ({@code FunnelEventService}) CONSUMES the new
 * list queries but adds none of its own (single owner → no merge conflict).
 */
public interface FunnelRepository extends MongoRepository<Funnel, String> {

    List<Funnel> findByProjectId(String projectId);

    List<Funnel> findByProjectIdAndStatus(String projectId, FunnelStatus status);

    // on_start conflict pre-check (Decision 8 / Optional-retention policy): exactly one active funnel may
    // own a given (triggerType, triggerValue). KEEP this single-result method — the List sibling below is
    // ADDED alongside it for fan-out, not a replacement.
    Optional<Funnel> findByProjectIdAndTriggerTypeAndTriggerValueAndStatus(
            String projectId, String triggerType, String triggerValue, FunnelStatus status);

    // Phase 3 (Decision 1) — fan-out trigger lookup: all active funnels matching a (triggerType,
    // triggerValue) for `event` / `tag_added` / `custom_field_set`. List sibling of the Optional method
    // above (Spring Data derives single-vs-many from the return type; the distinct name avoids a clash).
    List<Funnel> findAllByProjectIdAndTriggerTypeAndTriggerValueAndStatus(
            String projectId, String triggerType, String triggerValue, FunnelStatus status);

    // Phase 3 (Decision 3) — keyword fan-out: all active keyword funnels for a project. The
    // contains-match (case-insensitive, any-of-many) against each funnel's `keywords` runs in code,
    // because the exact-match triggerValue index cannot express "contains, multiple keywords".
    List<Funnel> findByProjectIdAndTriggerTypeAndStatus(
            String projectId, String triggerType, FunnelStatus status);
}
