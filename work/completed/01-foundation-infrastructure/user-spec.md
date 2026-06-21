---
created: 2026-05-03
status: approved
type: feature
size: L
---

# User Spec: 01 — Foundation Infrastructure

## Що робимо

Створюємо базовий скелет монорепозиторію для Bot Funnel Service: бекенд на Spring Boot 4 + WebFlux (один endpoint `/health`), фронтенд на Nuxt (одна сторінка Hello World), локальну інфраструктуру (MongoDB + Redis через Docker Compose) та документацію для локального запуску. Це Epic 01 — підготовчий етап, що дає змогу одразу починати розробку наступних epic.

## Навіщо

Без спільного скелету кожен розробник клонує репо і витрачає годину на з'ясування того, яку версію Java використовувати, де зберігати `.env`, як запустити інфраструктуру. Перший розробник, що долучається до проєкту, не зможе запустити код без усних інструкцій. Epic 01 фіксує tech stack, структуру папок, конфігурацію Spring Security та security gate (gitleaks) — щоб наступний розробник пройшов від `git clone` до working backend за 10 хвилин без допомоги.

## Як повинно працювати

Сценарій розробника після клонування репозиторію:

1. Клонує репо, встановлює pre-commit хук gitleaks (інструкція в `docs/local-setup.md`)
2. Копіює `.env.example` → `.env` (значення за замовчуванням підходять для локалки без змін)
3. Запускає `docker compose up` — піднімаються MongoDB (:27017) та Redis (:6379)
4. Запускає бекенд в IntelliJ або через `./gradlew bootRun` — сервер стартує на :8080
5. Запускає фронтенд: `pnpm install && pnpm dev` — фронтенд доступний на :3000
6. Перевіряє: `GET http://localhost:8080/health` повертає `{"status":"ok"}`

Кінцевих користувачів (бізнес-логіки) в Epic 01 немає — це технічна основа.

## Критерії приймання

- [x] Монорепо структура: `backend/`, `frontend/`, `infra/`, `docs/` в корені репозиторію
- [x] `.gitignore` присутній і включає `.env`, `*.key`, `secrets/`, `.idea/` та інші локальні файли
- [x] `backend/` — Spring Boot 3.5.x + Spring WebFlux + Gradle, package root `com.botfunnel`; Gradle toolchain зафіксований на Java 21
- [x] `./gradlew build` завершується з `BUILD SUCCESSFUL`
- [x] `GET /health` повертає HTTP 200 з тілом `{"status":"ok"}` (статична відповідь, без перевірки MongoDB/Redis — full health check з Actuator відкладено)
- [x] Пакет `com.botfunnel.security` створений і містить `SecurityConfig.java`
- [x] Пакет `com.botfunnel.common` створений і містить хоча б один placeholder-клас або `package-info.java`
- [x] `SecurityConfig` дозволяє `GET /health` без авторизації (відповідь 200, не 401)
- [x] `frontend/` — Nuxt 4 + Vue 3 + TypeScript + pnpm; `pnpm dev` стартує сервер; `GET http://localhost:3000` повертає HTTP 200
- [x] `infra/docker-compose.yml` — піднімає тільки MongoDB (:27017) та Redis (:6379); app-контейнерів немає
- [x] `.env.example` містить `MONGODB_URI` та `REDIS_URL` з коментарями; інших змінних немає
- [x] `.nvmrc` з версією Node.js LTS
- [x] `docs/local-setup.md` — покрокова інструкція: prerequisites, clone, gitleaks hook, .env, docker, backend, frontend, verify; включає інструкцію про зміну портів при конфлікті; застереження для Windows (WSL2)
- [x] Gitleaks pre-commit хук налаштований і задокументований у `docs/local-setup.md`
- [x] Один WebFlux integration test: `GET /health` → 200 + body `{"status":"ok"}`

## Обмеження

- **Spring Boot 3.5.x / Spring Framework 6.x** — стабільна LTS-версія; `spring-boot-devtools` включено для hot restart у локальній розробці
- **Docker Compose в Epic 01** — піднімає лише MongoDB + Redis; бекенд і фронтенд запускаються локально поза Docker
- **`.env.example` мінімальний** — тільки змінні потрібні для Epic 01; решта (JWT_SECRET, BOT_TOKEN_ENCRYPTION_KEY тощо) додаються в наступних epic
- CI/CD, production deploy, моніторинг — поза скоупом Epic 01

## Ризики

- **JobRunr сумісність:** `jobrunr-spring-boot-3-starter` з architecture.md є сумісним із Spring Boot 3.x — ризик знятий переходом на Boot 3.
- **Windows-розробники:** Docker Compose і shell-команди можуть поводитись інакше. **Митигація:** додати застереження у `docs/local-setup.md` — рекомендується Linux/Mac або WSL2 на Windows.
- **Конфлікт портів:** MongoDB :27017, Redis :6379, backend :8080 або frontend :3000 можуть бути зайняті. **Митигація:** задокументувати в `docs/local-setup.md` як змінити port mapping (docker-compose.yml для :27017/:6379, application.properties для :8080, nuxt.config.ts для :3000).
- **Gitleaks не встановлений:** якщо розробник не встановив gitleaks CLI — pre-commit хук не спрацьовуватиме. **Митигація:** задокументувати в `docs/local-setup.md` як перевірити та встановити (`brew install gitleaks` або аналог).

## Технічні рішення

- Вирішили **включити `spring-boot-devtools`** — Spring Boot 3.5.x повністю сумісний; надає hot restart під час локальної розробки.
- Вирішили **`/health` повертає статичну відповідь** без перевірки connectivity — Actuator з повноцінними health indicators підключається в пізніших epic разом з моніторингом.
- Вирішили перейти з **Spring Boot 4.0.6 → Spring Boot 3.5.x** — стабільніша екосистема, `jobrunr-spring-boot-3-starter` сумісний без ризиків; Nuxt 3 → **Nuxt 4** (Nuxt 3 EOL липень 2026, Nuxt 4 стабільний з липня 2025).
- Вирішили **створити тільки пакети `security` і `common`** в Epic 01 — решта модулів (auth, project, bot тощо) створюються у своїх epic з остаточними назвами, щоб уникнути зайвого перейменування.
- Вирішили **зафіксувати Gradle Java toolchain на Java 21** (аналог `.nvmrc` для бекенду).
- Вирішили **не включати app-контейнери в docker-compose.yml** Epic 01 — бекенд і фронтенд запускаються вручну для зручності розробки.
- Вирішили **включити `SecurityConfig` в Epic 01** — Spring Security додається в залежності вже зараз, щоб не переналаштовувати security filter chain пізніше; `SecurityConfig` відкриває тільки `/health`, все решта буде захищено за замовчуванням (401) поки не буде реалізована авторизація.
- Вирішили **включити gitleaks pre-commit хук** одразу в Epic 01 — greenfield setup є природним моментом для встановлення security gate.
- Вирішили **мінімальний `.env.example`** (2 змінні) — решта змінних додається поступово разом з відповідною функціональністю.
- Вирішили **не робити тести на фронтенді** на скелетному етапі — нема бізнес-логіки для тестування.

## Тестування

**Unit-тести:** робляться завжди, не обговорюються.

**Інтеграційні тести:** робимо — один WebFlux тест для `GET /health` → 200 + `{"status":"ok"}`. Цього достатньо для скелету.

**E2E тести:** не робимо — немає UI-флоу та бізнес-логіки в Epic 01.

## Як перевірити

### Агент перевіряє

| Крок | Інструмент | Очікуваний результат |
|------|-----------|---------------------|
| 1. Запустити `./gradlew build` | bash | Виводить `BUILD SUCCESSFUL`, integration test проходить |
| 2. `GET http://localhost:8080/health` | curl | HTTP 200, body `{"status":"ok"}` |
| 3. `GET http://localhost:8080/health` без токену | curl | HTTP 200 (не 401) — Security config дозволяє |
| 4. Перевірити структуру папок | bash/find | `backend/`, `frontend/`, `infra/`, `docs/` існують |
| 5. Перевірити `.env.example` | bash | Містить `MONGODB_URI` і `REDIS_URL` |
| 6. `docker compose up -d` → перевірити порти | bash | MongoDB :27017, Redis :6379 доступні |

### Користувач перевіряє

- Відкрити `http://localhost:3000` в браузері — Nuxt сторінка завантажується без помилок у консолі
- Перевірити що pre-commit хук спрацьовує: спробувати закомітити файл з фейковим секретом — gitleaks має заблокувати коміт
