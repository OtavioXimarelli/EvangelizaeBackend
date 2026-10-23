# Frontend → Backend Gap Analysis

## Already integrated (API calls exist in frontend)

1. Auth login/register (`auth-provider.tsx`)
2. Check-in submit/status/feed/amen/comment (`services/api.ts`)
3. User stats (`services/api.ts`)

## Not integrated yet (still local/Zustand)

1. Check-in modal writes only to local store (`components/check-in-modal.tsx`).
2. Dashboard stats/activity largely read local store (`app/[locale]/dashboard/page.tsx`).
3. Intentions wall is entirely local (`store/use-intentions-store.ts` + `ferramentas/mural-intencoes/page.tsx`).
4. Spiritual journal is entirely local (`store/use-journal-store.ts` + `ferramentas/diario-espiritual/page.tsx`).

## Backend work needed

### Immediate (to stabilize existing backend mode)
- Keep `/auth/*`, `/checkins/*`, `/users/me/stats` aligned with current frontend mapper.
- Return `message` on errors as string or string[].

### Next (to remove local persistence)
- Add Journal API (`/journal/entries` CRUD).
- Complete Prayers API and filtering expected by intentions wall.
- Add `/checkins/my` and ensure dashboard can consume server timeline.

## Frontend refactor needed after backend readiness

1. Update `CheckInModal` to call `submitCheckIn` instead of `usePrayerStore.addCheckIn`.
2. Migrate dashboard selectors from Zustand to React Query (`useTodayStatus`, `useUserStats`, `useFeed` + own check-ins).
3. Replace intentions and journal stores with API-backed repositories.

## Risk notes

- Enum mismatch risk (English internal frontend tags vs Portuguese backend labels) must be handled centrally.
- Current auth-disabled mode can hide backend defects; QA should test with auth enabled.
- Hybrid state (local + backend) can produce inconsistent streak values if both are active.
