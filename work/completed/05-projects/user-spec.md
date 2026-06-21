---
# Creation date (YYYY-MM-DD)
created: 2026-05-09

# Status: draft | approved
status: approved

# Work type: feature | bug | refactoring
type: feature

# Feature size: S (1-3 files, local fix) | M (several components) | L (new architecture)
size: L
---

# User Spec: 05-projects

## Что делаем

Додаємо концепцію Project (Workspace) — ізольований простір користувача,
у якому далі житимуть боти, підписники, воронки, розсилки і налаштування.
Один user може мати до **5 активних проектів**; CRUD через REST під
`/api/v1/projects`, soft-delete з 7-денним grace period і JobRunr cron'ом
для hard-delete. Frontend: селектор проектів у topbar, сторінки списку та
налаштувань, inline empty-state на `/dashboard` для нових користувачів.

Це перший `/api/v1/...` модуль і **isolation primitive** для всіх
наступних епіків (04-bots, 05-subscribers, 06-funnels, 07-broadcasts,
08-api, 09-analytics) — кожен з них монтуватиметься під
`/api/v1/projects/{projectId}/...` і проходитиме через спільний
access-guard цього модуля.

Frontend persistence — мінімальна: `localStorage` зберігає тільки
`currentProjectId` для cross-restart (без `storage` events і
cross-tab sync); решта стану — in-memory Pinia на час сесії вкладки.

## Зачем

**Primary use-case:** власник малого бізнесу, який веде декілька брендів,
повинен тримати аудиторії та воронки кожного бренду окремо. Без
ізоляції всі підписники, теги, розсилки лежать в одній купі — той самий
Telegram-юзер, що пише і в "Acme Coffee", і в "Brand X Books", отримує
змішані сценарії, теги стикаються, розсилка йде не на ту аудиторію.

**Secondary use-cases:** агенції, які запускають боти для багатьох
клієнтів (та сама ізоляція), та розробники, які прототипують і хочуть
тримати dev/test/prod-боти окремо.

Без цієї фічі multi-bot/multi-brand модель неможлива. Це фундамент, без
якого всі наступні фічі (підписники, воронки, розсилки) не мають сенсу.

## Как должно работать

### Сценарій 1 — перший проект після реєстрації / логіну з 0 проектами

1. Юзер реєструється або логіниться — auto-login виводить його на `/dashboard`.
2. `/dashboard` рендерить **inline empty-state** "Create your first project"
   з primary CTA — окрему сторінку `/onboarding` НЕ робимо.
3. Юзер тисне CTA → форма `/projects/new`: name (3-50 символів, обов'язково),
   description (опц., ≤200), timezone (default — TZ браузера, dropdown із
   `Intl.supportedValuesOf('timeZone')`).
4. Submit → `POST /api/v1/projects` → 201, проект додається у Pinia store,
   `currentProjectId` встановлено, редірект на `/dashboard` з нормальним
   контентом, селектор у topbar показує назву проекту.

### Сценарій 2 — повернення в наступну сесію

1. Юзер з ≥1 активним проектом залогінився / відкрив додаток.
2. Pinia fetch'ить `GET /api/v1/projects` (тільки активні).
3. Auto-select:
   - якщо в `localStorage['bot-funnel.currentProjectId']` є ID і він
     **присутній** у fetched-списку як активний → обираємо його;
   - інакше fallback: `currentProjectId = projects[0]` (sorted `createdAt
     desc`, тобто найновіший).
4. Якщо хоче інший — клікає селектор і переключається. На вибір
   пишемо `localStorage.setItem('bot-funnel.currentProjectId', id)`
   (під `import.meta.client` гейтом). Інша вкладка цього **не побачить
   у real-time** — `storage` event listener свідомо НЕ додається.
   Cross-tab independence — це фіче, не баг (multi-bot user керує Acme
   у tab X, Brand Y у tab Z паралельно).

### Сценарій 3 — створення додаткового / перемикання / налаштування

1. У селекторі: список активних проектів (sorted createdAt desc) +
   "+ Create new project" + "Project settings" (видимо коли
   `currentProject != null`).
2. Settings → загальна секція (rename, опис, timezone) і Danger zone
   (видалити проект з вводом назви).
3. PATCH update → 200 + оновлений ресурс. Останній write виграє
   (last-write-wins, без `If-Match`/optimistic locking — свідомо для MVP).

### Сценарій 4 — soft-delete і восстановлення

1. Settings → Danger zone → "Delete project". Модал просить **ввести
   назву проекту дослівно** для підтвердження (case-sensitive
   trim-compare). Кнопка Delete задизейблена доки текст не збігається.
2. `DELETE /api/v1/projects/{id}` → 200, `deletedAt = now()`. Проект
   зникає з активного списку і з'являється в `/projects` під секцією
   "Recently deleted (X days remaining)" (countdown computed client-side
   з `deletedAt + 7d`).
3. Якщо видалений був `currentProject` — store автоматично переключається
   на інший активний (або emptу-state, якщо жодного).
4. Restore: `POST /api/v1/projects/{id}/restore`:
   - Якщо активних `< 5` → 200, `deletedAt = null`. Якщо ім'я конфліктує
     з активним проектом цього ж юзера — сервер автоматично додає
     суфікс `" (restored)"` до назви відновленого і пише через `events`.
   - Якщо активних `== 5` → 422 `project_limit_reached`, toast: "ліміт
     5 активних — видаліть один щоб відновити". Свідома консистентність
     з create-flow ліміт.
5. Через 7 днів JobRunr cron `hard-delete-projects` (daily 03:00 UTC)
   видаляє проект назавжди + `events` де `metadata.projectId == id`.
   Restore після hard-delete неможливий (404 — записа немає).

### Сценарій 5 — захист від чужих проектів

1. Юзер вгадує URL `/api/v1/projects/{foreign-id}` (чужий проект,
   неіснуючий, або malformed) → **404 уніформно** для всіх трьох
   випадків. Anti-enumeration: ніколи не показуємо що проект існує але
   "не твій" (це деривація від workflow doc, де було 403 — свідомо
   замінили).
2. Не залогінений юзер — 401 на будь-який `/api/v1/projects/*`.

### Сценарій 6 — захист від stale state в межах сесії

1. Якщо `currentProjectId` (in-memory або з localStorage при старті)
   стає невалідним (проект soft-deleted з іншого браузера / API-ключа),
   API-запит повертає 404.
2. Frontend interceptor: на 404 від `/api/v1/projects/{currentProjectId}`
   (точне співпадіння шляху) — скидає `currentProjectId` у Pinia,
   **видаляє ключ з localStorage** (defensive cleanup), робить refetch
   списку, auto-select заново, показує toast "Цей проект більше
   недоступний".
3. Жодного cross-tab sync, жодного polling — кожна вкладка незалежна,
   404 — джерело правди.

## Критерии приёмки

### Backend (REST + persistence)

- [ ] **AC-1:** `POST /api/v1/projects` з валідним body
  (`name=3-50 chars`, `timezone=IANA`, опц. `description<=200`) →
  201 + повне Project body, emit audit event `project_created`.
- [ ] **AC-2:** `POST /api/v1/projects` з невалідним `name`:
  - відсутнє / порожнє → 400, message містить `name`
  - `length < 3` або `> 50` → 400, message містить `name`
  Bean-validation помилки повертаються через `WebExchangeBindException` →
  `{message, code: null}` (поточний контракт `GlobalErrorHandler`); тести
  асертять статус і presence поля у message, не code.
- [ ] **AC-3:** `POST /api/v1/projects` з невалідним `timezone`:
  - відсутнє / порожнє → 400, message містить `timezone`
  - значення не в `ZoneId.getAvailableZoneIds()` (наприклад `"GMT+5"`,
    `"+02:00"`, `"NotAZone"`) → 400, message містить `timezone`
  Bean-validation, як AC-2.
- [ ] **AC-3b:** `POST /api/v1/projects` з `description.length > 200`
  → 400, message містить `description`.
- [ ] **AC-4:** `POST /api/v1/projects` коли user уже має 5 АКТИВНИХ
  → 422, code `project_limit_reached`.
- [ ] **AC-5:** `POST /api/v1/projects` з `name`, що збігається з
  АКТИВНИМ проектом цього юзера → 409, code `project_name_taken`.
- [ ] **AC-6:** `GET /api/v1/projects` (без query) → масив **тільки
  власних активних** проектів, sorted `createdAt desc`.
- [ ] **AC-7:** `GET /api/v1/projects?include_deleted=true` →
  активні + soft-deleted (кожен рядок несе `deletedAt`).
- [ ] **AC-8:** `GET /api/v1/projects/{foreign-id}` → 404.
- [ ] **AC-9:** `GET /api/v1/projects/{malformed-id}` → 404 (uniform).
- [ ] **AC-10:** `GET /api/v1/projects/{soft-deleted-own-id}` за
  замовчуванням → 404 (single-resource GET тримаємо чистим; soft-deleted
  видно тільки в list-режимі з `?include_deleted=true`).
- [ ] **AC-11:** Будь-який `/api/v1/projects/*` без сесії → 401.
- [ ] **AC-12:** `PATCH /api/v1/projects/{id}` з валідним body → 200 +
  updated project; emit `project_renamed` якщо ім'я змінилось, інакше
  `project_updated`.
- [ ] **AC-12b:** `PATCH /api/v1/projects/{id}` з `name`, що збігається з
  іншим АКТИВНИМ проектом цього юзера → 409, code `project_name_taken`
  (service-thrown, як і create-flow). Перейменування у власне ім'я (no-op
  rename) не вважається конфліктом.
- [ ] **AC-13:** `DELETE /api/v1/projects/{id}` → 200, `deletedAt = now()`,
  emit `project_soft_deleted`. Проект зникає з default-list, з'являється
  в `?include_deleted=true`.
- [ ] **AC-14:** `POST /api/v1/projects/{id}/restore` на soft-deleted
  власному проекті при `active < limit` → 200 + project з `deletedAt = null`,
  emit `project_restored`. Якщо ім'я конфліктує з активним — сервер
  автоматично додає суфікс `" (restored)"` перед save і фіксує цей факт
  у `events.metadata.renamedDueToConflict = true`.
- [ ] **AC-14b:** Коли response від `/restore` містить
  `renamedDueToConflict: true` (або UI бачить що `response.name !== request
  context name`) — frontend показує informational toast з ключем
  `errors.projects.restore.renamedDueToConflict` (UA: "Назва вже зайнята
  активним проектом — ми додали суфікс « (restored)»"; EN: "Name is taken
  by an active project — we appended « (restored)»").
- [ ] **AC-15:** `POST /api/v1/projects/{id}/restore` коли активних
  `== limit` → 422, code `project_limit_reached`.
- [ ] **AC-16:** JobRunr recurring job `hard-delete-projects`
  зареєстрований з cron `0 3 * * *` UTC.
- [ ] **AC-17:** Cron permanently видаляє projects де
  `deletedAt < now-7d`, плюс events де `metadata.projectId ∈ deleted set`.
  Emit `project_hard_deleted` per кожен видалений проект.
- [ ] **AC-17b:** Подія `project_hard_deleted` сама **виживає каскад**:
  після cron-run в колекції `events` присутній рядок з
  `eventType="project_hard_deleted"` для кожного видаленого проекту,
  з `metadata.projectId = <deleted-id>` і `userId = <owner>`. Інтеграційний
  тест перевіряє це явно.
- [ ] **AC-17c:** Cron-run пише структурований INFO log один раз на запуск
  (навіть якщо нічого не видалено), з полями `deletedCount`,
  `eventsRemovedCount`, `runDurationMs`. Зразок: `INFO ProjectHardDeleteJob
  - run completed: deletedCount=0 eventsRemovedCount=0 runDurationMs=12`.
  Без цього зеро-running дні невидимі в operations.

### Frontend

- [ ] **AC-18:** Логін з 0 проектами → `/dashboard` показує
  full-width "Create your first project" empty-state з primary CTA.
- [ ] **AC-19:** Логін з ≥1 проектом → `/dashboard` показує welcome
  content; topbar має ProjectSelector з активним списком.
- [ ] **AC-20:** Auto-select на кожен fresh session:
  - якщо в `localStorage['bot-funnel.currentProjectId']` є ID, який
    присутній у fetched-списку як активний → `currentProjectId = <той-самий>`;
  - інакше → `currentProjectId = projects[0]` (sorted `createdAt desc`).
  Селектор показує обраний проект без явного user-action.
- [ ] **AC-21:** Перемикання у селекторі оновлює in-memory store **і**
  пише в localStorage (`localStorage.setItem('bot-funnel.currentProjectId',
  id)` під `import.meta.client` гейтом для SSR-safety). Інша відкрита
  вкладка цього не побачить — `storage` event listener свідомо
  відсутній; кожна вкладка має свій незалежний `currentProjectId`.
  При наступному відкритті браузера (нова сесія) localStorage читається
  знову — див. AC-20.
- [ ] **AC-22a:** Settings page rename: ввід нового імені → submit →
  PATCH → 200 → селектор у topbar показує нову назву одразу (без
  перезавантаження сторінки).
- [ ] **AC-22b:** Settings page опис: текстове поле (0-200), submit
  пустий → опис очищається у store і у відповіді бекенду (зберігається
  як `null` у Mongo).
- [ ] **AC-22c:** Settings page timezone: dropdown показує IANA-список
  (Intl.supportedValuesOf), submit → PATCH → 200; невалідне значення
  на бекенді (через прямий API, не через UI) → 400 з message про timezone.
- [ ] **AC-22d:** Settings page Danger zone delete: модал відкривається
  по кліку, кнопка Delete задизейблена доки `input.trim() === project.name`
  (case-sensitive); після підтвердження — DELETE → 200 → редірект з
  settings (бо проект тепер soft-deleted), toast.
- [ ] **AC-23:** `/projects` рендерить секцію "Active" + (якщо є хоч
  один soft-deleted) секцію "Recently deleted (X days remaining)" з
  кнопкою Restore.
- [ ] **AC-24:** 404-interceptor сфокусований **виключно** на single-resource
  GET/PATCH/DELETE для поточного проекту: 404 від
  `GET|PATCH|DELETE /api/v1/projects/{currentProjectId}` (точне співпадіння
  шляху) тригерить: clear `currentProjectId` у Pinia →
  `localStorage.removeItem('bot-funnel.currentProjectId')` (defensive
  cleanup) → refetch списку → auto-select (per AC-20) → toast. 404 з
  інших endpoint-ів (наприклад, `/api/profile/...`, майбутні `/api/v1/...`
  не пов'язані з projectId) НЕ перехоплюється цим інтерцептором.
  Майбутні `/api/v1/projects/{currentProjectId}/{module}/...` додаються до
  цього шляху-префіксу окремими епіками — у них своя логіка обробки 404
  (підписники не існують, бот не доступний, тощо), не наша.
- [ ] **AC-25:** i18n parity: всі нові ключі `projects.*`,
  `errors.projects.*`, `layout.*`, `validation.*` присутні в обох
  `uk.json` і `en.json` ЯВНО (не only "shape parity"). Зокрема ключі для
  AC-14b (`errors.projects.restore.renamedDueToConflict`), типу-назви
  модала (`errors.projects.delete.confirmTypeName`), стале-стейт тосту
  (`errors.projects.unavailable`), tooltip-у ліміту
  (`projects.create.limitReachedTooltip`). `pnpm build` (locale parity
  gate) проходить.

### Quality gates (CI)

- [ ] **AC-26:** `gradle test` зелений (backend unit + integration з
  Testcontainers real Mongo).
- [ ] **AC-27:** `pnpm test` зелений (vitest).
- [ ] **AC-28:** `pnpm test:e2e` зелений (Playwright golden path).
- [ ] **AC-29:** `pnpm build` зелений (locale parity gate enforced).

### UI limit enforcement (workflow MUST HAVE)

- [ ] **AC-30:** Коли `projectsStore.projects.length >= 5`, кнопка
  "+ Create new project" (у селекторі і на `/projects`) візуально
  задизейблена і має tooltip про ліміт 5 активних та як звільнити слот.

### Documentation alignment

- [ ] **AC-31:** `.claude/skills/project-knowledge/references/ux-guidelines.md`
  оновлений у тому ж PR: рядок 33 "full-page onboarding..." → "inline
  empty-state on `/dashboard`". Рядок 43 (про `localStorage`) лишається
  як є — наша імплементація відповідає (cross-restart persistence без
  cross-tab sync). Щоб наступні фічі читали актуальний стан.

## Ограничения

- **Hard-cap 5 активних проектів на user** — фіксована константа
  `app.projects.max-per-user` (env override `PROJECTS_MAX_PER_USER`).
  Per-user конфігурованість лімітів — окремий billing/plans епік
  (post-MVP). Soft-deleted **НЕ рахуються** в ліміт.
- **Soft-delete grace = 7 днів.** JobRunr daily cron робить hard-delete.
  Restore після hard-delete неможливий.
- **Stack — реактивний:** Java 21 + Spring WebFlux + reactive Mongo +
  spring-session-data-mongodb. JobRunr — recurring через `@Recurring`
  з MongoDB storage; auto-distributed-lock покриває multi-replica.
- **`project_id` завжди в URL path** (`/api/v1/projects/{projectId}/...`),
  ніколи не виводиться з сесії на бекенді. Кожен майбутній endpoint
  тут проходить через `ProjectService.requireOwned(ownerId, projectId)` —
  спільний access-guard для всієї платформи.
- **404 уніформно** на всі "not yours / not found / malformed"
  (anti-enumeration). Свідома деривація від workflow doc, де було 403.
- **Унікальність name тільки серед АКТИВНИХ** (per-owner). Soft-deleted
  не блокують ім'я (consistent with "not counted toward limit").
  Партіальний унікальний індекс Spring Data MongoDB `@CompoundIndex`
  не підтримує — enforce на service-layer (race-window 10-60ms, обмежено
  бізнес-кепом 5 активних, не швидкістю перевірки — див. Риск 3).
- **Frontend persistence — мінімальна:** localStorage зберігає тільки
  `currentProjectId` (cross-restart). Cross-tab sync через `storage`
  events не реалізується — різні вкладки можуть мати різний поточний
  проект (це фіче для multi-bot users). Жодних інших Pinia-станів у
  localStorage — список проектів, кеш та інше живе в памʼяті per
  session. SSR-safety через `import.meta.client` гейт.
- **Concurrency last-write-wins** на rename/update. Без `@Version`,
  без `If-Match`. Прийняті трейд-офи; revisit при появі collaboration
  епіку.
- **Cascade scope hard-delete на сьогодні** — тільки сам project +
  `events` з `metadata.projectId`. Інших project-scoped колекцій ще
  немає; кожен майбутній епік (04-bots, 05-subscribers, ...) реєструє
  свій cleanup сам.
- **Залежність — 02-auth (готова):** authenticated session,
  `ReactiveSecurityContextHolder`, CSRF cookie/header, `auth.global.ts`
  middleware. Жодних змін у цих модулях не потрібно.
- **Production index management поза scope.** В dev
  `auto-index-creation=true` створює індекси при startup. У prod
  (`application-prod.properties` ще не існує) індекси треба буде
  застосовувати вручну — це турбота окремого deploy-prep епіку.

## Риски

- **Риск 1 (HIGH) — data leakage між проектами / користувачами.**
  **Митигація:** `ProjectService.requireOwned(ownerId, projectId)`
  обов'язковий entry point для **кожного** project-scoped handler-а,
  включно з майбутніми епіками. Integration test з двома різними
  юзерами перевіряє 404 уніформно. Code review check-list окремим
  пунктом включає "all `/api/v1/projects/{id}/*` go through
  requireOwned".

- **Риск 2 (MEDIUM) — mass-assignment через unknown JSON fields**
  (наприклад, клієнт пише `"ownerId": "<other-user>"` у body).
  **Митигація:** усі DTO мають `@JsonIgnoreProperties(ignoreUnknown=true)`
  (стандарт по `profile/dto`). Сервіс **ніколи** не читає `ownerId` з
  request body — тільки з SecurityContext.

- **Риск 3 (MEDIUM) — Mongo `@CompoundIndex` partial filter не
  підтримується**, тому уникальність `(ownerId, name)` серед активних
  залежить від service-level pre-check (race-window 10-60ms у
  multi-replica/reactive стек, не "мікросекунди"). **Митигація:**
  захищає не швидкість перевірки, а **бізнес-обмеження 5 активних
  проектів** — користувачу неможливо запустити 50 паралельних POST
  з UI; якщо колізія з'явиться у проді через автоматизований клієнт —
  fallback на programmatic `IndexOps.ensureIndex` з
  `partialFilterExpression`.

- **Риск 4 (LOW) — race "hard-delete cron vs. user clicks Restore".**
  **Митигація:** 404 на `POST /restore` обробляється тим же 404-flow
  (toast + refetch). Race-вікно — мілісекунди.

- **Риск 5 (LOW) — JobRunr 03:00 cron колізія з існуючим
  `HardDeleteJob` (users).** JobRunr серіалізує recurring jobs
  внутрішньо. **Митигація:** observable у JobRunr dashboard; якщо
  колізія стане проблемою — рознесемо cron-час.

## Технические решения

- **Перший `/api/v1/...` модуль.** Існуючі `/api/auth/**` і
  `/api/profile/**` лишаються де є (не версіонуються). Майбутні модулі
  (bots, subscribers, ...) монтуються під
  `/api/v1/projects/{projectId}/...`.

- **Access-guard на сервіс-рівні** з єдиною точкою входу для всіх
  майбутніх епіків. Альтернатива (Spring Security `@PreAuthorize`
  expression) розглянута і відкинута: вимагає додаткової інфраструктури
  Spring Expression Language та неявного reactivity через
  `ReactiveAuthorizationManager` — overkill при поточних масштабах.

- **404 уніформне** на foreign / soft-deleted / malformed projectId
  (anti-enumeration). Свідома деривація від workflow doc, де було 403.

- **Strict IANA timezone validation** через
  `ZoneId.getAvailableZoneIds().contains(tz)`. Pure `ZoneId.of()`
  пропускає "GMT+5", "+02:00" — для розкладу розсилок це буде помилкою,
  тому вирізаємо одразу.

- **Bean validation → 400 без `code`, business rules → service-throw з
  кодом.** Поточний `GlobalErrorHandler` повертає `code: null` для
  `WebExchangeBindException`. Codes (`project_limit_reached`,
  `project_name_taken`) кидаємо явно з сервісу через
  `AppException.badRequest|conflict|...`. Frontend `useApiError` падає
  на `errors.projects.{action}.{status}` коли code відсутній — UX
  цілісний.

- **Cascade scope hard-delete сьогодні — тільки `events` з
  `metadata.projectId`.** Жодних бот/subscriber/funnel-моделей ще не
  існує; child-епіки додадуть свої cleanup-механізми (event listener
  на `project_hard_deleted` або власний recurring job). Не винаходимо
  infrastructure наперед. Сама подія `project_hard_deleted` per-user
  — НЕ видаляється в каскаді.

- **Frontend store — Pinia in-memory + мінімальний localStorage** для
  `currentProjectId` (cross-restart only). Cross-tab events свідомо
  не слухаємо. 404 interceptor сфокусований на single-resource
  project-endpoint (точний шлях `/api/v1/projects/{id}`, без wildcard);
  інші 404 не перехоплюються. На 404 interceptor також чистить ключ з
  localStorage (defensive).

- **Single list endpoint з `?include_deleted=true`** замість окремого
  `/projects/deleted` — простіше для UI ("Recently deleted" як секція
  на тій же сторінці), один кеш-кей.

- **Inline empty-state на `/dashboard`** замість окремої `/onboarding`
  сторінки — мінімум навігаційного шуму.

- **Реалізаційні деталі (класи, методи, файли) — у tech-spec.** Тут
  фіксуємо тільки рішення, не назви.

### Свідомі деривації від workflow / PK

| Що в workflow / PK | Наше рішення тут | Причина |
|---|---|---|
| 403 на чужий проект (workflow:69) | 404 уніформно | anti-enumeration |
| Full-page onboarding для 0 проектів (ux-guidelines:33) | Inline empty-state на `/dashboard` | менше навігаційного шуму |
| Каскад на bots/subscribers/funnels/broadcasts/keys (workflow:27) | Тільки events; child-епіки самі | ці колекції не існують |
| Cross-tab sync поточного проекту через `storage` event | НЕ реалізуємо | multi-tab independence — фіче, не баг |

`workflow/03-проекти/README.md:41` ("останній обраний у `localStorage`")
і `ux-guidelines.md:43` (та ж вимога) — наше рішення відповідає
(cross-restart persistence є). `ux-guidelines.md:33` — оновлюється у
тому ж PR (AC-31).

## Тестирование

**Unit-тести:** робляться завжди, не обговорюються. Покривають:
limit-логіку (5 активних vs N soft-deleted counting), `ValidTimezone`
gate (Europe/Kyiv → ok, GMT+5 → reject), `requireOwned` happy/sad
paths, name-conflict resolver на restore (suffix appending),
JobRunr cron query (`findByDeletedAtBefore`).

**Інтеграційні тести:** робимо. `@SpringBootTest(webEnvironment = RANDOM_PORT)`
+ `WebTestClient` + Testcontainers Mongo (per `patterns.md` — без
mocking БД). Кейси: всі CRUD endpoints; 401 без сесії; 404 на
foreign / malformed / soft-deleted projectId; 422 на 6-й проект і
restore при ліміті; 409 на duplicate active name; soft-delete +
ручний trigger cron-job-метода → проект і його events видалені;
restore-with-suffix; spring-session integration через
`WithMockAppUser`; CSRF mutator на POST/PATCH/DELETE.

**E2E тести:** робимо **один** golden-path Playwright spec
`frontend/e2e/projects.spec.ts`: register fresh user через UI →
`/dashboard` empty-state видно → "+ Create" → fill form → редірект →
selector показує проект → create another → switch via dropdown →
settings → rename → soft-delete з name typing → "Recently deleted"
видно → restore → активний знову. Немає per-locale варіантів
(покрито 03-i18n).

## Как проверить

### Агент проверяет

| Шаг | Інструмент | Очікуваний результат |
|-----|------------|---------------------|
| 1. Register fresh user і пройти golden path | Playwright MCP на live URL | `/dashboard` empty-state → create → selector populated → create 2nd → switch → settings rename → soft-delete (з name typing) → "Recently deleted" видно → restore → знову активний. Без console-помилок. |
| 2. 6-й проект (CREATE limit) | `curl -X POST $APP_URL/api/v1/projects -H 'Cookie: SESSION=<5-projects-user>' -d '{"name":"6th","timezone":"Europe/Kyiv"}'` | 422, body `{"message":"...","code":"project_limit_reached"}`. |
| 3. Restore при ліміті | `curl -X POST $APP_URL/api/v1/projects/{soft-deleted-id}/restore -H 'Cookie: SESSION=<5-active-1-deleted-user>'` | 422, code `project_limit_reached`. |
| 4. Чужий projectId | `curl $APP_URL/api/v1/projects/{foreign-objectid} -H 'Cookie: SESSION=<other-user>'` | 404. **НЕ** 403. |
| 5. Malformed projectId | `curl $APP_URL/api/v1/projects/zzz-not-an-objectid -H 'Cookie: SESSION=...'` | 404 уніформно. |
| 6. Без сесії | `curl $APP_URL/api/v1/projects` (без cookie) | 401. |
| 7. Duplicate active name | створити проект → POST з тим же name | 409, code `project_name_taken`. |
| 8. Invalid timezone | POST з `"timezone":"GMT+5"` | 400, message містить `timezone`. (`code` = null — bean-validation pipeline; перевіряємо presence поля в message.) |
| 9. Inspect Mongo | MongoDB MCP: `db.projects.findOne({name:"AVP-test"})`; перевірити поля `ownerId`, `deletedAt: null`, `createdAt`, `timezone`, `name`. | Документ присутній; deletedAt=null на активному, ISO instant на soft-deleted. |
| 10. JobRunr recurring job — автоматично через тести | integration-test асерт на `JobScheduler.getRecurringJobs()` — у списку має бути `id="hard-delete-projects"`, `cron="0 3 * * *"`. (Manual `/jobrunr-dashboard` check лишається опційним post-deploy smoke, не AVP-обов'язком.) | Test зелений; one-time post-deploy check бачить task у dashboard. |
| 11. PK alignment | `grep -n "full-page onboarding" .claude/skills/project-knowledge/references/ux-guidelines.md` | 0 результатів — рядок 33 PK оновлено на "inline empty-state". (Рядок 43 про localStorage свідомо лишається — наша імплементація відповідає.) |

### Пользователь проверяет

- **Локально перед merge:** запустити стек (Docker Compose + backend +
  frontend dev). Зареєструвати юзера, створити 2 проекти A і B, обрати
  B у селекторі. У Chrome DevTools → Application → Local Storage
  переконатися що `bot-funnel.currentProjectId` дорівнює ID проекту B.
  Закрити браузер. Знову відкрити → залогінитися → переконатися що
  селектор показує B (не A — тобто localStorage спрацював, не fallback
  на найновіший). Soft-видалити B з settings → відкрити нову вкладку →
  селектор показує A (бо B вже видалений + 404-interceptor очистив
  localStorage).
- **i18n smoke:** перемкнути локаль на `/en` → перевірити що всі нові
  тексти (selector, settings, danger zone, toast про unavailable
  project, "Recently deleted") переведені і немає видимих ключів типу
  `projects.delete.confirm`.
- **JobRunr dashboard:** після деплою відкрити `/jobrunr-dashboard` →
  Recurring → переконатися що `hard-delete-projects` (cron `0 3 * * *`)
  присутній поряд з існуючим `hard-delete-users`.
