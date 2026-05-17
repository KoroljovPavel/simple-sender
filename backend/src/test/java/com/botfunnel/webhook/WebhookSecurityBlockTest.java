package com.botfunnel.webhook;

import com.botfunnel.JobRunrInMemoryConfig;
import com.mongodb.reactivestreams.client.MongoClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockUser;

// Slice test mirroring SecurityBlockTest — verifies the security chain wiring (scoped permitAll +
// scoped CSRF disable) without needing the full IT stack. Backing DB/Redis are mocked because we
// only care about HTTP-level status codes from the security filter chain.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(JobRunrInMemoryConfig.class)
class WebhookSecurityBlockTest {

    @Autowired ApplicationContext applicationContext;
    private WebTestClient webTestClient;

    @MockitoBean MongoClient mongoClient;
    @MockitoBean RedisConnectionFactory redisConnectionFactory;
    @MockitoBean ReactiveRedisConnectionFactory reactiveRedisConnectionFactory;

    @BeforeEach
    void rebindClient() {
        // Bind to ApplicationContext so SecurityMockServerConfigurers.mockUser()/csrf() work.
        // The autowired RANDOM_PORT WebTestClient is bind-to-server and rejects those mutators
        // (AbstractIntegrationTest precedent).
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .configureClient()
                .build();
    }

    @Test
    void postWebhookWithoutAuth_reachesController_not403Csrf() {
        // No CSRF token, no auth — the request must clear the security chain and reach the
        // controller. Mongo is mocked so the result is likely a 5xx from the unmocked save call,
        // but the assertion is "NOT 403 CSRF" — anything that is not 403 proves the chain
        // released the request to the controller.
        webTestClient.post()
                .uri("/webhooks/telegram/507f1f77bcf86cd799439011")
                .header("X-Telegram-Bot-Api-Secret-Token", "anything")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("update_id", 1))
                .exchange()
                .expectStatus().value(status -> {
                    if (status == 403) {
                        throw new AssertionError(
                                "Webhook POST without X-XSRF-TOKEN must NOT be rejected by CSRF; got 403");
                    }
                });
    }

    @Test
    void postApiWithoutAuth_returns401() {
        // csrf() mutator added so the CSRF gate passes and we hit the auth filter (the
        // production-realistic baseline regression — unauth POST → 401 because secure cookie +
        // X-XSRF-TOKEN are missing from a bare client, but auth is the first failure when CSRF
        // is satisfied).
        webTestClient.mutateWith(csrf())
                .post().uri("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "x"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void postApiAuthedWithoutXsrfToken_returns403_csrfBaselineStillActive() {
        // Authenticated client without csrf() mutator → CSRF protection rejects with 403. Proves
        // the scoped disable did NOT bleed into /api/**.
        webTestClient.mutateWith(mockUser())
                .post().uri("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "x"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void postWebhookWithoutXsrfToken_doesNot403_scopedCsrfDisableActive() {
        // Bare client, no csrf() mutator — the Negated CSRF matcher must skip CSRF enforcement on
        // the webhook path. Any non-403 status proves the scoped disable works.
        webTestClient.post()
                .uri("/webhooks/telegram/507f1f77bcf86cd799439011")
                .header("X-Telegram-Bot-Api-Secret-Token", "anything")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("update_id", 2))
                .exchange()
                .expectStatus().value(status -> {
                    if (status == 403) {
                        throw new AssertionError(
                                "Scoped CSRF disable failed — got 403 on webhook path");
                    }
                });
    }
}
