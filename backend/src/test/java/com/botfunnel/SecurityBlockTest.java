package com.botfunnel;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Servlet-flip note: previously mocked MongoClient + RedisConnectionFactory at the slice level
// and exercised via WebTestClient.bindToApplicationContext. After the flip both patterns are
// gone — this test now inherits AbstractIntegrationTest's Testcontainers Mongo/Redis and uses
// the autowired servlet MockMvc.
class SecurityBlockTest extends AbstractIntegrationTest {

    @Test
    void undefinedPathBlocked_returns403() throws Exception {
        // Anonymous GET to /api/** is rejected by the servlet stack's default
        // ExceptionTranslationFilter path: with no AuthenticationEntryPoint override the
        // anonymous denial reaches the AccessDeniedHandler and surfaces as 403. CSRF is not
        // in play here (CsrfFilter only enforces on mutating verbs). The original reactive
        // chain emitted 401; the servlet behaviour is deterministic 403 — pinned exactly so
        // a future change reintroducing 401 (e.g. an explicit Http401AuthenticationEntryPoint)
        // becomes visible.
        mockMvc.perform(get("/api/nonexistent"))
                .andExpect(status().isForbidden());
    }
}
