package com.botfunnel.jobs;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.UuidRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Synchronous MongoDB client for JobRunr's MongoDBStorageProvider.
 *
 * The application's primary MongoDB integration is reactive
 * ({@code spring-boot-starter-data-mongodb-reactive}), but JobRunr's
 * StorageProvider only supports the synchronous driver. Without this bean
 * JobRunr's autoconfiguration cannot find a {@code com.mongodb.client.MongoClient}
 * and the application fails to start with "required a bean of type
 * 'org.jobrunr.storage.StorageProvider'".
 *
 * Reuses the same connection URI as Spring Data so JobRunr writes its
 * {@code jobrunr_*} collections into the application database.
 *
 * Sets {@code uuidRepresentation=STANDARD} explicitly: since MongoDB Java
 * Driver 4.0 the default is UNSPECIFIED, which JobRunr's MongoDBStorageProvider
 * rejects at startup.
 */
@Configuration
public class JobRunrMongoConfig {

    @Bean(destroyMethod = "close")
    public MongoClient jobrunrMongoClient(@Value("${spring.data.mongodb.uri}") String uri) {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .uuidRepresentation(UuidRepresentation.STANDARD)
                .build();
        return MongoClients.create(settings);
    }
}
