# Future Features — Epic 02 Auth

Речі які свідомо відкладені на після MVP.

## Pending-user restrictions
**Поточна поведінка (MVP):** юзер у статусі `pending` може залогінитись і бачить дашборд.
Показуємо banner/notice: "Підтвердіть email, щоб отримати повний доступ."

**На допрацювання:** визначити точно які дії заблоковані для `pending`-юзера:
- Блокування створення проектів (вже в README)
- Блокування підключення бота
- Блокування запуску розсилок
- Чи є щось що вони все ж можуть робити?
Реалізувати через permission guard на рівні API + UI.

## Email change
Зміна email потребує підтвердження зі старого і нового — складна flow, не для MVP.

## OAuth (Google, GitHub, Telegram Login Widget)
Post-MVP.

## 2FA / TOTP
Post-MVP.

## Magic-links без пароля
Post-MVP.
