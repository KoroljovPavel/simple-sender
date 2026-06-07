package com.botfunnel.security;

import com.botfunnel.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the CORS preflight for the {@code X-API-Key} header passes (Task 7). A cross-origin browser
 * caller sends an OPTIONS preflight advertising {@code X-API-Key} via
 * {@code Access-Control-Request-Headers}; without {@code X-API-Key} in the server's
 * {@code allowedHeaders} the browser would reject the request before the key filter ever runs. The curl
 * smoke does not send a preflight, so this gap is invisible there — asserted here directly.
 */
class IntegrationsCorsIT extends AbstractIntegrationTest {

    // Resolved from the configured single allowed origin (app.url) so the test is decoupled from any
    // specific local/staging value — the preflight is only approved when Origin matches allowedOrigins.
    @Value("${app.url}")
    private String allowedOrigin;

    @Test
    void preflight_allowsXApiKeyHeader() throws Exception {
        mockMvc.perform(options("/api/integrations/v1/events")
                        .header("Origin", allowedOrigin)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "X-API-Key"))
                .andExpect(status().isOk())
                .andExpect(header().stringValues("Access-Control-Allow-Headers",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsStringIgnoringCase("X-API-Key"))));
    }
}
