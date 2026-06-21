---
created: 2026-05-07
status: approved
type: feature
size: M
---

# User Spec: 03 — i18n (українська + англійська)

## Що робимо

Додаємо до Nuxt 4 фронтенду повноцінну i18n: дві локалі — українська (default) та англійська. URL: `uk` без префікса, `en` з префіксом (`/en/...`). Локаль зберігається в cookie на 1 рік, детектиться у порядку URL → cookie → Accept-Language браузера → default uk. Усі видимі рядки фронтенду виносимо у локаль-файли, додаємо текстовий перемикач `UK | EN` у обидва layouts. Бекенд (email-шаблони, API error responses) не зачіпаємо — лишається українською.

## Навіщо

`ux-guidelines.md` від MVP вимагає `t('key')` для всіх UI-рядків, але реальний код цього не дотримується: рядки забиті прямо у `.vue` (auth-сторінки UA, default layout — англ). Зараз frontend малий — ~10 файлів, ~150 рядків копірайту, переклад дешевий і робиться за один прохід. Через 5 епіків це 50+ файлів, переклад стане дорогим. Фіча закладає інфраструктуру одразу та виконує гайдлайн — наступні epic не зможуть випадково хардкодити рядки. Окремо: EN-локаль розблоковує демо/скріншоти потенційним англомовним early users та інвесторам, поки реальних користувачів немає.

## Як повинно працювати

### Перший візит без cookie

1. Браузер з `Accept-Language: en*` → редірект на EN-варіант шляху (`/en/...`), cookie з мовою `en` ставиться.
2. Браузер з `Accept-Language: uk*` або без заголовка → uk без префікса, cookie з мовою `uk`.
3. Cookie живе 1 рік, оновлюється при кожному явному виборі.

### Перемикання мови

1. У topbar default layout (для авторизованих) праворуч від avatar/Logout — текстовий toggle `UK | EN`, активна мова виділена.
2. У auth layout (для гостей — `/auth/*`) — той самий toggle, абсолютно позиційований у правому верхньому куті фону (поверх центральної картки).
3. Клік атомарно: оновлює cookie, навігує на новий URL (додає або забирає префікс `/en`), оновлює `<html lang>`. Без toast/loading-flash.
4. Стан незбереженої форми скидається при перемиканні (рідкісна дія, warning не показуємо).

### URL і shared посилання

1. `uk` (default) не має префікса: `/auth/login`, `/dashboard`, `/profile`. Існуючі UA-bookmarks залишаються валідними.
2. `en` має префікс: `/en/auth/login`, `/en/dashboard`, `/en/profile`.
3. Поділитися EN-посиланням можна напряму — на новому пристрої без cookie рендериться EN, cookie ставиться у `en`.
4. Повторний візит EN-юзера на uk-URL (`/dashboard`) → редірект на `/en/dashboard` (cookie каже `en` → редирект на префікс).

### Невалідний префікс у URL

1. `/fr/dashboard` (мова не з нашого списку) → стрипається невалідний 2-літерний префікс → 302 на `/dashboard` (default uk). Краще ніж 404 для UX (юзер міг помилитись з префіксом).
2. Перевірка робиться раніше за auth-перевірки, щоб 302 був стабільним для будь-якого стану авторизації.

### Помилки API і валідації

1. Усі catch-блоки на сторінках, що мапили HTTP status у текст (8 копій), використовують спільний composable, який повертає локалізований текст з namespace `errors.{context}.{code}` і fallback на загальне `errors.generic`.
2. Zod-схеми форм перебудовуються при зміні локалі — повідомлення валідації одразу у вибраній мові.
3. Якщо ключа нема в `en` → fallback на `uk` + `console.warn` у dev. Якщо нема і в `uk` (це edge case — треба не пропустити) → бібліотека показує raw key.

### Edge cases

- **Невалідне значення cookie** (`i18n_lang=xx` де `xx` не з нашого списку): значення ігнорується, детекція продовжує через Accept-Language → default uk.
- **Файл локалі відсутній або пошкоджений (невалідний JSON)** на момент білду: `pnpm build` падає, CI ловить (build fails fast).
- **Hydration mismatch:** SSR резолвить локаль до рендеру з cookie + Accept-Language; клієнт стартує з тими ж рядками — без warning'ів і стрибків розмітки.

### Що локалізуємо

5 auth-сторінок (login, register, forgot-password, reset-password, verify-email), dashboard, profile, default + auth layouts, API error mappings, Zod-повідомлення, pending-user banner на dashboard. Brand "Bot Funnel" не перекладаємо (proper noun).

## Критерії приймання

- [ ] Офіційний Nuxt-модуль i18n підключено та сконфігуровано: дві локалі (uk default, en), URL-стратегія "default без префікса, en з префіксом", детекція через cookie + Accept-Language з fallback на default. Cookie у prod встановлюється з flag Secure (HTTPS-only) та SameSite Lax.
- [ ] Локаль-файли uk + en мають однаковий набір ключів. Жодного hardcoded видимого рядка ≥3 символів у `pages/`, `layouts/`, `components/` поза викликами `t()`.
- [ ] Перший запит без cookie з `Accept-Language: en` → редірект на `/en/...`, cookie ставиться в `en`.
- [ ] Перший запит без cookie з UA або без `Accept-Language` → uk без префікса, cookie `uk`.
- [ ] Повторний запит EN-юзера на uk-шлях (наприклад `/dashboard`) → 302 на `/en/dashboard`.
- [ ] Невалідне значення cookie (`i18n_lang=xx` поза списком) → ігнорується, детекція через Accept-Language.
- [ ] `/fr/dashboard` (невалідний 2-літерний префікс) → 302 на `/dashboard`. Виконується до auth-redirect.
- [ ] `/auth/**` AND `/en/auth/**` отримують `X-Robots-Tag: noindex, nofollow` + `Referrer-Policy: no-referrer` — `?token=` URLs захищені обома локалями.
- [ ] Текстовий toggle `UK | EN` у default layout topbar (праворуч біля Logout) і auth layout (top-right фону). Активна мова візуально виділена. Клік перемикає атомарно: cookie + URL + `<html lang>` + контент.
- [ ] Auth middleware визначає public/protected роути за іменем роуту (через хелпер з i18n-модуля), не за стрічковим префіксом шляху — працює для обох локалей.
- [ ] Усі внутрішні навігації (NuxtLink + navigateTo) локаль-сумісні — після перемикання на en усі лінки ведуть на `/en/...`.
- [ ] Zod-валідація-повідомлення перебудовуються при зміні локалі.
- [ ] API-помилки на формах локалізовані (401/403/429/generic) — однаково обробляються через спільний composable.
- [ ] `<html lang>` оновлюється при перемиканні (root `app.vue` явно прокидує SEO-атрибути локалі у `useHead`).
- [ ] Vitest спека на компонент перемикача: рендер обох мов, клік перемикає, cookie оновлений.
- [ ] Playwright встановлений як devDep, є `playwright.config.ts`, npm-script `test:e2e`, директорія `e2e/`, ігноруємо артефакти у `.gitignore`. Налаштування виноситься як окрема Wave-1 задача — Playwright у проекті ще нема.
- [ ] Один Playwright E2E: open `/auth/login` → клік EN → URL `/en/auth/login`, кнопка стає "Sign in", відправка з невалідним email показує EN-повідомлення.
- [ ] Існуючі frontend spec-файли не змінюються — default локаль uk, asserts проходять.
- [ ] Build падає, якщо локаль-JSON відсутній або пошкоджений (валідація на pnpm build).

## Обмеження

- **Тільки 2 мови:** uk + en. Ru, інші — поза скоупом.
- **Backend без змін:** email-шаблони, error responses — українською. Окрема фіча, якщо знадобиться.
- **`<title>` через `useHead`** — поза скоупом. Зараз жодна сторінка не задає `<title>`. Локалізація tags — окремий refactor.
- **Pluralization API не вмикаємо:** у поточному UI нема лічильників. Додамо при потребі у наступних epic.
- **Date/number formatters не торкаємось:** у поточному auth/dashboard/profile нема дат/чисел для форматування.
- **Brand "Bot Funnel" не перекладається.**
- **Існуючі тести лишаються:** UA-літерали в asserts (`/коректний email/i`) працюють, бо default локаль uk не змінюється.
- **Деплой:** жодних змін у CI/CD (його ще нема), жодних env vars не додаємо. Зміна тільки у frontend bundle.
- **Опційний lint-gate (grep raw strings)** не входить в AC: CI зараз нема, тому реалізується або через pre-commit hook, або відкладається до появи CI. У scope цієї фічі — тільки локаль-перенесення, gate декларується як "nice to have" у тех-спеку.
- **Sizing:** фіча позначена як **M**, не L, попри ~19 acceptance criteria. Підстава: одна бібліотека, виключно frontend, без backend / DB / migrations / нової інфраструктури. Більшість AC — це чіткі behavioral checks навколо однієї точки інтеграції (`@nuxtjs/i18n` модуль).

## Ризики

- **Security regression на `/en/auth/**`.** `routeRules` зараз ставлять `X-Robots-Tag: noindex` і `Referrer-Policy: no-referrer` на `/auth/**`, щоб `?token=` URLs не індексувались і не текли через Referer. Якщо забути додати дзеркало для `/en/auth/**`, EN-юзер пройде verify/reset через незахищений URL. **Митигація:** AC окремо вимагає обидва патерни. Smoke-тест на CI/PR — `curl -I` обох URL.
- **Auth middleware path-check ламається на префікс.** Поточна перевірка прийому за стрічковим префіксом не матчить `/en/auth/...`, тому EN-неавторизований юзер не отримає редірект. **Митигація:** перейти на іменну перевірку роуту, яку дає бібліотека (`getRouteBaseName`) — вона повертає логічну назву незалежно від локалі.
- **Zod live-switch leaves stale messages.** Схеми будуються один раз при імпорті модуля — повідомлення запікаються у замиканні. **Митигація:** обгорнути всі схеми у `computed()` — вони перебудовуються при зміні `locale`.
- **Hardcoded paths у 20 місцях** (12 NuxtLink + 8 navigateTo) дають правильний URL для uk, але не додають префікс для en. **Митигація:** замінити на локаль-сумісні навігації під час того ж проходу по файлах.
- **Якість EN-копірайту.** Агент пише EN-локаль під час імплементації, не носій мови — можливі awkward phrasings. **Митигація:** дрібні виправлення можна робити post-launch без зміни коду — лише `en.json`. Для критичних рядків (error messages, кнопки CTA) тримаємо консервативно-формальний тон. Якщо post-launch виявиться що EN потребує редакторського проходу — це окрема дешева задача (правити тільки JSON).
- **Nuxt 4 path convention.** `@nuxtjs/i18n@9` під Nuxt 4 з default `restructureDir: 'i18n'` шукає локалі під директорією, що відрізняється від звичної у Nuxt 3. **Митигація:** перший smoke-тест у dev-server після інсталяції — переконатись що локалі підхопилися; правильний шлях зафіксувати явно у конфігу.
- **Hydration mismatch при різних значеннях cookie/Accept-Language.** Якщо клієнт стартує з cookie `en`, а SSR прорендерив uk (бо не врахував cookie) — Vue дасть warning. **Митигація:** SSR розв'язує локаль з cookie + Accept-Language до рендеру; eager-load обох локалей забезпечує що клієнт має рядки одразу.

## Технічні рішення

- **Офіційний Nuxt i18n-модуль (а не голий vue-i18n).** Модуль дає auto-routing з префіксами, locale-aware навігацію, SSR cookie integration і помічники для `<html lang>` коробкою. Без модуля все це треба було б ручкати — мінус значна частина точок збою.
- **URL: default без префікса, en з префіксом.** uk-bookmarks лишаються валідні; en-посилання діляться нормально (SEO + sharing). Альтернатива "обидві без префікса" дала б простіший конфіг ціною втрати sharing-семантики — відкинули.
- **Cookie, не localStorage.** SSR має визначити мову до першого рендеру; localStorage недоступний на сервері.
- **Detection: URL > cookie > Accept-Language > default uk.** Стандартний пріоритет. Cookie повертає юзера в його мову; URL завжди пере-перемагає (sharing).
- **Cookie конфіг безпечний для prod:** Secure флаг ставиться лише на HTTPS (env-conditional), SameSite Lax — не блокує GET-навігацію. Без env-conditional у dev на HTTP localhost браузер cookie не зберігав би.
- **Редирект EN-юзера на префіксований URL працює на будь-якому шляху** (не тільки на корені). Повторний EN-юзер, що відкриває uk-URL, потрапляє на en-варіант. Конфіг "тільки на корені" відкинули — UX непослідовний.
- **Fallback ключів: en → uk + dev-warn.** Не показуємо raw key (поганий UX); fallback на default локаль.
- **Eager-load обох локалей.** 2 файли по ~10KB — не варто async-chunks.
- **Структура локаль-файлів:** nested JSON, camelCase англомовні ключі. Namespaces по фічах (`auth`, `dashboard`, `profile`, `layout`, `validation`, `errors`, `common`).
- **EN-копірайт пише агент під час імплементації.** Користувач явно делегував — обсяг дешевий, post-launch правки тривіальні (тільки JSON).
- **Спільний composable для API-помилок.** 8 catch-блоків мають однакову форму status-extraction + UA-mapping. Локалізація вимагає переписати всі 8; одночасно виносимо у composable, щоб не плодити копії. Це не окремий рефакторинг — це форма того ж самого проходу, природна на цьому етапі.
- **Server-side global middleware для невалідного префіксу** (стрипає `/{xx}/` де `xx` не з нашого списку → 302 на default). Альтернатива — лишити дефолтну поведінку бібліотеки (404). Обрали стрип, бо для UX m'яко-фейлити краще ніж жорстко-404.
- **Текстовий toggle (а не dropdown).** Для 2 мов dropdown надмірний — toggle коротший і легше скануватись.
- **Brand "Bot Funnel" однаковий у обох локалях.**
- **Title/meta tags не локалізуємо** — поза скоупом, потребує `useHead` рефакторингу на кожній сторінці.
- **Backend не зачіпаємо** — окрема скоуп, потребує мови з cookie у HTTP-headers.
- **Існуючі тести (UA literals, path asserts у `auth.global.spec.ts`) не переписуємо** — default локаль uk, `localePath` для uk повертає той самий рядок без префікса, asserts проходять.
- **Playwright bootstrap — окрема Wave-1 задача.** Зараз Playwright у проекті немає. Інстал + конфіг + scripts окремо від E2E-сценарію (E2E пишеться вже після того як фічу імплементували).

## Тестування

**Unit-тести:** робляться завжди, не обговорюються.

**Інтеграційні тести:** не робляться — i18n це чиста frontend-зміна, бекенд не зачіпається, інтеграція з MongoDB/Redis нерелевантна.

**E2E тести:** один Playwright-сценарій: відкрити `/auth/login`, клацнути EN, перевірити що URL `/en/auth/login`, текст кнопки змінився з "Увійти" на "Sign in", невалідний email показує EN-повідомлення валідації. Це покриває golden-path перемикання, locale routing, Zod live-switch і `<html lang>` оновлення в один прохід. Більше E2E надлишково для feature M-розміру.

**Vitest:** один спек на компонент перемикача — рендер UK/EN, клік, локаль змінилася, cookie оновлений. Жодних per-key тестів — тавтології.

**Playwright bootstrap** — окрема Wave-1 задача (зараз `@playwright/test` нема в проекті): встановити пакет, створити конфіг, додати npm script `test:e2e`, директорію `e2e/`, оновити `.gitignore`.

## Як перевірити

### Агент перевіряє

| Крок | Інструмент | Очікуваний результат |
|------|-----------|---------------------|
| `cd frontend && pnpm test` | bash (Vitest) | Усі specs зелені, включно з компонентом перемикача |
| `cd frontend && pnpm test:e2e e2e/i18n.spec.ts` | bash (Playwright) | E2E (open → switch → assert EN) пройшов |
| `curl -sI 'http://localhost:3000/en/auth/verify-email?token=x'` | bash | Headers містять `X-Robots-Tag: noindex, nofollow` + `Referrer-Policy: no-referrer` |
| `curl -sI -H 'Accept-Language: en-US' http://localhost:3000/dashboard` | bash | 302 на `/en/auth/login`, cookie з мовою `en` ставиться |
| `curl -sI -H 'Accept-Language: uk-UA' http://localhost:3000/dashboard` | bash | 302 на `/auth/login`, cookie з мовою `uk` |
| `curl -sI http://localhost:3000/fr/dashboard` | bash | 302 на `/dashboard` (стрипнуто невалідний префікс) |
| `cd frontend && rm -f i18n/locales/en.json && pnpm build` | bash | Build падає (валідація локалей) |
| Перевірка `<html lang>` після перемикання | playwright | До: `lang="uk"`, після кліку EN: `lang="en"` |

### Користувач перевіряє

- `cd frontend && pnpm dev` → відкрити `localhost:3000/auth/login` → клік EN → форма перерендерилась EN, у URL `/en/`, у DevTools cookie оновлений.
- Очистити cookies, відкрити `localhost:3000/dashboard` у браузері з англомовним `Accept-Language` → редірект на `/en/auth/login`, форма EN.
- Відкрити `localhost:3000/fr/dashboard` → 302 → `/dashboard` (uk без префікса).
- Залогінитись, поділитись `localhost:3000/en/profile` з іншого браузера/інкогніто → відкривається EN, cookie ставиться у `en`.
- Перевірити що pending-user banner на dashboard перекладається при switch.
- Перевірити обидва layouts: топбар (default) і фоновий куток (auth).
