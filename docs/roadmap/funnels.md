# Funnels — Delivery Roadmap

Master checklist for the Funnels epic (project.md → Epic 06, "the heart of the product").
The epic is split into **vertical, independently shippable phases**. Each phase runs the full
cycle: `user-spec → tech-spec → tasks → implementation → done → work/completed/`.

**Rule:** only ONE phase is fully specced at a time. Detailed specs for later phases would go
stale before implementation — this map is the high-level source of truth for done vs. todo.

Source vision: `workflow/06-воронки/README.md` (abstract reference TZ — note its BullMQ/pg-boss
stack notes are illustrative; actual engine = **JobRunr + MongoDB**, see `architecture.md`).

---

## Status legend
`todo` · `in progress` · `done`

## Phases

| # | Phase | Folder | Delivers | Depends on | Status |
|---|-------|--------|----------|------------|--------|
| 1 | **Linear funnels (end-to-end)** | `10-funnels` | CRUD funnels; linear step types (Send Message, Send Image, Wait/Delay, Add Tag, Remove Tag, Set Custom Field); variable templating; engine (JobRunr scheduler, `funnel_executions`, idempotency, statuses); `/start` trigger; anti-spam re-enter guard; `/stop` cancellation; `paused` status; basic vertical-list editor. **No buttons/branches.** | — | done |
| 2 | **Interactive + keyboard** | `11-funnels-interactive` | Inline keyboards in `TelegramSender` (`reply_markup`); `callback_query` handling in webhook; `Wait for Reply` step; `Branch by Button`; composite **"Menu" step** (message + buttons + wait + branch in one node); branch-analytics hooks. | Phase 1 (engine) | todo |
| 3 | **Additional triggers** | `12-funnels-triggers` | Keyword trigger; Tag-added trigger; API-event trigger (`POST /api/v1/events`). | Phase 2 | todo |
| 4 | **Author tooling** | `13-funnels-tooling` | "Test for me" (link service account to Telegram); duplicate funnel; "Force stop all running executions"; message preview + pre-save validation. | Phase 2 | todo |

> **Per-execution analytics** (drop-off, branch distribution) is NOT a funnels phase — it belongs
> to the Analytics epic (`workflow/09-аналітика`). Cross-reference, do not duplicate.

## Notes
- Phases 1→2→3→4 is the dependency order. Priority of 3 ↔ 4 may be swapped later; 1 and 2 are the
  foundation and cannot be reordered.
- Each completed phase: move `work/{folder}` → `work/completed/`, flip status to `done` here,
  and update the integration notes in `architecture.md` (`funnel` module).
