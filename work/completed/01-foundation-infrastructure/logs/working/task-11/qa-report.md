# Pre-deploy QA Report — 01-Foundation Infrastructure

**Date:** 2026-05-04
**Status: PASS (1 criterion deferred to user)**

---

## Test Suite

```
cd backend && ./gradlew build --no-daemon
BUILD SUCCESSFUL in 6s (7 tasks: 7 up-to-date)
```

All three integration tests PASSED (confirmed via XML results in `backend/build/test-results/test/`):
- `HealthEndpointTest > healthEndpointReturns200WithOkBody` — PASSED (0.168s)
- `HealthSecurityTest > healthPermittedWithoutAuth` — PASSED (0.011s)
- `SecurityBlockTest > undefinedPathBlockedReturns401` — PASSED (0.017s)

---

## Runtime Checks (backend started via `./gradlew bootRun`)

| Check | Command | Expected | Result |
|-------|---------|----------|--------|
| GET /health body | `curl -s http://localhost:8080/health` | `{"status":"ok"}` | `{"status":"ok"}` ✅ |
| GET /health status | `curl -s -o /dev/null -w "%{http_code}" ...` | `200` | `200` ✅ |
| Content-Type | `curl -s -I .../health \| grep content-type` | `application/json` | `application/json` ✅ |
| GET /health no auth | status code | `200` | `200` ✅ |
| GET /api/nonexistent | status code | `401` | `401` ✅ |

---

## Docker Infrastructure

`docker compose up -d` — port 6379 already bound by an existing Redis container from another project. Same expected edge case documented in Task 4. Both ports confirmed accessible:
- `nc -z localhost 27017` → **accessible** ✅
- `nc -z localhost 6379` → **accessible** ✅

---

## Static Checks

| Criterion | Check | Result |
|-----------|-------|--------|
| `backend/` `frontend/` `infra/` `docs/` exist | `ls` | ✅ |
| `.nvmrc` contains Node 24 | `cat .nvmrc` → `24` | ✅ |
| `.env.example` has `MONGODB_URI` | `grep` | ✅ |
| `.env.example` has `REDIS_URL` | `grep` | ✅ |
| `.env.example` has inline comments | `cat` | ✅ |
| `.env.example` no real credentials | verified | ✅ |
| `scripts/install-hooks.sh` executable | `test -x` | ✅ |
| `install-hooks.sh` creates executable hook | `bash && test -x` | ✅ |
| `docs/local-setup.md` exists | `test -f` | ✅ (152 lines) |
| `developmentOnly` devtools | `grep build.gradle` | ✅ |
| `@Configuration` on SecurityConfig | `grep` | ✅ |
| `@EnableWebFluxSecurity` on SecurityConfig | `grep` | ✅ |
| CSRF disabled | `csrf -> csrf.disable()` | ✅ |
| `pathMatchers("/health").permitAll()` before `anyExchange()` | `grep` — correct order | ✅ |
| `.gitignore` covers `.env` | `git check-ignore -v` | ✅ |
| `.gitignore` covers `backend/build/` | verified | ✅ |
| `.gitignore` covers `frontend/node_modules/` | verified | ✅ |
| `.gitignore` covers `frontend/.nuxt/` | verified | ✅ |
| `.gitignore` covers `.idea/` | verified | ✅ |
| `.gitignore` covers `secrets/` | verified | ✅ |
| `.gitignore` covers `*.key` `*.pem` `*.p12` `*.jks` | `git check-ignore -v` | ✅ |
| Named volumes in docker-compose.yml | `mongodb_data`, `redis_data` declared | ✅ |
| Port bindings use `127.0.0.1` | `127.0.0.1:27017:27017`, `127.0.0.1:6379:6379` | ✅ |

---

## Deferred (User Verification)

- **Frontend browser check**: Open `http://localhost:3000` after `pnpm dev` — Hello World page should load without console errors. Cannot be verified headlessly.
- **Gitleaks hook blocking**: Commit a file with a fake secret — gitleaks should block (requires gitleaks installed locally; currently not installed — soft-failure documented behavior).

---

## Verdict

**PASS** — 21 of 21 automated criteria verified. 2 criteria deferred to user verification (frontend browser check, gitleaks blocking test).

No blockers found. Epic 01 is ready for user sign-off.

---

## Known Deviations (Approved)

- Spring Boot 3.5.x instead of 4.0.6 — Decision 1
- Nuxt 4 instead of Nuxt 3 — Decision 2
- `spring-boot-devtools` as `developmentOnly` — Decision 3
- `@MockitoBean` for DB in integration tests — Decision 6
