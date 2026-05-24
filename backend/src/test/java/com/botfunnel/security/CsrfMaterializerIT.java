package com.botfunnel.security;

import com.botfunnel.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Isolated from SecurityConfigTest because the MockMvc-based csrfCookie_writtenOnSafeVerbRequest
// assertion was an "occasional context-cache flake" when the suite ran (Task 16 audit F-C1 #2).
// Uses a fresh TestRestTemplate per test (SimpleClientHttpRequestFactory — no shared cookie
// store) so prior tests' XSRF-TOKEN cookies cannot leak into this request and cause Spring's
// CookieCsrfTokenRepository.loadToken to return non-null, which would skip the saveToken path
// the CsrfCookieMaterializer relies on. Closes Task 16 F-C1 #2 and F-M2 ("AC18 XSRF assertion
// deferred to a flaky companion") by making the cookie write deterministic.
class CsrfMaterializerIT extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void csrfCookie_writtenOnSafeVerbRequest() {
        // Regression guard for the CsrfCookieMaterializer filter: on a safe-verb request to a
        // CSRF-active path, the XSRF-TOKEN cookie must be materialised so SPAs can pre-fetch
        // it before their first POST. TC11 (full SESSION + XSRF co-emission) is owned by Task 12.
        TestRestTemplate freshClient = new TestRestTemplate();
        ResponseEntity<String> resp = freshClient.exchange(
                "http://localhost:" + port + "/api/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                String.class);

        List<String> setCookies = resp.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE);
        String xsrfCookie = setCookies.stream()
                .filter(c -> c.startsWith("XSRF-TOKEN="))
                .findFirst()
                .orElse(null);
        assertThat(xsrfCookie)
                .as("CsrfCookieMaterializer must emit an XSRF-TOKEN Set-Cookie on safe-verb requests; "
                        + "observed Set-Cookie headers: %s", setCookies)
                .isNotNull();
        int eq = xsrfCookie.indexOf('=');
        int semi = xsrfCookie.indexOf(';');
        String value = (semi < 0) ? xsrfCookie.substring(eq + 1) : xsrfCookie.substring(eq + 1, semi);
        assertThat(value).isNotBlank();
    }
}
