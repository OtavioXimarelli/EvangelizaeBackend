"""Entrypoint - stateless, run once then exit (spec §5).

Executed via: uv run python -m app.main  (dev) or docker compose run --rm scraper (prod).
Cron triggers it weekly: 0 3 * * 0  (Sunday 03:00)

Java analogy: Spring Boot CommandLineRunner / main() - no web server, just a job.
"""

import argparse
import os
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo
import json
import logging

import httpx

from app.parsers.cnbb_parser import CnbbParser
from app.parsers.vatican_parser import VaticanParser
from app.services.scraper_service import ScraperService
from app.sources.cnbb import CnbbSource
from app.sources.vatican import VaticanSource


def scraper_timezone() -> ZoneInfo:
    """IANA zone the liturgical day is defined in.

    The container runs UTC, so `date.today()` there is already tomorrow between
    21:00 and 24:00 in São Paulo. Importing under that key would publish a
    document dated ahead of the day the Church has actually reached.
    """
    return ZoneInfo(os.getenv("SCRAPER_TIMEZONE", "America/Sao_Paulo"))


def today_in_scraper_timezone() -> date:
    return datetime.now(scraper_timezone()).date()


def main(
    *,
    dry_run: bool = False,
    start_date: date | None = None,
    days_ahead: int | None = None,
    days_behind: int | None = None,
) -> None:
    """Scrape a period and either print it or POST it to Spring."""
    # Config from .env / env vars (see .env.example)
    import_url = os.getenv("LITURGY_IMPORT_URL", "http://localhost:8080/internal/v1/liturgy/import")
    import_token = os.getenv("LITURGY_IMPORT_TOKEN", "")
    configured_days = (
        days_ahead
        if days_ahead is not None
        else int(os.getenv("SCRAPER_DAYS_AHEAD", "14"))
    )
    if days_behind is not None:
        configured_behind = days_behind
    elif start_date is not None:
        # An explicit --start-date is the window start, not its anchor.
        configured_behind = 0
    else:
        configured_behind = int(os.getenv("SCRAPER_DAYS_BEHIND", "7"))
    timeout = int(os.getenv("HTTP_TIMEOUT_SECONDS", "30"))
    if configured_days < 1:
        raise ValueError("days must be at least 1")
    if configured_behind < 0:
        raise ValueError("SCRAPER_DAYS_BEHIND must not be negative")
    if timeout < 1:
        raise ValueError("HTTP_TIMEOUT_SECONDS must be at least 1")
    if not dry_run and (not import_token or import_token == "change-me"):
        raise ValueError("LITURGY_IMPORT_TOKEN must be configured")

    with httpx.Client(
        timeout=timeout,
        follow_redirects=True,
        headers={"User-Agent": "LiturgyScraper/0.1.0 (+https://liturgiadiaria.edicoescnbb.com.br/)"},
    ) as client:
        service = ScraperService(
            CnbbSource(client),
            VaticanSource(client),
            CnbbParser(),
            VaticanParser(),
            http_client=client,
        )
        # The window reaches backwards as well as forwards so a failed run heals
        # itself: a forward-only window leaves a missed date permanently
        # unimported, and the API answers 503 for it forever.
        start = (start_date or today_in_scraper_timezone()) - timedelta(days=configured_behind)
        end = start + timedelta(days=configured_days + configured_behind - 1)
        batch = service.scrape_period(start, end)
        assert_complete_batch(batch, start, end)
        if dry_run:
            output = batch.model_dump_json(
                by_alias=True,
                exclude_none=True,
                indent=2,
            )
        else:
            result = service.send_to_spring(batch, import_url, import_token)
            output = json.dumps(result, ensure_ascii=False, sort_keys=True)

    print(output)


def assert_complete_batch(batch, start: date, end: date) -> None:
    """Refuse to send a batch the backend would reject, or that is short.

    The import request is validated in full before anything is written, so one
    bad day discards the whole run. A short batch would instead leave the tail
    of the window unimported, and the API answers 503 for those days until a
    later run happens to cover them.
    """
    expected = [start + timedelta(days=offset) for offset in range((end - start).days + 1)]
    present = {day.date for day in batch.days}
    missing = [day for day in expected if day not in present]
    if missing:
        raise RuntimeError(
            f"scraping produced {len(batch.days)} of {len(expected)} days; "
            f"missing {', '.join(day.isoformat() for day in missing)}; batch was not sent"
        )

    for day in batch.days:
        for reading in day.parts.readings:
            for candidate in reading.options or [reading]:
                if not candidate.text:
                    raise RuntimeError(
                        f"{day.date.isoformat()} {reading.type.value} "
                        f"{candidate.reference or '(no citation)'} has no text; batch was not sent"
                    )


def parse_args() -> argparse.Namespace:
    """Parse command-line options for production and read-only runs."""
    parser = argparse.ArgumentParser(description="Fetch and validate daily liturgies")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print the batch JSON without requiring a token or calling Spring",
    )
    parser.add_argument(
        "--start-date",
        type=date.fromisoformat,
        help="first date to scrape in YYYY-MM-DD format (default: today)",
    )
    parser.add_argument(
        "--days",
        type=int,
        dest="days_ahead",
        help="number of days to scrape including the start date (default: SCRAPER_DAYS_AHEAD or 14)",
    )
    parser.add_argument(
        "--days-behind",
        type=int,
        dest="days_behind",
        help="days to re-scrape before the window start (default: SCRAPER_DAYS_BEHIND or 7)",
    )
    return parser.parse_args()


if __name__ == "__main__":
    logging.basicConfig(
        level=os.getenv("LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    try:
        args = parse_args()
        main(
            dry_run=args.dry_run,
            start_date=args.start_date,
            days_ahead=args.days_ahead,
            days_behind=args.days_behind,
        )
    except Exception:
        logging.exception("Liturgy scraper failed")
        raise SystemExit(1)
