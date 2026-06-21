# Smoke / ручна верифікація — 12-funnels-triggers (Phase 3)

Ручний чекліст для Task 15 (post-deploy verification, локальний запуск). Автодеплою немає —
«live» = локальний стек автора. Усе нижче перевіряється руками проти localhost.

> Що вже перевірено автоматично (можна не повторювати, лише за бажанням):
> - Усі acceptance-критерії покриті тестами — pre-deploy QA: backend 1020/1020, frontend 408/408, AC 23/23 PASS.
> - **Міграцію індексу Task 2 вже прогнано живим бутом** (jar проти Docker Mongo+Redis, 3 сценарії APPLIED/NO-OP/fresh).
>   Розділ 8 нижче — повторне підтвердження вручну (лог + `listIndexes`), за бажанням.
>
> Що **обовʼязково** руками (не автоматизовано в цій сесії): усе через реальний Telegram (розділи 3, 5)
> та UX у браузері (розділи 1, 2).

---

## 0. Передумови та запуск стека

- [ ] Інфра: `docker compose -f infra/docker-compose.yml up -d` → MongoDB :27017, Redis :6379, Mailpit :8025.
- [ ] Backend: `cd backend && ./gradlew bootRun` → Tomcat :8080.
- [ ] Health: `curl -s http://localhost:8080/health` → OK.
- [ ] Frontend: запущений на :3000.
- [ ] **Throwaway** Telegram-бот (НЕ продакшен-маркетинг-бот), підключений через ngrok-тунель
      (як у `docs/staging-smoke/08-webhook-ingestion.md`). У `application.properties` зараз
      `app.url` вказує на локальний тунель — звір, що це твій актуальний ngrok/loca.lt.
- [ ] Підготовлені тестові воронки (через кабінет на :3000):
  - keyword-воронка зі словом-тригером у списку `keywords` (напр. `bonus`);
  - `tag_added`-воронка (на тег-слаг) + `custom_field_set`-воронка (на ключ поля);
  - **дві** активні `event`-воронки-слухачі того самого `event_name` (для fan-out);
  - воронка з кроком `EMIT_EVENT`, що емітить той самий `event_name`.

---

## 1. Funnel editor UI — селектор тригерів + EMIT_EVENT (Task 9, браузер)

- [ ] Відкрити редактор воронки → у налаштуваннях тригера є **селектор типу**:
      `on_start / keyword / tag_added / custom_field_set / api-event`.
- [ ] Перемикання типу показує **правильний редактор значення**:
  - `on_start` → payload + прев'ю deep-link;
  - `keyword` → список слів (додати/видалити кілька);
  - `tag_added` → `SearchableSelect` тегу;
  - `custom_field_set` → `SearchableSelect` поля;
  - `api-event` → slug-інпут `event_name` (мапиться на `triggerType=event`).
- [ ] Діалог додавання кроку містить **«Emit event»** з полем `event_name`.
- [ ] Зберегти воронку кожного типу → після перезавантаження тип і значення збереглись (немає
      «протікання» значення іншого типу).

## 2. API-key card у налаштуваннях проєкту (Task 10, браузер)

- [ ] Налаштування проєкту → картка API-ключа. Якщо ключа немає → кнопка **«Згенерувати»**.
- [ ] Натиснути «Згенерувати» → у модалці plaintext-ключ показаний **один раз** + кнопка копіювання
      + попередження «більше не покажемо».
- [ ] Закрити модалку, перезавантажити сторінку → показана **маска** `prefix•••` + кнопка
      **«Перегенерувати»** (повний ключ ніде не видно).
- [ ] «Перегенерувати» → новий plaintext один раз; старий ключ після цього недійсний (перевіряється в розд. 6).

> Збережи згенерований ключ — він знадобиться для curl у розділах 6–7. Позначай `<key>`.

## 3. Keyword + precedence над меню (Task 6, Telegram) — ОБОВ'ЯЗКОВО РУКАМИ

- [ ] Написати боту вільний текст зі словом-тригером **у різному регістрі / як підрядок**
      (напр. `хочу BONUS сьогодні`) → стартує правильна keyword-воронка (приходить її повідомлення).
- [ ] Завести підписника в **меню** (крок MENU з `waiting_for_reply`, Phase 2) і написати **те саме слово**
      → меню **лишається**, keyword-воронка **НЕ** стартує (precedence).
- [ ] Команди `/start`, `/stop` keyword не чіпає (стартова поведінка незмінна).
- [ ] (опц.) у логах при підавленні видно greppable INFO-маркер precedence; при помилці диспетчера —
      WARN `KEYWORD_DISPATCH_ERROR`, воркер усе одно 200.

## 4. tag_added / custom_field_set (Task 5, кабінет/curl + Telegram)

- [ ] Додати підписнику **тег** (через кабінет на :3000 або curl) → стартує відповідна `tag_added`-воронка
      (приходить повідомлення в Telegram).
- [ ] **Повторно** додати той самий тег (idempotent no-op) → воронка **НЕ** стартує. ← ключова перевірка.
- [ ] Встановити **custom-поле** → стартує `custom_field_set`-воронка.
- [ ] Повторний set того самого значення (no-op) → воронка **НЕ** стартує.

## 5. EMIT_EVENT fan-out (Task 5, Telegram + mongosh) — ОБОВ'ЯЗКОВО РУКАМИ

- [ ] Прогнати батьківську воронку з кроком `EMIT_EVENT` для підписника (за наявності **двох** активних
      воронок-слухачів того самого `event_name`).
- [ ] Обидві воронки-слухачі **стартують** (перевірити через бота, що прийшли обидва повідомлення).
- [ ] `mongosh botfunnel --eval 'db.funnel_executions.find({subscriberId:"<id>"}).count()'` → +2 нові виконання.
- [ ] Батьківська воронка **продовжується** (крок повертає CONTINUE).
- [ ] (опц.) якщо у воронках навмисне зробити цикл — у логах має зʼявитись WARN дропу по depth-cap (10)
      або fan-out ceiling (50); живцем не навантажуємо, лише фіксуємо якщо побачимо.

## 6. POST /api/integrations/v1/events — матриця кодів (Task 7, curl) — ОБОВ'ЯЗКОВО РУКАМИ

Заголовок ключа: `X-API-Key: <key>`. Ендпоінт: `POST http://localhost:8080/api/integrations/v1/events`.

- [ ] **202 (старт):** валідний ключ + відомий `telegram_user_id` + є воронка-слухач:
      ```
      curl -i -X POST http://localhost:8080/api/integrations/v1/events \
        -H 'X-API-Key: <key>' -H 'Content-Type: application/json' \
        -d '{"event_name":"<listened>","telegram_user_id":<known>}'
      ```
      → `202`, воронка стартує.
- [ ] **202 (no-op):** той самий, але `event_name` без жодного слухача → `202`, нічого не стартує.
- [ ] **400 (битий slug):** `-d '{"event_name":"bad slug!!","telegram_user_id":<known>}'` → `400`.
- [ ] **400 (немає ідентифікатора):** `-d '{"event_name":"x"}'` (без `telegram_user_id`/`subscriber_id`) → `400`.
- [ ] **subscriber_id виграє:** передати обидва ідентифікатори (різні) → резолвиться по `subscriber_id`.
- [ ] **401 уніфікована (анти-enumeration):** прогнати три причини — **без** заголовка / **битий** ключ /
      **невідомий** ключ → усі три дають `401` з **байт-у-байт однаковим** тілом:
      ```
      curl -s -X POST .../events -H 'Content-Type: application/json' -d '{"event_name":"x","telegram_user_id":1}' ; echo
      curl -s -X POST .../events -H 'X-API-Key: garbage' -H 'Content-Type: application/json' -d '{"event_name":"x","telegram_user_id":1}' ; echo
      curl -s -X POST .../events -H 'X-API-Key: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' -H 'Content-Type: application/json' -d '{"event_name":"x","telegram_user_id":1}' ; echo
      ```
      → три однакові відповіді.
- [ ] **404 (невідомий підписник):** валідний ключ + неіснуючий `telegram_user_id` → `404`.
- [ ] **429 (rate-limit):** залити запитами понад глобальний ліміт (~300/хв) при **піднятому** Redis → `429`.
      (Якщо Redis недоступний — 429 не спрацює, це fail-open by design; перевіряти при живому Redis.)
- [ ] **Жодного 5xx** на всіх кейсах; підписник **не** створюється:
      `mongosh botfunnel --eval 'db.subscribers.find({telegramUserId:<known_unknown>}).count()'` → `0`.

## 7. API-ключ: hash-only at rest + ізоляція ланцюгів (Task 7/8, curl + mongosh)

- [ ] У БД лежить лише хеш + префікс:
      `mongosh botfunnel --eval 'db.api_keys.find({}, {keyHash:1, keyPrefix:1, _id:0}).pretty()'`
      → є `keyHash` + `keyPrefix`, **повного plaintext немає** ніде.
- [ ] **Ключ ✗ кабінет:** тим самим `<key>` дернути сесійний ендпоінт →
      `curl -i -H 'X-API-Key: <key>' http://localhost:8080/api/v1/projects/<projectId>/api-key` → відмова (401/403).
- [ ] **Сесія ✗ /integrations:** сесійною cookie (з браузера) дернути `/api/integrations/v1/events` → відмова (401).
- [ ] Після «Перегенерувати» (розд. 2) старий ключ на `/events` → `401`.

## 8. Міграція індексу (Task 2, лог старта + mongosh) — вже зроблено автоматично, повтор за бажанням

- [ ] У логах `bootRun` є маркер runner'а: `grep -E "trigger-index reconciliation" <startup.log>`
      → APPLIED (перший старт на старому індексі) або NO-OP (вже мігровано).
- [ ] `mongosh botfunnel --eval 'db.funnels.getIndexes()'` →
  - старого `projectId_triggerType_triggerValue_unique_active` з фільтром `{status:'active'}` **немає**;
  - є on_start-only форма з partial filter `{status:'active', triggerType:'on_start'}`.
- [ ] Перезапустити backend → маркер **NO-OP** (ідемпотентність), без помилок.
- [ ] Перевірка fan-out через індекс: дві активні `event`-воронки з однаковим `triggerValue`
      **співіснують**; дві `on_start` з однаковим payload — **конфліктують** (як і раніше).

---

## Куди писати результат

- Findings (якщо є) — у `decisions.md` (запис Task 15) + короткий звіт post-deploy verification.
- Код у межах Task 15 **не правимо** — будь-яке розходження фіксуємо як finding і сигналимо.

## На що особливо дивитись (edge cases)
- 401 має бути **уніфікована** (анти-enumeration) — три причини, однакове тіло.
- Розрізняти **404** (невідомий підписник) vs **202 no-op** (підписник є, слухача нема).
- Два під-кейси **400**: битий slug `event_name` та відсутність обох ідентифікаторів; при обох — виграє `subscriber_id`.
- **idempotent no-op НЕ тригерить** (теги/поля) — Decision 5.
- `/events` **не** створює підписника (авто-реєстрація лише на `/start`).
