# Evangelizae API agent guide

This file is the canonical operating guide for coding agents working on the backend. The frontend repository has its own `AGENTS.md` file; the two are companions, not duplicates.

## Repository independence

This repository is **one of two independent deployable units**:

| Repository | Purpose | Deploy target |
|---|---|---|
| `EvangelizaeBackend` (this repo) | Serve liturgy, validate imports, store documents | Coolify Docker resource |
| `Evangelizae` (frontend) | Render UI, cache responses, PWA | Coolify Docker resource |

**Each repository:**
- Has its own lifecycle, CI, and deployment
- Can be deployed independently without touching the others
- Has its own test suite that must pass before deploy
- Owns its own Dockerfile and environment variables

**The contract binds them:** `openapi/evangelizae-v1.openapi.yml` in this repo is the canonical API contract. The frontend has a mirror copy. Changing the contract requires coordinated updates across repos, but day-to-day development is independent.

**Agents working on this repo must:**
- Never assume the frontend code is in this repository
- Never modify frontend code from this repo
- Treat the OpenAPI spec as the boundary — if it doesn't change, the other repos don't need to
- Run only this repo's test suite (`./mvnw test`) to validate changes
- Deploy only this repo's Docker image to Coolify

## What this service is

Evangelizae is a free, open-source Catholic companion for everyday prayer. The backend's whole job in the MVP is narrow and deliberate: hold one normalized document per liturgical day, and serve today's liturgy honestly to the browser.

Three rules define it:

- **Veritas** — no invented devotional content, no fabricated calendar, no silent guessing.
- **Communio** — no engagement mechanics, no streaks-for-reward, no social graph.
- **Missio** — the product should send people back into parish life, not keep them in the app.

The public endpoint is the entire product surface. Everything else in this repository exists to keep that one endpoint correct.

## Source-of-truth order

When documents disagree, use this order:

1. This `AGENTS.md` for agent behavior and decisions.
2. `openapi/evangelizae-v1.openapi.yml` for the public contract (canonical; the frontend mirror is a copy).
3. `docs/CROSS_REPO_COORDINATION.md` for rules governing changes that span multiple repos.
4. `docs/LAUNCH_PLAN_2026-09-28.md` for the current MVP launch plan, its ordering constraints, and its definition of done. `docs/IMPLEMENTATION_SUMMARY_2026-09-29.md` records what was actually built, the day shapes found, and the fidelity gaps accepted.
5. `LITURGY_INTEGRATION_PLAN.md` for the delivery plan.
6. `LITURGY_IMPORT_TECHNICAL.md` for the import path as built.
7. `LITURGY_IMPORT_TECHNICAL.md` for the import path as built.
8. `README.md` for local operation and production configuration.
9. Current code and tests for actual behavior.
10. `ARCHITECTURE.md`, `ARCHITECTURE_EBOOK.md`, `SYSTEM_DESIGN.md`, `VISUAL_ARCHITECTURE.md` as **historical or aspirational context only**. These describe Redis, hexagonal ports/adapters, weekly cron, a `liturgical_day_versions` collection, and a `HttpLiturgyProvider` pull-mode that were all deliberately removed. Do not implement from them and do not treat them as current.

## Two orderings that can take the site down

Two constraints in the launch plan are not stylistic. Reversing either causes an outage:

1. **Reconcile the production `liturgical_days` collection before deploying any change that makes a missing `provider` fatal.** The API must never serve a document whose source is unknown, so a document without `provider` returns `503`. Ship that first against an unreconciled database and `/pt/liturgy` goes dark.
2. **The import request is validated in full before anything is written.** A single invalid day rejects the entire batch — the caller is responsible for never sending one.

## Liturgical text sourcing — decided

**Decision (project owner, 2026-09-28): the liturgical texts ingested by this pipeline are public and free for Evangelizae to redistribute. No formal license, permission request, or attribution negotiation is required.**

This closes the question left open in `README.md` ("A escolha da fonte, sua autorização de uso e o mapeamento do formato original devem ser definidos antes de habilitar produção") and in the frontend's `LITURGY_CONTENT_SOURCES.md`. Agents must not re-open it, re-litigate it, or add a license-gating step to the pipeline.

Concretely:

- **Primary source is CNBB / Edições CNBB — Igreja em Oração**, `https://api-liturgia.edicoescnbb.com.br/contents/in/date/{date}`. It is the single source of truth for the liturgical calendar (date, celebration, color, reading references) **and** for the reading text.
- **Validation source is Vatican News — Palavra do Dia**, best-effort only. A discrepancy produces a `WARNING`; it never blocks ingestion and never suppresses valid CNBB data.
- Scope covers the daily liturgical text served by that endpoint. It does not authorize copying the CNBB trade edition as a whole, the CNBB logo, or the Edições CNBB brand.
- `source.provider` must still name the real provider, and the UI must show it. **Do not claim institutional endorsement by CNBB or Edições CNBB.**

## MVP boundary — what this backend does and does not build

The frontend needs **exactly one** endpoint. Everything else it renders — the 73-step rosary, onboarding, the sanctuary, streak stats, reading preferences, theme, PWA/offline — is deliberately local-only and stays that way for the beta.

Shipped in the MVP:

- `GET /api/v1/liturgy/today?timezone=&locale=` — the only public product endpoint.
- `GET /api/v1/health` — public liveness.
- `POST /internal/v1/liturgy/import` — bearer-protected batch ingestion.

**Out of the MVP, explicitly.** Do not build these unless the product owner opens a new cycle:

- Accounts, auth, JWT, password reset, email verification.
- Prayer check-in, prayer history, prayer stats, cross-device sync, the Zustand migration endpoint.
- Intentions, the prayer wall, moderation, comments, social following, feeds, metrics.
- Bible search, catechism, saints, global search.
- Parishes, mass schedules, events, geolocation.
- Theological AI, RAG, vector search.
- Push notifications and subscriptions.
- Redis, message queues, worker frameworks, import version history, database migration frameworks, generated API clients.

The frontend already redirects its placeholder routes (`/pt/ai`, `/pt/intentions`, `/pt/profile`) to the roadmap instead of simulating those features. Leave them that way.

## Non-negotiables

- **Never present stale, cached, sample, or inferred content as today's liturgy.** `503 LITURGY_UNAVAILABLE` is the correct answer for a day that has not been imported. Never serve yesterday's document as today.
- **Never manufacture prayers, biblical quotations, saint quotations, liturgical texts, doctrinal explanations, or any claim of ecclesial approval.**
- **No ads, paywalls, premium tiers, leaderboards, coins, rewards, competitive streaks, or engagement traps.**
- Any change to prayer text, mystery fruit, or biblical reference requires pastoral sign-off before production.
- Secrets (`LITURGY_IMPORT_TOKEN`, `MONGODB_URI`) never enter the repository, the browser bundle, or logs.
- Internal import metadata (hashes, validation warnings, source URLs) stays internal. The public contract exposes only `DailyLiturgy`.

## Architecture rules

- Feature-first, package-by-feature, Spring MVC. No hexagonal ports/adapters, no generic repository framework, no CQRS.
- **Ingestion is push, never pull.** There is no in-process scheduler and no provider called at request time.
- One document per liturgical date, keyed by the ISO date string. Re-importing the same date replaces it. This makes ingestion idempotent without a migration engine.
- The import request is validated in full **before** any document is written. A single invalid day rejects the entire batch — the caller is responsible for never sending one.
- Date logic is always `LocalDate.ofInstant(clock.instant(), ZoneId)` against the requested IANA zone, with a UTC `Clock` injected. Never use server-local dates.
- `Content-Type: application/json`, `camelCase`, `exclude_none` semantics, dates as `YYYY-MM-DD`, timestamps as UTC ISO-8601.

## Import endpoint

The import endpoint accepts `POST /internal/v1/liturgy/import` with a `LiturgyImportRequest` body. This repo validates and stores it.

**Agents must not:**
- Add scraper-specific logic to the backend
- Assume caller behavior beyond the import contract

## Commands

```bash
mise install
mise exec -- ./mvnw test
mise exec -- ./mvnw clean package
mise exec -- ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Local stack: `docker compose up --build`, API on `:8080`, MongoDB on `:27017`.

## Docker and VPS operation

`compose.yml` is the single stack file. Do not add a second compose file; Docker
Compose warns on every command when two exist.

- `mongo` runs with authentication, publishes no port, and sits on an
  `internal: true` network. Never publish `27017` on a public host.
- `api` publishes **no host port**. The only way in is the Caddy proxy, and
  Caddy does not route `/internal/*`. The import endpoint is unreachable from the
  internet by network topology, not only by bearer token.
- `caddy` terminates TLS and blocks `/internal/*` and `/actuator/*`. Do not remove
  it without replacing both functions.
- `MONGODB_URI` feeds `spring.mongodb.uri`. In Spring Boot 4,
  `spring.data.mongodb.uri` is **not** the connection property; using it makes the
  driver silently fall back to `mongodb://localhost/test`.

Full runbook, including backups and token rotation: `deploy/README.md`.
