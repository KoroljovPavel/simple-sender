package com.botfunnel.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.server.Cookie;
import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RememberMeWebSessionIdResolverTest {

    private static ServerProperties propsWithDefaults() {
        // Mirrors application.properties: name=SESSION (Boot default), http-only=true,
        // secure=false, same-site=lax. Path/domain unset (Boot's parent initCookie defaults
        // path to "/" via the request — we mirror that in the resolver).
        ServerProperties p = new ServerProperties();
        Cookie c = p.getReactive().getSession().getCookie();
        c.setName("SESSION");
        c.setHttpOnly(true);
        c.setSecure(false);
        c.setSameSite(Cookie.SameSite.LAX);
        return p;
    }

    private static MockServerWebExchange exchangeFor(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path).build());
    }

    // -------- Scenario A — AC-1 (rememberMe=true → 30-day Max-Age) --------
    @Test
    void setSessionId_rememberMeTrue_writesCookieWithThirtyDayMaxAge() {
        RememberMeWebSessionIdResolver resolver =
                new RememberMeWebSessionIdResolver(propsWithDefaults(), 30L);
        MockServerWebExchange exchange = exchangeFor("/api/auth/login");
        exchange.getAttributes().put(
                RememberMeWebSessionIdResolver.REMEMBER_ME_ATTR, Boolean.TRUE);

        resolver.setSessionId(exchange, "session-id-true");

        ResponseCookie cookie = exchange.getResponse().getCookies().getFirst("SESSION");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("session-id-true");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(30));
    }

    // -------- Scenario B — AC-2 (rememberMe=false → session cookie, no Max-Age) --------
    @Test
    void setSessionId_rememberMeFalse_writesSessionCookieWithoutMaxAge() {
        RememberMeWebSessionIdResolver resolver =
                new RememberMeWebSessionIdResolver(propsWithDefaults(), 30L);
        MockServerWebExchange exchange = exchangeFor("/api/auth/login");
        exchange.getAttributes().put(
                RememberMeWebSessionIdResolver.REMEMBER_ME_ATTR, Boolean.FALSE);

        resolver.setSessionId(exchange, "session-id-false");

        ResponseCookie cookie = exchange.getResponse().getCookies().getFirst("SESSION");
        assertThat(cookie).isNotNull();
        // ResponseCookie builder default max-age is Duration.ofSeconds(-1) — negative → omitted from header.
        assertThat(cookie.getMaxAge().isNegative()).isTrue();
    }

    // -------- Scenario C — default-safe fallback (attribute absent → session cookie) --------
    @Test
    void setSessionId_attributeAbsent_writesSessionCookieWithoutMaxAge() {
        RememberMeWebSessionIdResolver resolver =
                new RememberMeWebSessionIdResolver(propsWithDefaults(), 30L);
        // No put() — attribute absent.
        MockServerWebExchange exchange = exchangeFor("/api/auth/lazy-flow");

        resolver.setSessionId(exchange, "lazy-id");

        ResponseCookie cookie = exchange.getResponse().getCookies().getFirst("SESSION");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getMaxAge().isNegative()).isTrue();
    }

    // -------- Scenario D — AC-7 (re-login flip: TRUE→FALSE on independent exchanges) --------
    @Test
    void setSessionId_reLoginFlip_writesNewMaxAgeOnEachExchange() {
        RememberMeWebSessionIdResolver resolver =
                new RememberMeWebSessionIdResolver(propsWithDefaults(), 30L);

        MockServerWebExchange ex1 = exchangeFor("/api/auth/login");
        ex1.getAttributes().put(RememberMeWebSessionIdResolver.REMEMBER_ME_ATTR, Boolean.TRUE);
        resolver.setSessionId(ex1, "id-1");

        MockServerWebExchange ex2 = exchangeFor("/api/auth/login");
        ex2.getAttributes().put(RememberMeWebSessionIdResolver.REMEMBER_ME_ATTR, Boolean.FALSE);
        resolver.setSessionId(ex2, "id-2");

        ResponseCookie c1 = ex1.getResponse().getCookies().getFirst("SESSION");
        ResponseCookie c2 = ex2.getResponse().getCookies().getFirst("SESSION");
        assertThat(c1.getMaxAge()).isEqualTo(Duration.ofDays(30));
        assertThat(c2.getMaxAge().isNegative()).isTrue();
    }

    // -------- Scenario E — AC-3 (Max-Age reads configured TTL, parameterized) --------
    @ParameterizedTest
    @ValueSource(longs = {30L, 7L, 60L})
    void setSessionId_maxAgeReadsConfiguredTtl_parameterized(long days) {
        RememberMeWebSessionIdResolver resolver =
                new RememberMeWebSessionIdResolver(propsWithDefaults(), days);
        MockServerWebExchange exchange = exchangeFor("/api/auth/login");
        exchange.getAttributes().put(
                RememberMeWebSessionIdResolver.REMEMBER_ME_ATTR, Boolean.TRUE);

        resolver.setSessionId(exchange, "id");

        ResponseCookie cookie = exchange.getResponse().getCookies().getFirst("SESSION");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(days));
    }

    // -------- Scenario F — AC-1, AC-8 (cookie flags pass through from ServerProperties) --------

    @Test
    void setSessionId_cookieName_passThroughFromConfig() {
        ServerProperties p = propsWithDefaults();
        p.getReactive().getSession().getCookie().setName("ALT");
        RememberMeWebSessionIdResolver resolver = new RememberMeWebSessionIdResolver(p, 30L);
        MockServerWebExchange exchange = exchangeFor("/api/auth/login");
        exchange.getAttributes().put(RememberMeWebSessionIdResolver.REMEMBER_ME_ATTR, Boolean.TRUE);

        resolver.setSessionId(exchange, "id");

        // No cookie under the default "SESSION" name.
        assertThat(exchange.getResponse().getCookies().getFirst("SESSION")).isNull();
        ResponseCookie alt = exchange.getResponse().getCookies().getFirst("ALT");
        assertThat(alt).isNotNull();
        assertThat(alt.getName()).isEqualTo("ALT");
    }

    @Test
    void setSessionId_httpOnly_passThroughFromConfig() {
        ServerProperties p = propsWithDefaults();
        p.getReactive().getSession().getCookie().setHttpOnly(false);
        RememberMeWebSessionIdResolver resolver = new RememberMeWebSessionIdResolver(p, 30L);
        MockServerWebExchange exchange = exchangeFor("/api/auth/login");

        resolver.setSessionId(exchange, "id");

        ResponseCookie cookie = exchange.getResponse().getCookies().getFirst("SESSION");
        assertThat(cookie.isHttpOnly()).isFalse();
    }

    @Test
    void setSessionId_secure_passThroughFromConfig() {
        ServerProperties p = propsWithDefaults();
        p.getReactive().getSession().getCookie().setSecure(true);
        RememberMeWebSessionIdResolver resolver = new RememberMeWebSessionIdResolver(p, 30L);
        MockServerWebExchange exchange = exchangeFor("/api/auth/login");

        resolver.setSessionId(exchange, "id");

        ResponseCookie cookie = exchange.getResponse().getCookies().getFirst("SESSION");
        assertThat(cookie.isSecure()).isTrue();
    }

    @Test
    void setSessionId_sameSite_passThroughFromConfig() {
        ServerProperties p = propsWithDefaults();
        p.getReactive().getSession().getCookie().setSameSite(Cookie.SameSite.STRICT);
        RememberMeWebSessionIdResolver resolver = new RememberMeWebSessionIdResolver(p, 30L);
        MockServerWebExchange exchange = exchangeFor("/api/auth/login");

        resolver.setSessionId(exchange, "id");

        ResponseCookie cookie = exchange.getResponse().getCookies().getFirst("SESSION");
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
    }

    @Test
    void setSessionId_path_passThroughFromConfig() {
        ServerProperties p = propsWithDefaults();
        p.getReactive().getSession().getCookie().setPath("/app");
        RememberMeWebSessionIdResolver resolver = new RememberMeWebSessionIdResolver(p, 30L);
        MockServerWebExchange exchange = exchangeFor("/api/auth/login");

        resolver.setSessionId(exchange, "id");

        ResponseCookie cookie = exchange.getResponse().getCookies().getFirst("SESSION");
        assertThat(cookie.getPath()).isEqualTo("/app");
    }

    @Test
    void setSessionId_domain_passThroughFromConfig() {
        ServerProperties p = propsWithDefaults();
        p.getReactive().getSession().getCookie().setDomain("example.test");
        RememberMeWebSessionIdResolver resolver = new RememberMeWebSessionIdResolver(p, 30L);
        MockServerWebExchange exchange = exchangeFor("/api/auth/login");

        resolver.setSessionId(exchange, "id");

        ResponseCookie cookie = exchange.getResponse().getCookies().getFirst("SESSION");
        assertThat(cookie.getDomain()).isEqualTo("example.test");
    }

    // -------- Scenario G — AC-4 (resolveSessionIds inherited; pre-fix cookies still resolved) --------
    @Test
    void resolveSessionIds_readsCookieValueFromRequest_inheritedBehavior() {
        RememberMeWebSessionIdResolver resolver =
                new RememberMeWebSessionIdResolver(propsWithDefaults(), 30L);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/me").cookie(new HttpCookie("SESSION", "abc")));

        List<String> ids = resolver.resolveSessionIds(exchange);

        assertThat(ids).containsExactly("abc");
    }

    // -------- Scenario H — AC-5 (expireSession writes Max-Age=0 regardless of attribute) --------
    @Test
    void expireSession_writesMaxAgeZero_regardlessOfAttribute() {
        RememberMeWebSessionIdResolver resolver =
                new RememberMeWebSessionIdResolver(propsWithDefaults(), 30L);
        MockServerWebExchange exchange = exchangeFor("/api/auth/logout");
        // Even with rememberMe=TRUE on the attribute, expireSession must clear the cookie.
        exchange.getAttributes().put(RememberMeWebSessionIdResolver.REMEMBER_ME_ATTR, Boolean.TRUE);

        resolver.expireSession(exchange);

        ResponseCookie cookie = exchange.getResponse().getCookies().getFirst("SESSION");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getMaxAge()).isZero();
        assertThat(cookie.getValue()).isEmpty();
    }

    // -------- Scenario H' — locks the constructor's setCookieName(...) propagation against --------
    // -------- regression: inherited expireSession must use the configured custom name. -------
    @Test
    void expireSession_withCustomCookieName_writesUnderConfiguredName() {
        ServerProperties p = propsWithDefaults();
        p.getReactive().getSession().getCookie().setName("ALT");
        RememberMeWebSessionIdResolver resolver = new RememberMeWebSessionIdResolver(p, 30L);
        MockServerWebExchange exchange = exchangeFor("/api/auth/logout");

        resolver.expireSession(exchange);

        assertThat(exchange.getResponse().getCookies().getFirst("SESSION")).isNull();
        ResponseCookie alt = exchange.getResponse().getCookies().getFirst("ALT");
        assertThat(alt).isNotNull();
        assertThat(alt.getMaxAge()).isZero();
    }

    // -------- Scenario F (secure inverse): https-scheme seed=true must yield to config-set false ---
    @Test
    void setSessionId_secureExplicitFalse_winsOverHttpsSchemeSeed() {
        // Seed default for `secure` is request-derived (scheme==https). This guards against a
        // refactor that drops the PropertyMapper override on `secure` — the false→true direction
        // (covered above) would still pass while true→false silently regresses.
        ServerProperties p = propsWithDefaults();
        p.getReactive().getSession().getCookie().setSecure(false);
        RememberMeWebSessionIdResolver resolver = new RememberMeWebSessionIdResolver(p, 30L);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("https://localhost/api/auth/login").build());

        resolver.setSessionId(exchange, "id");

        ResponseCookie cookie = exchange.getResponse().getCookies().getFirst("SESSION");
        assertThat(cookie.isSecure()).isFalse();
    }
}
