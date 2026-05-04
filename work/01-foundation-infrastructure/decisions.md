# Decisions Log: 01-Foundation Infrastructure

Agent reports on completed tasks. Each entry is written by the agent that executed the task.

---

## Task 1: Monorepo root scaffold

**Status:** Done
**Commit:** 27d18ce
**Agent:** main agent
**Summary:** Created five top-level directories (`backend/`, `frontend/`, `infra/`, `docs/`, `scripts/`) with `.gitkeep` files, extended `.gitignore` with Java/Gradle, Node/Nuxt, secrets, and IDE patterns, and created `.env.example` (MONGODB_URI + REDIS_URL with inline comments) and `.nvmrc` (Node 24 LTS). Post-review, added `.env.*` variants, `credentials.json` patterns, and Spring Boot profile-specific property ignores.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: 1 major (*.lock ↔ pnpm-lock.yaml concern — verified incorrect, pnpm-lock.yaml ends in .yaml not .lock), 1 minor → [logs/working/task-1/code-reviewer-1.json](logs/working/task-1/code-reviewer-1.json)
- security-auditor: approved — 1 major (.env.* variants missing), 3 minor → [logs/working/task-1/security-auditor-1.json](logs/working/task-1/security-auditor-1.json)
- infrastructure-reviewer: approved — 1 major (.env.* variants), 2 minor → [logs/working/task-1/infrastructure-reviewer-1.json](logs/working/task-1/infrastructure-reviewer-1.json)

*Fixes after round 1:* Added `.env.*` + `!.env.example`, `credentials.json`, `service-account*.json`, Spring Boot `application-local.*` patterns. No re-review needed (changes are additive gitignore patterns, no logic risk).

**Verification:**
- `ls backend/ frontend/ infra/ docs/ scripts/ .env.example .nvmrc` → all paths exist, exit 0
- `git check-ignore -v .idea/ .env .env.local` → all correctly gitignored
- `git check-ignore -v .env.example` → correctly NOT gitignored

---

## Task 2: Backend Gradle skeleton

**Status:** Done
**Commit:** c725a47 (implementation) + fix round after reviews
**Agent:** main agent
**Summary:** Created full Gradle project skeleton in `backend/` using Spring Initializr (Spring Boot 3.5.0, Gradle 8.14.4). All 8 required dependencies declared, Java 21 toolchain configured, `SecurityConfig` with CSRF disabled and first-match rule ordering, `BotFunnelApplication`, `package-info.java`, and `application.properties`. Added `.gitattributes` for line ending control cross-platform. Added `logging.level.org.springframework.security=INFO` for visible auth/401 events.
**Deviations:** `testRuntimeOnly 'org.junit.platform:junit-platform-launcher'` added (not in spec, but standard Gradle 8+ recommendation from Spring Initializr to prevent "No tests were found" in IDE/CI).

**Reviews:**

*Round 1:*
- code-reviewer: approved — 0 critical/major, 1 minor (`junit-platform-launcher` note) → [logs/working/task-2/code-reviewer-1.json](logs/working/task-2/code-reviewer-1.json)
- security-auditor: approved — 1 major (no auth provider — intentional skeleton, will be wired in auth epic), 2 minor (security logging missing, `.idea/` pattern) → [logs/working/task-2/security-auditor-1.json](logs/working/task-2/security-auditor-1.json)
- infrastructure-reviewer: approved — 1 major (no tests — intentional, Task 5 scope), 4 minor → [logs/working/task-2/infrastructure-reviewer-1.json](logs/working/task-2/infrastructure-reviewer-1.json)

*Fixes after round 1:* Added `logging.level.org.springframework.security=INFO` to `application.properties`. Added `.gitattributes` for CRLF/LF enforcement. `.idea/` gitignore concern already addressed (file has `.idea/` without anchor, covers nested dirs). Foojay 0.8.0 confirmed working with Gradle 8.14.4 (compileJava passes); left as-is. Auth provider absence is by design — skeleton task only; auth wired in later epic.

**Verification:**
- `cd backend && ./gradlew compileJava --no-daemon` → `BUILD SUCCESSFUL in 30s`
- All 10 acceptance criteria satisfied (see task checkboxes)

---

## Task 3: Frontend Nuxt 4 skeleton

**Status:** Done
**Commit:** 66193bb (implementation) + da22b13 (review fixes)
**Agent:** main agent
**Summary:** Created minimal Nuxt 4.4.x SSR project in `frontend/`: `package.json` (nuxt@^4.4.0, pnpm scripts), `nuxt.config.ts` (compatibilityDate 2025-07-01, TypeScript strict), `app.vue` (Hello World). Installed dependencies with pnpm on Node 24.15.0, confirmed `pnpm build` → `.output/` SSR artifacts. Node 24 installed via nvm (was not present locally).
**Deviations:** `"packageManager": "pnpm@10.33.2"` field added (beyond spec minimum) — informational for corepack; pinned version will drift but causes no functional issue.

**Reviews:**

*Round 1:*
- code-reviewer: approved — 0 critical/major, 2 minor (packageManager pin, stale .gitkeep) → [logs/working/task-3/code-reviewer-1.json](logs/working/task-3/code-reviewer-1.json)
- infrastructure-reviewer: approved — 0 critical/major, 3 minor (no src/, no gitleaks hook, *.lock clarity) → [logs/working/task-3/infrastructure-reviewer-1.json](logs/working/task-3/infrastructure-reviewer-1.json)

*Fixes after round 1:* Deleted `frontend/.gitkeep` (stale placeholder). Added `!frontend/pnpm-lock.yaml` negation to `.gitignore` (explicit safety net — `*.lock` does not match `.yaml` but clarifies intent). packageManager pin left as-is (minor, informational).

**Verification:**
- `cd frontend && pnpm install && pnpm build` → exit 0, "Build complete!" with `.output/` SSR artifacts
- All 7 acceptance criteria satisfied (see task checkboxes)

---

## Task 4: Docker Compose (MongoDB + Redis)

**Status:** Done
**Commit:** db50514 (implementation) + 5862f71 (review fixes)
**Agent:** main agent
**Summary:** Created `infra/docker-compose.yml` with MongoDB 8.0 and Redis 7.4 bound to `127.0.0.1` only, named volumes at correct mount paths, project name `simple-sender-infra`, `restart: unless-stopped`, healthchecks, and memory limits (512m MongoDB / 128m Redis). `config --quiet` exits 0. `docker compose up -d` was blocked by existing dev containers on local ports — expected edge case documented in the task file.
**Deviations:** Image digest pinning not applied — floating minor tags (`mongo:8.0`, `redis:7.4`) are acceptable for local dev; digest pinning adds maintenance overhead without meaningful security benefit in this scope.

**Reviews:**

*Round 1:*
- code-reviewer: approved — 0 critical/major, 2 minor (project name, restart policy) → [logs/working/task-4/code-reviewer-1.json](logs/working/task-4/code-reviewer-1.json)
- security-auditor: approved — 1 major (image digest pinning), 2 minor (no dev-only comment, no memory limits) → [logs/working/task-4/security-auditor-1.json](logs/working/task-4/security-auditor-1.json)
- infrastructure-reviewer: approved — 3 minor (healthchecks, restart, memory limits) → [logs/working/task-4/infrastructure-reviewer-1.json](logs/working/task-4/infrastructure-reviewer-1.json)

*Fixes after round 1:* Added `name: simple-sender-infra`, `restart: unless-stopped`, healthchecks (mongosh ping / redis-cli ping), memory limits, and local-dev-only comment header. Image digest pinning rejected — local dev scope, floating tags acceptable.

**Verification:**
- `docker compose -f infra/docker-compose.yml config --quiet` → exit 0
- All 8 acceptance criteria satisfied (see task checkboxes)

---

## Task 5: Backend /health endpoint + integration tests

**Status:** Done
**Commit:** d8380b0 (implementation) + 2d1b92a (review fixes)
**Agent:** main agent
**Summary:** TDD cycle — wrote 3 failing tests, confirmed failures, implemented `HealthController`, confirmed all 3 pass with `./gradlew build`. Used `@MockitoBean` (not deprecated `@MockBean`) for MongoClient, RedisConnectionFactory, ReactiveRedisConnectionFactory to avoid live DB dependency. `HealthSecurityTest` sends invalid Bearer token to prove `permitAll()` is unaffected by auth credentials. Content-Type assertions and Decision 6 comments added.
**Deviations from spec:** (1) 3 `@MockitoBean` fields instead of 2 — Spring Boot 3.5.x `RedisAutoConfiguration` also needs `RedisConnectionFactory` (non-reactive); spec only listed reactive variant. (2) Used `@MockitoBean` not `@MockBean` — Spring Boot 3.4.0+ deprecation.

**Reviews:**

*Round 1:*
- code-reviewer: approved — 1 major (HealthSecurityTest duplicate), 2 minor (@MockBean deprecated, shared base class) → [logs/working/task-5/code-reviewer-1.json](logs/working/task-5/code-reviewer-1.json)
- security-auditor: approved — 2 major (no auth provider comment, unverified 401 logging), 3 minor → [logs/working/task-5/security-auditor-1.json](logs/working/task-5/security-auditor-1.json)
- test-reviewer: needs_improvement — 1 major (HealthSecurityTest duplicate), 2 minor (no Content-Type, no mock comments) → [logs/working/task-5/test-reviewer-1.json](logs/working/task-5/test-reviewer-1.json)

*Fixes after round 1:* `@MockBean` → `@MockitoBean`. `HealthSecurityTest` differentiated (invalid Bearer token). Content-Type assertions added. CSRF/auth TODO comments added to SecurityConfig. Decision 6 reference in @MockitoBean fields. Shared base class skipped — over-engineering for 3 test classes.

**Verification:**
- `cd backend && ./gradlew build` → BUILD SUCCESSFUL, all 3 tests green
- All 6 acceptance criteria satisfied (see task checkboxes)

---

## Task 6: Gitleaks pre-commit hook

**Status:** Done
**Commit:** 0037a8e (implementation) + 938d9b2 (review fixes)
**Agent:** main agent
**Summary:** Created `scripts/install-hooks.sh` (mode 100755) that writes a POSIX sh pre-commit hook using `cat >` (idempotent overwrite). Hook checks for gitleaks with `command -v`, soft-exits 0 with warning if absent. Runs `gitleaks detect --staged --verbose --no-banner --redact` (--redact added after review). Explicit `exit $?` for safe future extensibility. Script uses `git rev-parse --show-toplevel` so it works from any subdirectory.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: approved — 2 minor (>/dev/null 2>&1 vs &>/dev/null — already correct for POSIX sh, no change; git repo guard comment) → [logs/working/task-6/code-reviewer-1.json](logs/working/task-6/code-reviewer-1.json)
- security-auditor: approved — 3 minor (symlink follow, PATH hijacking, no CI audit log) → [logs/working/task-6/security-auditor-1.json](logs/working/task-6/security-auditor-1.json)
- infrastructure-reviewer: approved — 1 major (--verbose without --redact exposes raw secret values), 2 minor → [logs/working/task-6/infrastructure-reviewer-1.json](logs/working/task-6/infrastructure-reviewer-1.json)

*Fixes after round 1:* Added `--redact` to gitleaks command. Added `exit $?` after gitleaks. Added git repo comment. Symlink/PATH/audit findings accepted as minor — symlink requires attacker already owning `.git/`, PATH hijacking is a developer machine concern, CI gitleaks is Task 11 scope.

**Verification:**
- `bash scripts/install-hooks.sh && test -x .git/hooks/pre-commit` → exit 0
- Second run → idempotent (same hook content)
- Hook triggered at commit time: soft failure (WARNING + exit 0) — gitleaks not installed locally
- All 7 acceptance criteria satisfied (see task checkboxes)

---

## Task 7: Developer documentation

**Status:** Done
**Commit:** dfccf56 (implementation) + 45bca6e (review fixes)
**Agent:** main agent
**Summary:** Created `docs/local-setup.md` with all required sections: prerequisites table (Java 21, Node 24, Docker, pnpm, gitleaks with install commands), 6-step setup sequence matching the actual implementation commands, verification checklist, port conflict resolution for all four ports (27017, 6379, 8080, 3000), and Windows/WSL2 warning. Commands and env variable names verified against `.env.example`, `infra/docker-compose.yml`, and `scripts/install-hooks.sh` — all match exactly.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: changes_required — 1 critical (MongoDB port conflict section incorrectly listed REDIS_URL instead of MONGODB_URI), 4 minor (healthcheck timing accuracy, .env blank line display, docker ps `starting` state note, nuxt.config.ts full example staleness risk) → [logs/working/task-7/code-reviewer-1.json](logs/working/task-7/code-reviewer-1.json)

*Fixes after round 1:* Fixed critical: changed "Update REDIS_URL and MONGODB_URI" → "Update MONGODB_URI" in MongoDB port section. Clarified MongoDB `start_period: 20s` vs Redis (no start_period, typically ready in seconds). Minor nuxt.config.ts staleness concern accepted — current file is minimal, risk is low. docker ps `starting` note — checklist already says to wait for healthchecks. .env blank line — already present in source.

**Verification:**
- User reviewed `docs/local-setup.md` end-to-end — confirmed setup flow matches actual implementation
- All 8 acceptance criteria satisfied (see task checkboxes)
