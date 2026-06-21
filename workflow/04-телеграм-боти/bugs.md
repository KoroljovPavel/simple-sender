# Bugs — ручне тестування телеграм-ботів

Журнал багів, знайдених під час проходження `ручне-тестування.md`.

---

## Bug #1 — F5 на сторінці Налаштування → Бот скидає обраний проект і пункт меню "Налаштування"

**Статус:** fixed
**Fix:** `frontend/pages/projects/[projectId]/settings/bot.vue:47-53` — onMounted тепер викликає `projectsStore.fetchAll()` (як на інших сторінках), щоб гідрувати список проектів після F5; `currentProjectId` уже персиститься через localStorage у `frontend/stores/projects.ts:39`.
**Дата:** 2026-05-23
**Сторінка:** Налаштування → Бот
**Severity:** TBD

### Кроки відтворення
1. Зайти в проект (обрати проект у селекторі).
2. Перейти в меню **Налаштування → Бот**.
3. Натиснути **F5** (перезавантажити сторінку).

### Фактична поведінка
- Обраний проект скидається (селектор проекту порожній).
- Пункт меню **"Налаштування"** зникає з навігації.

### Очікувана поведінка
- Після F5 обраний проект має зберігатися (persisted у URL / localStorage / store).
- Пункт меню **"Налаштування"** має залишатися видимим.
- Користувач має залишатися на сторінці Налаштування → Бот.

### Гіпотези про причину
- Стан обраного проекту тримається лише в пам'яті Pinia/Vuex стора без гідрації після reload.
- Пункт меню "Налаштування" рендериться умовно від наявності обраного проекту → зникає разом із ним.
- Маршрут не містить projectId у URL, тому після reload контекст втрачається.

### Нотатки
- Перевірити, чи зберігається `selectedProjectId` у `localStorage` / `sessionStorage` / URL params.
- Перевірити умову рендеру пункту "Налаштування" у компоненті навігації.

---

## Bug #2 — Telegram command events не містять `metadata.chatType`

**Статус:** fixed
**Fix:** `backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java:259-278` — `logEventCommandStart` / `logEventCommandStop` приймають і кладуть `chatType` у `metadata` (значення береться з `update.message.chat.type` у `dispatch`).
**Дата:** 2026-05-23
**Кроки чек-листа:** 2.6 (`telegram_command_start`), 2.13 (`telegram_command_stop`)
**Severity:** TBD (вище за початкову оцінку — зачіпає мінімум 2 типи івентів)

### Кроки відтворення

**Сценарій A — `telegram_command_start` (крок 2.6):**
1. Підключити бот до проекту (Частина 1 чек-листа).
2. У Telegram надіслати `/start ref_smoke_001` боту в приватному чаті.
3. Дочекатись `raw_updates.processingStatus = "DONE"` (крок 2.5).
4. Виконати в `mongosh`:
   ```
   db.events.find({eventType: "telegram_command_start", createdAt: {$gte: new Date(Date.now()-5*60*1000)}}).sort({createdAt:-1}).limit(1)
   ```

**Сценарій B — `telegram_command_stop` (крок 2.13):**
1. У тому ж приватному чаті з ботом надіслати `/stop` (крок 2.12).
2. Виконати в `mongosh`:
   ```
   db.events.find({eventType:"telegram_command_stop", createdAt:{$gte:new Date(Date.now()-5*60*1000)}}).sort({createdAt:-1}).limit(1)
   ```

### Фактична поведінка
- В обох документах events (`telegram_command_start` і `telegram_command_stop`) поля **`metadata.chatType`** немає взагалі (не `null`, не порожня строка — ключ відсутній).
- В `telegram_command_start` `metadata.startPayload = "ref_smoke_001"` присутній — отже мапер частково працює, але саме `chatType` не наповнюється.

### Очікувана поведінка (за чек-листами 2.6 та 2.13)
- `metadata.chatType = "private"` для команд із приватного чату — для обох event types (`telegram_command_start`, `telegram_command_stop`).
- Поле має бути присутнім в усіх telegram-command івентах із значеннями `"private" | "group" | "supergroup" | "channel"` відповідно до `chat.type` з Telegram update.
- Це особливо критично для AC9 (Non-private, крок 2.15), де очікується `chatType = "group"` / `"supergroup"` — без цього поля AC9 неможливо верифікувати взагалі.

### Гіпотези про причину
- Мапер з `Update → Event metadata` не копіює `message.chat.type` у `metadata.chatType` (втрачено при побудові payload).
- Поле додане у спеку/AC, але не імплементоване в коді (gap між тех-спеком і реалізацією).
- Можливо, мапиться під іншим ключем (наприклад, `metadata.chat_type` snake_case замість camelCase) — перевірити.

### Нотатки
- Перевірити код збагачення `telegram_command_start` — де формується `metadata`.
- Грепнути по кодовій базі `chatType` / `chat_type` / `chat.type` — чи взагалі читається це поле з Telegram update.
- Якщо поле є в AC user-spec/tech-spec — це регресія/недоробка задачі; додати інтеграційний тест.
