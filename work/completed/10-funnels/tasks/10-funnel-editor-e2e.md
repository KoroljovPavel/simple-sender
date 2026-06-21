---
status: done                       # planned -> in_progress -> done
depends_on: [5, 9]                 # Task 5 = API contract; Task 9 = store + index page + i18n base
wave: 4                            # frontend wave; MUST run sequentially AFTER Task 9 (shared locale files)
skills: [code-writing]             # МАССИВ скиллов для загрузки
verify: [user]                     # tech-spec Task 10 has Verify-user (no Verify-smoke)
reviewers: [code-reviewer, security-auditor, test-reviewer]
teammate_name:

---

# Task 10: Редактор кроків + тригер + активація (+ E2E)

## Required Skills

Перед выполнением задачи загрузи:
- `/skill:code-writing` — [skills/code-writing/SKILL.md](../../../.claude/skills/code-writing/SKILL.md)

## Description

Друга (фінальна) фронтенд-задача епіку 10-funnels. Будуємо повноцінний **редактор воронки** на сторінці
`funnels/[funnelId].vue` — серцевину user-facing-частини фічі. Тут власник проєкту перетворює порожню
`draft`-воронку (створену в Task 9) на готову до активації лінійну послідовність кроків.

Редактор складається з:
- **вертикального списку кроків** (`FunnelStepsList.vue`) з кнопками переміщення ↑/↓, редагування, видалення
  кожного кроку;
- **діалогів додавання/редагування кроку** (`AddStepDialog.vue` / `EditStepDialog.vue`) — picker типу кроку
  (Send Message / Send Image / Delay / Add Tag / Remove Tag / Set Custom Field) + per-type форма з полями
  й клієнтською валідацією, що дзеркалить серверні правила (порожній text, Delay <1хв, imageUrl http/https,
  tagSlug-regex);
- **налаштувань тригера** (`FunnelTriggerSettings.vue`) — `triggerType=on_start` (Phase 1 фіксовано `/start`)
  + опц. `trigger_value`, з показом готового deep-link `t.me/<botUsername>?start=<triggerValue>` і кнопкою
  «Copy»;
- **кнопки Activate / Pause** з відображенням 422-помилок валідації (порожні кроки, конфлікт тригера,
  невалідні кроки) як inline-помилки (не глобальний toast «щось пішло не так»).

Усі дані ходять через `useFunnelsStore` (Task 9) поверх `useApi`/`useApiError`. Контракт редагування кроків —
**PATCH повним масивом `steps`** (позиція в масиві = порядок; сервер перезаписує `order`), тож переміщення
↑/↓ це просто реорядкування масиву й повторний save. Deep-link читається з поля `deepLink` у `FunnelResponse`
(присутнє для active-воронки в single-GET — Task 5), тож після перезавантаження сторінки лінк лишається
видимим.

Задача також **завершує тестове покриття фронтенду фічі**: Vitest-специ на нові компоненти + i18n parity та
Playwright E2E golden-path + дешевий page-test на 422-гілку активації.

**КРИТИЧНО про i18n:** ключі `funnels.*` / `errors.funnels.*` додаються в `uk.json`/`en.json`
**послідовно ПІСЛЯ Task 9** (а не паралельно) — Task 9 створює базовий блок, Task 10 **дописує** ключі
редактора кроків/тригера/активації в той самий блок. Це уникає конфлікту merge у locale-файлах і тримає
i18n-parity-гейт зеленим. **Симетричний uk/en-паритет funnels-ключів enforce-иться
`frontend/scripts/check-locales.mjs`** (`cd frontend && node scripts/check-locales.mjs`), а НЕ
`tests/i18n/required-keys.spec.ts` (той перевіряє лише захардкоджений список старіших ключів).

## What to do

1. **`funnels/[funnelId].vue`** — сторінка редактора: на `onMounted` (тільки `import.meta.client`) тягне
   воронку через store (single-GET з `steps` + `deepLink`); рендерить заголовок (назва + статус-бейдж),
   `FunnelStepsList`, `FunnelTriggerSettings`, кнопку Activate/Pause (залежно від статусу), кнопку «+ Add step».
   Тримає локальний стан `steps` для оптимістичного реордеру; save через store-update (PATCH повним масивом).
   404/чужа воронка → graceful (редирект на список через `useLocalePath`, за взірцем custom-fields/index.vue).
2. **`FunnelStepsList.vue`** — вертикальний список кроків: кожен рядок показує тип кроку + коротке summary
   (текст-прев'ю / «Delay 5 хв» / «Add tag: vip» тощо), кнопки ↑ (disabled на першому), ↓ (disabled на
   останньому), Edit, Delete (з confirm). Емітить події реордеру/редагування/видалення нагору. Empty-state
   із CTA «Add step».
3. **`AddStepDialog.vue`** — діалог додавання: спершу picker `StepType` (native `<select>` за взірцем
   AddCustomFieldDialog для testability); після вибору — відповідна per-type форма з vee-validate+zod
   клієнт-валідацією обов'язкових полів. Емітить новий крок (додається в кінець масиву).
4. **`EditStepDialog.vue`** — діалог редагування наявного кроку: та сама per-type форма, попередньо заповнена;
   зберігає зміни назад у крок. Доцільно винести спільну форму між Add/Edit, але обидва файли мають існувати
   як у tech-spec.
5. **`FunnelTriggerSettings.vue`** — форма тригера: `triggerType` (фіксовано `/start`), `trigger_value`
   (опц., клієнт-валідація патерну `^[A-Za-z0-9_-]{0,64}$`). Показує deep-link `t.me/<bot>?start=<value>`
   (бере `deepLink` зі store для active або складає прев'ю для draft) + кнопку «Copy» (Clipboard API) із
   фідбеком «Copied». Порожній `trigger_value` → голий `/start` (лінк без `?start=`).
6. **Activate / Pause** — кнопка викликає store-екшн (`POST .../activate` чи `.../pause`); при 422 показує
   **inline** код помилки людиночитним повідомленням (`errors.funnels.*` — порожні кроки / конфлікт тригера /
   невалідний крок / Delay <1хв) через `useApiError`, не ковтаючи його в загальний toast.
7. **i18n** — дописати ключі `funnels.editor.*`, `funnels.steps.*`, `funnels.trigger.*`, `funnels.activate.*`
   + `errors.funnels.*` у `uk.json` І `en.json` (повний паритет ключів). Додавати ПІСЛЯ Task 9.
8. **Vitest-специ** (`frontend/tests/components/`) — на `AddStepDialog`/`EditStepDialog`/`FunnelStepsList`
   та `FunnelTriggerSettings` (рендер deep-link + Copy). Мок `useApi` через
   `mockNuxtImport('useApi', () => () => apiMock)` (`@nuxt/test-utils/runtime`), `navigator.clipboard` через
   `vi.stubGlobal`. Переконатися, що uk/en funnels-паритет тримається `node scripts/check-locales.mjs`.
9. **Playwright E2E** (`frontend/e2e/funnels.spec.ts`) — golden-path + page-test на 422-гілку (див. TDD Anchor).

## TDD Anchor

Пишемо тести ДО реалізації → запускаємо → падають → код → проходять.

**Vitest (component, `frontend/tests/components/`; `// @vitest-environment nuxt` + `mountSuspended`).**
Мок composables робимо `mockNuxtImport('useApi', () => () => apiMock)` з `@nuxt/test-utils/runtime`
(саме цей патерн у взірці `AddCustomFieldDialog.spec.ts` — НЕ `vi.stubGlobal('useApi', ...)`).
УВАГА на відмінність: `navigator.clipboard` — це браузерний global, а НЕ Nuxt-composable, тож його мокаємо
через `vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn() } })` (не `mockNuxtImport`).
- `frontend/tests/components/AddStepDialog.spec.ts::renders type picker and per-type form` — вибір `StepType`
  показує відповідні поля (Send Message → text+parseMode; Delay → value+unit; Add Tag → tagSlug).
- `frontend/tests/components/AddStepDialog.spec.ts::blocks emit on empty required field` — порожній text /
  Delay <1хв / порожній tagSlug → клієнтська помилка, крок НЕ емітиться (за взірцем
  AddCustomFieldDialog.spec.ts `blocks submit`).
- `frontend/tests/components/EditStepDialog.spec.ts::prefills existing step and emits updated step` — форма
  заповнена значеннями кроку; save емітить оновлений крок.
- `frontend/tests/components/FunnelStepsList.spec.ts::move up/down disabled at bounds and reorders` — ↑
  disabled на першому, ↓ disabled на останньому; клік емітить реордер.
- `frontend/tests/components/FunnelTriggerSettings.spec.ts::renders deep link with trigger value` — рендерить
  `t.me/<bot>?start=ref_x` для заданого `triggerValue`.
- `frontend/tests/components/FunnelTriggerSettings.spec.ts::renders bare /start link for empty trigger value` —
  порожній `triggerValue` → deep-link БЕЗ `?start=` (голий `t.me/<bot>?start=` згортається до `/start`-варіанту
  без query-параметра).
- `frontend/tests/components/FunnelTriggerSettings.spec.ts::copy writes full link and shows Copied feedback` —
  кнопка Copy кличе `navigator.clipboard.writeText` (замоканий через `vi.stubGlobal`) із повним лінком ТА
  перемикає UI у стан «Copied» (тимчасовий фідбек видно в DOM).
- i18n parity (symmetric uk/en для funnels): забезпечує `frontend/scripts/check-locales.mjs`
  (`cd frontend && node scripts/check-locales.mjs`) — саме він валідує симетричність ключів uk/en для нових
  `funnels.*`/`errors.funnels.*`. Наявний `frontend/tests/i18n/required-keys.spec.ts` перевіряє лише
  захардкоджений список СТАРИХ ключів і НЕ enforce-ить funnels-паритет — не покладатися на нього тут.

**Playwright E2E (`frontend/e2e/funnels.spec.ts`; РЕАЛЬНИЙ бекенд + graceful `test.skip`, ТОЧНО за взірцем
`e2e/subscribers.spec.ts` — НЕ `page.route()`/`stubAuth`/`makeX()`-фабрики, цих патернів у взірці немає):**

Взірець `e2e/subscribers.spec.ts` ганяє golden-path проти **запущеного стека**: у `test.beforeAll` робить
health-probe на `${E2E_BACKEND_URL}/health` (не `/actuator/health` — Actuator відсутній), бере проєкт/креди
з env-bootstrap (`E2E_*`), а коли бекенд лежить АБО bootstrap відсутній — викликає `test.skip(...)` з ясним
повідомленням (НЕ фейлить suite, Task 11 AC). Дзеркалимо саме це:

- `funnels.spec.ts::funnels_goldenPath_buildAndActivate` — golden-path проти реального стека (login через
  `E2E_LOGIN_EMAIL`/`E2E_LOGIN_PASSWORD`, `E2E_FUNNELS_PROJECT_ID` із CONNECTED-ботом, опц. seed draft-воронки):
  відкрити редактор → додати Send Message → Delay → Send Message → перемістити крок ↑/↓ → задати
  `trigger_value` → побачити deep-link `t.me/<bot>?start=...` + кнопку Copy → Activate → статус `active`.
  На старті: `test.skip(!backendUp, SKIP_BACKEND_MSG)` + `test.skip(!seedReady, SKIP_SEED_MSG)` (за взірцем).
- `funnels.spec.ts::funnels_activation422_showsInlineError` — дешевий **page-test** на 422-гілку, що НЕ
  потребує повного деплою/seed: відкрити редактор порожньої (без кроків) воронки на реальному стеку →
  натиснути Activate → бекенд повертає 422 → редактор показує inline-помилку валідації (НЕ глобальний toast).
  Так само захищений `test.skip` коли бекенд/seed недоступні.

Реальний Telegram у golden-path не залучається — активація лише змінює статус на бекенді.

## Acceptance Criteria

- [ ] Сторінка `funnels/[funnelId].vue` тягне воронку (steps + deepLink) і рендерить редактор; 404/чужа
      воронка обробляється gracefully (редирект на список).
- [ ] Можна додати крок будь-якого з 6 типів через picker + per-type форму; новий крок іде в кінець списку.
- [ ] Крок можна відредагувати (форма попередньо заповнена) і видалити (з confirm).
- [ ] Переміщення ↑/↓ реордерить кроки; межові кнопки disabled; save надсилає **повний масив `steps`** (PATCH).
- [ ] `FunnelTriggerSettings` показує deep-link `t.me/<bot>?start=<triggerValue>` (голий `/start` для порожнього
      `trigger_value`) + робоча кнопка «Copy».
- [ ] Кнопка Activate/Pause перемикає статус; 422 (порожні кроки / конфлікт тригера / невалідний крок /
      Delay <1хв) показується як inline-помилка з людиночитним текстом (`errors.funnels.*`).
- [ ] Клієнтська валідація дзеркалить серверні правила byte-for-byte (порожній text, Delay ≥1хв, imageUrl
      http/https, tagSlug `^[a-z0-9_-]{1,32}$`, trigger_value `^[A-Za-z0-9_-]{0,64}$`).
- [ ] Нові ключі `funnels.*` + `errors.funnels.*` присутні в `uk.json` І `en.json` з повним паритетом
      (`node scripts/check-locales.mjs` зелений — саме він enforce-ить симетрію funnels-ключів);
      додані ПІСЛЯ ключів Task 9 без конфлікту.
- [ ] Vitest-специ на AddStepDialog/EditStepDialog/FunnelStepsList/FunnelTriggerSettings зелені (`pnpm test`).
- [ ] Playwright E2E golden-path + 422-page-test зелені (`pnpm test:e2e`).

## Context Files

- [user-spec.md](../user-spec.md) — Сценарії 1-3 («створення й побудова», «тригер і deep link», «активація»),
  критерії приймання (Ліміт кроків, Send Message/Image, Delay, Tag, Custom Field, Templating, Тригер, Конфлікт
  тригера).
- [tech-spec.md](../tech-spec.md) — Implementation Tasks → Task 10; «How it works» (контракт PATCH повним
  масивом steps, deepLink у GET active); DTO-контракт; Testing Strategy → E2E.
- [decisions.md](../decisions.md) — звіти Task 5 (API-контракт) і Task 9 (store/index/i18n-база), на які
  спирається ця задача.
- [project.md](../../../.claude/skills/project-knowledge/references/project.md)
- [architecture.md](../../../.claude/skills/project-knowledge/references/architecture.md)
- [patterns.md](../../../.claude/skills/project-knowledge/references/patterns.md) — фронтенд-патерни (project-scoped
  page redirect на 404, dialog form) + Testing (Vitest `mountSuspended` component specs, i18n parity gate).
  Примітка: розділу про Playwright/E2E у patterns.md НЕМАЄ — E2E-патерн (реальний бекенд + graceful skip) беремо
  напряму зі взірця `e2e/subscribers.spec.ts`, а не з patterns.md.
- [ux-guidelines.md](../../../.claude/skills/project-knowledge/references/ux-guidelines.md) — діалоги, empty-states,
  inline-помилки.

**Code files (modify):**
- [funnels/[funnelId].vue](../../../frontend/pages/projects/[projectId]/funnels/[funnelId].vue) — нова сторінка редактора
- [FunnelStepsList.vue](../../../frontend/components/funnels/FunnelStepsList.vue) — новий
- [AddStepDialog.vue](../../../frontend/components/funnels/AddStepDialog.vue) — новий
- [EditStepDialog.vue](../../../frontend/components/funnels/EditStepDialog.vue) — новий
- [FunnelTriggerSettings.vue](../../../frontend/components/funnels/FunnelTriggerSettings.vue) — новий
- [uk.json](../../../frontend/i18n/locales/uk.json) — дописати ключі ПІСЛЯ Task 9
- [en.json](../../../frontend/i18n/locales/en.json) — дописати ключі ПІСЛЯ Task 9
- [funnels.spec.ts](../../../frontend/e2e/funnels.spec.ts) — новий E2E spec
- (нові Vitest-специ) `frontend/tests/components/{AddStepDialog,EditStepDialog,FunnelStepsList,FunnelTriggerSettings}.spec.ts`

**Code files (read for patterns):**
- [AddCustomFieldDialog.vue](../../../frontend/components/customFields/AddCustomFieldDialog.vue) — взірець
  shadcn `Dialog`/`DialogScrollContent`/`DialogFooter` + `useI18n().t()` + vee-validate+zod
  (`toTypedSchema`, `useForm`, `defineField`) + native `<select>` picker + `useApiError()` + `data-test`-атрибути.
- [custom-fields/index.vue](../../../frontend/pages/projects/[projectId]/custom-fields/index.vue) — взірець
  сторінки: route params, `onMounted`(`import.meta.client`) fetch, 404 → `navigateTo(localePath('/projects'))`,
  header + CTA + empty-state.
- [components/ui/dialog/](../../../frontend/components/ui/dialog/) — shadcn Dialog-примітиви (Dialog,
  DialogScrollContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter).
- [e2e/subscribers.spec.ts](../../../frontend/e2e/subscribers.spec.ts) — взірець Playwright-спека:
  **РЕАЛЬНИЙ бекенд** (health-probe `${E2E_BACKEND_URL}/health` у `test.beforeAll`), env-bootstrap
  (`E2E_*PROJECT_ID`/`E2E_LOGIN_*`), реальний login через UI, і `test.skip(!backendUp/!seedReady, ...)` коли
  стек/seed недоступні (НЕ фейлить suite). УВАГА: у цьому взірці НЕМАЄ `page.route()`-стабів, `makeX()`-фабрик
  чи `stubAuth` — НЕ копіювати їх; дзеркалити real-backend + graceful-skip патерн.
- [tests/components/AddCustomFieldDialog.spec.ts](../../../frontend/tests/components/AddCustomFieldDialog.spec.ts) —
  взірець Vitest component spec (`// @vitest-environment nuxt`, `mountSuspended`,
  `mockNuxtImport('useApi', () => () => apiMock)` з `@nuxt/test-utils/runtime` для мока composable,
  `vi.mock('vue-sonner')`, перевірка «API NOT called» при невалідній формі).
  Для `navigator.clipboard` (браузерний global, НЕ composable) використовувати `vi.stubGlobal`, не `mockNuxtImport`.
- [scripts/check-locales.mjs](../../../frontend/scripts/check-locales.mjs) — **справжній symmetric uk/en
  parity-гейт для funnels** (`cd frontend && node scripts/check-locales.mjs`). Саме він валідує, що нові
  `funnels.*`/`errors.funnels.*` присутні в обох локалях.
- [tests/i18n/required-keys.spec.ts](../../../frontend/tests/i18n/required-keys.spec.ts) — перевіряє лише
  захардкоджений список СТАРІШИХ ключів; funnels-паритет НЕ enforce-ить (не покладатися на нього для funnels).
- [stores/funnels.ts](../../../frontend/stores/funnels.ts) — store з Task 9 (fetch/create/update/delete +
  activate/pause). **Guard:** цей файл ІСНУЄ лише ПІСЛЯ завершення Task 9 — `depends_on: [5, 9]` + `wave: 4`
  (t9 у wave 3) гарантують, що Task 9 завершено до старту цієї задачі, тож на момент читання файл уже на місці.
  Якщо потрібного екшну немає — додати, але узгодити з тим, що зробив Task 9 (див. decisions.md).

## Verification Steps

### Automated
- `cd frontend && pnpm test` → Vitest зелений (нові funnels component specs; мок `useApi` через
  `mockNuxtImport`, `navigator.clipboard` через `vi.stubGlobal`).
- `cd frontend && node scripts/check-locales.mjs` → симетричний uk/en паритет funnels-ключів
  (`funnels.*`/`errors.funnels.*` без дрейфу). **Це і є funnels-i18n-parity-гейт** —
  `tests/i18n/required-keys.spec.ts` його НЕ покриває (перевіряє лише захардкоджений список старих ключів).
- `cd frontend && pnpm test:e2e` → Playwright `e2e/funnels.spec.ts` (golden-path + 422-page-test) проти
  запущеного стека; за відсутності бекенда/seed специ роблять `test.skip` (НЕ фейлять suite).

### User
<!-- From tech-spec Task 10 Verify-user. -->
Попросити користувача (потрібен запущений локальний стек: бекенд + `pnpm dev`, підключений CONNECTED-бот у
проєкті, наявна `draft`-воронка з Task 9):

- Відкрити `localhost:3000/projects/{id}/funnels/{fid}` → додати крок Send Message → Delay → Send Message →
  перемістити крок ↑/↓ → налаштувати `trigger_value` (напр. `ref_smoke`) → побачити готовий deep-link
  `t.me/<bot>?start=ref_smoke` і робочу кнопку «Copy» → натиснути «Activate» → статус стає `active`.
- Перевірити негативну гілку: спроба активувати воронку без кроків / із конфліктним тригером → inline-помилка
  валідації (а не загальний toast).

## Details

**Files:**
- `frontend/pages/projects/[projectId]/funnels/[funnelId].vue` — сторінка редактора. `onMounted`
  (`import.meta.client`) → store fetch single (`steps`+`deepLink`). Локальний `steps`-стан для оптимістичного
  реордеру; save через store-update повним масивом. Activate/Pause через відповідні store-екшни. Заголовок:
  назва + статус-бейдж (draft/active/paused). 404 → `navigateTo(localePath('/projects/{id}/funnels'))`.
- `frontend/components/funnels/FunnelStepsList.vue` — вертикальний список; per-row summary за `stepType`;
  кнопки ↑/↓ (disabled на межах), Edit, Delete (confirm). Емітить `move`/`edit`/`delete`/`add` нагору.
- `frontend/components/funnels/AddStepDialog.vue` — picker `StepType` (native `<select>`) → per-type форма;
  vee-validate+zod клієнт-валідація; емітить новий крок.
- `frontend/components/funnels/EditStepDialog.vue` — попередньо заповнена per-type форма; емітить оновлений
  крок. Доцільно винести спільну форму, але обидва файли мусять існувати.
- `frontend/components/funnels/FunnelTriggerSettings.vue` — `triggerType` (фіксовано `/start`) +
  `trigger_value`; deep-link + Copy (Clipboard API) із «Copied»-фідбеком.
- `frontend/i18n/locales/uk.json`, `en.json` — **дописати** `funnels.editor.*`, `funnels.steps.*` (типи
  кроків, summary-лейбли), `funnels.trigger.*`, `funnels.activate.*`, `errors.funnels.*` (коди: порожні кроки,
  `funnel_trigger_conflict`, невалідний крок, Delay <1хв, max steps). Повний паритет uk/en.
- `frontend/e2e/funnels.spec.ts` — golden-path + 422-page-test за патерном **реального бекенда** з
  `e2e/subscribers.spec.ts`: health-probe `${E2E_BACKEND_URL}/health` у `test.beforeAll`, env-bootstrap
  (`E2E_*PROJECT_ID`/`E2E_LOGIN_*`), реальний login через UI, `test.skip(!backendUp/!seedReady, ...)` коли
  стек/seed недоступні. БЕЗ `page.route()`/`stubAuth`/`makeX()` — цих патернів у взірці немає, нічого не стабити.
- `frontend/tests/components/*.spec.ts` — Vitest component specs.

**Dependencies:**
- **Task 5** — API-контракт: `FunnelResponse` (steps + `deepLink` для active), PATCH повним масивом `steps`,
  `POST .../activate` / `.../pause`, 422-коди (`funnel_trigger_conflict`, max-steps, невалідні кроки).
- **Task 9** — `stores/funnels.ts` (екшни fetch/create/update/delete + activate/pause), `funnels/index.vue`
  (навігація в редактор), **базовий блок i18n-ключів** `funnels.*`/`errors.funnels.*`. **Guard:**
  `frontend/stores/funnels.ts`, який читається на старті задачі, з'являється ТІЛЬКИ після завершення Task 9 —
  ланцюг `depends_on: [5, 9]` (t9 — wave 3, ця задача — wave 4) це гарантує. Прочитати звіт Task 9
  у decisions.md, щоб дізнатися точні назви store-екшнів і вже наявні i18n-ключі.
- Пакети — усі наявні (shadcn-vue/radix-vue, vee-validate+zod, vue-i18n, Pinia, vue-sonner, Playwright,
  Vitest+@nuxt/test-utils); нічого не додавати.

**Edge cases:**
- Порожня воронка (0 кроків) → empty-state CTA; Activate → 422 «порожні кроки» inline.
- Активація з конфліктом тригера (інша active-воронка з тим самим `(/start, trigger_value)`) → 422
  `funnel_trigger_conflict` inline.
- Голий `/start` (порожній `trigger_value`) → deep-link без `?start=`; має співіснувати з payload-воронками.
- Delay <1хв → клієнт-валідація блокує + серверний 422 як страхувальна сітка.
- imageUrl без http/https → клієнт-валідація; tagSlug поза `^[a-z0-9_-]{1,32}$` → валідація.
- 404/чужа воронка при відкритті редактора (anti-IDOR uniform 404 з бекенду) → graceful redirect.
- Перезавантаження сторінки active-воронки → `deepLink` усе ще видно (читається з GET, не лише з activate-відповіді).
- Реордер: save повним масивом; сервер перезаписує `order` за індексом — клієнту не треба слати `order` вручну.

**Implementation hints:**
- Дзеркаль патерн `AddCustomFieldDialog.vue`: shadcn `Dialog`/`DialogScrollContent`/`DialogFooter`,
  `useI18n().t()`, `toTypedSchema`+`useForm`+`defineField`, native `<select>` для picker (тестованість),
  `useApiError()` для мапінгу помилок, `data-test`-атрибути на всіх інтерактивних елементах (для Vitest+E2E).
- Activate/Pause-помилки: мапити 422-`code` із відповіді на `errors.funnels.<code>` через `useApiError`;
  показувати inline біля кнопки, не глобальним toast.
- Copy: `navigator.clipboard.writeText(deepLink)` + тимчасовий «Copied»-стан; у Vitest мокати
  `navigator.clipboard` через `vi.stubGlobal` (браузерний global, не composable) і перевірити як аргумент
  `writeText`, так і появу «Copied»-фідбеку; в E2E clipboard може бути недоступний — лишити лінк перевірюваним
  у DOM окремо.
- Component-специ: мок Nuxt-composables (`useApi`) — `mockNuxtImport('useApi', () => () => apiMock)` з
  `@nuxt/test-utils/runtime` (як у `AddCustomFieldDialog.spec.ts`), НЕ `vi.stubGlobal`.
- E2E: повторити підхід `e2e/subscribers.spec.ts` — **реальний бекенд** + env-bootstrap + `test.skip` коли
  стек/seed недоступні (НЕ `page.route()`/`stubAuth`/`makeX()` — їх у взірці немає). Golden-path ганяється
  проти запущеного стека; 422-гілка — дешевий page-test на порожній воронці. Реальний Telegram не залучається.
- i18n: додавати ключі в **кінець** блоку `funnels`/`errors.funnels`, успадкованого від Task 9 — не
  переписувати наявні; одразу дублювати в обидва локалі, щоб parity-гейт лишався зеленим.
- НЕ реалізовувати inline-кнопки/гілки/drag-and-drop/прев'ю/лічильник підписників — це поза скоупом Фази 1
  (user-spec «Технические решения» → що НЕ робимо).

## Reviewers

- **code-reviewer** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-10/code-reviewer-{round}.json`
- **security-auditor** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-10/security-auditor-{round}.json`
- **test-reviewer** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-10/test-reviewer-{round}.json`

## Post-completion

- [ ] Записать краткий отчёт в decisions.md по шаблону (Summary: 1-3 предложения, ревью со ссылками на JSON, без таблиц файндингов и дампов)
- [ ] Если отклонились от спека — описать отклонение и причину
- [ ] Обновить user-spec/tech-spec если что-то изменилось
