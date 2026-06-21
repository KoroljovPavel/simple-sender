---
status: done                       # planned -> in_progress -> done
depends_on: []                     # номера задач-зависимостей
wave: 1                            # волна параллельного выполнения
skills: [code-writing]             # МАССИВ скиллов для загрузки
verify: [smoke]                    # типы верификации: smoke, user (опционально)
reviewers: [code-reviewer, security-auditor, test-reviewer]  # явно указать. Пусто = fallback на дефолтные три
teammate_name:                     # косметическое имя тиммейта для agent teams
---

# Task 3: TelegramSender.sendPhoto

## Required Skills

Перед выполнением задачи загрузи:
- `/skill:code-writing` — [skills/code-writing/SKILL.md](../../../.claude/skills/code-writing/SKILL.md)

## Description

Додати метод `sendPhoto(botId, chatId, imageUrl, caption, parseMode, ownerId)` у наявний
`com.botfunnel.bot.TelegramSender` для кроку SendImage рушія воронок (Decision 13: extend, не
sibling-клас). Метод повинен переюзати всю наявну нетривіальну машинерію `sendText`: per-call AES-GCM
token-decrypt, 5xx exponential backoff (1s/2s/4s, 3 ретраї), 429 `retry_after`-loop, overall 30s
timeout, типізоване мапування винятків, audit-event emission і `dispatchSubscriberHook` (auto-flip
blocked/deleted на 403/400-chat-not-found, Decision 4).

Семантика збоїв ОБОВ'ЯЗКОВО та сама, що й у `sendText`: 429 → внутрішній backoff+retry; 403 →
`BLOCKED_BY_USER` + flip blocked + виняток; 400 з "chat not found" → `CHAT_NOT_FOUND` + flip deleted +
виняток; будь-який інший термінальний (інше 400, 5xx-exhausted) → `TelegramSendException` з reason
`OTHER` (для движка це `failed`, відрізняється від cancelled+flip). Це та сама `failure-matrix`, що
дає движку (Task 6) детермінований cancelled-vs-failed розподіл.

Критично з безпеки (Acceptance Criteria): метод МУСИТЬ успадкувати наявну token-redaction-дисципліну.
Декрипт-токен підставляється в URL `/bot{token}/sendPhoto` як шлях-сегмент (як у `sendMessage`) і
НІКОЛИ не повинен потрапити в лог — усі лог-рядки проганяються через `TelegramApiClient.scrubTokens`,
plaintext-токен живе лише як local-variable у `sendOnce`-еквіваленті на час одного HTTP-attempt.

## What to do

- Додати публічний метод `sendPhoto(String botId, Long chatId, String imageUrl, String caption,
  String parseMode, String ownerId)` у `TelegramSender`, що повертає `SentMessage` (як `sendText`).
- Переюзати наявний retry/backoff/timeout/audit/hook-каркас замість дублювання: винести спільну
  оркестрацію (`findById`+CONNECTED-filter → `sendWithRateLimitRetry` → audit success / catch →
  audit fail + `dispatchSubscriberHook` → rethrow) так, щоб і `sendText`, і `sendPhoto` ходили через
  неї. Різниця між методами — лише endpoint (`sendMessage` vs `sendPhoto`) і тіло запиту.
- POST'ити на `/bot{token}/sendPhoto` з тілом `{chat_id, photo: <imageUrl>, caption?, parse_mode?}`
  (`caption` і `parse_mode` додаються лише коли не null — дзеркало того, як `sendOnce` додає
  `parse_mode`). Telegram сам фетчить URL — бекенд URL НЕ дереференсить (без SSRF).
- Зберегти ідентичне мапування статусів через наявні `map4xx`/`toThrowable`/`terminalReasonFor` —
  не вводити окремий шлях мапування для photo.
- Усі лог-рядки на шляху photo — лише через `scrubTokens`; жодного raw URL із токеном, жодного
  логування токена/тіла.
- Не змінювати наявну поведінку `sendText` (рефактор має лишити всі наявні `TelegramSender`-тести
  зеленими).

## TDD Anchor

Додати тести в наявні `TelegramSenderTest` (unit, MockWebServer) та hook-IT. Спершу пишемо → падають
→ реалізуємо → зелені. Дзеркалити наявні `sendText`-сценарії для photo:

- `backend/src/test/java/com/botfunnel/bot/TelegramSenderTest.java::sendPhoto_success_returnsSentMessage`
  — MockWebServer стабує 200 `{ok:true,result:{message_id,chat:{id}}}`; запит іде на
  `/bot.../sendPhoto`, тіло містить `photo`+`caption`+`parse_mode`; повертає `SentMessage`; емітиться
  `telegram_message_sent`.
- `...::sendPhoto_rateLimited_retriesThenSucceeds` — 429 із `retry_after` → внутрішній backoff →
  наступний 200; підтверджує переюз 429-loop (attempts-семантика як у `sendText`).
- `...::sendPhoto_403_flipsBlockedAndThrows` — 403 → `TelegramSendException` reason `BLOCKED_BY_USER`;
  `subscriberService.markBlockedByChatId(projectId, telegramBotId, chatId)` викликано (через
  `dispatchSubscriberHook`).
- `...::sendPhoto_400ChatNotFound_flipsDeletedAndThrows` — 400 `description:"Bad Request: chat not
  found"` → reason `CHAT_NOT_FOUND`; `markDeletedByChatId(...)` викликано.
- `...::sendPhoto_400OtherDescription_terminalOther` — 400 без "chat not found" (напр. невалідний/
  недоступний imageUrl, на який Telegram відповідає 400) → `TelegramSendException` reason `OTHER`,
  жоден `mark*` не викликано (для движка це `failed`, не cancelled).
- `...::sendPhoto_5xxExhausted_throwsTransientExhausted` — стабільний 5xx → після MAX_RETRIES →
  `TelegramSendException("transient_failure_exhausted")`.
- `...::sendPhoto_failure_doesNotLeakDecryptedToken` — на фейл-сценарії (напр. 400) перехопити лог-
  вивід і впевнитись, що декрипт-токен (значення в `/bot{token}/sendPhoto`) НЕ з'являється в жодному
  лог-рядку (assert via `scrubTokens`-дисципліна) — Acceptance Criteria «токен не тече в лог».
- `backend/src/test/java/com/botfunnel/bot/TelegramSenderSubscriberHookIT.java::sendPhoto_403_marksBlockedByChat`
  (та `...sendPhoto_chatNotFound_marksDeletedByChat`) — IT-рівень flip через справжній
  subscriber-hook шлях, дзеркало наявних `sendText` hook-сценаріїв.

## Acceptance Criteria

- [ ] `sendPhoto(botId, chatId, imageUrl, caption, parseMode, ownerId)` існує, POST'ить
      `/bot{token}/sendPhoto` з тілом `{chat_id, photo, caption?, parse_mode?}`, повертає `SentMessage`.
- [ ] 429 → внутрішній backoff+retry (та сама логіка, що `sendText`), не термінальний.
- [ ] 403 → flip blocked (`markBlockedByChatId`) + `TelegramSendException` reason `BLOCKED_BY_USER`.
- [ ] 400 з "chat not found" → flip deleted (`markDeletedByChatId`) + reason `CHAT_NOT_FOUND`.
- [ ] Інше 400 (недоступний/невалідний URL) → reason `OTHER`, БЕЗ flip (движок → `failed`).
- [ ] 5xx-exhausted → `TelegramSendException("transient_failure_exhausted")`.
- [ ] Audit: success → `telegram_message_sent`; термінальний фейл → `telegram_send_failed`.
- [ ] Декрипт-токен НЕ тече в лог із `sendPhoto` (усі лог-рядки через `scrubTokens`; token —
      лише local var на час HTTP-attempt).
- [ ] Наявна поведінка `sendText` не змінена; усі наявні `TelegramSender`-тести зелені.
- [ ] Спільна машинерія переюзана, без дублювання retry/429/5xx/hook-коду.

## Context Files

- [user-spec.md](../user-spec.md) — критерій «Send Image», «Send-failure semantics»
- [tech-spec.md](../tech-spec.md) — Task 3, Decision 13 (extend), Decision 10 (caption escaping/1024
  ліміт — екранування caption робить Task 2/6, тут лише транспорт), «How it works» SendImage
  failure-matrix, Acceptance Criteria «декрипт-токен не тече в лог»
- [decisions.md](../decisions.md)
- [project.md](../../../.claude/skills/project-knowledge/references/project.md)
- [architecture.md](../../../.claude/skills/project-knowledge/references/architecture.md)
- [patterns.md](../../../.claude/skills/project-knowledge/references/patterns.md) — Testing section,
  token-redaction / sole-writer / subscriber-hook конвенції
- [TelegramSender.java](../../../backend/src/main/java/com/botfunnel/bot/TelegramSender.java) — файл для модифікації
- [TelegramSenderTest.java](../../../backend/src/test/java/com/botfunnel/bot/TelegramSenderTest.java) — MockWebServer unit-взірець
- [TelegramSenderSubscriberHookIT.java](../../../backend/src/test/java/com/botfunnel/bot/TelegramSenderSubscriberHookIT.java) — hook-flip IT-взірець

## Verification Steps

### Automated
- `cd backend && ./gradlew test --tests '*TelegramSender*'` → all pass (нові sendPhoto-сценарії +
  усі наявні sendText-тести лишаються зеленими).

### Smoke
MockWebServer-сценарії failure-matrix, що підтверджують поведінку (кожен пункт — конкретний тест-метод):
- 403 → blocked-flip: `TelegramSenderTest::sendPhoto_403_flipsBlockedAndThrows` (reason `BLOCKED_BY_USER`,
  `markBlockedByChatId` викликано) + IT `TelegramSenderSubscriberHookIT::sendPhoto_403_marksBlockedByChat`.
- 400 «chat not found» → deleted-flip: `TelegramSenderTest::sendPhoto_400ChatNotFound_flipsDeletedAndThrows`
  (reason `CHAT_NOT_FOUND`, `markDeletedByChatId` викликано) + IT
  `TelegramSenderSubscriberHookIT::sendPhoto_chatNotFound_marksDeletedByChat`.
- інше 400 → термінальний `OTHER` → движок `failed`: `TelegramSenderTest::sendPhoto_400OtherDescription_terminalOther`
  (reason `OTHER`, жоден `mark*` не викликано).
- 5xx-exhausted → `failed`: `TelegramSenderTest::sendPhoto_5xxExhausted_throwsTransientExhausted`
  (`TelegramSendException("transient_failure_exhausted")` після MAX_RETRIES).
- token-no-leak / scrubTokens assertion: `TelegramSenderTest::sendPhoto_failure_doesNotLeakDecryptedToken`
  (на фейл-сценарії перехоплений лог-вивід не містить декрипт-токена з `/bot{token}/sendPhoto`).

## Details

**Files:**
- `backend/src/main/java/com/botfunnel/bot/TelegramSender.java` — наразі має лише `sendText` +
  приватний каркас `sendWithRateLimitRetry` → `sendWith5xxRetry` → `sendOnce`, плюс
  `dispatchSubscriberHook`, `map4xx`/`toThrowable`/`terminalReasonFor`, audit `sentMetadata`/
  `failedMetadata`, константи `EVENT_TELEGRAM_MESSAGE_SENT`/`_SEND_FAILED`, `scrubTokens`-логування,
  `MESSAGE_BOT_NOT_FOUND` filter на CONNECTED-бота. Додати `sendPhoto` поряд із `sendText`,
  параметризувавши endpoint+body спільного шляху (НЕ дублювати retry/hook/audit-логіку).
- `backend/src/test/java/com/botfunnel/bot/TelegramSenderTest.java` — додати sendPhoto-сценарії
  (200/429/403/400-chat-not-found/400-other/5xx-exhausted + token-no-leak), за наявним
  MockWebServer-патерном.
- `backend/src/test/java/com/botfunnel/bot/TelegramSenderSubscriberHookIT.java` — додати
  sendPhoto flip-сценарії (403→blocked, chat-not-found→deleted) дзеркально наявним.

**Dependencies:** немає (Wave 1, depends_on: []). Споживач — Task 6 (FunnelExecutionEngine SendImage
executor) — у наступних хвилях.

**Edge cases:**
- 400 з порожнім/null/іншим description → reason `OTHER` (не CHAT_NOT_FOUND), без flip.
- Недоступний/невалідний imageUrl: бекенд URL НЕ фетчить — Telegram повертає 400 → `OTHER` → движок
  `failed`. Format-перевірка imageUrl (http/https) — на рівні DTO/сервісу (Task 5), не тут.
- `caption`/`parseMode` == null → відповідні ключі НЕ додаються в тіло (дзеркало `parse_mode` у
  `sendOnce`). Caption-ліміт 1024 та екранування — відповідальність renderer'а/executor'а
  (Task 2/6), `sendPhoto` транспортує те, що передали.
- 401 (token invalid) і 429 мапляться у виділені типи ДО `terminalReasonFor` — не зачіпати.
- bot не CONNECTED / не існує → `AppException.notFound(MESSAGE_BOT_NOT_FOUND)` (uniform 404), hook
  не диспатчиться (як у sendText).

**Implementation hints:**
- ПЕРЕВАЖНИЙ (безпечний) рефактор: витягнути generic-helper, що приймає endpoint-path + body-supplier,
  і переюзати його з обох — `sendText` і `sendPhoto`. Зберегти `attempts` AtomicInteger +
  `deadline`-семантику без змін.
- Уникати параметризації наявних приватних `sendWith*Retry`/`sendOnce`: їх сигнатури фіксовані й
  спільні з `sendText` — зміна ризикує зламати наявний sendText-ланцюг. Якщо все ж торкаєшся їх,
  роби це через новий generic-шлях, а не модифікацією наявних сигнатур.
- Тіло: `body.put("photo", imageUrl)` (URL — Telegram сам фетчить), `caption`/`parse_mode` лише
  якщо не null.
- НЕ логувати raw URL із токеном і НЕ логувати imageUrl/caption на щасливому шляху; усі error-логи
  через `TelegramApiClient.scrubTokens(...)`.
- Не міняти `terminalReasonFor`/`toThrowable` — переюзати as-is, щоб failure-matrix збіглася з
  `sendText` byte-for-byte.

## Reviewers

- **code-reviewer** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-3/code-reviewer-{round}.json`
- **security-auditor** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-3/security-auditor-{round}.json`
- **test-reviewer** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-3/test-reviewer-{round}.json`

## Post-completion

- [ ] Записать краткий отчёт в decisions.md по шаблону (Summary: 1-3 предложения, ревью со ссылками на JSON, без таблиц файндингов и дампов)
- [ ] Если отклонились от спека — описать отклонение и причину
- [ ] Обновить user-spec/tech-spec если что-то изменилось
