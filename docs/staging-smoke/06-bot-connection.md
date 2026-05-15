# 06-bot-connection — Staging Smoke Runbook (Manual, ~10 min)

Manual real-Telegram smoke procedure for feature `06-bot-connection`. Run
after a fresh deploy to staging. Mirrors `user-spec.md` § "Пользователь
проверяет — manual smoke checklist on staging" (lines 444–471).

This procedure is the **only** post-deploy verification gate for this
feature — there is no automated post-deploy QA task (real-Telegram cannot
be exercised by an agent: needs `@BotFather` + a human Telegram account).

## Prerequisites

- Staging URL of the backend (`APP_URL`) is reachable and serves
  `GET /health` → `HTTP 200`.
- A staging frontend deployment is reachable and points at the same
  backend.
- A staging admin / test owner account (`smoke_test_owner@…`) with at
  least one project created.
- Your personal Telegram account, logged in on a device near you.

## Steps

- [ ] **1. Create a fresh test bot in `@BotFather`.**
      In Telegram, message `@BotFather`:
      `/newbot` → name it `SmokeTest_<initials>_<date>_bot` (must end in
      `_bot`). Save the token BotFather returns — looks like
      `1234567890:AAH...` (numeric ID, colon, ~35-char secret).
      Do NOT paste this token in any shared chat / commit / screenshot.

- [ ] **2. Login as `smoke_test_owner@…`** in the staging frontend and
      open the project's **Settings → Bot** tab.
      You should see the Connect form (single password-type input, hint
      text "Get one by messaging `@BotFather` → `/newbot`", Connect
      button).

- [ ] **3. Paste the token, click Connect.** Within ~5 seconds the page
      MUST replace the Connect form with the Connected view showing:
      - `@SmokeTest_<initials>_<date>_bot` (username, large/bold)
      - the first_name BotFather assigned
      - a masked token of the literal shape `{telegramBotId}:•••...{suffix}`
        (e.g. `1234567890:•••...xyz` — middle bullet is U+2022)
      - a **Send Test Message** button (enabled)
      - a **Disconnect** button (enabled)
      If any of these are missing, STOP and file a bug; do not continue.

- [ ] **4. Trigger a `/start` from your personal Telegram account** so the
      bot has at least one `chat_id` on file (06b/Epic 05 will start
      populating `subscribers` from this point on). In your personal
      Telegram, open the bot via the username from step 3, click Start
      (or type `/start`). The bot will not reply yet — that's expected;
      it just gives 06b a `chat_id` to send to.

- [ ] **5. Click "Send Test Message" on the staging UI.**
      - Pre-06b expected response: an inline error keyed `errors.bot.testMessage.422`
        — "Send `/start` to your bot in Telegram first, then try again."
        (D7 — feature 06 stub-422s this endpoint until 06b lands.)
      - Post-06b expected response: a Telegram message arrives in your
        chat saying "Bot connected ✅" (06b acceptance criterion — do not
        validate here if 06b has not deployed yet).

- [ ] **6. Verify the webhook URL in BotFather.** In `@BotFather`:
      `/mybots` → tap your `SmokeTest_…_bot` → `Bot Settings` →
      `Webhook`. The URL field MUST contain
      `{staging-APP_URL}/webhooks/telegram/{projectId}` exactly. If the
      URL field is empty or points to a different host, STOP and file a
      bug (AC10 regression).

- [ ] **7. Test platform-wide uniqueness (R2 / AC6 / D1).** In the
      staging frontend, create a second project under the same user
      (Projects → New). Open **its** Settings → Bot. Paste the SAME
      token from step 1 into the new project's Connect form. Click
      Connect.
      - Expected: inline error keyed `errors.bot.connect.409` —
        "This bot is already connected to another project."
      - Expected NOT to contain: the first project's name, the owner's
        email, or any other identifier of the existing project (R2 anti-
        leak — generic copy only).

- [ ] **8. Return to the first project's Settings → Bot. Click
      Disconnect.** A modal appears with the AC19 copy:
      "Disconnect bot `@SmokeTest_…_bot`? Incoming messages will stop
      and the webhook will be removed. You can reconnect anytime."
      Click **Confirm**. Within ~3 seconds the page returns to the
      empty Connect form (no Connected view).

- [ ] **9. Verify the webhook is cleared in BotFather.** Re-run step 6's
      navigation: `/mybots` → `SmokeTest_…_bot` → `Bot Settings` →
      `Webhook`. The URL field MUST now be empty (AC13 — Disconnect
      issued `deleteWebhook` and Telegram released it).

- [ ] **10. Reconnect with the same token to verify the second `bots`
      document is created (D17 / append-only audit).** In the first
      project, paste the same token again → Connect. Expect the
      Connected view to reappear (same identity).
      Then, via an admin Mongo console (`mongosh
      mongodb://…/botfunnel --eval 'db.bots.find({projectId: "<id>"})
      .toArray()'`), confirm TWO documents exist for that `projectId`:
      one with `status: "disconnected"` (from step 8) and one with
      `status: "connected"` (the new row). The encrypted-token /
      tokenSuffix / webhookSecretHash fields on the disconnected doc
      MUST all be null (AC13 / D17 — Disconnect zeroes them).

## Sign-off

Each box above must be checked before staging is declared "06 OK". If
any step fails, file a bug citing the AC number, the step number, and
the observed vs. expected outcome. Do NOT promote to production while
any box is unchecked.

## Notes

- The `BOT_TOKEN_ENCRYPTION_KEY` for staging is set via the staging
  environment's secret store; never paste it in chat / commit / IM.
- The smoke bot's token can be revoked in `@BotFather`
  (`/mybots → … → Revoke Token`) after this checklist completes; the
  staging document with `status: "disconnected"` is harmless to leave
  behind for the audit log.
- This runbook is **manual-only** because the agent cannot exercise the
  real Telegram API on a human BotFather account; do not try to automate
  it.
