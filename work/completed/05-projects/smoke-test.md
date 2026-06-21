---
created: 2026-05-12
feature: 05-projects
type: smoke-test
scope: manual pre-merge / pre-deploy
---

# Smoke-Test Plan — 05-projects

Ручна перевірка перед merge у `main`. Автоматичні тести (gradle/vitest/Playwright)
вже зелені; тут — те, що людина повинна побачити очима, плюс кілька curl-спотів,
які перевіряють бекендну поведінку без UI.

## 0. Підготовка

### Стек

```bash
# Інфраструктура
docker compose up -d                     # Mongo, Redis, Mailpit
# Backend (окремий термінал)
cd backend && ./gradlew bootRun
# Frontend (окремий термінал)
cd frontend && pnpm dev
```

URL:
- Frontend: http://localhost:3000
- Backend: http://localhost:8080
- Mailpit (для verification email): http://localhost:8025
- JobRunr dashboard: http://localhost:8000/dashboard (порт див. в `application.properties`)

### Чисті дані

```bash
# Дроп колекцій projects + events перед запуском (через mongosh)
mongosh "mongodb://localhost:27017/botfunnel" --eval \
  'db.projects.drop(); db.events.deleteMany({eventType:/^project_/});'
```

### Користувачі для тесту

Заздалегідь зареєструй 2 окремих юзери через UI (`/auth/register` → підтвердити через Mailpit):
- `userA@test.local` — основний
- `userB@test.local` — для cross-user isolation check

---

## 1. UI golden-path (10 хв)

| # | Крок | Очікуваний результат | AC |
|---|------|---------------------|----|
| 1.1 | Логін як `userA` (нуль проектів) → `/dashboard` | Full-width "Create your first project" empty-state з primary CTA. Topbar **без** ProjectSelector. | AC-18 |
| 1.2 | Клік на CTA → форма `/projects/new` | Timezone-dropdown за замовчуванням = TZ браузера. Поля name/description порожні. | Сценарій 1 |
| 1.3 | Заповнити `name="Acme Coffee"`, опис порожній, timezone `Europe/Kyiv` → Submit | Редірект на `/dashboard` з welcome content. У topbar з'явився ProjectSelector з "Acme Coffee". | AC-1, AC-19 |
| 1.4 | Відкрити DevTools → Application → Local Storage → `bot-funnel.currentProjectId` | Значення = ID створеного проекту (ObjectId hex). | AC-21 |
| 1.5 | Селектор → "+ Create new project" → `name="Brand X Books"` → submit | У селекторі два проекти, sorted createdAt desc (Brand X згори). | AC-19 |
| 1.6 | У селекторі переключитися на "Acme Coffee" | Селектор показує "Acme Coffee". `localStorage` оновився. | AC-21 |
| 1.7 | Закрити вкладку, відкрити нову → `localhost:3000/dashboard` | Селектор автоматично показує "Acme Coffee" (не Brand X — localStorage перебив "найновіший"). | AC-20 |
| 1.8 | Селектор → Project settings → перейменувати "Acme Coffee" → "Acme HQ" → Save | 200, toast success, селектор у topbar **одразу** показує "Acme HQ" без перезавантаження. | AC-22a |
| 1.9 | Settings → опис = `"Brand for premium coffee"` → Save → перезайти на settings | Опис у формі. Очистити поле → Save → повторно відкрити форму → опис порожній. У Mongo `db.projects.findOne({name:"Acme HQ"}).description` має бути `null`. | AC-22b |
| 1.10 | Settings → timezone dropdown → обрати `America/New_York` → Save | 200, toast. У Mongo `timezone == "America/New_York"`. | AC-22c |
| 1.11 | Settings → Danger zone → "Delete project" → модал відкрився | Кнопка Delete **disabled**. Ввести "Acme HQ" — кнопка стала active. Ввести "acme hq" (lowercase) — знов **disabled** (case-sensitive). | AC-22d |
| 1.12 | Ввести точно "Acme HQ" → Confirm Delete | DELETE 200 → редірект з settings, toast. У селекторі залишився тільки "Brand X Books" (auto-select на нього). | AC-22d, Сценарій 4 |
| 1.13 | Перейти на `/projects` | Секція "Active" з "Brand X Books"; секція "Recently deleted (7 days remaining)" з "Acme HQ" + кнопка Restore. | AC-23 |
| 1.14 | Клікнути Restore на "Acme HQ" | 200, проект повертається в Active, секція "Recently deleted" зникає (більше soft-deleted немає). | AC-14 |

---

## 2. Edge-кейси UI (5 хв)

| # | Крок | Очікуваний результат | AC |
|---|------|---------------------|----|
| 2.1 | Створити ще 3 проекти (вже є Acme HQ + Brand X = 5 активних разом). У селекторі / на `/projects` дивитися на кнопку "+ Create new project". | Кнопка **візуально disabled**, tooltip з повідомленням про ліміт 5 активних і як звільнити слот. | AC-30 |
| 2.2 | Спробувати все одно надіслати `POST /api/v1/projects` через UI (наприклад прибрати `disabled` атрибут у DevTools і клікнути). | Toast із помилкою "ліміт 5 активних". В UI стан не змінюється. | AC-4 |
| 2.3 | Soft-delete один проект → активних 4 → спробувати створити новий з ім'ям видаленого. | 201 success: ім'я можна реюзати, бо унікальність тільки серед активних. | (constraints) |
| 2.4 | Створити "Test Restore" → soft-delete → створити **новий активний** з ім'ям "Test Restore" → на `/projects` → Restore. | Restored project має name "Test Restore (restored)". З'являється **informational toast** з ключем `errors.projects.restore.renamedDueToConflict`. | AC-14b |
| 2.5 | Підняти активних до 5 → soft-delete будь-який → ще раз заповнити до 5 (тепер є 5 active + 1 soft-deleted) → клікнути Restore на soft-deleted. | Toast 422: "ліміт 5 — видаліть один щоб відновити". | AC-15 |
| 2.6 | Stale-state: відкрити 2 вкладки `userA`. У вкладці №1 soft-delete `currentProject`. У вкладці №2 (де він ще current) клікнути будь-куди де піде запит до цього project-id (наприклад відкрий settings цього проекту). | Toast "Цей проект більше недоступний". Селектор переключається на інший активний. `localStorage` ключ очищено (перевір DevTools). | Сценарій 6, AC-24 |
| 2.7 | Переключити локаль на EN (`/en/...`) → пройти швидко selector → settings → danger zone → "Recently deleted". | Усі тексти англійською. Жодного видимого ключа типу `projects.delete.confirm` чи `errors.projects.unavailable` на сторінці. | AC-25 |

---

## 3. API spot-checks (curl) — 5 хв

### CSRF — без цього все летить у 403

Spring Security CSRF: на **POST/PATCH/DELETE** треба:
- cookie `XSRF-TOKEN` (Spring встановлює його на будь-який GET через `CookieServerCsrfTokenRepository`)
- header `X-XSRF-TOKEN` з тим самим значенням

GET-запити CSRF не потребують (3.1, 3.6, 3.7, 3.8, 3.9 — без хедера).

### Підготовка cookie+token

Варіант A — **з браузера** (швидко): DevTools → Application → Cookies → `localhost:8080` (або 3000, бо frontend проксі на той самий origin). Скопіювати значення `SESSION` і `XSRF-TOKEN`.

```bash
SESSION="<paste-SESSION-userA>"
XSRF="<paste-XSRF-TOKEN-userA>"
SESSION_B="<paste-SESSION-userB>"
XSRF_B="<paste-XSRF-TOKEN-userB>"
BASE="http://localhost:8080"
```

Варіант B — **через curl cookie jar** (відтворюваний скрипт):

```bash
JAR=/tmp/cookies-userA.txt
# крок 1: логін → отримаємо SESSION + XSRF-TOKEN у jar
curl -s -c $JAR -X POST $BASE/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"userA@test.local","password":"<pwd>","rememberMe":false}' > /dev/null
# крок 2: дістати XSRF-TOKEN зі jar
XSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' $JAR)
echo "XSRF=$XSRF"
# далі: усі мутації йдуть з `-b $JAR -H "X-XSRF-TOKEN: $XSRF"`
```

### Запити

| # | Запит | Очікуване | AC |
|---|------|-----------|----|
| 3.1 | `curl -i $BASE/api/v1/projects` (без cookie) | `401 Unauthorized` | AC-11 |
| 3.2 | `curl -i -X POST $BASE/api/v1/projects -H "Cookie: SESSION=$SESSION; XSRF-TOKEN=$XSRF" -H "X-XSRF-TOKEN: $XSRF" -H "Content-Type: application/json" -d '{"name":"x","timezone":"Europe/Kyiv"}'` | `400`, message містить `name` (length < 3) | AC-2 |
| 3.3 | `curl -i -X POST $BASE/api/v1/projects -H "Cookie: SESSION=$SESSION; XSRF-TOKEN=$XSRF" -H "X-XSRF-TOKEN: $XSRF" -H "Content-Type: application/json" -d '{"name":"GoodName","timezone":"GMT+5"}'` | `400`, message містить `timezone`, `code: null` | AC-3 |
| 3.4 | Створити проект `name="DupeTest"` через UI. Потім: `curl -i -X POST $BASE/api/v1/projects -H "Cookie: SESSION=$SESSION; XSRF-TOKEN=$XSRF" -H "X-XSRF-TOKEN: $XSRF" -H "Content-Type: application/json" -d '{"name":"DupeTest","timezone":"Europe/Kyiv"}'` | `409`, body `{"code":"project_name_taken", ...}` | AC-5 |
| 3.5 | Створити 5 активних → 6-й: `curl -i -X POST $BASE/api/v1/projects -H "Cookie: SESSION=$SESSION; XSRF-TOKEN=$XSRF" -H "X-XSRF-TOKEN: $XSRF" -H "Content-Type: application/json" -d '{"name":"Sixth","timezone":"Europe/Kyiv"}'` | `422`, body `{"code":"project_limit_reached", ...}` | AC-4 |
| 3.6 | `curl -i $BASE/api/v1/projects/507f1f77bcf86cd799439011 -H "Cookie: SESSION=$SESSION"` (GET — без CSRF) | `404` | AC-8 |
| 3.7 | `curl -i $BASE/api/v1/projects/zzz-not-an-objectid -H "Cookie: SESSION=$SESSION"` (GET — без CSRF) | `404` (uniform, не 400/500) | AC-9 |
| 3.8 | Foreign: дістати ID будь-якого проекту `userA`, тоді `curl -i $BASE/api/v1/projects/<idA> -H "Cookie: SESSION=$SESSION_B"` (GET — без CSRF, але потрібен `SESSION_B`) | `404` (НЕ 403, НЕ 200) | AC-8 |
| 3.9 | `curl $BASE/api/v1/projects?include_deleted=true -H "Cookie: SESSION=$SESSION" \| jq` (GET — без CSRF) | Масив містить і активні і soft-deleted, кожен soft-deleted має ISO `deletedAt`. | AC-7 |
| 3.10 | `curl -i -X POST $BASE/api/v1/projects -H "Cookie: SESSION=$SESSION; XSRF-TOKEN=$XSRF" -H "X-XSRF-TOKEN: $XSRF" -H "Content-Type: application/json" -d '{"name":"MassAssign","timezone":"Europe/Kyiv","ownerId":"000000000000000000000000"}'` потім перевірити `db.projects.findOne({name:"MassAssign"}).ownerId` у Mongo | DB має `ownerId == userA._id`, **не** `000000...`. Response body НЕ містить `ownerId` поля. | Risk 2 |

### Бонус — CSRF sanity check

```bash
# POST без X-XSRF-TOKEN хедера (cookie на місці) → має бути 403
curl -i -X POST $BASE/api/v1/projects \
  -H "Cookie: SESSION=$SESSION; XSRF-TOKEN=$XSRF" \
  -H "Content-Type: application/json" \
  -d '{"name":"NoCsrf","timezone":"Europe/Kyiv"}'
```
Очікуване: `403 Forbidden`. Це підтверджує, що CSRF-захист активний (не "пройшло через щілину" — він просто не дійшов до контролера).

---

## 4. Infrastructure / data checks (3 хв)

### 4.1 Mongo — структура документа
```bash
mongosh "mongodb://localhost:27017/botfunnel" --eval \
  'printjson(db.projects.findOne({name:"Brand X Books"}))'
```
Перевір: `_id`, `ownerId`, `name`, `description`, `timezone`, `createdAt`, `updatedAt`, `deletedAt: null`. **Нема** жодних службових полів типу `_class` поза стандартом проекту.

### 4.2 Audit events
```bash
mongosh "mongodb://localhost:27017/botfunnel" --eval \
  'db.events.find({eventType:/^project_/}).sort({createdAt:-1}).limit(20).toArray()'
```
Повинні бути типи: `project_created`, `project_renamed`, `project_updated`, `project_soft_deleted`, `project_restored`. На restored з конфліктом імені — `metadata.renamedDueToConflict: true`.

### 4.3 JobRunr recurring registration
Відкрити http://localhost:8000/dashboard → Recurring Jobs. Має бути:
- `hard-delete-users` (існуючий, з 02-auth)
- `hard-delete-projects` — cron `0 3 * * *`, наступний run-time у списку. | AC-16

### 4.4 PK alignment
```bash
grep -n "full-page onboarding" .claude/skills/project-knowledge/references/ux-guidelines.md
```
Очікуване — **0 результатів** (рядок 33 оновлено). | AC-31

### 4.5 Locale parity gate
```bash
cd frontend && pnpm build
```
Має пройти без помилок про missing keys. | AC-29, AC-25

---

## 5. Cron behavior (опційно, 5 хв)

Цей кейс важко зімітувати в реальному часі (7 днів). Натомість:

### 5.1 Подивитися лог
Після запуску `bootRun` дочекатися 03:00 UTC або глянути integration-test що це покриває:
```bash
cd backend && ./gradlew test --tests 'com.botfunnel.jobs.ProjectHardDeleteJobIT'
```
Має бути зелений + у тесті асерт на INFO log "run completed: deletedCount=... eventsRemovedCount=... runDurationMs=...". | AC-17c

### 5.2 Ручний trigger (через JobRunr dashboard)
Dashboard → Recurring → `hard-delete-projects` → "Trigger now". У логах backend має з'явитись INFO line з `deletedCount=0` (бо за 7 днів нічого ще немає).

---

## 6. Sign-off checklist

Перед approve PR / merge:

- [ ] Розділ 1 (UI golden-path) пройдено повністю без console-помилок у DevTools
- [ ] Розділ 2 (edge-кейси UI) — усі 7 пунктів проходять як описано
- [ ] Розділ 3 (curl) — всі 10 запитів відповідають як в табличці
- [ ] Розділ 4 (Mongo + JobRunr + PK + locale) — все ОК
- [ ] Розділ 5.1 (`ProjectHardDeleteJobIT`) — green
- [ ] `./gradlew test` — green (backend unit + IT)
- [ ] `pnpm test` — green (vitest)
- [ ] `pnpm test:e2e` — green (Playwright golden-path)
- [ ] Жодних попереджень про `localStorage` у SSR-логах frontend dev-сервера
- [ ] `gitleaks` pre-commit hook не блокував жоден commit

## 7. Що **не** треба перевіряти вручну

- Cron real-time hard-delete (через 7 днів) — покрито IT
- Concurrent-POST race для name uniqueness — свідомо не тестуємо (Risk R3, mitigated quota)
- Cross-tab `storage` event sync — **свідомо НЕ реалізовано** (multi-tab independence = feature)
- E2E варіанти на різні локалі — покрито `e2e/i18n.spec.ts` окремо
- Брут-форс/rate-limit на `/api/v1/projects/*` — свідомо НЕ реалізовано (Decision 8)
