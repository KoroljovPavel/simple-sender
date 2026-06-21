# 12 — Nice to Have (фази після MVP)

Усе, що тут — це **наступні фази після того, як MVP запустився, отримав перших користувачів і ви зрозуміли реальні потреби.** Свідомо НЕ робимо в першій версії.

Ціль цього файлу — задокументувати ідеї і не забути про них, але й не починати робити завчасно.

---

## Phase 2 — Розширення базової функціональності

### Нові канали
- **Viber** (другий пріоритет після Telegram — найпростіший за API)
- **Instagram Direct** через Meta Graph API (вимагає бізнес-верифікації, 24-годинне вікно)
- **WhatsApp Business API** (потребує BSP-партнерства, шаблонів — складно)
- **Facebook Messenger** (Meta Graph API, схоже на Instagram)
- **Web chat widget** (вбудовуваний на сайт)
- **Email** як додатковий канал у воронках
- **SMS** через Twilio / Vonage

### Робота в команді
- Запрошення співавторів у проект через email
- Ролі: Owner / Editor / Viewer / Marketer (тільки broadcast)
- Audit log дій усередині проекту (хто що змінював)
- Передача власності проекту іншому
- Команди / організації (вище за Project)

### Підписники
- Імпорт CSV
- Складний segment-builder з AND/OR/NOT та умовами по custom fields
- Lead scoring (автоматичні бали за дії)
- Об'єднання дублікатів між каналами (один підписник = один Telegram + один Viber + один Email)
- GDPR-tooling (експорт даних користувача за email, право на видалення)
- Bulk-дії через UI ("додати тег усім, хто за фільтром")

### Воронки
- **Drag-and-drop візуальний редактор** з canvas (велика задача — 4-6 тижнів)
- A/B тести воронок (50/50 розщеплення)
- Conditional branching за custom fields, тегами, статусом
- HTTP-крок: викликати зовнішній API з воронки
- AI-крок: відповідь LLM з контексту (OpenAI / Anthropic API)
- Math expressions у кроках
- Маркетплейс шаблонів воронок (community)
- Розклад "запускати воронку щодня о 10:00 для всіх з тегом X"
- Subflow / sub-funnels (один у іншому)

### Розсилки
- A/B тести (різні версії повідомлення на 50/50)
- Click tracking з UTM-розміткою і коротким посиланням
- Recurring broadcasts (щотижня, щомісяця)
- AI-генерація варіантів тексту
- "Best time to send" — підбір оптимального часу за активністю аудиторії

### API
- GraphQL endpoint
- WebSocket для real-time оновлень
- Bulk-операції (масово створити підписників)
- OAuth-app для third-party інтеграцій від імені користувачів
- SDK на Node.js, Python, PHP
- Sandbox environment для тестування
- Webhook event filtering і replay

### Аналітика
- Cohort analysis
- Conversion funnel за UTM джерелами
- Click tracking по посиланнях
- Real-time дашборд через WebSocket
- Експорт звітів у PDF / Excel
- Інтеграції з GA4 / Yandex Metrika
- Custom dashboards (юзер сам збирає)
- Прогнози на основі історичних даних

---

## Phase 3 — Монетизація і масштабування

### Білінг і тарифи
- Тарифні плани: Free / Starter / Pro / Business
- Stripe / WayForPay / LemonSqueezy інтеграція
- Обмеження по підписниках, повідомленнях, проектах, ботах
- Trial period 14 днів
- Upgrade / downgrade flow
- Купони, промокоди
- Реферальна програма

### Адмін-панель розширена
- Імперсонація для саппорту (з логуванням)
- Системні розсилки адмінів усім користувачам
- Feature flags
- Фінансова статистика, MRR / ARR / LTV / churn
- Users by plan, conversion-funnel реєстрація → платний
- 2FA для Super Admin

### Безпека (юзерська)
- 2FA через TOTP (Google Authenticator)
- OAuth-логін: Google, GitHub, Telegram Login Widget
- Magic-link logins (без пароля)
- Зміна email з підтвердженням обох сторін
- Session management (бачити всі сесії, закривати конкретні)

### Інтеграції
- Zapier / Make.com / n8n
- Native: HubSpot, Pipedrive, AmoCRM, Bitrix24, Salesforce
- Stripe / WayForPay webhooks → автоматичне додавання тегу `paid`
- Google Sheets двосторонньо
- Notion, Airtable
- Calendly, Cal.com — для запису на консультації з воронки

### UI/UX
- Темна тема + перемикач
- i18n (українська, англійська, російська як мінімум)
- PWA / installable
- Анімації переходів
- Власна дизайн-система
- Лендінг-сайт з блогом, ресурсами, документацією
- Onboarding-tour для нових користувачів

---

## Phase 4 — Enterprise / Платформа

### Self-hosted версія
- Docker Compose / Helm chart для on-premise розгортання
- Ліцензування
- Окрема документація для DevOps клієнтів

### White-label
- Customers вашого сервісу можуть рендити-білити для своїх клієнтів
- Своя домен, бренд, лого
- Subscription-as-a-Service (multi-tenancy на рівень вище)

### Compliance
- SOC 2 Type II audit
- GDPR comprehensive tooling (DPA, sub-processors list, ROPA)
- HIPAA (для медичних кейсів)
- Data residency (EU, US, окрема інсталяція)
- Audit logs з 7-річним retention для регульованих індустрій

### Enterprise features
- SSO через SAML
- Custom RBAC (admin визначає ролі сам)
- Розширене API rate limits
- SLA з фінансовими зобов'язаннями (99.9%, 99.95%)
- Dedicated support, named CSM
- Private deployment

### Платформа
- Marketplace плагінів від third-party-розробників
- Custom integration builder (low-code)
- Public bot directory
- Партнерська програма для агенцій

---

## Як вирішувати, що робити наступним

Після релізу MVP:
1. **Слухайте перших 50 платних користувачів** — їхні запити пріоритетніші за ваш бекáг
2. **Дивіться на retention і churn** — якщо люди йдуть, спочатку шукайте чому
3. **Не починайте Phase 4 поки не поставили MRR на ~$10-20k/міс** — enterprise вимоги вб'ють фокус
4. **Phase 2 — за частотою запитів:** не "усе підряд", а топ-3 найчастіших

---

## Технічні підготовки на майбутнє

Деякі рішення на MVP мають закладати можливість масштабування:
- Структура БД готова до multi-channel (поле `channel_type` навіть якщо тільки `telegram`)
- Auth-система готова до OAuth (interface, не лише email-password)
- API готове до версіонування (`/v1/`)
- Frontend готовий до i18n (всі тексти через `t('key')`)
- Архітектура готова до винесення воронок-engine у окремий сервіс при потребі
