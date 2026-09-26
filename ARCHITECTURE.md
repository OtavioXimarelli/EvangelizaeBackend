# Evangelizae API — Architecture (Spine)

> **Current implementation plan:** `LITURGY_INTEGRATION_PLAN.md` is the source of truth for the feature-first MVC refactor and scraper integration. This document retains broader historical design context that is not implemented yet.

**Stack:** Java 25, Spring Boot 4.1.1 `pom.xml:10`, Maven, **MongoDB**, Docker `Dockerfile:1`
**Entry:** `src/main/java/org/evangelizae/api/EvangelizaeApiApplication.java:8`, config `src/main/java/org/evangelizae/api/config/AppProperties.java:8` / `src/main/resources/application.yml:31`
**Public contract:** `openapi/evangelizae-v1.openapi.yml:1`

> **Note on `CONTRACT_SCRAPER.md:38`:** Original contract says `PostgreSQL` for liturgy. The enforceable contract is `POST /internal/v1/liturgy/import` `CONTRACT_SCRAPER.md:88` + `@Transactional` upsert `CONTRACT_SCRAPER.md:392` + `contentHash` diff `CONTRACT_SCRAPER.md:392` + response `CONTRACT_SCRAPER.md:420` + status codes `CONTRACT_SCRAPER.md:451`. This architecture stores liturgy in **MongoDB** to unify the store around social interactions (flexible documents) while preserving identical API semantics. If you must strictly follow the contract doc, replace `Mongo liturgy` below with `PostgreSQL + Flyway` — the rest of the spine is unchanged.

---

## 1. Goals and Constraints

1. **Liturgical correctness:** Never fabricate Catholic content or serve a stale date as `LIVE`. Validation in `src/main/java/org/evangelizae/api/liturgy/service/LiturgyService.java:99` and `openapi/evangelizae-v1.openapi.yml:9` (`GET /liturgy/today` with `timezone` + `locale=pt-BR` only `src/main/java/org/evangelizae/api/liturgy/service/LiturgyService.java:93`).
2. **Timezone correctness:** All date logic is `LocalDate.ofInstant(clock.instant(), ZoneId)` `src/main/java/org/evangelizae/api/liturgy/service/LiturgyService.java:43`, IANA validation `src/main/java/org/evangelizae/api/liturgy/service/LiturgyService.java:82`. UTC `Instant` persisted (`LiturgySource.java:3`).
3. **Ingestion correctness:** Liturgy is ingested from the autonomous Python **LiturgyScraper** `CONTRACT_SCRAPER.md:1` via `POST /internal/v1/liturgy/import` `CONTRACT_SCRAPER.md:88` — not pulled at request time.
4. **Future bounded contexts:** `identity` (auth+users), `prayer` (one completion per `UserId+LocalDate`, streak), `spiritualplans` (one active/user), `social` (follows, likes, comments, feeds — drives MongoDB choice), `moderation`, `liturgy`. All on **MongoDB** (flexible social schema, embedded readings); `Redis` for rate-limit, `JWT`, `Google OAuth`, `Email`.
5. **Non-functional:** Batch `14-30 days` `CONTRACT_SCRAPER.md:463` processed `<3s` `CONTRACT_SCRAPER.md:464`, client timeout `30s` `CONTRACT_SCRAPER.md:615`, transactional (Mongo multi-doc `ClientSession`) `CONTRACT_SCRAPER.md:414`, idempotent `CONTRACT_SCRAPER.md:392`, GDPR deletion across collections.

---

## 2. Style Decision

**Modular Monolith + Feature-first MVC (Controller-Service-Repository)**

*Why:* Single deployable `Dockerfile:8`, single DB `MongoDB`, and low operational complexity. MVC keeps the initial backend understandable while preserving explicit feature ownership. No `JPA/PostgreSQL` — one persistence paradigm.

*Rejected for now:* Microservices, event-driven everywhere, CQRS per module, generic repository abstraction, abstract factories, hybrid `PostgreSQL + MongoDB` (extra ops for <100MB liturgy `SYSTEM_DESIGN.md:12`).

*Package-by-feature:* Each feature owns its `controller`, `service`, `repository`, and `model` packages. Cross-cutting HTTP and configuration concerns live in `web` and `config`.

---

## 3. System Overview

```
┌──────────────────────────────────────────────────────────┐
│         LiturgyScraper (Python, stateless, cron)         │  CONTRACT_SCRAPER.md:16 / :53
│  CNBB PRIMARY https://liturgiadiaria.edicoescnbb.com.br  │  CONTRACT_SCRAPER.md:12
│  Vatican News VALIDATION https://www.vaticannews.va/...  │  CONTRACT_SCRAPER.md:13
│  - fetch, parse, normalize, cross-validate refs          │  CONTRACT_SCRAPER.md:59
│  - sourceHash + contentHash SHA-256 CONTRACT_SCRAPER.md:226
│  - 14d sliding window POST LiturgyImportRequest          │  CONTRACT_SCRAPER.md:56
└────────────────────────────┬─────────────────────────────┘
                             │ POST /internal/v1/liturgy/import
                             │ Authorization: Bearer <LITURGY_IMPORT_TOKEN> CONTRACT_SCRAPER.md:90
                             ▼
┌──────────────────────────────────────────────────────────┐
│   Core Backend (Java / Spring Boot + MongoDB)            │  CONTRACT_SCRAPER.md:31 (PostgreSQL → MongoDB)
│   - Bearer auth, schema validation,                     │
│     Mongo ClientSession @Transactional upsert            │  CONTRACT_SCRAPER.md:414 (Mongo 4.2+ multi-doc)
│   - contentHash diff (created/updated/unchanged)         │  CONTRACT_SCRAPER.md:392
│   - version history, lastVerifiedAt                      │
│   - serves GET /liturgy/today from Mongo                 │  openapi/evangelizae-v1.openapi.yml:9
│   - identity/prayer/social/plans/moderation → MongoDB    │
│   - Redis rate-limit                                     │
└──────────────────────────────────────────────────────────┘
                             │
                             ▼
                      Client (Frontend)
```

**Division of responsibilities `CONTRACT_SCRAPER.md:15`:**
- Scraper: stateless `CONTRACT_SCRAPER.md:43`, no DB access `CONTRACT_SCRAPER.md:44`, no `UUID` knowledge `CONTRACT_SCRAPER.md:45`, sliding batch `CONTRACT_SCRAPER.md:46`, CNBB authority `CONTRACT_SCRAPER.md:47`.
- Backend: authenticates `CONTRACT_SCRAPER.md:381`, validates `CONTRACT_SCRAPER.md:392`, upserts `CONTRACT_SCRAPER.md:395`, persists version history in **MongoDB** collections.

Legacy pull mode (`HttpLiturgyProvider.java:14` + `LiturgyProvider.java:1` + `LiturgyService.java:47` with `ConcurrentHashMap` cache `src/main/java/org/evangelizae/api/liturgy/service/LiturgyService.java:31` and `AppProperties.liturgy.provider` `src/main/resources/application.yml:34`) is deprecated after Mongo cutover. Keep for fallback during migration, then delete.

---

## 4. Module Map

```
org.evangelizae.api
├── app                          // composition root: EvangelizaeApiApplication.java:8, Clock ApiConfiguration.java:13, MongoConfig
├── shared
│   ├── kernel                   // Zero Spring/Mongo deps: UserId, DomainError, Clock, ApiError.java, Hash value objects
│   └── infrastructure           // GlobalExceptionHandler.java:15, CORS ApiConfiguration.java:18, Bearer filter, RateLimit, Idempotency, Observability, RequestId
├── liturgy      [MongoDB]       // Ingestion + delivery — now Mongo documents (embedded readings)
├── identity     [MongoDB]       // Merged auth+users — owns User aggregate, social-ready
├── prayer       [MongoDB]       // Prayer completions, streaks
├── social       [MongoDB]       // Follows, likes, comments, feeds — flexible documents, expandable
├── spiritualplans [MongoDB]     // Active plan per user
└── moderation   [MongoDB]       // Reports, flags
```

Each business module owns `domain`, `application`, `ports/out`, `adapters/in`, `adapters/out`, `tests`. Single `MongoDB` URI for all — no cross-DB.

**Inter-module dependencies (allowed only):**
```
prayer -> shared/kernel (UserId)
social -> shared/kernel (UserId)
spiritualplans -> shared/kernel (UserId)
moderation -> shared/kernel, identity (via UserId only)
liturgy -> shared/kernel only (no dependency on identity)
identity -> shared/kernel
shared/infrastructure -> shared/kernel (never business modules)
```
No module imports another's `domain` entity. Cross-module data via `UserId` value object. Single-DB transactions possible when needed (e.g., `DeleteAccountUseCase` across `users` + `prayer_completions` + `follows`).

---

## 5. Dependency Rule (Enforced)

```
adapters/in  ──►  application  ──►  domain  ◄── ports/out
                         ▲
                    shared/kernel
```

- `domain`: pure Java, zero `org.springframework.*`, `org.springframework.data.mongodb.*`, `io.jsonwebtoken.*`, `com.google.*`.
- `application`: orchestrates use cases, depends only on `ports/out` interfaces and `domain`.
- `ports/out`: interfaces (`UserRepository`, `TokenService`, `LiturgicalDayRepository`, `InteractionRepository`, etc.).
- `adapters`: `Spring MVC` + `Spring Data MongoDB` + `Redis/JWT/Google` + `adapters/in/web` controllers. No `JPA`.
- `shared/kernel`: depended on by all, depends on none (except JDK).
- Enforce with **ArchUnit** test: `no classes in ..domain.. should depend on org.springframework.. or org.springframework.data.mongodb..`.

---

## 6. Hexagonal Per Module (All MongoDB)

### 6.1 liturgy [MongoDB]
- **domain:** `LiturgicalDay(date PK)`, `Celebration(name, type, liturgicalColor)`, `LiturgicalSeason(name, week, liturgicalYear)`, `LiturgicalParts(readings)`, `Reading(type, reference, title, response, text XOR options)` invariant `CONTRACT_SCRAPER.md:145`, `SourceInfo(name, role, url, collectedAt, sourceHash, contentHash)` `CONTRACT_SCRAPER.md:155`, `Validation(status, sourcesCompared, warnings)` `CONTRACT_SCRAPER.md:165`, `LiturgyVersion`, enums `CelebrationType/Memorial/Feast/Solemnity`, `LiturgicalColor GREEN/WHITE/RED/PURPLE/ROSE` `CONTRACT_SCRAPER.md:187`, `ReadingType FIRST_READING/SECOND_READING/PSALM/GOSPEL/ACCLAMATION/SEQUENCE` `CONTRACT_SCRAPER.md:196`, `SourceName CNBB/VATICAN_NEWS`, `SourceRole PRIMARY/VALIDATION`, `ValidationStatus VALID/WARNING/REVIEW_REQUIRED` `CONTRACT_SCRAPER.md:217`. Also `LiturgyValidator` (extracted from `LiturgyService.java:99`).
- **application:** `ImportLiturgyBatchUseCase` (`Mongo ClientSession @Transactional` `CONTRACT_SCRAPER.md:569` via `TransactionalOperator`), `GetTodayLiturgyUseCase`.
- **ports/out:** `LiturgicalDayRepository { Optional<LiturgicalDay> findByDate(LocalDate); LiturgicalDay save(LiturgicalDay) }`, `LiturgyVersionRepository`.
- **adapters/in:** `internal.LiturgyImportController` (`POST /internal/v1/liturgy/import` `CONTRACT_SCRAPER.md:88`), `public.LiturgyController` (`GET /liturgy/today` `src/main/java/org/evangelizae/api/liturgy/web/LiturgyController.java:12` now from `Mongo`).
- **adapters/out:** `MongoLiturgicalDayRepository/LiturgicalDayDocument+Mapper` (`@Document("liturgical_days")`), `MongoLiturgyVersionRepository`. Readings/sources embedded (no `JOIN`).

### 6.2 identity (auth+users) [MongoDB]
- **domain:** `User(id, normalizedEmail unique, username, passwordHash, providerLinks, deactivatedAt, socialProfile{ bio, avatarUrl })`, `RefreshSession(tokenHash, expiresAt, revokedAt)`, `Email` VO, `PasswordPolicy`, `UsernamePolicy`.
- **application:** `RegisterUserUseCase`, `LoginUserUseCase`, `RefreshTokenUseCase` (rotation + reuse detection), `OAuthLoginUseCase` (link by verified email), `DeleteAccountUseCase` (cross-collection deletion in same Mongo transaction), `MigrateGuestDataUseCase`.
- **ports/out:** `UserRepository`, `PasswordHasher`, `TokenService`, `GoogleIdentityProvider`, `EmailSender`.
- **adapters/out:** `MongoUserRepository/UserDocument+Mapper` (`@Document("users")`), `MongoRefreshSessionRepository`, `BCryptPasswordHasher`, `JwtTokenService`, `GoogleOAuthClient`, `SmtpEmailSender`.
- **adapters/in:** `AuthController`.

### 6.3 prayer [MongoDB]
- **domain:** `PrayerCompletion(userId, localDate, completedAt)`, rule `one per user+localDate`, `StreakCalculator`.
- **application:** `CheckInPrayerUseCase` (idempotent, `IdempotencyService`), `GetPrayerStatsUseCase`.
- **ports/out:** `PrayerCompletionRepository { findByUserAndLocalDate(UserId, LocalDate) }`.
- **adapters/out:** `MongoPrayerCompletionRepository` (unique index `userId+localDate`).

### 6.4 social [MongoDB] — expansion reason
- **domain:** `Follow(followerId, followeeId)`, `Like(userId, targetType, targetId)`, `Comment(userId, targetType, targetId, text)`, `FeedItem`.
- **application:** `FollowUserUseCase`, `LikeUseCase`, `CommentUseCase`, `GetFeedUseCase`.
- **ports/out:** `FollowRepository`, `LikeRepository`, `CommentRepository`.
- **adapters/out:** `MongoFollowRepository`, `MongoLikeRepository`, `MongoCommentRepository` — document collections, no schema migration per new interaction type.

### 6.5 spiritualplans [MongoDB]
- **domain:** `SpiritualPlan`, rule `one active per user`.
- **application:** `CreateSpiritualPlanUseCase`, `CompleteStepUseCase`.

### 6.6 moderation [MongoDB]
- **domain:** `Report`, `ModerationDecision`.

---

## 7. Persistence — MongoDB Only (Mongock, Spring Data MongoDB)

**Rule:** Never reuse domain as persistence document. `domain/User.java` ≠ `adapters/out/mongodb/UserDocument.java` (`@Document`) (+ `Mapper`). Prevents `org.springframework.data.mongodb.*` leakage. Domain invariants (`text XOR options` `CONTRACT_SCRAPER.md:145`) enforced in domain + Mongo `validator` JSON schema.

### 7.1 Liturgy — `liturgical_days` (single document, embedded arrays — no JOIN)

```javascript
// liturgical_days — _id = date (ISODate midnight UTC), unique index {date:1}
{
  _id: ISODate("2026-08-24T00:00:00Z"), // CONTRACT_SCRAPER.md:117 unique domain key
  date: "2026-08-24",
  celebration: { name: String, type: String /* WEEKDAY/SUNDAY/MEMORIAL/FEAST/SOLEMNITY CONTRACT_SCRAPER.md:129 */, liturgicalColor: String /* GREEN/WHITE/RED/PURPLE/ROSE CONTRACT_SCRAPER.md:187 */ },
  liturgicalSeason: { name: String, week: Int | null, liturgicalYear: String | null /* A/B/C CONTRACT_SCRAPER.md:132 */ },
  parts: {
    readings: [
      {
        type: String /* FIRST_READING/SECOND_READING/PSALM/GOSPEL/ACCLAMATION/SEQUENCE CONTRACT_SCRAPER.md:196 */,
        reference: String | null,
        title: String | null,
        response: String | null, // psalm refrain CONTRACT_SCRAPER.md:151
        text: String | null,
        options: [ Reading ] | null // alternative readings, all share parent type CONTRACT_SCRAPER.md:153
        // invariant: text XOR options CONTRACT_SCRAPER.md:145 — enforced in domain + validator
      }
    ]
  },
  sources: [
    { name: String /* CNBB/VATICAN_NEWS */, role: String /* PRIMARY/VALIDATION CONTRACT_SCRAPER.md:159 */, url: String | null, collectedAt: ISODate, sourceHash: String /* sha256:... CONTRACT_SCRAPER.md:162 */, contentHash: String /* sha256:... CONTRACT_SCRAPER.md:163 */ }
  ],
  validation: { status: String /* VALID/WARNING/REVIEW_REQUIRED CONTRACT_SCRAPER.md:217 */, sourcesCompared: Int, warnings: [String] },
  note: String | null,
  primaryContentHash: String, // CNBB PRIMARY contentHash for diff CONTRACT_SCRAPER.md:392
  primarySourceHash: String | null,
  lastVerifiedAt: ISODate,
  createdAt: ISODate,
  updatedAt: ISODate
}
// Validation: celebration liturgicalColor enum, readings type enum, text XOR options, sources role enum

// liturgical_day_versions — history, index {liturgicalDate:1, createdAt:-1}
{
  _id: UUID,
  liturgicalDate: ISODate, // ref _id of liturgical_days
  contentHash: String,
  scrapedAt: ISODate, // CONTRACT_SCRAPER.md:104
  payload: Object, // canonical snapshot for audit
  createdAt: ISODate
}
```

`GetToday` is single `findById(date)` — no `JOIN`. `Import` is `ClientSession` transaction over `liturgical_days` + `liturgical_day_versions`.

### 7.2 Identity / Prayer / Social — MongoDB

```javascript
// users — unique {normalizedEmail:1}, unique sparse {googleSub:1}
{ _id: UUID, normalizedEmail: String, username: String, passwordHash: String, googleSub: String | null, socialProfile: { bio: String, avatarUrl: String }, createdAt: ISODate, deactivatedAt: ISODate | null }
// refresh_sessions — unique {tokenHash:1}, index {userId:1, expiresAt:1}
{ _id: UUID, userId: UUID, tokenHash: String, expiresAt: ISODate, revokedAt: ISODate | null }
// prayer_completions — unique {userId:1, localDate:1}
{ _id: UUID, userId: UUID, localDate: Date (midnight), completedAt: ISODate }
// follows — unique {followerId:1, followeeId:1}
{ _id: UUID, followerId: UUID, followeeId: UUID, createdAt: ISODate }
// likes — unique {userId:1, targetType:1, targetId:1}
{ _id: UUID, userId: UUID, targetType: String, targetId: String, createdAt: ISODate }
// comments — index {targetType:1, targetId:1, createdAt:-1}
{ _id: UUID, userId: UUID, targetType: String, targetId: String, text: String, createdAt: ISODate }
// spiritual_plans — partial unique {userId:1} where status=ACTIVE
{ _id: UUID, userId: UUID, status: String /* ACTIVE/COMPLETED/ARCHIVED */, createdAt: ISODate }
// idempotency_keys — TTL on expiresAt, unique {key:1}
{ _id: String (header value), userId: UUID, requestHash: String, responseStatus: Int, responseBody: Object, createdAt: ISODate, expiresAt: ISODate }
```

Multi-doc transactions used within a single use case when needed (e.g., `DeleteAccountUseCase` across `users` + `prayer_completions` + `follows` + `likes` + `refresh_sessions` — all Mongo, so one `ClientSession`).

---

## 8. API Layer

### 8.1 Internal Ingestion (scraper → backend) — MongoDB
- `POST /internal/v1/liturgy/import` `CONTRACT_SCRAPER.md:88` `Content-Type: application/json; charset=UTF-8` `CONTRACT_SCRAPER.md:91`, `camelCase` `CONTRACT_SCRAPER.md:91`, dates `YYYY-MM-DD` `CONTRACT_SCRAPER.md:92`, timestamps `YYYY-MM-DD'T'HH:mm:ss'Z'` `CONTRACT_SCRAPER.md:93`.
- **Auth:** `Authorization: Bearer <LITURGY_IMPORT_TOKEN>` `CONTRACT_SCRAPER.md:90`, `401` missing/malformed, `403` invalid `CONTRACT_SCRAPER.md:384`. Dedicated `BearerAuthFilter` for `/internal/**`, secret from `LITURGY_IMPORT_TOKEN` `CONTRACT_SCRAPER.md:610`, not JWT.
- **DTOs:** Java 25 records `CONTRACT_SCRAPER.md:474` (`LiturgyImportRequest`, `Period`, `LiturgicalDayImport`, `CelebrationImport`, `LiturgicalSeasonImport`, `LiturgicalPartsImport`, `ReadingImport`, `SourceInfoImport`, `ValidationImport`) with `jakarta.validation` (`@NotBlank/@NotNull/@NotEmpty/@Valid`) `CONTRACT_SCRAPER.md:486`.
- **Batch:** `days: list<LiturgicalDay>` `14-30` items `CONTRACT_SCRAPER.md:463`, envelope `schemaVersion, scraperVersion, scrapedAt, period, days` `CONTRACT_SCRAPER.md:99`.
- **Hashes:** `sourceHash = SHA-256(raw bytes)` `CONTRACT_SCRAPER.md:230`, `contentHash = SHA-256(canonical JSON sort_keys, separators=(",",":"))` `CONTRACT_SCRAPER.md:236` on domain fields only `CONTRACT_SCRAPER.md:246` (exclude `scrapedAt, collectedAt, url, scraperVersion` `CONTRACT_SCRAPER.md:247`).
- **Response:** `200 OK` / `201 Created` `CONTRACT_SCRAPER.md:451` body `{ importId (UUID/ULID), status SUCCESS/PARTIAL/FAILED, processed, created, updated, unchanged, rejected }` `CONTRACT_SCRAPER.md:420`. Errors: `400` malformed, `422` domain, `401/403` auth, `5xx` retry `CONTRACT_SCRAPER.md:451`.
- **SLA:** `<3s` normal `CONTRACT_SCRAPER.md:464`, client `30s` `CONTRACT_SCRAPER.md:615`.

### 8.2 Public Delivery (client → backend)
- `GET /liturgy/today?timezone=America/Sao_Paulo&locale=pt-BR` `openapi/evangelizae-v1.openapi.yml:9`, served from `Mongo liturgical_days` by timezone-derived date (`_id` lookup). No provider call. `DailyLiturgy` includes `source { provider, fetchedAt, freshness }` `openapi/evangelizae-v1.openapi.yml:72` — `freshness=LIVE` when date found, `503 LITURGY_UNAVAILABLE` `src/main/java/org/evangelizae/api/web/GlobalExceptionHandler.java:24` when missing (no `CACHED` fallback of wrong date `src/main/java/org/evangelizae/api/liturgy/service/LiturgyService.java:60` rule preserved).
- **Social (MongoDB):** `POST /social/follow`, `DELETE /social/follow/{userId}`, `POST /social/like`, `POST /social/comments`, `GET /social/feed` — all `Mongo`-backed, thin controllers: validate DTO → call use case → map response. No `Mongo` query in controller.

---

## 9. Ingestion Logic (Idempotency & Transaction) — MongoDB `ClientSession`

Per `CONTRACT_SCRAPER.md:392`, overlapping sliding windows `CONTRACT_SCRAPER.md:46` require strict idempotency via Mongo transaction:

```
@Transactional (Mongo ClientSession) // CONTRACT_SCRAPER.md:414 mapped to Mongo 4.2+ multi-doc
importBatch(req):
  session.startTransaction()
  for dayDto in req.days:
    incoming = primaryContentHash(dayDto.sources) // CNBB PRIMARY only CONTRACT_SCRAPER.md:159
    existing = liturgicalDayRepository.findByDateInSession(session, dayDto.date) // unique _id
    if empty:
      INSERT liturgical_days (document with embedded readings/sources)
      INSERT liturgical_day_versions (payload, contentHash, scrapedAt)
      created++
    else if existing.primaryContentHash == incoming:
      // Case B CONTRACT_SCRAPER.md:401 — NO-OP on content
      UPDATE liturgical_days.lastVerifiedAt = now() in session
      unchanged++
    else:
      // Case C CONTRACT_SCRAPER.md:405 — content changed
      REPLACE liturgical_days (full document) in session
      INSERT liturgical_day_versions (new contentHash, scrapedAt) in session
      updated++
  session.commitTransaction()
  return LiturgyImportResponse(UUID.randomUUID(), "SUCCESS", processed=days.size, created, updated, unchanged, rejected=0) // CONTRACT_SCRAPER.md:592
```

- `WARNING` (`VATICAN_READINGS_UNAVAILABLE`) `CONTRACT_SCRAPER.md:222` and `REVIEW_REQUIRED` (reference mismatch) still persisted `CONTRACT_SCRAPER.md:416` with `validation.status/warnings` stored.
- Severe failure (malformed JSON, missing required fields) aborts `ClientSession` and returns `400` or `422` `CONTRACT_SCRAPER.md:414`.

---

## 10. Security

- **Internal chain (`/internal/**`):** `BearerAuthFilter` validates `LITURGY_IMPORT_TOKEN` `CONTRACT_SCRAPER.md:610`. Stateless, no session, rate-limited separately. `401`/`403` `CONTRACT_SCRAPER.md:384`. Hits `Mongo liturgical_days` only.
- **Public chain (`/api/**`):** JWT access (short-lived) + refresh rotation (stored in `Mongo refresh_sessions`). `RefreshSession` tracks `tokenHash`; reuse detection revokes chain. `PasswordHasher` is a port (`BCrypt`). `GoogleIdentityProvider` port hides Google SDK. `OAuthLoginUseCase` links by verified email in `Mongo users`.
- **CORS:** `ApiConfiguration.java:18` allowlist from `APP_CORS_ALLOWED_ORIGINS` `src/main/resources/application.yml:33` / `.env.example:1`, `GET/OPTIONS` only `src/main/java/org/evangelizae/api/config/ApiConfiguration.java:25`.
- **Privacy:** `DeleteAccountUseCase` cascades across `Mongo` collections (`users`, `prayer_completions`, `follows`, `likes`, `comments`, `refresh_sessions`) in one `ClientSession` transaction. `users.deactivatedAt` soft-delete + job hard-deletes after grace period. Liturgy docs unaffected (no PII).

---

## 11. Cross-Cutting (shared/infrastructure)

- **Errors:** Single `GlobalExceptionHandler.java:15` mapping `LiturgyUnavailableException` → `503 LITURGY_UNAVAILABLE` `src/main/java/org/evangelizae/api/web/GlobalExceptionHandler.java:24`, `InvalidLiturgyRequestException` → `400 INVALID_REQUEST` `src/main/java/org/evangelizae/api/web/GlobalExceptionHandler.java:32`, plus `400/401/403/422` for import per `CONTRACT_SCRAPER.md:451`, `DuplicateKeyException` (Mongo unique `normalizedEmail`, `userId+localDate`, `followerId+followeeId`) → `409 Conflict`. Uniform `ApiError.java` `openapi/evangelizae-v1.openapi.yml:79`.
- **Rate limiting:** `Redis` `OncePerRequestFilter` for public endpoints, skipped for `/internal/**` (already token-gated).
- **Idempotency (client writes):** `Idempotency-Key` header filter for `POST /prayer/check-in`, `POST /social/*` etc., backed by `Mongo idempotency_keys` collection with TTL index on `expiresAt` (distinct from liturgy `contentHash` idempotency in `Mongo liturgical_days`).
- **Observability:** `MDC` `requestId`, `scraperVersion/schemaVersion/scrapedAt/importId` logged per import, Micrometer metrics `liturgy.import.{processed,created,updated,unchanged}` and `social.interactions.*`, Actuator `health/info` `src/main/resources/application.yml:13` with `mongo` + `redis` health. `Clock` bean `src/main/java/org/evangelizae/api/config/ApiConfiguration.java:13` injectable for time determinism `src/test/java/org/evangelizae/api/liturgy/service/LiturgyServiceTest.java:25`.
- **Config:** `AppProperties.java:8` prefix `app` `src/main/resources/application.yml:31`, env-driven `PORT` `src/main/resources/application.yml:8`, `LITURGY_IMPORT_TOKEN`, `LITURGY_IMPORT_URL` `CONTRACT_SCRAPER.md:608`, `SPRING_DATA_MONGODB_URI` only (no `SPRING_DATASOURCE_URL`). No secrets in repo.

---

## 12. Testing Strategy

- **domain:** Pure unit tests (liturgy color/type enums, `Reading` text⊕options invariant `CONTRACT_SCRAPER.md:145`, `Celebration` rules, `StreakCalculator`, `Email.normalize`, `Follow` invariants).
- **application:** Use-case tests with fake ports (import cases A/B/C `CONTRACT_SCRAPER.md:395` on fake `LiturgicalDayRepository`, concurrent `check-in` with fake `PrayerCompletionRepository`, duplicate `Idempotency-Key` same/different body, refresh reuse, Google linking, timezone edge `LiturgyServiceTest.java:29`).
- **adapters:** Testcontainers **MongoDB** for all stores — unique indexes (`date` for liturgy `_id`, `normalizedEmail`, `userId+localDate`, `followerId+followeeId`), TTL, `ClientSession` transaction for `Import` and `DeleteAccount`, `CACHED` same-date-only rule `src/main/java/org/evangelizae/api/liturgy/service/LiturgyService.java:60`.
- **api:** Controller + contract tests vs `openapi/evangelizae-v1.openapi.yml` and scraper example `CONTRACT_SCRAPER.md:254` (expect `200` `CONTRACT_SCRAPER.md:436` with `importId` `CONTRACT_SCRAPER.md:442`).

---

## 13. Deployment & Operation

- **Scraper:** Docker cron `0 3 * * 0` `CONTRACT_SCRAPER.md:53`, `SCRAPER_DAYS_AHEAD=14` `CONTRACT_SCRAPER.md:613`, `HTTP_TIMEOUT_SECONDS=30` `CONTRACT_SCRAPER.md:615`, `LITURGY_IMPORT_URL=http://backend/internal/v1/liturgy/import` `CONTRACT_SCRAPER.md:611`. Logs exit `0` on `200/201` `CONTRACT_SCRAPER.md:451`, `1` on `400/401/422`.
- **Backend:** `Dockerfile:1` multi-stage build, `server.port=${PORT:8080}` `src/main/resources/application.yml:8`, `context-path=/api/v1` `src/main/resources/application.yml:10`, graceful shutdown `src/main/resources/application.yml:11`. **Mongock** for `MongoDB` collections/indexes + schema validators. Single backup (Mongo Atlas PITR).
- **Migration from pull to push:** Provision `MongoDB` → create `liturgical_days` + `liturgical_day_versions` with `_id=date` unique → import endpoint → run scraper backfill (14d ahead) → switch `LiturgyController` to `Mongo` → deprecate `liturgy.provider.*` env `src/main/resources/application.yml:34` / `.env.example:2`.

---

## 14. Evolution

- Add ports only at external I/O. When a module needs independent scaling, extract its package to a service — `liturgy` and `social` already have collection-isolated boundaries. No premature `event bus`; add change-stream consumer (`liturgical_day_versions` as outbox, `Mongo` change streams for feed fan-out) only when async needed (e.g., email, push).
- OpenAPI remains canonical for public API `openapi/evangelizae-v1.openapi.yml:2`; `CONTRACT_SCRAPER.md:1` is canonical for ingestion.

---

## 15. Invariants Checklist (PR Gate)

- [ ] `domain` has no Spring/Mongo/JWT/Google/Redis imports?
- [ ] Controller is thin (validate → use case → map)?
- [ ] External I/O behind `ports/out` (all `Mongo*Repository` behind ports)?
- [ ] Persistence document ≠ domain entity + mapper (`UserDocument` vs `User`, `LiturgicalDayDocument` vs `LiturgicalDay`)? Embedded readings/sources, not separate tables?
- [ ] `liturgy` import is `ClientSession` transaction, `contentHash` diffed, `lastVerifiedAt` updated on unchanged? Unique `_id=date`?
- [ ] `Reading` respects `text XOR options`? Unique indexes for `userId+localDate`, `followerId+followeeId`?
- [ ] `Bearer` for `/internal/**`, `JWT` for `/api/**`?
- [ ] Tests cover concurrent, idempotency, timezone, reuse, contract? Testcontainers Mongo only?
