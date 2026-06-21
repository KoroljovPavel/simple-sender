# Code Research: 10-funnels (Phase 1 — LINEAR funnels)

Research-only. Backend: Java 21 + Spring Boot 3.5.0 + Spring MVC on virtual threads + Spring Data
MongoDB (sync driver) + JobRunr 7.3.2. Frontend: Nuxt 4 + Pinia + shadcn-vue (reka-ui/radix-vue) +
vee-validate/zod + @nuxtjs/i18n.

NOTE on package layout: the actual code does NOT use `com.botfunnel.telegram` / `.config` /
`.customfield`. Real packages are: `com.botfunnel.webhook` (Telegram inbound + worker +
command parser), `com.botfunnel.bot` (Bot entity + TelegramSender outbound), `com.botfunnel.jobs`
(recurring hard-delete jobs), `com.botfunnel.project` (Project + CustomField + CustomFieldValueValidator),
`com.botfunnel.subscriber`, `com.botfunnel.tag`, `com.botfunnel.events`, `com.botfunnel.common`
(AppException, GlobalErrorHandler, SessionAttributes). The funnel package already exists with only
two files: `com.botfunnel.funnel.FunnelTriggerService` + `NoOpFunnelTriggerService`.

The feature folder `work/10-funnels/` has only stub templates: `user-spec.md`, `tech-spec.md`,
`decisions.md` are all unfilled placeholders. There is NO existing `code-research.md`. Tech-spec
and decisions are explicitly deferred to "Cycle 2".

---

## 1. Trigger integration (Question 1)

### Where the worker already calls the funnel contract
`backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java`

- `fire(...)` call site — `handleStart(...)`, line 242:
  `funnelTriggerService.fire(projectId, chatId, "on_start", startPayload);`
  This runs ONLY for private chats with non-null chatId (guard at line 202), and ONLY AFTER
  `subscriberService.upsertFromTelegramUpdate(...)` (lines 235–241). The literal triggerType
  passed is the string `"on_start"` (NOT `/start`); `startPayload` is `parsed.payload()`.
- `cancelActiveFor(...)` call site — `handleStop(...)`, line 254:
  `funnelTriggerService.cancelActiveFor(projectId, chatId);`
  Runs only for private chats; AFTER `subscriberService.markUnsubscribed(...)` (line 253).
- The dispatch switch is at lines 170–178: `/start` → `handleStart`, `/stop` → `handleStop`,
  default → just logs `telegram_message_received`. Command is lower-cased at line 172
  (`parsed.command().toLowerCase()`).
- The contract interface: `com.botfunnel.funnel.FunnelTriggerService` (13 lines) — exactly:
  `void fire(String projectId, Long chatId, String triggerType, String payload);`
  `void cancelActiveFor(String projectId, Long chatId);`
- The current impl `NoOpFunnelTriggerService` is `@Service` (NOT `@Primary`). Its javadoc says
  "Epic 06 will replace this `@Service` directly" — i.e. the intended Phase-1 move is to REPLACE
  the body of `NoOpFunnelTriggerService` (or rename to a real impl and delete the no-op) so there
  is exactly one `FunnelTriggerService` bean. Two `@Service` impls without `@Primary` would fail
  context startup with a NoUniqueBeanDefinitionException — open question below.

### Command parsing + payload constraints
`backend/src/main/java/com/botfunnel/webhook/TelegramCommandParser.java` (static util) +
`backend/src/main/java/com/botfunnel/webhook/ParsedCommand.java` (record).

- `ParsedCommand(String command, String payload)` — `command` is the token after `/`, with
  `@botname` suffix stripped (split on first `@`, line 67–68), original case preserved; `payload`
  is everything after the first whitespace with leading whitespace trimmed, inner whitespace
  preserved verbatim. Both fields are never null (empty string when absent). `notACommand()`
  sentinel = `("", "")`.
- For `/start ref_x`: command=`"start"`, payload=`"ref_x"`. For `/stop`: command=`"stop"`,
  payload=`""`.
- NO 64-char / charset constraint exists in the parser. Telegram itself caps deep-link `/start`
  payload at 64 chars `[A-Za-z0-9_-]`, but the codebase does not enforce/validate this anywhere.
  (OPEN QUESTION: does the funnel `trigger_value` match need to normalize/validate payload?)

### How the worker resolves Bot → telegramBotId
In both `handleStart` (line 208) and `handleStop` (line 248) and plain-text (line 183):
`Bot bot = botRepository.findByProjectIdAndStatus(projectId, BotStatus.CONNECTED).orElse(null);`
then `Long telegramBotId = bot.getTelegramBotId();`. So the funnel engine, given (projectId,
chatId), must resolve the connected bot the same way to send messages. The fire() call passes
ONLY (projectId, chatId, triggerType, payload) — it does NOT pass telegramBotId, so the real
impl must look up the CONNECTED bot itself (mirroring the worker) to know which bot to send from
and to key the subscriber. (Decision needed: pass telegramBotId into fire() or re-resolve?)

`message.from()` (`com.botfunnel.webhook.dto.User`) carries `id()`, `first_name()`, `last_name()`,
`username()`, `language_code()` — these are already persisted onto the Subscriber during upsert
(lines 235–241), so `{user.first_name}` templating can read from the Subscriber row.

---

## 2. Subscriber service surface for step actions (Question 2)

`backend/src/main/java/com/botfunnel/subscriber/SubscriberService.java` (interface) +
`SubscriberServiceImpl.java` (sole impl, `@Service`). Key signatures a funnel step can call:

- `void addTag(String projectId, String subscriberId, String slug)` — SubscriberServiceImpl
  lines 114–128. Calls `tagService.findOrCreate(projectId, slug)` (find-or-create), atomic
  `$addToSet` on `tags`, and only when membership actually changed: `tagService.incrementCounter(+1)`
  and emits `subscriber_tag_added`. Idempotent.
- `void removeTag(String projectId, String subscriberId, String slug)` — lines 130–143. Atomic
  `$pull`; only-on-change counter `-1` + `subscriber_tag_removed`. Idempotent.
- `void recordCustomFieldsSet(String projectId, String subscriberId, Map oldValues, Map newValues)`
  — AUDIT ONLY (Decision 10 sole-writer of `subscriber_custom_field_set`). It does NOT mutate the
  subscriber document. (Interface lines 55–62.)

GAP for "Set Custom Field" step: there is NO reusable SERVICE method that validates + persists a
custom-field value. The persist+validate+audit logic is INLINE in the controller
`subscriber/SubscriberCustomFieldsController.setCustomFields(...)` (lines 59–113): it (1) calls
`projectService.requireOwned`, (2) builds allowed-types from `project.getCustomFieldDefinitions()`,
(3) per key calls `validator.validate(type, rawValue)` (drops unknown keys silently), (4) builds a
Mongo `Update` (`set`/`unset` on `customFields.<key>`), (5) applies it atomically, (6) calls
`subscriberService.recordCustomFieldsSet(...)` for audit. There is NO `SubscriberCustomFieldsService`
class — so the funnel Set-Custom-Field step CANNOT just reuse a service method; it must either
(a) extract this controller logic into a new shared service (recommended), or (b) replicate the
validate→update→recordCustomFieldsSet sequence in the engine. The funnel step has a SINGLE
(key, value) to set, so the loop collapses to one entry.

GAP for subscriber LOOKUP by (projectId, telegramBotId, chatId): the funnel engine receives
chatId from fire(), but the public `SubscriberService` interface exposes NO lookup-by-chat. The
repository does: `SubscriberRepository.findByProjectIdAndTelegramBotIdAndTelegramChatId(projectId,
telegramBotId, chatId)` returns `Optional<Subscriber>` (`SubscriberRepository.java` lines 9–10).
The funnel engine will need either to call the repository directly or to get a new
`SubscriberService` lookup method. `addTag`/`removeTag`/custom-field methods are keyed by
`subscriberId` (String Mongo id), so the engine must first resolve chatId→subscriberId.

Tag find-or-create: `TagService.findOrCreate(String projectId, String slug)` (`tag/TagService.java`
lines 52–63) — re-reads on the unique `(projectId, slug)` DuplicateKey race.

Custom-field validation/normalization: `Object CustomFieldValueValidator.validate(CustomFieldType
type, Object rawValue)` → returns the normalized value (`@Component` at
`subscriber/CustomFieldValueValidator.java` line 32; NOTE it lives under `subscriber`, not
`project`). The `CustomFieldType` enum values are `STRING, NUMBER, BOOLEAN, DATE` (it is STRING,
not "TEXT"). Normalization: STRING → trimmed `String` (max 1024 chars, else 422); NUMBER → `Double`
(`n.doubleValue()` or `Double.parseDouble`; rejects NaN/Infinity); BOOLEAN → `Boolean` (accepts
true/false/yes/no strings); DATE → `java.time.Instant` (parses ISO-8601 OffsetDateTime → UTC
Instant; MongoDB has no OffsetDateTime codec). `null` rawValue returns null = clears the field.
Any rule violation → `AppException.unprocessableEntity("custom_field_type_mismatch", ...)` (422).
The CALLER (controller) resolves the key→type map from `project.getCustomFieldDefinitions()` and
drops unknown keys; the validator itself takes an already-known type.

Subscriber lookup gives `getFirstName()`, plus `tags`, `customFields`, `status`, `getProjectId()`,
`getTelegramBotId()`, `getTelegramChatId()` (entity `subscriber/Subscriber.java`) — enough for
`{user.first_name}` and `{custom.field}` templating.

subscriber_events SOLE-WRITER rule (Decision 10): `SubscriberServiceImpl` is the ONLY writer to
`subscriber_events`. Methods that auto-emit: `addTag` (`subscriber_tag_added`), `removeTag`
(`subscriber_tag_removed`), `recordCustomFieldsSet` (`subscriber_custom_field_set`),
`markUnsubscribed`/`markBlockedByChatId`/`markDeletedByChatId`/`unsubscribeManual`. The funnel
engine MUST route any subscriber mutation through these methods, never write `subscriber_events`
directly. `tag_deleted` goes to the platform `events` collection via `TagService` (different
collection — see `tag/TagService.java` lines 119–134).

---

## 3. TelegramSender (Question 3)

`backend/src/main/java/com/botfunnel/bot/TelegramSender.java` (`@Component`).

- Exact signature: `public SentMessage sendText(String botId, Long chatId, String text, String
  parseMode, String ownerId)` (line 128). `botId` is the Mongo String id of the Bot (used for
  `findById` at line 143, filtered to `BotStatus.CONNECTED`). `ownerId` is the project owner's
  user id — used ONLY for audit-event emission (`eventService.logEvent(ownerId, ...)` at lines
  149 and 156); it is NOT a Telegram identifier. The request body built at lines 264–269 sets
  `chat_id`, `text`, and `parse_mode` only when non-null.
- NO sendPhoto / no `reply_markup` anywhere. `sendOnce` posts to `/bot{token}/sendMessage`
  (line 273) and the body map (lines 264–269) has no reply_markup key. So Phase-1 Send Image
  needs a NEW sender method (`sendPhoto`: photo URL + caption + parse_mode → POST
  `/bot{token}/sendPhoto`). RECOMMENDATION basis: the retry/backoff/timeout/token-decrypt/audit
  machinery in TelegramSender (lines 195–391) is non-trivial and image sends need the same 5xx/429
  handling and the same Decision-4 subscriber hooks; extending `TelegramSender` with a `sendPhoto`
  that reuses `sendWithRateLimitRetry` is lower-risk than a sibling that duplicates it. (OPEN
  QUESTION: extend vs sibling — confirm with interviewer.)
- Failure surfacing (typed exceptions, all in `com.botfunnel.bot`):
  - 401 → `BotTokenInvalidException` (line 306–308).
  - 429 → `TelegramRateLimitException` (carries `retry_after`), handled by an outer retry loop
    (lines 195–210); capped at 30s + jitter.
  - 5xx → transient retry (1s/2s/4s, 3 retries); exhausted → `TelegramSendException("transient_failure_exhausted")`.
  - 4xx terminal → `TelegramSendException(rawStatus, description, attempts, TerminalReason)`.
    `terminalReasonFor` (lines 327–336): 403 → `BLOCKED_BY_USER`; 400 + description contains
    "chat not found" (case-insensitive) → `CHAT_NOT_FOUND`; else `OTHER`.
- Decision-4 subscriber side-effects on terminal send failure: `dispatchSubscriberHook` (lines
  170–191), called AFTER the audit, on the same thread, BEFORE rethrowing:
  - `BLOCKED_BY_USER` → `subscriberService.markBlockedByChatId(bot.getProjectId(),
    bot.getTelegramBotId(), chatId)`.
  - `CHAT_NOT_FOUND` → `subscriberService.markDeletedByChatId(...)`.
  - A failure inside the hook is caught, WARN-logged with constant
    `TELEGRAM_SENDER_SUBSCRIBER_HOOK_FAILED`, and never alters the original exception.
  - IMPORTANT: the hook uses `bot.getTelegramBotId()` (Long), NOT the String `botId`.

Implication for funnels: a Send Message / Send Image step that calls `sendText`/new `sendPhoto`
will ALREADY auto-flip the subscriber to blocked/deleted on 403/400-chat-not-found. The engine
must treat the thrown exception as a terminal per-subscriber failure (mark execution failed /
cancelled) and must NOT retry sends that throw `BLOCKED_BY_USER` / `CHAT_NOT_FOUND`.

---

## 4. JobRunr usage patterns (Question 4)

Dependency: `org.jobrunr:jobrunr-spring-boot-3-starter:7.3.2` (`backend/build.gradle` line 31).
Config in `backend/src/main/resources/application.properties` lines 35–37:
`org.jobrunr.background-job-server.enabled=true`, `org.jobrunr.dashboard.enabled=false`
(dashboard confirmed DISABLED). There is NO dedicated JobRunr `@Configuration` class — the
spring-boot starter auto-configures it; `org.jobrunr.config.JobRunrConfig` does NOT exist.
Recurring/retry tuning is per-annotation, not centralized; no `org.jobrunr.jobs.default-number-of-retries`
property is set (so JobRunr default of 10 retries applies unless overridden per-job).

### Recurring jobs (the sweep idiom for the funnel engine)
- `backend/src/main/java/com/botfunnel/jobs/HardDeleteJob.java` (line 33):
  `@Recurring(id = "hard-delete-users", cron = "0 3 * * *")` + `@Job(name = "...")` on a
  `@Component` method. Comment (lines 17–20) confirms JobRunr 7.3.x resolves `@Recurring` at
  startup, supports 5- and 6-field cron, UTC unless zoneId set.
- `backend/src/main/java/com/botfunnel/jobs/ProjectHardDeleteJob.java` (line 59): same pattern.
- `backend/src/main/java/com/botfunnel/subscriber/jobs/ExportCleanupJob.java`: same `@Recurring`.

There is NO sub-minute recurring job today (all are daily cron `0 3 * * *`). The funnel engine's
~30s sweep is a NEW cadence. JobRunr cron is minute-granularity at minimum; a 30s sweep needs
either JobRunr's `interval`/`Duration`-based recurring (e.g. `@Recurring(interval = "PT30S")`,
supported in 7.x) or a Spring `@Scheduled(fixedDelay)`. (OPEN QUESTION: which scheduler.)

### One-shot enqueue (deterministic-UUID idempotent dedup)
`backend/src/main/java/com/botfunnel/webhook/TelegramWebhookController.java` lines 85–87 and
197–212; and `ExportSubscribersJob` (header comment lines 36–37). Pattern:
`UUID jobId = UUID.nameUUIDFromBytes(rawUpdateId.getBytes(UTF_8));`
`jobScheduler.<ProcessTelegramUpdateJob>enqueue(jobId, j -> j.handle(rawUpdateId));`
The deterministic jobId makes a re-enqueue a JobRunr-level no-op. `JobScheduler` is injected
(constructor) — preferred over static `BackgroundJob.enqueue` because of multi-context test caching
(comment lines 65–71).

### Retry config
Per-job via `@Job(name=..., retries=N)` — `ExportSubscribersJob.handle` uses `retries = 2`
(`subscriber/jobs/ExportSubscribersJob.java` line 38). `ProcessTelegramUpdateJob.handle` relies
on JobRunr's DEFAULT retry (no `retries=`), with an explicit re-entry guard + FAILED status write
+ rethrow (lines 88–143). For the funnel step runner, mirror this: idempotent re-entry guard +
atomic claim + bounded retries.

Job beans live as `@Component` classes (jobs in `com.botfunnel.jobs` and `com.botfunnel.subscriber.jobs`).
Recommendation: put the funnel engine sweep + step-runner under `com.botfunnel.funnel`.

---

## 5. Atomic claim / locking in Mongo (Question 5)

`MongoTemplate.findAndModify` is available and is the established atomic-claim primitive (no
SELECT FOR UPDATE; no replica-set transactions configured — see ProjectHardDeleteJob comment
lines 90–92). Mirror these precedents to claim a due `funnel_execution`:

- Atomic CAS predicate-fail-wins, the closest analog — `ProcessTelegramUpdateJob.handleStart`
  lines 222–227 (Bot `ownerChatId` first-`/start`-wins):
  `mongoTemplate.findAndModify(Query.query(Criteria.where("_id").is(bot.getId())
      .and("status").is(BotStatus.CONNECTED.name()).and("ownerChatId").isNull()),
      new Update().set("ownerChatId", chatId), Bot.class);`
  A null return = another instance already won = benign no-op. THIS is the exact pattern for
  "atomically claim a due execution": match on `_id` + `status == waiting/running` +
  `nextRunAt <= now` + claim-token-null, set claim/in_progress, returnNew; null return = lost the
  race, skip.
- Atomic status-claim PENDING→RUNNING — `ExportSubscribersJob.handle` lines 46–51:
  `findAndModify(Query.where("_id").is(exportId).and("status").is("PENDING"), Update.set("status","RUNNING")...)`.
- Atomic failure write — `ProcessTelegramUpdateJob.handleFailure` lines 122–127 (set status to
  `.name()` literal to match partial-filter indexes byte-for-byte).
- Per-step idempotency (`pending → in_progress → done`) maps directly onto the same findAndModify
  status-CAS idiom used by ExportSubscribersJob.

IMPORTANT idiom (lines 118–119, 124): enum status is persisted as `.name()` String, NEVER as the
enum object, when used inside a findAndModify Criteria/Update so it byte-matches index literals.

Other dedup precedent: the `raw_updates` unique index `(projectId, updateId)` + DuplicateKey
self-heal (`TelegramWebhookController` lines 167–182) — the model for "insert-once" uniqueness if
funnel executions need an idempotency key (e.g. `(funnelId, subscriberId)` when allow_re_enter=false).

---

## 6. Persistence & schema idioms (Question 6)

Entity convention (see `subscriber/Subscriber.java`, `bot/Bot.java`, `project/Project.java`,
`project/CustomField.java`, `webhook/RawUpdate.java`):
- `@Document(collection = "name")`, `@Id private String id;` (Mongo ObjectId stored as String).
- Time via `java.time.Instant`.
- Enums persisted by Spring Data as `name()` String by default (e.g. `SubscriberStatus` is a
  lowercase enum `active/unsubscribed/blocked/deleted` — `subscriber/SubscriberStatus.java`; note
  it is lowercase, unlike `BotStatus.CONNECTED`). When used in findAndModify Criteria you write
  the literal `.name()` (section 5).
- Indexes are DECLARED on the entity via `@CompoundIndex` / `@Indexes` / `@Indexed` and CREATED
  AUTOMATICALLY at startup. `application.properties` line 9:
  `spring.data.mongodb.auto-index-creation=true` (comment line 8 confirms intent). There is NO
  `@PostConstruct ensureIndex` precedent — indexes are annotation-driven only.
  - Unique compound example: `Subscriber` lines 16–24 (`uniq_project_bot_chat` unique +
    `project_status_created`). `Bot` lines 12–15 use a `partialFilter` unique index. `CustomField`
    line 11 `(projectId, key)` unique. So `funnels`/`funnel_executions` should declare their
    indexes the same way (e.g. executions: `(status, nextRunAt)` for the sweep query;
    `(projectId, ...)` for cascade; optional unique `(funnelId, subscriberId)` for re-enter guard).

Embedded heterogeneous list with a `type` discriminator: the closest existing model is
`Subscriber.customFields` = `Map<String, Object>` (line 45) — a free-form value map, NOT a typed
discriminated list. `CustomFieldDefinition` (`project/CustomFieldDefinition.java`) has an enum
`CustomFieldType { TEXT, NUMBER, BOOLEAN, DATE }` and is an embedded POJO, but it is homogeneous.
There is NO existing precedent for a polymorphic embedded array with a `type` discriminator (which
is what the funnel `steps` list needs — Send Message vs Delay vs Add Tag etc.). OPTIONS for steps:
(a) a single flat `FunnelStep` class with a `StepType` enum + nullable type-specific fields (all
steps in one shape, simplest, matches the codebase's "flat POJO" bias); (b) Spring Data
polymorphic `_class` discriminator (no precedent here, adds a `_class` field to BSON). (OPEN
QUESTION: which step modeling — recommend (a) flat for Phase-1 linear.)

---

## 7. Project-scope guard & module layout (Question 7)

`ProjectService.requireOwned(String ownerId, String projectId, boolean includeSoftDeleted)` →
`backend/src/main/java/com/botfunnel/project/ProjectService.java` line 50 (THREE args — note order
is ownerId FIRST). Returns the owned Project or throws a UNIFORM 404 (never 403; foreign /
soft-deleted / missing / malformed-ObjectId all collapse to the same 404) — anti-IDOR. Callers
(grep `requireOwned`): `BotService`, `ProjectController`, `CustomFieldsController`,
`SubscriberController`, `SubscriberCustomFieldsController`, `SubscriberExportController`,
`TagController`.

Controller convention — example `subscriber/SubscriberCustomFieldsController.java`:
- `@RestController` + `@RequestMapping("/api/v1/projects/{projectId}/subscribers/{subscriberId}/custom-fields")`.
- Get the user from the Spring Security context (NOT a request attribute): a private
  `currentUserId()` reads `SecurityContextHolder.getContext().getAuthentication()` and casts the
  principal to `AppUserDetails` → `details.id()` (lines 125–131; throws 401 if unauthenticated).
  Then `projectService.requireOwned(currentUserId(), projectId, false)` FIRST (line 63), before
  any sub-resource work. (`ProjectController` uses the same `AppUserDetails` principal pattern.)
- Returns `ResponseEntity<Void>` / `ResponseEntity<T>`; request bodies are `@Valid @RequestBody`
  records (e.g. `SetCustomFieldsRequest` with a `Map<String,Object> values()`).
- Base path convention: `/api/v1/projects/{projectId}/...`. Funnel CRUD →
  `/api/v1/projects/{projectId}/funnels` (+ `/{funnelId}`).

Error conventions — `common/AppException.java`: factories `notFound(message)` (404, code null),
`badRequest(message)` (400), `unauthorized(message)` (401), `forbidden(message)` (403),
`conflict(message)` / `conflict(code,message)` (409), `unprocessableEntity(code,message)` (422),
`tooManyRequests(message)` (429), `gone(code,message)` (410). Mapped by `common/GlobalErrorHandler.java`
(`@RestControllerAdvice`, `handleAppException` → status + `ErrorResponse(message, code)`;
`@Valid`/`MethodArgumentNotValidException` → 400; `ResponseStatusException` passthrough; any other
`Throwable` → generic 500 "Internal server error", no leak). Service-layer error codes are
String constants (e.g. `"tag_name_taken"`, `"already_unsubscribed"`,
`"custom_field_value_invalid"`).

Where funnel classes go: `com.botfunnel.funnel` (already exists). Suggested:
`funnel/Funnel.java`, `FunnelStep.java`, `FunnelExecution.java`, `FunnelRepository.java`,
`FunnelExecutionRepository.java`, `FunnelService.java`, `FunnelController.java`,
`FunnelExecutionEngine.java` (the sweep + step runner), and the real `FunnelTriggerService` impl;
DTOs under `funnel/dto/` (matches `subscriber/dto`, `tag/dto`, `project/dto`).

---

## 8. Project hard-delete cascade (Question 8)

`backend/src/main/java/com/botfunnel/jobs/ProjectHardDeleteJob.java`. Recurring daily
`@Recurring(id="hard-delete-projects", cron="0 3 * * *")` (line 59); selects projects soft-deleted
> 7 days ago. Current cascade collections (constants lines 33–42): `events` (by
`metadata.projectId`), GridFS `fs.files` (by `metadata.projectId`), `subscriber_exports`,
`subscriber_events`, `subscribers`, `tags` (all by top-level `projectId`), then finally `projects`.

Extension point: the helper `removeByProjectId(List<String> deletedIds, String collection)`
(lines 146–150) does `template.remove(Query.where("projectId").in(deletedIds), collection)`. To
extend, add two constants (`FUNNELS_COLLECTION = "funnels"`, `FUNNEL_EXECUTIONS_COLLECTION =
"funnel_executions"`) and two calls in the cascade block (lines 117–120):
`long funnelsRemoved = removeByProjectId(deletedIds, FUNNELS_COLLECTION);` and similarly for
executions — BEFORE the `projects` drop at line 131 — and add the counts to the INFO log lines
(both the zero-path at lines 74–77 and the deletion-path at lines 135–140). NOTE
`funnel_executions` are keyed by projectId at the document root (must store projectId top-level so
`removeByProjectId` works; if executions only carry funnelId, the cascade must resolve funnelIds
first — design executions to carry projectId).

The cascade is intentionally non-transactional and idempotent (re-runs next day on partial
failure) — keep that property; order funnels/executions deletes before the projects drop.

---

## 9. Frontend conventions for the editor (Question 9)

Project-scoped pages live at `frontend/pages/projects/[projectId]/<resource>/index.vue` (nested
folders, NOT flat files). Existing: `custom-fields/index.vue`, `tags/index.vue`,
`subscribers/index.vue`, `subscribers/[subscriberId].vue`, `settings/index.vue`, `settings/bot.vue`.
Closest list+editor template = **`frontend/pages/projects/[projectId]/custom-fields/index.vue`**:
- `<script setup lang="ts">`, explicit imports from `vue`/`vue-router`/`vue-i18n`/`pinia` (lines
  1–8), `definePageMeta({ middleware: 'auth', layout: 'default' })` (lines 17–20),
  `projectId = computed(() => route.params.projectId as string)`, `storeToRefs(store)`,
  `onMounted(() => store.fetchFields(projectId.value))`.
- Form: vee-validate `useForm` + `@vee-validate/zod` `toTypedSchema(z.object({...}))` (imports
  lines 5–7). It composes a list component (`CustomFieldsList.vue`) + an "Add" Dialog component
  (`AddCustomFieldDialog.vue`) from `~/components/customFields/` plus shadcn-vue primitives from
  `~/components/ui/*` (Button, Dialog…). A vertical list + per-item Dialog is the exact reusable
  shape for the funnel step editor. Create `frontend/components/funnels/*` (FunnelStepsList.vue,
  Add/EditStepDialog.vue) mirroring `components/customFields/`. Desktop-first ≥1024px guideline
  applies to the editor.

Pinia stores: `frontend/stores/*.ts` (currently `auth, bot, projects, subscribers` — NOTE
`customFields`/`tags` stores live as composables/stores referenced via `~/stores/customFields`;
the custom-fields page imports `useCustomFieldsStore` from `~/stores/customFields` and calls
`store.fetchFields(projectId)` exposing `{fields, loading, error}` refs). Setup-style
`defineStore('name', () => {...})`. Add `frontend/stores/funnels.ts` following this shape
(`useFunnelsStore`, `fetchFunnels(projectId)`, `create/update/delete`, calling `useApi()` +
`useApiError()`).

Composables: `frontend/composables/useApi.ts` — `useApi()` returns a configured `$fetch.create`
instance with `credentials:'include'`, SSR cookie forwarding, CSRF `X-XSRF-TOKEN` header injection
on unsafe methods, baseURL from `config.apiBaseSsr` (SSR) / `config.public.apiBase` (client), and a
404 interceptor for `/api/v1/projects/{id}` that flips the projects store. `useApiError.ts` —
`useApiError()` returns `resolveMessage(error, contextKey)` that tries i18n keys
`errors.<contextKey>.<status>` → `errors.<contextKey>.generic` → `errors.generic`. So funnel
error handling needs `errors.funnels.*` i18n keys.

i18n + parity gate (TWO gates): `frontend/i18n/locales/en.json` + `uk.json` +
`i18n/i18n.config.ts` (legacy:false, default+fallback locale `uk`). (1) Vitest parity test
`frontend/tests/i18n/required-keys.spec.ts` flattens both trees and asserts identical key sets —
FAILS CI on drift. (2) `scripts/check-locales.mjs` runs as `prebuild` (npm) and fails the build on
key drift. Every new `funnels.*` key MUST be added to BOTH en.json and uk.json. There is also a
rich component-test suite under `frontend/tests/components/*.spec.ts` (e.g. `AddTagDialog.spec.ts`,
`AddCustomFieldDialog.spec.ts`) — template for funnel dialog/component tests — and page tests under
`frontend/tests/pages/`, plus Playwright E2E under `frontend/e2e/`.

Scripts (`frontend/package.json`): `dev`, `prebuild` (check-locales), `build`, `generate`,
`preview`, `test` (`vitest`), `test:e2e` (`playwright test`). Stack: Nuxt 4, Pinia 3,
@nuxtjs/i18n 9, vee-validate 4 + @vee-validate/zod, zod 3, reka-ui/radix-vue (shadcn-vue),
vue-sonner (toasts), @tanstack/vue-query.

---

## 10. Variable templating (Question 10)

There is NO existing string-interpolation / placeholder-substitution utility in the backend.
Searched for `interpolat`, `String.format(`, `{{`, `replace(`, `MessageFormat`, `template`,
`render`, `substitut` across `backend/src/main` — zero matches in the message-building paths
(`TelegramSender.sendOnce` sends `text` verbatim; no templating). So a NEW
`VariableTemplateRenderer` must be built in `com.botfunnel.funnel` supporting `{user.first_name}`,
`{custom.<key>}`, unknown → "" (empty), and `{{`/`}}` escaping. Data sources: Subscriber fields
(`getFirstName()`, etc.) and `Subscriber.customFields` (`Map<String,Object>`). Note BOOLEAN
custom fields persist as `Boolean`, NUMBER as `Double`, DATE as `java.time.Instant` (per
`CustomFieldValueValidator`) — the renderer must `String.valueOf(...)` them sensibly (Double 30.0
and Instant ISO need special-casing if rendered). Unit-testable pure utility (mirror
`TelegramCommandParser`'s static-util style; test exemplar `webhook/TelegramCommandParserTest.java`).

---

## 11. Risks & gotchas (Question 11)

- Virtual threads + JobRunr: JobRunr dispatches `handle(...)` on its own worker threads;
  `ProcessTelegramUpdateJob` comment (lines 29–30) explicitly says blocking is safe there ("same
  convention as HardDeleteJob"). `spring.threads.virtual.enabled=true` is global. Blocking Mongo
  + RestClient sends inside a JobRunr worker are the established norm — fine for the funnel engine.
- Delay step MUST be `nextRunAt` + recurring sweep, NOT `Thread.sleep`. Confirmed: the only
  `Thread.sleep` usages are `TelegramSender` (429/5xx backoff, deadline-bounded, lines 384) and
  `TelegramApiClient`/`AuthService` — NONE for scheduling/delay. No precedent sleeps for delays;
  do not introduce one (would pin a worker thread for hours/days).
- Message duplication on JobRunr retry: a step runner that sends THEN marks done can double-send
  if it crashes after send / before the status flip. Mirror `ProcessTelegramUpdateJob`'s re-entry
  guard (line 88) + atomic claim (section 5): claim `in_progress` BEFORE the send, and on retry of
  an `in_progress`/`done` step, do NOT re-send. With JobRunr default 10 retries this is critical.
  Also note `TelegramSender` already retries 5xx/429 internally — the engine must not stack its own
  retries on top of a successful-then-failed-bookkeeping send (exactly-once is impossible; aim for
  at-most-once per step via the claim).
- `Subscriber.status` gating: ACTIVE only should receive funnel sends. UNSUBSCRIBED (via /stop —
  and the engine ALSO gets `cancelActiveFor` at that moment), BLOCKED, DELETED must NOT be sent to.
  A send to a blocked/deleted chat will throw and TelegramSender will (re-)flip status (section 3),
  but the engine should pre-check status and cancel the execution rather than rely on the bounce.
- Editing a funnel's step list while executions are mid-flight: executions reference steps by
  index/id; if steps shrink/reorder, an execution's `currentStep` may point at a different/removed
  step. No existing precedent for versioning. Options: snapshot the step list onto the execution at
  enqueue time, OR version the funnel and pin executions to a version, OR block edits on active
  funnels. (OPEN QUESTION.)
- `fire()` is called INSIDE `ProcessTelegramUpdateJob.dispatch` which is wrapped in catch-Throwable
  + rethrow + JobRunr retry (lines 98–111). If the real `fire()` throws, the WHOLE update is marked
  FAILED and RETRIED — which would re-run `upsertFromTelegramUpdate` (idempotent) AND re-fire the
  funnel. The real `fire()` MUST be idempotent / swallow-and-log its own errors so a funnel-start
  failure does not poison webhook processing. (The current no-op never throws.)
- Two `@Service` `FunnelTriggerService` beans: replacing rather than adding is required (the no-op
  is not `@Primary`); adding a second `@Service` impl breaks context startup.
- Send-from-which-bot: `fire()` does not receive telegramBotId; the engine must resolve the
  CONNECTED bot per (projectId) like the worker does. If a project disconnects/reconnects bots
  mid-funnel, `telegramBotId`/`ownerChatId` can change — decide how executions pin the bot.

---

## Testing infrastructure available

- `backend/src/test/java/com/botfunnel/AbstractIntegrationTest.java`: `@SpringBootTest` +
  `@ActiveProfiles("test")` + `@Testcontainers` + `@AutoConfigureMockMvc`, singleton reused
  `MongoDBContainer("mongo:8.0")` + a `redis:7.4-alpine` GenericContainer + a Mailpit container,
  wired via `@DynamicPropertySource` (mongo/redis URIs) and a `@Primary` JavaMailSender override.
  Imports `JobRunrInMemoryConfig`. Integration tests extend this (suffix `*IT`) and use the
  autowired `MockMvc` for HTTP-level tests; subclasses autowire `MongoTemplate`/repos/services.
- JobRunr in tests: `backend/src/test/java/com/botfunnel/JobRunrInMemoryConfig.java` — a
  `@TestConfiguration` providing an `InMemoryStorageProvider` bean. Recurring/sweep jobs are tested
  by CALLING the `@Recurring`/`@Job` bean method directly (see `jobs/HardDeleteJobTest.java`,
  `jobs/ProjectHardDeleteJobIT.java`, `subscriber/jobs/ExportCleanupJobIT.java`) rather than waiting
  for the scheduler — the pattern the funnel sweep tests should use.
- MockWebServer: `com.squareup.okhttp3:mockwebserver:4.12.0` (`build.gradle` line 38), used by
  `bot/TelegramSenderTest.java` + `bot/TelegramSenderIT.java` + `bot/TelegramSenderSubscriberHookIT.java`
  to stub Telegram HTTP — reuse for funnel send-step tests (incl. a future `sendPhoto`). The
  `TelegramSender` package-private test constructor (line 102) takes a base-url + tightened timeouts
  for fast tests.
- Existing test exemplars to mirror: `webhook/ProcessTelegramUpdateJobTest.java` (pure Mockito unit
  test; already has a `startCommandFiresFunnelTrigger` test asserting `fire(projectId, chatId,
  "on_start", payload)` — funnel-engine unit tests can mock `FunnelTriggerService` similarly),
  `funnel/NoOpFunnelTriggerServiceTest.java` (the only existing funnel test today),
  `subscriber/export/SubscriberExportIT.java` + `SubscriberExportConcurrencyIT.java` (one-shot
  JobRunr job IT + concurrency/atomic-claim race testing — the model for the funnel claim race),
  `bot/BotConnectRaceIT.java` (atomic findAndModify CAS race IT), `tag/TagServiceTest.java`,
  `subscriber/SubscriberServiceImplIT.java`, `subscriber/SubscriberStatusMachineTest.java`,
  `webhook/TelegramCommandParserTest.java` (pure-util test — template for VariableTemplateRenderer).
  `common/test/ConcurrencyTestUtils.java` exists for multi-thread race tests.
- Test deps (`build.gradle`): JUnit 5, spring-boot-starter-test, spring-security-test, testcontainers
  (junit-jupiter, mongodb), testcontainers-mailpit, okhttp3 mockwebserver. Slow tests are tagged
  `@Tag("slow")` and excluded from default `gradlew test` (run via `-PrunSlow=true`). Frontend:
  Vitest (`test`), Playwright (`test:e2e`), two i18n parity gates (see section 9).

## Constraints & infrastructure

- Java 21 toolchain; Spring Boot 3.5.0; JobRunr 7.3.2; Mongo sync driver; no replica-set
  transactions configured (cascades are non-transactional + idempotent by design).
- `spring.threads.virtual.enabled=true`; `spring.data.mongodb.auto-index-creation=true`;
  JobRunr dashboard disabled, background server enabled.
- Deployment: GitHub CI/CD only (CLAUDE.md).
- Session-cookie auth (Spring Session in Mongo); controllers read the current user from the Spring
  Security context via `AppUserDetails` principal (`SecurityContextHolder` → `details.id()`), NOT a
  request attribute. CSRF enforced; frontend forwards `X-XSRF-TOKEN`.

---

## OPEN QUESTIONS for Cycle 2 interviewer

1. **Trigger value matching.** `fire()` passes raw `payload` (`"on_start"` triggerType +
   deep-link `trigger_value`). Should funnel matching normalize/validate the 64-char/charset
   Telegram constraint? Exact match on `trigger_value`, or also a default funnel for empty payload?
2. **Which bot does the engine send from?** `fire()` does not pass `telegramBotId`. Re-resolve the
   CONNECTED bot per send, or pin `telegramBotId` onto the execution at creation? What if the bot
   reconnects mid-funnel?
3. **Bean replacement strategy.** Replace `NoOpFunnelTriggerService`'s body, or delete it and add a
   new `@Service` impl? (Cannot have two non-`@Primary` `@Service` impls.)
4. **Send Image: extend `TelegramSender` with `sendPhoto`, or sibling class?** (Recommend extend to
   reuse retry/429/Decision-4-hook machinery.) Confirm `sendPhoto` reuses block/delete hooks.
5. **Sweep cadence mechanism for ~30s:** JobRunr `@Recurring(interval=...)` (no precedent here, all
   existing recurring jobs are daily cron) vs Spring `@Scheduled(fixedDelay)`. Pick one.
6. **Step modeling:** flat `FunnelStep` POJO + `StepType` enum + nullable type fields (recommended,
   matches codebase), vs Spring Data polymorphic `_class` discriminator (no precedent).
7. **Mid-flight edit semantics:** snapshot steps onto execution, version the funnel, or block edits
   on active funnels?
8. **Re-enter semantics (`allow_re_enter`):** enforce via a unique `(funnelId, subscriberId)` index
   (mirroring `raw_updates` DuplicateKey self-heal), or via a query-time guard? What counts as
   "active" for re-entry (running + waiting)?
9. **Set-Custom-Field reuse:** there is NO `SubscriberCustomFieldsService` — the validate→update→
   `recordCustomFieldsSet` logic is inline in `SubscriberCustomFieldsController`. Extract it into a
   new shared service (callable from both the controller and the JobRunr funnel worker, with no
   request-scope deps), or replicate the sequence in the engine?
10. **Subscriber lookup surface:** add a `SubscriberService` lookup-by-chat method, or let the
    engine call `SubscriberRepository.findByProjectIdAndTelegramBotIdAndTelegramChatId` directly?
11. **Engine error isolation:** confirm the real `fire()` must swallow/log its own errors so a
    funnel-start failure never marks the inbound update FAILED and triggers webhook retry storms.
12. **Status gating + cancellation:** confirm only ACTIVE subscribers get sends, and that
    `cancelActiveFor` on `/stop` plus blocked/deleted bounce-flips are the only cancellation paths
    (vs. an explicit pause/cancel API).
