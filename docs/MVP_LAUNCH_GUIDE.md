# MVP Launch Guide — Evangelizae

**Version:** 1.0  
**Last updated:** 2026-09-29  
**Status:** Ready for deployment

This is the canonical technical guide for deploying Evangelizae MVP to production. It covers architecture, repository responsibilities, Coolify configuration, environment variables, network topology, alerting, and the exact deploy sequence.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Repository Structure](#2-repository-structure)
3. [Network Topology](#3-network-topology)
4. [Coolify Resource Configuration](#4-coolify-resource-configuration)
5. [Environment Variables](#5-environment-variables)
6. [Alerting Setup](#6-alerting-setup)
7. [Deploy Sequence](#7-deploy-sequence)
8. [Verification Checklist](#8-verification-checklist)
9. [Post-Launch Monitoring](#9-post-launch-monitoring)
10. [Troubleshooting](#10-troubleshooting)
11. [Rollback Procedures](#11-rollback-procedures)

---

## 1. Architecture Overview

### 1.1 System Components

```
─────────────────────────────────────────────────────────────────────┐
│                         Coolify VPS                                  │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────   │
│  │  Docker Network: coolify (internal)                          │   │
│  │                                                              │   │
│  │  ─────────────┐    ┌──────────────┐    ┌───────────────┐  │   │
│  │  │  MongoDB    │    │  Backend API │    │  Scraper Job  │  │   │
│  │  │  (Docker)   │◄──►│  (Docker)    │◄──►│  (Scheduled)  │  │   │
│  │  │  Port: 27017│    │  Port: 8080  │    │  One-shot     │  │   │
│  │  └─────────────┘    └──────┬───────┘    └───────────────┘  │   │
│  │                            │                                 │   │
│  └────────────────────────────┼─────────────────────────────────┘   │
│                               │                                       │
│  ┌────────────────────────────┼─────────────────────────────────┐   │
│  │  Public Internet          │                                     │   │
│  │                           ▼                                     │   │
│  │  ┌─────────────────────────────────────────────────────────┐  │   │
│  │  │  Frontend (Docker)                                       │  │   │
│  │  │  Port: 3000                                              │  │   │
│  │  │  Domain: evangelizae.com (Coolify proxy + TLS)          │  │   │
│  │  │  Calls: https://api.evangelizae.com/api/v1/liturgy/today│  │   │
│  │  └─────────────────────────────────────────────────────────┘  │   │
│  │                                                                │   │
│  │  ┌─────────────────────────────────────────────────────────┐  │   │
│  │  │  Backend API (Public)                                    │  │   │
│  │  │  Domain: api.evangelizae.com (Coolify proxy + TLS)      │  │   │
│  │  │  Endpoints: /api/v1/liturgy/today, /api/v1/health       │  │   │
│  │  └─────────────────────────────────────────────────────────┘  │   │
│  └────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### 1.2 Data Flow

#### Liturgy Ingestion (Push Model)

```
Host Cron (04:07 daily)
    │
    ▼
Coolify Scheduled Job: Scraper
    │
    ├─ Compute date window: today-7 to today+13 (21 days)
    ├─ For each date:
    │   ├─ Fetch from CNBB API: https://api-liturgia.edicoescnbb.com.br/contents/in/date/{date}
    │   ├─ Parse HTML → LiturgicalDay document
    │   └─ Validate: all readings have text, citations match summary
    │
    ├─ Pre-POST gate: refuse batch if any day is incomplete
    │
    ├─ POST to Backend API (internal hostname)
    │   URL: http://<backend-internal-hostname>:8080/internal/v1/liturgy/import
    │   Headers:
    │     Authorization: Bearer <LITURGY_IMPORT_TOKEN>
    │     X-Request-Id: <uuid>
    │   Body: LiturgyImportRequest (JSON)
    │
    ├─ Verify response:
    │   ├─ status == SUCCESS
    │   ├─ processed == received
    │   └─ no failed dates
    │
    └─ Exit 0 (success) or 1 (failure)
         │
         └─ If failure: send alerts to Discord + WhatsApp
```

#### Liturgy Serving (Pull Model)

```
Browser (evangelizae.com/pt/liturgia)
    │
    ▼
Frontend Container (Next.js)
    │
    ├─ Check localStorage cache for today's liturgy
    │   └─ If found and fresh: return CACHED
    │
    ├─ Fetch from Backend API (public domain)
    │   URL: https://api.evangelizae.com/api/v1/liturgy/today
    │   Params: timezone=America/Sao_Paulo&locale=pt-BR
    │
    ├─ Validate response shape
    │   ─ If invalid: throw LITURGY_RESPONSE_INVALID
    │
    ├─ Cache in localStorage
    │
    └─ Return LIVE data
         │
         └─ If API fails:
              ├─ Try localStorage cache → return CACHED
              ├─ Try embedded fallback (if within date range) → return EMBEDDED
              └─ If all fail: show error UI with retry button
```

### 1.3 Contract

The backend serves exactly three endpoints:

| Endpoint | Method | Auth | Purpose |
|---|---|---|---|
| `/api/v1/liturgy/today` | GET | None | Public product endpoint |
| `/api/v1/health` | GET | None | Liveness probe |
| `/internal/v1/liturgy/import` | POST | Bearer token | Scraper ingestion |

The frontend makes **exactly one** network call: `GET /api/v1/liturgy/today`.

---

## 2. Repository Structure

### 2.1 Backend (`EvangelizaeBackend`)

**Purpose:** Serve liturgy data, validate imports, store documents.

**Stack:** Java 25, Spring Boot 4, MongoDB 8

**Key files:**
```
EvangelizaeBackend/
├── src/main/java/org/evangelizae/api/
│   ├── liturgy/
│   │   ├── controller/
│   │   │   ├── LiturgyController.java          # GET /api/v1/liturgy/today
│   │   │   └── LiturgyImportController.java    # POST /internal/v1/liturgy/import
│   │   ├── service/
│   │   │   └── LiturgyService.java             # Business logic, validation
│   │   ├── model/
│   │   │   ├── DailyLiturgy.java               # Public response DTO
│   │   │   ├── LiturgyImportRequest.java       # Import request DTO
│   │   │   └── LiturgyImportResponse.java      # Import response DTO
│   │   └── repository/
│   │       ├── LiturgicalDayDocument.java      # MongoDB document
│   │       └── LiturgicalDayRepository.java    # Mongo repository
│   ├── config/
│   │   ├── ApiConfiguration.java               # CORS, Clock bean
│   │   ├── SecurityConfig.java                 # Stateless, no auth
│   │   └── InternalBearerAuthFilter.java       # Import endpoint auth
│   └── web/
│       ├── HealthController.java               # GET /api/v1/health
│       └── GlobalExceptionHandler.java         # Error responses
── src/main/resources/
│   ├── application.yml                         # Base config
│   ├── application-dev.yml                     # Dev overrides
│   └── application-prod.yml                    # Prod overrides
├── openapi/
│   └── evangelizae-v1.openapi.yml              # Canonical API contract
├── deploy/
│   ├── Caddyfile                               # Local dev proxy
│   ├── mongo-init.js                           # Local dev Mongo init
│   ├── reconcile-liturgical-days.js            # Production reconciliation script
│   └── README.md                               # Local dev runbook
├── compose.yml                                 # Local dev stack (NOT for production)
├── Dockerfile                                  # Coolify build
└── docs/
    ├── LAUNCH_PLAN_2026-09-28.md               # Original plan
    ├── IMPLEMENTATION_SUMMARY_2026-09-29.md    # What was built
    └── MVP_LAUNCH_GUIDE.md                     # This document
```

**Tests:** 18 backend tests, all green.

### 2.2 Scraper (`liturgy-scraper`)

**Purpose:** Fetch liturgy from CNBB, parse HTML, POST to backend.

**Stack:** Python 3.14, uv, httpx, BeautifulSoup4

**Key files:**
```
liturgy-scraper/
├── app/
│   ├── main.py                                 # Entry point, CLI args, batch assertion
│   ├── parsers/
│   │   ├── cnbb_parser.py                      # CNBB HTML parser (citation matching)
│   │   └── vatican_parser.py                   # Vatican News parser (validation only)
│   ├── services/
│   │   ├── scraper_service.py                  # Orchestration, retry, response verification
│   │   └── alert.py                            # Discord + WhatsApp alerts
│   ├── sources/
│   │   ├── cnbb.py                             # CNBB HTTP client
│   │   └── vatican.py                          # Vatican News HTTP client
│   ├── models/
│   │   └── liturgy.py                          # Pydantic models
│   └── validators/
│       └── liturgy_validator.py                # Schema validation
├── tests/
│   ├── fixtures/cnbb/                          # HTML fixtures for known-broken dates
│   │   ├── alternative_first_reading_2026-09-29.json
│   │   ├── alternative_first_reading_2026-12-21.json
│   │   ├── alternative_first_reading_2027-01-25.json
│   │   ├── easter_sunday_2026-04-05.json
│   │   ├── easter_vigil_2026-04-04.json
│   │   ├── multiple_masses_2026-12-24.json
│   │   ├── multiple_masses_2026-12-25.json
│   │   └── optional_readings_2026-11-02.json
│   ├── test_cnbb_parser.py                     # Parser tests (37 total)
│   ├── test_main.py                            # CLI and batch assertion tests
│   └── test_scraper_service.py                 # Service tests
├── tools/
│   ├── sweep.py                                # 400-day parser gate
│   └── refresh_fixtures.py                     # Fixture refresh (manual review required)
├── deploy/
│   └── crontab.example                         # Local dev cron example
├── Dockerfile                                  # Coolify build
├── pyproject.toml                              # Dependencies
└── .env.example                                # Environment template
```

**Tests:** 37 scraper tests, all green.

### 2.3 Frontend (`Evangelizae`)

**Purpose:** Render liturgy, rosary, onboarding, sanctuary.

**Stack:** Next.js 16, React 19, TypeScript, Zustand, Serwist (PWA)

**Key files:**
```
Evangelizae/
├── src/
│   ├── app/[locale]/
│   │   └── liturgy/
│   │       └── page.tsx                        # Liturgy page
│   ├── services/
│   │   ├── liturgyService.ts                   # API client, cache, validation
│   │   └── liturgyService.test.ts              # Service tests
│   ├── data/
│   │   └── embeddedDailyLiturgy.ts             # Fallback (2026-08-25 to 2026-09-01, expired)
│   ├── store/
│   │   └── usePreferencesStore.ts              # User preferences
│   └── hooks/
│       └── useDayContext.ts                    # Liturgical day context
├── contracts/
│   └── evangelizae-v1.openapi.yaml             # Contract mirror (backend is canonical)
├── Dockerfile                                  # Coolify build
├── next.config.ts                              # Next.js config, PWA, headers
└── package.json
```

**Tests:** Vitest unit tests, Playwright E2E tests.

---

## 3. Network Topology

### 3.1 Coolify Docker Network

All resources run on the `coolify` Docker network. Internal hostnames are generated by Coolify and follow the pattern:

```
<resource-id>-<project-id>
```

Example: `c88x7bp31cnnx2woh0c892ft-010619514887`

### 3.2 Communication Paths

| From | To | Protocol | URL Pattern | Notes |
|---|---|---|---|---|
| Frontend (browser) | Backend API (public) | HTTPS | `https://api.evangelizae.com/api/v1/liturgy/today` | Through Coolify proxy + TLS |
| Scraper | Backend API (internal) | HTTP | `http://<backend-internal-hostname>:8080/internal/v1/liturgy/import` | Direct container-to-container |
| Backend API | MongoDB (internal) | TCP | `mongodb://<mongo-internal-hostname>:27017/evangelizae` | Direct container-to-container |
| Frontend (container) | Backend API (public) | HTTPS | `https://api.evangelizae.com/api/v1/liturgy/today` | Through Coolify proxy (SSR if used) |

### 3.3 Security Boundaries

- **MongoDB:** No host port published. Only accessible from `coolify` network.
- **Backend API:** Port 8080 not published to host. Public access only through Coolify proxy.
- **Scraper:** One-shot job, no persistent ports. Reaches backend via internal hostname.
- **Frontend:** Port 3000 not published to host. Public access only through Coolify proxy.

### 3.4 Internal Hostnames (Placeholders)

**These values must be filled in from Coolify UI after creating each resource.**

| Resource | Internal Hostname | Port | Notes |
|---|---|---|---|
| MongoDB | `TODO_FILL_IN_FROM_COOLIFY` | 27017 | Found in MongoDB resource → Access → Internal hostname |
| Backend API | `TODO_FILL_IN_FROM_COOLIFY` | 8080 | Found in Backend API resource → Access → Internal hostname |
| Frontend | `c88x7bp31cnnx2woh0c892ft-010619514887` | 3000 | Already known (from screenshot) |

---

## 4. Coolify Resource Configuration

### 4.1 MongoDB Resource

**Type:** Docker resource  
**Image:** `mongo:8.0`

**Environment Variables:**
```env
MONGO_INITDB_ROOT_USERNAME=evangelizae_root
MONGO_INITDB_ROOT_PASSWORD=<generate-with-openssl-rand-hex-32>
MONGO_INITDB_DATABASE=evangelizae
MONGO_APP_USERNAME=evangelizae
MONGO_APP_PASSWORD=<generate-with-openssl-rand-hex-32>
```

**Volumes:**
- `/data/db` (persistent)

**Ports:**
- Internal: 27017 (do not publish to host)

**Healthcheck:**
```bash
mongosh --quiet --eval "db.adminCommand('ping').ok"
```

**Initialization Script:**
Create a file `mongo-init.js` and mount it to `/docker-entrypoint-initdb.d/10-app-user.js`:

```javascript
// Create application user with least privilege
db = db.getSiblingDB(process.env.MONGO_INITDB_DATABASE);
db.createUser({
  user: process.env.MONGO_APP_USERNAME,
  pwd: process.env.MONGO_APP_PASSWORD,
  roles: [{ role: 'readWrite', db: process.env.MONGO_INITDB_DATABASE }]
});

// Create indexes
db.liturgical_days.createIndex({ date: 1 }, { unique: true });
db.liturgical_days.createIndex({ provider: 1 });
```

**After creation:**
1. Note the internal hostname from Access → Internal hostname
2. Update the placeholder in this document (Section 3.4)

### 4.2 Backend API Resource

**Type:** Docker resource  
**Build:** Dockerfile from `EvangelizaeBackend` repo  
**Domain:** `api.evangelizae.com` (Coolify proxy + automatic TLS)

**Environment Variables:**
```env
# Spring Boot
SPRING_PROFILES_ACTIVE=prod
PORT=8080

# MongoDB
MONGODB_URI=mongodb://evangelizae:<MONGO_APP_PASSWORD>@<MONGO_INTERNAL_HOSTNAME>:27017/evangelizae?authSource=evangelizae
MONGO_DATABASE=evangelizae

# CORS
APP_CORS_ALLOWED_ORIGINS=https://evangelizae.com

# Import token (must match scraper)
LITURGY_IMPORT_TOKEN=<generate-with-openssl-rand-hex-32>

# JVM tuning
JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError
```

**Ports:**
- Internal: 8080 (do not publish to host)

**Healthcheck:**
```bash
curl -fsS http://127.0.0.1:8080/api/v1/health
```

**After creation:**
1. Note the internal hostname from Access → Internal hostname
2. Update the placeholder in this document (Section 3.4)
3. Use this internal hostname in the scraper's `LITURGY_IMPORT_URL`

### 4.3 Frontend Resource

**Type:** Docker resource  
**Build:** Dockerfile from `Evangelizae` repo  
**Domain:** `evangelizae.com` (Coolify proxy + automatic TLS)

**Build Arguments:**
```env
NEXT_PUBLIC_API_BASE_URL=https://api.evangelizae.com/api/v1
NEXT_PUBLIC_APP_URL=https://evangelizae.com
```

**Environment Variables:**
```env
NODE_ENV=production
PORT=3000

# Optional: Sentry monitoring
NEXT_PUBLIC_SENTRY_DSN=
SENTRY_DSN=
SENTRY_ENVIRONMENT=production
```

**Ports:**
- Internal: 3000 (do not publish to host)

**Healthcheck:**
```bash
wget -qO- http://127.0.0.1:3000/api/health
```

**Note:** `NEXT_PUBLIC_API_BASE_URL` is baked into the JS bundle at build time. If the backend domain changes, the frontend must rebuild.

### 4.4 Scraper Resource

**Type:** Scheduled Job  
**Build:** Dockerfile from `liturgy-scraper` repo  
**Schedule:** `7 4 * * *` (daily at 04:07, deliberately off the hour)

**Environment Variables:**
```env
# Backend API (internal hostname)
LITURGY_IMPORT_URL=http://<BACKEND_INTERNAL_HOSTNAME>:8080/internal/v1/liturgy/import
LITURGY_IMPORT_TOKEN=<same-value-as-backend>

# Window configuration
SCRAPER_DAYS_BEHIND=7
SCRAPER_DAYS_AHEAD=14

# Timezone (already default in Dockerfile, but explicit is safer)
SCRAPER_TIMEZONE=America/Sao_Paulo

# HTTP timeout
HTTP_TIMEOUT_SECONDS=30

# Logging
SCRAPER_LOG_LEVEL=INFO

# Alerting
ALERT_DISCORD_WEBHOOK_URL=<your-discord-webhook-url>
ALERT_WHATSAPP_PHONE=<your-phone-with-country-code>
ALERT_WHATSAPP_APIKEY=<your-callmebot-apikey>
```

**Ports:**
- None (one-shot job, no persistent ports)

**Restart policy:**
- `no` (do not restart on failure; let the alert fire)

---

## 5. Environment Variables

### 5.1 Secret Generation

Generate secrets before deployment:

```bash
# MongoDB root password
openssl rand -hex 32

# MongoDB app password
openssl rand -hex 32

# Liturgy import token (must match between backend and scraper)
openssl rand -hex 32
```

**Store these securely.** You will need them for multiple resources.

### 5.2 Variable Matrix

| Variable | MongoDB | Backend API | Frontend | Scraper | Notes |
|---|---|---|---|---|---|
| `MONGO_INITDB_ROOT_USERNAME` | ✓ | | | | |
| `MONGO_INITDB_ROOT_PASSWORD` | ✓ | | | | |
| `MONGO_INITDB_DATABASE` | ✓ | | | | |
| `MONGO_APP_USERNAME` | ✓ | | | | |
| `MONGO_APP_PASSWORD` | ✓ | ✓ | | | Backend needs it for MONGODB_URI |
| `MONGODB_URI` | | ✓ | | | Built from components |
| `MONGO_DATABASE` | | ✓ | | | |
| `APP_CORS_ALLOWED_ORIGINS` | | ✓ | | | Exact origin, no wildcard |
| `LITURGY_IMPORT_TOKEN` | | ✓ | | ✓ | Must match |
| `SPRING_PROFILES_ACTIVE` | | prod | | | |
| `PORT` | | 8080 | 3000 | | |
| `JAVA_TOOL_OPTIONS` | | ✓ | | | |
| `NEXT_PUBLIC_API_BASE_URL` | | | ✓ | | Build arg |
| `NEXT_PUBLIC_APP_URL` | | | ✓ | | Build arg |
| `NODE_ENV` | | | production | | |
| `LITURGY_IMPORT_URL` | | | | ✓ | Internal hostname |
| `SCRAPER_DAYS_BEHIND` | | | | ✓ | |
| `SCRAPER_DAYS_AHEAD` | | | | ✓ | |
| `SCRAPER_TIMEZONE` | | | | ✓ | |
| `HTTP_TIMEOUT_SECONDS` | | | | ✓ | |
| `SCRAPER_LOG_LEVEL` | | | | ✓ | |
| `ALERT_DISCORD_WEBHOOK_URL` | | | | ✓ | Optional |
| `ALERT_WHATSAPP_PHONE` | | | | ✓ | Optional |
| `ALERT_WHATSAPP_APIKEY` | | | | ✓ | Optional |

---

## 6. Alerting Setup

### 6.1 Discord Webhook

**Setup:**
1. Open Discord server → Server Settings → Integrations → Webhooks
2. Click "New Webhook"
3. Name: `Evangelizae Scraper Alerts`
4. Channel: Choose a dedicated alerts channel
5. Copy webhook URL

**Webhook URL format:**
```
https://discord.com/api/webhooks/<webhook-id>/<webhook-token>
```

**Test:**
```bash
curl -X POST "https://discord.com/api/webhooks/<id>/<token>" \
  -H "Content-Type: application/json" \
  -d '{"content": "Test alert from Evangelizae scraper"}'
```

### 6.2 WhatsApp via CallMeBot

**Setup:**
1. Save `+34 644 51 95 35` as a contact in your phone
2. Send it the message: `I accept pipedreambot`
3. You will receive an API key
4. Note your phone number with country code (e.g., `5511999999999` for Brazil)

**API endpoint:**
```
https://api.callmebot.com/whatsapp.php?phone=<phone>&text=<message>&apikey=<apikey>
```

**Test:**
```bash
curl "https://api.callmebot.com/whatsapp.php?phone=5511999999999&text=Test+alert+from+Evangelizae+scraper&apikey=<your-apikey>"
```

### 6.3 Alert Implementation

The scraper sends alerts on failure via `app/services/alert.py`:

```python
import os, httpx

def send_alert(message: str) -> None:
    # Discord
    discord_url = os.getenv("ALERT_DISCORD_WEBHOOK_URL")
    if discord_url:
        try:
            httpx.post(discord_url, json={"content": message}, timeout=10)
        except Exception:
            pass  # Never let alert failure block the exit

    # WhatsApp (CallMeBot)
    whatsapp_phone = os.getenv("ALERT_WHATSAPP_PHONE")
    whatsapp_key = os.getenv("ALERT_WHATSAPP_APIKEY")
    if whatsapp_phone and whatsapp_key:
        try:
            httpx.get("https://api.callmebot.com/whatsapp.php", params={
                "phone": whatsapp_phone,
                "text": message,
                "apikey": whatsapp_key,
            }, timeout=10)
        except Exception:
            pass
```

**Called from `main.py`:**
```python
except Exception:
    logging.exception("Liturgy scraper failed")
    send_alert(f"Evangelizae scraper failed, exit 1. Today may have no liturgy.")
    raise SystemExit(1)
```

### 6.4 Alert Triggers

The scraper exits `1` and sends alerts on:
- CNBB API unreachable
- HTML parse failure (any day in the window)
- Pre-POST gate failure (incomplete batch)
- POST failure (after retries)
- Response verification failure (status != SUCCESS, processed != received, failed dates present)

The scraper exits `0` and sends no alerts on:
- All days scraped, validated, and imported successfully

---

## 7. Deploy Sequence

**Prerequisites:**
- Coolify installed and accessible
- DNS for `evangelizae.com` and `api.evangelizae.com` pointing at VPS
- Git repos accessible (GitHub, GitLab, or self-hosted)
- Secrets generated (Section 5.1)

### Step 1: Commit Backend Changes

```bash
cd /home/otavio/Work/EvangelizaeBackend

# Remove scraper from compose.yml (lines 71-89)
# The scraper service is now a separate repo

# Commit working tree changes
git add -A
git commit -m "feat: remove scraper from compose, clean working tree for Coolify deploy"
git push
```

**What changed:**
- `compose.yml`: Removed `scraper` service (lines 71-89)
- `workers/liturgy-scraper/*`: All deleted (moved to separate repo)
- `src/main/resources/application-dev.yml`: Minor modification
- `src/test/java/.../LiturgyServiceTest.java`: Minor modification

### Step 2: Initialize Scraper Repo

```bash
cd /home/otavio/Work/liturgy-scraper

# Initialize git
git init
git remote add origin <your-scraper-repo-url>

# Add alerting module
cat > app/services/alert.py << 'EOF'
import os
import httpx
import logging

logger = logging.getLogger(__name__)


def send_alert(message: str) -> None:
    """Send alert to Discord and/or WhatsApp on scraper failure.

    Both channels are optional. If no alert env vars are set, this is a no-op.
    Alert failures never block the scraper exit.
    """
    discord_url = os.getenv("ALERT_DISCORD_WEBHOOK_URL")
    if discord_url:
        try:
            httpx.post(discord_url, json={"content": message}, timeout=10)
            logger.info("Discord alert sent")
        except Exception:
            logger.exception("Failed to send Discord alert")

    whatsapp_phone = os.getenv("ALERT_WHATSAPP_PHONE")
    whatsapp_key = os.getenv("ALERT_WHATSAPP_APIKEY")
    if whatsapp_phone and whatsapp_key:
        try:
            httpx.get(
                "https://api.callmebot.com/whatsapp.php",
                params={
                    "phone": whatsapp_phone,
                    "text": message,
                    "apikey": whatsapp_key,
                },
                timeout=10,
            )
            logger.info("WhatsApp alert sent")
        except Exception:
            logger.exception("Failed to send WhatsApp alert")
EOF

# Update main.py to call alert on failure
# Add to imports: from app.services.alert import send_alert
# Add to except block: send_alert("Evangelizae scraper failed, exit 1. Today may have no liturgy.")

# Commit
git add -A
git commit -m "feat: add Discord and WhatsApp alerting on failure"
git push
```

### Step 3: Create MongoDB Resource in Coolify

1. Open Coolify dashboard
2. Click "New Resource" → "Database" → "MongoDB" (or "Docker" if custom)
3. Configure:
   - Image: `mongo:8.0`
   - Environment variables (Section 4.1)
   - Volumes: `/data/db` (persistent)
   - Ports: Internal 27017 (do not publish)
   - Healthcheck: `mongosh --quiet --eval "db.adminCommand('ping').ok"`
4. Deploy
5. **Copy internal hostname** from Access → Internal hostname
6. **Update placeholder** in this document (Section 3.4)

### Step 4: Create Backend API Resource in Coolify

1. Click "New Resource" → "Docker"
2. Configure:
   - Git repo: `EvangelizaeBackend`
   - Branch: `main` (or your deploy branch)
   - Dockerfile path: `/Dockerfile`
   - Domain: `api.evangelizae.com`
   - Port: 8080
   - Environment variables (Section 4.2)
     - **Use MongoDB internal hostname** from Step 3
     - **Generate `LITURGY_IMPORT_TOKEN`** with `openssl rand -hex 32`
3. Deploy
4. **Copy internal hostname** from Access → Internal hostname
5. **Update placeholder** in this document (Section 3.4)

### Step 5: Verify Backend API

```bash
# From your local machine
curl -fsS https://api.evangelizae.com/api/v1/health
# Expected: {"status":"UP"}

# Verify internal endpoint is blocked
curl -s -o /dev/null -w '%{http_code}' https://api.evangelizae.com/internal/v1/liturgy/import
# Expected: 404 (blocked by Coolify proxy)
```

### Step 6: Create Frontend Resource in Coolify

1. Click "New Resource" → "Docker"
2. Configure:
   - Git repo: `Evangelizae`
   - Branch: `main` (or your deploy branch)
   - Dockerfile path: `/Dockerfile`
   - Domain: `evangelizae.com`
   - Port: 3000
   - Build arguments (Section 4.3)
     - `NEXT_PUBLIC_API_BASE_URL=https://api.evangelizae.com/api/v1`
     - `NEXT_PUBLIC_APP_URL=https://evangelizae.com`
   - Environment variables (Section 4.3)
3. Deploy

### Step 7: Create Scraper Resource in Coolify

1. Click "New Resource" → "Scheduled Job"
2. Configure:
   - Git repo: `liturgy-scraper`
   - Branch: `main` (or your deploy branch)
   - Dockerfile path: `/Dockerfile`
   - Schedule: `7 4 * * *`
   - Environment variables (Section 4.4)
     - **Use Backend API internal hostname** from Step 4
     - **Use same `LITURGY_IMPORT_TOKEN`** as backend
     - **Add Discord webhook URL** (Section 6.1)
     - **Add CallMeBot phone + API key** (Section 6.2)
3. **Do not enable schedule yet** (we will run manually first)

### Step 8: Run Scraper Manually (Backfill)

1. In Coolify, open the Scraper resource
2. Click "Run Now" or "Execute"
3. Wait for completion (should take 1-3 minutes for 21 days)
4. Check logs for:
   - `SUCCESS` status
   - `processed == received` (21 == 21)
   - No failed dates
   - Exit code 0

### Step 9: Verify Liturgy Endpoint

```bash
# From your local machine
curl -fsS "https://api.evangelizae.com/api/v1/liturgy/today?timezone=America/Sao_Paulo&locale=pt-BR" | jq .
```

**Expected response:**
```json
{
  "date": "2026-09-29",
  "title": "São Miguel, São Gabriel e São Rafael, arcanjos, solenidade",
  "color": "WHITE",
  "prayers": {},
  "groups": [
    {
      "kind": "FIRST_READING",
      "items": [
        {
          "title": "Primeira leitura",
          "reference": "Dn 7,9-10.13-14",
          "text": "..."
        }
      ]
    },
    // ... more groups
  ],
  "source": {
    "provider": "CNBB",
    "fetchedAt": "2026-09-29T04:07:00Z",
    "freshness": "LIVE"
  }
}
```

**Verify:**
- `date` matches today's date in São Paulo
- `title` is non-empty
- `color` is in enum (GREEN, WHITE, RED, PURPLE, ROSE)
- `groups` is non-empty, each `items` is non-empty
- `source.provider` is "CNBB" (not "mongodb")
- `source.freshness` is "LIVE"

### Step 10: Verify Frontend

1. Open `https://evangelizae.com/pt/liturgia` in browser
2. Verify:
   - Liturgy displays correctly
   - Title, date, color match backend response
   - All readings present
   - Source shows "CNBB"
   - No error messages

### Step 11: Enable Scraper Schedule

1. In Coolify, open the Scraper resource
2. Enable the schedule (`7 4 * * *`)
3. Verify it runs tomorrow at 04:07

### Step 12: Monitor for 7 Consecutive Days

Check daily:
- Scraper ran successfully (exit 0)
- No alerts fired
- `GET /api/v1/liturgy/today` returns LIVE data
- Frontend displays correctly

After 7 days, the embedded fallback (`embeddedDailyLiturgy.ts`) can be removed from the frontend.

---

## 8. Verification Checklist

### 8.1 Backend API

- [ ] `GET /api/v1/health` returns `{"status":"UP"}`
- [ ] `GET /api/v1/liturgy/today?timezone=America/Sao_Paulo&locale=pt-BR` returns valid `DailyLiturgy`
- [ ] Response shape matches OpenAPI spec
- [ ] `date` equals today's date in São Paulo
- [ ] `title` is non-empty
- [ ] `color` is in enum
- [ ] `groups` is non-empty, each `items` is non-empty
- [ ] `source.provider` is "CNBB" (not "mongodb")
- [ ] `source.freshness` is "LIVE"
- [ ] `source.fetchedAt` is recent UTC timestamp
- [ ] CORS headers present for `https://evangelizae.com`
- [ ] `POST /internal/v1/liturgy/import` returns 404 from public internet
- [ ] Invalid timezone returns 400
- [ ] Invalid locale returns 400
- [ ] Missing liturgy returns 503

### 8.2 Scraper

- [ ] Runs successfully via Coolify "Run Now"
- [ ] Logs show `SUCCESS` status
- [ ] `processed == received` (21 == 21)
- [ ] No failed dates
- [ ] Exit code 0
- [ ] Discord alert received (test with forced failure)
- [ ] WhatsApp alert received (test with forced failure)

### 8.3 Frontend

- [ ] `https://evangelizae.com/pt/liturgia` loads
- [ ] Liturgy displays correctly
- [ ] Title, date, color match backend
- [ ] All readings present
- [ ] Source shows "CNBB"
- [ ] No error messages
- [ ] Cache works (reload shows CACHED)
- [ ] Offline mode works (PWA installed)

### 8.4 MongoDB

- [ ] `liturgical_days` collection has 21+ documents
- [ ] Documents keyed by ISO date string (e.g., "2026-09-29")
- [ ] All documents have `provider: "CNBB"`
- [ ] No documents with missing `provider`
- [ ] Indexes present: `date` (unique), `provider`

### 8.5 Network Security

- [ ] MongoDB port 27017 not listening on host
- [ ] Backend port 8080 not listening on host
- [ ] Frontend port 3000 not listening on host
- [ ] `/internal/*` returns 404 from public internet
- [ ] Scraper reaches backend via internal hostname only

---

## 9. Post-Launch Monitoring

### 9.1 Daily Checks (Automated)

- Scraper exit code (0 = success, 1 = failure)
- Discord/WhatsApp alerts on failure
- `GET /api/v1/liturgy/today` returns 200 (not 503)

### 9.2 Weekly Checks (Manual)

- MongoDB disk usage (should be ~1MB per year)
- Backend API response time (should be < 100ms)
- Frontend Lighthouse score (should be > 90)
- Scraper logs for warnings or validation failures

### 9.3 Monthly Checks (Manual)

- Rotate `LITURGY_IMPORT_TOKEN` (generate new, update backend + scraper, restart both)
- Rotate MongoDB passwords (generate new, update MongoDB + backend, restart both)
- Review Discord/WhatsApp alert history for patterns
- Run 400-day sweep on scraper to verify parser still works:
  ```bash
  cd liturgy-scraper
  uv run python tools/sweep.py
  ```

### 9.4 Quarterly Checks (Manual)

- Review and refresh fixtures for known-broken dates:
  ```bash
  cd liturgy-scraper
  uv run python tools/refresh_fixtures.py --dry-run
  uv run python tools/refresh_fixtures.py
  ```
- Update dependencies (backend, scraper, frontend)
- Review Coolify resource logs for errors

---

## 10. Troubleshooting

### 10.1 Scraper Fails

**Symptoms:**
- Exit code 1
- Discord/WhatsApp alert received
- Logs show error

**Diagnosis:**
```bash
# In Coolify, open Scraper resource → Logs
# Look for:
# - "Liturgy scraper failed"
# - "scraping produced X of 21 days"
# - "has no text"
# - "status == PARTIAL"
# - "processed != received"
```

**Common causes:**
- CNBB API unreachable (network issue)
- HTML parse failure (CNBB changed markup)
- Backend API unreachable (wrong internal hostname)
- Invalid token (mismatch between backend and scraper)

**Fix:**
- Check CNBB API manually: `curl https://api-liturgia.edicoescnbb.com.br/contents/in/date/2026-09-29`
- Verify internal hostname in Coolify UI
- Verify token matches between backend and scraper
- If CNBB changed markup: run 400-day sweep, refresh fixtures, update parser

### 10.2 Backend Returns 503

**Symptoms:**
- `GET /api/v1/liturgy/today` returns 503
- Error: "Liturgy is not available for 2026-09-29"

**Diagnosis:**
```bash
# Check if document exists
# In Coolify, open MongoDB resource → Shell
mongosh -u evangelizae -p <password> --authenticationDatabase evangelizae
use evangelizae
db.liturgical_days.find({ date: ISODate("2026-09-29") })
```

**Common causes:**
- Scraper never ran for that date
- Scraper failed and date was not imported
- Document exists but missing `provider` (reconciliation needed)

**Fix:**
- Run scraper manually to backfill
- If document missing `provider`: run reconciliation script
  ```bash
  # In MongoDB shell
  db.liturgical_days.updateMany(
    { provider: { $exists: false } },
    { $set: { provider: "CNBB" } }
  )
  ```

### 10.3 Frontend Shows Error

**Symptoms:**
- Liturgy page shows "unavailableTitle" / "unavailableBody"
- Retry button does not help

**Diagnosis:**
```bash
# Open browser devtools → Network tab
# Look for:
# - GET /api/v1/liturgy/today → 503 or timeout
# - CORS error
# - LITURGY_RESPONSE_INVALID
```

**Common causes:**
- Backend returning 503 (see 10.2)
- CORS misconfiguration (wrong origin in `APP_CORS_ALLOWED_ORIGINS`)
- API URL wrong in `NEXT_PUBLIC_API_BASE_URL`
- Response shape mismatch (backend changed, frontend not rebuilt)

**Fix:**
- Verify backend returns 200 with valid data
- Verify CORS allows `https://evangelizae.com`
- Verify `NEXT_PUBLIC_API_BASE_URL=https://api.evangelizae.com/api/v1`
- Rebuild frontend if backend contract changed

### 10.4 MongoDB Connection Fails

**Symptoms:**
- Backend logs: "MongoTimeoutError" or "Authentication failed"
- Backend healthcheck fails

**Diagnosis:**
```bash
# In Coolify, open Backend API resource → Logs
# Look for:
# - "MongoTimeoutError"
# - "Authentication failed"
# - "connect ECONNREFUSED"
```

**Common causes:**
- Wrong `MONGODB_URI` (typo, wrong password, wrong hostname)
- MongoDB not running
- MongoDB not on same Docker network

**Fix:**
- Verify `MONGODB_URI` format: `mongodb://evangelizae:<password>@<internal-hostname>:27017/evangelizae?authSource=evangelizae`
- Verify MongoDB internal hostname from Coolify UI
- Verify MongoDB resource is running in Coolify

### 10.5 CORS Error

**Symptoms:**
- Browser console: "Access to fetch at '...' has been blocked by CORS policy"
- Frontend cannot reach backend

**Diagnosis:**
```bash
# From browser devtools → Network tab
# Look for:
# - OPTIONS request to /api/v1/liturgy/today
# - Response headers missing Access-Control-Allow-Origin
```

**Fix:**
- Verify `APP_CORS_ALLOWED_ORIGINS=https://evangelizae.com` (exact, no trailing slash, no wildcard)
- Restart backend API after changing CORS config

---

## 11. Rollback Procedures

### 11.1 Backend Rollback

**Scenario:** Backend deploy introduced a bug

**Steps:**
1. In Coolify, open Backend API resource
2. Click "Deployments" → select previous working deployment
3. Click "Rollback"
4. Verify `GET /api/v1/health` returns 200
5. Verify `GET /api/v1/liturgy/today` returns valid data

### 11.2 Frontend Rollback

**Scenario:** Frontend deploy introduced a bug

**Steps:**
1. In Coolify, open Frontend resource
2. Click "Deployments" → select previous working deployment
3. Click "Rollback"
4. Verify `https://evangelizae.com/pt/liturgia` loads correctly

### 11.3 Scraper Rollback

**Scenario:** Scraper deploy introduced a parse failure

**Steps:**
1. In Coolify, open Scraper resource
2. Click "Deployments" → select previous working deployment
3. Click "Rollback"
4. Run scraper manually to verify it succeeds
5. Re-enable schedule

### 11.4 Database Rollback

**Scenario:** MongoDB data corrupted

**Steps:**
1. Stop scraper schedule (prevent further writes)
2. In Coolify, open MongoDB resource → Backups
3. Restore from last known good backup
4. Run reconciliation script to verify data integrity
5. Run scraper manually to backfill any missing dates
6. Re-enable scraper schedule

**Backup frequency:** Coolify can be configured for daily backups. Verify this is enabled.

---

## Appendix A: Coolify Internal Hostname Pattern

Coolify generates internal hostnames in the format:

```
<resource-id>-<project-id>
```

Example: `c88x7bp31cnnx2woh0c892ft-010619514887`

**To find the internal hostname:**
1. Open the resource in Coolify
2. Click "Access" tab
3. Look for "Internal hostname" under "Internal access"
4. Copy the value

**All resources on the same Coolify Docker network can reach each other via these hostnames.**

---

## Appendix B: Dockerfile Notes

### Backend API Dockerfile

- Multi-stage build (Maven build → JRE runtime)
- Exposes port 8080
- Healthcheck: `curl http://127.0.0.1:8080/api/v1/health`
- Graceful shutdown: 10s cap (via `application.yml`)
- JVM tuning: `MaxRAMPercentage=75.0`

### Frontend Dockerfile

- Multi-stage build (dependencies → builder → runner)
- Build args: `NEXT_PUBLIC_API_BASE_URL`, `NEXT_PUBLIC_APP_URL`
- Exposes port 3000
- Healthcheck: `wget http://127.0.0.1:3000/api/health`
- Standalone output (Next.js)

### Scraper Dockerfile

- Single-stage build (Python 3.14 + uv)
- No exposed ports (one-shot job)
- No healthcheck (exits immediately)
- Entrypoint: `python -m app.main`
- Timezone: `America/Sao_Paulo` (baked in)

---

## Appendix C: Known Fidelity Gaps

These are accepted limitations, not bugs:

1. **Mass prayers are permanently `{}`.** CNBB payload has no Coleta, no orações, no prefácio.
2. **Weekday titles are bare season name.** `26ª Semana do Tempo Comum` rather than `Segunda-feira da 26ª Semana do Tempo Comum`.
3. **Easter Vigil third citation** (`Rm 6,3-11 Sl 117(118) 1-2…`) is typed as EXTRA (approximate).
4. **Summary line naming several readings** matches only the first one.

---

## Appendix D: Contract Verification

The backend serves everything the frontend MVP needs:

| Frontend Need | Backend Provides | Status |
|---|---|---|
| `GET /api/v1/liturgy/today?timezone&locale` | Yes | ✓ |
| `DailyLiturgy` shape | Yes | ✓ |
| `freshness: LIVE \| CACHED` | Yes | ✓ |
| `source.provider` never "mongodb" | Yes (503 instead) | ✓ |
| Groups iterable (no SECOND_READING on weekday) | Yes (omits empty groups) | ✓ |
| Alternative readings as multiple items | Yes | ✓ |
| CORS from `evangelizae.com` | Yes | ✓ |
| 400 vs 503 distinguishable | Yes | ✓ |
| `/api/v1/health` | Yes | ✓ |

**No gaps.** The backend contract is complete.

---

## Appendix E: Post-Launch Tasks

After 7 consecutive days of successful operation:

1. **Remove embedded fallback** from frontend:
   - Delete `src/data/embeddedDailyLiturgy.ts`
   - Update `liturgyService.ts` to remove embedded fallback logic
   - Update tests
   - Rebuild frontend

2. **Remove local dev compose stack** (optional):
   - Delete `compose.yml`, `deploy/Caddyfile`, `deploy/mongo-init.js`
   - Update `deploy/README.md` to reflect Coolify-only deployment

3. **Document lessons learned:**
   - Any issues encountered during deploy
   - Any configuration changes made
   - Any monitoring alerts fired

---

**End of document.**
