# 06-bot-connection — Pre-deploy QA Sign-off

**Verdict:** GO
**Date:** 2026-05-15
**QA agent:** claude-opus-4-7 (task-13 — pre-deploy-qa skill)

All gates green: backend tests 344/344, frontend tests 242/242, locale parity
exit 0, fail-fast on missing `BOT_TOKEN_ENCRYPTION_KEY` confirmed (exit 1
in 5s with explicit error), dev-Mongo `bots` collection carries all three
required indexes with the exact `{ status: 'CONNECTED' }` partial filter,
SecurityConfig unchanged and `/api/v1/projects/{id}/bot/**` inherits the
standard authenticated + CSRF-enforced policy. Three pre-existing audit
follow-ups (Task 12 majors F-1/F-2/F-3) are documented hardening items —
none blocks deploy.

## Test suite results

| Suite | Result | Duration | Report |
|---|---|---|---|
| Backend (`cd backend && ./gradlew test`) | **344/344 passed** (0 failed, 0 errors, 0 skipped) | 1m | `backend/build/reports/tests/test/index.html` |
| Frontend (`cd frontend && pnpm test --run`) | **242/242 passed** | 6.72s (14.69s tests, 19.31s setup) | Vitest stdout — `Test Files 23 passed (23) / Tests 242 passed (242)` |
| Locale parity (`cd frontend && pnpm prebuild`) | **exit 0** | <1s | `frontend/scripts/check-locales.mjs` clean |

Bot-module breakdown (from JUnit XML at
`backend/build/test-results/test/TEST-com.botfunnel.bot.*.xml` +
`backend/build/test-results/test/TEST-com.botfunnel.common.crypto.*.xml`):

| Suite | Tests | Fail | Error |
|---|---|---|---|
| `BotControllerIT` | 23 | 0 | 0 |
| `BotServiceTest` | 23 | 0 | 0 |
| `TokenEncryptorTest` | 18 | 0 | 0 |
| `TelegramApiClientTest` | 13 | 0 | 0 |
| `BotIndexTest` | 5 | 0 | 0 |
| `BotRepositoryTest` | 4 | 0 | 0 |
| `BotTokenLeakTest` | 2 | 0 | 0 |
| `BotStatusJsonTest` | 2 | 0 | 0 |
| `TokenEncryptorBootValidationTest` | 4 | 0 | 0 |
| **Total bot-module** | **94** | **0** | **0** |

No regressions outside bot-module (full backend suite 344 passed; only
JobRunr's pre-existing macOS-DNS warning appears in logs).

## Smoke checks

### Config / env wiring (read-only file inspection)

| Check | File / line | Result |
|---|---|---|
| `.env.example` has `BOT_TOKEN_ENCRYPTION_KEY=` placeholder (no real key) | `.env.example:35` | **OK** — line `BOT_TOKEN_ENCRYPTION_KEY=` (empty after `=`; comment block at lines 33–34 says "32 bytes / 64 hex chars; generate with openssl rand -hex 32. NEVER commit a real value.") |
| `app.bot.token-encryption-key` wired | `backend/src/main/resources/application.properties:44` | **OK** — `app.bot.token-encryption-key=${BOT_TOKEN_ENCRYPTION_KEY:}` |
| `app.telegram.base-url` default | `application.properties:45` | **OK** — `app.telegram.base-url=${TELEGRAM_BASE_URL:https://api.telegram.org}` |
| `spring.data.mongodb.auto-index-creation=true` | `application.properties:6` | **OK** — present |
| `mockwebserver` in `testImplementation` scope only | `backend/build.gradle:38` | **OK** — `testImplementation 'com.squareup.okhttp3:mockwebserver:4.12.0'` (NOT in `implementation`) |
| SecurityConfig: bot path NOT in any `permitAll` matcher | `backend/src/main/java/com/botfunnel/security/SecurityConfig.java:69-72` | **OK** — only `/health` and `/api/auth/**` are `permitAll`; `/api/**` is `authenticated` (covers `/api/v1/projects/{id}/bot/**`). No `csrf().ignoringRequestMatchers(...)` — bot routes inherit the CSRF protection chain |

### Live boot smoke

| Check | Result |
|---|---|
| **Local boot with valid key** | **OK** — exported a throwaway 64-hex-char key (generated via `head -c 32 /dev/urandom \| xxd -p -c 64`, kept only in the QA shell, never logged in chat). `cd backend && ./gradlew bootRun`. Boot log: `Started BotFunnelApplication in 1.823 seconds`. Netty on port 8080. MongoDB sync + reactive clients connected to `localhost:27017`. JobRunr started. |
| **Local boot WITHOUT key — fail-fast** | **OK** — `unset BOT_TOKEN_ENCRYPTION_KEY; ./gradlew bootRun` exits with code **1** within ~5 seconds. Error chain: `BeanInstantiationException: Failed to instantiate [com.botfunnel.common.crypto.TokenEncryptor]: Constructor threw exception → IllegalStateException: BOT_TOKEN_ENCRYPTION_KEY must be 32 bytes / 64 hex characters; blank value` (TokenEncryptor.java:63, line 29). Clear, actionable, names the env var. |
| **Health endpoint** | **OK** — `GET http://localhost:8080/health` → `HTTP/1.1 200 OK` |
| **Curl bean-validation 400 (AC2 live smoke)** | **PARTIAL via integration test + observation** — anonymous `POST /api/v1/projects/abc/bot/connect` from an unauthenticated curl returns `HTTP/1.1 403 Forbidden` (CSRF gate fires before bean validation — POST without `XSRF-TOKEN` cookie is rejected). This proves the CSRF policy is enforced on the bot route. The bean-validation 400 path is covered by `BotControllerIT.postConnect_malformedToken_returns400WithFieldError_andNoTelegramCall()` via WebTestClient with mocked auth (zero MockWebServer requests + no persisted Bot row asserted). Live curl with a real session cookie is documented for the staging-smoke runbook (step 2 prereq). |
| **Mongo indexes (live, after backend connect)** | **OK** — `docker exec development-mongo-1 mongosh botfunnel --quiet --eval 'db.bots.getIndexes()'` returned **5 indexes** on the `bots` collection (`_id_` Mongo-default; `projectId_status` non-unique compound; `projectId` single-field from `@Indexed(Bot.java:45)`; `projectId_unique_connected` with `unique: true` + `partialFilterExpression: { "status": "CONNECTED" }`; `telegramBotId_unique_connected` with `unique: true` + `partialFilterExpression: { "status": "CONNECTED" }`). Both **required partial-unique indexes** present with exact filter — D1 + D5 race protections live. Raw output stored below. |
| **Staging-smoke runbook** | **OK** — `docs/staging-smoke/06-bot-connection.md` created (120 lines, 10 numbered checkbox steps mirroring user-spec § "Пользователь проверяет"). `docs/staging-smoke/` is NOT gitignored — runbook lives in the repo as a real deliverable for post-deploy. |

Raw `db.bots.getIndexes()` output (abbreviated to the two race-protection indexes):

```json
{
  "name": "projectId_unique_connected",
  "key": { "projectId": 1 },
  "unique": true,
  "partialFilterExpression": { "status": "CONNECTED" }
},
{
  "name": "telegramBotId_unique_connected",
  "key": { "telegramBotId": 1 },
  "unique": true,
  "partialFilterExpression": { "status": "CONNECTED" }
}
```

## Audit gate

All three Wave-4 audit reports were produced, committed (as entries in
`decisions.md`), and reviewed:

| Audit | Verdict | Top blocker | Resolution |
|---|---|---|---|
| Task 10 — Code audit (`logs/working/task-10/code-audit-report.md`) | 0 critical / 0 high / 1 medium / 4 low | Medium: D4 compensation scope is narrower than the contract — encryption failure after `setWebhook` succeeds would leave an orphan webhook (`BotService.java:131-156`) | **Not a deploy blocker.** `tokenEncryptor.encrypt(token)` raises `IllegalStateException` only when `Cipher.doFinal` itself fails, which is unreachable on JDK 21 with a valid 256-bit key per the JCE spec. Logged as follow-up backlog. |
| Task 11 — Security audit (`logs/working/task-11/security-audit-report.md`) | 0 critical / 0 high / 0 medium / 3 low / 4 info — **Deploy recommendation: GO** | None (all three Low items are pre-existing platform patterns and the four Info items are deferred hardening) | Carried as backlog (X-Forwarded-For trust boundary, empty key default, shared SecureRandom, no `https://` scheme guard on `TELEGRAM_BASE_URL`). |
| Task 12 — Test audit (`logs/working/task-12/test-audit-report.md`) | 0 blockers / 3 majors / 4 minors — **Verdict: pass-with-followup** | F-1 `BotTokenLeakTest` reflection scope; F-2 AC11 IT missing Redis counter/TTL assertions; F-3 frontend `bot.spec.ts` missing `errors.bot.testMessage.502` branch | **Not deploy blockers.** F-1 is a wording/scope question (the dynamic IT sweep + masking-helper test cover the behavioural contract); F-2 is a 2-line `assertThat` addition; F-3 is a 10-line mirror test. All three are documented backlog items in `decisions.md` Task 12 entry. |

No WAIT-FOR-FIX findings carried forward. **GO gate clears.**

## AC ↔ evidence mapping

### User-spec AC1–AC23

| AC | Description | Evidence (test method / live output) |
|----|-------------|---------------------------------------|
| AC1 | Connect happy path 200 + `BotResponse` shape (telegramBotId, username, first_name, tokenSuffix, status, connectedAt) | `BotControllerIT.postConnect_validToken_returns200WithBotResponse()` |
| AC2 | Malformed token → 400 + bean-validation field error + zero Telegram calls | `BotControllerIT.postConnect_malformedToken_returns400WithFieldError_andNoTelegramCall()` (the live curl smoke is gated by CSRF in this QA session — see note above) |
| AC3 | Telegram 401 on `getMe` → 422 `invalid_bot_token` | `BotControllerIT.postConnect_telegramGetMe401_returns422()` + `TelegramApiClientTest.getMe_401_mapsToInvalidBotToken()` |
| AC4 | Telegram 5xx / timeout on `getMe`/`setWebhook` after retry-exhaustion → 502 `telegram_unavailable` | `BotControllerIT.postConnect_telegram5xxExhausted_returns502()` + `TelegramApiClientTest.getMe_5xxRetryExhausted_returns502TelegramUnavailable()` + `TelegramApiClientTest.getMe_nettyReadTimeout_retriesThenReturns502TelegramUnavailable()` + `TelegramApiClientTest.deleteWebhook_5xxRetryExhausted_returns502TelegramUnavailable()` |
| AC5 | Telegram 4xx config error on `setWebhook` → 500 `webhook_config_error` (generic copy, no raw description in user body) | `BotControllerIT.postConnect_setWebhook4xxConfigError_returns500WithCodeWebhookConfigError()` + `TelegramApiClientTest.setWebhook_4xxConfigError_mapsTo500WebhookConfigError()` + `TelegramApiClientTest.setWebhook_4xxConfigError_scrubsTokenInWarnLog()` |
| AC6 | Platform-wide `telegramBotId` collision → 409 `bot_already_connected` (anti-leak copy — generic) | `BotControllerIT.postConnect_platformBotIdAlreadyConnected_returns409BotAlreadyConnected()` + `BotServiceTest.connect_duplicateKeyOnTelegramBotIdIndex_returns409BotAlreadyConnected_andCompensates()` |
| AC7 | Per-project pre-check → 409 `bot_already_in_project` | `BotControllerIT.postConnect_projectAlreadyHasConnectedBot_returns409BotAlreadyInProject()` + `BotServiceTest.connect_duplicateKeyOnProjectIndex_returns409BotAlreadyInProject_andCompensates()` |
| AC8 | Side-effect order on success: requireOwned → INCR → per-project pre-check → getMe → platform pre-check → setWebhook → encrypt → save → event → DEL counter | `BotServiceTest.connect_happyPath_runsSideEffectsInDocumentedOrder()` (InOrder verification on mocked collaborators) |
| AC9 | `setWebhook` OK but Mongo persist fails → compensating `deleteWebhook` runs, 500 returned, error logged | `BotControllerIT.postConnect_persistFails_compensatingDeleteWebhookFires_returns500()` + `BotServiceTest.connect_persistFails_compensatesDeleteWebhookAndPropagatesError()` |
| AC10 | Webhook URL registered = `${APP_URL}/webhooks/telegram/{projectId}` exactly; secret freshly drawn per Connect | `BotServiceTest.connect_happyPath_runsSideEffectsInDocumentedOrder()` (captures and asserts URL string) + `BotServiceTest.connect_secretIsFreshlyDrawnPerInvocation()` |
| AC11 | Connect rate-limit per user: 11th attempt in window → 429, zero Telegram calls, no TTL refresh on 2nd attempt | `BotControllerIT.postConnect_eleventhAttemptWithinWindow_returns429_noTelegramCalls()` + `BotServiceTest.connect_rateLimitThreshold_returns429OnEleventhAttempt_noTelegramCall()` + `BotServiceTest.connect_secondAttemptDoesNotResetTtl()`. **Task 12 follow-up F-2** notes the IT does not assert Redis counter value/TTL directly — covered indirectly by the unit-test `secondAttemptDoesNotResetTtl`. |
| AC12 | Two concurrent Connects with same token → exactly one 200, the other 409 (race protected by partial-unique index) | `BotControllerIT.postConnect_parallelSameToken_exactlyOneSucceeds_otherReturns409()` + `BotControllerIT.postConnect_parallelSameProjectDifferentTokens_exactlyOneSucceeds_otherReturns409()` |
| AC13 | Disconnect: 200, `deleteWebhook` best-effort, atomic local update (encryptedToken/IV/tokenSuffix/webhookSecretHash all null), 404 when no connected bot | `BotControllerIT.postDisconnect_happyPath_returns200_andUpdatesMongoAtomically()` + `BotControllerIT.postDisconnect_noConnectedBot_returns404()` + `BotControllerIT.postDisconnect_telegramDown_warnLogged_returns200()` (AC13b best-effort) + `BotServiceTest.disconnect_happyPath_emitsBotDisconnectedEventWithDeletedTrue()` + `BotServiceTest.disconnect_telegramDown_logsWarnAndStillCompletes()` |
| AC14 | `GET /bot` returns the current connected bot (200) or 404 if none; shape matches AC1 | `BotControllerIT.getBot_seededConnected_returns200_noneReturns404()` + `BotServiceTest.getByProject_happyPath_returnsBot_noDecryption()` + `BotServiceTest.getByProject_noConnectedBot_returns404()` |
| AC15 | `POST /test-message` in 06: 422 `owner_chat_id_unknown`, zero Telegram calls, NO event emitted (D7) | `BotControllerIT.postTestMessage_in06_returns422_zeroTelegramCalls_zeroEvents()` + `BotServiceTest.sendTestMessage_returns422_noTelegramCalls_noEvents()` + `BotServiceTest.sendTestMessage_noConnectedBot_returns404()` |
| AC16 | Every bot endpoint calls `ProjectService.requireOwned` first; foreign / soft-deleted / malformed projectId → 404 (never 403); hostile body fields ignored | `BotControllerIT.antiEnumeration_foreignSoftDeletedMalformedProjectId_returns404_andHostileBodyIgnored()` + all `BotServiceTest` methods chain through `requireConnectedBot` which calls `requireOwned` first |
| AC17 | Raw token never leaves backend: no log line, no event metadata, no response body contains the token-shape regex; scrubber covers Telegram-side strings | `BotControllerIT.noTokenLeak_inAnyResponseBodyOrEventMetadata()` (includes setWebhook-4xx-with-token-in-description scenario) + `BotServiceTest.connect_bot_connected_event_metadata_containsNoTokenRegex()` + `BotServiceTest.disconnect_telegramErrorMessageWithTokenInUri_isScrubbedInWarnLog()` + `TelegramApiClientTest.scrubber_redactsTokenShapedSubstring()` + `TelegramApiClientTest.scrubber_regexBoundaries()` + `BotTokenLeakTest` (reflective). **Task 12 follow-up F-1** notes the reflective scope is narrow (DTO + entity `toString`) — the dynamic event-collector sweep in `BotControllerIT` covers the runtime contract. |
| AC18 | UI masked token literal: `{telegramBotId}:•••...{tokenSuffix}` (U+2022) | Frontend `bot.spec.ts` describe block covers the Connected view masked-token assertion (`bot.connected.tokenLabel` + literal mask) — 22 specs total in `frontend/tests/pages/projects/[projectId]/settings/bot.spec.ts` |
| AC19 | Disconnect modal copy verbatim ("Disconnect bot @{username}? Incoming messages will stop...") | Frontend `bot.spec.ts` — `bot.disconnect.modal.body` `toContain` assertion (Task 9 round-1 review tightened from substring to full-body match) |
| AC20 | Webhook secret stored as SHA-256 hash (hex), not plaintext | `BotControllerIT.webhookSecretInMongo_isSha256Hash()` (asserts hex shape of `webhookSecretHash` field) + `BotServiceTest.connect_webhookSecretIsHashedWithSha256_neverPlaintext()` |
| AC21 | `frontend/pages/projects/[projectId]/settings.vue` split into folder route with sub-nav; stale-projectId 404 redirect on `settings/index.vue` | Frontend `tests/components/SettingsSubnav.spec.ts` + `tests/pages/projects/[projectId]/settings/index.spec.ts` (covered by Task 5 — 203 total frontend tests, +5 for SettingsSubnav, +1 wiring for bot.vue placeholder) |
| AC22 | New i18n keys in both `uk.json` and `en.json`; locale parity gate (`pnpm prebuild`) fails on drift | **Live evidence:** `pnpm prebuild` exit 0; `jq` confirmation: `bot.*` 24 keys symmetric per locale; `errors.bot.{connect,testMessage,disconnect}` subtrees match user-spec exact-leaf inventory (`connect: 400,409,422,429,500,502,generic`; `testMessage: 422,502,generic`; `disconnect: 404,generic`); no 401 leaf in either subtree |
| AC23 | Audit events on state change: `bot_connected` (Connect success) and `bot_disconnected` (Disconnect success); no `bot_test_message_sent` in 06 (D7); negative regex assertion on event metadata | `BotControllerIT.noTokenLeak_inAnyResponseBodyOrEventMetadata()` (real `EventRepository` collector swept after Connect+Disconnect+leak-prone scenario) + `BotServiceTest.connect_bot_connected_event_metadata_containsNoTokenRegex()` + `BotServiceTest.disconnect_happyPath_emitsBotDisconnectedEventWithDeletedTrue()` + `BotServiceTest.sendTestMessage_returns422_noTelegramCalls_noEvents()` (negative — confirms no event emitted on the 422 path) |

### Tech-spec Acceptance Criteria (lines 475–484)

| Item | Evidence |
|------|----------|
| All API endpoints return precise HTTP status codes (200, 400, 401, 404, 409, 422, 429, 500, 502) | `BotControllerIT` covers every code: 200 (happyPath/get/disconnect), 400 (malformedToken), 401 (anyEndpoint_unauthenticatedBareClient_returns401), 404 (getBot none, postDisconnect none, antiEnumeration foreign id), 409 (parallelSameToken/parallelSameProjectDifferentTokens/projectAlreadyHas/platformBotIdAlready), 422 (telegramGetMe401, postTestMessage_in06), 429 (eleventhAttemptWithinWindow), 500 (persistFails / setWebhook4xxConfigError), 502 (telegram5xxExhausted) |
| Both partial unique indexes exist with filter `{ status: 'CONNECTED' }` | **Live evidence:** `db.bots.getIndexes()` output above shows both `projectId_unique_connected` and `telegramBotId_unique_connected` with `unique: true` + `partialFilterExpression: { "status": "CONNECTED" }` — and `BotIndexTest.indexes_areCreatedOnCollection()` asserts the same via Testcontainers Mongo |
| All bot-module tests pass + no regressions in existing tests | `./gradlew test` → 344/344 passed, 0 failed, 0 skipped (auth/profile/projects/events suites all green) |
| `pnpm prebuild` exits 0 with `bot.*` and `errors.bot.*` in both locales | **Live evidence:** prebuild exit 0; jq output confirms 24 `bot.*` keys symmetric + `errors.bot.{connect,testMessage,disconnect}` subtrees match per locale |
| `BOT_TOKEN_ENCRYPTION_KEY` documented in `.env.example`; backend fails fast on missing/wrong length | `.env.example:35` (documented with `openssl rand -hex 32` guidance) + `TokenEncryptorBootValidationTest.contextFailsWhenKeyIsBlank/NonHex/WrongLength()` + **live boot-without-key smoke exit 1 in 5s** |
| `app.telegram.base-url` default + IT override | `application.properties:45` default `https://api.telegram.org` + `BotControllerIT` `@DynamicPropertySource` overrides to `MockWebServer` URL (per Task 8 design) |
| `BotTokenLeakTest` reflective assertion | `BotTokenLeakTest.botResponseRecordHasNoEncryptedTokenComponents()` + `BotTokenLeakTest.botEntityDoesNotDeclareToStringExposingEncryptedTokenFields()` — passing. Task 12 F-1 notes the reflective scope is narrower than the AC wording implies (DTO + entity `toString` only, no `Logger`-call scan) — backlog. |
| `MockWebServer` in `testImplementation` only | `backend/build.gradle:38` — `testImplementation 'com.squareup.okhttp3:mockwebserver:4.12.0'`; no occurrence in `implementation` block |
| Compensating `deleteWebhook` on persist failure exercised by IT | `BotControllerIT.postConnect_persistFails_compensatingDeleteWebhookFires_returns500()` (asserts both `setWebhook` AND `deleteWebhook` requests in MockWebServer's recorded request log; persist forced to fail via `@MockitoSpyBean BotRepository`) |
| SecurityConfig unchanged + bot path under standard auth + CSRF | `SecurityConfig.java:69-72` — `/health` and `/api/auth/**` permitAll, `/api/**` authenticated, no `csrf().ignoringRequestMatchers(...)` clause. The file has been unchanged across the entire feature (no Task 1-9 commit touched it). |

## Blockers (if NO-GO)

None. Verdict is GO.

Three pre-existing audit follow-ups are documented as backlog (not deploy
blockers — see Audit gate table above):

1. **Task 10 Medium — D4 compensation scope.** Encryption failure between
   `setWebhook` success and `botRepository.save` does not trigger
   compensating `deleteWebhook`. Practically unreachable on JDK 21 with
   a valid 256-bit AES key (JCE contract). Fix is one scope move on the
   `.onErrorResume(...)` in `BotService.java:131-156`. Track in next epic
   backlog or 06.1 hotfix.
2. **Task 12 Major F-1 — `BotTokenLeakTest` reflective scope.** Test
   reflects over DTO record components + entity `toString` only; the
   "no production code path serializes the encrypted-token field" AC
   wording is wider than the test. Resolve by either widening the test
   (Reflections bytecode scan over `Logger` call sites) or narrowing the
   AC wording. Recommend narrowing — the dynamic
   `noTokenLeak_inAnyResponseBodyOrEventMetadata()` IT covers the
   behavioural contract.
3. **Task 12 Majors F-2 + F-3 — small test gaps.** F-2: AC11 IT asserts
   429 status + zero Telegram calls but not the Redis counter value (=11)
   or remaining TTL — 2-line addition. F-3: frontend `bot.spec.ts` does
   not exercise the `errors.bot.testMessage.502` branch — 10-line mirror
   of the 422 test.

## Notes

- **No Deploy task in this feature.** Deployment platform is TBD per
  `deployment.md`. The feature ships through whatever manual flow the
  operator chooses once the platform is decided.
- **No automated Post-deploy verification task.** Real-Telegram smoke
  requires `@BotFather` + a human Telegram account; the agent cannot
  exercise it. The 10-step manual runbook at
  `docs/staging-smoke/06-bot-connection.md` is the deliverable handed to
  the user for the post-deploy manual smoke. Each checkbox in that file
  must be ticked before declaring "06 OK" on staging.
- **`BOT_TOKEN_ENCRYPTION_KEY` for staging / production** is set via
  the platform's secret store. Never paste it in chat / commit / IM.
  Generate via `openssl rand -hex 32`. Rotating the key requires a
  one-off migration job to re-encrypt the small `bots` table (per D3 —
  no `keyVersion` schema field).
- The Wave 4 audit reports list residual hardening items (Task 11
  Low-1..3 + Info-1..4) that should be carried as cross-cutting
  platform improvements rather than 06 work — they touch shared
  patterns (X-Forwarded-For trust boundary, shared `SecureRandom`, etc.).
- The unused `radix-vue` dependency in `frontend/package.json` (Task 9
  deviation — shadcn-vue scaffolded Dialog over `reka-ui` instead) is
  noise that can be cleaned up in a follow-up; not a deploy gate.
