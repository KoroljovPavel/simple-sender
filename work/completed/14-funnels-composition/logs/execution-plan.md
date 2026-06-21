# Execution Plan: Воронки. Фаза 5 — композиція воронок (14-funnels-composition)

**Создан:** 2026-06-08

---

## Wave 1 (foundation)

### Task 1: Backend data model для SUBSCRIBE_TO_FUNNEL
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify:** —

## Wave 2 (Task 2,3 залежать від Task 1; Task 4 незалежна — паралельно)

### Task 2: Save + activation валідація цілі
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `curl -X POST` funnel-save з поганою ціллю → 422 `funnel_subscribe_*`; активація з `draft`-ціллю → 422 `funnel_subscribe_target_inactive`

### Task 3: Engine enroll-шлях (ядро фічі)
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew test --tests '*FunnelExecutionEngineIT*' --tests '*Subscribe*'` → зелені

### Task 4: Frontend — форма кроку «Почати воронку»
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-user:** localhost funnel editor → «+ Add step» → «Почати воронку»: пікер воронки, пікер кроку входу, прапорець, підказка про не-`active` ціль

## Wave 3 (Audit Wave — залежить від Task 2,3,4; reviewers: none, кожен аудитор = ревью)

### Task 5: Code Audit
- **Skill:** code-reviewing
- **Reviewers:** none

### Task 6: Security Audit
- **Skill:** security-auditor
- **Reviewers:** none

### Task 7: Test Audit
- **Skill:** test-master
- **Reviewers:** none

## Wave 4 (Final Wave — залежить від Task 5,6,7)

### Task 8: Pre-deploy QA
- **Skill:** pre-deploy-qa
- **Reviewers:** none
- **Verify-user:** локальний прогін повного сценарію `A → B → меню A` у реальному Telegram

## Проверки, требующие участия пользователя

- [ ] Task 4: автор перевіряє рендер форми «Почати воронку» в редакторі (localhost)
- [ ] Task 8 / після всіх волн: ручний наскрізний прогін `A → B → меню A` у реальному Telegram (інлайн-кнопки приходять webhook'ом, без тунелю не автоматизується)
