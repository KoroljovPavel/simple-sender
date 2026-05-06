# Decisions Log: 02 — Authentication & Accounts

Agent reports on completed tasks. Each entry is written by the agent that executed the task.

---

## Task 1: Infrastructure Setup

**Status:** Done
**Commit:** 79f4612
**Agent:** main agent
**Summary:** Fixed broken MONGODB_URI and REDIS_URL property bindings that have existed since Epic 01. Added 7 new Gradle dependencies (spring-session-data-mongodb, spring-boot-starter-mail, spring-boot-starter-validation, jobrunr-spring-boot-3-starter, testcontainers suite) with JaCoCo plugin. Added Mailpit to docker-compose.yml (127.0.0.1-bound ports, pinned to v1.29.7, healthcheck). Created application-test.properties stub. Synced .env.example.
**Deviations:** Replaced `server.servlet.session.cookie.http-only=true` (Servlet-only, silently ignored in WebFlux) with `server.reactive.session.cookie.http-only=true` — the correct reactive stack property. Removed empty default from `SUPER_ADMIN_PASSWORD` env binding to enforce fail-fast startup if var is unset. Pinned Mailpit image to `v1.29.7` (pulled version) instead of `latest`. These deviations correct spec oversights found during review.

**Reviews:**

*Round 1:*
- code-reviewer: 2 major, 2 minor → [logs/working/task-1/code-reviewer-1.json](logs/working/task-1/code-reviewer-1.json)
- security-auditor: 2 major, 2 minor → [logs/working/task-1/security-auditor-1.json](logs/working/task-1/security-auditor-1.json)
- infrastructure-reviewer: 1 major, 3 minor, 1 nitpick → [logs/working/task-1/infrastructure-reviewer-1.json](logs/working/task-1/infrastructure-reviewer-1.json)

*Round 1 fixes committed (79f4612): all critical/major findings resolved.*

**Verification:**
- `./gradlew compileJava` → BUILD SUCCESSFUL
- `./gradlew dependencies | grep spring-session-data-mongodb` → dependency listed (3.5.0)
- Smoke: `curl http://localhost:8025/api/v1/messages` → JSON with `messages` array (Mailpit alive)

---

## Task 2: User Domain

**Status:** Done
**Commit:** f36ec53
**Agent:** main agent
**Summary:** Created full User domain (UserStatus enum, User @Document with all token/timestamp fields, UserRepository with custom finders, UserService with softDelete and canActivate). Created common error infrastructure (AppException with 5 factory methods, ErrorResponse record, GlobalErrorHandler with AppException/BindException/ResponseStatusException/Throwable handlers). Extended SecurityConfig: removed CSRF disable, wired WebSessionServerSecurityContextRepository, configured CORS with @Value-injected origin and allowCredentials, added XorServerCsrfTokenRequestAttributeHandler, permitted /api/auth/**, disabled httpBasic/formLogin. Added BCryptPasswordEncoder bean. Also added @ActiveProfiles("test") and @MockitoBean StorageProvider to all integration tests (JobRunr added as dependency in Task 1 requires StorageProvider mock); updated application-test.properties with safe test env var defaults.
**Deviations:** `WebSessionServerSecurityContextRepository.getInstance()` does not exist in Spring Security 6.x — `new WebSessionServerSecurityContextRepository()` is the correct form (compile-verified). Added `ResponseStatusException` handler to `GlobalErrorHandler` to prevent Spring's 404/etc responses being swallowed as 500 by the catch-all. Added `server.reactive.session.cookie.secure` and `same-site` properties (env-configurable).

**Reviews:**

*Round 1:*
- code-reviewer: 1 critical, 4 major, 4 minor → [logs/working/task-2/code-reviewer-1.json](logs/working/task-2/code-reviewer-1.json)
- security-auditor: 3 critical, 3 major, 4 minor → [logs/working/task-2/security-auditor-1.json](logs/working/task-2/security-auditor-1.json)
- test-reviewer: 4 major, 3 minor → [logs/working/task-2/test-reviewer-1.json](logs/working/task-2/test-reviewer-1.json)

*Round 1 fixes committed (f36ec53): all critical/major findings resolved. Critical `getInstance()` documented as spec error (method doesn't exist in Spring Security 6).*

**Verification:**
- `./gradlew test` → BUILD SUCCESSFUL (16 tests total, 0 failed)
- `./gradlew test --tests "com.botfunnel.user.*"` → 5 tests passed
- `./gradlew test --tests "com.botfunnel.common.*"` → 6 tests passed
- `./gradlew test --tests "com.botfunnel.security.*"` → 2 tests passed

---

## Task 3: Email service + templates

**Status:** Done
**Commit:** 380140b
**Agent:** main agent
**Summary:** Created `EmailService` with three send methods (verification, password reset, account blocked) using a `Mono.fromCallable` fire-and-forget pattern on `Schedulers.boundedElastic()`. HTML templates in Ukrainian loaded from classpath; all user-supplied values HTML-escaped via a `htmlEscape()` chain covering `&`, `<`, `>`, `"`, and `'`. Created three classpath templates with correct Ukrainian subject lines and URL placeholder formats.
**Deviations:** Added single-quote escaping (`'` → `&#39;`) not mentioned in spec — required to prevent XSS in single-quoted attribute contexts (security-auditor finding). `loadTemplate` made package-private to enable spy-based template-not-found testing without altering the main send path.

**Reviews:**

*Round 1:*
- code-reviewer: approved_with_suggestions (3 minor) → [logs/working/task-3/code-reviewer-1.json](logs/working/task-3/code-reviewer-1.json)
- security-auditor: changes_required (6 major, 4 minor) → [logs/working/task-3/security-auditor-1.json](logs/working/task-3/security-auditor-1.json)
- test-reviewer: needs_improvement (3 major, 3 minor) → [logs/working/task-3/test-reviewer-1.json](logs/working/task-3/test-reviewer-1.json)

*Round 2 (after fixes — commit ccb9762):*
- code-reviewer: approved (3 minor) → [logs/working/task-3/code-reviewer-2.json](logs/working/task-3/code-reviewer-2.json)
- security-auditor: approved (5 minor) → [logs/working/task-3/security-auditor-2.json](logs/working/task-3/security-auditor-2.json)
- test-reviewer: needs_improvement (2 major, 1 minor) → [logs/working/task-3/test-reviewer-2.json](logs/working/task-3/test-reviewer-2.json)

*Round 3 (after fixes — commit 380140b):*
- test-reviewer: passed (1 minor, accepted) → [logs/working/task-3/test-reviewer-3.json](logs/working/task-3/test-reviewer-3.json)

**Verification:**
- `./gradlew test --tests "com.botfunnel.email.*"` → 11 passed, 0 failed

---

## Task 4: Login + brute-force + session management

**Status:** Done
**Commit:** a2efb06
**Agent:** main agent
**Summary:** Implemented `POST /api/auth/login` and `GET /api/auth/me` with the full security envelope: dual brute-force counters (per-email ≥5, per-IP ≥20), dummy bcrypt + INCR on missing email (closing the 429 status oracle and timing leak), session fixation prevention via WebSession invalidate-and-recreate, rememberMe-aware TTL (30d / 24h), Decision-4 fail-open Redis semantics, and audited login_failed events on every failure path (user_not_found, wrong_password, blocked, deleted, brute_force). Created the entire `events` package (Event @Document, EventRepository, EventService) used by all subsequent auth flows.
**Deviations:**
- `AppUserDetails` is a `record(id, email, name, status)` — not the full `User` entity originally implied by the task. Reason: Spring Session serializes the SecurityContext into MongoDB `sessions.attributes`; carrying the full User would make the sessions collection a secondary store of `passwordHash` and token hashes. Round-1 critical security finding.
- Email is canonicalized via `toLowerCase(Locale.ROOT)` at login() entry. Reason: the brute-force key was case-sensitive, allowing a 5x attempt budget per case variant. Locale.ROOT avoids Turkish dotless-i drift.
- `AuthService.me()` does not take `ServerWebExchange` — uses `ReactiveSecurityContextHolder` instead. Functionally equivalent, structurally cleaner.
- `WebSession.setMaxIdleTime(Duration)` is the WebFlux API; persisted as `maxInactiveInterval` (seconds) in `sessions` collection (the spec's terminology was Servlet-flavoured).
- `ServerSecurityContextRepository` extracted as `@Bean` in `SecurityConfig` so `AuthService` can inject the same instance the filter chain uses.
- `SecurityConfigTest::authEndpoints_permitWithoutAuthentication` updated: `/api/auth/me` now returns 401 (controller-level) instead of 404 (path didn't exist before).
- 4 TDD-anchor tests deferred to the `AbstractIntegrationTest` task (per task hint): `login_success_sessionFixation_newSessionId`, `login_rememberMe_sessionTtlIs30Days`, `login_noRememberMe_sessionTtlIs24H`, `login_success_loginSuccessEventInDB`. They require Testcontainer-backed real MongoDB to query the `sessions` and `events` collections. Equivalent behaviour is verified at the unit level via WebSession mocks and `verify(eventService).logEvent(...)` argument capture.
- `AuthControllerIT` is a `@WebFluxTest` slice (not full Testcontainers IT) for the same reason. Filename retained per task spec; will become full IT when AbstractIntegrationTest lands.
- Cookie `Secure`/`SameSite` defaults remain `false`/`lax` (dev-friendly). Production must override via `SESSION_COOKIE_SECURE=true` / `SESSION_COOKIE_SAME_SITE=strict` — to be enforced via `deployment.md` in Task 9 per Decision 12.
- INCR + conditional EXPIRE is not Lua-atomic. Acknowledged inline; small race window only relevant on Redis crash between INCR and EXPIRE, with the worst case being a TTL-less stuck counter.

**Reviews:**

*Round 1:*
- code-reviewer: approved_with_suggestions (14 minor) → [logs/working/task-4/code-reviewer-1.json](logs/working/task-4/code-reviewer-1.json)
- security-auditor: changes_required (4 critical, 5 major, 3 minor) → [logs/working/task-4/security-auditor-1.json](logs/working/task-4/security-auditor-1.json)
- test-reviewer: needs_improvement (3 major, 6 minor) → [logs/working/task-4/test-reviewer-1.json](logs/working/task-4/test-reviewer-1.json)

*Round 2 (after fixes — commit 1b4dcf8):*
- code-reviewer: approved (12 minor) → [logs/working/task-4/code-reviewer-2.json](logs/working/task-4/code-reviewer-2.json)
- security-auditor: changes_required (1 critical, 1 major) → [logs/working/task-4/security-auditor-2.json](logs/working/task-4/security-auditor-2.json)
- test-reviewer: passed (2 minor accepted) → [logs/working/task-4/test-reviewer-2.json](logs/working/task-4/test-reviewer-2.json)

*Round 3 (after fixes — commit a2efb06):*
- code-reviewer: approved (3 minor accepted) → [logs/working/task-4/code-reviewer-3.json](logs/working/task-4/code-reviewer-3.json)
- security-auditor: approved → [logs/working/task-4/security-auditor-3.json](logs/working/task-4/security-auditor-3.json)

**Verification:**
- `./gradlew test --tests "com.botfunnel.auth.*" --tests "com.botfunnel.events.*"` → 34 passed, 0 failed
  (AuthServiceTest 21, AuthControllerIT 5, TokenServiceTest 6, EventServiceTest 2)
- `./gradlew test` (full suite, regression) → 56 passed, 0 failed

---

## Task 5: Registration + email verification + resend

**Status:** Done
**Commit:** 9d99c34
**Agent:** main agent
**Summary:** Implemented `POST /api/auth/register`, `GET /api/auth/verify-email`, and `POST /api/auth/resend-verification` with `@ValidPassword` Bean Validation constraint, SHA-256-hashed token storage (Decision 2), 30-day soft-delete window, per-IP register rate-limit (Redis INCR/EXPIRE 10/min, fail-open per Decision 4), and SET-NX-EX-60 resend rate-limit (called unconditionally to close timing oracle, Decision 7). Created `AbstractIntegrationTest` base (MongoDB + Redis + Mailpit testcontainers) and converted `AuthControllerIT` into a real Testcontainers IT covering register / verify / resend end-to-end (HTTP + Mongo + Mailpit + events collection assertions). Existing login slice tests moved to `AuthControllerSliceTest`.
**Deviations:**
- BCrypt encode in registration runs on `Schedulers.boundedElastic` (mirrors login path) — required to avoid blocking the Reactor Netty event loop on the cost-12 ~250ms hash.
- Soft-delete repurpose path explicitly resets `isSuperAdmin=false` and clears all password-reset fields on every register, closing a privilege-escalation regression where a re-registered email would have inherited the previous account's superadmin flag.
- Per-IP register rate-limit (10/min) added beyond the task spec, gating BCrypt CPU before it runs (security-auditor finding). Threshold tuneable; start loose to avoid blocking legitimate teammate signups.
- `AbstractIntegrationTest` ships a `@Primary JavaMailSender` bean instead of relying on `@DynamicPropertySource` for `spring.mail.host/port` — `MailSenderAutoConfiguration` binds those keys at bean-creation time and the dynamic override does not consistently win for them (Mongo + Redis URIs work fine via DynamicPropertySource). Documented inline.
- `@WebFluxTest` slice tests moved to a new `AuthControllerSliceTest` so `AuthControllerIT` could become a real IT with the file path called out by the TDD anchors.
- WebTestClient in IT is rebuilt per test from the application context (not bound-to-server) so `SecurityMockServerConfigurers.csrf()` works under WebFlux RANDOM_PORT.
- Audit events for register and resend are NOT logged — the existing events-collection enum (login_success, login_failed, password_changed, email_verified, account_blocked, account_unblocked, account_deleted) does not define register / resend events; logging them would require extending the schema, out of scope here.
- Distinct 409 messages for "already exists" vs "recently deleted" kept per explicit user-spec requirement (lines 25-26), even though the security audit flagged the variance as anti-enumeration noise.

**Reviews:**

*Round 1:*
- code-reviewer: changes_required (1 critical, 2 major, 6 minor) → [logs/working/task-5/code-reviewer-1.json](logs/working/task-5/code-reviewer-1.json)
- security-auditor: changes_required (1 critical, 2 major, 5 minor) → [logs/working/task-5/security-auditor-1.json](logs/working/task-5/security-auditor-1.json)
- test-reviewer: needs_improvement (0 critical, 2 major, 6 minor) → [logs/working/task-5/test-reviewer-1.json](logs/working/task-5/test-reviewer-1.json)

*Round 1 fixes committed (963d61f): all critical and major findings resolved, several minors addressed inline.*

*Round 2 (after fixes):*
- code-reviewer: approved (0 critical, 0 major, 6 optional minor) → [logs/working/task-5/code-reviewer-2.json](logs/working/task-5/code-reviewer-2.json)
- security-auditor: approved (0 critical, 0 major, 1 minor — register-rate threshold tuning) → [logs/working/task-5/security-auditor-2.json](logs/working/task-5/security-auditor-2.json)
- test-reviewer: passed (0 critical, 0 major, 2 minor) → [logs/working/task-5/test-reviewer-2.json](logs/working/task-5/test-reviewer-2.json)

*Round 2 minor polish committed (9d99c34): replaced Thread.sleep with Awaitility during(); repurpose IT now also asserts password-reset fields cleared.*

**Verification:**
- `./gradlew test` (full suite) → 101 passed, 0 failed
- AuthControllerIT (Testcontainers MongoDB + Redis + Mailpit) → 10 passed
- AuthControllerSliceTest (WebFlux slice) → 5 passed
- AuthServiceRegistrationTest (unit) → 20 passed
- ValidPasswordValidatorTest (unit) → 10 passed

---

## Task 6: Logout + password reset

**Status:** Done
**Commit:** 75f8cba
**Agent:** main agent
**Summary:** Implemented `POST /api/auth/logout` (current-WebSession invalidate only), `POST /api/auth/forgot-password` (always-200 anti-enumeration with per-IP SET-NX-EX 60s rate limit, BCrypt-hashed reset token, fire-and-forget email), and `POST /api/auth/reset-password` (status-gated to pending/active, expiry + one-time-use checks, terminate-all-sessions via `ReactiveMongoTemplate.remove(Criteria.where("principal").is(userId), "sessions")`). Verified the spring-session-data-mongodb principal field path at runtime (top-level `principal`) via a dedicated IT.
**Deviations:**
- TDD-anchor `logout_invalidatesCurrentSession_otherSessionUnaffected` is split: the IT cannot drive a real SESSION cookie through `WebTestClient.bindToApplicationContext` (it silently drops the Set-Cookie even though spring-session-data-mongodb persists the document). Behaviour is verified at the unit level (`AuthServicePasswordResetTest.logout_invalidatesCurrentWebSession_returnsCompletes`) plus an IT (`logout_secondLoginCreatesIndependentSessionsDocument`) proving the multi-session model the contract relies on.
- Added per-IP `forgot:rate:ip:` rate limit (Redis SET-NX-EX 60s) and a calibrated 40ms baseline delay on the unknown/deleted/blocked branch to close email-bombing (CWE-307) and timing-oracle (CWE-208) vectors flagged by security-auditor.
- Added an explicit `pending|active` status gate at the top of `resetPassword` so a token issued just before an admin block / account deletion cannot rotate the password (code-reviewer major). Blocked users in `forgotPassword` now hit the same no-op as unknown email.
- forgot-password unknown/deleted/blocked branch logs `password_reset_requested` with `metadata=null` (NOT `Map.of("email", ...)`) — the audit log must not become the enumeration oracle the response shape was meant to prevent.

**Reviews:**

*Round 1:*
- code-reviewer: approved_with_suggestions (2 major, 7 minor, 4 nit) → [logs/working/task-6/code-reviewer-1.json](logs/working/task-6/code-reviewer-1.json)
- security-auditor: approved (2 major, 5 minor) → [logs/working/task-6/security-auditor-1.json](logs/working/task-6/security-auditor-1.json)
- test-reviewer: passed (4 minor) → [logs/working/task-6/test-reviewer-1.json](logs/working/task-6/test-reviewer-1.json)

*Round 1 fixes committed (75f8cba): all majors resolved; PII-in-logs and DTO `@Size(max=254)` minors also addressed.*

*Round 2 (after fixes):*
- code-reviewer: approved (1 minor — `@Size` on other DTOs, Task 5 scope) → [logs/working/task-6/code-reviewer-2.json](logs/working/task-6/code-reviewer-2.json)
- security-auditor: approved (3 minor — per-email rate-limit complement, virtual-time timing-parity test, frontend Referrer-Policy) → [logs/working/task-6/security-auditor-2.json](logs/working/task-6/security-auditor-2.json)
- test-reviewer: passed (3 carry-over minor — overstated test name, IT message-parity, magic numbers in setUp) → [logs/working/task-6/test-reviewer-2.json](logs/working/task-6/test-reviewer-2.json)

*Test-name minor polished post-Round-2: `resetPassword_runsBcryptOnBoundedElastic_doesNotBlockEventLoop` → `resetPassword_callsPasswordEncoderEncodeOnce_withNewPassword` (matches what the assertion actually verifies).*

**Verification:**
- `./gradlew test` (full suite) → 130 passed, 0 failed
- AuthControllerIT logout/forgot/reset/passwordChanged smoke → all green
- AuthServicePasswordResetTest (unit) → 19 passed
- IT `sessionsCollection_principalFieldPath_isAtTopLevel` confirms top-level `principal` field path on real Testcontainer Mongo

---

## Task 7: Profile endpoints + Super Admin seed + JobRunr hard-delete

**Status:** Done
**Commit:** 8cc1b7c
**Agent:** main agent
**Summary:** Implemented `ProfileController`/`ProfileService` for the five profile endpoints (GET/PATCH /api/profile, change-password with terminate-other-sessions per Decision 14, terminate-all-sessions, DELETE /api/profile with full session termination), idempotent `SuperAdminSeeder` (ApplicationRunner), and `HardDeleteJob` (JobRunr `@Recurring` daily at 03:00, GDPR audit log, 30d-inclusive cutoff). Added a Redis brute-force counter on change-password (`change-pwd:fail:{userId}`, threshold 5 / 15min, fail-open) to close the BCrypt-grinding window from a hijacked session, plus a status gate in `loadActiveUser` that rejects blocked/deleted users with 401 even when the session principal still says active.
**Deviations:**
- `deleteAccount` now invalidates ALL sessions for the user (not just the current one). The task spec wording said "invalidate session" — security-auditor major found that leaving sibling sessions alive defeats the intent of "delete account." `terminateAllSessions(userId)` runs before `session.invalidate()`.
- `loadActiveUser` adds a status gate that mirrors `AuthService.login` — non-active/non-pending users get 401 from every profile endpoint. The original task spec did not explicitly require this; security-auditor critical (#1).
- Per-userId Redis brute-force counter on change-password (security-auditor major #4). Not in the task spec, but mirrors the login-side primitive and uses the same fail-open semantic per Decision 4.
- `HardDeleteJob` cutoff uses `Instant.now().minus(30d).plusNanos(1)` so the strict-less-than finder behaves inclusively at the 30d boundary (AC line 81). Without the bump, a user soft-deleted exactly 30 days ago to the second would survive an extra day.
- Failed change-password attempts are NOT logged as `password_change_failed` events — the events enum is closed (Task 5 deviation precedent). The Redis brute-force counter is the active defense; visibility gap accepted.
- `terminateAllSessions(userId)` is duplicated between `AuthService` (reset-password) and `ProfileService` — accepted; the Task 6 IT pins the single field path, and a shared helper would pull AuthService into ProfileService's dep graph for one query. Documented inline.
- IT cannot drive a real persisted SESSION cookie under `@WithMockAppUser` (no real cookie flow), so "current session survives change-password" is verified at the unit level (Query.toJson() pinning of `_id != currentSessionId`). The IT instead seeds sibling sessions and asserts they are removed. Documented in test comments.
- New shared `JobRunrInMemoryConfig` test bean replaces the previous `@MockitoBean StorageProvider` pattern — `@Recurring` post-processor needs a non-null storage to register the job at startup; an InMemory provider plus `org.jobrunr.background-job-server.enabled=false` keeps tests fast and deterministic.
- `WithMockAppUser` custom `@WithSecurityContext` annotation injects an `AppUserDetails`-principal `Authentication` into the test SecurityContext. Required because the controller checks `instanceof AppUserDetails` and standard `@WithMockUser` injects a Spring Security `User` instead.

**Reviews:**

*Round 1:*
- code-reviewer: approved_with_suggestions (0 critical, 2 major, 8 minor, 2 nitpick) → [logs/working/task-7/code-reviewer-1.json](logs/working/task-7/code-reviewer-1.json)
- security-auditor: changes_required (1 critical, 3 major, 3 minor) → [logs/working/task-7/security-auditor-1.json](logs/working/task-7/security-auditor-1.json)
- test-reviewer: needs_improvement (3 major, 3 minor) → [logs/working/task-7/test-reviewer-1.json](logs/working/task-7/test-reviewer-1.json)

*Round 1 fixes committed (8cc1b7c): critical and majors resolved across all three reviewers.*

*Round 2 (after fixes):*
- code-reviewer: approved_with_suggestions (0 critical, 0 major, 4 minor, 3 nitpick — all optional) → [logs/working/task-7/code-reviewer-2.json](logs/working/task-7/code-reviewer-2.json)
- security-auditor: approved_with_suggestions (0 critical, 0 major, 4 minor — non-blocking) → [logs/working/task-7/security-auditor-2.json](logs/working/task-7/security-auditor-2.json)
- test-reviewer: passed (0 major, 2 minor — accepted) → [logs/working/task-7/test-reviewer-2.json](logs/working/task-7/test-reviewer-2.json)

**Verification:**
- `./gradlew test` (full suite) → 156 passed, 0 failed
- ProfileServiceTest (unit) → 9 passed
- ProfileControllerIT (Testcontainers, MongoDB + Redis) → 8 passed
- SuperAdminSeederTest (Testcontainers) → 5 passed
- HardDeleteJobTest (Testcontainers) → 4 passed

---

## Task 8: Frontend bootstrap

**Status:** Done
**Commit:** 477048b (round 1 fixes), final polish in next chore commit
**Agent:** main agent
**Summary:** Bootstrapped Nuxt 4 frontend: installed Pinia, Vue Query, vee-validate/zod, shadcn-vue toolkit (radix-vue, CVA, clsx, tailwind-merge, lucide), Tailwind, vitest 3 + @nuxt/test-utils. Created SSR-safe Pinia auth store backed by `useState('auth-user')`, global auth middleware with hydrate-then-route logic for both protected and `/auth/**` routes, `useApi` $fetch wrapper that propagates `XSRF-TOKEN` cookie as `X-XSRF-TOKEN` header on non-safe methods (Decision 12 alignment), Vue Query SSR plugin, default and auth layouts, and updated `app.vue` to render `<NuxtLayout><NuxtPage /></NuxtLayout>`. 12 unit tests cover store and middleware flows.
**Deviations:**
- Upgraded vitest from spec-implied `^2` to `^3.2.4` — Nuxt 4.4's vite-builder calls `viteServer.environments.client` which only exists in Vite 6 (vitest 3+). Vitest 2 fails at startup with "Cannot read properties of undefined (reading 'client')".
- Removed explicit `vue` from `package.json` deps — code-reviewer flagged it as a duplicate-Vue-copies risk; pulled transitively via `nuxt`.
- Updated `app.vue` from "Hello World" stub to `<NuxtLayout><NuxtPage />` — required for layouts/pages to render at all (tasks 10/11). Bootstrap-scope addition.
- Added CSRF token forwarding in `useApi.ts` (security-auditor critical round 1) — was not explicit in task spec but required by Decision 12 (`CookieCsrfTokenRepository.withHttpOnlyFalse`).
- Added `!frontend/plugins/` exception to root `.gitignore` — `plugins/` was globally ignored (Claude Code convention) and was hiding the new Vue Query plugin.

**Reviews:**

*Round 1:*
- code-reviewer: changes_required (0 critical, 2 major, 5 minor) → [logs/working/task-8/code-reviewer-1.json](logs/working/task-8/code-reviewer-1.json)
- security-auditor: changes_required (1 critical, 3 major, 3 minor, 1 low) → [logs/working/task-8/security-auditor-1.json](logs/working/task-8/security-auditor-1.json)
- test-reviewer: needs_improvement (1 major, 4 minor) → [logs/working/task-8/test-reviewer-1.json](logs/working/task-8/test-reviewer-1.json)

*Round 1 fixes committed (477048b): CSRF header, middleware always-hydrate + non-401 swallow, layout logout error swallow, vue dep removed, requiresAuth helper inlined, 401 test pre-arms user, +3 new tests (500 propagation, logout error, valid-session-on-/auth/login, middleware backend-error).*

*Round 2 (after fixes):*
- code-reviewer: approved_with_suggestions (0 critical, 0 major, 4 minor) → [logs/working/task-8/code-reviewer-2.json](logs/working/task-8/code-reviewer-2.json)
- security-auditor: approved (0 critical, 0 major; 5 round-1 items deferred — apiBase prod fallback, fetchUser stale-state on 5xx, useState SSR leak, missing CSP, happy-dom advisory) → [logs/working/task-8/security-auditor-2.json](logs/working/task-8/security-auditor-2.json)
- test-reviewer: passed (0 critical, 0 major, 2 minor carry-over) → [logs/working/task-8/test-reviewer-2.json](logs/working/task-8/test-reviewer-2.json)

**Verification:**
- `cd frontend && pnpm test` → 12 passed (auth.spec: 6, auth.global.spec: 6)
- `pnpm nuxt build` → BUILD SUCCESSFUL (3.6 MB total, 846 kB gzip)
- `pnpm dev` smoke (curl) → `/` → 302 `/auth/login`; `/dashboard` → 302 `/auth/login`; `/auth/login` → 200; no errors in dev log
- User check → confirmed by user ("так все добре")
