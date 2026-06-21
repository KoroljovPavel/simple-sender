# Code Research: 08-webhook-ingestion (sub-epic 04b)

Created: 2026-05-16. No prior `code-research.md` existed for this feature; this is the initial pass.

Endpoint scope: `POST /webhooks/telegram/{projectId}` → SHA-256 secret verify → persist `raw_updates` (idempotent) → enqueue JobRunr `ProcessTelegramUpdateJob(rawUpdateId)` → 200 OK in <100ms P99. Worker parses `/start [payload]` and `/stop`, writes `events`, populates `Bot.ownerChatId` on first eligible `/start`, calls stub `SubscriberService` + `FunnelTriggerService`. TTL 90d on `raw_updates.createdAt`.

---

## 1. Reuse — existing files to read first

### `backend/src/main/java/com/botfunnel/security/SecurityConfig.java` (108 lines)

- Reactive WebFlux security (`@EnableWebFluxSecurity`, `ServerHttpSecurity`).
- Current `authorizeExchange` order (`:68-73`):
  ```
  .pathMatchers("/health").permitAll()
  .pathMatchers("/api/auth/**").permitAll()
  .pathMatchers("/api/**").authenticated()
  .anyExchange().authenticated()
  ```
- CSRF is **enabled globally** (`:55-65`) with `CookieServerCsrfTokenRepository.withHttpOnlyFalse()`. There is no per-path CSRF exclusion yet — `csrf.disable()` is currently NOT used anywhere.
- **Edits required for 04b:**
  - Add `.pathMatchers("/webhooks/telegram/**").permitAll()` BEFORE `pathMatchers("/api/**").authenticated()`. First-match rule (`patterns.md` line 44).
  - Selectively disable CSRF for `/webhooks/telegram/**` only. In Spring Security 6 reactive this is `.csrf(csrf -> csrf.requireCsrfProtectionMatcher(exchange -> ...))` — i.e. supply a matcher that **excludes** the webhook prefix, leaving CSRF in force for everything else. Do NOT call `csrf().disable()` (would break the SPA — see `patterns.md` line 45 about scoped disable).
  - Keep the existing `csrfCookieMaterializer` and CORS configuration untouched (they apply on the SPA paths, not the webhook).
- The `WebSessionServerSecurityContextRepository` and `RememberMeWebSessionIdResolver` are unrelated and remain untouched.

### `backend/src/main/java/com/botfunnel/bot/Bot.java` (104 lines)

Fields already exist per 04a/04c:

| Field | Type | Nullable | Notes |
|---|---|---|---|
| `id` | `String` | — | `@Id` |
| `projectId` | `String` | no | `@Indexed`, also part of compound indexes |
| `telegramBotId` | `Long` | no when CONNECTED | unique partial-filter index (`status=CONNECTED`) |
| `telegramUsername` | `String` | — | |
| `telegramFirstName` | `String` | — | |
| `ownerChatId` | `Long` | **yes** | Added in 04c, populated only by 04b. Read in 04c via `BotService.sendTestMessage` for the success branch. No index, no `@Indexed`. |
| `status` | `BotStatus` enum | no | `CONNECTED \| DISCONNECTED` |
| `encryptedTokenCiphertext` | `String` | yes when DISCONNECTED | `@JsonIgnore` |
| `encryptedTokenIv` | `String` | yes when DISCONNECTED | `@JsonIgnore` |
| `tokenSuffix` | `String` | yes when DISCONNECTED | `@JsonIgnore` |
| `webhookSecretHash` | `String` | yes when DISCONNECTED | `@JsonIgnore`, **hex SHA-256** (`patterns.md` line 101) |
| `connectedAt` / `disconnectedAt` | `Instant` | — | |

Compound indexes (`:18-28`):
- `projectId_status` (non-unique) — usable by webhook handler for `findByProjectIdAndStatus(projectId, CONNECTED)`.
- `telegramBotId_unique_connected` (unique, partial `status=CONNECTED`).
- `projectId_unique_connected` (unique, partial `status=CONNECTED`).

Static class-load assertion at `:34-40` pins the literal `"CONNECTED"` to `BotStatus.CONNECTED.name()`. Adding new persisted enum values requires the same defense-in-depth pattern.

`BotTokenLeakTest` invariant: no `toString` override (`patterns.md` line 124). Any new field touching `Bot` must not generate a Lombok / IDE `toString`.

### `backend/src/main/java/com/botfunnel/bot/BotRepository.java` (15 lines)

```java
Mono<Bot> findByProjectIdAndStatus(String projectId, BotStatus status);
Flux<Bot> findByProjectId(String projectId);
Mono<Bot> findFirstByTelegramBotIdAndStatus(Long telegramBotId, BotStatus status);
```

`findByProjectIdAndStatus` is the exact query the webhook handler needs to resolve `(projectId from URL) → Bot{webhookSecretHash, telegramBotId, ownerChatId}`. No new repo method needed for the secret-verify path. For the worker's owner-chat populate step, a CAS-style write is needed; current options:

- Reuse `BotRepository.findByProjectIdAndStatus(...).flatMap(save)` — read-then-write race possible.
- Or use `ReactiveMongoTemplate.updateFirst(Query.query(Criteria.where("projectId").is(...).and("status").is("CONNECTED").and("ownerChatId").is(null)), Update.update("ownerChatId", chatId), Bot.class)` for atomic "first /start wins" semantics. Precedent: `ProjectHardDeleteJob.java:82-101` uses `ReactiveMongoTemplate` directly.

### `backend/src/main/java/com/botfunnel/bot/BotService.java` (340 lines)

- `sendTestMessage` (`:118-139`) branches on `bot.getOwnerChatId() == null` → 422 `owner_chat_id_unknown` else delegate to `TelegramSender`. The `ownerChatId` write side is currently absent from the codebase entirely — 04b owns it.
- Event-type constants kept as `private static final String` on each service (`:34-36`): `EVENT_BOT_CONNECTED`, `EVENT_BOT_DISCONNECTED`, `EVENT_BOT_TEST_MESSAGE_SENT`. Convention for 04b: define `EVENT_TELEGRAM_MESSAGE_RECEIVED`, `EVENT_TELEGRAM_COMMAND_START`, `EVENT_TELEGRAM_COMMAND_STOP` on the worker class.
- `compensateAndPropagate` / `mapPersistError` (`:182-219`) is the **`DuplicateKeyException` mapping precedent** — exception message contains the index name (`projectId_unique_connected`, `telegramBotId_unique_connected`), and the fallback re-queries when the driver omits the name. 04b uses the same shape on `raw_updates`: catch `DuplicateKeyException` from the `(projectId, updateId)` insert → treat as duplicate → return 200 silently (no enqueue, no event).
- `connect` (`:97-110`) is the precedent for `webhookSecretHash = sha256Hex(secretHex)` (`:152, :331-338`). The exact `sha256Hex` helper at `:331-338` is private; the verifier in 04b needs the same algorithm (`MessageDigest.getInstance("SHA-256")` → hex). Consider promoting to `common/` (security check: still SHA-256, no salt — matches the precedent).

### `backend/src/main/java/com/botfunnel/project/ProjectService.java` (218 lines)

- `requireOwned(ownerId, projectId, includeSoftDeleted): Mono<Project>` at `:50-64` is **authenticated-flow only** — it needs `ownerId` from `ReactiveSecurityContextHolder`. **Webhook handler cannot use it** (unauthenticated).
- For the soft-delete check, the webhook handler must query the repository directly. Options:
  - `projectRepository.findById(projectId)` → check `getDeletedAt() != null` → 404. Has the same `IllegalArgumentException` (malformed ObjectId) hazard as `requireOwned` — must `.onErrorMap(IllegalArgumentException.class, e -> notFound)`.
  - Or rely on `botRepository.findByProjectIdAndStatus(projectId, CONNECTED)` empty → 404 (covers both missing project AND no connected bot AND soft-deleted-with-still-connected-bot-leftover). This is simpler and aligns with the anti-enumeration uniform-404 rule (`patterns.md` line 79): all three cases collapse to identical 404.
  - **Recommendation:** single `botRepository.findByProjectIdAndStatus(projectId, CONNECTED)` lookup. If bot is DISCONNECTED or absent, return 404. If project was soft-deleted, the Bot row was force-set to `DISCONNECTED` on Disconnect (BotService `:255`), so this naturally short-circuits. (Verify in tech-spec: confirm project soft-delete cascades through Bot — check `ProjectHardDeleteJob.java` and 06-bot-connection decisions.)

### `backend/src/main/java/com/botfunnel/project/Project.java` (59 lines)

- `deletedAt: Instant` at `:34` is the soft-delete field. Nullable. Indexes `owner_deleted` and `owner_name_deleted` exist but webhook handler doesn't need them — it would query by `_id` if it queries Project at all.

### `backend/src/main/java/com/botfunnel/events/EventService.java` (39 lines) + `Event.java` (58 lines)

- Two factory methods:
  - `logEvent(userId, eventType, ipAddress, userAgent, metadata)` — fire-and-forget at `:23-28`. **`userId` is nullable** (sender uses null at `TelegramSender.java:122` for system-context). Webhook worker uses `null` for `userId` because no authenticated principal — verify against 04c precedent.
  - `logEventBlocking(...)` — returns `Mono<Event>` for cascade ordering (`:34-38`). Use this in the JobRunr worker if event ordering matters (e.g., write `telegram_message_received` BEFORE updating `raw_updates.processingStatus=done`).
- `Event.eventType` is an open string enum (`patterns.md` line 52). New event types (`telegram_message_received`, `telegram_command_start`, `telegram_command_stop`) just slot in.
- `Event.metadata: Map<String, Object>` — free-form. For 04b: include `{projectId, telegramBotId, chatId, updateId, startPayload?}`. Do NOT include raw text or token-bearing strings (token-scrubber rule, `patterns.md` line 98).
- Compound index `userId_createdAt_desc` (`:13`) is for the audit UI; events with `userId=null` (system context) still index correctly (Mongo indexes nulls).

### `backend/src/main/java/com/botfunnel/common/AppException.java` (50 lines)

Factories available:
- `badRequest(String)` → 400 (no code)
- `unauthorized(String)` → 401 (no code)
- `forbidden(String)` → 403
- `conflict(String)` and `conflict(String code, String message)` → 409
- `notFound(String)` → 404
- `unprocessableEntity(String code, String message)` → 422 (code REQUIRED — intentional asymmetry, `patterns.md` line 80)
- `tooManyRequests(String)` → 429

**Missing for 04b:**
- **401 with no body** — `unauthorized(String message)` exists; `GlobalErrorHandler.handleAppException` would emit a body `{"message":"…","code":null}`. User-spec requires "401 (no body) for invalid/missing secret". Options: (a) add a new factory `AppException.unauthorizedNoBody()` + a marker handled by `GlobalErrorHandler` to write empty body, OR (b) the controller returns `Mono.error` with `ResponseStatusException(HttpStatus.UNAUTHORIZED)` and override the `GlobalErrorHandler.handleResponseStatus` path to skip body for 401 from webhook prefix, OR (c) emit `ResponseEntity.status(401).build()` directly from the controller without an exception.
- **413 Payload Too Large** — no factory; add `AppException.payloadTooLarge(String message)` or use `ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE)`. Note: this is also typically enforced at the WebFlux codec/`spring.codec.max-in-memory-size` level — see Constraints below.

### `backend/src/main/java/com/botfunnel/common/GlobalErrorHandler.java` (49 lines)

- `@ExceptionHandler(AppException.class)` (`:20-24`) writes `ResponseEntity.status(ex.getStatus()).body(new ErrorResponse(ex.getMessage(), ex.getCode()))` — body is ALWAYS written.
- `@ExceptionHandler(WebExchangeBindException.class)` (`:26-35`) handles `@Valid` failures as 400.
- `@ExceptionHandler(ResponseStatusException.class)` (`:37-41`) honors any `HttpStatusCode` carried in the exception and includes a body.
- `@ExceptionHandler(Throwable.class)` (`:43-48`) — catchall 500.
- **To return empty 401 body** for the webhook handler, the cleanest path is to bypass `AppException`: the controller method returns `Mono.just(ResponseEntity.status(401).build())` (no exception, no body). Same for 404/413 if user-spec mandates body-less webhook responses. Confirm with tech-spec which webhook responses get no body — user-spec only says "401 (no body)" explicitly.

### `backend/src/main/java/com/botfunnel/jobs/`

Files present:
- `HardDeleteJob.java` (56 lines) — `@Recurring(id="hard-delete-users", cron="0 3 * * *")` + `@Job(name=...)`. Uses `.block()` inside the recurring job body — synchronous I/O on the JobRunr worker thread (acceptable per `patterns.md` line 59).
- `ProjectHardDeleteJob.java` (107 lines) — same shape; uses `ReactiveMongoTemplate` directly for cross-collection writes; calls `eventService.logEventBlocking(...).block()` for cascade ordering (`patterns.md` line 83).
- `JobRunrMongoConfig.java` (40 lines) — supplies the **synchronous** `com.mongodb.client.MongoClient` (`uuidRepresentation=STANDARD`) required by `MongoDBStorageProvider`. The application's primary Mongo integration is reactive; JobRunr storage uses sync (separate driver, same DB).

**No existing one-shot `BackgroundJob.enqueue(...)` call exists in the codebase.** This is a first-of-its-kind pattern. JobRunr `7.3.2` (build.gradle:30) supports both static `BackgroundJob.enqueue(lambda)` and injected `JobScheduler.enqueue(lambda)`. Per Context7 (JobRunr docs):

```java
// Preferred when the worker is a Spring bean:
BackgroundJob.<ProcessTelegramUpdateJob>enqueue(job -> job.handle(rawUpdateId));

// Or via injected scheduler:
@Autowired JobScheduler jobScheduler;
jobScheduler.<ProcessTelegramUpdateJob>enqueue(job -> job.handle(rawUpdateId));
```

Job arguments must be small primitives / `String` (`raw_updates` `_id` as `String`). Static `BackgroundJob.enqueue(...)` is **blocking** in the sense that it does a synchronous Mongo write to the `jobrunr_*` collection. In the reactive WebFlux request chain, wrap with `Mono.fromCallable(() -> BackgroundJob.enqueue(...)).subscribeOn(Schedulers.boundedElastic())` to keep the Netty event loop free.

`application-test.properties:9` sets `org.jobrunr.background-job-server.enabled=false` — recurring registration still runs against `InMemoryStorageProvider` (`JobRunrInMemoryConfig.java`). In tests, enqueued jobs are accepted but never executed — the worker must be invoked directly (the existing `HardDeleteJobTest` pattern: instantiate + call the `@Job` method).

### `backend/build.gradle` (49 lines, **not** `.kts`)

Confirmed dependencies:
- `org.springframework.boot:spring-boot-starter-data-mongodb-reactive` (reactive Mongo) ✓
- `org.mongodb:mongodb-driver-sync` (used by JobRunr) ✓
- `org.springframework.boot:spring-boot-starter-webflux` ✓
- `org.springframework.boot:spring-boot-starter-validation` ✓
- `org.jobrunr:jobrunr-spring-boot-3-starter:7.3.2` ✓
- Test deps: `okhttp3:mockwebserver:4.12.0` (used by `BotControllerIT`), `testcontainers:mongodb`, `reactor-test`.

**Missing for 04b:**
- **No Micrometer / `spring-boot-starter-actuator`.** User-spec lists three Micrometer counters (`telegram_webhook_received_total`, `telegram_webhook_duration_seconds`, `telegram_webhook_rejected_total`). Two paths:
  - (a) Add `implementation 'io.micrometer:micrometer-core'` (or `spring-boot-starter-actuator`) — opens the broader actuator surface.
  - (b) Use SLF4J structured-log counters only, matching the project's existing observability convention (`patterns.md` line 49: "The project has no Micrometer / actuator dependency, so a sustained Redis outage must be observable via logs alone").
  - **This is a tech-spec decision.** The pattern explicitly notes that observability today is log-based. Adding Micrometer requires a Decision entry.
- No JSR-380 Java Bean Validation runtime is needed for the webhook DTO (we set `@JsonIgnoreProperties(ignoreUnknown=true)` and read fields directly).

---

## 2. New files to add

Proposed paths and responsibilities. Packaging decisions surfaced below for tech-spec.

### Webhook ingestion module (`com.botfunnel.webhook`)

- `webhook/TelegramWebhookController.java` — `@RestController @RequestMapping("/webhooks/telegram")`. One handler: `POST /{projectId}`. Reads `X-Telegram-Bot-Api-Secret-Token` header, body as raw `Map<String, Object>` (or `JsonNode`), Content-Length guard (or codec limit), returns `ResponseEntity<Void>` with explicit status (200, 401-no-body, 404, 413). Performs the SHA-256 verify, persists `RawUpdate` reactively, enqueues JobRunr job (`Mono.fromCallable(...).subscribeOn(boundedElastic())`), records the three Micrometer counters (if added) or structured log lines.
- `webhook/RawUpdate.java` — `@Document(collection = "raw_updates")`. Fields: `id` (`@Id String`), `projectId` (`String`, indexed), `updateId` (`Long`, from Telegram `update_id`), `payload` (`Document` or `Map<String, Object>` — raw JSON), `processingStatus` (enum: `pending|done|failed`), `processingError` (`String`, nullable), `createdAt` (`Instant`). `@CompoundIndex(name="projectId_updateId_unique", def="{'projectId': 1, 'updateId': 1}", unique=true)`. `@Indexed(name="ttl_createdAt", expireAfterSeconds=7776000)` on `createdAt` (90d). Note: TTL indexes require `spring.data.mongodb.auto-index-creation=true` (set in `application.properties:6`) OR manual creation in prod (see Constraints).
- `webhook/RawUpdateRepository.java` — `extends ReactiveMongoRepository<RawUpdate, String>`. Methods: `Mono<RawUpdate> findByProjectIdAndUpdateId(String projectId, Long updateId)` (optional — `save` + duplicate-key catch is sufficient).
- `webhook/ProcessTelegramUpdateJob.java` — `@Component`. JobRunr worker. Method `public void handle(String rawUpdateId)`. Loads `RawUpdate`, parses the `payload` (via `TelegramCommandParser`), branches on message type. Performs the `Bot.ownerChatId` populate (atomic via `ReactiveMongoTemplate.updateFirst` with `ownerChatId=null` predicate). Writes `events`. Calls `SubscriberService.upsertFromTelegramUpdate(...)` and `FunnelTriggerService.fire(...)`. Updates `RawUpdate.processingStatus={done|failed}`. Throws on retryable failure to invoke JobRunr's Failed-queue retry.
- `webhook/TelegramCommandParser.java` — pure-function utility. `parseCommand(messageText): CommandResult { type: START|STOP|NONE, payload: String? }`. Handles `/start [payload]` (payload after first whitespace, trimmed), `/start@botname [payload]` (Telegram appends `@botname` in group chats — strip), `/stop` and `/stop@botname`. Returns `NONE` for any other text. Unit-testable without Spring context.
- `webhook/WebhookSecretVerifier.java` — `@Component` OR static utility. `boolean verify(String headerValue, String storedHashHex)`: hashes header value via SHA-256 → hex → constant-time compare against `storedHashHex` using `MessageDigest.isEqual(byte[], byte[])`. Reuses the SHA-256 algorithm from `BotService.sha256Hex` (`:331-338`) — consider promoting to `common/crypto/` so both producer (Connect) and verifier (webhook) share one implementation.

### Stub services for 04b (interfaces only, real impl in 05/06)

Decision: where do the stubs live? Two options.

- **Option A (target packages):** `com.botfunnel.subscriber.SubscriberService` and `com.botfunnel.funnel.FunnelTriggerService`. Each as a `@Service` interface + a `@Component` no-op impl. Final package for the real epic; 05/06 just replace the no-op with real logic.
- **Option B (`_stubs/` subpackage):** `com.botfunnel.webhook._stubs.SubscriberService` and `…funnel._stubs.FunnelTriggerService`. Forces a package rename in 05/06.

Option A aligns with the codebase's established pattern (no `_stubs` precedent exists; the 04c stub `BotService.sendTestMessage` lives in its final location with a `// arrive in 06b` comment). Recommend Option A; flag for tech-spec.

Stub interfaces:
```java
public interface SubscriberService {
    Mono<Void> upsertFromTelegramUpdate(String projectId, Long telegramBotId, Long chatId,
                                         /* possibly: */ Map<String, Object> from);
}
public interface FunnelTriggerService {
    Mono<Void> fire(String projectId, Long chatId, String triggerType, Map<String, Object> context);
}
```

(Final signatures are tech-spec scope. Stub impls return `Mono.empty()` and emit zero side effects.)

### Tests

- `backend/src/test/java/com/botfunnel/webhook/TelegramWebhookControllerIT.java` — extends `AbstractIntegrationTest`. WebTestClient against the real Mongo, no auth, with CSRF-disable for the webhook path verified.
- `backend/src/test/java/com/botfunnel/webhook/ProcessTelegramUpdateJobTest.java` — invokes the worker directly (mirrors `HardDeleteJobTest`).
- `backend/src/test/java/com/botfunnel/webhook/TelegramCommandParserTest.java` — pure unit test, no Spring context.
- `backend/src/test/java/com/botfunnel/webhook/WebhookSecretVerifierTest.java` — pure unit test; verifies constant-time path is exercised (via `MessageDigest.isEqual`).

---

## 3. Patterns to follow

(Citations: `patterns.md` line numbers + concrete codebase precedents.)

### Security & access control

- **CSRF scoped disable for stateless endpoints** — `patterns.md` line 45: "CSRF disable is reserved for truly stateless endpoints with no cookie auth — webhooks (e.g. `/webhooks/telegram/{projectId}` authenticated by a secret token)". Implement via matcher exclusion, not global `.disable()`.
- **First-match rule on `pathMatchers`** — `patterns.md` line 44 + `SecurityConfig.java:69-72`. `/webhooks/telegram/**` MUST come before `/api/**`.
- **Anti-enumeration uniform 404** — `patterns.md` line 79 + `ProjectService.java:50-63`. The webhook responds with identical 404 (no body, or generic shape) for: missing project, soft-deleted project, project with no CONNECTED bot, malformed projectId. An attacker probing `POST /webhooks/telegram/{any}` cannot distinguish "project does not exist" from "wrong secret" (which is 401-no-body).
- **Mass-assignment defense via `@JsonIgnoreProperties(ignoreUnknown=true)`** — `patterns.md` line 84 + `ConnectBotRequest.java:11`, `CreateProjectRequest.java:11`. The Telegram update payload has ~50 fields the parser ignores (entities, photo, video, etc.). Annotate the inbound DTO (or use `Map<String, Object>` raw + null-safe parsing). Defense applies even though there's no `ownerId` to defend — it prevents Jackson from failing on unexpected fields and stops accidental field-binding to entity fields.

### Secret handling

- **Stored SHA-256, verified by re-hashing** — `patterns.md` line 101 + `BotService.java:152, 331-338`. Producer: `BotService.connect` generates `secureRandom(16)` → hex → sends plaintext to Telegram + stores `sha256(hex)` in `bots.webhookSecretHash`. Verifier (this feature): SHA-256 the incoming header → hex → constant-time compare. Use `java.security.MessageDigest.isEqual` for the byte-array compare (NOT `String.equals`).
- **Token-scrubber on all logged strings** — `patterns.md` line 98 + `TelegramApiClient.scrubTokens(...)`. Webhook payloads can contain bot's own token if the operator misconfigures or if a malicious sender includes one in message text. Pre-log scrub via `TelegramApiClient.scrubTokens(...)` (already public). Never `log.info(payload)` verbatim.

### Reactive controller style

- **Controllers return `Mono<ResponseEntity<…>>`** — `BotController.java:33-37, :39-48`. Use the same shape for `TelegramWebhookController`.
- **Reactive WebClient pattern does NOT apply here** — `patterns.md` line 96 is for outbound HTTP; 04b is inbound-only.
- **Side effects that must not fail the request go through `boundedElastic`** — `patterns.md` line 53 ("Fire-and-forget side effects … `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())…`"). The JobRunr `BackgroundJob.enqueue(...)` call IS such a side effect (synchronous Mongo write). But we DO want enqueue failure to fail the response (otherwise duplicate-200 with no work), so it's not fire-and-forget — it's "wrap blocking I/O on a non-event-loop thread". Same primitive, different error-handling.

### Idempotency

- **`DuplicateKeyException` → semantically equivalent 200** — `BotService.java:182-219` is the precedent for catching `DuplicateKeyException` and mapping by index name. For webhook idempotency: catch on the `raw_updates.save(...)` chain, treat as "duplicate update — already accepted", return 200 OK, do NOT enqueue a second job.

### Race condition test pattern

- **Parallel-request integration test** — `patterns.md` line 104 + `BotControllerIT.java:511-580`. Two parallel POSTs with same body → assert status pair, assert exactly-one persisted row, assert exactly-one enqueued job. For 04b the assertion is `{200, 200}` (both succeed at the HTTP level — duplicate is silent) but exactly **one** `raw_updates` document and exactly **one** JobRunr enqueue. No compensating call here (unlike Connect-path).
- Implementation in the test: `Flux.range(0, 2).parallel(2).flatMap(req -> POST webhook).sequential().collectList()` against the integration WebTestClient bound to `ApplicationContext` (see `AbstractIntegrationTest.java:79-86`).

### Event logging

- **Open-enum event types** — `patterns.md` line 52. Add three constants to the worker: `EVENT_TELEGRAM_MESSAGE_RECEIVED`, `EVENT_TELEGRAM_COMMAND_START`, `EVENT_TELEGRAM_COMMAND_STOP`.
- **`EventService.logEvent` accepts `null` userId** — `TelegramSender.java:122` + `patterns.md` line 117. Webhook is system-context — pass `null`.
- **`EventService.logEventBlocking` for ordering** — `EventService.java:34-38` + `ProjectHardDeleteJob.java:89-95`. Use when the worker must ensure event reaches Mongo before another write (e.g., before `raw_updates.processingStatus=done`, so a JobRunr crash mid-flight cannot leave a "done" raw_update with no event). This is a tech-spec call.

### Schema-add convention for new fields

- **Nullable wrapper, no migration** — `patterns.md` line 124 + `BotRepositoryTest.findById_legacyDocumentWithoutOwnerChatId_readsAsNull`. If the worker needs to read a future field on `Bot`, add the field with getter/setter; missing fields read as `null`. (No new `Bot` fields are required for 04b, but the rule applies if any are added.)

---

## 4. Risks / unknowns to flag for tech-spec

### R1. Reactive timing: 200 OK in <100ms P99

- The end-to-end path is: header verify (in-memory SHA-256) → Mongo find Bot (~1 RTT) → Mongo insert raw_updates (~1 RTT) → JobRunr enqueue (~1 sync RTT on a separate thread pool) → 200 OK.
- **JobRunr `BackgroundJob.enqueue(...)` is synchronous** (per Context7 docs — it writes to `jobrunr_jobs` collection via sync Mongo driver). On a healthy Mongo (~5ms write), three round-trips comfortably fit under 100ms.
- **Crucial:** the enqueue runs on a separate thread to keep the Netty event loop free. Wrap with `Mono.fromCallable(() -> BackgroundJob.<ProcessTelegramUpdateJob>enqueue(job -> job.handle(rawUpdateId))).subscribeOn(Schedulers.boundedElastic())`.
- The MongoDB **reactive** insert for `raw_updates` is non-blocking already.
- No existing precedent in this codebase for `BackgroundJob.enqueue` from a WebFlux handler — this is greenfield. Flag in tech-spec Risks; mitigate with a load-test assertion (e.g., 100 sequential POSTs P99 < 100ms locally against testcontainer Mongo).

### R2. Idempotency strategy — index + duplicate-key catch

- Precedent: `Bot.java:18-28` declares partial unique indexes; `BotService.java:198-219` catches `DuplicateKeyException` and inspects the message text for index-name disambiguation.
- For `raw_updates` the index name is single (`projectId_updateId_unique`), so disambiguation is trivial — any `DuplicateKeyException` on this insert is a duplicate update → silent 200.
- **Unknown:** does `Mono<RawUpdate> save(...)` from `ReactiveMongoRepository` propagate `DuplicateKeyException` directly (as `BotService` shows it does via blocking save), OR is it wrapped? Verify via `TelegramSenderIT` / `BotControllerIT` precedent — yes, reactive save also throws (`BotService.connect.connectAfterPreChecks` is reactive throughout and the catch works).

### R3. Race condition — two parallel webhooks with same `update_id`

- Telegram never sends the same `update_id` twice in normal operation. But retries from Telegram's side (when our 200 response is delayed past the per-update timeout) can produce duplicates.
- Required test (`patterns.md` line 104):
  ```java
  Flux.range(0, 2).parallel(2).flatMap(i -> postWebhook(projectId, sameUpdateJson)).sequential().collectList().block();
  ```
- Assert: status pair `{200, 200}`, `rawUpdateRepository.findAll().count() == 1`, JobRunr Mongo collection enqueued exactly one job (assert via `jobrunr_jobs` collection count, or via the in-memory test storage provider).
- **Difference from Connect-path test** (`BotControllerIT.java:511-580`): no `setWebhook`/`deleteWebhook` count assertions. No compensating call. The "loser" returns 200 just like the winner — duplicate is silent.

### R4. Owner-chat detection — semantics

See section 6 (ownerChatId contract).

### R5. Project soft-delete lookup — performance

- Every webhook hits Mongo at least twice (Bot lookup + raw_updates insert + jobrunr_jobs insert via sync driver).
- The `projectId_status` compound index on `bots` (`Bot.java:19`) covers `findByProjectIdAndStatus(projectId, CONNECTED)` — single indexed query, ~1ms.
- No caching layer for Bot lookups exists today. Adding one (e.g., Caffeine) is premature — `patterns.md` line 119 explicitly defers cache addition until measurable cost is shown.
- **Flag for tech-spec:** if load testing reveals >100ms P99 from the Bot lookup alone, add a Redis cache keyed by `projectId` with TTL = a few minutes + invalidate on Connect/Disconnect events.

### R6. 413 Payload Too Large enforcement

- WebFlux's default `spring.codec.max-in-memory-size` is 256KB. User-spec says 413 for payloads >1MB. Options:
  - Bump the codec limit globally to 1MB and enforce explicit `Content-Length > 1MB → 413` in the controller.
  - Apply a `WebFilter` that inspects `Content-Length` on the webhook path only.
- Verify: does the existing config set `spring.codec.max-in-memory-size`? `application.properties` does not — default 256KB applies. Telegram updates with `text` only fit easily in 256KB but media-rich updates (photo `file_id` arrays etc.) can be larger.
- Flag for tech-spec.

### R7. Micrometer dependency

- `build.gradle:21-40` has no `io.micrometer:*` and no `spring-boot-starter-actuator`.
- `patterns.md` line 49 explicitly notes the project has no Micrometer today.
- User-spec calls for three Micrometer counters. **Tech-spec decision required**: (a) add the dep, (b) implement counters as `MeterRegistry` (would need bean injection), or (c) downgrade to structured log lines.

### R8. JobRunr Failed queue + `raw_updates.processingStatus`

- JobRunr 7.x has a built-in retry strategy (default: 10 retries with exponential backoff) and a "Failed" state surfaced in the JobRunr dashboard (currently `org.jobrunr.dashboard.enabled=false` in `application.properties:34`).
- User-spec requires both: built-in JobRunr Failed queue AND `raw_updates.processingStatus={pending|done|failed}` + `processingError`.
- Cleanest implementation: the worker catches its own exceptions, writes `processingStatus=failed` + `processingError=<message>` to `raw_updates`, then **rethrows** so JobRunr retries via its own mechanism. After retries exhausted, JobRunr marks the job FAILED in its own collection; our `raw_updates` row still shows `failed` with the last error.
- Risk: double-counting the failure. Mitigation: keep `processingStatus` writes atomic with `findAndModify` so concurrent JobRunr workers (retries) don't overwrite each other.

### R9. SecurityConfig CSRF matcher syntax

- Spring Security 6.x reactive: `.csrf(csrf -> csrf.requireCsrfProtectionMatcher(new NegatedServerWebExchangeMatcher(new PathPatternParserServerWebExchangeMatcher("/webhooks/telegram/**"))))`. Confirm exact API in Spring Security 6.5.x (Boot 3.5 ships with that).
- Cross-check via Context7 / Spring Security docs at tech-spec time.

### R10. JobRunr context in tests

- `application-test.properties:9` disables the background-job-server. Recurring jobs register; enqueued jobs are stored but never executed.
- `ProcessTelegramUpdateJobTest` should invoke the worker method directly (same shape as `HardDeleteJobTest`).
- Integration tests (controller IT) must assert "exactly one job enqueued" — read `jobrunr_jobs` collection count, OR (cleaner) inject `org.jobrunr.storage.StorageProvider` (`InMemoryStorageProvider` in tests via `JobRunrInMemoryConfig`) and call `getJobs(...)`.

### R11. Telegram payload field naming (`update_id` vs `updateId`)

- Telegram uses `snake_case` (`update_id`, `message.chat.id`, `message.from.id`). Jackson default is `camelCase` → field mismatch.
- Either: (a) annotate the DTO with `@JsonProperty("update_id") Long updateId;` per field, OR (b) configure `PropertyNamingStrategy.SNAKE_CASE` on the inbound DTO via `@JsonNaming`. Codebase precedent: `TelegramResult.java`, `TelegramUser.java`, `TelegramSendParameters.java`, `TelegramSendResult.java` all use Java record fields with snake-case names directly (`first_name`, `chat_id`, `retry_after`) — they are records, not Bean-style classes. Mirror that pattern for the inbound update DTO if one is created (otherwise use raw `JsonNode` / `Map<String, Object>`).

---

## 5. Tests to study

### `backend/src/test/java/com/botfunnel/AbstractIntegrationTest.java` (95 lines)

- Singleton Testcontainers: MongoDB 8.0, Redis 7.4-alpine, Mailpit. Started once in static block.
- `@DynamicPropertySource` for Mongo URI + Redis URL.
- `@Import({MailpitTestConfig.class, JobRunrInMemoryConfig.class})` — JobRunr storage is `InMemoryStorageProvider` in tests.
- WebTestClient re-bound per test to the application context (`:81-86`) so CSRF mutator works.

### `backend/src/test/java/com/botfunnel/bot/BotControllerIT.java` (1100+ lines)

Most directly relevant for 04b. Key patterns to mirror:
- Class-level `MockWebServer` (`:82-91`) — not needed in 04b (no outbound), but the `@DynamicPropertySource` static-init shape is the canonical reference.
- `@BeforeEach cleanAndSeed` (`:120-147`) — clean Mongo + Redis + register `ListAppender` for log assertions.
- Race-condition test pattern (`:511-580`) — two parallel POSTs via `Mono.zip(r1, r2)`, status-pair + invariant assertions.
- Idempotency-like test (`postConnect_persistFails` lines around `:480`) — `@MockitoSpyBean BotRepository` with `doReturn(Mono.error(...)).when(spy).save(any())` for forced-failure cases.
- Token-leak assertions (`:399`) — assert no field of any response body matches the token regex; assert no string value of any event's metadata matches it.

### `backend/src/test/java/com/botfunnel/SecurityBlockTest.java` (38 lines)

Pattern for **skeleton-only** security-rule tests with `@MockitoBean` mocking out the live DB clients (`MongoClient`, `RedisConnectionFactory`, `ReactiveRedisConnectionFactory`). For 04b: a slice-style `WebhookSecurityBlockTest` could assert that `POST /webhooks/telegram/{id}` is permitAll AND returns 401 (no body) with no secret AND `/api/v1/projects/{id}/bot/test-message` still requires auth — without booting the full integration container.

### `backend/src/test/java/com/botfunnel/jobs/HardDeleteJobTest.java` (3.5KB)

Pattern for invoking a `@Recurring` worker method directly — relevant for `ProcessTelegramUpdateJobTest`. Worker method is plain Java; the test seeds Mongo state, calls `job.handle(rawUpdateId)`, asserts outcomes.

### `backend/src/test/java/com/botfunnel/bot/BotRepositoryTest.java` (117 lines)

Pattern for repository tests with `StepVerifier`. For 04b: `RawUpdateRepositoryTest` to confirm the unique compound index actually fires (insert two with same `(projectId, updateId)` → second errors).

### `backend/src/test/java/com/botfunnel/bot/TelegramSenderIT.java` and `TelegramSenderTest.java`

Pattern for `@MockitoSpyBean` on `EventService` to assert event emission; `Awaitility.await()` for asynchronous event writes (fire-and-forget `logEvent`); per-log-site `ListAppender` assertions. Replicate for the worker's event writes.

### `backend/src/test/java/com/botfunnel/profile/WithMockAppUser.java`

Custom annotation that injects an `AppUserDetails` principal into the reactive security context for `@SpringBootTest` controller calls. **Not needed in 04b** (webhook is unauthenticated) but if you want to assert that authenticated users CANNOT reach the webhook (which is non-blocking and intentional — `permitAll`), this annotation is the helper.

---

## 6. `Bot.ownerChatId` contract

### Where the contract was defined

- **Definition source:** 04c `work/completed/07-telegram-sender/user-spec.md` and `tech-spec.md`. 04a (06-bot-connection) defined the column shape; 04c added the Java field (Decision 14) and consumed it via `BotService.sendTestMessage`. 04b owns the **write side**.
- **No code today writes `Bot.ownerChatId`.** Verified by grep: `grep -rn "setOwnerChatId" backend/src/main/java/` returns only `Bot.java` (the setter declaration). Production reads it via `BotService.java:124` (`bot.getOwnerChatId()`); tests seed it manually via `bot.setOwnerChatId(...)` in `BotControllerIT.seedConnectedBotWithRealEncryption(:206-220)` and `TelegramSenderIT`.

### Agreed semantics (from 04c specs)

Quoted from `work/completed/07-telegram-sender/user-spec.md`:

> Line 48: **"У production `Bot.ownerChatId` лишається null до релізу Epic 04b — гілка з реальним send не виконується ніде, окрім інтеграційних тестів і ручного dev/staging smeoke з ручним Mongo seed."**

Quoted from `work/completed/07-telegram-sender/tech-spec.md`:

> Line 16: **"The `Bot.ownerChatId` value is populated by Epic 04b webhook ingestion in a later release."**

The 04c specs do **not** prescribe the *exact* trigger ("which webhook update populates `ownerChatId`"). They only specify the read-side semantics:
- `ownerChatId == null` → 422 `owner_chat_id_unknown` with verbatim message `"Send /start to your bot in Telegram first, then try again"`.
- `ownerChatId != null` → call `TelegramSender.sendText(bot.id, bot.ownerChatId, body, null, ownerId)`.

The error message itself encodes the implicit contract: **the owner sends `/start` to the bot from their own Telegram account**. Verified by `work/completed/06-bot-connection/user-spec.md:96-100`:

> "The owner sends `/start` to the bot from their own Telegram account"

### Recommended 04b rule (for tech-spec to ratify)

Synthesizing across 04a + 04c:

**Populate `Bot.ownerChatId = message.chat.id` when ALL of the following hold:**

1. The inbound update contains a `message` with `text == "/start"` or starts with `"/start "` (with payload).
2. `message.chat.type == "private"` (1-on-1 DM with the bot).
3. The matched `Bot` (looked up via the `{projectId}` URL path) has `ownerChatId IS NULL`.
4. The matched `Bot` has `status == CONNECTED`.

**First-eligible-`/start`-wins** semantics. Subsequent `/start` from other private chats (e.g., random users who start using the bot) do NOT overwrite. Atomic update:

```java
ReactiveMongoTemplate.findAndModify(
    Query.query(Criteria.where("_id").is(botId)
                        .and("status").is("CONNECTED")
                        .and("ownerChatId").is(null)),
    Update.update("ownerChatId", chatId),
    Bot.class
);
```

**Rationale notes (clarifying the user's "actually Telegram does not give us bot ownership intrinsically" concern):**

- The `message.from.id` is the **sender's Telegram user id**, not the bot owner's. Telegram's API does NOT expose bot ownership.
- The user-spec sentence in the task prompt that says "`message.from.id` matches `bots.telegramBotId`'s owner" is incorrect on its face — `bots.telegramBotId` is the **bot's** Telegram id; comparing it to `message.from.id` would match only if the **bot were sending to itself**, which is impossible.
- The pragmatic approximation used industry-wide (and matching 04a's UX: "send `/start` to your bot first") is: **first private-chat `/start` to a connected bot wins**. The platform trusts that the operator (project owner) is the first person who starts their own bot from their own account immediately after Connect — the staging-smoke runbook (`docs/staging-smoke/06-bot-connection.md`, line 4 in 06-bot-connection user-spec) literally says "From your own Telegram account, write /start to the new bot."
- Threat model accepted: a fast attacker who knows `@botusername` immediately after operator Connect could race the operator and claim `ownerChatId`. Mitigations available (not required for 04b unless tech-spec elevates them): (a) staging-only feature flag, (b) require operator to confirm in UI before persisting, (c) compare `message.from.username` against an operator-set value. **None of these are mentioned in 04a or 04c specs** — proceed with first-wins and document the trade-off in 04b tech-spec.

### Files that confirm the contract

- `work/completed/07-telegram-sender/user-spec.md:44-48, 84-87, 117, 121` — read-side behavior, 04b dependency.
- `work/completed/07-telegram-sender/tech-spec.md:16, 77-78, 174-178, 241-251` — Java field declaration + Decision 14 (nullable, no migration, no index).
- `work/completed/06-bot-connection/user-spec.md:96-100, 217, 315, 453-454` — "send `/start` first" UX language.
- `work/completed/06-bot-connection/tech-spec.md:113-118, 192-197` (Decision 7) — 422 stub contract, deferred to 04b.
- `backend/src/main/java/com/botfunnel/bot/Bot.java:51, 81-82` — field declaration + accessors.
- `backend/src/main/java/com/botfunnel/bot/BotService.java:118-139` — null-branch consumer.
- `docs/staging-smoke/07-telegram-sender.md` — opt-in manual procedure that 04b must automate.

---

## Summary of files involved

**Read first (no change):** `backend/src/main/java/com/botfunnel/bot/{Bot.java, BotRepository.java, BotStatus.java}`, `backend/src/main/java/com/botfunnel/project/{Project.java, ProjectRepository.java}`, `backend/src/main/java/com/botfunnel/events/{Event.java, EventService.java, EventRepository.java}`, `backend/src/main/java/com/botfunnel/common/{AppException.java, ErrorResponse.java, GlobalErrorHandler.java}`, `backend/src/main/java/com/botfunnel/bot/TelegramApiClient.java` (for `scrubTokens`), `backend/src/main/java/com/botfunnel/jobs/{HardDeleteJob.java, ProjectHardDeleteJob.java, JobRunrMongoConfig.java}`.

**Modify:** `backend/src/main/java/com/botfunnel/security/SecurityConfig.java` (add permitAll + CSRF matcher), `backend/src/main/java/com/botfunnel/common/AppException.java` (optional factory `payloadTooLarge` or document why we don't need it), `backend/build.gradle` (optional Micrometer dep), `backend/src/main/java/com/botfunnel/bot/BotService.java` (optionally promote `sha256Hex` to `common/`).

**Create:** `backend/src/main/java/com/botfunnel/webhook/{TelegramWebhookController.java, RawUpdate.java, RawUpdateRepository.java, ProcessTelegramUpdateJob.java, TelegramCommandParser.java, WebhookSecretVerifier.java}`, `backend/src/main/java/com/botfunnel/subscriber/SubscriberService.java` (+ no-op impl), `backend/src/main/java/com/botfunnel/funnel/FunnelTriggerService.java` (+ no-op impl), plus matching test files.
