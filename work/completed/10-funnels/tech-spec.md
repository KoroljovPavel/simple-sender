---
created: 2026-05-31
status: approved
branch: dev
size: L
---

# Tech Spec: 10-funnels — Лінійні воронки (Фаза 1)

## Solution

Будуємо рушій лінійних воронок у новому модулі `com.botfunnel.funnel` поверх наявного стеку
(MongoDB sync + JobRunr 7.3.2 + Spring MVC on virtual threads). Дві колекції: `funnels` (визначення
воронки зі вбудованим списком кроків і тригером) та `funnel_executions` (per-subscriber стан
проходження зі **snapshot** кроків). Рушій складається з:

1. **CRUD-шару** (`FunnelController` + `FunnelService`) — створення/список/редагування/видалення/
   активація воронок під `/api/v1/projects/{projectId}/funnels`, з `requireOwned` першим.
2. **Тригер-шару** (`FunnelTriggerService` — реальна заміна `NoOpFunnelTriggerService`) — викликається
   вже наявним `ProcessTelegramUpdateJob` через `fire(...)`/`cancelActiveFor(...)`: матчить воронку за
   `(on_start, trigger_value)`, створює `funnel_execution` зі snapshot кроків і запіненим
   `telegramBotId`, застосовує re-enter guard. Всі власні помилки ковтає+логує (isolation).
3. **Engine-шару** (`FunnelExecutionEngine`) — JobRunr `@Recurring(interval="PT30S")` sweep, що
   atomic-claim'ить прострочені виконання (`findAndModify` CAS) і виконує кроки через діспетч
   `StepType` → executor. Delay через `nextRunAt`, ніколи `Thread.sleep`. At-most-once per step через
   per-step claim `pending→in_progress→done`.
4. **Допоміжних компонентів:** `VariableTemplateRenderer` (чиста утиліта підстановки змінних +
   екранування), `TelegramSender.sendPhoto` (нове), `SubscriberCustomFieldsService` (винесена зі
   `SubscriberCustomFieldsController` спільна логіка set-custom-field без request-scope залежностей).
5. **Фронтенд:** сторінка списку воронок + вертикальний редактор кроків (`components/funnels/*`,
   `stores/funnels.ts`) за взірцем custom-fields CRM.

Engine спирається на наявні патерни atomic-claim (`ProcessTelegramUpdateJob.handleStart`,
`ExportSubscribersJob`, `BotConnectRaceIT`) і не вводить нових інфраструктурних залежностей.

## Architecture

### What we're building/modifying

**Нове (backend, `com.botfunnel.funnel`):**
- **`Funnel`** (`@Document("funnels")`) — визначення воронки: `projectId`, `name`, `description`,
  `status` (draft/active/paused), `triggerType` (`on_start`), `triggerValue` (String, може бути порожній),
  `allowReEnter` (boolean, default false), embedded `List<FunnelStep> steps`, таймстемпи.
- **`FunnelStep`** — плаский embedded POJO: `stepType` (enum), `order` (int) + nullable type-specific
  поля (`text`, `parseMode`, `imageUrl`, `caption`, `delayValue`, `delayUnit`, `tagSlug`,
  `customFieldKey`, `customFieldValue`).
- **`FunnelExecution`** (`@Document("funnel_executions")`) — per-subscriber стан: `projectId` (top-level,
  для каскаду), `funnelId`, `subscriberId`, `telegramBotId` (пін), `status`
  (running/waiting/completed/cancelled/failed), `currentStepIndex`, `stepRunStatus`
  (pending/in_progress/done), `nextRunAt`, embedded `List<FunnelStep> stepsSnapshot`, таймстемпи.
- **`FunnelRepository`**, **`FunnelExecutionRepository`** — Spring Data Mongo repos.
- **`FunnelService`** — CRUD + валідація активації + конфлікт тригера.
- **`FunnelController`** — REST під `/api/v1/projects/{projectId}/funnels`.
- **`FunnelTriggerService` (реальна реалізація)** — замінює `NoOpFunnelTriggerService`.
- **`FunnelExecutionEngine`** — sweep `@Recurring` + step-runner + step-executors.
- **`VariableTemplateRenderer`** — чиста утиліта підстановки змінних.
- **DTO** під `funnel/dto/`.

**Модифікуємо (backend):**
- **`TelegramSender`** — додати `sendPhoto(botId, chatId, imageUrl, caption, parseMode, ownerId)`, що
  переюзає `sendWithRateLimitRetry` + `dispatchSubscriberHook` (block/delete на 403/400-chat-not-found).
- **`SubscriberCustomFieldsController`** — винести inline validate→update→`recordCustomFieldsSet` логіку
  в новий `SubscriberCustomFieldsService`; контролер делегує.
- **`SubscriberService`/`SubscriberServiceImpl`** — додати lookup-by-chat (`(projectId, telegramBotId,
  chatId)`) для рушія (делегує до наявного `SubscriberRepository`-методу).
- **`ProjectHardDeleteJob`** — додати каскад `funnels` + `funnel_executions` перед drop `projects`.
- **`application.properties` / `.env.example` / `docs/local-setup.md`** — нові env vars.

**Нове (frontend):**
- **`pages/projects/[projectId]/funnels/index.vue`** — список воронок + create dialog.
- **`pages/projects/[projectId]/funnels/[funnelId].vue`** — редактор кроків + тригер + активація.
- **`components/funnels/*`** — `FunnelStepsList.vue`, `AddStepDialog.vue`, `EditStepDialog.vue`,
  `FunnelTriggerSettings.vue` (deep-link + Copy).
- **`stores/funnels.ts`** — `useFunnelsStore`.
- **i18n** — `funnels.*` + `errors.funnels.*` у `uk.json` та `en.json`.

### How it works

**Створення/редагування (HTTP):** Власник через `FunnelController` створює воронку (`draft`), додає/
редагує/переміщує кроки. **Контракт редагування кроків:** PATCH приймає **повний масив `steps`**
(client-set порядок = позиція в масиві; сервер перезаписує `order` за індексом — переміщення ↑/↓ це
просто новий масив). `FunnelService` валідує ліміт `FUNNEL_MAX_STEPS` і per-type обов'язкові поля
(порожній text → 422 на рівні DTO bean-validation; Delay <1хв, невалідний imageUrl, tagSlug-regex,
customFieldKey — у сервісі/DTO). **Статус-переходи** (явні endpoints): `POST .../activate`,
`POST .../pause` (`active→paused`), `POST .../activate` повторно (`paused→active`, з тією ж валідацією
конфлікту). Активація: ≥1 крок + обов'язкові поля + відсутність конфлікту `(triggerType, triggerValue)` з
іншою `active`-воронкою проєкту → `status=active`. Конфлікт ловиться сервісною перевіркою І partial-unique
індексом (defense-in-depth); `DuplicateKeyException` на activate ловиться й мапиться в `422
funnel_trigger_conflict` (не 500). **`trigger_value`** валідується patterns'ом deep-link
payload (`^[A-Za-z0-9_-]{0,64}$`; порожній дозволено = голий `/start`) — пробіли/спецсимволи зламали б
посилання, тож 422.

**DTO-контракт:** `FunnelResponse` (single GET) містить метадані + повний `steps` + (для active) готовий
`deepLink` (`t.me/<botUsername>?start=<triggerValue>`); список (`GET .../funnels?status=`) повертає
метадані без `steps` (легкий список). Deep-link присутній у GET active-воронки (не лише у відповіді
activate), щоб редактор показував його після перезавантаження сторінки. `botUsername` резолвиться з
CONNECTED-бота проєкту.

**Тригер (із webhook-воркера):** `ProcessTelegramUpdateJob.handleStart` (уже існує) після
`upsertFromTelegramUpdate` (який ставить `Subscriber.status=active` ДО `fire()` — критичний порядок:
інакше перший send self-cancel'нувся б на status-gate) викликає
`funnelTriggerService.fire(projectId, chatId, "on_start", payload)`. Цей порядок уже забезпечений наявним
кодом воркера — НЕ змінюємо його. Реальний `fire()`:
1. Резолвить CONNECTED-бота `(projectId, status=CONNECTED)` (як воркер), бере `telegramBotId`.
2. Резолвить підписника за `(projectId, telegramBotId, chatId)`; немає → log+skip.
3. Знаходить `active`-воронку проєкту з `(triggerType=on_start, triggerValue=payload)` (exact match;
   порожній payload → воронка з порожнім `triggerValue`). Немає → нічого.
4. `allowReEnter=false`: insert `funnel_execution` зі snapshot кроків; unique partial index
   `(funnelId, subscriberId)` на `running|waiting` робить повторний `/start` атомарним no-op (DuplicateKey
   → swallow). `allowReEnter=true`: атомарно cancel наявного running|waiting, потім insert нового з кроку 0.
5. Будь-який виняток усередині `fire()` ковтається+логується (НЕ кидати — інакше webhook-update піде у
   FAILED+retry).

`cancelActiveFor(projectId, chatId)` на `/stop`: усі running|waiting виконання підписника → `cancelled`.

**Sweep (JobRunr `@Recurring(interval="${app.funnel.scheduler-interval:PT30S}")`):** Кожні ~30с
`FunnelExecutionEngine.sweep()` бере
виконання `status ∈ {running, waiting}` з `nextRunAt <= now` (індекс `(status, nextRunAt)`). Для кожного:
1. **Atomic claim** виконання — ОДИН `findAndModify`(критерій `_id` + `status ∈ {running,waiting}` +
   `nextRunAt<=now` + `stepRunStatus=pending`; update `stepRunStatus=in_progress`; returnNew). Це **єдина**
   атомарна операція (не дві окремі), тож вікна гонки між «обрав» і «застовпив» немає. null → інша
   репліка/тік виграв, skip.
2. Pre-send status-gate: `Subscriber.status != active` → виконання `cancelled`.
3. Bot-pin check: `telegramBotId` уже не CONNECTED → виконання `failed`.
4. Виконати `currentStepIndex`-крок через executor за `StepType`:
   - **SendMessage:** `VariableTemplateRenderer.render` → `TelegramSender.sendText`. Текст >4096 після
     підстановки → trim+WARN.
   - **SendImage:** `TelegramSender.sendPhoto` (URL+екранований caption). Telegram сам фетчить URL (наш
     бекенд НЕ дереференсить — без SSRF). Недоступний/невалідний URL → Telegram відповідає 400 БЕЗ "chat
     not found" → terminal reason `OTHER` → виконання `failed` (відрізняється від 400-chat-not-found, що дає
     `cancelled`+flip). При збереженні кроку — лише format-перевірка `imageUrl` (http/https-схема, 422
     інакше); сервер URL не фетчить.
   - **Delay:** `nextRunAt = now + duration`, `status=waiting`, `stepRunStatus=pending`, **не** інкрементить
     крок одразу — наступний sweep просуне.
   - **AddTag/RemoveTag:** `SubscriberServiceImpl.addTag/removeTag`.
   - **SetCustomField:** `SubscriberCustomFieldsService.setOne`; невалідне → `failed`; видалене поле → skip.
5. Mark step `done`, інкремент `currentStepIndex`, скинути `stepRunStatus=pending`. НЕ-Delay кроки
   виконуються підряд у межах одного тіку (цикл while). Після останнього → `completed`.
- Send-fail: 403/400-chat-not-found → `TelegramSender` авто-flip blocked/deleted, виконання `cancelled`;
  інші термінальні → `failed`. Без funnel-level ретраїв.

**Каскад:** `ProjectHardDeleteJob` видаляє `funnels` + `funnel_executions` (за top-level `projectId`)
перед drop `projects`.

### Shared resources

| Resource | Owner (creates) | Consumers | Instance count |
|----------|----------------|-----------|----------------|
| `TelegramSender` (RestClient bean) | `bot/` module | webhook worker, FunnelExecutionEngine | 1 (singleton `@Component`) |
| `MongoTemplate` | Spring Data autoconfig | FunnelService, FunnelTriggerService, FunnelExecutionEngine | 1 (singleton) |
| JobRunr background-job-server | jobrunr-spring-boot-3-starter | sweep `@Recurring` + existing recurring jobs | 1 per replica (distributed lock) |
| `SubscriberServiceImpl` | `subscriber/` module | engine (addTag/removeTag/lookup), webhook worker | 1 (singleton, sole writer of `subscriber_events`) |
| `SubscriberCustomFieldsService` (new) | `subscriber/` module | controller + engine | 1 (singleton) |

## Decisions

### Decision 1: Рушій на JobRunr `@Recurring` sweep + `funnel_executions`, не BullMQ/pg-boss
**Decision:** Sweep-джоб поверх MongoDB-колекції `funnel_executions`, не черговий рушій із референс-ТЗ.
**Rationale:** Наявний стек; `architecture.md` → «Funnel Step Execution» уже описує саме цей патерн.
BullMQ/pg-boss у проєкті відсутні. Підтримує US-обмеження «JobRunr + MongoDB».
**Alternatives considered:** BullMQ/pg-boss — немає в стеку, додало б Postgres/Redis-черги; відхилено.
Supports: user-spec «Ограничения → Стек», US-критерій engine.

### Decision 2: Sweep через `@Recurring(interval=...)` (sub-minute), не Spring `@Scheduled`, не cron-1хв
**Decision:** JobRunr `@Recurring(interval = "${app.funnel.scheduler-interval:PT30S}")` — інтервал
читається з property `app.funnel.scheduler-interval` (env `FUNNEL_SCHEDULER_INTERVAL`, ISO-8601 duration,
default `PT30S`) через Spring placeholder-резолвинг анотації.
**Rationale:** Підтверджено через `javap`: `org.jobrunr.jobs.annotations.Recurring` у jobrunr-7.3.2.jar
має член `interval()` (поряд із `cron()`/`id()`/`zoneId()`). JobRunr poll-interval за замовчуванням 15s
(`BackgroundJobServerConfiguration.DEFAULT_POLL_INTERVAL` — **у репо НЕ перевизначено**), тож 30с-інтервал
валідний (interval ≥ poll). JobRunr `@Recurring` дає розподілений лок із коробки — multi-replica-safe
(US-ризик «Multi-replica подвійний sweep»). Spring `@Scheduled` НЕ має розподіленого локу → відхилено.
Cron-1хв був fallback'ом у user-spec, але не потрібен, бо `interval()` підтверджено в jar. Коректність
(at-most-once) гарантує per-execution atomic claim **незалежно** від каденції. Env перейменовано з
`FUNNEL_SCHEDULER_INTERVAL_SECONDS` (число) на `FUNNEL_SCHEDULER_INTERVAL` (ISO-8601), бо `interval()`
приймає рядок Duration — див. User-Spec Deviations.
**Alternatives considered:** Spring `@Scheduled(fixedDelay)` — нема розподіленого локу, дублює sweep на
репліках; cron `* * * * *` (1хв) — точність Delay ±60с, гірший UX, не потрібен; числовий env у секундах —
не лягає на тип `interval()` (ISO-8601 String).
**Durability:** інваріант interval ≥ poll тримається лише поки poll = default 15s; Task 8 явно пінить
`org.jobrunr.background-job-server.poll-interval-in-seconds=15`, щоб майбутній підйом poll вище 30с не
деградував каденцію тихо.
Supports: user-spec «Ограничения → каденція», US-ризик multi-replica.

### Decision 3: Snapshot списку кроків на виконання при старті
**Decision:** `FunnelExecution.stepsSnapshot` = глибока копія `Funnel.steps` на момент `fire()`.
**Rationale:** Захищає in-flight виконання від правок воронки без версіонування й без блокування редагування —
найпростіший варіант. In-flight доходить за своїм snapshot; нові запуски — за новою версією.
**Alternatives considered:** Версіонування воронки + пін версії — складніше, зайве для Фази 1; блокування
правок active-воронки — гірший UX. Відхилено.
Supports: US-критерій «Snapshot», US-сценарій 5.4.

### Decision 4: At-most-once per step через atomic claim + per-step `pending→in_progress→done`
**Decision:** Claim виконання (`findAndModify` CAS) ПЕРЕД виконанням кроку; per-step `stepRunStatus`
прапор; на retry claimed/done кроку — не пере-виконувати.
**Rationale:** Exactly-once недосяжне (JobRunr default 10 retries, рестарти, репліки); дублювання
повідомлень — найгірший UX. Claim перед send + re-entry guard гарантують at-most-once навіть на репліках.
Дзеркалить наявні патерни (`ProcessTelegramUpdateJob.handleStart`, `ExportSubscribersJob`,
`BotConnectRaceIT`). Enum-статуси в `findAndModify` пишемо як `.name()`-літерали (byte-match індексів).
**Alternatives considered:** Покладатися на JobRunr-дедуплікацію — не покриває per-step семантику; відхилено.
Supports: US-критерії «At-most-once per step», «Multi-replica безпека».

### Decision 5: Замінити `NoOpFunnelTriggerService` єдиним реальним `@Service`
**Decision:** Видалити `NoOpFunnelTriggerService`, додати реальний `@Service FunnelTriggerService`-impl
(той самий інтерфейс, та сама точка виклику в `ProcessTelegramUpdateJob`).
**Rationale:** Два `@Service` того самого інтерфейсу без `@Primary` → `NoUniqueBeanDefinitionException`
на старті. Точка виклику `fire(...)`/`cancelActiveFor(...)` уже існує.
**Alternatives considered:** Додати другий бін із `@Primary` — зайвий мертвий код; відхилено.
Supports: US-ризик «Два @Service», US-рішення.

### Decision 6: `fire()` ізолює власні помилки (swallow+log), ніколи не кидає у webhook-воркер
**Decision:** Тіло `fire()` обгорнуте try/catch-Throwable зі swallow+log; назовні виняток не йде.
**Rationale:** `fire()` викликається всередині `ProcessTelegramUpdateJob.dispatch`, обгорнутого
catch-Throwable+rethrow+JobRunr retry. Виняток із `fire()` поронив би raw_update у FAILED і спричинив
retry-шторм (повторний upsert + re-fire). Поточний no-op ніколи не кидав.
**Alternatives considered:** Дати винятку піднятися — retry-шторм; відхилено.
Supports: US-критерій «fire() isolation», US-ризик.

### Decision 7: Пін `telegramBotId` на виконанні; не-CONNECTED на send → `failed`
**Decision:** `telegramBotId` снапшотиться на `FunnelExecution` при старті; перед кожним send —
перевірка, що цей бот ще CONNECTED, інакше виконання `failed`.
**Rationale:** Reconnect бота не має «таємно» переключати воронку на іншого бота — краще передбачуваний
`failed`, ніж надсилання з несподіваного бота.
**Alternatives considered:** Re-resolve CONNECTED-бота на кожен send — змінює відправника посеред воронки;
відхилено.
Supports: US-критерій «Bot pinning», US-ризик «Бот reconnect».

### Decision 8: Re-enter guard через unique partial index `(funnelId, subscriberId)` на `running|waiting`
**Decision:** Єдиний механізм — unique partial index; query-time-перевірку не дублюємо.
**Rationale:** Індекс дає атомарну гарантію без гонок (за взірцем `raw_updates` unique + DuplicateKey
self-heal). `allowReEnter=false`: повторний insert ловить DuplicateKey → swallow (ігнор). `=true`:
атомарний cancel старого ПЕРЕД insert нового.
**Alternatives considered:** Query-then-insert — гонка двох паралельних `/start`; відхилено.
Supports: US-критерій «Re-enter».

### Decision 9: Delay через `nextRunAt`, ніколи `Thread.sleep`; мін 1 хв
**Decision:** Delay-крок ставить `nextRunAt = now + duration`, `status=waiting`; sweep просуває.
Тривалість <1 хв → 422 при збереженні/активації.
**Rationale:** `Thread.sleep` запінив би JobRunr-worker-тред на години/дні. Жодного прецеденту sleep-для-
затримки в репо немає.
**Alternatives considered:** `Thread.sleep` — pin потоку; відхилено.
Supports: US-критерій «Delay», US-обмеження.

### Decision 10: `parse_mode` default = None; екранування підставлених значень для HTML/MarkdownV2
**Decision:** Picker None/HTML/MarkdownV2, default None. Розмітка автора лишається як є; **підставлені**
значення змінних екрануються за правилами обраного режиму. Те саме стосується **caption** кроку SendImage
— caption проганяється через той самий `VariableTemplateRenderer` з екрануванням (caption — рівноцінний
injection-sink). Caption-ліміт Telegram = 1024 (не 4096); текст повідомлення = 4096.
**Rationale:** Звичайний текст найбезпечніший із підстановкою (спецсимвол в імені не зламає надсилання).
Для HTML/MarkdownV2 екрануємо лише значення змінних (і в text, і в caption), щоб не зламати розмітку автора
й не дати інʼєкції (security A03).
**Alternatives considered:** Default HTML — крихке з довільними іменами; екранувати весь текст — зламало б
авторську розмітку. Відхилено.
Supports: US-критерій «Templating», «Send Message».

### Decision 11: Винести Set-Custom-Field у `SubscriberCustomFieldsService` (спільний, без request-scope)
**Decision:** Витягнути inline validate→update→`recordCustomFieldsSet` із
`SubscriberCustomFieldsController` у новий `@Service SubscriberCustomFieldsService` із методом
`setOne(projectId, subscriberId, key, value)`; контролер делегує.
**Rationale:** Engine працює в JobRunr-воркері без HTTP-контексту, тож не може переюзати inline-логіку
контролера. Спільний сервіс уникає дублювання CRM-логіки й зберігає sole-writer audit-семантику
(`subscriber_custom_field_set` через `SubscriberServiceImpl.recordCustomFieldsSet`).
**Alternatives considered:** Реплікувати послідовність у движку — дублювання + ризик розсинхрону аудиту;
відхилено.
Supports: US-критерій «Set Custom Field», US-ризик «Дублювання CRM-логіки».

### Decision 12: Пласка модель `FunnelStep` + `StepType` enum (без `_class` дискримінатора)
**Decision:** Один embedded POJO `FunnelStep` зі `stepType` enum + nullable type-specific полями.
**Rationale:** Матчить «flat POJO» bias кодбази (`CustomFieldDefinition`, `Subscriber.customFields`); немає
прецеденту полиморфного `_class`-дискримінатора. Для Фази-1 лінійних кроків достатньо.
**Alternatives considered:** Spring Data `_class` дискримінатор — додає `_class` у BSON, нема прецеденту;
відхилено (можна ввести у Фазі 2 за потреби).
Supports: [TECHNICAL] — обрана найпростіша модель під наявні конвенції; обслуговує US «список кроків».

### Decision 13: Extend `TelegramSender` методом `sendPhoto`, не sibling-клас
**Decision:** Додати `sendPhoto` у наявний `TelegramSender`, переюзаючи `sendWithRateLimitRetry`,
token-decrypt, 429/5xx-backoff, audit і `dispatchSubscriberHook`.
**Rationale:** Retry/backoff/timeout/hook-машинерія нетривіальна; sibling дублював би її та Decision-4
subscriber-hooks. Extend нижче-ризиковий.
**Alternatives considered:** Окремий клас `TelegramPhotoSender` — дублювання; відхилено.
Supports: [TECHNICAL] — обслуговує US-критерій «Send Image».

### Decision 14: Lowercase enum-константи для статусів воронки/виконання/кроку
**Decision:** `FunnelStatus {draft, active, paused}`, `ExecutionStatus {running, waiting, completed,
cancelled, failed}`, `StepRunStatus {pending, in_progress, done}` — lowercase константи.
**Rationale:** Матчить документований `architecture.md` (`running|waiting|...`) і наявну
`SubscriberStatus` lowercase-конвенцію. У `findAndModify`-критеріях пишемо `.name()`-літерали.
**Alternatives considered:** UPPERCASE (як `BotStatus`/raw_updates) — розходиться з architecture.md описом
funnel-статусів; відхилено для консистентності з документованою схемою.
Supports: [TECHNICAL] — консистентність схеми.

### Decision 15: Зберігати завершені виконання безстроково (без TTL)
**Decision:** `funnel_executions` без TTL-індексу.
**Rationale:** Потрібні для re-enter guard і майбутньої аналітики. TTL — рішення епіку аналітики.
**Alternatives considered:** TTL на completed/cancelled — зламало б re-enter guard semantics; відхилено.
Supports: US-рішення «зберігати безстроково».

### Decision 16: Спостережуваність через структуровані логи (ідентифікатори/коди, НЕ payload)
**Decision:** Кожен перехід стану виконання логується іменованими лог-константами (за взірцем
`ProcessTelegramUpdateJob`): claim win/loss, step `in_progress`/`done`, send-fail із terminal reason,
`completed`/`cancelled`/`failed`, re-enter ігнор. Логуємо **лише** ідентифікатори+стан+код-причину
(`executionId`, `funnelId`, `subscriberId`, `stepType`, `currentStepIndex`, terminal reason) — **ніколи**
відрендерений текст/caption/значення кастом-полів.
**Rationale:** JobRunr dashboard вимкнено (`dashboard.enabled=false`) → логи — єдина видимість (US-критерій
«Observability»). Відрендерені тіла містять PII підписника; logs-only + log-every-transition + indefinite
retention без TTL → витік PII (security A09, GDPR). Тому payload не логуємо.
**Alternatives considered:** Логувати повні тіла для дебагу — витік PII; увімкнути dashboard — поза скоупом
Фази 1. Відхилено.
Supports: US-критерій «Observability»; security A09 (PII).

### Decision 17: Обмеження навантаження sweep — batch cap + межа послідовних send без Delay
**Decision:** Sweep claim'ить за тік не більше `FUNNEL_SWEEP_BATCH_SIZE` (default 200) виконань, сортовано
за `nextRunAt` asc (найстаріші прострочені перші — без голодування); коли тік упирається в cap — WARN-лог
насичення. Цикл послідовних НЕ-Delay кроків у межах одного виконання обмежений стелею `FUNNEL_MAX_STEPS`
(бо snapshot кінечний), без штучних send-флудів.
**Rationale:** Без batch-cap claim на великій базі прострочених виконань завантажив би тік; cap дає рівне
просування. Стеля кроків + наявний `/start` rate-limit (100/min) стримують масовий старт. Захищає US-ризик
«Навантаження sweep».
**Alternatives considered:** Необмежений claim — сплеск навантаження/Telegram-флуд; відхилено.
Supports: US-ризик «Навантаження sweep» (додаткова митигація — див. User-Spec Deviations).

## Data Models

**`funnels` collection (`Funnel`):**
```
id: String (@Id)
projectId: String                 // indexed (cascade + lookup)
name: String                      // required
description: String               // nullable
status: FunnelStatus              // draft | active | paused
triggerType: String               // "on_start" (Phase 1)
triggerValue: String              // exact-match key; "" = bare /start
allowReEnter: boolean             // default false
steps: List<FunnelStep>           // embedded, ordered; max FUNNEL_MAX_STEPS
createdAt, updatedAt: Instant
```
Indexes: `(projectId, status)` lookup/list; partial-unique `(projectId, triggerType, triggerValue)`
filtered `status=active` — захищає від конфлікту тригера двох active-воронок (defense-in-depth поверх
сервісної перевірки).

**`FunnelStep` (embedded POJO):**
```
stepType: StepType                // SEND_MESSAGE|SEND_IMAGE|DELAY|ADD_TAG|REMOVE_TAG|SET_CUSTOM_FIELD
order: int
// SEND_MESSAGE
text: String                      // ≤4096 raw; parseMode-aware
parseMode: String                 // null|HTML|MarkdownV2 (null = None)
// SEND_IMAGE
imageUrl: String
caption: String                   // nullable
// DELAY
delayValue: Integer               // ≥1 minute total
delayUnit: String                 // MIN|HOUR|DAY
// ADD_TAG / REMOVE_TAG
tagSlug: String                   // ^[a-z0-9_-]{1,32}$
// SET_CUSTOM_FIELD
customFieldKey: String
customFieldValue: Object          // validated by CustomFieldValueValidator at run time
```

**`funnel_executions` collection (`FunnelExecution`):**
```
id: String (@Id)
projectId: String                 // top-level (cascade) — indexed
funnelId: String
subscriberId: String
telegramBotId: Long               // pinned at start
status: ExecutionStatus           // running|waiting|completed|cancelled|failed
currentStepIndex: int
stepRunStatus: StepRunStatus      // pending|in_progress|done
nextRunAt: Instant                // due time; sweep predicate
stepsSnapshot: List<FunnelStep>   // deep copy at fire()
createdAt, updatedAt, completedAt: Instant
```
Indexes: `(status, nextRunAt)` sweep claim (критичний); `(projectId)` cascade; unique partial
`(funnelId, subscriberId)` filtered `status IN [running, waiting]` — re-enter guard.

**DTO (`funnel/dto/`):** `CreateFunnelRequest` (name + опц. description), `UpdateFunnelRequest` (метадані +
**повний масив `steps`** — позиція = порядок, сервер перезаписує `order`; + `triggerType`/`triggerValue`/
`allowReEnter`), `FunnelResponse` (метадані + `steps` + `deepLink` для active, single-GET),
`FunnelSummaryResponse` (метадані без `steps`, для списку), `FunnelStepDto`. Кроки валідуються `@Valid`
(per-type обов'язкові поля; порожній text → 422; imageUrl http/https; tagSlug `^[a-z0-9_-]{1,32}$`;
trigger_value `^[A-Za-z0-9_-]{0,64}$`). Активація повертає `FunnelResponse` із `deepLink`.

## Dependencies

### New packages
None — увесь функціонал на наявних бекенд/фронтенд залежностях.

### Using existing (from project)
- `org.jobrunr:jobrunr-spring-boot-3-starter:7.3.2` — `@Recurring(interval=...)` sweep (член `interval()`
  підтверджено в jobrunr-7.3.2.jar через `javap`; poll-interval default 15s, не перевизначений).
- `MongoTemplate` / Spring Data Mongo — `findAndModify` atomic claim, repos, annotation-driven індекси
  (`auto-index-creation=true`).
- `TelegramSender` (`bot/`) — `sendText` + новий `sendPhoto`.
- `SubscriberServiceImpl`, `SubscriberRepository`, `TagService`, `CustomFieldValueValidator`
  (`subscriber/`) — мутації підписника, lookup, валідація.
- `ProjectService.requireOwned` (`project/`) — anti-IDOR guard.
- `AppException` / `GlobalErrorHandler` (`common/`) — коди помилок.
- Frontend: `useApi`, `useApiError`, vee-validate+zod, shadcn-vue, `@nuxtjs/i18n` (parity gates),
  Pinia, Playwright/Vitest.

## Testing Strategy

**Feature size:** L

### Unit tests
- **`VariableTemplateRenderer`:** `{user.first_name|last_name|username}`, `{custom.<key>}` підстановка;
  unknown/порожнє → `""`; `{{`/`}}` → `{`/`}`; Double `30.0`→`30`, Instant→ISO; екранування під HTML і
  MarkdownV2 (значення екрануються, авторська розмітка — ні; повний MarkdownV2-charset). (Взірець:
  `TelegramCommandParserTest`.)
- **Step-executors:** SendMessage (>4096 → trim-**and-continue**, не fail, + WARN), SendImage (caption
  екранується+trim 1024), Delay (`nextRunAt` розрахунок, <1хв→422), AddTag/RemoveTag, SetCustomField
  (валідне/невалідне→failed/видалене поле→skip).
- **Trigger-matcher:** exact-match payload; порожній payload→порожній triggerValue; немає збігу→null.
- **Execution state machine:** переходи running→waiting→running→completed; cancel; failed.
- **Funnel activation validation:** ≥1 крок, обов'язкові поля, конфлікт тригера.
- **Frontend (Vitest):** компоненти редактора (AddStepDialog/EditStepDialog/FunnelStepsList,
  FunnelTriggerSettings deep-link), i18n parity (`required-keys.spec.ts`).

### Integration tests
На `AbstractIntegrationTest` (Testcontainers Mongo + `JobRunrInMemoryConfig` + MockWebServer Telegram),
sweep тестується прямим викликом методу (за взірцем `ProjectHardDeleteJobIT`), таймінг детермінується
ін'єкцією `java.time.Clock` (`ClockConfig`):
- Тригер → виконання створено й проходить кроки end-to-end.
- Послідовні НЕ-Delay кроки за один sweep-тік.
- Delay → `nextRunAt` виставлено → наступний sweep resume.
- Idempotency: повторний прогін claimed-кроку не дублює send; **окремий сценарій**: крах після send /
  перед `done`-flip (рестарт інстанса) не пере-надсилає.
- Atomic-claim race на двох «репліках» (взірець `SubscriberExportConcurrencyIT` / `BotConnectRaceIT`).
- **`fire()` error-isolation** (HIGH): виняток усередині `fire()` ковтається й НЕ марковує raw_update
  FAILED / НЕ спричиняє webhook-retry (Decision 6, US-критерій «fire() isolation»).
- **Реактивація-порядок** (HIGH): `/start` від `unsubscribed`-підписника — upsert ставить `active` ДО
  `fire()`, тож перший send не само-скасовується status-gate'ом (US-критерій «Реактивація»).
- **SendImage failure-matrix**: невалідний/недоступний URL (400 без chat-not-found) → `failed`;
  403/chat-not-found → cancelled+flip (окремо від SendMessage).
- **Observability**: send-fail і термінальні переходи емітять лог-константи з кодами, БЕЗ payload
  (Decision 16) — тест перевіряє наявність константи й відсутність відрендереного тіла в логу.
- `/stop` → `cancelActiveFor` cancel; paused → no new + активні дограють; delete → cancel+remove.
- Re-enter: false→ігнор (unique partial index DuplicateKey), true→cancel+restart.
- Send-fail 403→cancelled / 5xx-exhausted→failed; status gating; bot pinning (не-CONNECTED→failed).
- Snapshot: правка кроків не чіпає in-flight.
- `requireOwned` + cross-project isolation (uniform 404).
- `ProjectHardDeleteJob` каскад видаляє funnels+funnel_executions.
- sub-minute `@Recurring` каденція реально реєструється й спрацьовує.
- `ProcessTelegramUpdateJobTest.startCommandFiresFunnelTrigger` — `fire(...)` після upsert (наявний).

### E2E tests
Playwright golden-path: створити лінійну воронку → додати/перемістити/видалити крок → налаштувати
тригер + побачити deep-link і «Copy» → активувати. Плюс дешевий page-test на error-гілку активації
(422: порожні кроки / конфлікт тригера → inline-помилка). Реальний Telegram-send не автоматизується
(MockWebServer в IT + ручний локальний прогін через runbook).

## Agent Verification Plan

**Source:** user-spec "Как проверить".

### Verification approach
Автоматичні тести (backend unit+IT, frontend Vitest+E2E, i18n parity gate) — основа. Понад це: per-task
smoke-перевірки (нижче в Implementation Tasks) для `sendPhoto` (MockWebServer-стиль),
sweep-каденції (IT), CRUD (MockMvc/curl). Реальний end-to-end Telegram-прохід — лише локально власником
(runbook `docs/staging-smoke/10-funnels.md`), бо HTTPS+setWebhook не автоматизувати без тунелю. CI/CD ще
немає → деплой ручний/локальний, post-deploy live-верифікація не застосовується для цієї фази.

### Tools required
curl/httpie (CRUD smoke), `./gradlew test`/`-PrunSlow=true`, `pnpm test`/`test:e2e`,
`node scripts/check-locales.mjs`. Playwright MCP не обов'язковий (E2E через `pnpm test:e2e`). Telegram MCP
не застосовується (реальний бот недоступний у CI).

## Risks

| Risk | Mitigation |
|------|-----------|
| Дублювання повідомлень (JobRunr retry / рестарт / репліки) | Atomic claim (`findAndModify` CAS) ПЕРЕД send + per-step `pending→in_progress→done` (Decision 4) |
| Drift таймінгу Delay (~30с sweep) | Документований ±~30с, мін 1 хв — для drip невидимо (Decision 9) |
| Навантаження sweep на великій базі | Індекс `(status, nextRunAt)`, batch atomic-claim; наявний `/start` rate-limit (100/min) стримує масовий старт |
| Зміна воронки під час активних виконань | Snapshot кроків на виконання (Decision 3) |
| `fire()` кидає виняток і поронить webhook-обробку | Ідемпотентний swallow+log усередині `fire()` (Decision 6) |
| Два `@Service FunnelTriggerService` → startup fail | Замінити NoOp, не додавати другий бін (Decision 5) |
| Бот reconnect посеред воронки | Пін `telegramBotId`; не-CONNECTED на send → `failed` (Decision 7) |
| Send у неактивний чат | Pre-send status-check → cancel (не покладатись на bounce) |
| Multi-replica подвійний sweep | JobRunr `@Recurring` розподілений лок + atomic claim (Decision 2, 4) |
| Дублювання CRM-логіки / псування аудиту | Set-Custom-Field у спільний сервіс; усі мутації лише через `SubscriberServiceImpl` (Decision 11) |
| sub-minute `@Recurring` не спрацьовує | Підтверджено `interval()` у jar (javap) + poll default 15s (interval≥poll); IT перевіряє реальну реєстрацію/спрацювання |

## User-Spec Deviations

- **Env var rename:** user-spec називає `FUNNEL_SCHEDULER_INTERVAL_SECONDS` (число секунд); tech-spec
  використовує `FUNNEL_SCHEDULER_INTERVAL` (ISO-8601 duration, default `PT30S`). Причина: JobRunr
  `@Recurring(interval=)` приймає рядок ISO-8601 Duration, а не число секунд — числовий env не лягає на
  тип. Поведінка (sweep ~30с) ідентична. → **[APPROVED 2026-05-31]**
- **Added: `FUNNEL_SWEEP_BATCH_SIZE`** (default 200) — не згадане в user-spec. Причина: обмежує claim за
  тік на великій базі прострочених виконань (мітигація US-ризику «Навантаження sweep»; Decision 17).
  → **[APPROVED 2026-05-31]**

<!-- Решта технічних рішень (cadence-механізм, моделювання кроків, sendPhoto extend, lowercase enums,
     observability) — у межах latitude user-spec ("каденція — рішення tech-spec") або суто внутрішні
     [TECHNICAL]-рішення. cron-1хв fallback НЕ використано (interval() підтверджено в jar) — вибір у межах
     дозволеного діапазону, не відхилення. -->

## Acceptance Criteria

Технічні критерії приймання (доповнюють користувацькі з user-spec):

- [ ] API повертає коректні коди: 201 (create), 200 (get/list/update), 204 (delete), 404 (uniform для
      чужого/soft-deleted/невалідного projectId|funnelId), 422 (ліміт кроків, конфлікт тригера, невалідні
      кроки, Delay <1хв), 400 (bean-validation).
- [ ] Індекси `funnels`/`funnel_executions` створюються при старті (annotation-driven,
      `auto-index-creation=true`); немає `@PostConstruct ensureIndex`.
- [ ] Усі тести зелені: backend unit+IT (`./gradlew test -PrunSlow=true`), frontend Vitest+E2E.
- [ ] i18n parity gate зелений (`uk.json`/`en.json` ключі збігаються; `funnels.*` + `errors.funnels.*`).
- [ ] Немає регресій у наявних тестах (зокрема webhook/subscriber/bot).
- [ ] `NoOpFunnelTriggerService` видалено; рівно один `FunnelTriggerService` `@Service` (контекст стартує).
- [ ] Enum-статуси в `findAndModify`-критеріях пишуться `.name()`-літералами.
- [ ] Кожен перехід стану виконання логується іменованою константою без відрендереного payload (PII).
- [ ] Atomic claim виконання — один `findAndModify` (не дві операції).
- [ ] Декрипт-токен бота не тече в лог із нового `sendPhoto`.

## Implementation Tasks

### Wave 1 (незалежні фундаменти)

#### Task 1: Доменна модель + персистенція воронок
- **Description:** Створити `Funnel`, `FunnelStep`, `FunnelExecution` сутності з enum-статусами
  (lowercase, Decision 14), репозиторії та annotation-driven індекси (`(status,nextRunAt)`, `(projectId)`,
  unique partial `(funnelId,subscriberId)` на running|waiting, partial-unique `(projectId,triggerType,
  triggerValue)` на active). Потрібно як фундамент для CRUD/тригера/движка. Результат: колекції з
  індексами стартують без помилок.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/Funnel.java`,
  `backend/src/main/java/com/botfunnel/funnel/FunnelStep.java`,
  `backend/src/main/java/com/botfunnel/funnel/FunnelExecution.java`,
  `backend/src/main/java/com/botfunnel/funnel/FunnelRepository.java`,
  `backend/src/main/java/com/botfunnel/funnel/FunnelExecutionRepository.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/subscriber/Subscriber.java`,
  `backend/src/main/java/com/botfunnel/bot/Bot.java`,
  `backend/src/main/java/com/botfunnel/webhook/RawUpdate.java`

#### Task 2: VariableTemplateRenderer (чиста утиліта)
- **Description:** Реалізувати підстановку `{user.*}`/`{custom.<key>}`, unknown→"", `{{`/`}}` escape,
  людський рендер Double/Boolean/Instant, екранування підставлених значень під HTML/MarkdownV2
  (Decision 10). Чиста статична утиліта. Результат: повне unit-покриття renderer'а.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/VariableTemplateRenderer.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/webhook/TelegramCommandParser.java`,
  `backend/src/main/java/com/botfunnel/subscriber/CustomFieldValueValidator.java`,
  `backend/src/main/java/com/botfunnel/subscriber/Subscriber.java`

#### Task 3: TelegramSender.sendPhoto
- **Description:** Додати `sendPhoto(botId, chatId, imageUrl, caption, parseMode, ownerId)` у наявний
  `TelegramSender`, переюзаючи retry/429/5xx-backoff, token-decrypt, audit і `dispatchSubscriberHook`
  (block/delete на 403/400-chat-not-found) (Decision 13). МУСИТЬ успадкувати наявну
  token-redaction-дисципліну (`scrubTokens`; декрипт-токен у URL `/bot{token}/sendPhoto` ніколи не
  потрапляє в лог). Потрібен для Send Image. Результат: `sendPhoto` POST'ить `/bot{token}/sendPhoto` з тією
  ж failure-семантикою, що й `sendText`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew test --tests '*TelegramSender*'` → зелені (MockWebServer
  стабує sendPhoto 200/429/403/5xx; перевірити block/delete-flip, термінальні винятки, і що token не
  тече в лог при фейлі)
- **Files to modify:** `backend/src/main/java/com/botfunnel/bot/TelegramSender.java`
- **Files to read:** `backend/src/test/java/com/botfunnel/bot/TelegramSenderTest.java`,
  `backend/src/test/java/com/botfunnel/bot/TelegramSenderSubscriberHookIT.java`

#### Task 4: SubscriberCustomFieldsService + lookup-by-chat
- **Description:** Винести inline validate→update→`recordCustomFieldsSet` із
  `SubscriberCustomFieldsController` у новий `@Service SubscriberCustomFieldsService` з методом
  `setOne(projectId, subscriberId, key, value)` (без request-scope залежностей, Decision 11); контролер
  делегує. Додати lookup-by-chat у `SubscriberService` (делегує до наявного
  `findByProjectIdAndTelegramBotIdAndTelegramChatId`). Результат: контролер behaviour незмінний, движок
  може ставити кастом-поле й резолвити підписника поза HTTP.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:**
  `backend/src/main/java/com/botfunnel/subscriber/SubscriberCustomFieldsService.java`,
  `backend/src/main/java/com/botfunnel/subscriber/SubscriberCustomFieldsController.java`,
  `backend/src/main/java/com/botfunnel/subscriber/SubscriberService.java`,
  `backend/src/main/java/com/botfunnel/subscriber/SubscriberServiceImpl.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/subscriber/SubscriberRepository.java`,
  `backend/src/main/java/com/botfunnel/subscriber/CustomFieldValueValidator.java`,
  `backend/src/main/java/com/botfunnel/project/ProjectService.java`

### Wave 2 (залежить від Wave 1)

#### Task 5: Funnel CRUD service + controller
- **Description:** `FunnelService` + `FunnelController` під `/api/v1/projects/{projectId}/funnels`
  (create draft, list із `?status=`, get, update метаданих+кроків, delete зі скасуванням активних
  виконань, activate з валідацією ≥1 крок/обов'язкові поля/конфлікт тригера + генерація deep-link).
  `requireOwned` першим (anti-IDOR, uniform 404). Ліміт `FUNNEL_MAX_STEPS`. Залежить від Task 1.
  Результат: повний CRUD+activate з коректними кодами (201/200/204/404/422).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `curl -X POST localhost:8080/api/v1/projects/{pid}/funnels -H 'Content-Type:
  application/json' --cookie <session> -d '{"name":"t"}'` → 201 draft; повторна активація конфліктного
  тригера → 422 `funnel_trigger_conflict` (перевіряється MockMvc IT)
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/FunnelService.java`,
  `backend/src/main/java/com/botfunnel/funnel/FunnelController.java`,
  `backend/src/main/java/com/botfunnel/funnel/dto/` (request/response records)
- **Files to read:** `backend/src/main/java/com/botfunnel/subscriber/SubscriberCustomFieldsController.java`,
  `backend/src/main/java/com/botfunnel/project/ProjectService.java`,
  `backend/src/main/java/com/botfunnel/common/AppException.java`,
  `backend/src/main/java/com/botfunnel/subscriber/CustomFieldsController.java`

#### Task 6: FunnelExecutionEngine — sweep + step-runner + executors
- **Description:** `@Recurring(interval="${app.funnel.scheduler-interval:PT30S}")` sweep із batch-cap
  `FUNNEL_SWEEP_BATCH_SIZE`, що atomic-claim'ить прострочені виконання (один `findAndModify` CAS,
  `.name()`-літерали), виконує кроки через діспетч `StepType`→executor, pre-send status-gate + bot-pin
  check, per-step `pending→in_progress→done`, послідовні НЕ-Delay кроки за один тік, send-fail семантика
  (cancelled/failed), завершення→completed. Кожен перехід стану логується іменованими константами —
  ідентифікатори/коди, НЕ payload (Decision 16). Залежить від Task 1,2,3,4. Результат: at-most-once,
  multi-replica-safe рушій (Decision 2,4,7,9,16,17).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew test -PrunSlow=true --tests '*FunnelExecution*'` → зелені
  (IT: проходження кроків, послідовні НЕ-Delay за тік, Delay resume, idempotency, atomic-claim race,
  send-fail, status-gate, bot-pin, sub-minute каденція реєструється)
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/FunnelExecutionEngine.java`,
  `backend/src/main/java/com/botfunnel/funnel/StepExecutor.java` (executors)
- **Files to read:** `backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java`,
  `backend/src/main/java/com/botfunnel/jobs/HardDeleteJob.java`,
  `backend/src/main/java/com/botfunnel/subscriber/jobs/ExportSubscribersJob.java`,
  `backend/src/main/java/com/botfunnel/bot/TelegramSender.java`,
  `backend/src/main/java/com/botfunnel/subscriber/SubscriberServiceImpl.java`

#### Task 7: Реальний FunnelTriggerService (заміна NoOp)
- **Description:** Видалити `NoOpFunnelTriggerService`, реалізувати реальний `@Service`
  `FunnelTriggerService`: `fire()` резолвить CONNECTED-бота+підписника, матчить active-воронку за
  `(on_start, triggerValue)` exact-match, створює `funnel_execution` зі snapshot кроків і пін
  `telegramBotId`, re-enter guard через unique partial index (Decision 8); `cancelActiveFor()` →
  cancel running|waiting. Усі власні помилки swallow+log (Decision 6). Залежить від Task 1,6. Результат:
  рівно один бін, контекст стартує, webhook-update не йде у FAILED через funnel-помилку.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/FunnelTriggerService.java`
  (impl у новому класі або заміна NoOp), видалити
  `backend/src/main/java/com/botfunnel/funnel/NoOpFunnelTriggerService.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java`,
  `backend/src/main/java/com/botfunnel/bot/BotRepository.java`,
  `backend/src/test/java/com/botfunnel/funnel/NoOpFunnelTriggerServiceTest.java`,
  `backend/src/test/java/com/botfunnel/webhook/ProcessTelegramUpdateJobTest.java`

### Wave 3 (залежить від Wave 2)

#### Task 8: Каскад hard-delete + конфіг/env vars + runbook
- **Description:** Розширити `ProjectHardDeleteJob` каскадом `funnels`+`funnel_executions` (за top-level
  `projectId`, перед drop `projects`, з лог-лічильниками). Додати properties `app.funnel.scheduler-interval`
  (env `FUNNEL_SCHEDULER_INTERVAL`, ISO-8601, default `PT30S`), `app.funnel.max-steps` (env
  `FUNNEL_MAX_STEPS`, default 50), `app.funnel.sweep-batch-size` (env `FUNNEL_SWEEP_BATCH_SIZE`, default
  200) в `application.properties` + `.env.example` + `docs/local-setup.md`. Створити staging-smoke runbook
  `docs/staging-smoke/10-funnels.md`. Залежить від Task 1,6. Результат: каскад видаляє нові колекції;
  конфіг задокументований; runbook для ручного Telegram-прогону.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew test --tests '*ProjectHardDelete*'` → зелені (каскад видаляє
  funnels+funnel_executions)
- **Files to modify:** `backend/src/main/java/com/botfunnel/jobs/ProjectHardDeleteJob.java`,
  `backend/src/main/resources/application.properties`, `.env.example`, `docs/local-setup.md`,
  `docs/staging-smoke/10-funnels.md`
- **Files to read:** `backend/src/test/java/com/botfunnel/jobs/ProjectHardDeleteJobIT.java`

### Wave 4 (фронтенд — залежить від Task 5 API-контракту)

#### Task 9: Funnels store + список + create/delete
- **Description:** `stores/funnels.ts` (`useFunnelsStore`: fetch/create/update/delete через
  `useApi`+`useApiError`), сторінка `funnels/index.vue` (список із фільтром статусу, empty-state CTA,
  create dialog, delete confirm зі скасуванням виконань). i18n ключі `funnels.*`+`errors.funnels.*` у
  обидва локалі. Залежить від Task 5. Результат: список воронок працює, CRUD-операції відображаються.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** open `localhost:3000/projects/{id}/funnels` → список рендериться, create відкриває
  редактор, delete просить confirm
- **Files to modify:** `frontend/stores/funnels.ts`,
  `frontend/pages/projects/[projectId]/funnels/index.vue`,
  `frontend/components/funnels/CreateFunnelDialog.vue`,
  `frontend/i18n/locales/uk.json`, `frontend/i18n/locales/en.json`
- **Files to read:** `frontend/pages/projects/[projectId]/custom-fields/index.vue`,
  `frontend/stores/subscribers.ts`, `frontend/composables/useApi.ts`,
  `frontend/composables/useApiError.ts`

### Wave 5 (фронтенд-редактор — після Task 9, щоб уникнути паралельного редагування locale-файлів)

#### Task 10: Редактор кроків + тригер + активація (+ E2E)
- **Description:** Сторінка `funnels/[funnelId].vue` — вертикальний список кроків, picker типу + форми
  кроків (Add/Edit Dialog), переміщення ↑/↓, видалення, налаштування тригера (`/start` + trigger_value)
  з готовим deep-link і кнопкою «Copy», кнопка «Activate»/«Pause» з відображенням 422-помилок валідації
  (порожні кроки/конфлікт тригера). i18n ключі (додаються послідовно після Task 9 — без конфлікту
  locale-файлів). Playwright E2E golden-path + page-test на 422-гілку активації. Залежить від Task 5,9.
  Результат: повний editor flow працює в браузері.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** open `localhost:3000/projects/{id}/funnels/{fid}` → додати Send→Delay→Send крок,
  перемістити, налаштувати trigger_value, побачити deep-link `t.me/<bot>?start=...` + Copy, активувати
- **Files to modify:** `frontend/pages/projects/[projectId]/funnels/[funnelId].vue`,
  `frontend/components/funnels/FunnelStepsList.vue`,
  `frontend/components/funnels/AddStepDialog.vue`,
  `frontend/components/funnels/EditStepDialog.vue`,
  `frontend/components/funnels/FunnelTriggerSettings.vue`,
  `frontend/i18n/locales/uk.json`, `frontend/i18n/locales/en.json`,
  `frontend/e2e/funnels.spec.ts`
- **Files to read:** `frontend/components/customFields/AddCustomFieldDialog.vue`,
  `frontend/pages/projects/[projectId]/custom-fields/index.vue`,
  `frontend/components/ui/dialog/`, `frontend/e2e/` (наявний spec за взірець)

### Audit Wave

#### Task 11: Code Audit
- **Description:** Full-feature code quality audit. Read all source files created/modified in this feature
  (from decisions.md + tech-spec "Files to modify"). Review holistically for cross-component issues:
  duplicate resource initialization, shared resources compliance (TelegramSender/SubscriberServiceImpl
  sole-writer), architectural consistency (atomic-claim idiom, `.name()` literals). Write audit report.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 12: Security Audit
- **Description:** Full-feature security audit. Read all source files created/modified in this feature.
  Analyze OWASP Top 10 across components: anti-IDOR (`requireOwned` first, uniform 404), input validation
  (step fields, trigger payload, image URL), template injection (HTML/MarkdownV2 escaping), no secret
  leakage in logs, cross-project isolation. Write audit report.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 13: Test Audit
- **Description:** Full-feature test quality audit. Read all test files created in this feature. Verify
  coverage of engine concurrency/idempotency/race, meaningful assertions, test pyramid balance
  (unit/IT/E2E), i18n parity gate, deterministic Clock usage. Write audit report.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 14: Pre-deploy QA
- **Description:** Acceptance testing: run all tests (`./gradlew test -PrunSlow=true`, `pnpm test`,
  `pnpm test:e2e`, `node scripts/check-locales.mjs`), verify all acceptance criteria from user-spec
  ("Критерии приёмки") and tech-spec ("Acceptance Criteria"). Produce QA report. (Deploy/Post-deploy не
  застосовуються: CI/CD ще немає, live Telegram-прогін — ручний через runbook.)
- **Skill:** pre-deploy-qa
- **Reviewers:** none
