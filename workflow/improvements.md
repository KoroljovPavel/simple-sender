# Майбутні доробки

Список знайдених недоробок у вже реалізованому функціоналі. Це не нові фічі (для того є `12-nice-to-have`), а саме **довести до пуття** те, що вже частково існує в коді.

---

## Auth / Сесії

### Запам'ятати «Запам'ятати мене» — pre-select чекбокса при наступному логіні

**Зараз:**
- При кожному завантаженні `/auth/login` чекбокс `rememberMe` рендериться зі стандартного default (false) — навіть якщо юзер раніше тиснув галочку і вибрав persistent cookie. Йому щоразу треба клікати знову.
- Auto-login після register також жорстко `rememberMe=false` (`AuthService.openSession(saved, false, exchange)` в `AuthService.java:167`).

**Що зробити:**
- На frontend зберігати останнє значення `rememberMe` в localStorage (`auth.rememberMeDefault`).
- На монтажі `pages/auth/login.vue` зчитувати ключ і pre-select-ити чекбокс.
- При успішному логіні — оновити localStorage поточним значенням.
- Опціонально: розглянути перенесення прапорця у профіль User як `preferences.persistentLogin` (на бекенді), щоб вибір переходив між пристроями. Тоді auto-login після register може його врахувати, якщо до цього юзер уже мав сесію на цьому акаунті.
- Тести: vitest на компонент логіну (preselect з localStorage), Playwright не потрібен.

---

## Frontend / Маршрутизація

### Корінь `/` віддає 404 замість редіректу або лендінгу

**Зараз:**
- `frontend/pages/index.vue` відсутній — Nuxt на запит `/` повертає 404.
- При цьому є `frontend/pages/dashboard.vue` (для авторизованих) і `frontend/pages/auth/login.vue` (для неавторизованих), але немає точки входу між ними.
- Юзер, який зайшов прямо на `https://<host>/` (з закладки, з посилання, після видачі домену), бачить помилку 404 — поганий перший досвід і обриває funnel реєстрації.

**Що зробити:**
- ✅ ЧАСТКОВО ЗРОБЛЕНО (Task 17 smoke, F-min-10): додано `frontend/pages/index.vue` як чистий редірект —
  авторизований → `/dashboard`, неавторизований → `/auth/login` (`layout:false`, redirect у setup). 404 на `/`
  усунено. Тест `tests/pages/index.spec.ts` (обидві гілки).
- ЗАЛИШИЛОСЬ (landing): замінити редірект на презентаційну (landing) сторінку — коротко про проєкт
  (опис із `project.md` Overview), CTA-кнопки на `/auth/register` і `/auth/login`, без даних з API. Лише для
  неавторизованих (авторизований і далі → `/dashboard`).
- i18n: усі тексти лендінгу через `t('landing.*')`, додати ключі в `uk.json` і `en.json` (build-gate
  `check-locales.mjs` зловить розбіжність).
- Тести: розширити `index.vue`-спек рендером CTA для неавторизованого.

---

## Auth / Email verification

### Неможливо підтвердити пошту, якщо ти вже авторизований в акаунті

**Зараз:**
- Юзер реєструється на пристрої A → auto-login (сесія активна) → лист із verification-посиланням приходить пізніше.
- Він відкриває це посилання у тому ж браузері, де вже залогінений (або через "Open in browser" з пошти на тому ж пристрої).
- Сторінка `/auth/verify-email?token=...` падає в `auth.global.ts`-редірект на `/dashboard` (бо сесія є), запит на `POST /api/auth/verify-email` не виконується, токен лишається невикористаним.
- Як наслідок: статус юзера в БД лишається `pending`, фічі що залежать від verified-email (якщо такі з'являться) не доступні, юзер не розуміє чому "пошта не підтверджена".

**Що зробити:**
- `auth.global.ts`: винести `/auth/verify-email`, `/auth/reset-password`, `/auth/forgot-password` з-під авто-редіректу авторизованих юзерів — це token-flow сторінки, які мають працювати в обох станах (logged-in і anonymous).
- На сторінці `/auth/verify-email`:
  - Якщо є `token` у query → завжди слати `POST /api/auth/verify-email` (навіть якщо є активна сесія).
  - На 200 → toast success + редірект на `/dashboard` (якщо logged-in) або `/auth/login` (якщо ні).
  - На 410 (token expired) → форма "запросити новий лист" (вже існує).
- Backend: переконатися що `POST /api/auth/verify-email` приймає токен **без вимоги авторизованої сесії** (анонімний endpoint) і коректно піднімає статус юзера з `pending` → `active` навіть якщо ownership-юзер зараз має активну сесію.
- Тести: integration test на verify-email з активною сесією того ж юзера; vitest на компонент verify-email page (token є → POST виконано незалежно від `auth.user`).

<!-- "Projects / UX: timezone picker з пошуком" — перенесено в work/05-projects/tasks/15.md, пункт 4. -->

---

## Subscribers / Пошук

### Пошук у списку підписників не знаходить за фрагментом (whole-word `$text`)

**Зараз:**
- Пошук на `/projects/{id}/subscribers` працює через MongoDB `$text` (`SegmentFilterBuilder.java:92` — `TextCriteria.forDefaultLanguage().matchingAny(search.split("\\s+"))`) по полях `firstName` (вага 3), `lastName` (3), `username` (5), `language="none"` без стемера (Decision 6, `@Document(language="none")` + `@TextIndexed` у `Subscriber.java`).
- `$text` матчить **цілими токенами**, не підрядком і не префіксом. Поля токенізуються по роздільниках (пробіл, `@`, `_`, `-`).
- Наслідок: користувач вводить фрагмент (`pvl`, `@krlvpv`) і **нічого не знаходить**, бо немає токена, що дорівнює фрагменту. Працює лише повне слово/username (`pvlkr`, `krlvpvl`, `Павло`, повний `@korolov_pavlo`). Це контрінтуїтивно — очікувана UX CRM-пошуку це typeahead «почав вводити → бачу збіги».
- Альтернатива C (незаякорений `/term/i`, contains) відкинута: case-insensitive regex **не використовує індекс** → повний скан усіх доків проєкту на кожне натискання; на 50–100к підписників (цільовий розмір) це сотні мс–секунди, найгірший випадок (нульовий збіг, як `pvl`) — повний скан щоразу. Не масштабується.

**Що зробити (варіант B+ — індексований префікс):**
- Додати в `Subscriber` lowercase-дублі пошукових полів: `firstNameLower`, `lastNameLower`, `usernameLower` (заповнювати при кожному write підписника — реєстрація/оновлення профілю/stub-replacement).
- Бекфіл наявних документів одноразовою міграцією (job або скрипт): `usernameLower = username.toLowerCase()` тощо по всій колекції.
- Індекси: звичайні (не text) по `(projectId, usernameLower)`, `(projectId, firstNameLower)`, `(projectId, lastNameLower)` — щоб заякорений range-scan тримав індекс per-project.
- Search-гілка в `SegmentFilterBuilder.build`: замість `TextCriteria` будувати `$or` із **заякореного** case-sensitive regex по lowercase-полях від lowercase-інпута: `^Pattern.quote(term)` (екранувати спецсимволи regex!), напр. `Criteria.where("usernameLower").regex("^" + Pattern.quote(term))`. Заякорений `/^term/` по індексованому полю → range-scan, масштабується на 100к+.
- Обмеження B+: матчить лише з початку поля (`pvl`→`pvlkr` ✅, `pvl`→`krlvpvl` ❌). Це покриває ~90% typeahead по імені/@username. Якщо згодом знадобиться mid-word substring — окрема доробка через триграми (Atlas Search недоступний, бо self-hosted Mongo).
- Зберегти `MIN_SEARCH_CHARS = 2` і keyset-пагінацію (заякорений regex сумісний із sort-індексом краще, ніж `$text`).
- Рішення про долю наявного text-індексу: або лишити (як fallback/інше використання), або прибрати разом із `@TextIndexed`, якщо `$text` більше ніде не потрібен — звірити перед видаленням.
- placeholder/тултіп пошуку оновити: «шукайте за початком імені або @username».
- Тести: оновити `SegmentFilterBuilderTest` (regex-гілка замість text), `SubscriberControllerIT` (фрагмент-префікс знаходить, mid-word — ні, екранування спецсимволів `.`/`*` в інпуті не ламає запит), бекфіл-міграцію покрити IT.
- Це зачіпає схему + індекси + міграцію → робити окремою задачею по процесу (кандидат на Task 18), не однорядкова правка.

---

## Воронки / Композиція (нові типи кроків)

> Виняток зі скоупу файлу: це не доробка наявного, а два нові типи кроків. Зафіксовано тут на
> прохання, як напрям. Детальний user-spec — коли фаза стане активною (роадмап: одна фаза за раз).
> Обидва кроки — про композицію воронок, тому розглядати разом.

### Крок «Emit event» + event-тригер (внутрішня pub/sub-шина)

**Зараз:**
- Тригер живе на воронці (`triggerType` + `triggerValue` у `Funnel.java`). `fire()` робить exact-match
  один-до-одного.
- Partial-unique індекс `(projectId, triggerType, triggerValue)` filtered `status='active'` →
  **рівно одна активна воронка на тригер**. Навмисне спрощення Phase 1.
- Phase 3 (`12-funnels-triggers`) уже планує API-event тригер (`POST /api/v1/events` з `event_name`).

**Що зробити:**
- Новий крок `EMIT_EVENT`: публікує іменований рядок-подію. Матчинг виносимо в систему тригерів,
  не в конфіг кроку — крок лише емітить строку; які воронки реагують, вирішує тригер
  (`triggerType=event`, `triggerValue=event_name`).
- Об'єднати з Phase-3 API-event тригером: один namespace `event_name`, два джерела емісії — ззовні
  (API) і зсередини (крок). Окрему підсистему не будувати.
- **Блокер fan-out:** для «один рядок → багато воронок» (one-to-many) поточний unique-індекс треба
  змінити. Варіанти:
  - **A (мінімум):** лишити 1:1. Один event → одна воронка. Майже без змін схеми.
  - **B (рекомендований):** релакс `Funnel` до `List<Trigger>`, прибрати unique для типу `event`
    (лишити тільки для `on_start`). Один рядок фанаутить у N воронок; воронка має кілька входів.
    Рефактор: схема + індекс + `FunnelTriggerService.fire` робить `find...List` замість `findOne`.
  - **C (окрема сутність `Trigger` з CRUD):** відхилено як YAGNI — лише якщо тригерами керують
    незалежно від воронок (reuse, окремий lifecycle/UI).
- Висновок: семантику матчингу — в тригер-систему (Phase 3); окрему сутність не будуємо; достатньо
  релаксу 1:1 → 1:N.

### Крок «Subscribe to funnel» (chaining / sub-funnel)

**Зараз:**
- Немає способу з однієї воронки enrol-нути підписника в іншу. `fire()` уже вміє створювати
  `FunnelExecution` — база для цього є.

**Що зробити:**
- Новий крок `SUBSCRIBE_TO_FUNNEL`: на `execute()` стартує дочірню воронку для того ж subscriber.
- Рішення, які прийняти явно:
  1. **Fire-and-forget vs await.** Рекомендовано fire-and-forget для v1: батько йде далі одразу.
     Await (пауза до завершення дочірньої) вимагає нового wait-стану + трекінгу parent↔child +
     резюму батька — окрема велика складність.
  2. **Захист від циклів** (головний ризик): A→B, B→A = нескінченний enroll. Закрити обов'язково —
     cycle-detection при збереженні (статичний граф), max-depth в execution або per-subscriber
     rate-limit на enroll.
  3. **Тільки `active` ціль:** пряма підписка обходить тригер-матчинг, але вимагати `status=active`
     цілі — не enrol-ити в `draft`/`paused`.
  4. **Re-enter guard:** дочірня має свій partial-unique `(funnelId, subscriberId)`; дубль →
     `DuplicateKeyException` → swallow (як для `/start`); поважати `allowReEnter` цілі.
- Залежить лише від engine (Phase 1, done). Тригерів не потребує — технічно можна навіть до Phase 2.

### Крок «API call to external service» (HTTP-крок + response → змінні)

**Зараз:**
- HTTP-крок свідомо поза scope (`06-воронки` README → `12-nice-to-have`).
- `StepExecutor.sendImage` навмисно НЕ дереференсить URL (Telegram сам тягне зображення → no SSRF).
  HTTP-крок ламає цей інваріант: бекенд робить outbound-виклик сам → **SSRF-ризик** (головне).
- `SET_CUSTOM_FIELD` уже вміє писати в custom fields через `SubscriberCustomFieldsService`
  (validate → normalize → apply → `recordCustomFieldsSet`, Decision 11).

**Що зробити:**
- Новий крок `API_CALL`: метод (GET/POST/PUT/PATCH/DELETE), URL (шаблонізація `{user.*}`/`{custom.*}`),
  headers, body (templated), timeout.
- **Response → змінні:** з JSON-відповіді витягувати значення (JSONPath / dot-path) і присвоювати в
  custom fields підписника через **той самий** `SubscriberCustomFieldsService` validate→normalize→
  apply→`recordCustomFieldsSet`, що й `SET_CUSTOM_FIELD` — тип кожного target-поля валідується як
  зараз. Кілька мапінгів (response-path → customFieldKey) на один крок.
- Outcome: 2xx → CONTINUE; помилка/таймаут — configurable: прапорець «continue on error» (з опц.
  збереженням status code в поле) або FAIL. Для v1 рекомендую continue-on-error за замовч.

**Безпека (критично, перед реалізацією):**
- **SSRF:** бекенд тепер ходить на довільний URL від користувача. Треба: блок private/loopback/
  link-local діапазонів (RFC1918, `127.0.0.0/8`, `169.254.169.254` метадата-endpoint), denylist схем
  (тільки `https`, можливо `http`), заборона/контроль redirect на internal, guard від DNS-rebinding,
  whitelist портів. Розглянути egress через окремий proxy.
- **Секрети в headers (API-ключі):** не зберігати в plaintext у снапшоті кроку — шифрувати як bot
  token (є `common/crypto`). У `stepsSnapshot` лежить шифротекст.
- **PII / Decision 16:** не логувати rendered URL/body/response. Обмежити РОЗМІР response (захист від
  OOM). Templated body може лікати custom fields у зовнішній сервіс — навмисно, автор має розуміти.

**Engine fit:**
- I/O-крок як `SEND_MESSAGE`: синхронний у tick під claim. На virtual threads блокування ок, але
  **timeout обов'язковий** (не висіти на повільному endpoint). Funnel-level retry немає (як зараз).
- Idempotency: при краху після POST, але до commit progress → execution лишається `in_progress`
  (stuck, не повториться) — at-most-once, узгоджено з наявною семантикою send для non-idempotent POST.
- Залежить лише від engine (Phase 1, done).

### Розміщення в роадмапі
- Крок 1 — половина Phase 3 (`12-funnels-triggers`): розширити «API-event тригер» до «event-тригер
  з двома джерелами (API + internal emit)» + релакс 1:1 → 1:N.
- Крок 2 — окремий вертикальний інкремент (нова Phase 5 або причепити до Phase 3 за темою композиції).
- Крок 3 (API call) — `12-nice-to-have` або окрема фаза; gate — SSRF-захист має бути готовий до релізу.

---

## Інфраструктура / Міграції БД

### Adopt Mongock (формальний інструмент міграцій MongoDB)

**Зараз:**
- `spring.data.mongodb.auto-index-creation=true` (`application.properties:9`) — Spring авто-створює
  індекси з анотацій при старті. **Зміна визначення** індексу (напр. partial-фільтра) з тим самим
  іменем → Mongo не мутує індекс in-place → конфлікт → індекс доводиться **дропати руками**.
- Формального інструменту міграцій нема (`mongock/mongobee/liquibase/flyway` відсутні в `build.gradle`).
- Є hand-rolled патерн стартових міграцій: `FunnelStepIdBackfill.java`, `SuperAdminSeeder.java`
  (ApplicationRunner-стиль, без впорядкування/трекінгу/ідемпотентності-з-коробки).

**Що зробити:**
- Завести **Mongock** (`mongock-springboot-v3` + `mongodb-springdata-v4-driver`): changelog-колекція,
  впорядковані changeset'и, ідемпотентність, бекфіли як код.
- **Вимкнути** `auto-index-creation` і перенести керування індексами в changeset'и (інакше дві системи
  б'ються). Міграція наявних index-визначень (`@Indexed`/`@CompoundIndex`/partial-unique по всіх
  колекціях) у перший changeset — основна робота.
- Існуючі hand-rolled міграції (`FunnelStepIdBackfill`, `SuperAdminSeeder`) переоформити в changeset'и.

**Розміщення:** окремий вертикальний інкремент (інфра), робити **одразу після** `12-funnels-triggers`.
Рішення (інтерв'ю Phase 3, варіант **ii**): фаза тригерів везе свою index-зміну **hand-rolled
стартовою міграцією** (патерн `FunnelStepIdBackfill`, ідемпотентна — користувач НЕ дропає індекс
руками); Mongock — наступною задачею, і ця index-зміна стане одним із перших changeset'ів.

