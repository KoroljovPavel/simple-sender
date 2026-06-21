# Code Research: 02-auth

## 1. Entry Points

### Current security entry point
`/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/security/SecurityConfig.java`
Spring Security WebFlux filter chain. Currently: `/health` is `permitAll()`, every other exchange requires `.authenticated()`. No auth provider is wired — the `authenticated()` rule blocks all non-health paths with HTTP 401 because there is no `ReactiveAuthenticationManager` registered.

Key bean signature:
```java
@Bean
public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http)
```

### Current open endpoints
- `GET /health` — `permitAll()`, returns `{"status":"ok"}`
- All other paths — `anyExchange().authenticated()` → HTTP 401 until auth is wired

### Controller to extend
`/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/HealthController.java`
Single `GET /health` endpoint returning `Mono<Map<String, String>>`. Pattern for new controllers: `@RestController` + reactive return types.

### Frontend entry (Hello World only)
`/Users/pavlokorolov/IdeaProjects/simple-sender/frontend/app.vue`
Single root component with `<h1>Hello World</h1>`. No pages/, middleware/, composables/, layouts/ directories exist. No routing, no auth guard, no HTTP client configured.

---

## 2. Data Layer

### MongoDB
No collections exist. The driver (`spring-boot-starter-data-mongodb-reactive`) is on the classpath but no `@Document` classes or repositories are defined. `spring.data.mongodb.auto-index-creation=false` is set — index management is manual.

Connection: `MONGODB_URI=mongodb://localhost:27017/botfunnel` (from `.env.example`). Property **not bound** to Spring Boot's `spring.data.mongodb.uri` in `application.properties` — this is a known audit warning (Task 8). The epic must add:
```properties
spring.data.mongodb.uri=${MONGODB_URI}
spring.data.redis.url=${REDIS_URL}
```

### Redis
`spring-boot-starter-data-redis-reactive` is on the classpath. `REDIS_URL=redis://localhost:6379`. Same binding gap as MongoDB — env var not wired in `application.properties`.

### What needs to be created for auth
- `User` document (`@Document("users")`) — fields: id, email, passwordHash, name, status (pending/active/blocked/deleted), createdAt, updatedAt, deletedAt
- `RefreshToken` or `SecurityEvent` — depending on storage strategy decisions
- Verification token storage (either embedded in User or separate collection)
- MongoDB indexes: unique on `users.email`, TTL on verification/reset tokens

### No schema migrations tooling
No Liquibase, Mongock, or equivalent is present. Index/collection management will be explicit in code or via `@Indexed` annotations (currently disabled by `auto-index-creation=false`).

---

## 3. Similar Features

No auth features exist yet in the codebase. The only implemented pattern is:

**REST controller with reactive return:**
```java
@RestController
public class HealthController {
    @GetMapping("/health")
    public Mono<Map<String, String>> health() { ... }
}
```

**Security filter chain customization:**
```java
http.csrf(csrf -> csrf.disable())
    .authorizeExchange(exchanges -> exchanges
        .pathMatchers("/health").permitAll()
        .anyExchange().authenticated())
    .build()
```

These are the only two patterns to extend. No service layer, repository layer, or DTO patterns exist yet — auth epic establishes all of them.

---

## 4. Integration Points

### Where auth must hook in
- `SecurityConfig.java` — must be extended to add JWT filter (`addFilterAt` / `addFilterBefore`) and open new public paths (`/api/auth/**`). Currently a single file, will grow significantly.
- `application.properties` — must gain JWT secret, token TTL, email SMTP settings, rate-limit thresholds as new env-driven properties.
- `build.gradle` — missing dependencies that must be added: JJWT or `spring-security-oauth2-jose` (JWT), Java mail (`spring-boot-starter-mail`), BCrypt is included via `spring-security` already.

### Shared state / event systems
None exists. Redis is available for: refresh token blacklist, brute-force rate-limit counters (incr/expire), verification token TTL. No shared event bus or Kafka.

### Packages that don't exist yet
- `com.botfunnel.auth` — controllers, services, DTOs for registration/login/password-reset
- `com.botfunnel.user` — User entity, UserRepository, UserService
- `com.botfunnel.security` — needs expansion: JwtFilter, JwtService, UserDetailsService implementation (currently only SecurityConfig exists)
- `com.botfunnel.common` — placeholder only (`package-info.java`). Can house shared exceptions, response wrappers, validation utilities.

---

## 5. Existing Tests

**Framework:** JUnit 5 + WebTestClient + Spring Security Test + Mockito
**Runner:** `./gradlew test` (JUnit Platform)
**Test location:** `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/test/java/com/botfunnel/`

Three tests exist:

```java
// HealthEndpointTest.java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTest {
    @Autowired WebTestClient webTestClient;
    @MockitoBean MongoClient mongoClient;
    @MockitoBean RedisConnectionFactory redisConnectionFactory;
    @MockitoBean ReactiveRedisConnectionFactory reactiveRedisConnectionFactory;

    @Test
    void healthEndpointReturns200WithOkBody() { ... }
}

// SecurityBlockTest.java — same setup
@Test
void undefinedPathBlockedReturns401() {
    webTestClient.get().uri("/api/nonexistent").exchange().expectStatus().isUnauthorized();
}
```

**Pattern established:**
- `@SpringBootTest(RANDOM_PORT)` for all integration tests
- `@MockitoBean` (NOT deprecated `@MockBean`) for `MongoClient`, `RedisConnectionFactory`, `ReactiveRedisConnectionFactory` — prevents Spring from requiring live DB at test startup
- `WebTestClient` for HTTP assertions (status, body, headers)
- No test slices (`@WebFluxTest`) used yet

**What's covered:** health endpoint, permit-all security rule, anyExchange block.
**What's not covered:** any auth flow, JWT validation, database interactions.

**Key constraint:** New auth integration tests will need the same three `@MockitoBean` fields unless they switch to Testcontainers (not yet configured). If tests need real MongoDB, Testcontainers must be introduced.

---

## 6. Shared Utilities

### BCrypt
`spring-security-crypto` is included transitively via `spring-boot-starter-security`. `BCryptPasswordEncoder` is available without additional dependencies. Cost factor 12 is specified in the workflow spec.

### Reactive utilities
`reactor-test` (`StepVerifier`) is available for unit testing reactive chains. `io.projectreactor:reactor-test` is in testImplementation.

### `com.botfunnel.common`
Currently only `package-info.java`. Intended for shared utilities — no concrete code exists. Auth epic can add exception classes (`AppException`, `ErrorResponse`), validation helpers, or shared constants here.

---

## 7. Potential Problems

### Missing env var bindings (CRITICAL for Epic 02)
`application.properties` does NOT bind `MONGODB_URI` or `REDIS_URL` to Spring Boot properties. When auth services start using `ReactiveMongoRepository` or `ReactiveRedisConnectionFactory`, Spring Boot will use defaults (`localhost:27017`, `localhost:6379`) instead of env vars. Must add:
```properties
spring.data.mongodb.uri=${MONGODB_URI}
spring.data.redis.url=${REDIS_URL}
```
Noted in Task 8 audit (code-audit-report) as a WARNING — "address before first epic with live DB."

### No JWT library present
`build.gradle` has no JWT library. Options to evaluate:
- `io.jsonwebtoken:jjwt-api` + `jjwt-impl` + `jjwt-jackson` (most common, explicit)
- `spring-boot-starter-oauth2-resource-server` (uses Nimbus JOSE — built into Boot BOM, no version to manage, best fit for Spring Security 6 reactive filter integration)

### No mail sender present
`build.gradle` has no `spring-boot-starter-mail`. Email verification and password reset require it. Mailpit is NOT in `infra/docker-compose.yml` — it must be added for local dev email testing.

### No reactive auth wiring in SecurityConfig
`SecurityConfig` has no `ReactiveAuthenticationManager`, no `ReactiveUserDetailsService`, no JWT decoder. Adding JWT-based auth requires non-trivial additions. The current `.anyExchange().authenticated()` with no provider means all protected paths return 401 immediately — there's no mechanism to pass a token.

### Test DB isolation
Current tests use `@MockitoBean` to avoid real DB. Auth service tests that actually query MongoDB (user lookup, token storage) cannot use this approach. Testcontainers for MongoDB will be needed, or a dedicated test application.properties profile with embedded MongoDB (de.flapdoodle). Neither is currently set up.

### Nuxt frontend — no auth infrastructure at all
No pages directory, no middleware directory, no composables. No HTTP client (Axios, $fetch configuration) is configured. No state management (Pinia). The frontend is a bare minimum Hello World — the auth epic must bootstrap the entire frontend app structure (pages, layouts, middleware, composables for token storage, routing guards).

### `pending` user restrictions — deferred by design
`future-features.md` explicitly defers the decision on what actions `pending` users can/cannot do. The auth epic only creates the `pending` status — restriction enforcement is future work.

---

## 8. Constraints & Infrastructure

### Spring Boot version: 3.5.0
Chosen over 4.0.x (Decision 1). Spring Security 6.x. Spring WebFlux with Netty embedded server. Reactive-first: all DB operations must be non-blocking (`Mono`/`Flux`).

### Java: 21 (toolchain-pinned)
Records available, pattern matching, sealed classes all usable.

### Gradle: 8.14.4 Groovy DSL
`./gradlew` wrapper present. `@MockitoBean` (not `@MockBean`) required — Spring Boot 3.4+ deprecation.

### MongoDB: 8.0 (local Docker, no auth)
`127.0.0.1:27017`. No credentials in local dev. Named volume `mongodb_data`. `auto-index-creation=false` — indexes must be created explicitly.

### Redis: 7.4 (local Docker, no auth)
`127.0.0.1:6379`. Named volume `redis_data`. Available for: refresh token blacklist, rate-limit counters, session cache.

### Mailpit: NOT present
Must be added to `infra/docker-compose.yml` for local email testing. Standard local-dev pattern: Mailpit on `:8025` (UI) + `:1025` (SMTP).

### Nuxt: 4.4.x, SSR enabled, pnpm
`compatibilityDate: '2025-07-01'`, TypeScript strict. No UI library installed. No Pinia, no Axios, no nuxt-auth-utils.

### Pre-commit hook
Gitleaks (`scripts/install-hooks.sh`). JWT secrets in `.env` must NOT be committed. `.env` is already in `.gitignore`.

### CI/CD
Not researched in Epic 01 — no GitHub Actions workflow files found in the repo. Deployment is via GitHub CI/CD (project CLAUDE.md constraint). Auth epic likely does not introduce CI — that's Epic 01's deferred task or a separate epic.

### No `application-test.properties`
No test-profile properties file exists. Tests use the same `application.properties`. Any test-specific config (e.g., embedded Mongo URI, mock SMTP) would need a new `src/test/resources/application-test.properties`.

---

## 9. External Libraries (to be evaluated)

### JWT — two viable options

**Option A: JJWT (io.jsonwebtoken)**
Direct API, explicit signing/parsing, widely documented. Requires three artifacts: `jjwt-api`, `jjwt-impl`, `jjwt-jackson`.

**Option B: spring-security-oauth2-resource-server (Nimbus JOSE)**
Included in Spring Boot BOM — no version to manage. Integrates natively with `ServerHttpSecurity.oauth2ResourceServer()`. Works with `ReactiveJwtDecoder`. Best fit for Spring Security 6 WebFlux — this is the approach the Spring documentation recommends for reactive JWT validation.

### Password encoding
`BCryptPasswordEncoder` — available via `spring-boot-starter-security` (already present). No additional dependency needed.

### Email
`spring-boot-starter-mail` — available in Spring Boot BOM. Wraps JavaMail/Jakarta Mail. Will need SMTP config properties (`spring.mail.host`, `spring.mail.port`, etc.).

### Rate limiting
No library present. Options: manual Redis INCR/EXPIRE pattern, or Bucket4j (`com.bucket4j:bucket4j-redis` or in-memory). Manual Redis implementation is simpler for MVP brute-force protection.

### Testcontainers (for auth integration tests with real MongoDB)
Not configured. `org.testcontainers:mongodb` + `org.testcontainers:junit-jupiter` would allow tests to spin up a real MongoDB container. Required if auth service tests need real DB operations (user lookup, token storage). Alternative: `de.flapdoodle.embed:de.flapdoodle.embed.mongo.spring30x` embedded MongoDB — lighter but less production-representative.

---

## Summary: What Exists vs. What Is Missing

| Area | Exists | Missing |
|------|--------|---------|
| Security filter chain | `SecurityConfig` (CSRF off, health open, rest 401) | JWT filter, auth manager, public path `/api/auth/**` |
| User entity | Nothing | `User` @Document, `UserRepository`, `UserService` |
| Auth endpoints | Nothing | POST /api/auth/register, /login, /logout, /refresh, /verify-email, /forgot-password, /reset-password |
| JWT | No library | Library choice + `JwtService`, `JwtFilter` |
| Email | No library, no Mailpit | `spring-boot-starter-mail`, Mailpit in docker-compose |
| Redis usage | Driver on classpath | Refresh token blacklist, rate-limit counters |
| MongoDB usage | Driver on classpath, URI not bound | User collection, indexes, env var binding in application.properties |
| Frontend auth | Nothing | pages/, middleware/, composables/, layouts/, Pinia, login/register/profile pages |
| Tests | 3 integration tests (health only) | Auth flow tests, Testcontainers or embedded Mongo setup |
