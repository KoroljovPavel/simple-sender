---
created: 2026-06-09
status: approved
branch: dev
size: L
---

# Tech Spec: 15-message-composer

## Solution

Замінюємо три плоскі типи кроків (`SEND_MESSAGE`, `SEND_IMAGE`, `MENU`) одним кроком-композером
`StepType.MESSAGE`, що тримає впорядкований `List<ContentBlock>`. Один композер-крок шле підписнику
**N окремих повідомлень Telegram** по черзі; до **останнього** не-альбомного блоку опційно
прикріплюються inline-кнопки + таймаут park-on-reply (механіка MENU). Старі типи видаляються
з бекенду і фронтенду; продакшну ще немає, тож наявні `funnels` і `funnel_executions` чистимо
вручну на деплої замість backfill.

Ключові інваріанти, що зберігаються: per-node at-most-once (один claim, без per-block курсора),
snapshot-ізоляція (`ContentBlock` immutable, глибока копія в `copyOf`), callback-контракт
`{executionId}:{buttonIndex}`, anti-SSRF (медіа лише URL/file_id, Telegram тягне сам),
backend-side `parseMode`-екранування і XSS-guard у превʼю.

## Architecture

### What we're building/modifying

**Backend — модель і персистенція:**
- **`ContentBlock`** (net-new) — immutable `record` з дискримінатором `BlockType type` + nullable
  per-type полями. Embedded у `FunnelStep`. Не несе Mongo `_class`.
- **`BlockType`** (net-new enum) — `TEXT, IMAGE, VIDEO, AUDIO, FILE, ALBUM`.
- **`MediaItem`** (net-new) — immutable `record` для елемента альбому (`url`/`file_id` + опційний
  `caption` лише на першому).
- **`FunnelStep`** — прибрати плоскі поля `text`/`parseMode`/`imageUrl`/`caption`; додати
  `List<ContentBlock> blocks`; `buttons` + `timeout*` лишаються (переюзаються композером). `copyOf`
  глибоко копіює `blocks`.
- **`StepType`** — видалити `SEND_MESSAGE`, `SEND_IMAGE`, `MENU`; додати `MESSAGE`.

**Backend — send / engine:**
- **`TelegramSender`** — нові методи `sendVideo`/`sendAudio`/`sendDocument` (тонкі обгортки над
  спільним `send(...)`) + `sendMediaGroup` з окремим **масив-маппером** відповіді.
- **`StepExecutor`** — гілка `MESSAGE`: послідовно шле блоки, підставляє змінні в text+усіх caption,
  trim+WARN над лімітами, до останнього не-альбомного блоку чіпляє клавіатуру → `waitForReply`;
  без кнопок → `cont()`. Видалити гілки `sendMessage`/`sendImage`/`menu`.
- **`FunnelService.validateSteps`** — case `MESSAGE`: валідація блоків (кількість, per-type поля,
  альбом 2–10, кнопки лише на останньому не-альбомному, ліміти over-length як warning). Видалити
  старі cases. DTO-ланцюг (`FunnelStepDto`, `toSteps`/`toStepDto`, `FunnelResponse`) — провести
  `List<ContentBlock>`.
- **`FunnelTriggerServiceImpl.advanceOnCallback`** — резолвити клавіатуру з композер-кроку (останній
  блок), зберегти строгий парсинг + IDOR-перевірки + `recordButtonClick`.
- **Preview** — `/preview` розширити на мультиблок (request: блоки; response: масив відрендерених
  блоків), зберігши backend-side екранування + XSS-guard.

**Frontend:**
- **`types/funnel.ts`** — `StepType` (видалити старі, додати `MESSAGE`), net-new `ContentBlock`/
  `BlockType`/`MediaItem` типи, оновлені preview DTOs.
- **`FunnelStepForm.vue`** — net-new саб-редактор композера: список блоків (add/move/remove), per-type
  віджет, кнопки+таймаут на останньому блоці. Видалити старі per-type гілки.
- **`FunnelMessagePreview.vue`** — рендер впорядкованого стосу різнотипних блоків (text-only `{{ }}`,
  `:src` для медіа, ніколи v-html).

**Deploy:**
- Ручне очищення `funnels` + `funnel_executions` (продакшну немає; backfill не робимо).

### How it works

**Save/activate:** фронт надсилає `FunnelStep` з `blocks: ContentBlock[]` → `toSteps` мапить у
`List<ContentBlock>` → `validateSteps` перевіряє жорсткі правила (інлайн 422-коди) → persist.

**Runtime (sweep → drive → execute):** engine claim-CAS (per step, без змін) → `StepExecutor`
гілка `MESSAGE` ітерує `blocks`: для кожного блоку рендерить text/caption через
`VariableTemplateRenderer`, шле відповідний `TelegramSender.send*`. Останній не-альбомний блок: якщо
крок має `buttons` — чіпляє `buildReplyMarkup` до цього send-у і повертає `waitForReply(deadline)`
(park-on-reply); інакше `cont()`. Усі N send-ів — під **одним claim** (per-node at-most-once: крах
посеред блоків лишає крок `in_progress`, без повтору).

**Callback:** натискання кнопки → webhook → `advanceOnCallback` (строгий парсинг
`{executionId}:{buttonIndex}`, IDOR/owner-перевірки) → резолв кнопки з останнього блоку композера →
`resumeOnCallback` branch. Таймаут → sweep → `timeoutTargetStepId` (паритет MENU).

**Album:** `sendMediaGroup` → масив `Message` → окремий маппер (не спільний single-`message_id` шлях).

### Shared resources

| Resource | Owner (creates) | Consumers | Instance count |
|----------|----------------|-----------|----------------|
| `TelegramSender` (HTTP client, retry/429/audit/scrub) | Spring `@Component` | `StepExecutor`, `SubscriberController` | 1 (singleton) |
| `VariableTemplateRenderer` (pure static) | static | `StepExecutor`, `FunnelService.previewStep` | n/a (stateless) |

Нових heavy-ресурсів не вводимо — переюзаємо наявний `send(...)` шлях (retry/429/audit/scrub
успадковуються безкоштовно).

## Decisions

### Decision 1: Модель ContentBlock — flat immutable record + BlockType дискримінатор
**Decision:** `ContentBlock` — один immutable `record` з полем `BlockType type` і nullable per-type
полями; embedded у `FunnelStep.blocks`, **без Mongo `_class`**.
**Rationale:** дзеркалить наявний плоский підхід `FunnelStep` (Decision 12 — без `_class`), тримає
блок immutable для snapshot-копії (`copyOf` робить shallow-copy списку, валідну бо елементи
immutable). Підтримує US: «`ContentBlock` має бути immutable (контракт snapshot-ізоляції)».
**Alternatives considered:** sealed-ієрархія з Jackson `@JsonTypeInfo`/polymorphic `_class` — важче
інтегрується зі Spring Data, вводить `_class` у документ і прямо суперечить Decision 12.

### Decision 2: Кнопки + таймаут лишаються полями рівня кроку (FunnelStep), не блоку
**Decision:** `buttons`, `timeoutValue/Unit/TargetStepId` лишаються на `FunnelStep` (як зараз у MENU);
композер чіпляє клавіатуру до **останнього** send-у.
**Rationale:** зберігає callback-контракт `{executionId}:{buttonIndex}`, строгий парсинг,
IDOR/owner-перевірки `advanceOnCallback`, `recordButtonClick` і `lastButtonClicked` майже без змін —
змінюється лише джерело резолву кнопок (з MENU-кроку на останній блок композера). Підтримує AC:
«Callback-контракт незмінний» + «Таймаут park-on-reply збережено (паритет MENU)».
**Важливо:** наявний guard `advanceOnCallback` «cursor must point at a MENU step» **замінюється на
позитивний guard `stepType == MESSAGE`** з резолвом клавіатури з останнього не-альбомного блоку (а не
видаляється/послаблюється) — інакше всі parked-MESSAGE callbacks мовчки відхиляться.
**Alternatives considered:** перенести `buttons` у `ContentBlock` — вимагало б переписати
index-addressing та IDOR-перевірки; зайвий ризик без вигоди (UI все одно показує їх «на останньому
блоці»).

### Decision 3: Per-node at-most-once (один claim на всі N send-ів)
**Decision:** усі блоки шлються в межах однієї гілки `StepExecutor` під одним claim; немає
per-block курсора на `FunnelExecution`.
**Rationale:** per-block прогрес вимагав би нового персистованого стану (`currentBlockIndex`) і
claim-conditional записів між блоками — невиправдана складність для цієї фази. Узгоджено з поточною
send-семантикою. Підтримує AC: «At-most-once на рівні ноди: крах посеред відправки лишає ноду
`in_progress` (без повтору)».
**Семантика помилки посеред блоків:** якщо send блоку K кидає `TelegramSendException` — гілка `MESSAGE`
мапить його через наявний `fromTerminalReason` (BLOCKED_BY_USER/CHAT_NOT_FOUND → `cancel`, інакше
`fail`), крок НЕ просувається; блоки 1..K-1 вже надіслані (lost-forward, без повтору). Це та сама
семантика, що в поточних `sendMessage`/`sendImage`.
**Alternatives considered:** per-block re-entrancy — нова схема стану + ризик подвійних send-ів;
відкладено.

### Decision 4: Видалення старих типів + ручне очищення даних замість backfill
**Decision:** видалити `SEND_MESSAGE`/`SEND_IMAGE`/`MENU` з усіх місць; на деплої вручну видалити
`funnels` + `funnel_executions`.
**Rationale:** продакшну немає; backfill flat→composer надто ризикований/складний. Чистіша модель
без подвійної підтримки. Підтримує AC: «Старі типи... видалені; воронки будуються лише на композері».
**Alternatives considered:** `ApplicationRunner` backfill (`FunnelStepIdBackfill`-патерн) — невиправданий
для нульових продакшн-даних.

### Decision 5: Альбом — окремий масив-маппер у TelegramSender
**Decision:** `sendMediaGroup` будує `media` JSON-масив і має власний маппер відповіді (масив
`Message`); решта нових методів переюзають спільний `send(...)`.
**Rationale:** `mapBodyToSentMessage` припускає single `message_id` і впаде на альбомній відповіді.
Підтримує AC: «альбом коректно мапить масив повідомлень-відповідей».
**Type-mixing предикат (Telegram media group):** елементи альбому мають бути **всі photo, всі video,
або суміш photo+video**; audio/document **не змішуються** з іншими типами в одній групі. `caption`
осмислений лише на **першому** елементі. Валідація на save (Task 4) enforce-ить цей предикат інлайн-кодом.
**Alternatives considered:** загнати альбом у спільний шлях — несумісно з форматом відповіді.

### Decision 6: Медіа лише URL/file_id (без дереференсу на бекенді)
**Decision:** бекенд не тягне URL; передає Telegram, що фетчить сам. Реального аплоаду файлів немає.
**Rationale:** зберігає інваріант «без SSRF», не тягне інфраструктуру зберігання. Прямо реалізує
US-обмеження «Медіа лише URL/file_id — бекенд не дереференсить URL → інваріант без SSRF збережено».
URL-поля валідуються строго як `http(s)` scheme (відхиляти `file://`/`data:`/`javascript:` тощо);
`file_id` приймається як непрозорий токен.
**Alternatives considered:** server-side upload/proxy (S3) — окремий пізніший крок.

### Decision 7: Скоуп блоків — text/image/video/audio/file/album
**Decision:** лише ці 6 типів; location/contact/invoice/receipt/delay/input/print/animation відкладено.
**Rationale:** invoice/receipt — територія Telegram Payments; delay/input уже існують як окремі
примітиви. Підтримує US «Типи блоків у цій фазі».
**Alternatives considered:** ширший набір — поза скоупом фази.

### Decision 8: Превʼю розширюється на мультиблок (backend-side render зберігається)
**Decision:** `/preview` приймає блоки і повертає масив відрендерених блоків; екранування за
`parseMode` лишається на бекенді, фронт рендерить text-only (`{{ }}`), медіа через `:src`, ніколи v-html.
**Rationale:** наявний контракт прибитий до одного рядка; XSS-guard (OWASP A03) і backend-екранування
треба зберегти. Підтримує US «Бекенд `/preview` контракт треба розширити на мультиблок».
**Alternatives considered:** рендерити blocks на фронті — дублює логіку екранування, ризик XSS.

## Data Models

**`ContentBlock`** (immutable record, embedded у `FunnelStep.blocks`):
```
record ContentBlock(
  BlockType type,            // TEXT|IMAGE|VIDEO|AUDIO|FILE|ALBUM
  String text,               // TEXT only
  String parseMode,          // TEXT/media caption: null|HTML|MarkdownV2
  String mediaUrl,           // IMAGE/VIDEO/AUDIO/FILE: URL або file_id
  String caption,            // IMAGE/VIDEO/AUDIO/FILE only
  List<MediaItem> items      // ALBUM only (2–10)
)
```
**`MediaItem`** (immutable record): `record MediaItem(String mediaUrl, String caption)` — `caption`
осмислений лише на першому елементі альбому.

**`BlockType`** enum: `TEXT, IMAGE, VIDEO, AUDIO, FILE, ALBUM`.

**`FunnelStep`** (зміни): видалити `text`, `parseMode`, `imageUrl`, `caption`; додати
`List<ContentBlock> blocks`. `buttons`, `timeoutValue/Unit/TargetStepId`, `id`, `next`, `order`,
`stepType` — без змін. `copyOf`: `blocks` копіюється захисно (`new ArrayList<>(...)`; елементи immutable).

**`StepType`**: `MESSAGE, DELAY, ADD_TAG, REMOVE_TAG, SET_CUSTOM_FIELD, EMIT_EVENT,
SUBSCRIBE_TO_FUNNEL` (видалено `SEND_MESSAGE`, `SEND_IMAGE`, `MENU`; додано `MESSAGE`).

**`FunnelStepDto`** (frontend `FunnelStep` дзеркалить): видалити плоскі msg-поля, додати
`List<ContentBlockDto> blocks`. **`FunnelResponse`/`FunnelSummaryResponse`**: провести `blocks` усюди,
де перелічувались плоскі msg-поля (summary може лишати блоки згорнутими, але не повинен посилатися на
видалені поля). **Preview DTOs**: `PreviewStepRequest(stepType, blocks)`,
`PreviewStepResponse(renderedBlocks[], sampleData, kind)`.

**Mongo:** жодних нових індексів/колекцій. `auto-index-creation=true` лишається. На деплої —
drop/clear `funnels` + `funnel_executions`.

## Dependencies

### New packages
- Немає. Усе на наявному стеку (Spring MVC, Jackson, OkHttp-клієнт `TelegramApiClient`, vee-validate+zod,
  shadcn-vue).

### Using existing (from project)
- `TelegramSender.send(...)` — спільний request/retry/429/audit/scrub шлях для нових sender-методів.
- `VariableTemplateRenderer` — підстановка `{user.*}`/`{custom.*}` + parseMode-екранування для text/caption.
- `buildReplyMarkup` / `parseCallbackData` / `advanceOnCallback` / `resumeOnCallback` — park-on-reply +
  branch для кнопок на останньому блоці.
- `SearchableSelect.vue` — комбобокс для target-крок/таймаут у формі блоків.
- `FunnelStepIdBackfill` — патерн НЕ застосовується (backfill не робимо; лишаємо для довідки).

## Testing Strategy

**Feature size:** L

### Unit tests
- `FunnelStepExecutorTest`: порядок блоків; підстановка змінних у text + **усіх** caption
  (text+image+video+audio+file — усі 1024 для медіа, 4096 для text); trim+WARN над лімітами для
  **кожного** caption-типу; збірка альбому (caption лише на першому елементі); клавіатура чіпляється
  лише до останнього не-альбомного блоку → `waitForReply`; без кнопок → `cont()`; **крах-семантика:
  send блоку K кидає `TelegramSendException` → гілка повертає `cancel`/`fail` за `fromTerminalReason`,
  крок не просувається**.
- `TelegramSenderTest` (MockWebServer): happy-path кожного нового методу
  (`sendVideo`/`sendAudio`/`sendDocument`/`sendMediaGroup`); успадковані retry/429; **масив-маппер**
  альбому (відповідь-масив → коректний результат) + його error/log-шлях проганяє raw-відповідь через
  `scrubTokens`; single-`message_id` маппер не ламається.
- `FunnelStep.copyOf` unit: `blocks` копіюється в **новий інстанс списку** (не той самий reference).
- `VariableTemplateRendererTest`: екранування в caption за parseMode (наявні тести зелені).
- Frontend vitest: редактор блоків (add/move/remove, per-type віджет), валідація-дзеркало
  (порожній/>10/альбом 2–10/неприпустиме змішування типів/кнопки на не-останньому), мультиблок-превʼю рендер.

### Integration tests
- `FunnelExecutionEngineIT` (реальна Mongo):
  - **per-node at-most-once (load-bearing):** крах посеред блоків → нода `in_progress`; на наступному
    sweep вже надіслані блоки **НЕ дослилаються** — `verify(sender, times(K))` через два тіки, а не лише
    перевірка статусу.
  - **snapshot-ізоляція (load-bearing):** enroll → **змінити `blocks` воронки після enroll** → in-flight
    ран усе одно шле оригінальну послідовність снапшоту.
  - **кнопки-на-останньому-блоці:** park + `resumeOnCallback` branch; `lastButtonClicked` зберігає
    координату `currentStepId:buttonIndex`; `funnel_button_clicked` фіксується **рівно один раз**;
    callback на виконання НЕ в стані `waiting_for_reply` (stale/duplicate webhook) ігнорується.
  - **наявні timeout-resume ITs лишаються зеленими** (паритет MENU).
- `FunnelTriggerService` callback-негативи (реальна Mongo): строгий парсинг callback_data (битий формат,
  >64 байти, не-hex executionId), out-of-range buttonIndex, не-callback кнопка (url), IDOR
  (cross-subscriber + cross-project) — кожен відхиляється, виконання не просувається.
- `FunnelControllerIT`: save-валідація (порожній композер, >10 блоків, альбом <2/>10, неприпустиме
  змішування типів, кнопки на не-останньому/альбомному блоці, не-`http(s)` медіа-URL → 422 з
  інлайн-кодами; over-length → позначено, не блокує). **DTO round-trip:** save→fetch повертає `blocks`
  без втрати жодного поля (захист від мовчазного дропу в `toSteps`/`toStepDto`/`FunnelResponse`).
  Preview мультиблок: ендпойнт повертає масив відрендерених блоків з екрануванням; **XSS-негатив** —
  ворожий payload у text/caption екранується за `parseMode` (OWASP A03, Decision 8), у відповіді немає
  неекранованої розмітки.
- **Регресія:** наявні funnel-ITs (Phases 1–5), що використовували SEND_MESSAGE/SEND_IMAGE/MENU,
  переписані на композер і зелені. Fail-safe: legacy old-type документ, що пережив ручний wipe, не
  крешить engine на старті (tolerant-read).

### E2E tests
- None — E2E-набору для воронок немає; golden-path рушія покривають engine ITs. Реальну доставку в
  Telegram перевіряє користувач через «Test for me» (post-deploy).

## Agent Verification Plan

**Source:** user-spec "Как проверить" section.

### Verification approach
Під час реалізації — per-task Verify-smoke (curl проти live funnel API для save-валідації; gradle/pnpm
тести). Перед деплоєм — pre-deploy QA проганяє повний сюїт + перевіряє AC. Після деплою — користувач
будує мультиблок-композер і проганяє «Test for me» у власному Telegram (єдина перевірка реальної
доставки поза автотестами): послідовність повідомлень у правильному порядку + натискання кнопки на
останньому блоці → правильна гілка.

### Tools required
curl, bash (gradle/pnpm) — обовʼязкові. Telegram MCP — недоступний; реальна доставка перевіряється
користувачем через in-app «Test for me». Playwright MCP — опційно для рендеру редактора/превʼю.

## Risks

| Risk | Mitigation |
|------|-----------|
| Blast radius від видалення старих типів (всі місця з плоскими полями + наявні funnel-ITs) | Композер перекриває видалену поведінку; різати на послідовні таски; existing-test-suite як сітка безпеки; ITs переписуються на композер у відповідних тасках |
| Частковa доставка ноди (крах посеред блоків) | Свідомо приймаємо per-node at-most-once; документуємо як відоме обмеження; per-block — поза скоупом |
| Альбом ламає спільний send-шлях (`sendMediaGroup` → масив) | Окремий result-маппер лише для альбому; решта senders переюзають спільний `send(...)` |
| Сплеск 429 від N послідовних send-ів | Наявний per-send 429-loop; inter-block pacing додамо лише за реальної потреби |
| Втрата наявних воронок при ручному wipe | Виконати лише на середовищі без проду; зафіксувати як крок деплою |
| XSS у мультиблок-превʼю | Backend-side parseMode-екранування зберігається; фронт рендерить text-only (`{{ }}`), медіа через `:src`, ніколи v-html |
| Callback-контракт ламається при переносі клавіатури на блок | Кнопки лишаються полями кроку (Decision 2); index-addressing незмінний |

## User-Spec Deviations

None — tech-spec повністю реалізує user-spec. Усі технічні рішення (модель ContentBlock, кнопки на
рівні кроку, назва `MESSAGE`, єдина фаза без MVP/Extension-розбивки) узгоджені з користувачем у фазі
clarification і не змінюють жодної вимоги user-spec.

## Acceptance Criteria

Технічні критерії приёмки (дополняют пользовательские из user-spec):

- [ ] Save/activate повертають 422 з інлайн-бізнес-кодами на всіх жорстких помилках (порожній композер,
      >10 блоків, альбом <2/>10, неприпустиме змішування типів, кнопки на не-останньому/альбомному блоці,
      биті edge-таргети); over-length text/caption позначається, але не блокує save.
- [ ] `StepType` більше не містить `SEND_MESSAGE`/`SEND_IMAGE`/`MENU`; обидва exhaustive-switch
      (`StepExecutor`, `validateSteps`) компілюються без них.
- [ ] `FunnelStep.copyOf` глибоко копіює `blocks`; snapshot-ізоляція доведена IT.
- [ ] Нові методи `TelegramSender` успадковують retry/429/audit/scrub; альбом мапить масив-відповідь.
- [ ] Усі наявні тести зелені після видалення старих типів (немає регресій; ITs переписані на композер).
- [ ] Превʼю-ендпойнт повертає масив відрендерених блоків з backend-side екрануванням; фронт не
      використовує v-html.
- [ ] Callback wire-формат `{executionId}:{buttonIndex}` незмінний; `buttonIndex` 0-based мапиться на
      кнопки останнього блоку; `lastButtonClicked` зберігає `currentStepId:buttonIndex`.

## Implementation Tasks

### Wave 1 (незалежні — backend foundation)

#### Task 1: ContentBlock модель + StepType + FunnelStep рефактор
- **Description:** Ввести immutable `ContentBlock`/`MediaItem` records і `BlockType` enum; замінити плоскі
  msg-поля `FunnelStep` на `List<ContentBlock> blocks` (зберегти `buttons`/`timeout*`); оновити `copyOf`
  для захисної копії `blocks`; у `StepType` видалити `SEND_MESSAGE`/`SEND_IMAGE`/`MENU`, додати `MESSAGE`;
  оновити `FunnelExecutionFactory.deepCopySteps`. Результат: модель компілюється, snapshot-копія валідна.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/FunnelStep.java`, `backend/src/main/java/com/botfunnel/funnel/StepType.java`, `backend/src/main/java/com/botfunnel/funnel/FunnelExecutionFactory.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/funnel/Button.java`, `backend/src/main/java/com/botfunnel/funnel/FunnelExecution.java`

#### Task 2: TelegramSender — нові sender-методи + альбом-маппер
- **Description:** Додати `sendVideo`/`sendAudio`/`sendDocument` як тонкі обгортки над спільним `send(...)`
  (правильні endpoint + contentFields) і `sendMediaGroup` з окремим маппером масив-відповіді; error/log-шлях
  альбом-маппера має проганяти raw-відповідь через `scrubTokens` (паритет зі спільним шляхом). Результат:
  кожен метод успадковує retry/429/audit/scrub; альбом коректно мапить масив `Message`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew test --tests '*TelegramSenderTest*'` → нові happy-path + альбом-маппер зелені
- **Files to modify:** `backend/src/main/java/com/botfunnel/bot/TelegramSender.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/bot/dto/SentMessage.java`, `backend/src/main/java/com/botfunnel/bot/TelegramApiClient.java`, `backend/src/test/java/com/botfunnel/bot/TelegramSenderTest.java`

### Wave 2 (залежить від Wave 1)

#### Task 3: StepExecutor — гілка MESSAGE (мультиблок send + клавіатура на останньому)
- **Description:** Реалізувати гілку `MESSAGE`: послідовно слати блоки, рендерити text+усі caption через
  `VariableTemplateRenderer`, trim+WARN над лімітами, збирати альбом; до останнього не-альбомного блоку
  чіпляти клавіатуру (`buildReplyMarkup`) → `waitForReply(deadline)`, без кнопок → `cont()`; зберегти
  per-node at-most-once (всі send-и під одним claim). Зберегти наявні catch-arms видалених гілок:
  `BotTokenInvalidException` → `fail("invalid_bot_token")`, `AppException` → `fail(codeOrStatus(ex))`,
  `TelegramSendException` → `fromTerminalReason`. Видалити гілки `sendMessage`/`sendImage`/`menu`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/StepExecutor.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/funnel/VariableTemplateRenderer.java`, `backend/src/main/java/com/botfunnel/bot/TelegramSender.java`, `backend/src/test/java/com/botfunnel/funnel/FunnelStepExecutorTest.java`

#### Task 4: FunnelService — validateSteps композер + DTO-ланцюг + preview мультиблок
- **Description:** Додати case `MESSAGE` у `validateSteps` (кількість блоків 1–10, per-type поля, альбом
  2–10 і type-mixing предикат, кнопки лише на останньому не-альбомному, over-length як warning, строгий
  `http(s)`-scheme для медіа-URL з disambiguation `file_id` vs URL) з інлайн 422-кодами; видалити старі
  cases. Провести `List<ContentBlock>` через `FunnelStepDto`, `toSteps`/`toStepDto`,
  `FunnelResponse`/`FunnelSummaryResponse`. Розширити `previewStep` + preview DTOs на масив блоків
  (`isMessageStep` → `MESSAGE`, backend-side parseMode-екранування, без серверного дереференсу медіа).
  Об'єднано в один таск, бо все живе у `FunnelService.java` (уникнення wave-конфлікту).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `curl -X PUT` (funnel update) з 0 блоків і з 11 блоків → 422 з інлайн-кодом в обох
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/FunnelService.java`, `backend/src/main/java/com/botfunnel/funnel/dto/FunnelStepDto.java`, `backend/src/main/java/com/botfunnel/funnel/dto/FunnelResponse.java`, `backend/src/main/java/com/botfunnel/funnel/dto/FunnelSummaryResponse.java`, `backend/src/main/java/com/botfunnel/funnel/dto/PreviewStepRequest.java`, `backend/src/main/java/com/botfunnel/funnel/dto/PreviewStepResponse.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/funnel/FunnelController.java`, `backend/src/main/java/com/botfunnel/funnel/dto/ButtonDto.java`, `backend/src/main/java/com/botfunnel/funnel/VariableTemplateRenderer.java`

#### Task 5: Callback-шлях — клавіатура з композер-кроку
- **Description:** Оновити `advanceOnCallback`: **замінити guard «cursor must point at a MENU step» на
  позитивний guard `stepType == MESSAGE`** і резолвити кнопки з останнього не-альбомного блоку композера
  замість MENU-полів, зберігши строгий парсинг `{executionId}:{buttonIndex}`, IDOR/owner-перевірки,
  range-check індексу, `recordButtonClick` з координатою `currentStepId:buttonIndex`. Результат:
  park+branch працює на композері; callback-контракт незмінний; parked-MESSAGE callbacks не відхиляються.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/FunnelTriggerServiceImpl.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/funnel/FunnelExecutionEngine.java`, `backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java`

### Wave 3 (frontend types — залежить від backend DTO-контракту)

#### Task 6: Frontend types — StepType + ContentBlock + preview DTOs
- **Description:** Оновити `types/funnel.ts`: видалити `SEND_MESSAGE`/`SEND_IMAGE`/`MENU` зі `StepType`,
  додати `MESSAGE`; ввести `ContentBlock`/`BlockType`/`MediaItem` інтерфейси (дзеркало бекенду); оновити
  `PreviewStepRequest`/`PreviewStepResponse` під мультиблок; `FunnelStep.blocks`. Результат: типи
  компілюються, дзеркалять DTO.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Files to modify:** `frontend/types/funnel.ts`
- **Files to read:** `frontend/stores/funnels.ts`

### Wave 4 (frontend UI — залежить від Wave 3 типів)

#### Task 7: FunnelStepForm — саб-редактор композера
- **Description:** Замінити per-type гілки на саб-редактор композера: список блоків (add через picker
  типу, move up/down, remove), per-type віджет (Text: текст+parseMode; Image/Video/Audio/File:
  URL/file_id+caption+parseMode; Album: 2–10 елементів, caption на першому), кнопки+таймаут на останньому
  не-альбомному блоці. Клієнт-валідація дзеркалить backend `validateSteps` (вкл. type-mixing). Усі рядки
  через `t()`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-user:** open funnel editor (≥1024px) → додати композер-крок, зібрати text→image+caption→album×3,
  причепити кнопки до text-хвоста → форма валідна, save проходить
- **Files to modify:** `frontend/components/funnels/FunnelStepForm.vue`, `frontend/i18n/locales/uk.json`, `frontend/i18n/locales/en.json`
- **Files to read:** `frontend/components/funnels/SearchableSelect.vue`, `frontend/pages/projects/[projectId]/funnels/[funnelId].vue`

#### Task 8: FunnelMessagePreview — мультиблок-рендер + store preview action
- **Description:** Рендерити впорядкований стос різнотипних блоків як у Telegram: text/caption через
  `{{ }}` (ніколи v-html), медіа через `:src`/іконку типу (FILE/document `:href` — лише за
  `http(s)`-scheme), альбом як сітку, кнопки під останнім блоком. Оновити `preview` action у
  `stores/funnels.ts` під новий request-shape (масив блоків) + дебаунс-виклик. Результат: превʼю
  відповідає порядку й типам блоків.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** open funnel editor → редагуючи композер, превʼю праворуч показує блоки стосом у
  правильному порядку
- **Files to modify:** `frontend/components/funnels/FunnelMessagePreview.vue`, `frontend/stores/funnels.ts`
- **Files to read:** `frontend/types/funnel.ts`

### Audit Wave

#### Task 9: Code Audit
- **Description:** Full-feature code quality audit. Read all source files created/modified in this feature
  (from decisions.md + tech-spec "Files to modify"). Review holistically for cross-component issues:
  duplicate resource initialization, shared resources compliance with Architecture decisions, architectural
  consistency, removal completeness of old step types. Write audit report.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 10: Security Audit
- **Description:** Full-feature security audit. Read all source files created/modified in this feature.
  Analyze for OWASP Top 10 across all components: anti-SSRF (no media dereference), XSS in preview
  (parseMode escaping, no v-html), callback IDOR/owner checks, token scrubbing in new sender methods.
  Write audit report.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 11: Test Audit
- **Description:** Full-feature test quality audit. Read all test files created/modified in this feature.
  Verify coverage (composer executor, new senders + album mapper, validation, callback branch,
  snapshot-isolation IT, rewritten Phase 1–5 ITs), meaningful assertions, test pyramid balance. Write
  audit report.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 12: Pre-deploy QA
- **Description:** Acceptance testing: run full backend suite (`./gradlew test`) + frontend (`pnpm test`),
  verify all acceptance criteria from user-spec and tech-spec, confirm no regressions after old-type
  removal. Report deferred live checks (Telegram delivery) for post-deploy.
- **Skill:** pre-deploy-qa
- **Reviewers:** none

#### Task 13: Deploy + ручне очищення даних
- **Description:** Deploy via GitHub CI/CD; як крок деплою вручну очистити `funnels` + `funnel_executions`
  (продакшну немає, backfill не робимо). Verify logs після старту (engine sweep піднявся, без помилок
  серіалізації снапшотів).
- **Skill:** deploy-pipeline
- **Reviewers:** none

#### Task 14: Post-deploy verification
- **Description:** Live verification:
  - Користувач: «Test for me» (Phase 4 test-run) на власному Telegram — побудувати мультиблок-композер
    (text→image+caption→album×3 + кнопки на text-хвості), отримати послідовність повідомлень у правильному
    порядку, натиснути кнопку на останньому блоці → перевірити правильну гілку — tool: user (in-app Test for me)
  - Агент: curl save/activate валідного композера на live → 200, воронка `active` — tool: curl
  Tools: curl, bash; реальна доставка — user-driven (Telegram MCP недоступний).
- **Skill:** post-deploy-qa
- **Reviewers:** none
