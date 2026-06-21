---
created: 2026-05-04
status: approved
type: feature
size: L
---

# User Spec: 02 — Автентифікація та облікові записи

## Що робимо

Будуємо повний шар автентифікації для Bot Funnel Service: реєстрація (email + пароль + ім'я), підтвердження email, вхід через HTTP-only session cookies (сесії в MongoDB), захист від brute-force, скидання та зміна пароля, сторінка профілю (ім'я, пароль, управління сесіями, видалення акаунту). Також: seed-скрипт Super Admin, Mailpit для локального тестування email, повний bootstrap frontend (Pinia, Vue Query, shadcn-vue, vee-validate/zod, auth middleware, layout shell), Testcontainers для integration тестів. Попутно фіксуємо баг Epic 01: env vars `MONGODB_URI`/`REDIS_URL` не були прив'язані в `application.properties`. Оновлюємо `architecture.md`, `deployment.md`, `ux-guidelines.md` відповідно до прийнятих рішень.

**Примітка щодо скоупу:** широкий скоуп цього epic свідомий — він закриває foundation фронтенду та тест-інфраструктури, які потрібні всім наступним epic. Розбивати недоцільно через щільні залежності між частинами.

## Навіщо

Малий бізнес і маркетологи не можуть використовувати Bot Funnel Service без облікового запису — неможливо створити проект, підключити бота або запустити розсилку. Без автентифікації сервіс недоступний для кінцевого користувача. Крім того, без Pinia, layout shell та auth middleware всі наступні epic не можуть будувати UI.

## Як повинно працювати

### Реєстрація

1. Форма: email, пароль (мін. 8 символів, одна цифра, одна літера), ім'я
2. Якщо email вже існує → інлайн помилка "Користувач з таким email вже існує"
3. Якщо email у 30-денному soft-delete вікні → "Акаунт з таким email вже існує або був нещодавно видалений. Зверніться до підтримки: {SUPPORT_EMAIL}"
4. Успіх → акаунт у статусі `pending` → надсилається verification email → сторінка "Перевірте пошту"
5. Якщо email-сервіс недоступний → логуємо помилку, користувачеві показуємо "Перевірте пошту" (не розкриваємо деталі збою)

### Підтвердження email

1. Клік по посиланню → валідація токену → статус `active` → редірект на `/login`
2. Пошкоджений або невалідний токен → сторінка "Посилання недійсне"
3. Посилання прострочено (24 год) → сторінка "Посилання прострочено" + кнопка "Надіслати новий лист" (rate limit: 1 запит/60 сек)

### Вхід

1. Форма email + пароль + чекбокс "Запам'ятати мене"
2. Перевірка brute-force: 5 невдалих спроб з одного IP/email → блокування на 15 хвилин
3. Перевірка статусу:
   - `blocked` → "Ваш акаунт заблоковано. Зверніться до підтримки: {SUPPORT_EMAIL}"
   - `pending` → вхід дозволено, редірект `/dashboard` з банером "Підтвердіть email"
   - `active` → HTTP-only session cookie → редірект `/dashboard`
4. "Запам'ятати мене" → TTL сесії 30 днів (без прапорця — 24 год)
5. Множинні сесії дозволені: ноутбук і телефон — незалежні активні сесії

### Банер для pending-користувача

На дашборді: "Підтвердіть email для повного доступу. [Перейти до профілю]". У профілі — кнопка "Надіслати лист повторно".

### Вихід

Звичайний Logout → видалення поточної сесії → редірект `/login`. Інші сесії (інші пристрої) залишаються активними.

### Скидання пароля

1. Форма "Forgot password" → завжди однакова відповідь: "Якщо акаунт з таким email існує, ви отримаєте лист" (незалежно від того, чи існує email — захист від enumeration)
2. Якщо email існує → reset-посилання (TTL 1 год, одноразове)
3. Пошкоджений або невалідний токен → "Посилання недійсне"
4. Прострочене або вже використане посилання → "Посилання недійсне або прострочено" + посилання на /forgot-password
5. Форма нового пароля (ті самі правила що і при реєстрації) → після успіху → інвалідація ВСІХ сесій → редірект `/login`

### Профіль (/profile)

- Перегляд та редагування імені
- Email: тільки читання (зміна — post-MVP)
- Кнопка "Надіслати лист підтвердження повторно" (тільки якщо статус `pending`)
- Зміна пароля: потребує поточного пароля (ті самі правила валідації)
- "Завершити всі сесії" — видаляє всі сесії цього користувача з MongoDB
- "Видалити акаунт" → модалка зі списком наслідків (видаляться всі проекти, боти, підписники) → кнопка "Видалити акаунт" → soft-delete → видалення поточної сесії → редірект `/login`

### Видалені акаунти

- Soft-delete: статус `deleted`, дані зберігаються 30 днів
- JobRunr recurring job (щоденно о 03:00): hard delete записів старших 30 днів
- Реєстрація з тим самим email під час 30-денного вікна заблокована

### Логування подій безпеки

Наступні події записуються в колекцію `events`: `login_success`, `login_failed`, `password_changed`, `password_reset_requested`, `email_verified`, `account_blocked`, `account_unblocked`, `account_deleted`. Паролі, токени та session ID — ніколи не потрапляють у логи та Sentry.

### Super Admin

Spring `ApplicationRunner` запускається при старті. Читає credentials з env vars. Ідемпотентний: якщо користувач вже існує — пропускає без помилки.

### Email-шаблони

Три шаблони: "Verify your email", "Reset your password", "Your account was blocked". Мова: українська.

## Критерії приймання

- [ ] Реєстрація: форма валідує email, пароль, ім'я з інлайн помилками; сервер повертає 400 з переліком помилок
- [ ] Реєстрація з існуючим email → 409 + "Користувач з таким email вже існує"
- [ ] Після реєстрації → verification email у Mailpit (dev) / SMTP (prod)
- [ ] При недоступності email-сервісу → 201 повертається, помилка логується, деталі не розкриваються
- [ ] Клік верифікаційного посилання → статус `active`, редірект `/login`
- [ ] Невалідний/пошкоджений токен верифікації → сторінка "Посилання недійсне"
- [ ] Прострочене верифікаційне посилання → сторінка з кнопкою "Надіслати новий лист"
- [ ] Resend rate limit: повторний запит менш ніж через 60 сек → 429
- [ ] Pending-користувач може залогінитись, бачить банер з лінком на профіль
- [ ] Active-користувач логіниться → HTTP-only cookie → редірект `/dashboard`
- [ ] "Запам'ятати мене": сесія живе 30 днів; без прапорця — 24 год
- [ ] 5 невдалих входів → 6-та спроба заблокована на 15 хв
- [ ] Blocked-користувач: вхід заблоковано, повідомлення з SUPPORT_EMAIL
- [ ] Logout: тільки поточна сесія видаляється, інші пристрої залишаються залогіненими
- [ ] Forgot password: однакова відповідь незалежно від існування email
- [ ] Reset-посилання: працює один раз протягом 1 год; повторне використання → "Посилання недійсне"
- [ ] Невалідний reset-токен → "Посилання недійсне" (не 500)
- [ ] Після зміни пароля → всі сесії інвалідовані
- [ ] Профіль: ім'я зберігається; зміна пароля потребує поточного
- [ ] "Завершити всі сесії": після натискання всі інші пристрої виходять
- [ ] "Видалити акаунт": модалка з наслідками → soft-delete → вихід
- [ ] Hard delete: записи старші 30 днів видаляються JobRunr-джобом
- [ ] Super Admin seed: запуск двічі → рівно один superadmin запис у БД
- [ ] Auth events логуються в колекцію `events` (login_success, login_failed, password_changed, email_verified, account_deleted)
- [ ] Frontend: auth middleware перехоплює неавторизований доступ до `/dashboard`, `/profile` → редірект `/login`
- [ ] Frontend: layout shell (topbar + sidebar) рендериться для авторизованих сторінок
- [ ] Тести: 80%+ покриття backend auth-модуля
- [ ] Документація: `architecture.md`, `deployment.md`, `ux-guidelines.md` оновлені відповідно до прийнятих рішень

## Обмеження

- **Session cookies замість JWT** — сесії в MongoDB, без JWT-бібліотеки. Redis — тільки brute-force лічильники та analytics cache.
- **Pending-user обмеження** — у цьому epic: тільки блокування створення проектів. Повний перелік обмежень для `pending` визначається в наступних epic. Детальніше: `work/02-auth/future-features.md`.
- **Email-провайдер для production** — TBD; в dev використовується Mailpit (Docker).
- **OAuth, 2FA, magic-links** — post-MVP. Перелік: `work/02-auth/future-features.md`.
- **Зміна email** — post-MVP.
- **Admin panel** — Epic 10. Тут: тільки seed Super Admin і backend блокування/unblock.
- **Cascade видалення** — при delete account у цьому epic видаляється тільки user-запис. Каскад (проекти, боти, підписники) — у відповідних epic.

## Ризики

- **`spring-session-data-mongodb` + WebFlux** — бібліотека має відкритий issue #226 щодо application-level session cleanup у reactive mode. Фактичне видалення сесій відбувається через MongoDB TTL index на полі `expireAt` (database-level) — не залежить від reactive-коду. **Митигація:** переконатись що TTL index створюється коректно; написати smoke-тест першим; якщо TTL index не спрацьовує — fallback на `spring-session-data-redis` (Redis вже є в стеку).
- **Mailpit Testcontainer** — `ch.martinelli.oss:testcontainers-mailpit` є в Maven Central (версія 1.3.1+).
- **Nuxt 4 SSR auth hydration** — useAuthStore та `/api/auth/me` call на server side можуть призвести до подвійних запитів. **Митигація:** використовувати `useState` або `useFetch` зі стратегією `server: false` для auth check.
- **Hard delete cascade** — видалення без каскаду залишає orphaned documents. **Митигація:** явно задокументовано в Обмеженнях; cascade в epic проектів.
- **Redis недоступний під час brute-force check** — якщо Redis down, лічильник не збільшується. **Митигація:** fail open (дозволяємо вхід) — brute-force це DoS-захист, не security gate.

## Технічні рішення

- **HTTP-only session cookies замість JWT** — Redis вже є, але сесії в MongoDB природно лягають до основного сховища. JWT давав би перевагу тільки для мобільних API-клієнтів; публічний REST API (Epic 08) використовуватиме API Keys. Session cookies простіші, revocation тривіальне.
- **`spring-session-data-mongodb`** — сесії в колекції `sessions`. Свідомий вибір попри issue #226: MongoDB TTL index забезпечує фізичне видалення; fail-safe fallback на Redis задокументовано у ризиках.
- **Видалення акаунту без повторного вводу пароля** — авторизована сесія вже підтверджує особу. Модалка з наслідками достатня. Відхилення від попередніх ux-guidelines — `ux-guidelines.md` оновлюється в цьому epic.
- **Email токени (verify + reset) зберігаються як хеш** — raw токен тільки в URL листа, ніколи в БД.
- **Frontend bootstrap в Epic 02** — всі frontend-залежності з `architecture.md` встановлюються зараз. Наступні epic не займаються налаштуванням.
- **Оновлення документації в Epic 02**: `architecture.md` (session замість JWT, Redis scope, нові залежності), `deployment.md` (прибрати JWT vars, додати SESSION_TTL_* та SUPER_ADMIN_* змінні), `ux-guidelines.md` (account deletion без пароля).

## Тестування

**Unit-тести:** робляться завжди, не обговорюються.

**Backend integration тести** — Testcontainers: MongoDB + Redis + Mailpit. Покриваємо: register, verify-email, resend-verify, login (включаючи brute-force та статуси), logout, forgot-password, reset-password, profile GET/PATCH, change-password, delete-account, terminate-all-sessions. Email flows: перевіряємо доставку та витягуємо токен з тіла листа через Mailpit API. JobRunr hard-delete: тригеримо джоб вручну, перевіряємо hard delete. Super Admin seed: ідемпотентність при двох запусках. Ціль: 80%+ покриття auth-модуля.

**Frontend** — Vitest + Vue Test Utils: валідація форм (формат email, правила пароля, порожні поля, невідповідність паролів). Playwright E2E: golden path реєстрації та логіну на живому додатку.

**E2E тести:** реєстрація → підтвердження email (Mailpit) → вхід → дашборд; forgot password → reset → вхід з новим паролем.

## Як перевірити

### Агент перевіряє

| Крок | Інструмент | Очікуваний результат |
|------|-----------|---------------------|
| `./gradlew test` | bash | Всі тести зелені, 80%+ coverage |
| `POST /api/auth/register` (валідні дані) | WebTestClient | 201, email у Mailpit Testcontainer |
| `POST /api/auth/register` (існуючий email) | WebTestClient | 409 + поле `message` |
| `POST /api/auth/login` (5+ невдалих спроб) | WebTestClient | 6-та спроба → 429 |
| `POST /api/auth/login` (blocked user) | WebTestClient | 403 + повідомлення |
| `POST /api/auth/forgot-password` (неіснуючий email) | WebTestClient | 200 (не 404) |
| `GET /api/auth/verify-email?token=INVALID` | WebTestClient | 400 (не 500) |
| Super Admin seed двічі | Mongo | 1 запис з `isSuperAdmin=true` |
| JobRunr hard-delete job | Testcontainer test | User видалено з MongoDB |

### Користувач перевіряє

- `docker compose up` → MongoDB + Redis + Mailpit підняті; `localhost:8025` відкривається
- Реєстрація → лист у Mailpit → клік посилання → вхід → дашборд
- "Запам'ятати мене": після перезапуску браузера — сесія активна
- Forgot password → лист у Mailpit → зміна пароля → старий пароль більше не працює
- Профіль: зміна імені зберігається; "Завершити всі сесії" виходить з усіх вкладок
