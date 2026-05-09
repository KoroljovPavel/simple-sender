package com.botfunnel.security;

import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.Cookie.SameSite;
import org.springframework.http.ResponseCookie;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.session.CookieWebSessionIdResolver;

import java.time.Duration;

/**
 * Per-request remember-me cookie writer. Boot's auto-configured {@link CookieWebSessionIdResolver}
 * only supports a single {@code cookieMaxAge} across all requests (set at bean creation), and
 * {@code addCookieInitializer} receives only the builder, not the exchange — so the per-request
 * rememberMe flag cannot be applied through the standard extension points. Mutating bean state
 * ({@code setCookieMaxAge}) per request is unsafe under WebFlux concurrency. Instead, this class
 * overrides {@code setSessionId} to build the cookie from scratch using {@link ServerProperties}
 * for static flags and the {@link #REMEMBER_ME_ATTR} exchange attribute for {@code Max-Age}.
 *
 * <p>{@code resolveSessionIds} (cookie read) and {@code expireSession} ({@code Max-Age=0} on
 * logout) are inherited from {@link CookieWebSessionIdResolver} unchanged — pre-fix cookies
 * remain valid and logout still clears.
 *
 * <p>The cookie {@code name} is propagated to the parent at construction so inherited
 * resolve/expire use the same name as {@code setSessionId}; runtime mutation of
 * {@code ServerProperties.getReactive().getSession().getCookie().getName()} is not supported,
 * matching Boot's auto-config behavior. All other cookie attributes are read live on every
 * request inside {@code setSessionId}.
 */
public class RememberMeWebSessionIdResolver extends CookieWebSessionIdResolver {

    /**
     * {@link org.springframework.web.server.ServerWebExchange} attribute key. {@code AuthService}
     * writes {@link Boolean#TRUE}/{@link Boolean#FALSE}; {@link #setSessionId} reads it on cookie
     * flush. Absent or {@code FALSE} → session-only cookie (no {@code Max-Age}).
     */
    public static final String REMEMBER_ME_ATTR = "com.botfunnel.auth.rememberMe";

    private final ServerProperties serverProperties;
    private final long rememberMeDays;

    public RememberMeWebSessionIdResolver(ServerProperties serverProperties, long rememberMeDays) {
        this.serverProperties = serverProperties;
        this.rememberMeDays = rememberMeDays;
        // Propagate the configured cookie name to the parent so inherited resolveSessionIds() and
        // expireSession() read/write under the same name. Mirrors Boot's auto-config.
        String cookieName = serverProperties.getReactive().getSession().getCookie().getName();
        if (StringUtils.hasText(cookieName)) {
            setCookieName(cookieName);
        }
    }

    @Override
    public void setSessionId(ServerWebExchange exchange, String id) {
        Assert.notNull(id, "'id' is required");
        Cookie cookieProps = serverProperties.getReactive().getSession().getCookie();
        String name = getCookieName();

        // Seed defaults that match parent CookieWebSessionIdResolver.initCookie (path="/",
        // httpOnly=true, secure=https-scheme, sameSite=Lax). PropertyMapper below overlays only
        // values that are explicitly set in ServerProperties. Under this project's
        // application.properties the http-only/secure/same-site keys are always set, so those
        // seeds are overwritten on every request — they survive only if a key is removed entirely
        // (parity fallback with Boot's auto-config). The path seed always survives because
        // ServerProperties exposes path=null by default.
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, id)
                .path(exchange.getRequest().getPath().contextPath().value() + "/")
                .httpOnly(true)
                .secure("https".equalsIgnoreCase(exchange.getRequest().getURI().getScheme()))
                .sameSite("Lax");

        PropertyMapper map = PropertyMapper.get().alwaysApplyingWhenNonNull();
        map.from(cookieProps::getDomain).to(builder::domain);
        map.from(cookieProps::getPath).to(builder::path);
        map.from(cookieProps::getHttpOnly).to(builder::httpOnly);
        map.from(cookieProps::getSecure).to(builder::secure);
        map.from(cookieProps::getPartitioned).to(builder::partitioned);
        map.from(cookieProps::getSameSite).as(SameSite::attributeValue).to(builder::sameSite);
        // Note: cookieProps.getMaxAge() is intentionally NOT mapped here. The auto-config maps it
        // to set a static cookie Max-Age — but we override Max-Age per request based on the
        // REMEMBER_ME_ATTR exchange attribute below.

        Boolean rememberMe = exchange.getAttribute(REMEMBER_ME_ATTR);
        if (Boolean.TRUE.equals(rememberMe)) {
            builder.maxAge(Duration.ofDays(rememberMeDays));
        }
        // attribute absent or Boolean.FALSE → no .maxAge() call. Builder default is
        // Duration.ofSeconds(-1), serialized as a session cookie (no Max-Age, no Expires).
        // Calling .maxAge(null) would throw IllegalArgumentException — Assert.notNull on the builder.

        exchange.getResponse().getCookies().set(name, builder.build());
    }
}
