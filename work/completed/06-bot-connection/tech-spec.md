---
created: 2026-05-13
status: approved
branch: dev
size: L
---

# Tech Spec: 06-bot-connection

## Solution

Greenfield `com.botfunnel.bot` backend module plus a new `frontend/pages/projects/[projectId]/settings/bot.vue` page, layered on top of established platform patterns (ProjectService.requireOwned, AppException/GlobalErrorHandler, EventService.logEvent, brute-force counter shape from `auth/`, CSRF/CORS/session config from `security/`).

Backend introduces three new building blocks:

1. `common/crypto/TokenEncryptor` — JDK `javax.crypto` AES-256-GCM helper (no new dependency) wrapping `encrypt(plaintext) → {iv, ciphertext}` and `decrypt(iv, ciphertext) → plaintext` around a 32-byte key read at startup from `BOT_TOKEN_ENCRYPTION_KEY`. Fail-fast on missing/short key. Fresh 12-byte IV per encryption, 128-bit tag.
2. `bot/TelegramApiClient` — first reactive `WebClient` bean in the codebase, base URL `https://api.telegram.org` (overridable per profile for tests). Methods: `getMe(token)`, `setWebhook(token, url, secret)`, `deleteWebhook(token)`. Each returns `Mono<TelegramResult<T>>`; transient errors (5xx, IOException, TimeoutException) retried with `Retry.backoff(3, 200ms).maxBackoff(2s)`; 4xx mapped to AppException via `.onStatus(...)`.
3. `bot/BotService` — orchestrates the Connect/Disconnect/Get/TestMessage lifecycle described in user-spec "Как должно работать". Bot persistence in MongoDB `bots` collection with two partial unique indexes filtered to `status="CONNECTED"`: one on `(telegramBotId)` (platform-wide race-proof uniqueness) and one on `(projectId)` (per-project race-proof uniqueness), both backed by service-level pre-checks (defense-in-depth per D1, D5).

Frontend introduces a Settings sub-zone:

- `pages/projects/[projectId]/settings.vue` is refactored into a folder. `settings/index.vue` keeps the existing General + Danger Zone. New `settings/bot.vue` renders the Connect form (token input + BotFather hint) or the Connected view (masked token + Send Test Message + Disconnect) depending on server state. Sub-nav rendered by a shared `SettingsSubnav.vue` component embedded at the top of each settings page.
- `stores/bot.ts` — Pinia setup-store keyed by the active `projectId`, fetched server-authoritative on every page mount; no localStorage (bot state is server-truth-only). Actions: `fetch`, `connect`, `disconnect`, `sendTestMessage`.
- i18n: new `bot.*` keys (page strings) and `errors.bot.{connect|disconnect|testMessage}.*` (status-keyed error messages) added to BOTH `uk.json` and `en.json`; the existing prebuild parity gate fails CI on drift.

Test-message endpoint short-circuits to 422 in 06 (subscriber chat_id lookup is delivered in 06b/Epic 05). The endpoint contract is final — body of `BotService.sendTestMessage` evolves in 06b; controller, request DTO, response DTO, error keys stay.

## Architecture

### What we're building/modifying

**Backend (greenfield module + one shared utility)**

- **`com.botfunnel.bot.Bot`** — `@Document(collection = "bots")` document. Fields: `id`, `projectId`, `telegramBotId`, `telegramUsername`, `telegramFirstName`, `status` (enum: `CONNECTED | DISCONNECTED`), `encryptedTokenCiphertext` (nullable), `encryptedTokenIv` (nullable), `tokenSuffix` (nullable; last 3 characters of the bot-token secret segment, computed at Connect and stored alongside the ciphertext so the UI can render the literal AC18 mask without ever decrypting the token), `webhookSecretHash` (nullable), `connectedAt`, `disconnectedAt`. Class-level `@CompoundIndexes` declares three indexes: `(projectId, status)` non-unique for lookups, `(telegramBotId)` partial unique filtered `{ status: "CONNECTED" }` for platform uniqueness (D1), `(projectId)` partial unique filtered `{ status: "CONNECTED" }` for per-project uniqueness (D5).
- **`com.botfunnel.bot.BotStatus`** — enum (`CONNECTED`, `DISCONNECTED`).
- **`com.botfunnel.bot.BotRepository`** — `ReactiveMongoRepository<Bot, String>` with derived queries `findByProjectIdAndStatus`, `findByProjectId`, `findFirstByTelegramBotIdAndStatus`.
- **`com.botfunnel.bot.BotService`** — orchestration. Injects `BotRepository`, `ProjectService`, `TokenEncryptor`, `TelegramApiClient`, `EventService`, `ReactiveRedisTemplate<String, String>`. Public methods: `getByProject(ownerId, projectId): Mono<Bot>`, `connect(ownerId, projectId, token, ip, ua): Mono<Bot>`, `disconnect(ownerId, projectId, ip, ua): Mono<Void>`, `sendTestMessage(ownerId, projectId, ip, ua): Mono<Void>`. Each operation runs `ProjectService.requireOwned(...)` as its first reactive step.
- **`com.botfunnel.bot.BotController`** — `@RestController @RequestMapping("/api/v1/projects/{projectId}/bot")`. Endpoints: `GET /` (read), `POST /connect`, `POST /disconnect`, `POST /test-message`. Standard `currentUserId()` + `extractIp` + `capUserAgent` helpers (copied verbatim from `ProjectController` per the established convention; extraction to common util is deferred per the explicit comment at `ProjectController.java:124`).
- **`com.botfunnel.bot.TelegramApiClient`** — `@Component` wrapping a `WebClient` configured via Reactor Netty `HttpClient.create().responseTimeout(Duration.ofSeconds(10))`, base URL via `@Value("${app.telegram.base-url:https://api.telegram.org}")`. Methods return reactive results; retry policy + status mapping centralised here so `BotService` only deals with domain errors.
- **`com.botfunnel.bot.dto.*`** — `ConnectBotRequest(@NotBlank @Pattern token)`, `BotResponse(telegramBotId, telegramUsername, telegramFirstName, tokenSuffix, status, connectedAt)`. The `tokenSuffix` field is the last 3 characters of the token secret segment — the only token-derived data that ever leaves the backend (D17). All request DTOs `@JsonIgnoreProperties(ignoreUnknown = true)` per the mass-assignment defense pattern.
- **`com.botfunnel.common.crypto.TokenEncryptor`** — `@Component`, JDK AES-256-GCM, key from `${app.bot.token-encryption-key}` (fail-fast in constructor on missing/short key).
- **`application.properties`** — add `app.bot.token-encryption-key=${BOT_TOKEN_ENCRYPTION_KEY:}` and `app.telegram.base-url=${TELEGRAM_BASE_URL:https://api.telegram.org}`.
- **`.env.example`** — add `BOT_TOKEN_ENCRYPTION_KEY=` placeholder with the "32 bytes / 64 hex chars, NEVER commit a real value" comment.

**Frontend**

- **Route restructure.** `frontend/pages/projects/[projectId]/settings.vue` → `frontend/pages/projects/[projectId]/settings/index.vue` (existing General + Danger Zone moved verbatim). New file `frontend/pages/projects/[projectId]/settings/bot.vue` for the Bot page.
- **`frontend/components/SettingsSubnav.vue`** — horizontal tab strip (`<nav>` with two `<NuxtLinkLocale>`s) embedded at the top of each `settings/*` page.
- **`frontend/stores/bot.ts`** — Pinia setup-store. State: `current: Bot | null`, `inFlight: Promise | null` (duplicate-fetch guard). Actions: `fetch(projectId)`, `connect(projectId, token)`, `disconnect(projectId)`, `sendTestMessage(projectId)`. No `localStorage` writes.
- **`frontend/types/bot.ts`** — TypeScript shape of `BotResponse`.
- **`frontend/i18n/locales/{uk,en}.json`** — add `bot.*` subtree (page strings) and `errors.bot.connect.{400,409,422,429,500,502,generic}`, `errors.bot.disconnect.{404,generic}`, `errors.bot.testMessage.{422,502,generic}`. No 401 key under `errors.bot.connect` — backend never returns 401 from these endpoints (Telegram 401 is mapped to 422 `invalid_bot_token`; auth failures collapse to the global 401 handled by `errors.generic`). The two locale files must stay in lock-step (prebuild parity gate).

### How it works

**Connect (`POST /api/v1/projects/{projectId}/bot/connect` with `{token}`)** runs the following reactive pipeline inside `BotService.connect` (token format already validated by `@Pattern` on the DTO — 400 returned at the controller layer before this method is reached):

```
1. requireOwned(ownerId, projectId, false)                      -> 404 if foreign/soft-deleted/malformed
2. incrementBruteForceCounter(brute:bot-connect:{userId})        -> INCR + EXPIRE(900s when value==1);
                                                                    if returned value > 10: return 429;
                                                                    fail-open on Redis error (log WARN, count failure metric, continue)
3. ensureNoConnectedBotForProject(projectId)                     -> 409 "bot_already_in_project" (D5 pre-check)
4. telegram.getMe(token)                                         -> 422 "invalid_bot_token" on 401; 502 on retry-exhausted 5xx
5. ensureTelegramBotIdNotConnectedAnywhere(botId)                -> 409 "bot_already_connected" (D1 pre-check)
6. webhookSecret = secureRandom16Bytes()
   webhookSecretHash = SHA-256(webhookSecret) as hex
   webhookUrl = "${app.url}/webhooks/telegram/{projectId}"
7. telegram.setWebhook(token, webhookUrl, webhookSecret)         -> 502 on retry-exhausted 5xx; 500 "webhook_config_error" on 4xx with config description (D8)
8. {iv, ciphertext} = tokenEncryptor.encrypt(token)
   tokenSuffix = token.substring(token.length() - 3)              // last 3 chars of secret segment, for UI mask (D17 / AC18)
9. bots.save({..., tokenSuffix, ...})
    .onErrorResume(persistErr ->
        telegram.deleteWebhook(token).onErrorContinue(...)        // compensating, best-effort
            .then(Mono.error(persistErr)))                         // 500 to user (D4 / AC9); on DuplicateKeyException: 409
10. eventService.logEvent(userId, "bot_connected", ip, ua,
                          Map.of("projectId", projectId,
                                 "telegramBotId", botId,
                                 "telegramUsername", username))
11. resetBruteForceCounter(brute:bot-connect:{userId})            -> DEL; fail-open
12. return BotResponse(...)
```

The platform-wide uniqueness defence-in-depth (D1) means step 5 is the service-level pre-check; even if a concurrent request slips between steps 5 and 9, the partial unique index on `(telegramBotId)` filtered to `status="CONNECTED"` raises a `DuplicateKeyException` at step 9. The per-project uniqueness defence-in-depth (D5) works the same way: step 3 is the pre-check, and the partial unique index on `(projectId)` filtered to `status="CONNECTED"` raises a `DuplicateKeyException` at step 9 if two concurrent Connects with different tokens slip past step 3 for the same project. In both cases the exception is caught and mapped to the matching 409 — and triggers the same compensating `deleteWebhook` to roll back the `setWebhook` call from step 7.

**Disconnect (`POST /api/v1/projects/{projectId}/bot/disconnect`)**:

```
1. requireOwned                                          -> 404
2. findByProjectIdAndStatus(projectId, CONNECTED)        -> 404 if none
3. decrypt token in-memory (scoped, immediately dropped)
4. telegram.deleteWebhook(token)                         -> retries on transient; persistent failure: WARN log + proceed (AC13)
5. bots.updateOne({_id}, {$set: {status: DISCONNECTED, encryptedTokenCiphertext: null, encryptedTokenIv: null, tokenSuffix: null, webhookSecretHash: null, disconnectedAt: now}})
6. eventService.logEvent(userId, "bot_disconnected", ip, ua,
                         Map.of("projectId", projectId,
                                "telegramBotId", botId,
                                "webhookDeleted", boolean))
7. return 200
```

**Get (`GET /api/v1/projects/{projectId}/bot`)**:

```
1. requireOwned
2. findByProjectIdAndStatus(projectId, CONNECTED)        -> 404 if none
3. return BotResponse (no token fields whatsoever)
```

**Test Message (`POST /api/v1/projects/{projectId}/bot/test-message`)**:

```
1. requireOwned
2. ensureConnectedBotExists(projectId)                   -> 404 if none
3. return AppException.unprocessableEntity(
       "owner_chat_id_unknown",
       "Send /start to your bot in Telegram first, then try again")  -> 422 (AC15)
```

The full Telegram `sendMessage` integration lives in 06b/Epic 05 — this feature ships the endpoint contract only. The 502 i18n key is reserved for future use.

**Reactive WebClient retry semantics (TelegramApiClient).** Each Telegram method composes:

```
webClient.post()
    .uri("/bot{token}/{method}", token, method)
    .bodyValue(body)
    .retrieve()
    .onStatus(HttpStatusCode::is4xxClientError, this::mapClientError)
    .bodyToMono(TelegramResult.class)
    .timeout(Duration.ofSeconds(10))
    .retryWhen(
        Retry.backoff(3, Duration.ofMillis(200))
             .maxBackoff(Duration.ofSeconds(2))
             .filter(ex -> ex instanceof WebClientResponseException w && w.getStatusCode().is5xxServerError()
                        || ex instanceof IOException
                        || ex instanceof TimeoutException))
```

Client errors are mapped synchronously inside `onStatus` — they never reach the retry filter. Retry only kicks in for transient failures.

### Shared resources

| Resource | Owner (creates) | Consumers | Instance count |
|----------|----------------|-----------|----------------|
| `WebClient` bean wrapped in `TelegramApiClient` | `TelegramApiClient` constructor | `BotService` (and 06b/06c later) | 1 (singleton component) |
| `TokenEncryptor` (32-byte AES key + SecureRandom) | `TokenEncryptor` constructor | `BotService` (and any future at-rest secret module) | 1 (singleton component) |
| `ReactiveRedisTemplate<String, String>` | Spring autoconfig (existing) | `AuthService`, `BotService` | 1 (existing) |
| Reactor Netty `HttpClient` (inside WebClient) | `TelegramApiClient` constructor | `WebClient` only | 1 |

## Decisions

### Decision 1: Defense-in-depth uniqueness (service pre-check + partial unique index)

**Decision:** Two layers guard `bots.telegramBotId` uniqueness: a service-level pre-check via `BotRepository.findFirstByTelegramBotIdAndStatus` AND a Spring Data `@Indexed(unique=true, partialFilter="{ status: \"connected\" }")` index on the `telegramBotId` field. `DuplicateKeyException` from the index is caught and mapped to the same 409 `bot_already_connected` as the pre-check.
**Rationale:** Race window between two concurrent Connect requests is real (two unrelated owners pasting the same token simultaneously); the consequence of a missed conflict is two projects believing they own the same Telegram identity — recovery is messy. The index closes the window definitively while the pre-check keeps the happy-path error message clean. Cost: one extra index + one extra exception-mapping path.
**Alternatives considered:** Service-only pre-check (current platform pattern per `patterns.md` → "Service-level uniqueness pre-check") — rejected because the race window is unacceptable for bot identity. Index-only — rejected because hitting Mongo to discover a duplicate before the encrypt + `setWebhook` chain wastes a Telegram API call and a `deleteWebhook` compensation. Recommendation: update `patterns.md` after this feature lands to note that the index option is acceptable when race semantics are critical.
**Anchors:** AC6, AC12 — and the planning-phase D1 decision in `decisions.md`.

### Decision 2: Webhook secret stored as SHA-256 hash, not in plaintext or encrypted

**Decision:** The per-project webhook secret is generated as a 16-byte random value at Connect time, sent in plaintext to Telegram via `setWebhook`, and stored on our side ONLY as a hex-encoded SHA-256 hash. The plaintext never persists. 06b will verify incoming Telegram updates by hashing `X-Telegram-Bot-Api-Secret-Token` and comparing to the stored hash.
**Rationale:** The webhook secret is as security-sensitive as the bot token. Asymmetric protection (token encrypted, secret plaintext) was flagged as a critical finding by the adequacy validator during user-spec planning. A SHA-256 hash gives stronger protection than AES at lower complexity: a Mongo backup leak reveals only hashes, and an attacker cannot forge incoming updates without the plaintext secret.
**Alternatives considered:** AES-encrypt the secret like the token — rejected (asymmetric protection adds complexity for no functional gain; webhooks need pre-image verification not decryption). Plaintext — rejected (R7).
**Anchors:** AC20 — and planning-phase D2.

### Decision 3: No `keyVersion` field on `bots`; single-key AES-256-GCM

**Decision:** Token encryption uses a single configured key `BOT_TOKEN_ENCRYPTION_KEY`. The `bots` document carries `encryptedTokenCiphertext` and `encryptedTokenIv` only — no per-document key-version marker. If key rotation is ever needed, a one-off migration job re-encrypts the (small) bot table.
**Rationale:** YAGNI per the user-spec adequacy validator. Hardcoded `keyVersion=1` with no rotation logic is bookkeeping with no consumer. The bot table is small enough that a future migration is acceptable.
**Alternatives considered:** `keyVersion: int` field shipping a 1 forever — rejected (clutter with no payoff).
**Anchors:** User-spec "Ограничения" → "No active encryption-key rotation". Planning-phase D3.

### Decision 4: Compensating `deleteWebhook` on Connect-path persist failure

**Decision:** If `setWebhook` succeeds but the subsequent Mongo persist fails, the Connect pipeline issues a best-effort `deleteWebhook` (logged on failure, never rethrown) before returning 500 to the user. Same compensation triggers when the partial unique index raises `DuplicateKeyException` after `setWebhook`.
**Rationale:** Without compensation, a persist failure leaves Telegram with a live webhook the system doesn't know about — orphan, no cleanup path. Symmetric to the Disconnect-path best-effort policy (AC13). Same risk class as R5, now closed.
**Alternatives considered:** Persist before `setWebhook` — rejected (would store a token that maps to no live webhook; impossible to retry `setWebhook` from the user side without a Disconnect/Reconnect round-trip).
**Anchors:** AC9 — planning-phase D4.

### Decision 5: Per-project uniqueness — service pre-check + partial unique index (defense-in-depth)

**Decision:** Two layers guard the "one connected bot per project" rule. (a) Service-level pre-check: `BotService.connect` runs `findByProjectIdAndStatus(projectId, CONNECTED)` before any Telegram call and returns 409 `bot_already_in_project` ("another bot is already connected — disconnect it first") if a row exists. (b) Partial unique index `@CompoundIndex(def="{'projectId':1}", unique=true, partialFilter="{status:'CONNECTED'}")` on the `bots` collection. A `DuplicateKeyException` from this index is caught and mapped to the same 409.
**Rationale:** The platform-wide partial unique index on `telegramBotId` protects against the same Telegram identity in two projects, but does NOT prevent a single project from accumulating two different connected bots. The service pre-check (a) handles the common case cleanly; the index (b) closes the race window for two concurrent Connects with *different* tokens to the same project. Symmetric with D1.
**Alternatives considered:** Service pre-check only — rejected (race window leaves the project in an inconsistent multi-bot state). Index only — rejected (`DuplicateKeyException` post-`setWebhook` requires the compensating `deleteWebhook` path; the pre-check avoids the wasted Telegram call in the common case).
**Anchors:** AC7 (per-project conflict), AC12 (race semantics — extended) — planning-phase D5.

### Decision 6: "Bot not connected" warning banner is out of scope

**Decision:** No cross-page warning banner ("Bot not connected — connect to start receiving subscribers") on the project home / dashboard. Discovery of bot-not-connected state lives in Settings → Bot only for this feature.
**Rationale:** The banner adds cross-page UX surface that depends on a broader dashboard story we haven't designed. Adding it here risks inconsistency with later UX work.
**Anchors:** User-spec "Ограничения" → 'Bot not connected warning banner on project home is out of scope'. Planning-phase D6.

### Decision 7: Test Message endpoint short-circuits to 422 in 06; no `bot_test_message_sent` event emitted in 06

**Decision:** `POST /api/v1/projects/{projectId}/bot/test-message` is implemented in 06 with body `return AppException.unprocessableEntity("owner_chat_id_unknown", "Send /start to your bot in Telegram first, then try again")`. No subscriber lookup attempted. No Telegram call attempted. **No `bot_test_message_sent` audit event emitted** — AC23 ties this event to "each state change" and in 06 the endpoint never produces a state change (no message sent, no chat_id resolved). 06b adds the chat_id lookup + `sendMessage` call AND the event emission on the success branch. Frontend resolves `errors.bot.testMessage.422` either way. The endpoint contract — URL, method, request shape, response shape, error keys — is final; 06b extends only the *body* of `BotService.sendTestMessage`.
**Rationale:** Forward-compatible contract: the endpoint exists once. Deferring it entirely would mean a coordinated 06+06b deploy to add the route. The 422 message is precise enough that users understand the next step; inline UI hint matches. Holding back the audit event in 06 matches "event on state change" — 06 produces no state change.
**Alternatives considered:** Hide the button entirely until 06b lands — rejected (worse UX: button reveal is a coordinated feature gate that we don't have infrastructure for; pre-shipping the visible button lets us run the manual smoke checklist against the Settings → Bot UI from day one). Abstract `OwnerChatIdResolver` interface with stub impl — rejected (YAGNI; abstraction has exactly one future consumer and the contract is too thin to design before 06b's actual lookup logic is known). Emit `bot_test_message_sent` in 06 with `success=false` metadata — rejected (an event named "sent" with `sent=false` is a contradiction; cleaner to defer event emission to 06b).
**Anchors:** AC15, AC23 — planning-phase D7.

### Decision 8: Telegram 4xx vs 5xx on setWebhook map to distinct error classes

**Decision:** Telegram 5xx or network timeout on `setWebhook` after retry exhaustion returns 502 `telegram_unavailable` (transient external). Telegram 4xx with a config-related description (HTTPS required, invalid URL, etc.) returns 500 with backend code `webhook_config_error`; the precise Telegram `description` field lives in operator logs only. Frontend resolves `errors.bot.connect.500` to a generic "Webhook configuration error — contact support" message.
**Rationale:** Conflating the two would hide configuration errors behind a "Telegram is currently unavailable" message and prevent operators from noticing they shipped an HTTP `APP_URL` to production. The user cannot self-correct a config bug, so the user-facing message is intentionally non-actionable; the actionable detail belongs to the operator.
**Anchors:** AC4, AC5 — planning-phase D8.

### Decision 9: MockWebServer (okhttp3) for Telegram simulation in tests [TECHNICAL]

**Decision:** Add `com.squareup.okhttp3:mockwebserver` as `testImplementation` for integration tests of the bot module. Tests start a `MockWebServer` per class, override `app.telegram.base-url` via `@DynamicPropertySource` to point at the mock, and enqueue scripted responses for each Telegram method.
**Rationale:** Lighter than WireMock — no `@AutoConfigureWireMock` Spring magic, no separate dependency tree, no extra Spring context cost per test class. MockWebServer fits the reactive test style (compatible with `WebClient` + `StepVerifier`) and matches the platform's idiom of "do the simplest thing that works".
**Alternatives considered:** WireMock — heavier, brings Spring Cloud Contract integration nobody else uses. Spring `MockServerHttpRequest` — wrong layer (it mocks server-side, we need outbound mock).
**Anchors:** User-spec Testing strategy → "Backend: реактивные интеграционные тесты … с мок-сервером для api.telegram.org".

### Decision 10: Settings route — folder split (Option A from code-research)

**Decision:** Convert `frontend/pages/projects/[projectId]/settings.vue` → `frontend/pages/projects/[projectId]/settings/index.vue` (existing General + Danger Zone moved verbatim). Add `frontend/pages/projects/[projectId]/settings/bot.vue`. Add a horizontal sub-nav component `frontend/components/SettingsSubnav.vue` rendered at the top of every `settings/*` page.
**Rationale:** AC21 mandates this split explicitly. Scales for future Settings additions (API keys, notifications, timezone, see Epic 08+) without further restructuring. Existing sidebar link `/projects/{id}/settings` continues to work because `index.vue` covers the bare path.
**Alternatives considered:** Option B (extend the existing `settings.vue` with a new section) — rejected because it loses the "Settings → Bot" sub-page semantics from user-spec and would force a second restructure when 08 adds API keys.
**Anchors:** AC21.

### Decision 11: Reactive retry policy for Telegram calls [TECHNICAL]

**Decision:** `Retry.backoff(3, Duration.ofMillis(200)).maxBackoff(Duration.ofSeconds(2))` filtered to retry only on (a) `WebClientResponseException` with 5xx status, (b) `IOException`, (c) `TimeoutException`. Total wall-clock for a fully exhausted retry sequence: ~2.6s under maximum backoff (200ms + 400ms + 2s); plus per-attempt 10s response timeout — worst case ~32.6s for a hung Telegram endpoint.
**Rationale:** 3 attempts handles the brief Telegram blips operators observe in practice without exposing the user to long stalls. Backoff parameters keep total latency under a normal HTTP timeout budget on the frontend (~35s default for `useApi` `ofetch`). Filter ensures 4xx (logical errors like invalid token) is NOT retried — it maps straight to a domain exception.
**Alternatives considered:** No retry — rejected (AC4 explicitly requires it). Indefinite retry — rejected (unbounded latency; frontend would time out anyway).
**Anchors:** AC4.

### Decision 12: Telegram base URL is configuration-driven [TECHNICAL]

**Decision:** `app.telegram.base-url=${TELEGRAM_BASE_URL:https://api.telegram.org}` injected into `TelegramApiClient` via `@Value`. Default is the production Telegram endpoint; tests override the property via `@DynamicPropertySource` to point at MockWebServer.
**Rationale:** Test isolation requires it. Production default makes the override invisible in normal operation.
**Alternatives considered:** Hard-code in `TelegramApiClient`, mock the bean in tests — rejected (heavier Spring context manipulation than a property override; less faithful to the production call path).

### Decision 13: HTTP timeouts on Telegram calls [TECHNICAL]

**Decision:** Reactor Netty `HttpClient.create().responseTimeout(Duration.ofSeconds(10))`. Per-method override applied via `.timeout(Duration.ofSeconds(10))` on the returned `Mono` as belt-and-braces.
**Rationale:** Telegram normally responds in <1s; 10s comfortably covers slow paths without letting a hung endpoint stall the Connect flow indefinitely. With 3 retries + max 2s backoff, the worst-case Connect duration is under 35s, which fits within the default frontend HTTP timeout window.

### Decision 14: Brute-force counter — INCR every attempt, DEL on success, observability on fail-open

**Decision:** Single per-user key `brute:bot-connect:{userId}`. INCR on every Connect attempt (both successful and failed attempts contribute to the same counter); EXPIRE 900s applied only when INCR returns 1 (window-stable). DEL on successful Connect. Threshold 10; if the value returned by INCR is > 10, the endpoint returns 429 `errors.bot.connect.429` BEFORE any Telegram call. Fail-open if Redis is unreachable: both INCR and DEL silently no-op the rate-limit logic, but every Redis exception is logged at WARN (`brute_bot_connect_redis_unavailable`) AND a Micrometer counter `botfunnel.bot_connect.brute_force.redis_failures` is incremented so a sustained Redis outage surfaces in operations.
**Rationale:** Per-user-only INCR (no IP key) — the actor is always an authenticated user; per-IP would punish corporate NATs without preventing per-user abuse. INCR-every-attempt (not just on failure) directly implements user-spec AC11 "11th attempt within 15 minutes" — an attacker who chains successful Connect + manual Disconnect can otherwise bypass the limiter; counting every attempt closes that loop. (This is an intentional departure from AuthService's INCR-on-failure-only shape: login has no equivalent "succeed-then-bypass" loop because a successful login terminates the per-email key explicitly.) Fail-open matches DoS-mitigation stance — this is not an auth gate. Observability gap on the existing auth-module fail-open is closed here proactively.
**Alternatives considered:** Per-IP + per-user dual key shape (login pattern) — rejected (Connect is an authenticated endpoint, per-account abuse is the only realistic vector). INCR-on-failure-only (auth pattern) — rejected (lets attacker bypass via Connect-then-Disconnect cycles). Refactor `incrementWithTtl` to a shared `common/RateLimit` helper now — deferred (one more consumer doesn't yet justify the abstraction; document the duplication and revisit when a third consumer arrives).
**Anchors:** AC11.

### Decision 15: TokenEncryptor lives in `common/crypto/` [TECHNICAL]

**Decision:** The AES-256-GCM helper is implemented at `com.botfunnel.common.crypto.TokenEncryptor`, not inside the bot module. Bot module injects it; future at-rest secret consumers (06c sender, 08 API keys when they need symmetric encryption rather than hashing) reuse the same component.
**Rationale:** The class is generic — it takes plaintext, returns ciphertext+iv, knows nothing about bots. Placing it under `bot/` would make a cross-cutting concern look bot-specific.

### Decision 16: Bean-validation regex at the controller layer for token format

**Decision:** `ConnectBotRequest.token` carries `@NotBlank` and `@Pattern(regexp = "^\\d{1,20}:[A-Za-z0-9_-]{30,50}$")`. Malformed tokens flow through `GlobalErrorHandler` → 400 with the standard field-validation body. `BotService.connect` never sees a malformed token.
**Rationale:** Established platform pattern: 400 = bean-validation, 422 = business rule. AC2 explicitly demands "no network call is made" on a format-invalid token — controller-layer rejection guarantees this without touching service code. Pattern is conservative — Telegram tokens always have the shape `<numeric id>:<35-char secret>`; the regex tolerates 30–50 secret chars to be future-proof against minor Telegram changes.
**Anchors:** AC2.

### Decision 17: Token-derived data shipped to the frontend is the 3-char suffix and nothing else

**Decision:** `BotResponse` carries a `tokenSuffix: String` field — the last 3 characters of the bot-token secret segment, computed once at Connect time and persisted alongside the ciphertext on the `Bot` document. The frontend renders the literal AC18 mask `{telegramBotId}:•••...{tokenSuffix}` (e.g. `1234567890:•••...xyz`). Nothing else token-derived ever round-trips: no `maskedToken` string from the server, no `encryptedTokenCiphertext`, no `encryptedTokenIv`. The pure helper signature is `mask(telegramBotId, tokenSuffix): string`.
**Rationale:** User-approved (Phase 6 question) — the literal AC18 form ships. The 3-character suffix exposes ~18 bits of entropy out of the token's ~192 secret bits, which is not cryptographically catastrophic given (a) the public `telegramBotId` is already known by anyone interacting with the bot, (b) the Connect rate limit (D14) prevents online brute-force of the remaining ~174 bits, and (c) the user-visible affordance matches what the user-spec promised in AC18. The "tokens never round-trip" stance from user-spec is preserved in spirit — the FULL token never round-trips; only a deliberately small public fingerprint does.
**Alternatives considered:** Synthesized placeholder with zero token content (`{id}:•••••••••••••••`) — rejected by user during Phase 6 in favor of the literal AC18 form. Server-side computed `maskedToken` field — rejected because it puts non-essential string assembly on the backend; the frontend already has both inputs.
**Anchors:** AC17 (matched fully), AC18 (matched literally with user approval).

### Decision 18: No JobRunr jobs, no scheduled work in this feature

**Decision:** This feature is request/response only. No recurring jobs introduced. Webhook auto-re-registration on Telegram-side drops is feature 06c's concern.
**Rationale:** Keeps the surface area minimal and avoids dragging JobRunr dependencies into the bot module where they're not needed.
**Anchors:** Code research §16.

## Data Models

### MongoDB collection: `bots`

```java
@Document(collection = "bots")
@CompoundIndexes({
    @CompoundIndex(name = "projectId_status", def = "{'projectId': 1, 'status': 1}"),
    @CompoundIndex(name = "telegramBotId_unique_connected",
                   def = "{'telegramBotId': 1}",
                   unique = true,
                   partialFilter = "{ 'status': 'CONNECTED' }"),
    @CompoundIndex(name = "projectId_unique_connected",
                   def = "{'projectId': 1}",
                   unique = true,
                   partialFilter = "{ 'status': 'CONNECTED' }")
})
public class Bot {
    @Id String id;
    @Indexed String projectId;             // FK to projects._id, never null
    Long telegramBotId;                    // public Telegram numeric id, never null
    String telegramUsername;               // e.g. "SmokeTest_XYZ_bot"
    String telegramFirstName;
    BotStatus status;                      // CONNECTED | DISCONNECTED
    String encryptedTokenCiphertext;       // Base64; null when status=DISCONNECTED
    String encryptedTokenIv;               // Base64; null when status=DISCONNECTED
    String tokenSuffix;                    // last 3 chars of token secret segment; null when status=DISCONNECTED; used by UI mask only (D17)
    String webhookSecretHash;              // hex-encoded SHA-256; null when status=DISCONNECTED
    Instant connectedAt;
    Instant disconnectedAt;                // null while CONNECTED
}
```

### DTOs

```java
public record ConnectBotRequest(
        @NotBlank @Pattern(regexp = "^\\d{1,20}:[A-Za-z0-9_-]{30,50}$") String token
) {}
// @JsonIgnoreProperties(ignoreUnknown = true) — applied via Jackson default mixin in common config, OR per-class

public record BotResponse(
        Long telegramBotId,
        String telegramUsername,
        String telegramFirstName,
        String tokenSuffix,        // last 3 chars only — for the UI mask (D17)
        BotStatus status,
        Instant connectedAt
) {}
```

### Telegram API response shapes

```java
record TelegramResult<T>(boolean ok, T result, Integer error_code, String description) {}
record TelegramUser(Long id, boolean is_bot, String first_name, String username) {}
record TelegramSetWebhookResult(boolean ok, boolean result, String description) {}
```

## Dependencies

### New packages

- `com.squareup.okhttp3:mockwebserver:4.x` — `testImplementation` only. Used by integration tests to script Telegram API responses (see D9).

### Using existing (from project)

- `spring-boot-starter-webflux` — already on classpath; provides `WebClient.Builder` autoconfig (code research §5).
- `spring-boot-starter-data-mongodb-reactive` — `ReactiveMongoRepository`, `ReactiveMongoTemplate`.
- `spring-boot-starter-data-redis-reactive` — `ReactiveRedisTemplate<String, String>` (injected the same way as in `AuthService`).
- `spring-boot-starter-security` — security context for `currentUserId()`; CSRF/CORS inherited from existing `SecurityConfig` (no edits per code research §13).
- `spring-boot-starter-validation` — `@NotBlank`, `@Pattern` on `ConnectBotRequest`.
- `javax.crypto` (JDK) — `Cipher.getInstance("AES/GCM/NoPadding")`, `MessageDigest.getInstance("SHA-256")`, `SecureRandom`.
- `com.botfunnel.project.ProjectService.requireOwned` — ownership guard for every endpoint.
- `com.botfunnel.common.AppException` — error factories.
- `com.botfunnel.common.GlobalErrorHandler` — global exception → ErrorResponse mapping (no edits).
- `com.botfunnel.events.EventService.logEvent` — audit log emission.
- `com.botfunnel.auth.AuthService` brute-force primitives — copied into `BotService` (key shape only, no shared util yet).
- Frontend: `useApi`, `useApiError`, `useProjectsStore`, `useI18n`, `useLocalePath`, `<NuxtLinkLocale>`, shadcn-vue Dialog component (for Disconnect confirmation modal).

## Testing Strategy

**Feature size:** L

### Unit tests

**TokenEncryptor:**
- Round-trip: `decrypt(encrypt(plaintext)) == plaintext` for ASCII and Unicode payloads.
- Tampered ciphertext: flipping one byte of ciphertext causes decrypt to throw (GCM tag mismatch).
- Tampered IV: substituting the IV yields a tag-mismatch failure.
- Wrong key: a TokenEncryptor configured with a different 32-byte key cannot decrypt; tag mismatch raised.
- Constructor validation: short key (<32 bytes), non-hex key, missing key — fail-fast with descriptive error at startup.
- Fresh IV per call: encrypt(plaintext) called twice yields different IVs and different ciphertexts.

**Webhook-secret hashing:**
- Random 16-byte secret hashed to 64-hex-char SHA-256 deterministically; same plaintext → same hash; different plaintext → different hash with vanishing collision probability.

**Telegram error mapping (TelegramApiClient):**
- `getMe` 401 → `AppException.unprocessableEntity("invalid_bot_token", ...)`.
- `setWebhook` 5xx (after retry exhaustion) → `AppException` with status 502 and code `telegram_unavailable`.
- `setWebhook` 4xx with description "HTTPS url must be provided" → `AppException` with status 500 and code `webhook_config_error`; Telegram description preserved in the operator log line.
- `deleteWebhook` 5xx (after retry exhaustion) → swallowed and surfaced to caller as a falsey/logged-WARN signal (best-effort semantics, AC13).
- `IOException` mid-call → retried; after retry exhaustion → 502.

**Token masking helper (frontend):**
- Pure function: `mask(telegramBotId, tokenSuffix): string` returns `"${telegramBotId}:•••...${tokenSuffix}"` (the literal AC18 form).
- Edge cases: very small / very large `telegramBotId`; `tokenSuffix` exactly 3 characters; null `tokenSuffix` (when bot is disconnected — component renders only when CONNECTED, but defensively the helper returns an empty string rather than `null...null`).

### Integration tests

`BotControllerIT` extends `AbstractIntegrationTest`. Uses MockWebServer for Telegram API. Each test enqueues scripted responses, performs the HTTP call via `WebTestClient` with `.mutateWith(csrf())` and `@WithMockAppUser`, and asserts both HTTP response and (where relevant) Mongo state + emitted audit events.

- **Happy path connect (AC1, AC8, AC10):** valid token → 200 with body matching the AC1 shape; one `bots` document persisted with `status=CONNECTED`, masked-token-only response, webhook secret stored as hash; `bot_connected` event emitted with PII-safe metadata; webhook URL passed to MockWebServer matches `${app.url}/webhooks/telegram/{projectId}`.
- **Format-invalid token (AC2):** bad regex → 400; MockWebServer receives zero requests.
- **Telegram 401 on getMe (AC3):** scripted 401 → 422; no `bots` document persisted.
- **Telegram 5xx retry exhausted (AC4):** scripted 503 × 4 → 502; no document persisted.
- **Telegram 4xx config error (AC5):** scripted 400 with description "HTTPS url must be provided" → 500 with code `webhook_config_error`; operator log line contains the Telegram description; no `bots` document persisted.
- **Platform-wide uniqueness (AC6):** seed an existing `status=CONNECTED` Bot with telegramBotId=X; attempt Connect with a token whose getMe returns id=X for a different project → 409 `bot_already_connected`.
- **Per-project uniqueness (AC7):** seed an existing `status=CONNECTED` Bot for projectId=P; attempt Connect with a different token for same projectId=P → 409 `bot_already_in_project`.
- **Persist fail compensation (AC9):** force `BotRepository.save` to error (Mockito-spy on a SaveBean); MockWebServer must receive `setWebhook` THEN `deleteWebhook` in that order; 500 returned; no `bots` document committed.
- **Race condition — same token (AC12):** two parallel Connect requests with the same valid token (different users / different projects) (`Flux.range(0,2).parallel(2).flatMap(...)`) — exactly one returns 200, the other returns 409 `bot_already_connected`. MockWebServer receives `setWebhook` twice + `deleteWebhook` once (the compensating call from the loser branch).
- **Race condition — same project, different tokens (D5):** two parallel Connect requests with different valid tokens to the same projectId — exactly one returns 200, the other returns 409 `bot_already_in_project`. MockWebServer receives both `setWebhook` calls + one `deleteWebhook` (compensating from the loser).
- **Rate limit — threshold trip (AC11):** 10 consecutive Connects each scripted to return Telegram 401 (so each maps to 422 from step 4 with the counter incremented at step 2) → the 11th attempt returns 429 with `errors.bot.connect.429` and MockWebServer receives zero further requests on the 11th call. Verify Redis key `brute:bot-connect:{userId}` value is 11 and TTL is between 0 and 900s.
- **Rate limit — DEL on success (AC11):** seed counter at 5; one successful Connect → Redis key is deleted (`exists` returns 0).
- **Redis down — fail-open (AC11):** simulate Redis outage by making `ReactiveRedisTemplate.opsForValue()` throw on `increment`; Connect still succeeds → 200; WARN log line emitted; Micrometer counter `botfunnel.bot_connect.brute_force.redis_failures` incremented.
- **Disconnect happy (AC13a, AC13c):** seed connected Bot; call Disconnect; MockWebServer receives `deleteWebhook`; row updated atomically (status=DISCONNECTED, encryptedTokenCiphertext/encryptedTokenIv/webhookSecretHash all null, disconnectedAt set); 200.
- **Disconnect Telegram down (AC13b):** seed connected Bot; MockWebServer scripted to 503 × 4; Disconnect returns 200; WARN log line emitted; Mongo row updated as in AC13c.
- **Disconnect no row (AC13):** no connected Bot for project → 404.
- **Get bot (AC14):** seed connected; GET returns 200 + AC1 shape. No connected → 404.
- **Test message in 06 (AC15):** seed connected Bot; call test-message → 422 with `errors.bot.testMessage.422`; MockWebServer receives ZERO requests.
- **Anti-enumeration (AC16):** foreign-owned project / soft-deleted project / malformed projectId all return 404 (never 403). Hostile body fields (`ownerId`) are echoed nowhere.
- **No token in response or event metadata (AC17, AC23):** assert no field of any HTTP response body matches the token regex; assert no string value of any event's `metadata` map matches the token regex.
- **Webhook secret in Mongo is hashed (AC20):** after happy-path Connect, read the `webhookSecretHash` field; assert it is 64 hex chars; assert it does NOT match the plaintext secret passed to MockWebServer; assert SHA-256(plaintext) == storedHash.
- **tokenSuffix persisted and returned (AC18, D17):** after happy-path Connect with a token whose secret ends in `xyz`, the persisted `Bot.tokenSuffix` is `"xyz"`; the Connect response and a subsequent GET both echo `tokenSuffix: "xyz"`; no other token-derived field is present in either response body.
- **tokenSuffix cleared on Disconnect:** after Disconnect, the `Bot.tokenSuffix` field is null along with the encrypted token fields and the webhook secret hash.
- **i18n parity:** `pnpm prebuild` exits 0; deliberately remove one `bot.*` key from one locale → prebuild exits 1 (covered by a small smoke step, not a Vitest test).

### Frontend component tests (Vitest + happy-dom)

`tests/pages/projects/[projectId]/settings/bot.spec.ts` mocks `useApi` and asserts:

- Initial mount fetches `/api/v1/projects/{id}/bot`; on 404 renders Connect form with disabled Connect button (empty input).
- Pasting a token enables Connect; clicking triggers a POST; loading state disables input + button.
- On 200, view swaps to Connected — `@username`, first_name, masked token `{telegramBotId}:•••...{tokenSuffix}` (AC18 literal form), Send Test / Disconnect buttons all rendered with the right `data-test` attributes (AC19 modal copy).
- Each error status from the backend connect endpoint (400/409/422/429/500/502) resolves the matching `errors.bot.connect.{status}` key via `useApiError`. (Backend never returns 401 from connect; that auth-failure status would resolve through the global error handler, not under `errors.bot.connect`.)
- Disconnect click opens shadcn Dialog with the exact AC19 copy; Cancel closes without API call; Disconnect button issues POST; on 200 swap back to Connect form.
- Test Message: click → POST; 422 surfaces the explanatory toast.

### E2E tests

**None.** Per user-spec Testing section, real-Telegram coverage is impossible without a stable bot + chat_id. The manual smoke checklist in user-spec "Как проверить" → "Пользователь проверяет" covers the end-to-end golden path on staging.

## Agent Verification Plan

**Source:** user-spec "Как проверить" section.

### Verification approach

Per-task Verify-smoke and Verify-user fields specify localized checks during implementation. After all tasks land, the Pre-deploy QA wave (Final Wave) executes the user-spec "Как проверить — Агент проверяет" matrix:

| Step | Tool | Expected |
|------|------|---------|
| `./gradlew :backend:test` | bash | bot-module unit + integration tests pass |
| `cd frontend && pnpm test` | bash | settings/bot.vue Vitest specs pass |
| `cd frontend && pnpm prebuild` | bash | locale parity gate exits 0 |
| Reflective token-leak assertion in `BotTokenLeakTest` | bash via gradle | masking function output verified; no production path serializes the encrypted-token field of the Bot entity to a string |
| `curl -X POST http://localhost:8080/api/v1/projects/{id}/bot/connect -d '{"token":"bad"}' -H 'Content-Type: application/json'` (after `./gradlew bootRun` against test DB) | curl | 400 + bean-validation body (AC2) |
| `mongosh ... --eval 'db.bots.getIndexes()'` after backend boot in dev profile | mongosh | both partial unique indexes present with filter `{ status: 'CONNECTED' }`: `telegramBotId_unique_connected` and `projectId_unique_connected` |

The user-spec "Пользователь проверяет" checklist (10 manual steps on staging using a real BotFather bot) is the user's responsibility and stored at `docs/staging-smoke/06-bot-connection.md`.

### Tools required

curl, bash, mongosh. **No MCP tools required for this feature** — Telegram MCP / Playwright are not used because:
- Telegram API is mocked in automated tests (MockWebServer).
- Frontend behaviour is covered by Vitest component specs (no end-to-end Playwright spec).
- Staging smoke is human-only (requires creating a BotFather bot and sending `/start` from a personal Telegram account).

Consequence: **no Post-deploy verification task** in the Final Wave — there are no live-environment checks an agent can run autonomously beyond the QA scripts above.

## Risks

| Risk | Mitigation |
|------|-----------|
| R1 — Bot token leak through logs or API responses | AES-256-GCM at rest; `BotResponse` has no token fields; synthesised placeholder mask shown in UI; `BotTokenLeakTest` reflective assertion verifies no production code path serializes the encrypted-token field; logging code paths reviewed against a checklist that disallows the token field in any log call. (D17) |
| R2 — Cross-project information leakage on 409 uniqueness conflict | The 409 response carries a generic message with no foreign project name or owner email. (AC6, D1) |
| R3 — At-rest encryption key compromise | Documented manual procedure: rotate `BOT_TOKEN_ENCRYPTION_KEY` env, admin force-disconnect all bots, owners reconnect. Active rotation infrastructure deferred (D3). The current schema does not carry a per-document key version marker. |
| R4 — Race condition on platform-wide bot uniqueness (same telegramBotId, different projects) | Defense-in-depth: service-level pre-check + partial unique index on `(telegramBotId)` filtered to `status=CONNECTED`. `DuplicateKeyException` mapped to 409 `bot_already_connected`, plus compensating `deleteWebhook`. (D1, D4) |
| R4b — Race condition on per-project bot uniqueness (different tokens, same projectId) | Defense-in-depth: service-level pre-check + partial unique index on `(projectId)` filtered to `status=CONNECTED`. `DuplicateKeyException` mapped to 409 `bot_already_in_project`, plus compensating `deleteWebhook`. (D5, D4) |
| R5 — Orphan webhook in Telegram after Disconnect with Telegram down | Webhook-secret hash is nulled on Disconnect. Any orphan update still hitting our webhook URL fails the secret check in 06b and is rejected. Operations runbook notes the manual `deleteWebhook` curl recipe. |
| R6 — Orphan webhook in Telegram after Mongo write fails post-setWebhook (Connect path) | Compensating `deleteWebhook` in the same request (D4, AC9), best-effort + logged. |
| R7 — Webhook secret leak from Mongo backup | Webhook secret stored as SHA-256 hash, not plaintext. A Mongo dump reveals only hashes; an attacker still cannot forge incoming Telegram updates because they need the plaintext secret to pass the header check. (D2) |
| R8 — Telegram base URL misconfigured (e.g. HTTP `APP_URL` in prod) | Explicit 500 `webhook_config_error` on Telegram 4xx with config description, surfaced in operator logs. User-facing message is non-actionable. (D8) |
| R9 — Redis outage masking brute-force activity | Fail-open by design (DoS mitigation, not auth gate). Logged at WARN level so operator notices sustained Redis problems. (D14) |
| R10 — `MockWebServer` test infra leak through to production classpath | Declared `testImplementation` only in `build.gradle`. Verified by inspecting the produced jar lacks the dependency. (D9) |

None. AC18 mask format is implemented literally — backend persists and returns `tokenSuffix` (last 3 chars of token secret), frontend renders `{telegramBotId}:•••...{tokenSuffix}`. (Round-2 review proposed a zero-leak deviation; user chose the literal AC18 form during Phase 6 approval — see D17 rationale.)

All tech-spec decisions either:
- Directly implement a user-spec AC (D1-D8, D14, D16, D17 trace to specific AC numbers).
- Realize a user-spec "Технические решения" item (D2-D5, D7, D17 mirror the user-spec list).
- Are pure technical decisions ([TECHNICAL]: D9-D13, D15, D18) on test library, route layout, retry parameters, config injection, timeouts, shared-util placement, and sub-nav component — none change the user-facing contract or alter any AC.

## Acceptance Criteria

User-spec AC1–AC23 are the primary acceptance set. Tech-level criteria supplementing them:

- [ ] All API endpoints return the precise HTTP status codes from user-spec AC (200, 400, 401, 404, 409, 422, 429, 500, 502).
- [ ] Both partial unique indexes — `telegramBotId_unique_connected` and `projectId_unique_connected` — exist on the `bots` collection after backend boot in dev profile (`spring.data.mongodb.auto-index-creation=true`), each with filter expression `{ status: 'CONNECTED' }`.
- [ ] All bot-module unit, integration, and frontend Vitest tests pass; no regressions in existing tests (auth, profile, projects, events).
- [ ] `pnpm prebuild` exits 0 with `bot.*` and `errors.bot.*` keys present in both `uk.json` and `en.json`.
- [ ] `BOT_TOKEN_ENCRYPTION_KEY` is documented in `.env.example`; backend fails to boot with a clear error message when the key is missing or wrong length.
- [ ] `app.telegram.base-url` defaults to `https://api.telegram.org`; integration tests override it without touching production behaviour.
- [ ] Reflective `BotTokenLeakTest` asserts no production code path serializes the encrypted-token field of the Bot entity to a string (positive masking-function test).
- [ ] `MockWebServer` lives in `testImplementation` scope only — not present in the built production jar.
- [ ] Bot collection's persist-after-setWebhook failure path is exercised by an integration test that verifies the compensating `deleteWebhook` was invoked.
- [ ] SecurityConfig is not modified (existing `/api/**` authenticated rule and CSRF config inherited verbatim).

## Implementation Tasks

### Wave 1 (parallel foundations — independent)

#### Task 1: TokenEncryptor + configuration scaffolding (AES-256-GCM crypto utility)
- **Description:** Build `com.botfunnel.common.crypto.TokenEncryptor` as a Spring `@Component` providing `encrypt(plaintext) -> {iv, ciphertext}` (12-byte IV generated by `SecureRandom`; 128-bit GCM tag) and `decrypt(iv, ciphertext) -> plaintext`. Constructor reads `${app.bot.token-encryption-key}` and fails fast when the value is missing, not valid hex, or does not decode to exactly 32 bytes. Add `BOT_TOKEN_ENCRYPTION_KEY` to `.env.example` AND adds both properties to `application.properties` in one pass to avoid a Wave 1 conflict with Task 2: `app.bot.token-encryption-key=${BOT_TOKEN_ENCRYPTION_KEY:}` and `app.telegram.base-url=${TELEGRAM_BASE_URL:https://api.telegram.org}`. Used by BotService to encrypt tokens at rest (D3, D15).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/common/crypto/TokenEncryptor.java` (new), `backend/src/main/java/com/botfunnel/common/crypto/EncryptedValue.java` (new record), `backend/src/main/resources/application.properties` (add both `app.bot.token-encryption-key` and `app.telegram.base-url`), `.env.example`
- **Files to read:** `backend/src/main/java/com/botfunnel/auth/TokenService.java`, `backend/src/main/java/com/botfunnel/security/SecurityConfig.java`, `backend/build.gradle`

#### Task 2: TelegramApiClient (reactive WebClient bean)
- **Description:** Build `com.botfunnel.bot.TelegramApiClient` as a Spring `@Component` wrapping a WebClient with base URL from `${app.telegram.base-url}` (property added by Task 1), Reactor Netty response timeout of 10s, and retry policy `Retry.backoff(3, 200ms).maxBackoff(2s)` filtered to 5xx/IOException/TimeoutException. Public methods: `getMe(token)`, `setWebhook(token, url, secret)`, `deleteWebhook(token)`. Map Telegram 4xx to AppException (401 → 422 invalid_bot_token; 4xx with config description → 500 webhook_config_error per D8). Before any WARN log line that echoes a Telegram `description` field, run the value through a token-regex scrubber (`\\d{1,20}:[A-Za-z0-9_-]{30,50}` → `[REDACTED_TOKEN]`) so a Telegram error message that happens to quote the token back at us never leaks into operator logs.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** unit test against a `MockWebServer` enqueueing `{"ok":true,"result":{"id":123,"is_bot":true,"first_name":"x","username":"x_bot"}}` returns a populated `TelegramUser`; enqueueing `503` four times triggers the retry exhaustion path and surfaces a 502-mapped AppException; scrubber unit test verifies a token-shaped substring is redacted.
- **Files to modify:** `backend/src/main/java/com/botfunnel/bot/TelegramApiClient.java` (new), `backend/src/main/java/com/botfunnel/bot/dto/TelegramResult.java` (new), `backend/src/main/java/com/botfunnel/bot/dto/TelegramUser.java` (new), `backend/build.gradle` (add `testImplementation "com.squareup.okhttp3:mockwebserver"`)
- **Files to read:** `backend/src/main/java/com/botfunnel/common/AppException.java`, `backend/src/main/java/com/botfunnel/common/GlobalErrorHandler.java`, `backend/src/main/java/com/botfunnel/email/EmailService.java`

#### Task 3: Bot domain layer (entity, status enum, repository, indexes)
- **Description:** Create `com.botfunnel.bot.Bot` `@Document(collection="bots")` with the schema in tech-spec Data Models section, including three indexes: non-unique compound `(projectId, status)` for lookups, partial unique on `(telegramBotId)` filtered `status=CONNECTED` (D1), partial unique on `(projectId)` filtered `status=CONNECTED` (D5). Create `BotStatus` enum (`CONNECTED`, `DISCONNECTED`). Create `BotRepository extends ReactiveMongoRepository<Bot, String>` with `findByProjectIdAndStatus`, `findByProjectId`, `findFirstByTelegramBotIdAndStatus` derived queries.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** after backend boot in dev profile (`./gradlew bootRun`), `mongosh botfunnel --eval 'db.bots.getIndexes()'` shows all three indexes — including the partial filter on both unique indexes.
- **Files to modify:** `backend/src/main/java/com/botfunnel/bot/Bot.java` (new), `backend/src/main/java/com/botfunnel/bot/BotStatus.java` (new), `backend/src/main/java/com/botfunnel/bot/BotRepository.java` (new)
- **Files to read:** `backend/src/main/java/com/botfunnel/project/Project.java`, `backend/src/main/java/com/botfunnel/project/ProjectRepository.java`, `backend/src/main/java/com/botfunnel/events/Event.java`

#### Task 4: Frontend i18n keys (bot.* and errors.bot.*)
- **Description:** Add `bot.*` (page strings: titles, button labels, hints, modal copy) and the status-keyed error subtrees to both `frontend/i18n/locales/uk.json` and `frontend/i18n/locales/en.json`. Key inventory follows user-spec AC22 verbatim: `errors.bot.connect.{400, 422, 500, 502, 409, 429}`, `errors.bot.testMessage.{422, 502}`, `errors.bot.disconnect.{404}` — no 401 in any subtree (backend never returns 401 from these endpoints). The two locale files must stay in lock-step; the existing `prebuild` parity gate enforces this on every build.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd frontend && pnpm prebuild` exits 0 after the edit; deliberately delete one key from `en.json` and rerun → exits 1.
- **Files to modify:** `frontend/i18n/locales/uk.json`, `frontend/i18n/locales/en.json`
- **Files to read:** `frontend/scripts/check-locales.mjs`, `frontend/composables/useApiError.ts`

#### Task 5: Frontend Settings route restructure (folder split + sub-nav scaffolding)
- **Description:** Convert `frontend/pages/projects/[projectId]/settings.vue` into a folder: move existing General + Danger Zone into `settings/index.vue` (no behaviour change). Create `frontend/components/SettingsSubnav.vue` rendering a horizontal tab strip with two `<NuxtLinkLocale>`s (`/projects/{id}/settings` and `/projects/{id}/settings/bot`). Embed `<SettingsSubnav>` at the top of `settings/index.vue`. The Bot page itself (`settings/bot.vue`) is delivered in Task 9 — only the empty placeholder file is created here so the sub-nav doesn't 404. (D10, AC21).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** navigate to `/projects/{id}/settings` → existing General + Danger Zone render unchanged; sub-nav visible with two tabs; clicking the Bot tab loads `/projects/{id}/settings/bot` (placeholder page).
- **Files to modify:** `frontend/pages/projects/[projectId]/settings.vue` (deleted), `frontend/pages/projects/[projectId]/settings/index.vue` (new — content moved verbatim), `frontend/pages/projects/[projectId]/settings/bot.vue` (new — minimal placeholder importing SettingsSubnav), `frontend/components/SettingsSubnav.vue` (new), `frontend/layouts/default.vue` (verify the sidebar link still resolves)
- **Files to read:** `frontend/pages/projects/[projectId]/settings.vue` (before deletion), `frontend/layouts/default.vue`, `frontend/composables/useApi.ts`, existing routing examples under `frontend/pages/`

### Wave 2 (depends on Wave 1)

#### Task 6: BotService — full lifecycle orchestration
- **Description:** Build `com.botfunnel.bot.BotService` orchestrating Connect, Disconnect, Get, TestMessage flows exactly as detailed in tech-spec Architecture → How it works. Inject `BotRepository`, `ProjectService`, `TokenEncryptor`, `TelegramApiClient`, `EventService`, `ReactiveRedisTemplate`. Implement: brute-force counter (D14, key `brute:bot-connect:{userId}`, threshold 10, TTL 15min, INCR every attempt, DEL on success, fail-open with WARN+metric), per-project pre-check + `DuplicateKeyException` handling on the per-project partial unique index (D5), platform-wide pre-check + `DuplicateKeyException` handling on the platform-wide partial unique index (D1), webhook secret generation + SHA-256 hashing (D2), AES-256-GCM token encryption (D3), compensating `deleteWebhook` on persist failure (D4), Disconnect best-effort `deleteWebhook` (AC13), audit event emission for `bot_connected` (on successful Connect) and `bot_disconnected` (on Disconnect). **No `bot_test_message_sent` event in 06** — per D7, the test-message endpoint short-circuits to 422 with no state change, so no event is emitted; the event is introduced in 06b on the success branch.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** unit tests cover the happy Connect path against mocked dependencies, verifying the order of side effects matches the pipeline in tech-spec Architecture → How it works (requireOwned → INCR brute-force counter → per-project pre-check → getMe → platform-wide pre-check → setWebhook → encrypt → persist → bot_connected event → DEL brute-force counter). Separate unit tests cover: compensating deleteWebhook on persist failure (and on each DuplicateKeyException variant), disconnect-with-Telegram-down WARN log, rate-limit threshold trip at the 11th attempt, Redis-down fail-open with WARN + Micrometer counter, test-message endpoint returns 422 with zero Telegram calls and zero events.
- **Files to modify:** `backend/src/main/java/com/botfunnel/bot/BotService.java` (new)
- **Files to read:** `backend/src/main/java/com/botfunnel/auth/AuthService.java` (brute-force primitives), `backend/src/main/java/com/botfunnel/project/ProjectService.java` (event-emit pattern), `backend/src/main/java/com/botfunnel/events/EventService.java`, `backend/src/main/java/com/botfunnel/common/AppException.java`, `backend/src/main/java/com/botfunnel/bot/TelegramApiClient.java`, `backend/src/main/java/com/botfunnel/common/crypto/TokenEncryptor.java`, `backend/src/main/java/com/botfunnel/bot/Bot.java`

#### Task 7: Frontend bot store + types
- **Description:** Create `frontend/types/bot.ts` mirroring the backend `BotResponse` shape and `BotStatus` enum. Create `frontend/stores/bot.ts` as a Pinia setup-store: state `current: Bot | null`, `inFlight: Promise | null` for duplicate-fetch guard; actions `fetch(projectId)`, `connect(projectId, token)`, `disconnect(projectId)`, `sendTestMessage(projectId)` — all using `useApi()`. No localStorage writes. Watcher on `useProjectsStore().currentProjectId` to refetch on project switch. The `connect(token)` action passes the token only as a POST body — must NOT include it in any URL, error message, or pinia devtools state snapshot.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `frontend/types/bot.ts` (new), `frontend/stores/bot.ts` (new)
- **Files to read:** `frontend/stores/projects.ts`, `frontend/types/project.ts`, `frontend/composables/useApi.ts`

### Wave 3 (depends on Wave 2)

#### Task 8: BotController + DTOs + integration tests
- **Description:** Build `com.botfunnel.bot.BotController` mapping `/api/v1/projects/{projectId}/bot` to `BotService` (GET, POST /connect, POST /disconnect, POST /test-message). Use `@Valid` + bean-validation regex on `ConnectBotRequest.token` for AC2 (D16). Copy `currentUserId() + extractIp + capUserAgent` helpers verbatim from `ProjectController`. Create DTOs `ConnectBotRequest` and `BotResponse` (D17 — no token fields in response). Write full `BotControllerIT` exercising every scenario from tech-spec Testing → Integration tests using MockWebServer for the Telegram API and `@WithMockAppUser` for auth.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew :backend:test --tests "com.botfunnel.bot.*"` passes including the full integration suite. Manual `curl -X POST http://localhost:8080/api/v1/projects/{id}/bot/connect -d '{"token":"bad"}' -H 'Content-Type: application/json'` returns 400 with a bean-validation body (AC2).
- **Files to modify:** `backend/src/main/java/com/botfunnel/bot/BotController.java` (new), `backend/src/main/java/com/botfunnel/bot/dto/ConnectBotRequest.java` (new), `backend/src/main/java/com/botfunnel/bot/dto/BotResponse.java` (new), `backend/src/test/java/com/botfunnel/bot/BotControllerIT.java` (new), `backend/src/test/java/com/botfunnel/bot/BotTokenLeakTest.java` (new)
- **Files to read:** `backend/src/main/java/com/botfunnel/project/ProjectController.java`, `backend/src/test/java/com/botfunnel/project/ProjectControllerIT.java`, `backend/src/test/java/com/botfunnel/AbstractIntegrationTest.java`, `backend/src/test/java/com/botfunnel/profile/WithMockAppUser.java`, `backend/src/main/java/com/botfunnel/bot/BotService.java`

#### Task 9: Frontend Settings → Bot page (Connect form + Connected view + Test/Disconnect)
- **Description:** Replace the placeholder `settings/bot.vue` from Task 5 with the full page: Connect form (token input + BotFather inline hint + Connect button with disabled-while-empty/loading state) when `current == null`, Connected view (`@username` prominent, first_name subtle, masked token `{telegramBotId}:•••...{tokenSuffix}` per D17 / AC18, Send Test Message primary button + "send /start first" hint, Disconnect destructive button) when `current.status == CONNECTED`. **Prereq sub-step:** the shadcn-vue Dialog component is not yet scaffolded in this repo — run `pnpm dlx shadcn-vue@latest add dialog` (or whatever the platform CLI command resolves to) before the first import of `Dialog` from `@/components/ui/dialog`. Disconnect opens that shadcn-vue Dialog with exact AC19 copy. All visible strings via `t('bot.*')`. Error handling: call `const handle = useApiError()` once at the top of `<script setup>`, then `handle(err, 'bot.connect')` / `handle(err, 'bot.disconnect')` / `handle(err, 'bot.testMessage')` at each catch site (matches the composable factory shape established in `useApiError.ts`). Component spec covering happy paths and every error branch from tech-spec Testing → Frontend component tests.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** with a working backend + a real BotFather bot, paste a valid token → Connected view renders within ~5 seconds with the right Telegram identity; Disconnect → modal → Confirm returns to Connect form. (Full 10-step manual smoke checklist runs after deploy per user-spec.)
- **Files to modify:** `frontend/pages/projects/[projectId]/settings/bot.vue` (replace placeholder), `frontend/tests/pages/projects/[projectId]/settings/bot.spec.ts` (new), `frontend/components/ui/dialog/*` (added by the shadcn-vue CLI; commit the generated files), `frontend/package.json` (any new peer-dep the CLI may register)
- **Files to read:** `frontend/pages/projects/[projectId]/settings/index.vue`, `frontend/stores/bot.ts`, `frontend/stores/projects.ts`, `frontend/components/SettingsSubnav.vue`, `frontend/composables/useApi.ts`, `frontend/composables/useApiError.ts`, `frontend/package.json` (verify shadcn-vue CLI availability), shadcn-vue Dialog docs

### Audit Wave

#### Task 10: Code Audit
- **Description:** Full-feature code quality audit. Read all source files created/modified in this feature (from decisions.md + tech-spec "Files to modify"). Review holistically for cross-component issues: duplicate resource initialization, shared resources compliance with Architecture decisions, architectural consistency, AppException usage, reactive-chain correctness (no blocking calls), idiomatic Spring WebFlux patterns. Write audit report.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 11: Security Audit
- **Description:** Full-feature security audit. Read all source files created/modified in this feature. Analyze for OWASP Top 10 across all components, with extra attention to: token-at-rest encryption (key handling, IV randomness, GCM auth tag check), webhook secret SHA-256 storage, mass-assignment in request DTOs, IDOR through projectId, rate-limit bypass, log-line and event-metadata content (no raw tokens), CSRF inheritance, cross-project information disclosure on 409, and the compensating-deleteWebhook code path. Write audit report.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 12: Test Audit
- **Description:** Full-feature test quality audit. Read all test files created in this feature (`TokenEncryptorTest`, `TelegramApiClientTest`, `BotServiceTest`, `BotControllerIT`, `BotTokenLeakTest`, frontend `bot.spec.ts`). Verify coverage of every AC (1–23), meaningful assertions, test pyramid balance, MockWebServer usage correctness, no flaky concurrency anti-patterns (e.g. `Thread.sleep`), audit-event negative assertion (no token regex match) actually runs. Write audit report.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 13: Pre-deploy QA
- **Description:** Acceptance testing: run the full backend test suite (`./gradlew :backend:test`), the full frontend test suite (`cd frontend && pnpm test`), and `pnpm prebuild` for locale parity. Verify every user-spec AC1–AC23 against the produced test reports (mapping AC ↔ test method name). Verify the tech-spec Acceptance Criteria items. Verify the curl smoke against a locally-booted backend (`./gradlew bootRun` + `curl -X POST http://localhost:8080/api/v1/projects/{id}/bot/connect -d '{"token":"bad"}' -H 'Content-Type: application/json'` → 400). Verify the partial unique index exists in dev Mongo (`mongosh ... --eval 'db.bots.getIndexes()'`). Produce a sign-off report listing AC ↔ evidence mapping.
- **Skill:** pre-deploy-qa
- **Reviewers:** none

<!-- No Deploy task: deployment platform is TBD per deployment.md; this feature ships through the existing manual flow once chosen. No Post-deploy verification task: no MCP-automatable live checks (real Telegram requires a human + BotFather + a personal Telegram account, per user-spec). The user-spec "Пользователь проверяет" 10-step manual smoke checklist (docs/staging-smoke/06-bot-connection.md) is the user's responsibility after deploy. -->
