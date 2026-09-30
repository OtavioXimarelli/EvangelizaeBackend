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
            assert request.headers["x-request-id"]
            imported.update(json.loads(request.content))
            return httpx.Response(
                200,
                json={
                    "importId": "b3b0c1f0-0000-4000-8000-000000000000",
                    "status": "SUCCESS",
                    "received": 1,
                    "processed": 1,
                    "created": 1,
                    "updated": 0,
                    "unchanged": 0,
                    "failed": [],
                },
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


def _batch_of_one() -> object:
    """A one-day batch built from fixtures, independent of the POST under test."""

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text=_fixture("cnbb", "normal_day.json"))

    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        service = ScraperService(
            CnbbSource(client),
            VaticanSource(client),
            CnbbParser(),
            VaticanParser(),
            http_client=client,
        )
        return service.scrape_period(TARGET_DATE, TARGET_DATE)


def _send(handler):
    """POST a one-day batch through a mocked Spring and return the result."""
    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        service = ScraperService(None, None, None, None, http_client=client)
        return service.send_to_spring(
            _batch_of_one(), "https://spring.example/import", "secret"
        )


def test_send_sends_and_honours_request_id():
    seen: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["id"] = request.headers["x-request-id"]
        return httpx.Response(
            200,
            json={"status": "SUCCESS", "received": 1, "processed": 1, "failed": []},
        )

    _send(handler)

    assert seen["id"]


def test_partial_status_is_a_failed_run():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "status": "PARTIAL",
                "received": 1,
                "processed": 0,
                "failed": [{"date": "2026-08-25", "code": "INVALID", "message": "x"}],
            },
        )

    with pytest.raises(ValueError, match="PARTIAL"):
        _send(handler)


def test_short_processed_count_is_a_failed_run():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={"status": "SUCCESS", "received": 1, "processed": 0, "failed": []},
        )

    with pytest.raises(ValueError, match="processed 0 of 1"):
        _send(handler)


def test_rejected_date_is_a_failed_run():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "status": "SUCCESS",
                "received": 1,
                "processed": 1,
                "failed": [{"date": "2026-08-25", "code": "INVALID", "message": "x"}],
            },
        )

    with pytest.raises(ValueError, match="rejected dates: 2026-08-25"):
        _send(handler)


def test_server_error_is_retried_then_succeeds():
    attempts = {"count": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        attempts["count"] += 1
        if attempts["count"] < 3:
            return httpx.Response(503)
        return httpx.Response(
            200,
            json={"status": "SUCCESS", "received": 1, "processed": 1, "failed": []},
        )

    result = _send(handler)

    assert result["status"] == "SUCCESS"
    assert attempts["count"] == 3


def test_forbidden_is_not_retried():
    attempts = {"count": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        attempts["count"] += 1
        return httpx.Response(403)

    with pytest.raises(httpx.HTTPStatusError):
        _send(handler)
    assert attempts["count"] == 1


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
