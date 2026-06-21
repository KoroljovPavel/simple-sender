---
created: 2026-05-23
status: approved
branch: dev
size: L
---

# Tech Spec: migrate-to-virtual-threads

## Solution

Migrate backend from Spring WebFlux (reactive Netty + Reactor) to Spring MVC + Java 21 virtual threads in three macro-phases:

**Phase A (Pre-flip cleanup):** Two small PRs that fit within the existing reactive runtime — relocate the `REMEMBER_ME_ATTR` constant out of `security/` to break the `auth↔security` cycle, and flip the no-op stub interfaces (`SubscriberService`, `FunnelTriggerService`) from `Mono<Void>` to `void`. Service/repository layers cannot pre-flip because every `*Repository` interface inherits from `ReactiveMongoRepository`, which is locked by `spring-boot-starter-data-mongodb-reactive` until that starter leaves the classpath.

**Phase B (Atomic flip):** One physical commit landing ~57 files (production + tests). Mutually-exclusive dependencies (`spring-boot-starter-webflux` vs `spring-boot-starter-web`) force atomicity. The commit swaps three starters, renames cookie-prefix properties, rewrites `SecurityConfig` for the servlet stack, replaces `RememberMeWebSessionIdResolver` with `RememberMeCookieSerializer` (`DefaultCookieSerializer` subclass), rewrites `WebhookPayloadSizeFilter` as `OncePerRequestFilter`, swaps `WebExchangeBindException` → `MethodArgumentNotValidException` in `GlobalErrorHandler`, flips all controller signatures from `Mono<ResponseEntity<...>>` to `ResponseEntity<...>`, flips all service/repository APIs to synchronous types, rewrites `TelegramApiClient` + `TelegramSender` on `RestClient` with hand-rolled retry loops, drops 17 `.block()` sites from `ProcessTelegramUpdateJob`, migrates `AbstractIntegrationTest` and all integration tests from `WebTestClient` to `MockMvc`, and converts all `StepVerifier` unit tests to direct JUnit assertions.

**Phase C (Post-flip verification + cleanup):** Boot with `-Djdk.tracePinnedThreads=full`, document pinning sites. Add three new acceptance integration tests (AC16 session continuity, AC17 validation 400 shape, AC18 remember-me Max-Age). Update Project Knowledge (`patterns.md` / `architecture.md` / `deployment.md`). Run AVP + pre-deploy QA.

End state: `application.properties` contains `spring.threads.virtual.enabled=true`; every request to Spring MVC dispatches onto a virtual thread; outbound HTTP, blocking Mongo, blocking Redis, and JobRunr workers all run on virtual threads transparent to application code.

## Architecture

### What we're building/modifying

- **`common/SessionAttributes`** (NEW) — Public class holding the `REMEMBER_ME_ATTR` constant. Single home shared by `AuthService` (writer) and `RememberMeCookieSerializer` (reader). Removes the `auth → security` circular import.
- **`common/HttpRequestUtils`** (NEW) — Static `extractIp(HttpServletRequest)` and `extractUserAgent(HttpServletRequest)` helpers. Consolidates the IP/UA extraction logic currently duplicated across 3 controllers + AuthService (4 sites total).
- **`common/GlobalErrorHandler`** — Rewritten: `@ExceptionHandler(WebExchangeBindException)` → `@ExceptionHandler(MethodArgumentNotValidException)`. Same `BindingResult` extraction; same `ErrorResponse(message, null)` body preserves the `code: null` invariant.
- **`security/SecurityConfig`** — Rewritten on the servlet stack: `@EnableWebSecurity`, `SecurityFilterChain`, `HttpSecurity`, MVC-side CSRF setup (`CookieCsrfTokenRepository.withHttpOnlyFalse()` paired with `XorCsrfTokenRequestAttributeHandler` to preserve BREACH mitigation from the current `XorServerCsrfTokenRequestAttributeHandler`), `HttpSessionSecurityContextRepository`, `org.springframework.web.cors.UrlBasedCorsConfigurationSource`. CSRF default-verb gate uses Spring Security's `CsrfFilter.DEFAULT_CSRF_MATCHER` directly (public static final in 6.x — covers POST/PUT/DELETE/PATCH automatically). Webhook path exclusion expressed as `AndRequestMatcher(CsrfFilter.DEFAULT_CSRF_MATCHER, NegatedRequestMatcher(antMatcher("/webhooks/telegram/{projectId}")))` — preserves the AND-scoping invariant from `patterns.md` so non-excluded paths retain GET safety. Session fixation: on successful login, `request.changeSessionId()` is invoked before `securityContextRepository.saveContext(...)` to mint a fresh `JSESSIONID` (mirrors current `WebSession.invalidate() + new session` semantics).
- **`security/RememberMeCookieSerializer`** (NEW, replaces deleted `RememberMeWebSessionIdResolver`) — `extends DefaultCookieSerializer`; constructor wires all cookie attributes from `ServerProperties.getServlet().getSession().getCookie()` via `PropertyMapper.alwaysApplyingWhenNonNull()` (`setCookieName`, `setUseHttpOnlyCookie`, `setUseSecureCookie`, `setSameSite`, `setCookiePath`, `setDomainName`) — mirrors the reactive precedent in current `RememberMeWebSessionIdResolver` so HttpOnly/Secure/SameSite/Path/Domain flow from env vars `SESSION_COOKIE_*` unchanged. Overrides `writeCookieValue(CookieValue)` to read `SessionAttributes.REMEMBER_ME_ATTR` request-attribute and set `cookieValue.setCookieMaxAge(rememberMeDays*86400)` when present and `true` (otherwise leaves default `-1` session-scoped). Registered as `@Bean CookieSerializer` to replace Spring Session's auto-configured default.
- **`webhook/WebhookPayloadSizeFilter`** — Rewritten as `extends OncePerRequestFilter`. Same path-scoped 413 logic; uses `request.getHeaders(name)` (`Enumeration<String>`) wrapped via `Collections.list(...)`.
- **All `*Repository` interfaces** (5 files) — `extends ReactiveMongoRepository<T,String>` → `extends MongoRepository<T,String>`. Custom finders flip from `Mono<T>` to `Optional<T>` and from `Flux<T>` to `List<T>`. `Mono<Long>` → `long`.
- **All `*Service` classes** — Flip public APIs to synchronous return types. Internal `Mono.fromCallable(...).subscribeOn(boundedElastic())` ceremonies collapse to direct calls. `WebSession session` parameters → `HttpSession session`. `ReactiveMongoTemplate` / `ReactiveRedisTemplate` fields → `MongoTemplate` / `RedisTemplate`.
- **`EventService`** — Single public method: `Event logEvent(String, String, String, String, Map<String,Object>) → Event`. The previous `void logEvent(...)` fire-and-forget variant and `Mono<Event> logEventBlocking(...)` variant collapse into one — under VT the direct synchronous call is cheap.
- **All `*Controller` classes** — Method signatures flip from `Mono<ResponseEntity<X>>` to `ResponseEntity<X>`. `ServerWebExchange exchange` parameter splits into `HttpServletRequest request, HttpServletResponse response`. `ProjectController.list(...)` returns `ResponseEntity<List<ProjectResponse>>` (collapses streaming `Flux` per D6).
- **`bot/TelegramApiClient`** — `WebClient` → `RestClient`. Replaces Reactor `Retry.backoff(3, 200ms).maxBackoff(2s)` with hand-rolled `for (attempt) { try { return ... } catch (transient) { sleep(backoff) } }` loop. Same `isTransient` cause-chain walking; same overall 10s ceiling. Same `requireValidTokenShape` precondition.
- **`bot/TelegramSender`** — `WebClient` → `RestClient`. Replaces stacked `.retryWhen(transient).retryWhen(rateLimit)` with two nested loops: outer `while(true)` for 429 retry-after, inner `for(attempt)` for 5xx 1s/2s/4s. `TelegramRateLimitException` sentinel survives. 30s outer ceiling enforced via deadline-based check: `Instant deadline = Instant.now().plus(Duration.ofSeconds(30))` captured at chain start; before each sleep and before each retry-attempt the loop checks `if (Instant.now().isAfter(deadline)) throw new TelegramSendException("timeout")`. Audit-event emission order preserved.
- **`webhook/TelegramWebhookController`** — `Mono<ResponseEntity<Void>> receive(...)` → `ResponseEntity<Void> receive(...)`. The `flatMap`/`switchIfEmpty(Mono.defer)`/`onErrorResume(DuplicateKeyException, ...)`/outer-`onErrorResume(ex → 500)` chain collapses to imperative if/else + nested try/catch. `JobScheduler.enqueue(UUID, lambda)` becomes a direct call (drops `Mono.fromRunnable(...).subscribeOn(boundedElastic())` wrapper).
- **`webhook/ProcessTelegramUpdateJob`** — 17 `.block()` sites disappear (verified by code-research §5(d) line-level enumeration). `ReactiveMongoTemplate` → `MongoTemplate`. Class structure, dispatch matrix, and ordering invariants (event-write-before-status-flip, CAS first-writer-wins) unchanged.
- **`admin/SuperAdminSeeder`** + **`jobs/HardDeleteJob`** + **`jobs/ProjectHardDeleteJob`** — `.block()` calls become direct sync calls. `JobRunrMongoConfig` unchanged (per D11).
- **`subscriber/SubscriberService` + `funnel/FunnelTriggerService`** (interfaces) and **`NoOpSubscriberService` + `NoOpFunnelTriggerService`** — `Mono<Void>` → `void` on all methods. Flipped in Phase A.
- **`AbstractIntegrationTest`** + all 12 IT files — `WebTestClient.bindToApplicationContext(...)` removed. `@AutoConfigureMockMvc` added. `MockMvc` autowired. CSRF mutator via `SecurityMockMvcRequestPostProcessors.csrf()`. Concurrency-test `Flux.range(0,N).parallel(N).runOn(boundedElastic())` patterns replaced with `ConcurrencyTestUtils.parallelInvoke(N, Callable)` using `CountDownLatch(ready, start)` barrier inside `Executors.newVirtualThreadPerTaskExecutor()`.
- **`TelegramWebhookP99IT`** (NEW test class, extracts the P99 latency probe from `TelegramWebhookControllerIT`) — `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate` instead of MockMvc, to preserve transport overhead that makes the P99 SLA meaningful (per D14). `@SpringBootTest.webEnvironment` is class-level — the test cannot live in the same file as MockMvc tests. The bot-connect race test in `BotControllerIT` likewise moves to its own `BotConnectRaceIT` class for the same reason (MockMvc's in-process DispatcherServlet does not reliably interleave critical sections under a parked-VT barrier; real transport is required for genuine race coverage).
- **`common/test/ConcurrencyTestUtils`** (NEW) — Test utility hosting `parallelInvoke(int, Callable<T>)` with `CountDownLatch` barrier. Used by 3 concurrency-tests.
- **`application.properties`** — Add `spring.threads.virtual.enabled=true`. Rename `server.reactive.session.cookie.*` → `server.servlet.session.cookie.*`. Audit `application-test.properties` for the same rename if present.
- **`build.gradle`** — Three starter swaps: `spring-boot-starter-webflux` → `spring-boot-starter-web`; `spring-boot-starter-data-mongodb-reactive` → `spring-boot-starter-data-mongodb`; `spring-boot-starter-data-redis-reactive` → `spring-boot-starter-data-redis`. Drop `io.projectreactor:reactor-test`.
- **`.claude/skills/project-knowledge/references/patterns.md` + `architecture.md` + `deployment.md`** — Documentation updates per AC14. Replace WebFlux-specific patterns with MVC equivalents; rewrite the test-specifics section; rewrite Spring Security WebFlux subsection; rewrite Reactor-specific retry/fire-and-forget patterns; flip the `ProjectService.requireOwned` signature note to non-reactive; update tech-stack tables. Single-file ownership: `patterns.md` is written by Task 11 (Virtual Threads section appended first) then Task 13 (all other WebFlux→MVC pattern updates, including reading Task 11's section as input) — strictly sequential within Wave 3, no parallel writes.

### How it works

**Request lifecycle post-migration.** A request hits Tomcat (replacing Netty). Tomcat dispatches onto a virtual thread (enabled via `spring.threads.virtual.enabled=true`). The VT runs synchronous controller → synchronous service → synchronous repository → sync MongoDB driver (which uses Netty internally but blocks the calling VT on the response, cheap under VT). Outbound HTTP via `RestClient` likewise blocks the VT on the JDK `HttpClient` future. Response synthesised and returned.

**Webhook + JobRunr.** `TelegramWebhookController.receive` runs on a Tomcat-spawned VT, persists to `raw_updates`, enqueues a JobRunr job with deterministic UUID, returns 200. The JobRunr worker pool stays platform-thread-backed (JobRunr 7.3.2 manages its own pool independent of `spring.threads.virtual.enabled`). `ProcessTelegramUpdateJob.handle(String)` runs on a JobRunr worker thread — blocking calls into `MongoTemplate` and stub `SubscriberService`/`FunnelTriggerService` are natural sync, no `.block()`.

**Auth + sessions.** Login dispatches `AuthService.login(...)` on a VT. BCrypt cost-12 runs on the calling VT (~250ms CPU); under load, every concurrent login spawns its own VT (no `boundedElastic` cap) — accepted per D4/R7. Session creation calls `securityContextRepository.saveContext(context, request, response)` synchronously, which writes the `SecurityContext` to `sessions` via `MongoIndexedSessionRepository` and pushes the `SESSION` cookie through the response. `RememberMeCookieSerializer.writeCookieValue(...)` reads the `REMEMBER_ME_ATTR` request attribute (written earlier in `AuthService.openSession`) and branches the cookie's `Max-Age` between 30 days and -1.

**CSRF.** MVC `CsrfFilter` invokes `XorCsrfTokenRequestAttributeHandler.handle(request, response, deferredToken)` eagerly (BREACH-resistant); the resolved token is written to the `XSRF-TOKEN` cookie via `CookieCsrfTokenRepository.saveToken(...)` synchronously. No materializer filter needed (current `csrfCookieMaterializer WebFilter` is deleted).

**Outbound Telegram.** `TelegramApiClient.setWebhook(...)` and `TelegramSender.sendText(...)` open a `RestClient` request on the calling VT (synchronous, blocks VT until response). 4xx mapped synchronously inside the response handler; 5xx triggers a hand-rolled `for(attempt)` loop with `Thread.sleep(backoff + jitter)` — sleeping a VT yields the carrier thread, cheap. 429 wraps 5xx via outer `while(true) { try { sendOnceWithRetryOn5xx } catch (TelegramRateLimitException e) { sleep(retryAfter); } }`. Token scrubber sites and audit events preserved.

**Strangler-fig boundary.** Pre-flip Phase A delivers stub interface flip + constant move. Phase B is one physical commit: the second `git add` brings in `build.gradle` and starts the cascade — all `Mono`/`Flux`/`ServerWebExchange`/`WebSession` imports must be resolved in the same commit because those symbols leave the classpath. Phase C is straightforward.

### Shared resources

| Resource | Owner (creates) | Consumers | Instance count |
|----------|----------------|-----------|----------------|
| Servlet `MongoTemplate` | Spring Boot auto-config (`spring-boot-starter-data-mongodb`) | `AuthService`, `ProfileService`, `ProcessTelegramUpdateJob`, `ProjectHardDeleteJob` | 1 (singleton) |
| Sync `MongoClient` (Spring main) | Spring Boot auto-config | `MongoTemplate`, repositories | 1 (singleton) |
| Sync `MongoClient` for JobRunr | `jobs/JobRunrMongoConfig` (unchanged per D11) | JobRunr `MongoDBStorageProvider` | 1 (singleton, separate from Spring's) |
| `RedisTemplate<String,String>` | Spring Boot auto-config (`spring-boot-starter-data-redis`) | `AuthService` (brute-force), `BotService` (bot-connect brute-force), `ProfileService` (change-password rate) | 1 (singleton) |
| `RestClient` for Telegram base URL | `TelegramApiClient` and `TelegramSender` each construct their own | `TelegramApiClient`, `TelegramSender` (two distinct `@Component` instances) | 2 (per-consumer singletons, mirrors current `WebClient` pattern) |
| `CookieSerializer` — `RememberMeCookieSerializer` | `SecurityConfig` `@Bean` | Spring Session's session-cookie write path | 1 (singleton, replaces auto-configured default) |
| `MockMvc` (test only) | `@AutoConfigureMockMvc` via `AbstractIntegrationTest` | All 12 IT classes | 1 per test class context |
| `Executors.newVirtualThreadPerTaskExecutor()` (test only) | `ConcurrencyTestUtils.parallelInvoke(...)` per call | 3 concurrency-test sites | Per-call instance (try-with-resources) |

JobRunr's worker thread pool stays platform-thread-backed (separate from the request-thread pool that becomes VT-backed). Two MongoClient beans (Spring's + JobRunr's) intentionally coexist per D11. `MeterRegistry` (`SimpleMeterRegistry`) unchanged.

## Decisions

### Decision 1: RestClient over JDK HttpClient for outbound Telegram

**Decision:** Use Spring 6.x `RestClient` (not raw `java.net.http.HttpClient`) for `TelegramApiClient` and `TelegramSender`.

**Rationale:** Minimal API delta from current `WebClient` shape; preserves the per-bean pattern documented in `patterns.md` (one upstream per `@Component` with its own client). MockWebServer-based tests carry over unchanged because MockWebServer operates at the HTTP-protocol level and accepts any JVM HTTP client.

**Alternatives considered:** Raw `java.net.http.HttpClient` — rejected because it adds boilerplate (manual `HttpRequest.newBuilder()`, manual JSON serialization) without runtime benefit (`RestClient` already uses `JdkClientHttpRequestFactory` under the hood for HTTP/2). Apache HttpClient 5 — rejected as an extra dependency.

**Serves:** US4 (future MVP features write synchronous controllers without reactive carry-over); supports D1 in user-spec.

### Decision 2: Hand-rolled retry loops over Spring Retry / Resilience4j

**Decision:** Implement retry logic for outbound Telegram clients as explicit `for`/`while` loops with `Thread.sleep(backoff + jitter)`.

**Rationale:** Only two clients, domain-specific invariants (429 wraps 5xx, `Math.max(0, Math.min(retry_after, 30))` clamp, jitter, exact `attempts==4` test on `[5xx, 429, 5xx, 200]` interleaving). Explicit code is easier to read and assert against than declarative annotations. Under VT `Thread.sleep` is a cheap carrier-thread park/unpark. No new dependency.

**Alternatives considered:** Spring Retry (`@Retryable` / `RetryTemplate`) — rejected because declarative annotations make the 429-wraps-5xx ordering invariant hard to express. Resilience4j — rejected because we don't need circuit-breaker / bulkhead, and pulling the dependency for retry alone adds surface for diminishing return.

**Serves:** US4; supports D2 in user-spec.

### Decision 3: `DefaultCookieSerializer` subclass for remember-me Max-Age branching

**Decision:** Create `RememberMeCookieSerializer extends DefaultCookieSerializer`, override `writeCookieValue(CookieValue)` to read `REMEMBER_ME_ATTR` from `cookieValue.getRequest().getAttribute(...)` and branch `cookieValue.setCookieMaxAge(...)` between `rememberMeDays*86400` and `-1`. Register as `@Bean CookieSerializer` in `SecurityConfig`, replacing Spring Session's `@ConditionalOnMissingBean` default.

**Rationale:** Spring Session exposes `writeCookieValue(CookieValue)` as the documented hook for cookie-attribute customisation; `CookieValue.getRequest()` provides the `HttpServletRequest` directly without `RequestContextHolder` gymnastics. The result is ~20 LOC, simpler than the current 60-LOC `RememberMeWebSessionIdResolver` (which had to fight `PropertyMapper` cookie-attribute juggling because `CookieWebSessionIdResolver` had no per-request hook).

**Alternatives considered:** Custom servlet `Filter` that rewrites the `Set-Cookie` header post-handler — rejected as more boilerplate and brittle against servlet response wrapping.

**Serves:** US5 (staging smoke runbook 06-bot-connection's auth flow remains identical); supports D3 in user-spec; satisfies AC18.

### Decision 4: Accept removal of implicit BCrypt back-pressure under VT

**Decision:** Do not add any throttling mechanism (`Semaphore`, work-stealing pool) for BCrypt operations during this migration. Accept that under VT each request spawns its own VT, removing the implicit `~10×CPU` cap that `Schedulers.boundedElastic` provided.

**Rationale:** Current `boundedElastic` cap is already weak production protection (~10×CPU is order-of-magnitude below realistic DOS protection). Not a regression. If/when production load profile shows CPU starvation on register-storm, add a `Semaphore` (VT-safe) point-fix. Decision is observational, not a code change.

**Alternatives considered:** Add `Semaphore.acquire(...)` around every BCrypt call now — rejected as premature optimisation against an unmeasured risk.

**Serves:** Supports D4 + R7 in user-spec; `[TECHNICAL]` deferred mitigation.

### Decision 5: Single atomic flip commit for all WebFlux-coupled files

**Decision:** The Phase B commit lands `build.gradle`, `application.properties`, `SecurityConfig`, `RememberMeCookieSerializer` (new) + `RememberMeWebSessionIdResolver` (delete), `WebhookPayloadSizeFilter`, `GlobalErrorHandler`, all controllers, all services, all repositories, both WebClient → RestClient rewrites, `AbstractIntegrationTest`, and all integration + unit tests that depend on flipped types. Target: ~57 files after pre-flip subscriber/funnel stub extraction.

**Rationale:** `spring-boot-starter-webflux` and `spring-boot-starter-web` are mutually exclusive on classpath (Spring Boot fails fast). `Mono`/`Flux`/`ServerWebExchange`/`WebSession` symbols leave the classpath the moment `spring-boot-starter-webflux` is removed. Any file that still imports them in that commit breaks the build. Strangler-fig applies only to the constant relocation (Phase A Task 1) and the no-op stub interface flip (Phase A Task 2) — every other file is forced into the atomic commit.

**Alternatives considered:** Module-by-module migration with `Mono.fromCallable(...)` bridges — rejected because the reactive starter is locked in `build.gradle`, so the repository interfaces (`ReactiveMongoRepository`) cannot pre-flip. Adding the synchronous starter alongside reactive — rejected because they're mutually exclusive.

**Serves:** US1 (`./gradlew test` always green per-PR — Phase A PRs stay green; Phase B PR is green when complete); supports D5 in user-spec.

### Decision 6: Collapse `ProjectController.list` from streaming `Flux` to materialised `List`

**Decision:** `ProjectController.list(boolean): Mono<ResponseEntity<Flux<ProjectResponse>>>` → `ResponseEntity<List<ProjectResponse>>`. Service-layer `Flux<Project> list(...)` → `List<Project> list(...)`.

**Rationale:** MVP cap of 5 projects per user (`PROJECTS_MAX_PER_USER` env var, default 5) — streaming JSON array is meaningless at this scale. No clients consume incremental JSON; UI consumes the full response.

**Alternatives considered:** Preserve streaming via `StreamingResponseBody` or `SseEmitter` — rejected because no client benefits and it adds complexity.

**Serves:** Supports D6 in user-spec; satisfies AC1 (no test regression — existing tests already collect full responses).

### Decision 7: Relocate `REMEMBER_ME_ATTR` to `common/SessionAttributes` in Phase A

**Decision:** Create `common/SessionAttributes.java` hosting `public static final String REMEMBER_ME_ATTR = "com.botfunnel.auth.rememberMe"`. Update `AuthService.openSession` import. Update `RememberMeWebSessionIdResolver` (pre-flip) and later `RememberMeCookieSerializer` (post-flip) to import from `common`. Trivial 4-file PR.

**Rationale:** Resolves `auth → security` circular import. Pre-flip auth migration would otherwise need to flip security simultaneously, expanding the atomic flip surface.

**Alternatives considered:** Inline the literal string at both call sites — rejected because string duplication is fragile across two modules. Keep constant in `security/` and add `auth → security` allowance — rejected because the dependency cycle is already noted as a code smell.

**Serves:** US1 (Phase A PR stays green); supports D7 in user-spec.

### Decision 8: No Lombok added

**Decision:** Lombok dependency stays out of `build.gradle`.

**Rationale:** Java 21 records + constructor injection cover all current use-cases. Annotation processor + obligatory IDE plugin for new developers + AOT compilation friction = lasting cost without lasting benefit.

**Alternatives considered:** Add Lombok during this migration to reduce service-constructor boilerplate — rejected at user-spec stage.

**Serves:** Supports D8 in user-spec; satisfies AC4.

### Decision 9: All 12 integration tests migrate inside the atomic flip commit

**Decision:** `AbstractIntegrationTest` base class flips inside the Phase B commit alongside its 11 IT subclasses. No parallel `AbstractMvcIntegrationTest` fork during transition.

**Rationale:** Per D5 atomicity, `WebTestClient` symbols leave the classpath when `spring-boot-starter-webflux` is removed; every IT class loses its `WebTestClient` dependency simultaneously. Forking a parallel base class adds confusion without benefit because no IT can stay reactive once the build dep flips.

**Alternatives considered:** Fork `AbstractMvcIntegrationTest`, migrate ITs one by one, delete the old base class last — rejected as inevitable extra coordination work that delivers nothing reactive ITs can use post-flip.

**Serves:** US1; supports D9 in user-spec; satisfies AC1 + AC15.

### Decision 10: `ConcurrencyTestUtils.parallelInvoke(...)` with `CountDownLatch` barrier

**Decision:** Create test utility `backend/src/test/java/com/botfunnel/common/test/ConcurrencyTestUtils.java` with `public static <T> List<T> parallelInvoke(int n, Callable<T> task) throws InterruptedException`. Uses `Executors.newVirtualThreadPerTaskExecutor()` + `CountDownLatch ready(n) + CountDownLatch start(1)` to guarantee all VTs are parked at the barrier before simultaneous release. Used uniformly by all 3 concurrency-test sites.

**Rationale:** Naive `executor.submit(...)` does NOT preserve the "fire all N requests concurrently" invariant — JVM scheduling can serialise submitted VTs. The `CountDownLatch` barrier replicates the `parallel(N).runOn(boundedElastic())` invariant that the existing tests rely on (`status pair {200,409}` for race conditions, single-row-persisted for idempotency, attempt counts for retry behaviour).

**Alternatives considered:** Plain `parallelStream()` — rejected because parallelism is `ForkJoinPool.commonPool`-backed and not VT-backed, and the barrier is not implicit. Per-test inline `CountDownLatch` — rejected because three copies of the same idiom invite drift.

**Serves:** Supports D10 in user-spec; preserves test invariants of EC2 + AC2.

### Decision 11: `JobRunrMongoConfig` dual-MongoClient setup retained

**Decision:** `jobs/JobRunrMongoConfig.java` keeps its own sync `MongoClient` bean separate from Spring's main `MongoClient` bean.

**Rationale:** JobRunr already works against this configuration in production-zero-traffic-but-strict-tests. Consolidation into one shared bean changes connection-pool sharing semantics with no measurable benefit and a chance of subtle JobRunr behaviour change.

**Alternatives considered:** Inject Spring's main `MongoClient` into JobRunr's `MongoDBStorageProvider` — rejected as unmotivated refactor.

**Serves:** Supports D11 in user-spec.

### Decision 12: `EventService` API collapse to single sync `Event logEvent(...)`

**Decision:** Collapse `EventService` public surface from two methods (`void logEvent(...)` fire-and-forget + `Mono<Event> logEventBlocking(...)`) to one: `Event logEvent(String, String, String, String, Map<String,Object>) → Event`. Caller invokes synchronously; under VT the embedded `eventRepository.save(...)` is cheap.

**Rationale:** [TECHNICAL] Q1 answered: under VT direct sync is the natural pattern; fire-and-forget semantics no longer warrant a separate method. The current API split exists only because Reactor's fire-and-forget pattern (`.subscribe(null, log::error)`) requires escaping the caller's Mono chain. Under VT that escape is unnecessary.

**Alternatives considered:** Keep `void logEvent(...)` as an executor-backed async wrapper for callers that explicitly want fire-and-forget — rejected because no caller actually needs that semantic; existing callers either ignored the returned `Mono<Event>` or `.block()`'d on it. Direct sync is correct.

**Serves:** US4 (future MVP features call `logEvent(...)` synchronously without wrapper ceremony).

### Decision 13: Extract `common/HttpRequestUtils` opportunistically

**Decision:** Create `common/HttpRequestUtils.java` with `public static String extractIp(HttpServletRequest)` and `public static String extractUserAgent(HttpServletRequest)`. Replace the 4 controller-side duplicates + `AuthService` internal helper.

**Rationale:** [TECHNICAL] Q2 answered: the migration forces editing every IP/UA extraction site (servlet API differs from `ServerWebExchange`). Extracting once during this forced edit is cheaper than duplicating four servlet-API rewrites.

**Alternatives considered:** Carry forward the 4 duplicates in their original sites — rejected because Q2 explicitly opted-in to consolidation. Wait until a third consumer appears — rejected because we already have 4 (well over the abstraction threshold).

**Serves:** US4 (smaller surface for future fields); `[TECHNICAL]` opportunistic refactor.

### Decision 14: AC2 P99 latency test + bot-connect race test extracted to dedicated `@SpringBootTest(RANDOM_PORT)` classes

**Decision:** Extract the P99 latency probe from `TelegramWebhookControllerIT` into a new test class `TelegramWebhookP99IT` configured with `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate`. Same treatment for the bot-connect race test in `BotControllerIT` → new class `BotConnectRaceIT`. `@SpringBootTest.webEnvironment` is class-level — these tests cannot share a file with MockMvc tests. Other tests in the original `*ControllerIT` files stay on MockMvc.

**Rationale:** [TECHNICAL] Q4 answered: MockMvc is in-process and has zero transport overhead — preserving the `<100ms` threshold under MockMvc would make AC2 a trivial-pass guardrail with no production-like meaning. `TestRestTemplate` over loopback Tomcat preserves transport overhead comparable to today's `WebTestClient` over loopback Netty, keeping AC2 a meaningful gate. The bot-connect race test moves for the same reason: MockMvc's in-process DispatcherServlet dispatch does not reliably interleave critical sections under a parked-VT release barrier; real transport is required to genuinely race two POSTs through the security filter + service layer.

**Alternatives considered:** Keep all tests on MockMvc and lower threshold to `<20ms` — rejected because the threshold becomes a hardcoded heuristic about local-stack speed, not a production-meaningful guarantee. Drop the P99 test entirely — rejected because Epic 04b explicitly ratified the SLA and removing it is scope creep. Keep the race test on MockMvc with `@RepeatedTest(100)` — rejected because empirical flakiness from in-process serialisation is worse than a small extra IT class.

**Serves:** AC2 retains meaningful production-like gate; preserves EC2 race invariants.

### Decision 15: EmailService preserves silent fire-and-forget semantics via try/catch

**Decision:** `EmailService.sendAsync(...)` invokes `JavaMailSender` synchronously inside a `try { ... } catch (Exception e) { log.error("Failed to send email: {}", e.toString()); }` wrapper. SMTP failures continue to produce a WARN log + a 200 response to the caller — identical visible behaviour to today's `Mono.fromRunnable(...).subscribeOn(boundedElastic()).subscribe(null, log::error)`.

**Rationale:** [TECHNICAL] Surfaced during validation: the user-spec requires "behavior preserved" for the register / resend-verification / forgot-password / reset-password flows. A naïve direct call without try/catch would propagate `MailException` upward, converting silent SMTP failures into 500 responses — a behavioural regression. The try/catch preserves the silent-failure semantics; under VT the synchronous send is cheap and the calling thread (which is itself a VT) is the appropriate execution surface.

**Alternatives considered:** Inject an `Executors.newVirtualThreadPerTaskExecutor()` and submit fire-and-forget — rejected because the calling thread is already a VT; submitting onto another VT just adds a hop without escaping the caller. The try/catch on the caller's VT achieves the same result. Re-throw the exception — rejected as a behavioural regression.

**Serves:** Preserves US-AC11 (register), US-AC13 (forgot-password/reset-password) visible behaviour; addresses critical finding from completeness review.

## Data Models

No data model changes. MongoDB collections (`users`, `projects`, `bots`, `raw_updates`, `events`, `sessions`, `jobrunr_*`), Redis keys (`brute:fail:*`, `brute:bot-connect:*`), and SMTP messages all retain their current schemas.

Verified: `spring-session-data-mongodb` 3.5.x (matches Boot 3.5.0 resolved version) servlet `MongoIndexedSessionRepository` writes the same document shape as the reactive `ReactiveMongoSessionRepository` (`principal` field path preserved). All Mongo indexes — compound unique on `(projectId, updateId)` in `raw_updates`, TTL on `raw_updates.createdAt`, TTL on `sessions.expireAt`, partial unique on `bots.telegramBotId` and `bots.projectId` — survive the auto-config swap because `spring.data.mongodb.auto-index-creation=true` stays enabled and `@Indexed` / `@CompoundIndex` annotations apply identically across the two starters.

## Dependencies

### New packages

None — `RestClient` and the JDK `HttpClient` already come transitively via `spring-boot-starter-web` (which replaces `spring-boot-starter-webflux`).

### Removed packages

- `spring-boot-starter-webflux` — Replaced by `spring-boot-starter-web`.
- `spring-boot-starter-data-mongodb-reactive` — Replaced by `spring-boot-starter-data-mongodb`.
- `spring-boot-starter-data-redis-reactive` — Replaced by `spring-boot-starter-data-redis`.
- `io.projectreactor:reactor-test` (testImplementation) — No remaining consumers post-migration.

### Added packages

- `spring-boot-starter-web` — Servlet stack on Tomcat (Boot 3.5.0 default).
- `spring-boot-starter-data-mongodb` — Synchronous MongoDB driver + `MongoTemplate` + `MongoRepository`.
- `spring-boot-starter-data-redis` — Synchronous Lettuce sync API + `RedisTemplate`.

### Using existing (from project)

- `spring-boot-starter-security` — unchanged; auto-config picks servlet stack when `spring-boot-starter-web` is on classpath.
- `spring-session-data-mongodb` — unchanged; ships both servlet and reactive auto-configs, Boot picks per `WebApplicationType`.
- `spring-boot-starter-mail` — unchanged; was always synchronous (`JavaMailSender`).
- `spring-boot-starter-validation` — unchanged.
- `io.micrometer:micrometer-core` — unchanged.
- `org.jobrunr:jobrunr-spring-boot-3-starter:7.3.2` — unchanged.
- `org.mongodb:mongodb-driver-sync` — unchanged; was already present for JobRunr; now also backs Spring's main `MongoClient`.
- `com.squareup.okhttp3:mockwebserver` (testImplementation) — unchanged; HTTP-protocol level.
- `org.testcontainers:mongodb`, `org.testcontainers:junit-jupiter`, `ch.martinelli.oss:testcontainers-mailpit` — unchanged.
- `spring-security-test` — unchanged; provides `SecurityMockMvcRequestPostProcessors.csrf()` and `.user(...)` mutators.

## Testing Strategy

**Feature size:** L

### Unit tests

15 `StepVerifier`-based test files convert to direct JUnit assertions (`AssertJ` API: `assertThat(...).isEqualTo(...)`, `assertThatThrownBy(() -> ...).isInstanceOf(...)`). Mockito stubbing migrates from `thenReturn(Mono.just(x))` to `thenReturn(Optional.of(x))` / `thenReturn(x)` / `thenReturn(List.of(...))` per finder signature. Service unit tests for `auth`, `bot`, `profile`, `project`, `user`, `subscriber`, `funnel`, `webhook` modules all migrate this pattern.

### Integration tests

12 `WebTestClient`-based integration tests convert to `MockMvc`:
- `AbstractIntegrationTest` — `@AutoConfigureMockMvc` + `MockMvc` autowire; `@BeforeEach rebindWebTestClient` removed.
- `HealthEndpointTest`, `HealthSecurityTest`, `SecurityBlockTest`, `WebhookSecurityBlockTest`, `SecurityConfigTest`, `MeterRegistryConfigTest` — mock-triple update + MockMvc usage.
- `AuthControllerSliceTest`, `ProjectControllerSliceTest` — `@WebFluxTest` → `@WebMvcTest`.
- `AuthControllerIT`, `BotControllerIT`, `ProjectControllerIT`, `TelegramWebhookControllerIT` — full integration tests on MockMvc except the slow-tagged P99 test (per D14, that one uses RANDOM_PORT + `TestRestTemplate`).
- `TelegramSenderIT` — MockWebServer-driven, MockMvc-irrelevant; only HTTP client construction changes.

Three new integration tests added per Phase C:
- AC16: session-continuity test asserting `principal`-lookup on `sessions` collection still terminates a logged-in user's other sessions after the migration.
- AC17: validation-error response shape test asserting `MethodArgumentNotValidException` produces JSON with `code: null` (preserves `useApiError` contract).
- AC18: remember-me Max-Age automated assertion via MockMvc `.andExpect(cookie().maxAge("SESSION", 2592000))` for `rememberMe=true`, absence for `rememberMe=false`.

Three concurrency-test sites (`BotControllerIT.connect_concurrent_race`, `TelegramWebhookControllerIT.receive_duplicateUpdateId_returns200_singleRowSingleJob`, `TelegramWebhookControllerIT.p99Latency_under100msAt100ParallelRequests`) rewrite to `ConcurrencyTestUtils.parallelInvoke(N, Callable)` per D10. Bot-connect race retains `{200,409}` + single-persisted-row + setWebhook count `[1, 2]` invariants; webhook idempotency retains 200 + single row + deterministic UUID enqueue invariants; P99 test runs under `TestRestTemplate` per D14.

### E2E tests

Not done. Frontend unchanged; existing Playwright spec `frontend/e2e/i18n.spec.ts` continues to cover the golden-path locale switch.

## Agent Verification Plan

**Source:** user-spec "Как проверить" section + AC1-AC18.

### Verification approach

Agent runs automated checks during pre-deploy QA: full test suite, slow-tagged P99 probe with `-PrunSlow=true`, 4 grep gates against reactive imports / Lombok / VT property / reactive starters, doc-update grep. Post-deploy verification not applicable — no production deploy in scope (deployment.md says TBD). VT-pinning probe (AC7) is a one-time observational step at the end of Phase B: boot backend with `-Djdk.tracePinnedThreads=full`, capture pinning sites, document in `patterns.md` Virtual Threads section. Manual UI smoke and Telegram smoke runbooks are operator-driven per `manual_user_actions` in user-spec.

Per-task `Verify-smoke` and `Verify-user` fields appear in the Implementation Tasks below.

### Tools required

- `bash` — gradle runs, grep gates, `git revert` for rollback.
- `mongosh` — pre-flip JobRunr queue drain, post-flip session continuity manual verification.
- Browser DevTools (Application → Cookies) — manual AC12 verification (now also covered automatically by AC18).
- Telegram MCP / Playwright MCP — **NOT required**: frontend unchanged, Telegram integration unchanged; manual smoke runbooks 06/07/08 cover real-Telegram verification via the operator.

## Risks

| Risk | Mitigation |
|------|-----------|
| R1: VT pinning on BCrypt + Lettuce internal `synchronized` blocks | AC7 observational gate via `-Djdk.tracePinnedThreads=full`; document, do not block. SB4/JDK24 future fix. |
| R2: Lettuce sync API blocking VT on Netty future | Documented in `patterns.md` as VT happy-path; no code change needed. |
| R3: Spring Session `principal` field path continuity | AC16 integration test confirms `principal`-lookup still terminates sessions correctly post-migration. |
| R4: CSRF cookie write path regression | Existing CSRF integration test migrates to MockMvc; AC18 cookie assertion + AC11-AC13 manual UI smoke catch regressions. |
| R5: In-flight session bytes-shape continuity | Manual operator step pre-flip: log into dev env via UI. Post-flip: refresh page, must remain logged in. If breaks → one-time re-login (no production users yet). |
| R6: JobRunr in-flight jobs serialisation continuity | Manual operator step pre-flip: drain JobRunr queue (`db.jobrunr_jobs.find({state:{$in:['ENQUEUED','PROCESSING']}}).count() === 0`). Post-flip: integration test on worker entrypoint. |
| R7: Implicit BCrypt back-pressure removal under VT | Accepted per D4. Reactive mitigation (Semaphore) deferred until production load profile shows starvation. |
| **R8: Atomic flip PR is ~57 files — review fatigue** | Phase B PR contents are split into 8 logical sub-task descriptions in the Implementation Tasks below to ease reviewer navigation, but the physical commit is one. Self-review checklist: grep gate AC3 must pass locally before pushing. |
| **R9: MockMvc P99 vs production transport gap** | Mitigated by D14 — slow-tagged P99 test runs under `TestRestTemplate` + RANDOM_PORT, preserving meaningful transport-overhead measurement. |

## User-Spec Deviations

- **Added: `common/HttpRequestUtils`** (not in user-spec). Reason: opportunistic consolidation during forced rewrite of 4 duplicated `extractIp(...)` sites. Confirmed by user during clarification (Q2). → [PENDING USER APPROVAL]
- **Added: `EventService` API collapse from two public methods to one** (not in user-spec). Reason: under VT direct sync call is the natural pattern; existing fire-and-forget split exists only because of Reactor's chain-escape ceremony. Confirmed by user during clarification (Q1). → [PENDING USER APPROVAL]
- **Added: `common/test/ConcurrencyTestUtils.parallelInvoke(...)`** (not in user-spec). Reason: D10 requires a uniform VT-based concurrency idiom across 3 sites; without a `CountDownLatch` barrier, VTs serialise. Confirmed by user during clarification (Q3). → [PENDING USER APPROVAL]
- **Modified: AC2 verification strategy.** User-spec AC2 says "webhook P99 latency probe (`TelegramWebhookControllerIT` slow-tagged) confirms SLA <100ms". Tech-spec moves this single test method to a separate `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate` setup while other tests in the same file use MockMvc. Reason: MockMvc has no transport overhead; preserving threshold under MockMvc makes AC2 a trivial gate without production-like meaning. Confirmed by user during clarification (Q4). → [PENDING USER APPROVAL]
- **Added: R8 (atomic flip PR review fatigue) and R9 (MockMvc transport gap)** — risk-register additions surfaced by code-research implementation phase. → [PENDING USER APPROVAL]
- **Refined: Phase A scope.** User-spec D5 said "strangler-fig applies only to services/repository ahead of [atomic flip] commit". Code-research found that service and repository layers cannot pre-flip because every `*Repository` interface inherits from `ReactiveMongoRepository`, which is locked by the reactive starter. Phase A reduces to: (1) `REMEMBER_ME_ATTR` constant relocation + (2) `subscriber/funnel` stub interface flip. All service/repository layers move into the atomic flip commit. Reason: classpath constraint discovered during implementation deepening. → [PENDING USER APPROVAL]
- **Added: D15 (`EmailService` try/catch preserves silent fire-and-forget semantics).** User-spec said behaviour preserved; naive direct sync call would convert silent SMTP failures into 500 responses on register/forgot-password flows. D15 adds an explicit try/catch wrapper to preserve current observable behaviour. → [PENDING USER APPROVAL]
- **Added: `TelegramWebhookP99IT` and `BotConnectRaceIT` new test classes.** D14 extracts the P99 latency probe and the bot-connect race test to dedicated `@SpringBootTest(RANDOM_PORT)` classes because (a) `@SpringBootTest.webEnvironment` is class-level — cannot coexist with MockMvc tests in the same file; (b) MockMvc's in-process DispatcherServlet does not reliably interleave critical sections for the race test. Both moves preserve original test invariants on real transport. → [PENDING USER APPROVAL]
- **Added: TC10 (token-scrubber preservation), TC11 (XSRF-TOKEN co-emission), TC12 (per-file coverage floor for new/rewritten high-risk files)** — technical acceptance criteria added in response to security + test validator feedback. → [PENDING USER APPROVAL]
- **Strengthened: AC16/AC17/AC18 assertions.** Task 12 now strengthens these to cover (AC16) pre-flip BSON fixture continuity, (AC17) joined-error message format pinning, (AC18) all cookie attributes (HttpOnly/Secure/SameSite) + XSRF-TOKEN co-emission. Lifts the rest of R3/R4/R5 from manual to automated. → [PENDING USER APPROVAL]
- **Refined: Decision 3 (`RememberMeCookieSerializer`) explicit cookie-attribute wiring** via `PropertyMapper.alwaysApplyingWhenNonNull()` mirroring reactive precedent — ensures `SESSION_COOKIE_*` env vars flow through unchanged. → [PENDING USER APPROVAL]

## Acceptance Criteria

Technical acceptance criteria complementing user-spec AC1-AC18:

- [x] **TC1.** No new compile-time warnings introduced in `backend/build/reports/compile-java.txt` beyond pre-migration baseline.
- [x] **TC2.** No regressions in `./gradlew jacocoTestReport` coverage: per-module coverage ≥ pre-migration baseline.
- [x] **TC3.** All `@MockitoBean` mock-triple sites updated to new types: grep `com.mongodb.reactivestreams.client.MongoClient` and `ReactiveRedisConnectionFactory` in `backend/src/test/java` returns 0 matches.
- [x] **TC4.** `application.properties` and `application-test.properties` contain no `server.reactive.session.cookie.*` keys; instead, `server.servlet.session.cookie.*` keys present.
- [x] **TC5.** New `RememberMeCookieSerializer` registered as `@Bean CookieSerializer` in `SecurityConfig`; Spring Session does not pick the auto-configured default (verified by integration test asserting `RememberMeCookieSerializer.class` instance).
- [x] **TC6.** `GlobalErrorHandler` has `@ExceptionHandler(MethodArgumentNotValidException.class)` and does NOT have `@ExceptionHandler(WebExchangeBindException.class)` — confirmed via grep + integration test.
- [x] **TC7.** `MongoRepository` (sync) replaces `ReactiveMongoRepository` in 5 repository interfaces — direct file grep.
- [x] **TC8.** `JobRunrMongoConfig` retains its own sync `MongoClient` bean (separate from Spring's main bean) — confirmed via integration test asserting two distinct `MongoClient` beans in context.
- [x] **TC9.** All controller methods return `ResponseEntity<X>` (not `Mono<ResponseEntity<X>>`) — confirmed via compilation + grep.
- [x] **TC10.** Token-scrubber sites preserved across imperative rewrites: per-site `ListAppender<ILoggingEvent>` test asserts no raw bot-token-shaped string appears in WARN/ERROR logs at the 6 enumerated sites (controller DuplicateKey WARN, controller enqueue-failure ERROR, filter 413 WARN, worker INFO/WARN/ERROR sites, sender 4xx/5xx/429/timeout sites). Same shape as existing per-site `BotTokenLeakTest`.
- [x] **TC11.** CSRF cookie write path: integration test asserts a response to a GET `/api/auth/me` (or any safe verb on a CSRF-active path) carries both `Set-Cookie: SESSION=...` and `Set-Cookie: XSRF-TOKEN=...` simultaneously after a fresh login. Closes the `csrfCookieMaterializer` removal regression risk. _(Base assertion satisfied via structural wiring + manual curl per Task 17; same-response co-emission on POST login deferred to operator FE smoke per Task 17 TC11-extension.)_
- [ ] **TC12.** Per-file coverage floor: new and rewritten high-risk files (`RememberMeCookieSerializer`, `WebhookPayloadSizeFilter`, `TelegramApiClient`, `TelegramSender`, `GlobalErrorHandler`, `ConcurrencyTestUtils`, `HttpRequestUtils`) reach ≥80% line+branch coverage in `jacoco` per-file report (supplements TC2 aggregate guard). _(3 files below 80% branch — Task 16 F-M1 Major non-blocking; carried as post-merge backlog per Task 17 decision.)_

## Implementation Tasks

**Task count: 17.** Above the 15-task heuristic threshold from the planning skill. Kept as-is per user decision (X1) because: (a) 4 of the 17 are template-mandatory audit/QA tasks (3 audit + 1 pre-deploy QA); (b) Wave 2 sub-tasks (3–10) explicitly enumerate the single physical atomic-flip commit by logical chunk to ease reviewer navigation per D5 — collapsing them obscures the migration shape; (c) functional refactoring tasks total 14, in line with code-research's estimate.

### Wave 1 (Pre-flip cleanup — parallelizable)

#### Task 1: Constant relocation — `REMEMBER_ME_ATTR` → `common/SessionAttributes`
- **Description:** Resolve `auth → security` circular dependency before atomic flip. Create new public class `common/SessionAttributes.java` hosting the existing `REMEMBER_ME_ATTR` constant. Update import sites and corresponding tests. Project stays fully compileable on the reactive stack — purely a refactor. Result: AuthService imports from `common`; RememberMeWebSessionIdResolver imports from `common`; no auth/security source files import each other.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/common/SessionAttributes.java` (NEW), `backend/src/main/java/com/botfunnel/auth/AuthService.java`, `backend/src/main/java/com/botfunnel/security/RememberMeWebSessionIdResolver.java`, `backend/src/test/java/com/botfunnel/auth/AuthServiceTest.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/security/RememberMeWebSessionIdResolver.java`, `backend/src/main/java/com/botfunnel/auth/AuthService.java`

#### Task 2: Stub interface flip — `subscriber/` + `funnel/` `Mono<Void>` → `void`
- **Description:** Flip `SubscriberService` and `FunnelTriggerService` interfaces and their `NoOp*` implementations from `Mono<Void>` to `void`. Update the 4 `.block()` call sites in `ProcessTelegramUpdateJob` accordingly. Rewrite the two `NoOp*` unit tests from `StepVerifier` to direct invocation assertions. Files do not import any WebFlux HTTP types (only `reactor.core.publisher.Mono`), so the flip is achievable on the reactive stack. Result: ProcessTelegramUpdateJob direct calls to stub services; tests assert side-effect-free invocation.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/subscriber/SubscriberService.java`, `backend/src/main/java/com/botfunnel/subscriber/NoOpSubscriberService.java`, `backend/src/main/java/com/botfunnel/funnel/FunnelTriggerService.java`, `backend/src/main/java/com/botfunnel/funnel/NoOpFunnelTriggerService.java`, `backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java`, `backend/src/test/java/com/botfunnel/subscriber/NoOpSubscriberServiceTest.java`, `backend/src/test/java/com/botfunnel/funnel/NoOpFunnelTriggerServiceTest.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java`

### Wave 2 (Atomic flip — single physical commit, enumerated as logical sub-tasks)

Tasks 3–10 land as ONE physical commit per D5/D9. Sub-tasks exist to ease review navigation; no inter-sub-task ordering is enforceable on disk.

#### Task 3: Build + properties + security shell + error handler
- **Description:** Swap WebFlux ↔ MVC stack at the build + config layer and rewrite the security shell. Includes: three starter swaps in `build.gradle`, cookie-prefix rename in `application.properties` + VT-enablement property, full `SecurityConfig` rewrite for servlet stack (per D5, D14 architecture details, D15 CSRF handler choice), `RememberMeWebSessionIdResolver` delete + `RememberMeCookieSerializer` create per D3, and `GlobalErrorHandler` exception-type swap per AC17. Result: project compiles on servlet stack (modulo the rest of Wave 2 sub-tasks); CSRF, remember-me, and validation 400 wiring functional.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew compileJava` succeeds after Wave 2 fully landed; integration test `SecurityConfigTest` boots context and asserts `RememberMeCookieSerializer.class` bean instance present
- **Files to modify:** `backend/build.gradle`, `backend/src/main/resources/application.properties`, `backend/src/test/resources/application-test.properties` (if relevant keys present), `backend/src/main/java/com/botfunnel/security/SecurityConfig.java`, `backend/src/main/java/com/botfunnel/security/RememberMeWebSessionIdResolver.java` (DELETE), `backend/src/main/java/com/botfunnel/security/RememberMeCookieSerializer.java` (NEW), `backend/src/main/java/com/botfunnel/common/GlobalErrorHandler.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/common/SessionAttributes.java`

#### Task 4: Repositories — `ReactiveMongoRepository` → `MongoRepository`
- **Description:** Flip all 5 repository interfaces to extend `MongoRepository<T,String>`. Custom finder return types change from `Mono<T>`/`Flux<T>`/`Mono<Long>` to `Optional<T>`/`List<T>`/`long`. Result: repository layer fully synchronous.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer (security-auditor intentionally omitted: pure type-signature refactor; no auth/data-handling surface — Audit Wave covers cross-cutting security)
- **Files to modify:** `backend/src/main/java/com/botfunnel/user/UserRepository.java`, `backend/src/main/java/com/botfunnel/project/ProjectRepository.java`, `backend/src/main/java/com/botfunnel/bot/BotRepository.java`, `backend/src/main/java/com/botfunnel/events/EventRepository.java`, `backend/src/main/java/com/botfunnel/webhook/RawUpdateRepository.java`
- **Files to read:** (none beyond the files above)

#### Task 5: Services not touching `ServerWebExchange` — `UserService`, `EventService`, `EmailService`, `common/HttpRequestUtils`
- **Description:** Flip three leaf services to synchronous APIs and create the new `HttpRequestUtils` helper per D13. `UserService` returns plain types. `EventService` collapses per D12. `EmailService.sendAsync(...)` becomes a try/catch-wrapped synchronous send per D15 (preserves silent fire-and-forget semantics). Result: three services and one helper compiled on servlet stack with no behavioural regression.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/user/UserService.java`, `backend/src/main/java/com/botfunnel/events/EventService.java`, `backend/src/main/java/com/botfunnel/email/EmailService.java`, `backend/src/main/java/com/botfunnel/common/HttpRequestUtils.java` (NEW)
- **Files to read:** existing controllers using `extractIp(...)` to verify signature compatibility

#### Task 6: Auth stack — `AuthService` + `AuthController` + tests
- **Description:** Flip the 650-LOC `AuthService` and its controller + 4 tests to synchronous APIs. All `Mono.fromCallable(...)` BCrypt sites (4 in `AuthService.java`) become direct calls. `ServerWebExchange`/`WebSession` split into servlet equivalents per signature mapping in code-research §3. Preserves: timing-attack BCrypt-dummy-hash path on `handleUserNotFound`, brute-force Redis INCR+EXPIRE+DEL atomicity (sync Lettuce primitives), session-fixation `changeSessionId()` after login. Result: auth flow fully synchronous; sessions invalidated via `HttpSession.invalidate()`; all timing/brute-force invariants intact.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/auth/AuthService.java`, `backend/src/main/java/com/botfunnel/auth/AuthController.java`, `backend/src/test/java/com/botfunnel/auth/AuthServiceTest.java`, `backend/src/test/java/com/botfunnel/auth/AuthServiceRegistrationTest.java`, `backend/src/test/java/com/botfunnel/auth/AuthServicePasswordResetTest.java`, `backend/src/test/java/com/botfunnel/auth/AuthControllerSliceTest.java`, `backend/src/test/java/com/botfunnel/auth/AuthControllerIT.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/common/SessionAttributes.java`, `backend/src/main/java/com/botfunnel/common/HttpRequestUtils.java`, `backend/src/main/java/com/botfunnel/security/SecurityConfig.java`

#### Task 7: Project + Bot + Profile services + controllers + tests
- **Description:** Flip the three project-scoped service trees in one chunk because they share `requireOwned(...)` cross-call. `ProjectService.requireOwned(...)` returns `Project` per signature mapping in code-research §3, preserving anti-enumeration 404 mapping (uniform 404 for foreign-owned / soft-deleted / `IllegalArgumentException` on bad ObjectId hex). `ProjectController.list(...)` collapses to `List<ProjectResponse>` per D6. ITs migrate to MockMvc; bot-connect race-test extracts to new `BotConnectRaceIT` class with `TestRestTemplate` per D14. Brute-force INCR+EXPIRE+DEL atomicity preserved via sync Lettuce primitives. Result: project/bot/profile modules fully synchronous; race-test invariants preserved on real transport; tenancy guard semantics unchanged.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/project/ProjectService.java`, `backend/src/main/java/com/botfunnel/project/ProjectController.java`, `backend/src/main/java/com/botfunnel/bot/BotService.java`, `backend/src/main/java/com/botfunnel/bot/BotController.java`, `backend/src/main/java/com/botfunnel/profile/ProfileService.java`, `backend/src/main/java/com/botfunnel/profile/ProfileController.java`, all corresponding tests under `backend/src/test/java/com/botfunnel/{project,bot,profile}/`, new file `backend/src/test/java/com/botfunnel/bot/BotConnectRaceIT.java`
- **Files to read:** `backend/src/test/java/com/botfunnel/common/test/ConcurrencyTestUtils.java`, `backend/src/main/java/com/botfunnel/common/HttpRequestUtils.java`

#### Task 8: Telegram clients + retry — `TelegramApiClient` + `TelegramSender` + tests
- **Description:** Rewrite both outbound Telegram clients on `RestClient` (per D1) with hand-rolled retry loops (per D2). Architecture section + D2 + D14 define the loop shape, deadline, and 429-wraps-5xx ordering — task implements them with audit-event emission order, token-scrubber sites, and `[5xx, 429, 5xx, 200]` interleaving test invariant preserved. Result: outbound HTTP fully synchronous; production behaviour identical including retry semantics.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** existing `MockWebServer`-driven tests (`TelegramApiClientTest`, `TelegramSenderTest`, `TelegramSenderIT`) cover the retry ladder, 429-wraps-5xx ordering, and scrubber assertions end-to-end after the rewrite
- **Files to modify:** `backend/src/main/java/com/botfunnel/bot/TelegramApiClient.java`, `backend/src/main/java/com/botfunnel/bot/TelegramSender.java`, `backend/src/test/java/com/botfunnel/bot/TelegramApiClientTest.java`, `backend/src/test/java/com/botfunnel/bot/TelegramSenderTest.java`, `backend/src/test/java/com/botfunnel/bot/TelegramSenderIT.java`
- **Files to read:** existing `TelegramRateLimitException`, `scrubTokens` helper, `isTransient` helper

#### Task 9: Webhook + Jobs + Admin
- **Description:** Drop 17 `.block()` sites from `ProcessTelegramUpdateJob.handle(String)` (enumerated in code-research §5(d)); preserve event-write-before-status-flip + CAS first-writer-wins invariants. Rewrite `WebhookPayloadSizeFilter` as `OncePerRequestFilter` preserving PathPattern path matching (`/webhooks/telegram/{projectId}` single-segment) + chunked Transfer-Encoding rejection + missing-Content-Length rejection per `patterns.md` Webhook Ingestion section. Rewrite `TelegramWebhookController.receive(...)` as imperative method preserving 401-vs-404 status-distinguishing carve-out + explicit-status no-body responses + counter ownership in the filter (not controller). Flip `HardDeleteJob`, `ProjectHardDeleteJob`, `SuperAdminSeeder`. Migrate IT files; P99 probe extracts to new `TelegramWebhookP99IT` per D14. Result: webhook ingestion + JobRunr workers fully synchronous; idempotency + P99 SLA + all security invariants preserved.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew test --tests com.botfunnel.webhook.*` passes after Wave 2 lands; verifies idempotency + 413 + P99 (slow tag) inside the standard test run
- **Files to modify:** `backend/src/main/java/com/botfunnel/webhook/TelegramWebhookController.java`, `backend/src/main/java/com/botfunnel/webhook/WebhookPayloadSizeFilter.java`, `backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java`, `backend/src/main/java/com/botfunnel/jobs/HardDeleteJob.java`, `backend/src/main/java/com/botfunnel/jobs/ProjectHardDeleteJob.java`, `backend/src/main/java/com/botfunnel/admin/SuperAdminSeeder.java`, all corresponding tests under `backend/src/test/java/com/botfunnel/webhook/`, new file `backend/src/test/java/com/botfunnel/webhook/TelegramWebhookP99IT.java`
- **Files to read:** `backend/src/test/java/com/botfunnel/common/test/ConcurrencyTestUtils.java`

#### Task 10: Test infrastructure — `AbstractIntegrationTest`, `HealthController`, mock-triple sites, `ConcurrencyTestUtils`
- **Description:** Rewrite `AbstractIntegrationTest` (WebTestClient → MockMvc; `@AutoConfigureMockMvc`; drop `rebindWebTestClient`). Create `ConcurrencyTestUtils.parallelInvoke(...)` per D10 — implementation MUST wrap each VT body in `try { … } finally { ready.countDown(); }` so a task throwing before barrier release does not hang the calling thread. Drop `Mono<>` wrapper from `HealthController`. Update `@MockitoBean` mock-triple in all affected wiring-only test classes (6 reactive-driver mock sites + 2 `@WebFluxTest` slice tests via their defaults — 8 test classes total). Result: test infrastructure fully servlet; mock-triple matches new types; `parallelInvoke` available for Wave 7 + 9.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer (security-auditor intentionally omitted: pure test infrastructure refactor — Audit Wave covers cross-cutting security)
- **Files to modify:** `backend/src/test/java/com/botfunnel/AbstractIntegrationTest.java`, `backend/src/test/java/com/botfunnel/common/test/ConcurrencyTestUtils.java` (NEW), `backend/src/main/java/com/botfunnel/HealthController.java`, `backend/src/test/java/com/botfunnel/HealthEndpointTest.java`, `backend/src/test/java/com/botfunnel/HealthSecurityTest.java`, `backend/src/test/java/com/botfunnel/SecurityBlockTest.java`, `backend/src/test/java/com/botfunnel/security/SecurityConfigTest.java`, `backend/src/test/java/com/botfunnel/common/metrics/MeterRegistryConfigTest.java`, `backend/src/test/java/com/botfunnel/webhook/WebhookSecurityBlockTest.java`
- **Files to read:** existing `AbstractIntegrationTest`, existing mock-triple usage sites

### Wave 3 (Post-flip verification + documentation — Task 11 → Task 12 in parallel, then Task 13 sequentially)

#### Task 11: VT pinning probe + patterns.md "Virtual Threads" section
- **Description:** Boot backend with `-Djdk.tracePinnedThreads=full` after Wave 2 lands. Capture the pinning sites observed at startup + during a synthetic register-storm + a synthetic webhook-storm. Document findings in `.claude/skills/project-knowledge/references/patterns.md` under a new "Virtual Threads" section: list pinning sites (BCrypt, Lettuce internal locks, anything else discovered), explain why each is acceptable, note SB4/JDK24 future-fix. Result: AC7 satisfied; pinning is observed, named, and bounded.
- **Skill:** documentation-writing
- **Reviewers:** code-reviewer
- **Verify-smoke:** `./gradlew bootRun -PappArgs="-Djdk.tracePinnedThreads=full"` + manual log inspection
- **Files to modify:** `.claude/skills/project-knowledge/references/patterns.md`
- **Files to read:** Wave 2 application boot logs

#### Task 12: New acceptance integration tests AC16 + AC17 + AC18 + bean type assertion (TC5)
- **Description:** Add four MockMvc integration tests with strengthened assertions per validator feedback:
  - **AC16 session continuity:** login as user X (two sessions), call `change-password` (invalidates non-current), assert `principal`-lookup confirms termination of session B while session A continues. ALSO: load a hand-crafted BSON fixture document into `sessions` representing the pre-flip `ReactiveMongoSessionRepository` byte-shape (per R5) — assert the post-flip `MongoIndexedSessionRepository` reads the `principal` field correctly. This catches the pre-flip → post-flip continuity case that R5 names.
  - **AC17 validation 400 shape:** POST `/api/auth/register` with empty email — assert response body has JSON `code: null` AND `message` field matches the joined-error format `"<field>: <defaultMessage>"` (pin the joiner shape that `useApiError` may parse).
  - **AC18 remember-me cookie:** `rememberMe=true` produces `Set-Cookie: SESSION=...; Max-Age=2592000; HttpOnly; Secure; SameSite=Strict` (assert all attributes — not just Max-Age); `rememberMe=false` omits `Max-Age`. ALSO: assert XSRF-TOKEN cookie is co-emitted alongside SESSION on the same login response (closes TC11 regression risk).
  - **TC5 bean assertion:** context-load test asserts the registered `CookieSerializer` bean is an instance of `RememberMeCookieSerializer` (not Spring Session's auto-configured default).
  Result: previously-manual checks backed by automation; R5 + R4 + AC12 lifted from manual.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** new methods inside `backend/src/test/java/com/botfunnel/auth/AuthControllerIT.java`, `backend/src/test/java/com/botfunnel/profile/ProfileControllerIT.java`, new methods inside `backend/src/test/java/com/botfunnel/security/SecurityConfigTest.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/security/RememberMeCookieSerializer.java`, `backend/src/main/java/com/botfunnel/common/GlobalErrorHandler.java`

#### Task 13: Documentation cleanup — patterns.md, architecture.md, deployment.md
- **Description:** Runs sequentially after Task 11 (which has already appended the "Virtual Threads" pinning section to `patterns.md`). Reads Task 11's section as input — does NOT modify it. Updates the rest of `patterns.md` (Spring Security WebFlux → MVC, mock-triple, WebTestClient → MockMvc, Reactor retry/fire-and-forget → synchronous, `ProjectService.requireOwned` non-reactive signature note), `architecture.md` (tech-stack table + dependency list), and `deployment.md` (Production Infrastructure container note). No parallel write to `patterns.md`. Result: AC14 satisfied; project-knowledge consistent with the migrated stack.
- **Skill:** documentation-writing
- **Reviewers:** code-reviewer
- **Verify-smoke:** `grep -E "WebFlux|ServerHttpSecurity|@EnableWebFluxSecurity|WebTestClient|ReactiveRedisConnectionFactory|reactivestreams.client" .claude/skills/project-knowledge/references/{patterns,architecture,deployment}.md` → 0 matches
- **Files to modify:** `.claude/skills/project-knowledge/references/patterns.md`, `.claude/skills/project-knowledge/references/architecture.md`, `.claude/skills/project-knowledge/references/deployment.md`
- **Files to read:** the updated user-spec.md, the updated tech-spec.md, the "Virtual Threads" section written by Task 11 (input to verify cross-references)

### Audit Wave

#### Task 14: Code Audit
- **Description:** Full-feature code quality audit. Read all source files created/modified across Phase A + Phase B + Phase C. Review for: cross-component duplication after `HttpRequestUtils` extraction; correct usage of `SecurityContextHolder` (servlet) vs `ReactiveSecurityContextHolder` (no leakage); correct `HttpSession` vs `WebSession` separation; `MongoTemplate` consistency vs hidden reactive references; `RestClient` construction consistency between `TelegramApiClient` and `TelegramSender`; control-flow clarity of imperative rewrites of former Reactor chains in `TelegramWebhookController.receive(...)` and `ProcessTelegramUpdateJob.handle(...)`; correct error mapping in `GlobalErrorHandler`. Write audit report.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 15: Security Audit
- **Description:** Full-feature security audit. Read all source files created/modified. OWASP Top 10 across all components. Specific focus areas: (1) CSRF — verify new MVC `CsrfFilter` setup writes XSRF-TOKEN cookie correctly and CSRF disable is properly AND-scoped to webhook path; (2) session fixation — verify `HttpSession.invalidate() + request.getSession(true)` is performed on successful login; (3) timing-attack guard — verify BCrypt dummy-hash path preserved in `AuthService.authenticate`; (4) rate-limit fail-open semantics under VT; (5) token scrubber sites preserved on every log site in Telegram-API path; (6) at-rest encryption — `TokenEncryptor` unchanged; (7) `RememberMeCookieSerializer` does not leak token via cookie attribute. Write audit report.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 16: Test Audit
- **Description:** Full-feature test quality audit. Read all test files created/modified. Verify: (1) every flipped service has parity-or-better coverage vs pre-migration; (2) `StepVerifier` rewrites correctly map error-expectations (`.expectError(X)` → `assertThatThrownBy.isInstanceOf(X)`); (3) `MockMvc` ITs use `csrf()` mutator where state-changing; (4) `ConcurrencyTestUtils.parallelInvoke` preserves race-test invariants (status pair, persisted-row count); (5) AC2 P99 test uses `TestRestTemplate` + RANDOM_PORT (not MockMvc); (6) AC16/AC17/AC18 tests assert acceptance criteria literally (no flaky proxies). Write audit report.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 17: Pre-deploy QA
- **Description:** Acceptance testing. Run `./gradlew test` and `./gradlew test -PrunSlow=true` — both must pass. Run all 4 grep gates from user-spec "Как проверить" + 1 doc grep (Task 13 verify-smoke). Verify AC1-AC6, AC14, AC15, TC1-TC9 from this tech-spec. Verify AC16/AC17/AC18 covered by Task 12 tests. Run AC7 manually via `-Djdk.tracePinnedThreads=full` boot probe. Walk AC11-AC13 manual UI smoke flows on `localhost:3000`. Confirm AC8-AC10 (Telegram smoke runbooks) are operator-runnable (do not require automation). Output: JSON pass/fail report per AC.
- **Skill:** pre-deploy-qa
- **Reviewers:** none

No Deploy or Post-deploy tasks — `deployment.md` documents production hosting as TBD; the migration delivers value to local-dev + staging-smoke workflows only.
