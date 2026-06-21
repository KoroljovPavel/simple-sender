# Code Research: 04-remember-me-cookie

## Overview

The bug: `LoginRequest.rememberMe=true` correctly extends `WebSession.maxIdleTime` to 30 days
(persisted into MongoDB `sessions` collection by spring-session-data-mongodb), but the SESSION
cookie is written without `Max-Age`/`Expires`, so the browser deletes it on close — the next
visit lands on /auth/login despite a still-valid server-side session.

Root cause: Spring Boot's `WebSessionIdResolverAutoConfiguration` registers a single
`CookieWebSessionIdResolver` configured from static `server.reactive.session.cookie.*`
properties. `Max-Age` is bound once at bean creation; there is no per-request branching.

Fix path (already approved): replace the auto-configured resolver with a custom
`WebSessionIdResolver` bean. `AuthService.openSession` writes a flag into
`ServerWebExchange.getAttributes()`. The custom resolver reads that flag in `setSessionId` and
applies `Max-Age=30d` (or no-max-age) per response.

## Files Touched

### Existing (read / modify)

- `backend/src/main/java/com/botfunnel/auth/AuthService.java:590-606` — `openSession(User, boolean rememberMe, ServerWebExchange exchange)`. Currently sets `WebSession.setMaxIdleTime(ttl)` and saves the SecurityContext; needs to ALSO call `exchange.getAttributes().put(<KEY>, rememberMe)` so the resolver can read it. Branch is reached from both `login` (`AuthService.java:582`) and `register` auto-login (`AuthService.java:167`, hard-coded `false`).
- `backend/src/main/java/com/botfunnel/security/SecurityConfig.java` — register `@Bean WebSessionIdResolver`. Boot auto-config has `@ConditionalOnMissingBean` (verified below) so our bean replaces the default.
- `backend/src/main/resources/application.properties:14-18` — already binds `server.reactive.session.cookie.http-only/secure/same-site` and `app.session.ttl-default-hours=24`, `app.session.ttl-remember-me-days=30`. We will reuse the same `app.session.*` keys for cookie Max-Age (single source of truth — same value as `WebSession.maxIdleTime`).
- `backend/src/main/java/com/botfunnel/auth/AuthController.java:34-38` — login endpoint, no change.
- `backend/src/main/java/com/botfunnel/auth/dto/LoginRequest.java:15` — `rememberMe` boolean, no change.

### To create

- `backend/src/main/java/com/botfunnel/security/RememberMeWebSessionIdResolver.java` — new class extending `CookieWebSessionIdResolver`. Override `setSessionId(ServerWebExchange, String)` to read the `rememberMe` flag from `exchange.getAttributes()` and call `setCookieMaxAge(...)` per request OR (cleaner) override the protected cookie initialization. See API options in next section.

### Tests to update / add

- `backend/src/test/java/com/botfunnel/auth/AuthServiceTest.java:306-365` — `login_success_invalidatesPreAuthSession_andSetsRememberMeTtl` and `login_success_noRememberMe_setsTtl24Hours`. Extend to also verify the attribute is set on the exchange when `rememberMe=true/false`.
- New unit test for `RememberMeWebSessionIdResolver` — verify cookie `Max-Age` derived from exchange attribute.
- Integration test — see "Test Coverage" below; the existing IT infrastructure cannot capture `Set-Cookie`.

## Existing Patterns to Reuse

### Spring Framework `CookieWebSessionIdResolver` API (spring-web 6.1.3)

Source: `~/.gradle/caches/modules-2/files-2.1/org.springframework/spring-web/6.1.3/...-sources.jar` → `org/springframework/web/server/session/CookieWebSessionIdResolver.java`.

Public/overridable methods:

- `void setCookieName(String)` / `String getCookieName()`
- `void setCookieMaxAge(Duration)` / `Duration getCookieMaxAge()` — bean-scoped, NOT per-request
- `void addCookieInitializer(Consumer<ResponseCookie.ResponseCookieBuilder>)` — registers a chain of consumers run inside `initCookie(ServerWebExchange, String)`. The consumer DOES receive the exchange-bound builder, but the `ServerWebExchange` is NOT passed to the consumer — only the builder. So `addCookieInitializer` alone cannot read the per-request flag.
- `List<String> resolveSessionIds(ServerWebExchange)` — read the cookie from the request
- `void setSessionId(ServerWebExchange, String id)` — writes cookie. Internally calls `private ResponseCookie.ResponseCookieBuilder initCookie(ServerWebExchange, String)` which sets `path`, `maxAge(getCookieMaxAge())`, `httpOnly(true)`, `secure(...)`, `sameSite("Lax")`, then runs the registered initializer. **`initCookie` is private** — cannot be overridden directly.
- `void expireSession(ServerWebExchange)` — emits `Max-Age=0` cookie (used on session invalidate).

Override strategy: extend `CookieWebSessionIdResolver` and override `setSessionId(ServerWebExchange, String)`. Read `Boolean rememberMe = exchange.getAttribute(REMEMBER_ME_ATTR)`, then either (a) call `super.setSessionId` after temporarily flipping a bean-level field — UNSAFE under reactive concurrency — or (b) build the `ResponseCookie` from scratch in the override (duplicating ~10 lines of `initCookie`). Option (b) is the only race-free choice.

### Spring Boot autoconfig

`org/springframework/boot/autoconfigure/web/reactive/WebSessionIdResolverAutoConfiguration.java` (spring-boot-autoconfigure 3.5.6 sources):

```java
@Bean
@ConditionalOnMissingBean
public WebSessionIdResolver webSessionIdResolver() { ... }
```

`@ConditionalOnMissingBean` confirms: registering our own `@Bean WebSessionIdResolver` in `SecurityConfig` makes Boot's bean back off completely. The auto-config also calls `addCookieInitializer` with `ServerProperties.reactive.session.cookie` (domain, path, httpOnly, secure, maxAge, partitioned, sameSite). Our custom bean must replicate this initialization (or extend the default and re-add the initializer).

### Logout & cookie clearing

`AuthService.logout` at `backend/src/main/java/com/botfunnel/auth/AuthService.java:250-255`:

```java
public Mono<Void> logout(ServerWebExchange exchange) {
    return exchange.getSession().flatMap(WebSession::invalidate);
}
```

`WebSession.invalidate()` triggers Spring's `WebSessionManager` to call `WebSessionIdResolver.expireSession(exchange)`, which writes `Max-Age=0`. This works for both default and our subclass (we do not override `expireSession`).

### terminate-all & change-password flows

- `ProfileService.terminateAllSessions(userId)` at `profile/ProfileService.java:108-116` — deletes all `sessions` documents for the principal. Does NOT touch the cookie. The user who triggered terminate-all KEEPS their cookie; on the next request the cookie value points to a non-existent session → Spring creates a fresh empty session and the user is logged out (401 from `/api/profile/*`). For OUR bug: same story — cookie persists with stale ID.
- `ProfileService.changePassword(...)` at `profile/ProfileService.java:72-106` calls `terminateAllSessionsExcept(userId, currentSession.getId())` keeping the current device. No cookie work.
- `ProfileService.deleteAccount(...)` at `profile/ProfileService.java:127-146` calls `terminateAllSessions` then `session.invalidate()` — invalidate triggers `expireSession` → cookie cleared.

### `sessions` collection field path

`AuthService.terminateAllSessions` at `auth/AuthService.java:389-393` and `ProfileService.terminateAllSessions` at `profile/ProfileService.java:108-116` both query `Criteria.where("principal").is(userId)` against the `sessions` collection. AppUserDetails returns `userId` from `getUsername()`. Our work doesn't change this — but tests touching `sessions` (`AuthControllerIT.logout_secondLoginCreatesIndependentSessionsDocument`, `ProfileControllerIT.terminateAllSessions_*`) must keep passing.

## Risks

| Risk | Mitigation |
|------|-----------|
| Custom resolver replaces ALL of Boot's cookie initializer (httpOnly, secure, sameSite from `application.properties` would be lost) | Read `ServerProperties.Reactive.Session.Cookie` ourselves OR extend default and call `addCookieInitializer` with the same logic Boot uses. Document explicitly. |
| Per-request mutation of bean state (e.g. `setCookieMaxAge` then `super.setSessionId`) is a race under WebFlux concurrency | Build cookie from scratch inside the override; never mutate bean fields per request. |
| `register` auto-login (`AuthService.java:167`) hard-codes `rememberMe=false`. After fix, registration sessions become browser-session cookies — same as today. No regression. | Confirm in test. |
| `terminate-all-sessions` from a remembered device leaves a long-Max-Age cookie pointing at a deleted session document | Existing behavior; on next request `me`/`/api/*` returns 401 and SPA redirects to /auth/login. SPA is expected to call /logout to clear cookie, but we are NOT changing this scope here. |
| spring-session-data-mongodb may itself wrap the resolver | Verified: it does not. The default resolver is plain `CookieWebSessionIdResolver` from spring-web. |
| Future Spring Boot upgrades may add new fields to `ServerProperties.Reactive.Session.Cookie` (e.g. `partitioned` already present in 3.5.x) | If we extend default + call `addCookieInitializer`, we automatically inherit Boot's mapping. Prefer that approach. |

## Test Coverage

### Existing tests in scope (must keep green)

- `backend/src/test/java/com/botfunnel/auth/AuthServiceTest.java:306` — `login_success_invalidatesPreAuthSession_andSetsRememberMeTtl` — currently asserts `WebSession.setMaxIdleTime(Duration.ofDays(30))`. Will be extended to assert exchange attribute.
- `backend/src/test/java/com/botfunnel/auth/AuthServiceTest.java:343` — `login_success_noRememberMe_setsTtl24Hours` — same, for `false` branch.
- `backend/src/test/java/com/botfunnel/auth/AuthServiceTest.java` brute-force suite (lines 130-474) — does not touch session cookie, unaffected.
- `backend/src/test/java/com/botfunnel/auth/AuthControllerIT.java:326-352` — `logout_endpoint_returns200`, `logout_secondLoginCreatesIndependentSessionsDocument`. Note explicit comment line 326-329: **"The Set-Cookie header is not propagated through the bind-to-context test infrastructure"** — `WebTestClient.bindToApplicationContext` (set up at `AbstractIntegrationTest.java:82-86`) does not flow cookies. Verification works through Mongo `sessions` queries instead.
- `backend/src/test/java/com/botfunnel/profile/ProfileControllerIT.java:120-160` — terminate-all-sessions IT; uses Mongo queries, not cookies.
- `backend/src/test/java/com/botfunnel/security/SecurityConfigTest.java`, `backend/src/test/java/com/botfunnel/HealthSecurityTest.java`, `backend/src/test/java/com/botfunnel/SecurityBlockTest.java` — security wiring tests; do not assert on SESSION cookie.

### Test framework

- `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@ActiveProfiles("test")` (`AbstractIntegrationTest.java:26-27`)
- Per-test `webTestClient = WebTestClient.bindToApplicationContext(applicationContext)` (`AbstractIntegrationTest.java:82-86`) — REQUIRED for `SecurityMockServerConfigurers.csrf()` mutator. Side effect: Set-Cookie not captured.
- Testcontainers: Mongo 8.0, Redis 7.4, Mailpit 1.29.7.
- `ProfileControllerIT.java:58` already seeds `sessions` documents directly.

### Cookie-capture options for new IT

Since the existing `bindToApplicationContext` infrastructure cannot capture `Set-Cookie`, an integration test that asserts cookie `Max-Age` requires either:
- A separate `WebTestClient.bindToServer().baseUrl(...)` against the RANDOM_PORT server (loses csrf() mutator — must fetch the XSRF cookie via a real GET first).
- Plain `RestClient` / `HttpClient` against `http://localhost:${port}`.
- Pure unit test on `RememberMeWebSessionIdResolver` with a mock `ServerWebExchange` (no Mongo/Redis needed). **Recommended primary coverage** — fastest, deterministic.

### No session-fixation / brute-force tests reference the resolver

`grep` for `fixation` returned zero matches under `backend/src/test/java`. Brute-force tests (`AuthServiceTest.java:259, 433, 451, 474`) operate on Redis keys only — registering a custom resolver does not affect them.

## Constraints & Infrastructure

- Java 21 toolchain, Spring Boot 3.5.0 (`backend/build.gradle:5`).
- Spring Web 6.1.3 (CookieWebSessionIdResolver source verified at this version).
- spring-session-data-mongodb stores sessions in `sessions` collection (`application.properties:13`). The session ID in the cookie maps to the `_id` field of a sessions document.
- Single `application.properties` file — no `application-prod.properties` per task notes. Test-only `application-test.properties` exists (referenced from `AbstractIntegrationTest.java:69`) for JobRunr disable.
- HardDeleteJob (`jobs/HardDeleteJob.java`) and JobRunrMongoConfig (`jobs/JobRunrMongoConfig.java`) — only touch `users` collection and JobRunr metadata, NOT `sessions`. spring-session-data-mongodb uses Mongo TTL on the `expireAt` field for session cleanup, so no application code triggers session deletion on a schedule.
- CSRF cookie (`XSRF-TOKEN`) and SESSION cookie are independent. Our changes don't touch CSRF flow (`SecurityConfig.java:44-54`).

## Recommendations

1. Subclass `CookieWebSessionIdResolver`, override only `setSessionId(ServerWebExchange, String)`, read attribute, build cookie from scratch. Re-use `addCookieInitializer` with a Consumer that copies Boot's mapping from `ServerProperties.Reactive.Session.Cookie` (httpOnly/secure/sameSite/path/domain/partitioned). Do NOT mutate bean state per request.
2. Define a constant `REMEMBER_ME_ATTR = "com.botfunnel.auth.rememberMe"` shared between `AuthService.openSession` (writer) and the resolver (reader). Place in the resolver class as `public static final`.
3. Reuse `app.session.ttl-default-hours` and `app.session.ttl-remember-me-days` for cookie Max-Age (same value as `WebSession.maxIdleTime` — single source of truth, no second knob to drift).
4. Unit-test the resolver with a mocked `ServerWebExchange` + `MockServerHttpResponse`; assert `getResponseCookies().getFirst("SESSION").getMaxAge()` matches the expected duration for both attribute branches and the missing-attribute branch (default to non-persistent cookie).
5. Extend existing `AuthServiceTest.login_*RememberMe*` tests to additionally `verify(exchange).getAttributes()` and assert the flag is set with the expected boolean.
6. Skip a full IT for the cookie header given documented `bindToApplicationContext` limitation; rely on the resolver unit test + `AuthService` exchange-attribute test for the full data flow.
