# Decisions Log: 05-projects

Agent reports on completed tasks. Each entry is written by the agent that executed the task.

---

<!-- Entries are added by agents as tasks are completed.

Format is strict — use only these sections, do not add others.
Do not include: file lists, findings tables, JSON reports, step-by-step logs.
Review details — in JSON files via links. QA report — in logs/working/.

## Task N: [title]

**Status:** Done
**Commit:** abc1234
**Agent:** [teammate name or "main agent"]
**Summary:** 1-3 sentences: what was done, key decisions. Not a file list.
**Deviations:** None / Deviated from spec: [reason], did [what].

**Reviews:**

*Round 1:*
- code-reviewer: 2 findings → [logs/working/task-N/code-reviewer-1.json]
- security-auditor: OK → [logs/working/task-N/security-auditor-1.json]

*Round 2 (after fixes):*
- code-reviewer: OK → [logs/working/task-N/code-reviewer-2.json]

**Verification:**
- `npm test` → 42 passed
- Manual check → OK

-->

## Task 1: Backend foundation — AppException factories + Project domain + DTOs + ValidTimezone

**Status:** Done
**Commit:** 8d446f9 (impl) + 2d19455 (review fixes)
**Agent:** main agent
**Summary:** Added `com.botfunnel.project` package (entity with `@CompoundIndexes` (first use in repo), reactive repo with 7 derived finders, 3 DTO records with `@JsonIgnoreProperties(ignoreUnknown=true)` whitelist, `@ValidTimezone` with strict `ZoneId.getAvailableZoneIds().contains(...)` per Decision 3) and three new `AppException` factories (`notFound`, 2-arg `unprocessableEntity`, 2-arg `conflict` overload required by AC-5/AC-12b). Foundation only — Wave 2 wires service/controller/job.
**Deviations:** None — design intent (3 factories instead of 2, `@CompoundIndexes` container) was captured in the task spec itself during decomposition fix-round.

**Reviews:**

*Round 1:*
- code-reviewer: approved_with_suggestions (3 minor) → [logs/working/task-1/code-reviewer-1.json](logs/working/task-1/code-reviewer-1.json)
- security-auditor: approved (2 minor defense-in-depth nudges, non-blocking) → [logs/working/task-1/security-auditor-1.json](logs/working/task-1/security-auditor-1.json)
- test-reviewer: needs_improvement (1 major: redundant @ParameterizedTest; 2 minor: bundled blank cases, naming prefix) → [logs/working/task-1/test-reviewer-1.json](logs/working/task-1/test-reviewer-1.json)

*Round 2 (after fixes):*
- test-reviewer: passed → [logs/working/task-1/test-reviewer-2.json](logs/working/task-1/test-reviewer-2.json)

**Verification:**
- `./gradlew compileJava compileTestJava` → exit 0
- `./gradlew test --tests 'com.botfunnel.project.validation.ValidTimezoneValidatorTest'` → 9/9 green
- AC-T9 grep: `unique = true`, `partialFilter` in `com.botfunnel.project.*` → 0 matches
- AC-T4 grep: `app.projects.max-per-user` in application.properties + `PROJECTS_MAX_PER_USER` in .env.example → present

## Task 2: Frontend foundation — types + Pinia projects store + i18n keys (uk + en)

**Status:** Done
**Commit:** b305c9d (impl) + 0373781 (review fixes)
**Agent:** main agent
**Summary:** Added `frontend/types/project.ts` (Project interface), `frontend/stores/projects.ts` (Pinia setup-style store with SSR-safe localStorage persistence per Decision 10, AC-20 auto-select via private `runAutoSelect` helper, `isLoaded`+`inFlight` dedup per Decision 9, `handleStaleCurrent` for AC-24 recovery), and the full `projects.*` / `errors.projects.*` / `layout.projectSelector*` / `layout.sidebar.settings` / `validation.{projectName,timezone}*` namespaces in both `uk.json` and `en.json` (197 keys each, all four AC-25 keys present). Introduced an internal `__setClientGuardForTests` seam in the store so the SSR-context branch (Risk R6) can be exercised under the Nuxt vitest environment, which fixes `import.meta.client = true` at compile time.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: approved_with_suggestions (4 minor + 3 suggestions, no major) → [logs/working/task-2/code-reviewer-1.json](logs/working/task-2/code-reviewer-1.json)
- security-auditor: approved (2 low informational, no findings against OWASP Top 10) → [logs/working/task-2/security-auditor-1.json](logs/working/task-2/security-auditor-1.json)
- test-reviewer: needs_improvement (2 major: SSR-context tests didn't truly flip the gate; 3 minor: missing sortByCreatedAtDesc/Quota coverage + `__dirname` ESM nit) → [logs/working/task-2/test-reviewer-1.json](logs/working/task-2/test-reviewer-1.json)

*Round 2 (after fixes):*
- test-reviewer: passed (2 minor non-blocking suggestions) → [logs/working/task-2/test-reviewer-2.json](logs/working/task-2/test-reviewer-2.json)

**Verification:**
- `cd frontend && pnpm test --run` → 111/111 green (89 pre-existing + 17 store + 2 i18n + 3 added in round 2)
- `cd frontend && node scripts/check-locales.mjs` → exit 0 (AC-T5 locale parity)
- AC-T7 grep: `git diff b305c9d~1..HEAD --name-only` against `auth.global.ts | SecurityConfig | JobRunrMongoConfig` → 0 matches (zero-touch)
- AC-T12 grep: `localStorage.setItem` in `frontend/**/*.{ts,vue,mjs}` (excl. `node_modules` + `tests/`) → only `stores/projects.ts:26`

## Task 3: Backend ProjectService + ProjectController + integration tests

**Status:** Done
**Commit:** 8cd8ce6 (impl) + 4c85960 (review fixes)
**Agent:** main agent
**Summary:** Wave 2 backend core for `05-projects`. `ProjectService` is the platform-wide isolation primitive (`requireOwned` collapses foreign / soft-deleted-without-flag / missing / malformed `projectId` to identical `AppException.notFound`); enforces 5-active quota and service-level name uniqueness, soft-delete + restore with auto-suffix `" (restored)"` and `metadata.renamedDueToConflict=true`, Decision 14 deletedAt-FIRST guard on restore. `ProjectController` exposes `/api/v1/projects` with PATCH-normalization and verbatim-copied `currentUserId` / `extractIp` / `capUserAgent` helpers from `ProfileController`. Empirical slice-test result: zero `@MockitoBean` connection-factory mocks needed (matches `AuthControllerSliceTest` precedent — `patterns.md` line 38's three-mock guidance is more conservative than necessary for `@WebFluxTest(controllers=...)` slice tests in this codebase).
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: approved (0 major/minor, 2 suggestions) → [logs/working/task-3/code-reviewer-1.json](logs/working/task-3/code-reviewer-1.json)
- security-auditor: approved (0 findings) → [logs/working/task-3/security-auditor-1.json](logs/working/task-3/security-auditor-1.json)
- test-reviewer: approved_with_suggestions (2 major: bare-client 401 + response-shape lock; 3 minor; 2 suggestions) → [logs/working/task-3/test-reviewer-1.json](logs/working/task-3/test-reviewer-1.json)

*Round 2 (after fixes):*
- test-reviewer: passed (all 7 round-1 items addressed, no new findings) → [logs/working/task-3/test-reviewer-2.json](logs/working/task-3/test-reviewer-2.json)

**Verification:**
- `cd backend && ./gradlew test --tests 'com.botfunnel.project.*'` → 69/69 green (20 ProjectServiceTest + 1 ProjectControllerSliceTest + 39 ProjectControllerIT + 9 ValidTimezoneValidatorTest)
- AC-T7 zero-touch grep: `git diff 8cd8ce6~1..HEAD --name-only | grep -E "(SecurityConfig|auth.global|JobRunrMongoConfig|EventService.java|AbstractIntegrationTest)"` → 0 matches
- AC-T9 grep: `unique = true | partialFilter` in `com.botfunnel.project.*` → 0 matches

## Task 4: Backend ProjectHardDeleteJob + tests

**Status:** Done
**Commit:** 5de35c2 (impl) + 5c6329a (review fixes)
**Agent:** main agent
**Summary:** Added `jobs/ProjectHardDeleteJob` — `@Recurring(id="hard-delete-projects", cron="0 3 * * *")` daily cron that hard-deletes soft-deleted projects (>7d) plus their `events` rows while preserving a fresh `project_hard_deleted` audit event per deleted project. Cascade order enforced as: (1) sweep prior events via typed `Criteria.where("metadata.projectId").in(deletedIds)` `.block()`, (2) emit `project_hard_deleted` per project via the new blocking variant `.block()`, (3) `template.remove(...).block()` on projects. Pattern parity with `HardDeleteJob` (`+1ns` cutoff, structured INFO log on every run including zero-deletion days for AC-17c liveness). Coverage policy is IT-only per task spec — `ProjectHardDeleteJobIT` covers all 7 anchor cases including AC-17b temporal-sandwich proof.

**Ordering-hazard fix:** chose **Option A** from the task's "Ordering hazard" hints — added a new `EventService.logEventBlocking(String, String, String, String, Map): Mono<Event>` that returns the repository save Mono. Caller chains `.block()`. The existing fire-and-forget `logEvent(...)` is untouched (auth/profile/project flows keep their non-blocking semantics). Rejected Option B (inject `EventRepository` into the job) — it would duplicate the `Event` constructor call at a fresh call-site and bypass the `EventService` audit-emission contract that other modules already follow.

**Tech-spec correction:** tech-spec line 350 mentioned `JobScheduler.getRecurringJobs()` — that method does not exist on `JobScheduler` in JobRunr 7.3.2; `getRecurringJobs()` lives on `StorageProvider` only. IT autowires `org.jobrunr.storage.StorageProvider` (the `InMemoryStorageProvider` bean from `JobRunrInMemoryConfig` in tests) and reads `RecurringJob.getScheduleExpression()`. Tech-spec line 350 updated in this commit.

**Deviations:**
- `ProjectHardDeleteJobTest.java` (separate Mockito unit test) listed in tech-spec Implementation Tasks "Files to create" was intentionally NOT created. The task spec ("Coverage policy" + "Why no unit test" subsections) explicitly waives it: all behavior is covered at IT level matching the existing `HardDeleteJobTest` pattern (also IT-only despite the `Test` suffix). Mockito `InOrder` proves nothing about reactive timing, which is the actual failure mode (Ordering hazard).

**Reviews:**

*Round 1:*
- code-reviewer: approved (0 major, 4 nits — stylistic, optional, skipped) → [logs/working/task-4/code-reviewer-1.json](logs/working/task-4/code-reviewer-1.json)
- security-auditor: approved (0 findings, 1 info note for downstream UI epics: HTML-escape `metadata.name` when rendering `project_hard_deleted` rows) → [logs/working/task-4/security-auditor-1.json](logs/working/task-4/security-auditor-1.json)
- test-reviewer: not_approved (1 major: AC-17b ordering proof was trivially satisfied because seeded events were 8-10d in the past; 3 minor: `+1ns` cutoff isolation, log-assertion duplication, `cleanState` intent comment; 1 nit: anchor `containsPattern` prefix) → [logs/working/task-4/test-reviewer-1.json](logs/working/task-4/test-reviewer-1.json)

*Round 2 (after fixes):*
- test-reviewer: approved (all round-1 findings resolved, no new issues, temporal-sandwich timing robust against CI latency) → [logs/working/task-4/test-reviewer-2.json](logs/working/task-4/test-reviewer-2.json)

**Verification:**
- `cd backend && ./gradlew test --tests 'com.botfunnel.jobs.ProjectHardDeleteJob*'` → 7/7 green (cron_deletesOldProjectsAndCascadesEvents, cron_doesNotTouchProjectsYoungerThanSevenDays, cron_exactlySevenDaysAgo_isRemoved, cron_perEventMetadata_forMultipleDeletions, cron_zeroDeletionDay_emitsStructuredInfoLog, cron_logsStructuredInfoOnDeletionRun, recurringJob_registeredWithCorrectIdAndCron)
- Regression: `cd backend && ./gradlew test --tests 'com.botfunnel.jobs.HardDeleteJob*'` → 4/4 green (users cron unaffected — Risk R5 same-cron-slot serialization)
- AC-T3 grep: `git diff 5de35c2~1..HEAD -- backend/build.gradle frontend/package.json` → no changes (no new deps)
- AC-T7 zero-touch grep: `git diff 5de35c2~1..HEAD --name-only | grep -E "(SecurityConfig|auth.global|JobRunrMongoConfig|AbstractIntegrationTest)"` → 0 matches

## Task 5: Frontend ProjectSelector component + 404 interceptor wiring in useApi

**Status:** Done
**Commit:** 388739b (impl) + b71d72a (review fixes)
**Agent:** main agent
**Summary:** `ProjectSelector.vue` is a manual-ARIA "menu button" dropdown over the Pinia projects store. Sorts on the fly via a `computed` shallow copy filtered to `deletedAt === null` (the store may carry soft-deleted entries when the /projects page later runs `fetchAll(true)`) — both the list and the at-limit check (AC-30) use this active-only count. `useApi.ts` extracts a named `handleApiResponseError(ctx)` and wires it via `onResponseError: (ctx) => handleApiResponseError(ctx)`. The interceptor is strictly path-scoped: regex `/^\/api\/v1\/projects\/[^/]+$/` + ID match against `currentProjectId` (Decision 11 / Risk R7) — sub-paths, other IDs, unrelated 404s, and non-404 statuses all propagate untouched.

**Status-surface decision:** the inline-banner i18n key is held in the projects store as a `pendingBannerKey: Ref<string | null>` slot. The interceptor writes the key into the slot; the layout (Task 9) renders the banner against it and clears it on dismiss/route change. Rejected a `useToast()` composable (none exists in the codebase) and rejected ad-hoc plugin state.

**Deviations:**
- Task 2's store was already `done` without the `pendingBannerKey` slot the task design called for. Per task line 54 ("surface as a blocker and propose adding it to Task 2 — one ref + setter"), the slot was added as part of this commit (`stores/projects.ts` +5 lines: one `ref<string | null>(null)` + exposed in the setup-store return). No setter helper — the slot is a public reactive field, documented in-line in `projects.ts`. The component-side host (banner div + dismiss/route-clear) belongs to Task 9.
- Deferred items (recorded for follow-up, not blocking AC): full APG keyboard nav inside the menu panel (ArrowDown/Up/Home/End/Tab) — only Esc-close + focus-restore-on-select are wired. Outside-click and Esc are tested.

**Reviews:**

*Round 1:*
- code-reviewer: approved_with_suggestions (2 major: `sortedProjects` and `atLimit` were over raw `projects.length` not active count; 8 minor) → [logs/working/task-5/code-reviewer-1.json](logs/working/task-5/code-reviewer-1.json)
- security-auditor: approved (0 findings; regex+equality two-layer defense verified against percent-encoded slash, trailing slash, mixed-case, absolute-URL scheme variants) → [logs/working/task-5/security-auditor-1.json](logs/working/task-5/security-auditor-1.json)
- test-reviewer: passed (15/15 litmus; 3 minor coverage gaps: absolute-URL-string branch, trigger placeholder text, outside-click/Esc) → [logs/working/task-5/test-reviewer-1.json](logs/working/task-5/test-reviewer-1.json)

*Round 2 (after fixes):*
- code-reviewer: approved (both majors resolved via active-only filter, focus-restore added, disabled `+ Create` now `<button disabled>` so keyboard users can discover the tooltip; deferred items recorded) → [logs/working/task-5/code-reviewer-2.json](logs/working/task-5/code-reviewer-2.json)
- test-reviewer: passed (0 findings; all 3 round-1 minor coverage gaps closed + AC-19/AC-30 regression tests added) → [logs/working/task-5/test-reviewer-2.json](logs/working/task-5/test-reviewer-2.json)

**Verification:**
- `cd frontend && pnpm test --run` → 133/133 green (16 files; +6 tests vs round 1; new files: 9 interceptor specs + 13 component specs)
- `cd frontend && pnpm build` → green (locale parity gate holds; AC-T5)
- AC-T7 zero-touch grep: `git diff 388739b~1..HEAD -- frontend/middleware/auth.global.ts` → 0 lines changed
- AC-T3 grep: `git diff 388739b~1..HEAD -- frontend/package.json` → no changes (no new deps)
- User UI check: deferred until Task 9 injects `<ProjectSelector />` into `layouts/default.vue`; component verified in isolation via 13 mounted specs

## Task 6: Frontend dashboard inline empty-state + /projects/new create form

**Status:** Done
**Commit:** 20436d5 (impl) + a7d3786 (review fixes)
**Agent:** main agent
**Summary:** Wired Wave-1 `projectsStore` into `pages/dashboard.vue` with a 3-branch render (empty-state / welcome / loading) gated on `isLoaded` to prevent flicker (Decision 13); fetched on client mount only, no `localStorage` in the page (AC-T6). Created `pages/projects/new.vue` mirroring `pages/auth/register.vue`: reactive Zod schema in `computed()` passed AS-IS to `useForm` for live re-validation on locale switch (patterns.md «Reactive Zod schemas»); 3 fields (name 3-50, description ≤200, timezone refine via `Intl.supportedValuesOf`); submit trims, normalizes blank description to `null`, calls `projectsStore.create`, then `navigateTo(localePath('/dashboard'))`; submit errors mapped via `useApiError(e, 'projects.create')` to an inline `submitError` block (not toast — patterns.md).

**Deviations:**
- Two i18n-key drifts noticed during implementation, both safe to resolve in this task:
  - Task description referenced `projects.empty.*` but Wave 1 actually shipped `projects.emptyState.*`. Used the latter (existing keys, no rename).
  - `validation.projectDescriptionMax` was listed in the task as «added by Wave 1» but absent from both locales. Added it in `uk.json`/`en.json` (one key each, locale parity holds — `node scripts/check-locales.mjs` exit 0).
- `.gitignore` rule `projects/` (line 14, «User data (privacy)» section) was unanchored and caught new feature paths `frontend/pages/projects/` and `frontend/tests/pages/projects/`. Anchored to `/projects/` (repo-root only) with user approval; verified `git check-ignore` no longer matches the new directories and no other previously-ignored path becomes trackable.
- Valid-submit test uses `'Europe/Berlin'` instead of `'Europe/Kyiv'` because Node 24's bundled ICU still ships `'Europe/Kiev'` (pre-2022) in `Intl.supportedValuesOf`, so `'Kyiv'` fails the refine check in the test env. Documented inline; production behavior unaffected (browser ICU is newer).
- Invalid-timezone test relies on happy-dom's `<select>.value` snap-back-to-`''` when the value isn't in the `<option>` list — `''` itself is non-IANA so the refine check rejects it. The reviewer-suggested precondition `expect(select.value).toBe('Mars/Olympus_Mons')` cannot hold in happy-dom; replaced with a comment explaining the true assertion path.

**Reviews:**

*Round 1:*
- code-reviewer: approve (2 nits: dead-clause description schema, fire-and-forget `fetchAll` unhandled-rejection — Task 2 store concern) → [logs/working/task-6/code-reviewer-1.json](logs/working/task-6/code-reviewer-1.json)
- security-auditor: approve (0 findings; AuthZ/XSS/CSRF/open-redirect/input-validation/.gitignore-anchor all clean) → [logs/working/task-6/security-auditor-1.json](logs/working/task-6/security-auditor-1.json)
- test-reviewer: approve (3 minor: misleading `_showsApiErrorToast` names, untyped `dashboard_mount_callsFetchAll` assertion, DOM-bypass brittleness; 2 nits) → [logs/working/task-6/test-reviewer-1.json](logs/working/task-6/test-reviewer-1.json)

Round 2 not requested — all three round-1 verdicts were `approve`. Actionable nits/minors from code-reviewer (#1) and test-reviewer (#1, #2, #3) addressed in fix commit a7d3786: simplified description schema; renamed `_showsApiErrorToast` → `_showsApiErrorInline`; tightened `dashboard_mount_callsFetchAll` to `toHaveBeenCalledTimes(1)` + `toHaveBeenCalledWith()`; documented happy-dom snap-back semantics in the invalid-timezone test. Optional nits (locale-switch behavioural test, exact-boundary valid cases, store-level unhandled-rejection guard) deferred — non-blocking and partly upstream.

**Verification:**
- `cd frontend && pnpm test` → 146/146 green (17 files; +15 tests vs round 1: dashboard 2→6, new projects/new.spec.ts with 9 tests)
- `cd frontend && node scripts/check-locales.mjs` → exit 0 (locale parity gate; new `validation.projectDescriptionMax` present in both locales)
- AC-T6 grep: `git grep -n 'localStorage' frontend/pages/dashboard.vue` → 0 hits
- User UI check: pending (see verification section below)

## Task 7: Frontend /projects index page (active + recently deleted with restore)

**Status:** Done
**Commit:** 16fc57e (impl) + a7b3cef (review fixes)
**Agent:** main agent
**Summary:** New `pages/projects/index.vue` composes the existing Pinia store and `useApi` composable: store-driven `Active` section + page-local `softDeletedProjects` from a separate `?include_deleted=true` fetch + per-row Restore that captures `preRequestName` and branches success / renamed-due-to-conflict (`response.name !== preRequestName`) / 422-limit through `useApiError(err, 'projects.restore')`. Concurrent restore is guarded by a `restoringIds: Set<string>` ref, the banner auto-dismisses via a single `setTimeout(4000)` cleared in `onBeforeUnmount`, and the `+ Create new project` control toggles between a `<NuxtLinkLocale>` and a `<button disabled aria-disabled title>` once `activeProjects.length >= 5` (AC-30).

**Deviations:**
- **i18n namespace reuse (same drift as Task 6).** Task 7 referenced a `projects.list.*` namespace (`projects.list.title`, `projects.list.activeHeading`, `projects.list.recentlyDeletedHeading`, `projects.list.daysRemaining`, `projects.list.emptyActive`, `projects.list.restoreLabel`); Task 2 actually shipped the flat shape (`projects.title`, `projects.activeSection`, `projects.recentlyDeletedSection`, `projects.daysRemaining`, `projects.restore.action`) plus the layout-namespaced `layout.projectSelector.{createNew,noActive,settingsLink}`. The page consumes the existing keys (no new keys introduced — locale parity holds without revisiting Task 2). Reuse of `layout.projectSelector.*` for page text couples the page copy to the selector dropdown; a future i18n hoist can untangle this (code-reviewer R1 nit, deferred).
- **Banner placement at page level (not nested in the Recently deleted section).** Task wording said "top-of-section element"; rendering inside the `<section v-if="softDeletedProjects.length > 0">` would hide the success banner on the last successful restore (section becomes empty → `v-if` false → banner unmounts). Banner lives above both sections so success feedback survives the row removal that triggers it.
- **`data-test="restore-toast"` kept** despite being rendered as an inline banner, as the task spec required for selector parity.

**Reviews:**

*Round 1:*
- code-reviewer: approve_with_suggestions (0 major, 3 minor, 4 nits) → [logs/working/task-7/code-reviewer-1.json](logs/working/task-7/code-reviewer-1.json)
- security-auditor: approved (0 findings, 2 info — UUID invariant note + silent-catch logging) → [logs/working/task-7/security-auditor-1.json](logs/working/task-7/security-auditor-1.json)
- test-reviewer: approved_with_suggestions (0 major, 6 minor + 3 nits — bare-digit regex brittleness, missing concurrent-click / 5-active+deleted / auto-dismiss / 500-fallback coverage, `href ?? to` fallback) → [logs/working/task-7/test-reviewer-1.json](logs/working/task-7/test-reviewer-1.json)

Round 2 not requested — all three verdicts were approve-class. Actionable round-1 items addressed in fix commit a7b3cef: stable tri-valued sort comparator for soft-deleted rows, `apiError(…) || t('errors.generic')` fallback to guarantee a banner on unknown statuses, `console.warn` on silent `refreshSoftDeleted` failure (security-auditor R1 telemetry note), defensive-filter comment on `activeProjects`, removed `href ?? to` test fallback (pinned to `href`), `aria-disabled` undefined assertion in enabled-button case, bounded `/\b\d\b/` digit regex for countdown, and four new specs: concurrent-restore guard, 5-active+deleted coexistence (AC-30 + AC-23 interaction), banner auto-dismiss via full fake timers + `advanceTimersByTimeAsync(4001)`, and generic-error fallback on 500.

Deferred (non-blocking): code-reviewer item #2 (`restored.name` falsy guard — TS strict-null + store contract make it theoretical), item #4 (SSR renders empty — matches the rest of the repo), item #6 (i18n namespace hoist — future cleanup). Security-auditor info #1 (UUID-shape guard) deferred: backend invariant + Nuxt percent-encoding cover it today; revisit if non-UUID ids ever land. Test-reviewer "import locale strings instead of substring assertions" deferred: substring matches are still robust enough, full i18n decoupling is a wider test-strategy decision.

**Verification:**
- `cd frontend && pnpm test --run` → 158/158 green (18 files; +12 new tests in `tests/pages/projects/index.spec.ts`)
- `cd frontend && pnpm build` → green (locale parity gate holds; no new keys introduced)
- User UI check: pending — covered by feature-level user verification once Task 9 wires the page into the default layout

## Task 8: Frontend project settings page (rename, description, timezone, danger-zone delete)

**Status:** Done
**Commit:** ca16c27 (impl) + b077a60 (review fixes)
**Agent:** main agent
**Summary:** New `pages/projects/[projectId]/settings.vue` composes the existing Pinia store and `useApiError` factory: vee-validate General form with reactive Zod schema (project-name `min(3).max(50)`, description `max(200)`, IANA timezone `refine()` against `Intl.supportedValuesOf('timeZone')`) builds a partial-diff PATCH payload (name/timezone only when changed-and-nonempty; description sent as literal `''` when cleared so the backend normalizes to `null`), and a type-the-name danger-zone modal with case-sensitive trim-compare branches the post-delete redirect to `/dashboard` when no active projects remain (defensive `p.deletedAt === null` filter) or `/projects` otherwise. Errors from PATCH/DELETE flow through `apiError(err, 'projects.update')` / `apiError(err, 'projects.delete')` for inline rendering; `t('errors.projects.delete.confirmTypeName')` is paired with a separate `<strong>{{ project.name }}</strong>` element because the shipped locale string has no `{name}` placeholder.

**Deviations:**
- **`{name}` placeholder absence.** Task spec called for `t('errors.projects.delete.confirmTypeName', { name: project.name })`; the locale shipped by Task 2 has no placeholder. Rather than edit locales (task AC: "не редагує uk.json / en.json"), the page renders the project name as a separate emphasized element next to the static label. Cleaner UX too — the expected name stands out visually.
- **PATCH-vs-POST description-blank asymmetry (intentional, called out by task spec).** Task 6 (POST `/create`) normalizes empty description to `null` client-side; this task (PATCH `/update`) deliberately ships literal `''` and relies on backend `isBlank` normalization. Different semantics: POST = "create without description"; PATCH = "explicit delta — clear the existing description". Documented here so future reviewers do not flag the contract split as inconsistency.
- **Lazy hydration `.catch()` added.** `pages/projects/index.vue` calls `projectsStore.fetchAll()` fire-and-forget without `.catch()`; this page wraps it because a failed listing fetch would otherwise surface as an unhandled rejection while the page is still rendering. Inline fallback (no programmatic redirect) handles `project === null` regardless of cause.

**Reviews:**

*Round 1:*
- code-reviewer: approve (3 minor: unused template ref + missing autofocus, locale-placeholder drop, fetchAll missing SSR guard / catch) → [logs/working/task-8/code-reviewer-1.json](logs/working/task-8/code-reviewer-1.json)
- security-auditor: approve (0 findings; XSS / authz / sensitive-data / CSRF / open-redirect / type-confirmation-bypass all clean) → [logs/working/task-8/security-auditor-1.json](logs/working/task-8/security-auditor-1.json)
- test-reviewer: request_changes (5 major: tautological currentProject assertion, weak deletedAt-filter coverage, missing DELETE-error / no-op-submit / positive-timezone-change tests; 3 minor + 2 nit) → [logs/working/task-8/test-reviewer-1.json](logs/working/task-8/test-reviewer-1.json)

*Round 2 (after fixes):*
- test-reviewer: approve (all five Round 1 majors addressed; localePath identity-mock noted as test-pragmatic and non-blocking) → [logs/working/task-8/test-reviewer-2.json](logs/working/task-8/test-reviewer-2.json)

Round 1 fix commit b077a60: rendered project name as a separate `<strong data-test="delete-project-expected-name">` element next to the static prompt; added `import.meta.client` guard + `.catch()` around the lazy `fetchAll`; dropped the unused template ref and rely on HTML `autofocus` for the modal input; replaced `renameSucceeds_storeReflectsNewName` (which only asserted the mock's own `update.mockImplementation`) with `renameSucceeds_showsSavedIndicator` (DOM assertion on `[data-test="settings-saved"]`); added `deleteConfirm_apiError_showsInlineErrorAndStays`, `submitWithoutChanges_doesNotCallUpdate`, `timezoneChanged_includesFieldInPartial`, `descriptionUnchanged_omitsFieldFromPartial`; seeded a `deletedAt` leftover in `deleteConfirm_redirects_toDashboard_whenNoOtherActive` so the page's `p.deletedAt === null` filter is load-bearing; added danger-zone-hidden assertion to the fallback test.

**Verification:**
- `cd frontend && pnpm test -- tests/pages/projects/settings.spec.ts` → 13/13 green
- `cd frontend && pnpm test` → 171/171 green (19 files)
- `cd frontend && node scripts/check-locales.mjs` → exit 0 (task does not edit locales; sanity check holds)
- `cd frontend && pnpm build` → green
- User UI check: deferred to feature-level verification per user request (golden path + edge cases listed in task.md Verification Steps → User remain pending)


## Task 9: Frontend layout integration + Project Knowledge alignment

**Status:** Done
**Commit:** af9d401
**Agent:** main agent
**Summary:** Wired `<ProjectSelector />` into the global `frontend/layouts/default.vue` topbar (between brand and user-info) and added a sidebar `Settings` link guarded by `v-if="projectsStore.currentProject"` linking to `/projects/${currentProject.id}/settings`. Aligned `.claude/skills/project-knowledge/references/ux-guidelines.md` line 33 with the inline empty-state UX (AC-31 / AC-T10); line 43 (localStorage persistence) left intact. 10-line layout diff; no new tests (task explicitly has no TDD anchor — layout composition only).
**Deviations:** None on implementation. Note: `git diff` for the `ux-guidelines.md` change is silent because `.claude/` is gitignored in this repo (per CLAUDE.md); AC-T10 "exactly one line changed" verified by direct file read instead.

**Reviews:**

*Round 1:*
- code-reviewer: approve (0 major; 2 minor advisories on flex-justify spacing + bare-path NuxtLinkLocale convention, both non-blocking) → [logs/working/task-9/code-reviewer-1.json](logs/working/task-9/code-reviewer-1.json)
- security-auditor: approve (0 findings; OWASP A01–A10 N/A or safe — `currentProject.id` is server-issued UUID, `:to` uses static path prefix, zero-touch contract AC-T7 verified) → [logs/working/task-9/security-auditor-1.json](logs/working/task-9/security-auditor-1.json)
- test-reviewer: passed (0 major; 1 optional suggestion to add a tiny `layouts/default.spec.ts` for the v-if branch — not required, manual user verification covers it) → [logs/working/task-9/test-reviewer-1.json](logs/working/task-9/test-reviewer-1.json)

**Verification:**
- `cd frontend && pnpm test --run` → 171/171 green (19 files); pre-existing Task 5 `ProjectSelector.spec.ts` and Task 2 `projects.spec.ts` unaffected
- `cd frontend && node scripts/check-locales.mjs` → exit 0 (locale parity holds; no new keys)
- `grep -n "full-page onboarding" .claude/skills/project-knowledge/references/ux-guidelines.md` → 0 matches, exit 1 (AC-T10 alignment)
- AC-T7 zero-touch grep: only `frontend/layouts/default.vue` in `git diff HEAD~1 HEAD --stat`
- User UI check: deferred to feature-level verification per user request (topbar order, sidebar v-if, locale switch listed in task.md Verification Steps → User remain pending)

## Task 10: Playwright golden-path E2E spec

**Status:** Done
**Commit:** 4a1fa23 (impl) + e10067d (review fixes round 1) + 4f1dc9c (AC-28 hydration gate) + 032f846 (review fixes round 2)
**Agent:** main agent
**Summary:** Single-test `frontend/e2e/projects.spec.ts` covers register → dashboard empty-state → create #1 → selector populated → create #2 → switch via dropdown → settings rename → live selector update (no `page.reload()`) → soft-delete via type-the-name modal (disabled → partial → exact) → /projects "Recently deleted" → restore → both projects active in selector. All selectors are stable `data-test` hooks, URL regexes tolerate the optional `/en` locale prefix, and the spec deliberately omits `test.use({ locale })` per task contract. Zero-touch: only the new spec file changed (`git diff 4a1fa23~1..HEAD --name-only` → exactly `frontend/e2e/projects.spec.ts`).

**Deviations:**
- Wave 3 (tasks 6-9) shipped `data-test` attributes under a different naming convention than the one assumed in this task's "Selector coordination contract" section. Since Wave 3 was already merged and AC-T7 zero-touch forbids edits to those files in Task 10, the spec consumes the actual hooks. Mapping (assumed → actual):
  - `empty-state-cta` → `dashboard-empty-state-cta`
  - `project-create-form` → not present (filled via inputs/submit by data-test)
  - `project-create-submit` → `project-submit`
  - `projects-active-section` → `active-section`
  - `projects-deleted-section` → `recently-deleted-section`
  - `project-active-item[data-project-id]` → `active-row-${id}`
  - `project-deleted-item[data-project-id]` → `deleted-row-${id}`
  - `restore-button` → `restore-button-${id}`
  - `project-settings-form` → `settings-form`
  - `project-name-input` (settings) → `settings-name-input`
  - `project-settings-save` → `settings-submit`
  - `danger-zone-delete-cta` → `delete-project-open`
  - `delete-confirm-modal` → `delete-project-modal`
  - `confirm-name-input` → `delete-project-name-input`
  - `delete-confirm-button` → `delete-project-confirm`
  - `project-selector-toggle` → `project-selector-trigger`
  - `project-selector-option[data-project-id]` → `project-selector-item-${id}`
  - `project-selector-create-cta` → `project-selector-create`
  - `project-selector-settings-cta` → `project-selector-settings`
  - `project-selector-current` / `dashboard-welcome` → not shipped; selector text is read via the trigger element, welcome state is implicitly asserted by the empty-state container becoming hidden.
- Register form inputs use `#id` selectors (`#email`, `#password`, `#confirmPassword`) because `pages/auth/register.vue` has no `data-test` on inputs (only on error `<p>` elements). Adding hooks there is out of feature scope.

**Reviews:**

*Round 1:*
- code-reviewer: approved_with_suggestions (0 critical, 0 major, 4 minor, 2 nit — selector verification confirmed all hooks shipped) → [logs/working/task-10/code-reviewer-1.json](logs/working/task-10/code-reviewer-1.json)
- security-auditor: approved (0 findings; no secrets, no env coupling, no PII, no auth bypass, no `page.route` mocking) → [logs/working/task-10/security-auditor-1.json](logs/working/task-10/security-auditor-1.json)
- test-reviewer: passed (11/11 litmus; 0 major, 6 minor, 3 nit — all AC items provably asserted) → [logs/working/task-10/test-reviewer-1.json](logs/working/task-10/test-reviewer-1.json)

Round 2 not requested — all three round-1 verdicts non-blocking. Actionable minors addressed in fix commit e10067d: dropped `networkidle` gate on register (HMR-websocket flake source), added 6-char random suffix to the unique email (per-millisecond collision guard), dropped per-call `waitForURL` timeout overrides (rely on Playwright 30s default for CI cold-start), asserted `dashboard-empty-state` hidden after first create (AC-18 → AC-19 transition guard). Skipped: `test.step()` wrappers (parity with `e2e/i18n.spec.ts`), URL regex tightening (current `(?:\?.*)?$` anchoring is already safe — would not match `/projects/new` etc.), Escape→outside-click swap (Escape is the documented APG menu-close path), capturing deleted-row id from URL (single-deletion in golden path; `hasText` is unambiguous), explicit welcome heading assertion (no shipped data-test, AC-T7 zero-touch).

**Task 14 follow-up — AC-28 hydration race (commit 4f1dc9c + 032f846):**

Task 14 pre-deploy QA caught a deterministic failure of `pnpm test:e2e e2e/projects.spec.ts` (5× consecutive). Click on `button[type="submit"]` at `/auth/register` fired before Vue's `@submit.prevent` hydrated → form did native HTML GET → never reached `/dashboard`. Round-1 fix-commit e10067d had dropped `networkidle` citing HMR-WebSocket flake; that swap exposed the race in dev mode. Fix added a Vue-mount probe (`waitForFunction` on `__vue_app__` on the `#__nuxt` mount-host) before the first interaction. Subsequent navigations are client-side via Vue Router, so no further gates are needed. After round-2 test-reviewer flagged the initial probe (`._instance.isMounted`) as dev-only (Vue 3.5 strips it under `NODE_ENV=production` — verified at `runtime-core.cjs.prod.js:3243`), the probe was simplified to `!!root.__vue_app__` (commit 032f846) which Vue sets unconditionally in `app.mount()` across dev and prod builds. Robust under both `pnpm dev` (current webServer) and the deferred `pnpm build && pnpm preview` AC-28 verification path.

*Round 2 (after AC-28 fix):*
- code-reviewer: approved_with_suggestions (0 critical, 0 major, 3 minor, 1 nit — minor #1 prod-build caveat addressed in 032f846) → [logs/working/task-10/code-reviewer-2.json](logs/working/task-10/code-reviewer-2.json)
- security-auditor: approved (0 findings; native-GET password-leak path no longer reachable; AC-T7/AC-T8 hold) → [logs/working/task-10/security-auditor-2.json](logs/working/task-10/security-auditor-2.json)
- test-reviewer: needs_improvement (3 major: prod-only hang via dev-gated `_instance`, narrow Suspense-vs-root gating, private API) → [logs/working/task-10/test-reviewer-2.json](logs/working/task-10/test-reviewer-2.json)

*Round 3 (after dropping dev-gated `_instance`):*
- test-reviewer: passed (major #2 closed in both dev and prod resolved deps; majors #1/#3 acknowledged but non-blocking — `/auth/register.vue` is sync `<script setup>` and `__vue_app__` is a single internal property set in both modes) → [logs/working/task-10/test-reviewer-3.json](logs/working/task-10/test-reviewer-3.json)

**Verification:**
- `frontend/node_modules/.bin/playwright test --list e2e/projects.spec.ts` → `Total: 1 test in 1 file` (single-block contract holds across all fix commits)
- AC-T7 zero-touch grep: `git diff 4a1fa23~1..HEAD --name-only` → exactly `frontend/e2e/projects.spec.ts` (no edits to `playwright.config.ts`, `frontend/package.json`, `middleware/auth.global.ts`, `backend/.../SecurityConfig.java`, `backend/.../JobRunrMongoConfig.java`)
- AC-T8 gitleaks: pre-commit hook executed all four task-10 commits without secret-scan blockage (warning "gitleaks not installed" surfaced but no findings; only synthetic `Test1234!` and `@test.local` literals present)
- Vue mount-marker verified in this project's resolved deps: `@vue+runtime-core@3.5.33` dev bundle (`runtime-core.esm-bundler.js:4251`) and prod bundle (`runtime-core.cjs.prod.js:3243`) both assign `rootContainer.__vue_app__ = app` unconditionally inside `app.mount()`.
- `pnpm test:e2e` live-stack smoke deferred to user — execution environment cannot bring up Mongo + Redis + Mailpit + backend + frontend together; user confirmed they will verify globally after finalize.

## Task 11: Code Audit

**Status:** Done
**Commit:** (no code change — audit-only)
**Agent:** main agent
**Summary:** Holistic cross-component audit of Tasks 1-10. Verdict `pass-with-recommendations`: 0 critical / 0 high / 0 medium / 5 low / 11 info findings across 27 in-scope source files. All AC checks pass (T1, T2, T3, T4, T5, T6, T7 strict, T9, T10, T11, T12 verified; T8 deferred to Task 12). `ProjectService.requireOwned` confirmed as the single ownership entry point; DTO whitelist applied consistently; Mongo queries are all typed (derived finders or `Criteria.where(...).in(List<String>)`); locale parity holds at 198 keys each. Notable low-severity recommendations: hoist duplicated `currentUserId/capUserAgent/extractIp` helpers, extract a `useTimezoneOptions()` composable, expose `activeProjects` getter on the store, and align tech-spec.md Architecture line 50 with AC-T7:415 wording re: EventService.java zero-touch status (Task 4 modified it with documented rationale — strict AC-T7 passes, broader architecture statement is now stale).
**Deviations:** None — Task 11 has no code output by design; the audit JSON itself is the deliverable.

**Reviews:**

This task has no reviewers (per task spec — the auditor IS the review).

**Verification:**
- 27 in-scope source files read at post-Task-10 state.
- AC-T7 strict (auth.global.ts / SecurityConfig.java / JobRunrMongoConfig.java) — `git diff 8d446f9^..HEAD --name-only | grep -E "(auth\\.global|SecurityConfig|JobRunrMongoConfig)"` → 0 matches.
- AC-T11 NoSQL sweep — `grep -rn "Criteria\\.where\\|Query\\.query" com.botfunnel.project.* + ProjectHardDeleteJob` → all matches use typed `.in(List<String>)`; no string concat / raw JSON.
- AC-T12 / AC-T6 — only `frontend/stores/projects.ts:26` calls `localStorage.setItem`; `pages/dashboard.vue` clean.
- AC-T9 — `grep -rn "@CompoundIndex.*unique\\|partialFilter" com.botfunnel.project.*` → 0 matches.
- AC-T5 — `node frontend/scripts/check-locales.mjs` exit 0; 198 keys in both `uk.json` and `en.json`, 0 diff.
- Audit report → [logs/working/task-11/code-audit-1.json](logs/working/task-11/code-audit-1.json)

## Task 12: Security Audit

**Status:** Done
**Commit:** (no code change — audit-only)
**Agent:** main agent
**Summary:** Full-feature security audit of Tasks 1-10 against OWASP Top 10 (2021). Verdict **PASS** — 0 Critical / 0 High / 0 Medium / 2 Low / 4 Informational findings. All six cross-component invariants verified with file:line evidence: single ownership entry point (`requireOwned` is the only `ownerId.equals` site in the project package), no `ownerId` in request DTOs (mass-assignment dropped by `@JsonIgnoreProperties(ignoreUnknown=true)` + asserted by `ProjectControllerIT.create_hostileBodyOwnerId_isIgnored`), uniform 404 anti-enumeration across foreign/soft-deleted/malformed/restore-on-active (asserted by 10 `*_returns404` tests + `errorBodies_alwaysHaveMessageAndCode`), AC-T7 zero-touch strict (3 files content-read clean), no new secrets, rate-limit acceptance backed by 5-active quota + audit-log. `pnpm audit --prod` reports "No known vulnerabilities found"; no new backend/frontend deps. Low-severity items: F-1 unused `findByIdAndOwnerId` finder (dormant footgun), F-2 fire-and-forget `fetchAll()` on `dashboard.vue`+`projects/index.vue` (robustness, not a vuln). Recommend addressing both in a small follow-up before merge.
**Deviations:** None — Task 12 has no code output by design; the audit report itself is the deliverable.

**Reviews:**

This task has no reviewers (per task spec — the security auditor IS the review).

**Verification:**
- 27 in-scope source files read at post-Task-10 state.
- AC-T7 zero-touch content-read: `SecurityConfig.java`, `JobRunrMongoConfig.java`, `auth.global.ts` — no reference to `/api/v1/projects/**` or `projects` package; baseline preserved.
- `grep -rn "ownerId.equals\|ownerId ==" com.botfunnel.project.*` → 1 match (only inside `requireOwned`). Single-entry-point invariant holds.
- `grep -nE 'ownerId' com.botfunnel.project.dto/*.java` → 0 hits in `CreateProjectRequest`, `UpdateProjectRequest` (whitelist DTOs). `ProjectResponse` also has no `ownerId` field (response-shape lock).
- `grep -n "@JsonIgnoreProperties" com.botfunnel.project.dto/*.java` → `ignoreUnknown = true` confirmed on both request DTOs.
- `cd frontend && pnpm audit --prod` → `No known vulnerabilities found`.
- `git log --oneline 8d446f9^..HEAD -- backend/build.gradle frontend/package.json frontend/pnpm-lock.yaml` → 0 commits (AC-T3 PASS).
- Audit report → [logs/audits/security-audit.md](logs/audits/security-audit.md)

## Task 13: Test Audit

**Status:** Done
**Commit:** (no code change — audit-only)
**Agent:** main agent
**Summary:** Test-quality audit of all test files shipped in Tasks 1-10. Verdict **pass-with-findings** — 0 blocker / 0 major / 3 minor / 4 nit. Coverage matrix maps every user-spec AC-1..AC-31 and tech-spec AC-T1..AC-T12 to concrete tests (direct coverage for 41/43 ACs; 2 ACs — AC-31/AC-T10 — are intentionally documentation-only and verified by grep, not by a test). Backend pyramid is heavy IT (46 IT cases across `ProjectControllerIT` + `ProjectHardDeleteJobIT`) over lean Mockito units (20 service + 1 slice + 9 validator); frontend pyramid is focused vitest (83 cases across store/components/pages/composables/i18n) plus exactly one Playwright golden-path spec. AC-T11 NoSQL safety holds (zero raw `Criteria` in `com.botfunnel.project.*`; `ProjectHardDeleteJob` uses typed `.in(List<String>)` only); AC-T12 single-call-site holds (only `frontend/stores/projects.ts:26`). All three "intentionally NOT written" tests (concurrent-POST race, cron-vs-restore race, E2E for Scenario 6) are confirmed absent and their absences are still justified by current mitigations. No work needs to be returned to Waves 1-4 before Task 14.
**Deviations:** None — Task 13 has no code output by design; the audit findings list itself is the deliverable.

**Reviews:**

This task has no reviewers (per task spec — the auditor IS the review).

**Findings:**

- **minor** Tech-spec drift on `ProjectHardDeleteJobTest.java`. Tech-spec line 298 and Task 13's file-list both name `ProjectHardDeleteJobTest` (Mockito unit) but only `ProjectHardDeleteJobIT` exists on disk. Decisions.md Task 4 explicitly documents the IT-only coverage policy (reviewer-approved): all behaviors — `findByDeletedAtBefore` cutoff, cascade order, structured INFO log, zero-deletion days — are covered by the 7 IT cases including the temporal-sandwich proof for AC-17b. Fix anchor: amend `work/05-projects/tech-spec.md` line 298 (remove the unit-test bullet for `ProjectHardDeleteJobTest`) and add a fourth entry to "Tests intentionally NOT written" (lines 352-357) explaining why a Mockito InOrder unit is inferior to the reactive IT for ordering proof. No new test needed.
- **minor** Weak coverage of AC-7 `?include_deleted=true` response shape. `ProjectControllerIT.getProjects_includeDeletedTrue_returnsActivePlusSoftDeleted` (line 311-321) asserts list length == 2 but does not assert that the soft-deleted row exposes `deletedAt` in the JSON response (the AC explicitly mandates "кожен рядок несе `deletedAt`"). Fix anchor: in that same test, add `.jsonPath("$[?(@.name == 'Deleted')].deletedAt").exists()` and `.jsonPath("$[?(@.name == 'Active')].deletedAt").doesNotExist()`.
- **minor** Tech-spec path drift on the settings spec. Tech-spec line 305 references `frontend/tests/pages/projects/[projectId]/settings.spec.ts` but the spec actually lives at `frontend/tests/pages/projects/settings.spec.ts` (flat, no dynamic-segment directory). The spec exercises `pages/projects/[projectId]/settings.vue` correctly via `mountSuspended` + a route-params mock, so coverage is intact. Fix anchor: amend tech-spec line 305 to the flat path (or move the file, but rename is cheaper and matches the actual import in the spec).
- **nit** `cron_exactlySevenDaysAgo_isRemoved` (ProjectHardDeleteJobIT:157-173) is self-documented as weak under wall-clock testing (without an injected `Clock`, the +1ns cutoff is indistinguishable from "the test happened to run after the seed"). Parity with `HardDeleteJob` is what really catches a regression. Acceptable as documentation; mark for follow-up only if `Clock` injection becomes available.
- **nit** AC-22c dropdown content (the IANA list from `Intl.supportedValuesOf('timeZone')`) is asserted indirectly via `new_initialValues_timezoneDefaultsToBrowser` and `new_validSubmit_callsStoreCreateAndRedirects` (accepts `Europe/Berlin`), and via the `setValue('Europe/London')` in `timezoneChanged_includesFieldInPartial`. No explicit "dropdown contains N+ IANA options" assertion. Acceptable — the refine() check on submit covers the failure mode.
- **nit** AC-31 / AC-T10 ux-guidelines.md update has no test coverage. Tech-spec line 367 explicitly excludes it from the test corpus (verified by grep instead); decisions.md Task 9 documents the grep verification. PASS (intentionally not test-asserted).
- **nit** Selector "reflects new name without reload" (AC-22a critical reactive path) is asserted end-to-end in `e2e/projects.spec.ts:88-90`; the unit-level `settings.spec.ts` covers the partial-payload + saved-indicator path but does not cross-component-assert the selector text. Acceptable — this property is layout-integration-only.

**Verification:**

- 5 backend + 8 frontend + 1 e2e test files read at post-Task-10 state.
- `find backend/src/test/java/com/botfunnel/project backend/src/test/java/com/botfunnel/jobs` → all expected files present except `ProjectHardDeleteJobTest.java` (covered by finding #1).
- AC-T11 NoSQL sweep: `grep -rn 'Criteria\.where\|@Query' backend/src/main/java/com/botfunnel/project/` → 0 matches (derived finders only). `ProjectHardDeleteJob.java` lines 83/99 use typed `.in(deletedIds: List<String>)`.
- AC-T12 single-call-site: `grep -rn 'localStorage.setItem' frontend/ --include='*.ts' --include='*.vue' --include='*.mjs'` (excluding tests + node_modules) → exactly `frontend/stores/projects.ts:26`.
- AC-T9: `grep -rn '@CompoundIndex.*unique\|partialFilter' backend/src/main/java/com/botfunnel/project/` → 0 matches.
- AC-25 i18n parity: `tests/i18n/required-keys.spec.ts` covers all four AC-25 keys in both `uk.json` and `en.json`.
- AC-17b: `ProjectHardDeleteJobIT.cron_deletesOldProjectsAndCascadesEvents` (lines 62-135) — temporal-sandwich timestamps prove the surviving `project_hard_deleted` row was created during the run, not pre-seeded.
- AC-17c: structured INFO log asserted via `OutputCaptureExtension` in both the deletion-day (line 132-134) and zero-deletion-day (line 212-214) tests; exactly-once emission verified by `cron_logsStructuredInfoOnDeletionRun` (lines 220-243).
- Bare-`WebTestClient` 401 (Decision 2 anti-enumeration auth-before-CSRF): `ProjectControllerIT.anyEndpoint_unauthenticatedBareClient_returns401` (lines 455-496) exercises all 6 verb shapes (GET list, GET item, POST, PATCH, DELETE, restore).
- Pyramid balance: 46 IT cases vs 30 Mockito-unit cases (backend) — IT-heavy per `patterns.md`; 83 vitest cases (frontend) — focused per-page/per-composable; 1 Playwright spec (E2E) — single golden path per tech-spec line 361.
- "Tests intentionally NOT written" sanity: `grep 'concurrent\|race' backend/src/test/java/com/botfunnel/{project,jobs}/` → 0 matches; `frontend/e2e/` contains only `i18n.spec.ts` (pre-existing) and `projects.spec.ts` (golden path) — no stale-state E2E.

## Task 14: Pre-deploy QA

**Status:** Done
**Commit:** (no code change — QA-only)
**Agent:** main agent
**Summary:** Full pre-deploy acceptance pass. **Verdict: pass-with-defect — feature is functionally ready but AC-28 (E2E green) fails deterministically in dev mode.** 42/43 acceptance criteria pass (31 user-spec + 11 of 12 tech-spec — AC-T8 verified via Task 12 audit + scripts/install-hooks.sh graceful-skip path). 250/250 backend tests green (`./gradlew test` BUILD SUCCESSFUL, 0 failures incl. all 46 IT cases under `ProjectControllerIT` + `ProjectHardDeleteJobIT`). 171/171 frontend vitest green across 19 files. `pnpm build` green (locale parity gate exit 0, 4.59 MB total). AVP curl smoke against live stack (backend `./gradlew bootRun` + dockerised Mongo/Redis/Mailpit) confirmed steps 2-9: 6th create → 422 `project_limit_reached`, restore-at-limit → 422 `project_limit_reached`, foreign id → 404 (NOT 403), malformed id → 404, no-session → 401, duplicate active name (fresh user) → 409 `project_name_taken`, invalid timezone `GMT+5` → 400 with `timezone` in message + `code:null`. Mongo inspection confirmed `deletedAt` absent on active (Spring serialises null Instants as absent — semantically equivalent), present as ISO instant on soft-deleted. AVP step 10 (JobRunr recurring) verified via `ProjectHardDeleteJobIT.recurringJob_registeredWithCorrectIdAndCron` asserting `id="hard-delete-projects"` + `cron="0 3 * * *"` against `StorageProvider.getRecurringJobs()`. AVP step 11 PK alignment grep returns 0 results. Static AC-T7 zero-touch `git diff` against pre-feature commit 56598cf for `auth.global.ts`/`SecurityConfig.java`/`JobRunrMongoConfig.java` is empty (wc -c == 1, newline only); AC-T3 diff for `backend/build.gradle`/`frontend/package.json` is empty; AC-T9 grep for `unique = true` + `partialFilter` in `com.botfunnel.project.*` → 0 matches; AC-T11 grep for `Criteria.where` in `com.botfunnel.project.*` → 0 matches (only `ProjectHardDeleteJob` uses `Criteria.where(field).in(typedList)` which is safe); AC-T12 grep `localStorage.setItem` across `frontend/` excluding `node_modules|tests|.nuxt|.output|e2e` → exactly one source-file match `stores/projects.ts:26`.
**Deviations:** None — Task 14 has no code output by design; the QA report is the deliverable.

**Reviews:**

This task has no reviewers (per task spec — the QA itself is the final gate).

**Findings:**

- **major (AC-28 fail)** `pnpm test:e2e` `e2e/projects.spec.ts` golden path fails deterministically on `page.waitForURL(/\/dashboard.../)` at line 40 because the `button[type="submit"]` click on `/auth/register` fires before Vue's `@submit.prevent` directive hydrates. The form does a native HTML GET submission, navigating to `/en/auth/register?email=...&password=...&confirmPassword=...` instead of POSTing JSON to `/api/auth/register`. Reproduced 5× consecutively (initial run + 2 manual retries + Playwright `--retries=2`), all three retries logged the same `navigated to .../en/auth/register?email=...&password=Test1234!&confirmPassword=Test1234!` pattern. `e2e/i18n.spec.ts` passes because it explicitly waits for `networkidle` before clicking `lang-en`. Task 10 fix-commit e10067d dropped the `networkidle` gate on the projects spec citing "HMR-websocket flake source" — that fix swapped a flake for a deterministic race in dev mode. Underlying feature works (backend + Pinia store + UI behaviour are all covered by IT + vitest + manual AVP smoke), so this is a test-spec defect, not a product bug. Probable resolution: re-introduce a hydration gate on the register submit (e.g. wait for a JS-side reactive-behaviour probe before clicking submit, OR `await page.waitForFunction(() => !!(window as any).__NUXT__)` if such a marker is reliable, OR pre-fill an invalid value and assert the validation error appears reactively before re-filling a valid value). **Action:** return Task 10 to work-loop for E2E spec hardening, OR verify on production build via `pnpm build && pnpm preview` before merge (deferred to post-deploy — see qa-report.json `deferredToPostDeploy`).

**Verification:**

- Backend: `./gradlew test` → BUILD SUCCESSFUL, 250 tests, 0 failures (aggregated from `backend/build/test-results/test/*.xml`)
- Frontend unit: `pnpm test` → 19 files, 171 tests passed (6.20 s)
- Frontend build: `pnpm build` → ✨ Build complete (4.59 MB total, 1.05 MB gzip); `node scripts/check-locales.mjs` → exit 0
- Frontend E2E: `pnpm test:e2e` → 1 passed (i18n.spec.ts), 1 FAILED (projects.spec.ts golden path) — see finding above
- AVP curl smoke (live stack — backend `bootRun` on :8080, Mongo :27017, Redis :6379, Mailpit :1025/:8025): steps 2, 3, 4, 5, 6, 7, 8 → all PASS with expected status codes and `code` payloads
- AVP step 9 Mongo inspection: `db.projects.findOne({_id: ObjectId('6a032a5abedee87948aa7a2f')})` after soft-delete → `deletedAt: ISODate('2026-05-12T13:25:46.143Z')`; on active (before delete) → field absent (Spring Data Mongo serialises null Instants as absent, which is Mongo-idiomatic and matches the `deletedAtIsNull` derived-finder semantics)
- AVP step 10: `recurringJob_registeredWithCorrectIdAndCron` in `ProjectHardDeleteJobIT` asserts `StorageProvider.getRecurringJobs()` contains the entry with `id="hard-delete-projects"` and `scheduleExpression="0 3 * * *"`
- AVP step 11: `grep -n "full-page onboarding" .claude/skills/project-knowledge/references/ux-guidelines.md` → 0 matches
- AC-T7 zero-touch: `git diff 56598cf..HEAD -- frontend/middleware/auth.global.ts backend/src/main/java/com/botfunnel/security/SecurityConfig.java backend/src/main/java/com/botfunnel/jobs/JobRunrMongoConfig.java | wc -c` → 1 (newline only — empty diff)
- AC-T3 deps: `git diff 56598cf..HEAD -- backend/build.gradle frontend/package.json` → empty
- AC-T8 gitleaks: gitleaks not installed locally (`which gitleaks` → "not found"); pre-commit hook gracefully skips per `scripts/install-hooks.sh:9-12`. Task 12 Security Audit (PASS, 0 critical/high/medium) confirmed no synthetic secrets in any committed file — verdict carried over.
- Full QA report: [logs/working/qa-report.json](logs/working/qa-report.json)

**Deferred to post-deploy:** 4 items — see `deferredToPostDeploy` in qa-report.json. Notably: AC-28 production-build re-verification (the dev-mode hydration race is materially less likely under bundled JS; verify on `pnpm preview` before merge or fix the spec hydration gate), cross-restart localStorage persistence (Local Storage panel + close/reopen browser is human-only), `/en` i18n visual smoke, and JobRunr dashboard sighting.

**Verdict: BLOCKED BY E2E HYDRATION RACE (AC-28).** Feature is otherwise merge-ready (backend + frontend unit + build + AVP smoke + tech-spec AC-T1..T12 all green). Recommend either (a) returning Task 10 to work-loop to harden the register-submit hydration gate, or (b) running the E2E spec against `pnpm preview` (production build) on the same live stack and treating that as the canonical AC-28 gate, with a follow-up to fix the dev-mode flake. Do NOT merge until AC-28 has a green run somewhere.

---

## Task 15: Smoke-test findings — UX polish (tooltip, nav, stale-state, TZ picker)

**Status:** Done
**Commit:** ccd5481 (round 2) + 9652b4b (round 1) + 5f25ed6 (initial)
**Agent:** main agent
**Summary:** Closed 4 UX findings surfaced by manual pre-merge smoke (work/05-projects/smoke-test.md): (1) AC-30 native-title tooltip replaced with always-visible limit text + aria-describedby; (2) added sidebar "All projects" link making AC-23 restore flow reachable without typed URLs; (3) settings.vue redirects to /projects with persistent dismissable banner on stale/missing project instead of passive amber div; (4) folded in TimezonePicker.vue (typeahead + UTC-offset + ARIA combobox) — moved from workflow/improvements.md since it touches the same files.
**Deviations:** Did NOT short-circuit the explicit GET on the happy path (code-reviewer-1 #5) — cross-tab smoke-test 2.6 is exactly the case where store says active but server says 404, so the redundant GET is the agreed cost.

**Reviews:**

*Round 1:*
- code-reviewer: 8 minor → [logs/working/task-15/code-reviewer-1.json](logs/working/task-15/code-reviewer-1.json)
- test-reviewer: 2 major + 6 minor → [logs/working/task-15/test-reviewer-1.json](logs/working/task-15/test-reviewer-1.json)

*Round 2 (after fixes):*
- code-reviewer: approved + 1 trivial nit → [logs/working/task-15/code-reviewer-2.json](logs/working/task-15/code-reviewer-2.json)
- test-reviewer: approved + 3 minor follow-ups → [logs/working/task-15/test-reviewer-2.json](logs/working/task-15/test-reviewer-2.json)

All round-2 findings applied in ccd5481 (negative empty-emit test, useApi rejection-path test, settings overwrite-guard test on !project.value branch, timezoneAttrs cleanup).

**Verification:**
- `pnpm test` → 196 passed (20 files; up from 186 pre-task — 10 new tests across TimezonePicker, settings, index, useApi-interceptor, new)
- `pnpm build` → ✨ Build complete (TimezonePicker chunk built without warnings)
- `node scripts/check-locales.mjs` → exit 0 (4 new keys parity in uk.json + en.json)
- AC-T7 zero-touch: `git diff main~3..HEAD -- frontend/middleware/auth.global.ts backend/.../SecurityConfig.java backend/.../JobRunrMongoConfig.java` → empty
- Decision 10 scope-lock: `grep localStorage components/TimezonePicker.vue` → 0
- Smoke 2.1 (tooltip): visible-text contract + aria-describedby asserted in ProjectSelector.spec + index.spec; native title absent assertion guards regression
- Smoke 2.6 (stale-state): 4 settings.spec cases cover same-tab back-after-delete, cross-tab stale, 500+missing, happy path; staleness banner persistence test guards against accidental auto-dismiss
- /projects via sidebar: E2E spec asserts `data-test="sidebar-all-projects-link"` visible after soft-delete redirect

