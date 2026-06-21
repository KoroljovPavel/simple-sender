---
# Creation date (YYYY-MM-DD)
created: 2026-05-23

# Status: draft | approved
status: approved

# Work type: feature | bug | refactoring
type: refactoring

# Feature size: S (1-3 files, local fix) | M (several components) | L (new architecture)
size: L
---

# User Spec: migrate-to-virtual-threads

## Что делаем

Полная миграция backend-стека с Spring WebFlux (реактивный Netty + Reactor) на Spring MVC + Java 21 virtual threads. Заменяем реактивные стартеры MongoDB и Redis на блокирующие, переписываем все controller/service/repository с `Mono`/`Flux` на синхронные типы, мигрируем тесты с `StepVerifier`/`WebTestClient` на JUnit-assertions/`MockMvc`. Frontend и внешние интеграции (Telegram, SMTP, MongoDB, Redis, JobRunr) — без изменений. Lombok НЕ добавляем (Java 21 records + ctor-injection достаточно).

## Зачем

В проде нет ни одной измеримой проблемы с WebFlux — миграция предприжена ради четырёх конкретных будущих выигрышей:

1. **Стеки трейсы блокирующего стиля проще читать** при дебаге, чем цепочки Reactor-операторов с переходами между schedulers.
2. **Тесты пишутся без обёрток** (`StepVerifier.create(...).expectNext(...).verifyComplete()` → обычный `assertThat(...)`).
3. **JobRunr-воркеры уже блокирующие** — virtual threads уберут impedance, который сейчас затыкается через `Mono.fromCallable(...).subscribeOn(boundedElastic)`. В `ProcessTelegramUpdateJob.handle(...)` уже сейчас 16 вызовов `.block()` поверх реактивных репозиториев — это чистая glue-обвязка, которую миграция уберёт.
4. **Будущие фичи MVP** (broadcast, analytics, public API) — будет проще добавлять синхронный controller/service/repo без реактивного «кармана» в кодовой базе.

Горизонтальное масштабирование планируется через множество инстансов, поэтому VT scaling-per-instance — НЕ драйвер миграции. Реальная мотивация — developer-ergonomics и упрощение будущего сопровождения.

**Почему сейчас, а не ждать Spring Boot 4 + JDK 24** (которые автоматически решают часть VT pinning'а): Spring Boot 4 апгрейд блокируется JobRunr 7.3.2, который pin'ится на Spring Boot 3.x; апгрейд JobRunr major до SB4-compatible — это отдельная фича со своими рисками. Дешевле сделать миграцию сейчас на текущей стабильной паре и принять pinning как observational gate (R1).

## Как должно работать

Перспектива разработчика/мейнтейнера (refactoring user-stories):

1. **`./gradlew test`** проходит зелёным на всех существующих 52 тест-файлах. Для slow-tagged P99 probe (`-PrunSlow=true`) — также зелёным.
2. **Чтение кода**: открыв любой `*Service.java` — видишь синхронные методы без `.flatMap`/`switchIfEmpty`/`onErrorResume` цепочек.
3. **Дебаг**: при ошибке в webhook ingestion стек-трейс ведёт прямо в `ProcessTelegramUpdateJob` без переходов между Reactor-scheduler'ами.
4. **Новая фича**: написать новый controller/service/repo — без обёртки `Mono.fromCallable(...).subscribeOn(boundedElastic)`, без `ReactiveMongoTemplate`.
5. **Staging smoke runbooks** 06-bot-connection / 07-telegram-sender / 08-webhook-ingestion проходят без модификации скриптов и шагов.

Поведенчески (для конечного пользователя bot-funnel сервиса) — НИЧЕГО НЕ ИЗМЕНИЛОСЬ: те же endpoints, те же status codes, те же cookies, та же CSRF-механика, те же email-flow, те же staging-smoke результаты.

## Критерии приёмки

- [ ] **AC1.** `./gradlew test` (без `-PrunSlow`) зелёный на всех тест-файлах post-migration.
- [ ] **AC2.** `./gradlew test -PrunSlow=true` зелёный; webhook P99 latency probe (`TelegramWebhookControllerIT` slow-tagged) подтверждает SLA `<100ms`.
- [ ] **AC3.** `grep -rE "reactor\.|StepVerifier|WebTestClient|Mono<|Flux<|Reactive(Mongo|Crud|Redis)" backend/src` возвращает 0 совпадений.
- [ ] **AC4.** `grep -E "lombok" backend/build.gradle` возвращает 0 совпадений (Lombok НЕ добавлен).
- [ ] **AC5.** `backend/src/main/resources/application.properties` содержит `spring.threads.virtual.enabled=true`.
- [ ] **AC6.** `backend/build.gradle` НЕ содержит `spring-boot-starter-webflux`, `spring-boot-starter-data-mongodb-reactive`, `spring-boot-starter-data-redis-reactive`, `io.projectreactor:reactor-test`.
- [ ] **AC7.** Backend стартует на JVM 21 с флагом `-Djdk.tracePinnedThreads=full`; сайты VT pinning (BCrypt, Lettuce internal locks) задокументированы в файле `.claude/skills/project-knowledge/references/patterns.md` (раздел "Virtual Threads"). Нулевой pinning НЕ требуется.
- [ ] **AC8.** Staging smoke runbook `docs/staging-smoke/06-bot-connection.md` пройден вручную (Connect / Disconnect / Reconnect / cross-project uniqueness / BotFather webhook field).
- [ ] **AC9.** Staging smoke runbook `docs/staging-smoke/07-telegram-sender.md` пройден вручную (Send Test Message happy-path).
- [ ] **AC10.** Staging smoke runbook `docs/staging-smoke/08-webhook-ingestion.md` пройден вручную (Telegram → ngrok → backend → JobRunr → events; FAILED-queue inspection).
- [ ] **AC11.** Manual UI smoke на `localhost:3000`: register → verify-email (Mailpit) → login → logout — все шаги работают.
- [ ] **AC12.** Manual UI smoke: login с `rememberMe=true` vs `rememberMe=false` дают разный cookie `Max-Age` (Long-lived vs Session-scoped) — проверяется в DevTools → Application → Cookies.
- [ ] **AC13.** Manual UI smoke: forgot-password → ссылка из Mailpit → reset-password → login — работает.
- [ ] **AC14.** Cleanup PR обновляет project-knowledge: `patterns.md` (Spring Security WebFlux → MVC, WebTestClient → MockMvc, "Spring Boot Test Specifics" с новой mock-triple, Reactor-specific retry/fire-and-forget — на синхронные паттерны, `ProjectService.requireOwned` — на не-реактивный signature), `architecture.md` (tech stack: WebFlux → Spring MVC + VT, dependencies list без реактивных стартеров), `deployment.md` (Production Infrastructure: "api — Spring MVC backend on virtual threads").
- [ ] **AC15.** `@MockitoBean` mock-triple обновлён во всех security-only / wiring-only тест-файлах: реактивные типы (`com.mongodb.reactivestreams.client.MongoClient`, `ReactiveRedisConnectionFactory`) больше не мокаются; на их месте — синхронные эквиваленты. (Конкретный список файлов — в tech-spec.)
- [ ] **AC16.** Integration-тест на session continuity: пользователь авторизован, `change-password` инвалидирует все остальные сессии — `principal`-lookup в `sessions` коллекции работает идентично pre-flip поведению. Тест выполняется в стандартном `./gradlew test`.
- [ ] **AC17.** Integration-тест на validation error response shape: `400 Bad Request` от `@Valid`-flow (например, registration с пустым email) возвращает JSON с `code: null` (как требует `useApiError` контракт в frontend). Тест подтверждает, что `WebExchangeBindException → MethodArgumentNotValidException` swap не сломал response body.
- [ ] **AC18.** Integration-тест на remember-me Max-Age cookie: автоматизирован — `MockMvc`-запрос на `/api/auth/login` с `rememberMe=true` подтверждает `Set-Cookie: SESSION=...; Max-Age=2592000` (30 дней); с `rememberMe=false` — `Max-Age` отсутствует. Снимает ручную нагрузку с AC12.

## Ограничения

**Технические:**

- Java 21 (`languageVersion=21`) — VT доступны.
- Spring Boot 3.5.0 (без апгрейда). VT включается через `spring.threads.virtual.enabled=true`.
- `spring-boot-starter-webflux` и `spring-boot-starter-web` — взаимоисключающие на classpath. Это форсирует атомарный flip-коммит для `build.gradle` + `SecurityConfig` + `application.properties` cookie-prefix.
- `WebSession` / `ServerWebExchange` / `Mono` / `Flux` / Reactor-types — исчезают с classpath в момент удаления WebFlux-стартера. Любой pre-flip модуль, который их всё ещё импортирует, ломает сборку.
- Существующий SLA Epic 04b: webhook P99 `<100ms` должен сохраниться.
- JobRunr `jobrunr-spring-boot-3-starter:7.3.2` — совместим со Spring Boot 3.x (не 4.x); версия не меняется.
- MVP-кэп проектов: 5 на пользователя. `ProjectController.list` возвращает `List<ProjectResponse>` (не streaming `Flux`) — допустимо при таком кэпе.

**Архитектурные (no-go list):**

- НЕ добавляем Lombok.
- НЕ меняем frontend.
- НЕ меняем external integrations (Telegram, SMTP, MongoDB, Redis, JobRunr).
- НЕ меняем схему MongoDB-коллекций и Redis-keys.
- НЕ добавляем circuit breaker / bulkhead / Resilience4j (retry — hand-rolled while-loop + Thread.sleep).
- НЕ упреждающе боремся с VT pinning (наблюдаем через `-Djdk.tracePinnedThreads=full`, документируем сайты, но НЕ затыкаем Semaphore'ами).

**Сохраняемое поведение (regressions to guard against):**

- `ProjectController.list` для пользователя без проектов возвращает `[]` (пустой JSON-массив), НЕ `null`.
- Webhook idempotency: повторный `(projectId, updateId)` → `DuplicateKeyException` → 200 OK + детерминистический re-enqueue в JobRunr с тем же UUID (никогда — двойная обработка).
- `ProcessTelegramUpdateJob` DONE re-entry guard: воркер на retry с уже-DONE статусом — short-circuit до записи audit event и до вызова downstream stubs.
- Redis fail-open: при недоступности Redis brute-force / rate-limit counters работают в fail-open режиме (WARN log; login успешен). Behavior сохраняется.
- `MongoTemplate.findAndModify(...)` с предикатом, который не сматчился — возвращает `null` (вместо `Mono.empty()`). Воркер `ProcessTelegramUpdateJob.populateOwnerChatIdIfFirst` трактует `null` как benign no-op.
- Validation 400 response shape: `MethodArgumentNotValidException` → JSON с `code: null` (frontend `useApiError` контракт сохраняется).

**Процесс:**

- Соло-разработчик; нет in-flight фич от других людей.
- Дедлайна нет.
- Dev env можно временно ломать.
- Production хостинг ещё не настроен (deployment.md → TBD). Миграция — это улучшение для текущего local-dev + staging-smoke workflow, без production deploy.

## Риски

- **R1: VT pinning на BCrypt и Lettuce internal `synchronized`-блоках** (pre-Java 24 carrier-thread pinning). Реальный эффект на проде неизвестен. **Митигация:** при boot пускаем backend с `-Djdk.tracePinnedThreads=full`, документируем найденные pinning-сайты в `patterns.md`. НЕ требуем нулевого pinning — это observational gate, не блокер. Если в будущем pinning станет проблемой — Spring Boot 4 / JDK 24 решит большинство кейсов.

- **R2: Lettuce sync API под VT блокирует VT на Netty CompletableFuture.** Это задокументированный happy-path для VT (carrier-thread парк-анпарк). Нет реального риска; документируем в `patterns.md` для будущих разработчиков, чтобы не пытались переходить на async-API «оптимизации ради».

- **R3: Spring Session schema continuity.** Коллекция `sessions` содержит поле `principal`, по которому `AuthService.terminateAllSessions(userId)` и `ProfileService.terminateAllSessionsExcept(...)` ищут сессии для инвалидации. Под servlet `MongoIndexedSessionRepository` поле `principal` сохраняется в той же форме (verified в code-research §3.3 + `spring-session-data-mongodb` source). **Митигация:** в integration-тесте `AuthServiceTest` (mvc-версия) — кейс «logged-in user X, change-password всех остальных сессий → запрос на сессию Y возвращает 401» подтверждает, что путь сохранён.

- **R4: CSRF cookie write path** изменится с deferred `WebFilter csrfCookieMaterializer` (текущий) на eager `CsrfFilter` (MVC default). Если frontend перестанет получать `XSRF-TOKEN` cookie — POST/PUT/DELETE начнут возвращать 403. **Митигация:** существующий integration-тест на CSRF переписан на MockMvc и проверяет cookie в response. Manual UI smoke (AC11-13) подтверждают, что forms POST'ятся успешно.

- **R5: In-flight session bytes-shape.** Сериализованные `SecurityContext` в коллекции `sessions`, записанные `ReactiveMongoSessionRepository` ДО flip'а, должны корректно десериализоваться `MongoIndexedSessionRepository` ПОСЛЕ flip'а. Документация Spring Session говорит, что schema идентична — но на практике это надо проверить. **Митигация:** перед flip'ом разработчик логинится в dev-env через UI; после flip'а перезагружает страницу — должен остаться залогиненным. Если ломается — приемлемая одноразовая ре-логин-волна (production пока без живых пользователей), но факт фиксируется.

- **R6: JobRunr in-flight jobs.** `ProcessTelegramUpdateJob.handle(String)` имеет стабильную сигнатуру, но lambda-захват внутри `JobScheduler.enqueue(UUID, ...)` может иметь разную сериализацию pre/post-flip. **Митигация:** перед flip'ом — операторская процедура «drain JobRunr queue to zero» (mongosh inspect `jobrunr_jobs` для `state IN ['ENQUEUED','PROCESSING']`, ждать пока пусто). После flip'а — integration-тест на worker entrypoint подтверждает, что новый `ProcessTelegramUpdateJob` корректно подхватывается.

- **R7: Удаление неявной back-pressure через `boundedElastic`.** Текущий `Schedulers.boundedElastic` имплицитно ограничивал параллелизм BCrypt-операций (cost-12, ~250ms CPU) до ~10×CPU. Под VT каждый запрос — собственный VT, параллелизм = N concurrent registrations / logins. При register-storm возможна CPU-starvation. **Митигация:** реактивно — если/когда production load profile покажет проблему, добавим `Semaphore` (VT-safe) точечно. Сейчас принимаем (decision D4), поскольку текущая защита и так не была серьёзной (cap ~10×CPU).

**Rollback strategy для атомарного flip-коммита:** `git revert <flip-commit-sha>` возвращает всю миграцию назад одним коммитом. Промежуточного состояния не существует — `spring-boot-starter-webflux` и `spring-boot-starter-web` взаимоисключающи, поэтому либо весь стек реактивный, либо весь блокирующий. Pre-flip module PR'ы (service/repo layer rewrites) — независимо revert'ятся стандартным образом. Если деплой production будет настроен ДО flip'а — на момент flip-коммита нужен plan для DB session-bytes-continuity check (R5).

## Технические решения

- **D1.** Мы решили использовать **Spring 6.x `RestClient`** как замену `WebClient` для outbound HTTP (`TelegramApiClient` + `TelegramSender`), потому что это минимальная дельта API и существующие `MockWebServer`-based integration-тесты переносятся без изменений.
- **D2.** Мы решили использовать **hand-rolled `while`-loop + `Thread.sleep`** для retry-стратегии outbound Telegram-клиентов (вместо Spring Retry / Resilience4j), потому что у нас всего 2 клиента, retry-инварианты доменно-специфичны (429 wraps 5xx, clamped `retry_after`, jitter), и явный код проще читать и тестировать, чем декларативные аннотации. Под VT `Thread.sleep` дешёвый.
- **D3.** Мы решили реализовать **remember-me cookie Max-Age через subclass `DefaultCookieSerializer`** (Spring Session), потому что Spring Session экспонирует публичный API именно для этого; альтернатива (custom servlet `Filter`, переписывающий response cookie) — больше boilerplate и боя с servlet response wrapping.
- **D4.** Мы решили **ничего не делать с BCrypt back-pressure** под VT, потому что текущий `Schedulers.boundedElastic` тоже не даёт настоящей защиты (cap ~10×CPU), а это не регрессия. Если на проде увидим register-storm — добавим `Semaphore` точечно (Semaphore VT-safe).
- **D5.** Мы решили делать **атомарный flip-коммит** для всех файлов, которые ссылаются на исчезающие WebFlux-типы (`build.gradle` + cookie-prefix в `application.properties` + security-конфиг + все остальные классы с WebFlux-импортами), потому что `spring-boot-starter-webflux` и `spring-boot-starter-web` взаимоисключающи на classpath. Strangler-fig применяется только к слоям service/repository ДО этого коммита; конкретный перечень файлов в flip — в tech-spec.
- **D6.** Мы решили **`ProjectController.list` возвращать `List<ProjectResponse>`** (не streaming `Flux`), потому что MVP-кэп 5 проектов на пользователя — streaming не даёт ни одного бенефита при таком объёме.
- **D7.** Мы решили **разрешить циклическую зависимость `auth → security`** (через константу `REMEMBER_ME_ATTR`) ДО начала миграции, потому что иначе `auth` и `security` приходится мигрировать одновременно. (Конкретная техника — перенос константы или инлайн литерала — в tech-spec.)
- **D8.** Мы решили **НЕ добавлять Lombok** в проект, потому что Java 21 records + ctor-injection покрывают типичные use-cases Lombok, а сама зависимость на annotation processor и обязательный IDE plugin для новых разработчиков — лишняя сложность.
- **D9.** Мы решили **мигрировать `AbstractIntegrationTest` + все 12 IT-файлов внутри атомарного flip PR**, потому что параллельный fork двух базовых классов — больше хаоса, чем выгоды; pre-flip module PR'ы касаются только unit-тестов (StepVerifier rewrites в service-layer).
- **D10.** Мы решили использовать **единый VT-based concurrency idiom** во всех concurrency-тестах (3 сайта), потому что один идиом проще поддерживать. (Конкретный класс/паттерн — в tech-spec.)
- **D11.** Мы решили **оставить JobRunr Mongo-config как есть** (без консолидации с основным MongoClient bean), потому что JobRunr уже работает с этой конфигурацией; консолидация — рефакторинг без явной выгоды.

## Тестирование

**Unit-тесты:** делаются всегда, не обсуждаются. Все unit-тесты с реактивными test-helpers (`StepVerifier`) переписываются на прямые JUnit-assertions.

**Интеграционные тесты:** делаем — критично для refactoring такого масштаба. Все integration-тесты с реактивными HTTP-тест-клиентами переписываются на MVC-эквивалент. Базовый класс интеграционных тестов мигрирует синхронно с реальным контроллером в атомарном flip-коммите. Slice-тесты мигрируют на servlet-stack аналог.

**E2E тесты:** не делаем — frontend не меняется, существующий Playwright E2E (`frontend/e2e/i18n.spec.ts`) уже покрывает golden path и продолжит работать без модификаций.

**Concurrency-тесты:** существующие сайты с реактивным concurrency primitive (`Schedulers.*`) мигрируют на единый VT-based idiom. Инварианты тестов (status pair `{200,409}`, persisted-row count, retry-attempts) сохраняются.

**Mock-triple для security/wiring-only тестов:** все затронутые файлы обновляются — реактивные mock-types (`reactivestreams.client.MongoClient`, `ReactiveRedisConnectionFactory`) заменяются на синхронные эквиваленты.

(Конкретные имена классов / mutators / mock FQN — в tech-spec и в diff'ах PR'ов.)

## Как проверить

### Агент проверяет

| Шаг | Инструмент | Ожидаемый результат |
|-----|-----------|-------------------|
| 1. Прогон полного test suite | `./gradlew test` | Зелёный на всех тест-файлах (AC1) |
| 2. Прогон slow-tagged P99 probe | `./gradlew test -PrunSlow=true` | Зелёный; webhook P99 latency `<100ms` (AC2) |
| 3. Grep gate: реактивные импорты | `grep -rE "reactor\.\|StepVerifier\|WebTestClient\|Mono<\|Flux<\|Reactive(Mongo\|Crud\|Redis)" backend/src` | 0 совпадений (AC3) |
| 4. Grep gate: Lombok | `grep -E "lombok" backend/build.gradle` | 0 совпадений (AC4) |
| 5. Grep gate: VT-property | `grep "spring.threads.virtual.enabled=true" backend/src/main/resources/application.properties` | 1 совпадение (AC5) |
| 6. Grep gate: реактивные стартеры | `grep -E "starter-webflux\|data-mongodb-reactive\|data-redis-reactive\|reactor-test" backend/build.gradle` | 0 совпадений (AC6) |
| 7. Boot с VT-pinning probe | `./gradlew bootRun -Pargs="-Djdk.tracePinnedThreads=full"` + анализ логов | Backend стартует; pinning-сайты задокументированы в `patterns.md` (AC7) |
| 8. Доку-апдейт | `grep -E "WebFlux\|ServerHttpSecurity\|@EnableWebFluxSecurity\|WebTestClient\|ReactiveRedisConnectionFactory\|reactivestreams.client" .claude/skills/project-knowledge/references/{patterns,architecture,deployment}.md` | 0 совпадений (AC14) |

### Пользователь проверяет

- **AC8 / AC9 / AC10 — staging smoke runbooks (10-15 минут каждый):** опционально на staging или локально с throwaway BotFather-ботом + ngrok-туннелем. Без реального Telegram эти runbook'и автоматически не проверяются — это de-facto post-deploy verification (см. `deployment.md` Decision 19 для Epic 04b).
- **AC11 — auth happy-path в браузере:** открыть `http://localhost:3000`, зарегистрироваться, забрать verification email из Mailpit `http://localhost:8025`, кликнуть ссылку, залогиниться, разлогиниться. Должно работать без 5xx и без зависших спиннеров.
- **AC12 — remember-me cookie Max-Age:** залогиниться с `rememberMe=true`, открыть DevTools → Application → Cookies → найти `SESSION`-cookie → подтвердить, что `Max-Age` ≈ 30 дней. Разлогиниться, залогиниться с `rememberMe=false` → `SESSION`-cookie без `Max-Age` (session-scoped).
- **AC13 — forgot/reset flow:** запросить reset, забрать ссылку из Mailpit, перейти, ввести новый пароль, залогиниться новым паролем.
- **R5 / R6 ручные шаги:** перед flip-коммитом — драйнить JobRunr-очередь (mongosh `db.jobrunr_jobs.find({state:{$in:['ENQUEUED','PROCESSING']}}).count() === 0`); опционально залогиниться в dev-env через UI, после flip'а — обновить страницу и подтвердить, что остался залогиненным (или зафиксировать вынужденный re-login).
