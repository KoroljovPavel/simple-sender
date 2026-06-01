package com.botfunnel.funnel;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Registers the {@code funnel_executions} entity with Spring Data (so its annotation-driven indexes
 * are created). No query methods are declared deliberately: the engine's sweep/claim and re-enter
 * guard run through {@code MongoTemplate.findAndModify} for atomicity (Task 6/7), and the hard-delete
 * cascade sweeps by top-level {@code projectId} via {@code MongoTemplate.remove(... in(ids))} in
 * {@code ProjectHardDeleteJob} (Task 8), matching the existing subscriber/tags cascade. Adding a
 * derived method here with no caller would be dead code.
 */
public interface FunnelExecutionRepository extends MongoRepository<FunnelExecution, String> {
}
