# Execution Plan: Воронки. Фаза 4 — інструменти автора (13-funnels-tooling)

**Створено:** 2026-06-07
**Команда:** `13-funnels-tooling`
**Гілка:** feature-гілка від `main` (tech-spec → `branch: dev`)
**Хвиль:** 7 · **Задач:** 10

---

## Wave 1 (бекенд-мутації, незалежна)

### Task 1: Duplicate + Stop-all endpoints
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** curl `POST .../funnels/{fid}/duplicate` → 201 draft; curl `POST .../funnels/{fid}/executions/stop` → 200 + count

## Wave 2 (бекенд author-context, після Wave 1 — спільні FunnelController/FunnelService)

### Task 2: Test-run + Preview endpoints
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** curl test-run (ownerChatId=null) → 422 `funnel_owner_not_linked`; curl preview → рендер з екрануванням

## Wave 3 (фронтенд foundation, паралельні — різні файли)

### Task 3: Store-дії + типи
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer

### Task 4: i18n-ключі + коди помилок (обидві локалі)
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-smoke:** `node frontend/scripts/check-locales.mjs` → exit 0

## Wave 4 (фронтенд список + хедер)

### Task 5: Кнопки дій у списку та хедері редактора
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-user:** кнопки рендеряться; Дублювати → draft-копія; Зупинити все → confirm; Test for me при незв'язаному боті → inline /start-підказка

## Wave 5 (фронтенд панель прев'ю, після Wave 4 — спільний [funnelId].vue)

### Task 6: Панель прев'ю повідомлення (greenfield)
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** перемкнути Прев'ю → message-крок показує рендер; не-message → плейсхолдер

## Wave 6 (Audit Wave — 3 аудитори паралельно, reviewers: none)

### Task 7: Code Audit — skill: code-reviewing
### Task 8: Security Audit — skill: security-auditor
### Task 9: Test Audit — skill: test-master

## Wave 7 (Final Wave)

### Task 10: Pre-deploy QA — skill: pre-deploy-qa
- backend `./gradlew test` + frontend vitest + parity-gate; верифікація AC user-spec + tech-spec; без деплою/пост-деплою

---

## Перевірки, що потребують участі користувача

- [ ] Task 5: кнопки в списку/хедері; Дублювати/Зупинити все/Test for me поведінка
- [ ] Task 6: панель прев'ю — рендер message-кроку vs плейсхолдер не-message
- [ ] Після Final Wave: «Test for me» на реальному боті — повідомлення приходить у власний Telegram (бек не спостерігає live-доставку)
- [ ] Деплой відсутній — фіча мерджиться локально, перевіряється вручну
