FROM ghcr.io/astral-sh/uv:python3.14-bookworm-slim

WORKDIR /app

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never

COPY pyproject.toml uv.lock ./

RUN uv sync --frozen --no-dev --no-install-project

# Activate the venv for the process environment, not just for `uv run`. Without
# this, `docker compose run scraper python -m app.main --dry-run` cannot import
# httpx, which makes the container undiagnosable from the host.
ENV VIRTUAL_ENV=/app/.venv
ENV PATH="$VIRTUAL_ENV/bin:$PATH"

COPY app ./app

# The container runs UTC. The scraper must compute "today" in America/Sao_Paulo
# itself — see SCRAPER_TIMEZONE in app/main.py — because between 21:00 and 24:00
# in São Paulo the UTC date is already tomorrow, and importing tomorrow's
# document under today's key would serve a date the Church has not reached.
ENV SCRAPER_TIMEZONE=America/Sao_Paulo

# No healthcheck: this is a one-shot job that exits. `restart: "no"` in
# compose.yml and a non-zero exit are the failure signals that matter.
ENTRYPOINT ["python", "-m", "app.main"]
