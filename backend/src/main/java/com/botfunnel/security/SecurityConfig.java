package com.botfunnel.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.session.WebSessionIdResolver;
import reactor.core.publisher.Mono;

import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${app.url:http://localhost:3000}")
    private String appUrl;

    @Bean
    public ServerSecurityContextRepository securityContextRepository() {
        // WebSessionServerSecurityContextRepository.getInstance() does not exist in
        // Spring Security 6.x; new is the correct form. Exposed as a bean so AuthService
        // can save the SecurityContext on login (manual auth — see Decision 11).
        return new WebSessionServerSecurityContextRepository();
    }

    @Bean
    public WebSessionIdResolver webSessionIdResolver(
            ServerProperties serverProperties,
            @Value("${app.session.ttl-remember-me-days:30}") long rememberMeDays) {
        // Replaces Boot's auto-configured CookieWebSessionIdResolver (which is @ConditionalOnMissingBean).
        // Adds per-request Max-Age branching driven by AuthService publishing REMEMBER_ME_ATTR.
        return new RememberMeWebSessionIdResolver(serverProperties, rememberMeDays);
    }

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http,
                                                            ServerSecurityContextRepository securityContextRepository) {
        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
                        // Plain attribute handler: cookie holds the raw token, SPA echoes the
                        // same raw value back via X-XSRF-TOKEN, server compares as-is. The
                        // default in Spring Security 6.x (XorServerCsrfTokenRequestAttributeHandler)
                        // would BREACH-mask only the form-attribute and break the cookie/header
                        // round-trip, since the cookie is written raw by CookieServerCsrfTokenRepository.
                        // BREACH protection is unnecessary here: tokens are only delivered via cookie,
                        // never rendered into a compressible JSON response body.
                        .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler())
                )
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .securityContextRepository(securityContextRepository)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/health").permitAll()
                        .pathMatchers("/api/auth/**").permitAll()
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().authenticated()
                )
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .addFilterAfter(csrfCookieMaterializer(), SecurityWebFiltersOrder.CSRF)
                .build();
    }

    private WebFilter csrfCookieMaterializer() {
        // Subscribe to the deferred CsrfToken so CookieServerCsrfTokenRepository writes the
        // XSRF-TOKEN cookie on every request. Without this the cookie is never set and SPA
        // POSTs (e.g. /api/auth/login) fail with 403 because the client has no token to send.
        return (exchange, chain) -> {
            Mono<CsrfToken> csrfToken = exchange.getAttribute(CsrfToken.class.getName());
            return csrfToken != null
                    ? csrfToken.doOnSuccess(token -> {}).then(chain.filter(exchange))
                    : chain.filter(exchange);
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(appUrl));
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        // X-XSRF-TOKEN is required for CookieServerCsrfTokenRepository — client must send it
        config.setAllowedHeaders(List.of("Content-Type", "X-Requested-With", "X-XSRF-TOKEN"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
