# Security Audit — Feature 08 Webhook Ingestion

**Auditor:** security-auditor agent (Wave 5, Task 13)
**Scope:** all 04b source + tests; cross-cutting OWASP Top 10 review of the composed surface.
**Methodology:** code-reading only, no runtime attacks. Per-task per-file audits in Waves 1–4 already resolved file-local findings; this audit confirms the COMPOSED system has no leaks visible only end-to-end.
**Commit reviewed:** `5083546` (head of feature branch).

---

## Item 1 — Scoped CSRF disable correctness

**Verified — `backend/src/main/java/com/botfunnel/security/SecurityConfig.java:77-82` —** CSRF protection matcher is `AndServerWebExchangeMatcher(DEFAULT_CSRF_MATCHER, NegatedServerWebExchangeMatcher(PathPatternParserServerWebExchangeMatcher("/webhooks/telegram/{projectId}")))`. The AND with `DEFAULT_CSRF_MATCHER` preserves the verb scope (POST/PUT/PATCH/DELETE) and the negated path matcher narrows the protection to NOT include `/webhooks/telegram/{projectId}`. Pattern is single-segment `{projectId}` — sub-paths like `/webhooks/telegram/abc/def` remain CSRF-protected (security M4 sub-path leak guard satisfied). `WebhookSecurityBlockTest:48-110` exercises four branches: (a) webhook POST with no CSRF token does NOT 403, (b) `/api/v1/projects` POST without auth returns 401 (CSRF passes only because csrf() mutator was added — proves chain still requires auth), (c) authed `/api/**` POST without csrf() mutator returns 403 (proves scoped disable did not bleed past `/webhooks/telegram`), (d) bare webhook POST without csrf() does not 403. Also covered end-to-end by `TelegramWebhookControllerIT.api_csrfRegression_postWithoutXsrfToken_rejected:371-384` (full integration).

## Item 2 — Secret-token handling

**Verified — `backend/src/main/java/com/botfunnel/webhook/WebhookSecretVerifier.java:18-39`, `backend/src/main/java/com/botfunnel/bot/BotService.java:146-171`, `backend/src/main/java/com/botfunnel/webhook/TelegramWebhookController.java:107-131` —**
- `X-Telegram-Bot-Api-Secret-Token` header is read into `headerSecret` and passed only to `webhookSecretVerifier.verify(headerSecret, bot.getWebhookSecretHash())` (controller line 131). Grep of all three webhook source files (`TelegramWebhookController`, `WebhookSecretVerifier`, `WebhookPayloadSizeFilter`) confirms NO `log.*` statement takes `headerSecret` or a hash candidate as parameter. The closest log lines (controller lines 140, 163, 191; filter line 87; verifier — no logs at all) take only `projectId`, `updateId`, `rawUpdateId`, and scrubbed exception messages.
- Storage: `BotService.connectAfterPreChecks:146-149` generates a fresh secret via `secureRandom.nextBytes(WEBHOOK_SECRET_BYTES)`, hex-encodes, then `Sha256Hex.hex(secretHex)` is computed and persisted as `bot.webhookSecretHash` (line 171). Plaintext `secretHex` is sent ONLY to Telegram via `telegramApiClient.setWebhook(token, webhookUrl, secretHex)` (line 152) and never persisted. The bot record carries hex SHA-256 only.
- Constant-time compare: verifier line 38 uses `MessageDigest.isEqual(candidateBytes, storedBytes)` — NOT `String.equals` / `Arrays.equals`. `WebhookSecretVerifierTest.verify_usesMessageDigestIsEqual:108-122` pins the call site via `MockedStatic`. `WebhookSecretVerifierTest.verify_anyOutcome_neverLogsHeaderOrStoredHash:124-147` attaches a TRACE-level appender to the ROOT logger and asserts no captured event contains either operand across six call permutations — strongest possible no-leak guard.

## Item 3 — Scrubber per log site

**Verified — `backend/src/main/java/com/botfunnel/webhook/TelegramWebhookController.java:140,163,191`, `backend/src/main/java/com/botfunnel/webhook/WebhookPayloadSizeFilter.java:87-92`, `backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java:84,129,229` —** every enumerated site that takes a payload-derived string wraps `TelegramApiClient.scrubTokens(...)`:
- Controller WARN on missing update_id (line 140) — `scrubTokens(projectId)`.
- Controller WARN on DuplicateKeyException (line 163) — `scrubTokens(projectId)`, `scrubTokens(String.valueOf(updateId))`.
- Controller ERROR on enqueue failure (line 191) — `scrubTokens(rawUpdateId)`, `scrubTokens(ex.getMessage())`.
- Filter WARN on 413 (line 87) — `scrubTokens(projectId)`, `scrubTokens(transferEncoding header)`.
- Worker WARN on rawUpdate-not-found (line 84) — `scrubTokens(rawUpdateId)`.
- Worker ERROR on terminal failure (line 129) — emits `truncated` which is `scrubTokens(message).substring(0, min(len,1024))` (lines 116-117). The `rawUpdateId` operand is a Mongo ObjectId (not payload-derived).
- Worker INFO on ownerChatId populate (line 229) — `scrubTokens(String.valueOf(chatId))`.

Integration assertions:
- `TelegramWebhookControllerIT.warnLogOnDuplicate_noTokenInOutput_andNoPayloadEcho:414-448` — adversarial payload with a `PAYLOAD-SENTINEL-{tokenShape}` sentinel; asserts WARN line contains neither the sentinel nor any Telegram-token-shaped substring.
- `TelegramWebhookControllerIT.errorLogOnEnqueueFail_noTokenInOutput:450-474` — forces enqueue failure with token-shaped exception message; asserts ERROR line has no token regex match.
- `ProcessTelegramUpdateJobTest:425-427` — assertion across the WHOLE captured log stream (`jobAppender.list`) that no event carries a raw token regex match — strong all-sites guard. Satisfies user-spec AC18.

See Finding 1 below for INFO-level worker sites that are enumerated in the tech-spec scrubber list but do NOT currently wrap scrubTokens — diagnosed as defense-in-depth gap, not a leak.

## Item 4 — Chunked-encoding bypass

**Verified — `backend/src/main/java/com/botfunnel/webhook/WebhookPayloadSizeFilter.java:30,59-99` —**
- Class-level `@Order(Ordered.HIGHEST_PRECEDENCE + 10)` (line 30) — `Ordered.HIGHEST_PRECEDENCE = Integer.MIN_VALUE`, so `+10` is still smaller than any Spring Security filter (default order group). The filter runs BEFORE the security chain by construction.
- Path scope is `PathPatternParser.parse("/webhooks/telegram/{projectId}")` (line 39-40) — single-segment, Spring's own parser (same normalisation as `SecurityConfig`). `WebhookPayloadSizeFilterTest.apiPathWithChunkedEncoding_passesThrough:109-125` proves filter scope; `multiSegmentWebhookPath_doesNotMatch:127-143` proves single-segment scope.
- Rejection branches (lines 69-78): chunked OR missing Content-Length OR oversize → 413 + counter increment, body never read. The `chunked` check uses `Locale.ROOT` case-fold (line 70) so a Turkish-locale JVM cannot dotless-i past the check.
- Empty Content-Length=0 passes through (test `zeroContentLength_passesThrough:145-161`) — controller's `@RequestBody required=true` surfaces Spring 400 downstream, no Netty buffer exposure.

**Attack walk-through (mental model, NOT executed):** attacker sends `POST /webhooks/telegram/507f1f77bcf86cd799439011` with `Transfer-Encoding: chunked` and no `Content-Length`, body = 100 MB. Netty reads the request line + headers (low fixed cost), routes through WebFilter chain. `WebhookPayloadSizeFilter` matches the path on line 59, reads `Transfer-Encoding` header on line 64 (case-insensitive multi-value tolerant), `chunked == true`, branches to line 94 (`setStatusCode(PAYLOAD_TOO_LARGE)`) and line 95 (`setComplete()`). Filter returns BEFORE `chain.filter(exchange)` — no body buffering ever starts, no controller invocation, no security chain invocation. Counter ticks `rejected_total{reason="chunked"}`. Result: 413 empty body, ≤1 KB of header bytes consumed, body bytes ignored at the Netty level (connection closed or drained per HTTP/1.1 spec, no application memory pressure).

## Item 5 — JobRunr deterministic-ID idempotency

**Verified — `backend/src/main/java/com/botfunnel/webhook/TelegramWebhookController.java:181` —** the sole jobId construction site is `UUID jobId = UUID.nameUUIDFromBytes(rawUpdateId.getBytes(StandardCharsets.UTF_8))` (line 181). Grep over `backend/src/main/java/com/botfunnel/webhook/` for `UUID.randomUUID` returns zero matches. The same `enqueueIdempotent(rawUpdateId)` helper is invoked from BOTH the normal path (line 153) and the DuplicateKeyException self-heal path (line 167) — both share line 181, so the deterministic UUID covers both branches. `rawUpdateId` is a Mongo ObjectId (24 hex chars) loaded from `saved.getId()` (normal) or `existing.getId()` (self-heal) — byte-for-byte deterministic across processes because (a) the `_id` is stable and (b) `UTF_8` encoding is explicit (line 181 uses `StandardCharsets.UTF_8`, never platform default). Combined with JobRunr's contract `BackgroundJob.enqueue(UUID, lambda)` being idempotent on duplicate jobId, the chain is collision-free and self-heal safe per Decision 11.

## Item 6 — processingError PII/token leak avoidance

**Verified — `backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java:114-127,137-143` —**
- Line 115: `message = t.getMessage() == null ? t.getClass().getName() : t.getMessage()` — wraps the EXCEPTION MESSAGE (not just class name). Class name fallback applies only when getMessage() is null; class names are compile-time constants and cannot carry payload.
- Line 116: `scrubbed = TelegramApiClient.scrubTokens(message)`.
- Line 117: `truncated = scrubbed.substring(0, Math.min(scrubbed.length(), ERROR_MAX_LEN))` — truncation happens AFTER scrubbing. `ERROR_MAX_LEN = 1024` (line 46) is interpreted as chars by `String.substring` (Java char semantics).
- Line 137-143: the rethrown RuntimeException carries `getClass().getSimpleName() + ": " + truncated`, re-truncated to 1024 (line 138-140), and `setStackTrace(t.getStackTrace())` (line 142) WITHOUT a cause chain. `ProcessTelegramUpdateJobTest:411-413` asserts `rethrown.getCause() == null` — prevents JobRunr from re-leaking the raw exception message through `t.getCause().getMessage()` into `jobrunr_jobs`. `ProcessTelegramUpdateJobTest:420-422` asserts `processingError` carries no token regex match.

## Item 7 — Mass-assignment defense on TelegramUpdate

**Verified —** all four DTO records carry `@JsonIgnoreProperties(ignoreUnknown=true)`:
- `backend/src/main/java/com/botfunnel/webhook/dto/TelegramUpdate.java:16`
- `backend/src/main/java/com/botfunnel/webhook/dto/Message.java:8`
- `backend/src/main/java/com/botfunnel/webhook/dto/Chat.java:14`
- `backend/src/main/java/com/botfunnel/webhook/dto/User.java:11`

Any future Telegram field absent from the record is silently dropped at deserialisation. Since these are Java `record`s with explicit canonical constructors, mass-assignment is structurally impossible regardless of the annotation — the annotation is belt-and-braces defense and prevents Jackson's `FAIL_ON_UNKNOWN_PROPERTIES` (when configured at the mapper level) from breaking deserialisation.

## Item 8 — Anti-enumeration 401 vs 404

**Verified (with one observation) — `backend/src/main/java/com/botfunnel/webhook/TelegramWebhookController.java:116-134` —**
- 401 path: ONLY when bot found AND `deletedAt == null` AND `webhookSecretVerifier.verify(...) == false` (line 131). The `ResponseEntity.status(HttpStatus.UNAUTHORIZED).<Void>build()` constructor adds no WWW-Authenticate header because the response is built by the controller (Spring's 401 entry point — which would add WWW-Authenticate — is bypassed by the `permitAll` on `/webhooks/telegram/{projectId}`). Body is empty (`<Void>build()` + `expectBody().isEmpty()` in `TelegramWebhookControllerIT:204,214,223`).
- 404 paths (lines 116-125): all four sub-cases collapse to `Mono.just(ResponseEntity.notFound().<Void>build())` at line 124 — byte-identical body (empty) and status (404). Verified by IT cases:
  - `receive_missingProject_returns404EmptyBody_counterTicked:233-246` — missing project (with valid CONNECTED bot orphan): `findById` returns empty, `filter` does not fire, `switchIfEmpty` → 404.
  - `receive_softDeletedProject_returns404:248-256` — bot found, project found with `deletedAt != null` → `filter` returns false → empty → `switchIfEmpty` → 404.
  - `receive_disconnectedBot_returns404:258-265` — `findByProjectIdAndStatus(_, CONNECTED)` empty → `switchIfEmpty` → 404.
  - `receive_malformedObjectId_returns404:267-274` — bot lookup empty (string projectId stored as-is in Bot doc, so a non-ObjectId-shaped string just misses); status 404 verified.
- The single observation is the timing-side-channel concern, filed as Finding 2 below — the soft-deleted sub-case performs one extra `projectRepository.findById` DB round-trip relative to the other three, creating a measurable response-time delta. Not a content-level leak; flagged as Minor because exploiting it requires a network attacker with consistent low-jitter timing measurements against the production deployment.

## Item 9 — Empty bodies on 401/404/413

**Verified —** Controller return type is `Mono<ResponseEntity<Void>>` (line 105). All three status responses use `.build()` on a `BodyBuilder` that yields a no-body entity:
- 401: `ResponseEntity.status(HttpStatus.UNAUTHORIZED).<Void>build()` — line 133.
- 404: `ResponseEntity.notFound().<Void>build()` — line 124.
- 413: filter writes status only via `exchange.getResponse().setStatusCode(...) + setComplete()` (lines 94-95), no body ever flows to `DataBufferUtils.write`.

IT assertions: `TelegramWebhookControllerIT.receive_invalidSecret_returns401EmptyBody_counterTicked:191-228` and `receive_missingProject_returns404EmptyBody_counterTicked:233-246` both call `.expectBody().isEmpty()`. `WebhookPayloadSizeFilterTest.oversizedContentLength_rejects413_incrementsCounter:48-65` (and the chunked / missing variants) all assert `expectStatus().isEqualTo(PAYLOAD_TOO_LARGE)` plus `downstream.get() == false` (chain.filter never invoked → body buffer never read).

## Item 10 — OWASP A01 Broken Access Control

**Verified (intentional design) — `backend/src/main/java/com/botfunnel/security/SecurityConfig.java:91`, `backend/src/main/java/com/botfunnel/webhook/TelegramWebhookController.java:116-131` —** the webhook path is `permitAll()` (line 91 of SecurityConfig). This is intentional — Telegram does not have a session. Access control is solely the secret header (`X-Telegram-Bot-Api-Secret-Token`) verified via SHA-256 + constant-time compare (item 2). Tracing the controller, every path that reaches line 152 (`rawUpdateRepository.save(row)`) and beyond MUST pass through line 131's secret verification — there is no fall-through, no early `Mono.just(ok())`. If `verifier.verify` returns false, the method returns 401 unconditionally (line 133). The only way to 200 is: bot CONNECTED + project exists + project.deletedAt null + secret valid. Risk accepted per Decision 13.

## Item 11 — OWASP A04 Insecure Design (re-entry guard)

**Verified — `backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java:78-92` —** after the `findById(rawUpdateId).block()` load + null check at lines 79-87, the FIRST executable statement against the loaded row is line 88: `if (rawUpdate.getProcessingStatus() == RawUpdateStatus.DONE) return;`. No event-write, stub-call, counter-increment, or status-mutation precedes the guard. `ProcessTelegramUpdateJobTest.reentryGuard_doneStatus_noOp:112-135` seeds a DONE row and asserts no side effects + status stays DONE. Decision 9 contract honoured.

## Item 12 — OWASP A09 Logging Failures

**Verified —** No PII or secret leaks end-to-end. Coverage matrix:
- Webhook-secret header: never logged (item 2; `WebhookSecretVerifier` has zero `log.*` statements; controller logs only after verifier returns; verifier test attaches ROOT TRACE appender to prove no leak across six call permutations).
- Bot tokens: never logged at any webhook site — `scrubTokens` regex `\d{1,20}:[A-Za-z0-9_-]{30,50}` redacts the Telegram bot-token shape; integration tests inject token-shaped sentinels and assert no match in captured log streams (controller IT lines 414-474, worker test lines 425-427).
- Payload content: controller does not log `body`; worker does not log raw payload — only `chatId` / `projectId` / `rawUpdateId` (all identifiers, no message text); filter logs only header values and reason string. No `body.toString()` or `payload.toString()` appears in any log site.
- Auth/access denied events: 401 (line 132 increments `rejectedInvalidSecret`), 404 (line 123 increments `rejectedProjectNotFound`), 413 (filter line 93 increments `rejectedPayloadTooLarge`), duplicate (line 162 increments `rejectedDuplicate`) — all surface as counters at the Micrometer registry. Audit trail for processed updates: `EventService.logEventBlocking` writes `telegram_command_start` / `_stop` / `_message_received` / `_update_other` event documents (worker lines 266, 273, 280, 287). DLQ rows: `raw_updates.processingStatus=FAILED` + scrubbed/truncated `processingError` per item 6.

See Finding 1 below — the INFO-level worker sites at lines 96, 107, 211 do NOT currently wrap operands through scrubTokens. These operands are all DB-known identifiers (rawUpdateId, projectId, chatId), so no payload-derived bytes are at risk; the gap is purely defense-in-depth vs. the explicit Decision 9 "every log site" claim.

---

# Findings

## Finding 1 — Three INFO/WARN log sites in worker do not wrap operands through scrubTokens despite tech-spec enumeration

**Severity:** Minor
**File:line:** `backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java:96-97`, `:107-108`, `:211-212`
**Category:** OWASP A09 (Logging Failures) — defense-in-depth gap

**Observation:** The tech-spec "Token-scrubber sites" subsection (`work/08-webhook-ingestion/tech-spec.md:426-431`) enumerates all worker log sites that must wrap payload-derived input through `TelegramApiClient.scrubTokens(...)`. Three sites currently emit raw operands:
- Line 96 INFO worker-start — `rawUpdateId, rawUpdate.getProjectId()` not wrapped.
- Line 107 INFO worker-success — `rawUpdateId, rawUpdate.getProjectId()` not wrapped.
- Line 211 WARN bot-lookup-missed in /start — `projectId, chatId` not wrapped.

The operands are DB-known identifiers (Mongo ObjectIds and a parsed numeric `chatId`), not raw payload bytes — Telegram-token shape `\d{1,20}:[A-Za-z0-9_-]{30,50}` cannot match a 24-hex ObjectId or a numeric chat id. The actual user-spec AC18 contract ("no token leaks in webhook logs") is satisfied: `ProcessTelegramUpdateJobTest:425-427` asserts the whole captured stream is token-free across the failure scenario.

**Recommendation:** Wrap all three sites for forward-defense — if a future refactor accidentally substitutes a payload-derived string into one of these slots (e.g. `chatType` from `message.chat()` or a `startPayload` echo), the scrubber catches it. Apply the same `TelegramApiClient.scrubTokens(...)` wrapping precedent already in use at line 229. Zero behavioural change today; one-line per site.

```java
// line 96-97
log.info("ProcessTelegramUpdateJob - start (rawUpdateId={}, projectId={})",
        TelegramApiClient.scrubTokens(rawUpdateId),
        TelegramApiClient.scrubTokens(rawUpdate.getProjectId()));
// line 107-108
log.info("ProcessTelegramUpdateJob - success (rawUpdateId={}, projectId={})",
        TelegramApiClient.scrubTokens(rawUpdateId),
        TelegramApiClient.scrubTokens(rawUpdate.getProjectId()));
// line 211-212
log.warn("ProcessTelegramUpdateJob - bot lookup missed in /start handler (projectId={}, chatId={})",
        TelegramApiClient.scrubTokens(projectId),
        TelegramApiClient.scrubTokens(String.valueOf(chatId)));
```

## Finding 2 — Soft-deleted-project 404 path performs one extra DB read vs. other three 404 sub-cases — measurable timing side channel

**Severity:** Minor
**File:line:** `backend/src/main/java/com/botfunnel/webhook/TelegramWebhookController.java:116-125`
**Category:** OWASP A01 (Broken Access Control) — information disclosure via timing

**Observation:** Item 8's content-level byte-equivalence holds (all four 404 sub-cases emit identical response headers + empty body + status 404). However, the DB-round-trip cost differs:
- Missing-project / disconnected-bot / malformed-ObjectId: ONE DB call (`botRepository.findByProjectIdAndStatus` returns empty) → 404.
- Soft-deleted-project: TWO DB calls (`findByProjectIdAndStatus` returns the bot; `projectRepository.findById(projectId)` is invoked; `filter` rejects on `deletedAt != null`) → 404.

The soft-deleted path takes a single additional indexed Mongo read (`projects._id`). On localhost the extra cost is ~1 ms; in production with median Mongo latency 2-5 ms, an attacker who can repeatedly probe the same projectId can statistically distinguish "soft-deleted" from the other three sub-cases. This leaks the existence of a soft-deleted project tied to a guessable projectId. The non-trivial caveat: projectId is a Mongo ObjectId (24 hex chars, ~96 bits of entropy — guessing is computationally infeasible without prior knowledge).

**Attack vector (not exploited):** an authenticated insider who knows a former projectId can verify whether it was soft-deleted vs. hard-deleted by measuring the webhook 404 timing. Information value is low (the attacker already knew the projectId existed), but it does distinguish the deletion mode.

**Recommendation:** Defer mitigation to a follow-up PR unless timing-side-channels become an explicit threat model item. If addressed, the canonical fixes are (in increasing order of cost):
1. Issue `findByProjectIdAndStatus` + `findById` always in parallel via `Mono.zip` — uniform two-DB-call cost across all sub-cases.
2. Add a constant-time-equalising delay before emitting 404 (synthetic `Mono.delay(D)`, with D selected to dominate Mongo jitter).
3. Cache project existence/soft-delete bit in Redis keyed by projectId with short TTL — collapses both Mongo calls to one Redis GET in the hot path.

Document the residual risk acceptance in `decisions.md` if no mitigation is taken.

## Finding 3 — DuplicateKey self-heal path may emit empty Mono (no response body, no status) if the existing RawUpdate is TTL-evicted between save-fail and find-first lookup

**Severity:** Minor
**File:line:** `backend/src/main/java/com/botfunnel/webhook/TelegramWebhookController.java:157-168`
**Category:** OWASP A04 (Insecure Design) — narrow edge-case correctness gap

**Observation:** Decision 4's self-heal flow on `DuplicateKeyException` is:
1. `rejectedDuplicate.increment()` (line 162).
2. Log scrubbed WARN (line 163-165).
3. `rawUpdateRepository.findFirstByProjectIdAndUpdateId(projectId, updateId)` (line 166).
4. `.flatMap(existing -> enqueueIdempotent(existing.getId()).thenReturn(ResponseEntity.ok().<Void>build()))` (line 167-168).

If between step 1 (DuplicateKey raised) and step 3 (find-first) the TTL index (`raw_updates.createdAt` partial filter `processingStatus in {PENDING, DONE}`) evicts the existing row, `findFirstByProjectIdAndUpdateId` returns `Mono.empty()`. The `.flatMap` does NOT emit, so the inner Mono is empty. The outer chain's `.onErrorResume(ex -> ...500)` does not fire (no error). Result: the response Mono is empty — Spring WebFlux's default for an empty `Mono<ResponseEntity<Void>>` is 200 OK no body. Acceptable from the Telegram contract perspective (200 stops the retry), but no enqueue happens for this updateId — the update is silently dropped.

The race window is microseconds wide and requires Mongo TTL eviction to fire exactly between the DuplicateKey moment and the find-first lookup, against a 90-day TTL — practically zero in production. Worth filing for completeness.

**Recommendation:** Add a `.switchIfEmpty(Mono.just(ResponseEntity.ok().<Void>build()))` after the `findFirstByProjectIdAndUpdateId` flatMap to make the missing-row branch explicit; log a WARN explaining the race so operators see it if it ever fires. Alternatively, treat the missing-row branch as a 5xx to surface the silent-drop case — Telegram will retry and either re-create the row or land on the still-existing one.

```java
return rawUpdateRepository.findFirstByProjectIdAndUpdateId(projectId, updateId)
        .flatMap(existing -> enqueueIdempotent(existing.getId())
                .thenReturn(ResponseEntity.ok().<Void>build()))
        .switchIfEmpty(Mono.defer(() -> {
            log.warn("TelegramWebhookController - DuplicateKey self-heal lost row (projectId={}, updateId={}) — likely TTL race",
                    TelegramApiClient.scrubTokens(projectId),
                    TelegramApiClient.scrubTokens(String.valueOf(updateId)));
            return Mono.just(ResponseEntity.ok().<Void>build());
        }));
```

## Finding 4 — Missing update_id returns 400 (not 404 or 401), distinguishing "valid secret + malformed payload" from other failure modes

**Severity:** Minor
**File:line:** `backend/src/main/java/com/botfunnel/webhook/TelegramWebhookController.java:136-143`
**Category:** OWASP A01 (Broken Access Control) — minor information disclosure

**Observation:** The controller emits 400 (`ResponseEntity.badRequest().build()`) on line 142 when `extractUpdateId(body) == null`. This branch is reachable ONLY after bot lookup + project lookup + secret verify all pass — so any external observer who triggers a 400 has confirmed (a) the bot is CONNECTED on this projectId, (b) the project exists and is not soft-deleted, AND (c) their secret header matches. The 400 leaks "you got past auth, your body shape was wrong."

In practice this leaks little: an attacker who has the secret can already POST valid payloads and reach 200 — the 400 is no worse than the 200 baseline. The only attack-relevant scenario is a peer-bot whose secret is unrelated but who can reach 200 anyway — irrelevant.

**Recommendation:** Acceptable as-is. Document in `decisions.md` that the 400 distinction is intentional (operator-facing: malformed Telegram update is a real diagnostic signal); attackers with secret access have no additional privilege via this 400. Alternatively change to 200-empty (silent-drop semantics) if Telegram's contract permits it — would unify the post-auth response surface to one code.

---

## Summary

| Severity   | Count |
|------------|-------|
| Critical   | 0     |
| Major      | 0     |
| Minor      | 4     |
| Verified items (no finding) | 11 |
| Total scope items addressed | 12 / 12 |

**Recommendation: GO for merge.** Zero Critical or Major findings. All twelve scope items in the audit charter are either explicitly verified against file:line evidence or carry an enumerated Minor finding with a concrete recommendation. Findings 1 and 4 are defense-in-depth / documentation tweaks (one-line code edits or `decisions.md` notes). Findings 2 and 3 are narrow edge cases (Mongo-jitter timing channel, microsecond-wide TTL race) that warrant follow-up PRs but do not block this feature's release. The composed 04b surface satisfies user-spec AC2, AC3, AC4, AC14, AC15, AC18 and the OWASP A01/A04/A09 requirements stated in the tech-spec.
