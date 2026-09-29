"""Scraper orchestration service (spec §4-5, §20).

Responsibility: orchestrates fetch -> parse -> validate -> hash -> batch -> POST.
Java analogy: @Service with @Transactional importBatch().

Flow per date:
  CNBB fetch -> CNBB parse -> Vatican fetch -> Vatican parse -> cross_validate -> hashes -> LiturgicalDay

Flow for period (14 days):
  loop days -> collect LiturgicalDay list -> build LiturgyImportRequest -> POST to Spring
"""

from datetime import date, datetime, timedelta, timezone
import logging

import httpx

from app.models.liturgy import (
    LiturgicalDay,
    LiturgyImportRequest,
    Period,
    SourceInfo,
    SourceName,
    SourceRole,
)
from app.validators.liturgy_validator import (
    calculate_content_hash,
    calculate_source_hash,
    content_hash_for_day,
    cross_validate,
)


logger = logging.getLogger(__name__)
SCHEMA_VERSION = "1.0"
SCRAPER_VERSION = "0.1.0"


class ScraperService:
    """Orchestrates the whole scraping pipeline."""

    def __init__(
        self,
        cnbb_source,
        vatican_source,
        cnbb_parser,
        vatican_parser,
        http_client: httpx.Client | None = None,
    ):
        # Constructor injection like Spring - pass adapters/parsers in
        self.cnbb_source = cnbb_source
        self.vatican_source = vatican_source
        self.cnbb_parser = cnbb_parser
        self.vatican_parser = vatican_parser
        self.http_client = http_client or getattr(cnbb_source, "client", None)

    def scrape_period(self, start: date, end: date) -> LiturgyImportRequest:
        """Scrape inclusive period [start, end] -> batch envelope.

        Steps:
          1. generate date range
          2. for each date: self._build_day(date)
          3. filter out None (failed days)
          4. build LiturgyImportRequest with schema_version, scraper_version, scraped_at, period
        """
        if end < start:
            raise ValueError("end date must be on or after start date")

        number_of_days = (end - start).days + 1
        days: list[LiturgicalDay] = []
        for offset in range(number_of_days):
            target_date = start + timedelta(days=offset)
            day = self._build_day(target_date)
            if day is not None:
                days.append(day)
            else:
                # The import is all-or-nothing, so a hole is a failed run. Skip
                # silently and let the caller compare against the window.
                logger.warning("No importable day for %s", target_date)

        return LiturgyImportRequest(
            schema_version=SCHEMA_VERSION,
            scraper_version=SCRAPER_VERSION,
            scraped_at=datetime.now(timezone.utc),
            period=Period(from_=start, to=end),
            days=days,
        )

    def _build_day(self, target_date: date) -> LiturgicalDay | None:
        """Scrape one day; the primary CNBB source is required."""
        try:
            cnbb_html = self.cnbb_source.fetch_html(target_date)
            celebration, season, parts, note = self.cnbb_parser.parse(cnbb_html, target_date)
        except Exception:
            logger.exception("Could not build %s from the primary CNBB source", target_date)
            return None

        vatican_html: str | None = None
        vatican_readings = []
        try:
            vatican_html = self.vatican_source.fetch_html(target_date)
            vatican_readings = self.vatican_parser.parse_readings(vatican_html)
        except Exception:
            logger.warning(
                "Vatican News validation unavailable for %s",
                target_date,
                exc_info=True,
            )

        validation = cross_validate(parts.readings, vatican_readings)
        sources = [
            self._build_source_info(
                name=SourceName.CNBB,
                role=SourceRole.PRIMARY,
                url=self._source_url(self.cnbb_source, target_date),
                raw_html=cnbb_html,
                content_hash=None,
            )
        ]
        if vatican_html is not None:
            vatican_content_hash = None
            if vatican_readings:
                vatican_content_hash = calculate_content_hash(
                    {
                        "readings": [
                            reading.model_dump(mode="json", by_alias=True, exclude_none=True)
                            for reading in vatican_readings
                        ]
                    }
                )
            sources.append(
                self._build_source_info(
                    name=SourceName.VATICAN_NEWS,
                    role=SourceRole.VALIDATION,
                    url=self._source_url(self.vatican_source, target_date),
                    raw_html=vatican_html,
                    content_hash=vatican_content_hash,
                )
            )

        day = LiturgicalDay(
            date=target_date,
            celebration=celebration,
            liturgical_season=season,
            parts=parts,
            sources=sources,
            validation=validation,
            note=note,
        )
        sources[0].content_hash = content_hash_for_day(day)
        return day

    def _build_source_info(
        self,
        name: SourceName,
        role: SourceRole,
        url: str | None,
        raw_html: str,
        content_hash: str | None,
    ) -> SourceInfo:
        """Helper to create SourceInfo with collected_at=now and hashes."""
        return SourceInfo(
            name=name,
            role=role,
            url=url,
            collected_at=datetime.now(timezone.utc),
            source_hash=calculate_source_hash(raw_html),
            content_hash=content_hash,
        )

    def send_to_spring(self, batch: LiturgyImportRequest, import_url: str, token: str) -> dict:
        """POST batch to Spring Boot (spec §20).

        Endpoint: POST /internal/v1/liturgy/import
        Headers: Authorization: Bearer <token>
        Body: batch.model_dump_json(by_alias=True, exclude_none=True)

        Returns:
            Parsed JSON response: {importId, status, processed, created, updated, unchanged}
        """
        if not token:
            raise ValueError("an import bearer token is required")
        headers = {
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        body = batch.model_dump_json(by_alias=True, exclude_none=True)
        if self.http_client is not None:
            response = self.http_client.post(import_url, content=body, headers=headers)
        else:
            response = httpx.post(import_url, content=body, headers=headers)
        response.raise_for_status()
        result = response.json()
        if not isinstance(result, dict):
            raise ValueError("Spring import endpoint returned a non-object JSON response")
        return result

    @staticmethod
    def _source_url(source, target_date: date) -> str | None:
        url_for = getattr(source, "url_for", None)
        return url_for(target_date) if callable(url_for) else None
