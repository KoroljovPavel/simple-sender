---
created: 2026-05-08
status: approved
branch: dev
size: M
---

# Tech Spec: 03 — i18n (uk + en)

## Solution

Підключаємо офіційний `@nuxtjs/i18n@^9` до Nuxt 4 фронтенду з двома локалями: `uk` (default, без префікса) та `en` (префікс `/en`). Локаль персиститься у cookie `i18n_lang` на 1 рік; детекція URL → cookie → Accept-Language → default uk. Усі видимі рядки у `pages/`, `layouts/`, `components/` виносимо у nested JSON namespaces (`auth`, `dashboard`, `profile`, `layout`, `validation`, `errors`, `common`, `brand`). Окремо створюємо `useApiError()` composable (factory-pattern: викликається у setup, повертає handler) і `LangSwitcher.vue` як текстовий toggle `UK | EN`. Auth global middleware перебудовуємо через `useRouteBaseName()` замість path-prefix matching. Server-side global Nitro middleware стрипає **будь-який непідтримуваний 2-літерний префікс** (включно з `uk`, бо під `prefix_except_default` префікс default-локалі заборонений) → 302 на default. Build-script `scripts/check-locales.mjs` запускається через `prebuild` і провалює CI, якщо парсинг JSON не вдається або key-set між uk/en розходиться. Backend та email-шаблони не змінюються — фіча зачіпає тільки frontend bundle.

## Architecture

### What we're building/modifying

- **`@nuxtjs/i18n` module integration** — реєстрація в `nuxt.config.ts:modules`, конфіг блок `i18n` (locales, strategy, defaultLocale, detectBrowserLanguage з explicit `cookieKey: 'i18n_lang'` — `cookieMaxAge` не задаємо, module default ~1y покриває вимогу), розширення `routeRules` на `/en/auth/**` для security headers
- **Locale dictionaries** — `frontend/i18n/locales/uk.json` + `en.json`, eager-loaded, nested JSON, camelCase ключі, namespaces по фічах
- **`i18n.config.ts`** — `legacy: false` (Composition API mode), `fallbackLocale: 'uk'`
- **`scripts/check-locales.mjs`** — pre-build валідація: парсинг JSON + рекурсивна перевірка однакового набору ключів uk/en. Захищена від prototype-pollution (`Object.create(null)` для key-set, фільтр `__proto__`/`constructor`/`prototype`). Запускається через `prebuild` script
- **`composables/useApiError.ts`** — factory composable: `const apiError = useApiError()` у `<script setup>` робить `useI18n()` у правильному lifecycle, повертає handler `(error, contextKey) => string`. Handler extract status з `e?.statusCode ?? e?.status ?? e?.response?.status` і шукає ключ через `te()` (translation exists) — спочатку `errors.{contextKey}.{code}`, потім `errors.{contextKey}.generic`, потім `errors.generic`
- **`components/LangSwitcher.vue`** — текстовий `UK | EN` toggle. Активна мова виділена. При кліку: `await setLocale(code)` (canonical v9 pattern: оновлює cookie, locale state, `<html lang>`, і робить auto-navigate на локалізований path під strategy `prefix_except_default`)
- **Server middleware `frontend/server/middleware/strip-invalid-locale.ts`** — Nitro global middleware. Парсить `event.path`, регекс `^/([a-z]{2})(?=/|$)` витягує potential locale-префікс. Якщо префікс **не** дорівнює `'en'` (єдина не-default локаль) — нормалізує path: (1) reject backslash → 400 (no redirect); (2) collapse leading multiple-slashes у hostnameless path (`//evil.com` → `/evil.com`); (3) decode percent-encoding *після* prefix-strip і normalization, щоб уникнути decode-then-match bypass; і `sendRedirect` 302 на path без префікса. Для auth-zone (path-after-strip починається з `/auth` — без trailing slash щоб ловити `/auth?token=x` а не лише `/auth/`) виставляє headers `X-Robots-Tag: noindex, nofollow` + `Referrer-Policy: no-referrer` на сам 302-response. Виконується до Vue Router, отже до auth-middleware
- **Auth global middleware refactor** — `frontend/middleware/auth.global.ts` переходить на `useRouteBaseName()(to)` (composable повертає функцію, що дає route base name незалежно від локалі, наприклад `auth-login`). Whitelist public-routes — explicit array `PUBLIC_ROUTE_NAMES`. Якщо `getBaseName(to)` повертає `undefined` (route не зареєстрований) — fail-closed, treat as protected. Усі `navigateTo()` обгортаються `useLocalePath()`
- **`app.vue` SEO wiring** — `useLocaleHead({ seo: true })` (правильна v9 опція) для динамічного `<html lang>` та `hreflang`-альтернатив. На auth-сторінках hreflang проставляється для обох локалей, але `noindex`-header і так блокує індексацію — додаткового опрацювання не треба
- **Layouts оновлення** — `default.vue` (топбар: LangSwitcher праворуч від Logout, переклад `Logout`/`Dashboard`/`Profile`), `auth.vue` (LangSwitcher absolute top-right поверх центральної картки)
- **Page rewrites** — 5 auth-сторінок + dashboard + profile: `t()` всюди, `<NuxtLinkLocale>` замість `<NuxtLink>`, `localePath()` обгортає `navigateTo()`, Zod-схеми у `computed()` для re-run при зміні локалі, інтеграція з `useApiError` factory
- **Playwright bootstrap** — `@playwright/test` devDep, `playwright.config.ts` з webServer, `e2e/` директорія, `test:e2e` npm-script, `.gitignore` оновлення (створити `frontend/.gitignore` якщо не існує)
- **Single E2E** — `e2e/i18n.spec.ts`: open `/auth/login` → click EN → assert URL `/en/auth/login` + button "Sign in" + EN validation message + `<html lang>="en"`
- **Vitest spec для LangSwitcher** — render обох мов; click → асерції на `setLocale('en')` call і `useCookie('i18n_lang').value === 'en'` (per AC15); активна мова виділена

### How it works

**Перший візит без cookie (Accept-Language: en):**
1. Запит на `/dashboard` приходить у Nitro.
2. `strip-invalid-locale` middleware: path `/dashboard` не починається з 2-літерного префікса — пропускає далі.
3. `@nuxtjs/i18n` SSR plugin читає `Accept-Language: en` (cookie відсутній), визначає `en`, видає 302 на `/en/dashboard`, ставить cookie `i18n_lang=en` (Secure якщо HTTPS, SameSite=Lax, MaxAge 1 рік).
4. Nuxt SSR render у `en`, клієнт гідрує без mismatch (ті самі рядки eager-loaded на client).

**Перемикання локалі (LangSwitcher):**
1. Юзер на `/auth/login` (uk). Клік на `EN` у `LangSwitcher`.
2. Компонент викликає `await setLocale('en')`. Модуль atomically: оновлює cookie `i18n_lang=en`, internal locale state, `<html lang>` (через `useLocaleHead`), і auto-navigate на локалізований path `/en/auth/login` (під `prefix_except_default`).
3. Компоненти ре-рендерять рядки з `en` namespace; Zod-схеми (у `computed`) перебудовуються; форма скидається.

**Невалідний префікс:**
1. `/fr/dashboard` → `strip-invalid-locale` middleware матчить regex, `fr ≠ 'en'` → нормалізує `/dashboard`, `sendRedirect(event, '/dashboard', 302)`.
2. `/uk/dashboard` → той самий шлях: `uk ≠ 'en'` → 302 на `/dashboard`. `uk` стрипається бо під `prefix_except_default` префікс default-локалі заборонений (модуль інакше повертає 404).
3. Auth middleware ніколи не виконується для невалідних префіксів — strip відбувається на server-сайді раніше.

**Невалідне cookie (`i18n_lang=xx`):**
1. `@nuxtjs/i18n` SSR plugin читає cookie, валідує проти `locales` списку. `xx` не в списку → cookie ігнорується, fallback на Accept-Language → uk.
2. Cookie перезаписується наступним явним вибором юзера.

**Build flow:**
1. `pnpm build` запускає Nuxt build. npm автоматично виконує `prebuild` script (npm convention) → `node scripts/check-locales.mjs`.
2. Скрипт парсить обидва JSON. Якщо файл відсутній або невалідний → `process.exit(1)`. Будує key-sets через `Object.create(null)` (захист від prototype pollution), порівнює рекурсивно — розбіжність → exit 1 з переліком missing keys.
3. Якщо exit 0 — Nuxt build продовжується. Інакше CI ловить fail.

### Shared resources

| Resource | Owner (creates) | Consumers | Instance count |
|----------|----------------|-----------|----------------|
| `useI18n()` composable instance | `@nuxtjs/i18n` plugin (auto-injected) | LangSwitcher, всі pages, layouts, useApiError factory | 1 per Vue app instance |
| Locale messages (eager-loaded) | i18n plugin при старті | Усі t() consumers | 2 ресурси (uk + en) у пам'яті, ~10KB кожен |
| Cookie `i18n_lang` | `@nuxtjs/i18n` detectBrowserLanguage | SSR resolver, client setLocale | 1 per browser, 1y TTL |

## Decisions

### Decision 1: Офіційний `@nuxtjs/i18n@^9` (а не голий `vue-i18n`)
**Decision:** Використовуємо офіційний Nuxt-модуль `@nuxtjs/i18n@^9` (Nuxt 4 + Vue I18n 11 compat).
**Rationale:** Модуль дає auto-routing з префіксами, locale-aware навігацію (`<NuxtLinkLocale>`, `localePath`, `useSwitchLocalePath`), SSR cookie integration, helpers для `<html lang>` (`useLocaleHead`) — все коробкою. Без модуля треба вручну писати router middleware, cookie SSR-resolver, head-tags injection.
**Alternatives considered:** (1) Голий `vue-i18n` — мінімум 200 LOC бойлерплейту; (2) `nuxt-i18n-micro` — менш мейнтейнений; (3) Без бібліотеки — суперечить ux-guidelines.md та user-spec вимозі офіційного модуля (AC1).
**Anchored to:** AC1 (офіційний Nuxt-модуль i18n підключено).

### Decision 2: `prefix_except_default` URL-стратегія
**Decision:** uk без префікса (`/dashboard`), en з префіксом (`/en/dashboard`).
**Rationale:** uk-bookmarks залишаються валідні (existing UA-юзери); en-посилання діляться нормально (SEO + sharing семантика на новому пристрої одразу рендерить EN).
**Alternatives considered:** (1) `prefix` — ламає всі існуючі UA-юзерські посилання; (2) `no_prefix` — втрачаємо sharing-семантику для en; (3) `prefix_and_default` — обидва шляхи валідні, ускладнює canonical URL.
**Anchored to:** AC1 ("URL-стратегія default без префікса, en з префіксом"), user-spec "URL і shared посилання".

### Decision 3: Cookie, не localStorage; `i18n_lang` як ключ; 1y TTL від module default
**Decision:** Локаль зберігається у cookie з explicit `cookieKey: 'i18n_lang'` (а не модульний default `i18n_redirected`), `cookieSecure: process.env.NODE_ENV === 'production'`, `cookieCrossOrigin: false`. SameSite політика і MaxAge — module defaults: SameSite Lax, MaxAge ~1 рік. Cookie не HttpOnly, бо клієнт читає його через `useCookie` для locale-state synchronization (acceptable trade-off: cookie не несе auth-значимості — лише UI-preference).
**Rationale:** SSR має визначити локаль до першого рендеру, щоб уникнути hydration mismatch — localStorage недоступний на сервері. Explicit cookie key зрозуміліший у DevTools (user-spec narrative і smoke-checks інспектують саме `i18n_lang`). v9 `detectBrowserLanguage` не приймає `cookieMaxAge` як опцію (verified: Context7 lists `useCookie/cookieKey/cookieDomain/cookieCrossOrigin/cookieSecure/redirectOn/alwaysRedirect/fallbackLocale` — `cookieMaxAge` відсутній); cookie живе module-default ~1 рік, що покриває user-spec "1 рік" вимогу. Secure флаг env-conditional — у dev на HTTP localhost браузер не зберіг би Secure-cookie. Non-HttpOnly: атакеру з XSS і так доступний `document.cookie`, а наш cookie не стає auth-token — risk amplification мінімальний.
**Alternatives considered:** (1) localStorage — провалює SSR; (2) Module default cookie name `i18n_redirected` — менш зрозуміло у DevTools; (3) SameSite=Strict — блокує деякі GET-навігації від external referrers; (4) HttpOnly cookie — несумісно з client-side locale-switching, бо frontend читає cookie у `useCookie`.
**Anchored to:** AC1 (cookie Secure + SameSite Lax), user-spec "Технічні рішення".

### Decision 4: Detection priority — URL > cookie > Accept-Language > default uk
**Decision:** `detectBrowserLanguage: { useCookie: true, cookieKey: 'i18n_lang', cookieSecure: process.env.NODE_ENV === 'production', cookieCrossOrigin: false, redirectOn: 'no prefix', alwaysRedirect: false, fallbackLocale: 'uk' }`. `cookieMaxAge` не передається — module default ~1 рік покриває user-spec вимогу (Decision 3).
**Rationale:** URL завжди перевершує (sharing-сценарій). Cookie повертає юзера в його мову при наступних відвідинах. Accept-Language — фінальний fallback для перших візитів. `redirectOn: 'no prefix'` важливо — забезпечує редірект EN-cookie юзера з `/dashboard` на `/en/dashboard` (а не лише з кореня).
**Alternatives considered:** (1) `redirectOn: 'root'` — редирект тільки з `/`, інші шляхи не персистять локаль; UX непослідовний; (2) `alwaysRedirect: true` — ламає sharing-семантику.
**Anchored to:** AC3, AC4, AC5 (різні редирект-сценарії за станом cookie + Accept-Language).

### Decision 5: Server-side Nitro middleware стрипає будь-який непідтримуваний 2-літерний префікс (включно з `uk`)
**Decision:** `frontend/server/middleware/strip-invalid-locale.ts` — Nitro global middleware. Regex `^/([a-z]{2})(?=/|$)` витягує potential locale, allow-list = `['en']` (єдина локаль з префіксом у нашій strategy `prefix_except_default`). Allow-list і `routeRules /en/auth/**` беруть значення з спільного константного списку non-default локалей у `nuxt.config.ts` — щоб додавання 3-ї локалі не вимагало редагування у двох місцях. Path normalization order: (1) reject backslash → 400; (2) strip prefix; (3) collapse multiple leading slashes; (4) decode percent-encoding *після* normalization (decode-then-match вразливе до double-encoded payloads). Для auth-zone (`startsWith('/auth')` — без trailing slash, ловить `/auth?token=x`) виставляє headers `X-Robots-Tag: noindex, nofollow` + `Referrer-Policy: no-referrer` на 302.
**Rationale:** Під `prefix_except_default` тільки `en` має префікс — `uk` як префікс заборонений (модуль інакше повертає 404). AC7 вимагає 302 на default для будь-якого невалідного 2-літерного префікса; те саме має застосовуватись до `/uk/...`. Server-side Nitro middleware виконується до Vue Router і до auth-middleware — гарантує стабільний 302 незалежно від auth-стану. Path normalization запобігає open-redirect (`/fr//evil.com` → `//evil.com` був би protocol-relative URL). Spy-точка-зв'язок між allow-list і routeRules захищає від parity-drift при додаванні локалей.
**Alternatives considered:** (1) Allow-list `['uk', 'en']` — `/uk/dashboard` пропустився б до Vue Router, де модуль повернув би 404 (UX гірший за 302); (2) Vue Router middleware — виконується після SSR resolution, складніше у edge cases; (3) `routeRules` redirect — не підтримує regex match.
**Anchored to:** AC7 (`/fr/dashboard` → 302 на `/dashboard`, до auth-redirect), Risk "Security regression на 302 redirect leaks `?token=` via Referer".

### Decision 6: Auth middleware на `useRouteBaseName()`
**Decision:** `frontend/middleware/auth.global.ts` викликає composable `useRouteBaseName()` всередині middleware-handler — composable повертає функцію (named `getRouteBaseName` за конвенцією, тут локально alias-нута як `getBaseName` для краткості). Для кожного `to` обчислює `baseName = getBaseName(to)` — повертає `auth-login`, `dashboard` тощо незалежно від локалі. Whitelist `PUBLIC_ROUTE_NAMES` (explicit array). Якщо `baseName` undefined (route не resolved) — fail-closed, treat as protected. Усі `navigateTo()` обгортаються `useLocalePath()`.
**Rationale:** Поточна перевірка `path.startsWith('/auth/')` не матчить `/en/auth/login`. Іменна перевірка стабільна для обох локалей — модуль автоматично генерує route names (`auth-login___en`, `auth-login___uk`), а `useRouteBaseName()` повертає базове ім'я без локалі. Fail-closed на undefined — захист від unmapped routes (наприклад catch-all 404), щоб не пропустити unauthed юзера.
**Alternatives considered:** (1) Strip prefix перед перевіркою — fragile, треба синхронізувати список локалей у двох місцях; (2) Залишити path-based + додати `/en/auth/`-перевірку — той самий fragility; (3) Fail-open на undefined — security-неприйнятно.
**Anchored to:** AC10 (auth middleware за іменем роуту), Risk "Auth middleware path-check ламається на префікс".

### Decision 7: Eager-load обох локалей
**Decision:** Локалі `uk.json` + `en.json` завантажуються одразу при старті (не lazy chunks).
**Rationale:** 2 файли по ~10KB — не варто async-chunks. Lazy-loading додає ще одне network round-trip + complications для SSR cookie matching. Eager-load забезпечує клієнтську доступність обох локалей одразу — switching без flicker.
**Alternatives considered:** Lazy-loading (`lazy: true`) — менший initial bundle на ~10KB, але flicker при перемиканні + більш складний hydration.
**Anchored to:** user-spec "Технічні рішення" (eager-load обох локалей), Risk "Hydration mismatch".

### Decision 8: Locale files структура — nested JSON, camelCase ключі, namespaces по фічах
**Decision:** Top-level namespaces: `auth`, `dashboard`, `profile`, `layout`, `validation`, `errors`, `common`, `brand`. Ключі camelCase англійською (`errors.login.tooManyAttempts`).
**Rationale:** Namespacing запобігає конфліктам ключів між фічами. CamelCase англомовні ключі узгоджені з рештою TS-кодбази. Окремий `validation` namespace для Zod-повідомлень — зрозумілий лінк між схемою і ключем.
**Alternatives considered:** (1) Flat keys — нечитко при великій кількості ключів; (2) ICU MessageFormat — оверкілл для MVP без plural/format потреб.
**Anchored to:** AC2 (однаковий набір ключів між локалями), user-spec "Технічні рішення".

### Decision 9: Build-time валідація локалей через `scripts/check-locales.mjs` + `prebuild`
**Decision:** Скрипт парсить обидва JSON, рекурсивно збирає key-set через `Object.create(null)` (no prototype-pollution), фільтрує небезпечні ключі (`__proto__`, `constructor`, `prototype`), порівнює key-sets, exit(1) при невалідному JSON, відсутньому файлі, або розбіжності. Запуск через npm-script `prebuild` (npm автоматично виконує перед `build`). У production-mode конфігу Vue I18n додатково виставляємо `silentTranslationWarn: true` і `silentFallbackWarn: true` — інакше `[intlify]` warnings з missing-key paths можуть лікнути у production logs/console.
**Rationale:** AC19 вимагає, щоб білд провалився при відсутньому/пошкодженому JSON. Парсинг ловиться сам по собі, але "однаковий набір ключів" (AC2) потребує окремого валідатора. Один node-script на ~40 LOC покриває обидва AC. Захист від prototype pollution критичний — JSON може містити `"__proto__": { ... }`, що при наївному `Object.assign`-стилі обходу заразить prototype chain. Silent prod warnings — defense-in-depth: build gate ловить divergence, але runtime warning suppression уникає будь-якого info disclosure через console.
**Alternatives considered:** (1) Тільки runtime fallback — не fail-fast; (2) Vitest test — не виконується у `pnpm build`; (3) Custom Nuxt module — оверкілл.
**Anchored to:** AC19 (build падає на missing/corrupted JSON), AC2 (однаковий набір ключів).

### Decision 10: `useApiError` factory composable з `te()` fallback chain
**Decision:** `composables/useApiError.ts` експортує factory: `useApiError()` викликається у `<script setup>` (правильний lifecycle для `useI18n()`), повертає handler `(error: unknown, contextKey: string) => string`. Handler extract status з `e?.statusCode ?? e?.status ?? e?.response?.status`. Lookup через `te()` (translation exists check): спочатку `errors.{contextKey}.{status}`, потім `errors.{contextKey}.generic`, потім `errors.generic`. `t()` викликається тільки після `te()`-confirmation — інакше `vue-i18n` падає на missing key у production-mode.
**Rationale:** Без factory pattern composable викликався б всередині catch-блоку — а `useI18n()` вимагає `<script setup>` lifecycle, інакше throw "called outside of setup()". `te()` checks замість blind `t()` — обхідний шлях для production-mode де `vue-i18n` мовчки повертає key-string без warning, що порушило б UX.
**Alternatives considered:** (1) Composable, що приймає `error` напряму — ламається на lifecycle; (2) Залишити inline catch у кожній сторінці — 8 копій бойлерплейту; (3) Pinia store — overkill, error mapping не є state; (4) Без `te()` — risk silent fallthrough на key-string.
**Anchored to:** AC13 (API-помилки локалізовані через спільний composable).

### Decision 11: Zod-схеми у `computed()` для re-run при зміні локалі
**Decision:** Кожна форма обгортає Zod-схему у `computed(() => z.object({...}))` через `toTypedSchema(unref(schemaComputed))`. При зміні `locale.value` schema перебудовується — повідомлення валідації одразу у новій мові.
**Rationale:** `t()` повертає рядок, що оцінюється eagerly при construct-time Zod-схеми. Без `computed` схема "запікає" UA-повідомлення у замиканні; live-switch на EN не оновить тексти.
**Alternatives considered:** (1) Re-mount компонента при зміні локалі — ламає UX (форма скидається); (2) Manual re-validate після setLocale — fragile.
**Anchored to:** AC12, Risk "Zod live-switch leaves stale messages".

### Decision 12: LangSwitcher викликає `setLocale(code)` (auto-navigate)
**Decision:** При кліку компонент викликає `await setLocale(code)`. Під strategy `prefix_except_default` v9 `setLocale` робить auto-navigate на новий локалізований path, оновлює cookie і locale state. Текстовий `UK | EN` toggle (не dropdown).
**Rationale:** v9 canonical lang-switcher pattern — `setLocale(code)` standalone (verified via Context7 `/websites/i18n_nuxtjs`). Розділяти це на explicit `switchLocalePath` + `navigateTo` — нерекомендовано і дублює внутрішню роботу модуля. Текстовий toggle (не dropdown) — для 2 мов dropdown надмірний, toggle коротший.
**Alternatives considered:** (1) Explicit `navigateTo(switchLocalePath(code))` після `setLocale` — redundant (auto-navigate вже виконується), додає race-condition surface; (2) `<SwitchLocalePathLink>` built-in — вимагає окремих елементів для кожної локалі, не вписується у `UK | EN` toggle із візуально активним станом.
**Anchored to:** AC9 (текстовий toggle UK | EN, клік перемикає атомарно: cookie + URL + `<html lang>` + контент).

### Decision 13: Brand "Bot Funnel" не локалізується (constant)
**Decision:** Однаковий ключ `brand.name` у обох локалях зі значенням `"Bot Funnel"`.
**Rationale:** Proper noun — не перекладається. Тримати у `t('brand.name')` дозволяє пізніше додати локалізовані варіанти без рефакторингу. Альтернатива (hardcoded string) порушує AC2.
**Alternatives considered:** Hardcoded string — порушує AC2.
**Anchored to:** AC2, user-spec "Brand 'Bot Funnel' не перекладаємо".

### Decision 14: Widen `routeRules` до `/en/auth/**` для security headers
**Decision:** Дублюємо ключ `'/en/auth/**'` у `routeRules` з тими самими headers (`X-Robots-Tag: noindex, nofollow`, `Referrer-Policy: no-referrer`).
**Rationale:** `'/auth/**'` glob не матчить `/en/auth/login?token=...`. Без widening EN-юзер пройде verify/reset через незахищений URL. AC8 вимагає обидва патерни.
**Alternatives considered:** (1) Регекс — Nuxt routeRules підтримує glob, не regex; (2) Server middleware, що ставить headers — складніше, легше пропустити.
**Anchored to:** AC8, Risk "Security regression на `/en/auth/**`".

### Decision 15: Layouts placement для LangSwitcher
**Decision:** У `default.vue` (топбар) — праворуч від `Logout` у тому ж flex-row. У `auth.vue` (фон з центральною карткою) — `position: absolute; top: 1rem; right: 1rem; z-index: 10` поверх фону.
**Rationale:** Топбар має готовий flex-row контейнер; auth-сторінки без topbar — absolute-position у куток забезпечує доступність без впливу на main-card layout.
**Alternatives considered:** (1) Footer — ховає toggle; (2) Modal/dropdown — overkill для одного toggle.
**Anchored to:** AC9 (toggle у обидва layouts).

### Decision 16: Existing tests UA-літерали залишаються; виняток — `auth.global.spec.ts`
**Decision:** Існуючі Vitest spec-файли не модифікуємо — UA-літерали в asserts (`/коректний email/i`) залишаються. Виняток: `tests/middleware/auth.global.spec.ts` — оновлюємо, бо middleware-логіка змінюється на іменну перевірку (Decision 6).
**Rationale:** Default локаль `uk` не змінюється; `localePath('/auth/login') === '/auth/login'` для uk; UA-літерали проходять без змін. Auth-middleware тести описують саму middleware-логіку — їх треба перевиписати під locale-aware redirects.
**Alternatives considered:** Переписати всі тести під `t()` mock — велика робота без додаткової value.
**Anchored to:** AC18 (з explicit вийнятком `auth.global.spec.ts`), Risk "Test brittleness".

### Decision 17: Playwright bootstrap — окрема Wave-1 задача (solo)
**Decision:** Установка `@playwright/test` як devDep, створення `playwright.config.ts`, npm-script `test:e2e`, директорії `e2e/`, оновлення `frontend/.gitignore` — окремою задачею у Wave 1 solo. Wave 2 (i18n bootstrap) запускається після.
**Rationale:** І Task 1 (Playwright), і Task 2 (i18n) модифікують `frontend/package.json`. Паралельне виконання дало б merge-конфлікт у dependencies/scripts. Серіалізація через окремі Waves — найпростіше рішення без додаткової координації.
**Alternatives considered:** (1) Bundle Playwright + i18n у одну задачу — велика, важко review; (2) Pre-write package.json patches — fragile.
**Anchored to:** AC16 (Playwright bootstrap), Wave conflict prevention.

## Data Models

N/A — фіча не торкається DB schemas чи backend interfaces. Локаль-словники — JSON-файли структурованої форми, описаної у Decision 8.

## Dependencies

### New packages

- `@nuxtjs/i18n@^9` — Nuxt 4 + Vue I18n 11 інтеграція; auto-routing, SSR cookie, locale-aware navigation helpers
- `@playwright/test@^1.49` — E2E test runner (devDep)

### Using existing (from project)

- `vee-validate@^4` + `zod@^3` — Zod-схеми обгорнемо у `computed()` для locale-reactivity
- `pinia` + auth store — без змін
- `@tanstack/vue-query` — без змін
- `@nuxtjs/tailwindcss` — utility classes для LangSwitcher styling

## Testing Strategy

**Feature size:** M

### Unit tests

- `composables/useApiError.test.ts` — factory повертає handler; handler повертає правильний ключ для 401/403/429; fallback chain через `te()`: `errors.{context}.{code}` → `errors.{context}.generic` → `errors.generic`; extraction з різних форм error-об'єкта (`statusCode`, `status`, `response.status`, undefined → generic). Mock `useI18n` (з `t` і `te`) через `mockNuxtImport`.
- `components/LangSwitcher.spec.ts` — рендер обох мов, активна виділена; click на EN → асерції на observable behavior: `setLocale('en')` був викликаний з правильним аргументом, `useCookie('i18n_lang').value === 'en'` після click (per AC15 "cookie оновлений" — ця асерція явно вимагається user-spec'ом). Mock `useI18n().setLocale` як spy. Cookie через mocked `useCookie` — асертимо value, не deep mock-плумбінг.
- `tests/middleware/auth.global.spec.ts` (оновлення) — нові кейси: `auth-login` для unauthed → passthrough; `dashboard` для unauthed → redirect на `localePath('/auth/login')`; `auth-login` для authed → `localePath('/dashboard')`; те саме для en-локалі (mock `useRouteBaseName()` повертає `auth-login`/`dashboard`, mock `useLocalePath` повертає `/en/auth/login`/`/en/dashboard`); fail-closed на `getBaseName` undefined → treated as protected.
- `tests/server/strip-invalid-locale.spec.ts` (новий) — pure-function unit на decision-логіку: invalid prefix `/fr/dashboard` → redirect `/dashboard`; `/uk/dashboard` → redirect `/dashboard`; `/en/dashboard` → passthrough; `/dashboard` → passthrough; auth-zone `/fr/auth/verify-email?token=x` → redirect `/auth/verify-email?token=x` + headers; нормалізація `/fr//evil.com` → `/evil.com` (collapse leading slashes); backslash `/fr/\\evil.com` → reject (treat as invalid).
- `scripts/check-locales.test.mjs` (новий, опційний) — node:test або vitest: parse OK; missing file → exit 1; invalid JSON → exit 1; key-divergence (uk has key X, en doesn't) → exit 1; prototype-pollution payload `{"__proto__": {...}}` → safe (does not pollute shared prototype).

### Integration tests

None — фіча 100% frontend, без backend/DB зачіпки.

### E2E tests

- `e2e/i18n.spec.ts` — один сценарій: open `http://localhost:3000/auth/login` → URL без префікса, button text "Увійти", `<html lang>="uk"` → click `EN` у LangSwitcher → URL `/en/auth/login`, button text "Sign in", `<html lang>="en"` → fill email "invalid" + click submit → EN-validation message ("Invalid email format" або equivalent з en.json). Покриває golden-path перемикання, locale routing, Zod live-switch, `<html lang>` оновлення.

## Agent Verification Plan

**Source:** user-spec "Як перевірити" section + Implementation Tasks Verify-smoke / Verify-user.

### Verification approach

Per-task smoke-checks (`Verify-smoke` / `Verify-user`) виконуються агентом одразу після implementation окремої задачі. Final verification (Pre-deploy QA) запускає повний test suite + acceptance criteria walkthrough на dev-сервері.

Key verifications після усіх waves (з user-spec "Як перевірити"):
1. `cd frontend && pnpm test` — всі Vitest specs зелені.
2. `cd frontend && pnpm test:e2e` — Playwright E2E проходить.
3. `cd frontend && pnpm dev` + curl-перевірки 8 сценаріїв з user-spec (302-редиректи, headers, cookie, build-fail).
4. `cd frontend && rm i18n/locales/en.json && pnpm build` — build падає на prebuild script (відновити файл після перевірки).

### Tools required

- bash + curl — server-side smoke checks (302 redirect, headers, build fail)
- Vitest (`pnpm test`) — unit tests
- Playwright (`pnpm test:e2e`) — E2E + `<html lang>` assertion
- Manual dev-server check для UX (LangSwitcher placement, layout перемикання)

Post-deploy verification — N/A (немає production environment, deploy не у scope).

## Risks

| Risk | Mitigation |
|------|-----------|
| Security regression на `/en/auth/**` (індексація `?token=` URLs) | Decision 14 widen routeRules. Wave-2 task smoke: `curl -I localhost:3000/en/auth/verify-email?token=x` |
| 302 redirect leaks `?token=` через Referer на проміжній відповіді (`/fr/auth/verify-email?token=x`) | Decision 5 — middleware виставляє headers на сам 302-response для auth-zone |
| Open-redirect через path-маніпуляції у strip-invalid-locale (`/fr//evil.com`) | Decision 5 — path normalization (collapse slashes, decode percent, reject backslash); unit-test fixtures |
| `useApiError` викликаний у catch-блоці без factory pattern → "useI18n outside setup()" | Decision 10 — factory pattern, composable runs у `<script setup>` |
| Auth middleware path-check ламається на префікс — EN-неавторизований не отримує редирект | Decision 6 — `useRouteBaseName()`, fail-closed на undefined |
| Race-condition між cookie-write і navigation у LangSwitcher | Decision 12 — `await setLocale(code)` (canonical v9 pattern, atomic у модулі) |
| Zod live-switch leaves stale UA messages | Decision 11 — schemas у `computed()` |
| Hardcoded paths у 20 місцях (12 NuxtLink + 8 navigateTo) — без префікса для en | Wave 4 заміняє на `<NuxtLinkLocale>` + `localePath()`. Code-review шукає residual hardcoded paths |
| Nuxt 4 path convention для `langDir` (під `restructureDir: 'i18n'`) | Локалі живуть у `frontend/i18n/locales/`. Wave-2 task експліцитно фіксує `langDir: 'locales'` |
| Hydration mismatch при різних cookie/Accept-Language | SSR plugin модуля резолвить локаль до рендеру; eager-load обох локалей |
| Prototype pollution через `__proto__` ключ у локаль-JSON | Decision 9 — `Object.create(null)`, фільтр небезпечних ключів |
| Якість EN-копірайту (агент пише, не носій) | Консервативно-формальний тон для CTA + error messages. Post-launch правки тільки JSON |
| Build не ловить missing key між uk і en | Decision 9 — `scripts/check-locales.mjs` + `prebuild`; unit-tests на key-divergence branch |

## User-Spec Deviations

- **Cookie TTL "1 рік" (user-spec "Перший візит без cookie", AC1):** user-spec вимагає TTL exactly 1 рік. Tech-spec relient на module default ~1 рік замість explicit `cookieMaxAge` config. Reason: `@nuxtjs/i18n@^9` `detectBrowserLanguage` не приймає `cookieMaxAge` як опцію (Context7-verified — Decision 3). Module default визначається у v9 source ~1 рік, що задовольняє user-spec вимогу approximately. Якщо точна тривалість критична — потрібен custom Nitro plugin що override-ує Set-Cookie header на response, що не виправдано для UI-preference cookie. → **[APPROVED 2026-05-08]**

## Acceptance Criteria

Технічні критерії приймання (доповнюють користувацькі з user-spec):

- [ ] `@nuxtjs/i18n@^9` і `@playwright/test` додані у `frontend/package.json`, lockfile оновлений
- [ ] `frontend/i18n/locales/uk.json` і `en.json` мають однаковий набір ключів (перевірено `scripts/check-locales.mjs`)
- [ ] `routeRules` у `nuxt.config.ts` має ключі обох патернів: `/auth/**` і `/en/auth/**` з ідентичними headers
- [ ] Cookie config explicit: `cookieKey: 'i18n_lang'`, env-conditional Secure (production), SameSite Lax (module default), TTL ~1y (module default)
- [ ] Усі `<NuxtLink to="...">` у feature scope замінені на `<NuxtLinkLocale to="...">`; усі `navigateTo()` обгорнуті у `localePath()`
- [ ] Жодних hardcoded UA/EN рядків (≥3 символів видимого тексту) у `frontend/{pages,layouts,components}/**/*.vue` поза викликами `t()`
- [ ] Vitest test suite проходить (мінімум: 9 існуючих + 4 нові: useApiError, LangSwitcher, strip-invalid-locale, check-locales; auth.global.spec.ts оновлений)
- [ ] Playwright E2E `e2e/i18n.spec.ts` проходить
- [ ] `pnpm build` успішно компілює; `rm i18n/locales/en.json && pnpm build` падає з зрозумілим повідомленням
- [ ] `useApiError` factory pattern: composable викликається у `<script setup>`, не у catch-блоці
- [ ] Spільний константний список non-default локалей у `nuxt.config.ts` використаний і у `strip-invalid-locale` middleware allow-list, і у `routeRules` ключах (parity захист)
- [ ] Vue I18n production-mode конфіг: `silentTranslationWarn: true` і `silentFallbackWarn: true` (no info disclosure через console)
- [ ] Pre-commit hooks (gitleaks) не блокують коміти

## Implementation Tasks

### Wave 1 (solo — Playwright bootstrap)

#### Task 1: Playwright bootstrap
- **Description:** Встановити `@playwright/test` як devDependency, створити `frontend/playwright.config.ts` з webServer-конфігом (`pnpm dev`, port 3000), додати npm-script `test:e2e`, створити директорію `frontend/e2e/`, створити/оновити `frontend/.gitignore` з ігноруванням `playwright-report/` і `test-results/`. Solo у Wave 1 щоб уникнути merge-конфлікту з Task 2 на `package.json` (per Decision 17).
- **Skill:** infrastructure-setup
- **Reviewers:** code-reviewer, security-auditor, infrastructure-reviewer
- **Verify-smoke:** `cd frontend && pnpm exec playwright --version` → друкує версію; `pnpm test:e2e --list` → "0 tests found" або equivalent (config валідний)
- **Files to modify:** `frontend/package.json`, `frontend/playwright.config.ts` (new), `frontend/e2e/.gitkeep` (new), `frontend/.gitignore` (new or modify)
- **Files to read:** `frontend/package.json`, repo-root `.gitignore`

### Wave 2 (паралельно — i18n foundation)

#### Task 2: i18n module bootstrap
- **Description:** Встановити `@nuxtjs/i18n@^9`, додати модуль у `nuxt.config.ts:modules`. Експортувати константу `NON_DEFAULT_LOCALES = ['en'] as const` у спільному файлі (`frontend/shared/i18n-locales.ts` або inline у `nuxt.config.ts` з re-export) — використовується і `routeRules`, і Task 3 middleware. Налаштувати блок `i18n` (locales `uk`/`en`, `defaultLocale: 'uk'`, `strategy: 'prefix_except_default'`, `langDir: 'locales'`, `detectBrowserLanguage` per Decision 4 з explicit `cookieKey: 'i18n_lang'`, env-conditional `cookieSecure`; `cookieMaxAge` НЕ передаємо — module default ~1y), створити `frontend/i18n.config.ts` з `legacy: false` + `fallbackLocale: 'uk'` + production-mode `silentTranslationWarn: true` + `silentFallbackWarn: true`, створити stub-файли `frontend/i18n/locales/uk.json` і `en.json` з мінімальним вмістом (`{"common": {"placeholder": "x"}}`). Widen `routeRules` — для кожної не-default локалі з `NON_DEFAULT_LOCALES` додати ключ `'/{code}/auth/**'` з тими ж headers (через map або spread).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd frontend && pnpm dev` + `curl -sI localhost:3000/en/auth/verify-email?token=x` → headers містять `X-Robots-Tag: noindex, nofollow` і `Referrer-Policy: no-referrer`; `curl -sI -H 'Accept-Language: en-US' localhost:3000/dashboard` → 302 на `/en/...` + Set-Cookie `i18n_lang=en`; `curl -sI -H 'Cookie: i18n_lang=xx' localhost:3000/dashboard` → не редиректить за невалідним cookie, fallback на default uk (AC6)
- **Files to modify:** `frontend/package.json`, `frontend/nuxt.config.ts`, `frontend/i18n.config.ts` (new), `frontend/i18n/locales/uk.json` (new stub), `frontend/i18n/locales/en.json` (new stub)
- **Files to read:** `frontend/nuxt.config.ts`, `frontend/package.json`, code-research.md §4, §13

#### Task 3: Server middleware — strip invalid/default-locale prefix
- **Description:** Створити `frontend/server/middleware/strip-invalid-locale.ts`. Імпортує allow-list з `NON_DEFAULT_LOCALES` константи (визначеної у Task 2 — спільне джерело правди для middleware і routeRules). Логіка per Decision 5: regex `^/([a-z]{2})(?=/|$)` витягує potential locale, перевіряє allow-list, normalization order — backslash reject (400) → strip prefix → collapse leading slashes → decode percent. Auth-zone match — `startsWith('/auth')` без trailing slash (ловить `/auth?token=x` теж). Декомпозувати pure-function decision-логіку у окремий exportable function для unit-test. Створити `tests/server/strip-invalid-locale.spec.ts` з кейсами per Testing Strategy.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd frontend && pnpm dev` + `curl -sI localhost:3000/fr/dashboard` → 302 location `/dashboard`; `curl -sI localhost:3000/uk/dashboard` → 302 location `/dashboard`; `curl -sI localhost:3000/en/dashboard` → 200 (валідний); `curl -sI localhost:3000/fr/auth/verify-email?token=x` → 302 на `/auth/verify-email?token=x` + headers `X-Robots-Tag` + `Referrer-Policy` на 302-response
- **Files to modify:** `frontend/server/middleware/strip-invalid-locale.ts` (new), `frontend/tests/server/strip-invalid-locale.spec.ts` (new)
- **Files to read:** code-research.md §4, §6

### Wave 3 (паралельно — після Wave 2)

#### Task 4: Локаль-словники + check-locales script
- **Description:** Заповнити `frontend/i18n/locales/uk.json` і `en.json` повним набором ключів покриваючи всі рядки з code-research §1. Структура namespaces — per Decision 8. UA-копія з existing файлів; EN-копія консервативно-формальним тоном. Створити `frontend/scripts/check-locales.mjs` per Decision 9 (включно з `Object.create(null)` і фільтром небезпечних ключів). Додати npm-script `prebuild` у `package.json`. Створити мінімальний `scripts/check-locales.test.mjs` (опційно, через node:test) — кейси per Testing Strategy.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd frontend && node scripts/check-locales.mjs` → exit 0; видалити рандомний ключ з `en.json` → `node scripts/check-locales.mjs; echo $?` → 1; видалити файл `en.json` → exit 1; restore. `pnpm build` проходить
- **Files to modify:** `frontend/i18n/locales/uk.json`, `frontend/i18n/locales/en.json`, `frontend/scripts/check-locales.mjs` (new), `frontend/scripts/check-locales.test.mjs` (new, optional), `frontend/package.json` (prebuild script)
- **Files to read:** code-research.md §1, §2, §11, existing pages для дослівної UA-копії

#### Task 5: useApiError factory composable
- **Description:** Створити `frontend/composables/useApiError.ts` per Decision 10 — factory повертає handler, всередині factory виклик `useI18n()` для коректного lifecycle, fallback chain через `te()`. Vitest spec покриває fallback gradient + extraction з різних error-форм.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `frontend/composables/useApiError.ts` (new), `frontend/tests/composables/useApiError.spec.ts` (new)
- **Files to read:** code-research.md §2

#### Task 6: LangSwitcher component + Vitest
- **Description:** Створити `frontend/components/LangSwitcher.vue` per Decision 12 — текстовий toggle, click-handler викликає `await setLocale(code)` (auto-navigate canonical pattern). Vitest spec per Testing Strategy — асерції на `setLocale` call + cookie value (per AC15). Активна мова виділена Tailwind класами.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** `pnpm dev` → open `localhost:3000/auth/login` → клік EN → URL стає `/en/auth/login`, активна мова перемкнулась візуально
- **Files to modify:** `frontend/components/LangSwitcher.vue` (new), `frontend/tests/components/LangSwitcher.spec.ts` (new)
- **Files to read:** code-research.md §13

#### Task 7: Auth middleware locale-aware refactor
- **Description:** Оновити `frontend/middleware/auth.global.ts` per Decision 6: `useRouteBaseName()` composable, `PUBLIC_ROUTE_NAMES` whitelist, fail-closed на undefined baseName, `useLocalePath()` обгортає всі `navigateTo()`. Оновити `tests/middleware/auth.global.spec.ts` per Testing Strategy. Mock `useRouteBaseName` і `useLocalePath` через `mockNuxtImport`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `frontend/middleware/auth.global.ts`, `frontend/tests/middleware/auth.global.spec.ts`
- **Files to read:** code-research.md §6, current `frontend/middleware/auth.global.ts`, current `tests/middleware/auth.global.spec.ts`

### Wave 4 (паралельно — після Wave 3)

#### Task 8: app.vue + layouts (default + auth)
- **Description:** Оновити `frontend/app.vue`: `useLocaleHead({ seo: true })` (правильна v9 опція) + `useHead` прокидування. Оновити `frontend/layouts/default.vue`: `t()` для всіх hardcoded рядків (Logout/Dashboard/Profile), brand → `t('brand.name')`, `<LangSwitcher />` у топбар, `<NuxtLink>` → `<NuxtLinkLocale>`, `navigateTo` обгорнуто `localePath()`. Оновити `frontend/layouts/auth.vue`: додати `<LangSwitcher />` absolute top-right.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** open `localhost:3000/dashboard` (залогіненим) — топбар з Logout + LangSwitcher; open `localhost:3000/auth/login` — LangSwitcher у правому верхньому куті
- **Files to modify:** `frontend/app.vue`, `frontend/layouts/default.vue`, `frontend/layouts/auth.vue`
- **Files to read:** code-research.md §1.1, §1.2

#### Task 9: Auth pages translation (5 files)
- **Description:** Перевести `pages/auth/{login,register,forgot-password,reset-password,verify-email}.vue`: всі hardcoded UA-рядки → `t()`, Zod-схеми у `computed()` per Decision 11, `useApiError()` factory у setup + handler у catch-блоках, `<NuxtLink>` → `<NuxtLinkLocale>`, `navigateTo()` обгорнуто `localePath()`. verify-email cooldown — `t('auth.verifyEmail.cooldown', { seconds })` interpolation.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd frontend && pnpm test tests/pages/auth/` → existing specs (UA-локаль) проходять без змін
- **Verify-user:** open `localhost:3000/auth/login` → click EN → URL `/en/auth/login`, button "Sign in", невалідний email → EN-повідомлення; повторити для register, forgot-password, reset-password, verify-email
- **Files to modify:** `frontend/pages/auth/login.vue`, `frontend/pages/auth/register.vue`, `frontend/pages/auth/forgot-password.vue`, `frontend/pages/auth/reset-password.vue`, `frontend/pages/auth/verify-email.vue`
- **Files to read:** code-research.md §1.3, §2, §10, `frontend/i18n/locales/uk.json` + `en.json`, `frontend/composables/useApiError.ts`

#### Task 10: App pages translation (dashboard + profile)
- **Description:** Перевести `pages/dashboard.vue` (banner, welcome line з conditional name, description) і `pages/profile.vue` (5 секцій, manual validation messages, Zod schema у `computed`, `useApiError` для catch-блоків, modal labels, interpolated cooldowns). NuxtLink → NuxtLinkLocale, navigateTo обгорнуто localePath.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd frontend && pnpm test tests/pages/dashboard.spec.ts tests/pages/profile.spec.ts` → existing specs проходять
- **Verify-user:** open `localhost:3000/dashboard` → click EN → банер, welcome, link перекладені; open `localhost:3000/profile` → всі 5 секцій перекладені
- **Files to modify:** `frontend/pages/dashboard.vue`, `frontend/pages/profile.vue`
- **Files to read:** code-research.md §1.4, `frontend/i18n/locales/uk.json` + `en.json`, `frontend/composables/useApiError.ts`

### Wave 5 (sequential — depends on Wave 4)

#### Task 11: Playwright E2E i18n spec
- **Description:** Створити `frontend/e2e/i18n.spec.ts` per Testing Strategy. Один спека, ~30 LOC. Webserver через playwright.config (auto-spawn). Залежить від Wave 4 — pages мають бути локалізовані щоб assertions матчили.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `cd frontend && pnpm test:e2e e2e/i18n.spec.ts` → 1 passed; `pnpm test:e2e e2e/i18n.spec.ts --list` → друкує правильну назву тесту
- **Files to modify:** `frontend/e2e/i18n.spec.ts` (new)
- **Files to read:** `frontend/i18n/locales/en.json`, `frontend/playwright.config.ts`

### Audit Wave (паралельно — sequential after Wave 5)

<!-- Audit виконується після всіх implementation waves. Аудитори читають фінальний код feature scope. -->


#### Task 12: Code Audit
- **Description:** Full-feature code quality audit. Прочитати усі source-файли створені/змінені у цій фічі. Holistic review: residual hardcoded UA/EN рядки, дубльовані patterns, відповідність до Decisions, всі `<NuxtLink>` замінено, всі `navigateTo()` обгорнуто. Записати audit-report у `logs/audit/code-audit.json`.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 13: Security Audit
- **Description:** Full-feature security audit (OWASP Top 10) з фокусом на: cookie-flags parity, security headers parity на `/en/auth/**`, 302 headers preservation у strip-invalid-locale, open-redirect нормалізація path, prototype-pollution safety у check-locales, `?token=` URLs не leakуються через Referer у обох локалях, fail-closed semantics в auth middleware. Записати у `logs/audit/security-audit.json`.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 14: Test Audit
- **Description:** Full-feature test audit. Прочитати всі test-файли. Перевірити: meaningful assertions, pyramid balance (4 нові unit + 1 оновлений middleware + 1 E2E — відповідає M-feature), coverage критичних шляхів per Testing Strategy, AC18 compliance (existing specs не мутовані крім authorized exception). Записати у `logs/audit/test-audit.json`.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 15: Pre-deploy QA
- **Description:** Acceptance testing: запустити повний test suite (Vitest + Playwright), пройти всі AC з user-spec (19 пунктів) + tech-spec (11 додаткових). Перевірити usability на dev-сервері (manual cookie state checks через DevTools для AC3-AC6). Записати QA-report у `logs/qa/pre-deploy-qa.json`.
- **Skill:** pre-deploy-qa
- **Reviewers:** none

<!-- Deploy і Post-deploy verification — out of scope. CI/CD не існує (per deployment.md), фіча — тільки frontend bundle. user-spec обмеження: "Деплой: жодних змін у CI/CD (його ще нема)". -->
