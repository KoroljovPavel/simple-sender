---
created: 2026-06-08
status: approved
branch: dev
size: L
---

# Tech Spec: Воронки. Фаза 5 — композиція воронок (14-funnels-composition)

## Solution

Додаємо один новий тип кроку `SUBSCRIBE_TO_FUNNEL` поверх готового рушія Фаз 1-4. Крок fire-and-forget: enroll-ить того самого підписника в іншу воронку проєкту і повертає `CONTINUE` (або завершує батька, якщо `endParentAfter=true`). Jump/transfer/повернення-на-меню — це патерни поверх одного кроку, нової машини станів немає.

Крок несе три нові поля на вбудованому `FunnelStep`: `targetFunnelId`, `targetEntryStepId` (опційно), `endParentAfter` (boolean). Жодного нового `ExecutionStatus`, жодної міграції БД, жодного нового park/resume.

Enroll виконується через **новий non-matching метод `FunnelEventService.enrollSpecificFunnel(...)`**, який пропускає матчинг тригерів (ціль задана явно), але застосовує ті самі per-subscriber бекстопи Фази 3 (depth-cap + auto-enroll rate-limit) перед викликом фабрики. Це безкоштовно успадковує захист від розгону циклів `A→B→A`.

Фабрика `FunnelExecutionFactory` отримує overload `insertExecutionAt(..., String startStepId)`, бо чинний `insertExecution` жорстко стартує з кроку 0, а child має стартувати з `targetEntryStepId`.

Для `endParentAfter` рушій отримує новий `Outcome.COMPLETE`, який завершує батьківську execution статусом `completed` (через наявний `complete(exec, now)`), не виконуючи наступні кроки.

Save-time валідація перевіряє існування цілі + той самий проєкт + існування кроку входу (НЕ `active`). Активація батьківської воронки блокується, якщо ціль ще не `active`. На рантаймі неактивна/видалена ціль → тихий skip + WARN, батько продовжує (snapshot-ізоляція).

Фронт: новий блок у `FunnelStepForm.vue` з пікером воронки (`SearchableSelect`) і пікером кроку входу (lazy-fetch цільової воронки через `fetchOne`), прапорцем «завершити», inline-підказкою про не-`active` ціль.

## Architecture

### What we're building/modifying

**Backend (модуль `com.botfunnel.funnel`):**
- **`StepType`** — додати константу `SUBSCRIBE_TO_FUNNEL` (exhaustive switch'і змусять заповнити всі гілки).
- **`FunnelStep`** — три immutable-поля `targetFunnelId`, `targetEntryStepId`, `endParentAfter` + копіювання в `copyOf`.
- **`FunnelStepDto`** — ті самі поля; маппінг у `FunnelService.toSteps`/`toStepDto`.
- **`FunnelService.validateSteps`** — нова гілка SUBSCRIBE з DB-lookup цілі (перша валідація, що торкається БД), нові 422-коди; крок свідомо виключений з generic edge-pass.
- **`FunnelService` (активація)** — блок активації батька з не-`active` ціллю (`funnel_subscribe_target_inactive`).
- **`FunnelExecutionFactory`** — overload `insertExecutionAt(..., String startStepId)` (старт з довільного кроку, fallback на крок 0).
- **`FunnelEventService.enrollSpecificFunnel(...)`** — новий non-matching enroll: depth-cap + rate-limit + резолв цілі (fail-closed по `projectId`) + re-enter + fallback кроку входу → виклик фабрики.
- **`StepExecutor`** — новий case SUBSCRIBE; інжект `FunnelEventService` уже є; виклик `enrollSpecificFunnel(parent.depth+1)`; `endParentAfter ? COMPLETE : CONTINUE`.
- **`FunnelExecutionEngine`** — новий `Outcome.COMPLETE` → `complete(exec, now)`.

**Frontend:**
- **`types/funnel.ts`** — `StepType` union + поля на `FunnelStep`.
- **`components/funnels/FunnelStepForm.vue`** — `STEP_TYPES`, новий template-блок, submit-case.
- **`i18n/locales/{uk,en}.json`** — лейбли типу/полів + `errors.funnels.funnel_subscribe_*`.

### How it works

**Збереження кроку (автор):** форма емітить `{stepType:'SUBSCRIBE_TO_FUNNEL', targetFunnelId, targetEntryStepId, endParentAfter}` → `FunnelService.validateSteps` робить DB-lookup цілі в межах `projectId`, перевіряє існування цілі та кроку входу (НЕ `active`) → 422-коди при помилці.

**Активація батька:** перевіряє, що кожна SUBSCRIBE-ціль `active` → інакше `funnel_subscribe_target_inactive` (422).

**Виконання (`StepExecutor` → `enrollSpecificFunnel`):**
1. `originDepth = parent.enrollDepth + 1`. Якщо `originDepth > maxEnrollDepth` → skip + greppable-WARN, повертаємо так, ніби enroll не стався.
2. Auto-enroll rate-limit (Redis, як у dispatcher, рахує бо `originDepth > 0`); перевищення → skip + WARN.
3. Резолв цільової воронки через `FunnelRepository.findById` (НЕ project-scoped на рівні БД), потім **явна fail-closed перевірка** `targetFunnel.getProjectId().equals(execution.getProjectId())` — це головний IDOR-guard. Malformed `ObjectId` / відсутня / інший проєкт / `draft`/`paused` → skip + greppable-WARN (окремий id на причину), батько продовжує. Лог — лише id-и (без user-input у повідомленні, проти log-injection).
4. Резолв `targetEntryStepId` у snapshot цілі; немає → fallback на крок 0 + WARN.
5. Re-enter: `allowReEnter=true` → cancel-then-insert; `allowReEnter=false` → `insertExecutionAt` у try, `DuplicateKeyException` → no-op + окремий greppable-WARN.
6. `insertExecutionAt(projectId, target, subscriberId, parent.telegramBotId, originDepth, startStepId)` → child з `currentStepId = startStepId`, `enrollDepth = originDepth`.

**Батько після enroll:** `endParentAfter=false` → `CONTINUE` (advance до наступного кроку, паралельний хід). `endParentAfter=true` → `COMPLETE` → `complete(exec, now)`, наступні кроки не виконуються.

**At-most-once:** enroll це side-effect перед claim-conditional advance/complete (як EMIT_EVENT) — повтор кроку при crash не дублює send, лише повторний enroll, який re-enter guard зробить no-op.

**Self-target (A→A):** не заборонений у редакторі, але впирається в re-enter guard тієї ж воронки — no-op без `allowReEnter`, перезапуск з кроку входу при `allowReEnter`. Не основний механізм циклів (для циклу — `A→B→A`); поведінка випливає автоматично з re-enter guard, окремої гілки коду не потребує.

### Shared resources

| Resource | Owner (creates) | Consumers | Instance count |
|----------|----------------|-----------|----------------|
| `FunnelExecutionFactory` | Spring DI | `FunnelEventService`, `FunnelTriggerServiceImpl`, `FunnelService.testRun` | 1 (singleton bean) |
| Redis (auto-enroll rate-limit key `bf:rate:auto-enroll:{subscriberId}`) | infra | `FunnelEventService` (dispatch + `enrollSpecificFunnel`) | 1 (shared) |
| `telegramBotId` (один CONNECTED бот на проєкт) | resolved at enroll | child execution | переюз від батька (без нового lookup) |

## Decisions

### Decision 1: Один крок `SUBSCRIBE_TO_FUNNEL`, патерни — поверх нього
**Decision:** Один fire-and-forget крок; jump/transfer/повернення-на-меню — патерни конфігурації, не окремі типи кроків.
**Rationale:** Покриває реальний кейс автора (виклик під-воронки + повернення на меню) мінімумом коду. Підтримує AC «новий тип кроку», US «Сценарій композиції».
**Alternatives considered:** Окремі типи JUMP/CALL/RETURN — більше коду, не потрібні авторові.

### Decision 2: Enroll через `FunnelEventService.enrollSpecificFunnel`, не через фабрику напряму
**Decision:** Новий non-matching метод на `FunnelEventService`, що застосовує depth-cap + rate-limit перед фабрикою.
**Rationale:** Безкоштовно успадковує **обидва** per-subscriber бекстопи Фази 3 (US «Захист від розгону», Ризик 1). Direct-factory виклик дав би лише re-enter guard, але не rate-limit. Ребро `StepExecutor → FunnelEventService` уже існує → bean-цикл не виникає (code-research §14.6).
**Alternatives considered:** Інжект `FunnelExecutionFactory` у `StepExecutor` (code-research рекомендує як «чистіше») — відкинуто, бо не дає rate-limit, а user-spec явно вимагає обидва бекстопи.

### Decision 3: `Outcome.COMPLETE` для `endParentAfter`
**Decision:** Додати новий `Outcome.COMPLETE`; рушій завершує батька через наявний `complete(exec, now)`.
**Rationale:** Чинний рушій завершує `completed` лише через end-of-graph (`FunnelExecutionEngine.java:247`). `endParentAfter=true` має завершити батька з будь-якої позиції, не лише з останнього кроку. Підтримує AC «завершити=так → completed, кроки після не виконуються». `CANCEL` дав би статус `cancelled` (семантично невірно). [TECHNICAL] — мінімальна гілка в exhaustive switch.
**Alternatives considered:** Зсунути cursor на end-of-graph + `CONTINUE` — крихко, залежить від позиції кроку; `CANCEL` — невірний статус.

### Decision 4: `insertExecutionAt(... startStepId)` overload фабрики
**Decision:** Новий метод-overload, що стартує child з довільного `startStepId` (fallback на крок 0 при null).
**Rationale:** Чинний `insertExecution` жорстко ставить `currentStepId = snapshot.get(0)` (`FunnelExecutionFactory.java:93`). Child SUBSCRIBE має стартувати з `targetEntryStepId`. Підтримує AC «currentStepId = крок входу (або старт)» і «повернення на меню M». [TECHNICAL].
**Alternatives considered:** Параметр на наявному методі — зачепив би 3 чинні callsites зайвим аргументом; overload ізолює зміну.

### Decision 5: Поле `targetEntryStepId`, не `targetStepId`
**Decision:** Назвати поле кроку входу `targetEntryStepId`.
**Rationale:** Уникнути плутанини з наявним `Button.targetStepId` (intra-funnel MENU-таргет). Підтримує US «Технічні рішення». Cross-funnel посилання — завжди пара `(targetFunnelId, targetEntryStepId)`, бо id кроків унікальні лише в межах воронки (code-research §6).
**Alternatives considered:** `targetStepId` — колізія з `Button.targetStepId` + generic edge-pass хибно підняв би `funnel_broken_edge`.

### Decision 6: `active`-перевірка цілі на виконанні + активації, не при збереженні
**Decision:** Save валідує лише існування + той самий проєкт + існування кроку входу. `active` НЕ перевіряється при save; перевіряється при **активації** батька (`funnel_subscribe_target_inactive`) і на рантаймі (тихий skip).
**Rationale:** Дозволяє посилатись на ще-`draft` ціль при побудові двох зв'язаних воронок (курка-яйце). Підтримує AC «`active` при збереженні НЕ перевіряється, активація блокується». Рознесення «помітити голосно» (save/activate/test → 422) і «не зламати прод» (runtime → skip+WARN, snapshot-ізоляція).
**Alternatives considered:** `active` при save — блокує two-funnel authoring.

### Decision 7: Неактивна/видалена ціль на рантаймі → тихий skip + WARN
**Decision:** Ціль `draft`/`paused`/видалена при виконанні → `enrollSpecificFunnel` нічого не enroll-ить, лог greppable-WARN, `StepExecutor` повертає так, ніби enroll не стався (батько йде далі). Видалений крок входу → fallback на старт + WARN.
**Rationale:** Дзеркалить precedent видаленого custom-field (`StepExecutor.setCustomField` → `cont()` + WARN). Видалення цілі не валить live-проходження інших воронок. Підтримує AC «крок тихо пропущено + WARN, батько продовжує».
**Alternatives considered:** `fail` батька — каскадні падіння при видаленні цілі.

### Decision 8: Без static cycle-detection; розгін стримує depth-cap + rate-limit
**Decision:** Цикли `A→B→A` дозволені; не детектимо їх при збереженні.
**Rationale:** Цикли — фіча (повернення на меню). Per-tick step budget НЕ обмежує cross-execution цикл (child — окремий рядок для sweep, code-research §14.8), тож обмежує саме depth-cap (`enrollDepth`) + auto-enroll rate-limit. Підтримує US «Цикли», Ризик 1.
**Alternatives considered:** Граф-аналіз циклів при save — складно, суперечить наміру автора.

### Decision 9: Переюз `telegramBotId` батька
**Decision:** Child успадковує `execution.getTelegramBotId()` без окремого пошуку бота.
**Rationale:** Один CONNECTED бот на проєкт (codebase припускає всюди). Економить lookup. Підтримує AC «telegramBotId успадковано від батька».

## Data Models

Нові опційні поля на вбудованому `FunnelStep` (POJO, flat-per-type, без `_class`, без міграції):

```
FunnelStep {
  // ... наявні
  String  targetFunnelId      // ціль SUBSCRIBE_TO_FUNNEL (immutable, copyOf by-ref)
  String  targetEntryStepId   // опц. крок входу в цілі; null → старт цілі
  boolean endParentAfter      // true → батько completed одразу після enroll
}
```

Дзеркало на `FunnelStepDto` (record) і `frontend/types/funnel.ts` (`targetFunnelId?`, `targetEntryStepId?`, `endParentAfter?`). Жодних змін індексів, схеми чи бекфілу. Cross-funnel посилання зберігається як пара `(targetFunnelId, targetEntryStepId)`.

Нові error-коди (`FunnelService` константи): `funnel_subscribe_target_required`, `funnel_subscribe_target_not_found`, `funnel_subscribe_target_step_not_found`, `funnel_subscribe_target_inactive` (лише при активації).

## Dependencies

### New packages
- None.

### Using existing (from project)
- `FunnelExecutionFactory` — enroll через новий `insertExecutionAt`.
- `FunnelEventService` — депт-кап + rate-limit (Redis) у новому `enrollSpecificFunnel`.
- `app.funnel.max-enroll-depth` (=10), `app.funnel.auto-enroll-rate-per-min` (=20) — наявні props, без нових.
- `SearchableSelect.vue`, `useFunnelsStore().fetch('active')` + `fetchOne(id)` — для пікерів форми.
- `resolveFunnelError` (i18n `errors.funnels.{code}`) — для inline-кодів.

## Testing Strategy

**Feature size:** L

### Unit tests
- `StepTypeTest` — пін enum-членів включно з `SUBSCRIBE_TO_FUNNEL`.
- `FunnelStepExecutorTest` — SUBSCRIBE повертає `cont()` (endParentAfter=false) / `complete()` (true); виклик `enrollSpecificFunnel` з `parent.depth+1`.
- `FunnelService` валідація — кожен новий код: `target_required`, `target_not_found` (інший проєкт / відсутня), `target_step_not_found`; активація з не-`active` ціллю → `target_inactive`; SUBSCRIBE не зачіпає generic edge-pass.
- `FunnelService.previewStep`/`isMessageStep` — SUBSCRIBE → `non_message` placeholder (assert, не входить в `isMessageStep`).
- Frontend (vitest) — форма рендерить пікер воронки, пікер кроку входу, прапорець; submit формує `{stepType, targetFunnelId, targetEntryStepId, endParentAfter}`; inline-підказка про не-`active` ціль.

### Integration tests
Engine-IT у стилі `FunnelExecutionEngineIT` (`MutableClock` + `MockWebServer` + `engine.sweep()`):
- enroll у ціль: нова execution + підписник, `currentStepId = targetEntryStepId`, `enrollDepth = батько+1`, **`telegramBotId` успадковано від батька** (assert).
- `endParentAfter=так` → батько `completed`, наступні кроки не виконуються.
- `endParentAfter=ні` → батько продовжує, обидві воронки йдуть паралельно.
- повернення на меню: `B → SUBSCRIBE(A, entry=M, end=так)` приземляє на крок M воронки A.
- depth-cap: цикл `A→B→A` понад `max-enroll-depth` → enroll skip + WARN, батько не `failed`.
- **rate-limit backstop**: понад `auto-enroll-rate-per-min` enroll'ів за хвилину від одного підписника → наступний enroll skip + WARN, батько не `failed` (другий per-subscriber бекстоп, Ризик 1 — окремий від depth-cap).
- ціль `draft`/`paused`/видалена / інший проєкт на рантаймі → skip + greppable-WARN, батько продовжує.
- видалений крок входу → fallback на старт + WARN.
- re-enter: ціль у процесі + `allowReEnter=false` → no-op (без падіння) + **окремий greppable-WARN** (assert лог); `allowReEnter=true` → перезапуск з кроку входу.
- self-target (A→A): `allowReEnter=false` → no-op; `allowReEnter=true` → перезапуск.
- **test-run шлях** («Test for me», стиль `FunnelTestRunSendIT`): test-run батька з SUBSCRIBE-кроком (depth-0 root) enroll-ить ціль; нова execution цілі з `enrollDepth=батько+1`; child-send спостерігається наступним sweep'ом (HTTP-відповідь підтверджує лише enroll батька).

### E2E tests
- None — фіча backend-центрична, редактор на переюзі `SearchableSelect`; frontend покрито vitest. Наскрізний Telegram-прогін — ручний (user-check, потребує тунелю).

## Agent Verification Plan

**Source:** user-spec "Как проверить".

### Verification approach
Автоматичні тести (unit + engine-IT + vitest) покривають усі AC, що відтворюються без живого Telegram. Per-task smoke-чеки — у полях Verify-smoke. Наскрізний Telegram-сценарій (`A → B → меню A`) автор перевіряє вручну локально (інлайн-кнопки приходять webhook'ом, без тунелю не автоматизується) — описано в Verify-user фінальних задач.

### Tools required
bash (`./gradlew test`, `npm run test`), curl (опц. — funnel save/activate/test-run 422-коди). Playwright/Telegram MCP — не потрібні (наскрізний прогін ручний).

## Risks

| Risk | Mitigation |
|------|-----------|
| Нескінченний цикл `A↔B` (cross-execution, не ловить per-tick budget) | enroll через `enrollSpecificFunnel` з `enrollDepth = батько+1` + depth-cap + auto-enroll rate-limit перед фабрикою; перевищення → skip+WARN, батько не падає |
| Повернення стає no-op (батько ще живий + `allowReEnter=false`) | рекомендований патерн `endParentAfter=так` (батько завершується до повернення); inline-підказка в редакторі про режим/`allowReEnter` |
| Застаріле cross-funnel посилання | зберігати пару `(targetFunnelId, targetEntryStepId)`; голосно при save/activate/test (422), тихо при рантаймі (skip+WARN, snapshot-ізоляція); видалений крок входу → fallback на старт |
| Витік між тенантами | резолв цілі строго в межах `execution.getProjectId()` (fail-closed) — і при save, і при виконанні |
| Bean-цикл | enroll через наявне ребро `StepExecutor → FunnelEventService` (новий метод); НЕ інжектити engine/`FunnelService` у `StepExecutor` |
| Фабрика стартує лише з кроку 0 | overload `insertExecutionAt(... startStepId)`; валідація `targetEntryStepId` у snapshot, fallback на крок 0 при відсутності |

## User-Spec Deviations

None.

<!-- enrollSpecificFunnel замість direct-factory — це НЕ deviation: user-spec явно обрав цей шлях (Технічні рішення, Обмеження). code-research лише пропонував альтернативу. Outcome.COMPLETE та insertExecutionAt — технічні засоби реалізації AC «завершити=так» і «currentStepId=крок входу», не зміни вимог. -->

## Acceptance Criteria

Технічні критерії приёмки (дополняют пользовательские из user-spec):

- [ ] Усі нові unit + engine-IT + vitest зелені, без регресій у наявних тестах funnel-модуля.
- [ ] Exhaustive switch'і (`StepExecutor`, `FunnelService.validateSteps`) компілюються з новим `StepType`.
- [ ] `StepType` enum-пін у `StepTypeTest` оновлено.
- [ ] Жодної міграції БД / нового `ExecutionStatus` / нового `status $in` site.
- [ ] 422-коди валідації відповідають user-spec (`funnel_subscribe_*`).
- [ ] `enrollSpecificFunnel` застосовує depth-cap + rate-limit перед фабрикою (не дублює бекстопи).
- [ ] Резолв цілі fail-closed по `projectId` і при save, і при виконанні.

## Implementation Tasks

### Wave 1 (foundation)

#### Task 1: Backend data model для SUBSCRIBE_TO_FUNNEL
- **Description:** Додати тип кроку `SUBSCRIBE_TO_FUNNEL` і його дані на вбудовану модель. Потрібно як фундамент для валідації, рушія й фронту. Результат: enum-константа, поля `targetFunnelId`/`targetEntryStepId`/`endParentAfter` на `FunnelStep` (+ копіювання в `copyOf`), дзеркало на `FunnelStepDto` з маппінгом `toSteps`/`toStepDto`, `isMessageStep` НЕ включає новий тип (падає в `non_message` placeholder), оновлений enum-пін у `StepTypeTest`. Поле кроку входу зветься `targetEntryStepId` (code-research місцями пише `targetStepId` — використовувати `targetEntryStepId`, Decision 5).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/StepType.java`, `backend/src/main/java/com/botfunnel/funnel/FunnelStep.java`, `backend/src/main/java/com/botfunnel/funnel/dto/FunnelStepDto.java`, `backend/src/main/java/com/botfunnel/funnel/FunnelService.java`, `backend/src/test/java/com/botfunnel/funnel/StepTypeTest.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/funnel/StepExecutor.java`, `work/14-funnels-composition/code-research.md`

### Wave 2 (depend on Task 1, run in parallel)

#### Task 2: Save + activation валідація цілі
- **Description:** Додати гілку SUBSCRIBE у `FunnelService.validateSteps` з DB-lookup цільової воронки (fail-closed по `projectId`): ціль вказана/існує/той самий проєкт, крок входу існує в цілі (НЕ `active`). Заблокувати активацію батька з не-`active` ціллю. Результат: нові 422-коди `funnel_subscribe_target_required`/`_not_found`/`_step_not_found`/`_inactive`; SUBSCRIBE виключений з generic edge-pass (`requireExistingTarget`).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `curl -X POST` на funnel-save з поганою ціллю → 422 з відповідним `funnel_subscribe_*` кодом; активація з `draft`-ціллю → 422 `funnel_subscribe_target_inactive`
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/FunnelService.java`, `backend/src/test/java/com/botfunnel/funnel/FunnelServiceEmitEventTest.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/funnel/FunnelRepository.java`, `work/14-funnels-composition/code-research.md`

#### Task 3: Engine enroll-шлях (ядро фічі)
- **Description:** Реалізувати виконання кроку: overload `FunnelExecutionFactory.insertExecutionAt(... startStepId)` (старт з довільного кроку, fallback крок 0); новий `FunnelEventService.enrollSpecificFunnel(projectId, subscriberId, targetFunnelId, targetEntryStepId, originDepth)` (порядок як у dispatcher: depth-cap → rate-limit → резолв цілі з **явною перевіркою `targetFunnel.getProjectId().equals(projectId)`** fail-closed → re-enter → fallback кроку входу → фабрика); case SUBSCRIBE у `StepExecutor` (виклик enroll з `parent.depth+1`, успадкування `telegramBotId` батька, потім `COMPLETE` якщо `endParentAfter` інакше `CONTINUE`); новий `Outcome.COMPLETE` у рушії → `complete(exec, now)`. Тихий skip + greppable-WARN (окремий id на причину, лише id-и в логах) для draft/paused/видаленої/чужої цілі, depth-cap, rate-limit, re-enter-no-op. Включає engine-IT + test-run IT (стиль `FunnelTestRunSendIT`). Поле зветься `targetEntryStepId` (НЕ `targetStepId` як у code-research — див. Decision 5).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew test --tests '*FunnelExecutionEngineIT*' --tests '*Subscribe*'` → зелені
- **Files to modify:** `backend/src/main/java/com/botfunnel/funnel/FunnelExecutionFactory.java`, `backend/src/main/java/com/botfunnel/funnel/FunnelEventService.java`, `backend/src/main/java/com/botfunnel/funnel/StepExecutor.java`, `backend/src/main/java/com/botfunnel/funnel/FunnelExecutionEngine.java`, `backend/src/test/java/com/botfunnel/funnel/FunnelExecutionEngineIT.java`, `backend/src/test/java/com/botfunnel/funnel/FunnelStepExecutorTest.java`
- **Files to read:** `work/14-funnels-composition/code-research.md`, `backend/src/main/java/com/botfunnel/funnel/FunnelExecution.java`

#### Task 4: Frontend — форма кроку «Почати воронку»
- **Description:** Додати тип у `STEP_TYPES`, новий template-блок у `FunnelStepForm.vue`: пікер цільової воронки (`SearchableSelect`, lazy `fetch('active')`), пікер кроку входу (lazy `fetchOne(targetFunnelId).steps` + сентинел «з початку»), прапорець «завершити цю воронку після старту», inline-підказка якщо ціль не `active`, **підказка про патерн повернення** (рекомендований `endParentAfter=так`; якщо ні — повернення в живого батька з `allowReEnter=false` стане no-op, Ризик 2); submit-case формує payload. Оновити `types/funnel.ts` (union + поля) та i18n (лейбли типу/полів + `errors.funnels.funnel_subscribe_*`). Прев'ю-панель показує placeholder non-message автоматично.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-user:** open localhost funnel editor → «+ Add step» → «Почати воронку»: рендериться пікер воронки, пікер кроку входу, прапорець; підказка про не-`active` ціль
- **Files to modify:** `frontend/types/funnel.ts`, `frontend/components/funnels/FunnelStepForm.vue`, `frontend/i18n/locales/uk.json`, `frontend/i18n/locales/en.json`
- **Files to read:** `frontend/components/funnels/SearchableSelect.vue`, `frontend/stores/funnels.ts`, `frontend/pages/projects/[projectId]/funnels/[funnelId].vue`, `work/14-funnels-composition/code-research.md` (поле зветься `targetEntryStepId`, не `targetStepId`, Decision 5)

### Audit Wave

#### Task 5: Code Audit
- **Description:** Full-feature code quality audit. Read all source files created/modified in this feature (from decisions.md + tech-spec "Files to modify"). Review holistically for cross-component issues: bean-cycle safety (`StepExecutor → FunnelEventService`), backstop-reuse correctness (no duplicated depth-cap/rate-limit), exhaustive-switch coverage, fail-closed project scoping, architectural consistency with Phase 1-4. Write audit report.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 6: Security Audit
- **Description:** Full-feature security audit. Read all source files created/modified in this feature. Analyze for OWASP Top 10 across all components, focus on multitenant isolation (target lookup fail-closed by `projectId` at save and runtime), runaway/DoS protection (depth-cap + rate-limit), no IDOR via cross-funnel reference. Write audit report.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 7: Test Audit
- **Description:** Full-feature test quality audit. Read all test files created in this feature. Verify coverage of every user-spec AC, meaningful assertions (enrollDepth, status, skip+WARN, re-enter no-op), engine-IT determinism (clock + MockWebServer), test pyramid balance. Write audit report.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 8: Pre-deploy QA
- **Description:** Acceptance testing: run all backend + frontend tests, verify every acceptance criterion from user-spec and tech-spec. Request user to run the manual end-to-end Telegram scenario locally (build funnels A+B, link via SUBSCRIBE_TO_FUNNEL, activate both, "Test for me", walk `A → B → menu A`) since inline-button webhooks can't be reproduced headless without a tunnel.
- **Skill:** pre-deploy-qa
- **Reviewers:** none
- **Verify-user:** локальний прогін повного сценарію `A → B → меню A` у реальному Telegram
