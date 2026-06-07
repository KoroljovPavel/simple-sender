# 11-funnels-interactive — Staging Smoke Runbook (Manual, ~20 min)

Manual real-Telegram smoke procedure for feature `11-funnels-interactive` (Phase 2 — interactive `MENU`
step, inline keyboards, callback branching, loops, timeout). Run on staging after the `10-funnels` smoke
is green, on a project with a bot in `status: "CONNECTED"` whose webhook is registered against the staging
`APP_URL` (so Telegram can POST `callback_query`). Mirrors `user-spec.md` «Пользователь проверяет».

This procedure is **opt-in and developer-/staging-only** — never CI, never production. The callback path
(button press → Telegram `callback_query` → webhook → `advanceOnCallback` → `resumeOnCallback` → branch
send) cannot be exercised in CI: it needs a public HTTPS endpoint Telegram can reach and a human Telegram
account to tap inline buttons and observe real keyboard behaviour. Webhook simulation (covered by ITs) does
**not** reproduce the real spinner / inline-keyboard UX — this runbook is the only way to confirm it.

Message bodies and subscriber PII are **never logged** (Decision 16 / Phase-2 Decision 9 — events carry
ids/codes only). Verify by observing real Telegram delivery + the named transition log constants below.

## Prerequisites

- Backend staging `APP_URL` reachable, `GET /health` → `HTTP 200`; staging frontend points at it.
- Test owner account with a project + a bot in `status: "CONNECTED"`, webhook registered against staging
  HTTPS (reuse the `10-funnels` bot; confirm `getWebhookInfo` shows the staging URL, no `last_error_message`).
- Your personal Telegram account on a device near you. For a clean run, `/stop` the bot first so no stale
  `waiting_for_reply` execution lingers.
- `mongosh` access to staging Mongo (read from the staging secret store — never paste inline).
- Backend log access (`kubectl logs` / platform viewer) to read transition markers.
- A SECOND throwaway Telegram account (or a colleague's) for the IDOR check in step 10 — optional but
  recommended.

## Steps

- [ ] **1. Open the editor.** Log in as the test owner → project → **Funnels** → create a funnel
      (e.g. `smoke-menu`) → enter the editor (`funnels/{funnelId}`).

- [ ] **2. Build an interactive graph.** Add steps in order:
      1. **MENU** — text e.g. `Головне меню, {user.first_name} 👇`, then **3 buttons**:
         - callback `Купити` → target a **Send message** step you add next (step 3 below);
         - callback `Назад у меню` → target **this MENU** itself (a **loop**);
         - URL `Наш сайт` → `https://example.com`.
         (Optional: expand **timeout** → `2 MIN`, target **End** — for step 9.)
      2. **Send message** (`Дякуємо за покупку ✅`) — the `Купити` branch target.
      Confirm the target picker shows step **number + name** only (no raw Mongo id / `__END__`).

- [ ] **3. Save + activate.** Trigger `on_start`, empty value. **Activate** → status `active`.
      Negative checks (the funnel must REFUSE these): temporarily point a button at a step, delete that
      step, Activate → inline error `funnel_broken_edge`; a MENU with only a URL button (no callback) →
      Activate blocked. Restore a valid graph and re-activate.

- [ ] **4. Trigger from Telegram.** From your account send `/start`. Within seconds the **MENU** arrives
      as an inline keyboard; `{user.first_name}` is substituted with your visible name.

- [ ] **5. Confirm the parked execution.** `mongosh`:
      `db.funnel_executions.find({funnelId:"<funnelId>"},{status:1,currentStepId:1,nextRunAt:1,lastButtonClicked:1}).sort({createdAt:-1}).limit(1).toArray()`
      → `status: "waiting_for_reply"`, `currentStepId` = the MENU step id, `nextRunAt` = `null` (no timeout)
      or ~2 min ahead (timeout set). Log: `FUNNEL_MENU_PARKED`.

- [ ] **6. Tap `Купити` (callback → branch).** The button's **spinner clears** (`answerCallbackQuery`) and
      the `Дякуємо за покупку ✅` message arrives. Re-run the step-5 query → execution advanced past the
      MENU then `status: "completed"`; `lastButtonClicked` set. Logs: `FUNNEL_RESUME_CALLBACK` +
      `FUNNEL_CALLBACK_ADVANCED`, and an event `funnel_button_clicked` (ids/codes only — grep confirms NO
      first name / button label text). The webhook also logged `telegram_callback_query` before the flip.

- [ ] **7. Loop check.** `/start` again (fresh execution → MENU). Tap **`Назад у меню`** → the **MENU is
      re-sent** (you can tap again, no hang). Execution stays `waiting_for_reply` on the MENU after the
      loop resend.

- [ ] **8. URL button + stale/double click.**
      - Tap **`Наш сайт`** → Telegram opens the link; the funnel does **NOT** advance (execution still
        `waiting_for_reply`, no new branch message, no `funnel_button_clicked`).
      - **Double-tap** a callback button fast → the branch fires **exactly once** (second tap is a silent
        no-op; log `FUNNEL_CALLBACK_NO_OP` / `FUNNEL_CALLBACK_NOT_WAITING_FOR_REPLY`, spinner still clears).
      - Tap a button on an **already-completed** run (scroll up to an old menu) → no-op, spinner clears.

- [ ] **9. Timeout branch (only if you set a timeout in step 2).** `/start`, receive the MENU, **do not
      tap**. After ~2 min (≤ one sweep interval slack, `FUNNEL_SCHEDULER_INTERVAL` default `PT30S`) the
      execution follows the timeout target (here End → `completed`). The sweep picks it up because
      `nextRunAt` was set; a menu WITHOUT a timeout (`nextRunAt=null`) must NEVER self-advance.

- [ ] **10. (Recommended) IDOR / cancel checks.**
      - **IDOR:** from a SECOND Telegram account, `/start` the same bot to get your own parked execution,
        then (if you can craft it) a `callback_data` referencing the FIRST account's `executionId` must be
        rejected — the first execution does NOT move. Log: `FUNNEL_CALLBACK_OWNER_MISMATCH`. (If you can't
        forge the payload by hand, this is covered by the IDOR IT — skip the live attempt.)
      - **/stop while waiting:** with a parked MENU, send `/stop` → execution(s) move to `cancelled`
        (re-run step-5 query); a subsequent button tap is a no-op.
      - **Paused-drain:** park a MENU, set the funnel to `paused` in the editor, then tap a callback button
        → the in-flight execution STILL advances to `completed` (pause only blocks NEW `/start`s).

- [ ] **11. Phase-1 regression + migration.** Re-run the `10-funnels` smoke's linear funnel once on this
      staging build → it still completes (graph migration didn't break linear runs; pre-existing funnels
      got `id`s via `FunnelStepIdBackfill` at startup — check the boot logs for its idempotent run line).

## Sign-off

Boxes 1–8 (and 11) must be checked before staging is "11 OK"; 9–10 are recommended. On any failure, file a
bug citing the user-spec AC, the step number, and observed vs. expected. Do NOT promote to production while
any required box is unchecked.

## Notes

- **Manual-only** — the agent cannot register a real Telegram webhook, tap inline buttons, or drive a human
  account; do not automate. Developer-/staging-only — never CI, never production.
- `callback_data = "{executionId}:{buttonIndex}"` (≤64 bytes). Malformed/oversized/foreign data is treated
  like a stale button: `answerCallbackQuery` fires + silent no-op (`FUNNEL_CALLBACK_MALFORMED_DATA` /
  `FUNNEL_CALLBACK_OWNER_MISMATCH` / `FUNNEL_CALLBACK_NO_EXECUTION`). The spinner ALWAYS clears, even on no-op.
- `answerCallbackQuery` is best-effort: a Telegram 5xx on the ack does NOT block the branch
  (`TELEGRAM_ANSWER_CALLBACK_FAILED` WARN only).
- A loop with no MENU/Delay between steps trips the per-tick budget (`FUNNEL_MAX_STEPS_PER_TICK`, default
  `100`) → execution `failed` + greppable `FUNNEL_STEP_BUDGET_EXCEEDED`. You should never hit this with a
  well-formed menu graph.
- `timeoutUnit` values are `MIN`/`HOUR`/`DAY` (same as Delay). URL buttons are `http(s)`-only.
- Never paste the bot token, the staging `mongosh` connection string, or real numeric chat ids into chat /
  commits / screenshots. This file uses placeholders (`<projectId>`, `<funnelId>`, `<executionId>`).
