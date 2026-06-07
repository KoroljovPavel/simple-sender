package com.botfunnel.security;

import com.botfunnel.AbstractIntegrationTest;
import com.botfunnel.api.ApiKey;
import com.botfunnel.api.ApiKeyRepository;
import com.botfunnel.common.crypto.Sha256Hex;
import com.botfunnel.profile.WithMockAppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Proves the two security chains are mutually exclusive (Decision 7), in BOTH directions:
 * <ul>
 *   <li>a session principal is NOT accepted on {@code /api/integrations/**} (the integrations chain is
 *       STATELESS + key-only — it ignores the session SecurityContext);</li>
 *   <li>an {@code X-API-Key} header is NOT accepted on the session cabinet {@code /api/v1/projects/**}
 *       (the session chain ignores the key header).</li>
 * </ul>
 * A regression that lets one chain shadow the other (e.g. the session {@code /api/**} matcher swallowing
 * {@code /api/integrations/**}, or the order flipping) breaks at least one direction here.
 */
class ChainIsolationIT extends AbstractIntegrationTest {

    private static final String API_KEY_HEADER = "X-API-Key";

    @Autowired ApiKeyRepository apiKeyRepository;

    private String plaintextKey;

    @BeforeEach
    void seed() {
        apiKeyRepository.deleteAll();
        plaintextKey = "isolation-test-key-1234567890";
        ApiKey key = new ApiKey();
        key.setProjectId("proj-isolation");
        key.setKeyHash(Sha256Hex.hex(plaintextKey));
        key.setKeyPrefix(plaintextKey.substring(0, 8));
        key.setCreatedAt(Instant.now());
        apiKeyRepository.save(key);
    }

    @Test
    @WithMockAppUser
    void sessionCookie_rejectedOn_integrations() throws Exception {
        // A valid session principal must NOT authenticate the integrations endpoint — only the key does.
        // The STATELESS integrations chain ignores the session context → 401.
        int status = mockMvc.perform(post("/api/integrations/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_name\":\"purchase\",\"telegram_user_id\":1}"))
                .andReturn().getResponse().getStatus();

        assertThat(status)
                .as("session principal must not be accepted on /api/integrations/** (got %s)", status)
                .isEqualTo(401);
    }

    @Test
    void apiKey_rejectedOn_cabinet() throws Exception {
        // An X-API-Key on the session cabinet must NOT authenticate — the session chain ignores it.
        // No session principal + no valid session auth → 401/403 (never 2xx).
        int status = mockMvc.perform(get("/api/v1/projects")
                        .header(API_KEY_HEADER, plaintextKey))
                .andReturn().getResponse().getStatus();

        assertThat(status)
                .as("API key must not authenticate the session cabinet /api/v1/projects/** (got %s)", status)
                .isIn(401, 403);
    }
}
