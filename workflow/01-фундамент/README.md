# 01 — Фундамент та інфраструктура

## Мета
Підготувати базу для всіх наступних епіків: репозиторій, технологічний стек, БД, локальне середовище, базовий деплой, моніторинг.

## Користувацька цінність
Жодної прямої — це робота для команди розробки. Без цього нічого з решти не починається.

---

## Рекомендований стек (на обговорення)

- **Backend:** Node.js + TypeScript + NestJS *(альтернатива — Python + FastAPI)*
- **БД:** PostgreSQL 16
- **Кеш / черги:** Redis 7 + BullMQ
- **Frontend:** React + Vite + TypeScript + Tailwind + shadcn/ui
- **Контейнери:** Docker + Docker Compose
- **Хостинг:** Hetzner Cloud або DigitalOcean (один VPS на старті, ~$10-20/міс)
- **Email:** Resend або Postmark
- **Error tracking:** Sentry (free tier на старті)

---

## MUST HAVE

### Репозиторій та код
- Монорепо з папками `backend/`, `frontend/`, `infra/`, `docs/`
- Кореневий `README.md` з інструкцією як запустити локально за 1 команду
- `.env.example` з усіма змінними оточення (без реальних секретів)
- ESLint + Prettier + Husky pre-commit hooks
- Conventional commits (опц., але бажано)

### База даних
- PostgreSQL з міграційним інструментом (Prisma / TypeORM / Alembic)
- Базова схема таблиць: `users`, `projects`, `bots`, `subscribers`, `subscriber_tags`, `funnels`, `funnel_steps`, `funnel_executions`, `broadcasts`, `broadcast_messages`, `events`, `api_keys`, `admin_actions`
- Seed-скрипт:
  - Створює першого Super Admin за змінними з `.env`
  - Створює тестовий проект з ботом-заглушкою для dev-середовища

### Локальне середовище
- `docker-compose.yml` піднімає Postgres + Redis + backend + frontend однією командою `docker compose up`
- Hot reload працює і на бекенді, і на фронті
- Документ `docs/local-setup.md` з кроками для нового розробника

### Деплой (production)
- Dockerfile для backend і frontend
- Production deploy на VPS:
  - HTTPS через Caddy (автоматичний Let's Encrypt) або Nginx
  - Окремі контейнери: api, web, postgres, redis
  - Volumes для постійних даних (Postgres data, uploads)
  - Автоматичні бекапи Postgres щодоби в S3 / Backblaze B2
- GitHub Actions: push to `main` → деплой на prod (через SSH або Watchtower)
- Скрипт `infra/deploy.sh` для ручного деплою

### Логування і моніторинг
- Структуровані JSON-логи (pino / winston)
- Sentry для error tracking на backend і frontend
- Healthcheck-endpoint `GET /health` (перевіряє connect до Postgres і Redis)
- UptimeRobot або BetterStack для зовнішнього моніторингу

### Безпека
- Секрети — тільки в env-файлах, ніколи в коді
- Rate limiting на API (express-rate-limit / @nestjs/throttler)
- CORS конфіг (whitelist доменів)
- Helmet (security headers)
- HTTPS обов'язково в prod

### Документація
- `docs/architecture.md` — high-level огляд
- `docs/db-schema.md` — основні таблиці і зв'язки
- `docs/deployment.md` — як деплоїтись

---

## Що НЕ входить (свідомо)
- Kubernetes, Helm, ArgoCD
- Multi-region, multi-AZ, geo-redundancy
- ELK / Grafana / Prometheus (тільки Sentry на старті)
- Автоматичні E2E-тести (юніт-тести так, для критичної логіки)
- Feature flags / canary deployments
- Service mesh, gRPC між сервісами

---

## Acceptance criteria
1. Розробник із нуля піднімає проект локально однією командою
2. Push у `main` автоматично деплоїться на prod
3. У БД після seed з'являється Super Admin
4. `GET /health` повертає 200 OK
5. Sentry приймає тестову помилку
6. Бекап Postgres створюється щодоби

---

## Залежності
Немає — це самий перший епік.
