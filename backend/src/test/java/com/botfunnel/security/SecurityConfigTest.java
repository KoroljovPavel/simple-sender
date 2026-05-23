package com.botfunnel.security;

import com.botfunnel.JobRunrInMemoryConfig;
import com.mongodb.client.MongoClient;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(JobRunrInMemoryConfig.class)
class SecurityConfigTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    CookieSerializer cookieSerializer;

    // Mock infrastructure to prevent auto-config from connecting to live services
    @MockitoBean
    MongoClient mongoClient;

    @MockitoBean
    RedisConnectionFactory redisConnectionFactory;

    @Test
    void cookieSerializer_isRememberMeCookieSerializer_notDefault() {
        // TC5 — Spring Session's @ConditionalOnMissingBean default does NOT win.
        assertThat(cookieSerializer).isInstanceOf(RememberMeCookieSerializer.class);
    }

    @Test
    void csrfProtection_excludesWebhookPath_keepsApiPathProtected() throws Exception {
        // /webhooks/telegram/{projectId} is excluded by the AND-scoped matcher: the CSRF
        // filter does NOT challenge it, so reach-through to the controller is allowed.
        // The controller may return any status (200 / 401 / 404 / 400 — secret-header gate),
        // but it MUST NOT be 403 from the CSRF filter.
        mockMvc.perform(post("/webhooks/telegram/some-id")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertThat(status)
                            .as("Webhook path must bypass CSRF; got " + status)
                            .isNotEqualTo(403);
                });

        // /api/** keeps CSRF protection on mutating verbs — no token → 403.
        mockMvc.perform(post("/api/projects")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void csrfCookie_writtenOnSafeVerbRequest() throws Exception {
        // Regression guard for the CsrfCookieMaterializer filter: on a safe-verb request to a
        // CSRF-active path, the XSRF-TOKEN cookie must be materialised so SPAs can pre-fetch
        // it before their first POST. TC11 (full SESSION + XSRF co-emission) is owned by Task 12.
        Cookie xsrf = mockMvc.perform(get("/health"))
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        assertThat(xsrf)
                .as("XSRF-TOKEN cookie must be written on safe-verb requests")
                .isNotNull();
        assertThat(xsrf.getValue()).isNotBlank();

        // Same expectation via the higher-level matcher API.
        mockMvc.perform(get("/health"))
                .andExpect(cookie().exists("XSRF-TOKEN"));
    }
}
