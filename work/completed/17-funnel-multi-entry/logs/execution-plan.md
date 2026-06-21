# Execution Plan: 17-funnel-multi-entry (Epic 8 — Phase 8, Multi-entry graph model)

**Создан:** 2026-06-13

**Team:** `17-funnel-multi-entry` · 8 хвиль · 12 задач

---

## Wave 1 (независимые)

### Task 1: Domain model `List<Trigger>` + `onStartTriggerValue` + index shape
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer

## Wave 2 (зависит от Task 1)

### Task 2: Repository array-aware queries + index reconciliation
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew test --tests '*FunnelTriggerIndexReconciliationIT' --tests '*FunnelIndexesIT'` → green, reconciliation APPLIED

### Task 3: DTO contract — `List<TriggerDto>`
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer

## Wave 3 (зависит от Task 1-3)

### Task 4: Trigger validation + `onStartTriggerValue` sync + conflict pre-check + duplicate reset
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew test --tests '*FunnelServiceTriggerTypeTest' --tests '*FunnelControllerIT'` → green

### Task 5: Redirect-or-start dispatcher + engine `redirectExecution` + on_start lookup
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew test -PrunSlow=true --tests '*FunnelExecutionEngineIT'` → redirect/start/allowReEnter/broken-cursor/no-second-execution green

## Wave 4 (фронтенд, зависит от Task 3 контракта)

### Task 6: Frontend types, store, and Triggers panel
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** open funnel editor on localhost → Triggers panel renders, add/remove trigger, entry-step picker, duplicate-event-name guard

## Wave 5 (зависит от Task 6)

### Task 7: Editor page wiring for the trigger array
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd frontend && pnpm test tests/pages/funnel-editor.spec.ts` + `node scripts/check-locales.mjs` → green
- **Verify-user:** open editor → add event trigger, pick entry step, reload → trigger persisted

## Wave 6 (Audit Wave — зависит от Task 1-7)

### Task 8: Code Audit
- **Skill:** code-reviewing · **Reviewers:** none (auditor IS the review)

### Task 9: Security Audit
- **Skill:** security-auditor · **Reviewers:** none

### Task 10: Test Audit
- **Skill:** test-master · **Reviewers:** none

## Wave 7 (Final — зависит от Task 8-10)

### Task 11: Pre-deploy QA
- **Skill:** pre-deploy-qa · **Reviewers:** none

## Wave 8 (зависит от Task 11)

### Task 12: Post-deploy verification (first available environment)
- **Skill:** post-deploy-qa · **Reviewers:** none

---

## Проверки, требующие участия пользователя

- [ ] Task 6: панель «Тригери» рендериться, add/remove, entry-step picker, дубль-події підсвічено
- [ ] Task 7: додати event-тригер, вибрати entry-крок, reload → тригер збережено
- [ ] Task 12 (post-deploy, на першому доступному оточенні): жива петля Telegram «кнопка меню → EMIT_EVENT → тригер-вузол → redirect»; ручний wipe `funnels`/`funnel_executions` + чистий старт
