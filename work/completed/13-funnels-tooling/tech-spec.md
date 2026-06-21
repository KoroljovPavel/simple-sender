---
created: 2026-06-07
status: approved
branch: dev
size: L
---

# Tech Spec: Воронки. Фаза 4 — інструменти автора (13-funnels-tooling)

## Solution

Чотири незалежні інструменти автора поверх готового рушія воронок (Фази 1-3). Реалізація — переважно **extraction + wiring наявних примітивів**, з єдиною greenfield-поверхнею (панель прев'ю). Нової схеми БД, міграцій і деплою немає.

Бекенд: чотири нові POST-ендпоінти на наявному `FunnelController`
(`/api/v1/projects/{projectId}/funnels`), кожен проходить `FunnelService.requireFunnel`
(anti-IDOR, уніфікований 404):

- **`POST /{funnelId}/duplicate`** — `FunnelStep.copyOf` копіює граф verbatim; статус форсимо `draft`, тригер скидаємо до `on_start`/`""`; 201 + `FunnelResponse`.
- **`POST /{funnelId}/executions/stop`** — bulk-cancel активних запусків (виносимо логіку з `FunnelService.delete`); 200 + лічильник `modifiedCount`.
- **`POST /{funnelId}/test-run`** — резолв власника через `Bot.ownerChatId` → `SubscriberService.findByChat` → прямий enroll через `FunnelExecutionFactory.insertExecution(depth=0)` з рестартом пари; pre-validate перед enroll; 2xx = enroll зареєстровано.
- **`POST /{funnelId}/steps/{stepId}/preview`** — рендер через `VariableTemplateRenderer.render` (екранування під `parse_mode` байт-в-байт як рантайм); дані власника або стаб-підписник із семпл-значеннями.

Фронтенд: чотири нові дії стора (`duplicate`, `stopAllExecutions`, `testRun`, `preview`) + типи; кнопки **Дублювати**/**Зупинити все** в рядку списку та хедері редактора; **Test for me** + перемикач **Прев'ю** лише в хедері; нова панель прев'ю праворуч у редакторі; нові i18n-ключі в обидві локалі (parity-gate).

Валідацію кроків НЕ переписуємо — вона вже реалізована (бек `validateSteps` + фронт-мирор у `FunnelStepForm.vue`). Test-run лише перевикористовує її (вимагає підняти видимість `private validateSteps` → package/public).

## Architecture

### What we're building/modifying

**Backend (`com.botfunnel.funnel`)**
- **`FunnelController`** — +4 ендпоінти (duplicate, executions/stop, test-run, steps/{stepId}/preview), що дзеркалять наявні роути (owner з сесії, делегація в сервіс).
- **`FunnelService`** — +методи `duplicate`, `stopAllExecutions`, `testRun`, `previewStep`. Підняти видимість `validateSteps` (private → package-private) для перевикористання в test-run. Нова бізнес-помилка `funnel_owner_not_linked` (422).
- **`FunnelExecutionFactory`** (package-private, той самий пакет) — перевикористовуємо `cancelExistingForPair` + `insertExecution(depth=0)` для test-run. Без змін.
- **`VariableTemplateRenderer`** — перевикористовуємо `render(template, parseMode, Subscriber)` для прев'ю. Без змін. Прев'ю передає стаб-`Subscriber` із семпл-значеннями, коли власник не зв'язаний (рендер вимагає non-null Subscriber).
- **`SubscriberService.findByChat`** + **`Bot.getOwnerChatId`** — читаємо для резолву підписника автора (test-run і preview). Без змін.

**Frontend (`frontend/`)**
- **`stores/funnels.ts`** — +дії `duplicate`, `stopAllExecutions`, `testRun`, `preview` (shape наявних дій: catch-flip-`error`-RETHROW, store ніколи не кличе `useApiError`).
- **`types/funnel.ts`** — +request/response типи (preview request/response, stop-all count).
- **`pages/projects/[projectId]/funnels/index.vue`** — у рядок списку: кнопки Дублювати + Зупинити все (Dialog-confirm для деструктивної дії).
- **`pages/projects/[projectId]/funnels/[funnelId].vue`** — у хедер: Дублювати, Зупинити все (confirm), Test for me, перемикач Прев'ю; монтаж панелі прев'ю праворуч.
- **`components/funnels/FunnelMessagePreview.vue`** — нова панель прев'ю (greenfield): для message-кроків показує рендер з бекенду; для не-message — нейтральний плейсхолдер.
- **`i18n/locales/{uk,en}.json`** — нові ключі `funnels.editor.*`, confirm/result-рядки, `errors.funnels.funnel_owner_not_linked` + відсутні `funnel_invalid_trigger_type`/`funnel_invalid_keywords`.

### How it works

**Test for me.** Контролер → `requireFunnel` → резолв `Bot` проекту (CONNECTED) → `bot.getOwnerChatId()`. Якщо null → 422 `funnel_owner_not_linked`. Інакше `findByChat(projectId, bot.telegramBotId, ownerChatId)`; якщо порожньо або підписник не `ACTIVE` → той самий 422. Pre-validate воронки через `validateSteps` (порожня/невалідна → ті ж 422-коди, що й при активації). Потім `cancelExistingForPair(projectId, funnelId, subscriberId)` (рестарт) → `insertExecution(projectId, funnel, subscriberId, telegramBotId, 0)`. HTTP-відповідь 2xx = execution створено; реальна відправка йде асинхронно рушієм (збій Telegram-send → execution `failed` згодом, HTTP лишається 2xx).

**Duplicate.** `requireFunnel` → побудова нової `Funnel`: `steps = original.steps.map(FunnelStep::copyOf)` (id та ребра `next`/`timeoutTargetStepId`/`Button.targetStepId` verbatim), копіюються `keywords`/`allowReEnter`/`description`; `name = truncate("<назва> (копія)", 128)`; форсимо `status=draft`, `triggerType=on_start`, `triggerValue=""`. Insert → 201 + `FunnelResponse`. Draft never enters the partial-unique trigger index → колізії неможливі.

**Stop all.** `requireFunnel` → `mongoTemplate.updateMulti` over `{projectId, funnelId, status IN [running, waiting, waiting_for_reply]}` set `{status=cancelled, stepRunStatus=done, updatedAt=now}` (дзеркало `FunnelService.delete`). 200 + `modifiedCount`. `stepRunStatus=done` обов'язковий — інакше bulk-cancel програє mid-tick `persistProgress` (claim-CAS контракт).

**Preview.** `requireFunnel` → знайти крок за `stepId` у `funnel.steps`. Для message-типів (SEND_MESSAGE→текст, SEND_IMAGE→caption, MENU→тіло) резолв `Subscriber` автора через ownerChatId; якщо не резолвиться — стаб-`Subscriber` (`first_name=Іван, last_name=Петренко, username=ivan`, порожні custom) + прапорець «семпл-дані». `VariableTemplateRenderer.render(template, parseMode, subscriber)` → рендерений рядок у відповіді. Для не-message кроків — відповідь-плейсхолдер «крок не надсилає повідомлення». Ніколи не 500.

### Shared resources

| Resource | Owner (creates) | Consumers | Instance count |
|----------|-----------------|-----------|----------------|
| `FunnelExecutionFactory` (Spring bean) | Spring context | test-run service, FunnelTriggerServiceImpl, FunnelEventService | 1 (singleton) |
| `VariableTemplateRenderer` (static utility) | — (pure static) | preview service, StepExecutor | 0 (stateless static) |
| OkHttp `MockWebServer` (tests only) | `FunnelExecutionEngineIT` (JVM singleton) | test-run IT send-assertions | 1 (test singleton) |

Нових heavy-ресурсів фіча не вводить.

## Decisions

### Decision 1: Отримувач «Test for me» — `Bot.ownerChatId`, без код-лінкінгу
**Decision:** Адресат тест-запуску = `Bot.ownerChatId` (Telegram власника, проставлений Epic 04b при першому приватному `/start`). Код-лінкінгу в стилі Manychat не робимо.
**Rationale:** Зв'язок «власник ↔ Telegram-акаунт» уже існує з 04b; будувати другий механізм — overengineering. Підтримує US «Test for me», обмеження user-spec.
**Alternatives considered:** Код-лінкінг (одноразовий код у боті) — відкинуто: нова сутність + UI заради вже наявного зв'язку.

### Decision 2: Прямий enroll через `insertExecution(depth=0)`, не `fire()`/`dispatchForSubscriber`
**Decision:** Test-run enroll-ить через `FunnelExecutionFactory.insertExecution(...enrollDepth=0)`, обходячи матчинг тригерів.
**Rationale:** `fire()`/`dispatchForSubscriber` повторно матчать тригери `(triggerType, triggerValue, status=active)` — саме те, що тест має обійти (тестуємо й draft, і будь-який тригер). depth=0 = людський корінь. Підтримує AC «обходячи матчинг тригерів» + «draft-воронку теж можна протестувати».
**Alternatives considered:** `fire()` — відкинуто: не запустить draft і прив'яже до конкретного тригера.

### Decision 3: Політика повторного тесту — рестарт (`cancelExistingForPair` перед insert)
**Decision:** Перед `insertExecution` завжди викликаємо `cancelExistingForPair(projectId, funnelId, subscriberId)`.
**Rationale:** Re-enter partial-unique index (`funnelId_subscriberId` filtered in-flight) інакше дасть `DuplicateKeyException` на повторному кліку. Рестарт = кожен клік перевіряє воронку з нуля (очікувана семантика тест-інструменту). Підтримує AC «повторний клік скасовує попередній активний запуск і стартує заново».
**Alternatives considered:** Catch+no-op («вже тестується», дзеркало `fire()`) — відкинуто: суперечить очікуванню «перезапустити тест».

### Decision 4: Test-run pre-validate перед enroll
**Decision:** Перед enroll прогоняємо `validateSteps`; порожня/невалідна воронка → ті ж 422-коди, що й при активації. Видимість `validateSteps` піднімаємо `private → package-private` (без зміни логіки/кодів).
**Rationale:** Без pre-validate порожня воронка enroll-иться й миттєво `completed` (silent no-op), а невалідні кроки впадуть у рантаймі без зрозумілого фідбеку. Підтримує AC «перед enroll прогоняється валідація; порожня/невалідна → ті ж 422-коди».
**Alternatives considered:** Не валідувати — відкинуто: silent-complete плутає автора. Дублювати валідацію — відкинуто: drift.

### Decision 5: `funnel_owner_not_linked` (422) для нерезолвленого власника, ніколи 500
**Decision:** `ownerChatId=null` / підписник відсутній / не `ACTIVE` → 422 `funnel_owner_not_linked` з ремедіейшеном «напишіть `/start` боту». Новий код-константа в `FunnelService`; новий i18n-ключ у `errors.funnels` (обидві локалі).
**Rationale:** Усі три гілки — один клас «акаунт не зв'язаний», одна дія для автора. 422 (а не 500) бо це передбачуваний бізнес-стан. Підтримує AC «422 `funnel_owner_not_linked`, ніколи не 500».
**Alternatives considered:** 409/окремі коди на гілку — відкинуто: для автора ремедіація однакова.

### Decision 6: Duplicate — копіювати `keywords`, але скинути тригер до `on_start`/`""`, статус `draft`
**Decision:** Копія: граф через `copyOf` (id/ребра verbatim), `keywords`/`allowReEnter`/`description` копіюються; `status=draft`, `triggerType=on_start`, `triggerValue=""`; назва `"<назва> (копія)"` truncate до 128.
**Rationale:** Partial-unique index `(projectId, triggerType, triggerValue)` filtered `status=active AND triggerType=on_start` — draft до нього не входить, тож колізії неможливі. Скидання тригера прибирає сюрприз «дві воронки претендують на той самий тригер при активації». `keywords` копіюються (інертні до перемикання `triggerType=keyword`) — так у user-spec AC. Підтримує AC duplicate.
**Alternatives considered:** Remint step-ids — відкинуто: id стабільні, не індекси; зайвий код/ризик. Зберегти тригер verbatim у draft — безпечно, але плутає; user-spec явно вимагає скидання.

### Decision 7: Stop all — `updateMulti` через `MongoTemplate`, set `status=cancelled` ТА `stepRunStatus=done`
**Decision:** Виносимо bulk-cancel із `FunnelService.delete` у `stopAllExecutions(ownerId, projectId, funnelId)`: `updateMulti` over `{projectId, funnelId, status IN in-flight}` set `{status=cancelled, stepRunStatus=done, updatedAt}`. Скоуп по `projectId`+`funnelId`. Повертаємо `modifiedCount`.
**Rationale:** `stepRunStatus=done` обов'язковий — claim-CAS рушія (`stillClaimed = {_id, stepRunStatus=in_progress}`); cancel, що флипнув лише `status`, програє mid-tick `persistProgress`. Скоуп по `projectId` — fail-closed tenant hardening (як `cancelActiveFor`). `FunnelExecutionRepository` навмисно без query-методів — bulk через `MongoTemplate`. Підтримує AC stop-all.
**Alternatives considered:** Лише `status=cancelled` — відкинуто: програє гонку рушію (resurrection/double-send). Додати repo-метод — відкинуто: проти конвенції.

### Decision 8: Лічильник зупинених — `modifiedCount`, best-effort
**Decision:** Повертаємо `UpdateResult.getModifiedCount()` на момент bulk-операції; 0 — не помилка.
**Rationale:** Запуск, що стартував/завершився одночасно зі сканом рушія, може не потрапити в число — прийнятна гонка (коректність гарантує claim-CAS, точність лічильника — best-effort). Підтримує AC «лічильник = `modifiedCount`».
**Alternatives considered:** Точний лічильник через блокування — відкинуто: overengineering, claim-CAS уже гарантує коректність.

### Decision 9: Прев'ю — окремий бекенд-ендпоінт поверх `VariableTemplateRenderer`, on-the-fly контент, стаб-підписник як fallback
**Decision:** `POST /{funnelId}/steps/{stepId}/preview` рендерить через `VariableTemplateRenderer.render` (SEND_MESSAGE→текст, SEND_IMAGE→caption, MENU→текст-тіло кроку). **Контент береться з тіла запиту (on-the-fly — поточний, можливо незбережений, текст кроку + `parseMode`), а не зі збереженого кроку** — щоб панель була реактивною до того, що автор друкує зараз; `stepId` у шляху лишається для контексту/типу й anti-IDOR. Дані власника через ownerChatId; якщо не резолвиться — стаб-`Subscriber` (семпл-значення) + прапорець «семпл-дані». Не-message кроки → відповідь-плейсхолдер. Невідомий `stepId` → 404 (узгоджено з `requireFunnel`-патерном). Ніколи 500.
**Rationale:** user-spec: «**під час редагування** message-кроку автор бачить панель прев'ю» — отже прев'ю має відображати поточний ввід, не останній auto-save. Екранування під `parse_mode` має точно повторювати рантайм — рендер на фронті розійдеться (XSS/markup-дрейф, OWASP A03). `render` вимагає non-null `Subscriber` → стаб уникає NPE/500 при незв'язаному боті. Бекенд віддає рядок із Telegram-екрануванням (не browser-safe), тому фронт-панель виводить його **лише як текст, ніколи `v-html`** (Task 6, stored-XSS guard). Підтримує AC preview + «стаб-підписник із семпл-значеннями».
**Alternatives considered:** Рендер лише збереженого кроку за `stepId` — відкинуто: прев'ю лагало б за незбереженими правками (F-01). Фронтова реалізація підстановки+екранування в TS — відкинуто: drift-ризик (правила `{{`/`}}`, single-pass, Double `.0`, повний MarkdownV2-набір).

### Decision 10: i18n parity — додати `funnel_invalid_trigger_type`/`funnel_invalid_keywords` зараз
**Decision:** Окрім `funnel_owner_not_linked`, у `errors.funnels` додаємо `funnel_invalid_trigger_type` і `funnel_invalid_keywords` (коди вже є на бекенді, але відсутні у фронт-мапі).
**Rationale:** test-run pre-validate (Decision 4) може повернути ці коди; без них `resolveFunnelError` покаже generic, а parity-gate (`check-locales.mjs`) впаде. Підтримує AC «спливуть при pre-validate; parity-gate впаде без них».
**Alternatives considered:** Додати лише при появі — відкинуто: pre-validate робить їх досяжними вже зараз.

### Decision 11: Декомпозиція бекенду — групування за спільним концерном + секвенування спільних файлів
**Decision:** Чотири ендпоінти → дві бекенд-задачі: (1) мутації **Duplicate + Stop-all** (чисті операції над `Funnel`/`executions`, без контексту автора); (2) контекст автора **Test-run + Preview** (обидві резолвлять підписника власника через `ownerChatId`). Задачі секвенуються в окремі хвилі, бо обидві редагують `FunnelController.java` + `FunnelService.java` (уникаємо конфлікту паралельних агентів). `[TECHNICAL]`
**Rationale:** Логічно інструменти незалежні (user-spec), але фізично ділять контролер/сервіс. Групування за «резолвом автора» — реальний архітектурний шов (test-run і preview ділять helper резолву + потребують винесення/рендерера). Секвенування хвиль усуває merge-конфлікт при per-wave-комітах feature-execution.
**Alternatives considered:** 4 окремі задачі в одній хвилі — відкинуто: конфлікт редагувань спільних файлів. Одна мега-задача на весь бекенд — відкинуто: гірша атомарність ревʼю.

## Data Models

**Без змін схеми БД, без міграцій.** Усі задіяні колекції/поля існують:
- `bots.ownerChatId: Long?` — читаємо (test-run, preview).
- `funnels` (`status`, `triggerType`, `triggerValue`, `keywords`, `allowReEnter`, `description`, `steps[]`) — читаємо оригінал, вставляємо копію (duplicate).
- `funnel_executions` (`status`, `stepRunStatus`, `currentStepId`, `enrollDepth`, …) — insert (test-run), bulk-update (stop-all).

Нові DTO (request/response), без персистенції:
- `PreviewStepRequest` — поточний (можливо незбережений) контент кроку: `{ stepType, text, parseMode }` (on-the-fly — Decision 9). Прев'ю рендерить саме цей контент, не зчитує збережений крок.
- `PreviewStepResponse` — рівно `{ rendered: String, sampleData: boolean, kind: "message" | "non_message" }` (без ownerChatId/identity).
- `StopAllResponse` — `{ cancelled: long }`.
- Test-run/duplicate перевикористовують наявні відповіді (2xx / `FunnelResponse`).

## Dependencies

### New packages
- Немає. Усе на наявному стеку.

### Using existing (from project)
- `FunnelExecutionFactory.insertExecution` / `cancelExistingForPair` — прямий enroll (test-run).
- `FunnelService.validateSteps` (підняти видимість) / `requireFunnel` — pre-validate + anti-IDOR.
- `FunnelStep.copyOf` — verbatim-копія графа (duplicate).
- `VariableTemplateRenderer.render` — рендер прев'ю.
- `SubscriberService.findByChat` + `Bot.getOwnerChatId` — резолв підписника автора.
- `MongoTemplate.updateMulti` — bulk-cancel (stop-all).
- `AbstractIntegrationTest` (Testcontainers Mongo + in-memory JobRunr), `FunnelControllerIT` (MockMvc + `@WithMockAppUser` + csrf), `FunnelExecutionEngineIT` MockWebServer-харнес — ITs.
- Фронт: `useApi`, `stores/funnels.ts` shape, shadcn `Dialog` (confirm), `vue-sonner` (toast), `resolveFunnelError`, `check-locales.mjs` (parity-gate).

## Testing Strategy

**Feature size:** L

### Unit tests
- **Duplicate (service):** копія `status=draft`, тригер скинутий до `on_start`/`""`, граф ідентичний (id/ребра verbatim), `keywords`/`allowReEnter`/`description` скопійовані; назва `"<назва> (копія)"`; оригінал не змінено. **Boundary назви:** `==128` після суфікса → без обрізання; `==129` → обрізається до 128, лишається валідним (не 400).
- **Stop-all (service):** лише `running|waiting|waiting_for_reply` → `cancelled` + `stepRunStatus=done`; термінальні не чіпаються; скоуп `projectId`+`funnelId`; лічильник = `modifiedCount`; 0 активних → 0 (не помилка).
- **Test-run (service):** `ownerChatId=null` → 422 `funnel_owner_not_linked`; підписник відсутній/не ACTIVE → 422; порожня/невалідна воронка → 422-коди валідації; **draft-воронка (валідна, непорожня) → 2xx** (status-гейт не блокує тест); happy-path — `InOrder` assert: `cancelExistingForPair` ВИКЛИКАНИЙ ПЕРЕД `insertExecution(...depth=0)`.
- **Preview (service):** окремі assert з **точними рядками** для кожного режиму — `parseMode=null` (без екранування), `HTML` (екранує `& < > "`), `MarkdownV2` (екранує повний набір + backslash); edge-кейси рендерера (`{{`/`}}`→літерали, незакритий `{` verbatim, integral `Double` без `.0`); незв'язаний бот → стаб-підписник (семпл) + прапорець, без NPE; не-message крок → плейсхолдер (`kind=non_message`).
- **Frontend (vitest):** дії стора (`duplicate`/`stopAllExecutions`/`testRun`/`preview`) — flip-error-RETHROW shape; панель прев'ю рендерить message-результат / плейсхолдер для не-message; confirm-діалог Stop-all (відкриття/підтвердження/скасування).

### Integration tests
`@SpringBootTest` + MockMvc + embedded Mongo (Testcontainers); `MockWebServer` для Telegram-send у test-run.
- **Duplicate:** `POST /duplicate` → 201, нова воронка `draft`, тригер скинутий, граф ідентичний, оригінал не змінено; назва >128 truncate (не 400).
- **Stop-all:** `POST /executions/stop` → 200 + count; усі активні execution-и `cancelled`+`stepRunStatus=done`; термінальні не зачеплені; 0 активних → 200 + 0. **Cross-funnel scoping:** засіяти активний execution в ІНШІЙ воронці/проєкті — він НЕ скасований (доводить скоуп `projectId`+`funnelId`, tenant-isolation).
- **Test-run direct-enroll:** зв'язаний бот → новий execution (depth=0), MockWebServer отримав `sendMessage` (через прогон sweep як у `FunnelExecutionEngineIT`); рестарт — повторний виклик скасовує попередній і стартує заново; **draft-воронка → 2xx**.
- **Test-run 422 (HTTP-shape):** `ownerChatId=null` → 422 з тілом `code=funnel_owner_not_linked` (не 500); порожня воронка → 422-код валідації (перевірити саме HTTP-відповідь, не лише сервіс).
- **Preview:** `POST /steps/{stepId}/preview` → рендер зі змінними й коректним екрануванням; незв'язаний бот → семпл-дані.
- **Anti-IDOR + precedence:** усі ендпоінти на чужому/неіснуючому funnel/project → уніфікований 404 (`requireFunnel`); для test-run/preview `requireFunnel`-404 ПЕРЕВАЖАЄ над 422/stepId-404 (щоб 404-vs-422 не став oracle існування).

### E2E tests
- **None.** Playwright у проєкті тримається лише на golden-path перемикання локалі (architecture.md). Нові UI-компоненти покриваємо vitest.

## Agent Verification Plan

**Source:** user-spec "Как проверить" section.

### Verification approach
Поза автотестами агент перевіряє ендпоінти через curl/IT під час імплементації (per-task Verify-smoke нижче) і ганяє parity-gate локалей. Live-середовища немає → пост-деплою/MCP-кроків немає. Реальну доставку в Telegram перевіряє користувач уручну (бек не спостерігає доставку без live-середовища).

### Tools required
- `curl` — duplicate / stop-all / test-run(422) / preview ендпоінти.
- `bash` + `node frontend/scripts/check-locales.mjs` — i18n parity-gate.
- IT (`./gradlew test`) з `MockWebServer` — send-assertion для test-run happy-path.
- Playwright/Telegram MCP — **не потрібні** (немає live-середовища, E2E не додаємо).

## Risks

| Risk | Mitigation |
|------|-----------|
| Гонка stop-all vs тік рушія (bulk-cancel програє mid-tick `persistProgress`) | Дзеркалити `FunnelService.delete` точно: `status=cancelled` **і** `stepRunStatus=done` (claim-CAS гарантує детермінований виграш скасування) |
| Винесення приватного `validateSteps` змінить поведінку | Лише підняти видимість (private → package-private), без зміни логіки/кодів; покрити тестом |
| Дрейф екранування в прев'ю (фронт ≠ рантайм) | Рендер на бекенді через той самий `VariableTemplateRenderer`; стаб-`Subscriber` проти NPE |
| Stored-XSS у панелі прев'ю (Telegram-екранований рядок не browser-safe) | Панель виводить рендер лише як текст, ніколи `v-html`/`innerHTML`; vitest-assert; узгоджено з конвенцією проєкту «never v-html» (`FunnelStepForm.vue`) |
| `DuplicateKeyException` на повторному test-run | `cancelExistingForPair` перед `insertExecution` (рестарт-політика) |
| `ownerChatId` проставлений не-власником (документована гонка 04b first-`/start`-wins) | Не вирішуємо тут — наявна модель довіри 04b; test-run просто шле на цей chat |
| Назва `"<назва> (копія)"` перевищує `@Size(max=128)` → 400 | Truncate до 128 перед суфіксом/після; покрити тестом |
| Паралельні агенти редагують спільні `FunnelController`/`FunnelService` | Секвенування бекенд-задач у різні хвилі (Decision 11); те саме для `[funnelId].vue` на фронті |

## User-Spec Deviations

None.

Усі рішення tech-spec прямо реалізують user-spec (включно з технічними рішеннями в його секціях «Технические решения», «Ограничения», «Критерии приёмки»). Decision 11 (декомпозиція/секвенування) — суто технічний `[TECHNICAL]`, не змінює жодної вимоги user-spec.

## Acceptance Criteria

Технічні критерії приёмки (доповнюють користувацькі з user-spec):

- [ ] Усі 4 ендпоінти повертають коректні коди: duplicate 201, stop-all 200, test-run 2xx/422, preview 200/404; ніколи 500 на передбачуваних бізнес-станах.
- [ ] Anti-IDOR: усі ендпоінти проходять `requireFunnel` → уніфікований 404 для чужого/неіснуючого funnel/project.
- [ ] **Без міграцій БД** — схема не змінюється (нічого застосовувати/відкочувати).
- [ ] `validateSteps` лишається логічно незмінним (тільки видимість), наявні тести воронок зелені.
- [ ] i18n parity-gate (`check-locales.mjs`) зелений: нові ключі в обох локалях.
- [ ] Усі тести проходять (unit + integration); немає регресій у наявних тестах воронок/рушія.
- [ ] 422 з бізнес-кодом мапиться inline (`resolveFunnelError`), мережева/непередбачена → toast.
- [ ] Панель прев'ю виводить рендерений рядок **лише як текст, без `v-html`/`innerHTML`** (Telegram-екранування ≠ browser-safe; stored-XSS guard) — покрито vitest-assert.

## Implementation Tasks

### Wave 1 (backend mutations — незалежні)

#### Task 1: Duplicate + Stop-all ендпоінти
- **Description:** Додати `POST /{funnelId}/duplicate` (verbatim-копія графа через `FunnelStep.copyOf`, `status=draft`, тригер скинутий, назва `"(копія)"` truncate 128 → 201 + `FunnelResponse`) і `POST /{funnelId}/executions/stop` (виносимо bulk-cancel із `FunnelService.delete` у `stopAllExecutions`, set `status=cancelled`+`stepRunStatus=done`, скоуп `projectId`+`funnelId` → 200 + count). Обидва через `requireFunnel`. Деталі — Decisions 6, 7, 8.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `curl -X POST .../funnels/{fid}/duplicate` → 201, нова воронка `status=draft`, тригер скинутий, граф ідентичний; `curl -X POST .../funnels/{fid}/executions/stop` → 200 + count, активні execution-и `cancelled`+`stepRunStatus=done`
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/FunnelController.java`, `backend/src/main/java/com/botfunnel/funnel/FunnelService.java`, `backend/src/test/java/com/botfunnel/funnel/FunnelControllerIT.java` (+нові DTO у funnel-пакеті)
- **Files to read:** `backend/src/main/java/com/botfunnel/funnel/FunnelStep.java`, `backend/src/main/java/com/botfunnel/funnel/Funnel.java`, `backend/src/main/java/com/botfunnel/funnel/FunnelExecutionFactory.java`, `backend/src/test/java/com/botfunnel/AbstractIntegrationTest.java`

### Wave 2 (backend author-context — секвенована після Wave 1: спільні файли)

#### Task 2: Test-run + Preview ендпоінти
- **Description:** Додати `POST /{funnelId}/test-run` (резолв `Bot.ownerChatId` → `findByChat`; null/відсутній/не-ACTIVE → 422 `funnel_owner_not_linked`; pre-validate через `validateSteps` (підняти видимість private→package); `cancelExistingForPair` → `insertExecution(depth=0)`) і `POST /{funnelId}/steps/{stepId}/preview` (рендер через `VariableTemplateRenderer.render` для SEND_MESSAGE/SEND_IMAGE/MENU; стаб-`Subscriber` із семпл-значеннями при незв'язаному боті; плейсхолдер для не-message; ніколи 500). Обидва через `requireFunnel`. Деталі — Decisions 2, 3, 4, 5, 9. Test-run send-assertion — через MockWebServer-харнес `FunnelExecutionEngineIT`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `curl -X POST .../funnels/{fid}/test-run` при `ownerChatId=null` → 422 `funnel_owner_not_linked` (не 500); `curl -X POST .../funnels/{fid}/steps/{sid}/preview` → рендер зі змінними й коректним екрануванням під `parse_mode`
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/FunnelController.java`, `backend/src/main/java/com/botfunnel/funnel/FunnelService.java`, `backend/src/test/java/com/botfunnel/funnel/FunnelControllerIT.java` (+нові DTO, +тест із MockWebServer)
- **Files to read:** `backend/src/main/java/com/botfunnel/funnel/FunnelExecutionFactory.java`, `backend/src/main/java/com/botfunnel/funnel/VariableTemplateRenderer.java`, `backend/src/main/java/com/botfunnel/bot/Bot.java`, `backend/src/main/java/com/botfunnel/subscriber/SubscriberService.java`, `backend/src/test/java/com/botfunnel/funnel/FunnelExecutionEngineIT.java`

### Wave 3 (frontend foundation — паралельні, різні файли)

#### Task 3: Store-дії + типи
- **Description:** Додати в `stores/funnels.ts` дії `duplicate`, `stopAllExecutions`, `testRun`, `preview` за shape наявних дій (catch-flip-`error`-RETHROW; store ніколи не кличе `useApiError`; `syncRow` для duplicate-результату). Додати request/response типи в `types/funnel.ts` (`PreviewStepRequest/Response`, `StopAllResponse`). Vitest на дії.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `frontend/stores/funnels.ts`, `frontend/types/funnel.ts`, `frontend/tests/` (vitest на нові дії)
- **Files to read:** `frontend/composables/useApi.ts`, `frontend/composables/useApiError.ts`

#### Task 4: i18n-ключі + коди помилок (обидві локалі)
- **Description:** Додати ключі `funnels.editor.*` (duplicate, testForMe, stopAll, preview + confirm/result-рядки), плейсхолдер прев'ю для не-message кроків, і в `errors.funnels`: `funnel_owner_not_linked`, `funnel_invalid_trigger_type`, `funnel_invalid_keywords`. У ОБИДВІ `uk.json` і `en.json` (parity-gate). Деталь — Decision 10.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-smoke:** `node frontend/scripts/check-locales.mjs` → exit 0
- **Files to modify:** `frontend/i18n/locales/uk.json`, `frontend/i18n/locales/en.json`
- **Files to read:** `frontend/scripts/check-locales.mjs`, `frontend/pages/projects/[projectId]/funnels/[funnelId].vue`

### Wave 4 (frontend list + header actions — після Wave 3)

#### Task 5: Кнопки дій у списку та хедері редактора (без панелі прев'ю)
- **Description:** У `index.vue` — у рядок списку кнопки Дублювати + Зупинити все (Dialog-confirm для деструктивної дії, як delete-патерн). У хедер `[funnelId].vue` — Дублювати, Зупинити все (confirm), Test for me. Мапінг 422 inline через `resolveFunnelError`; успіх — toast (`vue-sonner`); мережева помилка — toast. Test for me на draft теж доступний.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-user:** open localhost funnels list + editor → кнопки рендеряться; Дублювати створює копію-draft; Зупинити все показує confirm; Test for me при незв'язаному боті показує inline-підказку «напишіть /start»
- **Files to modify:** `frontend/pages/projects/[projectId]/funnels/index.vue`, `frontend/pages/projects/[projectId]/funnels/[funnelId].vue`
- **Files to read:** `frontend/components/ui/dialog/`, `frontend/stores/funnels.ts`

### Wave 5 (frontend preview panel — секвенована після Wave 4: спільний `[funnelId].vue`)

#### Task 6: Панель прев'ю повідомлення (greenfield)
- **Description:** Новий `components/funnels/FunnelMessagePreview.vue`: для message-кроків показує рендер з бекенд-ендпоінту (як виглядатиме в Telegram), для не-message — нейтральний плейсхолдер «крок не надсилає повідомлення»; індикатор «семпл-дані», коли бот не зв'язаний. **Рендер виводиться лише як текст — НІКОЛИ `v-html`/`innerHTML`** (рендерений рядок несе Telegram-екранування, не browser-safe; stored-XSS guard, OWASP A03 — Decision 9). Помилки: 404 (невідомий крок) і мережева → нейтральний стан/повідомлення в панелі, не 500/порожній екран. Перемикач Прев'ю в хедері `[funnelId].vue` монтує панель праворуч (desktop-first ≥1024px). Vitest на компонент (incl. assert «no v-html»). Секвенована після Task 5 (спільний `[funnelId].vue`).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-user:** open редактор → перемкнути Прев'ю → message-крок показує рендер зі змінними; Delay/Tag/Event-крок показує плейсхолдер
- **Files to modify:** `frontend/components/funnels/FunnelMessagePreview.vue` (новий), `frontend/pages/projects/[projectId]/funnels/[funnelId].vue`, `frontend/tests/` (vitest)
- **Files to read:** `frontend/components/funnels/FunnelStepForm.vue`, `frontend/stores/funnels.ts`

### Audit Wave

#### Task 7: Code Audit
- **Description:** Full-feature code quality audit. Read all source files created/modified in this feature (from decisions.md + tech-spec "Files to modify"). Review holistically for cross-component issues: дублювання резолву ownerChatId-підписника між test-run і preview, відповідність claim-CAS контракту в stop-all, узгодженість із наявними патернами funnel-модуля. Write audit report.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 8: Security Audit
- **Description:** Full-feature security audit. Read all source files created/modified in this feature. OWASP Top 10 across components: anti-IDOR через `requireFunnel` на всіх 4 ендпоінтах, A03 markup/XSS-дрейф у прев'ю (екранування на бекенді), відсутність витоку ownerChatId/токенів, CSRF на нових POST. Write audit report.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 9: Test Audit
- **Description:** Full-feature test quality audit. Read all test files created in this feature. Verify coverage кожного інструменту (duplicate/stop-all/test-run/preview), осмисленість assertions (claim-CAS статуси, екранування, 422-гілки), баланс піраміди (unit + IT + vitest, без E2E). Write audit report.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 10: Pre-deploy QA
- **Description:** Acceptance testing: запустити всі тести (backend `./gradlew test` + frontend vitest + parity-gate), верифікувати критерії приёмки з user-spec і tech-spec. Деплою/пост-деплою немає (CI/CD не налаштований, немає live-середовища) — фіча мерджиться в `main` і перевіряється локально.
- **Skill:** pre-deploy-qa
- **Reviewers:** none
