---
status: done
depends_on: [5]                    # Task 5 — Funnel CRUD service + controller (API contract)
wave: 3                            # волна параллельного выполнения
skills: [code-writing]             # МАССИВ скиллов для загрузки
verify: [user]                     # типы верификации: smoke, user (опционально)
reviewers: [code-reviewer, security-auditor, test-reviewer]
teammate_name:
---

# Task 9: Funnels store + список + create/delete

## Required Skills

Перед выполнением задачи загрузи:
- `/skill:code-writing` — [skills/code-writing/SKILL.md](../../../.claude/skills/code-writing/SKILL.md)

## Description

Фронтенд-фундамент епіку воронок (Wave 4, перша фронтенд-задача): Pinia-стор `useFunnelsStore` поверх
`useApi` і сторінка-список воронок проєкту. Це реалізує **Сценарій 1, крок 1** user-spec:
власник на `/projects/{id}/funnels` бачить список воронок із фільтром статусу, тисне «Create funnel»,
вводить назву (+опц. опис) → створюється `draft`-воронка і відбувається перехід у редактор
(`funnels/[funnelId].vue`, який будує Task 10 — тут лише навігація туди). Плюс delete із confirm-діалогом
(сервер каскадно скасовує активні виконання — це бекенд Task 5; фронт лише викликає DELETE і просить
підтвердження).

**Розподіл відповідальності store vs component (КРИТИЧНО):** стор робить лише HTTP через `useApi` і
**прокидає (re-throw) помилку як є** — він НЕ викликає `useApiError` всередині action. `useApiError`
викликає `useI18n()`, який вимагає Vue component setup-контексту і **впаде в рантаймі**, якщо викликати
його з action Pinia-стора. Жоден наявний стор (`subscribers`/`bot`/`projects`/`auth`) так не робить.
Тому мапінг `errors.funnels.*`-кодів у локалізовані повідомлення роблять **компоненти** —
`CreateFunnelDialog.vue` та сторінка-список (`funnels/index.vue`): вони викликають `useApiError` у своєму
setup-контексті, ловлять помилку зі стора і показують її.

Структурний взірець стора — `stores/bot.ts` / `stores/projects.ts` (простий CRUD: дії
`fetch`/`create`/`update`/`delete`, ref-стейт, `useApi`-виклики). **НЕ** бери `stores/subscribers.ts` за
структурний взірець — він курсор-пагінований (`loadFirstPage`/`loadMore`) і структурно несумісний із
простим CRUD-стором. З `subscribers.ts` запозич лише **ідіому читання `projectId` з route-параметра**
(`useRoute().params.projectId` у helper-функції `listUrl()`), щоб не протягувати `projectId` через кожну
дію вручну. Сторінка-список дзеркалить розкладку/стани CRM-взірця
`pages/projects/[projectId]/custom-fields/index.vue` (empty-state CTA, loading/error-стани, shadcn-vue
діалоги, vee-validate+zod для форми create).

DTO-контракт від Task 5: `GET /api/v1/projects/{projectId}/funnels?status=` повертає **легкий список**
`FunnelSummaryResponse[]` (метадані без `steps`), `POST .../funnels` приймає `{name, description?}` →
повертає `FunnelResponse` (з `id` для навігації в редактор), `DELETE .../funnels/{funnelId}` → 204.
Стор також виставляє `fetch/create/update/delete` — `update` тут не має UI (редагування метаданих/кроків —
Task 10), але метод включаємо в стор за контрактом, щоб Task 10 його переюзав.

i18n: нові ключі `funnels.*` (заголовки, кнопки, статуси, empty-state, confirm-тексти) + `errors.funnels.*`
(коди помилок CRUD) у **обидва** локалі (`uk.json`, `en.json`) з повним паритетом ключів.

## What to do

- Створити `frontend/stores/funnels.ts` з `useFunnelsStore`: реактивний стейт (`funnels`, `loading`,
  `error`), дії `fetch(status?)`, `create(payload)`, `update(funnelId, payload)`, `delete(funnelId)`.
  Усі HTTP — через `useApi`. **Стор НЕ викликає `useApiError`** — action ловить мінімально (для
  loading/error-флагу) і **прокидає помилку (re-throw)**, щоб компонент її змапив. Структура — як
  `stores/bot.ts`/`stores/projects.ts`; `projectId` читай із route через `useRoute().params.projectId`
  у helper `listUrl()` (ідіома з `subscribers.ts`). `fetch` тримає список `FunnelSummaryResponse[]`;
  `create` повертає створену воронку (з `id`) для навігації; `delete` оновлює локальний список.
- Створити сторінку `frontend/pages/projects/[projectId]/funnels/index.vue`: завантажує список на mount,
  фільтр за статусом (`all`/`draft`/`active`/`paused`), рендер карток/рядків воронок (name, status badge,
  опис), кнопка «Create funnel», empty-state CTA коли воронок немає, кнопка delete на кожній воронці з
  confirm-діалогом. Loading/error-стани. Помилки зі стора (`fetch`/`delete`) **мапить сама сторінка**
  через `useApiError` у setup-контексті. Клік по воронці / по «edit» → навігація на
  `funnels/{funnelId}` (редактор Task 10).
- Створити `frontend/components/funnels/CreateFunnelDialog.vue`: shadcn-vue dialog із формою (name
  required, description optional) на vee-validate+zod; на submit викликає `store.create`, при успіху
  закриває діалог і навігує в редактор створеної воронки; **серверні помилки мапить сам компонент**
  через `useApiError` (викликаний у setup-контексті компонента, не в сторі).
- Додати i18n-ключі `funnels.*` + `errors.funnels.*` у `frontend/i18n/locales/uk.json` ТА
  `frontend/i18n/locales/en.json` — однаковий набір ключів в обох (parity gate).
- Написати Vitest-специ (див. TDD Anchor) + переконатися, що i18n parity-spec включає нові ключі.

## TDD Anchor

Тести пишемо ДО реалізації, переконуємось що падають, потім код, потім зелені. Шляхи специв — під
`frontend/tests/` (vitest.config.ts `include: ['tests/**']`). Взірець моків — наявні Vitest-специ
(мок `useApi`; `useApiError` мокається лише в специх **компонентів**, не стора).

- `frontend/tests/stores/funnels.spec.ts::fetch loads summary list` — `fetch()` викликає
  `GET /projects/{projectId}/funnels`, заповнює `funnels` з `FunnelSummaryResponse[]` (мок `useApi`).
- `frontend/tests/stores/funnels.spec.ts::fetch with status filter` — `fetch('active')` додає
  `?status=active` у запит.
- `frontend/tests/stores/funnels.spec.ts::create posts and returns funnel` — `create({name})`
  робить `POST` і повертає об'єкт із `id`.
- `frontend/tests/stores/funnels.spec.ts::action re-throws on api error` — при відмові `useApi`
  action **прокидає помилку** наверх (стор НЕ викликає `useApiError`, не ковтає її).
- `frontend/tests/stores/funnels.spec.ts::update patches funnel` — `update(id, payload)` робить
  `PATCH .../funnels/{id}`.
- `frontend/tests/stores/funnels.spec.ts::delete removes from list` — `delete(id)` робить `DELETE`
  і прибирає воронку з локального `funnels`.
- `frontend/tests/components/funnels/CreateFunnelDialog.spec.ts::validates required name` — порожнє
  name → zod-помилка, submit заблоковано.
- `frontend/tests/components/funnels/CreateFunnelDialog.spec.ts::submits and navigates` — валідний
  submit кличе `store.create` і навігує в редактор створеної воронки.
- `frontend/tests/components/funnels/CreateFunnelDialog.spec.ts::maps server error via useApiError` —
  коли `store.create` прокидає помилку, компонент мапить її через `useApiError` і показує повідомлення.
- `frontend/tests/i18n/required-keys.spec.ts` (наявний parity-spec) — оновити/переконатися, що включає
  `funnels.*` + `errors.funnels.*` і що `uk.json`/`en.json` мають ідентичний набір цих ключів.

## Acceptance Criteria

- [ ] `useFunnelsStore` має дії `fetch`/`create`/`update`/`delete`, усі через `useApi`; стор НЕ викликає
      `useApiError` — action прокидає помилку наверх.
- [ ] Мапінг помилок у `errors.funnels.*` роблять компоненти (`CreateFunnelDialog.vue`, `funnels/index.vue`)
      через `useApiError` у власному setup-контексті.
- [ ] `funnels/index.vue` рендерить список воронок (`FunnelSummaryResponse[]`) із фільтром статусу,
      loading- та error-станами.
- [ ] Empty-state CTA показується коли воронок немає й веде до create.
- [ ] «Create funnel» відкриває `CreateFunnelDialog`; валідна форма (name required) створює `draft`-воронку
      і навігує в редактор `funnels/{id}`.
- [ ] Delete показує confirm-діалог; підтвердження викликає `DELETE` і прибирає воронку зі списку.
- [ ] Нові ключі `funnels.*` + `errors.funnels.*` присутні в `uk.json` ТА `en.json` з повним паритетом;
      i18n parity gate зелений.
- [ ] Усі Vitest-специ зелені; немає регресій у наявних frontend-тестах.

## Context Files

- [user-spec.md](../user-spec.md) — Сценарій 1 (створення/побудова воронки)
- [tech-spec.md](../tech-spec.md) — Frontend "What we're building", DTO-контракт (FunnelSummaryResponse vs FunnelResponse), Testing Strategy → Frontend Vitest, Dependencies
- [decisions.md](../decisions.md)
- [project.md](../../../.claude/skills/project-knowledge/references/project.md)
- [architecture.md](../../../.claude/skills/project-knowledge/references/architecture.md)
- [patterns.md](../../../.claude/skills/project-knowledge/references/patterns.md) — frontend patterns, Testing (Vitest), i18n parity
- [ux-guidelines.md](../../../.claude/skills/project-knowledge/references/ux-guidelines.md) — empty-state, dialogs, status badges
- Взірець (читати):
  - [stores/bot.ts](../../../frontend/stores/bot.ts) — СТРУКТУРНИЙ взірець CRUD-стора (дзеркалити структуру)
  - [stores/projects.ts](../../../frontend/stores/projects.ts) — СТРУКТУРНИЙ взірець CRUD-стора (fetch/create/update/delete)
  - [stores/subscribers.ts](../../../frontend/stores/subscribers.ts) — лише ідіома `useRoute().params.projectId` у `listUrl()` (НЕ структура — він курсор-пагінований)
  - [custom-fields/index.vue](../../../frontend/pages/projects/[projectId]/custom-fields/index.vue) — взірець сторінки-списку (стани, діалоги, empty-state)
  - [composables/useApi.ts](../../../frontend/composables/useApi.ts)
  - [composables/useApiError.ts](../../../frontend/composables/useApiError.ts) — викликати в КОМПОНЕНТАХ, не в сторі
  - [i18n/locales/uk.json](../../../frontend/i18n/locales/uk.json), [i18n/locales/en.json](../../../frontend/i18n/locales/en.json)
- Файли для створення:
  - [frontend/stores/funnels.ts](../../../frontend/stores/funnels.ts)
  - [frontend/pages/projects/[projectId]/funnels/index.vue](../../../frontend/pages/projects/[projectId]/funnels/index.vue)
  - [frontend/components/funnels/CreateFunnelDialog.vue](../../../frontend/components/funnels/CreateFunnelDialog.vue)

## Verification Steps

### Automated
- `cd frontend && pnpm test` → усі Vitest-специ зелені (стор fetch/create/update/delete, CreateFunnelDialog, i18n parity).
- `cd frontend && pnpm vitest run tests/stores/funnels.spec.ts tests/components/funnels/CreateFunnelDialog.spec.ts tests/i18n/required-keys.spec.ts` → таргетований прогін нових специв зелений.
- `cd frontend && node scripts/check-locales.mjs` → uk.json/en.json ключі збігаються (немає дрейфу `funnels.*`/`errors.funnels.*`). Якщо команда існує — інакше parity покривається `required-keys.spec.ts`.

### User
<!-- From tech-spec Verify-user — agent asks user to verify in browser. -->
Запусти фронтенд (`cd frontend && pnpm dev`) і попроси користувача відкрити
`localhost:3000/projects/{id}/funnels`:
- список воронок рендериться (або empty-state CTA, якщо їх немає);
- «Create funnel» → діалог → ввести назву → створюється воронка і **відкривається редактор** воронки;
- delete на воронці **просить confirm** перед видаленням.

## Details

<!-- All details for task execution. -->

**Files:**
- `frontend/stores/funnels.ts` — НОВИЙ. `useFunnelsStore` (Pinia, setup-style). Стейт:
  `funnels: FunnelSummaryResponse[]`, `loading`, `error`. Дії `fetch(status?)`, `create({name, description?})`,
  `update(funnelId, payload)`, `delete(funnelId)`. **Структурний взірець — `stores/bot.ts` /
  `stores/projects.ts`** (простий CRUD: ref-стейт + `useApi`-дії). `projectId` читай із route через
  helper `listUrl()` (`const projectId = useRoute().params.projectId` → base-path
  `/api/v1/projects/${projectId}/funnels`) — ідіома з `stores/subscribers.ts` (звідти бери ЛИШЕ це, не
  курсор-пагінацію). **Стор НЕ імпортує і НЕ викликає `useApiError`**: action ловить лише для скидання
  `loading`/виставлення `error`-флага і прокидає помилку (`throw`), щоб компонент її змапив у
  setup-контексті.
- `frontend/pages/projects/[projectId]/funnels/index.vue` — НОВА. Список + фільтр статусу + create-кнопка +
  empty-state + delete-confirm. Взірець розкладки/станів — `custom-fields/index.vue`. Помилки зі стора
  (`fetch`/`delete`) мапить ця сторінка через `useApiError` (викликаний у її setup).
- `frontend/components/funnels/CreateFunnelDialog.vue` — НОВИЙ. shadcn-vue dialog + vee-validate+zod форма
  (name required, description optional). Взірець — діалог create у custom-fields (`components/customFields/`).
  На submit ловить помилку зі `store.create` і мапить її через `useApiError` (викликаний у setup
  компонента — НЕ в сторі).
- `frontend/i18n/locales/uk.json` + `frontend/i18n/locales/en.json` — ДОДАТИ `funnels.*` (title, createButton,
  status labels draft/active/paused, emptyState title/cta, deleteConfirm title/message/confirm/cancel, form
  labels name/description) + `errors.funnels.*` (CRUD коди, які повертає Task-5 API: напр.
  `funnel_max_steps_exceeded`, `funnel_trigger_conflict`, generic create/delete fail). Однаковий набір
  ключів в обох локалях.

**Dependencies:**
- **Task 5 (API)** — `depends_on: [5]`. Контракт: `GET .../funnels?status=` → `FunnelSummaryResponse[]`
  (метадані без `steps`); `POST .../funnels` `{name, description?}` → `FunnelResponse` (з `id`);
  `PATCH .../funnels/{id}`; `DELETE .../funnels/{id}` → 204; коди помилок 422 `funnel_trigger_conflict`/
  `funnel_max_steps_exceeded`, 404 uniform. Звірити фактичні DTO-поля з Task-5 кодом/decisions перед
  написанням типів.
- Пакети — лише наявні: `useApi`, `useApiError`, vee-validate+zod, shadcn-vue, `@nuxtjs/i18n`, Pinia,
  Vitest. Нічого нового не ставити.

**ВАЖЛИВО — спільні locale-файли (sequential, не parallel):** ця задача додає `funnels.*` +
`errors.funnels.*` у `uk.json`/`en.json`. **Task 10 додає ДОДАТКОВІ ключі ПІСЛЯ цієї задачі** (редактор/
тригер/активація) — редагування locale-файлів навмисно послідовне (Wave 4 → Wave 5), щоб уникнути
паралельних конфліктних правок одних і тих самих JSON. Тому: додавай свої ключі акуратно, лиши структуру
розширюваною (секція `funnels` із під-секціями), не перейменовуй наявні ключі.

**Edge cases:**
- Empty list → empty-state CTA (не порожня таблиця).
- Помилка `fetch` → error-стан із retry, не білий екран (сторінка мапить помилку через `useApiError`).
- `create` повертає 422 (напр. валідація імені сервером) → стор прокидає помилку, `CreateFunnelDialog`
  ловить її і показує inline через `useApiError`.
- `delete` воронки з активними виконаннями → бекенд (Task 5) каскадно скасовує; фронт лише confirm + DELETE,
  без власної логіки скасування.
- Фільтр `all` (default) vs конкретний статус → коректний `?status=` (відсутній параметр для `all`).
- Невідомий код помилки → fallback-повідомлення через `useApiError` у компоненті (не сирий код).

**Implementation hints (НЕ псевдокод):**
- Структуру стора бери з `stores/bot.ts`/`stores/projects.ts` (як обгортають `useApi`-виклики, як
  виставляють loading/state, дії CRUD). З `stores/subscribers.ts` візьми ЛИШЕ ідіому
  `useRoute().params.projectId` у helper `listUrl()`. Не вигадуй новий патерн.
- `useApiError` викликається ТІЛЬКИ в компонентах (`CreateFunnelDialog.vue`, `funnels/index.vue`) у
  їхньому setup. У сторі його викликати не можна — він тягне `useI18n()`, який поза setup-контекстом
  падає в рантаймі. Стор лише `throw`-ить помилку наверх.
- Навігація в редактор — через Nuxt `navigateTo`/router, шлях `funnels/{id}` (сторінку будує Task 10; якщо
  її ще немає під час ручної перевірки — навігація просто веде на майбутній маршрут).
- Для i18n: додай ключі в обидва файли в одному коміті; прогони parity-spec/`check-locales.mjs` до ревью.
- Перед типізацією DTO — звір реальні поля `FunnelSummaryResponse`/`FunnelResponse`/`CreateFunnelRequest`
  з кодом Task 5 (`backend/.../funnel/dto/`) або записом Task 5 у decisions.md.

## Reviewers

- **code-reviewer** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-9/code-reviewer-{round}.json`
- **security-auditor** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-9/security-auditor-{round}.json`
- **test-reviewer** → `/Users/pavlokorolov/IdeaProjects/simple-sender/work/10-funnels/logs/working/task-9/test-reviewer-{round}.json`

## Post-completion

- [ ] Записать краткий отчёт в decisions.md по шаблону (Summary: 1-3 предложения, ревью со ссылками на JSON, без таблиц файндингов и дампов)
- [ ] Если отклонились от спека — описать отклонение и причину
- [ ] Обновить user-spec/tech-spec если что-то изменилось
