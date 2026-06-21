# Smoke Verification — 18-funnel-canvas

Per-task Verify-smoke commands and results, captured during feature execution.
Generated: 2026-06-14. Branch: `dev`.

| Task | Verify type | Command | Result |
|------|-------------|---------|--------|
| 1 — Backend persistence | smoke | `cd backend && ./gradlew test --tests '*FunnelServiceIT*' --tests '*FunnelControllerIT*'` | ✅ PASS — round-trip green; full `./gradlew test` BUILD SUCCESSFUL |
| 2 — Frontend foundation | smoke | `cd frontend && pnpm install && pnpm build` | ✅ PASS — builds clean, no `window is not defined` |
| 3 — Engine semantics | smoke | `cd backend && ./gradlew test --tests '*FunnelExecutionEngineIT*'` (slow-lane `-PrunSlow=true`) | ✅ PASS — EngineIT 69 tests green; full suite 1303/1304 (1 pre-existing flaky `TelegramWebhookP99IT` latency benchmark, unrelated) |
| 4 — Mapping layer | smoke | `cd frontend && pnpm test -- canvas` (isolated: `vitest run useFunnelCanvas`) | ✅ PASS — mapping specs 17→20; full canvas suite 549→552 |
| 5 — Canvas surface | user | (auto) `pnpm exec vitest run FunnelCanvas` + `pnpm build` | ✅ PASS auto — FunnelCanvas 27→29; build SSR-safe. Live drag/zoom/draw-edge → user |
| 6 — Authoring | user | (auto) `pnpm exec vitest run` + `pnpm build` | ✅ PASS auto — +41 specs, full 573→575; build SSR-safe. Live add/delete/edit → user |
| 7 — Editor wiring + i18n | smoke + user | `cd frontend && pnpm test && node scripts/check-locales.mjs && pnpm lint && pnpm typecheck` | ✅ pnpm test 582→583; check-locales exit 0; build SSR-safe. ⚠️ `lint`/`typecheck` scripts absent project-wide (pre-existing) — build+test are the gates. Live persist + `/start` flow → user |
| 8 — Code Audit | — | (no smoke) | n/a |
| 9 — Security Audit | — | (no smoke) | n/a |
| 10 — Test Audit | — | (no smoke) | n/a |
| 11 — Pre-deploy QA | — | full gate pass (see below) | ✅ PASS |

## Final QA gates (Task 11)

| Gate | Result |
|------|--------|
| Frontend `pnpm test` (vitest) | ✅ 581 passed / 0 failed (58 files) |
| i18n parity `node scripts/check-locales.mjs` | ✅ exit 0 — `funnels.canvas.*` non-empty in uk + en |
| Frontend `pnpm build` (SSR/prebuild) | ✅ complete, no `window is not defined` |
| Backend `./gradlew test -PrunSlow=true` | ✅ 1304 tests, 0 fail / 0 err, 2 skipped (pre-existing `TelegramSenderIT`); EngineIT 69, 0 skip |

**Acceptance criteria:** 24 PASS / 0 FAIL / 2 DEFERRED-TO-USER.

## Deferred to live user verification (no live bot/environment for an agent)

1. Live canvas at ≥1024px — drag/zoom/pan, dagre+fitView framing, draw-to-connect, delete-with-cleanup warning, add+edit note.
2. End-to-end webhook↔engine↔Telegram `/start` flow — assemble menu→branches, activate, confirm execution follows the drawn graph.

## Known / pre-existing (not introduced by this feature)

- No `lint`/`typecheck` npm scripts (no eslint flat config; `vue-tsc` version mismatch). Authoritative static gates: `pnpm build` + vitest.
- `TelegramWebhookP99IT::p99Latency_*` — hardware-sensitive latency benchmark, flaky on the base tree (passed in the final QA run).
