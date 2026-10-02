# Evangelizae Liturgy Import — Technical Reference

## 1. Purpose

This document describes the backend implementation that receives liturgical batches via the import endpoint, validates and normalizes them, stores one current document per date in MongoDB, and exposes the stable public response consumed by the Next.js frontend.

The implementation follows a feature-first Controller-Service-Repository MVC structure.

The broader delivery plan remains in `LITURGY_INTEGRATION_PLAN.md`.

## 2. Runtime Architecture

```text
External Caller
    |
    | POST /internal/v1/liturgy/import
    | Authorization: Bearer <LITURGY_IMPORT_TOKEN>
    | Content-Type: application/json
    v
InternalBearerAuthFilter
    |
    v
LiturgyImportController
    |
    v
LiturgyService.importBatch()
    |
    v
LiturgicalDayRepository
    |
    v
MongoDB collection: liturgical_days
    |
    v
LiturgyController
    |
    | GET /api/v1/liturgy/today
    v
LiturgyService.getToday()
    |
    v
DailyLiturgy JSON for the frontend
```

The import caller is external. It does not run inside the Spring Boot JVM.

## 3. Source File Inventory

### 3.1 Application and shared infrastructure

| File | Responsibility |
|---|---|
| `src/main/java/org/evangelizae/api/EvangelizaeApiApplication.java` | Spring Boot entry point. Enables configuration properties scanning. |
| `src/main/java/org/evangelizae/api/config/ApiConfiguration.java` | Creates the UTC `Clock` bean and configures public CORS for `GET` and `OPTIONS`. |
| `src/main/java/org/evangelizae/api/config/AppProperties.java` | Typed and validated application configuration, including CORS origins and the import token. |
| `src/main/java/org/evangelizae/api/config/SecurityConfig.java` | Stateless Spring Security filter chain. Public routes remain accessible; internal import authentication is performed by a servlet filter. |
| `src/main/java/org/evangelizae/api/config/InternalBearerAuthFilter.java` | Protects every `/internal/**` request with a static bearer token. |
| `src/main/java/org/evangelizae/api/web/RequestIdFilter.java` | Accepts or generates `X-Request-Id`, adds it to the response, and places it in the logging MDC. |
| `src/main/java/org/evangelizae/api/web/ApiError.java` | Shared JSON error representation. |
| `src/main/java/org/evangelizae/api/web/GlobalExceptionHandler.java` | Maps liturgy and import exceptions to HTTP status codes. |
| `src/main/java/org/evangelizae/api/web/HealthController.java` | Public liveness endpoint at `GET /api/v1/health`. |

### 3.2 Liturgy controllers

| File | Responsibility |
|---|---|
| `src/main/java/org/evangelizae/api/liturgy/controller/LiturgyController.java` | Exposes the public `GET /api/v1/liturgy/today` endpoint. |
| `src/main/java/org/evangelizae/api/liturgy/controller/LiturgyImportController.java` | Exposes the internal `POST /internal/v1/liturgy/import` endpoint. |

The public and internal surfaces use separate controllers so browser traffic and worker ingestion have different security and validation boundaries.

### 3.3 Liturgy service

| File | Responsibility |
|---|---|
| `src/main/java/org/evangelizae/api/liturgy/service/LiturgyService.java` | Contains public read logic, import validation, scraper normalization, reading grouping, persistence orchestration, and public response mapping. |
| `src/main/java/org/evangelizae/api/liturgy/service/InvalidLiturgyRequestException.java` | Invalid public query parameters. Produces `400 INVALID_REQUEST`. |
| `src/main/java/org/evangelizae/api/liturgy/service/InvalidLiturgyImportException.java` | Invalid import content. Produces `422 INVALID_LITURGY_IMPORT`. |
| `src/main/java/org/evangelizae/api/liturgy/service/LiturgyUnavailableException.java` | Missing or unusable public liturgy data. Produces `503 LITURGY_UNAVAILABLE`. |

The service is intentionally the only orchestration layer. A separate import service is not introduced at the current project size.

### 3.4 Liturgy import and public models

| File | Responsibility |
|---|---|
| `LiturgyImportRequest.java` | Typed, validated representation of the scraper payload, including nested periods, days, celebrations, seasons, readings, sources, and validation results. |
| `LiturgyImportResponse.java` | Import counters, generated import ID, status, and future failure details. |
| `DailyLiturgy.java` | Public response consumed by the frontend. |
| `LiturgyGroup.java` | Public reading group. |
| `LiturgyReading.java` | Public reading item. |
| `LiturgyPrayers.java` | Public prayers object. Currently imported as an empty object. |
| `LiturgySource.java` | Public provider, fetch timestamp, and freshness state. |
| `ReadingKind.java` | Public kinds: `FIRST_READING`, `PSALM`, `SECOND_READING`, `GOSPEL`, `EXTRA`. |
| `LiturgicalColor.java` | Public and imported liturgical colors. |

All these files are under:

```text
src/main/java/org/evangelizae/api/liturgy/model/
```

### 3.5 Liturgy persistence

| File | Responsibility |
|---|---|
| `LiturgicalDayRepository.java` | Repository abstraction used by the service. Provides date lookup and save operations. |
| `LiturgicalDayMongoRepository.java` | Spring Data MongoDB implementation. |
| `LiturgicalDayDocument.java` | MongoDB document stored in the `liturgical_days` collection. |

All three files are under:

```text
src/main/java/org/evangelizae/api/liturgy/repository/
```

### 3.6 Configuration, contracts, and deployment files

| File | Responsibility |
|---|---|
| `src/main/resources/application.yml` | Base runtime configuration, MongoDB, port, actuator, CORS binding, and import token binding. |
| `src/main/resources/application-dev.yml` | Local MongoDB and development defaults. |
| `src/main/resources/application-prod.yml` | Enables framework-level forwarded-header handling behind the Coolify proxy. |
| `src/test/resources/application.yml` | Test-only MongoDB URI and import token. |
| `openapi/evangelizae-v1.openapi.yml` | Public and internal HTTP contract. |
| `.env.example` | Production environment variable template. |
| `docker-compose.yml` | Local MongoDB and API development environment. Redis was removed because it had no runtime use. |
| `Dockerfile` | Multi-stage production image used by Coolify. |
| `README.md` | Runtime, endpoint, and production configuration instructions. |
| `LITURGY_INTEGRATION_PLAN.md` | Full integration and rollout plan. |

### 3.7 Tests

| File | Coverage |
|---|---|
| `LiturgyControllerTest.java` | Public status, validation, health, and actuator routing behavior. |
| `LiturgyImportControllerTest.java` | Import authentication, valid import, malformed body, and invalid content responses. |
| `LiturgyServiceTest.java` | Date-keyed creation, existing document handling, alternative readings, duplicate dates, hash validation, and public grouping. |

All test files are under:

```text
src/test/java/org/evangelizae/api/liturgy/
```

## 4. Import Data Flow

### 4.1 Worker request

The caller sends a camel-case JSON batch:

```http
POST /internal/v1/liturgy/import
Authorization: Bearer <LITURGY_IMPORT_TOKEN>
Content-Type: application/json
Accept: application/json
```

The batch envelope contains:

```json
{
  "schemaVersion": "1.0",
  "scrapedAt": "2026-09-24T01:00:00Z",
  "period": {
    "from": "2026-09-24",
    "to": "2026-09-24"
  },
  "days": []
}
```

### 4.2 Request ID propagation

`RequestIdFilter` runs before the authentication filter.

It:

1. Reads `X-Request-Id` when supplied.
2. Generates a UUID when the header is missing or invalid.
3. Adds the value to the response header.
4. Places the value in the MDC for logging.

### 4.3 Internal authentication

`InternalBearerAuthFilter` checks only requests under `/internal/`.

Behavior:

| Condition | Response |
|---|---|
| No `Authorization` header | `401 UNAUTHORIZED` |
| Header is not `Bearer <value>` | `401 UNAUTHORIZED` |
| Bearer value is empty | `401 UNAUTHORIZED` |
| Bearer value does not match the configured token | `403 FORBIDDEN` |
| Bearer value matches | Request continues to the controller |

Token comparison uses `MessageDigest.isEqual` to avoid a simple string comparison timing leak.

The token is loaded from `app.liturgy.import-config.token` and must contain at least 32 characters.

### 4.4 JSON and Bean Validation

`LiturgyImportController` applies `@Valid` to the request body.

`LiturgyImportRequest` rejects:

- Missing or blank schema version.
- Missing timestamps or periods.
- Empty day lists.
- Invalid or duplicate enum values.
- Missing celebrations, seasons, readings, sources, or validation status.
- Empty reading lists.
- Missing reading types.
- Missing source collection timestamps.
- Negative source comparison counts.

Malformed JSON or Bean Validation failures return `400 INVALID_IMPORT`.

### 4.5 Import domain validation

`LiturgyService.importBatch()` validates the whole batch before writing anything.

It rejects batches when:

- `schemaVersion` is not `1.0`.
- The period start is after the period end.
- The same date appears more than once.
- A day is outside the declared period.
- There is not exactly one `PRIMARY` source.
- The primary source is not `CNBB`.
- The primary source has no `contentHash`.
- The primary content hash is not `sha256:` followed by 64 lowercase hexadecimal characters.
- A reading has no text and no alternatives.
- A reading has both text and alternatives.
- Nested alternative readings are supplied.

Domain validation failures return `422 INVALID_LITURGY_IMPORT`.

### 4.6 Reading normalization

The import uses `ReadingType` values:

```text
FIRST_READING
SECOND_READING
PSALM
GOSPEL
ACCLAMATION
SEQUENCE
```

The public API uses:

```text
FIRST_READING
PSALM
SECOND_READING
GOSPEL
EXTRA
```

Normalization rules:

| Import reading | Public result |
|---|---|
| `FIRST_READING` | `FIRST_READING` item |
| `PSALM` | `PSALM` item |
| `SECOND_READING` | `SECOND_READING` item |
| `GOSPEL` | `GOSPEL` item |
| `ACCLAMATION` | `EXTRA` item |
| `SEQUENCE` | `EXTRA` item |
| Reading with `options` | Each option becomes an `EXTRA` item |

Reading titles are resolved in this order:

1. Reading title.
2. Reading reference.
3. Parent reading title for alternatives.
4. Reading type as a technical fallback.

Psalm responses are not duplicated into the public `refrain` field. The complete imported text remains in `text`.

### 4.7 Persistence

The MongoDB document ID is the ISO date:

```text
2026-09-24
```

This makes each date the natural unique key and makes a repeated import safe.

For every imported day, the service:

1. Looks up the date.
2. Builds a new `LiturgicalDayDocument`.
3. Preserves the original `createdAt` when the date already exists.
4. Updates `updatedAt` and `lastVerifiedAt`.
5. Saves the document through `LiturgicalDayRepository`.

No version history collection is created in the current implementation.

### 4.8 Import response

A successful import returns:

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

Current behavior always writes the received days, so `unchanged` is currently always `0`. The `PARTIAL` status and failure list are reserved for a later partial-write strategy.

## 5. MongoDB Document

Collection:

```text
liturgical_days
```

Example stored shape:

```json
{
  "_id": "2026-09-24",
  "date": "2026-09-24",
  "title": "Quarta-feira da 25ª Semana do Tempo Comum",
  "color": "GREEN",
  "readings": [
    {
      "kind": "FIRST_READING",
      "title": "Primeira leitura",
      "reference": "1Cor 2,10b-16",
      "text": "...",
      "refrain": null
    }
  ],
  "celebrationType": "WEEKDAY",
  "season": {
    "name": "Tempo Comum",
    "week": 25,
    "liturgicalYear": "A"
  },
  "note": null,
  "sources": [],
  "validation": {
    "status": "VALID",
    "sourcesCompared": 2,
    "warnings": []
  },
  "scrapedAt": "2026-09-24T01:00:00Z",
  "primaryContentHash": "sha256:...",
  "provider": "CNBB",
  "fetchedAt": "2026-09-24T01:00:00Z",
  "createdAt": "2026-09-24T02:00:00Z",
  "updatedAt": "2026-09-25T02:00:00Z",
  "lastVerifiedAt": "2026-09-25T02:00:00Z"
}
```

## 6. Public Read Data Flow

### 6.1 Request

The frontend calls:

```http
GET /api/v1/liturgy/today?timezone=America/Sao_Paulo&locale=pt-BR
Accept: application/json
```

`NEXT_PUBLIC_API_BASE_URL` in the frontend must include `/api/v1`.

### 6.2 Date calculation

`LiturgyService.getToday()`:

1. Validates the IANA timezone.
2. Requires `locale=pt-BR`.
3. Uses the injected UTC `Clock`.
4. Calculates the observer-local date in the requested timezone.
5. Queries MongoDB by that date.

This ensures date boundaries match the observer location rather than the server location.

### 6.3 Public mapping

Stored readings are grouped by their stored kind.

The mapper returns groups in enum order:

1. `FIRST_READING`
2. `PSALM`
3. `SECOND_READING`
4. `GOSPEL`
5. `EXTRA`

Source mapping:

| Stored field | Public field |
|---|---|
| `provider` | `source.provider` |
| `fetchedAt` | `source.fetchedAt` |
| Imported data | `source.freshness: LIVE` |

Imported documents return `CNBB` as the provider. Older documents without a fetch timestamp fall back to their update or creation timestamp.

### 6.4 Public response

```json
{
  "date": "2026-09-24",
  "title": "Quarta-feira da 25ª Semana do Tempo Comum",
  "color": "GREEN",
  "prayers": {},
  "groups": [
    {
      "kind": "FIRST_READING",
      "items": [
        {
          "title": "Primeira leitura",
          "reference": "1Cor 2,10b-16",
          "text": "..."
        }
      ]
    },
    {
      "kind": "GOSPEL",
      "items": [
        {
          "title": "Evangelho",
          "reference": "Mt 11,11-15",
          "text": "..."
        }
      ]
    }
  ],
  "source": {
    "provider": "CNBB",
    "fetchedAt": "2026-09-24T01:00:00Z",
    "freshness": "LIVE"
  }
}
```

Missing data returns:

```text
503 LITURGY_UNAVAILABLE
```

## 7. Error Contract

| Situation | HTTP status | Error code |
|---|---:|---|
| Public timezone or locale invalid | `400` | `INVALID_REQUEST` |
| Malformed import JSON or Bean Validation failure | `400` | `INVALID_IMPORT` |
| Internal bearer token missing or malformed | `401` | `UNAUTHORIZED` |
| Internal bearer token incorrect | `403` | `FORBIDDEN` |
| Import liturgical content invalid | `422` | `INVALID_LITURGY_IMPORT` |
| Public liturgy not available | `503` | `LITURGY_UNAVAILABLE` |

Error body:

```json
{
  "timestamp": "2026-09-24T02:00:00Z",
  "status": 422,
  "error": "Unprocessable Content",
  "code": "INVALID_LITURGY_IMPORT",
  "message": "Exactly one primary source is required for 2026-09-24",
  "path": "/internal/v1/liturgy/import"
}
```

## 8. Runtime Configuration

Required production variables:

```text
SPRING_PROFILES_ACTIVE=prod
PORT=8080
APP_CORS_ALLOWED_ORIGINS=https://evangelizae.com
MONGODB_URI=<production MongoDB URI>
LITURGY_IMPORT_TOKEN=<random value with at least 32 characters>
```

Caller variable:

```text
LITURGY_IMPORT_URL=https://api.evangelizae.com/internal/v1/liturgy/import
```

The backend does not require Redis. The local `docker-compose.yml` also runs without Redis.

## 9. Build and Deployment

Build:

```bash
mise exec -- ./mvnw clean package
```

Production image:

```bash
docker build -t evangelizae-api .
```

Coolify flow:

1. Coolify tracks the `main` branch.
2. A push to `main` starts a Dockerfile build.
3. The application environment variables are injected by Coolify.
4. The container starts with `SPRING_PROFILES_ACTIVE=prod`.
5. `application-prod.yml` enables forwarded-header handling for the Coolify proxy.

The release commit containing this implementation is:

```text
5ea78f8 refactor: remove scraper dependency, keep only the import endpoint
```

## 10. Test and Verification Coverage

The current backend test suite contains 18 tests.

It verifies:

- Public `503` behavior.
- Public locale validation.
- Public health and actuator routing.
- Missing internal bearer token.
- Invalid internal bearer token.
- Valid internal import controller flow.
- Malformed import payload.
- Invalid import content.
- Date-keyed document creation.
- Source and hash persistence.
- Alternative reading normalization.
- Duplicate date rejection.
- Content hash validation.
- Public reading group ordering.
- Stable provider and fetch timestamp mapping.

Verification commands:

```bash
mise exec -- ./mvnw clean package
docker compose config --quiet
ruby -e "require 'yaml'; YAML.load_file('openapi/evangelizae-v1.openapi.yml')"
git diff --check
```

## 11. Deliberate Design Limits

The current implementation intentionally does not include:

- Import version history.
- MongoDB transactions.
- Import queues.
- In-process scheduling.
- Distributed locking.
- A generated frontend API client.
- Frontend authentication.
- Redis caching.
- Distributed rate limiting.
- Automatic reconciliation of legacy documents that predate date-keyed IDs.
- Partial-write recovery.

These should be added only when operational or product requirements justify them.

## 12. Architecture Cleanup Performed

The following old structures were removed during the MVC refactor:

- `liturgy/application`
- `liturgy/domain`
- `liturgy/ports`
- `liturgy/adapters`
- `LiturgicalDayPersistenceAdapter`
- The disconnected `HttpLiturgyProvider` subsystem
- Unused Redis dependencies and configuration
- Unused rate-limit packages and properties
- Empty future feature package shells

The feature now has a direct path:

```text
Controller -> Service -> Repository -> MongoDB
```
