# Local Development Setup

This guide takes you from `git clone` to a running local environment. Follow steps in order.

> **Windows users:** All commands in this guide assume Linux, macOS, or WSL2. Native Windows (PowerShell/CMD) is not supported — shell scripts like `scripts/install-hooks.sh` require a Unix-compatible shell. Use WSL2 on Windows; install Docker Desktop with WSL2 backend enabled.

---

## Prerequisites

Install the following tools before proceeding:

| Tool | Version | Install |
|------|---------|---------|
| Java 21 JDK | 21 (JDK, not JRE) | [Adoptium Temurin](https://adoptium.net/) or `sdk install java 21-tem` via SDKMAN |
| Node.js | 24 LTS | via [nvm](https://github.com/nvm-sh/nvm): `nvm install` (reads `.nvmrc` automatically) |
| Docker | any recent | [Docker Desktop](https://www.docker.com/products/docker-desktop/) (macOS/Windows) or Docker Engine (Linux) |
| pnpm | latest | `npm install -g pnpm` or `corepack enable && corepack prepare pnpm@latest --activate` |
| gitleaks | latest | `brew install gitleaks` (macOS) — optional but strongly recommended |

**Verify gitleaks after install:**
```bash
gitleaks version
```

> **Note:** gitleaks is optional. The pre-commit hook exits 0 with a warning if gitleaks is not installed — it does not block commits. Install it to enable secret scanning before each commit.

> **Note:** `./gradlew` (Gradle Wrapper) auto-downloads Gradle on first run. Java 21 JDK is still required — IntelliJ and the Gradle toolchain resolver (Foojay) need it.

---

## Setup Steps

### Step 1 — Install the pre-commit hook

```bash
bash scripts/install-hooks.sh
```

This writes `.git/hooks/pre-commit` and sets it executable. Running it multiple times is safe (idempotent).

### Step 2 — Configure environment variables

```bash
cp .env.example .env
```

The defaults in `.env.example` point to the local Docker containers and work without changes:

```dotenv
# MongoDB connection URI — points to the local Docker container by default
MONGODB_URI=mongodb://localhost:27017/botfunnel

# Redis connection URL — points to the local Docker container by default
REDIS_URL=redis://localhost:6379
```

### Step 3 — Start MongoDB and Redis

```bash
docker compose -f infra/docker-compose.yml up -d
```

Starts MongoDB 8.0 on `:27017` and Redis 7.4 on `:6379`, both bound to `127.0.0.1` only. Both services have healthchecks — MongoDB takes up to ~20 seconds to report healthy (configured `start_period`); Redis is typically ready within a few seconds.

### Step 4 — Start the backend

```bash
cd backend && ./gradlew bootRun
```

On the **first run** Gradle downloads itself and all dependencies — this may take several minutes. Subsequent runs are fast (cached). Netty starts on `:8080`.

### Step 5 — Verify the backend is up

```bash
curl http://localhost:8080/health
```

Expected response:
```json
{"status":"ok"}
```

HTTP 200 with this body confirms the backend is running correctly.

### Step 6 — Start the frontend

```bash
nvm use
cd frontend && pnpm install && pnpm dev
```

Nuxt dev server starts on `:3000`. Open [http://localhost:3000](http://localhost:3000) in a browser — the Hello World page should load without console errors.

---

## Verification Checklist

After completing all setup steps, confirm the environment is healthy:

- [ ] `curl http://localhost:8080/health` → HTTP 200, body `{"status":"ok"}`
- [ ] `http://localhost:3000` opens in browser, Hello World page loads, no console errors
- [ ] `docker compose -f infra/docker-compose.yml ps` shows both `mongodb` and `redis` as `healthy`

---

## Port Conflict Resolution

If another process is already using one of the four ports, remap it:

### MongoDB — port 27017

Change the host port in `infra/docker-compose.yml` (left side of the mapping):

```yaml
# Change 27017 to any free port, e.g. 27018
- "127.0.0.1:27018:27017"
```

Update `MONGODB_URI` in `.env` to match the new port.

### Redis — port 6379

Change the host port in `infra/docker-compose.yml`:

```yaml
# Change 6379 to any free port, e.g. 6380
- "127.0.0.1:6380:6379"
```

Update `REDIS_URL` in `.env` to match.

### Backend — port 8080

Add to `backend/src/main/resources/application.properties`:

```properties
server.port=8081
```

### Frontend — port 3000

Add to `frontend/nuxt.config.ts`:

```typescript
export default defineNuxtConfig({
  compatibilityDate: '2025-07-01',
  typescript: { strict: true },
  devServer: { port: 3001 },
})
```
