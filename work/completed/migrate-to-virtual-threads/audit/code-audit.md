# Code Audit — migrate-to-virtual-threads

## Summary verdict

**minor-polish-only.**

The migration from Spring WebFlux + Reactor to Spring MVC + Java 21 virtual threads is structurally complete and internally consistent. All five grep gates pass at the contractual level (zero matches for reactive Mongo/Redis/Web types in production java; one and only one MVC `MethodArgumentNotValidException` handler in `GlobalErrorHandler`; zero residual `WebExchangeBindException` references; `X-Forwarded-For` / `User-Agent` literals exist only inside the consolidated `HttpRequestUtils`). Imperative rewrites of the former Reactor chains in `TelegramWebhookController.receive(...)` and `ProcessTelegramUpdateJob.handle(String)` preserve every documented invariant (401-vs-404 carve-out, `DuplicateKeyException` self-heal, event-write-before-status-flip, CAS first-writer-wins, all 17 former `.block()` sites collapsed to direct sync calls). The seven focus areas produced **no critical or major findings**; six minor findings and one nit are documented below for follow-up engineering.

The most material observation is a **stale Javadoc in `jobs/JobRunrMongoConfig.java`** (lines 16-21) that still tells the reader the application's primary MongoDB integration is `spring-boot-starter-data-mongodb-reactive` — false post-flip. The bean code itself is correct; only the explanatory comment is wrong. This is a Task-13 documentation-cleanup escape (the task wrote the three references docs but did not sweep production source-level Javadoc).

## Scope

- **Files read:** 34 production-source Java files + 2 build/property files + 4 in-context documentation files (8 specs, knowledge-base, decisions, code-research) = 40+ artefacts total.
- **In-scope grading:** the 34 Java files + `build.gradle` + `application.properties`.
- **Read for context (not graded):** `patterns.md`, `architecture.md`, `deployment.md`, all four feature specs.

### File list

Phase A:
- `backend/src/main/java/com/botfunnel/common/SessionAttributes.java`
- `backend/src/main/java/com/botfunnel/subscriber/SubscriberService.java`
- `backend/src/main/java/com/botfunnel/subscriber/NoOpSubscriberService.java`
- `backend/src/main/java/com/botfunnel/funnel/FunnelTriggerService.java`
- `backend/src/main/java/com/botfunnel/funnel/NoOpFunnelTriggerService.java`

Phase B — Task 3:
- `backend/build.gradle`
- `backend/src/main/resources/application.properties`
- `backend/src/main/java/com/botfunnel/security/SecurityConfig.java`
- `backend/src/main/java/com/botfunnel/security/RememberMeCookieSerializer.java`
- `backend/src/main/java/com/botfunnel/common/GlobalErrorHandler.java`

Phase B — Task 4 (repositories):
- `backend/src/main/java/com/botfunnel/user/UserRepository.java`
- `backend/src/main/java/com/botfunnel/project/ProjectRepository.java`
- `backend/src/main/java/com/botfunnel/bot/BotRepository.java`
- `backend/src/main/java/com/botfunnel/events/EventRepository.java`
- `backend/src/main/java/com/botfunnel/webhook/RawUpdateRepository.java`

Phase B — Task 5 (leaf services + HttpRequestUtils):
- `backend/src/main/java/com/botfunnel/user/UserService.java`
- `backend/src/main/java/com/botfunnel/events/EventService.java`
- `backend/src/main/java/com/botfunnel/email/EmailService.java`
- `backend/src/main/java/com/botfunnel/common/HttpRequestUtils.java`

Phase B — Task 6 (auth stack):
- `backend/src/main/java/com/botfunnel/auth/AuthService.java`
- `backend/src/main/java/com/botfunnel/auth/AuthController.java`

Phase B — Task 7 (project + bot + profile):
- `backend/src/main/java/com/botfunnel/project/ProjectService.java`
- `backend/src/main/java/com/botfunnel/project/ProjectController.java`
- `backend/src/main/java/com/botfunnel/bot/BotService.java`
- `backend/src/main/java/com/botfunnel/bot/BotController.java`
- `backend/src/main/java/com/botfunnel/profile/ProfileService.java`
- `backend/src/main/java/com/botfunnel/profile/ProfileController.java`

Phase B — Task 8 (telegram clients):
- `backend/src/main/java/com/botfunnel/bot/TelegramApiClient.java`
- `backend/src/main/java/com/botfunnel/bot/TelegramSender.java`

Phase B — Task 9 (webhook + jobs + admin):
- `backend/src/main/java/com/botfunnel/webhook/TelegramWebhookController.java`
- `backend/src/main/java/com/botfunnel/webhook/WebhookPayloadSizeFilter.java`
- `backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java`
- `backend/src/main/java/com/botfunnel/jobs/HardDeleteJob.java`
- `backend/src/main/java/com/botfunnel/jobs/ProjectHardDeleteJob.java`
- `backend/src/main/java/com/botfunnel/admin/SuperAdminSeeder.java`

Phase B — Task 10:
- `backend/src/main/java/com/botfunnel/HealthController.java`

Cross-checked (not in task 14.md scope list but materially relevant to focus area #4):
- `backend/src/main/java/com/botfunnel/jobs/JobRunrMongoConfig.java` — dual-MongoClient bean per D11; finding M5 below.

Documentation files (context only, not graded):
- `.claude/skills/project-knowledge/references/patterns.md`
- `.claude/skills/project-knowledge/references/architecture.md`
- `.claude/skills/project-knowledge/references/deployment.md`

### Out of scope

- **Security audit** — CSRF policy, session-fixation policy, anti-enumeration timing, token-scrubber coverage, rate-limit fail-open semantics. Deferred to Task 15.
- **Test audit** — `StepVerifier` → JUnit migration, `WebTestClient` → MockMvc migration, mock-triple updates, concurrency-test invariant preservation, TC10/TC11/TC12 per-file coverage floors. Deferred to Task 16.

## Findings by focus area

### 1. Cross-component duplication after `HttpRequestUtils` extraction

**Grep gate:** `grep -rE "X-Forwarded-For|getHeader\(\"User-Agent\"\)" backend/src/main/java/`
**Result:** 6 matches, **all inside `common/HttpRequestUtils.java` only** (lines 9, 25, 29, 33, 35, 50 — 5 in Javadoc/comments, 1 actual `request.getHeader("X-Forwarded-For")` at line 33 and 1 `request.getHeader("User-Agent")` at line 50, both inside the utility's own static methods). **Interpretation: gate passes — no residual duplication.**

**Delegation call-site verification (`grep -rcn "HttpRequestUtils\."`):**

| Site | Call count |
|------|------------|
| `auth/AuthService.java` | 9 (1 import + 8 call sites — `login`, `register`, `verifyEmail`, `forgotPassword`×3, `resetPassword`×2) |
| `bot/BotController.java` | 6 (1 import + 5 calls across `connect`, `disconnect`, `testMessage`) |
| `project/ProjectController.java` | 8 (1 import + 7 calls across `create`, `update`, `softDelete`, `restore`) |
| `profile/ProfileController.java` | 4 (1 import + 3 calls across `changePassword`, `deleteAccount`) |

Exactly the four documented call sites (3 controllers + AuthService) consume the helper. The previously-duplicated `extractIp(ServerWebExchange)` private helper has been deleted from `AuthService` (verified by `grep -n "private.*extractIp" backend/src/main/java/com/botfunnel/auth/AuthService.java` → 0 matches, per Task 6 decisions.md verification).

**Findings:** no issues found.

### 2. SecurityContextHolder vs ReactiveSecurityContextHolder (no leakage)

**Grep gate:** `grep -rE "Mono<|Flux<|WebSession|ServerWebExchange|ReactiveSecurityContextHolder|@EnableWebFluxSecurity|ServerHttpSecurity|WebExchangeBindException" backend/src/main/java/`
**Result:** 2 matches, **both inside comments/Javadoc only**:
- `SecurityConfig.java:46` — `"// WebSessionServerSecurityContextRepository."` — historical-context comment explaining what the new `HttpSessionSecurityContextRepository` replaces (acceptable per task edge-case rule "matches inside string literals, log messages, or test fixtures classify as nit").
- `RememberMeCookieSerializer.java:26` — `"matches the silent invariant of the deleted reactive {@code RememberMeWebSessionIdResolver}"` — historical reference in Javadoc.

No reactive Spring Security types are imported or instantiated anywhere in production Java. **Interpretation: gate passes — only comment-level legacy references remain.**

**`SecurityContextHolder` (servlet thread-local) usage verification:**

| Site | Pattern |
|------|---------|
| `AuthService.java:143` | `SecurityContextHolder.getContext().getAuthentication()` in `me()` — 4-clause unauthenticated guard preserved verbatim from the reactive predecessor's `switchIfEmpty + isAuthenticated + instanceof` chain. |
| `AuthService.java:264` | `SecurityContextHolder.clearContext()` in `logout()` — defensive thread-local clear on a VT that is per-request anyway (cost is zero, alignment with Spring Security guidance). |
| `ProjectController.java:126`, `ProfileController.java:69`, `BotController.java:79` | Identical `currentUserId()` helper — `Authentication auth = SecurityContextHolder.getContext().getAuthentication()` + null-check + `isAuthenticated()` + `instanceof AppUserDetails` pattern-match. |

The three controller helpers duplicate the same six-line shape. Not flagged as a finding because: (a) the duplication is below the abstraction threshold the project sets — `AppUserDetails` lives in `auth/`, so factoring would force a `common/` helper that imports from `auth/` (or an `auth/` helper that every controller depends on, which is the current shape), and (b) the duplicated code is purely defensive — no business logic.

**Findings:** no issues found.

### 3. HttpSession vs WebSession separation

**Grep gate (same as #2):** zero residual `WebSession` imports in production Java; the only two `WebSession`-token matches are documentation references in `SecurityConfig.java:46` and `RememberMeCookieSerializer.java:26`.

**`HttpSession` consumption verification:**

| Site | Pattern | Correctness |
|------|---------|-------------|
| `AuthService.java:257-259` (logout) | `HttpSession session = httpRequest.getSession(false); if (session != null) session.invalidate();` | ✓ Idempotent logout — `getSession(false)` does not materialise a session for an already-logged-out user. Matches user-spec line 53 (current session only, no global termination). |
| `AuthService.java:608-611` (openSession after authenticate) | `HttpSession session = httpRequest.getSession(true); httpRequest.changeSessionId(); session.setMaxInactiveInterval(...); securityContextRepository.saveContext(...)` | ✓ **Session-fixation defence is correctly invoked** at the documented point — mints a fresh `JSESSIONID` BEFORE the SecurityContext save. Mirrors the prior reactive `WebSession.invalidate() + new session` semantics. Task 6 verification (decisions.md line 167) pins call ordering via Mockito `InOrder`. |
| `ProfileService.java:69, 77, 116-128` | `HttpSession currentSession` and `HttpSession session` parameters threaded through `changePassword`, `verifyAndRotate`, `deleteAccount`. | ✓ `terminateAllSessionsExcept(saved.getId(), currentSession.getId())` excludes the current session document; `deleteAccount` then invalidates the local session as a Set-Cookie removal signal. |
| `ProfileController.java:46, 61` | `HttpSession session` parameter on `changePassword` and `deleteAccount`. | ✓ Servlet container injects the per-request session. |

The 8-line block at `AuthService.java:591-611` deserves explicit callout: the comment in lines 596-607 documents both **(a)** the rationale for `getSession(true)` BEFORE `changeSessionId()` (the latter requires an existing session) and **(b)** the residual-attribute-invariant assumption that no current writer puts state into an anonymous session before login. This is correct and well-documented; the future-maintenance hazard (if a filter ever starts writing pre-auth session attributes) is explicitly called out in the comment as a `existing.invalidate(); getSession(true);` switch.

**Findings:** no issues found.

### 4. MongoTemplate consistency vs hidden reactive references

**Grep gate:** `grep -rE "ReactiveMongoTemplate|ReactiveMongoRepository|reactivestreams\.client\.MongoClient|ReactiveRedisTemplate|ReactiveRedisConnectionFactory" backend/src/main/java/`
**Result:** **zero matches.** All five repository interfaces extend `MongoRepository`; all `*Service` and `*Job` classes inject `MongoTemplate` (sync); `RedisTemplate<String,String>` / `StringRedisTemplate` (sync) replaces the reactive variants. **Interpretation: gate passes.**

**Dual-MongoClient setup (D11) verification:**

| Bean | Owner | Location | Purpose |
|------|-------|----------|---------|
| Sync `MongoClient` (Spring main) | `spring-boot-starter-data-mongodb` auto-config | n/a | Backs `MongoTemplate` and 5 `MongoRepository` interfaces |
| Sync `MongoClient` (JobRunr) | `jobs/JobRunrMongoConfig.jobrunrMongoClient(...)` | `backend/src/main/java/com/botfunnel/jobs/JobRunrMongoConfig.java:32-39` | Backs JobRunr's `MongoDBStorageProvider` |

`JobRunrMongoConfig` uses `@Bean(destroyMethod = "close")` and `@Value("${spring.data.mongodb.uri}")` so the JobRunr-side client shares the connection URI with Spring's main client but has its own pool — exactly the D11 design. The bean creates `MongoClients.create(MongoClientSettings.builder().applyConnectionString(...).uuidRepresentation(STANDARD).build())`; the `uuidRepresentation` override is required because JobRunr's `MongoDBStorageProvider` rejects the driver's post-4.0 default of `UNSPECIFIED`. This rationale is documented in the bean's Javadoc.

**Cross-wiring check:** `MongoClient` is not autowired anywhere else in production code (per `grep -rn "MongoClient" backend/src/main/java/`). The only consumer of the `jobrunrMongoClient` bean is JobRunr's autoconfiguration, which picks it by type. Spring's auto-configured main `MongoClient` is consumed only by Spring Data's `MongoTemplate` (transitively). **No accidental cross-wiring.**

**Findings:**

#### Minor

- **M5 — `JobRunrMongoConfig.java:14-21` stale class-Javadoc.** The Javadoc still asserts `"The application's primary MongoDB integration is reactive (spring-boot-starter-data-mongodb-reactive), but JobRunr's StorageProvider only supports the synchronous driver"`. Post-migration the primary integration is `spring-boot-starter-data-mongodb` (sync) and the second `MongoClient` is now retained per D11 as a connection-pool-sharing decision, not as a sync-vs-reactive bridge. **Recommended fix:** rewrite the Javadoc to read `"Per Decision D11, JobRunr keeps its own sync MongoClient bean separate from Spring's main MongoClient bean (which is also sync post-migration). Two distinct connection pools intentionally coexist — consolidation is unmotivated refactor with subtle JobRunr behaviour-change risk."` Bean code itself is unchanged. Task-13 documentation-cleanup escape (the docs sweep covered `patterns.md`/`architecture.md`/`deployment.md` but not production-source Javadoc).

### 5. RestClient construction consistency between `TelegramApiClient` and `TelegramSender`

Both files construct `RestClient` via the same shape — verified by side-by-side read:

| Aspect | `TelegramApiClient.java:54-66` | `TelegramSender.java:88-110` | Match? |
|--------|-------------------------------|------------------------------|--------|
| `HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)` | Uses local `CONNECT_TIMEOUT` (public static final, line 40) | Uses `TelegramApiClient.CONNECT_TIMEOUT` (single source of truth) | ✓ Same value, single SoT |
| `JdkClientHttpRequestFactory(httpClient)` | line 58 | line 98 | ✓ Same factory class |
| `requestFactory.setReadTimeout(responseTimeout)` | `DEFAULT_RESPONSE_TIMEOUT = 10s` (test-overridable via package-private ctor) | `TelegramApiClient.DEFAULT_RESPONSE_TIMEOUT` (single SoT, test-overridable) | ✓ Same constant, single SoT |
| `DefaultUriBuilderFactory(baseUrl)` + `setEncodingMode(NONE)` | lines 60-61 | lines 100-101 | ✓ Identical |
| `builder.uriBuilderFactory(...).requestFactory(...).build()` | lines 62-65 | lines 102-105 | ✓ Identical |
| JSON converter | Inherited from autowired `RestClient.Builder` (Spring autoconfig provides Jackson `MappingJackson2HttpMessageConverter`) | Same | ✓ Same builder, same converters |
| Per-`@Component` singleton | `TelegramApiClient` is `@Component`, instantiated once per app context (line 28) | `TelegramSender` is `@Component`, instantiated once per app context (line 43) | ✓ Two instances, each with one `RestClient` — matches `patterns.md` "one upstream per @Component, one client per @Component" |

The public-static contract that `TelegramSender` consumes from `TelegramApiClient` (`DEFAULT_RESPONSE_TIMEOUT`, `CONNECT_TIMEOUT`, `requireValidTokenShape`, `isTransient`, `scrubTokens`) is documented per-line at the declaration site (`TelegramApiClient.java:35-40, 119-125, 197-219`) with `// Promoted to public for shared use by TelegramSender ...` comments. Strong single-source-of-truth coupling.

**Findings:** no issues found.

### 6. Control-flow clarity (TelegramWebhookController.receive + ProcessTelegramUpdateJob.handle)

#### `TelegramWebhookController.receive(...)` — `backend/src/main/java/com/botfunnel/webhook/TelegramWebhookController.java:105-195`

The Reactor chain (`flatMap` / `switchIfEmpty(Mono.defer)` / `onErrorResume(DuplicateKeyException, ...)` / outer `onErrorResume(ex → 500)`) collapses cleanly to a single 90-line imperative method with one outer `try { ... } catch (Throwable t) { ... }` and one inner `try { rawUpdateRepository.save(...) ... } catch (DuplicateKeyException ex) { ... }`. Each documented invariant is preserved:

| Invariant | Verified at |
|-----------|-------------|
| Lookup order: bot CONNECTED → project existence + soft-delete → secret verify → persist + enqueue | lines 117-144 in the documented order |
| 401 vs 404 carve-out (invalid secret produces 401; everything else maps to 404) | line 143: `return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();` vs lines 125, 137: `return ResponseEntity.notFound().build();` |
| `IllegalArgumentException` from Spring Data ObjectId parse collapses into the 404 path | lines 120-122 and 132-134 — explicit catch + `botOpt/projectOpt = Optional.empty()` |
| Missing `update_id` → 400 with scrubbed WARN log (Telegram contract violation) | lines 147-153 |
| `DuplicateKeyException` self-heal: rejected counter ticks, re-enqueue lookup of existing row, return 200 | lines 167-182 |
| Outer 500 fallback bypasses `GlobalErrorHandler` (preserves no-body response shape for Telegram) | lines 183-191 — `catch (Throwable t)` returns `ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()`; comment lines 184-190 explicitly state the rationale |
| `BiConsumer<UUID, String> enqueuer` package-private test seam preserved | lines 71, 101-103 |
| Timer started in try, stopped in finally | lines 111, 192-194 |

Control flow is shallow (max 3 levels of nesting; never deeper than `try { try { ... } catch (DuplicateKeyException) { ... } } catch (Throwable) { ... } finally { ... }`). Early returns abound (no else-after-return anti-pattern). Every counter increment is paired with a single explicit decision branch.

#### `ProcessTelegramUpdateJob.handle(String)` — `backend/src/main/java/com/botfunnel/webhook/ProcessTelegramUpdateJob.java:78-112`

All 17 former `.block()` sites have been collapsed to direct sync calls. `MongoTemplate` (sync) replaces `ReactiveMongoTemplate`; `subscriberService.upsertFromTelegramUpdate(...)` and `funnelTriggerService.fire(...) / cancelActiveFor(...)` invoke `void` interface methods directly (Phase A flipped these stubs from `Mono<Void>` to `void`). Each invariant is preserved:

| Invariant | Verified at |
|-----------|-------------|
| Re-entry guard on `processingStatus == DONE` (Decision 9) — short-circuit BEFORE event emission and ANY mutation | lines 88-92, explicit early return |
| Cascade ordering: every `event.logEvent(...)` runs BEFORE the `rawUpdate.processingStatus → DONE` save (audit-survives-crash invariant) | lines 102 (`dispatch(...)` calls all `logEvent*` helpers) → 104-105 (`setProcessingStatus(DONE)` + `save(...)`). Verified by `eventWriteBeforeStatusFlip_orderingInvariant` IT (decisions.md line 246). |
| Atomic `findAndModify` for first-writer-wins ownerChatId CAS — predicate-fail returns null treated as benign no-op | lines 218-232 — the `if (updated != null)` log-only branch is the entire predicate-success handling; predicate-fail returns silently. Verified by `ownerChatIdPopulate_secondStartDifferentChat` IT. |
| `findAndModify` for atomic FAILED status + scrubbed/truncated error write | lines 122-127 in `handleFailure(...)` — atomic write, NEVER `findById + setter + save` (two retries would trample each other in the read-write race window) |
| Rethrow new `RuntimeException` with ONLY the scrubbed+truncated message (no cause chain — never re-leaks raw token via `t.getMessage()` or `t.getCause().getMessage()`) | lines 137-143, `setStackTrace(t.getStackTrace())` copies trace without cause linkage |
| `null` Project handling: `userId = project == null ? null : project.getOwnerId()` | line 101 — accepted parity with original reactive semantics |

Dispatch matrix (lines 146-198) preserves the `null`-check ladders verbatim: `message == null` → `logEventOther("kind")`, `text == null` → `logEventOther("message")`, `text.startsWith("/")` → command-switch, otherwise plain-text private-chat subscriber-upsert flow. `handleStart` / `handleStop` correctly fork on `isPrivate && chatId != null`, with the AC9 invariant (group/supergroup/channel /start emits event only, no ownerChatId populate, no stub calls) at lines 202-206.

**Findings:** no issues found.

### 7. GlobalErrorHandler error mapping

**Grep gate 4:** `grep -rE "@ExceptionHandler\(MethodArgumentNotValidException" backend/src/main/java/com/botfunnel/common/GlobalErrorHandler.java`
**Result:** exactly 1 match at line 26. **Interpretation: gate passes.**

**Grep gate 5:** `grep -rE "@ExceptionHandler\(WebExchangeBindException" backend/src/main/java/`
**Result:** zero matches. **Interpretation: gate passes — reactive handler is gone.**

**Handler-by-handler review (`GlobalErrorHandler.java:1-49`):**

| Handler | Status | Body shape | Verified at |
|---------|--------|-----------|-------------|
| `@ExceptionHandler(AppException.class)` | `ex.getStatus()` | `new ErrorResponse(ex.getMessage(), ex.getCode())` | lines 20-24 — preserves the `code` from the domain exception |
| `@ExceptionHandler(MethodArgumentNotValidException.class)` | 400 | `new ErrorResponse(message, null)` — joined `"field: defaultMessage"` strings from `fieldErrors` + `globalErrors` via `Collectors.joining(", ")` | lines 26-35 — **`code: null` invariant preserved per AC17** |
| `@ExceptionHandler(ResponseStatusException.class)` | `ex.getStatusCode()` | `new ErrorResponse(reason ?? message, null)` | lines 37-41 |
| `@ExceptionHandler(Throwable.class)` | 500 | `new ErrorResponse("Internal server error", null)` + ERROR log with stack trace | lines 43-48 — catch-all fallback |

The `BindingResult` extraction at lines 28-33 enumerates **both** `getFieldErrors()` AND `getGlobalErrors()`. The reactive predecessor used the same dual-stream join, so the joined message format ("field1: msg1, field2: msg2, objectName: globalMsg") is preserved byte-identical. AC17 integration test (`register_emptyEmail_400_codeNull_messageJoinedFormat` per decisions.md Task 12) pins both the `$.code == null` invariant and the joiner regex.

Other handlers preserved/migrated correctly:
- `IllegalArgumentException` — NOT explicitly mapped. Falls through to `Throwable` → 500. This matches the reactive predecessor's behaviour (no explicit `IllegalArgumentException` handler existed in the WebFlux version per code-research §1).
- `DuplicateKeyException` — NOT mapped here. Handled at the call-site (`TelegramWebhookController.receive` self-heal path; `BotService.mapPersistError` index-name disambiguation). Correct — `DuplicateKeyException` semantics are inherently domain-specific.
- `IllegalStateException` — NOT explicitly mapped. Falls through to `Throwable` → 500. Same as `IllegalArgumentException`.

**Findings:** no issues found.

---

## Cross-cutting observations (not finding-blocking)

The following observations cut across multiple focus areas and are documented for engineering hygiene, not as standalone findings:

1. **Defensive `SecurityContextHolder.clearContext()` in `AuthService.logout`** (line 264) is technically unnecessary under VT — every request runs on a fresh VT, and Spring Security's filter chain runs the standard `SecurityContextHolderFilter` clear-after-request. The defensive call costs nothing and matches Spring Security's documented guidance, so it's correct as-is. Worth noting only because it could mislead a reader into thinking the VT model needs special handling.

2. **The `RememberMeCookieSerializer` constructor's `PropertyMapper.alwaysApplyingWhenNonNull()`** (line 35) reuses Spring Boot's idiom for cookie-attribute propagation from `ServerProperties`. The implementation is ~20 LOC and a clean improvement over the deleted 60-LOC reactive `RememberMeWebSessionIdResolver` (per Decision 3 rationale). No behavioural regression because both implementations write the same `SESSION` cookie with the same attributes.

3. **`SecurityConfig.CsrfCookieMaterializer`** (private static inner class at lines 119-129) is the documented deviation from tech-spec line 27/145: MVC's `CsrfFilter` does NOT eagerly invoke the request-attribute handler (verified against Spring Security 6.5 reference). Decisions.md Task 3 documents this deviation; the inner filter is the official Spring Security 6.5 SPA-integration pattern. Inner-class scoping is correct (only this `SecurityConfig` needs to reference it, no external use).

4. **`HealthController.health()`** has been correctly demoted from `Mono<Map<String,String>>` to `Map<String,String>` (line 12). Last reactive return type in production main was eliminated by Task 10.

5. **`SuperAdminSeeder.seed()`** (`admin/SuperAdminSeeder.java:49-79`) directly consumes the synchronous `UserRepository` — `.orElse(null)`, plain `userRepository.save(...)`. The 3 former `.block()` call sites are gone. `@Component` + `implements ApplicationRunner` + idempotent-on-startup semantics preserved. Errors are caught and logged via `run(ApplicationArguments)`'s try/catch (line 42-46) to keep the boot non-blocking.

6. **`HardDeleteJob` + `ProjectHardDeleteJob`** cleanly demonstrate the "13-line strangler-fig win" of this migration: `userRepository.findByStatusAndDeletedAtBefore(...)` returns `List<User>` directly (no `.collectList().blockOptional()`), `userRepository.deleteAll(users)` returns `void` directly (no `.block()`). `ProjectHardDeleteJob` injects `MongoTemplate` and writes two `template.remove(Query, collection)` calls plus a `for`-loop of `eventService.logEvent(...)` between them — cascade ordering invariant preserved verbatim (sweep events → emit per-project `project_hard_deleted` → drop project documents).

---

## Deferred to sibling audits

The following concerns surfaced during this audit but belong elsewhere:

- **(Task 15 — Security Audit)** CSRF cookie write path correctness post-`csrfCookieMaterializer` removal (M5 reactive scope) — partially addressed by the new `CsrfCookieMaterializer` inner filter in `SecurityConfig.java:119-129` but the BREACH-resistance / token-leak boundary review is Task 15 territory. Decisions.md Task 12 documents the `XSRF-TOKEN co-emission` IT structural impossibility on a CSRF-protected POST; Task 15 should review whether TC11 coverage in `SecurityConfigTest.csrfCookie_writtenOnSafeVerbRequest` is sufficient.
- **(Task 15)** `X-Forwarded-For` trust note in `HttpRequestUtils.java:25-27` — production deployment must front the backend with a reverse-proxy that overwrites this header; absent that, clients can forge it to bypass the per-IP brute-force counter. Documented inline, but not enforced.
- **(Task 15)** `AuthService.canonicalize(...)` uses `Locale.ROOT` (line 478) to prevent Turkish-locale dotless-i case-folding bypass. Reviewed defensively in code; sibling security audit should cross-check call sites.
- **(Task 15)** Anti-enumeration timing oracle in `doForgotPassword` (lines 293-330): `Thread.sleep(FORGOT_DUMMY_DELAY.toMillis())` calibrated to ~40ms equalises the unknown-email and known-email response time. Under VT the sleep is a cheap carrier-thread park. Task 15 should validate the calibration window in a measurement test on the actual runtime stack.
- **(Task 15)** `TelegramSender.sendOnce` plaintext-token lifetime is correctly bounded to a single method scope (verified in code), but the Javadoc claim that the token "never escapes the calling virtual thread" depends on the absence of token-leak via logging — the token-scrubber sites enumerated in TC10 of tech-spec are partly covered by `BotTokenLeakTest` per-site assertions and require Task 15 review.
- **(Task 16 — Test Audit)** `StepVerifier` → JUnit migration completeness across 15 test files. Confirmed by `grep -r "StepVerifier" backend/src/test/java/` returning 0 (decisions.md Task 4-10 verifications) but the test-quality dimension is Task 16's responsibility.
- **(Task 16)** `WebTestClient` → MockMvc migration completeness across 12 IT files; `ConcurrencyTestUtils.parallelInvoke` VT-barrier correctness for the 3 concurrency-test sites; `@SpringBootTest(webEnvironment=RANDOM_PORT)` vs MockMvc split (D14) producing the new `TelegramWebhookP99IT` and `BotConnectRaceIT` classes — Task 16 should verify that test invariants survive the test-shape change.
- **(Task 16)** AC16 / AC17 / AC18 / TC5 / TC10 / TC11 / TC12 acceptance coverage — the 6 new ITs Task 12 added are pinning the right invariants but the per-file coverage floors of TC12 need explicit jacoco-report validation.
- **(Task 13 follow-up — already done)** Documentation cleanup in `patterns.md`/`architecture.md`/`deployment.md` per AC14 — verified clean by Task 13 decisions.md entry. **Caveat:** finding M5 above (`JobRunrMongoConfig.java` stale Javadoc) is a Task-13 escape because the docs sweep covered the three references files but did not include production-source Javadoc. Task 13 should append the `JobRunrMongoConfig` Javadoc fix.

---

## Findings appendix (by severity)

### Critical

*None.*

### Major

*None.*

### Minor

- **M5 — `jobs/JobRunrMongoConfig.java:14-21` stale class-Javadoc.** Javadoc still references `spring-boot-starter-data-mongodb-reactive` as the "primary MongoDB integration", which became false at the Phase B atomic flip. Bean code is correct (sync `MongoClient` per D11). **Fix:** rewrite the Javadoc to align with the post-migration state per the recommendation in focus area #4 above. **Owner:** Task 13 follow-up (Documentation cleanup escape).

### Nit

- **N1 — `SecurityConfig.java:41-51` `securityContextRepository()` Javadoc.** The comment correctly explains why this bean is NOT wired into `HttpSecurity` (Spring Security 6.x default chain builds its own `DelegatingSecurityContextRepository`); the bean exists only for `AuthService.saveContext` after manual auth. The Javadoc could be more concise — the three-line preamble plus four-line "NOT wired into HttpSecurity" rationale could collapse into one sentence. Cosmetic; reader-comprehension is already fine. **No fix required.**

---

## Verdict justification

Zero critical findings, zero major findings, one minor finding (Javadoc drift), one nit. Per the severity rubric and the "Status Decision Matrix" in code-reviewing skill: **minor-polish-only** is the correct classification.

The migration's strongest signal is the grep-gate result quality: every contractual gate from tech-spec line 474 passes at zero matches, with the only remaining `WebSession`/`ServerWebExchange`/`ReactiveSecurityContextHolder` tokens being legitimate Javadoc-historical references. The imperative rewrites of the two most complex Reactor chains (`TelegramWebhookController.receive` and `ProcessTelegramUpdateJob.handle`) are well-structured, shallow-nested, early-return-heavy, and preserve every documented invariant — verified by the integration tests Task 12 added (AC16/AC17/AC18 + TC5) and the existing webhook + jobs test suites (decisions.md Task 9 verification: 113 webhook tests + jobs + admin all green).

The single material follow-up engineering work is the `JobRunrMongoConfig` Javadoc fix; this should land as a one-line documentation patch alongside any future Task 13 sweep, not as a blocker for merge.
