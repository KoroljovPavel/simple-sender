package com.botfunnel.webhook;

import com.botfunnel.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// Slice-style test for security wiring (scoped permitAll + scoped CSRF disable). After the
// servlet flip, uses mockMvc + SecurityMockMvcRequestPostProcessors mutators (csrf(), user())
// instead of the reactive equivalents — see AbstractIntegrationTest for the rebind history.
class WebhookSecurityBlockTest extends AbstractIntegrationTest {

    @Test
    void postWebhookWithoutAuth_reachesController_not403Csrf() throws Exception {
        // No CSRF token, no auth — the request must clear the security chain and reach the
        // controller. Assertion is "NOT 403 CSRF" — anything that is not 403 proves the chain
        // released the request to the controller (downstream resolution depends on whether the
        // controller short-circuits as 401/404).
        int status = mockMvc.perform(post("/webhooks/telegram/507f1f77bcf86cd799439011")
                        .header("X-Telegram-Bot-Api-Secret-Token", "anything")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"update_id\":1}"))
                .andReturn().getResponse().getStatus();
        if (status == 403) {
            throw new AssertionError(
                    "Webhook POST without X-XSRF-TOKEN must NOT be rejected by CSRF; got 403");
        }
    }

    @Test
    void postApiWithoutAuth_rejected() throws Exception {
        // csrf() post-processor is the MockMvc equivalent of the prior reactive csrf() mutator.
        // The strong invariant the original test pinned was "the security chain blocks an unauth
        // POST to /api/** — not 2xx". Servlet CSRF wiring with CookieCsrfTokenRepository may
        // surface this as either 401 (auth-first) or 403 (CSRF-first) depending on filter order;
        // both prove the protected path is unreachable.
        int status = mockMvc.perform(post("/api/v1/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(status).isIn(401, 403);
    }

    @Test
    void postApiAuthedWithoutXsrfToken_returns403_csrfBaselineStillActive() throws Exception {
        // Authenticated client without csrf() processor → CSRF protection rejects with 403.
        // Proves the scoped disable did NOT bleed into /api/**.
        int status = mockMvc.perform(post("/api/v1/projects")
                        .with(user("anyone"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(status).isEqualTo(403);
    }

    @Test
    void postWebhookWithoutXsrfToken_doesNot403_scopedCsrfDisableActive() throws Exception {
        // Bare client, no csrf() processor — the Negated CSRF matcher must skip CSRF enforcement
        // on the webhook path. Any non-403 status proves the scoped disable works.
        int status = mockMvc.perform(post("/webhooks/telegram/507f1f77bcf86cd799439011")
                        .header("X-Telegram-Bot-Api-Secret-Token", "anything")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"update_id\":2}"))
                .andReturn().getResponse().getStatus();
        if (status == 403) {
            throw new AssertionError(
                    "Scoped CSRF disable failed — got 403 on webhook path");
        }
    }
}
