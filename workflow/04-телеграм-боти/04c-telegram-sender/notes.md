# 04c — TelegramSender Service

**Статус:** notes для майбутнього user-spec (раніше частина `04-телеграм-боти/README.md` MUST HAVE).

## Що входить

Reactive-сервіс для надсилання повідомлень у Telegram. **Чистий інфраструктурний шар** — не знає про subscribers / funnels / broadcasts, лише про `chatId` + bot credentials. Викликається з:
- 04a (тестове повідомлення при підключенні бота)
- Epic 06 (funnel-кроки `send_message`)
- Epic 07 (broadcast delivery)

### API (Spring Bean `TelegramSender`)

```
sendText(botId, chatId, text, options?) → Mono<SentMessage>
sendWithButtons(botId, chatId, text, inlineButtons[]) → Mono<SentMessage>
sendImage(botId, chatId, urlOrFileId, caption?) → Mono<SentMessage>
editMessage(botId, chatId, messageId, text) → Mono<SentMessage>
deleteMessage(botId, chatId, messageId) → Mono<Void>
```

- `botId` → завантажуємо bot doc → дешифруємо токен → формуємо HTTP-запит до `https://api.telegram.org/bot{token}/{method}`
- HTTP-клієнт: `WebClient` (reactive Netty)
- Повертає DTO `SentMessage { chatId, messageId, sentAt }` (мінімум; розширювати по потребі)

### Обов'язкова поведінка

#### Retry
- Exponential backoff: 1s → 2s → 4s, до 3 спроб
- Retry **тільки на**: `5xx` (Telegram side), `IOException`/`TimeoutException` (network)
- НЕ retry на: `4xx` (наш bug або стан subscriber'а — okrema обробка нижче)

#### Rate limiting (Telegram side)
- При `429 Too Many Requests` — респонс містить `parameters.retry_after` (секунди)
- Чекаємо `retry_after` + jitter, повторюємо (не рахується в N=3 спроб retry)
- Глобальний бот-level throttle: ~25 msg/sec (нижче ліміту 30/sec) — реалізуємо через Reactor `Flux.delayElements` на send-pipeline у broadcast/funnel (тут, у `TelegramSender`, лише per-call rate-limit handling)

#### Mapping помилок → доменні події
- `403 Forbidden: bot was blocked by the user` → throw `SubscriberBlockedException(chatId)`. Виклик зверху (Epic 05) перевершує subscriber.status = `blocked`
- `400 Bad Request: chat not found` → throw `SubscriberDeletedException(chatId)` → status = `deleted`
- `400 Bad Request: bot can't initiate conversation with a user` → `SubscriberNotStartedException` (надсилання до `/start` від користувача)
- `401 Unauthorized` → throw `BotTokenInvalidException(botId)` → caller (`04a` чи broadcast worker) вирішує: авто-disconnect / алерт
- `400 Bad Request: message text is empty` / інші body-related → `TelegramBadRequestException(rawDescription)` — наш bug, лог + escalate

### Events (audit)
- Кожен успіх → event `telegram_message_sent { botId, chatId, messageId }`
- Кожна остаточна (після всіх retry) помилка → event `telegram_send_failed { botId, chatId, errorCode, errorDescription }`
- Subscriber status changes (`blocked`, `deleted`) — окремі events, але вже з Epic 05 (тут throw exception, callee пише event)

## Gaps для інтерв'ю

1. **Дешифрування токена на кожен виклик чи кеш?** Якщо broadcast = 10k повідомлень → 10k Mongo reads + AES decrypts. Кешуємо `(botId → decryptedToken)` у `ConcurrentHashMap` з TTL 5 хв? Або Caffeine? (Рекомендую: Caffeine cache, max 100 ботів, expire after access 10 хв, eviction on disconnect via event listener.)
2. **Telegram API timeout values.** Default WebClient timeout — нескінченний. Set: connect=5s, read=10s, write=5s. Достатньо?
3. **Дроп vs DLQ при terminal failure.** Якщо після 3 retry все одно `5xx` — кидаємо `TelegramSendException` і caller дропає? Чи перекладаємо в outgoing-DLQ для manual retry? (Рекомендую: throw, caller (broadcast/funnel) вирішує — broadcast має `broadcast_messages.status=failed`; funnel — recovery попозже.)
4. **Inline keyboard validation.** Інпут API `sendWithButtons` — масив кнопок (`text` + `callbackData` АБО `url`). Хто валідує? (Рекомендую: тут — мінімальна (non-empty, length limits); семантика — на боці caller.)
5. **`sendImage` — URL vs fileId vs upload.** На MVP — тільки URL та fileId (вже завантажено). Upload (multipart) — `12-nice-to-have`?
6. **`parse_mode`.** Markdown / HTML / MarkdownV2 / plain — який default? Recovery при невалідному markup (`400 Bad Request: can't parse entities`)? (Рекомендую: default HTML; на parse error — fallback retry plain text без markup.)
7. **Метрики.** `telegram_send_total{botId,result}`, `telegram_send_duration_seconds{method}`, `telegram_rate_limit_total{botId}`. Епік 09 деталізує — тут лише оголошуємо counter'и.
8. **`401` cascade.** Один `401` від бота → ставимо bot як `tokenInvalid=true`? Що з in-flight broadcast'ами? (Рекомендую: bot.status = `token_invalid`, всі pending broadcast_messages → status `failed_bot_disconnected`; broadcast worker перевіряє статус бота перед send.)
9. **Logging PII.** Не логуємо `text` повністю (може містити user data). Логуємо `messageId`, `chatId`, length, hash першого 50 chars? (Sec audit питання.)
10. **HTTP error reuse.** Telegram повертає JSON `{ok: false, error_code: 403, description: "...", parameters: {...}}`. Наш `WebClient` ловить error — як парсити `error_code` + `description`? (Patterns: `onStatus(HttpStatus::isError, response -> response.bodyToMono(TelegramErrorBody.class).flatMap(body -> Mono.error(mapToException(body))))`.)

## Викинути (свідомо)

- Sending voice/video/stickers/animations — `12-nice-to-have`
- Telegram Payments — `12-nice-to-have`
- Webhooks-based callbacks для outgoing (delivery confirmations) — не існує в Telegram, видалити з потенційного scope
- Media upload через multipart — поки тільки URL/fileId

## Перенесено в інші підзадачі / епіки

- **Підключення/disconnect бота** → `04a-bot-connection`
- **Webhook receiver** → `04b-webhook-ingestion`
- **Per-bot global throttle 25 msg/sec (broadcast pipeline)** → Epic 07 (broadcasts) — тут лише per-call 429 handling
- **Auto-disconnect on persistent 401** → координація між 04c (detection + throw) і 04a (UI banner + status update). Можливо, окремий subscribed listener; обговоримо при user-spec.
- **Subscriber.status updates (`blocked`, `deleted`)** → Epic 05 — тут лише exception, який caller обробляє

## Залежності

- 04a-bot-connection (читає `bots.encryptedToken` для дешифрування)
- 03-projects (для авторизації caller'а — implicit, через service-rule)
- Шифрування — `BotTokenEncryptionService` (виноситься з 04a як shared bean)

## Acceptance criteria (preview, фіналізуємо в інтерв'ю)

1. `sendText` з валідним botId/chatId/text → повідомлення доставлене (verifiable через Telegram Test API або mock-server у integration test); повертається `SentMessage` з `messageId`
2. Telegram `5xx` тричі → throw `TelegramSendException` після 3 спроб (1s/2s/4s); event `telegram_send_failed`
3. Telegram `429 retry_after=3` → wait 3s + jitter, повтор, success → event `telegram_message_sent` (retry_after не рахується в 3 retry-cap)
4. Telegram `403 Forbidden: bot was blocked` → throw `SubscriberBlockedException`, БЕЗ retry
5. Telegram `401 Unauthorized` → throw `BotTokenInvalidException`, БЕЗ retry; bot.status примусово оновлюється (через listener чи прямий виклик)
6. Дешифрування токена кешоване (1 read на 5+ хв при високому трафіку, verifiable через DB query count у integration test)
7. `sendWithButtons` з невалідним keyboard (порожні кнопки) → `TelegramBadRequestException` БЕЗ виклику Telegram API
8. Logging: токен НІКОЛИ не з'являється в logs (verifiable через test що grep'ає logback test output)
9. WebClient timeout (connect 5s, read 10s) — після перевищення throw `TelegramSendException` (як 5xx-class)
10. Інтеграційний тест використовує WireMock / MockServer (mocking `api.telegram.org`); реального Telegram не торкаємось у CI
