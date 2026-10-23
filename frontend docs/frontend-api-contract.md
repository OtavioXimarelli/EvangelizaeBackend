# Frontend API Contract (Source of Truth for Java Backend)

## Base

- Base URL: `/api`
- Content type: `application/json`
- Auth header: `Authorization: Bearer <jwt>`
- Error shape:
```json
{ "message": "..." }
```
or
```json
{ "message": ["field error 1", "field error 2"] }
```

## Auth

### `POST /auth/register`
Request:
```json
{ "name": "maria", "email": "maria@example.com", "password": "secret123" }
```
Response `201`:
```json
{
  "accessToken": "jwt",
  "user": {
    "id": "u1",
    "name": "maria",
    "email": "maria@example.com",
    "avatarUrl": null,
    "currentStreak": 0,
    "longestStreak": 0,
    "totalCheckIns": 0
  }
}
```

### `POST /auth/login`
Request:
```json
{ "email": "maria@example.com", "password": "secret123" }
```
Response `200`: same shape as register.

## Check-ins

### `POST /checkins` (auth)
Request:
```json
{
  "mystery": "Mistérios Gozosos",
  "reflection": "optional text",
  "intentions": ["Família", "Paz"],
  "isPublic": true
}
```
Response `201`:
```json
{
  "_id": "c1",
  "userId": "u1",
  "userName": "Maria",
  "userAvatar": null,
  "mystery": "Mistérios Gozosos",
  "reflection": "optional text",
  "intentions": ["Família", "Paz"],
  "comments": [],
  "amenCount": 0,
  "hasUserAmen": false,
  "createdAt": "2026-04-17T00:00:00Z"
}
```

### `GET /checkins/today` (auth)
Response:
```json
{ "hasCheckedIn": true, "checkIn": { "...": "optional object" } }
```

### `GET /checkins/feed?page=1&limit=10`
Response:
```json
{
  "items": [ { "...": "BackendCheckIn" } ],
  "total": 42,
  "page": 1,
  "limit": 10
}
```

### `POST /checkins/{id}/amen` (auth)
Response:
```json
{ "amenCount": 5 }
```

### `POST /checkins/{id}/comments` (auth)
Request:
```json
{ "text": "Amém 🙏" }
```
Response: updated `BackendCheckIn`.

### `GET /checkins/my?page=1&limit=20` (auth)
Response: paged list of own check-ins (same item shape as feed) using `{ items, total, page, limit }`.

### `GET /checkins/stats` (auth)
Response:
```json
{
  "totalCheckIns": 55,
  "publicCheckIns": 20
}
```

## Users

### `GET /users/me/stats` (auth)
Response:
```json
{
  "currentStreak": 7,
  "longestStreak": 20,
  "totalCheckIns": 55,
  "lastCheckIn": "2026-04-16T12:00:00Z",
  "favoriteMysteries": [
    { "mystery": "Mistérios Dolorosos", "count": 18 }
  ]
}
```

### `GET /users/me` (auth)
Response:
```json
{
  "id": "u1",
  "name": "Maria",
  "email": "maria@example.com",
  "avatarUrl": null,
  "bio": null,
  "createdAt": "2026-04-01T00:00:00Z"
}
```

### `PUT /users/me` (auth)
Request:
```json
{ "name": "Maria Santos", "avatarUrl": "https://...", "bio": "..." }
```

## Prayers / Intentions Wall (needed for full migration)

### `POST /prayers` (auth)
### `GET /prayers?page=1&limit=20&category=...`
### `GET /prayers/my?page=1&limit=20` (auth)
### `GET /prayers/testimonials?page=1&limit=10`
### `GET /prayers/{id}`
### `PUT /prayers/{id}` (auth)
### `POST /prayers/{id}/pray` (auth)
### `POST /prayers/{id}/answered` (auth)
### `DELETE /prayers/{id}` (auth)

Item shape:
```json
{
  "_id": "p1",
  "userId": "u1",
  "userName": "Maria",
  "title": "Intenção",
  "description": "Detalhes",
  "category": "Família",
  "prayingForCount": 10,
  "isUserPraying": true,
  "isActive": true,
  "isAnswered": false,
  "answeredAt": null,
  "testimonial": null,
  "createdAt": "2026-04-17T00:00:00Z"
}
```

List endpoints under `/prayers` also use pagination in the canonical shape:
```json
{
  "items": [ { "...": "PrayerItem" } ],
  "total": 42,
  "page": 1,
  "limit": 20
}
```

## Journal (needed for full migration)

### `POST /journal/entries` (auth)
### `GET /journal/entries?from=...&to=...` (auth)
### `PUT /journal/entries/{id}` (auth)
### `DELETE /journal/entries/{id}` (auth)

Entry shape:
```json
{
  "id": "j1",
  "date": "2026-04-17T00:00:00Z",
  "content": "text",
  "mood": "peaceful",
  "tags": ["gratitude"],
  "intentions": "string",
  "mystery": "Mistérios Gozosos"
}
```

Journal list response uses canonical pagination:
```json
{
  "items": [ { "...": "JournalEntity" } ],
  "total": 10,
  "page": 1,
  "limit": 20
}
```
