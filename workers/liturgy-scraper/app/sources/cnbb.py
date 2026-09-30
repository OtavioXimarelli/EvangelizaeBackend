"""CNBB source - Primary source (spec §3).

Responsibility: HTTP fetching only. No parsing.
Java analogy: @Repository / HttpClient adapter with @Retryable.
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


class CnbbSource:
    """Fetches raw HTML from CNBB / Igreja em Oração.

    The site's own Next.js client reads the date-keyed public API below. The
    response is JSON containing the ``details``, ``leituras`` and ``body`` HTML
    fragments parsed by :class:`CnbbParser`.
    """

    BASE_URL = "https://api-liturgia.edicoescnbb.com.br/contents/in/date"
    SITE_URL = "https://liturgiadiaria.edicoescnbb.com.br/"

    def __init__(self, client: httpx.Client):
        # injected client = like Spring RestClient / CloseableHttpClient
        self.client = client

    @retry(
        retry=retry_if_exception(_is_retryable),
        wait=wait_exponential(multiplier=0.5, min=0.5, max=4),
        stop=stop_after_attempt(3),
        reraise=True,
    )
    def fetch_html(self, target_date: date) -> str:
        """Fetch raw HTML for a single date.

        Args:
            target_date: LocalDate to fetch (e.g. date(2026, 8, 24))

        Returns:
            Raw HTML string (for source_hash + parser input)
        """
        response = self.client.get(
            self.url_for(target_date),
            headers={
                "Accept": "application/json",
                "Origin": self.SITE_URL.rstrip("/"),
                "Referer": self.SITE_URL,
            },
        )
        response.raise_for_status()
        return response.text

    @classmethod
    def url_for(cls, target_date: date) -> str:
        """Return the public API URL used by the CNBB web application."""
        return f"{cls.BASE_URL}/{target_date.isoformat()}"

    def fetch_many(self, dates: list[date]) -> dict[date, str]:
        """Fetch many dates sequentially (14-day window).

        Returns:
            dict mapping date -> raw HTML. Failures should be logged, not crash whole batch.
        """
        result: dict[date, str] = {}
        for target_date in dates:
            try:
                result[target_date] = self.fetch_html(target_date)
            except Exception:
                logger.exception("CNBB fetch failed for %s", target_date)
        return result
