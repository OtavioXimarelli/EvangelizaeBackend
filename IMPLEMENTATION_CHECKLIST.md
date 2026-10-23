# Backend/Frontend Contract Alignment - Implementation Checklist

## 🔴 CRITICAL ISSUES (Phase 1: 4-5 hours)

### Issue #1: ID Field Naming (_id vs id)
- [ ] Add @JsonProperty("_id") to CheckInView.id
- [ ] Add @JsonProperty("_id") to PrayerRequestView.id
- [ ] Test JSON serialization round-trip
- [ ] Verify frontend can deserialize

**Files to Modify:**
- `src/main/java/dev/ximarelli/rosary/backend/checkins/CheckInView.java`
- `src/main/java/dev/ximarelli/rosary/backend/prayers/PrayerRequestView.java`

**Testing:**
```bash
# Test that _id appears in JSON output
curl -H "X-User-Id: u1" http://localhost:8080/api/checkins/{id}
# Should see "\"_id\":" in response
```

---

### Issue #2: Pagination Format Standardization
- [ ] Create CheckInFeedResponse DTO
- [ ] Create PrayerFeedResponse DTO
- [ ] Add totalPages property (calculated from limit + total)
- [ ] Update all paginated endpoints to use new DTOs
- [ ] Test pagination with multiple pages

**Affected Endpoints:**
- GET /checkins/feed
- GET /checkins/my
- GET /prayers
- GET /prayers/my
- GET /prayers/testimonials
- GET /journal/entries (future)

**Files to Create:**
- `src/main/java/dev/ximarelli/rosary/backend/checkins/CheckInFeedResponse.java`
- `src/main/java/dev/ximarelli/rosary/backend/prayers/PrayerFeedResponse.java`
- `src/main/java/dev/ximarelli/rosary/backend/journal/JournalFeedResponse.java` (future)

**Testing:**
```bash
# Verify response structure
curl "http://localhost:8080/api/checkins/feed?page=1&limit=10"
# Should return: { "checkIns": [...], "total": X, "page": 1, "totalPages": Y }
```

---

### Issue #3: Enum JSON Serialization
- [ ] Add display name constructor to MysteryType
- [ ] Add @JsonValue and @JsonCreator to MysteryType
- [ ] Add display name constructor to IntentionTag
- [ ] Add @JsonValue and @JsonCreator to IntentionTag
- [ ] Test enum serialization (enum → JSON)
- [ ] Test enum deserialization (JSON → enum)

**Files to Modify:**
- `src/main/java/dev/ximarelli/rosary/backend/checkins/MysteryType.java`
- `src/main/java/dev/ximarelli/rosary/backend/prayers/IntentionTag.java`

**Testing:**
```java
// Serialization test
assertEquals("Mistérios Gozosos", objectMapper.writeValueAsString(MISTERIOS_GOZOSOS));

// Deserialization test
MysteryType type = objectMapper.readValue("\"Mistérios Gozosos\"", MysteryType.class);
assertEquals(MISTERIOS_GOZOSOS, type);
```

---

## 🟠 HIGH PRIORITY ISSUES (Phase 2: 3-4 hours)

### Issue #4: UserStats Missing Fields
- [ ] Add `lastCheckIn: Instant` field to UserStats
- [ ] Add `FavoriteMystery` record for { mystery, count }
- [ ] Add `favoriteMysteries: List<FavoriteMystery>` field to UserStats
- [ ] Update UserApplicationService.getStats() to compute fields
- [ ] Query user's check-ins to find last one
- [ ] Group by mystery type and count
- [ ] Sort mysteries by count and limit to top 5
- [ ] Test stats computation

**Files to Modify:**
- `src/main/java/dev/ximarelli/rosary/backend/users/UserStats.java`
- `src/main/java/dev/ximarelli/rosary/backend/users/UserApplicationService.java`

**Files to Create:**
- `src/main/java/dev/ximarelli/rosary/backend/users/FavoriteMystery.java`

**Testing:**
```bash
# Verify stats include new fields
curl -H "X-User-Id: u1" http://localhost:8080/api/users/me/stats
# Should include: "lastCheckIn": "...", "favoriteMysteries": [...]
```

---

### Issue #5: Amen Endpoint Over-response
- [ ] Create AmenResponse DTO with amenCount field
- [ ] Update CheckInsController.toggleAmen() return type
- [ ] Modify response to return only AmenResponse

**Files to Create:**
- `src/main/java/dev/ximarelli/rosary/backend/checkins/AmenResponse.java`

**Files to Modify:**
- `src/main/java/dev/ximarelli/rosary/backend/checkins/CheckInsController.java`

**Testing:**
```bash
# Verify minimal response
curl -X POST -H "X-User-Id: u1" http://localhost:8080/api/checkins/{id}/amen
# Should return: { "amenCount": 5 } only
```

---

### Issue #6: UserProfile Over-sharing Fields
- [ ] Remove currentStreak from UserProfile
- [ ] Remove longestStreak from UserProfile
- [ ] Remove totalCheckIns from UserProfile
- [ ] Remove lastCheckIn from UserProfile
- [ ] Update UserApplicationService.getProfile() to not populate removed fields
- [ ] Verify UserSummary still has these fields (for auth response)

**Files to Modify:**
- `src/main/java/dev/ximarelli/rosary/backend/users/UserProfile.java`
- `src/main/java/dev/ximarelli/rosary/backend/users/UserApplicationService.java`

**Testing:**
```bash
# Verify UserProfile no longer has stats fields
curl -H "X-User-Id: u1" http://localhost:8080/api/users/me
# Should NOT include: currentStreak, longestStreak, totalCheckIns, lastCheckIn
```

---

## ⚠️ FEATURE GAPS (Phase 3: 6-8 hours)

### Issue #7: Journal CRUD Not Implemented
- [ ] Create JournalEntry entity class
- [ ] Create JournalRepository interface (MongoDB queries)
- [ ] Create JournalApplicationService with CRUD logic
- [ ] Create JournalController with 4 endpoints
- [ ] Implement POST /journal/entries (create)
- [ ] Implement GET /journal/entries (list with date range)
- [ ] Implement PUT /journal/entries/{id} (update)
- [ ] Implement DELETE /journal/entries/{id} (delete)
- [ ] Add date range filtering to repository
- [ ] Write integration tests

**Files to Create:**
- `src/main/java/dev/ximarelli/rosary/backend/journal/JournalEntry.java`
- `src/main/java/dev/ximarelli/rosary/backend/journal/JournalRepository.java`
- `src/main/java/dev/ximarelli/rosary/backend/journal/JournalApplicationService.java`
- `src/main/java/dev/ximarelli/rosary/backend/journal/JournalController.java`
- `src/main/java/dev/ximarelli/rosary/backend/journal/CreateJournalEntryRequest.java`
- `src/main/java/dev/ximarelli/rosary/backend/journal/UpdateJournalEntryRequest.java`

**Expected Fields:**
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

**Endpoints:**
- POST /journal/entries (create)
- GET /journal/entries?from=...&to=... (list by date range)
- PUT /journal/entries/{id} (update)
- DELETE /journal/entries/{id} (delete)

---

## 🔵 POLISH & DOCUMENTATION (Phase 4: 1-2 hours)

### Issue #8: Documentation & Testing
- [ ] Add integration tests for enum serialization
- [ ] Add integration tests for all auth endpoints
- [ ] Add integration tests for all check-in endpoints
- [ ] Add integration tests for all user endpoints
- [ ] Add integration tests for all prayer endpoints
- [ ] Add contract validation tests
- [ ] Update API documentation / OpenAPI spec
- [ ] Add response examples to frontend contract
- [ ] Verify all enum values match enum-mapping.json

**Files to Create:**
- `src/test/java/dev/ximarelli/rosary/backend/contract/EnumSerializationTest.java`
- `src/test/java/dev/ximarelli/rosary/backend/contract/EndpointContractTest.java`

---

## 🎯 Verification Checklist

Before declaring success, verify:

**API Functionality:**
- [ ] POST /auth/register returns correct structure
- [ ] POST /auth/login returns correct structure
- [ ] POST /checkins returns CheckInView with `_id` field
- [ ] GET /checkins/feed returns paginated with `checkIns` field
- [ ] POST /checkins/{id}/amen returns only `{amenCount}`
- [ ] GET /users/me returns no streak/checkin fields
- [ ] GET /users/me/stats returns lastCheckIn and favoriteMysteries
- [ ] All CRUD prayers endpoints working

**Data Format:**
- [ ] All ID fields appear as `_id` in JSON
- [ ] All pagination responses have `totalPages` field
- [ ] All enums serialize to Portuguese display names
- [ ] All enum deserialization from Portuguese strings works

**Feature Completeness:**
- [ ] All 13 required endpoints exist and work
- [ ] Journal CRUD all 4 endpoints implemented
- [ ] Date range filtering on GET /journal/entries works

**Test Coverage:**
- [ ] Integration tests pass for all endpoints
- [ ] Enum serialization tests pass
- [ ] Contract validation tests pass

---

## Implementation Timeline

| Phase | Tasks | Hours | Days |
|-------|-------|-------|------|
| Phase 1 | ID fields, pagination, enums | 4-5h | 1 |
| Phase 2 | Stats, amen, profile | 3-4h | 1 |
| Phase 3 | Journal CRUD | 6-8h | 1-2 |
| Phase 4 | Tests, docs | 1-2h | 0.5 |
| **Total** | **All Issues** | **14-19h** | **3-4 days** |

---

## Sign-Off Criteria

✅ All critical issues resolved and tested  
✅ All high-priority issues resolved and tested  
✅ All features implemented and tested  
✅ Integration test suite passes  
✅ API contract documentation updated  
✅ Enum mappings verified against enum-mapping.json  

---

**Prepared by:** Frontend/Backend Contract Alignment Task  
**Date:** 2026-04-29  
**Status:** Ready for Implementation
