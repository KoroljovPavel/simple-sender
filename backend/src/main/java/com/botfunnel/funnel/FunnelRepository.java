package com.botfunnel.funnel;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Narrow lookup surface for the funnel feature. {@code findByProjectId} / the status-filtered variant
 * back the CRUD list endpoints; the trigger-bearing queries below resolve active funnels for trigger
 * dispatch. Engine state mutations go through {@code MongoTemplate.findAndModify}, not this repository.
 *
 * <p>Phase 8 (17-funnel-multi-entry / Decision 1 + 6): a funnel now carries a {@code List<Trigger>}
 * instead of a flat trigger trio, plus a denormalized nullable {@code onStartTriggerValue} scalar. The
 * trigger lookups are therefore array-aware (multikey nested-property derivation: Spring Data traverses
 * {@code triggers} then the element field, applying the predicate per array element and returning the
 * funnel if ANY element matches), except the on_start conflict pre-check, which keys on the denormalized
 * scalar so it can lean on the partial-unique {@code {projectId, onStartTriggerValue}} index.
 *
 * <p><b>Matched-element re-scan idiom.</b> The array-aware fan-out query narrows the candidate set to
 * funnels whose {@code triggers[]} contains a matching element; it does NOT tell the caller WHICH element
 * matched. The dispatcher (Task 5) re-scans each returned funnel's {@code triggers[]} to find the matched
 * element and read its {@code entryStepId} (start vs redirect routing).
 */
public interface FunnelRepository extends MongoRepository<Funnel, String> {

    List<Funnel> findByProjectId(String projectId);

    List<Funnel> findByProjectIdAndStatus(String projectId, FunnelStatus status);

    // on_start conflict pre-check (Decision 6): at most one active funnel per (projectId, on_start payload).
    // Keys on the DENORMALIZED onStartTriggerValue scalar — the same field the partial-unique index guards —
    // so a service-level pre-check and the DB constraint agree. Used by FunnelService.checkTriggerConflict
    // (Task 4) and FunnelTriggerServiceImpl.fire's on_start lookup (Task 5).
    Optional<Funnel> findByProjectIdAndOnStartTriggerValueAndStatus(
            String projectId, String onStartTriggerValue, FunnelStatus status);

    // Fan-out trigger lookup (Decision 1) — all active funnels whose triggers[] contains an element matching
    // (triggerType, triggerValue), for `event` / `tag_added` / `custom_field_set`. Multikey nested-property
    // derivation over the triggers array: the predicate matches per element, returning the funnel if any
    // element matches. The caller (Task 5) re-scans triggers[] to locate the matched element + its
    // entryStepId. Used by FunnelEventService fan-out (Task 5).
    List<Funnel> findByProjectIdAndTriggersTriggerTypeAndTriggersTriggerValueAndStatus(
            String projectId, String triggerType, String triggerValue, FunnelStatus status);

    // Keyword fan-out (Decision 3) — all active funnels carrying a keyword trigger, for in-code contains-
    // match (case-insensitive, any-of-many) against each matched trigger element's `keywords`. The exact-
    // match index cannot express "contains, multiple keywords", so the contains-match runs in code over the
    // returned candidates. Array-aware: matches funnels whose triggers[] contains a keyword-type element.
    // Used by FunnelEventService keyword scan (Task 5).
    List<Funnel> findByProjectIdAndTriggersTriggerTypeAndStatus(
            String projectId, String triggerType, FunnelStatus status);
}
