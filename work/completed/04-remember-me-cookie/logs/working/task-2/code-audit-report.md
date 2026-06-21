# Code Audit — 04-remember-me-cookie

**Audit Wave** holistic review of Wave 1 deliverables. Methodology: `code-reviewing` (11 dimensions), with prioritization on dimensions 1 (Architecture), 4 (Error Handling), 8 (Security), 10 (Cross-File Consistency), 11 (Resource Management) given the auth/session context.

**Scope:**
- `backend/src/main/java/com/botfunnel/security/RememberMeWebSessionIdResolver.java` (new, 97 LOC)
- `backend/src/main/java/com/botfunnel/security/SecurityConfig.java` (modified, +8 LOC bean + @Bean)
- `backend/src/main/java/com/botfunnel/auth/AuthService.java` (modified — `openSession` body, import added; `register` path unchanged)

**Reference baselines:** tech-spec Decisions 1, 2, 3, 4, 6; user-spec AC-1..AC-8; project conventions (`application.properties`, `AppUserDetails`, neighboring `auth/`/`security/` classes).

## Summary

PASS. The fix is correctly scoped, holistically coherent, and faithful to every approved decision. No blocker, no major findings. Three `nit`-level observations are recorded for transparency; none require code change. Verdict: **PASS** — recommend Audit Wave continues to Task 3 (Security Audit) without re-opening Task 1.

## Decision Compliance

### Decision 1 — Race-free override of `setSessionId`: **PASS**

Evidence:
- `RememberMeWebSessionIdResolver` declares only two fields, both `private final` (`serverProperties`, `rememberMeDays`) — set once in the constructor, never mutated. Resolver.java:43-44.
- `setSessionId(ServerWebExchange, String)` builds `ResponseCookie` from scratch via `ResponseCookie.from(name, id)` and a fresh builder. No call into `super.setSessionId`, no `setCookieMaxAge(...)` / `setCookieName(...)` / any setter on `this`. Resolver.java:58-96.
- The only per-request data sources are method arguments (`exchange`, `id`) and the immutable `serverProperties` snapshot. There is no shared mutable state to race over, hence the absence of `synchronized` / `volatile` / `AtomicReference` is correct (not a smell).
- `getCookieName()` reads the parent's `cookieName` field, set once at construction (Resolver.java:49-54). No runtime mutation path exists in the subclass.

**Verification:** `grep -nE "synchronized|volatile|AtomicReference|set[A-Z]" RememberMeWebSessionIdResolver.java` returns only the constructor-time `setCookieName(...)` call inside the constructor body — i.e., before the bean is published as a singleton. Correct.

### Decision 2 — `ServerWebExchange` attribute as the per-request channel: **PASS**

Evidence:
- Single owner of the constant: `RememberMeWebSessionIdResolver.REMEMBER_ME_ATTR = "com.botfunnel.auth.rememberMe"` declared `public static final String` at Resolver.java:41. No third file. No duplication.
- AuthService imports the constant from the resolver (producer imports consumer's contract): `import com.botfunnel.security.RememberMeWebSessionIdResolver;` at AuthService.java:12; usage at AuthService.java:604-605.
- Resolver reads with `exchange.getAttribute(REMEMBER_ME_ATTR)` (typed read returning `Boolean`). Resolver.java:87. `Boolean.TRUE.equals(rememberMe)` is null-safe and `false`-safe — covers all three branches (`TRUE`, `FALSE`, absent) in a single expression.
- Fallback semantics (absent or `Boolean.FALSE` → no `Max-Age`) are an explicit code path, not an exception/throw. Risk-3 mitigation (lazy session, WebSocket upgrade, anonymous flow) holds.

**Attribute-write ordering — the critical race-with-self check:**

`AuthService.openSession` at lines 591-615:

```
exchange.getAttributes().put(REMEMBER_ME_ATTR, Boolean.valueOf(rememberMe));   // line 604-605, eager

return exchange.getSession()                                                    // line 612
        .doOnNext(session -> session.setMaxIdleTime(ttl))
        .then(securityContextRepository.save(exchange, context));
```

The `put` is a top-of-method, synchronous, eager statement — it executes the moment `openSession(...)` is invoked, **before** any reactive subscription. It is NOT inside `flatMap` / `doOnNext` / `doOnSuccess`. By the time Spring's `WebSessionManager` flushes the session and calls `WebSessionIdResolver.setSessionId`, the attribute has been in `exchange.getAttributes()` since the synchronous call site of `openSession` from `authenticate(...)` / `register(...)`. No race-with-self.

`exchange.getAttributes()` is backed by a `ConcurrentHashMap` in `DefaultServerWebExchange`, so even concurrent puts are safe (none are expected — the exchange is request-scoped).

**Decisions.md Task 1 deviation note** explicitly flagged this design choice: the original task hint suggested an "окремий .doOnNext" but Wave 1 author moved the put pre-chain because the attribute is request-scoped, not session-scoped. This audit confirms the deviation is **strictly better** for correctness — it removes any timing dependency on the reactive chain completing before WebSessionManager flushes the session.

### Decision 3 — Single source of truth for cookie security flags: **PASS** (with nit, see Findings)

Cookie attributes inventory in `RememberMeWebSessionIdResolver.setSessionId`:

| Attribute | Source | Hard-coded? |
|-----------|--------|-------------|
| `name` | `getCookieName()` (parent field, seeded from `serverProperties.getReactive().getSession().getCookie().getName()` at construction). Resolver.java:51. | No |
| `path` (overlay) | `cookieProps.getPath()` via `PropertyMapper.alwaysApplyingWhenNonNull`. Resolver.java:78. | No |
| `path` (seed) | `exchange.getRequest().getPath().contextPath().value() + "/"` — parent's `initCookie` default. Resolver.java:71. | Parity-fallback (parent default), not a duplication of `ServerProperties` |
| `httpOnly` (overlay) | `cookieProps.getHttpOnly()`. Resolver.java:79. | No |
| `httpOnly` (seed) | `true` — parent default. Resolver.java:72. | Parity-fallback |
| `secure` (overlay) | `cookieProps.getSecure()`. Resolver.java:80. | No |
| `secure` (seed) | `"https".equalsIgnoreCase(scheme)` — parent default. Resolver.java:73. | Parity-fallback |
| `sameSite` (overlay) | `cookieProps.getSameSite()` mapped via `SameSite::attributeValue`. Resolver.java:82. | No |
| `sameSite` (seed) | `"Lax"` — parent default. Resolver.java:74. | Parity-fallback |
| `domain` | `cookieProps.getDomain()` (no seed). Resolver.java:77. | No |
| `partitioned` | `cookieProps.getPartitioned()` (no seed). Resolver.java:81. | No |
| `maxAge` | `Duration.ofDays(rememberMeDays)` if `rememberMe=TRUE`; else absent. Resolver.java:87-93. | Per-request |

The seed values on lines 70-74 are not duplications of `ServerProperties` config; they replicate the **parent class's** `initCookie` defaults verbatim, used only as fallback when a property is removed entirely from `application.properties` (in which case Boot's `Cookie` POJO defaults to `null` for that field, the `PropertyMapper` skips it, and the seed survives). Under the project's current `application.properties` (lines 14-16: `http-only=true`, `secure=…false}`, `same-site=…lax}`), these seeds are dead code — overwritten on every request. The class-level Javadoc on Resolver.java:62-69 documents this explicitly.

`grep -nE "\"SESSION\"|SameSite\.LAX|httpOnly\(true\)|secure\(true\)" RememberMeWebSessionIdResolver.java` finds:
- Line 72-73: the parity-fallback seeds (already discussed).
- No `"SESSION"` literal. No `SameSite.LAX` literal. No literal `cookie name`.

**Verdict:** No duplication of `ServerProperties` keys in the fix code. Decision 3 holds.

### Decision 4 — Reuse existing TTL config keys: **PASS**

Evidence:
- SecurityConfig.java:45 reads `@Value("${app.session.ttl-remember-me-days:30}") long rememberMeDays`.
- AuthService.java:99 reads the same key in its constructor: `@Value("${app.session.ttl-remember-me-days:30}") long rememberMeDays`.
- AuthService uses it for `WebSession.maxIdleTime` (line 592). SecurityConfig passes it to the resolver, which uses it for cookie `Max-Age` (Resolver.java:89: `Duration.ofDays(rememberMeDays)`).
- Single property, two consumers, identical default — TTL on disk and Max-Age on the wire cannot drift.
- `application.properties:18` declares `app.session.ttl-remember-me-days=${SESSION_TTL_REMEMBER_ME_DAYS:30}` — env-overridable. No new `app.session.cookie-max-age-*` key introduced.

### Decision 6 — Register flow unchanged, `rememberMe=false` preserved: **PASS**

Evidence:
- `AuthService.register` at line 168: `return openSession(saved, false, exchange).thenReturn(new RegisterResponse(saved.getId()));` — the literal `false` is unchanged from pre-fix.
- `AuthServiceTest` (line 526) asserts `exchange.getAttributes()` receives `(REMEMBER_ME_ATTR, Boolean.FALSE)` on the register-auto-login path. AC-6 regression-locked at the test layer.
- Resulting cookie: per Decision 2 fallback, `Boolean.FALSE` produces session-only cookie (no `Max-Age`). Same observable behavior as pre-fix.

## Findings

### blocker
None.

### major
None.

### minor
None.

### nit

**nit-1: Parity-fallback seeds duplicate parent's `initCookie` defaults (Resolver.java:70-74)**

The seeds `path(... contextPath ... + "/")`, `httpOnly(true)`, `secure(scheme=="https")`, `sameSite("Lax")` mirror what `CookieWebSessionIdResolver.initCookie` writes by default. Strictly speaking, every one of these is overwritten by the `PropertyMapper` overlay since `application.properties:14-16` always sets the corresponding keys. Under a deploy that intentionally strips a key (e.g. `server.reactive.session.cookie.same-site=` empty), the seed survives — matching Boot's auto-config behavior, which is the design intent.

This is **not actionable** — removing the seeds would diverge from parent behavior on key-removal edge cases, and Decision 3 forbids importing those defaults from Boot anyway. The class-level Javadoc on Resolver.java:62-69 already explains this. Logged as a `nit` only for transparency: a future reader who is not aware of `CookieWebSessionIdResolver.initCookie` might wonder if the seeds violate Decision 3. The Javadoc handles that question.

**Recommendation:** None. Leave as-is.

**nit-2: TTL default literal `30` repeated across two `@Value` injections**

Both `SecurityConfig.webSessionIdResolver(... @Value("${app.session.ttl-remember-me-days:30}") long rememberMeDays)` (SecurityConfig.java:45) and `AuthService(... @Value("${app.session.ttl-remember-me-days:30}") long rememberMeDays)` (AuthService.java:99) hard-code the same `:30` SpEL default. If the property is removed from `application.properties`, both default to 30 — fine, no drift. But the literal is duplicated.

**Why not actionable:** the only alternative would be a third constants holder, which adds a file for negligible benefit. `application.properties:18` is the canonical source; the SpEL default is a safety net for misconfigured deploys, not the primary value.

**Recommendation:** None. Acceptable convention.

**nit-3: `Boolean.valueOf(rememberMe)` vs `Boolean.TRUE/FALSE`**

`AuthService.java:605` uses `Boolean.valueOf(rememberMe)` to autobox the `boolean` argument. Equivalent to `rememberMe ? Boolean.TRUE : Boolean.FALSE`. The current form is idiomatic Java; tests assert against `Boolean.TRUE` / `Boolean.FALSE`, and `equals` on autoboxed Booleans is correct (cached singletons).

**Recommendation:** None.

## Cross-Cutting Observations

### Architecture / Separation of Concerns (dim 1, 2)

- Single new class with a single responsibility: write the SESSION cookie with per-request `Max-Age`. No mixed concerns.
- AuthService's modification is one-line at the call site (`exchange.getAttributes().put(...)`) plus an import. The auth flow keeps its existing shape; the resolver carries the cookie-attribute-mapping concern that previously lived inside Boot's auto-config.
- Boundaries respected: `AuthService` (auth flow) talks to `RememberMeWebSessionIdResolver` (cookie writer) only via the public `REMEMBER_ME_ATTR` constant and the request-scoped attribute map. No direct method calls. No back-channel.

### Cross-File Consistency (dim 10)

- `RememberMeWebSessionIdResolver.REMEMBER_ME_ATTR` is referenced from exactly the expected sites:
  - `AuthService.java:605` (writer)
  - Resolver.java:87 (reader)
  - `RememberMeWebSessionIdResolverTest.java` (5 assertions)
  - `AuthServiceTest.java:341, 380, 526` (3 assertions covering true/false/register branches)
- `import com.botfunnel.security.RememberMeWebSessionIdResolver;` resolves correctly (auth/security package boundary observed; auth depends on security, never the inverse, matching project convention).

### Naming / Project Consistency (dim 3)

- Package placement: `com.botfunnel.security` — same package as `SecurityConfig`. Correct: the resolver is a security-layer component, not an auth-flow component.
- Class name: `RememberMeWebSessionIdResolver` — describes the role, follows the `<Discriminator><FrameworkType>` pattern for Spring extensions.
- Member visibility: `private final` for state, `public static final` for the cross-package contract constant, `public` for the override (forced by `@Override` on a public parent method). All consistent with `AppUserDetails`, `SecurityConfig`, `AuthService`.
- Imports ordering matches `SecurityConfig.java` style (Spring imports first, `java.*` last).

### Wiring (dim 1)

- `SecurityConfig.webSessionIdResolver(...)` at SecurityConfig.java:42-49 declares an unconditional `@Bean WebSessionIdResolver`.
- Spring Boot's `WebSessionIdResolverAutoConfiguration` is `@ConditionalOnMissingBean` (verified in `code-research.md` against `spring-boot-autoconfigure 3.5.6`). Our bean wins; Boot's default is suppressed.
- `ServerProperties` is injected via method parameter (Boot autoconfigures it). No `@Autowired` on field — matches the project's constructor/method-injection convention.
- The TTL value comes from a separate `@Value` parameter on the same `@Bean` method — the resolver itself has no Spring config dependencies, which makes it trivially unit-testable (confirmed by `RememberMeWebSessionIdResolverTest`'s plain `new RememberMeWebSessionIdResolver(props, days)` instantiation).

### Resource Management (dim 11)

- The resolver is a singleton (one `@Bean` definition, framework-default scope). No per-request allocation of heavy state.
- `setSessionId` allocates one `ResponseCookieBuilder` and one `ResponseCookie` per call — necessary, matches parent.
- `serverProperties.getReactive().getSession().getCookie()` is called at most once per request — held in a local variable.
- No connection pools, no clients, no file handles touched. Nothing to leak.

### Security (dim 8)

This audit defers the OWASP A02/A07-grade analysis to Task 3 (Security Audit). At the code-quality level, this audit confirms:
- No hard-coded cookie security flags; `httpOnly`, `secure`, `sameSite` flow from `application.properties` → `ServerProperties` (Decision 3 holds — Risk 1 mitigated).
- Resolver does not log session IDs, cookie values, or the `rememberMe` boolean — no observability-shaped data leak.
- `Assert.notNull(id, "'id' is required")` on Resolver.java:59 mirrors parent input validation. No null-pointer reachability into the response writer.

### Performance (dim 9)

- Per-request work: one map lookup (`exchange.getAttribute`), one `PropertyMapper` walk over six fields, one `ResponseCookie.build()`. O(1) and trivial.
- No blocking calls in the override.
- Singleton resolver — no allocation cost on bean instantiation per request.

### Testing Posture (dim 6)

This audit defers the test-quality grade to Task 4 (Test Audit). For wiring confidence, this audit notes:
- `RememberMeWebSessionIdResolverTest` exists at `backend/src/test/java/com/botfunnel/security/RememberMeWebSessionIdResolverTest.java` with assertions on both `Max-Age` branches and parameterized cookie-flag pass-through.
- `AuthServiceTest` extends two existing login tests and adds the register-auto-login regression case (3 attribute assertions total).
- `decisions.md` records `BUILD SUCCESSFUL, 174 tests pass` after Wave 1 fixes.

## Recommendations

None. The implementation is production-ready as audited. No fix-loop required for Task 1. No changes recommended to tech-spec.

Audit Wave proceeds to Task 3 (Security Audit) and Task 4 (Test Audit) on the same code surface.
