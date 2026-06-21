# Decisions Log: 12-funnels-triggers

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

## Task 3: ApiKey domain + generation/hash service

**Status:** Done
**Commit:** 6eb4fdd (impl), cad58dc (review round 1 fixes)
**Agent:** apikey-domain
**Summary:** Greenfielded `com.botfunnel.api` with `ApiKey` (@Document `api_keys`, unique `keyHash` + `projectId`, `keyPrefix` mask, `lastUsedAt`), `ApiKeyRepository`, and `ApiKeyService` that mints a 256-bit `SecureRandom` plaintext, stores `Sha256Hex.hex` only (hash-only at rest), returns the plaintext once, looks up by hash, and regenerates by overwrite (Decision 8). On-demand only — not wired into `ProjectService.create`, no backfill runner.
**Deviations:** None. Per Decision 8 the code-research §5/§7 generate-on-create / backfill suggestion was deliberately ignored.

**Reviews:**

*Round 1:*
- code-reviewer: 2 valid minor findings (lastUsedAt untested; missing WHY comment) → [logs/working/task-3/code-reviewer-round1.json]
- security-auditor: OK (no critical/high/medium) → [logs/working/task-3/security-auditor-round1.json]
- test-reviewer: 2 valid minor findings (lastUsedAt coverage; field round-trip) → [logs/working/task-3/test-reviewer-round1.json]

*Round 2 (after fixes):*
- code-reviewer: OK → [logs/working/task-3/code-reviewer-round2.json]
- security-auditor: OK → [logs/working/task-3/security-auditor-round2.json]
- test-reviewer: OK → [logs/working/task-3/test-reviewer-round2.json]

**Verification:**
- `./gradlew test --tests 'com.botfunnel.api.*'` → BUILD SUCCESSFUL, 13 tests (9 `ApiKeyServiceTest` unit + 4 `ApiKeyRepositoryIT` against real embedded Mongo, incl. unique-index enforcement). Sibling Task 1 (`5751a73`) landed between the Task-3 commits, so the shared working tree compiles and the suite runs end-to-end.
- Reviews performed via skill-loading fallback (Agent/Task spawn tool unavailable in this environment).

## Task 1: Funnel domain — new trigger types, keywords, EMIT_EVENT, depth field

**Status:** Done
**Commit:** 5751a73 (impl), 90f589d (review round 1 fixes)
**Agent:** funnel-domain
**Summary:** Extended the funnel domain for Phase 3: added `StepType.EMIT_EVENT`, `FunnelStep.eventName` (+ `copyOf` carries it), `FunnelExecution.enrollDepth` (default 0), `Funnel.keywords` (lowercase/trim/dedupe list), and relaxed the partial-unique trigger index annotation to `{status:'active', triggerType:'on_start'}` (Decision 1; live drop is Task 2's migration). Extended `FunnelService` to validate the five trigger types (unknown → 422), branch `triggerValue` validation per type (tag slug / field key / event slug), require non-empty `keywords` iff `keyword`, and validate the `EMIT_EVENT` step `eventName` slug; added the `FunnelRepository` fan-out list queries (this task is the sole owner of repository changes).
**Deviations:** keywords/triggerValue mismatch resolved as reject-with-422 (not silent ignore), per the task's preferred tight-contract option (`CODE_INVALID_KEYWORDS` for keywords on a non-keyword trigger). `StepExecutor` (a read-context file) gained a single `EMIT_EVENT` case that throws `UnsupportedOperationException` — minimal change required to keep the exhaustive returning switch compiling now; the real dispatch is wired in Task 5.

**Reviews:**

*Round 1:*
- code-reviewer: 3 minor observations, all accepted as-is (no fix) → [logs/working/task-1/code-reviewer-round1.json]
- security-auditor: OK (no vulnerabilities) → [logs/working/task-1/security-auditor-round1.json]
- test-reviewer: 1 valid minor finding (assert specific business code, not just 422) → [logs/working/task-1/test-reviewer-round1.json]

*Round 2 (after fixes):*
- code-reviewer: OK → [logs/working/task-1/code-reviewer-round2.json]
- security-auditor: OK → [logs/working/task-1/security-auditor-round2.json]
- test-reviewer: OK → [logs/working/task-1/test-reviewer-round2.json]

**Verification:**
- `./gradlew test --tests 'com.botfunnel.funnel.*'` → BUILD SUCCESSFUL (new unit tests `StepTypeTest`, `FunnelExecutionTest`, `FunnelStepTest::copyOf_preservesEventName`, plus IT validation classes `FunnelServiceKeywordTest`/`FunnelServiceTriggerTypeTest`/`FunnelServiceEmitEventTest`, and the full existing funnel suite — no regressions).
- `./gradlew compileJava` → BUILD SUCCESSFUL (downstream tasks compile against the new fields/queries).
- `FunnelIndexesIT` not touched (owned by Task 2, per task constraint).
- Reviews performed via skill-loading fallback (Agent/Task spawn tool unavailable in this environment).

---

## Task 4: FunnelEventService — subscriber-keyed fan-out dispatcher + loop backstops

**Status:** Done
**Commit:** 17c5117 (impl), 075a836 (review round 1 fix)
**Agent:** event-dispatcher
**Summary:** Added `FunnelEventService` (`@Service`) with `dispatchForSubscriber(projectId, subscriberId, triggerType, matchKey, originDepth)`: resolves the CONNECTED bot + project-scoped subscriber, queries the LIST of active matching funnels (Task-1 queries; keyword contains-match runs in code, case-insensitive any-of-many), and fans out one execution per match stamping `enrollDepth = originDepth`. Extracted the depth-aware `insertExecution`/`cancelExistingForPair` into a package-private `@Component FunnelExecutionFactory` that BOTH `FunnelTriggerServiceImpl` (depth 0) and `FunnelEventService` inject — so `FunnelEventService` has zero dependency on `FunnelTriggerServiceImpl` and the cycle Task 5 would otherwise close (`StepExecutor → FunnelEventService`) cannot form. Enforced the three independent backstops (depth cap, per-subscriber auto-enroll rate-limit, per-dispatch fan-out ceiling) and full error-isolation (`try/catch(Throwable)`, greppable WARN). Created and own the project-scoped `SubscriberService.findById(projectId, subscriberId)` (anti-IDOR; Task 7 reuses it).
**Deviations:** None. Cycle broken structurally via `FunnelExecutionFactory` (no `@Lazy`); `findById` created and owned here as required. Decisions on tunables: depth-cap boundary is `drop when originDepth > 10` (depth 10 is the last allowed). Auto-enroll limit (20/min), depth cap (10), and fan-out ceiling (50) are all env-tunable properties (`app.funnel.auto-enroll-rate-per-min`, `app.funnel.max-enroll-depth`, `app.funnel.max-fanout-per-event`). `LOG_FIRE_STARTED` literal preserved (moved to factory as `LOG_EXECUTION_STARTED` with the same `FUNNEL_FIRE_EXECUTION_STARTED` string) so existing greps/alerts still match; `on_start` `fire(...)` behaviour unchanged.

**Reviews:**

*Round 1:*
- code-reviewer: 3 minor observations, all accepted as-is (no fix) → [logs/working/task-4/code-reviewer-round1.json]
- security-auditor: OK (no critical/high/medium; 4 low confirmations) → [logs/working/task-4/security-auditor-round1.json]
- test-reviewer: 1 valid minor finding (re-enter interplay coverage gap) → [logs/working/task-4/test-reviewer-round1.json]

*Round 2 (after fixes):*
- test-reviewer: OK → [logs/working/task-4/test-reviewer-round2.json]

**Verification:**
- `./gradlew test --tests 'com.botfunnel.funnel.FunnelEventServiceTest' --tests 'com.botfunnel.subscriber.SubscriberServiceImplTest'` → BUILD SUCCESSFUL (16 unit tests incl. fan-out, all three backstops with Redis-down, keyword matching, error-isolation, re-enter interplay, anti-IDOR findById).
- `./gradlew test --tests 'com.botfunnel.funnel.FunnelEventServiceIT'` → BUILD SUCCESSFUL (embedded Mongo: each trigger type, fan-out proving the index relax, depth-cap + fan-out-ceiling with Redis down, volume fail-open; context boots cleanly = bean-graph startup check, no `BeanCurrentlyInCreationException`).
- `./gradlew test --tests 'com.botfunnel.funnel.*' --tests 'com.botfunnel.subscriber.*' --tests 'com.botfunnel.webhook.*'` → BUILD SUCCESSFUL (no regressions; `on_start` `fire(...)` IT stays green).
- Reviews performed via skill-loading fallback (Agent/Task spawn tool unavailable in this environment).

---

## Task 2: Trigger-index reconciliation startup migration

**Status:** Done
**Commit:** f2f5582 (impl), ab07e19 (review round 1 fix)
**Agent:** index-migration
**Summary:** Added `FunnelTriggerIndexReconciliation` that drops the old broad-filter `projectId_triggerType_triggerValue_unique_active` index (partial filter `{status:'active'}`) at boot so `auto-index-creation` recreates the Phase-3 `{status:'active', triggerType:'on_start'}` shape on the same boot. Idempotent — driven by the actual `partialFilterExpression` (old shape lacks `triggerType`), not the index name; no-op + greppable marker when absent (fresh DB) or already migrated; log-but-never-throw. Updated the task-owned `FunnelIndexesIT` (`funnelsCollectionHasExpectedIndexes` now asserts `triggerType='on_start'`) and added two `@Tag("slow")` ITs (two active `event` funnels coexist; two `on_start` still collide).
**Deviations:** Deviated from Decision 9's mechanism (`ApplicationRunner`, FunnelStepIdBackfill pattern). A live boot smoke proved the runner ordering is unworkable: with `auto-index-creation=true`, Spring Data creates the annotation-driven indexes eagerly during `MongoTemplate` bean instantiation — long before any `ApplicationRunner` — and because the old/new indexes share a name but differ in partial filter, auto-creation hits MongoDB error 86 (IndexKeySpecsConflict) and the context FAILS to start, so the runner's drop never runs. Implemented instead as a `BeanPostProcessor` on the `MongoDatabaseFactory` bean (created/connected before `MongoTemplate`), dropping the old index in `postProcessAfterInitialization` so the conflict is cleared before auto-creation lays down the new shape. Decision 9's intent (idempotent startup drop, log-but-never-throw, `listIndexes()` guard) is preserved; only the lifecycle hook changed.

**Reviews:**

*Round 1:*
- code-reviewer: 1 minor (BPP single-factory assumption, verified + accepted) → [logs/working/task-2/code-reviewer-round1.json]
- security-auditor: OK (no findings; no user input / auth / secrets surface) → [logs/working/task-2/security-auditor-round1.json]
- test-reviewer: 1 valid minor (leaked MongoClient in fault test) → [logs/working/task-2/test-reviewer-round1.json]

*Round 2 (after fix):*
- code-reviewer: OK → [logs/working/task-2/code-reviewer-round2.json]
- security-auditor: OK → [logs/working/task-2/security-auditor-round2.json]
- test-reviewer: OK (TR-1 resolved) → [logs/working/task-2/test-reviewer-round2.json]

**Verification:**
- `./gradlew test -PrunSlow=true --tests 'com.botfunnel.funnel.FunnelTriggerIndexReconciliationIT'` → BUILD SUCCESSFUL (5 ITs: drop path, new-shape no-op, fresh-DB no-op, Mongo-fault swallow, fan-out coexist/collide).
- `./gradlew test -PrunSlow=true --tests 'com.botfunnel.funnel.FunnelIndexesIT' --tests 'com.botfunnel.funnel.FunnelStepIdBackfillTest'` → BUILD SUCCESSFUL (no regressions; new `event`-coexist + `on_start`-collide ITs pass).
- `./gradlew test` → BUILD SUCCESSFUL (full default suite, slow excluded).
- Smoke (DONE — live boot against Dockerised Mongo+Redis): Boot 1 (DB carrying old broad-filter index) → APPLIED marker logged, old index dropped, auto-index-creation recreated `{status:'active', triggerType:'on_start'}` (confirmed via `db.funnels.getIndexes()`). Boot 2 (same DB, new shape) → app started cleanly, NO-OP marker logged, APPLIED marker absent (idempotent). Boot 3 (fresh DB) → app started, NO-OP marker, new shape auto-created directly. Grep command: `grep -E "trigger-index reconciliation" <startup.log>` (APPLIED on first migrated boot, NO-OP thereafter).
- Reviews performed via skill-loading fallback (Agent/Task spawn tool unavailable to subagents in this environment).

---

## Task 5: Funnel-step trigger integration (tag/field hooks + EMIT_EVENT + depth threading)

**Status:** Done
**Commit:** 7f3cf50
**Agent:** step-triggers
**Summary:** Wired funnel-execution side effects into `FunnelEventService`: `addTag` now fires `tag_added(slug)` and `recordCustomFieldsSet` fires `custom_field_set` once per changed key — both strictly after the existing idempotency guard + `writeSubscriberEvent` audit (no-op writes never fire). Replaced the throwing `EMIT_EVENT` guard in `StepExecutor` with a real dispatch (`triggerType=event`, `matchKey=eventName`) that returns `CONTINUE`. Origin depth is an explicit `int originDepth` parameter (no ThreadLocal) on both writers: `0` from the manual controllers, `execution.getEnrollDepth() + 1` from the `StepExecutor` callers. Trigger-type literals exposed as public constants on `FunnelEventService` (mirroring the package-private `FunnelService.TRIGGER_*`) for cross-package callers.
**Deviations:** Task 5's closing edge also created a previously-absent 2-node bean cycle `SubscriberServiceImpl ↔ FunnelEventService` (the dispatcher injects `SubscriberService` for `findById`; the impl now injects the dispatcher). Broke it with `@Lazy` on the dispatcher constructor param in `SubscriberServiceImpl` — the idiomatic break for this edge, distinct from the `StepExecutor → FunnelEventService` edge the task discusses (that one stays structurally broken via the Task-4 `FunnelExecutionFactory`). Context boot is proven green by the full-context ITs; no `@Lazy`/workaround was needed on `StepExecutor`.

**Reviews:**

*Round 1:*
- code-reviewer: 2 minor (bean-cycle `@Lazy` break + trigger-constant indirection, both accepted-as-designed) → [logs/working/task-5/code-reviewer-round1.json]
- security-auditor: OK (no findings; reuses authorized project-scoped subscriber, parameterized Mongo match, no PII/secrets) → [logs/working/task-5/security-auditor-round1.json]
- test-reviewer: OK (all TDD anchors covered at the right layer; no redundancy) → [logs/working/task-5/test-reviewer-round1.json]

**Verification:**
- `./gradlew test --tests 'com.botfunnel.funnel.FunnelStepExecutorTest' --tests 'com.botfunnel.subscriber.SubscriberStatusMachineTest' --tests 'com.botfunnel.subscriber.SubscriberServiceImplTest'` → BUILD SUCCESSFUL (unit: EMIT_EVENT continue+child depth, tag/field fire-after-write, no-op no-fire, explicit depth pins).
- `./gradlew test --tests 'com.botfunnel.subscriber.SubscriberServiceImplIT' --tests 'com.botfunnel.subscriber.SubscriberTagAssignmentIT'` → BUILD SUCCESSFUL (full-context boot with FunnelEventService injected into both StepExecutor and SubscriberServiceImpl → no BeanCurrentlyInCreationException; @MockitoBean dispatch arg/depth assertions + real-DB audit interplay).
- `./gradlew test` → BUILD SUCCESSFUL (full default suite, no regressions).
- Reviews performed via skill-loading fallback (Agent/Task spawn tool unavailable to subagents in this environment).

---

## Task 6: Keyword webhook path + waiting_for_reply precedence

**Status:** Done
**Commit:** 455de10
**Agent:** keyword-webhook
**Summary:** Wired the keyword trigger into `ProcessTelegramUpdateJob`'s plain-text private-chat branch: after the subscriber upsert, resolve the subscriber via `findByChat`, run a read-only `mongoTemplate.exists(...)` precedence probe for an in-flight `waiting_for_reply` execution (menu wins — keyword suppressed, menu untouched), otherwise fan-out keyword matching via `FunnelEventService.dispatchForSubscriber(projectId, subscriberId, keyword, text, 0)`. The entire keyword block is a local `try/catch(Throwable)` swallow with a greppable PII-free WARN so a dispatch fault leaves the worker at HTTP 200 / JobRunr job not failed (Decision 12); the outer worker FAILED+rethrow path is untouched. Exposed `FunnelEventService.TRIGGER_KEYWORD` as a public constant (mirroring the existing public TRIGGER_* set) for the cross-package webhook caller.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: 2 minor (double bot-resolve + telegramBotId param — both WONT_FIX, intentional by Task-4 dispatcher design) → [logs/working/task-6/code-reviewer-round1.json]
- security-auditor: OK (no findings; project-scoped resolution, parameterized Mongo criteria, attacker text only contains-matched + never logged) → [logs/working/task-6/security-auditor-round1.json]
- test-reviewer: OK (full TDD-anchor coverage; real-state assertions incl. Mongo execution reload for precedence) → [logs/working/task-6/test-reviewer-round1.json]

**Verification:**
- `./gradlew test --tests '*ProcessTelegramUpdateJob*'` → BUILD SUCCESSFUL (5 new tests: keyword dispatch, waiting_for_reply precedence with execution-untouched assertion, error-isolation worker-still-succeeds, command-exclusion, non-private/no-subscriber skip).
- `./gradlew test --tests '*webhook*' --tests '*funnel*' --tests '*Funnel*' --tests '*Webhook*' --tests '*SubscriberExportSignedUrl*'` → BUILD SUCCESSFUL (989 tests, no regressions). One transient `SubscriberExportSignedUrlIT` failure in an interrupted earlier run was a testcontainer-state artifact — re-ran green in isolation and in-suite, unrelated to Task-6 files.
- Smoke (LIVE Telegram) DEFERRED to Task 15 (post-deploy verification) — requires a live bot + Telegram MCP. Steps: (1) send the bot free text containing a configured trigger word → the matching keyword funnel starts (new execution); (2) drive the subscriber to a MENU step (parked waiting_for_reply), then send the same trigger text → menu stays, NO new keyword execution is created.
- Reviews performed via skill-loading fallback (Agent/Task spawn tool unavailable to subagents in this environment).

## Task 7: API-key security chain + EventsController

**Status:** Done
**Commit:** 342a994
**Agent:** events-api
**Summary:** Added an `@Order(1)` STATELESS `SecurityFilterChain` with `securityMatcher("/api/integrations/**")` (CSRF off) + a non-`@Component` `ApiKeyAuthFilter` that resolves the project from the `X-API-Key` header via `ApiKeyService.lookup` (SHA-256 hash, no plaintext compare), stamps `lastUsedAt`, pins an `ApiKeyAuthentication` carrying only the projectId, and returns a uniform bodyless 401 for any key fault (missing/blank/malformed/unknown); the session chain was demoted to `@Order(2)` and `X-API-Key` added to CORS `allowedHeaders`. Added `EventsController` `POST /api/integrations/v1/events`: `@Valid` DTO (event_name slug + cross-field "≥1 identifier" → 400), global fixed Redis rate-limit (`bf:rate:events`, fail-open → 429), project-scoped subscriber resolution (subscriber_id wins; cross-project/unknown → 404, never auto-creates), dispatch via `FunnelEventService.dispatchForSubscriber(..., depth 0)`, error-isolated so an engine fault → 202 (never 5xx).
**Deviations:** Added a thin project-scoped boundary method `SubscriberService.findByTelegramUserId(projectId, telegramUserId)` (+ impl) — the task listed `SubscriberService` as read-only but ALSO required resolving telegram_user_id "through the service boundary, not the repository, from the api package" with no such method existing (only `findById(projectId,id)` from Task 4). The minimal boundary method honors the anti-IDOR + no-repo-from-api rules; it is NOT the forbidden re-add of `findById`. Updated two `@WebMvcTest` slice tests (`AuthControllerSliceTest`, `ProjectControllerSliceTest`) and `CsrfMaterializerIT` for the now-two-chain config (mock `ApiKeyService`/`ApiKeyRepository`; `@Qualifier("appSecurityFilterChain")`) — required so the imported `SecurityConfig` wires under the slice context.

**Reviews:**

*Round 1:*
- code-reviewer: 4 minor, all keep/noted (redundant-but-intentional belt-and-braces catch; verified cross-file signatures; non-@Component filter rationale) → [logs/working/task-7/code-reviewer-round1.json]
- security-auditor: OK, no critical/high (anti-IDOR project-scoping, uniform 401/no-enumeration, hash-only no-timing-oracle, no PII/secret logs, CSRF/CORS correctly scoped; one low note: per-request lastUsedAt write bounded by the 429 limit) → [logs/working/task-7/security-auditor-round1.json]
- test-reviewer: OK (all 18 TDD-anchor tests + a bonus redis-down fail-open IT; correct pyramid, real-state assertions) → [logs/working/task-7/test-reviewer-round1.json]

No round 2 — no code changes required (all findings keep/noted with justification).

**Verification:**
- `./gradlew test --tests 'com.botfunnel.api.ApiKeyAuthFilterTest' --tests 'com.botfunnel.api.EventsControllerIT' --tests 'com.botfunnel.security.ChainIsolationIT' --tests 'com.botfunnel.security.IntegrationsCorsIT'` → BUILD SUCCESSFUL.
- `./gradlew test` (full suite) → BUILD SUCCESSFUL, 1011 tests, no regressions. (An intermediate batch run showed cascade failures from JVM/Docker resource exhaustion when 5 heavy IT suites ran in one invocation; each suite is green run individually and the full clean run passes.)
- Smoke (LIVE curl 202/401/404/400/429) DEFERRED to Task 15 (post-deploy verification) — requires a live app + a generated API key (Task 8). The full matrix is covered by MockMvc/embedded-Mongo+Redis ITs. Exact deferred commands: `curl -X POST localhost:8080/api/integrations/v1/events -H 'X-API-Key: <key>' -H 'Content-Type: application/json' -d '{"event_name":"e","telegram_user_id":123}'` → 202; same without `-H 'X-API-Key'` → 401; with a bogus `telegram_user_id` → 404; with `-d '{"event_name":"!bad slug"}'` → 400; flood >300/min → 429.
- Reviews performed via skill-loading fallback (Agent/Task spawn tool unavailable to subagents in this environment).

## Task 8: Project-settings API-key generate/regenerate endpoint

**Status:** Done
**Commit:** 9f031a4
**Agent:** apikey-endpoint
**Summary:** Added a session-authed `ApiKeyController` at `/api/v1/projects/{projectId}/api-key`: GET returns the `prefix•••` mask (or `{present:false,mask:null}` for an owned key-less project), POST generate-or-regenerates via `ApiKeyService.generate` (single upsert call — overwrite invalidates the old hash) and returns the plaintext exactly once. Both verbs gate on `ProjectService.requireOwned(ownerId, projectId, false)` (uniform 404 anti-enumeration). Added `ApiKeyMaskResponse`/`ApiKeyGeneratedResponse` records + an `ApiKeyMask` helper, and a thin `ApiKeyService.currentKeyPrefix(projectId)` accessor (prefix-only, never hash/plaintext) to keep the controller off the entity internals. On-demand only — not wired into `ProjectService.create`. Served by the session chain (`@Order(2)`); `/api/v1/projects/**` is not shadowed by the `/api/integrations/**` key chain.
**Deviations:** Unauthenticated requests assert/return 403, not the 401 named in the task's TDD anchor — 403 is the verified project-wide convention for anonymous on authenticated() `/api/**` routes (`ProjectControllerIT.anyEndpoint_unauthenticated_returns403`); the session chain's AuthorizationFilter raises AccessDeniedException → 403. POST returns 200 (not 201) since it is an idempotent generate-or-regenerate overwrite of a single per-project row, not a pure resource creation. `application.properties` left untouched per task instruction.

**Reviews:**

*Round 1:*
- code-reviewer: 2 minor, both skip with justification (intentional local currentUserId duplication per task "не усложнять"; 200-vs-201 semantics) → [logs/working/task-8/code-reviewer-round1.json]
- security-auditor: OK, no findings to fix (requireOwned anti-IDOR/anti-enumeration, hash-only plaintext-once-no-logs, session-chain isolation + CSRF) → [logs/working/task-8/security-auditor-round1.json]
- test-reviewer: OK (all six TDD anchors + three extra anti-enumeration branches; behavior-focused assertions; no redundancy) → [logs/working/task-8/test-reviewer-round1.json]

No round 2 — no code changes required (all findings skip/keep with justification).

**Verification:**
- `./gradlew test --tests 'com.botfunnel.api.ApiKeyControllerIT'` → BUILD SUCCESSFUL, 9 tests pass.
- `./gradlew test --tests 'com.botfunnel.api.*' --tests 'com.botfunnel.project.*' --tests 'com.botfunnel.security.*'` → BUILD SUCCESSFUL, no regressions.
- Smoke (LIVE curl: POST with session cookie → plaintext once; GET → masked; Mongo `api_keys` stores hash+prefix only) DEFERRED to Task 15 (post-deploy verification) — requires a live app + login session. Fully covered here by `ApiKeyControllerIT` (real embedded Mongo + real session security chain via MockMvc): `generate_returnsPlaintextOnce` asserts DB holds only `keyHash`+`keyPrefix` (keyHash = SHA-256(plaintext)); `getAfterGenerate_returnsMaskedNoPlaintext` asserts the GET body never contains the plaintext. Exact deferred commands: `curl -s -b cookies.txt -c cookies.txt -X POST -H "X-XSRF-TOKEN: <token>" http://localhost:8080/api/v1/projects/<projectId>/api-key` → plaintext once; `curl -s -b cookies.txt http://localhost:8080/api/v1/projects/<projectId>/api-key` → `{present:true,mask:"prefix•••"}`; `db.api_keys.find({projectId:'<projectId>'})` → only keyHash+keyPrefix.
- Reviews performed via skill-loading fallback (Agent/Task spawn tool unavailable to subagents in this environment).

## Task 9: Funnel-editor Phase-3 UI (trigger selector + EMIT_EVENT form)

**Status:** Done
**Commit:** 63f3ff4
**Agent:** funnel-editor-ui
**Summary:** Replaced the static on_start badge in `FunnelTriggerSettings.vue` with a portable (Decision 11) trigger-type selector over the five types, rendering the value editor per type — on_start payload + deep-link, a keyword add/remove chip list bound to a `keywords` model, tag/field `SearchableSelect` pickers (lazy-fetched with the step form's error-swallow idiom), and an event-name slug input (UI label "api-event" → triggerType=event, Decision 4). Added `EMIT_EVENT` to `FunnelStepForm` (`STEP_TYPES` + slug-validated event-name block + `schemaFor`/`onSubmit`/`initialValues` branches). Extended `types/funnel.ts` (`StepType` gains `EMIT_EVENT`, `FunnelStep.eventName`, the `FunnelTriggerType` union, `keywords` on the funnel shapes) and round-tripped `triggerType`/`triggerValue`/`keywords` through `[funnelId].vue` (load seed + PATCH sends only the active type's value, never leaking a stale value).
**Deviations:** Reviews performed via skill-loading self-review (code-reviewing, test-master) instead of spawned subagents — nested Agent/Task spawning is unavailable to subagents in this environment (per task instruction). No security-auditor (frontend-only, per tech-spec Task 9). The pre-existing backend `application.properties` change was left unstaged/untouched.

**Reviews:**

*Round 1:*
- code-reviewer: approve, 2 minor accept-as-is (unchecked triggerType cast is a UX mirror; post-load no-op debounced PATCH is pre-existing behavior) → [logs/working/task-9/code-reviewer-round1.json]
- test-reviewer: approve, 1 minor accept-as-is (page-PATCH branching is verify-user/Task 15 scope, not Vitest) → [logs/working/task-9/test-reviewer-round1.json]

No round 2 — no code changes required (all findings accept-as-is with justification).

**Verification:**
- `node node_modules/vitest/vitest.mjs run tests/components/FunnelTriggerSettings.spec.ts tests/components/AddStepDialog.spec.ts` → 22 passed (7 new TDD-anchor tests + existing).
- `node node_modules/vitest/vitest.mjs run` (full frontend suite) → 402 passed, 48 files, no regressions.
- `node scripts/check-locales.mjs` (prebuild locale-parity gate) → exit 0 (uk/en key sets match).
- LIVE user check (funnel editor → trigger type selectable with matching value field; add-step dialog lists "Emit event" with event-name field) is for the user / Task 15 (verify-user).

## Task 10: API-key card in project settings

**Status:** Done
**Commit:** 7af33d3 (feat), bae2339 (fix round 1)
**Agent:** settings-apikey-card
**Summary:** Added an API-key card to the project general settings page (between General and Danger zone, Decision 8): "Generate" when absent, masked `prefix•••` + "Regenerate" when present, and a show-once "save this key" modal that surfaces the POST plaintext exactly once with copy-to-clipboard and a "won't be shown again" warning. Wired to the Task 8 GET (`{present,mask}`) and POST (`{apiKey,mask}`) endpoints via `useApi()`; the plaintext lives only in component state for the modal lifetime, is cleared on close, and is never persisted or logged. New i18n under `projects.settings.apiKey.*` + `errors.projects.apiKey.*` in uk.json/en.json (structurally identical, locale-parity gate green).
**Deviations:** Reviews performed via skill-loading self-review (code-reviewing, security-auditor, test-master) instead of spawned subagents — nested Agent/Task spawning is unavailable to subagents in this environment. The pre-existing backend `application.properties` change was left unstaged/untouched.

**Reviews:**

*Round 1:*
- code-reviewer: approve, 0 actionable (2 info) → [logs/working/task-10/code-reviewer-round1.json]
- security-auditor: approve, 0 findings → [logs/working/task-10/security-auditor-round1.json]
- test-reviewer: approve after fix, 1 minor (TR-1: missing in-flight disabled-button test) → [logs/working/task-10/test-reviewer-round1.json]

*Round 2 (after fix):*
- code-reviewer: approve, 0 findings → [logs/working/task-10/code-reviewer-round2.json]
- security-auditor: approve, 0 findings → [logs/working/task-10/security-auditor-round2.json]
- test-reviewer: approve, 0 findings → [logs/working/task-10/test-reviewer-round2.json]

**Verification:**
- `vitest run tests/pages/projects/settings-apikey.spec.ts` → 6 passed (none / masked / show-once modal / dismiss-no-linger / error / in-flight-disabled).
- `vitest run` (full frontend suite) → 408 passed (407 prior + 1 new file's net), no regressions.
- `node scripts/check-locales.mjs` (locale-parity gate) → exit 0.
- LIVE user check (settings → Generate shows key once; reload → masked + Regenerate) is for the user / Task 15 (verify-user).

## Task 12: Security Audit

**Status:** Done
**Agent:** security-auditor
**Summary:** Holistic OWASP Top 10 audit of the whole feature — verdict PASS (clean): 0 Critical, 0 High, 0 Medium, 2 Low (defense-in-depth). All seven priority surfaces confirmed sound: hash-only 256-bit-SecureRandom API-key storage with no plaintext compare/persistence and a non-leaking prefix mask; byte-identical bodyless 401 across all three key causes with no exploitable timing oracle; both-directions chain isolation (Order(1) STATELESS key chain vs Order(2) session chain, proven by ChainIsolationIT); Redis fail-open rate-limits backstopped by the Redis-independent depth cap (10) + fan-out ceiling (50) that bound recursion and width even with Redis down; project-scoped anti-IDOR subscriber resolution pinned from the key (both subscriber_id and telegram_user_id); event_name slug + one-identifier input validation; and ids/codes-only logging with no PII/secret leakage. The two Lows are non-blocking: EventIngressRequest omits the conventional @JsonIgnoreProperties(ignoreUnknown=true) (behaviorally safe — no owner/project field is read from the body, Boot ignores unknowns by default), and lookup() short-circuits null/blank before the DB query (no oracle for the only meaningful valid-vs-invalid-key distinction). No production code changed; this task IS the security gate. Report: [logs/working/audit/security-auditor.json].
**Deviations:** None.

## Task 13: Test Audit

**Status:** Done
**Agent:** test-auditor
**Summary:** Full-feature test-quality audit — verdict **PASS (clean)**, no critical/high/major findings; only 4 low/minor notes. Every required behavior in the coverage matrix is covered with litmus-passing assertions: all five trigger types, fan-out (1 firing → N executions), the three loop backstops each as an independent test (depth cap + fan-out ceiling exercised against genuinely-unavailable Redis, not a mocked boolean), the full /events response-code matrix (byte-identical uniform-401 across all three key causes, both 400 sub-paths, subscriber_id precedence, anti-IDOR 404, never-5xx, never-auto-create), both error-isolation paths (webhook 200 + JobRunr-not-failed; /events 202), both chain-isolation directions, and the index-relax migration (FunnelIndexesIT updated away from the old broad-filter assertion + reconciliation IT proving old-gone/event-coexist/on_start-collide + idempotent/never-throw). ITs use real embedded Mongo and a spied-but-live Redis (no DB mocking; no REDIS.stop()). Pyramid balance correct; no E2E (justified). Minor: the volume backstop's over-limit DROP is unit-only (no real-Redis IT), and enrollDepth/keywords round-trips are proven implicitly via service ITs. No fixes required before Task 14.
**Deviations:** None — read-only audit, no source/tests modified, no .md report written.

**Verification:**
- Read all Phase-3 test files (funnel unit + ITs, api/security ITs, webhook, subscriber, frontend Vitest) at audit time; full report in [logs/working/audit/test-auditor.json](logs/working/audit/test-auditor.json).
- Verdict: clean (low×4, no medium/high/critical). Follow-ups are optional, not blocking.

## Task 11: Code Audit

**Status:** Done
**Agent:** code-auditor
**Summary:** Holistic cross-component code audit of all source created/modified in Tasks 1-10 (35 production files + key tests, mapped via git diff 2212b64..HEAD) — verdict: issues (non-blocking). Confirmed shared-resource compliance (one FunnelEventService @Service singleton; FunnelExecutionFactory @Component sole owner of insertExecution/cancelExistingForPair, injected by both FunnelTriggerServiceImpl and FunnelEventService — no forked writer, no dispatcher bean cycle; all four trigger sources call the one dispatchForSubscriber), correct security-chain ordering (@Order(1) STATELESS key chain securityMatcher('/api/integrations/**') before @Order(2) session chain, no matcher overlap — ApiKeyController correctly on the session chain), uniform explicit-depth threading with NO ThreadLocal (roots=0, funnel-step callers=enrollDepth+1, threaded through the factory), three genuinely-independent backstops with distinct Redis keys (bf:rate:auto-enroll:{id} vs bf:rate:events), error-isolation try/catch(Throwable)+greppable PII-free WARN on every new path, byte-identical event_name slug across DTO/FunnelService/frontend, copyOf preserving eventName, and sound Task-2 BeanPostProcessor + Task-5 @Lazy deviations. Findings: 0 critical, 0 high, 1 medium, 3 low — all non-blocking (volume-counter increments before match lookup; fan-out drop-count log imprecision; triple subscriber lookup on the webhook keyword hot path; one dead-code no-finding). Report: [logs/working/audit/code-auditor.json].
**Deviations:** None. Read-only audit — no production code changed; auditor IS the review (reviewers empty per task).

## Task 14: Pre-deploy QA

**Status:** Done
**Agent:** pre-deploy-qa
**Summary:** Pre-deploy acceptance gate — verdict **GO**. Backend default `./gradlew test` = 1020/1020 green (2 skipped); slow `./gradlew test -PrunSlow=true` = 1078/1079 green incl. the `@Tag("slow")` index-migration ITs (`FunnelTriggerIndexReconciliationIT` 5/5, `FunnelIndexesIT` 6/6); frontend Vitest = 408/408 green + locale-parity exit 0. The single slow failure is `TelegramWebhookP99IT` (pre-existing Phase-1 latency probe, NOT in this feature's commit range; assertion `P99<100ms` got 100/467/461ms across runs — documented-flaky on a loaded workstation per build.gradle:48-55) — non-blocking environment artifact, SLA re-validated live in Task 15. All 23 acceptance criteria verified (user-spec 15 + tech-spec 8): **23 PASS, 0 FAIL**. Approved deviations (Task-2 BeanPostProcessor, Task-5 @Lazy, Task-8 403, fan-out ceiling, /api/integrations path, auto-enroll=depth>0) cross-checked — none reported as findings. No source/tests modified.
**Deviations:** None — read-only QA, no code/tests changed.

**Deferred to post-deploy (Task 15):** 7 live-only confirmations — each already proven by automated tests, deferred for live "feel"/HTTP/startup-log checks: keyword+menu precedence (Telegram), tag/field-fired funnels, `/events` curl matrix, EMIT_EVENT fan-out, index migration startup-log+listIndexes, API-key UI plaintext-once, and the P99 SLA on the author's machine. See §5 of the QA report.

**Verification:**
- `cd backend && ./gradlew test` → BUILD SUCCESSFUL, 1020 tests, 0 failures.
- `cd backend && ./gradlew test -PrunSlow=true` → 1079 tests, 1 failure (TelegramWebhookP99IT — flaky non-feature latency probe; isolated re-run also fails on load, not a resource cascade).
- `cd frontend && node node_modules/vitest/vitest.mjs run` → 408 passed (49 files); `node scripts/check-locales.mjs` → exit 0.
- Full report: [logs/working/task-14/qa-report.md](logs/working/task-14/qa-report.md)

## Task 15 finding — trigger auto-save 422

**Status:** Done
**Commit:** 54ac53d
**Agent:** trigger-autosave-fix
**Summary:** Live-verification (Task 15) finding: in the funnel editor page the debounced trigger auto-save only skipped persistence for on_start with an invalid value, so selecting any other type (keyword/tag_added/custom_field_set/event) fired a PATCH while the required value was still empty → backend 422 the instant the user changed the type. Fix: generalized the guard into a per-type `triggerReady()` readiness check (on_start → TRIGGER_VALUE_RE incl. empty; keyword → ≥1 keyword; tag_added/custom_field_set → non-empty trimmed triggerValue; event → EVENT_NAME_RE) and gate `scheduleTriggerPersist` on it — switching the type alone no longer auto-saves; activate() still flushes + persists so a genuine activate of an incomplete funnel surfaces the inline 422. Gate only — server validation is neither weakened nor duplicated.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: OK (0 findings) → [logs/working/task-15/code-reviewer-round1.json](logs/working/task-15/code-reviewer-round1.json)
- test-reviewer: OK (0 findings) → [logs/working/task-15/test-reviewer-round1.json](logs/working/task-15/test-reviewer-round1.json)

**Verification:**
- `npx vitest run tests/pages/funnel-editor.spec.ts` → 25 passed, 0 failed (5 new readiness specs + 20 pre-existing).
- `npx eslint` on both changed files → clean (exit 0).

## Task 15: Post-deploy verification (live, local run)

**Status:** Done
**Commit:** — (verification task, no code; the one finding shipped as 54ac53d above)
**Agent:** main agent (manual smoke by author)
**Summary:** Live verification run by the author against the local stack per work/12-funnels-triggers/smoke.md (manual hand-checklist derived from the AVP). Telegram/browser/curl behaviours exercised live; the index-migration live check was already proven during Task 2's boot smoke. One UX finding surfaced — trigger auto-save fired a 422 on type switch before required fields were filled — fixed under "Task 15 finding — trigger auto-save 422" (54ac53d) and re-verified manually. All other live checks behaved as specified.
**Deviations:** None beyond the already-approved ones (Task 2 BeanPostProcessor, Task 5 @Lazy, Task 8 unauth→403).

**Verification:**
- Manual smoke checklist: work/12-funnels-triggers/smoke.md — passed after the auto-save fix.
- Pre-deploy QA (Task 14): GO — backend 1020/1020, frontend 408/408, AC 23/23 PASS.
