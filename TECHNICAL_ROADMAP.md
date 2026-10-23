# Technical Roadmap for the Rosary Backend

## 1. Purpose

This document is the backend implementation roadmap for the Java/Spring Boot service in this repository. It aligns the current backend architecture with the frontend requirements pack in `frontend docs/` and defines the work needed to evolve the project from the current boilerplate/staging mode into the production backend expected by the frontend.

## 2. Source of truth

The roadmap is based on the following project artifacts:

- `README.md` — current backend scope, run instructions, and endpoint surface.
- `frontend docs/README.md` — index of frontend contract documents.
- `frontend docs/prd-frontend-to-backend.md` — product goals, feature inventory, rollout phases, and acceptance criteria.
- `frontend docs/frontend-api-contract.md` — request/response shapes expected by the frontend.
- `frontend docs/openapi-frontend-contract.yaml` — machine-readable contract baseline.
- `frontend docs/enum-mapping.json` — canonical enum/value mapping between frontend and backend.
- `frontend docs/frontend-feature-gap-analysis.md` — current gaps and sequencing guidance.

## 3. Current backend architecture

The backend is already organized as a feature-first Spring Boot application:

- Application bootstrap: `dev.ximarelli.rosary.backend.RosaryBackend`
- Cross-cutting config: `config/`
- Shared utilities and API helpers: `shared/`
- Feature modules:
  - `auth/`
  - `users/`
  - `checkins/`
  - `prayers/`
  - `health/`

Each feature currently follows the same pattern:

- Controller layer for HTTP endpoints
- Application service for business logic
- Request/response records or DTOs
- Repository abstraction, with in-memory implementations for local development

### Current HTTP and security posture

The current backend exposes the required route surface, but authentication is still transitional:

- Feature routes exist under `/auth`, `/users`, `/checkins`, and `/prayers`
- The current controllers resolve a user from `X-User-Id`, defaulting to `demo-user`
- Security is stateless at the Spring level, but it is still permissive and not yet enforcing JWT bearer authentication
- Global errors are mapped to a JSON response with a `message` field

This means the application is already structurally close to the frontend contract, but several areas still need hardening before production use.

## 4. Target backend architecture

The target backend should be a contract-first, stateless API with clear module boundaries and stable DTOs.

### Architectural principles

1. **Feature-first packages**
   - Keep domain logic grouped by feature.
   - Avoid spreading one business capability across multiple unrelated packages.

2. **Contract stability**
   - Treat the frontend API contract as the compatibility layer.
   - Preserve field names, enum values, and pagination shapes expected by the frontend.

3. **JWT-based authentication**
   - Replace the current `X-User-Id` shortcut with bearer-token authentication.
   - Keep `X-User-Id` only as a temporary migration aid if needed.

4. **Persistence over memory**
   - Replace in-memory repositories with durable storage.
   - Use repositories as the boundary between application services and persistence.

5. **Consistent error handling**
   - All API errors should be JSON and should include at least `message`.
   - Validation failures should be easy for the frontend to display.

6. **Enum normalization**
   - All frontend-facing enum values must be mapped through the canonical Portuguese labels in `enum-mapping.json`.

## 5. Implementation roadmap

### Phase 0 — Contract alignment and technical baseline

**Goal:** Ensure the backend and frontend contract documents are aligned before deeper implementation work starts.

**Work items:**

- Confirm the canonical request/response shapes for:
  - auth login/register
  - user profile and stats
  - check-in create/feed/today/my/stats/comment/amen
  - prayers/intentions wall CRUD and state transitions
  - journal entries
- Document which fields are public API fields vs internal persistence fields
- Verify enum mappings for mysteries and intention tags
- Define how pagination is represented everywhere (`page`, `limit`, `total`, and items array)

**Deliverables:**

- Stable mapping between frontend DTOs and backend DTOs
- A short contract checklist for every controller

**Exit criteria:**

- Every endpoint in `frontend docs/frontend-api-contract.md` has a matching backend implementation plan
- No unresolved field-name mismatches remain

---

### Phase 1 — Authentication and user identity hardening

**Goal:** Move from demo identity handling to real authenticated identity.

**Work items:**

- Implement JWT issuance and validation
- Replace `X-User-Id` resolution with authenticated principal lookup
- Preserve `AuthResult` response shape expected by the frontend:
  - `accessToken`
  - `user`
- Harden password handling with secure hashing
- Add auth-specific validation and consistent error responses
- Add rate limiting or abuse controls on login/register endpoints

**Primary backend areas:**

- `auth/`
- `config/SecurityConfig`
- `config/WebMvcConfig` if custom argument resolution remains necessary
- `shared/GlobalExceptionHandler`

**Acceptance criteria:**

- `POST /auth/register` returns a token and user payload
- `POST /auth/login` returns a token and user payload
- All protected routes can identify the current user without `X-User-Id`
- Auth failures and validation failures return frontend-friendly JSON

---

### Phase 2 — Check-ins parity and dashboard support

**Goal:** Fully support the frontend’s check-in experience and dashboard summaries.

**Current backend surface:**

- `POST /checkins`
- `GET /checkins/feed`
- `GET /checkins/today`
- `GET /checkins/my`
- `GET /checkins/stats`
- `GET /checkins/{id}`
- `POST /checkins/{id}/amen`
- `POST /checkins/{id}/comments`
- `DELETE /checkins/{id}`

**Work items:**

- Ensure the create payload matches the frontend expectations:
  - `mystery`
  - `reflection`
  - `intentions`
  - `isPublic`
- Enforce one check-in per user per day
- Make feed pagination deterministic and stable
- Return the shapes expected by the frontend, especially for:
  - `amenCount`
  - `hasUserAmen`
  - `comments`
  - `createdAt`
- Make `/checkins/today` resilient for the dashboard and hero area
- Ensure stats are computed server-side, not mixed with local state
- Add any missing filtering or ordering needed by the dashboard timeline

**Acceptance criteria:**

- The check-in modal can submit entirely through the backend
- Dashboard views can read their status, streak, and activity from the backend
- The frontend no longer needs local-only check-in persistence

---

### Phase 3 — Users profile and stats completion

**Goal:** Make the user profile and streak/stat endpoints authoritative.

**Work items:**

- Keep `GET /users/me` aligned to the frontend profile contract
- Keep `PUT /users/me` aligned to editable profile fields
- Ensure `GET /users/me/stats` computes the values used in the hero and dashboard:
  - `currentStreak`
  - `longestStreak`
  - `totalCheckIns`
  - `lastCheckIn`
  - `favoriteMysteries`
- Clarify which fields are optional vs always present
- Make stats computation consistent with check-in persistence rules

**Acceptance criteria:**

- User profile data is sourced from backend storage
- Streak and check-in totals match check-in history
- The frontend can depend on these endpoints without local fallback

---

### Phase 4 — Prayers / intentions wall backend parity

**Goal:** Replace the local intentions wall with a full API-backed backend implementation.

**Current backend surface:**

- `POST /prayers`
- `GET /prayers`
- `GET /prayers/my`
- `GET /prayers/testimonials`
- `GET /prayers/{id}`
- `PUT /prayers/{id}`
- `POST /prayers/{id}/pray`
- `POST /prayers/{id}/answered`
- `DELETE /prayers/{id}`

**Work items:**

- Ensure the request model supports title, description, and category
- Support filtering by category, status, and search terms if required by the frontend
- Keep `prayingForCount`, `isUserPraying`, `isAnswered`, `answeredAt`, and `testimonial` consistent
- Decide whether the wall should expose only active items or also archived/answered items
- Make testimonial listing stable and paginated
- Validate permissions for editing and deletion

**Acceptance criteria:**

- The intentions wall can run fully from backend data
- “Pray for” and “answered” actions update persisted state correctly
- Testimonials can be read without local store dependency

---

### Phase 5 — Spiritual journal backend implementation

**Goal:** Add the journal feature as a server-backed capability.

**Required API shape from the frontend contract:**

- `POST /journal/entries`
- `GET /journal/entries?from=...&to=...`
- `PUT /journal/entries/{id}`
- `DELETE /journal/entries/{id}`

**Work items:**

- Create a new `journal/` feature package
- Define journal entry model, repository, service, and controller
- Support date-range queries and sorting
- Support the frontend fields:
  - `content`
  - `mood`
  - `tags`
  - `intentions`
  - `mystery`
- Ensure all journal routes are auth-protected
- Decide whether export-ready retrieval requires additional endpoints or can be handled in the list response

**Acceptance criteria:**

- The spiritual journal no longer depends on Zustand-only local persistence
- Users can create, update, list, and delete entries through the API

---

### Phase 6 — Persistence, observability, and production hardening

**Goal:** Turn the application into a production-ready backend service.

**Work items:**

- Replace all remaining in-memory repositories
- Add database migrations and schema management
- Add request logging and tracing hooks
- Expose readiness and health endpoints consistently
- Review CORS policy for the frontend origin
- Add integration tests for the full API contract
- Add regression tests for enum mappings and validation edge cases
- Review performance of feed, stats, and paginated endpoints

**Acceptance criteria:**

- Backend state survives restarts
- Health endpoints are suitable for deployment
- The API contract is tested end-to-end

## 6. Cross-cutting technical workstreams

### API contract and DTO mapping

Maintain a clear boundary between internal models and external API payloads. Do not expose persistence-specific types directly if they can drift from the frontend contract.

### Validation and error handling

Ensure all validation errors produce a predictable response, preferably using the same `message` field shape across the API. This is especially important for auth forms, check-in submission, and journal entry editing.

### Enum mapping

Use `frontend docs/enum-mapping.json` as the source for all user-visible enum values. This is critical for:

- Rosary mysteries
- Intention tags
- Any future frontend adapter normalization

### Security

Migrate from permissive demo access to authenticated, stateless bearer-token access. Keep authorization checks aligned with ownership rules for profile, check-ins, prayers, and journal entries.

### Testing strategy

Prioritize the following tests:

- Controller tests for contract shape
- Service tests for business rules
- Integration tests for persistence and security
- Contract tests for pagination, errors, and enum values

## 7. Recommended execution order

1. Stabilize the contract and data mapping.
2. Complete authentication and identity.
3. Finalize check-ins and user stats.
4. Finish the prayers / intentions wall.
5. Implement the journal backend.
6. Swap local frontend persistence to API calls.
7. Harden persistence, observability, and deployment readiness.

## 8. Key risks

- **Enum drift:** frontend and backend labels can diverge if mapping is not centralized.
- **Hybrid state:** local fallback plus backend persistence can produce inconsistent streaks or timeline data.
- **Auth migration complexity:** moving from `X-User-Id` to JWT must be coordinated with frontend updates.
- **Pagination mismatches:** inconsistent `page`/`limit`/`total` semantics can break lists and infinite scrolling.
- **Contract regressions:** changing response fields without updating the frontend adapters will cause silent UI breakage.

## 9. Definition of done

The backend roadmap is complete when:

- `NEXT_PUBLIC_USE_BACKEND=true` works without local fallback for supported features
- Auth, user profile, stats, check-ins, feed, prayers, and journal are server-backed
- All contract files in `frontend docs/` match the live backend behavior
- Errors, pagination, and enum values are consistent across the API
- The backend can be deployed and tested independently of the frontend state

