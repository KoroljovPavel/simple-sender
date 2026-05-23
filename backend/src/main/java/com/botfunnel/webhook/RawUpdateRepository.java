package com.botfunnel.webhook;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface RawUpdateRepository extends MongoRepository<RawUpdate, String> {

    // Indexed lookup over the unique compound (projectId, updateId). Used by the
    // TelegramWebhookController self-heal path when a DuplicateKeyException re-enters
    // the chain — must be a direct indexed read, NOT a findAll+filter scan, because
    // raw_updates grows to ~90 days of PENDING/DONE rows under TTL.
    Optional<RawUpdate> findFirstByProjectIdAndUpdateId(String projectId, Long updateId);
}
