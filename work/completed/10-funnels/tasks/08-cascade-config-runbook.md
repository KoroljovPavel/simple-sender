---
status: done
depends_on: [1, 5, 6]              # номера задач-зависимостей
wave: 3                            # волна параллельного выполнения
skills: [code-writing]             # МАССИВ скиллов для загрузки
verify: [smoke]                    # типы верификации: smoke, user (опционально)
reviewers: [code-reviewer, security-auditor, test-reviewer]  # явно указать. Пусто = fallback на дефолтные три
teammate_name:                     # косметическое имя тиммейта для agent teams
---

# Task 8: Каскад hard-delete + конфіг/env vars + runbook

## Required Skills

Перед выполнением задачи загрузи:
- `/skill:code-writing` — [skills/code-writing/SKILL.md](../../../.claude/skills/code-writing/SKILL.md)

## Description

Завершальна backend-«склейка» рушія воронок: коли проєкт hard-видаляється, його воронки та виконання
не повинні лишатися сиротами в Mongo; а нові конфіг-параметри рушія (інтервал sweep, ліміт кроків,
batch-cap) мають бути керовані через env і задокументовані для локального та staging-запуску.

Конкретно три речі:

1. **Каскад.** Розширити наявний `ProjectHardDeleteJob` так, щоб ПЕРЕД drop колекції `projects` він
   видаляв документи `funnels` та `funnel_executions` за top-level полем `projectId` (обидві колекції
   мають top-level `projectId` — див. Task 1 / Data Models tech-spec). Видалення супроводжується
   структурованими лог-лічильниками (кількість видалених funnels / funnel_executions), за тим самим
   взірцем, що вже використовується для інших каскадних колекцій у цьому джобі.

2. **Конфіг/env.** Додати три properties у `application.properties` з env-плейсхолдерами та дефолтами:
   - `app.funnel.scheduler-interval` ← env `FUNNEL_SCHEDULER_INTERVAL`, ISO-8601 Duration, default `PT30S`
     (споживається `@Recurring(interval=...)` у `FunnelExecutionEngine`, Task 6 / Decision 2).
   - `app.funnel.max-steps` ← env `FUNNEL_MAX_STEPS`, default `50` (ліміт кроків у `FunnelService`, Task 5).
   - `app.funnel.sweep-batch-size` ← env `FUNNEL_SWEEP_BATCH_SIZE`, default `200` (batch-cap claim'у за тік
     у `FunnelExecutionEngine`, Task 6 / Decision 17).
   Також **явно запінити** `org.jobrunr.background-job-server.poll-interval-in-seconds=15`, щоб інваріант
   `interval ≥ poll` тримався й майбутній підйом poll вище 30с не деградував каденцію тихо (Decision 2,
   «Durability»). Ті самі три env vars додати в `.env.example` (з коментарями) та задокументувати в
   `docs/local-setup.md`.

3. **Runbook.** Створити staging-smoke runbook `docs/staging-smoke/10-funnels.md` — інструкція для ручного
   end-to-end Telegram-прогону воронки власником (HTTPS+setWebhook не автоматизуються в CI; це єдиний спосіб
   перевірити реальний send). Формат — за взірцем наявних runbook'ів у `docs/staging-smoke/`.

**Увага — два відхилення від user-spec, обидва [PENDING USER APPROVAL]** (див. розділ Details).

## What to do

- Розширити `ProjectHardDeleteJob` каскадом: перед видаленням/drop `projects` видалити всі документи
  `funnels` та `funnel_executions` з `projectId == <project-id, що видаляється>`. Логувати кількість
  видалених документів кожної колекції іменованим лог-повідомленням (за тим самим стилем лог-лічильників,
  що вже є в джобі для інших колекцій). Зберегти наявний порядок видалення (funnels/executions ДО drop
  projects) — top-level `projectId` дозволяє видаляти їх незалежно.
- Розширити інтеграційний тест `ProjectHardDeleteJobIT`: засідити `funnels` + `funnel_executions` для
  проєкту-під-видалення І для контрольного «чужого» проєкту, запустити джоб, переконатися, що документи
  цільового проєкту зникли, а чужого — лишилися (cross-project isolation). Зробити це ДО зміни джоба
  (TDD: тест червоний → код → зелений).
- **РЕГРЕСІЯ — обов'язково.** У `ProjectHardDeleteJobIT` уже є **5 тестів** із hardcoded `containsPattern`-ассертами
  на точний структурований рядок `ProjectHardDeleteJob - run completed: ...`. Додавання нових токенів-лічильників
  (`funnelsRemoved=` / `funnelExecutionsRemoved=`) у цей `log.info()` ЗЛАМАЄ всі 5. Оновити КОЖЕН із цих 5
  `containsPattern`-ассертів так, щоб вони включали два нові поля-лічильники — інакше будуть регресії. Спочатку
  прочитати тест, знайти всі `containsPattern`-виклики на цей рядок, узгодити їх із новим форматом лог-рядка.
- Додати три properties у `application.properties` з env-плейсхолдерами та дефолтами (формат
  `${ENV:default}`), плюс рядок `org.jobrunr.background-job-server.poll-interval-in-seconds=15`.
- Додати три env vars у `.env.example` з пояснювальними коментарями та дефолтними значеннями.
- Задокументувати три env vars у `docs/local-setup.md` (опис, дефолт, коли змінювати) у відповідній
  секції env-конфігу.
- Створити `docs/staging-smoke/10-funnels.md` — runbook ручного Telegram-прогону: передумови (CONNECTED-бот,
  HTTPS-тунель/setWebhook), кроки (створити воронку Send→Delay→Send, активувати, скопіювати deep-link,
  надіслати `/start` боту, переконатися в отриманні повідомлень із дотриманням Delay), очікувані
  результати, та як перевірити стан виконання в логах (лог-константи переходів, БЕЗ payload). Формат за
  взірцем наявних runbook'ів у `docs/staging-smoke/`.

## TDD Anchor

Тести пишемо/розширюємо ДО реалізації каскаду. Пишемо → запускаємо → переконуємось що падають → пишемо
код → переконуємось що проходять.

- `backend/src/test/java/com/botfunnel/jobs/ProjectHardDeleteJobIT::cascadeRemovesFunnelsAndExecutions`
  (новий або розширення наявного сценарію) — після hard-delete проєкту в `funnels` та `funnel_executions`
  не лишається жодного документа з `projectId` видаленого проєкту.
- `backend/src/test/java/com/botfunnel/jobs/ProjectHardDeleteJobIT::cascadeDoesNotTouchOtherProjects` —
  funnels/funnel_executions іншого (контрольного) проєкту лишаються недоторканими (cross-project isolation).

**Регресійна примітка (НЕ забути):** у `ProjectHardDeleteJobIT` уже існують 5 тестів із `containsPattern`-ассертами
на лог-рядок `ProjectHardDeleteJob - run completed: ...`. Додавання `funnelsRemoved=`/`funnelExecutionsRemoved=`
у цей рядок зробить усі 5 червоними. Оновити ВСІ 5 наявних `containsPattern`-ассертів під новий формат лог-рядка
(уникнути регресій). Це частина TDD-циклу: зміна лог-рядка → червоні наявні тести → оновити ассерти → зелені.

(Конфіг-properties, `.env.example`, `docs/local-setup.md`, `docs/staging-smoke/10-funnels.md` — doc/config
deliverables без власних автотестів; перевіряються рев'ю та запуском контексту, див. Verification Steps.)

## Acceptance Criteria

- [ ] `ProjectHardDeleteJob` видаляє `funnels` + `funnel_executions` за top-level `projectId` ПЕРЕД drop
      `projects`, з лог-лічильниками кількості видалених документів кожної колекції.
- [ ] Каскад не зачіпає funnels/funnel_executions інших проєктів (cross-project isolation у тесті).
- [ ] `application.properties` містить `app.funnel.scheduler-interval` (env `FUNNEL_SCHEDULER_INTERVAL`,
      default `PT30S`), `app.funnel.max-steps` (env `FUNNEL_MAX_STEPS`, default `50`),
      `app.funnel.sweep-batch-size` (env `FUNNEL_SWEEP_BATCH_SIZE`, default `200`).
- [ ] `application.properties` явно пінить `org.jobrunr.background-job-server.poll-interval-in-seconds=15`.
- [ ] Імена properties збігаються з тими, що споживають Task 5 (`max-steps`) та Task 6
      (`scheduler-interval`, `sweep-batch-size`) — жодного розсинхрону ключів.
- [ ] `.env.example` містить три нові env vars із коментарями й дефолтами.
- [ ] `docs/local-setup.md` документує три нові env vars.
- [ ] `docs/staging-smoke/10-funnels.md` створено за форматом наявних runbook'ів; runbook описує повний
      ручний Telegram-прогін і де дивитися лог-переходи (без PII у логах).
- [ ] `./gradlew test --tests '*ProjectHardDelete*'` → зелені.
- [ ] Немає регресій у наявних тестах `ProjectHardDeleteJob`.

## Context Files

- [user-spec.md](../user-spec.md)
- [tech-spec.md](../tech-spec.md) — Task 8, Decision 2, Decision 17, User-Spec Deviations, Data Models, AVP
- [decisions.md](../decisions.md)
- [project.md](../../../.claude/skills/project-knowledge/references/project.md)
- [architecture.md](../../../.claude/skills/project-knowledge/references/architecture.md) — Data Model, Funnel Step Execution
- [patterns.md](../../../.claude/skills/project-knowledge/references/patterns.md)
- [testing.md](../../../.claude/skills/project-knowledge/references/testing.md) — Testing section, cascade/job patterns

**Code files (modify):**
- [ProjectHardDeleteJob.java](../../../backend/src/main/java/com/botfunnel/jobs/ProjectHardDeleteJob.java)
- [application.properties](../../../backend/src/main/resources/application.properties)
- [.env.example](../../../.env.example)
- [docs/local-setup.md](../../../docs/local-setup.md)
- [docs/staging-smoke/10-funnels.md](../../../docs/staging-smoke/10-funnels.md) (новий)

**Code files (read first — verify current state):**
- [ProjectHardDeleteJobIT.java](../../../backend/src/test/java/com/botfunnel/jobs/ProjectHardDeleteJobIT.java) — наявні каскад-сценарії, сід-хелпери ТА 5 наявних `containsPattern`-ассертів на лог-рядок `ProjectHardDeleteJob - run completed: ...`
- [Funnel.java](../../../backend/src/main/java/com/botfunnel/funnel/Funnel.java),
  [FunnelExecution.java](../../../backend/src/main/java/com/botfunnel/funnel/FunnelExecution.java) — підтвердити імена колекцій
  (`funnels` / `funnel_executions`) та top-level поле `projectId` (Task 1)
- [FunnelExecutionEngine.java](../../../backend/src/main/java/com/botfunnel/funnel/FunnelExecutionEngine.java) — підтвердити точні property-ключі,
  що споживаються `@Recurring(interval="${app.funnel.scheduler-interval:PT30S}")` та batch-cap (Task 6)
- [FunnelService.java](../../../backend/src/main/java/com/botfunnel/funnel/FunnelService.java) — підтвердити property-ключ `max-steps` (Task 5)
- наявний runbook у [docs/staging-smoke/](../../../docs/staging-smoke/) — взірець формату

## Verification Steps

### Automated
- `cd backend && ./gradlew test --tests '*ProjectHardDelete*'` → all pass (включно з новими каскад-сценаріями).

### Smoke
- Підтвердити, що каскад справді видаляє документи (а не лише компілюється): новий тест `cascadeRemovesFunnelsAndExecutions`
  ассертить `funnels.count(projectId==target)==0` та `funnel_executions.count(projectId==target)==0`, а
  `cascadeDoesNotTouchOtherProjects` ассертить, що документи контрольного проєкту лишилися (count > 0). Запустити
  саме ці сценарії: `cd backend && ./gradlew test --tests '*ProjectHardDeleteJobIT'` і перевірити, що cascade-count
  ассерти зелені, а 5 оновлених `containsPattern`-ассертів на лог-рядок проходять (немає регресій).
- Перевірити, що контекст застосунку стартує з новими properties (повний прогін
  `cd backend && ./gradlew test` або запуск, що піднімає Spring-контекст) — placeholder'и `${FUNNEL_*:default}`
  резолвляться, `@Recurring`-інтервал не падає на старті.

## Details

**Files:**
- `backend/src/main/java/com/botfunnel/jobs/ProjectHardDeleteJob.java` — додати два кроки каскаду
  (`funnels`, `funnel_executions` за `projectId`) ПЕРЕД drop `projects`; додати лог-лічильники у стилі
  наявних. НЕ змінювати наявний порядок/семантику решти каскаду. Спершу прочитати файл, щоб переюзати
  наявний хелпер `removeByProjectId`, який видаляє BATCH-ом через
  `Criteria.where("projectId").in(deletedIds)` (`.in()`, НЕ `.is()`) — нові дві колекції видаляти тим самим
  батч-хелпером з `.in(deletedIds)`. Переюзати наявний стиль лог-константи з лічильником.
- `backend/src/test/java/com/botfunnel/jobs/ProjectHardDeleteJobIT.java` — розширити: засідити обидві нові
  колекції для цільового та контрольного проєктів; ассертити видалення цільових і збереження контрольних.
- `backend/src/main/resources/application.properties` — три нові `app.funnel.*` properties +
  `org.jobrunr.background-job-server.poll-interval-in-seconds=15`. Формат env-плейсхолдера —
  `${FUNNEL_SCHEDULER_INTERVAL:PT30S}` тощо.
- `.env.example` — три env vars із коментарями (призначення + дефолт + одиниці/формат).
- `docs/local-setup.md` — секція з описом трьох env vars.
- `docs/staging-smoke/10-funnels.md` — новий runbook (формат за наявним взірцем).

**Dependencies:**
- Task 1 (домен) — імена колекцій `funnels` / `funnel_executions` і top-level `projectId`, що ним каскадимо.
- Task 6 (engine) — точні property-ключі `app.funnel.scheduler-interval`, `app.funnel.sweep-batch-size`,
  що цей таск визначає в `application.properties`. ПЕРЕВІР, що ключі в коді Task 6 та тут ідентичні —
  головне джерело багів цього таска. Task 5 аналогічно для `app.funnel.max-steps`.

**Відхилення від user-spec (обидва [PENDING USER APPROVAL] — згадати у звіті в decisions.md):**
- **Env rename:** user-spec називав `FUNNEL_SCHEDULER_INTERVAL_SECONDS` (число секунд); цей таск
  використовує `FUNNEL_SCHEDULER_INTERVAL` (ISO-8601 Duration, default `PT30S`), бо JobRunr
  `@Recurring(interval=)` приймає рядок ISO-8601 Duration, а не число (Decision 2 / User-Spec Deviations).
  Поведінка (~30с sweep) ідентична. Якщо користувач не схвалив — зафіксувати блокер у звіті, не вигадувати
  компромісну назву.
- **Додано `FUNNEL_SWEEP_BATCH_SIZE`** (default 200) — у user-spec не згадане; мітигація US-ризику
  «Навантаження sweep» (Decision 17). Якщо не схвалено — зафіксувати у звіті.

**Edge cases:**
- Проєкт без жодної воронки/виконання — каскад має коректно відпрацювати з лічильником 0 (не падати).
- Кілька проєктів у БД — видаляються лише документи цільового `projectId` (cross-project isolation).
- Колекції ще не створені (auto-index-creation) — `remove` по неіснуючій колекції не має кидати помилку.

**Implementation hints:**
- Видаляти ПЕРЕД drop `projects`, бо drop projects не каскадить вкладені колекції; top-level `projectId`
  на funnels/funnel_executions (Data Models tech-spec) робить це прямим `remove`-запитом.
- Переюзати наявний хелпер `removeByProjectId`: він видаляє BATCH-ом через
  `Criteria.where("projectId").in(deletedIds)` (`.in()`, НЕ `.is()`). Обидві нові колекції видаляти тим самим
  `.in(deletedIds)`-батч-викликом — не вводити одиничний `.is()`-варіант.
- Лог-лічильники: дзеркали наявний стиль у `ProjectHardDeleteJob` (іменована константа + count видалених),
  спостережуваність каскаду (Decision 16 — без payload, лише ідентифікатори/лічильники). Наявний лог-рядок
  `ProjectHardDeleteJob - run completed: ...` доповнити `funnelsRemoved=`/`funnelExecutionsRemoved=` — і одразу
  оновити 5 наявних `containsPattern`-ассертів у `ProjectHardDeleteJobIT` під новий формат.
- Properties: тримати поряд із наявними `app.*`/jobrunr налаштуваннями; коментар біля
  `poll-interval-in-seconds=15` з посиланням на інваріант `interval ≥ poll` (Decision 2).
- Runbook: явно зазначити, що відрендерені тіла/PII у логах НЕ присутні (Decision 16), тож перевірка йде
  за лог-константами переходів + реальним отриманням повідомлень у Telegram.

## Reviewers

- **code-reviewer** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-8/code-reviewer-{round}.json`
- **security-auditor** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-8/security-auditor-{round}.json`
- **test-reviewer** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-8/test-reviewer-{round}.json`

## Post-completion

- [ ] Записати краткий отчёт в decisions.md по шаблону (Summary: 1-3 предложения, ревью со ссылками на JSON,
      без таблиц файндингов и дампов). **Явно зафіксувати статус двох [PENDING USER APPROVAL] відхилень**
      (env rename + FUNNEL_SWEEP_BATCH_SIZE) у секції Deviations.
- [ ] Если отклонились от спека — описать отклонение и причину.
- [ ] Обновить user-spec/tech-spec если что-то изменилось (зокрема після схвалення/відхилення env-deviations).
