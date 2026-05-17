# 08-webhook-ingestion — Staging Smoke Runbook (Manual, ~10–15 хв)

Ручна процедура перевірки inbound-webhook-контуру на staging після
деплою фічі `08-webhook-ingestion`. Оператор працює з одноразовим
ботом у `@BotFather`, ngrok-тунелем (для локального staging) або
безпосередньо staging-URL (для повного staging-деплою) і `mongosh`.

Цей runbook — єдиний пост-деплой verification gate для фічі: реальний
Telegram-сервер плюс `setWebhook`-flow неможливо емулювати в CI
(Decision 19 / user-spec line 199 — "Зачем руками"). Runbook покриває
happy path AC1, AC5 (опосередковано), AC6, AC7, AC8, AC17. Інші AC
закриті інтеграційними тестами (`*ControllerIT`, `*JobTest`) — див.
секцію **Чек-ліст результатів**.

Мова виконання: всі команди + назви UI-елементів / полів — англійською;
коментарі — українською.

## Передумови

- Staging URL бекенду (`APP_URL`) досяжний і `GET {APP_URL}/health`
  повертає `HTTP 200`. Якщо ви тестуєте локальний staging — підніміть
  ngrok-тунель (крок 2 нижче), і `APP_URL` буде HTTPS-URL від ngrok.
- Staging frontend задеплоєний і вказує на той самий бекенд.
- Staging-аккаунт-адміністратор / test-owner із принаймні одним
  проектом без активного `CONNECTED`-боту (або з вільним слотом).
- Ваш персональний Telegram-аккаунт із доступом до `@BotFather` і
  можливістю надсилати команди у чат з ботом.
- `mongosh` з доступом до staging Mongo (db: `botfunnel`). Connection
  string береться зі staging secret store — НЕ вставляйте його в чат,
  коміти або скріншоти. У командах нижче використовується плейсхолдер
  `mongodb://…/botfunnel`.
- `ngrok` встановлений локально (`brew install ngrok` на macOS).
  Безкоштовний акаунт достатній, але URL — session-scoped: при
  переконнекті ngrok URL змінюється, і крок **Підключити бот до
  проекту** треба повторити.

## Кроки

### 1. Створити одноразовий бот у `@BotFather`

1. У Telegram відкрийте діалог з `@BotFather` і надішліть `/newbot`.
2. Введіть display name (наприклад `SmokeTest_<initials>_<date>`) і
   username, що закінчується на `_bot` (наприклад
   `SmokeTest_pk_20260517_bot`).
3. Скопіюйте токен виду `1234567890:AAH...` (~45 символів). Це
   throwaway-токен — після завершення runbook бот видаляється
   (`/deletebot`). НЕ вставляйте токен у чат / коміти / скріншоти.

**Якщо щось пішло не так:** username вже зайнятий → виберіть інший із
суфіксом `_bot`. BotFather не відповідає → перевір, що ви у правильному
діалозі (`@BotFather`, не `BotFather`-twin), і виключи мережеві блоки.

### 2. Підняти ngrok-тунель (тільки для локального staging)

```bash
ngrok http 8080
```

ngrok виведе HTTPS-URL виду `https://<hash>.ngrok-free.app`.
Скопіюйте — це ваш `APP_URL` на наступному кроці. Якщо ви тестуєте
повністю-задеплоєний staging — цей крок пропускайте і використовуйте
staging-URL напряму.

**Якщо щось пішло не так:** `ngrok: command not found` →
`brew install ngrok` (macOS) або скачайте з ngrok.com. ngrok вимагає
acuthtoken на безкоштовному плані — налаштуйте через
`ngrok config add-authtoken <token>` (один раз).

### 3. Підключити бот до проекту

1. Залогіньтеся як test-owner у staging-frontend.
2. Відкрийте **Projects → <ваш-проект> → Settings → Bot**.
3. Якщо у проекті вже є `CONNECTED`-бот — натисніть **Disconnect**
   (підтвердіть у модалці), щоб звільнити слот.
4. У формі **Connect** вставте токен з кроку 1, натисніть **Connect**.
5. Протягом ~5 секунд UI має замінити форму на **Connected view** із
   username бота, masked-token (`{botId}:•••...{suffix}`), кнопками
   **Send Test Message** і **Disconnect**.

UI-flow одночасно викликає Telegram `setWebhook`. Перевір це у
наступному кроці.

**Якщо щось пішло не так:** `errors.bot.connect.409` →
бот вже підключений до іншого проекту (платформо-широка унікальність,
runbook 06 крок 7). `errors.bot.connect.422 invalid_token` →
перевір, що скопіювали повний токен без пробілів. Connect crashes →
перевір логи staging — найімовірніше Mongo або Redis недоступні.

### 4. Перевірити, що webhook зареєстровано у BotFather

У `@BotFather`: `/mybots` → виберіть свого бота → **Bot Settings →
Webhook**. У полі URL має бути `{APP_URL}/webhooks/telegram/{projectId}`
(точна відповідність). Якщо поле порожнє або вказує на інший хост —
крок 3 не відпрацював, перевір логи staging і повтори.

### 5. Надіслати `/start ref_smoke_001` зі свого Telegram

1. У Telegram знайдіть бота за `@<username>` із кроку 1.
2. Натисніть **Start** (або введіть `/start ref_smoke_001` вручну).
3. Бот не відповість (ще немає реактивної логіки на цьому етапі) —
   це очікувано; ми перевіряємо лише inbound-контур.

### 6. Перевірити `raw_updates` у Mongo

```bash
mongosh mongodb://…/botfunnel --eval \
  'db.raw_updates.find({}, {projectId: 1, updateId: 1, processingStatus: 1, createdAt: 1}).sort({createdAt: -1}).limit(1).toArray()'
```

Очікуваний вивід — один документ:

- `projectId` дорівнює `_id` вашого тестового проекту.
- `updateId` — додатне число (присвоєне Telegram).
- `processingStatus` — `"DONE"` (worker встиг обробити). Якщо бачите
  `"PENDING"` — повторіть запит через 2–3 секунди; якщо
  `"FAILED"` — перейдіть до секції **Перевірити Failed-queue**.
- `createdAt` — ISO timestamp у межах останньої хвилини.

### 7. Перевірити events: `telegram_command_start` із `metadata.startPayload`

```bash
mongosh mongodb://…/botfunnel --eval \
  'db.events.find({eventType: "telegram_command_start"}).sort({createdAt: -1}).limit(1).toArray()'
```

Очікуваний документ:

- `eventType` — `"telegram_command_start"`.
- `metadata.startPayload` — `"ref_smoke_001"` (точна відповідність до
  payload з кроку 5, AC7).
- `metadata.chatType` — `"private"` (AC7).
- `createdAt` — ISO timestamp у межах останньої хвилини.

### 8. Перевірити, що `bot.ownerChatId` встановлено

Спочатку дістаньте `_id` бота:

```bash
mongosh mongodb://…/botfunnel --eval \
  'db.bots.findOne({projectId: "<projectId>", status: "CONNECTED"}, {_id: 1, telegramBotId: 1, ownerChatId: 1})'
```

Очікуваний вивід:

- `ownerChatId` — `Long("<ваш-numeric-chat-id>")` (НЕ `null`, НЕ
  строка). Це закриває AC6 / AC17 happy path: первинний `/start` у
  private-chat атомарно populated `Bot.ownerChatId`.

Запам'ятайте `_id` для кроку 9.

**Якщо щось пішло не так:** `ownerChatId: null` →
worker не відпрацював; перевір логи staging для `ProcessTelegramUpdateJob`
і JobRunr Failed-queue (секція нижче). Якщо два оператори запускають
runbook паралельно на одному проекті — другий `/start` не populated
`ownerChatId` за атомарним предикатом (AC6); координуйтесь через Slack
або використовуйте окремий BotFather-бот.

### 9. Натиснути "Send Test Message" в адмінці

1. Поверніться у frontend, **Settings → Bot**.
2. Натисніть **Send Test Message**.
3. Запит `POST {APP_URL}/api/v1/projects/{projectId}/bot/test-message`
   має повернути `HTTP 200` (з network-tab браузера).
4. У вашому Telegram-чаті з ботом має з'явитися повідомлення
   `Hello from Bot Funnel Service! Bot connected ✅` протягом ~5 секунд.

Це закриває 04c "Send test message" gap: до 04b у production цей
branch був unobservable, бо `ownerChatId` ніким не виставлявся.

**Якщо щось пішло не так:** `422 owner_chat_id_unknown` →
крок 8 не відпрацював, повторіть кроки 5–8. `200`, але повідомлення не
дійшло → `db.events.find({eventType: "telegram_send_failed"}).sort({createdAt:-1}).limit(1)`
і подивіться на `metadata.errorCode` / `metadata.errorDescription`.

### 10. Надіслати `/stop` зі свого Telegram

1. У тому самому чаті з ботом надішліть `/stop`.
2. Через 1–3 секунди перевірте:

```bash
mongosh mongodb://…/botfunnel --eval \
  'db.events.find({eventType: "telegram_command_stop"}).sort({createdAt: -1}).limit(1).toArray()'
```

Очікуваний документ:

- `eventType` — `"telegram_command_stop"`.
- `metadata.chatType` — `"private"`.
- `createdAt` — ISO timestamp у межах останньої хвилини.

Це закриває AC8: `markUnsubscribed` + `cancelActiveFor` викликалися
(на стабах), плюс event-аудит виписався.

### 11. Перевірити Failed-queue (у разі підозри на помилку)

Worker exception → row `raw_updates` має `processingStatus="FAILED"` +
непустий `processingError` (scrubbed token, ≤1024 chars; Decision 9).
JobRunr окремо тримає список failed-job-ів у власній колекції.

```bash
# Raw-update side
mongosh mongodb://…/botfunnel --eval \
  'db.raw_updates.find({processingStatus: "FAILED"}, {projectId: 1, updateId: 1, processingError: 1, createdAt: 1}).sort({createdAt: -1}).limit(5).toArray()'

# JobRunr side — спочатку дізнайтеся точну назву колекції
mongosh mongodb://…/botfunnel --eval 'db.getCollectionNames()' | grep jobrunr

# JobRunr дефолт — `jobrunr_jobs`; перевір актуальну назву через попередню команду
mongosh mongodb://…/botfunnel --eval \
  'db.jobrunr_jobs.find({state: "FAILED"}, {jobName: 1, exceptionMessage: 1, updatedAt: 1}).sort({updatedAt: -1}).limit(5).toArray()'
```

`processingError` має бути scrubbed (без bot-token: substrings, без
повних stack-trace-ів) і truncated до ≤1024 chars. Якщо бачите токен у
`processingError` — це регресія security M6, відкривайте bug негайно.

**Подальші кроки при FAILED:** скопіюйте `processingError` +
`exceptionMessage` (без чутливих даних) у bug-репорт, citing AC14.

### 12. Очистити

1. У frontend **Settings → Bot → Disconnect** (підтвердіть у модалці).
   Disconnect-flow видаляє webhook через Telegram `deleteWebhook` і
   зануляє `encryptedTokenCiphertext`/`webhookSecretHash` на запису.
2. У BotFather: `/deletebot` → виберіть свого `SmokeTest_…_bot` →
   підтвердьте.
3. Якщо піднімали ngrok — `Ctrl+C` у терміналі з ngrok.

## Чек-ліст результатів

Кожен пункт нижче має бути `[x]` перед тим, як runbook вважається
"08 OK". Якщо хоч один пункт не зійшовся, файліть bug із посиланням
на AC і не promoute до production.

- [ ] **AC1** — `POST /webhooks/telegram/{projectId}` повернув `200`
      на `/start` (крок 5 → `raw_updates` row створено у кроці 6).
- [ ] **AC5** (опосередковано) — повторне Telegram retry на той самий
      `update_id` НЕ створило другий row (немає race-overlap у
      `raw_updates`; гарантовано unique-index, відлагоджується в
      `TelegramWebhookControllerIT`).
- [ ] **AC6** — `bot.ownerChatId` populated після першого приватного
      `/start` (крок 8).
- [ ] **AC7** — event `telegram_command_start` із `metadata.startPayload="ref_smoke_001"`
      і `metadata.chatType="private"` (крок 7).
- [ ] **AC8** — `/stop` створив event `telegram_command_stop`
      (крок 10).
- [ ] **AC17** — `Bot.ownerChatId` зчитується/пишеться без міграції
      (крок 8 + крок 9 success).

**Покрито інтеграційними тестами, НЕ цим runbook (не повторюйте їх
руками):**

- **AC2** — bad/missing secret → `401` (`TelegramWebhookControllerIT`).
- **AC3** — soft-deleted / unknown `projectId` → `404`
  (`TelegramWebhookControllerIT`).
- **AC4** — payload > 1 MB → `413` (`WebhookPayloadSizeFilterIT`).
- **AC11** — non-message updates (`callback_query`, `edited_message`,
  `channel_post`, `my_chat_member`) → event `telegram_update_received`
  із `metadata.updateKind=<имя поля>` (`ProcessTelegramUpdateJobTest`).
- **AC11a** — update без `message` / із `from==null` → event без crash
  (`ProcessTelegramUpdateJobTest`).
- **AC14** — worker exception → `raw_updates.processingStatus=FAILED`
  (`ProcessTelegramUpdateJobTest`).
- **AC15** — SecurityConfig `permitAll` тільки для webhook-шляху
  (`SecurityConfigIT`).
- **AC16** — Micrometer counters інкрементуються
  (`TelegramWebhookControllerIT`).
- **AC18** — JobRunr deterministic UUID + idempotent enqueue
  (`TelegramWebhookControllerIT`).
- **AC19** — re-entry guard у worker (`ProcessTelegramUpdateJobTest`).

## Sign-off

Кожен пункт чек-ліста результатів має бути `[x]` перед промоушеном на
production. У разі провалу будь-якого кроку — bug із посиланням на AC,
номер кроку runbook, observed vs expected.

## Notes

- Runbook **manual-only** — реальний Telegram + `@BotFather` не
  емулюються агентом, не намагайтеся автоматизувати.
- ngrok URL — session-scoped: переконект ngrok = повторити крок 3
  (Connect-flow перезапише webhook на свіжий URL).
- Паралельні оператори на одному проекті: другий `/start` не populated
  `ownerChatId` (атомарний CAS-предикат AC6). Використовуйте окремі
  BotFather-боти або координуйтесь через Slack.
- Throwaway-bot після кроку 12 повністю видалений; `bots`-document із
  `status: "DISCONNECTED"` залишається в Mongo як append-only audit
  (Decision 17 у Epic 04a).
- НЕ вставляйте у чат / коміти / скріншоти: токен бота, staging
  `mongosh` connection string, реальні numeric chat ids, JWT-куки.
