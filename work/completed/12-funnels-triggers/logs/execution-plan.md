# Execution Plan: 12-funnels-triggers — Additional triggers + internal event bus (Phase 3)

**Создан:** 2026-06-07
**Total waves:** 9 (6 implementation + 1 audit + 2 final)

---

## Wave 1 (foundation — independent)

### Task 1: Funnel domain — new trigger types, keywords, EMIT_EVENT, depth field
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer

### Task 3: ApiKey domain + generation/hash service
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer

## Wave 2 (depends on Wave 1)

### Task 2: Trigger-index reconciliation startup migration
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** boot app, grep startup log for migration marker; re-boot → no-op marker

### Task 4: FunnelEventService — subscriber-keyed fan-out dispatcher + loop backstops
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer

## Wave 3 (depends on Wave 2; no file overlap)

### Task 5: Funnel-step trigger integration (tag/field hooks + EMIT_EVENT + depth threading)
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer

### Task 6: Keyword webhook path + waiting_for_reply precedence
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** Telegram MCP — trigger word → funnel starts; same text in a menu → menu stays

## Wave 4 (public API — depends on Wave 1 + Wave 2)

### Task 7: API-key security chain + EventsController
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `curl POST /api/integrations/v1/events` → 202 / 401 / 404 / 400 matrix

### Task 8: Project-settings API-key generate/regenerate endpoint
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** curl POST with session cookie → plaintext once; GET → masked; DB hash only

## Wave 5 (funnel-editor frontend — depends on backend trigger types)

### Task 9: Funnel-editor Phase-3 UI (trigger selector + EMIT_EVENT form)
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer (NO security-auditor — frontend-only)
- **Verify-user:** funnel editor → trigger type selectable; add-step lists "Emit event"

## Wave 6 (settings frontend — depends on Task 8; serialized after Wave 5 to avoid locale-file conflict)

### Task 10: API-key card in project settings
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** settings → Generate shows key once; reload → masked + Regenerate

## Audit Wave (Wave 7 — after all implementation)

### Task 11: Code Audit — Skill: code-reviewing — Reviewers: none (auditor IS the review)
### Task 12: Security Audit — Skill: security-auditor — Reviewers: none
### Task 13: Test Audit — Skill: test-master — Reviewers: none

## Final Wave

### Task 14 (Wave 8): Pre-deploy QA — Skill: pre-deploy-qa — Reviewers: none
### Task 15 (Wave 9): Post-deploy verification (live, local run) — Skill: post-deploy-qa — Reviewers: none

## Проверки, требующие участия пользователя

- [ ] Task 6: keyword fires via Telegram; menu precedence holds (smoke)
- [ ] Task 9: trigger-type selector + EMIT_EVENT step in funnel editor (user)
- [ ] Task 10: API-key card generate/regenerate in settings (user)
- [ ] Task 15: live post-deploy verification on locally-run app (Telegram MCP, curl, bash)
- [ ] After all waves: final feature review
