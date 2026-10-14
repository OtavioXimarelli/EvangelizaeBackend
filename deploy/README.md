# Deploy runbook

Production is **one** Coolify resource: a Docker Compose application built from
this repository's `compose.yml`, holding both `mongo` and `api`. There is no
separate database resource to point at — `mongo` is a service name inside the
compose project, and `compose.yml` builds `MONGODB_URI` from it. A plain VPS run
of the same file also works, with `caddy` behind the `proxy` profile.

## Where things live

| Deployment | Compose project dir | TLS |
|---|---|---|
| Coolify | `/data/coolify/services/<resource uuid>` | Coolify proxy (`api.evangelizae.com` → `api:8080`) |
| Plain VPS | wherever the repo is checked out | `docker compose --profile proxy up -d` |

`/internal/*` has no proxy-level block during beta. The bearer token
(`LITURGY_IMPORT_TOKEN`) is the only thing guarding the import endpoint — restore
a proxy path rule and remove the `127.0.0.1` port binding before leaving beta.

## First deploy (Coolify)

Create the resource: New → Application → Docker Compose.

- **Repository / branch:** this repo, `main`
- **Docker compose file location:** `/compose.yml`
- **Service:** `api` — **Port:** `8080`
- **Domains:** `api.evangelizae.com` (Coolify proxy + automatic TLS)
- **Environment variables** (same names as `.env.example`, set in the Coolify UI,
  never committed):

```env
APP_CORS_ALLOWED_ORIGINS=https://evangelizae.com
LITURGY_IMPORT_TOKEN=<openssl rand -hex 32>
MONGO_DATABASE=evangelizae
MONGO_ROOT_USERNAME=evangelizae_root
MONGO_ROOT_PASSWORD=<openssl rand -hex 32>
MONGO_APP_USERNAME=evangelizae
MONGO_APP_PASSWORD=<openssl rand -hex 32>
```

DNS for the domain must already resolve to the VPS, or the certificate fails to
issue. Deploy, then confirm from the host before exposing anything:

```bash
cd /data/coolify/services/<resource-uuid>
docker compose ps          # mongo healthy, api up
docker compose logs api | grep -i 'started\|mongo'
curl -fsS https://api.evangelizae.com/api/v1/health   # from your machine
```

## Migrating from the old standalone Mongo resource

Order matters: dump before creating, restore before announcing.

```bash
# 1. Snapshot the old resource as root (the app user is readWrite only, and
#    mongorestore --drop needs more than readWrite).
docker exec <old-mongo-container> mongodump --archive | gzip \
  > /var/backups/evangelizae-pre-cutover-$(date +%F).gz

# 2. Create and deploy the compose resource (empty database, /today answers 503).

# 3. Restore inside the new stack.
cd /data/coolify/services/<resource-uuid>
cat /var/backups/evangelizae-pre-cutover-$(date +%F).gz | \
  docker compose exec -T mongo mongorestore --archive --drop \
    --username="$MONGO_ROOT_USERNAME" --password="$MONGO_ROOT_PASSWORD" \
    --authenticationDatabase="$MONGO_DATABASE"

# 4. Run the reconcile gate (below), then verify /today and one import round-trip.
# 5. Stop the old Mongo resource. Keep it for a week before deleting it.
```

## Verifying the topology

What the topology guarantees today, and what it does not:

```bash
# Public surface answers.
curl -fsS https://api.evangelizae.com/api/v1/health

# During beta /internal/* IS reachable here: it answers 401 without the token,
# which is the bearer check doing its job, and the token is the only guard.
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  https://api.evangelizae.com/internal/v1/liturgy/import
# expect 401 — no proxy path rule blocks it during beta

# Actuator is not on the public port at all.
curl -s -o /dev/null -w '%{http_code}\n' https://api.evangelizae.com/actuator/health
# expect 404 — management runs on 9090 inside the container and is unrouted

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
cd /data/coolify/services/<resource-uuid>
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

`mongo_data` is the only stateful volume. It is a named volume in the compose
project, so Coolify's database backups do not apply — the schedule is yours:

```bash
# /etc/cron.daily/evangelizae-backup  (chmod +x)
#!/bin/sh
set -e
cd /data/coolify/services/<resource-uuid>
docker compose exec -T mongo mongodump --archive | gzip \
  > "/var/backups/evangelizae-$(date +%F).gz"
```

Keep the copy off the VPS too — a dump on the same disk as the database is not a
backup:

```bash
rclone copy "/var/backups/" b2:evangelizae-backups/
```

Restore is the reverse of the migration step. Connect as root, since the app user
is `readWrite` only and `--drop` needs more:

```bash
cd /data/coolify/services/<resource-uuid>
gunzip -c /var/backups/evangelizae-$(date +%F).gz | \
  docker compose exec -T mongo mongorestore --archive --drop \
    --username="$MONGO_ROOT_USERNAME" --password="$MONGO_ROOT_PASSWORD" \
    --authenticationDatabase="$MONGO_DATABASE"
```

The collection is ~365 documents per year, so a nightly dump is a few hundred
kilobytes. There is no migration framework and no schema history to reconcile:
`LITURGY_INTEGRATION_PLAN.md:419-429` flags legacy documents with non-date
`_id`s as an open item, so confirm your production collection is date-keyed
before trusting a restore.

## Updating

A push to `main` redeploys the compose resource: Coolify rebuilds the `api`
image and recreates that container. `mongo` is only restarted if its own
configuration changed, and its data lives in `mongo_data` either way.

On a plain VPS, the same thing by hand:

```bash
git pull && docker compose build && docker compose up -d
```

`server.shutdown: graceful` plus a 10s cap in `application.yml` means `up -d`
replaces the api without dropping in-flight reads. Watch for the container to
reach `healthy` before assuming the deploy landed.

## Rotating `LITURGY_IMPORT_TOKEN`

Coolify: generate a new token with `openssl rand -hex 32`, update
`LITURGY_IMPORT_TOKEN` in the resource environment variables, and redeploy.

Plain VPS:

```bash
NEW=$(openssl rand -hex 32)
# update LITURGY_IMPORT_TOKEN in .env, then:
docker compose up -d --force-recreate api
```

The token is the only thing guarding the import endpoint.
