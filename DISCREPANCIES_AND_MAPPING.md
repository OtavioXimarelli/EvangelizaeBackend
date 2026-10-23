# Backend/Frontend Contract Discrepancies & Mapping Guide

> Historical analysis snapshot from before contract alignment was finished.  
> For current status, use `CONTRACT_CHECKLIST.md` and `CONTRACT_ALIGNMENT_SUMMARY.md`.

This document details every mismatch between the frontend API contract and current backend implementation, along with recommended fixes.

---

## 🔴 CRITICAL ISSUES (Block Usage)

### Issue #1: ID Field Naming Mismatch

**Severity:** 🔴 **CRITICAL** - Deserialization will fail

**Affected Entities:**
- CheckInView
- PrayerRequestView
- JournalEntry (future)

**Problem:**
```
Frontend Contract: "_id": "c1"
Backend Response:  "id": "c1"
```

**Impact:**
- Frontend cannot deserialize ID field
- Check-ins and prayers won't display
- User experience completely broken

**Root Cause:**
- MongoDB typically uses `_id` as primary key
- Java backend uses `id` naming convention
- No JSON serialization mapping applied

**Solution Options:**

**Option A: Add @JsonProperty Annotation (RECOMMENDED)**
```java
public record CheckInView(
    @JsonProperty("_id")
    String id,
    // ... other fields
) {}
```

**Option B: Custom JSON Serializer**
```java
@JsonSerialize(using = CheckInViewSerializer.class)
public record CheckInView(...) {}
```

**Option C: Jackson ObjectMapper Configuration**
```java
@Configuration
public class JacksonConfig {
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.registerModule(new JavaTimeModule());
        // Custom id field mapping
        return mapper;
    }
}
```

**Recommendation:** Use Option A (@JsonProperty) - simplest, most explicit
**Effort:** 30 minutes
**Affected Files:**
- `CheckInView.java` - Add @JsonProperty on `id` field
- `PrayerRequestView.java` - Add @JsonProperty on `id` field

**Status:** 🟡 READY TO IMPLEMENT

---

### Issue #2: Pagination Format Mismatch

**Severity:** 🔴 **CRITICAL** - Frontend cannot parse paginated responses

**Affected Endpoints:**
- GET /checkins/feed
- GET /checkins/my
- GET /checkins/stats (if paginated)
- GET /prayers
- GET /prayers/my
- GET /prayers/testimonials
- GET /journal/entries (future)

**Problem:**

**Current Backend Response (PagedResult):**
```json
{
  "items": [ { "...": "CheckInView" } ],
  "total": 42,
  "page": 1,
  "limit": 10
}
```

**Frontend Contract Expectation:**
```json
{
  "checkIns": [ { "...": "CheckInView" } ],
  "total": 42,
  "page": 1,
  "totalPages": 5
}
```

**Issue Details:**
1. ❌ Array field name differs (`items` vs endpoint-specific like `checkIns`, `prayers`, `entries`)
2. ❌ Backend returns `limit` but frontend needs `totalPages`
3. ❌ Inconsistency: some endpoints may use different field names

**Impact:**
- Frontend iteration over results fails ("items" not defined in model)
- Pagination calculation breaks (can't compute current page vs total)
- User can't navigate between pages

**Root Cause:**
- Generic `PagedResult<T>` used across all endpoints
- Contract specifies endpoint-specific field names
- totalPages not calculated (would be: ceil(total / limit))

**Solution Options:**

**Option A: Use Endpoint-Specific DTOs (RECOMMENDED)**
```java
// For Check-ins
public record CheckInFeedResponse(
    @JsonProperty("checkIns")
    List<CheckInView> items,
    long total,
    int page,
    int limit
) {
    public CheckInFeedResponse(PagedResult<CheckInView> paged) {
        this(paged.items(), paged.total(), paged.page(), paged.limit());
    }
    
    @JsonProperty("totalPages")
    public int getTotalPages() {
        return (int) Math.ceil((double) total / limit);
    }
}

// In CheckInsController
@GetMapping("/checkins/feed")
public CheckInFeedResponse getFeed(...) {
    return new CheckInFeedResponse(checkInService.getPublicFeed(...));
}
```

**Option B: Modify PagedResult to be Flexible**
```java
public record PagedResult<T>(
    List<T> items,
    long total,
    int page,
    int limit
) {
    @JsonProperty("totalPages")
    public int getTotalPages() {
        return (int) Math.ceil((double) total / limit);
    }
}

// Requires controller-level transformation for field naming
```

**Option C: Contract-Specific Wrapper Factory**
```java
public class PageResponseFactory {
    public static <T> Map<String, Object> checkInFeedResponse(PagedResult<T> paged) {
        return Map.of(
            "checkIns", paged.items(),
            "total", paged.total(),
            "page", paged.page(),
            "totalPages", (int) Math.ceil((double) paged.total() / paged.limit())
        );
    }
}
```

**Recommendation:** Use Option A with endpoint-specific response DTOs - clearest intent, type-safe
**Effort:** 2-3 hours (one DTO per endpoint type)
**Affected Files:**
- CheckInsController.java - 2 endpoints (feed, my)
- UsersController.java - 1 endpoint (stats if paginated)
- PrayersController.java - 3 endpoints (list, my, testimonials)

**Status:** 🟡 READY TO IMPLEMENT

---

### Issue #3: Enum JSON Serialization Missing

**Severity:** 🔴 **CRITICAL** - Request parsing fails for enums

**Affected Enums:**
- MysteryType
- IntentionTag

**Problem:**

**Creating a Check-in (POST /checkins):**

**Frontend Sends:**
```json
{
  "mystery": "Mistérios Gozosos",  // Portuguese string from enum-mapping.json
  "intentions": ["Família", "Paz"]  // Portuguese strings
}
```

**Backend Expects:**
```java
public enum MysteryType {
    MISTERIOS_GOZOSOS,  // CONSTANT_CASE enum constant
    MISTERIOS_DOLOROSOS,
    MISTERIOS_GLORIOSOS,
    MISTERIOS_LUMINOSOS
}
```

**What Happens Now:**
- Deserialization fails: Can't convert "Mistérios Gozosos" to MISTERIOS_GOZOSOS
- 400 Bad Request thrown
- Create check-in fails at first attempt

**Root Cause:**
- Enum-mapping.json specifies Portuguese display names
- Backend enums use CONSTANT_CASE names
- No @JsonValue or custom deserializer configured

**Enum-Mapping Reference (from enum-mapping.json):**

**MysteryType:**
```
Frontend → Display Name → Backend Enum
joyful → "Mistérios Gozosos" → MISTERIOS_GOZOSOS
sorrowful → "Mistérios Dolorosos" → MISTERIOS_DOLOROSOS
glorious → "Mistérios Gloriosos" → MISTERIOS_GLORIOSOS
luminous → "Mistérios Luminosos" → MISTERIOS_LUMINOSOS
```

**IntentionTag:**
```
family → "Família" → FAMILIA
peace → "Paz" → PAZ
health → "Saúde" → SAUDE
gratitude → "Pessoal" → PESSOAL
work → "Trabalho" → TRABALHO
faith → "Estudos" | "Vocação" | "Igreja" → {ESTUDOS, VOCACAO, IGREJA}
healing → "Conversão" → CONVERSAO
```

**Solution:**

**Option A: Add @JsonValue and @JsonCreator (RECOMMENDED)**

For MysteryType:
```java
public enum MysteryType {
    MISTERIOS_GOZOSOS("Mistérios Gozosos"),
    MISTERIOS_DOLOROSOS("Mistérios Dolorosos"),
    MISTERIOS_GLORIOSOS("Mistérios Gloriosos"),
    MISTERIOS_LUMINOSOS("Mistérios Luminosos");

    private final String displayName;

    MysteryType(String displayName) {
        this.displayName = displayName;
    }

    @JsonValue  // Serialize to Portuguese
    public String getDisplayName() {
        return displayName;
    }

    @JsonCreator  // Deserialize from Portuguese
    public static MysteryType fromDisplayName(String displayName) {
        for (MysteryType type : values()) {
            if (type.displayName.equals(displayName)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown mystery type: " + displayName);
    }
}
```

For IntentionTag:
```java
public enum IntentionTag {
    FAMILIA("Família"),
    PAZ("Paz"),
    SAUDE("Saúde"),
    TRABALHO("Trabalho"),
    ESTUDOS("Estudos"),
    VOCACAO("Vocação"),
    CONVERSAO("Conversão"),
    IGREJA("Igreja"),
    FIEIS_DEFUNTOS("Fiéis Defuntos"),
    PESSOAL("Pessoal");

    private final String displayName;

    IntentionTag(String displayName) {
        this.displayName = displayName;
    }

    @JsonValue
    public String getDisplayName() {
        return displayName;
    }

    @JsonCreator
    public static IntentionTag fromDisplayName(String displayName) {
        for (IntentionTag tag : values()) {
            if (tag.displayName.equals(displayName)) {
                return tag;
            }
        }
        throw new IllegalArgumentException("Unknown intention tag: " + displayName);
    }
}
```

**Option B: Custom Module Registration**
```java
@Configuration
public class EnumDeserializerConfig {
    @Bean
    public Module customEnumModule() {
        SimpleModule module = new SimpleModule();
        module.addDeserializer(MysteryType.class, new EnumDeserializer(MysteryType.class));
        module.addDeserializer(IntentionTag.class, new EnumDeserializer(IntentionTag.class));
        return module;
    }
}

class EnumDeserializer extends StdDeserializer<Enum<?>> {
    // Implementation...
}
```

**Recommendation:** Use Option A with @JsonValue/@JsonCreator - declarative, type-safe, no extra config needed
**Effort:** 1 hour
**Affected Files:**
- `MysteryType.java` - Add display names, @JsonValue, @JsonCreator
- `IntentionTag.java` - Same changes

**Testing Needed:**
```java
// Serialization (Enum → JSON)
assertEquals("Mistérios Gozosos", objectMapper.writeValueAsString(MISTERIOS_GOZOSOS));

// Deserialization (JSON → Enum)
MysteryType type = objectMapper.readValue("\"Mistérios Gozosos\"", MysteryType.class);
assertEquals(MISTERIOS_GOZOSOS, type);
```

**Status:** 🟡 READY TO IMPLEMENT

---

## 🟠 HIGH PRIORITY ISSUES (Incomplete Contracts)

### Issue #4: UserStats Missing Fields

**Severity:** 🟠 **HIGH** - Stats page won't display complete information

**Current Response:**
```json
{
  "currentStreak": 7,
  "longestStreak": 20,
  "totalCheckIns": 55
}
```

**Contract Requirement:**
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

**Missing Fields:**
1. ❌ `lastCheckIn` (Instant)
2. ❌ `favoriteMysteries` (List<{mystery, count}>)

**Current File:**
```java
// UserStats.java
public record UserStats(
    int currentStreak,
    int longestStreak,
    int totalCheckIns
) {}
```

**Solution:**

**Step 1: Update UserStats Record**
```java
public record UserStats(
    int currentStreak,
    int longestStreak,
    int totalCheckIns,
    Instant lastCheckIn,
    List<FavoriteMystery> favoriteMysteries
) {}

public record FavoriteMystery(
    String mystery,
    int count
) {}
```

**Step 2: Update UserApplicationService.getStats()**
```java
public UserStats getStats(String userId) {
    User user = userRepository.findById(userId);
    if (user == null) {
        user = User.createDemo();
    }
    
    // Query check-ins to compute stats
    List<CheckIn> userCheckIns = checkInRepository.findByUserId(userId);
    
    Instant lastCheckIn = userCheckIns.stream()
        .map(CheckIn::getCreatedAt)
        .max(Instant::compareTo)
        .orElse(null);
    
    // Group by mystery and count
    Map<MysteryType, Integer> mysteryCounts = userCheckIns.stream()
        .collect(Collectors.groupingBy(
            CheckIn::getMystery,
            Collectors.summingInt(c -> 1)
        ));
    
    List<FavoriteMystery> favorites = mysteryCounts.entrySet().stream()
        .map(e -> new FavoriteMystery(e.getKey().getDisplayName(), e.getValue()))
        .sorted((a, b) -> b.count() - a.count())
        .limit(5)  // Top 5
        .toList();
    
    return new UserStats(
        user.getCurrentStreak(),
        user.getLongestStreak(),
        userCheckIns.size(),
        lastCheckIn,
        favorites
    );
}
```

**Effort:** 2-3 hours
**Affected Files:**
- `UserStats.java` - Add fields
- `FavoriteMystery.java` - New record
- `UserApplicationService.java` - Implement computation logic
- `UserRepository.java` - May need query optimization

**Status:** 🟡 READY TO IMPLEMENT

---

### Issue #5: Amen Endpoint Response Mismatch

**Severity:** 🟠 **HIGH** - Response type breaks frontend expectations

**Current Behavior:**
```
POST /checkins/{id}/amen
→ 200 OK
→ Returns: Full CheckInView object
```

**Contract Expectation:**
```
POST /checkins/{id}/amen
→ 200 OK
→ Returns: { "amenCount": 5 }
```

**Current Implementation:**
```java
@PostMapping("/checkins/{id}/amen")
public CheckInView toggleAmen(
        @PathVariable String id,
        @RequestHeader(value = "X-User-Id", required = false) String userId) {
    return checkInService.toggleAmen(id, resolveUser(userId));
}
```

**Problem:**
- Frontend expects minimal response with just amenCount
- Backend returns entire CheckInView
- Frontend UI may expect lightweight response for performance
- Over-fetching data not needed for this operation

**Solution:**

**Option A: Minimal Response DTO (RECOMMENDED)**
```java
public record AmenResponse(
    @JsonProperty("amenCount")
    int amenCount
) {}

// In CheckInsController
@PostMapping("/checkins/{id}/amen")
public AmenResponse toggleAmen(
        @PathVariable String id,
        @RequestHeader(value = "X-User-Id", required = false) String userId) {
    CheckInView updated = checkInService.toggleAmen(id, resolveUser(userId));
    return new AmenResponse(updated.amenCount());
}
```

**Option B: Keep Full Response but Document**
```java
// If frontend actually needs full updated object for UI:
// Add comment to CheckInsController explaining return value
```

**Recommendation:** Use Option A (minimal response) - matches contract, more efficient
**Effort:** 1 hour
**Affected Files:**
- `AmenResponse.java` - New DTO
- `CheckInsController.java` - Modify toggleAmen method

**Testing:**
```java
// POST /checkins/{id}/amen
// Response body should be: { "amenCount": 5 }
```

**Status:** 🟡 READY TO IMPLEMENT

---

### Issue #6: UserProfile Over-sharing Fields

**Severity:** 🟠 **MEDIUM-HIGH** - Data visibility unclear

**Current Response (GET /users/me):**
```json
{
  "id": "u1",
  "name": "Maria",
  "email": "maria@example.com",
  "avatarUrl": null,
  "bio": null,
  "createdAt": "2026-04-01T00:00:00Z",
  "currentStreak": 7,
  "longestStreak": 20,
  "totalCheckIns": 55,
  "lastCheckIn": "2026-04-16T12:00:00Z"
}
```

**Contract (Frontend Docs):**
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

**Extra Fields in Current Response:**
- ❌ `currentStreak` (should be in UserStats endpoint only)
- ❌ `longestStreak` (should be in UserStats endpoint only)
- ❌ `totalCheckIns` (should be in UserStats endpoint only)
- ❌ `lastCheckIn` (internal computation, not needed in profile)

**Issue:**
- Duplicates data from UserStats endpoint
- Breaks separation of concerns
- Frontend doesn't expect these fields
- May confuse frontend developer about canonical source for stats

**Current Code:**
```java
public record UserProfile(
    String id,
    String name,
    String email,
    String avatarUrl,
    String bio,
    int currentStreak,
    int longestStreak,
    int totalCheckIns,
    Instant lastCheckIn,
    Instant createdAt
) {}
```

**Solution:**

**Option A: Remove Extra Fields (RECOMMENDED)**
```java
public record UserProfile(
    String id,
    String name,
    String email,
    String avatarUrl,
    String bio,
    Instant createdAt
) {}
```

**Option B: Add Fields to Contract**
If frontend actually needs these, update the contract documentation.

**Recommendation:** Remove fields - they belong in UserStats
**Effort:** 30 minutes
**Affected Files:**
- `UserProfile.java` - Remove 4 fields
- `UserApplicationService.java` - Don't populate removed fields
- May need frontend adjustment if it relies on these fields

**Status:** 🟡 READY TO IMPLEMENT

---

## 🟡 MEDIUM PRIORITY ISSUES (Data Clarity)

### Issue #7: CheckInView Extra Fields

**Severity:** 🟡 **MEDIUM** - Unclear if these are public API contract

**Extra Fields in CheckInView:**
1. `isPublic` - Is this public contract data?
2. `prayerDuration` - Not in contract, is it a feature?
3. `amens` (List<String>) - Internal detail, frontend only needs count

**Current CheckInView:**
```java
public record CheckInView(
    String id,
    String userId,
    String userName,
    String userAvatar,
    MysteryType mystery,
    String reflection,
    List<String> intentions,
    List<String> amens,          // ← Internal detail?
    int amenCount,               // ← Canonical count
    List<CommentView> comments,
    boolean isPublic,            // ← Unclear
    Integer prayerDuration,      // ← Unclear
    Instant createdAt,
    boolean hasUserAmen
) {}
```

**Questions:**
1. Should `isPublic` be returned to client? Why?
2. Should `prayerDuration` be public? Is this a feature?
3. Should `amens` list be included? Frontend only uses `amenCount`

**Analysis:**
- `isPublic`: Might be needed to show if check-in is private to user
- `prayerDuration`: Could be user feature to track prayer length
- `amens`: Over-exposing internal implementation

**Recommendation:**
1. Document intent for `isPublic` in contract
2. Document intent for `prayerDuration` in contract
3. Consider removing `amens` list (redundant with `amenCount`)

**Effort:** 1-2 hours (mostly documentation)
**Status:** ⏳ NEEDS DECISION FROM TEAM

---

### Issue #8: CreateCheckInRequest Optional Fields

**Severity:** 🟡 **MEDIUM** - Field nullability unclear

**Current Request:**
```java
public record CreateCheckInRequest(
    @NotNull MysteryType mystery,
    String reflection,              // ← Optional?
    List<String> intentions,        // ← Optional?
    Boolean isPublic,               // ← Optional? Default?
    @Min(1) @Max(180) Integer prayerDuration  // ← Optional?
) {}
```

**Issues:**
- No clear defaults specified
- Frontend doesn't document default values
- Backend behavior for null/empty values unclear

**Questions:**
1. If `reflection` is null, what happens?
2. If `intentions` is empty list, is that valid?
3. If `isPublic` is not provided, default to true or false?
4. If `prayerDuration` not provided, what's default?

**Current Contract (Silent):**
```json
{
  "mystery": "Mistérios Gozosos",
  "reflection": "optional text",
  "intentions": ["Família", "Paz"],
  "isPublic": true
}
```

**Recommendation:**
Update contract to document:
- Default values for optional fields
- Validation rules (min/max lengths, etc.)
- Null vs empty list behavior

**Effort:** 30 minutes (documentation)
**Status:** ⏳ NEEDS DOCUMENTATION

---

## 🔵 LOW PRIORITY ISSUES (Implementation Details)

### Issue #9: Undocumented Endpoints

**Severity:** 🔵 **LOW** - Implementation details not breaking

**Endpoints in backend but not in frontend contract:**

1. `GET /checkins/{id}` - Get single check-in
   - Missing from contract, but useful for detail page
   - Recommend adding to contract

2. `DELETE /checkins/{id}` - Delete check-in
   - Missing from contract, but standard CRUD
   - Recommend adding to contract

3. `GET /users/{id}` - Get any user's profile
   - Missing from contract, for user profiles feature
   - Recommend adding to contract

**Action:** Update contract documentation to include these

**Effort:** 30 minutes (documentation)
**Status:** ⏳ DOCUMENTATION ONLY

---

## ❌ FEATURE GAPS (Not Implemented)

### Issue #10: Journal CRUD Not Implemented

**Severity:** ⚠️ **CRITICAL** - Complete feature missing

**Required Endpoints:**
1. `POST /journal/entries` - Create entry
2. `GET /journal/entries?from=...&to=...` - List by date range
3. `PUT /journal/entries/{id}` - Update entry
4. `DELETE /journal/entries/{id}` - Delete entry

**Expected Entry DTO:**
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

**Status:** ❌ NO IMPLEMENTATION FOUND

**Implementation Tasks:**
- [ ] Create JournalEntry entity class
- [ ] Create JournalEntryRepository (MongoDB)
- [ ] Create JournalApplicationService
- [ ] Create JournalController with 4 endpoints
- [ ] Add date range query support
- [ ] Write tests

**Effort:** 6-8 hours
**Priority:** High (blocks full migration)

---

## Summary Table: All Issues

| # | Issue | Severity | Impact | Effort | Status |
|---|-------|----------|--------|--------|--------|
| 1 | ID field naming (_id vs id) | 🔴 CRITICAL | Deserialization fails | 30m | Ready |
| 2 | Pagination format mismatch | 🔴 CRITICAL | Can't parse responses | 2-3h | Ready |
| 3 | Enum JSON serialization | 🔴 CRITICAL | Create/update fails | 1h | Ready |
| 4 | UserStats missing fields | 🟠 HIGH | Incomplete stats | 2-3h | Ready |
| 5 | Amen endpoint over-response | 🟠 HIGH | Frontend expectation | 1h | Ready |
| 6 | UserProfile over-sharing | 🟠 HIGH | Data visibility | 30m | Ready |
| 7 | CheckInView unclear fields | 🟡 MEDIUM | Documentation gap | 1-2h | Decision needed |
| 8 | Optional field defaults | 🟡 MEDIUM | Behavior unclear | 30m | Documentation |
| 9 | Undocumented endpoints | 🔵 LOW | Documentation gap | 30m | Documentation |
| 10 | Journal not implemented | ⚠️ CRITICAL | Feature missing | 6-8h | Ready |

**Total Effort to Full Alignment:** ~18-20 hours

---

## Implementation Roadmap

### Phase 1: Critical Fixes (4-5 hours)
- [ ] Add @JsonProperty("_id") to ID fields
- [ ] Create endpoint-specific pagination DTOs
- [ ] Add @JsonValue/@JsonCreator to enums

### Phase 2: High Priority (3-4 hours)
- [ ] Add lastCheckIn + favoriteMysteries to UserStats
- [ ] Fix amen endpoint response
- [ ] Trim UserProfile fields

### Phase 3: Features (6-8 hours)
- [ ] Implement Journal CRUD endpoints

### Phase 4: Polish (1-2 hours)
- [ ] Update API documentation
- [ ] Add integration tests
- [ ] Verify all enum mappings

---

**Next Action:** Review this document with backend team and prioritize implementation.
