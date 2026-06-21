---
# Creation date (YYYY-MM-DD)
created: 2026-05-31

# Status: draft | approved
status: draft

# Work type: feature | bug | refactoring
type: feature

# Feature size: S (1-3 files, local fix) | M (several components) | L (new architecture)
size: L
---

# User Spec: 10-funnels — Лінійні воронки (Фаза 1)

## Что делаем

Перша фаза епіку «Воронки» (серце продукту, project.md Epic 06): даємо власнику проєкту будувати **лінійні автоматичні сценарії** спілкування з підписниками — упорядкований список кроків, що виконується по черзі, коли підписник тисне deep-link і пише боту `/start`. Кроки фази 1: надіслати повідомлення, надіслати зображення (за URL), затримка, додати/прибрати тег, присвоїти значення кастом-поля. Тексти підтримують підстановку змінних (`{user.first_name}`, `{custom.<key>}`). Бекенд — рушій виконання поверх **MongoDB + JobRunr** (НЕ BullMQ/pg-boss із референс-ТЗ `workflow/06-воронки/README.md`): для кожного підписника зберігається стан виконання (поточний крок, час наступного запуску, статус), періодичний sweep-джоб просуває прострочені виконання. Фронтенд — список воронок + базовий вертикальний редактор кроків. Ця фаза замінює заглушку `NoOpFunnelTriggerService`, яку вже викликає вебхук-воркер `ProcessTelegramUpdateJob`. **Без inline-кнопок, гілок і очікування відповіді** — це Фаза 2 (`11-funnels-interactive`). Карта всіх 4 фаз: `docs/roadmap/funnels.md`.

## Зачем

Без воронок користувач підключив бота й має підписників (Epic 04/09), але не може автоматизувати комунікацію — мусить або кодити бота вручну, або платити за дорогі no-code інструменти (ManyChat тощо). Лінійна воронка дає базову, але повноцінно робочу автоматизацію: drip-послідовність повідомлень із затримками та діями над підписником (теги, кастом-поля), що запускається маркетинговим реф-посиланням `t.me/<bot>?start=ref_promo`. Це мінімальний працюючий зріз «серця продукту», на якому далі будується інтерактив (Фаза 2) і додаткові тригери (Фаза 3). project.md маркує Epic 06 як Critical; це наступний крок MVP-демо «за 10 хвилин підключити бота й запустити воронку».

## Как должно работать

**Сценарій 1 — створення й побудова воронки:**
1. Власник на `/projects/{id}/funnels` тисне «Create funnel», вводить назву (+ опц. опис) → воронка у статусі `draft`, порожній список кроків, відкривається редактор.
2. У редакторі тисне «+ Add step» → picker типу кроку (Send Message / Send Image / Delay / Add Tag / Remove Tag / Set Custom Field) → форма кроку → крок додано в кінець.
3. Переміщає кроки ↑/↓, редагує, видаляє. Стеля — `FUNNEL_MAX_STEPS` (default 50).

**Сценарій 2 — тригер і deep link:**
1. У налаштуваннях воронки тригер `/start` + (опц.) `trigger_value` (напр. `ref_promo`).
2. Система показує готове посилання `t.me/<bot>?start=ref_promo` з кнопкою «Copy». Порожній `trigger_value` → воронка ловить «голий» `/start`.

**Сценарій 3 — активація:**
1. Власник тисне «Activate».
2. Валідація: ≥1 крок, усі обовʼязкові поля кроків заповнені, немає конфлікту `(/start, trigger_value)` з іншою active-воронкою → статус `active`. За помилки — 422 з людиночитним кодом.

**Сценарій 4 — проходження воронки підписником:**
1. Підписник тисне deep-link → Telegram шле боту `/start ref_promo`.
2. Вебхук-воркер `ProcessTelegramUpdateJob` (після `upsertFromTelegramUpdate`, який ставить `status=active`) викликає `fire(projectId, chatId, "on_start", "ref_promo")`.
3. Рушій резолвить CONNECTED-бота, знаходить підписника, створює `funnel_execution` зі **snapshot** кроків відповідної воронки й запіненим `telegramBotId`.
4. Sweep-джоб по черзі виконує кроки: надсилає повідомлення/зображення з підставленим іменем, витримує Delay (через `nextRunAt`), додає/прибирає теги, ставить кастом-поля. Кроки без Delay у межах одного тіку виконуються підряд.
5. Після останнього кроку виконання → `completed`.

**Сценарій 5 — керування під час роботи:**
1. `/stop` від підписника → усі його активні виконання → `cancelled`.
2. `paused` воронки → нові `/start` не створюють виконань; ті, що в дорозі, дограють.
3. Повторний `/start` поки активне виконання: default (`allow_re_enter=false`) — ігнор; `allow_re_enter=true` — старе виконання скасовується, стартує нове з кроку 0.
4. Редагування кроків active-воронки → нові запуски за новою версією; in-flight доходять за своїм snapshot.
5. Видалення воронки → confirm → усі активні виконання `cancelled`, потім воронка видалена.

## Критерии приёмки

- [ ] **CRUD.** `POST/GET/PATCH/DELETE /api/v1/projects/{projectId}/funnels[/{funnelId}]`: створення (draft), список із фільтром `?status=`, редагування метаданих+кроків, видалення (зі скасуванням активних виконань). Кожен endpoint викликає `ProjectService.requireOwned(ownerId, projectId, false)` першим; чужий/soft-deleted/невалідний `projectId` → uniform 404.
- [ ] **Ліміт кроків.** Додавання понад `FUNNEL_MAX_STEPS` (50) → 422 `funnel_max_steps_exceeded`.
- [ ] **Send Message.** Надсилає текст (≤4096) з `parse_mode` (None/HTML/MarkdownV2, default None); порожній текст → 422. Якщо після підстановки змінних текст перевищує 4096 — обрізається до ліміту перед надсиланням (а не падіння кроку), з WARN-логом.
- [ ] **Send Image.** Надсилає зображення за URL + caption через новий `sendPhoto`: 429 → внутрішній backoff+retry; 403/400-chat-not-found → flip blocked/deleted + виконання `cancelled`; інші термінальні → виконання `failed`. Недоступний/невалідний URL (Telegram відповів помилкою) → термінальна → `failed` з логуванням.
- [ ] **Delay.** Крок (число, одиниця ∈ {хв, год, дні}) ставить `nextRunAt = now + duration`, виконання → `waiting`; наступний sweep просуває (точність ±~`FUNNEL_SCHEDULER_INTERVAL_SECONDS`). Тривалість < 1 хв → 422.
- [ ] **Add/Remove Tag.** Викликають `SubscriberServiceImpl.addTag/removeTag` (ідемпотентно; автоемісія `subscriber_tag_added/removed`).
- [ ] **Set Custom Field.** Значення валідується `CustomFieldValueValidator` за типом і атомарно ставиться у `customFields.<key>` + аудит `subscriber_custom_field_set` (через спільний сервіс). Невалідне значення → виконання `failed`, код `custom_field_type_mismatch`. Видалене з проєкту визначення поля → крок тихо пропускається, виконання триває.
- [ ] **Templating.** `{user.first_name|last_name|username}`, `{custom.<key>}` підставляються з даних підписника; невідома/порожня → `""`; `{{`/`}}` → `{`/`}`; NUMBER/BOOLEAN/DATE рендеряться по-людськи (без `30.0` для цілих, ISO для дат). Для `parse_mode`=HTML/MarkdownV2 **підставлені значення** екрануються за правилами режиму (розмітка автора лишається як є).
- [ ] **Тригер + deep link.** `fire("on_start", "ref_x")` стартує саме воронку з `trigger_value="ref_x"`; голий `/start` (payload `""`) → воронку з порожнім `trigger_value`; немає збігу → нічого не стартує.
- [ ] **Конфлікт тригера.** Активація другої active-воронки з тим самим `(/start, trigger_value)` → 422 `funnel_trigger_conflict`; різні `trigger_value` (і голий vs payload) співіснують.
- [ ] **At-most-once per step.** Повторний прогін claimed (`in_progress`/`done`) кроку — JobRunr retry, рестарт інстанса, друга репліка — не пере-надсилає.
- [ ] **Multi-replica безпека.** Дві репліки одночасно не виконують той самий крок того самого виконання (розподілений лок JobRunr + atomic `findAndModify` claim).
- [ ] **Послідовність без Delay.** Воронка з кількох НЕ-Delay кроків доходить до кінця за один sweep-тік (а не «крок за тік»).
- [ ] **`/stop`.** `cancelActiveFor` → усі активні виконання підписника → `cancelled`.
- [ ] **Pause.** `paused` не створює нових виконань; активні дограють.
- [ ] **Delete.** Видалення воронки скасовує всі активні виконання (`cancelled`), потім видаляє документ.
- [ ] **Re-enter.** `allow_re_enter=false`: повторний `/start` поки активне (`running`|`waiting`) виконання — ігнор (атомарно через unique partial index `(funnelId, subscriberId)`). `=true`: старе атомарно → `cancelled`, ПОТІМ insert нового з кроку 0.
- [ ] **Status gating.** Перед кожним send — перевірка `Subscriber.status`; не-`active` → виконання `cancelled`.
- [ ] **Send-failure semantics.** Send 403 / 400-chat-not-found → `TelegramSender` авто-flip blocked/deleted, виконання → `cancelled`. Інші термінальні (5xx-exhausted, інші 4xx) → виконання `failed`, без funnel-level ретраїв.
- [ ] **Bot pinning.** `telegramBotId` снапшотиться на виконання при старті; якщо цей бот уже не `CONNECTED` на момент send → виконання `failed` (без переключення на reconnected-бота).
- [ ] **Snapshot.** Правка кроків воронки не змінює поведінку виконань, що вже в дорозі.
- [ ] **Завершення.** Після останнього кроку snapshot виконання → `completed`.
- [ ] **`fire()` isolation.** Виняток усередині `fire()` не марковує raw_update як FAILED і не спричиняє retry-шторм webhook-обробки (ідемпотентний swallow+log).
- [ ] **Реактивація.** `/start` від `unsubscribed`-підписника: upsert ставить `status=active` ДО `fire()` (порядок у `ProcessTelegramUpdateJob`), тож перший send не само-скасовується.
- [ ] **Каскад.** `ProjectHardDeleteJob` при hard-delete проєкту видаляє `funnels` + `funnel_executions` (перед drop `projects`).
- [ ] **Observability.** Кожен перехід стану виконання логується (claim win/loss, step `in_progress`/`done`, send-fail із terminal reason, `completed`/`cancelled`/`failed`) — JobRunr dashboard вимкнено, тож логи — єдина видимість (взірець: лог-константи `ProcessTelegramUpdateJob`).

## Ограничения

- **Стек:** Java 21 + Spring MVC on virtual threads + MongoDB sync driver + JobRunr 7.3.2 + Redis. Рушій = JobRunr `@Recurring` sweep + колекція `funnel_executions` (`architecture.md` → «Funnel Step Execution»). **НЕ** BullMQ/pg-boss із референс-ТЗ.
- **Мультиінстансність підтверджена** → sweep має бути JobRunr `@Recurring` із розподіленим локом (не Spring `@Scheduled`). **Точна каденція (~30с sub-minute) — рішення tech-spec:** у репо немає прецеденту sub-minute `@Recurring` (усі наявні — daily cron), тож tech-spec має підтвердити підтримку в JobRunr 7.3.2 і за потреби впасти на cron 1 хв (точність Delay тоді ±~60с — для drip прийнятно). Коректність (at-most-once) гарантує atomic per-execution claim **незалежно** від каденції — це лише питання точності/ефективності.
- **Доступ:** `ProjectService.requireOwned` першим у кожному project-scoped endpoint (uniform 404, anti-IDOR). Базовий шлях `/api/v1/projects/{projectId}/funnels`.
- **Telegram:** текст ≤4096; deep-link payload ≤64 chars `[A-Za-z0-9_-]`. У фазі 1 без `reply_markup` (кнопки — Фаза 2). Send Image — лише URL (без upload).
- **Subscriber:** воронка стартує/шле тільки для `active` підписників. Усі мутації підписника (теги, кастом-поля) — **виключно** через `SubscriberServiceImpl` (sole-writer rule для `subscriber_events`, patterns.md — НЕ писати події напряму й не форкати другий writer). Lookup за `(projectId, telegramBotId, chatId)`; підписник відсутній на момент `fire()` → log + skip.
- **Delay:** через `nextRunAt`, ніколи `Thread.sleep`; мінімум 1 хвилина; точність ±~30с (sweep-каденція).
- **Snapshot:** склад кроків копіюється на виконання при старті — правки воронки не впливають на in-flight.
- **Деплой:** CI/CD ще немає — деплой ручний/локальний. Нові env vars (`.env.example` + `docs/local-setup.md`): `FUNNEL_SCHEDULER_INTERVAL_SECONDS` (30), `FUNNEL_MAX_STEPS` (50).
- **Індекси:** annotation-driven (`auto-index-creation=true`), без `@PostConstruct ensureIndex`. Критичний `(status, nextRunAt)` для sweep; `(projectId)` для каскаду; unique partial `(funnelId, subscriberId)` на `running|waiting` для re-enter.

## Риски

- **Дублювання повідомлень** (JobRunr retry / рестарт / репліки). **Митигація:** atomic claim (`findAndModify` CAS, `pending→in_progress→done`) ПЕРЕД send + re-entry guard на claimed/done. At-most-once per step.
- **Drift таймінгу Delay** (sweep ~30с). **Митигація:** документований ±~30с, мін 1 хв — для drip невидимо.
- **Навантаження sweep** на великій базі виконань. **Митигація:** індекс `(status, nextRunAt)`, atomic-claim батч, наявний `/start` rate-limit (Epic 09, 100/min) стримує масовий старт.
- **Зміна воронки під час активних виконань.** **Митигація:** snapshot кроків на виконання при старті.
- **`fire()` кидає виняток і поронить webhook-обробку.** **Митигація:** ідемпотентний `fire()` зі swallow/log власних помилок; не дати raw_update піти у FAILED+retry.
- **Два `@Service FunnelTriggerService`** → context startup fail. **Митигація:** ЗАМІНИТИ `NoOp` (не додавати другий бін).
- **Бот reconnect посеред воронки** змінює `telegramBotId`. **Митигація:** пін `telegramBotId` на виконанні; не-CONNECTED на send → `failed`.
- **Send у неактивний чат.** **Митигація:** pre-send status-check → cancel (не покладатись на bounce).
- **Multi-replica подвійний sweep.** **Митигація:** JobRunr `@Recurring` розподілений лок + atomic claim (друга лінія).
- **Дублювання CRM-логіки / псування аудиту.** **Митигація:** Set-Custom-Field винесено у спільний сервіс; усі мутації підписника лише через `SubscriberServiceImpl`.

## Технические решения

- Мы решили **робити рушій на JobRunr + MongoDB** (`funnel_executions`), потому что це наявний стек і `architecture.md` уже описує цей патерн; BullMQ/pg-boss із референс-ТЗ у проєкті немає.
- Мы решили **замінити `NoOpFunnelTriggerService` єдиним реальним `@Service`**, потому что два біни того самого інтерфейсу без `@Primary` зламають старт контексту; точка виклику вже існує в `ProcessTelegramUpdateJob`.
- Мы решили **snapshot-ити список кроків на виконання при старті**, потому что це найпростіший спосіб захистити in-flight виконання від правок воронки без версіонування й без блокування редагування.
- Мы решили **at-most-once per step через atomic claim + per-step `pending→in_progress→done`**, потому что exactly-once недосяжне, а дублювання повідомлень — найгірший UX; claim ПЕРЕД send + re-entry guard це гарантують навіть на кількох репліках.
- Мы решили **sweep через JobRunr `@Recurring` із розподіленим локом** (не Spring `@Scheduled`), потому что бекенд працює в кількох репліках і лок не дасть задвоїти sweep. (Точна каденція ~30с проти cron-1хв — рішення tech-spec, бо sub-minute `@Recurring` не має прецеденту в репо.)
- Мы решили **пінити `telegramBotId` на виконанні**, потому что reconnect бота не має «таємно» переключати воронку на іншого бота — краще передбачуваний `failed`.
- Мы решили **Delay через `nextRunAt`, не `Thread.sleep`**, потому что sleep запінив би worker-тред на години/дні.
- Мы решили **default `parse_mode` = None** (picker None/HTML/MarkdownV2), потому что звичайний текст найбезпечніший із підстановкою змінних (спецсимвол в імені не зламає надсилання); для HTML/MarkdownV2 екрануємо підставлені значення.
- Мы решили **винести Set-Custom-Field логіку у спільний сервіс** (зараз inline у `SubscriberCustomFieldsController` із request-scope залежностями), потому что engine працює в JobRunr-воркері без HTTP-контексту.
- Мы решили **re-enter guard через unique partial index `(funnelId, subscriberId)`** на `running|waiting` (єдиний механізм, не дублювати query-time перевіркою), потому что індекс дає атомарну гарантію без гонок.
- Мы решили **зберігати завершені виконання безстроково** (без TTL), потому что вони потрібні для re-enter guard і майбутньої аналітики; TTL — рішення епіку аналітики.
- Мы решили **НЕ робити** inline-кнопки/гілки/Wait-for-Reply/Menu, додаткові тригери (Keyword/Tag-added/API-event), Test-for-me, дублювання, «Force stop all», превʼю, upload зображень, per-execution аналітику, явний End Funnel, секундні затримки, drag-and-drop, лічильник «скільки підписників у воронці» — потому что це наступні фази/епіки (`docs/roadmap/funnels.md`); фаза 1 — мінімальний робочий лінійний зріз.

## Тестирование

**Unit-тести:** делаются всегда. Покриття: step-executors (SendMessage/SendImage/Delay/AddTag/RemoveTag/SetCustomField), `VariableTemplateRenderer` (підстановка / unknown→"" / `{{` escape / екранування під HTML+MarkdownV2 / Double+Instant рендер), переходи стану виконання, trigger-matcher (payload exact-match), валідація воронки перед активацією.

**Интеграционные тесты:** делаем — рушій критично залежить від конкурентності й планувальника, юніти цього не покриють. На `AbstractIntegrationTest` (Testcontainers Mongo + `JobRunrInMemoryConfig` + MockWebServer для Telegram): тригер → виконання створено й проходить кроки; послідовні НЕ-Delay кроки за один тік; Delay → `nextRunAt` + resume; idempotency (повторний прогін claimed-кроку не дублює send); atomic-claim race на двох «репліках» (за взірцем `SubscriberExportConcurrencyIT`); `/stop` → cancel; paused → no new + активні дограють; re-enter (false ігнор / true cancel+restart, unique partial index); send-fail 403→cancelled / 5xx-exhausted→failed; status gating; bot pinning (не-CONNECTED → failed); snapshot (правка не чіпає in-flight); `requireOwned` + cross-project isolation; `ProjectHardDeleteJob` cascade видаляє funnels+funnel_executions; sub-minute `@Recurring`-каденція реально спрацьовує. Delay-таймінг детермінується інʼєкцією `java.time.Clock` (`ClockConfig`) + прямим викликом sweep-методу.

**E2E тести:** делаем (Playwright) — редактор є критичним user-facing інтерфейсом; ланцюг picker → форма кроку → налаштування тригера → активація вимагає browser-level перевірки (реактивність, валідація, deep-link «Copy»), яку Vitest на компонентному рівні не забезпечує. Сценарій: створити лінійну воронку, додати/перемістити/видалити крок, налаштувати тригер + побачити deep-link і «Copy», активувати. Реальний Telegram-send не автоматизується (потрібен реальний бот) — покривається MockWebServer в IT + ручним локальним прогоном.

## Как проверить

### Агент проверяет

| Шаг | Инструмент | Ожидаемый результат |
|-----|-----------|-------------------|
| Прогнати backend-тести | `cd backend && ./gradlew test` (+ `-PrunSlow=true`) | Усі unit+IT зелені (engine, idempotency, race, cancel, paused, re-enter, cascade) |
| Прогнати frontend-тести | `cd frontend && pnpm test` | Vitest (компоненти редактора + i18n parity) зелені |
| i18n parity gate | `cd frontend && node scripts/check-locales.mjs` | uk.json/en.json ключі збігаються (немає дрейфу `funnels.*`/`errors.funnels.*`) |
| E2E редактора | `cd frontend && pnpm test:e2e` | Playwright golden-path (створити→кроки→тригер→активувати) проходить |
| Тригер-інтеграція | unit `ProcessTelegramUpdateJobTest.startCommandFiresFunnelTrigger` | `fire(projectId, chatId, "on_start", payload)` викликається після upsert |

### Пользователь проверяет

- **End-to-end з реальним Telegram (локально):** `docker compose -f infra/docker-compose.yml up -d` + `./gradlew bootRun` + `pnpm dev` + `ngrok` тунель + BotFather throwaway-бот → Connect у UI → побудувати лінійну воронку (Send → Delay 1хв → Send із `{user.first_name}` → Add Tag) → активувати з `trigger_value=ref_smoke` → скопіювати deep-link → відкрити з власного Telegram (`/start ref_smoke`) → спостерігати: перше повідомлення, через ~1хв друге з підставленим іменем, тег додано → `/stop` → виконання `cancelled`. Перевірити `funnel_executions` у `mongosh` (`status`/`currentStepIndex`/`nextRunAt`). Це єдиний спосіб підтвердити реальний Telegram-прохід (HTTPS+setWebhook не автоматизувати локально без тунелю). Runbook: `docs/staging-smoke/10-funnels.md`.
