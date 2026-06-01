package com.botfunnel.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Bean
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
        // X-XSRF-TOKEN is required for CookieCsrfTokenRepository — client must send it
        config.setAllowedHeaders(List.of("Content-Type", "X-Requested-With", "X-XSRF-TOKEN"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
