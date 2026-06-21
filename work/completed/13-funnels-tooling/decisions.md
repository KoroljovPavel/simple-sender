# Decisions Log: 13-funnels-tooling

Agent reports on completed tasks. Each entry is written by the agent that executed the task.

---

<!-- Entries are added by agents as tasks are completed.

Format is strict — use only these sections, do not add others.
Do not include: file lists, findings tables, JSON reports, step-by-step logs.
Review details — in JSON files via links. QA report — in logs/working/.

## Task N: [title]

**Status:** Done
**Commit:** abc1234
**Agent:** [teammate name or "main agent"]
**Summary:** 1-3 sentences: what was done, key decisions. Not a file list.
**Deviations:** None / Deviated from spec: [reason], did [what].

**Reviews:**

*Round 1:*
- code-reviewer: 2 findings → [logs/working/task-N/code-reviewer-1.json]
- security-auditor: OK → [logs/working/task-N/security-auditor-1.json]

*Round 2 (after fixes):*
- code-reviewer: OK → [logs/working/task-N/code-reviewer-2.json]

**Verification:**
- `npm test` → 42 passed
- Manual check → OK

-->

## Task 1: Duplicate + Stop-all ендпоінти

**Status:** Done
**Commit:** b3135243c8a4e09dcfca812556c679b54f7c394a
**Agent:** funnel-be-mutations
**Summary:** Додано `POST /{funnelId}/duplicate` (verbatim-копія графа через `FunnelStep.copyOf`, копіюються keywords/allowReEnter/description, статус форсимо draft + тригер on_start/"", назва "<name> (копія)" truncate до 128 → 201 + FunnelResponse) і `POST /{funnelId}/executions/stop` (200 + StopAllResponse{cancelled}). Bulk-cancel винесено у спільний приватний хелпер `cancelInFlightExecutions(projectId, funnelId)`, скоупований по обох projectId І funnelId (set status=cancelled + stepRunStatus=done + updatedAt) і перевикористаний у delete() та новому stopAllExecutions(); рефактор delete() підсилив його критерії projectId-скоупом (tenant-isolation hardening), видимий ефект delete незмінний.
**Deviations:** None.

**Reviews:**

- Review проводить лід (review loop поза цією задачею).

**Verification:**
- `./gradlew test --tests 'com.botfunnel.funnel.FunnelControllerIT'` → BUILD SUCCESSFUL (нові duplicate/stop-all + наявні delete сценарії зелені)
- `./gradlew test --tests 'com.botfunnel.funnel.*'` → BUILD SUCCESSFUL (без регресій)
- Smoke: ендпоінти захищені сесією+CSRF; перевірено через MockMvc IT (`@WithMockAppUser` + `.with(csrf())`), що дзеркалить curl-виклики Verify-smoke — duplicate 201/draft/reset-trigger/identical-graph; stop-all 200/count/`cancelled`+`stepRunStatus=done` (явний reload поля).

## Task 2: Test-run + Preview ендпоінти (author-context)

**Status:** Done
**Commit:** 76237e0
**Agent:** funnel-be-author-ctx
**Summary:** Додано `POST /{funnelId}/test-run` (enroll власника напряму через `FunnelExecutionFactory.insertExecution(depth=0)`, обходячи матчинг тригерів; резолв власника через CONNECTED-bot→`ownerChatId`→`findByChat`→ACTIVE-фільтр; нерезолвлений власник → 422 `funnel_owner_not_linked`, ніколи 500; pre-validate через `validateSteps` дає ті ж 422-коди, що й activate; `cancelExistingForPair` ПЕРЕД `insertExecution` — рестарт пари; draft теж тестується) і `POST /{funnelId}/steps/{stepId}/preview` (рендер on-the-fly контенту з тіла запиту через `VariableTemplateRenderer.render` з рантайм-точним екрануванням per parse_mode; незв'язаний бот → стаб-Subscriber Іван/Петренко/ivan + `sampleData=true`; non-message крок → `kind=non_message`; невідомий stepId → 404; `requireFunnel`-404 ПЕРЕВАЖАЄ). Резолв власника винесено у спільний приватний хелпер `resolveOwnerSubscriber` (test-run фейлить, preview fallback-стаб). `validateSteps` піднято `private→package-private` без зміни логіки. У `FunnelService` інжектовано `FunnelExecutionFactory` + `SubscriberService`; новий код-константа `CODE_OWNER_NOT_LINKED`; i18n-ключ `funnel_owner_not_linked` додано в uk/en локалі.
**Deviations:** InOrder cancel→insert доведено через стан репозиторію в IT (`testRunRestartCancelsPrevious`), а не Mockito-spy — наявні `FunnelServiceXxxTest` є full-context IT, не unit-моки (task дозволяє цей шлях). Додано i18n-ключ у фронтові локалі (Decision 5) — поза переліком бекенд-файлів, але потрібен для повноти контракту.

**Reviews:**

- Review проводить лід (review loop поза цією задачею).

**Verification:**
- `./gradlew test --tests 'com.botfunnel.funnel.FunnelControllerIT'` → BUILD SUCCESSFUL (test-run 422-гілки + draft-2xx + restart + anti-IDOR; preview per-parse_mode точні рядки + edge + stub + non-message + 404 + IDOR-precedence)
- `./gradlew test --tests 'com.botfunnel.funnel.FunnelTestRunSendIT' -PrunSlow=true` → BUILD SUCCESSFUL (MockWebServer send-assertion: enroll depth=0 → `engine.sweep()` → 1 `sendMessage`, execution completed)
- `./gradlew test --tests 'com.botfunnel.funnel.*' -PrunSlow=true` → BUILD SUCCESSFUL (без регресій; `validateSteps`-видимість не зламала)
- `./gradlew test --tests 'com.botfunnel.subscriber.*' --tests 'com.botfunnel.bot.*'` → BUILD SUCCESSFUL (новий конструктор `FunnelService` не зламав context-wiring)
- Smoke: обидва ендпоінти захищені сесією+CSRF; live-curl відкладено — екранування доведено байт-в-байт через IT preview-кейси (null/HTML/MarkdownV2 + edge), test-run 422 — через `testRunOwnerChatIdNullReturns422` (HTTP-shape), як дозволяє task (IT — основне джерело впевненості).

## Task 3: Frontend store actions + types

**Status:** Done
**Commit:** d356f55
**Agent:** fe-store
**Summary:** Додано в `stores/funnels.ts` чотири дії автор-тулінгу (`duplicate`, `stopAllExecutions`, `testRun`, `preview`), що дзеркалять контракт існуючих activate/pause: `error.value=false` на вході, catch лише фліпає `error.value=true` і RE-THROW, стор НІКОЛИ не кличе `useApiError`. `duplicate` POST-ить `/duplicate`, кличе `syncRow(created)` для контрактної симетрії (no-op для нового id — список сурфейсить рядок окремо, синтетичний summary НЕ фабрикується) і повертає `FunnelResponse`; `stopAllExecutions`→`{cancelled}`; `testRun`→void (422 `funnel_owner_not_linked` теж re-throw без мапінгу); `preview` шле `PreviewStepRequest` як body і повертає `{rendered, sampleData, kind}` verbatim. У `types/funnel.ts` додано `PreviewStepRequest`/`PreviewStepResponse`/`StopAllResponse` з точними shape-ами tech-spec Data Models (doc-comment + backend DTO + Decision ref). XSRF авто-аттачиться в `useApi` для POST — ручних headers немає.
**Deviations:** None. Проєкт не має npm-скриптів `typecheck`/`lint` і не має `eslint.config.*` — лінт у репо не налаштований; типи перевірено через `tsc --noEmit` (нуль помилок у `stores/funnels.ts`/`types/funnel.ts`; решта TS2307 — pre-existing шум резолву `.vue` SFC без Volar/Nuxt-плагіна, не стосується цих файлів).

**Reviews:**

- Review проводить лід (review loop поза цією задачею).

**Verification:**
- `pnpm vitest run tests/stores/funnels.spec.ts` → 18 passed / 0 failed (10 наявних + 8 нових: happy+error path для всіх чотирьох дій; duplicate no-op syncRow без краху; testRun 422 re-throw без мапінгу коду).
- `tsc --noEmit -p tsconfig.json` → нуль type-помилок у `stores/funnels.ts` та `types/funnel.ts`.

## Task 4: i18n keys + error codes (both locales)

**Status:** Done
**Commit:** 8a5e92ec06d6452f6dbc9c7f5d2c58424440b831
**Agent:** fe-i18n
**Summary:** Додано Phase-4 автор-тулінг i18n-ключі в обидві локалі (`uk.json`/`en.json`) дзеркально. Під `funnels.editor` — пласкі `duplicate`/`duplicateResult`/`testForMe`/`testForMeResult`/`preview`/`previewPlaceholder`/`previewSampleData` плюс ВКЛАДЕНИЙ об'єкт `stopAll` (`button`/`title`/`message`/`confirm`/`cancel`/`result` з `{count}`-плейсхолдером), за канонічним контрактом Task 5 — без плаского `stopAllConfirm`. Під `errors.funnels` додано два бекенд-коди `funnel_invalid_trigger_type`/`funnel_invalid_keywords` (Decision 10); `funnel_owner_not_linked` уже існував з Task 8 — НЕ дублювався. Parity-gate зелений (key-set байт-ідентичний у двох файлах).
**Deviations:** None. `testForMe` UK-значення = "Тест для мене" (канон лишав фінальне формулювання на виконавця).

**Reviews:**

- Review проводить лід (review loop поза цією задачею).

**Verification:**
- `node frontend/scripts/check-locales.mjs` (Node v24.15.0) → exit 0 — повна паритетність, нуль orphan-ключів.
- `JSON.parse` обох файлів → OK (валідний JSON, без дублікатів ключів); `funnels.editor` має всі 12 ключів + `stopAll`{button,title,message,confirm,cancel,result} у обох локалях.

## Task 5: List + header action buttons (no preview panel)

**Status:** Done
**Commit:** e6f765d
**Agent:** fe-actions
**Summary:** Підключено три інструменти автора (Дублювати, Зупинити все, Test for me) до UI воронок поверх store-дій Task 3 та i18n-ключів Task 4 — чистий UI-wiring без нової бізнес-логіки. У `index.vue` рядок списку отримав кнопки Дублювати (`funnel-duplicate-{id}`) + Зупинити все (`funnel-stop-all-{id}`); Зупинити все відкриває ОКРЕМИЙ `Dialog`-confirm з власними `stopTarget`/`stopping`/`stopError` refs (не переплетений з delete-діалогом), підтвердження → `stopAllExecutions` + success-toast з `{count}`, помилка → inline `stopError` у тілі діалогу. У хедері редактора `[funnelId].vue` (біля Activate/Pause) додано Дублювати/Зупинити все/Test for me; Test for me доступний і активний на draft (не гейтиться статусом). Мапінг помилок за конвенцією: 422 з мапованим бізнес-кодом → inline (`funnel_owner_not_linked` → `<p data-test="funnel-test-run-error">` біля кнопки через перевикористання `errorCode`+`te`+`errors.funnels.${code}`); мережева/непередбачена → `toast.error`. Прев'ю-перемикач/панель свідомо НЕ додані (Task 6). Канонічні ключі вжито точно (вкладений `stopAll.*`, без плаского `stopAllConfirm`).
**Deviations:** None. Жодних нових i18n-ключів не додано — усі канонічні ключі вже були в Task 4 (parity-gate зелений). Тести-діалоги рендеряться через per-mount teleport-stub (`global.stubs.teleport`) — той самий патерн, що в `settings/bot.spec.ts` — щоб DialogPortal-контент був видимий через `wrapper.find()` і чисто демонтувався (інакше реальний teleport у body гонить з happy-dom на unmount → nextSibling-of-null). У репо немає npm-скриптів `lint`/`typecheck` і немає `eslint.config.*` — лінт не запускався (відсутній); типи де-факто перевірені vitest-транспіляцією SFC.

**Reviews:**

- Review проводить лід (review loop поза цією задачею).

**Verification:**
- `vitest run tests/pages/funnel-editor.spec.ts tests/pages/funnels-list.spec.ts tests/stores/funnels.spec.ts` (Node v24.15.0) → 54 passed / 0 failed / 0 errors (8 нових editor-кейсів: рендер 3 кнопок без Preview; Дублювати→store+toast; Test for me unlinked→inline-hint не toast; Test for me network→toast без inline; Test for me success-toast; Test for me активний на draft; Stop-all confirm→count-toast; Stop-all cancel→no-op. 3 нових list-кейси: рядок має Дублювати+Зупинити все без Test for me; Дублювати→копія у списку+toast; Stop-all confirm/cancel).
- `vitest run` (увесь фронт) → 432 passed / 0 failed / 0 errors (без регресій).
- `node frontend/scripts/check-locales.mjs` → exit 0 (parity; локалі НЕ чіпались — Task 4 owns).
- Verify-user (manual, відкладено користувачу): список+хедер показують Дублювати/Зупинити все (+Test for me лише в хедері); Дублювати створює draft-копію, що з'являється у списку; Зупинити все показує діалог підтвердження; Test for me на воронці з незв'язаним ботом (бот без ownerChatId) показує inline-підказку «напишіть /start», а не toast.

## Task 6: Message preview panel (greenfield)

**Status:** Done
**Commit:** 1aa1bc2
**Agent:** fe-preview
**Summary:** Створено новий компонент `components/funnels/FunnelMessagePreview.vue` — панель прев'ю повідомлення в редакторі воронок, і вмонтовано перемикач Прев'ю в хедер `[funnelId].vue`. Для message-кроків (SEND_MESSAGE/SEND_IMAGE/MENU) компонент кличе store-дію `preview` (Task 3) з `{stepType,text,parseMode}` (для SEND_IMAGE контент = `caption`) і виводить `response.rendered` ВИКЛЮЧНО як текст через `{{ }}`-інтерполяцію — НІКОЛИ `v-html`/`innerHTML` (stored-XSS guard, OWASP A03, Decision 9; переноси рядків через CSS `whitespace-pre-wrap`). Не-message крок → нейтральний плейсхолдер `funnels.editor.previewPlaceholder`, бекенд не кличеться. `sampleData===true` → індикатор `funnels.editor.previewSampleData`. Реактивне прев'ю дебаунсоване (~600ms, clear у `onBeforeUnmount`) за зразком `scheduleTriggerPersist`; перший рендер на mount — без дебаунсу. Помилка (404 невідомий крок / мережа / 5xx) ловиться в setup і мапиться через `useApiError(err, 'funnels.preview')` → нейтральне in-panel повідомлення (fallback `errors.generic`), не throw/blank/500. Хедерний перемикач `data-test="funnel-preview-toggle"` стартує OFF; при ON монтує панель праворуч у lg-grid (desktop-first ≥1024px, нижче — стек). Крок у фокусі = редагований (edit-діалог) або перший message-крок, інакше нейтральний empty-стан.
**Deviations:** Жодних нових i18n-ключів не додано — усі три (`preview`/`previewPlaceholder`/`previewSampleData`) уже були в Task 4 (parity-gate зелений, локалі не чіпались). Контекст-ключ помилки `funnels.preview` не має спеціальних `errors.funnels.preview.*` ключів → `useApiError` коректно падає на існуючий `errors.generic` (нейтральний рядок), нові ключі не вводив. Оновив 1 наявний Task-5 тест у `tests/pages/funnel-editor.spec.ts`, який ЯВНО стверджував відсутність `funnel-preview-toggle` (правомірно для Task 5) — тепер стверджує його наявність + стартовий OFF + монтування панелі по кліку; додав `preview: vi.fn()` у storeMock. У репо немає npm-скриптів `lint`/`typecheck` і немає `eslint.config.*` — лінт не запускався; типи де-факто перевірені vitest-транспіляцією SFC.

**Reviews:**

- Review проводить лід (review loop поза цією задачею).

**Verification:**
- `vitest run tests/components/funnels/FunnelMessagePreview.spec.ts` (Node v22.19.0) → 7 passed / 0 failed. Кейси: message-крок рендерить бекенд-результат + кличе `preview('f1','s1',{...})`; non-message → плейсхолдер (без виклику бекенду); sampleData-індикатор коли flagged + відсутній коли false; no-v-html (markup-payload `<img src=x onerror=...><b>x</b>` присутній ЛІТЕРАЛЬНО в `.text()`, `find('img')/find('b')` = false, `querySelector('img')` = null, + статичний guard на джерело: жодного `v-html=`/`.innerHTML=`); error-стан (404) → нейтральне повідомлення, rendered не показується; no-step → empty-стан.
- `vitest run` (увесь фронт) → 440 passed / 0 failed / 0 errors (без регресій; +8 нових кейсів проти 432 у Task 5).
- `node frontend/scripts/check-locales.mjs` (Node v22.19.0) → exit 0 (parity; локалі НЕ чіпались).
- Verify-user (manual, відкладено користувачу): локальний фронт → редактор воронки → перемкнути Прев'ю; message-крок (SEND_MESSAGE зі змінною `Привіт, {user.first_name}!`) → панель показує рендер зі змінними (підставлене ім'я або семпл-значення + індикатор семпл-даних, якщо бот не зв'язаний); Delay/Add-Tag/Emit-Event крок → плейсхолдер «Цей крок не надсилає повідомлення».

## Task 8: Security Audit

**Status:** Done
**Agent:** audit-security
**Summary:** Повнофічевий OWASP Top 10 аудит фінального стану фічі (бекенд funnel-модуль + фронт store/превʼю/типи + i18n). Перевірено всі 6 векторів — уразливостей НЕ знайдено (verdict: clean). Підтверджено: усі 4 нові POST проходять `requireFunnel` ПЕРШИМ (єдиний 404, ownerId лише з сесії, 404 превалює над 422/stepId-404 у preview/test-run); test-run/stop-all скоупляться `projectId`+`funnelId` (cross-tenant дірок немає); екранування превʼю — на бекенді через `VariableTemplateRenderer`, фронт виводить лише текстом (`{{ }}`), жодного `v-html`/`innerHTML`; DTO (`PreviewStepResponse`/`StopAllResponse`/`FunnelResponse`) не ликають ownerChatId/токен/Telegram-identity; нові POST лишаються під CSRF (scoped-disable тільки на webhook + stateless key-chain). Race 04b (`ownerChatId` first-/start-wins) зафіксовано як known/accepted (поза scope). Fixer-задача НЕ потрібна.
**Deviations:** None.

## Task 7: Code Audit

**Status:** Done
**Agent:** audit-code
**Summary:** Холістичний кросс-компонентний аудит коду всієї фічі (задачі 1-6, фінальний стан). Усі п'ять гарячих точок чисті (verdict: clean, 0 findings): резолв власника-підписника реально спільний (`resolveOwnerSubscriber`, без копіпасти між test-run/preview); stop-all дотримує claim-CAS (`cancelInFlightExecutions` спільний для delete+stopAll, ставить ОБА поля `status=cancelled`+`stepRunStatus=done`, скоуп projectId+funnelId — байт-дзеркало `cancelExistingForPair`); узгодженість із funnel-патернами (requireFunnel first на всіх 4 ендпоінтах, bulk через MongoTemplate без нового repo-методу, DTO у funnel/dto, error-код-константа); shared-ресурси без дублювання (FunnelExecutionFactory @Component-singleton інжектиться, VariableTemplateRenderer static — той самий рендер, що й рушій, нуль дрейфу екранування на фронт/у новий код); фронт-консистентність (catch-flip-RETHROW, нуль useApiError у сторі, нуль v-html/innerHTML, типи=DTO byte-for-byte, канонічні вкладені i18n-ключі, parity-gate зелений). Дрейфу/дублювання/мертвого коду між швами задач не виявлено. Лід НЕ спавнить фіксера.
**Deviations:** None.

## Task 9: Test Audit

**Status:** Done
**Agent:** audit-test
**Summary:** Холістичний кросс-компонентний аудит ЯКОСТІ тестів усієї фічі (задачі 1-6, фінальний стан; duplicate / stop-all / test-run / preview). Verdict: clean. Усі шість вимірів зелені з осмисленими (не tautological) assertions: duplicate — копія draft + reset-тригер + verbatim-граф (id/next/timeoutTargetStepId/Button.targetStepId) + границя імені ОБОМА боками (==128 без обрізки, ==129 truncate-still-201); stop-all — claim-CAS ДВА поля (status=cancelled І stepRunStatus=done через прямий mongo-reload; seed=pending робить done-флип значущим), cross-funnel+cross-project scoping, термінальні не тронуті, count=modifiedCount; test-run — усі 3 гілки 422 funnel_owner_not_linked як окремі шляхи + HTTP-shape (code-body, не 500) + draft-2xx + restart cancel-before-insert + MockWebServer send-assertion (інспектує path+chat_id+rendered text); preview — точні рядки per parse_mode (null/HTML/MarkdownV2 повний набір+backslash) + edge ({{}}/unclosed/Double-no-.0) + MENU/SEND_IMAGE + stub-no-NPE + non-message + 404; anti-IDOR — requireFunnel-404 на всіх 4 + precedence над stepId-404; frontend — flip-RETHROW + ніколи useApiError, confirm-діалог, inline-vs-toast 422 обома боками, no-v-html (літерал-текст + відсутність DOM-вузла + статичний source-guard). Пірамида збалансована (escaping/copyOf у unit, HTTP/persistence/CAS/cross-tenant/send у IT, UI у vitest); відсутність E2E обґрунтована (architecture.md). Дві low-нотатки (send-IT живе у FunnelTestRunSendIT, не FunnelExecutionEngineIT; InOrder доведено через стан репозиторію, не Mockito-spy) — не пробіли покриття. Fixer НЕ потрібен.
**Deviations:** None.

**Verification:**
- `./gradlew test -PrunSlow=true --tests 'com.botfunnel.funnel.*'` → BUILD SUCCESSFUL (35s, incl. slow MockWebServer send-IT).
- `vitest run` (4 funnel-спеки, Node v24.15.0) → PASS 62 / FAIL 0. Аудит на верифіковано-зеленій базі.

## Task 10: Pre-deploy QA

**Status:** Done
**Agent:** qa-runner
**Summary:** Приёмочне тестування фічі перед мерджем у `main`. Прогнано всі три сьюти: frontend `pnpm vitest run` → 51 файл / 440 passed / 0 failed; i18n parity-gate `check-locales.mjs` → exit 0; backend `./gradlew test -PrunSlow=true` → 1111 тестів, 1 fail, 2 skipped. Єдиний fail — `TelegramWebhookP99IT.p99Latency` (флакі P99-латентність-проба, got 130ms / 431ms isolated, поза scope фічі — належить попередньому webhook-епіку, build.gradle сам документує її як флакі), НЕ funnel-дефект і НЕ блокер. Slow-tag concern закрито: `-PrunSlow=true` реально виконав send-assertion `FunnelTestRunSendIT.testRunLinkedBotSends` (PASS, `skipped=0`); жоден funnel-IT не пропущений мовчки (усі `com.botfunnel.funnel.*` → `skipped=0`). 2 skipped — pre-existing `TelegramSenderIT` (потребують live-credentials). Зведено 29 verified / 1 deferred-to-user / 0 not-covered по обох спеках. Verdict: READY-TO-MERGE (feature-scoped).
**Deviations:** None. QA-задача — без продакшн-коду.

**Deferred to user:** Реальна доставка «Test for me» в Telegram власника (бек не спостерігає live-доставку без середовища) — не вважається провалом.

**Verification:**
- Full report: [logs/qa/pre-deploy-qa-report.md]

## Ad-hoc Phase-4 fix: Duplicate UX (два дефекти ручної верифікації)

**Status:** Done
**Agent:** fe-dup-ux
**Summary:** Виправлено два UX-дефекти дії Duplicate, знайдені користувачем у ручній верифікації. (1) Редактор: `duplicate()` ігнорував повернутий `FunnelResponse` — користувач лишався на СТАРІЙ воронці; тепер захоплюємо копію і навігуємо в редактор НОВОЇ воронки через наявний locale-aware патерн (`navigateTo(localePath(.../funnels/${created.id}))`), toast і error-гілка без навігації — без змін. (2) Список: `duplicateFunnel()` покладався на `store.duplicate→syncRow`, який є no-op для свіжого id, тож новий draft не з'являвся; тепер після успіху рефетчимо список з поточним фільтром (`funnelsStore.fetch(statusFilter.value)`) — новий draft видно, фільтрація коректна. Стор НЕ чіпали (контракт catch-flip-RETHROW / без useApiError / без фабрикації FunnelSummaryResponse збережено).
**Deviations:** None. Рев'ю виконує лід (фіксер не спавнив рев'юерів).

**Verification:**
- `pnpm vitest run tests/pages/funnel-editor.spec.ts tests/pages/funnels-list.spec.ts tests/stores/funnels.spec.ts` (Node v24.15.0) → 3 файли / 56 passed / 0 failed (редактор-спек +1 кейс: failure → toast + БЕЗ навігації; список-спек переписано на assert рефетчу `fetch('all')`, а не no-op push).
- `pnpm vitest run` (увесь фронт) → 51 файл / 441 passed / 0 failed (без регресій).
- `node frontend/scripts/check-locales.mjs` → exit 0 (parity; локалі НЕ чіпались).

## Ad-hoc Phase-4 enhancement: Preview — click any step + step heading

**Status:** Done
**Agent:** fe-preview-select
**Summary:** Закрито два UX-розриви панелі прев'ю (Task 6). (1) Клік по будь-якому рядку кроку тепер драйвить прев'ю саме на цей крок (message → рендер; non-message → наявний плейсхолдер «не надсилає повідомлення»). (2) Панель показує заголовок «Крок {N} · {тип}» (N — 1-based) для будь-якого сфокусованого кроку. Дефолт ЗБЕРЕЖЕНО: без кліку і без відкритого діалогу редагування прев'ю показує ПЕРШИЙ message-крок. Пріоритет вибору: редагований крок (відкритий edit-діалог) > клікнутий крок > перший message-крок.
- `FunnelStepsList.vue`: додано emit `select(index)` (клік по title/summary-блоку рядка — окрема `<button data-test="funnel-step-select-{index}">`; кнопки дій лишаються сусідніми елементами, не перехоплюють вибір) + опційний prop `selectedIndex` із підсвіткою рядка (`ring-2 ring-blue-400 border-blue-400`).
- `[funnelId].vue`: `previewSelectedIndex = ref(-1)` + `onSelectStep`; `previewIndex` computed реалізує пріоритет (editIndex → previewSelectedIndex → перший message-крок); `previewStep`/`previewStepNumber` похідні від нього; `:selected-index` підсвічує поточно-прев'юваний рядок лише коли панель відкрита; дефолтний first-message fallback збережено. Edit/move/delete/auto-save flow не змінено.
- `FunnelMessagePreview.vue`: опційний prop `stepNumber`; computed `stepHeading` = `funnels.editor.previewStepHeading` із {number} і вже-локалізованим {type} (`funnels.steps.type.*` — наявні ключі, не винаходимо нові); вивід ТІЛЬКИ як текст `{{ }}` (інваріант no-v-html збережено, плюс наявний static-source guard).
- i18n: новий ключ `funnels.editor.previewStepHeading` додано в uk.json («Крок {number} · {type}») і en.json («Step {number} · {type}») — parity збережено.
**Deviations:** Кейс «клікнутий message-крок» у funnel-editor.spec ассертить спостережуване (заголовок «Крок 3», ring-2 на рядку, наявність `funnel-preview-rendered`), а не дебаунснутий повторний store.preview-виклик (600ms debounce у панелі робить timing-ассерт флакі з реальними таймерами). Render-шлях самого виклику покрито у FunnelMessagePreview.spec. Рев'ю виконує лід (енхансер не спавнив рев'юерів).

**Verification:**
- `pnpm vitest run tests/components/funnels/FunnelMessagePreview.spec.ts tests/pages/funnel-editor.spec.ts tests/pages/funnels-list.spec.ts` (Node v24.15.0) → 52 passed / 0 failed.
- `pnpm vitest run` (увесь фронт) → 448 passed / 0 failed (без регресій).
- `node frontend/scripts/check-locales.mjs` → exit 0 (parity).

## Ad-hoc Phase-4 enhancement: Preview — render image for SEND_IMAGE steps

**Status:** Done
**Agent:** fe-preview-image
**Summary:** Панель прев'ю (Task 6) для кроку SEND_IMAGE тепер показує зображення НАД відрендереним caption (раніше — лише caption). Frontend-only, ТІЛЬКИ кейс SEND_IMAGE; SEND_MESSAGE / MENU / non-message — без змін. Зображення береться напряму зі `step.imageUrl` (валідований http(s) на збереженні) — жодного нового мережевого виклику, окрім завантаження URL браузером. Backend preview-endpoint не чіпали.
- `FunnelMessagePreview.vue`: computed `imageUrl` (тільки SEND_IMAGE + non-blank trim) → `<img :src>` (НЕ v-html — інваріант no-HTML-sink/stored-XSS збережено; `<img src>` не виконує JS) з `referrerpolicy="no-referrer"`, `alt` через i18n, візуальне обмеження (`max-h-64 w-full object-contain rounded-md border`). Порожній/whitespace URL АБО `@error` (reactive `imageLoadFailed`, скидається при зміні URL) → нейтральний текстовий плейсхолдер `funnel-preview-image-unavailable` замість broken-image. Caption (`rendered.rendered`) лишається ТЕКСТОМ `{{ }}` нижче зображення; для SEND_IMAGE з порожнім caption div ховається (image-only) — інші типи рендерять div як раніше.
- i18n: нові ключі `funnels.editor.previewImageUnavailable` (uk «Зображення недоступне» / en «Image unavailable») і `funnels.editor.previewImageAlt` (uk «Зображення кроку» / en «Step image») — parity збережено.
**Deviations:** Додано допоміжний alt-ключ `previewImageAlt` (поза мінімумом ТЗ) для осмисленого alt замість порожнього — parity дотримано. Рев'ю виконує лід (енхансер не спавнив рев'юерів).

**Verification:**
- `pnpm vitest run tests/components/funnels/FunnelMessagePreview.spec.ts` (Node v24.15.0) → 15 passed / 0 failed (+5 кейсів: valid image над caption; image-only при порожньому caption; blank URL → плейсхолдер + caption; @error → плейсхолдер; SEND_MESSAGE/MENU/non-message → без <img>).
- `pnpm vitest run` (увесь фронт) → 453 passed / 0 failed (без регресій).
- `node frontend/scripts/check-locales.mjs` → exit 0 (parity).
