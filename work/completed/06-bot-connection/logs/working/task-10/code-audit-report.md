# Code Audit — 06-bot-connection

Holistic, cross-component code-quality audit of the 06-bot-connection epic
(tasks 1–9). Tests are out of scope (Task 12); security-specific issues are
out of scope (Task 11) — anything security-flavored is mentioned briefly
with deferral.

## Scope

**Files reviewed (production code, tasks 1–9 `Files to modify` ∪ `decisions.md` deviations):**

Backend — production:
- `backend/src/main/java/com/botfunnel/common/crypto/TokenEncryptor.java`
- `backend/src/main/java/com/botfunnel/common/crypto/EncryptedValue.java`
- `backend/src/main/java/com/botfunnel/bot/TelegramApiClient.java`
- `backend/src/main/java/com/botfunnel/bot/dto/TelegramResult.java`
- `backend/src/main/java/com/botfunnel/bot/dto/TelegramUser.java`
- `backend/src/main/java/com/botfunnel/bot/Bot.java`
- `backend/src/main/java/com/botfunnel/bot/BotStatus.java`
- `backend/src/main/java/com/botfunnel/bot/BotRepository.java`
- `backend/src/main/java/com/botfunnel/bot/BotService.java`
- `backend/src/main/java/com/botfunnel/bot/BotController.java`
- `backend/src/main/java/com/botfunnel/bot/dto/ConnectBotRequest.java`
- `backend/src/main/java/com/botfunnel/bot/dto/BotResponse.java`
- `backend/src/main/resources/application.properties` (bot-module section)
- `backend/build.gradle` (mockwebserver testImplementation)
- `.env.example` (BOT_TOKEN_ENCRYPTION_KEY, TELEGRAM_BASE_URL)

Backend — comparison baselines (read-only):
- `backend/src/main/java/com/botfunnel/project/ProjectController.java`
- `backend/src/main/java/com/botfunnel/project/ProjectService.java`
- `backend/src/main/java/com/botfunnel/common/AppException.java`
- `backend/src/main/java/com/botfunnel/common/GlobalErrorHandler.java`
- `backend/src/main/java/com/botfunnel/security/SecurityConfig.java`
- `backend/src/main/java/com/botfunnel/auth/AuthService.java`
- `backend/src/main/java/com/botfunnel/events/EventService.java`

Frontend — production:
- `frontend/types/bot.ts`
- `frontend/stores/bot.ts`
- `frontend/pages/projects/[projectId]/settings/index.vue`
- `frontend/pages/projects/[projectId]/settings/bot.vue`
- `frontend/components/SettingsSubnav.vue`
- `frontend/components/ui/dialog/*` (Dialog.vue inspected; rest are shadcn-vue
  CLI-scaffolded primitives over reka-ui — code is template-generated and is
  not author-owned)
- `frontend/i18n/locales/uk.json` (bot.* and errors.bot.* subtrees)
- `frontend/i18n/locales/en.json` (bot.* and errors.bot.* subtrees)
- `frontend/package.json`
- `frontend/layouts/default.vue` (sidebar link to settings)

Frontend — comparison baselines:
- `frontend/stores/projects.ts`
- `frontend/composables/useApi.ts`
- `frontend/composables/useApiError.ts`

**Out of scope:** tests (Task 12), security-specific issues (Task 11). Where
a finding is borderline-security, this report mentions it briefly and defers
the deep analysis to Task 11.

## Summary

- Critical: 0
- High: 0
- Medium: 1
- Low: 4

Overall: the code is in good shape. The Wave-1 building blocks are properly
shared, layering is consistent with the rest of the platform, AppException
factories are used with the right (status, code) pairing, the reactive
chains use `Mono.defer` to lock side-effect ordering, no `block()` /
`.toFuture().get()` appears anywhere, and every WARN line that quotes a
Telegram-side string is scrubbed through `TelegramApiClient.scrubTokens(...)`.
The one medium finding is a contractual gap in the D4 compensation scope
(post-`setWebhook` encryption failure is not rolled back). The low findings
are duplication / hygiene smells that match documented "skipped" reviewer
notes from earlier tasks.

## Findings by Dimension

### 1. Duplicate resource initialization

#### Low

- **Two `SecureRandom` instances across `TokenEncryptor` and `BotService`**
  - File / lines: `backend/src/main/java/com/botfunnel/common/crypto/TokenEncryptor.java:26`,
    `backend/src/main/java/com/botfunnel/bot/BotService.java:66`
  - Problem: each class allocates its own `SecureRandom`. `SecureRandom` is
    thread-safe, so a single shared instance (a `@Component` `SecureRandomProvider`
    or a `@Bean SecureRandom`) would do. Seeding is cheap on JDK 21
    (`NativePRNGNonBlocking` on Linux), so the cost is one extra entropy
    request at startup — not a hot-path issue. Already triaged in Task 6
    round-1 review (C3) as "cosmetic, matches `TokenEncryptor`".
  - Suggested fix: defer — accept the duplication. Reconsider only if a
    third producer of cryptographic randomness lands (e.g. `WebhookSecretGenerator`
    in 06b); at that point promote to a `SecureRandomProvider` `@Component`.

Other potential duplicates I checked and rejected: `WebClient.Builder` is
injected (only `TelegramApiClient` consumes it; no other reactive client in
the bot module); `Retry.backoff(...)` is configured exactly once
(`TelegramApiClient.buildRetry`); `BotRepository` and `ReactiveRedisTemplate`
are injected, never reconstructed; `TokenEncryptor` and `TelegramApiClient`
are each a single `@Component` consumed by `BotService` only.

### 2. Shared-resources compliance with Architecture

Dimension X — OK. Every shared resource declared in `architecture.md` /
`patterns.md` is a `@Component` / `@Service` injected exactly where it is
used:

- `TokenEncryptor` — `@Component`, single instance, sole consumer is
  `BotService` (encrypt at Connect, decrypt at Disconnect).
- `TelegramApiClient` — `@Component`, single instance, sole consumer is
  `BotService` (3 methods: `getMe`, `setWebhook`, `deleteWebhook`). The
  `scrubTokens(...)` helper is correctly elevated to `public static` so
  `BotService` log sites can scrub Telegram error strings without
  re-declaring the regex.
- `ReactiveRedisTemplate<String, String>` — injected from the platform-wide
  Boot autoconfig, never reconstructed in the bot module. Keys follow the
  agreed `brute:bot-connect:{userId}` shape (`BotService.bruteForceKey`).
- `BotRepository` — injected, three derived queries, no module-local
  utility that duplicates Mongo access.
- `EventService.logEvent(...)` — used at the two agreed event sites
  (`bot_connected`, `bot_disconnected`); no module-private duplicate of the
  fire-and-forget save.
- `ProjectService.requireOwned(...)` — called as the FIRST reactive step
  in every public Bot-scoped service method (`getByProject`, `connect`,
  `disconnect`, `sendTestMessage`); see dimension 3 for the verification.

### 3. Architectural consistency (controller / service / repository layering)

Dimension X — OK.

- Layering: `BotController` (HTTP shape) → `BotService` (orchestration) →
  `BotRepository` (persistence). No layer skipping. No `BotRepository`
  access from `BotController`; no `TelegramApiClient` access from
  `BotController`; no `MessageDigest` / `TokenEncryptor` access from
  `BotController`.
- `BotController` carries only HTTP concerns (`@PathVariable`, `@Valid
  @RequestBody`, `currentUserId()` from the security context, `extractIp`
  / `capUserAgent` from headers, response mapping via `toResponse`).
- `BotService.requireOwned` placement — verified for each public method:
  - `getByProject` → `requireConnectedBot` → `projectService.requireOwned(ownerId, projectId, false)` is the first call (`BotService.java:119`).
  - `connect` → `projectService.requireOwned(ownerId, projectId, false)` is the first call (`BotService.java:92`).
  - `disconnect` → `requireConnectedBot` → `requireOwned` (same as above).
  - `sendTestMessage` → `requireConnectedBot` → `requireOwned` (same as above).
- No encryption outside `TokenEncryptor`; no Telegram API access outside
  `TelegramApiClient`; SHA-256 of the webhook secret is centralised in
  `BotService.sha256Hex(...)`.
- `BotStatus` enum: HTTP wire-form (`@JsonValue` lowercase) and Mongo
  persistence form (`name()` uppercase) are intentionally separate and
  the class-load assertion in `Bot.java:34-40` pins the partial-index
  literal to `BotStatus.CONNECTED.name()` so a future enum rename fails
  at `<clinit>` instead of voiding the D1/D5 race indexes.

### 4. AppException usage

Dimension X — almost-OK, one Low finding.

Verified all error paths in the feature:

| Site | Status | Code | Factory |
|---|---|---|---|
| `BotService.requireConnectedBot` (bot not found) | 404 | null | `AppException.notFound(...)` ✓ |
| `BotService.sendTestMessage` (D7) | 422 | `owner_chat_id_unknown` | `AppException.unprocessableEntity(code, msg)` ✓ |
| `BotService.incrementBruteForceCounter` (D14) | 429 | null | `AppException.tooManyRequests(msg)` ✓ |
| `BotService.ensureNoConnectedBotForProject` (D5) | 409 | `bot_already_in_project` | `AppException.conflict(code, msg)` ✓ |
| `BotService.ensureTelegramBotIdNotConnectedAnywhere` (AC6) | 409 | `bot_already_connected` | `AppException.conflict(code, msg)` ✓ |
| `BotService.mapPersistError` (DuplicateKeyException → 409) | 409 | both codes | `AppException.conflict(code, msg)` ✓ |
| `TelegramApiClient` 401 (D11) | 422 | `invalid_bot_token` | `AppException.unprocessableEntity(code, msg)` ✓ |
| `TelegramApiClient` 4xx-config (D8/D12) | 500 | `webhook_config_error` | `new AppException(INTERNAL_SERVER_ERROR, code, msg)` ✓ |
| `TelegramApiClient` retry-exhausted (D8) | 502 | `telegram_unavailable` | `new AppException(BAD_GATEWAY, code, msg)` ✓ |
| `TelegramApiClient.getMe` empty `result` | 502 | `telegram_unavailable` | `new AppException(BAD_GATEWAY, code, msg)` ✓ |
| `TelegramApiClient` 4xx-fallback | 400 | null | `AppException.badRequest(...)` ✓ (see Low below) |
| `BotController` (no auth principal) | 401 | null | `AppException.unauthorized(...)` ✓ |

No raw Telegram `description` reaches user-facing JSON — every 4xx /
5xx path uses a generic English message (`"Token is invalid or revoked"`,
`"Webhook configuration error"`, `"Telegram client error"`, `"Telegram is
currently unavailable. Try again in a minute."`). The raw Telegram
description only appears in WARN-level operator logs, scrubbed by
`scrubTokens(...)` (`TelegramApiClient.java:134, 140`).

#### Low

- **`TelegramApiClient` 4xx-fallback returns 400 with no business code**
  - File / lines: `backend/src/main/java/com/botfunnel/bot/TelegramApiClient.java:141`
  - Problem: `AppException.badRequest("Telegram client error")` (1-arg
    factory → 400, `code: null`). The 4xx-fallback branch fires when a
    Telegram-returned 4xx has neither a recognisable description (so
    the config-error 500 branch did not match) nor a 401 status (so the
    `invalid_bot_token` 422 branch did not match). The current behaviour
    is to return 400 to the user. There is no `errors.bot.connect.400`
    leaf shaped for "Telegram sent us something we didn't recognise",
    so the frontend falls through to `errors.bot.connect.generic` /
    `errors.generic`. The user message is non-actionable. Considering
    AC2 (400 is reserved for "Bot token has invalid format" — the bean
    validation path), surfacing the Telegram-side fallback as 400 risks
    a false-positive UX ("invalid format" message when the token shape
    is fine).
  - Suggested fix: map the fallback branch to 500
    `webhook_config_error` (same bucket as the config-error branch),
    since "Telegram 4xx we don't understand" is operationally the same
    class — operator-fix, not user-fix. Single-line change.

### 5. Reactive-chain correctness

Dimension X — almost-OK, one Medium finding.

- No `block()` / `blockOptional()` / `.toFuture().get()` in any production
  file under `com/botfunnel/bot/` or `com/botfunnel/common/crypto/`
  (verified by reading every file end-to-end).
- `Mono.defer(...)` wraps every step in `BotService.connect` that depends
  on prior side effects (brute-force counter, per-project pre-check, getMe,
  platform-wide pre-check). This is the canonical pattern from `AuthService`
  (login chain) and locks the side-effect order even if upstream
  short-circuits.
- Fire-and-forget audit-event emission: `eventService.logEvent(...)` is
  called inside `doOnSuccess`. The fire-and-forget happens INSIDE
  `EventService.logEvent`, which `.subscribe()`s the save and routes
  errors to `log.error`. This is the platform-wide pattern; no
  `subscribeOn(Schedulers.boundedElastic())` is needed because the inner
  call is a reactive Mongo save (non-blocking driver) — matches the auth
  and projects modules.
- Compensating `deleteWebhook` on Connect-path persist failure: composed
  via `.flatMap(Mono::error)` in the main chain (`BotService.compensateAndPropagate`).
  Not a fire-and-forget side effect — it is part of the user-facing
  pipeline and the original error is preserved.
- Brute-force counter DEL on Connect success: `.flatMap(saved ->
  resetBruteForceCounter(ownerId).thenReturn(saved))`. Failure is swallowed
  with a WARN log via `.onErrorResume(...)` (D14 fail-open semantic).
  This is intentional and matches the auth pattern.
- Disconnect-with-Telegram-down (AC13b): `.onErrorResume(err -> { log.warn(...);
  return Mono.just(false); })` so the local update proceeds. Documented
  fail-open; not a swallowed real failure.

#### Medium

- **D4 compensation scope is narrower than the contract — encryption
  failure after `setWebhook` succeeds leaves an orphan webhook**
  - File / lines: `backend/src/main/java/com/botfunnel/bot/BotService.java:131-156`
  - Problem: the contract from D4 ("compensating `deleteWebhook` on
    Connect-path persist failure") and from R5/D4 reasoning ("a persist
    failure leaves Telegram with a live webhook the system doesn't know
    about — orphan, no cleanup path") covers any error after `setWebhook`
    succeeds. The current code attaches `.onErrorResume(persistErr ->
    compensateAndPropagate(...))` ONLY to `botRepository.save(bot)`. If
    `tokenEncryptor.encrypt(token)` throws (which is `IllegalStateException
    ("AES-GCM encryption failed", GeneralSecurityException)` — practically
    impossible on JDK 21 with a valid 256-bit key, but the contract is
    contractual), the synchronous throw propagates out of `Mono.defer(...)`,
    bypasses the compensation, and the user gets a 500 from
    `GlobalErrorHandler`. Telegram is left with a live webhook that
    nothing on our side will clean up. Same narrative for `Base64.encode`
    (won't throw in practice but is between `setWebhook` and `save`).
  - Suggested fix: broaden the compensation scope to the entire post-
    `setWebhook` block, e.g.
    `.then(Mono.defer(() -> encryptAndSave(...))).onErrorResume(err ->
    compensateAndPropagate(token, user.id(), err))`. One scope move,
    zero behaviour change on the happy and `DuplicateKey` paths.

#### Low

- **Disconnect path: decryption failure on a corrupted bot row blocks
  Disconnect indefinitely**
  - File / lines: `backend/src/main/java/com/botfunnel/bot/BotService.java:212-239` (`doDisconnect`)
  - Problem: `tokenEncryptor.decrypt(iv, ct)` runs synchronously at the
    top of `doDisconnect`. If the stored `iv` or `ciphertext` is
    corrupted (Mongo backup restore mid-key-rotation, manual edit, etc.),
    `decrypt` throws `IllegalStateException("AES-GCM decryption failed",
    ...)` and the user can never Disconnect through the UI — every retry
    re-decrypts the same corrupted row. The current behaviour is
    correct in the "fail-loud" philosophy (surface the corruption); the
    alternative is symmetric to AC13b (best-effort against Telegram —
    proceed to local DISCONNECTED even if Telegram cannot be contacted)
    and would leave an orphan webhook. This is a tradeoff, not a bug,
    and the corruption scenario is unrealistic. Recorded as a residual
    that operators should know about.
  - Suggested fix: defer. If a user ever reports "stuck connected bot",
    add an `.onErrorResume(IllegalStateException.class, e -> ...)`
    around the decrypt that logs at ERROR and proceeds with the local
    DISCONNECTED update + clears the encrypted-token fields (same
    AC13b shape). Not worth pre-emptive code; document in the runbook.

### 6. Idiomatic Spring WebFlux patterns

Dimension X — OK.

- Constructor injection everywhere: `BotService`, `BotController`,
  `TokenEncryptor`, `TelegramApiClient` (the `@Autowired` on
  `TelegramApiClient`'s public constructor is required to disambiguate
  between the public and the package-private test-seam constructor —
  documented in Task 2 decisions.md round-1 "Skipped C1").
- All injected dependencies are `final`.
- `@Component` / `@Service` placement matches platform convention:
  `@Component` on stateless infra (`TokenEncryptor`, `TelegramApiClient`),
  `@Service` on the orchestration layer (`BotService`), `@RestController`
  on `BotController`.
- No `static` mutable state. The only `static` data in the feature is
  immutable constants (logger, regex patterns, threshold + TTL + event
  type strings).
- `Mono.zip` / `flatMap` / `then(Mono.defer(...))` chains throughout —
  no imperative `.block()` ladders.
- `Retry.backoff(3, Duration.ofMillis(200)).maxBackoff(Duration.ofSeconds(2))
  .filter(TelegramApiClient::isTransient)` is exactly the Task 2
  specification.
- The 16-hop cause-chain walker (`isTransient`) defends against pathological
  exception chains without an infinite loop. Good defensive design.

#### Low

- **Controller helper trio (`currentUserId` / `capUserAgent` / `extractIp`)
  is now duplicated across three controllers**
  - File / lines: `backend/src/main/java/com/botfunnel/bot/BotController.java:81-104`,
    cross-reference: `backend/src/main/java/com/botfunnel/project/ProjectController.java:125-148`,
    `backend/src/main/java/com/botfunnel/profile/ProfileController.java` (the original).
  - Problem: the comment in `BotController.java:80` says "Copied verbatim
    from ProfileController. A shared util is deferred (no other consumer
    yet)." — but with `BotController` we now have THREE consumers
    (`ProfileController`, `ProjectController`, `BotController`). The
    "deferred" rationale no longer holds; the three copies will diverge.
    A `WebExchangeUtils` (or `RequestContext`) helper in
    `com/botfunnel/common/` is now justified by the rule of three.
    Tracked in Task 8 decisions.md round-1 "Skipped: X-Forwarded-For
    platform observation ... future shared-util refactor that
    consolidates extractIp across ProjectController / ProfileController
    / BotController".
  - Suggested fix: out of scope for 06 per Task 8 decision; raise as a
    cross-cutting refactor backlog item before the next epic adds a
    fourth controller (subscribers, broadcasts).

- **`radix-vue` is in `package.json` but no longer used by the Dialog
  primitives** (dependency hygiene)
  - File / lines: `frontend/package.json:25`
  - Problem: Task 9's deviation note records that shadcn-vue 1.0.3
    scaffolded Dialog over `reka-ui`, but `radix-vue` stayed in deps
    "because removing it is out of scope". `frontend/components/ui/dialog/Dialog.vue`
    imports `reka-ui` exclusively. Leaving `radix-vue` in deps adds
    install time, bundle-bloat risk if anyone later imports from it by
    autocomplete accident, and a slow drift away from the shadcn-vue
    canonical state.
  - Suggested fix: run `grep -r "from 'radix-vue'" frontend/` — if no
    hits, remove `radix-vue` from `dependencies` and re-run
    `pnpm install`. One-line PR.

## Cross-cutting checks (orientation)

These are not separate findings — they are the explicit "I checked X and
X is fine" notes that justify the OK dimensions above.

- **No raw bot token in any log statement.** Every WARN site that
  interpolates a Telegram-side string or exception message routes through
  `TelegramApiClient.scrubTokens(...)`:
  - `TelegramApiClient.java:134, 140` (Telegram 4xx descriptions)
  - `BotService.java:167` (Connect-rollback compensation failure)
  - `BotService.java:224` (Disconnect-path Telegram error)
  - `BotService.java:262, 272` (Redis fail-open — `err.getMessage()`,
    no token surface possible)
- **Brute-force counter Redis keys** follow the `brute:bot-connect:{userId}`
  shape (`BotService.bruteForceKey`, line 277-279).
- **Audit event type names** are exactly `bot_connected` and
  `bot_disconnected` (constants at `BotService.java:33-34`). No
  `bot_test_message_sent` event is emitted in feature 06 — D7 short-
  circuits `sendTestMessage` to a 422 before any event site
  (`BotService.java:113-115`). Verified by reading `sendTestMessage` —
  there is no `eventService.logEvent(...)` call on that path.
- **`onErrorMap` discipline.** No generic `Throwable → AppException`
  mapping that could swallow `DuplicateKeyException`. The
  `compensateAndPropagate → mapPersistError` chain inspects the
  exception type explicitly (`instanceof DuplicateKeyException`) and
  delegates anything else through unchanged.
- **`@AutoCloseable` resources.** None opened in the feature — all
  network I/O goes through the shared `WebClient`, all crypto through
  `Cipher.getInstance(...)` (stateless), all Mongo through the reactive
  repository.
- **Frontend Pinia hygiene.** Token argument lives only in the
  `connect()` call-stack and the `$fetch` body; not on a ref, not in
  the URL, not returned. The `catch` block unconditionally redacts
  `err.options.body` (which ofetch has by-then JSON-stringified) so a
  caller that logs the rejected error cannot leak the secret. Module-
  scoped `inFlight` dedupes by `projectId` and protects against stale
  writes by `promise` identity (A→B→A re-selection regression test
  exists per Task 7 round-3 review).
- **`@JsonIgnore` defense on `Bot` entity.** All four sensitive fields
  (`encryptedTokenCiphertext`, `encryptedTokenIv`, `tokenSuffix`,
  `webhookSecretHash`) carry `@JsonIgnore` so even an accidental
  `ResponseEntity<Bot>` (e.g. a future maintainer drops `BotResponse`)
  cannot leak them.

## Findings by Severity (cross-reference)

- **Critical:** none.
- **High:** none.
- **Medium:**
  - [5] D4 compensation scope is narrower than the contract — encryption
    failure after `setWebhook` succeeds leaves an orphan webhook
    (`BotService.java:131-156`).
- **Low:**
  - [1] Two `SecureRandom` instances across `TokenEncryptor` and
    `BotService` (`TokenEncryptor.java:26`, `BotService.java:66`).
  - [4] `TelegramApiClient` 4xx-fallback returns 400 with no business
    code, risking a false-positive "invalid format" UX
    (`TelegramApiClient.java:141`).
  - [5] Disconnect path: decryption failure on a corrupted bot row
    blocks Disconnect indefinitely (`BotService.java:212-239`).
  - [6] Controller helper trio (`currentUserId` / `capUserAgent` /
    `extractIp`) is now duplicated across three controllers — rule of
    three now triggers (`BotController.java:81-104`).
  - [6] `radix-vue` is in `package.json` but no longer used by the
    Dialog primitives (`frontend/package.json:25`).

## Notes for follow-up tasks

- Task 11 (Security Audit) will deep-dive token leak vectors, OWASP Top
  10, key-rotation posture, X-Forwarded-For trust boundary. This audit
  intentionally stayed shallow on those.
- Task 12 (Test Audit) covers test files; this audit did not read any
  `src/test/...` or `frontend/tests/...`.
- The Medium finding is the only one worth acting on inside the 06
  scope. The five Low findings are either documented "skipped" reviewer
  notes from Tasks 1-9 (1, 6) or natural follow-ups that should be
  picked up in 06b/07 (4, 5, 6).
