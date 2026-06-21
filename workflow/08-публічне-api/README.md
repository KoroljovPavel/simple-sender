# 08 — Публічне API

## Мета
Дозволити зовнішнім системам (CRM, платіжний шлюз, веб-сайт, лендінг) інтегруватись зі сервісом: відправляти події, керувати тегами і custom fields, створювати розсилки, запускати воронки.

## Користувацька цінність
Це те, що **відрізняє нас** від чисто-візуальних no-code-платформ. Розробник може автоматизувати все. Без API сервіс — закритий острів.

---

## MUST HAVE

### Авторизація
- API-ключ генерується на рівні проекту (один primary активний)
- Можна regenerate (старий ключ одразу інвалідується, з підтвердженням)
- Передається у заголовку: `Authorization: Bearer {api_key}`
- Префікс ключа для впізнавання: `bfs_live_xxxxxxxxxxxxx` (32+ символів)
- При regenerate — нагадати користувачу оновити інтеграції

### Базові правила
- Усі ендпоінти під префіксом `/api/v1/`
- JSON request/response, UTF-8, `Content-Type: application/json`
- Усі ID — UUID v4 у строковому форматі
- Часові штампи — ISO 8601 з timezone (`2026-05-02T10:30:00+03:00`)
- Помилки у форматі: `{ "error": { "code": "subscriber_not_found", "message": "..." } }`
- Rate limiting: **60 запитів/хв** на ключ (м'який ліміт), 429 при перевищенні
- Idempotency-Key header (опц.) — для безпечних retry'їв

### Endpoints — мінімальний набір MVP

#### Subscribers
- `GET /api/v1/subscribers?tag=...&status=active&limit=50&cursor=...` — список з cursor-пагінацією
- `GET /api/v1/subscribers/{id}` — деталі підписника
- `POST /api/v1/subscribers/{id}/tags` — додати тег: `{ "tag": "vip" }`
- `DELETE /api/v1/subscribers/{id}/tags/{tag}` — прибрати тег
- `PATCH /api/v1/subscribers/{id}/fields` — оновити custom fields:
  ```json
  { "fields": { "city": "Kyiv", "purchase_amount": 99.5 } }
  ```

#### Events (універсальний прийом подій)
- `POST /api/v1/events`
  ```json
  {
    "event": "purchase",
    "subscriber_id": "uuid-or-telegram-id",
    "data": { "amount": 99.5, "currency": "USD" }
  }
  ```
- Знаходить підписника, тригерить воронки з відповідним event-тригером
- Якщо subscriber не знайдено → 404 (без авто-створення)

#### Broadcasts
- `POST /api/v1/broadcasts` — створити (поля як у UI), з опц. `send_immediately: true`
- `GET /api/v1/broadcasts/{id}` — статус і метрики
- `POST /api/v1/broadcasts/{id}/cancel`

#### Funnels
- `POST /api/v1/funnels/{id}/start` — запустити воронку для підписника:
  ```json
  { "subscriber_id": "..." }
  ```
- `GET /api/v1/funnels/{id}` — метадані воронки (без вмісту кроків — це nice-to-have)

#### Tags
- `GET /api/v1/tags` — список тегів проекту з кількостями

### Outgoing Webhooks
- У налаштуваннях проекту — поле "Webhook URL"
- Опційно: secret для перевірки підпису (HMAC-SHA256 у заголовку `X-Signature`)
- Події, які надсилаємо:
  - `subscriber.created`
  - `subscriber.unsubscribed`
  - `subscriber.tag_added` / `subscriber.tag_removed`
  - `funnel.started` / `funnel.completed` / `funnel.cancelled`
  - `broadcast.completed`
- Payload-формат:
  ```json
  {
    "event": "funnel.completed",
    "timestamp": "2026-05-02T10:30:00+03:00",
    "project_id": "...",
    "data": { "subscriber_id": "...", "funnel_id": "..." }
  }
  ```
- Retry з exponential backoff (3 спроби: 1s, 5s, 30s)
- Логування доставок у БД для дебагу (останні 1000 на проект)

### Документація
- **OpenAPI 3.0 spec** (автогенерована з коду — Nest@nestjs/swagger або FastAPI built-in)
- Swagger UI на `/api/docs`
- Сторінка "API Reference" у користувацькому кабінеті: приклади на curl + JS + Python
- "Quickstart" гайд: 3 кроки до першого запиту

### Версіонування
- Префікс `/api/v1/` — закладаємо можливість майбутньої v2 без ламання поточних інтеграцій
- Deprecation policy: 6 місяців на міграцію перед видаленням ендпоінта

### Логування і дебаг
- Усі API-виклики логуються (метод, шлях, IP, статус, час відповіді) з retention 30 днів
- Сторінка "API Logs" у налаштуваннях проекту — останні 100 викликів з можливістю переглянути payload (для дебагу інтеграцій)

---

## Що НЕ входить (свідомо)
- GraphQL — `12-nice-to-have`
- WebSocket для real-time оновлень — `12-nice-to-have`
- Bulk-операції (масово створити 1000 підписників за раз) — `12-nice-to-have`
- OAuth-app (для third-party інтеграцій від імені користувачів) — `12-nice-to-have`
- SDK на популярних мовах (Node, Python, PHP) — `12-nice-to-have`
- Sandbox environment — `12-nice-to-have`
- Webhook event filtering (підписатись лише на деякі типи) — `12-nice-to-have`

---

## Acceptance criteria
1. Користувач генерує API-ключ і робить `curl GET /subscribers` — отримує список
2. POST `/events` з тригером "purchase" → запускає відповідну воронку
3. Outgoing webhook приходить на тестовий URL з валідним HMAC-підписом
4. Документація доступна на `/api/docs`, усі ендпоінти описані
5. Регенерація ключа — старий не працює одразу, новий працює
6. Перевищення rate limit повертає 429 з заголовком `Retry-After`

---

## Залежності
- `05-підписники`
- `06-воронки`
- `07-розсилки`
- `03-проекти` (API-ключ на рівні проекту)

---

## Технічні нотатки
- API-ключі зберігати як hash (SHA-256), не plaintext
- Idempotency-Key — зберігати в Redis з TTL 24 год; повтор з тим самим key = повернути попередню відповідь
- Outgoing webhooks — окрема черга, окремий worker, щоб не блокувати основну логіку
