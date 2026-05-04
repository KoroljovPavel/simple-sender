# Decisions Log: 02 — Authentication & Accounts

Agent reports on completed tasks. Each entry is written by the agent that executed the task.

---

## Task 1: Infrastructure Setup

**Status:** Done
**Commit:** 79f4612
**Agent:** main agent
**Summary:** Fixed broken MONGODB_URI and REDIS_URL property bindings that have existed since Epic 01. Added 7 new Gradle dependencies (spring-session-data-mongodb, spring-boot-starter-mail, spring-boot-starter-validation, jobrunr-spring-boot-3-starter, testcontainers suite) with JaCoCo plugin. Added Mailpit to docker-compose.yml (127.0.0.1-bound ports, pinned to v1.29.7, healthcheck). Created application-test.properties stub. Synced .env.example.
**Deviations:** Replaced `server.servlet.session.cookie.http-only=true` (Servlet-only, silently ignored in WebFlux) with `server.reactive.session.cookie.http-only=true` — the correct reactive stack property. Removed empty default from `SUPER_ADMIN_PASSWORD` env binding to enforce fail-fast startup if var is unset. Pinned Mailpit image to `v1.29.7` (pulled version) instead of `latest`. These deviations correct spec oversights found during review.

**Reviews:**

*Round 1:*
- code-reviewer: 2 major, 2 minor → [logs/working/task-1/code-reviewer-1.json](logs/working/task-1/code-reviewer-1.json)
- security-auditor: 2 major, 2 minor → [logs/working/task-1/security-auditor-1.json](logs/working/task-1/security-auditor-1.json)
- infrastructure-reviewer: 1 major, 3 minor, 1 nitpick → [logs/working/task-1/infrastructure-reviewer-1.json](logs/working/task-1/infrastructure-reviewer-1.json)

*Round 1 fixes committed (79f4612): all critical/major findings resolved.*

**Verification:**
- `./gradlew compileJava` → BUILD SUCCESSFUL
- `./gradlew dependencies | grep spring-session-data-mongodb` → dependency listed (3.5.0)
- Smoke: `curl http://localhost:8025/api/v1/messages` → JSON with `messages` array (Mailpit alive)

---

## Task 2: User Domain

**Status:** Done
**Commit:** f36ec53
**Agent:** main agent
**Summary:** Created full User domain (UserStatus enum, User @Document with all token/timestamp fields, UserRepository with custom finders, UserService with softDelete and canActivate). Created common error infrastructure (AppException with 5 factory methods, ErrorResponse record, GlobalErrorHandler with AppException/BindException/ResponseStatusException/Throwable handlers). Extended SecurityConfig: removed CSRF disable, wired WebSessionServerSecurityContextRepository, configured CORS with @Value-injected origin and allowCredentials, added XorServerCsrfTokenRequestAttributeHandler, permitted /api/auth/**, disabled httpBasic/formLogin. Added BCryptPasswordEncoder bean. Also added @ActiveProfiles("test") and @MockitoBean StorageProvider to all integration tests (JobRunr added as dependency in Task 1 requires StorageProvider mock); updated application-test.properties with safe test env var defaults.
**Deviations:** `WebSessionServerSecurityContextRepository.getInstance()` does not exist in Spring Security 6.x — `new WebSessionServerSecurityContextRepository()` is the correct form (compile-verified). Added `ResponseStatusException` handler to `GlobalErrorHandler` to prevent Spring's 404/etc responses being swallowed as 500 by the catch-all. Added `server.reactive.session.cookie.secure` and `same-site` properties (env-configurable).

**Reviews:**

*Round 1:*
- code-reviewer: 1 critical, 4 major, 4 minor → [logs/working/task-2/code-reviewer-1.json](logs/working/task-2/code-reviewer-1.json)
- security-auditor: 3 critical, 3 major, 4 minor → [logs/working/task-2/security-auditor-1.json](logs/working/task-2/security-auditor-1.json)
- test-reviewer: 4 major, 3 minor → [logs/working/task-2/test-reviewer-1.json](logs/working/task-2/test-reviewer-1.json)

*Round 1 fixes committed (f36ec53): all critical/major findings resolved. Critical `getInstance()` documented as spec error (method doesn't exist in Spring Security 6).*

**Verification:**
- `./gradlew test` → BUILD SUCCESSFUL (16 tests total, 0 failed)
- `./gradlew test --tests "com.botfunnel.user.*"` → 5 tests passed
- `./gradlew test --tests "com.botfunnel.common.*"` → 6 tests passed
- `./gradlew test --tests "com.botfunnel.security.*"` → 2 tests passed

---

## Task 3: Email service + templates

**Status:** Done
**Commit:** 380140b
**Agent:** main agent
**Summary:** Created `EmailService` with three send methods (verification, password reset, account blocked) using a `Mono.fromCallable` fire-and-forget pattern on `Schedulers.boundedElastic()`. HTML templates in Ukrainian loaded from classpath; all user-supplied values HTML-escaped via a `htmlEscape()` chain covering `&`, `<`, `>`, `"`, and `'`. Created three classpath templates with correct Ukrainian subject lines and URL placeholder formats.
**Deviations:** Added single-quote escaping (`'` → `&#39;`) not mentioned in spec — required to prevent XSS in single-quoted attribute contexts (security-auditor finding). `loadTemplate` made package-private to enable spy-based template-not-found testing without altering the main send path.

**Reviews:**

*Round 1:*
- code-reviewer: approved_with_suggestions (3 minor) → [logs/working/task-3/code-reviewer-1.json](logs/working/task-3/code-reviewer-1.json)
- security-auditor: changes_required (6 major, 4 minor) → [logs/working/task-3/security-auditor-1.json](logs/working/task-3/security-auditor-1.json)
- test-reviewer: needs_improvement (3 major, 3 minor) → [logs/working/task-3/test-reviewer-1.json](logs/working/task-3/test-reviewer-1.json)

*Round 2 (after fixes — commit ccb9762):*
- code-reviewer: approved (3 minor) → [logs/working/task-3/code-reviewer-2.json](logs/working/task-3/code-reviewer-2.json)
- security-auditor: approved (5 minor) → [logs/working/task-3/security-auditor-2.json](logs/working/task-3/security-auditor-2.json)
- test-reviewer: needs_improvement (2 major, 1 minor) → [logs/working/task-3/test-reviewer-2.json](logs/working/task-3/test-reviewer-2.json)

*Round 3 (after fixes — commit 380140b):*
- test-reviewer: passed (1 minor, accepted) → [logs/working/task-3/test-reviewer-3.json](logs/working/task-3/test-reviewer-3.json)

**Verification:**
- `./gradlew test --tests "com.botfunnel.email.*"` → 11 passed, 0 failed
