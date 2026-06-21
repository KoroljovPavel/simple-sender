# Code Research: 07-telegram-sender

Scope: pure-infrastructure outbound Telegram sender — single `TelegramSender.sendText(botId, chatId, text, parseMode?)` bean; integrates into existing `BotService.sendTestMessage` when `Bot.ownerChatId` is present.

## 1. Reusable Components

Call these directly. Do NOT duplicate.

### 1.1 Token shape guard + scrubber + retry filter

`backend/src/main/java/com/botfunnel/bot/TelegramApiClient.java`:

- **`TOKEN_PATTERN`** (line 36) — `\d{1,20}:[A-Za-z0-9_-]{30,50}` non-anchored (substring scan).
- **`TOKEN_SHAPE`** (line 37) — `^\d{1,20}:[A-Za-z0-9_-]{30,50}$` anchored (full-match guard).
- **`requireValidTokenShape(String)`** (lines 113-117) — `private static`; throws `IllegalArgumentException("invalid token shape")` on bad shape. **CURRENTLY PRIVATE** — will need to be promoted to package-private or replicated in `TelegramSender` (decision point).
- **`scrubTokens(String)`** (lines 171-174) — `public static`; replaces all token-shaped substrings with `[REDACTED_TOKEN]`; null-safe. **Already public** — call directly from `TelegramSender` log sites.
- **`isTransient(Throwable)`** (lines 154-167) — `private static`; walks cause chain (max 16 hops) for `WebClientResponseException` 5xx / `IOException` / `TimeoutException` / Netty `ReadTimeoutException`. **CURRENTLY PRIVATE** — `TelegramSender` will need its own copy or this needs to be promoted.
- **`buildRetry()`** (lines 144-152) — `private`; returns `Retry.backoff(3, Duration.ofMillis(200)).maxBackoff(Duration.ofSeconds(2)).filter(isTransient).onRetryExhaustedThrow(...AppException(BAD_GATEWAY, "telegram_unavailable", ...))`. **CURRENTLY PRIVATE** — `TelegramSender` retry policy differs (1s→2s→4s per user-spec, not 200ms→400ms→2s), so will NOT be reused; document the divergence.

### 1.2 Constants for upstream config

`backend/src/main/java/com/botfunnel/bot/TelegramApiClient.java`:

- **`DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(10)`** (line 38) — matches user-spec read 10s.
- **`MONO_TIMEOUT = Duration.ofSeconds(10)`** (line 39) — belt-and-braces.
- **`CONNECT_TIMEOUT = Duration.ofSeconds(5)`** (line 40) — matches user-spec connect 5s.
- **`CAUSE_CHAIN_MAX_HOPS = 16`** (line 41).
- **WebClient construction pattern** (constructor lines 51-61): `HttpClient.create().responseTimeout(...).option(CONNECT_TIMEOUT_MILLIS, ...)` + `DefaultUriBuilderFactory(baseUrl)` with `EncodingMode.NONE` + `builder.uriBuilderFactory(...).clientConnector(new ReactorClientHttpConnector(httpClient)).build()`.

### 1.3 Property names

- `app.telegram.base-url` — already wired (`application.properties:45`, `BotControllerIT.java:92`). Reuse for `TelegramSender`.
- `app.bot.token-encryption-key` — `application.properties:44`. `TokenEncryptor` (singleton bean) auto-injected.

### 1.4 Token encryption (decrypt per call, no cache for MVP)

`backend/src/main/java/com/botfunnel/common/crypto/TokenEncryptor.java`:

- `encrypt(String plaintext) → EncryptedValue(byte[] iv, byte[] ciphertext)` (lines 32-43).
- **`decrypt(byte[] iv, byte[] ciphertext) → String`** (lines 45-59) — the call `TelegramSender` will make. Raw byte input — **the Base64 boundary lives at the consumer** (see `BotService.connect:136-138` for encryption side; `BotService.disconnect:216-218` for decryption side — exact pattern to copy).
- `EncryptedValue` record `{byte[] iv, byte[] ciphertext}` at `backend/src/main/java/com/botfunnel/common/crypto/EncryptedValue.java:3`.

### 1.5 EventService for audit

`backend/src/main/java/com/botfunnel/events/EventService.java`:

- **`logEvent(String userId, String eventType, String ipAddress, String userAgent, Map<String,Object> metadata)`** (lines 23-28) — fire-and-forget, never throws, internal `.subscribe(null, log::error)`. **Use this from `TelegramSender`** for `telegram_message_sent` / `telegram_send_failed`. The `userId` arg can be `null` per existing precedent in `AuthService.java:301, 526` (login-failure with no resolved user) and `ProjectHardDeleteJob.java:89-95` (system-context audit; jobs pass project owner's id, not `null`). For `TelegramSender` called from non-user contexts (funnel/broadcast workers, Epics 06/07), `null` is the closest existing precedent — no "system user" sentinel exists.
- `logEventBlocking(...)` (lines 34-38) — only used by `ProjectHardDeleteJob` for ordering guarantees; not needed for `TelegramSender`.

### 1.6 Existing bot lookup queries

`backend/src/main/java/com/botfunnel/bot/BotRepository.java`:

- `findByProjectIdAndStatus(String projectId, BotStatus status): Mono<Bot>` (line 9) — used by `BotService.requireConnectedBot`.
- `findById(String): Mono<Bot>` — inherited from `ReactiveMongoRepository<Bot, String>` (line 7). **This is what `TelegramSender.sendText(botId, ...)` will call** to load the bot doc.
- `findFirstByTelegramBotIdAndStatus(Long, BotStatus): Mono<Bot>` (line 13) — not needed for sender.

### 1.7 AppException factories

`backend/src/main/java/com/botfunnel/common/AppException.java` — call these or extend; do not duplicate the constructor pattern.

- `unprocessableEntity(code, message)` line 40 — 2-arg, code mandatory.
- `unauthorized(message)` line 20.
- `badRequest(message)` line 16.
- `tooManyRequests(message)` line 44.
- `new AppException(HttpStatus.BAD_GATEWAY, "telegram_unavailable", ...)` — used directly in `TelegramApiClient.java:148-151, 74-77` because no factory exists for 502.

### 1.8 MockWebServer test pattern

`backend/src/test/java/com/botfunnel/bot/TelegramApiClientTest.java` (unit-style, no Spring):

- `@BeforeEach setUp` (lines 37-47) — `new MockWebServer()`, `.start()`, construct `TelegramApiClient(WebClient.builder(), mockServer.url("/").toString())` via the package-private 2-arg constructor (line 41). **Use the matching package-private constructor for `TelegramSender`** to enable unit-test style.
- `@AfterEach tearDown` (lines 49-54) — detach logback appender, stop appender, `mockServer.shutdown()`.
- `ListAppender<ILoggingEvent>` for log-assertion (lines 43-47, 133-137, 151-155) — the pattern for "token never in WARN logs" assertion.
- `mockServer.enqueue(new MockResponse().setResponseCode(...).setHeader("Content-Type", "application/json").setBody("..."))` (line 58).
- `mockServer.takeRequest(2, TimeUnit.SECONDS)` + `req.getPath()` / `req.getBody().readUtf8()` (lines 71-73, 317-327).

`backend/src/test/java/com/botfunnel/bot/BotControllerIT.java` (integration-style, full Spring context):

- **Static-initializer MockWebServer + `@DynamicPropertySource`** (lines 79-93). Required because `@DynamicPropertySource` reads the URL at registry-build time. Mirror for `TelegramSenderIT`.
- `@BeforeEach cleanAndSeed` drains the request queue between tests (lines 117-127): `mockTelegram.setDispatcher(new QueueDispatcher())` + `while (mockTelegram.takeRequest(0, MILLISECONDS) != null) { /* drain */ }`.
- `@MockitoSpyBean BotRepository` (line 110) — pattern for forcing persist failures in integration tests.
- `@WithMockAppUser(userId = ...)` (line 267) — see `backend/src/test/java/com/botfunnel/profile/WithMockAppUser.java`.
- `webTestClient.mutateWith(csrf())` (line 276) for state-changing verbs.
- `awaitility` `await().atMost(...).until(...)` pattern for fire-and-forget event verification (lines 228-232) — necessary because `EventService.logEvent` is async.

### 1.9 Test infrastructure dependencies

`backend/build.gradle:38`: `testImplementation 'com.squareup.okhttp3:mockwebserver:4.12.0'` — **already present**, no build change.

## 2. Existing Patterns (verbatim snippets to mirror)

### 2.1 WebClient construction (`TelegramApiClient.java:51-61`)

```java
TelegramApiClient(WebClient.Builder builder, String baseUrl, Duration responseTimeout) {
    HttpClient httpClient = HttpClient.create()
            .responseTimeout(responseTimeout)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis());
    DefaultUriBuilderFactory uriBuilderFactory = new DefaultUriBuilderFactory(baseUrl);
    uriBuilderFactory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);
    this.webClient = builder
            .uriBuilderFactory(uriBuilderFactory)
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
}
```

### 2.2 Mono chain shape (`TelegramApiClient.java:63-78`)

```java
return webClient.get()
        .uri("/bot{token}/getMe", token)
        .retrieve()
        .onStatus(HttpStatusCode::is4xxClientError, this::mapClientError)
        .bodyToMono(new ParameterizedTypeReference<TelegramResult<TelegramUser>>() {})
        .timeout(MONO_TIMEOUT)
        .retryWhen(buildRetry())
        .flatMap(r -> r.result() != null
                ? Mono.just(r.result())
                : Mono.error(new AppException(HttpStatus.BAD_GATEWAY, "telegram_unavailable", ...)));
```

### 2.3 4xx mapping inside `onStatus` (`TelegramApiClient.java:119-142`)

```java
private Mono<? extends Throwable> mapClientError(ClientResponse response) {
    HttpStatus status = HttpStatus.resolve(response.statusCode().value());
    int rawStatus = response.statusCode().value();
    return response.bodyToMono(new ParameterizedTypeReference<TelegramResult<Object>>() {})
            .onErrorResume(ex -> Mono.just(new TelegramResult<>(false, null, null, null)))
            .defaultIfEmpty(new TelegramResult<>(false, null, null, null))
            .map(result -> toAppException(status, rawStatus, result.description()));
}

private AppException toAppException(HttpStatus status, int rawStatus, String description) {
    String scrubbed = scrubTokens(description);
    if (status == HttpStatus.UNAUTHORIZED) {
        return AppException.unprocessableEntity("invalid_bot_token", "Token is invalid or revoked");
    }
    // ... 4xx description-based mapping ...
    log.warn("Telegram client error (status {}): {}", rawStatus, scrubbed);
    return AppException.badRequest("Telegram client error");
}
```

For 07: same shape but the 401 mapping target is **`BotTokenInvalidException`** (new), and all other 4xx → **`TelegramSendException`** with `error_code` + `description` preserved. No `webhook_config_error` branch.

### 2.4 Decrypt-then-pass-to-network pattern (`BotService.java:216-220`)

```java
byte[] iv = Base64.getDecoder().decode(bot.getEncryptedTokenIv());
byte[] ct = Base64.getDecoder().decode(bot.getEncryptedTokenCiphertext());
String plaintextToken = tokenEncryptor.decrypt(iv, ct);

return telegramApiClient.deleteWebhook(plaintextToken)
        // ...
```

**Exact callsite to mirror** in `TelegramSender.sendText`. Closure capture is the existing convention — token is reachable until subscription completes, never logged/serialized.

### 2.5 Scrub-before-log pattern (`BotService.java:165-170`)

```java
return telegramApiClient.deleteWebhook(token)
        .onErrorResume(compErr -> {
            log.warn("Compensating deleteWebhook failed during Connect rollback: {}",
                    TelegramApiClient.scrubTokens(compErr.getMessage()));
            return Mono.just(false);
        })
```

WebClient transport-error messages embed the request URI (which carries the token). **Every WARN/ERROR site in `TelegramSender` must wrap external error messages in `TelegramApiClient.scrubTokens(...)`**.

### 2.6 Event constant + emission pattern

Constants are private static finals declared at the top of the service class. Convention: `EVENT_<UPPER_SNAKE> = "lower_snake"`.

Locations:
- `BotService.java:33-34` — `EVENT_BOT_CONNECTED = "bot_connected"`, `EVENT_BOT_DISCONNECTED = "bot_disconnected"`.
- `AuthService.java:69-73` — `login_success`, `login_failed`, `email_verified`, `password_reset_requested`, `password_changed`.
- `ProjectService.java:19-23` — `project_created`, `project_updated`, `project_renamed`, `project_soft_deleted`, `project_restored`.
- `ProfileService.java:30-31` — `password_changed`, `account_deleted`.
- `ProjectHardDeleteJob.java:30` — `project_hard_deleted`.

No central `EventTypes` registry. New events for 07 (`telegram_message_sent`, `telegram_send_failed`, `bot_test_message_sent`) should follow same convention: private constants on the owning class.

Emission pattern (`BotService.java:98-100`):

```java
.doOnSuccess(saved -> eventService.logEvent(ownerId, EVENT_BOT_CONNECTED,
        ip, userAgent, connectedMetadata(saved)))
```

Metadata builder pattern (`BotService.java:281-287`):

```java
private static Map<String, Object> connectedMetadata(Bot saved) {
    Map<String, Object> meta = new HashMap<>();
    meta.put("projectId", saved.getProjectId());
    meta.put("telegramBotId", saved.getTelegramBotId());
    meta.put("telegramUsername", saved.getTelegramUsername());
    return meta;
}
```

### 2.7 Controller helpers (`BotController.java:81-104`)

`currentUserId()`, `capUserAgent(String)`, `extractIp(ServerWebExchange)` — copied verbatim from `ProfileController`/`ProjectController` per the existing in-line comment. **`TelegramSender` is service-layer, does NOT need these**. Stays in `BotController` for the `sendTestMessage` integration path.

## 3. Files to Modify

### 3.1 `backend/src/main/java/com/botfunnel/bot/Bot.java`

Add field `ownerChatId: Long` (nullable). Placement: between `telegramFirstName` and `status` (keeps Telegram-identity fields contiguous). Persistence: Spring Data MongoDB reads back missing fields as `null` for primitive wrapper types — **no migration script** per user-spec; legacy `bots` documents read back as `ownerChatId == null` automatically. No index needed. Class-level `@CompoundIndex` declarations are unaffected.

Required edits:
- Private field with no annotation (snake-case is NOT used in this project — convention is camelCase in both Java field and Mongo doc; verified vs. `telegramBotId`, `telegramUsername`, `tokenSuffix` all stored camelCase).
- Public `getOwnerChatId()` / `setOwnerChatId(Long)` getter/setter (matches existing JavaBean style; no records).
- **Do NOT add `toString` override** — would fail `BotTokenLeakTest.botEntityDoesNotDeclareToStringExposingEncryptedTokenFields` at `BotTokenLeakTest.java:52-67`.

### 3.2 `backend/src/main/java/com/botfunnel/bot/BotService.java`

Rewrite `sendTestMessage(ownerId, projectId, ip, userAgent): Mono<Void>` (currently lines 109-116; the `Mono.<Void>error(...)` 422 stub) to:

1. Keep `requireConnectedBot(ownerId, projectId)` first call (line 110).
2. Branch on `bot.getOwnerChatId() == null`:
   - `null` → preserve existing 422 stub error (`AppException.unprocessableEntity("owner_chat_id_unknown", ...)`) — keeps backward compat for projects whose ownerChatId hasn't been resolved yet.
   - non-null → call new `telegramSender.sendText(bot.getId(), bot.getOwnerChatId(), "Bot connected ✅", null)` → `.doOnSuccess(_ -> eventService.logEvent(ownerId, EVENT_BOT_TEST_MESSAGE_SENT, ip, ua, Map.of(...)))` → `.then()`.
3. Inject `TelegramSender` via constructor (add field, update constructor signature — `BotServiceTest.java:101` constructs the service explicitly, so test must be updated in lock-step).
4. **Do NOT** double-write `bot_test_message_failed` event on failure (per user-spec — sender already writes `telegram_send_failed`).
5. Add `EVENT_BOT_TEST_MESSAGE_SENT = "bot_test_message_sent"` constant alongside lines 33-34.

User-spec D7 in `work/completed/06-bot-connection/tech-spec.md:194` defers the event emission to 06b/07 — that's this feature.

### 3.3 `backend/src/main/java/com/botfunnel/bot/BotController.java`

No mandatory change. The endpoint currently returns `Mono<ResponseEntity<Void>>` (line 61). User-spec allows either:
- Stay `Void` 200 (simpler, no DTO change).
- Move to `{messageId}` body — requires new `TestMessageResponse` record + `TelegramSender.SentMessage` returning a `messageId`.

**Recommendation:** stay `Void` for MVP (no new DTO). `SentMessage.messageId` is captured server-side for the audit event metadata and never exposed; the UI shows a generic success toast.

### 3.4 Test files to update (422-stub assertions break when stub becomes branch)

- `backend/src/test/java/com/botfunnel/bot/BotServiceTest.java:554-573` — `sendTestMessage_returns422_noTelegramCalls_noEvents`. Must be split into two cases:
  - `sendTestMessage_whenOwnerChatIdNull_returns422_noTelegramCalls_noEvents` (preserve existing assertions).
  - `sendTestMessage_whenOwnerChatIdPresent_callsSenderAndEmitsEvent` (new — mocks `TelegramSender.sendText`, asserts `bot_test_message_sent` event).
- `backend/src/test/java/com/botfunnel/bot/BotServiceTest.java:576-594` — `sendTestMessage_noConnectedBot_returns404` stays unchanged.
- `backend/src/test/java/com/botfunnel/bot/BotServiceTest.java:99-110` — constructor call must include the new `TelegramSender` mock.
- `backend/src/test/java/com/botfunnel/bot/BotControllerIT.java:740-760` — `postTestMessage_in06_returns422_zeroTelegramCalls_zeroEvents`. The seeded bot in `seedConnectedBot()` (`BotControllerIT.java:182-195`) doesn't set `ownerChatId` → still falls through the null branch → 422 path stays valid. **No mandatory change**, but a new positive-path test should be added (seed with `ownerChatId`, enqueue Telegram `sendMessage` 200, assert event emitted).
- `backend/src/test/java/com/botfunnel/bot/BotRepositoryTest.java:24-42` — `newBot` helper. No need to set `ownerChatId` (null default fine).

### 3.5 `backend/src/main/java/com/botfunnel/bot/dto/BotResponse.java`

**Discretionary.** The current record (lines 9-16) exposes `telegramBotId`, `telegramUsername`, `telegramFirstName`, `tokenSuffix`, `status`, `connectedAt`. Adding `ownerChatId` to the response is **NOT required for 07** (UI doesn't surface it) and triggers a `BotTokenLeakTest`-style reflection check audit. **Skip.** `ownerChatId` is internal to the backend.

### 3.6 `backend/build.gradle`

No changes. `okhttp3:mockwebserver:4.12.0` already at line 38; `spring-boot-starter-webflux`, `reactor-test`, `spring-boot-starter-data-mongodb-reactive`, `spring-boot-starter-data-redis-reactive` all present.

### 3.7 `application.properties` / `application-test.properties`

No new properties. `app.telegram.base-url` already wired. `TelegramSender` reuses the same property as `TelegramApiClient`.

### 3.8 SecurityConfig

**No change.** `pathMatchers("/api/**").authenticated()` (`SecurityConfig.java:71`) covers `/api/v1/projects/{projectId}/bot/test-message`. Confirmed by `BotControllerIT.java:865-896` `anyEndpoint_unauthenticatedBareClient_returns401` test which already passes.

## 4. Files to Create

### 4.1 `backend/src/main/java/com/botfunnel/bot/TelegramSender.java`

`@Component public class TelegramSender { Mono<SentMessage> sendText(String botId, Long chatId, String text, String parseMode); }`.

Dependencies (constructor-injected):
- `BotRepository` — `findById(botId)`.
- `TokenEncryptor` — decrypt per call (no cache for MVP).
- `WebClient.Builder` + `@Value("${app.telegram.base-url}")` — same construction shape as `TelegramApiClient.java:46-61`.
- `EventService` — `telegram_message_sent` / `telegram_send_failed` audit.

Retry policy: `Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(4)).filter(isTransient).onRetryExhaustedThrow(...)`. **3 retries AFTER the first attempt — see Risks §5 below.** Filter mirrors `TelegramApiClient.isTransient` (copy or promote helper).

429 handling: a separate `retryWhen` (or pre-`onStatus` parse) reads `parameters.retry_after` (seconds) from the Telegram error body and uses `Mono.delay(Duration.ofSeconds(retryAfter + jitter)).then(retry)`. This loop is NOT bounded by the 3-attempt cap (per user-spec). Reactor pattern: chain a `.retryWhen(Retry.from(signals -> signals.flatMap(rs -> { if (rs.failure() instanceof TelegramRateLimitException tle) return Mono.delay(...); else return Mono.error(rs.failure()); })))` BEFORE the transient-retry `.retryWhen(buildRetry())`. Order matters — 429 retry must wrap 5xx retry.

### 4.2 `backend/src/main/java/com/botfunnel/bot/dto/SentMessage.java`

`public record SentMessage(Long chatId, Long messageId, java.time.Instant sentAt) {}`. Used for the success return value and the `telegram_message_sent` event metadata.

### 4.3 Exception classes (3 new, all extend `AppException`)

Place in `backend/src/main/java/com/botfunnel/bot/`:

- **`BotTokenInvalidException`** — 401 from Telegram. Maps to existing `AppException.unprocessableEntity("invalid_bot_token", ...)` shape that `TelegramApiClient.toAppException` already uses (line 131). **Consider:** for 07, callers want a typed exception (Epic 06/07 will catch this to set `Bot.status = TOKEN_INVALID` later — out of scope for 07, but exception identity matters). Concrete class `class BotTokenInvalidException extends AppException { public BotTokenInvalidException(String botId) { super(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_bot_token", "Bot token rejected by Telegram"); } }`.
- **`TelegramSendException`** — generic 4xx (non-401) and transient-retry-exhausted 5xx. Carries `Integer errorCode` + `String description` (already scrubbed). Maps to `HttpStatus.BAD_GATEWAY` for 5xx-exhausted, `HttpStatus.BAD_REQUEST` (or `UNPROCESSABLE_ENTITY`) for terminal 4xx.
- **`TelegramRateLimitException`** — internal-only for the 429 retry loop; never propagated to the caller (always retried). Optional; could be modeled as a sentinel `Mono.error` without a class.

### 4.4 `backend/src/main/java/com/botfunnel/bot/dto/TelegramSendMessageRequest.java` (optional)

Record `(Long chat_id, String text, String parse_mode)` — Telegram API field naming uses snake_case wire format. Construct via `Map.of(...)` (like `TelegramApiClient.setWebhook` body at lines 87-89) to avoid a separate DTO. **Recommendation:** use `Map.of(...)` for symmetry with existing code.

### 4.5 `backend/src/main/java/com/botfunnel/bot/dto/TelegramErrorResponse.java` (optional)

Already exists as `TelegramResult<T>` (`TelegramResult.java:3`): `record TelegramResult<T>(boolean ok, T result, Integer error_code, String description)`. **Reuse.** Need to extend or add a separate `TelegramRateLimitParameters(Integer retry_after)` record for the 429 `parameters.retry_after` parse — current `TelegramResult` doesn't carry `parameters`. Either:
- Extend: `record TelegramResult<T>(boolean ok, T result, Integer error_code, String description, TelegramParameters parameters)` — small API ripple in `TelegramApiClient.mapClientError` (line 122) which builds the empty fallback.
- Add separate `TelegramSendResult<T>` with the extra field.

**Recommendation:** add separate record. Lower blast radius.

### 4.6 `backend/src/test/java/com/botfunnel/bot/TelegramSenderTest.java`

Unit-style. Mirrors `TelegramApiClientTest.java`. MockWebServer + `ListAppender<ILoggingEvent>`. No Spring context. Coverage:

- happy path → returns `SentMessage` with `messageId`.
- 5xx × 4 → `TelegramSendException`, exactly 4 requests.
- 401 → `BotTokenInvalidException`, exactly 1 request.
- 429 with `retry_after=1` → 1 retry delay, then success.
- 429 followed by 5xx → 429 retry succeeds AT THE FIRST attempt, then 5xx retry counter starts fresh (verify 429 is NOT counted).
- IOException / TimeoutException retried.
- Token in error description → scrubbed in WARN log.
- `requireValidTokenShape` guard on a malformed token (constructed via test seam) → `IllegalArgumentException`.

### 4.7 `backend/src/test/java/com/botfunnel/bot/TelegramSenderIT.java`

Integration-style. Mirrors `BotControllerIT.java` MockWebServer pattern (static initializer + `@DynamicPropertySource`, lines 79-93). Uses `AbstractIntegrationTest`. Coverage:

- end-to-end via `BotService.sendTestMessage` when `Bot.ownerChatId` is populated.
- `bot_test_message_sent` event emitted on success.
- `telegram_send_failed` event emitted on terminal failure.
- Backwards-compat: `Bot.ownerChatId == null` still 422 (preserves AC15 from 06).

## 5. Risks

### 5.1 Reactor `Retry.backoff(N, ...)` semantics

`Retry.backoff(3, Duration.ofMillis(200))` produces **3 RETRIES after the first attempt = 4 total attempts**. Confirmed by:
- `TelegramApiClientTest.java:97-114` — `for (int i = 0; i < 4; i++) { mockServer.enqueue(503) }` followed by `assertThat(mockServer.getRequestCount()).isEqualTo(4)` after `Retry.backoff(3, ...)` exhausts.
- `BotControllerIT.java:364-378` — same pattern: 4 × 503 enqueued, 502 returned, comment "3 retries → 4 attempts total".

**User-spec says "3 attempts, exponential backoff 1s→2s→4s".** If interpreted as 3 total attempts → use `Retry.backoff(2, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(4))` (2 retries after first = 3 total, delays 1s + 2s = sequence 1s, 2s). The "1s→2s→4s" sequence requires 3 retries (4 attempts). **Interview cycle 2 must resolve this**.

### 5.2 Scrubber regex `30-50` vs Telegram tokens with `_` characters

`TOKEN_PATTERN` at `TelegramApiClient.java:36` is `\d{1,20}:[A-Za-z0-9_-]{30,50}`. Real bot tokens are typically 35 chars. User-spec token `1234567890:ABCdefGHI_jklMNOpqrSTUvwxYZ0123456789xyz` (the test fixture at `BotServiceTest.java:61`) is 38 chars and matches. The 4xx description echo test (`TelegramApiClientTest.java:140-155`) proves the scrubber catches the literal token. **Low risk.**

### 5.3 `requireValidTokenShape` is currently private in `TelegramApiClient`

`TelegramApiClient.java:113-117` — `private static void requireValidTokenShape(String token)`. **Three options:**

(a) Promote to `public static` on `TelegramApiClient` (matches `scrubTokens` promotion path).
(b) Duplicate in `TelegramSender` as another `private static`.
(c) Extract to a new `bot/internal/TokenShape.java` (over-engineered for one helper).

**Recommendation:** (a). Mirrors the `scrubTokens` promotion comment at `TelegramApiClient.java:169-170`.

### 5.4 `isTransient` cause-chain walker is currently private

`TelegramApiClient.java:154-167` — same story as 5.3. **Recommendation:** promote to `public static` on `TelegramApiClient`. Both `getMe/setWebhook/deleteWebhook` and `TelegramSender.sendText` share the same transient-failure definition.

### 5.5 MongoDB nullable field on existing documents

`bots` documents persisted before 07 lacks `ownerChatId`. Spring Data MongoDB maps missing fields to `null` for object types (`Long` here, not primitive `long`). **Verified** by:
- `BotRepositoryTest.java:24-42` constructs Bot without setting `ownerChatId` (no setter call) → save → read → `ownerChatId == null` implicitly.
- `disconnectedAt` is the existing precedent (`Bot.java:63`) — nulled on Disconnect path (`BotService.java:233`), read back as null on connected rows.

No migration script needed. Confirms user-spec assumption.

### 5.6 No existing `TelegramSender` / `MessageSender` interface

`grep -rn "TelegramSender|MessageSender|sendMessage|sendText"` over `backend/src` returned zero results in production code (only documentation refs in tech-spec). `TelegramApiClient` has NO `sendMessage` method — only `getMe`, `setWebhook`, `deleteWebhook`. **No collision.**

### 5.7 `EventService.logEvent` is fire-and-forget — terminal-failure event may not survive a JVM crash before flush

`EventService.java:23-28` — `.subscribe(null, log::error)` with no flush guarantee. The `telegram_send_failed` audit event could be lost if the process dies right after the send fails. **Acceptable** for MVP (matches existing precedent for `bot_connected` / `bot_disconnected`). Document but do not gate on it.

### 5.8 `userId` to pass when `TelegramSender` is invoked from non-user contexts

`EventService.logEvent` accepts `String userId` (nullable per `AuthService.java:301` precedent). For 07, `BotService.sendTestMessage` already has `ownerId` (from `BotController.currentUserId()`). **For future Epic 06/07 workers** (out of scope for 07 but signal-worth) — no "system user" sentinel exists; `null` is the closest precedent. Sender's `sendText(botId, ...)` signature does NOT take `userId` per user-spec — sender writes its own audit events with `userId = null` (or accepts an additional context param; deferred to interview cycle 2).

### 5.9 Reactor 429-loop interleaving with 5xx-retry-loop

Reactor's `.retryWhen` operators **stack**: outer wraps inner. The 429 loop must wrap the 5xx loop, otherwise the 3-attempt 5xx counter exhausts during a `retry_after` wait. The naive `.retryWhen(buildRetry()).retryWhen(rateLimit())` is in the **wrong order** (inner first); the correct shape is the 5xx-loop INSIDE and the 429-loop OUTSIDE. Verify in tests.

### 5.10 Default `parse_mode = HTML` semantics

Telegram's `parse_mode=HTML` accepts a limited tag set (`<b>`, `<i>`, `<a>`, `<code>`, etc.). The MVP test message body is plain text; HTML default is safe. If a future caller passes user-generated content with `parse_mode=HTML`, an injection bug becomes possible — **out of scope for 07** but flag for future epics.

### 5.11 WebClient connect timeout 5s vs response timeout 10s

`TelegramApiClient.java:38-40` already encodes both: `responseTimeout(10s)` + `CONNECT_TIMEOUT_MILLIS = 5000` via `ChannelOption`. Mirror exactly. Worst-case wall-clock for one `sendText`: 4 attempts × (5s connect + 10s read) + (1s + 2s + 4s) backoff = up to ~67s without 429 + indefinite 429 loop. Frontend timeout on `useApi`/`ofetch` is ~35s default — Connect path could exceed this. **Low risk for 07** because `sendTestMessage` is the only synchronous caller and Telegram normally responds in <1s.

### 5.12 `Bot.ownerChatId` is set BY Epic 06b (webhook ingestion), not by 07

User-spec says 07 only **reads** `ownerChatId`; the value is populated when the project owner sends `/start` to the bot AFTER 06b is delivered. Until 06b lands, `ownerChatId` will always be `null` and `sendTestMessage` will keep returning 422 (the null branch). **The 07 happy-path is unobservable in production until 06b lands.** Integration test must seed `ownerChatId` directly in Mongo to exercise the branch.

### 5.13 No SecurityConfig change — confirmed

`SecurityConfig.java:68-73`:
```
.pathMatchers("/health").permitAll()
.pathMatchers("/api/auth/**").permitAll()
.pathMatchers("/api/**").authenticated()
.anyExchange().authenticated()
```

`/api/v1/projects/{projectId}/bot/test-message` matches `/api/**` → already authenticated. `BotControllerIT.java:865-896` proves 401 on unauthenticated access.

## 6. Open Questions for Interview Cycle 2

1. **Retry count semantics.** User-spec "3 attempts, exponential backoff 1s→2s→4s" — is this 3 total attempts (= `Retry.backoff(2, ...)`) or 3 retries after first attempt (= `Retry.backoff(3, ...)`)? Existing `TelegramApiClient` uses `Retry.backoff(3, ...)` = 4 total. Pick one and document. (Risks §5.1.)
2. **Promote `requireValidTokenShape` + `isTransient` to public on `TelegramApiClient`** or duplicate in `TelegramSender`? Same decision as `scrubTokens` (already promoted). (Risks §5.3, §5.4.)
3. **`userId` arg for `EventService.logEvent` from non-user contexts.** Pass `null` (existing precedent) or invent a `system` sentinel string? Affects `telegram_message_sent` event shape when sender is called from Epic 06/07 workers. (Risks §5.8.)
4. **`TelegramResult` extension vs separate result type.** `parameters.retry_after` for 429 requires either extending `TelegramResult<T>` or adding `TelegramSendResult<T>`. Lower blast radius = separate type. Confirm. (Files to Create §4.5.)
5. **Response shape for `POST /test-message`.** Currently `ResponseEntity<Void>` 200. Move to `{messageId, sentAt}` body? User-spec doesn't say. (Files to Modify §3.3.)
6. **Token shape guard target.** Sender's `requireValidTokenShape` runs against the **decrypted** token loaded from Mongo. If Mongo storage was tampered with (out-of-app modification), shape guard rejects with `IllegalArgumentException` → unhandled 500 by `GlobalErrorHandler.handleThrowable`. Should this be a typed `BotTokenInvalidException` and 422 instead?
7. **Connect timeout for `sendText`.** User-spec says 5s/10s. Reuse `TelegramApiClient`'s static constants or declare fresh? Reusing risks coupling — but the constants are upstream-not-method-scoped. Recommend reuse.
8. **`parse_mode = null` default vs `HTML` default.** User-spec says "default `HTML`". Confirm — Telegram default is plain text. Forcing HTML may break test message body. Recommend `null` (Telegram default) for the 07 test message specifically; HTML default lives in the method signature for future callers.
9. **Where to capture `bot_test_message_sent` metadata.** Sender writes `telegram_message_sent { botId, chatId, messageId }`. `BotService.sendTestMessage` writes `bot_test_message_sent { projectId, telegramBotId, chatId?, messageId? }` on success. Two events per successful test message — confirm intentional (sender events are infrastructure-level, BotService events are user-action-level).
10. **AbstractIntegrationTest reuse.** `TelegramSenderIT` would gain testcontainers Mongo + Redis startup (~6s class boot) for no real reason — sender only needs Mongo (for `BotRepository.findById`). Worth a lighter `@DataMongoTest`-style base? Defer to test author.
