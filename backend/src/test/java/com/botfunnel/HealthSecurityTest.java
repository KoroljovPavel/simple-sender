package com.botfunnel;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Servlet-flip note: same migration as HealthEndpointTest — MockMvc + AbstractIntegrationTest
// Testcontainers instead of the prior reactive WebTestClient + slice mocks.
class HealthSecurityTest extends AbstractIntegrationTest {

    @Test
    void healthPermittedWithoutAuth() throws Exception {
        // Verifies SecurityFilterChain.requestMatchers("/health").permitAll():
        // even an invalid Bearer token must not trigger 401 on this path
        mockMvc.perform(get("/health")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
