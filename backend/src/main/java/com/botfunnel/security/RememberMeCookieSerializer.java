package com.botfunnel.security;

import com.botfunnel.common.SessionAttributes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.Cookie.SameSite;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Per-request remember-me cookie writer. Boot's auto-configured {@code DefaultCookieSerializer}
 * supports only a single {@code cookieMaxAge} value (set at bean creation); the per-request
 * remember-me flag must be applied on the {@link CookieValue} before Spring Session writes the
 * cookie. This subclass overrides {@link #writeCookieValue(CookieValue)} to read
 * {@link SessionAttributes#REMEMBER_ME_ATTR} from the current {@link HttpServletRequest} and,
 * when {@code Boolean.TRUE}, override the {@code Max-Age} for that one cookie write.
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
