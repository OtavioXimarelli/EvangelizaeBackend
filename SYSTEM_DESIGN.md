# Evangelizae — System Design

> Companion to `ARCHITECTURE.md:1` (spine). This document thinks the system end-to-end: context, containers, components, data, APIs, NFRs, and operation.
> **MongoDB only** (all domains) + Redis. Original contract `CONTRACT_SCRAPER.md:38` PostgreSQL noted in `ARCHITECTURE.md:1`.

---

## 1. Context (C4 L1)

**Actors:** Mobile/Web Client, LiturgyScraper (Python cron) `CONTRACT_SCRAPER.md:1`, Admin/Moderator, Google OAuth, Email provider.

**System:** Evangelizae API — single deployable Modular Monolith `ARCHITECTURE.md:16` (Java 25/Spring Boot 4.1 `pom.xml:10` + MongoDB + Redis) serving:
- Public APIs (`/api/v1` `src/main/resources/application.yml:10` contract `openapi/evangelizae-v1.openapi.yml:1`) for liturgy, identity, prayer, social — all **MongoDB**.
- Internal API (`/internal/v1/liturgy/import` `CONTRACT_SCRAPER.md:88`) for scraper ingestion (**MongoDB** `liturgical_days`).

**External systems:** CNBB PRIMARY `CONTRACT_SCRAPER.md:12`, Vatican News VALIDATION `CONTRACT_SCRAPER.md:13` (only scraper talks to them), Google Identity, SMTP.

**Trust boundary:** `scraper --Bearer--> /internal/**` vs `client --JWT/CORS--> /api/**` `src/main/java/org/evangelizae/api/config/ApiConfiguration.java:18` — both hit **MongoDB**.

---

## 2. Containers (C4 L2)

```
[Client] --HTTPS--> [Load Balancer / Ingress]
                         |
            +------------+------------+
            |                         |
      [Backend: evangelizae-api]   [Scraper Job: liturgy-scraper]
       Spring Boot 8080              Python, cron 0 3 * * 0 CONTRACT_SCRAPER.md:53
       /api/v1, /internal/v1         fetches CNBB+Vatican, POST batch
       /actuator/health              LITURGY_IMPORT_URL CONTRACT_SCRAPER.md:611
            |                         |
       +----+----+                    |
       |         |                   |
   [MongoDB]  [Redis]                |
   liturgy    rate-limit             |
   identity   idempotency TTL        |
   prayer                            |
   social                            |
```

**Runtime:** Docker `Dockerfile:1`, `PORT=8080` `src/main/resources/application.yml:8`, `graceful shutdown` `src/main/resources/application.yml:11`. One backend replica initially; stateless (Clock `src/main/java/org/evangelizae/api/config/ApiConfiguration.java:13` injected, no `ConcurrentHashMap` `src/main/java/org/evangelizae/api/liturgy/service/LiturgyService.java:31` after Mongo migration).

**Config:** `AppProperties.java:8` (`app.cors` `src/main/resources/application.yml:31`, `app.liturgy.import.token`), env `LITURGY_IMPORT_TOKEN` `CONTRACT_SCRAPER.md:610`, `APP_CORS_ALLOWED_ORIGINS` `.env.example:1`, `SPRING_DATA_MONGODB_URI` (single URI — all domains), `REDIS_URL`, `JWT_SECRET`, `GOOGLE_CLIENT_ID`.

---

## 3. Requirements

**Functional:**
- F1 `GET /liturgy/today?timezone&locale=pt-BR` `openapi/evangelizae-v1.openapi.yml:9` — timezone-derived `LocalDate` `src/main/java/org/evangelizae/api/liturgy/service/LiturgyService.java:43`, `200` with `DailyLiturgy` from **MongoDB** `liturgical_days` or `503` `src/main/java/org/evangelizae/api/web/GlobalExceptionHandler.java:24`.
- F2 Scraper batch import `POST /internal/v1/liturgy/import` `CONTRACT_SCRAPER.md:88` with `LiturgyImportRequest` `CONTRACT_SCRAPER.md:99` (14d window `CONTRACT_SCRAPER.md:56`, `sourceHash/contentHash` `CONTRACT_SCRAPER.md:226`) → **MongoDB** `ClientSession` upsert `CONTRACT_SCRAPER.md:392` → `LiturgyImportResponse` `CONTRACT_SCRAPER.md:420`.
- F3 Identity: register/login/refresh (rotation+reuse detection)/OAuth Google/link/migrate guest/delete account — **MongoDB**.
- F4 Prayer: `POST /prayer/check-in` (one per `UserId+LocalDate`, idempotent), `GET /prayer/stats` — **MongoDB**.
- F5 Social: follow/like/comment/feed — flexible documents — **MongoDB**.
- F6 SpiritualPlans: create, one active/user — **MongoDB**.
- F7 Moderation: report, review — **MongoDB**.

**Non-functional:**
- Availability: target 99.9% (monolith + Mongo Atlas PITR). Single DB simplifies recovery.
- Latency: `p95 <150ms` public read (single doc `findById`), `<3s` batch import `CONTRACT_SCRAPER.md:464`, scraper timeout `30s` `CONTRACT_SCRAPER.md:615`.
- Consistency: Strong within Mongo multi-doc transactions (liturgy `ClientSession`, `DeleteAccount` across users/prayer/social). No cross-DB — no distributed commit.
- Scale (year 1): 50k MAU → Mongo shines: social `follows/likes/comments` variable schema, new `targetType` without migration, liturgy `365 docs/year` tiny.
- Security: Bearer for internal `401/403` `CONTRACT_SCRAPER.md:384`, JWT for public, CORS `ApiConfiguration.java:18`, GDPR deletion across collections, no PII in logs.
- Correctness: Never invent liturgy `README.md:5`, `WARNING/REVIEW_REQUIRED` persisted not discarded `CONTRACT_SCRAPER.md:416`.

---

## 4. High-Level Design

**Style:** Modular Monolith + Hexagonal `ARCHITECTURE.md:16` — **single store `MongoDB`** for all bounded contexts.

**Request flows:**
1. **Public liturgy read:** `Client → GET /liturgy/today → LiturgyController.java:12 → GetTodayLiturgyUseCase → LiturgicalDayRepository (Mongo).findByDate(zoneDate _id lookup) → 200/503`.
2. **Scraper import:** `Scraper → POST /internal/v1/liturgy/import (Bearer) → BearerAuthFilter → LiturgyImportController → ImportLiturgyBatchUseCase (ClientSession Mongo) → compare primary contentHash → INSERT/NO-OP/UPDATE+version → 200 {importId, created, updated, unchanged} CONTRACT_SCRAPER.md:424`.
3. **Auth:** `Client → POST /auth/login → LoginUserUseCase → UserRepository (Mongo) + PasswordHasher + TokenService → 200 {access, refresh} → refresh_sessions (Mongo)`.
4. **Prayer check-in:** `Client (Idempotency-Key) → POST /prayer/check-in → IdempotencyFilter (Mongo idempotency_keys TTL) → CheckInPrayerUseCase → PrayerCompletionRepository (Mongo).findByUserAndLocalDate → 201/200`.
5. **Social follow:** `Client → POST /social/follow → FollowUserUseCase → FollowRepository (Mongo) unique {followerId, followeeId}`.

---

## 5. Component Design (C4 L3 — Backend)

```
Backend (MongoDB only)
├── shared/infrastructure: GlobalExceptionHandler.java:15, ApiError.java, BearerAuthFilter, JwtAuthFilter, RateLimitFilter, IdempotencyFilter (Mongo TTL), RequestIdFilter, Micrometer
├── liturgy [MongoDB]: domain (LiturgicalDay, Reading text⊕options CONTRACT_SCRAPER.md:145), application (Import/GetToday via ClientSession), adapters/in (internal+public), adapters/out (MongoLiturgicalDayDocument)
├── identity [MongoDB]: domain (User, RefreshSession), application (Register/Login/Refresh/OAuth/Delete), adapters/out (MongoUserRepository)
├── prayer [MongoDB]: domain (PrayerCompletion, StreakCalculator), application, adapters/out (Mongo)
├── social [MongoDB]: domain (Follow, Like, Comment), application, adapters/out (Mongo)
├── spiritualplans [MongoDB], moderation [MongoDB]
└── app: EvangelizaeApiApplication.java:8 wiring (MongoConfig only)
```

Dependency rule `ARCHITECTURE.md:5`: `adapters → application → domain ← ports`, `domain` has no `Spring/Mongo`.

---

## 6. Data Design — MongoDB Only (Mongock)

### 6.1 Liturgy — `liturgical_days` + `liturgical_day_versions` — see `ARCHITECTURE.md:7.1` for full embedded readings/sources/validation.

### 6.2 Identity / Prayer / Social

```javascript
users: { _id: UUID, normalizedEmail: String /* unique */, username: String, passwordHash: String, googleSub: String /* unique sparse */, socialProfile: {bio, avatarUrl}, createdAt, deactivatedAt }
refresh_sessions: { _id: UUID, userId: UUID, tokenHash: String /* unique */, expiresAt, revokedAt }
prayer_completions: { _id: UUID, userId: UUID, localDate: Date, completedAt } // unique {userId:1, localDate:1}
follows: { _id: UUID, followerId: UUID, followeeId: UUID, createdAt } // unique {followerId:1, followeeId:1}
likes: { _id: UUID, userId: UUID, targetType: String, targetId: String, createdAt } // unique {userId:1, targetType:1, targetId:1}
comments: { _id: UUID, userId: UUID, targetType: String, targetId: String, text: String, createdAt } // index {targetType:1, targetId:1, createdAt:-1}
spiritual_plans: { _id: UUID, userId: UUID, status: String, createdAt } // partial unique {userId:1} where status=ACTIVE
idempotency_keys: { _id: String, userId: UUID, requestHash: String, responseStatus: Int, responseBody: Object, createdAt, expiresAt } // TTL on expiresAt
```

**Redis:** `rate-limit:{user|ip}:{endpoint}` counters, optional `liturgy:today:{date}` cache (Mongo read, short TTL, invalidated on import).

**Hashing:** `sourceHash = sha256(raw bytes)` `CONTRACT_SCRAPER.md:230`, `contentHash = sha256(canonical JSON sort_keys)` `CONTRACT_SCRAPER.md:236` on liturgy domain fields only `CONTRACT_SCRAPER.md:246`.

**Retention:** `liturgical_day_versions` forever (audit), `prayer_completions` per GDPR delete, `idempotency_keys` `24-72h` TTL.

---

## 7. API Design

**Public** `openapi/evangelizae-v1.openapi.yml:1` (`/api/v1` `src/main/resources/application.yml:10`):
- `GET /liturgy/today?timezone&locale` → `200 DailyLiturgy` `openapi/evangelizae-v1.openapi.yml:23` / `400` / `503` (Mongo `_id` lookup)
- `POST /auth/register|login|refresh|oauth/google`, `GET /users/me`, `DELETE /users/me` (Mongo)
- `POST /prayer/check-in` (header `Idempotency-Key`), `GET /prayer/stats?timezone` (Mongo)
- `POST /social/follow|like|comment`, `GET /social/feed` (Mongo)

**Internal** `CONTRACT_SCRAPER.md:80`:
- `POST /internal/v1/liturgy/import` → `200/201 LiturgyImportResponse` `CONTRACT_SCRAPER.md:424` / `400` / `401`/`403` `CONTRACT_SCRAPER.md:384` / `422` / `5xx` `CONTRACT_SCRAPER.md:451` (Mongo `ClientSession`)

All responses use `ApiError {timestamp,status,error,code,message,path}` `openapi/evangelizae-v1.openapi.yml:79` via `GlobalExceptionHandler.java:15`. Wire `camelCase` `CONTRACT_SCRAPER.md:91`, dates `YYYY-MM-DD` `CONTRACT_SCRAPER.md:92`, instants `...Z` `CONTRACT_SCRAPER.md:93`.

**Validation:** Jakarta `@NotBlank/@NotNull/@Valid` `CONTRACT_SCRAPER.md:486` + domain invariant `text XOR options` `CONTRACT_SCRAPER.md:145` + Mongo `validator` JSON schema → `409` on duplicate.

---

## 8. Integration Design (Scraper) — MongoDB

**Pipeline** `CONTRACT_SCRAPER.md:53`: `date range (today → +13d)` → loop `fetch CNBB → parse → fetch Vatican best-effort → parse → cross-validate → sourceHash/contentHash` → `LiturgyImportRequest` `CONTRACT_SCRAPER.md:99` with `period` `CONTRACT_SCRAPER.md:108` and `days[]` `CONTRACT_SCRAPER.md:114` → `POST` `LITURGY_IMPORT_URL` `CONTRACT_SCRAPER.md:611` with `Bearer` `LITURGY_IMPORT_TOKEN` `CONTRACT_SCRAPER.md:610` → exit `0` on `200/201`, `1` on `400/401/422` `CONTRACT_SCRAPER.md:451`.

**Idempotency:** Overlapping windows `CONTRACT_SCRAPER.md:46` handled by **Mongo** `contentHash` diff `CONTRACT_SCRAPER.md:392` — cases `created/unchanged(lastVerifiedAt)/updated+version` `CONTRACT_SCRAPER.md:395` within one `ClientSession`.

**Validation statuses** `CONTRACT_SCRAPER.md:217`: `VALID`, `WARNING` `CONTRACT_SCRAPER.md:222`, `REVIEW_REQUIRED` — all persisted.

**Scraper config:** `SCRAPER_DAYS_AHEAD=14` `CONTRACT_SCRAPER.md:613`, `HTTP_TIMEOUT_SECONDS=30` `CONTRACT_SCRAPER.md:615`, `LOG_LEVEL=INFO` `CONTRACT_SCRAPER.md:616`.

---

## 9. Security & Privacy

- Single filter chain: `/internal/**` (Bearer) vs `/api/**` (JWT+CORS) — both hit **MongoDB**.
- JWT: access `15m`, refresh `30d` rotation, `token_hash` in **Mongo** `refresh_sessions`, reuse detection → revoke chain.
- Passwords: `BCrypt` via `PasswordHasher` port.
- Google OAuth: `GoogleIdentityProvider` port hides SDK; link by verified email only (`users.googleSub`).
- CORS: `ApiConfiguration.java:18` allowlist `APP_CORS_ALLOWED_ORIGINS` `.env.example:1`, `GET/OPTIONS` only `src/main/java/org/evangelizae/api/config/ApiConfiguration.java:25`.
- GDPR: `DeleteAccountUseCase` single **Mongo** `ClientSession` across all collections; `deactivatedAt` soft-delete.

---

## 10. Observability & Ops

- **Actuator:** `health/info` `src/main/resources/application.yml:13` with probes `src/main/resources/application.yml:18` — `mongo` + `redis`.
- **Logging:** `MDC requestId`, `importId/scraperVersion/schemaVersion/period` per import.
- **Metrics:** `http_server_requests`, `liturgy.import.{processed,created,updated,unchanged}` (Mongo), `social.*`, `prayer.checkin.conflict`.
- **Alerting:** Scraper `5xx`, `REVIEW_REQUIRED` count, import latency `>3s`.

---

## 11. Failure Modes

| Failure | Behavior |
|---------|----------|
| Scraper CNBB down | No batch; last window still serves reads from Mongo. Alert. |
| Vatican down | Batch `status=WARNING` `CONTRACT_SCRAPER.md:222`, persisted. |
| MongoDB down | All `503` (liturgy + auth/social), import `503` scraper retries `CONTRACT_SCRAPER.md:451`. Single blip, single recovery. |
| Hash mismatch / invalid payload | `400/422` abort `ClientSession` `CONTRACT_SCRAPER.md:414`, scraper exits `1`. |
| Duplicate import (same contentHash) | `unchanged` + `lastVerifiedAt` bump. |
| Token invalid | `401/403` `CONTRACT_SCRAPER.md:384`, scraper exits `1`. |

---

## 12. Capacity & Scaling

Year-1 (50k MAU): all Mongo. Liturgy `365 docs/year` ~18MB; users/social dominate. Single `MongoDB Atlas M10` + `Redis cache.t3.micro` + backend `512 MB`. Scale via `userId` sharding for feeds when needed. No `PostgreSQL` vertical scaling.

---

## 13. Evolution Path

1. Mongock → `liturgical_days` (`_id=date` unique) + `liturgical_day_versions` → import endpoint `ClientSession` → switch `LiturgyController` to `Mongo` → delete `HttpLiturgyProvider.java:14`.
2. Next: identity + prayer + social on **same Mongo** per `ARCHITECTURE.md:6`.
3. Later: if needed, extract `liturgy` or `social` collections to dedicated service — already collection-isolated.

---

## 14. Open Decisions

- `liturgical_days` validator strictness (`text XOR options` as JSON schema vs app-level).
- `MongoDB` Atlas vs self-hosted — recommend Atlas PITR.
- Feed fan-out: `Mongo` change streams + `Redis` vs async job — out of scope initially.
