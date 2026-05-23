package com.botfunnel.common;

/**
 * Shared keys for per-request attributes carried between the auth and security layers.
 *
 * <p>Lives in {@code common} so neither {@code auth} nor {@code security} has to import the
 * other for this purely-data contract — keeping the module graph acyclic.
 */
public final class SessionAttributes {

    private SessionAttributes() {}

    /**
     * Per-request attribute key. {@code AuthService.openSession} writes
     * {@link Boolean#TRUE}/{@link Boolean#FALSE}; the cookie-resolver reads it on cookie flush
     * to decide whether the session cookie carries a {@code Max-Age} (remember-me) or stays
     * session-scoped. Absent or {@code FALSE} → session-only cookie.
     *
     * <p>Literal value is wire-format for in-flight requests — do not rename.
     */
    public static final String REMEMBER_ME_ATTR = "com.botfunnel.auth.rememberMe";
}
