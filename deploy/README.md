# VPS runbook

Everything below runs on the VPS with Docker Engine + the Compose plugin.
Nothing here requires Coolify.

## Scope

This stack file covers the backend only: `mongo`, `api` and `caddy`.
The frontend is deployed separately and is not built from this repository.

Caddy routes every path that is not `/api/*` to a service named `web` on the
`edge` network. Point that at however you run the frontend — a second Compose
project joined to the same external network, or a container attached directly:

```bash
# Option A: a separate Compose project on a shared external network.
docker network create evangelizae_edge
#   in the frontend project:  networks: [evangelizae_edge]  (external: true)
#   in this project:         edge: {name: evangelizae_edge, external: true}

# Option B: a plain container on the same network.
docker network connect evangelizae_edge <frontend-container>
```

If the frontend is not running, Caddy returns 502 for non-`/api` paths. The API
itself is unaffected — `/api/v1/liturgy/today` keeps serving.

## First deploy

```bash
cd /opt/evangelizae/api
cp .env.example .env && chmod 600 .env
# edit .env: SITE_DOMAIN, ACME_EMAIL, all three secrets, APP_CORS_ALLOWED_ORIGINS
```

DNS for `SITE_DOMAIN` must already point at this host, or the TLS certificate
will fail to issue and the site will be unreachable.

```bash
# Build both images first so a failure is visible before anything starts.
docker compose build

# Start the core. The api waits for mongo to report healthy.
docker compose up -d mongo api

# Confirm before exposing anything.
docker compose ps
curl -fsS http://api:8080/api/v1/health 2>/dev/null || \
  docker compose exec api curl -fsS http://127.0.0.1:8080/api/v1/health

# Then the edge: reverse proxy.
docker compose --profile proxy up -d
```

## Verifying the topology

The important property is that `/internal/*` is unreachable from the internet:

```bash
# Public surface answers.
curl -fsS https://$SITE_DOMAIN/api/v1/health

# The import endpoint does not, and never will.
curl -s -o /dev/null -w '%{http_code}\n' https://$SITE_DOMAIN/internal/v1/liturgy/import
# expect 404 — from the proxy, and because the api publishes no host port

# Mongo is not listening on the host at all.
ss -lntp | grep 27017 || echo '27017 not listening — correct'
```

## Reconciling before the strict provider read path

The API refuses to serve a document whose `provider` is unknown — it answers
`503` rather than labelling the source `mongodb`. That is the correct behaviour,
and it is why reconciliation is a **gate**, not a memory step. Run this before
the deploy that introduces the strict path; shipping the code against an
unreconciled collection takes `/pt/liturgy` dark.

```bash
cd /opt/evangelizae/api
docker compose exec -T -e RECONCILE_MODE=check mongo mongosh \
  -u "$MONGO_APP_USERNAME" -p "$MONGO_APP_PASSWORD" \
  --authenticationDatabase "$MONGO_DATABASE" --quiet \
  deploy/reconcile-liturgical-days.js
```

It reports three faults and exits non-zero if any are present:

- `_id` values that are not ISO dates. `findByDate(date)` cannot reach those
  rows, so the day is a `503` no matter what the scraper does.
- Documents with no `date` field.
- Documents whose `provider` is not `CNBB`.

To repair the provider field, run the same command with `RECONCILE_MODE=fix`. It
backfills from the `PRIMARY` source already stored on each document — it never
invents a value. A document with no `PRIMARY` source is reported as
`UNRECOVERABLE`: its provenance really is unknown, and the only fix is to
re-import that date. The mode is an environment variable, not an argument,
because `mongosh` rejects trailing command-line arguments after the script path.
Both modes are idempotent, and `fix` runs the gate afterwards, so a partial
repair still fails the deploy.

## Importing liturgy data

POST liturgical days to `/internal/v1/liturgy/import` with a valid `LiturgyImportRequest` body. See the OpenAPI spec for the schema.

Re-importing a date is safe. Documents are keyed by the ISO date string, so a repeat import replaces rather than duplicates.

## Backups

`mongo_data` is the only stateful volume.

```bash
docker compose exec -T mongo mongodump \
  --username="$MONGO_APP_USERNAME" --password="$MONGO_APP_PASSWORD" \
  --authenticationDatabase="$MONGO_DATABASE" \
  --archive | gzip > "/var/backups/evangelizae-$(date +%F).gz"
```

The collection is ~365 documents per year, so a nightly dump is a few hundred
kilobytes. There is no migration framework and no schema history to reconcile:
`LITURGY_INTEGRATION_PLAN.md:419-429` flags legacy documents with non-date
`_id`s as an open item, so confirm your production collection is date-keyed
before trusting a restore.

## Updating

```bash
cd /opt/evangelizae/api && git pull && docker compose build && docker compose up -d
```

`server.shutdown: graceful` plus a 10s cap in `application.yml` means `up -d`
replaces the api without dropping in-flight reads. Watch for the container to
reach `healthy` before assuming the deploy landed.

## Rotating `LITURGY_IMPORT_TOKEN`

```bash
NEW=$(openssl rand -hex 32)
# update LITURGY_IMPORT_TOKEN in .env, then:
docker compose up -d --force-recreate api
```

The token is the only thing guarding the import endpoint.
