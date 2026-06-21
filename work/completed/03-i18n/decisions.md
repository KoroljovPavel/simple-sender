# Decisions Log: 03-i18n

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

## Task 1: Playwright bootstrap

**Status:** Done
**Commit:** 8e6946b
**Agent:** main agent
**Summary:** Bootstrapped Playwright as E2E runner in `frontend/`: installed `@playwright/test@^1.59.1` (forward-compatible with task's `^1.49` lower bound, allowed by spec edge-cases), added `test:e2e` script, created `playwright.config.ts` (testDir `./e2e`, webServer `pnpm dev` on :3000 with `reuseExistingServer: !process.env.CI`, chromium project, list reporter, 120s timeout), placeholder `e2e/.gitkeep`, and incremental `frontend/.gitignore` for `playwright-report/` + `test-results/` (no duplication of root rules).
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: OK → [logs/working/task-1/code-reviewer-1.json](logs/working/task-1/code-reviewer-1.json)
- security-auditor: OK → [logs/working/task-1/security-auditor-1.json](logs/working/task-1/security-auditor-1.json)
- infrastructure-reviewer: OK → [logs/working/task-1/infrastructure-reviewer-1.json](logs/working/task-1/infrastructure-reviewer-1.json)

**Verification:**
- `pnpm exec playwright --version` → `Version 1.59.1`
- `pnpm test:e2e --list` → `Total: 0 tests in 0 files` (config valid; exit 1 expected when no specs)
- `git status` → no `playwright-report/` or `test-results/` artifacts tracked

## Task 2: i18n module bootstrap

**Status:** Done
**Commit:** 6c3131e (impl) + 62d1d87 (review fix r1)
**Agent:** main agent
**Summary:** Installed `@nuxtjs/i18n@^9` (resolved 9.5.6) and wired Decision 4 config into `nuxt.config.ts`: locales (uk default + en) with BCP47 `language` field, `strategy: 'prefix_except_default'`, `langDir: 'locales'`, explicit `cookieKey: 'i18n_lang'`, env-conditional `cookieSecure`, no `cookieMaxAge` (Context7-verified absent in v9 API). `NON_DEFAULT_LOCALES = ['en'] as const` exported from `shared/i18n-locales.ts` as single source of truth — drives `routeRules` `/en/auth/**` parity rules via `Object.fromEntries` + shared `AUTH_HEADERS` constant (Decision 14). i18n.config.ts placed at `frontend/i18n/i18n.config.ts` because Nuxt 4 + i18n v9 default `restructureDir: 'i18n'` resolves the `vueI18n` path inside that dir.
**Deviations:**
- i18n.config.ts location is `frontend/i18n/i18n.config.ts`, not `frontend/i18n.config.ts` as worded in spec. Necessary to silence `[@nuxtjs/i18n] WARN ./i18n.config.ts not found in /.../frontend/i18n. Skipping...` (the module's restructureDir-relative resolution).
- Smoke 2 (`/dashboard` + `Accept-Language: en-US` → expected `Location: /en/...` + `Set-Cookie: i18n_lang=en`) is unverifiable at this Wave-2 boundary because existing `frontend/middleware/auth.global.ts` hardcodes `navigateTo('/auth/login')` for unauth users without `localePath()`, preempting i18n's locale redirect. Decision 6 schedules the auth-middleware rewrite (`useRouteBaseName()` + `useLocalePath()`) for a separate task. Smoke 2/3 will be fully verifiable post-Decision-6.
- `silentTranslationWarn`/`silentFallbackWarn` (per AC literal text) are Legacy-mode-only flags in vue-i18n; under `legacy: false` they are inert. Spec-compliant by AC text; Decision 9's info-disclosure-prevention intent will need composition-mode equivalents (`missingWarn`/`fallbackWarn`) in a follow-up if console-warning leakage re-surfaces in production. Code-reviewer flagged as informational, non-blocking.

**Reviews:**

*Round 1:*
- code-reviewer: 1 minor (silentWarn legacy-only — non-blocking) + 1 nit (semicolon style — fixed) → [logs/working/task-2/code-reviewer-1.json](logs/working/task-2/code-reviewer-1.json)
- security-auditor: OK (2 info: vue-i18n@10 upstream-deprecated, dual-source locale-set surface) → [logs/working/task-2/security-auditor-1.json](logs/working/task-2/security-auditor-1.json)
- test-reviewer: OK (4 info about smoke-scope wording in task spec, not code defects) → [logs/working/task-2/test-reviewer-1.json](logs/working/task-2/test-reviewer-1.json)

**Verification:**
- `pnpm test` → 9 files, 47 tests passed (AC18 / Decision 16 holds)
- `pnpm dev` → starts cleanly, locale files resolve under `frontend/i18n/locales/` (AC10)
- Smoke 1 `curl -sI /en/auth/verify-email?token=x` → `x-robots-tag: noindex, nofollow` + `referrer-policy: no-referrer` ✅
- Smoke 4 `curl -sI /auth/login` → identical headers (parity baseline) ✅ — confirms Decision 14 routeRules parity (AC11)
- Smoke 2/3 → deferred per deviation note above

## Task 3: Server middleware — strip invalid/default-locale prefix

**Status:** Done
**Commit:** b2caf94 (impl + 15 tests, TDD red→green) + c413015 (review fix r1: +4 coverage tests)
**Agent:** main agent
**Summary:** New Nitro global middleware closes AC7 + Decision 5. Pure decision function `decideLocaleStrip(path, allowList)` lives in `server/utils/locale-strip.ts` (no h3 deps — unit-testable without Nitro mocks); thin Nitro handler in `server/middleware/strip-invalid-locale.ts` delegates and applies `sendRedirect`/`setResponseStatus`/`setResponseHeaders`. Allow-list imports `NON_DEFAULT_LOCALES` from `shared/i18n-locales` (Decision 14 parity). Path-normalization order per Decision 5: raw-backslash reject → 2-letter-prefix regex w/ `(?=/|$)` lookahead → allow-list passthrough → strip + collapse `^/+` → `decodeURIComponent` (try/catch) → post-decode backslash recheck → post-decode safety-net collapse → auth-zone match (`/auth`, `/auth/`, `/auth?` — not `/authentic`). Auth-zone 302 carries `X-Robots-Tag` + `Referrer-Policy` headers on the redirect itself (Decision 14 — token leak defense before browser issues the next request).
**Deviations:**
- Import path uses relative `../../shared/i18n-locales` rather than `~/shared/i18n-locales` alias — task hint allowed either; relative chosen as guaranteed-resolving in Nitro server context (alias resolution under Nuxt 4 server-middleware was uncertain per task lines 142, 148).

**Reviews:**

*Round 1:*
- code-reviewer: OK (5 minor/nit findings: `slice(3)` magic number, untested `/dashboard\evil` boundary, inline auth-zone matcher could be helper, auto-import of h3 helpers — all non-blocking) → [logs/working/task-3/code-reviewer-1.json](logs/working/task-3/code-reviewer-1.json)
- security-auditor: OK (2 minor info: control-char filter as defense-in-depth, adversarial-probe logging — both deferred to server-logger landing) → [logs/working/task-3/security-auditor-1.json](logs/working/task-3/security-auditor-1.json)
- test-reviewer: OK (4 minor coverage gaps — fixed in c413015: `/?foo=bar`, `/`, multi-locale allow-list, `/auth?token=` branch) → [logs/working/task-3/test-reviewer-1.json](logs/working/task-3/test-reviewer-1.json)

**Verification:**
- `pnpm test tests/server/strip-invalid-locale.spec.ts` → 19 passed (15 spec fixtures + 4 gap-fix tests)
- `pnpm test` → 11 files, 66 passed (no existing-spec regressions)
- Smoke checks on `pnpm dev` (port 3000):
  - `curl -sI /fr/dashboard` → `302` `location: /dashboard` ✅
  - `curl -sI /uk/dashboard` → `302` `location: /dashboard` ✅
  - `curl -sI /en/dashboard` → passthrough at middleware (auth.global redirects to /auth/login since unauth — expected for protected page) ✅
  - `curl -sI /dashboard` → passthrough at middleware (same auth-redirect downstream) ✅
  - `curl -sI '/fr/auth/verify-email?token=x'` → `302` `location: /auth/verify-email?token=x` + `x-robots-tag: noindex, nofollow` + `referrer-policy: no-referrer` on the 302 ✅
  - `curl -sI '/fr//evil.com'` → `302` `location: /evil.com` (collapse) ✅
  - `curl -sI '/fr/%5Cevil.com'` → `400` (encoded backslash rejected after decode) ✅

## Task 4: Locale dictionaries + check-locales script

**Status:** Done
**Commit:** 1e11f08 (impl) + 7f2655a (review fix r1)
**Agent:** main agent
**Summary:** Filled `i18n/locales/uk.json` (verbatim from existing pages) and `en.json` (conservative-formal EN copy) with the full Decision 8 namespace tree (auth/dashboard/profile/layout/validation/errors/common/brand). `errors.*` covers 2-level auth contexts (login/register/...) and 3-level profile contexts (`profile.saveName.*` etc.) per Task 10's `useApiError` contract. New `scripts/check-locales.mjs` (pure-function `collectKeySet`+`compareKeySets` separated from main entry) wired via `prebuild` npm-script — uses `Object.create(null)` accumulator and filters `__proto__`/`constructor`/`prototype` (defense-in-depth on top of `JSON.parse`). Dedicated `node:test` spec covers the 5 TDD-anchor cases.
**Deviations:** Added `validation.passwordMin8Login` key alongside `passwordMin8` (login uses the longer phrasing "Password must be at least 8 characters long" per code-research §1.3 verbatim, register/reset use the short "At least 8 characters" form). Documented here per code-reviewer's polish note.

**Reviews:**

*Round 1:*
- code-reviewer: OK (8 non-blocking polish notes) → [logs/working/task-4/code-reviewer-1.json](logs/working/task-4/code-reviewer-1.json)
- security-auditor: OK → [logs/working/task-4/security-auditor-1.json](logs/working/task-4/security-auditor-1.json)
- test-reviewer: needs_fixes — prototype-pollution test tautological → [logs/working/task-4/test-reviewer-1.json](logs/working/task-4/test-reviewer-1.json)

*Round 2 (after fixes):*
- test-reviewer: OK — assertions now catch removal of either `Object.create(null)` or `UNSAFE_KEYS` filter independently → [logs/working/task-4/test-reviewer-2.json](logs/working/task-4/test-reviewer-2.json)

**Verification:**
- `node --test scripts/check-locales.test.mjs` → 7 passed (5 TDD anchors + constructor-key test + compareKeySets diff)
- `node scripts/check-locales.mjs` → exit 0 on real locales
- Drop a key in `en.json` → exit 1 + stderr lists missing key (smoke verified, restored)
- `mv en.json /tmp` → exit 1 + stderr "Locale file not found" (smoke verified, restored)
- `pnpm build` → passes (prebuild hook + Nuxt build)
- `pnpm test` → 66 passed (no Vitest regressions; node:test runs separately)

## Task 5: useApiError factory composable

**Status:** Done
**Commit:** 65c7705
**Agent:** main agent
**Summary:** New `composables/useApiError.ts` per Decision 10 — factory invokes `useI18n()` at the correct `<script setup>` lifecycle, returns a handler `(error, contextKey) => string`. Status extracted via `error?.statusCode ?? error?.status ?? error?.response?.status` and normalized through `Number()` + `Number.isFinite` (drops NaN/null/undefined, coerces string-numbers). Lookup chain `errors.{ctx}.{status}` → `errors.{ctx}.generic` → `errors.generic` is gated by `te()` before each `t()` call to prevent Vue I18n's silent missing-key fallthrough in production. Vitest spec mocks `useI18n` via `mockNuxtImport` per the established pattern; covers all 9 TDD anchors plus 4 edge cases (null/undefined error, string status, NaN).
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: OK (2 minor nits — silent empty-string fallback if `errors.generic` itself missing; cosmetic shape-cast) → [logs/working/task-5/code-reviewer-1.json](logs/working/task-5/code-reviewer-1.json)
- security-auditor: OK (1 minor — throwing getters on error fields propagate exception rather than returning safe string; non-disclosure) → [logs/working/task-5/security-auditor-1.json](logs/working/task-5/security-auditor-1.json)
- test-reviewer: OK (4 minor — symmetric negative assertions only on test 3; status-extraction tests skip return-value assertions; factory_returnsFunction is shallow; no return-value assert when no `te()` matches) → [logs/working/task-5/test-reviewer-1.json](logs/working/task-5/test-reviewer-1.json)

**Verification:**
- `pnpm test tests/composables/useApiError.spec.ts` → 13 passed
- `pnpm test` → 79 passed (was 66 — +13 new; no regressions)

## Task 6: LangSwitcher component + Vitest

**Status:** Done
**Commit:** 2e5b605
**Agent:** main agent
**Summary:** New `components/LangSwitcher.vue` — text toggle `UK | EN` with two `<button type="button">` separated by a decorative `|`. Click on inactive locale calls `await setLocale(code)` (Decision 12 canonical pattern: module atomically updates cookie `i18n_lang`, locale state, `<html lang>`, and auto-navigates). Click on currently-active locale early-returns. Active button gets Tailwind `font-semibold text-primary` + `aria-current="true"`; inactive gets `text-muted-foreground hover:text-foreground`. Both buttons have `data-test="lang-{code}"` and `aria-label`. Items list filtered to `uk`/`en`. Vitest spec covers all 5 TDD anchors via `mockNuxtImport` for `useI18n` (with `localesRef` as a `ref` per v9 contract) and `useCookie`; AC15 cookie value is asserted explicitly.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: OK (2 nits — `as unknown as LocaleEntry[]` double cast; `aria-current="true"` vs more semantic `"page"`) → [logs/working/task-6/code-reviewer-1.json](logs/working/task-6/code-reviewer-1.json)
- security-auditor: OK → [logs/working/task-6/security-auditor-1.json](logs/working/task-6/security-auditor-1.json)
- test-reviewer: OK (3 minor — could also assert `text-primary`/`text-muted-foreground`, `aria-current`, and add a clarifying comment on the cookie test) → [logs/working/task-6/test-reviewer-1.json](logs/working/task-6/test-reviewer-1.json)

**Verification:**
- `pnpm test tests/components/LangSwitcher.spec.ts` → 5 passed
- `pnpm test` → 84 passed (was 79 — +5 new; no regressions)
- User-verify deferred to Task 8 (LangSwitcher mounted in layouts only there)

## Task 7: Auth middleware locale-aware refactor

**Status:** Done
**Commit:** 79da8f6
**Agent:** main agent
**Summary:** Replaced `isPublicAuthRoute(path)` (which broke under `prefix_except_default` for `/en/auth/...`) with name-based whitelist via `useRouteBaseName()`. `PUBLIC_ROUTE_NAMES = ['auth-login', 'auth-register', 'auth-forgot-password', 'auth-reset-password', 'auth-verify-email'] as const` at module scope; `(arr as readonly string[]).includes(baseName)` cast avoids literal-type narrowing. Both `navigateTo()` calls wrapped in `useLocalePath()`. Fail-closed: if `getBaseName(to)` returns `undefined`, treat as protected — unauth → login redirect, authed → passthrough (matches Decision 6). Existing `try/catch` around `authStore.fetchUser()` preserved verbatim. Spec rewrite invokes the AC18 explicit-exception (Decision 16); 10 cases cover uk + en redirect variants, both fail-closed branches, hydration, backend 5xx, and the public-form passthrough.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: OK (2 minor — empty-catch observability; one-line comment for `getBaseName` alias) → [logs/working/task-7/code-reviewer-1.json](logs/working/task-7/code-reviewer-1.json)
- security-auditor: OK (2 informational — silent error swallow lacks audit log, deferred to server-logger; no static check tying whitelist to filesystem) → [logs/working/task-7/security-auditor-1.json](logs/working/task-7/security-auditor-1.json)
- test-reviewer: OK (4 minor — passthrough/hydration tests omit `localePathMock` assertions; no en-variants for fail-closed and backend-error cases) → [logs/working/task-7/test-reviewer-1.json](logs/working/task-7/test-reviewer-1.json)

**Verification:**
- `pnpm test tests/middleware/auth.global.spec.ts` → 10 passed
- `pnpm test` → 88 passed (was 84 — net +4 over the old 6-case middleware spec; no regressions)

## Task 8: app.vue + layouts

**Status:** Done
**Commit:** 72b4e03
**Agent:** main agent
**Summary:** Wired i18n into the application shell. `app.vue` now calls `useLocaleHead({ seo: true })` + `useHead`, so SSR emits `<html lang="uk">` for unprefixed paths and `<html lang="en">` for `/en/...` plus the `rel="alternate" hreflang=""` triplet. `layouts/default.vue` translates brand/Logout/Dashboard/Profile via `t()`, swaps `<NuxtLink>` for `<NuxtLinkLocale>`, wraps the logout `navigateTo` in `localePath`, and mounts `<LangSwitcher />` at the topbar's flex-row right of Logout (Decision 15). `layouts/auth.vue` becomes `relative` and gets `<LangSwitcher class="absolute top-4 right-4 z-10" />` over the centered card.
**Deviations:** Flattened locale-key namespace from `layout.default.{logout,dashboard,profile}` (Task 4 over-nested it speculatively) to `layout.{logout,dashboard,profile}` to match Task 8 ACs verbatim. uk/en parity preserved.

**Reviews:**

*Round 1:*
- code-reviewer: OK (2 minor pre-existing notes — empty catch in onLogout, narrow-viewport topbar crowding) → [logs/working/task-8/code-reviewer-1.json](logs/working/task-8/code-reviewer-1.json)
- security-auditor: OK → [logs/working/task-8/security-auditor-1.json](logs/working/task-8/security-auditor-1.json)
- test-reviewer: OK — confirmed no test files modified in commit; layout verification correctly deferred to E2E (Task 11) → [logs/working/task-8/test-reviewer-1.json](logs/working/task-8/test-reviewer-1.json)

**Verification:**
- `pnpm test` → 88 passed (no regressions from layout changes)
- `pnpm build` → OK (prebuild + Nuxt build)
- `curl -s /auth/login | grep '<html'` → `<html dir="ltr" lang="uk">` ✅
- `curl -s /en/auth/login | grep '<html\|hreflang'` → `<html dir="ltr" lang="en">` + `<link rel="alternate" hreflang="uk">` + `<link rel="alternate" hreflang="en">` + `<link rel="alternate" hreflang="x-default">` ✅

## Task 9: Auth pages translation (5 files)

**Status:** Done
**Commit:** fbd0c9c
**Agent:** main agent
**Summary:** Translated all five auth pages (login/register/forgot-password/reset-password/verify-email) from hardcoded UA literals to `t()` backed by Task 4's locale dictionaries. Each page follows the canonical pattern: `useI18n()` + `useLocalePath()` + `useApiError()` at the top of `<script setup>`; Zod schema wrapped in a single `computed(() => toTypedSchema(z.object({...})))` and passed AS-IS to `useForm({ validationSchema: schemaComputed })` — never `.value`, never `unref()`. Catch blocks call `apiError(e, '<context>')` (forgot-password keeps silent catch; verify-email retains state-machine for branching). `<NuxtLink>` → `<NuxtLinkLocale>`, `navigateTo()` wrapped in `localePath()`. `verify-email.vue` renamed inner `t = route.query.token` to `rawToken` to avoid shadowing the destructured translator.
**Deviations:**
- Authorized AC18 / Decision 16 exception extension: each of the 5 page specs gained exactly one `mockNuxtImport('useLocalePath', () => () => (path) => path)` identity-passthrough mock. UA-literal asserts unchanged.
- Config-side: under VITEST, disabled `detectBrowserLanguage` in `nuxt.config.ts` because happy-dom defaults `navigator.language='en-US'`, which would otherwise flip the test locale to `en` and break every UA-literal assertion in the existing 21-case auth suite. Production behavior unchanged.

**Reviews:**

*Round 1:*
- code-reviewer: OK (1 internal contradiction in task spec — useApiError optional in forgot-password, followed sensible interpretation) → [logs/working/task-9/code-reviewer-1.json](logs/working/task-9/code-reviewer-1.json)
- security-auditor: OK (2 minor info — useApiError empty-string fallback if errors.generic missing; ?token= persists in URL/history during session as inherent to the email-link design) → [logs/working/task-9/security-auditor-1.json](logs/working/task-9/security-auditor-1.json)
- test-reviewer: OK → [logs/working/task-9/test-reviewer-1.json](logs/working/task-9/test-reviewer-1.json)

**Verification:**
- `pnpm test tests/pages/auth/` → 21 passed (no regressions)
- `pnpm test` → 88 passed
- Cyrillic-grep on `pages/auth/*.vue` → empty outside `t()` calls
- `pnpm build` → OK

## Task 10: App pages translation (dashboard + profile)

**Status:** Done
**Commit:** c3b1521
**Agent:** main agent
**Summary:** Translated `dashboard.vue` (32 LOC; banner/profile-link/welcome/description) and `profile.vue` (~480 LOC; 5 sections + delete-account modal). Welcome heading split into `dashboard.welcomeWithName` (with `{name}` placeholder) vs `dashboard.welcomeAnon` (no name) so each locale owns its own punctuation. profile.vue's change-password Zod schema in computed; useApiError used in 4 catch blocks (saveName, changePassword, terminateSessions, deleteAccount); onResend keeps imperative 429 state-machine branch but routes generic-error text through useApiError into a new `resendErrorText` ref. Both `navigateTo('/auth/login')` calls wrapped in `localePath`. Empty `catch {}` blocks rewritten to `catch (err: unknown)` so handler receives the error.
**Deviations (planned, recorded per task spec):**
1. Decision 11 correction: `ComputedRef` is passed AS-IS to `useForm` — never `unref()`. The tech-spec text showed `unref()` which is incorrect; vee-validate v4 supports reactive schemas via direct ComputedRef pass.
2. AC18 / Decision 16 authorized exception extension: dashboard.spec and profile.spec each gain one `mockNuxtImport('useLocalePath', () => () => (path) => path)` identity-passthrough mock; UA-literal asserts unchanged.
3. Decision 10 partial application in `onResend`: 429 branch stays imperative (state-machine + cooldown timer); useApiError covers only the generic-error branch — factory returns string and can't express state transitions.

**Reviews:**

*Round 1:*
- code-reviewer: OK (3 minor non-blocking — resendErrorText not cleared on transition; status-extraction duplicated with private helper in useApiError; minor cosmetic ordering) → [logs/working/task-10/code-reviewer-1.json](logs/working/task-10/code-reviewer-1.json)
- security-auditor: OK (2 informational — middleware-comment wording and absence of frontend audit-log signal for destructive ops; both pre-existing) → [logs/working/task-10/security-auditor-1.json](logs/working/task-10/security-auditor-1.json)
- test-reviewer: OK (1 cosmetic — optional inline comment referencing AC18) → [logs/working/task-10/test-reviewer-1.json](logs/working/task-10/test-reviewer-1.json)

**Verification:**
- `pnpm test tests/pages/dashboard.spec.ts tests/pages/profile.spec.ts` → 14 passed
- `pnpm test` → 88 passed (no regressions)
- Cyrillic-grep on dashboard.vue + profile.vue → empty outside `t()`
- `node scripts/check-locales.mjs` → OK

## Task 11: Playwright E2E i18n spec + dev hydration fix

**Status:** Done
**Commit:** 1ab5abb
**Agent:** main agent
**Summary:** Single E2E spec `e2e/i18n.spec.ts` (~22 LOC): visit `/auth/login` (uk default with `test.use({ locale: 'uk-UA' })`), wait for hydration via `networkidle`, click `[data-test="lang-en"]`, poll URL for `/en/auth/login`, assert `<html lang>` flip uk→en, assert button text "Увійти"→"Sign in", fill invalid email + submit, assert EN validation message "Invalid email format". Covers AC9 (atomic toggle: cookie+URL+lang+content), AC11 (URL strategy), AC12 (Zod live-switch via computed schema).
**Deviations:**
- `nuxt.config.ts`: added `devtools: { enabled: false }`. Nuxt DevTools depends on `@vue/devtools-api` which only ships CJS and isn't pre-bundled by Vite in this stack — browser throws `ReferenceError: exports is not defined` at hydration, silently breaking client-side click handlers (LangSwitcher.setLocale, form submit). DevTools is dev-only ergonomics; disabling it is the cleanest fix.
- `nuxt.config.ts`: added `bundle: { optimizeTranslationDirective: false }` on i18n module to suppress upstream deprecation warning (we don't use the `v-t` directive).

**Reviews:**

*Round 1:*
- code-reviewer: OK (2 minor — networkidle wait can be tightened to element-readiness; expect.poll vs page.waitForURL is preference-only) → [logs/working/task-11/code-reviewer-1.json](logs/working/task-11/code-reviewer-1.json)
- security-auditor: OK (1 forward-awareness note — future E2Es submitting valid forms should mock the API to prevent accidental cred transmission) → [logs/working/task-11/security-auditor-1.json](logs/working/task-11/security-auditor-1.json)
- test-reviewer: OK (4 minor — networkidle fragility; cookie not asserted in E2E; Zod live-switch inferred not contrasted; Accept-Language redundancy with test.use locale) → [logs/working/task-11/test-reviewer-1.json](logs/working/task-11/test-reviewer-1.json)

**Verification:**
- `pnpm test:e2e e2e/i18n.spec.ts` → 1 passed (1.7s test runtime)
- `pnpm test` → 88 passed (no regressions)
- `pnpm test:e2e e2e/i18n.spec.ts --list` → prints "switches locale on auth/login page"

## Task 12: Code Audit

**Status:** Done
**Commit:** N/A (audit-only — no code changes)
**Agent:** main agent
**Summary:** Holistic feature-wide audit across all 9 dimensions on 22 source files (Tasks 1-11). Result: 3 findings — 0 blocker, 0 high, 1 medium, 2 low. Pre-deploy QA (Task 15) is unblocked. Findings are maintainability-only: F-001 (medium) DRY duplication of inline status-extraction across 3 catch blocks (reset-password, verify-email, profile.onResend) duplicates useApiError's private extractStatus helper — recommend exporting it; F-002 (low) silentTranslationWarn/silentFallbackWarn are Legacy-mode-only flags inert under `legacy:false` (already approved deviation per Task 2); F-003 (low) errors.verifyEmail.* sub-tree is dead — verify-email.vue uses a state-machine pattern, not useApiError. All authorized exceptions (AC18 / Decision 16 page-spec mocks, Decision 11 ComputedRef-without-unref) verified within scope and not flagged. Full report: [logs/audit/code-audit.json](logs/audit/code-audit.json).
**Deviations:** None.

**Reviews:**

Audit Wave — auditor is the final reviewer; no downstream reviews.

**Verification:**
- `jq .summary logs/audit/code-audit.json` → "3 findings: 0 blocker, 0 high, 1 medium, 2 low. ..."
- `jq '.findings | length' logs/audit/code-audit.json` → 3
- `jq '.dimensions_checked | length' logs/audit/code-audit.json` → 9

## Task 13: Security Audit

**Status:** Done
**Commit:** N/A (audit-only)
**Agent:** main agent
**Summary:** Holistic OWASP Top 10 + 8 focus invariants audit on the final state of feature scope. Result: 0 critical, 0 high, 1 medium, 1 low, 2 informational — Pre-deploy QA unblocked from a security perspective. All 8 invariants PASS with file:line evidence (cookie Secure/SameSite/MaxAge parity; routeRules /auth/** + /en/auth/** parity via NON_DEFAULT_LOCALES; 302-response carries security headers in strip-invalid-locale; open-redirect normalization order; prototype-pollution via Object.create(null) + UNSAFE_KEYS filter; ?token= URLs covered on all paths; fail-closed semantics in auth.global; silentTranslationWarn/Fallback per Decision 9 with documented Legacy-mode-only inertness). Findings: SEC-001 (medium, A05) no test pinning i18n cookie MaxAge — relies on module default; SEC-002 (low, A07) auth.global swallows non-401 fetchUser errors — defense-in-depth note; 2 informational (locale-strip rejects not logged; silentTranslationWarn duplicate of Task 12 F-002). Full report: [logs/audit/security-audit.json](logs/audit/security-audit.json).
**Deviations:** None.

**Reviews:**

Audit Wave — auditor is the final reviewer; no downstream reviews.

**Verification:**
- `jq '.findings | length' logs/audit/security-audit.json` → 4
- `jq '.passes | length' logs/audit/security-audit.json` → 8
- `jq .owasp_summary logs/audit/security-audit.json` → all A01–A10 explicitly checked

## Task 14: Test Audit

**Status:** Done
**Commit:** N/A (audit-only)
**Agent:** main agent
**Summary:** Holistic test audit across the 6 dimensions. Verdict: pass — no blocking findings. Pyramid breakdown matches M-feature target exactly: 4 new unit specs (useApiError 13 cases, LangSwitcher 5, strip-invalid-locale 19, check-locales 7 node:test) + 1 updated middleware spec (auth.global 10 cases) + 1 E2E. Every Testing Strategy bullet from tech-spec maps to a concrete test. AC18 compliance verified — 8 authorized exceptions (auth.global.spec full rewrite per Decision 16 + 7 page specs each gaining ONE useLocalePath identity-passthrough mock); UA literals untouched; tests/stores/auth.spec.ts unmodified. Mock hygiene clean (mockNuxtImport at the right boundary; no real-network in unit layer). Prototype-pollution fixture present and exceeds spec (covers __proto__ AND constructor.prototype). Findings: 1 low (useApiError missing explicit 429 case — covered transitively by 403 fallback); 2 informational (LangSwitcher cookie assertion via spy is canonical per tech-spec; check-locales.test.mjs exceeds requirements). Full report: [logs/audit/test-audit.json](logs/audit/test-audit.json).
**Deviations:** None.

**Reviews:**

Audit Wave — auditor is the final reviewer; no downstream reviews.

**Verification:**
- `jq .verdict logs/audit/test-audit.json` → "pass"
- `jq .blocking_findings logs/audit/test-audit.json` → false
- `jq .pyramid_breakdown logs/audit/test-audit.json` → 4 unit + 1 updated middleware + 1 E2E (M-feature target)

## Task 15: Pre-deploy QA

**Status:** Done
**Commit:** N/A (QA-only — no code changes)
**Agent:** main agent
**Summary:** Final acceptance verdict: **PASS**. All 19 user-spec ACs and 11 tech-spec ACs verified. 96 automated assertions green: Vitest 88/88, node:test 7/7, Playwright E2E 1/1. All 8 user-spec curl smoke scenarios pass on dev (header parity on /auth/** and /en/auth/**; security headers preserved on 302 redirects from invalid prefixes; cookie set/ignored correctly; default-locale prefix /uk stripped; invalid prefix /fr stripped). Build-fail gate works (rm en.json → prebuild exits 1 with descriptive stderr; restore → builds clean). Production-mode cookie attributes confirmed via `pnpm preview` curl: `Secure; SameSite=Lax; Expires` ~1 year (module default per Decision 3 / approved User-Spec Deviation). All three audit reports cleared of blocker/high findings: code-audit 1 medium + 2 low (DRY suggestions and dead errors.verifyEmail.* sub-tree), security-audit 1 medium + 1 low (cookie MaxAge test pin + auth.global silent-error observability), test-audit pass with 1 low + 2 informational. Feature is ready to ship; deploy itself is out of scope per tech-spec footer (no CI/CD exists; frontend bundle only). Full report: [logs/qa/pre-deploy-qa.json](logs/qa/pre-deploy-qa.json).
**Deviations:** None — all approved deviations from prior tasks (cookie MaxAge module default, AC18 page-spec mocks, Decision 11 ComputedRef-without-unref, locale-key flatten in Task 8) are within scope and recorded above.

**Reviews:**

QA — final task, its own verification; no downstream reviews.

**Verification:**
- `pnpm test --run` → 12 files, 88 tests passing
- `node --test scripts/check-locales.test.mjs` → 7 tests passing
- `pnpm test:e2e e2e/i18n.spec.ts` → 1 passed
- 8 curl smoke scenarios all PASS (S1–S8 in JSON report)
- Build-fail test PASS
- Production cookie attributes: `Secure; SameSite=Lax; Expires=Sat, 08 May 2027 …` (1y) ✅
- All audit reports verdicts: 0 blocker, 0 critical, 0 high; only medium/low/informational findings recorded for follow-up backlog
