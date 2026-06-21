# Pre-deploy QA — Task 11 — 18-funnel-canvas

**Verdict: PASS** (zero criticals, all gates green)

## Gates

| Gate | Result |
|------|--------|
| Frontend `pnpm exec vitest run` | **PASS** — 581 passed / 0 failed (58 files): useFunnelCanvas mapping units (19), FunnelCanvas/SidePanel/Palette component specs, funnel-editor page specs (53), i18n required-keys (4). |
| i18n parity `node scripts/check-locales.mjs` | **PASS** — exit 0; uk/en key sets equal. `funnels.canvas.*` present in BOTH locales, 0 empty values each (verified by flattening the subtree). |
| Frontend `pnpm build` (SSR/prebuild parity) | **PASS** — "Build complete!", server bundle emitted, no `window is not defined` (canvas client-only + `<ClientOnly>`). prebuild ran check-locales before nuxt build. |
| Backend `./gradlew test -PrunSlow=true` | **PASS** — BUILD SUCCESSFUL. 1304 tests, 0 fail, 0 err, 2 skipped (pre-existing TelegramSenderIT, unrelated). |

### Backend slow-lane confirmation
- `FunnelExecutionEngineIT`: **69 tests, skip=0** — slow lane genuinely executed (not silently skipped). Includes `next_null_ends_branch_no_fallthrough`, `on_start_enters_entryStepId_regardless_of_order`, `loop_without_wait_trips_step_budget`.
- `FunnelControllerIT`: 108 tests (round-trip: `canvasPosition_step_and_trigger_round_trips`, `notes_round_trip_with_server_minted_id`, `starts_on_coordinate_less_funnel`, `rejects_notes_over_cap_and_oversized_text`).
- `FunnelServiceTriggerTypeTest`: 17 (on_start entry accept/reject + negative non-event case + testRun entry).
- `FunnelServiceNotesTest`: 7; `TriggerTest`: 4 (canvasPosition excluded from equals/hashCode); `CanvasPositionDtoTest`: 1 (finite-value reject).
- `TelegramWebhookP99IT`: PASSED this run.

## Acceptance Criteria tally

**24 PASS / 0 FAIL / 2 DEFERRED-TO-USER**

All user-spec "Критерії приёмки" and tech-spec "Acceptance Criteria" traced to concrete tests (see `pre-deploy-qa.json` for the per-criterion evidence table).

## Deferred to user (no live environment for an agent)
1. **Live canvas at ≥1024px** — real pointer drag/zoom/pan, dagre+fitView framing on a measured viewport, draw-to-connect, delete-with-cleanup warning, add note. (Vue Flow needs a measured DOM container; no E2E by design.)
2. **End-to-end webhook↔engine↔Telegram `/start` flow** — assemble "menu with buttons → branches", activate, run `/start`, confirm execution follows the drawn graph. (No deployed bot yet.)

## Known flakes
- `TelegramWebhookP99IT::p99Latency_*` — hardware/JIT-sensitive P99 latency benchmark, unrelated to this feature, fails identically on the clean base tree. **Passed this run** (0 backend failures) — not a blocker.

## Deviations
- **No `lint`/`typecheck` scripts project-wide** — no eslint flat config / `.eslintrc`, no pinned eslint; local `nuxi/vue-tsc` typecheck fails on an unrelated `@vue/language-core` mismatch + tsconfig `baseUrl` deprecation. **Pre-existing gap, NOT introduced by this feature.** Authoritative static gates: `pnpm build` (Nuxt type-aware bundling) + vitest — both green. No lint/typecheck infra built (out of scope).
- 2 pre-existing backend skips in `TelegramSenderIT` (Telegram send adapter), unrelated to canvas.
