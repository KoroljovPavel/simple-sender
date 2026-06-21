# 04a — Bot Connection

**Статус:** notes для майбутнього user-spec (раніше частина `04-телеграм-боти/README.md` MUST HAVE).

## Що входить

Перша і фундаментальна підзадача Epic 04 — без під'єднаного бота решта (webhook, sender) не має сенсу.

### Підключення
- Сторінка `Settings → Bot` у проекті (project-scoped: `/projects/{projectId}/settings/bot`)
- Поле "Telegram Bot Token" + кнопка "Connect"
- При збереженні:
  1. Виклик `getMe` через Telegram API для валідації токена
  2. Якщо успіх → зберігаємо токен (зашифрованим at-rest, AES-256-GCM, ключ `BOT_TOKEN_ENCRYPTION_KEY` з env), показуємо `@username`, ім'я бота, аватар
  3. Якщо помилка → зрозуміле пояснення (`token_invalid`, `token_revoked`, `network`)
- **Автоматичне налаштування webhook'а** на `{APP_URL}/webhooks/telegram/{projectId}` через `setWebhook` із secret token у заголовку
- Перевірка унікальності: один і той же бот не може бути підключений у двох проектах одночасно (unique index по `bots.telegramBotId` серед `disconnectedAt IS NULL`, по аналогії з паттерном "Service-level uniqueness pre-check" з `patterns.md`)

### Відключення / переконфігурація
- Кнопка "Disconnect bot":
  - Виклик `deleteWebhook` у Telegram
  - Помітити запис `bots` як `disconnectedAt = now()` (soft, для аудиту); токен з документа стираємо
  - Підписники лишаються (для історії), нові не приходитимуть
- Можливість підключити новий токен (reconnect новим)

### Безпека
- Токен at-rest: AES-256-GCM, `BOT_TOKEN_ENCRYPTION_KEY` (32 байти hex) — env var, fail-fast on startup if absent
- На UI токен показуємо masked: `1234567890:ABC***...***xyz`
- Webhook secret token — random 32-byte hex, генерується при підключенні, унікальний на проект
- API endpoints — обов'язково через `ProjectService.requireOwned(ownerId, projectId)` (паттерн з 03-projects)

### Тест-повідомлення
- Кнопка "Send test message" у налаштуваннях бота
- Вимагає, щоб користувач хоча б раз написав боту `/start` (треба знати власника-chat_id). До 04b/Epic 05 тестове повідомлення може йти на хардкодний chat_id з форми — обговорити в інтерв'ю.
- Відправляє "Hello from Bot Funnel Service! Bot connected ✅" через `TelegramSender` (з 04c — порядок інтеграції: спочатку 04a використовує stub, потім підключається реальний)

## Gaps для інтерв'ю

1. **Унікальність бота — UI behavior на конфлікт.** 409 `bot_already_connected_to_another_project`? Чи показуємо назву іншого проекту? (Може бути не наш — leakage.)
2. **Refresh bot identity.** Користувач у BotFather змінив ім'я/аватар — коли ми це бачимо? On-demand reload по кнопці? Auto-poll щогодини?
3. **Webhook URL у dev.** APP_URL у dev = `http://localhost:8080`, Telegram HTTPS-only. Інструкція з ngrok/cloudflared у `docs/local-setup.md`? Чи polling fallback (рекомендую не робити — окрема велика підсистема)?
4. **Telegram 401 пост-фактум.** Бот був валідний при connect, потім видалений через BotFather → наступний send падає 401. Авто-disconnect + банер? Чи лише алерт? (Глибше — в 04c.)
5. **Webhook secret rotation.** Раз згенерували — назавжди? Чи кнопка "Regenerate webhook secret" у UI? Корисно при підозрі на компрометацію.
6. **Encryption key rotation.** `BOT_TOKEN_ENCRYPTION_KEY` компрометовано — як re-encrypt? (Підказка: версіоновані ключі `BOT_TOKEN_ENCRYPTION_KEY_V1/V2` + поле `keyVersion` у `bots` документі. Для MVP можна винести в gaps без реалізації — задокументувати ризик.)
7. **"Show full token" з підтвердженням пароля.** Нетипова UX. Викидаємо на користь masked + regenerate webhook secret + reconnect? (Рекомендація: викинути — додає re-auth flow, мало користі.)
8. **Validation помилки локалізовані** через `errors.bot.connect.codes.{code}` — слідуємо паттерну `useApiError` з `patterns.md`.
9. **Soft vs hard disconnect.** Якщо disconnect → reconnect новим токеном — створюємо новий `bots` документ чи переписуємо існуючий? (Рекомендація: новий, бо historical subscribers вже посилаються на старого бота.)

## Викинути (свідомо)

- "Show full token" з re-auth → залишаємо лише masked + ability to disconnect/reconnect
- Polling-режим у dev → інструкції з ngrok/cloudflared у docs, без коду в backend
- Декілька ботів в одному проекті → 12-nice-to-have (вже відмічено в README)

## Перенесено в інші підзадачі

- **TelegramSender API + retry/rate-limit/error mapping** → `04c-telegram-sender`. У 04a використовуємо stub або мінімальний `sendText` (для тест-повідомлення).
- **Webhook endpoint + secret token verify + queue + idempotency** → `04b-webhook-ingestion`. У 04a лише `setWebhook` / `deleteWebhook` calls.
- **`/start` і `/stop` обробка** → `04b-webhook-ingestion` + Epic 05/06.

## Залежності

- 03-projects (бот живе всередині проекту; використовуємо `ProjectService.requireOwned`)
- 04c-telegram-sender (для тест-повідомлення; можна почати з stub, замінити пізніше)

## Acceptance criteria (preview, фіналізуємо в інтерв'ю)

1. Користувач вставляє валідний токен → бачить `@username` та ім'я свого бота на сторінці Settings → Bot
2. При невалідному токені — локалізована помилка з кодом (`token_invalid` / `token_revoked` / `network`)
3. При спробі підключити вже зайнятого бота — 409 з кодом `bot_already_connected` (без leakage чужого проекту)
4. Token зберігається зашифрованим; raw token ніде в БД не видно
5. Webhook автоматично зареєстровано в Telegram, secret token у заголовку при кожному запиті
6. Disconnect → `deleteWebhook` викликано, токен з документа очищено, документ помічено `disconnectedAt`
7. UI показує токен masked; кнопки "Show full token" немає
8. Тест-повідомлення доходить до власника-chat_id (вимагає, щоб власник хоча б раз написав `/start` боту)
9. Усі ендпоінти ходять через `ProjectService.requireOwned` (cross-tenant isolation)
