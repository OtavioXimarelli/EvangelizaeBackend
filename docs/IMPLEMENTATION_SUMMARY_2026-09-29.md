# Technical summary — MVP launch implementation

Branch `feat/mvp-launch-plan`, 9 commits ahead of `main`, 2026-09-29.
Implements Phases 0–4 of `docs/LAUNCH_PLAN_2026-09-28.md`. Phase 5 needs
production access and is not done.

This document describes what was built and why. It is subordinate to
`AGENTS.md`; where the two disagree, `AGENTS.md` wins.

## 1. What this branch contains

| | |
|---|---|
| Commits | 10 (`109e037` … `f9ac49d`, plus this commit) |
| Diff vs `main` | 71 files, +6945 / −54 |
| Backend tests | 18, green |
| Scraper tests | 37, green (was 19) |
| Parser gate | 400/400 liturgical days, no reading without text, no citation the day's summary did not name |

Two production behaviours changed in ways that can take the site down, and both
are called out in `AGENTS.md` as ordering constraints:

- a document with no `provider` now returns `503` instead of being labelled
  `mongodb` (`bb593e8`);
- the parser now refuses to publish a reading the day's summary did not name
  (`901ced5`).

## 2. Commit map

| Commit | Scope |
|---|---|
| `109e037` | Phase 0 — package the VPS stack, fix `MONGODB_URI`, fix the scraper timezone |
| `d96d750` | Phase 1 — alternative readings as separate texts; delete the note heuristic |
| `091adcc` | Phases 2 + 3 — lookbehind window, pre-POST batch guard, `.env.example`, crontab, runbook |
| `a6c73d6` | Phase 3 — import-response verification, `tenacity` retry, `X-Request-Id` / `importId` logging |
| `bb593e8` | Phase 4 — strict provider read path, GitHub Actions CI |
| `44cb237` | Multi-Mass / All Souls / Easter Sunday day shapes |
| `3bc8cbf` | Correct the launch plan and `AGENTS.md`; make the 400-day sweep the gate |
| `901ced5` | Citation matching; the sweep gate checks citations; scheduled sweep workflow |
| `f9ac49d` | Reconciliation as an executable gate |
| — | Technical summary (this document); drop `_count_matching_markers`, dead after the citation-matching rewrite |

## 3. The scraper → API contract

Ingestion is **push, never pull**. Spring holds no scheduler and calls no
provider; the scraper is a one-shot process on host cron.

```
cron  ──▶ docker compose run --rm scraper          Python, one-shot, exits 0 or 1
          ├─ CNBB      api-liturgia.edicoescnbb.com.br/contents/in/date/{date}
          ├─ Vatican   best-effort validation only, never blocks
          ├─ parse ──▶ one LiturgicalDay per date
          └─ POST  http://api:8080/internal/v1/liturgy/import
                   Authorization: Bearer $LITURGY_IMPORT_TOKEN
                   X-Request-Id: <uuid>
                        │
                        ▼
     InternalBearerAuthFilter   401 missing / 403 wrong, constant-time compare
     RequestIdFilter            echoes X-Request-Id into MDC
     LiturgyImportController    @Valid
     LiturgyService.importBatch validate every day, then write every day
                        │
                        ▼
     liturgical_days            _id = "YYYY-MM-DD"
```

The public GET is a separate one-way path and never touches the scraper:
`GET /api/v1/liturgy/today?timezone&locale` → `findByDate(date)` →
`DailyLiturgy`.

Network topology, not just the token, is the barrier: `api` publishes no host
port, Caddy answers `404` for `/internal/*`, and the scraper reaches the
endpoint only because it sits on the `edge` Docker network.

### 3.1 How the import is all-or-nothing

`LiturgyService.validateImport` validates schema version, period, duplicate
dates, dates inside the period, exactly one `PRIMARY` source, that it is CNBB, a
well-formed `contentHash`, and every reading — before a single document is
written. One invalid day discards the whole run.

That is why the scraper carries its own pre-POST gate (`091adcc`): refusing to
send a batch that the API will reject is cheaper than discovering it from an
alert.

## 4. Phase 0 — `109e037`

Brought the previously uncommitted working tree under version control, including
the whole `workers/liturgy-scraper` tree, which had been outside version control
entirely.

- **`spring.mongodb.uri`** replaces `spring.data.mongodb.uri`. Spring Boot 4
  renamed it; the old key still binds, so a wrong key produced no startup error
  and the driver silently fell back to `mongodb://localhost/test`, ignoring
  `MONGODB_URI` entirely. `MongoConnectionPropertiesTest` guards it, and `ci.yml`
  mutates the key to prove the test can still fail.
- **`SCRAPER_TIMEZONE`** (`workers/liturgy-scraper/Dockerfile`). The container runs
  UTC, where `date.today()` is already tomorrow between 21:00 and 24:00 in São
  Paulo. Importing under that key published tomorrow's document as today.
- **`compose.yml`**, `deploy/Caddyfile`, `deploy/mongo-init.js`, `deploy/README.md`,
  `Dockerfile`, `.env.example`. Mongo authenticates, publishes no port and sits on
  an `internal: true` network; the API publishes no port; Caddy blocks
  `/internal/*` and `/actuator/*` and terminates TLS.
- `docker-compose.yml` deleted in favour of `compose.yml`; two compose files make
  Docker Compose warn on every command.

## 5. Phase 1 — `d96d750`

**Alternative readings.** A memorial with two permitted first readings
(`Dn 7,9-10.13-14 ou Ap 12,7-12a`) publishes two bodies. The old code did
`zip(parsed, references, strict=False)`, which handed the second body the *next*
citation and shifted every later reading, leaving one option with no text. The API
rejects that, so the whole batch failed — the job would have broken around 21 Dec
and 25 Jan.

`_parse_single_reading` now returns a **list**: the colored verse-range tags
(`#ff6666` / `tomato`) are the real body boundary, not the reading markers, because
2026-12-21 prints one `PRIMEIRA LEITURA` marker and two ranges under it.
`_require_text` enforces the invariant locally before anything is returned.

**The liturgical-note heuristic was deleted, not tightened**
(`cnbb_parser.py:258-264` before this change). It looked for `<i>` elements
starting with `hoje` or containing `omite-se`. Those tags are *italic scripture
quotations* — for 2026-09-28 they are Job 1:21 and Luke 22:26. CNBB's `details`
fragment carries no liturgical note at all, so `note` is now always `None`.

## 6. Phases 2 and 3 — `091adcc`, `a6c73d6`

**Lookbehind** (`SCRAPER_DAYS_BEHIND`, default 7). The window was forward-only,
so one failed run left a date permanently unimported and the API answered `503`
for it forever. The window is now `today-7 … today+13`, 21 days. The ~7
overlapping days are harmless rewrites — imports are idempotent, keyed by date.

**Pre-POST gate** (`app/main.py:assert_complete_batch`). Refuses to send a batch
whose day count is short of the window, or that contains any reading or option
without text. A short batch would otherwise leave the tail of the window unserved
and the run would look green.

**Response verification** (`ScraperService._verify_import_result`). The POST used
to `raise_for_status()` and then trust the JSON. It now requires `status ==
SUCCESS`, `received == days sent`, `processed == days sent`, and no sent date in
`failed`. `PARTIAL` raises, so the container exits `1` and cron alerts.

**Retry** (`ScraperService._post_with_retry`). `tenacity` on 429, 5xx and
transport failures, 4 attempts, exponential backoff capped at 8s. A 4xx other than
429 is *not* retried: a bad token stays bad and retrying only delays the alert.

**Traceability.** A `X-Request-Id` is generated per run (or passed in) and logged;
`RequestIdFilter` echoes it into the Java MDC, so one id spans the scraper log and
the API log for the same POST. The returned `importId` is logged.

**Docs corrected**: `.env.example` had `localhost` and `change-me` and lacked the
timezone and lookbehind; `deploy/crontab.example` was weekly `0 3 * * 0` at the old
`/opt/liturgia-scraper` path. Both now match the stack, and the cron runs daily at
`23 4`.

## 7. Phase 4 — `bb593e8`

`LiturgyService` fell back to the literal string `"mongodb"` when a document had no
provider, which the UI then displayed to the user as the liturgical source. A
document whose provenance is unknown is now `503 LiturgyUnavailableException`.

**Deploy only after reconciling production.** This makes `provider` mandatory on
the read path, and shipping it against an unreconciled collection takes
`/pt/liturgy` dark.

`compose.yml` needed no change here — the `web` service and its `NEXT_PUBLIC_*`
build args were already gone in `109e037`; only a stale comment remained.

`.github/workflows/ci.yml` runs `./mvnw test` and `uv run pytest` on Java 25 with
the wrapper-pinned Maven, and includes a step that rewrites the Mongo property key
to the pre-Boot-4 form and requires `MongoConnectionPropertiesTest` to fail. A
regression test that cannot fail is not a test.

## 8. Day shapes — `44cb237`, `901ced5`

This is the bulk of the work and the part the plan got wrong.

The plan estimated a 2.5% failure rate from three memorials. A 400-day sweep
(2026-01-01 → 2027-02-04) found **four more shapes**, and none of them are visible
to a 120-day window started from any fixed date:

| Date | Shape | Symptom |
|---|---|---|
| 2026-12-21 | two colored verse ranges under one marker | `EMPTY-OPT`, batch rejected |
| 2026-12-24, 2026-12-25 | three Masses in one body, each repeating every marker | readings from the wrong Mass published |
| 2026-11-02 | `Outras leituras próprias à escolha` catalogue after All Souls | optional readings published as the day's |
| 2026-04-04 | Easter Vigil sharing its body with the Masses that follow | **fifteen readings from other Masses published** |
| 2026-04-05 | empty `title`, no season line | permanent `503` on Easter Sunday |

CNBB also marks the section headings three different ways — a `<center>` nested
inside the red `<font>`, a bare `<font>` with an empty `<center>` beside it, and
the Mass name left inside the marker's own `<center>` — qualifies them
(`PRIMEIRA LEITURA (mais longa)`), and leaves the HTML unbalanced around them.
Splitting the source string therefore produces fragments the parser cannot read;
`_section_plan` finds the boundary by walking `soup.descendants` instead, and
labels each marker with the section it opens.

**The 2026-04-04 finding changed the design.** All fifteen published readings had
text, so the plan's `EMPTY-TEXT` gate passed on a day that was badly wrong. What
was wrong was not a missing value but a value belonging to a different Mass, and
no check on the plan as written can see that.

So the summary is no longer consumed positionally. `_attach_references` matches
each citation the day's summary names against the body readings that *quote* it
(`_quotes`, comparing chapter and verses after stripping the book, the psalm
refrain and parenthesised alternate numbers), and drops everything the summary did
not name. A citation naming *N* alternatives backed by *N* bodies becomes *N*
readings; backed by one body it becomes one group of options repeating that text.
Where CNBB writes a citation differently in the two places — All Souls names
`Sl 23(24),1-2.3-4ab.5-6` where the body prints `Sl 22(23),1-3.4.5.6` — the order
still decides, and the published citation is the summary's.

Two supporting corrections: the body citation now prefers its own colored verse
range over `_reference_from_hidden_heading`, which could name an earlier reading
(Easter Sunday's Gospel inherited `1Cor 5,6`); and the season falls back to the
source's own wording when CNBB publishes no season, because failing there would
leave Easter permanently unimported.

`Reading.reference` returned by the parser is now the summary citation, and the
body's own citation is only a matching key — so the "drop leftover readings" and
"never borrow a neighbour's citation" rules are structural, not a convention.

## 9. Gates

`workers/liturgy-scraper/tools/sweep.py` parses 400 days and checks two things:

1. the day parses, and every reading and option carries text;
2. **every citation published is one the day's own summary named.**

The second check is the one that matters; it is what `44cb237` would have failed.
It needs network access to CNBB, so it is not in `ci`. `.github/workflows/sweep.yml`
runs it daily at 05:23 UTC and on demand — a source-side markup change otherwise
breaks ingestion with nothing in the repository changing.

`workers/liturgy-scraper/tools/refresh_fixtures.py` re-fetches the eight dates the
parser has broken on. It is a deliberate, reviewable act: a fixture is evidence
about what the source published, so a machine must never commit one. `--dry-run`
reports what would change.

## 10. Reconciliation gate — `f9ac49d`

`deploy/reconcile-liturgical-days.js` makes the ordering constraint executable
instead of prose. It reports and exits non-zero on:

- `_id` values that are not ISO dates — `findByDate` cannot reach them, so the day
  is `503` regardless of what the scraper does;
- documents with no `date`;
- documents whose `provider` is not `CNBB`.

`RECONCILE_MODE=fix` backfills `provider` from the `PRIMARY` source already stored
on the document and never invents one; a document with no `PRIMARY` source is
reported `UNRECOVERABLE` because its provenance really is unknown and the only fix
is to re-import that date. Both modes are idempotent, and `fix` runs the gate
afterwards so a partial repair still fails the deploy.

The mode is an environment variable, not an argument: `mongosh` rejects trailing
command-line arguments after the script path. Verified against MongoDB 8 — the
gate flags all three fault classes and exits `1`; `fix` repairs, reports the
unrecoverable row, and exits `0`; re-running repairs nothing; an empty collection
exits `2`.

## 11. Tests added

| File | Covers |
|---|---|
| `test_cnbb_parser.py` | alternative first readings ×3 with a Gospel-citation assertion pinning the off-by-one; All Souls catalogue; Christmas Eve and Christmas Day multi-Mass; Easter Vigil; Easter Sunday season |
| `test_main.py` | lookbehind window arithmetic, short batch refused, empty reading refused, negative lookbehind rejected |
| `test_scraper_service.py` | `X-Request-Id` propagation, `PARTIAL` rejected, short `processed` rejected, rejected date rejected, 503 retried then succeeding, 403 *not* retried |
| `MongoConnectionPropertiesTest` | `MONGODB_URI` reaches the client |
| `LiturgyServiceTest` | missing provider yields `503`, not a mislabelled source |

## 12. Not done — Phase 5

Requires production access.

1. Run the reconciliation gate until it passes.
2. Backfill `today-7 … today+13`.
3. Deploy; confirm the strict provider path returns 200, not 503.
4. Install the daily cron from `deploy/README.md`, off the hour, **with an alert
   attached to a non-zero exit** — without it a dead scraper is indistinguishable
   from a quiet Tuesday.
5. Confirm the public GET and CORS in production.

Steps 1 and 3 are the ordering constraint. The strict provider path is already on
this branch.

## 13. Known fidelity gaps

Accepted, documented, not invented around:

- **Mass prayers are permanently `{}`.** The CNBB payload has no Coleta, no
  orações, no prefácio. `{}` is the only honest answer.
- **Weekday titles are the bare season name** — `26ª Semana do Tempo Comum`
  rather than `Segunda-feira da 26ª Semana do Tempo Comum`. Memorials and
  solemnities are correct.
- **The Easter Vigil's third citation** (`Rm 6,3-11 Sl 117(118) 1-2…`) is a
  reading and its psalm on one summary line. The contract's `ReadingType` has no
  epistolary value, so it is typed from the nearest marker. The citation and text
  are right; the type is approximate. Dropping it would be worse.
- **A summary line naming several readings** matches the first one; the rest are
  not published. Losing a psalm is a fidelity gap, not a wrong-content bug.
