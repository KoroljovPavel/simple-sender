---
status: done                       # planned -> in_progress -> done
depends_on: []                     # номера задач-зависимостей
wave: 1                            # волна параллельного выполнения
skills: [code-writing]             # МАССИВ скиллов для загрузки
verify: []                         # типы верификации: smoke, user (опционально)
reviewers: [code-reviewer, security-auditor, test-reviewer]  # явно указать. Пусто = fallback на дефолтные три
teammate_name:                     # косметическое имя тиммейта для agent teams
---

# Task 1: Доменна модель + персистенція воронок

## Required Skills

Перед виконанням задачі завантаж:
- `/skill:code-writing` — [skills/code-writing/SKILL.md](../../../.claude/skills/code-writing/SKILL.md)

## Description

Фундамент модуля `com.botfunnel.funnel`: доменні сутності та персистенція двох колекцій воронкового
рушія. Усі решта задач (CRUD — Task 5, рушій — Task 6, тригер — Task 7, каскад — Task 8) спираються на
ці типи й індекси, тож вони мають з'явитися першими і самостійно (Wave 1, без залежностей).

Створюємо:
- **`Funnel`** (`@Document("funnels")`) — визначення воронки: метадані, тригер, embedded список кроків.
- **`FunnelStep`** — плаский embedded POJO (Decision 12, без `_class`-дискримінатора) зі `stepType`-enum
  + nullable type-specific полями.
- **`FunnelExecution`** (`@Document("funnel_executions")`) — per-subscriber стан проходження зі
  **snapshot** кроків (Decision 3) і запіненим `telegramBotId` (Decision 7).
- Три enum-и статусів **lowercase** (Decision 14): `FunnelStatus`, `ExecutionStatus`, `StepRunStatus`,
  плюс `StepType`.
- **`FunnelRepository`**, **`FunnelExecutionRepository`** — Spring Data Mongo repos із вузькою lookup-
  поверхнею, потрібною Wave 2.

Усі індекси — **annotation-driven** через `@CompoundIndexes`/`@CompoundIndex`, авто-створювані на старті
(`spring.data.mongodb.auto-index-creation=true` уже ввімкнено в `application.properties`). Жодного
`@PostConstruct ensureIndex` / імперативного `IndexOperations`. Lowercase enum-константи означають, що
`.name()` дає `running`/`active`/... — тож **partialFilter-літерали в індексах мусять бути lowercase**
і байт-у-байт збігатися з `.name()`. Захищаємо це class-load static-assert'ом за взірцем `Bot`/`RawUpdate`.

Результат: при старті застосунку (і в IT на Testcontainers) колекції `funnels` та `funnel_executions`
створюються з усіма потрібними індексами без помилок.

## What to do

- Створити enum-и в пакеті `com.botfunnel.funnel`:
  - `FunnelStatus` з константами `draft`, `active`, `paused` (lowercase).
  - `ExecutionStatus` з `running`, `waiting`, `completed`, `cancelled`, `failed` (lowercase).
  - `StepRunStatus` з `pending`, `in_progress`, `done` (lowercase).
  - `StepType` з `SEND_MESSAGE`, `SEND_IMAGE`, `DELAY`, `ADD_TAG`, `REMOVE_TAG`, `SET_CUSTOM_FIELD`.
    (StepType — дискримінатор типу кроку, не статус; залишаємо UPPERCASE — у нього немає partial-index
    залежності, але дотримуйся послідовності з рештою enum-дискримінаторів кодбази.)
- Створити `FunnelStep` як плаский embedded POJO з полями за Data Models: `stepType`, `order` + nullable
  `text`, `parseMode`, `imageUrl`, `caption`, `delayValue`, `delayUnit`, `tagSlug`, `customFieldKey`,
  `customFieldValue`. Без bean-validation-анотацій тут (валідація живе в DTO/сервісі — Task 5); це
  persistence-POJO. Передбачити можливість глибокого копіювання для snapshot (Decision 3) — простий
  copy-конструктор або factory-метод (фактичне копіювання виконує Task 7, але тип має це дозволяти).
- Створити `Funnel` (`@Document(collection = "funnels")`) з полями за Data Models: `id` (`@Id`),
  `projectId`, `name`, `description`, `status` (`FunnelStatus`), `triggerType`, `triggerValue`,
  `allowReEnter` (default false), `steps` (`List<FunnelStep>`), `createdAt`, `updatedAt`.
- Створити `FunnelExecution` (`@Document(collection = "funnel_executions")`) з полями: `id`, `projectId`
  (top-level, для каскаду), `funnelId`, `subscriberId`, `telegramBotId` (`Long`, пін), `status`
  (`ExecutionStatus`), `currentStepIndex`, `stepRunStatus` (`StepRunStatus`), `nextRunAt` (`Instant`),
  `stepsSnapshot` (`List<FunnelStep>`), `createdAt`, `updatedAt`, `completedAt`.
- Оголосити annotation-driven індекси на сутностях (за взірцем `Subscriber`/`Bot`/`RawUpdate`):
  - `funnels`: `(projectId, status)` (lookup/list); partial-unique `(projectId, triggerType,
    triggerValue)` з `partialFilter` `status=active` (захист конфлікту тригера двох active-воронок).
  - `funnel_executions`: `(status, nextRunAt)` (sweep-claim, критичний); `(projectId)` (каскад); unique
    partial `(funnelId, subscriberId)` з `partialFilter` `status IN [running, waiting]` (re-enter guard).
- Узгодити partialFilter-літерали з lowercase `.name()` (`'active'`, `'running'`, `'waiting'`).
- Додати static class-load assert у `Funnel` та `FunnelExecution`, що звіряє partialFilter-літерали з
  `FunnelStatus.active.name()` / `ExecutionStatus.running.name()` / `ExecutionStatus.waiting.name()`
  (за взірцем static-блоків у `Bot`/`RawUpdate`), щоб тихий rename enum не знеактивив індекси.
- Створити `FunnelRepository extends MongoRepository<Funnel, String>` — методи лише ті, що знадобляться
  Wave 2 (lookup воронок проєкту за статусом, активна воронка за тригером). Не додавай мертвих методів.
- Створити `FunnelExecutionRepository extends MongoRepository<FunnelExecution, String>` — вузька
  поверхня (lookup за `(projectId)` для каскаду; інші запити рушія йдуть через `MongoTemplate`
  `findAndModify` у Task 6, тож тут лише те, що справді викликають).

## TDD Anchor

Тести пишемо ДО реалізації, переконуємось що падають (типи/колекції ще не існують), потім реалізуємо.

- `backend/src/test/java/com/botfunnel/funnel/FunnelIndexesIT.java::funnelsCollectionHasExpectedIndexes`
  — на `AbstractIntegrationTest` (Testcontainers Mongo, auto-index-creation): після старту контексту
  колекція `funnels` має індекси `(projectId, status)` та partial-unique `(projectId, triggerType,
  triggerValue)` з partialFilter `status=active`. Перевіряти через `mongoTemplate.getCollection("funnels")
  .listIndexes()` (keys + `partialFilterExpression` + `unique`).
- `...FunnelIndexesIT::funnelExecutionsCollectionHasExpectedIndexes` — колекція `funnel_executions` має
  `(status, nextRunAt)`, `(projectId)`, та unique partial `(funnelId, subscriberId)` з partialFilter
  `status IN [running, waiting]`. ВАЖЛИВО — наявності ключа замало: тест мусить дістати з `listIndexes()`
  потрібний індекс (за `name` або за `key`-документом `{funnelId:1, subscriberId:1}`), прочитати поле
  `partialFilterExpression` (це вкладений BSON-документ) і явно асертити його ВМІСТ, а не лише факт
  існування. Очікувана структура: `partialFilterExpression == { "status": { "$in": ["running", "waiting"] } }`
  — тобто дістань `pfe.get("status")` як `Document`, з нього `get("$in")` як `List`, і звір, що список
  дорівнює `["running","waiting"]` (порядок як оголошено) та що рядки lowercase. Також асертити `unique == true`
  на цьому ж індексі.
- `...FunnelIndexesIT::contextStartsAndCollectionsExistWithoutError` — контекст піднімається, обидві
  колекції створені без винятку (acceptance: «колекції з індексами стартують без помилок»).
- `backend/src/test/java/com/botfunnel/funnel/FunnelStatusEnumTest.java::statusEnumsArePersistedLowercase`
  — unit: `FunnelStatus.active.name().equals("active")`, `ExecutionStatus.running.name().equals("running")`,
  `ExecutionStatus.waiting.name().equals("waiting")`, `StepRunStatus.in_progress.name().equals("in_progress")`
  тощо — байт-match із partialFilter-літералами (Decision 14, AC).
- `backend/src/test/java/com/botfunnel/funnel/FunnelStepTest.java::deepCopyProducesIndependentStep`
  — копія `FunnelStep` (для snapshot) не ділить стан з оригіналом (мутація копії не зачіпає джерело).

## Acceptance Criteria

- [ ] Сутності `Funnel`, `FunnelStep`, `FunnelExecution` створені в `com.botfunnel.funnel` з полями за
      tech-spec «Data Models».
- [ ] Enum-и статусів lowercase (Decision 14): `FunnelStatus {draft, active, paused}`,
      `ExecutionStatus {running, waiting, completed, cancelled, failed}`,
      `StepRunStatus {pending, in_progress, done}`; `StepType {SEND_MESSAGE, SEND_IMAGE, DELAY, ADD_TAG,
      REMOVE_TAG, SET_CUSTOM_FIELD}`.
- [ ] Індекси `funnels`/`funnel_executions` створюються при старті annotation-driven
      (`auto-index-creation=true`); НЕМАЄ `@PostConstruct ensureIndex` / імперативного `indexOps`.
- [ ] `funnels`: індекс `(projectId, status)`; partial-unique `(projectId, triggerType, triggerValue)`
      filtered `status=active`.
- [ ] `funnel_executions`: індекс `(status, nextRunAt)`; індекс `(projectId)`; unique partial
      `(funnelId, subscriberId)` filtered `status IN [running, waiting]`.
- [ ] partialFilter-літерали lowercase й байт-у-байт збігаються з `.name()` enum-константами; class-load
      static-assert у `Funnel`/`FunnelExecution` звіряє це (за взірцем `Bot`/`RawUpdate`).
- [ ] `FunnelStep` — плаский POJO без `_class`-дискримінатора (Decision 12); підтримує глибоке копіювання
      для snapshot (Decision 3).
- [ ] `FunnelRepository`/`FunnelExecutionRepository` extends `MongoRepository`; без мертвих методів.
- [ ] Контекст застосунку стартує; колекції з індексами створюються без помилок (IT зелений).
- [ ] Немає регресій у наявних тестах.

## Context Files

- [user-spec.md](../user-spec.md)
- [tech-spec.md](../tech-spec.md) — «Data Models», «Architecture → What we're building», Decisions 1/3/12/14/15, «Acceptance Criteria»
- [decisions.md](../decisions.md)
- [project.md](../../../.claude/skills/project-knowledge/references/project.md)
- [architecture.md](../../../.claude/skills/project-knowledge/references/architecture.md) — Data Model / Funnel Step Execution
- [patterns.md](../../../.claude/skills/project-knowledge/references/patterns.md) — Mongo-документи, declarative-індекси, Testing
- Код (взірці анотації-driven індексів + static-assert + lowercase/uppercase enum):
  - [Subscriber.java](../../../backend/src/main/java/com/botfunnel/subscriber/Subscriber.java)
  - [Bot.java](../../../backend/src/main/java/com/botfunnel/bot/Bot.java) — `@CompoundIndex` partialFilter + static-assert
  - [BotStatus.java](../../../backend/src/main/java/com/botfunnel/bot/BotStatus.java)
  - [RawUpdate.java](../../../backend/src/main/java/com/botfunnel/webhook/RawUpdate.java) — `@Indexed` partialFilter + static-assert
  - [SubscriberStatus.java](../../../backend/src/main/java/com/botfunnel/subscriber/SubscriberStatus.java)
  - [SubscriberRepository.java](../../../backend/src/main/java/com/botfunnel/subscriber/SubscriberRepository.java) — вузька repo-поверхня
- Цільові файли (створити):
  - `backend/src/main/java/com/botfunnel/funnel/Funnel.java`
  - `backend/src/main/java/com/botfunnel/funnel/FunnelStep.java`
  - `backend/src/main/java/com/botfunnel/funnel/FunnelExecution.java`
  - `backend/src/main/java/com/botfunnel/funnel/FunnelRepository.java`
  - `backend/src/main/java/com/botfunnel/funnel/FunnelExecutionRepository.java`
  - + enum-и: `FunnelStatus.java`, `ExecutionStatus.java`, `StepRunStatus.java`, `StepType.java`
- Тестова база: [AbstractIntegrationTest.java](../../../backend/src/test/java/com/botfunnel/AbstractIntegrationTest.java) — Testcontainers Mongo, auto-index-creation honoured

## Verification Steps

### Automated
- `cd backend && ./gradlew test --tests 'com.botfunnel.funnel.FunnelStatusEnumTest' --tests 'com.botfunnel.funnel.FunnelStepTest'` → unit зелені
- `cd backend && ./gradlew test -PrunSlow=true --tests 'com.botfunnel.funnel.FunnelIndexesIT'` → IT зелений (колекції + індекси створюються на Testcontainers Mongo)
- `cd backend && ./gradlew test` → немає регресій у наявних тестах

<!-- No Smoke / User verification: pure domain/persistence layer, no HTTP surface and no UI. -->

## Details

**Files:**
- `Funnel.java` — `@Document(collection = "funnels")` + `@CompoundIndexes({...})`. Поля за «Data Models».
  Static-блок звіряє partialFilter-літерал `'active'` з `FunnelStatus.active.name()`.
- `FunnelStep.java` — плаский POJO (Decision 12), без `@Document` (embedded). Усі type-specific поля
  nullable. Глибока копія для snapshot — copy-конструктор або статичний `copyOf`.
- `FunnelExecution.java` — `@Document(collection = "funnel_executions")` + `@CompoundIndexes({...})`.
  Static-блок звіряє `'running'`/`'waiting'` з `ExecutionStatus.{running,waiting}.name()`.
- `FunnelStatus.java` / `ExecutionStatus.java` / `StepRunStatus.java` — lowercase константи (Decision 14).
  За потреби HTTP-серіалізації lowercase enum уже й так серіалізується як `.name()` — на відміну від
  `BotStatus`/`SubscriberStatus` тут НЕ потрібен `@JsonValue.toLowerCase()`, бо константи вже lowercase.
- `StepType.java` — UPPERCASE дискримінатор типів кроків (немає partial-index залежності).
- `FunnelRepository.java` / `FunnelExecutionRepository.java` — `MongoRepository<…, String>`; лише методи з
  відомим викликачем у Wave 2 (інакше — мертвий код, який зріжуть рев'ювери).

**Dependencies:** немає задач-передумов (Wave 1). Жодних нових пакетів — лише наявні Spring Data Mongo
анотації. `spring.data.mongodb.auto-index-creation=true` уже стоїть в `application.properties` —
підтверджувати/не чіпати.

**Edge cases:**
- partialFilter для `funnel_executions` re-enter guard має бути `{ 'status': { $in: ['running','waiting'] } }`
  (lowercase). `RawUpdate` теж використовує `$in`-форму у partialFilter, тож взірець для СИНТАКСИСУ `$in` —
  саме `RawUpdate`. УВАГА: у `RawUpdate`/`Bot` enum-літерали UPPERCASE, а тут вони LOWERCASE (Decision 14) —
  бери з взірця форму `$in`, але літерали лишай lowercase (`'running'`/`'waiting'`). UPPERCASE-літерал не
  матчив би жодного рядка → guard став би мертвим.
- `(projectId)` на executions — простий `@Indexed` достатньо (як `@Indexed projectId` у `Bot`/`RawUpdate`);
  unique partial `(funnelId, subscriberId)` не покриває `projectId`-каскад-lookup.
- `unique` на partial `(funnelId, subscriberId)` критичний — без `unique=true` re-enter guard (Decision 8)
  не атомарний.
- `triggerValue` може бути `""` (голий `/start`) — partial-unique індекс має коректно трактувати порожній
  рядок як значення ключа (це не null), що нормально для compound-unique.
- Snapshot-копія має бути глибокою (нові `FunnelStep`-обʼєкти), інакше правка `Funnel.steps` зачепить
  in-flight `FunnelExecution.stepsSnapshot` (порушення Decision 3).

**Implementation hints (НЕ псевдокод):**
- Дзеркаль анотаційний стиль `Bot.java`/`RawUpdate.java`: `@Document(collection=...)`, `@CompoundIndexes`,
  `@CompoundIndex(name=..., def="{...}", unique=..., partialFilter="{...}")`, `@Indexed` для одиничного поля.
- Іменуй індекси явно (`name=...`) у тому ж стилі (`projectId_status`, `status_nextRunAt`,
  `funnelId_subscriberId_unique_active`, `projectId_triggerType_triggerValue_unique_active`).
- Static class-load assert копіюй СТРУКТУРНО з `Bot`/`RawUpdate` (той самий патерн: статичний блок, що
  кидає `IllegalStateException` при розбіжності), АЛЕ напрямок літералів інвертований. У `Bot`/`RawUpdate`
  enum-константи UPPERCASE, і partialFilter містить UPPERCASE-літерали (напр. `'ACTIVE'`). Тут же
  enum-константи LOWERCASE (Decision 14), тож звіряй LOWERCASE рядкові літерали (`"running"`, `"waiting"`,
  `"active"`) проти `.name()`. НЕ копіюй UPPERCASE-літерали зі взірців — вони б не матчили `.name()` і
  знеактивили б partial-індекс. Тобто: бери лише форму static-блоку, а самі літерали лишай lowercase.
- IT перевіряй фактичні створені індекси через `mongoTemplate.getCollection("...").listIndexes()` і звіряй
  `key`, `unique`, `partialFilterExpression`. Не покладайся лише на «контекст піднявся».
- POJO-стиль кодбази — звичайні приватні поля + явні getter/setter (як у `Subscriber`/`Bot`), не Lombok
  (у наявних сутностях Lombok не використовується).

## Reviewers

- **code-reviewer** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-1/code-reviewer-{round}.json`
- **security-auditor** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-1/security-auditor-{round}.json`
- **test-reviewer** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-1/test-reviewer-{round}.json`

## Post-completion

- [ ] Записати краткий отчёт в decisions.md по шаблону (Summary: 1-3 предложения, ревью со ссылками на JSON, без таблиц файндингов и дампов)
- [ ] Если отклонились от спека — описать отклонение и причину
- [ ] Обновить user-spec/tech-spec если что-то изменилось
