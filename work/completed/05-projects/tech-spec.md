---
created: 2026-05-09
status: approved
branch: dev
size: L
---

# Tech Spec: 05-projects (Workspaces / Project CRUD + isolation primitive)

## Solution

We add the first `/api/v1/...` module — `projects` — as the **isolation primitive** for the platform. Backend exposes session-authenticated REST under `/api/v1/projects` for full CRUD + restore + soft-delete; a JobRunr `@Recurring` cron hard-deletes records older than 7 days plus their `metadata.projectId`-tagged events. Anti-enumeration: 404 uniformly on foreign / soft-deleted-by-default-GET / malformed `projectId`. Hard-cap of 5 active projects per owner is service-enforced (race-window irrelevant — limit caps attempts, not microsecond timing).

Frontend ships a Pinia `projects` store with **only** `currentProjectId` persisted to `localStorage` (cross-restart, no cross-tab sync), an inline empty-state on `/dashboard` (no `/onboarding` page), a topbar `ProjectSelector`, list page (`/projects`), settings page with name-typing delete-confirm, and a path-scoped 404 interceptor on `useApi` that auto-resets stale `currentProjectId`. Single Playwright golden-path E2E covers register → empty-state → create → rename → soft-delete → restore.

No new dependencies. Implementation reuses existing patterns: WebFlux reactive controllers + `ReactiveSecurityContextHolder` extraction, `EventService` audit log, `JobRunr @Recurring` (mirroring `HardDeleteJob`), `useApi` + `useApiError` composables, Vitest + Testcontainers + Playwright.

## Architecture

### What we're building/modifying

**Backend (new package `com.botfunnel.project` + extensions):**

- **`Project`** (`@Document("projects")`) — entity with `id`, `ownerId`, `name`, `description`, `timezone`, `createdAt`, `updatedAt`, `deletedAt`. `@Indexed` on `ownerId`; `@CompoundIndex` on `(ownerId, deletedAt)` and `(ownerId, name, deletedAt)` (both non-unique — uniqueness among active enforced at service-layer per Risk 3 in user-spec).
- **`ProjectRepository`** — `ReactiveMongoRepository<Project, String>` with derived finders: `findByIdAndOwnerId`, `findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc`, `findByOwnerIdOrderByCreatedAtDesc`, `countByOwnerIdAndDeletedAtIsNull`, `findByOwnerIdAndNameAndDeletedAtIsNull` (uniqueness pre-check on insert), `findByOwnerIdAndNameAndIdNotAndDeletedAtIsNull` (uniqueness pre-check on rename — excludes the project being renamed so AC-12b no-op rename and equivalent-case rename do not falsely collide), `findByDeletedAtBefore`.
- **`ProjectService`** — owns `requireOwned(ownerId, projectId, includeSoftDeleted): Mono<Project>` (the access guard for every project-scoped handler), `list(ownerId, includeDeleted)`, `create`, `update`, `softDelete`, `restore`. Quota check via `countByOwnerIdAndDeletedAtIsNull` before insert/restore. Name-conflict pre-check with auto-suffix `" (restored)"` on restore.
- **`ProjectController`** — `@RequestMapping("/api/v1/projects")`. Endpoints: `GET /` (with `?include_deleted=true`), `POST /`, `GET /{projectId}`, `PATCH /{projectId}`, `DELETE /{projectId}`, `POST /{projectId}/restore`.
- **DTOs** — `ProjectResponse` (record), `CreateProjectRequest`, `UpdateProjectRequest`. All carry `@JsonIgnoreProperties(ignoreUnknown = true)` (whitelist — Risk 2 mass-assignment defense).
- **`@ValidTimezone`** + validator (`com.botfunnel.project.validation`) — strict IANA via `ZoneId.getAvailableZoneIds().contains(tz)`. Null/blank delegated to `@NotBlank`.
- **`ProjectHardDeleteJob`** (`com.botfunnel.jobs`) — `@Recurring(id = "hard-delete-projects", cron = "0 3 * * *")`. Deletes projects with `deletedAt < now - 7d.plusNanos(1)` and `events` documents with `metadata.projectId ∈ deleted-set`. Emits one `project_hard_deleted` event per deleted project BEFORE the cascade so the event survives (AC-17b).
- **`AppException` factories** — extend `common/AppException.java` with `notFound(message)` (HTTP 404) and `unprocessableEntity(code, message)` (HTTP 422). Currently 404 is constructed inline in `user/UserService.java:29`; 422 has no factory — both are needed by this feature (Decision 7).

**Frontend (new files + minor edits):**

- **`types/project.ts`** — `Project` interface mirroring backend response.
- **`stores/projects.ts`** — Pinia store: `projects[]`, `currentProjectId` (with `localStorage` mirror, `import.meta.client` gated), `currentProject` computed, `isLoaded`, actions for CRUD, `selectProject`, `handleStaleCurrent`.
- **`components/ProjectSelector.vue`** — topbar dropdown. Active list (sorted `createdAt desc`), `+ Create new project` (disabled + tooltip when `length >= 5`, AC-30), `Project settings` (visible when `currentProject != null`).
- **`pages/projects/index.vue`** — list of active + "Recently deleted (X days remaining)" section with Restore buttons.
- **`pages/projects/new.vue`** — create form (name, description, timezone defaulting to `Intl.DateTimeFormat().resolvedOptions().timeZone`).
- **`pages/projects/[projectId]/settings.vue`** — general (rename, description, timezone) + Danger zone with type-the-name modal.
- **`pages/dashboard.vue`** — modify to render inline empty-state when `projectsStore.isLoaded && projectsStore.projects.length === 0`.
- **`layouts/default.vue`** — inject `<ProjectSelector />` between brand and user info; add Sidebar `Settings` link visible when `currentProject != null`.
- **`composables/useApi.ts`** — add `onResponseError` hook (currently only `onRequest` is configured) with path-scoped 404 interceptor: when path matches `^/api/v1/projects/[^/]+$` exactly AND extracted ID equals `currentProjectId`, clear `currentProjectId`, remove `localStorage` key, refetch list, auto-select per AC-20, show toast.
- **`i18n/locales/{uk,en}.json`** — new `projects.*`, `errors.projects.*`, `layout.projectSelector*`, `validation.projectName*`, `validation.timezone*` namespaces. Both locales updated atomically (parity gate).
- **`e2e/projects.spec.ts`** — single Playwright golden-path spec.

**Project Knowledge alignment (in same PR — AC-31):**
- `.claude/skills/project-knowledge/references/ux-guidelines.md:33` — `"full-page onboarding"` → `"inline empty-state on /dashboard"`. Line 43 (`localStorage`) stays as-is.

**No changes (zero-touch contract — AC-T7):** `SecurityConfig.java` (`/api/v1/projects/**` already covered by `pathMatchers("/api/**").authenticated()`), `auth.global.ts`, `EventService.java`, `JobRunrMongoConfig`, `AbstractIntegrationTest`.

### How it works

**Project create (Scenario 1 — first project):**
1. User authenticates → `auth.global.ts` allows `/dashboard` → `dashboard.vue` mounts → reads `projectsStore` (calls `fetchAll()` if `!isLoaded`).
2. Empty state renders inline (full-width "Create your first project" CTA).
3. User clicks → navigates to `/projects/new` → submits form.
4. `useApi()('/api/v1/projects', { method: 'POST', body })` → `ProjectController.create`.
5. Controller calls `currentUserId()` → `projectService.create(ownerId, dto, ip, ua)`:
   - `countByOwnerIdAndDeletedAtIsNull(ownerId) >= max` → throw `AppException.unprocessableEntity("project_limit_reached", ...)` (HTTP 422).
   - `findByOwnerIdAndNameAndDeletedAtIsNull(ownerId, name)` non-empty → throw `AppException.conflict("project_name_taken", ...)` (HTTP 409).
   - Save project; emit `project_created` audit event with `{projectId, name}` metadata.
6. 201 + `ProjectResponse`. Store appends, `selectProject(newProject.id)` → `localStorage` write, redirect to `/dashboard`.

**Auth on every request (Scenario 5 — foreign-projectId protection):**
- `currentUserId()` returns `Mono<String>` from `ReactiveSecurityContextHolder` (or `AppException.unauthorized` → 401).
- Every project-scoped handler calls `projectService.requireOwned(ownerId, projectId, includeSoftDeleted)` first. The guard uses `findById(projectId)` and throws `AppException.notFound("Project not found")` (HTTP 404) if (a) record absent, (b) `ownerId != current`, (c) `deletedAt != null` AND `includeSoftDeleted == false` (single-resource GET, PATCH, DELETE — per AC-10). For `restore` we pass `includeSoftDeleted = true`.
- Malformed `projectId` (non-`ObjectId`) — Mongo throws `IllegalArgumentException` upstream; `requireOwned` catches via `.onErrorMap(IllegalArgumentException.class, e -> AppException.notFound(...))` so all three cases collapse to 404 (AC-9).

**Soft-delete + restore (Scenario 4):**
- DELETE → `requireOwned(includeSoftDeleted=false)` then `project.deletedAt = Instant.now()`, save; emit `project_soft_deleted`. Idempotent: a second DELETE on already-soft-deleted ID returns 404 (because guard rejects it).
- POST `/restore`:
  - `requireOwned(includeSoftDeleted=true)` (allow already soft-deleted).
  - **Decision 14 guard FIRST:** if `project.deletedAt == null` → throw `AppException.notFound(...)` (uniform anti-enumeration; an active project is not restore-eligible). This guard runs BEFORE quota / rename / save logic so an active project cannot be silently mutated.
  - If `countActive >= max` → `AppException.unprocessableEntity("project_limit_reached", ...)` (422).
  - Name-conflict check among active (`findByOwnerIdAndNameAndDeletedAtIsNull`): if collision → set `name = original + " (restored)"` and remember `renamedDueToConflict = true` for the audit event.
  - `deletedAt = null`; save; emit `project_restored` (with `metadata.renamedDueToConflict = true` when applicable).
  - 200 + ProjectResponse (with possibly new name).

**Hard-delete cron (every day 03:00 UTC):**
- `findByDeletedAtBefore(Instant.now().minus(7, DAYS).plusNanos(1))` → `Flux<Project>`.
- Order matters (AC-17b — `project_hard_deleted` event must survive cascade):
  1. **Cascade-delete events first:** `template.remove(Query.query(where("metadata.projectId").in(deletedIds)), "events")`. At this point, no `project_hard_deleted` event for these IDs exists yet (this is the first hard-delete) — the cascade only sweeps prior `project_created`/`renamed`/`updated`/`soft_deleted`/`restored` events.
  2. **Emit `project_hard_deleted` events:** one per deleted project, carrying `{projectId, name}` metadata. These fresh events land in `events` AFTER step 1 finished — so they are not swept by this run's cascade and persist as the audit-trail for the hard-delete.
  3. **Delete project documents:** `template.remove(Query.query(where("_id").in(deletedIds)), "projects")`.
- INFO log line at end-of-run with `deletedCount`, `eventsRemovedCount`, `runDurationMs` (zero-running days are visible — AC-17c).

**Stale `currentProjectId` self-healing (Scenario 6):**
- `useApi.onResponseError` checks: `status === 404` AND request path matches `^/api/v1/projects/[^/]+$` AND extracted ID equals `projectsStore.currentProjectId`.
- On match: `projectsStore.handleStaleCurrent()` → clear in-memory ID + `localStorage.removeItem('bot-funnel.currentProjectId')` + `await fetchAll()` + auto-select (per AC-20) + toast.
- 404 from any other endpoint propagates unchanged.

### Shared resources

| Resource | Owner (creates) | Consumers | Instance count |
|---|---|---|---|
| `ProjectService.requireOwned` | `ProjectService` | every future `/api/v1/projects/{id}/...` module's services | 1 (singleton via Spring DI) |
| Pinia `projects` store | `stores/projects.ts` | `ProjectSelector`, `dashboard.vue`, `pages/projects/*`, `useApi` 404 interceptor | 1 (per Pinia singleton in app) |
| `localStorage` key `bot-funnel.currentProjectId` | `projects` store (`selectProject` action) | `projects` store on hydrate; 404 interceptor (defensive cleanup) | 1 string |
| JobRunr recurring slot `0 3 * * *` UTC | `ProjectHardDeleteJob`, `HardDeleteJob` (existing users) | JobRunr scheduler (serializes runs internally) | 2 jobs at same cron — Risk 5 in user-spec |

## Decisions

### Decision 1: Service-layer access guard, NOT Spring Security `@PreAuthorize`
**Decision:** A single method `ProjectService.requireOwned(ownerId, projectId, includeSoftDeleted): Mono<Project>` is the entry point for every project-scoped handler. Future epics (`bots`, `subscribers`, `funnels`, `broadcasts`) call this from their services before any other work.
**Rationale:** Supports user-spec Risk 1 mitigation ("data leakage between projects/users") and Constraint "ProjectService.requireOwned — спільний access-guard для всієї платформи". Mirrors the existing pattern of in-controller `currentUserId()` (no reactive `@AuthenticationPrincipal`); composes naturally with `Mono`-based services.
**Alternatives considered:** Spring Security `@PreAuthorize("@projectAccessGuard.check(...)")` — rejected per user-spec Технические решения: requires SpEL infrastructure and reactive `ReactiveAuthorizationManager` plumbing, overkill at current scale.

### Decision 2: 404 uniform on foreign / soft-deleted-by-default-GET / malformed `projectId`
**Decision:** Anti-enumeration. `requireOwned` throws `AppException.notFound("Project not found")` for all three cases. Single-resource GET/PATCH/DELETE treats soft-deleted-but-owned as 404 (AC-10); list-mode `?include_deleted=true` exposes them; restore passes `includeSoftDeleted=true` to find-and-resurrect.
**Rationale:** Supports user-spec AC-8/9/10 + Constraint "404 уніформно". Workflow doc had 403; user-spec deliberately overrides to 404 to prevent enumeration of project-IDs.
**Alternatives considered:** 403 + body `{code: "forbidden"}` — rejected: enumeration risk (attacker learns existence).

### Decision 3: `ZoneId.getAvailableZoneIds().contains(tz)` for IANA strictness in `@ValidTimezone`
**Decision:** Validator returns `true` only if `tz` is non-null/non-blank AND `ZoneId.getAvailableZoneIds().contains(tz)`. Pure `ZoneId.of(tz)` accepts `"GMT+5"`, `"+02:00"`, `"UT"` — these are valid `ZoneId`s but not IANA names.
**Rationale:** Supports user-spec AC-3 ("значення не в `ZoneId.getAvailableZoneIds()` → 400"). Future broadcast-scheduling needs DST-aware IANA zones; offset-only IDs would silently break "send daily at 09:00 local time" semantics.
**Alternatives considered:** Permissive `ZoneId.of(tz)` — rejected per user-spec Технические решения "Strict IANA timezone validation". Custom regex `^[A-Za-z_]+/[A-Za-z_]+$` — rejected (incomplete; also accepts non-existent `"Foo/Bar"`).

### Decision 4: Service-level uniqueness pre-check (no partial unique index)
**Decision:** Uniqueness of `(ownerId, name)` among active projects is enforced via `findByOwnerIdAndNameAndDeletedAtIsNull` pre-check inside `ProjectService.create` and `ProjectService.update` (when `name` changes). Race window 10–60ms is acceptable.
**Rationale:** Supports user-spec Constraint "уникальність name тільки серед АКТИВНИХ ... enforce на service-layer (race-window 10-60ms, обмежено бізнес-кепом 5 активних)". Spring Data `@CompoundIndex` does not support `partialFilterExpression`. Quota of 5 active per user caps abuse — no automated client can flood a user out.
**Alternatives considered:** Programmatic `IndexOps.ensureIndex` with `partialFilterExpression` — rejected for V1 (no precedent in codebase; user-spec defers to "fallback if collision appears in prod via automated client"). Full `@CompoundIndex(unique=true)` — rejected: would block re-using a soft-deleted project's name.

### Decision 5: Hard-delete cascade scope = projects + `events` only
**Decision:** `ProjectHardDeleteJob` removes the project document and `events` documents with `metadata.projectId == <id>`. No other project-scoped collections are touched (none exist yet).
**Rationale:** Supports user-spec Constraint "Cascade scope hard-delete на сьогодні — тільки сам project + events". Future epics (`04-bots`, `05-subscribers`, ...) register their own cleanup mechanisms (event listener on `project_hard_deleted` or own recurring job).
**Alternatives considered:** Stub future `cascadeDeleteByProjectIds(List<String>)` calls today — rejected: dead code, premature abstraction.

### Decision 6: HTTP status mapping — 422 for `project_limit_reached`, 409 for `project_name_taken`, 400 for bean-validation
**Decision:** Limit-violation throws `AppException.unprocessableEntity("project_limit_reached", ...)` (HTTP 422). Name-collision throws `AppException.conflict("project_name_taken", ...)` (HTTP 409). Bean-validation errors (missing `name`, invalid `timezone`, `description > 200`) surface through `WebExchangeBindException` as 400 with `{message, code: null}`.
**Rationale:** Supports user-spec AC-2/3/3b (400 for validation), AC-4/15 (422 for limit), AC-5/12b (409 for name conflict). 422 is semantically correct for "well-formed request, business rule rejects".
**Alternatives considered:** All-409 — rejected: muddles "unique conflict" with "you hit a quota". 429 for limit — rejected: 429 implies "retry after backoff", but the user must free a slot first (semantic mismatch).

### Decision 7: Extend `AppException` with `notFound(String)` and `unprocessableEntity(String, String)` factories
**Decision:** Add two factory methods to `common/AppException.java`. `notFound(message)` → `(HttpStatus.NOT_FOUND, null, message)` — single-arg, matches existing factory shape. `unprocessableEntity(code, message)` → `(HttpStatus.UNPROCESSABLE_ENTITY, code, message)` — **two-arg, intentional asymmetry** with existing factories: 422 always carries a business-rule code (`project_limit_reached` and future codes) while 400/401/403 errors flow through `WebExchangeBindException` with `code: null`. The 2-arg shape is the canonical 422 pattern going forward.
**Rationale:** Supports Decisions 2 and 6. Currently 404 is constructed inline via raw constructor (`user/UserService.java:29`); 422 has no factory. Adding factories is a strict-superset extension with no risk to existing callers.
**Alternatives considered:** Raw `new AppException(...)` at call sites — rejected: breaks the established factory pattern. Single-arg `unprocessableEntity(message)` and forcing callers to chain `.withCode(...)` — rejected: 422 with null code is meaningless (UI relies on the code for i18n key resolution per AC-14b).

### Decision 8: No brute-force / rate-limit on `/api/v1/projects/*`
**Decision:** Project endpoints rely on session auth + CSRF + the per-user 5-active quota. No Redis dual-key brute-force counter.
**Rationale:** [TECHNICAL] User-spec has no rate-limit AC. We document the unbounded vectors and accept them with operational mitigations:
- **POST `/projects` (create):** capped at 5 active by quota — quota IS the rate-limit.
- **PATCH `/projects/{id}` (update/rename):** unbounded per-project. Worst case: audit-log spam (`project_renamed`/`project_updated` events). Operational mitigation: existing Mongo write-rate alarms (planned, deployment.md monitoring). UX-only damage; no data leak.
- **DELETE + `/restore` oscillation:** unbounded. Same audit-log spam concern. Same operational mitigation.
- **404 ID-probe enumeration:** authenticated user probing `GET /api/v1/projects/{guess}` to detect ownership. Rate-unlimited, but the response is uniform (anti-enumeration per Decision 2). Mongo-hit vs Mongo-miss timing diff is theoretical; in practice both paths run a single `findById` and return identical bodies.
The `restore`/`update`/`delete` endpoints all gate via `requireOwned` → 404 on probe attempts (no information leak). Audit log volume monitoring is the operational backstop.
**Alternatives considered:** Per-user `INCR` on POST/PATCH/DELETE/RESTORE following the auth `brute:fail:*` pattern — rejected: marginal security value (no credential or data exposure to defend), adds Redis coupling for write-path latency, and there is no user-spec AC requesting it. Revisit if production audit-log volume becomes a real cost-line.

### Decision 9: Pinia store hydrates lazily; not in `auth.global.ts`
**Decision:** `projectsStore.fetchAll()` is invoked from page components that need projects (`dashboard.vue`, `ProjectSelector.vue`, `/projects` index, settings). `auth.global.ts` is NOT modified. The store has an `isLoaded` flag to dedupe concurrent calls.
**Rationale:** [TECHNICAL] User-spec mandates fetch on session start (Сценарій 2) but does not constrain WHERE the fetch happens. Adding it to `auth.global.ts` would double-fetch on every `/auth/*` route render (since middleware runs there too) and pollute the public auth-zone. Lazy hydration on first authenticated mount keeps middleware focused; AC-T7 zero-touch contract on `auth.global.ts` is preserved.
**Alternatives considered:** Eager fetch in `auth.global.ts` — rejected (above). Manual `await projectsStore.fetchAll()` in every page's `<script setup>` — rejected: error-prone, the store should self-manage via `isLoaded`.

### Decision 10: localStorage key `bot-funnel.currentProjectId`, gated with `import.meta.client`
**Decision:** Single localStorage key. Reads (in store init) and writes (on `selectProject`) are wrapped in `if (import.meta.client) { ... }`. No other Pinia state touches localStorage.

**Scope-lock for future epics:** Only **opaque resource identifiers** (ObjectId hex) may be persisted in `localStorage`. No tokens, no PII, no list data, no cached server responses. Any future epic adding a `localStorage.setItem(...)` write must amend this decision and pass a fresh security review. Enforced by AC-T12.

**Rationale:** Supports user-spec Constraint "frontend persistence — мінімальна" + AC-21 ("`import.meta.client` гейтом для SSR-safety"). Nuxt 4 SSR otherwise throws on `localStorage` access during server render. Namespacing `bot-funnel.*` is precautionary against future apps sharing the origin (no current convention — `auth` store uses `useState`, `i18n` uses cookie). XSS exposure analysis: an XSS-injected script in the same origin can already call any `/api/v1/projects/{id}/...` endpoint as the user (CSRF cookie is `httpOnly=false` by design — SPA must read it); persisting an opaque project ID in `localStorage` adds no incremental risk for this feature. The scope-lock above prevents drift in future epics where bot tokens, subscriber lists, or broadcast contents could be tempting to cache locally.
**Alternatives considered:** `useState` (Nuxt SSR-safe) — rejected: no cross-restart persistence. Cookie — rejected: server round-trip on every navigation, no UX gain over localStorage.

### Decision 11: 404 interceptor scoped to exact path `/api/v1/projects/{currentProjectId}` (no `/{module}/...` wildcard)
**Decision:** Path regex `^/api/v1/projects/[^/]+$` AND extracted ID equals `currentProjectId` AND status 404 → trigger stale-state recovery. Other 404s pass through.
**Rationale:** Supports user-spec AC-24. Future modules under `/api/v1/projects/{id}/{module}/...` (bots, subscribers) will surface their own 404 semantics ("subscriber not found", "bot disconnected") that should NOT clear the project.
**Alternatives considered:** Wildcard `/api/v1/projects/{id}/.*` — rejected (above). No interceptor — rejected: breaks Scenario 6 silently.

### Decision 12: Single list endpoint with `?include_deleted=true` query parameter
**Decision:** `GET /api/v1/projects` (default) returns active only. `?include_deleted=true` returns active + soft-deleted (each row carries `deletedAt`).
**Rationale:** Supports user-spec AC-6/7 + Технические решения "Single list endpoint з `?include_deleted=true`". One cache key, two sections rendered client-side. One endpoint = one ownership check + one quota enforcement audit point.
**Alternatives considered:** Separate `/api/v1/projects/deleted` — rejected: doubles surface area for marginal clarity.

### Decision 13: Inline empty-state on `/dashboard` (no `/onboarding` route)
**Decision:** `pages/dashboard.vue` reads `projectsStore.projects.length`. When `0` AND `isLoaded`, render full-width "Create your first project" CTA. No new `/onboarding` route.
**Rationale:** Supports user-spec Сценарій 1 + Технические решения "Inline empty-state на /dashboard замість окремої /onboarding сторінки". One fewer route = one fewer middleware concern. The same dashboard renders welcome content when `>=1` project exists (existing content stays).
**Alternatives considered:** New `/onboarding` route + middleware redirect — rejected (above). Modal on dashboard — rejected: breaks deep-link `/projects/new`.

### Decision 14: Restore on already-active project → 404 (idempotency boundary)
**Decision:** `POST /api/v1/projects/{id}/restore` on a project where `deletedAt == null` returns 404 (uniform with other "not in restore-eligible state" cases). **Implementation order is critical:** `requireOwned(includeSoftDeleted=true)` returns the project; service then checks `deletedAt != null` as the **FIRST** action after the guard, BEFORE quota check, name-conflict check, or rename logic. This ordering ensures an active project cannot accidentally enter the rename path during a refactor (defense-in-depth against future regression that might silently append `" (restored)"` to a live project name).
**Rationale:** [TECHNICAL] User-spec doesn't enumerate this case. Returning 404 is consistent with the anti-enumeration principle (Decision 2): the response is identical whether the project is missing, foreign, malformed, or already-active. UI never reaches this state through normal flow (only soft-deleted rows have a Restore button); a stale UI hitting this endpoint is treated as "no longer restore-eligible" and triggers the standard 404 toast/refetch flow. The deletedAt-FIRST ordering is verified by a unit test that calls `restore` on an active project and asserts neither `project_renamed` nor `project_restored` events are emitted.
**Alternatives considered:** 200 idempotent (treat as no-op) — rejected: signals existence of resource the caller may not own. 409 — rejected: leaks "exists but in wrong state". Skip the deletedAt check (rely on `requireOwned` only) — rejected: would silently allow restore on active.

### Decision 15: Deploy via existing CI/CD (no Deploy task in Final Wave)
**Decision:** This feature ships via the existing `main`-merge → GitHub-CI/CD path. JobRunr `@Recurring` registration happens automatically on Spring Boot startup. No `application-prod.properties` work in this PR.
**Rationale:** Supports user-spec Constraint "Production index management поза scope" + project knowledge `deployment.md` (deployment is TBD; per CLAUDE.md "ALL deployments via GitHub CI/CD only"). Final Wave only needs Pre-deploy QA. Post-deploy verification deferred until production exists.
**Alternatives considered:** Add Deploy task with `deploy-pipeline` skill — rejected: nothing to deploy beyond standard code-merge flow; would be a no-op.

## Data Models

### Backend Mongo collection `projects`

```java
@Document(collection = "projects")
@CompoundIndexes({
    @CompoundIndex(name = "owner_deleted", def = "{'ownerId': 1, 'deletedAt': 1}"),
    @CompoundIndex(name = "owner_name_deleted", def = "{'ownerId': 1, 'name': 1, 'deletedAt': 1}")
})
public class Project {
    @Id
    private String id;                                     // ObjectId hex
    @Indexed
    private String ownerId;                                // FK → users._id
    private String name;                                   // 3-50 chars, service-unique among active per ownerId
    private String description;                            // null when blank, ≤200 chars
    private String timezone;                               // IANA, e.g. "Europe/Kyiv"
    private Instant createdAt;                             // server-set at create
    private Instant updatedAt;                             // server-set on every save
    private Instant deletedAt;                             // null = active; non-null = soft-deleted
    // (production index note — same comment style as User.java:10-11 / Event.java:10-11)
}
```

### REST DTOs

```java
public record ProjectResponse(
    String id, String name, String description,
    String timezone, Instant createdAt, Instant updatedAt,
    Instant deletedAt                                       // null in default list; ISO instant in ?include_deleted view
) {}

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateProjectRequest(
    @NotBlank @Size(min = 3, max = 50) String name,
    @Size(max = 200) String description,                    // null/blank is allowed
    @NotBlank @ValidTimezone String timezone
) {}

@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateProjectRequest(
    @Size(min = 3, max = 50) String name,                   // optional — patch semantics; null means "no change"
    @Size(max = 200) String description,                    // null/blank means "clear"; controller maps blank → null
    @ValidTimezone String timezone                          // optional — null OR blank means "no change"; never persists empty string
) {}
```

**PATCH semantics (Architecture detail):** `ProjectController.update` normalizes the incoming `UpdateProjectRequest` before passing to `ProjectService.update`:
- `name`: `null` → no change; non-null → rename (with the AC-12b no-op-rename allowance + name-conflict pre-check).
- `description`: `null` → no change; blank/empty string → clear (persists as `null` in Mongo, AC-22b); non-blank → set.
- `timezone`: `null` OR blank → no change (never overwrites an active timezone with empty string); non-blank → must pass `@ValidTimezone` (AC-22c). Bean-validation rejects non-IANA values with 400 before reaching the service.

### Restore response augmentation

The restore endpoint returns `ProjectResponse` AS-IS — when the server appends `" (restored)"` to break a name conflict (AC-14), the renaming is recorded only in the audit event (`metadata.renamedDueToConflict = true`). Frontend detects renaming by comparing the response `name` with the pre-request name (stored in component state) and shows the AC-14b informational toast accordingly — no extra HTTP field needed.

### Frontend types

```ts
// frontend/types/project.ts
export interface Project {
  id: string
  name: string
  description: string | null
  timezone: string
  createdAt: string                                         // ISO instant
  updatedAt: string
  deletedAt: string | null
}
```

### Audit event types added

| Event type | Emitted on | Metadata keys |
|---|---|---|
| `project_created` | POST `/api/v1/projects` 201 | `projectId`, `name` |
| `project_updated` | PATCH 200 (no name change) | `projectId` |
| `project_renamed` | PATCH 200 (name changed) | `projectId`, `previousName`, `name` |
| `project_soft_deleted` | DELETE 200 | `projectId`, `name` |
| `project_restored` | POST /restore 200 | `projectId`, `name`, optional `renamedDueToConflict: true` |
| `project_hard_deleted` | Cron run | `projectId`, `name` (event itself survives cascade — emitted AFTER the events-cascade delete and BEFORE the project-document delete; see Architecture "Hard-delete cron" for the exact ordering that makes AC-17b hold) |

## Dependencies

### New packages

None.

### Using existing (from project)

- `spring-boot-starter-data-mongodb-reactive` — `Project` entity + `ProjectRepository` derived finders + `ReactiveMongoTemplate.remove(...)` for cascade.
- `spring-boot-starter-validation` — `@Valid`, `@NotBlank`, `@Size` on DTOs; custom `@ValidTimezone` annotation following `auth/validation/{ValidPassword,ValidPasswordValidator}.java` shape.
- `jobrunr-spring-boot-3-starter` — `@Recurring` + `@Job` on `ProjectHardDeleteJob` mirroring `jobs/HardDeleteJob.java`.
- `spring-boot-starter-webflux` + `spring-boot-starter-security` + `spring-session-data-mongodb` — controller stack, CSRF + session cookies already wired by `SecurityConfig`.
- Backend tests: `@SpringBootTest(webEnvironment=RANDOM_PORT)` + `WebTestClient` + `AbstractIntegrationTest` (Testcontainers Mongo + Redis + Mailpit) + `@WithMockAppUser` (we keep it in `profile/` for V1 — relocating to a shared test-support package is deferred to avoid scope creep).
- `JobRunrInMemoryConfig` — test-time scheduler swap (already configured).
- Frontend: `useApi`, `useApiError`, `<NuxtLinkLocale>`, `useLocalePath`, vee-validate + Zod schemas in `computed()`. shadcn-vue is listed as a dev-dep CLI but no `components/ui/*` are scaffolded today — modal/input/dropdown are built as plain Tailwind divs following the existing pattern in `pages/profile.vue:431-477` (manual ARIA: `role="dialog"`, `aria-modal="true"`, `aria-labelledby`). Per ux-guidelines.md "Functionality over aesthetics on MVP".
- E2E: existing Playwright config (`webServer: pnpm dev`, chromium-only, `reuseExistingServer: !CI`).

## Testing Strategy

**Feature size:** L

### Unit tests

Backend (`backend/src/test/java/com/botfunnel/project/`):
- `ProjectServiceTest` — limit logic (5 active vs N soft-deleted not counted); name-conflict pre-check; restore-name suffix append (case-sensitive trim-compare); `requireOwned` for happy/foreign/soft-deleted/missing/malformed projectId; `softDelete` rejects already-soft-deleted (404); `update` no-op rename does NOT trigger 409.
- `ValidTimezoneValidatorTest` — `Europe/Kyiv` → ok; `GMT+5` → reject; `+02:00` → reject; `NotAZone` → reject; null and blank → `true` (delegated to `@NotBlank`).
- `ProjectControllerSliceTest` (`@WebFluxTest(ProjectController.class)`) — minimal: only what doesn't fit in IT (e.g. happy-path response shape).
- `ProjectHardDeleteJobTest` (plain Mockito) — query is `findByDeletedAtBefore(now-7d.plusNanos(1))`; cascade order = (1) delete `events` where `metadata.projectId in deletedIds` → (2) emit `project_hard_deleted` event per deleted project → (3) delete `projects` documents (the order that makes AC-17b hold — verified by mock-call sequence assertions); structured INFO log on every run including zero-deletion days.

Frontend (`frontend/tests/`):
- `stores/projects.spec.ts` — fetch, create, soft-delete, restore actions; `selectProject` writes `localStorage`; on init reads `localStorage` and falls back to `projects[0]` when key missing/invalid; `handleStaleCurrent` clears state + `localStorage` + refetches. **SSR-context branch (Risk R6 mitigation):** with `import.meta.client = false` (via `vi.stubGlobal` or equivalent), `selectProject` does NOT call `localStorage.setItem` and does NOT throw `ReferenceError`; store init under SSR skips `localStorage.getItem`.
- `components/ProjectSelector.spec.ts` — dropdown items sorted by `createdAt desc`; "+ Create new project" disabled with correct tooltip when `length >= 5`; `Project settings` link visible only when `currentProject != null`.
- `pages/dashboard.spec.ts` — extends existing spec: when `length === 0 && isLoaded`, empty-state CTA renders; when `>=1`, welcome content renders.
- `pages/projects/new.spec.ts` — form validation (name 3-50 required, timezone defaults to `Intl.DateTimeFormat().resolvedOptions().timeZone`, description max 200); submit calls `projectsStore.create`; on success `localePath('/dashboard')` navigation.
- `pages/projects/[projectId]/settings.spec.ts` — rename → PATCH, selector reflects new name without reload; description blank → null; timezone blank submit → no PATCH issued (no-change semantics); danger-zone modal Delete button disabled until exact match; on confirm DELETE → redirect.
- `pages/projects/index.spec.ts` — Active section + "Recently deleted" section conditionally; Restore button → POST /restore. **AC-14b rename-detection (added):** when mocked store returns project with `name === preRequestName + " (restored)"`, the page invokes the toast helper with key `errors.projects.restore.renamedDueToConflict`; when response `name` unchanged, only the success toast fires.
- `composables/useApi-404-interceptor.spec.ts` — 404 from `/api/v1/projects/{currentProjectId}` triggers cleanup + toast; 404 from `/api/v1/projects/{otherId}` does NOT; 404 from sub-path `/api/v1/projects/{currentProjectId}/anything` does NOT; 404 from unrelated paths (e.g. `/api/profile`) does NOT.
- `tests/i18n/required-keys.spec.ts` (new) — imports both `uk.json` and `en.json` and asserts each AC-25-mandated key exists in both: `errors.projects.delete.confirmTypeName`, `errors.projects.unavailable`, `errors.projects.restore.renamedDueToConflict`, `projects.create.limitReachedTooltip`. Closes the gap where `check-locales.mjs` would pass even if all four are omitted from BOTH locales.

### Integration tests

Backend (`backend/src/test/java/com/botfunnel/project/`):
- `ProjectControllerIT` (extends `AbstractIntegrationTest`, uses `@WithMockAppUser`):
  - `POST /` happy 201 + audit event presence (poll via `await().atMost(...)`); assert event `metadata.projectId == response.id` AND `metadata.name == request.name`.
  - `POST /` invalid `name` length / blank / missing → 400 (assert presence in message; `code: null`)
  - `POST /` invalid `timezone` (`GMT+5`, `NotAZone`, blank) → 400
  - `POST /` `description.length > 200` → 400
  - `POST /` 6th project → 422 + code `project_limit_reached`
  - `POST /` duplicate active name → 409 + code `project_name_taken`
  - `POST /` with hostile body `{name, ownerId: "<other-user>"}` → DB record's `ownerId` equals authenticated user (mass-assignment defense). **Response-shape lock:** assert response body does NOT contain `ownerId` field (`jsonPath("$.ownerId").doesNotExist()`) — same assertion repeated for GET, PATCH, RESTORE responses.
  - `GET /` returns only own active sorted desc
  - `GET /?include_deleted=true` returns active + soft-deleted
  - `GET /{foreign-id}`, `GET /{malformed-id}`, `GET /{soft-deleted-own-id}` → 404 uniformly (AC-8/9/10)
  - `PATCH /{soft-deleted-own-id}` → 404 (single-resource soft-deleted is not editable; user must restore first)
  - `DELETE /{soft-deleted-own-id}` → 404 (already covered by "idempotent double-delete" line below — listed here for verb-parity completeness)
  - **Anti-enumeration parity across verbs (AC-8/9/10 hold for ALL methods):**
    - `PATCH /{foreign-id}` → 404
    - `PATCH /{malformed-id}` → 404
    - `DELETE /{foreign-id}` → 404
    - `DELETE /{malformed-id}` → 404
    - `POST /{foreign-id}/restore` → 404
    - `POST /{malformed-id}/restore` → 404
  - **Unauthenticated requests → 401** (using a **bare** `WebTestClient.get()/post()/patch()/delete()` — no `@WithMockAppUser`-mutated client, no `csrf()` mutator. A fresh test client confirms that `pathMatchers("/api/**").authenticated()` rule (`SecurityConfig.java:71`, which covers `/api/v1/projects/**`) fires before any other gate).
  - `PATCH /{id}` happy 200 + `project_renamed` event when name changes (assert `metadata.previousName == oldName`, `metadata.name == newName`, `metadata.projectId == id`); `project_updated` event when name unchanged (assert `metadata.projectId` only — NO `name`/`previousName` fields).
  - `PATCH /{id}` rename to existing active name → 409
  - `PATCH /{id}` rename to own name (no-op rename — same name, equivalent case) → 200, no audit event change beyond `project_updated`.
  - `PATCH /{id}` description blank → response body has `description: null`; DB document's `description` is `null` (AC-22b).
  - `PATCH /{id}` timezone blank → no-op for that field; existing timezone preserved in DB.
  - `DELETE /{id}` 200 + `project_soft_deleted` (assert `metadata.projectId`, `metadata.name`) + appears in `?include_deleted=true`
  - `DELETE /{id}` on already-soft-deleted ID (idempotent double-delete) → 404 (guard rejects via `requireOwned(includeSoftDeleted=false)`).
  - `POST /{id}/restore` happy 200 + `project_restored` (assert `metadata.projectId`, `metadata.name`)
  - `POST /{id}/restore` with name collision → suffixed name + `renamedDueToConflict=true` in event metadata
  - `POST /{id}/restore` when active count == 5 → 422
  - `POST /{id}/restore` on active project → 404 (Decision 14); assert NEITHER `project_renamed` NOR `project_restored` event was written (deletedAt-FIRST guard verified).
- `ProjectHardDeleteJobIT` (extends `AbstractIntegrationTest` + `JobRunrInMemoryConfig` swap):
  - Inject the bean; seed 2 owned projects (one >7d soft-deleted, one <7d soft-deleted) + matching events; call method.
  - Assert: only the >7d one and its events are removed; <7d untouched.
  - Assert: `events` collection contains a `project_hard_deleted` row for each deleted project (AC-17b — survives cascade); event has `metadata.projectId == deletedId` AND `metadata.name == deletedName` AND `userId == ownerId`.
  - Assert: structured INFO log line with `deletedCount`, `eventsRemovedCount`, `runDurationMs` (capture via Spring Boot `OutputCaptureExtension`).
  - Recurring registration: assert `StorageProvider.getRecurringJobs()` includes `id="hard-delete-projects"`, `cron="0 3 * * *"` via `RecurringJob.getScheduleExpression()`. JobRunr 7.3.2 exposes `getRecurringJobs()` on `StorageProvider` (not on `JobScheduler` — the earlier wording in this line was incorrect; corrected during Task 4 implementation). Autowire the `@Bean StorageProvider` from `JobRunrInMemoryConfig`.

### Tests intentionally NOT written

To pre-empt audit confusion: the following are conscious omissions, not gaps.
- **Concurrent-POST race for name uniqueness (Risk R3 — 10–60ms window).** Mitigated by the 5-active quota per Decision 4. A `partialFilterExpression` index via programmatic `IndexOps.ensureIndex` is the deferred fallback if collisions appear in production via automated clients.
- **Cron-vs-restore race (Risk R4 — millisecond window).** Frontend handles the resulting 404 via the standard stale-state flow (Scenario 6); no special test needed.
- **E2E for Scenario 6 (stale-state recovery).** Covered by `composables/useApi-404-interceptor.spec.ts` with all four path-matching cases. Adding a Playwright variant that hard-deletes a project mid-session would require API-side test fixtures or a clock-skip helper not in the existing E2E harness — disproportionate cost vs the unit coverage already in place.

### E2E tests

Frontend (`frontend/e2e/projects.spec.ts`) — single golden-path Playwright spec:
- Register fresh user → `/dashboard` shows inline empty-state CTA → click → `/projects/new` → fill form → submit → redirect to `/dashboard` with welcome content + selector populated.
- Open selector → `+ Create new project` → create 2nd project → switch via dropdown.
- Settings → rename → assert selector shows new name without reload.
- Settings → Danger zone → Delete → type-the-name modal: assert button disabled until typed name matches exactly → confirm → DELETE → redirect; project gone from selector.
- Navigate to `/projects` → "Recently deleted" section visible → Restore → project back in active list and selector.

No per-locale variants (locale switching is covered by `e2e/i18n.spec.ts`).

## Agent Verification Plan

**Source:** user-spec "Как проверить" section (AVP-агент steps 1-11).

### Verification approach

Per-task `Verify-smoke` checks are listed inline in Implementation Tasks. Pre-deploy QA (Final Wave) runs the full test suite (`./gradlew test`, `pnpm test`, `pnpm test:e2e`, `pnpm build`) and verifies all 31 user-spec ACs and tech-spec AC-T1..T10.

Post-deploy verification on a live URL (Playwright MCP golden-path, curl smokes for limit/conflict/auth/404, MongoDB MCP inspection of `projects` document, JobRunr dashboard inspection of `hard-delete-projects` recurring job) is deferred until production exists per Decision 15. When production is available, the user-spec "Как проверить" steps 1-11 become the post-deploy QA script.

### Tools required

- **Playwright MCP** — golden-path on live URL (post-deploy, when production exists)
- **curl** — direct API smoke (limit, conflict, 401, 404, malformed ID, invalid timezone)
- **MongoDB MCP** — inspect document state in `projects` and `events`
- **bash + grep** — PK alignment check (`grep -n "full-page onboarding" .claude/skills/project-knowledge/references/ux-guidelines.md` → 0 results)

## Risks

| Risk | Mitigation |
|---|---|
| **R1 (HIGH) — Data leakage between projects/users** (user-spec Risk 1) | All project-scoped handlers go through `ProjectService.requireOwned`. Code-review checklist includes "every `/api/v1/projects/{id}/*` calls requireOwned". IT covers two-user 404 uniformity. **Follow-up trigger:** when the first child epic mounts a controller under `/api/v1/projects/{projectId}/...` (04-bots), introduce a structural enforcement (ArchUnit rule or grep-based CI gate) so missing `requireOwned` becomes a CI failure, not a checklist miss. Out-of-scope here (no descendants exist yet). |
| **R2 (MEDIUM) — Mass-assignment via unknown JSON** (user-spec Risk 2) | All DTOs carry `@JsonIgnoreProperties(ignoreUnknown = true)` (whitelist). `ownerId` is read only from `SecurityContext`, never from body. IT seeds a hostile body `{name, ownerId: "<other>"}` and asserts `ownerId` in DB equals authenticated user. |
| **R3 (MEDIUM) — Mongo `@CompoundIndex` no `partialFilterExpression`** (user-spec Risk 3) | Service-layer pre-check `findByOwnerIdAndNameAndDeletedAtIsNull`. Quota of 5 active per user caps abuse. Race window 10–60ms acceptable. Future fallback: programmatic `IndexOps.ensureIndex` if automated client triggers collisions. |
| **R4 (LOW) — Race "hard-delete cron vs Restore"** (user-spec Risk 4) | Restore on already-hard-deleted ID returns 404 (record absent) — handled by the same 404 stale-state flow on frontend (toast + refetch). Race window: milliseconds. |
| **R5 (LOW) — JobRunr 03:00 cron collision with `HardDeleteJob` (users)** (user-spec Risk 5) | JobRunr serializes recurring runs internally (distributed lock). Both runs surface in `/jobrunr-dashboard`. If interleaved logs become noisy in operations, separate cron times in a follow-up. |
| **R6 (LOW) — `localStorage` written during SSR throws** | All `localStorage` accesses gated with `if (import.meta.client) { ... }`. Vitest runs Pinia actions in client mode by default; explicit SSR-context test ensures no throw. |
| **R7 (LOW) — 404 interceptor false-positive matches** | Path-regex anchored to `^/api/v1/projects/[^/]+$` (no trailing segments). Future `/api/v1/projects/{id}/{module}/*` 404s pass through to module-specific handlers. Unit test covers all 4 cases (current ID match, other ID, sub-path, unrelated endpoint). |

## User-Spec Deviations

None.

The user-spec is highly detailed; this tech-spec is a strict implementation of its requirements. Decisions 8 (no rate-limit), 9 (lazy hydration), 10 (localStorage key naming), 11 (404 path-regex shape), and 14 (restore-on-active = 404) are technical realizations of behaviors the user-spec implies but does not specify line-by-line — they do not change, contradict, or extend any user-spec requirement and are explicitly marked `[TECHNICAL]` where appropriate.

## Acceptance Criteria

Technical criteria supplementing user-spec AC-1..31:

- [ ] **AC-T1:** All public endpoints under `/api/v1/projects/*` return JSON with `Content-Type: application/json`. Error bodies always conform to `{message: string, code: string | null}` (per `common/ErrorResponse.java`).
- [ ] **AC-T2:** `AppException.notFound(String)` and `AppException.unprocessableEntity(String, String)` factories present in `common/AppException.java`. Existing call sites unchanged.
- [ ] **AC-T3:** No new dependencies added in `backend/build.gradle` or `frontend/package.json`.
- [ ] **AC-T4:** `application.properties` has `app.projects.max-per-user=${PROJECTS_MAX_PER_USER:5}`. `.env.example` has `PROJECTS_MAX_PER_USER=5`.
- [ ] **AC-T5:** `frontend/scripts/check-locales.mjs` exit code is 0 after both `uk.json` and `en.json` are updated (locale parity holds).
- [ ] **AC-T6:** `pages/dashboard.vue` does NOT call `localStorage` directly; only `stores/projects.ts` (under `import.meta.client`) does.
- [ ] **AC-T7:** No commit in this feature edits `frontend/middleware/auth.global.ts`, `backend/src/main/java/com/botfunnel/security/SecurityConfig.java`, or `backend/src/main/java/com/botfunnel/jobs/JobRunrMongoConfig.java` (zero-touch contract).
- [ ] **AC-T8:** `gitleaks` pre-commit hook passes (no new secrets in any committed file).
- [ ] **AC-T9:** No backend code references `@CompoundIndex(unique = true, partialFilter = ...)` (the unsupported feature in Spring Data MongoDB) — uniqueness lives at the service layer (Decision 4).
- [ ] **AC-T10:** Project-knowledge `ux-guidelines.md` line 33 is updated in this PR per user-spec AC-31; `git diff` of that file shows exactly one line change there.
- [ ] **AC-T11:** All Mongo queries in `com.botfunnel.project.*` use `ReactiveMongoRepository` derived finders or `Query.query(Criteria.where(field).is/in(typedValue))` with typed values. No string concatenation into `Criteria`; no use of `Criteria.where(...).is(rawJson)`. Code-reviewer enforces during Audit Wave (NoSQL injection guidance freeze for downstream epics).
- [ ] **AC-T12:** `frontend/stores/projects.ts` is the only file in this PR that calls `localStorage.setItem` (Decision 10 scope-lock for opaque-IDs-only). Verified via `grep -rn "localStorage.setItem" frontend/` → exactly one source-file match, in `stores/projects.ts`.

## Implementation Tasks

### Wave 1 (foundation, parallel)

#### Task 1: Backend foundation — AppException factories + Project domain + DTOs + ValidTimezone ✓
- **Status:** done
- **Description:** Lay down the persistence + validation primitives consumed by Wave 2. Add `AppException.notFound(message)` (1-arg) + `AppException.unprocessableEntity(code, message)` (2-arg, intentional). Create `com.botfunnel.project` package with `Project` entity, `ProjectRepository`, DTOs (whitelist via `@JsonIgnoreProperties(ignoreUnknown=true)`), and `@ValidTimezone` validator. Add `app.projects.max-per-user=${PROJECTS_MAX_PER_USER:5}` config; the quota will be injected into `ProjectService` via `@Value("${app.projects.max-per-user:5}")` in Task 3 (no `@ConfigurationProperties` class — matches current codebase pattern).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew compileJava compileTestJava` — exits 0.
- **Files to modify:** `backend/src/main/java/com/botfunnel/common/AppException.java`, `backend/src/main/resources/application.properties`, `.env.example`
- **Files to create:** `backend/src/main/java/com/botfunnel/project/Project.java`, `backend/src/main/java/com/botfunnel/project/ProjectRepository.java`, `backend/src/main/java/com/botfunnel/project/dto/ProjectResponse.java`, `backend/src/main/java/com/botfunnel/project/dto/CreateProjectRequest.java`, `backend/src/main/java/com/botfunnel/project/dto/UpdateProjectRequest.java`, `backend/src/main/java/com/botfunnel/project/validation/ValidTimezone.java`, `backend/src/main/java/com/botfunnel/project/validation/ValidTimezoneValidator.java`, `backend/src/test/java/com/botfunnel/project/validation/ValidTimezoneValidatorTest.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/auth/validation/ValidPassword.java`, `backend/src/main/java/com/botfunnel/auth/validation/ValidPasswordValidator.java`, `backend/src/main/java/com/botfunnel/profile/dto/UpdateProfileRequest.java`, `backend/src/main/java/com/botfunnel/user/User.java`, `backend/src/main/java/com/botfunnel/events/Event.java`, `backend/src/main/java/com/botfunnel/user/UserRepository.java`, `backend/src/main/java/com/botfunnel/common/ErrorResponse.java`

#### Task 2: Frontend foundation — types + Pinia projects store + i18n keys (uk + en) ✓
- **Status:** done
- **Description:** Create `types/project.ts` and `stores/projects.ts` (Pinia store with `projects[]`, `currentProjectId` mirrored to `localStorage` under `import.meta.client`, `currentProject` computed, `isLoaded`, actions `fetchAll/create/update/softDelete/restore/selectProject/handleStaleCurrent`). Add complete `projects.*`, `errors.projects.*`, `layout.projectSelector*`, `layout.sidebar.settings`, `validation.projectName*`, `validation.timezone*` namespaces to BOTH `uk.json` and `en.json` (must include `errors.projects.delete.confirmTypeName`, `errors.projects.unavailable`, `errors.projects.restore.renamedDueToConflict`, `projects.create.limitReachedTooltip`).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd frontend && node scripts/check-locales.mjs` — exits 0; `cd frontend && pnpm test -- tests/i18n/required-keys.spec.ts` — green (asserts AC-25 keys present in both locales).
- **Files to modify:** `frontend/i18n/locales/uk.json`, `frontend/i18n/locales/en.json`
- **Files to create:** `frontend/types/project.ts`, `frontend/stores/projects.ts`, `frontend/tests/stores/projects.spec.ts`, `frontend/tests/i18n/required-keys.spec.ts`
- **Files to read:** `frontend/stores/auth.ts`, `frontend/composables/useApi.ts`, `frontend/i18n/locales/uk.json`, `frontend/i18n/locales/en.json`, `frontend/scripts/check-locales.mjs`, `frontend/types/user.ts`

### Wave 2 (services + first UI, parallel — depends on Wave 1)

#### Task 3: Backend ProjectService + ProjectController + integration tests ✓
- **Status:** done
- **Description:** Implement `ProjectService` with `requireOwned(ownerId, projectId, includeSoftDeleted)`, `list(ownerId, includeDeleted)`, `create`, `update`, `softDelete`, `restore` (auto-suffix `" (restored)"` on name conflict, with `renamedDueToConflict=true` event metadata). Enforce 5-active quota (configurable via `app.projects.max-per-user`) and service-level name uniqueness. Implement `ProjectController` at `/api/v1/projects` with all 6 endpoints. Audit-log every mutation via `EventService`. Write `ProjectControllerIT` covering all user-spec AC-1..15 + AC-T1.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew test --tests 'com.botfunnel.project.*'` — green; `curl -i -X POST localhost:8080/api/v1/projects` (no session) → 401.
- **Files to create:** `backend/src/main/java/com/botfunnel/project/ProjectService.java`, `backend/src/main/java/com/botfunnel/project/ProjectController.java`, `backend/src/test/java/com/botfunnel/project/ProjectServiceTest.java`, `backend/src/test/java/com/botfunnel/project/ProjectControllerIT.java`, `backend/src/test/java/com/botfunnel/project/ProjectControllerSliceTest.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/profile/ProfileController.java`, `backend/src/main/java/com/botfunnel/profile/ProfileService.java`, `backend/src/main/java/com/botfunnel/auth/AuthService.java`, `backend/src/main/java/com/botfunnel/events/EventService.java`, `backend/src/main/java/com/botfunnel/common/GlobalErrorHandler.java`, `backend/src/test/java/com/botfunnel/AbstractIntegrationTest.java`, `backend/src/test/java/com/botfunnel/profile/ProfileControllerIT.java`, `backend/src/test/java/com/botfunnel/profile/WithMockAppUser.java`, `backend/src/main/resources/application.properties`

#### Task 4: Backend ProjectHardDeleteJob + tests ✓
- **Status:** done
- **Description:** Create `jobs/ProjectHardDeleteJob` with `@Recurring(id="hard-delete-projects", cron="0 3 * * *")`. Cascade: emit `project_hard_deleted` event BEFORE removal (event survives — AC-17b), then remove `events` with `metadata.projectId in deletedIds`, then remove projects. Emit a structured INFO log on every run with `deletedCount`, `eventsRemovedCount`, `runDurationMs` (AC-17c). Integration test asserts cron registration, cascade ordering, log presence on zero-deletion days.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew test --tests 'com.botfunnel.jobs.ProjectHardDeleteJob*'` — green.
- **Files to create:** `backend/src/main/java/com/botfunnel/jobs/ProjectHardDeleteJob.java`, `backend/src/test/java/com/botfunnel/jobs/ProjectHardDeleteJobTest.java`, `backend/src/test/java/com/botfunnel/jobs/ProjectHardDeleteJobIT.java`
- **Files to read:** `backend/src/main/java/com/botfunnel/jobs/HardDeleteJob.java`, `backend/src/test/java/com/botfunnel/jobs/HardDeleteJobTest.java`, `backend/src/test/java/com/botfunnel/JobRunrInMemoryConfig.java`, `backend/src/main/java/com/botfunnel/events/EventRepository.java`

#### Task 5: Frontend ProjectSelector component + 404 interceptor wiring in useApi
- **Description:** Create `components/ProjectSelector.vue` (dropdown over `projectsStore.projects` sorted `createdAt desc`, `+ Create new project` action, disabled with i18n tooltip when `length >= 5` per AC-30, `Project settings` link visible when `currentProject != null`). Extend `composables/useApi.ts` `onResponseError` with path-scoped 404 interceptor (regex `^/api/v1/projects/[^/]+$` AND ID matches `currentProjectId` → `projectsStore.handleStaleCurrent()` + toast). Unit-test the interceptor with all 4 cases.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** open `localhost:3000/dashboard` after creating a project → topbar shows selector with project name; click → dropdown lists projects + create + settings.
- **Files to modify:** `frontend/composables/useApi.ts`
- **Files to create:** `frontend/components/ProjectSelector.vue`, `frontend/tests/components/ProjectSelector.spec.ts`, `frontend/tests/composables/useApi-404-interceptor.spec.ts`
- **Files to read:** `frontend/components/LangSwitcher.vue`, `frontend/composables/useApiError.ts`, `frontend/layouts/default.vue`, `frontend/stores/projects.ts`, `frontend/tests/components/LangSwitcher.spec.ts`

#### Task 6: Frontend dashboard inline empty-state + /projects/new create form ✓
- **Description:** Modify `pages/dashboard.vue` to render full-width "Create your first project" CTA when `projectsStore.isLoaded && projectsStore.projects.length === 0`; otherwise existing welcome content. Create `pages/projects/new.vue` — vee-validate + reactive Zod schema for `name` (3-50), `description` (≤200), `timezone` (default `Intl.DateTimeFormat().resolvedOptions().timeZone`, dropdown from `Intl.supportedValuesOf('timeZone')`); submit calls `projectsStore.create(...)`; on success navigates to `localePath('/dashboard')`. Use `useApiError` for 400/422/409 toasts.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** new user logs in → `/dashboard` shows empty-state CTA → click → fill form → submit → redirected to dashboard with welcome content + selector populated.
- **Files to modify:** `frontend/pages/dashboard.vue`, `frontend/tests/pages/dashboard.spec.ts`
- **Files to create:** `frontend/pages/projects/new.vue`, `frontend/tests/pages/projects/new.spec.ts`
- **Files to read:** `frontend/pages/dashboard.vue`, `frontend/pages/auth/register.vue`, `frontend/tests/pages/dashboard.spec.ts`, `frontend/tests/helpers/settle.ts`

### Wave 3 (UI integration, parallel — depends on Wave 2)

#### Task 7: Frontend /projects index page (active + recently deleted with restore)
- **Description:** Create `pages/projects/index.vue`. Render two sections: "Active" (cards/rows for `projectsStore.projects`) and "Recently deleted (X days remaining)" — only when `?include_deleted=true` returns soft-deleted rows; days-remaining computed client-side from `deletedAt + 7d`. Each soft-deleted row: Restore button → `projectsStore.restore(id)` with toasts (success / 422 limit / informational `renamedDueToConflict`). Top-of-page "+ Create new project" button (disabled+tooltip when active >= 5).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** soft-delete a project from settings → navigate to `/projects` → "Recently deleted" section visible with countdown → click Restore → row moves to Active.
- **Files to create:** `frontend/pages/projects/index.vue`, `frontend/tests/pages/projects/index.spec.ts`
- **Files to read:** `frontend/stores/projects.ts`, `frontend/composables/useApiError.ts`, `frontend/pages/profile.vue`, `frontend/i18n/locales/uk.json`

#### Task 8: Frontend project settings page (rename, description, timezone, danger-zone delete)
- **Description:** Create `pages/projects/[projectId]/settings.vue`. General section: vee-validate form for `name` (3-50, optional patch), `description` (≤200; empty submits as `null`), `timezone` (IANA dropdown). Submit → `projectsStore.update(id, partial)` → 200 reflects in selector immediately. Danger zone: button opens modal with required type-the-name input (case-sensitive trim-compare); Delete button disabled until exact match; on confirm → `projectsStore.softDelete(id)` → redirect (`/dashboard` if no other active, otherwise `/projects`) + toast.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** open settings → rename → submit → topbar selector updates without reload; danger-zone modal Delete button locked until typed name exact match; confirm → redirect.
- **Files to create:** `frontend/pages/projects/[projectId]/settings.vue`, `frontend/tests/pages/projects/settings.spec.ts`
- **Files to read:** `frontend/pages/profile.vue`, `frontend/i18n/locales/uk.json`, `frontend/stores/projects.ts`, `frontend/composables/useApiError.ts`

#### Task 9: Frontend layout integration + Project Knowledge alignment ✓
- **Status:** done
- **Description:** Edit `layouts/default.vue` — inject `<ProjectSelector />` between brand and user info; add Sidebar `Settings` link visible when `currentProject != null` linking to `/projects/${currentProject.id}/settings`. Update `.claude/skills/project-knowledge/references/ux-guidelines.md` line 33: `"full-page onboarding..."` → `"inline empty-state on /dashboard"` (per user-spec AC-31). Line 43 stays unchanged.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `grep -n "full-page onboarding" .claude/skills/project-knowledge/references/ux-guidelines.md` returns 0 results.
- **Verify-user:** any authenticated page → topbar shows brand | selector | user info | logout | LangSwitcher; sidebar shows Dashboard | Settings (when project selected) | Profile.
- **Files to modify:** `frontend/layouts/default.vue`, `.claude/skills/project-knowledge/references/ux-guidelines.md`
- **Files to read:** `frontend/layouts/default.vue`, `frontend/components/ProjectSelector.vue`, `.claude/skills/project-knowledge/references/ux-guidelines.md`

### Wave 4 (E2E — depends on Wave 3)

#### Task 10: Playwright golden-path E2E spec ✓
- **Description:** Create `frontend/e2e/projects.spec.ts` covering the full golden path: register fresh user → `/dashboard` empty-state → create first project → see in selector → create second → switch via selector → settings rename → assert selector update without reload → soft-delete with name-typing modal → "Recently deleted" section visible on `/projects` → restore → active again. No per-locale variants. Uses existing `playwright.config.ts`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd frontend && pnpm test:e2e` — green (golden-path spec passes).
- **Files to create:** `frontend/e2e/projects.spec.ts`
- **Files to read:** `frontend/e2e/i18n.spec.ts`, `frontend/playwright.config.ts`

### Audit Wave

#### Task 11: Code Audit ✓
- **Status:** done
- **Description:** Full-feature code quality audit. Read all source files created/modified in this feature (from decisions.md + tech-spec "Files to modify"). Review holistically for cross-component issues: duplicate resource initialization, shared resources compliance with Architecture decisions (`requireOwned` is the single ownership entry point), architectural consistency (DTOs whitelist, currentUserId pattern reused, no edits to forbidden zero-touch files per AC-T7). Write audit report.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 12: Security Audit ✓
- **Status:** done
- **Description:** Full-feature security audit. Read all source files created/modified in this feature. Analyze for OWASP Top 10 across all components, cross-component auth/data flow (every project-scoped handler goes through `requireOwned`; no `ownerId` read from request body; mass-assignment defense via `@JsonIgnoreProperties(ignoreUnknown=true)`; 404 uniformity for anti-enumeration; CSRF inherited from `SecurityConfig`; no new secrets). Write audit report.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 13: Test Audit ✓
- **Status:** done
- **Description:** Full-feature test quality audit. Read all test files created in this feature. Verify coverage of user-spec AC-1..31 + tech-spec AC-T1..T10, meaningful assertions (not just status codes — assert event presence, document state, locale parity, log line content), and test pyramid balance (heavy IT for backend per `patterns.md`, focused vitest for components/stores, single E2E golden path). Write audit report.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 14: Pre-deploy QA
- **Status:** done
- **Description:** Acceptance testing: run `./gradlew test`, `pnpm test`, `pnpm test:e2e`, `pnpm build`. Verify all user-spec AC-1..31 and tech-spec AC-T1..T10 are met. Run user-spec AVP-агент steps 2-11 locally (curl checks for limit/conflict/auth/404, MongoDB inspection of `projects` document, JobRunr recurring registration via integration test, PK alignment grep). Manually verify `Локально перед merge` checklist from user-spec.
- **Skill:** pre-deploy-qa
- **Reviewers:** none
