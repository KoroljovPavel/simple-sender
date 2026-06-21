---
status: done
depends_on: []
wave: 1
skills: [code-writing]
verify: []
reviewers: [code-reviewer, security-auditor, test-reviewer]
teammate_name:
---

# Task 2: VariableTemplateRenderer (чиста утиліта)

## Required Skills

Перед виконанням задачі загрузи:
- `/skill:code-writing` — [skills/code-writing/SKILL.md](../../../.claude/skills/code-writing/SKILL.md)

## Description

Реалізувати `VariableTemplateRenderer` — чисту статичну утиліту підстановки змінних у тексти кроків
воронки (SendMessage `text` і SendImage `caption`). Це фундаментальний компонент Wave 1: рушій
(`FunnelExecutionEngine`, Task 6) проганяє через нього кожен текст/caption перед надсиланням у Telegram.

Утиліта підставляє плейсхолдери `{user.*}` (поля підписника) та `{custom.<key>}` (значення кастом-полів),
коректно обробляє невідомі/порожні значення (→ `""`), escape-послідовності `{{`/`}}` (→ `{`/`}`), людський
рендер не-рядкових типів (`Double 30.0`→`30`, `Boolean`, `Instant`→ISO-8601), і — критично для безпеки
(Decision 10, OWASP A03 injection) — екранує **підставлені значення** за правилами обраного `parseMode`
(None / HTML / MarkdownV2). Авторська розмітка самого шаблону НЕ екранується — екрануються лише значення,
що приходять із даних підписника, щоб спецсимвол в імені/полі не зламав розмітку й не дав інʼєкцію.

Задача чисто-функціональна (без I/O, без Spring-залежностей, без request-scope), тож вимагає сильного
unit-покриття всіх гілок — це основний deliverable.

## What to do

- Створити статичну утиліту `VariableTemplateRenderer` у пакеті `com.botfunnel.funnel`, що приймає
  template-рядок, контекст підписника (поля `first_name`/`last_name`/`username` + map кастом-полів) і
  `parseMode` (null = None | `HTML` | `MarkdownV2`), і повертає відрендерений рядок.
- Підставляти `{user.first_name}`, `{user.last_name}`, `{user.username}` зі значень підписника.
- Підставляти `{custom.<key>}` зі значення відповідного кастом-поля підписника.
- Невідомий плейсхолдер (немає такого `user.*`-поля чи `custom.<key>`) АБО порожнє/null-значення → підставити
  порожній рядок `""` (плейсхолдер зникає, без помилки).
- Обробити escape: літерали `{{` → `{` і `}}` → `}` (екранований брейс не трактується як початок/кінець
  плейсхолдера).
- Людський рендер не-рядкових значень кастом-полів: `Double` без зайвого дробу для цілих (`30.0` → `30`),
  `Boolean` → людський рядок, `Instant` → ISO-8601, інші типи → `String.valueOf`.
- Екранувати ЛИШЕ підставлені значення за обраним `parseMode`:
  - `None`: без екранування (звичайний текст — найбезпечніший, спецсимвол не зламає send).
  - `HTML`: екранувати `&`, `<`, `>` у підставлених значеннях; авторська HTML-розмітка шаблону лишається як є.
  - `MarkdownV2`: екранувати повний charset спецсимволів MarkdownV2 у підставлених значеннях; авторська
    розмітка лишається як є.
- Жодного I/O, жодного логування PII всередині утиліти.

## TDD Anchor

Тести пишемо ДО реалізації у `backend/src/test/java/com/botfunnel/funnel/VariableTemplateRendererTest.java`
(стиль — `TelegramCommandParserTest`: JUnit 5, табличні/параметризовані кейси, чисті асерти без I/O).
Пишемо → запускаємо → падають → реалізуємо → зелені.

- `` `backend/src/test/java/com/botfunnel/funnel/VariableTemplateRendererTest.java::substitutesUserFields` `` — `{user.first_name}`/`{user.last_name}`/`{user.username}` замінюються відповідними значеннями підписника.
- `` `backend/src/test/java/com/botfunnel/funnel/VariableTemplateRendererTest.java::substitutesCustomField` `` — `{custom.<key>}` замінюється значенням кастом-поля.
- `` `backend/src/test/java/com/botfunnel/funnel/VariableTemplateRendererTest.java::unknownPlaceholderRendersEmpty` `` — невідомий `{user.x}` / `{custom.x}` → `""`.
- `` `backend/src/test/java/com/botfunnel/funnel/VariableTemplateRendererTest.java::emptyOrNullValueRendersEmpty` `` — присутній ключ із null/порожнім значенням → `""`.
- `` `backend/src/test/java/com/botfunnel/funnel/VariableTemplateRendererTest.java::escapedBracesAreLiteral` `` — `{{` → `{`, `}}` → `}` (не трактуються як плейсхолдер).
- `` `backend/src/test/java/com/botfunnel/funnel/VariableTemplateRendererTest.java::rendersDoubleWithoutTrailingZero` `` — `Double 30.0` → `30`.
- `` `backend/src/test/java/com/botfunnel/funnel/VariableTemplateRendererTest.java::rendersBooleanHumanReadable` `` — `Boolean` → очікуваний рядок.
- `` `backend/src/test/java/com/botfunnel/funnel/VariableTemplateRendererTest.java::rendersInstantAsIso` `` — `Instant` → ISO-8601.
- `` `backend/src/test/java/com/botfunnel/funnel/VariableTemplateRendererTest.java::htmlModeEscapesSubstitutedValues` `` — у `HTML`-режимі значення зі `&<>` екрануються, авторська HTML-розмітка шаблону — НІ.
- `` `backend/src/test/java/com/botfunnel/funnel/VariableTemplateRendererTest.java::markdownV2ModeEscapesFullCharset` `` — у `MarkdownV2`-режимі повний charset спецсимволів у значеннях екранується, авторська розмітка — НІ.
- `` `backend/src/test/java/com/botfunnel/funnel/VariableTemplateRendererTest.java::noneModeDoesNotEscape` `` — `None`-режим лишає значення без екранування.

## Acceptance Criteria

- [ ] `{user.first_name}` / `{user.last_name}` / `{user.username}` підставляються коректно.
- [ ] `{custom.<key>}` підставляється зі значення кастом-поля підписника.
- [ ] Невідомий плейсхолдер і порожнє/null-значення → `""` (без помилки).
- [ ] `{{` → `{`, `}}` → `}` (escape).
- [ ] `Double 30.0` → `30`; `Boolean` людський; `Instant` → ISO-8601.
- [ ] У режимах HTML/MarkdownV2 екрануються ЛИШЕ підставлені значення; авторська розмітка не чіпається.
- [ ] Режим None не екранує.
- [ ] Утиліта чиста: без I/O, без Spring-залежностей, без логування відрендерених значень (PII, Decision 16).
- [ ] Усі unit-тести `VariableTemplateRendererTest` зелені.

## Context Files

- [user-spec.md](../user-spec.md)
- [tech-spec.md](../tech-spec.md)
- [decisions.md](../decisions.md)
- [project.md](../../../.claude/skills/project-knowledge/references/project.md)
- [architecture.md](../../../.claude/skills/project-knowledge/references/architecture.md)
- [patterns.md](../../../.claude/skills/project-knowledge/references/patterns.md) — Testing section (взірець unit-тестів)
- Code (modify): `backend/src/main/java/com/botfunnel/funnel/VariableTemplateRenderer.java`
- Code (read): `backend/src/main/java/com/botfunnel/webhook/TelegramCommandParser.java` — взірець стилю
  чистого парсера + його тест
- Code (read): `backend/src/main/java/com/botfunnel/subscriber/CustomFieldValueValidator.java` — типи
  значень кастом-полів (Double/Boolean/Instant/String)
- Code (read): `backend/src/main/java/com/botfunnel/subscriber/Subscriber.java` — форма `customFields`
  (shape map ключ→значення) і user-поля підписника

## Verification Steps

### Automated
- `cd backend && ./gradlew test --tests '*VariableTemplateRenderer*'` → усі зелені.

<!-- Smoke: немає Verify-smoke для Task 2 у tech-spec (чиста утиліта покривається unit-ами) — секцію опущено. -->
<!-- User: немає Verify-user для Task 2 — секцію опущено. -->

## Details

**Files:**
- `backend/src/main/java/com/botfunnel/funnel/VariableTemplateRenderer.java` — НОВИЙ файл. Чиста статична
  утиліта (без `@Component`/`@Service` — викликається статично з рушія). Один публічний метод рендеру, що
  приймає template + контекст підписника (user-поля + map кастом-полів) + `parseMode`.
- `backend/src/test/java/com/botfunnel/funnel/VariableTemplateRendererTest.java` — НОВИЙ файл (директорія
  `funnel/` у test-source може ще не існувати — створити).

**Dependencies:** немає залежностей від інших задач (Wave 1 фундамент). Споживач — Task 6
(`FunnelExecutionEngine`). Нових пакетів не додавати — лише JDK + наявні залежності.

**Edge cases:**
- Невідомий ключ vs присутній-але-порожній ключ — обидва → `""`, але переконатися що логіка не падає на
  null-map / null-значенні.
- `{{user.first_name}}` — це літеральний `{user.first_name}` (escape брейсів має пріоритет над підстановкою).
- Незакритий `{` без пари `}` — визначити поведінку (лишати як є, не падати) і покрити тестом.
- Значення підписника, що саме містить `{` чи `}` — після підстановки НЕ ре-парситься (одно-прохідний рендер).
- `parseMode = null` трактувати як None.
- Caption SendImage проходить той самий рендер — escape-семантика однакова (Decision 10); ліміти довжини
  (4096 text / 1024 caption) — НЕ тут, а в step-executor'і Task 6.

**Implementation hints (НЕ псевдокод):**
- Стиль брати з `TelegramCommandParser` — компактна чиста утиліта, детермінована, легко юніт-тестується.
- Екранування підставлених значень робити ПІСЛЯ резолву значення й ПЕРЕД вставкою в результат, щоб авторська
  розмітка шаблону лишалась нечіпаною (ключова вимога Decision 10 / A03).
- Для `MarkdownV2` використати повний офіційний charset спецсимволів Telegram MarkdownV2 — звірити з докою.
- Рендер типів: цілий `Double` без `.0`; `Instant` через ISO-8601 (`Instant.toString()` дає ISO-8601 UTC).
- Тримати утиліту вільною від логування відрендереного тексту — це PII (Decision 16).

## Reviewers

- **code-reviewer** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-2/code-reviewer-{round}.json`
- **security-auditor** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-2/security-auditor-{round}.json`
- **test-reviewer** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-2/test-reviewer-{round}.json`

## Post-completion

- [ ] Записати краткий отчёт в decisions.md по шаблону (Summary: 1-3 предложения, ревью со ссылками на JSON, без таблиц файндингов и дампов)
- [ ] Если отклонились от спека — описать отклонение и причину
- [ ] Обновить user-spec/tech-spec если что-то изменилось
