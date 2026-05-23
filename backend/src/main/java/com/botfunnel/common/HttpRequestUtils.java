package com.botfunnel.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Shared servlet-side request helpers for client IP and User-Agent extraction.
 *
 * <p>Consolidates the four controller-side duplicates (auth, project, profile, bot) that all
 * read the X-Forwarded-For / Remote-Addr pair the same way and cap the User-Agent at the same
 * length. Lives in {@code common} to keep the module graph acyclic.
 */
public final class HttpRequestUtils {

    /**
     * Same cap previously used in {@code AuthService} and {@code ProjectController}. Kept here
     * so per-controller duplicates can import this constant when they are dropped in later tasks.
     */
    public static final int USER_AGENT_MAX = 500;

    private HttpRequestUtils() {}

    /**
     * Returns the client IP for {@code request}.
     *
     * <p>Trust note: {@code X-Forwarded-For} is honoured unconditionally. Production deployments
     * must front the backend with a reverse proxy (nginx/traefik) that overwrites this header —
     * without one, clients can forge it and bypass the per-IP brute-force counter.
     *
     * <p>Resolution order: leftmost trimmed entry of {@code X-Forwarded-For} → {@link
     * HttpServletRequest#getRemoteAddr()} → the literal string {@code "unknown"}.
     */
    public static String extractIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // X-Forwarded-For chains through proxies; the leftmost entry is the original client.
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String remote = request.getRemoteAddr();
        return (remote != null && !remote.isEmpty()) ? remote : "unknown";
    }

    /**
     * Returns the {@code User-Agent} header for {@code request}, capped at {@link
     * #USER_AGENT_MAX} characters. Returns {@code null} when the header is absent (matches the
     * prior {@code capUserAgent(null) → null} semantic so persisted event fields stay null
     * instead of empty).
     */
    public static String extractUserAgent(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        if (userAgent == null) return null;
        return userAgent.length() > USER_AGENT_MAX ? userAgent.substring(0, USER_AGENT_MAX) : userAgent;
    }
}
