# Code Research: Epic 09 — Subscribers CRM

Mapping of every integration point the Subscribers CRM feature touches in the existing codebase.
File paths are absolute. Where a thing does not yet exist, it is marked "needs to be created"
with a proposed location.

---

## 1. NoOpSubscriberService contract & consumers

### Files (existing, MUST preserve method signatures)

- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/subscriber/SubscriberService.java` (interface, 22 lines)
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/subscriber/NoOpSubscriberService.java` (`@Service`, **NOT `@Primary`**)
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/test/java/com/botfunnel/subscriber/NoOpSubscriberServiceTest.java`

### Contract (must remain byte-identical to the worker call sites)

```java
public interface SubscriberService {
    void upsertFromTelegramUpdate(String projectId,
                                  Long telegramBotId,
                                  Long chatId,
                                  String chatType,        // "private" only — gated by worker
                                  Long telegramUserId,
                                  String firstName,
                                  String lastName,
                                  String username,
                                  String languageCode);

    void markUnsubscribed(String projectId, Long telegramBotId, Long chatId);
}
```

### Consumers

Single production consumer: `ProcessTelegramUpdateJob` (worker). Tests reference via
`@MockitoSpyBean` in `ProcessTelegramUpdateJobTest`. No other module injects it.

### Replacement strategy

Doc comment in `NoOpSubscriberService.java:6` says: *"Epic 05 will replace this `@Service`
directly."* Two options:
- **Delete** `NoOpSubscriberService.java` + its test, add a real `@Service` `SubscriberServiceImpl`.
- **Keep** the interface, add `SubscriberServiceImpl @Primary`, leave the no-op for slice tests
  that don't want CRM side effects.

Same applies symmetrically to the funnel stub:
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/funnel/FunnelTriggerService.java`
- `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/funnel/NoOpFunnelTriggerService.java`
- Signature: `fire(projectId, chatId, triggerType, payload)` + `cancelActiveFor(projectId, chatId)`.
- Out of scope for Epic 05 (belongs to Epic 06). Do not touch.

---

## 2. ProcessTelegramUpdateJob — worker call sites

`/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java`

### Threading model

JobRunr worker thread, blocking-safe (virtual threads enabled in
`application.properties:4` — `spring.threads.virtual.enabled=true`). NOT Netty. All
side effects synchronous.

### Constructor (`ProcessTelegramUpdateJob.java:58-76`)

Injects `RawUpdateRepository`, `BotRepository`, `ProjectRepository`, `MongoTemplate`,
`EventService`, **`SubscriberService`**, **`FunnelTriggerService`**, `MeterRegistry`,
`ObjectMapper`.

### Subscriber call sites

| Site | Trigger | Gating |
|------|---------|--------|
| `handleStart` line 235-241 | `/start` in private chat | `isPrivate && chatId != null` + `bot != null` |
| `dispatch` line 189-195 | plain text in private chat (re-upsert for activity refresh) | `isPrivate && chatId != null` + `bot != null` |
| `handleStop` line 253 | `/stop` in private chat | `isPrivate && chatId != null` + `bot != null` |

Plain-text upsert in `dispatch:189` matters for AC5 (reactivate) — every private text
message lands here, including `/start` (which also hits `handleStart` because of the
`text.startsWith("/")` short-circuit at line 170; **`/start` does NOT fall through to the
plain-text branch** — it is dispatched exclusively via `handleStart`). Real impl must be
idempotent under repeated upserts of the same `(projectId, telegramUserId)`.

### Events written today by the worker (`ProcessTelegramUpdateJob.java:41-44`)

| Constant | String literal | When |
|----------|---------------|------|
| `EVT_COMMAND_START` | `telegram_command_start` | every `/start` (private and group) |
| `EVT_COMMAND_STOP` | `telegram_command_stop` | every `/stop` |
| `EVT_MESSAGE_RECEIVED` | `telegram_message_received` | non-command messages with text |
| `EVT_UPDATE_OTHER` | `telegram_update_other` | non-message slots, media-only |

All written via `eventService.logEvent(userId, type, null /*ip*/, null /*ua*/, metadata)`.
`userId` here = `project.getOwnerId()` (`ProcessTelegramUpdateJob.java:101`) — **NOT
`null`**. This is "owner-context system event" — distinct from the `null userId` precedent
seen in `AuthService` (login_failed before user resolution). Epic 05 should follow the same
rule: pass `project.getOwnerId()` for subscriber-lifecycle events.

### chatType is already passed through

Worker calls `subscriberService.upsertFromTelegramUpdate(projectId, telegramBotId, chatId,
chatType /* "private" only at the call sites */, ...)`. Real impl can re-assert
`"private".equals(chatType)` as defense-in-depth but the gating already happened upstream.

---

## 3. Project module — access control & ownership

### `ProjectService.requireOwned`

`/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/project/ProjectService.java:50-66`

```java
public Project requireOwned(String ownerId, String projectId, boolean includeSoftDeleted)
```

- Foreign owner → `AppException.notFound("Project not found")` (anti-enumeration uniform 404)
- Soft-deleted (`deletedAt != null`) with `includeSoftDeleted=false` → 404
- Malformed `ObjectId` (`IllegalArgumentException` from `repo.findById`) → 404
- Returns the loaded `Project` so callers don't re-fetch.

**MUST be called first** by every project-scoped service handler. Pattern documented in
`patterns.md:155`.

### Project entity (`Project.java`)

- Soft-delete field: `Instant deletedAt` (lines 34, 57-58)
- Indexes:
  - `owner_deleted`: `(ownerId, deletedAt)` non-unique
  - `owner_name_deleted`: `(ownerId, name, deletedAt)` non-unique (service-level uniqueness)
- `auto-index-creation=true` in `application.properties:9`.
- Embedded `customFieldDefinitions` array (Epic 05 scope) — **needs to be added** to
  `Project.java` as a new field. No migration script per `patterns.md:201`
  ("Schema-add convention").

### ProjectController example pattern

`/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/project/ProjectController.java`

- `@RequestMapping("/api/v1/projects")`
- `currentUserId()` helper at line 125-131 reads `SecurityContextHolder` → `AppUserDetails.id()`.
- Endpoints use `@Valid @RequestBody`, `HttpServletRequest httpRequest` for ip/UA extraction
  via `HttpRequestUtils.extractIp/extractUserAgent`.
- Sub-resources follow `@RequestMapping("/api/v1/projects/{projectId}/<module>")` pattern
  (see `BotController.java:21`).

For Epic 05, propose:
- `/api/v1/projects/{projectId}/subscribers` — `SubscribersController`
- `/api/v1/projects/{projectId}/subscribers/{subscriberId}` — single + actions
- `/api/v1/projects/{projectId}/tags` — `TagsController`
- `/api/v1/projects/{projectId}/custom-fields` — `CustomFieldsController` (CRUD on the
  embedded array in `Project`)
- `/api/v1/projects/{projectId}/subscribers/export` — POST creates export job; GET signed-URL serves CSV

---

## 4. TelegramSender — error-handling surface

`/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/bot/TelegramSender.java`

### Current 4xx mapping (`toThrowable` lines 251-269)

| HTTP | Exception | Notes |
|------|-----------|-------|
| 401 | `BotTokenInvalidException` | hard 422 to caller |
| 429 | `TelegramRateLimitException` (internal sentinel) | caught by outer loop, sleeps `retry_after` (clamped 0..30 + jitter), retries |
| other 4xx | `TelegramSendException(rawStatus, description, attempts)` | terminal, audited |

**Currently 403 is NOT distinguished from generic 4xx** — it lands in
`TelegramSendException` with `errorCode=403, errorDescription=scrubbed_telegram_text`. No
chat-not-found special case for 400 either.

### Audit emission (`TelegramSender.java:131-139`)

```java
} catch (RuntimeException ex) {
    if (isTerminalAuditable(ex)) {  // BotTokenInvalidException OR TelegramSendException
        eventService.logEvent(ownerId, EVENT_TELEGRAM_SEND_FAILED,
                null, null, failedMetadata(botId, chatId, ex, attempts.get()));
    }
    throw ex;
}
```

Metadata includes `errorCode`, `errorDescription`, `chatId`, `botId`, `attempts`. Ownership:
the sender owns this audit (`patterns.md:194`).

### Cleanest extension point for `markBlocked` / `markDeleted`

Recommendation: **add a `SubscriberErrorHandler` collaborator** consumed by
`TelegramSender` (constructor-injected) and called inside `toThrowable` (or just after,
inside the catch in `sendText`). Reasons:

- The sender already has `botId` and `chatId` in scope at the point of failure.
- Direct call avoids an event bus / AOP layer (none exists; would be premature).
- The handler can be the `SubscriberService` itself with two new methods:
  `markBlockedByChatId(String projectId, Long telegramBotId, Long chatId)` and
  `markDeletedByChatId(...)`.
- The sender does not know `projectId` directly — only `botId` (Bot._id). The handler must
  resolve `projectId` from `Bot` (already loaded in `sendText:122-124`).

Alternative: extend `TelegramSendException` with a typed `terminalReason` enum
(`BLOCKED_BY_USER`, `CHAT_NOT_FOUND`) and let *callers* (`BotService`,
broadcast workers in Epic 07) react. Pros: looser coupling; sender stays pure. Cons:
every caller must remember to react — silent drift risk. Reject in favour of in-sender call
unless tech-spec disagrees.

429 already retries with backoff — no work to do there.

---

## 5. Existing job/scheduling patterns

### Recurring jobs (daily cron)

`/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/jobs/HardDeleteJob.java`
+ `ProjectHardDeleteJob.java`.

Idiomatic shape:

```java
@Component
public class FooRecurringJob {
    @Recurring(id = "hard-delete-projects", cron = "0 3 * * *")
    @Job(name = "Hard delete soft-deleted projects")
    public void run() { ... }
}
```

- `@Recurring(id=...)` MUST be deterministic and unique across the JVM.
- 5-field cron, UTC (no DST sensitivity for `03:00`).
- `+1ns` cutoff trick to convert strict-`<` finder into inclusive boundary (see
  `ProjectHardDeleteJob.java:53`).

For Epic 05 `ExportCleanupJob`, propose:
- File: `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/subscriber/jobs/ExportCleanupJob.java`
- `@Recurring(id = "subscriber-export-cleanup", cron = "0 3 * * *")` (shares the `03:00 UTC` slot — no contention; jobs run sequentially within JobRunr)
- Retention `Duration.ofDays(7)`; sweep GridFS file + the metadata document(s).

### One-shot enqueued jobs

`/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/webhook/TelegramWebhookController.java:85-87` shows the canonical pattern:

```java
jobScheduler.<ProcessTelegramUpdateJob>enqueue(jobId /*UUID*/, j -> j.handle(rawUpdateId));
```

`JobScheduler` is the injected bean — **NOT** static `BackgroundJob.enqueue` (see
`patterns.md:217`). Deterministic UUID is created via
`UUID.nameUUIDFromBytes(seed.getBytes(UTF_8))` for idempotent self-heal on retry.

For Epic 05 `ExportSubscribersJob`, propose:
- File: `/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/subscriber/jobs/ExportSubscribersJob.java`
- `handle(String exportId)` reads a pre-persisted `subscriber_exports` doc with the segment filter, streams CSV into GridFS, updates status, emails the owner.
- Deterministic UUID from `exportId` for retry safety.

### JobRunr storage config

- Production: `JobRunrMongoConfig.java` provides synchronous `MongoClient` bean for the
  `MongoDBStorageProvider`. Dashboard disabled in `application.properties:37`.
- Test: `JobRunrInMemoryConfig.java` provides `InMemoryStorageProvider`. Background server
  disabled in test profile so `@Recurring` jobs register but do not auto-run; tests invoke
  `job.run()` directly.

---

## 6. MongoDB GridFS readiness

### Classpath

`spring-boot-starter-data-mongodb` is already on the classpath
(`backend/build.gradle:23`), and the synchronous driver `mongodb-driver-sync` is pinned
(line 24). `GridFsTemplate` ships with Spring Data MongoDB — no extra dependency
needed.

### Existing usage

`grep -rn "GridFs\|gridfs"` returns **zero matches** in `backend/` and `frontend/`. Epic 05
is the first user.

### Spring Boot autoconfig

`MongoDataAutoConfiguration` auto-creates a `GridFsTemplate` bean from the existing
`MongoDatabaseFactory` and `MongoConverter`. **No `@Configuration` class needed** — direct
constructor injection of `GridFsTemplate` (or `GridFsOperations` interface) into the
service works out of the box.

### Cleanest pattern (Spring Data MongoDB docs via Context7)

```java
// upload
@Autowired GridFsOperations gridFs;
ObjectId fileId = gridFs.store(inputStream, "subscribers-export-2026-05-24.csv",
        "text/csv", Map.of("exportId", exportId, "projectId", projectId));

// retrieve
GridFsResource resource = gridFs.getResource("subscribers-export-...csv");
InputStreamResource body = new InputStreamResource(resource.getInputStream());
return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
        .body(body);
```

### Signed URL signing

No existing signed-URL helper in the project. Reuse the AES key infrastructure
(`common/crypto/TokenEncryptor`) by signing `(exportId, expiresAt)` as a short opaque
token, OR mint a stateless HMAC-SHA256 of `(exportId|expiresAt)` keyed by a new env var.
Propose new helper `common/crypto/SignedDownloadToken.java` to keep the primitive
co-located with the existing crypto folder.

---

## 7. Redis usage patterns

### Existing rate-limit / brute-force precedents

- `AuthService.java:454-470` — register IP rate-limit. Key: `register:rate:ip:<ip>`,
  TTL 60s, threshold 10.
- `AuthService.java:539-556` — login brute-force counters. Keys:
  `bf:email:<emailLower>` / `bf:ip:<ip>`, TTL 900s, thresholds 5/20.
- `BotService.java:250-269` — bot-connect brute-force (INCR-every-attempt variant).
  Key: `brute:bot-connect:<userId>`, TTL 900s, threshold 10.

### Bean

`RedisTemplate<String, String>` (auto-configured by `spring-boot-starter-data-redis`).
Some classes use `StringRedisTemplate` (e.g. `BotService`, `ProfileService`) — same type,
narrower bound.

### Canonical pattern (INCR + conditional EXPIRE)

```java
Long count = redisTemplate.opsForValue().increment(key);
if (count != null && count == 1L) {
    redisTemplate.expire(key, ttl);
}
if (count != null && count > threshold) {
    throw AppException.tooManyRequests("...");
}
```

Fail-open on Redis errors with WARN log (`patterns.md:179`). For Epic 05, mirror this
for the new-`/start` bucket:

```java
String key = "bf:rate:start:" + projectId;
Duration ttl = Duration.ofMinutes(1);
int threshold = 100;
```

Tech-spec author should decide whether to fail-open (consistent with existing precedent,
admits abuse during outage) or fail-closed (rejects legitimate /start, but never lets a
flood through). Existing convention is fail-open.

---

## 8. Email sending

`/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/email/EmailService.java`

### Template mechanism

- **Raw HTML with `String.replace("{TOKEN}", ...)`** — no FreeMarker, no Thymeleaf.
- Templates live in `backend/src/main/resources/templates/email/`:
  - `verify-email.html`
  - `reset-password.html`
  - `account-blocked.html`
- All values HTML-escaped via `htmlEscape()` (line 111-119).

### API shape

```java
public void sendVerificationEmail(String to, String name, String token);
public void sendPasswordResetEmail(String to, String name, String token);
public void sendAccountBlockedEmail(String to, String name, String supportEmail);
```

All three call `sendAsync(to, subject, htmlBody)` — silent-failure (logs ERROR, swallows
exception). Subjects are hardcoded Ukrainian strings (lines 35, 41, 47).

For Epic 05, add:
- Template: `backend/src/main/resources/templates/email/subscribers-export-ready.html`
- Method: `void sendExportReadyEmail(String to, String name, String downloadUrl, Instant expiresAt)`
- Subject (UA): "Експорт підписників готовий" (or English per user-spec — confirm in Cycle 2).
- The signed download URL replaces a `{URL}` placeholder; never include subscriber data in the email.

JavaMail config in `application.properties:23-28` is already wired (MailHog dev port 1025;
MAIL_USERNAME/PASSWORD env in prod).

---

## 9. Event / audit logging

### Schema

`/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/main/java/com/botfunnel/events/Event.java`

Fields: `id`, `userId` (nullable), `eventType` (String), `ipAddress` (nullable), `userAgent`
(nullable), `metadata` (`Map<String,Object>`), `createdAt`.

Index: `userId_createdAt_desc` compound `(userId, createdAt desc)`.

### API

`EventService.logEvent(String userId, String eventType, String ipAddress, String userAgent, Map<String,Object> metadata): Event`

- Synchronous (`EventService.java:26`). Persistence failure propagates.
- `userId == null` is a documented system-context pattern, used by `AuthService.java:312, 502,
  521` (login_failed before user resolution). For Epic 05, **subscriber-lifecycle events
  should use `project.getOwnerId()` as `userId`** (the worker call sites already do this).

### Existing event types (grep `EVENT_\|EVT_`)

- Auth: `login_success`, `login_failed`, `email_verified`, `password_reset_requested`,
  `password_changed`
- Project: `project_created`, `project_updated`, `project_renamed`, `project_soft_deleted`,
  `project_restored`, `project_hard_deleted`
- Bot: `bot_connected`, `bot_disconnected`, `bot_test_message_sent`
- Telegram: `telegram_message_sent`, `telegram_send_failed`
- Webhook worker: `telegram_command_start`, `telegram_command_stop`,
  `telegram_message_received`, `telegram_update_other`

### Epic 05 — propose

- `subscriber_registered`, `subscriber_reactivated`, `subscriber_unsubscribed`,
  `subscriber_blocked`, `subscriber_deleted`, `subscriber_tag_added`, `subscriber_tag_removed`,
  `subscriber_custom_field_set`
- `tag_created`, `tag_deleted`
- `custom_field_definition_created`, `custom_field_definition_deleted`
- `subscribers_export_requested`, `subscribers_export_completed`, `subscribers_export_failed`,
  `subscribers_export_downloaded`, `subscribers_export_purged`

### SubscriberEvent collection (separate, per user-spec — history feed)

The user-spec calls for a *second* events collection scoped to a single subscriber, with
TTL 1y and index `(subscriberId, createdAt desc)`. **This is a NEW collection** — do NOT
overload the existing `events` collection. Propose:
- File: `backend/src/main/java/com/botfunnel/subscriber/SubscriberEvent.java`
- `@Document(collection = "subscriber_events")`
- TTL via `@Indexed(expireAfter = "365d")` on `createdAt`. (Mirror `RawUpdate.java:54-57`
  for the partial-filter TTL pattern if a status filter is needed; for plain TTL just
  use `expireAfter` alone.)

---

## 10. Frontend conventions

### API call pattern

`/Users/pavlokorolov/IdeaProjects/simple-sender/frontend/composables/useApi.ts`

- `$fetch.create({ baseURL, credentials:'include', onRequest: <CSRF>, onResponseError: <404 interceptor> })`
- CSRF token auto-injected for non-safe verbs via `X-XSRF-TOKEN` header read from
  `XSRF-TOKEN` cookie (HttpOnly=false). Works on SSR via forwarded `cookie` request header.
- 404 interceptor only matches `/api/v1/projects/{id}` exactly (not sub-paths) — sub-path
  404s pass through to module-specific handlers. Subscriber pages can rely on the
  project's interceptor when project goes 404; subscriber-resource 404s need their own
  page-level handling.

### Pinia store conventions

`/Users/pavlokorolov/IdeaProjects/simple-sender/frontend/stores/projects.ts` — setup-store
pattern (`defineStore('name', () => {...})`). Single localStorage key
`bot-funnel.currentProjectId`. Pattern documented in `patterns.md:165`. **No new
localStorage keys without security review** — Subscribers should use Pinia in-memory only.

### i18n key namespace

`/Users/pavlokorolov/IdeaProjects/simple-sender/frontend/i18n/locales/uk.json` +
`en.json`. Current top-level keys: `layout`, `validation`, `projects`, `bot`, `errors`,
`auth`, `dashboard`, `profile`. For Epic 05 add: `subscribers`, `tags`, `customFields`.
Both UA and EN MUST exist (`scripts/check-locales.mjs` is run by `prebuild` to verify
key parity).

### shadcn-vue components scaffolded

Only `Dialog` is currently scaffolded in
`/Users/pavlokorolov/IdeaProjects/simple-sender/frontend/components/ui/dialog/`. Everything
else (Table, Select, Badge, Input, Toast, Sheet, Tabs, ...) needs `pnpm dlx shadcn-vue add
<component>` during Wave 1 of Epic 05. `reka-ui` is the primitives library currently
configured (per `architecture.md:46`). `lucide-vue-next` is available for icons.

### vee-validate + zod schema pattern

`/Users/pavlokorolov/IdeaProjects/simple-sender/frontend/pages/projects/new.vue` is the
canonical example:

```ts
const schemaComputed = computed(() => toTypedSchema(z.object({
    name: z.string().trim().min(3, t('validation.projectNameMin')).max(50, ...),
    ...
})))
const { defineField, handleSubmit, isSubmitting, errors } = useForm({
    validationSchema: schemaComputed,
    initialValues: {...},
})
const [name, nameAttrs] = defineField('name')
```

**Schema MUST be a `ComputedRef`** (live-locale re-validation). Documented in
`patterns.md:145`.

### List page pattern

`/Users/pavlokorolov/IdeaProjects/simple-sender/frontend/pages/projects/index.vue` — no
pagination, no filters yet (Epic 05 list will exceed this). It does:
- `useApi()<T[]>('/api/v1/...')` with optional query params
- `useApiError(err, 'context')` for error message resolution
- inline toast banner with auto-fade timer (4s)
- `data-test` attributes on every interactive element

For Epic 05 subscriber list, pagination is required (>1000 rows expected). No existing
precedent — either backend cursor-based or page/limit query params; tech-spec to decide.

---

## 11. Existing integration test patterns

### Base class

`/Users/pavlokorolov/IdeaProjects/simple-sender/backend/src/test/java/com/botfunnel/AbstractIntegrationTest.java`

- `@SpringBootTest(webEnvironment = RANDOM_PORT)`
- `@AutoConfigureMockMvc`
- `@Testcontainers` with **singleton-container static fields** (started once per JVM):
  - `MongoDBContainer mongo:8.0`
  - `GenericContainer redis:7.4-alpine`
  - `MailpitContainer axllent/mailpit:v1.29.7`
- Imports `JobRunrInMemoryConfig` → in-memory `StorageProvider`
- Imports `MailpitTestConfig` → primary `JavaMailSender` pointed at testcontainer SMTP

### Client convention

`MockMvc` (autowired) for inbound assertions. **Not `TestRestTemplate`** — only used for
the AC2 P99 latency probe (`TelegramWebhookP99IT`).

### Testing JobRunr-enqueued jobs

- `JobRunrInMemoryConfig` injects `InMemoryStorageProvider`. Background-job-server is
  disabled in `application-test.properties` so no worker threads run.
- Tests assert ENQUEUED state via `storageProvider.countJobs(StateName.ENQUEUED)` (see
  `TelegramWebhookControllerIT.java:158-160`).
- For recurring jobs: test calls the `@Recurring` method **directly**
  (`ProjectHardDeleteJobIT.java:168` — `job.hardDeleteSoftDeletedProjects();`).
- For end-to-end one-shot dispatch, use `await().atMost(...).untilAsserted(...)` over the
  in-memory storage count (`TelegramWebhookControllerIT.java:166-170`).

### Representative signatures

```java
class ProjectHardDeleteJobIT extends AbstractIntegrationTest {
    @Autowired ProjectRepository projectRepository;
    @Autowired EventRepository eventRepository;
    @Autowired ProjectHardDeleteJob job;
    @Test void cron_deletesOldProjectsAndCascadesEvents(CapturedOutput output) { ... }
}
```

```java
class TelegramWebhookControllerIT extends AbstractIntegrationTest {
    @Autowired StorageProvider storageProvider;
    @Autowired JobScheduler jobScheduler;
    @Test void receive_happyPath_returns200_persistsAndEnqueues() throws Exception { ... }
}
```

### Slow tag

`@Tag("slow")` excluded by default in `build.gradle:42-46`. Latency-sensitive tests opt in
via `./gradlew test -PrunSlow=true`. Subscriber CSV-export latency probe (if any) should
use this tag.

---

## 12. Package layout & DTO conventions

### Existing package layout (precedent)

```
com.botfunnel.<module>/
    <Module>Controller.java
    <Module>Service.java
    <Module>Repository.java
    <Module>.java                  (entity)
    <ModuleStatus>.java            (enum, if any)
    <module exception>.java        (one per error class)
    dto/
        Create<Module>Request.java
        Update<Module>Request.java
        <Module>Response.java
    validation/
        Valid<X>.java              (custom annotations)
        Valid<X>Validator.java
```

Concrete examples: `com.botfunnel.bot`, `com.botfunnel.project`, `com.botfunnel.auth`.

### Epic 05 proposed package layout

```
com.botfunnel.subscriber/
    Subscriber.java                              (entity)
    SubscriberStatus.java                        (enum: active|unsubscribed|blocked|deleted)
    SubscriberRepository.java
    SubscriberService.java                       (existing interface — keep)
    SubscriberServiceImpl.java                   (new — replaces NoOpSubscriberService)
    SubscriberController.java
    SubscriberEvent.java                         (entity, separate collection)
    SubscriberEventRepository.java
    SegmentFilter.java                           (immutable record, used by export + UI list)
    dto/
        SubscriberResponse.java
        TagAssignRequest.java
        CustomFieldSetRequest.java
        ExportRequest.java
        ExportResponse.java
    jobs/
        ExportSubscribersJob.java
        ExportCleanupJob.java
    export/
        SubscriberCsvWriter.java                 (streaming CSV serialization)
        SignedDownloadToken.java                 (HMAC token mint + verify)
com.botfunnel.tag/                               (new sibling module — separate collection)
    Tag.java
    TagRepository.java
    TagService.java
    TagController.java
    dto/
        CreateTagRequest.java
        TagResponse.java
com.botfunnel.project/                           (modify existing)
    Project.java                                 (add embedded customFieldDefinitions[])
    CustomFieldDefinition.java                   (new embedded record)
    CustomFieldType.java                         (new enum: string|number|boolean|date)
    CustomFieldsController.java                  (new)
    dto/CreateCustomFieldRequest.java
```

### DTO style (precedent)

- **Records** (not classes) — `record CreateProjectRequest(...)`.
- **Suffixes**: `Request` / `Response`.
- **JSON**: `camelCase` (Jackson default; no `@JsonProperty` overrides anywhere).
- **`@JsonIgnoreProperties(ignoreUnknown = true)`** on every Request record (mass-assignment
  defense — `patterns.md:161`).
- `ownerId` / project-bound fields are **never** read from request body — only from
  `SecurityContextHolder` via `currentUserId()`.

### Validation

- Bean Validation (`jakarta.validation.constraints.*`): `@NotBlank`, `@Size(min,max)`,
  `@Email`, `@NotNull`, `@Positive`, etc.
- Custom annotations live under `<module>/validation/`. Examples:
  `ValidTimezone` (`project/validation/`), `ValidPassword` (`auth/validation/`).
- For Epic 05 propose: `ValidCustomFieldName`, `ValidTagName` (or just `@Pattern`).

### Error responses

`GlobalErrorHandler` (`common/GlobalErrorHandler.java`) maps:
- `AppException` → `{status, body: {message, code}}`
- `MethodArgumentNotValidException` (`@Valid` fail) → 400 with comma-joined field summary,
  `code: null`
- Anything else → 500 `{message:"Internal server error"}`

Convention (`patterns.md:157`): **422 + code** for business rule violations; **400** for
bean validation; **409 + code** for unique conflict. For Epic 05:
- `tag_name_taken` (409)
- `tag_limit_reached` (422)
- `custom_field_limit_reached` (422) — cap = 20 per project
- `custom_field_name_taken` (409)
- `custom_field_type_mismatch` (422) — value-vs-definition type check
- `export_in_flight` (409) — one export at a time per project (if enforced)

---

## 13. Risks / pitfalls

### Code-level findings

- **No GridFS infrastructure** — Epic 05 is the first user. Verify `MongoDataAutoConfiguration`
  picks up `GridFsTemplate` in the test profile too (no known issue, but worth a smoke).
- **No CSV streaming helper exists** — write a custom `SubscriberCsvWriter` that streams
  rows from a `MongoTemplate` cursor directly into a `PipedOutputStream` bridged to the
  GridFS upload, avoiding any in-memory list materialization. For 100k+ subscribers a naive
  `List<Subscriber>` would OOM.
- **No signed-URL primitive exists** — needs to be created from scratch. Co-locate with
  `common/crypto/` to keep crypto primitives discoverable.
- **No partial scaffold for `Subscriber.java` / `SubscriberRepository.java`** — grep returns
  zero matches outside the existing stub. Greenfield.
- **`Subscriber` document collisions with `subscribers` collection name** — `architecture.md:59`
  already commits to `subscribers` collection; no rename risk.
- **Soft-delete cascade gap precedent** (`patterns.md:207`): when a project is soft-deleted,
  its subscribers/tags/exports are NOT auto-cascaded. The `ProjectHardDeleteJob` cascade-
  deletes events but ignores other project-scoped collections. Epic 05 either: (a) extend
  `ProjectHardDeleteJob` to sweep `subscribers`, `subscriber_events`, `tags`, GridFS files,
  and `subscriber_exports`; or (b) document the deferred cascade explicitly in the tech-spec
  Risks table.
- **Indexes auto-created in dev only** (`application.properties:9`). For production,
  new `@Indexed` and `@CompoundIndex` declarations must be ratified into a manual
  index-creation step or deployment runbook (currently no automated production index
  migration exists — see deferral note in `Event.java:10-11`).
- **No TODO/FIXME comments touch subscriber/, webhook/, or bot/ scope.** Clean.
- **`SubscriberService` doc comment claims direct replacement** but the call sites in
  `ProcessTelegramUpdateJob` will not change. The replacement is purely DI-level. No
  schema-version conflict.
- **`chatType` argument**: worker passes `"private"` exclusively (see `ProcessTelegramUpdateJob.java:236`
  `handleStart` and `:189` `dispatch`). The interface technically allows any string, but
  the real impl can `requireEquals("private")` as a defensive assert.
- **403 → markBlocked branch**: the existing `TelegramSender` catches `RuntimeException`
  and audits — adding a side-effect call inside that catch needs careful ordering to keep
  the existing `telegram_send_failed` event emission semantics intact. New
  `subscriber_blocked` / `subscriber_deleted` events should be emitted by
  `SubscriberServiceImpl.markBlocked` / `markDeleted`, NOT by `TelegramSender`.

### Documentation / spec findings

- `architecture.md:59-66` already commits to `subscribers`, `tags`, `subscriber_events`
  collection names — keep these.
- `architecture.md:78` mentions `SubscriberService` is consumed by the worker — alignment OK.
- `patterns.md:155` lists subscribers in the "future modules must call `requireOwned`" list — must follow.
- No tech-debt entry flags Epic 05 specifically. Greenfield epic on the backend side; mostly
  net-new code.

---

## 14. Constraints & infrastructure

### Runtime

- Java 21 toolchain (`build.gradle:13`)
- Spring Boot 3.5.0 (`build.gradle:4`)
- Virtual threads enabled (`application.properties:4`)
- Spring MVC (NOT WebFlux — completed migration; reactive driver gone from classpath)

### Dependency notes

- `spring-boot-starter-data-mongodb` (sync driver) — GridFS comes for free.
- `mongodb-driver-sync` pinned.
- `spring-boot-starter-data-redis` (Lettuce sync).
- `org.jobrunr:jobrunr-spring-boot-3-starter:7.3.2`
- `spring-boot-starter-mail` — JavaMailSender available.
- `spring-boot-starter-validation` — Bean Validation 3 (`jakarta.validation.constraints`).
- `io.micrometer:micrometer-core` — for `MeterRegistry`. `SimpleMeterRegistry` wired by
  `common/metrics/MeterRegistryConfig.java` (`patterns.md:220`).
- No actuator — adding metric counters works but no `/actuator/prometheus` endpoint.
- Testcontainers: `mongodb`, `junit-jupiter`, `testcontainers-mailpit`, `mockwebserver`.
- Frontend: Nuxt 4.4, `@vee-validate/zod`, `@pinia/nuxt`, `reka-ui`, `shadcn-vue` (only
  Dialog scaffolded so far), Playwright for E2E.

### Pre-commit hooks

- gitleaks via `bash scripts/install-hooks.sh` (idempotent, soft-fail if not installed).
- No pre-push tests (planned per `patterns.md:24-25`).

### Env / secrets (existing keys to be aware of)

`backend/src/main/resources/application.properties` reads:
- `MONGODB_URI`, `REDIS_URL`, `SESSION_COOKIE_SECURE/SAME_SITE`, `SESSION_TTL_*`
- `MAIL_HOST/PORT/USERNAME/PASSWORD/FROM`, `SUPPORT_EMAIL`, `APP_URL`
- `PROJECTS_MAX_PER_USER`, `SUPER_ADMIN_EMAIL/PASSWORD`
- `BOT_TOKEN_ENCRYPTION_KEY` (32-byte hex), `TELEGRAM_BASE_URL`

New keys Epic 05 will likely need:
- `SUBSCRIBER_EXPORT_TOKEN_KEY` — HMAC key for signed download URLs.
- `SUBSCRIBER_EXPORT_RETENTION_DAYS=7` — overridable.
- `SUBSCRIBER_RATE_LIMIT_START_PER_MIN=100` — Redis bucket threshold.

### Deployment

`CLAUDE.md`: *"ALL deployments via GitHub CI/CD only. Direct server access (SSH, container
restarts) only for emergency debugging."* No SSH-deploy paths.

`work/` directory is gitignored — feature artefacts stay local.

---

## 15. Open questions for Cycle 2 (user-spec interview to re-probe)

1. **Tag entity scope and uniqueness.** Tags as a *separate* collection vs `Tag[]` array
   embedded in `Project`. The brief says "separate `tags` collection" — confirm. Tag name
   uniqueness scope = per-project, case-sensitive? (Mongo collation matters for
   non-Latin scripts.) Tag rename behaviour — propagate to subscribers, or
   alias?
2. **Soft-delete cascade behaviour.** When a project is soft-deleted (7d retention before
   hard delete), what happens to its subscribers, tags, custom fields, in-flight exports,
   GridFS files? Cascade now (extend `ProjectHardDeleteJob`) or defer (separate sweeper
   job)? Implications for the export feature: a signed URL for a project that gets soft-
   deleted mid-flight should resolve to 404 or 410?
3. **Subscriber soft-delete vs hard-delete.** Status `deleted` means subscriber is hidden
   from the list but kept in DB for audit; user-spec doesn't specify a retention window or a
   hard-delete pass. Does the existing `ProjectHardDeleteJob`-style cron need a sibling
   for subscribers (e.g. purge `status=deleted` rows older than N days), or is
   indefinite retention intended?
4. **CSV export concurrency control.** One export at a time per project, or queue
   unbounded? If queued, what's the response code on a duplicate request? Where does the
   export-in-flight state live (Redis lock or `subscriber_exports` doc status)?
5. **Custom field rename / delete semantics.** If a project owner renames a custom field
   definition, what happens to subscribers that have a value for that field — preserved
   under the new key (rename), preserved under the old key (audit-only), or deleted? Same
   question for deleting a definition. Important for the 20-field cap (delete-and-recreate
   loophole).
6. **TelegramSender error-hook scope.** Subscriber-state mutations from inside the sender
   couples two modules. Should the hook be a constructor-injected `SubscriberService`
   collaborator on the sender (tight coupling, simple), an event publisher
   (`ApplicationEventPublisher` listener pattern, loose), or a typed-exception bubble that
   each caller reacts to (no shared collaborator)? The current codebase has zero precedent
   for `ApplicationEventPublisher` — picking it for one feature creates a single-use
   pattern.

---

## 16. Implementation Verification (for tech-spec)

> Added: 2026-05-24. Verified facts with file:line citations for each tech-spec open question.
> Sources: codebase grep+read; Context7 `/spring-projects/spring-data-mongodb` for index/text queries.

### 16.1 TelegramSender constructor + sendText signature

**Files**: `backend/src/main/java/com/botfunnel/bot/TelegramSender.java`, `BotService.java`.

**(a) Constructor (`TelegramSender.java:74-82`)** — primary `@Autowired` ctor:

```java
public TelegramSender(RestClient.Builder builder,
                      @Value("${app.telegram.base-url}") String baseUrl,
                      BotRepository botRepository,
                      TokenEncryptor tokenEncryptor,
                      EventService eventService)
```

Package-private test ctor (lines 88-110) adds two `Duration` params (`responseTimeout`,
`overallTimeout`) for fast-failing tests. To wire `SubscriberService` without breaking tests,
**append it as the LAST parameter of BOTH constructors** — the test ctor delegates from the
prod ctor at line 80-81.

**(b) `sendText` signature (`TelegramSender.java:112-140`)**:

```java
public SentMessage sendText(String botId, Long chatId, String text,
                            String parseMode, String ownerId)
```

`Bot` is NOT passed in — it is loaded inside the method via `botRepository.findById(botId)`
(line 122). The `projectId` is available on the loaded `Bot.getProjectId()` (see
`Bot.java:69-70`). The error-hook can resolve `projectId` from the same loaded `Bot` without
extra DB calls.

**(c) Existing catch block (`TelegramSender.java:131-139`)** — only emits
`telegram_send_failed` for `isTerminalAuditable` exceptions (`BotTokenInvalidException` OR
`TelegramSendException`, lines 330-336):

```java
} catch (RuntimeException ex) {
    if (isTerminalAuditable(ex)) {
        log.error("Telegram send terminal failure attempts={}: {}",
                attempts.get(), TelegramApiClient.scrubTokens(ex.getMessage()));
        eventService.logEvent(ownerId, EVENT_TELEGRAM_SEND_FAILED,
                null, null, failedMetadata(botId, chatId, ex, attempts.get()));
    }
    throw ex;
}
```

**Recommendation**: extend `TelegramSendException` to carry a typed `terminalReason` enum
(`BLOCKED_BY_USER` on 403, `CHAT_NOT_FOUND` on 400 with description "chat not found").
Inside the catch, AFTER emitting `telegram_send_failed`, BEFORE `throw ex`, call
`subscriberService.markBlockedByChatId(bot.getProjectId(), botId, chatId)` /
`markDeletedByChatId(...)`. The `bot` reference is captured at line 122 — promote it to a
method-scope local. Keep `telegram_send_failed` emission intact (do not swap order).

### 16.2 Mongo 8 partial-unique index with `$in` in Spring Data MongoDB

**Direct precedent**: `backend/src/main/java/com/botfunnel/webhook/RawUpdate.java:54-57`:

```java
@Indexed(name = "ttl_createdAt",
        expireAfter = "90d",
        partialFilter = "{ 'processingStatus': { $in: ['PENDING', 'DONE'] } }")
private Instant createdAt;
```

`$in` IS supported by Mongo's partial-filter expression evaluator and Spring Data MongoDB
passes the JSON literal through verbatim. `auto-index-creation=true` in
`application.properties:9` builds it at boot. The compound-partial precedent is on `Bot.java:18-28`
(single-value partial filter `{ 'status': 'CONNECTED' }`).

Context7 (`/spring-projects/spring-data-mongodb`) confirms `partialFilter` accepts any
JSON object Mongo accepts as a filter expression — no Spring-side restriction. No docs
example uses `$in` explicitly but the operator is unrestricted.

**Recommendation for AC17 `export_in_flight`**:

```java
@CompoundIndex(name = "exports_in_flight_unique",
        def = "{'projectId': 1}",
        unique = true,
        partialFilter = "{ 'status': { $in: ['PENDING', 'RUNNING'] } }")
```

Persist `ExportStatus.PENDING.name()` / `RUNNING.name()` (UPPERCASE — Spring persists
enums via `.name()`, see `Bot.java:31-40` defensive class-load assertion — add the same
guard on `SubscriberExport`). On race, the second `subscriberExportRepository.save(...)`
throws `DuplicateKeyException` → map to 409 `export_in_flight` via `mapPersistError`
pattern (`BotService.java:189-208`). No Redis lock needed; DB-level guarantee.

### 16.3 Existing text-index precedent

`grep -rn "TextIndexed\|@TextIndexed\|TextScore\|TextCriteria" backend/src/main` → **zero
matches**. Epic 05 is the first user.

**Recommendation** (from Context7 docs above):

```java
@Document(collection = "subscribers")
public class Subscriber {
    @TextIndexed(weight = 3) String firstName;
    @TextIndexed(weight = 3) String lastName;
    @TextIndexed(weight = 5) String username;  // higher weight — primary handle
    @TextScore Float score;  // populated when querying via TextCriteria
}
```

Repository query (per Context7 example):

```java
TextCriteria criteria = TextCriteria.forDefaultLanguage().matchingAny(input.split("\\s+"));
Page<Subscriber> page = subscriberRepository.findAllBy(criteria,
        PageRequest.of(0, 50, Sort.by("score").descending()));
```

Caveat: Mongo allows **only one text index per collection**, but `@TextIndexed` on
multiple fields combines into one (per Context7 Index Management section). For Cyrillic
support set `@TextIndexed(language = "russian")` on the doc OR use default-language="none"
to skip stemming. Tech-spec must pick.

### 16.4 AbstractIntegrationTest JobRunr + Mailpit + GridFS patterns

**(a) JobRunr in-memory** (`JobRunrInMemoryConfig.java:23-28`) — provides
`InMemoryStorageProvider` wired with `JobMapper`. Background server disabled in
`application-test.properties`. Tests autowire the recurring/one-shot job bean directly
and invoke `.handle(...)` / `.hardDeleteSoftDeletedProjects()` synchronously
(`ProcessTelegramUpdateJobTest.java:59` autowires `ProcessTelegramUpdateJob job;` then
`job.handle(raw.getId())` on line 122). For controller→worker assertions, use
`storageProvider.countJobs(StateName.ENQUEUED)` polled via Awaitility
(`TelegramWebhookControllerIT.java:158-170`):

```java
await().atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(20))
        .untilAsserted(() -> assertThat(enqueuedCount()).isEqualTo(expected));
```

**(b) Mailpit pattern** (`AbstractIntegrationTest.java:37-47, 58-60` + `AuthControllerIT.java:86-116`):

```java
private MailpitClient mailpit() { return MAILPIT.getClient(); }

mailpit().deleteAllMessages();  // in @BeforeEach
// ... action that sends email ...
await().atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> mailpit().getMessageCount() >= 1);
Message latest = mailpit().getAllMessages().get(0);
String html = mailpit().getMessageHtml(latest.id());
```

For `sendExportReadyEmail`: assert `html.contains("/api/v1/projects/.../subscribers/export/download")`
plus the signed-token segment + expiry display.

**(c) GridFS assertion**: zero precedent in repo. Standard Spring Data approach
— autowire `GridFsOperations` (or `GridFsTemplate`), then:

```java
GridFSFile file = gridFsOperations.findOne(
        Query.query(Criteria.where("metadata.exportId").is(exportId)));
assertThat(file).isNotNull();
assertThat(file.getMetadata().getString("contentType")).isEqualTo("text/csv");
```

### 16.5 ProjectHardDeleteJob cascade extension

**File**: `backend/src/main/java/com/botfunnel/jobs/ProjectHardDeleteJob.java`.

**Annotation** (line 46-47):

```java
@Recurring(id = "hard-delete-projects", cron = "0 3 * * *")
@Job(name = "Hard delete soft-deleted projects")
```

**Cutoff trick** (line 53):

```java
Instant cutoff = Instant.now().minus(RETENTION).plusNanos(1);
```

**Current cascade order** (lines 67-93): events `metadata.projectId IN deletedIds` →
emit `project_hard_deleted` per project → drop project documents. Single-collection swaps
per step, no replica-set tx (noted line 74-76); idempotent recovery via next run.

**Integration test** (`ProjectHardDeleteJobIT.java`):

```java
class ProjectHardDeleteJobIT extends AbstractIntegrationTest {
    @Autowired ProjectHardDeleteJob job;
    @Test void cron_deletesOldProjectsAndCascadesEvents(CapturedOutput output) { ... }
}
```

**Recommendation for AC22**: **EXTEND `ProjectHardDeleteJob` in place** (do NOT spawn a
separate `SubscriberCascadeJob`). The existing structured INFO log
(`ProjectHardDeleteJob.java:60-61, 95-96`) already commits to a single per-run
observability line; splitting jobs forces operators to correlate two cron logs to verify
"project N truly cleaned". The 5-collection cascade is still bounded — daily, batched by
project IDs. Append between step 1 (events) and step 3 (drop projects):

```
1. remove events             — existing
1a. GridFsTemplate.delete( Query.query(Criteria.where("metadata.projectId").in(deletedIds)) )
1b. template.remove(... "subscriber_exports")
1c. template.remove(... "subscriber_events")  // also TTL'd at 365d but explicit sweep prevents orphans
1d. template.remove(... "subscribers")
1e. template.remove(... "tags")
2. emit project_hard_deleted — existing
3. drop projects             — existing
```

Order matters: GridFS files first (so SubscriberExport→GridFS reference dangling is
impossible), then export metadata, then events, then subscribers, then tags. Each step
logs its own removedCount in the final INFO line.

### 16.6 ProcessTelegramUpdateJob subscriber call sites (verbatim)

**File**: `backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java`.

**`handleStart`** (lines 234-241) — `/start` in private chat AFTER ownerChatId CAS:

```java
com.botfunnel.webhook.dto.User from = message.from();
subscriberService.upsertFromTelegramUpdate(
        projectId, telegramBotId, chatId, "private",
        from == null ? null : from.id(),
        from == null ? null : from.first_name(),
        from == null ? null : from.last_name(),
        from == null ? null : from.username(),
        from == null ? null : from.language_code());
```

**`dispatch` plain-text branch** (lines 188-195):

```java
com.botfunnel.webhook.dto.User from = message.from();
subscriberService.upsertFromTelegramUpdate(
        projectId, bot.getTelegramBotId(), chatId, chatType,
        from == null ? null : from.id(),
        from == null ? null : from.first_name(),
        from == null ? null : from.last_name(),
        from == null ? null : from.username(),
        from == null ? null : from.language_code());
```

**`handleStop`** (line 253): `subscriberService.markUnsubscribed(projectId, bot.getTelegramBotId(), chatId);`

**(a)** Param order: `(projectId, telegramBotId, chatId, chatType, telegramUserId, firstName, lastName, username, languageCode)` — matches `SubscriberService.java:10-18` interface byte-identically.

**(b)** No try/catch around the calls — exceptions propagate to the worker's outer
`catch (Throwable t)` at `ProcessTelegramUpdateJob.java:109-111` → `handleFailure` writes
FAILED + scrubbed/truncated error and rethrows for JobRunr retry.

**(c)** No `lastSeenAt` argument — `SubscriberServiceImpl` owns the timestamp internally
(`Instant.now()` at upsert site). Interface stays unchanged.

### 16.7 common/crypto inventory + SignedDownloadToken proposal

**Existing files** (`backend/src/main/java/com/botfunnel/common/crypto/`):

- `TokenEncryptor.java` — `@Component`, AES-GCM/NoPadding, 32-byte key from
  `${app.bot.token-encryption-key}` (env `BOT_TOKEN_ENCRYPTION_KEY`). Fail-fast on blank /
  non-hex / wrong-length (lines 62-82) — message NEVER echoes the misconfigured key value.
- `EncryptedValue.java` — `record EncryptedValue(byte[] iv, byte[] ciphertext)`.
- `Sha256Hex.java` — `public static String hex(String input)` returning lowercase hex of
  SHA-256(UTF-8). Used by `BotService` for webhook-secret hashing and `WebhookSecretVerifier`
  for comparison.

**Env-var precedent** (`TokenEncryptor.java:28`, `:62-82`): `@Value("${app.bot.token-encryption-key}")`
bound via `application.properties` to `BOT_TOKEN_ENCRYPTION_KEY`. The `decodeKey` helper is the
canonical fail-fast template — copy it.

**Recommendation: `common/crypto/SignedDownloadToken.java`**

```java
@Component
public class SignedDownloadToken {
    private static final String PROPERTY_NAME = "SUBSCRIBER_EXPORT_TOKEN_KEY";
    private static final String HMAC_ALG = "HmacSHA256";
    private final SecretKeySpec key;

    public SignedDownloadToken(@Value("${app.subscriber.export.token-key}") String hexKey) {
        this.key = new SecretKeySpec(decodeKey(hexKey), HMAC_ALG);  // reuse fail-fast template
    }

    public String mint(String exportId, String projectId, Instant expiresAt) {
        // payload = base64url(exportId + "|" + projectId + "|" + expiresAt.toEpochMilli())
        // signature = base64url(HMAC-SHA256(key, payload))
        // token = payload + "." + signature
    }

    public record Verified(String exportId, String projectId, Instant expiresAt) {}

    public Verified verify(String token) {  // throws AppException on tamper/expiry
        // split on '.', recompute HMAC, MessageDigest.isEqual(...) for constant-time compare
        // check expiresAt > Instant.now() → else throw AppException.gone(...)
    }
}
```

Env var: `SUBSCRIBER_EXPORT_TOKEN_KEY` (32-byte hex). Property: `app.subscriber.export.token-key`.
Mirror `TokenEncryptor.decodeKey`'s fail-fast message format (`PROPERTY_NAME + " must be 32 bytes / 64 hex characters; ..."`).
Constant-time compare via `java.security.MessageDigest.isEqual(byte[], byte[])`.

### 16.8 EmailService extension

**File**: `backend/src/main/java/com/botfunnel/email/EmailService.java`.

**Existing methods** (lines 32-48):

```java
public void sendVerificationEmail(String to, String name, String token);     // Subject UA: "Підтвердіть email"
public void sendPasswordResetEmail(String to, String name, String token);    // "Скидання пароля"
public void sendAccountBlockedEmail(String to, String name, String supportEmail);  // "Ваш акаунт заблоковано"
```

**Template loading** (`loadTemplate`, lines 98-109): classpath via `getClass().getResourceAsStream(path)`
where path is `/templates/email/<name>.html`. Returns `""` on missing file (logs ERROR).
Existing templates: `verify-email.html`, `reset-password.html`, `account-blocked.html`.

**Substitution** (lines 50-76): `String.replace("{TOKEN}", htmlEscape(value))` plus
`{NAME}`, `{APP_URL}`, `{SUPPORT_EMAIL}`. `htmlEscape` (lines 111-119) escapes
`& < > " '`. Null-safe (returns "").

**`sendAsync` silent-failure** (lines 82-96): wraps `MimeMessageHelper`, sets UTF-8, catches
`Exception` → logs ERROR (with stack trace) → swallows. Caller never sees SMTP failure.

**Recommendation**:

```java
public void sendExportReadyEmail(String to, String name, String downloadUrl, Instant expiresAt) {
    String body = buildExportReadyBody(name, downloadUrl, expiresAt);
    if (body.isEmpty()) return;
    sendAsync(to, "Експорт підписників готовий", body);
}

public void sendExportFailedEmail(String to, String name) {
    String body = buildExportFailedBody(name);
    if (body.isEmpty()) return;
    sendAsync(to, "Експорт підписників не вдався", body);
}

String buildExportReadyBody(String name, String downloadUrl, Instant expiresAt) {
    String template = loadTemplate("/templates/email/subscribers-export-ready.html");
    if (template.isEmpty()) return "";
    return template
            .replace("{NAME}", htmlEscape(name))
            .replace("{URL}", htmlEscape(downloadUrl))
            .replace("{EXPIRES_AT}", htmlEscape(expiresAt.toString()))
            .replace("{APP_URL}", htmlEscape(appUrl));
}
```

Templates: `backend/src/main/resources/templates/email/subscribers-export-ready.html` +
`subscribers-export-failed.html`. Per security guidance: NEVER include subscriber rows in
the email body — only the signed URL. URL itself carries the project/export ids in the HMAC
payload (16.7) so the link is single-purpose and bounded by `expiresAt`.

### 16.9 Redis brute-force pattern (INCR-every-attempt)

**File**: `backend/src/main/java/com/botfunnel/bot/BotService.java:250-278`.

```java
private void incrementBruteForceCounter(String userId) {
    String key = bruteForceKey(userId);  // "brute:bot-connect:" + userId
    try {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, BRUTE_TTL);  // Duration.ofSeconds(900)
        }
        if (count != null && count > BRUTE_FORCE_THRESHOLD) {  // = 10
            throw AppException.tooManyRequests("Too many Connect attempts. Try again later.");
        }
    } catch (AppException e) { throw e; }
    catch (Exception e) {
        log.warn(REDIS_FAIL_OPEN_WARN, e.getMessage());  // dedicated greppable WARN constant
    }
}
```

Key features to mirror in Epic 05:
- **INCR before EXPIRE**, EXPIRE only on first-bucket (count == 1L).
- **Threshold check after INCR** — `> threshold` not `>= threshold`.
- **Fail-open**: any non-AppException catches a Redis transport failure and downgrades to
  WARN (greppable constant). The request proceeds. Documented as intentional trade-off
  (line 46-49, 254).
- **Reset on success** (`resetBruteForceCounter`, lines 271-278) wraps `redisTemplate.delete(key)`
  in the same fail-open try/catch with the same WARN constant.

**For Epic 05** — new-`/start` bucket:
- Key: `"bf:rate:start:" + projectId`. TTL `Duration.ofMinutes(1)`. Threshold = 100.
  Mirror layout exactly. On rate-trip, throw `AppException.tooManyRequests("...")` and the
  worker's `handleFailure` writes FAILED — JobRunr retries with backoff (acceptable —
  Telegram already retries our HTTP non-200).

- Download-link bucket: `"bf:download:export:" + projectId`. TTL `Duration.ofMinutes(15)`.
  Threshold = 20 (rate-cap to thwart enumeration). Same fail-open behavior. Use a NEW
  greppable WARN constant: `EXPORT_DOWNLOAD_RATE_REDIS_FAIL_OPEN`.

### 16.10 Frontend cursor-pagination precedent

`grep -rn "cursor\|nextCursor\|loadMore" frontend/composables frontend/pages` → **zero
relevant matches** (one CSS `cursor-not-allowed`). Epic 05 is first user.

**Existing list page** (`frontend/pages/projects/index.vue`) — no pagination, no filters,
single `useApi()<T[]>(...)`.

**Recommendation — canonical Load-More pattern**:

Pinia setup-store mirror of `frontend/stores/projects.ts`:

```ts
export const useSubscribersStore = defineStore('subscribers', () => {
  const items = ref<Subscriber[]>([])
  const nextCursor = ref<string | null>(null)
  const loading = ref(false)

  async function loadFirstPage(filter: SegmentFilter) {
    items.value = []
    nextCursor.value = null
    await loadMore(filter)
  }

  async function loadMore(filter: SegmentFilter) {
    if (loading.value || (items.value.length > 0 && !nextCursor.value)) return
    loading.value = true
    try {
      const res = await useApi()<PageResponse<Subscriber>>(
        `/api/v1/projects/${pid}/subscribers`,
        { query: { ...filter, cursor: nextCursor.value, limit: 50 } })
      items.value.push(...res.items)
      nextCursor.value = res.nextCursor
    } finally { loading.value = false }
  }
  return { items, nextCursor, loading, loadFirstPage, loadMore }
})
```

Backend contract: `{ items: T[], nextCursor: string | null }`. Cursor = opaque base64 of
`{lastSeenAt, lastId}` for tie-breaking (subscribers list is sorted by `lastSeenAt desc`,
then `_id desc`). Same shape reusable for `subscriber_events` history page.

### 16.11 `@Tag("slow")` precedent

**`backend/build.gradle:46-56`**:

```groovy
tasks.named('test') {
    useJUnitPlatform {
        if (!project.hasProperty('runSlow')) {
            excludeTags 'slow'
        }
    }
}
```

**Existing slow test**: `backend/src/test/java/com/botfunnel/webhook/TelegramWebhookP99IT.java:33-35`:

```java
// ... @Tag("slow") so the default CI run skips this; opt-in via -PrunSlow=true.
@Tag("slow")
class TelegramWebhookP99IT extends AbstractIntegrationTest { ... }
```

Confirmed convention: `./gradlew test -PrunSlow=true` runs `@Tag("slow")` tests. Subscriber
CSV-export latency/throughput probe (e.g. 100k-row export under 60s budget) should use
`@Tag("slow")`.

### 16.12 ProcessTelegramUpdateJobTest patterns to mirror

**File**: `backend/src/test/java/com/botfunnel/webhook/ProcessTelegramUpdateJobTest.java`.

**(a) Spy wiring** (lines 60-63):

```java
@MockitoSpyBean SubscriberService subscriberService;
@MockitoSpyBean FunnelTriggerService funnelTriggerService;
@MockitoSpyBean com.botfunnel.events.EventService eventService;
```

`Mockito.reset(...)` in `@BeforeEach` cleanAndSeed (line 92).

**(b) Today's assertions** (`startPrivateWithPayload...` lines 234-252) confirm only
call-count on the no-op stub:

```java
verify(subscriberService, times(1)).upsertFromTelegramUpdate(
        eq(projectId), eq(TELEGRAM_BOT_ID), eq(100L), eq("private"),
        eq(100L), anyString(), anyString(), anyString(), anyString());
```

After Epic 05 swaps `NoOpSubscriberService` for `SubscriberServiceImpl`, the spy still
works (real method runs; spy records calls). Add side-effect assertions:

```java
Subscriber persisted = subscriberRepository.findByProjectIdAndChatId(projectId, 100L).orElseThrow();
assertThat(persisted.getStatus()).isEqualTo(SubscriberStatus.ACTIVE);
assertThat(persisted.getFirstName()).isEqualTo("Test");
```

**(c) Reusable fixtures** (lines 86-108, 611-658):
- `cleanAndSeed`: wipes events/rawUpdates/bots/projects; saves `Project` (OWNER_ID,
  "Test Project", UTC) and a CONNECTED `Bot` with `telegramBotId=1234567890L`. Mirror
  verbatim for `SubscriberServiceImplIT`.
- `privateStartPayload(chatId, fromId, text)` builds a private-chat Document including
  `from{id, is_bot:false, first_name:"Test", last_name:"User", username:"testuser",
  language_code:"en"}` (line 632-639) — perfect for `upsert` assertions.
- `seedRawUpdate(status, payload, updateId)` returns a saved `RawUpdate` (line 611-619).

For `SubscriberServiceImplIT`: extend `AbstractIntegrationTest`, autowire `ProcessTelegramUpdateJob`
+ new `SubscriberRepository`, copy the seed helpers, call `job.handle(raw.getId())`
directly. No new test framework needed.

### 16.13 shadcn-vue scaffolding for Epic 05

**Currently scaffolded** (`frontend/components/ui/`): only `dialog/` (per §10).

**components.json** (verified): `style: "new-york"`, `tailwind.baseColor: "zinc"`,
`cssVariables: true`, `aliases.ui: "@/components/ui"`, `iconLibrary: "lucide"`.

**Commands to run during Wave 1** (idempotent — re-run safe; will prompt to overwrite):

```bash
cd frontend
pnpm dlx shadcn-vue@latest add table     # Subscriber list, segment results
pnpm dlx shadcn-vue@latest add select    # Status filter, tag filter, custom-field type picker
pnpm dlx shadcn-vue@latest add badge     # Tags, status pills
pnpm dlx shadcn-vue@latest add input     # Search, custom-field value editors
pnpm dlx shadcn-vue@latest add sonner    # Toasts (replaces Toast in newer shadcn-vue)
pnpm dlx shadcn-vue@latest add combobox  # Tag picker on subscriber detail
pnpm dlx shadcn-vue@latest add tabs      # Subscriber detail: Profile / Tags / Custom fields / History
pnpm dlx shadcn-vue@latest add card      # Detail panels
pnpm dlx shadcn-vue@latest add sheet     # Filter drawer on subscriber list
pnpm dlx shadcn-vue@latest add tooltip   # Truncated tag/field hints
pnpm dlx shadcn-vue@latest add checkbox  # Bulk select on table
pnpm dlx shadcn-vue@latest add button    # If not already present transitively
```

Files land in `frontend/components/ui/<component>/Index.vue` + `<Component>.vue` siblings
(per `components.json` `aliases.ui`). `lucide-vue-next` provides icons (already present).
After scaffolding, commit the generated files — they are project-owned source per
shadcn-vue convention.

---

**Verification summary**: every question has a concrete file:line answer. No questions
require user input. The single tech-spec surprise: `$in` inside `partialFilter` IS already
proven in this codebase (`RawUpdate.java:56`) — AC17 `export_in_flight` enforcement can
use a DB-level partial-unique index, no Redis lock required.

