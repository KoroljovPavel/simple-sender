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
    void undefinedPathBlockedReturns401() throws Exception {
        mockMvc.perform(get("/api/nonexistent"))
                .andExpect(status().isUnauthorized());
    }
}
