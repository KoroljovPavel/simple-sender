# Decisions Log: 04-remember-me-cookie

Agent reports on completed tasks. Each entry is written by the agent that executed the task.

---

## Task 1: Implement remember-me cookie persistence

**Status:** Done
**Commit:** b50f7b4 (fix round 1) on top of d6037b0 (initial)
**Agent:** main agent
**Summary:** Added `RememberMeWebSessionIdResolver` (subclass of `CookieWebSessionIdResolver`) that overrides only `setSessionId` to write the cookie with per-request `Max-Age` driven by the `com.botfunnel.auth.rememberMe` exchange attribute. `AuthService.openSession` publishes the boolean to that attribute. `SecurityConfig` registers the resolver as `@Bean WebSessionIdResolver`, replacing Boot's `@ConditionalOnMissingBean` default. `resolveSessionIds` and `expireSession` are inherited unchanged — pre-fix cookies still resolve and logout still writes `Max-Age=0`.
**Deviations:**
- Per code-reviewer round-1 finding #3, the `REMEMBER_ME_ATTR` write was moved out of the reactive `doOnNext` chain in `openSession` to a synchronous statement before `exchange.getSession()`. Task hint suggested an "окремий .doOnNext" — kept the spirit (separate site, explicit), but pre-chain placement reflects the data flow more honestly (attribute is request-scoped, not session-scoped). Functionally equivalent.
- Fixed an unrelated pre-existing failing assertion in `AuthServiceRegistrationTest.register_newEmail_savesPendingUser_dispatchesEmail_returnsId` — it expected `name="Alice"` after the prior commit `fbe1504` dropped name from registration. Required to pass `./gradlew :backend:build` AC.

**Reviews:**

*Round 1:*
- code-reviewer: 6 minor → [logs/working/task-1/code-reviewer-1.json](logs/working/task-1/code-reviewer-1.json)
- security-auditor: clean → [logs/working/task-1/security-auditor-1.json](logs/working/task-1/security-auditor-1.json)
- test-reviewer: 1 minor → [logs/working/task-1/test-reviewer-1.json](logs/working/task-1/test-reviewer-1.json)

*Round 2 (after fixes):*
- code-reviewer: clean → [logs/working/task-1/code-reviewer-2.json](logs/working/task-1/code-reviewer-2.json)
- test-reviewer: clean → [logs/working/task-1/test-reviewer-2.json](logs/working/task-1/test-reviewer-2.json)

**Verification:**
- `./gradlew :backend:build` → BUILD SUCCESSFUL, 174 tests pass.
- `RememberMeWebSessionIdResolverTest` (10 tests) + extended `AuthServiceTest` cases → all green.
- Smoke against running local backend on `:8080`:
  - AC-1: `POST /api/auth/login {rememberMe:true}` → `Set-Cookie: SESSION=…; Max-Age=2592000; Expires=Mon, 08 Jun 2026 …; Path=/; HTTPOnly; SameSite=Lax` ✅
  - AC-2: `{rememberMe:false}` → `Set-Cookie: SESSION=…; Path=/; HTTPOnly; SameSite=Lax` (no Max-Age, no Expires) ✅
  - AC-5: `POST /api/auth/logout` → `Set-Cookie: SESSION=; Max-Age=0; Expires=Sat, 09 May 2026 …; …` ✅
  - AC-6: `POST /api/auth/register` → session-only cookie ✅
  - AC-7: re-login flip true→false rewrites cookie attributes ✅
  - AC-8: cookie name `SESSION` matches default config; flags from `application.properties` not hard-coded ✅

---

## Task 2: Code Audit

**Status:** Done
**Commit:** (this commit)
**Agent:** main agent
**Summary:** Holistic code-quality audit of Wave 1 deliverables (`RememberMeWebSessionIdResolver`, `SecurityConfig`, `AuthService.openSession` + register path) against `code-reviewing` 11 dimensions and tech-spec Decisions 1, 2, 3, 4, 6. Verdict: **PASS** — Decisions 1/2/3/4/6 all PASS; 0 blocker, 0 major, 0 minor, 3 nits (parity-fallback seeds documented, repeated `:30` SpEL default, `Boolean.valueOf` style — none actionable). Race-freedom confirmed (no mutable bean state in resolver, no `synchronized`/`volatile`); attribute-write ordering in `AuthService.openSession` is eager pre-chain — visible to `setSessionId` callback, not nested in `flatMap`/`doOnSuccess`. Cookie attribute inventory: 0 hard-coded `ServerProperties` keys; seeds on lines 70-74 of resolver are parent-class `initCookie` parity fallback, documented in Javadoc. Register path unchanged: `openSession(saved, false, exchange)` at AuthService.java:168.
**Deviations:** None.

**Reviews:**

Audit Wave — auditor IS the review. No second-pass reviewers per tech-spec.

**Verification:**
- Audit report: [logs/working/task-2/code-audit-report.md](logs/working/task-2/code-audit-report.md)
- Decision 1 (race-free): PASS — no mutable state in resolver
- Decision 2 (exchange attribute channel): PASS — eager pre-chain put, single owner constant, null-safe read
- Decision 3 (cookie attrs from ServerProperties): PASS — 0 duplicated config literals
- Decision 4 (TTL keys reused): PASS — both consumers read `app.session.ttl-remember-me-days`
- Decision 6 (register flow unchanged): PASS — hard-coded `false` preserved, regression test in place
- Recommendation for follow-up: none. No fix-loop required for Task 1.

---

## Task 3: Security Audit

**Status:** Done
**Commit:** (this commit)
**Agent:** main agent
**Summary:** Static security audit of Wave 1 deliverables (`RememberMeWebSessionIdResolver`, `SecurityConfig`, `AuthService.openSession` + register, `application.properties`) against OWASP A02 (Cryptographic Failures) and A07 (Identification & Auth Failures). Verdict: **APPROVE** — 0 blocker, 0 major, 0 minor; 2 informational notes (pre-existing dev-default `Secure=false` in `application.properties:15`; manual `PropertyMapper` overlay does not auto-forward future Spring Boot `Cookie` POJO fields — already documented as `[TECHNICAL]` note in tech-spec). All 11 checklist items pass: cookie flags flow through `ServerProperties` (no literals), env vars `SESSION_COOKIE_*` still effective, no session-id logging (grep-confirmed in resolver and `AuthService.openSession`), CSRF stack untouched, attribute-absent fallback emits session-only cookie (Scenario C corroborates), no bean-level mutable state, TTL synced via `app.session.ttl-remember-me-days`, logout inherited (`Max-Age=0`), pre-fix cookies still authorize, register flow keeps hard-coded `false` at `AuthService.java:168`. `REMEMBER_ME_ATTR` is single-write server-side (`AuthService.java:604–605`), no external injection vector under WebFlux.
**Deviations:** None.

**Reviews:**

Audit Wave — auditor IS the review. No second-pass reviewers per tech-spec.

**Verification:**
- Audit report: [logs/working/task-3/security-audit-report.md](logs/working/task-3/security-audit-report.md)
- OWASP A02: clean — no weakening of `Secure`/`HttpOnly`/`SameSite`; cookie value (session id) opaque, untouched.
- OWASP A07: clean — `REMEMBER_ME_ATTR` server-set, fallback safe, logout/terminate-all unchanged (Decision 5).
- No fix-loop required for Task 1.

---

## Task 4: Test Audit

**Status:** Done
**Commit:** (this commit)
**Agent:** main agent
**Summary:** Static test-quality audit of Wave 1 deliverables (`RememberMeWebSessionIdResolverTest`, extended `AuthServiceTest`, verified `AuthServiceRegistrationTest`). Verdict: **PASS** — 0 blocker, 0 major, 2 minor, 1 nit. All Scenarios A-H covered with non-vacuous, deterministic assertions; three AuthService cases (login+true, login+false, register-auto-login at `AuthServiceTest.java:492`) lock `REMEMBER_ME_ATTR` against silent regression. `mockExchangeWithCachedSession` correctly uses real `ConcurrentHashMap` for `getAttributes()` — attribute writes are observable, no silent NPE/no-op false-confidence. Decision 7 IT-defer is honored as documented (`AuthControllerIT.java:326-329`); not flagged as a finding.
**Deviations:** None.

**Reviews:**

Audit Wave — auditor IS the review. No second-pass reviewers per tech-spec.

**Verification:**
- Audit report: [logs/working/task-4/test-audit-report.md](logs/working/task-4/test-audit-report.md)
- Coverage matrix: 8 resolver scenarios + 3 AuthService cases — all green.
- Litmus test: removing `builder.maxAge(...)` in resolver fails Scenarios A/D/E; removing `exchange.getAttributes().put(...)` in `AuthService.openSession` fails all three AuthService cases.
- Minor findings (cookie value not asserted in Scenarios B/C, magic `30` in Scenario A) are non-blocking polish; may be folded into follow-up cleanup.
- No fix-loop required. Final Wave (Task 5 Pre-deploy QA) unblocked.

---

## Task 5: Pre-deploy QA

**Status:** Done
**Commit:** b50f7b4 (feature head verified)
**Agent:** main agent
**Summary:** Final-Wave acceptance testing for `04-remember-me-cookie`. Verdict: **PASSED** — `./gradlew :backend:test` 174/174 green; AC-1..AC-8 all pass against live `:8080` (default config + AC-8 cookie-name override via `SERVER_REACTIVE_SESSION_COOKIE_NAME=ALT` rebuild); manual browser smoke (Nuxt `:3000` + DevTools) confirmed by user. Zero critical, zero major, zero minor findings; nothing deferred to post-deploy.
**Deviations:** None.

**Reviews:**

QA — verification IS the review. No second-pass reviewers per tech-spec.

**Verification:**
- Full report: [logs/working/task-5/qa-report.json](logs/working/task-5/qa-report.json)
- Smoke log: [logs/working/task-5/smoke.log](logs/working/task-5/smoke.log)
- Tests: `./gradlew :backend:test` → 174 passed, 0 failed, 0 skipped (20.7s)
- AC-1: `Set-Cookie: SESSION=...; Max-Age=2592000; Expires=Mon, 08 Jun 2026 ...` ✅
- AC-2: `Set-Cookie: SESSION=...; Path=/; HTTPOnly; SameSite=Lax` (no Max-Age) ✅
- AC-3: covered by `RememberMeWebSessionIdResolverTest` Scenario E + `AuthServiceTest` ✅
- AC-4: `GET /api/auth/me` with raw `Cookie: SESSION=<value>` → 200 OK + body ✅
- AC-5: logout → `Set-Cookie: SESSION=; Max-Age=0; Expires=Sat, 09 May 2026 ...` ✅
- AC-6: `POST /api/auth/register` → session-only cookie ✅
- AC-7: re-login `true→false` rewrites cookie attrs (same jar) ✅
- AC-8: `SERVER_REACTIVE_SESSION_COOKIE_NAME=ALT` → `Set-Cookie: ALT=...` (no code changes) ✅
- Manual browser smoke (`/auth/login` checkbox + browser restart + logout) — user-confirmed OK
- Feature ready for merge. No fix-loop required.

---

<!-- Entries are added by agents as tasks are completed.

Format is strict — use only these sections, do not add others.
Do not include: file lists, findings tables, JSON reports, step-by-step logs.
Review details — in JSON files via links. QA report — in logs/working/.

## Task N: [title]

**Status:** Done
**Commit:** abc1234
**Agent:** [teammate name or "main agent"]
**Summary:** 1-3 sentences: what was done, key decisions. Not a file list.
**Deviations:** None / Deviated from spec: [reason], did [what].

**Reviews:**

*Round 1:*
- code-reviewer: 2 findings → [logs/working/task-N/code-reviewer-1.json]
- security-auditor: OK → [logs/working/task-N/security-auditor-1.json]

*Round 2 (after fixes):*
- code-reviewer: OK → [logs/working/task-N/code-reviewer-2.json]

**Verification:**
- `npm test` → 42 passed
- Manual check → OK

-->
