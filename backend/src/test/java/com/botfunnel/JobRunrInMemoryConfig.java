package com.botfunnel;

import org.jobrunr.jobs.mappers.JobMapper;
import org.jobrunr.storage.InMemoryStorageProvider;
import org.jobrunr.storage.StorageProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

// Shared test config that supplies an in-memory JobRunr StorageProvider so the @Recurring
// post-processor (which runs at context startup) can register HardDeleteJob without a real
// MongoDB/SQL/Redis backend. Imported by integration tests and by slice tests that mock out
// the rest of the database stack. The background-job-server is disabled in test profile via
// application-test.properties so no worker threads are spawned.
//
// JobMapper is wired explicitly into the InMemoryStorageProvider. JobRunr's autoconfigure
// supplies a JobMapper bean (jobMapper(JsonMapper)) but does NOT inject it into custom-defined
// StorageProvider beans — only into auto-created providers (e.g. SQL). Without setJobMapper,
// InMemoryStorageProvider.deepClone NPEs on save() — visible the moment any code path actually
// calls BackgroundJob.enqueue (recurring-job tests that only call handle() directly hide it).
@TestConfiguration
public class JobRunrInMemoryConfig {

    @Bean
    StorageProvider storageProvider(JobMapper jobMapper) {
        InMemoryStorageProvider provider = new InMemoryStorageProvider();
        provider.setJobMapper(jobMapper);
        return provider;
    }
}
