package com.botfunnel.common;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HttpRequestUtilsTest {

    @Mock
    HttpServletRequest request;

    @Test
    void extractIp_prefersLeftmostXForwardedFor() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.1, 10.0.0.5");

        assertThat(HttpRequestUtils.extractIp(request)).isEqualTo("203.0.113.1");
    }

    @Test
    void extractIp_singleXForwardedForEntry() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.1");

        assertThat(HttpRequestUtils.extractIp(request)).isEqualTo("203.0.113.1");
    }

    @Test
    void extractIp_fallbackToRemoteAddrWhenHeaderMissing() {
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("192.168.1.10");

        assertThat(HttpRequestUtils.extractIp(request)).isEqualTo("192.168.1.10");
    }

    @Test
    void extractIp_returnsUnknownWhenNothingResolves() {
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn(null);

        assertThat(HttpRequestUtils.extractIp(request)).isEqualTo("unknown");
    }

    @Test
    void extractIp_blankXForwardedForFallsBack() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("   ");
        when(request.getRemoteAddr()).thenReturn("192.168.1.10");

        assertThat(HttpRequestUtils.extractIp(request)).isEqualTo("192.168.1.10");
    }

    @Test
    void extractIp_xForwardedForWithLeadingSpacesIsTrimmed() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("  203.0.113.1  , 10.0.0.5");

        assertThat(HttpRequestUtils.extractIp(request)).isEqualTo("203.0.113.1");
    }

    @Test
    void extractUserAgent_returnsHeaderValueWhenWithinCap() {
        when(request.getHeader("User-Agent")).thenReturn("Mozilla/5.0 (X11; Linux x86_64)");

        assertThat(HttpRequestUtils.extractUserAgent(request))
                .isEqualTo("Mozilla/5.0 (X11; Linux x86_64)");
    }

    @Test
    void extractUserAgent_capsAtLengthLimit() {
        String overlong = "a".repeat(HttpRequestUtils.USER_AGENT_MAX + 50);
        when(request.getHeader("User-Agent")).thenReturn(overlong);

        String result = HttpRequestUtils.extractUserAgent(request);

        assertThat(result).hasSize(HttpRequestUtils.USER_AGENT_MAX);
        assertThat(result).isEqualTo("a".repeat(HttpRequestUtils.USER_AGENT_MAX));
    }

    @Test
    void extractUserAgent_returnsNullWhenHeaderAbsent() {
        when(request.getHeader("User-Agent")).thenReturn(null);

        assertThat(HttpRequestUtils.extractUserAgent(request)).isNull();
    }

    @Test
    void cannotBeInstantiated() throws Exception {
        // D13 anti-instantiation gate: utility class — constructor must be private.
        Constructor<HttpRequestUtils> ctor = HttpRequestUtils.class.getDeclaredConstructor();
        assertThat(Modifier.isPrivate(ctor.getModifiers())).isTrue();
    }
}
