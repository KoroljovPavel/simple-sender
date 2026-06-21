---
created: 2026-05-15
status: approved
type: feature
size: L
---

# User Spec: 07 — TelegramSender Service (Epic 04c)

## Що робимо

Створюємо `TelegramSender` — реактивний backend-сервіс для надсилання текстових повідомлень у Telegram. Один публічний метод: `sendText(botId, chatId, text, parseMode?, ownerId?)`. Sender завантажує bot doc, дешифрує токен, викликає Telegram Bot API з повною поведінкою (retry на transient помилки, 429 `retry_after`, маппінг помилок у доменні exceptions, overall timeout, audit events, scrubber токена в логах). Окремо вмикаємо реальну гілку у `BotService.sendTestMessage`: коли `Bot.ownerChatId` заданий — sender відсилає тест-повідомлення; коли ні — існуючий `422 owner_chat_id_unknown` зберігається без змін. Для цього додаємо nullable поле `ownerChatId` у `bots` doc (заповнюватиме Epic 04b webhook ingestion).

## Навіщо

`BotService.sendTestMessage` зараз — навмисний stub: повертає `422 owner_chat_id_unknown` із коментарем у коді, що success-гілка «arrive in 06b» (= цей епік). Funnels (Epic 06) і Broadcasts (Epic 07) теж блоковані відсутністю спільного outbound-примітиву. Без 04c кожен майбутній caller винаходив би заново WebClient + retry + rate-limit + error mapping — дублюючи патерни, що вже валідовані в `TelegramApiClient`. 04c — розблокувач для відкладеної success-гілки 04a і фундамент для 06/07.

## Як повинно працювати

**Сценарій 1 — Sender ізольовано (інфраструктура).**

1. Caller (BotService / майбутній FunnelStepExecutor / BroadcastWorker) викликає `sendText(botId, chatId, text, parseMode=null, ownerId=...)`.
2. Sender читає bot doc. Не знайдено або статус не `CONNECTED` → 404 (anti-enumeration, як `requireOwned` патерн).
3. Декриптує токен. Будь-яка помилка (corrupted ciphertext, неправильна довжина GCM tag, malformed shape після decrypt) → 422 `invalid_bot_token`.
4. POST на Telegram Bot API `sendMessage` із `{ chat_id, text, parse_mode? }`. Поле `parse_mode` додається лише коли заданий ненульовим аргументом (default — без parse_mode = Telegram plain text).
5. На transient помилку (Telegram 5xx, мережа, read timeout) — retry з exponential backoff: 3 retries після першого attempt = 4 total спроби, sleeps 1s/2s/4s. 4xx ніколи не retry.
6. На 429 — парсимо `parameters.retry_after`, чекаємо `retry_after` секунд + малий jitter, повторюємо. Не рахується в retry-cap. Реалізовано як окремий retry-loop, що обгортає 5xx-loop ззовні.
7. Overall timeout 30s на весь Mono — outer cap, що захищає від нескінченних 429 з великими `retry_after` (Telegram може повернути будь-яке значення). Перевищено → `TelegramSendException`.
8. Маппінг успіху і помилок:
   - 200 OK з `ok=true` → повертає `SentMessage(chatId, messageId, sentAt)` + audit event `telegram_message_sent`.
   - 200 OK з `ok=false` (рідко, але можливо) → `TelegramSendException` (description збережено, scrubbed).
   - 401 → `BotTokenInvalidException` → 422 `invalid_bot_token`.
   - Інші 4xx → `TelegramSendException` (зі збереженням `error_code` + scrubbed description) → 400 `telegram_send_failed`.
   - Після retry-exhaustion на transient → `TelegramSendException` → 400.
   - Overall 30s timeout → `TelegramSendException` → 400.
9. На terminal failure (будь-який кінцевий exception, окрім success) — audit event `telegram_send_failed` з `{ botId, chatId, errorCode?, errorDescription? (scrubbed), attempts }`.
10. Усі WARN/ERROR логи, що echo Telegram description, проходять через token scrubber (reuse helper, що вже існує в `TelegramApiClient`).

**Сценарій 2 — Test-message wiring у `BotService.sendTestMessage`.**

1. Користувач натискає «Send test message» у `Settings → Bot` (UI з 04a без змін).
2. Frontend → `POST /api/v1/projects/{projectId}/bot/test-message` (ендпоінт існує).
3. BotService отримує `ownerId, projectId, ip, userAgent`, викликає `requireConnectedBot`.
4. **Якщо `bot.ownerChatId == null`** → той самий `422 owner_chat_id_unknown` (stub зберігається верба́тім).
5. **Якщо `bot.ownerChatId != null`** → виклик `sendText(bot.id, bot.ownerChatId, "Hello from Bot Funnel Service! Bot connected ✅", parseMode=null, ownerId)`.
   - На success — додатковий audit event `bot_test_message_sent { projectId, telegramBotId, chatId, messageId }`; контролер повертає 200 OK без тіла.
   - На failure — exception propagates до GlobalErrorHandler; BotService **не** пише `bot_test_message_failed` (sender уже зафіксував `telegram_send_failed`).
6. У production `Bot.ownerChatId` лишається null до релізу Epic 04b — гілка з реальним send не виконується ніде, окрім інтеграційних тестів і ручного dev/staging smoke з ручним Mongo seed.

## Критерії приймання

**Sender — публічний контракт:**

- [ ] **AC1.** Бін `TelegramSender` має метод `sendText(botId, chatId, text, parseMode?, ownerId?) → Mono<SentMessage>`. `SentMessage` несе `chatId`, `messageId`, `sentAt`. Решта методів з 04c-notes (sendWithButtons, sendImage, editMessage, deleteMessage) — поза scope 04c.
- [ ] **AC2.** Bot не знайдений або статус ≠ `CONNECTED` → 404 з кодом `bot_not_found`. Антиенумераційний UX — однакова відповідь незалежно від причини (відсутність doc'а vs disconnected статус не розрізняються).
- [ ] **AC3.** Будь-яка помилка дешифрування або post-decrypt shape mismatch → `BotTokenInvalidException` → 422 `invalid_bot_token`.
- [ ] **AC4.** HTTP-запит до Telegram несе `chat_id` + `text` + (опційно) `parse_mode`. Поле `parse_mode` присутнє лише коли caller передав non-null (default Telegram plain text).
- [ ] **AC5.** WebClient timeouts: 5s на TCP-connect, 10s на response read. Reuse констант з `TelegramApiClient`.

**Retry та rate-limit:**

- [ ] **AC6.** Transient помилки (Telegram 5xx, мережа, read timeout) ретраяться з exponential backoff 1s → 2s → 4s; максимум 3 retries після першого attempt = 4 total спроби. 4xx ніколи не ретраяться.
- [ ] **AC7.** 429 → wait `parameters.retry_after` seconds + jitter, retry. Не рахується в retry-cap. Реалізовано як окремий retry-loop, що обгортає 5xx-loop ззовні (порядок підтверджено тестом на interleaving — див. AC19).
- [ ] **AC8.** Захист від великих/неприпустимих значень `retry_after`: якщо `retry_after` > 30s, missing або від'ємне — sender не намагається довше, ніж overall timeout. Overall timeout 30s на весь Mono — outer cap. Перевищено → `TelegramSendException` → 400 `telegram_send_failed`.

**Маппінг помилок:**

- [ ] **AC9.** 200 OK + `ok=false` (рідко, але Telegram so повертає) → `TelegramSendException` зі збереженою (scrubbed) description.
- [ ] **AC10.** 401 Unauthorized → `BotTokenInvalidException` (без retry) → 422 `invalid_bot_token`.
- [ ] **AC11.** Інші 4xx → `TelegramSendException` (без retry); response несе scrubbed Telegram `description`. На рівні HTTP — 400 `telegram_send_failed`.
- [ ] **AC12.** Retry-exhaustion або 30s timeout на transient class → `TelegramSendException` → 400.
- [ ] **AC13.** Три нові класи exception, усі extends `AppException`:
  - `TelegramSendException` (зовнішній, маппиться у 400 `telegram_send_failed`).
  - `BotTokenInvalidException` (зовнішній, маппиться у 422 `invalid_bot_token`, несе `botId` для логу).
  - `TelegramRateLimitException(retryAfterSeconds)` — **internal-only**, ловиться 429-loop'ом, ніколи не доходить до GlobalErrorHandler і не має registered HTTP mapping.

**Audit і логування:**

- [ ] **AC14.** Sender пише `telegram_message_sent { botId, chatId, messageId }` на success і `telegram_send_failed { botId, chatId, errorCode?, errorDescription? (scrubbed), attempts }` на terminal failure. `attempts` — загальна кількість HTTP-запитів до Telegram, що відбулись у межах виклику (включаючи 5xx-retry і 429-retry; перший attempt = 1). Events пишуться через `EventService.logEvent` із `userId = ownerId` (може бути null для system callers).
- [ ] **AC15.** Токен ніколи не з'являється в логах. Усі WARN/ERROR з Telegram description проходять через token scrubber (reuse `TelegramApiClient.scrubTokens`). Перевірено через `ListAppender`-асерції у тестах (AC20).

**Schema і інтеграція в BotService:**

- [ ] **AC16.** `bots` колекція отримує nullable поле `ownerChatId` (`Long`, без index, без міграційного скрипта). Існуючі doc'и читаються з `ownerChatId == null` — підтверджений прецедент: `disconnectedAt`.
- [ ] **AC17.** `BotService.sendTestMessage` переписано:
  - `bot.ownerChatId == null` → той самий `422 owner_chat_id_unknown` верба́тім (stub збережено).
  - `bot.ownerChatId != null` → sender викликаний; success → 200 OK + додатковий event `bot_test_message_sent { projectId, telegramBotId, chatId, messageId }`; failure → exception propagates без дублювання event'у (sender уже зафіксував).
  - HTTP контракт BotController не змінюється (`ResponseEntity<Void>` 200 OK).
- [ ] **AC18.** SecurityConfig без змін — endpoint уже покритий правилом authenticated для `/api/**`.

**Тестування:**

- [ ] **AC19.** Unit-тести (MockWebServer, без Spring context) покривають: success, retry-then-success на 5xx, retry-exhausted → `TelegramSendException`, 429 з `retry_after` (нормальне, велике, missing, від'ємне), 401 → `BotTokenInvalidException`, інші 4xx → `TelegramSendException`, `ok=false` → `TelegramSendException`, malformed token → `BotTokenInvalidException` до HTTP-виклику, overall 30s timeout. Окремий тест на 429+5xx interleaving: scripted послідовність `[5xx, 429(retry_after=1), 5xx, 200]` має завершитися success при загальній кількості attempts=4 і обидва retry-cap'и не вичерпані одне одним.
- [ ] **AC20.** «Token never in logs» — інтеграція з `ListAppender<ILoggingEvent>` (mirror існуючого pattern з `TelegramApiClientTest`): після кожного error-сценарію перевіряємо, що жодна log line не містить токен.
- [ ] **AC21.** Integration test (`TelegramSenderIT`, повний Spring context, embedded Mongo, MockWebServer for Telegram): end-to-end через `BotRepository` + `TokenEncryptor` + `EventService`. Awaitility-асерція на async event write.
- [ ] **AC22.** Існуючий тест `BotServiceTest.sendTestMessage_returns422_noTelegramCalls_noEvents` розбито на дві гілки: `ownerChatId=null` (422 зберігається, sender не викликаний) + `ownerChatId=set` (sender викликаний, success → event `bot_test_message_sent`).
- [ ] **AC23.** Існуючий `BotControllerIT` test, що асертить 422 для test-message, лишається як null-branch case; додано positive-path test, що сидить `ownerChatId` напряму в Mongo і асертить 200 OK + event.

## Обмеження

**Технічні:**
- HTTPS only для Telegram API (`https://api.telegram.org`).
- Реактивно, без блокувань — повертаємо `Mono<SentMessage>`.
- WebClient timeouts: connect 5s, read 10s.
- Retry — лише на transient (5xx + мережа + read timeout). 4xx ніколи не retry.
- Overall 30s timeout на весь Mono.
- Token decrypted per-call (без кешу в MVP — додамо коли Epic 07 broadcast load покаже need).
- Усі нові exceptions (зовнішні два) extends `AppException` для уніфікованого mapping у `GlobalErrorHandler`. `TelegramRateLimitException` — internal-only, без HTTP mapping.
- Audit events fire-and-forget (без flush guarantee — acceptable; matches `bot_connected/bot_disconnected` precedent).
- Test isolation: okhttp3 MockWebServer (вже в `build.gradle` як testImplementation); без живого Telegram у CI.

**Scope (свідомо відкладено):**
- `sendWithButtons`, `sendImage`, `editMessage`, `deleteMessage` — додаємо коли з'явиться реальний caller (Epic 06/07).
- Subscriber-specific exceptions (`SubscriberBlocked/Deleted`) — Epic 05.
- Bot status зміна на 401 (UI banner, auto-disconnect) — у 04c лише `throw BotTokenInvalidException`, реакція пізніше.
- Token decrypt cache — Epic 07 broadcast.
- Webhook ingestion і реальне заповнення `Bot.ownerChatId` — Epic 04b. У production happy-path test-message спрацьовує тільки після 04b.

## Ризики

- **R1 — Sender exercising у production неможливе до 04b.** `Bot.ownerChatId` лишається null без webhook ingestion. **Митигація:** покриваємо integration tests із seeded Mongo + явно прапоримо залежність у tech-spec verification.
- **R2 — Promotion двох private helper'ів у `TelegramApiClient` до public static** (token shape guard + transient classifier) розширює публічний surface area класу. **Митигація:** signature незмінна, коментар «promoted for TelegramSender reuse»; recompile-check на call sites; mirror того, як уже зроблено для `scrubTokens`.
- **R3 — Retry-loop ordering: 429 outer / 5xx inner.** Неправильний порядок призводить до того, що 5xx retry-cap вичерпується під час `retry_after` wait. **Митигація:** explicit unit test з послідовністю `[5xx, 429(retry_after=1), 5xx, 200]`, що асертить attempts=4, success, обидва cap'и не вичерпані. Покрито AC19.
- **R4 — Worst-case wall-clock виклику.** Максимальний retry budget (4 спроби × до 10s read + 1s+2s+4s backoff ≈ до 47s) перевищує overall timeout 30s; timeout — outer cap, який спрацює першим. Це навмисно — frontend default timeout (`ofetch`) ≈35s; sender фейлиться раніше, ніж фронт думає, що зависло. **Митигація:** explicit `.timeout(30s)` як єдиний effective ceiling; retry budget — теоретичний максимум, на практиці не досягається.
- **R5 — `EventService.logEvent` fire-and-forget.** terminal-failure event може не flush до process death. **Митигація:** acceptable (matches `bot_connected/bot_disconnected`); document у tech-spec, не gate.
- **R6 — `Bot.ownerChatId` без migration script.** **Митигація:** Spring Data MongoDB читає відсутні поля для object types як null; precedent — `disconnectedAt`.
- **R7 — Tampered Mongo storage / corrupted ciphertext → `BotTokenInvalidException` → 422.** UI виглядає як «user-fixable», але root cause — ops. **Митигація:** WARN-level log зі scrubbed context для ops review.
- **R8 — `ownerId == null` для майбутніх Epic 06/07 workers** знижує observability в `telegram_message_sent`. **Митигація:** out of scope для 04c; флагуємо в tech-spec «Epic 06/07 may want a system-context sentinel».
- **R9 — Немає per-bot concurrency fairness.** Дві одночасні `sendText`-операції на той самий bot decryptяться і відсилаються паралельно — Epic 07 broadcast hot path може потребувати throttle (Telegram bot-level ~25 msg/sec). **Митигація:** out of scope для 04c (тільки один синхронний caller `sendTestMessage`); Epic 07 додасть per-bot rate limiter (Reactor `Flux.delayElements` або semaphore) на send-pipeline.

## Технічні рішення

- **Один метод `sendText` у MVP.** Решта 4 методів відкладено — у 04c немає жодного caller'а, який би їх викликав. YAGNI.
- **Без кешу дешифрованого токена.** Premature; перегляньмо коли Epic 07 broadcast покаже навантаження.
- **Audit events пише sender.** Гарантований audit trail незалежно від caller'а; кожен caller додає власні доменні events поверх (наприклад, `bot_test_message_sent`).
- **`ownerId` як опціональний параметр.** Caller передає контекст; `null` — для system context (Epic 06/07).
- **Exceptions extends `AppException`.** Single mapping point у `GlobalErrorHandler`; consistent з прецедентом `bot/` модуля.
- **Default `parse_mode = null`** (Telegram plain text). Безпечніший default — `HTML` за замовчуванням створює injection-ризик для майбутніх user-content caller'ів; HTML як opt-in.
- **Sender відмовляється від DISCONNECTED ботів (404).** Anti-enumeration; ніякої implicit re-validation.
- **Без pre-validation довжини тексту.** Telegram — джерело істини; sender тонкий шар.
- **Overall 30s timeout як outer cap.** Прогнозований worst-case; узгоджено з frontend timeout; виконує роль єдиного effective ceiling над теоретичним retry budget.
- **`TelegramRateLimitException` — окремий internal клас, не sentinel.** Type-safe filter у retry loop; describes intent.
- **Окремий record `TelegramSendResult<T>` для парсингу 429 `parameters.retry_after`** (не розширюємо `TelegramResult<T>`). Sender-only тип; `TelegramApiClient` лишається недоторканим — нижчий blast radius.
- **Окремий бін `TelegramSender`, а не додавання `sendMessage` у `TelegramApiClient`.** `TelegramApiClient` — для bot-connection flow (`getMe`, `setWebhook`, `deleteWebhook`); його retry policy (200ms/400ms/2s) і semantic відрізняються від sender'а. Розділення дозволяє різні timeouts/retry policies/audit events без розмивання semantic одного класу.
- **Retry ladder sender'а 1s/2s/4s vs `TelegramApiClient` 200ms/400ms/2s.** Connect path — швидкий, бо користувач чекає у формі; send path — допускає довші паузи, бо Telegram потребує часу на recovery. Це різні UX-вимоги до однієї external API.

**Note про розмір:** оголошено `size: L` — 23 AC + 6 integration points виходять за пороги M, а сам обсяг роботи (новий reactive бін з повним retry/backoff/timeout/error-mapping/audit policy + три exception класи + schema-зміна `bots.ownerChatId` + переписаний BotService stub + новий integration test) ближче до Large. Прецедент `06-bot-connection` (size L, 23 AC) — суміжна інфра-фіча з тим самим рівнем деталізації.

## Тестування

**Unit-тести:** робимо завжди.

**Інтеграційні тести:** робимо — основний рівень верифікації для sender'а. MockWebServer (okhttp3) script'ить усі Telegram сценарії (success, 5xx retry, 429 з різними `retry_after`, 401, інші 4xx, `ok=false`, network timeouts, interleaving 429+5xx). Awaitility перевіряє async event writes. `BotControllerIT` додає positive-path test з seeded `ownerChatId`.

**E2E тести:** не робимо. Немає живого Telegram у CI, немає нового UI flow у 04c (button «Send test message» з 04a лишається той самий і поводиться так само в production, поки 04b не приземлить `ownerChatId`).

## Як перевірити

### Агент перевіряє

| Крок | Інструмент | Очікуваний результат |
|-----|-----------|-------------------|
| 1. `./gradlew test` | Bash | Усі unit + integration тести зелені (включно з новими `TelegramSenderTest`, `TelegramSenderIT` + оновленими `BotServiceTest`, `BotControllerIT`). |
| 2. `ListAppender` асерції на token-leak у логах (всередині тестів) | Інтеграція | Жодна log line не містить токен ні в WARN, ні в ERROR. |
| 3. `curl -i -b session=... -X POST http://localhost:8080/api/v1/projects/{id}/bot/test-message` (без seeded `ownerChatId`) | Bash + bootRun | `422 owner_chat_id_unknown` — stub поведінка збережена. |
| 4. mongosh `db.bots.updateOne({_id: ObjectId(...)}, {$set: {ownerChatId: <numeric>}})` + повтор curl з кроку 3 | Bash | `200 OK`. У Mongo events: `telegram_message_sent` + `bot_test_message_sent`. У Telegram (якщо chatId реальний) — повідомлення «Hello from Bot Funnel Service! Bot connected ✅». |
| 5. SecurityConfig перевірка | grep `pathMatchers` у `SecurityConfig.java` | Жодних змін щодо `/api/v1/projects/**/bot/test-message`. |
| 6. Перевірка nullable schema на pre-existing документі | mongosh `db.bots.findOne({status: "CONNECTED"})` | `ownerChatId` читається як `null` без помилок десеріалізації. |

### Користувач перевіряє

- **Реальна доставка в Telegram (опціонально, dev/staging).** Зайди в БД, постав свій `ownerChatId` (отримати від `@userinfobot` у Telegram) на свій підключений бот, клікни «Send test message» в UI → перевір, що повідомлення прийшло в Telegram. Це разовий exploratory smoke; повноцінний flow без mongosh з'явиться в Epic 04b runbook.
