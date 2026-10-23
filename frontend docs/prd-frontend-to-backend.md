# PRD — Frontend Requirements for Java Backend

## 1. Purpose

Define the backend product requirements needed for the Java/Spring Boot backend to fully support the current frontend experience in `frontend/`, replacing local-only storage where applicable.

## 2. Product context

- Frontend stack: Next.js + React Query + Zustand + localStorage fallback.
- Current backend switch flags:
  - `NEXT_PUBLIC_USE_BACKEND=true`
  - `NEXT_PUBLIC_API_URL=http://localhost:3001/api`
- Auth token storage key: `rosario-access-token`.
- Current auth gate in frontend is disabled (`AUTH_DISABLED=true`), but backend contract is already coded.

## 3. Goals

1. Provide stable API contracts for existing frontend calls (auth, check-ins, feed, stats).
2. Add missing APIs for local-only features (intentions wall, spiritual journal, full user prayer timeline).
3. Preserve UI expectations (field names, pagination behavior, message/error shape).
4. Enable phased migration with minimal frontend rewrites.

## 4. Frontend feature inventory and backend needs

| Feature | Current frontend data source | Backend requirement |
|---|---|---|
| Login/Register | API-ready (`/auth/register`, `/auth/login`) | Keep contract stable + JWT |
| Hero status + streak | API-ready fallback to local | `/checkins/today`, `/users/me/stats` |
| Community feed + amen + comments | API-ready | `/checkins/feed`, `/checkins/{id}/amen`, `/checkins/{id}/comments` |
| Daily check-in modal | Local store only today | Must switch to API submit (`POST /checkins`) |
| Dashboard personal timeline | Local store only today | Need `/checkins/my` + optional date filters |
| Intentions Wall (`mural-intencoes`) | Local Zustand only | Need full prayers endpoints (create/list/update/pray/answered/delete) |
| Spiritual Journal | Local Zustand only | Need journal CRUD endpoints |

## 5. Functional requirements (MVP for backend parity)

### Auth
- Register by email/password with auto-login response token.
- Login by email/password.
- Response shape must include:
  - `accessToken`
  - `user: { id, name, email?, avatarUrl?, currentStreak, longestStreak, totalCheckIns }`

### Check-ins
- Create daily check-in with:
  - `mystery` (Portuguese label in current frontend mapping)
  - optional `reflection` (max 500 chars expected by UI)
  - optional `intentions[]`
  - `isPublic`
- Prevent duplicate check-in per day/user.
- Feed endpoint with pagination.
- Amen toggle endpoint with updated count.
- Comment endpoint returning updated check-in.
- Stats endpoint returning streak metrics and favorite mysteries.

### Users
- `GET /users/me` profile.
- `PUT /users/me` profile update.
- `GET /users/me/stats` for dashboard/hero.

### Prayers / Intentions Wall
- CRUD + "pray for" toggle + "mark answered".
- Filters by category/status/search.
- Include `prayingForCount`, `isAnswered`, `answeredAt`, `testimonial`.

### Spiritual Journal
- Entry CRUD for authenticated user.
- Date-based querying (today, range).
- Export-ready list retrieval.

## 6. Non-functional requirements

- **Latency target:** p95 < 400ms for read endpoints.
- **Availability:** 99.9% monthly for API.
- **Security:** JWT bearer auth, password hashing (bcrypt/argon2), rate limiting on auth endpoints.
- **Validation:** strict request validation with clear 4xx responses.
- **Observability:** request tracing, structured logs, health/readiness probes.

## 7. API behavior requirements

- Base path: `/api`
- Errors must be JSON with at least `message`.
- For validation errors, frontend accepts `message` as string or array of strings.
- Authenticated routes must accept `Authorization: Bearer <token>`.

## 8. Data and enum compatibility

Current frontend expects mapped Portuguese labels for check-ins:
- Mysteries: `Mistérios Gozosos`, `Mistérios Dolorosos`, `Mistérios Gloriosos`, `Mistérios Luminosos`
- Intention labels: `Família`, `Paz`, `Saúde`, `Trabalho`, `Estudos`, `Vocação`, `Conversão`, `Igreja`, `Fiéis Defuntos`, `Pessoal`

## 9. Rollout phases

1. **Phase 1 (already started):** Auth + check-ins + stats + feed contracts.
2. **Phase 2:** Wire CheckIn modal/dashboard screens to API endpoints.
3. **Phase 3:** Implement prayers wall backend and switch `mural-intencoes`.
4. **Phase 4:** Implement journal backend and switch `diario-espiritual`.
5. **Phase 5:** Disable local fallback and enforce backend mode in production.

## 10. Acceptance criteria

- Frontend works with `NEXT_PUBLIC_USE_BACKEND=true` with no local fallback required.
- Auth + check-ins + feed + stats + intentions + journal all persisted server-side.
- API responses match documented contract files in this docs folder.
