# Ручне тестування — 11-funnels-interactive (Інтерактив + клавіатури, Фаза 2)

Чеклист ручної перевірки **поверх автотестів**. Автотести (974 backend + 391 vitest) уже зелені й покривають
інваріанти на рівні IT — тут перелічено те, що потребує **живого середовища / реального Telegram** і не покривається
симуляцією. Кожен пункт має посилання на критерій приймання (AC) та автотест, що покриває його непрямо.

Джерела: per-task `Verify-smoke`/`Verify-user`, tech-spec «Agent Verification Plan», user-spec «Как проверить».

---

## 0. Передумови (локальне середовище)

Деплою немає — усе локально.

- [ ] Підняти MongoDB + Redis (docker-compose / локально).
- [ ] Backend: `cd backend && ./gradlew bootRun` (конфіг `app.funnel.max-steps-per-tick` має дефолт 100).
- [ ] Frontend: `cd frontend && npm run dev` (node з nvm, якщо не в PATH: `~/.nvm/versions/node/v22.19.0/bin`).
- [ ] Тестовий Telegram-бот: токен підключено до тестового проєкту (бот у статусі `CONNECTED`).
- [ ] Залогінений у фронт як власник проєкту (auth-cookie/csrf для `curl`-кроків).

Значення, що знадобляться: `{projectId}`, `{funnelId}`, `{executionId}` (з Mongo), id кроків воронки.

---

## A. Редактор воронок (браузер) — Task 6 (Verify-user)

> Покрито непрямо: vitest `frontend/tests/pages/funnel-editor.spec.ts` + Playwright `e2e/funnels.spec.ts`
> (`funnels_menuGoldenPath_buildAndActivate`). Жива перевірка інтерактиву UI — нижче.

- [ ] **A1.** Відкрити редактор воронки → **+ Add step → MENU**. Форма меню рендериться (текст + parse_mode).
      → AC: «автор додає крок MENU…»
- [ ] **A2.** Додати **2 callback-кнопки** з лейблами; для кожної обрати ціль через `SearchableSelect`
      (інший крок або **End**). Пошук у пікері працює. → AC: «callback-кнопка веде на крок або End».
- [ ] **A3.** Додати **URL-кнопку** з `http(s)`-лінком. Спроба ввести `javascript:`/`data:`/`tg://` або без схеми →
      інлайн-валідація блокує. → AC: «URL-кнопка — на http(s)-посилання».
- [ ] **A4. (опційний таймаут)** Розгорнути таймаут: значення + одиниця (**MIN/HOUR/DAY**) + опційна ціль через пікер.
      Лишити порожнім → поля не відправляються (необмежене очікування). → AC: «Таймаут… не заданий → необмежено».
- [ ] **A5.** Зберегти → крок зʼявляється у списку з коректним MENU-summary (`FunnelStepsList`).
- [ ] **A6.** Активувати воронку → активується без помилки.
- [ ] **A7. (битий edge інлайн).** Націлити кнопку на крок → видалити цей крок → активувати →
      помилка `funnel_broken_edge` підсвічена **інлайн** біля кроку/кнопки, а **не** глобальним тостом.
      → AC: «активація з кнопкою на видалений крок → 422».
- [ ] **A8. (петля + fan-in у редакторі).** Зробити кнопку, що веде **назад** на те саме меню (петля), і **дві**
      кнопки на **один** крок (fan-in). Зберегти → обидва збереглися. → AC: «кнопка може вести назад / fan-in».

---

## B. Валідація активації (curl) — Task 4 / AVP кроки 3-4

> Покрито непрямо: `FunnelControllerIT.activateBrokenEdgeReturns422`, `activateMenuWithoutCallbackReturns422`.
> Жива перевірка бізнес-коду через реальний HTTP:

- [ ] **B1.** Активувати воронку з ціллю (кнопка/`next`/таймаут) на **видалений** крок:
      ```
      curl -X POST http://localhost:8080/api/projects/{projectId}/funnels/{funnelId}/activate \
        -b <auth-cookie> -H 'X-CSRF-Token: <csrf>'
      ```
      → **422**, тіло `{"code":"funnel_broken_edge", ...}`. → AC: broken-edge.
- [ ] **B2.** Активувати `MENU` **без жодної callback-кнопки** (лише URL або порожньо) → **422**.
      → AC: «MENU без callback-кнопки → 422».
- [ ] **B3.** Активувати MENU з таймаутом, де `timeoutUnit` ≠ MIN/HOUR/DAY або `timeoutValue` < 1 → **422**
      (закрита діра «застряглий execution»). → audit-fix F1.

---

## C. Симуляція callback_query на webhook + Mongo — Task 7 / Task 5 / AVP кроки 5-6

> Покрито непрямо: `FunnelExecutionEngineIT.advanceOnCallback_*` (IDOR/stale/malformed/oversized/no-PII),
> `ProcessTelegramUpdateJobTest` (event-before-flip). Жива перевірка через реальний webhook-ендпоінт:

Ендпоінт: `POST /webhooks/telegram/{projectId}`. Тіло — Telegram-update з `callback_query`
(`callback_data = "{executionId}:{buttonIndex}"`).

- [ ] **C1. Валідний клік.** Стартувати воронку (`/start`), дочекатися `MENU` (execution `waiting_for_reply`),
      надіслати валідний `callback_data`. Перевірити в Mongo:
      - колекція `events` має запис **`funnel_button_clicked`** (`funnelId`/`executionId`/крок/кнопка, **без PII**);
      - на execution оновлено **`lastButtonClicked`**, курсор зрушив у гілку. → AC: подія + правильна гілка.
- [ ] **C2. Застаріла кнопка.** Надіслати callback на execution, що вже пішов далі / `completed` / `cancelled` →
      execution **не зрушив**, `answerCallbackQuery` викликано (тихий no-op). → AC: застаріла кнопка.
- [ ] **C3. Malformed/oversized.** `callback_data` без `:`, з кількома `:`, не-hex executionId, index поза межами,
      >64 байт → **no-op + answerCallbackQuery**, нічого не зрушило. → user-spec «Ограничения».
- [ ] **C4. IDOR.** Підписник A підробляє `callback_data` з `executionId` підписника B →
      execution B **не зрушив** (mismatch subscriberId/projectId), без події. → Decision 6 / Risks.
- [ ] **C5. URL-кнопка.** Симулювати callback на index URL-кнопки → no-op (URL-кнопки не шлють callback;
      воронка не рушить). → AC: «натиск URL-кнопки не просуває воронку».

---

## D. Живий прохід у Telegram (тільки користувач) — user-spec «Пользователь проверяет»

> **Не покривається жодним автотестом** — реальна поведінка inline-клавіатур Telegram. Це головна ручна перевірка.

На тестовому боті:

- [ ] **D1.** `/start` → прийшло повідомлення `MENU` з inline-клавіатурою; видиме імʼя підставлене в текст
      (`{user.first_name}`). → AC: «/start → MENU з inline-клавіатурою; імʼя підставлене».
- [ ] **D2.** Натиснути callback-кнопку → **«годинник» (спіннер) зникає** і прийшла **правильна** гілка.
      → AC: answerCallbackQuery + правильна гілка.
- [ ] **D3.** Кнопка-**петля** (повернення на меню) → меню надіслалося **заново**; можна тиснути знову,
      без зависання. → AC: петля працює.
- [ ] **D4.** **URL-кнопка** → відкрився лінк, воронка **не зрушила**. → AC: URL не просуває.
- [ ] **D5.** **Подвійний** швидкий клік по одній кнопці → гілка виконалася **один раз** (другий клік ігнорується).
      → AC: подвійний клік → один раз.
- [ ] **D6.** **`/stop`** під час очікування меню → execution(и) у `waiting_for_reply` стали `cancelled`
      (наступний клік не просуває). → AC: `/stop` під час очікування.
- [ ] **D7. Заблокований бот.** Підписник блокує бота під час очікування → натиск кнопки → перша спроба send у гілці
      → execution `cancelled` (як у Фазі 1). → AC: blocked-bot.
- [ ] **D8. Snapshot-ізоляція.** Поки execution чекає на меню — відредагувати/перейменувати воронку → натиснути кнопку
      → execution дограє по **старому** графу. → AC: snapshot-ізоляція.
- [ ] **D9. Paused-drain.** Поставити воронку на `paused`, поки execution у `waiting_for_reply` → натиснути кнопку
      → execution **і далі просувається** до `completed` (нові `/start` не стартують). → AC: paused-drain.
- [ ] **D10. Таймаут.** Меню з заданим таймаутом → не відповідати → після дедлайну веде по цілі таймауту.
      Меню без таймауту → чекає необмежено. → AC: таймаут / необмежено.

---

## E. Регресія Фази 1 (наявні воронки)

> Покрито непрямо: `FunnelStepIdBackfillTest` (ідемпотентність), engine-ITs дренажу.

- [ ] **E1.** Лінійна воронка Фази 1, створена **до** фічі, після рестарту backend: усі кроки отримали `id`
      (backfill), in-flight лінійні runs **дограли** без помилок. → AC: «воронки Ф1 не ламаються».
- [ ] **E2. (runaway-guard).** Воронка з петлею **без** wait/delay/MENU між кроками → execution `failed`,
      у логах греппабельний маркер `FUNNEL_STEP_BUDGET_EXCEEDED`. → AC: петля без wait → ліміт рве цикл.

---

## Примітки

- Якщо локальне середовище (Mongo/Redis/backend) підняти неможливо — кроки B та C мають **непряме** покриття
  ITs (див. посилання), але **D обовʼязково ручний** (живий Telegram).
- Відомий флейк, не блокує: `TelegramWebhookP99IT.p99Latency_*` — cold-JVM perf-бенчмарк (p99 ~107ms vs 100ms SLA).
- Деталі реалізації — `decisions.md`; QA-звіт — `logs/working/task-11/qa-report.json`.
