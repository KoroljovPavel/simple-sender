---
created: 2026-05-16
status: approved
branch: dev
size: L
---

# Tech Spec: 08-webhook-ingestion

## Solution

First inbound surface of the platform. `POST /webhooks/telegram/{projectId}` does: scoped `WebFilter` size guard → SHA-256 secret verify against `bots.webhookSecretHash` → atomic project-exists + bot-CONNECTED guard → idempotent insert to `raw_updates` (unique compound `(projectId, updateId)`) → JobRunr `BackgroundJob.enqueue(deterministicJobId, ProcessTelegramUpdateJob)` wrapped in `Mono.fromCallable(...).subscribeOn(boundedElastic())` → `200 OK` to Telegram in <100ms P99.

Worker (JobRunr-thread, blocking-safe) loads the raw update, short-circuits if `processingStatus == DONE` (re-entry guard for JobRunr retries), parses `/start [payload]` / `/stop` (strips `@botname` suffix), atomically populates `Bot.ownerChatId` on the first private `/start` against a CONNECTED bot with `ownerChatId IS NULL` (closes the unobservable 04c happy-path), invokes no-op `SubscriberService` + `FunnelTriggerService` stubs in their final packages (real impl deferred to Epic 05/06), and writes events via `EventService.logEventBlocking` before flipping `raw_updates.processingStatus=done`.

Greenfield patterns introduced: WebFlux→JobRunr enqueue bridge with deterministic job IDs, scoped CSRF disable via negated `PathPatternParserServerWebExchangeMatcher`, path-scoped 1 MB `WebFilter` payload cap (rejects chunked-encoding too), first Micrometer dependency (`micrometer-core` + explicit `@Bean SimpleMeterRegistry`, no actuator). Promotes `BotService.sha256Hex` to `common/crypto/Sha256Hex` (second consumer triggers DRY threshold).

## Architecture

### What we're building/modifying

**Create — `com.botfunnel.webhook`:**
- **`TelegramWebhookController`** — `POST /{projectId}` handler. Bot lookup + project lookup (deletedAt/hard-delete check) + secret verify + idempotent persist + idempotent enqueue. Owns 3 counters (received, duration, rejected{invalid_secret|project_not_found|duplicate}).
- **`RawUpdate`** — `@Document("raw_updates")` with class-level unique `@CompoundIndex` on `(projectId, updateId)` and TTL partial-filter on uppercase enum names `PENDING, DONE`.
- **`RawUpdateRepository`** — `extends ReactiveMongoRepository<RawUpdate, String>`.
- **`RawUpdateStatus`** — enum `PENDING | DONE | FAILED`.
- **`ProcessTelegramUpdateJob`** — JobRunr worker `@Component` with `public void handle(String rawUpdateId)`. First action: re-entry guard.
- **`WebhookSecretVerifier`** — `@Component`. SHA-256 + constant-time `MessageDigest.isEqual`.
- **`WebhookPayloadSizeFilter`** — `@Component implements WebFilter` scoped to `/webhooks/telegram/{projectId}`. Rejects (a) `Transfer-Encoding: chunked` header, (b) missing `Content-Length`, (c) `Content-Length > 1_048_576`. Owns the `rejected{payload_too_large}` counter (filter short-circuits before controller — counter MUST live here).
- **`TelegramUpdate` + nested `Message`, `Chat`, `User`, `CallbackQuery`** — `record`s with snake_case fields and stub `JsonNode` slots for non-message update kinds to support `updateKind` resolution per AC11. `@JsonIgnoreProperties(ignoreUnknown=true)`.
- **`TelegramCommandParser`** — pure static utility. Strips `@botname` suffix.
- **`MeterRegistryConfig`** — `@Configuration` providing explicit `@Bean SimpleMeterRegistry`. Required because `spring-boot-starter-actuator` is NOT on classpath; `micrometer-core` alone does not register the bean.

**Create — `com.botfunnel.subscriber` (stub interface + no-op impl, real impl in Epic 05):**
- **`SubscriberService`** interface — `upsertFromTelegramUpdate(...)`, `markUnsubscribed(...)`. Returns `Mono<Void>`.
- **`NoOpSubscriberService`** `@Service` — returns `Mono.empty()`.

**Create — `com.botfunnel.funnel` (stub interface + no-op impl, real impl in Epic 06):**
- **`FunnelTriggerService`** interface — `fire(projectId, chatId, triggerType, payload)`, `cancelActiveFor(projectId, chatId)`. Returns `Mono<Void>`.
- **`NoOpFunnelTriggerService`** `@Service` — returns `Mono.empty()`.

**Create — `com.botfunnel.common.crypto`:**
- **`Sha256Hex`** — public static utility `hex(String input): String`. Promoted from private `BotService.sha256Hex`.

**Modify:**
- **`SecurityConfig`** — current state uses `CookieServerCsrfTokenRepository.withHttpOnlyFalse()` + `ServerCsrfTokenRequestAttributeHandler` (the non-Xor variant; NOT `.csrf().disable()`). Add `.pathMatchers("/webhooks/telegram/{projectId}").permitAll()` BEFORE `/api/**.authenticated()` (use single-segment path, not `/**`, to prevent future sub-path leak). Scoped CSRF disable via `csrf.requireCsrfProtectionMatcher(new NegatedServerWebExchangeMatcher(new PathPatternParserServerWebExchangeMatcher("/webhooks/telegram/{projectId}")))`. CSRF stays enforced on every other path.
- **`BotService`** — replace private `sha256Hex(...)` call with `Sha256Hex.hex(...)`. Delete the private method.
- **`backend/build.gradle`** — add `implementation 'io.micrometer:micrometer-core'`.

**Reuse unchanged:**
- `BotRepository.findByProjectIdAndStatus`, `ProjectRepository.findById`, `EventService.logEventBlocking`, `TelegramApiClient.scrubTokens` (static), `Bot.ownerChatId` field (already present from 04c), `ReactiveMongoTemplate` (for atomic `findAndModify`), `AbstractIntegrationTest` + `JobRunrInMemoryConfig` (tests).

### How it works

**Inbound HTTP path (Netty event-loop, target <100ms P99):**

1. `WebhookPayloadSizeFilter` (matches `/webhooks/telegram/{projectId}`, `@Order(Ordered.HIGHEST_PRECEDENCE + 10)` so it runs BEFORE the security filter chain). Checks:
   - `Transfer-Encoding: chunked` present → 413 (empty body) + `rejected_total{reason=payload_too_large}`++.
   - `Content-Length` missing → 413 (empty body) + counter++.
   - `Content-Length > 1_048_576` → 413 (empty body) + counter++.
2. Spring Security filter chain — `permitAll` for `/webhooks/telegram/{projectId}` (single segment), CSRF disabled via negated matcher.
3. `TelegramWebhookController.receive(projectId, headerSecret, body)`:
   - `botRepository.findByProjectIdAndStatus(projectId, CONNECTED)` → empty → `rejected_total{reason=project_not_found}`++ + 404 empty body. `onErrorMap(IllegalArgumentException → notFound)` handles malformed ObjectId.
   - `projectRepository.findById(projectId)` (chained) → empty (hard-deleted) OR `deletedAt != null` (soft-deleted) → same 404 path. Resolves user-spec R6 (cascade gap — `ProjectService.softDelete` does not currently disconnect bots; second lookup absorbs the gap without touching Epic 03).
   - `WebhookSecretVerifier.verify(headerSecret, bot.webhookSecretHash)` → false → `rejected_total{reason=invalid_secret}`++ + 401 empty body.
   - `rawUpdateRepository.save(new RawUpdate(projectId, body.update_id(), body, PENDING, now))`:
     - Success → `enqueueIdempotent(rawUpdate.getId())` (see below) → `received_total{projectId}`++ + 200 OK.
     - `DuplicateKeyException` → `rejected_total{reason=duplicate}`++ + `enqueueIdempotent(rawUpdate.getId())` STILL called (self-healing — recovers from previous mid-flight enqueue failure; deterministic job ID makes re-enqueue a no-op) + 200 OK.
   - `enqueueIdempotent(rawUpdateId)`: `UUID jobId = UUID.nameUUIDFromBytes(rawUpdateId.getBytes(UTF_8))`; `Mono.fromCallable(() -> BackgroundJob.<ProcessTelegramUpdateJob>enqueue(jobId, j -> j.handle(rawUpdateId))).subscribeOn(Schedulers.boundedElastic())`. JobRunr `enqueue(UUID, lambda)` is idempotent — duplicate jobId is a no-op.
   - Timer `webhook_duration_seconds` wraps body.

**Worker path (JobRunr-thread, blocking-safe per `HardDeleteJob` / `ProjectHardDeleteJob` precedent):**

1. `handle(rawUpdateId)` — load `RawUpdate` (`.block()`). **Re-entry guard:** if `processingStatus == DONE`, return immediately (no event, no stub call, no counter tick — already processed, this is a JobRunr retry overlap). If `processingStatus == FAILED` and the call is a retry, treat as new attempt: continue.
2. Deserialize `payload` to `TelegramUpdate`.
3. Branch by update kind:
   - `update.message() != null && message.text() startsWith "/start"` (private chat) — `TelegramCommandParser.parse(text)`, get payload. If `bot.ownerChatId == null` AND `chat.type=="private"` AND `bot.status==CONNECTED` → `reactiveMongoTemplate.findAndModify(Query(_id=botId, status=CONNECTED, ownerChatId=null), Update.set("ownerChatId", chat.id), Bot.class).block()`. Call `SubscriberService.upsertFromTelegramUpdate(...).block()` + `FunnelTriggerService.fire(projectId, chat.id, "on_start", payload).block()`. `logEventBlocking(telegram_command_start, metadata.startPayload=payload)` (always non-null, possibly empty string).
   - `/stop` (private) — `markUnsubscribed(...).block()` + `cancelActiveFor(...).block()` + event `telegram_command_stop`.
   - `/start` in group/supergroup/channel — event only, no ownerChatId populate, no Subscriber upsert (per AC9).
   - Unknown command `/foo...` private → event `telegram_message_received`, no stubs.
   - Plain text private → `upsertFromTelegramUpdate(...).block()` + event `telegram_message_received`.
   - Plain text non-private → event only.
   - Non-message updates with a modeled JsonNode slot (`callback_query`, `edited_message`, `channel_post`, `edited_channel_post`, `my_chat_member`, `chat_member`, `inline_query`, `shipping_query`, `pre_checkout_query`, `poll_answer`) → event `telegram_update_other` with `metadata.updateKind=<jsonFieldName>` (e.g. `"my_chat_member"`), no stubs.
   - Truly unknown update kind (none of the modeled slots present) → event `telegram_update_other` with `updateKind="unknown"`.
   - Missing `message` OR `message.from == null` (channel posts) — safe-navigate → `updateKind="unknown"`.
4. Cascade order: `logEventBlocking(...).block()` BEFORE `rawUpdateRepository.save(processingStatus=DONE)`. Mirror of `ProjectHardDeleteJob` ordering precedent. On exception: catch, atomically write `processingStatus=FAILED` + `processingError=scrubTokens(ex.getMessage()).substring(0, min(len, 1024))` via `findAndModify`, increment `telegram_worker_outcome_total{outcome=failure}`, **rethrow** → JobRunr retry.
5. Every log site that touches payload-derived strings → wrap through `TelegramApiClient.scrubTokens(...)`. Enumerated sites: see Token-scrubber sites subsection in Risks.

### Shared resources

| Resource | Owner (creates) | Consumers | Instance count |
|----------|----------------|-----------|----------------|
| `MeterRegistry` | `MeterRegistryConfig @Bean SimpleMeterRegistry` (new, this feature) | `TelegramWebhookController`, `WebhookPayloadSizeFilter`, `ProcessTelegramUpdateJob` | 1 (singleton bean) |
| `ReactiveMongoTemplate` | Spring Data MongoDB autoconfig (existing) | `ProcessTelegramUpdateJob` (atomic `findAndModify` for `ownerChatId` and `processingStatus`) | 1 (singleton, platform-wide) |
| `JobRunr StorageProvider` (sync `MongoClient`) | `JobRunrMongoConfig` (existing) | `BackgroundJob.enqueue(UUID, lambda)` static call from controller | 1 (singleton, platform-wide) |

## Decisions

### Decision 1: 404 lookup — `Bot` CONNECTED + explicit `Project` existence + `deletedAt == null`
**Decision:** Two sequential indexed reads: `botRepository.findByProjectIdAndStatus(projectId, CONNECTED)` first, then `projectRepository.findById(projectId)` and reject if (a) empty (hard-deleted project with orphan bot row), (b) `deletedAt != null` (soft-deleted). Both `IllegalArgumentException` (malformed ObjectId) and empty bot result collapse to uniform 404 with no body.
**Rationale:** Supports user-spec AC3. Resolves R6 cascade-gap: `ProjectService.softDelete` only sets `Project.deletedAt`; it does NOT cascade to `BotService.disconnect`. `ProjectHardDeleteJob` deletes the Project document but does NOT cascade to bots either (verified by reading the job source). So `findByProjectIdAndStatus` alone would still find a CONNECTED bot for both soft- and hard-deleted projects. Adding a project-existence + deletedAt check in the webhook handler keeps 04b's blast radius minimal. Latency cost: +1 indexed `_id` read (~5ms).
**Alternatives considered:** (a) Extend `ProjectService.softDelete` to invoke force-disconnect on the bot — rejected: out of 04b scope, Telegram `deleteWebhook` coordination, behavior change for Epic 03. (b) Single aggregation pipeline (`$lookup` bots+projects) — rejected: more complex query for marginal latency win.

### Decision 2: Secret token — SHA-256 hex storage, `MessageDigest.isEqual` constant-time verify
**Decision:** Plaintext secret exists only in memory at Telegram and during Connect. Stored as hex SHA-256 in `bots.webhookSecretHash`. Verifier re-hashes incoming header, constant-time compares via `MessageDigest.isEqual(byte[], byte[])`.
**Rationale:** Supports user-spec AC2. Mirror of existing producer (`patterns.md` Secret handling section + `BotService.connect`). Mongo backup leak then reveals only hashes. `String.equals` would be timing-vulnerable.
**Alternatives considered:** AES-256-GCM encryption of the secret — rejected: we never need pre-image; "does this candidate hash equal the stored hash" is the only op.

### Decision 3: 401 / 404 / 413 emitted as `ResponseEntity.status(...).build()` (no `AppException`, no body)
**Decision:** Webhook controller returns `Mono<ResponseEntity<Void>>` with explicit status. Skip `AppException` + `GlobalErrorHandler` path entirely. Keep 401 distinct from 404 per user-spec AC2 vs AC3.
**Rationale:** Supports user-spec AC2 (401 no body), AC3 (404 no body), AC4 (413 no body). `GlobalErrorHandler.handleAppException` always writes `{"message":..., "code":...}` body. Per user-spec, the distinct 401/404 codes are intentional (AC2 ratifies "Bad/missing secret token → 401, тело пустое" separately from AC3 anti-enumeration 404 for project state). Adding a one-off "no-body" exception type + handler branch is more code than just returning the response directly.
**Alternatives considered:** (a) Collapse 401 + 404 to uniform 404 (security M3 suggestion) — rejected: contradicts user-spec ratified AC2/AC3 distinction. (b) New `AppException.noBody(status)` factory + handler branch — rejected: scope creep for one endpoint.

### Decision 4: Idempotency — unique compound `(projectId, updateId)` + `DuplicateKeyException` → re-enqueue (self-heal) + 200
**Decision:** Class-level `@CompoundIndex(name="projectId_updateId_unique", def="{'projectId': 1, 'updateId': 1}", unique=true)` on `RawUpdate`. On `DuplicateKeyException` from `rawUpdateRepository.save(...)`: increment `rejected_total{reason=duplicate}`, **still call** `enqueueIdempotent(rawUpdateId)` (deterministic job ID, idempotent), return 200 OK silently.
**Rationale:** Supports user-spec AC5. The "still call enqueue on duplicate" branch is the self-heal for Security C1 (silent message loss): if the first request's save succeeded but enqueue failed, Telegram retries, second request hits DuplicateKey, but the second enqueue call (idempotent via deterministic UUID per Decision 11) succeeds. Without this branch, the PENDING row orphans forever.
**Alternatives considered:** (a) Redis-based dedup — rejected: separate failure mode, no real benefit over Mongo unique index. (b) Pre-check then save — rejected: race window between read and write. (c) Ignore the self-heal scenario — rejected: silent data loss flagged as critical by security audit.

### Decision 5: `ownerChatId` populate — atomic `findAndModify` with predicate `ownerChatId=null`
**Decision:** `reactiveMongoTemplate.findAndModify(Query(_id=botId, status=CONNECTED, ownerChatId=null), Update.set("ownerChatId", chatId), Bot.class)` inside the worker. First eligible private `/start` wins; subsequent calls are no-ops (predicate fails).
**Rationale:** Supports user-spec AC6. Atomic CAS eliminates read-then-write race when two private `/start` from different chats land in parallel JobRunr workers. Threat model accepted per user-spec R2.
**Alternatives considered:** (a) Read bot → check ownerChatId → conditional save — rejected: read-then-write race. (b) Optimistic-lock `@Version` field — rejected: adds a field for a single use case.

### Decision 6: `TelegramUpdate` as typed Java `record` with snake_case fields + `JsonNode` stubs for non-message types
**Decision:** `record TelegramUpdate(Long update_id, Message message, Message edited_message, Message channel_post, Message edited_channel_post, JsonNode callback_query, JsonNode my_chat_member, JsonNode chat_member, JsonNode inline_query, JsonNode shipping_query, JsonNode pre_checkout_query, JsonNode poll_answer)` with `@JsonIgnoreProperties(ignoreUnknown=true)`. Nested `Message`, `Chat`, `User` records mirror the same shape. Non-message slots are `JsonNode` because 04b only needs to detect their presence (for `updateKind` resolution per AC11); their payload schemas belong to Epic 05/06.
**Rationale:** Supports user-spec AC11 (`metadata.updateKind=<имя поля>` — the JSON field name from the update). The worker resolves `updateKind` by checking which slot is non-null. Modeling 6 non-message kinds explicitly closes the AC11 contract for the Telegram update types Epic 05/06 are likely to handle. `@JsonIgnoreProperties` is the mass-assignment defense.
**Alternatives considered:** (a) Only model `message` + `callback_query`; everything else `updateKind="unknown"` — rejected: AC11 explicitly enumerates `callback_query`, `edited_message`, `channel_post`, `my_chat_member` as types we must distinguish. (b) Use `Map<String, JsonNode>` + `@JsonAnySetter` — rejected: untyped, breaks IDE field navigation in Epic 05/06. (c) `Map<String, Object>` raw payload — rejected: no compile-time check on field renames.

### Decision 7: `Bot.ownerChatId` already present (from 04c) — no schema change in 04b
**Decision:** `Bot.java:51` already declares `private Long ownerChatId;` from Epic 04c (verified by direct file read). 04b adds the write side only via the atomic `findAndModify` in Decision 5.
**Rationale:** Supports user-spec AC17. Field-add convention: nullable wrapper, missing-field reads as `null` for legacy docs — pinned by `BotRepositoryTest.findById_legacyDocumentWithoutOwnerChatId_readsAsNull`. No query path filters on `ownerChatId`, so no index needed.
**Alternatives considered:** None — field already exists; tech-spec only documents the consumption side.

### Decision 8: TTL on `raw_updates.createdAt` — partial filter on UPPERCASE enum names, `expireAfter="90d"`
**Decision:** `@Indexed(name="ttl_createdAt", expireAfter="90d", partialFilter="{ 'processingStatus': { $in: ['PENDING', 'DONE'] } }")` on `RawUpdate.createdAt`. Uppercase enum literals match Spring Data MongoDB's default enum-as-`name()` persistence (`Bot.java` partial-filter precedent uses `'CONNECTED'` uppercase). `expireAfter` (Duration string) replaces deprecated `expireAfterSeconds`.
**Rationale:** Supports user-spec AC13. Uppercase fix per completeness validator F5 (critical): lowercase `['pending','done']` would never match → TTL would apply to ALL rows including FAILED, opposite of AC13's audit-retention intent. `expireAfter="90d"` per Spring Data MongoDB 4.5.4 deprecation of `expireAfterSeconds`.
**Alternatives considered:** (a) `@Scheduled` cron deleting by age — rejected: more code, more failure modes than native TTL. (b) Custom enum codec converting to lowercase strings — rejected: deviates from `Bot.java` precedent and `BotStatus` convention.

### Decision 9: DLQ = JobRunr Failed-queue + `processingStatus` + worker re-entry guard
**Decision:** Worker catches its own exceptions, atomically writes `processingStatus=FAILED` + `processingError=scrubTokens(ex.getMessage()).substring(0, min(len, 1024))` via `findAndModify`, increments `telegram_worker_outcome_total{outcome=failure}`, then **rethrows** to trigger JobRunr's default retry (10 attempts, exponential backoff). After retries exhausted, JobRunr marks job FAILED; `raw_updates.processingStatus=FAILED` shows the last error. **Re-entry guard:** worker's first action is "if `processingStatus==DONE` return" (handles JobRunr retry overlap when prior attempt succeeded but JobRunr didn't observe success in time).
**Rationale:** Supports user-spec AC14. Re-entry guard prevents double-firing events + stub calls on retry overlap. Scrubber + truncate on `processingError` per security audit M6 — payload-derived strings may contain tokens or user PII; 1024 char limit caps log-bloat.
**Alternatives considered:** (a) Custom retry loop in worker — rejected: duplicates JobRunr's mechanism. (b) Skip re-entry guard — rejected: silent double-event on retry overlap. (c) Persist full stack trace in `processingError` — rejected: payload-data leak risk + storage bloat.

### Decision 10: Observability — explicit `@Bean SimpleMeterRegistry` + 4 Micrometer counters, filter owns its own counter
**Decision:** Add `io.micrometer:micrometer-core` to `backend/build.gradle` AND register explicit `@Bean public MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }` in new `MeterRegistryConfig`. Without `spring-boot-starter-actuator`, Spring Boot does NOT autoconfigure a `MeterRegistry` (the `MetricsAutoConfiguration` classes ship in `spring-boot-actuator-autoconfigure`). Counters:
- `telegram_webhook_received_total{projectId}` — **TelegramWebhookController** (success path).
- `telegram_webhook_duration_seconds` — **TelegramWebhookController** Timer wrap.
- `telegram_webhook_rejected_total{reason=invalid_secret|project_not_found|duplicate}` — **TelegramWebhookController**.
- `telegram_webhook_rejected_total{reason=payload_too_large}` — **`WebhookPayloadSizeFilter`** (filter short-circuits before controller; counter MUST live where the rejection happens, or it will sit at 0 in production).
- `telegram_worker_outcome_total{outcome=success|failure}` — **ProcessTelegramUpdateJob**.
**Rationale:** Supports user-spec AC16. Explicit-bean approach per skeptic critical finding (`micrometer-core` alone insufficient). Splitting `rejected_total{payload_too_large}` ownership to the filter per completeness F4 / security M8 (critical) — counter must increment where the rejection actually happens.
**Alternatives considered:** (a) Add `spring-boot-starter-actuator` and disable endpoints via `management.endpoints.enabled-by-default=false` — viable, rejected: pulls in broader autoconfig surface (health, info, etc.) needing per-endpoint suppression. Explicit single-bean approach is minimal-surface. (b) Structured log lines now + Micrometer later — rejected: creates migration debt, breaks AC16 integration-test assertions. (c) Place `payload_too_large` counter in controller — rejected: filter short-circuits before controller, counter would never tick.

### Decision 11: WebFlux→JobRunr enqueue bridge — `Mono.fromCallable + boundedElastic` + deterministic UUID job ID
**Decision:** `UUID jobId = UUID.nameUUIDFromBytes(rawUpdateId.getBytes(UTF_8))`; `Mono.fromCallable(() -> BackgroundJob.<ProcessTelegramUpdateJob>enqueue(jobId, j -> j.handle(rawUpdateId))).subscribeOn(Schedulers.boundedElastic())`. Enqueue happens after reactive save (or on DuplicateKey self-heal path per Decision 4); failure propagates as 5xx (Telegram retries).
**Rationale:** Supports user-spec AC1 (P99 <100ms). Deterministic UUID per security audit C1 (critical): JobRunr `enqueue(UUID, lambda)` is idempotent — duplicate `jobId` is a no-op. Combined with Decision 4's "re-enqueue on DuplicateKey", this self-heals the silent-message-loss scenario (save succeeds → enqueue fails → Telegram retries → DuplicateKey path re-enqueues with same UUID → job exists, 200 returned). Worker uses `.block()` on inner reactive helpers — accepted per JobRunr-thread blocking-safe convention (`HardDeleteJob`/`ProjectHardDeleteJob` precedent).
**Alternatives considered:** (a) Random UUID per enqueue — rejected: not idempotent, duplicate-key path can't self-heal. (b) `Mono.fromRunnable(...)` (fire-and-forget) — rejected: enqueue failures swallowed, message lost. (c) Injected `JobScheduler` bean instead of static `BackgroundJob` — viable but static call needs no DI plumbing.

### Decision 12: Cascade ordering in worker — `logEventBlocking(...).block()` BEFORE `processingStatus=DONE`
**Decision:** Use `EventService.logEventBlocking(...)` (NOT fire-and-forget `logEvent`) for all worker event writes. Cascade per scenario: event-write → status-flip. Mirror of `ProjectHardDeleteJob` ordering.
**Rationale:** Supports user-spec AC14 + observability invariant. Guarantees event in `events` before `processingStatus=DONE`. Without this, a JobRunr worker crash mid-flight could leave a DONE raw_update with no audit event.
**Alternatives considered:** Fire-and-forget `logEvent` — rejected: no ordering guarantee.

### Decision 13: Payload cap (1 MB) — scoped `WebFilter` on `/webhooks/telegram/{projectId}`; rejects chunked + missing-Content-Length + >1MB
**Decision:** New `WebhookPayloadSizeFilter implements WebFilter` matching path `/webhooks/telegram/{projectId}` (single segment, NOT `/**`). Rejects with 413 empty body if ANY of: (a) `Transfer-Encoding: chunked` header present, (b) `Content-Length` header missing/unparseable, (c) `Content-Length > 1_048_576`. `@Order(Ordered.HIGHEST_PRECEDENCE + 10)` — runs before security chain. Injects `MeterRegistry` to tick its own `rejected_total{reason=payload_too_large}` counter.
**Rationale:** Supports user-spec AC4. Path-narrowing per security audit M4 (`{projectId}` single segment prevents future sub-path leak like `/webhooks/telegram/v2/...`). Chunked + missing-Content-Length rejection per security C2 (critical): `HttpHeaders.getContentLength()` returns -1 for chunked-encoded or missing-header requests; bare `> 1_048_576` check would pass-through arbitrary-size bodies and exhaust Netty buffers. Telegram always sends `Content-Length` per Bot API (verified via Telegram docs).
**Alternatives considered:** (a) Per-route codec config via `@Bean WebFluxConfigurer` — rejected: more setup, fragile across Spring upgrades. (b) Bumping global codec limit — rejected: violates user-spec line 63 + expands attack surface on `/api/**`. (c) Allow chunked-encoded but stream-count bytes with short-circuit — rejected: more complex, no Telegram client sends chunked.

### Decision 14: Stub services in final packages `subscriber/`, `funnel/` — NOT `_stubs/`
**Decision:** `com.botfunnel.subscriber.SubscriberService` (interface) + `com.botfunnel.subscriber.NoOpSubscriberService` (impl). Same for `funnel/`.
**Rationale:** [TECHNICAL] (also referenced in user-spec line 137). Matches 04c precedent. Avoids package rename + import update in Epic 05/06.
**Alternatives considered:** `com.botfunnel.webhook._stubs.SubscriberService` — rejected: forces package rename, no precedent.

### Decision 15: `sha256Hex` promoted to `common/crypto/Sha256Hex`
**Decision:** Extract `BotService.sha256Hex` (private) to `com.botfunnel.common.crypto.Sha256Hex.hex(String): String` (public static). `BotService.connect` refactored. `WebhookSecretVerifier` calls the same.
**Rationale:** [TECHNICAL]. DRY threshold reached (2 consumers). Lives next to `common/crypto/TokenEncryptor`. Single algorithm declaration prevents subtle divergence.
**Alternatives considered:** (a) Inline duplicate in `WebhookSecretVerifier` — rejected: invites algorithm drift. (b) Promote to `bot/` — rejected: shared utility belongs in `common/`.

### Decision 16: `WebhookSecretVerifier` as `@Component`
**Decision:** `@Component public class WebhookSecretVerifier { public boolean verify(String header, String storedHashHex) {...} }`. Injected into `TelegramWebhookController`.
**Rationale:** [TECHNICAL]. Spring DI consistency. Easier to `@MockitoSpyBean` for invariant assertions.
**Alternatives considered:** Static method on `Sha256Hex` — rejected: verifier holds policy ("constant-time", "no plaintext logging"), not just an algorithm.

### Decision 17: No per-chat ordering in 04b
**Decision:** JobRunr default worker concurrency (~10 threads). Multiple workers may process `/start` and `/stop` from the same chat out of order in the millisecond window. No serialization primitive in 04b.
**Rationale:** [TECHNICAL] (explicitly out of scope per user-spec line 106). Race is rare; cost of per-chat lock is high; stub callees no-op anyway. Epic 05/06 will decide their own serialization.
**Alternatives considered:** (a) Per-chat Redis lock — rejected: deferred to consumers in Epic 05/06. (b) Single-thread queue per chat — rejected: throughput hit unjustified for 04b.

### Decision 18: No polling-mode for local dev
**Decision:** Local testing uses ngrok + Telegram `setWebhook` via existing 04a Connect flow. Production-only HTTPS, no `getUpdates` polling fallback in 04b.
**Rationale:** [TECHNICAL] (per user-spec line 110). Polling code is dead weight; second code path doubles test surface.
**Alternatives considered:** Add `getUpdates` polling for local dev — rejected: dead code in webhook architecture; ngrok flow is documented in staging-smoke runbook.

### Decision 19: Final Wave — `pre-deploy-qa` only (no Deploy, no Post-deploy MCP)
**Decision:** Final Wave contains a single `pre-deploy-qa` task. No Deploy task (CI/CD platform TBD per `deployment.md`). No `post-deploy-qa` task (no MCP tooling for Telegram bot interaction; staging-smoke is operator-manual).
**Rationale:** [TECHNICAL] (Q4 ratified). Staging-smoke runbook (Wave 1 task T8) is the de-facto post-deploy verification; manual execution by operator with throwaway BotFather bot, ngrok, `mongosh`.
**Alternatives considered:** (a) Pretend post-deploy-qa task with MCP placeholders — rejected: dishonest about manual nature. (b) Defer runbook to a post-feature task — rejected: runbook is needed BEFORE feature ships so operator can verify on merge.

## Data Models

### `RawUpdate` (new collection `raw_updates`)

```java
@Document(collection = "raw_updates")
@CompoundIndexes({
    @CompoundIndex(name = "projectId_updateId_unique",
                   def = "{'projectId': 1, 'updateId': 1}",
                   unique = true)
})
public class RawUpdate {
    @Id
    private String id;

    @Indexed
    private String projectId;

    private Long updateId;

    private org.bson.Document payload;     // raw Telegram update JSON, persisted as BSON

    private RawUpdateStatus processingStatus;  // PENDING | DONE | FAILED (stored uppercase via enum.name())

    private String processingError;        // nullable; populated only when status=FAILED; scrubbed + truncated to 1024 chars

    @Indexed(name = "ttl_createdAt",
             expireAfter = "90d",
             partialFilter = "{ 'processingStatus': { $in: ['PENDING', 'DONE'] } }")
    private Instant createdAt;

    // getters / setters
}

public enum RawUpdateStatus { PENDING, DONE, FAILED }
```

### `TelegramUpdate` + nested (DTOs, `webhook/dto/`)

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramUpdate(
        Long update_id,
        Message message,
        Message edited_message,
        Message channel_post,
        Message edited_channel_post,
        JsonNode callback_query,
        JsonNode my_chat_member,
        JsonNode chat_member,
        JsonNode inline_query,
        JsonNode shipping_query,
        JsonNode pre_checkout_query,
        JsonNode poll_answer
) {}

@JsonIgnoreProperties(ignoreUnknown = true)
public record Message(
        Long message_id,
        User from,
        Chat chat,
        Long date,
        String text
) {}

@JsonIgnoreProperties(ignoreUnknown = true)
public record Chat(
        Long id,
        String type,            // "private" | "group" | "supergroup" | "channel"
        String title,
        String username
) {}

@JsonIgnoreProperties(ignoreUnknown = true)
public record User(
        Long id,
        Boolean is_bot,
        String first_name,
        String last_name,
        String username,
        String language_code
) {}
```

Worker resolves `updateKind` by scanning slots: returns first non-null field's JSON name (`"my_chat_member"`, `"callback_query"`, etc.). Unmodeled update types → `"unknown"`.

### Stub service signatures (final, no changes in 04b)

```java
// com.botfunnel.subscriber.SubscriberService
public interface SubscriberService {
    Mono<Void> upsertFromTelegramUpdate(String projectId, Long telegramBotId,
                                        Long chatId, String chatType,
                                        Long telegramUserId, String firstName,
                                        String lastName, String username,
                                        String languageCode);

    Mono<Void> markUnsubscribed(String projectId, Long telegramBotId, Long chatId);
}

// com.botfunnel.funnel.FunnelTriggerService
public interface FunnelTriggerService {
    Mono<Void> fire(String projectId, Long chatId, String triggerType, String payload);

    Mono<Void> cancelActiveFor(String projectId, Long chatId);
}
```

No-op impls return `Mono.empty()`.

### `Bot` (no schema change)

`ownerChatId: Long` already present from Epic 04c. 04b only writes via `findAndModify`. No new index.

## Dependencies

### New packages
- `io.micrometer:micrometer-core` — provides `MeterRegistry` API + `SimpleMeterRegistry` impl. Without `spring-boot-starter-actuator` we register the bean ourselves (Decision 10). Version inherited from Spring Boot BOM (pinned by parent).

### Using existing (from project)
- `jobrunr-spring-boot-3-starter:7.3.2` — static `BackgroundJob.<X>enqueue(UUID, lambda)` from controller (deterministic ID per Decision 11). First one-shot `enqueue` in codebase (existing usage is `@Recurring`).
- `spring-boot-starter-data-mongodb-reactive` — `ReactiveMongoRepository` + `ReactiveMongoTemplate.findAndModify`.
- `spring-boot-starter-webflux` — `WebFilter` API for `WebhookPayloadSizeFilter`.
- `spring-boot-starter-security` (Spring Security 6.5.x) — `NegatedServerWebExchangeMatcher` + `PathPatternParserServerWebExchangeMatcher` for scoped CSRF disable. Cross-check exact 6.5.x API at implementation time via Context7 (R8).

## Testing Strategy

**Feature size: L** — unit + integration + manual staging-smoke runbook.

### Unit tests
- `TelegramCommandParserTest` — `/start`, `/start payload`, `/start ref_a b c`, `/start@SomeBot`, `/start@SomeBot ref_X`, `/stop`, `/stop@SomeBot`, non-command text, `/`-alone, empty, leading whitespace, case-sensitivity (`/Start` vs `/start`), multi-space (`/start  ref_X`), newline payload.
- `WebhookSecretVerifierTest` — match, mismatch, empty header, null stored hash, malformed hex stored hash. Verifies `MessageDigest.isEqual` is the comparison primitive.
- `Sha256HexTest` — parity with `BotService.connect`-side hashing, UTF-8 byte handling, lowercase hex output.
- `TelegramUpdateDeserializationTest` — snake_case mapping, `@JsonIgnoreProperties` swallowing unknowns, missing nested `message` → `null`, `message.from == null` safe-navigable, malformed JSON → Jackson exception (regression guard), empty JSON `{}` → all-null record, `update_id` as String in input → deserialization failure (type-safety regression).
- `MeterRegistryConfigTest` — bean registered, `find("any.metric").counter()` returns null (not NPE).

### Integration tests (Testcontainers Mongo + `JobRunrInMemoryConfig`)

#### `TelegramWebhookControllerIT`
- AC1 latency — `Flux.range(0, 100).parallel(10).flatMap(req → POST webhook)` against Testcontainer Mongo; per-request `System.nanoTime()` collection; assert P99 <100ms. **Note:** if CI flake materializes, mark `@Tag("slow")` and run separately; do NOT downgrade to mean-latency (per user-spec line 60 P99 requirement).
- AC2 — POST with empty/wrong/missing `X-Telegram-Bot-Api-Secret-Token` → 401; `response.expectBody().isEmpty()`; `rejected_total{reason=invalid_secret}` ticked.
- AC3 — 4 cases (missing project / soft-deleted project / DISCONNECTED bot / malformed ObjectId projectId) all → 404 empty body; `rejected_total{reason=project_not_found}` ticked for all 4.
- AC4 — three sub-cases all → 413 empty body, `rejected_total{reason=payload_too_large}` increment verified per case:
  - `Content-Length: 1048577` (>1 MB)
  - `Transfer-Encoding: chunked` header (no Content-Length)
  - No `Content-Length` header at all
- AC5 — `Flux.range(0, 2).parallel(2).flatMap(...)` with same `(projectId, updateId)` → status pair `{200, 200}`, `rawUpdateRepository.count() == 1`, `StorageProvider.getJobs(StateName.ENQUEUED, PageRequest.ascOnUpdatedAt(10)).size() == 1` (note: with deterministic UUID per Decision 11, even if both inserts raced through DuplicateKey self-heal path, enqueue is idempotent). User-spec line 64 explicit parallel size `=2` — keep.
- AC6 (controller side only) — assert ENQUEUED job count after POST is 1; the actual `ownerChatId` atomic write assertion lives in `ProcessTelegramUpdateJobTest` (worker does not execute in IT under `JobRunrInMemoryConfig`).
- Decision 4/11 self-heal — enqueue-failure recovery: spy `BackgroundJob` to throw on first call; second POST (Telegram retry) hits DuplicateKey path + re-enqueue → succeeds; one row, one job, no orphan.
- AC15 — POST `/api/v1/projects/{id}` without `X-XSRF-TOKEN` still returns 403 (CSRF regression — scoped disable did NOT leak).
- AC16 — after N varied requests, assert `meterRegistry.find(name).tag(k,v).counter().count()` exactly matches expected for each of the 5 counter combinations (4 rejection reasons + 1 received + 1 timer + worker outcomes). Use `Counter c = meterRegistry.find(name).tag(k,v).counter(); assertThat(c).isNotNull(); assertThat(c.count()).isEqualTo(N)` (`isNotNull` first guards against NPE if counter not yet created).
- AC18 — token-scrubber per log site via `ListAppender<ILoggingEvent>` filtered by `Logger.getName()` AND `Level` — split per call site (no combined assertion). Mirror `BotControllerIT` token-leak assertion precedent.

#### `ProcessTelegramUpdateJobTest` (direct worker invocation, mirror `HardDeleteJobTest`)
- AC6 — owner `ownerChatId` populate: seed Bot with `ownerChatId=null`, invoke worker with `/start` from private chat → `botRepository.findById(botId).block().getOwnerChatId() == firstChatId`. Then invoke worker again with `/start` from a DIFFERENT chat → `bot.ownerChatId` STILL equals firstChatId (atomic predicate-fail no-op; explicit "not overwritten" assertion per completeness F11 + test M3).
- AC7 — `/start ref_X` private → `events.metadata.startPayload="ref_X"`; spy verifies `subscriber.upsertFromTelegramUpdate` + `funnel.fire("on_start", "ref_X")` called once.
- AC7 — `/start` no-payload → `startPayload=""` (empty string, not `null`).
- AC7 — `/start@BotName ref_X` → suffix stripped, `startPayload="ref_X"`.
- AC8 — `/stop` private → spy: `markUnsubscribed` + `cancelActiveFor` called; event `telegram_command_stop`.
- AC9 — `/start` in group → event written, NO ownerChatId populate, NO Subscriber upsert.
- AC10 — plain text private → `subscriber.upsertFromTelegramUpdate` called + event `telegram_message_received`; plain text in group → event only.
- AC11 — for each modeled non-message type (`callback_query`, `edited_message`, `channel_post`, `edited_channel_post`, `my_chat_member`, `chat_member`, `inline_query`, `shipping_query`, `pre_checkout_query`, `poll_answer`) → event `telegram_update_other` with `updateKind=<jsonFieldName>`, no stub calls.
- AC11 — truly unknown update type (none of the slots populated) → `updateKind="unknown"`.
- AC11a — payload without `message` AND no other modeled slot → safe-navigate, no NPE, `updateKind="unknown"`.
- AC12 — `/foo bar` private → event `telegram_message_received`, no stub calls. Also `/foo bar` in group → event only.
- AC14 — worker exception → `processingStatus=FAILED`, `processingError` non-null + scrubbed + length ≤ 1024, counter `worker_outcome_total{outcome=failure}` ticked, exception rethrown. Integration follow-up: spy `JobRunr StorageProvider` to confirm retry actually fires (verify `getJobs(FAILED)` after exhaustion).
- Re-entry guard — seed raw_update with `processingStatus=DONE`, invoke worker → no event written, no stub call, no counter tick (verifies idempotency under JobRunr retry overlap).

#### `WebhookSecurityBlockTest` (slice-style, mocks per `patterns.md`)
- `@MockitoBean MongoClient/RedisConnectionFactory/ReactiveRedisConnectionFactory` — slice mock pattern.
- POST `/webhooks/telegram/{any}` returns 401 (no auth, hits controller).
- POST `/api/v1/projects` still 401 (auth required).
- CSRF on `/api/**` still active (POST without `X-XSRF-TOKEN` → 403).

#### `RawUpdateRepositoryTest` (integration, classified accordingly)
- Insert two with same `(projectId, updateId)` → second `DuplicateKeyException`.
- `IndexOperations.getIndexInfo()` lists `ttl_createdAt` with `expireAfter` resolved + `partialFilterExpression` containing `['PENDING', 'DONE']` (uppercase — regression guard for completeness F5 fix).
- Insert with `processingStatus=PENDING` + manually-aged `createdAt` (>90d ago) — TTL eventually removes (timing-sensitive: skip from CI, document procedure for manual run).
- Insert with `processingStatus=FAILED` + aged `createdAt` — partial filter excludes; row persists (AC13 invariant).

#### Existing tests — regression guards
- `BotControllerIT` (existing test class — includes Connect-flow tests) and `TelegramSenderIT` must stay green after `Sha256Hex` refactor.

### E2E tests
- `docs/staging-smoke/08-webhook-ingestion.md` — manual 10-15 min checklist created as Wave 1 task. Throwaway BotFather bot, ngrok tunnel, `mongosh` for inspection. Covers: Connect → `setWebhook` → `/start ref_smoke_001` from owner Telegram → assert `raw_updates` row + `events.telegram_command_start` + `bot.ownerChatId` populated → click "Send test message" in admin UI (closes 04c gap) → `/stop` → Disconnect → delete throwaway bot.

## Agent Verification Plan

**Source:** user-spec "Как проверить" section.

### Verification approach
Per-task `Verify-smoke:` fields drive in-development agent checks (gradlew test runs, curl probes, `mongosh` inspection). No MCP tools required — webhook inbound flow involves Telegram's real servers via ngrok, operator-driven and outside agent reach. Post-deploy verification is the manual staging-smoke runbook (task T8).

### Tools required
- `./gradlew test` — full test suite.
- `curl` — HTTP probes for status-code assertions.
- `mongosh` — index existence + `raw_updates` / `events` row inspection.
- No MCP. No Playwright.

## Risks

| Risk | Mitigation |
|------|-----------|
| R1 (user-spec) — <100ms P99 SLA on greenfield WebFlux→JobRunr enqueue | Wrap enqueue in `Mono.fromCallable + boundedElastic` (Decision 11); AC1 integration test asserts P99 via 100-request parallel load against Testcontainer Mongo; if exceeded, fall-back is Caffeine cache for Bot lookup (deferred until measured). |
| R2 (user-spec) — `ownerChatId` race vs fast attacker | Accepted threat. Staging-smoke runbook re-instructs owner to `/start` immediately. UI mitigation deferred. |
| R3 (user-spec) — Spring Security 6.5.x scoped CSRF matcher syntax | Cross-check via Context7 at implementation time. AC15 regression test. |
| R4 (user-spec) — Token leak in webhook logs | `TelegramApiClient.scrubTokens(...)` on every webhook + worker log site. Per-site `ListAppender` tests (AC18). |
| R5 (user-spec) — JobRunr Failed-queue UX hidden | Document `mongosh db.raw_updates.find({processingStatus:'FAILED'})` procedure in staging-smoke runbook. |
| R6 (user-spec) — Soft-delete project cascade gap | Resolved in Decision 1: extra `projectRepository.findById` check covers soft- and hard-deleted cases. |
| R7 (user-spec) — First Micrometer dependency in project | Decision 10 limits scope (no actuator endpoints, explicit bean). AC16 tests consume immediately. |
| R8 (tech-spec) — Spring Security 6.5.x matcher API | Verify constructor / fluent-builder via Context7 during T11. AC15 catches behavioral regression. |
| R9 (tech-spec) — `WebhookPayloadSizeFilter` ordering | `@Order(Ordered.HIGHEST_PRECEDENCE + 10)` runs before security filter chain. Integration test: 1.5 MB payload → 413 even with valid secret. |
| R10 (tech-spec) — `ReactiveMongoTemplate.findAndModify` empty-on-no-match semantics | Spec: returns `Mono.empty()` when predicate matches zero docs (verify via Context7). Worker treats empty as "already populated by another worker" — no-op. Explicit `StepVerifier` for both branches. |
| R11 (tech-spec) — Explicit `MeterRegistry` bean across Spring Boot upgrades | `MeterRegistryConfig` test boots context + asserts bean registered. If future upgrade autoconfigures the bean (e.g., adding actuator), our explicit bean conflicts: catch via `@ConditionalOnMissingBean` on our config. |
| R12 (tech-spec) — Deterministic JobRunr UUID collision | `UUID.nameUUIDFromBytes(rawUpdateId)` — collision-resistant per MD5; `rawUpdateId` is Mongo ObjectId (24 hex chars), uniqueness guaranteed at insert time by `_id`. No risk. |

### Token-scrubber sites (enumerated per security audit M7)

Every log site that emits payload-derived strings:

**`TelegramWebhookController`:**
- WARN on `DuplicateKeyException` (logs `projectId` + `updateId`, NOT payload).
- ERROR on enqueue failure (logs `rawUpdateId`, NOT payload).

**`WebhookPayloadSizeFilter`:**
- WARN on 413 (logs `projectId` + `Content-Length` / encoding header value — scrubber applied to header values).

**`ProcessTelegramUpdateJob`:**
- INFO on worker start (logs `rawUpdateId` + `projectId`, NOT payload).
- WARN inside per-command branches (logs command type + `chatId` + scrubbed payload).
- ERROR on terminal failure (logs scrubbed `ex.getMessage()`).
- INFO on `ownerChatId` populate event.
- INFO on worker success.

Every WARN/ERROR string-formatted with payload-derived input wraps `TelegramApiClient.scrubTokens(...)`. Per-site `ListAppender` test asserts no token regex matches.

## User-Spec Deviations

None.

(Decision 1 explicitly resolves user-spec R6 — the soft-delete cascade open question — by adding the second indexed `Project` lookup. Decision 4 extends the AC5 idempotency mechanism with a self-heal re-enqueue branch to address the silent-message-loss scenario surfaced by security audit; this does not alter the AC5 observable contract — same status codes, same row + job invariants — only adds robustness. Both conform to user-spec; neither requires user re-approval.)

## Acceptance Criteria

Technical acceptance criteria (complement user-spec AC1–AC19):

- [ ] All user-spec AC1–AC19 pass.
- [ ] DB indexes visible via `mongosh botfunnel --eval 'db.raw_updates.getIndexes()'`: `_id_`, `projectId_updateId_unique` (unique), `projectId_1`, `ttl_createdAt` with `expireAfterSeconds=7776000` (Mongo resolves `expireAfter="90d"` to seconds) AND `partialFilterExpression: { processingStatus: { $in: ['PENDING', 'DONE'] } }` (uppercase).
- [ ] No regressions in existing tests: `BotControllerIT`, `TelegramSenderIT`, `ProjectControllerIT`, `SecurityBlockTest` all stay green after `Sha256Hex` refactor.
- [ ] `./gradlew test` — all unit + integration tests pass.
- [ ] gitleaks pre-commit hook passes.
- [ ] `SecurityConfig.pathMatchers("/webhooks/telegram/{projectId}").permitAll()` BEFORE `/api/**.authenticated()`.
- [x] `Sha256Hex.hex(...)` is the sole SHA-256 implementation in `backend/src/main/java/` (verify via grep).
- [ ] Webhook handler returns `Mono<ResponseEntity<Void>>` for 200/401/404 — never via `GlobalErrorHandler`. `WebhookPayloadSizeFilter` writes 413 directly.
- [ ] `payload_too_large` counter exclusively owned by filter (grep: no `payload_too_large` reference in controller).
- [x] `MeterRegistry` bean present at context startup with no actuator on classpath (test asserts via `ApplicationContext.getBean(MeterRegistry.class) != null`).

## Implementation Tasks

### Wave 1 (parallel, no dependencies)

#### Task 1: Add `micrometer-core` + explicit `@Bean SimpleMeterRegistry`
- **Description:** Add `implementation 'io.micrometer:micrometer-core'` to `backend/build.gradle`. Create `MeterRegistryConfig @Configuration` with `@ConditionalOnMissingBean MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }`. Scope per Decision 10.
- **Skill:** code-writing
- **Reviewers:** code-reviewer
- **Verify-smoke:** `./gradlew test --tests *MeterRegistryConfigTest` green; `ApplicationContext.getBean(MeterRegistry.class)` returns non-null at boot.
- **Files to modify:** `backend/build.gradle`
- **Files to create:** `backend/src/main/java/com/botfunnel/common/metrics/MeterRegistryConfig.java`, `backend/src/test/java/com/botfunnel/common/metrics/MeterRegistryConfigTest.java`
- **Files to read:** `backend/build.gradle`

#### Task 2: `Sha256Hex` utility + `WebhookSecretVerifier` component
- **Description:** Extract `BotService.sha256Hex` (private) to `com.botfunnel.common.crypto.Sha256Hex.hex(String): String` public static. Refactor `BotService.connect` to call it; delete the private method. Create `WebhookSecretVerifier @Component` per Decisions 2, 15, 16.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew test --tests *Sha256HexTest --tests *WebhookSecretVerifierTest --tests *BotControllerIT` green.
- **Files to modify:** `backend/src/main/java/com/botfunnel/bot/BotService.java`
- **Files to create:** `backend/src/main/java/com/botfunnel/common/crypto/Sha256Hex.java`, `backend/src/main/java/com/botfunnel/webhook/WebhookSecretVerifier.java`, `backend/src/test/java/com/botfunnel/common/crypto/Sha256HexTest.java`, `backend/src/test/java/com/botfunnel/webhook/WebhookSecretVerifierTest.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/bot/BotService.java`, `backend/src/main/java/com/botfunnel/common/crypto/TokenEncryptor.java`

#### Task 3: `TelegramCommandParser` pure utility
- **Description:** Create `com.botfunnel.webhook.TelegramCommandParser` with static `parse(String): ParsedCommand`. Strips `@botname` suffix. Pure function, no Spring context.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-smoke:** `./gradlew test --tests *TelegramCommandParserTest` green.
- **Files to create:** `backend/src/main/java/com/botfunnel/webhook/TelegramCommandParser.java`, `ParsedCommand.java`, `backend/src/test/java/com/botfunnel/webhook/TelegramCommandParserTest.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/bot/dto/TelegramSendParameters.java`

#### Task 4: `TelegramUpdate` + nested records
- **Description:** Create snake_case Java records in `com.botfunnel.webhook.dto` per Decision 6 (includes `JsonNode` stub slots for non-message types). `@JsonIgnoreProperties(ignoreUnknown=true)` on all. Tests cover representative deserialization + edge cases (malformed, empty, type-mismatch).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew test --tests *TelegramUpdateDeserializationTest` green.
- **Files to create:** `backend/src/main/java/com/botfunnel/webhook/dto/TelegramUpdate.java`, `Message.java`, `Chat.java`, `User.java`, `backend/src/test/java/com/botfunnel/webhook/dto/TelegramUpdateDeserializationTest.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/bot/dto/TelegramResult.java`, `TelegramUser.java`, `TelegramSendResult.java`

#### Task 5: `RawUpdate` entity + repository + status enum
- **Description:** Create `RawUpdate` `@Document("raw_updates")`, `RawUpdateStatus` enum (`PENDING|DONE|FAILED`), `RawUpdateRepository`. Compound unique index + TTL per Decisions 4 and 8. Repository test verifies index metadata.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew test --tests *RawUpdateRepositoryTest` green; `mongosh` confirms 3 expected indexes with correct partial filter.
- **Files to create:** `backend/src/main/java/com/botfunnel/webhook/RawUpdate.java`, `RawUpdateStatus.java`, `RawUpdateRepository.java`, `backend/src/test/java/com/botfunnel/webhook/RawUpdateRepositoryTest.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/bot/Bot.java`

#### Task 6: `SubscriberService` stub in `subscriber/`
- **Description:** Create interface + `NoOpSubscriberService @Service` impl per Data Models signatures. Returns `Mono.empty()`. Final package per Decision 14.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-smoke:** `./gradlew test --tests *NoOpSubscriberServiceTest` green.
- **Files to create:** `backend/src/main/java/com/botfunnel/subscriber/SubscriberService.java`, `NoOpSubscriberService.java`, `backend/src/test/java/com/botfunnel/subscriber/NoOpSubscriberServiceTest.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/bot/BotService.java`

#### Task 7: `FunnelTriggerService` stub in `funnel/`
- **Description:** Create interface + `NoOpFunnelTriggerService @Service` impl per Data Models signatures. Returns `Mono.empty()`. Final package per Decision 14.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-smoke:** `./gradlew test --tests *NoOpFunnelTriggerServiceTest` green.
- **Files to create:** `backend/src/main/java/com/botfunnel/funnel/FunnelTriggerService.java`, `NoOpFunnelTriggerService.java`, `backend/src/test/java/com/botfunnel/funnel/NoOpFunnelTriggerServiceTest.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/bot/BotService.java`

#### Task 8: Staging-smoke runbook `docs/staging-smoke/08-webhook-ingestion.md`
- **Description:** Create 10-15 min manual checklist per user-spec "Пользователь проверяет" section. Throwaway BotFather bot, ngrok, `/start ref_smoke_001`, `mongosh` inspection, "Send test message" click (closes 04c gap), `/stop`, cleanup. Include JobRunr Failed-queue inspection per Decision 9.
- **Skill:** documentation-writing
- **Reviewers:** code-reviewer
- **Verify-user:** Operator reads runbook end-to-end, steps reproducible without out-of-band knowledge.
- **Files to create:** `docs/staging-smoke/08-webhook-ingestion.md`
- **Files to read:** `docs/staging-smoke/06-bot-connection.md`, `docs/staging-smoke/07-telegram-sender.md`, `work/08-webhook-ingestion/user-spec.md`

### Wave 2 (depends on T2, T3, T4, T5, T6, T7)

#### Task 9: `ProcessTelegramUpdateJob` worker
- **Description:** Create JobRunr worker that loads `RawUpdate`, applies re-entry guard (Decision 9), branches per AC matrix, performs atomic `ownerChatId` populate (Decision 5), calls stubs, writes events via `logEventBlocking` (Decision 12), updates `processingStatus` atomically, scrubs and truncates `processingError` on failure. All log sites wrapped via `TelegramApiClient.scrubTokens`. Direct-invocation tests cover AC6–AC12, AC14, re-entry idempotency.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew test --tests *ProcessTelegramUpdateJobTest` green.
- **Files to create:** `backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java`, `backend/src/test/java/com/botfunnel/webhook/ProcessTelegramUpdateJobTest.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/jobs/ProjectHardDeleteJob.java`, `backend/src/main/java/com/botfunnel/jobs/HardDeleteJob.java`, `backend/src/test/java/com/botfunnel/jobs/HardDeleteJobTest.java`, `backend/src/main/java/com/botfunnel/events/EventService.java`, `backend/src/main/java/com/botfunnel/bot/Bot.java`, `backend/src/main/java/com/botfunnel/bot/BotRepository.java`, `backend/src/main/java/com/botfunnel/bot/TelegramApiClient.java`

### Wave 3 (depends on T1, T2, T4, T5, T9)

#### Task 10: `TelegramWebhookController` + idempotent enqueue + 4 counters
- **Description:** Create controller per Decisions 1, 3, 4, 11. Bot lookup + project soft/hard-delete check + secret verify + idempotent save + idempotent enqueue (deterministic UUID; re-enqueue on `DuplicateKeyException` self-heal path). Inject `MeterRegistry` for 3 controller-owned counters (received, duration, rejected{invalid_secret|project_not_found|duplicate}). Integration test covers AC1–AC3, AC5, AC15, AC16, AC18 + enqueue-failure recovery test (Decision 11 self-heal).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew test --tests *TelegramWebhookControllerIT` green.
- **Files to create:** `backend/src/main/java/com/botfunnel/webhook/TelegramWebhookController.java`, `backend/src/test/java/com/botfunnel/webhook/TelegramWebhookControllerIT.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/bot/BotController.java`, `backend/src/main/java/com/botfunnel/bot/BotRepository.java`, `backend/src/main/java/com/botfunnel/project/ProjectRepository.java`, `backend/src/main/java/com/botfunnel/jobs/JobRunrMongoConfig.java`, `backend/src/test/java/com/botfunnel/AbstractIntegrationTest.java`, `backend/src/test/java/com/botfunnel/bot/BotControllerIT.java`

### Wave 4 (depends on T10)

#### Task 11: `WebhookPayloadSizeFilter` + `SecurityConfig` updates
- **Description:** Create `WebhookPayloadSizeFilter` per Decision 13 (path `/webhooks/telegram/{projectId}`, rejects chunked + missing Content-Length + >1MB, owns `rejected{payload_too_large}` counter). Modify `SecurityConfig` per Decisions 3, 13 + Architecture (`permitAll` for `/webhooks/telegram/{projectId}` BEFORE `/api/**.authenticated()`; scoped CSRF disable via negated `PathPatternParserServerWebExchangeMatcher`). Slice-style `WebhookSecurityBlockTest` + filter integration test (AC4 all three sub-cases + AC15 CSRF regression).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew test --tests *WebhookPayloadSizeFilterTest --tests *WebhookSecurityBlockTest` green; manual `curl -X POST -H "Transfer-Encoding: chunked" http://localhost:8080/webhooks/telegram/507f1f77bcf86cd799439011 -d '{}'` returns 413; `curl -X POST http://localhost:8080/api/v1/projects` returns 401 (CSRF baseline still active).
- **Files to create:** `backend/src/main/java/com/botfunnel/webhook/WebhookPayloadSizeFilter.java`, `backend/src/test/java/com/botfunnel/webhook/WebhookPayloadSizeFilterTest.java`, `backend/src/test/java/com/botfunnel/webhook/WebhookSecurityBlockTest.java`
- **Files to modify:** `backend/src/main/java/com/botfunnel/security/SecurityConfig.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/security/SecurityConfig.java`, `backend/src/test/java/com/botfunnel/SecurityBlockTest.java`

### Audit Wave (parallel — `reviewers: none`)

#### Task 12: Code Audit
- **Description:** Holistic code-quality audit across all 04b source files. Check cross-component issues: duplicate resource initialization, shared `MeterRegistry` usage consistency across controller + filter + worker, naming conventions, error-handling shape uniformity, Decisions section compliance. Write audit report.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 13: Security Audit
- **Description:** Full-feature security audit. Read all 04b source files + `SecurityConfig` diff. OWASP Top 10 focus per security-auditor skill. Verify: scoped CSRF disable correctness, no plaintext logging, scrubber per site, no chunked-encoding bypass, deterministic JobRunr ID idempotency, `processingError` PII/token leak avoidance. Write audit report.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 14: Test Audit
- **Description:** Full-feature test-quality audit. Verify AC1–AC19 mapping (each AC has a test that would fail if the AC were removed from code), meaningful assertions, `ListAppender` per-site filtering, `@MockitoSpyBean` usage, race-test correctness, no flaky `Thread.sleep`, re-entry guard test present. Write audit report.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 15: Pre-deploy QA
- **Description:** Acceptance testing. Run `./gradlew test`. Verify every user-spec AC1–AC19 + tech-spec Acceptance Criteria mechanically pass. Inspect `mongosh` indexes. Confirm grep invariants (single SHA-256 implementation, no `payload_too_large` reference in controller). Report failures to lead before deploy. No deploy task per Decision 19.
- **Skill:** pre-deploy-qa
- **Reviewers:** none
