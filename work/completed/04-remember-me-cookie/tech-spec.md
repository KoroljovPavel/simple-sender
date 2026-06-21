---
created: 2026-05-09
status: approved
branch: dev
size: S
---

# Tech Spec: 04-remember-me-cookie

## Solution

Replace Spring Boot's auto-configured `WebSessionIdResolver` with a custom
`RememberMeWebSessionIdResolver` that reads a per-request `rememberMe` flag
from `ServerWebExchange` attributes and writes the `SESSION` cookie with
`Max-Age = app.session.ttl-remember-me-days` (when `true`) or without
`Max-Age` (when `false` or absent). `AuthService.openSession` already
receives `rememberMe` and the `ServerWebExchange`; it is extended to also
publish the flag onto exchange attributes before the framework writes the
cookie. Cookie security flags (`name`, `httpOnly`, `secure`, `sameSite`,
`path`, `domain`) are read from
`serverProperties.getReactive().getSession().getCookie()` (returns
`org.springframework.boot.web.server.Cookie`) inside the resolver — single
source of truth, no constants duplicated in the fix. `expireSession`
(logout) and `resolveSessionIds` (read) are inherited from
`CookieWebSessionIdResolver` unchanged.

## Architecture

### What we're building/modifying

- **`RememberMeWebSessionIdResolver`** (new) —
  `backend/src/main/java/com/botfunnel/security/RememberMeWebSessionIdResolver.java`.
  Subclasses `CookieWebSessionIdResolver`. Overrides only
  `setSessionId(ServerWebExchange, String)`. Reads `Boolean rememberMe` from
  exchange attribute `REMEMBER_ME_ATTR`; builds the `ResponseCookie` from
  scratch using injected `ServerProperties` (reads cookie attributes via
  `serverProperties.getReactive().getSession().getCookie()`) and the
  per-request flag. Defines the public constant
  `REMEMBER_ME_ATTR = "com.botfunnel.auth.rememberMe"` shared with
  `AuthService`. Inherits `resolveSessionIds`, `expireSession` from the
  parent unchanged.
- **`SecurityConfig`** (modify) —
  `backend/src/main/java/com/botfunnel/security/SecurityConfig.java`.
  Register `@Bean WebSessionIdResolver` returning
  `RememberMeWebSessionIdResolver`. Boot's
  `WebSessionIdResolverAutoConfiguration` is `@ConditionalOnMissingBean`,
  so our bean replaces the default. Inject `ServerProperties` and pass it
  to the resolver constructor.
- **`AuthService.openSession`** (modify) —
  `backend/src/main/java/com/botfunnel/auth/AuthService.java:590-606`. After
  setting `WebSession.maxIdleTime`, write the `rememberMe` boolean to
  `exchange.getAttributes()` under
  `RememberMeWebSessionIdResolver.REMEMBER_ME_ATTR`. No change to the
  method signature — `ServerWebExchange` is already a parameter.
- **`application.properties`** (no change) — already declares
  `app.session.ttl-default-hours=24` and
  `app.session.ttl-remember-me-days=30`. Resolver reads the same keys via
  `@Value` to keep cookie `Max-Age` in lockstep with server-side TTL.

### How it works

1. Login flow: `AuthController` → `AuthService.login(req, exchange)` →
   `openSession(user, req.rememberMe(), exchange)`.
2. `openSession` reads the cached `WebSession` for the exchange (anonymous
   pre-auth → authenticated transition; the existing code intentionally
   does NOT call `invalidate()` because `ServerWebExchange.getSession()`
   is `Mono.cache`d for the request lifetime — see comment at
   `AuthService.java:598-602`), sets `maxIdleTime` to the TTL matching
   `rememberMe` (24 h or 30 d), saves `SecurityContext`, and **publishes
   `rememberMe`** to `exchange.getAttributes()`.
3. Spring's `WebSessionManager` flushes the session, calling
   `WebSessionIdResolver.setSessionId(exchange, sessionId)` on our
   `RememberMeWebSessionIdResolver`.
4. Resolver reads `Boolean` attribute. If `Boolean.TRUE` → builds cookie
   with `maxAge = Duration.ofDays(app.session.ttl-remember-me-days)`.
   Otherwise (attribute absent or `Boolean.FALSE`) → builds cookie without
   `maxAge` (session cookie). All other attributes (`name`, `path`,
   `domain`, `httpOnly`, `secure`, `sameSite`) are sourced from
   `serverProperties.getReactive().getSession().getCookie()`.
5. Logout flow: `AuthService.logout` → `WebSession.invalidate()` →
   framework calls inherited `expireSession(exchange)` which writes
   `Max-Age=0` cookie (independent of `rememberMe`).

### Shared resources

| Resource | Owner (creates) | Consumers | Instance count |
|----------|-----------------|-----------|----------------|
| `RememberMeWebSessionIdResolver` | `SecurityConfig.webSessionIdResolver()` | Spring `WebSessionManager` (framework-wide) | 1 (singleton) |

## Decisions

### Decision 1: Subclass `CookieWebSessionIdResolver`, override only `setSessionId`
**Decision:** Extend `CookieWebSessionIdResolver` and override
`setSessionId(ServerWebExchange, String)` only. In the override, build the
`ResponseCookie` from scratch using `serverProperties.getReactive().getSession().getCookie()`
and the per-request `rememberMe` attribute. Inherit `resolveSessionIds` and
`expireSession` unchanged.
**Rationale:** Per-request `Max-Age` cannot be set via the framework's
`addCookieInitializer` (the consumer only receives the builder, not the
exchange). Mutating bean state per request (e.g. temporarily flipping
`setCookieMaxAge` then calling `super.setSessionId`) is unsafe under
WebFlux concurrency. Building the cookie from scratch in the override is
the only race-free option. Inheriting `expireSession` keeps logout
behavior identical to today (AC-5).
Supports user-spec AC-1, AC-2, AC-5.
**Alternatives considered:**
- *Mutate bean-level `cookieMaxAge` per request, call `super.setSessionId`.*
  Rejected: race condition under concurrent requests (one request flips
  the field, another reads stale/wrong value).
- *Implement `WebSessionIdResolver` from scratch (no inheritance).*
  Rejected: forces us to re-implement `resolveSessionIds` and
  `expireSession`. Subclassing keeps logout cookie-clear logic delegated
  to the framework.

### Decision 2: Channel `rememberMe` via `ServerWebExchange` attribute
**Decision:** `AuthService.openSession` writes `Boolean rememberMe` to
`exchange.getAttributes()` under the constant
`RememberMeWebSessionIdResolver.REMEMBER_ME_ATTR =
"com.botfunnel.auth.rememberMe"`. Resolver reads the same attribute in
`setSessionId`. If absent or `Boolean.FALSE` — fall back to session-only
cookie.
**Rationale:** `ServerWebExchange.getAttributes()` is the standard Spring
WebFlux mechanism for per-request scoped state shared between handler and
framework callbacks. Constant placed in resolver class so the producer
(`AuthService`) imports the consumer's contract — no third file. Public
visibility allows future extension flows to opt in (e.g. an admin
"keep me signed in for 90 days" endpoint).
Supports user-spec AC-1, AC-2 (per-request branching), Risk 3 mitigation.
**Alternatives considered:**
- *Reactor Context propagation.* Rejected: heavier API, requires
  `ContextWebFilter` plumbing; attributes are sufficient and idiomatic.
- *ThreadLocal.* Rejected: WebFlux is non-blocking; ThreadLocal does not
  follow reactive thread switches.

### Decision 3: Single source of truth for cookie security flags
**Decision:** Read `name`, `httpOnly`, `secure`, `sameSite`, `path`,
`domain` from
`serverProperties.getReactive().getSession().getCookie()` (which returns
`org.springframework.boot.web.server.Cookie`) inside the resolver. The
fix code never hard-codes these values.
**Rationale:** `application.properties` and `.env`-driven overrides
(`SESSION_COOKIE_SECURE`, `SESSION_COOKIE_SAME_SITE`,
`server.reactive.session.cookie.name`, etc.) remain the single source of
truth. Changing `Secure`/`SameSite`/`name` via env vars in production
must continue to flow through to the actual `Set-Cookie` header without
code changes (Risk 1).
Supports user-spec AC-1 (other flags from config), AC-8 (cookie name
overridable), Risk 1 mitigation.
**Alternatives considered:**
- *Hard-code `httpOnly`, `secure`, `sameSite` in the resolver matching
  current dev defaults.* Rejected: drift between env config and resolver
  output. AC-1 explicitly forbids this.

### Decision 4: Reuse existing TTL config keys for cookie `Max-Age`
**Decision:** Cookie `Max-Age` is `app.session.ttl-remember-me-days * 86400`
(when `rememberMe=true`). The resolver reads the same `app.session.*` keys
that `AuthService` already reads for `WebSession.maxIdleTime`.
**Rationale:** AC-3 requires server-side TTL and cookie `Max-Age` to stay
synchronized. Two values from one config key → no drift possible. Adding
a separate `app.session.cookie-max-age-*` knob would require ops to
maintain two values in lockstep; this is the bug we are fixing,
re-introduced at config level.
Supports user-spec AC-3.
**Alternatives considered:**
- *Add separate `app.session.cookie-max-age-days`.* Rejected: introduces
  drift surface area for no operational benefit.

### Decision 5: No sliding expiration, no pre-fix cookie invalidation
**Decision:** `Max-Age` is set once at login from `rememberMe`; subsequent
authenticated requests do not extend it. Cookies issued before the fix
(session cookies, no `Max-Age`) continue to authorize the user until the
browser is closed (cookie deletion) or until the server-side session TTL
elapses in MongoDB — whichever comes first. Relogin overwrites with the
new format. The deploy itself does not invalidate any session.
**Rationale:** Explicitly required by user-spec ("Ограничения" §1, §2).
Pre-fix cookies map to valid `sessions` documents in MongoDB; the new
resolver only affects how *new* cookies are written. Backward compat is
free here — we are not changing the cookie-read path.
Supports user-spec AC-4, "Ограничения" §1–§2.

### Decision 6: Register auto-login keeps `rememberMe=false` and gets a session-only cookie
**Decision:** No change to the register flow. `AuthService.register(...)`
already invokes `openSession(saved, false, exchange)` at
`AuthService.java:167`. After this fix, that path produces a session-only
cookie (no `Max-Age`) — same observable behavior as today. An explicit
regression case in `AuthServiceTest` asserts the exchange attribute is
set to `Boolean.FALSE` on the register-auto-login path so a future change
to the flag value is caught by tests.
**Rationale:** AC-6 mandates "Auto-login після register залишається без
галочки — session-only cookie. Поведінка не регресує." The simplest way
to honor this is to not touch the register-flow argument and to lock the
expected boolean into the test layer.
Supports user-spec AC-6.
**Alternatives considered:**
- *Make `rememberMe` nullable in `openSession` and treat `null` as "no
  attribute write".* Rejected: changes a method signature for no benefit;
  the resolver already treats `Boolean.FALSE` and "absent" identically.
- *Remove the attribute write on the register path.* Rejected: makes the
  data flow asymmetric (login writes, register doesn't), which is harder
  to reason about than "always writes the boolean".

### Decision 7: Verify cookie behavior via resolver unit test, skip new IT
**Decision:** Cookie `Max-Age` assertions live in a unit test for
`RememberMeWebSessionIdResolver` using a mocked `ServerWebExchange` with
a `MockServerHttpResponse`. Existing `AuthServiceTest` is extended to
assert the exchange attribute is set in both branches. No new
`@SpringBootTest` integration test that asserts on `Set-Cookie` is added.
**Rationale:** The shared test fixture `AbstractIntegrationTest` uses
`WebTestClient.bindToApplicationContext` (required for the
`SecurityMockServerConfigurers.csrf()` mutator); this binding does not
propagate the `Set-Cookie` header in responses (already documented at
`AuthControllerIT.java:326-329`). Switching to `bindToServer` to recover
headers means losing the csrf mutator and rebuilding test bootstrap for
this single fix — disproportionate for an S-sized bug. The resolver's
logic is pure: build cookie from `(rememberMe, ServerProperties)`. A
unit test on the resolver covers all three branches (`true`, `false`,
absent) deterministically. AuthService→exchange attribute coverage
closes the wiring loop.
Supports user-spec "Тестирование" — explicit deferral of integration-
level cookie assertions called out in user-spec Risk 2.
**Alternatives considered:**
- *New IT with `WebTestClient.bindToServer().baseUrl(randomPort)`.*
  Rejected: requires re-implementing csrf bootstrap (fetch XSRF cookie
  via real GET, echo header on POST) only for this fix. Cost > benefit
  for an S bug fix.
- *Manual smoke covers it.* Already in user-spec "Как проверить"; it
  complements automated coverage but does not replace deterministic
  regression tests.

## Data Models

No schema changes. `sessions` collection is managed by
`spring-session-data-mongodb` and is unchanged. Cookie attributes are
not persisted server-side.

## Dependencies

### New packages
None. `spring-web` (transitive via `spring-boot-starter-webflux`) already
provides `CookieWebSessionIdResolver`; `spring-boot-autoconfigure` already
provides `ServerProperties`.

### Using existing (from project)
- `org.springframework.boot.autoconfigure.web.ServerProperties` — read
  cookie attributes (`reactive.session.cookie.*`) inside the resolver.
- `org.springframework.web.server.session.CookieWebSessionIdResolver` —
  parent class for `RememberMeWebSessionIdResolver`.
- `org.springframework.http.ResponseCookie` — build `Set-Cookie` from
  scratch in `setSessionId`.
- `com.botfunnel.auth.AuthService.openSession` — already accepts
  `rememberMe` and `ServerWebExchange`; now also writes the attribute.
- `app.session.ttl-default-hours`, `app.session.ttl-remember-me-days`
  (already in `application.properties`) — single source of truth for both
  server TTL and cookie `Max-Age`.

## Testing Strategy

**Feature size:** S

### Unit tests
- `RememberMeWebSessionIdResolverTest` (new):
  - Scenario A — `setSessionId` with attribute = `Boolean.TRUE` →
    response cookie has `Max-Age = Duration.ofDays(30)`. (AC-1)
  - Scenario B — `setSessionId` with attribute = `Boolean.FALSE` →
    response cookie has `Max-Age = null` (session cookie). (AC-2)
  - Scenario C — `setSessionId` with attribute absent → response cookie
    has `Max-Age = null` (default-safe fallback). Covers lazy-session and
    non-login flows.
  - Scenario D — re-login flip: call `setSessionId` once with
    `Boolean.TRUE`, then with `Boolean.FALSE` on a fresh exchange; assert
    each call independently produces the matching cookie. Proves the
    resolver carries no per-request state across calls. (AC-7)
  - Scenario E — TTL reads config, not constant: parameterized over
    `app.session.ttl-remember-me-days = {30, 7, 60}`; assert
    `Max-Age` matches `days * 86400` for each value. (AC-3)
  - Scenario F — cookie security flags pass through: parameterized table
    that toggles each property independently
    (`name=ALT`, `http-only=false`, `secure=true`, `same-site=Strict`,
    `path=/app`, `domain=example.test`) and asserts only that one
    attribute changes in the resulting `ResponseCookie`. Avoids vacuous
    "all defaults" pass. (AC-1, AC-8)
  - Scenario G — `resolveSessionIds` reads cookie value from request
    (inherited behavior smoke; ensures pre-fix cookies without `Max-Age`
    still resolve). (AC-4)
  - Scenario H — `expireSession` writes `Max-Age=0` regardless of
    attribute state. (AC-5)
- `AuthServiceTest` (extend existing tests at lines 306, 343, plus
  register flow):
  - `login_success_invalidatesPreAuthSession_andSetsRememberMeTtl` —
    additionally `verify(exchange.getAttributes()).put(REMEMBER_ME_ATTR,
    Boolean.TRUE)`.
  - `login_success_noRememberMe_setsTtl24Hours` — additionally verify
    attribute set with `Boolean.FALSE`.
  - **New**: register-auto-login regression case — call `register(...)`,
    assert `exchange.getAttributes()` receives `(REMEMBER_ME_ATTR,
    Boolean.FALSE)`. Locks AC-6 against silent regression to `true`.

### Integration tests
None new. Rationale captured in Decision 7. Existing
`AuthControllerIT` and `ProfileControllerIT` keep passing because they
verify session lifecycle via Mongo `sessions` queries, not via cookies.

### E2E tests
None. Cookie behavior is exercised by manual smoke (user-spec
"Пользователь проверяет"); Playwright is reserved for golden-path locale
flows per project convention.

## Agent Verification Plan

**Source:** user-spec "Как проверить" section.

### Verification approach
Resolver unit test gives deterministic regression coverage. Per-task
`Verify-smoke` covers live cookie inspection via `curl` against a local
backend run (`./gradlew bootRun` on `:8080` after `docker compose up -d`
in `infra/`). User performs the in-browser smoke in the Final Wave's
QA task to confirm `Expires` rendering in DevTools and persistence
across browser-close cycles.

### Tools required
- `curl` — assert `Set-Cookie: Max-Age=...` headers from real backend.
- `bash` — drive the local stack (`docker compose`, `./gradlew`).
- No MCP tooling. No Playwright (manual browser smoke is faster for a
  one-cookie attribute check than authoring a Playwright spec).

## Risks

| Risk | Mitigation |
|------|------------|
| Replacing the auto-configured resolver loses Boot's cookie attribute mapping (httpOnly, secure, sameSite from env vars) | Inject `ServerProperties` and copy `reactive.session.cookie.*` (path, domain, httpOnly, secure, sameSite, partitioned) into the cookie builder ourselves. Unit test parameterizes over property changes. |
| Per-request mutation of bean state under WebFlux concurrency | Build cookie from scratch in the override; never mutate bean fields in `setSessionId`. Decision 1. |
| Test infrastructure (`WebTestClient.bindToApplicationContext`) drops `Set-Cookie` headers, blocking integration-level assertions | Cover the wiring with (a) a resolver unit test on a `MockServerHttpResponse` and (b) an exchange-attribute assertion on `AuthServiceTest`. Decision 7. |
| Future Spring Boot upgrade adds new fields to the `Cookie` config (e.g. `partitioned`) and our manual mapping silently misses them | Mirror Boot's `WebSessionIdResolverAutoConfiguration` mapping (path, domain, httpOnly, secure, sameSite, partitioned). If a Boot upgrade adds a new flag, the bug surfaces as a missing attribute on the next dependency bump — caught at next code-review of the upgrade. No proactive guard added (out of scope for this S bug). |

## User-Spec Deviations

The user-spec leaves "конкретні класи, методи override та обраний спосіб
тестування — визначаються в техспеку" — those are filled here in
Decisions 1, 2, 6, 7 without changing user-spec scope. All AC-1..AC-8 are
addressed by the Solution and Decisions above. The technical decisions
in user-spec "Технические решения" §1–§7 are adopted verbatim:

- Sliding expiration deferred (Decision 5)
- Pre-fix cookies remain valid (Decision 5)
- Logout/terminate-all unchanged (Solution: `expireSession` inherited)
- Missing-attribute → session-only cookie (Decision 2 fallback)
- Cookie security flags read from config (Decision 3)
- No new logs from the resolver (resolver is a thin transform; no logging
  added)

### Documented `[TECHNICAL]` notes

These are not deviations from user-spec scope — they document
spec-internal corrections found during review:

- **`AuthService.openSession` does NOT invalidate the pre-auth session.**
  Earlier draft text claimed it does. Code at `AuthService.java:598-602`
  intentionally does not call `WebSession.invalidate()` because
  `ServerWebExchange.getSession()` is `Mono.cache`d for the request,
  making post-invalidate session writes go to a zombie. This pre-existing
  behavior is unchanged by this fix. Any session-fixation hardening (ID
  rotation on login) is **out of scope** for this bug fix and tracked
  separately. `[TECHNICAL]`
- **No proactive Spring Boot upgrade guard.** Boot's
  `WebSessionIdResolverAutoConfiguration` could grow new cookie attributes
  in a future release; we mirror its current mapping and rely on the
  next dependency-bump code review to catch additions. Adding an
  enumeration regression test is YAGNI for an S bug. `[TECHNICAL]`

## Acceptance Criteria

Технические критерии приёмки (дополняют пользовательские из user-spec):

- [x] Backend builds and starts: `./gradlew :backend:build` passes; Boot
      logs `Started ... in N s`.
- [x] Existing `AuthServiceTest` and `AuthControllerIT` keep passing
      without modification beyond the cases listed in "Testing Strategy"
      (two extended login tests and one new register-auto-login case).
- [x] `RememberMeWebSessionIdResolver` is wired as the active
      `WebSessionIdResolver` (verifiable: `ApplicationContext` returns
      our class, not Boot's default).
- [x] No regressions in `ProfileControllerIT` (terminate-all,
      change-password, deleteAccount flows).
- [x] No new ERROR-level logs at startup. No new dependencies in
      `backend/build.gradle`.
- [x] `curl -i POST /api/auth/login` smoke (Verify-smoke in Task 1)
      returns `Set-Cookie` matching user-spec AC-1 / AC-2 in both
      branches against a locally-running backend.

## Implementation Tasks

### Wave 1

#### Task 1: Implement remember-me cookie persistence
- **Description:** Fix the bug where the `SESSION` cookie lacks `Max-Age`
  even when `rememberMe=true` extends the server-side session TTL to 30
  days. Add a custom `WebSessionIdResolver` that branches per-request on
  an exchange attribute, wire `AuthService.openSession` to publish that
  attribute, and register the bean to replace Boot's default. Result:
  `POST /api/auth/login` with `rememberMe=true` returns `Set-Cookie` with
  `Max-Age=2592000`; with `rememberMe=false` returns a session cookie.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:**
  ```bash
  # Backend up locally on :8080. Replace email/password with a real test user.
  curl -i -s -X POST http://localhost:8080/api/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"email":"test@example.com","password":"...","rememberMe":true}' \
    | grep -i '^set-cookie:'
  # Expected: Set-Cookie: SESSION=...; Path=/; Max-Age=2592000; Expires=...; HttpOnly; SameSite=Lax
  curl -i -s -X POST http://localhost:8080/api/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"email":"test@example.com","password":"...","rememberMe":false}' \
    | grep -i '^set-cookie:'
  # Expected: Set-Cookie: SESSION=...; Path=/; HttpOnly; SameSite=Lax  (no Max-Age, no Expires)
  ```
- **Files to modify:**
  - `backend/src/main/java/com/botfunnel/security/SecurityConfig.java`
  - `backend/src/main/java/com/botfunnel/auth/AuthService.java`
  - `backend/src/test/java/com/botfunnel/auth/AuthServiceTest.java`
- **Files to read:**
  - `backend/src/main/resources/application.properties`
  - `backend/src/main/java/com/botfunnel/auth/AuthController.java`
  - `backend/src/main/java/com/botfunnel/auth/dto/LoginRequest.java`
  - `backend/src/test/java/com/botfunnel/AbstractIntegrationTest.java`
  - `backend/src/test/java/com/botfunnel/auth/AuthControllerIT.java`
- **Files to create:**
  - `backend/src/main/java/com/botfunnel/security/RememberMeWebSessionIdResolver.java`
  - `backend/src/test/java/com/botfunnel/security/RememberMeWebSessionIdResolverTest.java`

### Audit Wave

#### Task 2: Code Audit
- **Description:** Full-feature code quality audit. Read all source files
  created/modified in this feature (`RememberMeWebSessionIdResolver`,
  `SecurityConfig`, `AuthService`). Review holistically for cross-component
  issues: race-free per-request handling, no duplicated cookie-attribute
  constants, correct Spring WebFlux patterns, naming consistency with
  existing `auth/`/`security/` modules. Write audit report.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 3: Security Audit
- **Description:** Full-feature security audit. Read all source files
  created/modified in this feature. Confirm: no cookie security flags
  hard-coded (must flow from `ServerProperties`), no session-id leakage
  in logs, no regression in CSRF/XSRF flow, no auth bypass via missing
  attribute (default must be session-cookie, not unauthenticated).
  OWASP A02 (Cryptographic Failures) and A07 (Identification & Auth
  Failures) explicitly considered. Write audit report.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 4: Test Audit
- **Description:** Full-feature test quality audit. Read
  `RememberMeWebSessionIdResolverTest` and the extended cases in
  `AuthServiceTest`. Verify: all three resolver branches covered
  (true/false/absent), parameterized cookie-flag pass-through actually
  changes asserted output (not vacuously passing), `register`
  auto-login regression case covers `rememberMe=false`. Confirm no test
  duplicates existing IT coverage. Write audit report.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 5: Pre-deploy QA
- **Description:** Acceptance testing. Run `./gradlew :backend:test`
  (full backend suite) — all green. Verify each user-spec acceptance
  criterion AC-1..AC-8 against the local stack:
  - AC-1, AC-2, AC-7: `curl` smoke against `:8080` with both `rememberMe`
    branches; switch and re-login to assert cookie is rewritten.
  - AC-3: confirm `WebSession.maxIdleTime` matches `Max-Age` in both
    branches (existing Mongo `sessions` IT plus new resolver test).
  - AC-4: seed a pre-fix-style session document and request `/api/me`
    with a `Cookie: SESSION=...` header lacking `Max-Age` — must return
    `200 OK`.
  - AC-5: logout returns `Max-Age=0` cookie (existing behavior; smoke
    via `curl`).
  - AC-6: register flow returns session cookie (no `Max-Age`).
  - AC-8: change `server.reactive.session.cookie.name=ALT` via env var,
    restart, re-run AC-1 smoke; cookie name flips, fix code unchanged.
  Manual browser smoke per user-spec "Пользователь проверяет" before
  marking the task done.
- **Skill:** pre-deploy-qa
- **Reviewers:** none
