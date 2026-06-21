# Security Audit — migrate-to-virtual-threads (Task 15)

**Auditor:** security-auditor skill (single-agent linear walk).
**Scope:** Wave 1 + Wave 2 + Wave 3 source + audit-target test files per task-15.md.
**Method:** Read-only inspection of every file in the audit scope, cross-referenced against the seven focus areas, the additional cross-cutting checks, and OWASP Top 10 (2021).
**Output:** This report only. No source or test was modified.

---

## 1. Executive Summary

**Verdict: PASS WITH CONDITIONS.**

All seven Task-15 focus areas come out clean on the migration touch surface. The atomic WebFlux → Spring MVC + virtual-threads flip preserves every load-bearing security invariant: CSRF wiring, session-fixation defence, timing-attack guard, fail-open rate-limiting, token scrubbing on the Telegram path, `TokenEncryptor` byte-identity, and `RememberMeCookieSerializer` (no token leak).

The conditions are pre-existing weaknesses surfaced (not introduced) by this migration plus a handful of documentation / coverage gaps:

- **No Critical findings.** `TokenEncryptor` is byte-identical to the pre-migration source (verified via `git log`). No algorithm/key/IV drift.
- **No High findings.** All seven focus areas pass with concrete file:line evidence (see §2).
- **3 Medium findings** — all pre-existing weaknesses preserved through the migration touch surface: `HttpRequestUtils.extractIp` trusts `X-Forwarded-For` unconditionally (M1); insecure default values shipped in `application.properties` for `BOT_TOKEN_ENCRYPTION_KEY` and `SUPER_ADMIN_PASSWORD` (M2); CSRF uses the plain `CsrfTokenRequestAttributeHandler` instead of the XOR / BREACH-mitigating handler called out in tech-spec Task 15 (M3 — deliberate deviation, see Deviation note in decisions.md Task 3, but flagged here because Task 15 description still demands XOR).
- **5 Low findings** — TC11 (XSRF-TOKEN co-emission on the SAME login response) is documented as structurally unachievable in MockMvc and never asserted on a successful POST (L1); per-site token-scrubber automation is missing on 3 of the 6 TC10 sites (L2); spec-internal divergence between Architecture and Task-15 description on the session-fixation API (L3); JobRunr `MongoClient` Javadoc claims a reactive primary driver that no longer exists (L4); 3 `ProcessTelegramUpdateJob` log sites pass unscrubbed string arguments that, by Mongo ObjectId / Long shape, cannot match the token regex — defensive scrubber missing per the TC10 rule "scrub every argument" (L5).

**Top 3 findings (severity-ordered):**
1. **M1 — `HttpRequestUtils.extractIp` IP-spoofing vector for brute-force counter evasion** (`common/HttpRequestUtils.java:33`).
2. **M2 — Hardcoded weak defaults for `BOT_TOKEN_ENCRYPTION_KEY` (all-zero 32-byte) and `SUPER_ADMIN_PASSWORD` ("12345678") in `application.properties`** (`backend/src/main/resources/application.properties:41,47`).
3. **M3 — `CsrfTokenRequestAttributeHandler` (plain) used in place of `XorCsrfTokenRequestAttributeHandler`; Task 15 description requires XOR for BREACH mitigation** (`security/SecurityConfig.java:75`).

**Task 17 (pre-deploy QA) status: NOT BLOCKED.** Zero Critical and zero High findings. The three Medium findings are pre-existing accepted-risk items that this migration was not chartered to fix; they should be tracked as standalone follow-up tickets (M1 is in scope for the reverse-proxy deployment story; M2 is config hygiene; M3 is a spec-vs-code reconciliation decision).

**Scope confirmation.** Every production file enumerated in tasks/15.md Context Files → Source files was read in full (no skim). Every test file enumerated under Test files was read in the parts relevant to security invariant lock-in. Repositories (`UserRepository`, `ProjectRepository`, `BotRepository`, `EventRepository`, `RawUpdateRepository`) reviewed — all are plain Spring Data interfaces with derived-finder methods, no `@Query` projections, no raw filter strings — nothing to audit beyond declaration shape.

---

## 2. Focus Area Verdicts

### Focus Area 1: CSRF wiring

**Verdict: PASS (with M3 noted on the handler choice).**

Evidence:
- `security/SecurityConfig.java:67` uses `CookieCsrfTokenRepository.withHttpOnlyFalse()` — correct for SPA double-submit cookie pattern.
- `security/SecurityConfig.java:84-87` AND-scopes the CSRF disable matcher to `CsrfFilter.DEFAULT_CSRF_MATCHER` (mutating verbs only) AND a `NegatedRequestMatcher(antMatcher("/webhooks/telegram/{projectId}"))`. Single-segment `{projectId}` prevents sub-path leak (matches `security M4` invariant). GET/HEAD/OPTIONS on non-webhook paths still flow through CSRF protection.
- `security/SecurityConfig.java:96` explicit `permitAll()` for the exact webhook path; no `/webhooks/**` widening.
- `CsrfCookieMaterializer` private filter at `security/SecurityConfig.java:119-129` is wired via `addFilterAfter(..., CsrfFilter.class)` — its sole responsibility is to call `csrfToken.getToken()` so `CookieCsrfTokenRepository` writes the XSRF-TOKEN cookie eagerly on every request. The Javadoc on this class is unusually candid about the tech-spec deviation (Task 15 said the eager cookie write would not need a helper filter; in practice the plain handler stores a `Supplier` and never invokes it).
- `WebhookSecurityBlockTest` (`postWebhookWithoutXsrfToken_doesNot403_scopedCsrfDisableActive` + `postApiAuthedWithoutXsrfToken_returns403_csrfBaselineStillActive`) locks the AND-scoping invariant against regression.
- `SecurityConfigTest.csrfCookie_writtenOnSafeVerbRequest` proves the XSRF-TOKEN cookie is materialised on a fresh anonymous GET (the SPA pre-fetch path).

**One deliberate deviation** — `SecurityConfig.java:75` registers `new CsrfTokenRequestAttributeHandler()` (plain), NOT `XorCsrfTokenRequestAttributeHandler`. Tech-spec Task 15 demands the XOR handler for BREACH mitigation. The code's inline Javadoc justifies the choice — XOR would mask the form-attribute and break the cookie/header round-trip because the cookie is written raw by `CookieCsrfTokenRepository`, and BREACH protection is unnecessary because tokens travel via cookie and are never rendered into a compressible JSON body. The reasoning is sound for the chosen architecture, but Task 15 description still names XOR. Flagged as **M3** so the spec-vs-code divergence is resolved explicitly rather than silently.

### Focus Area 2: Session fixation

**Verdict: PASS.**

Evidence:
- `auth/AuthService.openSession` at `auth/AuthService.java:608-611`:
  ```
  HttpSession session = httpRequest.getSession(true);
  httpRequest.changeSessionId();
  session.setMaxInactiveInterval((int) ttl.getSeconds());
  securityContextRepository.saveContext(context, httpRequest, httpResponse);
  ```
  `changeSessionId()` fires BEFORE `saveContext(...)`. The fresh JSESSIONID is minted on every successful login; the SecurityContext is then persisted against the new session id.
- The inline comment at `AuthService.java:596-607` documents the residual-attribute analysis explicitly: no current writer puts state into an anonymous session before login, so a session-id rotation without prior invalidate() is safe. If a future filter starts writing session-scoped attrs pre-auth, the comment instructs to switch to `existing.invalidate(); getSession(true);` before `changeSessionId`. This is the right level of diligence and the right marker for future maintainers.
- `BotConnectRaceIT.login()` exercises the post-login cookie state across two real Tomcat round-trips and observes the session rotation indirectly (`Step 3: merge Set-Cookie values from the login response (session-rotated post-login per Task 6 changeSessionId() defense)`). Not a dedicated session-fixation regression test, but it would surface a regression that left the anonymous session-id intact.

**Spec-internal divergence (Low — L3):** Tech-spec Architecture says `request.changeSessionId()`. Tech-spec Task 15 description says `HttpSession.invalidate() + request.getSession(true)`. Both achieve fixation defence. The code uses `request.changeSessionId()` (Architecture's wording). Documentation only; not a security regression — surfaced for spec hygiene.

### Focus Area 3: Timing-attack guard

**Verdict: PASS.**

Evidence:
- `auth/AuthService.DUMMY_HASH` at `auth/AuthService.java:52` — pre-computed real BCrypt cost-12 hash (`$2a$12$...`), NOT a sentinel. `passwordEncoder.matches(...)` will run the full ~250ms key-stretching schedule.
- `auth/AuthService.handleUserNotFound` at `auth/AuthService.java:513-524` is invoked from the `orElseGet(...)` branch of `findByEmail` at `auth/AuthService.java:127-128`. The dummy compare fires BEFORE any return / throw — the bcrypt cost is paid on the not-found branch before the 401 is thrown. Login-with-nonexistent-user and login-with-wrong-password are wall-clock indistinguishable.
- BCrypt cost is `12` at `security/SecurityConfig.java:133` (`new BCryptPasswordEncoder(12)`). No change from the pre-migration source.
- The forgot-password unknown-email branch at `auth/AuthService.java:301-313` adds a calibrated `Thread.sleep(FORGOT_DUMMY_DELAY)` (40ms) to equalise wall-clock against the known-user save path — separate from the login timing-attack defence, but worth noting that the timing-oracle defence pattern is applied consistently.

### Focus Area 4: Rate-limit fail-open semantics under VT

**Verdict: PASS.**

Evidence:
- `auth/AuthService.checkBruteForce` at `auth/AuthService.java:489-506`: try/catch around the `currentCount(...)` GETs; on any `Exception` it logs `WARN` and `return`s — fail-open. Threshold trip → 429 + audit event. No path converts a Redis outage into a 500.
- `auth/AuthService.incrementWithTtl` at `auth/AuthService.java:548-556`: `INCR` then conditional `EXPIRE` only when `count == 1L` — the known small crash race between INCR and EXPIRE is documented inline and accepted (Redis MEMORY-policy bounds it). `registerFailure` at `auth/AuthService.java:539-546` wraps both increments in a `try/catch` that logs WARN — fail-open.
- `auth/AuthService.resetBruteCounters` at `auth/AuthService.java:614-620`: `DEL` in try/catch with WARN-on-failure. Fail-open.
- `bot/BotService.incrementBruteForceCounter` at `bot/BotService.java:250-269`: same INCR+EXPIRE+threshold-check pattern; the AppException-rethrow path is preserved (a real `tooManyRequests` 429 propagates), but every `Exception` other than `AppException` is caught and WARN-logged via the pinned `REDIS_FAIL_OPEN_WARN` constant — fail-open. The constant being a pinned greppable string is a thoughtful operability touch.
- `profile/ProfileService.checkChangePwdRate` at `profile/ProfileService.java:149-164` mirrors the auth pattern: GET → AppException-rethrow + generic-exception catch that logs WARN. Fail-open. `registerChangePwdFailure` at `profile/ProfileService.java:166-176` + `resetChangePwdCounter` at `profile/ProfileService.java:178-184` follow the same wrap-and-WARN pattern.
- `auth/AuthService.checkRegisterRate` at `auth/AuthService.java:452-471`: same INCR+EXPIRE-on-first + WARN-on-failure pattern. The threshold check sits OUTSIDE the catch block so a Redis success that returns a count above threshold still throws 429; a Redis exception is the only path that returns without throwing.

**VT race analysis (focus area 4 paranoid check):**
- Lettuce 6.5.5.RELEASE sync API is documented thread-safe — the connection is multiplexed and the synchronous wrapper parks the VT on a `CompletableFuture` while the async transport keeps moving. Two VTs entering `INCR` concurrently each block on their own future; Redis serialises the INCRs server-side, so each VT sees its own monotonic count return value. There is no path where two VTs both see `count <= threshold` and both pass the gate — Redis itself owns the increment atomicity, and the carrier thread pinning Lettuce introduces (documented in patterns.md per Task 11) doesn't break the per-VT happens-before edge.
- No `boundedElastic()` ceremonies remain in any of the three rate-limit paths — confirmed by grep across the four files.

### Focus Area 5: Token scrubber preservation (TC10)

**Verdict: PASS (with L2 noted on missing per-site automation).**

Per-site walk of all 6 TC10 enumerated sites:

| Site | File:Line | scrubTokens call? | Per-site test coverage? |
|------|-----------|-------------------|--------------------------|
| `TelegramWebhookController` DuplicateKey WARN | `webhook/TelegramWebhookController.java:173-175` | YES — `scrubTokens(projectId)`, `scrubTokens(String.valueOf(updateId))` | YES — `TelegramWebhookControllerIT` (ListAppender on `TelegramWebhookController.class` logger). |
| `TelegramWebhookController` enqueue-failure ERROR | `webhook/TelegramWebhookController.java:207-209` | YES — `scrubTokens(rawUpdateId)`, `scrubTokens(ex.getMessage())` | YES — same `TelegramWebhookControllerIT` appender. |
| `WebhookPayloadSizeFilter` 413 WARN | `webhook/WebhookPayloadSizeFilter.java:92-97` | YES — `scrubTokens(projectId)`, `scrubTokens(String.join(...))` on transfer-encoding | YES — `WebhookPayloadSizeFilterTest` (ListAppender on the filter logger). |
| `ProcessTelegramUpdateJob` INFO start/success | `webhook/ProcessTelegramUpdateJob.java:96-97, 107-108` | **NO** — passes raw `rawUpdateId, rawUpdate.getProjectId()` (Mongo ObjectId hex, shape cannot match token regex) | Partial — `ProcessTelegramUpdateJobTest.workerException_writes...` asserts `jobAppender.list` has no token-shaped string across ALL captured events, which catches the negative case generically but no positive per-site test. |
| `ProcessTelegramUpdateJob` WARN/ERROR | `webhook/ProcessTelegramUpdateJob.java:84-85, 129-130, 211-212, 229-230` | line 84 YES (`scrubTokens(rawUpdateId)`); line 129 partial (logs `truncated` which IS scrubbed at `:116`, but raw `rawUpdateId`); line 211 NO (logs raw `projectId, chatId`); line 229 YES on chatId. | YES (line 129) via the appender-wide negative assertion. Lines 84, 211 not pinned per-site. |
| `TelegramSender` 4xx/5xx/429/timeout | `bot/TelegramSender.java:133-134, 154, 179-180, 264, 267, 277, 287` | YES — every payload-derived log arg goes through `scrubTokens(...)`; lines 154 + 264 + 267 + 277 + 287 use already-scrubbed locals or non-payload values. | YES — `TelegramSenderTest` (ListAppender on the sender logger). |

**Helper itself unchanged:** `TelegramApiClient.scrubTokens` at `bot/TelegramApiClient.java:216-219` — pattern `\d{1,20}:[A-Za-z0-9_-]{30,50}` replaced with `[REDACTED_TOKEN]`. Identical to the pre-migration helper (the file's `git log` shows only one functional change, which was the original Task 1 implementation; subsequent commits touch unrelated regions).

**Multi-line stack trace coverage:** `TelegramApiClient.isTransient` walks the cause chain up to 16 hops (`bot/TelegramApiClient.java:199-212`), and `ProcessTelegramUpdateJob.handleFailure` deliberately constructs the rethrown exception with NO cause chain (`webhook/ProcessTelegramUpdateJob.java:131-143`) — so JobRunr's `jobrunr_jobs` collection cannot re-leak the raw token via `t.getCause().getMessage()`. `ProcessTelegramUpdateJobTest.workerException_writesFailedStatus_scrubbedTruncated_incrementsFailureCounter_rethrows` at test line 446-492 asserts both the persisted `processingError` and the rethrown message regex-don't-contain a token. This is the right level of defence for the JobRunr leakage vector.

**L2 finding scope:** Per-site automation on the 6 enumerated TC10 sites is partial. Sites 1-3 + 6 are explicitly pinned; sites 4-5 (ProcessTelegramUpdateJob INFO + non-failure WARN) are covered only by the appender-wide negative assertion. The test-reviewer (Task 16) is the right place to either add per-site assertions or formally adopt the appender-wide assertion as TC10's coverage style — the security audit's role here is to flag the gap.

### Focus Area 6: `TokenEncryptor` unchanged

**Verdict: PASS.**

Evidence:
- `git log --oneline -- backend/src/main/java/com/botfunnel/common/crypto/TokenEncryptor.java` returns exactly two commits: `91b499b` (Task 1 — initial implementation) and `dfecdc0` (Task 1 — review round 1 fix). NO Wave 2/3 migration commit touched this file. Byte-identity is preserved.
- Algorithm: `AES/GCM/NoPadding` at `common/crypto/TokenEncryptor.java:20`, 32-byte key, 12-byte IV, 128-bit auth tag. Same parameters as pre-migration.
- Key handling: hex-decoded once in the constructor; bad hex throws `IllegalStateException` with the offending character STRIPPED from the message (`common/crypto/TokenEncryptor.java:69-75`) — the JDK default would echo the bad character + index, leaking a fragment of the misconfigured key. This is a small but meaningful hardening preserved across the migration.
- IV handling: fresh per-encrypt via `SecureRandom.nextBytes(iv)` at `common/crypto/TokenEncryptor.java:33-34`. Decrypt validates exact IV length (12 bytes) at `common/crypto/TokenEncryptor.java:46-50` — wrong-length IV throws `IllegalArgumentException` BEFORE the AES-GCM init runs. Caller boundary semantics (Base64 encode/decode) live in `BotService` per D15 — the encryptor itself is byte-only. Confirmed unchanged.

**Callers' contract:**
- `bot/BotService.connectAfterPreChecks` at `bot/BotService.java:154-156` encodes the IV + ciphertext to Base64 strings.
- `bot/BotService.doDisconnect` at `bot/BotService.java:225-227` decodes Base64 → calls `tokenEncryptor.decrypt(iv, ct)` → plaintext token in a local variable scoped to the method.
- `bot/TelegramSender.sendOnce` at `bot/TelegramSender.java:191-205` mirrors the same pattern: Base64-decode failure → wrapped in `BotTokenInvalidException` (422), AEAD failure / wrong IV length → bare propagation (becomes 500). The deliberate non-catching of AEAD-tag failures and wrong-IV-length errors is correct: those failure modes ops must see, and they can never be triggered by user input on a healthy database.

No algorithm, key derivation, IV handling, or AEAD-mode change. **Not a Critical finding.**

### Focus Area 7: `RememberMeCookieSerializer` doesn't leak token

**Verdict: PASS.**

Evidence:
- `security/RememberMeCookieSerializer.java:28` extends `DefaultCookieSerializer` and overrides only `writeCookieValue(CookieValue)` at `security/RememberMeCookieSerializer.java:44-53`. No override of cookie name, value, domain, comment, or any custom attribute.
- `REMEMBER_ME_ATTR` is read from the request attribute at `security/RememberMeCookieSerializer.java:47` and used ONLY to call `cookieValue.setCookieMaxAge((int) (rememberMeDays * 86400L))` when `Boolean.TRUE`. The remember-me boolean never enters the cookie body, path, domain, comment, or a sibling cookie. There is no `Set-Cookie: REMEMBER=1` emission anywhere — the only Set-Cookie is `SESSION=...` with the per-request Max-Age.
- HttpOnly / Secure / SameSite / Path / Domain / Name flow from `ServerProperties.getServlet().getSession().getCookie()` via `PropertyMapper.alwaysApplyingWhenNonNull()` (`security/RememberMeCookieSerializer.java:34-42`). The `alwaysApplyingWhenNonNull()` form copies a value through unconditionally so long as the source is non-null — exactly the silent-non-drop semantic Task 15 demanded.
- `AuthControllerIT.login_rememberMeTrue_setsAllSessionCookieAttributes_andCoEmitsXsrfToken` and `..._rememberMeFalse_omitsMaxAgeOnSessionCookie` (test lines 591-681) parse the raw `Set-Cookie` header and assert HttpOnly / Secure / SameSite match the configured values, AND that Max-Age is present (= `rememberMeDays*86400`) iff `rememberMe=true`. The defensive `rememberMeDays <= 60L` cap at test line 619-621 protects against an env misconfig pushing remember-me to ~1 year.
- `SecurityConfigTest.cookieSerializerBean_isRememberMeCookieSerializer_notDefault` proves the @Bean override wins — a regression that drops the `cookieSerializer` @Bean would let Spring Session's `@ConditionalOnMissingBean` default silently disable the per-request Max-Age path. This is the right TC5 lock-in.

The cookie serializer is a clean override; no token leakage path exists.

---

## 3. OWASP Top 10 (2021) Sweep

- A01: Broken Access Control — PASS. `ProjectService.requireOwned` (`project/ProjectService.java:50-66`) collapses foreign-owned / soft-deleted / bad-ObjectId all to identical 404. `BotService.requireConnectedBot` chains through it. Webhook authz is by `webhookSecretVerifier.verify(...)` (constant-time, see Webhook Authorisation Invariants below). Controllers' `currentUserId()` helpers (`AuthController`, `BotController`, `ProfileController`, `ProjectController`) all funnel through `SecurityContextHolder.getContext().getAuthentication()` + `AppUserDetails` instanceof check → 401 on any drift.
- A02: Cryptographic Failures — PASS. AES-GCM-256 with per-call random IV and 128-bit auth tag (`TokenEncryptor`); bcrypt cost-12 for password hashing (`SecurityConfig:133`); session id rotation on login (focus area 2); `MessageDigest.isEqual` for webhook secret compare (`WebhookSecretVerifier:38` — constant-time). One weak default key for `BOT_TOKEN_ENCRYPTION_KEY` shipped in `application.properties:47` (all-zero) — Medium (M2).
- A03: Injection — PASS. All MongoDB queries use Spring Data derived finders or `MongoTemplate.Query.query(Criteria.where(...))` — no string concatenation, no `$where`. No raw SQL anywhere. Email templates HTML-escape every templated value at `email/EmailService.java:111-119`. Webhook payload is parsed as a typed `Document` and projected to `TelegramUpdate` via Jackson; the only string→Long parse is `extractUpdateId` (`webhook/TelegramWebhookController.java:214-221`) which uses `Long.valueOf(raw.toString())` — fails fast on malformed input.
- A04: Insecure Design — PASS. Fail-open Redis rate-limiting is a documented design decision (Decision 4) with explicit operability via WARN-level log lines pinned as constants (`REDIS_FAIL_OPEN_WARN`). Webhook idempotency uses first-writer-wins CAS on a unique compound index + deterministic JobRunr UUID — eliminates the duplicate-event class entirely. Tenancy guard funnels through one method so future modules cannot accidentally skip the check.
- A05: Security Misconfiguration — PASS WITH M2. CORS limited to `${app.url}` only (`security/SecurityConfig.java:139`). Session cookie HttpOnly default `true` (`application.properties:17`), Secure default `false` (intended for local dev — production overrides via `SESSION_COOKIE_SECURE=true`), SameSite default `lax`. JobRunr dashboard disabled. The development-only `BOT_TOKEN_ENCRYPTION_KEY` and `SUPER_ADMIN_PASSWORD` defaults in `application.properties` are M2 (Medium).
- A06: Vulnerable Components — PASS. Boot 3.5.0 transitives reviewed: Tomcat 10.1.41 (current within 3.5.0 line), Lettuce 6.5.5.RELEASE, MongoDB driver-sync 5.4.0, Hibernate Validator 8.0.2.Final, Logback 1.5.18, Netty 4.1.121.Final, Jackson 2.19.0, snakeyaml 2.4, Spring Framework 6.2.7, Spring Security 6.5.0. No publicly disclosed Critical/High CVEs against these specific versions as of the audit date. Recommend tracking Boot patch releases (3.5.x stream) for the deployment story.
- A07: Identification and Authentication Failures — PASS. BCrypt cost-12, brute-force counters on email + IP (with fail-open), session-fixation defence, session id rotation, anti-enumeration on register / forgot-password / reset-password / login / verify-email (identical messages + status across known/unknown branches), reset-password terminates all sessions (`AuthService.terminateAllSessions`), change-password terminates all but current (`ProfileService.terminateAllSessionsExcept`). No MFA — that's a product gap not a regression.
- A08: Software and Data Integrity — PASS. Webhook payload validated by typed parse + structural checks before any side effect. JobRunr UUIDs are deterministic (`UUID.nameUUIDFromBytes(rawUpdateId.getBytes(UTF_8))`) — duplicate enqueue is a no-op. Cascade ordering in `ProcessTelegramUpdateJob.handle` (events committed BEFORE rawUpdate flips to DONE — `webhook/ProcessTelegramUpdateJob.java:104-105`) means a mid-flight worker crash leaves NO DONE row without its audit event. No insecure deserialization paths (no `ObjectInputStream`, no YAML.load on untrusted input).
- A09: Security Logging and Monitoring Failures — PASS. Comprehensive audit events on login (success/failed/blocked/deleted/brute_force), email verification, password reset request / change, bot connect / disconnect / test-message / send-failed, project create / update / rename / soft-delete / restore / hard-delete, account deletion. The audit-event-write happens-before invariants are documented at multiple sites (`AuthService.rotatePasswordAndTerminateSessions:362-370`, `ProcessTelegramUpdateJob.handle`'s cascade order). `EmailService.sendAsync` at `email/EmailService.java:82-96` silently swallows SMTP errors but logs the failure at ERROR with the full stack trace (`log.error("Email send failed to {}: {}", to, e.toString(), e)`) — silent-fire-and-forget contract preserved (D15), and the SMTP outage is observable. No swallowed exception in the imperative rewrites of the former Reactor chains (audited in §4 Imperative Rewrite Control-Flow).
- A10: Server-Side Request Forgery — PASS. The only outbound HTTP calls are to Telegram Bot API via `TelegramApiClient` + `TelegramSender`, both built on a `RestClient` whose `DefaultUriBuilderFactory` is pinned to `${app.telegram.base-url}` (an operator-controlled property). User-supplied input feeds only the path variable `{token}` (regex-validated by `requireValidTokenShape` at `bot/TelegramApiClient.java:121-125`) and the body (typed DTO). `setWebhook(token, url, secret)` is called only from `BotService.connectAfterPreChecks` with `url = appUrl + "/webhooks/telegram/" + projectId` — the URL is platform-derived, never user-derived. The Javadoc warning at `TelegramApiClient.java:86-90` ("Caller is responsible for ensuring `url` is platform-controlled — never forward a user-supplied URL") is a good safety marker for future maintainers.

---

## 4. Findings

### Medium

Medium: HttpRequestUtils.extractIp trusts X-Forwarded-For without proxy allowlist.
- Location: `backend/src/main/java/com/botfunnel/common/HttpRequestUtils.java:33`
- OWASP: A07 (Authentication failures — brute-force counter evasion)
- CWE: CWE-348 (Use of Less Trusted Source)
- Attack vector: Without a known-proxy allowlist, any client can send `X-Forwarded-For: 10.0.0.42` and the helper returns that as the client IP. The brute-force counter keyed on `bruteIpKey(ip)` then increments a useless attacker-controlled bucket. The attacker can grind logins indefinitely by rotating the spoofed header. Same vector against `forgotKey(ip)`, `registerIpKey(ip)`, and `BotService.incrementBruteForceCounter(userId)` (this last one is keyed on userId not IP, so it's unaffected — but the email and register flows are vulnerable).
- Impact: Per-IP rate limit becomes per-spoofed-header rate limit; brute-force defence collapses to the per-email and per-userId counters only. Password-spraying across multiple emails becomes unbounded.
- Remediation: Either (a) deploy the application behind a reverse proxy (nginx/traefik) that overwrites `X-Forwarded-For` with the real client IP — the inline Javadoc at `HttpRequestUtils.java:25-27` already states this is the production contract; OR (b) parse `X-Forwarded-For` only when the request originates from a known proxy IP. Option (b) requires a `app.trusted-proxies` config + an IP allowlist check before the header read. Pre-migration semantics are byte-identical so this is not a regression — it's a pre-existing weakness preserved through the migration touch surface. Track as a deployment-story follow-up.

Medium: Hardcoded weak defaults for BOT_TOKEN_ENCRYPTION_KEY and SUPER_ADMIN_PASSWORD.
- Location: `backend/src/main/resources/application.properties:41,47`
- OWASP: A02 (Cryptographic Failures), A05 (Security Misconfiguration)
- CWE: CWE-798 (Use of Hard-coded Credentials), CWE-1188 (Insecure Default Initialization)
- Attack vector: `BOT_TOKEN_ENCRYPTION_KEY` defaults to `0000...0000` (32 zero bytes hex-encoded → 64 zero chars). If the env var is unset in production, the encryption-at-rest key for bot tokens becomes a publicly-known constant — any database leak / replica snapshot lets an attacker decrypt every persisted bot token. `SUPER_ADMIN_PASSWORD` defaults to `"12345678"`. If unset, `SuperAdminSeeder.seed` creates `admin@admin.com` with that password on first boot — a Telegram-bot-platform compromise of "well-known default admin creds" is the textbook A05 finding.
- Impact: Trivial admin takeover OR trivial decryption of every persisted bot token if either env var is forgotten during deployment.
- Remediation: Replace both defaults with empty strings. `TokenEncryptor` already throws `IllegalStateException("blank value")` on empty input (`TokenEncryptor.java:62-64`) — the application will refuse to boot, which is the correct fail-secure stance for a missing encryption key. `SuperAdminSeeder.seed` already short-circuits on blank password (`SuperAdminSeeder.java:50-53`) and logs `WARN`. Both fail-secure paths are already in place; only the default values in `application.properties` need to flip from `12345678` / `0000...0000` to empty. Add a deployment-checklist item to surface the required env vars.

Medium: CSRF uses plain CsrfTokenRequestAttributeHandler; Task 15 demands XorCsrfTokenRequestAttributeHandler.
- Location: `backend/src/main/java/com/botfunnel/security/SecurityConfig.java:75`
- OWASP: A05 (Security Misconfiguration — spec-vs-code divergence)
- CWE: N/A (not a vulnerability in the chosen architecture, but a spec deviation that should be resolved explicitly)
- Attack vector: BREACH attacks against compressed responses containing the CSRF token. The plain handler echoes the same token in both cookie and request — if the token is ever rendered into a compressible JSON body and TLS compression is enabled (HTTPS gzip/br), a BREACH-style oracle can recover the token. The XOR handler masks the form-attribute per-request to break this oracle.
- Impact: The architecture as-built avoids BREACH by never rendering the CSRF token into a response body — tokens travel ONLY via cookie. The threat is not realisable in the current code shape, and the inline Javadoc at `SecurityConfig.java:69-74` justifies the choice correctly. The Medium severity is for the spec-vs-code divergence (Task 15 description says XOR; code uses plain), not the BREACH risk itself.
- Remediation: Two valid options. (a) Update the tech-spec Task 15 description to reflect the chosen architecture and document the no-BREACH-because-no-body-render argument. (b) Switch to `XorCsrfTokenRequestAttributeHandler` AND adopt the official Spring Security 6.x SPA pattern (`handler.setCsrfRequestAttributeName(null)` so the cookie/header round-trip uses the raw token while server-side form binding uses the masked one). Option (a) is lower risk for this migration; option (b) is a defence-in-depth upgrade for a future task. Either way, resolve the divergence explicitly so the spec and code agree.

### Low

Low: TC11 (XSRF-TOKEN co-emission on successful login response) not asserted in test suite.
- Location: `backend/src/test/java/com/botfunnel/auth/AuthControllerIT.java:603-615` (documented limitation)
- OWASP: A07 (testing gap)
- Attack vector: Not a runtime vulnerability — a testing gap. The TC11 invariant is that a logged-in user receives BOTH `SESSION=...` and `XSRF-TOKEN=...` cookies on the SAME response after login, so the SPA can immediately make a CSRF-protected POST without an extra GET round-trip. The test class documents (lines 603-615) that this is structurally unachievable in MockMvc: with `.with(csrf())` the test post-processor substitutes a `TestCsrfTokenRepository`; without it the POST returns 403. The XSRF cookie write only fires when `loadToken` returns null, and once a valid XSRF-TOKEN cookie is in the request, the post-flip flow skips the re-emit.
- Impact: A regression that deletes the `CsrfCookieMaterializer` filter (`security/SecurityConfig.java:119-129`) would silently break the SPA's first-POST-after-login flow. Currently only `SecurityConfigTest.csrfCookie_writtenOnSafeVerbRequest` covers the cookie-write path (fresh anonymous GET). The full TC11 invariant is not pinned at the IT level.
- Remediation: Add a real-Tomcat IT (or extend `BotConnectRaceIT.login`) that issues a real round-trip: (a) fresh GET to /health → cookie store the XSRF-TOKEN; (b) POST /api/auth/login with the cookie + X-XSRF-TOKEN header; (c) assert the login response carries BOTH `SESSION=...` and `XSRF-TOKEN=...` Set-Cookie headers. `BotConnectRaceIT.login` already does (a) and (b) — adding the (c) assertion is a small extension. Track in Task 16 (test audit) follow-ups.

Low: Partial per-site token-scrubber automation on TC10.
- Location: `backend/src/test/java/com/botfunnel/webhook/ProcessTelegramUpdateJobTest.java:487-489`
- OWASP: A09 (testing gap)
- Attack vector: TC10 specifies per-site coverage on the 6 enumerated token-scrubber sites. The current test coverage uses an appender-wide negative assertion (`jobAppender.list ... allSatisfy(e -> token_regex_does_not_match)`) — this catches the negative case generically but does not lock in that EACH of the 4 ProcessTelegramUpdateJob log sites (lines 84, 96, 107, 129, 211, 229) actually has a scrubber on its arguments. A future change that drops the scrubber on line 84 (where it IS currently called) but leaves the other lines' scrubbers in place would not be caught by the appender-wide assertion unless the test data deliberately injects a token-shaped string at THAT site.
- Impact: TC10 acceptance is not fully automated. A scrubber regression on a specific site could survive the test suite if the test fixtures don't carry a token-shaped string into that site's log args.
- Remediation: Add per-site positive assertions: for each of the 6 sites, drive the production code through the specific exception/branch that hits that log line, with a token-shaped string in the failing payload, and assert the captured log event's formatted message contains `[REDACTED_TOKEN]` (or at minimum does not contain the raw token). Track in Task 16 (test audit) follow-ups.

Low: Spec-internal divergence between Architecture and Task-15 description on session-fixation API.
- Location: `work/migrate-to-virtual-threads/tech-spec.md` (Architecture section vs Task 15 description)
- OWASP: N/A (documentation)
- Attack vector: None — both `request.changeSessionId()` and `HttpSession.invalidate() + request.getSession(true)` achieve session-fixation defence. The code uses the Architecture's wording (`changeSessionId()`). Task 15 description names the other API.
- Impact: Confuses downstream maintainers reading the spec.
- Remediation: Update tech-spec Task 15 description to match the Architecture wording (the actual implementation). Pure spec hygiene.

Low: JobRunrMongoConfig Javadoc references a reactive primary driver that no longer exists.
- Location: `backend/src/main/java/com/botfunnel/jobs/JobRunrMongoConfig.java:15-17`
- OWASP: N/A (stale docstring)
- Attack vector: None. The Javadoc says "The application's primary MongoDB integration is reactive (`spring-boot-starter-data-mongodb-reactive`)" — after the Wave 2 flip the primary is the sync starter (`spring-boot-starter-data-mongodb`), so the rationale for a separate `MongoClient` bean is now "JobRunr needs a `com.mongodb.client.MongoClient` and so does the rest of the app — but JobRunr's autoconfiguration wants its own bean lifecycle". The bean is still wired correctly (same URI, same `STANDARD` uuid representation). Only the docstring is stale.
- Impact: Misleading documentation. Could mislead a future audit into thinking two distinct connection pools / credential sets are in play (they are not — both bind to `${spring.data.mongodb.uri}`).
- Remediation: Update the Javadoc to reflect the post-flip reality.

Low: 3 ProcessTelegramUpdateJob log sites pass unscrubbed string arguments.
- Location: `backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java:96-97, 107-108, 211-212`
- OWASP: A09 (defensive scrubber missing)
- Attack vector: None realisable. The arguments are Mongo ObjectId hex strings (24 chars, no colon) and `Long` (chat id) — the scrubber regex `\d{1,20}:[A-Za-z0-9_-]{30,50}` requires a colon and a 30-50-char suffix, which neither shape can produce. So a token cannot actually leak through these sites today.
- Impact: Defence-in-depth. If a future refactor changes what these sites log (e.g. inlines the raw payload, or starts logging an exception message), the missing scrubber becomes a real leak vector overnight.
- Remediation: Wrap every payload-derived argument in `TelegramApiClient.scrubTokens(...)` even when the current arg shape cannot match the token regex. The scrubber is a constant-time regex; the overhead is negligible. Treat the scrubber as a typing constraint, not a runtime check.

---

## 5. Test Coverage Gaps

Security-specific gaps where automation does not lock an invariant the audit verified by reading code. Cross-reference with Task 16 (test audit):

1. **TC11 XSRF-TOKEN co-emission** — see L1 above. Real-Tomcat IT extension is the right shape. Currently the only coverage is `SecurityConfigTest.csrfCookie_writtenOnSafeVerbRequest` (anonymous GET only) and the documented limitation in `AuthControllerIT`.

2. **Per-site TC10 automation** — see L2 above. Appender-wide negative assertion is good defence-in-depth but does not pin each of the 6 sites independently.

3. **Session-fixation regression test** — focus area 2 verified the code calls `changeSessionId()` before `saveContext(...)`. No dedicated test pins the invariant "JSESSIONID before login != JSESSIONID after login". `BotConnectRaceIT.login` exercises the post-login cookie state but does not compare pre- vs post-login session ids. Worth a small `AuthControllerIT` test that captures the SESSION cookie value on a pre-login GET, executes the login, captures the SESSION cookie on the login response, and asserts inequality.

4. **Brute-force counter fail-open** — focus area 4 verified the production code logs WARN and continues on Redis exceptions. No test injects a Redis outage (`StringRedisTemplate` mock that throws) to lock the fail-open behaviour. A unit test on `AuthService.checkBruteForce` / `BotService.incrementBruteForceCounter` / `ProfileService.checkChangePwdRate` with a throwing Redis mock + an assertion that the request still succeeds would close this gap.

5. **VT race on the rate-limit gate** — no dedicated VT-race test exists for the rate-limit primitives. `BotConnectRaceIT` covers the bot-connect race but not the brute-force counter race. Lettuce sync API is documented thread-safe, but a paranoid `parallelInvoke(20, () -> POST /api/auth/login with bad password)` then `expect_(count_of_429s) > 0` test would lock the invariant.

6. **`HttpRequestUtils.extractIp` spoofing** — no test pins the "X-Forwarded-For = `10.0.0.42` is returned as-is" current semantic. If a future refactor adds a proxy allowlist (the M1 remediation), having an explicit test of the pre-refactor semantic helps avoid silent behavioural drift.

---

## 6. Cleared Risks

Per the audit-quality discipline rule: every focus area + cross-cutting check that came out clean must cite the file:line the auditor actually read.

**Focus areas:**
1. **CSRF wiring** — read `security/SecurityConfig.java:64-104` (filter chain), `:107-129` (materialiser); test lock-in `security/SecurityConfigTest.java:35-75`, `webhook/WebhookSecurityBlockTest.java:14-73`. CLEAR (with M3 noted on the handler choice).
2. **Session fixation** — read `auth/AuthService.java:581-612`; the ordering `getSession(true)` → `changeSessionId()` → `setMaxInactiveInterval` → `saveContext` is correct. CLEAR.
3. **Timing-attack guard** — read `auth/AuthService.java:52, 127-128, 513-524`, `security/SecurityConfig.java:131-134`; BCrypt cost 12 preserved, dummy hash is real, no early return predates the dummy compare. CLEAR.
4. **Rate-limit fail-open under VT** — read `auth/AuthService.java:452-471, 489-506, 539-556, 614-620`; `bot/BotService.java:250-278`; `profile/ProfileService.java:149-184`. Every Redis path is wrapped in `try { ... } catch (Exception) { log.warn(...) }` with fail-open semantics. CLEAR.
5. **Token scrubber preservation** — read all 6 enumerated TC10 sites + the helper at `bot/TelegramApiClient.java:216-219` + the no-cause rethrow at `webhook/ProcessTelegramUpdateJob.java:131-143` + the regex test at `webhook/ProcessTelegramUpdateJobTest.java:487-489`. CLEAR (with L2 noted on partial per-site automation).
6. **TokenEncryptor unchanged** — `git log --oneline -- backend/src/main/java/com/botfunnel/common/crypto/TokenEncryptor.java` returned 2 commits, both pre-migration. File at `common/crypto/TokenEncryptor.java:17-87` is byte-identical to the Task 1 review-round-1 commit `dfecdc0`. CLEAR.
7. **RememberMeCookieSerializer no token leak** — read `security/RememberMeCookieSerializer.java:28-54`; only override is `writeCookieValue`, only attribute read is `REMEMBER_ME_ATTR`, only mutation is `setCookieMaxAge`. Test lock-in `auth/AuthControllerIT.java:591-681` + `security/SecurityConfigTest.java:25-32`. CLEAR.

**Cross-cutting checks:**
- **XSRF-TOKEN co-emission (TC11)** — read `security/SecurityConfig.java:64-104` (CsrfFilter + materialiser wiring) + `auth/AuthController.java:35-39` (login endpoint) + `auth/AuthControllerIT.java:603-615` (documented test limitation). PASS at the production code level; test coverage gap captured as L1.
- **Webhook authorisation invariants** — read `webhook/TelegramWebhookController.java:105-195` (401-vs-404 carve-out at lines 123-143 preserved), `webhook/WebhookPayloadSizeFilter.java:60-104` (chunked + missing-CL rejection at lines 70-101), `webhook/TelegramWebhookController.java:41` (`@RequestMapping("/webhooks/telegram")`) + `:105` (`@PostMapping("/{projectId}")` — single segment, no `..` traversal). CLEAR.
- **Tenancy guard** — read `project/ProjectService.java:50-66`. Three carve-outs (bad ObjectId at :54-57, missing project at :58, foreign owner at :59-61, soft-deleted at :62-64) all collapse to `AppException.notFound(MESSAGE_NOT_FOUND)`. Uniform 404. CLEAR.
- **VT brute-force race** — read `bot/BotConnectRaceIT.java:158-240`. Asserts `{200, 409}` status pair, exactly-one CONNECTED row, `setWebhook` count in `[1, 2]`, `deleteWebhook` count == `setWebhook - 1`. Real Tomcat transport via `TestRestTemplate` + `ConcurrencyTestUtils.parallelInvoke(2, ...)`. CLEAR.
- **Webhook idempotency under VT** — read `webhook/TelegramWebhookController.java:155-181` (CAS via DuplicateKeyException) + `:197-212` (deterministic UUID at line 198: `UUID.nameUUIDFromBytes(rawUpdateId.getBytes(StandardCharsets.UTF_8))`) + the DuplicateKey self-heal at `:167-181` (return 200 + scrubbed WARN). CLEAR.
- **Validation-error body shape (TC6/AC17)** — read `common/GlobalErrorHandler.java:26-35`. Both `getFieldErrors().stream().map(e -> e.getField() + ": " + e.getDefaultMessage())` and `getGlobalErrors()` joined with `", "`; response body is `new ErrorResponse(message, null)` — code is explicitly null. Test lock-in `auth/AuthControllerIT.java:565-587` (jsonPath `$.code` == null + regex match on the joined format). CLEAR.
- **IP/UA trust model** — read `common/HttpRequestUtils.java:32-41`. Trusts XFF unconditionally — pre-existing semantic preserved through the migration. Captured as M1 above.
- **Imperative rewrite control-flow** — read `webhook/TelegramWebhookController.java:105-195` (former `flatMap`/`switchIfEmpty`/`onErrorResume`), `bot/TelegramSender.java:112-189` (former stacked `.retryWhen`), `bot/TelegramApiClient.java:127-160` (former `Retry.backoff`). No exception swallowing introduced; audit event emission order preserved (success path emits AFTER all side effects, failure path emits ONLY for terminal-auditable errors at `bot/TelegramSender.java:131-139`); deadlines intact (`bot/TelegramSender.java:118-119, 302-324`); 429-wraps-5xx ordering correct (`bot/TelegramSender.java:144-159` outer 429 loop wraps `:161-189` inner 5xx loop). CLEAR.
- **Dependency CVEs** — read `backend/build.gradle:21-40` + `./gradlew dependencies --configuration runtimeClasspath` output. Boot 3.5.0 transitives all current. CLEAR.

**Additional cross-cutting items from task description / Details / edge cases:**
- **Two MongoClient beans (D11)** — read `jobs/JobRunrMongoConfig.java:30-40`. Both clients bind to `${spring.data.mongodb.uri}` — same DB, same credentials, no misbinding risk. Stale Javadoc at `:15-17` captured as L4 above.
- **Pre-flip BSON fixture continuity (R5)** — `AuthControllerIT.sessionsCollection_principalFieldPath_isAtTopLevel` at `auth/AuthControllerIT.java:760-776` verifies the live spring-session-data-mongodb schema actually stores `principal` at the top-level field that `AuthService.terminateAllSessions` queries on. Round-trip via a real login, not a write-then-read inside the same repository. CLEAR.
- **EmailService try/catch swallowing SMTP errors (D15)** — read `email/EmailService.java:82-96`. `log.error("Email send failed to {}: {}", to, e.toString(), e)` — full stack trace appended (trailing `e` argument). SMTP outage is observable via logs. CLEAR.
- **Removed `csrfCookieMaterializer` WebFilter** — read `security/SecurityConfig.java:102` (`addFilterAfter(new CsrfCookieMaterializer(), CsrfFilter.class)`); the inner `CsrfCookieMaterializer` at `:119-129` is the MVC replacement. No controller anywhere expects the XSRF-TOKEN on the REQUEST side (grep `request.getAttribute(CsrfToken` across the codebase → 1 match, inside the materialiser itself). CLEAR.
- **scrubTokens on multi-line stack traces** — read `bot/TelegramSender.java:133-134` (`scrubTokens(ex.getMessage())` — message only; the stack trace itself is written by SLF4J via the throwable parameter, but TelegramApiClient's `isTransient` walks the cause chain). `ProcessTelegramUpdateJob.handleFailure` at `webhook/ProcessTelegramUpdateJob.java:131-143` constructs the rethrown exception with NO cause chain — JobRunr's `jobrunr_jobs` row cannot re-leak a token through `t.getCause().getMessage()`. Test `ProcessTelegramUpdateJobTest.workerException...:466-475` explicitly pins both invariants. CLEAR.
- **`AppUserDetails` principal-type contract** — read `auth/AuthService.me:144-148`, `project/ProjectController.currentUserId:125-131`, `bot/BotController.currentUserId:78-84`, `profile/ProfileController.currentUserId:68-74`. Every controller-side principal extraction is gated on `instanceof AppUserDetails` — drift to a different principal type fails closed with `Not authenticated`. CLEAR.
- **Hardcoded secrets sweep** — read every source file in scope. Only hits: `AuthService.DUMMY_HASH` (intentional, a public BCrypt hash that is meant to be a public constant); `application.properties` defaults (captured as M2 above). No secrets in source files, no hardcoded API keys, no embedded private keys, no embedded connection strings with passwords. CLEAR.

---

## Appendix A: Files audited (read in full)

Source files:
- `backend/build.gradle`
- `backend/src/main/resources/application.properties`
- `backend/src/main/java/com/botfunnel/HealthController.java`
- `backend/src/main/java/com/botfunnel/admin/SuperAdminSeeder.java`
- `backend/src/main/java/com/botfunnel/auth/AuthController.java`
- `backend/src/main/java/com/botfunnel/auth/AuthService.java`
- `backend/src/main/java/com/botfunnel/bot/BotController.java`
- `backend/src/main/java/com/botfunnel/bot/BotRepository.java`
- `backend/src/main/java/com/botfunnel/bot/BotService.java`
- `backend/src/main/java/com/botfunnel/bot/TelegramApiClient.java`
- `backend/src/main/java/com/botfunnel/bot/TelegramSender.java`
- `backend/src/main/java/com/botfunnel/common/GlobalErrorHandler.java`
- `backend/src/main/java/com/botfunnel/common/HttpRequestUtils.java`
- `backend/src/main/java/com/botfunnel/common/SessionAttributes.java`
- `backend/src/main/java/com/botfunnel/common/crypto/TokenEncryptor.java`
- `backend/src/main/java/com/botfunnel/email/EmailService.java`
- `backend/src/main/java/com/botfunnel/events/EventRepository.java`
- `backend/src/main/java/com/botfunnel/events/EventService.java`
- `backend/src/main/java/com/botfunnel/funnel/FunnelTriggerService.java`
- `backend/src/main/java/com/botfunnel/funnel/NoOpFunnelTriggerService.java`
- `backend/src/main/java/com/botfunnel/jobs/HardDeleteJob.java`
- `backend/src/main/java/com/botfunnel/jobs/JobRunrMongoConfig.java`
- `backend/src/main/java/com/botfunnel/jobs/ProjectHardDeleteJob.java`
- `backend/src/main/java/com/botfunnel/profile/ProfileController.java`
- `backend/src/main/java/com/botfunnel/profile/ProfileService.java`
- `backend/src/main/java/com/botfunnel/project/ProjectController.java`
- `backend/src/main/java/com/botfunnel/project/ProjectRepository.java`
- `backend/src/main/java/com/botfunnel/project/ProjectService.java`
- `backend/src/main/java/com/botfunnel/security/RememberMeCookieSerializer.java`
- `backend/src/main/java/com/botfunnel/security/SecurityConfig.java`
- `backend/src/main/java/com/botfunnel/subscriber/NoOpSubscriberService.java`
- `backend/src/main/java/com/botfunnel/subscriber/SubscriberService.java`
- `backend/src/main/java/com/botfunnel/user/UserRepository.java`
- `backend/src/main/java/com/botfunnel/user/UserService.java`
- `backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java`
- `backend/src/main/java/com/botfunnel/webhook/RawUpdateRepository.java`
- `backend/src/main/java/com/botfunnel/webhook/TelegramWebhookController.java`
- `backend/src/main/java/com/botfunnel/webhook/WebhookPayloadSizeFilter.java`
- `backend/src/main/java/com/botfunnel/webhook/WebhookSecretVerifier.java`

Test files (security invariant lock-in — read for relevant sections):
- `backend/src/test/java/com/botfunnel/auth/AuthControllerIT.java`
- `backend/src/test/java/com/botfunnel/bot/BotConnectRaceIT.java`
- `backend/src/test/java/com/botfunnel/bot/BotTokenLeakTest.java`
- `backend/src/test/java/com/botfunnel/profile/ProfileControllerIT.java`
- `backend/src/test/java/com/botfunnel/security/SecurityConfigTest.java`
- `backend/src/test/java/com/botfunnel/webhook/TelegramWebhookControllerIT.java`
- `backend/src/test/java/com/botfunnel/webhook/WebhookSecurityBlockTest.java`
- `backend/src/test/java/com/botfunnel/webhook/ProcessTelegramUpdateJobTest.java` (partial — scrubber assertions)

---

*End of audit.*
