package com.botfunnel;

import com.mongodb.reactivestreams.client.MongoClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityBlockTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean
    MongoClient mongoClient;

    @MockBean
    RedisConnectionFactory redisConnectionFactory;

    @MockBean
    ReactiveRedisConnectionFactory reactiveRedisConnectionFactory;

    @Test
    void undefinedPathBlockedReturns401() {
        webTestClient.get().uri("/api/nonexistent")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
