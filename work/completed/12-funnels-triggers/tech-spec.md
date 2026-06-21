---
created: 2026-06-07
status: approved
branch: dev
size: L
---

# Tech Spec: 12-funnels-triggers — Additional triggers + internal event bus (Phase 3)

## Solution

Phase 3 extends the funnel engine from a single `on_start` trigger to **six** entry paths
(`on_start`, `keyword`, `tag_added`, `custom_field_set`, plus the shared **`event`** namespace fed
by both an external `POST /api/integrations/v1/events` call and a new in-funnel `EMIT_EVENT` step),
and lays the **first rails of the public API** (per-project, hash-only API key + a key-authenticated
filter chain).

The de-risking spine (from user-spec): every trigger stays **subscriber-scoped** — one firing creates
**one** `FunnelExecution` for **one** subscriber, exactly like `on_start` today. "One event → N
funnels" (fan-out) is achieved by **N separate funnels** sharing the same trigger value, which only
requires **relaxing the partial-unique index** (uniqueness kept for `on_start` only) — **not** a
`List<Trigger>` per funnel (multi-entry is Epic 8). The existing execution-creation path
(`insertExecution` + the partial-unique re-enter guard) is reused verbatim.

The work reuses four established patterns instead of new infrastructure: the chatId-keyed `fire(...)`
gets a **sibling subscriber-keyed fan-out entrypoint**; tag/field triggers hook the **sole writers**
(`addTag` / `recordCustomFieldsSet`) after their idempotency guards; the index relax ships as an
**idempotent `ApplicationRunner` migration** (the `FunnelStepIdBackfill` pattern, because
`auto-index-creation=true` creates but never drops indexes); the `/events` rate-limit and the new
auto-enroll backstop reuse the **Redis fail-open INCR/EXPIRE counter** idiom. Cascade/runaway loops
are bounded by **three independent backstops**: a per-subscriber auto-enroll rate-limit (Redis,
fail-open), a Redis-independent enroll-chain **depth cap** carried on each execution, and a
Redis-independent **per-dispatch fan-out ceiling**. Every new trigger path is **error-isolated** —
a dispatch fault never escalates to a 5xx or a failed webhook job.

## Architecture

### What we're building/modifying

**Funnel domain (`com.botfunnel.funnel`)**
- **`Funnel`** — add `keywords: List<String>` (lowercase, contains-match list; used only by the
  `keyword` trigger). Relax the partial-unique trigger index so uniqueness applies to `on_start`
  only.
- **`FunnelStep`** — add `eventName: String` (for `EMIT_EVENT`); extend `copyOf` so the snapshot
  keeps it.
- **`StepType`** — add `EMIT_EVENT`.
- **`FunnelExecution`** — add `enrollDepth: int` (default 0) — the Redis-independent depth backstop.
- **`FunnelEventService`** (NEW) — the internal dispatcher (deliberately named distinct from the
  `events` audit collection and `subscriber_events`). Owns the **subscriber-keyed fan-out** firing
  for `keyword` / `tag_added` / `custom_field_set` / `event`, plus all three loop backstops. Wraps the
  existing `insertExecution` per matched funnel. Error-isolated (`try/catch(Throwable)`).
- **`FunnelTriggerService` / `FunnelTriggerServiceImpl`** — keep the chatId-keyed `on_start`
  `fire(...)` unchanged; the new subscriber-keyed fan-out logic lives in `FunnelEventService` and
  reuses `insertExecution` / `cancelExistingForPair`.
- **`FunnelRepository`** — add list queries: keyword funnels (`findByProjectIdAndTriggerTypeAndStatus`,
  contains-match in code) and a LIST variant of the trigger lookup (drop the single-result
  `.orElse(null)` for `event`/`tag_added`/`custom_field_set`).
- **`StepExecutor`** — new `EMIT_EVENT` case: synchronously dispatch the event for the current
  subscriber (passing `enrollDepth+1` explicitly), return `CONTINUE`.
- **`FunnelService`** — validate/normalize the new trigger types + `keywords` + the `EMIT_EVENT`
  step's `eventName`.
- **Trigger-index reconciliation `ApplicationRunner`** (NEW) — idempotent startup migration: drop the
  old `projectId_triggerType_triggerValue_unique_active` index so auto-index-creation recreates the
  `on_start`-only-unique shape.

**Webhook (`com.botfunnel.webhook`)**
- **`ProcessTelegramUpdateJob`** — in the plain-text branch, after subscriber upsert: if the
  subscriber has an in-flight `waiting_for_reply` execution → skip keyword (precedence); else
  dispatch keyword matching via `FunnelEventService`. The keyword dispatch is wrapped so any fault
  is swallowed (WARN) and the worker still returns 200 (error-isolation — Decision 12).

**Subscriber (`com.botfunnel.subscriber`)**
- **`SubscriberServiceImpl`** — fire `tag_added` inside `addTag` and `custom_field_set` inside
  `recordCustomFieldsSet`, **after** the idempotency guard and the `writeSubscriberEvent` call (no-op
  writes do not fire). The caller-supplied origin depth (Decision 6) is threaded as an explicit
  parameter on these writers.

**Public API (`com.botfunnel.api` — greenfield)**
- **`ApiKey`** domain + `api_keys` collection (hash-only, one primary key per project) + repository.
- **`ApiKeyService`** — generate (≥256-bit `SecureRandom` → plaintext shown once) + store
  `Sha256Hex.hex(plaintext)` + lookup-by-hash. Explicitly NOT hooked into `ProjectService.create`.
- **API-key `SecurityFilterChain`** (`@Order(1)`, `securityMatcher("/api/integrations/**")`,
  STATELESS, CSRF off) + `OncePerRequestFilter` that resolves the project from the presented key
  (uniform 401 on any key problem). The existing session chain becomes `@Order(2)`.
- **`EventsController`** — `POST /api/integrations/v1/events` (key-authed): DTO validation, global
  fixed rate-limit, resolve subscriber, dispatch via `FunnelEventService`, response codes
  202/400/404/401/429, error-isolated (engine fault → 202, never 5xx).
- **`ApiKeyController`** — session-authed `/api/v1/projects/{projectId}/api-key` generate/regenerate
  (on-demand only — no auto-gen on project create, no backfill).

**Frontend (`frontend/`)**
- **`FunnelTriggerSettings.vue`** — trigger-**type** selector + per-type value editor (keyword list /
  tag `SearchableSelect` / field `SearchableSelect` / event-name slug input / on_start payload).
- **`FunnelStepForm.vue`** — `EMIT_EVENT` in `STEP_TYPES` + event-name field block.
- **Project settings** — API-key card (Generate → show plaintext once; Regenerate; masked `prefix•••`).
- **`types/funnel.ts`** + i18n locale files.

### How it works

**Trigger-type model.** `triggerType` carries five values: `on_start`, `keyword`, `tag_added`,
`custom_field_set`, `event`. The UI label "api-event" maps to `triggerType=event` — external
API events and in-funnel `EMIT_EVENT` share **one** `event` namespace keyed by `event_name`.
`triggerValue` is reused as the match key for `tag_added` (tag slug), `custom_field_set` (field key)
and `event` (event_name). `keyword` does **not** use `triggerValue` — it scans the funnel's
`keywords` list (contains-match, case-insensitive, any-of-many).

**Fan-out via index relax.** The partial-unique index changes its partial filter from
`{status:'active'}` to `{status:'active', triggerType:'on_start'}`. Two active `event`/`keyword`/
`tag`/`field` funnels with the same trigger value now coexist; two active `on_start` funnels with the
same payload still collide. Because `auto-index-creation=true` only creates indexes, a startup runner
drops the old index first; auto-index then recreates the new shape. Idempotent via `listIndexes()`.

**Subscriber-keyed dispatch.** `FunnelEventService.dispatchForSubscriber(projectId, subscriberId,
triggerType, matchKey, originDepth)` resolves the subscriber via the service boundary, queries the
LIST of active matching funnels, and loops `insertExecution` per funnel (fan-out, capped — see
backstops). Each created execution gets `enrollDepth = originDepth` (roots) or
`parent.enrollDepth + 1` (auto children). The whole method is `try/catch(Throwable)` — a fault is
logged (greppable WARN) and swallowed.

**Keyword (webhook).** Plain-text branch resolves the subscriber; if an in-flight
`waiting_for_reply` execution exists → keyword is suppressed (menu precedence); else `FunnelEventService`
scans active keyword funnels, contains-matches the text against each `keywords` list, starts every
match (fan-out). Commands (`/start`, `/stop`) keep their own branch — keyword never touches them.

**Tag / field.** Inside `addTag` / `recordCustomFieldsSet`, after the idempotency guard + audit
write, fire `tag_added`(slug) / `custom_field_set`(key) for that subscriber. Origin depth is passed
explicitly by the caller (0 for manual UI / API; `currentExecution.enrollDepth` when the write came
from a funnel `ADD_TAG`/`SET_CUSTOM_FIELD` step — see Decision 6).

**External event.** `POST /api/integrations/v1/events {event_name, telegram_user_id, subscriber_id?}`.
The key filter validates (SHA-256 lookup) and pins the project; the DTO is validated (event_name slug
`^[A-Za-z0-9_-]{1,64}$`, identifiers well-formed → 400 on malformed). Subscriber resolves by
`subscriber_id` when supplied (`(projectId, subscriber_id)`, takes precedence), else by
`(projectId, telegram_user_id)`; if neither identifier is present → 400. Dispatch fans out to active
`event`=`event_name` funnels for that subscriber (depth 0 — external root). Responses: 202 success /
202 no-op (subscriber exists, no listener) / 404 unknown subscriber / 400 malformed body / uniform
401 any key problem / 429 rate-limit. The endpoint never auto-creates a subscriber and never returns
5xx — engine faults are swallowed → 202 (Decision 12).

**EMIT_EVENT step.** `StepExecutor` synchronously calls `FunnelEventService` for the current
subscriber with `enrollDepth = currentExecution.enrollDepth + 1`, then returns `CONTINUE` (parent
funnel proceeds). Same `event` namespace as the API event.

**Loop backstops (three, independent).**
1. *Volume* — per-subscriber auto-enroll rate-limit: ≤ 20 auto-starts/min (Redis fail-open, key
   `bf:rate:auto-enroll:{subscriberId}`). Only **auto** enrolls (depth > 0) count; human/external
   roots do not. Over limit → drop + greppable WARN.
2. *Depth* — `enrollDepth` on the execution. Root (human/external) = 0; each auto child = parent + 1.
   Over a hard cap of **10** → drop + WARN, **even when Redis is down** (where the fail-open
   rate-limit would otherwise let it through).
3. *Fan-out width* — a Redis-independent per-dispatch ceiling (`app.funnel.max-fanout-per-event`,
   default 50): a single event/word/tag/field firing inserts at most N executions; excess is dropped
   + WARN. Bounds the public `/events` ingress even with Redis down (closes the fail-open hole at the
   API layer, where the depth cap bounds chain depth but not request width).
4. The partial-unique `(funnelId, subscriberId)` re-enter guard still catches a direct duplicate into
   the same funnel.

### Shared resources

| Resource | Owner (creates) | Consumers | Instance count |
|----------|----------------|-----------|----------------|
| `FunnelEventService` (internal dispatcher) | Spring (`@Service`) | `ProcessTelegramUpdateJob` (keyword), `SubscriberServiceImpl` (tag/field), `StepExecutor` (EMIT_EVENT), `EventsController` (api-event) | 1 (singleton) |
| `StringRedisTemplate` (existing) | Spring Data Redis | auto-enroll rate-limit (`bf:rate:auto-enroll:{subscriberId}`), `/events` global rate-limit (`bf:rate:events`) | 1 (singleton) |
| `Sha256Hex` (existing util) | `common.crypto` | `ApiKeyService` (store/verify key hash) | static |
| Mongo `funnels` partial-unique index (shape changed) | trigger-index reconciliation runner | trigger conflict guard, fan-out | 1 |

## Decisions

### Decision 1: Subscriber-scoped fan-out via index relax, not `List<Trigger>`
**Decision:** Keep one execution per subscriber per firing; achieve "one event → N funnels" by N
funnels sharing a trigger value, enabled by relaxing the partial-unique index to `on_start`-only.
**Rationale:** Removes the riskiest schema change; reuses `insertExecution` unchanged.
**Alternatives considered:** `List<Trigger>` / multi-entry per funnel — rejected, deferred to Epic 8.
**Serves:** US "Ключове архітектурне рішення (де-ризик)", AC `fan-out`, AC `on_start лишається 1:1`.

### Decision 2: New subscriber-keyed fan-out entrypoint, separate from chatId `fire`
**Decision:** Add `FunnelEventService.dispatchForSubscriber(...)` returning a list of matched funnels;
keep `fire(projectId, chatId, on_start, payload)` (single-result, chatId-keyed) untouched.
**Rationale:** New triggers start from a subscriberId, not a chatId, and must fan out; overloading
`fire` would break its single-result contract.
**Alternatives considered:** Overload `fire` with a list return — rejected (regresses on_start path).
**Serves:** US scenarios 2–5, "Технічні рішення" bullet 2.

### Decision 3: Keyword stored as a list field, contains-match in code
**Decision:** Add `Funnel.keywords: List<String>` (lowercase-normalized on save); match by
case-insensitive contains, any-of-many, via a code-side scan of active keyword funnels.
**Rationale:** The exact-match `triggerValue` index cannot express "contains, multiple keywords".
**Alternatives considered:** Cram keywords into `triggerValue` — rejected (exact-match only); regex
keywords — rejected, deferred (user-spec out-of-scope).
**Serves:** US scenario 2, AC `keyword`.

### Decision 4: Shared `event` namespace for API-event and EMIT_EVENT
**Decision:** Both external `POST /events` and the `EMIT_EVENT` step dispatch `triggerType=event`
keyed by `event_name`. UI label "api-event" = `triggerType=event`.
**Rationale:** The author's job-to-be-done ("broadcast emits event → funnel responds") is identical
whether the emitter is external or an in-funnel step; one namespace keeps lookup uniform.
**Alternatives considered:** Separate `api_event` and `emit_event` trigger types — rejected (doubles
the lookup surface and breaks the user-spec's "один namespace" requirement); reuse `EventService`
audit collection as a bus — rejected (it's an append-only audit log, nothing consumes it).
**Serves:** US scenarios 4–5, AC `api-event`, AC `EMIT_EVENT`.

### Decision 5: Hook tag/field triggers in the sole writers, after idempotency + audit
**Decision:** Fire `tag_added` in `addTag` and `custom_field_set` in `recordCustomFieldsSet`, after
the existing idempotency guard and `writeSubscriberEvent`.
**Rationale:** These are the verified single choke points (2 call sites each: manual controller +
StepExecutor); a no-op write must not fire.
**Alternatives considered:** Hook in `SubscriberCustomFieldsService.applyAll` / the controllers —
rejected (would miss the funnel-step path or double-fire); read `subscriber_events` as a bus —
rejected (audit feed, no consumer).
**Serves:** US scenario 3, AC `tag_added` (idempotent no-op no-fire), AC `custom_field_set`.

### Decision 6: Three independent loop backstops; "auto-enroll" defined by depth; explicit depth propagation
**Decision:** (a) Per-subscriber auto-enroll rate-limit 20/min (Redis fail-open, counts depth>0
enrolls only). (b) Redis-independent `enrollDepth` cap of 10 carried on the execution. (c)
Redis-independent per-dispatch fan-out ceiling (`max-fanout-per-event`, default 50). An enroll is
**auto** iff it originated inside a running execution (`depth > 0`); manual UI tag-add, `/start`,
keyword and external api-event are human/external roots (`depth = 0`). Origin depth is propagated as
an **explicit method parameter** through `FunnelEventService.dispatchForSubscriber` and the
`addTag`/`recordCustomFieldsSet` writers — **not** a ThreadLocal.
**Rationale:** The user-spec's rate-limit text ("counts кроку/тегу/поля/події") and depth text
("api-event is depth 0") are reconciled by defining *auto = funnel-originated child (depth>0)* —
consistent across both backstops. Fail-open rate-limit alone leaves a hole when Redis is down; the
depth cap and the fan-out ceiling close it (depth bounds chain length, ceiling bounds request width).
Explicit-param propagation is chosen over ThreadLocal because the funnel sweep runs on arbitrary
JobRunr pool / virtual threads — a ThreadLocal set in one tick would silently leak or be absent across
the pool boundary.
**Alternatives considered:** Single Redis rate-limit only — rejected (Redis-outage hole + no width
bound); per-tick step budget only — rejected (bounds one execution, not cross-execution cascades);
ThreadLocal depth context — rejected (JobRunr thread-pool boundary unsafe).
**Serves:** US scenario 7, Risks "Каскади/нескінченні петлі", AC `анти-цикл (обсяг)`, AC `анти-цикл (глибина)`.

### Decision 7: Dedicated key-authenticated chain on `/api/integrations/**`, `@Order(1)`
**Decision:** Add a higher-priority STATELESS `SecurityFilterChain` with
`securityMatcher("/api/integrations/**")` (CSRF off, custom api-key filter) before the session chain
(`@Order(2)`). Project resolves **from the key** (no `{projectId}` in path); subscriber resolution is
always pinned to that project (anti-IDOR). Any key problem → uniform 401 (anti-enumeration).
**Rationale:** The session chain's `/api/**` matcher would otherwise shadow the public endpoint;
namespace `integrations` (vs `public`/`external`) is precise and doesn't imply "no auth"; STATELESS +
header key needs no CSRF (cookieless).
**Alternatives considered:** Reuse session chain with a path carve-out — rejected (fragile ordering);
`/api/v1/events` — rejected (collides with the session API, per code-research §5).
**Serves:** US scenario 4 + 6, "Ограничения" (public API minimum), Risks "Колізія namespace",
AC `API-ключ` mutual isolation.

### Decision 8: API key hash-only, ≥256-bit, generated on demand — no auto-gen, no backfill
**Decision:** Generate a ≥256-bit (32-byte) `SecureRandom` plaintext, store `Sha256Hex.hex(plaintext)`
+ a short `keyPrefix` for the UI mask; one primary key per project. Generate only via the settings
endpoint; plaintext shown once at (re)generation. No key on project create, no backfill. Lookup is by
hash (effectively constant-time w.r.t. the secret — no plaintext compare). No expiry/rotation
(accepted limitation; deferred to Epic 08).
**Rationale:** Matches user-spec scenario 6 exactly; minimizes blast radius and schema reach. A
256-bit random key makes unsalted SHA-256 safe (no brute-forceable low-entropy preimage).
**Alternatives considered:** Generate-on-create + backfill runner (code-research §5/§7 suggestion) —
rejected, contradicts user-spec ("ключ — на вимогу"); therefore **only one** startup migration runner
ships (index reconciliation), not two. AES-decrypt-display via `TokenEncryptor` — rejected
(hash-only is safer; plaintext shown once is sufficient).
**Serves:** US scenario 6, "Ограничения", Risks "Втрата plaintext-ключа", AC `API-ключ`.

### Decision 9: Index relax via idempotent startup `ApplicationRunner` (FunnelStepIdBackfill pattern)
**Decision:** A log-but-never-throw `ApplicationRunner` drops the old trigger index (guarded by
`listIndexes()`); no Mongock now.
**Rationale:** `auto-index-creation=true` creates but never drops/alters; the annotation change alone
is insufficient. Mongock is a separate next task (`workflow/improvements.md`).
**Alternatives considered:** Manual ops drop — rejected (error-prone, user-spec forbids); Mongock now
— rejected (out of scope).
**Serves:** US scenario 8, "Ограничения" (migrations), Risks "Витік index-міграції", AC `міграція`.

### Decision 10: Reuse the Redis fail-open counter for both rate-limits; named keys
**Decision:** Reuse the `SubscriberServiceImpl.startRateLimitExceeded` boolean fail-open INCR/EXPIRE
idiom for the per-subscriber auto-enroll limit (20/min, key `bf:rate:auto-enroll:{subscriberId}`) and
a fixed-global `/events` limit (~300/min, key `bf:rate:events`). Distinct key prefixes prevent
collision. Fail-open is paired with the Redis-independent fan-out ceiling (Decision 6c) so a Redis
outage cannot leave the public ingress unbounded.
**Rationale:** Established, fail-open (availability over hard limit during Redis outage), greppable WARN.
**Alternatives considered:** A blocking (fail-closed) limiter — rejected (a Redis blip would 503 the
public API; fail-open + fan-out ceiling preserves availability while still bounding width); a new
bucket library — rejected (the existing idiom suffices).
**Serves:** US "Технічні рішення", Risks "Перевантаження /events" (fail-open 429).

### Decision 11: Trigger form built as a portable component
**Decision:** Build the trigger-type selector + per-type value editor as a self-contained form
component; only the list wrapper is throwaway when a future canvas editor arrives.
**Rationale:** Minimizes future rework — the form migrates as a node form.
**Alternatives considered:** Inline the selector into the editor page — rejected (not reusable as a
canvas node later); defer all trigger UI to the canvas epic — rejected (authors need the triggers now
in the existing vertical-list editor).
**Serves:** US "Технічні рішення" (portable trigger form), `docs/roadmap/funnels.md` Phase 8 alignment.

### Decision 12: Error-isolation on every new trigger path
**Decision:** Every new dispatch path is `try/catch(Throwable)` and degrades gracefully: a keyword
dispatch fault inside `ProcessTelegramUpdateJob` is swallowed (greppable WARN) so the worker returns
200 and the JobRunr job does not fail; an engine fault inside `POST /events` is swallowed so the
endpoint still returns 202 (event accepted). Mirrors the existing `FunnelTriggerServiceImpl`
convention.
**Rationale:** A funnel/trigger fault must never poison the webhook ingestion pipeline or surface a
5xx to an external caller.
**Alternatives considered:** Let faults propagate to JobRunr retry / HTTP 500 — rejected (a poisoned
update would retry-loop; a 5xx leaks internal state and breaks the "event accepted" contract).
**Serves:** US AC `error-isolation (вебхук)`, US AC `error-isolation (API)`, Risks (cascade safety).

## Data Models

**`api_keys` (new collection)**
```
{
  _id, projectId,            // index: unique (projectId) — one primary key per project
  keyHash,                   // index: unique — SHA-256 hex of ≥256-bit plaintext (Sha256Hex.hex)
  keyPrefix,                 // short plaintext head for UI mask "prefix•••" (never the full key)
  createdAt, lastUsedAt      // lastUsedAt stamped by the key filter on each authenticated call
}
```

**`Funnel` (modified)** — add `keywords: List<String>` (lowercase; only for `triggerType=keyword`).
Partial-unique index partial filter `{status:'active'}` → `{status:'active', triggerType:'on_start'}`.

**`FunnelStep` (modified)** — add `eventName: String` (only for `EMIT_EVENT`; added to `copyOf`).

**`StepType` (modified)** — add `EMIT_EVENT`.

**`FunnelExecution` (modified)** — add `enrollDepth: int` (default 0; depth backstop, on the snapshot).

**`triggerType` values** — `on_start` | `keyword` | `tag_added` | `custom_field_set` | `event`.

**Request DTO** — `POST /api/integrations/v1/events`:
`{ event_name: string (^[A-Za-z0-9_-]{1,64}$, @NotBlank), telegram_user_id?: long, subscriber_id?: string }`.
Validation: `event_name` required + slug-shaped; at least one of `telegram_user_id` / `subscriber_id`
required (else 400); when both present, `subscriber_id` wins. Resolution stays project-scoped from the
key. Requires a `SubscriberService` lookup-by-id at the boundary (add if absent).

## Dependencies

### New packages
- None. All needs are met by existing deps (Spring Security, Spring Data Mongo/Redis, `common.crypto`).

### Using existing (from project)
- `common.crypto.Sha256Hex` — store/verify API-key hash.
- `java.security.SecureRandom` — generate the ≥256-bit plaintext API key (pattern from `TokenEncryptor`).
- `StringRedisTemplate` — both rate-limit counters (fail-open INCR/EXPIRE idiom).
- `FunnelTriggerServiceImpl.insertExecution` / `cancelExistingForPair` — execution creation, reused.
- `FunnelStepIdBackfill` / `SuperAdminSeeder` `ApplicationRunner` pattern — the index migration.
- `SearchableSelect.vue` — tag/field trigger pickers (already used for step `tagSlug`/`customFieldKey`).
- `ProjectService.requireOwned` — ownership guard for the session-authed API-key endpoint.

## Testing Strategy

**Feature size:** L

### Unit tests
- Keyword matching: contains / case-insensitive / any-of-many / no-match.
- Keyword normalization: `"Bonus"`, `"SALE"` → `"bonus"`, `"sale"` on save.
- `EMIT_EVENT` step: returns `CONTINUE`, dispatches with the right `event_name` + child depth =
  parent+1; `copyOf` preserves `eventName`.
- API-key generation: ≥256-bit length; plaintext ≠ stored hash; `Sha256Hex` verify round-trip; prefix
  mask shape.
- Index-reconciliation runner: idempotent (no-op when already new shape), log-but-never-throw.
- Backstops (unit, isolated): rate-limit boolean fail-open; depth-cap drop at >10; fan-out ceiling
  drop at >N; volume limit exempts depth-0 (human/external) enrolls.
- Trigger-type / `keywords` / `eventName` validation in `FunnelService`; `/events` DTO validation
  (malformed `event_name`, missing identifiers).

### Integration tests
- Each trigger type starts the correct funnel for the correct subscriber (`FunnelTriggerServiceIT` sibling).
- Keyword via webhook + precedence vs `waiting_for_reply` (assert: no execution created AND the
  waiting execution is untouched) (`ProcessTelegramUpdateJobTest`).
- Fan-out: one event/word/tag/field → N executions.
- Anti-cycle, as **three separate** ITs: volume (A→B→A under 20/min, fail-open path), depth (chain
  >10 truncated with Redis unavailable — depth-cap is Redis-independent), and fan-out width (one
  firing with >N listener funnels inserts at most N executions with Redis unavailable — ceiling is
  Redis-independent).
- `POST /api/integrations/v1/events`: 202 / 202-no-op / 404 / 400 / 401 / 429 (each distinct);
  never auto-creates a subscriber; 404-vs-202-no-op distinction asserted; the uniform 401 is asserted
  identical across all three key causes (missing / malformed / unknown); the two 400 sub-paths
  (malformed `event_name` slug vs missing-both-identifiers) and the `subscriber_id`-wins precedence
  each have a distinct assertion; mutual key↔session isolation in **both** directions
  (key ✗ `/api/v1/projects/**`; session ✗ `/api/integrations/**`).
- **Error-isolation:** keyword dispatch throws → webhook worker returns 200, JobRunr job not failed,
  WARN logged (AC `error-isolation (вебхук)`); engine throws inside `/events` → 202, no 5xx
  (AC `error-isolation (API)`).
- Index-relax migration (`FunnelIndexesIT` sibling): old index gone; two active `event` funnels coexist;
  two `on_start` with same payload still collide.
- API-key generation in settings; project without a key is rejected on `/integrations`; `eventName`
  preserved across the execution snapshot boundary.
- tag_added / custom_field_set fire an execution after `addTag` / `recordCustomFieldsSet`; idempotent
  no-op does not fire.

### E2E tests
- None. No new critical user-facing flow beyond trigger config in the existing editor. Covered by
  Vitest: (1) trigger-type selector renders the correct value editor per selected type; (2) keyword
  multi-word list add/remove; (3) `EMIT_EVENT` appears in the step picker and shows the event-name
  field; (4) API-key card states (generate → plaintext-once modal → masked + regenerate).

## Agent Verification Plan

**Source:** user-spec "Как проверить" section.

### Verification approach
Beyond automated tests, an agent exercises the live behaviors that are hard to unit-test: keyword
firing + menu precedence over real Telegram, tag/field-triggered funnels, the external event endpoint
(202/400/401/404/429), `EMIT_EVENT` fan-out, the startup index migration (log + `listIndexes`), and
API-key generation (plaintext once, hash-only at rest, chain isolation). Per-task smoke checks are in
each task's Verify-smoke / Verify-user fields. The live checks run against the author's locally-run app
(no CI deploy yet) and are described in the Post-deploy verification task.

### Tools required
Telegram MCP (keyword / EMIT_EVENT / tag flows), curl (`/events` codes, chain isolation, `listIndexes`
via app log), bash (`./gradlew test`, `pnpm test`, startup-log inspection), browser/localhost (API-key
settings card).

## Risks

| Risk | Mitigation |
|------|-----------|
| Cascades / infinite loops (tag→funnel→tag, EMIT_EVENT fan-out, A→B→A) | Three independent backstops: per-subscriber 20/min auto-enroll rate-limit (Redis fail-open) + Redis-independent `enrollDepth` cap of 10 + Redis-independent per-dispatch fan-out ceiling; re-enter guard catches direct same-funnel dupes (Decision 6). |
| `/api/integrations/**` namespace collision with session `/api/**` | Dedicated `@Order(1)` STATELESS chain with `securityMatcher("/api/integrations/**")` before the session chain; IT proves mutual isolation in both directions (Decision 7). |
| Index migration forgotten → fan-out silently broken | Idempotent startup runner + `FunnelIndexesIT` proving old index gone, two `event` funnels coexist, two `on_start` collide (Decision 9). |
| Lost plaintext API key (hash-only, unrecoverable) | Show-once modal + "Regenerate"; document in UI (Decision 8). |
| Leaked API key valid indefinitely (no rotation/expiry) | ≥256-bit entropy + hash-only at rest; rotation/expiry an accepted limitation deferred to Epic 08; Regenerate invalidates the old key (Decision 8). |
| Redis outage removes `/events` volume throttle (fail-open) | Redis-independent per-dispatch fan-out ceiling bounds executions-per-request even with Redis down (Decision 6c, 10). |
| Keyword noise / false positives (too-generic word) | Contains-match over an author-curated list + normalization; precedence never pulls a subscriber out of a menu; author controls the words. |
| Trigger/engine fault poisons webhook or leaks 5xx | Error-isolation on every new path: keyword fault → 200, engine fault → 202 (Decision 12). |
| `events` naming overload (3 distinct concepts) | Internal dispatcher named `FunnelEventService`; `EventService`/`subscriber_events` NOT reused (Decision 4). |

## User-Spec Deviations

- **AC `api-event` endpoint path:** the roadmap (`docs/roadmap/funnels.md`) names `POST /api/v1/events`,
  but the **approved user-spec** specifies `POST /api/integrations/v1/events` (separate key-authed
  namespace). Tech-spec follows the user-spec. Not a deviation from the approved spec — noted only
  because the roadmap line is stale. → No approval needed (already approved in user-spec).
- **"Auto-enroll" definition (Decision 6):** user-spec describes the volume rate-limit as counting
  "кроку/тегу/поля/події" while the depth-cap treats external `api-event` as depth 0. Tech-spec
  reconciles both by defining *auto-enroll = enroll originating inside a running execution (depth>0)*.
  **Concrete behavior:** manual-UI tag-add, `/start`, keyword and external api-event are depth=0 roots
  and are therefore **NOT** counted toward the 20/min volume limit (only funnel-step-originated
  enrolls are); they remain bounded by the depth cap and fan-out ceiling. → APPROVED (2026-06-07).
- **Added: per-dispatch fan-out ceiling (Decision 6c)** — not explicitly in user-spec. Reason: the
  user-spec's fail-open `/events` rate-limit leaves the public ingress unbounded during a Redis
  outage (security review A04); a Redis-independent width cap closes it without changing intended
  behavior under normal load (default 50 ≫ realistic listener count). → APPROVED (2026-06-07).
- **No API-key backfill / no generate-on-create:** code-research §5/§7 suggested both; user-spec
  explicitly forbids them ("ключ — на вимогу"). Tech-spec follows user-spec → only one startup runner
  (index reconciliation). → No approval needed (matches user-spec).

## Acceptance Criteria

Technical criteria (complement the user-facing criteria in user-spec):

- [ ] `POST /api/integrations/v1/events` returns the exact code matrix: 202 (start), 202 (no-op,
      subscriber exists no listener), 404 (unknown subscriber), 400 (malformed body / missing
      identifier), 401 (any key problem, uniform), 429 (rate-limit); never 5xx; never auto-creates a
      subscriber.
- [ ] Error-isolation holds: a keyword dispatch fault returns webhook 200 (job not failed, WARN
      logged); an engine fault inside `/events` returns 202 (WARN logged).
- [ ] All three loop backstops are independently exercised, including the depth cap and fan-out
      ceiling with Redis unavailable.
- [ ] Startup index-reconciliation runner is idempotent (second boot = no-op) and never throws.
- [ ] New `Funnel.keywords`, `FunnelStep.eventName`, `FunnelExecution.enrollDepth` round-trip through
      Mongo; `eventName` survives the execution snapshot (`copyOf`).
- [ ] `api_keys` stores a ≥256-bit-derived hash + prefix only; full plaintext never persisted;
      `keyHash` unique index enforced.
- [ ] Key chain and session chain are mutually exclusive (key ✗ cabinet, session ✗ `/integrations`).
- [ ] All existing funnel/webhook/subscriber/security tests stay green (no regressions).
- [ ] New unit + integration tests pass, including the `@Tag("slow")` index-migration IT.

## Implementation Tasks

### Wave 1 (foundation — independent)

#### Task 1: Funnel domain — new trigger types, keywords, EMIT_EVENT, depth field
- **Description:** Extend the funnel domain for Phase 3: add `Funnel.keywords` (lowercase list),
  `FunnelStep.eventName` (+ `copyOf`), `StepType.EMIT_EVENT`, `FunnelExecution.enrollDepth` (default
  0), and relax the partial-unique trigger index annotation to `on_start`-only. Extend
  `FunnelService` to validate/normalize the new trigger types and the `EMIT_EVENT` `eventName` slug.
  WHY: the data layer every other task builds on.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `funnel/Funnel.java`, `funnel/FunnelStep.java`, `funnel/StepType.java`,
  `funnel/FunnelExecution.java`, `funnel/FunnelService.java`, `funnel/FunnelRepository.java`
- **Files to read:** `funnel/FunnelTriggerServiceImpl.java`, `funnel/StepExecutor.java`,
  `funnel/FunnelStatus.java`, `subscriber/SubscriberEvent.java`

#### Task 2: Trigger-index reconciliation startup migration
- **Description:** Add an idempotent `ApplicationRunner` (FunnelStepIdBackfill pattern, log-but-never-
  throw) that drops the old `projectId_triggerType_triggerValue_unique_active` index so auto-index-
  creation recreates the `on_start`-only-unique shape; no-op when already migrated (guard via
  `listIndexes()`). WHY: `auto-index-creation=true` never drops/alters indexes, so the annotation
  change alone would not relax uniqueness.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** boot the app, grep startup log for the migration marker; re-boot → no-op marker.
- **Files to modify:** `funnel/` (new `FunnelTriggerIndexReconciliation.java`)
- **Files to read:** `funnel/FunnelStepIdBackfill.java`, `admin/SuperAdminSeeder.java`,
  `funnel/Funnel.java`, `src/main/resources/application.properties`

#### Task 3: ApiKey domain + generation/hash service
- **Description:** Greenfield `com.botfunnel.api`: `ApiKey` domain (`api_keys`, unique `keyHash`,
  unique `projectId`, `keyPrefix` for the mask, `lastUsedAt`) + repository + `ApiKeyService` (generate
  a ≥256-bit `SecureRandom` plaintext, store `Sha256Hex.hex(plaintext)`, return plaintext once,
  lookup-by-hash, regenerate = overwrite). Hash-only at rest. WHY: the credential the public-API chain
  authenticates against. NOTE: do NOT hook generation into `ProjectService.create` (on-demand only —
  Decision 8); ignore the code-research §5/§7 backfill suggestion.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `api/` (new `ApiKey.java`, `ApiKeyRepository.java`, `ApiKeyService.java`)
- **Files to read:** `common/crypto/Sha256Hex.java`, `common/crypto/TokenEncryptor.java`,
  `bot/BotService.java`, `project/Project.java`

### Wave 2 (dispatcher core — depends on Wave 1)

#### Task 4: FunnelEventService — subscriber-keyed fan-out dispatcher + loop backstops
- **Description:** New `FunnelEventService` (`@Service`) with `dispatchForSubscriber(projectId,
  subscriberId, triggerType, matchKey, originDepth)`: resolve subscriber via the service boundary,
  query the LIST of active matching funnels, loop `insertExecution` per match (fan-out), set
  `enrollDepth`. Enforce all three backstops — per-subscriber auto-enroll rate-limit (Redis fail-open,
  key `bf:rate:auto-enroll:{subscriberId}`, counts depth>0 only), the Redis-independent depth cap, and
  the Redis-independent per-dispatch fan-out ceiling. Error-isolated (`try/catch(Throwable)`, greppable
  WARN). Add the list/keyword repository queries. WHY: the single dispatcher all four trigger sources
  call — must exist before its callers. Decisions 2, 6, 10, 12.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `funnel/` (new `FunnelEventService.java`), `funnel/FunnelTriggerServiceImpl.java`,
  `funnel/FunnelRepository.java`, `src/main/resources/application.properties`
- **Files to read:** `funnel/FunnelTriggerService.java`, `subscriber/SubscriberServiceImpl.java`
  (rate-limit idiom), `bot/BotService.java`, `subscriber/SubscriberService.java`

### Wave 3 (trigger sources — depend on Wave 2; no file overlap between the two tasks)

#### Task 5: Funnel-step trigger integration (tag/field hooks + EMIT_EVENT + depth threading)
- **Description:** Wire funnel-execution side-effects into `FunnelEventService` (single owner of
  `StepExecutor.java`): (1) fire `tag_added` inside `SubscriberServiceImpl.addTag` and
  `custom_field_set` inside `recordCustomFieldsSet`, after the idempotency guard and
  `writeSubscriberEvent`; (2) add the `EMIT_EVENT` case to `StepExecutor` (call the dispatcher for the
  current subscriber, return `CONTINUE`); (3) thread origin depth as an explicit parameter through the
  writers and the step calls — 0 from the manual controllers / API, `execution.enrollDepth(+1)` from
  the funnel-step callers. WHY: makes tags/fields/emit active triggers with correct depth, without
  firing on no-op writes. Decisions 4, 5, 6.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `subscriber/SubscriberServiceImpl.java`, `subscriber/SubscriberService.java`,
  `subscriber/SubscriberController.java`, `subscriber/SubscriberCustomFieldsController.java`,
  `funnel/StepExecutor.java`
- **Files to read:** `funnel/FunnelEventService.java`, `funnel/FunnelExecutionEngine.java`,
  `funnel/FunnelStep.java`, `funnel/StepType.java`, `subscriber/SubscriberCustomFieldsService.java`,
  `subscriber/SubscriberEvent.java`

#### Task 6: Keyword webhook path + waiting_for_reply precedence
- **Description:** In `ProcessTelegramUpdateJob`'s plain-text branch, after subscriber upsert: resolve
  the subscriber, skip keyword if an in-flight `waiting_for_reply` execution exists (menu precedence),
  else dispatch keyword matching via `FunnelEventService` (contains-match, fan-out). Wrap the dispatch
  so any fault is swallowed (WARN) and the worker still returns 200 (error-isolation). Commands keep
  their branch. WHY: makes the bot respond to free-text keywords without hijacking menus. Decisions 3, 12.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** Telegram MCP — text with a trigger word → funnel starts; same text while in a menu
  → menu stays, no keyword funnel.
- **Files to modify:** `webhook/ProcessTelegramUpdateJob.java`
- **Files to read:** `funnel/FunnelEventService.java`, `funnel/FunnelRepository.java`,
  `subscriber/SubscriberService.java`, `funnel/FunnelExecution.java`

### Wave 4 (public API — depends on Wave 1 + Wave 2)

#### Task 7: API-key security chain + EventsController
- **Description:** Add an `@Order(1)` STATELESS `SecurityFilterChain` with
  `securityMatcher("/api/integrations/**")` (CSRF off) + an `OncePerRequestFilter` that resolves the
  project from the presented key (uniform 401 on any key problem; stamps `lastUsedAt`); demote the
  session chain to `@Order(2)`. Add `EventsController` (`POST /api/integrations/v1/events`): validate
  the DTO (event_name slug; require ≥1 identifier else 400; subscriber_id wins when both present),
  global fixed Redis rate-limit (429, fail-open), resolve subscriber project-scoped, dispatch via
  `FunnelEventService` (depth 0), response codes 202/400/404/401/429, never 5xx (engine fault → 202).
  WHY: external systems drive funnels. Decisions 7, 10, 12.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `curl -X POST localhost:8080/api/integrations/v1/events -H 'X-API-Key: <key>'
  -H 'Content-Type: application/json' -d '{"event_name":"e","telegram_user_id":123}'` → 202; no key
  → 401; unknown user → 404; bad body → 400.
- **Files to modify:** `security/SecurityConfig.java`, `api/` (new `EventsController.java`,
  api-key `OncePerRequestFilter`, request DTO), `src/main/resources/application.properties`
- **Files to read:** `webhook/WebhookSecretVerifier.java`, `api/ApiKeyService.java`,
  `funnel/FunnelEventService.java`, `subscriber/SubscriberService.java`, `subscriber/SubscriberServiceImpl.java`

#### Task 8: Project-settings API-key generate/regenerate endpoint
- **Description:** Session-authed `ApiKeyController` under `/api/v1/projects/{projectId}/api-key`:
  GET current mask (`prefix•••` or "none"), POST generate/regenerate (ownership-guarded via
  `requireOwned`) returning the plaintext **once**. Generation is on-demand only — not wired into
  project creation. WHY: lets the author obtain a key on demand. Decision 8.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `curl` POST with session cookie → returns plaintext once; GET → masked; DB stores
  hash only.
- **Files to modify:** `api/` (new `ApiKeyController.java`, response DTO)
- **Files to read:** `api/ApiKeyService.java`, `project/ProjectService.java`,
  `project/ProjectController.java`

### Wave 5 (funnel-editor frontend — depends on backend trigger types)

#### Task 9: Funnel-editor Phase-3 UI (trigger selector + EMIT_EVENT form)
- **Description:** Two coupled funnel-editor changes (kept in one task because they share
  `types/funnel.ts` + the funnel locale keys, so they cannot run in parallel safely): (1) replace the
  static `on_start` badge in `FunnelTriggerSettings.vue` with a trigger-type selector (on_start /
  keyword / tag_added / custom_field_set / api-event) rendering the value editor per type (on_start
  payload + deep-link preview; keyword multi-word list; tag/field `SearchableSelect`; event-name slug
  input), built as a portable form component; (2) add `EMIT_EVENT` to `STEP_TYPES` in
  `FunnelStepForm.vue` with an event-name field block + `buildStep` branch. Update `types/funnel.ts`
  (trigger types + `StepType` union) and the funnel i18n keys. WHY: authors configure the new triggers
  and the emit step. Decisions 4, 11.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-user:** funnel editor → trigger type selectable with the matching value field; add-step
  dialog lists "Emit event" with its event-name field.
- **Files to modify:** `frontend/components/funnels/FunnelTriggerSettings.vue`,
  `frontend/components/funnels/FunnelStepForm.vue`, `frontend/types/funnel.ts`,
  `frontend/i18n/locales/uk.json`, `frontend/i18n/locales/en.json`
- **Files to read:** `frontend/components/funnels/SearchableSelect.vue`,
  `frontend/components/funnels/AddStepDialog.vue`, `frontend/components/funnels/EditStepDialog.vue`

### Wave 6 (settings frontend — depends on Task 8; serialized after Wave 5 to avoid locale-file conflict)

#### Task 10: API-key card in project settings
- **Description:** Add an API-key card to project settings: "Generate" when absent (show plaintext once
  in a "save this key" modal), masked `prefix•••` + "Regenerate" when present. Wire to the Task 8
  endpoints. Sequenced after Task 9 because both append to the i18n locale files (single-owner-per-
  file to avoid a merge collision). WHY: surfaces the on-demand key. Decision 8.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** settings → Generate shows the key once; reload → masked + Regenerate.
- **Files to modify:** `frontend/` (project settings page/component), `frontend/i18n/locales/uk.json`,
  `frontend/i18n/locales/en.json`
- **Files to read:** `frontend/composables/useApi.ts`, `frontend/components/funnels/FunnelTriggerSettings.vue`

### Audit Wave

#### Task 11: Code Audit
- **Description:** Full-feature code quality audit. Read all source files created/modified in this
  feature (from decisions.md + tech-spec "Files to modify"). Review holistically for cross-component
  issues: duplicate dispatcher/rate-limit initialization, shared-resource compliance (single
  `FunnelEventService`), security-chain ordering, explicit-depth threading consistency, architectural
  consistency across funnel/webhook/subscriber/api modules. Write audit report.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 12: Security Audit
- **Description:** Full-feature security audit. Read all source files created/modified in this feature.
  Analyze OWASP Top 10 across components: api-key storage/comparison, 401 uniformity/anti-enumeration,
  chain isolation (key↔session, both directions), rate-limit + fan-out-ceiling bypass, IDOR on
  subscriber resolution (project-scoping), input validation on event_name/telegram_user_id/
  subscriber_id, no PII/secrets in logs. Write audit report.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 13: Test Audit
- **Description:** Full-feature test quality audit. Read all test files created in this feature. Verify
  coverage of each trigger type, fan-out, all three loop backstops (independently, incl. Redis-down
  paths), the response-code matrix, both error-isolation paths, chain isolation, and the index
  migration; check meaningful assertions and test-pyramid balance. Write audit report.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 14: Pre-deploy QA
- **Description:** Acceptance testing: run all tests (`./gradlew test` incl. `@Tag("slow")` index IT,
  `pnpm test`), verify every acceptance criterion from user-spec and tech-spec.
- **Skill:** pre-deploy-qa
- **Reviewers:** none

#### Task 15: Post-deploy verification (live, local run)
- **Description:** Live verification against the author's locally-run app (no CI deploy yet):
  - keyword fires + menu precedence — tool: Telegram MCP
  - tag_added / custom_field_set fire a funnel — tool: Telegram MCP + curl (cabinet)
  - `POST /api/integrations/v1/events` 202 / 400 / 401 / 404 / 429 — tool: curl
  - EMIT_EVENT fan-out (2 listener funnels) — tool: Telegram MCP
  - index migration applied + idempotent re-boot — tool: bash (startup log + `listIndexes`)
  - API-key generated once, hash-only at rest, chain isolation — tool: browser/localhost + curl
  Tools: Telegram MCP, curl, bash.
- **Skill:** post-deploy-qa
- **Reviewers:** none
