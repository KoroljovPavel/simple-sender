# 04b — Webhook Ingestion

**Статус:** notes для майбутнього user-spec (раніше частина `04-телеграм-боти/README.md` MUST HAVE).

## Що входить

Прийом вхідних повідомлень від Telegram + швидке persisting + асинхронна постановка в чергу. Це **інфраструктурний** шар: бізнес-логіка (subscriber upsert, funnel triggers) — інтеграційні точки в інші епіки, не реалізуємо тут.

### Endpoint
- `POST /webhooks/telegram/{projectId}` приймає updates
- Перевірка secret token у заголовку `X-Telegram-Bot-Api-Secret-Token` проти `bots.webhookSecret` цього `projectId`
- CSRF disabled для цього шляху (patterns.md: "CSRF disable для truly stateless endpoints — webhooks")
- SecurityConfig: `pathMatchers("/webhooks/telegram/**").permitAll()` (поставити ПЕРЕД `/api/**` rule)
- Парсинг update: `message`, `callback_query` (мінімум для MVP)
- Швидке збереження raw update у MongoDB колекцію `raw_updates` + постановка JobRunr-задачі для асинхронної обробки
- Відповідь Telegram-серверу `200 OK` за < 100ms (SLA — verifiable у тестах)

### Idempotency
- Повторне отримання того самого `(projectId, telegram_update_id)` ігнорується
- Реалізація: unique index `(projectId, updateId)` на `raw_updates`; на duplicate key insert — silent 200 OK, без enqueue
- Не використовуємо Redis для дедуплікації (MongoDB має durable storage; Redis у нас і так є, але це окремий компонент failure mode)

### Worker (асинхронна обробка)
- JobRunr-задача `ProcessTelegramUpdateJob(rawUpdateId)`
- Викликає **інтеграційні точки** (заглушки в 04b, реалізація в 05/06):
  - `SubscriberService.upsertFromTelegramUpdate(projectId, update)` — повертає `Subscriber` (Epic 05 деталізує)
  - `FunnelTriggerService.fire(projectId, subscriber, trigger, payload)` — Epic 06 деталізує
- Зберігає подію в `events` (eventType `telegram_message_received`, `telegram_command_start`, `telegram_command_stop`)
- JobRunr distributed lock покриває multi-instance race — додаткова логіка не потрібна
- Failure mode: job retry by JobRunr (built-in exponential backoff); після N невдач — лог + DLQ-флаг у `raw_updates.processingError`

### Команди /start, /stop
- **Парсер команд** повертає `(command, payload)`:
  - `/start` → `("/start", "")`
  - `/start ref_123` → `("/start", "ref_123")`
  - `/stop` → `("/stop", "")`
- `payload` зберігається в `events.metadata.startPayload` для майбутнього deep-linking + attribution (див. рішення в чаті)
- Реальна логіка:
  - `/start` → виклик `SubscriberService.upsertFromTelegramUpdate` + `FunnelTriggerService.fire("on_start", payload)`
  - `/stop` → виклик `SubscriberService.markUnsubscribed` + `FunnelTriggerService.cancelActiveFor(subscriber)`
- У межах 04b ці виклики — заглушки/інтерфейси з no-op реалізацією, реальна логіка — Epic 05/06

### Storage / retention
- `raw_updates` — TTL index `createdAt` 90 днів (MongoDB native TTL, не cleanup job)
- `events` — без TTL (audit log, керується HardDeleteJob на рівні проекту)

## Gaps для інтерв'ю

1. **Помилка `webhook_secret_invalid` — що повертаємо Telegram?** 401 чи 200 з тихим дропом? Telegram при 4xx ретраїть; рекомендую 401, але без тіла (no leakage).
2. **Webhook payload size limits.** Telegram updates можуть містити великі поля (`message.text` до 4096 chars, media URLs, callback_query). Reactor WebFlux default — налаштувати `maxInMemorySize` для тіла? Робимо явний ліміт ~1 MB?
3. **`callback_query` без message context.** Сценарій: бот видалив повідомлення → callback приходить без `message`. Парсимо graceful чи дропаємо?
4. **DLQ для failed jobs.** Якщо `ProcessTelegramUpdateJob` падає N разів — куди йде? Просто `raw_updates.processingStatus=failed` + admin alert? Окрема колекція?
5. **Worker concurrency.** JobRunr default — скільки воркерів? Telegram нам шле updates послідовно для одного chat, але паралельно для різних. Чи треба per-chat ordering всередині нашого worker'а? (Рекомендую — ні, кожен update самостійний; впорядкованість гарантується Telegram-ом.)
6. **`update_id` overflow / wraparound.** Telegram гарантує монотонність? (Так — per-bot.) Чи unique index по `(projectId, updateId)` достатньо?
7. **Race: webhook прийшов, projectId існує, але бот вже disconnected.** 401 secret invalid (бо `webhookSecret` стерли) — це наш кейс. Перевірити, що delete order правильний (спочатку `deleteWebhook` у Telegram, потім стираємо secret).
8. **Project soft-deleted.** Webhook для soft-deleted проекту — який response? 410 Gone? 404? Чи продовжуємо приймати (для audit, але без обробки)?
9. **Local dev.** Без HTTPS Telegram webhook не зробити. Інструкція: ngrok + `APP_URL=https://*.ngrok-free.app`. (Або деплоїти в staging для тестів.)
10. **Metrics.** Кількість updates/sec, P99 latency, error rate, queue depth — додаємо в micrometer? (`telegram_webhook_received_total{projectId}`, `telegram_webhook_duration_seconds`.) Епік 09 — analytics; тут лише оголошуємо counter'и.

## Викинути (свідомо)

- Polling-режим (long-polling) — інструкції з ngrok достатньо
- Парсинг `inline_query`, `chosen_inline_result`, `shipping_query`, `pre_checkout_query`, `poll`, `poll_answer` — не входить у MVP (1-on-1 DM only)
- Підтримка груп/каналів — `12-nice-to-have`

## Перенесено в інші підзадачі

- **TelegramSender (відправка)** → `04c-telegram-sender`
- **Підключення/налаштування webhook'а в Telegram** → `04a-bot-connection`
- **Реальний upsert Subscriber** → Epic 05
- **Реальний funnel trigger** → Epic 06

## Залежності

- 04a-bot-connection (`bots.webhookSecret` має існувати; endpoint валідує проти нього)
- 03-projects (validate `projectId` exists, not soft-deleted)
- Epic 05 (інтеграційна точка `SubscriberService.upsertFromTelegramUpdate` — заглушка тут, реальність там)
- Epic 06 (інтеграційна точка `FunnelTriggerService.fire` — заглушка тут, реальність там)

## Acceptance criteria (preview, фіналізуємо в інтерв'ю)

1. `POST /webhooks/telegram/{projectId}` з валідним secret token приймає update і повертає `200 OK` за < 100ms (P99)
2. Запит без / з неправильним `X-Telegram-Bot-Api-Secret-Token` отримує `401` без тіла
3. Той самий `(projectId, update_id)` отриманий двічі → один запис у `raw_updates`, один job, один event
4. Raw update з'являється в `raw_updates` колекції з повним payload (без редагування)
5. JobRunr task `ProcessTelegramUpdateJob` ставиться в чергу і виконується (інтеграція з заглушкою-`SubscriberService` — fake реалізація, що повертає mock subscriber)
6. `/start ref_123` → парсер виділяє `payload="ref_123"`, зберігається в `events.metadata.startPayload`
7. `/stop` → виклик заглушки `SubscriberService.markUnsubscribed` + заглушки `FunnelTriggerService.cancelActiveFor` (verifiable through interaction logs)
8. TTL index на `raw_updates.createdAt` встановлено на 90 днів
9. Webhook на soft-deleted або hard-deleted проект → відповідь `404` (без leakage), без enqueue
10. SecurityConfig: `pathMatchers("/webhooks/telegram/**").permitAll()` ПЕРЕД `/api/**`; CSRF disabled тільки для `/webhooks/telegram/**`
