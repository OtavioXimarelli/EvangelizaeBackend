"""Vatican News source - Validation source (spec §3).

Responsibility: HTTP fetching only.
URL pattern: https://www.vaticannews.va/pt/palavra-do-dia/YYYY/MM/DD.html
Java analogy: second @Repository adapter, used for cross-validation.
"""

from datetime import date
import logging

import httpx
from tenacity import retry, retry_if_exception, stop_after_attempt, wait_exponential


logger = logging.getLogger(__name__)


def _is_retryable(error: BaseException) -> bool:
    if isinstance(error, httpx.TransportError):
        return True
    if isinstance(error, httpx.HTTPStatusError):
        return error.response.status_code in {408, 429} or error.response.status_code >= 500
    return False


class VaticanSource:
    """Fetches raw HTML from Vatican News — Palavra do Dia."""

    BASE_URL = "https://www.vaticannews.va/pt/palavra-do-dia"

    def __init__(self, client: httpx.Client):
        self.client = client

    @retry(
        retry=retry_if_exception(_is_retryable),
        wait=wait_exponential(multiplier=0.5, min=0.5, max=4),
        stop=stop_after_attempt(3),
        reraise=True,
    )
    def fetch_html(self, target_date: date) -> str:
        """Fetch raw HTML for a single date.

        URL example: .../palavra-do-dia/2026/08/21.html
        Use f-string: f"{BASE_URL}/{target_date:%Y/%m/%d}.html"
        """
        response = self.client.get(
            self.url_for(target_date),
            headers={"Accept": "text/html,application/xhtml+xml"},
        )
        response.raise_for_status()
        return response.text

    @classmethod
    def url_for(cls, target_date: date) -> str:
        return f"{cls.BASE_URL}/{target_date:%Y/%m/%d}.html"

    def fetch_many(self, dates: list[date]) -> dict[date, str]:
        """Fetch many dates for validation."""
        result: dict[date, str] = {}
        for target_date in dates:
            try:
                result[target_date] = self.fetch_html(target_date)
            except Exception:
                logger.exception("Vatican News fetch failed for %s", target_date)
        return result
