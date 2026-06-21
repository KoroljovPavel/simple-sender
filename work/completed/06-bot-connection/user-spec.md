---
# Creation date (YYYY-MM-DD)
created: 2026-05-13

# Status: draft | approved
status: approved

# Work type: feature | bug | refactoring
type: feature

# Feature size: S (1-3 files, local fix) | M (several components) | L (new architecture)
size: L
---

# User Spec: 06-bot-connection

This is the FIRST of three sub-features decomposed from Epic 04 (Telegram bots).
The next two — `06b-webhook-ingestion` and `06c-telegram-sender` — are planned
separately.

## Что делаем

A new "Settings → Bot" page inside a project lets the project owner connect
their own Telegram bot by pasting the bot token. The token is validated through
Telegram's `getMe`, encrypted at rest, and a webhook is auto-registered in
Telegram pointing at `${APP_URL}/webhooks/telegram/{projectId}` with a
per-project random secret header (stored as a hash on our side). The page
also exposes Disconnect and a Send Test Message button. The bot is always
shown masked; the raw token never leaves the backend after first submission.
One bot per project at a time; the same Telegram bot cannot be connected to
two projects simultaneously across the whole platform.

## Зачем

Without a connected bot a project is inert — no subscribers can arrive, no
messages can be sent, no funnel or broadcast logic has anything to operate on.
Bot connection is the gatekeeper for every downstream value in the product
(Epics 05 subscribers, 06 funnels, 07 broadcasts, 08 public API). It is also
the single action that turns an empty project into a working one in the MVP
10-minute onboarding goal (project.md). The owner must be able to do it from
a single page with no external dependencies beyond BotFather.

## Как должно работать

### Pre-step (outside our app)

The owner creates a Telegram bot via `@BotFather`: opens Telegram → `/newbot` →
enters display name → enters username (must end in `bot`) → receives a token.
This is documented as an inline hint on the Connect form.

### First-time connect

1. Owner navigates to project Settings and clicks the "Bot" sub-nav link
   (a new horizontal tab inside Settings, alongside the existing "General").
2. The page shows: title "Connect your Telegram bot", a token input with
   placeholder "Paste your bot token from BotFather", an inline hint
   "Get one by messaging @BotFather → /newbot", and a Connect button
   (disabled while the input is empty).
3. The owner pastes the token and clicks Connect. The button enters a
   loading state; the input becomes read-only.
4. The backend validates the token format (Telegram tokens have a strict
   `<numeric-id>:<35-char-secret>` shape). Tokens that fail the format check
   return an inline form error "Bot token has invalid format" — no network
   call is made.
5. The backend pre-checks: no other bot is currently connected for this
   project (returns 409 "another bot is already connected — disconnect it
   first" if there is), and the Telegram bot ID is not already connected
   anywhere else on the platform (returns 409 "this bot is already connected
   to another project" — generic, no foreign project info).
6. The backend calls Telegram `getMe`. If the response is 401, the form shows
   "Token is invalid or revoked". If Telegram is unavailable (5xx or
   timeout), the backend retries with backoff; after exhausted retries the
   user sees a toast "Telegram is currently unavailable. Try again in a
   minute."
7. On `getMe` success the backend generates a fresh random webhook secret,
   encrypts the token, and calls Telegram `setWebhook` with the secret in
   the `X-Telegram-Bot-Api-Secret-Token` header. `setWebhook` is retried
   with the same backoff on transient errors. If Telegram returns a 4xx
   that signals a config problem (e.g. "HTTPS url must be provided"), the
   user sees a toast "Webhook configuration error — contact support" and
   the operator's logs carry the precise reason.
8. If both Telegram calls succeed, the backend persists the `bots` document.
   If Mongo persist fails AFTER `setWebhook` succeeded, the backend issues
   a compensating `deleteWebhook` (best-effort) so Telegram is not left
   with a webhook the system doesn't know about, and returns a 500 to the
   user.
9. On success the page swaps to the Connected view.

### Connected view

- Shows `@username` (prominent) and `first_name` (subtle), plus the masked
  token (first numeric segment + a few obfuscation dots + the last 3 chars).
- Two buttons: `Send Test Message` (primary) and `Disconnect` (destructive
  variant).
- Inline note under Send Test Message: "To enable test message, write
  `/start` to your bot in Telegram first."

### Send Test Message

The owner sends `/start` to the bot from their own Telegram account
(outside our app). Then they click Send Test Message. The backend looks
up the owner's chat_id in subscribers (populated by 06b webhook ingestion
+ Epic 05). Until 06b lands, subscribers stays empty, so the endpoint
returns the same explanatory error "Send /start to your bot in Telegram
first, then try again". When 06b is delivered, the endpoint starts
working transparently without code changes in this feature.

### Disconnect

1. Owner clicks Disconnect. A confirmation modal opens: "Disconnect bot
   @{username}? Incoming messages will stop and the webhook will be
   removed. You can reconnect anytime." Cancel and Disconnect buttons.
2. On confirmation, the backend calls Telegram `deleteWebhook` with retries
   on transient failure. On persistent Telegram failure, a WARN is logged
   ("orphan webhook may remain in Telegram") and the disconnect proceeds
   anyway — the owner is never blocked from disconnecting because Telegram
   is down.
3. The `bots` document is updated atomically: status becomes `disconnected`,
   encrypted token and webhook-secret hash are nulled, `disconnectedAt` is
   set.
4. The page returns to the empty Connect view. Any orphan webhook still
   pointing at us will be rejected by 06b because the secret check fails.

### Reconnect

Pasting a new token after disconnect (same bot OR different bot) creates
a NEW `bots` document. The old disconnected document stays for audit.
There is no in-place update on reconnect — historical events keep pointing
at the original bot they belonged to.

### Bot uniqueness across the platform

If the owner tries to connect a Telegram bot whose ID is already present
in another `bots` document with `status=connected` (own or foreign
project), the backend returns 409 with a generic message: "This bot is
already connected to another project." No foreign project name, owner
email, or other identifier is ever exposed.

## Критерии приёмки

### Connect

- [ ] **AC1.** `POST /api/v1/projects/{projectId}/bot/connect` with a valid
  token returns 200 with `{telegramBotId, telegramUsername,
  telegramFirstName, status:"connected", connectedAt}`. The response never
  contains the raw or encrypted token.
- [ ] **AC2.** A token failing the Telegram format check returns 400. The
  endpoint does NOT call Telegram. Frontend resolves
  `errors.bot.connect.400`.
- [ ] **AC3.** A token that Telegram rejects on `getMe` (HTTP 401) returns
  422 from our endpoint. Frontend resolves `errors.bot.connect.422`. No
  `bots` document is persisted.
- [ ] **AC4.** Telegram 5xx or timeout on `getMe` OR `setWebhook` triggers
  inline retries with exponential backoff; once exhausted, the endpoint
  returns 502. Frontend resolves `errors.bot.connect.502`. No `bots`
  document is persisted.
- [ ] **AC5.** A Telegram 4xx on `setWebhook` whose `description` indicates
  a configuration problem (e.g. "HTTPS url must be provided") returns 500
  with backend code `webhook_config_error` and logs the full Telegram
  description on the operator side. Frontend resolves
  `errors.bot.connect.500`.
- [ ] **AC6.** Connect with a Telegram bot ID that already exists in some
  `bots` document with `status=connected` (any project) returns 409
  `bot_already_connected`. Generic message; foreign project name/owner
  never revealed.
- [ ] **AC7.** Connect into a project that already has its own
  `status=connected` bot (different Telegram bot ID) returns 409
  `bot_already_in_project` with the message "another bot is already
  connected — disconnect it first".
- [ ] **AC8.** A successful Connect performs side effects in this order:
  (a) get-me verified, (b) `setWebhook` called, (c) `bots` document
  persisted. Failure at any step aborts the flow. The document is
  persisted ONLY if all three succeed. No `pending_webhook` intermediate
  state exists.
- [ ] **AC9.** If `setWebhook` succeeds but the Mongo persist fails, the
  endpoint issues a compensating `deleteWebhook` (best-effort, logged on
  failure) and returns 500. Telegram is not left with a webhook the
  system has no record of.
- [ ] **AC10.** The webhook URL registered in Telegram equals
  `${APP_URL}/webhooks/telegram/{projectId}` and the secret passed to
  `setWebhook` is a fresh random value generated per Connect.
- [ ] **AC11.** Connect is rate-limited per user via Redis counter
  `brute:bot-connect:{userId}` (INCR + 15-minute TTL on first set). The
  11th attempt within 15 minutes returns 429
  `errors.bot.connect.429`. On a successful Connect the counter is DELETED
  for the user (matching the auth pattern). Fail-open if Redis is
  unreachable — both the INCR and the DEL silently no-op on Redis errors;
  a Redis outage never blocks Connect from succeeding.
- [ ] **AC12.** Two concurrent Connect requests with the same token
  (different users, or same user from two tabs) — exactly one succeeds,
  the other returns 409 `bot_already_connected`. Verified by an
  integration test issuing parallel requests against a mocked Telegram.

### Disconnect

- [ ] **AC13.** `POST /api/v1/projects/{projectId}/bot/disconnect`:
  (a) calls `deleteWebhook` with retries on transient Telegram failures;
  (b) on persistent Telegram failure logs a WARN and PROCEEDS to local
      update — the user is never blocked from disconnecting;
  (c) atomically marks the bot `disconnected`, nulls the encrypted token,
      nulls the webhook-secret hash, sets `disconnectedAt`;
  (d) returns 200 once (c) succeeds, regardless of (a)/(b) outcome.
  Returns 404 if no `status=connected` bot exists for the project.

### Read

- [ ] **AC14.** `GET /api/v1/projects/{projectId}/bot` returns the current
  bot with `status=connected` (200) or 404 when none. Shape matches AC1.

### Test message

- [ ] **AC15.** `POST /api/v1/projects/{projectId}/bot/test-message`
  attempts to send a fixed "Bot connected ✅" message via Telegram
  `sendMessage` to the owner's chat_id, looked up in subscribers (a
  collection populated by 06b/Epic 05). For 06 MVP, subscribers is
  empty, so the endpoint returns 422 with
  `errors.bot.testMessage.422` = "Send /start to your bot in Telegram
  first, then try again". The endpoint contract is final — when 06b
  lands it starts working without code changes in this feature.

### Security & isolation

- [ ] **AC16.** Every bot endpoint calls `ProjectService.requireOwned`
  FIRST. Hostile request bodies (`ownerId` or similar) are ignored — the
  authenticated user is taken from the security context. Foreign,
  soft-deleted, and malformed `projectId` all collapse to 404 per the
  anti-enumeration pattern.
- [ ] **AC17.** The raw bot token never leaves the backend after Connect:
  no endpoint response and no log line carries it. A unit test on the
  masking function plus a code-review checklist enforce this. Decrypted
  tokens are not cached in 06 (deferred to 06c).
- [ ] **AC18.** UI shows the token masked as `{first id segment}:•••...{last
  3 chars}` (e.g. `1234567890:•••...xyz`). Read-only display; no "Show
  full token" affordance exists.
- [ ] **AC19.** Disconnect opens a shadcn-vue confirmation modal with the
  copy: "Disconnect bot @{username}? Incoming messages will stop and the
  webhook will be removed. You can reconnect anytime." Cancel /
  Disconnect buttons. The API call only fires on the Disconnect click.
- [ ] **AC20.** The webhook secret is stored as a SHA-256 hash, not in
  plaintext. The plaintext exists only in process memory during the
  Connect call and is passed to Telegram `setWebhook`; nothing on our
  side persists it. 06b will verify incoming requests by hashing the
  header and comparing to the stored hash.

### Frontend integration

- [ ] **AC21.** `frontend/pages/projects/[projectId]/settings.vue` is
  refactored into a folder. The existing General + Danger Zone live in
  `settings/index.vue`; the new Bot page lives in `settings/bot.vue`. A
  horizontal sub-nav inside Settings links between them.
- [ ] **AC22.** New i18n keys in both `uk.json` and `en.json`; the
  prebuild parity gate passes:
  - `bot.*` — page strings (titles, button labels, hints, modal copy).
  - `errors.bot.connect.{400, 422, 500, 502, 409, 429}` — Connect endpoint
    errors. The 500 key surfaces a generic "Webhook configuration error —
    contact support" message; the precise reason from Telegram lives in
    operator logs only (see D8 in `decisions.md`).
  - `errors.bot.testMessage.{422, 502}` — Test-message endpoint errors.
    The 502 key is reserved for forward compatibility: when 06b/Epic 05
    populate `subscribers` and `chat_id` becomes available, an actual
    Telegram `sendMessage` call from the test-message endpoint can hit a
    transient external failure. In 06 the endpoint short-circuits before
    any network call, so 502 is not reachable yet.
  - `errors.bot.disconnect.{404}` — Disconnect endpoint errors. Disconnect
    never returns 502 (best-effort semantics in AC13).

### Audit

- [ ] **AC23.** Audit events are written through `EventService.logEvent`
  on each state change:
  - `bot_connected` — references the bot id, Telegram bot id and username.
  - `bot_disconnected` — references the bot id, Telegram bot id, and a
    boolean for whether Telegram `deleteWebhook` succeeded or we logged
    the orphan WARN.
  - `bot_test_message_sent` — references bot id and chat_id.
  Verification: an integration test queries the `events` collection after
  Connect and asserts that no string value in any event's `metadata`
  matches the Telegram-token shape (a numeric segment followed by a
  35-character secret). The masking-function unit test (AC17) covers
  the positive side.

## Ограничения

- **Stack.** Spring WebFlux + reactive `WebClient` for Telegram API,
  reactive MongoDB driver for `bots`, Redis for the brute-force counter.
  No new infrastructure components are introduced.
- **Bot uniqueness.** One `status=connected` bot per project. The same
  Telegram bot cannot be connected in two `bots` documents across the
  whole platform.
- **HTTPS for webhooks.** Telegram requires HTTPS. Production `APP_URL`
  must be HTTPS; local development uses ngrok or cloudflared (instructions
  in `docs/local-setup.md`). There is no polling fallback.
- **Bot identity is captured at Connect time.** If the owner later renames
  the bot in BotFather, our cached username/first_name stays stale until
  the owner disconnects and reconnects. Auto-refresh of bot identity is
  out of scope for MVP.
- **No avatar.** Fetching the avatar requires extra Telegram calls and
  produces a token-in-URL file path. Out of scope for MVP.
- **No "Show full token".** Token is masked permanently after Connect;
  rotation = disconnect + reconnect.
- **No active encryption-key rotation.** If the at-rest key is suspected
  compromised, the manual procedure is to rotate the env var, force-
  disconnect all bots through an admin operation, and have owners
  reconnect. A self-service rotation is a future epic.
- **Tokens never round-trip to the frontend.** After successful Connect,
  no API response carries the raw or encrypted token.
- **No localStorage for bot state.** Bot state is fetched server-
  authoritative on every page mount via the existing data-fetch
  composable.
- **"Bot not connected" warning banner on project home is out of scope.**
  Discovery of bot-not-connected state lives in Settings → Bot for this
  feature. A cross-page reminder banner can be added in a later UX epic.
- **Manual user actions, formalized.** (1) Owner creates the bot in
  `@BotFather` outside our app and copies the token. (2) Owner sends
  `/start` to their bot in Telegram before clicking Send Test Message.
  (3) Deleting the bot in BotFather is detected lazily by us — the next
  send attempt sees Telegram 401 (full handling in 06c).

## Риски

- **R1 — Bot token leak through logs or API responses.** Mitigation:
  AES-256-GCM at rest; UI shows masked form only; no API endpoint ever
  returns the raw or encrypted token; logging code paths in the bot
  module are reviewed against a checklist that disallows the token field
  in any log call.
- **R2 — Cross-project information leakage on 409 uniqueness conflict.**
  Mitigation: the 409 response carries a generic message with no foreign
  project name or owner email.
- **R3 — At-rest encryption key compromise.** Mitigation: documented
  manual procedure (rotate env, admin force-disconnect, owners
  reconnect). Active rotation infrastructure is deferred to a future
  epic. The current schema does not carry a per-document key version
  marker — the decision to keep the schema minimal is recorded in
  `decisions.md`.
- **R4 — Race condition on bot uniqueness check.** Mitigation: a
  service-level pre-check on insert plus a partial unique index on
  `(telegramBotId, status)` filtered to `status=connected`. The duplicate
  key error is mapped to the same 409 as the pre-check. The defense-in-
  depth deviation from the existing convention (pre-check only) is
  recorded in `decisions.md` with a recommendation to update
  `patterns.md`.
- **R5 — Orphan webhook in Telegram after Disconnect with Telegram down.**
  Mitigation: webhook-secret hash is nulled on Disconnect. Any orphan
  update still hitting our webhook URL fails the secret check in 06b and
  is rejected. Operations runbook notes the manual `deleteWebhook` curl
  recipe.
- **R6 — Orphan webhook in Telegram after Mongo write fails post-
  setWebhook (Connect path).** Mitigation: compensating `deleteWebhook`
  in the same request (AC9), best-effort + logged.
- **R7 — Webhook secret leak from Mongo backup.** Mitigation: webhook
  secret is stored as a SHA-256 hash, not in plaintext. A Mongo dump
  reveals only hashes; an attacker still cannot forge incoming Telegram
  updates because they need the plaintext secret to pass the header
  check.

## Технические решения

- **Greenfield backend module for bot connection.** No bot-related code
  exists in the repo today; the module is created from scratch.
- **The Telegram HTTP client is built in this feature.** No reactive
  WebClient bean exists in the codebase yet; the bot module introduces
  the first one. Timeouts and retry policy live in tech-spec.
- **AES-256-GCM at rest, single key.** The encryption key is read from a
  single configuration property `BOT_TOKEN_ENCRYPTION_KEY` (no version
  suffix); a per-document key version field is intentionally NOT added,
  per D3 in `decisions.md`. If rotation is needed later, a one-off
  migration job re-encrypts documents — that cost is acceptable given
  the small bot table size.
- **Webhook secret stored as SHA-256 hash.** Plaintext only exists in
  process during the Connect call. 06b verifies incoming updates by
  hashing the header and comparing to the stored hash, so even a Mongo
  backup leak does not give an attacker a forging primitive.
- **Compensating `deleteWebhook` on Connect-path persist failure** (AC9).
  Symmetric to the Disconnect-path best-effort policy.
- **Per-project pre-check on Connect** rejects a second concurrent bot
  with `bot_already_in_project` (AC7), separate from the platform-wide
  `bot_already_connected` (AC6).
- **Bean-validation rejects malformed tokens at the controller layer.**
  The token regex lives on the request DTO; 400 is produced by the
  standard error handler without an explicit `AppException`.
- **Anti-enumeration 404** via `ProjectService.requireOwned` for foreign,
  soft-deleted, and malformed `projectId`. Never 403.
- **Brute-force rate limit `brute:bot-connect:{userId}`.** Single-key
  per-user counter (no IP key for this endpoint — abuse vectors are
  per-account, not per-IP) with 15-minute TTL, threshold 10, INCR on
  failure, DEL on success, fail-open if Redis is down.
- **Disconnect is soft.** The document is preserved with `disconnectedAt`,
  encrypted token and webhook-secret hash nulled. Hard-delete was
  rejected: it would break the historical link between subscribers /
  events and the bot they belonged to.
- **Reconnect creates a new document.** In-place update on reconnect was
  rejected for the same audit-link reason.
- **Settings page becomes a folder.** `settings.vue` is split into
  `settings/index.vue` (existing) + new `settings/bot.vue`. A horizontal
  sub-nav inside Settings links between them. This pre-empts future
  Settings additions (API keys, notifications, timezone) without further
  restructuring.
- **All visible UI strings are localized.** New keys live under `bot.*`
  and `errors.bot.*` in both `uk.json` and `en.json`; the parity gate
  fails the build on drift.
- **Bot state is fetched server-authoritative per page mount.** No
  `localStorage` writes from this feature.
- **No decrypted-token caching in 06.** 1–2 decrypts per session does not
  justify a cache; high-throughput caching is deferred to 06c.

## Тестирование

**Unit-тесты:** делаются всегда, не обсуждаются. Высокая концентрация на
шифровальном компоненте (round-trip + tampered-ciphertext rejection +
wrong-key rejection), мэппинге ошибок Telegram → коды нашего API, и на
функции маскировки токена.

**Интеграционные тесты:** ДЕЛАЕМ.

- Backend: реактивные интеграционные тесты против бэкенда с мок-сервером
  для `api.telegram.org`. Покрытие: happy-path connect, 401 → 422, 5xx
  с исчерпанным retry → 502, 4xx config-error на setWebhook → 500,
  setWebhook OK + persist fail → 500 c compensating deleteWebhook,
  disconnect happy и при недоступном Telegram, уникальность на уровне
  индекса и сервиса, параллельный race (AC12), per-project precondition
  (AC7), rate-limit, DEL counter on success.
- Frontend: компонентные тесты страницы Settings → Bot с мок-API:
  валидация формы, connect/disconnect-потоки, masked token, модалка
  подтверждения, все ветки ошибок и тостов.

**E2E тесты:** НЕ ДЕЛАЕМ. Real-Telegram coverage невозможно без живого
бота и стабильного chat_id; задача e2e (golden path UI) хуже решает то,
что уже покрыто компонентным тестом Settings → Bot. Вместо этого —
manual staging smoke checklist (ниже).

## Как проверить

### Агент проверяет

| Шаг | Инструмент | Ожидаемый результат |
|-----|-----------|-------------------|
| 1. Backend test suite passes | backend test runner | All bot-module tests green; integration tests cover AC1–AC16, AC20 (webhook-secret stored as hash — integration verifies the value in Mongo is base64/hex hash of the value passed to the mock `setWebhook`), and AC23 (including the events-metadata negative assertion). |
| 2. Frontend test suite passes | frontend test runner | `settings/bot.vue` specs cover AC17–AC19 and AC21 behaviorally. |
| 3. i18n parity gate passes | frontend prebuild | Adding `bot.*` to only one locale fails the build; both present → build proceeds (AC22). |
| 4. Token-leak unit test | backend test runner | Masking function returns expected masked form for sample tokens; no production code path serializes the token field of the bot entity to a string (verified via a small reflective assertion). |
| 5. Curl smoke against local dev | shell + curl | A malformed-token POST returns 400 with a bean-validation field error (AC2). |
| 6. Index sanity | mongosh | After app boot in dev mode, `db.bots.getIndexes()` shows the partial unique index on `(telegramBotId, status)` filtered to `status=connected`. |

### Пользователь проверяет — manual smoke checklist on staging

Выполняется ВРУЧНУЮ после деплоя на staging (≈10 минут):

1. Create a test bot via `@BotFather` (`/newbot` → `SmokeTest_XYZ_bot`,
   copy token).
2. Login as `smoke_test_owner@…`, open the project's Settings → Bot.
3. Paste token → Connect. Expect `@SmokeTest_XYZ_bot` + first_name + masked
   token + Disconnect / Send-Test buttons visible within ~5 seconds.
4. From your own Telegram account, write `/start` to the new bot.
5. Click Send Test Message. Expect the 422 "send /start first" message
   until 06b lands; after 06b lands the same step should return
   "Bot connected ✅" in Telegram.
6. In BotFather: `/mybots → SmokeTest → Bot Settings → Webhook` — the URL
   must contain `{APP_URL}/webhooks/telegram/{projectId}`.
7. Create a second test project under the same user, try to paste the
   SAME token → expect 409 inline "this bot is already connected to
   another project". No mention of the first project's name.
8. Return to the first project, Disconnect → modal → Confirm → UI returns
   to the empty Connect view.
9. In BotFather check the webhook field — must be empty.
10. Reconnect with the same token → expect a NEW `bots` document (verify
    via admin Mongo inspection: two documents for the projectId — one
    `disconnected`, one `connected`).

Each step has a checkbox in the staging-smoke template stored alongside
the feature (`docs/staging-smoke/06-bot-connection.md`).
