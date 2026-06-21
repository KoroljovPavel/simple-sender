---
status: done
depends_on: [1, 4, 6]
wave: 3
skills: [code-writing]
verify: []
reviewers: [code-reviewer, security-auditor, test-reviewer]
teammate_name:
---

# Task 7: Реальний FunnelTriggerService (заміна NoOp)

## Required Skills

Before starting, load the skills listed below (they contain the methodology and patterns for this work):

- /skill:code-writing — [SKILL.md](../../../.claude/skills/code-writing/SKILL.md)

## Description

Зараз `com.botfunnel.funnel.FunnelTriggerService` — це інтерфейс із двома методами
(`fire(projectId, chatId, triggerType, payload)` і `cancelActiveFor(projectId, chatId)`), а єдина
реалізація — `NoOpFunnelTriggerService` (`@Service`, що нічого не робить, лише логує/мовчить). Точки
виклику вже існують у `ProcessTelegramUpdateJob`: `handleStart` викликає `fire(...)` після
`upsertFromTelegramUpdate`, а обробка `/stop` викликає `cancelActiveFor(...)`.

Ця задача замінює заглушку реальним рушієм тригера. Потрібно:

1. **Видалити** `NoOpFunnelTriggerService` (Decision 5) — інакше два `@Service` того самого інтерфейсу без
   `@Primary` дадуть `NoUniqueBeanDefinitionException` на старті контексту.
2. **Реалізувати** єдиний реальний `@Service`, що матчить active-воронку за `(triggerType, triggerValue)`,
   створює `funnel_execution` зі snapshot кроків і запіненим `telegramBotId`, застосовує re-enter guard
   через unique partial index, і `cancelActiveFor` скасовує всі running|waiting виконання підписника.

Критично для коректності всієї фічі: `fire()` викликається **всередині** webhook-воркера, тож **будь-який**
виняток усередині `fire()` має бути проковтнутий+залогований (Decision 6) — інакше webhook-update піде у
FAILED і спричинить JobRunr retry-шторм (повторний upsert + re-fire). Поточний NoOp ніколи не кидав; реальна
реалізація мусить зберегти цей контракт.

Залежить від Task 1 (доменна модель `Funnel`/`FunnelExecution`/`FunnelStep` + репозиторії + unique partial
index `(funnelId, subscriberId)` на `running|waiting`) і Task 6 (engine — спільні enum-статуси/константи й
очікувана форма `FunnelExecution` зі snapshot та `nextRunAt`, який sweep підхопить).

## What to do

1. **Видалити** `NoOpFunnelTriggerService.java`. Оновити/видалити `NoOpFunnelTriggerServiceTest.java` —
   замінити на тести реального сервісу (див. TDD Anchor); якщо тест більше не релевантний як unit —
   перетворити на IT реального бінy.
2. **Реалізувати реальний `@Service`** (новий клас, напр. `FunnelTriggerServiceImpl`, або реалізація
   інтерфейсу в одному `@Service`-класі — головне рівно один бін). Залежності через конструктор:
   `BotRepository` (резолв CONNECTED-бота), `SubscriberService` (інтерфейс — lookup-by-chat через
   публічний `findByChat(projectId, telegramBotId, chatId)`, доданий Task 4),
   `FunnelRepository` (матч active-воронки), `FunnelExecutionRepository`/`MongoTemplate` (insert/cancel
   виконань).
3. **`fire(projectId, chatId, triggerType, payload)`** — реалізувати 5 кроків (див. tech-spec «How it
   works» → Тригер):
   1. Резолвити CONNECTED-бота `(projectId, status=CONNECTED)` так само, як це робить webhook-воркер; узяти
      його `telegramBotId`. Немає CONNECTED-бота → log+skip.
   2. Резолвити підписника за `(projectId, telegramBotId, chatId)`. Немає → log+skip.
   3. Знайти active-воронку проєкту з exact-match `(triggerType, triggerValue == payload)` — порожній
      payload матчить воронку з порожнім `triggerValue`. Немає збігу → нічого (no-op).
   4. Re-enter guard (Decision 8):
      - `allowReEnter == false`: insert `funnel_execution` зі **snapshot** кроків воронки (deep copy,
        Decision 3) і запіненим `telegramBotId` (Decision 7), `currentStepIndex=0`,
        `status=running`/`stepRunStatus=pending`, `nextRunAt=now` (щоб найближчий sweep підхопив). Unique
        partial index `(funnelId, subscriberId)` на `running|waiting` робить повторний `/start` атомарним
        no-op: `DuplicateKeyException` → swallow (ігнор, лог re-enter).
      - `allowReEnter == true`: атомарно cancel наявного running|waiting виконання цієї пари
        `(funnelId, subscriberId)`, **потім** insert нове з кроку 0.
   5. Обгорнути все тіло `fire()` у try/catch `Throwable` зі swallow+log (Decision 6) — назовні виняток не
      йде ніколи.
4. **`cancelActiveFor(projectId, chatId)`** — резолвити підписника (як вище) і перевести всі його виконання
   зі `status ∈ {running, waiting}` у `cancelled`. Викликається з `/stop`-гілки воркера.
5. **Enum-статуси у Mongo-критеріях** писати `.name()`-літералами (byte-match індексів; Decision 4/14) —
   узгоджено з Task 6.
6. **Логування** — іменовані лог-константи на ключові переходи (re-enter ignore, fire-skip-причини, cancel),
   лише ідентифікатори/коди, **ніколи** payload/текст (Decision 16).
7. **Оновити `ProcessTelegramUpdateJobTest`** — наявне очікування
   `startPrivateWithPayload_callsSubscriberAndFunnel_writesEvent` має залишитись зеленим (виклик `fire(...)`
   після upsert). Тест уже використовує `@MockitoSpyBean FunnelTriggerService` (інтерфейс), тож лишається
   зеленим, щойно реальний `@Service` замінить `NoOp` — підтвердити, що наявний spy резолвить рівно один
   бін `FunnelTriggerService`; перейменування не потрібне.

## TDD Anchor

Tests to write BEFORE implementation (real tests/IT — на `AbstractIntegrationTest`: Testcontainers Mongo +
детермінований `Clock` через `ClockConfig`; за взірцем `ProjectHardDeleteJobIT` / `BotConnectRaceIT`):

- `backend/src/test/java/com/botfunnel/funnel/FunnelTriggerServiceIT::fireCreatesExecutionWithSnapshotAndPinnedBot`
  — `fire()` зі збігом створює `funnel_execution` зі stepsSnapshot (deep copy кроків воронки) і
  `telegramBotId` запіненим від CONNECTED-бота; `currentStepIndex=0`, status=running, nextRunAt виставлено.
- `...FunnelTriggerServiceIT::exactMatchIncludingEmptyPayload` — порожній payload матчить воронку з порожнім
  `triggerValue`; непорожній payload матчить лише точний `triggerValue`.
- `...FunnelTriggerServiceIT::noMatchingFunnelIsNoop` — немає active-воронки під `(triggerType, payload)` →
  жодного виконання не створено, виняток не кинуто.
- `...FunnelTriggerServiceIT::reEnterFalseDuplicateKeySwallowedNoop` — `allowReEnter=false` + наявне
  running виконання → повторний `fire()` ловить DuplicateKey від unique partial index і ковтає (без другого
  виконання, без винятку).
- `...FunnelTriggerServiceIT::reEnterTrueCancelsAndRestarts` — `allowReEnter=true` + наявне running →
  старе → cancelled, нове running з кроку 0.
- `...FunnelTriggerServiceIT::cancelActiveForCancelsRunningAndWaiting` — `cancelActiveFor` переводить усі
  running|waiting виконання підписника у cancelled; completed/failed/cancelled не чіпає.
- `...FunnelTriggerServiceIT::noConnectedBotOrNoSubscriberIsNoop` — немає CONNECTED-бота / немає підписника
  → log+skip, без винятку, без виконання.
- `...FunnelTriggerServiceIT::fireErrorIsolationSwallowsAndDoesNotFailWebhook` (HIGH, Decision 6) —
  виняток усередині `fire()` (напр. підставлений падаючий репозиторій/мок) проковтнуто; `fire()` не кидає
  назовні; raw_update **не** марковано FAILED і немає webhook-retry (перевірити через виклик у контексті
  `ProcessTelegramUpdateJob.dispatch`, що завершується успішно).
- `...FunnelTriggerServiceIT::reactivationOrderingFirstSendNotSelfCancelled` (HIGH) — `/start` від
  `unsubscribed`-підписника: upsert ставить `Subscriber.status=active` ДО `fire()`, тож створене виконання
  проходить pre-send status-gate і перший send не само-скасовується (підтвердити, що порядок воркера не
  змінено й виконання не одразу cancelled).
- Оновити `backend/src/test/java/com/botfunnel/webhook/ProcessTelegramUpdateJobTest::startPrivateWithPayload_callsSubscriberAndFunnel_writesEvent`
  — очікування виклику `fire(...)` після upsert лишається зеленим на інтерфейсі `FunnelTriggerService`.

## Acceptance Criteria

- [ ] `NoOpFunnelTriggerService` видалено; рівно один `@Service`, що реалізує `FunnelTriggerService`;
      Spring-контекст стартує без `NoUniqueBeanDefinitionException`.
- [ ] `fire()` резолвить CONNECTED-бота + підписника; немає одного з них → log+skip без винятку.
- [ ] `fire()` матчить active-воронку exact-match за `(triggerType, triggerValue)`; порожній payload →
      порожній `triggerValue`; немає збігу → no-op.
- [ ] При збігу створюється `funnel_execution` зі snapshot кроків (deep copy) і запіненим `telegramBotId`.
- [ ] `allowReEnter=false`: повторний `/start` → DuplicateKey від unique partial index проковтнуто (no-op);
      `allowReEnter=true`: старе виконання cancelled, нове стартує з кроку 0.
- [ ] `cancelActiveFor` переводить усі running|waiting виконання підписника у cancelled.
- [ ] **Будь-який** виняток усередині `fire()` проковтнуто+залогований; назовні не кидається; webhook-update
      не йде у FAILED і немає retry (Decision 6).
- [ ] Порядок воркера НЕ змінено: `upsertFromTelegramUpdate` (ставить status=active) лишається ПЕРЕД
      `fire()`; перший send реактивованого підписника не само-скасовується.
- [ ] Enum-статуси у Mongo-критеріях написані `.name()`-літералами.
- [ ] Переходи логуються іменованими константами без payload/PII (Decision 16).
- [ ] Усі тести зелені (`./gradlew test -PrunSlow=true`); немає регресій у webhook/subscriber/bot тестах.
- [ ] `ProcessTelegramUpdateJobTest.startPrivateWithPayload_callsSubscriberAndFunnel_writesEvent` лишається
      зеленим — наявний `@MockitoSpyBean FunnelTriggerService` резолвить рівно один бін і Spring-контекст
      стартує.

## Context Files

**Feature-specific:**
- [user-spec.md](../user-spec.md)
- [tech-spec.md](../tech-spec.md) — «How it works» → Тригер (fire() 5 кроків, cancelActiveFor),
  Decisions 3, 5, 6, 7, 8, критичний порядок воркера (upsert status=active ПЕРЕД fire()).
- [decisions.md](../decisions.md)

**Project context (PK):**
- [project.md](../../../.claude/skills/project-knowledge/references/project.md)
- [architecture.md](../../../.claude/skills/project-knowledge/references/architecture.md) — atomic-claim
  патерн, Data Model, Funnel Step Execution.
- [patterns.md](../../../.claude/skills/project-knowledge/references/patterns.md)
- [testing.md](../../../.claude/skills/project-knowledge/references/testing.md) — `AbstractIntegrationTest`,
  Testcontainers Mongo, детермінований `Clock`.

**Code — modify:**
- [FunnelTriggerService.java](../../../backend/src/main/java/com/botfunnel/funnel/FunnelTriggerService.java)
  — інтерфейс (`fire(projectId, chatId, triggerType, payload)`, `cancelActiveFor(projectId, chatId)`);
  реалізувати реальний impl (новий клас або заміна NoOp).
- [NoOpFunnelTriggerService.java](../../../backend/src/main/java/com/botfunnel/funnel/NoOpFunnelTriggerService.java)
  — **видалити**.

**Code — read:**
- [ProcessTelegramUpdateJob.java](../../../backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java)
  — наявні call sites `fire()`/`cancelActiveFor()`, `handleStart`, порядок `upsertFromTelegramUpdate` →
  `fire()`, обгортка `dispatch` catch-Throwable+rethrow+retry.
- [BotRepository.java](../../../backend/src/main/java/com/botfunnel/bot/BotRepository.java) — резолв
  CONNECTED-бота `(projectId, status=CONNECTED)`.
- [NoOpFunnelTriggerServiceTest.java](../../../backend/src/test/java/com/botfunnel/funnel/NoOpFunnelTriggerServiceTest.java)
  — наявний тест, що оновити/замінити.
- [ProcessTelegramUpdateJobTest.java](../../../backend/src/test/java/com/botfunnel/webhook/ProcessTelegramUpdateJobTest.java)
  — `startPrivateWithPayload_callsSubscriberAndFunnel_writesEvent`; уже використовує
  `@MockitoSpyBean FunnelTriggerService` (інтерфейс) — підтвердити, що spy резолвить рівно один бін; без
  перейменування.
- Task-1 артефакти: `Funnel`, `FunnelStep`, `FunnelExecution`, `FunnelRepository`,
  `FunnelExecutionRepository` (під `com/botfunnel/funnel/`) — форма snapshot, enum-статуси, unique partial
  index `(funnelId, subscriberId)`.
- Task-6 артефакт: `FunnelExecutionEngine` — спільні enum-статуси/константи; очікувана форма `FunnelExecution`
  (snapshot + `nextRunAt`), яку sweep підхопить.

## Verification Steps

### Automated

- `cd backend && ./gradlew test -PrunSlow=true --tests '*FunnelTriggerService*'` → зелені (всі IT з TDD
  Anchor: snapshot+pinned bot, exact-match incl empty payload, no-match no-op, re-enter false/true,
  cancelActiveFor, fire() error-isolation, реактивація-порядок).
- `cd backend && ./gradlew test --tests '*ProcessTelegramUpdateJob*'` → зелені
  (`startPrivateWithPayload_callsSubscriberAndFunnel_writesEvent` лишається зеленим на інтерфейсі).
- `cd backend && ./gradlew test -PrunSlow=true` → повний прогін без регресій (Spring-контекст стартує —
  рівно один `FunnelTriggerService` бін).

_(Smoke/User verification не застосовується для цієї задачі.)_

## Details

**Files & current state:**
- `FunnelTriggerService.java` — інтерфейс із двома методами; залишається інтерфейсом, додається реальна
  реалізація (новий `@Service`-клас, напр. `FunnelTriggerServiceImpl`, АБО анотувати реалізацію
  безпосередньо). Головне — рівно один `@Service`-бін цього типу.
- `NoOpFunnelTriggerService.java` — поточний `@Service`-no-op; **видалити** (Decision 5 — другий бін без
  `@Primary` зламав би старт).
- Call sites у `ProcessTelegramUpdateJob` НЕ змінюємо: `handleStart` уже викликає `fire(...)` після
  `upsertFromTelegramUpdate`, `/stop`-гілка викликає `cancelActiveFor(...)`. Критичний інваріант: upsert
  ставить `Subscriber.status=active` ПЕРЕД `fire()` — порядок уже забезпечений; не чіпати.

**Dependencies:**
- Task 1 — `Funnel`/`FunnelStep`/`FunnelExecution` + репозиторії + unique partial index
  `(funnelId, subscriberId)` filtered `status ∈ {running, waiting}` (re-enter guard покладається на цей
  індекс) + partial-unique тригера.
- Task 6 — спільні enum-статуси (`ExecutionStatus`, `StepRunStatus` lowercase) і форма `FunnelExecution`
  (snapshot, `nextRunAt`), що sweep підхопить.

**Edge cases:**
- Немає CONNECTED-бота / немає підписника → log+skip (не помилка).
- Порожній payload (`/start` без deep-link) → матч воронки з порожнім `triggerValue`.
- Паралельні `/start` (`allowReEnter=false`) → лише одне виконання; решта DuplicateKey → swallow.
- `allowReEnter=true` під гонкою → cancel-then-insert має бути послідовним для пари
  `(funnelId, subscriberId)`; покладатись на index для гарантії унікальності активного.
- Реактивація з `unsubscribed`: статус уже `active` до `fire()`, тож виконання не cancelled status-gate'ом.
- Виняток будь-де у `fire()` (Mongo-помилка, NPE) → swallow+log, не кидати.

**Implementation hints:**
- Резолв CONNECTED-бота — дзеркалити те, що робить webhook-воркер (`BotRepository` за
  `(projectId, status=CONNECTED)`); узяти `telegramBotId` для піна.
- Lookup-by-chat підписника — ВИКЛЮЧНО через публічний метод
  `SubscriberService.findByChat(projectId, telegramBotId, chatId)` (доданий Task 4). НЕ звертатись до
  `SubscriberRepository` напряму — це тримає межу модулів funnel→subscriber; цей публічний метод
  інтерфейсу не існує до Task 4.
- Insert/cancel виконань — через `MongoTemplate` (для `.name()`-літералів у критеріях і атомарного
  cancel-then-insert), узгоджено зі стилем Task 6.
- Re-enter guard — лише index + catch `DuplicateKeyException` (Decision 8 — НЕ дублювати query-time
  перевіркою, бо гонка).
- `fire()` обгортка — `try { ... } catch (Throwable t) { log.warn(...); }` (Decision 6); жодного rethrow.
- Snapshot — deep copy `Funnel.steps` у `FunnelExecution.stepsSnapshot` на момент `fire()` (Decision 3).

## Reviewers

- **code-reviewer** → report: `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-7/code-reviewer-{round}.json`
- **security-auditor** → report: `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-7/security-auditor-{round}.json`
- **test-reviewer** → report: `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-7/test-reviewer-{round}.json`

## Post-completion

- [ ] Write report to [decisions.md](../decisions.md) (include all review rounds with links)
- [ ] If deviated from spec — describe deviation and reason
- [ ] Update user-spec/tech-spec if anything changed
