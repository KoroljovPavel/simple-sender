# Code Research: 05-projects (Workspaces / Project CRUD)

Sources read:
- `.claude/skills/project-knowledge/references/{architecture,patterns,project,deployment,ux-guidelines}.md`
- `backend/src/main/java/com/botfunnel/**` (full tree)
- `backend/src/main/resources/application.properties`, `backend/src/test/resources/application-test.properties`
- `backend/src/test/java/com/botfunnel/{AbstractIntegrationTest,JobRunrInMemoryConfig,profile/*,auth/*,jobs/*,security/*}.java`
- `backend/build.gradle`
- `frontend/{nuxt.config.ts,playwright.config.ts,vitest.config.ts,package.json}`
- `frontend/{layouts/default.vue,middleware/auth.global.ts,stores/auth.ts,types/user.ts,composables/{useApi,useApiError}.ts,pages/{dashboard,profile}.vue,e2e/i18n.spec.ts,i18n/locales/{uk,en}.json}`
- `frontend/tests/{stores/auth,pages/{dashboard,profile}}.spec.ts`, `frontend/tests/helpers/settle.ts`
- `work/completed/{02-auth,04-remember-me-cookie}/{code-research,decisions}.md` (excerpts)

The `work/05-projects/{user-spec.md,tech-spec.md}` files at the time of this research were the unfilled templates; everything below is grounded in current source. The orchestrator's feature summary is treated as authoritative scope.

---

## 1. Existing patterns I MUST reuse

### 1.1 REST controller shape (WebFlux + reactive return + current-user)

Pattern source: `backend/src/main/java/com/botfunnel/profile/ProfileController.java`.

- Class annotation: `@RestController` + `@RequestMapping("/api/profile")` (constructor injection of the service).
- Every handler returns `Mono<ResponseEntity<T>>` (or `Mono<ResponseEntity<Void>>`).
- Validation: `@Valid @RequestBody DTO` — bean-validation errors surface through `GlobalErrorHandler`'s `WebExchangeBindException` handler as HTTP 400 with `ErrorResponse{message, code}`.
- Current-user extraction is done **inside the controller** via a private static helper, not via `@AuthenticationPrincipal` (reactive principal injection is brittle with the project's manual auth wiring). Copy this verbatim into `ProjectController`:

```java
// ProfileController.java:75-81
private static Mono<String> currentUserId() {
    return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .filter(a -> a != null && a.isAuthenticated() && a.getPrincipal() instanceof AppUserDetails)
            .map(a -> ((AppUserDetails) a.getPrincipal()).id())
            .switchIfEmpty(Mono.error(AppException.unauthorized("Not authenticated")));
}
```

- IP / User-Agent extraction (only needed if a project endpoint emits an audit event with these): `ProfileController.java:88-98` (`extractIp`) and `:83-86` (`capUserAgent`) — same logic also lives at `AuthService.java:642-653`. **The helper is currently duplicated** between `ProfileController` and `AuthService` — when a third copy is added for Projects, consider promoting to `common/` (open question, see §5).

Auth controller `AuthController.java` differs in that it takes `ServerWebExchange` directly because login flows write request-scoped cookie attrs. Project controller does NOT need the exchange for normal CRUD — only for endpoints that want IP/UA in `events`.

### 1.2 Service / repository pattern (reactive Mongo)

Two co-existing styles in the codebase, both required:

| Concern | Use | Examples |
|---|---|---|
| Simple by-id / derived finders, basic save/delete | `extends ReactiveMongoRepository<T, String>` + custom finder methods | `user/UserRepository.java:9-18`, `events/EventRepository.java` |
| Atomic dynamic queries, count, deleteMany, projections | `ReactiveMongoTemplate` injected into the service | `auth/AuthService.java:84,107`, `profile/ProfileService.java:42,108-125` |

For the project module both will be needed:

- `ProjectRepository extends ReactiveMongoRepository<Project, String>` for `save`, `findById`, derived finders such as `findByOwnerIdAndDeletedAtIsNull`, `findByOwnerIdAndDeletedAtBefore` (used by hard-delete job), and `findByOwnerIdAndNameAndDeletedAtIsNull` (uniqueness pre-check before insert).
- `ReactiveMongoTemplate` for the project-count quota check (`countByOwnerIdAndDeletedAtIsNull`) and any bulk operation (e.g. cascade delete of project-scoped events in the hard-delete job).

Pinning of CPU-heavy work to `Schedulers.boundedElastic()` (e.g. `auth/AuthService.java:418-423`) is **not** relevant for projects: there is no BCrypt or other CPU-bound work on the project happy path.

### 1.3 Audit log via `EventService`

- Type: `events/EventService.java:21-27` exposes a single fire-and-forget method:
  ```java
  public void logEvent(String userId, String eventType, String ipAddress,
                       String userAgent, Map<String, Object> metadata)
  ```
- It calls `eventRepository.save(event).subscribe(null, err -> log.error(...))`. Failures are swallowed — never break the user-facing flow.
- `eventType` is an open string (no enum). Existing values in the codebase: `login_success`, `login_failed`, `email_verified`, `password_reset_requested`, `password_changed`, `account_deleted` (`auth/AuthService.java:69-73`, `profile/ProfileService.java:30-31`).
- **Forbidden in metadata:** raw secrets, raw email-of-unknown-user (anti-enumeration). For projects, safe metadata keys: `projectId`, `name` (the user's own project name), `previousName` for rename. **Do NOT** put `ownerId` in metadata — `userId` already carries it.
- Consumers of `events` pollable in tests: `EventRepository.findAll().filter(e -> "...".equals(e.getEventType())).blockFirst()` (e.g. `profile/ProfileControllerIT.java:174-184`).

Suggested project event types (open enum extension): `project_created`, `project_renamed`, `project_updated` (timezone/description-only edits), `project_soft_deleted`, `project_restored`, `project_hard_deleted`. Names are snake_case to match existing style — confirm in tech-spec.

### 1.4 JobRunr recurring job — exact wiring

Reference: `backend/src/main/java/com/botfunnel/jobs/HardDeleteJob.java`.

- Class-level `@Component`. Method-level `@Recurring(id = "hard-delete-users", cron = "0 3 * * *")` + `@Job(name = "...")`.
- JobRunr resolves `@Recurring` annotations at startup and registers them with the recurring scheduler. Distributed lock guarantees one execution across replicas (per `patterns.md` line 59).
- Cron is 5-field Unix; UTC unless `zoneId` is set. Daily at 03:00 UTC has no DST risk.
- Idempotency strategy: cutoff is `Instant.now().minus(RETENTION).plusNanos(1)` so a record exactly at the boundary is included; the finder is reactive `Flux<User>` collected via `.collectList().blockOptional()` — the explicit `.block()` is acceptable here because JobRunr workers are not on the Reactor event loop. Logs include the deleted IDs (GDPR audit trail).
- Storage provider for the recurring scheduler: `JobRunrMongoConfig.java` supplies a synchronous `com.mongodb.client.MongoClient` (JobRunr's `MongoDBStorageProvider` does not support the reactive driver). `uuidRepresentation=STANDARD` is required since MongoDB Java Driver 4.x.
- Test wiring: `backend/src/test/java/com/botfunnel/JobRunrInMemoryConfig.java` swaps in `InMemoryStorageProvider` and `application-test.properties` sets `org.jobrunr.background-job-server.enabled=false` so registration runs but no workers spawn. New job tests just inject the `@Component` and call the recurring method directly (`jobs/HardDeleteJobTest.java:55,89`).

Direct copy-template for the projects hard-delete job:

```java
@Component
public class ProjectHardDeleteJob {
    private static final Duration RETENTION = Duration.ofDays(7);
    @Recurring(id = "hard-delete-projects", cron = "0 3 * * *")
    @Job(name = "Hard delete soft-deleted projects")
    public void hardDeleteSoftDeletedProjects() { ... }
}
```

Cascade decision (per orchestrator scope: "also clears project-scoped events"): on hard delete, ALSO remove `events` documents that carry `metadata.projectId == <projectId>` for the deleted IDs. Use `ReactiveMongoTemplate.remove(Query.query(Criteria.where("metadata.projectId").in(deletedIds)), "events")`. **Open question (§5):** confirm cascade scope — the only project-scoped collection that exists today is `events`; once `bots`/`subscribers`/etc. land, this job must grow. Explicitly out-of-scope here.

### 1.5 Spring Security WebFlux config — adding `/api/v1/projects/**`

`backend/src/main/java/com/botfunnel/security/SecurityConfig.java:68-73`:

```java
.authorizeExchange(exchanges -> exchanges
    .pathMatchers("/health").permitAll()
    .pathMatchers("/api/auth/**").permitAll()
    .pathMatchers("/api/**").authenticated()
    .anyExchange().authenticated()
)
```

`/api/v1/projects/**` already falls under `pathMatchers("/api/**").authenticated()` — **no change to `SecurityConfig` is required** for the happy path. The `/api/auth/**` permitAll is BEFORE the `/api/**` authenticated rule (first-match — see `patterns.md` Spring Security section). Any new `permitAll` rule (none expected here) MUST go before the `/api/**` authenticated catch-line.

CSRF: `CookieServerCsrfTokenRepository.withHttpOnlyFalse()` writes the `XSRF-TOKEN` cookie; SPA (`useApi`) reads it via `document.cookie` and echoes as `X-XSRF-TOKEN` on non-safe methods (`composables/useApi.ts:1-23,49-52`). Tests mutate via `SecurityMockServerConfigurers.csrf()` (`profile/ProfileControllerIT.java:26,93`). Project endpoints inherit this for free; tests for POST/PATCH/DELETE just need `.mutateWith(csrf())`.

### 1.6 Brute-force / rate-limit (Redis dual-key)

Pattern: `auth/AuthService.java:490-565` (login dual-key — `brute:fail:{email}` + `brute:fail:ip:{ip}`); single-key per-IP versions for register/forgot-password (`auth/AuthService.java:452-475,266-279`); single-key per-userId for change-password (`profile/ProfileService.java:167-181`). All use `INCR` + `EXPIRE 900` on first set, `DEL` on success, fail-open on Redis errors.

**Decision deferred to tech-spec:** project endpoints are CSRF-protected, session-authenticated, and the worst case is creating ≤5 spam projects (the per-user quota itself caps abuse). I do NOT recommend brute-force throttling here. Document the "no rate-limit needed" decision explicitly in tech-spec so security-auditor doesn't flag it.

### 1.7 Error response shape

- Wire format: `common/ErrorResponse.java` — `record ErrorResponse(String message, String code)` (JSON: `{"message": "...", "code": "..."}`, where `code` is null for most errors).
- Throw site: `common/AppException.java` factory methods — `badRequest`, `unauthorized`, `forbidden`, `conflict`, `tooManyRequests`. **Note: there is no `notFound` factory** — the projects feature needs 404, so I'll either (a) add `AppException.notFound(...)` factory (preferred — minor extension), or (b) construct directly via `new AppException(HttpStatus.NOT_FOUND, null, "Not found")` (already done in `user/UserService.java:29`). The factory is the cleaner route.
- Handler: `common/GlobalErrorHandler.java`:
  - `AppException` → status from `ex.getStatus()`, body `{message, code}`
  - `WebExchangeBindException` (bean validation) → 400, body message is comma-joined `field: defaultMessage` pairs
  - `ResponseStatusException` → status from exception, body `{message: reason, code: null}`
  - Catch-all `Throwable` → 500, body `{message: "Internal server error", code: null}`, exception logged

Status-code conventions in use: 200 (PATCH success returning resource), 201 (register), 400 (bad input / validation / wrong current password), 401 (no session / status-gated), 403 (blocked user, account-level only), 409 (duplicate email / soft-delete window collision), 429 (rate-limited). For projects: 200/201/400/401/404/409/429 covered; 422 is **NOT used** — bean-validation errors come back as 400.

**Anti-enumeration rule for the project-access guard:** per orchestrator scope, "exists+wrong-owner" returns the same 404 as "does not exist". Mirror the pattern — never throw 403 for ownership mismatch. The factory call is `AppException.notFound("Project not found")` (or whatever the agreed UA copy is).

### 1.8 Custom validation annotations

Reference: `auth/validation/{ValidPassword,ValidPasswordValidator}.java`.

- `@Constraint(validatedBy = ...Validator.class)` on a `@Target({ElementType.FIELD, ElementType.PARAMETER})` `@Retention(RUNTIME)` annotation.
- Validator implements `ConstraintValidator<Annotation, String>`. Null/blank is **delegated to `@NotBlank`** — the validator returns `true` for null/empty so the user doesn't see two stacked errors.

For the project module:

- `@ValidProjectName` (3-50 chars) is **likely overkill**: built-in `@NotBlank` + `@Size(min=3, max=50)` covers the spec. Recommend NOT adding a custom annotation unless we add character-class restrictions later. Same shape used by `profile/dto/UpdateProfileRequest.java:13-16` (`@NotBlank @Size(max = 200)`).
- `@ValidTimezone` IS warranted because `ZoneId.of(...)` is the validation oracle — built-in annotations cannot express IANA TZ membership. Implementation:
  ```java
  public boolean isValid(String tz, ConstraintValidatorContext ctx) {
      if (tz == null || tz.isBlank()) return true;  // @NotBlank handles required
      try { ZoneId.of(tz); return true; }
      catch (DateTimeException ex) { return false; }
  }
  ```
  **Risk:** `ZoneId.of("GMT+5")` accepts offset IDs that are technically "zone IDs" but not IANA. If we need strict IANA, gate on `ZoneId.getAvailableZoneIds().contains(tz)` instead. Open question §5.

`@JsonIgnoreProperties(ignoreUnknown = true)` on every request DTO (`profile/dto/UpdateProfileRequest.java:10`) — required for whitelist semantics; without it, mass assignment (e.g. a client posting `ownerId`) could bypass server-side ownership. Apply to all project DTOs.

### 1.9 Account-deletion confirmation UX (NOT a name-typing pattern)

The orchestrator brief said: "profile.vue allegedly has account-deletion confirmation. Find it and document the exact UX so projects-delete matches."

**Finding:** the profile.vue account-delete UX is a **plain confirmation modal** — NO name typing. See `frontend/pages/profile.vue:431-477` and `i18n/locales/uk.json:129-142`:
- Modal with a bulleted list of consequences (`modalProjects`, `modalBots`, `modalSubscribers`).
- Two buttons: `Cancel` and `Delete account` (red).
- No text input. The `confirmDelete()` handler (`profile.vue:201-213`) just calls `DELETE /api/profile`.

The "type the project name to confirm" requirement comes from `ux-guidelines.md` line 38: **"Project deletion: requires typing the project name to confirm"** — this is project-specific and is NOT modeled in any existing component. The new ProjectSettings page is the FIRST place this UX appears; there is no template to copy. Recommended shape (matches shadcn-vue conventions):

- Open confirm modal.
- Show an `<input>` with a placeholder/label "Type `<project-name>` to confirm".
- "Delete" button is `disabled` until `input.value === project.name` (case-sensitive trim-compare).
- On confirm: `DELETE /api/v1/projects/{id}`. On 200: navigate to project list / onboarding.

Note: the existing modal in `profile.vue:431-477` uses fixed positioning with manual ARIA (`role="dialog"`, `aria-modal="true"`, `aria-labelledby`). Reuse that scaffold; just add the typed-confirm input.

### 1.10 Frontend default layout — where to inject the project selector

Reference: `frontend/layouts/default.vue:18-50`.

Topbar structure (current):
```
<header>
  <NuxtLinkLocale to="/dashboard">{{ brand.name }}</NuxtLinkLocale>
  <div class="flex items-center gap-3">
    <span>{user name + email}</span>
    <button @click="onLogout">Logout</button>
    <LangSwitcher />
  </div>
</header>
```

`ux-guidelines.md` line 19 prescribes: `logo | project selector (dropdown) | user avatar → Profile / Logout | LangSwitcher`. The project selector is **NOT** present today — the entire space between the logo and the user info is empty. Inject the new `ProjectSelector.vue` between the `NuxtLinkLocale` brand and the right-flex group. The current layout has no slot system; just edit the file.

There is also **no Sidebar** today (`ux-guidelines.md` lists Dashboard / Subscribers / Funnels / Broadcasts / Settings, but `default.vue:38-43` only renders `<aside>` with two links: Dashboard and Profile). Adding `Settings` (project settings) is part of this feature; other sidebar entries are deferred.

### 1.11 `useApi` / `useApiError` — exact signatures

`useApi` (`frontend/composables/useApi.ts`):

```ts
export function useApi(): typeof $fetch  // really $fetch.create(...)
// Usage: const data = await useApi()<TypedResponse>('/api/v1/projects', { method: 'GET' })
//        await useApi()('/api/v1/projects', { method: 'POST', body: { ... } })
```

Behavior:
- SSR base = `runtimeConfig.apiBaseSsr` (defaults `http://localhost:8080`).
- Browser base = `runtimeConfig.public.apiBase` (relative — same-origin via `nitro.devProxy /api → :8080/api`).
- `credentials: 'include'` so the SESSION cookie travels.
- SSR forwards the incoming request's `Cookie` header; client pulls from `document.cookie`.
- For non-safe methods (POST/PATCH/DELETE) it auto-sets `X-XSRF-TOKEN` from the `XSRF-TOKEN` cookie. **No project-side work needed for CSRF.**

`useApiError` (`frontend/composables/useApiError.ts`):

```ts
export function useApiError(): (error: unknown, contextKey: string) => string
// Call once at <script setup> top: `const apiError = useApiError()`
// Then per-call:                  `apiError(err, 'projects.create')`
```

Behavior:
- Status pulled via `error.statusCode ?? error.status ?? error.response?.status`, normalized through `Number()`.
- Looks up `errors.{contextKey}.{status}` → `errors.{contextKey}.generic` → `errors.generic`. Each candidate is `te()`-gated to avoid leaking missing keys.
- For project endpoints, populate i18n under `errors.projects.{create,rename,delete,restore}.{400,401,403,404,409,429,generic}` plus an `errors.projects.generic`. Match the `errors.profile.{action}.*` 3-level shape (`uk.json:173-200`) for consistency.

### 1.12 Pinia stores — where `useProjectStore` should live

- Existing store: `frontend/stores/auth.ts` (defineStore('auth', () => { ... composition API }) using `useState<User | null>('auth-user', ...)` for SSR-safe state).
- Folder convention is `frontend/stores/<name>.ts`. Place new store at `frontend/stores/projects.ts` with id `'projects'`.
- **Bootstrap:** `auth.global.ts` middleware calls `authStore.fetchUser()` once on first protected route. The new project store must NOT auto-fetch — wait until the topbar selector or onboarding page mounts. Otherwise we double-fetch on every public auth route render.
- Required state surface (informed by orchestrator scope):
  - `projects: Project[]` (current user's ACTIVE projects, sorted by createdAt desc).
  - `currentProjectId: string | null` (persisted to `localStorage` per ux-guidelines line 43).
  - `currentProject` (computed lookup).
  - `isLoaded: boolean`.
  - actions: `fetchAll()`, `create({name, description, timezone})`, `rename(id, name)`, `update(id, partial)`, `softDelete(id)`, `restore(id)`, `selectProject(id)`.
- `localStorage` key: `bot-funnel.currentProjectId` (namespaced — there is no existing convention to follow because no other store touches localStorage today; the auth flow uses `useState`, and i18n uses cookie `i18n_lang`).
- SSR caveat: do NOT touch `localStorage` during SSR. Wrap reads/writes in `if (import.meta.client)`. (See `composables/useApi.ts:5-22` for the parallel `import.meta.server` pattern.)

### 1.13 i18n key structure

- Locale files: `frontend/i18n/locales/{uk,en}.json`. Top-level namespaces in use: `brand`, `common`, `layout`, `validation`, `auth.{login,register,forgotPassword,resetPassword,verifyEmail}`, `dashboard`, `profile.{personalData,emailVerification,changePassword,sessions,deleteAccount}`, `errors.{generic, login, register, forgotPassword, resetPassword, verifyEmail, profile.{saveName,changePassword,resend,terminateSessions,deleteAccount}}`.
- New namespace to add: top-level `projects` for UI copy, `errors.projects` for API-error messages, plus a few keys under `layout` (e.g. `layout.projectSelector`, `layout.createFirstProject`) and `validation` (e.g. `validation.projectNameMin3`, `validation.projectNameMax50`, `validation.timezoneRequired`, `validation.timezoneInvalid`).
- **Build gate:** `frontend/scripts/check-locales.mjs` runs at `prebuild` and exits 1 on key divergence between `uk.json` and `en.json` (`patterns.md` line 64, `package.json:7`). Every key added in one locale MUST be added to the other or `pnpm build` fails. This is a CI gate.

---

## 2. Files / classes the new module will touch or extend

### 2.1 Backend

| File | Change |
|---|---|
| `backend/src/main/java/com/botfunnel/project/Project.java` | NEW. `@Document("projects")` with fields per orchestrator scope. `@Indexed` on `ownerId`, partial unique index on `(ownerId, name)` where `deletedAt IS NULL` — see §4.5 for the partial-index gotcha. |
| `backend/src/main/java/com/botfunnel/project/ProjectRepository.java` | NEW. `extends ReactiveMongoRepository<Project, String>`. Derived finders: `findByIdAndOwnerIdAndDeletedAtIsNull`, `findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc`, `countByOwnerIdAndDeletedAtIsNull`, `findByDeletedAtBefore`, `findByOwnerIdAndNameAndDeletedAtIsNull` (uniqueness pre-check before insert/rename so we surface 409 cleanly without depending on duplicate-key exception parsing). |
| `backend/src/main/java/com/botfunnel/project/ProjectService.java` | NEW. Methods: `list(ownerId)`, `create(ownerId, dto, ip, ua)`, `get(ownerId, projectId)` (the access guard), `update(ownerId, projectId, dto)`, `softDelete(ownerId, projectId, ip, ua)`, `restore(ownerId, projectId, ip, ua)`. Quota check via `ReactiveMongoTemplate` (or repo `count*`) before insert. |
| `backend/src/main/java/com/botfunnel/project/ProjectController.java` | NEW. `@RequestMapping("/api/v1/projects")`. GET `/`, POST `/`, GET `/{projectId}`, PATCH `/{projectId}`, DELETE `/{projectId}`, POST `/{projectId}/restore`. Each calls `currentUserId()` → `projectService.*`. |
| `backend/src/main/java/com/botfunnel/project/dto/{ProjectResponse,CreateProjectRequest,UpdateProjectRequest}.java` | NEW. Records / classes with `@JsonIgnoreProperties(ignoreUnknown = true)` (whitelist DTOs — see `profile/dto/UpdateProfileRequest.java:10`). |
| `backend/src/main/java/com/botfunnel/project/validation/{ValidTimezone,ValidTimezoneValidator}.java` | NEW. Shape mirrors `auth/validation/{ValidPassword,ValidPasswordValidator}.java`. |
| `backend/src/main/java/com/botfunnel/project/ProjectAccessGuard.java` (or method on service) | NEW. Reusable lookup that returns `Mono<Project>` if `(id == project.id) && (ownerId == project.ownerId) && (project.deletedAt == null)`, else `Mono.error(AppException.notFound(...))`. Future `/api/v1/projects/{id}/...` modules call this before any work. **Open question §5:** module location and exact API. |
| `backend/src/main/java/com/botfunnel/jobs/ProjectHardDeleteJob.java` | NEW. `@Component`, `@Recurring(id = "hard-delete-projects", cron = "0 3 * * *")`. Hard-deletes records where `deletedAt < now - 7d.plusNanos(1)` and removes `events` documents whose `metadata.projectId` is in the deleted set. **Note: existing `HardDeleteJob` (users) also runs at 03:00 — both jobs share the cron slot. JobRunr serializes recurring runs internally; no conflict, but log lines will interleave.** |
| `backend/src/main/java/com/botfunnel/common/AppException.java` | EXTEND. Add `public static AppException notFound(String message)` factory. Currently 404 is constructed inline (`user/UserService.java:29` is the only existing call site). |
| `backend/src/main/java/com/botfunnel/security/SecurityConfig.java` | NO CHANGE. `/api/v1/projects/**` already authenticated via `pathMatchers("/api/**").authenticated()` rule (`SecurityConfig.java:71`). |
| `backend/src/main/java/com/botfunnel/events/EventService.java` | NO CHANGE. Open string enum — new event-type constants live in the `project` package as `private static final String EVENT_PROJECT_CREATED = "project_created"` etc., mirroring `profile/ProfileService.java:30-31` and `auth/AuthService.java:69-73`. |
| `backend/src/main/resources/application.properties` | EXTEND. Add `app.projects.max-per-user=${PROJECTS_MAX_PER_USER:5}`. |
| `.env.example` | EXTEND. Add `PROJECTS_MAX_PER_USER=5` if env-overridable. |

### 2.2 Frontend

| File | Change |
|---|---|
| `frontend/types/project.ts` | NEW. `interface Project { id, ownerId, name, description, timezone, deletedAt: string \| null, createdAt, updatedAt }` (deletedAt is reserved for the restore-list view; for the active-list endpoint it's always null). |
| `frontend/stores/projects.ts` | NEW. See §1.12 for surface. |
| `frontend/components/ProjectSelector.vue` | NEW. Topbar dropdown — list active projects + a `+ Create` action that opens a modal or navigates. Hidden when zero projects (which means the user is on the onboarding empty-state page anyway). |
| `frontend/components/ProjectDeleteConfirmDialog.vue` | NEW (or inline in settings). Type-the-name confirmation. |
| `frontend/pages/projects/index.vue` | NEW. List of active projects + restore-deleted section. Or merge with onboarding empty-state. |
| `frontend/pages/projects/new.vue` | NEW. Create form. Or use a modal on the list page — UX decision. |
| `frontend/pages/projects/[projectId]/settings.vue` | NEW. Rename, timezone, description, soft-delete with name-typing confirmation. **Note:** the route name will resolve to `projects-projectId-settings` — `auth.global.ts` middleware gates by base name, and unknown route names redirect unauthenticated users to login (fail-closed). Authenticated users pass through, so no change to `auth.global.ts:38-40` is needed UNLESS we want a "0 projects → /onboarding" redirect inside this middleware (recommended NOT — let the page handle that to keep middleware focused). |
| `frontend/pages/onboarding.vue` (or `frontend/pages/dashboard.vue` extension) | NEW empty-state. Per `ux-guidelines.md:33`: "Zero projects → full-page onboarding with step-by-step 'Create your first project'". **Open question §5:** is dashboard with-zero-projects allowed (current shows welcome + description) or do we redirect from middleware? |
| `frontend/layouts/default.vue` | EDIT. Inject `<ProjectSelector />` into the topbar between brand and user info. Add Sidebar link to "Settings" (project settings) when `currentProject != null`. |
| `frontend/i18n/locales/uk.json` and `frontend/i18n/locales/en.json` | EXTEND. Add `projects.*`, `errors.projects.*`, plus `layout.projectSelector*`, `validation.projectName*`, `validation.timezone*`. **Both locales must change in lock-step or `prebuild` fails.** |
| `frontend/middleware/auth.global.ts` | NO CHANGE recommended. Project-level access is enforced server-side (404 on owner mismatch); the middleware should not reach for project state. The "0 projects → onboarding" decision belongs to page-level logic. |
| `frontend/e2e/i18n.spec.ts` | NO CHANGE. Existing single E2E spec is locale-only. |
| `frontend/e2e/projects.spec.ts` | NEW. Single golden-path Playwright spec — login → create project → see in selector → rename → soft-delete → restore. |

---

## 3. Tests already in place I should match

### 3.1 Backend integration tests

- **Base class:** `backend/src/test/java/com/botfunnel/AbstractIntegrationTest.java`. JVM-singleton Testcontainers (`MongoDBContainer mongo:8.0`, `GenericContainer redis:7.4-alpine`, `MailpitContainer axllent/mailpit:v1.29.7`) started in a static block. `@DynamicPropertySource` rewrites `spring.data.mongodb.uri` and `spring.data.redis.url`. Per-test `webTestClient = WebTestClient.bindToApplicationContext(applicationContext)` — REQUIRED so `SecurityMockServerConfigurers.csrf()` mutator works. **Side effect:** `Set-Cookie` headers are NOT propagated through the bind-to-context client (`work/completed/04-remember-me-cookie/code-research.md:108`). For project tests this doesn't matter — no cookies are set on project endpoints.
- **Test profile:** `application-test.properties` sets `org.jobrunr.background-job-server.enabled=false`. JobRunr `@Recurring` registration still runs at startup against the in-memory storage provider supplied by `JobRunrInMemoryConfig.java`. For testing the projects hard-delete job: inject the bean directly and call the method, exactly like `jobs/HardDeleteJobTest.java`.
- **Auth in tests:** `profile/WithMockAppUser.java` + `WithMockAppUserSecurityContextFactory.java`. Annotate test method with `@WithMockAppUser(userId = "...")` to inject `AppUserDetails` into the SecurityContext. The userId field is overwritten per-test to match a seeded user. **Reuse this annotation directly** — it lives in `profile/` but is package-default visibility... actually, it's `public` (verified by usage in IT). For project tests:
  ```java
  @Test
  @WithMockAppUser(userId = OWNER_ID)
  void createProject_validData_201() {
      webTestClient.mutateWith(csrf())
          .post().uri("/api/v1/projects")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(Map.of("name", "My Project", "timezone", "Europe/Kyiv"))
          .exchange()
          .expectStatus().isCreated()
          .expectBody().jsonPath("$.id").isNotEmpty();
  }
  ```
  **Question §5:** should `WithMockAppUser` move from `profile/` to a shared test utility location (e.g. `com.botfunnel.testsupport`)? Currently it's reused only by `ProfileControllerIT` — adding a third consumer (`ProjectControllerIT`) is a fine reason to relocate.
- **Cleaning state:** `@BeforeEach` deletes from the relevant repositories and `redisTemplate.delete(redisTemplate.scan()).block()` for Redis (`auth/AuthControllerIT.java:60-68`, `profile/ProfileControllerIT.java:39-44`). For project tests: clear `userRepository`, `projectRepository`, `eventRepository`, `sessions`. No Redis state for project endpoints.
- **Polling for fire-and-forget events:** `await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100)).until(...)` — `profile/ProfileControllerIT.java:174-184`. Use the same shape to assert `project_created` audit log appears after POST.

### 3.2 Backend unit tests

Slice tests use `@WebFluxTest(SomeController.class)` + `@MockitoBean` for service deps + the standard three infra mocks (`MongoClient`, `RedisConnectionFactory`, `ReactiveRedisConnectionFactory`) per `patterns.md` line 38. See `auth/AuthControllerSliceTest.java` for the pattern.

Service unit tests use plain Mockito: `auth/AuthServiceTest.java`, `profile/ProfileServiceTest.java`, `events/EventServiceTest.java`. No Spring context — fast.

### 3.3 Frontend unit tests (Vitest + Nuxt test-utils)

- Config: `frontend/vitest.config.ts` — `environment: 'nuxt'` (provides `mockNuxtImport`, `mountSuspended`, `useState`, etc).
- Pattern: `frontend/tests/pages/profile.spec.ts` (full surface) and `frontend/tests/pages/dashboard.spec.ts` (smaller). Mock `useApi`, `useAuthStore`, `useLocalePath`, `navigateTo` via `vi.hoisted` + `mockNuxtImport`. `setActivePinia(createPinia())` in `beforeEach`. Use `data-test="..."` selectors verbatim with `wrapper.find('[data-test="..."]')`.
- The `helpers/settle.ts` helper handles vee-validate's microtask races: `await flushPromises(); await new Promise(r => setTimeout(r, 50)); await flushPromises(); await nextTick()`. Always call `await settle()` after triggering form submit / state change.
- Stores: `frontend/tests/stores/auth.spec.ts`. `useState<unknown>('auth-user').value = null` resets the SSR-shared state between tests.
- Component tests: `frontend/tests/components/LangSwitcher.spec.ts`. Same `mountSuspended` shape.

UA literal asserts: existing tests assert against Ukrainian text directly (`/Підтвердіть email/i` in `dashboard.spec.ts:43`). Default locale is `uk`; tests pass without locale switching. The single `mockNuxtImport('useLocalePath', () => () => (path: string) => path)` identity passthrough is permitted (and required) per `patterns.md` line 73 (AC18 exception).

### 3.4 Frontend E2E tests (Playwright)

Single existing spec: `frontend/e2e/i18n.spec.ts`. Config (`playwright.config.ts`): chromium-only, `webServer: { command: 'pnpm dev', reuseExistingServer: !CI }`. baseURL `http://localhost:3000`.

For projects: ONE golden-path spec `frontend/e2e/projects.spec.ts`. Pattern from existing spec — `await page.goto('/auth/login')` → fill form → assert URL/text → `data-test="..."` selectors.

The orchestrator scope says "one Playwright golden-path spec" — keep scope tight. The spec must run against a real backend (Testcontainers are not used in E2E; the Playwright webServer just spawns `pnpm dev`). **Open question §5:** does the E2E spec need to seed a user in the backend, or can it register through the UI? Today's `i18n.spec.ts` does not log in, just stays on `/auth/login`.

---

## 4. Risks / gotchas surfaced from existing code

### 4.1 `default.vue` — handling zero projects

`default.vue` currently has no awareness of project state — it only depends on `authStore.user`. There is no built-in "if user has 0 projects, redirect to onboarding" hook. Adding it requires either:
- (a) page-level logic in `dashboard.vue` (and any future `/projects/{id}/...`-prefixed page) to redirect when `projectsStore.projects.length === 0`. Pro: explicit and avoids middleware-fanout bugs.
- (b) a new global middleware after `auth.global.ts`. Risk: middleware order & re-fetching project list on every navigation.
- I recommend (a). Doc the decision in tech-spec.

### 4.2 `/api/v1` route conflicts

Currently: `/api/auth/**` (permitAll), `/api/profile`, `/api/profile/change-password`, `/api/profile/terminate-all-sessions`. **No** routes under `/api/v1/` exist today. The orchestrator chose `/api/v1/projects` — this is the FIRST `/api/v1/...` route in the codebase. No conflicts. But:

- The existing `/api/auth/**` and `/api/profile/**` are NOT versioned. New `/api/v1/...` lives next to them. This is an intentional spec choice (per orchestrator) — not a regression. But document it in tech-spec so future epics know to use `/api/v1/...` for new work.
- `SecurityConfig.java:71` `pathMatchers("/api/**").authenticated()` covers `/api/v1/**` — no edit needed.

### 4.3 Mongo `sessions` collection — index collisions

`spring-session-data-mongodb` manages `sessions`, including a TTL index on `expireAt` (auto-created at startup; documented in `architecture.md:53`). The new `projects` collection lives in a different collection, so direct collisions are impossible. But:

- Auto-index creation is GLOBAL (`spring.data.mongodb.auto-index-creation=true`, `application.properties:6`). Adding `@Indexed` annotations to `Project` fields will cause Mongo index creation at startup. This is fine — `User` and `Event` already do this.
- **Partial unique index on `(ownerId, name)` where `deletedAt IS NULL`:** Spring Data MongoDB's `@CompoundIndex` does NOT support partial filter expressions natively. Options:
  - (a) Live with a non-unique compound index; enforce uniqueness in the service layer with a `findByOwnerIdAndNameAndDeletedAtIsNull` pre-check + handle the rare race via a duplicate-key-style guard (`AppException.conflict` — but ONLY for the active duplicate, not soft-deleted).
  - (b) Drop down to `MongoIndex` programmatically via `ReactiveMongoTemplate.indexOps("projects").ensureIndex(...)` at startup with an `IndexDefinition` carrying `partialFilterExpression`. There is precedent for runtime-created infrastructure (`JobRunrMongoConfig`) but no precedent for programmatic indexes — would be a first.
  - I recommend (a) for V1 — service-level guard is sufficient since the quota is 5 active per user (collisions are vanishingly rare). Document the decision in tech-spec.

### 4.4 Spring Session `principal` field path

Already locked in by IT `sessionsCollection_principalFieldPath_isAtTopLevel` (referenced in `auth/AuthService.java:386-389`, `profile/ProfileService.java:110-115`). For projects this only matters if a project deletion needs to invalidate all of the owner's sessions — **not in scope**. Project endpoints don't touch sessions.

### 4.5 No request-scoped context in `common`

Searched `backend/src/main/java/com/botfunnel/common/` — only `AppException`, `ErrorResponse`, `GlobalErrorHandler`, `package-info`. There is NO request-scoped context bean (e.g. a `CurrentUserContext` filter). The pattern is: every controller calls `currentUserId()` from `ReactiveSecurityContextHolder`. The `ProjectAccessGuard` should follow the same pattern — accept `ownerId` as a parameter, do not auto-inject.

A future refactor could hoist `currentUserId()` into a shared utility (it's currently duplicated in `ProfileController`, `AuthService`, and any future controller). **Open question §5:** ride along with this feature, or defer? Recommend defer — the duplication is small and the pattern is uniform.

### 4.6 IP/UA extraction duplication

Same observation: `extractIp` is duplicated between `ProfileController.java:88-98` and `AuthService.java:642-653`. If the project module needs IP/UA in audit events (recommended yes — for `project_created` etc.), we'll get a third copy. **Open question §5:** introduce `common/RequestMeta.java` static utility, or accept the duplication?

### 4.7 `auto-index-creation=true` in dev, `false` planned for prod

`application.properties:6` is `true`. `User.java:10-11` and `Event.java:10-11` carry comments warning that production sets it to `false` and indexes must be manually created. There is no `application-prod.properties` yet — so production override is a future concern, not a blocker. But: for any `@Indexed` annotation added to `Project`, the same comment must be added so the deploy-prep epic catches it.

### 4.8 `terminateAllSessions` does NOT clear cookies

Documented in `work/completed/04-remember-me-cookie/code-research.md:80-83`. Not relevant for project endpoints (we don't terminate sessions). Worth knowing in case a future "delete project + log user out" feature appears (out of scope here).

### 4.9 Frontend SSR — `localStorage` usage is unsafe in SSR

Pinia store using `localStorage` for `currentProjectId` MUST gate reads/writes behind `import.meta.client`. The auth store doesn't use localStorage (uses Nuxt's `useState` for SSR-safe state). No current pattern to copy — first store to need localStorage.

### 4.10 Testcontainers cold-start time

`AbstractIntegrationTest` boots Mongo + Redis + Mailpit. First test class adds ~10–15s. Subsequent classes reuse via the static singleton. Adding `ProjectControllerIT` increases JVM-test wall-clock by ~3–6s (test count, not container boot). Acceptable.

### 4.11 `auth.global.ts` middleware — fail-closed behavior

`auth.global.ts:38-40`: unknown route names + unauthenticated user → redirect to login. Project routes all resolve to known names (`projects-index`, `projects-new`, `projects-projectId-settings`), so authenticated users pass through; unauthenticated users are redirected to login (correct). No change needed.

### 4.12 Auto-login on register & "0 projects" UX

`auth/AuthService.java:166-170`: register auto-opens a session, so a freshly registered user lands on `/dashboard`. With this feature, that landing page must either (a) show the onboarding empty-state itself, (b) redirect to `/onboarding`, or (c) redirect to `/projects` which renders empty-state. Choice belongs to tech-spec; flag in §5.

---

## 5. Open questions for Cycle 2

1. **Project-access guard location & shape.** Method on `ProjectService.requireOwned(ownerId, projectId)` returning `Mono<Project>`, OR standalone `ProjectAccessGuard` bean injected into other modules' services? Future bot/funnel/broadcast modules will all need it.
2. **Cascade scope of hard-delete.** Today only `events` is project-scoped (and even that is documented as containing arbitrary `metadata` — the use of `metadata.projectId` is by convention, not enforced). Should the hard-delete job also `deleteByProjectId` from collections that don't yet exist (`bots`, `subscribers`, `funnels`, `broadcasts`, `tags`, `api_keys`)? Recommend: design the job so a single `cascadeDeleteByProjectIds(List<String>)` method exists in each future module, called from this job — but for now only events.
3. **`@ValidTimezone` strictness.** Allow `ZoneId.of(...)` permissive set (includes `GMT+5`, `UTC`, etc.), OR strict IANA-only via `ZoneId.getAvailableZoneIds().contains(tz)`?
4. **i18n keys for delete-confirm typing-prompt.** `projects.delete.modalTypeNameToConfirm` placeholder pattern — `"Type {name} to confirm"` or `"Введіть назву проекту: {name}"`? Need UA copy approved.
5. **Onboarding flow.** After register/login with 0 projects: (a) `/dashboard` shows "Create first project" inline; (b) redirect to `/onboarding`; (c) redirect to `/projects` which shows empty-state. Pick one.
6. **`/api/v1/...` versioning convention.** Is this only for new modules (projects, bots, etc.) or are existing `/api/profile`, `/api/auth` going to be moved/aliased? Recommend: only new modules under `/api/v1`; existing endpoints stay where they are. Document in tech-spec.
7. **Move `WithMockAppUser` to a shared test-support package?** Currently lives in `profile/`. Adding a second consumer is the threshold to relocate.
8. **`AppException.notFound(...)` factory.** OK to add to `common/AppException.java` as part of this feature (small, isolated)?
9. **IP/UA extraction utility.** Promote `extractIp` / `capUserAgent` to `common/RequestMeta.java`, or accept third duplication?
10. **Topbar selector default state.** When `currentProjectId` is null (fresh user, never selected) AND `projects.length > 0`, do we auto-select the first / most recently created project, or show "(no project selected)"? Recommend auto-select most-recently-created on first render.
11. **Project list endpoint shape.** Just active projects, or split into `?include_deleted=true` for the restore-pending list? My read: the orchestrator scope says "list / get one / restore" → restore-list view needs a way to fetch soft-deleted records within their 7-day window. Either (a) filter on the same endpoint via query param, or (b) a separate `/api/v1/projects/deleted` endpoint. Pick one.
12. **Description field — empty-string vs null.** Spec says 0–200 chars. Stored as null when empty, or always a string? Affects DTO `@JsonInclude` behavior and UI default values.
13. **Audit event for soft-delete cascade-clear of events.** When the daily job hard-deletes a project AND wipes its events, do we emit a final `project_hard_deleted` event into `events` itself? Self-reference is fine because that event is per-user (not per-project) — but the metadata can still carry `projectId`. Recommend yes.
14. **CSRF on E2E.** Playwright spec going through real `pnpm dev` will hit the real backend. The CSRF cookie/header dance happens automatically because `useApi` reads the cookie. Confirm in spec planning that we don't need explicit token handling.
