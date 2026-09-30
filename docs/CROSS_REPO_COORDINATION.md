# Cross-Repository Coordination Guide

This document defines the rules for coordinating changes across the three Evangelizae repositories. It is referenced by each repo's `AGENTS.md` and applies when changes span multiple repos.

## The Three Repositories

| Repository | Purpose | Deploy target | Test command |
|---|---|---|---|
| `EvangelizaeBackend` | Serve liturgy, validate imports, store documents | Coolify Docker resource | `./mvnw test` |
| `liturgy-scraper` | Fetch CNBB HTML, parse, POST to backend | Coolify Scheduled Job | `uv run pytest` |
| `Evangelizae` (frontend) | Render UI, cache responses, PWA | Coolify Docker resource | `pnpm check` |

## Independence Rules

### Rule 1: Each repo deploys independently

Each repository has its own Dockerfile, test suite, and deployment pipeline. A change in one repo does not require redeploying the others **unless the contract changes**.

**Example:** Fixing a bug in the scraper's retry logic only requires redeploying the scraper. The backend and frontend continue running unchanged.

### Rule 2: The OpenAPI spec is the contract boundary

`openapi/evangelizae-v1.openapi.yml` in the backend repo is the **canonical** API contract. The frontend has a mirror copy in `contracts/`. The scraper implements the import side.

**If the contract does not change, the other repos do not need to change.**

**Example:** Adding a new field to the backend's internal document model (not exposed in the API) does not require frontend or scraper changes.

### Rule 3: Contract changes require coordinated deployment

When the contract changes (new required fields, changed validation rules, new endpoints, etc.):

1. **Backend repo:** Update `openapi/evangelizae-v1.openapi.yml` and implement the change
2. **Scraper repo:** Update `app/models/liturgy.py` and `app/main.py` to match (if import contract changed)
3. **Frontend repo:** Update `contracts/evangelizae-v1.openapi.yaml` mirror and adjust UI (if public contract changed)
4. **Deploy backend first**, then scraper/frontend
5. **Verify** with manual testing before re-enabling automated jobs

**Deploy order matters:**
- Backend first (it validates the contract)
- Scraper second (it must send valid requests)
- Frontend last (it must handle valid responses)

### Rule 4: Never break the contract without coordination

An agent working on one repo must not:
- Change the contract without updating the other repos
- Assume the other repos will accept the old contract after a change
- Deploy a contract-breaking change without verifying the other repos are updated

**Example:** If the backend adds a new required field to `DailyLiturgy`, the frontend must be updated to handle it before the backend is deployed. Otherwise, the frontend will fail to validate the response.

### Rule 5: Each repo owns its own code

An agent working on one repo must not:
- Modify code in another repo
- Assume code from another repo is available locally
- Import or reference code from another repo

**Example:** The backend agent must not modify scraper parsing logic, even if it sees a bug. Instead, it should document the issue and let the scraper agent fix it.

### Rule 6: Each repo runs its own tests

An agent validating changes must:
- Run only the test suite for the repo it's working on
- Not assume tests from other repos will catch issues
- Verify the contract is satisfied by running the relevant tests

**Test commands:**
- Backend: `./mvnw test` (18 tests)
- Scraper: `uv run pytest` (37 tests)
- Frontend: `pnpm check` (lint + typecheck + unit + build)

### Rule 7: Each repo has its own environment variables

Environment variables are repo-specific and must not be shared or assumed:

**Backend:**
- `MONGODB_URI`, `MONGO_DATABASE`
- `APP_CORS_ALLOWED_ORIGINS`
- `LITURGY_IMPORT_TOKEN`
- `SPRING_PROFILES_ACTIVE`, `PORT`, `JAVA_TOOL_OPTIONS`

**Scraper:**
- `LITURGY_IMPORT_URL` (backend's internal hostname)
- `LITURGY_IMPORT_TOKEN` (must match backend)
- `SCRAPER_DAYS_BEHIND`, `SCRAPER_DAYS_AHEAD`, `SCRAPER_TIMEZONE`
- `ALERT_DISCORD_WEBHOOK_URL`, `ALERT_WHATSAPP_PHONE`, `ALERT_WHATSAPP_APIKEY`

**Frontend:**
- `NEXT_PUBLIC_API_BASE_URL` (backend's public domain, baked into bundle)
- `NEXT_PUBLIC_APP_URL`
- `NEXT_PUBLIC_SENTRY_DSN`, `SENTRY_DSN`, `SENTRY_ENVIRONMENT`

**Shared values:**
- `LITURGY_IMPORT_TOKEN` must match between backend and scraper
- `NEXT_PUBLIC_API_BASE_URL` must point at the backend's public domain

### Rule 8: Internal vs. public URLs

The backend has two URLs:
- **Internal hostname** (Coolify-generated): Used by the scraper to reach the backend within the Docker network
- **Public domain** (`api.evangelizae.com`): Used by the frontend (browser) to reach the backend through the internet

**Agents must not confuse these:**
- Scraper uses internal hostname (faster, no proxy, no TLS overhead)
- Frontend uses public domain (browser can't reach internal network)
- Internal hostname is found in Coolify UI → resource → Access → Internal hostname

### Rule 9: Deployment is via Coolify

All three repos deploy as Coolify resources:
- Backend: Docker resource, port 8080, domain `api.evangelizae.com`
- Frontend: Docker resource, port 3000, domain `evangelizae.com`
- Scraper: Scheduled Job, cron `7 4 * * *`, no ports

**Agents must not:**
- Add `compose.yml` or Docker deployment logic to the repos (Coolify handles this)
- Assume local Docker Compose setup reflects production
- Modify deployment configuration from within the repos

### Rule 10: Contract verification before deploy

Before deploying any contract change:

1. **Backend:** Run `./mvnw test` — all 18 tests must pass
2. **Scraper:** Run `uv run pytest` — all 37 tests must pass
3. **Frontend:** Run `pnpm check` — lint, typecheck, tests, build must pass
4. **Manual verification:**
   - Backend: `curl https://api.evangelizae.com/api/v1/health`
   - Backend: `curl https://api.evangelizae.com/api/v1/liturgy/today?timezone=America/Sao_Paulo&locale=pt-BR`
   - Frontend: Open `https://evangelizae.com/pt/liturgia` in browser
   - Scraper: Run manually via Coolify "Run Now" and verify exit 0

## Contract Change Workflow

When a contract change is needed:

### Step 1: Identify the change

Determine which contract is changing:
- **Public contract** (`GET /api/v1/liturgy/today` response): Affects backend + frontend
- **Import contract** (`POST /internal/v1/liturgy/import` request): Affects backend + scraper
- **Both**: Affects all three repos

### Step 2: Update the canonical OpenAPI spec

In the backend repo, update `openapi/evangelizae-v1.openapi.yml`. This is the source of truth.

### Step 3: Update the mirror (if public contract changed)

In the frontend repo, update `contracts/evangelizae-v1.openapi.yaml` to match the backend's spec.

### Step 4: Update implementations

- **Backend:** Implement the change in controllers, services, models
- **Scraper:** Update `app/models/liturgy.py` and any validation logic
- **Frontend:** Update `liturgyService.ts` types and validation

### Step 5: Update tests

- **Backend:** Add/update tests in `src/test/java/...`
- **Scraper:** Add/update tests in `tests/`
- **Frontend:** Add/update tests in `src/services/liturgyService.test.ts`

### Step 6: Deploy in order

1. Deploy backend (verify health endpoint)
2. Deploy scraper (run manually, verify success)
3. Deploy frontend (verify UI loads correctly)

### Step 7: Monitor

Watch for 7 consecutive days:
- Scraper exits 0 daily
- No alerts fired
- Frontend displays correctly
- Backend returns 200 for liturgy endpoint

## Common Scenarios

### Scenario 1: Backend adds optional field to response

**Impact:** Frontend only (if it wants to use the field)

**Steps:**
1. Backend: Add field to `DailyLiturgy`, update OpenAPI spec
2. Backend: Deploy
3. Frontend: Optionally update UI to use the field
4. Frontend: Deploy (or skip if not using the field)
5. Scraper: No change needed

### Scenario 2: Backend adds required field to response

**Impact:** Frontend must handle it

**Steps:**
1. Backend: Add field to `DailyLiturgy`, update OpenAPI spec
2. Frontend: Update types and validation to require the field
3. Backend: Deploy
4. Frontend: Deploy
5. Scraper: No change needed

### Scenario 3: Import contract adds required field

**Impact:** Scraper must send it

**Steps:**
1. Backend: Add field to `LiturgyImportRequest`, update OpenAPI spec, update validation
2. Scraper: Update models and parsing to include the field
3. Backend: Deploy
4. Scraper: Deploy
5. Frontend: No change needed

### Scenario 4: Scraper changes parse logic (no contract change)

**Impact:** Scraper only

**Steps:**
1. Scraper: Update parser, add fixtures, run 400-day sweep
2. Scraper: Deploy
3. Backend: No change needed
4. Frontend: No change needed

### Scenario 5: Frontend changes UI (no contract change)

**Impact:** Frontend only

**Steps:**
1. Frontend: Update UI components
2. Frontend: Deploy
3. Backend: No change needed
4. Scraper: No change needed

## Anti-patterns to Avoid

### Anti-pattern 1: Monolithic deploys

**Wrong:** "I changed the scraper, so I need to redeploy everything."

**Right:** Only redeploy the repo that changed (unless the contract changed).

### Anti-pattern 2: Cross-repo code modifications

**Wrong:** "I see a bug in the scraper while working on the backend, so I'll fix it here."

**Right:** Document the issue and let the scraper agent fix it in its own repo.

### Anti-pattern 3: Assuming local setup matches production

**Wrong:** "My local Docker Compose works, so production will work."

**Right:** Production uses Coolify, not Docker Compose. Test with the actual deployment method.

### Anti-pattern 4: Breaking the contract without coordination

**Wrong:** "I'll add a required field to the backend and deploy it now. The frontend can catch up later."

**Right:** Update all affected repos, then deploy in order (backend → scraper/frontend).

### Anti-pattern 5: Sharing environment variables across repos

**Wrong:** "I'll put all env vars in one place and reference them from all repos."

**Right:** Each repo has its own env vars in Coolify. Only `LITURGY_IMPORT_TOKEN` must match between backend and scraper.

## Summary

The three repos are **independent but coordinated**:

- **Independent:** Each deploys, tests, and runs on its own
- **Coordinated:** The OpenAPI spec binds them; contract changes require coordination

**Agents must respect these boundaries to maintain a stable, deployable system.**
