# Detailed Implementation Tasks: Rosary Backend

This document provides a technical breakdown of the implementation path. Each issue is designed to be self-contained and follows the architecture defined in the `TECHNICAL_ROADMAP.md`.

---

## Milestone 1: Data Persistence & Identity (Foundational)

### [Issue #1] Transition to MongoDB Persistence
**Description:** Replace volatile in-memory storage with MongoDB to ensure data durability across service restarts.

**Technical Specifications:**
- **Configuration:** 
  - Update `src/main/resources/application.properties` with `spring.data.mongodb.uri`.
  - Enable Mongo Auditing (`@EnableMongoAuditing`) to handle `createdAt` and `updatedAt` automatically.
- **Entity Mapping:**
  - Annotate `User`, `CheckIn`, and `PrayerRequest` with `@Document`.
  - Use `@Id` with `String` or `ObjectId` for the primary keys to match the frontend's expectation of string-based IDs.
  - Implement `@Indexed(unique = true)` on `User.email`.
- **Repository Layer:**
  - Create interfaces extending `MongoRepository<T, String>`.
  - Delete all `InMemory*Repository` classes and their associated boilerplate.
- **Service Layer:**
  - Inject the new `MongoRepository` beans into `UserApplicationService`, `AuthApplicationService`, etc.

**Definition of Done:**
- Application starts and connects to MongoDB.
- All CRUD operations currently in the app persist to the database.
- Integration tests (using `@DataMongoTest`) pass for core repository methods.

---

### [Issue #2] Production-Grade Security (JWT & BCrypt)
**Description:** Replace the `X-User-Id` header hack with stateless JWT authentication and secure password storage.

**Technical Specifications:**
- **Password Security:**
  - Inject `BCryptPasswordEncoder` into `AuthApplicationService`.
  - Remove the `"noop:"` prefix logic and implement real hashing during registration and verification during login.
- **JWT Implementation:**
  - Implement a `JwtProvider` utility to handle token generation and parsing (using `jjwt` or `spring-security-oauth2-resource-server`).
  - Create a `JwtAuthenticationFilter` (extending `OncePerRequestFilter`) to intercept requests, validate the `Authorization: Bearer <token>` header, and populate the `SecurityContextHolder`.
- **Security Configuration:**
  - Update `SecurityConfig.java` to:
    - Set `SessionCreationPolicy.STATELESS`.
    - Permit all on `/api/auth/**` and `/api/health`.
    - Require authentication for all other `/api/**` routes.
- **Context Handling:**
  - Update `CurrentUserArgumentResolver` to retrieve the user principal directly from `SecurityContextHolder.getContext().getAuthentication()`.

**Definition of Done:**
- `POST /auth/login` returns a valid JWT.
- Requests without a token to `/api/checkins` return `401 Unauthorized`.
- Passwords in the `users` collection are salted hashes, not plain text.

---

## Milestone 2: Core Activity & Analytics

### [Issue #3] Business Logic: Authoritative Check-ins
**Description:** Implement strict server-side rules for the daily Rosary check-in to ensure data integrity.

**Technical Specifications:**
- **Constraints:**
  - Implement a check in `CheckInApplicationService.create()` that verifies if the user has already submitted a check-in for the current date (UTC).
  - Use a MongoDB unique compound index on `(userId, date)` if possible, or a service-side lock/check.
- **Enum Validation:**
  - Validate `mystery` and `intentions` against the `enum-mapping.json`. Use a custom `@Validator` or strict enum mapping in the DTO.
- **Data Shape:**
  - Ensure the `CheckInView` returns the exact field names: `amenCount`, `hasUserAmen`, and `createdAt` in ISO-8601.

**Definition of Done:**
- Users cannot submit two check-ins for the same day.
- Invalid mystery labels are rejected with `400 Bad Request`.

---

### [Issue #4] Real-time Streak & Stats Calculation
**Description:** Implement the algorithmic logic to compute user engagement metrics authoritative on the server.

**Technical Specifications:**
- **Streak Algorithm:**
  - Implement logic in `UserApplicationService` to walk back through the `CheckIn` collection for a specific `userId`.
  - A streak is incremented if there is a check-in for `today` and `yesterday`.
  - Calculate `longestStreak` by finding the maximum consecutive date gap in the history.
- **Aggregations:**
  - Use MongoDB Aggregation Framework to calculate `favoriteMysteries` (Group by mystery, count, sort descending).
- **Optimization:**
  - Consider caching these stats or storing them incrementally in the `User` document to avoid full collection scans on every dashboard load.

**Definition of Done:**
- `GET /api/users/me/stats` returns accurate streak and favorite mystery data.
- The stats update immediately after a new check-in is saved.

---

## Milestone 3: Community & Social Features

### [Issue #5] Prayers Wall & Testimonials Logic
**Description:** Complete the implementation of the community intentions wall and the transition to answered prayers.

**Technical Specifications:**
- **Repository Implementation:**
  - Implement `PrayerRequestRepository` with support for `status` filtering (Active vs. Answered).
- **Interaction Logic:**
  - `POST /prayers/{id}/pray`: Implement an atomic increment using MongoDB `$inc` to update the `prayingForCount`.
  - `POST /prayers/{id}/answered`: Implement a transaction (or atomic update) that sets `isAnswered = true`, sets `answeredAt`, and optionally attaches a `testimonial` string.
- **Pagination:**
  - Ensure `GET /api/prayers` supports `page` and `limit` parameters using `Pageable` to prevent loading the entire wall into memory.

**Definition of Done:**
- Users can post a prayer and others can "pray" for it.
- Answered prayers appear in the testimonials feed.

---

## Milestone 4: Personal Features & Hardening

### [Issue #6] Spiritual Journal Module
**Description:** Implement the private journal feature for personal reflections with date-range support.

**Technical Specifications:**
- **Module Creation:**
  - New package: `dev.ximarelli.rosary.backend.journal`.
  - Entity: `JournalEntry` (fields: `userId`, `content`, `mood`, `tags`, `date`).
- **Range Queries:**
  - Implement `GET /api/journal/entries?from=...&to=...`.
  - Use MongoDB `$gte` and `$lte` operators on the `date` field.
- **Security:**
  - Every controller method must verify that the `JournalEntry.userId` matches the authenticated user ID.

**Definition of Done:**
- Users can save reflections.
- Range queries correctly return entries for the selected calendar period.
- Attempting to access another user's journal entry returns `403 Forbidden` or `404 Not Found`.

---

### [Issue #7] Final Contract Audit & Production Hardening
**Description:** Ensure the API is robust, consistent, and perfectly aligned with the frontend expectations.

**Technical Specifications:**
- **Global Error Handling:**
  - Refine `GlobalExceptionHandler` to catch `ConstraintViolationException` and `MethodArgumentNotValidException`.
  - Ensure all error responses follow the `{ "message": "error description" }` shape.
- **CORS & Headers:**
  - Configure `WebMvcConfigurer` to allow requests from the frontend origin (e.g., `localhost:3000`).
- **Logging:**
  - Add a `CommonsRequestLoggingFilter` or structured SLF4J logging for all API requests to aid in debugging production issues.

**Definition of Done:**
- Frontend `NEXT_PUBLIC_USE_BACKEND=true` works with zero console errors.
- All endpoints respond within <200ms for standard queries.
- No sensitive information (stack traces, internal IDs) is leaked in error responses.
