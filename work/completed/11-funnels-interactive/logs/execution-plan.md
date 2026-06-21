# Execution Plan: 11-funnels-interactive — Інтерактив + клавіатури (Фаза 2)

**Створено:** 2026-06-07

**Розмір:** L · **Тасок:** 11 · **Хвиль:** 6

---

## Wave 1 (незалежні)

### Task 1: Граф-модель даних + статус-фундамент
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify:** —

### Task 2: TelegramSender — reply_markup + answerCallbackQuery
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** TelegramSender-IT через mockwebserver — `reply_markup` у body sendMessage; `answerCallbackQuery` шле POST на `/answerCallbackQuery`

## Wave 2 (залежить від Wave 1)

### Task 3: Рушій — граф-навігація, MENU-park, resumeOnCallback, таймаут
- **depends_on:** 1, 2
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify:** —

### Task 4: Валідація графа + DTO
- **depends_on:** 1
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `curl -X POST .../funnels/{id}/activate` з ціллю на видалений крок → 422 `funnel_broken_edge`; `MENU` без callback → 422

## Wave 3 (залежить від Wave 2)

### Task 5: FunnelTriggerService.advanceOnCallback + аналітика-хук
- **depends_on:** 2, 3
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify:** —

### Task 6: Фронтенд — редактор MENU + кнопки + ціль
- **depends_on:** 1, 4
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** open localhost funnel editor → «+ Add step» → MENU → 2 callback-кнопки + ціль → save → activate; битий edge підсвічений інлайн

## Wave 4 (залежить від Wave 3)

### Task 7: Webhook — інжест callback_query
- **depends_on:** 5
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `bash` симуляція `callback_query` на `POST /webhooks/telegram/{projectId}` → `advanceOnCallback` викликано, подія перед flip

## Wave 5 (Audit Wave — залежить від усіх code-тасок)

### Task 8: Code Audit
- **depends_on:** 1–7
- **Skill:** code-reviewing
- **Reviewers:** none (аудитор сам є рев'ю)

### Task 9: Security Audit
- **depends_on:** 1–7
- **Skill:** security-auditor
- **Reviewers:** none

### Task 10: Test Audit
- **depends_on:** 1–7
- **Skill:** test-master
- **Reviewers:** none

## Final Wave (Wave 6)

### Task 11: Pre-deploy QA
- **depends_on:** 8, 9, 10
- **Skill:** pre-deploy-qa
- **Reviewers:** none

---

## Проверки, требующие участия пользователя

- [ ] **Task 6 (Verify-user):** автор відкриває редактор воронок локально, додає крок MENU з 2 callback-кнопками + ціль через SearchableSelect, зберігає, активує; перевіряє, що битий edge підсвічується інлайн.
- [ ] **Після всіх хвиль:** живий прохід у Telegram (реальна поведінка inline-клавіатур — натиск кнопки, петля, таймаут) — ручна перевірка користувача, поза автоматизованим QA.
