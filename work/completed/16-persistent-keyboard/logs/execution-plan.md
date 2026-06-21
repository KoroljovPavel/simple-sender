# Execution Plan: 16-persistent-keyboard

**Создан:** 2026-06-11

**Feature:** Два нові типи кроків воронки — `SET_KEYBOARD` / `CLEAR_KEYBOARD` (постійна нижня
reply-клавіатура Telegram + її зняття). Fire-and-forget кроки, тап по кнопці маршрутизується
існуючим keyword-dispatch. Розширення preview (бекенд + фронт), валідація, slow-lane ITs.

**Branch:** dev | **Size:** M | **Tasks:** 11 | **Waves:** 5

---

## Wave 1 (независимые)

### Task 1: Backend step model + validation
- **Teammate:** backend-model
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- StepType constants, FunnelStep nullable keyboard fields + copyOf, KeyboardRow/KeyboardButton
  records, DTO mirrors (toSteps + toStepDto), validateSetKeyboard/validateClearKeyboard.

### Task 2: Frontend step form + i18n
- **Teammate:** frontend-form
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** редактор на localhost:3000 — додати SET_KEYBOARD, ряди кнопок, amber-підказка,
  save/reopen — поля зберігаються.
- STEP_TYPES, per-type form blocks, rows sub-editor, чекбокси, keyword hint, uk/en strings.

## Wave 2 (зависит от Wave 1)

### Task 3: StepExecutor execution of both steps (depends: 1)
- **Teammate:** executor
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- Два switch-кейси: renderTrimmed, reply_markup Map builders, 6-arg sendText, cont(), error mapping.

### Task 4: Backend preview extension (depends: 1)
- **Teammate:** backend-preview
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- PreviewStepRequest/Response keyboard fields, kind="keyboard", clamped raw rows, previewStep branch.

## Wave 3 (зависит от Wave 2)

### Task 5: Frontend preview panel (depends: 2, 4)
- **Teammate:** frontend-preview
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** прев'ю-панель показує відрендерений текст + мок клавіатури.
- FunnelMessagePreview.vue render branch, types, no-v-html guard.

### Task 6: Engine slow-lane wire-format ITs (depends: 3)
- **Teammate:** engine-it
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew test -PrunSlow=true --tests '*FunnelExecutionEngineIT*'` → green
- Wire-body assertions для обох кроків, completed-not-parked, keyword-dispatch case.

### Task 7: Manual smoke checklist (depends: 3)
- **Teammate:** smoke-writer
- **Skill:** documentation-writing
- **Reviewers:** code-reviewer
- smoke.md українською за user-spec «Пользователь проверяет».

## Audit Wave — Wave 4 (depends: 1-7)

### Task 8: Code Audit — auditor-code, skill: code-reviewing, reviewers: none
### Task 9: Security Audit — auditor-security, skill: security-auditor, reviewers: none
### Task 10: Test Audit — auditor-tests, skill: test-master, reviewers: none

Кожен аудитор читає decisions.md + усі файли фічі, пише звіт у
`logs/working/audit/{auditor}.json`. Якщо знайдені проблеми → ad-hoc fixer з аудиторами
як ревьюерами.

## Final Wave — Wave 5 (depends: 8, 9, 10)

### Task 11: Pre-deploy QA — qa, skill: pre-deploy-qa, reviewers: none
- Default lane, slow lane, frontend suite, locale parity (pnpm build), усі AC, локальний API-прогін.

## Проверки, требующие участия пользователя

- [ ] Task 2: редактор — SET_KEYBOARD крок, ряди кнопок, amber-підказка, персистентність полів
- [ ] Task 5: прев'ю-панель — відрендерений текст + мок клавіатури
- [ ] После всех волн: ручна перевірка в реальному Telegram-клієнті за `smoke.md`
  (постійна клавіатура, тап → keyword-воронка, бонусний сценарій, CLEAR_KEYBOARD)
