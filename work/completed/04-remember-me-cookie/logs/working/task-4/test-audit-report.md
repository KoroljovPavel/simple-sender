# Test Audit — 04-remember-me-cookie

**Audit Wave** static review of Wave 1 test deliverables. Methodology: `test-master` skill (`references/test-quality-review.md`) + tech-spec Testing Strategy + Decision 7. Read-only audit; no test code modified, no tests executed.

**Scope:**
- `backend/src/test/java/com/botfunnel/security/RememberMeWebSessionIdResolverTest.java` (new, 270 LOC, 13 `@Test` + 1 `@ParameterizedTest`)
- `backend/src/test/java/com/botfunnel/auth/AuthServiceTest.java` (modified — extended `login_success_*` plus new `register_autoLogin_*`)
- `backend/src/test/java/com/botfunnel/auth/AuthServiceRegistrationTest.java` (verified for register-auto-login; case lives in `AuthServiceTest`, not here — acceptable per task spec)
- Cross-references: `AuthControllerIT.java`, `AbstractIntegrationTest.java`, production `RememberMeWebSessionIdResolver.java`, `AuthService.openSession`/`register`.

**Reference baselines:** tech-spec Decisions 1, 2, 6, 7; user-spec AC-1..AC-8; tech-spec Testing Strategy Scenarios A-H.

## Verdict

**PASS**. All Scenarios A-H from tech-spec Testing Strategy are covered with non-vacuous, deterministic assertions. The three AuthService cases (login+true, login+false, register-auto-login) lock the `REMEMBER_ME_ATTR` boolean against silent regression in both directions. The `mockExchangeWithCachedSession` helper was correctly extended in Task 1 — `exchange.getAttributes()` returns a real `ConcurrentHashMap`, so the attribute write is observable, not a silent no-op. Two bonus regression tests (Scenario H' for cookie-name propagation, Scenario F-inverse for secure=false vs https-scheme seed) strengthen coverage beyond the tech-spec floor.

Findings: 0 blocker, 0 major, 2 minor, 1 nit.

## Coverage Matrix

| Scenario | AC | Covered? | Test method (file:line) |
|----------|----|----|---|
| A — `Boolean.TRUE` → 30-day Max-Age | AC-1 | yes | `setSessionId_rememberMeTrue_writesCookieWithThirtyDayMaxAge` (`RememberMeWebSessionIdResolverTest.java:39`) |
| B — `Boolean.FALSE` → no Max-Age | AC-2 | yes | `setSessionId_rememberMeFalse_writesSessionCookieWithoutMaxAge` (`RememberMeWebSessionIdResolverTest.java:56`) |
| C — attribute absent → no Max-Age | (default-safe fallback) | yes | `setSessionId_attributeAbsent_writesSessionCookieWithoutMaxAge` (`RememberMeWebSessionIdResolverTest.java:73`) |
| D — re-login flip on independent exchanges | AC-7 | yes | `setSessionId_reLoginFlip_writesNewMaxAgeOnEachExchange` (`RememberMeWebSessionIdResolverTest.java:88`) |
| E — TTL reads config (parameterized) | AC-3 | yes | `setSessionId_maxAgeReadsConfiguredTtl_parameterized` `@ValueSource(longs = {30L, 7L, 60L})` (`RememberMeWebSessionIdResolverTest.java:107-120`) |
| F — cookie security flags pass-through | AC-1, AC-8 | yes | Six discrete `@Test` methods, one per attribute: `cookieName` (l. 125), `httpOnly` (l. 142), `secure` (l. 155), `sameSite` (l. 168), `path` (l. 181), `domain` (l. 194). Plus inverse-direction guard `setSessionId_secureExplicitFalse_winsOverHttpsSchemeSeed` (l. 255). |
| G — `resolveSessionIds` inherited (pre-fix cookie compat) | AC-4 | yes | `resolveSessionIds_readsCookieValueFromRequest_inheritedBehavior` (`RememberMeWebSessionIdResolverTest.java:208`) |
| H — `expireSession` writes Max-Age=0 | AC-5 | yes | `expireSession_writesMaxAgeZero_regardlessOfAttribute` (`RememberMeWebSessionIdResolverTest.java:221`); plus bonus `expireSession_withCustomCookieName_writesUnderConfiguredName` (l. 239) for cookie-name propagation regression. |
| AuthService — login + `rememberMe=true` writes `Boolean.TRUE` | AC-1 wiring | yes | `login_success_setsRememberMeTtl_andPublishesRememberMeTrueAttribute` (`AuthServiceTest.java:313`); attribute assertion at line 340-341 reads real `ConcurrentHashMap` from `mockExchangeWithCachedSession` (l. 125-137). |
| AuthService — login + `rememberMe=false` writes `Boolean.FALSE` | AC-2 wiring | yes | `login_success_noRememberMe_setsTtl24Hours_andPublishesRememberMeFalseAttribute` (`AuthServiceTest.java:355`); attribute assertion at line 379-380. |
| AuthService — register-auto-login writes `Boolean.FALSE` | AC-6 | yes | `register_autoLogin_publishesRememberMeFalseAttribute` (`AuthServiceTest.java:492`); uses real `MockServerWebExchange` with backing attribute map. **Found in `AuthServiceTest`, not `AuthServiceRegistrationTest`** — acceptable per task spec ("located in either file"). |
| `mockExchangeWithCachedSession` extended for `getAttributes()` | (Task spec point #8) | yes | `AuthServiceTest.java:125-137`: `when(exchange.getAttributes()).thenReturn(new ConcurrentHashMap<>())` (l. 135). Real Map → write is observable; comment on l. 133-134 explicitly documents the contract. No silent NPE / silent stub-miss path. |

All ten Description checklist items from `tasks/4.md` are addressed:

1. Three branches (TRUE/FALSE/absent) — Scenarios A/B/C, distinct, no duplicate. ✓
2. Scenario F non-vacuous — six separate `@Test` methods, each toggles ONE config attribute and asserts on THAT same attribute. Removing any single test would silently let the corresponding flag pass-through regress. ✓
3. Scenario D — two independent `MockServerWebExchange` instances, each with own attribute set, two independent asserts (`isEqualTo(Duration.ofDays(30))` vs `isNegative()`). Resolver carries no per-request state across calls. ✓
4. Scenario E — `@ParameterizedTest @ValueSource(longs = {30L, 7L, 60L})`, constructor argument flows into `Duration.ofDays(days)` assertion. Removing the config-read in resolver would fail at least 2/3 cases. ✓
5. Scenarios G + H — inherited behavior smokes present, plus bonus H' regression for cookie-name propagation. ✓
6. Login flow — both branches verify `exchange.getAttributes()` contains expected `Boolean.TRUE`/`Boolean.FALSE`. Real Map, not mock-call verify. ✓
7. Register-auto-login — `register_autoLogin_publishesRememberMeFalseAttribute` (`AuthServiceTest.java:492`) locks AC-6. Found in `AuthServiceTest`; not missing. ✓
8. `mockExchangeWithCachedSession` correctly extended (`when(exchange.getAttributes()).thenReturn(new ConcurrentHashMap<>())`); verify-on-attribute-puts is real, not a silent NPE. ✓
9. No duplication with `AuthControllerIT` / `ProfileControllerIT`. AuthControllerIT explicitly defers `Set-Cookie` introspection (l. 326-329 comment); login lifecycle tests there exercise Mongo `sessions` queries, not cookie attributes. ✓
10. No `Thread.sleep` in audit-target tests. No real-HTTP stand. All names read as behavior specs (e.g. `setSessionId_rememberMeTrue_writesCookieWithThirtyDayMaxAge`). ✓

## Findings

### Finding 1 — Scenario C does not assert cookie value, only Max-Age
- **Severity:** minor
- **Where:** `RememberMeWebSessionIdResolverTest.java:80-83`
- **Issue:** `setSessionId_attributeAbsent_writesSessionCookieWithoutMaxAge` asserts `cookie.getMaxAge().isNegative()` but never asserts that `cookie.getValue().equals("lazy-id")`. If a future refactor accidentally drops the session id from the cookie write under the absent-attribute path (e.g. early return on `attribute == null`), this test would still pass because `getCookies().getFirst("SESSION")` would return the partially-built cookie or null-check would still hold. Scenario A asserts both value and Max-Age (l. 50-51); B and C should match.
- **Fix:** Add one line after l. 82: `assertThat(cookie.getValue()).isEqualTo("lazy-id");`. Same fix recommended for Scenario B (`RememberMeWebSessionIdResolverTest.java:65-68`): add `assertThat(cookie.getValue()).isEqualTo("session-id-false");`.

### Finding 2 — Scenario A Max-Age asserted as `Duration.ofDays(30)` literal, drift from config not exercised in this scenario
- **Severity:** minor
- **Where:** `RememberMeWebSessionIdResolverTest.java:51`
- **Issue:** `assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(30))` hard-codes the day count `30` rather than referencing the constructor argument. The constructor on l. 41 is also `30L`, so the test is internally consistent — but if a future change switches the Scenario A constructor to `7L` while leaving the assertion at `30`, the test would fail in a misleading way (looking like a logic bug, not a test/setup mismatch). The drift-against-config concern is already covered by Scenario E parameterization, so this is purely a maintainability nit, not a correctness gap.
- **Fix:** Extract a local `long DAYS = 30L`, pass to constructor and use `Duration.ofDays(DAYS)` in the assertion. Or accept the duplication — Scenario E already covers TTL-from-config. Either is acceptable; flagging only because tech-spec edge-case checklist explicitly mentions magic-number drift (`tasks/4.md` line 129).

### Finding 3 — Scenario F cookie-name test asserts `getName()` redundantly with the implicit container key
- **Severity:** nit
- **Where:** `RememberMeWebSessionIdResolverTest.java:136-138`
- **Issue:** `setSessionId_cookieName_passThroughFromConfig` asserts both `getCookies().getFirst("ALT")` is non-null AND `alt.getName().equals("ALT")`. The second assertion is redundant given the first (the container map is keyed by name, so retrieving under "ALT" already implies the cookie's name is "ALT"). Cosmetic only.
- **Fix:** Drop l. 138 (`assertThat(alt.getName()).isEqualTo("ALT")`); the container-lookup assertion on l. 136-137 is sufficient. Or accept the redundancy as documentation. No behavior impact.

## Intentional Deferral (NOT a finding)

**Missing:** `@SpringBootTest` integration test that asserts on the real `Set-Cookie` HTTP header for the login flow.

**Why this is NOT a finding:** Decision 7 in `tech-spec.md:202-228` explicitly defers this. The shared `AbstractIntegrationTest` fixture uses `WebTestClient.bindToApplicationContext` (required for `SecurityMockServerConfigurers.csrf()` mutator); this binding does not propagate `Set-Cookie` headers in responses. Switching to `bindToServer` would require rebuilding the csrf bootstrap (fetch XSRF cookie via real GET, echo header on POST) for this single fix — disproportionate for an S-sized bug. The constraint is documented at `AuthControllerIT.java:326-329`:

> *"Login via webTestClient (bindToApplicationContext + csrf()). The Set-Cookie header is not propagated through the bind-to-context test infrastructure even though spring-session-data-mongodb does persist the session document — so we verify session lifecycle through MongoDB queries on the `sessions` collection rather than via cookie introspection."*

Coverage substitutes:
- Resolver's pure cookie-build logic is exercised at unit level (Scenarios A-H + bonuses) on `MockServerHttpResponse`.
- Wiring loop closed by `AuthService` exchange-attribute assertions on real `ConcurrentHashMap`.
- Live `Set-Cookie` header verified by curl smoke (`tech-spec.md:404-417` Verify-smoke; results recorded in `decisions.md` Task 1 Verification section).

This is exactly the strategy `test-quality-review.md` "Excessive Mocking" guidance prescribes (resolver inputs are 2 immutables + 1 attribute → unit test is appropriate; integration would need to mock 3+ frameworks to substitute for the missing `Set-Cookie` channel). No follow-up IT recommended.

## Test Quality Observations (non-findings)

- **Litmus test passes for all flagged-critical scenarios.** Removing the production line `if (Boolean.TRUE.equals(rememberMe)) { builder.maxAge(Duration.ofDays(rememberMeDays)); }` (`RememberMeWebSessionIdResolver.java:88-90`) breaks Scenarios A, D (first half), E (all 3 rows). Removing the production line `exchange.getAttributes().put(REMEMBER_ME_ATTR, Boolean.valueOf(rememberMe))` (`AuthService.java:604-605`) breaks both `login_success_*` cases AND `register_autoLogin_*`. No test gives false confidence.
- **Mock vs real Map decision is sound.** `mockExchangeWithCachedSession` uses `when(exchange.getAttributes()).thenReturn(new ConcurrentHashMap<>())` (l. 135) instead of a Mockito-mock Map. The choice is correct: a mock Map would allow `verify(attrs).put(...)` on `attrs.put(...)` no-op'ing silently, whereas a real Map persists the write and the test reads it back via `assertThat(exchange.getAttributes()).containsEntry(...)`. The contract is observed end-to-end.
- **Scenario H' (cookie-name regression) is good defensive coverage.** A future refactor that drops the `setCookieName(cookieName)` call in the resolver constructor (`RememberMeWebSessionIdResolver.java:53`) would silently regress logout — `expireSession` would still write under "SESSION" while `setSessionId` writes under "ALT". This test catches that.
- **Scenario F-inverse (`setSessionId_secureExplicitFalse_winsOverHttpsSchemeSeed`, l. 255-269) is good defensive coverage.** Without it, a refactor that drops the `PropertyMapper` override on `secure` would still pass the false→true case (Scenario F `secure_passThroughFromConfig`, l. 155-165) because the https-scheme seed and the explicit `setSecure(true)` happen to agree. The inverse direction guards the silent-drop case.
- **Pyramid balance.** All Wave 1 tests are unit-level. Given the resolver's pure-logic nature and the documented `bindToApplicationContext` constraint, this is appropriate. Existing integration tests (`AuthControllerIT`, `ProfileControllerIT`) cover session lifecycle through Mongo queries; no integration test was added or removed in Wave 1.
- **No `Thread.sleep`, no time-based flakiness, no shared state across tests.** Each test creates its own `MockServerWebExchange` and `RememberMeWebSessionIdResolver` instance. AuthService tests use fresh `ConcurrentHashMap` per `mockExchangeWithCachedSession` invocation.
- **Naming reads as specification.** `setSessionId_rememberMeTrue_writesCookieWithThirtyDayMaxAge`, `expireSession_writesMaxAgeZero_regardlessOfAttribute`, `register_autoLogin_publishesRememberMeFalseAttribute` — each name maps unambiguously to a single behavior contract.
- **No duplication with `AuthControllerIT` / `ProfileControllerIT`.** Existing IT tests on auth/session do not assert on cookie Max-Age at all (header is suppressed by the test stand). All Wave 1 tests live at the unit layer where they have observability.

## Summary

| Category | Count |
|----------|-------|
| blocker | 0 |
| major | 0 |
| minor | 2 |
| nit | 1 |

**Verdict: PASS.** Test layer is fit for Final Wave (Pre-deploy QA). The two minor findings (cookie value not asserted in Scenarios B/C, magic `30` in Scenario A) are non-blocking maintenance polish; they may be folded into a follow-up cleanup or accepted as-is. Decision 7 deferral is honored exactly as the tech-spec prescribes.
