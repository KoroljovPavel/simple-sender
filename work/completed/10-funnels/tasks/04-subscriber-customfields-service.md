---
status: done
depends_on: []
wave: 1
skills: [code-writing]
verify: []
reviewers: [code-reviewer, security-auditor, test-reviewer]
teammate_name:
---

# Task 4: SubscriberCustomFieldsService + lookup-by-chat

## Required Skills

Перед выполнением задачи загрузи:
- `/skill:code-writing` — [skills/code-writing/SKILL.md](../../../.claude/skills/code-writing/SKILL.md)

## Description

Движок воронок (`FunnelExecutionEngine`, Task 6) виконує крок `SET_CUSTOM_FIELD` усередині JobRunr-воркера,
де немає HTTP-контексту, `SecurityContextHolder` чи `@RequestBody`-валідації. Сьогодні вся логіка
set-custom-field живе **inline** у `SubscriberCustomFieldsController.setCustomFields(...)`:
validate (через `CustomFieldValueValidator`) → update `customFields.<key>` через `MongoTemplate` →
`subscriberService.recordCustomFieldsSet(...)` (sole-writer аудит-події `subscriber_custom_field_set`).
Движок не може переюзати цю послідовність, не реплікуючи її (ризик розсинхрону аудиту, Decision 11).

Тому виносимо спільну логіку set-one-custom-field у новий `@Service SubscriberCustomFieldsService`
із методом `setOne(projectId, subscriberId, key, value)` **без request-scope залежностей**, а контролер
делегує до нього. Поведінка контролера (PATCH-ендпоінт) МУСИТЬ лишитися **байт-у-байт незмінною** для
клієнта: ті самі коди (200/404/422), та сама mass-assignment-defense (невідомі ключі тихо ігноруються),
той самий sole-writer-шлях аудиту.

Окремо: движку потрібен lookup підписника поза HTTP за `(projectId, telegramBotId, chatId)`. Цей репо-метод
(`SubscriberRepository.findByProjectIdAndTelegramBotIdAndTelegramChatId`) уже існує й уже використовується
приватно в `SubscriberServiceImpl.findByChat(...)`, але **не виставлений** в інтерфейсі `SubscriberService`.
Додаємо публічний lookup-by-chat у `SubscriberService` (+ impl), що делегує до наявного репо-метода — без
нової інфраструктури.

Результат: контролер behaviour незмінний; движок може ставити кастом-поле через спільний сервіс і
резолвити підписника поза HTTP, зберігаючи sole-writer audit-семантику.

## What to do

1. **Створити `SubscriberCustomFieldsService` (`@Service`)** у пакеті `com.botfunnel.subscriber`:
   - Конструкторні залежності: `CustomFieldValueValidator`, `SubscriberService`, `MongoTemplate`
     (НЕ `ProjectService`, НЕ `SecurityContextHolder`, НЕ HTTP/request-scope — Decision 11).
   - Метод `setOne(String projectId, String subscriberId, String customFieldType-info..., Object value)` —
     точна сигнатура нижче в Details. Він інкапсулює один валід-апдейт-аудит цикл для **одного** ключа:
     валідація значення за типом (`validator.validate(type, value)` → 422 на mismatch; `null` → unset поля),
     атомарний `MongoTemplate`-апдейт `customFields.<key>` (set/unset), і виклик
     `subscriberService.recordCustomFieldsSet(projectId, subscriberId, oldValues, newValues)` (єдиний
     sole-writer-шлях аудиту, ідемпотентний — порожній diff не пише події).
   - "Видалене поле" семантика для движка: якщо `type == null` (ключ більше не визначений у проєкті) —
     **skip** (no-op), не кидати. Це дзеркалить mass-assignment-defense контролера й вимогу tech-spec
     («видалене поле → skip»).

2. **Рефакторити `SubscriberCustomFieldsController`** так, щоб inline validate→update→record логіка
   делегувалася до `SubscriberCustomFieldsService`. Контролер далі робить `projectService.requireOwned(...)`
   + projectId-scoped lookup підписника (anti-IDOR / uniform 404) — це HTTP-only обовʼязки, вони
   **залишаються в контролері**. Витягнута частина — лише валідація/апдейт/аудит.
   **ОБОВʼЯЗКОВО зберегти агреговану форму аудиту:** виклик
   `SubscriberServiceImpl.recordCustomFieldsSet(projectId, subscriberId, oldValues, newValues)` мусить
   відбутися ОДИН раз із повними мапами old/new усіх змінених ключів за один PATCH — НЕ per-key. Інакше
   один PATCH розфрагментується на кілька `subscriber_custom_field_set` подій (регрес аудиту). Тобто сервіс
   має приймати мапу (key→value) або контролер агрегує old/new по всіх ключах і викликає record один раз.
   Кінцева зовнішня поведінка ендпоінта не змінюється (ті самі коди, той самий тіло-відгук = поточні
   `customFields`, та сама кількість і форма аудит-подій).

3. **Додати lookup-by-chat в `SubscriberService`** (інтерфейс): публічний метод, що повертає
   `Optional<Subscriber>` за `(projectId, telegramBotId, chatId)`.

4. **Імплементувати lookup-by-chat у `SubscriberServiceImpl`**, делегуючи до наявного
   `subscriberRepository.findByProjectIdAndTelegramBotIdAndTelegramChatId(...)`. Можна перевикористати/
   підняти наявний приватний `findByChat(...)` helper. Жодної нової query-логіки — лише експозиція.

5. Написати тести (див. TDD Anchor) ДО реалізації. **Створити нові тест-файли:**
   - `backend/src/test/java/com/botfunnel/subscriber/SubscriberCustomFieldsServiceTest.java` (new)
   - `backend/src/test/java/com/botfunnel/subscriber/SubscriberCustomFieldsControllerTest.java` (new)
   Lookup-by-chat покривається у наявному `SubscriberServiceImplIT.java` (єдиний наявний тест subscriber-сервісу
   сьогодні; окремого `SubscriberServiceImplTest.java` НЕМАЄ — не припускати його існування).

## TDD Anchor

- `backend/src/test/java/com/botfunnel/subscriber/SubscriberCustomFieldsServiceTest.java::setOne_validValue_updatesAndRecordsAudit`
  — `setOne` з валідним значенням пише `customFields.<key>` і викликає `recordCustomFieldsSet` із
  правильними old/new (через мок `SubscriberService` / Mongo verify).
- `...SubscriberCustomFieldsServiceTest::setOne_invalidValue_throws422` — невалідне значення (validator
  кидає) пропагується як 422-`AppException` (для движка це обернеться в `failed`).
- `...SubscriberCustomFieldsServiceTest::setOne_deletedField_skips` — ключ, відсутній у визначеннях
  проєкту (`type == null`) → no-op: жодного апдейту, жодної аудит-події.
- `...SubscriberCustomFieldsServiceTest::setOne_nullValue_unsetsField` — `null` → `unset` поля + аудит
  (відображення «null clears the field» контролера).
- `backend/src/test/java/com/botfunnel/subscriber/SubscriberCustomFieldsControllerTest.java::patchCustomFields_behaviourUnchanged`
  — PATCH через делегацію дає той самий результат, що й раніше: 200 + поточні `customFields`; невідомий
  ключ тихо ігнорується; невалідний тип → 422; чужий/відсутній subscriber → uniform 404. (Якщо контролер-
  тест уже існує — переконатися, що він лишається зеленим без змін очікувань; за потреби розширити.)
- `backend/src/test/java/com/botfunnel/subscriber/SubscriberServiceImplIT.java::lookupByChat_delegatesToRepository`
  — новий lookup-by-chat повертає те, що віддає `findByProjectIdAndTelegramBotIdAndTelegramChatId`
  (включно з `Optional.empty()` коли нема). Розширити наявний `SubscriberServiceImplIT` (це єдиний наявний
  тест subscriber-сервісу; окремого `SubscriberServiceImplTest` немає).

## Acceptance Criteria

- [ ] Новий `@Service SubscriberCustomFieldsService` з методом `setOne(...)` без request-scope залежностей
      (немає `ProjectService` / `SecurityContextHolder` / HTTP-типів у класі).
- [ ] `SubscriberCustomFieldsController` делегує validate→update→record до сервісу; зовнішня поведінка
      PATCH-ендпоінта незмінна (200/404/422, mass-assignment-defense, тіло = поточні `customFields`).
- [ ] Аудит `subscriber_custom_field_set` пишеться ВИКЛЮЧНО через
      `SubscriberServiceImpl.recordCustomFieldsSet` (sole-writer збережено; жодного прямого
      `subscriberEventRepository`/`EventService` у новому сервісі) — Decision 11.
- [ ] PATCH-контролер викликає `recordCustomFieldsSet(projectId, subscriberId, oldValues, newValues)` ОДИН
      раз на запит із агрегованими мапами old/new усіх змінених ключів (НЕ per-key) — кількість і форма
      `subscriber_custom_field_set` подій ідентична поточній.
- [ ] `SubscriberService` має публічний lookup-by-chat за `(projectId, telegramBotId, chatId)`, impl
      делегує до наявного `SubscriberRepository.findByProjectIdAndTelegramBotIdAndTelegramChatId`.
- [ ] Видалене поле (`type == null`) у `setOne` → skip (no-op), не виняток.
- [ ] Усі нові unit-тести зелені; наявні тести контролера/subscriber-модуля без регресій.

## Context Files

- [user-spec.md](../user-spec.md)
- [tech-spec.md](../tech-spec.md) — Decision 11; "How it works" → SetCustomField + lookup-by-chat у `fire()`;
  Shared resources table (`SubscriberCustomFieldsService` new, `SubscriberServiceImpl` sole writer).
- [decisions.md](../decisions.md)
- [project.md](../../../.claude/skills/project-knowledge/references/project.md)
- [architecture.md](../../../.claude/skills/project-knowledge/references/architecture.md)
- [patterns.md](../../../.claude/skills/project-knowledge/references/patterns.md) — Java/Spring service patterns, Testing section.
- Code (modify):
  - [SubscriberCustomFieldsService.java](../../../backend/src/main/java/com/botfunnel/subscriber/SubscriberCustomFieldsService.java) (new)
  - [SubscriberCustomFieldsController.java](../../../backend/src/main/java/com/botfunnel/subscriber/SubscriberCustomFieldsController.java)
  - [SubscriberService.java](../../../backend/src/main/java/com/botfunnel/subscriber/SubscriberService.java)
  - [SubscriberServiceImpl.java](../../../backend/src/main/java/com/botfunnel/subscriber/SubscriberServiceImpl.java)
- Code (create — tests):
  - [SubscriberCustomFieldsServiceTest.java](../../../backend/src/test/java/com/botfunnel/subscriber/SubscriberCustomFieldsServiceTest.java) (new)
  - [SubscriberCustomFieldsControllerTest.java](../../../backend/src/test/java/com/botfunnel/subscriber/SubscriberCustomFieldsControllerTest.java) (new)
  - [SubscriberServiceImplIT.java](../../../backend/src/test/java/com/botfunnel/subscriber/SubscriberServiceImplIT.java) — наявний; розширити кейсом lookup-by-chat.
- Code (read):
  - [SubscriberRepository.java](../../../backend/src/main/java/com/botfunnel/subscriber/SubscriberRepository.java) — наявний `findByProjectIdAndTelegramBotIdAndTelegramChatId`.
  - [CustomFieldValueValidator.java](../../../backend/src/main/java/com/botfunnel/subscriber/CustomFieldValueValidator.java) — `validate(type, value)`.
  - [ProjectService.java](../../../backend/src/main/java/com/botfunnel/project/ProjectService.java) — `requireOwned` (лишається в контролері).

## Verification Steps

### Automated
- `cd backend && ./gradlew test --tests '*SubscriberCustomFieldsService*' --tests '*SubscriberCustomFieldsController*' --tests '*SubscriberServiceImpl*'` → all pass
- `cd backend && ./gradlew test` → без регресій у subscriber/webhook/bot-модулях

<!-- Smoke / User: not applicable for this task (pure backend refactor, no endpoint/behaviour change). -->

## Details

**Files:**
- `backend/src/main/java/com/botfunnel/subscriber/SubscriberCustomFieldsService.java` — **новий** `@Service`.
  Виносить per-key цикл validate→update→record із контролера. Залежності: `CustomFieldValueValidator`,
  `SubscriberService`, `MongoTemplate`. Метод (рекомендована сигнатура):
  `void setOne(String projectId, String subscriberId, com.botfunnel.project.CustomFieldType type, String key, Object value)`
  — тобто тип поля передається викликачем (контролер резолвить тип із `Project.customFieldDefinitions`,
  движок — зі snapshot/визначень проєкту). Якщо архітектурно зручніше приймати `Map<String,CustomFieldType> allowed`
  + одну пару (key,value) — допустимо, головне: НЕ тягнути `Project`/`ProjectService` у сервіс (це HTTP-
  слой). Семантика всередині `setOne`: `type == null` → skip; `validator.validate(type, value)` →
  normalized (422 на mismatch); `normalized == null` → `update.unset("customFields."+key)`, інакше
  `update.set("customFields."+key, normalized)`; застосувати `MongoTemplate` апдейт (mirror того inline-блоку
  контролера, що будує `Update` на `customFields.<key>` set/unset і викликає `mongoTemplate.update(...)`
  всередині циклу по `request.values()`); потім — для агрегованого аудиту — повернути old/normalized
  викликачеві АБО прийняти повну мапу й викликати
  `subscriberService.recordCustomFieldsSet(projectId, subscriberId, oldValues, newValues)`
  один раз (див. «Аудит обовʼязково агрегований» нижче). recordCustomFieldsSet уже ідемпотентний
  (порожній diff → no-op), тож дублювати guard не треба.
- `backend/src/main/java/com/botfunnel/subscriber/SubscriberCustomFieldsController.java` — у циклі по
  `request.values()` для кожного дозволеного ключа делегувати в `SubscriberCustomFieldsService.setOne(...)`
  замість inline `validator.validate` + `update.set/unset` + ручного збору oldValues/newValues +
  `recordCustomFieldsSet`. `requireOwned` + projectId-scoped subscriber lookup + збір `allowedTypes(project)`
  + фінальний reload-та-повернення `customFields` ЛИШАЮТЬСЯ в контролері. Невідомі ключі (`type == null`)
  далі тихо ігноруються (mass-assignment-defense AC11) — або відсіюються в контролері перед делегацією,
  або сервісом через skip; зберегти існуючий `log.debug` для дропнутого ключа.
  Увага: поточний контролер групує всі апдейти в ОДИН `Update` і робить один `mongoTemplate.update`, потім
  один `recordCustomFieldsSet` з агрегованими old/new. **Аудит ОБОВʼЯЗКОВО лишається агрегованим:**
  `recordCustomFieldsSet(projectId, subscriberId, oldValues, newValues)` викликається рівно ОДИН раз на PATCH
  із повними мапами old/new усіх змінених ключів. Не делегувати аудит per-key (це породить кілька
  `subscriber_custom_field_set` подій замість однієї → регрес аудиту). Прийнятні форми: (а) `setOne(...)`
  лише валідує+апдейтить значення й ПОВЕРТАЄ old/normalized, а контролер агрегує мапи та викликає
  `recordCustomFieldsSet` один раз; або (б) додати сервіс-метод, що приймає всю мапу (key→value) і робить
  один record. Per-key виклик `recordCustomFieldsSet` зсередини `setOne` ЗАБОРОНЕНО для контролерного шляху.
  Підсумковий стан `customFields` й набір аудит-подій мусять лишитися байт-у-байт як зараз — головний
  інваріант.
- `backend/src/main/java/com/botfunnel/subscriber/SubscriberService.java` — додати в інтерфейс публічний
  метод, напр. `Optional<Subscriber> findByChat(String projectId, Long telegramBotId, Long chatId);`
  (назва на твій розсуд, але узгоджена з кодстайлом інтерфейсу).
- `backend/src/main/java/com/botfunnel/subscriber/SubscriberServiceImpl.java` — реалізувати метод інтерфейсу,
  делегуючи до `subscriberRepository.findByProjectIdAndTelegramBotIdAndTelegramChatId(...)`. У класі вже є
  приватний `findByChat(...)`, що повертає `Subscriber` (null якщо нема) — або підняти його до `@Override`
  публічного `Optional`-варіанта, або додати окремий публічний метод поряд. Уникнути дублювання query.

**Dependencies:** Жодних задач-залежностей (Wave 1). Споживачі — Task 6 (engine `SET_CUSTOM_FIELD` + lookup
у тригері/движку). Жодних нових пакетів.

**Edge cases:**
- Невідомий/видалений custom-field key → skip (no-op), не виняток (mass-assignment-defense + tech-spec
  «видалене поле → skip»).
- `value == null` → `unset` поля (очищення), аудит фіксує зміну.
- Невалідний тип значення → 422 пропагується (у движку Task 6 обернеться в execution `failed`).
- Порожній diff (значення не змінилось) → `recordCustomFieldsSet` нічого не пише (ідемпотентність уже в
  `SubscriberServiceImpl`).
- lookup-by-chat: відсутній підписник → `Optional.empty()` (движок: log+skip).
- Сервіс НЕ робить anti-IDOR — це обовʼязок викликача (контролер: `requireOwned`; движок працює у вже
  довіреному project-scope).

**Implementation hints:**
- Sole-writer інваріант (Decision 11): новий сервіс НЕ торкається `subscriberEventRepository`/`EventService`
  напряму — лише через `subscriberService.recordCustomFieldsSet`.
- Поточний inline-блок контролера (цикл по `request.values()`: `validator.validate` → побудова `Update`
  set/unset на `customFields.<key>` → `mongoTemplate.update` → збір агрегованих old/new → один
  `recordCustomFieldsSet`) — референс для семантики validate/set/unset/record; зберегти її один-в-один.
- Тести-сервісу — Mockito (mock `CustomFieldValueValidator`, `SubscriberService`, `MongoTemplate`) за взірцем
  наявних unit-тестів subscriber-модуля; контролер-behaviour тест — MockMvc/наявний стиль контролер-тестів.

## Reviewers

- **code-reviewer** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-4/code-reviewer-{round}.json`
- **security-auditor** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-4/security-auditor-{round}.json`
- **test-reviewer** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-4/test-reviewer-{round}.json`

## Post-completion

- [ ] Записать краткий отчёт в decisions.md по шаблону (Summary: 1-3 предложения, ревью со ссылками на JSON, без таблиц файндингов и дампов)
- [ ] Если отклонились от спека — описать отклонение и причину (особенно если форма аудит-событий изменилась)
- [ ] Обновить user-spec/tech-spec если что-то изменилось
