# Task 16 — Test Quality Audit (WebFlux → MVC + Virtual Threads migration)

Pre-flip parent commit: `8989a9c` (last commit before `77118c4` "task 1 — relocate REMEMBER_ME_ATTR").
Post-flip HEAD: `de61b14` ("fix: address review round 1 for task 12").
Coverage source: `backend/build/reports/jacoco/test/html/index.html` (regenerated via `./gradlew jacocoTestReport -x test` to reuse the most-recent `test.exec` after the test step left some classes uninstrumented on its first failure).
Test suite outcome at audit time: `./gradlew test` → 529 tests, 8 failed, 2 skipped (failures pre-documented in Task 10/Task 12 decisions; see Findings — Critical).

---

## 1. Verdict

**FAIL-NEEDS-FIX**

Eight test methods fail under `./gradlew test` at the post-Wave-3 HEAD; some test-side, some product-side. All are pre-existing and were explicitly deferred by Task 10 and Task 12 decisions to "T15 audit wave". This task IS that audit. They must be re-opened before Task 17 (Pre-deploy QA) runs — Pre-deploy QA reads the green test bar as one of its gating signals, and a known-red suite would either suppress real regressions or be ignored as noise.

Task(s) to re-open: **Task 10** (owns `RememberMeCookieSerializerTest`, the controller-IT 401-vs-403 expectations, `SecurityConfigTest.csrfCookie_writtenOnSafeVerbRequest` context-cache flake) and **Task 7/8** (owns `AuthControllerSliceTest.me_withoutSession_returns401`, `BotConnectRaceIT` real-Tomcat login round-trip). Task 16 itself does not modify code (audit-only).

## 2. Per-dimension verdict

| # | Dimension | Verdict | Notes |
|---|-----------|---------|-------|
| 1 | Parity-or-better coverage vs pre-migration | NOTES | Net `@Test` deltas per module: auth +11, project +11, bot +2, profile +2, webhook +14, user 0, events 0, email +2, subscriber 0, funnel 0, common +28. Security is **−9** (JUSTIFIED: `RememberMeWebSessionIdResolverTest` 14 methods → `RememberMeCookieSerializerTest` 5 methods because the underlying API changed from `WebSessionIdResolver` to `CookieSerializer` — many pre-flip resolver-specific tests had no post-flip surface). Documented as Minor finding F1. |
| 2 | `StepVerifier` → `assertThatThrownBy(...).isInstanceOf(X)` | PASS | `grep -rE "StepVerifier\|reactor\.test\|expectError\|expectComplete"` against `backend/src/test/java/` → **0** matches. Pre-flip expectError counts (auth 29, project 10, bot 37, profile 5, webhook 1, user 1) vs post-flip `assertThatThrownBy` / `isInstanceOf` counts (auth 32/30, project 11/10, bot 42/37, profile 6/5, webhook 6/4, user 2/1) — no module downgraded. No silent `assertThat(...).isNotNull()` swap; no swallowed catch blocks. |
| 3 | `csrf()` on state-changing MockMvc requests | PASS | Multi-line `awk` sweep over every `mockMvc.perform(post|put|delete|patch(...))` block: every match without `.with(csrf())` is either a webhook POST (CSRF-exempt path), a deliberate "no CSRF → expect 403" rejection assertion (`TelegramWebhookControllerIT.api_csrfRegression_postWithoutXsrfToken_rejected`, `SecurityConfigTest.csrfProtection_excludesWebhookPath_keepsApiPathProtected`, `WebhookSecurityBlockTest.postApiWithoutAuth_rejected`), or a slice-test that doesn't load the CSRF filter chain. Zero accidental omissions. |
| 4 | `ConcurrencyTestUtils.parallelInvoke` race invariants | PASS | `ConcurrencyTestUtilsTest` (5 methods, all green) pins barrier-spread <200ms, `try/finally` no-hang on `task.call()` throw (the D10 contract), `n<=0` fail-fast, no-loss/no-duplicate result list, first-future-throws propagation. The three call-sites all assert the documented invariants: `BotConnectRaceIT` (`{200,409}` + single-CONNECTED-row + setWebhook count `[1,2]` + delWebhook = setCount−1), `TelegramWebhookControllerIT.receive_duplicateUpdateId_*` (single row + single ENQUEUED job + `duplicate` counter == 1.0), `TelegramWebhookP99IT.p99Latency_*` (100 statuses == OK + p99 latency bound). The pre-barrier-throw branch of the `try { ready.countDown(); start.await(); ... } finally { ready.countDown(); }` defence is honestly documented as inspection-only in the class javadoc rather than tested with brittle impl pokes — acceptable per Task 10 decision. |
| 5 | AC2 P99 test transport (`TelegramWebhookP99IT` + RANDOM_PORT + TestRestTemplate) | PASS | `TelegramWebhookP99IT.java` inherits `@SpringBootTest(webEnvironment = RANDOM_PORT)` from `AbstractIntegrationTest:25` (sole webEnvironment annotation in the suite — every IT inherits it). Imports + autowires `TestRestTemplate` (line 17, 42); uses `restTemplate.postForEntity(url, ...)` against a `LocalServerPort`-resolved URL (line 76, 89). `@Tag("slow")` present at line 35. `grep -l "p99Latency" backend/src/test/java/com/botfunnel/webhook/TelegramWebhookControllerIT.java` → empty (move confirmed; no residual). |
| 6 | AC16/AC17/AC18 literal assertions | PASS | **AC16**: `ProfileControllerIT.changePassword_preFlipBsonFixture_isReadCorrectlyByPostFlipRepository` (line 361) hand-crafts a pre-flip-shape BSON `Document("_id","pre-flip-session").append("principal",USER_ID).append("created",...).append("expireAt",...)`, inserts via `mongoTemplate.getCollection("sessions").insertOne(doc)`, and asserts post-flip `terminate-by-principal` deletes it. `AuthControllerIT.sessionsCollection_principalFieldPath_isAtTopLevel` (line 761) additionally pins `principal`-field-read against the live schema. **AC17**: `AuthControllerIT.register_emptyEmail_400_codeNull_messageJoinedFormat` (line 565) asserts `$.code: null` AND `$.message` matches regex `^email: [^,]+(, [^,]+: [^,]+)*$` — joiner shape pinned (round-1 fix tightened the regex from `.+` to `[^,]+` per Task 12 decisions). **AC18**: `AuthControllerIT.login_rememberMeTrue_setsAllSessionCookieAttributes_andCoEmitsXsrfToken` + `login_rememberMeFalse_omitsMaxAgeOnSessionCookie` parse the raw `Set-Cookie` header via `response.getHeaders("Set-Cookie")` (NOT `getCookie(name)`) and assert Max-Age + HttpOnly + Secure + SameSite together against `@Value`-bound configured values. XSRF-TOKEN co-emission on the SAME login response is **deliberately deferred** to `SecurityConfigTest.csrfCookie_writtenOnSafeVerbRequest` per Task 12 deviation (`CookieCsrfTokenRepository.saveToken` is unreachable when the request already carries a valid token via `.with(csrf())`); the deferral is documented in the test javadoc — accepted. |

## 3. TC12 per-file coverage (≥80% line+branch floor)

| File | Line cov | Branch cov | Verdict @80% |
|------|----------|------------|--------------|
| `RememberMeCookieSerializer` | 100% (17/17) | 100% (2/2 branches) | **PASS** |
| `WebhookPayloadSizeFilter` | 100% (37/37) | 87% (21/24 branches) | **PASS** |
| `TelegramApiClient` | 93% (94/101) | 67% (35/52 branches) | **FAIL** — branch < 80% |
| `TelegramSender` | 93% (147/158) | 78% (33/42 branches) | **FAIL** — branch < 80% (marginal) |
| `GlobalErrorHandler` | 100% (16/16) | 50% (1/2 branches) | **FAIL** — branch < 80% (only one branch missed but the per-class total is 2) |
| `ConcurrencyTestUtils` | N/A (test-side utility, not under `src/main/java` — JaCoCo does not instrument it) | N/A | **PASS** (utility's own contract is exercised by 5 ConcurrencyTestUtilsTest methods, all green) |
| `HttpRequestUtils` | 100% (9/9) | 92% (13/14 branches) | **PASS** |

`RememberMeCookieSerializer.writeCookieValue(...)` is 100% line + 100% branch — both `remember=true` and `remember=false` paths fire even on the failing test run (JaCoCo records execution, not assertion outcome). The AC18 zero-coverage carve-out from the task spec ("zero on this branch is Critical") does NOT trigger.

`grep -rE "WebTestClient" backend/src/test/java/` → 5 lexical matches, all in comments documenting migration history (`HealthEndpointTest:12`, `HealthSecurityTest:12`, `SecurityBlockTest:9`, `AbstractIntegrationTest:74`, `TelegramWebhookControllerIT:45`). No `import org.springframework.test.web.reactive.server.WebTestClient` and no usages. ✓

## 4. Findings — Critical

**F-C1. Eight tests fail under `./gradlew test` at audit-time HEAD.**

| # | Test | Failure | Likely root |
|---|------|---------|-------------|
| 1 | `RememberMeCookieSerializerTest.writeCookieValue_withRememberMeTrue_setsMaxAgeToRememberMeDays` (line 48) | expected `"session-id-true"`, got `"c2Vzc2lvbi1pZC10cnVl"` | **Test bug.** `DefaultCookieSerializer` base64-encodes cookie values when `useBase64Encoding=true` (Spring Session default). The assertion should decode (or `.with(serializer.setUseBase64Encoding(false))`, or assert on the decoded value). The branch coverage is unaffected — code path runs. |
| 2 | `SecurityConfigTest.csrfCookie_writtenOnSafeVerbRequest` (line 69) | `No cookie with name 'XSRF-TOKEN'` | Test asserts the `CsrfCookieMaterializer` fires on `GET /health`. Either (a) shared-context state from a prior test left the deferred token already-loaded so `CookieCsrfTokenRepository.saveToken` was skipped, or (b) `/health` no longer threads through the CSRF filter. Task 10 documented it as "occasional context-cache flake"; the in-package full-class run repros it here. Must isolate (`@DirtiesContext` or a dedicated test class) or rewire on a non-actuator GET that always carries the chain. |
| 3 | `AuthControllerSliceTest.me_withoutSession_returns401` (line 92) | expected 401 got 200 | The `@WebMvcTest` slice does NOT include the full filter chain even with `@Import(SecurityConfig.class)` because no `MongoClient` bean exists — security falls back to permitAll on missing autowire. Must either (a) add `@WithAnonymousUser` + `httpBasic().disabled()` explicit, (b) move the test out of the slice into `AuthControllerIT`, or (c) wire a stub `SecurityFilterChain`. |
| 4 | `BotControllerIT.anyEndpoint_unauthenticated_returns401` (line 766) | expected 401 got 403 | Anonymous + state-changing verb hits CSRF filter before AuthenticationEntryPoint → 403. Same shape as the previously-corrected `SecurityBlockTest` per Task 10 decisions; this test wasn't updated in the same round. Tighten to `isEqualTo(403)` or change the verb to GET. |
| 5 | `ProjectControllerIT.anyEndpoint_unauthenticated_returns401` (line 427) | expected 401 got 403 | Same root as #4. |
| 6 | `ProfileControllerIT.getProfile_unauthenticated_401` (line 198) | expected 401 got 403 | Same root as #4 — but on a GET. This is more surprising; either the request is being treated as state-changing by a misconfigured route, or CsrfCookieMaterializer is rejecting because no SESSION cookie present. Investigate before tightening. |
| 7 | `BotConnectRaceIT.postConnect_parallelSameToken_exactlyOneSucceeds_otherReturns409` (line 186) | got `[403, 403]` expected `[200, 409]` | The hand-rolled `login()` helper does a `GET /api/auth/me` to prime XSRF, then POSTs login with the cookies; but the test sees `[403, 403]` on the bot-connect race → the merged session cookie isn't being accepted by real Tomcat. Likely the XSRF token rotated post-login but `extractXsrfToken(mergedCookies)` returned null + the fallback to the pre-login `xsrf` no longer matches the post-login session. Add a "session ping" after login to confirm `/api/auth/me` returns 200 before launching the race, and surface that mid-test if it isn't. |
| 8 | `BotConnectRaceIT.postConnect_parallelSameProjectDifferentTokens_exactlyOneSucceeds_otherReturns409` (line 229) | got `[403, 403]` expected `[200, 409]` | Same root as #7. |

Severity is **Critical** because (a) the suite is not green at HEAD, and (b) Task 17 (Pre-deploy QA) is contracted to gate on `./gradlew test`. Pre-deploy QA cannot distinguish "intentional pre-existing red" from "regression introduced today" — both look identical in the CI signal — so the suite must be green before the audit wave hands off.

Recommended fix path: re-open Task 10 to address F-C1 #1, #2, #4, #5, #6 (all owned by the security/IT-infrastructure rewrites). Re-open Task 7 (or a dedicated retro-fix task) for #7, #8 (BotConnectRaceIT login-round-trip). #3 (AuthControllerSliceTest) is a slice-test fixture issue — quickest fix is to delete the negative assertion and rely on `AuthControllerIT.me_withoutSession_*` (already exists in spirit).

## 5. Findings — Major

**F-M1. Two TC12 high-risk files miss the 80% branch coverage floor (and one marginal).**

- `TelegramApiClient`: 67% branch (17 of 52 branches missed). Gaps likely sit in the hand-rolled retry/backoff matrix (status-class branching × attempt counter) — exercise via parameterized `TelegramApiClientTest` cases covering 429-with-Retry-After, 5xx-then-2xx, 5xx-exhausted, network timeout, 401-no-retry. Each new case adds 4–6 branches without re-architecting.
- `TelegramSender`: 78% branch (9 of 42 missed). Marginally under floor — likely the rate-limit + bot-token-invalid exception-mapping branches. Two targeted parameterized cases on the error-mapping switch would lift over 80%.
- `GlobalErrorHandler`: 50% branch (1 of 2 missed) — class-level percentage is misleading because the class has only 2 branches; the missed one is likely the `Throwable` fallback in the `AppException`-vs-`MethodArgumentNotValidException`-vs-generic chain. Add a `GlobalErrorHandlerTest` case that throws a non-`AppException` `RuntimeException` from a fake controller to cover the fallback.

These are coverage **floor** issues, not behaviour gaps that block the migration; they belong on the Wave-2 follow-up backlog rather than the atomic-flip critical path.

**F-M2. `AC18.login_rememberMeTrue_*` test deliberately skips the XSRF-TOKEN co-emission assertion.**

Documented in the test javadoc (lines 591–615) and in Task 12 decisions as structurally unachievable — `.with(csrf())` substitutes a `TestCsrfTokenRepository` that never invokes the real `CookieCsrfTokenRepository.saveToken`. The cookie-write path is verified by `SecurityConfigTest.csrfCookie_writtenOnSafeVerbRequest`. But that fallback test is itself flaky (F-C1 #2), so the AC18 XSRF assertion is currently uncovered when the flake fires. Recommend: replace the flaky context-cache test with a fresh-context `@DirtiesContext` companion in `SecurityConfigTest` (or move to its own class) so the AC18 XSRF gate has a reliable pin even when the in-class test order changes.

## 6. Findings — Minor

**F1. Security module test-count delta is −9 (RememberMeWebSessionIdResolverTest 14 methods → RememberMeCookieSerializerTest 5 methods).**

Justified because the underlying API changed (`WebSessionIdResolver` → `CookieSerializer`). Pre-flip tests for `expireSession`, `resolveSessionIds`, `setSessionId_reLoginFlip`, `path_passThroughFromConfig`, `domain_passThroughFromConfig`, `secureExplicitFalse_winsOverHttpsSchemeSeed` have no equivalent surface on the new `CookieSerializer` interface — `writeCookieValue` is the single entry point. Post-flip `constructor_appliesServerPropertiesCookieAttributes` collapses five pre-flip attribute-passthrough tests into one bundled assertion, which is acceptable for a passthrough but reduces blast-radius granularity (a regression in any single attribute now fails one test, not five).

Recommend (advisory only — not blocking): split the bundled passthrough assertion into one `@Test` per attribute (HttpOnly, Secure, SameSite, Path, Domain) so a future single-attribute regression names the broken attribute in the failure message.

**F2. `ConcurrencyTestUtils.parallelInvoke`'s finally-clause hedge (Error-class throws between pre-barrier countDown and `start.await()`) is documented as inspection-only and not exercised by `ConcurrencyTestUtilsTest`.**

Acknowledged in Task 10 decisions (line 270) and in the test's own javadoc. The exposed branch is "task body throws inside `task.call()`" — covered. The hedge guards a window so narrow it requires impl-injection to exercise reliably. The current carve-out is defensible — but if a future change widens the window (e.g. inserts an allocation between the pre-barrier countDown and start.await), the gap becomes load-bearing. Recommend adding a comment in `ConcurrencyTestUtils.java` itself (not just the test) noting that any structural change in the try-body MUST be paired with a new test that exercises the finally hedge.

**F3. `TelegramWebhookControllerIT.receive_duplicateUpdateId_returns200_singleRowSingleJob` (line 260) asserts `containsOnly(200)` for the status list.**

`containsOnly` ignores duplicates / order. With `parallelInvoke(2, ...)` the list always has size 2, but a future change to N=3 would silently still pass even if only one of three POSTs returned 200. The strong invariant is `hasSize(2).allMatch(s -> s == 200)`. Cosmetic — current pin is sufficient for N=2.

## 7. Findings — Nit

**N1. `TelegramWebhookControllerIT.receive_invalidSecret_returns401EmptyBody_counterTicked` (line 205) uses three sequential POSTs (wrong / empty / null secret) and a single `assertThat(counter("invalid_secret")).isEqualTo(3.0)` at the end.**

If one of the three POSTs returns 401 for the WRONG reason (e.g. wrong secret rejected by global handler before reaching counter), the per-case status assertion would catch it but the counter wouldn't disambiguate. Acceptable for the AC's scope but a parameterised test with one case per branch would localise a regression to the specific failing secret-shape.

**N2. `BotConnectRaceIT` defers MockWebServer cleanup to `@AfterAll` only and reuses the dispatcher across both race tests.**

`@BeforeEach` drains pending requests + resets the dispatcher, which is sound. But if either race test crashes between the enqueue of getMe/setWebhook responses and the actual POSTs, the next test sees stale enqueued responses. The `@BeforeEach` `while (mockTelegram.takeRequest(0, TimeUnit.MILLISECONDS) != null)` drains REQUESTS but not enqueued RESPONSES. Recommend `mockTelegram.setDispatcher(new QueueDispatcher())` already swaps to a fresh queue — but explicitly draining via a quick `mockTelegram.enqueue(...).consume()` loop or simply restarting the server per test (more deterministic) removes the cross-test contamination risk.

**N3. `AbstractIntegrationTest.MailpitTestConfig` provides a `@Primary JavaMailSender`.**

A test class extending `AbstractIntegrationTest` that wants to inject a stub mail sender must use `@MockitoSpyBean` or pick up the `@Primary` from the base config. Documented inline. Not a defect but a footgun for future test authors.
