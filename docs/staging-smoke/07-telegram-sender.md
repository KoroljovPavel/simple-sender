# 07-telegram-sender — Staging Smoke Runbook (Manual, ~5 min)

Manual real-Telegram smoke procedure for feature `07-telegram-sender`. Run
on staging after `06-bot-connection` smoke is green and a project has at
least one bot in `status: "CONNECTED"`. Mirrors `user-spec.md` §
"Користувач перевіряє" (lines 170–173).

This procedure is **opt-in and developer-/staging-only** — never CI,
never production. The sender's success branch is unobservable in
production until Epic 04b (webhook ingestion) lands and starts
populating `Bot.ownerChatId` automatically. Until 04b ships, this
runbook (seed `ownerChatId` by hand via `mongosh`, then click "Send Test
Message") is the **only** way to exercise the success branch end-to-end
against real Telegram.

## Prerequisites

- Staging URL of the backend (`APP_URL`) is reachable and serves
  `GET /health` → `HTTP 200`.
- A staging frontend deployment is reachable and points at the same
  backend.
- A staging admin / test owner account with at least one project that
  has a bot in `status: "CONNECTED"` (either reuse the bot connected
  during `06-bot-connection` smoke, or create a fresh throwaway test
  bot in `@BotFather` and connect it via 06's steps 1–3 first).
- Your personal Telegram account, logged in on a device near you, with
  the test bot already started (`/start` sent at least once — step 4
  of 06's runbook).
- `mongosh` access to the staging Mongo cluster. The connection string
  is read from the staging secret store; do NOT paste it inline in
  this runbook or in chat / commits / screenshots.

## Steps

- [ ] **1. Locate the target bot document.** In `mongosh` against the
      staging cluster, run:
      `mongosh mongodb://…/botfunnel --eval 'db.bots.findOne({projectId: "<projectId>", status: "CONNECTED"}, {_id: 1, telegramBotId: 1, telegramUsername: 1})'`
      Replace `<projectId>` with the id of your test project. Save the
      returned `_id` (ObjectId) — you will pass it as `<bot _id>` in
      step 3.

- [ ] **2. Obtain your personal Telegram numeric chat id.** In
      Telegram, message `@userinfobot` (exact handle — there are
      lookalikes; verify the username). It replies with a card whose
      `Id` line is a 9–10 digit decimal number. That number is your
      `<numeric-chat-id>`. It is a number, not a string — record it
      verbatim with no quotes.

- [ ] **3. Seed `ownerChatId` on the bot doc.** In `mongosh`:
      `mongosh mongodb://…/botfunnel --eval 'db.bots.updateOne({_id: ObjectId("<bot _id>")}, {$set: {ownerChatId: NumberLong(<numeric-chat-id>)}})'`
      The `NumberLong(...)` wrapper is mandatory — the field is a
      `Long` on the Java side, and a JS-number or a string literal
      (`"<numeric-chat-id>"`) will fail to deserialize into
      `Bot.ownerChatId` and surface as `500` on the next call.
      Expected `mongosh` response: `{ acknowledged: true, matchedCount: 1, modifiedCount: 1 }`.

- [ ] **4. Verify the seed landed correctly.** In `mongosh`:
      `mongosh mongodb://…/botfunnel --eval 'db.bots.findOne({_id: ObjectId("<bot _id>")}, {ownerChatId: 1})'`
      The output MUST show `ownerChatId: Long("<numeric-chat-id>")`
      (or `NumberLong(...)` depending on the `mongosh` formatter). If
      you see a plain JS number or a quoted string, redo step 3 with
      the `NumberLong(...)` wrapper — do not proceed.

- [ ] **5. Login as the test owner** in the staging frontend and open
      the project's **Settings → Bot** tab. You should see the
      Connected view from 06 — the bot username, the masked token, a
      **Send Test Message** button (enabled), a **Disconnect** button
      (enabled). UI surface is the one shipped in feature 04a; no
      changes here.

- [ ] **6. Click "Send Test Message".** Watch the browser dev tools
      Network tab. The request to
      `POST {staging-APP_URL}/api/v1/projects/{projectId}/bot/test-message`
      MUST return `HTTP 200 OK` with empty body within ~5 seconds. UI
      MUST show a success toast / no error banner. If you see `422
      owner_chat_id_unknown`, the seed from step 3 did not take — go
      back to step 4.

- [ ] **7. Confirm Telegram delivery.** Within ~5 seconds of step 6,
      your personal Telegram chat with the test bot MUST receive a
      message whose body is exactly:
      `Hello from Bot Funnel Service! Bot connected ✅`
      (single line, U+2705 white-check-mark at the end, no Markdown /
      HTML formatting — `parse_mode` is null per Decision in
      user-spec). If the message does not arrive, check step 1
      `telegramUsername` matches the bot you `/start`-ed in step 4 of
      06's runbook and check the events query in step 8 for a
      `telegram_send_failed` document.

- [ ] **8. Verify the audit events.** In `mongosh`:
      `mongosh mongodb://…/botfunnel --eval 'db.events.find({eventType: {$in: ["telegram_message_sent", "bot_test_message_sent"]}, createdAt: {$gte: new Date(Date.now() - 5*60*1000)}}).sort({createdAt: 1}).toArray()'`
      Expect **exactly two** new event documents, both timestamped
      within the last few seconds:
      - One with `eventType: "telegram_message_sent"` and `metadata`
        shape `{ botId, chatId, messageId }` — `botId` matches `<bot _id>`,
        `chatId` matches `<numeric-chat-id>`, `messageId` is a positive
        integer assigned by Telegram.
      - One with `eventType: "bot_test_message_sent"` and `metadata`
        shape `{ projectId, telegramBotId, chatId, messageId }` —
        `projectId` matches your test project, `telegramBotId` matches
        the value seen in step 1, `chatId` and `messageId` agree with
        the `telegram_message_sent` document above.
      If you see a third document with `eventType: "telegram_send_failed"`,
      delivery failed silently for the UI — capture its `metadata.errorCode`
      and `metadata.errorDescription` and file a bug citing AC14.

- [ ] **9. Cleanup (optional but recommended on shared staging).** In
      `mongosh`:
      `mongosh mongodb://…/botfunnel --eval 'db.bots.updateOne({_id: ObjectId("<bot _id>")}, {$unset: {ownerChatId: ""}})'`
      Returns the bot to the production-equivalent null-branch state
      so the next operator's `07-telegram-sender` smoke starts from a
      clean slate (and the `06-bot-connection` smoke's `422
      owner_chat_id_unknown` step 5 keeps passing).

## Sign-off

Each box above must be checked before staging is declared "07 OK". If
any step fails, file a bug citing the AC number from user-spec (AC17 —
BotService branching, AC21 — sender IT, AC23 — controller IT), the step
number, and the observed vs. expected outcome. Do NOT promote to
production while any box is unchecked.

## Notes

- This runbook is **manual-only** because the agent cannot exercise the
  real Telegram API on a human `@BotFather` account; do not try to
  automate it. It is **developer-/staging-only** — never CI, never
  production.
- This procedure is the **only** way to exercise the sender's success
  branch end-to-end until **Epic 04b** (webhook ingestion) ships and
  starts populating `Bot.ownerChatId` automatically from incoming
  Telegram updates. Once 04b lands, the seed step (3) goes away and
  this runbook is retired in favour of the 04b post-deploy procedure.
- The hand-seed step is safe to run repeatedly on the same bot —
  `Bot.ownerChatId` is a nullable wrapper field with no migration and
  no index (Decision 14); `$set` and `$unset` are idempotent.
- If the test bot was revoked in `@BotFather` after the `06-bot-connection`
  smoke (`/mybots → … → Revoke Token`), step 6 will return
  `422 invalid_bot_token` instead of `200 OK` — decryption succeeds but
  Telegram returns `401` and the sender maps it to
  `BotTokenInvalidException`. Reconnect the bot via 06's runbook (paste
  the new token in step 3 of 06) before retrying this runbook.
- If step 4 shows `ownerChatId` as a string (`"<numeric-chat-id>"`
  with quotes) instead of `Long`, Spring Data MongoDB will fail to
  deserialize the field on the next read and the call will surface as
  `500`, not `422`. Always wrap the chat id in `NumberLong(...)` in
  step 3.
- Never paste the bot token, the production `BOT_TOKEN_ENCRYPTION_KEY`,
  the staging `mongosh` connection string, or real numeric chat ids
  in chat / commits / screenshots. This file uses placeholders
  (`<projectId>`, `<bot _id>`, `<numeric-chat-id>`, `{staging-APP_URL}`)
  for that reason.
- The regression smoke for the null-branch (`422 owner_chat_id_unknown`
  when `ownerChatId` is unset) is already covered by
  `06-bot-connection.md` step 5 and by automated unit + integration
  tests (`BotServiceTest`, `BotControllerIT`); do not re-run it here.
