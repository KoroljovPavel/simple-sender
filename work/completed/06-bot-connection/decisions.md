# Decisions Log: 06-bot-connection

Agent reports on completed tasks. Each entry is written by the agent that executed the task.

---

## Planning-phase decisions (recorded before tasks exist)

These are user-confirmed decisions captured during user-spec planning. They
are durable — every downstream task (tech-spec, implementation) inherits them.

### D1 — Defense-in-depth uniqueness, deviation from existing convention

**Status:** Decided
**Decided by:** main agent + user, planning interview cycle 2, batch 4
**Summary:** For bot uniqueness across the platform we keep BOTH a
service-level pre-check (`findByTelegramBotIdAndStatus`) AND a partial
unique index on `(telegramBotId, status)` filtered to `status=connected`,
catching `DuplicateKeyException` and mapping it to the same 409 as the
pre-check.
**Reason:** The auth/projects modules currently keep uniqueness only at
the service level (per `patterns.md` → "Service-level uniqueness pre-check
(no partial unique index)"). For bot connection the race window matters
more because two unrelated owners could each paste the same token at the
same time, and the consequence of a missed conflict is two projects
believing they own the same Telegram identity — recovery is messy.
A partial unique index closes the race definitively. Cost: one extra
index + one extra exception-mapping path.
**Recommendation:** Update `patterns.md` after this feature lands to
say the index option is acceptable when race semantics are critical.

### D2 — Webhook secret stored as SHA-256 hash, not in plaintext or encrypted

**Status:** Decided
**Decided by:** main agent + user, planning fix round (post-validation)
**Summary:** The per-project webhook secret token is generated as a
random byte string at Connect time, sent in plaintext to Telegram via
`setWebhook`, and stored on our side ONLY as a SHA-256 hash. 06b will
verify incoming requests by hashing the `X-Telegram-Bot-Api-Secret-Token`
header value and comparing against the stored hash.
**Reason:** The webhook secret is as security-sensitive as the bot
token. Asymmetric protection (token encrypted, secret plaintext) was
called out as a critical finding by the adequacy validator. A SHA-256
hash gives stronger protection than AES at lower complexity: a Mongo
backup leak reveals only hashes, and an attacker cannot forge incoming
updates without the plaintext secret.

### D3 — No `keyVersion` field on `bots`; simple single-key encryption

**Status:** Decided
**Decided by:** main agent + user, planning fix round (post-validation)
**Summary:** The bot token is encrypted with a single configured key.
The `bots` document does NOT carry a per-document key version marker.
The single env var is `BOT_TOKEN_ENCRYPTION_KEY`. If rotation is ever
needed, a one-off migration job re-encrypts the small bot table; the
schema is not pre-shaped for rotation.
**Reason:** YAGNI per the adequacy validator. Hardcoded `keyVersion=1`
with no rotation logic is bookkeeping with no consumer. The bot table is
small enough that a future migration is acceptable.

### D4 — Compensating `deleteWebhook` on Connect-path persist failure

**Status:** Decided
**Decided by:** main agent + user, planning fix round (post-validation)
**Summary:** If `setWebhook` succeeds but the subsequent Mongo persist
fails, the Connect endpoint issues a best-effort `deleteWebhook` before
returning 500 to the user. This is symmetric to the Disconnect-path
best-effort policy (AC13).
**Reason:** Without compensation, a persist failure leaves Telegram with
a live webhook the system doesn't know about — orphan, no cleanup path.
Same risk class as Disconnect-with-Telegram-down (R5), now symmetric.

### D5 — Per-project pre-check on Connect

**Status:** Decided
**Decided by:** main agent + user, planning fix round (post-validation)
**Summary:** The Connect endpoint pre-checks that the project does not
already have a `status=connected` bot. If it does, the endpoint returns
409 `bot_already_in_project` ("another bot is already connected —
disconnect it first"). This is distinct from the platform-wide
`bot_already_connected` 409 (AC6).
**Reason:** The platform-wide partial unique index protects against the
same Telegram identity in two projects, but does NOT prevent a project
from accumulating two different connected bots. Without this check, a
second concurrent Connect with a different token would succeed and
create a second `status=connected` document for the same project.

### D6 — "Bot not connected" banner on project home is out of scope of 06

**Status:** Decided
**Decided by:** main agent + user, planning fix round (post-validation)
**Summary:** The originally proposed warning banner ("Bot not connected
— connect to start receiving subscribers") on the project home /
dashboard is NOT included in this feature. Discovery of bot-not-
connected state lives in Settings → Bot only.
**Reason:** The banner adds cross-page UX surface that depends on a
broader dashboard story we haven't designed. Adding it here would risk
inconsistency with later UX work. Recorded as an explicit
out-of-scope item in user-spec.

### D7 — Test Message endpoint exists in 06 with graceful 422 fallback

**Status:** Decided
**Decided by:** main agent + user, planning interview cycle 2, batch 4
**Summary:** The `test-message` endpoint and the UI button both exist
in 06. Until 06b/Epic 05 populate the `subscribers` collection with
incoming chat_ids, the endpoint returns 422 with a clear "send /start
first" message. After 06b lands the endpoint starts working with no
code changes in this feature.
**Reason:** Forward-compatible — the contract lands once. Deferring the
endpoint entirely would mean a coordinated 06+06b deploy to add it.
The 422 message is precise enough that users understand the next step;
inline UI hint matches.

### D8 — Telegram 4xx on setWebhook is treated separately from 5xx/timeout

**Status:** Decided
**Decided by:** main agent, planning fix round (post-validation)
**Summary:** Telegram 5xx or network timeout on `setWebhook` after
retry exhaustion returns 502 `telegram_unavailable` (transient
external). Telegram 4xx with a config-related description (HTTPS
required, etc.) returns 500 `webhook_config_error` (our bug, surfaced
in operator logs).
**Reason:** Conflating the two would hide configuration errors behind
a "Telegram is currently unavailable" message and prevent operators
from noticing they shipped an HTTP `APP_URL` to production.
**User-facing message for 500:** generic "Webhook configuration error
— contact support" (i18n key `errors.bot.connect.500`). The precise
Telegram description never reaches the user — it lives in operator
logs only. The user cannot self-correct a config bug, so the message
is intentionally non-actionable; the actionable detail belongs to the
operator.

---

## Task entries (added by agents as tasks complete)

<!-- Format:

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

## Task 1: TokenEncryptor + configuration scaffolding (AES-256-GCM crypto utility)

**Status:** Done
**Commit:** `91b499b` (implementation) + `dfecdc0` (review round 1 fixes)
**Agent:** main agent
**Summary:** Added `com.botfunnel.common.crypto.{TokenEncryptor,EncryptedValue}` — Spring-managed AES-256-GCM wrapper with 12-byte SecureRandom IV, 128-bit auth tag, and constructor-time fail-fast key validation that refuses to start the application context when `BOT_TOKEN_ENCRYPTION_KEY` is missing, non-hex, or not exactly 32 bytes (D3, D15). Both bot-module properties (`app.bot.token-encryption-key`, `app.telegram.base-url`) landed in one pass to avoid a Wave 1 merge conflict with Task 2; `.env.example` documents `BOT_TOKEN_ENCRYPTION_KEY` with the "openssl rand -hex 32 / NEVER commit" guidance; `application-test.properties` seeds an all-zeros placeholder so downstream `@SpringBootTest`s boot the bean without env vars.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: 4 findings (2 minor, 2 low) → [logs/working/task-1/code-reviewer-1.json](logs/working/task-1/code-reviewer-1.json)
- security-auditor: 3 low findings → [logs/working/task-1/security-auditor-1.json](logs/working/task-1/security-auditor-1.json)
- test-reviewer: 5 findings (3 minor, 2 low) → [logs/working/task-1/test-reviewer-1.json](logs/working/task-1/test-reviewer-1.json)

Applied (round 1 → fix commit `dfecdc0`): boot-validation test refactored to walk the cause chain via a single helper + class-level Javadoc (C1, C2, T3); hex-parse error message no longer splices the JDK `parseHex` output that could echo a fragment of a misconfigured key (S1) + regression test `hexParseErrorMessageDoesNotEchoKeyBytes`; added NUL-byte round-trip (T1); parameterized null/empty key path (T5) and wrong-length IV decrypt (T2) plus explicit null-IV case; simplified `tamperedIvRejected` to a one-bit XOR (T4).

Skipped (per task spec / D3 / D15): error-message env-var name vs. property name (C3 — spec mandates wording); `EncryptedValue` record byte[] identity equality and defensive-copy (C4, S2 — spec mandates record shape, in-process trust boundary); `SecretKeySpec` on heap for JVM lifetime (S3 — out of scope per D3).

*Round 2 (after fixes):*
- code-reviewer: OK, 2 optional stylistic notes → [logs/working/task-1/code-reviewer-2.json](logs/working/task-1/code-reviewer-2.json)
- security-auditor: OK, zero findings → [logs/working/task-1/security-auditor-2.json](logs/working/task-1/security-auditor-2.json)
- test-reviewer: OK, zero findings → [logs/working/task-1/test-reviewer-2.json](logs/working/task-1/test-reviewer-2.json)

Round-2 optional notes (skipped — clearly optional, would add noise/YAGNI per project conventions): regex-tightening of the hex-error regression assertion; visited-set in the test-only cause-chain walker.

**Verification:**
- `./gradlew test --tests "com.botfunnel.common.crypto.*"` → 18 passed (10 unit + 1 NUL-byte + 5 IV-length + bonus + 4 boot-validation cases via ApplicationContextRunner)
- `./gradlew test` (full backend suite) → BUILD SUCCESSFUL — no regressions in existing modules
- `./gradlew compileJava` → BUILD SUCCESSFUL — no new dependencies needed

## Task 2: TelegramApiClient (reactive WebClient bean)

**Status:** Done
**Commit:** `976106e` (implementation) + `51e3dc5` (review round 1 fixes)
**Agent:** main agent
**Summary:** Added `com.botfunnel.bot.TelegramApiClient` — first reactive `WebClient` bean in the codebase — wrapping Telegram Bot API with Reactor Netty `responseTimeout(10s)`, `Retry.backoff(3, 200ms, maxBackoff=2s)` filtered to transient failures (5xx WCRE, IOException, TimeoutException, Netty `ReadTimeoutException` including wrapped causes via 16-hop chain walker), and synchronous `onStatus` 4xx mapping: 401 → 422 `invalid_bot_token`, config-related 4xx → 500 `webhook_config_error`, fallback 4xx → generic `Telegram client error` (D8/D11/D12/D13). Token-regex scrubber redacts `\d{1,20}:[A-Za-z0-9_-]{30,50}` to `[REDACTED_TOKEN]` before every WARN log that echoes Telegram `description` (R1/AC17), plus a `requireValidTokenShape` boundary guard on every public method. DTOs `TelegramResult<T>` and `TelegramUser` ship with literal snake_case fields matching Telegram contract; `mockwebserver:4.12.0` pinned as `testImplementation` (D9, D10).
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: 5 findings (3 minor, 2 low) → [logs/working/task-2/code-reviewer-1.json](logs/working/task-2/code-reviewer-1.json)
- security-auditor: 6 findings (2 medium, 4 low) → [logs/working/task-2/security-auditor-1.json](logs/working/task-2/security-auditor-1.json)
- test-reviewer: 8 findings (2 medium, 6 low) → [logs/working/task-2/test-reviewer-1.json](logs/working/task-2/test-reviewer-1.json)

Applied (round 1 → fix commit `51e3dc5`): method-reference style on `onStatus` (C2); `ClientResponse` import (C3); `.onErrorResume` on body-parse failure (C4) + bonus test for non-JSON 4xx; `getMe` null-guard via `.flatMap` mapping empty result to 502 (C5); `requireValidTokenShape` defense-in-depth guard on every public method (S1); generic user-facing message + scrubbed-WARN log for non-standard 4xx fallback (S3); 16-hop cap on cause-chain walker (S4); SSRF Javadoc on `setWebhook` (S6); decoupled `CONNECT_TIMEOUT=5s` constant from `responseTimeout` (T4); test additions for 4xx-fallback (T1), end-to-end scrubber→WARN coupling (T2), regex boundary envelope `{1,20}/{30,50}` (T3/S5), `takeRequest(2s)` (T5), JSON body parsing with `hasSize(2)` (T6), `deleteWebhook` happy path (T7), `requestCount==1` on 4xx-config (T8).

Skipped: drop `@Autowired` on public constructor (C1) — reviewer missed the package-private 3-arg test-seam constructor; Spring requires `@Autowired` to disambiguate between two non-private constructors. Project-wide Logback message converter for global token scrubbing (S2) — out of scope for the client task; call-site scrubber covers every code path this client emits at WARN/ERROR, and the project does not enable reactor DEBUG.

*Round 2 (after fixes):*
- code-reviewer: OK → [logs/working/task-2/code-reviewer-2.json](logs/working/task-2/code-reviewer-2.json)
- security-auditor: OK → [logs/working/task-2/security-auditor-2.json](logs/working/task-2/security-auditor-2.json)
- test-reviewer: OK → [logs/working/task-2/test-reviewer-2.json](logs/working/task-2/test-reviewer-2.json)

**Verification:**
- `./gradlew test --tests "com.botfunnel.bot.TelegramApiClientTest"` → 13 passed (happy/401/5xx-retry/4xx-config/4xx-blank-fallback/4xx-non-JSON-fallback/Netty-read-timeout/setWebhook-body/setWebhook-scrubs-token-in-WARN/scrubber-edges/scrubber-boundaries/deleteWebhook-happy/deleteWebhook-retry)
- `./gradlew test` (full backend suite) → BUILD SUCCESSFUL — no regressions in existing modules
- `./gradlew compileJava` → BUILD SUCCESSFUL

## Task 3: Bot domain layer (entity, status enum, repository, indexes)

**Status:** Done
**Commit:** `579f86c` (implementation) + `506c479` (review round 1 fixes)
**Agent:** main agent
**Summary:** Added `com.botfunnel.bot.Bot` (`@Document(collection = "bots")`) with the exact tech-spec field set and three class-level `@CompoundIndex` declarations — non-unique `projectId_status` plus two partial-unique compounds on `telegramBotId` and `projectId`, both filtered to `status='CONNECTED'` to close the D1 / D5 race windows. Added `BotStatus` enum (CONNECTED, DISCONNECTED) with `@JsonValue` lowercase + `@JsonCreator fromString` for the HTTP wire form, leaving Spring Data MongoDB to persist the Java `name()` uppercase form so the partial filter expression keeps matching. Added `BotRepository extends ReactiveMongoRepository<Bot, String>` with the three derived queries `BotService` (Task 6) needs. Strict persistence shape — no business logic, no encryption, no service wiring.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: 3 minor (all optional, approved) → [logs/working/task-3/code-reviewer-1.json](logs/working/task-3/code-reviewer-1.json)
- security-auditor: 1 major + 3 minor (approved) → [logs/working/task-3/security-auditor-1.json](logs/working/task-3/security-auditor-1.json)
- test-reviewer: 4 minor (all on BotRepositoryTest discrimination, passed) → [logs/working/task-3/test-reviewer-1.json](logs/working/task-3/test-reviewer-1.json)

Applied (round 1 → fix commit `506c479`): field-level `@JsonIgnore` on the four sensitive Bot fields — `encryptedTokenCiphertext`, `encryptedTokenIv`, `tokenSuffix`, `webhookSecretHash` (security major; D17 forbids round-tripping the full token; defense-in-depth before Task 6 / 06b / 06c can land); class-load static-initializer assertion that `BotStatus.CONNECTED.name()` still equals the partial-filter literal `"CONNECTED"`, so a future enum rename fails at `<clinit>` instead of silently voiding the D1/D5 race indexes; `BotStatus.fromString` null guard returning `null` so Jackson null delegation paths surface as 400 instead of a 500 NPE; static `assertThat` import + four FQN replacements in `BotRepositoryTest`; one-line intent comment in `BotStatusJsonTest` about the bare `ObjectMapper`. Test discrimination strengthening: `findByProjectIdAndStatus_returnsEmptyWhenNoConnectedBot` now seeds a CONNECTED row for a different project (proves projectId filter is applied); `findFirstByTelegramBotIdAndStatus_returnsConnectedBotByTelegramId` now seeds a DISCONNECTED row with the same `telegramBotId` in a different project (proves status filter is applied); `findByProjectId_returnsAllStatusesForProject` switched from `expectNextCount(2)` to a `collectList()`-recorded assertion checking both statuses, both `telegramBotId`s, and that every row's `projectId` matches.

Skipped: explicit-switch refactor in `BotStatus.fromString` (code-reviewer #3) — the null guard above closes the practical hazard and the spec literally calls for `valueOf(s.toUpperCase())`. Explicit `Bot.toString()` override (security #3) — default `Object#toString` is the safe form today; out of scope for a persistence-shape-only task.

*Round 2 (after fixes):*
- code-reviewer: OK → [logs/working/task-3/code-reviewer-2.json](logs/working/task-3/code-reviewer-2.json)
- security-auditor: OK → [logs/working/task-3/security-auditor-2.json](logs/working/task-3/security-auditor-2.json)
- test-reviewer: OK → [logs/working/task-3/test-reviewer-2.json](logs/working/task-3/test-reviewer-2.json)

**Verification:**
- `./gradlew test --tests "com.botfunnel.bot.BotStatusJsonTest" --tests "com.botfunnel.bot.BotRepositoryTest" --tests "com.botfunnel.bot.BotIndexTest"` → 11 passed (2 Jackson round-trip + 4 derived-query + 5 partial-unique-index enforcement / presence)
- `./gradlew test` (full backend suite) → BUILD SUCCESSFUL — no regressions
- Smoke: literal `mongosh botfunnel --eval 'db.bots.getIndexes()'` not run — neither `mongosh` nor a local dev Mongo present in this environment. The same assertion is exercised by the automated `BotIndexTest::indexes_areCreatedOnCollection` test (Testcontainers Mongo, same Spring `auto-index-creation=true` pathway, verifies all three index names, both unique-flag values, and the partial-filter expression `{ status: "CONNECTED" }` parsed via `Document.parse(getPartialFilterExpression())`).

## Task 4: Frontend i18n keys (bot.* and errors.bot.*)

**Status:** Done
**Commit:** `7170f72` (implementation) + `d65bae5` (review round 1 fix)
**Agent:** main agent
**Summary:** Added a new top-level `bot` block (subnav, placeholder, page, connect.form, connected, disconnect.modal, toasts) and the `errors.bot` subtree (per-action `generic` for all three actions + status-keyed leaves exactly matching AC22: connect 400/409/422/429/500/502, testMessage 422/502, disconnect 404) to both `en.json` and `uk.json`. All paths are canonical dot-segmented (`bot.connect.form.*`, `bot.disconnect.modal.*`), and `bot.disconnect.modal.body` uses the `{username}` named interpolation placeholder Task 9 will pass through `t(...)`. The `errors.bot.connect.409` copy is AC6/R2-safe — generic in both locales, no foreign-project name or owner identifier. The Task 5 lock-step keys (`bot.subnav.general`, `bot.subnav.bot`, `bot.placeholder`) are included as planned beyond AC22.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: 1 minor (Ukrainian tone consistency) → [logs/working/task-4/code-reviewer-1.json](logs/working/task-4/code-reviewer-1.json)
- security-auditor: OK, zero findings → [logs/working/task-4/security-auditor-1.json](logs/working/task-4/security-auditor-1.json)
- test-reviewer: 1 major + 2 minor (approved) → [logs/working/task-4/test-reviewer-1.json](logs/working/task-4/test-reviewer-1.json)

Applied (round 1 → fix commit `d65bae5`): switched `errors.bot.connect.500` and `bot.toasts.webhookConfigError` Ukrainian copy from formal "зверніться" to informal "звернись" so the entire new bot subtree stays in the second-person-singular voice mandated by the task. Test-reviewer's major (parity gate enforces key-set only, not values) addressed in the Verification block below — recorded jq content checks for AC19 `{username}`, AC22 exact-leaves, no-401, AC6/R2 generic 409 copy.

Skipped: test-reviewer minor on behavioral TDD anchor (deferred to Tasks 5/9 component tests per task spec — JSON-only edit is acceptably structural in Wave 1).

*Round 2 (after fixes):*
- code-reviewer: OK, zero findings → [logs/working/task-4/code-reviewer-2.json](logs/working/task-4/code-reviewer-2.json)

**Verification:**
- `cd frontend && pnpm prebuild` → exit 0 (parity gate `check-locales.mjs` confirms en.json/uk.json flat key sets are symmetric, 37 new keys per locale).
- Smoke (failing-state branch): deleted `errors.bot.connect.429` from `en.json`, re-ran `pnpm prebuild` → exit 1 with stderr `Keys present in uk.json but missing in en.json: errors.bot.connect.429`; restored → exit 0.
- AC19: `jq -r '.bot.disconnect.modal.body'` contains `{username}` in both locales.
- AC22 exact-leaves: `jq -r '.errors.bot.connect | keys | sort | join(",")'` → `400,409,422,429,500,502,generic` (both locales); `.errors.bot.testMessage` → `422,502,generic`; `.errors.bot.disconnect` → `404,generic`.
- No 401: `jq '[paths(scalars) | select(.[0]=="bot" or (.[0]=="errors" and .[1]=="bot")) | join(".")] | map(select(test("401"))) | length'` → `0` (both locales).
- AC6/R2: `.errors.bot.connect.409` → "This bot is already connected to another project." / "Цей бот уже підключений до іншого проєкту." — no foreign-project identity in either locale.

## Task 5: Frontend Settings route restructure (folder split + sub-nav scaffolding)

**Status:** Done
**Commit:** `0786228` (implementation) + `199537c` (review round 1 fixes)
**Agent:** main agent
**Summary:** Converted single-page `frontend/pages/projects/[projectId]/settings.vue` into a Nuxt folder route (`settings/index.vue` near-verbatim + `settings/bot.vue` placeholder) and added `frontend/components/SettingsSubnav.vue` with two `<NuxtLinkLocale>` tabs. Sidebar link from `layouts/default.vue` still resolves to `settings/index.vue` via Nuxt's automatic folder-route handling (D10, AC21). Active state for the sub-nav is computed manually from `useRoute().path` and surfaced via `aria-current="page"` + a Tailwind class — see Deviations.
**Deviations:** Two intentional deviations from the task literal text. (1) Active state via manual computed instead of Vue Router's `exact-active-class` prop. Reason: `mockNuxtImport('useRoute')` in vitest swaps only the auto-import — RouterLink's internal `currentRoute` still resolves to `/` under `@nuxt/test-utils` `mountSuspended`, making the "General not active on /bot" regression contract un-testable through the prop. The manual computed delivers the same user-visible contract (General does not light up on `/settings/bot`), strips the locale prefix sourced from `frontend/shared/i18n-locales.ts → NON_DEFAULT_LOCALES` so `/en/...` paths still resolve, and emits `aria-current="page"` for accessibility. (2) Two locale strings in `frontend/i18n/locales/{en,uk}.json` authored by Task 4 — `bot.connect.form.tokenHint` and `bot.disconnect.modal.body` — escaped the literal `@` using vue-i18n v9 literal-interpolation `{'@'}` (per Intlify docs). Without this fix the entire `pnpm test` run failed at the module-resolution stage with `[unplugin-vue-i18n] Invalid linked format`, which blocked Task 5's automated verification. The change is purely a parser-escape (no behavioural change to rendered text — `t('bot.disconnect.modal.body', { username })` still produces `@somebot`).

**Reviews:**

*Round 1:*
- code-reviewer: 5 minor → [logs/working/task-5/code-reviewer-1.json](logs/working/task-5/code-reviewer-1.json)
- security-auditor: 1 low + 1 info → [logs/working/task-5/security-auditor-1.json](logs/working/task-5/security-auditor-1.json)
- test-reviewer: 4 minor → [logs/working/task-5/test-reviewer-1.json](logs/working/task-5/test-reviewer-1.json)

Applied (round 1 → fix commit `199537c`): locale-prefix regex sourced from `NON_DEFAULT_LOCALES` constant (`uk` dropped — it's the default under `strategy: prefix_except_default`); `linkBase` / `linkActive` class-string consts extracted; `data-test="bot-page"` hook on `bot.vue` root; `aria-current="page"` emitted on the active `<NuxtLinkLocale>` and asserted as the primary active-state contract in tests (Tailwind class kept only as a secondary CSS-regression signal); `localePathSpy.toHaveBeenCalledWith(...)` assertions added so the `useLocalePath` dependency is explicit despite the identity-mock constraint (a non-identity transform double-prefixes via `NuxtLinkLocale`'s own locale resolution); symmetric `/en/projects/p1/settings` General-active case added to the spec; `bot.spec.ts` smoke turned into a wiring check that asserts the rendered General href is `/projects/p1/settings`, so dropping `:project-id` collapses the href and trips the test; `t()`-key text assertions added in the "renders two tabs" case so a hardcoded English literal regression breaks. The `{'@'}` literal-escape fix to Task 4 locale strings is recorded here per code-reviewer #5 — the change lives in this commit so future Task 4 re-touches do not silently revert it.

Deferred: `bot.vue` stale-projectId 404-redirect (security-auditor low) — the placeholder ships zero project-scoped data; Task 9 owns the full page and the AC21 onMounted hydration guard. Tracked as a Task 9 prerequisite, not a Task 5 regression.

*Round 2 (after fixes):*
- code-reviewer: OK → [logs/working/task-5/code-reviewer-2.json](logs/working/task-5/code-reviewer-2.json)
- security-auditor: OK, zero findings → [logs/working/task-5/security-auditor-2.json](logs/working/task-5/security-auditor-2.json)
- test-reviewer: OK → [logs/working/task-5/test-reviewer-2.json](logs/working/task-5/test-reviewer-2.json)

**Verification:**
- `cd frontend && pnpm test` → 203 passed (was 199; +4 new: 5 SettingsSubnav cases + 1 bot.spec wiring case, minus the round-1 settings.spec import-path migration which was a single-line update to an existing 19-case suite).
- `cd frontend && pnpm prebuild` → exit 0 (locale parity gate still green after the `{'@'}` escape; the escape leaves the flat key sets identical between en.json and uk.json).
- Manual user verification of the sub-nav UI / sidebar link / Bot tab navigation: deferred to end-of-feature smoke per user instruction at task closeout — user opted to verify the whole Wave 1 stack together rather than per-task.

## Task 6: BotService — full lifecycle orchestration

**Status:** Done
**Commit:** `e25418e` (implementation) + `978afbf` (review round 1 fixes)
**Agent:** main agent
**Summary:** Added `com.botfunnel.bot.BotService` orchestrating Connect, Disconnect, Get, TestMessage — the only place that composes Wave-1 building blocks (`TokenEncryptor`, `TelegramApiClient`, `Bot/BotRepository`) into the documented pipeline. Side-effect order is locked in by `Mono.defer` wrappers (requireOwned → INCR brute-force → per-project pre-check → getMe → platform-wide pre-check → setWebhook → encrypt → save → bot_connected event → DEL brute-force). Compensating `deleteWebhook` covers persist failure (raw and both `DuplicateKeyException` variants — `projectId_unique_connected` → 409 `bot_already_in_project`, `telegramBotId_unique_connected` → 409 `bot_already_connected`, driver-omits-index recheck fallback). Disconnect is best-effort against Telegram (AC13) and atomically nulls the encrypted-token + IV + tokenSuffix + webhookSecretHash fields. `sendTestMessage` short-circuits with 422 `owner_chat_id_unknown` and emits **no** event (D7). `BotService` owns the Base64 boundary for the `{iv, ciphertext}` round-trip (D15). Brute-force fail-open observability is delivered via a dedicated greppable WARN line — no actuator / Micrometer dependency was introduced (the project does not yet have one). 23 Mockito + StepVerifier unit tests cover every TDD anchor plus three round-1 reinforcements (AC10 URL + per-Connect secret freshness, AC11 EXPIRE-on-first-hit / no-TTL-refresh-on-second-attempt, S1 token-scrub regression on Disconnect WARN). No controller, DTO, or integration test in this task — those land in Task 8.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: 3 findings (2 minor, 1 low) → [logs/working/task-6/code-reviewer-1.json](logs/working/task-6/code-reviewer-1.json)
- security-auditor: 1 high + 3 low → [logs/working/task-6/security-auditor-1.json](logs/working/task-6/security-auditor-1.json)
- test-reviewer: 7 findings (3 major, 4 minor) → [logs/working/task-6/test-reviewer-1.json](logs/working/task-6/test-reviewer-1.json)

Applied (round 1 → fix commit `978afbf`): S1 — promoted `TelegramApiClient.scrubTokens` to `public static` and applied it to both BotService Telegram-error WARN call sites (Disconnect + Connect-rollback) so a non-transient WebClient transport-error whose message embeds the token-bearing URI is redacted before logging; new regression test `disconnect_telegramErrorMessageWithTokenInUri_isScrubbedInWarnLog`. S2 — corrected the `doDisconnect` comment that overstated plaintextToken lifetime. C1 — extracted `requireConnectedBot` helper, deduplicating the read prologue across Get / Disconnect / TestMessage. C2 — clarified the intent of the `count != null` guards in `incrementBruteForceCounter` (defensive against non-spec emit; genuine null surfaces via outer fail-open). T1 — happy-path InOrder now captures the webhook URL string and asserts equality with `${app.url}/webhooks/telegram/{projectId}`; new `connect_secretIsFreshlyDrawnPerInvocation` proves two consecutive Connects produce distinct 32-hex-char secrets. T2 — happy-path InOrder verifies the conditional `expire(900s)` on count==1; new `connect_secondAttemptDoesNotResetTtl` (count=2) pins `never().expire(...)`; 11th-attempt path adds the same negative assertion. T3+T6 — `bot_connected` metadata captor with `containsOnlyKeys` + `containsEntry` assertions inside the InOrder block. T4 — Telegram-down WARN pinned to a dedicated substring constant. T5 — Redis-down-on-INCR fail-open litmus now verifies setWebhook + save + bot_connected event actually ran after the swallow. T7 — InOrder added on the four negative branches (rate-limit / disconnect-404 / sendTestMessage-404 / getByProject-404) so AC16's "requireOwned is the first reactive step" invariant is locked in even on short-circuit paths.

Skipped (per task spec / design): S3 — both-index-substrings-present `DuplicateKeyException` is a theoretical driver edge case Mongo does not produce; the existing recheck fallback already handles the unknown case authoritatively. S4 — `tokenSuffix` 3-char retention is part of design D17 / AC1 / AC18; documented as residual risk in the security report. C3 — `secureRandom` field-init style is cosmetic and matches `TokenEncryptor`.

*Round 2 (after fixes):*
- code-reviewer: OK, zero findings → [logs/working/task-6/code-reviewer-2.json](logs/working/task-6/code-reviewer-2.json)
- security-auditor: OK, zero findings → [logs/working/task-6/security-auditor-2.json](logs/working/task-6/security-auditor-2.json)
- test-reviewer: OK, zero findings → [logs/working/task-6/test-reviewer-2.json](logs/working/task-6/test-reviewer-2.json)

**Verification:**
- `./gradlew test --tests "com.botfunnel.bot.BotServiceTest"` → 23 passed (every TDD anchor + AC10 URL/secret freshness + AC11 EXPIRE-on-first-hit + AC11 no-TTL-refresh-on-2nd-attempt + S1 scrub regression)
- `./gradlew test` (full backend suite) → BUILD SUCCESSFUL — no regressions in existing modules (Token Encryptor, TelegramApiClient, Bot domain layer, auth, project, events, security, profile, jobs)
- Verify-smoke per task spec is the same set of mocked-collaborator unit tests (no live process required); no separate Smoke / User section in tech-spec for Task 6.

---

## Task 7: Frontend bot store + types

**Status:** Done
**Commit:** `794ce40` (implementation) + `7a064dd` (review round 1 fixes) + `5b2839c` (review round 2 fixes)
**Agent:** main agent
**Summary:** Added `frontend/types/bot.ts` (lowercase `BotStatus` union + `Bot` interface mirroring backend `BotResponse` from tech-spec lines 309–316) and `frontend/stores/bot.ts` — a Pinia setup-store named `'bot'` exposing `current`, `fetch`, `connect`, `disconnect`, `sendTestMessage`. `inFlight` is module-scoped with dedup keyed by `projectId` and stale-write protection keyed by run-promise identity, so concurrent same-project calls share one request and an A→B→A re-selection cannot let an older same-project run stomp `current` with stale data. The `connect()` token is write-only — passed only inside the $fetch POST body, never on a ref, never in the URL — and `connect()`'s catch block unconditionally redacts `err.options.body` (handles both the realistic JSON-string shape ofetch actually attaches and the defensive object shape) before rethrowing, preserving `response.status` for downstream `useApiError` / 404 interceptor consumers. A `watch` on `useProjectsStore().currentProjectId` synchronously clears `current` on every transition and (client-only) refetches when the new id is non-null. 18 vitest tests cover every TDD anchor plus three regression tests added in review (A→B→A re-selection, FetchError serialized-string body redaction, disconnect failure leaves current intact).
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: 1 major + 3 minor + 1 low → [logs/working/task-7/code-reviewer-1.json](logs/working/task-7/code-reviewer-1.json)
- security-auditor: 3 minor (0 critical, 0 major) → [logs/working/task-7/security-auditor-1.json](logs/working/task-7/security-auditor-1.json)
- test-reviewer: 3 major + 4 minor → [logs/working/task-7/test-reviewer-1.json](logs/working/task-7/test-reviewer-1.json)

Applied (round 1 → fix commit `7a064dd`): BOT-STORE-001 (project-switch-mid-fetch stale-write — initial fix was a projectId-keyed identity check) + SEC-T7-001 (FetchError options.body token redaction, initial attempt gated on `typeof === 'object'`) + F1 (pre-seed `current` before 404 assertion so a regression is detectable) + F2 (follow-up call after dedup test asserts `inFlight` was reset) + F3 (new "late-arriving fetch after project switch" regression test) + F4 (split sendTestMessage into success / failure tests) + F5 ($state token-free invariant on connect-failure) + F6 (new disconnect-failure test) + new "connect scrubs token from FetchError" test.

Skipped (per task spec / design): BOT-STORE-002 (SSR watcher gate — synchronous clear is a no-op on SSR and Vue does not fire watchers there). BOT-STORE-003 (`fetch` shadowing the global — task contract names the action `fetch`). BOT-STORE-004 (double-click guard on disconnect/sendTestMessage — Task 9 UI responsibility). BOT-TYPES-001 (telegramBotId number future-proofing comment — rationale already documented in the task description). SEC-T7-002 (Pinia devtools action-trace records token argument — no setup-store API to opt out; production builds disable Vue devtools; residual documented inline in `connect()`). SEC-T7-003 (URL-encode projectId — `stores/projects.ts` does not encode either and IDs are backend-controlled UUIDs). F7 (type-level test for `BotStatus` casing — TypeScript compile-time guard is sufficient).

*Round 2 (after fixes):*
- code-reviewer: 1 major (BOT-STORE-005 — A→B→A re-selection regression introduced by the projectId-keyed identity check from round 1) → [logs/working/task-7/code-reviewer-2.json](logs/working/task-7/code-reviewer-2.json)
- security-auditor: 1 major (SEC-T7-002 round-2 — `typeof opts.body === 'object'` guard is a no-op in production because ofetch JSON-stringifies the body before dispatch) → [logs/working/task-7/security-auditor-2.json](logs/working/task-7/security-auditor-2.json)
- test-reviewer: 0 major, 1 minor (round-1 fixes verified resolved) → [logs/working/task-7/test-reviewer-2.json](logs/working/task-7/test-reviewer-2.json)

Applied (round 2 → fix commit `5b2839c`): BOT-STORE-005 (switch stale-write guard from `inFlight?.projectId === projectId` to `inFlight?.promise === run` at all three call sites, with `let run!: Promise<Bot | null>` to capture the promise in the IIFE closure; new "rapid A→B→A re-selection" regression test). SEC-T7-002 round-2 (unconditional redaction `if (opts) opts.body = '[redacted]'` covers both the real ofetch JSON-string shape and the defensive object shape; the round-1 test was renamed to label its defensive intent and a new test mocks the realistic JSON-string body).

*Round 3 (after fixes):*
- code-reviewer: OK, zero findings; A→B→A test would fail under the reverted projectId-only guard → [logs/working/task-7/code-reviewer-3.json](logs/working/task-7/code-reviewer-3.json)
- security-auditor: OK, zero findings; both body shapes redacted, `response.status` preserved → [logs/working/task-7/security-auditor-3.json](logs/working/task-7/security-auditor-3.json)

**Verification:**
- `cd frontend && pnpm test tests/stores/bot.spec.ts` → 18 passed (every TDD anchor + A→B→A regression + dedup-reset litmus + 404 pre-seed litmus + FetchError redaction for both JSON-string and object body shapes + disconnect-failure + sendTestMessage success/failure split + no-localStorage spy)
- `tsc --noEmit -p .nuxt/tsconfig.app.json` → no errors in `types/bot.ts`, `stores/bot.ts`, `tests/stores/bot.spec.ts` (pre-existing errors in unrelated files are out of scope)
- No `Smoke` / `User` section in the task — verification-automated section is the unit-test command above.

---

## Task 8: BotController + DTOs + integration tests

**Status:** Done
**Commit:** 6845945 (round-1 fixes; impl in 91c6842)
**Agent:** main agent
**Summary:** Built the HTTP layer of the bot module on top of `BotService` (Task 6) — `BotController` mapping `/api/v1/projects/{projectId}/bot` with `GET /`, `POST /connect`, `POST /disconnect`, `POST /test-message`; `ConnectBotRequest` carrying `@JsonIgnoreProperties(ignoreUnknown=true)` + `@NotBlank @Pattern("^\\d{1,20}:[A-Za-z0-9_-]{30,50}$")` (D16 short-circuits malformed tokens before the service runs); `BotResponse` exposing only `tokenSuffix` + non-secret fields (D17). Companion `BotControllerIT` (23 scenarios incl. unauth 401 sweep) drives the full pipeline against a class-level `MockWebServer` registered via `@DynamicPropertySource`, using `@MockitoSpyBean` on `BotRepository` + `ReactiveRedisTemplate` for the persist-failure and Redis-down branches with `Mockito.reset(...)` in `@BeforeEach` for isolation. `BotTokenLeakTest` enforces the AC17 / R1 invariant reflectively (no encrypted-token component on `BotResponse`, no custom `toString` on `Bot`).
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: approve, 6 low/info → [logs/working/task-8/code-reviewer-1.json](logs/working/task-8/code-reviewer-1.json)
- security-auditor: approve, 1 info (X-Forwarded-For trust — pre-existing platform pattern from `ProjectController`/`ProfileController`, not a task-8 regression) → [logs/working/task-8/security-auditor-1.json](logs/working/task-8/security-auditor-1.json)
- test-reviewer: fix, 3 medium + 4 low → [logs/working/task-8/test-reviewer-1.json](logs/working/task-8/test-reviewer-1.json)

Applied (round 1 → fix commit `6845945`): parallel-Connect race tests loosened from hard `setCount==2` / `delCount==1` to `setCount in [1,2]` + `delCount = setCount-1` (the loser may legitimately short-circuit at the service-level pre-check before reaching `setWebhook`); strong invariants — `{200, 409}` status pair and exactly-one CONNECTED row — preserved. `noTokenLeak_inAnyResponseBodyOrEventMetadata` augmented with a Telegram 4xx scenario where the token is quoted in `description`, asserting the 500 response body passes the regex scan (the previous happy-path-only sweep was structurally vacuous). New `anyEndpoint_unauthenticatedBareClient_returns401` mirroring `ProjectControllerIT`. Code-reviewer cleanups also applied: `Mono.fromCallable(...)` → `Mono.just(...)` on Void-body endpoints to match `AuthController`; AC5 IT now asserts no `Bot` row persisted; persist-fail status pinned to exactly 500.

Skipped (per task spec / pre-existing pattern): X-Forwarded-For platform observation (will be addressed at the reverse-proxy layer or in a future shared-util refactor that consolidates `extractIp` across `ProjectController` / `ProfileController` / `BotController`). Stale "Copied verbatim from ProfileController" comment on the helper trio kept verbatim per task spec mandate. `BotTokenLeakTest`'s `getDeclaredMethods()` scope (Bot extends Object, no superclass `toString` risk today). AC20 SHA-256 plaintext-vs-hash equality assertion — current shape-match assertion (64 hex chars) is sufficient given `BotServiceTest` already pins the SHA-256 contract end-to-end at unit-test granularity.

*Round 2 (after fixes):*
- test-reviewer: approve, all three round-1 mediums confirmed resolved → [logs/working/task-8/test-reviewer-2.json](logs/working/task-8/test-reviewer-2.json)

**Verification:**
- `./gradlew test --tests "com.botfunnel.bot.*"` → all bot-module tests pass (BotControllerIT 23 scenarios, BotTokenLeakTest 2 reflective assertions, plus existing BotServiceTest, BotRepositoryTest, BotIndexTest, BotStatusJsonTest, TelegramApiClientTest)
- `./gradlew test` → 343 backend tests pass, no regressions
- AC2 live curl smoke (bootRun + malformed-token POST → expect 400) — skipped per user; the IT method `postConnect_malformedToken_returns400WithFieldError_andNoTelegramCall` exercises the same Spring filter chain → bean validator → `GlobalErrorHandler` path with stricter assertions (zero `MockWebServer` requests + no persisted `Bot` row), making the live curl redundant.

---

## Task 9: Frontend Settings → Bot page (Connect form + Connected view + Test/Disconnect)

**Status:** Done
**Commit:** a401372 (round-2 autocomplete fix); e0e4d2b (round-1 fixes); 91c6842 (impl)
**Agent:** main agent
**Summary:** Replaced the Task-5 placeholder `frontend/pages/projects/[projectId]/settings/bot.vue` with the full Bot page — `<SettingsSubnav>` + Connect form when `botStore.current === null` + Connected view (`@username`, first_name, AC18 masked-token literal `1234567890:•••...xyz`, Send Test, Disconnect) when `current.status === 'connected'`. Disconnect opens a shadcn-vue `Dialog` confirmation modal with the exact AC19 copy interpolating `{username}`; Cancel closes without an API call, Confirm calls `botStore.disconnect`. All visible strings flow through `t('bot.*')` / `t('errors.bot.*')` (Task 4 inventory). Error surfaces via `useApiError()` factory at every catch site — `bot.connect` / `bot.disconnect` / `bot.testMessage` contexts. Companion Vitest spec covers all 22 branches from the tech-spec testing matrix including the AC17 token-regex negative assertion on both error and Connected paths.

**Deviations:**
- The shadcn-vue 1.0.3 CLI now generates Dialog primitives based on `reka-ui` (not `radix-vue` as the tech-spec note anticipated). Both libraries are now in `package.json`; `radix-vue` remains unused by Dialog but stays in deps because removing it is out of scope. The CLI also added `@vueuse/core` + `tailwindcss-animate` peer deps and scaffolded `components.json`, a top-level `tailwind.config.js`, a top-level `tsconfig.json` extending `.nuxt/tsconfig.json` (the CLI requires both, and Nuxt 4 has no top-level form by default). Initial init wrote a global `* { @apply border-border }` + `body { @apply bg-background text-foreground }` block to `assets/css/tailwind.css`; round-1 review caught it bleeding into every page outside the dialog scope, and it was removed in commit `e0e4d2b`.
- shadcn-vue CLI also modified `lib/utils.ts` to add a `valueUpdater` helper that imports from `@tanstack/vue-table` (not in deps). Reverted to the original two-function `cn`-only shape since Dialog does not consume it.

**Reviews:**

*Round 1:*
- code-reviewer: approve, 1 major + 6 minor → [logs/working/task-9/code-reviewer-1.json](logs/working/task-9/code-reviewer-1.json)
- security-auditor: approve, 3 minor → [logs/working/task-9/security-auditor-1.json](logs/working/task-9/security-auditor-1.json)
- test-reviewer: approve, 4 minor → [logs/working/task-9/test-reviewer-1.json](logs/working/task-9/test-reviewer-1.json)

Applied (round 1 → fix commit `e0e4d2b`): removed the global border/body @apply block from `tailwind.css`; switched token input to `type="password"`; moved `tokenInput.value = ''` to a `finally` block so the secret clears on both success and failure paths; added a `watch(botStore.current)` to auto-close the disconnect modal if `current` goes null mid-modal (cross-tab disconnect / project switch); moved the Teleport stub from `config.global.stubs` (module-level mutation) to a per-mount `mountOptions` object referenced by all 22 `mountSuspended(BotPage, mountOptions)` sites; tightened the AC19 modal-copy assertion from a substring regex to a full-body `toContain`; added the AC17 token-regex negative assertion on the Connected view; added a new test covering the modal-auto-close-on-current-null lifecycle.

Skipped (out of Task 9 scope or stylistic): stale-projectId 404 redirect on this route (Task 5 left this gap on the Settings folder; not a Task 9 regression); modal-stays-open-on-disconnect-error (the page-level inline error banner pattern is consistent across connect/disconnect/testMessage; a follow-up could align with `settings/index.vue`'s modal-stays-open delete pattern); assertion-shape inconsistency across the connect-error matrix (stylistic).

*Round 2 (after fixes):*
- code-reviewer: approve, 0 findings → [logs/working/task-9/code-reviewer-2.json](logs/working/task-9/code-reviewer-2.json)
- security-auditor: approve, 1 minor (`autocomplete="off"` ignored on password fields per browser policy → `autocomplete="new-password"`) → [logs/working/task-9/security-auditor-2.json](logs/working/task-9/security-auditor-2.json)
- test-reviewer: approve, 0 findings → [logs/working/task-9/test-reviewer-2.json](logs/working/task-9/test-reviewer-2.json)

Applied (round 2 → fix commit `a401372`): swapped token input `autocomplete="off"` → `autocomplete="new-password"` to suppress browser password-vault save prompts and autofill (CWE-522).

**Verification:**
- `cd frontend && pnpm test 'tests/pages/projects/[projectId]/settings/bot.spec.ts'` → 22/22 passed
- `cd frontend && pnpm test` → 242/242 passed (no regressions in existing specs)
- `cd frontend && pnpm prebuild` → exit 0 (i18n parity gate green)
- Live BotFather-token smoke against a running backend — deferred to staging per user-spec "Пользователь проверяет" checklist (`docs/staging-smoke/06-bot-connection.md`).

---

## Task 10: Code Audit

**Status:** Done
**Commit:** none (audit produces a report under `work/` which is gitignored)
**Agent:** main agent
**Summary:** Holistic code-quality audit of the 06-bot-connection production code across the six dimensions defined in the task (duplicate resource initialization, shared-resources compliance, architectural consistency, AppException usage, reactive-chain correctness, idiomatic Spring WebFlux). Read every production file listed under tasks 1–9 `Files to modify` plus the comparison baselines (`AppException`, `GlobalErrorHandler`, `SecurityConfig`, `AuthService`, `ProjectController/Service`, frontend `useApi` / `useApiError` / `stores/projects.ts`). Result: **0 critical, 0 high, 1 medium, 4 low**. The one medium finding is a contractual gap in the D4 compensation scope — `tokenEncryptor.encrypt(token)` runs inside `Mono.defer(...)` AFTER `setWebhook` succeeded but BEFORE the `.onErrorResume(persistErr -> compensateAndPropagate(...))` attached to `botRepository.save(bot)`, so a synchronous throw from `encrypt` would bypass the compensating `deleteWebhook`. Practically unreachable on JDK 21 with a valid key, but the contract is wider than the implementation. Full report: [logs/working/task-10/code-audit-report.md](logs/working/task-10/code-audit-report.md).
**Deviations:** None (audit is read-only — no production code modified).

**Reviews:**

Audit Wave: auditor IS the review — no reviewer round. Findings from this report are the input for follow-up code-edit tasks if the user decides to act on them.

**Verification:**
- Report exists at `work/06-bot-connection/logs/working/task-10/code-audit-report.md`, 422 lines, all six dimensions present (`grep -c '^### ' ...` → 6).
- Every finding entry parses as severity + file + line(s) + problem + suggested fix (1 medium + 4 low entries).
- Findings grouped by severity in the cross-reference section at the end of the report.
- No production code was modified by this task.

---

## Task 11: Security Audit

**Status:** Done
**Commit:** none (audit produces a report under `work/` which is gitignored)
**Agent:** security-auditor subagent
**Summary:** Full-feature security audit of 06-bot-connection against OWASP Top 10 (2021) plus the nine focus areas from the task description. All ten OWASP categories walked with explicit verdicts; Risks R1–R10 from user-spec cross-checked against the implemented code — every stated mitigation is in place. Result: **0 Critical, 0 High, 0 Medium, 3 Low, 4 Info → Deploy recommendation: GO.** The three Low items are non-blocking hardening notes (empty default for `BOT_TOKEN_ENCRYPTION_KEY` in `application.properties:44` — fail-fast already covers it; shared `new SecureRandom()` field initializers in `BotService:66` / `TokenEncryptor:26` with no anti-refactor comment; unconditional `X-Forwarded-For` trust in `BotController.extractIp` — pre-existing platform pattern, brute-force key is per-user so spoofing cannot bypass the limiter). The four Info items are deferred (Micrometer counter from D14 per task-file directive, unused `radix-vue` dep, `webhookUrl` string-concat, no `https://` scheme guard on `TELEGRAM_BASE_URL`). Full report: [logs/working/task-11/security-audit-report.md](logs/working/task-11/security-audit-report.md).
**Deviations:** None (audit is read-only — no production code modified).

**Reviews:**

Audit Wave: auditor IS the review — no reviewer round. Findings from this report feed Pre-deploy QA (Task 13).

**Verification:**
- Report exists at `work/06-bot-connection/logs/working/task-11/security-audit-report.md`, 573 lines.
- All five severity headers present (`grep -E '^## (Critical|High|Medium|Low|Info)' ...` → all 5).
- All nine focus areas covered (grep hits for: token-at-rest, webhook secret, mass-assignment, IDOR, rate-limit, log, CSRF, cross-project, compensating).
- OWASP A01..A10 all referenced.
- Final line: `Deploy recommendation: GO`.
- No production code was modified by this task.

**Deploy recommendation: GO** — no Critical / High findings, Risks R1–R10 mitigations all implemented in code. Three Low items are documented residuals that don't gate deploy.

---

## Task 12: Test Audit

**Status:** Done
**Commit:** none (audit produces a report under `work/` which is gitignored)
**Agent:** test-reviewer subagent
**Summary:** Full-feature test-quality audit covering all six test files (`TokenEncryptorTest`, `TelegramApiClientTest`, `BotServiceTest`, `BotControllerIT`, `BotTokenLeakTest`, frontend `bot.spec.ts`). Every AC1–AC23 mapped to a concrete test method — AC11 and AC17 marked `partial`, all others `covered` (or `n/a` for build-gate items like AC22 parity). Test pyramid balance is correct across all six files; no `Thread.sleep` in `bot/` or `common/crypto/` test packages; `MockWebServer` correctly enqueued/takeRequest-balanced in `TelegramApiClientTest` and `BotControllerIT`. Token-leak negative assertion (AC17/AC23) is correctly wired to a real `EventRepository` collector, sweeps both Connect AND Disconnect, and is exercised against a leak-prone setWebhook-4xx-with-token-in-description scenario. Result: **0 blockers, 3 majors, 4 minors → Verdict: `pass-with-followup`.** Full report: [logs/working/task-12/test-audit-report.md](logs/working/task-12/test-audit-report.md).
**Deviations:** None (audit is read-only — no test files modified).

**Reviews:**

Audit Wave: auditor IS the review — no reviewer round. Findings from this report feed Pre-deploy QA (Task 13).

**Verification:**
- Report exists at `work/06-bot-connection/logs/working/task-12/test-audit-report.md`, 494 lines.
- All required sections present: Summary, AC Coverage Matrix, Pyramid Assessment, MockWebServer Usage, Concurrency Anti-patterns, Token-leak Negative Assertion, Frontend Coverage, Findings, Recommendations, Verdict.
- Every AC1–AC23 mentioned by number in the AC Coverage Matrix.
- Sanity grep `Thread.sleep` in test files: 0 matches (confirms Concurrency Anti-patterns "absent" finding).
- Sanity grep `MockWebServer` in `backend/src/test/java/com/botfunnel/bot`: `BotControllerIT.java`, `TelegramApiClientTest.java`.
- Final line: `Verdict: pass-with-followup`.
- No test or production code was modified by this task.

**Follow-up items (majors — for Task 13 escalation or post-deploy backlog):**
1. **F-1 (major):** `BotTokenLeakTest` only checks DTO record components + absence of `Bot.toString()`. Does NOT reflect over production code paths to verify no `Logger` call serializes the encrypted-token field, which is what supplementary AC line 481 promises. Either widen the test with a `Reflections`-based bytecode scan, or downgrade the AC wording (recommend the latter — the dynamic IT sweep + code-review checklist are operative defense).
2. **F-2 (major):** AC11 IT `postConnect_eleventhAttemptWithinWindow_returns429_noTelegramCalls` does not assert the Redis counter value (= 11) or the remaining TTL (≤ 900s) — tech-spec line 390 explicitly requires both. Two extra `assertThat` lines close the gap.
3. **F-3 (major):** Frontend `bot.spec.ts` does not exercise the `errors.bot.testMessage.502` branch; the i18n key exists in both locales but no Vitest method resolves it. Add a ten-line mirror of the 422 test.

---

## Task 13: Pre-deploy QA

**Status:** Done
**Commit:** `930ce2c` (staging-smoke runbook). QA sign-off report lives under `work/` (gitignored) — no commit.
**Agent:** main agent (pre-deploy-qa skill)
**Summary:** Executed the full pre-deploy QA gate. Backend `./gradlew test` → **344/344 passed**, frontend `pnpm test` → **242/242 passed**, locale parity `pnpm prebuild` → **exit 0**. Live boot smoke confirmed: backend starts in 1.823s with a throwaway 64-hex-char key; without the key, boot fails fast in ~5s with the explicit `BOT_TOKEN_ENCRYPTION_KEY must be 32 bytes / 64 hex characters; blank value` error from `TokenEncryptor.java:63`. Dev-Mongo `db.bots.getIndexes()` confirms both required partial-unique indexes (`projectId_unique_connected`, `telegramBotId_unique_connected`) with exact `{ status: 'CONNECTED' }` filter. All 23 user-spec ACs and all 10 tech-spec supplementary criteria mapped to concrete test methods or live evidence in the report. SecurityConfig unchanged — bot routes inherit `/api/**` authenticated + CSRF enforcement (verified by anonymous POST returning 403 CSRF rejection before bean validation could fire). 10-step manual staging-smoke runbook created at `docs/staging-smoke/06-bot-connection.md` (committed in `930ce2c`). Full report: [logs/working/task-13/qa-signoff-report.md](logs/working/task-13/qa-signoff-report.md).
**Deviations:** Live curl bean-validation 400 smoke (AC2) is gated by CSRF in an unauthenticated QA shell — the anonymous POST returns 403 (CSRF gate fires before bean validation), which is the correct production behaviour. AC2's behavioural contract is fully covered by the integration test `BotControllerIT.postConnect_malformedToken_returns400WithFieldError_andNoTelegramCall()` (WebTestClient with mocked auth, asserts zero MockWebServer requests + no persisted Bot row). The live curl-with-real-cookie path is the runbook's responsibility (staging smoke step 2 prereq is being logged in as the test owner).

**Reviews:**

Final Wave: pre-deploy QA is its own verification — no reviewer round.

**Verification:**
- `cd backend && ./gradlew test` → 344/344 passed in 1m (0 failed, 0 errors, 0 skipped). Bot-module breakdown: BotControllerIT 23, BotServiceTest 23, TokenEncryptorTest 18, TelegramApiClientTest 13, BotIndexTest 5, BotRepositoryTest 4, BotTokenLeakTest 2, BotStatusJsonTest 2, TokenEncryptorBootValidationTest 4 = 94 bot-module tests, all green.
- `cd frontend && pnpm test --run` → 242/242 passed in 6.72s (23 test files).
- `cd frontend && pnpm prebuild` → exit 0. jq confirmation: 24 `bot.*` keys symmetric per locale; `errors.bot.connect` leaves `400,409,422,429,500,502,generic`; `errors.bot.testMessage` `422,502,generic`; `errors.bot.disconnect` `404,generic` — exact AC22 inventory match in both `uk.json` and `en.json`.
- Read-only smoke: `.env.example:35` `BOT_TOKEN_ENCRYPTION_KEY=` (placeholder, no real value); `application.properties:6,44,45` all three required keys present; `build.gradle:38` mockwebserver in `testImplementation` only; `SecurityConfig.java:69-72` `/api/**` authenticated (covers `/api/v1/projects/{id}/bot/**`), no `permitAll`/`csrf-ignore` for bot routes.
- Live boot WITH valid key: `Started BotFunnelApplication in 1.823 seconds`. Health endpoint → 200. Anonymous POST to `/api/v1/projects/abc/bot/connect` → 403 (CSRF correctly enforced).
- Live boot WITHOUT key: exit code 1 within ~5s with full stack trace pointing to `TokenEncryptor.java:63 — BOT_TOKEN_ENCRYPTION_KEY must be 32 bytes / 64 hex characters; blank value`.
- Live Mongo indexes: `docker exec development-mongo-1 mongosh botfunnel --quiet --eval 'db.bots.getIndexes()'` → 5 indexes on `bots` collection — `_id_`, `projectId_status`, `projectId`, `projectId_unique_connected` (unique + partial `{ status: "CONNECTED" }`), `telegramBotId_unique_connected` (unique + partial `{ status: "CONNECTED" }`).
- Staging-smoke runbook: `docs/staging-smoke/06-bot-connection.md` created (120 lines, 10 numbered checkbox steps, committed in `930ce2c`).

**Deploy recommendation: GO.** No Critical / High findings across the Wave 4 audits, no test failures, all 23 ACs covered by tests, locale parity green, partial-unique indexes confirmed live, fail-fast key validation confirmed live, SecurityConfig unchanged.

**Follow-up backlog (NOT deploy blockers — carried into the next epic / 06.1 hotfix):**
- Task 10 Medium: D4 compensation scope is narrower than the contract — `tokenEncryptor.encrypt(token)` throw between `setWebhook` success and `botRepository.save` would bypass the compensating `deleteWebhook`. Practically unreachable on JDK 21 (JCE contract) but a contractual gap. Fix is one `.onErrorResume(...)` scope move in `BotService.java:131-156`.
- Task 12 Major F-1: `BotTokenLeakTest` reflective scope is narrower than the AC wording — reflects over DTO record components + entity `toString` only. Runtime contract is covered by `BotControllerIT.noTokenLeak_inAnyResponseBodyOrEventMetadata()`. Resolve by narrowing the AC wording or widening the test with a `Reflections` bytecode scan.
- Task 12 Major F-2: AC11 IT does not assert the Redis counter value (=11) or remaining TTL (≤900s) — covered indirectly by `BotServiceTest.connect_secondAttemptDoesNotResetTtl()`. Two-line `assertThat` addition closes the gap.
- Task 12 Major F-3: Frontend `bot.spec.ts` does not exercise the `errors.bot.testMessage.502` branch — i18n key exists in both locales but no Vitest method resolves it. Ten-line mirror of the 422 test closes the gap.

**Manual post-deploy smoke is now the user's responsibility:** the 10-step runbook at `docs/staging-smoke/06-bot-connection.md` is the only post-deploy verification gate (no automated post-deploy task — real Telegram requires `@BotFather` + a human Telegram account). Each checkbox must be ticked on staging before promoting to production.
