# Code Research: 01 — Foundation Infrastructure

researched: 2026-05-03

---

## 1. Current Repo State

Root of `/Users/pavlokorolov/IdeaProjects/simple-sender/`:

```
.claude/          — Claude Code project config, agents, skills
.git/
.idea/            — IntelliJ project files (untracked, in .gitignore)
work/             — Feature specs and research (this file lives here)
workflow/         — Reference TZ docs (abstract examples, NOT the source of truth)
.gitignore        — Currently configured for Claude/IDE files, NOT for a Java/Node project
CLAUDE.md         — Project-level Claude instructions
project.md        — High-level product plan (MVP epics overview)
```

**No `backend/`, `frontend/`, `infra/`, `docs/` directories exist yet.** This is a pure greenfield setup.

---

## 2. Existing .gitignore — What's Already There

The current `.gitignore` at repo root is configured for Claude Code's own files, not for a Java/Node monorepo. Current entries cover:

- `.claude`, `CLAUDE.md`, `project.md`, `workflow/`, `work/` — Claude/planning files
- Security/credentials: `.credentials.json`, `.mcp.json`
- IDE: `.idea/`
- Session/runtime/debug/cache: `session-env/`, `shell-snapshots/`, `debug/`, `downloads/`, `tmp/`
- System: `.DS_Store`, `*.lock`
- Settings: `settings.json`, `settings.local.json`

**Missing entries that must be added** for the monorepo:
- `.env` (top-level and per-module)
- `*.key`, `secrets/`
- `backend/build/`, `backend/.gradle/`, `backend/out/`
- `frontend/node_modules/`, `frontend/.nuxt/`, `frontend/.output/`
- `*.jar`, `*.class`

---

## 3. workflow/01-фундамент/README.md — Reference Context

This is the original TZ (reference only). Key differences from the approved user-spec:

| Topic | workflow README (reference) | user-spec.md (actual) |
|---|---|---|
| Backend | Node.js + NestJS or Python + FastAPI | Java Spring Boot 4 + WebFlux |
| DB | PostgreSQL 16 | MongoDB + Redis |
| Frontend | React + Vite | Nuxt 4 + Vue 3 + TypeScript |
| DevTools in Docker | Full stack in Docker Compose | Only MongoDB + Redis in Docker Compose |
| Seed script | Super Admin seed in DB | Not in Epic 01 scope |
| CI/CD | GitHub Actions on push to main | Out of scope for Epic 01 |

The workflow README is an abstract template. **All implementation decisions come from user-spec.md.**

---

## 4. Spring Boot 4.0 / Spring Framework 7 — Breaking Changes Relevant to This Setup

Spring Boot 4.0.6 is the target version (released 2026-04-23). It uses Spring Framework 7.x.

### 4.1 Java and Jakarta EE Baseline

- **Java 17 minimum** (Java 21 recommended and used here via toolchain)
- **Jakarta EE 11** baseline — all `javax.*` imports are `jakarta.*`
- Servlet 6.1 baseline (irrelevant for WebFlux, which does not use Servlet APIs)

### 4.2 Removed Deprecated APIs

All APIs deprecated in Spring Boot 2.x and 3.x are removed in 4.0. Key removals:

- `WebSecurityConfigurerAdapter` — removed. Replaced by `SecurityWebFilterChain` beans (for WebFlux) or `SecurityFilterChain` beans (for servlet).
- `MockitoTestExecutionListener` — removed. Use `@ExtendWith(MockitoExtension.class)` instead.
- Jackson 2.x default replaced by Jackson 3.x with package relocations (affects `ObjectMapper` if explicitly imported from `com.fasterxml.jackson`). Jackson 3 is still `com.fasterxml.jackson`, so minimal impact for basic use.

### 4.3 spring-boot-devtools — Why It's Excluded

The user-spec states `spring-boot-devtools` is excluded due to Spring Boot 4 / Framework 7 compatibility issues. The concrete mechanism:

- DevTools uses a dual-classloader strategy: `RestartClassLoader` for app classes + base classloader for third-party jars.
- In Spring Boot 4 with modularized JARs and stricter module boundaries in Spring Framework 7, the `RestartClassLoader` causes `ClassCastException` and `NoSuchBeanDefinitionException` when Spring registers beans under one classloader but another classloader's version of the class is resolved at injection time.
- Additionally, DevTools Live Reload is **disabled by default** in Spring Boot 4.0, and the feature is marked deprecated with no replacement (scheduled for removal in 4.1).
- **Decision:** Do not include `spring-boot-devtools` in `build.gradle`. Use IntelliJ's built-in build-on-save or `./gradlew bootRun` with manual restarts.

### 4.4 WebFlux Security — SecurityWebFilterChain Configuration

For reactive (WebFlux) applications, the correct Spring Security approach in Spring Boot 4:

- Use `@EnableWebFluxSecurity` on a `@Configuration` class (note: `@Configuration` is **not** included in `@EnableWebFluxSecurity` since Spring Security 6 — you must add it explicitly)
- Define a `SecurityWebFilterChain` bean using `ServerHttpSecurity`
- Use `authorizeExchange()` (not `authorizeRequests()` which is servlet-only)
- `pathMatchers()` + `permitAll()` for public paths; `anyExchange().authenticated()` for the rest

Minimum `SecurityConfig` skeleton for `/health` public access:

```java
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers(HttpMethod.GET, "/health").permitAll()
                .anyExchange().authenticated()
            )
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .build();
    }
}
```

**Important:** CSRF protection should be disabled for stateless REST APIs. For a reactive app without sessions, form login is not applicable.

---

## 5. Gradle Build Configuration

### 5.1 Java Toolchain (pinning Java 21)

In `build.gradle` (Groovy DSL — matches user-spec which uses `.gradle` not `.gradle.kts`):

```groovy
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
```

This pins compilation, test execution, and Javadoc to Java 21 regardless of the system JDK. Gradle will auto-provision JDK 21 if not installed locally (when Foojay resolver plugin is present in `settings.gradle`).

For `settings.gradle`, add toolchain auto-provisioning:

```groovy
plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'
}
```

### 5.2 Core Dependencies (Spring Boot 4.0.6)

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '4.0.6'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.botfunnel'
version = '0.0.1-SNAPSHOT'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-webflux'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-data-mongodb-reactive'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis-reactive'

    // No spring-boot-devtools — excluded intentionally (Spring Boot 4 / Framework 7 classloader issues)

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'io.projectreactor:reactor-test'
    testImplementation 'org.springframework.security:spring-security-test'
}

test {
    useJUnitPlatform()
}
```

**Notes:**
- Version numbers for Spring Boot dependencies are managed by the `io.spring.dependency-management` plugin — do not specify explicit versions for `spring-boot-starter-*` artifacts.
- `spring-boot-starter-data-redis-reactive` pulls in Lettuce (reactive Netty-based Redis client). No additional reactive driver needed.
- `spring-boot-starter-data-mongodb-reactive` pulls in the MongoDB Reactive Streams driver.
- `reactor-test` provides `StepVerifier` for testing reactive streams; required for WebFlux integration tests.

### 5.3 WebFlux Integration Test Pattern

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class HealthEndpointTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void healthEndpoint_returns200WithStatusOk() {
        webTestClient.get().uri("/health")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("ok");
    }
}
```

For this test to work without MongoDB/Redis connections, either use `@MockBean` for the reactive repositories, or configure `application-test.properties` with embedded or mocked connection strings.

---

## 6. Nuxt 4 Frontend Setup

### 6.1 Nuxt Version — What to Use

- **Nuxt 4** is stable as of July 2025. Latest version as of May 2026: `4.4.x` (4.4.2 was March 2026).
- Nuxt 3 reaches EOL July 31, 2026 — start with Nuxt 4.
- **Known issue:** Nuxt 4.4.4 has a dev server failure for `ssr: false` apps ("Vite Node IPC socket path not configured"). Pin to `4.4.2` or latest patch that resolves this if SSR is disabled.
- User-spec says Nuxt 3, but given Nuxt 3 EOL in 2026 and Nuxt 4 being stable, **clarify with user** — research suggests Nuxt 4 is the right choice for a new project.

### 6.2 Project Init

```bash
pnpm dlx nuxi@latest init frontend
cd frontend
pnpm install
```

This generates:
- `app.vue` — root component
- `nuxt.config.ts` — configuration
- `package.json` with `nuxt` as dependency
- `tsconfig.json` (auto-generated by Nuxt, do not edit manually)

### 6.3 Minimum nuxt.config.ts for Skeleton

```typescript
// frontend/nuxt.config.ts
export default defineNuxtConfig({
  compatibilityDate: '2025-01-01',  // required in Nuxt 4

  typescript: {
    typeCheck: true,
    strict: true,
  },

  devtools: { enabled: true },
})
```

**SSR:** Keep default (SSR enabled) unless the user explicitly wants SPA mode. Nuxt 4.4.4 has a known bug with `ssr: false`, avoid it for now.

### 6.4 .nvmrc

Node.js LTS as of May 2026: **Node 24** (active LTS through 2028-05-31).

```
24
```

Alternatives: Node 22 (LTS until 2027-04-30) for more conservative projects. Node 20 reaches EOL 2026-04-30 — avoid for new projects.

---

## 7. Docker Compose — MongoDB + Redis

### 7.1 Recommended Image Versions

- **MongoDB:** `mongo:8.0` — MongoDB 8.0.x is the latest stable major. Pin to minor `8.0` to get patch updates automatically but avoid major version jumps.
- **Redis:** `redis:7.4` — Redis 7.4.x is the latest stable. Redis Enterprise 7.4.6 released June 2025.

### 7.2 Minimal docker-compose.yml for Epic 01

```yaml
# infra/docker-compose.yml
services:
  mongodb:
    image: mongo:8.0
    container_name: botfunnel-mongodb
    ports:
      - "27017:27017"
    volumes:
      - mongodb_data:/data/db
    environment:
      MONGO_INITDB_DATABASE: botfunnel
    restart: unless-stopped

  redis:
    image: redis:7.4
    container_name: botfunnel-redis
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data
    restart: unless-stopped

volumes:
  mongodb_data:
  redis_data:
```

**Notes:**
- No auth configured on MongoDB/Redis for local dev (matches user-spec: defaults work without changes).
- Named volumes ensure data persists across `docker compose down` / `up` cycles.
- No app containers — backend and frontend run locally outside Docker (explicit Epic 01 decision).
- Port conflicts: document in `docs/local-setup.md` how to remap `27017` and `6379` in `docker-compose.yml`.

### 7.3 .env.example

```dotenv
# MongoDB connection (used by backend)
MONGODB_URI=mongodb://localhost:27017/botfunnel

# Redis connection (used by backend)
REDIS_URL=redis://localhost:6379
```

Only 2 variables per user-spec decision. Additional secrets (JWT_SECRET, BOT_TOKEN_ENCRYPTION_KEY, etc.) added in later epics.

---

## 8. Gitleaks Pre-Commit Hook

### 8.1 Installation Prerequisite

```bash
brew install gitleaks   # macOS
# or: download binary from https://github.com/gitleaks/gitleaks/releases
```

Developers must install gitleaks CLI before the hook works. Document this in `docs/local-setup.md`.

### 8.2 Hook Script — `.git/hooks/pre-commit`

```bash
#!/usr/bin/env bash
set -euo pipefail

if ! command -v gitleaks &>/dev/null; then
  echo "WARNING: gitleaks not found. Skipping secret scan."
  echo "Install: brew install gitleaks"
  echo "See docs/local-setup.md for setup instructions."
  exit 0
fi

gitleaks protect --staged --redact --exit-code 1

if [ $? -eq 1 ]; then
  echo "BLOCKED: gitleaks detected secrets in staged files."
  echo "Remove secrets before committing."
  exit 1
fi
```

Make it executable:

```bash
chmod +x .git/hooks/pre-commit
```

### 8.3 Key Notes

- `--staged` — scans only staged files, not the full history (fast for pre-commit use)
- `--redact` — redacts detected secret values in output (avoids printing secrets to terminal)
- `--exit-code 1` — exits with code 1 on detection (blocks the commit)
- The hook is in `.git/hooks/` — **not tracked by git**. Every developer must install it manually after cloning. Document in `docs/local-setup.md`.
- Optional: add a `scripts/install-hooks.sh` that copies the hook and makes it executable, then reference from `docs/local-setup.md`.

---

## 9. Package Structure

Per user-spec, only two packages are created in Epic 01:

| Package | Location | Contents |
|---|---|---|
| `com.botfunnel.security` | `backend/src/main/java/com/botfunnel/security/` | `SecurityConfig.java` |
| `com.botfunnel.common` | `backend/src/main/java/com/botfunnel/common/` | Placeholder class or `package-info.java` |
| `com.botfunnel` | `backend/src/main/java/com/botfunnel/` | `BotFunnelApplication.java` (main class) |

Application entry point class name: `BotFunnelApplication` with `@SpringBootApplication`.

---

## 10. Potential Problems

### 10.1 MongoDB/Redis Auto-Configuration Without Connection

When running `./gradlew build` (which runs tests), Spring Boot will attempt to connect to MongoDB and Redis if `spring-boot-starter-data-mongodb-reactive` and `spring-boot-starter-data-redis-reactive` are on the classpath. If MongoDB/Redis are not running, the integration test will fail.

**Solution options:**
- Use `@SpringBootTest` with `excludeAutoConfiguration` to disable MongoDB/Redis auto-config during health tests.
- Or configure `application-test.properties` with mocked/fake connection properties and `@TestPropertySource`.
- Or use embedded/in-memory alternatives for tests only (`de.flapdoodle.embed.mongo.spring` for MongoDB, `com.github.codemonstur:embedded-redis` — check Spring Boot 4 compatibility).
- Simplest: use `@MockBean` on any reactive repository interfaces so Spring context starts without actual connections.

### 10.2 Spring Security Blocking /health by Default

If `SecurityConfig` is misconfigured, `GET /health` returns 401. The `SecurityWebFilterChain` bean must explicitly `permitAll()` the `/health` path. Without an explicit `SecurityWebFilterChain` bean, Spring Boot auto-configuration secures all endpoints.

### 10.3 WebSecurityConfigurerAdapter Usage

Any example code from Spring Boot 2.x/3.x tutorials that uses `WebSecurityConfigurerAdapter` will not compile in Spring Boot 4. The pattern is fully removed.

### 10.4 Nuxt Version Ambiguity

The user-spec says "Nuxt 3" but Nuxt 3 EOL is July 2026. Since this is a greenfield project starting May 2026, **Nuxt 4 is the correct choice**. However, confirm with the user before implementing. The `user-spec.md` criteria say "Nuxt 3 + Vue 3 + TypeScript + pnpm" — flag this for clarification.

### 10.5 Gradle Wrapper

The repo does not yet have a Gradle wrapper. `./gradlew` will not exist until `gradle wrapper` is run inside `backend/`. This is part of the initial setup. Gradle version that supports Java 21 and Spring Boot 4: Gradle 8.x (8.5+).

### 10.6 Windows Developer Warning

Docker Compose and bash scripts (gitleaks hook) behave differently on Windows. `docs/local-setup.md` must include a WSL2 recommendation for Windows developers as noted in user-spec risks.

---

## 11. Constraints and Infrastructure

- **Spring Boot version:** 4.0.6 (released 2026-04-23) — latest stable in 4.0.x line
- **Spring Framework version:** 7.x (managed by Spring Boot BOM)
- **Java:** 21 (toolchain-pinned in Gradle, not just a compiler flag)
- **Gradle DSL:** Groovy (`build.gradle`) — user-spec does not specify KTS, default to Groovy unless specified
- **Build tool:** Gradle (no Maven)
- **Frontend package manager:** pnpm (not npm, not yarn)
- **Node.js:** 24 LTS (`.nvmrc`)
- **Docker Compose:** V2 syntax (no `version:` key — V2 format is default in Docker Compose v2.x+)
- **No CI/CD in Epic 01** — GitHub Actions deferred to later epics
- **No Dockerfile in Epic 01** — containers for app deferred to later epics
- **`spring-boot-devtools` explicitly excluded** — do not add, not even in `developmentOnly` configuration

---

## 12. Similar Features / Comparable Patterns

No existing backend or frontend code in this repo. This section is N/A for a greenfield project.

---

## 13. External Libraries Summary

| Library | Version | Key API for this Epic |
|---|---|---|
| Spring Boot | 4.0.6 | `@SpringBootApplication`, `SpringApplication.run()` |
| Spring WebFlux | managed by Boot | `RouterFunction`, `@RestController` with `Mono<T>` returns |
| Spring Security (reactive) | managed by Boot | `@EnableWebFluxSecurity`, `SecurityWebFilterChain`, `ServerHttpSecurity` |
| Spring Data MongoDB Reactive | managed by Boot | `ReactiveMongoRepository` (not used in Epic 01, just on classpath) |
| Spring Data Redis Reactive | managed by Boot | `ReactiveRedisConnectionFactory` (not used in Epic 01, just on classpath) |
| Nuxt | 4.4.x | `defineNuxtConfig()`, `nuxi init`, `pnpm dev` |
| Vue 3 | managed by Nuxt | Composition API, `<script setup>` |
| MongoDB Docker | 8.0 | Official `mongo:8.0` image |
| Redis Docker | 7.4 | Official `redis:7.4` image |
| Gitleaks | latest (v8.x) | `gitleaks protect --staged --redact` |
| Gradle | 8.5+ | `gradle wrapper`, `./gradlew build`, `./gradlew bootRun` |
