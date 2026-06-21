---
# Creation date (YYYY-MM-DD)
created: 2026-05-09

# Status: draft | approved
status: approved

# Work type: feature | bug | refactoring
type: bug

# Feature size: S (1-3 files, local fix) | M (several components) | L (new architecture)
size: S
---

# User Spec: 04-remember-me-cookie

## Что делаем
Виправляємо баг: чекбокс «Запам'ятати мене» на сторінці логіну не робить
сесію persistent. Зараз сервер ставить TTL сесії в MongoDB на 30 днів, але
cookie `SESSION` приходить без `Max-Age`/`Expires` — браузер видаляє його
при закритті, і юзер логаутиться попри 30-денний серверний TTL. Після
фіксу логін із галочкою повертає cookie з `Max-Age=2592000`, і браузер
зберігає сесію між закриттями вікна.

## Зачем
Чекбокс «Запам'ятати мене» зараз UX-broken: юзер ставить галочку, очікує
залишитись залогіненим між сесіями браузера, але насправді логаутиться при
кожному закритті вікна. Це знижує довіру до продукту і змушує юзера ввести
пароль знову там, де він явно дав згоду цього не робити. Фікс відновлює
обіцяну поведінку та усуває розбіжність між серверним TTL (30 днів) і
терміном життя cookie на клієнті.

## Как должно работать

**Сценарій 1 — рerзистентний логін:**
1. Юзер на `/auth/login` тисне «Запам'ятати мене», вводить пароль і логіниться.
2. Бекенд відкриває сесію в Mongo з TTL 30 днів і повертає cookie `SESSION`
   із `Max-Age=2592000` (30 діб у секундах) та `Expires=<+30d>`.
3. Юзер закриває браузер. Через годину/день/тиждень відкриває знову → cookie
   на місці, бекенд знаходить сесію в Mongo → юзер потрапляє на `/dashboard`
   без повторного логіну. Працює до 30 днів від моменту логіну, потім жорсткий
   релогін.

**Сценарій 2 — звичайний логін без галочки:**
1. Юзер логіниться без галочки.
2. Бекенд відкриває сесію з TTL 24 години і повертає cookie без `Max-Age` /
   `Expires` (як зараз — session cookie).
3. Юзер закриває браузер → cookie зникає → відкриває знову → редірект на
   `/auth/login`.

**Сценарій 3 — logout:**
1. Юзер тисне «Logout» (з будь-якої гілки 1 чи 2).
2. Бекенд інвалідує сесію, Spring Session емітить cookie з `Max-Age=0`.
3. Браузер видаляє cookie. Юзер на `/auth/login`.

**Сценарій 4 — pre-fix backward compat:**
1. Юзер уже мав активну сесію до деплою фіксу. Cookie без `Max-Age`
   зберігся між закриттями браузера тільки доки юзер не закривав вікно.
2. Після деплою фіксу — нічого не ламається: при першому ж запиті бекенд
   приймає старий cookie, авторизує юзера за серверним TTL. Доки юзер не
   релогіниться, нова поведінка cookie на нього не поширюється.

## Критерии приёмки
- [ ] **AC-1:** `POST /api/auth/login` з `rememberMe=true` повертає
  `Set-Cookie` для cookie сесії з `Max-Age=2592000`
  (= `app.session.ttl-remember-me-days * 86400`) і `Expires` приблизно +30
  днів. Прапори `Path`, `HttpOnly`, `SameSite`, `Secure` зберігаються
  такими ж, як зараз (зчитуються з конфігу, не дублюються в коді фіксу).
- [ ] **AC-2:** `POST /api/auth/login` з `rememberMe=false` повертає
  `Set-Cookie` БЕЗ атрибутів `Max-Age` і `Expires` (session cookie,
  як зараз). Решта прапорів незмінні.
- [ ] **AC-3:** Серверний TTL у MongoDB лишається синхронним з cookie:
  `rememberMe=true` → 30 днів, `rememberMe=false` → 24 години. Існуючі
  тести на відкриття сесії продовжують проходити без модифікацій.
- [ ] **AC-4:** Pre-fix-cookies (видані до деплою фіксу) при першому
  запиті після деплою продовжують авторизувати юзера, доки серверна сесія
  не протухне або юзер не релогіниться. Деплой не інвалідує існуючі сесії.
- [ ] **AC-5:** Logout (`POST /api/auth/logout`) очищає cookie через
  `Max-Age=0` незалежно від того, був на сесії `rememberMe` чи ні.
- [ ] **AC-6:** Auto-login після register залишається без галочки —
  session-only cookie. Поведінка не регресує.
- [ ] **AC-7:** Юзер релогіниться зі зміною прапорця: був `rememberMe=true`,
  тепер `rememberMe=false` (або навпаки) — новий cookie перезаписує старий
  з відповідними атрибутами. Без додаткових ручних дій від юзера.
- [ ] **AC-8:** Cookie name, що пишеться, відповідає поточно налаштованому
  `server.reactive.session.cookie.name` (default `SESSION`). Зміна імені
  через env vars працює без правок коду фіксу.

## Ограничения
- **Sliding expiration не вмикаємо.** Cookie `Max-Age` ставиться один раз
  при логіні і не подовжується на наступних запитах. Активний юзер
  релогіниться приблизно раз на 30 днів. Простіше і прозоріше для MVP, при
  потребі додамо sliding-window окремою фічею.
- **Backward compatibility.** Існуючі pre-fix cookies НЕ інвалідуються при
  деплої. Старий cookie (без `Max-Age`) продовжує працювати до природного
  закінчення серверного TTL або релогіну. Натуральна міграція.
- **Cookie security flags не дублюються в коді.** `httpOnly`, `secure`,
  `sameSite`, `name` читаються з поточного конфігу
  (`server.reactive.session.cookie.*` у `application.properties` →
  переноситься env vars у проді). Логіка фіксу не задає ці значення явно.
- **Frontend не змінюється.** Чекбокс на `/auth/login` уже існує, прапорець
  `rememberMe` уже шлеться у POST `/api/auth/login`. Жодної frontend-роботи.
- **Обмеження браузера на incognito.** Браузер у приватному режимі ігнорує
  `Max-Age` і видаляє всі cookies при закритті вікна — це поза нашим
  контролем, документуємо як known limitation.
- **Інші auth-flow не чіпаємо.** `terminate-all-sessions`,
  `change-password`, `deleteAccount` (інвалідація сесій з Mongo) і
  поточний `logout` — без змін. При терміації всіх сесій cookie на
  пристрої залишається з попереднім `Max-Age`, але перший наступний
  запит без сесії в Mongo → юзер як анонім → редірект на login.
  Окрему cookie-clear на ці шляхи НЕ додаємо.
- **Cookie з `Max-Age` пишеться лише на login-flow з `rememberMe=true`.**
  Інші місця, де фреймворк може створювати сесію (lazy session,
  WebSocket upgrade тощо), отримують session-only cookie за замовчуванням.
- **Стек:** Java 21, Spring Boot 3.5.x, Spring WebFlux, Spring Session
  (`spring-session-data-mongodb`).

## Риски
- **Риск 1:** Кастомний bean для запису cookie може втратити синхронізацію
  з конфігом cookie (`server.reactive.session.cookie.*`), якщо у проді
  захочуть змінити `SameSite`/`Secure` через env vars. **Митигация:**
  bean читає всі cookie-флаги з налаштувань рантайму, не з констант.
  Тести перевіряють, що зміна env vars справді змінює відповідний прапор
  у `Set-Cookie`.
- **Риск 2:** Поточний тестовий стенд бекенду не пропускає заголовок
  `Set-Cookie` у відповіді (підтверджено коментарем у тестах).
  **Митигация:** для нових тестів обрати інший спосіб тестового бутстрапу,
  який пропускає заголовки відповіді, або винести cookie-перевірку в
  unit-тест над компонентом, що пише cookie. Рішення — у техспеку.
- **Риск 3:** Передача прапорця `rememberMe` між сервісом логіну і
  компонентом запису cookie через runtime-канал — нова конвенція в коді.
  **Митигация:** канал scoped лише до login-flow, ключ — приватний;
  поведінка fallback-у при відсутності прапора (session-only cookie)
  явно покрита тестами.

## Технические решения
- Sliding expiration НЕ робимо — простіше і прозоріше для MVP. Активний
  юзер релогіниться раз на 30 днів — це норма для consumer-додатків такого
  рівня. При потребі додамо sliding-window окремою фічею.
- Pre-fix-cookies НЕ інвалідуємо при деплої. Backward compat важливіший
  за immediate consistency для одноразового переходу.
- Logout-flow і інвалідація сесій (terminate-all, change-password,
  deleteAccount) залишаються без змін: cookie очищається через `Max-Age=0`
  механізмом, що вже працює у фреймворку.
- Якщо runtime-прапорець `rememberMe` відсутній — пишемо session-only
  cookie (як зараз). Виняток не кидаємо: фікс безпечний для шляхів поза
  логіном (lazy session, lazy attribute init).
- Cookie security flags (`name`, `httpOnly`, `secure`, `sameSite`, `path`,
  `domain`) — джерело істини залишається в `application.properties` /
  env vars. Логіка фіксу їх лише читає, не дублює.
- Без додаткових логів від компонента запису cookie: він — тонкий слой,
  спостережуваність забезпечена існуючими auth-event логами
  (login_success, logout) у колекції `events`.
- UX-фіча «запам'ятовувати останній вибір rememberMe між сесіями» винесена
  в `workflow/improvements.md`, не входить у цей фікс.
- Конкретні класи, методи override та обраний спосіб тестування —
  визначаються в техспеку.

## Тестирование

**Unit-тесты:** делаются всегда, не обсуждаются. Покривають компонент
запису cookie: дві гілки (`rememberMe=true` → cookie має `Max-Age`;
відсутній або `false` атрибут → cookie без `Max-Age`), плюс перевірка
що cookie security flags зчитуються з конфігу (зміна `SameSite`/`Secure`/
`name` у конфізі дає відповідний прапор у `Set-Cookie`).

**Интеграционные тесты:** робимо. Два нових кейси на `/api/auth/login`
перевіряють реальний `Set-Cookie` заголовок із живого backend-стенда:
один із галочкою (`Max-Age` присутній), один без (`Max-Age` відсутній).
Точний підхід до тестового бутстрапу — у техспеку (поточний
`bindToApplicationContext` стенд не пропускає `Set-Cookie`, треба обрати
альтернативу).

**E2E тесты:** не робимо. Поведінка cookie точніше і дешевше тестується
на рівні integration; Playwright уже завантажений локалізацією і додавати
golden-path для cookie не потрібно. Manual smoke перед merge покриває
живий браузер.

## Как проверить

### Агент проверяет

| Шаг | Инструмент | Ожидаемый результат |
|-----|-----------|-------------------|
| 1. POST /api/auth/login з `rememberMe=true` | `curl -i -X POST $APP_URL/api/auth/login -H 'Content-Type: application/json' -d '{"email":"...","password":"...","rememberMe":true}'` | `Set-Cookie` для cookie сесії містить `Max-Age=2592000` і `Expires` ~+30 днів. `Path=/`, `HttpOnly`, `SameSite`, `Secure` зберігаються відповідно до конфігу env. |
| 2. POST /api/auth/login з `rememberMe=false` | `curl -i -X POST ...` (як вище, `rememberMe:false`) | `Set-Cookie` для cookie сесії БЕЗ `Max-Age` і без `Expires`. Інші прапори без змін. |
| 3. POST /api/auth/login повторно з тим же юзером, але `rememberMe=true` (після кроку 2) | `curl -i -X POST ...` | Cookie перезаписується — нове `Set-Cookie` уже з `Max-Age=2592000`. Натуральна міграція. |
| 4. POST /api/auth/logout (з cookie з кроку 1) | `curl -i -X POST $APP_URL/api/auth/logout -H 'Cookie: SESSION=...'` | `Set-Cookie` із `Max-Age=0` (cookie очищається). |
| 5. Pre-fix backward compat (manual seed cookie) | seed серверну сесію в Mongo з валідним id, віддати запит із cookie без `Max-Age` | `200 OK`, юзер залогінений. Сесія не інвалідується деплоєм. |

### Пользователь проверяет
- **Локально перед merge:** запустити стек (Docker Compose + backend +
  frontend dev-server). Логін з галочкою → DevTools → Application →
  Cookies → cookie сесії має `Expires` ~30 днів вперед. Закрити браузер,
  відкрити знову — потрапляє на `/dashboard`.
- **Те ж локально без галочки:** `Expires` рівне `Session`. Закрити
  браузер — на наступному відкритті редірект на `/auth/login`.
- **Logout-кнопка:** після logout cookie сесії зникає зі списку Cookies
  у DevTools.
