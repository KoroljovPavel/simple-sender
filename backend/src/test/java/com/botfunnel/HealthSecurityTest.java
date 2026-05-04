package com.botfunnel;

import com.mongodb.reactivestreams.client.MongoClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthSecurityTest {

    @Autowired
    WebTestClient webTestClient;

    // Decision 6: mocked to prevent auto-config from requiring live DB connections in tests
    @MockitoBean
    MongoClient mongoClient;

    @MockitoBean
    RedisConnectionFactory redisConnectionFactory;

    @MockitoBean
    ReactiveRedisConnectionFactory reactiveRedisConnectionFactory;

    @Test
    void healthPermittedWithoutAuth() {
        // Verifies SecurityWebFilterChain.pathMatchers("/health").permitAll():
        // even an invalid Bearer token must not trigger 401 on this path
        webTestClient.get().uri("/health")
                .header("Authorization", "Bearer invalid-token")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo("ok");
    }
}
