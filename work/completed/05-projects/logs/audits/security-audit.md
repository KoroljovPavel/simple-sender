# Security Audit: 05-projects

**Auditor:** main agent (Task 12 — Security Audit)
**Scope:** Tasks 1–10 (Waves 1–4) of the `05-projects` feature.
**Methodology:** OWASP Top 10 (2021) review across all source files created/modified in this feature, content-read of AC-T7 zero-touch files, dependency vulnerability scan, and verification of the six cross-component invariants documented in tech-spec.md Decisions 1, 2, 8, 10, 11, 14.
**Date:** 2026-05-12.

---

## Executive Summary

**Verdict:** **PASS** — 0 Critical / 0 High / 0 Medium / 2 Low / 4 Informational findings.

Every project-scoped backend handler routes through `ProjectService.requireOwned` (the single ownership entry point). Mass-assignment is defended at two layers (DTO whitelist + response-shape lock); 404 anti-enumeration is uniform across all four documented branches (foreign / soft-deleted / malformed / restore-on-active) and exercised by integration tests; CSRF + session-cookie filter chain is unmodified (AC-T7 zero-touch verified by content-read); no new dependencies were introduced (`pnpm audit --prod` exits clean, "No known vulnerabilities found"); audit-log emissions whitelist specific metadata keys (no bulk-DTO leak); `localStorage` writes are SSR-gated; no `v-html` in any in-scope component or page. The two Low-severity findings concern (F-1) the `findByIdAndOwnerId` derived finder being defined but never used (dead-code risk if a future caller copies the pattern from `requireOwned` without it), and (F-2) `dashboard.vue` and `pages/projects/index.vue` calling `projectsStore.fetchAll()` fire-and-forget without `.catch()` (unhandled rejection on transient backend failure — robustness gap, not a vulnerability). Informational items document architectural confirmations for future epics.

The audit is a hard gate before Final Wave; **proceed to Task 14 (Pre-deploy QA)**.

---

## Files Audited

### Backend (Java) — main sources

- `backend/src/main/java/com/botfunnel/common/AppException.java` (modified — added `notFound`/`unprocessableEntity` factories)
- `backend/src/main/java/com/botfunnel/project/Project.java`
- `backend/src/main/java/com/botfunnel/project/ProjectRepository.java`
- `backend/src/main/java/com/botfunnel/project/ProjectService.java`
- `backend/src/main/java/com/botfunnel/project/ProjectController.java`
- `backend/src/main/java/com/botfunnel/project/dto/ProjectResponse.java`
- `backend/src/main/java/com/botfunnel/project/dto/CreateProjectRequest.java`
- `backend/src/main/java/com/botfunnel/project/dto/UpdateProjectRequest.java`
- `backend/src/main/java/com/botfunnel/project/validation/ValidTimezone.java`
- `backend/src/main/java/com/botfunnel/project/validation/ValidTimezoneValidator.java`
- `backend/src/main/java/com/botfunnel/jobs/ProjectHardDeleteJob.java`
- `backend/src/main/java/com/botfunnel/events/EventService.java` (modified — new blocking variant; broader zero-touch advisory, see code audit F1)

### Backend (config)

- `backend/src/main/resources/application.properties` (added `app.projects.max-per-user`)
- `.env.example` (added `PROJECTS_MAX_PER_USER`)

### Backend (tests — security-relevant assertions only)

- `backend/src/test/java/com/botfunnel/project/ProjectControllerIT.java`
- `backend/src/test/java/com/botfunnel/project/ProjectControllerSliceTest.java`
- `backend/src/test/java/com/botfunnel/project/ProjectServiceTest.java`
- `backend/src/test/java/com/botfunnel/project/validation/ValidTimezoneValidatorTest.java`
- `backend/src/test/java/com/botfunnel/jobs/ProjectHardDeleteJobIT.java`

### Frontend (Vue/TS — main sources)

- `frontend/types/project.ts`
- `frontend/stores/projects.ts`
- `frontend/composables/useApi.ts` (modified — 404 interceptor)
- `frontend/components/ProjectSelector.vue`
- `frontend/pages/dashboard.vue` (modified — empty-state)
- `frontend/pages/projects/new.vue`
- `frontend/pages/projects/index.vue`
- `frontend/pages/projects/[projectId]/settings.vue`
- `frontend/layouts/default.vue` (modified — selector slot + settings link)
- `frontend/i18n/locales/uk.json`
- `frontend/i18n/locales/en.json`

### E2E

- `frontend/e2e/projects.spec.ts`

### Shared infrastructure (read-only)

- `backend/src/main/java/com/botfunnel/security/SecurityConfig.java` (AC-T7 zero-touch — content-read verified)
- `backend/src/main/java/com/botfunnel/common/GlobalErrorHandler.java` (error-body shape pre-existing baseline)
- `backend/src/main/java/com/botfunnel/jobs/JobRunrMongoConfig.java` (AC-T7 zero-touch — content-read verified)
- `frontend/middleware/auth.global.ts` (AC-T7 zero-touch — content-read verified)

**Out of scope (per task spec):** `auth/`, `profile/`, `email/`, `events/`, `user/` baseline packages; future epics; production deploy posture.

---

## OWASP Top 10 (2021) Verdicts

### A01 Broken Access Control — **OK**

**Evidence:** `ProjectService.requireOwned(ownerId, projectId, includeSoftDeleted)` (`ProjectService.java:50-64`) is the single entry point.

- `ProjectController.getOne` (line 60), `ProjectController.update` (via `ProjectService.update`, `ProjectService.java:102`), `ProjectController.softDelete` (via `ProjectService.softDelete`, line 141), `ProjectController.restore` (via `ProjectService.restore`, line 154) all invoke `requireOwned` before any read or mutation.
- `list` (`ProjectService.java:66-70`) and `create` (line 72-98) operate on `ownerId` directly — they never accept an external `projectId`, so `requireOwned` is not applicable.
- `ProjectHardDeleteJob` (`ProjectHardDeleteJob.java:48-105`) derives the deletion set from `findByDeletedAtBefore(cutoff)` — no caller-supplied ID enters the cron path.
- `grep -rn "ownerId.equals\|ownerId =="` in `com.botfunnel.project.*` → **exactly one match** at `ProjectService.java:56` inside `requireOwned`. No bypass surfaces elsewhere.
- `ProjectRepository.findByIdAndOwnerId` is defined but never called from any source file (see F-1 below). The only call to `projectRepository.findById(...)` is inside `requireOwned` itself (line 51).
- `ProjectController` never calls `projectRepository.findById` directly (verified via `grep -n "projectRepository\." backend/src/main/java/com/botfunnel/project/ProjectController.java` → 0 matches; the controller only knows the service).

**Cross-evidence from tests:** `ProjectControllerIT.getProject_foreignId_returns404` (line 348), `getProject_softDeletedOwnId_returns404` (line 366), `patchProject_foreignId_returns404` (line 391), `deleteProject_foreignId_returns404` (line 415), `restoreProject_foreignId_returns404` (line 435), `restoreProject_onActiveProject_returns404AndDoesNotEmitRenameOrRestoreEvent` (line 727) — all six branches asserted.

### A02 Cryptographic Failures — **OK**

**Evidence:** No new crypto in this feature.

- No password storage, JWT signing, encryption-at-rest, or key derivation introduced — those live in `SecurityConfig.java` (BCrypt at strength 12) and are unmodified (zero-touch).
- Session cookies are unchanged; CSRF token storage in `XSRF-TOKEN` cookie with `httpOnly=false` (so SPA can read it) is documented in `SecurityConfig.java:55-65` and unchanged.
- `localStorage` value `bot-funnel.currentProjectId` is an opaque server-issued ObjectId hex — not a token, not PII (`stores/projects.ts:4-32`).

### A03 Injection — **OK**

**Evidence:** All Mongo queries are parameterized.

- Repository uses derived finders (`ProjectRepository.java:11-23`) — Spring Data generates parameterized BSON queries from method names. No raw `Query`/`Criteria` with string concatenation.
- The only manual `Query`/`Criteria` constructions live in `ProjectHardDeleteJob.java:82-101`:
  - `Criteria.where("metadata.projectId").in(deletedIds)` — `deletedIds` is `List<String>` typed from the cron-local derived set.
  - `Criteria.where("_id").in(deletedIds)` — same typed list.
  - **No** string concat; **no** `$where` (server-side JS evaluation); **no** raw JSON literals into `Criteria`.
- `findByOwnerIdAndNameAndDeletedAtIsNull(ownerId, name)` uses the `name` parameter verbatim through the BSON driver, which escapes string values. No regex/`$where` evaluation.
- AC-T11 verified — see code audit (Task 11) finding catalogue, all matches typed.
- No SQL is used anywhere in the codebase (Mongo-only stack).

### A04 Insecure Design — **OK (with one Low-severity robustness gap)**

**Evidence:** Anti-enumeration uniform 404 enforced at the `requireOwned` guard and verified in tests.

- `requireOwned` (`ProjectService.java:50-64`) collapses four cases to the same `AppException.notFound(MESSAGE_NOT_FOUND)`:
  1. `findById` returns empty → `switchIfEmpty(notFound)` (line 54).
  2. `IllegalArgumentException` on malformed ObjectId → `onErrorMap(...)` (line 52-53).
  3. Foreign owner → explicit `Mono.error(notFound)` (line 56-58).
  4. Soft-deleted on `includeSoftDeleted=false` → `Mono.error(notFound)` (line 59-61).
- Decision 14 deletedAt-FIRST guard on `restore` (`ProjectService.java:160-162`) runs **before** quota / name-conflict / save — verified by `ProjectControllerIT.restoreProject_onActiveProject_returns404AndDoesNotEmitRenameOrRestoreEvent` (line 727) which also asserts ZERO `project_*` events were emitted, proving the guard short-circuits.
- `GlobalErrorHandler.handleAppException` (`GlobalErrorHandler.java:20-24`) emits `{message, code}` body; for 404 the code is `null`, asserted by `errorBodies_alwaysHaveMessageAndCode` (`ProjectControllerIT.java:751-801`).
- See **F-2** for the fire-and-forget `fetchAll()` robustness gap — not a security vulnerability, recorded under Insecure Design per task hint #3.

### A05 Security Misconfiguration — **OK**

**Evidence:** `SecurityConfig.java` zero-touch; `application.properties` addition is benign.

- `SecurityConfig.java` content-read verified — no reference to `projects` or `/api/v1/projects/**`. Existing rules:
  - `pathMatchers("/health").permitAll()` — health probe (pre-existing).
  - `pathMatchers("/api/auth/**").permitAll()` — auth endpoints (pre-existing).
  - `pathMatchers("/api/**").authenticated()` — covers `/api/v1/projects/**` transitively.
  - `anyExchange().authenticated()` — fail-closed default.
- `JobRunrMongoConfig.java` content-read verified — only defines the JobRunr sync client; no security relevance.
- `auth.global.ts` content-read verified — only enumerates `PUBLIC_ROUTE_NAMES = [auth-login, auth-register, auth-forgot-password, auth-reset-password, auth-verify-email]`. Project routes are NOT public; they fall into the fail-closed `!isPublicAuth && user === null → redirect /auth/login` branch (line 38-40).
- New config keys:
  - `application.properties:30` `app.projects.max-per-user=${PROJECTS_MAX_PER_USER:5}` — env-driven int, default 5. No credential exposure.
  - `.env.example:31` `PROJECTS_MAX_PER_USER=5` — example value, no secret.
- No new `permitAll`, no new `csrf().disable()`, no new public path under `/api/v1/projects/**`. AC-T7 strict PASS.

### A06 Vulnerable and Outdated Components — **OK**

**Evidence:** No new dependencies introduced.

- `git log --oneline 8d446f9^..HEAD -- backend/build.gradle frontend/package.json frontend/pnpm-lock.yaml` → **0 commits** touched any manifest file.
- AC-T3 PASS — confirmed by tech-spec Dependencies "None" section.
- `cd frontend && pnpm audit --prod` → `No known vulnerabilities found` (advisory, not gating).
- Backend: no `./gradlew dependencies` run needed since no new dep was introduced; existing dep tree unchanged.

### A07 Identification and Authentication Failures — **OK**

**Evidence:** `currentUserId()` is the only auth source.

- `ProjectController.currentUserId()` (`ProjectController.java:125-131`) extracts `userId` from `ReactiveSecurityContextHolder.getContext() → SecurityContext.getAuthentication() → AppUserDetails::id`. `switchIfEmpty(unauthorized)` on missing principal — fail-closed.
- This is byte-for-byte identical to `ProfileController.currentUserId()` (`ProfileController.java:75-81`) — same auth contract.
- No controller method derives identity from a header, query param, or body field. All five endpoints (`list/create/getOne/update/softDelete/restore`) start their chain with `currentUserId().flatMap(ownerId -> ...)` (lines 42, 52, 59, 71, 81, 91).
- `@AuthenticationPrincipal` is not used (Decision 1 alternatives — out of scope).
- Mass-assignment attempt where a hostile body sets `ownerId` is silently dropped by `@JsonIgnoreProperties(ignoreUnknown=true)` on `CreateProjectRequest` and `UpdateProjectRequest` (DTOs:11-12 in both files). Verified by `ProjectControllerIT.create_hostileBodyOwnerId_isIgnored` (line 270-280) which asserts "hostile body's ownerId must NOT overwrite authenticated user".
- `WithMockAppUser` test fixture (referenced throughout `ProjectControllerIT`) confirms tests bind a real `AppUserDetails` principal through the same security stack.

### A08 Software and Data Integrity Failures — **OK**

**Evidence:** Mass-assignment defense at two layers.

- **Request side:**
  - `CreateProjectRequest` (`dto/CreateProjectRequest.java:11-16`) — record with whitelisted fields (`name`, `description`, `timezone`); `@JsonIgnoreProperties(ignoreUnknown = true)` discards unknown keys including `ownerId`, `id`, `deletedAt`.
  - `UpdateProjectRequest` (`dto/UpdateProjectRequest.java:12-17`) — same pattern.
- **Response side:**
  - `ProjectResponse` (`dto/ProjectResponse.java:5-15`) — record with NO `ownerId` field. Line 5 comment: "NO ownerId field — response-shape lock for Risk R2 mass-assignment defense."
  - Verified by `ProjectControllerIT` line 112, 274, 307, 343 (`$.ownerId / $[0].ownerId / ... .doesNotExist()`).
- **Entity binding:** `Project` entity is never `@RequestBody`-bound; only DTO records cross the wire (`ProjectController.java:48, 65`).
- **Deserialization risk:** Jackson default-typing is not enabled in the project; no `@JsonTypeInfo` polymorphism on DTOs. No Java deserialization attack surface in WebFlux JSON binding.

### A09 Security Logging and Monitoring — **OK**

**Evidence:** Every mutation emits an audit event; metadata is whitelisted.

- `ProjectService.create` → `project_created` event, `Map.of("projectId", saved.getId(), "name", saved.getName())` (line 94-96).
- `ProjectService.update` (rename branch) → `project_renamed`, `{projectId, previousName, name}` (line 126-131); (non-rename branch) → `project_updated`, `{projectId}` (line 132-133).
- `ProjectService.softDelete` → `project_soft_deleted`, `{projectId, name}` (line 147-150).
- `ProjectService.restore` → `project_restored`, `{projectId, name, renamedDueToConflict?}` (line 183-192). The `renamedDueToConflict` key is only set when the auto-suffix path fired (line 187-189).
- `ProjectHardDeleteJob` → `project_hard_deleted`, `{projectId, name}` per project, emitted BEFORE the cascade so the event survives (`ProjectHardDeleteJob.java:88-96`). AC-17c structured INFO log on every run, including zero-deletion days (line 63-64, 103-104).
- **No bulk-DTO serialization** into metadata — never see `Map.of(... saved)` or `Map.of(... dto)`; only the documented keys. Verified by `grep -n "Map.of" backend/src/main/java/com/botfunnel/project/ProjectService.java` → 4 matches, all whitelist-shaped.
- `description` content NEVER lands in audit metadata. Acceptable for a feature where descriptions may contain user-private notes.
- IP / user-agent properly attached via `eventService.logEvent(ownerId, eventType, ip, userAgent, metadata)` — `ip` extracted via `extractIp` with `X-Forwarded-For` parsing (`ProjectController.java:138-148`), `userAgent` capped at 500 chars (line 133-136).

### A10 Server-Side Request Forgery (SSRF) — **OK**

**Evidence:** Feature adds no HTTP egress.

- `ProjectController` and `ProjectService` make no outbound HTTP calls — entire request path stays within Spring DI + Mongo driver.
- `ProjectHardDeleteJob` calls `template.remove(...)` and `eventService.logEventBlocking(...)` only — both internal.
- Frontend uses `useApi()` (relative paths to own backend); no user-controllable URL passes into a server-side fetch.
- No webhook callbacks, no integrations, no proxied requests in the feature scope.

---

## Cross-Component Invariants Verification

| Invariant | Status | Evidence |
|---|---|---|
| Single ownership entry point: every project-scoped handler routes through `ProjectService.requireOwned` | **Verified** | `ProjectService.java:50-64` is the only ownership-check site (`ownerId.equals` match). All five mutating/reading handlers in `ProjectController` start their chain with `requireOwned`-prefixed service calls. |
| No `ownerId` in request DTOs; controller derives identity from `currentUserId()` | **Verified** | `CreateProjectRequest.java:12-16` and `UpdateProjectRequest.java:13-17` have no `ownerId` field; `@JsonIgnoreProperties(ignoreUnknown=true)` drops it. Controller calls `currentUserId()` exclusively (`ProjectController.java:42, 52, 59, 71, 81, 91`). |
| Anti-enumeration uniform 404: foreign / soft-deleted / malformed / restore-on-active return identical `{message: "Project not found", code: null}` body | **Verified** | `ProjectService.java:53, 57, 60, 161` all use the same `AppException.notFound(MESSAGE_NOT_FOUND)`. `GlobalErrorHandler.handleAppException` emits `{message, code}` body with `code=null` for 404 (`GlobalErrorHandler.java:20-24`, `AppException.java:36-38`). Tests at `ProjectControllerIT.java:348-451, 727, 751-801`. |
| CSRF + session inheritance from `SecurityConfig.java`; no `permitAll` / `csrf().disable()` / new public path under `/api/v1/projects/**` | **Verified** | `SecurityConfig.java` content-read — no project-specific rules; `/api/**` is `authenticated()` and covers `/api/v1/projects/**`. AC-T7 strict (3 files) PASS. |
| No new secrets, tokens, API keys in source or env files; only opaque `currentProjectId` in `localStorage` (gated by `import.meta.client`) | **Verified** | `.env.example:31` only adds `PROJECTS_MAX_PER_USER=5` (config integer). `stores/projects.ts:9, 14-32` SSR-gates all `localStorage` access. `currentProjectId` is a server-issued ObjectId hex — not a token. |
| Rate-limit acceptance (Decision 8): documented mitigations — 5-active quota + uniform 404 + audit-log volume — are implemented, not just designed | **Verified** | Quota check at `ProjectService.java:73-78` and `ProjectService.java:163-168` (`countByOwnerIdAndDeletedAtIsNull >= maxPerUser`); env-driven via `app.projects.max-per-user`. Uniform 404 covered above. Audit-log emission on every mutation provides monitoring backstop. Tests: `ProjectControllerIT` 422 + 409 branches. |

---

## Findings Table

| ID | Severity | File | Lines | Summary | Fix |
|---|---|---|---|---|---|
| F-1 | Low | `backend/src/main/java/com/botfunnel/project/ProjectRepository.java` | 11 | `findByIdAndOwnerId(id, ownerId)` declared but never invoked. A future caller could copy from `requireOwned` and skip the soft-delete branch, accidentally weakening the guard. Not a vulnerability today (no caller exists) but a dormant footgun. | Either delete the unused derived finder, or refactor `ProjectService.requireOwned` to use it (collapsing the foreign-owner branch into one Mongo round-trip — `findByIdAndOwnerId` returns empty Mono on both "missing" and "foreign", which the guard could collapse). Aligns with code audit F6 (Task 11). |
| F-2 | Low | `frontend/pages/dashboard.vue` | 8-10 | `projectsStore.fetchAll()` is fire-and-forget with no `.catch()`. A transient backend error surfaces as an unhandled promise rejection (DevTools noise; observability gap in prod). Same pattern in `frontend/pages/projects/index.vue:55-58`. `settings.vue:19-23` correctly wraps with `.catch()`. Not a security vulnerability (no information leak, no XSS, no DoS); robustness gap under A04 Insecure Design per task hint. | Either (a) centralize error swallowing inside the store action (set `isLoaded=true` + an `error` ref the UI binds to), or (b) standardize on `.catch(console.warn)` at every call site. Aligns with code audit F5 (Task 11). |
| F-3 | Info | `backend/src/main/java/com/botfunnel/events/EventService.java` | 27-37 | Task 4 added a new public method `logEventBlocking(...)` to `EventService.java`. Strict AC-T7 (tech-spec.md:415, 3-file list) is unaffected. Broader zero-touch advisory in tech-spec.md:50 listed `EventService.java` — addition is documented in decisions.md Task 4 with rationale (AC-17b requires synchronous emission ordering). | Update tech-spec.md:50 to align with AC-T7:415 — remove `EventService.java` from the "No changes" list (ratifying the new pattern as the blocking-emit precedent for future cron jobs), or strengthen AC-T7 to include it (rejected: would retroactively block Task 4's reviewed approach). Aligns with code audit F1/F15. |
| F-4 | Info | `backend/src/main/java/com/botfunnel/project/ProjectController.java` | 124-148 | `currentUserId()`, `capUserAgent()`, `extractIp()` are byte-for-byte duplicated from `ProfileController` (lines 75-95). The line-124 comment explicitly defers extraction. With ProjectController shipped, there are 2 consumers — future epics will keep copying. | Hoist to `common/SecurityWebUtils.java` (or similar) before Wave 2 epics start. Defense-in-depth: a single util makes `currentUserId()` impossible to accidentally weaken via copy-paste. Aligns with code audit F2. |
| F-5 | Info | `frontend/components/ProjectSelector.vue` | 17-25 | Active-only filter (`p.deletedAt === null`) repeats across `stores/projects.ts:58`, `components/ProjectSelector.vue:18`, `pages/projects/index.vue:63`. Drift risk: a regression that flipped the filter in one place would create a "show soft-deleted in selector" leakage of stale project names. | Add `activeProjects` getter to the Pinia store; consumers read the getter. Centralizes the definition of "active project". Aligns with code audit F4. |
| F-6 | Info | `frontend/pages/projects/index.vue` | 36 | Page calls `useApi()` directly for soft-deleted list (`?include_deleted=true`) instead of through a store action. Compliant with the "useApi composable boundary" rule (no raw `$fetch`), but bypasses Pinia. No security impact — request still carries session cookies + CSRF via the composable. | Add `fetchSoftDeleted()` action to the store; page consumes it. Architectural tidiness, not a security fix. Aligns with code audit F7. |

---

## Edge-Case Spot Checks

- **Decision 14 ordering:** `ProjectService.restore` checks `project.getDeletedAt() == null` at line 160-162 BEFORE quota / name-conflict / save. A regression that flipped this to last-action would silently rename live projects (auto-suffix `" (restored)"`). ✓ Order verified by code-read AND by `restoreProject_onActiveProject_returns404AndDoesNotEmitRenameOrRestoreEvent` test (line 727-741).
- **404 interceptor scoping:** Regex `/^\/api\/v1\/projects\/([^/]+)$/` in `useApi.ts:31` is anchored at both ends; sub-paths (e.g. `/api/v1/projects/{id}/bots`) and the collection (`/api/v1/projects`) intentionally do NOT match. ID-match guard (`match[1] !== currentProjectId`) prevents clearing on unrelated 404s. ✓ Decision 11 / Risk R7 implemented per spec.
- **SSR-gating of `localStorage`:** `stores/projects.ts:9` `isClientGuard = () => import.meta.client`; both `readPersisted` (line 14-21) and `writePersisted` (line 23-32) check the guard. An ungated write would throw `ReferenceError` under SSR. ✓ Robustness preserved.
- **i18n template-interpolation leak:** `grep -nE '"<%|\$\{|\{\{'` against `uk.json` and `en.json` → 0 matches. No string value contains template syntax that could interpolate server-side data into a user-visible toast. ✓.
- **`v-html` in scope:** `grep -rn "v-html"` against `frontend/pages/projects/`, `components/ProjectSelector.vue`, `pages/dashboard.vue`, `layouts/default.vue` → 0 matches. All dynamic strings render through Vue's auto-escaping (`{{ ... }}` or attribute binding). ✓ No stored-XSS sink for `errors.projects.*` toasts.
- **Event metadata whitelist:** `ProjectService` uses `Map.of("projectId", ..., "name", ..., ["previousName", ..., "renamedDueToConflict", true])` — 4 explicit `Map.of` call sites, all whitelist-shaped. No bulk-DTO serialization. `description` content never enters the audit log. ✓.

---

## Dependency Vulnerability Check

- **Backend:** `git log --oneline 8d446f9^..HEAD -- backend/build.gradle` → 0 commits. No new packages. AC-T3 PASS.
- **Frontend:** `git log --oneline 8d446f9^..HEAD -- frontend/package.json frontend/pnpm-lock.yaml` → 0 commits. No new packages.
- **`pnpm audit --prod`** (run from `frontend/`, Node 18.18.2 via nvm): `No known vulnerabilities found`.
- No transitive vulnerability findings to report.

---

## Out-of-Scope Notes (Documented, Not Audited)

- `auth/`, `profile/`, `email/`, `events/`, `user/` baseline packages — existing security posture inherited.
- Future epics (`bots/`, `subscribers/`, `funnels/`, `broadcasts/`) — will register their own `requireOwned` callers when implemented; this feature establishes the precedent.
- Production deploy posture (TLS, WAF, secrets store) — deferred per Decision 15.
- Gitleaks pre-commit hook (AC-T8) — task 14 (Pre-deploy QA) responsibility; pre-commit reportedly passed without secret-scan blockage during Task 10 commits (gitleaks not installed locally; no findings).

---

## Verdict

**PASS** — proceed to Final Wave (Task 14 Pre-deploy QA).

All six cross-component invariants verified with concrete file:line evidence. OWASP Top 10 categories all return `OK`. Two Low-severity findings (F-1, F-2) and four Informational items recorded for downstream improvement; none block deployment. Dependency scan clean. AC-T7 strict zero-touch contract upheld for the three files in scope.

Recommend: address F-1 (delete or wire the unused finder) and F-2 (centralize fetchAll error handling) in a small follow-up commit before final merge — both are 5-10 line changes and would eliminate the only Low-severity items.
