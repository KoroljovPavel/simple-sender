# Code Research: 12-funnels-triggers (Phase 3)

Research date: 2026-06-07. Source paths absolute. Backend root:
`/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel`.
Test root: `.../backend/src/test/java/com/botfunnel`.
Frontend root: `/Users/pavlokorolov/IdeaProjects/simple-sender/frontend`.

## TL;DR — plan-impacting findings (read first)

1. **There is NO `api` module and NO `api_keys` / `ApiKey` domain.** The public-API foundation is
   greenfield. `grep` for `api_keys`/`ApiKey` finds only `/api/v1/...` request-mappings (the existing
   SESSION-authenticated app API).
2. **Path-prefix collision.** The plan says api-key auth filter on `/api/v1/**`. But the ENTIRE
   existing app API (projects, bots, subscribers, funnels, custom-fields, exports) already lives under
   `/api/v1/**` and is SESSION-authenticated. `SecurityConfig` has ONE filter chain with
   `.requestMatchers("/api/**").authenticated()`. You cannot blanket-apply an api-key filter to
   `/api/v1/**` without breaking the SPA. **Recommend** a distinct prefix for the public endpoint
   (e.g. `/api/public/v1/events` or `/api/v1/events` carved out as its own chain via
   `@Order` + `securityMatcher`). This is a tech-spec decision the plan understates.
3. **`com.botfunnel.events` is an audit-log, NOT a pub/sub bus.** `EventService.logEvent(...)` just
   `save()`s an `Event` document (platform audit). `SubscriberServiceImpl.writeSubscriberEvent` writes
   to a SEPARATE `subscriber_events` collection. **Neither is a dispatcher** — nothing consumes these
   to fire funnels today. The EMIT_EVENT/api-event/tag_added/custom_field_set "internal pub/sub" must
   be built new. The naming clash ("events") is a real readability risk: the new funnel-event namespace
   is distinct from both the `events` audit collection and `subscriber_events`.
4. **Single `fire(...)` entrypoint** today: `FunnelTriggerServiceImpl.fire(projectId, chatId,
   triggerType, payload)` keyed on (chatId → subscriber). New triggers (tag_added,
   custom_field_set, api-event, EMIT_EVENT) often start from a **subscriberId**, not a chatId — the
   trigger service will need a subscriber-keyed entrypoint, not just the chatId one.
5. **`addTag`/`removeTag`/`recordCustomFieldsSet` ARE the sole writers** (verified: exactly 2 call
   sites each — the manual controller + the funnel `StepExecutor`). They are the correct single hook
   point for tag_added / custom_field_set triggers. **Cascade/loop risk is real**: a funnel ADD_TAG
   step calls `addTag`, which would fire a tag_added trigger, which starts another funnel, whose
   ADD_TAG fires again. Loop protection must be designed (see §4).
6. **Trigger matching is exact-match on `(triggerType, triggerValue)`** via a partial-unique index.
   Keyword "contains-match, any-of-multiple-keywords" does NOT fit the single-string `triggerValue`
   exact-match model. Keyword triggers need a different lookup (scan active keyword funnels for the
   project, contains-match in code) — they cannot reuse `findByProjectIdAndTriggerTypeAndTriggerValueAndStatus`.
7. **The 1:1→1:N relax is straightforward**: the partial-unique index is declared as an annotation on
   `Funnel.java` (`@CompoundIndex ... unique=true partialFilter "{'status':'active'}"`). With
   `auto-index-creation=true`, changing the annotation does NOT drop the old index — a startup
   reconciliation migration (FunnelStepIdBackfill pattern) must `dropIndex` the old one. See §2, §7.

---

## 1. Funnel trigger engine — `com.botfunnel.funnel`

### `FunnelTriggerServiceImpl` (`funnel/FunnelTriggerServiceImpl.java`)
The real `@Service` (interface `FunnelTriggerService.java`). `fire()`/`cancelActiveFor()`/
`advanceOnCallback()` are all `try/catch(Throwable)` error-isolated and NEVER throw outward (a funnel
fault must not poison the webhook worker).

- **`fire(String projectId, Long chatId, String triggerType, String payload)`** — lines 119-171.
  Flow: (1) resolve project's CONNECTED `Bot` via `botRepository.findByProjectIdAndStatus`
  (line 123); pin `telegramBotId`. (2) resolve `Subscriber` via
  `subscriberService.findByChat(projectId, telegramBotId, chatId)` (line 133) — NEVER the repo
  directly (module boundary). (3) **exact-match** the active funnel:
  `funnelRepository.findByProjectIdAndTriggerTypeAndTriggerValueAndStatus(projectId, triggerType,
  triggerValue, FunnelStatus.active)` (line 142) where `triggerValue = payload==null?"":payload`.
  No match → no-op. (4) re-enter guard (line 152): `allowReEnter` → `cancelExistingForPair` then
  `insertExecution`; else `insertExecution` and swallow `DuplicateKeyException` as benign re-enter
  no-op.
  - **Extension point for new triggers:** `fire` is chatId-keyed and assumes EXACTLY ONE matching
    funnel (`.orElse(null)`). For fan-out (1 event → N funnels) and for subscriber-keyed triggers
    (tag/field/api/emit) you need a new entrypoint, e.g.
    `fireForSubscriber(projectId, subscriberId, triggerType, eventName)` that (a) resolves the
    subscriber by id (skip the chatId lookup), (b) queries a LIST of active matching funnels (no
    `.orElse(null)`), (c) loops `insertExecution` per funnel. `insertExecution`/`cancelExistingForPair`
    are reusable as-is (they already take `subscriberId`).
- **`insertExecution(projectId, funnel, subscriberId, telegramBotId)`** — lines 426-448. Builds a
  fresh `FunnelExecution`: status=running, currentStepIndex=0, stepRunStatus=pending, nextRunAt=now,
  `stepsSnapshot = deepCopySteps(funnel.getSteps())` (Decision 3 snapshot isolation via
  `FunnelStep.copyOf`), `currentStepId = snapshot.get(0).getId()`. Uses `mongoTemplate.insert` so a
  unique-index collision surfaces as `DuplicateKeyException` (the re-enter guard for
  `allowReEnter=false`). **This is THE execution-creation path — reuse verbatim for all new triggers.**
- **`cancelActiveFor(projectId, chatId)`** — lines 173-205. `updateMulti` flips all running|waiting|
  waiting_for_reply executions of the subscriber → cancelled (used by `/stop`).
- **`advanceOnCallback(...)`** — lines 207-314. Phase-2 callback path; strict callback_data parse,
  anti-IDOR owner check, delegates to `executionEngine.resumeOnCallback`. Not directly relevant to
  Phase 3 but shows the established error-isolation + greppable-log conventions to mirror.
- **Re-enter guard** = the partial-unique index `(funnelId, subscriberId)` filtered status IN
  [running, waiting, waiting_for_reply] on `funnel_executions` (NOT a query-time check; that would
  race two concurrent `/start`s). Declared on `FunnelExecution.java` (see §2). `allowReEnter` is a
  per-funnel boolean on `Funnel`.
- Logging: greppable named constants, ids/codes only, never payload/PII (Decision 16). Statuses
  written as lowercase `.name()` literals (Decision 14) to byte-match indexes.

### `FunnelExecutionEngine` (`funnel/FunnelExecutionEngine.java`)
JobRunr `@Recurring(id="funnel-sweep", interval ${app.funnel.scheduler-interval:PT30S})` sweep
(line 91). Claims due executions via `findAndModify` CAS (`stepRunStatus pending→in_progress`),
drives steps through `StepExecutor`. `resumeOnCallback(executionId, subscriberId, targetStepId)`
(line 181) is the callback-resume entrypoint with subscriberId-scoped CAS. The engine is unchanged by
Phase 3 except EMIT_EVENT step dispatch happens inside `StepExecutor.execute` (engine just sees a
CONTINUE outcome). `drive()` per-tick budget = `app.funnel.max-steps-per-tick:100` (loop guard).

### `FunnelRepository` (`funnel/FunnelRepository.java`)
- `findByProjectId`, `findByProjectIdAndStatus`,
  `findByProjectIdAndTriggerTypeAndTriggerValueAndStatus` (exact-match, used by `fire`).
- **New queries needed:** keyword funnels need a list query like
  `findByProjectIdAndTriggerTypeAndStatus(projectId, "keyword", active)` (returns ALL, then
  contains-match in code over a per-funnel keyword list). Event/tag/field triggers (post-relax) need a
  LIST variant of the trigger lookup (drop the `.orElse(null)` single-result assumption).

---

## 2. Funnel domain + indexes

### `Funnel` (`funnel/Funnel.java`)
- Trigger fields: `triggerType` (String, "on_start"), `triggerValue` (String, exact-match key,
  ""=bare /start), `allowReEnter` (boolean, default false). Lines 50-52.
- `steps: List<FunnelStep>` (line 54). `status: FunnelStatus`.
- **Partial-unique index** (lines 17-24): `@CompoundIndex(name=
  "projectId_triggerType_triggerValue_unique_active", def="{'projectId':1,'triggerType':1,
  'triggerValue':1}", unique=true, partialFilter="{ 'status': 'active' }")`. Plus a non-unique
  `(projectId, status)` lookup index.
- Class-load assertion (lines 30-36) guards that `"active"` literal == `FunnelStatus.active.name()`.
- **1:1→1:N relax (plan "Крок 1"):** keep unique for `on_start`, drop for `event`/keyword. Mongo
  partial filters can express this with a compound condition, BUT the cleanest path given the existing
  literal-`'active'` filter is: change the annotation's `partialFilter` to also require
  `triggerType: 'on_start'` (so only on_start funnels participate in uniqueness), and let
  event/keyword funnels carry NO uniqueness. Because `auto-index-creation=true` will NOT drop the old
  index (only creates), a startup migration must `mongoTemplate.getCollection("funnels")
  .dropIndex("projectId_triggerType_triggerValue_unique_active")` then let auto-index recreate the new
  shape. See §7.

### `FunnelStatus` enum (`funnel/FunnelStatus.java`) — `draft`, `active`, `paused` (lowercase).

### `FunnelStep` (`funnel/FunnelStep.java`)
Flat POJO, no `_class`. Fields per StepType (text/parseMode, imageUrl/caption, delayValue/delayUnit,
tagSlug, customFieldKey/customFieldValue), graph fields (`id`, `next`, `buttons`, `timeoutValue`,
`timeoutUnit`, `timeoutTargetStepId`). `copyOf(source)` (lines 76-101) deep-copies for the snapshot —
**any new EMIT_EVENT field (e.g. `eventName`) MUST be added to `copyOf`** (it's an immutable String →
reference copy, line 92 block) or the snapshot will drop it.

### `StepType` enum (`funnel/StepType.java`)
`SEND_MESSAGE, SEND_IMAGE, DELAY, ADD_TAG, REMOVE_TAG, SET_CUSTOM_FIELD, MENU`. **Add `EMIT_EVENT`**
here (UPPERCASE discriminator convention). Frontend mirror: `frontend/types/funnel.ts` line 10
`StepType` union + `STEP_TYPES` array in `FunnelStepForm.vue` line 30 — both must add it.

### `Button` record (`funnel/Button.java`) — `(type, label, targetStepId, url)`. Immutable. Not
changed by Phase 3.

### How to add `EMIT_EVENT` step
1. `StepType.EMIT_EVENT` (enum).
2. `FunnelStep` new field `eventName` (String) + getter/setter + add to `copyOf`.
3. `StepExecutor.execute` switch (line 90) — new `case EMIT_EVENT -> emitEvent(step, execution)`
   returning `StepResult.cont()`. The handler calls the new funnel-event dispatcher with
   `(projectId, subscriberId, eventName)`. **This is a synchronous in-tick call** — it will create
   sibling executions; loop protection applies (see §4). Mirror the existing ADD_TAG case (lines
   94-97) which already calls back into a side-effecting service.
4. `FunnelService.validateSteps` switch (line 331) + `toSteps` mapping (line 262) — validate
   `eventName` (non-blank, slug shape to match the shared event-name namespace).
5. `FunnelStepDto` / `ButtonDto` DTO + frontend `FunnelStep` type + `FunnelStepForm.vue` form section.

---

## 3. Webhook message dispatch — `com.botfunnel.webhook`

### `ProcessTelegramUpdateJob` (`webhook/ProcessTelegramUpdateJob.java`)
JobRunr worker `handle(rawUpdateId)`. `dispatch(projectId, userId, update)` (lines 148-208) routing:
- **callback_query branch FIRST** (line 152) → `handleCallbackQuery` → `advanceOnCallback`. Must run
  before message-fallback (callback updates have top-level `message==null`).
- `message == null` → `logEventOther(kind)` (line 161). `text == null` (media-only) →
  `logEventOther("message")` (line 176).
- `text.startsWith("/")` (line 180) → `TelegramCommandParser.parse`; switch on command:
  `start` → `handleStart` (which calls `funnelTriggerService.fire(projectId, chatId, "on_start",
  startPayload)` at line 252), `stop` → `handleStop` (calls `cancelActiveFor`, line 264), default →
  `logEventMessageReceived`.
- **Plain text branch** (lines 191-207): `isPrivate && chatId != null` → resolve CONNECTED bot →
  `subscriberService.upsertFromTelegramUpdate(...)` (line 199) → then ALWAYS
  `logEventMessageReceived` (line 207).

### Exact hook point for KEYWORD matching
The plain-text branch (lines 191-207) is the place. **Precedence (per plan): a subscriber in
`waiting_for_reply` consumes the text first; keyword only fires if no in-flight wait.** Phase 2 only
parks on **button callbacks**, NOT free text (roadmap "Free-text wait-for-reply" is explicitly
deferred). So today plain text NEVER resumes an execution — meaning a `waiting_for_reply` execution
exists but is not consulted on text. **Decision needed:** the plan's precedence requires checking for
an active `waiting_for_reply` execution for this subscriber BEFORE keyword-firing. That check is new
code (query `funnel_executions` by subscriberId + status waiting_for_reply). Suggested flow inserted
after the `upsertFromTelegramUpdate` call (line ~206), before `logEventMessageReceived`:
1. resolve subscriber (already resolved bot above; need subscriberId — currently only the bot is
   resolved, the upsert returns void). You'll need `subscriberService.findByChat` to get the id.
2. if subscriber has an in-flight `waiting_for_reply` → skip keyword (consume-or-defer per plan).
3. else `funnelTriggerService.fireKeyword(projectId, chatId, text)` (new method) — scans active
   keyword funnels, contains-match case-insensitive, any-match → start funnel(s).

**Collision risk:** keyword firing must NOT interfere with the existing `/start` exact-command path
(that's a separate `text.startsWith("/")` branch) — keyword only applies to non-command plain text, so
no collision. But note `upsertFromTelegramUpdate` returns void; you'll add a `findByChat` lookup.

`funnelTriggerService` is already injected (constructor line 66). `subscriberService` too (line 65).

---

## 4. Subscriber event hooks — `com.botfunnel.subscriber`

### Sole writers (VERIFIED via grep — exactly 2 call sites each)
`SubscriberServiceImpl` (`subscriber/SubscriberServiceImpl.java`):
- **`addTag(projectId, subscriberId, slug)`** — lines 242-253. `addToSet` (idempotent: modifiedCount
  0 → no-op return), then `tagService.incrementCounter` + `writeSubscriberEvent(EVT_TAG_ADDED)`.
  Callers: `SubscriberController:216` (manual UI), `StepExecutor:95` (funnel ADD_TAG). **API path:**
  there is currently NO API tag-add beyond the session controller; the new public API may add one.
- **`removeTag`** — lines 255-266. Symmetric. Callers `SubscriberController:226`, `StepExecutor:99`.
- **`recordCustomFieldsSet(projectId, subscriberId, oldValues, newValues)`** — lines 228-240. Diffs
  changedKeys; if empty → no-op; else `writeSubscriberEvent(EVT_CUSTOM_FIELD_SET)`. Callers:
  `SubscriberCustomFieldsController:97` (manual PATCH), `StepExecutor:243` (funnel SET_CUSTOM_FIELD).
  The actual mutation is `SubscriberCustomFieldsService.applyAll` (separate); the audit event is the
  sole-writer call. **Hook the trigger inside `recordCustomFieldsSet` AFTER the event write**, keyed
  by `changedKeys` (the plan says "any value, no predicate" → fire once per set).

**Conclusion:** `addTag` and `recordCustomFieldsSet` in `SubscriberServiceImpl` ARE the single
choke points to fire `tag_added` / `custom_field_set` funnel triggers. Hook AFTER the existing
idempotency guard (so a no-op tag-add does NOT fire) and AFTER `writeSubscriberEvent`.

### Cascade / loop analysis (critical risk)
- Manual UI add-tag → fires tag_added trigger funnel A → funnel A has an ADD_TAG step →
  `StepExecutor:95` → `addTag` → fires tag_added again → funnel A re-enters (blocked by re-enter guard
  if same funnel, but a DIFFERENT funnel B keyed on the same tag would start → B adds another tag → …).
- EMIT_EVENT step → fires event trigger → that funnel has an EMIT_EVENT step → infinite fan-out.
- **Mitigations to design (tech-spec):** (a) the per-execution `app.funnel.max-steps-per-tick:100`
  budget bounds a single execution but NOT cross-execution cascades; (b) need a cascade depth/visited
  guard (e.g. carry an emit-depth counter or a per-chain set on the execution, or rate-limit
  same-(subscriber,eventName) fires), or (c) document that authors must not create cycles. The
  re-enter partial-unique index only protects re-entry into the SAME funnel for the SAME subscriber
  while one is running/waiting — it does NOT stop A→B→A chains.

### `SubscriberService` interface (`subscriber/SubscriberService.java`)
Declares `addTag`, `removeTag`, `recordCustomFieldsSet`, `findByChat`, `upsertFromTelegramUpdate`,
status-machine methods. `findById`-equivalent for subscriber-keyed triggers: `findByChat` exists but a
`findById`/`requireById` by subscriberId may be needed (engine uses `subscriberRepository.findById`
directly; the trigger service must stay on the public boundary).

### `SubscriberCustomFieldsService` (`subscriber/SubscriberCustomFieldsService.java`)
`validateAndNormalize`, `applyAll`, `setOne`. Does NOT write audit (sole-writer stays at
`recordCustomFieldsSet`). No trigger hook here — hook in `recordCustomFieldsSet`.

### `subscriber_events` (`subscriber/SubscriberEvent.java`)
Separate collection from `events`. Event types: `subscriber_registered/reactivated/unsubscribed/
blocked/deleted/tag_added/tag_removed/custom_field_set`. This is an AUDIT feed (365d TTL), NOT a
dispatcher — nothing reads it to fire funnels.

---

## 5. Public API + API keys — GREENFIELD

- **No `api` module, no `api_keys` collection, no `ApiKey` domain.** Confirmed by `ls` (no
  `com/botfunnel/api`) and grep (`api_keys`/`ApiKey` → only existing `/api/v1/...` mappings).
- **`POST /api/v1/events` path collision:** ALL existing endpoints are under `/api/v1/**` and
  session-authenticated. See `ProjectController` `@RequestMapping("/api/v1/projects")`,
  `SubscriberController` `/api/v1/projects/{projectId}/subscribers`, etc. The plan's
  "api-key auth filter on `/api/v1/**`" would shadow the session API. **Needs a tech-spec decision:**
  either a separate prefix (`/api/public/**`) or a dedicated `SecurityFilterChain` with
  `securityMatcher("/api/v1/events")` ordered before the session chain.

### `SecurityConfig` (`security/SecurityConfig.java`)
- ONE `@Bean SecurityFilterChain appSecurityFilterChain` (line 64). CSRF enabled except a scoped
  negation for `/webhooks/telegram/{projectId}` (lines 84-87). `authorizeHttpRequests` (lines 90-99):
  `/health` permitAll, `/api/auth/**` permitAll, `/webhooks/telegram/{projectId}` permitAll,
  `/api/**` authenticated, anyRequest authenticated.
- **To add an api-key chain:** introduce a second `@Bean` `SecurityFilterChain` annotated `@Order(1)`
  with `http.securityMatcher("/api/v1/events")` (or chosen public prefix), `.csrf(disable)` (API key
  in header, not cookie → no CSRF), a custom `OncePerRequestFilter` that reads
  `X-API-Key` / `Authorization: Bearer`, hashes via `Sha256Hex.hex`, looks up `api_keys` by hash, sets
  Authentication (project-scoped principal). The existing chain becomes `@Order(2)` default. Mirror
  the webhook's "unauthenticated + custom verifier" idea but with a real Authentication.

### Crypto utilities (`common/crypto/`)
- **`Sha256Hex.hex(String)`** (`common/crypto/Sha256Hex.java`) — UTF-8 → lowercase hex SHA-256.
  Already used for webhook-secret hashing (`BotService.connect` + `WebhookSecretVerifier`). **Use this
  to store `keyHash` and verify presented keys** (store only the hash; show the plaintext once at
  generation). Null input → NPE (guard at boundary).
- **`TokenEncryptor`** (`common/crypto/TokenEncryptor.java`) — AES-256-GCM encrypt/decrypt (used for
  bot tokens). NOT needed for api keys if you store hash-only (preferred). Available if you must
  decrypt-display (not recommended for API keys).
- **`SignedDownloadToken`** (`common/crypto/SignedDownloadToken.java`) — HMAC signed-URL pattern
  (export downloads). Reference only.
- **Generation:** use `java.security.SecureRandom` (see `TokenEncryptor` line 26 for the pattern) →
  e.g. 32 random bytes → URL-safe base64 or hex as the plaintext key; store `Sha256Hex.hex(plaintext)`.

### Project creation flow
- `ProjectService.create(ownerId, dto, ip, userAgent)` (`project/ProjectService.java` lines 74-95) —
  quota + name-conflict checks, `projectRepository.save(p)`, `eventService.logEvent(PROJECT_CREATED)`.
  **Hook API-key generation here** (after `save`, generate + persist 1 primary key for `saved.getId()`).
- `ProjectController.create` (`project/ProjectController.java` lines 48-56) returns 201. The generated
  plaintext key would need to be surfaced once — decide whether `create` returns it in the response
  (new field on `ProjectResponse`) or via a separate "reveal" endpoint.
- `Project` domain (`project/Project.java`) — no api-key field today; api keys belong in their own
  `api_keys` collection keyed by `projectId` (not embedded), to allow rotation/multiple.

---

## 6. Rate-limit infra (reusable for `POST /api/v1/events`)

Established Redis fail-open INCR+EXPIRE counter pattern (3 instances):
- **`BotService.incrementBruteForceCounter`** (`bot/BotService.java` lines 250-269): `INCR` key;
  `EXPIRE` only when count==1; throw `AppException.tooManyRequests` when `count > THRESHOLD`;
  `catch(Exception)` → fail-open WARN (greppable `REDIS_FAIL_OPEN_WARN`). `BRUTE_FORCE_THRESHOLD=10`,
  `BRUTE_TTL=900s`. Key prefix `brute:bot-connect:`.
- **`SubscriberServiceImpl.startRateLimitExceeded`** (`subscriber/SubscriberServiceImpl.java`
  lines 270-284): same INCR/EXPIRE/fail-open, returns boolean instead of throwing. Key prefix
  `bf:rate:start:`, TTL 1 min, limit from `app.subscriber.rate-limit.start-per-min:100`.
- Subscriber also has a personal-message rate-limit (`app.subscriber.rate-limit.
  personal-message-per-min:60`) and export download rate-limit (`download-rate-per-min:30`).

**For a fixed global rate-limit on `POST /api/v1/events`:** copy the `SubscriberServiceImpl` boolean
variant (fail-open). For a "global" (not per-project) limit use a fixed key (e.g. `bf:rate:events` or
per-project `bf:rate:events:{projectId}`). Inject `StringRedisTemplate`, add a config property
`app.api.events.rate-limit-per-min`. Reuse the greppable-WARN + INCR-then-EXPIRE-on-1 idiom verbatim.

---

## 7. Migration pattern (index reconciliation + api-key backfill)

### `FunnelStepIdBackfill` (`funnel/FunnelStepIdBackfill.java`) — THE template
`@Component implements ApplicationRunner`, `run(ApplicationArguments)` wrapped in
`try/catch(Exception)` that **logs but NEVER throws** (app must boot even if Mongo is transiently
down). Idempotent (re-running never re-stamps existing ids). Works at raw `Document`/collection level
(`mongoTemplate.getCollection(...)`) to stay surgical and tolerant of legacy shapes.

### `SuperAdminSeeder` (`admin/SuperAdminSeeder.java`) — same shape
`@Component implements ApplicationRunner`, idempotent (`findByEmail` → create/promote/skip),
log-but-never-throw. Reads `@Value("${app.super-admin.email:}")` config.

### For Phase 3, model TWO startup runners on these:
1. **Trigger-index reconciliation:** drop the existing
   `projectId_triggerType_triggerValue_unique_active` index so the changed annotation's new partial
   filter (uniqueness only for `triggerType:'on_start'`) is recreated by auto-index-creation. Idempotent:
   check `listIndexes()` for the old key/filter before dropping; if already the new shape, no-op. This
   is REQUIRED because **`auto-index-creation=true` creates but never drops/alters** existing indexes.
2. **API-key backfill:** for every existing project lacking a primary api key, generate + persist one
   (idempotent: skip projects that already have a primary key).

### Index config — CONFIRMED
`spring.data.mongodb.auto-index-creation=true` (`src/main/resources/application.properties` line 9,
and `application-test.properties`). All `@CompoundIndex`/`@Indexed` annotations auto-create at startup.
**Caveat noted in `events/Event.java` line 10-11** that production once ran with
auto-index-creation=false — but the current global setting is `true`. New `api_keys` indexes (unique
on `keyHash`, lookup on `projectId`) will auto-create.

---

## 8. Frontend funnel editor — `frontend/`

### Trigger config today (on_start only)
- **`components/funnels/FunnelTriggerSettings.vue`** — trigger UI. Phase 1 FIXES triggerType to
  on_start: the type is a static read-only badge (`funnel-trigger-type`, lines 58-62), NOT a selector;
  only `triggerValue` is an editable `<input>` (lines 64-77) with live deep-link preview + copy. Model:
  `defineModel('triggerValue')`. `TRIGGER_VALUE_RE = /^[A-Za-z0-9_-]{0,64}$/` (line 13, mirrors backend).
  **Extension point:** replace the static badge with a trigger-TYPE selector (on_start / keyword /
  tag_added / custom_field_set / api-event), and conditionally render the value editor per type
  (keyword → multi-keyword list input; tag_added → tag SearchableSelect; custom_field_set → field
  SearchableSelect; api-event → event-name input). The deep-link preview only applies to on_start.
- **`types/funnel.ts`** — `triggerType: string|null`, `triggerValue: string|null` on
  `FunnelSummaryResponse`/`FunnelResponse`/`UpdateFunnelRequest` (lines 71, 87, 107). Already string,
  so new trigger types are just new string values; a multi-keyword trigger may need a new field
  (`triggerValue` is a single string — multiple keywords need a list, a DTO/model change).

### EMIT_EVENT step slotting
- **`components/funnels/FunnelStepForm.vue`** — shared per-type form. `STEP_TYPES` array (line 30)
  drives the step-type `<select>` (line 485-491, `v-model="selectedType"`). Per-type field blocks are
  conditionally rendered by `selectedType`. **Add `'EMIT_EVENT'` to `STEP_TYPES` + a new field block
  (event-name input).** `buildStep`/emit logic (lines ~419-458) constructs the typed step object — add
  an `EMIT_EVENT` branch.
- **`components/funnels/AddStepDialog.vue`** / **`EditStepDialog.vue`** wrap `FunnelStepForm`; no
  change beyond the form.
- **`components/funnels/SearchableSelect.vue`** — reusable searchable dropdown, already used in
  `FunnelStepForm` for `tagSlug` and `customFieldKey` (line 8 import; bound via v-model). Reuse it for
  a tag_added trigger's tag picker and custom_field_set's field picker. The MENU target picker
  (line 122+) also reuses it.
- **Editor pages:** `pages/projects/[projectId]/funnels/[funnelId].vue` (editor) and `index.vue`
  (list). The editor renders `FunnelTriggerSettings` + `FunnelStepsList` (vertical-list editor).
- **i18n:** step/trigger labels via `t('funnels.steps.type.${stepType}')` and
  `t('funnels.trigger...')` — new keys needed in the locale files.

---

## 9. Tests — patterns to model

### Infra base: `AbstractIntegrationTest` (`test/.../AbstractIntegrationTest.java`)
`@SpringBootTest(RANDOM_PORT)` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")` +
`@Testcontainers`. **Singleton Testcontainers** (static, started once per JVM): `MongoDBContainer
mongo:8.0`, Redis `redis:7.4-alpine`, Mailpit. `@DynamicPropertySource` wires Mongo + Redis URIs.
JobRunr background server disabled in test (`JobRunrInMemoryConfig` import), so the funnel sweep does
NOT auto-run — ITs invoke `engine.sweep()` / `runExecution` directly (see FunnelExecutionEngineIT).
`mockMvc` is the HTTP client. Subclass this for all new ITs.

### Index migration testing: `FunnelIndexesIT` (`test/.../funnel/FunnelIndexesIT.java`)
`@Tag("slow")` (run with `-PrunSlow=true`). Inspects `mongoTemplate.getCollection(c).listIndexes()`,
asserts keys / `unique` / `partialFilterExpression` literals, and proves the partial-unique guard fires
via `assertThatThrownBy(() -> mongoTemplate.insert(dup)).isInstanceOfAny(DuplicateKeyException,
DataIntegrityViolationException)`. **Model the relax-migration IT on this:** assert the OLD index is
gone and the NEW partial filter (on_start-only) exists; prove two active `event`-trigger funnels with
the same `(triggerType, triggerValue)` now COEXIST, while two on_start with the same value still
collide.

### Trigger firing: `FunnelTriggerServiceIT` (`test/.../funnel/FunnelTriggerServiceIT.java`, 19KB)
Existing on_start fire ITs. Model new per-trigger firing ITs (keyword, tag_added, custom_field_set,
api-event, EMIT_EVENT), fan-out (N funnels → N executions), and loop-protection ITs here or a sibling.

### Webhook path: `ProcessTelegramUpdateJobTest` (`test/.../webhook/ProcessTelegramUpdateJobTest.java`,
39KB) + `TelegramWebhookControllerIT`. Model the keyword webhook path (plain-text → fire vs.
waiting_for_reply precedence) here. `ProcessTelegramUpdateJobTest` is the dispatch-matrix unit test
(likely Mockito-mocking the trigger service). MockWebServer is used in `bot/TelegramSender*` tests for
Telegram API stubbing (`grep` shows MockWebServer usage in `bot/` tests).

### Subscriber hooks: `SubscriberTagAssignmentIT`, `SubscriberServiceImplIT`,
`SubscriberCustomFieldsControllerTest`, `SubscriberEventsIsolationIT`
(`test/.../subscriber/`). Model tag_added / custom_field_set trigger-firing ITs by asserting an
execution is created after `addTag` / `recordCustomFieldsSet`.

### Project + API key: `ProjectControllerIT` (32KB), `ProjectServiceTest` (18KB)
(`test/.../project/`). Model "api key generated on project create" + "backfill existing projects" ITs.

### Security chain: `SecurityConfigTest`, `WebhookSecurityBlockTest`, `SecurityBlockTest`,
`HealthSecurityTest` (`test/.../security/` + root). Model the api-key-authenticated `/events` chain IT
(valid key → 200/202, missing/bad key → 401, session cookie does NOT authenticate `/events`, and the
api key does NOT authenticate the session `/api/v1/projects` endpoints) on these.

### Step executor unit: `FunnelStepExecutorTest` (`test/.../funnel/FunnelStepExecutorTest.java`, 21KB)
+ `FunnelStepTest` (copyOf coverage). Model an `EMIT_EVENT` step unit test (asserts the dispatcher is
called with the right eventName, returns CONTINUE) here; add an `EMIT_EVENT` copyOf case to
`FunnelStepTest`.

### Backfill/migration unit: `FunnelStepIdBackfillTest` (`test/.../funnel/FunnelStepIdBackfillTest.java`,
9.8KB) — idempotency + log-but-never-throw test pattern for the new ApplicationRunner migrations.

---

## Concrete file inventory (paths)

Backend (modify):
- `funnel/StepType.java` — add `EMIT_EVENT`.
- `funnel/FunnelStep.java` — add `eventName` + `copyOf`.
- `funnel/StepExecutor.java` — `EMIT_EVENT` case (line 90 switch).
- `funnel/FunnelService.java` — validate/map EMIT_EVENT + new trigger types (lines 248-260 trigger
  normalize is on_start-locked today; relax for new types).
- `funnel/Funnel.java` — partial-unique index annotation change (lines 17-24).
- `funnel/FunnelTriggerServiceImpl.java` + `FunnelTriggerService.java` — new subscriber-keyed
  fan-out fire + keyword fire entrypoints.
- `funnel/FunnelRepository.java` — list query for keyword/event triggers.
- `webhook/ProcessTelegramUpdateJob.java` — keyword hook in plain-text branch (lines 191-207).
- `subscriber/SubscriberServiceImpl.java` — fire tag_added (line 252 area) / custom_field_set
  (line 239 area) triggers.
- `project/ProjectService.java` — generate api key on create (line 91 area).
- `security/SecurityConfig.java` — add api-key `SecurityFilterChain` (line 64 area).
- `src/main/resources/application.properties` — new props (events rate-limit, api-key config).

Backend (new):
- `api/` (or `apikey/`) module: `ApiKey` domain (`api_keys` collection, `keyHash` unique index,
  `projectId` index), repository, generation/auth filter, `EventsController` (`POST /events`), event
  dispatcher service (the internal pub/sub), api-key backfill `ApplicationRunner`, trigger-index
  reconciliation `ApplicationRunner`.

Frontend (modify):
- `components/funnels/FunnelTriggerSettings.vue` — trigger-type selector + per-type value editor.
- `components/funnels/FunnelStepForm.vue` — `STEP_TYPES` + EMIT_EVENT field block.
- `types/funnel.ts` — `StepType` union + trigger types/fields.
- i18n locale files — new step/trigger keys.

## Risks flagged against the plan
1. **`/api/v1/**` prefix collision** with the session API — the api-key filter cannot blanket-cover
   it (§5). Decide a distinct matcher/prefix.
2. **`events` naming overload** — three distinct "event" concepts (`events` audit, `subscriber_events`
   audit, new funnel-event pub/sub). The internal pub/sub is NEW; do not reuse `EventService`.
3. **Cascade/loop** for tag_added/custom_field_set/EMIT_EVENT — re-enter guard does NOT stop A→B→A
   chains (§4). Needs explicit depth/visited/rate guard.
4. **Keyword ≠ exact-match index** — keyword "contains, any-of-many" cannot use the existing
   exact-match `triggerValue` lookup; needs a per-funnel keyword list + code-side scan (§1, §2).
5. **Free-text wait precedence** — Phase 2 does NOT consume free text; the plan's "waiting_for_reply
   consumes text first" requires NEW code in the webhook plain-text branch to check for an in-flight
   `waiting_for_reply` execution before keyword-firing (§3).
6. **`auto-index-creation=true` never drops indexes** — the 1:1→1:N relax REQUIRES a startup
   `dropIndex` migration; changing the annotation alone is insufficient (§2, §7).
7. **`fire()` is chatId-keyed and single-result** — subscriber-keyed + fan-out triggers need a new
   entrypoint; do not overload the existing `fire` (§1).
