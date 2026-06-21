# Security Audit Report — Epic 01: Foundation Infrastructure

**Date:** 2026-05-04
**Auditor:** security-auditor agent (claude-sonnet-4-6)
**Scope:** Epic 01 security-relevant artifacts (Wave 4 Audit Task 9)
**OWASP focus:** A01 (Broken Access Control), A02 (Cryptographic Failures), A05 (Security Misconfiguration), A07 (Auth Failures)

---

## Summary

| Area | File | Status |
|------|------|--------|
| Spring SecurityWebFilterChain | `backend/src/main/java/com/botfunnel/security/SecurityConfig.java` | PASS |
| Gitignore coverage | `.gitignore` | PASS |
| Env example credentials | `.env.example` | PASS |
| Hook install script | `scripts/install-hooks.sh` | PASS |
| Docker Compose port binding | `infra/docker-compose.yml` | PASS |
| Developer documentation | `docs/local-setup.md` | PASS |

**Overall result: PASS — no critical or high findings.**

---

## Findings

### LOW — Missing `.nvmrc` and `*.lock` exclusion granularity (INFO-grade, listed for completeness)

**Severity:** INFO
**Category:** Best Practice / A05 Security Misconfiguration
**Location:** `.gitignore:38`
**Description:** The rule `*.lock` with a negation `!frontend/pnpm-lock.yaml` correctly preserves the frontend lockfile. This is working as intended. Flagged as informational only: any future `backend/*.lock` files (e.g. Gradle lock files if `--write-locks` is used) would also be excluded. No action required unless Gradle dependency locking is introduced.
**Impact:** Negligible; only relevant if dependency-locking is enabled in a future epic.
**Recommendation:** No action needed now. If Gradle dependency locking (`dependencyLocking {}`) is adopted in a later epic, add `!backend/gradle/dependency-locks/**` as a negation.
**CWE:** N/A

---

### INFO — CSRF disabled without HTTP method restriction noted in comments

**Severity:** INFO
**Category:** A05 Security Misconfiguration / Best Practice
**Location:** `backend/src/main/java/com/botfunnel/security/SecurityConfig.java:17`
**Description:** CSRF is disabled globally for the stateless REST API. This is architecturally correct for a token-authenticated REST service, and the inline comment explains the rationale ("Stateless API — no session-based features; CSRF protection is unnecessary"). No vulnerability exists here, but the rationale is only partially documented: the key technical reason is that CSRF only applies to cookie-based session authentication, which is absent here. The comment is acceptable for Epic 01 scope.
**Impact:** None. Correctly disabled for stateless REST. The TODO comment regarding JWT/OAuth2 in a future auth epic ensures reviewers know CSRF posture will be re-evaluated.
**Recommendation:** No immediate action. When the auth epic introduces JWT/OAuth2 bearer tokens, confirm that CSRF remains correctly disabled (bearer tokens are CSRF-immune by nature). If cookie-based auth is ever added, CSRF must be re-enabled.
**CWE:** CWE-352 (informational — correctly mitigated)

---

## Detailed Verification Results

### 1. SecurityConfig.java

**File:** `backend/src/main/java/com/botfunnel/security/SecurityConfig.java`

| Check | Result |
|-------|--------|
| `@Configuration` annotation present | PASS — line 9 |
| `@EnableWebFluxSecurity` annotation present | PASS — line 10 |
| Both annotations on same class (required for Spring Security 6) | PASS |
| CSRF explicitly disabled | PASS — `.csrf(csrf -> csrf.disable())` line 17 |
| `pathMatchers("/health").permitAll()` present | PASS — line 19 |
| `anyExchange().authenticated()` present | PASS — line 21 |
| Rule ordering correct (permitAll BEFORE authenticated) | PASS — `/health` appears at line 19, `anyExchange()` at line 21; first-match ordering is correct |
| No unintended additional `permitAll()` rules | PASS — only `/health` is whitelisted |
| No hardcoded credentials | PASS |
| Returns `SecurityWebFilterChain` bean | PASS — line 14 |

**Analysis:** The SecurityWebFilterChain is correctly configured. The critical ordering risk documented in tech-spec Decision 5 (rule-ordering inversion) is NOT present. `pathMatchers("/health").permitAll()` precedes `anyExchange().authenticated()` ensuring `/health` is accessible without auth while all other paths require authentication.

---

### 2. .gitignore

**File:** `.gitignore`

| Required Pattern | Present | Line |
|-----------------|---------|------|
| `.env` | PASS | 59 |
| `.env.*` (with `!.env.example` negation) | PASS | 60–61 |
| `*.key` | PASS | 62 |
| `secrets/` | PASS | 63 |
| `*.pem` | PASS | 64 |
| `*.p12` | PASS | 65 |
| `*.jks` | PASS | 66 |
| `.idea/` | PASS | 75 |
| `backend/build/` | PASS | 45 |
| `backend/.gradle/` | PASS | 46 |
| `frontend/node_modules/` | PASS | 53 |
| `frontend/.nuxt/` | PASS | 54 |
| `credentials.json` | PASS | 67 |
| `application-local.properties` | PASS | 71 |
| `application-local.yml` | PASS | 72 |

**Analysis:** All required patterns from the tech-spec are present. The `.env.example` negation correctly preserves the example file while excluding all other `.env.*` variants. The `!backend/gradle/wrapper/gradle-wrapper.jar` negation at line 49 correctly preserves the Gradle wrapper JAR (required for `./gradlew` to work). No tracked sensitive files were identified by pattern analysis.

**Additional coverage beyond spec requirements (positive):**
- `service-account*.json`, `*-credentials.json` — protects GCP service account files
- `application-local.properties` / `application-local.yml` — protects Spring Boot local profile overrides
- `*.war`, `*.class` — build artifact exclusion

---

### 3. .env.example

**File:** `.env.example`

| Check | Result |
|-------|--------|
| Zero real credentials | PASS — both URIs use `localhost` with no username/password |
| `MONGODB_URI` present with comment | PASS — line 1–2 |
| `REDIS_URL` present with comment | PASS — line 4–5 |
| No extra variables beyond Epic 01 scope | PASS — exactly 2 variables |
| No embedded passwords in URIs | PASS — `mongodb://localhost:27017/botfunnel` has no credentials |
| No tokens, API keys, or secrets | PASS |

**Analysis:** `.env.example` is minimal and clean. Both URIs are localhost-only with no embedded credentials. Inline comments are present for each variable. File scope matches the Epic 01 constraint ("minimal — only variables needed for Epic 01").

---

### 4. scripts/install-hooks.sh

**File:** `scripts/install-hooks.sh`

| Check | Result |
|-------|--------|
| `set -euo pipefail` (strict mode) | PASS — line 3 |
| `REPO_ROOT` properly quoted in assignments | PASS — `"$(git rev-parse --show-toplevel)"` |
| `HOOK_PATH` properly quoted | PASS — `"$REPO_ROOT/.git/hooks/pre-commit"` |
| No unquoted variable expansion (injection risk) | PASS — all variable uses are double-quoted |
| Hook body uses heredoc with `'EOF'` (single-quoted, no expansion) | PASS — `<< 'EOF'` prevents variable interpolation in the hook body |
| Soft-failure pattern correctly implemented | PASS — `exit 0` when gitleaks not installed (line 13) |
| Hook makes gitleaks exit code pass-through | PASS — `exit $?` after `gitleaks detect` (line 17) |
| `chmod +x` applied to hook | PASS — line 20 |
| Idempotency | PASS — `cat >` overwrites on re-run; no duplicate content |
| No command injection via `REPO_ROOT` | PASS — `git rev-parse --show-toplevel` output is safe; all downstream uses are quoted |
| Script does not bypass hook on failure | PASS — `set -euo pipefail` causes script to abort on any unhandled error |
| `gitleaks detect --staged` scans only staged files | PASS — correct scope for pre-commit |
| `--redact` flag present | PASS — prevents secret values from appearing in terminal output |

**Analysis:** The install script is well-hardened. The use of `'EOF'` (single-quoted heredoc) is the correct technique to prevent variable expansion in the hook body. All variable uses in path construction are double-quoted, eliminating word-splitting and glob expansion risks. The soft-failure pattern is correct and intentional.

**Note on soft-failure:** The exit-0 behavior when gitleaks is not installed is an accepted risk (see Accepted Risks section). It is NOT a security bypass vulnerability — it is a documented design decision to not block new developers who haven't yet installed gitleaks.

---

### 5. infra/docker-compose.yml

**File:** `infra/docker-compose.yml`

| Check | Result |
|-------|--------|
| MongoDB port bound to `127.0.0.1` | PASS — `"127.0.0.1:27017:27017"` line 11 |
| Redis port bound to `127.0.0.1` | PASS — `"127.0.0.1:6379:6379"` line 29 |
| No `0.0.0.0` binding | PASS — not present anywhere |
| No hardcoded credentials in `environment:` blocks | PASS — no `environment:` blocks; no `MONGO_INITDB_ROOT_*`, no `REDIS_PASSWORD` |
| Named volumes used | PASS — `mongodb_data` and `redis_data` declared in `volumes:` section |
| Image versions pinned | PASS — `mongo:8.0`, `redis:7.4` |
| Healthchecks configured | PASS — both services have `healthcheck:` blocks |
| Memory limits set | PASS — MongoDB 512m, Redis 128m |
| No app containers | PASS — only `mongodb` and `redis` services |
| Security comment at top | PASS — comment explicitly states "LOCAL DEVELOPMENT ONLY" |

**Analysis:** Docker Compose configuration is correctly secured for local development. Both services are bound to loopback only (`127.0.0.1`), preventing exposure to the local network. Named volumes ensure data persistence. The absence of authentication credentials is an accepted risk mitigated by the localhost-only binding (see Accepted Risks).

---

### 6. docs/local-setup.md

**File:** `docs/local-setup.md`

| Check | Result |
|-------|--------|
| No real credentials in examples | PASS — all URIs use `localhost` with no credentials |
| Gitleaks installation instructions present | PASS — prerequisites table, line 19 |
| Soft-failure behavior documented | PASS — explicit note: "The pre-commit hook exits 0 with a warning if gitleaks is not installed — it does not block commits" |
| WSL2 warning for Windows developers | PASS — top of document, prominent warning block |
| Port conflict resolution documented | PASS — all four ports covered |
| `.env.example` values shown (no real secrets) | PASS — shows placeholder `localhost` URIs |
| Setup steps match actual flow | PASS — 6-step flow matches tech-spec developer setup flow |

**Analysis:** Documentation is accurate and security-aware. The gitleaks soft-failure behavior is clearly documented in the prerequisites section, ensuring developers understand the risk of skipping installation. All code examples use placeholder/localhost values with no real credentials.

---

## Accepted Risks

The following risks are documented in the tech-spec Risk table and are explicitly accepted. They are NOT flagged as findings.

### AR-1: Gitleaks Soft-Failure (exit 0 when not installed)

**Risk:** If a developer has not installed the gitleaks CLI, the pre-commit hook exits 0 with a warning instead of blocking the commit. A secret could be committed before the developer installs gitleaks.
**Mitigation:** `docs/local-setup.md` clearly documents the behavior and provides installation instructions. gitleaks is listed as "optional but strongly recommended."
**Compensating control absent:** No CI-side gitleaks scan in Epic 01 (deferred). This gap should be addressed in a future epic by adding a CI secret-scanning step (e.g., GitHub Actions with `gitleaks/gitleaks-action`).
**Status:** Accepted for Epic 01. Re-evaluate when CI/CD pipeline is introduced.

### AR-2: No Authentication on MongoDB and Redis

**Risk:** MongoDB and Redis containers run without password authentication.
**Mitigation:** Both services are bound exclusively to `127.0.0.1` (loopback interface), preventing any network access from outside the local machine. On a single-developer workstation this provides adequate isolation.
**Limitation:** On shared developer machines or CI agents this could be a concern, but this configuration is explicitly scoped to local development only (documented in `docker-compose.yml` comment).
**Status:** Accepted for local development. Production deployment must enforce authentication (separate epic).

---

## Recommendations

### REC-1: Add CI-side Secret Scanning (Future Epic)
When the CI/CD pipeline is introduced, add a GitHub Actions workflow step using `gitleaks/gitleaks-action` or `trufflesecurity/trufflehog-actions-scan`. This compensates for the soft-failure gap in the pre-commit hook and provides a mandatory server-side check that cannot be bypassed.

### REC-2: Pin Image Digests for Production (Future Epic)
`mongo:8.0` and `redis:7.4` are mutable tags. For production Docker Compose or Kubernetes manifests, pin to immutable SHA digests (e.g., `mongo:8.0@sha256:...`). Not required for local dev.

### REC-3: Document CSRF Posture in Auth Epic
When JWT/OAuth2 authentication is implemented, include a security checklist item confirming that CSRF protection should remain disabled (bearer token auth is inherently CSRF-immune). If cookie-based sessions are ever introduced, CSRF must be re-enabled in `SecurityConfig`.

### REC-4: Add Gradle Dependency Locking Consideration
If Gradle dependency locking (`dependencyLocking {}`) is adopted in a future epic, add `!backend/gradle/dependency-locks/**` to `.gitignore` negations to ensure lock files are tracked.

---

## OWASP Coverage Matrix

| OWASP Category | Applicable Check | Result |
|----------------|-----------------|--------|
| A01 — Broken Access Control | SecurityWebFilterChain rule ordering; unintended permitAll paths | PASS |
| A02 — Cryptographic Failures | No credentials in tracked files; no secrets in .env.example | PASS |
| A05 — Security Misconfiguration | Docker port binding (127.0.0.1); CSRF disabled; image pinning | PASS |
| A07 — Auth Failures | Security filter chain correctness; /health accessible; all other paths require auth | PASS |
| A03 — Injection | install-hooks.sh variable quoting; no command injection vectors | PASS |
| A09 — Security Logging | gitleaks hook scans staged files; soft-failure documented | PASS (with accepted risk AR-1) |

---

*Report generated by security-auditor agent. All source files read directly. No automated scanners (npm audit, SAST) applicable to this infrastructure-only epic.*
