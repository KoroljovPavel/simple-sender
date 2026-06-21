---
# Creation date (YYYY-MM-DD)
created: 2026-05-16

# Status: draft | approved
status: approved

# Work type: feature | bug | refactoring
type: feature

# Feature size: S (1-3 files, local fix) | M (several components) | L (new architecture)
size: L
---

# User Spec: 08-webhook-ingestion

## Что делаем

Инфраструктурный слой приёма Telegram-обновлений. Реализуем `POST /webhooks/telegram/{projectId}`: проверка `X-Telegram-Bot-Api-Secret-Token` против SHA-256-hash в `bots.webhookSecretHash`, быстрое сохранение raw update в новую коллекцию `raw_updates`, постановка в JobRunr асинхронной задачи `ProcessTelegramUpdateJob`, ответ Telegram-серверу `200 OK` за <100ms P99. Worker парсит команды `/start [payload]` и `/stop`, на первом приватном `/start` к CONNECTED-боту атомарно прописывает `Bot.ownerChatId` (закрывает production-наблюдаемость success-ветки `04c TelegramSender.sendTestMessage`), вызывает no-op заглушки `SubscriberService` и `FunnelTriggerService` (реальная реализация — Epic 05/06), пишет события в `events`. Это последний из трёх под-эпиков `04 — Telegram-боты` (04a и 04c уже в `completed/`).

## Зачем

Без 04b сервис не способен принять ни одного `/start` — это блокирует запуск Epic 05 (Subscribers, авто-регистрация подписчиков) и Epic 06 (Funnels, триггер `on_start`). Также 04c (`TelegramSender.sendTestMessage`) сейчас имеет невидимую в production happy-ветку: при `Bot.ownerChatId IS NULL` возвращается 422 `owner_chat_id_unknown`, и реальный send выполняется только в integration-тестах и в staging-smoke с ручным `mongosh` seed. 04b замыкает контур: владелец проекта подключает бота (04a flow), пишет `/start` своему боту со своего Telegram, `ownerChatId` автоматически записывается, кнопка "Send test message" в админке начинает реально слать сообщение. После 04b Epic 05/06 могут стартовать.

## Как должно работать

**Сценарий 1 — обычный subscriber пишет `/start ref_123` боту:**
1. Telegram POST-ит update на `https://<APP_URL>/webhooks/telegram/{projectId}` с заголовком `X-Telegram-Bot-Api-Secret-Token`.
2. Сервис ищет `bots` по `(projectId, status=CONNECTED)` — если нет, отвечает `404` (uniform anti-enumeration).
3. SHA-256 хэширует заголовок, сравнивает constant-time с `bots.webhookSecretHash`. Несовпадение → `401` без тела.
4. Reactive-save в `raw_updates` (`projectId`, `updateId`, `payload`, `processingStatus=pending`, `createdAt`). Уникальный compound-индекс `(projectId, updateId)` — на `DuplicateKeyException` тихо `200 OK` без enqueue.
5. `Mono.fromCallable(() -> BackgroundJob.enqueue(ProcessTelegramUpdateJob → handle(rawUpdateId))).subscribeOn(boundedElastic())` — освобождает Netty event-loop.
6. Ответ `200 OK` Telegram-серверу за <100ms (P99).
7. Worker подхватывает job, парсит `/start ref_123`, вызывает no-op `SubscriberService.upsertFromTelegramUpdate(...)` и `FunnelTriggerService.fire("on_start", "ref_123")`, пишет event `telegram_command_start` с `metadata.startPayload="ref_123"`, проставляет `raw_updates.processingStatus=done`.

**Сценарий 2 — владелец проекта впервые пишет `/start` своему боту:**
1-6. Как в Сценарии 1, без payload.
7. Worker детектит: `message.chat.type=="private"`, `bot.ownerChatId IS NULL`, `bot.status==CONNECTED`. Атомарный `findAndModify` с предикатом `ownerChatId=null` записывает `bot.ownerChatId = message.chat.id`. Гонка ("first-wins") разруливается атомарностью.
8. Дальше владелец идёт в Settings → Bot, кликает "Send test message" — теперь оно реально пишет в Telegram (04c happy-path запускается).

**Сценарий 3 — Telegram повторно прислал тот же `update_id` (наш `200 OK` задержался):**
1-3. Как в Сценарии 1.
4. Insert падает с `DuplicateKeyException` (unique-index `(projectId, updateId)`).
5. Контроллер ловит, не enqueue-ит job, отвечает `200 OK`. `raw_updates` остаётся с одним документом, один job, один event.

**Сценарий 4 — `/stop`:**
1-6. Как в Сценарии 1.
7. Worker парсит `/stop`, вызывает no-op `SubscriberService.markUnsubscribed(...)` + `FunnelTriggerService.cancelActiveFor(...)`, пишет event `telegram_command_stop`.

**Сценарий 5 — что-то "странное" (callback_query без message, edited_message, /start в группе, неизвестная команда):**
1-6. Как в Сценарии 1 — raw_update сохранён, `200 OK`.
7. Worker:
   - callback_query / edited_message и прочие non-message updates → silent ignore, event `telegram_update_other`.
   - `/start` в group/supergroup/channel → event `telegram_command_start` без populate `ownerChatId`, без Subscriber upsert.
   - Неизвестная команда `/foo bar` в private → event `telegram_message_received` без stub-вызовов.
   - Обычный текст в private → Subscriber upsert (stub) + event `telegram_message_received`. В non-private — только event.

## Критерии приёмки

- [ ] **AC1.** `POST /webhooks/telegram/{projectId}` с валидным `X-Telegram-Bot-Api-Secret-Token` → `200 OK`. Latency budget: P99 <100ms под parallel load (100 запросов в окне `Flux.range(0,100).parallel(10)` против Testcontainer Mongo). Sequential POST sanity-check недостаточен для P99 — нужно реальное распределение.
- [ ] **AC2.** Bad/missing secret token → `401`, тело пустое.
- [ ] **AC3.** Несуществующий / soft-deleted / без CONNECTED-бота `projectId` (включая malformed-ObjectId) → `404` (uniform anti-enumeration, нельзя отличить от "не наш проект").
- [ ] **AC4.** Payload > 1 MB на `/webhooks/telegram/**` → `413 Payload Too Large`. Лимит scoped к webhook-пути (через `WebFilter` или per-route codec), НЕ глобальный bump WebFlux `maxInMemorySize` — иначе расширяется attack-surface на `/api/**` где 256KB default защищает от DoS-payload.
- [ ] **AC5.** Тот же `(projectId, update_id)` дважды (параллельно или последовательно) → один документ в `raw_updates`, один JobRunr-job, один event. Race-тест: `Flux.range(0, 2).parallel(2)` → status pair `{200, 200}`, exactly 1 row, exactly 1 enqueued job.
- [ ] **AC6.** `/start` от владельца в private-chat, когда `bot.ownerChatId IS NULL` и `bot.status=CONNECTED` → `ownerChatId` записан атомарно через `findAndModify` с предикатом `ownerChatId=null`. Второй `/start` от другого пользователя → `ownerChatId` НЕ перезаписывается.
- [ ] **AC7.** Парсер `/start`:
  - `/start ref_X` в private-chat → `events.metadata.startPayload="ref_X"`; вызвал `SubscriberService.upsertFromTelegramUpdate` + `FunnelTriggerService.fire("on_start", "ref_X")` (оба no-op stub).
  - `/start` без payload → `events.metadata.startPayload=""` (пустая строка, НЕ `null`, НЕ отсутствует ключ — стабильный контракт для Epic 05/06).
  - `/start ref_a b c` (multi-word payload) → `startPayload="ref_a b c"` (всё после первого whitespace, trimmed).
  - `/start@SomeBot ref_X` (Telegram suffix в групп-чатах) → `@SomeBot` strip-ается парсером, `startPayload="ref_X"`. То же для `/stop@SomeBot`.
- [ ] **AC8.** `/stop` в private-chat → вызвал no-op stubs `markUnsubscribed` + `cancelActiveFor`; event `telegram_command_stop`.
- [ ] **AC9.** `/start` в group/supergroup/channel (`chat.type != "private"`) → event записан, но `ownerChatId` НЕ populate, `Subscriber` stub НЕ вызван.
- [ ] **AC10.** Обычный текст в private-chat → `SubscriberService.upsertFromTelegramUpdate` stub + event `telegram_message_received`. В non-private — только event.
- [ ] **AC11.** Non-message updates (`callback_query`, `edited_message`, `channel_post`, `my_chat_member`, etc.) → `raw_update` сохранён, event `telegram_update_other` с `metadata.updateKind=<имя поля>`, без вызовов stub-сервисов. Epic 05 при реализации Subscriber-status sync может позже различить `my_chat_member` отдельным event-типом — в 04b это generic ignore per Q11.
- [ ] **AC11a.** Update без `message` ИЛИ с `message.from == null` (rare для channel posts) → safe-навигация в worker'е, никаких NPE, event `telegram_update_other`, без stub-вызовов.
- [ ] **AC12.** Неизвестная команда (`/foo bar`) → event `telegram_message_received`, без stub-вызовов.
- [ ] **AC13.** `raw_updates.createdAt` имеет TTL index 90 дней (MongoDB native `expireAfterSeconds=7776000`, не cleanup-job). Partial-filter: TTL применяется ТОЛЬКО к строкам `processingStatus IN ("pending","done")`. Строки с `processingStatus="failed"` НЕ авто-удаляются (audit-видимость failure'ов важнее экономии storage до тех пор, пока JobRunr dashboard не включён в Epic 10).
- [ ] **AC14.** Worker exception → `raw_updates.processingStatus="failed"` + `processingError` записан; JobRunr Failed-queue ловит через built-in retry. После исчерпания retry — manual redrive через JobRunr dashboard (включение dashboard отложено на Epic 10).
- [ ] **AC15.** SecurityConfig: `pathMatchers("/webhooks/telegram/**").permitAll()` ПЕРЕД `/api/**.authenticated()`. CSRF отключён через `requireCsrfProtectionMatcher` exclusion (НЕ глобальный `.disable()`). Тест: `POST /api/v1/...` без `X-XSRF-TOKEN` всё ещё возвращает `403`.
- [ ] **AC16.** Observability — counters пишутся И проверяются (integration-test assert на `MeterRegistry.find(...).counter().count()` после `n` HTTP-вызовов):
  - `telegram_webhook_received_total{projectId}` — все принятые.
  - `telegram_webhook_duration_seconds` — Timer (P99 верифицируется в AC1).
  - `telegram_webhook_rejected_total{reason=invalid_secret|project_not_found|duplicate|payload_too_large}` — rejection-причины (controller-сторона).
  - `telegram_worker_outcome_total{outcome=success|failure}` — закрывает worker-observability gap (dashboard выключен, без этого failure видим только через `mongosh`). Increment в worker'е после `processingStatus` обновления.
  - Все 4 проверяются ListAppender-логом + counter-assert в integration-тестах. Зависимость: `io.micrometer:micrometer-core` в `backend/build.gradle`.
- [ ] **AC17.** `Bot.ownerChatId` — Java-field-only добавление (nullable wrapper, без Mongo-миграции, без index). Legacy-документы читаются как `null` (precedent `Bot.disconnectedAt`).
- [ ] **AC18.** Token-scrubber (`TelegramApiClient.scrubTokens`) применён ко ВСЕМ лог-сайтам в webhook handler и worker (ListAppender-тесты per лог-сайт).
- [ ] **AC19.** Inbound payload — типизированный `record TelegramUpdate(...)` со snake_case-полями (mirror precedent `TelegramResult`/`TelegramUser`/`TelegramSendParameters`). Nested records: `Message`, `Chat`, `User`, `CallbackQuery` (минимум, что реально читаем). `@JsonIgnoreProperties(ignoreUnknown=true)` на классе.

## Ограничения

**SLA / performance:**
- Webhook P99 latency <100ms — Telegram считает long-response (>seconds) failure и ретраит. End-to-end: 1 Mongo read (`bots` lookup, indexed) + 1 reactive insert (`raw_updates`) + 1 sync enqueue (JobRunr storage, на bounded-elastic потоке). Headroom есть.
- Payload max 1 MB — scoped к `/webhooks/telegram/**` (через `WebFilter` или per-route codec), НЕ глобальный bump WebFlux. Default 256KB на остальных путях сохраняется как DoS-защита. >1 MB → 413.

**Security:**
- HTTPS only — Telegram требует. Production `APP_URL` уже HTTPS.
- SecurityConfig: `pathMatchers("/webhooks/telegram/**").permitAll()` ПЕРЕД `/api/**.authenticated()` (first-match rule, `patterns.md` line 44).
- CSRF disabled ТОЛЬКО для `/webhooks/telegram/**` через `requireCsrfProtectionMatcher` (negated matcher), не глобальный `.disable()`. Остальные пути сохраняют CSRF-защиту (`patterns.md` line 45).
- Secret token хранится как SHA-256 hex (`patterns.md` line 101), верифицируется через `MessageDigest.isEqual` (constant-time).
- Mass-assignment: `@JsonIgnoreProperties(ignoreUnknown=true)` на `TelegramUpdate` record — Telegram добавляет новые поля без предупреждения.
- Token-scrubber на всех лог-сайтах (`patterns.md` line 98).

**Concurrency / consistency:**
- Idempotency: unique compound index `(projectId, updateId)` на `raw_updates` + ловля `DuplicateKeyException` (precedent `BotService.connect`, `patterns.md` line 82).
- Worker concurrency = JobRunr default (~10 потоков), БЕЗ per-chat ordering — accepted compromise, ordering задача Epic 05/06.
- `ownerChatId` populate — атомарный `findAndModify` с предикатом `ownerChatId=null`, first-private-`/start`-wins. Threat model (быстрый атакер race-ит владельца) — accepted, mitigations не делаем.

**Local dev:**
- Без polling-режима. Локальное тестирование — ngrok + `setWebhook` через 04a flow.
- Real Mongo в integration-тестах (Testcontainers, mirror 04a/04c).

**MVP scope:**
- 1-on-1 DM only. Группы / каналы / `inline_query` / `shipping_query` / `pre_checkout_query` / `poll_answer` — silent ignore + event `telegram_update_other`.
- Без outbound Telegram-API в 04b — это 04c (`TelegramSender`).

**Dependencies (новые):**
- `io.micrometer:micrometer-core` добавляется в `backend/build.gradle` (первое введение Micrometer в проект, без actuator).

## Риски

- **R1 — <100ms P99 SLA на greenfield JobRunr-enqueue из WebFlux.** В кодовой базе нет прецедента `BackgroundJob.enqueue(...)` из реактивного controller'а — только `@Recurring`-задачи. **Митигация:** обернуть enqueue в `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` (`patterns.md` line 53); load-test в AC1 (100 sequential POST против Testcontainer); если P99 >100ms — добавить Caffeine cache для `Bot` lookup (отложено per `patterns.md` line 119, пока не доказано измерением).
- **R2 — ownerChatId race-condition.** Быстрый атакер, узнавший `@botusername` сразу после Connect, может опередить владельца с `/start`. **Митигация:** accepted threat per Q10; staging-smoke runbook 04a уже инструктирует "напиши `/start` сразу после Connect"; UI-mitigations (требование подтверждения, white-list) отложены до отдельной фичи если возникнет необходимость.
- **R3 — CSRF matcher syntax в Spring Security 6.5.x.** `requireCsrfProtectionMatcher` с negated `PathPatternParserServerWebExchangeMatcher` имеет тонкости в API. **Митигация:** cross-check через Context7 в tech-spec; integration-тест "POST `/api/v1/...` без `X-XSRF-TOKEN` → `403`" (защищает от регрессии).
- **R4 — Token leak в логах.** Webhook payload может содержать произвольный пользовательский текст, включая случайно вставленный bot token. **Митигация:** AC18 — token-scrubber на всех лог-сайтах; ListAppender per-сайт-тесты; `BotTokenLeakTest` invariant остаётся в силе.
- **R5 — JobRunr Failed-queue UX в production пока скрыта.** Dashboard выключен (`application.properties:34`). При worker failure видимость только через `mongosh db.raw_updates.find({processingStatus:"failed"})`. **Митигация:** явно прописать процедуру в staging-smoke runbook; включение dashboard отложено на Epic 10 (admin panel).
- **R6 — Cascade на soft-delete проекта.** Если `ProjectHardDeleteJob` или soft-delete flow оставит `bot` row CONNECTED после удаления проекта, webhook продолжит работать. **Митигация:** integration-тест "soft-delete project → next webhook → `404`" в AC3; в tech-spec проверить cascade-логику `ProjectHardDeleteJob.java`, при необходимости добавить bot-disconnect в каскад.
- **R7 — Первое введение Micrometer-зависимости.** Зависимость новая для проекта (`patterns.md` line 49: "проект не имеет Micrometer/actuator"). **Митигация:** добавляем только `micrometer-core` (без actuator endpoints), tech-spec оформит как Decision; counters пишутся через `MeterRegistry` bean без дополнительной конфигурации.

## Технические решения

- **Endpoint и path-структура:** `POST /webhooks/telegram/{projectId}` — `projectId` в path естественно даёт single-lookup на `bots.findByProjectIdAndStatus(projectId, CONNECTED)`. Этот единственный запрос схлопывает все 404-кейсы (нет проекта / soft-deleted / disconnected bot / malformed ObjectId) в одинаковый ответ — anti-enumeration uniform 404 (`patterns.md` line 79).
- **Secret token — храним SHA-256 hash, верифицируем re-hash + constant-time compare.** Plaintext-секрет существует только в памяти у Telegram (он отдаёт его в заголовке). Утечка Mongo backup даёт атакеру только хэши. Constant-time через `MessageDigest.isEqual(byte[], byte[])`.
- **401 без тела — через `ResponseEntity.status(401).build()` напрямую, НЕ через `AppException`.** Существующий `GlobalErrorHandler.handleAppException` всегда пишет body — добавлять exception-аркадеа и спец-обработчик не оправдано для одного эндпоинта.
- **Idempotency через unique compound index `(projectId, updateId)` + ловля `DuplicateKeyException`.** Mongo как single source of truth (durable). Без Redis для дедупликации — Redis в проекте есть, но это отдельный failure-mode без реальной пользы (Mongo TTL и так чистит за 90 дней).
- **`ownerChatId` — атомарный `findAndModify` с предикатом `ownerChatId=null`, first-private-`/start`-wins.** Точная семантика 04a/04c не прописывала, синтезирована из UX-фразы "Send /start to your bot first". Threat model (race с атакером) — accepted, mitigations не делаем.
- **Stub-сервисы живут в финальных пакетах `subscriber/` и `funnel/` (не в `_stubs/` подпапке).** 04b коммитит интерфейс + no-op impl; Epic 05/06 заменят impl на реальный без рефакторинга пакетной структуры. Прецедент — `BotService.sendTestMessage` stub в 04c в своём финальном месте.
- **JobRunr enqueue из реактивного controller'а — wrap в `Mono.fromCallable(...).subscribeOn(boundedElastic())`.** Greenfield-паттерн (в кодовой базе только `@Recurring`-задачи без one-shot `BackgroundJob.enqueue`). Освобождает Netty event-loop, синхронный Mongo write JobRunr-storage уходит на bounded-elastic.
- **Worker body выполняет реактивные методы через `.block()`.** JobRunr-thread не Netty event-loop, blocking-вызовы безопасны. Прецедент — `HardDeleteJob.java`, `ProjectHardDeleteJob.java`.
- **DLQ = JobRunr Failed-queue (built-in retry с exponential backoff) + `raw_updates.processingStatus={pending|done|failed}` + `processingError` (последнее сообщение).** Без отдельной коллекции `dead_letter_updates`.
- **Inbound payload — типизированный `record TelegramUpdate(...)` со snake_case-полями (mirror `TelegramResult`/`TelegramUser` precedent).** Nested records `Message`, `Chat`, `User`, `CallbackQuery` (только то, что реально читаем). `@JsonIgnoreProperties(ignoreUnknown=true)`. Альтернативу `Map<String,Object>` отклонили — type-safety и refactor-friendly важнее.
- **Bot.ownerChatId — Java-field add без миграции.** Spring Data MongoDB читает отсутствующие поля как `null` для wrapper-типов (`Long`). Прецедент — `Bot.disconnectedAt` (`patterns.md` line 124).
- **Per-chat ordering НЕ реализуем в 04b.** JobRunr default concurrency, worker'ы могут обработать `/start` и `/stop` от одного chat в любом порядке. Race редкий (мс-окно), стоимость serialize-everything слишком высокая. Решение про ordering — Epic 05/06 через optimistic locking или per-subscriber lock.
- **4 micrometer counters, через `micrometer-core` (без `spring-boot-starter-actuator`).** Counters в production не имеют экспортёра — Prometheus scrape и dashboard — Epic 09 (analytics). Это НЕ dead code: (1) integration-тесты в AC16 явно assert-ят `MeterRegistry.find(...).counter().count()` — counters имеют consumer и сейчас; (2) когда Epic 09 добавит exporter, код counter'ов не меняется — нет refactor'а из log-формата в MeterRegistry. Альтернативу "structured logs now + Micrometer later" отклонили — это создаёт миграцию для observability-кода. Альтернативу "full actuator now" отклонили — открывает endpoint-surface (`/actuator/**` permitAll/auth-config), который сейчас не нужен.
- **`logEventBlocking` (вместо fire-and-forget `logEvent`) для критичных событий в worker'е.** Гарантия, что event попал в Mongo до `processingStatus=done` (защита от crash mid-flight; `patterns.md` line 83).
- **Без polling-режима для local dev.** Staging-smoke через ngrok + BotFather throwaway bot.
- **`update_id` monotonicity — accepted как Telegram-guaranteed.** Unique index на `Long` достаточен.

## Тестирование

**Unit-тесты:** делаются всегда. Конкретно:
- `TelegramCommandParserTest` — `/start`, `/start payload`, `/start@botname`, `/start@botname payload`, `/stop`, `/stop@botname`, non-command текст, edge-cases (multi-word payload, пустой payload, "/" alone).
- `WebhookSecretVerifierTest` — happy-path, mismatch, empty header, MessageDigest constant-time path exercised.
- (опционально) `RawUpdateRepositoryTest` — `StepVerifier` против `DuplicateKeyException` на unique-index.

**Интеграционные тесты: делаем.** Причина: 04b — security-критичный (anti-enumeration 404, 401-no-body, CSRF scoped disable), идемпотентность через Mongo unique-index, и реактивная цепочка с blocking-enqueue требуют живого Mongo. Конкретно:
- `TelegramWebhookControllerIT` (`@SpringBootTest`+`AbstractIntegrationTest` с Testcontainers Mongo + `JobRunrInMemoryConfig`):
  - AC1: WebTestClient happy-path, измерение latency stopwatch'ем.
  - AC2: 401 no-body — assertion `response.body == null`.
  - AC3: 404 для 4 кейсов (missing project, soft-deleted, disconnected bot, malformed ObjectId).
  - AC4: 413 на >1 MB payload.
  - AC5: race-test через `Flux.range(0, 2).parallel(2).flatMap(...)` — status pair `{200, 200}`, `rawUpdateRepository.count()==1`, `InMemoryStorageProvider.getJobs().size()==1`.
  - AC6: ownerChatId atomic-write через 2 параллельных `/start` от разных chat — `bot.ownerChatId == первый_chatId`.
  - AC15: CSRF-protection всё ещё активна для `/api/v1/...` (отдельный test-кейс с auth user без `X-XSRF-TOKEN` → 403).
- `ProcessTelegramUpdateJobTest` — invoke worker напрямую (mirror `HardDeleteJobTest`). Seed `raw_updates`, вызвать `job.handle(rawUpdateId)`, assert events/`processingStatus`/stub-spy-вызовы.
- `WebhookSecurityBlockTest` (slice-style, mirror `SecurityBlockTest`) — webhook permitAll без booting full container, `/api/**` всё ещё auth-required.
- Token-leak assertions per лог-сайт через `ListAppender<ILoggingEvent>` с явной level-фильтрацией (per-сайт, не комбинированный assert) — mirror `BotControllerIT` precedent.

**E2E тесты: делаем (staging-smoke runbook).** Причина: реальный Telegram + ngrok → нельзя автоматизировать в CI, но критично проверить полный inbound-контур с реальным BotFather-ботом и production-like окружением.
- Файл: `docs/staging-smoke/08-webhook-ingestion.md`.
- 10-15 минутный manual checklist с throwaway-ботом (`SmokeTest_XYZ_bot`).
- Покрывает: Connect → setWebhook → `/start ref_smoke_001` → проверка `raw_updates` через `mongosh` → проверка events → ownerChatId populate → клик "Send test message" (закрывает 04c gap) → `/stop` → Disconnect → удалить bot через BotFather.

## Как проверить

### Агент проверяет (during development, без deploy)

| Шаг | Инструмент | Ожидаемый результат |
|-----|-----------|-------------------|
| 1. Запустить полный test suite | `./gradlew test` | Все тесты зелёные; coverage на `webhook/`, `subscriber/`, `funnel/` >= 80% на business logic |
| 2. Health-check после `bootRun` | `curl http://localhost:8080/health` | `{"status":"ok"}` |
| 3. Webhook 401 без secret | `curl -X POST -H "Content-Type: application/json" http://localhost:8080/webhooks/telegram/507f1f77bcf86cd799439011 -d '{}'` | HTTP `401`, тело пустое |
| 4. Webhook 404 на несуществующий project | `curl -X POST -H "Content-Type: application/json" -H "X-Telegram-Bot-Api-Secret-Token: random" http://localhost:8080/webhooks/telegram/507f1f77bcf86cd799439011 -d '{}'` | HTTP `404` |
| 5. SecurityConfig regression — `/api/v1/...` всё ещё auth-required | `curl -X POST http://localhost:8080/api/v1/projects` (без сессии) | HTTP `401` |
| 6. Mongo collections и indexes созданы | `mongosh botfunnel --eval 'db.raw_updates.getIndexes()'` | Видны `_id_`, `projectId_updateId_unique` (unique), `ttl_createdAt` (`expireAfterSeconds=7776000`) |
| 7. JobRunr storage отдельной коллекцией | `mongosh botfunnel --eval 'db.jobrunr_jobs.countDocuments()'` | Возвращает `0` на свежем dev (или >0 если уже было событие) |
| 8. Структурное прохождение `TelegramWebhookControllerIT` | `./gradlew test --tests *.TelegramWebhookControllerIT` | 20+ test methods зелёные, включая race-test и token-leak invariant |

### Пользователь проверяет (post-deploy на staging)

**Prerequisite:** перед запуском smoke нужно создать `docs/staging-smoke/08-webhook-ingestion.md` (новый runbook, создаётся в рамках задач этой фичи).

**Что проверяем (10-15 мин):**
- Connect throwaway-бот через BotFather + ngrok-туннель → setWebhook → `/start ref_smoke_001` со своего Telegram → `raw_updates` через `mongosh` имеет один документ → events имеют `telegram_command_start` с `metadata.startPayload="ref_smoke_001"` → `bot.ownerChatId` populated.
- Клик "Send test message" в UI → получили сообщение в Telegram (это закрывает 04c gap, который до 04b в production был unobservable).
- `/stop` → event `telegram_command_stop`.
- Disconnect bot → удалить throwaway-bot через BotFather.

**Зачем руками:** реальный Telegram-сервер + production-like HTTPS + setWebhook-flow невозможно подделать в CI; этот эпик впервые делает inbound-контур наблюдаемым в production, поэтому первый раз — глазами.
