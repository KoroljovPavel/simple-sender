package com.botfunnel.security;

import com.botfunnel.common.SessionAttributes;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.server.Cookie.SameSite;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer.CookieValue;

import static org.assertj.core.api.Assertions.assertThat;

class RememberMeCookieSerializerTest {

    private static ServerProperties propsWithDefaults() {
        // Mirrors application.properties: name=SESSION (Boot default), http-only=true,
        // secure=false, same-site=lax.
        ServerProperties p = new ServerProperties();
        org.springframework.boot.web.server.Cookie c = p.getServlet().getSession().getCookie();
        c.setName("SESSION");
        c.setHttpOnly(true);
        c.setSecure(false);
        c.setSameSite(SameSite.LAX);
        return p;
    }

    private static CookieValue cookieValueFor(MockHttpServletRequest request,
                                              MockHttpServletResponse response,
                                              String value) {
        return new CookieValue(request, response, value);
    }

    @Test
    void writeCookieValue_withRememberMeTrue_setsMaxAgeToRememberMeDays() {
        RememberMeCookieSerializer serializer =
                new RememberMeCookieSerializer(propsWithDefaults(), 30L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(SessionAttributes.REMEMBER_ME_ATTR, Boolean.TRUE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        serializer.writeCookieValue(cookieValueFor(request, response, "session-id-true"));

        Cookie cookie = response.getCookie("SESSION");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("session-id-true");
        assertThat(cookie.getMaxAge()).isEqualTo((int) (30L * 86400L));
    }

    @Test
    void writeCookieValue_withoutRememberMeAttr_leavesDefaultMaxAge() {
        RememberMeCookieSerializer serializer =
                new RememberMeCookieSerializer(propsWithDefaults(), 30L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        // No attribute set — must remain session-scoped.
        MockHttpServletResponse response = new MockHttpServletResponse();

        serializer.writeCookieValue(cookieValueFor(request, response, "lazy-id"));

        Cookie cookie = response.getCookie("SESSION");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getMaxAge()).isEqualTo(-1);
    }

    @Test
    void writeCookieValue_withRememberMeFalse_leavesDefaultMaxAge() {
        RememberMeCookieSerializer serializer =
                new RememberMeCookieSerializer(propsWithDefaults(), 30L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(SessionAttributes.REMEMBER_ME_ATTR, Boolean.FALSE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        serializer.writeCookieValue(cookieValueFor(request, response, "session-id-false"));

        Cookie cookie = response.getCookie("SESSION");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getMaxAge()).isEqualTo(-1);
    }

    @ParameterizedTest
    @ValueSource(longs = {30L, 7L, 60L})
    void writeCookieValue_maxAgeReadsConfiguredTtl_parameterized(long days) {
        RememberMeCookieSerializer serializer =
                new RememberMeCookieSerializer(propsWithDefaults(), days);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(SessionAttributes.REMEMBER_ME_ATTR, Boolean.TRUE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        serializer.writeCookieValue(cookieValueFor(request, response, "id"));

        Cookie cookie = response.getCookie("SESSION");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getMaxAge()).isEqualTo((int) (days * 86400L));
    }

    @Test
    void constructor_appliesServerPropertiesCookieAttributes() {
        ServerProperties p = new ServerProperties();
        org.springframework.boot.web.server.Cookie c = p.getServlet().getSession().getCookie();
        c.setName("ALT");
        c.setHttpOnly(false);
        c.setSecure(true);
        c.setSameSite(SameSite.STRICT);
        c.setPath("/app");
        c.setDomain("example.test");
        RememberMeCookieSerializer serializer = new RememberMeCookieSerializer(p, 30L);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        serializer.writeCookieValue(cookieValueFor(request, response, "id"));

        // Custom name applied — no cookie under default "SESSION".
        assertThat(response.getCookie("SESSION")).isNull();
        Cookie alt = response.getCookie("ALT");
        assertThat(alt).isNotNull();
        assertThat(alt.isHttpOnly()).isFalse();
        assertThat(alt.getSecure()).isTrue();
        assertThat(alt.getPath()).isEqualTo("/app");
        assertThat(alt.getDomain()).isEqualTo("example.test");
        // SameSite is emitted via the Set-Cookie header attribute (servlet Cookie API has no
        // first-class accessor in this version), so assert against the raw Set-Cookie header.
        assertThat(response.getHeader("Set-Cookie")).contains("SameSite=Strict");
    }
}
