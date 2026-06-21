# Code Research — 06-bot-connection

Stack confirmed: Java 21 + Spring Boot 3.5.0 + Spring WebFlux + reactive MongoDB + Redis + Nuxt 4 (Vue 3 + Pinia + vee-validate + zod). Backend group: `com.botfunnel`. Frontend: pnpm workspace at `frontend/`.

## 1. Backend `bot` module — greenfield

`backend/src/main/java/com/botfunnel/bot/` — **does not exist**. Current module folders under `com/botfunnel/`:

- `admin/`, `auth/`, `common/`, `email/`, `events/`, `jobs/`, `profile/`, `project/`, `security/`, `user/`
- Plus `BotFunnelApplication.java` (vanilla `@SpringBootApplication`, no scan customization) and `HealthController.java`.

No existing classes match `*Bot*` (`find … -path "*Bot*"` returns only `BotFunnelApplication.java`). No `bot` keys in `i18n/locales/uk.json` or `en.json` either (uk.json line 158 has `profile.deleteAccount.modalBots: "ботів"` — that is the only `bot` word in the whole locale tree).

Conclusion: implementation creates the `com.botfunnel.bot` package from scratch alongside existing modules. Follow the `project/` module layout: `Bot.java` (`@Document`), `BotRepository.java` (`ReactiveMongoRepository`), `BotService.java`, `BotController.java`, `dto/` package, `validation/` package.

## 2. ProjectService.requireOwned — the ownership guard

File: `backend/src/main/java/com/botfunnel/project/ProjectService.java:50-64`.

```java
public Mono<Project> requireOwned(String ownerId, String projectId, boolean includeSoftDeleted)
```

Package: `com.botfunnel.project`. Return type: `Mono<Project>`. The method:

1. Calls `projectRepository.findById(projectId)`.
2. Maps `IllegalArgumentException` (raised by Mongo when `projectId` is not a valid ObjectId) to `AppException.notFound("Project not found")`.
3. `switchIfEmpty` → same 404.
4. `ownerId.equals(project.getOwnerId())` mismatch → same 404 (anti-enumeration, Decision 2 — line 47-49 comment).
5. `deletedAt != null && !includeSoftDeleted` → same 404.

Callers (all in `project/`):
- `ProjectController.java:60` (GET single project)
- `ProjectController.java:72` (PATCH)
- `ProjectController.java:82` (DELETE / soft delete)
- `ProjectController.java:92` (POST /restore)
- `ProjectService.java:102, 141, 154` (internal — update / softDelete / restore)

No external module currently calls `requireOwned` — `BotController` will be the first cross-module consumer. The intended pattern is the one in `ProjectController` lines 57-62: extract user id, then `projectService.requireOwned(ownerId, projectId, false)` before any bot-scoped logic. Inject `ProjectService` into `BotService` (or pass through controller). Both classes are in the same Spring context, no cyclic import risk because `BotService` does not need to be referenced back from `ProjectService`.

## 3. AppException + GlobalErrorHandler

`backend/src/main/java/com/botfunnel/common/AppException.java`:

```java
public class AppException extends RuntimeException {
    private final HttpStatus status;
    private final String code;     // optional machine-readable code, e.g. "project_limit_reached"
}
```

Factory methods (lines 16-46):
- `badRequest(String message)` → 400, code=null
- `unauthorized(String message)` → 401, code=null
- `forbidden(String message)` → 403, code=null
- `notFound(String message)` → 404, code=null
- `conflict(String message)` → 409, code=null
- `conflict(String code, String message)` → 409, with code
- `unprocessableEntity(String code, String message)` → 422, with code
- `tooManyRequests(String message)` → 429, code=null

Plus the public constructor `new AppException(HttpStatus, code, message)` (used directly twice in `AuthService` for custom statuses like `TOKEN_EXPIRED`).

`ErrorResponse` (`common/ErrorResponse.java`): `record ErrorResponse(String message, String code)`. Wire format: `{"message": "...", "code": "..."}`. Both fields are always present; `code` may be `null`.

`GlobalErrorHandler` (`common/GlobalErrorHandler.java`):
- `@RestControllerAdvice` — global to all `@RestController`.
- Maps `AppException` → status + body (line 21-24).
- Maps `WebExchangeBindException` (Jakarta validation) → 400 with comma-joined field errors (line 27-35).
- Maps `ResponseStatusException` → echoes status (line 38-41).
- Catch-all `Throwable` → 500 + `"Internal server error"` (line 43-48).

Frontend consumer: `frontend/composables/useApiError.ts`. Resolution order (line 19-34):
1. `errors.{contextKey}.{status}` — e.g. `errors.bot.connect.401`.
2. `errors.{contextKey}.generic` — e.g. `errors.bot.connect.generic`.
3. `errors.generic` — global fallback.

The `code` field from the error body is NOT consulted by `useApiError` — it lookups purely by HTTP status. (For the bot feature, this means returning status-distinct 401/422/409 from the backend, since the frontend will branch by status only.) See `useApiError.ts:7-14` for the `statusCode | status | response.status` triple — `ofetch` rejected promises always expose at least one of those.

## 4. EventService.logEvent

File: `backend/src/main/java/com/botfunnel/events/EventService.java`.

```java
public void logEvent(String userId, String eventType, String ipAddress, String userAgent,
                     Map<String, Object> metadata)
```

- `Event(userId, eventType, ipAddress, userAgent, metadata, Instant.now())` is persisted.
- Fire-and-forget via `.subscribe(null, err -> log.error(...))` (line 27). Never throws.
- Also exists: `logEventBlocking(...)` returning `Mono<Event>` (line 34) for ordering-sensitive callers (`ProjectHardDeleteJob` uses this).

`Event` document (`events/Event.java`):
```java
@Document(collection = "events")
@CompoundIndex(name = "userId_createdAt_desc", def = "{'userId': 1, 'createdAt': -1}")
class Event { String id; String userId; String eventType; String ipAddress;
              String userAgent; Map<String, Object> metadata; Instant createdAt; }
```

Existing event types — compiled from `AuthService` constants (lines 69-73) and `ProjectService` constants (lines 19-23):

- Auth: `login_success`, `login_failed` (metadata.reason = `brute_force | wrong_password | user_not_found | blocked | deleted`), `email_verified`, `password_reset_requested`, `password_changed`.
- Project: `project_created`, `project_updated`, `project_renamed`, `project_soft_deleted`, `project_restored`.
- Profile: see `ProfileService.java:102, 143` for `profile_name_updated` / `account_deleted` style events.

For 06-bot-connection, emit: `bot_connected`, `bot_disconnected`, `bot_reconnected`, `bot_token_invalidated`, `bot_test_message_sent`. Place these as `private static final String EVENT_BOT_*` constants in `BotService`, mirroring the `ProjectService` convention (lines 19-23). Metadata recommendation (PII-safe, mirroring AuthService comments about not storing email in metadata): include only `projectId` and `telegramBotId` (numeric, public) and `botUsername` (public). Never put the raw or encrypted token into metadata.

Calling pattern: pass `ip` + `userAgent` from controller via `ServerWebExchange` (see `ProjectController.java:138-148` for the `extractIp` + `capUserAgent` helpers — copy verbatim; they cap UA at 500 chars and parse the leftmost `X-Forwarded-For` entry).

## 5. WebClient — none configured, set up fresh

`grep -r "WebClient" backend/src/main/java/` returns **zero hits** (only test code uses `MockServerWebExchange` from `spring-test`, not WebClient). The only external IO in production code is `EmailService` (`backend/src/main/java/com/botfunnel/email/EmailService.java`) which uses `JavaMailSender` (SMTP via `spring-boot-starter-mail`, line 28 of `build.gradle`), not HTTP.

`spring-boot-starter-webflux` IS on the classpath (`build.gradle:26`), so `WebClient.Builder` autoconfiguration is available — but no `@Bean` for `WebClient` yet.

Plan: introduce `com.botfunnel.bot.TelegramApiClient` (`@Component`) that holds a `WebClient` field built once in the constructor:

```java
WebClient.builder()
    .baseUrl("https://api.telegram.org")
    .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
    .build();
```

Per Context7 `/spring-projects/spring-framework`, the canonical reactive call shape is `.exchangeToMono(response -> …)` or `.retrieve().onStatus(…).bodyToMono(…)`. Use `.retrieve()` with `.onStatus(HttpStatusCode::is4xxClientError, …)` to map a Telegram 401 (`{"ok":false,"error_code":401,"description":"Unauthorized"}`) and a 400 (malformed token) to `AppException.unprocessableEntity("invalid_bot_token", …)`. Response shape (Telegram convention — from `/websites/core_telegram_bots_api`):

```
{ "ok": true, "result": { "id": 12345678, "is_bot": true, "first_name": "...", "username": "..." } }
{ "ok": false, "error_code": 401, "description": "Unauthorized" }
```

Configure timeouts via Reactor Netty `HttpClient.create().responseTimeout(Duration.ofSeconds(N))` injected into `WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient))`. Recommend 10s connect+read timeout for `getMe`/`setWebhook`/`deleteWebhook` calls — fast-fail on a hung Telegram endpoint.

## 6. MongoDB indexing — annotation-driven with auto-create in dev

Pattern: indexes are declared via `@Indexed`, `@CompoundIndex`, `@CompoundIndexes` directly on the `@Document` class.

Concrete examples:
- `Project.java:14-19` — `@CompoundIndexes({@CompoundIndex(name=…, def=…)})` plus `@Indexed` on `ownerId`. Both compound indexes are non-unique; uniqueness is service-enforced.
- `User.java:18` — `@Indexed(unique = true)` on `email` (the only unique index in the codebase today).
- `Event.java:13` — single `@CompoundIndex`.

Auto-creation is **globally ON**: `backend/src/main/resources/application.properties:6` sets `spring.data.mongodb.auto-index-creation=true`. Comments in `Project.java:11`, `User.java:10-11`, and `Event.java:11-12` flag that production deployments should switch this to `false` and manage indexes manually — but the codebase does not yet have a migration framework. For dev + integration tests, indexes are materialized at startup from annotations.

For the bot collection, the uniqueness rule "same Telegram bot cannot be connected to two projects at once" maps to a **partial unique index** on `telegramBotId` filtered to `status = "connected"`. Spring Data Mongo supports this through `@Indexed(unique=true, partialFilter="{ status: \"connected\" }")` (available since spring-data-mongodb 3.x — Boot 3.5.0 pulls in spring-data-mongodb 4.x). Alternative: a plain `@Indexed(unique=true)` on `telegramBotId` combined with setting that field to `null` on disconnect (Mongo allows multiple nulls in a non-partial unique index ONLY in some driver versions — verify by integration test). Recommended path: partial unique index for clarity, plus a non-unique `@Indexed` on `projectId` for the lookup `findByProjectId(...)`.

## 7. Redis brute-force pattern

Implementation: `backend/src/main/java/com/botfunnel/auth/AuthService.java`.

Key shapes (lines 626-632):
```java
private static String bruteEmailKey(String email) { return "brute:fail:" + email; }
private static String bruteIpKey(String ip) { return "brute:fail:ip:" + ip; }
```

TTL & thresholds (lines 51-53):
```java
private static final int EMAIL_THRESHOLD = 5;
private static final int IP_THRESHOLD = 20;
private static final Duration BRUTE_TTL = Duration.ofSeconds(900);   // 15 min
```

Core building blocks:
- `checkBruteForce(...)` (line 490-508) — reads both counters via `Mono.zip`, errors with 429 if either threshold tripped, fail-open on Redis error (line 504-507).
- `registerFailure(...)` (line 546-552) — `Mono.when(incrementWithTtl(emailKey), incrementWithTtl(ipKey))`.
- `incrementWithTtl(...)` (line 554-565) — `INCR`, then `EXPIRE` only when count == 1 (window-stable; race acknowledged on lines 557-559).
- `resetBruteCounters(...)` (line 617-624) — `DEL` on success.
- `currentCount(...)` (line 510-514) — `GET` → `Long.parseLong` with `defaultIfEmpty(0L)`.

Same shape applies for `brute:bot-connect:{userId}` with threshold 10 / TTL 15min:
- Key prefix should be `brute:bot-connect:` (per-user, not per-email, since the actor is an authenticated user).
- Reuse the increment-with-once-set-TTL helper (consider extracting to a `common/RateLimitService` later — out of scope for this feature, just copy the method).
- Inject `ReactiveRedisTemplate<String, String>` exactly as `AuthService` does (line 78, 90, 101).

Note: lines 462-472 of `AuthService` show the registration rate-limit pattern (`register:rate:ip:{ip}`, threshold 10/60s) — also relevant if we end up wanting an IP-scoped second factor on bot-connect.

## 8. Encryption utilities — none, build a thin AES-GCM helper

`grep -rn "AES\|Cipher\|encrypt" backend/src/main/java/` returns **zero hits**. The only crypto in production is BCrypt (`security/SecurityConfig.java:94` — `BCryptPasswordEncoder(12)`) and HMAC-like token hashing in `auth/TokenService.java` (used for email-verification / password-reset token hashes — file is 1.1K; not AES, just SHA-style hashing per the field names in `User.java:31-34`).

Spring Boot 3.5.0 does NOT ship an AES utility. There is `spring-security-crypto` with `Encryptors.stronger(password, salt)` (PBKDF2 + AES-256 CBC) and `BytesEncryptor` interfaces — but these use a password+salt scheme, not a raw 32-byte key with AES-GCM AEAD. For AES-256-GCM with a configured 32-byte key we use JDK's `javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")` directly.

Implementation plan: add `backend/src/main/java/com/botfunnel/common/crypto/TokenEncryptor.java` (`@Component`) with:

```java
record Encrypted(String iv, String ciphertext) {}
Encrypted encrypt(String plaintext);
String decrypt(String iv, String ciphertext);
```

Construct with `@Value("${app.bot.token-encryption-key}") String hexKey`. Decode hex to `byte[32]` once in the constructor; fail fast on wrong length. Use `SecureRandom` for a fresh 12-byte IV per encryption (AES-GCM recommended IV size). Store `{iv, ciphertext}` Base64-encoded as two String fields on the `Bot` document, separate from each other so corruption of one is detectable. Tag length: 128 bits (default for `GCMParameterSpec(128, iv)`).

No new dependency required — `javax.crypto` is JDK. The `org.bouncycastle:bcprov-jdk*` line is NOT in `build.gradle:21-39`; do not introduce it.

## 9. currentUserId() helper — copy-paste, no shared util yet

The Mono<String> currentUserId() helper is duplicated in two places:

- `backend/src/main/java/com/botfunnel/project/ProjectController.java:125-131`
- `backend/src/main/java/com/botfunnel/profile/ProfileController.java:75-81`

Both bodies are identical:
```java
private static Mono<String> currentUserId() {
    return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .filter(a -> a != null && a.isAuthenticated() && a.getPrincipal() instanceof AppUserDetails)
            .map(a -> ((AppUserDetails) a.getPrincipal()).id())
            .switchIfEmpty(Mono.error(AppException.unauthorized("Not authenticated")));
}
```

The comment in `ProjectController.java:124` is explicit: "Copied verbatim from ProfileController. A shared util is deferred (no other consumer yet)." With `BotController` becoming the third consumer, extracting to `com.botfunnel.common.security.CurrentUserResolver` would be justified — but the task spec does NOT mandate it. Safe default for this feature: copy the helper one more time into `BotController`, mirroring the established convention. Defer extraction.

Principal contract: `AppUserDetails` is a `record(String id, String email, String name, String status)` — `AppUserDetails.java:15`. `id` is the Mongo ObjectId string of the User document.

## 10. Frontend Settings page

Path: `frontend/pages/projects/[projectId]/settings.vue` — **exists** as a single file (not a folder). Implements General section + Danger Zone (delete project).

Routing model used (`frontend/pages/`):
```
projects/
  [projectId]/
    settings.vue
  index.vue
  new.vue
```

There is no `projects/[projectId]/settings/` directory. Nuxt 4 file-based routing: turning `settings.vue` into a folder would replace the existing route. Two viable strategies:

- **Option A (recommended):** convert `pages/projects/[projectId]/settings.vue` → `pages/projects/[projectId]/settings/index.vue` (general + danger), then add `pages/projects/[projectId]/settings/bot.vue`. Sidebar nav in `layouts/default.vue:51-57` already points to `/projects/{id}/settings` — that link continues to work because `index.vue` covers the bare path. Add a second sidebar link `/projects/{id}/settings/bot` (with `v-if="projectsStore.currentProject"`) and an in-page sub-nav tab strip.
- **Option B:** keep `settings.vue` and extend it with a `<section data-test="settings-bot">` block (no route change). Simpler but quickly outgrows itself when subsequent feature 06b adds webhook health UI.

User-spec says "Settings → Bot" suggesting a sub-section. Option A scales better; Option B is faster. Decision deferred to tech-spec.

Existing `settings.vue` patterns worth reusing for `/settings/bot`:
- Mount-time fetch of project via `useApi()<Project>('/api/v1/projects/${projectId}')` (line 51) — same guard against deleted/foreign project.
- `useProjectsStore()` + `localePath()` + `useI18n()` + `useApiError()` composables.
- Data-test attribute convention: `settings-*-input`, `settings-*-error`, `settings-saved` (lines 213-289).
- Reactive store-then-fetch pattern: rely on the store for already-loaded data; fall back to a GET on mount.

Layouts and Settings nav: `frontend/layouts/default.vue` (the only authenticated layout) uses `definePageMeta({ layout: 'default' })` per page. Bot page must declare the same.

## 11. Frontend i18n namespaces

`frontend/i18n/locales/uk.json` top-level keys (line 2-289):
- `brand`, `common`, `layout`, `timezone`, `validation`, `auth`, `dashboard`, `profile`, `projects`, `errors`.

`errors` namespace tree (line 209-289) currently covers: `generic`, `projects.*`, `login.*`, `register.*`, `forgotPassword.*`, `resetPassword.*`, `verifyEmail.*`, `profile.*`. No `bot.*` subtree.

Add to BOTH `uk.json` and `en.json`:

- `bot.title`, `bot.notConnected.*`, `bot.connect.*` (label, submit, submitting, tokenLabel, tokenPlaceholder), `bot.connected.*` (botName, username, lastConnectedAt, testMessage.button, disconnect.open / modalTitle / confirm / cancel / deleting), `bot.testMessage.chatLabel, sent, sendFailed`.

- `errors.bot.connect.{400, 401, 409, 422, 429, generic}` (mapping: 401 = bad token, 409 = bot already connected to another project, 422 = unprocessable / brute-force payload limit, 429 = brute-force trigger).
- `errors.bot.disconnect.{404, generic}`.
- `errors.bot.testMessage.{400, 401, 404, 429, generic}`.

Locale-parity tooling: `frontend/scripts/check-locales.mjs` runs in `prebuild` (per `package.json:7`) — any missing key in one language vs the other will break the build. Keep both files in lock-step.

## 12. Pinia store patterns

Reference store: `frontend/stores/projects.ts` — setup-store style (`defineStore('projects', () => { ... })`), 143 lines. Conventions:

- State refs at top, computed below, actions last (line 38-128).
- `useApi()<T>(...)` for every HTTP call.
- localStorage hydration: gated behind `isClientGuard()` (line 9-12) — the test-only `__setClientGuardForTests` indirection is used to exercise SSR branches under Vitest. Key constant exported: `export const LOCAL_STORAGE_KEY = 'bot-funnel.currentProjectId'` (line 4).
- Single in-flight guard: `let inFlight: Promise<void> | null = null` (line 46, used in `fetchAll`) prevents duplicate concurrent requests.
- `pendingBannerKey` (line 45) is exposed as a cross-cutting status channel for 404 banners — read by `composables/useApi.ts:75`.
- Return object explicitly lists everything reactive — typical setup-store contract.

Auth store at `frontend/stores/auth.ts` — even shorter (40 lines), uses `useState<User|null>('auth-user', ...)` for SSR-friendly state (line 5). Note: `useState` (Nuxt) vs `ref` — the projects store uses plain `ref`. Either is acceptable inside Pinia.

For `stores/bot.ts`, keyed by current `projectId`:

- Simplest model: store the bot DTO for `currentProjectId` only, refetch whenever `currentProjectId` changes (watch on `useProjectsStore().currentProjectId`). No localStorage persistence — bot connection state is server-authoritative and short-lived per session.
- Alternative: `bots: Map<projectId, Bot>` for cross-tab consistency. Heavier; only needed if multiple projects open in tabs becomes a real flow. Recommend single-key store for MVP.

Methods to expose: `fetch(projectId)`, `connect(projectId, token)`, `disconnect(projectId)`, `sendTestMessage(projectId, chatId)`. Mirror `projects.ts:93-108` shape: try/catch in the page using `useApiError`, no `try/catch` inside the store action.

## 13. CSRF config for /api/v1/...

File: `backend/src/main/java/com/botfunnel/security/SecurityConfig.java`.

Setup:
- `csrf()` uses `CookieServerCsrfTokenRepository.withHttpOnlyFalse()` (line 56) — token rides in the `XSRF-TOKEN` cookie, SPA echoes via `X-XSRF-TOKEN`.
- Plain (non-XOR) attribute handler (line 64) — crucial for the cookie/header round-trip.
- `csrfCookieMaterializer()` (line 80-90) is a `WebFilter` added AFTER the CSRF filter so the cookie is actually written every request.

AuthorizeExchange (line 68-73):
```java
.pathMatchers("/health").permitAll()
.pathMatchers("/api/auth/**").permitAll()
.pathMatchers("/api/**").authenticated()
.anyExchange().authenticated()
```

So `/api/v1/projects/{projectId}/bot` and `/api/v1/projects/{projectId}/bot/*` are covered by `/api/**` → `authenticated()` and CSRF-protected by default. No SecurityConfig edits needed for this feature.

CORS (line 97-107): `allowedOrigins` is just `${app.url:http://localhost:3000}`. `X-XSRF-TOKEN` is in `allowedHeaders`. Methods: `GET, POST, PATCH, DELETE, OPTIONS`. Bot endpoints fit the existing surface — no config changes.

(Note: `/webhooks/telegram/{projectId}` for feature 06b will need a CSRF exemption AND a `permitAll()` rule. Not required for THIS feature.)

Frontend CSRF wiring: `frontend/composables/useApi.ts:91-107` — `$fetch` instance with `credentials: 'include'` and an `onRequest` interceptor that reads `XSRF-TOKEN` from `document.cookie` (or the forwarded SSR Cookie header) and sets `X-XSRF-TOKEN` for every non-GET request (line 99-103). The bot endpoints work transparently through `useApi()` — no extra plumbing.

## 14. Env vars list

`/.env.example` (32 lines). Current vars:
- `MONGODB_URI`, `REDIS_URL`.
- `SESSION_TTL_DEFAULT_HOURS`, `SESSION_TTL_REMEMBER_ME_DAYS`, `SESSION_COOKIE_SECURE`, `SESSION_COOKIE_SAME_SITE`.
- `SUPER_ADMIN_EMAIL`, `SUPER_ADMIN_PASSWORD`.
- `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`, `SUPPORT_EMAIL`.
- `APP_URL=http://localhost:3000` (line 28) — **already present, reuse for the webhook URL template**.
- `PROJECTS_MAX_PER_USER=5`.

Backend mapping: `backend/src/main/resources/application.properties:27` — `app.url=${APP_URL:http://localhost:3000}` is already injected. New properties for this feature:

Add to `.env.example`:
```
# Bot token encryption (AES-256-GCM). 32 bytes / 64 hex chars. NEVER commit a real value.
BOT_TOKEN_ENCRYPTION_KEY=
```

Add to `backend/src/main/resources/application.properties`:
```
app.bot.token-encryption-key=${BOT_TOKEN_ENCRYPTION_KEY:}
```

Fail-fast on startup if the key is blank or the wrong byte length — wire this into the `TokenEncryptor` constructor.

`.gitignore` already covers `.env` (per CLAUDE.md instruction).

## 15. Test infrastructure for Telegram mock

`backend/build.gradle:21-39` test dependencies:
- `spring-boot-starter-test` (JUnit 5, AssertJ, Mockito).
- `reactor-test` (`StepVerifier`).
- `spring-security-test` (provides `SecurityMockServerConfigurers.csrf()`).
- `testcontainers:mongodb`, `testcontainers:junit-jupiter`.
- `ch.martinelli.oss:testcontainers-mailpit:1.3.1` (Mailpit SMTP for email tests).

No WireMock, no MockServer, no okhttp3.mockwebserver, no Spring Cloud Contract. The only "mock server" references in code are Spring's `MockServerHttpRequest` / `MockServerWebExchange` (unit-level WebFlux test helpers, not HTTP servers).

Integration test base: `backend/src/test/java/com/botfunnel/AbstractIntegrationTest.java` (94 lines). Pattern:

- `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@ActiveProfiles("test")`, `@Testcontainers`.
- Singleton MongoDBContainer, GenericContainer<Redis>, MailpitContainer (lines 50-66, started in a static block).
- `@DynamicPropertySource` wires `spring.data.mongodb.uri` and `spring.data.redis.url` (lines 88-93).
- `WebTestClient` is re-bound to the ApplicationContext in `@BeforeEach` so `SecurityMockServerConfigurers.csrf()` mutator works (lines 79-86).
- Auth: `@WithMockAppUser(userId = "test-user-fixed-id")` (`backend/src/test/java/com/botfunnel/profile/WithMockAppUser.java`) — paired with `WithMockAppUserSecurityContextFactory.java` (10 lines, constructs `AppUserDetails` principal in the test SecurityContext).
- Example IT: `backend/src/test/java/com/botfunnel/project/ProjectControllerIT.java` (32 KB) — full pattern of `webTestClient.mutateWith(csrf()).post()...exchange()...expectStatus()` plus `Awaitility` for async event-log assertions (`awaitEvent(...)`, `findEvent(...)` helpers at lines 85-93).
- Slice tests (e.g. `ProjectControllerSliceTest.java`) use `@WebFluxTest` + mocked service.

For Telegram-API integration tests we need to mock outbound HTTP. Recommended add: `org.springframework.cloud:spring-cloud-contract-wiremock` (or directly `com.github.tomakehurst:wiremock-jre8-standalone:3.x`). Per `build.gradle` style — declare as `testImplementation`. Pattern in IT: start a WireMock server on a random port, register a stub for `POST /bot{token}/getMe`, override the WebClient base URL via `@DynamicPropertySource` (e.g. `app.telegram.base-url=http://localhost:${wiremock.port}`). For unit tests (`BotServiceTest`), prefer testing the `TelegramApiClient` in isolation by injecting a `WebClient` built from `MockWebServer` (from `com.squareup.okhttp3:mockwebserver`) — lighter than spinning Spring context.

Decision deferred to tech-spec which library to add. WireMock is the more idiomatic Spring choice and integrates with `@AutoConfigureWireMock`.

Sample test signatures (current style):
```java
@Test
@WithMockAppUser(userId = USER_ID)
void postProject_validBody_returns201AndEmitsProjectCreatedEvent() { ... }
```
(from `ProjectControllerIT.java:97-100`). Bot tests should match this naming convention.

## 16. JobRunr

Present and configured:
- Gradle: `org.jobrunr:jobrunr-spring-boot-3-starter:7.3.2` (`build.gradle:30`).
- Config: `backend/src/main/java/com/botfunnel/jobs/JobRunrMongoConfig.java` provides a sync `MongoClient` bean because JobRunr's storage provider requires the sync driver (lines 14-21 comment).
- Application properties (line 33-34): `org.jobrunr.background-job-server.enabled=true`, dashboard disabled.
- Existing jobs: `jobs/HardDeleteJob.java` (purges deleted users 30d+), `jobs/ProjectHardDeleteJob.java` (purges soft-deleted projects 7d+).
- Test-side: `JobRunrInMemoryConfig` (lines 13-20) provides an InMemoryStorageProvider for tests; background server is disabled in `application-test.properties:9`.

**For 06-bot-connection: do NOT add a new recurring job.** Webhook re-registration (auto-recovery if Telegram drops the webhook) is feature 06b/06c. This feature is synchronous request/response only. Avoid introducing JobRunr dependencies into `BotService`.

## 17. PROJECTS_MAX_PER_USER cap pattern

Reference: `backend/src/main/java/com/botfunnel/project/ProjectService.java:36, 40, 73-78`.

```java
private final int maxPerUser;
public ProjectService(..., @Value("${app.projects.max-per-user:5}") int maxPerUser) { ... }

// inside create():
return projectRepository.countByOwnerIdAndDeletedAtIsNull(ownerId)
        .flatMap(count -> {
            if (count >= maxPerUser) {
                return Mono.<Project>error(AppException.unprocessableEntity(
                        CODE_PROJECT_LIMIT_REACHED, MESSAGE_LIMIT_REACHED));
            }
            ...
        });
```

`.env.example:31`: `PROJECTS_MAX_PER_USER=5`. `application.properties:30`: `app.projects.max-per-user=${PROJECTS_MAX_PER_USER:5}`.

For 06-bot-connection MVP, "1 bot per project" is a hardcoded rule, not a configurable cap. Enforcement is structural: the `bots` collection has one document per `projectId` (with `status: connected | disconnected`), or no document at all. The "Disconnect preserves the bot record for audit" rule from the user-spec implies the document stays in `disconnected` status, and `connect` while a `connected` bot exists for the same projectId returns `409`. Implementation: in `BotService.connect(...)`, query `findByProjectIdAndStatus(projectId, "connected")` first; if a row exists, return `AppException.conflict("bot_already_connected", ...)`.

No need to introduce a configurable cap for this feature. Document the rule in tech-spec under "Decisions" so future cross-project bot sharing (12-nice-to-have) doesn't blindside us.

---

## Files most relevant to this feature

Files to read before writing code (absolute paths):

- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/project/ProjectService.java` — copy `requireOwned` pattern, event-emit pattern.
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/project/ProjectController.java` — mirror controller boilerplate (currentUserId, extractIp, capUserAgent, csrf-protected endpoints).
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/project/Project.java` — `@Document` + `@CompoundIndex` style.
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/auth/AuthService.java` — brute-force counter primitives (`bruteEmailKey/bruteIpKey`, `checkBruteForce`, `incrementWithTtl`, `resetBruteCounters`).
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/common/AppException.java` — error factories.
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/common/GlobalErrorHandler.java` — global error mapping.
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/events/EventService.java` — `logEvent` signature.
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/security/SecurityConfig.java` — confirm no SecurityConfig changes needed.
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/resources/application.properties` — add `app.bot.token-encryption-key`.
- `/Users/pavlokorolov/IdeaProjects/simple-sender/.env.example` — add `BOT_TOKEN_ENCRYPTION_KEY`.
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/test/java/com/botfunnel/AbstractIntegrationTest.java` — IT base class.
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/test/java/com/botfunnel/profile/WithMockAppUser.java` — auth annotation for IT.
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/test/java/com/botfunnel/project/ProjectControllerIT.java` — IT pattern reference.
- `/Users/pavlokorolov/IdeaProjects/simple-sender/frontend/pages/projects/[projectId]/settings.vue` — existing settings page (decide Option A folder split vs. Option B section extension).
- `/Users/pavlokorolov/IdeaProjects/simple-sender/frontend/stores/projects.ts` — Pinia setup-store style.
- `/Users/pavlokorolov/IdeaProjects/simple-sender/frontend/composables/useApi.ts` — CSRF + SSR cookie forwarding (no change needed; just reuse).
- `/Users/pavlokorolov/IdeaProjects/simple-sender/frontend/composables/useApiError.ts` — error key resolution.
- `/Users/pavlokorolov/IdeaProjects/simple-sender/frontend/i18n/locales/uk.json` and `/Users/pavlokorolov/IdeaProjects/simple-sender/frontend/i18n/locales/en.json` — add `bot.*` and `errors.bot.*`.
- `/Users/pavlokorolov/IdeaProjects/simple-sender/frontend/layouts/default.vue` — sidebar nav to extend.
- `/Users/pavlokorolov/IdeaProjects/simple-sender/frontend/types/project.ts` — model for new `types/bot.ts`.

## Potential problems / things to watch

- **Auto-index-creation in prod**: comments in `Project.java`, `User.java`, `Event.java` flag that `spring.data.mongodb.auto-index-creation=true` is a dev convenience. There is no migration tool. The partial unique index on `bots.telegramBotId` (status=connected) MUST be created with auto-index ON in current state. Production switch-off is an open project-level risk, not specific to this feature.
- **No WebClient bean in DI graph**: introduces a new external IO failure mode. Timeouts and retry semantics for `getMe` / `setWebhook` / `deleteWebhook` need explicit configuration — Reactor Netty default is no read timeout. Document in tech-spec.
- **Disconnect with Telegram unreachable**: tech-spec must decide whether `deleteWebhook` failure aborts the local disconnect or proceeds (orphan webhook on Telegram side). User-spec hint: "Disconnect calls deleteWebhook in Telegram, clears the encrypted token and webhook secret, marks the bot as `disconnected`" — implies best-effort `deleteWebhook` with local state always cleared.
- **No `try/catch` for emailService-style errors in WebClient calls**: established pattern (`AuthService` lines 155-163) is to wrap fire-and-forget secondary effects in `try/catch (RuntimeException)`. For `setWebhook` failure during connect, this is NOT secondary — it's a hard precondition. Reject the whole connect operation and DO NOT persist the encrypted token. (Different policy from disconnect.)
- **Frontend masking of token in UI**: token must NEVER round-trip back to the client after connect. Backend response on `GET /api/v1/projects/{id}/bot` should return `botUsername`, `botName`, `botAvatar`, `status`, `lastConnectedAt` — but NOT any token field, not even masked. The UI shows `••••••••:••••••••` literally as a CSS placeholder.
- **No existing pattern for "decrypt at use"**: every Telegram API call (send test message, set webhook) needs to load the encrypted token + iv from MongoDB and decrypt in-memory. Service surface should expose a method like `withDecryptedToken(projectId, function)` that scopes the cleartext to a callback and immediately drops it — avoids leaking decrypted tokens into log lines or event metadata.
- **Spring Session + bot-state correlation**: principal in `AppUserDetails.id` ties session ↔ user; no project-scoping at the session layer (correctly so — projects are URL-scoped). All bot endpoints must call `projectService.requireOwned(currentUserId, projectId, false)` as the first reactive step. Skipping this is a critical IDOR.
- **`code` field in ErrorResponse is unused by the frontend**: do not rely on it for UX branching. Pick HTTP statuses (401 invalid token, 409 conflict on Telegram-bot-already-connected, 422 brute-force, 429 rate-limited) so `useApiError` can map them via status-keyed locale keys. Backend can still set `code` for log analytics — but the frontend will ignore it.
