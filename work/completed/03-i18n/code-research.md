# Code Research: 03-i18n

Created: 2026-05-07
Author: research agent
Scope: introduce `@nuxtjs/i18n` with `uk` (default) + `en`, `prefix_except_default`, cookie-persisted locale, translate every existing user-visible string in `frontend/`.

This is the canonical reference for the tech-spec author. Backend email templates and backend-emitted error strings are explicitly out of scope.

---

## 1. Frontend file inventory (every file with user-visible text)

All currently rendered strings are Ukrainian (UA). The codebase has no English UI strings and no translation layer.

### 1.1 `frontend/app.vue`
- Root shell only — `<NuxtLayout><NuxtPage/></NuxtLayout>`.
- **No user-visible text.** Will, however, need `<Html :lang="head.htmlAttrs.lang">` wiring (or `useHead`) once the locale becomes dynamic, so search engines and screen readers see the right `lang`. The `@nuxtjs/i18n` module supplies the bookkeeping via `useLocaleHead()` — flag for spec.

### 1.2 Layouts

#### `frontend/layouts/default.vue` (authenticated shell)
- L19  `Bot Funnel` — brand wordmark in header `<NuxtLink>`. Could stay as a non-translated brand name; document the decision.
- L27  `Logout` — button label. **Currently English** (lone exception in UA codebase). Translate.
- L37  `Dashboard` — sidebar nav. **English** (literal).
- L38  `Profile` — sidebar nav. **English** (literal).
- L21-L22 dynamic: `{{ user.name }} ({{ user.email }})` — no translation needed but the surrounding template stays as-is.

#### `frontend/layouts/auth.vue`
- No literal text; pure shell. Nothing to translate.

### 1.3 Pages — auth flow (5 files, all UA)

#### `frontend/pages/auth/login.vue`
- L9  Zod: `'Введіть коректний email'`.
- L10 Zod: `'Пароль має містити щонайменше 8 символів'`.
- L43 onSubmit catch (HTTP 429): `'Забагато спроб. Спробуйте через 15 хв'`.
- L45 onSubmit catch (HTTP 401): `'Невірний email або пароль'`.
- L47 onSubmit catch (HTTP 403): `'Доступ заборонено. Зверніться до підтримки'`.
- L49 onSubmit catch (generic): `'Не вдалося увійти. Спробуйте пізніше'`.
- L57 H1: `Увійти`.
- L61 label: `Email`.
- L77 label: `Пароль`.
- L94 checkbox label: `Запам'ятати мене`.
- L106 button: `'Зачекайте…' : 'Увійти'`.
- L111 link: `Зареєструватись`.
- L112 link: `Забули пароль?`.

#### `frontend/pages/auth/register.vue`
- L10 Zod: `'Введіть коректний email'`.
- L11 Zod: `"Ім'я обов'язкове"`.
- L14 Zod: `'Мін. 8 символів'`.
- L15 Zod: `'Має містити хоча б одну літеру'`.
- L16 Zod: `'Має містити хоча б одну цифру'`.
- L21 Zod refine: `'Паролі не співпадають'`.
- L52 catch 409: `'Користувач з таким email вже існує. Якщо це ваш акаунт, зверніться до підтримки.'`.
- L54 catch 400: `'Перевірте правильність даних і спробуйте ще раз'`.
- L56 catch 429: `'Забагато спроб реєстрації. Спробуйте через хвилину'`.
- L58 catch generic: `'Не вдалося завершити реєстрацію. Спробуйте пізніше'`.
- L66 H1: `Реєстрація`.
- L70 label: `Email`.
- L86 label: `Ім'я`.
- L102 label: `Пароль`.
- L118 label: `Підтвердження пароля`.
- L142 button: `'Зачекайте…' : 'Зареєструватись'`.
- L147 paragraph: `Вже маєте акаунт?` + L148 link `Увійти`.

#### `frontend/pages/auth/forgot-password.vue`
- L9 Zod: `'Введіть коректний email'`.
- L36 H1: `Відновлення пароля`.
- L40 success paragraph: `Якщо акаунт з таким email існує, ви отримаєте лист з інструкціями для відновлення пароля.`.
- L46 button-link: `Повернутись до входу`.
- L52 hint paragraph: `Вкажіть email, на який ви реєструвалися. Ми надішлемо посилання для скидання пароля.`.
- L56 label: `Email`.
- L76 button: `'Надсилаємо…' : 'Надіслати лист'`.
- L80 link: `Повернутись до входу`.

#### `frontend/pages/auth/reset-password.vue`
- L18 Zod: `'Мін. 8 символів'`.
- L19 Zod: `'Має містити хоча б одну літеру'`.
- L20 Zod: `'Має містити хоча б одну цифру'`.
- L25 Zod refine: `'Паролі не співпадають'`.
- L57 catch generic: `'Не вдалося оновити пароль. Спробуйте ще раз пізніше'`.
- L65 H1: `Новий пароль`.
- L69 paragraph (invalid/expired token): `Посилання недійсне або прострочено. Запросіть нове посилання для скидання пароля.`.
- L75 button-link: `Запросити нове посилання`.
- L81 label: `Новий пароль`.
- L97 label: `Підтвердження пароля`.
- L121 button: `'Оновлюємо…' : 'Оновити пароль'`.

#### `frontend/pages/auth/verify-email.vue`
- L52 Zod: `'Введіть коректний email'`.
- L112 H1: `Підтвердження email`.
- L116 paragraph: `Перевіряємо посилання…`.
- L120 paragraph (success): `Email підтверджено!`.
- L125 button-link: `Увійти →` (note Unicode arrow in source).
- L130 paragraph (invalid): `Посилання недійсне.`.
- L135 button-link: `До входу`.
- L140 paragraph (expired): `Посилання прострочено.`.
- L144 hint: `Введіть email, на який ви реєструвалися, щоб отримати новий лист.`.
- L147 label: `Email`.
- L163 success line: `Якщо акаунт існує, ви отримаєте лист найближчим часом.`.
- L166 cooldown line: `Спробуйте знову через {{ cooldownSecondsLeft }} с` (interpolated number — needs `t()` with placeholder).
- L169 error line: `Не вдалося надіслати лист. Спробуйте пізніше`.
- L178 button label: `\`Зачекайте ${cooldownSecondsLeft} с\` : 'Надіслати новий лист'` (template-string interpolation — translate via `t()` with `{seconds}` arg).

### 1.4 Pages — app

#### `frontend/pages/dashboard.vue`
- L15 banner text (pending users): `Підтвердіть email для повного доступу.`.
- L21 link: `Перейти до профілю →`.
- L26 H1: `Ласкаво просимо{{ user.name ? \`, ${user.name}\` : '' }}` — needs `t('dashboard.welcome', { name })` with conditional.
- L29 paragraph: `Це ваш дашборд. Більше функцій з'явиться у наступних епіках.`.

#### `frontend/pages/profile.vue` (largest file, 5 sections)
- L40 manual validation: `"Ім'я обов'язкове"`.
- L43 manual validation: `"Ім'я не може бути довшим за 100 символів"`.
- L57 catch generic (save name): `'Не вдалося зберегти. Спробуйте пізніше'`.
- L108 Zod: `'Введіть поточний пароль'`.
- L111 Zod: `'Мін. 8 символів'` / L112 `'Має містити хоча б одну літеру'` / L113 `'Має містити хоча б одну цифру'`.
- L118 Zod refine: `'Паролі не співпадають'`.
- L157 catch 400 (change pwd): `'Невірний поточний пароль'`.
- L159 catch 429: `'Забагато спроб. Спробуйте через 15 хв'`.
- L161 catch generic: `'Не вдалося змінити пароль. Спробуйте пізніше'`.
- L181 catch generic (terminate sessions): `'Не вдалося завершити сесії. Спробуйте пізніше'`.
- L210 catch generic (delete account): `'Не вдалося видалити акаунт. Спробуйте пізніше'`.
- Template (lots): L217 `Профіль`, L221 `Особисті дані`, L224 `Email`, L235 `Ім'я`, L246 `Редагувати`, L250 `Ім'я збережено`, L255 `Ім'я`, L274 `'Збереження…' : 'Зберегти'`, L282 `Скасувати`, L294 `Підтвердження email`, L295 `Ваш email не підтверджено`, L298 `Якщо акаунт існує, ви отримаєте лист найближчим часом.`, L301 `Почекайте 60 секунд`, L304 `Не вдалося надіслати лист. Спробуйте пізніше`, L314 `\`Зачекайте ${resendCooldown} с\` : 'Надіслати лист повторно'`, L320 `Зміна пароля`, L329 `Поточний пароль`, L345 `Новий пароль`, L361 `Підтвердження нового пароля`, L377 `Пароль змінено`, L388 `'Зачекайте…' : 'Змінити пароль'`, L395 `Сесії`, L397 `Завершення всіх сесій вийде з акаунта на цьому та всіх інших пристроях.`, L409 `'Завершення…' : 'Завершити всі сесії'`, L415 `Видалення акаунта`, L417 `Дія незворотна. Усі ваші проекти, боти та підписники будуть видалені.`, L425 `Видалити акаунт`, L440 modal title `Видалити акаунт?`, L443 `Це видалить:`, L445-L447 list items `всі ваші проекти` / `ботів` / `підписників`, L449 `Дію не можна скасувати.`, L463 `Скасувати`, L472 `'Видалення…' : 'Видалити акаунт'`.

### 1.5 Composables / plugins / stores / middleware
- `frontend/composables/useApi.ts` — no user-visible strings.
- `frontend/plugins/vue-query.ts` — no user-visible strings.
- `frontend/stores/auth.ts` — no user-visible strings (no toast, no redirect message). Locale-agnostic.
- `frontend/middleware/auth.global.ts` — no user-visible strings, but contains hardcoded path literals (see §6).

### 1.6 Components / error.vue / app-level
- `frontend/components/` — **directory does not exist**. No reusable components defined yet. The shadcn-vue dependency is installed, but no shadcn primitives have been generated (no `components.json`, no `components/ui/`). The Delete-account modal in `profile.vue` (L430-L476) is hand-rolled with `role="dialog"` + raw classes — no shadcn `Dialog` aria-labels to translate.
- `frontend/error.vue` — **does not exist**. Default Nuxt error page is used. Worth flagging for the spec author: if we want a localized custom error page (404/500) we need to create one; otherwise Nuxt's built-in English fallback is shown on errors.

---

## 2. API-error-to-text mappings (centralization candidates)

Every catch block today maps HTTP codes to a hand-written UA sentence. Same patterns repeat across 5 files — perfect candidate for a single helper such as `useApiErrorMessage(error, contextKey)` that picks the message via `t('errors.<contextKey>.<code>')` with a generic fallback to `t('errors.generic')`.

| File:Line | Trigger | Current UA string |
|---|---|---|
| `pages/auth/login.vue:43` | HTTP 429 | `Забагато спроб. Спробуйте через 15 хв` |
| `pages/auth/login.vue:45` | HTTP 401 | `Невірний email або пароль` |
| `pages/auth/login.vue:47` | HTTP 403 | `Доступ заборонено. Зверніться до підтримки` |
| `pages/auth/login.vue:49` | other | `Не вдалося увійти. Спробуйте пізніше` |
| `pages/auth/register.vue:52` | HTTP 409 | `Користувач з таким email вже існує. Якщо це ваш акаунт, зверніться до підтримки.` |
| `pages/auth/register.vue:54` | HTTP 400 | `Перевірте правильність даних і спробуйте ще раз` |
| `pages/auth/register.vue:56` | HTTP 429 | `Забагато спроб реєстрації. Спробуйте через хвилину` |
| `pages/auth/register.vue:58` | other | `Не вдалося завершити реєстрацію. Спробуйте пізніше` |
| `pages/auth/reset-password.vue:54` | HTTP 400 | (sets `linkInvalid=true`, message rendered at L69) |
| `pages/auth/reset-password.vue:57` | other | `Не вдалося оновити пароль. Спробуйте ще раз пізніше` |
| `pages/auth/verify-email.vue:39` | HTTP 400 + `TOKEN_EXPIRED` | (sets `state='expired'`, branch UI) |
| `pages/auth/verify-email.vue:42` | other | (sets `state='invalid'`, branch UI) |
| `pages/auth/verify-email.vue:100` | resend HTTP 429 | (sets `resendStatus='cooldown'`, message at L165-L167) |
| `pages/auth/verify-email.vue:104` | resend other | `Не вдалося надіслати лист. Спробуйте пізніше` |
| `pages/profile.vue:57` | save name other | `Не вдалося зберегти. Спробуйте пізніше` |
| `pages/profile.vue:96-101` | resend 429 / other | (sets resendStatus, messages at L300/L304) |
| `pages/profile.vue:157` | change pwd 400 | `Невірний поточний пароль` |
| `pages/profile.vue:159` | change pwd 429 | `Забагато спроб. Спробуйте через 15 хв` |
| `pages/profile.vue:161` | change pwd other | `Не вдалося змінити пароль. Спробуйте пізніше` |
| `pages/profile.vue:181` | terminate sessions | `Не вдалося завершити сесії. Спробуйте пізніше` |
| `pages/profile.vue:210` | delete account | `Не вдалося видалити акаунт. Спробуйте пізніше` |

The `e?.statusCode ?? e?.status ?? e?.response?.status` extraction is duplicated **8 times** verbatim. Centralizing into `composables/useApiError.ts` is in scope for this feature (recommended) but should be flagged — it is a refactor riding along with the i18n work.

---

## 3. Existing i18n traces

Verified by full-tree grep over `frontend/**/*.{vue,ts}` (excluding `node_modules`, `.nuxt`, `.output`):

```
grep -rn "useI18n\|\$t(\|i18n\|\bt(" frontend  → 0 results
```

- **No `@nuxtjs/i18n` in `frontend/package.json`** — confirmed (deps listed in `frontend/package.json:12-25`, devDeps L26-L33).
- **No `vue-i18n` either.**
- **No `frontend/locales/` directory** (verified).
- **No `i18n.config.ts`**.
- No `useI18n()`, no `$t()`, no template `{{ t(...) }}` — every string is a literal.

Project is a true greenfield for i18n.

---

## 4. Nuxt config touchpoints — `frontend/nuxt.config.ts`

Full file is 27 lines:
- L1-L5 `compatibilityDate: '2025-07-01'`, `typescript.strict: true`.
- L6-L9 `modules: ['@pinia/nuxt', '@nuxtjs/tailwindcss']` — must add `'@nuxtjs/i18n'`.
- L10 `css: ['~/assets/css/tailwind.css']`.
- L11-L15 `runtimeConfig.public.apiBase`.
- L19-L26 `routeRules`:

```ts
routeRules: {
  '/auth/**': {
    headers: {
      'X-Robots-Tag': 'noindex, nofollow',
      'Referrer-Policy': 'no-referrer',
    },
  },
}
```

**RISK / required change.** With `prefix_except_default` and second locale `en`, every auth page becomes accessible at **two** URL shapes:
- `/auth/login` (default `uk`, no prefix) — already covered.
- `/en/auth/login` — **NOT** matched by `/auth/**`.

The security headers (no-index, no-referrer) on auth pages exist specifically to keep `?token=` query params out of crawler indexes and Referer chains. **The route-rule must be widened to also cover `/en/auth/**`** (e.g. add a second key `'/en/auth/**'` with the same headers, or migrate to a regex/multi-pattern approach). Forgetting this is a real security regression. Flag in the tech-spec acceptance criteria.

Other config notes:
- No `runtimeConfig` keys are language-aware today.
- No `nitro` block, so SSR rendering uses defaults.

---

## 5. Routing & links — `<NuxtLink>` and `navigateTo()` call sites

After enabling `prefix_except_default`, every hardcoded path stops being locale-aware. The module ships `<NuxtLinkLocale>` and `localePath()`/`useLocalePath()` to inject the prefix automatically. Each call site below must either use one of those helpers or be wrapped.

### `<NuxtLink to="...">` call sites (all currently hardcoded)
| File:Line | Target | Notes |
|---|---|---|
| `layouts/default.vue:19` | `/dashboard` | brand link in header |
| `layouts/default.vue:37` | `/dashboard` | sidebar |
| `layouts/default.vue:38` | `/profile` | sidebar |
| `pages/auth/login.vue:111` | `/auth/register` | footer link |
| `pages/auth/login.vue:112` | `/auth/forgot-password` | footer link |
| `pages/auth/register.vue:148` | `/auth/login` | footer link |
| `pages/auth/forgot-password.vue:42-47` | `/auth/login` | success-state CTA |
| `pages/auth/forgot-password.vue:80` | `/auth/login` | form footer |
| `pages/auth/reset-password.vue:71-76` | `/auth/forgot-password` | invalid-token CTA |
| `pages/auth/verify-email.vue:121-126` | `/auth/login` | success CTA |
| `pages/auth/verify-email.vue:131-136` | `/auth/login` | invalid CTA |
| `pages/dashboard.vue:17-22` | `/profile` | pending-banner CTA |

Total: **12 `<NuxtLink>` sites**. Recommended pattern — replace with `<NuxtLinkLocale to="...">` (preserves the `/auth/login` literal but the module rewrites to `/en/auth/login` when active locale is `en`).

### `navigateTo(...)` call sites
| File:Line | Target | Notes |
|---|---|---|
| `layouts/default.vue:12` | `'/auth/login'` | logout flow |
| `middleware/auth.global.ts:23` | `'/dashboard'` | already-authed user lands on auth route |
| `middleware/auth.global.ts:27` | `'/auth/login'` | unauthed user on protected route |
| `pages/auth/login.vue:38` | `'/dashboard'` | post-login redirect |
| `pages/auth/register.vue:47` | `{ path: '/auth/login', query: { registered: '1' } }` | object form |
| `pages/auth/reset-password.vue:50` | `{ path: '/auth/login', query: { reset: '1' } }` | object form |
| `pages/profile.vue:178` | `'/auth/login'` | terminate-all-sessions |
| `pages/profile.vue:207` | `'/auth/login'` | delete-account |

Total: **8 `navigateTo` sites**. Each must become `navigateTo(localePath('/auth/login'))` (string form) or `navigateTo({ path: localePath('/auth/login'), query: ... })` (object form). The `localePath()` helper accepts both string and route-object inputs.

Tests in `tests/middleware/auth.global.spec.ts:36,44,56,82` and several page specs assert the exact unprefixed string `'/auth/login'` / `'/dashboard'` (see grep in §11) — **all those expectations break** the moment `navigateTo` calls are wrapped. Two options for the test plan:
1. Keep them unchanged (since the default locale `uk` is unprefixed, `localePath('/dashboard') === '/dashboard'` for `uk`), and add new tests that switch locale → expect `/en/dashboard`.
2. Mock `localePath` in tests to identity.

Option 1 is closer to real behaviour. Document the choice.

---

## 6. Auth middleware — `frontend/middleware/auth.global.ts`

```ts
function isPublicAuthRoute(path: string): boolean {
  return path.startsWith('/auth/')
}
```

Uses `to.path`, which is **the resolved Vue Router path** (i.e. without locale prefix when using `prefix_except_default` only if the module strips it from `path`). However, with `@nuxtjs/i18n`, the prefix **is** part of `to.path` (e.g. `/en/auth/login`).

**Problem.** `path.startsWith('/auth/')` returns `false` for `/en/auth/login`, so:
- An authenticated user hitting `/en/auth/login` would not be redirected to `/dashboard` (current safety net at L22-L24).
- An unauthenticated user hitting `/en/dashboard` would correctly redirect to `/auth/login` — but the redirect target is unprefixed (`navigateTo('/auth/login')` at L27), losing the locale.

**Required changes.**
1. Detect "public auth route" via the resolved route name (which `@nuxtjs/i18n` generates as `auth-login___en` etc.) or via stripping the locale prefix first.
2. Wrap both `navigateTo(...)` calls with `localePath(...)` so the redirect respects current locale.

Both fixes are mandatory and are the highest-risk part of the migration in terms of regressions. Tests in `tests/middleware/auth.global.spec.ts:36,44,56,82` will need updating to match.

---

## 7. Pinia auth store — `frontend/stores/auth.ts`

```ts
export const useAuthStore = defineStore('auth', () => {
  const userState = useState<User | null>('auth-user', () => null)
  async function fetchUser() { ... }
  async function logout() { ... }
  const isAuthenticated = computed(() => userState.value !== null)
  const isPending = computed(() => userState.value?.status === 'pending')
  return { user: userState, fetchUser, logout, isAuthenticated, isPending }
})
```

- No user-visible strings. No errors emitted to UI from the store.
- No locale handling needed.
- Consumers will continue to call the store unchanged; only the surrounding components need translating.

The `User` type (`types/user.ts`) has no language preference field. The user's locale is **per-browser, not per-user**, which matches the user-spec's "cookie-only" persistence.

---

## 8. Component library (shadcn-vue / radix-vue)

- `shadcn-vue@^1.0.3` and `radix-vue@^1.9.13` are listed in `frontend/package.json:21,31`.
- **No shadcn components have been generated** — there is no `frontend/components.json`, no `frontend/components/ui/` directory.
- The Delete-account modal in `pages/profile.vue:430-476` is hand-rolled HTML with `role="dialog" aria-modal="true" aria-labelledby="delete-modal-title"` — no auto-generated aria-labels from a Radix `Dialog` to translate. Close behaviour is via the explicit `Скасувати` button, not an `X` icon.
- `lucide-vue-next` is installed but no icons currently rendered.

Conclusion: **no third-party component aria-labels need translation.** Should the team adopt shadcn's `Dialog`/`Popover` later, the auto-generated `Close` aria-label will need overriding via the slot/`as-child` pattern — out of scope for this feature.

---

## 9. SSR / hydration considerations

Nuxt SSR + `@nuxtjs/i18n` cookie persistence has a subtle ordering constraint relevant to this codebase:

- **First request, no cookie.** Server has no `i18n_lang` cookie; module falls back to `defaultLocale: 'uk'`. SSR renders Ukrainian. Client hydrates with `uk`. No mismatch.
- **Returning visitor with `i18n_lang=en` cookie, default URL (`/`) without prefix.** Server reads cookie via `useCookie('i18n_lang')` during the SSR render cycle (Nitro request context). The module's `detectBrowserLanguage` with `useCookie: true` redirects to `/en/...` *on the root path* when `redirectOn: 'root'` (default). For non-root paths (e.g. `/dashboard`), behaviour depends on `redirectOn` setting — must be set explicitly in tech-spec.
- **Direct hit on `/en/auth/login`.** URL prefix is authoritative. SSR renders English, cookie is set/refreshed.
- **Hydration mismatch risk.** If we render UA on the server but the client-side script then reads the cookie and switches to EN before hydration finishes, Vue logs a hydration mismatch warning (and a brief flicker shows). Mitigations:
  - Use `useCookie('i18n_lang', { default: () => 'uk' })` and let `@nuxtjs/i18n`'s built-in cookie integration drive locale resolution — module reads the cookie *before* render in the SSR plugin, so server already commits to the right locale.
  - Set `detectBrowserLanguage.alwaysRedirect: true` only if we want to force redirect on every visit; for cookie-driven persistence keep it `false` and rely on the cookie alone.
  - **Do not** read the cookie in client-only `onMounted` and call `setLocale()` after hydrate — that is exactly the flicker pattern the user-spec forbids.

Recommended cookie configuration (subject to tech-spec confirmation):
```ts
i18n: {
  defaultLocale: 'uk',
  locales: [
    { code: 'uk', file: 'uk.json' },
    { code: 'en', file: 'en.json' },
  ],
  strategy: 'prefix_except_default',
  langDir: 'locales',  // resolved relative to restructureDir (defaults to 'i18n/' in v9 — verify in spec)
  detectBrowserLanguage: {
    useCookie: true,
    cookieKey: 'i18n_lang',
    cookieCrossOrigin: false,
    cookieSecure: true,
    redirectOn: 'no prefix',  // covers '/dashboard' → '/en/dashboard' for a returning EN user
    alwaysRedirect: false,
    fallbackLocale: 'uk',
  },
}
```
(`cookieCrossOrigin`/`cookieSecure` settings need checking against the deployment domain config; in dev `cookieSecure: false` to allow plain HTTP.)

A separate concern: **cookie TTL of 1y** — `@nuxtjs/i18n` does not expose a direct `cookieMaxAge` option on `detectBrowserLanguage`; the cookie is set with the module's default TTL (about 1 year already). Confirm by reading the module source / Context7 if longer-than-default needed.

---

## 10. Risks specific to this codebase

1. **Nuxt 4 + `@nuxtjs/i18n` major version compatibility.** `frontend/package.json:19` pins `nuxt@^4.4.0`. Per Context7 / module docs, `@nuxtjs/i18n` v9+ targets Nuxt 3/4 and is built on `vue-i18n` v11. Use `@nuxtjs/i18n@^9` (latest stable). Versions <9 (the v8 line) target Nuxt 3 only — do not install. No `npm:@nuxtjs/i18n-edge` needed.
2. **`langDir` location changed in v9 / Nuxt 4 layout.** With Nuxt 4's `app/` directory restructuring, `langDir` is resolved relative to `restructureDir` (default `i18n`) — meaning the locale files may need to live in `frontend/i18n/locales/uk.json` rather than `frontend/locales/uk.json`. Confirm exact path in tech-spec by reading the module getting-started doc.
3. **`routeRules` security regression** (already covered §4). Forgetting to widen the rule to `/en/auth/**` leaks the `?token=` reset/verify links to crawlers via the EN URL.
4. **Test brittleness.** All eight Vitest specs assert UA literals via `toMatch(/коректний email/i)` etc. Once strings come from `t()` they are still UA in default locale, so existing assertions keep passing — **but** any test that switches locale to EN will need explicit mock setup of `useI18n` (e.g. provide `vue-i18n`'s test utils or `mockNuxtImport('useI18n', ...)`). Likely we keep current tests UA-only and add a separate EN-locale spec for `<LangSwitcher>`.
5. **Hand-rolled modal aria** (`profile.vue:430`). Translating `aria-labelledby` target and modal title via `t()` works because the title is a normal `<h3>`. But `id="delete-modal-title"` must remain locale-independent (it is just an attribute, fine).
6. **Brand wordmark `Bot Funnel`** (layouts/default.vue:19). Not a localizable string. Pin to a constant or wrap in `t('brand.name')` returning `'Bot Funnel'` in both locales — explicit decision to record.
7. **`Logout`, `Dashboard`, `Profile` are currently English** in an otherwise UA codebase (`layouts/default.vue:27,37,38`). Choose UA versions `Вийти` / `Дашборд` / `Профіль` for the `uk.json` keys and document the change — this is technically a content change, not just a wrapping change.
8. **No `error.vue`.** Once Nuxt's default error page appears (404 / 500 / NuxtError), it is in English and unlocalized. Out of scope per user-spec but worth flagging.
9. **`vee-validate` + `zod` error messages.** Today each Zod schema is recreated per-component with literal UA strings. Replacing each `'Введіть коректний email'` with `t('validation.emailFormat')` requires the schema to be **inside** `setup()` (Zod messages are evaluated eagerly at schema-construction time). This is already the case for every page (every schema lives inside `<script setup>` — see e.g. `login.vue:8`), so simply substituting `t(...)` calls works. But: `t()` from `useI18n()` is reactive — when locale changes mid-session, the *previously constructed* Zod schema still holds the old strings. To avoid stale validation errors after live language switch, the form must re-create its schema (or re-validate) on locale change. Practically: use `computed(() => z.object({...}))` and feed it through `toTypedSchema(unref(schemaComputed))`, or trigger `meta.value && validate()` on locale change. Worth noting in the spec.
10. **vee-validate v4 + `toTypedSchema`** (`login.vue:1-2`) is already in use — no API change needed, just message replacement.

---

## 11. Tests in place

### Existing test suite (Vitest + `@nuxt/test-utils`)

| File | What it covers | Notable UA literal asserts |
|---|---|---|
| `tests/helpers/settle.ts` | `settle()` helper that flushes promises + nextTick — used by all page specs. | n/a |
| `tests/middleware/auth.global.spec.ts` | 6 cases: redirect/passthrough matrix. Asserts target paths `/auth/login` and `/dashboard` directly. | none |
| `tests/stores/auth.spec.ts` | 6 cases: `fetchUser` 200/401/500, `logout` happy/error, `isPending`. | none |
| `tests/pages/auth/login.spec.ts` | 5 cases: invalid email, short password, valid submit + redirect, 429, 401. | `/коректний email/i` (L44), `/15 хв/` (L96), `/невірний/i` (L112) |
| `tests/pages/auth/register.spec.ts` | 4 cases: password mismatch, no-digit, valid submit, 409. | `/не співпадають/i` (L43), `/цифр/i` (L59), `/вже існує/i` (L95) |
| `tests/pages/auth/forgot-password.spec.ts` | 3 cases: success, backend-error-still-shows-success, invalid email. | `/коректний email/i` (L55) |
| `tests/pages/auth/reset-password.spec.ts` | 4 cases: missing token, valid submit, 400, password mismatch. | `/не співпадають/i` (L78) |
| `tests/pages/auth/verify-email.spec.ts` | 5 cases: valid token, generic error, missing token, expired token, expired+resend. | none (asserts only `data-test` attrs) |
| `tests/pages/dashboard.spec.ts` | 2 cases: pending banner shown / hidden, name in greeting. | `/Підтвердіть email/i` (L42), `/Перейти до профілю/i` (L45) |
| `tests/pages/profile.spec.ts` | ~10 cases across 5 sections (info, resend, change pwd, terminate, delete). | `/не співпадають/i`, `/введіть поточний пароль/i`, `/цифр/i`, `/невірний поточний пароль/i`, `/підписник/i` |

Total: **9 test files, ~40 test cases.** All assert UA literals (where they assert text at all) — these continue to pass after i18n introduction *as long as* the default locale is `uk` and the literals are unchanged.

### Test framework details
- Runner: Vitest 3.2.4 (`@nuxt/test-utils/config` → `defineVitestConfig` with `environment: 'nuxt'`).
- `mockNuxtImport(...)` + `mountSuspended(...)` from `@nuxt/test-utils/runtime` are the standard wiring.
- `vi.hoisted({...})` pattern for stable mocks.
- Global setup file: none — each spec wires its own pinia + mocks.

### E2E / Playwright setup
- **Not present.** No `playwright.config.*`, no `@playwright/test` in `frontend/package.json`. No `e2e/` directory.
- `frontend/package.json:5-11` scripts contains only `dev`, `build`, `generate`, `preview`, `test` (Vitest).
- Adding the single E2E test required by the user-spec (login → switch → form re-renders in EN) will require:
  1. Adding `@playwright/test` as a devDependency.
  2. Creating `playwright.config.ts` with at least `webServer: { command: 'pnpm dev', port: 3000 }` to auto-spawn the Nuxt dev server.
  3. Adding `test:e2e` script.
  4. Creating `e2e/i18n.spec.ts`.
  5. Updating `frontend/.gitignore` (if any) to ignore `playwright-report/`, `test-results/`.
  6. Wiring CI (none present yet — no `.github/workflows/`).

This is a non-trivial sub-task. Flag explicitly in the tech-spec implementation tasks (likely its own task in Wave 1).

---

## 12. Constraints & infrastructure

- **Node.** `.nvmrc` pins Node 24 (`/Users/pavlokorolov/IdeaProjects/simple-sender/.nvmrc` → `24`).
- **Package manager.** `pnpm@10.33.2` (declared in `package.json:34`).
- **Nuxt.** `^4.4.0`. `compatibilityDate: '2025-07-01'`.
- **Tailwind CSS.** v3 via `@nuxtjs/tailwindcss@^6`. Locale-agnostic.
- **TypeScript strict** is on (`nuxt.config.ts:3-5`).
- **Pinia.** v3 (`@pinia/nuxt@^0.11.2`).
- **Pre-commit hook.** `.git/hooks/pre-commit` runs `gitleaks` if installed (script in `scripts/install-hooks.sh`). No lint hook. Adding a "no raw UA strings in `.vue` outside `/locales`" grep gate would require either a new pre-commit hook or a CI step — flag.
- **CI/CD.** No `.github/workflows/` directory found. No CI pipeline currently runs tests on push. Any "grep gate" or "Playwright run" step has nowhere to live yet.
- **Env vars.** Only `NUXT_PUBLIC_API_BASE` (`nuxt.config.ts:13`). No new env var needed for i18n (locale in cookie, not env).
- **Deployment.** Nuxt SSR via Node (no static export). Cookies must be `Secure` in prod. Document.

---

## 13. External library — `@nuxtjs/i18n` (Context7 reference)

Confirmed via Context7 (`/websites/i18n_nuxtjs`, source reputation High):

- Install: `npx nuxi@latest module add @nuxtjs/i18n`. Adds to `modules` array, sets `i18n: {}` block.
- Strategy options: `'no_prefix' | 'prefix_except_default' | 'prefix' | 'prefix_and_default'`. Required for this feature: `'prefix_except_default'` (matches user-spec).
- `defaultLocale` should be set explicitly (recommended for fallback purposes).
- `langDir` default `'locales'`, resolved relative to project `restructureDir` (default `'i18n'` in v9). Use a relative path (`'locales'` or `'./locales'`); absolute paths fail in production.
- `detectBrowserLanguage` config supports `useCookie`, `cookieKey`, `cookieDomain`, `cookieCrossOrigin`, `cookieSecure`, `redirectOn` (`'all' | 'root' | 'no prefix'`), `alwaysRedirect`, `fallbackLocale`. Cookie-based redirect is the canonical pattern.
- Helper APIs:
  - `useI18n()` → `{ t, locale, locales, ... }`.
  - `useLocalePath()` (composable) and `localePath()` (template helper).
  - `useSwitchLocalePath()` and `<SwitchLocalePathLink locale="en">English</SwitchLocalePathLink>` — recommended for the `<LangSwitcher>` component.
  - `<NuxtLinkLocale to="/auth/login">` — drop-in replacement for `<NuxtLink>`.
  - `useLocaleHead()` — for `<html lang>` and `hreflang`.
- Fallback locale: configured in `i18n.config.ts` via `fallbackLocale: 'uk'` (matches user-spec). Missing keys → fall back + dev-mode `vue-i18n` logs `[intlify] Not found '...' key`.
- Language-switcher canonical pattern (from docs):
  ```vue
  <script setup>
  const { locale, locales } = useI18n()
  const switchLocalePath = useSwitchLocalePath()
  const availableLocales = computed(() => locales.value.filter(i => i.code !== locale.value))
  </script>
  ```
- **Vue I18n v11** is the underlying engine (v9+ of `@nuxtjs/i18n` ships v11). Composition API mode (`legacy: false`) is the default.

Documentation snippets above are sufficient for the tech-spec — re-query with `researchMode: true` only if tricky edge-cases emerge during implementation (e.g. exact cookie-TTL override).

---

## 14. Summary table — files that change

| File | Change category | Complexity |
|---|---|---|
| `frontend/package.json` | add `@nuxtjs/i18n@^9`, `@playwright/test` | trivial |
| `frontend/nuxt.config.ts` | register module, configure i18n block, widen routeRules to `/en/auth/**` | low |
| `frontend/i18n/locales/uk.json` (new) | full UA translation tree | medium |
| `frontend/i18n/locales/en.json` (new) | full EN translation tree | medium |
| `frontend/i18n.config.ts` (new, optional) | fallbackLocale | trivial |
| `frontend/components/LangSwitcher.vue` (new) | UI control | low |
| `frontend/composables/useApiError.ts` (new, recommended) | centralize HTTP→message mapping | medium |
| `frontend/app.vue` | wire `useLocaleHead` for `<html lang>` | trivial |
| `frontend/layouts/default.vue` | `<NuxtLinkLocale>` + `t(...)` | low |
| `frontend/layouts/auth.vue` | no change (no text) | none |
| `frontend/middleware/auth.global.ts` | locale-aware path detection + `localePath()` for `navigateTo` | medium (highest regression risk) |
| `frontend/pages/auth/login.vue` | `t(...)` everywhere, `<NuxtLinkLocale>`, `localePath()` for `navigateTo`, schema in computed | medium |
| `frontend/pages/auth/register.vue` | same | medium |
| `frontend/pages/auth/forgot-password.vue` | same | low |
| `frontend/pages/auth/reset-password.vue` | same | low |
| `frontend/pages/auth/verify-email.vue` | same + interpolated `{seconds}` | medium |
| `frontend/pages/dashboard.vue` | `t(...)` + `<NuxtLinkLocale>` + interpolated `{name}` | low |
| `frontend/pages/profile.vue` | extensive `t(...)` (largest single page change) | high |
| `frontend/tests/components/LangSwitcher.spec.ts` (new) | Vitest unit | low |
| `frontend/playwright.config.ts` (new) | Playwright config | low |
| `frontend/e2e/i18n.spec.ts` (new) | login → switch → re-render assertion | low |
| `frontend/tests/middleware/auth.global.spec.ts` | update assertions for locale-aware redirects (or mock `localePath`) | low |

---

End of research.
