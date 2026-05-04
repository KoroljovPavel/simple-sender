package com.botfunnel.security;

import com.mongodb.reactivestreams.client.MongoClient;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SecurityConfigTest {

    @Autowired
    WebTestClient webTestClient;

    @MockitoBean
    MongoClient mongoClient;

    @MockitoBean
    RedisConnectionFactory redisConnectionFactory;

    @MockitoBean
    ReactiveRedisConnectionFactory reactiveRedisConnectionFactory;

    @MockitoBean
    StorageProvider storageProvider;

    @Test
    void unauthenticated_protectedEndpoint_returns401() {
        webTestClient.get().uri("/api/profile")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
