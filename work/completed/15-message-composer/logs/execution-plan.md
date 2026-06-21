# Execution Plan: 15-message-composer

**Створено:** 2026-06-09
**Розмір:** L · **Гілка:** dev · **Усього хвиль:** 8

Замінюємо три плоскі типи кроків (`SEND_MESSAGE`/`SEND_IMAGE`/`MENU`) одним композером
`StepType.MESSAGE` з `List<ContentBlock>`. Бекенд → фронтенд → аудит → деплой.

---

## Wave 1 (незалежні — backend foundation)

### Task 1: ContentBlock модель + StepType + FunnelStep рефактор
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify:** —

### Task 2: TelegramSender — нові sender-методи + альбом-маппер
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd backend && ./gradlew test --tests '*TelegramSenderTest*'`

## Wave 2 (залежить від Wave 1)

### Task 3: StepExecutor — гілка MESSAGE (deps: 1, 2)
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer

### Task 4: FunnelService — validateSteps композер + DTO-ланцюг + preview (deps: 1)
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `curl -X PUT` funnel update з 0 та 11 блоками → 422

### Task 5: Callback-шлях — клавіатура з композер-кроку (deps: 1)
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer

## Wave 3 (frontend types — залежить від backend DTO)

### Task 6: Frontend types — StepType + ContentBlock + preview DTOs (deps: 4)
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer

## Wave 4 (frontend UI — залежить від Wave 3 типів)

### Task 7: FunnelStepForm — саб-редактор композера (deps: 6)
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-user:** funnel editor → зібрати text→image+caption→album×3 + кнопки → save проходить

### Task 8: FunnelMessagePreview — мультиблок-рендер + store preview (deps: 6)
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** funnel editor → превʼю показує блоки стосом у правильному порядку

## Wave 5 (Audit Wave — аудитор IS the review)

### Task 9: Code Audit (deps: 1-8)
- **Skill:** code-reviewing · **Reviewers:** none

### Task 10: Security Audit (deps: 1-8)
- **Skill:** security-auditor · **Reviewers:** none

### Task 11: Test Audit (deps: 1-8)
- **Skill:** test-master · **Reviewers:** none

## Wave 6 (Final — pre-deploy QA)

### Task 12: Pre-deploy QA (deps: 9, 10, 11)
- **Skill:** pre-deploy-qa · **Reviewers:** none

## Wave 7 (Final — deploy) ⚠️ ПОТРЕБУЄ ЗГОДИ КОРИСТУВАЧА

### Task 13: Deploy + ручне очищення funnels/funnel_executions (deps: 12)
- **Skill:** deploy-pipeline · **Reviewers:** none

## Wave 8 (Final — post-deploy verification)

### Task 14: Post-deploy verification (deps: 13)
- **Skill:** post-deploy-qa · **Reviewers:** none
- User-driven: «Test for me» у Telegram

## Проверки, требующие участия пользователя

- [ ] Task 7: користувач відкриває funnel editor → збирає композер → save валідний
- [ ] Task 8: користувач перевіряє мультиблок-превʼю (порядок + типи блоків)
- [ ] Task 13: **згода на деплой + ручне очищення даних** (ALL deployments via GitHub CI/CD)
- [ ] Task 14: «Test for me» у власному Telegram — послідовність повідомлень + кнопка на останньому блоці
