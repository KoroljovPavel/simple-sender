---
status: done                       # planned -> in_progress -> done
depends_on: [1]                    # номера задач-зависимостей
wave: 2                            # волна параллельного выполнения
skills: [code-writing]             # МАССИВ скиллов для загрузки
verify: [smoke]                    # типы верификации: smoke, user (опционально)
reviewers: [code-reviewer, security-auditor, test-reviewer]  # явно указать. Пусто = fallback на дефолтные три
teammate_name:                     # косметическое имя тиммейта для agent teams
---

# Task 5: Funnel CRUD service + controller

## Required Skills

Перед выполнением задачи загрузи:
- `/skill:code-writing` — [skills/code-writing/SKILL.md](../../../.claude/skills/code-writing/SKILL.md)

## Description

Реалізувати CRUD-шар лінійних воронок: `FunnelService` (бізнес-логіка + валідація) і `FunnelController`
(REST) під базовим шляхом `/api/v1/projects/{projectId}/funnels`. Це HTTP-фасад поверх доменної моделі з
Task 1 (`Funnel`, `FunnelStep`, `FunnelRepository`, enum-статуси `FunnelStatus {draft, active, paused}`).

Контролер дає власнику проєкту повний життєвий цикл воронки:
- **create** — нова воронка у статусі `draft` (тільки `name` + опц. `description`);
- **list** — легкий список воронок проєкту з опц. фільтром `?status=`;
- **get** — повна воронка з кроками і (для `active`) готовим `deepLink`;
- **update** — метадані + **повний масив `steps`** (позиція в масиві = порядок; сервер перезаписує
  `order` за індексом — переміщення ↑/↓ це просто новий масив) + `triggerType`/`triggerValue`/`allowReEnter`;
- **delete** — видалення воронки зі скасуванням її активних виконань;
- **activate / pause** — явні status-переходи з валідацією.

Ключові вимоги, що відрізняють цю задачу від звичайного CRUD:
- **Anti-IDOR:** `projectService.requireOwned(projectId, ownerId)` викликається ПЕРШИМ у кожному
  ендпоінті; чужий / soft-deleted / неіснуючий `projectId` або `funnelId` → **uniform 404** (не 403, не
  leak існування). Funnel, що не належить вказаному проєкту → теж 404.
- **Активація** перевіряє: ≥1 крок; усі per-type обов'язкові поля заповнені; відсутній конфлікт тригера
  `(triggerType, triggerValue)` з іншою `active`-воронкою цього проєкту. При успіху → `status=active` +
  генерація `deepLink`.
- **Конфлікт тригера** ловиться двома механізмами (defense-in-depth, Decision 8): сервісною перевіркою
  перед записом І partial-unique індексом `(projectId, triggerType, triggerValue)` filtered `status=active`
  (з Task 1). `DuplicateKeyException` на activate ловиться й мапиться у **422 `funnel_trigger_conflict`**
  (а не 500).
- **Ліміт кроків** `FUNNEL_MAX_STEPS` — перевищення → 422.

Залежить від Task 1 (доменна модель + репозиторії + індекси). DTO-records у `funnel/dto/`.

## What to do

1. **DTO-records у `funnel/dto/`** (за взірцем records/validation у custom-fields контролерах):
   - `CreateFunnelRequest` — `name` (required, `@NotBlank`), `description` (nullable).
   - `UpdateFunnelRequest` — метадані (`name`, `description`) + `triggerType` + `triggerValue`
     (`@Pattern("^[A-Za-z0-9_-]{0,64}$")` — порожній дозволено = голий `/start`; пробіли/спецсимволи
     зламали б deep-link → 422) + `allowReEnter` + **повний масив `steps`** (`List<FunnelStepDto>`, `@Valid`).
   - `FunnelStepDto` — `stepType` (enum, required) + nullable type-specific поля (`text`, `parseMode`,
     `imageUrl`, `caption`, `delayValue`, `delayUnit`, `tagSlug`, `customFieldKey`, `customFieldValue`).
     Per-type bean-validation: порожній `text` для SEND_MESSAGE → 422; `imageUrl` http/https-схема;
     `tagSlug` `@Pattern("^[a-z0-9_-]{1,32}$")`.
   - `FunnelResponse` — метадані + повний `steps` + `deepLink` (для `active`; інакше null), single-GET +
     відповідь activate.
   - `FunnelSummaryResponse` — метадані БЕЗ `steps` (легкий список).
2. **`FunnelService`** — методи: `create`, `list(status?)`, `get`, `update`, `delete`, `activate`,
   `pause`. Кожен приймає `ownerId` + `projectId` (+ `funnelId` де доречно). Логіка:
   - `requireOwned` першим у кожному методі (через `ProjectService`).
   - `create` → нова `Funnel` зі `status=draft`, таймстемпи.
   - `update` → перезаписати `order` кроків за індексом у масиві; провалідувати per-type поля та ліміт
     `FUNNEL_MAX_STEPS`; зберегти.
   - `delete` → скасувати активні виконання воронки (cancel running|waiting через
     `FunnelExecutionRepository`/`MongoTemplate`) ПЕРЕД/разом із видаленням `Funnel`. (Якщо
     `FunnelExecution`-репо з Task 1 ще не дає bulk-cancel — додати query тут; уникати дублювання з движком.)
   - `activate` → валідація (≥1 крок; обов'язкові поля; конфлікт тригера через сервісну перевірку);
     встановити `status=active`; зловити `DuplicateKeyException` (partial-unique індекс) → `AppException`
     з кодом `funnel_trigger_conflict` (422); згенерувати `deepLink` через резолв CONNECTED-бота проєкту
     (`t.me/<botUsername>?start=<triggerValue>`).
   - `pause` → `active→paused`.
   - `get` для `active`-воронки повертає `deepLink` (не лише у відповіді activate — щоб редактор показав
     його після перезавантаження сторінки).
3. **`FunnelController`** — REST-мапінг під `/api/v1/projects/{projectId}/funnels`:
   - `POST` → 201 + `FunnelResponse` (draft).
   - `GET` (`?status=`) → 200 + `List<FunnelSummaryResponse>`.
   - `GET /{funnelId}` → 200 + `FunnelResponse`.
   - `PUT`/`PATCH` `/{funnelId}` → 200 + `FunnelResponse`.
   - `DELETE /{funnelId}` → 204.
   - `POST /{funnelId}/activate` → 200 + `FunnelResponse` (з `deepLink`).
   - `POST /{funnelId}/pause` → 200 + `FunnelResponse`.
   - Резолв `ownerId` із сесії так само, як custom-fields контролери. Делегувати все у `FunnelService`.
4. **Помилки** через наявний `AppException` / `GlobalErrorHandler` (коди → HTTP). Нові коди:
   `funnel_trigger_conflict` (422), та коди для not-found (uniform 404), ліміту кроків (422), невалідних
   кроків (422). Bean-validation → 400.

## TDD Anchor

Тести пишемо ДО реалізації (MockMvc IT на `AbstractIntegrationTest` — Testcontainers Mongo), файл
`backend/src/test/java/com/botfunnel/funnel/FunnelControllerIT.java`:

- `FunnelControllerIT::createReturns201Draft` — `POST .../funnels` з `{"name":"t"}` → 201, тіло
  `FunnelResponse` зі `status=draft`.
- `FunnelControllerIT::listFiltersByStatus` — `GET .../funnels?status=active` → 200, повертає лише active,
  без `steps` (FunnelSummaryResponse).
- `FunnelControllerIT::getReturnsFullFunnelWithSteps` — `GET .../funnels/{id}` → 200, повний `steps`;
  для active — присутній `deepLink`.
- `FunnelControllerIT::updateRewritesStepOrderByIndex` — `PUT` з переставленим масивом `steps` → 200,
  `order` перезаписано за позицією в масиві.
- `FunnelControllerIT::deleteReturns204AndCancelsActiveExecutions` — `DELETE` → 204; активні виконання
  воронки переведені у `cancelled`.
- `FunnelControllerIT::activateValidatesAtLeastOneStep` — activate воронки без кроків → 422.
- `FunnelControllerIT::activateValidatesRequiredFields` — activate воронки з кроком без обов'язкового
  поля → 422.
- `FunnelControllerIT::activateTriggerConflictReturns422` — активувати другу воронку з тим самим
  `(triggerType, triggerValue)`, що в уже active-воронки → 422 з кодом `funnel_trigger_conflict`
  (перевірити, що спрацьовує саме гілка `DuplicateKeyException` partial-unique індексу, не 500).
- `FunnelControllerIT::crossProjectFunnelReturnsUniform404` — `GET/PUT/DELETE/activate` funnel'а іншого
  проєкту (або чужого власника) → 404 (uniform, не 403, не 500).
- `FunnelControllerIT::beanValidationReturns400or422` — порожній `text` у SEND_MESSAGE → 422; невалідний
  `triggerValue` (пробіл) → 422; відсутній `name` на create → 400.

## Acceptance Criteria

- [ ] `POST .../funnels` з `{"name":"t"}` → 201, `FunnelResponse` зі `status=draft`.
- [ ] `GET .../funnels?status=` → 200, легкий список `FunnelSummaryResponse` (без `steps`), фільтр працює.
- [ ] `GET .../funnels/{id}` → 200, повний `FunnelResponse` із `steps`; для active присутній `deepLink`.
- [ ] `PUT/PATCH .../funnels/{id}` → 200; сервер перезаписує `order` кроків за індексом у масиві.
- [ ] `DELETE .../funnels/{id}` → 204; активні виконання воронки скасовані (`cancelled`).
- [ ] `POST .../funnels/{id}/activate` → 200 + `deepLink`; валідація ≥1 крок / обов'язкові поля / конфлікт.
- [ ] `POST .../funnels/{id}/pause` → 200 (`active→paused`).
- [ ] Конфлікт тригера двох active-воронок → 422 `funnel_trigger_conflict` (ловиться і сервісною
      перевіркою, і `DuplicateKeyException` partial-unique індексу — не 500).
- [ ] Перевищення `FUNNEL_MAX_STEPS` → 422; Delay <1хв / невалідний imageUrl / невалідний tagSlug → 422.
- [ ] Bean-validation (відсутній `name`) → 400.
- [ ] `requireOwned` викликається ПЕРШИМ; чужий/soft-deleted/неіснуючий `projectId|funnelId` →
      uniform 404 (anti-IDOR, без leak існування).
- [ ] `deepLink` присутній у GET active-воронки (не лише у відповіді activate).
- [ ] Усі IT у `FunnelControllerIT` зелені; немає регресій у наявних тестах.

## Context Files

- [user-spec.md](../user-spec.md) — сценарії 1-3 (create/list/edit) + активація
- [tech-spec.md](../tech-spec.md) — "How it works" (create/edit, status transitions, trigger conflict),
  Data Models (DTO section), Acceptance Criteria (HTTP codes), Decisions 2 (deepLink/cadence) і 8 (re-enter
  guard / partial-unique index)
- [decisions.md](../decisions.md)
- [project.md](../../../.claude/skills/project-knowledge/references/project.md)
- [architecture.md](../../../.claude/skills/project-knowledge/references/architecture.md)
- [patterns.md](../../../.claude/skills/project-knowledge/references/patterns.md) — controller/service patterns + Testing section

**Code — to modify:**
- `backend/src/main/java/com/botfunnel/funnel/FunnelService.java` (new)
- `backend/src/main/java/com/botfunnel/funnel/FunnelController.java` (new)
- `backend/src/main/java/com/botfunnel/funnel/dto/` — request/response records (new)

**Code — to read (exemplars):**
- `backend/src/main/java/com/botfunnel/subscriber/SubscriberCustomFieldsController.java` — controller + DTO
  + per-type validation exemplar; `requireOwned`-first + uniform-404 cross-project isolation
  (`findById(...).filter(projectId-matches).orElseThrow(notFound)`) — точний взірець для funnel-belongs-to-
  project guard
- `backend/src/main/java/com/botfunnel/project/CustomFieldsController.java` — НАЙБЛИЖЧИЙ CRUD-controller
  exemplar (project-scoped, `requireOwned`-first, create/list/update/delete shape + status codes + records).
  ПРИМІТКА: tech-spec посилається на `subscriber/CustomFieldsController.java`, але реальний файл лежить у
  `project/` (не `subscriber/`) — використати цей шлях.
- `backend/src/main/java/com/botfunnel/project/ProjectController.java` — додатковий взірець REST-мапінгу
  (`@RequestMapping("/api/v1/projects")`, резолв owner із `SecurityContextHolder`/`AppUserDetails`).
- `backend/src/main/java/com/botfunnel/project/ProjectService.java` — `requireOwned` anti-IDOR (uniform 404)
- `backend/src/main/java/com/botfunnel/common/AppException.java` — error-code factories: `notFound(msg)`
  (404), `unprocessableEntity(code, msg)` (422 — для `funnel_trigger_conflict`, ліміту кроків, невалідних
  кроків), `conflict(code, msg)`; HTTP-мапінг через `GlobalErrorHandler`
- `backend/src/main/java/com/botfunnel/funnel/Funnel.java`, `FunnelStep.java`, `FunnelRepository.java`,
  `FunnelExecutionRepository.java` — доменна модель з Task 1. ПРИМІТКА: наразі в модулі `funnel/` є лише
  `FunnelTriggerService.java` + `NoOpFunnelTriggerService.java`; пакет `funnel/dto/` і `FunnelService.java`
  ще НЕ створені — створюються цією задачею (після того, як Task 1 додасть доменні класи/репо).

## Verification Steps

### Automated
- `cd backend && ./gradlew test --tests '*FunnelControllerIT*'` → all pass
- `cd backend && ./gradlew test` → no regressions (controller/service-рівень)

### Smoke
- Запустити локальний бекенд (`localhost:8080`) з активною сесією власника, тоді:
  ```
  curl -X POST localhost:8080/api/v1/projects/{pid}/funnels \
    -H 'Content-Type: application/json' --cookie <session> \
    -d '{"name":"t"}'
  ```
  → **201**, тіло `FunnelResponse` зі `status=draft`.
- **Конфлікт тригера → 422 `funnel_trigger_conflict`** перевіряється MockMvc IT
  (`FunnelControllerIT::activateTriggerConflictReturns422`): активувати дві воронки з однаковим
  `(triggerType, triggerValue)` → друга activate повертає 422 з кодом `funnel_trigger_conflict` (не 500).

## Details

**Files:**
- `funnel/FunnelService.java` — новий `@Service`. Інжектить `FunnelRepository`,
  `FunnelExecutionRepository` (для cancel-on-delete), `ProjectService` (requireOwned), bot-резолвер
  (CONNECTED-бот → `botUsername` для deepLink), `@Value FUNNEL_MAX_STEPS`. Методи create/list/get/update/
  delete/activate/pause; `requireOwned` першим у кожному.
- `funnel/FunnelController.java` — новий `@RestController @RequestMapping("/api/v1/projects/{projectId}/funnels")`.
  Резолв `ownerId` із сесії (як custom-fields контролери), делегує у сервіс, мапить коди 201/200/204.
- `funnel/dto/` — records `CreateFunnelRequest`, `UpdateFunnelRequest`, `FunnelStepDto`, `FunnelResponse`,
  `FunnelSummaryResponse`. Bean-validation анотації на полях.

**Dependencies:**
- Task 1 — `Funnel`, `FunnelStep`, `FunnelStatus`, `StepType`, `FunnelRepository`,
  `FunnelExecutionRepository`, partial-unique індекс `(projectId, triggerType, triggerValue)` filtered
  `status=active`. `FunnelService` і пакет `funnel/dto/` ще не існують — створюються цією задачею (наразі
  в модулі лише `FunnelTriggerService` + `NoOpFunnelTriggerService`, їх не чіпати — то Task 7).
- `ProjectService.requireOwned`, `AppException`/`GlobalErrorHandler` (`common/`) — наявні.

**Edge cases:**
- Конфлікт тригера: сервісна перевірка може промахнутися через гонку двох паралельних activate → друга
  впаде на partial-unique індексі `DuplicateKeyException` → мапити у 422 `funnel_trigger_conflict`
  (НЕ дати піднятися як 500). Тестувати саме індексну гілку.
- Порожній `triggerValue` дозволено (голий `/start`); пробіл/спецсимвол → 422 (pattern).
- `update` із переставленим масивом `steps` — `order` бере з ІНДЕКСУ масиву, ігноруючи клієнтський `order`.
- delete воронки в `draft` (без виконань) — теж 204, cancel-крок no-op.
- soft-deleted проєкт / чужий власник / неіснуючий funnelId — усі дають uniform 404 (через requireOwned
  першим + funnel-belongs-to-project check).
- `deepLink` для `draft`/`paused` — null (генерується лише для active).

**Implementation hints:**
- Дзеркалити структуру `SubscriberCustomFieldsController`/`CustomFieldsController` для record-DTO,
  `@Valid`, резолву owner із сесії та делегування у сервіс.
- Enum-статуси у будь-яких `findAndModify`/criteria (cancel-on-delete) писати `.name()`-літералами
  (lowercase, Decision 14).
- Cancel-on-delete: bulk-update виконань `status ∈ {running, waiting}` → `cancelled` за `funnelId`.
  Не дублювати логіку движка (Task 6) — лише атомарний bulk-update тут.
- `botUsername` для deepLink резолвити з CONNECTED-бота проєкту (один на проєкт у Фазі 1).

## Reviewers

- **code-reviewer** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-5/code-reviewer-{round}.json`
- **security-auditor** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-5/security-auditor-{round}.json`
- **test-reviewer** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-5/test-reviewer-{round}.json`

## Post-completion

- [ ] Записать краткий отчёт в decisions.md по шаблону (Summary: 1-3 предложения, ревью со ссылками на JSON, без таблиц файндингов и дампов)
- [ ] Если отклонились от спека — описать отклонение и причину
- [ ] Обновить user-spec/tech-spec если что-то изменилось
