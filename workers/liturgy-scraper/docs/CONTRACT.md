# LiturgyScraper ⇄ Backend Integration Contract

This document defines the complete technical contract between the **LiturgyScraper** service (Python) and the **Core Backend Application** (Java / Spring Boot + PostgreSQL).

---

## 1. System Overview & Purpose

### 1.1 What the Application Does
**LiturgyScraper** is an autonomous, stateless data ingestion service responsible for scraping, parsing, normalizing, validating, and cryptographically hashing the **Roman Catholic Daily Liturgy (Liturgia Diária)** used in Brazil.

- **Primary Source (`PRIMARY`)**: **CNBB / Edições CNBB — Igreja em Oração** ([https://liturgiadiaria.edicoescnbb.com.br/](https://liturgiadiaria.edicoescnbb.com.br/)), the official liturgical translation approved for Brazil.
- **Secondary Source (`VALIDATION`)**: **Vatican News — Palavra do Dia** ([https://www.vaticannews.va/pt/palavra-do-dia/](https://www.vaticannews.va/pt/palavra-do-dia/)), used for cross-source reference verification and divergence detection.

### 1.2 Architectural Boundaries & Division of Responsibilities

```text
┌──────────────────────────────────────────────────────────┐
│              LiturgyScraper (Python Job)                 │
│  - Fetches CNBB API + Vatican News HTML                  │
│  - Parses & normalizes HTML into canonical structures    │
│  - Cross-validates biblical references                   │
│  - Calculates sourceHash & contentHash (SHA-256)         │
│  - Serializes atomic JSON batch (LiturgyImportRequest)   │
│  - Stateless: runs via cron/Docker, exits immediately    │
└────────────────────────────┬─────────────────────────────┘
                             │
                             │ POST /internal/v1/liturgy/import
                             │ Authorization: Bearer <token>
                             ▼
┌──────────────────────────────────────────────────────────┐
│           Core Backend (Java / Spring Boot)              │
│  - Authenticates Bearer token                            │
│  - Validates batch schema & constraints                  │
│  - Executes transactional upsert by calendar date        │
│  - Detects changes via contentHash comparison            │
│  - Manages database primary keys & relational integrity  │
│  - Persists data & version history in PostgreSQL         │
│  - Serves public/client-facing APIs                      │
└──────────────────────────────────────────────────────────┘
```

#### Key Architecture Principles
1. **Stateless Scraper**: The Python scraper does **not** maintain a persistent database, does not hold state between runs, and has **no direct access to PostgreSQL**.
2. **Database Isolation**: The Python scraper has no knowledge of internal database IDs (UUIDs, sequence IDs) or internal database schemas.
3. **Idempotent Batch Delivery**: The scraper sends a sliding collection window (typically **14 days ahead**) in a single atomic HTTP POST request.
4. **Primary Source Authority**: CNBB is the source of truth for Brazilian liturgy. Vatican News is strictly a validation aid. A failure or discrepancy in Vatican News generates a warning/flag but does not prevent valid CNBB data from being ingested.

---

## 2. Operational Pipeline (How the App Works)

When triggered (e.g., via weekly cron at `0 3 * * 0`), the scraper executes the following sequential pipeline:

```text
1. Date Range Generation (start = today, end = today + 13 days)
       │
       ▼
2. Per-Day Processing Loop:
   ├── a. Fetch CNBB API JSON/HTML
   ├── b. Parse celebration, liturgical season, readings, and notes
   ├── c. Fetch Vatican News HTML (best-effort)
   ├── d. Parse Vatican News readings
   ├── e. Cross-validate biblical references (CNBB vs Vatican)
   ├── f. Compute sourceHash (raw HTML payload SHA-256)
   └── g. Compute contentHash (canonical normalized JSON SHA-256)
       │
       ▼
3. Batch Envelope Construction (LiturgyImportRequest)
       │
       ▼
4. HTTP POST Transmission (POST /internal/v1/liturgy/import)
       │
       ▼
5. Result Logging & Process Termination
```

---

## 3. Integration API Specification

### 3.1 HTTP Transport & Endpoint

| Parameter | Value |
|---|---|
| **HTTP Method** | `POST` |
| **Path** | `/internal/v1/liturgy/import` |
| **Content-Type** | `application/json; charset=UTF-8` |
| **Accept** | `application/json` |
| **Authentication** | `Authorization: Bearer <LITURGY_IMPORT_TOKEN>` |
| **Payload Wire Format** | JSON with `camelCase` keys (`exclude_none=true`) |
| **Dates Format** | ISO-8601 Date (`YYYY-MM-DD`, e.g., `2026-08-24`) |
| **Timestamps Format** | ISO-8601 UTC Instant (`YYYY-MM-DD'T'HH:mm:ss'Z'`, e.g., `2026-08-23T03:15:42Z`) |

---

### 3.2 Request Data Schema (`LiturgyImportRequest`)

#### Root Envelope: `LiturgyImportRequest`
| Field | Type | Required | Description |
|---|---|---|---|
| `schemaVersion` | `string` | Yes | Version of the payload schema (currently `"1.0"`). |
| `scraperVersion` | `string` | Yes | Version of the scraper application (e.g., `"0.1.0"`). |
| `scrapedAt` | `string (ISO-8601 UTC)` | Yes | UTC timestamp when the scraping batch was assembled. |
| `period` | `Period` | Yes | Date range covered by this batch. |
| `days` | `list<LiturgicalDay>` | Yes | Array of daily liturgical data items. |

#### Sub-Model: `Period`
| Field | Type | Required | Description |
|---|---|---|---|
| `from` | `string (date)` | Yes | Start date inclusive (`YYYY-MM-DD`). |
| `to` | `string (date)` | Yes | End date inclusive (`YYYY-MM-DD`). |

#### Sub-Model: `LiturgicalDay`
| Field | Type | Required | Description |
|---|---|---|---|
| `date` | `string (date)` | Yes | Calendar date of the liturgy (`YYYY-MM-DD`). Unique domain key. |
| `celebration` | `Celebration` | Yes | Title, liturgical color, and celebration rank. |
| `liturgicalSeason` | `LiturgicalSeason` | Yes | Liturgical season, week number, and cycle year. |
| `parts` | `LiturgicalParts` | Yes | Container holding liturgical components (readings, etc.). |
| `sources` | `list<SourceInfo>` | Yes | Provenance and cryptographic hashes for each queried source. |
| `validation` | `Validation` | Yes | Cross-source comparison result and validation status. |
| `note` | `string \| null` | No | Rubric notes (e.g., celebration displacement or feast omissions). |

#### Sub-Model: `Celebration`
| Field | Type | Required | Description |
|---|---|---|---|
| `name` | `string` | Yes | Official celebration title (e.g. `"São Bartolomeu, Apóstolo"`). |
| `type` | `CelebrationType` (Enum) | Yes | Rank of celebration: `WEEKDAY`, `SUNDAY`, `MEMORIAL`, `FEAST`, `SOLEMNITY`. |
| `liturgicalColor` | `LiturgicalColor` (Enum) | Yes | Liturgical color: `GREEN`, `RED`, `WHITE`, `PURPLE`, `ROSE`. |

#### Sub-Model: `LiturgicalSeason`
| Field | Type | Required | Description |
|---|---|---|---|
| `name` | `string` | Yes | Liturgical season name (e.g., `"Tempo Comum"`, `"Quaresma"`, `"Advento"`, `"Tempo Pascal"`, `"Tempo do Natal"`). |
| `week` | `integer \| null` | No | Week number in the season (e.g., `21`). |
| `liturgicalYear` | `string \| null` | No | Liturgical year cycle (e.g., `"A"`, `"B"`, `"C"`). |

#### Sub-Model: `LiturgicalParts`
| Field | Type | Required | Description |
|---|---|---|---|
| `readings` | `list<Reading>` | Yes | Ordered list of readings and chants for the day. |

#### Sub-Model: `Reading`
> **Invariant**: A reading has either `text` (standard single reading) or `options` (alternative reading choices), **never both**.
| Field | Type | Required | Description |
|---|---|---|---|
| `type` | `ReadingType` (Enum) | Yes | `FIRST_READING`, `SECOND_READING`, `PSALM`, `GOSPEL`, `ACCLAMATION`, `SEQUENCE`. |
| `reference` | `string \| null` | No | Biblical reference (e.g. `"Ap 21,9b-14"`, `"Sl 144(145)"`, `"Jo 1,45-51"`). |
| `title` | `string \| null` | No | Pericope title / proclamation header. |
| `response` | `string \| null` | No | Responsorial psalm refrain (e.g. `"R. O Senhor é bondoso e compassivo."`). |
| `text` | `string \| null` | No | Full normalized reading text. Null if `options` is used. |
| `options` | `list<Reading> \| null` | No | Alternative reading options (all options share the parent's `type`). |

#### Sub-Model: `SourceInfo`
| Field | Type | Required | Description |
|---|---|---|---|
| `name` | `SourceName` (Enum) | Yes | `CNBB` or `VATICAN_NEWS`. |
| `role` | `SourceRole` (Enum) | Yes | `PRIMARY` (CNBB) or `VALIDATION` (Vatican News). |
| `url` | `string \| null` | No | Specific URL or endpoint queried. |
| `collectedAt` | `string (ISO-8601 UTC)` | Yes | UTC timestamp when this source was fetched. |
| `sourceHash` | `string \| null` | No | SHA-256 hex digest of raw HTTP response (`sha256:<hex>`). |
| `contentHash` | `string \| null` | No | SHA-256 hex digest of canonical parsed JSON (`sha256:<hex>`). |

#### Sub-Model: `Validation`
| Field | Type | Required | Description |
|---|---|---|---|
| `status` | `ValidationStatus` (Enum) | Yes | `VALID`, `WARNING`, or `REVIEW_REQUIRED`. |
| `sourcesCompared` | `integer` | Yes | Count of sources compared (`1` or `2`). |
| `warnings` | `list<string>` | Yes | Array of warning codes/details (empty `[]` if valid). |

---

### 3.3 Enumerations Reference

```typescript
// Celebration rank
enum CelebrationType {
  WEEKDAY = "WEEKDAY",      // Dia de semana (ferial)
  SUNDAY = "SUNDAY",        // Domingo
  MEMORIAL = "MEMORIAL",    // Memória
  FEAST = "FEAST",          // Festa
  SOLEMNITY = "SOLEMNITY"   // Solenidade
}

// Liturgical vestment / day color
enum LiturgicalColor {
  GREEN = "GREEN",          // Verde
  RED = "RED",              // Vermelho
  WHITE = "WHITE",          // Branco
  PURPLE = "PURPLE",        // Roxo
  ROSE = "ROSE"             // Rosa
}

// Liturgical text classifications
enum ReadingType {
  FIRST_READING = "FIRST_READING",
  SECOND_READING = "SECOND_READING",
  PSALM = "PSALM",
  GOSPEL = "GOSPEL",
  ACCLAMATION = "ACCLAMATION",
  SEQUENCE = "SEQUENCE"
}

// Source identifiers and roles
enum SourceName {
  CNBB = "CNBB",
  VATICAN_NEWS = "VATICAN_NEWS"
}

enum SourceRole {
  PRIMARY = "PRIMARY",
  VALIDATION = "VALIDATION"
}

// Cross-validation status
enum ValidationStatus {
  VALID = "VALID",                      // Both sources matched or verified
  WARNING = "WARNING",                  // Vatican News unavailable, CNBB valid
  REVIEW_REQUIRED = "REVIEW_REQUIRED"   // Biblical reference mismatch detected
}
```

---

## 4. Cryptographic Hashing & Idempotency Rules

Two distinct SHA-256 hashes are calculated for each day:

### 4.1 `sourceHash` (Raw Payload Hash)
- **Calculation**: `SHA-256(raw_source_response_bytes)`
- **Format**: `sha256:<64-hex-characters>`
- **Purpose**: Auditing and detecting web page layout / API response mutations on the remote source.

### 4.2 `contentHash` (Canonical Domain Content Hash)
- **Calculation**: `SHA-256(canonical_json_string)`
- **Canonical JSON formatting**:
  ```python
  json.dumps(
      canonical_data,
      ensure_ascii=False,
      sort_keys=True,
      separators=(",", ":")
  )
  ```
- **Included fields**: ONLY domain data (`date`, `celebration`, `liturgicalSeason`, `parts`, `note`).
- **Excluded fields**: `scrapedAt`, `collectedAt`, `url`, `scraperVersion`, `schemaVersion`, HTTP headers.
- **Purpose**: Deterministic change detection. The `contentHash` remains identical regardless of when the scraper runs, as long as the liturgical text and metadata have not changed.

---

## 5. Example Batch Payload (Request)

```json
{
  "schemaVersion": "1.0",
  "scraperVersion": "0.1.0",
  "scrapedAt": "2026-08-23T03:15:42Z",
  "period": {
    "from": "2026-08-24",
    "to": "2026-08-25"
  },
  "days": [
    {
      "date": "2026-08-24",
      "celebration": {
        "name": "São Bartolomeu, Apóstolo",
        "type": "FEAST",
        "liturgicalColor": "RED"
      },
      "liturgicalSeason": {
        "name": "Tempo Comum",
        "week": 21
      },
      "parts": {
        "readings": [
          {
            "type": "FIRST_READING",
            "reference": "Ap 21,9b-14",
            "title": "Leitura do Livro do Apocalipse de São João",
            "text": "Um anjo falou comigo e disse: «Vem! Vou mostrar-te a noiva, a esposa do Cordeiro»..."
          },
          {
            "type": "PSALM",
            "reference": "Sl 144(145),10-11.12-13ab.17-18 (R. 12)",
            "response": "R. Anunciai entre as nações as grandes obras do Senhor!",
            "text": "Que vossas obras, ó Senhor, vos glorifiquem, e os vossos santos com louvores vos bendigam!..."
          },
          {
            "type": "GOSPEL",
            "reference": "Jo 1,45-51",
            "title": "Proclamação do Evangelho de Jesus Cristo segundo João",
            "text": "Naquele tempo, Filipe encontrou Natanael e disse-lhe: «Encontramos aquele de quem Moisés escreveu...»"
          }
        ]
      },
      "sources": [
        {
          "name": "CNBB",
          "role": "PRIMARY",
          "url": "https://api-liturgia.edicoescnbb.com.br/v2/liturgias/2026-08-24",
          "collectedAt": "2026-08-23T03:15:20Z",
          "sourceHash": "sha256:4a5f6e8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f",
          "contentHash": "sha256:8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c"
        },
        {
          "name": "VATICAN_NEWS",
          "role": "VALIDATION",
          "url": "https://www.vaticannews.va/pt/palavra-do-dia/2026/08/24.html",
          "collectedAt": "2026-08-23T03:15:30Z",
          "sourceHash": "sha256:1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b",
          "contentHash": "sha256:2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c"
        }
      ],
      "validation": {
        "status": "VALID",
        "sourcesCompared": 2,
        "warnings": []
      }
    },
    {
      "date": "2026-08-25",
      "celebration": {
        "name": "21ª Terça-feira do Tempo Comum",
        "type": "WEEKDAY",
        "liturgicalColor": "GREEN"
      },
      "liturgicalSeason": {
        "name": "Tempo Comum",
        "week": 21
      },
      "parts": {
        "readings": [
          {
            "type": "FIRST_READING",
            "reference": "2Ts 2,1-3a.14-17",
            "title": "Leitura da Segunda Carta de São Paulo aos Tessalonicenses",
            "text": "Irmãos, no que se refere à vinda de nosso Senhor Jesus Cristo..."
          },
          {
            "type": "PSALM",
            "reference": "Sl 95(96),10.11-12a.12b-13 (R. 13b)",
            "response": "R. O Senhor vem julgar a terra com justiça.",
            "text": "Dizei aos pagãos: «O Senhor é Rei!». O mundo permanece firme, não se abala..."
          },
          {
            "type": "GOSPEL",
            "reference": "Mt 23,23-26",
            "title": "Proclamação do Evangelho de Jesus Cristo segundo Mateus",
            "text": "Naquele tempo, disse Jesus: «Ai de vós, mestres da Lei e fariseus hipócritas!...»"
          }
        ]
      },
      "sources": [
        {
          "name": "CNBB",
          "role": "PRIMARY",
          "url": "https://api-liturgia.edicoescnbb.com.br/v2/liturgias/2026-08-25",
          "collectedAt": "2026-08-23T03:15:35Z",
          "sourceHash": "sha256:3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d",
          "contentHash": "sha256:9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1f0e9d8c7b6a5f4e3d2c1b0a9f8e"
        }
      ],
      "validation": {
        "status": "WARNING",
        "sourcesCompared": 1,
        "warnings": ["VATICAN_READINGS_UNAVAILABLE"]
      },
      "note": "Hoje, omite-se a Memória de São Luís da França."
    }
  ]
}
```

---

## 6. What the Application Needs from the Backend

To ensure smooth, resilient integration, the Core Backend application must fulfill the following operational and architectural requirements:

### 6.1 Authentication & Endpoint Availability
1. **Internal Endpoint**: Expose an HTTP endpoint matching `POST /internal/v1/liturgy/import`.
2. **Bearer Token Authentication**: Enforce validation of the `Authorization: Bearer <token>` header against a configured secret token (`LITURGY_IMPORT_TOKEN`).
3. **Rejection Responses**:
   - `401 Unauthorized`: Missing or malformed Authorization header.
   - `403 Forbidden`: Invalid / expired token.

---

### 6.2 Idempotency & Upsert Handling

The scraper sends overlapping sliding windows (e.g. 14 days every week). The backend **must be strictly idempotent**:

```text
For each LiturgicalDay in batch.days:
  1. Find existing record by calendar date (date = LiturgicalDay.date).
  2. Compare incoming primary contentHash with stored current contentHash:
     - Case A: Date does not exist in database
       -> INSERT new day record and initial version record
       -> Increment 'created' counter
     - Case B: Date exists AND stored contentHash == incoming contentHash
       -> NO-OP / UNCHANGED (do not modify liturgical content)
       -> Update last_verified_at timestamp
       -> Increment 'unchanged' counter
     - Case C: Date exists AND stored contentHash != incoming contentHash
       -> UPDATE active day record with new content
       -> INSERT new version record into history table with incoming contentHash and scrapedAt
       -> Increment 'updated' counter
```

---

### 6.3 Transactional Guarantees
- The import batch should be processed within a **database transaction** (`@Transactional` in Spring Boot).
- If validation fails for severe reasons, the transaction rolls back and returns a detailed `400 Bad Request` or `422 Unprocessable Entity`.
- For non-critical day-level warnings (e.g. `ValidationStatus.WARNING` or `REVIEW_REQUIRED`), the backend **should accept and persist** the day, storing the validation status and warnings for administrative review.

---

### 6.4 Expected Backend Response Contract

Upon processing the batch, the backend must return a JSON response object with HTTP status `200 OK` (or `201 Created`):

#### Response Schema
| Field | Type | Description |
|---|---|---|
| `importId` | `string` | Unique identifier generated for the import job (UUID or ULID). |
| `status` | `string` | Import execution status: `"SUCCESS"`, `"PARTIAL"`, or `"FAILED"`. |
| `processed` | `integer` | Total number of days evaluated in the batch. |
| `created` | `integer` | Number of new liturgical days inserted into the database. |
| `updated` | `integer` | Number of existing liturgical days updated (content changed). |
| `unchanged` | `integer` | Number of days whose `contentHash` was already up to date. |
| `rejected` | `integer` | Number of days rejected due to domain errors (optional, defaults to `0`). |

#### Example Response Body
```json
{
  "importId": "01J5X98Z1V2B3N4M5K6L7P8Q9R",
  "status": "SUCCESS",
  "processed": 14,
  "created": 10,
  "updated": 2,
  "unchanged": 2,
  "rejected": 0
}
```

---

### 6.5 HTTP Status Codes Expected

| Status Code | Meaning | Scraper Behavior |
|---|---|---|
| `200 OK` / `201 Created` | Batch processed successfully. | Scraper logs summary and exits with code `0`. |
| `400 Bad Request` | Malformed JSON or schema violation. | Scraper logs error, does not retry, exits with code `1`. |
| `401 Unauthorized` | Missing / invalid Bearer token. | Scraper logs configuration error, exits with code `1`. |
| `422 Unprocessable Entity` | Domain validation failure in payload. | Scraper logs validation details, exits with code `1`. |
| `500 / 502 / 503 / 504` | Backend server error / timeout. | Scraper retries (if retry configured) or logs failure and alerts. |

---

### 6.6 Performance & SLA Expectations
- **Batch Size**: 14 to 30 days per payload (typically ~50 KB to ~150 KB JSON).
- **Processing Time**: Backend should process and commit the batch in **< 3 seconds** under normal load.
- **Client Timeout**: Default HTTP client timeout is configured to **30 seconds** (`HTTP_TIMEOUT_SECONDS=30`).

---

## 7. Java / Spring Boot Implementation Reference

For the backend engineering team, below is the recommended Java 21+ record DTO structure and service skeleton matching this contract:

### 7.1 Java Record DTOs
```java
package br.com.app.liturgy.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record LiturgyImportRequest(
    @NotBlank String schemaVersion,
    @NotBlank String scraperVersion,
    @NotNull Instant scrapedAt,
    @NotNull @Valid Period period,
    @NotEmpty @Valid List<LiturgicalDayImport> days
) {
    public record Period(
        @JsonProperty("from") @NotNull LocalDate from,
        @JsonProperty("to") @NotNull LocalDate to
    ) {}

    public record LiturgicalDayImport(
        @NotNull LocalDate date,
        @NotNull @Valid CelebrationImport celebration,
        @NotNull @Valid LiturgicalSeasonImport liturgicalSeason,
        @NotNull @Valid LiturgicalPartsImport parts,
        @NotEmpty @Valid List<SourceInfoImport> sources,
        @NotNull @Valid ValidationImport validation,
        String note
    ) {}

    public record CelebrationImport(
        @NotBlank String name,
        @NotNull CelebrationType type,
        @NotNull LiturgicalColor liturgicalColor
    ) {}

    public record LiturgicalSeasonImport(
        @NotBlank String name,
        Integer week,
        String liturgicalYear
    ) {}

    public record LiturgicalPartsImport(
        @NotEmpty @Valid List<ReadingImport> readings
    ) {}

    public record ReadingImport(
        @NotNull ReadingType type,
        String reference,
        String title,
        String response,
        String text,
        List<ReadingImport> options
    ) {}

    public record SourceInfoImport(
        @NotNull SourceName name,
        @NotNull SourceRole role,
        String url,
        @NotNull Instant collectedAt,
        String sourceHash,
        String contentHash
    ) {}

    public record ValidationImport(
        @NotNull ValidationStatus status,
        int sourcesCompared,
        List<String> warnings
    ) {}

    public enum CelebrationType { WEEKDAY, SUNDAY, MEMORIAL, FEAST, SOLEMNITY }
    public enum LiturgicalColor { GREEN, RED, WHITE, PURPLE, ROSE }
    public enum ReadingType { FIRST_READING, SECOND_READING, PSALM, GOSPEL, ACCLAMATION, SEQUENCE }
    public enum SourceName { CNBB, VATICAN_NEWS }
    public enum SourceRole { PRIMARY, VALIDATION }
    public enum ValidationStatus { VALID, WARNING, REVIEW_REQUIRED }
}
```

### 7.2 Spring Boot Service Skeleton
```java
package br.com.app.liturgy.application.service;

import br.com.app.liturgy.adapter.in.web.dto.LiturgyImportRequest;
import br.com.app.liturgy.adapter.in.web.dto.LiturgyImportResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LiturgyImportService {

    @Transactional
    public LiturgyImportResponse importBatch(LiturgyImportRequest request) {
        int created = 0;
        int updated = 0;
        int unchanged = 0;

        for (var dayDto : request.days()) {
            var existingOpt = repository.findByDate(dayDto.date());
            String incomingContentHash = findPrimaryContentHash(dayDto.sources());

            if (existingOpt.isEmpty()) {
                persistNewDay(dayDto, incomingContentHash, request.scrapedAt());
                created++;
            } else {
                var existing = existingOpt.get();
                if (existing.getContentHash().equals(incomingContentHash)) {
                    unchanged++;
                } else {
                    updateExistingDay(existing, dayDto, incomingContentHash, request.scrapedAt());
                    updated++;
                }
            }
        }

        return new LiturgyImportResponse(
            UUID.randomUUID().toString(),
            "SUCCESS",
            request.days().size(),
            created,
            updated,
            unchanged,
            0
        );
    }
}
```

---

## 8. Environment & Configuration Reference

| Environment Variable | Default Value | Description |
|---|---|---|
| `LITURGY_IMPORT_URL` | `http://localhost:8080/internal/v1/liturgy/import` | Spring Boot batch receiver URL. |
| `LITURGY_IMPORT_TOKEN` | *(Required)* | Secret Bearer authentication token. |
| `SCRAPER_DAYS_AHEAD` | `14` | Number of days ahead from today to scrape. |
| `HTTP_TIMEOUT_SECONDS` | `30` | HTTP request timeout in seconds. |
| `LOG_LEVEL` | `INFO` | Logging level (`DEBUG`, `INFO`, `WARNING`, `ERROR`). |
