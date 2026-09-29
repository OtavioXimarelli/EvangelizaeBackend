from datetime import date
import json
from pathlib import Path

import httpx
import pytest

from app.models.liturgy import SourceRole, ValidationStatus
from app.parsers.cnbb_parser import CnbbParser
from app.parsers.vatican_parser import VaticanParser
from app.services.scraper_service import ScraperService
from app.sources.cnbb import CnbbSource
from app.sources.vatican import VaticanSource


FIXTURES_DIR = Path(__file__).parent / "fixtures"
TARGET_DATE = date(2026, 8, 25)


def _fixture(source: str, name: str) -> str:
    return (FIXTURES_DIR / source / name).read_text(encoding="utf-8")


def test_end_to_end_batch_and_spring_post():
    imported: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.host == "api-liturgia.edicoescnbb.com.br":
            assert request.url.path.endswith("/2026-08-25")
            assert request.headers["origin"] == "https://liturgiadiaria.edicoescnbb.com.br"
            return httpx.Response(200, text=_fixture("cnbb", "normal_day.json"))
        if request.url.host == "www.vaticannews.va":
            assert request.url.path.endswith("/2026/08/25.html")
            return httpx.Response(200, text=_fixture("vatican", "normal_day.html"))
        if request.url.host == "spring.example":
            assert request.headers["authorization"] == "Bearer secret"
            imported.update(json.loads(request.content))
            return httpx.Response(
                200,
                json={"status": "SUCCESS", "processed": 1, "created": 1},
            )
        raise AssertionError(f"unexpected URL: {request.url}")

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        service = ScraperService(
            CnbbSource(client),
            VaticanSource(client),
            CnbbParser(),
            VaticanParser(),
            http_client=client,
        )
        batch = service.scrape_period(TARGET_DATE, TARGET_DATE)
        response = service.send_to_spring(batch, "https://spring.example/import", "secret")

    assert response["status"] == "SUCCESS"
    assert batch.period.from_ == TARGET_DATE
    assert len(batch.days) == 1
    day = batch.days[0]
    assert day.validation.status == ValidationStatus.VALID
    assert [source.role for source in day.sources] == [
        SourceRole.PRIMARY,
        SourceRole.VALIDATION,
    ]
    assert all(source.source_hash.startswith("sha256:") for source in day.sources)
    assert all(source.content_hash.startswith("sha256:") for source in day.sources)
    assert imported["period"] == {"from": "2026-08-25", "to": "2026-08-25"}
    assert imported["days"][0]["sources"][0]["role"] == "PRIMARY"


def test_vatican_failure_keeps_primary_day_with_warning():
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.host == "api-liturgia.edicoescnbb.com.br":
            return httpx.Response(200, text=_fixture("cnbb", "normal_day.json"))
        return httpx.Response(404)

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        service = ScraperService(
            CnbbSource(client),
            VaticanSource(client),
            CnbbParser(),
            VaticanParser(),
            http_client=client,
        )
        batch = service.scrape_period(TARGET_DATE, TARGET_DATE)

    assert len(batch.days) == 1
    assert len(batch.days[0].sources) == 1
    assert batch.days[0].validation.status == ValidationStatus.WARNING
    assert batch.days[0].validation.warnings == ["VATICAN_READINGS_UNAVAILABLE"]


def test_primary_failure_is_skipped_and_invalid_period_is_rejected():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404)

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        service = ScraperService(
            CnbbSource(client),
            VaticanSource(client),
            CnbbParser(),
            VaticanParser(),
            http_client=client,
        )
        batch = service.scrape_period(TARGET_DATE, TARGET_DATE)

        assert batch.days == []
        with pytest.raises(ValueError, match="end date"):
            service.scrape_period(date(2026, 8, 26), TARGET_DATE)
