---
status: done                       # planned -> in_progress -> done
depends_on: [1, 2, 3, 4]           # домен/репо (1), renderer (2), sendPhoto (3), custom-fields service + lookup (4)
wave: 2                            # волна параллельного выполнения
skills: [code-writing]             # МАССИВ скиллов для загрузки
verify: [smoke]                    # типы верификации
reviewers: [code-reviewer, security-auditor, test-reviewer]
teammate_name:
---

# Task 6: FunnelExecutionEngine — sweep + step-runner + executors

## Required Skills

Перед выполнением задачи загрузи:
- `/skill:code-writing` — [skills/code-writing/SKILL.md](../../../.claude/skills/code-writing/SKILL.md)

## Description

Серце Фази-1 воронок: JobRunr `@Recurring` sweep-джоб, що періодично (~30с) забирає прострочені
`funnel_executions`, **атомарно** їх застовплює (claim) і просуває кроки воронки через діспетч
`StepType` → executor. Це найскладніша та найризикованіша задача фічі — від її коректності залежить
гарантія **at-most-once per step** на кількох репліках і при JobRunr-retry/рестартах.

Рушій спирається на вже наявні в репо патерни atomic-claim через `MongoTemplate.findAndModify` CAS
(exemplars: `subscriber/jobs/ExportSubscribersJob`, `subscriber/SubscriberServiceImpl`,
`jobs/ProjectHardDeleteJob`) і **не вводить нових інфраструктурних залежностей**. Точка інтеграції з
триггер-шаром (Task 7) — нові виконання, що з'являються в колекції `funnel_executions`; рушій лише
підхоплює прострочені виконання за `nextRunAt`/`status`, він не викликається напряму з webhook-воркера.

Ключові інваріанти (Decisions 2, 4, 7, 9, 14, 16, 17):
- **Один `findAndModify` CAS** на claim виконання (НЕ дві окремі операції select-then-update) — нема вікна
  гонки між «обрав» і «застовпив».
- Enum-статуси в `findAndModify`-критеріях/апдейтах пишемо `.name()`-літералами (byte-match індексів,
  узгоджено з lowercase enum-константами з Task 1, Decision 14).
- Pre-send status-gate (Subscriber.status != active → `cancelled`) + bot-pin check (запінений
  `telegramBotId` уже не CONNECTED → `failed`) ПЕРЕД кожним send.
- Delay — лише через `nextRunAt`, **ніколи** `Thread.sleep`.
- Послідовні НЕ-Delay кроки виконуються підряд у межах одного тіку (цикл), стеля — кінечний snapshot.
- Batch-cap `FUNNEL_SWEEP_BATCH_SIZE` (default 200) на claim за тік, сорт `nextRunAt` asc (найстаріші
  перші — без голодування); упор у cap → WARN.
- Кожен перехід стану логується **іменованими лог-константами** — лише ідентифікатори/коди, **ніколи**
  відрендерений текст/caption/значення кастом-полів (PII; Decision 16). Взірець лог-дисципліни —
  `webhook/ProcessTelegramUpdateJob` (структуровані log-аргументи, без сирого payload).

## What to do

Створити реальний `FunnelExecutionEngine` (sweep + step-runner) та step-executors у
`backend/src/main/java/com/botfunnel/funnel/` (`StepExecutor` + per-type логіка).

1. **Sweep-метод** `@Recurring(id = "funnel-sweep", interval = "${app.funnel.scheduler-interval:PT30S}")`
   `public void sweep()` (без аргументів). На кожному тіку:
   - Дістати до `FUNNEL_SWEEP_BATCH_SIZE` виконань `status ∈ {running, waiting}` із `nextRunAt <= now`,
     сорт `nextRunAt` asc (індекс `(status, nextRunAt)` з Task 1). `now` бери з ін'єктованого
     `java.time.Clock` (`Instant.now(clock)`), щоб IT детермінувалися advance'ом тест-годинника.
   - Якщо кількість зібраних = cap → WARN-лог насичення (іменована константа).
   - Для кожного виконання викликати step-runner.
2. **Step-runner** (на одне виконання):
   - **Atomic claim** — ОДИН `mongoTemplate.findAndModify(query, update, FindAndModifyOptions().returnNew(true),
     FunnelExecution.class)`: критерій `_id == id` AND `status ∈ {running, waiting}` AND `nextRunAt <= now`
     AND `stepRunStatus == pending`; апдейт `stepRunStatus = in_progress` (+ `updatedAt`). null → інша
     репліка/тік виграв → лог claim-loss, skip. Не-null → лог claim-win.
   - **Pre-step status-gate:** резолвити `Subscriber` за `subscriberId`; `status != active` → виконання
     `cancelled` (термінальний апдейт), стоп.
   - **Bot-pin check:** запінений `telegramBotId` уже не `CONNECTED` → виконання `failed`, стоп.
   - **Цикл по кроках** (доки виконання `running` і є наступний крок):
     - Виконати `stepsSnapshot.get(currentStepIndex)` через executor за `StepType`.
     - **Delay-крок:** виставити `nextRunAt = now + duration`, `status = waiting`, `stepRunStatus =
       pending`, **НЕ** інкрементити `currentStepIndex` тут — крок просунеться наступним тіком після
       нового claim. Вийти з циклу (тік для цього виконання завершено).
     - **НЕ-Delay-крок:** після успіху — позначити крок `done`, інкремент `currentStepIndex`, скинути
       `stepRunStatus = pending`, продовжити цикл (наступний НЕ-Delay крок у тому ж тіку).
   - Після останнього кроку → `status = completed`, `completedAt = now`.
3. **Step-executors** (`StepExecutor` діспетч + per-type):
   - **SEND_MESSAGE:** `VariableTemplateRenderer.render(text, parseMode, subscriber)` →
     `TelegramSender.sendText(...)`. Якщо відрендерений текст > 4096 — **обрізати** до 4096 і WARN
     (НЕ фейлити крок). Успішне повернення = надіслано; гілка-помилки — через **catch
     `TelegramSendException`** (див. п.4).
   - **SEND_IMAGE:** caption проганяється через той самий renderer (escape!) і тримиться до 1024;
     `TelegramSender.sendPhoto(...)` (з Task 3). Telegram сам фетчить URL — бекенд URL **не** дереференсить
     (без SSRF). Гілка-помилки так само через catch (див. п.4).
   - **DELAY:** обчислити `Duration` із `delayValue`+`delayUnit` (MIN/HOUR/DAY) → керує step-runner
     (виставлення `nextRunAt`). Без `Thread.sleep`.
   - **ADD_TAG / REMOVE_TAG:** `SubscriberService.addTag/removeTag(...)` через **інтерфейс** `SubscriberService`
     (ідемпотентно). Рантайм-бін за інтерфейсом — `SubscriberServiceImpl`, який лишається єдиним писарем
     `subscriber_events` (sole-writer); рушій залежить від інтерфейсу, не від `Impl`.
   - **SET_CUSTOM_FIELD:** `SubscriberCustomFieldsService.setOne(projectId, subscriberId, key, value)`
     (з Task 4); невалідне значення → виконання `failed` (код `custom_field_type_mismatch`); видалене з
     проєкту визначення поля → крок **тихо пропускається**, виконання триває. Зверни увагу: кожен виклик
     `setOne()` на крок емітить один `recordCustomFieldsSet` audit-event — це коректно для контексту рушія
     (один SET_CUSTOM_FIELD-крок = один audit-event).
4. **Send-fail семантика** — `TelegramSender.sendText/sendPhoto` НЕ повертають outcome-enum: успіх =
   нормальне повернення (`SentMessage`), помилка = **викидають `TelegramSendException`** (rethrow після
   того, як `dispatchSubscriberHook` усередині sender'а вже зробив auto-flip blocked/deleted на терміналі).
   Рушій загортає send у `try/catch (TelegramSendException ex)` і гілкує на `ex.getTerminalReason()`:
   - повернення без винятку → крок успішний, продовжити.
   - `TerminalReason.BLOCKED_BY_USER` (403) або `TerminalReason.CHAT_NOT_FOUND` (400 chat-not-found) →
     виконання `cancelled` (subscriber уже flip'нутий sender'ом — рушій НЕ дублює flip).
   - `TerminalReason.OTHER` (5xx-exhausted, інші 4xx, недоступний/невалідний image URL → 400 без
     chat-not-found, `BotTokenInvalidException` тощо) → виконання `failed`. Без funnel-level ретраїв.
   - **ВАЖЛИВО:** `bot/TelegramSendException.java` має `enum TerminalReason { BLOCKED_BY_USER,
     CHAT_NOT_FOUND, OTHER }` — гілкувати на нього, а не на сирому HTTP-коді. Звернути увагу, що
     `BotTokenInvalidException` — окремий тип (не `TelegramSendException`); його теж ловити й мапити в
     `failed`.
5. **Observability:** оголосити іменовані лог-константи для кожного переходу (claim win/loss, step
   in_progress/done, send-fail з terminal reason, completed/cancelled/failed). Логувати лише
   `executionId/funnelId/subscriberId/stepType/currentStepIndex/terminalReason` — НІКОЛИ відрендерене тіло.

## TDD Anchor

Тести пишемо ДО реалізації. Юніти для executors/renderer-інтеграції + IT на
`AbstractIntegrationTest` (Testcontainers Mongo + `JobRunrInMemoryConfig` + MockWebServer Telegram).
Sweep тестуємо **прямим викликом** `engine.sweep()` (за взірцем `jobs/ProjectHardDeleteJobIT`, який кличе
метод джоба напряму), таймінг рухаємо advance'ом ін'єктованого тест-`Clock`. Mutable `@Primary` тест-Clock
оголошуй як **`static` inner `@TestConfiguration` самого `FunnelExecutionEngineIT`** (не в
`AbstractIntegrationTest` — інакше протече в інші IT-контексти; деталі в Details → Clock).

Integration tests (`backend/src/test/java/com/botfunnel/funnel/FunnelExecutionEngineIT.java`):
- `FunnelExecutionEngineIT::stepsRunEndToEnd` — створене виконання проходить усі кроки → `completed`.
- `FunnelExecutionEngineIT::consecutiveNonDelayStepsRunInSingleTick` — кілька НЕ-Delay кроків доходять до
  кінця за один `sweep()` (а не «крок за тік»).
- `FunnelExecutionEngineIT::delaySetsNextRunAtAndResumesOnNextSweep` — Delay-крок виставляє `nextRunAt`,
  `status=waiting`; після advance годинника наступний `sweep()` просуває.
- `FunnelExecutionEngineIT::reRunningClaimedStepDoesNotDuplicateSend` — повторний `sweep()` по claimed
  (`in_progress`) кроку не дублює send (MockWebServer лічильник запитів = 1).
- `FunnelExecutionEngineIT::crashAfterSendBeforeDoneFlipDoesNotResend` — симуляція краху (виконання лишилось
  `in_progress` після send, без `done`-flip): наступний `sweep()` НЕ пере-надсилає.
- `FunnelExecutionEngineIT::atomicClaimRaceAcrossTwoReplicasRunsStepOnce` — два паралельні `sweep()`
  (за взірцем `subscriber/export/SubscriberExportConcurrencyIT` / `bot/BotConnectRaceIT`) виконують крок
  рівно раз.
- `FunnelExecutionEngineIT::sendImageInvalidUrlFailsExecution` — image URL дає 400 без chat-not-found
  (`TerminalReason.OTHER`) → виконання `failed`.
- `FunnelExecutionEngineIT::sendImageBlockedFlipsAndCancels` — 403/chat-not-found
  (`BLOCKED_BY_USER`/`CHAT_NOT_FOUND`) → cancelled + subscriber flip blocked/deleted (виконує sender;
  відмінно від SendMessage-гілки).
- `FunnelExecutionEngineIT::sendMessageTerminalErrorFailsExecution` — 5xx-exhausted (`TerminalReason.OTHER`)
  → `failed`.
- `FunnelExecutionEngineIT::inactiveSubscriberCancelsBeforeSend` — pre-send status-gate: non-active
  subscriber → `cancelled`, без send (MockWebServer лічильник = 0).
- `FunnelExecutionEngineIT::pinnedBotNotConnectedFailsExecution` — запінений бот не CONNECTED → `failed`,
  без send.
- `FunnelExecutionEngineIT::stateTransitionsEmitNamedLogConstantsWithoutPayload` — send-fail/термінальні
  переходи лог-константами з кодами; відрендерене тіло **відсутнє** в логу (Decision 16).
- `FunnelExecutionEngineIT::subMinuteRecurringJobIsRegistered` — `@Recurring(interval=PT30S)` реально
  реєструється у JobRunr (`storageProvider.getRecurringJobs()` → знайти `id="funnel-sweep"`, перевірити
  `getScheduleExpression()`; взірець — `ProjectHardDeleteJobIT.recurringJob_registeredWithCorrectIdAndCron`).

Unit tests (executors/renderer-інтеграція; `backend/src/test/java/com/botfunnel/funnel/FunnelStepExecutorTest.java`):
- `FunnelStepExecutorTest::sendMessageOver4096TrimsAndContinues` — >4096 після підстановки → trim + WARN,
  крок **успішний** (не fail).
- `FunnelStepExecutorTest::sendImageCaptionEscapedAndTrimmedTo1024` — caption escape + trim 1024.
- `FunnelStepExecutorTest::delayComputesDurationFromUnit` — MIN/HOUR/DAY → коректний `nextRunAt`.
- `FunnelStepExecutorTest::setCustomFieldInvalidValueFailsExecution` — невалідне → `failed`.
- `FunnelStepExecutorTest::setCustomFieldDeletedDefinitionSkips` — видалене поле → skip, виконання триває.

## Acceptance Criteria

- [ ] `@Recurring(interval="${app.funnel.scheduler-interval:PT30S}")` sweep реєструється і виконується;
      sub-minute каденція підтверджена IT.
- [ ] Claim виконання — **рівно один** `findAndModify` (не дві операції); enum-статуси в критеріях/апдейтах
      записані `.name()`-літералами.
- [ ] At-most-once per step: повторний прогін claimed-кроку, крах-після-send-перед-done, друга репліка —
      **не** пере-надсилають (підтверджено IT MockWebServer-лічильником).
- [ ] Послідовні НЕ-Delay кроки доходять до кінця за один тік; Delay через `nextRunAt` (нема `Thread.sleep`
      ніде в рушії).
- [ ] Pre-send status-gate (non-active → `cancelled`) і bot-pin check (не-CONNECTED → `failed`) спрацьовують
      ПЕРЕД send.
- [ ] Send-fail семантика (catch `TelegramSendException`): `BLOCKED_BY_USER`/`CHAT_NOT_FOUND` →
      `cancelled` (flip уже в `TelegramSender`, не дублювати); `OTHER` (+ `BotTokenInvalidException`) →
      `failed`; недоступний image URL → `failed`.
- [ ] SendMessage >4096 → trim+WARN (не fail); SendImage caption escape+trim 1024; SetCustomField
      invalid→failed, deleted-definition→skip; Add/Remove Tag через інтерфейс `SubscriberService`.
- [ ] batch-cap `FUNNEL_SWEEP_BATCH_SIZE` поважається, сорт `nextRunAt` asc, упор у cap → WARN.
- [ ] Кожен перехід стану логується іменованою лог-константою; **жодного** відрендереного payload (PII) в
      логах (Decision 16).
- [ ] `cd backend && ./gradlew test -PrunSlow=true --tests '*FunnelExecution*'` → зелені; нема регресій у
      наявних webhook/subscriber/bot тестах.

## Context Files

- [user-spec.md](../user-spec.md)
- [tech-spec.md](../tech-spec.md)
- [decisions.md](../decisions.md)
- [project.md](../../../.claude/skills/project-knowledge/references/project.md)
- [architecture.md](../../../.claude/skills/project-knowledge/references/architecture.md) — розділ «Funnel Step Execution» + atomic-claim патерн
- [patterns.md](../../../.claude/skills/project-knowledge/references/patterns.md) — findAndModify CAS, sole-writer rule (`SubscriberServiceImpl`), лог-константи
- [testing.md](../../../.claude/skills/project-knowledge/references/testing.md)
- Atomic-claim exemplars: [ExportSubscribersJob.java](../../../backend/src/main/java/com/botfunnel/subscriber/jobs/ExportSubscribersJob.java), [SubscriberServiceImpl.java](../../../backend/src/main/java/com/botfunnel/subscriber/SubscriberServiceImpl.java), [ProjectHardDeleteJob.java](../../../backend/src/main/java/com/botfunnel/jobs/ProjectHardDeleteJob.java), [HardDeleteJob.java](../../../backend/src/main/java/com/botfunnel/jobs/HardDeleteJob.java)
- Worker idioms / лог-константи: [ProcessTelegramUpdateJob.java](../../../backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java)
- Sender contract: [TelegramSender.java](../../../backend/src/main/java/com/botfunnel/bot/TelegramSender.java) (`sendText` повертає [SentMessage.java](../../../backend/src/main/java/com/botfunnel/bot/dto/SentMessage.java) / кидає `TelegramSendException`; новий `sendPhoto` з Task 3; subscriber-hook робить blocked/deleted-flip; token-redaction `scrubTokens`), [TelegramSendException.java](../../../backend/src/main/java/com/botfunnel/bot/TelegramSendException.java) (`enum TerminalReason {BLOCKED_BY_USER, CHAT_NOT_FOUND, OTHER}`), [BotTokenInvalidException.java](../../../backend/src/main/java/com/botfunnel/bot/BotTokenInvalidException.java), [BotStatus.java](../../../backend/src/main/java/com/botfunnel/bot/BotStatus.java) (`CONNECTED`)
- Subscriber mutations/lookup: інжектити інтерфейс [SubscriberService.java](../../../backend/src/main/java/com/botfunnel/subscriber/SubscriberService.java) (`addTag`/`removeTag`, lookup-by-chat); рантайм-бін — [SubscriberServiceImpl.java](../../../backend/src/main/java/com/botfunnel/subscriber/SubscriberServiceImpl.java) (sole-writer `subscriber_events`), [SubscriberRepository.java](../../../backend/src/main/java/com/botfunnel/subscriber/SubscriberRepository.java)
- Custom field (Task 4): [SubscriberCustomFieldsService.java](../../../backend/src/main/java/com/botfunnel/subscriber/SubscriberCustomFieldsService.java) (`setOne`)
- Renderer (Task 2): [VariableTemplateRenderer.java](../../../backend/src/main/java/com/botfunnel/funnel/VariableTemplateRenderer.java)
- Domain (Task 1): [FunnelExecution.java](../../../backend/src/main/java/com/botfunnel/funnel/FunnelExecution.java), [FunnelStep.java](../../../backend/src/main/java/com/botfunnel/funnel/FunnelStep.java), enums, [FunnelExecutionRepository.java](../../../backend/src/main/java/com/botfunnel/funnel/FunnelExecutionRepository.java)
- Bot status: [Bot.java](../../../backend/src/main/java/com/botfunnel/bot/Bot.java), [BotRepository.java](../../../backend/src/main/java/com/botfunnel/bot/BotRepository.java) (`findFirstByTelegramBotIdAndStatus`)
- Prod Clock bean: [ClockConfig.java](../../../backend/src/main/java/com/botfunnel/common/ClockConfig.java) (`@Bean systemClock()`)
- Test infra: [AbstractIntegrationTest.java](../../../backend/src/test/java/com/botfunnel/AbstractIntegrationTest.java), [JobRunrInMemoryConfig.java](../../../backend/src/test/java/com/botfunnel/JobRunrInMemoryConfig.java)
- IT/concurrency exemplars: [ProjectHardDeleteJobIT.java](../../../backend/src/test/java/com/botfunnel/jobs/ProjectHardDeleteJobIT.java) (direct method call), [SubscriberExportConcurrencyIT.java](../../../backend/src/test/java/com/botfunnel/subscriber/export/SubscriberExportConcurrencyIT.java), [BotConnectRaceIT.java](../../../backend/src/test/java/com/botfunnel/bot/BotConnectRaceIT.java)

## Verification Steps

### Automated
- `cd backend && ./gradlew test -PrunSlow=true --tests '*FunnelExecution*'` → all pass
- `cd backend && ./gradlew test --tests '*FunnelStepExecutor*'` → all pass (executor unit tests)
- `cd backend && ./gradlew test` → нема регресій у наявних тестах (webhook/subscriber/bot)

### Smoke
- `cd backend && ./gradlew test -PrunSlow=true --tests '*FunnelExecution*'` → зелені.
  Покриває: проходження кроків end-to-end, послідовні НЕ-Delay за тік, Delay→`nextRunAt`→resume,
  idempotency (re-run claimed / crash-after-send / two-replica race), send-fail матрицю
  (SendImage invalid-URL→failed vs 403/chat-not-found→cancelled+flip; SendMessage 5xx→failed),
  status-gate, bot-pin→failed, observability (лог-константа присутня, payload відсутній),
  sub-minute `@Recurring` реєструється.

## Details

**Files:**
- `backend/src/main/java/com/botfunnel/funnel/FunnelExecutionEngine.java` — НОВИЙ `@Component` зі sweep
  `@Recurring(id="funnel-sweep", interval="${app.funnel.scheduler-interval:PT30S}")` + step-runner.
  Залежності (constructor inject): `MongoTemplate`, `FunnelExecutionRepository`, `SubscriberService`
  (інтерфейс — рантайм-бін `SubscriberServiceImpl`, sole-writer `subscriber_events`),
  `SubscriberCustomFieldsService`, `TelegramSender`, `BotRepository`,
  `VariableTemplateRenderer`, `Clock`, `StepExecutor`-діспетч, та
  `@Value("${app.funnel.sweep-batch-size:200}")` для batch-cap.
- `backend/src/main/java/com/botfunnel/funnel/StepExecutor.java` — діспетч `StepType`→executor + per-type
  логіка (SendMessage/SendImage/Delay/AddTag/RemoveTag/SetCustomField). Один клас із switch або набір
  методів; НЕ полиморфний `_class` (Decision 12 — пласка модель `FunnelStep`).

**Dependencies:**
- Task 1 — `FunnelExecution`/`FunnelStep`/enums (`ExecutionStatus {running,waiting,completed,cancelled,
  failed}`, `StepRunStatus {pending,in_progress,done}`, `StepType`), репо + критичний індекс
  `(status, nextRunAt)` і unique partial `(funnelId, subscriberId)`.
- Task 2 — `VariableTemplateRenderer.render(...)` з escape під HTML/MarkdownV2.
- Task 3 — `TelegramSender.sendPhoto(...)` з тією ж failure-семантикою, що `sendText`: успіх повертає
  `bot/dto/SentMessage`, помилка **кидає** `TelegramSendException` (`getTerminalReason()` →
  `TerminalReason {BLOCKED_BY_USER, CHAT_NOT_FOUND, OTHER}`) або `BotTokenInvalidException`. НЕ
  outcome-enum. blocked/deleted-flip уже всередині `sendPhoto` (як у `sendText`) — рушій не дублює.
- Task 4 — `SubscriberCustomFieldsService.setOne(...)` + lookup-by-chat (рушій для status-gate резолвить
  підписника за `subscriberId`).
- Task 8 (ПІЗНІШЕ) — додасть властивість `app.funnel.sweep-batch-size` у `application.properties`. Тут
  використовуй `@Value` з inline-дефолтом (`:200`), щоб контекст стартував до Task 8.

**Atomic-claim idiom (підтверджено в репо — `subscriber/jobs/ExportSubscribersJob`,
`subscriber/SubscriberServiceImpl`, `jobs/ProjectHardDeleteJob` усі через `MongoTemplate.findAndModify`):**
- Один виклик: `mongoTemplate.findAndModify(Query(Criteria…), Update…,
  new FindAndModifyOptions().returnNew(true), FunnelExecution.class)`.
- Критерій claim: `_id`=id AND `status in [ExecutionStatus.running.name(), ExecutionStatus.waiting.name()]`
  AND `nextRunAt <= now` AND `stepRunStatus = StepRunStatus.pending.name()`.
- Усі enum-значення в Criteria/Update — `.name()` (НЕ передавати enum-об'єкт у критерій, НЕ хардкодити
  рядок-літерал окремо від enum) — Decision 4/14, AC «`.name()`-літерали».

**TelegramSender contract (звірено по коду):** `sendText(botId, chatId, text, parseMode, ownerId)` повертає
`SentMessage` на успіх і **кидає** `TelegramSendException` (з `getTerminalReason()`) або
`BotTokenInvalidException` на терміналі. НЕ повертає outcome-enum. Усередині `sendText` метод
`dispatchSubscriberHook` уже робить `markBlockedByChatId`/`markDeletedByChatId` на 403/chat-not-found ПЕРЕД
rethrow — тож рушій НЕ дублює flip, лише мапить `TerminalReason` → execution-status. token-redaction
(`scrubTokens`) уже всередині sender'а. `sendPhoto` (Task 3) мусить мати ту саму семантику винятків.

**Bot-pin check:** резолвити бота за запіненим `telegramBotId` через
`BotRepository.findFirstByTelegramBotIdAndStatus(telegramBotId, BotStatus.CONNECTED)` — порожній Optional →
бот уже не CONNECTED → виконання `failed` (Decision 7), без re-resolve іншого бота. `BotStatus.CONNECTED` —
підтверджена константа.

**Clock:** інжектити вже наявний `java.time.Clock`-бін — прод-бін `common/ClockConfig.java` (`@Bean
systemClock()` → `Clock.systemUTC()`), точно як це робить `SubscriberServiceImpl` — і брати
`Instant.now(clock)`/`clock.instant()`. НЕ викликати `Instant.now()` напряму. **Увага для
IT-детермінізму:** наразі в тестах НЕ існує mutable/`@Primary` тест-Clock (`ProjectHardDeleteJobIT`
прямо зазначає, що Clock-injection — майбутній рефактор). Тож для Delay→resume IT треба завести власний
`@TestConfiguration` із `@Primary` mutable Clock, що **перебиває** `systemClock()`, і advance'ити його.
**КРИТИЧНО:** цей `@TestConfiguration` мусить бути **`static` inner class самого
`FunnelExecutionEngineIT`** (а НЕ доданий до `AbstractIntegrationTest`) — інакше `@Primary` mutable Clock
протече в контексти інших IT і зламає їхні timestamp-assert'и. Підключати його через
`@Import(FunnelExecutionEngineIT.TestClockConfig.class)` (або вкладений `@TestConfiguration` у тому ж
файлі). Патерн `@Primary`-override бери з `AbstractIntegrationTest.MailpitTestConfig` (але оголошуй
локально, не там). Альтернатива без mutable Clock — сидіти виконання з `nextRunAt` у минулому. Обрати
детермінований підхід, без `Thread.sleep` у тестах.

**Edge cases:**
- Claim повертає null (інший тік/репліка виграв або вже не pending) → лог claim-loss, акуратний skip, без
  винятку.
- Subscriber зник між створенням виконання і sweep → трактувати як non-active → `cancelled` (за tech-spec
  status-gate; узгодити з Task 1 семантикою).
- SetCustomField: видалене з проєкту визначення → **skip кроку**, виконання продовжується (НЕ failed);
  невалідне значення → `failed`.
- SendMessage текст після підстановки > 4096 → **trim + WARN + continue** (це НЕ помилка кроку).
- SendImage недоступний/невалідний URL → Telegram повертає 400 без «chat not found» → terminal-error →
  `failed` (відмінно від 403/chat-not-found → blocked/deleted → `cancelled`).
- Delay-крок: НЕ інкрементити `currentStepIndex` у тому ж тіку; лише `nextRunAt`/`waiting`/`pending`.
  Інкремент станеться після наступного claim.
- Останній крок завершено → `completed` + `completedAt`; жодного «зайвого» тіку.
- batch упор у cap → WARN-лог насичення (Decision 17), без падіння.

**Implementation hints (НЕ псевдокод):**
- `@Recurring` import — `org.jobrunr.jobs.annotations.Recurring` (підтверджено: усі три наявні джоби
  `jobs/HardDeleteJob`, `jobs/ProjectHardDeleteJob`, `subscriber/jobs/ExportCleanupJob` використовують
  саме його, з `cron=`). Тут замість `cron=` використовуй `interval=` (Decision 2). `id` — стабільний
  `"funnel-sweep"`. Можна додати `@Job(name=...)` за взірцем наявних джоб.
- **Реєстрація recurring-джоби в IT** — точний взірець уже є:
  `ProjectHardDeleteJobIT.recurringJob_registeredWithCorrectIdAndCron` autowire'ить `StorageProvider`,
  кличе `storageProvider.getRecurringJobs()`, фільтрує за `getId()` і перевіряє `getScheduleExpression()`.
  Аналогічно: знайти `"funnel-sweep"` і перевірити `getScheduleExpression()` == `PT30S` (interval-форма).
- Лог-константи — `private static final String` (за взірцем лог-кодів `ProcessTelegramUpdateJob`),
  логувати структуровано `log.info("{} executionId={} …", CONST, id, …)`. У повідомленнях/аргументах
  **жодного** `renderedText`/`caption`/`customFieldValue` (Decision 16, security A09/PII) — окремий тест це
  перевіряє.
- Step-runner — цикл `while (status == running && currentStepIndex < snapshot.size())`; Delay розриває цикл,
  виставляючи `waiting`. Стеля циклу природно обмежена кінечним `stepsSnapshot` (Decision 17) — переконайся,
  що Delay завжди виходить із циклу (не нескінченний цикл).
- Усі мутації підписника — **виключно** через інтерфейс `SubscriberService` (рантайм-бін
  `SubscriberServiceImpl` — sole-writer `subscriber_events`; patterns.md) та `SubscriberCustomFieldsService` —
  НЕ писати теги/кастом-поля/події напряму в Mongo.
- IT кличуть `engine.sweep()` напряму (як `ProjectHardDeleteJobIT` кличе метод джоба), не чекають
  планувальник; race-тести — два конкурентні `sweep()` (взірець `SubscriberExportConcurrencyIT`/
  `BotConnectRaceIT`); таймінг — advance тест-Clock.

## Reviewers

- **code-reviewer** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-6/code-reviewer-{round}.json`
- **security-auditor** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-6/security-auditor-{round}.json`
- **test-reviewer** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-6/test-reviewer-{round}.json`

## Post-completion

- [ ] Записать краткий отчёт в decisions.md по шаблону (Summary: 1-3 предложения, ревью со ссылками на JSON, без таблиц файндингов и дампов)
- [ ] Если отклонились от спека — описать отклонение и причину
- [ ] Обновить user-spec/tech-spec если что-то изменилось (напр. фактичні шляхи test-infra `com/botfunnel/AbstractIntegrationTest.java` та `com/botfunnel/JobRunrInMemoryConfig.java` — у корені тест-пакета, не в `support/`)
