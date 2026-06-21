---
created: 2026-06-06
status: approved
branch: dev
size: L
---

# Tech Spec: 11-funnels-interactive — Інтерактив + клавіатури (Фаза 2)

## Solution

Поверх лінійного рушія Фази 1 (`work/completed/10-funnels`) додаємо інтерактив одним новим
композитним кроком **`MENU`** (повідомлення + inline-клавіатура + park-on-reply + розгалуження по
кнопці) і переводимо воронку з плаского «список + числовий індекс» на **граф-модель**: кожен крок
отримує стабільний `id`, навігація йде по `currentStepId` (а не `currentStepIndex`), кожен не-`MENU`
крок має одну вихідну ціль `next` (дефолт — наступний крок у списку), `MENU` має ціль на кожну кнопку
(+ опційну ціль таймауту). Це безкоштовно дає **петлі** (кнопка веде назад на меню) і **fan-in**
(кілька кнопок → один крок), і робить майбутній канвас шаром візуалізації **без міграції даних**.

Механіку at-most-once Фази 1 зберігаємо незмінною: атомний claim-CAS (`pending→in_progress`),
claim-conditional записи (`stillClaimed`), lowercase `ExecutionStatus.name()` у літералах індексів +
статична drift-перевірка. Додаємо статус **`waiting_for_reply`** (reply-park), новий вихід
StepExecutor для `MENU`, окрему точку входу рушія `resumeOnCallback` (callback-пробудження повз
sweep), і таймаут-гілку (reuse `nextRunAt` + наявний sweep). Цикл-guard `size+1` замінюємо на
**ліміт кроків за тік** (`app.funnel.max-steps-per-tick`). Наявні воронки Фази 1 та їхні in-flight
executions не ламаються: backfill `id` (через `ApplicationRunner`, за зразком `SuperAdminSeeder`) +
сумісний index↔id-шлях у рушії для дренажу лінійних runs.

На фронтенді — новий тип `MENU` у вертикальному списку-редакторі (без канвасу): форма меню з
sub-редактором кнопок, пікер цілі через наявний `SearchableSelect`, інлайн-підсвітка битого ребра.

## Architecture

### What we're building/modifying

**Backend (`com.botfunnel`)**
- **`funnel.FunnelStep`** — додаємо граф-поля: `id` (стабільний), `next` (ціль за замовч.),
  `List<Button> buttons`, опційні `timeoutValue`/`timeoutUnit`/`timeoutTargetStepId`. Новий
  immutable record **`funnel.Button`** (`type` callback|url, `label`, `targetStepId`, `url`).
  `copyOf` отримує явну глибоку копію списку кнопок (snapshot-ізоляція).
- **`funnel.FunnelExecution`** — додаємо `currentStepId` (поряд з наявним `currentStepIndex` для
  дренажу), `lastButtonClicked`. Розширюємо статичну drift-перевірку статусів.
- **`funnel.ExecutionStatus`** — новий `waiting_for_reply` (lowercase). Додаємо в усі 7 літерал-сайтів
  ($in/partial-filter): re-enter index, sweep, claim, `cancelActiveFor`, `cancelExistingForPair`,
  `FunnelService.delete`, `FunnelIndexesIT`.
- **`funnel.StepType`** — новий `MENU`.
- **`funnel.FunnelExecutionEngine`** — граф-навігація (`currentStepId` + `stepById` lookup), ліміт
  кроків за тік, новий вихід `MENU` (park-on-reply), окрема точка входу `resumeOnCallback`,
  таймаут-resume-гілка.
- **`funnel.FunnelTriggerServiceImpl`** — новий `advanceOnCallback(projectId, chatId, callbackData,
  callbackQueryId)`: резолв бота/підписника/припаркованого execution, match кнопки→ціль, делегування
  в `resumeOnCallback`, best-effort `answerCallbackQuery`, подія `funnel_button_clicked`.
- **`funnel.FunnelService` / dto** — `validateSteps` для кнопок/ребер/`MENU`; `funnel_broken_edge`
  422; `FunnelStepDto.buttons` + `ButtonDto` + timeout-поля; `toSteps` мінтить/зберігає `id`.
- **`funnel` backfill** — новий `FunnelStepIdBackfill implements ApplicationRunner`: проставляє `id`
  наявним воронкам та in-flight снапшотам, ідемпотентно.
- **`bot.TelegramSender`** — `reply_markup` у `sendText` (only-if-non-null) + новий
  `answerCallbackQuery(botId, callbackQueryId, text?)`.
- **`webhook`** — типізований `CallbackQuery` DTO + dispatch-гілка `callback_query` →
  `advanceOnCallback`, з event-before-flip та error-isolation.

**Frontend (`frontend/`)**
- `types/funnel.ts` — `MENU` у `StepType`, `Button` interface + граф-поля у `FunnelStep`.
- `components/funnels/FunnelStepForm.vue` — `MENU` у `STEP_TYPES`, `schemaFor` (валідація кнопок),
  sub-редактор кнопок, пікер цілі через `SearchableSelect`.
- `components/funnels/FunnelStepsList.vue` — `summary()` арм для `MENU`.
- `pages/projects/[projectId]/funnels/[funnelId].vue` — інлайн `funnel_broken_edge`.
- `i18n/locales/{uk,en}.json` — ключі `funnels.steps.type.MENU`, кнопки, валідаційні повідомлення.

### How it works

**Автор будує:** «+ Add step» → `MENU` → форма (текст + `parse_mode`, до 8 кнопок по 1 в ряд,
опц. таймаут). Кнопка callback → ціль (інший крок або «End») через `SearchableSelect`; кнопка URL →
`http(s)`-лінк. Сабміт PATCHить увесь масив кроків; сервер у `toSteps` мінтить `id` новим крокам і
повертає їх — після першого persist кожен крок має server-`id`, тож реордер (splice) **безпечний без
ремапу** (цілі — стабільні `id`, не індекси).

**Активація:** окрім перевірок Фази 1 валідуються ребра — кожен `MENU` має ≥1 callback-кнопку; усі
цілі (`next`, кнопки, таймаут) вказують на наявні `id`; URL — `http(s)`; лейбли/кількість у лімітах.
Битий edge → 422 `funnel_broken_edge` (draft зберегти можна, активувати — ні).

**Підписник проходить:** `/start` → `fire()` стартує snapshot-execution з `currentStepId` =
id першого кроку. Рушій резолвить крок по `currentStepId` (`stepById`); не-`MENU` крок → виконати →
`currentStepId = step.next` (або наступний у списку). `MENU` → `TelegramSender.sendText` з
`reply_markup` → новий вихід park-on-reply: `status=waiting_for_reply`, `currentStepId` лишається на
`MENU`, `nextRunAt` = дедлайн таймауту (або `null` — чекати необмежено).

**Натиск кнопки:** Telegram POSTить `callback_query` на webhook → нова dispatch-гілка → 
`advanceOnCallback`. `callback_data = "{executionId}:{buttonIndex}"` (≤64 байт). Сервіс: резолвить
CONNECTED-бота → підписника → завантажує execution; вимагає `status==waiting_for_reply`; резолвить
поточний `MENU` по `currentStepId`; перевіряє, що `buttonIndex` у межах і це callback-кнопка; її
`targetStepId` (або End) → `resumeOnCallback(executionId, targetStepId)` (claim-CAS на
`waiting_for_reply && pending` → `currentStepId=target`, `status→running`, той самий step-loop).
Best-effort `answerCallbackQuery` прибирає спіннер. Подвійний клік / застаріла / некоректна
`callback_data` → `answerCallbackQuery` + тихий no-op (другий claim програє). Подія
`funnel_button_clicked` + `lastButtonClicked` на execution.

**Таймаут:** якщо заданий — `nextRunAt`=дедлайн; наявний sweep підбирає прострочений
`waiting_for_reply` (бо `waiting_for_reply` тепер у sweep/claim `$in`, а предикат `nextRunAt <= now`
природно пропускає меню без таймауту з `nextRunAt=null`), delay-resume-гілка веде по
`timeoutTargetStepId` (а не `+1`).

**Петля без wait:** граф із циклом без `MENU`/Delay між кроками за тік перевищить
`max-steps-per-tick` → `terminate(failed)` з греппабельним лог-маркером.

### Shared resources

| Resource | Owner (creates) | Consumers | Instance count |
|----------|----------------|-----------|----------------|
| `TelegramSender` (RestClient bean) | `bot` module | `StepExecutor`, `FunnelTriggerServiceImpl` (answerCallbackQuery) | 1 (singleton, existing) |
| `Clock` bean | `common.ClockConfig` | engine (timeout deadlines, `@now`) | 1 (singleton, existing) |
| `EventService` | `events` module | engine/trigger (`funnel_button_clicked`), webhook | 1 (singleton, existing) |
| JobRunr `@Recurring` sweep | `FunnelExecutionEngine` | — (timeout resume) | 1 recurring job (existing) |

Нових важких ресурсів немає — усе переюз існуючих синглтонів.

## Decisions

### Decision 1: Один композитний крок `MENU` (а не окремі Wait + Branch)
**Decision:** один тип `MENU` інкапсулює повідомлення + клавіатуру + очікування + розгалуження.
**Rationale:** простіше для автора; прибирає клас помилок «orphan branch / Wait без Branch».
**Alternatives considered:** окремі `WAIT_FOR_REPLY` + `BRANCH` (як у початковому code-research) —
відкинуто: більше станів, ручне звʼязування, легше зламати граф.
**Supports:** US Сценарій 1, AC «автор додає крок MENU…».

### Decision 2: Граф-модель даних (`currentStepId` + `next` + цілі кнопок)
**Decision:** стабільний `id` на кожен крок; навігація по `currentStepId`; не-`MENU` крок має `next`
(дефолт — наступний у списку); `MENU` — ціль на кожну кнопку (+ опц. таймаут). Цілі = стабільні `id`,
не індекси.
**Rationale:** безкоштовно дає петлі + fan-in; реордер у редакторі стає безпечним без ремапу цілей;
майбутньо-сумісно з канвасом без міграції даних.
**Alternatives considered:** index-навігація з ремапом цілей на реордер (початковий code-research) —
відкинуто: крихко, ремап на кожен move/insert/delete, цілі ламаються.
**Supports:** AC «кнопка може вести назад / кілька кнопок на один крок».

### Decision 3: `waiting_for_reply` — у sweep+claim `$in`; розрізнення через `nextRunAt`
**Decision:** новий статус `waiting_for_reply` **включається** у sweep/claim `$in` (і в усі cancel-
шляхи + re-enter-індекс). Callback-пробудження — окрема точка входу `resumeOnCallback` (НЕ через
sweep). Таймаут — через наявний sweep: `nextRunAt`=дедлайн; меню без таймауту має `nextRunAt=null` і
sweep-предикат `nextRunAt <= now` його ніколи не матчить.
**Rationale:** двошляхове пробудження без окремого «виключення зі sweep»; timeout reuse наявного
механізму з нульовою новою sweep-гілкою.
**Alternatives considered:** виключити `waiting_for_reply` зі sweep (як радив ранній code-research) —
відкинуто user-spec: тоді таймаут потребує окремого планувальника.
**Supports:** US «Ограничения»/«Риски» (двошляхове пробудження), AC «Таймаут… не заданий → необмежено».

### Decision 4: Ліміт кроків за тік замість `guard=size+1`
**Decision:** замінюємо цикл-guard `size+1` на `@Value("${app.funnel.max-steps-per-tick:100}")`.
Трип guard при `status==running` і курсорі **не** в кінці графа → `terminate(failed)` з греппабельним
маркером `FUNNEL_STEP_BUDGET_EXCEEDED`.
**Rationale:** ребра графа не монотонні (петлі); старий guard припускав зростаючий індекс. Дефолт 100
> максимум кроків воронки (50), тож легітимна 50-крокова лінійка ніколи не дає false-trip, а петля
без park гине швидко.
**Alternatives considered:** forward-only цілі (зберегти старий guard) — відкинуто: вбиває петлі/меню-
повернення, головний сценарій. **[TECHNICAL]** для самого числа дефолту (100).
**Supports:** AC «петля без wait/delay → ліміт рве цикл → failed із греппабельним лог-маркером».

### Decision 5: `Button` як immutable record + явна глибока копія в `copyOf`
**Decision:** `Button` — immutable record; `FunnelStep.copyOf` робить `new ArrayList<>(buttons)`
(достатньо для списку імутабельних record-ів). Javadoc-контракт `copyOf` оновлюємо.
**Rationale:** snapshot-ізоляція Фази 1 (Decision 3 Ф1) трималася на «усі поля — імутабельні скаляри»;
мутабельний список кнопок ламає це — потрібна явна копія.
**Supports:** AC «воронку відредагували поки execution чекає → дограє по старому графу». **[TECHNICAL]**
(record vs class — деталь реалізації вимоги ізоляції).

### Decision 6: `callback_data = "{executionId}:{buttonIndex}"` + авторизація на власника
**Decision:** кодуємо ObjectId-hex execution (24 байти) + індекс кнопки (`0..7`) через `:` (≈26 байт
≤ 64). Перевірка приналежності кроку — серверна: `currentStepId` читається з самого execution, тож
callback на крок, який вже пройдено, природно відсікається. **Авторизація власника (anti-IDOR):**
перед просуванням `advanceOnCallback` вимагає `exec.subscriberId == resolvedSubscriber.id &&
exec.projectId == projectId`, а CAS у `resumeOnCallback` **scoped по `subscriberId`** (предикат
`_id == execId AND subscriberId == sub.id AND status==waiting_for_reply AND stepRunStatus==pending`).
Інакше будь-який легітимний підписник проєкту міг би підробити `callback_data` з чужим `executionId`
і просунути виконання іншого підписника (ObjectId частково передбачуваний — не секрет).
**Строгий парс `callback_data`:** перевірка форми ObjectId-hex (24 hex-символи) до DB-lookup; індекс —
невідʼємне ціле в межах; рівно один `:`-роздільник (зайві сегменти → reject); кнопка має бути
callback-типу. **`answerCallbackQuery` викликається ЗАВЖДИ** (включно з усіма no-op/null-шляхами),
щоб спіннер у підписника гарантовано зник, навіть коли просування не відбувається.
**Rationale:** однозначно ідентифікує припарковане виконання + кнопку у межах 64 байт; підписник може
бути у двох воронках з меню одночасно; власницька перевірка закриває IDOR.
**Alternatives considered:** кодувати ще й stepId — надлишково (серверна перевірка покриває).
Malformed/oversized/нерозбірний/чужий → `answerCallbackQuery` + тихий no-op.
**Supports:** AC «застаріла кнопка → no-op», «подвійний клік → один раз», US «Ограничения» (callback_data).

### Decision 7: Міграція `id` — `ApplicationRunner` backfill (визначення + in-flight снапшоти)
**Decision:** `FunnelStepIdBackfill implements ApplicationRunner` (за зразком `SuperAdminSeeder`:
ідемпотентно, логує-не-кидає) проставляє `id` усім крокам у `funnels` **та** у
`funnel_executions.stepsSnapshot`, і виставляє `currentStepId` із `currentStepIndex` для in-flight.
Рушій читає `currentStepId`, з fallback на `currentStepIndex`, якщо `currentStepId==null` (страховка
для дренажу).
**Rationale:** user-spec вимагає автоматичну міграцію без ручних дій і щоб лінійні runs дограли.
`ApplicationRunner` — єдиний наявний startup-hook-прецедент.
**Alternatives considered:** backfill лише визначень (без снапшотів) — відкинуто: in-flight execution
тримає власний снапшот; без `id` у ньому граф-навігація не резолвиться.
**Supports:** AC «наявні воронки Фази 1 і їхні in-flight executions не ламаються».

### Decision 8: `answerCallbackQuery` — best-effort, без аудит-події
**Decision:** завжди намагаємось прибрати спіннер; збій (Telegram 5xx) **не блокує** просування гілки;
без окремої аудит-події, лише WARN-лог.
**Rationale:** це non-content ack; провал ack не повинен зупиняти воронку.
**Supports:** US «Ограничения» (answerCallbackQuery — best-effort). **[TECHNICAL]**.

### Decision 9: Аналітика — мінімальний хук
**Decision:** подія `funnel_button_clicked` (`funnelId`, `executionId`, крок, кнопка) через наявний
`EventService` + `lastButtonClicked` на execution. Дашборди — епік 09. **Без PII:** події
(`funnel_button_clicked`, `telegram_callback_query`) несуть лише ids/коди — жодних
`from.first_name`/`username` (за зразком Decision 16 Ф1).
**Rationale:** мінімальний майбутньо-сумісний хук без передчасної аналітики.
**Supports:** AC «натиск кнопки записує подію funnel_button_clicked + lastButtonClicked».

### Decision 10: URL-кнопки не рушать воронку; `MENU` вимагає ≥1 callback
**Decision:** URL-кнопка (лише `http(s)`) лише відкриває лінк — callback не шле, execution лишається
`waiting_for_reply`. Кожен `MENU` має ≥1 callback-кнопку (інакше 422 на активацію). **Строга
перевірка схеми:** URL парситься через `URI`, схема має бути точно `http`|`https` (не `startsWith`),
host непорожній; `javascript:`/`data:`/`tg://`/whitespace-obфусковані → 422 (SSRF/scheme-injection
defense).
**Rationale:** меню без callback ніколи б не «зрушило» — підписник застряг би назавжди; строга
схема закриває scheme-injection.
**Supports:** AC «натиск URL-кнопки… не просуває воронку», «MENU без callback-кнопки → 422».

### Decision 11: Paused-воронка — in-flight `waiting_for_reply` дограє (без per-step paused-gate)
**Decision:** `resumeOnCallback` та step-loop **не** додають перевірку `funnel.status==paused` —
успадковуємо модель Ф1, де pause блокує лише старт нових executions (на trigger), а in-flight runs
дограють (pre-step гейти лише `subscriber ACTIVE` + bot `CONNECTED`). Тож натиск кнопки на paused-
воронці просуває припарковане виконання до `completed`. Явно фіксуємо, щоб реалізатор НЕ додав
paused-gate у resume-шлях.
**Rationale:** прямо вимагається user-spec AC; будь-який paused-gate у resume зламав би «дограє».
**Supports:** AC «Воронка paused → активний waiting_for_reply після натискання кнопки і далі
просувається до completed».

## Data Models

**`FunnelStep`** (плаский POJO, без `_class`) — нові поля:
```
String id;                       // стабільний, мінтиться сервером (ObjectId hex / UUID)
String next;                     // ціль за замовч. (null → наступний у списку)
List<Button> buttons;            // лише для MENU; deep-copied у copyOf
Integer timeoutValue;            // опц. (MENU)
String  timeoutUnit;             // опц. ("MIN"|"HOUR"|"DAY") — за зразком delayUnit
String  timeoutTargetStepId;     // опц. ціль таймауту (null → completed)
```

**`Button`** (immutable record):
```
record Button(String type,        // "callback" | "url"
              String label,       // ≤64 символи, plain text (без екранування)
              String targetStepId,// для callback: id кроку або null=End
              String url)         // для url: http(s)
```

**`FunnelExecution`** — нові поля:
```
String currentStepId;            // граф-курсор (поряд з currentStepIndex для дренажу)
String lastButtonClicked;        // остання натиснута кнопка (аналітика)
```

**`ExecutionStatus`** — `running, waiting, completed, cancelled, failed, waiting_for_reply` (lowercase).

**DTO:** `FunnelStepDto` +`List<ButtonDto> buttons` + timeout-поля; `ButtonDto(type, label,
targetStepId, url)` record; `@JsonIgnoreProperties` (mass-assignment defense) лишається.

**Колекції:** змін індексів-схем немає, окрім додавання `waiting_for_reply` у partialFilter
re-enter-індексу `funnelId_subscriberId_unique_active`.

## Dependencies

### New packages
- Немає.

### Using existing (from project)
- `TelegramSender` (`bot`) — `reply_markup`/`answerCallbackQuery` як нові поля/метод у наявних патернах.
- `EventService` (`events`) — подія `funnel_button_clicked`.
- `SubscriberService.findByChat` — резолв підписника в callback-шляху (наявний boundary).
- `VariableTemplateRenderer` — рендер тексту `MENU` (екранування під `parse_mode`, як Send).
- `SearchableSelect.vue` — пікер цілі кнопки (наявний combobox).
- `MutableClock`/`TestClockConfig` (test) — детерміновані engine-ITs.
- `mockwebserver` (test) — TelegramSender-ITs.

## Testing Strategy

**Feature size:** L

### Unit tests
- `Button`/`FunnelStep.copyOf` — мутація списку кнопок джерела після `copyOf` не чіпає копію (deep-copy).
- `validateSteps` — ≥1 callback на `MENU`; ціль на наявний id; URL `http(s)`; лейбл/кількість лімітів;
  `funnel_broken_edge` на видалений крок.
- callback_data — білд `"{executionId}:{buttonIndex}"`; парс malformed/oversized → no-op.
- URL-scheme reject: `javascript:`/`data:`/`tg://`/leading-whitespace/empty-host → 422 (негативні кейси).
- `reply_markup` render — мапінг кнопок → `{"inline_keyboard":[[{text,callback_data|url}]]}`.
- `ExecutionStatus.waiting_for_reply.name()=="waiting_for_reply"` (drift), статична assertion.
- `stepById` lookup; default-`next` = наступний у списку.

### Integration tests
Backend (Testcontainers Mongo + in-memory JobRunr + MockWebServer + `MutableClock`, за зразком Ф1):
- park-on-reply: `MENU` → `status==waiting_for_reply`, sweep **не** резюмить меню без таймауту;
  `resumeOnCallback` → просування у гілку обраної кнопки.
- петля + ліміт кроків за тік → `failed` + лог-маркер.
- fan-in: кілька кнопок → один крок.
- таймаут: sweep підбирає прострочений → веде по `timeoutTargetStepId`.
- ідемпотентний подвійний клік (claim-CAS, другий програє); callback-vs-cancel race;
  callback на `completed`/`cancelled` → no-op.
- **IDOR:** callback з чужим `executionId` (підписник A → execution B) → відхилено
  (subscriberId/projectId mismatch), виконання B не зрушило.
- **currentStepId-mismatch:** callback на крок, який execution уже пройшов → no-op (Decision 6).
- in-range-but-invalid button: index URL-кнопки, out-of-range index, snapshot-vs-live mismatch → no-op.
- успішний клік: подія `funnel_button_clicked` записана + `lastButtonClicked` оновлено на execution.
- snapshot-ізоляція: edit воронки під час `waiting_for_reply` не змінює виконання.
- скасування `waiting_for_reply` через `/stop` / delete / re-enter.
- **paused-drain:** натиск кнопки на paused-воронці просуває in-flight до `completed` (Decision 11).
- **blocked-bot під час waiting:** натиск кнопки → resume → перший send у гілці → 403 →
  execution `cancelled` (re-exercise Ф1 blocked-flow через `resumeOnCallback`).
- **legit loop-resend:** кнопка-петля → меню надсилається заново, наступний клік працює (без зависання).
- `{user.first_name}` підстановка у текст `MENU` + екранування під `parse_mode`.
- міграція: стара лінійна воронка/in-flight run дограє після backfill; backfill ідемпотентний на
  повторний старт (повторний run не дублює `id`).
- таймаут `timeoutTargetStepId==null` → `completed`.
- `answerCallbackQuery` 5xx → best-effort, просування гілки не блокується (WARN-лог).
- webhook: інжест `callback_query` → `advanceOnCallback` + event-before-flip (`ProcessTelegramUpdateJobTest`).
- TelegramSender: `reply_markup` у body + `answerCallbackQuery` через mockwebserver.
- `FunnelIndexesIT` — `waiting_for_reply` у partialFilter re-enter-індексу.

### E2E tests
- 1 Playwright happy-path: редактор `MENU` — додати меню + 2 callback-кнопки + ціль через
  `SearchableSelect`, зберегти, активувати. Решта фронтенду — vitest (форма меню, пікер цілі,
  інлайн `funnel_broken_edge`).

## Agent Verification Plan

**Source:** user-spec «Как проверить».

### Verification approach
Поверх автотестів агент перевіряє через `curl` бізнес-коди валідації (422 `funnel_broken_edge`, 422
«MENU без callback»), симулює `callback_query` на webhook (валідний / застарілий / неіснуючий /
malformed / oversized `callback_data`) і перевіряє Mongo-стан (execution не зрушив на стейл,
`funnel_button_clicked` + `lastButtonClicked` записані на валідний клік). Живий прохід у Telegram —
**ручна** перевірка користувача (реальна поведінка inline-клавіатур, яку симуляція webhook не покриває).
Per-task smoke-перевірки — у полях Verify-smoke/Verify-user нижче.

### Tools required
`bash` (gradle, симуляція callback_query, Mongo-запити), `curl` (PUT/activate), `vitest`+`playwright`
(frontend). Telegram MCP / Playwright MCP для живого проходу не потрібні — це ручна перевірка користувача.

## Risks

| Risk | Mitigation |
|------|-----------|
| Зміна рушія index→graph зачіпає at-most-once-інваріант | claim-CAS + `stillClaimed` лишаються по `_id`+`stepRunStatus` (не по індексу); guard→ліміт кроків за тік; двореплічні race/crash-ITs за зразком Ф1 |
| Мутабельний список кнопок ламає snapshot-ізоляцію | `Button` як record + явна копія списку в `copyOf`; unit-IT на «edit під час waiting не змінює виконання» |
| `waiting_for_reply` само-просувається sweepʼом | меню без таймауту має `nextRunAt=null` → sweep-предикат `nextRunAt<=now` ніколи не матчить; IT «waiting без таймауту sweep не рухає» |
| Подвійний клік / застаріла кнопка запускає гілку двічі/не туди | claim-CAS (перший виграє) + просування лише якщо `waiting_for_reply` саме на цьому `MENU`; решта — answerCallbackQuery+no-op; IT на ідемпотентність |
| Міграція ламає наявні воронки/in-flight Ф1 | адитивний backfill `id` (визначення + снапшоти) + index↔id fallback у рушії; IT «стара воронка дограє» |
| Битий граф (ціль на видалений крок) | валідація на activate (422 `funnel_broken_edge`) + інлайн-підсвітка; снапшот ізолює in-flight; null-резолв курсора → failed+маркер |
| Пропущений літерал-сайт нового статусу | статична drift-assertion + `FunnelStatusEnumTest` + `FunnelIndexesIT` падають, якщо `waiting_for_reply` не доданий у потрібні `$in` |
| IDOR: підписник підробляє `callback_data` з чужим `executionId` | `advanceOnCallback` звіряє `exec.subscriberId/projectId` з резолвленим підписником; CAS scoped по `subscriberId`; IDOR-IT |
| Scheme-injection в URL-кнопці | строгий `URI`-парс схеми `http(s)` + непорожній host на activate; reject `javascript:`/`data:`/`tg://` |

## User-Spec Deviations

None. Техспек повністю слідує затвердженому user-spec. Інженерні дефолти, які потребують підтвердження
користувача (не суперечать user-spec, але обрані тут):
- **`app.funnel.max-steps-per-tick` default = 100** (user-spec вимагає «з дефолтом», без конкретного
  числа) — Decision 4.
- **Backfill охоплює і in-flight снапшоти** (user-spec вимагає «id проставлені, лінійні runs дограють»;
  тут уточнено *як*) — Decision 7.

## Acceptance Criteria

Технічні критерії (доповнюють користувацькі з user-spec):

- [ ] `activate` з битим ребром → 422 `funnel_broken_edge`; `MENU` без callback-кнопки → 422.
- [ ] `waiting_for_reply` присутній у re-enter partialFilter, sweep/claim `$in`, усіх cancel-шляхах;
      drift-assertion + `FunnelStatusEnumTest` + `FunnelIndexesIT` зелені.
- [ ] `FunnelStep.copyOf` глибоко копіює список кнопок (unit доводить ізоляцію).
- [ ] Backfill `ApplicationRunner` ідемпотентний; повторний старт не дублює `id`.
- [ ] `callback_data` ≤ 64 байти; malformed/oversized/чужий → answerCallbackQuery + no-op.
- [ ] callback з чужим `executionId` (IDOR) відхилено; `answerCallbackQuery` викликано на всіх no-op-шляхах.
- [ ] Усі тести зелені (unit + integration + 1 e2e); немає регресій у тестах Ф1.
- [ ] Немає нових секретів; URL-кнопки лише `http(s)` (строгий `URI`-парс схеми + host).

## Implementation Tasks

### Wave 1 (незалежні)

#### Task 1: Граф-модель даних + статус-фундамент
- **Description:** Додати граф-поля у `FunnelStep` (`id`, `next`, `buttons`, timeout-поля) + immutable
  record `Button`; зробити явну глибоку копію кнопок у `copyOf` (snapshot-ізоляція). Додати
  `currentStepId` + `lastButtonClicked` у `FunnelExecution`. Додати `ExecutionStatus.waiting_for_reply`
  (lowercase) в усі 7 літерал-сайтів + статичну drift-assertion. Додати `StepType.MENU`. Backfill
  `FunnelStepIdBackfill implements ApplicationRunner` (визначення + in-flight снапшоти, ідемпотентно).
  Результат: модель і статус готові, наявні воронки отримують `id`, тести drift/index/copyOf зелені.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/FunnelStep.java`, `funnel/FunnelExecution.java`, `funnel/ExecutionStatus.java`, `funnel/StepType.java`, `funnel/Button.java` (new), `funnel/FunnelStepIdBackfill.java` (new), `frontend/types/funnel.ts`
- **Files to read:** `backend/src/main/java/com/botfunnel/admin/SuperAdminSeeder.java`, `funnel/FunnelExecutionEngine.java`, `funnel/StepRunStatus.java`, `backend/src/test/java/com/botfunnel/funnel/FunnelStatusEnumTest.java`, `funnel/FunnelIndexesIT.java`, `funnel/FunnelStepTest.java`

#### Task 2: TelegramSender — reply_markup + answerCallbackQuery
- **Description:** Додати `reply_markup` у `sendText` (only-if-non-null, за ідіомом `parse_mode`) як
  обʼєкт `{"inline_keyboard":[[{text, callback_data|url}]]}`. Додати метод
  `answerCallbackQuery(botId, callbackQueryId, text?)` на наявних патернах (AES-GCM decrypt,
  CONNECTED-фільтр, 5xx/429 retry, token-scrub, best-effort). Результат: бот шле клавіатуру і вміє
  прибирати спіннер.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** TelegramSender-IT через `mockwebserver` — `reply_markup` присутній у body sendMessage; `answerCallbackQuery` шле POST на `/answerCallbackQuery`
- **Files to modify:** `backend/src/main/java/com/botfunnel/bot/TelegramSender.java`, `backend/src/test/java/com/botfunnel/bot/TelegramSenderIT.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/bot/TelegramApiClient.java`, `bot/dto/SentMessage.java`

### Wave 2 (залежить від Wave 1)

#### Task 3: Рушій — граф-навігація, MENU-park, resumeOnCallback, таймаут
- **Description:** Перевести `FunnelExecutionEngine` на граф-навігацію: резолв кроку по `currentStepId`
  (`stepById` helper, fallback на `currentStepIndex` для дренажу), `next`-дефолт = наступний у списку,
  ліміт кроків за тік з `failed`+маркером `FUNNEL_STEP_BUDGET_EXCEEDED` (значення/дефолт — Decision 4).
  Seed `currentStepId` першого кроку в `FunnelTriggerServiceImpl.insertExecution`. Додати вихід
  StepExecutor для `MENU` (надіслати меню + park-on-reply: `status=waiting_for_reply`,
  `nextRunAt`=дедлайн|null). Окрема точка входу `resumeOnCallback(executionId, subscriberId,
  targetStepId)` — claim-CAS scoped по `subscriberId` (Decision 6, anti-IDOR). Таймаут-resume-гілка
  у delay-resume seam: веде по `timeoutTargetStepId`, а `null` → `completed`. Результат: рушій ходить
  графом, паркується на меню, резюмиться по callback (лише власник) і по таймауту.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/FunnelExecutionEngine.java`, `funnel/StepExecutor.java`, `funnel/FunnelTriggerServiceImpl.java`, `backend/src/main/resources/application.properties`, `backend/src/test/java/com/botfunnel/funnel/FunnelExecutionEngineIT.java`, `funnel/FunnelStepExecutorTest.java`
- **Files to read:** `funnel/FunnelExecution.java`, `bot/TelegramSender.java`, `common/ClockConfig.java`

#### Task 4: Валідація графа + DTO
- **Description:** Розширити `validateSteps`: ≥1 callback на `MENU`; усі цілі (`next`/кнопки/таймаут)
  на наявні `id`; URL — строгий `URI`-парс схеми `http(s)` + непорожній host (Decision 10);
  лейбл/кількість кнопок (≤8) у лімітах; битий edge → 422 `funnel_broken_edge`. Додати
  `FunnelStepDto.buttons` + `ButtonDto` record + timeout-поля; `toSteps` мінтить новий `id` / зберігає
  вхідний (з перевіркою унікальності в межах воронки); round-trip `toStepDto`. Результат: активація
  валідує граф, DTO round-trip-ить кнопки/цілі.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `curl -X POST .../funnels/{id}/activate` з ціллю на видалений крок → 422 `funnel_broken_edge`; `MENU` без callback → 422
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/FunnelService.java`, `funnel/dto/FunnelStepDto.java`, `funnel/dto/ButtonDto.java` (new), `backend/src/test/java/com/botfunnel/funnel/FunnelControllerIT.java`
- **Files to read:** `funnel/FunnelStep.java`, `funnel/dto/UpdateFunnelRequest.java`, `funnel/dto/CreateFunnelRequest.java`

### Wave 3 (залежить від Wave 2)

#### Task 5: FunnelTriggerService.advanceOnCallback + аналітика-хук
- **Description:** Додати `advanceOnCallback(projectId, chatId, callbackData, callbackQueryId)` (інтерфейс
  + impl, увесь у `try/catch(Throwable)` swallow): резолв CONNECTED-бота → підписника → припаркованого
  execution; строгий парс `callback_data` `"{executionId}:{buttonIndex}"` (ObjectId-hex shape,
  bounded index, рівно один `:`); **авторизація власника** — `exec.subscriberId/projectId` мусять
  збігатися з резолвленим підписником (Decision 6, anti-IDOR); вимога `status==waiting_for_reply` на
  поточному `MENU`; match callback-кнопки → `targetStepId`/End → `resumeOnCallback`. **`answerCallbackQuery`
  викликається ЗАВЖДИ** (включно з null-lookup/стейл/malformed/чужий → тихий no-op), окремий
  try/catch, best-effort. Подія `funnel_button_clicked` + `lastButtonClicked` на execution (лише ids).
  Результат: натиск кнопки атомарно просуває правильну гілку **лише власника**, подія записана.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/FunnelTriggerService.java`, `funnel/FunnelTriggerServiceImpl.java`, `backend/src/test/java/com/botfunnel/funnel/FunnelExecutionEngineIT.java`
- **Files to read:** `funnel/FunnelExecutionEngine.java`, `bot/TelegramSender.java`, `subscriber/SubscriberService.java`, `events/EventService.java`

#### Task 6: Фронтенд — редактор MENU + кнопки + ціль
- **Description:** Додати `MENU` у `STEP_TYPES` (`FunnelStepForm.vue`), `schemaFor`-арм (валідація:
  лейбл непорожній, callback_data ≤64, ≥1 callback, URL `http(s)`), sub-редактор кнопок (add/remove
  рядки, тип callback|url, лейбл, ціль/url), пікер цілі через `SearchableSelect` (опції = інші кроки +
  «End»). `summary()` арм для `MENU` у `FunnelStepsList.vue`. Інлайн `funnel_broken_edge` у
  `[funnelId].vue`. i18n-ключі (`uk`+`en`). Vitest на форму/пікер/валідацію + 1 Playwright happy-path.
  Кнопки/лейбли рендеряться текстовою інтерполяцією (НЕ `v-html`). Результат: автор будує `MENU` з
  кнопками й цілями у вертикальному списку.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** open localhost funnel editor → «+ Add step» → MENU → додати 2 callback-кнопки + ціль → save → activate; битий edge підсвічений інлайн
- **Files to modify:** `frontend/components/funnels/FunnelStepForm.vue`, `frontend/components/funnels/FunnelStepsList.vue`, `frontend/pages/projects/[projectId]/funnels/[funnelId].vue`, `frontend/i18n/locales/uk.json`, `frontend/i18n/locales/en.json`, `frontend/tests/pages/funnel-editor.spec.ts`, `frontend/e2e/funnels.spec.ts`
- **Files to read:** `frontend/components/funnels/SearchableSelect.vue`, `frontend/types/funnel.ts`, `frontend/stores/funnels.ts`

### Wave 4 (залежить від Wave 3)

#### Task 7: Webhook — інжест callback_query
- **Description:** Додати типізований `CallbackQuery(id, from, message, data)` DTO; у `dispatch(...)`
  гілку `if (update.callback_query() != null)` перед message-fallback: резолв CONNECTED-бота, екстракт
  `chatId`+`data`, виклик `funnelTriggerService.advanceOnCallback(...)` (error-isolated), подія
  `telegram_callback_query` ДО `processingStatus=DONE`. Зберегти re-entry guard / idempotency
  Фази 1. Результат: callback_query доходить до рушія, дотримано event-before-flip.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `bash` симуляція `callback_query` на `POST /webhooks/telegram/{projectId}` → `advanceOnCallback` викликано, подія записана перед flip (`ProcessTelegramUpdateJobTest`)
- **Files to modify:** `backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java`, `webhook/dto/TelegramUpdate.java`, `webhook/dto/CallbackQuery.java` (new), `backend/src/test/java/com/botfunnel/webhook/ProcessTelegramUpdateJobTest.java`
- **Files to read:** `webhook/dto/Message.java`, `webhook/dto/Chat.java`, `webhook/dto/User.java`, `funnel/FunnelTriggerService.java`

### Audit Wave

#### Task 8: Code Audit
- **Description:** Full-feature code quality audit. Read all source files created/modified in this feature
  (from decisions.md + tech-spec "Files to modify"). Review holistically for cross-component issues:
  duplicate resource initialization, shared resources compliance with Architecture decisions,
  architectural consistency (граф-навігація, claim-CAS-інваріант, snapshot-ізоляція). Write audit report.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 9: Security Audit
- **Description:** Full-feature security audit. Read all source files created/modified in this feature.
  Analyze for OWASP Top 10 across all components: callback_data parsing/injection, URL-button `http(s)`
  enforcement, mass-assignment on DTOs, token-scrub у answerCallbackQuery, PII у подіях. Write audit report.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 10: Test Audit
- **Description:** Full-feature test quality audit. Read all test files created in this feature. Verify
  coverage, meaningful assertions, test pyramid balance (engine ITs детерміновані по Clock, idempotency/
  race покриті, e2e мінімальний). Write audit report.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 11: Pre-deploy QA
- **Description:** Acceptance testing: run all tests (backend unit+IT, frontend vitest+playwright),
  verify acceptance criteria from user-spec and tech-spec. Деплою немає — перевірка локальна; живий
  прохід у Telegram лишається ручною перевіркою користувача (поза автоматизованим QA).
- **Skill:** pre-deploy-qa
- **Reviewers:** none
