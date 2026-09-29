# MVP launch plan — 2026-09-28

Backend only. The frontend is out of scope for this plan and is being run
separately.

This document records the plan as decided on 2026-09-28. It is subordinate to
`AGENTS.md`; where the two disagree, `AGENTS.md` wins.

## 1. Verified state

Everything below was checked by running it, not by reading it. All of it is
**uncommitted** in the working tree.

| Item | Evidence |
|---|---|
| MongoDB URI was silently ignored | Spring Boot 4 renamed `spring.data.mongodb.uri` to `spring.mongodb.uri`. The driver seeded `localhost:27017` and ignored `MONGODB_URI`. Fixed; now `hosts=[mongo:27017]`, health `UP` |
| Regression test for the above | `MongoConnectionPropertiesTest` — verified to **fail** when the key is wrong |
| Scraper timezone | The container runs UTC, so `date.today()` returned 2026-09-29 while São Paulo was still 2026-09-28. Fixed with `SCRAPER_TIMEZONE`; documents now land under the correct date |
| Mongo authentication | Unauthenticated query → `requires authentication`; `27017` not listening on the host |
| `/internal` exposure | Connection refused from the host; wrong token → `403` |
| Full chain in containers | scraper → api → mongo returns `SUCCESS`, `created: 1`; document `_id` is date-keyed |
| Idempotency | Re-import returns `updated: 1`, document count unchanged |
| Public contract | `GET /api/v1/liturgy/today` returns a valid `DailyLiturgy`; CORS preflight correct |
| Scraper exit codes | Container returned `1` on a failed POST, `0` on success |
| Test suites | 17 backend tests, 19 scraper tests (run inside the image) |
| CNBB past dates | Serves at least 30 days back, so the lookbehind window is safe |

## 2. Feature coverage

The frontend makes exactly one network call, so the contract surface is finite.
Every clause of its runtime validator was checked against the backend.

| Requirement | Status |
|---|---|
| `GET /api/v1/liturgy/today?timezone&locale` | OK |
| `date` equals the observer's São Paulo civil date | OK |
| `title` non-empty, `color` in enum | OK |
| `prayers` object with only the three optional keys | OK — always `{}`, see 3.1 |
| `groups` non-empty, each `items` non-empty | OK |
| `source.provider` / `fetchedAt` / `freshness` | OK — `provider` fallback being removed, see 3.4 |
| CORS from the frontend origin | OK |
| 400 vs 503 distinguishable | OK |
| Never serves a stale date | OK |
| Weekday with no `SECOND_READING` | OK — group omitted, frontend iterates actual groups |
| Alternative readings as multiple items in one group | OK — **no frontend change required** |

**The backend has every feature the frontend MVP needs.** The remaining work is
reliability, not features.

### 3.1 Mass prayers — resolved, no work

`prayers` is permanently `{}`. The CNBB `content` payload contains only `body`,
`color`, `date`, `details`, `filename`, `leituras`, `title` — no Coleta, no
orações, no prefácio. The Mass prayers are not published by this source, so `{}`
is the only honest answer. The contract makes all three fields optional.
Fabricating them would violate the Veritas rule. **Out of scope.**

### 3.2 Missing-day recovery — the window is forward-only

`main.py:74-75` computed `start = today`, `end = today + N - 1`. No later run
revisited a date, so one failed run made that date a permanent 503 and the
frontend hard-failed on it. Fixed by a lookbehind: `today - 7 … today + 13`,
21 days per run.

### 3.3 The liturgical note is a false-positive heuristic

`cnbb_parser.py:258-264` looked for `<i>` elements starting with `hoje` or
containing `omite-se`. Those `<i>` tags are **italic scripture quotations** — for
2026-09-28 they are Job 1:21 and Luke 22:26, not notes. CNBB's `details`
fragment carries no liturgical note at all. Delete the heuristic rather than
tighten it.

### 3.4 `provider` can leak the string "mongodb"

`LiturgyService:283` fell back to `"mongodb"` for documents lacking provider
metadata, which the UI would display as the liturgical source. Decision: reconcile
production, then remove the fallback so a document with no `provider` throws
`LiturgyUnavailableException` instead of being mislabeled.

### 3.5 Weekday titles are the bare season name

Monday 2026-09-28 renders as `26ª Semana do Tempo Comum` rather than
`Segunda-feira da 26ª Semana do Tempo Comum`. Memorials and solemnities are
correct. Fidelity item, deferred.

## 4. Ordering constraints

Two of these can take the site down if reversed.

1. **Reconcile production Mongo before deploying the strict provider read
   path.** With 3.4 applied, a document lacking `provider` returns 503. Ship that
   code against an unreconciled database and `/pt/liturgy` goes dark.
2. **The parser fix must land before any windowed run.** 2026-09-29 is in every
   window from 2026-09-28 forward, and a single invalid day rejects the entire
   batch.
3. CNBB past dates are available, so the lookbehind is safe. Verified.

## 5. Window arithmetic

`SCRAPER_DAYS_BEHIND=7`, `SCRAPER_DAYS_AHEAD=14` → `today-7 … today+13`, 21 days
per run. Re-imports are idempotent, so the ~7 overlapping days are harmless
rewrites. `unchanged` remains `0` because the backend always writes; that is the
current designed behaviour, not a defect.

## 6. Phases

### Phase 0 — Commit what exists (10 min) — **done** (`109e037`)

Everything from this session is uncommitted and the scraper was outside version
control entirely. Commit first.

```bash
git add -A
git commit -m "feat: package VPS Docker stack, fix MONGODB_URI, scraper timezone"
```

### Phase 1 — Parser: alternative readings (~2h, critical path) — **done** (`d96d750`, `44cb237`)

`workers/liturgy-scraper/app/parsers/cnbb_parser.py:195` — replace
`zip(parsed, references, strict=False)` with a consumption-based walk:

- A reference naming *N* alternatives with *N* or more matching body markers
  following → **separate readings**, one reference per marker, consuming *N* of
  each. This is the 2026-09-29 case (Daniel *ou* Apocalipse, each with its own
  text).
- A reference naming *N* alternatives with only one matching body marker → one
  reading, with `options` where every option carries the same text.
- References exhausted → fall back to `_reference_from_hidden_heading`; leave the
  citation blank rather than borrowing a neighbour's.

Invariant at the end of `_parse_readings`: every reading and every option must
have non-empty `text`, else raise locally.

Expected for 2026-09-29: `FIRST_READING` ×2 (`Dn 7,9-10.13-14`,
`Ap 12,7-12a`), `PSALM` `Sl 137(138)…`, `GOSPEL` `Jo 1,47-51`.

The known failure rate is **3 of 120 days ≈ 2.5%**: 2026-09-29, 2026-12-21 and
2027-01-25, all memorials with two permitted first readings. Unfixed, the job
breaks again around 21 Dec and 25 Jan.

Also delete the note heuristic (`cnbb_parser.py:258-264`, see 3.3).

**Deviation from the plan, recorded here because the plan's 2.5% figure was
wrong.** 2026-12-21 does not repeat the `PRIMEIRA LEITURA` marker; it prints one
marker and two colored verse ranges under it, so the range tags — not the
markers — are the real body boundary. A 400-day sweep (2026-01-01 → 2027-02-04)
then found three more shapes the plan did not anticipate, all of which reject the
whole batch:

| Date | Shape | Failure |
|---|---|---|
| 2026-12-24, 2026-12-25 | Three Masses in one body, each repeating every reading marker | Readings from the wrong Mass published; Gospel mismatched |
| 2026-11-02 | `Outras leituras próprias à escolha` catalogue appended to All Souls | Optional readings published as the day's |
| 2026-04-05 | Easter Sunday published with an empty `title` and no season line | `could not identify the CNBB liturgical season` — a permanent 503 on Easter |

CNBB also marks these headings up three different ways (a `<center>` nested in
the red `<font>`, a bare `<font>` with an empty `<center>` beside it, and the
Mass name left inside the marker's own `<center>`), and its HTML is unbalanced
around them, so the section boundary has to be found in the parse tree. Fixed in
`44cb237`; all three dates are now fixtures.

### Phase 2 — Guard the batch (~1h) — **done** (`091adcc`)

- Pre-POST assertion that every reading in every day has non-empty text; abort
  rather than send a day the backend will reject.
- Assert the day count equals the window; a short batch is a failed run.
- Fixtures for 2026-09-29, 2026-12-21 and 2027-01-25, asserting the Gospel keeps
  `Jo 1,47-51` to pin the off-by-one.
- Add `SCRAPER_DAYS_BEHIND` support at `main.py:74-75`.
- Gate: 120-day scan green, 120/120, no `EMPTY-OPT`, no `EMPTY-TEXT`.

### Phase 3 — Scraper reliability (~2h) — **done** (`091adcc`, `a6c73d6`)

In `services/scraper_service.py` and `main.py`:

- `tenacity` retry on 429 / 5xx / timeout (already a dependency).
- Validate the response: `status == SUCCESS`, `processed == received`, and every
  expected date present. Today it `raise_for_status()`s then trusts the JSON.
- Treat `PARTIAL` and any missing date as failure.
- Send and log `X-Request-Id`; log the returned `importId`.
- Delete the stray `1` at `sources/cnbb.py:16`.
- Refresh `workers/liturgy-scraper/.env.example` — still `localhost` and
  `change-me`, missing `SCRAPER_TIMEZONE` and `SCRAPER_DAYS_BEHIND`.
- Refresh `workers/liturgy-scraper/deploy/crontab.example` — still weekly
  `0 3 * * 0` at the old `/opt/liturgia-scraper` path.

Exit `0` / `1` already works; verified.

### Phase 4 — Backend contract and stack (~1h) — **done** (`bb593e8`)

- `LiturgyService:283` — remove the `"mongodb"` fallback (see 3.4). **Deploy only
  after reconciliation.**
- `compose.yml` — remove the `web` service and the `NEXT_PUBLIC_*` build args.
  Keep `caddy`: it is what blocks `/internal/*` and terminates TLS.
- Add `.github/workflows/ci.yml` running `./mvnw test` and `uv run pytest`.

The `web` service and its build args were already gone in `109e037`; only a
stale comment referencing it remained. CI additionally mutates the Mongo
property key to prove `MongoConnectionPropertiesTest` can still fail — a
regression test that cannot fail is not a test.

### Phase 5 — Reconcile, deploy, schedule (~1.5h) — **not started**

1. Inspect production `liturgical_days` for non-date-keyed `_id`s
   (`LITURGY_INTEGRATION_PLAN.md:419-429`); convert or recreate the collection.
2. Backfill `today-7 … today+13`.
3. Deploy. Confirm the strict provider path returns 200, not 503.
4. Install the daily cron from `deploy/README.md`, deliberately off the hour, and
   attach an alert to a non-zero exit. Without the alert a dead scraper is
   indistinguishable from a quiet Tuesday.
5. Confirm `GET /api/v1/liturgy/today` in production and CORS from the real
   frontend origin.

Steps 1 and 3 are the ordering constraint in §4.1 and are not optional: the
strict provider read path in `bb593e8` is already on this branch, and shipping it
against an unreconciled collection takes `/pt/liturgy` dark.


## 7. Definition of done

- [x] 120-day scan: 120/120, no `EMPTY-OPT`, no `EMPTY-TEXT`
- [x] 400-day scan (2026-01-01 → 2027-02-04): 400/400, no `EMPTY-TEXT`
- [x] Fixtures cover all three broken dates, including the Gospel-citation
      assertion
- [ ] 21-day import returns `SUCCESS` with `processed == 21` — needs a live API
- [ ] Re-import leaves the document count unchanged — needs a live API
- [ ] Public GET returns `freshness: LIVE`, `provider: CNBB`, every reading
      non-empty — needs production
- [x] No document can be served with a missing or `mongodb` provider
- [x] Scraper exits `0` on success and `1` on any failure, retries
      429/5xx/timeout, logs `X-Request-Id` and `importId`
- [x] Backend and scraper tests green (18 + 36), both wired into CI
- [ ] `compose.yml` starts clean from a fresh clone with only `.env` populated
- [ ] Mongo authentication on, no host port, no volume loss on `up -d`
- [ ] Production collection reconciled before the strict provider path ships
- [ ] Daily job green for 7 consecutive days

The unchecked items all need a running stack or production access; they are
Phase 5.

### 7.1 The scan is a gate, not a one-off

A 120-day window from any given date will not contain 2026-11-02, 2026-12-24,
2026-12-25 or 2026-04-05. The plan's gate therefore passes on a window that still
contains four unparseable days. The only sweep that exercises a full liturgical
year is the 400-day one, and it is what caught these. Rerun it before any change
to `cnbb_parser.py`:

```bash
cd workers/liturgy-scraper
uv run python - <<'PY'
import logging
from datetime import date, timedelta
import httpx
from app.parsers.cnbb_parser import CnbbParser
from app.sources.cnbb import CnbbSource

logging.disable(logging.CRITICAL)
client = httpx.Client(timeout=30, follow_redirects=True,
                      headers={"User-Agent": "LiturgyScraper/0.1.0"})
cnbb, parser = CnbbSource(client), CnbbParser()
start, failures = date(2026, 1, 1), 0
for offset in range(400):
    day = start + timedelta(days=offset)
    try:
        parser.parse(cnbb.fetch_html(day), day)
    except Exception as error:
        failures += 1
        print(f"FAIL {day} {type(error).__name__}: {error}")
print(f"{400 - failures}/400 parsed")
raise SystemExit(1 if failures else 0)
PY
```

It needs network access to CNBB, so it cannot run in CI. It must run by hand
before a deploy that touches the parser.


## 8. Out of scope

Accounts, prayer sync, intentions, the prayer wall, theological AI, parishes,
push notifications, Redis, message queues, worker frameworks, import version
history, database migration frameworks, a generated API client, a server-side
liturgical calendar, Mass prayers (3.1), and all frontend work.

The backend ships exactly three endpoints: `GET /api/v1/liturgy/today`,
`GET /api/v1/health`, and the bearer-protected
`POST /internal/v1/liturgy/import`. All three exist and work. Nothing in this
plan adds a fourth.
