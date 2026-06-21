# Code Audit — Feature 08-webhook-ingestion (Task 12, Wave 5)

**Scope:** holistic cross-component sweep of all 04b source + test files. Per-file
quality was reviewed during T1–T11; this audit targets findings that only become
visible when the whole feature lands.

**Reference commit:** `5083546` (T10 + T11 round-1 fixes) — latest `main` at audit
time.

**Method:** read every 04b source + test file under
`backend/src/main/java/com/botfunnel/{webhook,common/crypto,common/metrics,subscriber,funnel,security,bot}`
and matching tests; cross-check against tech-spec Decisions 1–19 and user-spec ACs
1–19; run grep guards listed in the task plus prove-negative searches for `TODO`,
`FIXME`, `_stubs/`, `BackgroundJob.enqueue`, and stray Micrometer instantiations.

## Decision → Code Mapping

| Decision | Implementing site(s) | Test guard(s) | Status |
| --- | --- | --- | --- |
| **1.** 404 lookup — Bot CONNECTED + Project existence + `deletedAt == null` | `TelegramWebhookController.java:116-126` (`findByProjectIdAndStatus` → `projectRepository.findById` → `filter(deletedAt == null)` → `switchIfEmpty` 404; malformed ObjectId collapsed via `onErrorResume(IllegalArgumentException)`) | `TelegramWebhookControllerIT` (404 for missing / soft-deleted / no-bot / malformed) | OK |
| **2.** Secret token — SHA-256 hex storage + `MessageDigest.isEqual` constant-time | `WebhookSecretVerifier.java:31-38` (`Sha256Hex.hex(...)` re-hash, parse hex, `MessageDigest.isEqual`); storage at `BotService.java:149` (`Sha256Hex.hex(secretHex)`) | `WebhookSecretVerifierTest` (9 cases incl. mismatch, empty, malformed-hex) | OK |
| **3.** 401 / 404 / 413 emitted as `ResponseEntity.status(...).build()` (no body, no `AppException`) | `TelegramWebhookController.java:122-124, 132-133, 142, 156, 168, 176-177`; filter 413 at `WebhookPayloadSizeFilter.java:94-95` | `TelegramWebhookControllerIT` body-empty assertions; outer `onErrorResume → 500 empty` enforces Decision 3 even on enqueue failure | OK |
| **4.** Idempotency — unique `(projectId, updateId)` + `DuplicateKeyException` → re-enqueue + 200 | `RawUpdate.java:17-21` (`@CompoundIndex unique=true`); `TelegramWebhookController.java:157-169` (catch DKE → `rejectedDuplicate.increment()` → `findFirstByProjectIdAndUpdateId` → re-enqueue → 200) | `RawUpdateRepositoryTest` (compound + duplicate), IT race test | OK |
| **5.** `ownerChatId` populate — atomic `findAndModify` predicate `ownerChatId=null` | `ProcessTelegramUpdateJob.java:218-231` (predicate `_id=botId, status=CONNECTED, ownerChatId=null` → `Update.set("ownerChatId", chatId)`) | `ProcessTelegramUpdateJobTest` (predicate-fail no-overwrite, second-/start no-op) | OK |
| **6.** `TelegramUpdate` typed record + snake_case + `JsonNode` stubs + `@JsonIgnoreProperties` | `webhook/dto/TelegramUpdate.java:16-29`; nested `Message.java`, `Chat.java`, `User.java` each `@JsonIgnoreProperties(ignoreUnknown=true)` | `TelegramUpdateDeserializationTest` (10 cases) | OK |
| **7.** `Bot.ownerChatId` already present (04c) — no schema change in 04b | No 04b modification to `Bot.java` field declaration; read site at `ProcessTelegramUpdateJob.java:218` | `BotRepositoryTest.findById_legacyDocumentWithoutOwnerChatId_readsAsNull` (existing) | OK |
| **8.** TTL `raw_updates.createdAt` partial filter UPPERCASE `['PENDING','DONE']`, `expireAfter="90d"` | `RawUpdate.java:54-57` (`@Indexed(expireAfter="90d", partialFilter="{ 'processingStatus': { $in: ['PENDING', 'DONE'] } }")`); static block at `RawUpdate.java:27-37` pins enum-name byte-identity | `RawUpdateRepositoryTest` (TTL 90d + UPPERCASE F5 regression guard) | OK |
| **9.** DLQ = JobRunr Failed-queue + `processingStatus` + re-entry guard | `ProcessTelegramUpdateJob.java:88-92` (re-entry guard); `handleFailure(...)` at `ProcessTelegramUpdateJob.java:114-144` (atomic `findAndModify` → FAILED + scrubbed/truncated error → `meterRegistry.counter(... outcome=failure)` → rethrow with class-name-prefixed scrubbed message, no cause chain) | `ProcessTelegramUpdateJobTest` (re-entry, failure, rethrow scrubbed) | OK |
| **10.** Observability — explicit `@Bean SimpleMeterRegistry` + 4 controller counters + filter counter + worker counter | `common/metrics/MeterRegistryConfig.java:22-28`; controller counters at `TelegramWebhookController.java:88-97` + per-request success counter at line 154; filter counter at `WebhookPayloadSizeFilter.java:51-54, 93`; worker counter at `ProcessTelegramUpdateJob.java:106, 128` | `MeterRegistryConfigTest`, `TelegramWebhookControllerIT` (counter assertions), `WebhookPayloadSizeFilterTest` (rejected counter), `ProcessTelegramUpdateJobTest` (worker outcome counter) | OK |
| **11.** WebFlux→JobRunr enqueue bridge — `Mono.fromCallable + boundedElastic` + deterministic UUID | `TelegramWebhookController.java:180-197` (deterministic `UUID.nameUUIDFromBytes(rawUpdateId, UTF_8)` + `Mono.fromRunnable` + `subscribeOn(boundedElastic())`). **Refactor:** uses injected `JobScheduler` bean (line 54, 77, 83-85) instead of static `BackgroundJob.enqueue`. Decision 11 alternative (c) — production behaviour identical; documented inline lines 64-69 and in `decisions.md` task 10 entry. | `TelegramWebhookControllerIT` (deterministic UUID self-heal IT) | OK (alt-(c) variant) |
| **12.** Cascade ordering — `logEventBlocking(...).block()` BEFORE `processingStatus=DONE` | `ProcessTelegramUpdateJob.java:266, 273, 280, 287` — all four event log helpers use `eventService.logEventBlocking(...).block()`; cascade then sets DONE at line 104-105 | `ProcessTelegramUpdateJobTest` (cascade order via source-ordering inspection per decisions.md T9) | OK |
| **13.** Payload cap 1 MB — scoped `WebFilter` on `/webhooks/telegram/{projectId}`; chunked + missing-CL + >1MB | `WebhookPayloadSizeFilter.java:31-99` (`PathPattern("/webhooks/telegram/{projectId}")`, `@Order(HIGHEST_PRECEDENCE+10)`, multi-value `Transfer-Encoding` parse with `Locale.ROOT`, `getContentLength() < 0` for missing/unparseable, `> 1_048_576` for oversize); SecurityConfig CSRF scope via `AndServerWebExchangeMatcher(DEFAULT_CSRF_MATCHER, Negated(webhook-path))` at `SecurityConfig.java:77-82` | `WebhookPayloadSizeFilterTest`, `WebhookSecurityBlockTest::postApiAuthedWithoutXsrfToken_returns403` | OK (with documented CSRF adjustment from bare-negated to AND-with-default-matcher — see `decisions.md` task 11 entry) |
| **14.** Stub services in final packages `subscriber/`, `funnel/` — NOT `_stubs/` | `subscriber/SubscriberService.java`, `subscriber/NoOpSubscriberService.java`, `funnel/FunnelTriggerService.java`, `funnel/NoOpFunnelTriggerService.java`. Grep `_stubs` / `stubs/` across `main/java` and `test/java`: 0 matches. | `NoOpSubscriberServiceTest`, `NoOpFunnelTriggerServiceTest` | OK |
| **15.** `sha256Hex` promoted to `common/crypto/Sha256Hex.hex` | `common/crypto/Sha256Hex.java:21-28`. `BotService.java:149` and `WebhookSecretVerifier.java:31` both call `Sha256Hex.hex(...)`. Grep `MessageDigest.getInstance` in `main/java`: exactly 1 match in `Sha256Hex.java:23`. | `Sha256HexTest` (4 cases) | OK |
| **16.** `WebhookSecretVerifier` as `@Component` | `WebhookSecretVerifier.java:17-18` (`@Component`); injected at `TelegramWebhookController.java:52, 75, 81` | `WebhookSecretVerifierTest` (mocked via `@MockitoSpyBean` per decisions.md) | OK |
| **17.** No per-chat ordering in 04b | No serialization primitive in `ProcessTelegramUpdateJob.java`. Worker uses JobRunr default concurrency. Stub services no-op. | n/a — accepted threat | OK |
| **18.** No polling-mode for local dev | No `getUpdates` / polling code in `webhook/` package. Local-dev path is ngrok + `setWebhook` via 04a (documented in T8 runbook `docs/staging-smoke/08-webhook-ingestion.md`) | n/a | OK |
| **19.** Final Wave — `pre-deploy-qa` only (no Deploy, no Post-deploy MCP) | `work/08-webhook-ingestion/tasks/` contains T1–T15 only — no Deploy task; T8 ships the staging-smoke runbook. T15 is `pre-deploy-qa`. | Operator-manual verification per runbook | OK (confirmed, not drift) |

## Findings

(Sorted by severity desc — Critical → Major → Minor → Info.)

### Finding 1 — User-spec AC15 wildcard vs Decision 13 single-segment narrowing

**Severity:** Info

**Files / lines:**
- `backend/src/main/java/com/botfunnel/security/SecurityConfig.java:81, 91`
- `backend/src/main/java/com/botfunnel/webhook/WebhookPayloadSizeFilter.java:39-40`
- `work/08-webhook-ingestion/user-spec.md:79` (AC15) — literal text `/webhooks/telegram/**`

**Observation:** user-spec AC15 ratifies the security path as
`/webhooks/telegram/**` (double-star). Decision 13 deliberately narrows the
implementation to single-segment `/webhooks/telegram/{projectId}` per security
audit M4 (prevents future sub-path leak like `/webhooks/telegram/v2/...`). The
implementation matches Decision 13 (good — narrower is safer). However a literal
reading of AC15 against `SecurityConfig.java:91` looks like a deviation.

**Recommendation:** none for code. Worth recording here so the pre-deploy-qa
task (T15) understands that the AC15 single-line check is satisfied by a
deliberately narrower matcher and does not need re-widening. The deviation is
already documented in tech-spec Decision 13 rationale and `decisions.md` task 11
entry. No action required for merge.

### Finding 2 — `SecurityConfig` CSRF wiring differs from Decision 13 plain text

**Severity:** Info

**Files / lines:**
- `backend/src/main/java/com/botfunnel/security/SecurityConfig.java:77-82`
- `work/08-webhook-ingestion/tech-spec.md` Decision 13 plain prose
- `work/08-webhook-ingestion/decisions.md` task 11 entry

**Observation:** Decision 13's prose describes CSRF scoping via
`requireCsrfProtectionMatcher(new NegatedServerWebExchangeMatcher(...))`.
Implementation uses `AndServerWebExchangeMatcher(CsrfWebFilter.DEFAULT_CSRF_MATCHER,
NegatedServerWebExchangeMatcher(...))`. Without the AND, a bare-negated matcher
would extend CSRF enforcement to GET/HEAD/OPTIONS for non-webhook paths and break
`/health` plus every read-only endpoint. The implementation is the materially
correct shape and is documented inline at `SecurityConfig.java:70-76` and in
`decisions.md` task 11.

**Recommendation:** none for code. Consider a one-line follow-up to amend
tech-spec Decision 13's "Decision:" sentence to reflect the AND form so future
readers do not mistake the deployed shape for drift. No action required for
merge.

### Finding 3 — `processingError` value vs partial-filter literal asymmetry

**Severity:** Minor

**Files / lines:**
- `backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java:125`
- `backend/src/main/java/com/botfunnel/webhook/RawUpdate.java:54-57`

**Observation:** `handleFailure` writes the FAILED status via
`.set("processingStatus", RawUpdateStatus.FAILED.name())` (String literal,
line 125) — defended by the `RawUpdate.java:27-37` static class-load assertion
that pins enum names byte-identical with the partial-filter literals
`'PENDING'`/`'DONE'`. Note that `processingStatus` writes elsewhere in the job
(line 104) use the enum form `RawUpdate.setProcessingStatus(RawUpdateStatus.DONE)`
which Spring Data persists via `name()` — same outcome, different code shape.
The class-load assertion correctly defends both call sites against an enum
rename.

**Recommendation:** none for merge. As a follow-up commit, consider
standardising on the typed setter (`new Update().set("processingStatus",
RawUpdateStatus.FAILED)` — Spring Data converts via `name()` at write time) so
the two call sites read identical. The current code is correct; this is a
readability nit only.

### Finding 4 — Two non-public test seam methods on the controller

**Severity:** Minor

**Files / lines:**
- `backend/src/main/java/com/botfunnel/webhook/TelegramWebhookController.java:70, 84-85, 99-102`

**Observation:** the controller defines a package-private
`BiConsumer<UUID, String> enqueuer` field (line 70) and a package-private
`setEnqueuer` method (line 100) so a self-heal IT can inject a failing
enqueue. The pattern is documented inline at lines 64-69 with clear
justification (multi-Spring-context tests would silently target the wrong
`StorageProvider` if the controller used the static `BackgroundJob.enqueue`
API). Two visible cost items:
1. Constructor default-binds the seam on every instance, including production
   (line 84-85) — extra `BiConsumer` allocation per controller bean (one-time,
   inconsequential).
2. The seam adds two non-final touchable surfaces on a production class.

**Recommendation:** none for merge — the documented justification stands and
the alternative (static-API enqueue) re-introduces the multi-context StorageProvider
bug the seam fixes. As a long-term follow-up, consider extracting an
`@Component WebhookEnqueuer` abstraction that wraps `JobScheduler.enqueue(UUID, lambda)`;
the IT then `@MockitoBean`s the abstraction without touching the controller. Out
of scope for 04b.

### Finding 5 — `dispatch(...)` method length

**Severity:** Minor

**Files / lines:**
- `backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java:146-198`

**Observation:** `dispatch(...)` is 53 lines (52 inclusive of signature). Just
over the 50-line "extract helper" rule of thumb in the code-reviewing skill.
The structure is reasonable: it is a top-level routing matrix (`message == null`
→ other; `text == null` → other; `/`-prefix → command parse + switch;
private-chat plain text → upsert subscriber + event; non-private plain text →
event only), and each branch already delegates to a named helper. Splitting
further would require either passing dispatcher state through extra parameters
or hoisting the matrix to a tiny strategy table.

**Recommendation:** none for merge. If a future epic adds more update kinds,
consider a `Map<Class<?>, Handler>` strategy — but a 5-branch switch does not
clear the readability bar for that refactor yet.

### Finding 6 — `received_total` per-request `Counter.builder` allocation

**Severity:** Minor

**Files / lines:**
- `backend/src/main/java/com/botfunnel/webhook/TelegramWebhookController.java:154-155, 56-58`

**Observation:** the success counter is incremented per request via
`meterRegistry.counter(RECEIVED_TOTAL, "projectId", projectId).increment()` — a
fresh `Tag.of(...)` array allocation per call, by design (the inline comment at
lines 56-58 explicitly justifies the per-request shape because the tag varies
per `projectId`). `SimpleMeterRegistry` caches and returns the same `Counter`
instance for identical tag sets, so the only on-hot-path cost is the
varargs+`Tags.of` allocation. At Telegram webhook volume (1 RPS-class for a
single bot) this is inconsequential; under hypothetical 1 kRPS it becomes
visible.

**Recommendation:** none for merge. Documented design choice; allocations are
small and short-lived. If Epic 09 turns on a Prometheus scrape and traffic
grows, consider a `ConcurrentHashMap<projectId, Counter>` cache. Out of scope
here.

### Finding 7 — `@SpringBootTest` slice test naming (`*Test` not `*IT`)

**Severity:** Info

**Files / lines:**
- `backend/src/test/java/com/botfunnel/webhook/WebhookSecurityBlockTest.java:26-29`
- Precedent: `backend/src/test/java/com/botfunnel/SecurityBlockTest.java`

**Observation:** `WebhookSecurityBlockTest` uses `@SpringBootTest(webEnvironment
= RANDOM_PORT)` + `@MockitoBean MongoClient` / `RedisConnectionFactory` to
exercise the security chain without a live Mongo/Redis container, and is named
`*Test` (not `*IT`). The project convention (per `TelegramWebhookControllerIT`,
`BotControllerIT`, etc.) is `*IT` for Testcontainer-backed full integration
tests. The new test follows the existing `SecurityBlockTest` precedent for
"security-chain slice with mocked downstreams" — explicitly noted in the file's
class comment at lines 23-25.

**Recommendation:** none. The naming is consistent with prior art and the test
genuinely is a slice, not a true IT. Documenting here so the test-audit (T14)
does not re-flag it.

### Finding 8 — `MeterRegistryConfig` `@ConditionalOnMissingBean` semantics

**Severity:** Info

**Files / lines:**
- `backend/src/main/java/com/botfunnel/common/metrics/MeterRegistryConfig.java:24-28`

**Observation:** the bean is annotated `@Bean @ConditionalOnMissingBean` so a
future upgrade that adds `spring-boot-starter-actuator` (and thus
auto-configures a `MeterRegistry`) silently steps aside. Per `decisions.md`
task 1 entry, adding `micrometer-core` activates JobRunr's
`JobRunrMetricsAutoConfiguration`, which carries class-literal references to
actuator classes that ship in `spring-boot-actuator-autoconfigure` and triggers
`ClassNotFoundException` at boot unless excluded. The exclusion is registered
on `@SpringBootApplication` (string-form, never resolved). This pairing
(`@ConditionalOnMissingBean` here + string-form exclusion at the app entry) is
deliberate and matches the Decision 1 deviation note.

**Recommendation:** none. Worth recording so future readers see the two pieces
as a single design and do not "clean up" the exclusion in isolation.

## Summary

**Findings totals:** 0 Critical, 0 Major, 4 Minor, 4 Info. Cross-component grep
guards (sole-owner of `MessageDigest.getInstance`, sole-owner of
`payload_too_large` counter, zero `BackgroundJob.enqueue`/`BackgroundJob.<` in
webhook package, worker imports from both `subscriber` and `funnel`, no `_stubs/`
references, no `TODO`/`FIXME` markers, single `MeterRegistry` bean producer)
all pass. Every Decision 1–19 maps to a real implementation site and a test
guard. **Recommendation: GO for merge.**
