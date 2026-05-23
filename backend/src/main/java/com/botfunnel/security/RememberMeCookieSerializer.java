package com.botfunnel.security;

import com.botfunnel.common.SessionAttributes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.Cookie.SameSite;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Per-request remember-me cookie writer. {@link DefaultCookieSerializer} applies a single
 * {@code cookieMaxAge} value to every cookie write; per-request remember-me requires overriding
 * {@link #writeCookieValue(CookieValue)} to mutate the {@link CookieValue#setCookieMaxAge(int)
 * cookie max-age} before the parent writes the {@code Set-Cookie} header. When
 * {@link SessionAttributes#REMEMBER_ME_ATTR} on the {@link HttpServletRequest} is
 * {@code Boolean.TRUE}, the cookie carries a Max-Age of {@code rememberMeDays * 86400}.
 * Absent or {@code Boolean.FALSE} leaves the default {@code Max-Age=-1} (session-scoped).
 *
 * <p>Constructor wires every supported cookie attribute through {@link PropertyMapper} from
 * {@link ServerProperties#getServlet()}'s session/cookie tree so the
 * {@code server.servlet.session.cookie.*} keys and their {@code SESSION_COOKIE_*} env-var
 * defaults continue to flow through unchanged after the WebFlux → MVC migration.
 *
 * <p>Security: never logs the session id, cookie value, or any {@code SecurityContext} content —
 * matches the silent invariant of the deleted reactive {@code RememberMeWebSessionIdResolver}.
 */
public class RememberMeCookieSerializer extends DefaultCookieSerializer {

    private final long rememberMeDays;

    public RememberMeCookieSerializer(ServerProperties serverProperties, long rememberMeDays) {
        this.rememberMeDays = rememberMeDays;
        Cookie cookieProps = serverProperties.getServlet().getSession().getCookie();
        PropertyMapper map = PropertyMapper.get().alwaysApplyingWhenNonNull();
        map.from(cookieProps::getName).to(this::setCookieName);
        map.from(cookieProps::getHttpOnly).to(this::setUseHttpOnlyCookie);
        map.from(cookieProps::getSecure).to(this::setUseSecureCookie);
        map.from(cookieProps::getSameSite).as(SameSite::attributeValue).to(this::setSameSite);
        map.from(cookieProps::getPath).to(this::setCookiePath);
        map.from(cookieProps::getDomain).to(this::setDomainName);
    }

    @Override
    public void writeCookieValue(CookieValue cookieValue) {
        HttpServletRequest request = cookieValue.getRequest();
        Object attr = request.getAttribute(SessionAttributes.REMEMBER_ME_ATTR);
        if (Boolean.TRUE.equals(attr)) {
            cookieValue.setCookieMaxAge((int) (rememberMeDays * 86400L));
        }
        // Absent or Boolean.FALSE → leave the default Max-Age=-1 (session-scoped cookie).
        super.writeCookieValue(cookieValue);
    }
}
