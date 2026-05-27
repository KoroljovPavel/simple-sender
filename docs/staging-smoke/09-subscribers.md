# 09-subscribers — Staging Smoke Runbook (Manual, ~20–25 хв)

Ручна процедура перевірки CRM-контуру підписників (Epic 09) на staging
після деплою. Оператор працює з одноразовим ботом у `@BotFather`,
ngrok-тунелем (для локального staging) або безпосередньо staging-URL
(для повного staging-деплою), `mongosh` і власним поштовим аккаунтом
для перевірки export-email.

Цей runbook — пост-деплой verification gate для фічі: реальний
Telegram-сервер, `setWebhook`-flow, реальна email-доставка signed-URL і
рендер кирилиці у спредшиті неможливо емулювати в CI. Runbook покриває
happy-path життєвий цикл підписника (реєстрація → теги → кастомні поля →
персональне повідомлення → відписка → реактивація → CSV-експорт →
signed-URL → refresh), а також fail-CLOSED-поведінку refresh-URL під час
Redis-outage. Решта AC закриті інтеграційними / E2E-тестами
(`*ControllerIT`, `*IT`, `frontend/e2e/subscribers.spec.ts`) — див.
секцію **Чек-ліст результатів**.

Мова виконання: всі команди + назви UI-елементів / полів — англійською;
коментарі — українською. Усі приклади slug використовують канонічну
форму `^[a-z0-9_-]{1,32}$` (напр. `smoke_test`, `paid-2024`, `2024_vip`).

## Передумови

- Staging URL бекенду (`APP_URL`) досяжний і `GET {APP_URL}/health`
  повертає `HTTP 200`. Якщо тестуєте локальний staging — підніміть
  ngrok-тунель (крок 2), і `APP_URL` буде HTTPS-URL від ngrok.
- Staging frontend задеплоєний і вказує на той самий бекенд.
- Staging-аккаунт-адміністратор / test-owner із принаймні одним
  проектом без активного `CONNECTED`-боту (або з вільним слотом).
- Ваш персональний Telegram-аккаунт із доступом до `@BotFather` і
  можливістю писати боту в приватний чат.
- Поштова скринька, на яку прийде export-email (адреса власника
  проекту). За потреби — одноразова тестова скринька.
- `mongosh` з доступом до staging Mongo (db: `botfunnel`). Connection
  string береться зі staging secret store — НЕ вставляйте його в чат,
  коміти або скріншоти. У командах нижче плейсхолдер `mongodb://…/botfunnel`.
- `ngrok` встановлений локально (`brew install ngrok` на macOS).
  Безкоштовний акаунт достатній, але URL session-scoped: при
  переконнекті ngrok URL змінюється — крок **setWebhook** треба повторити.
- Спредшит-застосунок для відкриття CSV (LibreOffice Calc / Excel /
  Numbers / Google Sheets) — для перевірки рендеру кирилиці + UTF-8 BOM.
- `<projectId>` — береться з URL сторінки проекту
  (`/projects/<projectId>/subscribers`) або з консолі браузера
  `localStorage.getItem('bot-funnel.currentProjectId')`. Підставляйте
  значення без лапок.

## Кроки

### 1. Створити одноразовий бот у `@BotFather`

У Telegram напишіть `@BotFather` → `/newbot` → дайте ім'я та username
(напр. `smoke_subs_<дата>_bot`). Збережіть **bot token** у secret store
(НЕ в чат). Цей токен буде вставлено в адмінці на кроці 3.

### 2. Підняти ngrok-тунель (тільки для локального staging)

```
ngrok http 8080
```

Скопіюйте HTTPS-forwarding-URL (напр. `https://ab12cd34.ngrok-free.app`)
— це ваш `APP_URL`. Перевірте `curl {APP_URL}/health` → `HTTP 200`.

### 3. Підключити бот до проекту

В адмінці: `/projects/<projectId>/settings/bot` → вставте bot token →
**Connect**. Бекенд викличе `setWebhook` на Telegram із згенерованим
webhook-secret. Перевірте, що UI показує статус `CONNECTED`.

### 4. Надіслати реальний `/start` і перевірити появу підписника (≤2s)

Зі свого Telegram напишіть боту `/start`. Відкрийте
`/projects/<projectId>/subscribers` → протягом ~2 секунд має з'явитись
рядок з вашим іменем (за потреби оновіть сторінку — `loadFirstPage`
рефетчить при монтуванні). Альтернативна перевірка в Mongo:

```
mongosh "mongodb://…/botfunnel" --eval 'db.subscribers.find({projectId:"<projectId>"}).pretty()'
```

Має бути документ зі `status: "ACTIVE"`, заповненими `telegramUserId`,
`telegramChatId`, `subscribedAt`, `lastSeenAt`.

### 5. Додати тег `smoke_test` через UI → лічильник на `/tags`

Відкрийте профіль підписника (клік по рядку) → таб **Tags** → введіть
`smoke_test` у combobox → **Add**. Має з'явитись chip `smoke_test`.
Перейдіть на `/tags` → рядок `smoke_test` із колонкою
**Підписників** = `1`.

> Канонічний slug: тег приймається лише якщо відповідає
> `^[a-z0-9_-]{1,32}$`. Спробуйте ввести `Smoke Test` (з пробілом) — UI
> має показати помилку валідації й не відправити запит.

### 6. Додати кастомне поле `city` (type=string) і встановити значення

`/custom-fields` → **Додати поле** → name `city`, label `City`, type
`Текст (string)` → **Додати**. Рядок `city` з'являється у таблиці.
Поверніться в профіль підписника → таб **Custom Fields** → у полі `city`
введіть `Київ` → **Зберегти**.

### 7. Надіслати персональне повідомлення через UI → приходить у Telegram

У профілі підписника → **Send personal message** → введіть `hello` →
**Send**. Повідомлення `hello` має прийти у ваш Telegram-чат із ботом
протягом кількох секунд. В Mongo перевірте подію:

```
mongosh "mongodb://…/botfunnel" --eval 'db.subscriber_events.find({eventType:"personal_message_sent"}).sort({createdAt:-1}).limit(1).pretty()'
```

### 8. `/stop` → статус `Unsubscribed` (≤2s); `/start` → реактивація з даними

Зі свого Telegram надішліть `/stop`. У профілі / списку статус має
перемкнутись на **Unsubscribed** протягом ~2 секунд. Потім надішліть
`/start` знову — підписник реактивується (`ACTIVE`), а тег `smoke_test`
і кастомне поле `city=Київ` мають зберегтися (НЕ скидаються при
реактивації).

### 9. Export CSV → email зі signed-URL → завантаження → рендер кирилиці

На `/subscribers` → **Export CSV** → у діалозі **Експортувати**. UI
показує toast **Експорт розпочато**. Через кілька секунд на пошту
власника проекту приходить лист **Експорт підписників готовий** із
signed-URL. Натисніть посилання → завантажиться CSV. Відкрийте файл у
спредшиті:

- Перший символ файлу — UTF-8 BOM (кирилиця рендериться без «кракозябр»).
- Колонка `custom_fields` містить JSON зі значенням `Київ` для вашого
  підписника.
- Колонки в порядку: `subscriber_id, telegram_user_id, …, tags,
  custom_fields`.

### 10. Конкурентний експорт → 409 / банер у UI

Поки попередній експорт ще `PENDING`/`RUNNING` (або одразу повторно),
натисніть **Export CSV** вдруге. Бекенд має повернути `409
export_in_flight`, а UI — показати toast **Експорт уже виконується.
Зачекайте завершення.** (партіал-унік-індекс `exports_in_flight_unique`,
Decision 2).

### 11. Manual unsubscribe через UI → миттєвий flip статусу

У профілі (поки `ACTIVE`) → **Manual unsubscribe**. Статус-бейдж має
перемкнутись на **Unsubscribed** протягом ~1 секунди (пряма дія, без
окремого confirm-модалу — Task 10).

## Перевірка signed-URL refresh

Signed-URL живе 24 години (`SUBSCRIBER_EXPORT_URL_TTL_HOURS`, Decision
15), тож «дочекатись протермінування» вручну непрактично. Примусово
протермінуйте `expiresAt` тестового експорту через `mongosh`:

```
mongosh "mongodb://…/botfunnel" --eval 'db.subscriber_exports.updateOne({projectId:"<projectId>", status:"DONE"}, {$set:{expiresAt: ISODate("2020-01-01T00:00:00Z")}})'
```

Потім на `/subscribers` → **Останні експорти** → знайдіть DONE-рядок:
кнопка має змінитись з **Завантажити** на **Оновити посилання**.
Натисніть **Оновити посилання** → бекенд re-mint-ить токен (новий
`expiresAt`), кнопка повертається на **Завантажити** і нове посилання
працює.

Перевірте accepted-status matrix (Decision 15) за потреби:
- `DONE` → `200` `{downloadUrl, expiresAt}` (happy path вище);
- `PENDING`/`RUNNING` → `409 export_in_flight`;
- `PURGED` → `410 export_purged`;
- `FAILED` → `410 export_failed`;
- перевищення 5 refresh/год → `429 refresh_rate_limited`;
- Redis недоступний → `503 service_unavailable` (fail-CLOSED, див.
  Troubleshooting).

## Key-rotation runbook entry

> If key rotation required, plan downtime window where pending email links
> break; communicate to affected owners; UI 'Refresh URL' will re-mint with
> new key.

Практично: ротація `SUBSCRIBER_EXPORT_TOKEN_KEY` (через GitHub Actions
secret + редеплой) інвалідовує ВСІ раніше видані signed-URL — `verify`
повертає `401 invalid_token`. Це і є передбачена реакція на компрометацію
ключа. Власники, у яких є непротерміновані email-посилання, побачать
401; для відновлення вони відкривають **Останні експорти** → **Оновити
посилання**, що re-mint-ить токен новим ключем (поки GridFS-файл ще живий
у межах 7-денної retention). Сплануйте вікно простою й попередьте
залучених власників заздалегідь.

## Чек-ліст результатів

Позначте після прогону (цей runbook покриває happy-path лайфсайкл +
fail-CLOSED refresh; решта — автотести):

- [ ] Крок 4: `/start` → підписник `ACTIVE` з'явився ≤2s (AC1/AC6).
- [ ] Крок 5: тег `smoke_test` доданий; `/tags` лічильник = 1; невалідний
      slug відхилено в UI (AC7/AC8, Decision 11).
- [ ] Крок 6: кастомне поле `city=string` створене; значення `Київ`
      збережене для підписника (AC10/AC11).
- [ ] Крок 7: персональне повідомлення доставлене в Telegram; подія
      `personal_message_sent` записана (Decision 10).
- [ ] Крок 8: `/stop` → `Unsubscribed` ≤2s; `/start` → реактивація з
      тегом + кастомним полем (AC5).
- [ ] Крок 9: export-email прийшов; signed-URL завантажив CSV; UTF-8 BOM
      + `Київ` рендеряться у спредшиті (AC16, Decision 7).
- [ ] Крок 10: конкурентний експорт → `409 export_in_flight` + UI-toast
      (AC17, Decision 2).
- [ ] Крок 11: manual unsubscribe → flip статусу ≤1s.
- [ ] Перевірка refresh: протермінований URL → **Оновити посилання** →
      новий робочий URL (Decision 15).

## Cleanup

- В адмінці: `/projects/<projectId>/settings/bot` → **Disconnect** (бекенд
  викличе `deleteWebhook`).
- У `@BotFather`: `/deletebot` → виберіть одноразовий бот → підтвердьте.
- Видаліть тестову поштову скриньку, якщо створювали одноразову.
- Зупиніть ngrok (`Ctrl-C`).
- За потреби приберіть тестові дані:

```
mongosh "mongodb://…/botfunnel" --eval 'db.subscribers.deleteMany({projectId:"<projectId>"}); db.subscriber_events.deleteMany({projectId:"<projectId>"}); db.subscriber_exports.deleteMany({projectId:"<projectId>"}); db.tags.deleteMany({projectId:"<projectId>"})'
```

## Troubleshooting

- **Підписник не з'являється після `/start`.** Перевірте, що бот
  `CONNECTED` і webhook зареєстровано: `curl
  https://api.telegram.org/bot<token>/getWebhookInfo` має показати ваш
  `APP_URL`. Перевірте `raw_updates` у Mongo на наявність вхідного
  update. Якщо ngrok-URL змінився — повторіть крок 3 (setWebhook).
- **Export-email не прийшов.** Перевірте spam-папку. В Mongo:
  `db.subscriber_exports.find({projectId:"<projectId>"}).sort({createdAt:-1})`
  — статус має бути `DONE` із заповненим `fileId`/`rowCount`. Логи
  бекенду на наявність SMTP-помилок (EmailService swallow-and-log).
- **Кирилиця у CSV ламається.** Переконайтесь, що відкриваєте файл як
  UTF-8; BOM (`EF BB BF`) має бути першими трьома байтами — перевірте
  `xxd <file> | head -1`.
- **`Оновити посилання` повертає 503.** Це fail-CLOSED-поведінка
  refresh-URL при недоступному Redis (Decision 15). Грепніть логи
  бекенду за greppable-константою `EXPORT_REFRESH_REDIS_FAIL_CLOSED` —
  вона маркує саме цей шлях. Відновіть Redis і повторіть.
- **Конкурентний експорт не дає 409.** Перевірте, що партіал-унік-індекс
  `exports_in_flight_unique` створений:
  `db.subscriber_exports.getIndexes()`.

## Sign-off

| Поле | Значення |
|------|----------|
| Дата прогону | |
| Оператор | |
| `APP_URL` (ngrok / staging) | |
| `<projectId>` | |
| Результат (PASS / FAIL) | |
| Нотатки / відхилення | |

## Notes

- Runbook виконується вручну після деплою; в CI цей контур не
  емулюється (реальний Telegram + email + спредшит-рендер).
- E2E-spec `frontend/e2e/subscribers.spec.ts` покриває браузерний
  golden-path локально (з підключеним ботом + seed-env); він self-skip-ається,
  якщо бекенд не запущено.
- Health-endpoint цього проекту — `GET {APP_URL}/health` (Spring Actuator
  не на classpath, тож `/actuator/health` не існує).
