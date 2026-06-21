# Manual smoke checklist — 09-subscribers

> ✅ **ЗАВЕРШЕНО 2026-05-31** (Task 17, local-staging). Усі ручні перевірки пройдено.
> Знайдені під час смоуку дефекти виправлено й залоговано як F-min-2…F-min-12 у
> `logs/qa/post-deploy-verification-report.md` (відкритим лишається тільки F-min-1 — бекфіл
> env-доків для майбутнього deploy-епіка).

Ручні / user-перевірки, відкладені на кінець фічі. Пройти все одним заходом перед деплоєм.
Кожна задача дописує сюди свої кроки, які НЕ покриваються автотестами.

## Передумови

```bash
# Інфраструктура (вже піднята в development-* контейнерах)
docker ps   # mongo:27017, redis:6379, mailpit

# Backend
cd backend && ./gradlew bootRun        # http://localhost:8080

# Frontend
cd frontend && pnpm install && pnpm dev # http://localhost:3000
```

---

## Task 1 — Backend data layer + crypto (verify: smoke)

Статус: ✅ вже перевірено агентом під час виконання (наведено для повторної перевірки за потреби).

Перевірка індексів (на хості немає `mongosh` — через контейнер):

```bash
docker exec development-mongo-1 mongosh botfunnel --quiet --eval '
["subscribers","tags","subscriber_events","subscriber_exports"].forEach(c => {
  print("===== "+c+" =====");
  db.getCollection(c).getIndexes().forEach(i => print(JSON.stringify(i)));
});'
```

Очікувано:
- `subscribers`: unique `(projectId, telegramUserId)`; text-index `default_language:"none"`, weights firstName=3/lastName=3/username=5; + `(projectId,lastSeenAt:-1)`, `(projectId,subscribedAt:-1)`, `(projectId,status)`, `(projectId,tags)`.
- `tags`: unique `(projectId, slug)`.
- `subscriber_events`: `(subscriberId, createdAt:-1)` + TTL `expireAfterSeconds: 31536000` (365 днів) на `createdAt`.
- `subscriber_exports`: partial-unique `(projectId)` з `partialFilterExpression {status:{$in:["PENDING","RUNNING"]}}` + `(projectId, createdAt:-1)`.

---

## Task 2 — Frontend foundation (verify: smoke + USER)

Smoke (✅ headless-перевірено агентом): `pnpm prebuild` exit 0, `pnpm test` 248/248, `pnpm dev` стартує без помилок, `ls frontend/components/ui/` = 12 директорій.

### USER-перевірка в браузері (✅ виконано вручну)

1. ✅ Відкрий http://localhost:3000 → залогінься тестовим акаунтом.
2. ✅ Обери проект у `ProjectSelector` (топбар) — будь-який активний.
3. ✅ У сайдбарі під «Усі проекти» з'явилась група **Підписники** + 3 дочірні пункти з відступом: **Підписники / Теги / Кастомні поля**. Без обраного проекту цих пунктів немає.
4. ✅ Клікни **Підписники** → URL `/projects/{id}/subscribers`, заголовок `Підписники`, пункт підсвічений.
5. ✅ Клікни **Теги** → `/projects/{id}/tags`, заголовок `Теги`. Підсвічений саме «Теги», а не «Підписники».
6. ✅ Клікни **Кастомні поля** → `/projects/{id}/custom-fields`, заголовок `Кастомні поля`.
7. ✅ Зайди на профіль підписника `/projects/{id}/subscribers/{будь-який-id}` → заголовок `Профіль підписника`, активними лишаються батьківський «Підписники» + дочірній «Підписники».
8. ✅ У консолі браузера немає помилок і немає «сирих» i18n-ключів (напр. `subscribers.title` замість тексту).
9. ✅ Перемкни мову через `LangSwitcher`: UK → EN → UK, повтори один перехід — обидві локалі рендеряться коректно.

> Примітка (ВИПРАВЛЕНО під час Task 17 smoke): WARN `Two component files resolving to the same name UiTable/UiTabs/...` на старті dev-сервера спричиняв Nuxt auto-import — для кожного shadcn-vue примітиву `ui/<x>/Badge.vue` і `ui/<x>/index.ts` давали одне ім'я (`UiBadge`). Фікс — у `nuxt.config.ts` обмежено скан компонентів `extensions: ['.vue']`, тож `index.ts`-barrel'и не реєструються як компоненти (вони споживаються лише явними імпортами). Згенеровані `ui/`-файли НЕ чіпалися. Перевірка: `nuxi prepare` → 0 WARN.

---

## Task 3 — SubscriberServiceImpl: state machine, rate-limit, events (verify: smoke)

Статус: ✅ smoke повністю автоматичний, ручних кроків немає (немає `Verify-user`).

```bash
cd backend && ./gradlew test \
  --tests SubscriberServiceImplIT --tests SubscriberRateLimitIT \
  --tests SubscriberStatusMachineTest --tests SubscriberStubReplacementIT \
  --tests SubscriberEventsIsolationIT
grep -r "NoOpSubscriberService" backend/src/main   # очікувано: пусто (AC24)
```

> Наскрізну ручну перевірку реальним Telegram `/start`/`/stop` (авто-реєстрація → подія `subscriber_registered`) робимо в межах webhook-флоу наприкінці фічі / після Task 6 — на рівні Task 3 standalone живого Telegram не треба, усе покрито ITs через `processTelegramUpdateJob.handle(...)`.

---

## Task 4 — TagService + TagController + counter (verify: smoke)

Статус: ✅ smoke повністю автоматичний, ручних кроків немає (немає `Verify-user`).

```bash
cd backend && ./gradlew test \
  --tests TagServiceTest --tests TagControllerIT --tests TagSlugValidatorTest
```

### Опціональний ручний curl-чекліст живого API (за бажанням перед деплоєм)

Потрібні залогінена сесія (cookie) + `X-XSRF-TOKEN` + `{projectId}` свого проекту. Лічильник `subscriberCount` через assign/unassign підписника тут НЕ перевіряється — це Task 8.

1. ✅ POST `/api/v1/projects/{projectId}/tags` `{"slug":"vip","label":"VIP"}` → `201`, body `{slug, label, subscriberCount:0, createdAt}`, без `id`/`projectId`.
2. ✅ Повторний POST того ж slug → `409` `{code:"tag_name_taken"}`.
3. ✅ POST `{"slug":"VIP"}` (uppercase) → `400` (regex).
4. ✅ GET `/api/v1/projects/{projectId}/tags` → `200`, масив відсортований за `slug asc`.
5. ✅ PATCH `/.../tags/vip` `{"label":"нова назва"}` → `200`, label оновлено, slug незмінний.
6. ✅ DELETE `/.../tags/vip` → `204`; повторний DELETE → теж `204` (ідемпотентно).
7. ✅ Будь-який ендпоінт із чужим/неіснуючим `projectId` → `404` (anti-enumeration).

---

## Task 5 — CustomField CRUD + per-type валідація (verify: smoke)

Статус: ✅ smoke автоматичний (немає `Verify-user`); нижче — ручний 20-cap probe з tech-spec Verify-smoke + опціональний curl-чекліст.

```bash
cd backend && ./gradlew test \
  --tests CustomFieldDefinitionIT --tests CustomFieldValueValidationIT \
  --tests CustomFieldValueValidatorTest
```

### Ручний 20-cap probe (tech-spec Verify-smoke)

Потрібні залогінена сесія (cookie) + `X-XSRF-TOKEN` + свій `{projectId}`. База: `/api/v1/projects/{projectId}/custom-fields`.

1. ✅ POST 20 різних визначень (`f1`..`f20`, `{"name":"f1","label":"F1","type":"STRING"}`) → кожне `201`.
2. ✅ POST 21-го (`f21`) → `422` `{code:"custom_field_limit_reached"}` (atomic cap = 20, не 21 — Decision 3 на індексі 19).
3. ✅ POST дубль існуючого `name` → `409` `{code:"custom_field_name_taken"}`.
4. ✅ POST `{"name":"BAD SLUG",...}` → `400` (regex `@Pattern`).
5. ✅ POST `{"name":"age","type":"NUMBER","defaultValue":"abc"}` → `422` `{code:"custom_field_type_mismatch"}` (F9).
6. ✅ PATCH `/.../custom-fields/{name}` `{"label":"нова","type":"NUMBER"}` → `200`; GET → `type` НЕ змінився (immutable, `@JsonIgnoreProperties` мовчки дропає `type`/`name`).
7. ✅ DELETE `/.../custom-fields/{name}` → `204`; значення зникає з `customFields[name]` усіх підписників проекту (каскад).
8. ✅ Будь-який ендпоінт із чужим/soft-deleted/малформ `projectId` → `404`.

> Subscriber custom-fields PATCH (`/.../subscribers/{id}/custom-fields`, silent-drop невідомих ключів + подія `subscriber_custom_field_set`) повністю покрито `CustomFieldValueValidationIT`. Жива перевірка потребує реального підписника (з webhook) — складаємо в наскрізний subscriber-флоу наприкінці фічі.

---

## Task 6 — TelegramSender hook: terminalReason + markBlocked/markDeleted (verify: smoke)

Статус: ✅ smoke повністю автоматичний, ручних кроків немає (немає `Verify-user`).

```bash
cd backend && ./gradlew test \
  --tests TelegramSenderSubscriberHookIT --tests TelegramSendExceptionTerminalReasonTest \
  --tests TelegramSenderTest
```

> Жива перевірка флоу 403 (бот заблоковано) / 400 «chat not found» потребує (а) реального підписника, що заблокував бота / видалив акаунт, і (б) тригера надсилання — особисте повідомлення підписнику з'являється лише в Task 8 (`SubscriberController` `POST /subscribers/{id}/messages`). Тому наскрізний live-чек «надіслати → 403 → підписник стає BLOCKED + подія `subscriber_blocked`» складаємо в кінець фічі після Task 8. На рівні Task 6 усе покрито `TelegramSenderSubscriberHookIT` (MockWebServer 403/400/401 + `InOrder` audit-before-mark + fail-safe хука).

---

## Task 7 — ProjectHardDeleteJob cascade extension (verify: smoke)

Статус: ✅ smoke повністю автоматичний, ручних кроків немає (немає `Verify-user`).

```bash
cd backend && ./gradlew test --tests ProjectHardDeleteJobIT --tests ProjectSoftDeleteCascadeIT
```

Перевіряє: каскад `events → GridFS → subscriber_exports → subscriber_events → subscribers → tags → emit project_hard_deleted → drop projects` (Decision 8), розширений INFO-лог з per-collection counters, ідемпотентність повторного прогону, isolation проектів <7 днів, 404 на soft-deleted CRM list endpoints.

> Cron триггер (щодня 03:00 UTC) у живому середовищі не перевіряємо — це разовий offline-флоу, який виконується тільки після soft-delete >7d. Якщо хочеться probe-перевірки на локалі — забекдейтити `deletedAt` на >7d через `mongosh` (`db.projects.updateOne({_id:"<id>"}, {$set:{deletedAt: ISODate("2020-01-01T00:00:00Z")}})`), перезапустити бекенд, чекнути логи через ~хвилину (JobRunr підхопить recurring) або стригернути напряму з тестового контексту.

---

## Task 8 — SubscriberController + TagController endpoints wiring (verify: smoke)

Статус: ✅ smoke повністю автоматичний. Уся жива HTTP-поверхня (list з фільтрами + cursor, profile, history, manual unsubscribe, add/remove tag, send personal message, rate-limit, cursor security) — exercised через Task 10 USER (UI) і Task 11 staging-smoke (реальний бот + Telegram).

```bash
cd backend && ./gradlew test \
  --tests SubscriberControllerIT --tests SubscriberProfileIT \
  --tests SubscriberPersonalMessageIT --tests SubscriberManualUnsubscribeIT \
  --tests SegmentFilterBuilderTest --tests TelegramTextValidatorTest
```

> Окрема жива перевірка curl-ом cursor-injection (`?cursor=$(printf '{"v":{"$ne":null},"id":"abc"}' | base64)` → 400 `invalid_cursor`) і `personal_message_rate_limited` (61 запит за хвилину) НЕ потрібна — обидва покриті ITs (`cursor_invalidShape_returns400`, `cursor_dollarPrefixedKeys_returns400`, `rate_limited_returns429_andNoSenderCalled`). Жива перевірка цих сценаріїв з'явиться тільки якщо щось зламається в реальному staging.

---

## Task 9 — Export pipeline (verify: smoke)

Статус: ✅ smoke повністю автоматичний. Жива перевірка наскрізного export-флоу (BOM у спредшиті, реальний email, signed-URL refresh, конкурентний 409) — у `docs/staging-smoke/09-subscribers.md` кроки 9-11 + «Перевірка signed-URL refresh».

```bash
# Default suite (швидко):
cd backend && ./gradlew test \
  --tests SubscriberExportIT --tests SubscriberExportConcurrencyIT \
  --tests SubscriberExportSignedUrlIT --tests ExportCleanupJobIT \
  --tests SubscriberCsvWriterTest --tests SignedDownloadTokenTest

# Slow-tag probes (heap <64MB at 50k, estimate latency P95<200ms at 100k):
cd backend && ./gradlew test -PrunSlow=true \
  --tests SubscriberExportHeapIT --tests SubscriberExportEstimateLatencyIT
```

> Live-перевірки, що НЕ покриваються ITs (рендер кирилиці у спредшиті, реальний SMTP, ngrok→staging→Mailpit пайплайн email), виконуються Task 17 проти local-staging (`./gradlew bootRun` + ngrok + Mailpit `localhost:8025`) за runbook `docs/staging-smoke/09-subscribers.md`. Якщо staging email-провайдер відсутній — лист перехоплює Mailpit, BOM/кирилицю перевіряємо там же (відкривши вкладений CSV).

---

## Task 10 — Subscribers list + profile pages (verify: smoke + USER)

Smoke (✅ headless-перевірено агентом): `pnpm test` 292/292 (34 файли), `pnpm prebuild` exit 0, `grep -n localStorage frontend/stores/subscribers.ts` порожньо, `git diff HEAD -- frontend/i18n/locales/` порожньо.

> `nuxi typecheck`/`lint` не запускались — у середовищі немає локального `vue-tsc`/`@vue/language-core` і немає `lint`-скрипта (давня прогалина тулчейна, не регресія). Vitest транспілює весь TS/SFC через Vite.

### Передумови для USER-перевірки

Підняти інфраструктуру (див. «Передумови» вгорі), залогінитись, обрати проект, і мати **хоча б одного підписника** — за потреби засіяти через `/start` реальному підключеному боту.

### USER-перевірка в браузері (✅ виконано вручну)

**Список `/projects/{id}/subscribers`:**
1. ✅ Таблиця рендериться, над нею фільтр-бар (пошук, статус-select, сорт-select, дві дати, дві групи тег-чипів include/exclude). Консоль чиста.
2. ✅ Ввести ≥2 символи в пошук → список оновлюється (дебаунс ~300 мс). Ввести 1 символ → список НЕ оновлюється.
3. ✅ Обрати статус «Active» → лишаються активні; обрати тег в «With tags» → лишаються підписники з цим тегом; тег у «Without tags» виключає.
4. ✅ «Load more» з'являється лише коли є наступна сторінка; клік дозавантажує рядки (append, не replace).
5. ✅ Клік по рядку → перехід на `/subscribers/{id}`.

**Профіль `/projects/{id}/subscribers/{id}`:**
6. ✅ Картка особи + бейдж статусу правильного кольору (Active=зелений, Unsubscribed=сірий, Blocked/Deleted=червоний); вкладки Tags / Custom fields / History.
7. ✅ Tags: ввести новий тег у комбобокс → додати → з'явився чип; клік × → зник.
8. ✅ Custom fields: для поля змінити значення → зберегти → тост-підтвердження; перезавантажити профіль → значення збереглося.
9. ✅ History: події видно, новіші зверху.
10. ✅ «Send message»: порожній текст → submit заблокований/не шле; «hello» → тост «надіслано». Для BLOCKED-підписника кнопка disabled з тултіпом.
11. ✅ «Export CSV» → діалог → submit → тост «Export started»; одразу повторити → тост «вже виконується» (409).
12. ✅ 404-редирект: відкрити `/subscribers/{неіснуючий-id}` → редирект назад на список.
13. ✅ Консоль чиста на обох сторінках — без Vue warnings, без missing-key i18n, без unhandled rejections.

> **i18n-прогалина (наслідок Task 2) — ВИПРАВЛЕНО під час Task 17 smoke (F-min-2/4/7):** тости помилок (send-message 429/503, custom-field 422), підтвердження збереження поля, заголовок колонки «Підписався», дати в картці — усі отримали власні ключі (`errors.subscribers.*`, `subscribers.profile.customFields.saved`, `subscribers.columns.subscribedAt`, `subscribers.profile.lifecycle.*`). Деталі у `logs/qa/post-deploy-verification-report.md`.

---

## Task 11 — Tags page + Custom-fields page + Recent exports + Playwright E2E + runbook (verify: smoke + USER)

Поставлено: 3 діалоги тегів (`Create/Rename/DeleteTagDialog`), 3 діалоги кастомних полів
(`Add/Edit/DeleteCustomFieldDialog`), `RecentExportsDialog`, сторінки `/tags` + `/custom-fields`
(повний CRUD + 404-редирект), монтування `RecentExportsDialog` у `subscribers/index.vue` під
маркером `<!-- TASK-11 RecentExportsDialog mount -->`, Vitest-спеки
(`AddTagDialog`, `AddCustomFieldDialog`, `RecentExportsDialog`), Playwright `e2e/subscribers.spec.ts`
+ `e2e/helpers/webhook-fixture.ts`, runbook `docs/staging-smoke/09-subscribers.md`.

### Рев'ю — усі схвалили (звіти в `logs/working/task-11/`)
- code-reviewer: R1 approve (1 major + minors) → R2 **approve**.
- security-auditor: **approve** (0 critical/high; 1 low + 1 info, info виправлено).
- test-reviewer: R1 changes_requested (1 major — бракувало спеку RecentExportsDialog) → R2 **approve**.

13 зауважень опрацьовано: 11 виправлено, 2 свідомо лишено (affected-count копія використовує наявний
ключ колонки — кращого немає без редагування локалей; `console.warn(err)` на list-load — за
прецедентом `subscribers/index.vue`, токенів на цих GET немає).

### Smoke — статичні перевірки (✅ виконано агентом)
- Канонічний slug-regex `^[a-z0-9_-]{1,32}$` — єдиний; `grep "\[a-z\]\[a-z0-9_\]" frontend/ docs/` порожньо.
- Locale-parity uk = en = 348 ключів (Python-дзеркало `check-locales.mjs`); локалі НЕ редаговано.
- Заборонені Task-10 спеки (`SendPersonalMessageDialog`, `SubscribersFilterBar`) не додавалися.
- Усі `~/components/ui/*` імпорти резолвляться; немає висячих посилань; немає дублів `data-test`.

### Smoke — JS-suite НЕ запускався в сесії (прогалина тулчейна) — ЗАПУСТИТИ перед мерджем
У сесії немає Node/pnpm у PATH (лише IntelliJ-Node 16, несумісний з Vitest 3). Запустити там, де є тулчейн:

```bash
cd frontend && pnpm test -- AddTagDialog AddCustomFieldDialog RecentExportsDialog   # 3 нові спеки
cd frontend && pnpm test          # повний прогін — без регресій
cd frontend && pnpm prebuild      # locale-parity gate (очікувано exit 0)
rg -n "\[a-z\]\[a-z0-9_\]" frontend/ docs/   # очікувано: порожньо
# E2E (потрібен живий бекенд + seed-env з підключеним ботом; інакше self-skip, НЕ fail):
cd backend && ./gradlew bootRun   # потім, зі встановленими E2E_* env:
cd frontend && pnpm playwright test subscribers.spec.ts --reporter=line
```

> happy-dom 16.8.1 санітизує `<input type=number>` (`"abc"`→`""`) — тест numeric-rejection навмисно
> проганяє "abc" через шлях STRING→NUMBER (reactive computed-schema), тож валідовується збережене
> модельне значення, а не DOM (це не false-green; підтверджено test-reviewer R2).

### USER-перевірка в браузері (✅ виконано вручну; потрібен живий бекенд + підключений бот + підписник)

**`/projects/{id}/tags`:**
1. ✅ `+ Створити тег` → діалог: ввести slug `smoke_test` + назву → створити → рядок з'явився, тост.
2. ✅ Ввести невалідний slug `Smoke Test` (пробіл) → помилка під полем, запит не відправляється.
3. ✅ Перевірити slug `paid-2024` і `2024_vip` (дефіс + цифра на початку) → приймаються.
4. ✅ Rename: змінити лише назву (slug read-only, з пунктирним cue) → збережено.
5. ✅ Delete: рядок «Підписників: N» показано (включно з 0), typed-confirm (ввести slug) розблоковує
   кнопку → видалено.

**`/projects/{id}/custom-fields`:**
6. ✅ `Додати поле` → по черзі кожен тип: string (text), number (number-input), boolean (tri-state
   select unset/✓/✕), date (date-picker) → defaultValue-інпут змінюється за типом.
7. ✅ number + defaultValue `abc` → помилка валідації; валідне → створено.
8. ✅ Edit: name + type показані read-only (disabled), редагуються лише label + defaultValue.
9. ✅ Delete: typed-confirm (ввести name) → видалено.

**`/projects/{id}/subscribers` — Recent exports:**
10. ✅ `Останні експорти` → діалог зі статус-бейджами, колонки createdAt/status/rowCount/дії.
11. ✅ DONE з валідним токеном → кнопка **Завантажити** (лінк); протермінований (через `mongosh`
    `expiresAt`=минуле) → **Оновити посилання** → новий робочий лінк.
12. ✅ Export CSV → Download → відкрити у спредшиті: UTF-8 BOM + кирилиця «Київ» рендеряться.
13. ✅ Консоль чиста на `/tags`, `/custom-fields`, при відкритті RecentExportsDialog.

> Повний наскрізний lifecycle (реальний бот + ngrok + email + signed-URL refresh + key-rotation) —
> у `docs/staging-smoke/09-subscribers.md` (виконує Task 17).

### Відхилення (адаптація до реального бекенду — деталі в `decisions.md`)
- Немає `GET /tags/{slug}` → DeleteTagDialog бере affected-count із завантаженого рядка.
- Custom-field DELETE → 204 без affected-count → загальний cascade-текст без числа.
- `ExportResponse` має `rowCount` (не filename/size); кнопка перемикається за наявністю `downloadUrl`.
- `listExports` повертає лише DONE та ігнорує `limit` (контракт Task 9) → гілки PENDING/RUNNING/
  FAILED/PURGED forward-compatible + покриті юніт-тестом, але поки не досяжні в проді (звірити з Task 9).
- Health-endpoint — `/health` (Actuator не на classpath), не `/actuator/health`.
- Manual-unsubscribe без confirm-модалу (Task 10 — пряма дія).
- BOOLEAN default — tri-state `<select>` (не checkbox), бо `defaultValue=null` має бути представимим.

### i18n-прогалини — ВИПРАВЛЕНО під час Task 17 smoke (F-min-6/7)
`validation.{tagSlugPattern,customFieldNamePattern,...}` (zod-повідомлення тег/поле/повідомлення),
`errors.tags.*` / `errors.customFields.*` / `errors.subscribers.exports.refresh.*` (per-code тости),
`customFields.editImmutableWarning`, `tags.columns.created` / `customFields.columns.created` (колонки
додані). Локалі тепер редаговано (uk = en, parity-gate зелений). Деталі у
`logs/qa/post-deploy-verification-report.md` (F-min-7). Лейбл «оновити список» у RecentExports —
свідомо лишено (модель close-reopen, кнопки нема).
