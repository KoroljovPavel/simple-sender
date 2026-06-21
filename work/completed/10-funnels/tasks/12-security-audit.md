---
status: done                       # planned -> in_progress -> done
depends_on: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]  # весь feature-код має бути готовий до аудиту
wave: 5                            # audit wave — після всіх імплементаційних задач
skills: [security-auditor]         # МАССИВ скиллов для загрузки
verify: []                         # audit deliverable — без smoke/user верифікації
reviewers: []                      # audit-задача сама є рев'ю — без рев'юерів
teammate_name:
---

# Task 12: Security Audit

## Required Skills

Перед выполнением задачи загрузи:
- `/skill:security-auditor` — [SKILL.md](../../../.claude/skills/security-auditor/SKILL.md)

## Description

Повний security-аудит фічі `10-funnels` (лінійні воронки, Фаза 1) проти OWASP Top 10. Це фінальна
аудит-задача п'ятої хвилі: увесь backend- і frontend-код фічі (Tasks 1–10) уже написаний і пройшов
per-task рев'ю. Аудитор читає **всі** створені/змінені у фічі файли цілісно й шукає security-вразливості,
які могли прослизнути крізь окремі рев'ю — особливо крос-компонентні, уздовж усього шляху даних:
від HTTP-запиту власника та Telegram-апдейта підписника через рушій до Telegram-API й логів.

Фіча вводить нову поверхню атаки: новий REST-CRUD під `/api/v1/projects/{projectId}/funnels`; рушій
(`FunnelExecutionEngine`), що виконує задані власником кроки (надсилання тексту/зображень, теги,
кастом-поля) у фоні JobRunr-воркера; підстановку **значень підписника** (first_name/username/custom-fields)
у шаблони повідомлень; новий `TelegramSender.sendPhoto`, що декриптує токен бота. Кожна з цих точок —
потенційний injection / IDOR / SSRF / secret-leak sink.

Результат — **звіт аудиту** (НЕ код): перелік знахідок із severity, локацією (файл + метод/рядок),
описом вектора, прив'язкою до OWASP-категорії та рекомендацією. Звіт пишеться у `work/10-funnels/logs/`.
Аудит — read-only: знайдені вразливості описуються в звіті, не фіксяться тут.

## What to do

Прочитати всі source-файли фічі (повний список у Details) і провести аудит за наступними OWASP-векторами,
сфокусованими саме на цій фічі:

1. **A01 Broken Access Control / IDOR.** Перевірити, що **кожен** ендпоінт `FunnelController`
   (create / list / get / update / delete / activate / pause) викликає `ProjectService.requireOwned`
   **першим** — до будь-якого читання funnel/виконання. Перевірити, що funnel-lookup завжди скоупиться
   `projectId` власника (немає шляху прочитати/змінити чужу воронку за голим `funnelId`). Перевірити
   **uniform 404**: чужий / soft-deleted / неіснуючий `projectId` чи `funnelId` дають однаковий 404 — без
   витоку «існує, але не твій» через 403-vs-404 чи різні тіла помилок. Перевірити cross-project isolation
   у рушії та каскаді (виконання й `ProjectHardDeleteJob` працюють строго за top-level `projectId`).

2. **A03 Injection — template injection.** Перевірити `VariableTemplateRenderer`: підставлені значення
   змінних (`{user.*}`, `{custom.*}`) екрануються за правилами обраного `parseMode` (HTML / MarkdownV2),
   тоді як авторська розмітка лишається як є (Decision 10). Підтвердити, що **caption** кроку SendImage
   проганяється через той самий екранувальник (caption — рівноцінний injection-sink). Шукати пропущені
   спецсимволи повного MarkdownV2-charset і можливість підписника інʼєктнути розмітку/ентіті через власні
   first_name / username / значення кастом-полів.

3. **A03 / validation — input validation на сервері.** Перевірити server-side валідацію (не лише фронт)
   всіх полів кроку та тригера: порожній `text` → 422; `imageUrl` — лише http/https-схема (format-only,
   сервер НЕ фетчить URL); `tagSlug` regex `^[a-z0-9_-]{1,32}$`; `trigger_value` regex
   `^[A-Za-z0-9_-]{0,64}$` (пробіли/спецсимволи зламали б deep-link); `delayValue` ≥1хв; ліміт
   `FUNNEL_MAX_STEPS`. Переконатися, що це робиться на сервері (DTO bean-validation / `FunnelService`),
   а не лише в клієнтських формах.

4. **A10 / SSRF.** Підтвердити, що backend **ніколи не дереференсить** `imageUrl` — Telegram сам фетчить
   URL у `sendPhoto`. Перевірити, що ніде в рушії / сервісі / валідації немає HTTP-запиту на
   user-supplied URL (інакше — SSRF на внутрішню мережу / метадані-ендпоінти).

5. **A09 Logging — no PII / secret / payload leakage.** Перевірити, що жоден лог не містить відрендереного
   тіла повідомлення / caption / значень кастом-полів — лише ідентифікатори + стан + код-причину
   (Decision 16; logs-only + indefinite retention без TTL → витік PII / GDPR). Перевірити, що
   декрипт-токен бота **не тече** в лог із нового `sendPhoto` (URL `/bot{token}/sendPhoto` має скрабитись
   наявним `scrubTokens`, Decision 13). Перевірити, що SetCustomField / AddTag / RemoveTag-кроки не логують
   значення полів.

6. **A04 / A05 — design & isolation.** Перевірити error-isolation `fire()` (Decision 6): виняток усередині
   не пробивається у webhook-воркер (інакше DoS-вектор через retry-шторм + повторний upsert/re-fire).
   Перевірити bot-pin (Decision 7): reconnect бота не дає «таємно» слати з іншого бота. Перевірити, що
   мутації підписника йдуть лише через `SubscriberServiceImpl` (sole-writer audit), а
   `SubscriberCustomFieldsService.setOne` не обходить валідацію `CustomFieldValueValidator`.

7. **Записати звіт** у `work/10-funnels/logs/security-audit.md`: кожна знахідка — severity
   (critical / high / medium / low / info), файл + локація, OWASP-категорія, опис вектора, рекомендація.
   Якщо вразливостей немає — явно зафіксувати «no findings» з переліком перевірених векторів. Підсумок —
   у decisions.md (див. Post-completion).

## Acceptance Criteria

- [ ] Прочитано всі backend + frontend файли фічі (список у Details) — аудит цілісний, не per-task.
- [ ] Перевірено anti-IDOR: `requireOwned` першим у кожному ендпоінті `FunnelController`; funnel-lookup
      скоупиться `projectId`; uniform 404 для чужого / soft-deleted / неіснуючого ресурсу.
- [ ] Перевірено template injection: екранування підставлених значень під HTML/MarkdownV2 у text **і**
      caption; авторська розмітка не екранується.
- [ ] Перевірено input validation на сервері: text, imageUrl (http/https), tagSlug, trigger_value,
      delay <1хв, ліміт кроків.
- [ ] Підтверджено відсутність SSRF: backend не фетчить `imageUrl`.
- [ ] Перевірено A09: жоден лог не містить payload/PII; декрипт-токен бота не тече з `sendPhoto`.
- [ ] Перевірено `fire()` error-isolation, bot-pin, sole-writer + валідацію кастом-полів.
- [ ] Звіт записано в `work/10-funnels/logs/security-audit.md` із severity / локацією / OWASP /
      рекомендацією по кожній знахідці (або явним «no findings» + перелік перевіреного).

## Context Files

- [user-spec.md](../user-spec.md)
- [tech-spec.md](../tech-spec.md) — Decisions 6, 7, 10, 11, 13, 16; Acceptance Criteria (security items)
- [decisions.md](../decisions.md) — Task Execution Log: фактичні файли, відхилення, посилання на рев'ю
- [project.md](../../../.claude/skills/project-knowledge/references/project.md)
- [architecture.md](../../../.claude/skills/project-knowledge/references/architecture.md)
- [patterns.md](../../../.claude/skills/project-knowledge/references/patterns.md) — security/logging/validation
  конвенції проєкту (`scrubTokens`, `requireOwned`, atomic-claim ідіоми)

**Файли фічі для аудиту** — повний backend + frontend список у секції Details.

## Verification Steps

<!-- NON-CODE аудит-deliverable: автоматичних тестів немає. Верифікація = повнота й коректність звіту. -->

### Audit deliverable

- Звіт `work/10-funnels/logs/security-audit.md` існує й покриває всі 6 OWASP-векторів вище.
- Кожна знахідка має severity, файл + локацію, OWASP-категорію, опис вектора й рекомендацію.
- Якщо знахідок немає — звіт явно перелічує перевірені вектори й фіксує «no findings».
- Крос-перевірка проти tech-spec Acceptance Criteria (security items): anti-IDOR + uniform 404,
  екранування підстановок, no-payload-in-logs (Decision 16), token не тече з `sendPhoto` (Decision 13 /
  AC) — кожен пункт або підтверджено, або відкрито як знахідка.

## Details

<!-- NON-CODE аудит-задача. Deliverable — звіт, не зміни в коді. -->

**Що читати (backend, нове / змінене у фічі — Tasks 1–8):**
- `backend/src/main/java/com/botfunnel/funnel/Funnel.java`, `FunnelStep.java`, `FunnelExecution.java`,
  `FunnelRepository.java`, `FunnelExecutionRepository.java` — модель / персистенція, індекси, скоуп `projectId`
- `backend/src/main/java/com/botfunnel/funnel/VariableTemplateRenderer.java` — **injection-критичний**
  (екранування HTML / MarkdownV2 підставлених значень)
- `backend/src/main/java/com/botfunnel/funnel/FunnelService.java`, `FunnelController.java`,
  `funnel/dto/*` — **IDOR / validation-критичний** (requireOwned-first, uniform 404, server-side validation)
- `backend/src/main/java/com/botfunnel/funnel/FunnelExecutionEngine.java`, `StepExecutor.java` —
  **A09 / SSRF / isolation-критичний** (логування без payload, no-fetch imageUrl, bot-pin, status-gate)
- `backend/src/main/java/com/botfunnel/funnel/FunnelTriggerService.java` (+ перевірити, що
  `NoOpFunnelTriggerService.java` видалено) — **isolation-критичний** (`fire()` swallow+log, cross-project scope)
- `backend/src/main/java/com/botfunnel/bot/TelegramSender.java` — `sendPhoto` — **token-leak-критичний**
  (`scrubTokens`; декрипт-токен у URL `/bot{token}/sendPhoto` не в лог)
- `backend/src/main/java/com/botfunnel/subscriber/SubscriberCustomFieldsService.java`,
  `SubscriberCustomFieldsController.java`, `SubscriberService.java`, `SubscriberServiceImpl.java` —
  sole-writer audit, валідація `CustomFieldValueValidator`, lookup-by-chat scope
- `backend/src/main/java/com/botfunnel/jobs/ProjectHardDeleteJob.java` — каскад за top-level `projectId`
- `backend/src/main/resources/application.properties`, `.env.example`, `docs/local-setup.md`,
  `docs/staging-smoke/10-funnels.md` — конфіг / секрети / defaults

**Що читати (frontend, Tasks 9–10):**
- `frontend/stores/funnels.ts`, `frontend/pages/projects/[projectId]/funnels/index.vue`,
  `frontend/pages/projects/[projectId]/funnels/[funnelId].vue`,
  `frontend/components/funnels/*` (CreateFunnelDialog, FunnelStepsList, AddStepDialog, EditStepDialog,
  FunnelTriggerSettings) — XSS на рендері deep-link / значень; чи клієнт не покладається на client-only
  validation як на security-контроль (server-side має дублювати)

**Dependencies:** усі імплементаційні задачі 1–10 мають бути `done`. Фактичний перелік змінених файлів і
відхилень — у `decisions.md` (Task Execution Log); звірити з ним, бо реальні шляхи могли відрізнятися від
spec. Рев'юерів у цієї задачі немає.

**Edge cases для перевірки:**
- Підписник із розміткою у first_name / username / custom-field → інʼєкція в HTML / MarkdownV2-повідомлення
  чи caption.
- `imageUrl` на внутрішню адресу (`http://169.254.169.254/...`, `http://localhost/...`) → backend його
  не фетчить (тільки format-validation схеми).
- `funnelId` чужого проєкту в URL → uniform 404, не 403 / 200.
- Лог-рядок при send-fail / SetCustomField → відсутність відрендереного тіла / значення поля.
- `sendPhoto` на 4xx / 5xx → токен у логнутому URL / повідомленні помилки скрабнутий.

**Implementation hints:** read-only аудит — НЕ правити код. `grep` по логуванню (`log.`, `logger.`, `LOG.`)
у funnel-пакеті — швидкий спосіб знайти потенційні leak-sinks. `grep` по `requireOwned` у
`FunnelController` — підтвердити виклик першим у кожному методі. Звірити фактичні файли з decisions.md
перед читанням (могли бути відхилення).

## Reviewers

Немає — аудит-задача сама є фінальним security-рев'ю; її звіт споживає team lead / Pre-deploy QA (Task 14).

## Post-completion

- [ ] Записать краткий отчёт в decisions.md (Summary: 1-3 предложення — обсяг аудиту, кількість і severity
      знахідок, посилання на `logs/security-audit.md`; без дампів файндингів)
- [ ] Якщо знайдено critical / high — явно підсвітити в decisions.md для team lead (блокери деплою)
- [ ] Оновити статус security-пунктів tech-spec Acceptance Criteria, якщо аудит виявив розходження
