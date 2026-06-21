---
created: 2026-05-15
status: approved
branch: dev
size: L
---

# Tech Spec: 07 — TelegramSender Service (Epic 04c)

## Solution

Add a reactive backend bean `TelegramSender` with a single public method `sendText(botId, chatId, text, parseMode?, ownerId?) → Mono<SentMessage>`. The bean owns the full outbound policy for Telegram `sendMessage` calls: per-call token decryption, transient-failure retry with exponential backoff, 429 `retry_after` honoring as an outer loop wrapping the 5xx loop, overall timeout, mapping of HTTP/payload errors to a small set of typed exceptions extending `AppException`, audit events on success and terminal failure, and token-scrubbed logging.

Two private helpers in the existing `TelegramApiClient` (`requireValidTokenShape`, `isTransient`) are promoted to `public static` so both clients share one transient-failure definition and one token-shape guard — mirrors the path already taken for `scrubTokens`.

The `bots` document gains a nullable `ownerChatId: Long` (no migration script — Spring Data MongoDB reads missing fields as null for wrapper types; precedent: `disconnectedAt`). `BotService.sendTestMessage` is rewritten to branch on this field: when `null`, the existing `422 owner_chat_id_unknown` stub is preserved verbatim; when present, `TelegramSender.sendText` is called with the test message body and an additional `bot_test_message_sent` audit event is written on success. The HTTP contract of `BotController.sendTestMessage` (`ResponseEntity<Void>` 200) does not change. The `Bot.ownerChatId` value is populated by Epic 04b webhook ingestion in a later release; until then the production happy-path is unobservable except via integration tests with seeded Mongo state.

## Architecture

### What we're building/modifying

**New files:**
- `backend/src/main/java/com/botfunnel/bot/TelegramSender.java` — reactive `@Component` with `sendText(...)`; owns retry, 429-loop, timeout, error mapping, and audit emission.
- `backend/src/main/java/com/botfunnel/bot/dto/SentMessage.java` — `record SentMessage(Long chatId, Long messageId, Instant sentAt)`. Returned to caller; carried in success audit metadata.
- `backend/src/main/java/com/botfunnel/bot/dto/TelegramSendResult.java` — `record TelegramSendResult<T>(boolean ok, T result, Integer error_code, String description, TelegramSendParameters parameters)`. Sender-only; parses `parameters.retry_after` for 429.
- `backend/src/main/java/com/botfunnel/bot/dto/TelegramSendParameters.java` — `record TelegramSendParameters(Integer retry_after)`.
- `backend/src/main/java/com/botfunnel/bot/BotTokenInvalidException.java` — extends `AppException(UNPROCESSABLE_ENTITY, "invalid_bot_token", ...)`. Carries `botId` for log context.
- `backend/src/main/java/com/botfunnel/bot/TelegramSendException.java` — extends `AppException(BAD_REQUEST, "telegram_send_failed", ...)`. Carries optional `errorCode`, scrubbed `description`, `attempts`.
- `backend/src/main/java/com/botfunnel/bot/TelegramRateLimitException.java` — internal sentinel for the 429 retry loop; never reaches `GlobalErrorHandler`.
- `backend/src/test/java/com/botfunnel/bot/TelegramSenderTest.java` — unit-style with `MockWebServer` + `ListAppender<ILoggingEvent>`.
- `backend/src/test/java/com/botfunnel/bot/TelegramSenderIT.java` — integration-style (full Spring context, embedded Mongo, MockWebServer for Telegram).
- `docs/staging-smoke/07-telegram-sender.md` — manual checklist for opt-in real-Telegram smoke (seeded `ownerChatId` in Mongo).

**Modified files:**
- `backend/src/main/java/com/botfunnel/bot/Bot.java` — add nullable field `Long ownerChatId` between `telegramFirstName` and `status` (camelCase, no toString override per `BotTokenLeakTest`).
- `backend/src/main/java/com/botfunnel/bot/TelegramApiClient.java` — change `requireValidTokenShape`, `isTransient`, `DEFAULT_RESPONSE_TIMEOUT`, and `CONNECT_TIMEOUT` from `private static` to `public static` (signatures and values unchanged; recompile-clean). Existing 200ms/400ms/2s retry policy and call sites untouched.
- `backend/src/main/java/com/botfunnel/bot/BotService.java` — inject `TelegramSender`; rewrite `sendTestMessage` to branch on `bot.ownerChatId`; add constant `EVENT_BOT_TEST_MESSAGE_SENT = "bot_test_message_sent"`.
- `backend/src/test/java/com/botfunnel/bot/BotServiceTest.java` — update constructor wiring to include `TelegramSender` mock; split `sendTestMessage_returns422_noTelegramCalls_noEvents` into two branches (null + present `ownerChatId`).
- `backend/src/test/java/com/botfunnel/bot/BotControllerIT.java` — add a positive-path test (seed `ownerChatId` in Mongo, enqueue Telegram 200 on `MockWebServer`, assert 200 OK + `bot_test_message_sent` event); existing `postTestMessage_in06_returns422_zeroTelegramCalls_zeroEvents` stays valid as the null-branch case. Note: controller method is named `testMessage` (not `sendTestMessage` — that is the *service* method name); the endpoint is `POST /api/v1/projects/{projectId}/bot/test-message`.

**Unchanged:**
- `BotController.java` — endpoint signature stays `Mono<ResponseEntity<Void>>` on the `testMessage(...)` method.
- `SecurityConfig.java` — `/api/**` is already authenticated; covers `/api/v1/projects/{projectId}/bot/test-message`.
- `BotResponse.java` — `ownerChatId` is internal; not exposed on the API.
- `application.properties` — reuses existing `app.telegram.base-url` and `app.bot.token-encryption-key` properties.
- `build.gradle` — `okhttp3:mockwebserver:4.12.0` already present as `testImplementation`.

### How it works

**Sender flow (`sendText(botId, chatId, text, parseMode?, ownerId?)`):**

**Outermost-closure state.** Allocate `AtomicInteger attempts = new AtomicInteger(0)` in the outermost lambda (BEFORE `Mono.defer`/`Mono.fromSupplier` that builds the per-attempt request). This single counter survives both retry loops and matches AC14's "загальна кількість HTTP-запитів" semantics. A counter declared inside the inner request supplier would reset on every 429-resubscription.

1. `botRepository.findById(botId)` → `Mono.empty()` if not found.
2. Filter `bot.status == CONNECTED`; otherwise `AppException.notFound(BotService.MESSAGE_BOT_NOT_FOUND)` (uniform anti-enumeration; same shape as `requireOwned`). Note: `AppException.notFound(String)` is the single-arg factory at `AppException.java:36-38` — it sets `message` (not `code`). The wire shape is `{ status: 404, code: null, message: "Bot not found" }`, matching the existing `BotService.requireConnectedBot` precedent. Symbolic identifier `bot_not_found` used throughout this tech-spec is a SHORTHAND for this anti-enumeration 404 case, not a literal `code` value in the response body.
3. Decrypt token via `tokenEncryptor.decrypt(iv, ciphertext)` (Base64 decode boundary at the call site, mirroring `BotService.disconnect:216-218`). The catch is **scoped to the Base64 decode step only**: catch `IllegalArgumentException` from `Base64.getDecoder().decode(...)` and map to `BotTokenInvalidException(botId, "decryption failed")`. **Do NOT catch `IllegalStateException` from `TokenEncryptor.decrypt`** — `TokenEncryptor` wraps every `GeneralSecurityException` (including `AEADBadTagException` from a corrupted/tampered ciphertext) into `IllegalStateException("AES-GCM decryption failed", cause)`, and a misconfigured `TokenEncryptor` bean (missing key, framework wiring failure) also surfaces as `IllegalStateException`. Because these two failure modes are indistinguishable at the call site, both propagate as a 500 via `GlobalErrorHandler.handleThrowable` — this is consistent with the existing `BotService.disconnect` precedent which makes the same trade-off. Differentiating corrupted ciphertext vs misconfigured bean is deferred (Risk R7) and tracked outside this feature. A unit test stubs `TokenEncryptor` to throw `IllegalStateException` and asserts the exception propagates without `BotTokenInvalidException` translation; this single test covers both the misconfig and the tampered-ciphertext scenarios.
4. **Immediately** call `TelegramApiClient.requireValidTokenShape(plaintext)` on the decrypted result — BEFORE constructing the URI or building the body. On `IllegalArgumentException` → `BotTokenInvalidException(botId, "invalid token shape after decrypt")`. This ordering is non-negotiable: shape validation must precede the wire call to prevent a tampered-Mongo token from being concatenated into the WebClient URI template.
5. Build POST body via `Map.of("chat_id", chatId, "text", text)`; if `parseMode != null`, add `"parse_mode" → parseMode`. (Wire format is snake_case via map keys — no DTO needed; mirrors `TelegramApiClient.setWebhook`.)
6. WebClient call: `webClient.post().uri("/bot{token}/sendMessage", token).bodyValue(body).retrieve()`. The `{token}` placeholder is path-encoded by the `DefaultUriBuilderFactory(EncodingMode.NONE)` template substitution — same shape as `TelegramApiClient`.
7. `.doOnSubscribe(sub -> attempts.incrementAndGet())` — increment on every subscription = every HTTP attempt (initial + each 5xx retry + each 429 retry). First attempt = 1.
8. `.onStatus(HttpStatusCode::is4xxClientError, this::map4xx)` — 401 → `BotTokenInvalidException`; 429 → parse `parameters.retry_after`, throw internal `TelegramRateLimitException(retryAfterSeconds)`; other 4xx → `TelegramSendException(errorCode, scrubbedDescription, attempts.get())`.
9. `.bodyToMono(new ParameterizedTypeReference<TelegramSendResult<JsonNode>>() {})` then map: `ok=true && result.message_id != null` → `SentMessage(chatId, messageId, Instant.now())`; `ok=false` → `TelegramSendException` with scrubbed description.
10. `.retryWhen(transientRetry)` — inner loop: `Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(4)).filter(TelegramApiClient::isTransient).onRetryExhaustedThrow((spec, retrySignal) -> new TelegramSendException(null, "transient_failure_exhausted", attempts.get()))`.
11. `.retryWhen(rateLimitRetry)` — outer loop: filter on `TelegramRateLimitException`, delay `Math.max(0, Math.min(retryAfter, 30)) + jitter(0..200ms)` seconds, then resubscribe. The `Math.max(0, ...)` clamp prevents a Reactor spin loop when Telegram returns negative or missing `retry_after` (Reactor treats negative `Duration` as immediate). Outer 30s timeout (step 12) is the only cap on this loop.
12. `.timeout(Duration.ofSeconds(30))` — overall outer cap. On expiry → `TelegramSendException(null, "timeout", attempts.get())`.
13. `.doOnSuccess(sm -> eventService.logEvent(ownerId, EVENT_TELEGRAM_MESSAGE_SENT, null, null, sentMetadata(botId, sm)))` and `.doOnError(ex -> { if terminal: eventService.logEvent(ownerId, EVENT_TELEGRAM_SEND_FAILED, null, null, failedMetadata(botId, chatId, ex, attempts.get())) })`. The `if terminal` guard skips audit emission for transient or 429 exceptions consumed by the retry loops above.

**Plaintext-token lifetime in memory.** The decrypted token is captured in the chain closure (Mono pipeline) and is reachable until subscription completes — up to the 30s overall timeout in the worst case (large `retry_after` waits + transient retries). It is never assigned to a field, never logged, never serialized, and not pushed to background schedulers. Closure capture is the existing `BotService` convention (`BotService.java:216-220`); this feature does not change the lifetime model.

**Scrubber coverage.** All log sites that echo Telegram `description` or transport-error messages wrap the string in `TelegramApiClient.scrubTokens(...)` before passing to `log.warn`/`log.error`. The required sites are: 4xx error path in `map4xx` (WARN), `ok=false` mapper (WARN), 5xx retry attempts (WARN at `retryWhen(...)` `doBeforeRetry`), 429-wait announcement (WARN inside `rateLimitRetry`), terminal failure log inside `doOnError` (ERROR), overall-timeout log (ERROR).

**WebClient construction.** Constructor mirrors `TelegramApiClient.java:51-61`: `HttpClient.create().responseTimeout(TelegramApiClient.DEFAULT_RESPONSE_TIMEOUT).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) TelegramApiClient.CONNECT_TIMEOUT.toMillis())`, `DefaultUriBuilderFactory(baseUrl)` with `EncodingMode.NONE`, `WebClient.Builder` injected. Reuses the same `app.telegram.base-url` property and the same `CONNECT_TIMEOUT` (5s) / `DEFAULT_RESPONSE_TIMEOUT` (10s) static constants. AC5 satisfied without re-declaring constants.

**BotService integration (`sendTestMessage(ownerId, projectId, ip, userAgent)`):**

1. `requireConnectedBot(ownerId, projectId)` (unchanged precondition).
2. If `bot.getOwnerChatId() == null` → `Mono.error(AppException.unprocessableEntity("owner_chat_id_unknown", "Send /start to your bot in Telegram first, then try again"))` (verbatim stub from `BotService.java:113-115`).
3. Else: `telegramSender.sendText(bot.getId(), bot.getOwnerChatId(), TEST_MESSAGE_BODY, null, ownerId)` → `.doOnSuccess(sm -> eventService.logEvent(ownerId, EVENT_BOT_TEST_MESSAGE_SENT, ip, userAgent, testMessageMetadata(bot, sm)))` → `.then()`.
4. On failure, exception propagates through `GlobalErrorHandler`. **No** double-write of `bot_test_message_failed` — sender already wrote `telegram_send_failed`.

`TEST_MESSAGE_BODY = "Hello from Bot Funnel Service! Bot connected ✅"` (per user-spec Сценарій 2).

### Shared resources

| Resource | Owner (creates) | Consumers | Instance count |
|----------|----------------|-----------|----------------|
| `WebClient` for Telegram base URL | `TelegramSender` constructor | `TelegramSender` only | 1 (singleton, distinct from `TelegramApiClient`'s instance) |
| `TokenEncryptor` (AES-256-GCM helper) | `common/crypto` (existing singleton) | `BotService`, `TelegramSender` | 1 (singleton) |
| `BotRepository` (reactive Mongo) | Spring Data | `BotService`, `TelegramSender` | 1 (singleton) |
| `EventService` (audit log) | `events/` module | `BotService`, `TelegramSender` (and many others) | 1 (singleton) |

`TelegramSender` and `TelegramApiClient` each construct their own `WebClient` instance because retry policy, timeout configuration, and audit semantics differ. Both reuse the same `app.telegram.base-url` property and the same `WebClient.Builder` injected by Spring; the construction shape (HttpClient → ReactorClientHttpConnector → uriBuilderFactory) is mirrored, not shared.

## Decisions

### Decision 1: Single `sendText` method in MVP

**Decision:** Public surface is one method: `sendText(botId, chatId, text, parseMode?, ownerId?)`. `sendWithButtons`, `sendImage`, `editMessage`, `deleteMessage` are explicitly deferred.
**Rationale:** Supports AC1 + AC4. No caller in 04c needs the other methods; adding them now would carry untested code into the codebase. YAGNI.
**Alternatives considered:** Implement the full `MessageSender` API up-front — rejected because Epics 06/07 will design caller-driven shapes (broadcast batching, button callbacks); guessing now risks rework.

### Decision 2: No decrypted-token cache (per-call decrypt)

**Decision:** Decrypt token on every `sendText` call inside the chain closure; never store the plaintext beyond Mono completion.
**Rationale:** Supports AC3 + Constraints ("Token decrypted per-call (без кешу в MVP — додамо коли Epic 07 broadcast load покаже need)"). Per-call AES-GCM decrypt is microseconds; broadcast load (Epic 07) will profile and decide whether a TTL cache is justified.
**Alternatives considered:** Caffeine cache keyed by `botId` with short TTL — rejected as premature; introduces invalidation complexity (token rotation) without measured need.

### Decision 3: Audit events written by the sender, not the caller

**Decision:** Sender writes `telegram_message_sent` on success and `telegram_send_failed` on terminal failure. Each caller may write additional domain-level events (e.g., `bot_test_message_sent` from `BotService`).
**Rationale:** Supports AC14. Guarantees an audit trail for every outbound send regardless of caller correctness; future Epic 06/07 workers inherit this for free.
**Alternatives considered:** Caller-only audit — rejected because every caller would re-implement the same logic and any caller bug would silently lose the trail.

### Decision 4: `ownerId` as optional parameter

**Decision:** `sendText` accepts `ownerId?` (nullable `String`). Caller passes the user context when known; `null` is acceptable for system-context callers (Epic 06/07 workers).
**Rationale:** Supports AC1 + AC14. `EventService.logEvent` already accepts `null userId` (precedent: `AuthService.java:301` for unresolved login failures). No new "system user" sentinel needed.
**Alternatives considered:** Introduce a `SYSTEM_USER_ID` sentinel — rejected; no existing precedent and would require schema-level coordination across modules.

### Decision 5: Three exceptions; two extend `AppException`, one is internal sentinel

**Decision:** `BotTokenInvalidException` and `TelegramSendException` extend `AppException` (uniform mapping in `GlobalErrorHandler`). `TelegramRateLimitException` is an internal class, never propagated past the 429 retry loop.
**Rationale:** Supports AC13. `AppException` extension matches `bot/` module precedent (`AppException.unprocessableEntity` for `invalid_bot_token` already used by `TelegramApiClient.toAppException`); typed internal class avoids the sentinel-string anti-pattern in `.retryWhen`.
**Alternatives considered:** Single `TelegramException` with discriminator field — rejected because retry filter logic needs `instanceof` checks, and single-class hierarchies muddy the typed-exception model already established.

### Decision 6: Default `parse_mode = null` (Telegram plain text)

**Decision:** `parseMode` is an optional argument; when `null`, the wire body omits the field entirely. Telegram interprets that as plain text.
**Rationale:** Supports AC4 + Decision in user-spec ("HTML як opt-in"). HTML default would create injection risk for any future caller passing user-generated content; plain-text default is the safe baseline. Test message body is plain text.
**Alternatives considered:** Default `HTML` — rejected; raises XSS-equivalent risk in Telegram-rendered messages from Epic 06 (funnel) and Epic 07 (broadcast) when content includes user-supplied substrings.

### Decision 7: 404 for `DISCONNECTED` bots (anti-enumeration)

**Decision:** Sender returns the same `404` (anti-enumeration shape, message `"Bot not found"`, `code: null`) for both "bot doc not found" and "bot exists but status ≠ CONNECTED".
**Rationale:** Supports AC2. Mirrors the platform-wide `requireOwned` pattern (`patterns.md` → "Anti-enumeration 404") and the existing `BotService.requireConnectedBot` 404 (using `AppException.notFound(MESSAGE_BOT_NOT_FOUND)`); leaking "exists but disconnected" to a system-context caller adds no value and breaks the uniform shape. Symbolic identifier `bot_not_found` used as shorthand in this spec is NOT a literal `code` field — `AppException.notFound(String)` sets only `message`, not `code` (see `AppException.java:36-38`).
**Alternatives considered:** Distinct `409 bot_disconnected` — rejected; sender callers (BotService, future workers) treat both as terminal.

### Decision 8: Overall 30s timeout as the single effective ceiling

**Decision:** `.timeout(Duration.ofSeconds(30))` wraps the entire chain. Theoretical retry budget (4 attempts × up to 10s read + 1s+2s+4s backoff ≈ 47s + indefinite 429 loop) exceeds 30s by design.
**Rationale:** Supports AC8. Frontend default `ofetch` timeout is ~35s; sender must fail before the frontend gives up, so the user sees a coherent error rather than a hung request. Defense against pathological 429 `retry_after` values.
**Alternatives considered:** Tighter per-step caps that sum to < 30s — rejected as brittle; the 30s outer cap is the simplest correct ceiling and lets transient failures use as much budget as they need within it.

### Decision 9: Separate `TelegramSendResult<T>` record (do not extend `TelegramResult<T>`)

**Decision:** New `TelegramSendResult<T>(ok, result, error_code, description, parameters)` record + `TelegramSendParameters(retry_after)` lives in `bot/dto/`. Existing `TelegramResult<T>` is untouched.
**Rationale:** Supports AC7 (parse `retry_after`) + technical decision in user-spec. Lower blast radius — `TelegramApiClient.mapClientError` builds an empty `TelegramResult<>` fallback at lines 122-123 that would need re-tooling if the record gained a fifth field.
**Alternatives considered:** Add a fifth nullable field to `TelegramResult<T>` — rejected; breaks the lean shape used by `getMe`/`setWebhook`/`deleteWebhook`.

### Decision 10: 429 retry loop wraps 5xx retry loop (outer/inner ordering)

**Decision:** `.retryWhen(transientRetry)` chained first (inner), `.retryWhen(rateLimitRetry)` chained second (outer). Reactor's `.retryWhen` operators stack such that the second-applied wraps the first.
**Rationale:** Supports AC7. With wrong order (5xx outer / 429 inner), the 3-attempt 5xx counter would exhaust during a `retry_after` wait, mis-classifying a recoverable 429 as a transient failure. Verified by AC19's interleaving test (`[5xx, 429(retry_after=1), 5xx, 200]` → success at attempts=4).
**Alternatives considered:** Single combined retry with case logic — rejected; harder to reason about cap exhaustion and harder to test in isolation.

### Decision 11: Separate `TelegramSender` bean (not a method on `TelegramApiClient`)

**Decision:** New `@Component TelegramSender` with its own `WebClient`, retry policy, and audit semantics. `TelegramApiClient` continues to host bot-connection methods (`getMe`, `setWebhook`, `deleteWebhook`).
**Rationale:** Supports AC1 + AC14. Connect-flow retry is 200ms/400ms/2s (user is waiting on a form); send-flow retry is 1s/2s/4s (Telegram needs time to recover from 5xx). Connect-flow is a one-shot user action with no audit; send-flow writes per-call audit events. Mixing semantics in one class would obscure both.
**Alternatives considered:** Add `sendMessage` to `TelegramApiClient` and switch retry policy by method — rejected; turns the class into a switch statement and confuses readers about which retry budget applies where.

### Decision 12: Promote `requireValidTokenShape`, `isTransient`, `DEFAULT_RESPONSE_TIMEOUT`, and `CONNECT_TIMEOUT` to `public static` on `TelegramApiClient` `[TECHNICAL]`

**Decision:** Change two helpers (`requireValidTokenShape(String)`, `isTransient(Throwable)`) and two constants (`DEFAULT_RESPONSE_TIMEOUT = 10s`, `CONNECT_TIMEOUT = 5s`) from `private static` to `public static`; signatures and values unchanged. `TelegramSender` calls/reads them directly.
**Rationale:** `[TECHNICAL]` — not derived from a user-spec requirement, but required to honor user-spec AC5 ("Reuse констант з TelegramApiClient") and Risk R2 (avoid duplicating helpers across both clients). Mirrors the pre-existing promotion of `scrubTokens` (`TelegramApiClient.java:171`) — same pattern, same justification. The unrelated `MONO_TIMEOUT` and `CAUSE_CHAIN_MAX_HOPS` constants stay private — TelegramSender has its own outer timeout (30s, Decision 8) and inherits the cause-chain depth via `isTransient` itself.
**Alternatives considered:** Duplicate helpers/constants in `TelegramSender` (private copies) — rejected; two sources of truth for "what counts as transient" or "what is the connect timeout" is a future-bug magnet. Extract constants to a new `bot/TelegramConfig.java` — rejected as over-engineering for four module-local items.

### Decision 13: Retry ladder 1s/2s/4s for sender vs 200ms/400ms/2s for `TelegramApiClient`

**Decision:** `Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(4))`. `TelegramApiClient` keeps its `Retry.backoff(3, Duration.ofMillis(200)).maxBackoff(Duration.ofSeconds(2))`.
**Rationale:** Supports AC6. Connect path is fast because the user is waiting in a form; send path tolerates longer pauses because Telegram needs time to recover from 5xx (rate-limit and infrastructure issues are common). Different UX vs reliability tradeoffs to the same external API.
**Alternatives considered:** One shared retry policy — rejected; would either degrade Connect UX or under-protect Send reliability.

### Decision 14: Add `Bot.ownerChatId` as nullable `Long` without migration script

**Decision:** Java field `private Long ownerChatId;` with public getter/setter; no Mongo migration. No `@Indexed` annotation. No `toString` override.
**Rationale:** Supports AC16. Spring Data MongoDB reads missing fields as `null` for wrapper types — this is documented Spring Data behavior (no prior dedicated test in this codebase). The new integration test `sendTestMessage_legacyDocumentReadsAsNull` is the first explicit verification. The `disconnectedAt` field on `Bot` (set to null on the Disconnect path at `BotService.java:233`) demonstrates the *write* side of the same nullable-wrapper convention. Avoiding `toString` honors `BotTokenLeakTest.botEntityDoesNotDeclareToStringExposingEncryptedTokenFields`.
**Alternatives considered:** Mongo migration script setting `ownerChatId: null` on legacy docs — rejected; redundant. Index — rejected; no query path filters by `ownerChatId` (sender uses `findById`).

### Decision 15: `BotController.testMessage` HTTP contract preserved (`Mono<ResponseEntity<Void>>`)

**Decision:** Endpoint signature unchanged; success returns 200 with empty body. The controller method is `testMessage(...)` (the *service* method is `sendTestMessage` — distinct names; this decision is about the controller surface). `messageId` is captured in the audit event metadata, never exposed on the API.
**Rationale:** Supports AC17. UI shows a generic success toast on 200; no caller of the endpoint reads `messageId`. Avoids creating a `TestMessageResponse` DTO and a corresponding frontend type.
**Alternatives considered:** Return `{messageId, sentAt}` body — rejected; new DTO + new type generation for zero UI value.

## Data Models

### New domain types

```java
// backend/src/main/java/com/botfunnel/bot/dto/SentMessage.java
public record SentMessage(Long chatId, Long messageId, Instant sentAt) {}

// backend/src/main/java/com/botfunnel/bot/dto/TelegramSendResult.java
public record TelegramSendResult<T>(
        boolean ok,
        T result,
        Integer error_code,
        String description,
        TelegramSendParameters parameters) {}

// backend/src/main/java/com/botfunnel/bot/dto/TelegramSendParameters.java
public record TelegramSendParameters(Integer retry_after) {}
```

### New exceptions

```java
// backend/src/main/java/com/botfunnel/bot/BotTokenInvalidException.java
public class BotTokenInvalidException extends AppException {
    private final String botId;
    public BotTokenInvalidException(String botId, String reason) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_bot_token", reason);
        this.botId = botId;
    }
    public String getBotId() { return botId; }
}

// backend/src/main/java/com/botfunnel/bot/TelegramSendException.java
public class TelegramSendException extends AppException {
    private final Integer errorCode;
    private final int attempts;
    public TelegramSendException(Integer errorCode, String scrubbedDescription, int attempts) {
        super(HttpStatus.BAD_REQUEST, "telegram_send_failed", scrubbedDescription);
        this.errorCode = errorCode;
        this.attempts = attempts;
    }
    public Integer getErrorCode() { return errorCode; }
    public int getAttempts() { return attempts; }
}

// backend/src/main/java/com/botfunnel/bot/TelegramRateLimitException.java
// Internal-only — never propagated past the 429 retry loop. No HTTP mapping.
class TelegramRateLimitException extends RuntimeException {
    private final int retryAfterSeconds;
    TelegramRateLimitException(int retryAfterSeconds) { this.retryAfterSeconds = retryAfterSeconds; }
    int getRetryAfterSeconds() { return retryAfterSeconds; }
}
```

### `Bot.ownerChatId` (Mongo schema delta)

Add nullable wrapper field on `backend/src/main/java/com/botfunnel/bot/Bot.java`, between `telegramFirstName` and `status`:

```java
private Long ownerChatId;  // nullable; populated by Epic 04b webhook ingestion
public Long getOwnerChatId() { return ownerChatId; }
public void setOwnerChatId(Long ownerChatId) { this.ownerChatId = ownerChatId; }
```

No new index. No migration. Existing documents read back with `ownerChatId == null`.

### Event types (audit log)

Two new event types written by `TelegramSender`:
- `telegram_message_sent` — metadata `{ botId, chatId, messageId }`. Fired on success.
- `telegram_send_failed` — metadata `{ botId, chatId, errorCode? (Integer), errorDescription? (String, scrubbed), attempts (int) }`. Fired on terminal failure.

One new event type written by `BotService`:
- `bot_test_message_sent` — metadata `{ projectId, telegramBotId, chatId, messageId }`. Fired on successful test-message send (in addition to the sender's `telegram_message_sent`).

All events go through `EventService.logEvent(userId, eventType, ip, userAgent, metadata)`. `userId = ownerId` for `telegram_*` events (may be `null` for system-context callers); `ip` and `userAgent` are `null` for sender-emitted events. `bot_test_message_sent` carries the controller-supplied `ip` and `userAgent`.

## Dependencies

### New packages
- None.

### Using existing (from project)
- `org.springframework.web.reactive.function.client.WebClient` — outbound HTTP, mirrors `TelegramApiClient` construction.
- `reactor.util.retry.Retry` — `Retry.backoff` for inner 5xx loop, `Retry.from` for outer 429 loop.
- `com.botfunnel.common.crypto.TokenEncryptor.decrypt(byte[], byte[])` — per-call token decryption.
- `com.botfunnel.bot.BotRepository.findById(String)` — load bot doc.
- `com.botfunnel.bot.TelegramApiClient.scrubTokens(String)` — already public; reused in WARN/ERROR log sites.
- `com.botfunnel.bot.TelegramApiClient.requireValidTokenShape(String)` — promoted to public in this feature.
- `com.botfunnel.bot.TelegramApiClient.isTransient(Throwable)` — promoted to public in this feature.
- `com.botfunnel.events.EventService.logEvent(String, String, String, String, Map<String,Object>)` — fire-and-forget audit emission.
- `com.botfunnel.common.AppException.unprocessableEntity(String, String) / .badRequest(String) / .notFound(String)` — error factories.
- `okhttp3.mockwebserver.MockWebServer` — already a `testImplementation` (no build change).
- `ch.qos.logback.core.read.ListAppender` + `ch.qos.logback.classic.spi.ILoggingEvent` — token-leak assertion in tests; pattern from `TelegramApiClientTest`.
- `org.awaitility.Awaitility` — async event verification in `TelegramSenderIT`. Available transitively via `spring-boot-starter-test` (no explicit `build.gradle` declaration required); already used by `BotControllerIT` (lines 228-232) so the dependency path is validated.
- `com.fasterxml.jackson.databind.JsonNode` — parse arbitrary `result` payload from Telegram's `sendMessage` response without a typed DTO.

## Testing Strategy

**Feature size:** L

### Unit tests (`TelegramSenderTest.java` — MockWebServer, no Spring context)

**Happy path and field shape:**
- `sendText_happyPath_returnsSentMessage_attemptsEqualsOne` — enqueue 200 with `{ok: true, result: {message_id: 42, chat: {id: 5}}}` → assert `SentMessage.messageId == 42`, `SentMessage.chatId == 5`, `request count == 1`, audit event `telegram_message_sent` written with metadata `{botId, chatId: 5, messageId: 42}` (assert metadata map shape, not just presence).
- `sendText_parseModeNull_omitsFieldFromBody` — happy path with `parseMode=null` → assert `MockWebServer` request body parses to JSON without a `parse_mode` key.
- `sendText_parseModeHtml_includesFieldInBody` — happy path with `parseMode="HTML"` → assert request body has `"parse_mode": "HTML"`.
- `sendText_ownerIdNull_succeeds_eventWrittenWithNullUserId` — happy path with `ownerId=null` (system-context caller) → assert success and that `EventService.logEvent` is invoked with `userId=null`, mirroring `AuthService.java:301` precedent. Covers AC14 + Constraints "ownerId optional".

**5xx retry semantics:**
- `sendText_5xxThenSuccess_retriesAndReturnsSentMessage_attemptsEqualsTwo` — enqueue `[503, 200]` → assert success, request count == 2, audit metadata `attempts == 2`.
- `sendText_5xxRetryExhausted_throwsTelegramSendException_attemptsEqualsFour` — enqueue `[503, 503, 503, 503]` → assert `TelegramSendException`, request count == 4 (3 retries after first per Decision 13), failure event metadata `attempts == 4`, `errorCode == null`.
- `sendText_ioExceptionRetriedThenSuccess_attemptsEqualsTwo` — start the request against MockWebServer that disconnects mid-response on the first attempt (e.g. `setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)`), then enqueue 200 success → assert transient classifier catches the resulting `IOException`, retry succeeds, request count == 2, audit metadata `attempts == 2`.

**429 rate-limit loop:**
- `sendText_429NormalRetryAfter_waitsAndRetries` — enqueue `[429 with parameters.retry_after=1, 200]` → assert success, request count == 2, elapsed wall-clock ≥ 1s but < 2s.
- `sendText_429RateLimitExceptionNotPropagated` — enqueue `[429(retry_after=120)]` → assert externally thrown exception `.isInstanceOf(TelegramSendException.class).isNotInstanceOf(TelegramRateLimitException.class)` (AC13 — internal-only sentinel must not leak past the loop).
- `sendText_429LargeRetryAfter_capsAtOverallTimeout` — enqueue `[429 with retry_after=120]` → assert `TelegramSendException` after ~30s (overall timeout fires; clamp keeps single delay ≤ 30s but the loop still exhausts the budget).
- `sendText_429MissingRetryAfter_capsAtOverallTimeout` — enqueue `[429 with no parameters]` → assert `TelegramSendException` after ~30s.
- `sendText_429NegativeRetryAfter_clampsToZero_thenCapsAtOverallTimeout` — enqueue `[429 with retry_after=-5]` × 100 → assert no spin loop (clamp via `Math.max(0, Math.min(retryAfter, 30))`), terminal `TelegramSendException` at ~30s wall-clock, request count `>= 2 AND <= 100` (concrete ceiling — 100-enqueue exhaustion would fire long before the 30s timeout if a spin loop existed; correct behavior under jitter ≥0ms means handful of attempts within the budget).
- `sendText_429And5xxInterleaving_succeeds_attemptsEqualsFour` — enqueue `[503, 429(retry_after=1), 503, 200]` → assert success, audit metadata `attempts == 4`, neither retry cap exhausted by the other (R3 + AC19 verbatim).

**Terminal 4xx and payload errors:**
- `sendText_401_throwsBotTokenInvalidException_noRetry` — enqueue `[401]` → assert `BotTokenInvalidException` with `botId` populated, request count == 1.
- `sendText_otherClientError_throwsTelegramSendException_noRetry` — enqueue `[400 with description: "Bad Request: chat not found"]` → assert `TelegramSendException` with scrubbed description and `errorCode == 400`, request count == 1.
- `sendText_okFalse_throwsTelegramSendException` — enqueue `[200 with {ok: false, description: "..."}]` → assert `TelegramSendException`, scrubbed description preserved in event metadata.

**Pre-wire guards:**
- `sendText_malformedTokenInBot_throwsBotTokenInvalidException_beforeHttp` — seed bot with ciphertext that decrypts to a non-token shape → assert `BotTokenInvalidException("invalid token shape after decrypt")`, request count == 0.
- `sendText_invalidBase64Ciphertext_throwsBotTokenInvalidException_beforeHttp` — seed bot with `encryptedToken` whose `ciphertext` field is not valid Base64 → `Base64.getDecoder().decode()` throws `IllegalArgumentException` → assert `BotTokenInvalidException("decryption failed")`, request count == 0.
- `sendText_decryptThrowsIllegalState_propagatesAsInternalError_notBotTokenInvalid` — stub `TokenEncryptor.decrypt` to throw `IllegalStateException("AES-GCM decryption failed")` (single test that covers both corrupted-ciphertext and misconfigured-bean failure modes — they are indistinguishable at the call site per Architecture step 3) → assert the exception propagates as-is (not wrapped in `BotTokenInvalidException`), request count == 0.
- `sendText_botNotFound_throwsNotFound_noHttp` — `findById` returns empty → `AppException(NOT_FOUND, "bot_not_found", ...)`, request count == 0.
- `sendText_botDisconnected_throwsNotFound_noHttp` — bot doc with `status=DISCONNECTED` → same 404, request count == 0.

**Timeouts (AC5 + AC8):**
- `sendText_readTimeout_isTransient_retriedThenTerminal` — `mockServer` enqueues with `setBodyDelay(15s)` 4 times → response timeout (10s) trips per attempt, `isTransient` catches `ReadTimeoutException`, after 3 retries terminal `TelegramSendException` is thrown. Verifies AC5 `responseTimeout(10s)` is actually wired.
- `sendText_overallTimeout_throwsTelegramSendException` — script a single 35s-delayed 200 → assert `TelegramSendException` after 30s wall-clock, audit metadata `attempts` captured, terminal `telegram_send_failed` event written.

**Token-leak (AC15 + AC20) — split per log site:**
- `sendText_tokenIn4xxDescription_scrubbed_warnLevel` — enqueue `[400 with description containing the literal token]` → `ListAppender` filtered to WARN level → assert at least one WARN line contains `[REDACTED_TOKEN]` and zero WARN lines contain the raw token.
- `sendText_tokenIn5xxRetryLog_scrubbed_warnLevel` — enqueue `[503 with description containing token, 200]` → assert WARN line emitted by retry observer is scrubbed.
- `sendText_tokenIn429RetryLog_scrubbed_warnLevel` — enqueue `[429 with description containing token, 200]` → assert WARN line emitted by 429 wait announcement is scrubbed.
- `sendText_tokenInTerminalFailureLog_scrubbed_errorLevel` — enqueue `[503, 503, 503, 503 each with description containing token]` → terminal exhaustion → assert ERROR line emitted by `doOnError` is scrubbed.
- `sendText_tokenInTransportErrorMessage_scrubbed` — induce a `WebClientRequestException` whose message embeds the URI (which carries the token) by using a closed MockWebServer port → assert WARN/ERROR line is scrubbed (transport-error messages echo the URI per `BotService.java:165-170` precedent).

### Integration tests (`TelegramSenderIT.java` — full Spring context, embedded Mongo, MockWebServer)

All four scenarios use Awaitility (`await().atMost(Duration.ofSeconds(5)).untilAsserted(...)`) for any assertion on the `events` collection because `EventService.logEvent` is fire-and-forget. Negative-presence assertions (event NOT written) use `await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> count == 0)` to allow a settle window.

- `sendText_endToEndViaBotService_emitsBothEvents` — seed Bot with encrypted token + `ownerChatId`; call `POST /api/v1/projects/{id}/bot/test-message` via `WebTestClient`; enqueue Telegram `sendMessage` 200 with `{ok: true, result: {message_id: 100, chat: {id: <ownerChatId>}}}`; assert 200 OK to client; Awaitility-assert both `telegram_message_sent` (metadata `{botId, chatId, messageId: 100}`) and `bot_test_message_sent` (metadata `{projectId, telegramBotId, chatId, messageId: 100}`) events written to `events` collection.
- `sendText_terminalFailureViaBotService_emitsFailedEventOnly` — seed Bot with encrypted token + `ownerChatId`; enqueue Telegram 401; assert 422 `invalid_bot_token` to client; Awaitility-assert `telegram_send_failed` event present (sender writes its own audit on terminal failure); Awaitility-assert `bot_test_message_sent` NOT written (BotService correctly skips its event on failure).
- `sendTestMessage_ownerChatIdNull_returns422_zeroTelegramCalls` — seed Bot without `ownerChatId`; assert 422 `owner_chat_id_unknown` with the verbatim message `"Send /start to your bot in Telegram first, then try again"`; MockWebServer request count == 0 (drained queue assertion); Awaitility-assert no `telegram_*` and no `bot_test_message_sent` events written within 2s settle window (preserves Epic 06 AC15 behavior).
- `sendTestMessage_legacyDocumentReadsAsNull` — insert raw BSON document into `bots` collection via `ReactiveMongoTemplate.insert(Document)` without the `ownerChatId` field; load via `BotRepository.findById`; assert `bot.getOwnerChatId() == null` (no deserialization error). This is the **first** verification that Spring Data MongoDB reads missing wrapper-type fields as null in this project (no prior test covers this for `disconnectedAt` or any other nullable wrapper field on `Bot`).

`BotControllerIT.java` additions:
- `postTestMessage_in07_seededOwnerChatId_returns200_emitsEvent` — seed `ownerChatId` on top of `seedConnectedBot`; enqueue Telegram 200; assert 200 OK, Awaitility-assert `bot_test_message_sent` event in `events` collection with full metadata shape `{projectId, telegramBotId, chatId, messageId}`.

`BotServiceTest.java` updates (mock `TelegramSender`):
- `sendTestMessage_whenOwnerChatIdNull_returns422_noTelegramCalls_noEvents` — split from existing test; preserves all current assertions including the verbatim message string.
- `sendTestMessage_whenOwnerChatIdPresent_callsSenderAndEmitsEvent` — new; verify `telegramSender.sendText(eq(botId), eq(ownerChatId), eq(TEST_MESSAGE_BODY), eq(null), eq(ownerId))` invoked with exact argument match; `bot_test_message_sent` event written on success with full metadata shape.
- `sendTestMessage_whenOwnerChatIdPresentAndSenderFails_propagatesError_noBotEvent` — new; sender returns `Mono.error(new TelegramSendException(null, "scrubbed", 4))`; verify error propagates with `.isInstanceOf(TelegramSendException.class)` (pin the type, not just "any error"); verify `bot_test_message_sent` NOT written via `verify(eventService, never()).logEvent(any(), eq(EVENT_BOT_TEST_MESSAGE_SENT), any(), any(), any())`.

### E2E tests

- **None.** No live Telegram in CI. No new UI flow in 04c (the «Send test message» button from 04a is unchanged and behaves identically in production until Epic 04b lands `ownerChatId` ingestion). Manual real-Telegram smoke is covered by the staging-smoke runbook (opt-in).

## Agent Verification Plan

**Source:** user-spec "Як перевірити" section.

### Verification approach

Per-task `Verify-smoke` and `Verify-user` fields document the executable checks per implementation task (see Implementation Tasks). The Pre-deploy QA task in the Final Wave runs the full test suite and verifies all 23 acceptance criteria from user-spec plus the Acceptance Criteria below.

There is no Post-deploy verification task because:
- The happy path is unobservable in production until Epic 04b webhook ingestion lands (sender's success branch requires `Bot.ownerChatId` to be populated, which only Epic 04b does).
- The 422 null-branch is exercisable in production but yields the same response as before the feature shipped — nothing to verify beyond regression coverage already in tests.
- A staging-smoke runbook (`docs/staging-smoke/07-telegram-sender.md`) documents the opt-in manual procedure for exercising the success branch with seeded Mongo state on staging; the runbook is not part of CI.

### Tools required

- `bash` — run `./gradlew test`, `./gradlew bootRun`.
- `curl` — exercise `/api/v1/projects/{id}/bot/test-message` for null-branch regression.
- `mongosh` — seed `ownerChatId` for opt-in success-branch smoke (developer-only, never in CI).

No MCP tools required. No Playwright (no UI changes).

## Risks

| Risk | Mitigation |
|------|-----------|
| **R1.** Sender exercising in production unobservable until Epic 04b lands. | Integration tests (`TelegramSenderIT`) seed `ownerChatId` directly in Mongo to exercise the branch. Staging-smoke runbook documents the manual procedure. Cross-epic dependency flagged in user-spec R1. |
| **R2.** Promoting two private helpers in `TelegramApiClient` to public expands its surface area. | Signatures unchanged. Promotion mirrors the existing `scrubTokens` precedent (already public). Recompile-clean (no call-site changes). Add a code comment noting "promoted for `TelegramSender` reuse." |
| **R3.** Retry-loop ordering (429 outer / 5xx inner) is subtle; wrong order silently exhausts the 5xx cap during a `retry_after` wait. | Explicit unit test (`sendText_429And5xxInterleaving_succeeds`) with scripted `[503, 429(retry_after=1), 503, 200]` asserting attempts=4 and success. AC19 in user-spec demands this scenario. |
| **R4.** Theoretical worst-case wall-clock (4 attempts × 10s read + 1+2+4s backoff + indefinite 429 loop) exceeds 30s timeout; timeout fires first. | Intentional. 30s outer cap is the single effective ceiling and aligns with frontend `ofetch` ~35s default — sender fails before the frontend gives up. Unit test for overall timeout asserts `TelegramSendException` at 30s. |
| **R5.** `EventService.logEvent` is fire-and-forget; terminal-failure event may be lost on JVM crash. | Acceptable per existing `bot_connected` / `bot_disconnected` precedent. Documented; not gated. Audit completeness on terminal failure is a known property of the platform's audit substrate, not a 04c regression. |
| **R6.** Adding `Bot.ownerChatId` without a migration script could fail to deserialize legacy documents. | Spring Data MongoDB reads missing fields as `null` for wrapper types (documented framework behavior; `disconnectedAt` is a sibling nullable-wrapper field that exercises the *write* side at `BotService.java:233`). The new integration test `sendTestMessage_legacyDocumentReadsAsNull` is the first explicit codebase verification of the *read* side and acts as a regression net. |
| **R7.** Tampered Mongo storage / corrupted ciphertext surfaces as `IllegalStateException` from `TokenEncryptor.decrypt` (AES-GCM auth-tag failure is wrapped) and propagates as a 500, indistinguishable from a misconfigured `TokenEncryptor` bean. | Accepted as a known limitation — differentiating ops-misconfig from data-tampering would require changing `TokenEncryptor` to unwrap `AEADBadTagException` or attaching a discriminator to the wrapping `IllegalStateException`. Out of scope for 04c (no existing caller differentiates these either). 500 is the safer default — surfaces the issue to ops monitoring rather than misleading a user with "user-fixable" 422 `invalid_bot_token`. `BotTokenInvalidException` is still raised by the explicitly-caught `IllegalArgumentException` from Base64 decode (input-shape failure, not a crypto failure) and by `requireValidTokenShape` (post-decrypt sanity check). |
| **R8.** `ownerId == null` for future Epic 06/07 workers reduces observability of `telegram_message_sent` (no user attribution). | Out of scope for 04c (only synchronous caller is `sendTestMessage` with concrete `ownerId`). Flag in tech-spec for Epic 06/07 design — they may want a system-context sentinel. |
| **R9.** No per-bot concurrency fairness — two simultaneous `sendText` calls on the same bot decrypt and send in parallel. Telegram bot-level limit ~25 msg/sec. | Out of scope for 04c (single synchronous caller). Epic 07 broadcast hot-path will add per-bot rate limiter (`Flux.delayElements` or semaphore) in `sendPipeline` redesign. Documented in user-spec R9. |
| **R10.** Token never in logs — must be enforced everywhere, not just in tests. | All WARN/ERROR sites in `TelegramSender` route external strings through `TelegramApiClient.scrubTokens(...)`. Code review checklist; `ListAppender` assertions in unit tests are split per log site (4xx/5xx-retry/429-wait/terminal-failure/transport-error) so each scrubber site is independently verified (AC15, AC20). |
| **R11.** No rate limit on `/api/v1/projects/{id}/bot/test-message` — an authenticated user could spam the endpoint and exhaust the bot's per-bot Telegram quota (~30 msg/sec). | Out of scope for 04c (existing endpoint, single-user manual click in UI; abuse vector requires authenticated session and is bounded by Telegram's own response). Flag for Epic 07 broadcast hot-path which adds per-bot rate limiting; the same primitive can be reused on `/test-message` if abuse is observed. Documented; not gated. |
| **R12.** `chatId` and `messageId` stored in `events.metadata` are end-user PII (Telegram numeric ids); long-term retention policy not defined here. | Out of scope for 04c — `events` collection has no platform-wide retention policy yet (cross-cutting concern across all `*_*` audit events from `auth`, `project`, `bot`, etc.). Flag for the future analytics/compliance epic. Sender uses the same metadata shape as existing `bot_connected`/`bot_disconnected` events, so 07 doesn't change the platform's PII surface. |
| **R13.** Double-load N+1 + TOCTOU window: `BotService.requireConnectedBot` loads the bot via `findByProjectIdAndStatus`, then `TelegramSender.sendText` re-loads the same bot via `findById(botId)`. Between the two loads, a concurrent `BotService.disconnect` could flip the bot's status, making the sender return 404 for a bot `BotService` just confirmed CONNECTED. Also one extra Mongo round-trip per call. | Accepted as a deliberate cross-task contract: `TelegramSender` is self-contained for future Epic 06/07 system-context callers that don't go through `BotService` (e.g. broadcast workers, scheduled triggers). The TOCTOU window is microseconds; the 404 outcome on race is benign (caller sees the same anti-enumeration shape it would have seen one tick later anyway). The double-load is bounded at one extra `findById` per send — negligible vs the network round-trip to Telegram. Re-evaluate in Epic 07 broadcast hot-path: caller may pre-load and pass a `Bot` reference to skip the second load. |

## User-Spec Deviations

None. All 23 acceptance criteria, all explicit constraints, and all "Технічні рішення" entries from user-spec are honored verbatim. No implicit reinterpretations.

The `[TECHNICAL]`-marked Decision 12 (promoting `requireValidTokenShape` and `isTransient` to public) is an implementation detail required to honor user-spec Risk R2 ("Promotion двох private helper'ів... до public static") — not a deviation, but an implementation-level choice user-spec explicitly anticipates.

## Acceptance Criteria

Technical criteria complementing the 23 user-spec ACs:

- [ ] All new and modified tests green: `./gradlew test` exits 0.
- [ ] No regressions: existing `BotControllerIT`, `BotServiceTest`, `BotRepositoryTest`, `BotTokenLeakTest` all pass unchanged or with the explicit updates listed above.
- [ ] No new Spring beans require `@MockitoBean` in tests outside the new test files (`TelegramSender` is constructor-injected, fits existing patterns).
- [ ] No new properties in `application.properties` / `application-test.properties`.
- [ ] No `build.gradle` changes (`okhttp3:mockwebserver:4.12.0` already declared).
- [ ] `BotTokenLeakTest.botEntityDoesNotDeclareToStringExposingEncryptedTokenFields` continues to pass after `Bot.ownerChatId` is added (verifies no `toString` override).
- [ ] `SecurityConfig.java` has zero diff.
- [ ] `BotResponse.java` has zero diff (`ownerChatId` is internal).
- [ ] `BotController.java` has zero diff.
- [ ] All log lines that include external Telegram strings pass through `TelegramApiClient.scrubTokens(...)` — verified by code review and unit-test `ListAppender` assertions.
- [ ] HTTP 200 OK on success, 404 on bot-not-found / disconnected, 422 on `invalid_bot_token` and `owner_chat_id_unknown`, 400 on `telegram_send_failed` (matches user-spec AC2/3/10/12/17 and existing `GlobalErrorHandler` mapping).

## Implementation Tasks

### Wave 1 (parallel — foundation, no inter-task dependencies)

#### Task 1: Promote `TelegramApiClient` helpers and timeout constants to public

- **Description:** Change `requireValidTokenShape(String)`, `isTransient(Throwable)`, `DEFAULT_RESPONSE_TIMEOUT`, and `CONNECT_TIMEOUT` from `private static` to `public static` on `TelegramApiClient` per Decision 12. Signatures, bodies, and values unchanged; add a brief comment on each noting the second consumer (TelegramSender) — same pattern as the existing `scrubTokens` promotion comment.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew test --tests "com.botfunnel.bot.TelegramApiClientTest"` → all green.
- **Files to modify:** `backend/src/main/java/com/botfunnel/bot/TelegramApiClient.java`
- **Files to read:** `backend/src/test/java/com/botfunnel/bot/TelegramApiClientTest.java`

#### Task 2: Add nullable `ownerChatId` field to `Bot`

- **Description:** Add `private Long ownerChatId;` field on `Bot.java` with public getter/setter, between `telegramFirstName` and `status`. No `toString` override (would break `BotTokenLeakTest`). No `@Indexed` annotation. Confirm legacy-document deserialization via a new `BotRepositoryTest` case that persists a Bot without setting `ownerChatId`, reads it back, and asserts `null`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew test --tests "com.botfunnel.bot.BotRepositoryTest" --tests "com.botfunnel.bot.BotTokenLeakTest"` → all green.
- **Files to modify:** `backend/src/main/java/com/botfunnel/bot/Bot.java`, `backend/src/test/java/com/botfunnel/bot/BotRepositoryTest.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/bot/BotRepository.java`, `backend/src/test/java/com/botfunnel/bot/BotTokenLeakTest.java`

#### Task 3: Create exceptions, records, and event constants

- **Description:** Create `BotTokenInvalidException`, `TelegramSendException`, and `TelegramRateLimitException` classes per Decision 5. Create `SentMessage`, `TelegramSendResult<T>`, and `TelegramSendParameters` records in `bot/dto/`. No business logic — these are typed data carriers and exception shapes consumed by Tasks 4 and 5. Verify each new file compiles standalone via `cd backend && ./gradlew compileJava`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew compileJava` → exits 0; `cd backend && ./gradlew test` → existing tests still pass.
- **Files to modify:** none (all new files)
- **Files to read:** `backend/src/main/java/com/botfunnel/common/AppException.java`, `backend/src/main/java/com/botfunnel/bot/TelegramResult.java`

### Wave 2 (depends on Wave 1)

#### Task 4: Implement `TelegramSender` bean with full unit + integration tests

- **Description:** Implement the `TelegramSender` `@Component` and accompanying tests per Architecture → How it works and Testing Strategy. The bean owns retry/429-loop/timeout/error-mapping/audit/scrubbing per the sender flow steps; tests cover all unit scenarios in `TelegramSenderTest` and all integration scenarios in `TelegramSenderIT`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew test --tests "com.botfunnel.bot.TelegramSenderTest" --tests "com.botfunnel.bot.TelegramSenderIT"` → all green; visual scan of test output confirms `attempts` counts match expectations for retry scenarios.
- **Files to modify:** none (all new files)
- **Files to read:** `backend/src/main/java/com/botfunnel/bot/TelegramApiClient.java`, `backend/src/main/java/com/botfunnel/bot/BotService.java`, `backend/src/main/java/com/botfunnel/bot/BotRepository.java`, `backend/src/main/java/com/botfunnel/common/crypto/TokenEncryptor.java`, `backend/src/main/java/com/botfunnel/events/EventService.java`, `backend/src/test/java/com/botfunnel/bot/TelegramApiClientTest.java`, `backend/src/test/java/com/botfunnel/bot/BotControllerIT.java`

### Wave 3 (depends on Wave 2)

#### Task 5: Wire `TelegramSender` into `BotService.sendTestMessage`

- **Description:** Rewrite `BotService.sendTestMessage` per Architecture → How it works (BotService integration): inject `TelegramSender`, branch on `bot.getOwnerChatId()`, preserve the verbatim 422 stub for null, call sender + emit `bot_test_message_sent` for non-null. Update `BotServiceTest` and `BotControllerIT` per Testing Strategy.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew test --tests "com.botfunnel.bot.BotServiceTest" --tests "com.botfunnel.bot.BotControllerIT"` → all green.
- **Verify-user:** With backend running (`cd backend && ./gradlew bootRun`) and a project + connected bot in Mongo where `ownerChatId IS NULL`, click "Send test message" in `Settings → Bot` → assert UI shows the existing 422 error message (regression check on null-branch behavior unchanged).
- **Files to modify:** `backend/src/main/java/com/botfunnel/bot/BotService.java`, `backend/src/test/java/com/botfunnel/bot/BotServiceTest.java`, `backend/src/test/java/com/botfunnel/bot/BotControllerIT.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/bot/BotController.java`, `backend/src/main/java/com/botfunnel/events/EventService.java`

### Wave 4 (documentation, depends on Wave 3)

#### Task 6: Staging-smoke runbook

- **Description:** Create `docs/staging-smoke/07-telegram-sender.md` documenting the opt-in manual procedure for exercising the sender success branch on staging: prerequisites (throwaway test bot from BotFather, real Telegram account, mongosh access), steps to seed `Bot.ownerChatId` via mongosh, click "Send test message" in UI, verify message arrival in Telegram + presence of `telegram_message_sent` and `bot_test_message_sent` events in Mongo. Mirror the structure of `docs/staging-smoke/06-bot-connection.md`. Document the 04b dependency: this runbook is the only way to exercise the happy path until Epic 04b webhook ingestion populates `ownerChatId` automatically.
- **Skill:** documentation-writing
- **Reviewers:** code-reviewer
- **Files to modify:** none (new file)
- **Files to read:** `docs/staging-smoke/06-bot-connection.md`, `work/07-telegram-sender/user-spec.md`

### Audit Wave

#### Task 7: Code Audit

- **Description:** Full-feature code quality audit. Read all source files created/modified in this feature: `TelegramSender.java`, `Bot.java`, `BotService.java`, the three new exceptions, the three new dto records, `TelegramApiClient.java` (helper promotion), and the staging-smoke runbook. Review holistically for cross-component issues: shared resources compliance (each `WebClient` instance per Architecture → Shared Resources), retry/timeout policy correctness, audit-event metadata consistency, scrubber coverage at every external-string log site, exception hierarchy correctness, anti-enumeration uniformity. Write audit report to `logs/working/task-7/code-reviewer.json`.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 8: Security Audit

- **Description:** Full-feature security audit. Read all source files created/modified in this feature plus all new tests. Analyze for OWASP Top 10: A01 broken access control (anti-enumeration 404 for `bot_not_found`/disconnected, `requireConnectedBot` precondition preserved); A02 cryptographic failures (per-call decrypt, no token in logs/responses/exception messages, `BotTokenLeakTest` regressions); A03 injection (no SQL; `parse_mode=null` default avoids HTML injection in Telegram-rendered output; URI template uses `{token}` placeholder so the token is encoded by the WebClient, not concatenated into a literal); A05 security misconfiguration (`SecurityConfig` unchanged); A07 identification & authentication failures (no auth-flow change); A09 logging failures (audit events on success and terminal failure; no token leakage). Write audit report.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 9: Test Audit

- **Description:** Full-feature test quality audit. Read all test files created/updated in this feature: `TelegramSenderTest.java`, `TelegramSenderIT.java`, updates to `BotServiceTest.java`, `BotControllerIT.java`, `BotRepositoryTest.java`. Verify all 28 unit scenarios (4 happy + 3 5xx + 6 429 + 3 4xx/payload + 5 pre-wire + 2 timeouts + 5 token-leak) + 4 integration scenarios from Testing Strategy are present and meaningful (asserting on observable outcomes, not internals). Verify retry-attempt counts asserted explicitly. Verify token-leak `ListAppender` coverage at WARN and ERROR levels. Verify Awaitility usage on async event writes. Verify positive-path and negative-path coverage in `BotControllerIT`. Write audit report.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 10: Pre-deploy QA

- **Description:** Acceptance testing: run `./gradlew test` (all unit + integration tests, including new `TelegramSenderTest`, `TelegramSenderIT`, updates to `BotServiceTest`, `BotControllerIT`, `BotRepositoryTest`). Verify all 23 user-spec ACs are demonstrably covered by tests or by the implementation. Verify all 11 tech-spec acceptance criteria. Verify the agent verification table from user-spec "Як перевірити" section: ListAppender token-leak assertions in tests, the `curl` null-branch regression, mongosh nullable-schema check (existing pre-feature documents read back as `ownerChatId == null`), `SecurityConfig` zero-diff. Produce QA report.
- **Skill:** pre-deploy-qa
- **Reviewers:** none
