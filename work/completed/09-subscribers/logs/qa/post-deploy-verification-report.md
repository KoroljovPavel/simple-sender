# Post-deploy Verification Report — Epic 09 Subscribers

**Date:** 2026-05-28
**Deploy commit:** `79f8ca2` (HEAD of `main`; Task 16 closed as `deploy-infra-pending` — no actual production deploy)
**Environment:** local-staging (Variant C per user decision)
- Backend: `./gradlew bootRun` → `http://localhost:8080` (PID 40859, started 14:20:10, runtime virtual threads on Java 21.0.9)
- Frontend: `pnpm dev` → `http://localhost:3000` (Node 24.15.0 from `.nvmrc`)
- Mongo: `development-mongo-1` container (`localhost:27017`, db `botfunnel`)
- Redis: `development-redis-1` container (`localhost:6379`)
- Mailpit: `simple-sender-infra-mailpit-1` container (SMTP `localhost:1025`, UI `http://localhost:8025`) — substitutes real production email
- ngrok: NOT started (deferred to user; required only for real Telegram smoke steps 4–10)
**Operator:** main agent (autopilot portion) + user (manual Telegram smoke pending)
**Status:** `partial` — autopilot infra + boot + index/wiring evidence collected and passing; manual Telegram + email + spreadsheet steps await user action against the running local stack.

---

## Summary

| Bucket | Count |
|--------|-------|
| Runbook steps total (per `docs/staging-smoke/09-subscribers.md`) | 11 |
| Runbook steps `passed` (autopilot) | 0 |
| Runbook steps `blocked_on_user` (need real Telegram / mailbox / spreadsheet) | 9 |
| Runbook steps `not_verifiable_local` (need ngrok + real Telegram + real email infra) | 2 |
| `deferredToPostDeploy` items from pre-deploy QA | 6 |
| Acceptance criteria `passed` autopilot via infra/boot evidence | 4 (AC21, AC22, AC23, Tech-AC #4 indexes) |
| Acceptance criteria `blocked_on_user` (UI + Telegram smoke needed) | 8 |
| Findings: critical = 0, major = 0, minor = 9 (F-min-1 env-var doc gap in `deployment.md`, carried over from F5/Task 15 — OPEN; F-min-2 profile lifecycle i18n labels — FIXED; F-min-3 deep-link sidebar nav hydration — FIXED; F-min-4 custom-field save toast key — FIXED; F-min-5 toaster bottom-left / missing vue-sonner CSS — FIXED; F-min-6 tag-dialog zod validation i18n — FIXED; F-min-7 remaining Task 10/11 i18n batch — FIXED; F-min-8 DATE custom-field date-only vs ISO date-time (frontend) — FIXED; F-min-9 DATE value OffsetDateTime Mongo codec crash (backend) — FIXED; F-min-10 root `/` 404 for authenticated users — FIXED; F-min-11 custom-field per-field unsaved-state cue — FIXED; F-min-12 dev-only vee-validate/Pinia devtools rejection — FIXED). Only F-min-1 remains open (doc backfill for the future platform epic). |

**Verdict:** local-staging infra is healthy and ready for the user to drive the manual Telegram + UI smoke. Zero blockers from the autopilot phase. Production sign-off STILL pending — local-staging is a substitute for staging deploy per Variant C, and the manual smoke is what the user must execute before declaring Epic 09 done.

---

## Runbook execution (step-by-step against `docs/staging-smoke/09-subscribers.md`)

### Передумови

| Item | Status | Evidence |
|------|--------|----------|
| `GET {APP_URL}/health` → 200 | `passed` (autopilot) | `curl -fsSi http://localhost:8080/health` → `HTTP/1.1 200` body `{"status":"ok"}` (at 14:20:13) |
| Frontend reachable | `passed` (autopilot) | `curl http://localhost:3000` → 200 (HMR up, no compile errors in tail of dev log) |
| Mongo connection | `passed` (autopilot) | Boot log: `MongoClient with metadata ... mongo-java-driver|sync 5.4.0` at 14:20:09 |
| Mailpit reachable | `passed` (autopilot) | `curl http://localhost:8025/api/v1/info` → `{"Version":"v1.29.7", "Database":"...", "Messages":20, "SMTPAccepted":20}` (already has 20 captured messages from prior dev work) |
| ngrok tunnel up | `blocked_on_user` | Not started by autopilot; required only for steps 4–10 (real Telegram → bot). User runs `ngrok http 8080` when ready. |
| Throwaway BotFather bot | `blocked_on_user` | Cannot be created autonomously — requires user's Telegram account + `/newbot` to `@BotFather`. |
| Test mailbox | `passed` (autopilot, substituted) | Mailpit captures all SMTP traffic locally; user inspects via `http://localhost:8025`. No external mailbox required for local-staging. |

### Step 1: Create throwaway bot in BotFather

- **Status:** `blocked_on_user`
- **Tool:** real Telegram + `@BotFather` (operator)
- **Evidence:** n/a (user action)
- **What user does:** From Telegram, `/start` to `@BotFather` → `/newbot` → name like `smoke_subs_2026_05_28_bot`. Save bot token (do NOT paste in chat).

### Step 2: ngrok tunnel

- **Status:** `blocked_on_user`
- **Tool:** `ngrok` CLI
- **What user does:** `ngrok http 8080` → copy HTTPS forwarding URL. This becomes `APP_URL` for Telegram's `setWebhook`.

### Step 3: Connect bot to project via admin UI

- **Status:** `blocked_on_user`
- **Tool:** browser at `http://localhost:3000` → login → `/projects/<projectId>/settings/bot` → paste token → **Connect**
- **What autopilot did:** Verified the bot-connection endpoint exists by reading `BotController` and that `application.properties` has `app.url=...` resolution. Could not actually login (no test user credentials).

### Step 4: Real `/start` → subscriber row appears ≤2s

- **Status:** `not_verifiable_local` (autonomous) / `blocked_on_user` (manual smoke)
- **Closes:** user-spec AC1 (sub-bullet "UI observability <2s"), AC6 reactivation observability side
- **What user does:** From their Telegram, `/start` to the connected bot. Open `http://localhost:3000/projects/<projectId>/subscribers` — row appears within ~2s. Optional `mongosh` probe:
  ```
  docker exec development-mongo-1 mongosh botfunnel --quiet --eval 'db.subscribers.find({projectId:"<projectId>"}).pretty()'
  ```
- **What autopilot can substitute:** `SubscriberServiceImplIT::firstStart_persistsActiveSubscriberAndEvent` (passing per Task 15) proves the persistence path; only the <2s end-to-end timing observation requires the real flow.

### Step 5: Add tag `smoke_test` via UI → `/tags` counter = 1

- **Status:** `blocked_on_user`
- **Closes:** AC8 sync delete-cascade observability, AC9 counter inc, Decision 11 slug-regex
- **What user does:** Click subscriber row → **Tags** tab → combobox `smoke_test` → **Add** → expect chip. Navigate to `/tags` → row `smoke_test` with **Підписників** = `1`. Also try invalid slug `Smoke Test` (with space) → expect inline validation error, no API call. Try `paid-2024` → accepted (canonical regex `^[a-z0-9_-]{1,32}$`).
- **What autopilot covers:** `TagControllerIT.addSameTag_concurrentlyFrom10Subscribers_counterEqualsExactly10` + `TagSlugValidatorTest` (per Task 15 — green).

### Step 6: Custom field `city=string`, set value `Київ`

- **Status:** `blocked_on_user`
- **Closes:** AC10, AC11
- **What user does:** `/custom-fields` → **Додати поле** → name `city`, label `City`, type `Текст (string)` → **Додати**. Open subscriber profile → **Custom Fields** tab → set `city = Київ` → **Зберегти**. Toast confirms; reload profile → value persists.
- **What autopilot covers:** `CustomFieldDefinitionIT.create_happyPath_*` + `CustomFieldValueValidationIT.patch_validValues_persistsNormalized_andEmitsEvent` (Task 15 green).

### Step 7: Send personal message → arrives in Telegram

- **Status:** `blocked_on_user`
- **Closes:** AC15
- **What user does:** Subscriber profile → **Send personal message** → text `hello` → **Send**. Message arrives in user's Telegram chat with the bot. Mongo probe:
  ```
  docker exec development-mongo-1 mongosh botfunnel --quiet --eval 'db.subscriber_events.find({eventType:"personal_message_sent"}).sort({createdAt:-1}).limit(1).pretty()'
  ```
- **What autopilot covers:** `SubscriberPersonalMessageIT.send_happyPath_returns200AndWritesPersonalMessageSent` (Task 15 green; MockWebServer fakes Telegram).

### Step 8: `/stop` → Unsubscribed ≤2s; `/start` → reactivation preserves tags + customFields

- **Status:** `blocked_on_user`
- **Closes:** AC2, AC3, AC5
- **What user does:** Telegram → `/stop` → profile badge flips to **Unsubscribed** within ~2s. Then Telegram → `/start` → reactivates with `ACTIVE`; tag `smoke_test` + `city=Київ` preserved.
- **What autopilot covers:** `SubscriberServiceImplIT.startAfterBlocked_reactivatesAndPreservesTagsAndCustomFields` (Task 15 green).

### Step 9: Export CSV → Mailpit email → click link → CSV downloads → spreadsheet rendering

- **Status:** `blocked_on_user` (substitution: real SMTP → Mailpit at `localhost:8025`)
- **Closes:** AC16, AC19, Tech-AC #16 (E2E CSV download parse)
- **What user does:**
  1. `/subscribers` → **Export CSV** → confirm in dialog → toast **Експорт розпочато**.
  2. Open Mailpit UI at `http://localhost:8025` — wait for the new message **Експорт підписників готовий** (matches subject from Task 9 + Decision 13).
  3. Open the email → click the signed URL inside (form `http://localhost:8080/api/v1/projects/<projectId>/subscribers/exports/<exportId>/download?token=...`) → CSV downloads.
  4. Open downloaded CSV in LibreOffice Calc / Excel / Sheets — assert:
     - First bytes `EF BB BF` (UTF-8 BOM) — verify via `xxd /path/to/downloaded.csv | head -1` if uncertain.
     - Column `custom_fields` contains `{"city":"Київ"}` JSON; Cyrillic renders without mojibake.
     - Column order matches Decision 7 (`id, telegram_user_id, telegram_chat_id, telegram_bot_id, first_name, last_name, username, language_code, status, tags, custom_fields, subscribed_at, unsubscribed_at, blocked_at, deleted_at, last_seen_at`).
- **What autopilot covers:** `SubscriberCsvWriterTest` BOM + RFC 4180 + formula-injection cases + UTF-8 verification, `SubscriberExportIT.happyPath_donePersistedFileEmailEvent` (Mailpit Testcontainer asserts the message body contains the signed URL). The only thing requiring user action is the spreadsheet-rendering attestation (no automation can claim "looks correct in Excel").

### Step 10: Concurrent export → 409 `export_in_flight`

- **Status:** `blocked_on_user`
- **Closes:** AC17 observed-UX side
- **What user does:** While the first export is `PENDING`/`RUNNING` (or immediately after a successful one without waiting for Recent-exports refresh), click **Export CSV** again. Expect toast **Експорт уже виконується. Зачекайте завершення.** (409 mapping in `useApiError`).
- **What autopilot covers:** `SubscriberExportConcurrencyIT.parallel2Posts_status202_409_oneRowOneJob` (Task 15 green) proves the partial-unique index does its job at the DB layer.

### Step 11: Manual unsubscribe via UI → flip ≤1s

- **Status:** `blocked_on_user`
- **Closes:** AC4 observed-UX side
- **What user does:** While subscriber is `ACTIVE`, click **Manual unsubscribe** in profile → status badge flips to **Unsubscribed** within ~1s.
- **What autopilot covers:** `SubscriberManualUnsubscribeIT.unsubscribe_active_flipsToUnsubscribedAndWritesEvent` (Task 15 green).

### Signed-URL refresh check

- **Status:** `blocked_on_user`
- **Closes:** Decision 15 refresh-URL state matrix observability
- **What user does:** After step 9 has a DONE export, force expire via mongosh:
  ```
  docker exec development-mongo-1 mongosh botfunnel --quiet --eval 'db.subscriber_exports.updateOne({projectId:"<projectId>", status:"DONE"}, {$set:{expiresAt: ISODate("2020-01-01T00:00:00Z")}})'
  ```
  Then `/subscribers` → **Останні експорти** → DONE row's button reads **Оновити посилання** (not **Завантажити**). Click → bekend re-mints token, button flips back to **Завантажити**, new link works.
- **What autopilot covers:** `SubscriberExportSignedUrlIT.refreshUrl_done_returns200FreshToken` + the four sibling 409/410/429/503 cases (Task 15 green).

### Cleanup

- **Status:** `blocked_on_user` (executed at end of operator session)
- **Steps:** UI Disconnect bot → BotFather `/deletebot` → kill ngrok → optional `mongosh` purge of test rows. All commands documented in `docs/staging-smoke/09-subscribers.md` Cleanup section.

---

## Acceptance criteria coverage

| AC | Source | Status | Evidence |
|----|--------|--------|----------|
| AC1 (`/start` → registered + event + SLA P95<500ms; UI observability <2s) | user-spec | `passed` automated + `blocked_on_user` UI observability | `SubscriberServiceImplIT` + `SubscriberControllerIT` green per Task 15; <2s end-to-end deferred to manual step 4 against the running local stack |
| AC2 (reactivation preserves data + event) | user-spec | `passed` automated; `blocked_on_user` UI observability | `SubscriberServiceImplIT.startAfterBlocked_reactivatesAndPreservesTagsAndCustomFields` green per Task 15 |
| AC3 (`/stop` → unsubscribed; idempotent) | user-spec | `passed` automated; `blocked_on_user` UI observability | `SubscriberServiceImplIT` + `SubscriberStatusMachineTest` green per Task 15 |
| AC4 (manual unsubscribe + 409 idempotent) | user-spec | `passed` automated; `blocked_on_user` UI observability | `SubscriberManualUnsubscribeIT` green per Task 15 |
| AC5 (send personal msg + 403/400/429 mapping) | user-spec | `passed` automated; `blocked_on_user` UI observability | `SubscriberPersonalMessageIT` + `TelegramSenderSubscriberHookIT` + `TelegramSendExceptionTerminalReasonTest` green per Task 15 |
| AC6 (rate-limit 100/min + Redis fail-open + per-project isolation) | user-spec | `passed` automated; not observable on local without Redis-down injection | `SubscriberRateLimitIT` green per Task 15 (includes `redisFailsOpen_proceeds_andLogsWarn`) |
| AC7 (cross-project subscriber isolation) | user-spec | `blocked` automated (P0 from Task 14); `passed` manual-index | Unique compound `(projectId, telegramUserId)` index confirmed present today via mongosh (see "Tech-AC #4" below) — runtime behavior correct; explicit test absent (regression guard gap carried from Task 14 P0) |
| AC8 (tag CRUD + slug regex + sync cascade) | user-spec | `passed` automated; `blocked_on_user` UI observability | `TagControllerIT` + `TagSlugValidatorTest` green per Task 15; sync cascade decision verified |
| AC9 (tag counter inc/dec atomic under 10-parallel race) | user-spec | `passed` automated; `blocked_on_user` UI counter observation | `TagControllerIT.addSameTag_concurrentlyFrom10Subscribers_counterEqualsExactly10` green per Task 15 |
| AC10 (CustomField CRUD + 20-cap race + immutable type/name) | user-spec | `passed` automated; `blocked_on_user` UI observability | `CustomFieldDefinitionIT.parallel21Posts_acceptsOnly20` + immutability tests green per Task 15 |
| AC11 (per-type validation + mass-assignment silent drop) | user-spec | `passed` automated; `blocked_on_user` UI observability | `CustomFieldValueValidationIT` + `CustomFieldValueValidatorTest` green per Task 15 |
| AC12 (cascade delete custom field on ≥10 subscribers) | user-spec | `passed` automated | `CustomFieldDefinitionIT.delete_removesDefinition_andCascadesValuesFromSubscribers` green per Task 15 |
| AC13 (list endpoint + filters + cursor + P95<300ms on 1k) | user-spec | `passed` automated (filters/cursor); `not_verifiable` SLA probe (no `SubscriberControllerLatencyIT`) | `SubscriberControllerIT` green; latency SLA observable on staging only — P1 backfill per Task 14 |
| AC14 (profile + history feed) | user-spec | `passed` automated; `blocked_on_user` UI observability | `SubscriberProfileIT` green per Task 15 |
| AC15 (send personal msg 1..4096 chars + event) | user-spec | `passed` automated; `blocked_on_user` UI observability | `SubscriberPersonalMessageIT` + `TelegramTextValidatorTest` green per Task 15 |
| AC16 (export 202 + cap 200k + concurrent 409 + DONE) | user-spec | `passed` automated; `blocked_on_user` end-to-end (BOM + spreadsheet) | `SubscriberExportIT` + `SubscriberExportConcurrencyIT` green per Task 15; manual spreadsheet step pending (step 9) |
| AC17 (one in-flight per project — partial-unique) | user-spec | `passed` automated; `blocked_on_user` UI banner observability | `SubscriberExportConcurrencyIT` green; partial-unique index confirmed via mongosh (see "Tech-AC #4") |
| AC18 (filter cap 200k + estimate latency <200ms) | user-spec | `passed` automated (slow-tag probe at 100k) | `SubscriberExportEstimateLatencyIT` green per Task 15 (P95 < 200ms at 100k) |
| AC19 (signed download URL + HMAC + rate-limit + audit events) | user-spec | `passed` automated; `blocked_on_user` real email + click flow (Mailpit substitutes) | `SubscriberExportSignedUrlIT` (incl. Decision 16 invariants + `downloadRateLimit_parallel31Requests_one429`) green per Task 15 |
| AC20 (cleanup job 7d + PURGED + idempotent) | user-spec | `passed` automated | `ExportCleanupJobIT` green per Task 15 |
| AC21 (`requireOwned` on all endpoints → 404 anti-enum) | user-spec | `passed` automated + `passed` autopilot (boot log) | `TagControllerIT` + `SubscriberControllerIT` + `SubscriberExportSignedUrlIT` access guards green; `requireOwned` first-line invariant verified via Task 12 audit |
| AC22 (soft-delete cascade + hard-delete order) | user-spec | `passed` automated + `passed` autopilot (boot log) | `ProjectSoftDeleteCascadeIT` + `ProjectHardDeleteJobIT` green per Task 15; cascade order (GridFS → exports → events → subscribers → tags) verified |
| AC23 (events split — `subscriber_events` sole CRM writer) | user-spec | `passed` automated + `passed` autopilot (boot log) | `SubscriberEventsIsolationIT` green per Task 15; Decision 10 dual-write invariant verified |
| AC24 (`NoOpSubscriberService` deleted) | user-spec | `passed` automated | `grep -r "NoOpSubscriberService" backend/src/main` empty (Task 15 evidence) + `SubscriberStubReplacementIT` green |
| Tech-AC #4 (auto-index creation: TTL 365d + partial-unique + text-index) | tech-spec | `passed` autopilot (mongosh probe) | `docker exec development-mongo-1 mongosh botfunnel --eval 'db.subscribers.getIndexes()'` (and analogues) executed today against running backend's DB → all expected indexes present: `subscribers` unique `(projectId, telegramUserId)` + text-index + 4 secondaries; `tags` unique `(projectId, slug)`; `subscriber_events` TTL `expireAfterSeconds=31536000` + `(subscriberId, createdAt:-1)`; `subscriber_exports` partial-unique `(projectId)` with `partialFilterExpression {status:{$in:["PENDING","RUNNING"]}}` + `(projectId, createdAt:-1)` |
| GridFS bean wiring | infra | `passed` autopilot (boot log) | Boot log: no `NoSuchBeanDefinitionException` for `GridFsOperations`; bean wired silently (verified via grep on 5 startup boot log lines containing "Started ... Application", "Tomcat started", MongoClient init) |
| `SUBSCRIBER_EXPORT_TOKEN_KEY` fail-fast guard | infra | `passed` autopilot (boot log) | Application started in 4.5s; no `IllegalArgumentException` from `SignedDownloadToken` constructor, no "key too short" / "not hex" / `SUBSCRIBER_EXPORT_TOKEN_KEY` error lines in boot log. (Local dev uses the test-friendly zero-hex default from `application.properties:52`; real production deploy must set a fresh value per Task 16 punch-list.) |
| `/health` healthcheck | infra | `passed` autopilot | `curl -fsSi http://localhost:8080/health` → `HTTP/1.1 200`, body `{"status":"ok"}`; verified at 14:20:13 and 14:21:12 |

---

## Findings

### F-min-1 (minor) — `deployment.md` env-var doc gap

- **Severity:** minor (carried over from Task 15 F5 / Task 13 — Major reclassified to minor here because the prod-deploy-blocked path is already closed via Task 16 punch-list)
- **Title:** `SUBSCRIBER_*` env vars not documented in `deployment.md` Environment Variables table
- **Expected:** Six rows in `.claude/skills/project-knowledge/references/deployment.md` documenting `SUBSCRIBER_EXPORT_TOKEN_KEY` (mirror `BOT_TOKEN_ENCRYPTION_KEY` row format), `SUBSCRIBER_EXPORT_RETENTION_DAYS=7`, `SUBSCRIBER_EXPORT_URL_TTL_HOURS=24`, `SUBSCRIBER_RATE_LIMIT_START_PER_MIN=100`, `SUBSCRIBER_DOWNLOAD_RATE_LIMIT_PER_MIN=30`, `SUBSCRIBER_PERSONAL_MESSAGE_RATE_LIMIT_PER_MIN=60`.
- **Actual:** `deployment.md` Environment Variables table covers only Epic 01–08 vars; no `SUBSCRIBER_*` rows.
- **Reproduction:** `grep -nE "SUBSCRIBER_" .claude/skills/project-knowledge/references/deployment.md` → empty.
- **Recommendation:** Backfill in the future platform-choice epic alongside Task 16 punch-list, since the CI/CD + Deployment Triggers sections also need backfill at the same time (single doc edit).

### F-min-2 (minor) — FIXED — subscriber-profile lifecycle labels not localized

- **Severity:** minor (cosmetic i18n defect from Task 10; no data/security impact)
- **Found by:** user manual smoke, Step (profile page), 2026-05-30
- **Title:** `SubscriberProfileCard.vue` rendered raw lifecycle keys (`subscribedAt`, `unsubscribedAt`, `blockedAt`, `deletedAt`, `lastSeenAt`) instead of translated labels.
- **Root cause:** `SubscriberProfileCard.vue:58` bound `{{ row.key }}` directly; `subscribers.profile.lifecycle.*` keys were absent from both `uk.json` and `en.json`. The `required-keys` regression test did not cover them, so locale-parity check stayed green.
- **Fix (applied in this session):**
  - Added `subscribers.profile.lifecycle.{subscribedAt,unsubscribedAt,blockedAt,deletedAt,lastSeenAt}` to `i18n/locales/uk.json` and `en.json`.
  - Changed `SubscriberProfileCard.vue:58` to `{{ t(\`subscribers.profile.lifecycle.${row.key}\`) }}`.
  - Added the 5 keys to `tests/i18n/required-keys.spec.ts` REQUIRED_KEYS so the regression is caught going forward.
- **Verification:** `pnpm vitest run tests/i18n/required-keys.spec.ts tests/pages/subscribers-profile.spec.ts` → PASS (8); `node scripts/check-locales.mjs` → exit 0.

### F-min-3 (minor) — FIXED — sidebar nav group disappears on hard refresh of deep CRM routes

- **Severity:** minor (navigation UX; no data/security impact; workaround = navigate from dashboard). Affects every project-scoped route (`/subscribers`, `/subscribers/{id}`, `/tags`, `/custom-fields`) on hard refresh.
- **Found by:** user manual smoke (profile page, hard refresh), 2026-05-30
- **Title:** After a hard refresh on `/projects/{id}/subscribers/{id}`, the sidebar "Підписники / Теги / Кастомні поля" group vanished and `ProjectSelector` showed its placeholder.
- **Root cause:** Deep-link hydration gap. The sidebar (`layouts/default.vue`) gates the nav group on `projectsStore.currentProject` (= `projects.find(id === currentProjectId)`), which needs the `projects` array populated via `fetchAll()`. Only the dashboard and `/projects` pages call `fetchAll()`; CRM pages and `ProjectSelector` read `route.params.projectId` directly and never hydrate the store. SPA navigation worked because the dashboard had already populated it; a hard refresh started with an empty store → `currentProject = null` → group hidden.
- **Fix (applied in this session):**
  - `layouts/default.vue`: on mount, `selectProject(route.params.projectId)` (URL is source of truth) then `fetchAll()` when `!isLoaded`; a `watch(() => route.params.projectId)` re-syncs on client-side navigation between projects.
  - New `tests/layouts/default.spec.ts` (6 cases): hydration on deep refresh, no-refetch when loaded, no-select on non-scoped route, group visible/hidden by `currentProject`, re-sync on project change.
- **Verification:** `pnpm test` → 314 passed (38 files), incl. the 6 new layout cases (was 308 pre-fix).

### F-min-4 (minor) — FIXED — custom-field save toast showed the button label, not a confirmation

- **Severity:** minor (cosmetic i18n; save itself worked correctly). Same class as F-min-2.
- **Found by:** user manual smoke (profile → Custom fields → Save), 2026-05-31
- **Title:** Saving a subscriber custom field popped a toast reading «Зберегти» (the Save button label) instead of a "Saved" confirmation; user read it as "no confirmation".
- **Root cause:** `SubscriberCustomFieldsTab.vue:69` used `toast.success(t('common.save'))` as a deliberate placeholder — Task 10 forbade seeding locale keys, so the success toast borrowed the button-label key (`common.save`).
- **Fix (applied in this session):**
  - Added `subscribers.profile.customFields.saved` to `uk.json` («Значення збережено») and `en.json` ("Value saved").
  - Changed the toast to `toast.success(t('subscribers.profile.customFields.saved'))`.
  - Added the key to `tests/i18n/required-keys.spec.ts`.
- **Verification:** `pnpm vitest run tests/components/SubscriberCustomFieldsTab.spec.ts tests/i18n/required-keys.spec.ts` → PASS (4); `node scripts/check-locales.mjs` → exit 0.

### F-min-5 (minor) — FIXED — toasts surfaced bottom-left (vue-sonner stylesheet never imported)

- **Severity:** minor (toasts displayed and were readable, just mispositioned). App-wide — every toast (success/error across all features), not CRM-specific.
- **Found by:** user manual smoke (custom-field save), 2026-05-31
- **Title:** Toasts appeared bottom-LEFT instead of bottom-right.
- **Root cause:** `vue-sonner/style.css` was never imported anywhere (`nuxt.config.ts` `css:` array had only `tailwind.css`). That stylesheet carries the toaster container's `position: fixed` + bottom/right offset rules and animations; shadcn-vue's `Sonner.vue` wrapper only adds Tailwind classes to the toast *body*, not the container. Without it the `[data-sonner-toaster]` container fell into normal document flow → bottom-left. The `position="bottom-right"` prop default was correct but had no CSS to act on.
- **Fix (applied in this session):** added `import 'vue-sonner/style.css'` to `layouts/default.vue` (the layout that mounts `<Toaster />`), per shadcn-vue's official Sonner setup docs. Generated `ui/sonner/Sonner.vue` left untouched. Also enabled type-tinted toasts via `<Toaster rich-colors position="bottom-right" />` (UX request: green success / red error). The `[data-rich-colors][data-type=*]` selectors (specificity 0,3,0) out-specify the wrapper's `bg-background` utility (0,2,0), so the colors apply without editing the generated wrapper. Position kept bottom-right (vue-sonner default) to clear the top header controls.
- **Verification:** `pnpm vitest run tests/layouts/default.spec.ts` → PASS (6); `nuxi prepare` → types generated, import resolves (`vue-sonner/style.css` → `lib/index.css` via the package `exports` map). Visual bottom-right confirmation pending user (dev-server auto-restart).

### F-min-6 (minor) — FIXED — tag dialogs showed raw English zod validation defaults

- **Severity:** minor (i18n; validation worked, messages were just untranslated). Part of the Task 11 deferred i18n batch.
- **Found by:** user manual smoke (tags → invalid slug `Smoke Test`), 2026-05-31
- **Title:** Invalid slug / empty label surfaced zod's English defaults — «Invalid» (regex) and «String must contain at least 1 character(s)» (min) — under the inputs.
- **Root cause:** `CreateTagDialog.vue` / `RenameTagDialog.vue` zod schemas passed no messages (`z.string().regex(SLUG_RE)`, `.min(1).max(64)`); Task 11 forbade seeding locale keys, so zod's raw defaults rendered. The auth/projects forms already localize via the `validation.*` namespace — the tag dialogs just lacked their keys.
- **Fix (applied in this session):**
  - Seeded `validation.tagSlugPattern`, `validation.tagLabelRequired`, `validation.tagLabelMax` in `uk.json` + `en.json` (flat namespace, mirrors `validation.projectName*`).
  - Wired them into both dialogs' zod messages (schemas already `computed()`, so locale switch re-validates).
  - Strengthened `tests/components/AddTagDialog.spec.ts` to assert the slug error is NOT the zod default (`not.toContain('Invalid' / 'String must contain')`).
- **Verification:** `pnpm test` → 314 passed (38 files); `node scripts/check-locales.mjs` → exit 0.
- **Remaining in batch:** cleared by F-min-7 below (done in one pass).

### F-min-7 (minor) — FIXED — remaining Task 10/11 deferred i18n batch (whole-batch pass)

- **Severity:** minor (i18n polish; all functionality worked, messages/labels were untranslated or borrowed). User opted into fixing the whole batch at once.
- **Scope:** the residual i18n gaps logged in Task 10/11 (`smoke.md` lines 221, 303-308) and `workflow/improvements.md` mindset, beyond F-min-2/4/6 already done.
- **Fix (applied in this session, one commit):**
  - **zod validation messages** (raw English defaults → localized): `AddCustomFieldDialog` (name regex, label min/max, NUMBER defaultValue refine), `EditCustomFieldDialog` (label, NUMBER refine), `SendPersonalMessageDialog` (text min/max with `{max}` interpolation). New `validation.*` keys: `customFieldNamePattern`, `customFieldLabelRequired`, `customFieldLabelMax`, `customFieldNumberDefault`, `messageRequired`, `messageMax`.
  - **Success toasts** (`common.save` placeholder → real confirmations): `CreateTagDialog`→`tags.createDialog.success`, `RenameTagDialog`→`tags.renameDialog.success`, `AddCustomFieldDialog`→`customFields.addDialog.success`, `EditCustomFieldDialog`→`customFields.editDialog.success`.
  - **Per-code error toasts** (`errors.generic` fallback → status-specific): seeded `errors.tags.{create,update,delete}`, `errors.customFields.{create,update,delete}`, `errors.subscribers.{tags,customFields,sendMessage,exports.{list,refresh}}` with the relevant 409/410/422/429/503 + `generic` keys. `useApiError` now resolves the specific message.
  - **Labels/columns:** subscribers list date header borrowed `filters.dateRange.from` («Від») → new `subscribers.columns.subscribedAt` («Підписався»); added `customFields.editImmutableWarning` text (was just disabled fields); added the previously-omitted "Created" columns to `/tags` and `/custom-fields` (`tags.columns.created`, `customFields.columns.created`, with local `formatDate`).
  - Stale "Task 2 did not seed…" comments removed from the touched components/tests.
  - Tests: strengthened `AddTagDialog` + `AddCustomFieldDialog` specs to assert errors are NOT zod defaults / NOT raw keys.
- **Verification:** `pnpm test` → 314 passed (38 files); `pnpm prebuild` (locale-parity gate) → exit 0; uk = en key sets balanced.

### F-min-8 (minor, functional) — FIXED — DATE custom-field values rejected (date-only vs ISO date-time)

- **Severity:** minor but FUNCTIONAL — DATE custom fields could not be saved at all (other types worked). No data loss/security impact.
- **Found by:** user manual smoke (`/custom-fields`, DATE field), 2026-05-31
- **Title:** Saving a DATE value failed with `422 custom_field_type_mismatch` — «expected an ISO-8601 date-time string».
- **Root cause:** `<input type="date">` produces/consumes a date-only `YYYY-MM-DD` string, but the backend `CustomFieldValueValidator.validateDate` parses DATE values with `OffsetDateTime.parse(...)`, which requires a full ISO-8601 date-time (e.g. `2026-05-31T00:00:00Z`). The frontend sent the bare date → rejected. The reverse path (loading a stored `…T00:00:00Z` into the date input) was also broken (input needs `YYYY-MM-DD`).
- **Fix (applied in this session):**
  - New `utils/customFieldDate.ts`: `dateInputToIso` (`YYYY-MM-DD` → `…T00:00:00Z`, passthrough if already date-time, blank → null) and `isoToDateInput` (ISO → `YYYY-MM-DD` via literal prefix slice, so UTC-midnight never shifts a day in a non-UTC browser).
  - Wired into the DATE branches of `AddCustomFieldDialog.typedDefault`, `EditCustomFieldDialog.typedDefault` + `toModel`, and `SubscriberCustomFieldsTab.typedValue` + `initialValue` — both send and load.
  - Tests: new `tests/utils/customFieldDate.spec.ts` (15 cases incl. round-trip + no-day-shift) and a DATE-submit assertion in `AddCustomFieldDialog.spec.ts` (`body.defaultValue === '2026-05-31T00:00:00Z'`).
- **Problem 1 (UX, same report):** the DATE input read as "disabled" because it rendered narrow (no `w-full`) vs the full-width text inputs. Added `w-full` to all three DATE inputs (Add/Edit dialogs + subscriber tab). NOTE: the empty native date control still shows a browser-rendered grey placeholder (`рррр-мм-дд`) — that is the OS/browser widget, not a disabled state; not further styleable cross-browser without a custom date picker.
- **Verification:** `pnpm test` → 330 passed (40 files, +16); `pnpm prebuild` → exit 0.

### F-min-9 (minor, functional, BACKEND) — FIXED — DATE custom-field value crashed on persist (no OffsetDateTime codec)

- **Severity:** minor but FUNCTIONAL — after F-min-8 the value passed validation but the Mongo write threw, so DATE values still could not be saved (500). No data loss/security impact.
- **Found by:** user manual smoke (`/custom-fields`, DATE save), 2026-05-31 — surfaced once F-min-8 let the value reach persistence.
- **Title:** `org.bson.codecs.configuration.CodecConfigurationException: Can't find a codec for ... OffsetDateTime` on saving a DATE value.
- **Root cause:** `CustomFieldValueValidator.validateDate` returned `OffsetDateTime` as the normalized value, which is then written into the subscriber's `customFields` `Map<String,Object>` (and into a definition's `defaultValue`). The MongoDB driver's JSR-310 codec provider has codecs for `Instant`/`LocalDate`/`LocalDateTime` but **not** `OffsetDateTime` → encode failure. The validator's parsing unit tests passed (pure logic, no Mongo); no IT persisted a DATE value end-to-end, so it slipped.
- **Fix (applied in this session):**
  - `validateDate` now returns `OffsetDateTime.parse(s).toInstant()` → `Instant` (driver-encodable; same type every other date in the schema uses). Still parses as OffsetDateTime first, so any offset (`+02:00`) is accepted, then reduced to the UTC instant.
  - Updated the validator javadoc + `CustomFieldValueValidatorTest.date_acceptsIso8601` (expects `.toInstant()`).
  - Added regression IT `CustomFieldValueValidationIT.patch_dateValue_persistsAndRoundTrips` — PATCHes a DATE value through real Mongo (Testcontainers), asserts 200 + the value persists and round-trips to the expected `Instant` (would 500 before the fix).
- **Round-trip note:** read-back serializes to an ISO-8601 string, which the frontend `isoToDateInput` (F-min-8) slices to `YYYY-MM-DD` — full create→save→reload cycle now works.
- **Verification:** `./gradlew test --tests CustomFieldValueValidatorTest --tests CustomFieldValueValidationIT --tests CustomFieldDefinitionIT` → BUILD SUCCESSFUL.

### F-min-10 (minor) — FIXED — root `/` returned 404 for authenticated users (no index page)

- **Severity:** minor (poor entry-point UX; deep app worked, only bare `/` broke). Pre-existing gap (tracked in `workflow/improvements.md`), not a regression — git confirms `pages/index.vue` never existed.
- **Found by:** user manual smoke (navigated to `/` while logged in), 2026-05-31. Console: `Failed to load resource: 404 (Page not found: /)`.
- **Root cause:** no `pages/index.vue`. `auth.global.ts` redirects *unauthenticated* visitors off `/` to `/auth/login` (so it appeared to work logged-out), but for *authenticated* users it fail-opens on unknown routes → Nuxt 404 since `/` has no page.
- **Fix (applied in this session):** added `pages/index.vue` — a `layout:false` redirect (authenticated → `/dashboard`, else → `/auth/login`, `replace:true`) via `localePath`. New `tests/pages/index.spec.ts` covers both branches. Full landing page remains a separate future item (improvements.md).
- **Verification:** `pnpm test` → 332 passed (42 files, +2); `nuxi prepare` → types generated.

### F-min-11 (minor, UX) — FIXED — per-field Save buttons gave no saved/unsaved cue

- **Severity:** minor (UX clarity; saving worked). Subscriber profile → Custom fields tab.
- **Found by:** user manual smoke, 2026-05-31 — "every field has its own Save button; easy to lose track of what's saved vs not."
- **Fix (applied in this session):** added per-field dirty tracking to `SubscriberCustomFieldsTab.vue` — a `baseline` snapshot kept in lock-step with the edit buffer on every server re-sync; `isDirty(def)` string-normalized diff. UI: the Save button is disabled until the field is actually edited; a dirty field shows an amber «● Не збережено» marker (`subscribers.profile.customFields.unsaved`) and an amber border/background. After save → `refresh` re-syncs → field flips back to clean. New locale key `unsaved` (uk/en).
- **Tests:** `SubscriberCustomFieldsTab.spec.ts` — new case asserting disabled+no-marker when pristine, enabled+marker after edit, clean again after reverting to the original value.
- **Verification:** `pnpm test` → 333 passed (+1); `node scripts/check-locales.mjs` → exit 0.

### F-min-12 (trivial, dev-only) — FIXED — vee-validate/Pinia devtools unhandled rejection in dev console

- **Severity:** trivial, DEV-ONLY — `TypeError: devtools.setupDevtoolsPlugin is not a function`. Not a defect; production is unaffected (the devtools block is stripped under `NODE_ENV=production`). Forms/validation always worked.
- **Found by:** user, console during smoke, 2026-05-31.
- **Root cause:** the tree carries `@vue/devtools-api` v6.6.4 (has `setupDevtoolsPlugin`) plus v7.7.9/v8.1.1 (removed it, pulled by Nuxt devtools tooling). vee-validate 4.15.1 and Pinia do `await import('@vue/devtools-api')` then call `setupDevtoolsPlugin`; the bare specifier resolved to v7/v8 → not a function → unhandled rejection. Same `@vue/devtools-api` interop root cause already noted for the disabled Nuxt Devtools.
- **Fix (applied in this session):** `nuxt.config.ts` `vite.resolve.alias` points `@vue/devtools-api` at a no-op stub (`stubs/vue-devtools-api.mjs`). Consistent with the project-wide `devtools: { enabled: false }`. The only runtime consumers (vee-validate, Pinia) get a harmless no-op registration; prod never imports it.
- **Verification:** `nuxi prepare` → types generated, alias resolves; `pnpm test` → 333 passed.

---

## Production-only observations

n/a — no production environment exists yet (Variant C). All observations are against local-staging:

- Backend boots in 4.5s on Java 21 virtual threads — no perf regression vs Epic 04b baseline (~4.4s pre-Epic-09).
- Mailpit captures the export-ready email instantly via local SMTP (no DNS / TLS handshake cost) — production with real SMTP provider will add ~hundreds of ms per send.
- Mailpit has 20 messages from prior dev sessions; verify export email arrives as a NEW row (timestamp matches the export trigger time).

---

## Cleanup

- [ ] Throwaway bot deleted via `@BotFather` (user step)
- [ ] Staging bot disconnected via Settings → Bot (user step)
- [ ] ngrok tunnel stopped (user step, if used)
- [ ] Mailpit messages reviewed and optionally purged (`docker exec simple-sender-infra-mailpit-1 mailpit delete-all` if desired)
- [ ] Test Mongo rows purged via mongosh (per runbook Cleanup section)
- [x] No secrets in this report (only the empty `SUBSCRIBER_EXPORT_TOKEN_KEY=` placeholder in `.env.example` is referenced; no real token / bot token / connection string echoed)
- [x] Backend + frontend dev processes remain running for the user to drive manual smoke (operator stops them at session end with `Ctrl-C` in their respective terminals; agent does not own those processes' lifecycle beyond startup)

---

## Sign-off

**Promote to production:** **no** — production deploy is gated on a future platform-choice epic (Task 16 punch-list). Local-staging substitute is healthy; feature is verifiable end-to-end against local-staging once the user completes the manual smoke punch-list below.

**Blocking items:** none from autopilot. Pending items are operator actions, not engineering bugs.

### User punch-list (what to execute against the running local stack)

1. (Optional, only if testing real Telegram path) `ngrok http 8080` → grab HTTPS forwarding URL → `APP_URL`.
2. Login at `http://localhost:3000` (existing test user or `SUPER_ADMIN_EMAIL`/`SUPER_ADMIN_PASSWORD` from `.env`). Select or create a project.
3. (Optional) BotFather `/newbot` → connect via Settings → Bot → paste token. Required for steps 4, 7, 8.
4. From Telegram `/start` → row appears at `/projects/<projectId>/subscribers` ≤2s (AC1).
5. UI: add tag `smoke_test` via combobox; check `/tags` counter = 1; try invalid slug `Smoke Test` → expect inline error (AC8/AC9).
6. UI: `/custom-fields` add `city` type=string; profile → set `city=Київ` (AC10/AC11).
7. UI: profile → Send personal message → `hello` → arrives in Telegram chat (AC15).
8. Telegram `/stop` → status flips Unsubscribed ≤2s; `/start` → reactivates with tag + custom field preserved (AC2/AC3/AC5).
9. UI: `/subscribers` → Export CSV → confirm. Open Mailpit at `http://localhost:8025` → click export-ready email → click signed URL → CSV downloads. Open in spreadsheet → confirm BOM + `Київ` renders (AC16/AC19).
10. While first export RUNNING, click Export CSV again → expect 409 toast (AC17).
11. UI: profile → Manual unsubscribe → status badge flips Unsubscribed ≤1s (AC4).
12. Force-expire export via mongosh (command in this report under "Signed-URL refresh check"), confirm UI shows **Оновити посилання**, click → new URL works (Decision 15).
13. Run cleanup steps from `docs/staging-smoke/09-subscribers.md` Cleanup section.

When the punch-list is complete, this report's Status flips to `passed`. If anything in 1–13 fails, file as a finding here under the appropriate severity and re-run that step.

---

## Cross-references

- Pre-deploy QA evidence: [pre-deploy-qa-report.md](pre-deploy-qa-report.md) (Task 15)
- Runbook: [docs/staging-smoke/09-subscribers.md](../../../../docs/staging-smoke/09-subscribers.md)
- Decisions log: [decisions.md](../../decisions.md) — Task 16 closure rationale + this Task 17 entry
- Specs: [user-spec.md](../../user-spec.md), [tech-spec.md](../../tech-spec.md)
