# Test Audit — Feature 06-bot-connection (Task 12)

## Summary

The 06-bot-connection epic ships a strong test corpus: six in-scope test files
(`TokenEncryptorTest`, `TelegramApiClientTest`, `BotServiceTest`,
`BotControllerIT`, `BotTokenLeakTest`, frontend `bot.spec.ts`) plus a supporting
`TokenEncryptorBootValidationTest`. Coverage across user-spec AC1–AC23 and the
tech-spec supplementary criteria is essentially complete; assertions are
mostly observable (HTTP status + Mongo state + audit-event metadata +
MockWebServer request log), the test pyramid sits in the right shape
(unit-heavy with one integration class, no E2E), and the AC17/AC23 token-leak
negative assertion is wired to a real `EventRepository` collector and is
exercised against a *leak-prone* setWebhook-4xx-with-token-in-description
scenario that would actually fail if a token slipped through. No
`Thread.sleep` is present in the bot/crypto test packages.

Three issues warrant follow-up: (1) `BotTokenLeakTest` reflects only over
DTO/entity *shape* and never executes a production code path that serializes
the Bot to a string — it is technically a static-structure invariant, not the
"reflective assertion that no production code path serializes the encrypted-
token field" that the supplementary AC line 481 promises; (2) the frontend
`bot.spec.ts` skips the `errors.bot.testMessage.502` branch even though the
i18n key exists and the tech-spec line 414 inventory lists 422+502 as required
test-message branches; (3) the AC11 rate-limit integration test does NOT
assert the `BRUTE_KEY` counter value (= 11) or the remaining TTL window — the
tech-spec line 390 contract says verify both; the current test only asserts
the 429 status + zero Telegram calls.

Findings: **0 blockers, 3 majors, 4 minors**. The audit therefore lands at
`pass-with-followup`.

---

## AC Coverage Matrix

Every AC1–AC23 from user-spec plus the tech-spec supplementary criteria
(tech-spec.md lines 475–484). Status: `covered` (≥1 method asserts the
observable contract), `partial` (covered but missing one or more assertions
the AC demands), `missing` (no method exercises this AC).

| AC | Test method(s) | Status |
|----|---------------|--------|
| AC1 (Connect happy-path response shape) | `BotControllerIT#postConnect_validToken_returns200WithBotResponse` (asserts `telegramBotId`, `telegramUsername`, `telegramFirstName`, `tokenSuffix`, `status="connected"`, `connectedAt`, and explicit `.doesNotExist()` for raw/encrypted token fields) | covered |
| AC2 (Malformed token → 400, no Telegram call) | `BotControllerIT#postConnect_malformedToken_returns400WithFieldError_andNoTelegramCall` + `BotServiceTest` covers the regex on the request DTO indirectly | covered |
| AC3 (Telegram 401 → 422) | `BotControllerIT#postConnect_telegramGetMe401_returns422`; `TelegramApiClientTest#getMe_401_mapsToInvalidBotToken` | covered |
| AC4 (5xx retry exhausted → 502) | `BotControllerIT#postConnect_telegram5xxExhausted_returns502`; `TelegramApiClientTest#getMe_5xxRetryExhausted_returns502TelegramUnavailable`, `deleteWebhook_5xxRetryExhausted_returns502TelegramUnavailable`, `getMe_nettyReadTimeout_retriesThenReturns502TelegramUnavailable` | covered |
| AC5 (setWebhook 4xx config-error → 500 webhook_config_error + log description) | `BotControllerIT#postConnect_setWebhook4xxConfigError_returns500WithCodeWebhookConfigError`; `TelegramApiClientTest#setWebhook_4xxConfigError_mapsTo500WebhookConfigError` + `setWebhook_4xxConfigError_scrubsTokenInWarnLog` | covered |
| AC6 (Platform-wide uniqueness → 409 `bot_already_connected`) | `BotControllerIT#postConnect_platformBotIdAlreadyConnected_returns409BotAlreadyConnected`; `BotServiceTest#connect_duplicateKeyOnTelegramBotIdIndex_returns409BotAlreadyConnected_andCompensates` | covered |
| AC7 (Per-project uniqueness → 409 `bot_already_in_project`) | `BotControllerIT#postConnect_projectAlreadyHasConnectedBot_returns409BotAlreadyInProject`; `BotServiceTest#connect_duplicateKeyOnProjectIndex_returns409BotAlreadyInProject_andCompensates` | covered |
| AC8 (Side-effect order: getMe → setWebhook → persist) | `BotServiceTest#connect_happyPath_runsSideEffectsInDocumentedOrder` (explicit `InOrder` over projectService → valueOps → redis → repo → telegram → encryptor → repo.save → events → redis.delete); `BotControllerIT#postConnect_validToken_returns200WithBotResponse` asserts the two enqueued requests land in `[getMe, setWebhook]` order via `drainRequests()` indices | covered |
| AC9 (Persist-fail → compensating deleteWebhook + 500) | `BotControllerIT#postConnect_persistFails_compensatingDeleteWebhookFires_returns500` (explicit set-then-delete ordering check); `BotServiceTest#connect_persistFails_compensatesDeleteWebhookAndPropagatesError` (Mockito `InOrder`) | covered |
| AC10 (Webhook URL shape + fresh random secret per Connect) | `BotServiceTest#connect_happyPath_runsSideEffectsInDocumentedOrder` (asserts URL == `${APP_URL}/webhooks/telegram/{projectId}`); `BotServiceTest#connect_secretIsFreshlyDrawnPerInvocation` (two consecutive Connects emit two distinct 32-hex secrets); `BotControllerIT#postConnect_validToken_returns200WithBotResponse` (asserts setWebhook body contains the webhook path + `secret_token` form param) | covered |
| AC11 (Rate-limit: 11th attempt → 429; counter DEL on success; Redis fail-open) | `BotServiceTest#connect_rateLimitThreshold_returns429OnEleventhAttempt_noTelegramCall`, `connect_secondAttemptDoesNotResetTtl`, `connect_redisDownOnIncrement_failsOpenAndLogsWarn`, `connect_redisDownOnDelete_failsOpenAndLogsWarn`, `connect_successDelsBruteForceCounter`; `BotControllerIT#postConnect_eleventhAttemptWithinWindow_returns429_noTelegramCalls`, `postConnect_successfulConnect_deletesBruteForceKey`, `postConnect_redisDown_failsOpen_connectSucceeds_warnLogged` | partial — IT does not assert the counter value (= 11) nor the TTL window (≤ 900s) on the 429 path, both of which the tech-spec line 390 explicitly lists as required assertions |
| AC12 (Race: same token, two requests → 1×200, 1×409 `bot_already_connected`; request log ordering) | `BotControllerIT#postConnect_parallelSameToken_exactlyOneSucceeds_otherReturns409` (parallel `Mono.zip` via `subscribeOn(boundedElastic)`; asserts `{200, 409}` and exactly one CONNECTED row; asserts `delCount == setCount-1` with `setCount ∈ [1,2]` to cover both pre-check and post-setWebhook loser arrival) | covered |
| D5 (Race: same project, different tokens → 1×200, 1×409 `bot_already_in_project`; compensating deleteWebhook) | `BotControllerIT#postConnect_parallelSameProjectDifferentTokens_exactlyOneSucceeds_otherReturns409` (same shape as AC12) | covered |
| AC13 (Disconnect: deleteWebhook with retries, WARN on persistent failure, atomic local update, 404 when none) | `BotControllerIT#postDisconnect_happyPath_returns200_andUpdatesMongoAtomically` (asserts status=DISCONNECTED, all 4 token/secret fields null, `disconnectedAt` set, event emitted with `webhookDeleted=true`); `postDisconnect_telegramDown_warnLogged_returns200` (asserts 200 even on persistent 503×4, WARN logged once, event emitted with `webhookDeleted=false`); `postDisconnect_noConnectedBot_returns404`; `BotServiceTest#disconnect_*` (5 methods cover decryption args, scrubbed WARN, telegram-down event, 404 branch) | covered |
| AC14 (GET /bot → 200 connected / 404 none) | `BotControllerIT#getBot_seededConnected_returns200_noneReturns404` (asserts both branches in one test); `BotServiceTest#getByProject_happyPath_returnsBot_noDecryption`, `getByProject_noConnectedBot_returns404` | covered |
| AC15 (Test-message in 06 → 422 + zero Telegram calls + zero events) | `BotControllerIT#postTestMessage_in06_returns422_zeroTelegramCalls_zeroEvents` (asserts 422, code `owner_chat_id_unknown`, `drainRequests().isEmpty()`, no `bot_test_message_sent` event); `BotServiceTest#sendTestMessage_returns422_noTelegramCalls_noEvents`, `sendTestMessage_noConnectedBot_returns404` | covered |
| AC16 (`requireOwned` first; anti-enumeration 404 for foreign/soft-deleted/malformed; hostile body ignored) | `BotControllerIT#antiEnumeration_foreignSoftDeletedMalformedProjectId_returns404_andHostileBodyIgnored` (covers all three projectId shapes + hostile `ownerId` body); `BotServiceTest#connect_rateLimitThreshold_returns429OnEleventhAttempt_noTelegramCall`, `disconnect_noConnectedBot_returns404`, `sendTestMessage_noConnectedBot_returns404`, `getByProject_noConnectedBot_returns404` all assert `requireOwned` precedes the next step in an `InOrder` | covered |
| AC17 (Raw token never leaves backend; masking + leak-negative assertion) | `BotTokenLeakTest#botResponseRecordHasNoEncryptedTokenComponents`, `botEntityDoesNotDeclareToStringExposingEncryptedTokenFields`; `BotControllerIT#noTokenLeak_inAnyResponseBodyOrEventMetadata` (sweeps connect+disconnect+setWebhook-4xx-with-token-in-description); `BotServiceTest#connect_bot_connected_event_metadata_containsNoTokenRegex`, `disconnect_telegramErrorMessageWithTokenInUri_isScrubbedInWarnLog`; `TelegramApiClientTest#setWebhook_4xxConfigError_scrubsTokenInWarnLog`, `scrubber_redactsTokenShapedSubstring`, `scrubber_regexBoundaries`; frontend `bot.spec.ts#token never appears in error message or DOM (AC17)` + `renders Connected view from initial fetch` (asserts `wrapper.text()` does not match token regex) | partial — `BotTokenLeakTest` is a static-structure check (DTO record components + Bot.toString absence); supplementary AC line 481 specifically promises a "reflective assertion that no production code path serializes the encrypted-token field" — the current test would NOT catch a logger call that interpolates `bot.getEncryptedTokenCiphertext()` directly. See finding F-1. |
| AC18 (UI masked form `{telegramBotId}:•••...{tokenSuffix}`) | Frontend `bot.spec.ts#masked token uses literal AC18 form` (exact `.toBe('1234567890:•••...xyz')`); `renders Connected view from initial fetch (no Connect interaction)` (also exact); `BotControllerIT#tokenSuffix_persistedAndReturned_clearedOnDisconnect` (backend `tokenSuffix` persistence); `BotServiceTest#connect_tokenSuffixIsLastThreeCharacters` (last 3 chars of secret) | covered |
| AC19 (Disconnect modal copy verbatim) | Frontend `bot.spec.ts#Disconnect click opens modal with AC19 copy` (exact `.toContain('Відключити бота @SmokeBot? Вхідні повідомлення припиняться, а вебхук буде видалено. Підключити знову можна будь-коли.')`) | partial — assertion is on the Ukrainian copy only; the english locale string is never exercised by this spec. Minor since the prebuild parity gate (AC22) guarantees both locales exist; substantive copy drift in `en.json` would still slip past until manual smoke. See finding F-5. |
| AC20 (Webhook secret stored as SHA-256 hash) | `BotControllerIT#webhookSecretInMongo_isSha256Hash` (asserts `[0-9a-f]{64}` shape in Mongo after Connect); `BotServiceTest#connect_webhookSecretIsHashedWithSha256_neverPlaintext` (asserts `storedHash == SHA-256(plaintext)` via `MessageDigest`, 64 hex chars, NOT equal to plaintext) | covered |
| AC21 (Settings folder split + sub-nav routes) | Frontend `bot.spec.ts#renders SettingsSubnav with both routes (AC21 integration)` (asserts both `href` values) | covered |
| AC22 (i18n keys + parity gate) | Not testable via Vitest — the parity gate is enforced by `pnpm prebuild`, called out as a smoke step (tech-spec line 403). Per tech-spec this is intentional. | covered (by build gate, not test) |
| AC23 (Audit events on state change; token-regex negative on metadata) | `BotControllerIT#postConnect_validToken_returns200WithBotResponse` (asserts `bot_connected` event with `containsOnlyKeys("projectId","telegramBotId","telegramUsername")`); `postDisconnect_happyPath_returns200_andUpdatesMongoAtomically`, `postDisconnect_telegramDown_warnLogged_returns200` (assert `bot_disconnected` event with `webhookDeleted` boolean); `BotControllerIT#noTokenLeak_inAnyResponseBodyOrEventMetadata` (sweeps all events' metadata + ip + UA against token regex); `BotServiceTest#connect_bot_connected_event_metadata_containsNoTokenRegex` | covered |
| Supplementary (line 475: precise HTTP status codes 200/400/401/404/409/422/429/500/502) | BotControllerIT covers all of these as their per-AC tests assert exact status; 401 sweep is `anyEndpoint_unauthenticatedBareClient_returns401` | covered |
| Supplementary (line 476: both partial unique indexes present with filter `{status: 'CONNECTED'}`) | `BotIndexTest` (out of scope of this audit but exists per ls of the test dir); also implicitly verified via the race tests that depend on those indexes throwing DuplicateKey. Per AVP line 435 the index assertion is mongosh-script not test-code. | covered (out-of-scope test file + AVP step) |
| Supplementary (line 477: all bot tests + no regressions) | The full suite runs in Task 13 (pre-deploy QA). Not in scope of this audit. | covered (Task 13 owns) |
| Supplementary (line 478: `pnpm prebuild` exits 0 with parity) | Not a Vitest test; covered by build gate. | covered (build gate) |
| Supplementary (line 479: `BOT_TOKEN_ENCRYPTION_KEY` documented; backend fails to boot on missing/wrong length) | `TokenEncryptorBootValidationTest#contextFailsWhenKeyIsBlank`, `contextFailsWhenKeyIsNonHex`, `contextFailsWhenKeyIsWrongLength`, `contextStartsWhenKeyIsValidHexAndThirtyTwoBytes` (ApplicationContextRunner) plus `TokenEncryptorTest#missingKeyFailsFast`, `shortKeyFailsFast`, `nonHexKeyFailsFast`, `longKeyFailsFast` | covered |
| Supplementary (line 480: `app.telegram.base-url` default + IT override) | `BotControllerIT#registerTelegramBaseUrl` (`@DynamicPropertySource` overrides the property to the MockWebServer URL — proves override works without touching production code path) | covered |
| Supplementary (line 481: `BotTokenLeakTest` asserts no production code path serializes the encrypted-token field) | `BotTokenLeakTest` covers only DTO record-component absence and Bot.toString absence; no actual reflection over `Logger.info/.warn/.error` call sites or `ObjectMapper.writeValueAsString(Bot)` paths | partial — see finding F-1 |
| Supplementary (line 482: `MockWebServer` is `testImplementation`-only) | Out of scope of this audit (verified by build configuration inspection in Task 10/11). | n/a |
| Supplementary (line 483: persist-after-setWebhook failure path exercised by IT verifying compensating deleteWebhook) | `BotControllerIT#postConnect_persistFails_compensatingDeleteWebhookFires_returns500` (explicit setWebhook-precedes-deleteWebhook ordering check) | covered |
| Supplementary (line 484: SecurityConfig unmodified — existing rules inherited) | Not a test in this audit's scope (build-time invariant). | n/a |

---

## Pyramid Assessment

| File | Test type | Verdict |
|------|-----------|---------|
| `TokenEncryptorTest` | unit (no Spring context) | correct placement — pure logic; round-trip + tamper + wrong-key + IV freshness + constructor validation + format errors |
| `TokenEncryptorBootValidationTest` | slice (`ApplicationContextRunner`, no Mongo/Redis/Mailpit) | correct placement — verifies fail-fast bean wiring with the minimum context |
| `TelegramApiClientTest` | unit-with-MockWebServer (no Spring context) | correct placement — exercises retry policy, error mapping, scrubber. MockWebServer is the right substitute for `api.telegram.org`. |
| `BotServiceTest` | unit with mocked deps (Mockito + StepVerifier) | correct placement — pure orchestration unit; no Mongo/Redis/MockWebServer dependencies; 20 tests covering Connect/Disconnect/TestMessage/Get branches plus rate-limit and Redis-down paths. |
| `BotControllerIT` | integration (extends `AbstractIntegrationTest` → real Mongo + Redis testcontainers + class-level MockWebServer) | correct placement — every test exercises the HTTP → Controller → Service → Mongo + Redis stack with the real driver; Telegram is the only mocked boundary. ~22 tests covering AC1–AC20 + anti-enumeration + token-leak + auth filter sweep. |
| `BotTokenLeakTest` | unit (reflection over compiled classes) | correct placement for a structural invariant — but see finding F-1: the test is narrower than the AC line 481 promise. |
| `frontend/bot.spec.ts` | component (Vitest + happy-dom + Nuxt test-utils) | correct placement — store + composables mocked, page mounted with `mountSuspended`. 20 tests. |

**Inversions flagged:** none. Each suite operates at the layer the tech-spec
Testing Strategy assigns it. The integration suite avoids the temptation to
test pure-orchestration branches (those live in BotServiceTest with mocks);
BotServiceTest avoids hitting Mongo (uses `Mockito.mock(BotRepository.class)`
+ `Mono.just/error`). No unit test depends on a real Mongo/Redis.

---

## MockWebServer Usage

`MockWebServer` is referenced in two files (sanity grep confirmed):
`TelegramApiClientTest.java` and `BotControllerIT.java`.

### `TelegramApiClientTest`

- **Lifecycle:** per-test `@BeforeEach` starts a fresh server; `@AfterEach`
  shuts it down. `getMe_nettyReadTimeout_retriesThenReturns502TelegramUnavailable`
  shuts down the per-test server inline and starts a replacement to inject
  a custom timeout — properly bracketed; the `@AfterEach` still shuts down
  the replacement.
- **Enqueue/takeRequest balance:** Every `enqueue` is matched. Tests that
  care about URL/method assert `takeRequest(2, TimeUnit.SECONDS)` (timeout-
  bounded — no infinite poll). `getMe_5xx*` and `deleteWebhook_5xx*` enqueue
  4 responses and assert `getRequestCount() == 4`; the path/body of each
  retry is not asserted but the request count + status mapping is the
  contract under test, so this is fine.
- **`setWebhook_passesSecretTokenAndUrlInBody`:** parses the recorded body
  as JSON and asserts `hasSize(2)`, `url`, `secret_token` — strong assertion
  on the exact request body shape.
- **Parallel safety:** server is per-test (fresh in `@BeforeEach`), no
  cross-test leakage possible.

### `BotControllerIT`

- **Lifecycle:** class-level `MockWebServer` started in a static initializer
  (required for `@DynamicPropertySource` to see the URL before Spring boot)
  and shut down in `@AfterAll`. `@BeforeEach` resets the dispatcher to a
  fresh `QueueDispatcher` and drains any leftover requests — the documented
  fix for shared-state leak between tests. Sound.
- **Enqueue/takeRequest balance:** Every happy-path test enqueues exactly
  the responses it needs (`enqueueHappyPathConnect()` = getMe + setWebhook;
  AC9 persist-fail also enqueues the deleteWebhook for the compensation).
  Error-path tests enqueue exactly the failing response. The race tests
  enqueue an over-supply (2× getMe, 2× setWebhook, 1× deleteWebhook) and
  use `drainRequests()` to count actual invocations — `setCount` is
  asserted `between(1, 2)` and `delCount == setCount - 1` which captures
  both race orderings (pre-check trips early vs. post-setWebhook arrival
  with compensation).
- **`takeRequest` assertions on AC-critical paths:**
  - AC8/AC10 path assertion: `drainRequests()` asserts size==2 and ordered
    paths end with `/getMe` then `/setWebhook`, plus body contains
    `/webhooks/telegram/{projectId}` and `secret_token` form param. Strong.
  - AC9 path assertion: ordering check `setIdx < delIdx`. Strong.
  - AC2/AC7/AC11/AC15/AC16 zero-call assertion: `drainRequests().isEmpty()`.
    Correct.
  - AC11 11th-attempt path: `drainRequests().isEmpty()` after the 429.
    Correct.
  - AC12/D5 race: `setCount`/`delCount` parametrized range. See above.
  - AC6 platform-conflict path: `drainRequests().hasSize(1)` and asserts
    the single recorded request path ends with `/getMe`. Strong.
- **Missing request-log assertions:** none material on the AC-required
  paths. **Finding F-2** flags one weaker assertion: the AC11 trip test
  does not assert the Redis counter value at the 429 boundary.
- **Parallel-safety:** the class-level server is single-threaded by
  default. The race tests issue two HTTP requests via
  `Mono.zip(r1, r2).block()` with `subscribeOn(boundedElastic())`, but the
  MockWebServer serializes the responses from the queue — the test
  intentionally relaxes the request-count assertion to a range to
  accommodate both possible orderings (see comments at lines 510–514 and
  546–548). This is the correct trade-off; the strong invariants are
  exactly-one CONNECTED row + `{200, 409}` status pair.
- **Shutdown:** `@AfterAll shutdownTelegramMock()` is properly declared.
  No leaked instance.

---

## Concurrency Anti-patterns

The sanity grep `grep -rn "Thread.sleep" backend/src/test/java/com/botfunnel/bot
backend/src/test/java/com/botfunnel/common/crypto` returned **zero matches**.

- `Thread.sleep`: **absent**. Confirmed by grep.
- `Awaitility`: used once in `BotControllerIT.awaitEvent` with explicit
  `Duration.ofSeconds(5)` ceiling and `Duration.ofMillis(100)` poll interval.
  This is the bounded form the task file requires.
- `.block()` outside `StepVerifier`: present in `BotControllerIT` 35 times,
  all in test-setup or test-assertion positions (drain Mongo / Redis / spy
  setup / zip parallel Monos). These are the conventional sync-boundary
  blocks needed to assert against a reactive store; not a reactive-chain
  blocking violation. The production code's reactive chain is exercised
  via `webTestClient.exchange()` which subscribes internally — no
  `.block()` invokes the SUT's reactive pipeline.
- `StepVerifier` in `BotServiceTest` and `TelegramApiClientTest`: used
  consistently, with explicit `.verify(Duration.ofSeconds(...))` ceilings
  on the retry-exhaustion tests (10s for 5xx, 15s for read-timeout).
- Shared mutable counters across reactive tests: **absent**. The race tests
  in `BotControllerIT` rely on Mongo's atomic partial-unique-index check —
  no client-side counter is shared.
- Race tests: `BotControllerIT#postConnect_parallelSameToken_*` and
  `postConnect_parallelSameProjectDifferentTokens_*` use
  `Mono.zip(r1, r2, ...).block()` with each leg
  `.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())`. This
  is the recommended pattern; not the literal
  `Flux.range(0,2).parallel(2).flatMap(...)` shape the tech-spec line 388
  suggests, but functionally equivalent and verified to produce the
  required `{200, 409}` outcome.

**Verdict:** No anti-patterns in scope. The two minor frontend test concerns
(see `bot.spec.ts` `loading state disables input and button during connect` —
a deliberate hand-resolved promise with no timeout fallback) carry minimal
flakiness risk in CI but are bounded by Vitest's default per-test timeout.

---

## Token-leak Negative Assertion

The AC17/AC23 contract: "no string value in any emitted event's `metadata`
map matches the token regex `\d{1,20}:[A-Za-z0-9_-]{30,50}`".

### Where it lives

- `BotControllerIT#noTokenLeak_inAnyResponseBodyOrEventMetadata` (lines 802–
  861) — the canonical assertion.
- `BotServiceTest#connect_bot_connected_event_metadata_containsNoTokenRegex`
  (lines 388–410) — unit-level mirror.

### (a) Exists?

Yes. Both anchored (`TOKEN_REGEX.matcher(s).matches()`) and substring
(`TOKEN_SUBSTRING.matcher(s).find()`) checks live in the IT.

### (b) Real event collector?

Yes. The IT queries the real `EventRepository` via
`eventRepository.findAll().collectList().block()` (line 833), then iterates
over every event's `metadata`, `ipAddress`, and `userAgent` — not a literal
key check ("does metadata contain a key named 'token'"), but a *value-scan*
over all string values. This is what the spec demands and what would
actually catch a leak.

### (c) Invoked after both Connect AND Disconnect?

Yes. The test executes Connect (line 815) THEN Disconnect (line 825) THEN
awaits the `bot_disconnected` event THEN sweeps. So both event types
(`bot_connected` + `bot_disconnected`) are produced before the assertion
runs.

### (d) Would actually fail if the token slipped in?

Yes — by construction. The third leg of the test (lines 849–860) enqueues
a setWebhook 4xx where the Telegram `description` *contains* the actual
token: `"Bad webhook for token " + VALID_TOKEN + " on chat"`. This produces
a 500 response. The assertion then scans the 500 response body for the
token regex. The defense path that prevents the leak is:

1. `TelegramApiClient` scrubs the description with `scrubTokens()` before
   logging it as a WARN (verified by
   `TelegramApiClientTest#setWebhook_4xxConfigError_scrubsTokenInWarnLog`).
2. The `AppException` thrown carries a sanitized description (also
   verified by the unit test).
3. The `GlobalErrorHandler` serializes only the code + message, never the
   raw Telegram description.

If any one of those guards regressed, the IT body sweep would catch the
leak. This is a non-vacuous assertion — explicit comment at lines 846–848
acknowledges that the prior happy-path sweeps cannot fail because no token
flows into their response shape; this third leg is what makes the test
meaningful.

**Verdict:** the AC17/AC23 negative assertion is correctly wired and would
actually fail if the contract regressed. **No finding.**

---

## Frontend Coverage

`frontend/tests/pages/projects/[projectId]/settings/bot.spec.ts` — 20 tests.

### Required error branches (tech-spec line 412)

| Branch | Test | Status |
|--------|------|--------|
| connect 400 | `connect 400 resolves errors.bot.connect.400` (`/формат/i`) | covered |
| connect 409 | `connect 409 resolves errors.bot.connect.409` (`/іншого проєкту/i`) | covered |
| connect 422 | `connect 422 resolves errors.bot.connect.422` (`/невалідний/i`) | covered |
| connect 429 | `connect 429 resolves errors.bot.connect.429` (`/15 хвилин/i`) | covered |
| connect 500 | `connect 500 resolves errors.bot.connect.500` (`/вебхука/i`) | covered |
| connect 502 | `connect 502 resolves errors.bot.connect.502` (`/недоступний/i`) | covered |
| disconnect 404 | `disconnect 404 resolves errors.bot.disconnect.404` (`/підключеного бота/i`) | covered |
| testMessage 422 | `test message 422 resolves errors.bot.testMessage.422` (`/\/start/i`) | covered |
| testMessage 502 | **none** — the i18n key `errors.bot.testMessage.502` exists in both locales (verified) but the spec contains no test exercising this branch | **missing** |

### AC18 mask form

`masked token uses literal AC18 form` (line 174) asserts
`.toBe('1234567890:•••...xyz')` — the literal AC18 form with all three
component (`telegramBotId` + `:•••...` + `tokenSuffix`). Also asserted on a
second path (`renders Connected view from initial fetch`, line 377). Strong.

### AC19 modal copy

`Disconnect click opens modal with AC19 copy` (line 269) asserts
`.toContain('Відключити бота @SmokeBot? Вхідні повідомлення припиняться, а
вебхук буде видалено. Підключити знову можна будь-коли.')` — verbatim
Ukrainian copy with `@username` interpolated. Comment at lines 280–281
explicitly justifies the `.toContain` (over substring fragments) as
necessary to prevent a translator from dropping half the sentence
undetected. **Caveat (finding F-5):** asserted on the `uk` locale only;
no symmetric assertion for `en.json`.

### Token-in-DOM leak

`token never appears in error message or DOM (AC17)` (line 353) — types
`VALID_TOKEN` into the input, triggers a 422, then asserts
`wrapper.text() not.toMatch(TOKEN_REGEX)` and `errText not.toMatch(TOKEN_REGEX)`.

`renders Connected view from initial fetch (no Connect interaction)` (line
370) adds a second AC17 regex sweep on the Connected view (`wrapper.text()
not.toMatch(TOKEN_REGEX)`).

**Store-state snapshot leak:** not explicitly asserted. The store mock
captures `connect(VALID_TOKEN)` arguments via `vi.fn()` — a regression
that wrote the token into `reactiveStore.current` would not be caught
unless the page then rendered it. Strictly the AC17 contract is met
(the user-spec says "no API endpoint response and no log line"); the
store snapshot is a defense-in-depth concern but not a literal AC.
**Finding F-7** flags it as a minor.

### Page-shape coverage

- mount + fetch on mount (line 76): covered.
- Connect form renders when current=null + Connect button disabled-when-empty (line 84): covered.
- Connect button enables on typing (line 104) + stays disabled on whitespace (line 117): covered.
- Loading state disables input + button during in-flight connect (line 127): covered.
- Successful connect swaps view (line 153): covered.
- Initial-fetch-already-connected renders Connected directly (line 370): covered.
- Disconnect Cancel closes without API call (line 287): covered.
- Disconnect Confirm calls store + swaps view (line 305): covered.
- Modal closes on cross-tab disconnect (line 385): covered — edge case not in tech-spec but defensively good.

---

## Findings

### F-1 (major) — `BotTokenLeakTest` is a structural check, not the AC line 481 reflective assertion

**Severity:** major
**Justification:** The supplementary AC line 481 says "Reflective
`BotTokenLeakTest` asserts no production code path serializes the
encrypted-token field of the Bot entity to a string". The current test
asserts (a) `BotResponse` record components do not include the token field
names, and (b) `Bot.class.getDeclaredMethods()` contains no `toString()`.
This catches a future DTO leak and a future Lombok `@ToString` accident, but
does NOT catch the realistic regression where a logger call directly
interpolates `bot.getEncryptedTokenCiphertext()`. The runtime token-leak
sweep in `BotControllerIT#noTokenLeak_inAnyResponseBodyOrEventMetadata` is
the dynamic counterpart and partly compensates — but only across the paths
that test exercises. The AC promise is a static check across *all*
production paths.
**Recommendation (follow-up, not in scope of this audit):** broaden
`BotTokenLeakTest` to (1) `Reflections`-scan `com.botfunnel.bot` classes
for any method whose bytecode references `Bot.getEncryptedTokenCiphertext`
or `Bot.getEncryptedTokenIv`, then (2) inspect call sites for whether the
result reaches a `Logger` invocation. Alternatively, a simpler narrow
mitigation: ASM-scan `BotService` and `TelegramApiClient` for
`Logger.*(String, Object...)` calls whose argument array contains a
`getEncryptedTokenCiphertext()` call. Or accept that the dynamic IT sweep
+ code-review checklist are the operative defense and downgrade the AC
line 481 wording in the tech-spec.

### F-2 (major) — AC11 IT does not assert counter value (= 11) or remaining TTL on the 429 path

**Severity:** major
**Justification:** Tech-spec line 390 contract: "Verify Redis key
`brute:bot-connect:{userId}` value is 11 and TTL is between 0 and 900s."
The IT `postConnect_eleventhAttemptWithinWindow_returns429_noTelegramCalls`
pre-loads the counter to 10, asserts the 429 status, and asserts
`drainRequests().isEmpty()` — but never reads `redisTemplate.opsForValue()
.get(BRUTE_KEY)` or `redisTemplate.getExpire(BRUTE_KEY)`. A regression that
silently let the counter stay at 10 (i.e. the INCR didn't fire on the 11th
attempt) would still pass this test as long as the 429-return logic is
intact. Likewise, a regression that reset the TTL to a fresh 900s on every
attempt would slip through.
**Recommendation (follow-up):** add two assertions to the test:
```java
assertThat(redisTemplate.opsForValue().get(BRUTE_KEY).block()).isEqualTo("11");
Long ttl = redisTemplate.getExpire(BRUTE_KEY).block().getSeconds();
assertThat(ttl).isBetween(0L, 900L);
```

### F-3 (major) — Frontend `errors.bot.testMessage.502` branch is not tested

**Severity:** major
**Justification:** Tech-spec line 414 lists "Test Message: click → POST;
422 surfaces the explanatory toast" but the broader contract (user-spec
AC22 + tech-spec line 412 "Each error status from the backend connect
endpoint (400/409/422/429/500/502)") implies all listed error-statuses are
exercised. The `errors.bot.testMessage.502` key exists in both `uk.json`
and `en.json` (verified by direct file inspection), but no spec method
exercises it. A regression that broke the i18n key lookup for the 502 case
(typo on the resolution side, or a missing `testMessage.502` branch in
`useApiError`) would not be caught by Vitest. Since the spec calls the 502
case "reserved for forward compatibility" (user-spec line 260), this could
also be argued as deliberate-deferred, but the key was shipped and ought
to have a test guard.
**Recommendation (follow-up):** add a one-liner test mirroring the 422
case:
```ts
it('test message 502 resolves errors.bot.testMessage.502', async () => {
  reactiveStore.current = BOT_FIXTURE
  botStoreMock.sendTestMessage.mockRejectedValueOnce({ statusCode: 502 })
  // ...mount + click + settle...
  expect(wrapper.find('[data-test="bot-error"]').text()).toMatch(/недоступний/i)
})
```

### F-4 (minor) — Race tests' request-count assertion is intentionally weak

**Severity:** minor
**Justification:** `BotControllerIT#postConnect_parallelSameToken_*` asserts
`setCount ∈ [1, 2]` and `delCount == setCount - 1`. The comment at lines
510–514 justifies this by noting the loser may trip the pre-check early
(setCount=1, delCount=0) or arrive post-setWebhook (setCount=2, delCount=1).
That justification is correct. The risk is that a regression in which BOTH
requests trip the pre-check (setCount=0, delCount=0) would also satisfy
`setCount ∈ [1, 2]` if the bound were `[0, 2]` — but the bound here is
`[1, 2]` so the strict invariant "at least one Telegram call must have
fired" is captured. Still, the assertion could be tightened by adding
`assertThat(setCount + delCount).isGreaterThan(0L)` redundantly for clarity.
**Recommendation:** accept as-is; the strong invariants (exactly one
CONNECTED row + `{200, 409}` status pair) already gate the meaningful
regressions.

### F-5 (minor) — AC19 modal copy asserted on Ukrainian only

**Severity:** minor
**Justification:** AC19 demands verbatim copy; the prebuild parity gate
(AC22) ensures both `uk.json` and `en.json` carry the key, but does NOT
guarantee that `en.json`'s copy is a correct translation. A test that
swaps `useI18n` to `en` and re-asserts would catch a copy regression in
either locale. The Ukrainian copy is the primary user-facing string for
this audience (user is Ukrainian per project knowledge), so the priority
is low.
**Recommendation:** optional second test with `en` locale; or accept the
prebuild parity + manual smoke as sufficient defense.

### F-6 (minor) — `BotServiceTest#disconnect_telegramErrorMessageWithTokenInUri_isScrubbedInWarnLog` only covers the disconnect path

**Severity:** minor
**Justification:** The token-in-URI scrubbing assertion is excellent
defense — but the same regression could appear in any WARN log path that
echoes a transport-error message embedding the request URI. The connect-
path equivalent (setWebhook transient error with the token in the URI)
is covered by `TelegramApiClientTest#setWebhook_4xxConfigError_scrubsTokenInWarnLog`
but only against the description, not the URI. A future log line in
`BotService.connect`'s onError handler echoing the URI could leak.
**Recommendation:** optional — add a connect-path mirror of the
`disconnect_telegramErrorMessageWithTokenInUri_isScrubbedInWarnLog`
assertion if a regression of this class is plausible.

### F-7 (minor) — Frontend store-state snapshot is not asserted for token absence

**Severity:** minor
**Justification:** The token passes through `botStoreMock.connect(token)`
via `vi.fn()` — a regression that wrote `state.lastToken = token` in the
store would not be caught unless the page rendered that value. The user-
spec line 305 says "Tokens never round-trip to the frontend"; the implicit
mirror is that the frontend never persists the token client-side either.
**Recommendation:** optional defense-in-depth: assert
`reactiveStore.lastToken === undefined` (or whatever the store shape is)
after a connect call.

---

## Recommendations

1. **F-2 is the most concrete follow-up:** two extra `assertThat` lines on
   the AC11 IT close the only remaining gap in the AC↔assertion mapping.
2. **F-3 is also concrete:** ten lines of new Vitest. Cheap.
3. **F-1 is a tech-spec ambiguity, not a test gap.** Either widen the
   reflective test (50–100 lines using `Reflections` library) or downgrade
   the AC line 481 wording. Suggest the latter — the dynamic
   `BotControllerIT#noTokenLeak_inAnyResponseBodyOrEventMetadata` sweep
   plus the code-review checklist are operative defense.
4. **F-4, F-5, F-6, F-7 are accept-as-is** unless cheap follow-up time
   exists.
5. **No blocker means Task 13 (pre-deploy QA) can proceed.** Hand the
   three follow-up items to that wave as deferred-after-deploy items via
   `decisions.md` per the task post-completion checklist.

---

## Verdict

`Verdict: pass-with-followup`

Reasoning per the rubric in the task file:
- 0 blockers.
- 3 majors (F-1, F-2, F-3) — exceeds the `pass` ceiling of ≤ 2 majors.
- 4 minors (F-4, F-5, F-6, F-7) — accept-as-is acceptable.
- No flakiness risk introduced (no `Thread.sleep`, no unbounded `Awaitility`).
- No AC effectively untested. Every AC1–AC23 has at least one asserting test.
