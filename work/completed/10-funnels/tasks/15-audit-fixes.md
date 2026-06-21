---
status: done
depends_on: [11]
wave: 5
skills: [code-writing]
verify: [smoke]
reviewers: [code-reviewer, test-reviewer]
teammate_name:
---

# Task 15: Code-audit fixes (F1 audit-event gap + F2 claim-conditional advance)

## Required Skills

Перед виконанням завантаж:
- `/skill:code-writing` — [SKILL.md](../../../.claude/skills/code-writing/SKILL.md)

## Description

Усуває два крос-компонентні знайдення з аудиту Task 11 ([logs/code-audit.md](../logs/code-audit.md)). Обидва — імплементаційні прогалини проти наявних ідіом техспеку; зміни специфікації НЕ потрібні.

- **F1 (major):** воронковий крок `SET_CUSTOM_FIELD` (`StepExecutor.setCustomField`) пише значення поля, але не емітить аудит-івент `subscriber_custom_field_set`. Це суперечить (а) крокам `ADD_TAG`/`REMOVE_TAG`, що пишуть аудит через `SubscriberServiceImpl`, і (б) задокументованому контракту: `SubscriberCustomFieldsService.setOne` свідомо НЕ пише аудит, а викликач (рушій) мусить викликати `SubscriberService.recordCustomFieldsSet` (Decision 11, sole-writer; Task 4 NOTE + Javadoc `setOne`). Наразі `recordCustomFieldsSet` викликається лише з HTTP-контролера.

- **F2 (minor):** рушій просуває виконання через безумовний full-document `mongoTemplate.save(exec)` (`persistProgress`/`scheduleDelay`/`complete`/`terminate`), не залежний від утримання claim. Конкурентний термінальний cancel (`FunnelService.delete`, `FunnelTriggerServiceImpl.cancelActiveFor`/`cancelExistingForPair`), що влучив у вікно після claim і до save того ж тіку, затирається — виконання воскресає. `/stop` self-heal'иться через status-gate наступного тіку, але delete воронки — ні (підписник active, бот connected), тож виконання видаленої воронки добігає до кінця за snapshot.

## What to do

**F1 — додати sole-writer аудит для воронкового SET_CUSTOM_FIELD:**
1. Передати `Subscriber` у `StepExecutor.setCustomField` (рушій уже має його в `execute(...)` і відкидає при виклику).
2. Дзеркалити sole-writer патерн контролера (`SubscriberCustomFieldsController`): обчислити нормалізоване НОВЕ значення через `customFieldsService.validateAndNormalize(type, raw)`, прочитати СТАРЕ з `subscriber.getCustomFields()`, застосувати через `applyAll`, потім викликати `subscriberService.recordCustomFieldsSet(projectId, subscriberId, {key:old}, {key:new})`. Один івент на крок.
3. Зберегти наявну поведінку: видалене/невідоме поле (type==null) → silent skip без аудиту; 422 type-mismatch → `StepResult.fail`.

**F2 — зробити in-tick записи рушія умовними щодо claim:**
4. Перевести `persistProgress`/`scheduleDelay`/`complete`/`terminate` з `mongoTemplate.save(exec)` на guarded `findAndModify`/`updateFirst` із критерієм `_id` + `stepRunStatus = in_progress.name()` (тобто "claim ще наш"). Записувати лише потрібні поля (не full-doc replace).
5. Якщо guarded-запис не зматчив документ (claim втрачено — інший писар скасував) → рушій припиняє обробку цього виконання в поточному тіку (вийти з `runExecution`), без перетирання `cancelled`.
6. Після фіксу підправити коментар у `FunnelService.delete` (рядки ~142-145), що наразі переоцінює гарантію — тепер вона справжня.

## TDD Anchor

Пишемо тести ДО реалізації, переконуємось що падають на поточному коді, потім реалізуємо.

- `FunnelStepExecutorTest` (або новий IT) — SET_CUSTOM_FIELD-крок успішно → у `subscriber_events` зʼявляється рівно один `subscriber_custom_field_set` з коректними `oldValues`/`newValues`/`changedKeys` (значення нормалізоване, не сире). Симетрично наявному тесту на `subscriber_tag_added`.
- SET_CUSTOM_FIELD з видаленим полем (type==null) → НЕ пише аудит, крок CONTINUE.
- SET_CUSTOM_FIELD з тим самим значенням, що вже стоїть → `recordCustomFieldsSet` no-op (порожній changedKeys), крок CONTINUE.
- `FunnelExecutionEngineIT` — гонка claim↔cancel: рушій claim'ить виконання, паралельний `cancelActiveFor`/`FunnelService.delete` ставить `cancelled`; після тіку виконання лишається `cancelled` і НЕ переходить у `completed`/`running` (тобто guarded-запис не воскресив його).
- Регрес: наявні engine-IT (проходження кроків, Delay resume, idempotency, atomic-claim race) лишаються зеленими.

## Acceptance Criteria

- [ ] Воронковий SET_CUSTOM_FIELD пише `subscriber_custom_field_set` через `SubscriberService.recordCustomFieldsSet` (sole-writer), з нормалізованими old/new; видалене поле — без аудиту; 422 → fail.
- [ ] Усі in-tick записи рушія (`persistProgress`/`scheduleDelay`/`complete`/`terminate`) умовні щодо `stepRunStatus = in_progress`; втрата claim → рушій не перетирає термінальний стан і припиняє тік для цього виконання.
- [ ] Коментар у `FunnelService.delete` приведено у відповідність до реальної (тепер справжньої) гарантії.
- [ ] Нові тести з TDD Anchor зелені; повний `./gradlew test` (+`-PrunSlow=true` для engine-IT) без регресій.
- [ ] Жодного запису `subscriber_events` поза `SubscriberServiceImpl` (sole-writer збережено — рушій лише делегує).

## Context Files

- [tech-spec.md](../tech-spec.md) — Decisions 4 (atomic-claim), 11 (sole-writer / shared service), 14 (lowercase `.name()`)
- [decisions.md](../decisions.md) — Task 4 NOTE (setOne не пише аудит), Task 6 (engine), Task 11 (findings)
- [logs/code-audit.md](../logs/code-audit.md) — повний опис F1/F2 + suggested fixes
- [patterns.md](../../../.claude/skills/project-knowledge/references/patterns.md)
- `backend/src/main/java/com/botfunnel/funnel/StepExecutor.java` (F1)
- `backend/src/main/java/com/botfunnel/funnel/FunnelExecutionEngine.java` (F1 call-site + F2)
- `backend/src/main/java/com/botfunnel/funnel/FunnelService.java` (F2 comment)
- `backend/src/main/java/com/botfunnel/subscriber/SubscriberCustomFieldsService.java`, `SubscriberServiceImpl.java`, `SubscriberCustomFieldsController.java` (sole-writer reference)

## Verification Steps

### Automated
- `./gradlew test --tests '*FunnelStepExecutorTest*'` → pass
- `./gradlew test -PrunSlow=true --tests '*FunnelExecutionEngineIT*'` → pass (incl. new claim↔cancel race test)
- `./gradlew test` (full non-slow suite) → no regressions

### Smoke
- Спостережуваність: після SET_CUSTOM_FIELD у тесті присутній рядок `subscriber_custom_field_set` у `subscriber_events`.

## Details

**Files:**
- `StepExecutor.java` — `setCustomField` отримує `Subscriber`; validateAndNormalize → applyAll → recordCustomFieldsSet.
- `FunnelExecutionEngine.java` — call-site `setCustomField(step, exec, subscriber)`; guarded writes (findAndModify з `stepRunStatus=in_progress`), повертати/перевіряти "claim still mine".
- `FunnelService.java` — текст коментаря в `delete()`.

**Edge cases:**
- `subscriber.getCustomFields()` може бути null → old = null (singletonMap допускає null-значення; `recordCustomFieldsSet` null-safe).
- old береться з in-memory subscriber (snapshot початку тіку) — для Фази 1 прийнятно (контролер теж читає old один раз); зазначити в коментарі.
- guarded terminal-запис: критерій `stepRunStatus=in_progress`; cancel-шляхи ставлять `stepRunStatus=done`, тож після cancel предикат не матчиться — cancel детерміновано виграє.
- enum-літерали лишаються `.name()` lowercase (Decision 14).

**Implementation hints:** дзеркалити контролер для F1 (validateAndNormalize+applyAll, НЕ setOne — потрібне нормалізоване значення для коректного diff). Для F2 — `findAndModify` returnNew + null-check, або `updateFirst` + перевірка modifiedCount; при втраті claim вийти з `runExecution`.

## Reviewers

- **code-reviewer** → `logs/working/task-15/code-reviewer-{round}.json`
- **test-reviewer** → `logs/working/task-15/test-reviewer-{round}.json`

## Post-completion

- [ ] Записати короткий звіт у decisions.md (Task 15) — що виправлено, ревью зі посиланнями.
- [ ] Якщо відхилились від suggested fix у code-audit.md — описати причину.
- [ ] tech-spec/user-spec не чіпати (фікс не змінює контракт).
