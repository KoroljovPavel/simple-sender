# Test Audit — 08-webhook-ingestion

**Audit date:** 2026-05-17
**Auditor:** test-master (Task 14)
**Latest commit on `main`:** `5083546` — "fix: address review round 1 for tasks 10 + 11"
**Scope:** all 04b test files under `backend/src/test/java/com/botfunnel/webhook/`, `com/botfunnel/common/crypto/`, `com/botfunnel/common/metrics/`, `com/botfunnel/subscriber/`, `com/botfunnel/funnel/`, plus regression-coverage check on `BotControllerIT`, `TelegramSenderIT`, `ProjectControllerIT`, `SecurityBlockTest`.
**Source of truth:** `work/08-webhook-ingestion/user-spec.md` (AC1–AC19 + AC11a) and `work/08-webhook-ingestion/tech-spec.md` (Testing Strategy + Acceptance Criteria sections).

---

## 1. User-spec AC → Test Mapping

Each row identifies a test that would fail if the AC was removed from production code (litmus test). `runbook-only` = manual-checklist-verified per task T8.

| AC | Description (short) | Mapped test |
|----|--------------------|-------------|
| AC1 | 200 OK happy-path + P99 < 100 ms @ 100 parallel | `TelegramWebhookControllerIT::receive_happyPath_returns200_persistsAndEnqueues`, `TelegramWebhookControllerIT::p99Latency_under100msAt100ParallelRequests` (Tag=slow) |
| AC2 | Bad/missing secret → 401, empty body | `TelegramWebhookControllerIT::receive_invalidSecret_returns401EmptyBody_counterTicked` (3 sub-cases: wrong / empty / missing header); `TelegramWebhookControllerIT::receive_anotherBotsSecret_returns401`; `WebhookSecretVerifierTest::verify_nonMatchingSecret_returnsFalse` (unit) |
| AC3 | Missing / soft-deleted / disconnected / malformed-ObjectId projectId → 404 | `TelegramWebhookControllerIT::receive_missingProject_returns404EmptyBody_counterTicked`, `receive_softDeletedProject_returns404`, `receive_disconnectedBot_returns404`, `receive_malformedObjectId_returns404` |
| AC4 | Payload > 1 MB → 413 (scoped) | `WebhookPayloadSizeFilterTest::oversizedContentLength_rejects413_incrementsCounter`, `chunkedEncoding_rejects413_incrementsCounter`, `missingContentLength_rejects413_incrementsCounter`, `contentLengthAtBoundary_passesThrough`, `apiPathWithChunkedEncoding_passesThrough`, `multiSegmentWebhookPath_doesNotMatch`, `zeroContentLength_passesThrough`. **Filter-unit only — no IT covers AC4** (see Finding F4). |
| AC5 | Same `(projectId, updateId)` parallel → 1 row, 1 job, all 200 | `TelegramWebhookControllerIT::receive_duplicateUpdateId_returns200_singleRowSingleJob` (parallel size=2), `receive_enqueueFailsThenRetries_selfHealsToSingleJob` (Decision 4/11 self-heal), `RawUpdateRepositoryTest::duplicateProjectIdUpdateId_throwsDuplicateKey`, `RawUpdateRepositoryTest::compoundIndex_isUnique` |
| AC6 | `/start` private + `ownerChatId=null` → atomic write; second `/start` from another chat → NO overwrite | `ProcessTelegramUpdateJobTest::ownerChatIdPopulate_firstStart_atomicWrite`, `ownerChatIdPopulate_secondStartDifferentChat_doesNotOverwrite`; controller-side enqueue: `TelegramWebhookControllerIT::receive_happyPath_returns200_persistsAndEnqueues` |
| AC7 | `/start` parser variants (payload / no-payload / multi-word / `@BotName` suffix) | `ProcessTelegramUpdateJobTest::startPrivateWithPayload_callsSubscriberAndFunnel_writesEvent`, `startPrivateNoPayload_startPayloadIsEmpty`, `startWithBotSuffix_payloadExtracted`, `startPrivateMultiWordPayload_joinedAfterFirstWhitespace`; parser unit coverage: `TelegramCommandParserTest` (20 tests covering /start, /start payload, /start@SomeBot, /start@SomeBot ref_X, /, empty, null, leading whitespace, case-sensitivity, multi-space, newline, tab, long payload, `@@` edge) |
| AC8 | `/stop` private → stubs + `telegram_command_stop` event | `ProcessTelegramUpdateJobTest::stopPrivate_callsMarkUnsubscribedAndCancel_writesEvent`, `stopGroup_eventOnly_noStubCall`; parser unit: `TelegramCommandParserTest::parse_stop_returnsStopEmpty`, `parse_stopWithBotSuffix_returnsStopEmpty` |
| AC9 | `/start` in group → event, no `ownerChatId` populate, no Subscriber upsert | `ProcessTelegramUpdateJobTest::startGroup_eventOnly_noStubsNoOwnerPopulate` |
| AC10 | Plain text private → Subscriber upsert + `telegram_message_received`; non-private → event only | `ProcessTelegramUpdateJobTest::plainTextPrivate_callsSubscriber_writesMessageReceivedEvent`, `plainTextGroup_eventOnly_noStubCall` |
| AC11 | Non-message updates → `telegram_update_other` with `updateKind` | `ProcessTelegramUpdateJobTest::nonMessageJsonNodeSlot_writesUpdateOther_noStubs` (parameterized over 7 slots), `typedMessageSlot_writesUpdateOther_noStubs` (3 slots), `trulyUnknownUpdate_updateKindUnknown`, `mediaOnlyMessagePrivate_updateKindMessage_noStubs` |
| AC11a | `message == null` OR `message.from == null` → safe-navigate, no NPE, `telegram_update_other` | `ProcessTelegramUpdateJobTest::messageNullAndNoSlot_safeNavigate`, `mediaOnlyMessagePrivate_updateKindMessage_noStubs`; deserializer-level: `TelegramUpdateDeserializationTest::deserialize_channelPostNoFrom_messageFromIsNull`, `deserialize_missingMessage_messageFieldIsNull` |
| AC12 | Unknown command `/foo bar` → `telegram_message_received`, no stubs | `ProcessTelegramUpdateJobTest::unknownCommandPrivate_telegramMessageReceivedEvent_noStubs`, `unknownCommandGroup_telegramMessageReceivedEvent_noStubs` |
| AC13 | TTL on `createdAt` 90d native + partialFilter UPPERCASE `[PENDING, DONE]`; FAILED rows not auto-deleted | `RawUpdateRepositoryTest::ttlIndex_hasExpireAfter90Days`, `ttlIndex_partialFilterIsUppercase`. **Note:** "FAILED rows persist past TTL" invariant from tech-spec line 377 is NOT covered by a row-aging test (timing-sensitive; documented as manual-procedure in tech-spec). The partial-filter index assertion is the implementation proxy. See Finding F5. |
| AC14 | Worker exception → `processingStatus=FAILED` + scrubbed `processingError` + failure counter ticked; retry via JobRunr | `ProcessTelegramUpdateJobTest::workerException_writesFailedStatus_scrubbedTruncated_incrementsFailureCounter_rethrows`. **Note:** tech-spec mentions follow-up "spy `JobRunr StorageProvider` to confirm retry actually fires (verify `getJobs(FAILED)` after exhaustion)" — not implemented. See Finding F6. |
| AC15 | SecurityConfig `/webhooks/telegram/**` permitAll + scoped CSRF disable; `/api/**` still auth + CSRF | `TelegramWebhookControllerIT::api_csrfRegression_postWithoutXsrfToken_rejected`, `WebhookSecurityBlockTest::postWebhookWithoutAuth_reachesController_not403Csrf`, `postApiWithoutAuth_returns401`, `postApiAuthedWithoutXsrfToken_returns403_csrfBaselineStillActive`, `postWebhookWithoutXsrfToken_doesNot403_scopedCsrfDisableActive` |
| AC16 | Observability counters (5 in spec: received, duration timer, rejected x 4, worker outcome x 2) | `TelegramWebhookControllerIT::countersAfterMixedTraffic_exactValues` (received + 3 rejection reasons exact-value), `receive_invalidSecret_returns401EmptyBody_counterTicked` (invalid_secret), `receive_missingProject_returns404EmptyBody_counterTicked` (project_not_found), `receive_duplicateUpdateId_returns200_singleRowSingleJob`+`receive_enqueueFailsThenRetries_selfHealsToSingleJob` (duplicate), `WebhookPayloadSizeFilterTest::*` (payload_too_large), `ProcessTelegramUpdateJobTest::workerException_writesFailedStatus_*` (worker outcome failure tick). **Gaps:** `telegram_webhook_duration_seconds` Timer count/total never asserted; `telegram_worker_outcome_total{outcome=success}` increment never explicitly asserted on a happy-path worker test (only "did not tick" on failure path). See Finding F7. |
| AC17 | `Bot.ownerChatId` Java-field-only; legacy doc reads as `null` | `TelegramSenderIT::sendTestMessage_legacyDocumentReadsAsNull` (regression test from 04c, still active and unmodified) |
| AC18 | Token-scrubber on every webhook + worker log site; ListAppender per-site | Controller: `TelegramWebhookControllerIT::warnLogOnDuplicate_noTokenInOutput_andNoPayloadEcho` (filtered by `getLoggerName()=...TelegramWebhookController` AND `Level=WARN`), `errorLogOnEnqueueFail_noTokenInOutput` (same logger + `Level=ERROR`). Worker: `ProcessTelegramUpdateJobTest::workerException_writesFailedStatus_*` (asserts `jobAppender.list` allSatisfy no-token-regex). Verifier: `WebhookSecretVerifierTest::verify_anyOutcome_neverLogsHeaderOrStoredHash` (operand-content guard). **Gap:** `WebhookPayloadSizeFilter` WARN log site (line 87 of filter) emits scrubbed payload-derived strings BUT no test asserts the scrubber actually fires there — `WebhookPayloadSizeFilterTest` checks status + counter only, not the log line. See Finding F1. **Also gap:** worker `INFO` log sites (start, success, ownerChatId populate, bot lookup missed WARN) have only ONE generic `allSatisfy(no token regex)` assertion in the failure-path test — no per-INFO-site `ListAppender`-filtered assertion. See Finding F2. |
| AC19 | Typed `TelegramUpdate` record + nested records, snake_case, `@JsonIgnoreProperties` | `TelegramUpdateDeserializationTest` (10 tests: full message, unknown top-level field, unknown nested field, missing message, channel_post no `from`, malformed JSON, empty object → all nulls, `update_id` String → MismatchedInputException, callback_query JsonNode slot, my_chat_member slot) |

**Coverage assessment:** 19 of 20 ACs have at least one mapping that would fail on AC removal. AC4 is filter-unit-only without an end-to-end IT (F4); AC13's "FAILED row persists past TTL" sub-invariant is a manual-procedure check; AC16 is partially incomplete (duration timer never asserted, worker-success-tick never asserted on happy path); AC18 has one un-tested log site (filter WARN).

---

## 2. Tech-Spec Acceptance Criteria → Test / Grep-Invariant Mapping

Tech-spec `Acceptance Criteria` section (lines 441–453):

| Tech-spec bullet | Mapped test or grep invariant |
|------------------|-------------------------------|
| All user-spec AC1–AC19 pass | See section 1 above. |
| `db.raw_updates.getIndexes()` shows `_id_`, `projectId_updateId_unique` (unique), `projectId_1`, `ttl_createdAt` with `expireAfterSeconds=7776000` AND `partialFilterExpression { processingStatus: { $in: ['PENDING','DONE'] } }` UPPERCASE | `RawUpdateRepositoryTest::compoundIndex_isUnique`, `ttlIndex_hasExpireAfter90Days`, `ttlIndex_partialFilterIsUppercase`, `projectIdIndex_existsSeparately` — all four indexes verified programmatically against live Testcontainer Mongo. |
| No regressions in `BotControllerIT`, `TelegramSenderIT`, `ProjectControllerIT`, `SecurityBlockTest` after `Sha256Hex` refactor | **Verified by inspection:** none of these files were modified during 04b (`git log --since=3w` on each: only edits are from older epics 04a/04c/03/02 — last touch on `BotControllerIT.java` was `91c6842 feat: task 8 — BotController + DTOs + integration tests`, well before 04b started). The `Sha256Hex` refactor (commit `4bc7c52`) modified only `BotService.java`, `TokenService.java`, and added new `Sha256Hex.java` + `WebhookSecretVerifier.java` — no test sources were edited. `TelegramSenderIT::sendTestMessage_legacyDocumentReadsAsNull` exists and is enabled (sole non-`@Disabled` `Bot.ownerChatId`-related test). The two `@Disabled` entries in `TelegramSenderIT` (lines 160, 169) are pre-04b annotations carried over from epic 04c task plan — confirmed via `git blame`-style log, not introduced or modified by 04b. |
| `./gradlew test` — all unit + integration tests pass | Confirmed by Wave 1–4 per-task verification rounds (each task's review-2 closed green before merge; commits `e80baf1`, `2a0335d`, `d334b58`, `8312762`, `7dea05d`, `66c0f22`, `b1c10c5`, `1449c04`, `5083546`). Not re-run during this audit per task T14 scope (T15 is pre-deploy QA). |
| gitleaks pre-commit hook passes | Confirmed by clean commit chain (all 04b commits landed on `main`; gitleaks runs in pre-commit hook per `CLAUDE.md` policy). |
| `SecurityConfig.pathMatchers("/webhooks/telegram/{projectId}").permitAll()` BEFORE `/api/**.authenticated()` | Behavioral coverage: `WebhookSecurityBlockTest::postWebhookWithoutAuth_reachesController_not403Csrf` (webhook NOT rejected by CSRF) + `postApiWithoutAuth_returns401` (api still auth-required). Source-order-grep invariant is implicit: if the order were swapped, those two tests would invert their statuses. |
| `Sha256Hex.hex(...)` is the sole SHA-256 implementation in `backend/src/main/java/` (grep) | **Grep invariant** (verified during audit): `grep -rE "MessageDigest.getInstance.\"SHA-256\"" backend/src/main/java/` → returns ONLY `backend/src/main/java/com/botfunnel/common/crypto/Sha256Hex.java`. All other callers (`BotService.java:149`, `TokenService.java:22`, `WebhookSecretVerifier.java:31`) call `Sha256Hex.hex(...)`. Pinned by `Sha256HexTest::hex_knownInput_matchesProducerSideVector` (known-vector regression). |
| Webhook handler returns `Mono<ResponseEntity<Void>>` for 200/401/404 — never via `GlobalErrorHandler`. `WebhookPayloadSizeFilter` writes 413 directly | Verified by `TelegramWebhookControllerIT::receive_invalidSecret_returns401EmptyBody_counterTicked` (`.expectBody().isEmpty()`), `receive_missingProject_returns404EmptyBody_counterTicked` (idem), happy-path empty body — `GlobalErrorHandler` always writes a `{"code":...}` body, so empty-body assertions falsify the negative. Filter: `WebhookPayloadSizeFilterTest` writes the 413 directly via `exchange.getResponse()`. |
| `payload_too_large` counter exclusively owned by filter (grep) | **Grep invariant** (verified during audit): `grep -rE "payload_too_large" backend/src/main/java/com/botfunnel/webhook/` → ONLY `WebhookPayloadSizeFilter.java` (counter name) and an explanatory comment in `TelegramWebhookController.java` ("filter owns payload_too_large"). No controller code increments it. |

---

## 3. Scope-Item Verifications and Findings

For each of the 12 scope items from `tasks/14.md`:

### Item 1 — AC1–AC19 mapping
**Verified.** See section 1. All 20 ACs (AC1–AC19 + AC11a) have at least one mapped test. AC4 mapping is filter-unit-only (Finding F4). AC13 has no test for "FAILED row survives TTL" (Finding F5). AC16 has 2 sub-gaps (Finding F7). AC18 has 1 missing log-site assertion (Finding F1).

### Item 2 — Tech-spec Acceptance Criteria mapping
**Verified.** See section 2. All 9 tech-spec bullets resolve to either a test class or a grep invariant. No critical gap.

### Item 3 — Meaningful assertions (litmus test)
**Verified.** Spot-checked 12 representative tests:

- `TelegramWebhookControllerIT::receive_happyPath_returns200_persistsAndEnqueues` — asserts row count, ENQUEUED count, counter value (not "did not throw"). PASS.
- `TelegramWebhookControllerIT::receive_invalidSecret_returns401EmptyBody_counterTicked` — asserts 401, empty body, counter==3 across 3 sub-cases, NO row written, NO enqueue. PASS.
- `TelegramWebhookControllerIT::receive_duplicateUpdateId_returns200_singleRowSingleJob` — asserts pair of 200s, exact-count=1 row, exact-count=1 ENQUEUED. PASS.
- `TelegramWebhookControllerIT::p99Latency_under100msAt100ParallelRequests` — asserts 100 samples sorted, P99 (index 98) in ms `< 100`. PASS.
- `ProcessTelegramUpdateJobTest::reentryGuard_doneStatus_noOp` — asserts event-count==0, stubs `never()`, counter deltas==0, raw status STAYS `DONE`. PASS.
- `ProcessTelegramUpdateJobTest::ownerChatIdPopulate_secondStartDifferentChat_doesNotOverwrite` — asserts `ownerChatId == 100L` after second `/start` from chat 200. PASS.
- `ProcessTelegramUpdateJobTest::workerException_writesFailedStatus_*` — asserts status==FAILED, processingError scrubbed + length ≤ 1024, rethrown message scrubbed + length ≤ 1024 + no cause chain, jobAppender no-token-regex, failure counter delta==1, success counter unchanged. PASS — strongest assertion bundle in the suite.
- `RawUpdateRepositoryTest::ttlIndex_partialFilterIsUppercase` — parses partial-filter JSON, asserts `$in` list `containsExactlyInAnyOrder("PENDING", "DONE")`. PASS.
- `WebhookSecretVerifierTest::verify_usesMessageDigestIsEqual` — uses `MockedStatic<MessageDigest>` to assert constant-time primitive is called exactly once. PASS — defends against an `equals(String)` regression.
- `Sha256HexTest::hex_knownInput_matchesProducerSideVector` — pins a known-external SHA-256 vector. Removing the hash implementation would fail. PASS.
- `TelegramCommandParserTest::parse_payloadWithEmbeddedNewline_preservesPayloadContent` — asserts payload value `"ref_X\nline2"` round-trips. PASS — non-trivial regression guard.
- `WebhookPayloadSizeFilterTest::oversizedContentLength_rejects413_incrementsCounter` — asserts status 413 + downstream chain not invoked + counter==1. PASS.

**No "does not throw" assertions found.** No `mono.isNotNull()` followed by no further assertion. No mock-return tautologies (`spy.foo.mockReturnValue(x); assertThat(spy.foo()).isEqualTo(x)`).

The `NoOpSubscriberServiceTest` and `NoOpFunnelTriggerServiceTest` are mostly contract-verification tests (`Mono.empty()` completes) — they have weak business value because the stubs intentionally do nothing. But they DO assert observable behavior (`expectComplete()` with deterministic time bound, no exception, no side effect), which is the documented contract for these stubs in Epic 05/06 hand-off. See minor Finding F8.

### Item 4 — `ListAppender` per-site filtering (AC18)
**Verified with one gap.** Inventory of log-site assertions:

| Log site (source) | Test that filters by `Logger.getName()` AND `Level` |
|---|---|
| `TelegramWebhookController` WARN (duplicate update_id branch) | `TelegramWebhookControllerIT::warnLogOnDuplicate_noTokenInOutput_andNoPayloadEcho` — filters `getLoggerName() == "com.botfunnel.webhook.TelegramWebhookController"` AND `getLevel() == Level.WARN`. Two-part assertion: no token-shape AND no payload sentinel. Strongest log-site test in the suite. |
| `TelegramWebhookController` WARN (missing update_id) | Same filter as above also catches it (line-140 site emits WARN with `scrubTokens(projectId)`). |
| `TelegramWebhookController` ERROR (enqueue failure) | `TelegramWebhookControllerIT::errorLogOnEnqueueFail_noTokenInOutput` — filters same logger AND `getLevel() == Level.ERROR`. PASS. |
| `WebhookPayloadSizeFilter` WARN (413 rejection, line 87) | **NO per-site ListAppender test.** `WebhookPayloadSizeFilterTest` covers status+counter but does not attach a `ListAppender<ILoggingEvent>` to `org.slf4j.LoggerFactory.getLogger(WebhookPayloadSizeFilter.class)`. If `TelegramApiClient.scrubTokens(...)` were removed from line 88, no test would fail. See Finding F1. |
| `ProcessTelegramUpdateJob` WARN (`rawUpdate not found`, line 84) | Caught by `allSatisfy(no token regex)` on `jobAppender.list` inside `workerException_writesFailedStatus_*` AND by `startPrivateBotMissing_logsWarnAndWritesUpdateOther` (asserts the WARN line is present BUT does NOT assert scrubbing). |
| `ProcessTelegramUpdateJob` INFO (worker start, line 96) | Only covered by the catch-all `jobAppender.list allSatisfy(no token regex)` in `workerException_writesFailedStatus_*` — not a per-site filter (no `Level==INFO` filter). See Finding F2. |
| `ProcessTelegramUpdateJob` INFO (worker success, line 107) | Same as above. |
| `ProcessTelegramUpdateJob` ERROR (worker failure, line 129) | Covered: `workerException_writesFailedStatus_*` asserts `jobAppender.list allSatisfy(no token regex)` — token regex is the strong invariant for the AC. PASS (single-test coverage, not split per-level). |
| `ProcessTelegramUpdateJob` WARN (bot lookup missed in `/start` handler, line 211) | `startPrivateBotMissing_logsWarnAndWritesUpdateOther` asserts WARN emission and message contains "bot lookup missed" — but does NOT assert no-token-regex on this specific line. Indirectly covered by failure-path catch-all. See Finding F2. |
| `ProcessTelegramUpdateJob` INFO (ownerChatId populated, line 229) | Only catch-all from failure path. Source line wraps `chatId` in `TelegramApiClient.scrubTokens(String.valueOf(chatId))` — defense-in-depth. See Finding F2. |
| `WebhookSecretVerifier` (any log level) | `WebhookSecretVerifierTest::verify_anyOutcome_neverLogsHeaderOrStoredHash` — sweeps 6 calls across match/mismatch/empty/null/malformed, attaches ROOT logger appender at TRACE level, asserts NO captured event contains plaintext / wrong-header / storedHash / malformed string. Strong negative log-leak guard. |

**Conclusion:** Controller log sites have per-site filters with both `Logger.getName()` AND `Level` filtering. Worker log sites have ONE generic catch-all assertion in the failure-path test plus targeted message-content assertions per site; this is weaker than per-site filtering but still catches token leaks. `WebhookPayloadSizeFilter` log site has NO ListAppender coverage at all (Finding F1).

### Item 5 — `@MockitoSpyBean` usage
**Verified.** Two spies in `ProcessTelegramUpdateJobTest`:

- `@MockitoSpyBean SubscriberService subscriberService` (line 61) — spies the `NoOpSubscriberService` impl. Real `Mono.empty()` returns flow through, mock count assertions (`verify(...).times(1)`, `verify(...).never()`) drive the test. **Fine** — service is a stub with no DB I/O.
- `@MockitoSpyBean FunnelTriggerService funnelTriggerService` (line 62) — spies the `NoOpFunnelTriggerService` impl. Same shape. **Fine.**

NO spy on `BotRepository`, `RawUpdateRepository`, `EventRepository`, `EventService`. All repo + event paths use the real Testcontainer Mongo. PASS — no fragile repository-spy anti-pattern.

`Mockito.doThrow(...).when(subscriberService).upsertFromTelegramUpdate(...)` (line 392-394) is used to inject the worker-exception failure scenario — legitimate spy use for exercising a code path that the no-op stub cannot reach naturally.

### Item 6 — Race-test correctness
**Verified with caveats.**

- **AC5 race-test (`receive_duplicateUpdateId_returns200_singleRowSingleJob`, lines 279–297):** uses `Flux.range(0, 2).parallel(2).runOn(Schedulers.boundedElastic()).flatMap(...).sequential().collectList().block()`. Truly parallel (not sequential `for`). Boundedness is correct — `runOn(boundedElastic())` is required because `WebTestClient.bindToApplicationContext` serializes by default (commented in source line 283-286). **PASS.**

- **AC6 no-overwrite test (`ownerChatIdPopulate_secondStartDifferentChat_doesNotOverwrite`, lines 153–170):** runs worker TWICE on different chat ids (`100L` then `200L`), asserts `ownerChatId == 100L` after both. This is sequential, NOT parallel — but that is correct for testing the atomic predicate (`ownerChatId == null` fails on the second call, so the second `findAndModify` is a no-op). A parallel-race test would still need a deterministic "first wins" ordering, which the integration test cannot easily guarantee. **PASS.**

- **No `Thread.sleep` in race tests.** `awaitEnqueuedCount(...)` (line 151) uses `Awaitility.await().atMost(5s).pollInterval(20ms).untilAsserted(...)`. The latency test uses real timing (`System.nanoTime()`) for measurement, not for synchronization. **PASS.**

### Item 7 — No flaky `Thread.sleep`
**Verified.** `grep -rE "Thread\.sleep" backend/src/test/java/com/botfunnel/webhook/ backend/src/test/java/com/botfunnel/common/{crypto,metrics}/ backend/src/test/java/com/botfunnel/{subscriber,funnel}/` returns ZERO matches. All synchronisation uses `Awaitility` (`TelegramWebhookControllerIT::awaitEnqueuedCount`) or `StepVerifier.verify(Duration.ofMillis(100))` (stub tests). **PASS.**

### Item 8 — Re-entry guard test
**Verified.** `ProcessTelegramUpdateJobTest::reentryGuard_doneStatus_noOp` (lines 111–137):
- Seeds `processingStatus=DONE` with a valid `/start ref_X` payload (which WOULD otherwise trigger stubs + event + counter).
- Asserts `eventRepository.count() == 0` after `job.handle(raw.getId())`.
- Asserts `subscriberService` and `funnelTriggerService` `never()` called.
- Asserts `successCount()` and `failureCount()` deltas == 0 (NO counter tick).
- Asserts `reloaded.getProcessingStatus() == DONE` (no mutation) AND `processingError == null` (no error).

**PASS — strongest re-entry test possible**, defends against an off-by-one early-return bug in the worker (e.g. a missing `if (status != PENDING) return` guard).

### Item 9 — TTL index regression test
**Verified.** `RawUpdateRepositoryTest::ttlIndex_partialFilterIsUppercase` (lines 76–93):
- Reads `IndexInfo.getPartialFilterExpression()` (the JSON string from `db.raw_updates.getIndexes()`).
- Parses with `Document.parse(pf)`.
- Extracts `processingStatus.$in` list.
- Asserts `containsExactlyInAnyOrder("PENDING", "DONE")` — UPPERCASE literals (regression guard for completeness F5).

Adjacent test `ttlIndex_hasExpireAfter90Days` (lines 63–74) asserts `Duration.ofDays(90)` from `getExpireAfter()`.

**PASS — regression for the FAILED-row-survives-TTL invariant is implicit in the partial-filter assertion** (FAILED is not in the `$in` list, so Mongo will not TTL-evict FAILED rows). The tech-spec line 377 "Insert FAILED + aged row, partial filter excludes, row persists" test is NOT implemented — but the partial-filter assertion proves the predicate cannot match, so the explicit row-aging test would be a high-cost / low-information addition. Listed as informational only (no separate finding).

### Item 10 — Counter NPE protection (AC16)
**Verified.** All `counter().count()` call sites are null-guarded:

- `TelegramWebhookControllerIT::counter(reason)` helper (lines 157–161): `Counter c = ...find(...).counter(); return c == null ? 0.0 : c.count();` — NPE-safe.
- `TelegramWebhookControllerIT::receive_happyPath_*` (lines 184–186): `Counter rec = ...find(...).counter(); assertThat(rec).isNotNull(); assertThat(rec.count()).isEqualTo(1.0);` — explicit `isNotNull()` precedes `.count()`. Pattern matches tech-spec line 349 prescription exactly.
- `TelegramWebhookControllerIT::countersAfterMixedTraffic_exactValues` (lines 403–405): same `Counter received = ...; assertThat(received).isNotNull(); assertThat(received.count())...` shape.
- `WebhookPayloadSizeFilterTest::counter()` (lines 34–38): `Counter c = ...find(...).counter(); return c == null ? 0.0 : c.count();` — NPE-safe.
- `MeterRegistryConfigTest::findUnknownMetric_returnsNullNotNpe` (lines 52–55): asserts the safe `null`-return pattern is the contract.
- `ProcessTelegramUpdateJobTest::successCount()` and `failureCount()` (lines 565–571): use `meterRegistry.counter(name, tag, value)` (NOT `find().counter()`) — this auto-creates the counter, so NEVER returns null. NPE-impossible by API choice. Acceptable but worth noting: this means `successCount()` returns 0 even if the worker never emits the counter (which is fine since assertions use deltas).

**PASS.** No `counter.count()` call lacks null protection.

### Item 11 — Test pyramid balance
**Verified.** Counts (each `@ParameterizedTest` counted as 1 test method, even though it expands to N invocations):

| File | Test count | Category |
|------|-----------|----------|
| `TelegramCommandParserTest` | 20 | Unit |
| `WebhookSecretVerifierTest` | 9 | Unit |
| `WebhookPayloadSizeFilterTest` | 7 | Unit |
| `TelegramUpdateDeserializationTest` (dto/) | 10 | Unit |
| `Sha256HexTest` | 4 | Unit |
| `NoOpSubscriberServiceTest` | 3 | Unit |
| `NoOpFunnelTriggerServiceTest` | 4 | Unit |
| **Unit total** | **57** | |
| `ProcessTelegramUpdateJobTest` | 21 (incl. 2 parameterized × 7 + × 3 = 28 effective) | Integration |
| `TelegramWebhookControllerIT` | 14 | Integration |
| `RawUpdateRepositoryTest` | 6 | Integration |
| `WebhookSecurityBlockTest` | 4 | Integration (slice) |
| `MeterRegistryConfigTest` | 2 | Integration (slice) |
| **Integration total** | **47** | |

Ratio: 57/47 ≈ 55/45 unit/integration. **Healthy.** No E2E in 04b (deliberate — `docs/staging-smoke/08-webhook-ingestion.md` is the manual runbook per tech-spec line 382). Pyramid is well-shaped: most pure-logic tests (parser, hex, DTO) are unit; security-critical paths (controller, worker, repo) are integration with real Mongo. **PASS.**

### Item 12 — Existing-test regressions
**Verified.** `git log --since="3 weeks ago"` on each of the four regression files returns NO 04b-era modifications:

- `backend/src/test/java/com/botfunnel/SecurityBlockTest.java` — last touched in `f5cba44` (Project 07, task 7) and `fbc8b69` (Project 02, task 2). Not modified during 04b.
- `backend/src/test/java/com/botfunnel/bot/BotControllerIT.java` — last touched in `91c6842` (Project 04a, task 8). Not modified during 04b.
- `backend/src/test/java/com/botfunnel/bot/TelegramSenderIT.java` — last touched in `3282b95` (Project 04c, task 5 fix). Not modified during 04b. The two `@Disabled` annotations (lines 160, 169) are pre-existing from the 04c task plan, NOT introduced by 04b.
- `backend/src/test/java/com/botfunnel/project/ProjectControllerIT.java` — last touched in `4c85960` (Project 03, task 3 fix). Not modified during 04b.

The Sha256Hex refactor (commit `4bc7c52`) modified only `BotService.java`, `TokenService.java`, and added new source files — NO test source was edited. The regression tests carry the pre-refactor behavioral contract forward, so any production-side break would surface here. **PASS.**

---

## 4. Findings (sorted by severity descending)

### Major

#### F1 — `WebhookPayloadSizeFilter` WARN log site has no per-site ListAppender token-leak test
- **Location:** `backend/src/test/java/com/botfunnel/webhook/WebhookPayloadSizeFilterTest.java` (no test attaches a ListAppender to `WebhookPayloadSizeFilter.class` logger).
- **Observation:** Tech-spec line 415–425 enumerates `WebhookPayloadSizeFilter` WARN (line 87 in source) as a token-scrubber site. Source line 88-91 wraps `projectId` and `transferEncoding` in `TelegramApiClient.scrubTokens(...)`. The filter unit test checks status (413) and counter increment but never inspects the emitted log line. If a future refactor drops `scrubTokens(...)` from line 88, no test would fail. AC18 requires "every WARN/ERROR log site ... has its OWN `ListAppender`-filtered test ... by `Logger.getName()` AND `Level`".
- **Recommendation:** Add one test to `WebhookPayloadSizeFilterTest`:
  ```java
  @Test
  void warnOnReject_doesNotContainTokenShape() {
      Logger l = (Logger) LoggerFactory.getLogger(WebhookPayloadSizeFilter.class);
      ListAppender<ILoggingEvent> appender = new ListAppender<>();
      appender.start();
      l.addAppender(appender);
      try {
          String tokenShaped = "1234567890:ABCdefGHI_jklMNOpqrSTUvwxYZ0123456789xyz";
          MockServerWebExchange exchange = exchange(
              post("/webhooks/telegram/" + tokenShaped)
                  .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                  .build());
          StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();
          List<String> warnLines = appender.list.stream()
              .filter(e -> e.getLoggerName().equals(WebhookPayloadSizeFilter.class.getName()))
              .filter(e -> e.getLevel() == Level.WARN)
              .map(ILoggingEvent::getFormattedMessage)
              .toList();
          assertThat(warnLines).isNotEmpty();
          for (String line : warnLines) {
              assertThat(Pattern.compile("\\d{1,20}:[A-Za-z0-9_-]{30,50}").matcher(line).find())
                  .as("filter WARN must not contain token-shape: %s", line).isFalse();
          }
      } finally { l.detachAppender(appender); appender.stop(); }
  }
  ```

#### F4 — AC4 (413 payload-cap) has no end-to-end IT
- **Location:** `backend/src/test/java/com/botfunnel/webhook/TelegramWebhookControllerIT.java` (no AC4 test); tech-spec line 341 explicitly required three sub-cases here.
- **Observation:** AC4 is covered only by `WebhookPayloadSizeFilterTest` (a unit test invoking the filter via `MockServerWebExchange`). The filter must be (a) registered as a `WebFilter` bean, (b) ordered before the security chain (per Decision 13, `@Order(HIGHEST_PRECEDENCE + 10)`), and (c) actually invoked on `/webhooks/telegram/**` requests by the live WebFlux pipeline. None of these wiring invariants are exercised. If somebody removes the filter from the Spring config or shifts its `@Order` past the security filter, AC4 would silently break in production while the unit tests stay green. Tech-spec line 341 prescribes IT coverage: "AC4 — three sub-cases all → 413 empty body, `rejected_total{reason=payload_too_large}` increment verified per case".
- **Recommendation:** Add three tests to `TelegramWebhookControllerIT`:
  ```java
  @Test
  void receive_oversizedContentLength_returns413() {
      webTestClient.post().uri("/webhooks/telegram/507f1f77bcf86cd799439011")
          .header("X-Telegram-Bot-Api-Secret-Token", SECRET_PLAIN)
          .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(1_048_577))
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(samplePayload(1L))
          .exchange()
          .expectStatus().isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
          .expectBody().isEmpty();
      assertThat(counter("payload_too_large")).isEqualTo(1.0);
  }
  @Test void receive_chunkedEncoding_returns413() { /* same with Transfer-Encoding: chunked */ }
  @Test void receive_missingContentLength_returns413() { /* same without Content-Length */ }
  ```

#### F7 — AC16 partial coverage: duration Timer never asserted, worker `outcome=success` never explicitly asserted on happy path
- **Location:** `backend/src/test/java/com/botfunnel/webhook/TelegramWebhookControllerIT.java` + `ProcessTelegramUpdateJobTest.java`.
- **Observation:** AC16 enumerates 5 counter/timer names. Coverage:
  - `telegram_webhook_received_total` — verified exact-value (PASS).
  - `telegram_webhook_duration_seconds` (Timer) — never asserted. `meterRegistry.find("telegram_webhook_duration_seconds").timer()` could be null and no test would fail. The `p99Latency_under100msAt100ParallelRequests` test measures latency via `System.nanoTime()` outside the meter registry, not via the Timer.
  - `telegram_webhook_rejected_total{reason=...}` x 4 reasons — verified exact-value (PASS).
  - `telegram_worker_outcome_total{outcome=success}` — never asserted as incremented. `reentryGuard_doneStatus_noOp` asserts "did not increment", `workerException_*` asserts "did not increment" on failure path. NO test asserts `successCount() == beforeSuccess + 1` after a happy-path worker invocation.
  - `telegram_worker_outcome_total{outcome=failure}` — verified exact-delta (PASS).
- **Recommendation:**
  1. Add one assertion to `TelegramWebhookControllerIT::receive_happyPath_*` (or a new test):
     ```java
     io.micrometer.core.instrument.Timer t = meterRegistry.find("telegram_webhook_duration_seconds").timer();
     assertThat(t).isNotNull();
     assertThat(t.count()).isEqualTo(1L);
     ```
  2. Add one assertion to `ProcessTelegramUpdateJobTest::startPrivateWithPayload_*` (or any happy-path worker test):
     ```java
     assertThat(successCount() - beforeSuccess).isEqualTo(1L);  // capture beforeSuccess in @BeforeEach or at test start
     ```

### Minor

#### F2 — Worker per-INFO-site log assertions not split (AC18)
- **Location:** `backend/src/test/java/com/botfunnel/webhook/ProcessTelegramUpdateJobTest.java`. INFO log sites at `ProcessTelegramUpdateJob.java` lines 96, 107, 229 emit scrubbed payload-derived strings but no test attaches a `Level==INFO`-filtered `ListAppender` per site.
- **Observation:** The catch-all in `workerException_writesFailedStatus_*` (line 425-427) asserts `jobAppender.list allSatisfy(no token regex)` across ALL captured events at ALL levels, which DOES catch a token leak in any INFO line. However, this is one assertion across N sites — if a new INFO log site is added without `scrubTokens`, the regex check still catches the obvious token-shaped substring, but a payload-echo leak (without a token shape) would NOT be caught. The Controller IT pattern (split per WARN / per ERROR with payload-echo sentinel) is stronger.
- **Recommendation:** Add one test per INFO log site (start, success, ownerChatId populate) that injects a sentinel string into the request payload and asserts the sentinel does NOT appear in any INFO log line filtered by `getLoggerName() == ProcessTelegramUpdateJob.class.getName()` AND `getLevel() == Level.INFO`. Pattern: same as `warnLogOnDuplicate_noTokenInOutput_andNoPayloadEcho` in the controller IT.

#### F5 — AC13 "FAILED row persists past TTL" invariant has no test (only implementation-proxy)
- **Location:** `backend/src/test/java/com/botfunnel/webhook/RawUpdateRepositoryTest.java`.
- **Observation:** Tech-spec line 377 prescribes "Insert FAILED + aged row, partial filter excludes, row persists (AC13 invariant)" — but no test implements it. The `ttlIndex_partialFilterIsUppercase` test asserts the partial-filter shape, which is an implementation proxy: if the partial filter excludes FAILED, Mongo will not TTL-evict FAILED rows. This proxy is acceptable because the alternative is a timing-sensitive >90d-aging test that the tech-spec itself recommends skipping from CI.
- **Recommendation:** Informational only. Document the proxy in a one-line comment on `ttlIndex_partialFilterIsUppercase` test: "// AC13 'FAILED rows persist past TTL' invariant — implementation proxy: partial filter excludes FAILED, so Mongo TTL daemon cannot evict them. Direct row-aging test is timing-sensitive and skipped from CI per tech-spec L376." No new test required.

#### F6 — AC14 follow-up "JobRunr retry actually fires" not implemented
- **Location:** `backend/src/test/java/com/botfunnel/webhook/ProcessTelegramUpdateJobTest.java`.
- **Observation:** Tech-spec line 364 mentions "Integration follow-up: spy `JobRunr StorageProvider` to confirm retry actually fires (verify `getJobs(FAILED)` after exhaustion)". Not implemented. The current AC14 test (`workerException_*`) verifies the worker WRITES `processingStatus=FAILED` and RETHROWS — but does not verify JobRunr's `StateName.FAILED` queue picks it up.
- **Recommendation:** Either implement the follow-up (recommended — adds defense against a JobRunr config drift) OR document it as deferred to Epic 10 (when JobRunr dashboard is enabled). If implemented: add a test that invokes the worker via `jobScheduler.enqueue(...)`, then polls `storageProvider.getJobs(StateName.FAILED, ...)` with `Awaitility.until(...)`. Severity is Minor because (a) the rethrow assertion proves the failure surfaces to JobRunr, and (b) Epic 10 will make the queue user-visible.

#### F8 — NoOp stub tests verify "Mono.empty()" but the no-op contract is weak by design
- **Location:** `backend/src/test/java/com/botfunnel/subscriber/NoOpSubscriberServiceTest.java`, `backend/src/test/java/com/botfunnel/funnel/NoOpFunnelTriggerServiceTest.java`.
- **Observation:** All 7 tests across these two files assert `StepVerifier.expectComplete().verify(Duration.ofMillis(100))`. The stub returns `Mono.empty()` by design — Epic 05 and Epic 06 will replace these impls with real DB writes. The current tests verify (a) the stub returns within 100ms (i.e. is truly no-op), (b) accepts null/empty optional fields, (c) returns a non-null Mono. This is the documented "hand-off contract" for Epic 05/06 (Decision 14): "interface is final; impl gets replaced".
- **Recommendation:** Acceptable. Could be strengthened by adding a single assertion that the stub does NOT touch ANY repository (`assertThat(eventRepository.count().block()).isZero()` after a stub call) — but that's an integration test against the stub's no-op contract, which is overkill for a 7-line method. Leave as-is. Severity Minor for documentation clarity only.

#### F3 — AC6 "no overwrite" test seeds DIFFERENT `chat.id` but same Bot — does not test cross-Bot scenario
- **Location:** `backend/src/test/java/com/botfunnel/webhook/ProcessTelegramUpdateJobTest.java` lines 153–170.
- **Observation:** `ownerChatIdPopulate_secondStartDifferentChat_doesNotOverwrite` runs the worker twice against the SAME bot id with different chat ids (100L, 200L). This verifies the `findAndModify(predicate: ownerChatId==null)` semantics. It does NOT verify the no-overwrite holds across bots, but AC6 is scoped to a single bot (`ownerChatId` is a per-bot field), so the scope is correct. The test is well-shaped.
- **Recommendation:** Informational. The test is correct as written. No change needed.

---

## 5. Summary

**Totals:**
- Critical: 0
- Major: 3 (F1 — filter log-site scrubber test missing; F4 — AC4 IT missing; F7 — duration Timer + worker-success counter never asserted)
- Minor: 5 (F2 — worker INFO log per-site assertions; F3 — informational note on AC6 test scope; F5 — AC13 row-aging proxy; F6 — AC14 JobRunr retry follow-up; F8 — NoOp stub contract clarity)
- Info: 0

**Coverage:**
- 19/20 user-spec ACs fully covered; AC4 partially covered (filter unit only).
- All 9 tech-spec acceptance bullets covered (tests or grep invariants).
- 12/12 scope items have a Verified entry or Finding entry.
- Pyramid balance: 57 unit / 47 integration (≈ 55/45) — healthy.
- No `Thread.sleep` flakiness. No empty/mock-only/excessive-mocking tests. Litmus test passes on 12/12 spot-checked tests.

**Regression coverage:** `BotControllerIT`, `TelegramSenderIT`, `ProjectControllerIT`, `SecurityBlockTest` all present, enabled (modulo two pre-existing `@Disabled` lines in `TelegramSenderIT` carried over from Epic 04c), and not modified during 04b. `Sha256Hex` refactor changed only production sources, not tests.

**Go / No-Go recommendation:** **GO with conditions.** Zero Critical findings; the three Major findings are tractable test-only additions (no production-code changes), all under 30 lines of new test code each. F1 and F4 should be addressed before T15 pre-deploy QA — F1 closes the last AC18 log-site gap, F4 promotes AC4 from filter-unit to wired-IT. F7 can be addressed inline with F4 (add Timer + success-counter assertions in the same happy-path IT). F2/F3/F5/F6/F8 are documentation- or hand-off-clarity Minor findings and may be deferred or fixed during normal maintenance. No findings block deploy.
