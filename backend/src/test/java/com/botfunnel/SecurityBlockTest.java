package com.botfunnel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

// Servlet-flip note: previously mocked MongoClient + RedisConnectionFactory at the slice level
// and exercised via WebTestClient.bindToApplicationContext. After the flip both patterns are
// gone — this test now inherits AbstractIntegrationTest's Testcontainers Mongo/Redis and uses
// the autowired servlet MockMvc.
class SecurityBlockTest extends AbstractIntegrationTest {

    @Test
    void undefinedPathBlockedReturns401Or403() throws Exception {
        // Servlet stack with CookieCsrfTokenRepository may surface anonymous denial as either
        // 401 (auth-first chain) or 403 (CSRF-deferred handler reaching the default
        // AccessDeniedHandler before the AuthenticationEntryPoint). The invariant pinned here
        // is "/api/** without auth is blocked" — both statuses satisfy it (same precedent as
        // WebhookSecurityBlockTest::postApiWithoutAuth_rejected).
        int status = mockMvc.perform(get("/api/nonexistent"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isIn(401, 403);
    }
}
