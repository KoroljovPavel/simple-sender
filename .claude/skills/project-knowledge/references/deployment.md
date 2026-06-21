# Deployment

## Platform

**TBD** — platform and hosting provider not yet decided. VPS-based deployment is under consideration.

Planned approach: Docker Compose on a single server for MVP, GitHub Actions for CI/CD.

## Deployment Triggers

_TBD — will be defined when platform is chosen._

Planned:
- Push to `main` → automated deploy to production via GitHub Actions
- Manual deploy via `infra/deploy.sh` as fallback

## Environments

| Environment | URL | Notes |
|-------------|-----|-------|
| Production | TBD | |
| Development | `http://localhost:3000` (frontend), `http://localhost:8080` (backend) | Docker Compose |

## Local Development

Infrastructure (MongoDB + Redis + Mailpit) starts with Docker Compose. Backend and frontend run locally (not in Docker) for dev ergonomics:

```bash
docker compose -f infra/docker-compose.yml up -d   # MongoDB :27017, Redis :6379, Mailpit :1025/:8025
cd backend && ./gradlew bootRun                     # Tomcat :8080 (Spring DevTools hot-reload)
cd frontend && nvm use && pnpm install && pnpm dev  # Nuxt :3000 (HMR)
```

App containers (`api`, `web`) are not dockerized for local dev — added when production deployment is set up. All Compose ports bound to `127.0.0.1` only (not `0.0.0.0`).

### Mailpit (local email testing)

Mailpit captures all SMTP traffic from the backend `EmailService` (verification, password reset, blocked-account notifications, subscriber CSV-export-ready links) so no real emails are sent in dev or integration tests.

- **SMTP endpoint:** `localhost:1025` (point `MAIL_HOST=localhost` and `MAIL_PORT=1025`; no auth)
- **Web UI:** `http://localhost:8025` — inspect captured emails, copy verification/reset tokens for manual smoke tests
- **HTTP API:** `http://localhost:8025/api/v1/messages` — used by Mailpit Testcontainer in integration tests to assert that verification/reset emails are sent
- **Image:** pinned to `axllent/mailpit:v1.29.7` in `infra/docker-compose.yml`

## Environment Variables

See `.env.example` for all required variables. Never commit real secrets.

| Variable | Purpose | Notes |
|----------|---------|-------|
| `MONGODB_URI` | MongoDB connection | `mongodb://localhost:27017/botfunnel` in dev |
| `REDIS_URL` | Redis connection | `redis://localhost:6379` in dev |
| `SESSION_TTL_DEFAULT_HOURS` | Session lifetime when `rememberMe=false` | `24` |
| `SESSION_TTL_REMEMBER_ME_DAYS` | Session lifetime when `rememberMe=true` | `30` |
| `SESSION_COOKIE_SECURE` | `Secure` flag on the `SESSION` cookie | `false` in dev (HTTP); MUST be `true` in production (HTTPS only) |
| `SESSION_COOKIE_SAME_SITE` | `SameSite` policy on the `SESSION` cookie | `lax` in dev; `strict` in production (mitigates CSRF per Decision 12) |
| `SUPER_ADMIN_EMAIL` | Email of the seeded Super Admin user | Required at startup; `SuperAdminSeeder` creates/promotes this user |
| `SUPER_ADMIN_PASSWORD` | Password of the seeded Super Admin user | Required at startup; no default — fail-fast if unset |
| `BOT_TOKEN_ENCRYPTION_KEY` | AES-256-GCM key for bot token encryption at rest (`common/crypto/TokenEncryptor`) | 32 bytes / 64 hex chars (generate via `openssl rand -hex 32`). Missing/short/non-hex value → backend fails fast at startup (`TokenEncryptor` constructor). Rotation = redeploy + admin force-disconnect-all + owners reconnect (no schema-level keyVersion marker). |
| `TELEGRAM_BASE_URL` | Telegram Bot API base URL (override for tests / proxies) | Defaults to `https://api.telegram.org`. Production HTTPS only — `setWebhook` requires HTTPS `APP_URL`, no polling fallback. |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` | SMTP credentials | Mailpit `localhost:1025` in dev (no auth); production provider TBD |
| `MAIL_FROM` | `From:` address used by `EmailService` (verification, password reset, blocked notifications) | e.g. `noreply@botfunnel.local` in dev |
| `SUPPORT_EMAIL` | Support contact shown in 403 (blocked user) and 409 (soft-deleted email) error messages | e.g. `support@botfunnel.local` |
| `SENTRY_DSN` | Error tracking | Planned, not yet configured |
| `APP_URL` | Public base URL | Used in email links (verify, reset) and webhook URLs |
| `PROJECTS_MAX_PER_USER` | Hard cap on active projects per user (soft-deleted not counted) | `5` in dev/prod for MVP. Per-user override is a future billing/plans concern — do not raise the env-default to fix a "power user" complaint, raise it via the future plans epic. |
| `SUBSCRIBER_EXPORT_TOKEN_KEY` | HMAC-SHA256 key signing CSV-export download URLs (`common/crypto/SignedDownloadToken`) | 32 bytes / 64 hex chars (`openssl rand -hex 32`). Missing/short/non-hex → backend fails fast at startup (constructor guard, mirrors `BOT_TOKEN_ENCRYPTION_KEY`). Rotation invalidates all outstanding download links → users click "Refresh link" (see `patterns.md` → Subscribers CRM, Decision 15). Distinct from `BOT_TOKEN_ENCRYPTION_KEY` — never reuse a key across the two. |
| `SUBSCRIBER_EXPORT_RETENTION_DAYS` | Age after which `ExportCleanupJob` purges a finished export's GridFS blob + flips it to PURGED | `7` |
| `SUBSCRIBER_EXPORT_URL_TTL_HOURS` | Validity window of a signed download URL before it must be refreshed | `24` |
| `SUBSCRIBER_RATE_LIMIT_START_PER_MIN` | Redis bucket cap on NEW `/start` auto-registrations per project per minute (existing subscribers exempt) | `100` |
| `SUBSCRIBER_DOWNLOAD_RATE_LIMIT_PER_MIN` | Redis bucket cap on signed-URL download attempts | `30` |
| `SUBSCRIBER_PERSONAL_MESSAGE_RATE_LIMIT_PER_MIN` | Redis bucket cap on per-subscriber manual "Send message" calls | `60` |
| `FUNNEL_SCHEDULER_INTERVAL` | Cadence of the `FunnelExecutionEngine` JobRunr `@Recurring` sweep | ISO-8601 Duration `PT30S` (NOT a seconds integer — `@Recurring(interval=)` requires a Duration string). Must be ≥ `org.jobrunr.background-job-server.poll-interval-in-seconds` (pinned `15` in `application.properties`). Drives Delay accuracy (±~interval). |
| `FUNNEL_MAX_STEPS` | Hard cap on steps per funnel | `50`; exceeding → 422 `funnel_step_limit_reached` |
| `FUNNEL_SWEEP_BATCH_SIZE` | Max executions claimed per sweep tick (sweep-load mitigation) | `200` |
| `FUNNEL_MAX_STEPS_PER_TICK` | Phase-2 loop guard: max graph steps executed in one engine tick before `failed` + log marker `FUNNEL_STEP_BUDGET_EXCEEDED` | `100` (> the 50-step funnel cap, so a linear funnel never trips it; a loop without a MENU/Delay dies fast) |
| `FUNNEL_MAX_FANOUT_PER_EVENT` | Phase-3 backstop (c): max executions a single event/keyword/tag/field firing may insert; excess dropped + WARN. Redis-independent — bounds request width even when Redis is down | `50` (≫ realistic listener count) |
| `FUNNEL_AUTO_ENROLL_RATE_PER_MIN` | Phase-3 backstop (a): per-subscriber auto-enroll cap (`bf:rate:auto-enroll:{subscriberId}`, Redis fail-open). Counts only depth>0 (funnel-originated) enrolls; human/external roots exempt | `20` |
| `FUNNEL_MAX_ENROLL_DEPTH` | Phase-3 backstop (b): hard cap on `enrollDepth` (auto enroll-chain depth) carried on each execution. Redis-independent — cuts runaway even when the fail-open rate-limit passes during a Redis outage | `10` |
| `API_EVENTS_RATE_LIMIT_PER_MIN` | Global fixed Redis rate-limit on `POST /api/integrations/v1/events` (`bf:rate:events`, fail-open → 429 over cap) | `300` |

### Frontend cookies

| Cookie | Purpose | Attributes |
|--------|---------|------------|
| `i18n_lang` | UI locale preference (`uk` or `en`); read by `@nuxtjs/i18n` SSR plugin to resolve locale before first paint | `Path=/`, `Expires` ~1 year (module default), `SameSite=Lax`, `Secure` only when `NODE_ENV=production`; **not HttpOnly** — UI-only preference, not auth-significant. Invalid values (e.g. `xx` outside the configured list) are ignored and overwritten on the next request. |

## Frontend Build Gates

- **Locale parity (`pnpm build`).** `frontend/package.json` has `"prebuild": "node scripts/check-locales.mjs"` (npm convention — fires automatically before `nuxt build`). The script compares key sets between `uk.json` and `en.json` and exits 1 on missing file, invalid JSON, or divergent keys. Adding a key in one locale and forgetting the other fails the build. Same script is runnable standalone for smoke checks.
- **Vitest** (`pnpm test`): unit + component specs (Nuxt test environment + happy-dom). Includes the AC18 authorized-exception page specs (see `patterns.md`).
- **Playwright** (`pnpm test:e2e`): single golden-path spec at `frontend/e2e/i18n.spec.ts`. Requires `pnpm exec playwright install chromium` once per machine. `playwright.config.ts` auto-spawns the dev server (`reuseExistingServer: !CI`).

## CI/CD

_TBD — GitHub Actions workflows will be defined when platform is chosen._

Planned pipeline steps:
1. Build and test (Gradle + vitest + playwright; `pnpm build` fails fast on locale divergence per the prebuild gate above)
2. Docker image build
3. Deploy to production server
4. Health check verification

## Production Infrastructure (Planned)

Containers:
- `api` — Spring MVC backend on virtual threads
- `web` — Nuxt frontend (SSR or static)
- `mongo` — MongoDB with persistent volume
- `redis` — Redis
- Reverse proxy (Caddy or Nginx) — HTTPS via Let's Encrypt

Backups: daily automated MongoDB dump to cloud storage (S3-compatible).

## Staging Smoke Runbooks

Per-feature 10-minute manual checklists for flows that can't be covered automatically (require external state — BotFather bot, real Telegram account, etc.).

- **`docs/staging-smoke/06-bot-connection.md`** — Bot Connect/Disconnect/Reconnect + cross-project uniqueness + BotFather webhook field check. Must be ticked end-to-end on staging before promoting to production. Run with a throwaway test bot (`SmokeTest_XYZ_bot`) — never the production marketing bot.
- **`docs/staging-smoke/07-telegram-sender.md`** — Opt-in real-Telegram smoke for the `TelegramSender` success branch via the "Send Test Message" button. With Epic 04b shipped, `Bot.ownerChatId` is populated automatically by the first private `/start`; the runbook's `mongosh`-seed step now applies only if the operator wants to smoke 07 in isolation from 04b. Never run in CI.
- **`docs/staging-smoke/09-subscribers.md`** — Subscriber CRM end-to-end smoke (Epic 09): real `/start`→register, tag + custom-field assign, manual personal message, `/stop`→unsubscribe + `/start`→reactivation, CSV export → Mailpit email → signed-URL download (BOM + Cyrillic render check), concurrent-export 409, signed-URL refresh, key-rotation. Health endpoint is `/health`. Run with a throwaway bot + ngrok; needs `SUBSCRIBER_EXPORT_TOKEN_KEY` set. Last full pass: 2026-05-31 against local-staging (Task 17) — findings F-min-2…F-min-12 fixed, see `work/completed/09-subscribers/logs/qa/post-deploy-verification-report.md`.
- **`docs/staging-smoke/08-webhook-ingestion.md`** — Inbound webhook ingestion smoke (Epic 04b). Connect → BotFather throwaway bot → ngrok tunnel → `/start ref_smoke_001` from owner Telegram → `mongosh` inspection of `raw_updates` + `events.telegram_command_start` + `bot.ownerChatId` populated → click "Send test message" in admin UI (closes the 04c gap that was unobservable in production until 04b) → `/stop` → JobRunr Failed-queue inspection procedure (`db.raw_updates.find({processingStatus:'FAILED'})` + `db.jobrunr_jobs` — dashboard deferred to Epic 10) → cleanup throwaway bot via BotFather. 10-15 minutes. Telegram → ngrok → backend cannot be automated in CI (Telegram requires HTTPS + setWebhook from real domain); this is the de-facto post-deploy verification per 04b Decision 19.

## Startup Migrations

No Mongock yet — schema migrations are idempotent, log-but-never-throw startup hooks that run on every boot regardless of launch method (no separate deploy step). Two exist:

- **`FunnelStepIdBackfill`** (Phase 2) — an `ApplicationRunner` backfilling step `id`s + `currentStepId` onto funnels and in-flight executions.
- **`FunnelTriggerIndexReconciliation`** (Phase 3) — a `BeanPostProcessor` on `MongoDatabaseFactory` (NOT an `ApplicationRunner` — must run before `MongoTemplate` auto-index-creation to avoid an `IndexKeySpecsConflict`; see `patterns.md` → Funnel Engine Phase 3). Drops the old `funnels` trigger index so the relaxed `on_start`-only-unique shape is recreated. Grep the startup log for `trigger-index reconciliation` → APPLIED (first migrating boot) or NO-OP (idempotent re-boot).

**Phase 6 (`15-message-composer`) — one-time manual data wipe, NOT a startup hook (Decision 4).** The composer removed the `SEND_MESSAGE`/`SEND_IMAGE`/`MENU` step types, so pre-Phase-6 `funnels`/`funnel_executions` documents are schema-incompatible. There is deliberately no backfill migration (no production data when authored): on the first deploy that carries Phase 6, manually drop/clear the `funnels` and `funnel_executions` collections, then verify the startup log shows the engine sweep coming up without snapshot-deserialization errors. Safety net if a stray legacy document survives the wipe: the `StepTypeReadConverter` tolerant-read (see `patterns.md` → Funnel Engine Phase 6) reads its removed `stepType` as `UNKNOWN` and terminal-fails only that execution, so the engine does not crash.

Mongock adoption is a separate deferred infra task (`workflow/improvements.md`).

## Monitoring

- **Healthcheck endpoint:** `GET /health` — returns static `{"status":"ok"}` (liveness probe only; no DB connectivity check in Epic 01). Full Actuator health indicators deferred to monitoring epic.
- **Error tracking:** Sentry (planned, not yet configured)
- **Uptime monitoring:** TBD (UptimeRobot or BetterStack planned)
- **JobRunr dashboard:** built-in UI for monitoring background job queues. Currently disabled in `application.properties`; manual Failed-queue inspection in production is via `mongosh db.jobrunr_jobs.find({state:'FAILED'})` plus `db.raw_updates.find({processingStatus:'FAILED'})` for webhook-ingestion failures. Dashboard activation deferred to Epic 10 (admin panel).
- **Webhook ingestion counters (Micrometer, in-memory only).** `telegram_webhook_received_total{projectId}`, `telegram_webhook_duration_seconds` (Timer), `telegram_webhook_rejected_total{reason=invalid_secret|project_not_found|duplicate|payload_too_large}`, `telegram_worker_outcome_total{outcome=success|failure}`. Live in the explicit `SimpleMeterRegistry` (no actuator endpoints, no Prometheus exporter — deferred to analytics epic). Integration tests consume them via `MeterRegistry.find(...).counter().count()` assertions, so they are not dead code.
