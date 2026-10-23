# backend-haveyouprayedtherosarytoday

Separated Spring Boot backend for **HaveYouPrayedTheRosaryToday**, configured for **Java 25**.

## Requirements

- JDK 25+ (project compiles with `--release 25`)
- Maven Wrapper (included)

## Run

```bash
./mvnw spring-boot:run
```

API base URL: `http://localhost:3001/api`  
Health check: `http://localhost:3001/api/health`

## Build

```bash
./mvnw clean package
```

## Lean feature-first architecture

Each feature is kept in a single package (for now), with only the classes needed:

- Controllers + request/response contracts
- Services + repositories
- Models/enums

Shared cross-feature concerns stay in `shared/`.

Implemented feature modules:

- `auth`
- `users`
- `checkins`
- `prayers`
- `journal`

## Boilerplate endpoint surface (matching previous backend)

- `POST /api/auth/register`
- `POST /api/auth/login`
- `GET /api/users/me`
- `PUT /api/users/me`
- `GET /api/users/me/stats`
- `GET /api/users/{id}`
- `POST /api/checkins`
- `GET /api/checkins/feed`
- `GET /api/checkins/today`
- `GET /api/checkins/my`
- `GET /api/checkins/stats`
- `GET /api/checkins/{id}`
- `POST /api/checkins/{id}/amen`
- `POST /api/checkins/{id}/comments`
- `DELETE /api/checkins/{id}`
- `POST /api/prayers`
- `GET /api/prayers`
- `GET /api/prayers/my`
- `GET /api/prayers/testimonials`
- `GET /api/prayers/{id}`
- `PUT /api/prayers/{id}`
- `POST /api/prayers/{id}/pray`
- `POST /api/prayers/{id}/answered`
- `DELETE /api/prayers/{id}`
- `POST /api/journal/entries`
- `GET /api/journal/entries`
- `PUT /api/journal/entries/{id}`
- `DELETE /api/journal/entries/{id}`

> For this boilerplate phase, authenticated routes use header `X-User-Id` (defaults to `demo-user`).
