# Execution Plan: 18-funnel-canvas

**Создан:** 2026-06-13

**Всего волн:** 7 (5 реализации + Audit Wave + Final Wave)

---

## Wave 1 (независимые)

### Task 1: Backend additive persistence — canvasPosition + notes[] round-trip
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew test --tests '*FunnelServiceIT*' --tests '*FunnelControllerIT*'` → round-trip passes

### Task 2: Frontend foundation — Vue Flow/dagre deps + types
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-smoke:** `cd frontend && pnpm install && pnpm build` → builds clean (no `window is not defined`)

## Wave 2 (зависит от Wave 1)

### Task 3: Backend execution semantics — null next = end, on_start explicit entry
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Depends on:** Task 1
- **Verify-smoke:** `cd backend && ./gradlew test --tests '*FunnelExecutionEngineIT*'` (slow-lane) → green

### Task 4: Frontend model↔graph mapping layer
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Depends on:** Task 2
- **Verify-smoke:** `cd frontend && pnpm test -- canvas` → mapping unit specs pass

## Wave 3 (зависит от Wave 2)

### Task 5: Canvas surface component (client-only)
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Depends on:** Task 4
- **Verify-user:** open the funnel editor at ≥1024px → canvas renders (dagre layout, fitView), handles visible, an edge can be drawn between two saved nodes

## Wave 4 (зависит от Wave 3)

### Task 6: Authoring interactions — palette, start node, delete, side panel
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Depends on:** Task 5
- **Verify-user:** add a node from the palette, add/remove the start node, delete a node and see the disconnect warning, click a node and edit it in the side panel (no target dropdowns)

## Wave 5 (зависит от Wave 4)

### Task 7: Editor page wiring + read-only list + i18n + component tests
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Depends on:** Task 6, Task 3
- **Verify-smoke:** `cd frontend && pnpm test && node scripts/check-locales.mjs && pnpm lint && pnpm typecheck` → all green
- **Verify-user:** move a node, reload → position persists; assemble "menu with buttons → branches", activate, run `/start` in the bot → flow matches the drawn graph

## Audit Wave (Wave 6, зависит от всех задач реализации 1–7)

### Task 8: Code Audit
- **Skill:** code-reviewing
- **Reviewers:** none (auditor IS the review)

### Task 9: Security Audit
- **Skill:** security-auditor
- **Reviewers:** none

### Task 10: Test Audit
- **Skill:** test-master
- **Reviewers:** none

## Final Wave (Wave 7, зависит от Audit Wave)

### Task 11: Pre-deploy QA
- **Skill:** pre-deploy-qa
- **Reviewers:** none

---

## Проверки, требующие участия пользователя

- [ ] Task 5: canvas рендерится на ≥1024px (dagre layout, fitView), handles видны, ребро рисуется между двумя сохранёнными узлами
- [ ] Task 6: добавление узла из палитры, добавление/удаление стартового узла, удаление узла с предупреждением о disconnect, клик по узлу → редактирование в side panel (без target dropdowns)
- [ ] Task 7: перемещение узла → reload → позиция сохраняется; собрать "меню с кнопками → ветки", активировать, пройти `/start` в боте → поток соответствует нарисованному графу
- [ ] После всех волн: финальная проверка живого drag/zoom/pan и end-to-end webhook↔движок↔Telegram (нет живого окружения — проверяется на первом доступном)
