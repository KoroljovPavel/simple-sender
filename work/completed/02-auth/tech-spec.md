---
created: 2026-05-04
status: approved
size: L
branch: dev
---

# Tech Spec: 02 — Authentication & Accounts

## Solution

Full authentication layer for Bot Funnel Service: user registration with email verification, session-based login (HTTP-only cookies, sessions in MongoDB via `spring-session-data-mongodb`), brute-force protection via Redis (per-email + per-IP), password reset, profile management, account soft-delete with JobRunr cleanup, and Super Admin seeding. Simultaneously bootstraps the entire Nuxt frontend: Pinia, Vue Query, shadcn-vue, vee-validate/zod, auth middleware, and layout shell.

The central technical constraint is Spring WebFlux + spring-session-data-mongodb: session creation, SecurityContext injection, and remember-me TTL all require explicit reactive handling in login endpoint code because Spring Security's form-login automation does not apply in WebFlux. The login handler must: (1) invalidate the pre-auth session to prevent session fixation, (2) create a new `WebSession`, (3) store `SecurityContext` in the new session, (4) set `maxInactiveInterval`. Spring Session serializes to MongoDB automatically.

The second complexity area is Nuxt SSR auth hydration: `GET /api/auth/me` must be called server-side to seed the Pinia auth store, but reactive state must not duplicate on client. We use `useState` composable for SSR-safe store initialization. The third complexity is terminating all sessions across devices: querying spring-session-data-mongodb's sessions collection by principal name requires `ReactiveMongoTemplate` with a field-level query on the sessions document structure.

## Architecture

### What we're building/modifying

- **`backend/src/main/java/com/botfunnel/auth/`** — New package: `AuthController` (all auth REST endpoints), `AuthService` (registration, login, logout, password reset, email verification, resend), `TokenService` (raw→hash token generation for verify/reset), request/response DTOs with Bean Validation annotations
- **`backend/src/main/java/com/botfunnel/user/`** — New package: `User` @Document, `UserRepository` (ReactiveMongoRepository), `UserService` (CRUD, status transitions), `UserStatus` enum
- **`backend/src/main/java/com/botfunnel/profile/`** — New package: `ProfileController` (profile CRUD, change-password, terminate-all-sessions, delete-account), `ProfileService`
- **`backend/src/main/java/com/botfunnel/admin/`** — New package: `SuperAdminSeeder` (ApplicationRunner)
- **`backend/src/main/java/com/botfunnel/jobs/`** — New package: `HardDeleteJob` (JobRunr recurring job, daily at 03:00)
- **`backend/src/main/java/com/botfunnel/email/`** — New package: `EmailService` (JavaMailSender wrapper), 3 plain HTML email templates under `resources/templates/email/`
- **`backend/src/main/java/com/botfunnel/events/`** — New package: `Event` @Document, `EventRepository`, `EventService` (security event logging)
- **`backend/src/main/java/com/botfunnel/security/SecurityConfig.java`** — Extend: session-based SecurityContext, open `/api/auth/**` paths, wire `WebSessionServerSecurityContextRepository`, remove misleading "stateless" CSRF comment, configure SameSite cookie policy
- **`backend/src/main/java/com/botfunnel/common/`** — Create: `AppException`, `ErrorResponse` DTO, global error handler (`@ControllerAdvice`)
- **`backend/src/main/resources/application.properties`** — Add: `spring.data.mongodb.uri`, `spring.data.redis.url`, `spring.session.*`, `spring.mail.*`, session TTL vars, Super Admin vars
- **`backend/build.gradle`** — Add: `spring-session-data-mongodb`, `spring-boot-starter-mail`, `jobrunr-spring-boot-3-starter`, Testcontainers deps
- **`infra/docker-compose.yml`** — Add: Mailpit service (ports :8025 UI, :1025 SMTP, bound to 127.0.0.1)
- **`frontend/`** — Full bootstrap: `nuxt.config.ts` updates, install all libs (Pinia, Vue Query, shadcn-vue, vee-validate/zod), `layouts/default.vue` (topbar + sidebar), `layouts/auth.vue` (centered card), `middleware/auth.ts`, `stores/auth.ts` (Pinia), `composables/useApi.ts` ($fetch wrapper), pages: `/auth/login`, `/auth/register`, `/auth/forgot-password`, `/auth/reset-password`, `/auth/verify-email`, `/profile`, `/dashboard`
- **`.claude/skills/project-knowledge/references/architecture.md`** — Update: session-based auth replaces JWT; Redis scope updated; new backend deps listed; jjwt reference removed
- **`.claude/skills/project-knowledge/references/deployment.md`** — Update: remove JWT_* vars, add SESSION_TTL_*, SUPER_ADMIN_*, MAIL_*, SUPPORT_EMAIL, Mailpit section
- **`.claude/skills/project-knowledge/references/ux-guidelines.md`** — Update: account deletion without password re-entry; Mailpit local dev note

### How it works

**Registration flow:**
1. `POST /api/auth/register` → Bean Validation (email format, password rules: min 8 chars, ≥1 letter, ≥1 digit, name non-blank) → check email uniqueness (active/pending/soft-deleted window check) → hash password (BCrypt cost 12) → create User with status `pending` → generate 32-byte random token (SecureRandom, base64url) → store SHA-256(token) in `emailVerificationTokenHash`, set `emailVerificationExpiresAt = now + 24h` → dispatch email via `EmailService` (fire-and-forget: `.subscribeOn(Schedulers.boundedElastic()).subscribe(null, log::error)`) → return 201
2. Email contains link: `{APP_URL}/auth/verify-email?token={rawToken}`

**Email verification flow:**
1. `GET /api/auth/verify-email?token={rawToken}` → SHA-256(rawToken), lookup user by hash where `status IN (pending, active)` and `expiresAt > now` → set status `active`, clear token fields → return 200 `{redirect: "/login"}`
2. Invalid token → 400; expired → 400 with `{code: "TOKEN_EXPIRED"}` (frontend shows resend button)

**Login flow:**
1. `POST /api/auth/login` →
   - Check Redis per-email key (`brute:fail:{email}`, count ≥ 5 → 429) **and** per-IP key (`brute:fail:ip:{ip}`, count ≥ 20 → 429)
   - Find user by email. **If not found: run `BCryptPasswordEncoder.matches(input, dummyHash)` to consume ~250ms** (timing attack prevention), then return 401
   - BCrypt verify against stored hash → on mismatch: INCR both brute-force keys (EXPIRE 900 on first), return 401
   - Check user status: `blocked` → 403; `deleted` → 401; `pending` → allow login (return warning); `active` → proceed
   - **Session fixation prevention:** `exchange.getSession().flatMap(WebSession::invalidate)` → then `exchange.getSession()` to obtain a fresh session ID
   - Store `SecurityContext` in new session attributes → set `maxInactiveInterval` (30d if `rememberMe`, 24h otherwise)
   - DEL both brute-force keys on success → log `login_success` event → return 200
2. Session cookie (`SESSION`, HttpOnly, path `/`, SameSite=Strict in prod, SameSite=Lax in dev) set automatically by Spring WebFlux session management

**Session auth filter:**
- Spring Session reads `SESSION` cookie from every request, loads `SecurityContext` from MongoDB `sessions` collection → injects into reactive `SecurityContext` holder (automatic via `WebSessionServerSecurityContextRepository`)
- `GET /api/auth/me` → returns current user DTO from session context (used by Nuxt middleware on SSR)

**Logout:** `POST /api/auth/logout` → `exchange.getSession().flatMap(WebSession::invalidate)` → removes session from MongoDB → 200 (other sessions on other devices untouched)

**Password reset:**
1. `POST /api/auth/forgot-password` → always return 200 same message → if email exists and status not deleted: generate token (SecureRandom, 32 bytes, base64url), store SHA-256 hash in `passwordResetTokenHash` + `passwordResetExpiresAt = now + 1h` → send reset email (fire-and-forget)
2. `POST /api/auth/reset-password` → SHA-256(token), lookup user, check `expiresAt > now`, check `passwordResetUsedAt == null` → Bean Validation on new password → hash new password → update user: new hash, mark `passwordResetUsedAt = now`, clear token fields → **invalidate ALL sessions for this user** (see terminate-all mechanism below) → log `password_changed` event → return 200

**Profile — change-password:** `POST /api/profile/change-password` → verify current password (BCrypt) → hash new password → update DB → **invalidate all sessions EXCEPT the current one** (terminate all other devices) → log `password_changed` event → return 200.
Rationale: a hijacked session on another device should be invalidated when user actively changes their password.

**Terminate-all-sessions mechanism:** Query `sessions` MongoDB collection using `ReactiveMongoTemplate` with a filter on the principal-name field. Spring Session stores principal name in the session document's `principal` field (exact field path verified in Task 6 against spring-session-data-mongodb source). Delete all matching documents for the given userId (or all except current session ID for change-password).

**Brute-force via Redis:**
- Email key: `brute:fail:{email}` — INCR on each failed login, EXPIRE 900 on first increment; threshold 5
- IP key: `brute:fail:ip:{ip}` — INCR on each failed login from this IP, EXPIRE 900 on first increment; threshold 20 (allows legitimate users with dynamic IPs while blocking spray attacks)
- On login success: DEL both keys
- Login check: GET both keys before touching DB; if either ≥ threshold → 429

**Resend verification rate limit:**
- Key: `resend:rate:{email}` — `SET NX EX 60` (set if not exists, TTL 60s). Always perform SET regardless of whether email exists (prevents timing oracle).
- Returns 429 if key existed; sends email if user exists and status is pending

**SUPPORT_EMAIL in error responses:** `@Value("${app.support-email}")` injected into `AuthService` and `ProfileService`. Used in 403 (blocked user) and 409 (soft-deleted email) error messages. Added to `application.properties` as `app.support-email=${SUPPORT_EMAIL}`.

**Super Admin seed (ApplicationRunner):**
- Reads `SUPER_ADMIN_EMAIL` + `SUPER_ADMIN_PASSWORD` env vars
- Finds user by email → if exists and `isSuperAdmin=true`: skip → if exists and not superadmin: promote → if not exists: create with status `active` and `isSuperAdmin=true`
- Logs outcome on startup; does not throw on error (log only)

**JobRunr hard-delete:**
- Recurring job scheduled daily at 03:00 (cron: `0 3 * * *`)
- Query: `users.status = deleted AND users.deletedAt <= now - 30d` → hard delete matching records
- Logs user IDs of deleted records (for GDPR erasure audit trail) and total count

**Frontend auth middleware (`middleware/auth.ts`):**
- On every route navigation: if `authStore.user` null → call `GET /api/auth/me` → if 401 and route requires auth → redirect `/auth/login`
- Protected routes: `/dashboard`, `/profile` (and future epics via route meta)
- Public routes: `/auth/**` — redirect to `/dashboard` if already logged in

### Shared resources

| Resource | Owner | Consumers | Instances |
|----------|-------|-----------|-----------|
| MongoDB `users` collection | `UserRepository` | `AuthService`, `ProfileService`, `SuperAdminSeeder`, `HardDeleteJob` | 1 reactive connection pool |
| MongoDB `sessions` collection | spring-session-data-mongodb | Spring Security filter chain, logout handler, reset-password, terminate-all | 1 reactive connection pool |
| MongoDB `events` collection | `EventRepository` | `EventService` (called from auth/profile flows) | 1 reactive connection pool |
| Redis (brute-force keys) | `AuthService` | login endpoint | 1 reactive Redis connection |
| Redis (rate-limit keys) | `AuthService` | resend-verification endpoint | 1 reactive Redis connection |
| `JavaMailSender` | `EmailService` | `AuthService` (verify, reset, blocked emails) | 1 instance (singleton) |

## Decisions

### Decision 1: spring-session-data-mongodb over JWT
**Decision:** Use `spring-session-data-mongodb` for session management. Sessions stored in MongoDB `sessions` collection. `jjwt` is NOT added to `build.gradle` (it was listed in `architecture.md` as planned from initial design, but was never actually in `build.gradle`). The `jjwt` reference is removed from `architecture.md` in this epic.
**Rationale:** Supports US-7 (Logout deletes only current session), US-8 (multiple concurrent sessions), US-13 (terminate all sessions). Session revocation is trivial — delete session document. JWT blacklist in Redis would be equally complex but adds token refresh complexity. Sessions also eliminate frontend token storage concerns.
**Risk mitigated:** spring-session-data-mongodb issue #226 (application-level cleanup in reactive mode) — physical session expiry is handled by MongoDB TTL index on `sessions.expireAt`, which is database-level and independent of reactive code. Fallback documented: if TTL index does not fire, `spring-session-data-redis` is available (Redis already in stack).
**Alternatives considered:** JWT with Redis blacklist — more complex revocation, extra token refresh logic, no advantage for this use case.
**References:** Supports US-3 (login), US-7 (logout), US-8 (multiple sessions), US-13 (terminate all); user-spec "Технічні рішення" section.

### Decision 2: Email verification and reset tokens stored as SHA-256 hash
**Decision:** Raw token (32 random bytes, SecureRandom, base64url) sent only in email. SHA-256 hash stored in `users` document. Fields embedded in User: `emailVerificationTokenHash`, `emailVerificationExpiresAt`, `passwordResetTokenHash`, `passwordResetExpiresAt`, `passwordResetUsedAt`.
**Rationale:** Supports US-4 (email verification security), US-11 (password reset single-use). If MongoDB is compromised, raw tokens cannot be extracted. Embedding in User document avoids an extra collection — token cardinality is 1:1 per user.
**Alternatives considered:** Separate `tokens` collection with TTL index — cleaner but TTL index auto-deletes the document, making "already used" detection impossible. Rejected.
**References:** Supports US-4, US-11; user-spec "Технічні рішення" section.

### Decision 3: BCrypt cost factor 12
**Decision:** [TECHNICAL] `BCryptPasswordEncoder` with strength 12. BCrypt is provided via `spring-boot-starter-security` (no extra dependency).
**Rationale:** Cost 12 ≈ 250ms on modern hardware — acceptable for login latency, resistant to offline brute-force. Cost 10 (default) is below current OWASP recommendations.
**Alternatives considered:** Argon2 (stronger) — not in Spring Boot BOM without extra dependency; overkill for MVP.

### Decision 4: Redis brute-force via manual INCR/EXPIRE (per-email + per-IP)
**Decision:** [TECHNICAL] Two Redis keys per failed login: `brute:fail:{email}` (threshold 5, prevents targeted account attacks) and `brute:fail:ip:{ip}` (threshold 20, prevents password spraying across accounts). Raw `INCR` + `EXPIRE` commands via `ReactiveRedisTemplate`. No Bucket4j or rate-limit library.
**Rationale:** Dual-key approach addresses user-spec requirement "5 невдалих спроб з одного IP/email." Manual INCR/EXPIRE is sufficient for this use case without adding a library. Fail-open on Redis unavailability (allow login) — brute-force protection is DoS mitigation, not a security gate.
**Alternatives considered:** Bucket4j — adds a dependency for a 5-line Redis operation; rejected.
**References:** Supports US-6 (brute-force protection); user-spec "Ризики" section.

### Decision 5: Account deletion without re-entering password
**Decision:** `DELETE /api/profile` requires only an active authenticated session. A modal with consequences is shown — no password confirmation.
**Rationale:** Supports US-18 (delete account). An active session already proves identity. Password re-entry would add friction with no meaningful security gain given session-based auth. This intentionally contradicts the current `ux-guidelines.md` — that guideline is updated in this epic.
**Alternatives considered:** Require current password (original ux-guidelines) — adds friction, no security benefit over an active session.
**References:** Supports US-18; user-spec "Технічні рішення" section; see User-Spec Deviations.

### Decision 6: Testcontainers (MongoDB + Redis + Mailpit)
**Decision:** Integration tests use `org.testcontainers:mongodb`, `org.testcontainers:junit-jupiter`, and `ch.martinelli.oss:testcontainers-mailpit:1.3.1` (Maven Central). Tests extend `AbstractIntegrationTest` base class that starts all three containers once per test suite (Singleton pattern).
**Rationale:** Supports US-22 (80%+ coverage). Auth flows require real MongoDB (user creation, token lookup), real Redis (brute-force counter), and real Mailpit (email delivery + token extraction).
**Alternatives considered:** Embedded MongoDB (flapdoodle) — does not support MongoDB 8.0 features, less production-representative. `@MockitoBean` for all — cannot test actual auth flows.
**References:** Supports US-22; user-spec "Тестування" section, user-spec "Ризики" section.

### Decision 7: Resend verification endpoint is public (by email)
**Decision:** [TECHNICAL] `POST /api/auth/resend-verification` accepts `{email}` in body, requires no auth. Rate limited via Redis SET NX (called regardless of whether email exists — prevents timing oracle). Returns 200 always.
**Rationale:** Allows users to resend from the expired-token page without being logged in. The profile page (authenticated) calls the same endpoint. Consistent behavior, single endpoint, anti-enumeration guaranteed by both uniform response and uniform Redis SET.
**Alternatives considered:** Separate authenticated endpoint for profile — would require duplication.

### Decision 8: CORS with allowCredentials for session cookies
**Decision:** [TECHNICAL] `SecurityConfig` configures CORS: allowed origin = `http://localhost:3000` (dev) / `${APP_URL}` (prod), `allowCredentials: true` (required for session cookies), explicit allowed methods (GET, POST, PATCH, DELETE, OPTIONS), explicit allowed headers (Content-Type, X-Requested-With). No wildcard origins when `allowCredentials: true` (browser blocks it).
**Rationale:** Session cookies are not sent cross-origin without `allowCredentials: true`. Explicit allowed methods and headers prevent accidental CORS bypass.
**Alternatives considered:** Per-controller `@CrossOrigin` — too granular, easy to miss a new endpoint.

### Decision 9: Frontend pages use layout-based auth protection
**Decision:** [TECHNICAL] Auth protection enforced via Nuxt `middleware/auth.ts` (global route middleware) + layout assignment in page `definePageMeta`. Public pages use `layout: auth`; protected pages use `layout: default`.
**Rationale:** Global middleware runs on every navigation. Standard Nuxt 4 pattern for auth-protected routing.
**Alternatives considered:** Per-page guards — more verbose, easier to forget on new pages.

### Decision 10: Email templates as plain HTML (no Thymeleaf)
**Decision:** [TECHNICAL] Email templates are plain HTML files in `resources/templates/email/`. `EmailService` sends them via `MimeMessageHelper` with HTML content type. Variable substitution via `String.replace()`. User-supplied content (name) is HTML-escaped before embedding to prevent XSS. `build.gradle` does NOT add `spring-boot-starter-thymeleaf`.
**Rationale:** Thymeleaf adds a dependency for 3 static templates where only the token URL and user name change. String.replace() is sufficient.
**Alternatives considered:** Thymeleaf — adds dependency for no meaningful benefit.

### Decision 11: SecurityConfig session-based auth wiring
**Decision:** [TECHNICAL] `SecurityConfig` uses `http.securityContextRepository(WebSessionServerSecurityContextRepository.getInstance())` and disables `httpBasic()` and `formLogin()`. Login controller calls `ReactiveAuthenticationManager` programmatically — Spring Security filter chain does NOT auto-authenticate.
**Rationale:** WebFlux does not support `UsernamePasswordAuthenticationFilter` auto-wiring like Servlet stack. Manual authentication in the controller is the correct WebFlux pattern for session-based login.
**Alternatives considered:** Form login auto-config — not applicable to reactive stack.

### Decision 12: CSRF handling with session cookies
**Decision:** CSRF protection is enabled for state-changing endpoints (`POST`, `PATCH`, `DELETE`). In the API context (frontend on separate origin), we use `SameSite=Strict` cookie policy for production (strongest protection) combined with the `Origin`/`Referer` header check built into Spring Security CSRF. SameSite=Strict prevents the browser from sending the session cookie on cross-site requests. In development (`SameSite=Lax`), CSRF tokens are exchanged via the `X-XSRF-TOKEN` header with Spring Security's `CookieCsrfTokenRepository`.
**Rationale:** The original `SecurityConfig` has `.csrf(csrf -> csrf.disable())` with a comment "stateless API." After this epic, the app uses session cookies — CSRF is fully relevant. Disabling CSRF with session cookies is an OWASP A05 violation. SameSite=Strict mitigates CSRF in modern browsers; the Spring CSRF token adds defense-in-depth for older browsers.
**Alternatives considered:** Keeping CSRF disabled — OWASP violation for session-cookie-based auth. Rejected.

### Decision 13: Dummy BCrypt on missing user (timing attack prevention)
**Decision:** [TECHNICAL] When a user with the given email is not found, `AuthService` runs `BCryptPasswordEncoder.matches(providedPassword, DUMMY_HASH)` before returning 401. `DUMMY_HASH` is a precomputed bcrypt hash stored as a constant. This ensures the response time for "user not found" (~250ms) matches "wrong password" (~250ms), preventing email enumeration via timing.
**Rationale:** Without this, an attacker can enumerate valid emails by measuring response time: non-existent email responds in <1ms (no DB hash comparison), valid email with wrong password responds in ~250ms.
**Alternatives considered:** Artificial sleep — harder to get right, leaks timing under load. Rejected.

### Decision 14: Change-password invalidates all OTHER sessions
**Decision:** `POST /api/profile/change-password` invalidates all user sessions except the current one. The current session stays active (user remains logged in on the device where they changed the password). All other devices are signed out.
**Rationale:** [TECHNICAL] A hijacked session on another device becomes invalid after a password change, limiting the blast radius of credential theft. Current session exclusion avoids a poor UX (user immediately signed out after changing their password).
**Alternatives considered:** Invalidate all sessions including current — worse UX, user must re-login. Keep all sessions — security gap.

### Decision 15: Backend password validation via Bean Validation
**Decision:** [TECHNICAL] Registration and change-password DTOs use Bean Validation annotations: `@NotBlank` (email, name, password), `@Email` (email), `@Size(min=8)` (password), and a custom `@ValidPassword` constraint that checks for ≥1 letter and ≥1 digit via regex. `spring-boot-starter-validation` added to `build.gradle`.
**Rationale:** Frontend validation (zod) can be bypassed by direct API calls. Backend validation is the authoritative gate. Bean Validation integrates with Spring WebFlux via `@Valid` on `@RequestBody`.
**Alternatives considered:** Manual service-layer validation — more verbose, harder to test.

## Data Models

### `users` collection (new)

```
{
  _id: ObjectId,
  email: String (unique, lowercase),
  passwordHash: String (bcrypt cost 12),
  name: String,
  status: String enum(pending, active, blocked, deleted),
  isSuperAdmin: Boolean (default false),
  emailVerificationTokenHash: String (nullable),
  emailVerificationExpiresAt: Instant (nullable),
  passwordResetTokenHash: String (nullable),
  passwordResetExpiresAt: Instant (nullable),
  passwordResetUsedAt: Instant (nullable),
  createdAt: Instant,
  updatedAt: Instant,
  deletedAt: Instant (nullable)
}
```

Indexes:
- `{ email: 1 }` — unique
- `{ status: 1 }` — for hard-delete job queries

### `sessions` collection (managed by spring-session-data-mongodb)

Managed automatically. Contains: sessionId (indexed), principal name, attributes (SecurityContext, maxInactiveInterval), `expireAt` (TTL index, auto-created by spring-session at startup).

**Note:** `spring.data.mongodb.auto-index-creation=false` is set globally. The `expireAt` TTL index on `sessions` must be created by spring-session-data-mongodb initialization. Verify this works correctly in smoke test — this is the issue #226 area.

### `events` collection (new)

```
{
  _id: ObjectId,
  userId: ObjectId (nullable — null for pre-auth events like failed login),
  eventType: String enum(login_success, login_failed, password_changed, password_reset_requested, email_verified, account_blocked, account_unblocked, account_deleted),
  ipAddress: String,
  userAgent: String (nullable),
  metadata: Document (nullable, e.g. {reason: "brute_force"}),
  createdAt: Instant
}
```

Indexes:
- `{ userId: 1, createdAt: -1 }` — for future user audit log queries

**Note:** `account_blocked` and `account_unblocked` event types are defined in the schema but have no trigger in this epic (admin block/unblock is Epic 10). The schema is defined now to avoid a migration later.

### Redis key schema

| Key pattern | Type | TTL | Purpose |
|-------------|------|-----|---------|
| `brute:fail:{email}` | string (counter) | 900s (15 min) | failed login attempts per email (threshold 5) |
| `brute:fail:ip:{ip}` | string (counter) | 900s (15 min) | failed login attempts per IP (threshold 20) |
| `resend:rate:{email}` | string (exists/not) | 60s | resend verification rate limit |

## Dependencies

### New (backend — `backend/build.gradle`)

- `spring-session-data-mongodb` — session storage in MongoDB (Spring Session 3.x, managed by Boot BOM)
- `spring-boot-starter-mail` — JavaMailSender for SMTP email sending
- `spring-boot-starter-validation` — Bean Validation for DTO request validation
- `jobrunr-spring-boot-3-starter` — background job scheduling (hard-delete recurring job)
- `org.testcontainers:mongodb` (testImplementation) — real MongoDB in integration tests
- `org.testcontainers:junit-jupiter` (testImplementation) — JUnit 5 integration
- `ch.martinelli.oss:testcontainers-mailpit:1.3.1` (testImplementation) — Mailpit Testcontainer for email flow tests

### New (frontend — `frontend/`)

- `@pinia/nuxt` — global state management (auth store, current project)
- `@tanstack/vue-query` — server state, caching, background refetch
- `vee-validate` + `zod` — form validation with schema support
- `shadcn-vue` — pre-built UI components (button, input, form, card, dialog, badge)
- `@nuxtjs/tailwindcss` — utility CSS (if not already present from Epic 01)

### Note on jjwt

`jjwt-api`, `jjwt-impl`, `jjwt-jackson` were listed in `architecture.md` as planned dependencies. They were never in `build.gradle`. This epic does not add them. The `architecture.md` reference is removed in Task 9.

### Using existing

- `spring-boot-starter-security` — SecurityWebFilterChain, BCryptPasswordEncoder
- `spring-boot-starter-data-mongodb-reactive` — ReactiveMongoRepository, @Document
- `spring-boot-starter-data-redis-reactive` — ReactiveRedisTemplate for brute-force
- `spring-boot-starter-test` — JUnit 5, WebTestClient

## Testing Strategy

**Feature size:** L

### Unit tests

- `TokenService` — SHA-256 hash is deterministic, raw token length = 32 bytes, two calls produce different tokens
- `AuthService` — registration duplicate email check (mock repo returns existing user), token expiry check (mock clock), brute-force counter logic (mock ReactiveRedisTemplate), dummy BCrypt runs when user not found
- `UserService` — status transition guards (e.g., cannot activate a `blocked` user)
- `ProfileService` — change-password wrong current password returns error, terminate-all excludes current session
- `EmailService` — JavaMailSender called with correct recipient/subject/HTML body (mock JavaMailSender); fire-and-forget errors do not propagate to caller
- `HardDeleteJob` — query selects only users where `deletedAt <= now - 30d` (mock repository)
- Frontend form validation schemas (Vitest) — email format, password rules (8+, letter, digit), confirm-password match, name non-blank

### Integration tests (Testcontainers: MongoDB + Redis + Mailpit)

Base class `AbstractIntegrationTest`: `@SpringBootTest(RANDOM_PORT)` + `@Testcontainers` + singleton containers (MongoDB 8.0, Redis 7.4, Mailpit 1.3.1). All tests inherit this base. Test profile (`application-test.properties`) overrides: `spring.data.mongodb.uri`, `spring.data.redis.url`, `spring.mail.host/port`. Assertions check both HTTP response AND MongoDB/Redis state.

Covered flows:
- **Registration:** valid data → 201, user in DB with status `pending`, email in Mailpit, event `login_success` NOT present yet; duplicate email → 409; soft-deleted email (within 30d) → 409
- **Email verification:** valid token → 200, user status = `active`, `emailVerificationTokenHash` cleared in DB; invalid token → 400; expired token → 400 with `TOKEN_EXPIRED` code; resend → email in Mailpit, Redis `resend:rate` key set; resend within 60s → 429
- **Login:** active user → 200 + SESSION cookie, `login_success` event in `events` collection; pending user → 200 with warning, `login_success` event; blocked user → 403; wrong password × 5 → 429 on 6th, Redis counter = 5; wrong password then correct → Redis counter reset to 0; login of non-existent email → 401 (response time comparable — verify no 500); rememberMe=true → query MongoDB `sessions` collection, assert `maxInactiveInterval >= 30 days`; rememberMe=false → assert `maxInactiveInterval <= 24h`; login creates new session ID (different from any pre-auth session)
- **Logout:** SESSION cookie invalidated (second request with same cookie → 401), other session (second login) remains valid
- **Forgot password:** existing email → 200, email in Mailpit, extract token from body; unknown email → 200 (no email in Mailpit, same response time)
- **Reset password:** valid token → 200, BCrypt verify old password → fails, all sessions invalidated (query `sessions` by principal → 0 records); expired token → 400; already-used token → 400; invalid token → 400 (not 500); `password_changed` event logged
- **Profile GET/PATCH:** GET → 200 with name/email/status; PATCH name → 200, name updated in MongoDB
- **Change-password:** correct current → 200, new password works for login, other sessions invalidated, current session still valid; wrong current → 400; `password_changed` event logged
- **Terminate all sessions:** create 2 sessions, terminate-all → both sessions deleted from `sessions` collection
- **Delete account:** soft-delete → `status=deleted`, `deletedAt` set in DB, current session invalidated; `account_deleted` event logged
- **Super Admin seed:** run seeder twice → exactly 1 record with `isSuperAdmin=true` and matching email
- **Hard-delete job:** create user with `deletedAt = now - 31d` and one with `deletedAt = now - 29d`, trigger job → first user deleted, second remains

### E2E tests (Playwright)

- Golden path: registration → Mailpit UI email → verify-email page → login → dashboard visible
- Forgot password: forgot-password form → Mailpit reset email → reset-password page → login with new password
- Auth middleware: navigate to `/dashboard` without auth → redirected to `/auth/login`

### Frontend component tests (Vitest + Vue Test Utils)

- Login form: submits with valid data, shows inline errors on invalid email/short password, disables submit button while loading
- Register form: password match validation, all field inline errors, correct data submits

## Agent Verification Plan

**Source:** user-spec "Як перевірити" section.

### Verification approach

Agent runs `./gradlew test`. All integration tests run in-process via Testcontainers — no external services needed.

| Step | Tool | Expected result |
|------|------|----------------|
| `./gradlew test` | bash | All tests green, 80%+ coverage on auth/user/profile packages |
| `POST /api/auth/register` (valid data) | WebTestClient in test | 201, user in Testcontainer MongoDB with status `pending`, email in Mailpit |
| `POST /api/auth/register` (existing email) | WebTestClient in test | 409 + `message` field |
| `POST /api/auth/login` (5+ wrong passwords) | WebTestClient in test | 6th attempt → 429; Redis `brute:fail:{email}` counter = 5 |
| `POST /api/auth/login` (correct after 4 wrongs) | WebTestClient in test | 200; Redis counter deleted |
| `POST /api/auth/login` (non-existent email) | WebTestClient in test | 401 (not 500, not different timing) |
| `POST /api/auth/login` (blocked user) | WebTestClient in test | 403 + message with SUPPORT_EMAIL |
| `POST /api/auth/forgot-password` (unknown email) | WebTestClient in test | 200 (not 404); no email in Mailpit |
| `GET /api/auth/verify-email?token=INVALID` | WebTestClient in test | 400 (not 500) |
| Super Admin seed (run twice) | Testcontainer MongoDB query | Exactly 1 record with `isSuperAdmin=true` |
| JobRunr hard-delete job (triggered manually) | Testcontainer test | User with `deletedAt > 30d` deleted; newer user remains |
| Session TTL (rememberMe=true) | Query `sessions` MongoDB collection | `maxInactiveInterval` value ≥ 2592000 seconds (30 days) |
| `login_success` event after login | Query `events` MongoDB collection | 1 record with `eventType=login_success` and matching userId |

### Tools required

bash, Testcontainers (MongoDB, Redis, Mailpit) — all run in-process during `./gradlew test`. No external services required for agent verification. User verification requires `docker compose up` and browser.

## Risks

| Risk | Mitigation |
|------|-----------|
| spring-session-data-mongodb TTL index on `sessions.expireAt` not created | First integration test verifies session expiry works. If TTL index absent, sessions accumulate but expiry is checked in memory. Add manual index creation in `MongoConfig` `@PostConstruct` if spring-session does not auto-create it. Fallback: switch to `spring-session-data-redis`. |
| Terminate-all-sessions: sessions collection field path unknown | Task 6 implementation starts by verifying the actual spring-session-data-mongodb document schema in the Testcontainer (log or query the sessions collection after first login). Then build the `ReactiveMongoTemplate` query on the verified field name. |
| Mailpit Testcontainer availability | `ch.martinelli.oss:testcontainers-mailpit:1.3.1` is in Maven Central. Pin version explicitly in `build.gradle`. |
| Nuxt SSR double-fetch on auth check | Use `useState('auth-user', ...)` in `authStore` — SSR-safe. `GET /api/auth/me` uses `useFetch` with `server: true` only once, result hydrated to client. |
| Redis unavailable during brute-force check | Fail open — if Redis is down, allow login. Brute-force protection is DoS mitigation, not authentication gate. Log Redis connectivity error. |
| sessions collection index conflict | `auto-index-creation=false` globally may prevent spring-session from creating TTL index. Verify spring-session creates index on startup via log output in smoke test. Add manual `expireAt` index creation via `MongoConfig` if needed. |
| Pending user has full API access | By design, documented in user-spec Обмеження section. Only project creation is blocked in this epic. Full restriction enforcement (subscriber access, funnel access, etc.) is delegated to future epics. Risk: unverified users can call all current API endpoints. Accepted for MVP. |
| account_blocked/account_unblocked events have no trigger | By design. Admin block/unblock is Epic 10. The event types are pre-defined in the schema; no code logs them in this epic. Risk: events schema has two unused values. Accepted. |

## User-Spec Deviations

- **US-23 (ux-guidelines.md — account deletion requires password):** User-spec says "Видалити акаунт: модалка зі списком наслідків → кнопка 'Видалити акаунт'" (no password re-entry). Current `ux-guidelines.md` says "Account deletion: requires current password." Tech-spec follows user-spec. `ux-guidelines.md` updated in Task 9. → **No pending approval required — user-spec explicitly overrides the old guideline.**

- **architecture.md (jjwt reference):** `architecture.md` lists `jjwt` as a planned key dependency. This epic does not add jjwt; `architecture.md` updated to list `spring-session-data-mongodb` instead. → **No pending approval — user-spec explicitly states session-based auth replaces JWT.**

- **deployment.md (JWT env vars):** `deployment.md` lists `JWT_SECRET`, `JWT_ACCESS_TTL_MINUTES`, `JWT_REFRESH_TTL_DAYS`. These are removed and replaced with `SESSION_TTL_DEFAULT_HOURS`, `SESSION_TTL_REMEMBER_ME_DAYS`, `SUPER_ADMIN_EMAIL`, `SUPER_ADMIN_PASSWORD`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `SUPPORT_EMAIL`. → **No pending approval — user-spec explicitly states deployment.md is updated.**

## Acceptance Criteria

(Derived from user-spec. All agent-verifiable criteria are covered by integration tests; user-verifiable criteria require manual browser check.)

- [ ] `./gradlew test` passes with 80%+ coverage on `auth`, `user`, `profile`, `email`, `events` packages
- [ ] `POST /api/auth/register` with valid data → 201, email in Mailpit, user status `pending` in MongoDB
- [ ] `POST /api/auth/register` with invalid password (< 8 chars, no digit) → 400 with validation errors
- [ ] `POST /api/auth/register` with existing email → 409 + message "Користувач з таким email вже існує"
- [ ] `POST /api/auth/register` with soft-deleted email (within 30d) → 409 + support contact message
- [ ] Email verification link click → user status `active`, `emailVerificationTokenHash` cleared, response `{redirect: "/login"}`
- [ ] Invalid/corrupt verification token → 400
- [ ] Expired verification token → 400 `{code: "TOKEN_EXPIRED"}`
- [ ] Resend verification within 60s → 429
- [ ] `POST /api/auth/login` with `pending` user → 200 + SESSION cookie + warning field
- [ ] `POST /api/auth/login` with `active` user → 200 + SESSION cookie; new session ID differs from pre-auth session (session fixation prevention)
- [ ] `POST /api/auth/login` with `rememberMe: true` → MongoDB `sessions` document `maxInactiveInterval` ≥ 30 days
- [ ] `POST /api/auth/login` without `rememberMe` → `maxInactiveInterval` ≤ 24h
- [ ] 5 failed logins from same email → 6th attempt returns 429; Redis `brute:fail:{email}` = 5
- [ ] Successful login after failed attempts → Redis brute-force counter deleted
- [ ] `POST /api/auth/login` with non-existent email → 401 (not 500)
- [ ] `POST /api/auth/login` with `blocked` user → 403 + message containing SUPPORT_EMAIL value
- [ ] `POST /api/auth/logout` → SESSION cookie invalidated; other sessions unaffected
- [ ] `POST /api/auth/forgot-password` (any email) → always 200
- [ ] Reset password: valid token (1h TTL) → 200, old password rejected, ALL sessions invalidated (query `sessions` collection → 0 records for user)
- [ ] Reset password with expired token → 400
- [ ] Reset password with already-used token → 400
- [ ] Reset password with invalid token → 400 (not 500)
- [ ] `PATCH /api/profile` with `{name: "New Name"}` → name updated in MongoDB; `{isSuperAdmin: true}` in body → ignored (whitelist DTO)
- [ ] `POST /api/profile/change-password` with wrong current password → 400
- [ ] `POST /api/profile/change-password` with correct current password → 200, new password works for login, other sessions invalidated, current session still valid
- [ ] `POST /api/profile/terminate-all-sessions` → all user sessions removed from MongoDB `sessions` collection
- [ ] `DELETE /api/profile` → user status `deleted`, `deletedAt` set, current session invalidated
- [ ] JobRunr hard-delete job removes users where `deletedAt` is older than 30 days; users deleted within 30 days remain; deleted user IDs logged
- [ ] Super Admin seed run twice → exactly 1 `isSuperAdmin=true` record with matching email
- [ ] Auth events logged in `events` collection: login_success, login_failed, password_changed, email_verified, account_deleted
- [ ] Frontend: accessing `/dashboard` or `/profile` without auth → redirect to `/auth/login`
- [ ] Frontend: layout shell (topbar + sidebar) renders for authenticated users
- [ ] Frontend: pending user sees banner "Підтвердіть email для повного доступу" on dashboard
- [ ] `architecture.md`, `deployment.md`, `ux-guidelines.md` updated per decision log

## Implementation Tasks

**Task count note:** 15 tasks total (11 implementation + 3 audit + 1 QA). This matches the L-size feature scope. User-spec explicitly states wide scope is intentional and splitting is impractical due to tight interdependencies.

### Wave 1 (parallel)

#### Task 1: Infrastructure setup
- **Description:** Fix missing env var bindings in `application.properties` (`MONGODB_URI`, `REDIS_URL`). Add Mailpit service to `infra/docker-compose.yml` (SMTP :1025, UI :8025, bound to 127.0.0.1). Add new Gradle deps: `spring-session-data-mongodb`, `spring-boot-starter-mail`, `spring-boot-starter-validation`, `jobrunr-spring-boot-3-starter`, Testcontainers suite. Add session/mail/jobrunr properties to `application.properties`, `application-test.properties` (Testcontainer overrides), and `.env.example`.
- **Skill:** infrastructure-setup
- **Reviewers:** code-reviewer, security-auditor, infrastructure-reviewer
- **Verify-smoke:** `docker compose -f infra/docker-compose.yml up -d mailpit && curl -s http://localhost:8025/api/v1/messages | jq .` → JSON response (Mailpit API alive)
- **Files to modify:** `backend/build.gradle`, `backend/src/main/resources/application.properties`, `backend/src/test/resources/application-test.properties` (new), `infra/docker-compose.yml`, `.env.example`
- **Files to read:** `backend/src/main/resources/application.properties`, `infra/docker-compose.yml`

#### Task 2: User domain
- **Description:** Create `User` @Document with all fields (status enum, token fields, timestamps, isSuperAdmin), `UserRepository` (ReactiveMongoRepository with custom finders), `UserService` (status transitions, soft-delete, find-by-email). Extend `SecurityConfig`: open `/api/auth/**` paths, wire `WebSessionServerSecurityContextRepository`, configure CSRF (SameSite cookie policy per Decision 12), configure CORS (`allowCredentials: true`, explicit methods/headers per Decision 8), disable httpBasic/formLogin, remove misleading "stateless" comment. Create `AppException`, `ErrorResponse` DTO, and `@ControllerAdvice` global error handler in `common/` (all new files).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/user/` (new), `backend/src/main/java/com/botfunnel/security/SecurityConfig.java`, `backend/src/main/java/com/botfunnel/common/` (new classes)
- **Files to read:** `backend/src/main/java/com/botfunnel/security/SecurityConfig.java`

### Wave 2 (parallel, after Wave 1)

#### Task 3: Email service + templates
- **Description:** Create `EmailService` wrapping `JavaMailSender` with fire-and-forget reactive pattern (`subscribeOn(Schedulers.boundedElastic()).subscribe(null, log::error)` — errors never propagated to caller). Implement three plain-HTML email templates (classpath resources): "Verify your email" (token URL), "Reset your password" (token URL), "Your account was blocked" (SUPPORT_EMAIL). HTML-escape user-supplied data (name) before embedding. Language: Ukrainian.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/email/` (new), `backend/src/main/resources/templates/email/` (new: 3 HTML files)
- **Files to read:** `backend/src/main/resources/application.properties`

#### Task 4: Login + brute-force + session management
- **Description:** Implement `POST /api/auth/login`: dual brute-force check (per-email `brute:fail:{email}` threshold 5, per-IP `brute:fail:ip:{ip}` threshold 20), dummy BCrypt on missing user (Decision 13), session fixation prevention (invalidate pre-auth session, create new), SecurityContext injection, rememberMe TTL (30d vs 24h), brute-force counter reset on success, event logging. Implement `GET /api/auth/me`. Create `Event` @Document, `EventRepository`, `EventService`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/auth/` (new: AuthController, AuthService, DTOs, TokenService skeleton), `backend/src/main/java/com/botfunnel/events/` (new)
- **Files to read:** `backend/src/main/java/com/botfunnel/security/SecurityConfig.java`, `backend/src/main/java/com/botfunnel/user/UserRepository.java`

### Wave 3 (after Wave 2)

#### Task 5: Registration + email verification + resend
- **Description:** Implement `POST /api/auth/register` (Bean Validation via `@Valid`, duplicate/soft-delete email check, BCrypt hash, create pending User, fire-and-forget verification email). Implement `GET /api/auth/verify-email?token=` (SHA-256 lookup, expiry check, activate user). Implement `POST /api/auth/resend-verification` (public, `SET NX EX 60` regardless of email existence, always 200). Finalize `TokenService` (SecureRandom, base64url, SHA-256).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/auth/AuthController.java`, `backend/src/main/java/com/botfunnel/auth/AuthService.java`, `backend/src/main/java/com/botfunnel/auth/TokenService.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/email/EmailService.java`, `backend/src/main/java/com/botfunnel/user/UserRepository.java`

### Wave 4 (parallel, after Wave 3)

#### Task 6: Logout + password reset
- **Description:** Implement `POST /api/auth/logout` (invalidate current WebSession only). Implement `POST /api/auth/forgot-password` (always 200, generate reset token if user exists, fire-and-forget email). Implement `POST /api/auth/reset-password` (validate token, check expiry and one-time use, Bean Validation on new password, mark token used, invalidate ALL user sessions via `ReactiveMongoTemplate` query on `sessions` collection by principal name — verify field path against actual collection schema first).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/auth/AuthController.java`, `backend/src/main/java/com/botfunnel/auth/AuthService.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/auth/TokenService.java`, `backend/src/main/java/com/botfunnel/email/EmailService.java`

#### Task 7: Profile endpoints + Super Admin seed + JobRunr hard-delete
- **Description:** Create `ProfileController`/`ProfileService`: `GET /api/profile`, `PATCH /api/profile` (whitelist DTO — name only, isSuperAdmin ignored), `POST /api/profile/change-password` (BCrypt verify current, update hash, invalidate all sessions except current, log event), `POST /api/profile/terminate-all-sessions`, `DELETE /api/profile` (soft-delete, invalidate session). Create `SuperAdminSeeder` (idempotent ApplicationRunner). Create `HardDeleteJob` (JobRunr daily at 03:00, logs deleted user IDs for GDPR audit trail).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `backend/src/main/java/com/botfunnel/profile/` (new), `backend/src/main/java/com/botfunnel/admin/SuperAdminSeeder.java` (new), `backend/src/main/java/com/botfunnel/jobs/HardDeleteJob.java` (new)
- **Files to read:** `backend/src/main/java/com/botfunnel/user/UserRepository.java`, `backend/src/main/java/com/botfunnel/events/EventService.java`

### Wave 5 (parallel, after Wave 4)

#### Task 8: Frontend bootstrap
- **Description:** Update `nuxt.config.ts` to add all required modules and configure `$fetch` base URL toward backend. Install and configure Pinia (`stores/auth.ts`: user state, login/logout actions, SSR-safe `GET /api/auth/me` hydration via `useState`), Vue Query, shadcn-vue (button, input, card, form, dialog, badge), vee-validate + zod. Create `layouts/default.vue` (topbar: logo + user avatar + logout; sidebar: nav links) and `layouts/auth.vue` (centered card). Create `middleware/auth.ts` (global: unauthenticated → `/auth/login` on protected routes; authenticated → `/dashboard` on `/auth/**`).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** `cd frontend && pnpm dev` → `localhost:3000/auth/login` renders login card without console errors
- **Files to modify:** `frontend/nuxt.config.ts`, `frontend/package.json`, `frontend/stores/auth.ts` (new), `frontend/middleware/auth.ts` (new), `frontend/layouts/default.vue` (new), `frontend/layouts/auth.vue` (new), `frontend/composables/useApi.ts` (new)
- **Files to read:** `frontend/nuxt.config.ts`, `frontend/app.vue`

#### Task 9: Documentation update
- **Description:** Update `architecture.md`: remove jjwt reference, add `spring-session-data-mongodb`/`spring-boot-starter-mail`/`spring-boot-starter-validation`/`jobrunr-spring-boot-3-starter` to backend deps, update Redis scope (brute-force counters + analytics cache), add all new frontend deps, update package list. Update `deployment.md`: remove JWT_* vars, add SESSION_TTL_DEFAULT_HOURS, SESSION_TTL_REMEMBER_ME_DAYS, SUPER_ADMIN_EMAIL/PASSWORD, MAIL_*, SUPPORT_EMAIL; add Mailpit under Local Development. Update `ux-guidelines.md`: account deletion to "modal with consequences, no password required."
- **Skill:** documentation-writing
- **Reviewers:** code-reviewer
- **Files to modify:** `.claude/skills/project-knowledge/references/architecture.md`, `.claude/skills/project-knowledge/references/deployment.md`, `.claude/skills/project-knowledge/references/ux-guidelines.md`
- **Files to read:** all three target files before editing

### Wave 6 (parallel, after Wave 5)

#### Task 10: Auth pages
- **Description:** Create Nuxt pages with `layout: auth` and vee-validate/zod forms: `/auth/login` (email + password + remember-me, links to register/forgot), `/auth/register` (email + name + password + confirm-password, inline errors), `/auth/forgot-password` (email field, always shows success message after submit), `/auth/reset-password?token=` (new + confirm password, token from query), `/auth/verify-email?token=` (loading → success/error; calls backend verify; shows resend button on TOKEN_EXPIRED).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** navigate to all 5 pages, submit with invalid data, confirm inline errors appear below fields
- **Files to modify:** `frontend/pages/auth/login.vue` (new), `frontend/pages/auth/register.vue` (new), `frontend/pages/auth/forgot-password.vue` (new), `frontend/pages/auth/reset-password.vue` (new), `frontend/pages/auth/verify-email.vue` (new)
- **Files to read:** `frontend/stores/auth.ts`, `frontend/layouts/auth.vue`

#### Task 11: Profile page + dashboard
- **Description:** Create `/dashboard` page (layout: default) with pending-user banner ("Підтвердіть email для повного доступу. [Перейти до профілю]") visible when `authStore.user.status === 'pending'`. Create `/profile` page (layout: default) with sections: view/edit name, email (read-only), resend-verification button (pending only), change-password form, "Завершити всі сесії" button, "Видалити акаунт" → confirmation modal listing consequences (projects, bots, subscribers deleted).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** login as pending user → banner visible on dashboard; open profile → all sections visible; click "Завершити всі сесії" → other tabs sign out
- **Files to modify:** `frontend/pages/dashboard.vue` (new), `frontend/pages/profile.vue` (new)
- **Files to read:** `frontend/stores/auth.ts`, `frontend/layouts/default.vue`

### Audit Wave

#### Task 12: Code Audit
- **Description:** Holistic code quality review of all feature code: auth, user, profile, email, events, jobs, admin packages; SecurityConfig changes; all Nuxt frontend pages, stores, middleware, layouts. Check Spring WebFlux reactive chain correctness (no blocking calls, correct error propagation), session management patterns, naming conventions, error handling consistency.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 13: Security Audit
- **Description:** OWASP Top 10 across all auth components: token entropy and hash storage, session fixation prevention, brute-force bypass scenarios (per-IP + per-email coverage), dummy BCrypt implementation, CSRF configuration with session cookies, email enumeration prevention (timing + uniform responses), mass assignment in profile PATCH, Bean Validation completeness, HTML encoding in email templates, CORS allowCredentials setup, HttpOnly/Secure/SameSite cookie flags, sensitive data in logs.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 14: Test Audit
- **Description:** Test quality and coverage across all components. Check: Testcontainers setup correctness, integration test assertions on both HTTP status AND MongoDB/Redis state, Mailpit token extraction correctness, brute-force counter reset test present, event collection assertions after auth actions, session TTL verification via MongoDB query, dummy BCrypt timing test, change-password session invalidation test.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 15: Pre-deploy QA
- **Description:** Acceptance testing: run `./gradlew test` (all tests green, 80%+ coverage), verify all acceptance criteria from user-spec and tech-spec. Check auth endpoints, session behavior, brute-force protection (both keys), email flows via Mailpit Testcontainer, session fixation prevention, Super Admin seed idempotency, JobRunr job execution, frontend pages render and auth middleware redirects.
- **Skill:** pre-deploy-qa
- **Reviewers:** none
