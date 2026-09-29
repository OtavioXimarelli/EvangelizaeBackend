# VPS runbook

Everything below runs on the VPS with Docker Engine + the Compose plugin.
Nothing here requires Coolify.

## Scope

This stack file covers the backend only: `mongo`, `api`, `scraper` and `caddy`.
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

## Scheduling the scraper

There is no in-process scheduler in the API, by design: ingestion is push-only
and the scraper is a one-shot process. Invoke it from host cron.

```bash
crontab -e
```

```cron
# Daily liturgy import. 04:07 local, deliberately off the :00 spike.
7 4 * * * cd /opt/evangelizae/api && docker compose run --rm --no-deps scraper >> /var/log/evangelizae-scraper.log 2>&1
```

Keep the odd minute. Every other scraper on the planet fires at `0 3 * * 0` or
`0 4 * * *`, and CNBB is a small publisher.

`--no-deps` skips the `depends_on: api healthy` check, because cron must not
skip a run if the API happens to be mid-restart; the scraper retries the POST
and a genuine API outage should page you, not silently vanish. Drop it if you
would rather the job wait.

## Alerting

A scraper that dies is indistinguishable from a quiet day unless you watch the
exit code. Do not skip this.

```bash
# /usr/local/bin/evangelizae-scraper-alert
#!/bin/sh
# Cron calls this AFTER the scraper. $1 is the scraper exit code.
if [ "$1" -ne 0 ]; then
  curl -fsS -X POST "$ALERT_WEBHOOK" \
    -H 'Content-Type: application/json' \
    -d "{\"text\":\"evangelizae scraper failed, exit $1. Today may have no liturgy.\"}" \
    >/dev/null
fi
```

The failure this catches: the scraper stops, nobody notices for three days, and
`/pt/liturgy` serves nothing on day four because that date was never imported.

## Backfilling a missed window

After a scraper outage, or to seed a fresh database:

```bash
cd /opt/evangelizae/api
SCRAPER_DAYS_AHEAD=14 docker compose run --rm --no-deps -e SCRAPER_DAYS_AHEAD=14 scraper
```

Re-importing a date is safe. Documents are keyed by the ISO date string, so a
repeat import replaces rather than duplicates.

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

The token is the only thing guarding the import endpoint, and the token in
`.env` is the same one the scraper reads. Change both together or the next
scheduled run exits `1` on `403 FORBIDDEN`.
