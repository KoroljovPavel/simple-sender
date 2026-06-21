# Security Audit — Funnels (Лінійні воронки, Фаза 1)

Task 12 · full-feature OWASP Top 10 audit · read-only (no production code changed).
Methodology: `security-auditor` skill, applied holistically across Tasks 1–10 (+ Task 15 fixes)
along the full data path: owner HTTP request & subscriber Telegram update → engine → Telegram API → logs.

## Result

**No exploitable vulnerabilities found.** All security-relevant Acceptance Criteria and Decisions
(6, 7, 10, 11, 13, 16) are upheld in code. Three INFO-level notes below are defense-in-depth /
documentation observations, not weaknesses — no fix is required for deploy.

Severity legend: critical / high / medium / low / **info** (no action required).

## Scope — files audited

Reconciled against `decisions.md` Task Execution Log (actual files & deviations).

**Backend (`com/botfunnel/funnel/`):** `Funnel`, `FunnelStep`, `FunnelExecution`,
`FunnelRepository`, `FunnelExecutionRepository`, enums (`FunnelStatus`/`ExecutionStatus`/
`StepRunStatus`/`StepType`), `VariableTemplateRenderer`, `FunnelService`, `FunnelController`,
`dto/*`, `FunnelExecutionEngine`, `StepExecutor`, `FunnelTriggerService` (+`Impl`).
**Backend (other pkgs):** `bot/TelegramSender`, `bot/TelegramApiClient` (`scrubTokens`),
`subscriber/SubscriberCustomFieldsService`, `SubscriberCustomFieldsController`,
`CustomFieldValueValidator`, `SubscriberServiceImpl` (sole-writer surface), `jobs/ProjectHardDeleteJob`.
**Config:** `application.properties`, `.env.example`, `docs/*`.
**Frontend:** `stores/funnels.ts`, `funnels/index.vue`, `funnels/[funnelId].vue`,
`components/funnels/*` (CreateFunnelDialog, FunnelStepForm, FunnelStepsList, FunnelTriggerSettings).

---

## Findings by OWASP vector

### A01 — Broken Access Control / IDOR  → PASS

- Every `FunnelController` endpoint (create / list / get / update / delete / activate / pause)
  delegates to `FunnelService`, which calls `ProjectService.requireOwned(ownerId, projectId, false)`
  **first** — directly in `create`/`list`, and via `requireFunnel(...)` (FunnelService.java:218–231)
  for every id-scoped op, **before** any funnel read.
- Owner id is resolved from the session (`FunnelController.currentUserId()`, line 94), never from the
  request body — no owner-spoofing.
- Funnel lookup is project-scoped: `requireFunnel` loads by id then
  `.filter(f -> projectId.equals(f.getProjectId()))` (line 229). A cross-project `funnelId`,
  a soft-deleted/missing project, and a non-existent funnel all collapse to the **same uniform 404**
  (`MESSAGE_NOT_FOUND`). A malformed ObjectId is caught (`IllegalArgumentException`) and mapped to the
  identical 404 (line 223–227) — no 403-vs-404 or body-shape existence leak (anti-enumeration).
- Cross-project isolation in the engine/trigger/cascade: `FunnelTriggerServiceImpl` cancel queries are
  scoped by `projectId` + `subscriberId` (lines 154, 174); `ProjectHardDeleteJob.removeByProjectId`
  sweeps `funnels`/`funnel_executions` strictly by top-level `projectId.in(deletedIds)` (lines 130–131,
  159–162). `SubscriberCustomFieldsController` and `SubscriberServiceImpl.addTag/removeTag/applyAll`
  scope writes by `(_id, projectId)` (uniform 404 for a foreign subscriber).

### A03 — Injection (template injection)  → PASS

- `VariableTemplateRenderer` escapes **only** substituted values per `parseMode`, leaving author markup
  untouched (Decision 10): `None`→no escape; `HTML`→`& < > "` (incl. `&quot;` against attribute
  breakout, line 190); `MarkdownV2`→the full official special set `_*[]()~\`>#+-=|{}.!` **plus** the
  backslash itself (lines 54, 197–211), iterating the original value once so an attacker-supplied `\*`
  cannot smuggle active markup.
- The single-pass scanner never re-parses a substituted value (lines 90–108), so a subscriber whose
  `first_name`/`username`/custom-field contains `{...}` cannot inject a new placeholder.
- The SendImage **caption** is run through the same renderer with the step `parseMode`
  (`StepExecutor.sendImage`, line 112) — caption is not an unescaped injection sink.
- A subscriber injecting markup/entities via own profile fields or custom-field values is neutralised
  at substitution time for both `text` and `caption`.

### A03 / validation — server-side input validation  → PASS

All enforced server-side in `FunnelService` (not only the client zod schema):

- `text` non-empty + ≤4096 (`requireText`, lines 295–302); empty → 422 `funnel_step_invalid`.
- `imageUrl` http/https scheme only, format-only (`requireImageUrl`, lines 310–318) — see A10.
- `tagSlug` regex `^[a-z0-9_-]{1,32}$` (`requireTagSlug`, line 330).
- `trigger_value` regex `^[A-Za-z0-9_-]{0,64}$` (`normalizeTriggerValue`, line 240) — spaces/specials
  that would break the deep-link are rejected as 422.
- Delay ≥1 with unit MIN/HOUR/DAY (`requireDelay`, lines 320–328) → always ≥1 min (Decision 9 floor).
- Step count ≤ `FUNNEL_MAX_STEPS` (`validateSteps`, lines 274–276).
- `parseMode` restricted to `HTML`/`MarkdownV2`/null (`requireParseMode`).
- Custom-field **values** are validated/normalized at write time by `CustomFieldValueValidator`
  (string ≤1024, finite number, boolean set, ISO-8601 date) on **both** the controller PATCH path and
  the engine `SET_CUSTOM_FIELD` path (`StepExecutor.setCustomField` → `validateAndNormalize`,
  line 148) — the engine cannot bypass it.

### A10 — SSRF  → PASS

- The backend **never dereferences** `imageUrl`. `StepExecutor.sendImage` passes it verbatim to
  `TelegramSender.sendPhoto`, which places it in the `photo` request **body** field
  (`TelegramSender.java:144`) — Telegram fetches it. No `RestClient`/HTTP call targets a user-supplied
  URL anywhere in the engine, service, or validation.
- `requireImageUrl` does scheme-format validation only (no fetch). An `imageUrl` pointing at
  `http://169.254.169.254/...` or `http://localhost/...` is never contacted by the backend; only
  Telegram's infrastructure would resolve it, outside our network/metadata boundary.

### A09 — Security Logging / PII & secret leakage  → PASS

- Every log statement in the funnel package (verified by grep across `FunnelExecutionEngine`,
  `StepExecutor`, `FunnelTriggerServiceImpl`) carries **only** identifiers and codes —
  `executionId`, `funnelId`, `subscriberId`, `projectId`, `stepType`, `stepIndex`, lengths, batch
  size, status, reason code, and `exception.getClass().getSimpleName()`. **No** rendered text,
  caption, custom-field value, or subscriber profile field is ever logged (Decision 16). Over-length
  trims log only `originalLength`/`trimmedTo`, not content (`StepExecutor` lines 92, 114).
- `SET_CUSTOM_FIELD` / `ADD_TAG` / `REMOVE_TAG` do not log field values (the skip log carries only
  ids, `StepExecutor` line 137).
- Bot-token non-leakage from the new `sendPhoto`: the decrypted token lives only as a `sendOnce` local
  and is substituted into the `/bot{token}/sendPhoto` URI template (`TelegramSender.java:286, 302`).
  Every error/log path that could carry the URL or upstream description is wrapped in
  `TelegramApiClient.scrubTokens(...)` (lines 184, 219, 261, 334, 347, 382–383), whose regex
  `\d{1,20}:[A-Za-z0-9_-]{30,50}` (`TelegramApiClient.java:33`) redacts the token shape **anywhere**
  in a string — including inside `/bot<token>/sendPhoto` (Decision 13). 4xx/5xx send-failure paths log
  only `attempts` + scrubbed description.

### A04 / A05 — Insecure Design & Misconfiguration / isolation  → PASS

- `fire()` error-isolation (Decision 6): the entire body of
  `FunnelTriggerServiceImpl.fire` (and `cancelActiveFor`) is wrapped in `try/catch(Throwable)` that
  logs the exception **class name only** and returns (lines 83–132, 137–165) — a funnel fault never
  propagates into the webhook worker, so no retry-storm / re-upsert / re-fire DoS vector.
- Bot-pin (Decision 7): the execution stores the `telegramBotId` resolved at trigger time
  (`insertExecution`, line 194); the engine re-checks that pinned id still maps to a CONNECTED bot
  before any send (`FunnelExecutionEngine.runExecution`, lines 141–146). A bot reconnect cannot
  silently send from a different bot.
- Sole-writer audit: all subscriber mutations from the engine route through `SubscriberServiceImpl`
  (`addTag`/`removeTag`/`recordCustomFieldsSet`), the only writer of `subscriber_events`; the engine
  mirrors the controller's validate→apply→record cycle (`StepExecutor.setCustomField`, lines 141–157,
  Task 15 fix). `SubscriberCustomFieldsService.setOne`/`applyAll` cannot bypass
  `CustomFieldValueValidator` — `applyAll`'s precondition (slug keys only) is enforced upstream by the
  controller's mass-assignment defense (only project-defined keys reach the DB) and by the engine's
  definition-name match gate (`resolveFieldType`).
- At-most-once integrity: engine claim + every in-tick write is a `findAndModify` CAS on
  `stepRunStatus=in_progress` (`FunnelExecutionEngine` lines 199–290, Task 15 fix), so a concurrent
  terminal cancel (funnel delete / `/stop`) deterministically wins and a cancelled/deleted execution
  cannot be resurrected.

### XSS (frontend)  → PASS

- No `v-html` / `innerHTML` / dangerous sink anywhere in the funnel components or pages (grep → none).
  All dynamic values (`funnel.name`, the deep-link, step fields) render through Vue's auto-escaped
  `{{ }}` interpolation. The deep-link is shown as escaped text inside `<code>{{ link }}</code>`
  (`FunnelTriggerSettings.vue:82`), never as an `:href`, so a `javascript:`-style URL is impossible;
  the value is additionally regex-constrained server-side.
- The client does not rely on client-only validation as a security control — `FunnelStepForm` zod rules
  mirror `FunnelService`, and the server independently re-validates every field (see A03/validation).

---

## INFO notes (no action required)

- **I1 (info)** — `FunnelService.requireCustomFieldKey` (line 336) validates the funnel step's
  `customFieldKey` only as non-blank, not against the `^[a-z0-9_-]{1,32}$` slug shape. Mongo dotted-path
  / operator injection into `customFields.<key>` is **fully prevented downstream**: the engine's
  `StepExecutor.resolveFieldType` only resolves a key that exactly equals an existing project field
  definition name (`key.equals(d.name())`), and a non-matching key is skipped with no DB write.
  Adding the slug check at step-save time would make the invariant explicit rather than relying on the
  downstream gate (defense-in-depth, not a vulnerability).
- **I2 (info)** — `recordCustomFieldsSet` persists old/new custom-field values into `subscriber_events`
  metadata (`SubscriberServiceImpl.java:235–239`). This is the intended audit trail (Decision 10/11),
  not a log leak, and these rows are swept on project hard-delete by `ProjectHardDeleteJob`
  (`subscriber_events` cascade) — consistent with the rest of the subscriber domain and bounded by the
  project lifecycle.
- **I3 (info)** — `SubscriberCustomFieldsController` line 81 logs a dropped **unknown key name** at
  `debug` (parameterized, no value). The key is an owner-supplied field identifier, not subscriber PII;
  no rendered value is logged. Acceptable.

---

## Cross-check vs tech-spec Acceptance Criteria (security items)

| Security AC / Decision | Status |
|---|---|
| Anti-IDOR: `requireOwned` first + uniform 404 | ✅ confirmed (FunnelService.requireFunnel) |
| Substituted-value escaping (HTML/MarkdownV2), text **and** caption | ✅ confirmed (VariableTemplateRenderer + StepExecutor) |
| No payload/PII in logs (Decision 16) | ✅ confirmed (grep of funnel-pkg logs) |
| Bot token not leaked from `sendPhoto` (Decision 13) | ✅ confirmed (scrubTokens regex covers `/bot{token}/`) |
| No SSRF — backend does not fetch `imageUrl` (A10) | ✅ confirmed (URL only in request body) |
| `fire()` error-isolation (Decision 6), bot-pin (Decision 7), sole-writer (Decision 11) | ✅ confirmed |

**Conclusion:** the `10-funnels` feature is clear for deploy from a security standpoint. No
critical/high/medium/low findings; three info-level hardening/documentation notes recorded above.
