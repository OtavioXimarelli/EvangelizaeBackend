# Backend/Frontend Contract Checklist

**Issue:** #8  
**Status:** ✅ Completed  
**Last updated:** 2026-04-29

---

## Canonical decisions (locked)

- [x] IDs for check-ins and prayers are exposed as `_id` in JSON.
- [x] Pagination shape is canonical across list endpoints: `{ items, total, page, limit }`.
- [x] Mystery/intention enums serialize to canonical Portuguese labels and accept frontend keys.
- [x] Error payload keeps the frontend-compatible shape: `{ "message": "..." }` (or message array).
- [x] Authenticated routes resolve the current user from `Authorization: Bearer <token>` (with dev fallback `X-User-Id`).

---

## Controller-by-controller checklist

### AuthController (`/auth`)
- [x] `POST /auth/register` request/response shape aligned.
- [x] `POST /auth/login` request/response shape aligned.
- [x] User summary fields aligned (`id`, `name`, `email`, `avatarUrl`, streak metrics).

**Status:** ✅ Fully aligned

### CheckInsController (`/checkins`)
- [x] `POST /checkins` request/response shape aligned (`_id` mapped).
- [x] `GET /checkins/feed` pagination aligned (`items`, `total`, `page`, `limit`).
- [x] `GET /checkins/today` response aligned (`hasCheckedIn`, `checkIn`).
- [x] `GET /checkins/my` pagination aligned (`items`, `total`, `page`, `limit`).
- [x] `GET /checkins/stats` shape documented and aligned (`totalCheckIns`, `publicCheckIns`).
- [x] `POST /checkins/{id}/amen` response aligned (`amenCount` only).
- [x] `POST /checkins/{id}/comments` request/response shape aligned.

**Status:** ✅ Fully aligned

### UsersController (`/users`)
- [x] `GET /users/me` profile shape aligned.
- [x] `GET /users/me/stats` shape aligned (`currentStreak`, `longestStreak`, `totalCheckIns`, `lastCheckIn`, `favoriteMysteries`).
- [x] `PUT /users/me` request/response shape aligned.

**Status:** ✅ Fully aligned

### PrayersController (`/prayers`)
- [x] `POST /prayers` request/response shape aligned (`_id` mapped).
- [x] `GET /prayers` pagination aligned (`items`, `total`, `page`, `limit`).
- [x] `GET /prayers/my` pagination aligned (`items`, `total`, `page`, `limit`).
- [x] `GET /prayers/testimonials` pagination aligned (`items`, `total`, `page`, `limit`).
- [x] `GET /prayers/{id}` aligned.
- [x] `PUT /prayers/{id}` aligned.
- [x] `POST /prayers/{id}/pray` aligned.
- [x] `POST /prayers/{id}/answered` aligned.
- [x] `DELETE /prayers/{id}` aligned.

**Status:** ✅ Fully aligned

### JournalController (`/journal`)
- [x] `POST /journal/entries` implemented and aligned.
- [x] `GET /journal/entries` implemented and aligned with canonical pagination.
- [x] `PUT /journal/entries/{id}` implemented and aligned.
- [x] `DELETE /journal/entries/{id}` implemented and aligned.

**Status:** ✅ Fully aligned

---

## DTO boundary notes (frontend-facing vs internal)

| DTO | Frontend-facing contract fields | Internal/non-contract fields |
|---|---|---|
| `AuthResult` / `UserSummary` | `accessToken`, `id`, `name`, `email`, `avatarUrl`, streak metrics | Password hash, auth internals |
| `CheckInView` | `_id`, `userId`, `userName`, `userAvatar`, `mystery`, `reflection`, `intentions`, `comments`, `amenCount`, `hasUserAmen`, `createdAt` | `amens` user-id set and persistence metadata are internal details |
| `AmenCountResponse` | `amenCount` | N/A |
| `UserProfile` | `id`, `name`, `email`, `avatarUrl`, `bio`, `createdAt` | Internal persistence metadata |
| `UserStats` | `currentStreak`, `longestStreak`, `totalCheckIns`, `lastCheckIn`, `favoriteMysteries` | Internal computation details |
| `PrayerRequestView` | `_id`, `userId`, `userName`, `userAvatar`, `title`, `description`, `category`, `prayingForCount`, `isUserPraying`, `isActive`, `isAnswered`, `answeredAt`, `testimonial`, `createdAt` | Internal praying user-id set and persistence metadata |
| `JournalEntryView` | `id`, `date`, `content`, `mood`, `tags`, `intentions`, `mystery` | Internal persistence metadata |

---

## Acceptance criteria audit

- [x] Every endpoint in `frontend docs/frontend-api-contract.md` has a matching backend implementation.
- [x] No unresolved field-name mismatches remain.
- [x] Public DTO boundaries and internal fields are documented.
- [x] Enum mappings are explicit and consistent with `frontend docs/enum-mapping.json`.
- [x] Pagination representation is consistent across check-ins, prayers, and journal.

