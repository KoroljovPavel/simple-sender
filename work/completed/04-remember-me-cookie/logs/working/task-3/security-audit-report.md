# Security Audit — 04-remember-me-cookie (Task 3)

**Audit Wave** security-only review of Wave 1 deliverables. Methodology: `security-auditor` skill — OWASP Top 10 (2021), with primary focus on **A02 (Cryptographic Failures)** and **A07 (Identification & Authentication Failures)**, as scoped by `tasks/3.md`. Static analysis only — no backend run, no test execution, no code modifications.

**Scope (read end-to-end):**
- `backend/src/main/java/com/botfunnel/security/RememberMeWebSessionIdResolver.java` (new, 97 LOC)
- `backend/src/main/java/com/botfunnel/security/SecurityConfig.java` (modified — `webSessionIdResolver` `@Bean` + `ServerProperties` injection)
- `backend/src/main/java/com/botfunnel/auth/AuthService.java` (modified — `openSession` body lines 591–615; `register` call site line 168)
- `backend/src/main/resources/application.properties` (config source of truth)
- `backend/src/test/java/com/botfunnel/security/RememberMeWebSessionIdResolverTest.java` (corroborates the attribute-absent branch — Scenario C, line 73)

**Reference baselines:** tech-spec Decisions 1–6, Risks 1–3; user-spec AC-1..AC-8; Task-2 code audit (`logs/working/task-2/code-audit-report.md`) for non-security cross-checks; Task-1 round-1 security-auditor JSON (`logs/working/task-1/security-auditor-1.json`) for prior-art.

## Executive Verdict

**`approve`** — zero blocker, zero major, zero minor findings. Two informational notes (`info`) are recorded for transparency. OWASP **A02** and **A07** sign off **clean** against the audited surface.

The fix neither introduces new attack surface nor weakens any existing cookie-security or CSRF property of the application. The per-request channel (`ServerWebExchange` attribute) is server-set only, not externally injectable. Pre-fix cookies remain valid (no flag day). Logout still emits `Max-Age=0`. The resolver is a thin, allocation-only transform: it never mutates bean state, never touches the session id beyond writing it, and never logs.

## Per-item Verdicts (tasks/3.md → "What to do" item 4)

For each item: verdict (`pass` / `fail` / `n/a`) + evidence (file:line or one-line quote).

### 1. No hard-coded cookie security flags — **pass**

`name`, `httpOnly`, `secure`, `sameSite`, `path`, `domain`, `partitioned` are read from `serverProperties.getReactive().getSession().getCookie()` via `PropertyMapper.alwaysApplyingWhenNonNull()`. No string literals, no constants for these attributes.

Evidence:
- `RememberMeWebSessionIdResolver.java:60` — `Cookie cookieProps = serverProperties.getReactive().getSession().getCookie();`
- `RememberMeWebSessionIdResolver.java:76–82` — `PropertyMapper` mappings:
  ```
  map.from(cookieProps::getDomain).to(builder::domain);
  map.from(cookieProps::getPath).to(builder::path);
  map.from(cookieProps::getHttpOnly).to(builder::httpOnly);
  map.from(cookieProps::getSecure).to(builder::secure);
  map.from(cookieProps::getPartitioned).to(builder::partitioned);
  map.from(cookieProps::getSameSite).as(SameSite::attributeValue).to(builder::sameSite);
  ```
- `RememberMeWebSessionIdResolver.java:51` — cookie name propagated to parent: `String cookieName = serverProperties.getReactive().getSession().getCookie().getName();`
- `grep` for `"SESSION"`, `SameSite.LAX`, `httpOnly(true)`, `secure(true)` in `RememberMeWebSessionIdResolver.java`: only matches are the parity-fallback seeds at lines 72 (`.httpOnly(true)`) and 74 (`.sameSite("Lax")`). These mirror **parent class** `CookieWebSessionIdResolver.initCookie` defaults and are **always overwritten** by the `PropertyMapper` overlay under the project's `application.properties` (which always sets the keys). Class-level Javadoc lines 62–69 documents this. Same conclusion already reached in Task-2 audit `Decision 3 — PASS` (code-audit-report.md "Cookie attributes inventory").

Decision 3 honored; user-spec AC-1 honored.

### 2. Configurable cookie attributes still flow through — **pass**

Changing `SESSION_COOKIE_SECURE`, `SESSION_COOKIE_SAME_SITE`, or `server.reactive.session.cookie.name` via env vars must change the resulting `Set-Cookie` header without code changes. Verified by code-reading:

- `application.properties:14–16`:
  - `server.reactive.session.cookie.http-only=true`
  - `server.reactive.session.cookie.secure=${SESSION_COOKIE_SECURE:false}`
  - `server.reactive.session.cookie.same-site=${SESSION_COOKIE_SAME_SITE:lax}`
- These keys feed Spring Boot's `ServerProperties.Reactive.Session.Cookie` POJO automatically.
- The resolver reads that POJO live on every `setSessionId` invocation (`RememberMeWebSessionIdResolver.java:60`) and re-derives all cookie attributes from it via `PropertyMapper` (`RememberMeWebSessionIdResolver.java:76–82`).
- Cookie `name`: at construction time, the configured name is propagated to the parent via `setCookieName(...)` (`RememberMeWebSessionIdResolver.java:51–54`). Parent `getCookieName()` is read on line 61 inside `setSessionId`, so renaming via `server.reactive.session.cookie.name` flips the cookie name on the next bean instantiation (i.e., on the next deploy / restart) — same lifecycle as Boot's auto-config. Test `setSessionId_cookieName_passThroughFromConfig` (`RememberMeWebSessionIdResolverTest.java:125`) corroborates the wiring.

Risk 1 mitigated; user-spec AC-8 honored.

### 3. No session-id leakage in logs — **pass**

Static grep against the audited files:

- `RememberMeWebSessionIdResolver.java`: `grep -nE "log\.|System\.out|System\.err"` → **0 matches**. The class declares no `Logger` field and imports no `org.slf4j.*`.
- `AuthService.openSession` body (lines 591–615): `grep -nE "log\.|System\.out|System\.err"` → **0 matches**. The only logging in `AuthService` overall (`log.warn(...)` at lines 160, 215, 241, 273, 316, 471, 507, 549, 621) sits in unrelated branches (Redis errors, email-dispatch failures, brute-force resets); **none reach the openSession body** and **none reference the session id**, the cookie value, or any `SecurityContext` content.
- `SecurityConfig.java`: zero logging (verified by reading lines 1–108 end-to-end).

Cookie value (session id) is never serialized into a log argument. The resolver does not even hold a reference to the session id beyond the `id` parameter, which is consumed exclusively by `ResponseCookie.from(name, id)` (`RememberMeWebSessionIdResolver.java:70`) and `Assert.notNull(id, ...)` (line 59).

### 4. No regression in CSRF/XSRF flow — **pass**

`SecurityConfig` continues to register the same CSRF stack (`SecurityConfig.java:55–65`):

- `CookieServerCsrfTokenRepository.withHttpOnlyFalse()` — `SecurityConfig.java:56` (line "csrfTokenRepository(...)").
- `ServerCsrfTokenRequestAttributeHandler` — `SecurityConfig.java:64` (plain handler, not the BREACH-masking XOR variant; correct for the cookie/header round-trip with raw tokens).
- `csrfCookieMaterializer()` `WebFilter` registered `addFilterAfter(..., SecurityWebFiltersOrder.CSRF)` — `SecurityConfig.java:76`. Implementation lines 80–90 unchanged.
- Authorization rules unchanged: `pathMatchers("/health").permitAll()`, `/api/auth/**` `permitAll`, `/api/**` `authenticated`, catch-all `authenticated` (`SecurityConfig.java:68–73`).

The new `@Bean WebSessionIdResolver` is on a **different cookie** (`SESSION`) than the CSRF cookie (`XSRF-TOKEN`, written by `CookieServerCsrfTokenRepository`). The resolver swap only affects `WebSessionManager` calls into `setSessionId`/`resolveSessionIds`/`expireSession`; it does not interpose on the CSRF filter chain. CSRF rules for non-safe methods on session-cookie-authenticated endpoints remain configured (`/api/**` is `authenticated`, CSRF is enabled via the unconditional `csrf(csrf -> ...)` block — there is no `csrf().disable()` anywhere).

### 5. No auth bypass via missing `REMEMBER_ME_ATTR` — **pass**

Fallback path when the attribute is absent OR `Boolean.FALSE`:

- Read: `Boolean rememberMe = exchange.getAttribute(REMEMBER_ME_ATTR);` (`RememberMeWebSessionIdResolver.java:87`). Returns `null` when absent.
- Branch: `if (Boolean.TRUE.equals(rememberMe)) { builder.maxAge(...); }` (`RememberMeWebSessionIdResolver.java:88–90`). `Boolean.TRUE.equals(null)` and `Boolean.TRUE.equals(Boolean.FALSE)` both evaluate to `false` — null-safe, false-safe.
- No `else { throw }`, no `else { return }`, no default to 30-day persistence.
- Outcome: when `rememberMe` is absent or `FALSE`, the builder's default `maxAge` (`Duration.ofSeconds(-1)`) survives, which `ResponseCookie` serializes as a session cookie (no `Max-Age`, no `Expires`). The session id is **still written** via `exchange.getResponse().getCookies().set(name, builder.build())` on line 95 — **not skipped, not dropped**.

The fallback is the **safe direction** for both privacy and auth:
- Never escalates to authenticated-30-days when no one asked for it.
- Never silently drops the cookie (which would force re-login on every request).
- Never throws, so non-login flows (lazy session, WebSocket upgrade, anonymous flows) cannot break the response.

Unit-test coverage: `setSessionId_attributeAbsent_writesSessionCookieWithoutMaxAge` (`RememberMeWebSessionIdResolverTest.java:73–84`) explicitly asserts the absent-attribute branch produces `cookie.getMaxAge().isNegative()` (i.e., session-only cookie). Decision 2 fallback honored; Risk 3 mitigated.

### 6. Per-request concurrency safety — **pass**

No bean-level mutable state is touched in `setSessionId`:

- Class declares only two fields: `private final ServerProperties serverProperties;` and `private final long rememberMeDays;` (`RememberMeWebSessionIdResolver.java:43–44`). Both are `final` and assigned in the constructor.
- `setSessionId` allocates a fresh `ResponseCookieBuilder` (`RememberMeWebSessionIdResolver.java:70`) and a fresh `PropertyMapper` (line 76) per call. No shared builder, no shared mapper.
- The only setter call on `this` is `setCookieName(cookieName)` inside the **constructor** (`RememberMeWebSessionIdResolver.java:53`), executing before the bean is published as a singleton — i.e., before any concurrent request can reach it. After construction, `getCookieName()` reads the parent's already-set field; no further mutation path exists.
- No `synchronized`, no `volatile`, no `AtomicReference`, no `ThreadLocal` — and that is correct because there is no shared mutable state to guard.

Decision 1 honored; the WebFlux concurrency risk explicitly called out in Decision 1 ("Mutating bean state per request ... is unsafe under WebFlux concurrency. Building the cookie from scratch in the override is the only race-free option") is materially eliminated.

### 7. TTL synchronization — **pass**

Cookie `Max-Age` and `WebSession.maxIdleTime` derive from the **same** `app.session.ttl-*` keys.

- `WebSession.maxIdleTime` source: `AuthService.java:99` injects `@Value("${app.session.ttl-remember-me-days:30}") long rememberMeDays`; `AuthService.java:592` computes `Duration ttl = rememberMe ? Duration.ofDays(rememberMeDays) : Duration.ofHours(defaultHours);` and applies it at line 613 (`session.setMaxIdleTime(ttl)`).
- Cookie `Max-Age` source: `SecurityConfig.java:45` injects `@Value("${app.session.ttl-remember-me-days:30}") long rememberMeDays` and passes it to `new RememberMeWebSessionIdResolver(serverProperties, rememberMeDays)` (line 48); the resolver applies it at `RememberMeWebSessionIdResolver.java:89` (`builder.maxAge(Duration.ofDays(rememberMeDays))`).
- `application.properties:18`: `app.session.ttl-remember-me-days=${SESSION_TTL_REMEMBER_ME_DAYS:30}` — single env-overridable key feeds both consumers.

No drift surface introduced. No new `app.session.cookie-max-age-*` knob. Decision 4 honored; user-spec AC-3 honored.

### 8. Logout still clears the cookie via inherited `expireSession` — **pass**

The fix does not override `expireSession`. Inherited from `CookieWebSessionIdResolver`, which writes the cookie with `Max-Age=0` regardless of the `REMEMBER_ME_ATTR` exchange attribute.

- `RememberMeWebSessionIdResolver.java:34` — class extends `CookieWebSessionIdResolver`. The only override is `setSessionId` (line 58); `grep` for `expireSession` finds zero `@Override` (only Javadoc references on lines 24, 50). `resolveSessionIds` likewise inherited unchanged.
- `AuthService.logout` (`AuthService.java:251–256`) calls `exchange.getSession().flatMap(WebSession::invalidate)` — same as pre-fix; the framework calls `expireSession(exchange)` on the resolver in response. Inherited expiry uses the configured cookie name (because the constructor propagated it via `setCookieName` — see item 1).
- Test `expireSession_writesMaxAgeZero_regardlessOfAttribute` (`RememberMeWebSessionIdResolverTest.java:221–234`) explicitly asserts that `expireSession` writes `Max-Age=0` even when `REMEMBER_ME_ATTR=Boolean.TRUE` is present. Companion test `expireSession_withCustomCookieName_writesUnderConfiguredName` (line 239) locks the cookie-name propagation against future regression.

Decision 1 (inherit logout behavior) honored; user-spec AC-5 honored.

### 9. OWASP A02 — Cryptographic Failures — **pass**

See dedicated section below ("OWASP A02 sign-off").

### 10. OWASP A07 — Identification & Authentication Failures — **pass**

See dedicated section below ("OWASP A07 sign-off").

### 11. Pre-fix backward compatibility — **pass**

`resolveSessionIds` is inherited from `CookieWebSessionIdResolver` unchanged. Pre-fix cookies (no `Max-Age`) hitting a post-fix backend still resolve via the inherited cookie-read path; the user remains authenticated until the server-side session TTL elapses or the user re-logs in.

- The override touches only `setSessionId` (`RememberMeWebSessionIdResolver.java:57–96`); no override of `resolveSessionIds`. The parent's read implementation is used as-is.
- `Set-Cookie` is a write-side concern; client-side cookie deletion is governed by the **previous** `Max-Age` (or its absence). New cookies get `Max-Age=2592000`; old cookies continue with whatever they had.
- Test `resolveSessionIds_readsCookieValueFromRequest_inheritedBehavior` (`RememberMeWebSessionIdResolverTest.java:208–217`) corroborates that inherited resolve works against a request with a vanilla `HttpCookie("SESSION", "abc")` — i.e., no `Max-Age` on the request side.
- The deploy itself does not invalidate any session: the session id format and the `sessions` collection in MongoDB are unchanged; only the way **new** `Set-Cookie` headers are written changes.

Decision 5 honored; user-spec AC-4 honored.

### 12. Register-flow regression — **pass**

`AuthService.register` (`AuthService.java:141–171`) still calls `openSession(saved, false, exchange)` at line **168**:

```
return openSession(saved, false, exchange)
        .thenReturn(new RegisterResponse(saved.getId()));
```

The boolean is `false` — literal, not derived from a request field, not flipped. Decision 6 honored; user-spec AC-6 honored.

Effect downstream:
- `AuthService.openSession` writes `exchange.getAttributes().put(REMEMBER_ME_ATTR, Boolean.valueOf(false))` (`AuthService.java:604–605`).
- Resolver reads `Boolean.FALSE` and produces a session-only cookie (per item 5).

Test layer: `AuthServiceTest` (per Task-2 audit and decisions.md) asserts the register-auto-login path puts `(REMEMBER_ME_ATTR, Boolean.FALSE)` on the exchange — locks AC-6 against silent regression.

## OWASP A02 — Cryptographic Failures — **sign-off: clean**

Cookie protections (`Secure`, `HttpOnly`, `SameSite`) are sourced from runtime config, not from constants in the fix code. The fix never weakens any of these.

**Evidence:**

| Attribute | Source in audited code | Production value |
|-----------|------------------------|------------------|
| `Secure` | `cookieProps.getSecure()` (`RememberMeWebSessionIdResolver.java:80`); driven by `application.properties:15` (`server.reactive.session.cookie.secure=${SESSION_COOKIE_SECURE:false}`). | Production must set `SESSION_COOKIE_SECURE=true` (the dev default `false` is appropriate only for plain HTTP local dev; deploy convention is documented in `references/deployment.md`). The fix does not interfere with this lever. |
| `HttpOnly` | `cookieProps.getHttpOnly()` (`RememberMeWebSessionIdResolver.java:79`); `application.properties:14` declares `server.reactive.session.cookie.http-only=true` unconditionally. | `true` always (env override would have to explicitly disable it). |
| `SameSite` | `cookieProps.getSameSite()` mapped via `SameSite::attributeValue` (`RememberMeWebSessionIdResolver.java:82`); `application.properties:16` defaults to `lax`, env-overridable to `strict` / `none`. | `Lax` default; tightenable via `SESSION_COOKIE_SAME_SITE=strict`. |

**Cookie value (session id):** opaque, generated by `spring-session-data-mongodb`, never decoded or mutated by the resolver. The resolver only writes the bytes Spring hands it (`ResponseCookie.from(name, id)` at line 70). No custom encoding, no truncation, no transformation that could weaken entropy.

**No new cryptographic primitives** introduced by the fix — no encrypt, no sign, no hash, no MAC. The fix is a thin transform over `(rememberMe, ServerProperties) → Set-Cookie attributes`. Therefore there is no algorithm choice, no key management, and no plaintext-storage decision to evaluate.

**No hardcoded secrets** in the audited files — verified by manual reading of all four target files. (`AuthService.DUMMY_HASH` at line 49 is an intentional anti-enumeration constant for unknown-user response timing, documented at lines 47–48 and called out in `Decision 13` of an earlier feature; not part of this fix and not exploitable as a credential.)

Verdict: **A02 clean.** The fix preserves every cookie-confidentiality flag the application already had, sources them all from a single env-driven config, and does not introduce any new cryptographic surface area.

## OWASP A07 — Identification & Authentication Failures — **sign-off: clean**

Per-request `rememberMe` cannot be forged from outside the request. Logout / terminate-all flows are unchanged. Session-cookie lifetime is gated by server-side configuration, not by client-controlled headers.

**Evidence — attribute is server-set only:**

- `REMEMBER_ME_ATTR` is **only written** in two places, both inside `AuthService` (server-side, after authentication has been decided):
  1. `AuthService.java:604–605` (the only write site): `exchange.getAttributes().put(RememberMeWebSessionIdResolver.REMEMBER_ME_ATTR, Boolean.valueOf(rememberMe));` — invoked from `openSession(...)`, which is called from `authenticate(...)` after BCrypt verification (`AuthService.java:583`) and from `register(...)` after the user document is saved (`AuthService.java:168`). In both cases, `rememberMe` is the **server's** boolean — derived from `LoginRequest.isRememberMe()` (a JSON field, but only consumed after auth) or hard-coded `false`.
  2. The same `REMEMBER_ME_ATTR` constant is used by tests, never by request handlers.
- WebFlux does **not** auto-bind any HTTP header / query string / cookie to `ServerWebExchange.getAttributes()`. Attributes are populated only by `WebFilter` chain entries that explicitly call `exchange.getAttributes().put(...)`. There is no attacker-reachable mechanism to inject `com.botfunnel.auth.rememberMe` into the attributes map from outside.
- No `@RequestHeader("X-Remember-Me")`, no `@RequestAttribute`, no `@CookieValue` reads `REMEMBER_ME_ATTR` anywhere in the code.

**Evidence — fallback is safe direction:**

When the attribute is absent (lazy session, WebSocket upgrade, anonymous request that lazily allocates a session, framework-internal session writes), the resolver emits a session-only cookie (no `Max-Age`) — see item 5. This is the **least privileged** outcome:
- Cannot promote an unauthenticated request to a 30-day persisted cookie.
- Cannot drop the session id (which would log out a legitimate user).
- Cannot throw (which would 500 random non-login flows).

**Evidence — logout / terminate-all unchanged:**

- `AuthService.logout` (`AuthService.java:251–256`): no change. Calls `exchange.getSession().flatMap(WebSession::invalidate)`. Framework invokes inherited `expireSession`, which writes `Max-Age=0` regardless of the attribute (item 8).
- `AuthService.terminateAllSessions` (`AuthService.java:390–394`): no change. Removes all `sessions` documents for the principal in MongoDB. The cookie on the client device retains its previous `Max-Age`, but the next request without a matching `sessions` document returns the user to anonymous → `/auth/login` redirect (per user-spec "Ограничения" §6).
- Decision 5 ("No sliding expiration, no pre-fix cookie invalidation") preserves the explicit deploy-day backward-compat guarantee.

**Evidence — no session-id leakage:** see item 3.

**Evidence — no auth bypass:** see item 5.

**Evidence — no fixation hardening regression:** the pre-existing decision **not** to call `WebSession.invalidate()` in `openSession` (because `ServerWebExchange.getSession()` is `Mono.cache`d for the request lifetime — see comment block at `AuthService.java:607–611`) is **unchanged** by this fix. Tech-spec lines 356–363 explicitly call this out as a `[TECHNICAL]` note, not a deviation; session-fixation hardening (ID rotation on login) is tracked separately. This audit notes it as **out of scope** and **not regressed**.

Verdict: **A07 clean.** The remember-me flag has a single server-controlled write site driven by an already-client-exposed login API, an externally-uninjectable read site, a safe fallback, and no impact on logout / terminate-all / session-fixation posture.

## Cross-cutting OWASP Coverage

- **A01 Broken Access Control:** No new authorization rules; `SecurityConfig` `authorizeExchange` block (lines 68–73) unchanged. **n/a — no change.**
- **A03 Injection:** No SQL / NoSQL / OS-command surface introduced. The cookie value (session id) is server-generated, never derived from user input. **n/a — no change.**
- **A04 Insecure Design:** Per-request channel via `ServerWebExchange.getAttributes()` is the standard Spring WebFlux mechanism for handler-to-framework-callback state (Decision 2). Public constant on the resolver class with package-distinct prefix `com.botfunnel.auth.rememberMe` follows least-collision convention. **pass.**
- **A05 Security Misconfiguration:** No new misconfig surface. Cookie defaults align with project convention (`http-only=true`, `same-site=lax`, `secure` env-driven). The `Secure=false` dev default in `application.properties:15` is a **pre-existing** project convention for plain-HTTP local dev — production deploys must override via `SESSION_COOKIE_SECURE=true`. The fix does not change this lever. **pass — no regression.**
- **A06 Vulnerable Components:** No new dependencies (`tech-spec.md:238–241`: "None. `spring-web` (transitive via `spring-boot-starter-webflux`) already provides `CookieWebSessionIdResolver`"). **n/a — no new packages.**
- **A08 Software & Data Integrity:** No deserialization, no plugin loading, no dynamic code path. The exchange attribute carries a `Boolean` (autoboxed, JVM-cached singletons), not a serialized object. **pass.**
- **A09 Security Logging & Monitoring:** Auth events (`login_success`, `login_failed`) continue to be written from `AuthService.authenticate` (`AuthService.java:586`). Logout is flagged via `WebSession.invalidate()` (no event log added — pre-existing project decision). The fix does not remove or weaken any log site. **pass — no regression.**
- **A10 SSRF:** No outbound HTTP from the audited code paths. **n/a.**

## Findings

### blocker
None.

### major
None.

### minor
None.

### info

**info-1: `Secure=false` dev default is a pre-existing operational convention, not a fix-side concern.**

`application.properties:15` declares `server.reactive.session.cookie.secure=${SESSION_COOKIE_SECURE:false}`. The `false` default is appropriate for plain-HTTP local dev (`docker compose up`) but **must** be overridden to `true` in any environment that terminates HTTPS. This is a deploy-time concern documented in `references/deployment.md` and a project-wide convention pre-dating this feature. The fix neither relaxes nor tightens this lever — it only ensures the value flows through to the cookie writer. Logged here for **audit completeness**; **not a finding** against Task 1.

Recommendation: none for Task 1. Long-term: the deployment runbook should continue to assert `SESSION_COOKIE_SECURE=true` on staging / production. Out of scope for this feature.

**info-2: Future Spring Boot upgrade may add new cookie attributes the manual mapping does not forward.**

`PropertyMapper` overlay in `RememberMeWebSessionIdResolver.java:76–82` enumerates Boot 3.5's `Cookie` POJO fields (`domain`, `path`, `httpOnly`, `secure`, `partitioned`, `sameSite`). If a future Boot version adds a new field (for example, a hypothetical `priority` attribute), the manual mapping silently misses it — the new field would be honored under Boot's auto-config but lost under our subclass.

Already documented as a `[TECHNICAL]` note in `tech-spec.md:364–368`. Tech-spec explicitly chooses no proactive guard ("YAGNI for an S bug"). Surfaces at the next dependency-bump code review, not in production. **Not a finding** against Task 1; logged to keep the future reviewer aware.

Recommendation: at the next `spring-boot-dependencies` bump that touches WebFlux session handling, re-diff `WebSessionIdResolverAutoConfiguration` and `org.springframework.boot.web.server.Cookie` against the `PropertyMapper` chain in this resolver. Out of scope for this feature.

## Coverage Notes

- **Attribute absent edge case:** covered by `setSessionId_attributeAbsent_writesSessionCookieWithoutMaxAge` test (resolver test line 73). Verdict: pass.
- **Attribute set to `Boolean.FALSE`:** covered by `setSessionId_rememberMeFalse_writesSessionCookieWithoutMaxAge` test (resolver test line 56). Behaviorally identical to the absent case, as required by Decision 2. Verdict: pass.
- **Concurrent logins for two different users on the same instance:** no shared mutable state between requests in the resolver (item 6). `ServerWebExchange` is per-request; `exchange.getAttributes()` is backed by `ConcurrentHashMap` in `DefaultServerWebExchange`. No cross-request bleed possible. Verdict: pass.
- **Logout flow:** `expireSession` inherited unchanged; cookie cleared with `Max-Age=0` regardless of attribute state (item 8). Verdict: pass.
- **Pre-fix cookie hitting post-fix backend:** inherited `resolveSessionIds` reads vanilla cookie; user remains authenticated until server-side TTL or relogin (item 11). Verdict: pass.
- **Future Spring Boot field addition:** documented (`info-2`).

## Summary by Severity

| Severity | Count |
|----------|-------|
| blocker  | 0 |
| major    | 0 |
| minor    | 0 |
| info     | 2 |

## Final Verdict

**`approve`.** No code changes required. No re-open of Task 1.

OWASP A02 sign-off: **clean.** OWASP A07 sign-off: **clean.** The remember-me cookie persistence fix is production-ready against the security checklist scoped by `tasks/3.md`.

Audit Wave proceeds to Task 4 (Test Audit) on the same code surface. No follow-up reviewers spawned (this audit IS the security review per tech-spec).
