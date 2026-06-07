package com.botfunnel.security;

import com.botfunnel.api.ApiKeyAuthFilter;
import com.botfunnel.api.ApiKeyRepository;
import com.botfunnel.api.ApiKeyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.url:http://localhost:3000}")
    private String appUrl;

    @Bean
    public SecurityContextRepository securityContextRepository() {
        // Exposed as a bean so AuthService can save the SecurityContext on login
        // (manual auth — see Decision 11). Servlet equivalent of the former
        // WebSessionServerSecurityContextRepository.
        // NOT wired into HttpSecurity — Spring Security 6.x's default servlet chain builds its
        // own DelegatingSecurityContextRepository for per-request load/save; this bean exists
        // only for AuthService.saveContext after manual auth.
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public CookieSerializer cookieSerializer(
            ServerProperties serverProperties,
            @Value("${app.session.ttl-remember-me-days:30}") long rememberMeDays) {
        // Shadows Spring Session's @ConditionalOnMissingBean default so the SESSION
        // cookie carries a per-request Max-Age driven by REMEMBER_ME_ATTR (set by
        // AuthService.openSession).
        return new RememberMeCookieSerializer(serverProperties, rememberMeDays);
    }

    /**
     * Decision 7: higher-priority STATELESS chain for the public, key-authenticated API. Its
     * {@code securityMatcher("/api/integrations/**")} carves the public namespace out from the session
     * chain's broad {@code /api/**} matcher — it MUST be {@code @Order(1)} so it is evaluated first; the
     * session chain is {@code @Order(2)}. CSRF is disabled (cookieless header-key auth, nothing to
     * forge), httpBasic/formLogin are off, and the {@link ApiKeyAuthFilter} runs before the username/
     * password filter slot to pre-authenticate from the {@code X-API-Key} header. CORS is shared with the
     * session chain so the {@code X-API-Key} preflight allowance applies here too.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain integrationsSecurityFilterChain(HttpSecurity http,
                                                               ApiKeyService apiKeyService,
                                                               ApiKeyRepository apiKeyRepository)
            throws Exception {
        // Constructed here (NOT a @Component) so the filter runs ONLY inside this chain — a
        // @Component OncePerRequestFilter would be auto-registered globally and 401 every request.
        ApiKeyAuthFilter apiKeyAuthFilter = new ApiKeyAuthFilter(apiKeyService, apiKeyRepository);
        return http
                .securityMatcher("/api/integrations/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authz -> authz.anyRequest().authenticated())
                // The api-key filter pins an ApiKeyAuthentication before the auth slot; a request that
                // reaches the slot unauthenticated falls through to the entry point → uniform 401.
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain appSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        // Plain attribute handler: cookie holds the raw token, SPA echoes the
                        // same raw value back via X-XSRF-TOKEN, server compares as-is. The
                        // default in Spring Security 6.x (XorCsrfTokenRequestAttributeHandler)
                        // would BREACH-mask only the form-attribute and break the cookie/header
                        // round-trip, since the cookie is written raw by CookieCsrfTokenRepository.
                        // BREACH protection is unnecessary here: tokens are only delivered via cookie,
                        // never rendered into a compressible JSON response body.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        // Decision 13: scoped CSRF disable. AND the default verb matcher (which
                        // restricts CSRF to mutating methods POST/PUT/DELETE/PATCH) with a path
                        // negation that excludes /webhooks/telegram/{projectId}. Replacing the
                        // protection matcher with ONLY the path negation would extend CSRF
                        // enforcement to GET/HEAD/OPTIONS as well — breaking /health and every
                        // bare GET request (HealthEndpointTest / SecurityConfigTest precedent).
                        // Single segment ({projectId}) prevents future sub-path leak (security M4);
                        // CSRF stays active on /api/** mutating verbs.
                        .requireCsrfProtectionMatcher(new AndRequestMatcher(
                                CsrfFilter.DEFAULT_CSRF_MATCHER,
                                new NegatedRequestMatcher(
                                        new AntPathRequestMatcher("/webhooks/telegram/{projectId}"))))
                )
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(authz -> authz
                        .requestMatchers("/health").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        // Decision 13: webhook is unauthenticated; secret-header gate is the
                        // sole access control. Ordered BEFORE /api/** so the latter does not
                        // shadow this rule. Single-segment {projectId} prevents sub-path leak.
                        .requestMatchers("/webhooks/telegram/{projectId}").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated()
                )
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .addFilterAfter(new CsrfCookieMaterializer(), CsrfFilter.class)
                .build();
    }

    /**
     * Reads the lazy {@link CsrfToken} attribute set by {@link CsrfFilter} and calls
     * {@link CsrfToken#getToken()} so {@link CookieCsrfTokenRepository} writes the XSRF-TOKEN
     * cookie on every request — including safe-verb GETs. Without this, the cookie is only
     * materialised when a downstream component (typically a controller or view) reads the
     * attribute, which leaves SPAs unable to pre-fetch the token before their first POST.
     *
     * <p>Deviates from the tech-spec claim that MVC's {@code CsrfFilter} eagerly invokes the
     * handler — it does not; the plain {@link CsrfTokenRequestAttributeHandler} stores a
     * Supplier in request attributes and never calls {@code .get()} itself. The MVC equivalent
     * of the deleted reactive cookie materializer {@code WebFilter} is therefore still required
     * (matches the official Spring Security 6.5 SPA pattern).
     */
    private static class CsrfCookieMaterializer extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(appUrl));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // X-XSRF-TOKEN is required for CookieCsrfTokenRepository — client must send it.
        // X-API-Key (Task 7): without it a cross-origin integrations caller is blocked at the OPTIONS
        // preflight before the key filter ever runs. The CORS source is registered on /** so this one
        // entry covers /api/integrations/** too.
        config.setAllowedHeaders(List.of("Content-Type", "X-Requested-With", "X-XSRF-TOKEN", "X-API-Key"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
