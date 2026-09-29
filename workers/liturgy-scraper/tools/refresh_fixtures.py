"""Re-fetch the CNBB fixtures for the dates this parser has broken on.

The fixtures exist so the offline suite can catch a markup change without the
network. Refreshing them is a deliberate act: a fixture is evidence about what
the source published on a date, so a refresh must land as a reviewable diff and
never be committed by a machine.

    uv run python tools/refresh_fixtures.py            # only if the sweep failed
    uv run python tools/refresh_fixtures.py --dry-run  # report, change nothing
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import date
from pathlib import Path

import httpx

from app.sources.cnbb import CnbbSource


FIXTURES = Path(__file__).resolve().parent.parent / "tests" / "fixtures" / "cnbb"
USER_AGENT = "LiturgyScraper/0.1.0 (+https://liturgiadiaria.edicoescnbb.com.br/)"

# Every date the parser has broken on, with the reason. Adding a date here is how
# a new shape gets a regression test.
KNOWN_SHAPES: dict[str, str] = {
    "2026-04-04": "easter_vigil",
    "2026-04-05": "easter_sunday",
    "2026-09-29": "alternative_first_reading",
    "2026-11-02": "optional_readings",
    "2026-12-21": "alternative_first_reading",
    "2026-12-24": "multiple_masses",
    "2026-12-25": "multiple_masses",
    "2027-01-25": "alternative_first_reading",
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--timeout", type=int, default=30)
    args = parser.parse_args()

    with httpx.Client(
        timeout=args.timeout,
        follow_redirects=True,
        headers={"User-Agent": USER_AGENT},
    ) as client:
        source = CnbbSource(client)
        for iso, shape in sorted(KNOWN_SHAPES.items()):
            target = date.fromisoformat(iso)
            path = FIXTURES / f"{shape}_{iso}.json"
            body = source.fetch_html(target)
            # Re-serialise so the file has no trailing-whitespace drift.
            canonical = json.dumps(json.loads(body), ensure_ascii=False, indent=2) + "\n"
            existing = path.read_text(encoding="utf-8") if path.exists() else None
            if existing == canonical:
                print(f"unchanged {path.name}")
                continue
            if args.dry_run:
                print(f"WOULD CHANGE {path.name}")
                continue
            path.write_text(canonical, encoding="utf-8")
            print(f"updated   {path.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
