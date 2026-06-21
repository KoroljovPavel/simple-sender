---
status: done
depends_on: [11, 12, 13]
wave: 6
skills: [pre-deploy-qa]
verify: []
reviewers: []
teammate_name:
---

# Task 14: Pre-deploy QA

## Required Skills

Before starting, load:
- /skill:pre-deploy-qa — [SKILL.md](../../../.claude/skills/pre-deploy-qa/SKILL.md)

## Description

Final acceptance gate for the `10-funnels` feature before it is considered ready.
This task runs the complete automated test suite across backend and frontend, then
verifies every acceptance criterion from both the user-spec ("Критерии приёмки") and
the tech-spec ("Acceptance Criteria"). The deliverable is a QA report that records the
result of each test suite and the verification status of each acceptance criterion.

This is the last task in the feature (wave 6) and depends on tasks 11, 12 and 13 being
complete, so all funnels code is in place when QA runs.

Scope notes — what this task does NOT cover:
- Deploy does not apply: there is no CI/CD pipeline yet for this project.
- Post-deploy verification does not apply: the live Telegram run is performed manually
  by a human following the runbook at `docs/staging-smoke/10-funnels.md`. This task does
  not perform the live run; it only confirms the feature is green and the runbook exists.

## What to do

1. Run the full automated test suite and capture the result of each command (note the
   per-command directory — there is no root gradlew wrapper or root package.json):
   - Backend (including slow/integration tests): `cd backend && ./gradlew test -PrunSlow=true`
   - Frontend unit tests: `cd frontend && pnpm test`
   - Frontend E2E tests: `cd frontend && pnpm test:e2e`
   - Locale completeness check: `cd frontend && node scripts/check-locales.mjs`
2. Read the acceptance criteria from both specs:
   - user-spec.md → "Критерии приёмки"
   - tech-spec.md → "Acceptance Criteria"
3. Verify each acceptance criterion. For criteria covered by automated tests, point to the
   passing test(s). For criteria that cannot be confirmed by the suite (e.g. live Telegram
   behavior), mark them as deferred to the manual staging-smoke runbook and note this.
4. Produce a QA report summarizing:
   - Per-suite result (command, pass/fail, failing tests if any).
   - Per-criterion verification status (verified / deferred-to-manual / failed), with the
     evidence (test name or runbook reference) for each.
   - Overall go / no-go conclusion.
5. Deploy and post-deploy steps are not applicable — explicitly state this in the report and
   reference `docs/staging-smoke/10-funnels.md` for the manual live run.

## Acceptance Criteria

- [ ] `cd backend && ./gradlew test -PrunSlow=true` passes (all backend unit + slow/integration tests green).
- [ ] `cd frontend && pnpm test` passes (all frontend unit tests green).
- [ ] `cd frontend && pnpm test:e2e` passes (all E2E tests green).
- [ ] `cd frontend && node scripts/check-locales.mjs` passes (no missing/extra locale keys).
- [ ] Every acceptance criterion in user-spec.md "Критерии приёмки" is verified or explicitly
      deferred to the manual runbook with a reason.
- [ ] Every acceptance criterion in tech-spec.md "Acceptance Criteria" is verified or explicitly
      deferred to the manual runbook with a reason.
- [ ] A QA report is produced with per-suite results, per-criterion status, and a go/no-go conclusion.
- [ ] Report states that Deploy and Post-deploy do not apply and references the manual
      staging-smoke runbook.

## Context Files

Feature:
- [user-spec.md](../user-spec.md) — "Критерии приёмки" (source of acceptance criteria)
- [tech-spec.md](../tech-spec.md) — "Acceptance Criteria" and "Agent Verification Plan"
- [decisions.md](../decisions.md) — decisions made during the feature

Project context:
- [project.md](../../../.claude/skills/project-knowledge/references/project.md)
- [architecture.md](../../../.claude/skills/project-knowledge/references/architecture.md)
- [patterns.md](../../../.claude/skills/project-knowledge/references/patterns.md) — Testing section

Runbook (manual live run — not executed here):
- [docs/staging-smoke/10-funnels.md](../../../docs/staging-smoke/10-funnels.md)

## Verification Steps

### Automated

Run each command from its own subdirectory (there is no root gradlew wrapper, no root
package.json, and the locales script lives at `frontend/scripts/`). Confirm each exits 0:

```bash
cd backend && ./gradlew test -PrunSlow=true
cd frontend && pnpm test
cd frontend && pnpm test:e2e
cd frontend && node scripts/check-locales.mjs
```

All four must pass. Record any failing suite/test in the QA report.

### Smoke

Not applicable — there is no executable smoke check for this acceptance task beyond the
automated suite above.

### User

Not applicable — the live Telegram acceptance run is performed manually by a human via the
runbook `docs/staging-smoke/10-funnels.md` and is out of scope for this task.

## Details

Commands (each runs from its own subdirectory — there is no root gradlew wrapper, no root
package.json, and the locales script is at `frontend/scripts/`):
- `cd backend && ./gradlew test -PrunSlow=true` — backend tests; the `-PrunSlow=true` flag
  enables slow/integration tests gated behind that property.
- `cd frontend && pnpm test` — frontend unit tests (Vitest).
- `cd frontend && pnpm test:e2e` — frontend E2E tests (Playwright).
- `cd frontend && node scripts/check-locales.mjs` — verifies locale files are complete and consistent.

Acceptance-criteria sources:
- user-spec.md → "Критерии приёмки".
- tech-spec.md → "Acceptance Criteria".
Cross-reference both lists; treat the union as the full set to verify. Where a criterion is
only confirmable on a live environment, mark it deferred to the manual runbook rather than
failing it.

Deploy / Post-deploy:
- Not applicable. No CI/CD pipeline exists for this project yet.
- The live Telegram-on-staging run is manual, documented in `docs/staging-smoke/10-funnels.md`.
- The report must state this explicitly so reviewers know live verification is intentionally
  outside automated QA.

Edge cases / hints:
- If a suite fails, do not stop — run the remaining suites so the report captures the full
  picture, then conclude no-go.
- Quote the exact failing test name(s) and the relevant error excerpt for any failure.
- If `frontend/scripts/check-locales.mjs` or any command path differs from the above, locate
  the actual script/command in `frontend/package.json` / `backend/build.gradle` and use it,
  noting the difference.
- The runbook `docs/staging-smoke/10-funnels.md` is created by Task 8; the depends_on chain
  (11, 12, 13 → 8) guarantees it already exists by the time this QA task runs. If it is
  missing, treat that as a blocker and report no-go.

## Reviewers

None — this is a QA/acceptance task; its output (the QA report) is itself the verification.

## Post-completion

- [ ] Write QA report to decisions.md (per-suite results, per-criterion status, go/no-go).
- [ ] If any acceptance criterion was deferred to the manual runbook — list which and why.
- [ ] If deviated from spec (e.g. command names differ) — describe deviation and reason.
- [ ] Update user-spec/tech-spec if anything changed during verification.
