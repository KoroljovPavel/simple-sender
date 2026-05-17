package com.botfunnel.webhook;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

public interface RawUpdateRepository extends ReactiveMongoRepository<RawUpdate, String> {
}
