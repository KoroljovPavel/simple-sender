package com.botfunnel.api;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link ApiKeyAuthFilter} — pins the security spine without a Spring context: every key
 * fault (missing / blank / unknown) maps to a uniform 401 with no body and never advances the chain,
 * while a valid key authenticates, stamps {@code lastUsedAt}, and exposes the resolved project.
 */
class ApiKeyAuthFilterTest {

    private ApiKeyService apiKeyService;
    private ApiKeyRepository apiKeyRepository;
    private FilterChain chain;
    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        apiKeyService = mock(ApiKeyService.class);
        apiKeyRepository = mock(ApiKeyRepository.class);
        chain = mock(FilterChain.class);
        filter = new ApiKeyAuthFilter(apiKeyService, apiKeyRepository);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesProjectFromKey_uniform401OnAnyProblem() throws Exception {
        // lookup() returns empty for every key fault (the service maps null/blank/unknown to empty);
        // the filter must collapse all of them to the same bare 401 and never advance the chain.
        when(apiKeyService.lookup(any())).thenReturn(Optional.empty());

        for (String header : new String[]{null, "", "   ", "malformed", "unknown-but-well-formed"}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            if (header != null) {
                request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, header);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).isEmpty();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
        verify(chain, never()).doFilter(any(), any());
        verifyNoInteractions(apiKeyRepository); // no stamp on any failure
    }

    @Test
    void validKey_authenticatesAndStampsLastUsedAt() throws Exception {
        ApiKey apiKey = new ApiKey();
        apiKey.setProjectId("proj-42");
        apiKey.setKeyHash("hash");
        when(apiKeyService.lookup("good-key")).thenReturn(Optional.of(apiKey));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, "good-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        // Chain advanced exactly once during the authenticated call.
        verify(chain).doFilter(request, response);
        // lastUsedAt stamped + persisted.
        assertThat(apiKey.getLastUsedAt()).isNotNull();
        assertThat(apiKey.getLastUsedAt()).isBeforeOrEqualTo(Instant.now());
        verify(apiKeyRepository).save(apiKey);
        // Context is cleared in the finally block after the chain returns (no leak across threads).
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validKey_pinsResolvedProjectDuringChain() throws Exception {
        ApiKey apiKey = new ApiKey();
        apiKey.setProjectId("proj-77");
        when(apiKeyService.lookup("good-key")).thenReturn(Optional.of(apiKey));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, "good-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] seenProject = new String[1];
        FilterChain capturing = (req, res) -> seenProject[0] =
                ((ApiKeyAuthentication) SecurityContextHolder.getContext().getAuthentication()).getProjectId();

        filter.doFilter(request, response, capturing);

        assertThat(seenProject[0]).isEqualTo("proj-77");
    }

    @Test
    void stampFailure_doesNotBreakAuthenticatedCall() throws Exception {
        ApiKey apiKey = new ApiKey();
        apiKey.setProjectId("proj-9");
        when(apiKeyService.lookup("good-key")).thenReturn(Optional.of(apiKey));
        when(apiKeyRepository.save(any())).thenThrow(new RuntimeException("mongo down"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ApiKeyAuthFilter.API_KEY_HEADER, "good-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // A stamp write failure must NOT turn a valid call into a 5xx — the chain still advances.
        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
        verify(apiKeyService).lookup(anyString());
    }
}
