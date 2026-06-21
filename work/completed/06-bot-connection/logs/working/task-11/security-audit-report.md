# Security Audit Report — Epic 06 Bot Connection (Task 11)

**Scope:** All production and test sources created/modified by Tasks 1–9 of the
`06-bot-connection` epic, plus the inherited security perimeter
(`SecurityConfig`, `GlobalErrorHandler`, `AppException`, `ProjectService`,
`AuthService`, `EventService`).

**Auditor:** security-auditor agent (Task 11, Audit Wave).
**Mode:** Read-only. No production code modified.
**Methodology:** OWASP Top 10 (2021) walkthrough + the nine focus areas
mandated by `tasks/11.md` + cross-check of the user-spec Risk register
R1–R10 against the as-built code.
**Note on prior reviews:** Tasks 1–9 each ran through a per-task
`security-auditor` round-1/round-2 review (see `decisions.md`). Findings
already applied are NOT re-flagged. This audit is cross-cutting and
deliberately looks for what slice-level reviewers could miss.

---

## Headline Verdict

- **Counts:** 0 Critical, 0 High, 0 Medium, 3 Low, 4 Info.
- **Deploy gate:** No Critical → not blocked. No High → no mandatory
  pre-deploy fixes.
- **Recommendation:** `Deploy recommendation: GO` (see final section for the
  formal line).

---

## OWASP Top 10 (2021) Walkthrough

One line per category, one-line verdict.

- **A01 Broken Access Control** — Applies. `ProjectService.requireOwned` is the
  first reactive step on every endpoint (`BotService.connect:92`,
  `BotService.requireConnectedBot:119`). Anti-enumeration 404 inherited.
  Hostile body fields rejected by `@JsonIgnoreProperties(ignoreUnknown=true)`
  on `ConnectBotRequest:11`. No findings.
- **A02 Cryptographic Failures** — Applies. AES-256-GCM with 12-byte
  `SecureRandom` IV per call, 128-bit auth tag, single-key model documented
  (D3), webhook secret stored as hex SHA-256 (D2), fail-fast key validation
  (`TokenEncryptor.decodeKey:61`). No findings — see Focus Area #1, #2.
- **A03 Injection** — Applies. No SQL surface. Mongo queries use derived
  repository methods (no string concatenation). Telegram URL components are
  URL-template parameters via `WebClient.uri(..., token)`; URI builder factory
  is configured `EncodingMode.NONE` (`TelegramApiClient:55-56`) which is safe
  because the token has already passed `requireValidTokenShape` (regex match
  excludes path-injection metacharacters). Webhook URL is constructed from
  `app.url` + `projectId` (`BotService.connectAfterPreChecks:129`). See
  Info-1 for the residual `projectId` consideration. No findings of
  exploitable injection.
- **A04 Insecure Design** — Applies. Defense-in-depth uniqueness (service
  pre-check + partial unique index, D1/D5) closes the cross-project race
  identity hijack. Compensating `deleteWebhook` on persist failure (D4) closes
  the orphan-webhook attack surface (R6). No findings.
- **A05 Security Misconfiguration** — Applies. `application.properties:44`
  reads `BOT_TOKEN_ENCRYPTION_KEY` with an empty default — fail-fast at
  startup forces operators to set a real key. `.env.example` documents
  `openssl rand -hex 32` and "NEVER commit a real value". No actual finding,
  but see Low-1 (empty-default UX consideration).
- **A06 Vulnerable Components** — Applies. `mockwebserver:4.12.0` declared
  `testImplementation` only (`backend/build.gradle:38`) — not shipped in
  production jar. JDK `javax.crypto` and `MessageDigest` only — no new crypto
  libraries. Frontend adds `reka-ui`, `@vueuse/core`, `tailwindcss-animate`
  via shadcn-vue CLI (see Info-2). No known-CVE component. No findings.
- **A07 Identification & Authentication Failures** — Applies (auth inherited
  from existing `SecurityConfig`). Per-user brute-force counter
  `brute:bot-connect:{userId}` (D14) with INCR-every-attempt and DEL-on-success
  closes the Connect-then-Disconnect bypass. Fail-open by design with a pinned
  WARN line (`BotService.REDIS_FAIL_OPEN_WARN`). The four bot endpoints sit
  under `/api/**` and require an authenticated session
  (`SecurityConfig:71`). No findings — see Focus Area #5.
- **A08 Software & Data Integrity Failures** — Applies. Request DTO uses
  Jackson with `@JsonIgnoreProperties(ignoreUnknown=true)` (no mass
  assignment). No unsigned deserialisation. `TelegramResult` is plain JSON
  parsed via Jackson with explicit record components — no polymorphic typing,
  no `@JsonTypeInfo`. No findings.
- **A09 Security Logging & Monitoring Failures** — Applies.
  `bot_connected` / `bot_disconnected` events written via `EventService`
  (`BotService:98, 235`); 429 trip is testable via the dedicated WARN line;
  Redis fail-open + Telegram-down both have pinned greppable WARN strings
  (`BotService:43-49`). Micrometer counter expectation in D14 is deferred —
  the project has no actuator dependency. Recorded as Info-3 (deferred per
  task-file directive, not a finding).
- **A10 Server-Side Request Forgery (SSRF)** — Applies. `TelegramApiClient`
  accepts only a `token` and a hard-controlled `webhookUrl` built from
  `app.url + "/webhooks/telegram/" + projectId` (`BotService:129`). The URL
  is **never** taken from request input. SSRF Javadoc on
  `TelegramApiClient.setWebhook:80-84` documents the contract. The
  `app.telegram.base-url` property is operator-configured. No findings — see
  Info-4 for `projectId` consideration.

---

## Findings by Severity

## Critical

_No findings._

## High

_No findings._

## Medium

_No findings._

## Low

### Low-1 — Empty default for `BOT_TOKEN_ENCRYPTION_KEY` permits startup attempt with a blank key

- **OWASP:** A05 Security Misconfiguration (CWE-1188 — insecure default
  initialization).
- **Location:** `backend/src/main/resources/application.properties:44`,
  `backend/src/main/java/com/botfunnel/common/crypto/TokenEncryptor.java:28-30,61-82`.
- **Description:** `app.bot.token-encryption-key=${BOT_TOKEN_ENCRYPTION_KEY:}`
  uses an *empty-string* default. The `TokenEncryptor` constructor does
  fail-fast on blank / non-hex / wrong-length (`decodeKey:62-80`), so the
  application context will *fail to boot*, which is the intended behaviour.
  However, the empty default means the failure surfaces at bean
  initialisation (an opaque `IllegalStateException` wrapped in
  `BeanInstantiationException`) rather than a Spring property-binding error.
  An operator misreading the cause line could assume "bean wiring problem"
  rather than "missing env var".
- **Exploit scenario:** Not directly exploitable — the application refuses to
  serve traffic without a valid key. Defense-in-depth concern only:
  troubleshooting friction during a misconfigured deploy could lead an
  operator to set a weak placeholder hex string to "make it boot" instead of
  generating one with `openssl rand -hex 32`.
- **Recommendation:** Two equally acceptable options. (a) Omit the default
  entirely (`app.bot.token-encryption-key=${BOT_TOKEN_ENCRYPTION_KEY}`) so
  Spring fails with `Could not resolve placeholder` at context boot — a more
  operator-friendly message. (b) Keep the empty default and prepend a
  one-line operator-facing message to the constructor error
  (`"Set BOT_TOKEN_ENCRYPTION_KEY env var; see .env.example"`). Either is
  hardening, not a correctness fix. Anchor: AC22 / tech-spec line 479.
- **CWE:** CWE-1188.

### Low-2 — `secureRandom` field initialiser in `BotService` is not `@Bean`-scoped explicitly

- **OWASP:** A02 Cryptographic Failures (CWE-330 — use of insufficiently
  random values, defense-in-depth).
- **Location:**
  `backend/src/main/java/com/botfunnel/bot/BotService.java:66`.
- **Description:** `private final SecureRandom secureRandom = new SecureRandom();`
  is created with the no-arg constructor, which on Linux uses
  `NativePRNGNonBlocking` (urandom-backed). That is the correct choice — but
  the field is owned by the singleton `@Service`, which means every
  Connect across the JVM lifetime shares one PRNG instance. This is desirable
  (entropy pool is shared) but not documented anywhere in the code; a future
  reader could think a per-request `new SecureRandom()` is cheaper and
  refactor it, which would hit `/dev/random` blocking on a freshly-booted
  container. The constructor in `TokenEncryptor:26` has the same shape.
- **Exploit scenario:** No direct exploit. A future refactor that reseeds the
  PRNG per request could introduce a startup hang or, worse, a deterministic
  fallback PRNG.
- **Recommendation:** Add a one-line comment on both `secureRandom` field
  declarations stating "Singleton — `SecureRandom` is thread-safe per the JDK
  spec; do NOT re-instantiate per request (would re-seed and may block on a
  fresh container)." No code change required.
- **CWE:** CWE-330.

### Low-3 — `extractIp` trusts `X-Forwarded-For` unconditionally

- **OWASP:** A09 Security Logging & Monitoring Failures (CWE-348 — use of
  less trusted source).
- **Location:**
  `backend/src/main/java/com/botfunnel/bot/BotController.java:94-104`.
- **Description:** `BotController.extractIp` returns the first comma-segment
  of the `X-Forwarded-For` header unchecked. If the backend is deployed
  **without** a reverse proxy that overwrites `X-Forwarded-For`, a client can
  send `X-Forwarded-For: 1.2.3.4` and have that IP recorded in the
  `bot_connected` / `bot_disconnected` audit events. This is **not** a new
  weakness — the identical helper is in `ProjectController`,
  `ProfileController`, and `AuthService` (`AuthService:642`). Task 8's
  per-task auditor already noted it as Info; recorded again here for
  cross-cutting visibility because the audit events written by Task 6 use the
  same path. Note: the user (not the IP) is the brute-force key
  (`brute:bot-connect:{userId}`), so a spoofed `X-Forwarded-For` does **not**
  let an attacker bypass the rate limit (Connect is authenticated).
- **Exploit scenario:** An attacker logged in as their own user can spoof
  `X-Forwarded-For` to insert misleading IPs into the audit trail of their
  own Connect / Disconnect events, complicating forensic analysis. They
  cannot escape rate limits or impersonate another user.
- **Recommendation:** Track at the platform level — production deploy
  documentation should mandate that the reverse proxy (nginx/traefik)
  overwrites `X-Forwarded-For`. Optionally, gate `X-Forwarded-For` parsing
  behind a `trust.proxy=true` property. Not a Task-11 regression; the audit
  records it for completeness. The fix belongs in a shared util refactor
  (`extractIp` is duplicated four times now).
- **CWE:** CWE-348.

## Info

### Info-1 — `projectId` path segment is not URL-encoded when building the webhook URL

- **OWASP:** A03 Injection (CWE-93 — CRLF injection / header injection,
  abstract risk).
- **Location:**
  `backend/src/main/java/com/botfunnel/bot/BotService.java:129`.
- **Description:** `String webhookUrl = appUrl + "/webhooks/telegram/" + projectId;`
  concatenates `projectId` into the URL string sent to Telegram via
  `setWebhook`. Mongo `_id` values are 24-character hex `ObjectId`s in this
  codebase, so they cannot contain `\r\n`, `?`, `#`, or `/`. The
  `requireOwned` guard runs **before** this step, so a `projectId` that
  reaches this line has already been looked up successfully in Mongo — an
  attacker cannot inject malformed characters here. The risk is purely
  hypothetical against a future schema change that allowed UUID-string or
  user-suffixed project ids.
- **Recommendation:** Use `java.net.URI` or
  `UriComponentsBuilder.fromUriString(appUrl).pathSegment("webhooks","telegram",projectId).build().toUriString()`
  to make the encoding explicit. Pure defense-in-depth.

### Info-2 — Frontend brought in `reka-ui` via shadcn-vue CLI (Task 9 deviation already noted)

- **OWASP:** A06 Vulnerable Components.
- **Location:** `frontend/package.json` + `frontend/components/ui/dialog/*`.
- **Description:** Task 9's `decisions.md` entry documents that shadcn-vue
  1.0.3 produces `reka-ui`-based Dialog primitives (not `radix-vue` as the
  tech-spec note anticipated). Both libraries are now in `package.json`;
  `radix-vue` is unused. This is a transitive-supply-chain consideration —
  not a vulnerability today, but Dependabot / `pnpm audit` should monitor
  both libraries.
- **Recommendation:** Open a follow-up cleanup task to drop the unused
  `radix-vue` dependency. Not blocking.

### Info-3 — D14's Micrometer counter for Redis fail-open is deferred

- **OWASP:** A09 Security Logging & Monitoring Failures.
- **Location:**
  `backend/src/main/java/com/botfunnel/bot/BotService.java:43-44` (the
  `REDIS_FAIL_OPEN_WARN` pinned constant), tech-spec D14.
- **Description:** D14 specifies both a WARN log AND a Micrometer counter
  `botfunnel.bot_connect.brute_force.redis_failures` on the brute-force
  Redis fail-open path. The project has no Spring Boot Actuator / Micrometer
  dependency. The pinned, greppable WARN line is the only observability
  surface today. Per `tasks/11.md` lines 135–136 (verbatim mandate), this
  audit MUST NOT fail the feature for the missing counter — recorded as
  Info / deferred only.
- **Recommendation:** Track in a follow-up Observability epic when actuator
  is introduced platform-wide. No action in 06.

### Info-4 — `TELEGRAM_BASE_URL` env var has no production-side guard against `http://` schemes

- **OWASP:** A05 Security Misconfiguration / A10 SSRF (defense-in-depth).
- **Location:** `application.properties:45`,
  `TelegramApiClient.TelegramApiClient(builder, baseUrl, timeout):51-61`.
- **Description:** `app.telegram.base-url=${TELEGRAM_BASE_URL:https://api.telegram.org}`
  defaults safely. The test profile overrides this to point at MockWebServer
  via `@DynamicPropertySource`, which is correct. There is no startup
  validation that the configured base URL has scheme `https` in production
  — an operator who accidentally set `TELEGRAM_BASE_URL=http://api.telegram.org`
  would silently downgrade to plaintext (and therefore leak the bot token to
  any on-path observer of the Telegram request). The default is safe; only
  an explicit misconfiguration triggers this.
- **Recommendation:** Add a one-line guard in the `TelegramApiClient`
  constructor that asserts `baseUrl.startsWith("https://")` unless a
  `spring.profiles.active` includes `test`. Defense-in-depth; not blocking.

---

## Focus-Area Checklist (nine items from `tasks/11.md`)

### 1. Token-at-rest encryption — PASS

- **Algorithm.** AES/GCM/NoPadding (`TokenEncryptor:20`), 256-bit key
  (`KEY_BYTES=32` enforced in `decodeKey:76-80`).
- **IV.** Fresh 12-byte IV per encryption from `SecureRandom`
  (`TokenEncryptor.encrypt:33-34`). IV is never reused; per-call generation
  is the GCM-safe form.
- **Tag.** 128-bit GCM tag (`TAG_BITS=128`,
  `new GCMParameterSpec(TAG_BITS, iv)`:37,53). On decrypt, `Cipher.doFinal`
  raises `AEADBadTagException` (subclass of `GeneralSecurityException`) on
  tamper — caught and rethrown as `IllegalStateException` at `:56-58`. Tag
  is not silently truncated.
- **Key handling.** Read from
  `@Value("${app.bot.token-encryption-key}")` (env-bound). Fail-fast on
  blank / non-hex / wrong-length at `decodeKey:62-80`. The JDK
  `HexFormat.parseHex` error message is **deliberately dropped** at
  `:69-74` so a misconfigured key fragment does not leak into the boot log
  (round-1 finding S1 — fix verified in `TokenEncryptor.java`).
- **Encrypted fields on `Bot`** carry `@JsonIgnore` (`Bot.java:53-60`) so
  even an accidental serializer cannot ship them. `BotResponse` has no
  ciphertext / IV components (`BotResponse:9-16`). `BotTokenLeakTest`
  enforces both invariants reflectively.
- **Decryption path.** Only `BotService.doDisconnect:216-218` decrypts —
  the plaintext is reachable via lambda capture for the lifetime of one
  Disconnect subscription, used as the `deleteWebhook` argument, and never
  logged or returned. Comment at `:213-215` documents the lifetime
  honestly.
- **Anchors:** AC1, AC17, R1, R3, D3, D15. Round-1 / round-2 security
  reviews of Task 1 closed with zero findings.

### 2. Webhook secret SHA-256 storage — PASS

- **Generation.** 16 bytes from `SecureRandom`
  (`BotService.connectAfterPreChecks:125-126`). Hex-encoded for transport
  to Telegram (`HexFormat.of().formatHex(secretBytes)`:127).
- **Transport.** Plaintext is sent in the `secret_token` form field to
  `setWebhook` (`TelegramApiClient.setWebhook:87-89`). Exists only inside
  the request body; never logged.
- **Persistence.** Hex-encoded SHA-256 hash stored at
  `Bot.webhookSecretHash`. `sha256Hex` lives at `BotService.sha256Hex:297-304`
  — `MessageDigest.getInstance("SHA-256")` + `HexFormat.formatHex`. 64-char
  hex form. The integration test asserts the field matches `[0-9a-f]{64}`
  (`BotControllerIT.webhookSecretInMongo_isSha256Hash:902-918`).
- **No response leak.** `BotResponse` has no `webhookSecret` /
  `webhookSecretHash` field; `Bot.webhookSecretHash` is `@JsonIgnore`
  (`Bot.java:60`); `BotTokenLeakTest.FORBIDDEN_RESPONSE_COMPONENTS`
  includes `webhookSecretHash`.
- **Cleared on Disconnect.** `setWebhookSecretHash(null)` at
  `BotService.doDisconnect:232`. Integration-tested at
  `BotControllerIT.postDisconnect_happyPath:663`.
- **Anchors:** AC20, R7, D2.

### 3. Mass-assignment in request DTOs — PASS

- `ConnectBotRequest` is a `record` with one component (`token`) and is
  annotated `@JsonIgnoreProperties(ignoreUnknown = true)` at
  `ConnectBotRequest:11`. Unknown JSON fields are silently dropped by
  Jackson before reaching the controller method body.
- Verified end-to-end by
  `BotControllerIT.antiEnumeration_foreignSoftDeletedMalformedProjectId_returns404_andHostileBodyIgnored:765-798`
  which posts `{"token":"...","ownerId":"attacker"}` and observes the
  hostile `ownerId` is ignored (the request goes through `requireOwned`
  with the authenticated user ID, returns 404 for the foreign project).
- `BotController` takes `@PathVariable String projectId` and the security
  context user; never reads `ownerId` from the body.
- `BotResponse` is also a record with no setters — no entity-poisoning
  shape on the way out, either.
- **Anchors:** AC16, planning convention from `patterns.md` (mass-assignment
  defense pattern).

### 4. IDOR through `projectId` — PASS

- Every public service method calls `projectService.requireOwned` as its
  first reactive step:
  - `BotService.connect:92`
  - `BotService.requireConnectedBot:119` (used by `getByProject`,
    `disconnect`, `sendTestMessage`)
- `ProjectService.requireOwned` (`ProjectService:50-64`) collapses
  foreign-owned / soft-deleted / malformed `ObjectId` all to
  `AppException.notFound` — never 403, never 200, never a distinguishing
  body. Integration-tested at
  `BotControllerIT.antiEnumeration_...:765-798` (three branches: foreign,
  soft-deleted, malformed).
- `BotController` never reads `ownerId` from the body — the value comes
  only from the security context via `currentUserId()` (`:81-87`). The
  `@JsonIgnoreProperties(ignoreUnknown=true)` defense (Focus #3) is the
  belt; `currentUserId()` is the braces.
- Unauthenticated requests hit the `/api/**` authenticated rule from
  `SecurityConfig:71` and return 401 — exercised by
  `BotControllerIT.anyEndpoint_unauthenticatedBareClient_returns401:866-896`
  for all four endpoints.
- **Anchors:** AC16, R2 (cross-project info leak — see Focus #8), pattern
  in `patterns.md` → anti-enumeration.

### 5. Rate-limit bypass — PASS

- **INCR every attempt.** `BotService.incrementBruteForceCounter:247`
  increments unconditionally — covers Connect-then-Disconnect-then-Connect
  loops (auth pattern's INCR-on-failure would leave the loop wide open).
- **Threshold check before any Telegram call.** The pipeline ordering at
  `BotService.connect:92-100` runs `incrementBruteForceCounter` BEFORE
  `telegramApiClient.getMe`. The 11th attempt's INCR returns 11; the chain
  emits `AppException.tooManyRequests` at `:252-255`; `getMe` is never
  invoked. Integration-tested at
  `BotControllerIT.postConnect_eleventhAttemptWithinWindow_returns429_noTelegramCalls:567-587`
  with an explicit `drainRequests()` assertion of zero Telegram calls.
- **TTL on first set only.** `redisTemplate.expire(key, BRUTE_TTL)` is
  applied only when `count == 1L` (`BotService.incrementBruteForceCounter:249-251`).
  Window is stable across the 15-minute period (auth-pattern compatible).
- **Key shape immutable from user input.** Key is
  `"brute:bot-connect:" + userId` (`BotService.bruteForceKey:277-279`).
  `userId` comes from the security context principal — never from request
  body or path. An attacker cannot poison the key shape.
- **Fail-open on Redis error.** `.onErrorResume(err -> {...})` at
  `BotService.incrementBruteForceCounter:258-264` catches every
  non-AppException, logs the pinned WARN
  (`REDIS_FAIL_OPEN_WARN`:43-44 — greppable, asserted by IT
  `postConnect_redisDown_failsOpen_connectSucceeds_warnLogged:612-638`),
  and emits `Mono.empty()` so the pipeline proceeds.
- **DEL on success.** `BotService.resetBruteBruteForceCounter` is called
  via `BotService.connect:100`; integration-tested at
  `BotControllerIT.postConnect_successfulConnect_deletesBruteForceKey:589-609`.
- **Micrometer counter.** Deferred — see Info-3.
- **Anchors:** AC11, D14, R9.

### 6. Log-line and event-metadata content (no raw tokens) — PASS

- **No log site takes the raw token.** Greppable: no `log.info|warn|error`
  call in `BotService.java`, `TelegramApiClient.java`, or `BotController.java`
  references the `token` argument by name. The only `log.warn` calls in
  `BotService` are:
  - `REDIS_FAIL_OPEN_WARN` (`:43-44, 262, 272`) — message is the Redis
    exception's `getMessage()` only.
  - `TELEGRAM_DISCONNECT_WARN` (`:48-49, 224`) — message is
    `TelegramApiClient.scrubTokens(err.getMessage())`.
  - Compensation rollback (`:167-168`) — message is
    `TelegramApiClient.scrubTokens(compErr.getMessage())`.
- **TelegramApiClient WARN sites scrub.** `mapClientError` →
  `toAppException` (`TelegramApiClient:128-142`) runs every Telegram error
  `description` through `scrubTokens` BEFORE logging. The regex
  `\d{1,20}:[A-Za-z0-9_-]{30,50}` replaces matches with `[REDACTED_TOKEN]`
  (`scrubTokens:171-174`). Even if Telegram echoed our own token back in
  the `description` field of a 4xx response, the WARN line is safe.
- **End-to-end test of the scrubber.** Round-1 reinforcement to Task 2
  added `setWebhookScrubsTokenInWarnLog` (asserted in unit tests). For the
  integration boundary,
  `BotControllerIT.noTokenLeak_inAnyResponseBodyOrEventMetadata:802-861`
  exercises the leak-prone scenario: enqueues a Telegram 4xx whose
  description literally contains `"Bad webhook for token <REAL TOKEN> on chat"`,
  and asserts the 500 response body does **not** match the token regex.
- **Event metadata content.** `BotService.connectedMetadata:281-287`
  contains exactly `projectId`, `telegramBotId`, `telegramUsername` — no
  token, no IV, no hash. `BotService.disconnectedMetadata:289-295` contains
  exactly `projectId`, `telegramBotId`, `webhookDeleted` — no token, no IV,
  no hash. Asserted with `containsOnlyKeys(...)` at
  `BotControllerIT.postConnect_validToken_returns200WithBotResponse:303-307`
  and `BotControllerIT.postDisconnect_happyPath_returns200_andUpdatesMongoAtomically:667-670`.
- **`BotTokenLeakTest` asserts.** `botResponseRecordHasNoEncryptedTokenComponents`
  fails the build on any record-component named `encryptedTokenCiphertext`,
  `encryptedTokenIv`, `token`, or `webhookSecretHash`.
  `botEntityDoesNotDeclareToStringExposingEncryptedTokenFields` fails the
  build on any future `Bot#toString` override (default `Object#toString`
  cannot leak fields — sets a tripwire for any later change).
- **No `description` leak in user-facing 500.** `toAppException:133-138`
  wraps the Telegram description in an operator-only WARN log and returns
  a generic `AppException(500, "webhook_config_error", "Webhook configuration error")`
  with no description in the body.
- **Anchors:** AC17, AC23, R1, D17.

### 7. CSRF inheritance — PASS

- **`SecurityConfig` is not modified by this feature.** Verified by git
  log and by re-reading the file in its entirety
  (`SecurityConfig:1-108`). The bot module ships zero security filter
  changes.
- **CSRF protection covers `/api/v1/projects/{projectId}/bot/**`.** The
  filter chain registers
  `CookieServerCsrfTokenRepository.withHttpOnlyFalse()` at
  `SecurityConfig:56` with no exclusions — every state-changing request on
  `/api/**` is gated. Verified at runtime via
  `BotControllerIT.anyEndpoint_unauthenticatedBareClient_returns401:866-896`
  (CSRF mutator is used for state-changing verbs; without it the request
  fails before reaching authorization).
- **All four endpoints live under `/api/**`.**
  `@RequestMapping("/api/v1/projects/{projectId}/bot")` at
  `BotController:21`. `authorizeExchange` at `SecurityConfig:71` requires
  authentication on `/api/**`.
- **No CSRF-exempt matcher for bot routes.** No `csrfMatcher.exclude(...)`
  / `requireCsrfProtectionMatcher` overrides — the default protects all
  state-changing methods.
- **GET is exempt by Spring Security default.** Acceptable per task-file
  note.
- **Anchors:** Tech-spec line 484 ("SecurityConfig is not modified").

### 8. Cross-project information disclosure on 409 — PASS

- **`bot_already_connected` body.** `BotService.MESSAGE_BOT_ALREADY_CONNECTED`
  (`:55-56`) = `"This bot is already connected to another project"`. No
  foreign project name, no foreign owner email, no foreign `telegramBotId`.
  Asserted in IT at
  `BotControllerIT.postConnect_platformBotIdAlreadyConnected_returns409BotAlreadyConnected:402-425`
  (asserts only `$.code == "bot_already_connected"`, no other body field).
- **`bot_already_in_project` body.** `MESSAGE_BOT_ALREADY_IN_PROJECT`
  (`:53-54`) = `"another bot is already connected — disconnect it first"`.
  Refers only to "another bot" without naming it — even though this
  conflict is within the requester's own project, the message is generic.
- **i18n side.** `errors.bot.connect.409` in `en.json:261` =
  `"This bot is already connected to another project."` — matches the
  generic backend message; no foreign metadata. Same generic copy in
  `uk.json`.
- **No `WWW-Authenticate` / `Location` / `X-` headers reveal anything.**
  `GlobalErrorHandler.handleAppException:20-24` writes only the
  `ErrorResponse{message, code}` body — no auxiliary headers.
- **Anchors:** AC6, AC7, R2, D1.

### 9. Compensating-`deleteWebhook` code path — PASS

- **Triggers on generic persist failure.**
  `BotService.connectAfterPreChecks:153-156` →
  `botRepository.save(bot).onErrorResume(persistErr -> compensateAndPropagate(token, user.id(), persistErr))`.
  Integration-tested at
  `BotControllerIT.postConnect_persistFails_compensatingDeleteWebhookFires_returns500:447-481`
  with the spy returning `Mono.error(...)`; the test asserts
  `setWebhook` precedes `deleteWebhook` in the recorded request log.
- **Triggers on `DuplicateKeyException` from BOTH partial unique indexes.**
  `compensateAndPropagate` calls `deleteWebhook` first, then
  `mapPersistError` (`:171, 175-196`) discriminates the index name
  (`projectId_unique_connected` vs `telegramBotId_unique_connected`) to
  return the matching 409. Tested by the parallel-race ITs at
  `BotControllerIT.postConnect_parallelSameToken_exactlyOneSucceeds_otherReturns409:483-520`
  and `..._parallelSameProjectDifferentTokens_...:522-554` — the loser
  emits a 409 AND the recorded request log contains one compensating
  `deleteWebhook` per loser that reached `setWebhook`.
- **Best-effort (`.onErrorResume`, never rethrows).**
  `BotService.compensateAndPropagate:166-170` swallows any failure from
  the compensating `deleteWebhook` itself, logs at WARN
  (`"Compensating deleteWebhook failed during Connect rollback: {}"`),
  and proceeds to surface the original persist error. The compensation's
  failure does NOT mask the user-facing 500/409.
- **Original error is propagated.** `compensateAndPropagate` returns
  `Mono::error` over the mapped `persistErr` (`:171-172`). Verified by
  the IT asserting status code 500 (or 409 in the race tests).
- **Same plaintext token is used.** `compensateAndPropagate(String token, ...)`
  receives the same `token` parameter that was passed to
  `setWebhook` earlier in the chain (`:131`) — no decrypt-from-DB round
  trip. Confirmed by line-walking the call sites at `:155`. This matters:
  on a persist failure the row was never written, so there is no
  ciphertext to decrypt.
- **Disconnect path symmetry.** `BotService.doDisconnect:223-226` mirrors
  the policy: persistent Telegram failure logs `TELEGRAM_DISCONNECT_WARN`
  via `scrubTokens`, returns `false`, and proceeds to the local update.
  Tested by `BotControllerIT.postDisconnect_telegramDown_warnLogged_returns200:673-699`.
- **WARN log scrubs the token.** Both compensation WARN sites use
  `TelegramApiClient.scrubTokens(err.getMessage())` (`:168, 224`) — a
  WebClient transport error message that contains the request URI (which
  embeds the token) is redacted before reaching the log.
- **Anchors:** AC9, AC13, R5, R6, D4.

---

## Risks-vs-Code Matrix (user-spec Risks R1–R10 — tech-spec Risks)

User-spec lists R1–R7. Tech-spec adds R4b, R8, R9, R10 (an effective
R1–R10 set when including R4b). All ten are checked here.

| Risk | Stated mitigation | Implemented? | Evidence |
|---|---|---|---|
| R1 — Bot token leak through logs or API responses | AES-256-GCM at rest; `BotResponse` carries no token fields; UI masked only; logs reviewed against a checklist; `BotTokenLeakTest` reflective assert | YES | `TokenEncryptor` (AES-256-GCM, IV randomness, GCM tag); `BotResponse:9-16` has only `tokenSuffix`; `BotTokenLeakTest` enforces both invariants; `scrubTokens` applied at every WARN log site that echoes a Telegram description; `Bot` fields `@JsonIgnore`d. |
| R2 — Cross-project information leakage on 409 | Generic 409 message — no foreign project name or owner email | YES | `MESSAGE_BOT_ALREADY_CONNECTED:55-56`, `MESSAGE_BOT_ALREADY_IN_PROJECT:53-54`, matching i18n in `en.json:261` / `uk.json`; integration-test asserts only `$.code`. |
| R3 — At-rest encryption key compromise | Documented manual rotation procedure; no per-doc key version | YES | `BOT_TOKEN_ENCRYPTION_KEY` is single-key (D3); user-spec "Ограничения" documents the rotation procedure (rotate env → admin force-disconnect → owners reconnect). Active rotation deferred — matches D3. |
| R4 — Race on platform-wide bot uniqueness | Defense-in-depth: service pre-check + partial unique index on `(telegramBotId)` filtered `status=CONNECTED`; `DuplicateKeyException` mapped to 409 | YES | Pre-check at `BotService.ensureTelegramBotIdNotConnectedAnywhere:205-210`; index `telegramBotId_unique_connected` at `Bot.java:20-23`; `DuplicateKeyException` mapping in `mapPersistError:175-196`; compensating `deleteWebhook` triggers; parallel-race IT verifies. |
| R4b — Race on per-project bot uniqueness | Defense-in-depth: service pre-check + partial unique index on `(projectId)` filtered `status=CONNECTED`; `DuplicateKeyException` mapped to 409 | YES | Pre-check at `BotService.ensureNoConnectedBotForProject:198-203`; index `projectId_unique_connected` at `Bot.java:24-27`; mapping in `mapPersistError`; parallel-race IT `..._parallelSameProjectDifferentTokens_..._returns409:522-554` verifies. |
| R5 — Orphan webhook after Disconnect with Telegram down | Webhook-secret hash nulled on Disconnect; orphan updates rejected by 06b secret check | YES | `BotService.doDisconnect:232` nulls `webhookSecretHash`. 06b is a future feature; 06 makes the rejection capability possible by storing the hash for compare. |
| R6 — Orphan webhook after Mongo write fails post-setWebhook | Compensating `deleteWebhook` (best-effort + logged) in same request | YES | Focus Area #9 covers in detail. |
| R7 — Webhook secret leak from Mongo backup | Stored as SHA-256 hash, not plaintext | YES | `BotService.sha256Hex:297-304`; `Bot.webhookSecretHash` is stored as 64-char hex (IT asserts `[0-9a-f]{64}`); plaintext exists only during the request. |
| R8 — Telegram base URL misconfigured (HTTP `APP_URL` in prod) | Explicit 500 `webhook_config_error` surfaced in operator logs; non-actionable user message | YES | `TelegramApiClient.toAppException:128-142` maps 4xx-with-description to `500 webhook_config_error`; WARN log contains scrubbed Telegram description. User-facing message is generic. IT `postConnect_setWebhook4xxConfigError_returns500WithCodeWebhookConfigError:380-399` verifies. Residual hardening: Info-4 (no scheme guard on `TELEGRAM_BASE_URL`). |
| R9 — Redis outage masking brute-force activity | Fail-open by design (DoS mitigation, not auth gate); WARN level log; (Micrometer counter — deferred per task directive) | YES with Info-3 caveat | WARN-line pinned at `BotService.REDIS_FAIL_OPEN_WARN:43-44`; greppable; IT `postConnect_redisDown_failsOpen_connectSucceeds_warnLogged:612-638` verifies. Micrometer counter deferred per the task-file mandate. |
| R10 — MockWebServer leak through to production classpath | Declared `testImplementation` only; verified via produced jar | YES | `backend/build.gradle:38` declares `testImplementation 'com.squareup.okhttp3:mockwebserver:4.12.0'`. Gradle's `testImplementation` configuration is not part of the runtime classpath and is not packaged into the bootJar. |

**All R1–R10 mitigations are implemented as stated.** No risk-vs-code gap
elevated to a finding; Info-3 and Info-4 record forward-looking hardening
notes only.

---

## Deploy Recommendation

`Deploy recommendation: GO`

The audit identified 0 Critical, 0 High, 0 Medium, 3 Low, 4 Info findings.
Low and Info findings are defense-in-depth or pre-existing platform
patterns shared with other modules (Low-3 in particular). None block
deploy. The R1–R10 mitigation matrix is fully implemented. The nine focus
areas all pass.

Recommended follow-ups (non-blocking, post-deploy):

1. Low-1: tighten the boot-time validation message for
   `BOT_TOKEN_ENCRYPTION_KEY` (operator-facing UX).
2. Low-2: add a one-line comment on the `SecureRandom` field declarations.
3. Low-3: track the `extractIp` X-Forwarded-For trust at the platform
   level — fold into a future shared `IpExtractor` util.
4. Info-1: switch `webhookUrl` construction to `UriComponentsBuilder`
   defense-in-depth.
5. Info-2: drop the unused `radix-vue` dependency from `frontend/package.json`.
6. Info-3: add the Micrometer counter when actuator is introduced
   platform-wide.
7. Info-4: add an `https://` scheme guard on `TELEGRAM_BASE_URL` for the
   production profile.
