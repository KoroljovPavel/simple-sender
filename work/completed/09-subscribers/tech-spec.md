---
created: 2026-05-24
status: approved
branch: dev
size: L
---

# Tech Spec: 09-subscribers (Epic 05 — Subscribers CRM)

## Solution

Replace `NoOpSubscriberService` with full `SubscriberServiceImpl` and ship the real CRM module that has been stubbed since Epic 04b. Implementation spans seven backend areas (Subscriber + Tag + SubscriberEvent + CustomFieldDefinition + SubscriberExport entities, status state-machine, Redis rate-limit, TelegramSender 403/400 hook, async CSV export through JobRunr→GridFS→email, ProjectHardDeleteJob cascade extension) and four new frontend pages (`/subscribers` list, `/subscribers/{id}` profile, `/tags`, `/custom-fields`). The export pipeline lives in the same tech-spec but is decomposed into a separate wave (Wave 3) so it can be reviewed in isolation. All endpoints mount under `/api/v1/projects/{projectId}/...` and pass through `ProjectService.requireOwned` (existing platform guard). No new Maven dependencies — GridFS comes with `spring-boot-starter-data-mongodb`, signed-URL uses JDK `javax.crypto.Mac`, rate-limits reuse the existing `RedisTemplate` brute-force pattern from `BotService`. Four new env vars (`SUBSCRIBER_EXPORT_TOKEN_KEY`, `SUBSCRIBER_EXPORT_RETENTION_DAYS`, `SUBSCRIBER_RATE_LIMIT_START_PER_MIN`, `SUBSCRIBER_DOWNLOAD_RATE_LIMIT_PER_MIN`).

## Architecture

### What we're building/modifying

**Backend — new modules**
- `com.botfunnel.subscriber` (replace stub) — `SubscriberServiceImpl @Service` (replaces `NoOpSubscriberService`), `Subscriber` entity, `SubscriberRepository`, `SubscriberStatus` enum (`active|unsubscribed|blocked|deleted`), `SubscriberController` (list+profile+history+unsubscribe+send-message), `SubscriberEvent` entity + repo (separate `subscriber_events` collection, TTL 365d), `SegmentFilter` immutable record.
- `com.botfunnel.subscriber.export` — `SubscriberExport` entity + repo, `SubscriberExportController` (POST/GET list/GET download), `ExportSubscribersJob` (JobRunr one-shot), `ExportCleanupJob` (JobRunr daily recurring), `SubscriberCsvWriter` (streaming serializer).
- `com.botfunnel.tag` (new sibling module) — `Tag` entity, `TagRepository`, `TagService`, `TagController` with denormalized `subscriberCount` counter.
- `com.botfunnel.common.crypto.SignedDownloadToken` — HMAC-SHA256 mint/verify primitive (env `SUBSCRIBER_EXPORT_TOKEN_KEY`).

**Backend — modified**
- `com.botfunnel.project.Project` — add embedded `customFieldDefinitions: List<CustomFieldDefinition>` field (max 20).
- `com.botfunnel.project` — new `CustomFieldDefinition` record + `CustomFieldType` enum + `CustomFieldsController` (`/api/v1/projects/{projectId}/custom-fields`).
- `com.botfunnel.bot.TelegramSender` — append `SubscriberService` constructor dependency; in `sendText` catch, detect 403 (blocked-by-user) / 400 chat-not-found via typed `TelegramSendException.terminalReason` and call `markBlockedByChatId` / `markDeletedByChatId` after emitting `telegram_send_failed`.
- `com.botfunnel.jobs.ProjectHardDeleteJob` — extend cascade order: GridFS files → `subscriber_exports` → `subscriber_events` → `subscribers` → `tags` → existing project drop.
- `com.botfunnel.email.EmailService` — add public `sendExportReadyEmail`, `sendExportFailedEmail` methods inside the existing class (Ukrainian subjects); they reuse the package-private `loadTemplate`/`htmlEscape` helpers and the private `sendAsync` swallow-and-log dispatcher. No helper visibility changes required.
- `com.botfunnel.webhook.ProcessTelegramUpdateJob` — no code changes; the real `SubscriberServiceImpl @Service` replaces the no-op bean by simple `@Service` swap.

**Frontend — new pages**
- `frontend/pages/projects/[projectId]/subscribers/index.vue` — list with text search, status filter, tag include/exclude, date range, cursor-based "Load more", row click → profile, "Export CSV" trigger + Recent exports dialog.
- `frontend/pages/projects/[projectId]/subscribers/[subscriberId].vue` — identity card + Tabs (Tags/CustomFields/History), action buttons (Manual unsubscribe, Send personal message), inline editors.
- `frontend/pages/projects/[projectId]/tags/index.vue` — Tag list with counter + create/rename(label)/delete dialogs.
- `frontend/pages/projects/[projectId]/custom-fields/index.vue` — CustomFieldDefinition list + Add/Edit(label,defaultValue only)/Delete dialogs.

**Frontend — modified**
- `frontend/components/Sidebar.vue` (or layout default) — add sub-nav under "Subscribers": Subscribers / Tags / Custom Fields.
- `frontend/i18n/locales/{uk,en}.json` — new top-level namespaces `subscribers`, `tags`, `customFields`, `exports`.
- `frontend/composables/useApi.ts` — no changes (cursor pagination is a page concern).
- shadcn-vue scaffolding: `table`, `select`, `badge`, `input`, `sonner`, `combobox`, `tabs`, `card`, `sheet`, `tooltip`, `checkbox` (currently only `dialog` exists).

### How it works

**Auto-registration data flow** (Scenario 1 in user-spec): Telegram → `POST /webhooks/telegram/{projectId}` (Epic 04b unchanged) → `WebhookSecretVerifier` → save `RawUpdate` + enqueue `ProcessTelegramUpdateJob` → worker calls `subscriberService.upsertFromTelegramUpdate(...)` (same interface, real impl now). Real impl: (1) `subscriberRepository.findByProjectIdAndTelegramUserId(projectId, telegramUserId)` — if exists, skip rate-limit and update only `lastSeenAt` + identity refresh; if not, (2) Redis `INCR bf:rate:start:{projectId}` with TTL 60s — if count > 100 write event `rate_limit_exceeded` and return silently; (3) insert new `Subscriber{status=ACTIVE, subscribedAt=now, lastSeenAt=now, tags=[], customFields={}}` + write event `subscriber_registered`. Reactivation path: existing subscriber with `status != ACTIVE` → flip to `ACTIVE`, preserve `subscribedAt`, refresh `lastSeenAt` + identity, write `subscriber_reactivated`. Race against unique `(projectId, telegramUserId)` index → `DuplicateKeyException` is caught, the existing subscriber re-read, and the update applied (idempotent upsert pattern — fresh in this epic, not present in `BotService.connect` which maps DuplicateKey to a hard 409).

**Status state-machine writers**: `/stop` (worker) → `markUnsubscribed(projectId, telegramBotId, chatId)` lookup by `(projectId, telegramBotId, telegramChatId)`, flip to `UNSUBSCRIBED` + `unsubscribedAt=now` + event `subscriber_unsubscribed{reason:"command_stop"}`. Manual unsubscribe (POST) → same flow with `reason:"manual"`. `TelegramSender` catches Telegram 403 → `markBlockedByChatId(...)` (status=BLOCKED + blockedAt + event `subscriber_blocked`); catches 400 with description "chat not found" → `markDeletedByChatId(...)` (status=DELETED + deletedAt + event `subscriber_deleted`). Reactivation on `/start`: any non-ACTIVE → ACTIVE, tags + custom fields preserved.

**List endpoint**: `GET /api/v1/projects/{projectId}/subscribers` with `search` (≥2 chars, text-index match), `status` (single), `tags_include[]` (AND), `tags_exclude[]` (NOT), `subscribed_from`/`subscribed_to`, `sort=created_desc|last_seen_desc`, `cursor` (opaque base64 of `{lastSortVal, lastId}`), `limit` (default 50, max 200). Response `{items: SubscriberResponse[], nextCursor: string|null}`. Mongo text-index on (firstName, lastName, username) — language `none` for UA+EN+emoji parity (no stemming). Tag filters use `tags: {$all: [...], $nin: [...]}`.

**CSV export pipeline**: POST `/api/v1/projects/{projectId}/subscribers/export {filter}` → `requireOwned` → estimated `count(filter)` (P95<200ms via indexed-only filter; if text-search present, drop text criterion for the estimate per AC18 fallback) → > 200000 returns 422 `export_filter_too_large` → else insert `SubscriberExport{status=PENDING, filter, ownerId, createdAt}` → partial-unique compound index `(projectId)` with `partialFilter {status: {$in: [PENDING, RUNNING]}}` → DuplicateKeyException → 409 `export_in_flight` → else `jobScheduler.enqueue(deterministicUuid(exportId), j -> j.handle(exportId))` → return 202 `{exportId, status: "PENDING"}`. Job: flip RUNNING → `mongoTemplate.stream(query, Subscriber.class)` cursor → `PipedOutputStream` → `SubscriberCsvWriter` (UTF-8 BOM, columns `subscriber_id, telegram_user_id, telegram_chat_id, first_name, last_name, username, language_code, status, subscribed_at, unsubscribed_at, blocked_at, deleted_at, last_seen_at, tags, custom_fields`; dates ISO-8601 UTC; tags `;`-joined slugs; custom_fields as JSON-string) → `GridFsOperations.store(in, fileName, "text/csv", Map.of("exportId", id, "projectId", projectId))` → update `status=DONE, fileId, rowCount, completedAt` → `emailService.sendExportReadyEmail(ownerEmail, name, signedDownloadUrl, expiresAt)` → event `subscribers_export_completed`. Job timeout 10min — JobRunr default retry policy on transient failure; terminal failure flips `status=FAILED` + event `subscribers_export_failed` + `sendExportFailedEmail`. Download: `GET .../exports/{exportId}/download?token=<HMAC>` → `SignedDownloadToken.verify(token)` (constant-time; payload contains projectId+exportId+expiresAt) → 401/410 on tamper/expiry → Redis bucket `bf:download:export:{projectId}` (30/min, TTL 60s) → 429 → `requireOwned` → load export → DONE → stream `GridFsResource` (Content-Disposition attachment) + event `subscribers_export_downloaded` → PURGED→410 `export_purged` / FAILED→410 `export_failed`. Cleanup: `ExportCleanupJob @Recurring(cron="0 3 * * *")` finds exports `createdAt < now-7d AND status=DONE`, deletes GridFS file then flips to `PURGED` + event `subscribers_export_purged`.

**Project soft-delete cascade**: existing flow unchanged. On hard delete, `ProjectHardDeleteJob` cascade order (extends current `events` → `project_hard_deleted` event → drop project): **GridFS files** (`{metadata.projectId: in deletedIds}`) → `subscriber_exports` → `subscriber_events` → `subscribers` → `tags` → existing event emission → drop project. Each step uses `mongoTemplate.remove(query, collection)`; idempotent — partial-cascade survivors are mopped up on next daily run (JobRunr retry).

### Shared resources

| Resource | Owner (creates) | Consumers | Instance count |
|----------|----------------|-----------|----------------|
| `GridFsOperations` (Spring Boot auto-config) | `MongoDataAutoConfiguration` (Spring Boot) | `ExportSubscribersJob`, `ExportCleanupJob`, download endpoint in `SubscriberExportController`, `ProjectHardDeleteJob` (cascade purge) | 1 (singleton bean) |
| `RedisTemplate<String, String>` (auto-config) | `spring-boot-starter-data-redis` | `SubscriberServiceImpl` (start rate-limit), `SubscriberExportController` download endpoint (download rate-limit) | 1 (singleton bean) — already in use by `AuthService`, `BotService` |
| `SignedDownloadToken @Component` | new `common/crypto/SignedDownloadToken.java` | `SubscriberExportController` (mint on export-list endpoint, verify on download) + `EmailService.sendExportReadyEmail` (URL contains minted token) | 1 (singleton); env-var `SUBSCRIBER_EXPORT_TOKEN_KEY` fail-fast at startup (mirror `TokenEncryptor`) |
| `JobScheduler` (JobRunr) + `StorageProvider` | `JobRunrMongoConfig` (prod), `JobRunrInMemoryConfig` (test) | `SubscriberExportController` (enqueue export), `ExportSubscribersJob` (handler), `ExportCleanupJob` (recurring) | 1 each — already in use by Epic 04b; JobRunr default worker pool (no dedicated label — accept R9 trade-off) |
| `SubscriberService` (interface, existing) | new `SubscriberServiceImpl @Service` (replaces `NoOpSubscriberService`) | `ProcessTelegramUpdateJob` (worker, existing), `SubscriberController` (manual unsubscribe + send-message), `TagController` (subscriberCount updates via service callback), `TelegramSender` (markBlocked/markDeleted) | 1 (singleton); single `@Service` after no-op deletion — no `@Primary` needed |
| `MongoTemplate` (auto-config) | Spring Data | All new repositories + `SubscriberServiceImpl` (text-search query, cursor pagination, atomic `findAndModify` for `subscriberCount` denormalized counter), `ProjectHardDeleteJob` cascade extension | 1 (singleton) — already in use |

## Decisions

### Decision 1: Delete NoOpSubscriberService (no `@Primary` swap)
**Decision:** Delete `backend/src/main/java/com/botfunnel/subscriber/NoOpSubscriberService.java` and its test `NoOpSubscriberServiceTest.java`. Register `SubscriberServiceImpl` as the sole `@Service` implementing `SubscriberService`. Tests that previously relied on the no-op (slice tests) get `@MockitoBean SubscriberService`.
**Rationale:** Two `@Service` beans implementing the same interface fail Spring DI (R7). The no-op test asserts a no-op contract that no longer holds. Doc comment at `NoOpSubscriberService.java:6` already telegraphs "Epic 05 will replace this `@Service` directly".
**Alternatives considered:** Keep no-op + add `@Primary` on real impl — rejected: leaves dead code, confuses future contributors, no slice test currently needs no-op behavior. Supports user-spec AC24 (`grep -r "NoOpSubscriberService" backend/src/main` → empty).

### Decision 2: `export_in_flight` enforced via Mongo partial-unique compound index (not Redis lock)
**Decision:** `SubscriberExport` carries `@CompoundIndex(name="exports_in_flight_unique", def="{'projectId': 1}", unique=true, partialFilter="{ 'status': { $in: ['PENDING', 'RUNNING'] } }")`. Persist `ExportStatus` enums via `.name()` (UPPERCASE) — add a class-load defensive assert mirroring `RawUpdate.java:31-40`. Concurrent POST → `DuplicateKeyException` → mapped to 409 `export_in_flight` via the `BotService.mapPersistError` precedent.
**Rationale:** Code-research §16.2 confirmed `$in` inside `partialFilter` is already proven in this codebase (`RawUpdate.java:54-57`). DB-level guarantee removes the need for an out-of-band Redis lock; one less moving part; survives Redis outage. Supports user-spec AC17.
**Alternatives considered:** Redis lock `bf:export:lock:{projectId}` (R2 fallback) — rejected: introduces second source of truth, fail-open semantics would degrade to "any number of in-flight exports" during Redis outage, violating the AC. Two separate single-status partial-unique indexes — rejected: more index maintenance, identical effect.

### Decision 3: 20-cap CustomFieldDefinition race — atomic conditional push
**Decision:** Insert via `mongoTemplate.update(Query.query(Criteria.where("_id").is(projectId).and("customFieldDefinitions.20").exists(false)), new Update().push("customFieldDefinitions", def), Project.class)`. If `modifiedCount == 0` → re-load project → if `size() >= 20` throw `unprocessableEntity("custom_field_limit_reached")`, else throw `conflict("custom_field_name_taken")` (re-check name uniqueness after re-load).
**Rationale:** Single atomic operation closes the read-then-write race (R4) without compensating writes. `array.20` existence is the Mongo idiom for "size > 20" guard. Cheaper than `$expr {$lt: [{$size: ...}, 20]}` pipeline-style update and works with `auto-index-creation` on existing path.
**Alternatives considered:** Post-write count check + cleanup last-inserted (compensate) — rejected: cleanup window leaks transient state visible to readers (subscribers profile UI could briefly see 21 definitions). Application-layer mutex via Redis — rejected: lock per-project doesn't scale across instances and Redis-outage fail-open re-introduces the race.

### Decision 4: TelegramSender hook via typed `terminalReason` on existing `TelegramSendException`
**Decision:** Extend `TelegramSendException` with `enum TerminalReason { BLOCKED_BY_USER, CHAT_NOT_FOUND, OTHER }`. In `TelegramSender.toThrowable`: on `403` map to `BLOCKED_BY_USER`; on `400` AND description contains `"chat not found"` map to `CHAT_NOT_FOUND`; default `OTHER`. In the existing `catch (RuntimeException ex)` block at `TelegramSender.java:131-139`: AFTER `eventService.logEvent(... EVENT_TELEGRAM_SEND_FAILED ...)` (preserve audit semantics), check `TelegramSendException.terminalReason` and call `subscriberService.markBlockedByChatId(bot.getProjectId(), botId, chatId)` or `markDeletedByChatId(...)`. The `Bot` already loaded at `TelegramSender.java:122` — promote to method-scope local; resolve `projectId` from it (no extra DB call). Append `SubscriberService` as last parameter of BOTH prod and test constructors (test ctor delegates to prod via `this(...)`).
**Rationale:** Direct injection beats `ApplicationEventPublisher` (zero precedent in codebase, R6: single-use pattern would create maintenance noise) and beats typed-exception-bubble-to-callers (R6: every future caller must remember to react, silent drift risk). Typed `TerminalReason` enum is more explicit than substring sniffing inside `SubscriberService` and keeps `TelegramSender`'s mapping logic colocated with the rest of `toThrowable`. Supports user-spec AC5.
**Alternatives considered:** `ApplicationEventPublisher` listener — rejected per R6. Throw new sentinel exception types `BlockedByUserException` / `ChatNotFoundException` — rejected: forces every caller (broadcast Epic 07, funnel Epic 06) to catch/rethrow; existing `TelegramSendException` already mapped by `GlobalErrorHandler`. Pass projectId from caller — rejected: changes `sendText` signature project-wide (BotService, broadcast workers); resolving from already-loaded `Bot` is zero-cost.

### Decision 5: Cursor-based pagination (opaque base64 of `{sortVal, lastId}`)
**Decision:** Encode cursor as base64url-encoded JSON `{"v": <sortFieldEpochMilli|literal>, "id": <subscriber._id hex>}`. **Strict server-side decode rules (closes security F6):** (1) decode base64url → parse JSON; (2) reject if JSON is not an object, or has keys other than exactly `{v, id}`; (3) reject if any value contains `$`-prefixed sub-key (NoSQL operator injection guard) at any depth; (4) reject if `v` is not a primitive (`long` for created/last-seen sorts, never an object/array); (5) reject if `id` is not 24-char lowercase hex. Any rejection → HTTP 400 with `code: "invalid_cursor"`. Server then applies `Criteria` of `(sortField < v) OR (sortField == v AND _id < lastId)` to break ties deterministically. Response carries `nextCursor: string | null`. Limit default 50, max 200; `count`-for-total NOT computed (heavy under text-search).
**Rationale:** Numbered pages don't scale on 1000+ rows + text search (count is `O(N)` against text index per query); user-spec explicitly chose Load-More UX. Tie-breaker on `_id` prevents skipped rows when many subscribers share the same `lastSeenAt` (broadcast burst, funnel re-fire). Opaque cursor lets the contract evolve without leaking sort details to clients.
**Alternatives considered:** Skip/limit numbered pagination — rejected per user-spec UX choice. Cursor = last `_id` only — rejected: requires fixed-sort = `_id` only; user-spec needs `created_desc` AND `last_seen_desc`. Encrypted cursor — rejected: opacity is enough; no PII inside.

### Decision 6: Mongo text-index `language="none"` on (firstName, lastName, username)
**Decision:** `@TextIndexed(weight=3)` on `firstName` + `lastName`, `weight=5` on `username` (primary handle). At doc level: `@Document(collection="subscribers", language="none")`. Repository query: `TextCriteria.forDefaultLanguage().matchingAny(input.split("\\s+"))` with `Sort.by("score").descending()`. Search input min 2 chars enforced at controller layer.
**Rationale:** Mongo allows only one text index per collection; collapsing 3 fields with weights gives relevance-ranked match in one query. `language="none"` skips stemming — works identically for Ukrainian, English, and emoji-in-usernames (no false-positive matches via cyrillic/latin stemmer divergence). Confirmed via Context7 `/spring-projects/spring-data-mongodb`.
**Alternatives considered:** `language="russian"` — rejected per user clarification (gives incorrect stemming on Ukrainian). Atlas Search / Elasticsearch — deferred per user-spec (>1M subscribers). Substring `$regex` — rejected: no index acceleration, P95 fails at 1k rows.

### Decision 7: CSV streaming via `MongoTemplate.stream` + `PipedOutputStream` → GridFS
**Decision:** Use `mongoTemplate.stream(query, Subscriber.class)` (returns `CloseableIterator<Subscriber>`) → write rows to `PipedOutputStream` → connected `PipedInputStream` passed to `gridFsOperations.store(in, fileName, "text/csv", metadata)`. The writer thread is the JobRunr worker (virtual thread — blocking on the pipe is cheap). UTF-8 BOM `0xEF 0xBB 0xBF` prepended once; rows escaped per RFC 4180 (`"` → `""`, wrap field in quotes if it contains `,` `"` `\n` `\r`). **Formula-injection neutralization (mandatory):** before RFC 4180 escaping, any cell whose first character is one of `= + - @ \t \r` is prefixed with a single apostrophe (`'`) to defuse spreadsheet formula execution (Excel, Sheets, LibreOffice, Numbers). Applied to ALL string cells: firstName, lastName, username, languageCode, status, tags-joined, custom_fields JSON-string, and numeric/date cells stringified (since `Double.isFinite` accepts `=1+1` as a String upstream). `customFields` serialized via Jackson `ObjectMapper.writeValueAsString` (already-injected bean) then formula-neutralized as a whole-string cell.
**Rationale:** Zero in-memory list materialization — heap stays bounded at single-row size regardless of dataset (R1). Mongo cursor + Pipe is the JDK-native zero-dep streaming bridge; no Reactor scheduler needed under virtual threads. `@Tag("slow")` integration test asserts 50k seeded rows → file written → heap stays under threshold. Formula-injection neutralization is mandatory per A03 (CSV injection): Telegram-supplied identity fields (firstName, lastName, username) and NUMBER-type custom fields (validated as `Double.isFinite`, so `=1+1` literally passes) are unbounded by the slug regex; without prefix, an owner opening the CSV in Excel executes arbitrary subscriber-supplied formulas. Supports user-spec AC16 + closes security finding F1.
**Alternatives considered:** `Files.write` to tmpfile → `GridFsOperations.store(InputStream)` — rejected: double-write (disk + GridFS), needs tmpfile cleanup. Build `List<Subscriber>` then serialize — rejected: OOM at 100k+ subscribers (R1). Spring Batch ItemReader/Writer — rejected: extra dependency for one job. Wrap risky cells in double-quotes only — rejected: Excel still parses formula prefix inside quotes.

### Decision 8: Extend `ProjectHardDeleteJob` in place (not separate cascade job)
**Decision:** Extend `backend/src/main/java/com/botfunnel/jobs/ProjectHardDeleteJob.java` to cascade-delete in order: (1) `events` (existing) → (2) GridFS files `{metadata.projectId: {$in: deletedIds}}` → (3) `subscriber_exports` → (4) `subscriber_events` → (5) `subscribers` → (6) `tags` → (7) emit `project_hard_deleted` (existing) → (8) drop project documents (existing). Single structured INFO log per run reports per-collection removedCount.
**Rationale:** Splitting into a sibling `SubscriberCascadeJob` would force operators to correlate two cron logs to verify "project N fully cleaned" — observability regression. Cascade is daily, batched by `deletedIds`, bounded by retention window. Order matters: GridFS files first prevents orphan-file leaks (R11). Each step is idempotent — JobRunr retry on partial-cascade-failure picks up next 24h. Supports user-spec AC22.
**Alternatives considered:** Separate `SubscriberCascadeJob` — rejected per observability. Cascade inside `SubscriberServiceImpl` driven by soft-delete event — rejected: requires event listener infra (no precedent), tight coupling between project module and subscriber module reversed.

### Decision 9: Redis rate-limits mirror `BotService` INCR-every-attempt fail-open shape
**Decision:** Three buckets. (1) New-`/start` bucket: key `"bf:rate:start:" + projectId`, threshold 100/min (env `SUBSCRIBER_RATE_LIMIT_START_PER_MIN`). (2) Download bucket: key `"bf:download:export:" + projectId`, threshold 30/min (env `SUBSCRIBER_DOWNLOAD_RATE_LIMIT_PER_MIN`). (3) **Personal-message bucket (new — F5):** key `"bf:rate:personal_message:" + projectId`, threshold 60/min (env `SUBSCRIBER_PERSONAL_MESSAGE_RATE_LIMIT_PER_MIN`, default 60). All three: TTL `Duration.ofMinutes(1)`, INCR → EXPIRE only on first-bucket (count==1L) → threshold check `> threshold`. Fail-open on any Redis transport exception → WARN log with greppable constant (`SUBSCRIBER_START_RATE_REDIS_FAIL_OPEN`, `EXPORT_DOWNLOAD_RATE_REDIS_FAIL_OPEN`, `PERSONAL_MESSAGE_RATE_REDIS_FAIL_OPEN`). Existing-subscriber `/start` lookup BEFORE INCR — re-/start by known subscriber is not rate-limited. Personal-message and download buckets DO NOT bypass — every call counts.
**Rationale:** Identical shape to `BotService.incrementBruteForceCounter` — verified pattern, established WARN-on-fail observability. Existing-subscriber bypass on `/start` prevents false-positive rate-trip on legitimate users hitting `/start` again after a long absence. Personal-message bucket added to defend against compromised-session flood (security F5): asymmetric with the 30/min download bucket; threshold 60/min is generous for legitimate per-subscriber outreach yet caps Telegram-flood + subscriber_events bloat from a single owner. Supports user-spec AC6, AC15, AC19; R12 (accepted Redis-outage exposure documented).
**Alternatives considered:** Token-bucket via Redis script (Lua EVAL) — rejected: no precedent in codebase, INCR+EXPIRE is sufficient for minute-window granularity. Fail-closed — rejected: codebase convention is fail-open (AuthService precedent), R12. Personal-message limit per-subscriber instead of per-project — rejected: cardinality blow-up (one Redis key per subscriber), per-project is sufficient for flood defense.

### Decision 10: SubscriberEvent collection is the SOLE writer for CRM lifecycle events
**Decision:** New collection `subscriber_events` (`@Document(collection="subscriber_events")`), TTL via `@Indexed(name="ttl_createdAt", expireAfter="365d")` on `createdAt`, plus compound non-unique `@CompoundIndex(name="subscriber_recent", def="{'subscriberId': 1, 'createdAt': -1}")` for the profile history feed. CRM-only event types: `subscriber_registered`, `subscriber_reactivated`, `subscriber_unsubscribed`, `subscriber_blocked`, `subscriber_deleted`, `subscriber_tag_added`, `subscriber_tag_removed`, `subscriber_custom_field_set`, `personal_message_sent`, `personal_message_failed`. **Writer ownership:** all events written by `SubscriberServiceImpl` except `personal_message_sent` (written by `SubscriberController.sendPersonalMessage` on TelegramSender 2xx return) and `personal_message_failed` (written by the same controller in the catch block when TelegramSender throws `TelegramSendException` with `terminalReason=OTHER` — i.e. the failure was NOT mapped to `markBlocked` / `markDeleted`, which write their own events). Existing `events` collection continues to receive webhook (`telegram_command_*`), bot lifecycle, sender (`telegram_message_sent`, `telegram_send_failed`), security, and project events — NO duplicate writes from subscriber lifecycle. Dual-write invariant enforced by integration test (`SubscriberEventsIsolationIT`) that asserts after a `subscriber_registered` write, the `events` collection has only the corresponding webhook event (`telegram_command_start`), NOT a duplicate `subscriber_registered` row.
**Rationale:** Different retention (365d vs unlimited), different access patterns (subscriber-profile-scoped vs platform-wide audit), different scope. Avoids overloading `events` collection with high-volume per-subscriber data. Consistency-failure story (AC23): if `subscriber_events` write fails, corresponding sender/webhook event in `events` collection has overlap metadata (chatId + timestamp) for reconstruction. Explicit writer ownership for personal-message events removes ambiguity flagged by completeness review. Supports user-spec AC23.
**Alternatives considered:** Single `events` collection with `scope` field — rejected: TTL conflict (need 365d for subscriber but unlimited for audit); index growth for the platform-wide collection. Outbox pattern with periodic flush — rejected: synchronous write is simpler, no consumer in this epic.

### Decision 11: Slug / name validation via `@Pattern` (no new custom annotation)
**Decision:** `Tag.slug` and `CustomFieldDefinition.name` validated by `@Pattern(regexp = "^[a-z0-9_-]{1,32}$", message = "{validation.slug.pattern}")` on the DTO field directly. Same regex literal across both modules; no shared `ValidSlug` annotation (premature abstraction for two call sites). Slug is **immutable after create** for both entities — `PATCH .../tags/{slug}` body schema excludes `slug`; attempt to mutate via custom code path (e.g. body with `slug` field) returns 422 `tag_slug_immutable` / `custom_field_immutable`.
**Rationale:** Bean Validation supplies the regex check at controller boundary — zero custom code. Two call sites is below the abstraction threshold per `patterns.md` precedent ("Custom annotations live under `<module>/validation/` … For Epic 05 propose: `ValidCustomFieldName`, `ValidTagName` (or just `@Pattern`)" — choosing the simpler option). Immutability enforced server-side regardless of client requests. Supports user-spec AC8, AC10.
**Alternatives considered:** Custom `@ValidSlug` annotation — rejected: two call sites, no DRY benefit. Mutable slug with rename cascade — rejected per user-spec ("CustomFieldDefinition rename slug — accepted limitation").

### Decision 12: Frontend cursor-pagination via Pinia store + array-append
**Decision:** New Pinia setup-store `frontend/stores/subscribers.ts` mirrors `stores/projects.ts` pattern. State: `items: Subscriber[]`, `nextCursor: string | null`, `loading: boolean`, `filter: SegmentFilter`. Actions: `loadFirstPage(filter)` clears + fetches first page; `loadMore()` appends if `nextCursor != null && !loading`. Page mounts → `loadFirstPage(default)`. Filter change → `loadFirstPage(newFilter)`. Click "Load more" → `loadMore()`. No localStorage persistence (per `patterns.md:165` opaque-ID-only rule).
**Rationale:** Mirrors existing precedent; setup-store keeps the slice transparent. Array-append is what user expects ("Load more" UX); replacing the array would force scroll-position recovery (out of scope). No new state-management dependency.
**Alternatives considered:** `@tanstack/vue-query` `useInfiniteQuery` — already present; rejected for now to keep store pattern consistent with `projects.ts`. Direct fetch inside component — rejected: shared state for "Export CSV" trigger button needs the filter visible across components.

### Decision 13: Email language Ukrainian (matches existing precedent)
**Decision:** Export emails in Ukrainian. Subject "Експорт підписників готовий" (ready), "Експорт підписників не вдався" (failed). Templates `subscribers-export-ready.html`, `subscribers-export-failed.html` under `backend/src/main/resources/templates/email/`. Body placeholders: `{NAME}`, `{URL}` (signed download URL), `{EXPIRES_AT}` (ISO-8601), `{APP_URL}`. Reuse `EmailService.htmlEscape` for all substitutions.
**Rationale:** Existing transactional emails (`verify-email.html`, `reset-password.html`, `account-blocked.html`) are all Ukrainian with hardcoded subjects (`EmailService.java:35,41,47`). i18n is not available in email-job context (cookie not accessible from JobRunr worker thread). Single-language ships now; per-user locale field is a future epic. Supports user-spec "Email language — Ukrainian default" decision.
**Alternatives considered:** English — rejected: inconsistent with existing user-touching emails. Bilingual subject — rejected: subject-line aesthetics, user-spec already chose UA.

### Decision 14: shadcn-vue scaffolding done in Wave 1 (frontend foundation)
**Decision:** Run `pnpm dlx shadcn-vue@latest add <component>` for: `table`, `select`, `badge`, `input`, `sonner`, `combobox`, `tabs`, `card`, `sheet`, `tooltip`, `checkbox`. Generated files commit under `frontend/components/ui/<component>/`. Done up-front in Wave 1 (frontend foundation task) so Waves 4 pages can compose without serialization delay. `lucide-vue-next` already present for icons.
**Rationale:** Scaffolding is idempotent + low-risk; isolating it in a dedicated task means page-level reviewers don't need to re-validate generated boilerplate. Single PR makes generated diffs easy to skim.
**Alternatives considered:** Scaffold each component lazily inside page tasks — rejected: serial dependency, every page reviewer reads the same generated files, churn.

### Decision 15: Signed-URL expiry semantics + key rotation strategy
**Decision:** Token expiry = **24 hours** from mint time (env `SUBSCRIBER_EXPORT_URL_TTL_HOURS`, default 24; lowered from initial-draft 7d per security F2 — URL leaks via reverse-proxy logs/Referer should have minimum exposure window). GridFS retention remains 7 days (Decision 8, AC20). `SubscriberExport.expiresAt = createdAt + URL_TTL_HOURS` written at DONE time and immutable. Email contains one signed URL minted at job completion (`emailService.sendExportReadyEmail`). UI "Recent exports" list shows DONE rows with **two-state button**: if `expiresAt > now` → "Download" links to existing token; else → "Refresh URL" → POST `/api/v1/projects/{projectId}/subscribers/exports/{exportId}/refresh-url`. **Refresh-URL endpoint accepted status matrix:** status DONE → 200 `{downloadUrl, expiresAt}`; status PENDING/RUNNING → 409 `export_in_flight`; status PURGED → 410 `export_purged`; status FAILED → 410 `export_failed`. Endpoint runs `requireOwned` first, then Redis rate-limit `bf:refresh:export:{projectId}` 5/hour with **fail-CLOSED on Redis outage** (token mint is a sensitive privilege; bypassing the bucket during outage would defeat the 24h TTL hardening from F2) — Redis-down ⇒ 503 `service_unavailable` with WARN log + greppable constant `EXPORT_REFRESH_REDIS_FAIL_CLOSED`. **Key rotation:** `SUBSCRIBER_EXPORT_TOKEN_KEY` rotated by operator emergency action (e.g. suspected key leak) via deploy → on rotation, ALL existing signed URLs become invalid (verify returns 401 `invalid_token`) — by design, this IS the response to compromise. No `kid` (key-id) prefix in token payload — single-key design accepted; multi-key rotation deferred until rotation cadence exceeds quarterly. Rotation runbook entry added to `docs/staging-smoke/09-subscribers.md`: "If key rotation required, plan downtime window where pending email links break; communicate to affected owners; UI 'Refresh URL' will re-mint with new key."
**Rationale:** 24h exposure window vs 7d cuts the leak surface 7×. UI refresh button gives owners a friction-free recovery path inside the 7-day GridFS retention. Single-key design keeps `SignedDownloadToken` simple — multi-key (kid) infrastructure costs are not justified for an MVP epic; rotation = invalidate is acceptable for an emergency-only operation. Closes security F2 (link leak window) + F3 (rotation strategy documented).
**Alternatives considered:** Keep 7d expiry — rejected per F2. 1h expiry with auto-refresh in UI — rejected: requires SSE/polling infrastructure not in scope; 24h matches most owners' "click email when convenient" workflow. Implement `kid` for multi-key parallel rotation — deferred: zero rotation events expected during MVP year; complexity not warranted.

### Decision 16: Audit events on download non-200 paths (security F4)
**Decision:** Every non-200 outcome on `GET /subscribers/exports/{exportId}/download` writes an event `subscribers_export_download_denied` to the platform `events` collection (NOT `subscriber_events`). Metadata `{reason, projectId, exportId?, tokenSegmentPrefix?}`. `ip` and `userAgent` go through the standard `EventService.logEvent(userId, eventType, ipAddress, userAgent, metadata)` parameters, which already write into the platform `events` collection — GDPR basis Art. 6(1)(f) legitimate interest (recon-attack detection); the `events` collection has no TTL by design (unbounded audit), but is acceptable here because access is operator-only and not joined to subscriber identity (downloads are authenticated by HMAC token, NOT by subscriber identity). `reason` enum: `invalid_token` (401), `expired` (410), `rate_limited` (429), `project_unavailable` (410), `purged` (410), `failed` (410), `foreign_owner` (404). `tokenSegmentPrefix` = first 8 chars of payload segment (NOT signature) for cross-reference without leaking the full token. Audit writes happen BEFORE returning the HTTP response to ensure observability survives downstream failures.
**Rationale:** Without audit, recon attacks (token enumeration, brute-forcing expired URLs, cross-project token replay) are invisible to operators. The download endpoint is the most-attacked public surface in this epic — it's unauthenticated by session (HMAC only) and serves PII. Routing to platform `events` (not `subscriber_events`) keeps GDPR exposure bounded: denied-download events have no subscriber-identity join, so the 365d `subscriber_events` TTL is not the right home for IP-tagged audit. Supports R8 detection + security F4.
**Alternatives considered:** Write to `subscriber_events` with 365d TTL — rejected per GDPR finding R2-2 (IP+UA + 365d retention without subscriber identity is disproportionate). Log to file only — rejected: `deployment.md` commits to structured event log via `EventService`, file log won't be queryable. Sample 1-in-N events — rejected: download volume is low; full audit is cheap.

### Decision 17: Tag delete cascade synchronous (no async JobRunr job)
**Decision:** `DELETE /api/v1/projects/{projectId}/tags/{slug}` runs cascade synchronously within request: `mongoTemplate.update(Query.query(...).addCriteria(Criteria.where("tags").is(slug)), new Update().pull("tags", slug), Subscriber.class)` (bulk modify), then `tagRepository.delete(tag)`, then write event `tag_deleted`. Returns 204 on completion. For very-large projects (>10k subscribers), the bulk `$pull` is a single Mongo operation — sub-second on indexed `tags` field. JobRunr default retry policy is NOT involved (sync request).
**Rationale:** User-spec AC8 says "sync on any size, JobRunr retry handles partial failures" — but the cascade is itself one atomic Mongo `update many`, so partial failure surface is small. Async via JobRunr adds 202-then-poll UX complexity that the use case doesn't justify (admin actions are rare and the request is owner-initiated). Mid-operation crash before `tagRepository.delete` leaves tag-with-removed-references — still consistent (subscribers have no orphan refs); next call to delete is idempotent.
**Alternatives considered:** Async JobRunr job + 202 — rejected: introduces "Recent tag deletions" UX surface that user-spec doesn't ask for. Soft-delete tag — rejected: would orphan subscriber `tags[]` entries pointing to invisible tags.

## Data Models

### Subscriber

`backend/src/main/java/com/botfunnel/subscriber/Subscriber.java`

```java
@Document(collection = "subscribers", language = "none")
@CompoundIndexes({
    @CompoundIndex(name = "project_telegramUser_unique",
                   def = "{'projectId': 1, 'telegramUserId': 1}", unique = true),
    @CompoundIndex(name = "project_lastSeen_desc",
                   def = "{'projectId': 1, 'lastSeenAt': -1}"),
    @CompoundIndex(name = "project_subscribedAt_desc",
                   def = "{'projectId': 1, 'subscribedAt': -1}"),
    @CompoundIndex(name = "project_status",
                   def = "{'projectId': 1, 'status': 1}"),
    @CompoundIndex(name = "project_tags",
                   def = "{'projectId': 1, 'tags': 1}")
})
public class Subscriber {
    @Id String id;
    String projectId;
    Long telegramUserId;
    Long telegramChatId;
    Long telegramBotId;
    @TextIndexed(weight = 3) String firstName;
    @TextIndexed(weight = 3) String lastName;
    @TextIndexed(weight = 5) String username;
    String languageCode;
    SubscriberStatus status;          // ACTIVE | UNSUBSCRIBED | BLOCKED | DELETED
    List<String> tags;                // tag slugs (denormalized for indexed filter)
    Map<String, Object> customFields; // key = CustomFieldDefinition.name; per-type validated
    Instant subscribedAt;             // immutable after create
    Instant unsubscribedAt;
    Instant blockedAt;
    Instant deletedAt;
    Instant lastSeenAt;
    @TextScore Float score;           // populated when search query returns matches
}
```

### Tag

`backend/src/main/java/com/botfunnel/tag/Tag.java`

```java
@Document(collection = "tags")
@CompoundIndexes({
    @CompoundIndex(name = "project_slug_unique",
                   def = "{'projectId': 1, 'slug': 1}", unique = true)
})
public class Tag {
    @Id String id;
    String projectId;
    String slug;            // immutable; matches ^[a-z0-9_-]{1,32}$
    String label;           // editable display name
    long subscriberCount;   // denormalized; updated via $inc on add/remove
    Instant createdAt;
}
```

### SubscriberEvent

`backend/src/main/java/com/botfunnel/subscriber/SubscriberEvent.java`

```java
@Document(collection = "subscriber_events")
@CompoundIndexes({
    @CompoundIndex(name = "subscriber_recent",
                   def = "{'subscriberId': 1, 'createdAt': -1}")
})
public class SubscriberEvent {
    @Id String id;
    String subscriberId;
    String projectId;
    String eventType;        // see Decision 10 enumeration
    Map<String, Object> metadata;
    @Indexed(name = "ttl_createdAt", expireAfter = "365d")
    Instant createdAt;
}
```

### CustomFieldDefinition (embedded in Project)

`backend/src/main/java/com/botfunnel/project/CustomFieldDefinition.java`

```java
public record CustomFieldDefinition(
    String name,             // immutable slug ^[a-z0-9_-]{1,32}$
    String label,            // editable display name
    CustomFieldType type,    // STRING | NUMBER | BOOLEAN | DATE
    Object defaultValue,     // editable; null allowed; validated per-type at create + update
    Instant createdAt
) {}
```

Embedded in `Project.customFieldDefinitions: List<CustomFieldDefinition>` (max 20 enforced by Decision 3). `defaultValue` is validated against `type` at create + update via the same `CustomFieldValueValidator` used for subscriber custom-field PATCH (Task 5) — a `STRING` definition rejects numeric `defaultValue`, a `DATE` definition rejects free-form text, etc. — error code `custom_field_type_mismatch` (422). Closes security F9.

### SubscriberExport

`backend/src/main/java/com/botfunnel/subscriber/export/SubscriberExport.java`

```java
@Document(collection = "subscriber_exports")
@CompoundIndexes({
    @CompoundIndex(name = "exports_in_flight_unique",
                   def = "{'projectId': 1}", unique = true,
                   partialFilter = "{ 'status': { $in: ['PENDING', 'RUNNING'] } }"),
    @CompoundIndex(name = "project_createdAt_desc",
                   def = "{'projectId': 1, 'createdAt': -1}")
})
public class SubscriberExport {
    @Id String id;
    String projectId;
    String ownerId;
    ExportStatus status;     // PENDING | RUNNING | DONE | FAILED | PURGED
    Document filter;          // serialized SegmentFilter
    String fileId;            // GridFS file id, populated on DONE
    Long rowCount;            // populated on DONE
    String errorMessage;      // populated on FAILED; scrubbed via TelegramApiClient.scrubTokens then truncated to 1024 chars (mirror ProcessTelegramUpdateJob FAILED branch per patterns.md:216)
    Instant createdAt;
    Instant completedAt;
    Instant expiresAt;        // populated on DONE = completedAt + URL_TTL_HOURS (Decision 15); used by download endpoint AC19 + UI "Refresh URL" button
}
```

Class-level static block defensively asserts enum order matches the persisted-name set used in the partial-filter literal (`{PENDING, RUNNING, DONE, FAILED, PURGED}`) — mirror `RawUpdate.java:27-37` and `Bot.java:31-40` precedent.

### REST contracts (request/response records)

DTOs under `subscriber/dto/`, `tag/dto/`, `project/dto/`. All `@JsonIgnoreProperties(ignoreUnknown = true)` per `patterns.md:161`. Bean Validation annotations as listed below.

```java
// Subscriber list + profile
record SubscriberResponse(String id, Long telegramUserId, Long telegramChatId,
        Long telegramBotId, String firstName, String lastName, String username,
        String languageCode, String status, List<String> tags,
        Map<String, Object> customFields, Instant subscribedAt,
        Instant unsubscribedAt, Instant blockedAt, Instant deletedAt,
        Instant lastSeenAt) {}
record PageResponse<T>(List<T> items, String nextCursor) {}

// List query params (controller binds via @ModelAttribute)
record SubscriberListQuery(
        @Size(min=2, max=120) String search,            // text-search input cap (security F7)
        String status,                                   // single SubscriberStatus name
        List<@Pattern(regexp="^[a-z0-9_-]{1,32}$") String> tagsInclude,
        List<@Pattern(regexp="^[a-z0-9_-]{1,32}$") String> tagsExclude,
        Instant subscribedFrom, Instant subscribedTo,
        String sort,                                     // "created_desc" | "last_seen_desc"
        @Size(max=256) String cursor,                   // opaque base64; server decodes + type-validates (security F6)
        @Positive @Max(200) Integer limit               // default 50
) {}

// Manual unsubscribe / send personal message
record SendMessageRequest(@NotBlank @Size(min=1, max=4096) String text) {}
record SendMessageResponse(String status, String message) {}  // status: sent|blocked|deleted

// Tag CRUD
record CreateTagRequest(@Pattern(regexp="^[a-z0-9_-]{1,32}$") String slug,
        @Size(max=64) String label) {}
record UpdateTagRequest(@Size(max=64) String label) {}
record TagResponse(String slug, String label, long subscriberCount, Instant createdAt) {}

// Subscriber tag assign / unassign
record TagAssignRequest(@Pattern(regexp="^[a-z0-9_-]{1,32}$") String slug) {}

// Custom field schema CRUD
record CreateCustomFieldRequest(
        @Pattern(regexp="^[a-z0-9_-]{1,32}$") String name,
        @NotBlank @Size(max=64) String label,
        @NotNull CustomFieldType type,
        Object defaultValue) {}
record UpdateCustomFieldRequest(@Size(max=64) String label, Object defaultValue) {}
record CustomFieldResponse(String name, String label, CustomFieldType type,
        Object defaultValue, Instant createdAt) {}

// Custom field value
record SetCustomFieldsRequest(Map<String, Object> values) {}

// Export
record CreateExportRequest(SegmentFilter filter) {}
record ExportResponse(String exportId, String status, Long rowCount,
        Instant createdAt, Instant completedAt,
        String downloadUrl /* null if not DONE */, Instant expiresAt) {}

// Subscriber events / history feed
record SubscriberEventResponse(String id, String eventType,
        Map<String, Object> metadata, Instant createdAt) {}
```

### Error codes (mapped via `GlobalErrorHandler`)

`tag_name_taken` (409), `custom_field_limit_reached` (422), `custom_field_name_taken` (409), `custom_field_immutable` (422), `custom_field_type_mismatch` (422), `tag_slug_immutable` (422), `already_unsubscribed` (409), `export_in_flight` (409), `export_filter_too_large` (422), `export_expired` (410), `export_purged` (410), `export_failed` (410), `export_project_unavailable` (410), `invalid_token` (401), `telegram_rate_limited` (503), `personal_message_rate_limited` (429), `invalid_bot_token` (422 — existing).

## Dependencies

### New packages
None. GridFS comes with `spring-boot-starter-data-mongodb`; HMAC uses JDK `javax.crypto.Mac`; CSV writer is hand-rolled per Decision 7.

### Using existing (from project)
- `spring-boot-starter-data-mongodb` — collections, indexes, GridFS (`GridFsOperations` auto-config).
- `spring-boot-starter-data-redis` — rate-limit buckets (`RedisTemplate`).
- `spring-boot-starter-validation` — `@Pattern`, `@NotBlank`, `@Size`, `@NotNull`.
- `org.jobrunr:jobrunr-spring-boot-3-starter` — `ExportSubscribersJob` (one-shot enqueue), `ExportCleanupJob` (`@Recurring`), `ProjectHardDeleteJob` (extended).
- `spring-boot-starter-mail` — `EmailService.sendAsync` (existing); new `sendExportReadyEmail` / `sendExportFailedEmail`.
- `io.micrometer:micrometer-core` — `MeterRegistry` for counters (optional; defer to Epic 09 analytics unless needed for AC18 latency probe).
- `common.crypto.SignedDownloadToken` — new; HMAC-SHA256 via JDK `javax.crypto.Mac`.
- `@vee-validate/zod` + `vee-validate` (frontend, existing) — form schemas for Add tag, Add custom field, Send message, Export filter.
- `@pinia/nuxt` (frontend, existing) — new store `subscribers.ts`.
- shadcn-vue — scaffold 11 new components (Decision 14).

## Testing Strategy

**Feature size:** L

### Unit tests
- `SubscriberStatusMachineTest` — transitions: `active↔unsubscribed`, `*→blocked`, `*→deleted`, reactivation `(unsubscribed|blocked|deleted) → active on /start`. Idempotency: `markUnsubscribed` on already-unsubscribed → no-op (no event written, no field updates). Time: assert `subscribedAt` preserved across reactivation, `lastSeenAt` updated on every transition.
- `SegmentFilterBuilderTest` — `{status, tags_include[], tags_exclude[], dateRange, search}` → `Query` doc; edge cases: empty filter, only one criterion, all criteria, text-search combined with tags filter.
- `SubscriberCsvWriterTest` — UTF-8 BOM emission, RFC 4180 escape (`;`, `"`, `\n`, `\r`, `,`), column order stable per Decision 7, tags `;`-joined slugs, custom_fields JSON-string serialization, ISO-8601 UTC dates with `Z` suffix. **Formula-injection cases (Decision 7):** seed subscriber with `firstName="=SUM(A1:A99)"`, `username="@evil"`, `lastName="-cmd|notepad"`, `custom_fields={"city": "+1+1", "note": "\tsneaky"}` → asserts every prefixed cell starts with apostrophe `'` after CSV serialization; control case (`firstName="Iva"`) emitted without prefix.
- `CustomFieldValueValidatorTest` — `STRING` trim + max 1024 chars; `NUMBER` parses `"3.14"`, `"-0"`, `"1e10"`, rejects `Infinity`, `NaN`, `"abc"`; `BOOLEAN` accepts `true|false|"yes"|"no"` case-insensitive; `DATE` accepts ISO-8601 (`OffsetDateTime` parseable), rejects "tomorrow".
- `TelegramTextValidatorTest` — `1..4096` chars, reject blank, reject null.
- `TagSlugValidatorTest` — `@Pattern ^[a-z0-9_-]{1,32}$` accepts `vip`, `course_buyer`, `paid-2024`; rejects `VIP`, `vi p`, `vip.2024`, 33+ chars.
- `SignedDownloadTokenTest` — `mint(...)` produces parseable token; `verify(...)` returns `Verified` on valid; throws on tampered payload, wrong signature, expired (`expiresAt < now`); `MessageDigest.isEqual` constant-time compare exercised; cross-project token substitution (sign with project A's data, verify against project B's) → rejected via payload `projectId` mismatch.
- `SubscriberExportFilterEstimateTest` — `estimatedCount(filter)` for indexed-only filter returns under 200ms on seeded 100k (under `@Tag("slow")`).
- `TelegramSendExceptionTerminalReasonTest` — `toThrowable(403)` → `BLOCKED_BY_USER`; `toThrowable(400, "Bad Request: chat not found")` → `CHAT_NOT_FOUND`; `toThrowable(400, "Bad Request: text too long")` → `OTHER`.

### Integration tests

All extend `AbstractIntegrationTest` (real Mongo + Redis + Mailpit per `patterns.md`).

- `SubscriberServiceImplIT` — invoke `processTelegramUpdateJob.handle(rawUpdateId)` directly (mirror `ProcessTelegramUpdateJobTest` fixtures). New `/start` → `Subscriber` persisted with `status=ACTIVE`, identity fields populated, event `subscriber_registered` in `subscriber_events`. Re-`/start` from same `telegramUserId` (`status=ACTIVE`) → only `lastSeenAt` updated. Reactivation: pre-seeded subscriber with `status=BLOCKED` → `/start` → status flipped to ACTIVE, `subscribedAt` unchanged, `tags`+`customFields` preserved, event `subscriber_reactivated`.
- `SubscriberRateLimitIT` — 101 parallel `/start` from 101 distinct `telegramUserId` (via `ConcurrencyTestUtils.parallelInvoke` per `patterns.md:181`) → asserts 100 subscribers persisted + at least 1 event `rate_limit_exceeded` written. Existing-subscriber re-`/start` 200× → no rate-limit event (lookup-before-incr behavior). **Redis fail-open** (`redisFailsOpen_proceeds_andLogsWarn`): stop the Redis container mid-test (`Testcontainers` start/stop API) → `/start` proceeds, new subscriber persisted, captured WARN log contains `SUBSCRIBER_START_RATE_REDIS_FAIL_OPEN` constant → restart Redis → counters reset (acceptable per R12). **Cross-project isolation** (`rateLimit_isolatedPerProject`): 100 `/start`s on project A do NOT trigger rate-limit on project B (parallel 100 + 100 from distinct `telegramUserId` sets → all 200 subscribers persisted; regression-catch for any future drop of `{projectId}` suffix from the Redis key).
- `SubscriberControllerIT` — `GET /subscribers` happy paths: empty result, full page (50 items + nextCursor present), final page (nextCursor=null), text search ≥2 chars matches firstName/lastName/username, status filter, `tags_include` AND semantics, `tags_exclude` NOT semantics, dateRange. Cursor pagination forward 3 pages, no duplicates, no skips. **Cursor tie-break** (`cursor_paginates_across_identical_sortValue`): seed 60 subscribers all with `lastSeenAt=T` → assert page 1 (50) + page 2 (10) cover all 60 with no skips/dupes (Decision 5). **Cursor type validation** (`cursor_invalidShape_returns400`): pass `cursor=base64({\"v\": {\"$ne\": null}, ...})` (NoSQL injection attempt) → 400 + zero leaked rows (closes security F6). Access guards: foreign owner → 404, soft-deleted project → 404, malformed `projectId` → 404.
- `SubscriberProfileIT` — `GET /subscribers/{id}` returns identity + tags + customFields + status + dates; `GET /subscribers/{id}/events?limit=50` returns last 50 events sorted `createdAt desc`.
- `SubscriberPersonalMessageIT` — `POST /subscribers/{id}/messages {text}` happy path → 200 + event `personal_message_sent` (written by controller per Decision 10). `MockWebServer` 403 response → `markBlocked` called → status=BLOCKED + event `subscriber_blocked` (CRM, by service) + event `telegram_send_failed` (audit, by sender) + response body `{status: "blocked", message: "..."}` — NO `personal_message_failed` event (controller skips when terminalReason ≠ OTHER). 400 chat-not-found → markDeleted → status=DELETED + body `{status: "deleted", ...}`. **400 OTHER reason** (e.g. "Bad Request: text too long"): asserts `personal_message_failed` event written by controller. 429 → existing retry-with-backoff exhausted → 503 `telegram_rate_limited`. **Personal-message rate-limit** (`rate_limited_returns429_andNoSenderCalled`): 61st POST within 60s → 429 `personal_message_rate_limited`, MockWebServer received only 60 outbound calls — closes security F5.
- `SubscriberManualUnsubscribeIT` — `POST /subscribers/{id}/unsubscribe` happy path; idempotent retry on already-unsubscribed → 409 `already_unsubscribed`; reactivation: unsubscribed subscriber sends `/start` → status flipped via worker → asserts state machine.
- `TagControllerIT` — CRUD: create + duplicate 409 + slug regex 400 + denormalized counter inc/dec on add/remove via subscriber endpoints + label-only edit (slug mutation rejected 422 `tag_slug_immutable`) + sync delete cascade on subscribers (≥10 seeded subscribers with tag → 0 after delete). **Access guards** (`requireOwned` parity per AC21): `GET/POST/DELETE` against foreign-owned project → 404; against soft-deleted project → 404; malformed `projectId` → 404. **Concurrent counter race** (`addSameTag_concurrentlyFrom10Subscribers_counterEqualsExactly10`): `parallelInvoke(10, subscriberId -> POST /subscribers/{subscriberId}/tags {"slug":"vip"})` → asserts `Tag.subscriberCount == 10` exactly (atomic `$inc` correctness).
- `CustomFieldDefinitionIT` — schema CRUD + 20-cap 422 `custom_field_limit_reached` (atomic conditional push, asserted via 21st POST sequential) + name conflict 409 + label edit + delete cascade removes values (≥10 seeded subscribers — per AC12 user-spec threshold) + slug/type immutable 422 `custom_field_immutable`. **`defaultValue` per-type validation** (`create_stringTypeWithNumericDefault_returns422`): POST `{name:"city", type:STRING, defaultValue: 42}` → 422 `custom_field_type_mismatch`; same for STRING vs DATE, NUMBER vs object, etc. — closes security F9. **Concurrent 20-cap race** (`parallel21Posts_acceptsOnly20`): `parallelInvoke(21, i -> POST /custom-fields {name:"f"+i, ...})` → asserts persisted `customFieldDefinitions.size() == 20` exactly, 1 of the 21 returns 422 (Decision 3 atomic conditional push under load).
- `CustomFieldValueValidationIT` — `PATCH /subscribers/{id}/custom-fields {values}` per-type validation 422 `custom_field_type_mismatch`. **AC11 mass-assignment explicit** (`patch_unknownKeys_silentlyDropped_noError_noPersist`): PATCH `{"city": "Kyiv", "evil_unknown_key": "x", "another_unknown": 42}` against a schema that only defines `city` → 200 OK, `Subscriber.customFields` contains exactly `{"city": "Kyiv"}` (no `evil_unknown_key` / `another_unknown`); no exception thrown.
- `SubscriberExportIT` — `POST /subscribers/export {filter}` → 202 + `exportId`; autowired `ExportSubscribersJob.handle(exportId)` directly invoked → asserts `status=DONE`, GridFS file present (`gridFsOperations.findOne({metadata.exportId: id})`), GridFS metadata also contains `projectId` + `rowCount` (closes security F10 integrity probe), `rowCount` populated, `expiresAt` populated `≈ completedAt + 24h` (Decision 15), email enqueued (Mailpit captures `sendExportReadyEmail` with HTML body containing signed URL + expiry), event `subscribers_export_completed`. **FAILED path** (`exportJob_internalError_writesFailedAndEmail`): inject a `MongoTemplate` proxy that throws on `stream()` mid-export → asserts `status=FAILED`, `errorMessage` scrubbed via `TelegramApiClient.scrubTokens` + truncated to 1024 chars (closes F8), email `sendExportFailedEmail` enqueued, event `subscribers_export_failed`. **Empty filter / zero rows** (`emptyFilter_zeroSubscribers_producesValidHeaderOnly`): seed zero subscribers → DONE, GridFS file contains BOM + header row + zero data rows, `rowCount=0`.
- `SubscriberExportConcurrencyIT` — `parallelInvoke(2, () -> POST /export)` → status pair `{202, 409}`, strict invariants: exactly 1 PENDING row in `subscriber_exports`, exactly 1 JobRunr-enqueued job for the winning exportId (via `storageProvider.countJobs(StateName.ENQUEUED) == 1`).
- `SubscriberExportSignedUrlIT` — happy download streams CSV with correct Content-Type/Disposition + event `subscribers_export_downloaded`. **Decision 16 audit invariants** (F4): tampered token → 401 `invalid_token` + event `subscribers_export_download_denied{reason:"invalid_token", tokenSegmentPrefix:"..."}`; expired token → 410 `export_expired` + event `..._denied{reason:"expired"}`; project soft-deleted → 410 `export_project_unavailable` + event `{reason:"project_unavailable"}`; PURGED → 410 `export_purged` + event `{reason:"purged"}`; cross-project token substitution (mint with project A, request against project B) → 401 (payload-projectId mismatch) + event `{reason:"invalid_token"}`. **Rate-limit under load** (`downloadRateLimit_parallel31Requests_one429`): `parallelInvoke(31, () -> GET /download?token=...)` → exactly 30× 200 + 1× 429 + event `{reason:"rate_limited"}` for the 31st (race-shape parity with `SubscriberExportConcurrencyIT`). **Refresh URL endpoint** (`refreshUrl_*`): DONE+expired → 200 fresh token + new `expiresAt`; PENDING/RUNNING → 409 `export_in_flight`; PURGED → 410 `export_purged`; FAILED → 410 `export_failed`; 6th refresh in 1h → 429 (Decision 15 bucket); Redis-down during refresh → 503 + `EXPORT_REFRESH_REDIS_FAIL_CLOSED` WARN constant (fail-closed per Decision 15).
- `ExportCleanupJobIT` — seed `SubscriberExport{status=DONE, createdAt=now-8d}` + GridFS file → `cleanupJob.run()` → file gone, status=PURGED, event `subscribers_export_purged`. Idempotent: second run on PURGED → no-op.
- `ProjectSoftDeleteCascadeIT` — soft-delete project → all CRM endpoints return 404, signed download URLs return 410 `export_project_unavailable`. Hard-delete (`hardDeleteJob.run()` with seeded `Project.deletedAt < cutoff`) → all 4 child collections (subscribers, tags, subscriber_events, subscriber_exports) + GridFS files cleaned. **Order assertion** (`hardDelete_logsPerCollectionInExpectedOrder`): `ListAppender<ILoggingEvent>` filtered on `ProjectHardDeleteJob` logger captures per-step INFO lines — asserts `gridFsFilesRemoved` line emitted BEFORE `exportsRemoved` BEFORE `subscriberEventsRemoved` BEFORE `subscribersRemoved` BEFORE `tagsRemoved` BEFORE `project_hard_deleted` event before project doc drop. **AC22 mid-cascade retry** (`hardDelete_midCascadeCrash_nextRunResumes`): inject a `MongoTemplate` decorator that throws on the 3rd cascade step (subscriber_events) → assert exception propagates + first 2 steps' collections empty + remaining collections intact + project doc still present (no orphan `project_hard_deleted` event); on next `hardDeleteJob.run()`, idempotent — collections drained, deletion completes, project doc dropped.
- `CrossProjectIsolationIT` — same `telegramUserId` in project A and B (different bots, same owner) → 2 separate `Subscriber` documents; tags + customFields independent per project.
- `TelegramSenderSubscriberHookIT` — `TelegramSender.sendText(botId, chatId, text, "MarkdownV2", ownerId)` against `MockWebServer` 403 → asserts `SubscriberService.markBlockedByChatId` called via `@MockitoSpyBean` AND **direct Mongo state assertion** (not mock-only): `subscriberRepository.findByProjectIdAndTelegramChatId(...).getStatus() == BLOCKED`, `blockedAt` populated, event `subscriber_blocked` row present in `subscriber_events`, event `telegram_send_failed` row present in `events`. Same for 400 chat-not-found → markDeleted.
- `IndexCreationIT` — bootstrap app against fresh Mongo, autowire `MongoMappingContext`, assert all expected indexes present on `subscribers`, `tags`, `subscriber_events`, `subscriber_exports` collections with **exact-property assertions** (not just name): `(projectId, telegramUserId)` index has `unique=true`; subscribers text-index has `language="none"`; `subscriber_events.createdAt` TTL has `expireAfter == Duration.ofDays(365)`; `subscriber_exports` partial-unique index has `partialFilterExpression` byte-matching `{ status: { $in: [PENDING, RUNNING] } }`.
- `SubscriberStubReplacementIT` (AC24) — `ApplicationContext.getBeansOfType(SubscriberService.class).size() == 1` AND the single bean's `.getClass().getSimpleName().equals("SubscriberServiceImpl")` — Spring-context invariant that survives any future `@MockitoBean` slip.
- `SubscriberEventsIsolationIT` (Decision 10 dual-write invariant) — invoke webhook flow that writes `subscriber_registered` → assert `events` collection has zero rows of `eventType="subscriber_registered"` and exactly one row of `eventType="telegram_command_start"`. Repeats for `subscriber_unsubscribed` vs `telegram_command_stop`.

Latency probes under `@Tag("slow")`:
- `SubscriberControllerLatencyIT` — seed 1000 subscribers, hit `GET /subscribers?search=...&status=...&tags_include=...` → assert P95 < 300ms over 100-request burst.
- `SubscriberExportEstimateLatencyIT` — seed 100k subscribers, `estimatedCount(indexedFilter)` P95 < 200ms over 50 invocations.
- `SubscriberExportHeapIT` — seed 50k subscribers, run `ExportSubscribersJob.handle(exportId)` → assert JVM heap delta < 64 MB (capture via `ManagementFactory.getMemoryMXBean()` before/after). **Flake mitigation:** heap-delta is GC-sensitive; the test invokes `System.gc()` + `Thread.sleep(200)` after each measurement and reports the **minimum of 3 runs** (median fallback acceptable). If CI flakes regardless, the recommended next step is to switch to `MemoryPoolMXBean.getPeakUsage()` with `resetPeakUsage()` — explicitly NOT done now to keep the test surface small for MVP (accepted MVP trade-off).

### E2E tests

`frontend/e2e/subscribers.spec.ts` — single Playwright golden-path spec (~10 min).

Scenario steps:
1. Login → /projects → click project → `/subscribers` (asserts empty state with text from `subscribers.emptyState`).
2. Test fixture POSTs to `/webhooks/telegram/{projectId}` with valid secret → `/start` from `telegram_user_id=12345` → page reloads via `loadFirstPage` → one row visible.
3. Add second fixture subscriber (`firstName=Ivanna`) → search "Iva" finds one row (text-index match).
4. Click row → navigates to `/subscribers/{id}` → asserts Tabs (Tags, CustomFields, History) rendered.
5. Add tag "vip" via combobox (create-on-the-fly) → chip rendered → navigate to `/tags` → counter=1.
6. Navigate to `/custom-fields` → Add field `name=city, type=string` → submit → row appears → back to subscriber profile → CustomFields tab shows `city` row → inline edit value "Київ" → save → row updated.
7. Click "Export CSV" → toast "Export started" → poll "Recent exports" dialog until first row shows `status=DONE` (timeout 30s; CSV is small) → click Download → CSV downloaded → parse header + 2 rows (UTF-8 BOM correct, "Київ" renders in custom_fields JSON column).
8. Manual unsubscribe first subscriber → confirm modal → status badge flips to "Unsubscribed" within 1s.

Vitest specs: `frontend/tests/components/AddTagDialog.spec.ts`, `AddCustomFieldDialog.spec.ts`, `SendPersonalMessageDialog.spec.ts`, `SubscribersFilterBar.spec.ts` — vee-validate + zod schema validation, computed-ref pattern per `patterns.md:145`.

## Agent Verification Plan

**Source:** user-spec "Как проверить" → Агент проверяет (steps 1-10) and Пользователь проверяет.

### Verification approach

Per-task smoke checks are specified in each task's `Verify-smoke` / `Verify-user` fields in Implementation Tasks. Beyond automated tests, the agent runs:
- `./gradlew test` (default) → all green; `./gradlew test -PrunSlow=true --tests *Latency*IT --tests *HeapIT` → slow probes pass.
- `./gradlew bootRun` then `curl http://localhost:8080/health` → `{"status":"ok"}`.
- `mongosh botfunnel --eval 'db.subscribers.getIndexes()'` and analogues for `tags`, `subscriber_events`, `subscriber_exports` → expected indexes present (unique compound + text + TTL + partial-unique).
- `cd frontend && pnpm dev` then visit `/subscribers`, `/tags`, `/custom-fields`, `/subscribers/{id}` → all render without console errors; `pnpm prebuild` → locale parity passes.
- `cd frontend && pnpm playwright test subscribers.spec.ts --reporter=line` → green.
- `grep -r "NoOpSubscriberService" backend/src/main` → empty.

Post-deploy checks are described in the Post-deploy verification task description below.

### Tools required

- `curl` — health, fake webhook trigger.
- `bash` — gradle, pnpm, mongosh.
- `mongosh` (Docker Compose `mongo` service) — index inspection, post-cleanup state probes.
- Playwright (already in frontend devDeps) — E2E spec runner.
- Telegram MCP / real Telegram app + ngrok — post-deploy staging smoke only (`docs/staging-smoke/09-subscribers.md` runbook), not used by agent in dev loop.

## Risks

| Risk | Mitigation |
|------|-----------|
| R1 — CSV export OOM at 100k+ subscribers | `MongoTemplate.stream` + `PipedOutputStream` → GridFS (zero in-memory list — Decision 7); `@Tag("slow")` heap-bound IT asserts <64MB delta at 50k seeded |
| R2 — Mongo 8 partial-unique `$in` syntax in Spring Data | Verified in codebase (`RawUpdate.java:54-57`) per code-research §16.2 — no fallback needed; Decision 2 |
| R3 — CustomFieldDefinition type immutability disappoints user | Documented in `/custom-fields` UI ("Type cannot be changed later; delete-and-recreate"); delete confirm shows affected subscriber count |
| R4 — Race on 20-cap CustomFieldDefinition | Atomic conditional push using `customFieldDefinitions.20` exists-false guard — Decision 3 |
| R5 — Cross-project isolation regression | Unique compound `(projectId, telegramUserId)` + `CrossProjectIsolationIT` explicit dual-project assertion |
| R6 — TelegramSender ↔ SubscriberService cyclic-dependency | Sender constructor-injects `SubscriberService`; documented as architectural rule: `SubscriberService MUST NOT inject TelegramSender` (broadcast workers in Epic 07 get sender directly) — Decision 4 |
| R7 — Existing `NoOpSubscriberService` conflicts with new `@Service` | Delete no-op + its test atomically; single `@Service` after Wave 2 — Decision 1, supports AC24 |
| R8 — Signed URL HMAC key compromise | 7-day expiry on token; env-var rotated quarterly via deploy; key never logged; download event audited for anomaly detection |
| R9 — `ExportSubscribersJob` long-running occupies JobRunr workers | One in-flight per project enforced (Decision 2); cross-project concurrency capped by JobRunr default pool size (accept MVP trade-off); Phase 2 epic can add dedicated worker pool labels |
| R10 — GridFS purge sweep performance | Preselect `fileId` from `subscriber_exports` doc → batch-delete by indexed fileId; collection-scan 1×/day OK |
| R11 — `ProjectHardDeleteJob` extension complexity (6-step cascade) | Order codified in Decision 8; `ProjectSoftDeleteCascadeIT` asserts per-step results post-run; idempotent per step |
| R12 — Rate-limit fail-open during Redis outage | Codebase convention (`AuthService`, `BotService`); WARN log via greppable constant for monitoring; accept MVP risk per user-spec — Decision 9, R15 |
| R13 — Subscriber history feed feedback loop (broadcast/funnel events fill feed) | `limit=50` on profile fetch; scroll-pagination deferred to Phase 2 |
| R14 — Production auto-index creation timing | Initial deploy has empty collections — index creation instant. Subsequent deploys: index changes are additive (no rebuild); `IndexCreationIT` asserts post-bootstrap presence |
| R15 — Rate-limit fail-open monitoring gap (`/start` flood unbounded surface) | Epic 09 (analytics) adds Redis-down alert; runbook: manual `mongosh` purge of subscribers created within outage window |
| R16 — Text-index "none" language fails partial-word matches | `language="none"` does whole-word match; user-spec calls min-2-chars search → operator types meaningful tokens; Atlas Search deferred |

## User-Spec Deviations

None substantive. The tech-spec resolves two user-spec "tech-spec chooses" placeholders and one fallback consideration as follows; all are within the user-spec author's explicit delegation, not contradictions:

- **R2 resolution (partial-unique vs Redis-lock fallback):** Chosen partial-unique compound index per code-research §16.2 (proven in `RawUpdate.java:54-57`). Redis-lock fallback NOT implemented. → [DELEGATED BY USER-SPEC]
- **R4 resolution (20-cap race strategy):** Chosen atomic conditional push using `customFieldDefinitions.20` exists-false guard (Decision 3). → [DELEGATED BY USER-SPEC]
- **Tag delete cascade async fallback (user-spec AC8 mentions "tech-spec обирає"):** Always synchronous (Decision 17). No async JobRunr fallback for very-large projects in MVP. → [DELEGATED BY USER-SPEC]
- **Phasing:** User opted for single tech-spec, all 17 tasks in one document (user clarification: Option A). Implementation tasks are organized as 4 waves; export pipeline isolated in Wave 3 for review-surface clarity. → [APPROVED BY USER]

## Acceptance Criteria

User-facing acceptance criteria are AC1-AC24 in `work/09-subscribers/user-spec.md`. Technical additions below.

- [ ] All 24 user-spec AC's verified by automated tests (unit + integration + E2E per Testing Strategy).
- [ ] `SubscriberServiceImpl` is the sole `@Service` implementing `SubscriberService`; `NoOpSubscriberService.java` + `NoOpSubscriberServiceTest.java` deleted; `grep -r "NoOpSubscriberService" backend/src/main` returns empty.
- [ ] All new endpoints mount under `/api/v1/projects/{projectId}/...` and call `ProjectService.requireOwned(currentUserId(), projectId, false)` BEFORE any other work (anti-IDOR, anti-enumeration).
- [ ] All new collections (`subscribers`, `tags`, `subscriber_events`, `subscriber_exports`) auto-create their indexes on application bootstrap (verified by `IndexCreationIT`); TTL on `subscriber_events.createdAt` = 365d; partial-unique on `subscriber_exports` for in-flight enforcement.
- [ ] No new Maven dependencies in `backend/build.gradle`.
- [ ] All Request records carry `@JsonIgnoreProperties(ignoreUnknown = true)`; integration test `TagControllerIT.create_hostileBodyWithOwnerId_ignoresMassAssignment` seeds hostile body `{slug:"vip", ownerId:"<other>"}` on `POST /tags` and asserts persisted tag's `projectId` is from `requireOwned` (NOT from body), response body does not echo `ownerId`. Same shape asserted in `SubscriberPersonalMessageIT.create_hostileBodyMassAssignment_dropped`.
- [ ] All new error codes (`tag_name_taken`, `custom_field_limit_reached`, `custom_field_name_taken`, `custom_field_immutable`, `custom_field_type_mismatch`, `tag_slug_immutable`, `already_unsubscribed`, `export_in_flight`, `export_filter_too_large`, `export_expired`, `export_purged`, `export_failed`, `export_project_unavailable`, `invalid_token`, `telegram_rate_limited`) wired through `GlobalErrorHandler` with the documented HTTP status.
- [ ] No regressions in existing test suite (`./gradlew test` green; existing `ProcessTelegramUpdateJobTest`, `TelegramSenderTest`, `ProjectHardDeleteJobIT`, `TelegramWebhookControllerIT` continue to pass with no signature changes to existing methods).
- [ ] New env vars added to `.env.example` and documented in `deployment.md`: `SUBSCRIBER_EXPORT_TOKEN_KEY` (32-byte hex, fail-fast on missing/short/non-hex — mirror `TokenEncryptor`), `SUBSCRIBER_EXPORT_RETENTION_DAYS=7`, `SUBSCRIBER_EXPORT_URL_TTL_HOURS=24`, `SUBSCRIBER_RATE_LIMIT_START_PER_MIN=100`, `SUBSCRIBER_DOWNLOAD_RATE_LIMIT_PER_MIN=30`, `SUBSCRIBER_PERSONAL_MESSAGE_RATE_LIMIT_PER_MIN=60`.
- [ ] CSV exports neutralize formula-injection prefixes (`= + - @ \t \r`) per Decision 7; verified by `SubscriberCsvWriterTest` formula-injection cases.
- [ ] Download endpoint emits `subscribers_export_download_denied` audit event with `reason` enum on every non-200 outcome per Decision 16; verified by `SubscriberExportSignedUrlIT` Decision 16 invariants.
- [ ] Single Spring `@Service` bean implementing `SubscriberService` after Wave 2; verified by `SubscriberStubReplacementIT` context-level assertion.
- [ ] CRM lifecycle events written ONLY to `subscriber_events` collection (no duplicate writes to platform `events`); verified by `SubscriberEventsIsolationIT`.
- [ ] `frontend/i18n/locales/{uk,en}.json` carry parity-checked `subscribers.*`, `tags.*`, `customFields.*`, `exports.*` namespaces; `pnpm prebuild` exits 0.
- [ ] `docs/staging-smoke/09-subscribers.md` runbook created and self-contained (no external doc lookups required to execute).
- [ ] `SubscriberCsvWriter` produces UTF-8 BOM, RFC 4180-escaped output, stable column order per Decision 7; verified by unit test + E2E download parse.

## Implementation Tasks

### Wave 1 — Foundations (parallel, independent)

#### Task 1: Backend data layer + crypto primitive
- **Status:** Done (commit 9acd6e9, review fix cfda994)
- **Description:** Create all new entities (`Subscriber`, `Tag`, `SubscriberEvent`, `CustomFieldDefinition` record, `SubscriberExport`) + their repositories with full index annotations (unique compound, text-index, TTL, partial-unique per Decision 2). Add `CustomFieldDefinition`/`CustomFieldType` to `Project` entity (embedded list field, no migration). Implement `common.crypto.SignedDownloadToken` (HMAC-SHA256, fail-fast env-var parse mirroring `TokenEncryptor`). Wire `SUBSCRIBER_EXPORT_TOKEN_KEY` in `application.properties` as `app.subscriber.export.token-key`. Unit tests for `SignedDownloadToken` mint/verify/tamper/expiry.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew bootRun` → `mongosh botfunnel --eval 'db.subscribers.getIndexes()'` shows unique compound `(projectId, telegramUserId)`, text-index, plus the secondary indexes per Data Models section; analogues for `tags`, `subscriber_events`, `subscriber_exports`.
- **Files to modify:** `backend/src/main/java/com/botfunnel/subscriber/Subscriber.java` (new), `SubscriberRepository.java` (new), `SubscriberStatus.java` (new), `SubscriberEvent.java` (new), `SubscriberEventRepository.java` (new), `subscriber/export/SubscriberExport.java` (new), `SubscriberExportRepository.java` (new), `ExportStatus.java` (new), `tag/Tag.java` (new), `tag/TagRepository.java` (new), `project/Project.java` (add `customFieldDefinitions`), `project/CustomFieldDefinition.java` (new), `project/CustomFieldType.java` (new), `common/crypto/SignedDownloadToken.java` (new), `backend/src/main/resources/application.properties` (add `app.subscriber.export.token-key`).
- **Files to read:** `backend/src/main/java/com/botfunnel/webhook/RawUpdate.java` (partial-filter $in precedent), `backend/src/main/java/com/botfunnel/bot/Bot.java` (compound-index precedent + class-load enum guard), `backend/src/main/java/com/botfunnel/common/crypto/TokenEncryptor.java` (fail-fast template), `backend/src/main/java/com/botfunnel/project/Project.java` (existing fields), `backend/src/main/java/com/botfunnel/events/Event.java` (entity convention).

#### Task 2: Frontend foundation — shadcn-vue scaffolding + full i18n keys + sidebar sub-nav + page shells
- **Status:** Done (commit d53bed3; Verify-user deferred to smoke.md)
- **Description:** Scaffold all 11 shadcn-vue components (Decision 14) and seed the COMPLETE `subscribers.*` / `tags.*` / `customFields.*` / `exports.*` i18n key sets in both `uk.json` and `en.json` — no other task touches locale files (closes Wave 4 merge-conflict risk flagged in template review). Add sub-nav under "Subscribers" + empty page shells.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd frontend && pnpm install && pnpm prebuild && pnpm dev` → manual nav click through all 4 sub-routes renders without console errors.
- **Verify-user:** open localhost:3000 → log in → select project → click Subscribers in sidebar → sub-nav shows 3 items → all navigate cleanly.
- **Files to modify:** `frontend/components/ui/<11 dirs>/` (new, generated), `frontend/i18n/locales/uk.json`, `frontend/i18n/locales/en.json`, `frontend/layouts/default.vue` (or sidebar component — read first), `frontend/pages/projects/[projectId]/subscribers/index.vue` (new shell), `frontend/pages/projects/[projectId]/subscribers/[subscriberId].vue` (new shell), `frontend/pages/projects/[projectId]/tags/index.vue` (new shell), `frontend/pages/projects/[projectId]/custom-fields/index.vue` (new shell).
- **Files to read:** `frontend/components/ui/dialog/` (existing scaffold reference), `frontend/scripts/check-locales.mjs` (parity gate semantics), `frontend/layouts/default.vue`, `frontend/composables/useApi.ts`, `frontend/components/SettingsSubnav.vue` (sub-nav precedent from Settings split per `patterns.md:183`).

### Wave 2 — Services and integration hooks (parallel, depends on T1)

#### Task 3: SubscriberServiceImpl — state machine, rate-limit, reactivation, events
- **Status:** Done (commit 4160e0e; reviews — code round 1 OK, security + test round 2 OK)
- **Description:** Replace `NoOpSubscriberService` with `SubscriberServiceImpl @Service` per Decisions 1, 9, 10. Extend `SubscriberService` interface with the new lifecycle/audit methods (`markBlockedByChatId`, `markDeletedByChatId` — Task 6 calls them; `unsubscribeManual` — Task 8; `recordCustomFieldsSet(projectId, subscriberId, oldValues, newValues)` — Task 5's custom-fields PATCH sole-writer path per Decision 10; `addTag`/`removeTag` — Task 8). Idempotency, reactivation rules, rate-limit shape, and event taxonomy come from the Decisions section; do not duplicate that detail here.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew test --tests SubscriberServiceImplIT --tests SubscriberRateLimitIT --tests SubscriberStatusMachineTest --tests SubscriberStubReplacementIT --tests SubscriberEventsIsolationIT` → green.
- **Files to modify:** `backend/src/main/java/com/botfunnel/subscriber/SubscriberService.java` (extend interface with 3 new methods), `SubscriberServiceImpl.java` (new), `SubscriberServiceImplIT.java` (new), `SubscriberStatusMachineTest.java` (new), `SubscriberRateLimitIT.java` (new), `SubscriberStubReplacementIT.java` (new), `SubscriberEventsIsolationIT.java` (new), `application.properties` (add `app.subscriber.rate-limit.start-per-min`), `.env.example` (add `SUBSCRIBER_RATE_LIMIT_START_PER_MIN=100`). Delete: `NoOpSubscriberService.java`, `NoOpSubscriberServiceTest.java`.
- **Files to read:** `backend/src/main/java/com/botfunnel/subscriber/SubscriberService.java` (interface, byte-identical signature), `backend/src/main/java/com/botfunnel/bot/BotService.java` (rate-limit pattern, lines 250-278), `backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java` (caller call sites), `backend/src/test/java/com/botfunnel/webhook/ProcessTelegramUpdateJobTest.java` (test fixture reuse).

#### Task 4: TagService + TagController + denormalized counter
- **Status:** Done (commit faaf92a; reviews round 1 — code/security/test all OK)
- **Description:** Ship `TagService` (find-or-create with atomic `$inc` counter) and `TagController` (CRUD: create, list, label-only PATCH, sync delete cascade per Decision 17). Subscriber-tag association endpoints are wired by Task 8 — this task delivers only the service + standalone tag CRUD endpoints.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew test --tests TagServiceTest --tests TagControllerIT.create_* --tests TagControllerIT.list_* --tests TagControllerIT.delete_* --tests TagSlugValidatorTest` → green. (Counter inc/dec via subscriber endpoints is covered in Task 8's verify-smoke once `SubscriberController` exists.)
- **Files to modify:** `backend/src/main/java/com/botfunnel/tag/TagService.java` (new), `tag/TagController.java` (new), `tag/dto/*` (new), `TagControllerIT.java` (new), `TagSlugValidatorTest.java` (new).
- **Files to read:** `backend/src/main/java/com/botfunnel/project/ProjectController.java` (controller idiom), `backend/src/main/java/com/botfunnel/bot/BotService.java` (DuplicateKeyException → 409 mapping), `backend/src/main/java/com/botfunnel/common/GlobalErrorHandler.java` (error-code wiring).

#### Task 5: CustomField schema CRUD + per-type value validation
- **Status:** Done (commit 7330489 impl, e1c9672 review round 1; reviews round 1 — code/security/test all OK, minors applied)
- **Description:** Ship `CustomFieldsController` (CRUD on `Project.customFieldDefinitions[]` per Decision 3), `CustomFieldValueValidator` (per-type rules per AC11 — see Data Models DTO comments + unit test list), and `SubscriberCustomFieldsController` `PATCH` endpoint with mass-assignment silent-drop. Definition delete cascades values out of all project subscribers.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew test --tests CustomFieldDefinitionIT --tests CustomFieldValueValidationIT --tests CustomFieldValueValidatorTest` → green; manual: POST 21 definitions to one project → 21st returns 422 `custom_field_limit_reached`.
- **Files to modify:** `backend/src/main/java/com/botfunnel/project/CustomFieldsController.java` (new), `project/dto/CreateCustomFieldRequest.java` (new), `project/dto/UpdateCustomFieldRequest.java` (new), `project/dto/CustomFieldResponse.java` (new), `subscriber/CustomFieldValueValidator.java` (new), `subscriber/SubscriberCustomFieldsController.java` (new — PATCH endpoint split out of Task 8's `SubscriberController` to avoid a Wave-2/Wave-3 collision; sole event-emit via `recordCustomFieldsSet`), `subscriber/dto/SetCustomFieldsRequest.java` (new), `CustomFieldDefinitionIT.java` (new), `CustomFieldValueValidationIT.java` (new), `CustomFieldValueValidatorTest.java` (new).
- **Files to read:** `backend/src/main/java/com/botfunnel/project/ProjectService.java` (`requireOwned`), `backend/src/main/java/com/botfunnel/project/Project.java` (post Task 1 modification — embed array).

#### Task 6: TelegramSender hook — terminalReason + markBlocked/markDeleted
- **Status:** Done (commit 9bccb86 impl, 94ddb34 review round 1; reviews — code/security round 1 OK, test round 2 OK after fixes)
- **Description:** Wire `TelegramSender` to call `SubscriberService.markBlocked/markDeleted` on terminal 403/400-chat-not-found per Decision 4. Mechanics, ordering, and constructor surgery are spelled out in the Decision — implementation follows it verbatim.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew test --tests TelegramSenderSubscriberHookIT --tests TelegramSendExceptionTerminalReasonTest --tests TelegramSenderTest` (existing sender tests still pass — no regressions).
- **Files to modify:** `backend/src/main/java/com/botfunnel/bot/TelegramSender.java` (extend ctor + catch hook), `bot/TelegramSendException.java` (add TerminalReason enum + getter), `backend/src/test/java/com/botfunnel/bot/TelegramSenderTest.java` (update test ctor invocations), `TelegramSendExceptionTerminalReasonTest.java` (new), `TelegramSenderSubscriberHookIT.java` (new).
- **Files to read:** `backend/src/main/java/com/botfunnel/bot/TelegramSender.java`, `backend/src/main/java/com/botfunnel/bot/Bot.java`, code-research §16.1 for verified file:line citations.

#### Task 7: ProjectHardDeleteJob cascade extension
- **Status:** Done (commit 79badcc impl, 8ceec6c review round 1, b9c0247 review round 2; reviews — code/security round 1 approved with minors documented, test round 2 passed after idempotency-test rework)
- **Description:** Extend `ProjectHardDeleteJob` cascade per Decision 8 (full order spelled out there). Update the single structured INFO log line at end-of-run to include per-collection removedCount fields (`gridFsFilesRemoved`, `exportsRemoved`, `subscriberEventsRemoved`, `subscribersRemoved`, `tagsRemoved`).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew test --tests ProjectHardDeleteJobIT --tests ProjectSoftDeleteCascadeIT` → green (asserts cascade order via 7-collection pre-seed + post-run state).
- **Files to modify:** `backend/src/main/java/com/botfunnel/jobs/ProjectHardDeleteJob.java`, `backend/src/test/java/com/botfunnel/jobs/ProjectHardDeleteJobIT.java` (extend assertions for new collections), `backend/src/test/java/com/botfunnel/project/ProjectSoftDeleteCascadeIT.java` (new).
- **Files to read:** `backend/src/main/java/com/botfunnel/jobs/ProjectHardDeleteJob.java` (current cascade lines 67-93), `backend/src/test/java/com/botfunnel/jobs/ProjectHardDeleteJobIT.java`.

### Wave 3 — HTTP layer and export pipeline (depends on Wave 2)

#### Task 8: SubscriberController + TagController endpoints wiring
- **Status:** Done (commit edacea9 impl, 71cfece review round 1; reviews — round 2 code/security/test all approved)
- **Description:** Implement `SubscriberController` endpoints: `GET /subscribers` (list with text search, status filter, tag include/exclude, dateRange, cursor pagination per Decision 5), `GET /subscribers/{id}` (profile), `GET /subscribers/{id}/events?limit=50` (history feed), `POST /subscribers/{id}/unsubscribe` (manual + idempotent 409 `already_unsubscribed`), `POST /subscribers/{id}/tags`, `DELETE /subscribers/{id}/tags/{slug}`, `POST /subscribers/{id}/messages` (text validation 1..4096; calls `TelegramSender.sendText`, maps 403/400/429 per AC5 to body `{status, message}` + status code). All endpoints first-line: `Project project = projectService.requireOwned(currentUserId(), projectId, false);`. Implement `SegmentFilterBuilder` (DTO → Mongo `Query`).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew test --tests SubscriberControllerIT --tests SubscriberProfileIT --tests SubscriberPersonalMessageIT --tests SubscriberManualUnsubscribeIT --tests SegmentFilterBuilderTest` → green; `curl -b cookies "http://localhost:8080/api/v1/projects/{id}/subscribers?limit=10"` → 200 with proper response shape.
- **Files to modify:** `backend/src/main/java/com/botfunnel/subscriber/SubscriberController.java` (new), `subscriber/SegmentFilter.java` (new immutable record), `subscriber/SegmentFilterBuilder.java` (new), `subscriber/dto/*` (new — see Data Models), `SubscriberControllerIT.java` (new), `SubscriberProfileIT.java` (new), `SubscriberPersonalMessageIT.java` (new), `SubscriberManualUnsubscribeIT.java` (new), `SegmentFilterBuilderTest.java` (new), `TelegramTextValidatorTest.java` (new).
- **Files to read:** `backend/src/main/java/com/botfunnel/project/ProjectController.java` (currentUserId + controller idiom), `backend/src/main/java/com/botfunnel/bot/BotController.java` (`/api/v1/projects/{projectId}/...` sub-resource pattern), `backend/src/main/java/com/botfunnel/subscriber/SubscriberServiceImpl.java` (from Task 3).

#### Task 9: Export pipeline — controller + job + cleanup + download + refresh-url + email
- **Status:** Done (commit d5672cb impl, 5399f83 review round 1; reviews round 2 — code/security/test all approved). Deviation: download endpoint kept session-guarded (`/api/**` authenticated) in addition to the HMAC token, stricter than §146's "HMAC-only" — see decisions.md.
- **Description:** Ship the full async export pipeline: `SubscriberExportController` (POST/list/download/refresh-url), `ExportSubscribersJob`, `ExportCleanupJob`, `SubscriberCsvWriter` (with formula-injection neutralization per Decision 7), `EmailService` extension + 2 new templates. Mechanics per Decisions 7, 8, 9, 15, 16.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew test --tests SubscriberExportIT --tests SubscriberExportConcurrencyIT --tests SubscriberExportSignedUrlIT --tests ExportCleanupJobIT --tests SubscriberCsvWriterTest --tests SignedDownloadTokenTest` → green; `./gradlew test -PrunSlow=true --tests SubscriberExportHeapIT --tests SubscriberExportEstimateLatencyIT` → green.
- **Files to modify:** `backend/src/main/java/com/botfunnel/subscriber/export/SubscriberExportController.java` (new), `subscriber/export/SubscriberCsvWriter.java` (new), `subscriber/jobs/ExportSubscribersJob.java` (new), `subscriber/jobs/ExportCleanupJob.java` (new), `email/EmailService.java` (add `sendExportReadyEmail`, `sendExportFailedEmail`, body builders; internal `sendAsync` reused — method stays private), `backend/src/main/resources/templates/email/subscribers-export-ready.html` (new), `subscribers-export-failed.html` (new), `application.properties` (add `app.subscriber.export.retention-days`, `app.subscriber.export.download-rate-per-min`, `app.subscriber.export.url-ttl-hours`), `.env.example` (add `SUBSCRIBER_EXPORT_RETENTION_DAYS=7`, `SUBSCRIBER_DOWNLOAD_RATE_LIMIT_PER_MIN=30`, `SUBSCRIBER_EXPORT_URL_TTL_HOURS=24`, `SUBSCRIBER_PERSONAL_MESSAGE_RATE_LIMIT_PER_MIN=60` + comments). Integration tests: `SubscriberExportIT.java`, `SubscriberExportConcurrencyIT.java`, `SubscriberExportSignedUrlIT.java`, `ExportCleanupJobIT.java`, `SubscriberCsvWriterTest.java`, `SubscriberExportHeapIT.java`, `SubscriberExportEstimateLatencyIT.java`.
- **Files to read:** `backend/src/main/java/com/botfunnel/email/EmailService.java` (existing pattern), `backend/src/main/java/com/botfunnel/webhook/TelegramWebhookController.java` (deterministic UUID enqueue precedent, lines 85-87), `backend/src/main/java/com/botfunnel/jobs/ProjectHardDeleteJob.java` (recurring job pattern), `backend/src/main/java/com/botfunnel/common/crypto/SignedDownloadToken.java` (from Task 1).

### Wave 4 — Frontend pages (depends on Wave 3 HTTP layer)

#### Task 10: Subscribers list + profile pages
- **Description:** Build `/subscribers` list (filter bar, table, Load-More, Export trigger) and `/subscribers/{id}` profile (identity card, action buttons, Tabs Tags/CustomFields/History, Send Personal Message dialog). Pinia store + Decision 12 pattern. No i18n key additions — Task 2 has already seeded the full key set.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** localhost:3000 → /subscribers shows table + filters; click row → profile loads + tabs render; add tag works; set custom field value works; send message works.
- **Files to modify:** `frontend/pages/projects/[projectId]/subscribers/index.vue`, `subscribers/[subscriberId].vue`, `frontend/stores/subscribers.ts` (new), `frontend/components/subscribers/SubscribersFilterBar.vue` (new), `SubscribersTable.vue` (new), `SubscriberProfileCard.vue` (new), `SubscriberTagsTab.vue` (new), `SubscriberCustomFieldsTab.vue` (new), `SubscriberHistoryTab.vue` (new), `SendPersonalMessageDialog.vue` (new), `ExportCsvDialog.vue` (new). **Does NOT modify `i18n/locales/*` — Task 2 owns those.**
- **Files to read:** `frontend/pages/projects/index.vue` (list page precedent), `frontend/stores/projects.ts` (Pinia setup-store pattern), `frontend/composables/useApi.ts`, `frontend/composables/useApiError.ts`, `frontend/components/ui/dialog/` (existing scaffold), `frontend/pages/projects/new.vue` (vee-validate + zod + computed schema precedent per `patterns.md:145`).

#### Task 11: Tags page + Custom fields page + Recent exports + Playwright E2E + staging-smoke runbook
- **Status:** Done (commit 79f8ca2 impl; reviews round 2 — code/test approved, security round 1 approved). i18n gaps + backend-contract deviations recorded in decisions.md + smoke.md. JS suite (`pnpm test`/`prebuild`/`playwright`) deferred to user run — no Node/pnpm in execution session.
- **Description:** Build `/tags` + `/custom-fields` pages, Recent-exports dialog inside `/subscribers`, Playwright golden-path spec, and the `docs/staging-smoke/09-subscribers.md` runbook (consumed by Task 17). No i18n key additions — Task 2 owns locale files.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd frontend && pnpm playwright test subscribers.spec.ts --reporter=line` → green; `pnpm test` → green; `pnpm prebuild` → locale parity passes.
- **Verify-user:** localhost:3000 → /tags create+rename+delete works; /custom-fields full lifecycle works; export CSV flow end-to-end (button → poll → download → CSV opens in spreadsheet with Cyrillic intact).
- **Files to modify:** `frontend/pages/projects/[projectId]/tags/index.vue`, `frontend/pages/projects/[projectId]/custom-fields/index.vue`, `frontend/components/tags/CreateTagDialog.vue`, `RenameTagDialog.vue`, `DeleteTagDialog.vue`, `frontend/components/customFields/AddCustomFieldDialog.vue`, `EditCustomFieldDialog.vue`, `DeleteCustomFieldDialog.vue`, `frontend/components/subscribers/RecentExportsDialog.vue`, `frontend/e2e/subscribers.spec.ts` (new), `frontend/tests/components/AddTagDialog.spec.ts`, `AddCustomFieldDialog.spec.ts`, `SendPersonalMessageDialog.spec.ts`, `SubscribersFilterBar.spec.ts`, `docs/staging-smoke/09-subscribers.md` (new — runbook authored here, executed by Task 17). **Does NOT modify `i18n/locales/*` — Task 2 owns those.**
- **Files to read:** `frontend/e2e/i18n.spec.ts` (Playwright config precedent), `frontend/tests/pages/dashboard.spec.ts` (Vitest precedent), `frontend/pages/projects/new.vue` (form pattern).

### Audit Wave (parallel, reviewers: none)

#### Task 12: Code Audit
- **Description:** Full-feature code quality audit. Read all source files created/modified in Tasks 1-11 (use decisions.md + tech-spec "Files to modify" as inventory). Review holistically for cross-component issues: duplicate resource initialization, shared resources compliance with Architecture decisions (GridFsOperations, RedisTemplate, SignedDownloadToken singleton ownership), architectural consistency with `patterns.md` (controller idioms, DTO conventions, error-code mapping, Redis rate-limit shape, package layout). Write audit report.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 13: Security Audit
- **Status:** Done (report `logs/audit/security-audit-report.md`; 3 findings Low, posture ready)
- **Description:** Full-feature security audit. Read all source files created/modified in Tasks 1-11. Analyze for OWASP Top 10 across all components, with particular attention to: HMAC token verify (constant-time, projectId-in-payload, expiry), `requireOwned` coverage on every endpoint, mass-assignment protection on custom-field PATCH (silent-drop unknown keys), CSV injection on exported cells (formulas starting with `=`/`+`/`-`/`@`), webhook 403/400 → markBlocked/markDeleted side-effect safety, rate-limit fail-open exposure documentation. Write audit report.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 14: Test Audit
- **Status:** Done (report `logs/audit/test-audit-report.md`; verdict BACKFILL_REQUIRED — 1 P0 (AC7 CrossProjectIsolationIT absent), 3 P1, 6 P2)
- **Description:** Full-feature test quality audit. Read all test files created in Tasks 1-11 (unit + integration + E2E). Verify coverage of AC1-AC24 from user-spec, meaningful assertions (not just "no exception"), test pyramid balance (unit-heavy on validators / state machine; integration for cross-module flows; one E2E golden path). Check `@Tag("slow")` correctly applied to latency/heap probes. Write audit report.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 15: Pre-deploy QA
- **Status:** Done (verdict `passed`; report `logs/qa/pre-deploy-qa-report.md`; 0 critical, 5 major incl. 3 absent tests carried from Task 14, 2 minor; 6 criteria deferred to post-deploy)
- **Description:** Acceptance testing without live environment: run full test suite (`./gradlew test`), run slow tests (`./gradlew test -PrunSlow=true`), run frontend Vitest (`pnpm test`), run Playwright (`pnpm test:e2e`), run locale parity (`pnpm prebuild`). Verify each AC1-AC24 from user-spec has a passing automated test. Verify all 11 technical AC's from this tech-spec. Write QA report.
- **Skill:** pre-deploy-qa
- **Reviewers:** none

#### Task 16: Deploy
- **Description:** Add all 6 new env vars to GitHub Actions secrets: `SUBSCRIBER_EXPORT_TOKEN_KEY` (generate via `openssl rand -hex 32`), `SUBSCRIBER_EXPORT_RETENTION_DAYS=7`, `SUBSCRIBER_EXPORT_URL_TTL_HOURS=24`, `SUBSCRIBER_RATE_LIMIT_START_PER_MIN=100`, `SUBSCRIBER_DOWNLOAD_RATE_LIMIT_PER_MIN=30`, `SUBSCRIBER_PERSONAL_MESSAGE_RATE_LIMIT_PER_MIN=60`. Push branch → PR to `main` → CI runs (build, tests, locale parity, lint) → squash merge → GitHub Actions deploy workflow triggers → verify deploy logs show successful index creation for new collections (`subscribers`, `tags`, `subscriber_events`, `subscriber_exports`) + GridFS file collection initialization. Update `deployment.md` to document new env vars.
- **Skill:** deploy-pipeline
- **Reviewers:** code-reviewer, security-auditor, deploy-reviewer

#### Task 17: Post-deploy verification
- **Description:** Execute the `docs/staging-smoke/09-subscribers.md` runbook (authored by Task 11) on staging: throwaway BotFather bot via ngrok → real Telegram `/start` from operator → verify `/subscribers` shows operator within 2s; add tag "smoke_test" → `/tags` counter=1; define custom field `city`=string, set value "Kyiv" via UI; send personal message "hello" → arrives in Telegram; `/stop` → Unsubscribed within 2s; `/start` again → reactivates with tag + custom field intact; click Export CSV → email arrives at test mailbox → click signed URL → CSV downloads → open in spreadsheet (Cyrillic city renders); attempt concurrent export → 409 / UI banner; (optional) manual unsubscribe → confirm → status flip; cleanup: disconnect bot, delete throwaway via BotFather. Tools required: Telegram MCP (or real Telegram app), curl (for fake webhook fallback if MCP not available), bash, mongosh (for index inspection on staging if needed).
- **Skill:** post-deploy-qa
- **Reviewers:** none
