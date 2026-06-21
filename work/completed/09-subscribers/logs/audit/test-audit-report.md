# Test Audit Report — Epic 09 Subscribers

## Verdict
BACKFILL_REQUIRED

## Summary
The Epic 09 test surface is, on the whole, of high quality: integration tests extend the real `AbstractIntegrationTest` (Mongo + Redis + Mailpit), assertions verify persisted documents / event rows / GridFS metadata rather than mere status codes, all six race surfaces use `ConcurrencyTestUtils.parallelInvoke`, and the unit layer (state machine, segment-filter, CSV writer, signed-token, validators) is genuinely litmus-resistant. One **P0** blocks QA: **AC7 cross-project isolation has no automated test** — the `CrossProjectIsolationIT` the user-spec and R5 mitigation explicitly named was never created, and no other test seeds the same `telegramUserId` into two projects. Three **P1** items (missing `IndexCreationIT`, missing list-latency `@Tag("slow")` probe for AC13, and a mock-only `*IT` that deviates from its spec) and several **P2** deviations follow. Headline: **1 P0, 3 P1, 6 P2**.

## AC Coverage Matrix
| AC | Covered by (file::method) | Assertion summary | Status |
| ---- | -------------------------------------------------- | ------------------------------------------ | ------ |
| AC1  | SubscriberServiceImplIT.firstStart_persistsActiveSubscriberAndEvent; SubscriberEventsIsolationIT.registerWritesOnlyToSubscriberEvents | Mongo doc status=ACTIVE + chatId/botId/subscribedAt + empty tags; event `subscriber_registered` in subscriber_events only | OK* |
| AC2  | SubscriberServiceImplIT.startAfterBlocked_reactivates...; SubscriberStatusMachineTest.unsubscribedToActive_onStart_preservesSubscribedAt, reactivation_fromBlocked_clearsBlockedAt | status→ACTIVE, subscribedAt preserved, tags+customFields preserved, blockedAt $unset, event `subscriber_reactivated{previousStatus}` | OK |
| AC3  | SubscriberStatusMachineTest.activeToUnsubscribed_writesEventAndTimestamp, markUnsubscribed_onAlreadyUnsubscribed_isNoop; SubscriberEventsIsolationIT.unsubscribeWritesOnlyToSubscriberEvents | status=UNSUBSCRIBED + unsubscribedAt; event `{reason:command_stop}`; idempotent re-/stop writes no 2nd event | OK |
| AC4  | SubscriberManualUnsubscribeIT.unsubscribe_active_flips..., _alreadyUnsubscribed_returns409..., _thenReactivationViaStartCommand...; SubscriberStatusMachineTest.unsubscribeManual_* | Mongo flip + event `{reason:manual}`; 409 `already_unsubscribed` (no dup event); reactivation via /start | OK |
| AC5  | SubscriberPersonalMessageIT.send_403blocked..., send_400chatNotFound..., send_400otherReason..., send_429AfterRetryExhausted...; SubscriberStatusMachineTest.markBlocked/markDeleted | 403→BLOCKED+`subscriber_blocked`; 400→DELETED+`subscriber_deleted`; 400-other→`personal_message_failed`; 429→503 `telegram_rate_limited` | OK |
| AC6  | SubscriberRateLimitIT.parallel101DistinctUsers..., existingSubscriberRestart200Times..., rateLimit_isolatedPerProject, redisDown_failsOpen... | 101→100 persisted + 1 `rate_limit_exceeded`; existing bypass; fail-open + WARN constant; cross-project key isolation | OK |
| AC7  | **none** (CrossProjectIsolationIT does not exist) | Same `telegramUserId` in Project A & B → 2 separate Subscriber docs with independent tags — **untested** | **MISSING** |
| AC8  | TagControllerIT (create/dup409/regex400/patch-label/delete-cascade+event/idempotent/access); TagServiceTest (findOrCreate/race/incrementCounter-atomic/deleteWithCascade); TagSlugValidatorTest | slug regex 400, 409 `tag_name_taken`, denormalized counter inc/dec, sync cascade + `tag_deleted` event | OK† |
| AC9  | SubscriberTagAssignmentIT.addTag_happyPath..., _idempotentReattach..., removeTag..., addSameSlug_concurrentlyToSameSubscriber...; SubscriberServiceImplIT.addTag/removeTag | idempotent add/remove, counter ±1 on actual change, events `subscriber_tag_added/removed`, race→counter==1 | OK |
| AC10 | CustomFieldDefinitionIT (create/dup409/regex400/20-cap-422/parallel21/type-mismatch-default-422/label-edit/type+name-immutable/delete-cascade/access) | 20-cap 422, 409 name conflict, default per-type 422, immutability enforced (silent drop), delete cascade | OK† |
| AC11 | CustomFieldValueValidationIT (valid-normalized+event/value-equal-no-event/unknown-keys-dropped/all-unknown/per-type-422/null-clears/cross-project-404/foreign-404); CustomFieldValueValidatorTest | per-type validation 422 `custom_field_type_mismatch`; mass-assignment unknown keys silently dropped (persist only schema keys) | OK |
| AC12 | CustomFieldDefinitionIT.delete_removesDefinition_andCascadesValuesFromSubscribers (12 subs), delete_definitionWithNoSeededValues...(count 0) | definition removed + value purged from all 12 subscribers + event `removedValueCount=12` | OK |
| AC13 | SubscriberControllerIT (empty/full50/finalPage/textSearch/under2-400/status/tagsInclude-AND/tagsExclude-NOT/dateRange/cursor3pages/tie-break/cursor-injection×4/access); SegmentFilterBuilderTest | full query-param matrix + cursor pagination + tie-break + NoSQL-injection rejection. **P95<300ms latency probe absent** | OK‡ |
| AC14 | SubscriberProfileIT.get_existing..., events_returnsLast50SortedDesc, events_limitMax200_clamped | profile identity+tags+customFields+status+dates; events feed sorted desc + limit clamp | OK |
| AC15 | SubscriberPersonalMessageIT.send_happyPath..., send_textBlank_returns400, send_text4097chars..., rate_limited_returns429...; TelegramTextValidatorTest | 1..4096 validation, blank/4097 reject (no outbound call), happy `personal_message_sent`, 61st→429 | OK |
| AC16 | SubscriberExportIT.happyPath..., exportJob_internalError..., emptyFilter..., filterTooLarge_returns422, estimateDropsTextSearchCriterion; SubscriberCsvWriterTest | 202+exportId, DONE+rowCount+GridFS metadata+email+event; FAILED scrub+orphan-cleanup; BOM+header CSV | OK |
| AC17 | SubscriberExportConcurrencyIT.parallel2Posts_status202_409_oneRowOneJob | parallel POST → {202,409}, exactly 1 PENDING row, exactly 1 enqueued job (partial-unique index) | OK |
| AC18 | SubscriberExportIT.filterTooLarge_returns422; SubscriberExportEstimateLatencyIT.estimateP95Under200ms_at100k (@Tag slow) | >200k → 422 `export_filter_too_large`; indexed-only estimate P95<200ms over 50 runs at 100k | OK |
| AC19 | SubscriberExportSignedUrlIT (happy/tampered401/expired410/softDeleted410/purged410/crossProject401/rateLimit31→429/refresh-matrix/unauth403); SignedDownloadTokenTest | HMAC verify, expiry, cross-project payload mismatch, 30/min rate-limit, audit reason enum on every non-200 | OK |
| AC20 | ExportCleanupJobIT.recurringRegistration..., cleanup_donePast7d_purgesAndEmits, cleanup_alreadyPurged_idempotent, cleanup_doneWithin7d_notPurged | cron `0 3 * * *`, purge DONE>7d → PURGED + GridFS gone + event, idempotent, within-7d untouched | OK |
| AC21 | Access-guard cases in SubscriberControllerIT/TagControllerIT/CustomFieldDefinitionIT/CustomFieldValueValidationIT/SubscriberProfileIT/ProjectSoftDeleteCascadeIT; SubscriberStatusMachineTest.unsubscribeManual_foreignProject_throws404 | foreign owner / soft-deleted / malformed projectId → uniform 404 across every endpoint family | OK |
| AC22 | ProjectHardDeleteJobIT (cascadesAllSubscriberDomain.../partial-recovery/orphan-blob/young-project/structured-order-line/recurring); ProjectSoftDeleteCascadeIT; SubscriberExportSignedUrlIT.softDeletedProject_returns410 | soft→404, signed-URL→410 `export_project_unavailable`, hard-delete drains all 4 collections+GridFS, mid-cascade resume idempotent | OK |
| AC23 | SubscriberEventsIsolationIT.register/unsubscribeWritesOnlyToSubscriberEvents; TagServiceTest.deleteWithCascade (no subscriber_events write) | CRM events ONLY in subscriber_events; platform `events` keeps telegram_command_* and never a `subscriber_*` row | OK |
| AC24 | SubscriberStubReplacementIT.onlyOneSubscriberServiceBean_andItIsSubscriberServiceImpl | `getBeansOfType(SubscriberService).size()==1` + single bean is `SubscriberServiceImpl` | OK |

\* AC1: registration + status/dates/tags/event asserted; identity fields (firstName/lastName/username/languageCode) extracted-from-update are NOT re-asserted post-registration (covered indirectly by 04b `ProcessTelegramUpdateJobTest`); webhook-to-Mongo P95<500ms sub-SLA not separately probed — see P2.
† AC8/AC10: immutability holds & is tested, but via silent `@JsonIgnoreProperties` drop, not the spec's `422 tag_slug_immutable` / `custom_field_immutable` — see P2.
‡ AC13: full functional behavior covered; the `@Tag("slow")` P95<300ms latency probe (`SubscriberControllerLatencyIT`) is absent — see P1.

## @Tag("slow") Audit
| File | Has @Tag("slow") | Expected | Verdict |
| ------------------------------------- | ---------------- | -------- | ------- |
| SubscriberControllerLatencyIT | n/a — file does not exist | yes | **MISSING (P1)** |
| SubscriberExportEstimateLatencyIT | yes | yes | OK |
| SubscriberExportHeapIT | yes | yes | OK |
| SubscriberExportFilterEstimateTest | n/a — file does not exist | yes (tech-spec L379) | DEVIATION (P2) — concern folded into SubscriberExportEstimateLatencyIT |
| TelegramWebhookP99IT | yes | n/a (Epic 04b, pre-existing) | OK — correct usage, out of Epic 09 scope |

No fast test carries an unwarranted `@Tag("slow")`; the only tagged tests are genuine latency/heap probes, so no real coverage is hidden from the default `./gradlew test`.

## Concurrency Idiom Audit
| Race surface | Test | Uses parallelInvoke | Verdict |
| ----------------------------------------------- | ----------------------------------------------------- | ------------------- | ------- |
| Rate-limit 101 parallel /start | SubscriberRateLimitIT.parallel101DistinctUsers_persists100AndOneRateLimitEvent | yes | OK |
| Cross-project rate-limit isolation | SubscriberRateLimitIT.rateLimit_isolatedPerProject | yes | OK |
| Insert race (same user, same project) | SubscriberServiceImplIT.duplicateKeyException_onInsertRace_recoversAndUpdates | yes | OK |
| Tag counter race (idempotent re-attach) | SubscriberTagAssignmentIT.addSameSlug_concurrentlyToSameSubscriber_counterEqualsOne | yes | OK |
| Tag counter race (50 atomic increments, no lost updates) | TagServiceTest.incrementCounter_concurrentIncrements_areAtomic | yes | OK |
| Tag find-or-create race (8 callers) | TagServiceTest.findOrCreate_raceDuplicateKey_recoversAndReturnsExisting | yes | OK |
| 20-cap CustomField race | CustomFieldDefinitionIT.parallel21Posts_acceptsOnly20 | yes | OK |
| Export 2-parallel in-flight | SubscriberExportConcurrencyIT.parallel2Posts_status202_409_oneRowOneJob | yes | OK |
| Download rate-limit 31-parallel | SubscriberExportSignedUrlIT.downloadRateLimit_parallel31Requests_one429 | yes | OK |

All race surfaces use `ConcurrencyTestUtils.parallelInvoke` (virtual threads + `CountDownLatch` barrier per `patterns.md:181`). No naive `Thread.start` loops anywhere. The spec named the tag-counter race as `TagControllerIT.addSameTag_concurrentlyFrom10Subscribers_counterEqualsExactly10`; the implementation split it across two stronger tests (idempotent-reattach→1 + 50-concurrent-inc→50), which together cover both the over-count and lost-update directions. Benign deviation.

## IT Environment Audit
| IT class | Extends AbstractIntegrationTest | @MockBean on Mongo/Redis/Mail? | Verdict |
| --------------------------------- | ------------------------------- | ------------------------------ | ------- |
| SubscriberServiceImplIT | yes | none | OK |
| SubscriberRateLimitIT | yes | `@MockitoSpyBean StringRedisTemplate` (delegating spy, reset per test; stubs `opsForValue()` only in the fail-open case) | OK — fault injection, not degradation |
| SubscriberControllerIT | yes | none | OK |
| SubscriberPersonalMessageIT | yes | none (MockWebServer for Telegram only) | OK |
| SubscriberManualUnsubscribeIT | yes | none | OK |
| SubscriberProfileIT | yes | none | OK |
| SubscriberEventsIsolationIT | yes | none | OK |
| SubscriberStubReplacementIT | yes | none | OK |
| SubscriberTagAssignmentIT | yes | none | OK |
| TagControllerIT | yes | none | OK |
| TagServiceTest (real-Mongo IT) | yes | none | OK |
| CustomFieldDefinitionIT | yes | none | OK |
| CustomFieldValueValidationIT | yes | none | OK |
| SubscriberExportIT | yes | `@MockitoSpyBean MongoTemplate` (delegating spy; `doThrow` on `stream()` and `doReturn` on `count()` for fault injection) | OK — fault injection, not degradation |
| SubscriberExportConcurrencyIT | yes | none | OK |
| SubscriberExportSignedUrlIT | yes | `@MockitoSpyBean StringRedisTemplate` (delegating spy; stubs `opsForValue()` only in the redis-down refresh case) | OK — fault injection, not degradation |
| SubscriberExportEstimateLatencyIT | yes | none | OK |
| SubscriberExportHeapIT | yes | none | OK |
| ExportCleanupJobIT | yes | none | OK |
| ProjectSoftDeleteCascadeIT | yes | none | OK |
| ProjectHardDeleteJobIT | yes | none | OK |
| **TelegramSenderSubscriberHookIT** | **no** (plain JUnit, no Spring context) | mocks SubscriberService + EventService + BotRepository entirely | **FLAG (P1)** — mock-only unit test mislabeled `*IT`; deviates from tech-spec L401 |

No IT uses `@MockBean` to replace a Mongo/Redis/Mail bean. The three `@MockitoSpyBean`s are delegating spies used for fault injection (Redis-down, mid-export Mongo failure) — they keep real behavior by default and `Mockito.reset(...)` in `@BeforeEach`, so they do **not** degrade an IT to a unit test. The sole environment flag is `TelegramSenderSubscriberHookIT` (detailed under P1).

## Security Regression Coverage
| Security item | Cited test | Asserts | Verdict |
| -------------------------------------- | --------------------------------------------------------- | ------ | ------- |
| Mass-assignment (tag) | TagControllerIT.create_hostileBodyWithOwnerId_ignoresMassAssignment | persisted projectId from requireOwned, body ownerId/projectId/subscriberCount dropped, no leak into hostile project | OK |
| Mass-assignment (personal message) | SubscriberPersonalMessageIT.create_hostileBodyMassAssignment_dropped | extra keys dropped, status stays ACTIVE, exactly 1 send | OK |
| Mass-assignment (custom-field PATCH) | CustomFieldValueValidationIT.patch_unknownKeys_silentlyDropped_noError_noPersist, patch_allKeysUnknown... | persisted map contains only schema keys; no event for all-unknown | OK |
| NoSQL-injection cursor | SubscriberControllerIT.cursor_invalidShape_returns400, cursor_dollarPrefixedKeys..., cursor_unknownKeys..., cursor_idNot24..., cursor_vNotPrimitive...; SegmentFilterBuilderTest.decodeCursor_* | 400 `invalid_cursor` + zero leaked rows; $-key walk, unknown-key, id-regex, primitive-type guards isolated | OK (exceeds spec) |
| Formula-injection cells | SubscriberCsvWriterTest.formulaInjection_equalsSign/atSign/minusSign/tabAndCR_prefixed, _controlCase_safeFirstName_notPrefixed, _wholeCellHelper... | every `= + - @ \t \r` lead cell gets apostrophe prefix; control case (`Iva`) unprefixed; JSON `{`-lead cell unprefixed | OK |
| Signed-URL audit invariants (Decision 16) | SubscriberExportSignedUrlIT.tamperedToken/expiredToken/softDeletedProject/purgedExport/crossProjectSubstitution/downloadRateLimit... | `subscribers_export_download_denied` with `reason` ∈ {invalid_token, expired, project_unavailable, purged, rate_limited} on every non-200 | OK |
| FAILED-export token scrub | SubscriberExportIT.exportJob_internalError_writesFailedAndEmail | errorMessage `[REDACTED_TOKEN]`, no raw token, ≤1024 chars, orphan GridFS blob deleted | OK |
| Rate-limit cross-project isolation | SubscriberRateLimitIT.rateLimit_isolatedPerProject | 100+100 distinct users in two projects → all 200 persisted, zero rate-limit events (regression-catch for dropped `{projectId}` key) | OK |
| HMAC primitive (constant-time, ordering, key validation) | SignedDownloadTokenTest (8 cases) | tamper/wrong-key/cross-project→BAD_SIGNATURE, expired→EXPIRED, sig-before-expiry ordering, fail-fast key parse | OK |

Security-regression coverage is the strongest area of the suite — it meets or exceeds every item the tech-spec enumerated.

## Pyramid Balance
Counts (Epic 09 surface): **unit ≈ 7 backend classes** (`SubscriberStatusMachineTest`, `SegmentFilterBuilderTest`, `SubscriberCsvWriterTest`, `CustomFieldValueValidatorTest`, `TelegramTextValidatorTest`, `TagSlugValidatorTest`, `SignedDownloadTokenTest`) **+ frontend Vitest** (4 named dialog/filter specs + ~13 supporting component/page/store specs); **integration ≈ 20 backend `*IT`/real-Mongo classes**; **E2E = 1** (`subscribers.spec.ts`).

Verdict: **balanced**, leaning integration-heavy — which is correct and justified for this feature. The tech-spec's own rationale (L311) is that this L-feature crosses webhook/sender/JobRunr/GridFS/Redis/Email seams where mocks would assert nothing; the heavy IT layer asserts real persistence, not mock calls. Pure logic (state machine, filter builder, CSV escape, signed token, validators) is correctly pushed down to fast unit tests. No inverted ratios observed: no IT does pure-regex work that belongs in a unit test, and the single E2E covers a flow (webhook→list→tag→custom-field→export→download→unsubscribe) that no IT replicates end-to-end. Notes: cursor NoSQL-injection is intentionally double-covered (unit `SegmentFilterBuilderTest` + integration `SubscriberControllerIT`) — justified, since the unit isolates the codec and the IT proves the controller wires it.

## Findings

### P0 (blocks deploy)
- **AC7 cross-project isolation — no automated test (CrossProjectIsolationIT absent).** The user-spec (AC7) and risk R5 both name `CrossProjectIsolationIT` as the explicit verification that the same `telegramUserId` writing `/start` to Project A and Project B yields **two separate** Subscriber documents with independent tags/customFields. No such file exists, and no other test exercises this: `SubscriberServiceImplIT.duplicateKeyException_onInsertRace` proves uniqueness *within* one project (the opposite direction); `SubscriberRateLimitIT.rateLimit_isolatedPerProject` uses *distinct* user-id ranges (100_000+ vs 200_000+), not the same id; `CustomFieldValueValidationIT.patch_crossProjectSubscriber_returns404` proves access *scoping*, not the 2-docs creation invariant. **Mitigation in place:** the unique compound index `(projectId, telegramUserId)` is confirmed present via manual `mongosh` inspection (decisions.md Task 1), so the live behavior is almost certainly correct — but a regression that dropped `projectId` from the key (exactly the R5 scenario) would pass the entire automated suite. Not acknowledged as a deviation in decisions.md. **Suggested fix:** add `CrossProjectIsolationIT` — seed a `/start` for the same `telegramUserId` against two projects (different bots, same owner) via `processTelegramUpdateJob.handle(...)`, assert `subscriberRepository.count()==2`, each scoped to its own `projectId`, and that tags added in A do not appear in B. ~1 small IT.

### P1 (backfill before next wave)
- **IndexCreationIT — absent (tech-spec L402, tech-AC L488, R14).** No automated test asserts the index shapes on bootstrap: subscribers unique `(projectId, telegramUserId)`, text `language="none"`, `subscriber_events` TTL `expireAfter==365d`, `subscriber_exports` partial-unique `{status:{$in:[PENDING,RUNNING]}}`. Functional behaviors are *partially* exercised — partial-unique via `SubscriberExportConcurrencyIT`, within-project uniqueness via `SubscriberServiceImplIT.duplicateKeyException`, text-search via `SubscriberControllerIT.list_textSearch` — but TTL=365d, the exact byte-shape of the partial filter, and `projectId`-in-unique-key (overlaps the AC7 P0) have **no regression catch**. Currently protected only by a one-time manual `mongosh` check (decisions.md Task 1). **Suggested fix:** autowire `MongoMappingContext` / `IndexOperations` and assert each index's properties as tech-spec L402 describes.
- **SubscriberControllerLatencyIT — absent (AC13, tech-spec L407).** The list endpoint's P95<300ms SLA at 1000 subscribers has no `@Tag("slow")` probe. The endpoint's *functional* behavior is fully covered by `SubscriberControllerIT`, so this is a performance-SLA gap, not a behavioral one. **Suggested fix:** add `SubscriberControllerLatencyIT` (`@Tag("slow")`, seed 1000, 100-request burst, assert P95<300ms) mirroring `SubscriberExportEstimateLatencyIT`.
- **TelegramSenderSubscriberHookIT — mock-only test mislabeled `*IT`; deviates from tech-spec L401.** The class is plain JUnit (no Spring context) and mocks `SubscriberService`, `EventService`, and `BotRepository`. It only `verify()`s that `markBlockedByChatId(...)` / `markDeletedByChatId(...)` were *called* and the audit-before-mark `InOrder` — it never asserts the real Mongo status flip or the `subscriber_blocked` / `telegram_send_failed` rows, which tech-spec L401 explicitly mandated for this named IT (`@MockitoSpyBean` + direct Mongo + event-row assertions). **Not a coverage gap:** the actual 403→BLOCKED+event and 400→DELETED+event end-to-end flips ARE proven by `SubscriberPersonalMessageIT` (real IT, real Mongo). The defect is the misleading `*IT` suffix and the absent spec-mandated state assertions. **Suggested fix:** rename to `TelegramSenderSubscriberHookTest` (it is a valid, well-scoped unit test of the sender's hook wiring), OR promote it to a real `AbstractIntegrationTest` with the direct-state assertions the spec described.

### P2 (nice-to-have)
- **`tag_slug_immutable` / `custom_field_immutable` error codes are dead.** AC8 and AC10 specify a `422` with these codes on slug/name/type change; the implementation enforces immutability by silently dropping the stray field via `@JsonIgnoreProperties` (`TagControllerIT.patch_unknownSlugInBody_silentlyDropped`, `CustomFieldDefinitionIT.update_attemptToChangeType/Name_silentlyIgnored` — both assert 200 + value unchanged). The invariant holds and is tested, but the named 422 paths are never exercised and the error codes appear unused. Surface to completeness/code audit.
- **`SubscriberExportFilterEstimateTest` (unit, tech-spec L379) does not exist as a separate file.** Its AC18 estimate-latency concern is folded into `SubscriberExportEstimateLatencyIT` (an IT). Acceptable consolidation; the AC18 SLA is covered.
- **Cascade ORDER is asserted via a single structured summary log line (per-collection counts), not per-step ordered `ListAppender` lines** as tech-spec L399 described. The order-critical invariant (GridFS swept by `metadata.projectId` regardless of pointer row) is separately proven by `ProjectHardDeleteJobIT.cron_gridFsFileWithoutExportPointer_isStillSwept`. Acceptable deviation.
- **`ProjectSoftDeleteCascadeIT.softDeletedProject_subscribersListEndpoint_returns404` uses a `requireOwned`-direct workaround with a stale TODO** ("replace once the subscriber list endpoint lands"). That endpoint now exists and `SubscriberControllerIT.list_softDeletedProject_returns404` already covers the real HTTP surface — the workaround is harmless but stale.
- **AC1 identity-field population from the webhook is not re-asserted post-registration.** `firstStart_persistsActiveSubscriberAndEvent` asserts status/dates/tags/event but not that `firstName/lastName/username/languageCode` were extracted from the update (covered indirectly by Epic 04b `ProcessTelegramUpdateJobTest`); the AC1 webhook-to-Mongo P95<500ms sub-SLA has no dedicated probe.
- **Over-coverage (not defects, surfaced per task instruction).** Extra frontend specs beyond the four named (`ExportCsvDialog`, `RecentExportsDialog`, `SubscriberTagsTab`, `SubscriberCustomFieldsTab`, `SubscriberHistoryTab`, `subscribers-index`, `subscribers-profile` pages, `stores/subscribers`, `types/subscriber-filters`, `utils/subscriberStatus`) and extra backend units (`CustomFieldValueValidatorTest`, `TelegramTextValidatorTest`, `TagSlugValidatorTest`) — all welcome.

## Notes
- **E2E runs only with bootstrap env + live backend.** `frontend/e2e/subscribers.spec.ts` `test.skip()`s the entire golden-path spec when `/health` is down or `E2E_SUBSCRIBERS_PROJECT_ID / E2E_WEBHOOK_SECRET / E2E_LOGIN_EMAIL / E2E_LOGIN_PASSWORD` are absent (documented Task 11 AC: must not fail the suite). The `test.skip` calls are whole-test infra guards, **not** per-step skips — all 8 documented steps are present and asserted (login→empty-state, webhook-seed→rows, search, profile-tabs, add-tag→/tags counter, custom-field set "Київ", export→DONE→download→parse BOM+Cyrillic, manual-unsubscribe→badge flip). **Consequence for Pre-deploy QA:** in a default CI run without that bootstrap, the golden path provides **zero** regression protection. Pre-deploy QA (Task 15) must execute this spec against a seeded live backend (or the staging-smoke runbook) or the end-to-end golden path goes unverified.
- **Default `./gradlew test` stays fast:** the only `@Tag("slow")` Epic 09 tests are the estimate-latency and heap probes; CI must run them via `-PrunSlow=true` to cover AC18.
- **Backfill signal for the team lead (feature-execution):** one P0 is present → per this task's Post-completion step 3, Pre-deploy QA (Task 15) should not start until `CrossProjectIsolationIT` is backfilled. The P0 is low-effort (one small IT) and the underlying behavior is likely correct (index verified present), so the lead may choose to backfill in place before unblocking Wave 6 rather than treating it as a deep defect.
