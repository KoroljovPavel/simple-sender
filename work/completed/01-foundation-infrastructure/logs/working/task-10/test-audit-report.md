# Test Audit Report: 01-Foundation Infrastructure

**Task:** 10 — Test Audit (Audit Wave)
**Date:** 2026-05-04
**Auditor:** AI agent (test-master)
**Scope:** HealthEndpointTest, HealthSecurityTest, SecurityBlockTest

---

## Summary

**Overall assessment: PASS — no critical findings.**

All three integration tests are correctly structured, produce meaningful assertions, and would fail under the specific regression scenarios described in the task. Mock setup is correct and minimal. Test suite ran successfully in the most recent cached build.

### Test Suite Run Result

**Result: BUILD SUCCESSFUL**

Evidence from `backend/build/test-results/test/`:

| Test | Result | Failures | Errors | Time |
|------|--------|----------|--------|------|
| HealthEndpointTest.healthEndpointReturns200WithOkBody() | PASS | 0 | 0 | 0.168s |
| HealthSecurityTest.healthPermittedWithoutAuth() | PASS | 0 | 0 | 0.011s |
| SecurityBlockTest.undefinedPathBlockedReturns401() | PASS | 0 | 0 | 0.017s |

Note: the `./gradlew build` invocation at task start returned `BUILD SUCCESSFUL` with all tasks `UP-TO-DATE` (cached). The XML test results confirm the prior run had 0 failures and 0 errors. A `./gradlew clean test` was requested to get a fresh run but permission was not granted; the cached XML results are sufficient to confirm the suite is green.

Spring Boot version in use: **3.5.0**, Java: **21.0.9**, Gradle: **8.14.4**.

---

## Per-Test Findings

### HealthEndpointTest

**File:** `backend/src/test/java/com/botfunnel/HealthEndpointTest.java`

**Purpose:** Verify `GET /health` returns HTTP 200 with body `{"status":"ok"}`.

**Assertions present:**
- `.expectStatus().isOk()` — asserts HTTP 200. Would fail if controller returned HTTP 500 or any non-200 status.
- `.expectHeader().contentType(MediaType.APPLICATION_JSON)` — asserts `Content-Type: application/json`. Would fail if controller omitted the content type.
- `.expectBody().jsonPath("$.status").isEqualTo("ok")` — asserts JSON field `status` equals `"ok"`. Uses JSON path (not exact string comparison), making it whitespace-agnostic and key-targeted. Would fail if controller returned `{"status":"error"}`.

**Regression sensitivity:**
- Scenario: controller returns HTTP 500 → test FAILS on `.expectStatus().isOk()`. Sensitive.
- Scenario: controller returns `{"status":"error"}` → test FAILS on `.jsonPath("$.status").isEqualTo("ok")`. Sensitive.
- Scenario: controller returns HTTP 200 with empty body → test FAILS on `.jsonPath("$.status")` (no such field). Sensitive.

**Setup correctness:**
- `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` — correct.
- `@Autowired WebTestClient webTestClient` — correct; no `MockMvc`.
- Mocks: `MongoClient`, `RedisConnectionFactory`, `ReactiveRedisConnectionFactory` — correct and minimal (see @MockitoBean audit below).

**Verdict: PASS.** All required assertions present. Regression sensitive. Setup correct.

---

### HealthSecurityTest

**File:** `backend/src/test/java/com/botfunnel/HealthSecurityTest.java`

**Purpose:** Verify `SecurityWebFilterChain.pathMatchers("/health").permitAll()` is in effect — `/health` returns HTTP 200 even when a (potentially invalid) auth credential is presented.

**Test method: `healthPermittedWithoutAuth()`**

The request sends `.header("Authorization", "Bearer invalid-token")`. This is an **approved deviation** documented in the task prompt: the invalid token is intentional to differentiate this test from `HealthEndpointTest` and to prove `permitAll()` is genuinely indifferent to credentials, not just absent of them. The test name `healthPermittedWithoutAuth` refers to "without valid auth" — acceptable and clarified by the inline comment in the source.

**Assertions present:**
- `.expectStatus().isOk()` — asserts HTTP 200. Would fail if `/health` was changed to `authenticated()` in SecurityConfig.
- `.expectHeader().contentType(MediaType.APPLICATION_JSON)` — redundant but harmless Content-Type check.
- `.expectBody().jsonPath("$.status").isEqualTo("ok")` — body assertion (same as HealthEndpointTest).

**Regression sensitivity:**
- Scenario: `SecurityConfig` changes `pathMatchers("/health").permitAll()` to `.authenticated()` → with an invalid token, Spring Security rejects the request with HTTP 401. Test FAILS on `.expectStatus().isOk()`. Sensitive.
- Scenario: Rule ordering is reversed (`.anyExchange().authenticated()` placed before `.pathMatchers("/health").permitAll()`) → `/health` would require auth. Test FAILS on `.expectStatus().isOk()`. Sensitive.

**Setup correctness:**
- `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` — correct.
- `@Autowired WebTestClient webTestClient` — correct.
- Mocks: same three as HealthEndpointTest — correct.
- No `@WithMockUser` or real JWT present — the test relies on real security filter chain behavior, which is appropriate for this integration test.

**Verdict: PASS.** Assertions are meaningful. Regression sensitive. The `Authorization: Bearer invalid-token` header is intentional and correctly documented. Test name could be clarified (see Findings), but behavior is correct.

---

### SecurityBlockTest

**File:** `backend/src/test/java/com/botfunnel/SecurityBlockTest.java`

**Purpose:** Verify `anyExchange().authenticated()` blocks undefined paths — unauthenticated requests to `/api/nonexistent` return HTTP 401.

**Assertions present:**
- `.expectStatus().isUnauthorized()` — asserts HTTP 401. Would fail if `anyExchange()` was changed to `permitAll()`.

**Regression sensitivity:**
- Scenario: `anyExchange().authenticated()` replaced with `anyExchange().permitAll()` → unauthenticated requests pass through. Test FAILS on `.expectStatus().isUnauthorized()`. Sensitive.
- Scenario: `/api/nonexistent` is accidentally added to `pathMatchers(...).permitAll()` → same failure. Sensitive.

**Request setup:** No `Authorization` header. No `@WithMockUser`. Correct for testing unauthenticated access to a blocked path.

**Setup correctness:**
- `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` — correct.
- `@Autowired WebTestClient webTestClient` — correct.
- Mocks: same three — correct.
- No Content-Type assertion — not needed; 401 response has no meaningful body to assert.

**Verdict: PASS.** Single focused assertion is sufficient and regression-sensitive. Setup correct.

---

## @MockitoBean Audit

All three test classes declare the following mocks:

```java
@MockitoBean MongoClient mongoClient;
@MockitoBean RedisConnectionFactory redisConnectionFactory;
@MockitoBean ReactiveRedisConnectionFactory reactiveRedisConnectionFactory;
```

**Class correctness:**

| Mock | Import | Verdict |
|------|--------|---------|
| `MongoClient` | `com.mongodb.reactivestreams.client.MongoClient` | Correct. This is the real reactive MongoDB client interface used by Spring Data MongoDB Reactive auto-configuration. A common mistake is mocking a non-existent `ReactiveMongoClient` class — not made here. |
| `RedisConnectionFactory` | `org.springframework.data.redis.connection.RedisConnectionFactory` | Correct. Required by `RedisAutoConfiguration` (non-reactive variant) in Spring Boot 3.5.x. Would cause context startup failure if omitted. |
| `ReactiveRedisConnectionFactory` | `org.springframework.data.redis.connection.ReactiveRedisConnectionFactory` | Correct. Required by `ReactiveRedisConnectionFactory`-dependent auto-configs (Lettuce reactive). |

**Annotation correctness:**

`@MockitoBean` (from `org.springframework.test.context.bean.override.mockito.MockitoBean`) is used, not the deprecated `@MockBean`. This is correct for Spring Boot 3.4.0+ — `@MockBean` was deprecated in 3.4.0 and `@MockitoBean` is the replacement. This is an **approved deviation** documented in the task prompt.

**Minimality:** The three mocks are the exact set needed to suppress MongoDB and Redis auto-configuration without real DB connections. No extra mocks are present. No production beans (controllers, security config) are mocked. The Spring context starts fully with only DB connection factories replaced — this is the correct minimal setup for infrastructure tests that do not exercise DB queries.

**Consistency:** All three test classes have identical mock declarations. This is appropriate because all three tests share the same Spring application context startup requirements. A shared base class was considered and explicitly rejected during Task 5 reviews as over-engineering for three small classes.

**Verdict: PASS.** Correct classes, correct annotation, minimal set.

---

## patterns.md Deviation Note

**Documented and accepted deviation (Decision 6).**

`patterns.md` states: "No mocking of the database in integration tests — use real embedded MongoDB."

These tests use `@MockitoBean` for `MongoClient` and Redis connection factories instead of embedded MongoDB. This intentionally deviates from the patterns.md rule.

**Rationale (from tech-spec Decision 6):** The patterns.md rule targets integration tests that exercise MongoDB queries and business logic. The Epic 01 tests exercise HTTP routing and Spring Security filters only — MongoDB is never queried in any of the three tests. Using embedded MongoDB (`de.flapdoodle.embed.mongo`) would add a heavyweight dependency for tests that never touch the database layer. The deviation is scoped exclusively to this Epic 01 skeleton phase.

**Scope limitation:** All future epics that introduce real MongoDB repository usage must follow the patterns.md rule (embedded MongoDB or real DB in Docker, no mocking).

This deviation is **not flagged as a bug or warning.** It is recorded here as a documented accepted deviation for audit completeness.

---

## Findings

### Suggestion (severity: suggestion)

**HealthSecurityTest — test method name vs behavior mismatch**

The method is named `healthPermittedWithoutAuth()`, but the request includes `.header("Authorization", "Bearer invalid-token")`. The name implies "no auth" while the implementation sends an invalid credential. The behavior is correct and intentional (proving `permitAll()` ignores credentials), but the name may confuse a future developer reading the test in isolation.

**Recommendation:** Consider renaming to `healthPermittedWithInvalidToken()` or adding a more prominent comment at the method level (currently the comment is in the method body, which is sufficient but could be at the declaration). Not a functional issue — no change required before Task 11.

---

### Observation (informational, not a finding)

**Mockito self-attaching agent warning in test output**

The `HealthEndpointTest` stderr shows:
```
Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future releases of the JDK.
```

This is a known Mockito warning with modern JDKs (JDK 21+) and is not a test failure. It does not affect test correctness. Resolution (adding Mockito as a javaagent in `build.gradle`) is a future improvement, not a blocker.

---

## Acceptance Criteria Verification

| Criterion | Status |
|-----------|--------|
| All three tests pass (`BUILD SUCCESSFUL`) | PASS |
| HealthEndpointTest asserts HTTP 200 via `.expectStatus().isOk()` | PASS |
| HealthEndpointTest asserts `$.status == "ok"` via JSON path | PASS |
| HealthEndpointTest would fail if controller returned HTTP 500 | PASS (assertion chain fails at isOk()) |
| HealthEndpointTest would fail if controller returned `{"status":"error"}` | PASS (jsonPath assertion fails) |
| HealthSecurityTest makes request (with invalid auth) and asserts HTTP 200 | PASS (approved deviation) |
| HealthSecurityTest would fail if `/health` changed to `authenticated()` | PASS |
| SecurityBlockTest requests `/api/nonexistent` without auth, asserts HTTP 401 | PASS |
| SecurityBlockTest would fail if `anyExchange().permitAll()` used | PASS |
| `@MockitoBean` targets `com.mongodb.reactivestreams.client.MongoClient` | PASS |
| `@MockitoBean` includes `ReactiveRedisConnectionFactory` | PASS |
| No unnecessary extra mocks | PASS |
| `@SpringBootTest(webEnvironment = RANDOM_PORT)` in all tests | PASS |
| `WebTestClient` used (not `MockMvc`) | PASS |
| patterns.md deviation (Decision 6) noted as documented and accepted | PASS |

**All 15 acceptance criteria: SATISFIED.**

---

## Conclusion

The test suite for Epic 01 is fit for purpose. All three integration tests produce meaningful assertions, are regression-sensitive to the specific failure modes described in the task spec, and are correctly set up with minimal mocks. One suggestion (test method naming) and one informational observation (Mockito agent warning) were identified — neither blocks progression to Task 11 (Pre-deploy QA).

**No critical findings. No blockers. Task 11 may proceed.**
