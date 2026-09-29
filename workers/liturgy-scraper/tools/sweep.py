"""Parse-sweep gate: prove the parser handles every day shape CNBB publishes.

The import is validated in full before anything is written, so one unparseable
day discards the whole run. A 120-day window is not enough: the shapes that broke
this parser (2026-04-04, 2026-11-02, 2026-12-24, 2026-12-25) sit months apart, and
a window started from any fixed date will miss most of them. A full liturgical
year is the smallest sweep that exercises all of them.

Two things are checked per day, and the second is the one that matters:

1. The day parses at all, and every reading and option carries text.
2. Every citation published is one the day's own summary named. Non-empty text is
   not sufficient: before the citations were matched against the summary, the
   Easter Vigil (2026-04-04) published fifteen readings from other Masses, every
   one of them non-empty and every one of them wrong.

This needs network access to CNBB, so it cannot run in the offline test suite. It
runs in the scheduled `sweep` workflow instead, which is how a CNBB markup change
gets caught within a day rather than at the next deploy.

    uv run python tools/sweep.py
    uv run python tools/sweep.py --start 2027-01-01 --days 60

Exits 0 when every day parses, every reading carries text, and no day publishes
a citation its summary does not name.
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from datetime import date, timedelta

import httpx
from bs4 import BeautifulSoup

from app.parsers.cnbb_parser import CnbbParser
from app.sources.cnbb import CnbbSource


# Two liturgical years, so the Triduum, All Saints/All Souls and the whole
# Christmas octave are each crossed more than once.
DEFAULT_START = date(2026, 1, 1)
DEFAULT_DAYS = 400
USER_AGENT = "LiturgyScraper/0.1.0 (+https://liturgiadiaria.edicoescnbb.com.br/)"


def check_day(parser: CnbbParser, html: str, target: date) -> list[str]:
    """Return the reasons this day is not publishable; empty means it is."""
    celebration, _, parts, _ = parser.parse(html, target)
    problems: list[str] = []

    for reading in parts.readings:
        for candidate in reading.options or [reading]:
            if not candidate.text:
                problems.append(f"{reading.type.value} {candidate.reference}: no text")

    summary = CnbbParser.extract_summary_references(
        BeautifulSoup(str(json.loads(html)["content"]["details"]), "lxml")
    )
    for reading in parts.readings:
        for candidate in reading.options or [reading]:
            if not any(parser._quotes(candidate.reference, name) for name in summary):
                problems.append(
                    f"{reading.type.value} {candidate.reference}: not in the day's summary"
                )

    print(
        f"ok   {target} {celebration.name} "
        f"({len(parts.readings)} readings, {len(summary)} citations)"
    )
    return problems


def sweep(start: date, days: int, timeout: int) -> int:
    """Parse `days` consecutive days from `start`; return the failure count."""
    logging.getLogger().setLevel(logging.ERROR)
    parser = CnbbParser()
    failures = 0

    with httpx.Client(
        timeout=timeout,
        follow_redirects=True,
        headers={"User-Agent": USER_AGENT},
    ) as client:
        source = CnbbSource(client)
        for offset in range(days):
            target = start + timedelta(days=offset)
            try:
                html = source.fetch_html(target)
                problems = check_day(parser, html, target)
            except Exception as error:  # noqa: BLE001 - the gate reports every shape
                failures += 1
                print(f"FAIL {target} {type(error).__name__}: {error}")
                continue
            if problems:
                failures += 1
                print(f"FAIL {target} {len(problems)} problem(s)")
                for problem in problems:
                    print(f"       {problem}")

    print(f"\n{days - failures}/{days} parsed")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--start", type=date.fromisoformat, default=DEFAULT_START)
    parser.add_argument("--days", type=int, default=DEFAULT_DAYS)
    parser.add_argument("--timeout", type=int, default=30)
    args = parser.parse_args()
    if args.days < 1:
        print("--days must be at least 1", file=sys.stderr)
        return 2
    return 1 if sweep(args.start, args.days, args.timeout) else 0


if __name__ == "__main__":
    raise SystemExit(main())
