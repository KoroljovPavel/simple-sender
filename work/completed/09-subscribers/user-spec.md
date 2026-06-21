---
# Creation date (YYYY-MM-DD)
created: 2026-05-24

# Status: draft | approved
status: approved

# Work type: feature | bug | refactoring
type: feature

# Feature size: S (1-3 files, local fix) | M (several components) | L (new architecture)
size: L
---

# User Spec: 09-subscribers

## Что делаем

Реальный CRM-модуль `subscriber/` (Epic 05) — заменяем `NoOpSubscriberService` (живёт в проекте после Epic 04b как заглушка, которую вызывает `ProcessTelegramUpdateJob`) на полноценный `SubscriberServiceImpl`. Покрываем: auto-registration подписчиков из webhook'а, status state-machine (`active | unsubscribed | blocked | deleted` с reactivation на новое `/start`), отдельную коллекцию `tags` с on-demand counters, embedded `customFieldDefinitions[]` в `Project` (max 20 schema-полей), отдельную append-only коллекцию `subscriber_events` (TTL 365 дней) для истории. Добавляем segment-filter (status + tags include/exclude + subscribedAt range), асинхронный CSV-export через JobRunr → MongoDB GridFS → email + signed-URL download, расширяем `TelegramSender` хуком на 403/400 для авто-перевода в blocked/deleted, ставим Redis-bucket rate-limit 100 нов. `/start`/мин на проект. UI: четыре новых страницы (`/subscribers` список + `/subscribers/{id}` профиль + `/tags` + `/custom-fields`) с пагинацией cursor-based "Load more". Замыкаем stub-интерфейс `SubscriberService` из 04b и готовим почву для Epic 06 (Funnels — читают subscribers + tags) и Epic 07 (Broadcasts — segment audience).

## Зачем

Без 09-subscribers вся CRM-цепочка MVP не работает: webhook (04b) ловит `/start`/`/stop`, но `NoOpSubscriberService` тихо игнорирует — пользователь подключил бота, видит "send test message" работает, но в `/subscribers` пусто, нет кому слать broadcast'ы (Epic 07), нет кого триггерить воронкой (Epic 06). project.md в Key Features таблице маркирует Epic 05 как Critical и явно фиксирует: "Subscriber auto-registration, tags, custom fields, segments". Это первая видимая для пользователя CRM-функциональность после auth + projects + bot connection — без неё MVP-демо "за 10 минут запустить funnel" невозможен (project.md MVP goal). Дополнительно: workflow ставит ограничение "1k+ subscribers, 100+ broadcast recipients" — этот эпик должен выдержать такие объёмы без рефакторинга на старте.

## Как должно работать

**Сценарій 1 — новий підписник пише `/start`:**
1. Telegram POST'ить update на `/webhooks/telegram/{projectId}` (Epic 04b, без змін). Воркер `ProcessTelegramUpdateJob` розпізнає `/start` у private chat.
2. Перед upsert'ом воркер дзвонить `SubscriberServiceImpl.upsertFromTelegramUpdate(...)`. Імпл спочатку перевіряє rate-limit: якщо subscriber з цим `telegramUserId` уже існує — пропускає лімітування (rate-limit лише для НОВИХ /start); якщо новий — інкрементить Redis bucket за projectId з TTL 60s. Result > 100 → event `rate_limit_exceeded` і тихо повертається БЕЗ створення Subscriber'а.
3. Якщо rate-limit OK (або existing subscriber): upsert на subscribers collection по унікальному ключу (projectId, telegramUserId):
   - Не існує → створення з `status=active`, порожніми tags/customFields, `subscribedAt=now`, `lastSeenAt=now`, identity fields з update.
   - Існує, `status != active` → перехід у `active`, `subscribedAt` НЕ перезаписуємо, `lastSeenAt=now`, identity refresh + event `subscriber_reactivated`.
   - Існує, `status == active` → лише `lastSeenAt=now` + identity refresh (без події).
4. На створення — event `subscriber_registered` записується у `subscriber_events`.
5. Через ≤2 секунди (background worker latency) запис видно у `/subscribers` UI у власника проекту.

**Сценарий 2 — подписчик пишет `/stop`:**
1. Воркер 04b вызывает `SubscriberServiceImpl.markUnsubscribed(projectId, telegramBotId, chatId)`.
2. Lookup по `(projectId, telegramBotId, telegramChatId)` → если нашёлся И `status != unsubscribed`: UPDATE `status=unsubscribed`, `unsubscribedAt=now` + event `subscriber_unsubscribed{reason:"command_stop"}`.
3. Если уже `unsubscribed` — idempotent (без записи дубль-события).
4. На последующее `/start` от того же subscriber'а → AC2 reactivation: `status=active`, теги и custom fields сохранены (workflow line 76).

**Сценарій 3 — власник заходить на `/subscribers`:**
1. Frontend Nuxt-сторінка викликає `GET /api/v1/projects/{projectId}/subscribers` з query-параметрами для search/filter/sort/cursor.
2. Backend: ProjectService.requireOwned → 404 на foreign owner / soft-deleted / malformed.
3. Query builds: filter за status + tags include/exclude + subscribedAt range + full-text search (за необхідності); cursor-based slicing + sort newest first.
4. Повертає `{ items: [...50], nextCursor: string | null }`. P95 <300ms на dataset 1k subscribers.
5. UI: таблиця + filters bar (Status Select, Tags Include/Exclude combo, Date range) + кнопка "Load more" (cursor-based, infinite). Empty state: "No subscribers yet. They appear when someone messages your bot."

**Сценарий 4 — владелец открывает профиль:**
1. Клик на строку → роут `/subscribers/{subscriberId}` (project-scoped Nuxt-страница).
2. Frontend параллельно дёргает `GET /api/v1/projects/{projectId}/subscribers/{subscriberId}` (returns identity + tags + customFields + status + dates) и `GET /api/v1/projects/{projectId}/subscribers/{subscriberId}/events?limit=50` (returns latest history feed).
3. UI: identity card (name, username, status badge, IDs, dates, action buttons "Manual unsubscribe" / "Send personal message") + Tabs (Tags chips with Add/Remove combo, Custom fields list with per-row inline edit, History feed chronological).

**Сценарій 5 — власник додає тег:**
1. У профілі клік "Add tag" → autocomplete-combo з існуючими project-tags + опція "Create '{input}'".
2. POST `/api/v1/projects/{projectId}/subscribers/{subscriberId}/tags` body `{slug: "vip"}`.
3. Backend: TagService find-or-create (validate slug regex; retry-read на race); idempotent add to Subscriber.tags; на actual add — інкремент `Tag.subscriberCount`.
4. Events: `tag_created` (якщо новий) + `subscriber_tag_added`.
5. Tag counter на `/tags` бачимо одразу (denormalized counter).

**Сценарій 6 — власник створює custom field schema:**
1. На `/custom-fields` клік "Add field" → Dialog (name slug + label + type Select string|number|boolean|date + optional default).
2. POST `/api/v1/projects/{projectId}/custom-fields` body `{name, label, type, defaultValue?}`.
3. Backend: validate slug regex; check current count < 20 → 422 `custom_field_limit_reached`; check name unique → 409 `custom_field_name_taken`; append у embed-array `Project.customFieldDefinitions`.
4. Event `custom_field_definition_created`.
5. На subscriber-профілі у tab'і "Custom fields" з'являється новий рядок з порожнім значенням (або defaultValue, якщо задано).

**Сценарій 7 — set value для custom field:**
1. На subscriber-профілі inline-edit field `city` зі значенням "Київ".
2. PATCH `/api/v1/projects/{projectId}/subscribers/{subscriberId}/custom-fields` body `{city: "Київ"}`.
3. Backend: перевірка ключа `city` в schema (інакше silent drop — mass-assignment protection); per-type validation (string trim+max 1024; number як finite double; boolean true/false/yes/no; date ISO-8601) → 422 `custom_field_type_mismatch` на fail.
4. Запис у `Subscriber.customFields.city` + event `subscriber_custom_field_set`.

**Сценарій 8 — send personal message:**
1. На profile клік "Send personal message" → Dialog з textarea (char counter n/4096).
2. POST `/api/v1/projects/{projectId}/subscribers/{subscriberId}/messages` body `{text}`.
3. Backend: validate 1..4096 chars; TelegramSender.sendText(bot, chatId, text):
   - 200 OK → event `personal_message_sent` + return 200 to UI.
   - 403 Forbidden → sender catches → SubscriberService.markBlocked → `status=blocked` + `blockedAt=now` + event `subscriber_blocked`; sender пише telegram_send_failed (existing). UI toast "Subscriber blocked the bot."
   - 400 chat_not_found → markDeleted → `status=deleted` + `deletedAt=now` + event `subscriber_deleted`. UI toast "Telegram chat no longer exists."
   - 429 → existing TelegramSender retry-with-backoff (без змін); exhausted → 503 `telegram_rate_limited` до UI.

**Сценарій 9 — export CSV:**
1. На `/subscribers` клік "Export CSV" з поточними filter'ами → POST `/api/v1/projects/{projectId}/subscribers/export` body `{filter}`.
2. Backend: requireOwned; estimated count > 200000 → 422 `export_filter_too_large`; insert у `subscriber_exports` (status=PENDING + filter + ownerId + createdAt). Concurrent attempt ловиться partial-unique index → 409 `export_in_flight` (R2 fallback Redis-lock якщо partial-filter не підтримується).
3. Enqueue ExportSubscribersJob з deterministic UUID для retry safety. Return 202 + `{exportId, status: "PENDING"}`.
4. Job (JobRunr worker): status=RUNNING; streaming cursor через subscribers → CSV writer (header + UTF-8 BOM, escape, всі identity-fields + status + dates + tags + custom_fields як JSON-string) → GridFS upload з metadata. status=DONE + fileId + rowCount + completedAt; виклик EmailService.sendExportReadyEmail. Event `subscribers_export_completed`. Job timeout 10 хв — overshoot → status=FAILED + event _failed + email _failedEmail.
5. UI "Recent exports" Dialog polls `GET /api/v1/projects/{projectId}/subscribers/exports?limit=10` → бачить row статус DONE з кнопкою Download.

**Сценарій 10 — download:**
1. Клік Download (або клік link'а з email): `GET /api/v1/projects/{projectId}/subscribers/exports/{exportId}/download?token=<HMAC>`.
2. Backend: parse token → перевірити HMAC (включає `projectId` у payload — захист від cross-project token substitution на verify рівні, без DB hit) constant-time → 401 `invalid_token` на mismatch.
3. `expiresAt < now` → 410 `export_expired`.
4. Rate-limit перевірка (30 download/min per project) → 429 на overflow.
5. requireOwned → 404 на foreign owner; project soft-deleted → 410 `export_project_unavailable` (явний mapping).
6. Lookup export: DONE → streaming GridFS resource (Content-Disposition attachment, Content-Type text/csv) + event `subscribers_export_downloaded`. PURGED → 410 `export_purged`. FAILED → 410 `export_failed`.

**Сценарій 11 — cleanup:**
1. Recurring daily job (03:00 UTC, mirror ProjectHardDeleteJob precedent).
2. Знаходить exports старше 7 днів у status=DONE; per export — видаляє GridFS file → переводить export в `PURGED`, нулює fileId, event `subscribers_export_purged`. Per-step idempotent.

**Сценарій 12 — owner soft-deletes project:**
1. UI flow Epic 03 (project soft-delete) — `Project.deletedAt=now`.
2. Усі subscriber-CRM endpoints на цей projectId → 404 (requireOwned блокує).
3. Існуючі signed download URLs → 410 `export_project_unavailable` (endpoint розрізнює явним мапінгом).
4. Через 7 днів ProjectHardDeleteJob extended cascade у послідовності: GridFS files → subscriber_exports → subscriber_events → subscribers → tags → project doc. GridFS першим, щоб не лишилось orphan-файлів. Кожен крок idempotent, mid-cascade crash recoverable (JobRunr retry перезапускає від поточного стану).
5. Restore (якщо у вікні) — дані повертаються (теги, custom fields, subscribers — все intact).

**Сценарій 13 — cross-project isolation:**
1. Той самий tg-user пише `/start` у Project A і Project B (два різних боти у одного власника).
2. У кожному проекті upsert по (projectId, telegramUserId) створює ОКРЕМИЙ Subscriber-документ (project.md role table: "Same Telegram user in two projects = two separate Subscriber records (intentional isolation)").
3. Tags + customFields двох subscriber'ів незалежні.

## Критерии приёмки

- [ ] **AC1.** `/start` от нового tg-user (private chat, bot CONNECTED, project not soft-deleted) → Subscriber появляется в `GET /api/v1/projects/{projectId}/subscribers` со всеми identity-полями (firstName, lastName, username, languageCode, telegramUserId, telegramChatId, telegramBotId); `status=active`, `tags=[]`, `customFields={}`; event `subscriber_registered` в `subscriber_events`.
  - Backend SLA: webhook-to-Mongo-write latency P95 <500ms (measurable via `ProcessTelegramUpdateJob` end-to-end integration test).
  - UI observability: <2s end-to-end (webhook + worker + frontend refetch) — verified manually в staging-smoke runbook, не автоматизируется.
- [ ] **AC2.** Reactivation: тот же tg-user повторно пишет `/start`, но `status=unsubscribed | blocked | deleted` → upsert переводит `status=active`, `subscribedAt` НЕ перезаписан, `lastSeenAt=now`, `tags` и `customFields` сохранены; event `subscriber_reactivated`.
- [ ] **AC3.** `/stop` в private chat → `markUnsubscribed` → `status=unsubscribed`, `unsubscribedAt=now`; event `subscriber_unsubscribed{reason:"command_stop"}`. Повторный `/stop` от того же subscriber'а → idempotent (без дубль-события).
- [ ] **AC4.** Manual unsubscribe POST `/api/v1/projects/{projectId}/subscribers/{subscriberId}/unsubscribe` → `status=unsubscribed`; event `subscriber_unsubscribed{reason:"manual"}`. На уже unsubscribed/blocked/deleted subscriber'е → 409 `already_unsubscribed` (idempotent, без дубль-события).
- [ ] **AC5.** Send personal message: TelegramSender ловит 403 → SubscriberService.markBlocked(projectId, telegramBotId, chatId) (projectId извлекается sender'ом из уже загруженного Bot — loose coupling) → status=blocked, blockedAt=now; events: subscriber_blocked (от сервиса) + telegram_send_failed (от sender'а, existing); HTTP к UI — 200 OK с body `{status: "blocked", message: "Subscriber blocked the bot"}` (UI рендерит toast по полю message). Аналогично 400 chat_not_found → markDeleted → status=deleted, body `{status: "deleted", message: "Telegram chat no longer exists"}`. 429 → existing retry-with-backoff; exhausted → 503 `telegram_rate_limited`.
- [ ] **AC6.** Rate-limit: 100 нов. `/start` per project per minute via Redis bucket `bf:rate:start:{projectId}` (INCR+EXPIRE 60s, fail-open на Redis-down); existing-subscriber повторный `/start` (lookup перед INCR) НЕ лимитируется; overflow → silent ignore (НЕ создаёт Subscriber) + event `rate_limit_exceeded`; webhook возвращает 200 OK Telegram'у (не 429, иначе Telegram повторит).
- [ ] **AC7.** Cross-project isolation: same `telegram_user_id` в Project A и Project B → 2 отдельных Subscriber-документа; unique compound `(projectId, telegramUserId)` + service-level dedup; integration-тест `CrossProjectIsolationIT` явно проверяет.
- [ ] **AC8.** Tag CRUD:
  - POST `/api/v1/projects/{projectId}/tags` `{slug, label?}` — slug regex `^[a-z0-9_-]{1,32}$` (lowercase alnum + dash/underscore, max 32); 400 на regex fail; 409 `tag_name_taken` на unique violation. Slug immutable после создания.
  - GET `/api/v1/projects/{projectId}/tags` — список с `subscriberCount` (denormalized counter на Tag entity, инкрементируется при add-tag, декрементируется при remove-tag, согласуется с architecture.md "tags — project-scoped tag registry with subscriber count").
  - PATCH `/api/v1/projects/{projectId}/tags/{slug}` `{label}` → 200 с обновлённым tag-документом. Менять можно только `label`; попытка изменить slug → 422 `tag_slug_immutable`.
  - DELETE `/api/v1/projects/{projectId}/tags/{slug}` — confirm на UI с count subscriber'ов. Endpoint синхронно завершує cascade (видалення тега з subscribers + delete tag doc) для невеликих проектів (<10k subscribers з цим тегом) і повертає 204. Для great-projects tech-spec обирає чи робити це async через JobRunr-job + 202 (на момент MVP реально rare); event `tag_deleted` пишеться по завершенню. JobRunr retry policy обробляє mid-operation failures.
- [ ] **AC9.** Add/remove tag на subscriber:
  - POST `/api/v1/projects/{projectId}/subscribers/{subscriberId}/tags` `{slug}` → 200 с обновлённым subscriber-документом. Find-or-create tag в `tags` collection; idempotent add (повторный POST того же тега — без изменений и без дубль-события); on actual add → `Tag.subscriberCount += 1` + event `subscriber_tag_added`.
  - DELETE `/api/v1/projects/{projectId}/subscribers/{subscriberId}/tags/{slug}` → 204. Idempotent (на отсутствующем теге — без изменений); on actual remove → `Tag.subscriberCount -= 1` (clamped at 0) + event `subscriber_tag_removed`.
- [ ] **AC10.** CustomFieldDefinition CRUD:
  - POST `/api/v1/projects/{projectId}/custom-fields` `{name, label, type, defaultValue?}` — `name` slug regex `^[a-z0-9_-]{1,32}$`; `type IN (string,number,boolean,date)`; 20-cap check → 422 `custom_field_limit_reached`; duplicate name → 409 `custom_field_name_taken`; insert в `Project.customFieldDefinitions[]` + event `custom_field_definition_created`.
  - PATCH `/api/v1/projects/{projectId}/custom-fields/{name}` — разрешено менять ТОЛЬКО `label` + `defaultValue`; `name` и `type` immutable (попытка изменить → 422 `custom_field_immutable`).
  - DELETE `/api/v1/projects/{projectId}/custom-fields/{name}` — confirm в UI ("X subscribers have a value for this field. Deleting will remove their data."); backend cascade-видаляє значення цього field з усіх subscriber'ів проекту + видаляє definition з embed-array; event `custom_field_definition_deleted`.
- [ ] **AC11.** Per-type validation на set custom field value (PATCH `/api/v1/projects/{projectId}/subscribers/{subscriberId}/custom-fields`):
  - `type=string`: trim + max 1024 chars.
  - `type=number`: finite double (reject Infinity/NaN/null-strings; принять "3.14", "-0", "1e10").
  - `type=boolean`: accept `true|false|"yes"|"no"` (case-insensitive).
  - `type=date`: ISO-8601 (`OffsetDateTime` parseable).
  - На fail → 422 `custom_field_type_mismatch` с указанием поля.
  - Mass-assignment: ключи, отсутствующие в schema, silent drop (НЕ ошибка — frontend может посылать устаревшую схему).
- [ ] **AC12.** Delete custom field definition → cascade-удаление значения этого field на ВСЕХ subscriber'ах проекта + удаление definition из Project; integration-тест с ≥10 subscribers подтверждает purge values; UI показывает confirm modal с count.
- [ ] **AC13.** List endpoint `GET /api/v1/projects/{projectId}/subscribers`:
  - Query параметры: `search` (min 2 chars, full-text search по firstName+lastName+username), `status` (single value), `tags_include[]` (subscriber должен иметь ВСЕ перечисленные теги), `tags_exclude[]` (subscriber НЕ должен иметь ни одного), `subscribed_from`/`subscribed_to` (Instant range), `sort=created_desc|last_seen_desc`, `cursor` (opaque pagination cursor), `limit` (default 50, max 200).
  - Response: `{items: [...], nextCursor: string|null}` (presence of nextCursor означает hasNext).
  - P95 <300ms на dataset 1000 subscribers (latency-тест под @Tag("slow")).
- [ ] **AC14.** Subscriber profile `GET /api/v1/projects/{projectId}/subscribers/{subscriberId}`: возвращает identity + tags + customFields + status + dates. Отдельный `GET /api/v1/projects/{projectId}/subscribers/{subscriberId}/events?limit=50` возвращает последние 50 subscriber_events (sort `createdAt desc`).
- [ ] **AC15.** Send personal message POST `/api/v1/projects/{projectId}/subscribers/{subscriberId}/messages` `{text}`: validation `1..4096` chars (zod + bean); call `TelegramSender.sendText`; success → 200 + event `personal_message_sent`; 403/400/429 — см. AC5.
- [ ] **AC16.** Export POST `/api/v1/projects/{projectId}/subscribers/export` `{filter}`:
  - estimated count > 200000 → 422 `export_filter_too_large`.
  - concurrent (status PENDING|RUNNING для projectId) → 409 `export_in_flight` (partial-unique index гарантирует).
  - else: INSERT `subscriber_exports{status=PENDING, ...}`, enqueue `ExportSubscribersJob` с deterministic UUID, return 202 `{exportId, status: "PENDING"}`.
  - Job: cursor-streaming CSV writer (UTF-8 BOM, всі identity-поля + status + dates + tags + custom_fields як JSON-string; точний порядок колонок — tech-spec) → GridFS upload → UPDATE status=DONE + sendExportReadyEmail.
- [ ] **AC17.** Один in-flight export per project — guarantee на DB-уровне (partial-unique index ИЛИ Redis-lock — tech-spec обирає, дивись R2). Параллельный POST другого export'а на проект зі status PENDING/RUNNING → 409 `export_in_flight`.
- [ ] **AC18.** Export filter size cap: estimated count > 200000 subscribers → 422 `export_filter_too_large` ДО enqueue. Count-estimation operation сама P95 <200ms на 100k dataset (latency-тест @Tag("slow")). Если count-estimation >200ms — fallback на быстрое нижнее приближение (cap по indexed-only filter без $text).
- [ ] **AC19.** Download GET `/api/v1/projects/{projectId}/subscribers/exports/{exportId}/download?token=<HMAC>`:
  - HMAC включает `projectId` в payload (защищает от cross-project token substitution на verify level, без DB hit). Constant-time compare.
  - `expiresAt < now` → 410 `export_expired`.
  - Rate-limit 30 запросов / минуту на projectId через Redis bucket (защита от bandwidth-abuse скриптами); overflow → 429.
  - `requireOwned(currentUserId, projectId, false)` → 404 (foreign owner) или мапим в 410 `export_project_unavailable` (project soft-deleted) — endpoint distinguishes явно.
  - export lookup: `status=DONE` → стрим CSV (Content-Disposition attachment, Content-Type text/csv) + event `subscribers_export_downloaded`; `status=PURGED` → 410 `export_purged`; `status=FAILED` → 410 `export_failed`.
- [ ] **AC20.** Cleanup: recurring daily job (03:00 UTC) находит exports старше 7 дней в статусе DONE → удаляет GridFS-файлы + переводит export в `PURGED` + event `subscribers_export_purged`. Idempotent (повторный запуск на уже PURGED — no-op). Per-export step может failед безопасно: следующий запуск через 24h подберёт.
- [ ] **AC21.** Access control: ВСЕ endpoints из этого эпика начинаются с `ProjectService.requireOwned(currentUserId, projectId, includeSoftDeleted=false)`. Foreign owner / soft-deleted / malformed `projectId` → 404 (uniform anti-enumeration, существующий precedent).
- [ ] **AC22.** Soft-delete cascade:
  - `project.deletedAt` set → все subscriber-CRM endpoints на этот projectId → 404.
  - Существующие signed export URLs → 410 `export_project_unavailable`.
  - `ProjectHardDeleteJob` extended cascade order: GridFS files → subscriber_exports → subscriber_events → subscribers → tags → project doc (GridFS перед subscriber_exports чтобы избежать orphan-файлы).
  - Каждый шаг каскада идемпотентный (re-run на partial-state — продолжает с того места); mid-cascade crash → JobRunr retry policy перезапускает job, completed-collections видно как empty, остальные подбираются.
- [ ] **AC23.** Event sourcing разделение:
  - `subscriber_events` (новая, append-only, TTL 365d, indexed `(subscriberId, createdAt desc)`) — это ЕДИНСТВЕННЫЙ writer для CRM-lifecycle событий subscriber'а: `subscriber_registered`, `subscriber_reactivated`, `subscriber_unsubscribed`, `subscriber_blocked`, `subscriber_deleted`, `subscriber_tag_added`, `subscriber_tag_removed`, `subscriber_custom_field_set`, `personal_message_sent`, `personal_message_failed`. Используется для рендера history feed на subscriber profile.
  - `events` (existing) — НЕ дублирует CRM-события. Продолжает писать только: webhook-уровень (04b: `telegram_command_*`, `telegram_message_*`, `telegram_update_other`), bot lifecycle (04a), TelegramSender (04c: `telegram_message_sent`, `telegram_send_failed`), security/auth, project. Subscriber-lifecycle events НЕ дублируются туда (consistency-failure story: если subscriber_events write fails — событие потеряно для feed'а, но в `events` лежит соответствующий 04b/04c системный event с overlap-метаданными для audit; admin может реконструировать через корелляцию по chatId+timestamp).
- [ ] **AC24.** Stub replacement: `NoOpSubscriberService` удалён (вместе с тестом); `SubscriberServiceImpl @Service` инжектится в `ProcessTelegramUpdateJob`. `grep -r "NoOpSubscriberService" backend/src/main` → пусто.

## Ограничения

**SLA / performance:**
- `/api/v1/projects/{projectId}/subscribers` P95 <300ms на dataset 1000 subscribers (text-index + cursor pagination).
- Subscriber auto-registration latency observed-in-UI <2s (background worker + Mongo write).
- Export job hard cap 10 минут на JobRunr выполнение.
- Filter cap 200000 subscribers — выше → 422.
- Tag delete cascade: sync на любом размере проекта; on partial-failure (доcs частично обновились) — JobRunr retry до consistency, status visible через standard JobRunr Failed-queue.

**Security:**
- ProjectService.requireOwned гард на всех endpoints (anti-IDOR).
- Signed download URL: HMAC-SHA256 с projectId в payload (defense-in-depth: HMAC-verify + DB ownership), env key `SUBSCRIBER_EXPORT_TOKEN_KEY` (32-byte hex, rotated quarterly).
- Mass-assignment protection: PATCH custom-fields silent-drop ключей не из schema.
- `@JsonIgnoreProperties(ignoreUnknown=true)` на ВСЕХ Request records.
- Email content escape — мы используем существующий `EmailService.htmlEscape` precedent.
- Rate-limit fail-open на Redis outage (codebase convention из AuthService); accept risk per Q8.

**Concurrency / consistency:**
- Idempotency upsert: unique `(projectId, telegramUserId)` + DuplicateKeyException catch + retry-read (mirror BotService.connect).
- Add/remove tag — idempotent by design (повторні виклики не змінюють стан і не пишуть дубль-події).
- Tag find-or-create: unique `(projectId, slug)` + DuplicateKeyException catch.
- Export concurrency: partial-unique `(projectId, status WHERE status IN PENDING|RUNNING)` гарантирует один in-flight per project на DB-уровне.
- ExportSubscribersJob enqueue з deterministic UUID — retry safety (повторний enqueue з тим самим exportId створює той самий JobRunr-job).
- Race на 20-cap CustomFieldDefinition: tech-spec обирає конкретну стратегію (atomic conditional push АБО post-write count check + compensate — див. R4).

**MVP scope (свідомо НЕ робимо):**
- Segment builder с AND/OR/NOT и условиями по custom fields — Epic 12 (nice-to-have).
- Import CSV — Epic 12.
- Lead scoring — Epic 12.
- GDPR-запросы (export all user data by email, right to deletion) — Epic 12.
- Bulk-операции (масс-tag по filter'у) — Epic 12.
- Subscriber profile "Send personal message" с медиа-вложениями — text-only MVP.
- CustomFieldDefinition rename slug — accepted limitation (delete-and-recreate как escape hatch).
- CustomFieldDefinition type change — immutable; чтобы избежать data-loss risk.
- Subscriber hard-delete UI button (статус `deleted` — для chat-not-found, не для owner-инициированного удаления).
- Recurring exports.

**Dependencies (новые):**
- Никаких новых maven-зависимостей. GridFS приходит с уже подключенным `spring-boot-starter-data-mongodb`; signed-URL — нативный `javax.crypto.Mac`.

**Local dev:**
- Real Mongo + Redis + Mailpit в integration-тестах (Testcontainers, mirror 04b precedent).
- Staging-smoke через ngrok + BotFather throwaway bot (см. verification).
- GridFS storage — часть того же MongoDB instance, без новой инфраструктуры.

**Production index management:**
- Auto-index creation вже глобально увімкнено у проекті — нові collections (subscribers, subscriber_events, tags, subscriber_exports) та їх індекси автостворюються в prod при першому запуску. Приймаємо як trade-off vs manual deploy-script step; risk R14 documented.

**Environment variables (новые):**
- `SUBSCRIBER_EXPORT_TOKEN_KEY` — 32-byte hex для HMAC signed URLs (обязательный).
- `SUBSCRIBER_EXPORT_RETENTION_DAYS` — default 7.
- `SUBSCRIBER_RATE_LIMIT_START_PER_MIN` — default 100.
- `SUBSCRIBER_DOWNLOAD_RATE_LIMIT_PER_MIN` — default 30 (rate-limit на signed-URL endpoint per project).

## Риски

- **R1 — CSV export OOM на больших dataset'ах.** При 100k+ subscribers naive in-memory материализация (List<Subscriber>) приведёт к heap exhaustion. **Митигация:** streaming MongoTemplate cursor → PipedOutputStream → GridFS upload (zero in-memory list). `@Tag("slow")` integration-тест с 50k seeded subscribers + heap-assert.

- **R2 — Partial-unique index `$in`-filter синтаксис в Mongo 8.0.** AC17 концепция "один in-flight export per project" опирается на partial-filter unique index с `status IN (PENDING, RUNNING)`. Если синтаксис не поддерживается → fallback Redis-lock (`bf:export:lock:{projectId}` с TTL 10 min). **Митигация:** tech-spec обязан верифицировать через Context7 на этапе detail-design; если partial-filter не работает — Redis-lock alternative готов к подмене.

- **R3 — CustomFieldDefinition type immutability разочаровывает пользователя.** Owner создал `city` як `string`, потім хоче `number` для дат-фільтрації. **Митигація:** документовано в `/custom-fields` UI ("Type cannot be changed later. Delete and recreate to switch types — note that this removes existing data."); delete-and-recreate як escape hatch. Phase 2 поза цим epic'ом: type-migration job якщо виникне реальний попит.

- **R4 — Race на 20-field CustomFieldDefinition cap.** Параллельные POST могут проскочити через `count < 20` check + insert. **Митигация:** atomic conditional push (Mongo aggregation pipeline з $size check) АБО post-write count check + cleanup last-inserted (compensate). Tech-spec обирає конкретну стратегію.

- **R5 — Cross-project isolation regression.** Same `telegramUserId` в Project A и Project B обязан создать ДВА Subscriber'а (project.md role table guarantee). Если service-layer dedup проверяет только по `telegramUserId` без `projectId` — потеряем boundary. **Митигация:** unique compound `(projectId, telegramUserId)` на DB-уровне + CrossProjectIsolationIT integration-тест (explicit assert).

- **R6 — TelegramSender ↔ SubscriberService coupling.** TelegramSender теперь dependent on SubscriberService — потенциальный cyclic-dependency, если SubscriberService начнёт инжектить TelegramSender (broadcast Epic 07 будет соблазн). **Митигация:** документируем в tech-spec: SubscriberService MUST NOT inject TelegramSender direct; broadcast/funnel cases получают sender отдельно через Epic 07/06. Архитектурный гард — TelegramSender внизу графа, читается всеми, не читает CRM.

- **R7 — Существующий NoOpSubscriberService.** Два `@Service` с одним интерфейсом → Spring fail. **Митигация:** удаляем NoOp + его test; precedent: 04b комментарий явно говорит "Epic 05 will replace this @Service directly".

- **R8 — Signed URL HMAC key compromise.** Утечка `SUBSCRIBER_EXPORT_TOKEN_KEY` → все signed URLs вне 7d window становятся forge-able. **Митигация:** короткий expiry 7d; env-var rotated quarterly; audit `subscribers_export_downloaded` event; key хранится только в GitHub Actions secrets + production env.

- **R9 — ExportSubscribersJob long-running занимает JobRunr workers.** 10-min cap × default concurrency = до 10 параллельных long-jobs всего по platform'е. Если 10 owner'ов одновременно запросят export — broadcast / future jobs ждут. **Митигация:** AC17 ограничивает один in-flight per project; cross-project concurrency 10 — accepted MVP. Phase 2: dedicated export-worker-pool через JobRunr filter labels.

- **R10 — GridFS purge sweep performance.** Naive delete-by-metadata-query — collection-scan. **Митигація:** preselect fileId з `subscriber_exports` doc → batch-delete by fileId (indexed); collection-scan 1×/day OK навіть на 1000 files.

- **R11 — ProjectHardDeleteJob extension complexity.** Добавляем 5 cascade-шагов (GridFS + subscriber_exports + subscriber_events + subscribers + tags). Order имеет значение (GridFS перед subscriber_exports иначе orphan-файлы). **Митигация:** explicit `ProjectHardDeleteJobIT` extension с assertions per step; tech-spec фиксирует order.

- **R12 — Rate-limit fail-open во время Redis outage.** Spam-атака `/start` пройдёт. **Митигация:** fail-open — codebase convention (AuthService precedent); accepted per Q8. Monitoring у Epic 09 добавит Redis-down alert.

- **R13 — Subscriber history feed feedback loop.** Active subscriber может за день получить 100+ events (broadcast + funnel-steps от Epic 07/06). LIMIT 50 на profile fetch достаточен; older events видны через scroll-pagination — отложено в Phase 2 этой эпики (не required MVP).

- **R14 — Production auto-index creation.** Auto-index-creation створює індекси при першому запуску app — може зайняти час на already-populated collection (але subscribers порожня, тегів нема — клейн-старт). **Митигація:** ratify в tech-spec; integration-тест `IndexCreationIT` (запускає app з pre-seeded data, перевіряє індекси створені).

- **R15 — Rate-limit fail-open monitoring gap.** На відміну від login brute-force (AuthService precedent, де flood має natural upper bound — кінцева кількість existing users), `/start` flood має unbounded surface (атакер може скриптом створити N tg-bot accounts і слати на наш бот). Fail-open у Redis outage = бесконтрольний приріст Subscriber-документів. **Митигація:** Epic 09 моніторинг додасть Redis-down alert; цей epic фіксує assumed-risk як acceptable MVP trade-off. Якщо incident трапиться — runbook: manual purge нових Subscriber'ів за вікно outage через mongosh.

## Технические решения

- **Replace NoOpSubscriberService через `delete + new @Service`** — нет смысла держать оба, slice-тесты могут @MockBean. NoOpSubscriberServiceTest удаляется.
- **CustomFieldDefinition embedded в `Project.customFieldDefinitions[]`** — bounded (max 20), всегда читается с проектом, никогда не запрашивается независимо. project.md Entity Hierarchy уже фиксирует это вложение.
- **Tags — отдельная коллекция** с unique `(projectId, slug)`. Нужна для tags-management page, dropdown без distinct-сканирования по subscribers, и для будущих per-tag метрик.
- **Tag.subscriberCount — denormalized counter**, инкрементируется/декрементируется на add-tag / remove-tag operations (architecture.md commitment "tags — project-scoped tag registry with subscriber count"). НЕ on-demand aggregation: real-time counter простіше та alignment с уже зафіксованою архітектурою. Sync-cost мінімальний — один indexed update per add/remove.
- **SubscriberEvent — отдельная коллекция** (TTL 365d, indexed `(subscriberId, createdAt desc)`) — ЕДИНСТВЕННЫЙ writer для CRM-lifecycle событий. Не дублируем в существующую `events` collection. Разные retention, разные access patterns, разные scope. Consistency-failure story задокументирована в AC23.
- **Mongo text-index для search** на firstName/lastName/username + min 2 chars на UI. Atlas Search / Elastic — deferred при >1M subscribers.
- **Cursor pagination** (opaque base64) + sort newest first. Без numbered pages, без Previous. Load-more UX. count-for-total НЕ делаем (heavy на text-search).
- **CSV export storage — MongoDB GridFS**, не локальный disk, не S3, не sync inline-download. Альтернативу sync inline-download (drop ~5 ACs, простіше) отклонили: 200k subscriber cap означает CSV до ~30MB — для HTTP response держать в памяти JobRunr worker'а долго, плюс Telegram-broadcast (Epic 07) піде по тому же async-pattern, тож раніше будуємо durable infrastructure. Unified backup story (mongodump схватывает GridFS), multi-instance совместимость (не нужен shared filesystem), zero infra-changes vs S3.
- **Signed download URL — HMAC с projectId в payload**, защищает от cross-project token substitution на verify level (без DB hit). Constant-time compare. Helper в common/crypto/.
- **Status state-machine writers:**
  - `active ↔ unsubscribed`: webhook worker (`/stop`) или UI (manual button).
  - `* → blocked`: TelegramSender catches 403 → direct `SubscriberService.markBlocked(projectId, telegramBotId, chatId)` call. projectId extracted sender'ом из already-loaded Bot (loose coupling).
  - `* → deleted`: TelegramSender catches 400 chat_not_found → `markDeleted(...)` analog.
  - Reactivation: любой non-active → active при новом `/start` (workflow line 76).
- **TelegramSender hook — direct constructor inject SubscriberService**, не `ApplicationEventPublisher` (zero precedent в codebase — single-use pattern), не typed-exception bubble (silent drift на каждом caller'е). markBlocked/markDeleted вызовы в catch блока `sendText`, не в `toThrowable` (тот остаётся pure mapper).
- **Events split:**
  - `telegram_send_failed` пишет TelegramSender (existing).
  - `subscriber_blocked` / `subscriber_deleted` пишет SubscriberService.
  - Дублирование `telegram_*` и `subscriber_*` events для одного physical send-failure — намеренное, разные scope (Telegram-API audit vs CRM-lifecycle).
- **Rate-limit C — Redis bucket** per project; existing-subscriber lookup перед инкрементом → не лимитируется; overflow event `rate_limit_exceeded`. Fail-open на Redis-down (AuthService precedent). Risk-acceptance: `/start`-flood в окно outage пройдёт; monitoring/alert в Epic 09 (Redis-down метрика).
- **Download-rate-limit** на signed-URL endpoint — отдельный Redis bucket per project, 30 запросов/мин default. Защита от bandwidth-abuse скриптами, который не закрыт expiry-window (7 дней — достаточно для сотен скачиваний).
- **Project soft-delete cascade A** — children intact во время 7-day retention; restore возвращает всё; ProjectHardDeleteJob extends cascade (GridFS → subscriber_exports → subscriber_events → subscribers → tags → project).
- **Subscriber `status=deleted` forever** — без TTL, без purge job в Epic 05. Реактивируется на новый /start (рідко но возможно).
- **CSV export concurrency: один in-flight per project** — primary через partial-unique compound `(projectId, status IN (PENDING, RUNNING))`. Параллель → 409. Tech-spec обязан верифицировать Mongo 8 partial-filter `$in`-syntax; fallback готов — Redis-lock `bf:export:lock:{projectId}` с TTL 10 min (R2).
- **CustomFieldDefinition semantics** — slug `name` immutable после save; `label` + `defaultValue` editable; `type` immutable (data-loss prevention). Delete cascades remove-value-from-all-subscribers + confirm modal с count.
- **Failed export "New export" button** (не "Retry") — создаёт новый export с теми же filter'ами; старый FAILED row остаётся для audit; admin может удалить через JobRunr dashboard (Epic 10).
- **markBlocked/markDeleted сигнатура** — `(projectId, telegramBotId, chatId)`; sender передаёт projectId извлечённым из уже загруженного Bot, без re-fetch. Loose coupling (SubscriberService не знает Bot entity).
- **JobRunr jobs:**
  - `ExportSubscribersJob` — one-shot per export, enqueue с deterministic UUID (retry safety, 04b precedent).
  - `ExportCleanupJob` — recurring daily (03:00 UTC, shared slot с ProjectHardDeleteJob — sequentially выполняются).
- **Email language — Ukrainian default** (existing EmailService precedent: "Експорт підписників готовий", "Експорт підписників не вдався"). Не i18n-aware (cookie не доступен в email-job context).
- **Sidebar — sub-items под Subscribers**: Subscribers / Tags / Custom Fields как nav-children. ux-guidelines.md правит "Subscribers" — добавляем sub-nav.
- **Production index — global auto-index-creation** (existing application.properties setting). Не делаем кастомные ensure-index callbacks — accept trade-off vs manual deploy-script step.
- **Tech-spec phasing — рекомендация (не lock):** tech-spec may разделить implementation на 2 фази: (1) core CRM (subscribers, tags, custom fields, status state-machine, list/profile UI) — ACs 1-15, 21-24; (2) async export (subscriber_exports, GridFS, signed URL, email, cleanup, download rate-limit) — ACs 16-20. Фази не имеют runtime coupling. Это дает faster MVP demo + независимый review surface для export'а.

## Тестирование

**Unit-тесты:** делаются всегда. Конкретно:
- `SubscriberStatusMachineTest` — все transitions (active↔unsubscribed, *→blocked, *→deleted, reactivation на /start от любого non-active) + idempotency (повторный markUnsubscribed → no-op).
- `SegmentFilterBuilderTest` — DTO `{status, tags_include[], tags_exclude[], dateRange}` → Mongo query Document; edge cases (empty filter, only one criterion, all criteria).
- `SubscriberCsvWriterTest` — UTF-8 BOM, escape (";", quotes, newlines, comma), header order стабільний, tags ";"-joined, customFields as JSON-string.
- `CustomFieldValidatorTest` — string trim + max 1024, number parseDouble (включая edge: "3.14", "-0", "1e10"), boolean (true/false/yes/no case-insensitive), date ISO-8601 + reject "tomorrow".
- `TelegramTextValidatorTest` — `1..4096` chars, reject blank.
- `TagSlugValidatorTest` — regex `^[a-z0-9_-]{1,32}$` (accept "vip", "course_buyer", "paid-2024"; reject "VIP", "vi p", "vip.2024", "1234567890123456789012345678901234" (>32 chars)).
- `SignedDownloadTokenTest` — HMAC mint + verify constant-time + expiry check + projectId-in-payload protection.

**Интеграционные тесты: делаем.** Причина: L-feature пересекается с webhook (04b), TelegramSender (04c), ProjectHardDeleteJob (03), EventService (01), JobRunr, GridFS, Redis, Email — каждый из них требует real Mongo + Redis + Mailpit. Mock'и здесь были бы пустыми (assert на mock-call вместо assert на real-persistence).

Конкретно (mirror `AbstractIntegrationTest` precedent):

- `SubscriberServiceImplIT` — full webhook contour: invoke `ProcessTelegramUpdateJob.handle(rawUpdateId)` напрямую (mirror 04b ProcessTelegramUpdateJobTest), assert Subscriber persisted, events written, reactivation flow.
- `SubscriberRateLimitIT` — 101 параллельных `/start` от 101 разных tg_user_id → 100 Subscribers, 1+ event `rate_limit_exceeded`, существующий subscriber повторные /start не лимитятся.
- `SubscriberControllerIT` — list endpoint: search + filter combinations (status, tags include/exclude, dateRange) + cursor pagination forward + sort variants + access guards (foreign owner → 404, missing project → 404, malformed → 404).
- `SubscriberProfileIT` — GET profile + history feed; events feed sort и limit.
- `SubscriberPersonalMessageIT` — sendText success-path; mock TelegramSender 403 → markBlocked → status=blocked + event; mock 400 → markDeleted; mock 429 → 503 telegram_rate_limited (existing sender retry-with-backoff behaviour).
- `SubscriberStatusFlowIT` — manual unsubscribe success; idempotent unsubscribe-twice → 409; reactivation: blocked → /start → active.
- `TagControllerIT` — CRUD: create + duplicate 409 + slug regex 400 + denormalized counter inc/dec на add/remove + label-only edit (slug immutable 422 assertion) + delete cascade на subscribers + JobRunr retry на partial-cascade-failure.
- `CustomFieldDefinitionIT` — schema CRUD + 20-cap 422 + name conflict 409 + label edit + delete cascade видалення значень (assert на ≥5 subscribers) + slug/type immutable.
- `CustomFieldValueValidationIT` — per-type validation 422; mass-assignment silent drop unknown keys.
- `SubscriberExportIT` — POST export → `ExportSubscribersJob` `@Autowired` invoke `.handle(exportId)` directly → GridFS file written (verify via `GridFsTemplate.findOne`), email enqueued (Mailpit `MailpitContainer.receivedMessages()`), status=DONE.
- `SubscriberExportConcurrencyIT` — concurrent POST → second 409 (partial-unique index assertion).
- `SubscriberExportSignedUrlIT` — happy download + invalid token 401 + expired 410 + project soft-deleted 410 + GridFS purged 410 + cross-project token substitution 401 (HMAC payload mismatch) + download rate-limit 429 на 31-му запиті за хвилину.
- `ExportCleanupJobIT` — seed `subscriber_exports` с createdAt < now-8d + GridFS file → invoke job → file gone, status=PURGED.
- `ProjectSoftDeleteCascadeIT` — soft-delete project → CRM endpoints 404; hard-delete → all 4 cascade collections + GridFS files cleaned. Order assertion.
- `CrossProjectIsolationIT` — same telegramUserId в Project A и B → 2 Subscriber docs, tags независимы.
- `TelegramSenderSubscriberHookIT` — `TelegramSender.sendText(bot, chatId, text)` против MockWebServer-stub'а Telegram 403 → assert `SubscriberService.markBlocked` called + status=blocked + event `subscriber_blocked` + event `telegram_send_failed` (existing) — confirms split.

**E2E тесты: делаем (Playwright golden path).** Причина: 4 новые UI-страницы с критическими flow (auto-update list от webhook, add tag, export-download), которые отдельно unit/integration не покрывают полностью. Файл: `frontend/e2e/subscribers.spec.ts`.

Сценарий (~10 мин):
1. Login → /projects → click project → /subscribers (empty state).
2. Test-fixture отправляет fake webhook (`POST /webhooks/telegram/{projectId}` через bot-stub с правильным secret) → /start от tg_user 12345 → page reload → одна строка в таблице.
3. Search "Iva" → не находит (имя другое); добавить второго subscriber'а с firstName=Ivanna → search "Iva" → одна строка.
4. Click row → /subscribers/{id} → tabs Tags/CustomFields/History видны.
5. Add tag "vip" через combobox → chip появился; navigate /tags → counter=1.
6. Navigate /custom-fields → Add field "city" type=string → save; back to subscriber → set value "Київ" → save.
7. Click "Export CSV" → toast "Export started"; poll "Recent exports" пока status=DONE (timeout 30s); click Download → CSV-файл скачался, проверить header + 2 rows.
8. (Optional) Manual unsubscribe одного subscriber'а → confirm modal → status badge меняется на "Unsubscribed".

Также Vitest на vee-validate schemas (form-валидация Add tag / Add custom field).

**Staging-smoke runbook** (создаётся в рамках этой фичи): `docs/staging-smoke/09-subscribers.md` — 15-минутный manual checklist с throwaway BotFather-ботом + ngrok-туннелем. Покрывает: real Telegram /start → row in /subscribers (visible <2s) → add tag → /stop → status flip → define custom field + set value → export CSV → email прилетел на test-mailbox → click signed URL → CSV скачан → cleanup.

## Как проверить

### Агент проверяет (during development, без deploy)

| Шаг | Инструмент | Ожидаемый результат |
|-----|-----------|-------------------|
| 1. Полный test suite | `./gradlew test` | Все тесты зелёные; coverage на `subscriber/` + `tag/` + дополнения в `project/` >= 80% на business logic |
| 2. Slow tests (latency + heap) | `./gradlew test -PrunSlow=true --tests *SubscriberCsv* --tests *SubscriberControllerLatency*` | CSV 50k seeded — heap <512MB; list P95 <300ms |
| 3. Health-check после bootRun | `curl http://localhost:8080/health` | `{"status":"ok"}` |
| 4. New collections + indexes созданы | `mongosh botfunnel --eval 'db.subscribers.getIndexes()'` + аналогично tags/subscriber_events/subscriber_exports | unique (projectId, telegramUserId); (projectId, status, createdAt desc); (projectId, tags); text(firstName, lastName, username); TTL 365d на subscriber_events.createdAt; partial-unique на subscriber_exports |
| 5. Access guard regression | `curl -b "JSESSIONID=foreignUser" /api/v1/projects/{otherUserProject}/subscribers` | HTTP 404 (anti-enumeration) |
| 6. Webhook still works (04b regression) | через fake webhook (используя 04b test fixture) | Subscriber появляется в Mongo + event `subscriber_registered` |
| 7. NoOpSubscriberService removed | `grep -r "NoOpSubscriberService" backend/src/main` | Пусто; SubscriberServiceImpl @Service |
| 8. Frontend dev build | `cd frontend && pnpm install && pnpm dev` → click через /subscribers / /tags / /custom-fields | Все страницы рендерятся, нет console errors, i18n parity (UA/EN) проходит check-locales |
| 9. Playwright E2E | `cd frontend && pnpm playwright test subscribers.spec.ts --reporter=line` | Зелёный run, golden path complete |
| 10. Frontend i18n parity gate | `pnpm prebuild` → `node scripts/check-locales.mjs` | Все ключи subscribers.* / tags.* / customFields.* / exports.* присутствуют в обоих uk.json и en.json |

### Пользователь проверяет (post-deploy на staging)

**Prerequisite:** перед запуском smoke нужно создать `docs/staging-smoke/09-subscribers.md` (новый runbook, создаётся в рамках задач этой фичи). Также проверить, что `SUBSCRIBER_EXPORT_TOKEN_KEY` присутствует в GitHub Actions secrets (`openssl rand -hex 32`).

**Что проверяем (15 мин, real Telegram + ngrok):**
- Connect throwaway-бот через BotFather + ngrok-туннель → /start со своего Telegram → перейти в /subscribers, увидеть себя в списке за <2s.
- Add tag "smoke_test" → перейти в /tags, увидеть counter=1.
- Define custom field "city" (string), set value "Kyiv" в профиле — сохранилось.
- Send personal message "hello from staging" → получили в Telegram.
- /stop → status badge меняется на "Unsubscribed" в течение <2s.
- /start снова → reactivate → status=active, тег и custom field на месте.
- Click "Export CSV" → email прилетел на test-mailbox → click signed URL → CSV скачан → открыть в Excel/sheets, проверить header + BOM (UTF-8 корректно отображает Kyrillic city).
- Concurrent export attempt while RUNNING → UI banner "Export already running" / HTTP 409.
- (Optional) Manual unsubscribe → confirm → status flip.
- Cleanup: disconnect bot, удалить throwaway-bot через BotFather.

**Зачем руками:** ngrok + Telegram real-time push + GridFS-через-email — невозможно автоматизировать в CI; этот эпик впервые экспонирует CRM в production, поэтому глазами + signed URL email-flow обязательны вживую.
