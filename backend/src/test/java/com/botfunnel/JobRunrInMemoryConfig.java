package com.botfunnel;

import org.jobrunr.storage.InMemoryStorageProvider;
import org.jobrunr.storage.StorageProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

// Shared test config that supplies an in-memory JobRunr StorageProvider so the @Recurring
// post-processor (which runs at context startup) can register HardDeleteJob without a real
// MongoDB/SQL/Redis backend. Imported by integration tests and by slice tests that mock out
// the rest of the database stack. The background-job-server is disabled in test profile via
// application-test.properties so no worker threads are spawned.
@TestConfiguration
public class JobRunrInMemoryConfig {

    @Bean
    StorageProvider storageProvider() {
        return new InMemoryStorageProvider();
    }
}
