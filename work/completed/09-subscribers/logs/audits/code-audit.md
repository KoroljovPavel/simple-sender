# Code Audit — Epic 09 (Subscribers)

**Auditor:** code-reviewing (Task 12)
**Scope:** Tasks 1-11 source + test files (cross-component sweep; per-task review already done by `code-reviewer` per task)
**Date:** 2026-05-27

## Summary at a glance
- Total findings: 4 (Critical: 0, Major: 0, Minor: 1, Info: 3)
- Prerequisite tasks 1-11: all `status: done` (verified).
- Merge recommendation: **go**

This audit is a cross-component consistency sweep — it does NOT re-do the per-task `code-reviewer` work. Prioritised dimensions: 1 (Architecture), 4 (Error Handling), 10 (Cross-File), 11 (Resource Management); light spot-checks of 8 (Security) cross-referenced to Task 13. No Decision is contradicted; no shared resource is duplicated; every new controller is `requireOwned`-first; the single-`@Service` invariant (Decision 1 / AC24) holds. The 4 findings are 1 documentation-vs-code drift (Minor) and 3 informational notes (already recorded in `decisions.md`).

## Decision → code mapping (Decisions 1-17)

| Decision | Implementing site | Verdict |
|----------|-------------------|---------|
| 1 — Delete `NoOpSubscriberService`, sole `@Service`, no `@Primary` | `subscriber/SubscriberServiceImpl.java:41` (only `@Service` impl); `grep NoOpSubscriberService backend/src/main` empty; no `@Primary` in Epic-09 code | OK |
| 2 — `export_in_flight` via partial-unique compound index | `subscriber/export/SubscriberExport.java:15-19` (partial `{status:{$in:[PENDING,RUNNING]}}`) + class-load enum guard `:28-38`; mapped at `SubscriberExportController.java:184-186` | OK |
| 3 — 20-cap atomic conditional push | `project/CustomFieldsController.java:57,112-125` (`customFieldDefinitions.<19>` exists-false guard → modifiedCount 0 → 422/409 re-disambiguation) | OK (spec off-by-one corrected — see Finding 2) |
| 4 — `TelegramSender` typed `terminalReason`, event BEFORE side-effect | `bot/TelegramSender.java:149-163` (event `telegram_send_failed` at `:156` precedes `dispatchSubscriberHook` at `:162`); ctor wires `SubscriberService` last `:88-96`, test ctor delegates via `this(...)` `:102-109`; mapping `:327-336` | OK |
| 5 — Opaque base64url cursor, strict decode | `subscriber/SegmentFilterBuilder.java:114-145` (base64url→object→exactly `{v,id}`→recursive no-`$`-key→`v` integral long→`id` 24-hex; uniform 400 `invalid_cursor`) | OK |
| 6 — Text-index `language="none"` (3/3/5) | `subscriber/Subscriber.java:19` (`@Document(language="none")`) + `:41,43,45` weights; query `SegmentFilterBuilder.java:92` (`TextCriteria.forDefaultLanguage().matchingAny`) | OK |
| 7 — CSV stream `MongoTemplate.stream`+`PipedOutputStream`→GridFS, formula neutralization on ALL cells | `subscriber/jobs/ExportSubscribersJob.java:153-201`; `subscriber/export/SubscriberCsvWriter.java:92-109` (`neutralizeFormula` applied per cell incl. stringified numeric/date + custom_fields JSON) | OK (column name/order drift — see Finding 1) |
| 8 — `ProjectHardDeleteJob` in-place cascade order | `jobs/ProjectHardDeleteJob.java:93-133`: events → GridFS(`:115`) → subscriber_exports(`:117`) → subscriber_events(`:118`) → subscribers(`:119`) → tags(`:120`) → `project_hard_deleted`(`:122-129`) → drop project(`:131-133`); per-collection counts in single INFO line `:135-140` | OK |
| 9 — 3 Redis buckets, INCR-every / EXPIRE-on-first / fail-open (download fail-CLOSED on refresh) | start: `SubscriberServiceImpl.java:270-284`; personal-message: `SubscriberController.java:290-304`; download: `SubscriberExportController.java:348-362`; refresh fail-CLOSED: `:364-380`. All 4 greppable WARN/FAIL constants present | OK |
| 10 — `subscriber_events` sole writer; personal-message ownership split | `SubscriberServiceImpl.java` writes all lifecycle events incl. `subscriber_blocked`/`subscriber_deleted` (`:197,207`); `SubscriberController.java:252,279` writes `personal_message_sent`/`_failed` ONLY (skips when reason≠OTHER `:264-269`); `TagService.java:130` writes only platform `tag_deleted` | OK |
| 11 — `@Pattern` slug, no custom annotation, immutable slug/name | DTO `@Pattern ^[a-z0-9_-]{1,32}$` (Tag/CustomField); name+type immutable via DTO whitelist + `@JsonIgnoreProperties` (`CustomFieldsController.java:141-157`); no `@ValidSlug` annotation exists | OK |
| 12 — Frontend cursor via Pinia setup-store, no persistence | `frontend/stores/subscribers.ts` (`grep localStorage` empty per Task 10 decisions.md) | OK (frontend — Task 14/10 scope; not re-audited here) |
| 13 — Export emails Ukrainian, reuse private `sendAsync` | `email/EmailService.java:51-60` (subjects "Експорт підписників готовий"/"…не вдався"), reuses private `sendAsync`/`loadTemplate`/`htmlEscape` — no visibility downgrade | OK |
| 14 — shadcn-vue scaffolding in Wave 1 | `frontend/components/ui/*` (12 dirs per Task 2 decisions.md) | OK (frontend — not re-audited) |
| 15 — 24h signed-URL TTL, refresh fail-CLOSED, single-key (no `kid`) | `common/crypto/SignedDownloadToken.java` (single key, sig→parse→expiry order `:82,88,93`); `SubscriberExportController.refreshUrl :312-344` + `enforceRefreshRateLimit :364-380` (503 `service_unavailable` on Redis outage) | OK (deferred multi-key per spec — accepted trade-off) |
| 16 — Audit event on every non-200 download | `SubscriberExportController.deny() :434-451` writes `subscribers_export_download_denied` to platform `events` with `reason` enum BEFORE response; reasons `:99-105` | OK |
| 17 — Tag delete cascade synchronous | `tag/TagService.deleteWithCascade :119-134` (bulk `$pull` → delete tag → `tag_deleted`); no async JobRunr job | OK |

## Shared-resource compliance (Architecture "Shared resources" table)

| Resource | Producer (single) | Consumers verified inject (no `new`) | Verdict |
|----------|-------------------|--------------------------------------|---------|
| `GridFsOperations` | Spring Boot `MongoDataAutoConfiguration` | `ExportSubscribersJob`, `ExportCleanupJob`, `SubscriberExportController`, `ProjectHardDeleteJob` — all ctor-injected | OK |
| `RedisTemplate`/`StringRedisTemplate` | `spring-boot-starter-data-redis` auto-config | `SubscriberServiceImpl:75`, `SubscriberController:98`, `SubscriberExportController:118` — all ctor-injected `StringRedisTemplate` | OK |
| `SignedDownloadToken` `@Component` | `common/crypto/SignedDownloadToken.java:30` | `SubscriberExportController`, `ExportSubscribersJob` — injected; `grep "new SignedDownloadToken"` empty | OK |
| `JobScheduler`/`StorageProvider` (JobRunr) | `JobRunrMongoConfig`(prod)/`JobRunrInMemoryConfig`(test) | `SubscriberExportController` (enqueue), `ExportSubscribersJob` (`@Job`), `ExportCleanupJob` (`@Recurring`) | OK |
| `SubscriberService` | `SubscriberServiceImpl @Service` (sole) | `ProcessTelegramUpdateJob`, `SubscriberController`, `SubscriberCustomFieldsController`, `TelegramSender` — injected; single bean (Decision 1/AC24) | OK |
| `MongoTemplate` | Spring Data auto-config | all new repos + `SubscriberServiceImpl`, controllers, jobs, `ProjectHardDeleteJob` — injected | OK |

Additional Epic-09 singleton helper not in the original table: `ExportUrlBuilder` `@Component` (`subscriber/export/ExportUrlBuilder.java:11`), injected by `ExportSubscribersJob` + `SubscriberExportController` — single bean, no `new`. OK.

## Grep-guard results

| # | Guard | Result |
|---|-------|--------|
| 1 | `grep -RIn "NoOpSubscriberService" backend/src/main` | **clean** (empty) — Decision 1 / AC24 |
| 2 | `grep -RIn "@Service" backend/src/main/java/com/botfunnel/subscriber/` | only `SubscriberServiceImpl.java:41` (other hit is a Javadoc reference, not wiring) — Decision 1 |
| 3 | `grep -RIn "new SignedDownloadToken\|new SubscriberServiceImpl\|new TagService" backend/src/main/java` | **clean** (empty) — singletons only |
| 4 | `requireOwned` in `subscriber`/`tag`/`CustomFieldsController` | present as first statement in every handler (Subscriber, SubscriberCustomFields, Export, Tag, CustomFields controllers) — AC21 |
| 5 | `@JsonIgnoreProperties` in dto packages | present on every Request record (subscriber/dto, tag/dto, project/dto); response records also carry it — mass-assignment defense |
| 6 | 4 fail-open/closed WARN constants | all present: `SUBSCRIBER_START_RATE_REDIS_FAIL_OPEN` (`SubscriberServiceImpl:68`), `PERSONAL_MESSAGE_RATE_REDIS_FAIL_OPEN` (`SubscriberController:84`), `EXPORT_DOWNLOAD_RATE_REDIS_FAIL_OPEN` + `EXPORT_REFRESH_REDIS_FAIL_CLOSED` (`SubscriberExportController:108-111`) — Decision 9/15 |
| 7 | `subscribers_export_download_denied` on non-200 download | `SubscriberExportController.java:86` constant, emitted via `deny()` `:449` on every non-200 path — Decision 16 |
| 8 | `mapPersistError`/`DuplicateKeyException` 409 reuse | `SubscriberExportController:184` (export_in_flight), `TagService:73` (tag_name_taken), `SubscriberServiceImpl:127` (idempotent upsert) — precedent reused |
| 9 | `TextCriteria.forDefaultLanguage` | `SegmentFilterBuilder.java:92`; `Subscriber.java:19` `language="none"` — Decision 6 |
| 10 | `PipedOutputStream`/`PipedInputStream`/`mongoTemplate.stream` | `ExportSubscribersJob.java:153-158` — Decision 7 streaming wiring |
| 11 | `telegram_send_failed` emission ORDER vs `markBlocked/markDeleted` | `TelegramSender.java:156` (event) precedes `:162` (`dispatchSubscriberHook`→mark-*) — Decision 4 invariant (event BEFORE side-effect) |
| + | `@Primary` in `backend/src/main/java` | **clean** — only hit is a comment in `funnel/NoOpFunnelTriggerService.java` (different epic; literally says "NOT annotated @Primary"). No `@Primary` in Epic-09 code |
| + | `TODO`/`FIXME`/`XXX`/`HACK` in Epic-09 src/main | **clean** (empty) |
| + | new `app.subscriber.*` props + `SUBSCRIBER_*` env vars | all 6 present in `application.properties:52-62` and `.env.example:38-48` |

## Findings

# Finding 1 — CSV header column naming + order drifts from Decision 7
**Severity:** Minor
**File:** `backend/src/main/java/com/botfunnel/subscriber/export/SubscriberCsvWriter.java:36-38`
**Observation:** Decision 7 / Solution (tech-spec line 52) specifies the column set+order as `subscriber_id, telegram_user_id, telegram_chat_id, first_name, last_name, username, language_code, status, subscribed_at, unsubscribed_at, blocked_at, deleted_at, last_seen_at, tags, custom_fields`. The shipped `HEADER` constant instead emits `id, telegram_user_id, telegram_chat_id, telegram_bot_id, first_name, last_name, username, language_code, status, tags, custom_fields, subscribed_at, unsubscribed_at, blocked_at, deleted_at, last_seen_at` — three deviations: (a) `subscriber_id` renamed to `id`; (b) an extra `telegram_bot_id` column added; (c) `tags`/`custom_fields` moved ahead of the four date columns. The output is internally consistent (unit test matches the code) and AC16 ("export works") is satisfied — spreadsheet consumers parse by header — but the shipped contract no longer matches the decided one. The E2E only asserts header presence + the `custom_fields` cell, so no test catches the divergence.
**Recommendation:** Reconcile spec and code in one direction. The shipped header is reasonable (adding `telegram_bot_id` is more useful data), so the lowest-friction fix is to update Decision 7's column list (and tech-spec line 52) to match the writer; alternatively rename `id`→`subscriber_id` and reorder in `SubscriberCsvWriter` if downstream consumers were promised the spec contract. Do NOT edit tech-spec from this task — flag for the feature lead.

# Finding 2 — Decision 3 off-by-one: spec names slot index 20, code correctly uses 19
**Severity:** Info
**File:** `backend/src/main/java/com/botfunnel/project/CustomFieldsController.java:54-57`
**Observation:** Decision 3 prescribes the guard `customFieldDefinitions.20` exists-false. Index 20 is the 21st slot, which would cap at 21, not the 20-element limit the acceptance criteria (`parallel21Posts_acceptsOnly20`, 21st-POST→422) require. The implementation correctly enforces the cap at index `MAX_DEFINITIONS - 1 = 19` and documents the reasoning inline. This is a case where the implementation is more correct than the decided text. Already recorded in `decisions.md` (Task 5 deviation 1).
**Recommendation:** Correct Decision 3's prose to name index 19 (or phrase as "the slot at `MAX_DEFINITIONS - 1`") so a future reader doesn't "fix" the code back to the off-by-one. Spec edit owned by feature lead — no code change.

# Finding 3 — Export refresh-url error codes not enumerated in tech-spec AC error-code list
**Severity:** Info
**File:** `backend/src/main/java/com/botfunnel/subscriber/export/SubscriberExportController.java:96-97`
**Observation:** The refresh-url endpoint throws `AppException` with codes `refresh_rate_limited` (429) and `service_unavailable` (503). Neither appears in the tech-spec AC error-code enumeration (tech-spec line 348/491), though `service_unavailable` is named in Decision 15 prose. There is no functional wiring gap: `GlobalErrorHandler` is a generic mapper that returns whatever `code`+status the `AppException` carries (confirmed by Task 4/5 decisions.md: "new error codes propagate via GlobalErrorHandler with no allow-list change"). The download endpoint's body codes (`rate_limited`, etc.) intentionally bypass `GlobalErrorHandler` (direct `ResponseEntity`), consistent with the "explicit-status responses bypass GlobalErrorHandler" webhook precedent.
**Recommendation:** Add `refresh_rate_limited` (429) and `service_unavailable` (503) to the tech-spec AC error-code list for completeness. No code change.

# Finding 4 — Download endpoint session-guarded (stricter than §146 "HMAC-only"); F4 audit covers authenticated recon only
**Severity:** Info
**File:** `backend/src/main/java/com/botfunnel/subscriber/export/SubscriberExportController.java:211-289` (cross-ref `SecurityConfig` `/api/**` authenticated)
**Observation:** Tech-spec §146 describes the download endpoint as "unauthenticated by session (HMAC only)". As shipped, it sits under `/api/**` so Spring Security requires an authenticated session in addition to the HMAC token (a deliberate Task 9 decision — `SecurityConfig` was out of task scope and permit-all on a PII endpoint is a security-sensitive change). Consequence: the Decision-16 `subscribers_export_download_denied` audit fires for authenticated-session recon but NOT for anonymous requests rejected at the filter chain (403). This is a detection-coverage trade-off, not data exposure — the file stays HMAC-protected. Already documented in `decisions.md` (Task 9) and flagged for product confirmation.
**Recommendation:** Product/lead to confirm whether session-guarding the download link is acceptable (the email recipient is the project owner, who has an account). If anonymous-recon visibility is required, revisit `SecurityConfig` in a follow-up. This is primarily a Task 13 (Security Audit) concern — cross-referenced here, not duplicated.

## Summary
Total: 4 findings (Critical: 0, Major: 0, Minor: 1, Info: 3). All 17 Decisions implemented as decided (Decision 3's code is more correct than its prose); all 6 shared resources have a single producer with inject-only consumers; all 11 grep guards plus the `@Primary`/`TODO`/config guards are clean; the single-`@Service` (Decision 1/AC24), `requireOwned`-first (AC21), mass-assignment (`@JsonIgnoreProperties`), formula-neutralization (Decision 7), and download-audit (Decision 16) invariants all hold. No issue blocks merge. Merge recommendation: **go**.
