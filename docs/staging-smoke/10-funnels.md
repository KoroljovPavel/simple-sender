# 10-funnels — Staging Smoke Runbook (Manual, ~15 min)

Manual real-Telegram smoke procedure for feature `10-funnels`. Run on staging after the
`08-webhook-ingestion` and `09-subscribers` smokes are green and a project has at least one bot in
`status: "CONNECTED"` with its webhook actually registered against Telegram. Mirrors `user-spec.md`
Сценарій 1 (owner builds and activates a funnel) end-to-end.

This procedure is **opt-in and developer-/staging-only** — never CI, never production. The funnel
trigger path (`/start` → `FunnelTriggerService.fire` → `funnel_execution` → engine sweep → real
Telegram send) cannot be exercised in CI: it needs a public HTTPS endpoint that Telegram can reach via
`setWebhook`, and a human Telegram account to send `/start` and observe delivery. This runbook is the
**only** way to confirm the full chain against real Telegram.

By design the rendered message bodies are **never logged** (Decision 16) — verification is by observing
the actual messages arriving in Telegram plus the named transition log constants (identifiers/codes
only). Do not expect to grep message text out of the logs.

## Prerequisites

- Staging URL of the backend (`APP_URL`) is reachable and serves `GET /health` → `HTTP 200`.
- A staging frontend deployment is reachable and points at the same backend.
- A staging admin / test owner account with a project that has a bot in `status: "CONNECTED"`. The bot's
  webhook MUST be registered with Telegram against the staging `APP_URL` over HTTPS (reuse the bot from
  the `08-webhook-ingestion` smoke, or connect a fresh throwaway `@BotFather` bot and confirm
  `getWebhookInfo` shows the staging URL with no `last_error_message`).
- Your personal Telegram account, logged in on a device near you. Do **not** pre-`/start` the bot for
  the cold-start case in step 5 (the funnel must fire on a fresh `/start`).
- `mongosh` access to the staging Mongo cluster — read from the staging secret store, never pasted
  inline in this runbook, chat, commits, or screenshots.
- Access to the backend logs (e.g. `kubectl logs` / platform log viewer) to read the transition lines.

## Steps

- [ ] **1. Log in as the test owner** in the staging frontend and open
      `localhost`/`{staging-frontend}` → the project → **Funnels** tab
      (`/projects/{projectId}/funnels`). You should see the funnel list (or an empty-state CTA if the
      project has no funnels yet).

- [ ] **2. Create a funnel.** Click **Create funnel**, enter a name (e.g. `smoke-funnel`), submit. The
      app MUST create a `draft` funnel and navigate into the editor (`funnels/{funnelId}`).

- [ ] **3. Build three steps in order:**
      1. **Send message** — body e.g. `Step 1: welcome 👋`
      2. **Delay** — `2 MIN` (short enough to watch, long enough to prove the delay is honoured)
      3. **Send message** — body e.g. `Step 3: after the delay ✅`
      Save the funnel.

- [ ] **4. Set the trigger and activate.** Set the trigger to `on_start` with an **empty** trigger value
      (bare `/start`, no deep-link payload). Click **Activate**. The funnel status MUST become `active`.
      Confirm in `mongosh` (optional):
      `mongosh mongodb://…/botfunnel --eval 'db.funnels.findOne({_id: ObjectId("<funnelId>")}, {status:1, triggerType:1, triggerValue:1})'`
      → expect `status: "active"`, `triggerType: "on_start"`, `triggerValue: ""`.

- [ ] **5. Trigger the funnel from Telegram.** From your personal Telegram account, open the test bot and
      send `/start` (bare, no payload). Within a few seconds Telegram delivers the **Step 1** message
      (`Step 1: welcome 👋`). The **Step 3** message MUST NOT arrive yet — the 2-minute delay is pending.

- [ ] **6. Confirm a running execution was created.** In `mongosh`:
      `mongosh mongodb://…/botfunnel --eval 'db.funnel_executions.find({funnelId: "<funnelId>"}, {status:1, currentStepIndex:1, nextRunAt:1, telegramBotId:1}).sort({createdAt:-1}).limit(1).toArray()'`
      Expect exactly one row for this subscriber with `status: "running"` (or `"waiting"` while the delay
      is pending), a pinned `telegramBotId`, and a `nextRunAt` roughly 2 minutes in the future (the delay
      boundary).

- [ ] **7. Wait out the delay.** After ~2 minutes the **Step 3** message (`Step 3: after the delay ✅`)
      MUST arrive in your Telegram chat. The gap between Step 1 and Step 3 MUST be ≈ the configured delay
      (give or take one sweep interval — `FUNNEL_SCHEDULER_INTERVAL`, default `PT30S`).

- [ ] **8. Confirm the execution completed.** Re-run the query from step 6. The row MUST now show
      `status: "completed"` (and `currentStepIndex` advanced past the last step). No further messages
      arrive.

- [ ] **9. Verify the transition log constants (no payload).** In the backend logs, confirm the named
      transition markers for this run appear — identifiers/codes only, never message text (Decision 16).
      Expect to see the trigger-fire marker (`FUNNEL_FIRE_EXECUTION_STARTED funnelId=… subscriberId=…
      executionId=…`) on `/start`, the per-step engine transitions, and a terminal completion marker.
      You MUST NOT see the rendered message bodies (`welcome`, `after the delay`) anywhere in the logs.

- [ ] **10. (Optional) Re-enter / cancel checks.**
      - **Re-enter:** send `/start` again while the first execution is still running. If the funnel has
        `allowReEnter=false`, no second execution is created (the unique partial index makes it an atomic
        no-op — expect a `FUNNEL_FIRE_REENTER_IGNORED` marker). If `allowReEnter=true`, the old execution
        moves to `cancelled` and a fresh one starts (`FUNNEL_FIRE_REENTER_RESTARTED`).
      - **Cancel:** send `/stop`. All `running`/`waiting` executions for your subscriber MUST move to
        `cancelled` (re-run the step-6 query to confirm) and no further step messages arrive.

- [ ] **11. (Optional) Cascade check.** Soft-delete the test project, then (only on a throwaway staging
      project) wait for / trigger the `ProjectHardDeleteJob`. After the hard-delete run, the project's
      `funnels` and `funnel_executions` documents MUST be gone:
      `mongosh mongodb://…/botfunnel --eval 'db.funnels.countDocuments({projectId: "<projectId>"})'` → `0`
      and the same for `funnel_executions`. The job's `run completed:` log line includes
      `funnelsRemoved=… funnelExecutionsRemoved=…` counters.

## Sign-off

Each non-optional box (1–9) must be checked before staging is declared "10 OK". If any step fails, file
a bug citing the user-spec scenario/AC, the step number, and observed vs. expected outcome. Do NOT
promote to production while any required box is unchecked.

## Notes

- This runbook is **manual-only** — the agent cannot register a real Telegram webhook or drive a human
  `@BotFather` account; do not try to automate it. Developer-/staging-only — never CI, never production.
- Rendered message bodies and any subscriber PII are **never logged** (Decision 16). Step-9 verification
  is by the named transition constants + real Telegram delivery, not by grepping payload out of logs.
- `FUNNEL_SCHEDULER_INTERVAL` (default `PT30S`) is the sweep cadence: a step's actual fire time is its
  `nextRunAt` rounded up to the next sweep tick, so allow up to one extra interval of slack when timing
  the delay in step 7.
- If Step 1 never arrives on `/start`, check `getWebhookInfo` (webhook registered, no
  `last_error_message`), that the bot is `CONNECTED`, and that the subscriber row was upserted
  (`08-webhook-ingestion` smoke). A missing CONNECTED bot or subscriber makes `fire()` a logged no-op
  (`FUNNEL_FIRE_SKIP_NO_CONNECTED_BOT` / `FUNNEL_FIRE_SKIP_NO_SUBSCRIBER`), not an error.
- Never paste the bot token, the staging `mongosh` connection string, or real numeric chat ids into
  chat / commits / screenshots. This file uses placeholders (`<projectId>`, `<funnelId>`,
  `{staging-frontend}`, `{staging-APP_URL}`).
