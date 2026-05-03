# 11 — Web UI (Frontend)

## Мета
Створити інтерфейс, через який користувачі реально працюють зі своїми проектами: будують воронки, керують підписниками, запускають розсилки.

## Користувацька цінність
Без UI продукт існує тільки для розробників. UI — це 80% сприйняття користувачем якості сервісу.

---

## Принципи MVP-фронтенду
- **Функціональність важливіша за дизайн** на першій версії
- Готові компоненти: Tailwind + shadcn/ui — без власної дизайн-системи
- Один SPA на React, без SSR (не потрібен SEO для приватного кабінету)
- Адаптивність: основні сторінки до 768px (mobile-friendly), складний редактор воронки — desktop-first (≥ 1024px)
- Один темний / один світлий вигляд — без перемикача (одну тему на старті)
- i18n не на MVP, але закласти структуру (всі тексти через `t('key')`) для майбутнього

---

## Технологічний стек
- React 18 + Vite + TypeScript
- React Router v6
- TanStack Query (react-query) для запитів і кешу
- Zustand для глобального стану (поточний проект, current user)
- Tailwind CSS + shadcn/ui компоненти
- React Hook Form + Zod для форм і валідації
- Recharts для графіків

---

## MUST HAVE

### Публічні сторінки
- **`/` (Landing)** — мінімум: hero + 3 фічі + CTA "Sign up". Можна замінити redirect на `/signup` для MVP, лендінг винести в `12-nice-to-have`
- **`/signup`** — реєстрація
- **`/login`** — вхід
- **`/forgot-password`** — запит на скидання
- **`/reset-password`** — встановлення нового пароля по token
- **`/verify-email`** — підтвердження email по token
- **`/blocked`** — інформативна сторінка для заблокованих юзерів

### Layout залогіненого користувача
- **Topbar:**
  - Логотип
  - Селектор поточного проекту (dropdown)
  - Профіль (avatar/initials → меню: Profile, Logout)
- **Sidebar** (для розділів поточного проекту):
  - Dashboard
  - Subscribers
  - Funnels
  - Broadcasts
  - Settings (з підрозділами: Bot, Custom Fields, API, General)
- **Main content area** з breadcrumbs

### Сторінки: Projects management
- `/projects` — список власних проектів (cards або table)
- Кнопка "+ New project" → модал з формою
- Якщо проектів 0 → empty state з онбординг-кроками

### Сторінки: Dashboard проекту
- `/projects/{id}` — головна сторінка проекту
- Метрики з епіка `09-аналітика`

### Сторінки: Subscribers
- `/projects/{id}/subscribers` — таблиця з фільтрами і пошуком
- Картка підписника: side panel або окрема сторінка `/subscribers/{subId}`
- Кнопка "Export CSV"

### Сторінки: Funnels
- `/projects/{id}/funnels` — список усіх воронок зі статусами
- `/projects/{id}/funnels/new` — створити нову
- `/projects/{id}/funnels/{funnelId}` — редактор воронки:
  - Вертикальний список кроків
  - Кнопка "+ Add step" → picker типів
  - На кожному кроці: edit / move up / move down / delete
  - Inline-форма для простих кроків (Send Message)
  - Модалка для складних (Wait for Reply з кнопками і гілками)
  - Превью повідомлень у бічній панелі
- `/projects/{id}/funnels/{funnelId}/stats` — статистика
- Toggle статусу `active` / `paused` / `draft`

### Сторінки: Broadcasts
- `/projects/{id}/broadcasts` — список з фільтрами
- `/projects/{id}/broadcasts/new` — майстер створення (1-2-3 кроки або одна форма)
- `/projects/{id}/broadcasts/{broadcastId}` — деталі і статистика
- Превью повідомлення під час редагування

### Сторінки: Settings проекту
- `/projects/{id}/settings/general` — назва, опис, timezone, delete project
- `/projects/{id}/settings/bot` — Telegram bot connection
- `/projects/{id}/settings/fields` — Custom Fields CRUD
- `/projects/{id}/settings/tags` — Tags management
- `/projects/{id}/settings/api` — API key + webhook URL + API logs

### Профіль користувача
- `/profile` — ім'я, email, change password, delete account

### Адмінка для Super Admin
- Окремий layout `/admin/*`
- Деталі в `10-адмін-панель`

### UX-вимоги
- **Loading states:** skeleton / spinner на КОЖНІЙ сторінці і КОЖНОМУ запиті
- **Empty states:** "У вас ще немає підписників", "Ще немає воронок" — з ілюстрацією і CTA
- **Error states:** "Щось пішло не так" з кнопкою retry
- **Toast-нотифікації** для дій (saved, deleted, error) — react-hot-toast або sonner
- **Підтвердження** для деструктивних дій (delete project, regenerate API key) — модалкою
- **Optimistic updates** для швидких дій (toggle status) — щоб UI не "лагав"
- **Form validation** з зрозумілими помилками під полями (Zod + RHF)
- **Keyboard navigation** на основних формах (Tab, Enter, Esc)

### Performance
- Code splitting per route (lazy loading)
- Bundle size під 500kb gzipped на initial load
- LCP < 2.5s на 4G

---

## Що НЕ входить (свідомо)
- Drag-and-drop конструктор воронок з canvas — `12-nice-to-have`
- Темна тема / перемикач світло-темно — `12-nice-to-have`
- i18n (мультимовність) — `12-nice-to-have`
- PWA / installable — `12-nice-to-have`
- Анімації переходів між сторінками — `12-nice-to-have`
- Mobile-first складний редактор воронок — `12-nice-to-have`
- Власна дизайн-система — `12-nice-to-have`
- A11y стандарти WCAG AA — на MVP basic accessibility, повний compliance — `12-nice-to-have`

---

## Acceptance criteria
1. Користувач від реєстрації до запущеної першої воронки доходить менше ніж за 10 хв без зовнішніх інструкцій
2. Усі сторінки рендеряться менше ніж за 1 секунду після loading state
3. Немає 404 і JS-помилок у консолі на основних сценаріях
4. Деструктивні дії завжди мають підтвердження
5. Toast-нотифікації працюють на success і error
6. Mobile-вигляд (768px) основних сторінок не зламаний

---

## Залежності
- Усі бекенд-епіки (фронт повторює backend-функціональність)

---

## Технічні нотатки
- Структура папок: `pages/`, `components/`, `hooks/`, `lib/`, `stores/`, `api/`
- Усі API-запити через TanStack Query з singleton-клієнтом
- Auth-token у HTTP-only cookie АБО в memory + refresh у cookie (на вибір команди)
- Розгляньте Tanstack Router як альтернативу React Router (типи краще)
- **Цей епік великий — рекомендую розбити на підзадачі:** (1) layout + auth pages, (2) projects list + create, (3) subscribers, (4) funnels editor, (5) broadcasts, (6) settings, (7) admin panel
