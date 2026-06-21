# Decisions Log: 08-webhook-ingestion

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

## Task 1: Add `micrometer-core` dependency + explicit `@Bean SimpleMeterRegistry`

**Status:** Done
**Commit:** f7e3b57 (impl: 6f429be)
**Agent:** main agent
**Summary:** Added `io.micrometer:micrometer-core` (no pin, BOM-resolved to 1.15.0) and `common/metrics/MeterRegistryConfig` exposing `@Bean @ConditionalOnMissingBean MeterRegistry meterRegistry()` returning `new SimpleMeterRegistry()` per Decision 10 / Risk R11. Bean wiring asserted by a `@SpringBootTest` mirroring the `HealthEndpointTest` pattern (mocked Mongo/Redis connection factories + `JobRunrInMemoryConfig`).
**Deviations:** Adding `micrometer-core` activates JobRunr's `JobRunrMetricsAutoConfiguration`, which carries `@AutoConfiguration(after = { …, MetricsAutoConfiguration.class, … })` with class-literal references to actuator classes Spring resolves during autoconfig sort — throws `ClassNotFoundException` at boot because Decision 10 keeps actuator off the classpath. Excluded the autoconfig by name on `@SpringBootApplication` (string-based, never resolved). The exclusion is the minimal-surface fix consistent with Decision 10; the alternative (pulling `spring-boot-actuator-autoconfigure` for the class symbol alone) re-introduces exactly the broad autoconfig surface that decision rejected. Bean signature unchanged from Decision 10; no tech-spec amendment required.

**Reviews:**

*Round 1:*
- code-reviewer: 4 minor findings (approved_with_suggestions) → [logs/working/task-1/code-reviewer-1.json](logs/working/task-1/code-reviewer-1.json)

*Round 2 (after fixes):*
- code-reviewer: 0 findings (approved) → [logs/working/task-1/code-reviewer-2.json](logs/working/task-1/code-reviewer-2.json)

**Verification:**
- `./gradlew test --tests "com.botfunnel.common.metrics.MeterRegistryConfigTest"` → 2 passed
- `./gradlew test` (full suite) → BUILD SUCCESSFUL (no regressions)
- `./gradlew compileJava` → BUILD SUCCESSFUL
- `./gradlew :dependencies --configuration runtimeClasspath | grep micrometer-core` → single resolved version 1.15.0 (BOM)

## Task 2: `Sha256Hex` utility + `WebhookSecretVerifier` component

**Status:** Done
**Commit:** d334b58 (impl: 4bc7c52, round-1 fix: 8a7b369, round-2 fix: d334b58)
**Agent:** main agent
**Summary:** Promoted private `BotService.sha256Hex` to public `Sha256Hex.hex(String)` in `common/crypto/` (Decision 15) and added `WebhookSecretVerifier @Component` (Decision 16) performing constant-time `MessageDigest.isEqual` verification (Decision 2) with defensive null/empty/malformed-hex handling returning `false`. `BotService.connect` and `TokenService.hashToken` both refactored to `Sha256Hex.hex` — `grep` confirms exactly one SHA-256 site in `main/java/`.
**Deviations:** None functional. Refactored `TokenService.hashToken` in addition to `BotService.connect` to satisfy the task AC "exactly one `MessageDigest.getInstance("SHA-256")` match in main/java" — task wording focused on the trigger (BotService) but the AC requires the broader cleanup, and the third consumer was right there.

**Reviews:**

*Round 1:*
- code-reviewer: 3 minor optional suggestions (approved) → [logs/working/task-2/code-reviewer-1.json](logs/working/task-2/code-reviewer-1.json)
- security-auditor: 2 minor non-blocking suggestions (approved) → [logs/working/task-2/security-auditor-1.json](logs/working/task-2/security-auditor-1.json)
- test-reviewer: 3 minor non-blocking (passed) → [logs/working/task-2/test-reviewer-1.json](logs/working/task-2/test-reviewer-1.json)

*Round 2 (after fixes):*
- test-reviewer: 1 low observation (passed) → [logs/working/task-2/test-reviewer-2.json](logs/working/task-2/test-reviewer-2.json)

**Verification:**
- `./gradlew test --tests "*Sha256HexTest"` → 4 passed
- `./gradlew test --tests "*WebhookSecretVerifierTest"` → 9 passed
- `./gradlew test --tests "*BotControllerIT"` → 24 passed (regression guard)
- `./gradlew test` (full suite) → BUILD SUCCESSFUL
- `grep -RIn "MessageDigest.getInstance(\"SHA-256\")" backend/src/main/java/` → 1 match (Sha256Hex.java)

## Task 3: `TelegramCommandParser` pure utility

**Status:** Done
**Commit:** 7dea05d (impl: 2a0335d, round-1 fix: 7dea05d)
**Agent:** main agent
**Summary:** Added `ParsedCommand(String command, String payload)` record with `notACommand()` sentinel and `TelegramCommandParser` pure static utility. Recognises commands prefixed `/`, strips `@botname` suffix on first `@`, preserves original case (worker handles folding), trims leading payload whitespace while preserving inner whitespace and newlines. Allocation-light single-pass scan; no regex, no logging, no Spring annotations.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: 3 minor optional suggestions (approved) → [logs/working/task-3/code-reviewer-1.json](logs/working/task-3/code-reviewer-1.json)
- test-reviewer: 2 minor non-blocking (passed) → [logs/working/task-3/test-reviewer-1.json](logs/working/task-3/test-reviewer-1.json)

**Verification:**
- `./gradlew test --tests "*TelegramCommandParserTest"` → 20 passed
- `./gradlew test` (full suite) → BUILD SUCCESSFUL

## Task 4: `TelegramUpdate` + nested records

**Status:** Done
**Commit:** 8312762 (impl: b8a2f4e, round-1 fix: 8312762)
**Agent:** main agent
**Summary:** Added four snake_case Java records (`TelegramUpdate`, `Message`, `Chat`, `User`) in `com.botfunnel.webhook.dto`, each annotated `@JsonIgnoreProperties(ignoreUnknown=true)` for mass-assignment defense (Decision 6). Non-message update kinds modelled as opaque `JsonNode` slots — worker only needs presence detection for `metadata.updateKind` (user-spec AC11). Jackson 2.12+ binds the snake_case components natively, so no `@JsonProperty` aliases required.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: 3 minor optional suggestions (approved) → [logs/working/task-4/code-reviewer-1.json](logs/working/task-4/code-reviewer-1.json)
- security-auditor: APPROVED, zero findings → [logs/working/task-4/security-auditor-1.json](logs/working/task-4/security-auditor-1.json)
- test-reviewer: 2 minor non-blocking (passed) → [logs/working/task-4/test-reviewer-1.json](logs/working/task-4/test-reviewer-1.json)

**Verification:**
- `./gradlew test --tests "*TelegramUpdateDeserializationTest"` → 10 passed
- `./gradlew test` (full suite) → BUILD SUCCESSFUL

## Task 5: `RawUpdate` entity + repository + status enum

**Status:** Done
**Commit:** e80baf1 (impl: 6224a67, round-1 fix: e80baf1)
**Agent:** main agent
**Summary:** Added `com.botfunnel.webhook.RawUpdate` `@Document("raw_updates")` with unique compound index `projectId_updateId_unique` (Decision 4) and TTL `ttl_createdAt` `expireAfter="90d"` partial-filtered on UPPERCASE `['PENDING', 'DONE']` (Decision 8 / F5). `RawUpdateStatus` enum and `ReactiveMongoRepository`. Defensive class-load assertion covers all three enum names byte-identity. Integration test parses the persisted partial-filter BSON to assert UPPERCASE literals — the F5 regression guard.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: 3 minor optional suggestions (approved with minor findings) → [logs/working/task-5/code-reviewer-1.json](logs/working/task-5/code-reviewer-1.json)
- security-auditor: APPROVED, zero findings → [logs/working/task-5/security-auditor-1.json](logs/working/task-5/security-auditor-1.json)
- test-reviewer: 1 minor non-blocking (passed) → [logs/working/task-5/test-reviewer-1.json](logs/working/task-5/test-reviewer-1.json)

**Verification:**
- `./gradlew test --tests "com.botfunnel.webhook.RawUpdateRepositoryTest"` → 6 passed
- All 6 TDD anchors covered: duplicate-key, compound unique, TTL expireAfter 90d, TTL partial-filter UPPERCASE (F5 regression guard), projectId standalone, status enum round-trip

## Task 6: `SubscriberService` stub interface + `NoOpSubscriberService`

**Status:** Done
**Commit:** 9bcd5f3
**Agent:** main agent
**Summary:** Added `com.botfunnel.subscriber.SubscriberService` interface and `NoOpSubscriberService` `@Service` returning `Mono.empty()` from both methods. Final package per Decision 14 — Epic 05 replaces the @Service directly with no consumer-side import churn. NoOp is NOT @Primary.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: APPROVED, zero findings → [logs/working/task-6/code-reviewer-1.json](logs/working/task-6/code-reviewer-1.json)
- test-reviewer: PASSED, zero findings → [logs/working/task-6/test-reviewer-1.json](logs/working/task-6/test-reviewer-1.json)

**Verification:**
- `./gradlew test --tests "com.botfunnel.subscriber.NoOpSubscriberServiceTest"` → 3 passed

## Task 7: `FunnelTriggerService` stub interface + `NoOpFunnelTriggerService`

**Status:** Done
**Commit:** c709dc1
**Agent:** main agent
**Summary:** Added `com.botfunnel.funnel.FunnelTriggerService` interface and `NoOpFunnelTriggerService` `@Service` returning `Mono.empty()` from both methods. Final package per Decision 14; mirrors Task 6 shape. `triggerType` is `String` (not an enum) — Epic 06 owns its own type evolution. NoOp is NOT @Primary.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: APPROVED, zero findings → [logs/working/task-7/code-reviewer-1.json](logs/working/task-7/code-reviewer-1.json)
- test-reviewer: PASSED, 1 optional non-blocking → [logs/working/task-7/test-reviewer-1.json](logs/working/task-7/test-reviewer-1.json)

**Verification:**
- `./gradlew test --tests "com.botfunnel.funnel.NoOpFunnelTriggerServiceTest"` → 4 passed

## Task 8: Staging-smoke runbook `docs/staging-smoke/08-webhook-ingestion.md`

**Status:** Done
**Commit:** 1449c04 (impl: 1928aa8, round-1 fix: 1449c04)
**Agent:** main agent
**Summary:** Added Ukrainian operator runbook (~290 lines) covering happy-path AC1, AC5 (indirectly), AC6, AC7, AC8, AC17. Sections: prerequisites → throwaway BotFather bot → optional ngrok tunnel → Connect-flow → /start ref_smoke_001 → mongosh inspection of raw_updates + events + bot.ownerChatId → Send Test Message UI click (closes the 04c gap) → /stop → Failed-queue inspection (raw_updates side + JobRunr `jobrunr_jobs` collection, Decision 9) → cleanup. Final checklist enumerates verified-by-runbook ACs vs integration-test-only ACs (no silent gaps). Decision 19 — this runbook is the de-facto post-deploy verification (no MCP for Telegram bot interaction). Runbook actually corrects two field-name inaccuracies in task spec text (`eventType` vs `type`, `updateId` vs `payload.update_id`) — used the values verified against `Event.java` and `RawUpdate.java`.
**Deviations:** None against the tech-spec / user-spec. Length 291 lines vs 07's 157 — at the stretch end of "comparable", under the 300-line ceiling; per-step "Якщо щось пішло не так" sections inflate length but improve operator self-recovery, accepted trade-off.

**Reviews:**

*Round 1:*
- code-reviewer: APPROVED with suggestions, zero critical / zero major / 7 minor → [logs/working/task-8/code-reviewer-1.json](logs/working/task-8/code-reviewer-1.json)

**Verification:**
- Style + section ordering matches `docs/staging-smoke/07-telegram-sender.md` (precedent).
- Acceptance criteria mapping to user-spec ACs verified against `user-spec.md` lines 60-101.
- All Mongo collection names + field names verified against `RawUpdate.java`, `Event.java`, `BotService.java`, `JobRunrMongoConfig.java`.
- User-side verification deferred: per task frontmatter `verify: [user]`, an operator runs the runbook end-to-end against staging post-merge. This is the gate per Decision 19.

## Task 9: `ProcessTelegramUpdateJob` worker

**Status:** Done
**Commit:** 66c0f22 (impl: 7d9a343, round-1 fix: b1c10c5, round-2 fix: 66c0f22)
**Agent:** main agent
**Summary:** Created the JobRunr worker that processes inbound Telegram updates per Architecture Worker-path steps 1–5. Re-entry guard short-circuits DONE rows (Decision 9); event-write cascade completes via `logEventBlocking(...).block()` BEFORE `processingStatus=DONE` save (Decision 12); failure path atomically writes FAILED + scrubbed+truncated `processingError` via `findAndModify` and rethrows so JobRunr's default retry policy fires (Decision 9). `ownerChatId` populate uses atomic `findAndModify` with `(_id=botId, status=CONNECTED, ownerChatId=null)` predicate (Decision 5). 29 tests cover AC6–AC12, AC11a, AC14, re-entry idempotency, ownerChatId no-overwrite, bot==null edge case, media-only message edge case, and rethrown-exception scrubber pin.
**Deviations:** None against Decisions 5 / 9 / 12 (worker mechanics). One security-relevant strengthening applied beyond the tech-spec text: the rethrown exception is a NEW `RuntimeException(classSimpleName + ": " + scrubbedTruncatedMessage)` with NO cause chain (stack trace copied from original via `setStackTrace`). The tech-spec text only specified that `processingError` is scrubbed; round-1 security audit flagged that without this rethrow-strengthening the raw token would leak via JobRunr's `jobrunr_jobs` failure row + ERROR log path. The strengthening is in-spirit with Decision 9 (which mandates the scrubber for the FAILED-state error string) and AC18 (scrubber applied at ALL worker log/storage sites). No alteration to dispatch matrix or observable contracts.

**Reviews:**

*Round 1:*
- code-reviewer: APPROVED with suggestions, 0 critical / 0 major / 5 minor → [logs/working/task-9/code-reviewer-1.json](logs/working/task-9/code-reviewer-1.json)
- security-auditor: CHANGES REQUIRED, 1 critical / 1 major / 3 minor → [logs/working/task-9/security-auditor-1.json](logs/working/task-9/security-auditor-1.json)
- test-reviewer: PASSED, 0 critical / 0 major / 3 minor → [logs/working/task-9/test-reviewer-1.json](logs/working/task-9/test-reviewer-1.json)

*Round 2 (after fixes):*
- code-reviewer: APPROVED, 0 critical / 0 major / 2 optional minor → [logs/working/task-9/code-reviewer-2.json](logs/working/task-9/code-reviewer-2.json)
- security-auditor: APPROVED, 0 critical / 0 major / 2 minor → [logs/working/task-9/security-auditor-2.json](logs/working/task-9/security-auditor-2.json)
- test-reviewer: PASSED, 0 critical / 0 major / 2 minor → [logs/working/task-9/test-reviewer-2.json](logs/working/task-9/test-reviewer-2.json)

**Verification:**
- `./gradlew test --tests *ProcessTelegramUpdateJobTest` → 29 tests, 0 failures.
- `./gradlew test` (full backend) → BUILD SUCCESSFUL, no regressions.
- Source-ordering inspection confirms cascade invariant (Decision 12): every `eventService.logEventBlocking(...).block()` finishes before `rawUpdateRepository.save(processingStatus=DONE).block()` in `handle()`.
- ListAppender pin in the failure test asserts NO captured log event ever carries a raw bot-token regex match — defends the AC18 scrubber contract for the implemented log sites (INFO start, INFO success, WARN bot==null, INFO ownerChatId populate, ERROR failure).

## Task 10: `TelegramWebhookController` + idempotent enqueue

**Status:** Done
**Commit:** 5083546 (impl: ddd9ff1, round-1 fix: 5083546)
**Agent:** main agent
**Summary:** Created the inbound HTTP handler. Returns `Mono<ResponseEntity<Void>>` with explicit
status — 200 happy / 401 invalid secret / 404 missing-project-or-bot / 400 missing update_id /
500 enqueue-failure — never through `GlobalErrorHandler` (Decision 3). Lookup order is
bot-CONNECTED → project (existence + soft-delete) → secret verify (constant-time via
`WebhookSecretVerifier`) → save + enqueue (Decision 1). On `DuplicateKeyException` from save,
re-enqueues with the deterministic UUID for self-heal (Decision 4 + 11). Owns 4 counters + 1
Timer; the fifth counter (oversize-body reason) lives on `WebhookPayloadSizeFilter`. Every
WARN/ERROR log site wraps payload-derived strings through `TelegramApiClient.scrubTokens`.

**Deviations from Decisions:** None. Two strengthenings applied beyond the tech-spec text:
1. `JobScheduler` is injected (Decision 11 alternative c) instead of the static
   `BackgroundJob.enqueue` so multi-Spring-context tests do not silently target the wrong
   `StorageProvider`. Production behaviour identical.
2. Missing `update_id` returns 400 (rather than persisting a row with `null` updateId that
   would break the unique-index idempotency contract) — closes a mass-assignment edge case
   surfaced by the code review.
The outer `onErrorResume(ex -> 500 empty body)` enforces Decision 3 for the enqueue-failure
path: without it, `Mono.error(ex)` propagated to `GlobalErrorHandler` and the webhook would
have leaked a JSON body. Documented inline in the controller.

**Reviews (round 1):**
- code-reviewer: CONDITIONAL, 0 critical / 1 major / 4 minor / 3 info → [logs/working/task-10/code-reviewer-1.json](logs/working/task-10/code-reviewer-1.json)
- security-auditor: CONDITIONAL, 0 critical / 2 major / 3 minor / 2 info → [logs/working/task-10/security-auditor-1.json](logs/working/task-10/security-auditor-1.json)
- test-reviewer: CONDITIONAL, 0 critical / 1 major / 4 minor / 3 info → [logs/working/task-10/test-reviewer-1.json](logs/working/task-10/test-reviewer-1.json)

Major findings (all resolved in 5083546):
- O(N) `findAll+filter` scan on the self-heal path → replaced with indexed
  `findFirstByProjectIdAndUpdateId`.
- Enqueue failure surfaced through `GlobalErrorHandler` (JSON body + unscrubbed log) → outer
  `onErrorResume` returns empty 500; controller's own ERROR log site keeps the scrubber.
- `warnLogOnDuplicate_noTokenInOutput` was vacuous (plain projectId/updateId, no token to
  scrub) → plant a token-shaped sentinel in the body and assert no leak.

**Verification:**
- `./gradlew test --tests *TelegramWebhookControllerIT` → 13 tests green (P99 latency marked
  `@Tag("slow")`, excluded by default; enable with `-PrunSlow=true`).
- `./gradlew test` full suite → BUILD SUCCESSFUL, no regressions.
- `grep -RIn "payload_too_large" backend/src/main/java/com/botfunnel/webhook/TelegramWebhookController.java` → exits 1 (no match — counter exclusively owned by filter).

## Task 11: `WebhookPayloadSizeFilter` + `SecurityConfig` scoping

**Status:** Done
**Commit:** 5083546 (impl: 9147b4a, round-1 fix: 5083546)
**Agent:** main agent
**Summary:** Added `WebhookPayloadSizeFilter` at `Ordered.HIGHEST_PRECEDENCE + 10`. Path-scoped
to single-segment `/webhooks/telegram/{projectId}` via Spring's `PathPattern`; rejects with 413
empty body on `Transfer-Encoding: chunked` (any value, multi-header tolerant), missing/unparseable
`Content-Length`, or `Content-Length > 1_048_576`. Owns the fifth counter
`telegram_webhook_rejected_total{reason=payload_too_large}` — controller deliberately does NOT
reference this reason (grep guard). `SecurityConfig` adds `.pathMatchers("/webhooks/telegram/{projectId}").permitAll()`
BEFORE `/api/**.authenticated()` and scopes CSRF via
`AndServerWebExchangeMatcher(CsrfWebFilter.DEFAULT_CSRF_MATCHER, NegatedServerWebExchangeMatcher(webhook-path))`.

**Deviations from Decisions:** One material adjustment to Decision 13's CSRF wiring.
Decision 13 specifies `csrf.requireCsrfProtectionMatcher(new NegatedServerWebExchangeMatcher(...))`.
Implementing it bare-negated would extend CSRF enforcement to ALL HTTP verbs (including
GET/HEAD/OPTIONS) for non-webhook paths, breaking `HealthEndpointTest` and every read-only
endpoint. The implementation ANDs the negation with Spring Security's
`CsrfWebFilter.DEFAULT_CSRF_MATCHER` (which restricts to POST/PUT/DELETE/PATCH), preserving the
verb-based filter while still scoping the path negation. Documented inline + commit message;
verified by `WebhookSecurityBlockTest::postApiAuthedWithoutXsrfToken_returns403`.

**Reviews (round 1):**
- code-reviewer: CONDITIONAL, 0 critical / 1 major / 4 minor / 2 info → [logs/working/task-11/code-reviewer-1.json](logs/working/task-11/code-reviewer-1.json)
- security-auditor: GO, 0 critical / 0 major / 2 minor / 4 info → [logs/working/task-11/security-auditor-1.json](logs/working/task-11/security-auditor-1.json)
- test-reviewer: GO, 0 critical / 0 major / 3 minor / 2 info → [logs/working/task-11/test-reviewer-1.json](logs/working/task-11/test-reviewer-1.json)

Major findings (resolved in 5083546):
- Filter used a hand-rolled regex for path match → replaced with Spring's `PathPattern` for
  normalisation parity with SecurityConfig (percent-decode, dot-segment handling).
- `toLowerCase()` defaulted to system locale → `Locale.ROOT`.
- Single `getFirst(Transfer-Encoding)` ignored multi-value headers → `getValuesAsList`.

**Verification:**
- `./gradlew test --tests "*WebhookPayloadSizeFilterTest" --tests "*WebhookSecurityBlockTest"` → all green.
- `./gradlew test` full suite → BUILD SUCCESSFUL.
- `grep -RIn "payload_too_large" backend/src/main/java/com/botfunnel/webhook/TelegramWebhookController.java` → no match (filter is sole owner).

## Task 12: Code Audit (full feature)

**Status:** Done
**Agent:** code-reviewer subagent
**Report:** [logs/audits/code-audit.md](logs/audits/code-audit.md)
**Summary:** All five grep guards pass; every Decision 1–19 has a concrete implementing site +
test guard; no `TODO`/`FIXME`/`_stubs/` references in the feature. 8 findings — 0 Critical, 0
Major, 4 Minor, 4 Info. **Verdict: GO for merge.** Two Info entries record deliberate documented
narrowings: (a) user-spec AC15 uses `**` while Decision 13 + implementation use single-segment
`{projectId}` (Security M4 anti-leak narrowing — recorded in commit message); (b) Decision 13
prose described bare `requireCsrfProtectionMatcher(Negated(...))` while the implementation uses
`AndServerWebExchangeMatcher(DEFAULT_CSRF_MATCHER, Negated(...))` to preserve the verb-based
filter — see T11 decision entry above for full rationale.

## Task 13: Security Audit (full feature)

**Status:** Done
**Agent:** security-auditor subagent
**Report:** [logs/audits/security-audit.md](logs/audits/security-audit.md)
**Summary:** 12/12 scope items verified. 0 Critical, 0 Major, 4 Minor findings. **Verdict: GO for
merge.** Minor findings: (1) three worker INFO/WARN log sites emit raw DB identifiers — defense-
in-depth gap, AC18 still satisfied; (2) soft-deleted 404 path has one extra `findById` call —
content-byte-identical, timing channel only; (3) DuplicateKey self-heal can emit empty Mono if
TTL evicts the row between save-fail and find — vanishingly rare microsecond race against 90-day
TTL; (4) 400 on missing `update_id` distinguishable from 401 — attacker already has the secret
to reach this status, leak value minimal.

## Task 14: Test Audit (full feature)

**Status:** Done
**Agent:** test-reviewer subagent
**Report:** [logs/audits/test-audit.md](logs/audits/test-audit.md)
**Summary:** 19/20 user-spec ACs mapped to tests that would fail on AC removal; tech-spec AC
bullets all mapped. 57/47 unit/integration split (≈55/45 balance). 0 Critical, 3 Major, 5 Minor.
**Verdict: GO with conditions.** The 3 Majors are tractable test-only additions:
- F1: `WebhookPayloadSizeFilter` WARN log site needs per-site `ListAppender` token-leak test.
- F4: AC4 (413 payload cap) has no end-to-end IT in `TelegramWebhookControllerIT` — currently
  covered by `WebhookPayloadSizeFilterTest` (unit) only. Tech-spec line 341 prescribed IT sub-cases.
- F7: AC16 partial — `telegram_webhook_duration_seconds` Timer never asserted; worker success
  counter not explicitly asserted on happy path.

All 3 Majors addressed before T15 — see test-additions commit below.

## Task 15: Pre-deploy QA

**Status:** Done
**Agent:** main agent (pre-deploy-qa methodology)
**Report:** [logs/qa/pre-deploy-qa-report.md](logs/qa/pre-deploy-qa-report.md)
**Summary:** All three Wave-5 audits cleared. `./gradlew test` runs 494 tests, 0 failures, 2
skipped (pre-existing `@Disabled` from Epic 04c). 20/20 user-spec ACs map to passing tests or
runbook-covered operator checks. Tech-spec ACs all verified (grep guards, SecurityConfig
order, MeterRegistry without actuator). T14 Major findings were resolved before this QA run
(commit `f05ac89`). Slow-tagged AC1 P99 latency excluded from default `./gradlew test` —
enable with `-PrunSlow=true`; staging-smoke runbook (T8) measures real-world latency.
gitleaks not installed locally; CI handles the gate per `scripts/install-hooks.sh` docs.
Manual `curl` probes deferred to staging-smoke runbook (no live Mongo/Redis outside test
containers on this workstation).

**Verdict: GO** for merge. Staging-smoke runbook (`docs/staging-smoke/08-webhook-ingestion.md`)
is the post-merge verification step per Decision 19.
