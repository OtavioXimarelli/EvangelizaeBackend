# Evangelizae Liturgy Integration Plan

## 1. Status

This plan defines the initial integration of the Python `LiturgyScraper` worker with the Evangelizae Spring Boot API and the compatibility requirements for the Next.js frontend.

The frontend repository is reference-only for this plan. No frontend MVC restructuring is included.

Decisions already selected:

- Keep the scraper as a separate one-shot process scheduled outside the API.
- Keep only one current MongoDB document per liturgical date.
- Use a static bearer token to protect the internal import endpoint.
- Simplify the backend to feature-first Controller-Service-Repository MVC.
- Use the existing public `DailyLiturgy` response for the frontend.
- Keep detailed scraper fields internal to Python and the backend.
- Use `evangelizae.com` and `api.evangelizae.com` as the canonical production domains.

### Implementation status

- Feature-first Controller-Service-Repository MVC refactor: completed in the current working tree.
- Persistence adapter and ports/adapters package structure: removed.
- Disconnected HTTP provider subsystem: removed.
- Unused Redis and rate-limit configuration: removed.
- Public reading grouping and stable stored timestamp mapping: corrected.
- `POST /internal/v1/liturgy/import`: implemented with typed validation, date-keyed replacement, and internal bearer authentication.
- Public OpenAPI contract: updated with the internal import endpoint.

## 2. Current State

### Backend

The Spring Boot backend currently exposes:

- `GET /api/v1/liturgy/today`
- `GET /api/v1/health`
- Actuator endpoints

The current liturgy read path is:

```text
LiturgyController
    -> LiturgyService
    -> LiturgicalDayRepository
    -> LiturgicalDayMongoRepository
    -> MongoDB
```

The internal import path is now implemented:

- `POST /internal/v1/liturgy/import`
- Typed import request and response records
- Batch and liturgical-content validation
- Date-keyed MongoDB replacement
- Internal bearer authentication
- Scraper reading normalization

Remaining integration work:

- Worker scheduling and deployment composition
- Scraper response validation and retry behavior
- Existing-data reconciliation for documents that predate date-keyed IDs
- Real MongoDB integration verification

Current architecture issues:

- Existing documents that predate date-keyed IDs require reconciliation.
- Historical architecture documents still contain sections that are not implemented.
- The Python worker is not yet packaged or scheduled with the backend.

### LiturgyScraper

`LiturgyScraper` is currently a standalone Python batch application. It:

- Scrapes CNBB as the primary source.
- Scrapes Vatican News for validation.
- Parses and validates multiple reading days.
- Computes content hashes.
- Sends a camel-case batch to the backend.

Its production target is already defined as:

```text
POST /internal/v1/liturgy/import
Authorization: Bearer <LITURGY_IMPORT_TOKEN>
Content-Type: application/json
```

The scraper is currently an untracked nested repository with its own Docker and cron configuration.

### Frontend reference

The frontend repository is located at:

```text
/home/otavio/Work/Evangelizae
```

It is a Next.js 16, React 19, TypeScript, Zustand, Vitest, and Playwright application. Its only current backend request is:

```text
GET {NEXT_PUBLIC_API_BASE_URL}/liturgy/today
    ?timezone=America/Sao_Paulo
    &locale=pt-BR
```

The frontend expects the public `DailyLiturgy` contract defined in:

- Backend: `openapi/evangelizae-v1.openapi.yml`
- Frontend mirror: `contracts/evangelizae-v1.openapi.yaml`
- Frontend runtime types: `src/services/liturgyService.ts`

The frontend includes a temporary embedded liturgy fallback. It must remain until the API and scraper have passed seven consecutive production days.

## 3. Goals

- Import normalized liturgy data from the scraper into MongoDB.
- Make repeated imports safe through date-keyed replacement.
- Preserve enough provenance to identify the source and verification state.
- Serve a stable, frontend-compatible public response.
- Keep the scraper independently deployable and schedulable.
- Protect the internal import route without introducing user authentication.
- Simplify the backend to a conventional feature-first MVC structure.
- Remove unused or speculative infrastructure from the active architecture.
- Keep the public backend contract and frontend contract synchronized.

## 4. Non-Goals

The initial integration will not include:

- Running Python inside the Spring Boot JVM.
- An in-process scheduler.
- Kafka, RabbitMQ, or another message queue.
- Import version history.
- MongoDB transactions.
- A generic repository framework.
- CQRS commands or queries.
- A frontend BFF or API proxy.
- Browser authentication for the public liturgy endpoint.
- A generated frontend API client.
- React Query or SWR.
- Multi-region import coordination.
- A microservice split.

These may be reconsidered only after measured requirements justify them.

## 5. Target Architecture

```text
External scheduler
    |
    v
LiturgyScraper one-shot container
    |
    | POST /internal/v1/liturgy/import
    | Authorization: Bearer token
    | X-Request-Id: request UUID
    v
LiturgyImportController
    |
    v
LiturgyService
    |
    v
LiturgicalDayRepository
    |
    v
MongoDB liturgical_days collection
    |
    v
LiturgyController
    |
    v
Frontend GET /api/v1/liturgy/today
```

The scraper and API remain separate deployment units. The API owns persistence and public response mapping. The scraper owns source acquisition, parsing, source comparison, and source hashes.

## 6. Backend MVC Architecture

### 6.1 Feature-first structure

The backend will use feature-first Controller-Service-Repository MVC.

```text
src/main/java/org/evangelizae/api/
├── EvangelizaeApiApplication.java
├── config/
│   ├── ApiConfiguration.java
│   ├── AppProperties.java
│   └── SecurityConfig.java
├── web/
│   ├── ApiError.java
│   ├── GlobalExceptionHandler.java
│   ├── HealthController.java
│   └── RequestIdFilter.java
└── liturgy/
    ├── controller/
    │   ├── LiturgyController.java
    │   └── LiturgyImportController.java
    ├── service/
    │   └── LiturgyService.java
    ├── repository/
    │   ├── LiturgicalDayDocument.java
    │   ├── LiturgicalDayRepository.java
    │   └── LiturgicalDayMongoRepository.java
    └── model/
        ├── DailyLiturgy.java
        ├── LiturgyImportRequest.java
        ├── LiturgyImportResponse.java
        └── existing public and import records
```

Future business features should follow the same structure:

```text
feature/
├── controller/
├── service/
├── repository/
└── model/
```

Cross-cutting technical concerns remain in `config` and `web`.

### 6.2 Removed package conventions

Remove the following unused package structures:

```text
liturgy/application/
liturgy/domain/
liturgy/ports/
liturgy/adapters/
```

Also remove:

- `LiturgicalDayPersistenceAdapter`
- The disconnected `LiturgyProvider` hierarchy
- Empty future-module package shells
- Redis and rate-limit dependencies that have no active use case
- Provider configuration that is no longer used

### 6.3 MVC responsibilities

#### Controllers

Controllers are responsible for:

- HTTP path and method selection
- Request body and query parsing
- Bean Validation integration
- HTTP status selection
- Response serialization

Controllers must not:

- Access MongoDB repositories directly
- Implement scraper normalization
- Build reading groups
- Calculate liturgical dates
- Handle persistence errors

Keep separate public and internal controllers:

- `LiturgyController`: public read API
- `LiturgyImportController`: protected internal import API

#### Service

`LiturgyService` is responsible for:

- Observer-local date calculation
- Reading import validation
- Primary source selection
- Scraper-to-public model normalization
- Reading grouping and ordering
- Upsert orchestration
- Public read mapping
- Business-level error decisions

`LiturgyImportService` will not be introduced initially. Split the service later only if it becomes large or difficult to test.

#### Repository

The repository layer is responsible for:

- MongoDB date lookup
- Date-keyed create and replacement
- Document identity
- Persistence operations

The repository must not:

- Perform frontend response mapping
- Apply scraper business rules
- Expose source collection logic
- Contain HTTP concerns

Use the ISO date as MongoDB `_id`, for example:

```text
2026-09-24
```

This provides natural uniqueness without requiring a separate migration engine.

#### Models

Model records are responsible for representing:

- Public API data
- Internal import data
- MongoDB documents
- Enums and validation constraints

Models must not orchestrate database or HTTP behavior.

## 7. Internal Import API

### 7.1 Endpoint

```text
POST /internal/v1/liturgy/import
Authorization: Bearer <LITURGY_IMPORT_TOKEN>
Content-Type: application/json
Accept: application/json
X-Request-Id: <UUID>
```

The endpoint is server-to-server only. It is not a frontend API and must not be enabled through browser CORS.

### 7.2 Request validation

Validate the complete request before writing any document.

The service must reject requests when:

- `schemaVersion` is not `1.0`
- `days` is empty
- A day is outside the declared period
- A date appears more than once
- A celebration or color is invalid
- A reading type is invalid
- Exactly one `PRIMARY` CNBB source is not present
- The primary source has no valid `contentHash`
- A timestamp is invalid
- A reading cannot be represented safely by the public contract

### 7.3 Import response

Recommended response:

```json
{
  "importId": "uuid",
  "status": "SUCCESS",
  "received": 14,
  "processed": 14,
  "created": 8,
  "updated": 6,
  "unchanged": 0,
  "failed": []
}
```

Use `PARTIAL` if only some dates were written. The scraper must treat `PARTIAL` as a failed run and retry the batch.

Recommended status codes:

| Status | Meaning |
|---|---|
| `200` | Batch accepted and fully processed |
| `400` | Malformed JSON or invalid envelope/schema |
| `401` | Missing or malformed bearer token |
| `403` | Invalid bearer token |
| `422` | Invalid liturgical content |
| `500` | Unexpected server failure |
| `503` | MongoDB unavailable |

`importId` is used for backend and scraper logging. It is not exposed through the frontend API.

## 8. Persistence Model

### 8.1 Current-document strategy

Store one current document per date:

```text
_id: "2026-09-24"
date: "2026-09-24"
```

Do not create a version collection in the initial implementation.

A repeated import is safe because the service replaces the same date-keyed document. The service must preserve `createdAt` and update `updatedAt`.

### 8.2 Internal stored data

The document should preserve the useful scraper data:

- Date
- Celebration name and type
- Liturgical color
- Liturgical season
- Readings and alternatives
- Note
- Sources
- Validation status and warnings
- Primary content hash
- Source collection time
- Scraper version
- Import time
- Last verification time

Scraper schema, validation, and source hashes remain internal. They are not returned directly to the frontend.

### 8.3 Existing data migration

Before rollout:

- Inspect existing `liturgical_days` documents.
- Reconcile documents that have arbitrary string IDs.
- Convert them to ISO date IDs or recreate the collection if the dataset is disposable.
- Verify no duplicate liturgical dates exist.
- Run a read check against the public API after conversion.

Do not add Mongock or another migration framework for this single early-stage collection change unless a broader migration requirement emerges.

## 9. Public Frontend Contract

### 9.1 Endpoint

Keep the existing public endpoint:

```text
GET /api/v1/liturgy/today
    ?timezone=America/Sao_Paulo
    &locale=pt-BR
```

Production public base URL:

```text
https://api.evangelizae.com/api/v1
```

The endpoint is called directly by the browser. No frontend proxy is required.

### 9.2 Public response

```text
date
title
color
prayers
groups[]
source.provider
source.fetchedAt
source.freshness
```

The public contract remains the frontend's current `DailyLiturgy` shape.

### 9.3 Import-to-public mapping

| Scraper field | Public field |
|---|---|
| `day.date` | `date` |
| `celebration.name` | `title` |
| `celebration.liturgicalColor` | `color` |
| grouped normalized readings | `groups` |
| no scraper prayers field | `prayers: {}` |
| primary source name | `source.provider` |
| primary source `collectedAt` | `source.fetchedAt` |
| delivery state | `source.freshness: LIVE` |

Do not expose:

- Content hashes
- Source hashes
- Scraper version
- Validation warnings
- Source URLs
- `EMBEDDED`
- Internal import identifiers

### 9.4 Reading normalization

Public reading kinds remain:

```text
FIRST_READING
PSALM
SECOND_READING
GOSPEL
EXTRA
```

Normalize scraper types as follows:

| Scraper type | Public kind |
|---|---|
| `FIRST_READING` | `FIRST_READING` |
| `PSALM` | `PSALM` |
| `SECOND_READING` | `SECOND_READING` |
| `GOSPEL` | `GOSPEL` |
| `ACCLAMATION` | `EXTRA` |
| `SEQUENCE` | `EXTRA` |

Canonical group order:

1. `FIRST_READING`
2. `PSALM`
3. `SECOND_READING`
4. `GOSPEL`
5. `EXTRA`

Alternative readings represented by `options` become separate `EXTRA.items` entries.

Every public item must have:

- Nonblank `title`
- Nonblank `text`
- Optional `reference`
- Optional `refrain`

If an alternative cannot satisfy those rules, reject the import day rather than silently dropping liturgical content.

For Psalms, preserve the full text in `text` and omit `refrain` initially. The scraper may include the response inside the text, and exposing both could duplicate it in the frontend.

### 9.5 Source and timestamp semantics

Use:

```text
source.provider = "CNBB"
source.fetchedAt = primary CNBB collectedAt
source.freshness = "LIVE"
```

Do not return:

- `mongodb` as the content provider
- `Instant.now()` as the source fetch time
- A new timestamp on every GET

`CACHED` remains a frontend-local state for its same-day browser cache. The backend can continue documenting `LIVE | CACHED`, but it should not claim a backend cache exists until one is implemented.

`EMBEDDED` remains frontend-only for the temporary fallback.

## 10. Security

Protect only the internal import path with `LITURGY_IMPORT_TOKEN`.

Requirements:

- Public GET routes remain accessible according to the current product decision.
- `/internal/**` requires a bearer token.
- Missing or malformed credentials return `401`.
- Wrong credentials return `403`.
- The token must never be logged.
- The token must not be included in frontend environment variables.
- Browser CORS must continue allowing only public `GET` and `OPTIONS` methods.
- Production network controls should prevent public access to internal routes when the deployment platform supports path or network restrictions.

A static internal token is sufficient for the initial deployment. Consider mTLS or signed service identities only when the deployment model requires stronger service authentication.

## 11. Scraper Plan

### 11.1 Runtime

Keep the scraper as a separate one-shot Python process.

Recommended repository location:

```text
workers/liturgy-scraper/
```

The scraper and backend contract should be versioned together so a checkout can reproduce the integration.

### 11.2 Scheduling

Use an external scheduler:

- Host cron
- systemd timer
- Coolify scheduled job
- Platform CronJob

Do not keep a long-running Python process merely to execute a scheduled job.

A daily schedule is preferred because it reduces the time required to detect and correct upstream changes. Fourteen days of lookahead remains sufficient for the initial integration.

Container URL example:

```text
LITURGY_IMPORT_URL=http://api:8080/internal/v1/liturgy/import
```

Do not use `localhost` from inside a scraper container.

### 11.3 Scraper changes

Add only the reliability needed for integration:

- Calculate the default start date in `America/Sao_Paulo`.
- Generate or accept a stable run ID.
- Send it as `X-Request-Id`.
- Validate the backend response structure.
- Treat `PARTIAL` as failure.
- Fail when an expected date is missing.
- Retry backend `429` and `5xx` responses.
- Retry backend timeouts and transport failures.
- Report which dates failed scraping.
- Preserve the existing fixture-based parser tests.

Do not add parallel scraping, queues, or a worker framework until runtime measurements justify them.

## 12. Backend Simplification

### 12.1 Remove immediately

- Disconnected provider classes and configuration.
- Unused Redis dependency and Compose service.
- Unused rate-limit properties and empty packages.
- Empty future feature package shells.
- Current persistence adapter and ports-based package structure.
- Stale provider/cache architecture claims.
- Duplicate architecture documents after one canonical plan is established.
- Duplicate or conflicting production domain references.

### 12.2 Retain

- Spring MVC
- Spring Data MongoDB
- Validation starter
- Actuator and Prometheus
- Constructor injection
- Immutable Java records
- Injected UTC `Clock`
- MongoDB as the current data store
- Request ID propagation
- Public CORS allowlist
- Typed configuration validation

### 12.3 Defer

- Public user authentication.
- Redis caching.
- Distributed rate limiting.
- Generic audit infrastructure.
- Database migration frameworks.
- CQRS.
- Event sourcing.
- Multi-document transactions.
- Generated API clients until a second consuming service exists.

## 13. Frontend Compatibility Work

The frontend repository remains reference-only, but backend delivery must account for its current expectations.

Backend changes that must remain compatible:

- `NEXT_PUBLIC_API_BASE_URL` includes `/api/v1`.
- Query parameters remain `timezone` and `locale`.
- Locale remains `pt-BR`.
- `prayers` is always an object.
- `groups` is non-empty.
- Public `title`, `text`, `provider`, `fetchedAt`, and `freshness` remain present.
- Unknown optional reading fields are omitted according to the current API behavior.
- The backend returns valid CORS headers for `https://evangelizae.com`.

The backend OpenAPI file is canonical. Synchronize the frontend mirror when the public contract changes.

Do not add frontend architecture changes as part of this backend plan.

Keep the embedded frontend fallback until the stated seven-day production gate has passed.

## 14. Deployment Configuration

Canonical production values:

```text
Frontend origin:
https://evangelizae.com

Public API base:
https://api.evangelizae.com/api/v1

Backend CORS allowed origin:
https://evangelizae.com

Internal scraper URL:
https://api.evangelizae.com/internal/v1/liturgy/import
```

The frontend API URL is a Next.js public build-time value. Changing it requires rebuilding the frontend image.

The scraper import URL is a runtime worker setting. It may use the internal service name when both containers share a Docker network.

Required secrets must be supplied through deployment secrets or environment injection and must not be committed.

## 15. Implementation Phases

### Phase 1: Establish the backend structure

1. Create the feature-first MVC packages.
2. Move existing liturgy classes into controller, service, repository, and model packages.
3. Remove the persistence adapter.
4. Keep one `LiturgyService` initially.
5. Repair the current test application context.
6. Run backend tests, lint, and type checks available for the Java project.

### Phase 2: Implement persistence and import

1. Add import DTOs and validation.
2. Add date-keyed MongoDB documents.
3. Add repository create and replacement operations.
4. Add internal import service logic.
5. Add bearer authentication.
6. Add import response counters and errors.
7. Add repeated-import and retry tests.

### Phase 3: Correct public mapping

1. Replace the collapsed reading mapping.
2. Preserve each reading kind.
3. Normalize alternatives into public items.
4. Map acclamations and sequences to `EXTRA`.
5. Return `CNBB` and stable source collection time.
6. Return `{}` for unavailable prayers.
7. Add malformed and empty persisted-record tests.

### Phase 4: Synchronize contracts

1. Update the backend OpenAPI document.
2. Synchronize the frontend OpenAPI mirror.
3. Document internal import behavior separately.
4. Add backend response fixture tests.
5. Confirm frontend runtime validation remains compatible.

### Phase 5: Harden and package the scraper

1. Add São Paulo date calculation.
2. Add request and run IDs.
3. Add response validation.
4. Add backend retry behavior.
5. Add partial-batch failure behavior.
6. Package the scraper inside the backend repository.
7. Add the scheduled one-shot container configuration.

### Phase 6: End-to-end verification

1. Run the scraper in dry-run mode.
2. Import a reviewed fixture into MongoDB.
3. Verify documents use ISO date IDs.
4. Re-import the same fixture.
5. Confirm no duplicate dates are created.
6. Request the public API.
7. Confirm the frontend runtime validator accepts the response.
8. Run the frontend production build with the canonical API URL.
9. Run the frontend against the API.
10. Exercise CORS from the canonical frontend origin.

### Phase 7: Production rollout

1. Deploy MongoDB and the simplified API.
2. Deploy the import route and authentication.
3. Run a manual scraper import.
4. Compare output with reviewed frontend fallback content.
5. Enable the frontend API base URL.
6. Monitor daily imports.
7. Keep the embedded fallback for seven consecutive days.
8. Remove the fallback only after backend approval.

## 16. Test Plan

### Backend

- Public GET success with multiple reading groups.
- Timezone and date-boundary behavior.
- Invalid timezone and locale.
- Missing document returning `503`.
- Stable `fetchedAt` across repeated reads.
- Correct CNBB provider mapping.
- Import schema validation.
- Import authentication.
- Duplicate date rejection.
- Primary source and hash validation.
- Date-keyed replacement.
- Repeated import without duplicate documents.
- Alternative reading normalization.
- Acclamation and sequence normalization.
- Rejection of incomplete public reading items.
- Internal error status mapping.

### Scraper

- Existing parser fixtures.
- São Paulo date selection.
- Backend request headers.
- Response schema validation.
- Retry and timeout behavior.
- Partial and missing date failure.
- Request ID propagation.
- Dry-run behavior without backend credentials.

### Frontend compatibility reference

- Existing service tests continue to pass.
- Public response fixture with multiple groups is accepted.
- `CNBB` provider is accepted.
- Stable `fetchedAt` is accepted.
- `400` and `503` handling remains functional.
- Embedded fallback still works.
- Production API base URL is present during build.

### End-to-end

```text
Scraper dry run
    -> Scraper import
    -> API authentication
    -> MongoDB replacement
    -> Public API response
    -> Frontend rendering
```

## 17. Acceptance Criteria

The initial integration is complete when:

- The external scheduler can run the scraper successfully.
- The scraper reaches the API through an internal service URL.
- Invalid import tokens are rejected.
- Valid batches create or replace one document per date.
- Repeated imports do not create duplicate dates.
- Import failures are visible to the scraper and retried safely.
- The public API returns one group per reading kind.
- Alternatives are safely represented as public items.
- The frontend accepts the public response without a new API client.
- `source.provider` is `CNBB`.
- `source.fetchedAt` remains stable across reads.
- CORS allows only the configured frontend origin.
- The temporary embedded fallback remains available during the observation period.
- Unused provider, Redis, rate-limit, and aspirational package scaffolding is removed.
- Backend documentation describes the implemented architecture rather than the previous target state.

## 18. Deferred Decisions

The following require product or operational evidence and are intentionally deferred:

- Liturgical version history and audit requirements.
- MongoDB transactions.
- Distributed locking.
- Import queues.
- Administrative import replay.
- Liturgical content review workflows.
- Additional frontend languages.
- Public user accounts.
- Personal prayer synchronization.
- Advanced caching.
- Distributed rate limiting.
