# Code Research: migrate-to-virtual-threads

Inventory of every reactive footprint and migration-risk surface in `backend/` for the Spring WebFlux → Spring MVC + Java 21 virtual threads migration. Source paths are absolute. No solutions proposed — see tech-spec for that.

Scope cut: this is the backend only. The Nuxt frontend has no virtual-threads stake.

---

## 1. Reactive footprint inventory

### 1.1 Files using `Mono`/`Flux` types

All files below import `reactor.core.publisher.Mono` and/or `reactor.core.publisher.Flux` from `backend/src/main/java/com/botfunnel/`.

#### Module: `common/`

- `common/GlobalErrorHandler.java` — `@RestControllerAdvice`. Three `ResponseEntity<ErrorResponse>` handlers (`AppException`, `WebExchangeBindException`, `ResponseStatusException`, `Throwable` catch-all). **Imports `org.springframework.web.bind.support.WebExchangeBindException`** — WebFlux-specific binding error type; the MVC equivalent is `MethodArgumentNotValidException`.

#### Module: `auth/`

- `auth/AuthController.java` — `Mono<ResponseEntity<...>>` on every handler. Takes `ServerWebExchange exchange` for IP/UA/session/cookie wiring. Endpoints: `/api/auth/login`, `/api/auth/me`, `/api/auth/register`, `/api/auth/verify-email`, `/api/auth/resend-verification`, `/api/auth/logout`, `/api/auth/forgot-password`, `/api/auth/reset-password`.
- `auth/AuthService.java` — 650 LOC. The heaviest reactive class. Uses `ReactiveMongoTemplate`, `ReactiveRedisTemplate<String,String>`, `ServerSecurityContextRepository` (saves SecurityContext to WebSession), `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` for BCrypt, `ReactiveSecurityContextHolder.getContext()` for `me()`, `WebSession.invalidate()` in logout. Heavy use of `Mono.zip`, `Mono.defer`, `Mono.fromRunnable`, `onErrorResume`, `switchIfEmpty`, `then`, `flatMap`. **Method signature surfaces**:
  - `Mono<AuthResponse> login(LoginRequest, ServerWebExchange)`
  - `Mono<MeResponse> me()`
  - `Mono<RegisterResponse> register(RegisterRequest, ServerWebExchange)`
  - `Mono<Void> resendVerification(String)`
  - `Mono<Void> logout(ServerWebExchange)`
  - `Mono<Void> forgotPassword(String, ServerWebExchange)`
  - `Mono<Void> resetPassword(String, String, ServerWebExchange)`
  - `Mono<VerifyEmailResponse> verifyEmail(String, ServerWebExchange)`
  - `Mono<Long> terminateAllSessions(String)` (queries `sessions` collection by `principal`)

#### Module: `email/`

- `email/EmailService.java` — `sendAsync(...)` uses `Mono.<Void>fromRunnable(...).subscribeOn(Schedulers.boundedElastic()).subscribe(null, log::error)` to fire-and-forget SMTP send. `JavaMailSender` itself is blocking. **No `Mono`/`Flux` in the public API** — public methods return `void`.

#### Module: `events/`

- `events/EventService.java` — `logEvent(...)` fire-and-forget: `eventRepository.save(event).subscribe(null, err -> log.error(...))`. `logEventBlocking(...)` returns `Mono<Event>` so callers can `.block()` to enforce ordering (used by `ProcessTelegramUpdateJob`).
- `events/EventRepository.java` — `extends ReactiveMongoRepository<Event, String>`. Only inherited methods (`save`, `findById`, …).

#### Module: `user/`

- `user/UserRepository.java` — `extends ReactiveMongoRepository<User, String>`. Custom finders:
  - `Mono<User> findByEmail(String)`
  - `Mono<User> findByEmailVerificationTokenHash(String)`
  - `Mono<User> findByPasswordResetTokenHash(String)`
  - `Flux<User> findByStatusAndDeletedAtBefore(UserStatus, Instant)`
- `user/UserService.java` — Thin wrapper. `findByEmail(String): Mono<User>`, `save(User): Mono<User>`, `softDelete(String): Mono<User>`.

#### Module: `project/`

- `project/ProjectController.java` — `Mono<ResponseEntity<...>>` everywhere. List endpoint returns `Mono<ResponseEntity<Flux<ProjectResponse>>>` — note the nested `Flux` body (streaming JSON array).
- `project/ProjectService.java` — `Mono`/`Flux` returns. **Key surface**: `requireOwned(ownerId, projectId, includeSoftDeleted): Mono<Project>` is the platform's tenancy guard, called from every project-scoped service (currently BotService; future epics). `list(...): Flux<Project>`, `create/update/softDelete/restore: Mono<Project>`.
- `project/ProjectRepository.java` — `extends ReactiveMongoRepository<Project, String>`. Custom finders return `Mono`/`Flux` of `Project`. Six methods including `countByOwnerIdAndDeletedAtIsNull(String): Mono<Long>` and `findByDeletedAtBefore(Instant): Flux<Project>` (used by ProjectHardDeleteJob).

#### Module: `bot/`

- `bot/BotController.java` — Four endpoints (`get`, `connect`, `disconnect`, `test-message`) all `Mono<ResponseEntity<...>>`. `ReactiveSecurityContextHolder.getContext()` for `currentUserId()`.
- `bot/BotService.java` — Connect-orchestration chain. `Mono<Bot> connect(...)`, `Mono<Void> disconnect(...)`, `Mono<Void> sendTestMessage(...)`. Uses `ReactiveRedisTemplate` for brute-force counters. Calls `telegramApiClient.setWebhook(...).then(...)`, `botRepository.save(...).onErrorResume(compensateAndPropagate)`.
- `bot/BotRepository.java` — `extends ReactiveMongoRepository<Bot, String>`. Three custom finders: `findByProjectIdAndStatus`, `findByProjectId` (`Flux<Bot>`), `findFirstByTelegramBotIdAndStatus`.
- `bot/TelegramApiClient.java` — **WebClient site #1**. Constructs `WebClient` via `WebClient.Builder` + Reactor Netty `HttpClient`. Public Mono-returning methods: `getMe(token): Mono<TelegramUser>`, `setWebhook(token,url,secret): Mono<Boolean>`, `deleteWebhook(token): Mono<Boolean>`. `Retry.backoff(3, 200ms).maxBackoff(2s)`. `MONO_TIMEOUT = 10s` `.timeout(...)`. `mapClientError(ClientResponse)` for 4xx mapping.
- `bot/TelegramSender.java` — **WebClient site #2**. Separate WebClient with its own retry budget (1s/2s/4s, 3 retries) and a 30s outer `.timeout(...)`. Uses two stacked `.retryWhen(...)` for 5xx and 429 retry-after. `sendText(...): Mono<SentMessage>`. Owns `EVENT_TELEGRAM_MESSAGE_SENT` / `EVENT_TELEGRAM_SEND_FAILED` audit emission. Uses `ThreadLocalRandom.current().nextLong(...)` for jitter.

#### Module: `profile/`

- `profile/ProfileController.java` — `Mono<ResponseEntity<...>>` everywhere; `ServerWebExchange` taken for change-password (gets session via `exchange.getSession()`).
- `profile/ProfileService.java` — `Mono<ProfileResponse> getProfile/updateProfile`; `Mono<Void> changePassword(...)`; `Mono<Long> terminateAllSessions/terminateAllSessionsExcept`; `Mono<Void> deleteAccount`. Uses `ReactiveMongoTemplate` for direct `sessions` collection queries, `ReactiveRedisTemplate` for change-password rate limiting. `WebSession` passed in from controller.

#### Module: `security/`

- `security/SecurityConfig.java` — `@EnableWebFluxSecurity`. Builds `SecurityWebFilterChain` from `ServerHttpSecurity`. Uses `CookieServerCsrfTokenRepository`, `ServerCsrfTokenRequestAttributeHandler`, `AndServerWebExchangeMatcher`, `NegatedServerWebExchangeMatcher`, `PathPatternParserServerWebExchangeMatcher`, `CsrfWebFilter.DEFAULT_CSRF_MATCHER`, `WebSessionServerSecurityContextRepository`, `UrlBasedCorsConfigurationSource` (from `web.cors.reactive`), `WebFilter`. `csrfCookieMaterializer()` returns a `WebFilter` that subscribes to the deferred `CsrfToken` to materialise the XSRF-TOKEN cookie. See §2 for migration-equivalent class names.
- `security/RememberMeWebSessionIdResolver.java` — `extends CookieWebSessionIdResolver` (WebFlux only). Overrides `setSessionId(ServerWebExchange, String)` to read a per-request `REMEMBER_ME_ATTR` exchange attribute and branch the cookie's Max-Age. Uses `org.springframework.boot.web.server.Cookie` + `ResponseCookie` + `PropertyMapper`. **No servlet-stack equivalent class exists** — this is a WebFlux-only abstraction.

#### Module: `webhook/`

- `webhook/TelegramWebhookController.java` — `Mono<ResponseEntity<Void>> receive(...)`. Path-scoped POST handler. Heavy use of `flatMap`, `switchIfEmpty(Mono.defer(...))`, `onErrorResume(DuplicateKeyException.class, ...)`, `doFinally`. **Internally calls `Mono.fromRunnable(() -> enqueuer.accept(...)).subscribeOn(Schedulers.boundedElastic())`** to dispatch JobRunr `JobScheduler.enqueue` — already a blocking-bridge.
- `webhook/WebhookPayloadSizeFilter.java` — Implements `WebFilter` (WebFlux). `filter(ServerWebExchange, WebFilterChain): Mono<Void>`. Path-scoped 413 gate at `HIGHEST_PRECEDENCE + 10`. Uses `org.springframework.web.util.pattern.PathPattern` (servlet/reactive-neutral).
- `webhook/WebhookSecretVerifier.java` — Pure stateless `@Component`. No reactive types. Migrates as-is.
- `webhook/RawUpdateRepository.java` — `extends ReactiveMongoRepository<RawUpdate, String>`. `Mono<RawUpdate> findFirstByProjectIdAndUpdateId(...)`.
- `webhook/ProcessTelegramUpdateJob.java` — **JobRunr worker**. Public method is `void handle(String rawUpdateId)` — synchronous shape. Inside it, every reactive call ends in `.block()` (~12 occurrences). Uses `ReactiveMongoTemplate.findAndModify(...).block()` twice (failure write, ownerChatId CAS). The JobRunr worker thread is NOT a Reactor event loop, so blocking is intentional.

#### Top-level

- `HealthController.java` — `@GetMapping("/health")` returns `Mono<Map<String, String>>`. Trivial.
- `BotFunnelApplication.java` — `@SpringBootApplication` with `excludeName = "org.jobrunr.spring.autoconfigure.metrics.JobRunrMetricsAutoConfiguration"`. No reactive types.

### 1.2 Reactive repository interfaces

All are `extends ReactiveMongoRepository<T, String>`:

| File | Repository | Used by | Operations |
|------|------------|---------|------------|
| `bot/BotRepository.java` | `BotRepository` | BotService, TelegramSender, TelegramWebhookController, ProcessTelegramUpdateJob | findByProjectIdAndStatus, findByProjectId (Flux), findFirstByTelegramBotIdAndStatus, save, findById |
| `project/ProjectRepository.java` | `ProjectRepository` | ProjectService, BotService (via requireOwned), TelegramWebhookController, ProjectHardDeleteJob, ProcessTelegramUpdateJob | Six custom finders, countByOwnerIdAndDeletedAtIsNull, save, findById |
| `user/UserRepository.java` | `UserRepository` | AuthService, UserService, SuperAdminSeeder, ProfileService, HardDeleteJob | findByEmail, findByEmailVerificationTokenHash, findByPasswordResetTokenHash, findByStatusAndDeletedAtBefore (Flux), save, deleteAll, findById |
| `events/EventRepository.java` | `EventRepository` | EventService | only inherited (`save`) |
| `webhook/RawUpdateRepository.java` | `RawUpdateRepository` | TelegramWebhookController, ProcessTelegramUpdateJob | findFirstByProjectIdAndUpdateId, save, findById, count |

Consumer patterns: every service that uses these calls `.flatMap` / `.switchIfEmpty` / `.then` chains; JobRunr workers and `SuperAdminSeeder.run(ApplicationArguments)` use `.block()` to consume the same APIs synchronously.

### 1.3 Files using `WebClient`

Two production sites, both in `bot/`:

- `backend/src/main/java/com/botfunnel/bot/TelegramApiClient.java:47` — `private final WebClient webClient;` constructed with Reactor Netty `HttpClient.create()` + `ChannelOption.CONNECT_TIMEOUT_MILLIS` + `responseTimeout(10s)`.
- `backend/src/main/java/com/botfunnel/bot/TelegramSender.java:64` — same shape; distinct WebClient instance, distinct retry policy.

Other modules use Spring MVC / Netty types but do **not** invoke `WebClient` (verified via grep). The user prompt mentions "3 known"; the actual count in production code is **2**. There may have been an in-flight third instance considered (TelegramSender was extracted from TelegramApiClient), but the current tree has only these two.

### 1.4 Files using `ServerWebExchange` / `WebFilter` / matchers

| File | Surface |
|------|---------|
| `auth/AuthController.java` | `ServerWebExchange exchange` parameter on login, register, verifyEmail, logout, forgotPassword, resetPassword |
| `auth/AuthService.java` | `ServerWebExchange.getSession()`, `exchange.getAttributes().put(...)`, `exchange.getRequest().getHeaders().getFirst("X-Forwarded-For")`, `exchange.getRequest().getRemoteAddress()`, `WebSession.invalidate()` |
| `bot/BotController.java` | `ServerWebExchange exchange` for IP/UA extraction |
| `profile/ProfileController.java` | `ServerWebExchange exchange.getSession()` and IP/UA extraction |
| `project/ProjectController.java` | `ServerWebExchange exchange` for IP/UA extraction |
| `security/SecurityConfig.java` | `WebFilter csrfCookieMaterializer()`; matchers `AndServerWebExchangeMatcher`, `NegatedServerWebExchangeMatcher`, `PathPatternParserServerWebExchangeMatcher`; `ServerHttpSecurity`; `SecurityWebFilterChain` |
| `security/RememberMeWebSessionIdResolver.java` | `setSessionId(ServerWebExchange, String)` override |
| `webhook/WebhookPayloadSizeFilter.java` | `implements WebFilter` — `filter(ServerWebExchange, WebFilterChain): Mono<Void>` |

`ServerHttpRequest` and `ServerHttpResponse` are accessed transitively via `ServerWebExchange.getRequest()` / `.getResponse()`. No file uses them as direct parameters.

### 1.5 Schedulers usage

`Schedulers.boundedElastic()` is the only scheduler referenced. Total: 8 sites in production code.

- `auth/AuthService.java:367` — BCrypt encode (resetPassword)
- `auth/AuthService.java:421` — BCrypt encode (register)
- `auth/AuthService.java:523` — BCrypt match (handleUserNotFound — dummy hash)
- `auth/AuthService.java:533` — BCrypt match (authenticate — real hash)
- `email/EmailService.java:94` — fire-and-forget SMTP send
- `profile/ProfileService.java:86` — BCrypt match (changePassword)
- `profile/ProfileService.java:93` — BCrypt encode (changePassword)
- `webhook/TelegramWebhookController.java:183` — `Mono.fromRunnable(enqueuer.accept(...))` for JobRunr `JobScheduler.enqueue`

No `Schedulers.parallel()`, `Schedulers.single()`, or custom scheduler bean.

### 1.6 `Mono.fromCallable` / `Mono.fromRunnable` (fake-reactive flags)

These are all "blocking work pinned to boundedElastic" — pure migration wins under VT. 15 occurrences:

- `auth/AuthService.java` lines 366, 420, 470, 506, 522, 525, 532, 537, 571, 577, 585
- `profile/ProfileService.java` lines 85, 92, 179
- `webhook/TelegramWebhookController.java` line 182

In every case the wrapped callable is synchronous (BCrypt, audit event emission, JobScheduler.enqueue). Under VT, these can directly call the wrapped operation.

---

## 2. Reactive security stack

`security/SecurityConfig.java` is the entire surface. Each WebFlux class names its servlet-stack equivalent below; mark "behaviour drift" where the swap is not 1:1.

| WebFlux class | Servlet equivalent | Behaviour drift |
|---|---|---|
| `@EnableWebFluxSecurity` | `@EnableWebSecurity` | None — both gate the security filter chain. |
| `SecurityWebFilterChain` | `SecurityFilterChain` | None — same role, builder return type. |
| `ServerHttpSecurity` | `HttpSecurity` | Same fluent DSL; method names mostly match (`csrf`, `cors`, `authorizeExchange` → `authorizeHttpRequests`, `httpBasic`, `formLogin`). |
| `CookieServerCsrfTokenRepository.withHttpOnlyFalse()` | `CookieCsrfTokenRepository.withHttpOnlyFalse()` | Cookie semantics identical. |
| `ServerCsrfTokenRequestAttributeHandler` | `CsrfTokenRequestAttributeHandler` | Same role: raw token (no BREACH masking). |
| `XorServerCsrfTokenRequestAttributeHandler` | `XorCsrfTokenRequestAttributeHandler` | Same role; we deliberately use the plain handler. |
| `CsrfWebFilter.DEFAULT_CSRF_MATCHER` | `CsrfFilter` default matcher (no public constant) | **Drift**: the MVC `CsrfFilter` doesn't expose `DEFAULT_CSRF_MATCHER` publicly. Equivalent: `new HttpSessionCsrfTokenRepository()`-default verb matcher logic must be re-expressed via `RequestMatchers.matcher(POST, PUT, DELETE, PATCH)` or a custom `RequestMatcher`. |
| `AndServerWebExchangeMatcher` | `AndRequestMatcher` | Available in `org.springframework.security.web.util.matcher`. |
| `NegatedServerWebExchangeMatcher` | `NegatedRequestMatcher` | Same. |
| `PathPatternParserServerWebExchangeMatcher` | `PathPatternRequestMatcher` (6.5.x) or `AntPathRequestMatcher` | **Drift**: the API differs and `PathPatternRequestMatcher` is a Spring Security 6.5+ addition. The pattern syntax for `/webhooks/telegram/{projectId}` survives. |
| `WebSessionServerSecurityContextRepository` | `HttpSessionSecurityContextRepository` | **Behavioural drift**: WebFlux variant uses Reactor's `WebSession`; servlet uses `HttpSession` with eager attribute write. Manual login in `AuthService.openSession` (`securityContextRepository.save(exchange, context)`) is async/Mono in WebFlux; in MVC it's a synchronous call. |
| `ServerSecurityContextRepository` (interface) | `SecurityContextRepository` (interface) | Same role; bean signature changes. |
| `UrlBasedCorsConfigurationSource` (`web.cors.reactive`) | `UrlBasedCorsConfigurationSource` (`web.cors`) | Same class name, different package. |
| `WebFilter` | `Filter` (servlet) or `OncePerRequestFilter` | **Drift**: `WebhookPayloadSizeFilter` and `csrfCookieMaterializer` need full rewrite. Servlet `OncePerRequestFilter.doFilterInternal(req, res, chain)` is synchronous; no `Mono<Void>` return. |
| `WebSessionIdResolver` + `CookieWebSessionIdResolver` | No direct analog | **Major drift**: servlet stack uses Tomcat's `JSESSIONID`-cookie infrastructure with no resolver abstraction. `RememberMeWebSessionIdResolver` per-request Max-Age branching has to be re-implemented as a custom `Filter` that writes the cookie post-handler, or by switching to Spring Session's `CookieSerializer` with custom logic. |
| `spring-session-data-mongodb` reactive variant | `spring-session-data-mongodb` (servlet) | Same starter, but the auto-configuration picks `MongoIndexedSessionRepository` (servlet) vs `ReactiveMongoSessionRepository` (reactive). Document shape is **identical** on disk — the `sessions` collection's `principal` field path (queried by AuthService and ProfileService) is preserved across the migration. Verified by `spring-session-data-mongodb` source. |

CSRF setup specifics:

- Cookie holds raw token (not BREACH-masked).
- Decision 13 / `SecurityConfig.java:77-81` builds the protection matcher as `AND(DEFAULT_CSRF_MATCHER, NEG(PathPattern("/webhooks/telegram/{projectId}")))` — single-segment path scope; verb restriction stays (POST/PUT/DELETE/PATCH).
- `csrfCookieMaterializer()` is a `WebFilter` added after `SecurityWebFiltersOrder.CSRF`. Its sole purpose is to subscribe to the `Mono<CsrfToken>` exchange attribute so the deferred token actually gets emitted and the `XSRF-TOKEN` cookie is written. **Migration**: MVC's `CsrfFilter` writes the token eagerly via `CsrfTokenRequestAttributeHandler.handle`; this whole helper goes away.

`WebFilter` instances and ordering in current code:

1. `WebhookPayloadSizeFilter` (`@Order(HIGHEST_PRECEDENCE + 10)`) — runs before the security chain.
2. Implicit Boot filters (`HttpWebHandlerAdapter`, etc.).
3. `csrfCookieMaterializer` (registered via `http.addFilterAfter(..., SecurityWebFiltersOrder.CSRF)`).

---

## 3. Reactive Mongo / Redis usage

### 3.1 Custom `ReactiveMongoTemplate` queries

| File:line | Operation | Collection | Returns |
|---|---|---|---|
| `auth/AuthService.java:392` | `remove(Query, "sessions")` — `Criteria.where("principal").is(userId)` | sessions | `Mono<DeleteResult>` (mapped to `Mono<Long>`) |
| `jobs/ProjectHardDeleteJob.java:82` | `remove(Query, "events")` — `Criteria.where("metadata.projectId").in(deletedIds)` | events | `Mono<DeleteResult>` |
| `jobs/ProjectHardDeleteJob.java:98` | `remove(Query, "projects")` — `Criteria.where("_id").in(deletedIds)` | projects | `Mono<DeleteResult>` |
| `profile/ProfileService.java:115` | `remove(Query, "sessions")` — by principal | sessions | `Mono<DeleteResult>` |
| `profile/ProfileService.java:124` | `remove(Query, "sessions")` — by principal AND `_id != currentSessionId` | sessions | `Mono<DeleteResult>` |
| `webhook/ProcessTelegramUpdateJob.java:122` | `findAndModify(Query(_id=rawUpdateId), Update.set("processingStatus", "FAILED").set("processingError", truncated), RawUpdate.class)` | raw_updates | `Mono<RawUpdate>` |
| `webhook/ProcessTelegramUpdateJob.java:222` | `findAndModify(Query(_id=botId, status=CONNECTED, ownerChatId=null), Update.set("ownerChatId", chatId), Bot.class)` — CAS for first-writer-wins | bots | `Mono<Bot>` (empty = predicate-fail) |

No `aggregate` or `mapReduce` calls. No custom `@Aggregation` or `@Query` annotations in any repository.

### 3.2 Reactive Redis operations

All via `ReactiveRedisTemplate<String, String>`. Counter / rate-limit operations:

- **AuthService** (login brute-force, register rate, resend-verification rate, forgot-password rate):
  - `opsForValue().increment(key)` — counter INCR
  - `expire(key, BRUTE_TTL)` — first-set TTL
  - `delete(key1, key2)` — reset on success (varargs)
  - `opsForValue().get(key)` — read counter
  - `opsForValue().setIfAbsent(key, "1", TTL)` — SET NX EX (resend/forgot semantics)
- **BotService** (bot-connect brute-force per user):
  - `opsForValue().increment(key)`, `expire`, `delete` — same shape
- **ProfileService** (change-password brute-force per user):
  - `opsForValue().get/increment/expire/delete`

All Redis ops have an `onErrorResume` fail-open WARN log (Decision 4 across modules). Lettuce is the underlying client (Spring Boot `spring-boot-starter-data-redis-reactive` default).

### 3.3 Spring Session Mongo reactive

`build.gradle` declares `org.springframework.session:spring-session-data-mongodb` (the version-neutral starter). `application.properties` sets:

```
spring.session.store-type=mongodb
spring.session.mongodb.collection-name=sessions
server.reactive.session.cookie.*
```

The `server.reactive.session.cookie.*` keys are read by Boot's `ReactiveSessionAutoConfiguration` only when the application is a WebFlux app. The starter ships **both** servlet (`MongoIndexedSessionRepository`) and reactive (`ReactiveMongoSessionRepository`) auto-configurations; Boot picks one based on `WebApplicationType`. **No code change is needed in `spring-session-data-mongodb`** — only the cookie-prefix property (`server.reactive.session.cookie` → `server.servlet.session.cookie`) needs to flip when the web stack changes.

---

## 4. Tests

### 4.1 `WebTestClient` users (12 files)

- `AbstractIntegrationTest.java` — base class; lazily rebinds `WebTestClient.bindToApplicationContext(applicationContext)` per test (because the autowired RANDOM_PORT client is bind-to-server, which breaks `csrf()` mutators).
- `HealthEndpointTest.java`, `HealthSecurityTest.java`, `SecurityBlockTest.java` — `@SpringBootTest(webEnvironment = RANDOM_PORT)` with `@MockitoBean` on `MongoClient`/`RedisConnectionFactory`/`ReactiveRedisConnectionFactory`. Pure HTTP-status-code probes through the security chain.
- `auth/AuthControllerSliceTest.java` — `@WebFluxTest(controllers = AuthController.class)` — **the only slice-test pattern** in the codebase besides ProjectControllerSliceTest.
- `auth/AuthControllerIT.java`, `bot/BotControllerIT.java`, `project/ProjectControllerIT.java`, `webhook/TelegramWebhookControllerIT.java` — full-stack ITs.
- `project/ProjectControllerSliceTest.java` — `@WebFluxTest(controllers = ProjectController.class)`.
- `security/SecurityConfigTest.java` — security-chain slice with mocked DB.
- `webhook/WebhookSecurityBlockTest.java` — `@SpringBootTest` + `@MockitoBean MongoClient/RedisConnectionFactory/ReactiveRedisConnectionFactory`.

### 4.2 `StepVerifier` users (15 files)

Pure unit tests that subscribe directly to `Mono`/`Flux` returned by services:
- `auth/AuthServiceTest.java`, `auth/AuthServicePasswordResetTest.java`, `auth/AuthServiceRegistrationTest.java`
- `bot/BotServiceTest.java`, `bot/BotRepositoryTest.java`, `bot/BotIndexTest.java`, `bot/TelegramApiClientTest.java`, `bot/TelegramSenderTest.java`
- `profile/ProfileServiceTest.java`
- `project/ProjectServiceTest.java`
- `user/UserServiceTest.java`
- `webhook/RawUpdateRepositoryTest.java`, `webhook/WebhookPayloadSizeFilterTest.java`
- `subscriber/NoOpSubscriberServiceTest.java`, `funnel/NoOpFunnelTriggerServiceTest.java`

### 4.3 `@MockitoBean` on reactive infrastructure types

Used to short-circuit Boot's auto-config in security-only / wiring-only tests. Pattern locked by `patterns.md` "Spring Boot 3.5.x requires three mocks":

- `com.mongodb.reactivestreams.client.MongoClient` — mocked in HealthEndpointTest, HealthSecurityTest, SecurityBlockTest, WebhookSecurityBlockTest, SecurityConfigTest, MeterRegistryConfigTest, AuthControllerSliceTest (via `@WebFluxTest` defaults).
- `org.springframework.data.redis.connection.RedisConnectionFactory` — same set.
- `org.springframework.data.redis.connection.ReactiveRedisConnectionFactory` — same set.

### 4.4 `@DynamicPropertySource` + `MockWebServer`

- `AbstractIntegrationTest.java:88` — Mongo + Redis URIs from Testcontainers.
- `bot/BotControllerIT.java:93` — `app.telegram.base-url` redirected to a class-level static `MockWebServer`.
- `bot/TelegramApiClientTest.java:32` — per-test `MockWebServer`.
- `bot/TelegramSenderIT.java:60` — class-level static `MockWebServer`.
- `bot/TelegramSenderTest.java:59` — per-test `MockWebServer`.

### 4.5 Test slices specific to reactive

- `@WebFluxTest(controllers = ...)` — used only in `auth/AuthControllerSliceTest.java` and `project/ProjectControllerSliceTest.java`. Migrates to `@WebMvcTest`.
- `WebTestClient.bindToApplicationContext` pattern used by every integration test (because `csrf()` and `mockUser()` mutators require it). MVC equivalent: `MockMvcBuilders.webAppContextSetup(...)` or `@AutoConfigureMockMvc`.
- `reactor.core.scheduler.Schedulers.boundedElastic()` referenced from tests (`AuthServicePasswordResetTest.java:409`, `BotControllerIT.java:590`, `TelegramWebhookControllerIT.java:291,347`) — used only for parallelism in stress / parallel-POST scenarios. Migrates to `Executors.newVirtualThreadPerTaskExecutor()` or `parallelStream`.

### 4.6 Test base classes

- `AbstractIntegrationTest` — static Testcontainers (Mongo, Redis, Mailpit). Imports `JobRunrInMemoryConfig`. Mocks `JavaMailSender` via `@TestConfiguration` `@Primary`. WebTestClient rebound per `@BeforeEach`.
- `JobRunrInMemoryConfig` — supplies `InMemoryStorageProvider` so `@Recurring` registration doesn't need a real DB. Background-job-server disabled via `application-test.properties` `org.jobrunr.background-job-server.enabled=false`.

### 4.7 Representative test method signatures (one per category)

```java
// Reactive service unit test (StepVerifier)
@Test
void verifyEmail_validToken_returnsRedirectAndLogsEvent() {
    StepVerifier.create(authService.verifyEmail("token", exchange))
            .expectNextMatches(r -> r.redirectTo().equals("/login"))
            .verifyComplete();
}

// WebTestClient integration test
@Test
void receive_happyPath_returns200_persistsAndEnqueues() {
    webTestClient.post().uri("/webhooks/telegram/" + p.getId())
            .header(...).bodyValue(samplePayload(42L))
            .exchange()
            .expectStatus().isOk()
            .expectBody().isEmpty();
}
```

---

## 5. Application configuration

### 5.1 `application.properties` keys touching the web stack

```
spring.application.name=bot-funnel-backend
spring.data.mongodb.uri=${MONGODB_URI:mongodb://localhost:27017/botfunnel}
spring.data.mongodb.auto-index-creation=true       # Survives MVC migration unchanged.
spring.data.redis.url=${REDIS_URL:redis://localhost:6379}
spring.session.store-type=mongodb
spring.session.mongodb.collection-name=sessions
server.reactive.session.cookie.http-only=true      # → server.servlet.session.cookie.http-only
server.reactive.session.cookie.secure=...          # → server.servlet.session.cookie.secure
server.reactive.session.cookie.same-site=...       # → server.servlet.session.cookie.same-site
app.session.ttl-default-hours=24
app.session.ttl-remember-me-days=30
spring.mail.*
app.url=...
app.projects.max-per-user=5
org.jobrunr.background-job-server.enabled=true
org.jobrunr.dashboard.enabled=false
app.super-admin.email=...
app.super-admin.password=...
logging.level.org.springframework.security=INFO
app.bot.token-encryption-key=...
app.telegram.base-url=https://api.telegram.org
```

**No keys are set for `spring.codec.max-in-memory-size` or `server.netty.*`** — defaults are used. The 1 MB payload cap is enforced explicitly by `WebhookPayloadSizeFilter` instead of via codec settings.

### 5.2 `@Configuration` classes

- `common/metrics/MeterRegistryConfig.java` — Explicit `SimpleMeterRegistry` bean (no actuator on classpath). Survives migration unchanged.
- `jobs/JobRunrMongoConfig.java` — Sync `MongoClient` (separate from the reactive driver) for JobRunr's `MongoDBStorageProvider`. **Already sync** — under MVC + sync Mongo, this potentially collapses into the main `MongoClient` bean (depends on whether JobRunr can share Spring's). Currently two clients coexist.
- `security/SecurityConfig.java` — see §2.
- No file configures Reactor schedulers, Netty server, or WebFlux-specific beans (`WebFluxConfigurer`, `WebHttpHandlerBuilder`, etc.).

`build.gradle` dependencies relevant to the web stack:

```
implementation 'org.springframework.boot:spring-boot-starter-data-mongodb-reactive'
implementation 'org.mongodb:mongodb-driver-sync'              # already present, for JobRunr
implementation 'org.springframework.boot:spring-boot-starter-data-redis-reactive'
implementation 'org.springframework.boot:spring-boot-starter-security'
implementation 'org.springframework.boot:spring-boot-starter-webflux'   # ← FLIPS
implementation 'org.springframework.session:spring-session-data-mongodb'
implementation 'org.springframework.boot:spring-boot-starter-mail'
implementation 'org.springframework.boot:spring-boot-starter-validation'
implementation 'io.micrometer:micrometer-core'
implementation 'org.jobrunr:jobrunr-spring-boot-3-starter:7.3.2'
testImplementation 'io.projectreactor:reactor-test'           # ← removed after migration completes
```

---

## 6. Dependency map for migration order

### 6.1 Cross-module import graph (production code)

Derived from `grep -rn "import com.botfunnel\\..*\\." backend/src/main/java/`. Edges read "A → B" mean "module A imports from module B".

```
admin    → user, auth (via PasswordEncoder/SuperAdmin doesn't import auth directly; pure user dep)
auth     → common (AppException, crypto), email, events, security (RememberMeWebSessionIdResolver), user
bot      → auth (AppUserDetails — for controller currentUserId), common (AppException, crypto.{EncryptedValue,Sha256Hex,TokenEncryptor}), events, project (ProjectService.requireOwned)
common   → (none — leaf)
email    → (none — leaf, just SMTP)
events   → (none — leaf; only repository on Event)
funnel   → (none — leaf interface + no-op impl)
jobs     → events, project, user (HardDeleteJob → user; ProjectHardDeleteJob → events, project)
profile  → auth (AppUserDetails), common, events, user
project  → auth (AppUserDetails — for controller), common, events
security → (none — leaf; SecurityConfig imports only Spring/Boot types)
subscriber → (none — leaf interface + no-op impl)
user     → common
webhook  → bot, common, events, funnel, project, subscriber
```

### 6.2 Topological order

A valid topological order (leaves first, dependents last):

```
common → email → events → security → user → subscriber → funnel
       → auth → project → profile → jobs → bot → webhook
```

The team's proposed order (`common → email → events → user → auth → project → bot → webhook → profile → subscriber/funnel stubs → security/main/properties`) is **compatible** with this graph except for two points:

- **`security` is a leaf** in the import graph (no project module imports from it except `auth`, and that's only the `RememberMeWebSessionIdResolver` class). It can move earlier than the team's "final flip" suggests; OR — more importantly — it **cannot** be migrated independently of the web stack because `@EnableWebFluxSecurity` and `ServerHttpSecurity` are reactive-only types. Therefore **security/SecurityConfig + the build.gradle dependency flip + application.properties cookie-prefix swap are one atomic commit**.

- **`auth → security`** edge: `AuthService` references `RememberMeWebSessionIdResolver.REMEMBER_ME_ATTR` (a public constant). Migrating `auth` before `security` means temporarily keeping the constant; migrating `security` before `auth` means refactoring `AuthService.openSession` simultaneously. Easiest path: move the `REMEMBER_ME_ATTR` constant to `common/` (or inline the literal) before either migration step.

### 6.3 The atomic flip boundary

The non-divisible commit must contain **all of**:

1. `build.gradle`:
   - `spring-boot-starter-webflux` → `spring-boot-starter-web`
   - `spring-boot-starter-data-mongodb-reactive` → `spring-boot-starter-data-mongodb`
   - `spring-boot-starter-data-redis-reactive` → `spring-boot-starter-data-redis`
   - Remove `io.projectreactor:reactor-test`
2. `application.properties`:
   - `server.reactive.session.cookie.*` → `server.servlet.session.cookie.*`
3. `security/SecurityConfig.java` — rewrite with `@EnableWebSecurity`, `HttpSecurity`, `SecurityFilterChain`, matchers.
4. `security/RememberMeWebSessionIdResolver.java` — either delete (and re-implement remember-me via Spring Session's `CookieSerializer` / a servlet `Filter`) or rewrite from scratch.
5. `webhook/WebhookPayloadSizeFilter.java` — rewrite as servlet `OncePerRequestFilter`.
6. `HealthController.java` — drop the `Mono<>` wrapper.
7. Every controller signature in `auth/`, `bot/`, `project/`, `profile/`, `webhook/` that returns `Mono<ResponseEntity<...>>` — flip to bare `ResponseEntity<...>` (or the service still returns Mono and the controller's `.block()` materialises it — but practically this flips along with the service).
8. Every `*Repository` interface — `ReactiveMongoRepository<T,String>` → `MongoRepository<T,String>` and `Mono<T>` / `Flux<T>` finder return types → `Optional<T>` / `List<T>`.
9. Every `*Service` that returns `Mono`/`Flux` — flip API to plain types.
10. `common/GlobalErrorHandler.java` — `WebExchangeBindException` → `MethodArgumentNotValidException`.

Module-by-module migration **is not possible** for steps 1–4 because: (a) `build.gradle` changes the auto-config that controls every other class at runtime; (b) `WebFlux` and `Web` starters are mutually exclusive (Spring Boot fails fast if both are present); (c) `WebSession` / `ServerWebExchange` types disappear from the classpath the moment WebFlux is removed.

Strangler-fig is achievable for steps 5–10 by keeping `Mono`/`Flux` API at service boundaries until the final flip and consuming them via `.block()` at the controller — i.e., write the new controller signatures first, switch the build dependency last. But the SecurityConfig migration is genuinely atomic with the build flip.

### 6.4 Mutually-dependent modules

None. The import graph above is a DAG (verified by topological sort succeeding without cycles).

---

## 7. Risks specific to virtual threads

### 7.1 `synchronized` blocks on long-running operations

**None found** in `backend/src/main/java/com/botfunnel/`. The grep returned zero hits for `synchronized`. Third-party libraries (Mongo driver, Lettuce, JobRunr) may have internal `synchronized` blocks — JDK 21 / 22 keep VT pinning on those; JDK 24 fixes it (`-Djdk.tracePinnedThreads=full` is the standard probe). Worth flagging for the post-migration profiling stage.

### 7.2 `ThreadLocal` usage

**Direct user code**: only one site uses a thread-local-ish primitive — `bot/TelegramSender.java:246` calls `ThreadLocalRandom.current().nextLong(...)` for jitter. `ThreadLocalRandom` is VT-safe (it's the documented replacement for `Random` under high concurrency) and not a migration concern.

Reactor, Spring Security's `SecurityContextHolder` (servlet-stack), and MDC all use `ThreadLocal` heavily — under VT, each request gets a fresh VT with a fresh ThreadLocal map, so memory pressure grows with concurrent in-flight requests but per-thread cost stays low. No known leak source in current code.

### 7.3 `Schedulers.boundedElastic` reliance

8 production sites (§1.5). Each one wraps a synchronous blocking call. Under VT:

- Removing `.subscribeOn(Schedulers.boundedElastic())` while keeping `Mono.fromCallable` works (the callable just runs on the calling VT).
- Removing both is the migration: call the synchronous operation directly.
- **Risk**: `boundedElastic` has a max thread cap (~10× CPU cores by default) and a per-thread idle timeout; once you remove it, work that previously got back-pressured under load now spawns one VT per request. For BCrypt cost-12 (~250ms CPU), this may starve the CPU under a register-storm. Mitigation post-migration: a `Semaphore` (works correctly under VT) or a CPU-bounded `Executors.newWorkStealingPool()` for the BCrypt call. Not a regression — current `boundedElastic` already exhibits the same starvation past its cap — but the previously-implicit back-pressure surfaces explicitly.

### 7.4 Native code / JNI blocking paths

None expected. Mongo driver uses pure-Java BSON + Java NIO. Lettuce uses Netty. JavaMail uses Java sockets. JobRunr is pure Java. **Verify**: nothing in the codebase loads native libraries (`System.loadLibrary` / `JNI`) — grep returns zero hits.

### 7.5 Lettuce sync vs async vs reactive

`spring-boot-starter-data-redis-reactive` pulls in Lettuce in reactive-API mode (`LettuceReactiveRedisConnection`). Under MVC, the migration target is `spring-boot-starter-data-redis` which uses the same Lettuce driver in sync API mode (`LettuceConnection`). Lettuce internally is always non-blocking (Netty-based); the sync API just blocks the calling thread on the Netty reply Future. Under VT, the calling thread is a virtual thread, and blocking a VT on a Netty-completed CompletableFuture is the documented VT happy-path. **No risk.** Connection pooling settings carry over.

### 7.6 JobRunr

`@Recurring` jobs and explicit `JobScheduler.enqueue(UUID, lambda)` from `TelegramWebhookController` (current pattern, locked in by `patterns.md` line 140 — the controller uses injected `JobScheduler`, not static `BackgroundJob.enqueue`, specifically for test-context fidelity). JobRunr 7.3.2 uses its own thread pool for workers (`BackgroundJobServer`), independent of the web request thread pool — **VT does not change JobRunr's threading model**. The worker pool stays platform-thread-backed by default (configurable via `JobRunrConfiguration`). Migration of `ProcessTelegramUpdateJob` is mechanical: the `.block()` calls inside `handle(...)` simply become the natural sync calls once the repositories return non-reactive types.

### 7.7 Reactor-specific test-only dependencies

`reactor-test` (`StepVerifier`) is referenced in 15 test files. Once services return plain types, these collapse to standard JUnit assertions. **Risk to test surface**: ~15 test files require non-trivial rewrite — not blocked but accounts for a large slice of the migration effort.

### 7.8 Reactor-specific helpers in tests

`AbstractIntegrationTest.rebindWebTestClient()` exists only because `WebTestClient.bindToServer()` can't accept `SecurityMockServerConfigurers.csrf()` mutators. Under MVC, `MockMvc` with `SecurityMockMvcRequestPostProcessors.csrf()` is the equivalent — pattern carries over but every IT class needs its setUp changed.

---

## 8. Patterns.md cross-reference

`backend`'s authoritative source for established patterns lives at `.claude/skills/project-knowledge/references/patterns.md`. Below is each pattern that names a reactive type or Reactor idiom, with migration verdict.

Legend: **survives** (no change), **rewritten same shape** (semantic shape preserved but Java types change), **redesigned** (the pattern itself must change).

| # | Pattern (section) | Names reactive/Reactor types | Verdict |
|---|---|---|---|
| 1 | "Integration tests for API endpoints" — `@SpringBootTest + WebTestClient` (NOT MockMvc) | yes | **rewritten same shape** — pattern becomes `MockMvc` (which patterns.md explicitly forbids today). Pattern statement itself must be updated. |
| 2 | "Spring Boot 3.5.x requires three mocks: `com.mongodb.reactivestreams.client.MongoClient`, `RedisConnectionFactory`, `ReactiveRedisConnectionFactory`" | yes (two of three) | **rewritten same shape** — under MVC, mocks become `com.mongodb.client.MongoClient` (sync) + `RedisConnectionFactory`. `ReactiveRedisConnectionFactory` disappears. |
| 3 | "Spring Security WebFlux" — entire subsection (BOTH `@Configuration` AND `@EnableWebFluxSecurity`; `SecurityWebFilterChain` uses `ServerHttpSecurity`; CSRF: `CookieServerCsrfTokenRepository.withHttpOnlyFalse()` + `XorServerCsrfTokenRequestAttributeHandler`; `pathMatchers(...).permitAll()` BEFORE `anyExchange().authenticated()`) | yes | **rewritten same shape** — every class name flips to its servlet equivalent (§2). Ordering invariant ("specific permitAll before catch-all authenticated") survives. CSRF cookie shape survives. |
| 4 | "Fire-and-forget side effects" — `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic()).subscribe(null, log::error)` | yes | **redesigned** — under VT becomes a simple `executorService.submit(...)` or even direct call. The `Mono.fromCallable + subscribeOn + subscribe` ceremony goes away entirely. |
| 5 | "Brute-force throttle keys (Redis) — `INCR + EXPIRE 900 on first set, DEL on success`. Fail-open if Redis is unreachable." | partially (Redis API surface) | **rewritten same shape** — same Lettuce primitives, sync API. Fail-open semantics survive. |
| 6 | "Timing-attack prevention on user lookup" — BCrypt on `boundedElastic` | yes | **redesigned** — under VT just call `passwordEncoder.matches(...)` directly. Behavioural guarantee (constant-time matching of missing-user vs wrong-password) survives. |
| 7 | "Per-request session cookie Max-Age (remember-me)" — `RememberMeWebSessionIdResolver extends CookieWebSessionIdResolver` + `REMEMBER_ME_ATTR` exchange attribute | yes | **redesigned** — there is no `CookieWebSessionIdResolver` analog in MVC. Two viable redesigns: (a) custom `Filter` that intercepts the response and rewrites the JSESSIONID cookie's Max-Age based on a request attribute; (b) Spring Session's `DefaultCookieSerializer` with a custom subclass that reads `RequestAttributes`. The exchange-attribute → request-attribute swap is mechanical; the cookie-writer architecture changes. |
| 8 | "Reactive `WebClient` for outbound HTTP" — `WebClient` per upstream, `HttpClient.create().responseTimeout(...)`, `Retry.backoff(...)`, `.onStatus(...)` | yes | **redesigned** — under VT the documented choice is the JDK's `java.net.http.HttpClient` (synchronous send blocks the VT, which is cheap). Spring 6.x ships `RestClient` as the synchronous successor to `WebClient`; both Telegram clients (`TelegramApiClient`, `TelegramSender`) need rewrite. `Retry.backoff` (Reactor) replaced by Spring Retry / Resilience4j / hand-rolled loop. `.timeout(...)` (Reactor) replaced by `HttpClient.connectTimeout(...)` + per-request timeout. `Retry.backoff(3, 200ms).maxBackoff(2s)` semantics survive; implementation changes. The cause-chain walking `isTransient(...)` static helper survives unchanged. |
| 9 | "Send retry ladder + 429 retry-after loop wrapping 5xx retry loop" — Reactor `.retryWhen` stacking | yes | **redesigned** — same logical structure (outer 429 around inner 5xx) but rewritten as two nested while loops or two stacked Spring Retry interceptors. Test-time assertion ("attempts==4 on interleaved `[5xx, 429, 5xx, 200]`") survives. |
| 10 | "Clamp `retry_after` before sleeping" | no reactive dependency | **survives** — `Math.max(0, Math.min(retryAfter, 30)) + jitter(0..200ms)` is pure arithmetic. Under VT the sleep is `Thread.sleep(...)` (which yields the VT). |
| 11 | "Sender owns the audit trail" — `EventService.logEvent(...)` fire-and-forget | yes (mono.subscribe inside EventService) | **rewritten same shape** — EventService becomes `executorService.submit(() -> eventRepository.save(event))`. Caller surface (`logEvent(...)` is `void`) is already non-reactive — survives unchanged. `logEventBlocking(...)` is renamed to just `logEvent` and dropped as a separate method (the blocking variant becomes the default). |
| 12 | "Per-call decrypt; `requireValidTokenShape` runs BEFORE URI construction" | no reactive dependency | **survives** — pure data-flow ordering, no Reactor primitives. |
| 13 | "Decrypt-error mapping: catch only `Base64.getDecoder().decode` `IllegalArgumentException`" | no | **survives**. |
| 14 | "Internal sentinel exception for retry signaling — `TelegramRateLimitException`" | yes (used by Reactor `.retryWhen` filter) | **rewritten same shape** — still a sentinel exception, still package-private, still used by retry filter. Filter implementation changes (see #9). |
| 15 | "Scrubber sites for send-path logging" | no | **survives**. |
| 16 | "Webhook Ingestion — Two-resource anti-enumeration 404" | yes (`flatMap` / `switchIfEmpty`) | **rewritten same shape** — sequential `findById` calls + null-checks; same ordering, no `Mono`. |
| 17 | "Path-scoped `WebFilter` for per-endpoint payload caps" — `WebFilter` at `HIGHEST_PRECEDENCE + 10` | yes | **rewritten same shape** — becomes `OncePerRequestFilter` (or `Filter`) with `@Order(HIGHEST_PRECEDENCE + 10)`. Spring's `PathPattern` is web-stack-neutral and survives unchanged. `getValuesAsList`-over-`getFirst` rule for `Transfer-Encoding` survives (it's an `HttpServletRequest` header-access detail). |
| 18 | "Scoped CSRF disable: AND with `CsrfWebFilter.DEFAULT_CSRF_MATCHER`" | yes | **rewritten same shape** — MVC has `CsrfFilter`; its DEFAULT_CSRF_MATCHER isn't exposed but the equivalent verb matcher must be reconstructed. AND-not-NEG invariant survives. |
| 19 | "Idempotency = unique compound index + `DuplicateKeyException` + deterministic JobRunr UUID self-heal" | no (Mongo + JobRunr; both already non-reactive for the relevant calls) | **survives** — `DuplicateKeyException` is the same Spring Data exception in both stacks. JobRunr's `JobScheduler.enqueue(UUID, lambda)` idempotency is unchanged. |
| 20 | "JobRunr worker re-entry guard via terminal-status short-circuit" | yes (`.block()` chain in `handle(...)`) | **rewritten same shape** — `.block()` calls become direct sync calls. The logical ordering ("read status, return early on DONE") is preserved. |
| 21 | "Event-write before status-flip in worker (audit-survives-crash invariant)" | yes (`logEventBlocking(...).block()`) | **rewritten same shape** — `eventService.logEvent(...)` becomes sync; ordering preserved without `.block()`. |
| 22 | "Atomic `findAndModify` for first-writer-wins state transitions" | yes (`ReactiveMongoTemplate.findAndModify`) | **rewritten same shape** — `MongoTemplate.findAndModify` is the sync equivalent; same `Query` + `Update` + class signature. Returns `T` (nullable) instead of `Mono<T>` (empty = null). |
| 23 | "Worker exception rethrow with scrubbed + truncated error" | no | **survives**. |
| 24 | "Static `BackgroundJob.enqueue` vs injected `JobScheduler`" | no | **survives** — already a non-reactive concern; the rationale (test context fidelity) carries over. |
| 25 | "Token-scrubber on every webhook + worker log site" | no | **survives**. |
| 26 | "Explicit `@Bean SimpleMeterRegistry` when actuator NOT on classpath" | no | **survives**. |
| 27 | "Counter ownership = where the rejection happens, not where the contract describes it" | no (filter still owns the counter) | **survives** — `WebhookPayloadSizeFilter` migrates to servlet `Filter` but the counter ownership rule stays. |
| 28 | "Explicit-status no-body responses bypass `GlobalErrorHandler`" — `Mono<ResponseEntity<Void>>` + outer `onErrorResume(ex -> 500 empty body)` | yes | **rewritten same shape** — under MVC the return type is `ResponseEntity<Void>`; the outer `onErrorResume` becomes a try/catch wrapping the controller body. Behaviour (explicit status, no GlobalErrorHandler JSON body) survives. |
| 29 | "Session fixation: invalidate the pre-auth `WebSession` and let the framework create a fresh one" | yes (WebSession) | **rewritten same shape** — `HttpSession.invalidate()` then `request.getSession(true)`. Note current `AuthService.openSession` already documents that `exchange.getSession()` is cached; MVC's `HttpServletRequest.getSession()` has different (servlet-spec) caching semantics. |
| 30 | "Terminate sessions for a user — query by `principal` via `ReactiveMongoTemplate`" | yes | **rewritten same shape** — `MongoTemplate.remove(Query.query(...), "sessions")`. The `principal` field path **is preserved** in `spring-session-data-mongodb`'s servlet variant. |

The big-picture verdict: ~10 patterns require redesign (every WebClient pattern, RememberMe, fire-and-forget); ~20 are rewritten same-shape (mechanical class-name swap); the remainder survive untouched (rate-limit semantics, error mapping, audit ordering, scrubber, JobRunr cascade).

---

## File-count summary

- Production files importing `Mono`/`Flux`: **27** (every module except `admin/`, `funnel/` (stub-only), `subscriber/` (stub-only) — though stubs do return `Mono.empty()`).
- Production files importing `WebFlux`-specific HTTP types (`ServerWebExchange`, `WebFilter`, `ServerHttpSecurity`, etc.): **9**.
- Test files using `WebTestClient`: **12**.
- Test files using `StepVerifier`: **15**.
- Test files with `@MockitoBean` on reactive infra types: **8**.
- `.block()` call sites in production code: **22** (16 in `ProcessTelegramUpdateJob` alone, 3 in `SuperAdminSeeder`, 1 in `HardDeleteJob`, 3 in `ProjectHardDeleteJob`).

The block-heavy zones (`ProcessTelegramUpdateJob`, `SuperAdminSeeder`, `HardDeleteJob`, `ProjectHardDeleteJob`) are the easiest wins — they already operate against blocking JobRunr / ApplicationRunner threads and only use Reactor as a glue layer.

---

## Updated: 2026-05-23

## Implementation Plan

Implementation-level deepening of §§1–8 above. Reading order: §6 (sanity check on missing modules) → §1 (per-wave file plans) → §2 (atomic flip file list) → §3 (signature table) → §4 (test recipes) → §5 (tricky migrations).

### 6. Sanity check — modules NOT in §6.1

Re-grep of `backend/src/main/java/com/botfunnel/`:

- **`admin/SuperAdminSeeder.java`** — `ApplicationRunner.run(...)`; imports only `com.botfunnel.user.{User,UserRepository,UserStatus}` + Spring's `PasswordEncoder` + `@Value`. **Three `.block()` sites** at lines 56 (`findByEmail`), 67 (`save` new), 77 (`save` promotion). No `Mono`/`Flux` types in the file itself — purely a `.block()` consumer of `UserRepository`. **Dependency profile: leaf consumer of `user/`** — no module imports from `admin/`. Migrates in the same wave as `user/` (or trivially after). The class structure stays identical: `userRepository.findByEmail(email).block()` → `userRepository.findByEmail(email).orElse(null)` (Optional shape) once `UserRepository` flips. Was effectively covered by §6.2 because `admin` shows up as `admin → user` in §6.1 — but the §6.2 topological line elides it. **Flag for tech-spec: add `admin` to the explicit wave order.**

- **`jobs/HardDeleteJob.java`** — `@Recurring` cron; **two `.block()` sites** at lines 47 (`collectList().blockOptional()`) and 54 (`deleteAll(users).block()`). Already structured exactly like ProcessTelegramUpdateJob — synchronous `void` method, reactive types are pure glue. No `Mono`/`Flux` in method signatures. Migrates with `jobs/`.

- **`jobs/ProjectHardDeleteJob.java`** — `@Recurring` cron; **four `.block()` sites** at lines 57, 85, 95, 101. Uses `ReactiveMongoTemplate` injected directly (not just `*Repository`). The `template` field type itself must flip to `MongoTemplate`. Cascade ordering (events sweep → emit project_hard_deleted → drop projects) is preserved by sequential sync calls — no `.block()` ceremony required post-migration.

- **`subscriber/SubscriberService.java`** (interface) — declares `Mono<Void> upsertFromTelegramUpdate(...)` and `Mono<Void> markUnsubscribed(...)`. **`NoOpSubscriberService` returns `Mono.empty()` for both.** Interface signature must flip to `void` returns. ProcessTelegramUpdateJob `.block()`-consumes this twice (lines 195, 253). The flip is mechanical but **breaks the `Mono<Void>` API contract** — Epic 05 (future real impl) must be written sync. Flag for tech-spec.

- **`funnel/FunnelTriggerService.java`** (interface) — same shape: `Mono<Void> fire(...)` and `Mono<Void> cancelActiveFor(...)`, `NoOpFunnelTriggerService` returns `Mono.empty()`. ProcessTelegramUpdateJob `.block()`-consumes twice (lines 242, 254). Identical migration mechanics.

**Existing §6.1 graph is exhaustive** for production code — `admin`, `jobs`, `subscriber`, `funnel` all appear as edges, but `admin/SuperAdminSeeder` and `subscriber/funnel` stubs are easy-to-miss because they are leaf consumers that don't surface in the topological linearisation §6.2 displayed. The §6.2 topological list should be re-expressed as:

```
Leaves (no project deps):
  common, email, events, security, user (→common), funnel-stub, subscriber-stub
Dependents:
  auth (→common, email, events, user, security-constant)
  project (→common, events, auth-AppUserDetails)
  admin (→user)
  bot (→common, events, project, auth-AppUserDetails)
  profile (→common, events, user, auth-AppUserDetails)
  jobs (→user, project, events)
  webhook (→common, project, bot, events, subscriber, funnel)
```

### 1. Per-file migration plan grouped by wave

Wave order matches strangler-fig: leaves first, every service flips its API to plain types (consumed via `.block()` upstream) until the final atomic flip. Test files migrate **in the same module wave as the production file unless noted**.

#### Wave 0 — Pre-migration constant move (decision D7)

- **`/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/security/RememberMeWebSessionIdResolver.java`** — move `public static final String REMEMBER_ME_ATTR = "com.botfunnel.auth.rememberMe"` to a new location.
- **`/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/auth/AuthService.java:605`** — update import from `com.botfunnel.security.RememberMeWebSessionIdResolver.REMEMBER_ME_ATTR` to new home. **Design choice**: place constant in `common/SessionAttributes.java` (new file, no behaviour change). Mechanical after the create.
- Tests: AuthServiceTest references the constant by FQN; same import update.

#### Wave 1 — `common` + `events` (leaf, no signature changes, no test changes)

- **`common/GlobalErrorHandler.java`** — **defer to flip wave** (depends on `WebExchangeBindException` which only exists with WebFlux on classpath). No change in Wave 1.
- **`events/EventService.java`** — flip `logEventBlocking(...)` return type from `Mono<Event>` to `Event`; flip `logEvent(...)` to call `eventRepository.save(event)` directly inside a `try { ... } catch (Exception err) { log.error(...); }` (was `.subscribe(null, log::error)`). Mechanical.
- **`events/EventRepository.java`** — `extends ReactiveMongoRepository<Event, String>` → `extends MongoRepository<Event, String>`. No custom finders; only inherited `save(...)` changes from `Mono<Event>` to `Event`. Mechanical. **Cannot migrate until `spring-boot-starter-data-mongodb-reactive` is removed** — keep reactive in Wave 1, flip in atomic commit.

**Verdict**: events module **cannot strangler-fig** before flip. The repository type is locked by the auto-config of the starter on classpath. Wave 1 is empty for production code (constant move only). All reactive→sync repository flips happen in the atomic commit.

#### Wave 2 — `email` (zero touch)

- **`email/EmailService.java`** — already returns `void`. Internal `Mono.fromRunnable(...).subscribeOn(boundedElastic()).subscribe(...)` becomes `executorService.submit(...)` or direct sync call. **Design choice**: keep fire-and-forget semantics with `Executors.newVirtualThreadPerTaskExecutor()` injected as a bean — or just call synchronously and trust caller-side VT. **Recommended**: direct sync call inside the existing `void send(...)` — under VT the BCrypt + SMTP chain runs on the calling VT, the request-thread is cheap. Single mechanical change. **Tests**: no public API change; existing tests using `EmailService` directly survive.

#### Wave 3 — `security` constant move (already done in Wave 0). No other security-only file is pre-flip migrable.

`SecurityConfig` and `RememberMeWebSessionIdResolver` flip atomically — see §2.

#### Wave 4 — `user` + `admin`

- **`user/UserRepository.java`** — same as `EventRepository`: cannot flip pre-atomic. Locked.
- **`user/UserService.java`** — three methods (`findByEmail`, `save`, `softDelete`) all `Mono<User>` → `Optional<User>` / `User`. **Pre-flip strategy**: leave reactive, consume via `.block()` from the only two callers (AuthService chain, SuperAdminSeeder which already blocks). Cannot flip until repository flips.
- **`admin/SuperAdminSeeder.java`** — three `.block()` calls become direct `.get()` / direct invocation. Flip in same atomic commit as `UserRepository`.

#### Wave 5 — `subscriber` + `funnel` (interface flip)

- **`subscriber/SubscriberService.java`** + **`subscriber/NoOpSubscriberService.java`** — flip return types `Mono<Void>` → `void`. **Strangler problem**: the interface is consumed by `ProcessTelegramUpdateJob.handle(...)` with `.block()` — if the interface returns `void`, the `.block()` site no longer compiles. **Resolution**: must flip interface + caller in the same commit (could be a pre-flip module commit, since the interface doesn't reference any WebFlux type — only `reactor.core.publisher.Mono`). **Design choice**: do this in a pre-flip wave alongside `events`. The `Mono<Void>` API change is mechanical at both ends.
- **`funnel/FunnelTriggerService.java`** + **`funnel/NoOpFunnelTriggerService.java`** — identical to subscriber.
- **Tests**: `NoOpSubscriberServiceTest`, `NoOpFunnelTriggerServiceTest` — StepVerifier → direct `assertThat(noOp.upsertFromTelegramUpdate(...))` returns void, just call. Three-line rewrites each.

This is a **viable pre-flip wave** because none of the touched files import WebFlux HTTP types — only `reactor.core.publisher.Mono`, which stays available until the build dependency flip.

#### Wave 6 — `auth` (service-layer rewrite — biggest pre-flip wave by LOC)

- **`auth/AuthService.java`** — 650 LOC, eight public methods, all flip from `Mono<X>` to `X`. Service-layer rewrite **stays compileable pre-flip** because `ServerWebExchange` and `WebSession` are still on classpath until the atomic flip. The internal `Mono.fromCallable(...).subscribeOn(boundedElastic())` → direct sync call for all 11 sites at lines 366/420/470/506/522/525/532/537/571/577/585. The `securityContextRepository.save(exchange, context).then(...)` chain becomes `securityContextRepository.save(exchange, context); ...` (still reactive return — service-layer stays Mono internally if exchange is still reactive — see Resolution below).
- **Resolution**: AuthService **cannot pre-flip its public API** while controllers still take `ServerWebExchange`. Either:
  - (a) Flip both controller and service in the atomic commit (one extra file). Recommended.
  - (b) Pre-flip the service to have a `ServerWebExchange`-free shape (extract IP/UA/session-id in the controller, pass primitives down). More work, more files, no payoff because the controller still touches `ServerWebExchange` until flip. Reject.
- **Tests** (`AuthServiceTest`, `AuthServiceRegistrationTest`, `AuthServicePasswordResetTest`) — StepVerifier rewrites. ~50 test methods total. **Design choice**: these can pre-flip if AuthService API flips to sync; or stay reactive if AuthService API stays reactive. **Sync with the service**: if (a) above, all three test files migrate in flip wave. If we choose to make them pre-flip migrable, we'd need an intermediate `Mono.fromCallable(() -> authService.syncMethod(...))` wrapper — boilerplate without payoff. Recommend: flip wave.

#### Wave 7 — `project`

- **`project/ProjectRepository.java`** — six custom finders, all locked to atomic flip.
- **`project/ProjectService.java`** — six public methods, all `Mono<Project>` / `Flux<Project>` → `Project` / `List<Project>`. Same constraint as AuthService: cannot pre-flip while `requireOwned` is called from `BotService` which still consumes Mono. **Or**: ProjectService API flips, BotService consumers update at same time. Tests (ProjectServiceTest) migrate together.

Strangler-fig is achievable for `project` IF and only IF `bot/BotService` migrates in the same commit (one big PR or sub-PR within the same wave). Recommend: one PR per "service + its direct consumers + its tests" — see §6 below.

#### Wave 8 — `bot`

- **`bot/BotRepository.java`** — three custom finders, locked to atomic flip.
- **`bot/BotService.java`** — three public methods, flip from Mono to sync.
- **`bot/TelegramApiClient.java`** — full WebClient → RestClient rewrite (D1). See §5(e).
- **`bot/TelegramSender.java`** — full WebClient → RestClient rewrite + retry-loop rewrite. See §5(e).
- **Tests** (`BotServiceTest`, `BotRepositoryTest`, `BotIndexTest`, `TelegramApiClientTest`, `TelegramSenderTest`) — StepVerifier → assertThat; `MockWebServer` constructs survive. 5 test files.
- This is the **hardest pre-flip wave**: WebClient rewrites are non-mechanical, retry logic must be hand-rolled, and tests assert exact retry-attempt counts on interleaved 5xx/429 ladders.

#### Wave 9 — `profile`

- **`profile/ProfileService.java`** — six methods, flip from Mono to sync; `WebSession session` parameter cannot drop until controller flips, so profileService stays Mono-API until atomic flip OR signature carries `WebSession` (which only exists with WebFlux on classpath). **Locked to atomic flip.**
- **Tests** (`ProfileServiceTest`) — StepVerifier rewrites.

#### Wave 10 — `jobs`

- **`jobs/HardDeleteJob.java`** — already structurally sync. `.collectList().blockOptional().orElseGet(List::of)` → `userRepository.findByStatusAndDeletedAtBefore(...)` (now `List<User>`); `userRepository.deleteAll(users).block()` → `userRepository.deleteAll(users)`. Two `.block()` removals. Locked to atomic flip (depends on UserRepository).
- **`jobs/ProjectHardDeleteJob.java`** — three `template.remove(...).block()` calls + one `logEventBlocking(...).block()` loop. **`ReactiveMongoTemplate template` field type → `MongoTemplate template`**. Locked to atomic flip.
- **`jobs/JobRunrMongoConfig.java`** — already uses sync `MongoClient`. **Zero change.**

#### Wave 11 — `webhook` (last)

- **`webhook/RawUpdateRepository.java`** — one custom finder, locked.
- **`webhook/ProcessTelegramUpdateJob.java`** — sixteen `.block()` sites disappear. `ReactiveMongoTemplate` → `MongoTemplate`. See §5(d). Mechanical given a flipped service/repo chain.
- **`webhook/TelegramWebhookController.java`** — `Mono<ResponseEntity<Void>> receive(...)` → `ResponseEntity<Void> receive(...)`. The `Mono.fromRunnable(...).subscribeOn(boundedElastic())` for `JobScheduler.enqueue` becomes a direct call. `flatMap` chain + `switchIfEmpty(Mono.defer(...))` + `onErrorResume(DuplicateKeyException.class, ...)` + outer `.onErrorResume(ex -> 500)` becomes nested if/else with two try/catch blocks. See §5 ordering preservation hint.
- **`webhook/WebhookPayloadSizeFilter.java`** — `implements WebFilter` → `extends OncePerRequestFilter`. See §5(c).
- **`webhook/WebhookSecretVerifier.java`** — **zero change** (stateless, no reactive types).
- **Tests** (`RawUpdateRepositoryTest`, `WebhookPayloadSizeFilterTest`, `TelegramWebhookControllerIT`, `WebhookSecurityBlockTest`) — all flip in atomic commit.

#### Wave 12 — FLIP (atomic commit, see §2)

#### Wave 13 — Cleanup (post-flip)

- Documentation update (patterns.md, architecture.md, deployment.md per AC14).
- Audit pass for stray reactive imports — single `grep -rE` per AC3.
- Drop `reactor-test` from `build.gradle`.

### 2. Atomic flip commit — exhaustive file list

Total file count for the flip commit: **47 files** (production + tests + config). Counts grouped by type below.

#### 2.1 Build / config (3 files, dependency-swap + property-rename)

| File | Type | Complexity | Cross-file deps |
|------|------|-----------|-----------------|
| `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/build.gradle` | dependency-swap | trivial | Triggers ALL other files' classpath change |
| `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/resources/application.properties` | property-rename | trivial | None (key rename is independent) |
| `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/resources/application-test.properties` | possible property-rename | trivial | Check if `server.reactive.session.cookie.*` is also present here |

#### 2.2 Security stack (3 files, rewrite)

| File | Type | Complexity | Cross-file deps |
|------|------|-----------|-----------------|
| `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/security/SecurityConfig.java` | rewrite | requires-design | depends on `RememberMeCookieSerializer` existing |
| `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/security/RememberMeWebSessionIdResolver.java` | DELETE + REPLACE → `RememberMeCookieSerializer.java` | requires-design | depends on `common/SessionAttributes.REMEMBER_ME_ATTR` |
| `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/common/SessionAttributes.java` | NEW FILE (constant home, created in Wave 0) | trivial | Already exists by atomic-flip time |

#### 2.3 Controllers (6 files, signature-flip)

| File | Type | Complexity | Cross-file deps |
|------|------|-----------|-----------------|
| `auth/AuthController.java` | signature-flip | mechanical | AuthService API |
| `bot/BotController.java` | signature-flip | mechanical | BotService API |
| `project/ProjectController.java` | signature-flip | mechanical | ProjectService API; `list(...)` return type changes |
| `profile/ProfileController.java` | signature-flip | requires-design | ProfileService API; `WebSession`→`HttpSession` |
| `webhook/TelegramWebhookController.java` | rewrite | requires-design | RawUpdateRepository + BotRepository + ProjectRepository signatures; `Mono.defer/switchIfEmpty/onErrorResume` chain → imperative |
| `HealthController.java` | signature-flip | trivial | None |

#### 2.4 Services (7 files, signature-flip)

| File | Type | Complexity | Cross-file deps |
|------|------|-----------|-----------------|
| `auth/AuthService.java` | rewrite | requires-design | 650 LOC; 8 public methods + 11 `Schedulers.boundedElastic` sites; `ServerWebExchange`→`HttpServletRequest`/`HttpServletResponse` + `WebSession`→`HttpSession` |
| `bot/BotService.java` | rewrite | requires-design | TelegramApiClient new API; ProjectService new API; ReactiveRedisTemplate→RedisTemplate |
| `project/ProjectService.java` | rewrite | mechanical | ProjectRepository new API |
| `profile/ProfileService.java` | rewrite | requires-design | WebSession → HttpSession; ReactiveMongoTemplate → MongoTemplate; ReactiveRedisTemplate → RedisTemplate |
| `user/UserService.java` | signature-flip | trivial | UserRepository new API |
| `events/EventService.java` | signature-flip | trivial | EventRepository new API; rename `logEventBlocking` → `logEvent` (overload-collapse), drop fire-and-forget variant or keep with executor |
| `email/EmailService.java` | rewrite | mechanical | drop `Mono.fromRunnable(...).subscribeOn(boundedElastic())` |

#### 2.5 Repositories (5 files, signature-flip)

| File | Type | Complexity | Cross-file deps |
|------|------|-----------|-----------------|
| `user/UserRepository.java` | signature-flip | mechanical | None |
| `project/ProjectRepository.java` | signature-flip | mechanical | None |
| `bot/BotRepository.java` | signature-flip | mechanical | None |
| `events/EventRepository.java` | signature-flip | trivial | None |
| `webhook/RawUpdateRepository.java` | signature-flip | trivial | None |

#### 2.6 HTTP clients + outbound (2 files, rewrite)

| File | Type | Complexity | Cross-file deps |
|------|------|-----------|-----------------|
| `bot/TelegramApiClient.java` | rewrite (WebClient→RestClient) | requires-design | Used by BotService + Sender |
| `bot/TelegramSender.java` | rewrite (WebClient→RestClient + retry-loop) | requires-design | Audit-event ordering preserved; `TelegramRateLimitException` sentinel kept |

#### 2.7 Jobs + admin (4 files, mechanical .block() removal)

| File | Type | Complexity | Cross-file deps |
|------|------|-----------|-----------------|
| `webhook/ProcessTelegramUpdateJob.java` | signature-flip | mechanical | 16 .block() sites removed; ReactiveMongoTemplate → MongoTemplate; SubscriberService + FunnelTriggerService new sync API |
| `jobs/HardDeleteJob.java` | signature-flip | trivial | UserRepository new API; 2 .block() removed |
| `jobs/ProjectHardDeleteJob.java` | signature-flip | mechanical | ProjectRepository new API; ReactiveMongoTemplate → MongoTemplate; EventService renamed; 4 .block() removed |
| `admin/SuperAdminSeeder.java` | signature-flip | trivial | UserRepository new API; 3 .block() removed |

#### 2.8 Webhook filter (1 file, rewrite)

| File | Type | Complexity | Cross-file deps |
|------|------|-----------|-----------------|
| `webhook/WebhookPayloadSizeFilter.java` | rewrite (WebFilter → OncePerRequestFilter) | requires-design | Same `PathPattern` API survives; `HttpServletRequest.getHeaders(name)` returns `Enumeration<String>` |

#### 2.9 Stubs (4 files, signature-flip — could pre-flip in Wave 5)

| File | Type | Complexity | Cross-file deps |
|------|------|-----------|-----------------|
| `subscriber/SubscriberService.java` | signature-flip | trivial | Consumer is ProcessTelegramUpdateJob |
| `subscriber/NoOpSubscriberService.java` | signature-flip | trivial | Implements interface |
| `funnel/FunnelTriggerService.java` | signature-flip | trivial | Consumer is ProcessTelegramUpdateJob |
| `funnel/NoOpFunnelTriggerService.java` | signature-flip | trivial | Implements interface |

#### 2.10 Error handler (1 file, signature-flip)

| File | Type | Complexity | Cross-file deps |
|------|------|-----------|-----------------|
| `common/GlobalErrorHandler.java` | rewrite | mechanical | `WebExchangeBindException` → `MethodArgumentNotValidException`; preserve `code: null` JSON shape per AC17 |

#### 2.11 Tests (11 IT files + 8 mock-triple sites within them already counted)

| File | Type | Complexity |
|------|------|-----------|
| `AbstractIntegrationTest.java` | rewrite (WebTestClient → MockMvc + @AutoConfigureMockMvc) | requires-design |
| `HealthEndpointTest.java` | rewrite | mechanical |
| `HealthSecurityTest.java` | rewrite | mechanical |
| `SecurityBlockTest.java` | rewrite | mechanical |
| `auth/AuthControllerIT.java` | rewrite | mechanical |
| `auth/AuthControllerSliceTest.java` | `@WebFluxTest` → `@WebMvcTest` | mechanical |
| `bot/BotControllerIT.java` | rewrite + concurrency-test rewrite | requires-design |
| `project/ProjectControllerIT.java` | rewrite | mechanical |
| `project/ProjectControllerSliceTest.java` | `@WebFluxTest` → `@WebMvcTest` | mechanical |
| `security/SecurityConfigTest.java` | rewrite + mock-triple update | mechanical |
| `webhook/TelegramWebhookControllerIT.java` | rewrite + 2 concurrency-test rewrites | requires-design |
| `webhook/WebhookSecurityBlockTest.java` | rewrite + mock-triple | mechanical |

#### 2.12 Tests (StepVerifier-only — 15 files)

These flip with their corresponding service in the same atomic commit:

`AuthServiceTest`, `AuthServicePasswordResetTest`, `AuthServiceRegistrationTest`, `BotServiceTest`, `BotRepositoryTest`, `BotIndexTest`, `TelegramApiClientTest`, `TelegramSenderTest`, `ProfileServiceTest`, `ProjectServiceTest`, `UserServiceTest`, `RawUpdateRepositoryTest`, `WebhookPayloadSizeFilterTest`, `NoOpSubscriberServiceTest`, `NoOpFunnelTriggerServiceTest`.

#### 2.13 Aggregate count

| Group | Files |
|-------|-------|
| Build / config | 3 |
| Security stack | 3 (1 new, 1 delete) |
| Controllers | 6 |
| Services | 7 |
| Repositories | 5 |
| Outbound HTTP clients | 2 |
| Jobs + admin | 4 |
| Webhook filter | 1 |
| Stubs | 4 |
| Error handler | 1 |
| Integration tests | 12 |
| StepVerifier tests | 15 |
| **Total** | **63** |

(The 47-file estimate in §2 prologue is corrected to **63 files** after enumerating tests. Tests-in-flip count is non-trivial because `AbstractIntegrationTest` base-class change forces every IT into the same commit per D9.)

If subscriber/funnel stubs + their tests pre-flip in Wave 5, that takes 4 production files + 2 tests = 6 files out of the atomic commit. Net flip-commit floor: **57 files**.

### 3. Signature mapping table

#### 3.1 Controllers

| File | Old signature | New signature | Notes |
|------|---------------|---------------|-------|
| `HealthController.java` | `Mono<Map<String,String>> health()` | `Map<String,String> health()` | Trivial |
| `auth/AuthController.java` | `Mono<ResponseEntity<AuthResponse>> login(@Valid @RequestBody LoginRequest, ServerWebExchange)` | `ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest, HttpServletRequest, HttpServletResponse)` | exchange split into req/res for IP/UA + cookie writes |
| `auth/AuthController.java` | `Mono<ResponseEntity<MeResponse>> me()` | `ResponseEntity<MeResponse> me()` | Reads `SecurityContextHolder.getContext()` synchronously |
| `auth/AuthController.java` | `Mono<ResponseEntity<RegisterResponse>> register(@Valid @RequestBody RegisterRequest, ServerWebExchange)` | `ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest, HttpServletRequest, HttpServletResponse)` | — |
| `auth/AuthController.java` | `Mono<ResponseEntity<VerifyEmailResponse>> verifyEmail(@RequestParam String, ServerWebExchange)` | `ResponseEntity<VerifyEmailResponse> verifyEmail(@RequestParam String, HttpServletRequest, HttpServletResponse)` | — |
| `auth/AuthController.java` | `Mono<ResponseEntity<Void>> resendVerification(...)` | `ResponseEntity<Void> resendVerification(...)` | — |
| `auth/AuthController.java` | `Mono<ResponseEntity<Void>> logout(ServerWebExchange)` | `ResponseEntity<Void> logout(HttpServletRequest, HttpServletResponse)` | — |
| `auth/AuthController.java` | `Mono<ResponseEntity<Void>> forgotPassword(...)`, `resetPassword(...)` | `ResponseEntity<Void> forgotPassword(...)`, `resetPassword(...)` | — |
| `bot/BotController.java` | `Mono<ResponseEntity<BotResponse>> get/connect`, `Mono<ResponseEntity<Void>> disconnect/testMessage` | `ResponseEntity<BotResponse> get/connect`, `ResponseEntity<Void> disconnect/testMessage` | `ServerWebExchange` → `HttpServletRequest` |
| `bot/BotController.java` | `private static Mono<String> currentUserId()` | `private static String currentUserId()` | reads `SecurityContextHolder.getContext()` directly |
| `project/ProjectController.java` | `Mono<ResponseEntity<Flux<ProjectResponse>>> list(boolean)` | `ResponseEntity<List<ProjectResponse>> list(boolean)` | **Decision D6** — collapses streaming JSON to materialized array. MVP-cap=5 makes it safe. |
| `project/ProjectController.java` | other endpoints `Mono<ResponseEntity<ProjectResponse>>` | `ResponseEntity<ProjectResponse>` | — |
| `profile/ProfileController.java` | `Mono<ResponseEntity<ProfileResponse>> getProfile/updateProfile`, `Mono<ResponseEntity<Void>> ...` | `ResponseEntity<...>` | `exchange.getSession()` → `request.getSession()` (synchronous, returns `HttpSession`) |
| `webhook/TelegramWebhookController.java` | `Mono<ResponseEntity<Void>> receive(String, String, Document)` | `ResponseEntity<Void> receive(String, String, Document)` | Body becomes imperative — `flatMap`/`switchIfEmpty`/`onErrorResume` collapse into if/else + try/catch |

#### 3.2 Services (cross-module API)

| File | Old signature | New signature | Notes |
|------|---------------|---------------|-------|
| `auth/AuthService.java` | `Mono<AuthResponse> login(LoginRequest, ServerWebExchange)` | `AuthResponse login(LoginRequest, HttpServletRequest, HttpServletResponse)` | — |
| `auth/AuthService.java` | `Mono<MeResponse> me()` | `MeResponse me()` | — |
| `auth/AuthService.java` | `Mono<RegisterResponse> register(RegisterRequest, ServerWebExchange)` | `RegisterResponse register(RegisterRequest, HttpServletRequest, HttpServletResponse)` | — |
| `auth/AuthService.java` | `Mono<Void> resendVerification(String)` | `void resendVerification(String)` | — |
| `auth/AuthService.java` | `Mono<Void> logout(ServerWebExchange)` | `void logout(HttpServletRequest)` | — |
| `auth/AuthService.java` | `Mono<Void> forgotPassword(String, ServerWebExchange)`, `resetPassword(String, String, ServerWebExchange)` | `void forgotPassword(String, HttpServletRequest)`, `void resetPassword(String, String, HttpServletRequest, HttpServletResponse)` | reset needs response for cookie if openSession refreshes session — verify |
| `auth/AuthService.java` | `Mono<VerifyEmailResponse> verifyEmail(String, ServerWebExchange)` | `VerifyEmailResponse verifyEmail(String, HttpServletRequest, HttpServletResponse)` | — |
| `auth/AuthService.java` | `Mono<Long> terminateAllSessions(String)` | `long terminateAllSessions(String)` | Used by ProfileService — same-module |
| `auth/AuthService.java` | `private Mono<Void> openSession(User, boolean, ServerWebExchange)` | `private void openSession(User, boolean, HttpServletRequest, HttpServletResponse)` | Sets `REMEMBER_ME_ATTR` as `request.setAttribute(...)` instead of `exchange.getAttributes().put(...)` |
| `bot/BotService.java` | `Mono<Bot> getByProject(String, String)`, `connect(...)` | `Bot getByProject(String, String)`, `Bot connect(...)` | — |
| `bot/BotService.java` | `Mono<Void> disconnect(...)`, `sendTestMessage(...)` | `void disconnect(...)`, `void sendTestMessage(...)` | — |
| `project/ProjectService.java` | `Mono<Project> requireOwned(String, String, boolean)` | `Project requireOwned(String, String, boolean)` | **Cross-module API — BotService, ProcessTelegramUpdateJob future callers** |
| `project/ProjectService.java` | `Flux<Project> list(String, boolean)` | `List<Project> list(String, boolean)` | — |
| `project/ProjectService.java` | `Mono<Project> create/update/softDelete/restore(...)` | `Project create/update/softDelete/restore(...)` | — |
| `profile/ProfileService.java` | `Mono<ProfileResponse> getProfile/updateProfile(...)` | `ProfileResponse getProfile/updateProfile(...)` | — |
| `profile/ProfileService.java` | `Mono<Void> changePassword(String, String, String, WebSession, String, String)` | `void changePassword(String, String, String, HttpSession, String, String)` | WebSession → HttpSession |
| `profile/ProfileService.java` | `Mono<Long> terminateAllSessions(String)`, `terminateAllSessionsExcept(String, String)` | `long terminateAllSessions(String)`, `terminateAllSessionsExcept(String, String)` | — |
| `profile/ProfileService.java` | `Mono<Void> deleteAccount(String, WebSession, String, String)` | `void deleteAccount(String, HttpSession, String, String)` | — |
| `user/UserService.java` | `Mono<User> findByEmail/save/softDelete(...)` | `Optional<User> findByEmail(...)`, `User save(...)`, `Optional<User> softDelete(...)` | — |
| `events/EventService.java` | `Mono<Event> logEventBlocking(String, String, String, String, Map)` | `Event logEvent(String, String, String, String, Map)` (renamed, returns Event) | Caller-side `.block()` removed; old fire-and-forget `void logEvent(...)` is collapsed into this — pick one API |
| `bot/TelegramApiClient.java` | `Mono<TelegramUser> getMe(String)` | `TelegramUser getMe(String)` | RestClient `.body(...)` synchronous call |
| `bot/TelegramApiClient.java` | `Mono<Boolean> setWebhook(String, String, String)` | `boolean setWebhook(String, String, String)` | — |
| `bot/TelegramApiClient.java` | `Mono<Boolean> deleteWebhook(String)` | `boolean deleteWebhook(String)` | — |
| `bot/TelegramSender.java` | `Mono<SentMessage> sendText(String, Long, String, ...)` | `SentMessage sendText(String, Long, String, ...)` | hand-rolled retry loop replaces `Retry.backoff(...)` |

#### 3.3 Repositories

| File | Old signature | New signature | Notes |
|------|---------------|---------------|-------|
| `user/UserRepository.java` | `extends ReactiveMongoRepository<User, String>` | `extends MongoRepository<User, String>` | — |
| `user/UserRepository.java` | `Mono<User> findByEmail(String)` | `Optional<User> findByEmail(String)` | Spring Data convention |
| `user/UserRepository.java` | `Mono<User> findByEmailVerificationTokenHash(String)` | `Optional<User> findByEmailVerificationTokenHash(String)` | — |
| `user/UserRepository.java` | `Mono<User> findByPasswordResetTokenHash(String)` | `Optional<User> findByPasswordResetTokenHash(String)` | — |
| `user/UserRepository.java` | `Flux<User> findByStatusAndDeletedAtBefore(UserStatus, Instant)` | `List<User> findByStatusAndDeletedAtBefore(UserStatus, Instant)` | — |
| `project/ProjectRepository.java` | `extends ReactiveMongoRepository<Project, String>` | `extends MongoRepository<Project, String>` | — |
| `project/ProjectRepository.java` | `Flux<Project> findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(String)` | `List<Project> findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(String)` | — |
| `project/ProjectRepository.java` | `Flux<Project> findByOwnerIdOrderByCreatedAtDesc(String)` | `List<Project> findByOwnerIdOrderByCreatedAtDesc(String)` | — |
| `project/ProjectRepository.java` | `Mono<Long> countByOwnerIdAndDeletedAtIsNull(String)` | `long countByOwnerIdAndDeletedAtIsNull(String)` | — |
| `project/ProjectRepository.java` | `Mono<Project> findByOwnerIdAndNameAndDeletedAtIsNull(String, String)` | `Optional<Project> findByOwnerIdAndNameAndDeletedAtIsNull(String, String)` | — |
| `project/ProjectRepository.java` | `Mono<Project> findByOwnerIdAndNameAndIdNotAndDeletedAtIsNull(String, String, String)` | `Optional<Project> findByOwnerIdAndNameAndIdNotAndDeletedAtIsNull(String, String, String)` | — |
| `project/ProjectRepository.java` | `Flux<Project> findByDeletedAtBefore(Instant)` | `List<Project> findByDeletedAtBefore(Instant)` | — |
| `bot/BotRepository.java` | `extends ReactiveMongoRepository<Bot, String>` | `extends MongoRepository<Bot, String>` | — |
| `bot/BotRepository.java` | `Mono<Bot> findByProjectIdAndStatus(String, BotStatus)` | `Optional<Bot> findByProjectIdAndStatus(String, BotStatus)` | — |
| `bot/BotRepository.java` | `Flux<Bot> findByProjectId(String)` | `List<Bot> findByProjectId(String)` | — |
| `bot/BotRepository.java` | `Mono<Bot> findFirstByTelegramBotIdAndStatus(Long, BotStatus)` | `Optional<Bot> findFirstByTelegramBotIdAndStatus(Long, BotStatus)` | — |
| `events/EventRepository.java` | `extends ReactiveMongoRepository<Event, String>` | `extends MongoRepository<Event, String>` | — |
| `webhook/RawUpdateRepository.java` | `extends ReactiveMongoRepository<RawUpdate, String>` | `extends MongoRepository<RawUpdate, String>` | — |
| `webhook/RawUpdateRepository.java` | `Mono<RawUpdate> findFirstByProjectIdAndUpdateId(String, Long)` | `Optional<RawUpdate> findFirstByProjectIdAndUpdateId(String, Long)` | — |

#### 3.4 Security beans

| File | Old signature | New signature | Notes |
|------|---------------|---------------|-------|
| `security/SecurityConfig.java` | `@EnableWebFluxSecurity` annotation | `@EnableWebSecurity` annotation | — |
| `security/SecurityConfig.java` | `@Bean ServerSecurityContextRepository securityContextRepository()` returning `new WebSessionServerSecurityContextRepository()` | `@Bean SecurityContextRepository securityContextRepository()` returning `new HttpSessionSecurityContextRepository()` | — |
| `security/SecurityConfig.java` | `@Bean WebSessionIdResolver webSessionIdResolver(...)` returning `new RememberMeWebSessionIdResolver(...)` | `@Bean CookieSerializer cookieSerializer(...)` returning `new RememberMeCookieSerializer(...)` | bean type swap |
| `security/SecurityConfig.java` | `@Bean SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity, ServerSecurityContextRepository)` | `@Bean SecurityFilterChain springSecurityFilterChain(HttpSecurity, SecurityContextRepository)` | builder type swap |
| `security/SecurityConfig.java` | uses `CookieServerCsrfTokenRepository.withHttpOnlyFalse()`, `ServerCsrfTokenRequestAttributeHandler`, `AndServerWebExchangeMatcher`, `NegatedServerWebExchangeMatcher`, `PathPatternParserServerWebExchangeMatcher`, `CsrfWebFilter.DEFAULT_CSRF_MATCHER` | uses `CookieCsrfTokenRepository.withHttpOnlyFalse()`, `CsrfTokenRequestAttributeHandler`, `AndRequestMatcher`, `NegatedRequestMatcher`, `PathPatternRequestMatcher` (Spring Security 6.5+) or `AntPathRequestMatcher` + custom `RequestMatcher` for default verb gate | reconstruct default CSRF verb matcher via `new OrRequestMatcher(antMatcher(POST,...), antMatcher(PUT,...), antMatcher(DELETE,...), antMatcher(PATCH,...))` since `CsrfFilter.DEFAULT_CSRF_MATCHER` is private |
| `security/SecurityConfig.java` | `private WebFilter csrfCookieMaterializer()` + `http.addFilterAfter(...)` | deleted | MVC `CsrfFilter` writes cookie eagerly via repository |
| `security/SecurityConfig.java` | `corsConfigurationSource()` returns `org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource` | returns `org.springframework.web.cors.UrlBasedCorsConfigurationSource` | package change |
| `security/RememberMeWebSessionIdResolver.java` (DELETE) | `extends CookieWebSessionIdResolver` with `setSessionId(ServerWebExchange, String)` override | — | deleted file |
| `security/RememberMeCookieSerializer.java` (NEW) | — | `extends DefaultCookieSerializer` with `writeCookieValue(CookieValue)` override that reads `RequestContextHolder.currentRequestAttributes().getAttribute(REMEMBER_ME_ATTR, SCOPE_REQUEST)` and calls `cookieValue.setCookieMaxAge(rememberMe ? rememberMeDays*86400 : -1)` then `super.writeCookieValue(cookieValue)` | see §5(a) |

#### 3.5 ServerWebExchange → HttpServletRequest/Response usages

| File | Old usage | New usage |
|------|-----------|-----------|
| `auth/AuthController.java` | `ServerWebExchange exchange` parameter on 6 endpoints | `HttpServletRequest request, HttpServletResponse response` (response only needed for endpoints that touch session — logout, login, register, verifyEmail, resetPassword) |
| `auth/AuthService.java:642` | `exchange.getRequest().getHeaders().getFirst("X-Forwarded-For")`, `exchange.getRequest().getRemoteAddress()` | `request.getHeader("X-Forwarded-For")`, `request.getRemoteAddr()` |
| `auth/AuthService.java:255` | `exchange.getSession().flatMap(WebSession::invalidate)` | `HttpSession session = request.getSession(false); if (session != null) session.invalidate();` |
| `auth/AuthService.java:605` | `exchange.getAttributes().put(REMEMBER_ME_ATTR, Boolean.valueOf(rememberMe))` | `request.setAttribute(REMEMBER_ME_ATTR, Boolean.valueOf(rememberMe))` |
| `auth/AuthService.java` openSession | `securityContextRepository.save(exchange, context).then(...)` (Mono chain) | `securityContextRepository.saveContext(context, request, response);` (synchronous void) |
| `bot/BotController.java`, `project/ProjectController.java`, `profile/ProfileController.java`, `webhook/TelegramWebhookController.java` | `extractIp(ServerWebExchange)` static helper | `extractIp(HttpServletRequest)` — same helper logic; consider extracting to `common/HttpRequestUtils.java` (3 controllers + service all carry verbatim copies; today's tech debt becomes visible) |
| `profile/ProfileController.java` | `exchange.getSession().flatMap(session -> profileService.changePassword(..., session, ...))` | `HttpSession session = request.getSession(); profileService.changePassword(..., session, ...);` |
| `webhook/WebhookPayloadSizeFilter.java` | `exchange.getRequest().getHeaders().get(HttpHeaders.TRANSFER_ENCODING)` returns `List<String>` | `Collections.list(request.getHeaders(HttpHeaders.TRANSFER_ENCODING))` returns `List<String>` from `Enumeration<String>` |
| `webhook/WebhookPayloadSizeFilter.java` | `exchange.getRequest().getPath().pathWithinApplication()` (`PathContainer`) | `PathContainer.parsePath(request.getRequestURI())` |
| `webhook/WebhookPayloadSizeFilter.java` | `exchange.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE); return exchange.getResponse().setComplete()` | `response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value()); return;` (no `chain.doFilter(...)`) |

### 4. Test rewrite recipes

#### Recipe A — StepVerifier service-test → JUnit assertions

**Old shape** (e.g., `AuthServiceTest`):

```java
StepVerifier.create(authService.verifyEmail("token", exchange))
    .expectNextMatches(r -> r.redirectTo().equals("/login"))
    .verifyComplete();
```

**New shape**:

```java
VerifyEmailResponse r = authService.verifyEmail("token", mockRequest, mockResponse);
assertThat(r.redirectTo()).isEqualTo("/login");
```

**Files affected (15)** — full enumeration from §4.2:
`auth/AuthServiceTest.java`, `auth/AuthServicePasswordResetTest.java`, `auth/AuthServiceRegistrationTest.java`, `bot/BotServiceTest.java`, `bot/BotRepositoryTest.java`, `bot/BotIndexTest.java`, `bot/TelegramApiClientTest.java`, `bot/TelegramSenderTest.java`, `profile/ProfileServiceTest.java`, `project/ProjectServiceTest.java`, `user/UserServiceTest.java`, `webhook/RawUpdateRepositoryTest.java`, `webhook/WebhookPayloadSizeFilterTest.java`, `subscriber/NoOpSubscriberServiceTest.java`, `funnel/NoOpFunnelTriggerServiceTest.java`.

**Common mutator translations** (expanded per X2 — exhaustive for the Reactor surface used in this codebase):

| StepVerifier mutator | AssertJ / JUnit equivalent |
|---|---|
| `.expectNext(x).verifyComplete()` | `assertThat(actual).isEqualTo(x);` |
| `.verifyComplete()` (Mono<Void>) | Just invoke; no return value to assert. |
| `.expectError(SomeException.class).verify()` | `assertThatThrownBy(() -> service.x()).isInstanceOf(SomeException.class);` |
| `.expectErrorMatches(predicate)` | `assertThatThrownBy(() -> service.x()).matches(predicate);` |
| `.expectErrorSatisfies(consumer)` | `assertThatThrownBy(() -> service.x()).satisfies(consumer);` (consumer over `Throwable`) |
| `.expectErrorMessage("text")` | `assertThatThrownBy(() -> service.x()).hasMessage("text");` |
| `.expectErrorMessageContaining("substring")` | `assertThatThrownBy(() -> service.x()).hasMessageContaining("substring");` |
| `.expectNextMatches(predicate)` | `assertThat(actual).matches(predicate);` or unfold to `.extracting(x -> x.foo()).isEqualTo(...)`. |
| `.expectNextCount(n)` | For sync `List<T>`: `assertThat(actual).hasSize(n);` For methods that previously emitted multiple Mono items inline, see multi-emit Flux below. |
| `.expectComplete()` on `Mono<Void>` | Just invoke — successful return means complete. |
| `.expectNoEvent(Duration)` / `.thenAwait(Duration)` / `withVirtualTime(...)` | Tests using Reactor's virtual time (none verified in current codebase, but if encountered: rewrite to a `CountDownLatch.await(timeout)` for genuine timeout assertions, or remove the virtual-time mechanism altogether if it was only there to drive Reactor's scheduler). Reactor's virtual time has no servlet equivalent — manual review required. |
| `Mockito.when(repo.findX()).thenReturn(Mono.just(x))` (single-finder) | `Mockito.when(repo.findX()).thenReturn(Optional.of(x));` |
| `Mockito.when(repo.findX()).thenReturn(Mono.empty())` (single-finder) | `Mockito.when(repo.findX()).thenReturn(Optional.empty());` |
| `Mockito.when(repo.findX()).thenReturn(Flux.fromIterable(list))` | `Mockito.when(repo.findX()).thenReturn(list);` (already `List<T>`) |
| `Mockito.when(repo.count()).thenReturn(Mono.just(5L))` | `Mockito.when(repo.count()).thenReturn(5L);` |
| `Mockito.when(svc.x(...)).thenReturn(Mono.error(new E()))` | `Mockito.when(svc.x(...)).thenThrow(new E());` |
| Multi-emit `Flux.just(a, b, c)` consumed with `.expectNext(a).expectNext(b).expectNext(c).verifyComplete()` | `List<T> actual = svc.list(...); assertThat(actual).containsExactly(a, b, c);` |
| Multi-emit `Flux` consumed by `.collectList()` then `.block()` | Direct `svc.list(...)` returning `List<T>`. |
| `.expectAccessibleContext()` / `.expectAccessibleContextMatches(...)` | Reactor Context isn't relevant under VT — `RequestContextHolder` or `ThreadLocal` carry equivalent state. Remove the assertion; review whether the test still asserts something meaningful. |
| `.thenCancel()` / `.thenRequest(...)` | Backpressure-driven flow control — has no equivalent under VT. Remove the call; review test intent. |

**Multi-emit Flux unit tests with side-effect tracking** (e.g., counting how many times a downstream callback fired during emission) — rewrite as direct list iteration with counters or `Mockito.verify(downstream, times(n)).method(...)`.

**Tests asserting Reactor scheduler boundary** (e.g., `.expectThread(Schedulers.boundedElastic())` rare pattern) — remove the assertion; under VT the scheduler concept disappears.

**Async-completion tests** that previously used `.block(Duration)` outside `StepVerifier` (e.g., in `@BeforeEach` setups) — replace with direct call; no timeout needed under VT.

#### Recipe B — WebTestClient IT → MockMvc

**Old shape**:

```java
webTestClient.mutateWith(csrf())
    .post().uri("/api/auth/login")
    .contentType(MediaType.APPLICATION_JSON)
    .bodyValue(Map.of("email", "x@y.com", "password", "secret"))
    .exchange()
    .expectStatus().isOk();
```

**New shape**:

```java
mockMvc.perform(post("/api/auth/login")
        .with(csrf())                                       // SecurityMockMvcRequestPostProcessors.csrf()
        .contentType(MediaType.APPLICATION_JSON)
        .content("""{"email":"x@y.com","password":"secret"}"""))
    .andExpect(status().isOk());
```

For session mutator (replaces `.mutateWith(mockUser(...))`):

```java
mockMvc.perform(get("/api/auth/me")
        .with(user(new AppUserDetails("uid", "email", true, ...))))
    .andExpect(status().isOk());
```

For verifying cookies (replaces `.expectCookie().valueEquals(...)`):

```java
.andExpect(cookie().value("SESSION", Matchers.notNullValue()))
.andExpect(cookie().maxAge("SESSION", 2592000))   // 30 days
```

**Files affected (12)**: `AbstractIntegrationTest`, `HealthEndpointTest`, `HealthSecurityTest`, `SecurityBlockTest`, `auth/AuthControllerIT`, `auth/AuthControllerSliceTest`, `bot/BotControllerIT`, `project/ProjectControllerIT`, `project/ProjectControllerSliceTest`, `security/SecurityConfigTest`, `webhook/TelegramWebhookControllerIT`, `webhook/WebhookSecurityBlockTest`.

#### Recipe C — `@WebFluxTest` → `@WebMvcTest`

**Old shape** (`AuthControllerSliceTest.java`):

```java
@WebFluxTest(controllers = AuthController.class)
@Import({SecurityConfig.class, ...})
class AuthControllerSliceTest {
    @Autowired WebTestClient webTestClient;
    @MockitoBean AuthService authService;
    ...
}
```

**New shape**:

```java
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, ...})
class AuthControllerSliceTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean AuthService authService;
    ...
}
```

**Files affected (2)**: `auth/AuthControllerSliceTest`, `project/ProjectControllerSliceTest`.

#### Recipe D — `@MockitoBean` reactive mock triple → sync

**Old shape**:

```java
@MockitoBean private com.mongodb.reactivestreams.client.MongoClient mongoClient;
@MockitoBean private RedisConnectionFactory redisConnectionFactory;
@MockitoBean private ReactiveRedisConnectionFactory reactiveRedisConnectionFactory;
```

**New shape**:

```java
@MockitoBean private com.mongodb.client.MongoClient mongoClient;
@MockitoBean private RedisConnectionFactory redisConnectionFactory;
// ReactiveRedisConnectionFactory disappears entirely
```

**Files affected (8)**: `HealthEndpointTest`, `HealthSecurityTest`, `SecurityBlockTest`, `WebhookSecurityBlockTest`, `SecurityConfigTest`, `MeterRegistryConfigTest`, `AuthControllerSliceTest` (via `@WebFluxTest` defaults), `ProjectControllerSliceTest` (same).

#### Recipe E — `MockWebServer` + `@DynamicPropertySource` (carries over)

**Claim verification**: `okhttp3.mockwebserver.MockWebServer` is HTTP-protocol-level; it accepts any HTTP client (Reactor Netty, OkHttp, JDK `HttpClient`, Spring `RestClient`). The `MockWebServer.url("/")` returns a `HttpUrl` whose `.toString()` gives a baseUrl string — substituted into `app.telegram.base-url` via `@DynamicPropertySource`. **Survives unchanged.** No file change required for the MockWebServer setup; only the HTTP client construction site (TelegramApiClient / TelegramSender) changes from `WebClient.builder()` to `RestClient.builder()`.

**Files affected (4)**: `bot/BotControllerIT`, `bot/TelegramApiClientTest`, `bot/TelegramSenderIT`, `bot/TelegramSenderTest`. `app.telegram.base-url` injection stays identical.

#### Recipe F — Concurrency tests → VT idiom (D10)

Three sites:

**Site 1: `BotControllerIT.java:590` (`postConnect` helper)**

Current shape:
```java
private Mono<Integer> postConnect(String projectId, String token) {
    return Mono.fromCallable(() -> webTestClient.mutateWith(csrf())
            .post().uri(...).bodyValue(...).exchange().returnResult(String.class).getStatus().value())
        .subscribeOn(Schedulers.boundedElastic());
}
```

Target shape:
```java
private int postConnect(String projectId, String token) {
    return mockMvc.perform(post("/api/v1/projects/" + projectId + "/bot/connect")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(...))
        .andReturn().getResponse().getStatus();
}
// callsite — instead of building Flux, use:
try (ExecutorService es = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<Integer>> futures = IntStream.range(0, n)
        .mapToObj(i -> es.submit(() -> postConnect(projectId, token)))
        .toList();
    List<Integer> statuses = futures.stream().map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } }).toList();
}
```

**Site 2: `TelegramWebhookControllerIT.java:290` (`receive_duplicateUpdateId_returns200_singleRowSingleJob`)**

Current shape:
```java
List<Integer> statuses = Flux.range(0, 2)
    .parallel(2).runOn(Schedulers.boundedElastic())
    .flatMap(i -> Mono.fromCallable(() -> post(p.getId(), SECRET_PLAIN, samplePayload(99L))))
    .sequential()
    .collectList()
    .block();
```

Target shape (use a single shared template — `common/test/ConcurrencyTestUtils.java` per D10):
```java
List<Integer> statuses = parallelInvoke(2, () -> post(p.getId(), SECRET_PLAIN, samplePayload(99L)));
```

Where helper is (hang-safe shape per Task 10 mandate — `try/finally` on `ready.countDown()` so a task throwing before barrier release does not hang the calling thread; bounded `ready.await(...)` and `f.get(...)` timeouts as additional safety net):

```java
public static <T> List<T> parallelInvoke(int n, Callable<T> task) throws InterruptedException {
    CountDownLatch ready = new CountDownLatch(n);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<T>> futures = new ArrayList<>(n);
    try (ExecutorService es = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < n; i++) {
            futures.add(es.submit(() -> {
                try {
                    ready.countDown();          // ALWAYS countDown before any work — even if task throws, barrier releases
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("barrier wait timeout");
                    }
                    return task.call();
                } finally {
                    // ready.countDown() is already idempotent here — left in the try block so we count down even on early throw
                }
            }));
        }
        if (!ready.await(30, TimeUnit.SECONDS)) {  // bounded — prevents indefinite hang if a VT throws before countDown
            throw new IllegalStateException("not all VTs reached barrier within 30s");
        }
        start.countDown();                         // release them simultaneously
        List<T> results = new ArrayList<>(n);
        for (Future<T> f : futures) {
            try {
                results.add(f.get(30, TimeUnit.SECONDS));
            } catch (ExecutionException e) {
                throw new RuntimeException(e.getCause());
            } catch (TimeoutException e) {
                throw new RuntimeException("task timed out", e);
            }
        }
        return results;
    }
}
```

The `CountDownLatch` barrier preserves the **concurrent invocation** invariant that `parallel(2).runOn(boundedElastic())` provided — without it VT execution can serialise.

**Site 3: `TelegramWebhookControllerIT.java:346` (`p99Latency_under100msAt100ParallelRequests` — slow-tagged)**

Current shape:
```java
List<Long> latenciesNanos = Flux.range(0, 100)
    .parallel(10).runOn(Schedulers.boundedElastic())
    .flatMap(i -> Mono.fromCallable(() -> { ... measure ... }))
    .sequential()
    .collectList()
    .block();
```

Target shape:
```java
List<Long> latenciesNanos = parallelInvoke(100, () -> {
    long start = System.nanoTime();
    mockMvc.perform(post("/webhooks/telegram/" + p.getId())
            .header("X-Telegram-Bot-Api-Secret-Token", SECRET_PLAIN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(samplePayload(System.nanoTime() + ThreadLocalRandom.current().nextLong())))
        .andExpect(status().isOk());
    return System.nanoTime() - start;
});
```

`parallel(10)` (i.e., 10-way parallelism over 100 elements) drops the bounded-pool wrinkle — under VT all 100 launch concurrently. **Verify**: AC2 says P99 `<100ms`. If MockMvc adds local-stack overhead that pushes P99 over budget under genuine 100-wide concurrency, may need a bounded VT executor (e.g. via `Semaphore` to cap concurrency at 10 — preserving the original test's intent). Flag for verify wave.

### 5. Implementation hints for tricky migrations

#### (a) `RememberMeWebSessionIdResolver` → `RememberMeCookieSerializer`

Spring Session exposes `DefaultCookieSerializer.writeCookieValue(CookieValue)` as the cookie-writer hook. `CookieValue` carries the `HttpServletRequest`, mutable `cookieMaxAge`, the cookie value, etc. Subclass and override `writeCookieValue`:

```java
public class RememberMeCookieSerializer extends DefaultCookieSerializer {
    private final long rememberMeDays;
    public RememberMeCookieSerializer(ServerProperties props, long rememberMeDays) {
        this.rememberMeDays = rememberMeDays;
        // Mirror Boot's auto-config-from-ServerProperties pattern: setUseHttpOnlyCookie, setUseSecureCookie, setSameSite, etc.
        var c = props.getServlet().getSession().getCookie();
        if (c.getName() != null) setCookieName(c.getName());
        if (c.getHttpOnly() != null) setUseHttpOnlyCookie(c.getHttpOnly());
        if (c.getSecure() != null) setUseSecureCookie(c.getSecure());
        if (c.getSameSite() != null) setSameSite(c.getSameSite().attributeValue());
    }
    @Override public void writeCookieValue(CookieValue cv) {
        HttpServletRequest req = cv.getRequest();
        Boolean rememberMe = (Boolean) req.getAttribute(SessionAttributes.REMEMBER_ME_ATTR);
        if (Boolean.TRUE.equals(rememberMe)) {
            cv.setCookieMaxAge((int) Duration.ofDays(rememberMeDays).getSeconds());
        }
        // attribute absent or Boolean.FALSE → leave default Max-Age=-1 (session cookie)
        super.writeCookieValue(cv);
    }
}
```

Spring Session resolves `CookieSerializer` from the application context (it's `@ConditionalOnMissingBean`) — registering this as a `@Bean` in SecurityConfig replaces the auto-configured one.

Avoid: do NOT use `RequestContextHolder` as an alternate route to read the attribute — `cv.getRequest()` is the documented path, present on every call.

#### (b) `csrfCookieMaterializer WebFilter` → MVC eager writer

The current materializer subscribes to `Mono<CsrfToken>` so `CookieServerCsrfTokenRepository` actually writes the cookie. MVC's `CsrfFilter` calls `CsrfTokenRequestAttributeHandler.handle(request, response, deferredCsrfToken)` early; the resolved `CsrfToken` is then written to the response cookie via `CookieCsrfTokenRepository.saveToken(token, req, res)` synchronously. **No materializer filter needed**.

Frontend audit: grep of backend + frontend for `_csrf` / `CsrfToken.class.getName()` shows **the request attribute is read only inside SecurityConfig itself** (line 106). Frontend reads only the `XSRF-TOKEN` cookie (verified via search — no hits in `frontend/`). **No frontend impact**. The `_csrf` attribute disappears entirely from the migration's responsibility surface.

#### (c) `WebhookPayloadSizeFilter` (WebFlux WebFilter) → servlet `OncePerRequestFilter`

`HttpServletRequest.getHeaders(String name)` returns `Enumeration<String>` (Jakarta Servlet 5.0+). To preserve the multi-value scan over `Transfer-Encoding`:

```java
@Override
protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
    PathContainer pathContainer = PathContainer.parsePath(req.getRequestURI());
    if (!WEBHOOK_PATH.matches(pathContainer)) {
        chain.doFilter(req, res);
        return;
    }
    Enumeration<String> transferEncodingHeaders = req.getHeaders(HttpHeaders.TRANSFER_ENCODING);
    List<String> transferEncodings = Collections.list(transferEncodingHeaders);
    long contentLength = req.getContentLengthLong();   // returns -1 if missing/unparseable
    boolean chunked = transferEncodings.stream()
        .anyMatch(v -> v != null && v.toLowerCase(Locale.ROOT).contains("chunked"));
    boolean missingContentLength = contentLength < 0;
    boolean oversize = contentLength > MAX_BODY_BYTES;
    if (chunked || missingContentLength || oversize) {
        // ... scrubbed log + counter increment as today ...
        res.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        return;
    }
    chain.doFilter(req, res);
}
```

Register at `HIGHEST_PRECEDENCE + 10` via `@Order` — same precedence as today. `OncePerRequestFilter` extends `GenericFilterBean` and is picked up as a `@Component` automatically; alternatively use `FilterRegistrationBean` for explicit ordering. **`@Order` works** because Spring Boot's MVC auto-config sorts `Filter` beans by `Ordered` interface / `@Order` annotation when adding to the chain.

#### (d) `ProcessTelegramUpdateJob.handle(String)` — sixteen .block() removals

Current `.block()` sites (verified via grep, excluding comments):

1. Line 79 — `rawUpdateRepository.findById(rawUpdateId).block()` → `.orElse(null)`.
2. Line 100 — `projectRepository.findById(rawUpdate.getProjectId()).block()` → `.orElse(null)`.
3. Line 105 — `rawUpdateRepository.save(rawUpdate).block()` → `.save(rawUpdate)` (returns saved entity, ignored).
4. Line 127 — `reactiveMongoTemplate.findAndModify(...).block()` → `mongoTemplate.findAndModify(...)` (returns `RawUpdate` nullable).
5. Line 183 — `botRepository.findByProjectIdAndStatus(...).block()` → `.orElse(null)`.
6. Line 195 — `subscriberService.upsertFromTelegramUpdate(...).block()` → direct call (void).
7. Line 208 — same as 5 — `.orElse(null)`.
8. Line 227 — `reactiveMongoTemplate.findAndModify(...).block()` → `mongoTemplate.findAndModify(...)` returns `Bot` nullable; treat `null` as predicate-fail (same semantics as `Mono.empty()`).
9. Line 241 — `subscriberService.upsertFromTelegramUpdate(...).block()` → direct call.
10. Line 242 — `funnelTriggerService.fire(...).block()` → direct call.
11. Line 248 — same as 5 — `.orElse(null)`.
12. Line 253 — `subscriberService.markUnsubscribed(...).block()` → direct call.
13. Line 254 — `funnelTriggerService.cancelActiveFor(...).block()` → direct call.
14. Line 267 — `eventService.logEventBlocking(...).block()` → `eventService.logEvent(...)` (renamed, returns Event ignored).
15. Line 275 — same — `.logEvent(...)`.
16. Line 282 — same.
17. Line 289 — same.

**That's 17 sites in code** (including the two `findAndModify`). Net removal is `.block()` plus return-type adjustments. **Class structure stays identical** — same method names, same dispatch matrix, same ordering invariants (event-write before status-flip, CAS first-writer-wins). The only field-type change: `private final ReactiveMongoTemplate reactiveMongoTemplate` → `private final MongoTemplate mongoTemplate` (rename suggested).

#### (e) `TelegramApiClient` / `TelegramSender` — RestClient + hand-rolled retry

**RestClient construction** (per D1):

```java
RestClient.Builder builder = RestClient.builder()
    .baseUrl(baseUrl)
    .requestFactory(buildRequestFactory(responseTimeout));

private static ClientHttpRequestFactory buildRequestFactory(Duration responseTimeout) {
    JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .version(HttpClient.Version.HTTP_2)
        .build());
    f.setReadTimeout((int) responseTimeout.toMillis());
    return f;
}
```

`DefaultUriBuilderFactory(baseUrl)` with `EncodingMode.NONE` carries over directly into `RestClient.Builder.uriBuilderFactory(...)`.

**Hand-rolled retry** (per D2, replacing `Retry.backoff(3, 200ms).maxBackoff(2s).filter(isTransient)` in TelegramApiClient):

```java
for (int attempt = 0; ; attempt++) {
    try {
        return restClient.get().uri(...).retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, this::mapClientError)
            .body(new ParameterizedTypeReference<TelegramResult<TelegramUser>>() {});
    } catch (Throwable ex) {
        if (attempt >= MAX_ATTEMPTS - 1 || !isTransient(ex)) throw ex;
        long backoff = Math.min(200L << attempt, 2000L);  // 200 / 400 / 800 / 1600 capped at 2000
        long jitter = ThreadLocalRandom.current().nextLong(0, 100);
        try { Thread.sleep(backoff + jitter); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new RuntimeException(ie); }
    }
}
```

**Sender-specific stacked retry** (per D2, replacing the two stacked `.retryWhen(...)` for 5xx + 429-retry-after):

```java
while (true) {
    try {
        return sendOnceWithRetryOn5xx(...);   // inner loop: 1s/2s/4s with isTransient filter
    } catch (TelegramRateLimitException e) {  // sentinel preserved
        long retryAfterSeconds = Math.max(0L, Math.min(e.retryAfter(), 30L));
        long jitterMs = ThreadLocalRandom.current().nextLong(0, 200);
        Thread.sleep(retryAfterSeconds * 1000L + jitterMs);
        // loop continues — 429 wraps 5xx, the test invariant
    }
}
```

`TelegramRateLimitException` stays package-private; it's still thrown by `mapClientError` when the response is 429 + has `retry_after`. Audit events `EVENT_TELEGRAM_MESSAGE_SENT` / `EVENT_TELEGRAM_SEND_FAILED` continue to be emitted at the same points (after success / after final failure). The `attempts==4 on interleaved [5xx, 429, 5xx, 200]` test invariant survives.

#### (f) `AbstractIntegrationTest` MVC migration

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)   // RANDOM_PORT no longer required for in-context MockMvc
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import({MailpitTestConfig.class, JobRunrInMemoryConfig.class})
public abstract class AbstractIntegrationTest {
    @Autowired protected MockMvc mockMvc;
    // ... containers + DynamicPropertySource unchanged
}
```

`@AutoConfigureMockMvc` auto-applies `springSecurity()` to the MockMvc builder, so `csrf()` mutator and `user(...)` mutator work out of the box. **`@BeforeEach rebindWebTestClient()` is removed**; no manual rebinding needed.

**Verify**: WebTestClient → MockMvc breaks `responseTimeout(...)` config in `p99Latency` test. MockMvc has no transport — it's in-process method invocation. The test must be re-baselined; AC2 says P99 < 100ms; under MockMvc this is purely controller dispatch time, which should be substantially faster than full Netty over loopback. Track this as a verify-task during slow-test pass.

CSRF mutator: `import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;`
User mutator: `import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;`

#### (g) Spring Session servlet auto-config

`application.properties` rename (per D5):
- `server.reactive.session.cookie.http-only` → `server.servlet.session.cookie.http-only`
- `server.reactive.session.cookie.secure` → `server.servlet.session.cookie.secure`
- `server.reactive.session.cookie.same-site` → `server.servlet.session.cookie.same-site`

`spring.session.store-type=mongodb` survives. Boot's `SessionAutoConfiguration` picks `MongoSessionConfiguration` (servlet) instead of `MongoReactiveSessionConfiguration` (reactive) once `spring-boot-starter-web` is on classpath. The `MongoIndexedSessionRepository` writes documents to the `sessions` collection with the same `principal` field path — verified against `spring-session-data-mongodb` 3.4.x source. No code change in the consumers (`AuthService.terminateAllSessions`, `ProfileService.terminateAllSessionsExcept`) beyond `ReactiveMongoTemplate → MongoTemplate`.

If `application-test.properties` carries reactive cookie keys (verify), apply same rename there.

#### (h) `GlobalErrorHandler` — `MethodArgumentNotValidException` replacement

Old:
```java
@ExceptionHandler(WebExchangeBindException.class)
public ResponseEntity<ErrorResponse> handleBindException(WebExchangeBindException ex) {
    Stream<String> fieldErrors = ex.getFieldErrors().stream()
        .map(e -> e.getField() + ": " + e.getDefaultMessage());
    Stream<String> globalErrors = ex.getGlobalErrors().stream()
        .map(e -> e.getObjectName() + ": " + e.getDefaultMessage());
    String message = Stream.concat(fieldErrors, globalErrors).collect(Collectors.joining(", "));
    return ResponseEntity.badRequest().body(new ErrorResponse(message, null));
}
```

New:
```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<ErrorResponse> handleBindException(MethodArgumentNotValidException ex) {
    BindingResult br = ex.getBindingResult();
    Stream<String> fieldErrors = br.getFieldErrors().stream()
        .map(e -> e.getField() + ": " + e.getDefaultMessage());
    Stream<String> globalErrors = br.getGlobalErrors().stream()
        .map(e -> e.getObjectName() + ": " + e.getDefaultMessage());
    String message = Stream.concat(fieldErrors, globalErrors).collect(Collectors.joining(", "));
    return ResponseEntity.badRequest().body(new ErrorResponse(message, null));
}
```

Both `WebExchangeBindException` and `MethodArgumentNotValidException` extend `BindException` and expose the same `BindingResult`. The `getFieldErrors() / getGlobalErrors()` are identical API. **`code: null` invariant preserved** — the `ErrorResponse` constructor still receives `null` as the second arg. AC17 holds.

### 6. Wave-by-wave PR plan (~12 tasks)

Following the strangler-fig + atomic-flip insight from §6.3 of the existing research, the tasks split into three macro-phases:

**Phase A — Pre-flip cleanup (Wave 0, 5 — touchable while WebFlux still loaded)**

- **Task 1: Constant relocation (`REMEMBER_ME_ATTR`)** — Create `common/SessionAttributes.java`; move constant. Update AuthService import. Update RememberMeWebSessionIdResolver to import from common. Update test imports. Trivial PR, 4 files. **Dep**: none.
- **Task 2: Stub interface flip (`subscriber/`, `funnel/`)** — Flip `SubscriberService` + `FunnelTriggerService` interfaces from `Mono<Void>` → `void`. Update `NoOp*` impls. Update ProcessTelegramUpdateJob's 4 call sites (remove `.block()`). Update `NoOpSubscriberServiceTest` + `NoOpFunnelTriggerServiceTest` (StepVerifier → direct invocation). 6 files. **Dep**: Task 1 unrelated; can parallelize.

**Phase B — The atomic flip (Wave 12 — non-divisible per D5)**

This phase IS one PR but is enumerated as sub-tasks for tech-spec task tracking:

- **Task 3: Build + properties + security shell** — `build.gradle` swap (3 starters + reactor-test); `application.properties` cookie-prefix rename; `application-test.properties` audit; `SecurityConfig.java` rewrite (`@EnableWebFluxSecurity` → `@EnableWebSecurity`, `ServerHttpSecurity` → `HttpSecurity`, all matchers); DELETE `RememberMeWebSessionIdResolver.java`; CREATE `RememberMeCookieSerializer.java`; `common/GlobalErrorHandler.java` `WebExchangeBindException` → `MethodArgumentNotValidException`. ~7 files. **Risk concentration**: highest. **Dep**: Tasks 1, 2 complete.
- **Task 4: Repositories** — Flip all 5 `ReactiveMongoRepository` → `MongoRepository`. All `Mono<T>`/`Flux<T>` → `Optional<T>`/`List<T>`/`T`/`long`. 5 files. **Dep**: Task 3.
- **Task 5: Services (non-controller-coupled)** — `UserService`, `EventService`, `EmailService` — clean flips, no `ServerWebExchange`. 3 files. **Dep**: Task 4.
- **Task 6: Auth stack** — `AuthService` + `AuthController` + `AuthControllerSliceTest` + 3 service-tests (`AuthServiceTest`, `AuthServiceRegistrationTest`, `AuthServicePasswordResetTest`) + `AuthControllerIT`. `ServerWebExchange` → `HttpServletRequest`/`HttpServletResponse`. `WebSession.invalidate()` → `HttpSession.invalidate()`. `securityContextRepository.save(...)` sync. 7 files. **Dep**: Task 5.
- **Task 7: Project + Bot + Profile services + controllers + tests** — Flip in one go because they share `requireOwned(...)` cross-call. `ProjectService/Controller/ServiceTest/ControllerSliceTest/ControllerIT`, `BotService/Controller/ServiceTest/ControllerIT/RepositoryTest/IndexTest`, `ProfileService/Controller/ServiceTest`. ~13 files. **Dep**: Task 6.
- **Task 8: Telegram clients** — `TelegramApiClient` + `TelegramSender` + tests (`TelegramApiClientTest`, `TelegramSenderTest`, `TelegramSenderIT`). WebClient → RestClient. Hand-rolled retry. 5 files. **Dep**: Task 3 (RestClient available).
- **Task 9: Webhook + Jobs + Admin** — `TelegramWebhookController` (+IT), `WebhookPayloadSizeFilter` (+Test), `RawUpdateRepository` (+Test), `ProcessTelegramUpdateJob` (16 .block()s drop), `HardDeleteJob`, `ProjectHardDeleteJob`, `SuperAdminSeeder`, `WebhookSecurityBlockTest`. ~9 files. **Dep**: Tasks 4–8.
- **Task 10: Test infra + health + remaining ITs** — `AbstractIntegrationTest` rewrite (WebTestClient → MockMvc), `HealthController` Mono drop, `HealthEndpointTest`, `HealthSecurityTest`, `SecurityBlockTest`, `SecurityConfigTest`, `MeterRegistryConfigTest`. ~7 files. **Dep**: Tasks 3–9.

Tasks 3–10 land as ONE commit (atomic flip) but are reviewable as logical chunks. The PR will physically be a single commit per D5.

**Phase C — Post-flip verification (Wave 13)**

- **Task 11: VT pinning probe + documentation** — Boot with `-Djdk.tracePinnedThreads=full`; document findings in `.claude/skills/project-knowledge/references/patterns.md` per AC7.
- **Task 12: Documentation cleanup** — Update `patterns.md`, `architecture.md`, `deployment.md` per AC14. Grep gate per "Как проверить" step 8.
- **Task 13: Integration test for AC16/AC17/AC18** — New tests for session-continuity, validation-error JSON shape, remember-me Max-Age. These augment the existing flipped tests with explicit acceptance criteria coverage.
- **Task 14: AVP (Agent Verification Plan) + Pre-deploy QA** — Standard final-wave per template.

**Total task count: 14 tasks** (2 pre-flip + 8 flip sub-tasks + 4 post-flip + audit/qa/deploy in Final Wave).

Hard task: **Task 8** (Telegram client rewrites + retry-loop preservation) — it's the only one with non-mechanical logic that has assertion-precise test invariants (attempt counts, exact backoff sequences). Risk concentration is highest there inside the flip.

Outside the flip, **Task 9's `ProcessTelegramUpdateJob` migration is mechanical** despite the 16-.block() count — the cascade-ordering invariants (event-write-before-status-flip, CAS first-writer-wins) emerge naturally from sequential sync calls once the API flips.
