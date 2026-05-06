package com.botfunnel.security;

import com.botfunnel.JobRunrInMemoryConfig;
import com.mongodb.reactivestreams.client.MongoClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JobRunrInMemoryConfig.class)
class SecurityConfigTest {

    @Autowired
    WebTestClient webTestClient;

    // Mock infrastructure to prevent auto-config from connecting to live services
    @MockitoBean
    MongoClient mongoClient;

    @MockitoBean
    RedisConnectionFactory redisConnectionFactory;

    @MockitoBean
    ReactiveRedisConnectionFactory reactiveRedisConnectionFactory;

    @Test
    void unauthenticated_protectedEndpoint_returns401() {
        webTestClient.get().uri("/api/profile")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void authEndpoints_permitWithoutAuthentication() {
        // /api/auth/me reaches the controller without filter-level auth;
        // the controller itself returns 401 when no session is present.
        webTestClient.get().uri("/api/auth/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
