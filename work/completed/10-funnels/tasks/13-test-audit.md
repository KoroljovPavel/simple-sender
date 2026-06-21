---
status: done                       # planned -> in_progress -> done
depends_on: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]   # все реализующие задачи фичи
wave: 5                            # волна параллельного выполнения (audit wave)
skills: [test-master]              # МАССИВ скиллов для загрузки
verify: []                         # типы верификации: smoke, user (нет — это аудит-deliverable)
reviewers: []                      # аудит — финальный артефакт, ревьюеров нет
teammate_name:                     # косметическое имя тиммейта (не используется)
---

# Task 13: Test Audit

## Required Skills

Перед выполнением задачи загрузи:
- `/skill:test-master` — [SKILL.md](../../../.claude/skills/test-master/SKILL.md) — методология качества тестов: пирамида unit/IT/E2E, осмысленность ассертов, детерминизм, покрытие конкурентности/идемпотентности (см. также `references/test-quality-review.md`, `references/integration-tests.md`).

## Description

Сквозной аудит качества тестов всей фичи `10-funnels` (линейные воронки, Фаза 1). Это **не код-таск** — реализация уже выполнена в Tasks 1–10. Задача: прочитать **все** тесты, созданные/затронутые фичей (backend unit + integration, frontend Vitest, Playwright E2E, i18n parity gate), и оценить их качество против стандартов test-master и раздела "Testing Strategy" tech-spec.

Цель аудита — найти пробелы и слабые места до pre-deploy QA (Task 14): отсутствующее покрытие критичных инвариантов рушія (at-most-once per step, atomic-claim race, idempotency на рестарте), бессмысленные/тавтологичные ассерты, перекос пирамиды (слишком много IT там, где хватило бы unit, или наоборот — критичные конкурентные сценарии покрыты только unit-моками без реального Testcontainers/Mongo), нарушения детерминизма (реальные `Instant.now()`/`Thread.sleep` вместо инъекции `java.time.Clock` через `ClockConfig`), пропущенный или формальный i18n parity gate.

Deliverable — **отчёт аудита** (markdown), записанный в `work/10-funnels/logs/test-audit.md`. Кода не пишем и не правим; находки передаём для исправления в рамках соответствующих реализующих задач или в Task 14 (Pre-deploy QA).

## What to do

1. **Собрать инвентарь тестов фичи.** Найти и прочитать все тестовые файлы, относящиеся к фиче. **Если целевой тест-файл отсутствует на момент аудита** (например, `frontend/e2e/funnels.spec.ts` создаётся в Task 10) — **не падать**: зафиксировать как HIGH coverage-gap находку в отчёте и продолжить аудит остальных файлов.
   - Backend unit: `*Test.java` под `com/botfunnel/funnel/**` (VariableTemplateRenderer, step-executors, trigger-matcher, execution state machine, funnel activation validation, enum lowercase) и затронутые модули (`TelegramSenderTest` для `sendPhoto`, тесты `SubscriberCustomFieldsService`).
   - Backend integration: `*IT.java` на `AbstractIntegrationTest` (Testcontainers Mongo + `JobRunrInMemoryConfig` + MockWebServer Telegram) — индексы, sweep end-to-end, последовательные НЕ-Delay шаги за тик, Delay resume, idempotency, atomic-claim race, `fire()` error-isolation, реактивация-порядок, SendImage failure-matrix, observability (лог без payload), `/stop`/pause/delete, re-enter, snapshot, `requireOwned`/cross-project, `ProjectHardDeleteJob` каскад, sub-minute `@Recurring` каденция, `ProcessTelegramUpdateJobTest.startCommandFiresFunnelTrigger`.
   - Frontend: Vitest-компоненты редактора (AddStepDialog/EditStepDialog/FunnelStepsList, FunnelTriggerSettings), Playwright E2E (`frontend/e2e/funnels.spec.ts`).
   - i18n parity: `frontend/tests/i18n/required-keys.spec.ts` / `cd frontend && node scripts/check-locales.mjs` (gate `funnels.*` + `errors.funnels.*` в `uk.json`/`en.json`).

2. **Сверить фактическое покрытие с Testing Strategy tech-spec** (раздел "Testing Strategy" — unit/IT/E2E списки). Для каждого ожидаемого сценария отметить: покрыт / частично / отсутствует. Особое внимание HIGH-сценариям, явно помеченным в tech-spec.

3. **Оценить покрытие критичных инвариантов рушія:**
   - **Atomic-claim / concurrency:** есть ли IT на двух «репликах» (взірець `SubscriberExportConcurrencyIT` / `BotConnectRaceIT`), проверяющий что параллельный claim выдаёт ровно одно выполнение шага (at-most-once).
   - **Idempotency:** повторный прогон уже claimed-шага не дублирует send; **отдельный** сценарий краха после send / до `done`-flip (рестарт инстанса) не пере-надсилает.
   - **At-most-once per step:** проверка одного `findAndModify` (не двух операций) и `.name()`-литералов в критериях.

4. **Оценить осмысленность ассертов.** Тест должен проверять поведение/состояние, а не тавтологию (например, не `assertEquals(x, x)`, не только «не бросил исключение»). Для рушія: проверяются реальные переходы статусов (`running→waiting→running→completed`, `cancelled`, `failed`), фактический вызов `TelegramSender` нужным методом, корректный `nextRunAt`, инкремент `currentStepIndex`.

5. **Оценить баланс пирамиды (unit/IT/E2E).** Чистая логика (VariableTemplateRenderer, рендер Double/Instant, escaping HTML/MarkdownV2) — на unit. Конкурентность/persistence/каденция — на IT (Testcontainers), не на моках. Golden-path UI — один E2E + дешёвый page-test на 422-ветку активации. Отметить перекосы: дублирование одного и того же на нескольких уровнях, либо критичный инвариант, покрытый только моками.

6. **Проверить i18n parity gate.** Gate реально падает при рассинхроне ключей `uk.json`/`en.json`; покрывает добавленные `funnels.*` и `errors.funnels.*`.

7. **Проверить детерминизм времени.** Все таймингозависимые тесты (Delay `nextRunAt`, sweep due-predicate) используют инъецированный `java.time.Clock` (`ClockConfig`), а не реальный `Instant.now()` / `Thread.sleep` для продвижения времени. Sweep тестируется прямым вызовом метода (взірець `ProjectHardDeleteJobIT`), без ожидания реальной каденции.

8. **Написать отчёт аудита** в `work/10-funnels/logs/test-audit.md`: по каждой оси — статус (OK / findings), список находок с severity (HIGH/MEDIUM/LOW), указанием тест-файла и предлагаемым фиксом. Завершить таблицей покрытия Testing-Strategy-сценариев и итоговым вердиктом (готово к pre-deploy QA / нужны доработки).

## Acceptance Criteria

- [ ] Прочитаны все тестовые файлы фичи (backend unit+IT, frontend Vitest, E2E, i18n gate); инвентарь зафиксирован в отчёте.
- [ ] Каждый сценарий из tech-spec "Testing Strategy" сопоставлен с фактическим тестом (покрыт / частично / отсутствует).
- [ ] Покрытие конкурентности рушія оценено: atomic-claim race, idempotency повторного прогона, отдельный сценарий «крах после send / до done-flip».
- [ ] Оценена осмысленность ассертов (нет тавтологий / «не бросил» как единственная проверка); слабые тесты перечислены.
- [ ] Оценён баланс пирамиды unit/IT/E2E; перекосы и дубли отмечены.
- [ ] Проверен i18n parity gate (реально падает при рассинхроне; покрывает `funnels.*` + `errors.funnels.*`).
- [ ] Проверен детерминизм времени (инъекция `java.time.Clock`/`ClockConfig`, отсутствие `Thread.sleep`/`Instant.now()` для тайминга).
- [ ] HIGH-сценарии tech-spec (`fire()` error-isolation, реактивация-порядок, SendImage failure-matrix, observability-без-payload) явно проверены на наличие.
- [ ] Отчёт записан в `work/10-funnels/logs/test-audit.md` с находками (severity + файл + фикс) и итоговым вердиктом.

## Context Files

- [user-spec.md](../user-spec.md)
- [tech-spec.md](../tech-spec.md) — раздел "Testing Strategy" (unit/IT/E2E ожидания), "Acceptance Criteria" (i18n parity, тесты зелёные, нет регрессий)
- [decisions.md](../decisions.md) — итоги реализующих задач + созданные тест-файлы
- [project.md](../../../.claude/skills/project-knowledge/references/project.md)
- [architecture.md](../../../.claude/skills/project-knowledge/references/architecture.md)
- [patterns.md](../../../.claude/skills/project-knowledge/references/patterns.md) — раздел Testing (паттерны `AbstractIntegrationTest`, Testcontainers, `JobRunrInMemoryConfig`, MockWebServer, `ClockConfig`, atomic-claim race взірці)

**Тестовые файлы фичи (читать всё, что существует на момент аудита):**
- Backend unit: `backend/src/test/java/com/botfunnel/funnel/**Test.java` (VariableTemplateRenderer, step-executors, trigger-matcher, execution state machine, funnel activation validation, enum lowercase)
- Backend (затронутые модули): `backend/src/test/java/com/botfunnel/bot/TelegramSenderTest.java`, тесты `SubscriberCustomFieldsService` под `backend/src/test/java/com/botfunnel/subscriber/`
- Backend integration: `backend/src/test/java/com/botfunnel/funnel/**IT.java`, `backend/src/test/java/com/botfunnel/jobs/ProjectHardDeleteJobIT.java`, `backend/src/test/java/com/botfunnel/webhook/ProcessTelegramUpdateJobTest.java`
- Взірці concurrency-IT для сравнения: `backend/src/test/java/com/botfunnel/subscriber/export/SubscriberExportConcurrencyIT.java`, `backend/src/test/java/com/botfunnel/bot/BotConnectRaceIT.java`
- Frontend Vitest: `frontend/**/*funnel*.spec.ts` / `*.test.ts` (компоненты редактора)
- Frontend E2E: `frontend/e2e/funnels.spec.ts`
- i18n gate: `frontend/tests/i18n/required-keys.spec.ts` + `frontend/i18n/locales/{uk,en}.json`

## Verification Steps

<!-- Это аудит-deliverable, а не код-таск. "Верификация" = подтверждение полноты самого отчёта. -->

### Audit deliverable check
- Отчёт `work/10-funnels/logs/test-audit.md` существует и заполнен по всем осям из "What to do".
- Каждая HIGH-находка имеет: тест-файл, описание пробела/слабости, предлагаемый фикс.
- Присутствует таблица покрытия Testing-Strategy-сценариев и итоговый вердикт.

### Reference (опционально — убедиться, что тесты вообще зелёные перед оценкой качества)
- `cd backend && ./gradlew test -PrunSlow=true` → backend unit+IT зелёные (включает медленные IT).
- `cd frontend && pnpm test` → Vitest зелёные; `pnpm test:e2e` → E2E зелёные.
- `cd frontend && node scripts/check-locales.mjs` (или соответствующий i18n gate) → parity зелёный.

## Details

<!-- Аудит: читаем, оцениваем, пишем отчёт. Код не правим. -->

**Что делаем:** читаем все тесты фичи целиком, оцениваем против Testing Strategy tech-spec и стандартов test-master, пишем отчёт. Никаких правок исходников/тестов — только находки.

**Что читать (полный охват):** все тестовые файлы из секции Context Files. Если целевой/ожидаемый по tech-spec тест-файл отсутствует на момент аудита (например, `frontend/e2e/funnels.spec.ts` — deliverable Task 10) — **не падать**: записать как HIGH coverage-gap находку и продолжить аудит остального.

**Ключевые оси аудита (из tech-spec Testing Strategy + test-master):**
- Concurrency: atomic-claim race на двух «репликах» (взірець `SubscriberExportConcurrencyIT`/`BotConnectRaceIT`) — результат at-most-once.
- Idempotency: повторный прогон claimed-шага + **отдельный** сценарий краха после send / до `done`-flip.
- HIGH-сценарии tech-spec: `fire()` error-isolation (виняток не марковує raw_update FAILED / не вызывает webhook-retry, Decision 6); реактивация-порядок (upsert ставит `active` ДО `fire()`, первый send не само-скасовується status-gate'ом).
- SendImage failure-matrix отделён от SendMessage (400-без-chat-not-found → `failed`; 403/chat-not-found → cancelled+flip).
- Observability: тест проверяет наличие лог-константы и **отсутствие** отрендеренного payload (PII, Decision 16).
- Детерминизм: `java.time.Clock`/`ClockConfig` инъекция; sweep — прямой вызов метода, не реальная каденция; нет `Thread.sleep` для тайминга (Decision 9).
- Пирамида: чистая логика → unit; persistence/concurrency/каденция → IT (Testcontainers, не моки); UI golden-path → 1 E2E + page-test на 422.
- i18n parity gate реально падает при рассинхроне `uk.json`/`en.json` и включает новые `funnels.*` ключи.

**Dependencies:** Tasks 1–10 (вся реализация + тесты фичи должны существовать). Запускается в Audit Wave параллельно с Task 11 (Code Audit) и Task 12 (Security Audit) — все три не зависят друг от друга.

**Edge cases / на что смотреть:**
- Тест «зелёный», но ничего не проверяет (только запускает путь без ассертов состояния).
- Конкурентный инвариант «покрыт» юнит-тестом с моками вместо реального Mongo (ложная уверенность).
- Idempotency покрыта только «повторным вызовом», но нет сценария рестарта между send и done-flip.
- i18n gate существует, но не включает новые `funnels.*` ключи в required-набор.
- Тайминговый тест проходит из-за реального ожидания/sleep, а не детерминированного `Clock` — флакость в CI.
- Ожидаемый по spec тест-файл отсутствует (например, нет отдельного SendImage failure-matrix IT) — пробел.

**Implementation hints (НЕ псевдокод):** структурируй отчёт по осям (Coverage-vs-spec таблица, Concurrency, Idempotency, Assertions quality, Pyramid balance, i18n, Determinism, Verdict). Для каждой находки — severity, файл, проблема, фикс. Не дублируй находки Code/Security аудитов (Tasks 11/12) — фокус строго на качестве тестов.

## Reviewers

Нет. Аудит — финальный артефакт фичи; ревью не назначается.

## Post-completion

- [ ] Записать краткий отчёт в decisions.md (Summary: 1–3 предложения с итоговым вердиктом аудита и ссылкой на `logs/test-audit.md`; без дампов находок).
- [ ] Если найдены HIGH-пробелы — явно перечислить их в decisions.md как блокеры для Task 14 (Pre-deploy QA).
- [ ] Обновить tech-spec "Testing Strategy" только если выявлено расхождение между заявленной и реальной стратегией (зафиксировать как deviation).
