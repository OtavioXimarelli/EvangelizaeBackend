from datetime import date
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

import app.main as main_module


def _day(day: date) -> SimpleNamespace:
    reading = SimpleNamespace(
        type=SimpleNamespace(value="GOSPEL"), reference="Jo 1,47-51", text="T", options=None
    )
    parts = SimpleNamespace(readings=[reading])
    return SimpleNamespace(date=day, parts=parts)


def test_dry_run_prints_batch_without_token_or_post(monkeypatch, capsys):
    batch = SimpleNamespace(
        days=[_day(date(2026, 9, 1)), _day(date(2026, 9, 2))],
        model_dump_json=MagicMock(return_value='{"days": []}'),
    )
    service = MagicMock()
    service.scrape_period.return_value = batch
    client = MagicMock()
    client.__enter__.return_value = client

    monkeypatch.delenv("LITURGY_IMPORT_TOKEN", raising=False)
    monkeypatch.setattr(main_module.httpx, "Client", MagicMock(return_value=client))
    monkeypatch.setattr(
        main_module,
        "ScraperService",
        MagicMock(return_value=service),
    )

    main_module.main(
        dry_run=True,
        start_date=date(2026, 9, 1),
        days_ahead=2,
    )

    assert capsys.readouterr().out == '{"days": []}\n'
    service.scrape_period.assert_called_once_with(
        date(2026, 9, 1),
        date(2026, 9, 2),
    )
    service.send_to_spring.assert_not_called()
    batch.model_dump_json.assert_called_once_with(
        by_alias=True,
        exclude_none=True,
        indent=2,
    )


def test_import_mode_still_requires_token(monkeypatch):
    monkeypatch.delenv("LITURGY_IMPORT_TOKEN", raising=False)

    with pytest.raises(ValueError, match="LITURGY_IMPORT_TOKEN"):
        main_module.main()


def test_rejects_non_positive_days():
    with pytest.raises(ValueError, match="days must be at least 1"):
        main_module.main(dry_run=True, days_ahead=0)


def test_rejects_negative_lookbehind():
    with pytest.raises(ValueError, match="SCRAPER_DAYS_BEHIND must not be negative"):
        main_module.main(dry_run=True, days_behind=-1)


def _stub_scrape(monkeypatch, days):
    service = MagicMock()
    service.scrape_period.return_value = SimpleNamespace(
        days=days, model_dump_json=MagicMock(return_value="{}")
    )
    client = MagicMock()
    client.__enter__.return_value = client
    monkeypatch.setattr(main_module.httpx, "Client", MagicMock(return_value=client))
    monkeypatch.setattr(main_module, "ScraperService", MagicMock(return_value=service))
    return service


def test_window_reaches_backwards_by_default(monkeypatch):
    """A forward-only window leaves a failed run's date permanently 503."""
    service = _stub_scrape(monkeypatch, [])
    monkeypatch.setattr(main_module, "today_in_scraper_timezone", lambda: date(2026, 9, 28))

    with pytest.raises(RuntimeError):
        main_module.main(dry_run=True)

    # 2026-09-21 … 2026-10-11 = 7 behind + 14 ahead = 21 days
    service.scrape_period.assert_called_once_with(
        date(2026, 9, 21),
        date(2026, 10, 11),
    )


def test_short_batch_is_not_sent(monkeypatch):
    service = _stub_scrape(monkeypatch, [_day(date(2026, 9, 1))])

    with pytest.raises(RuntimeError, match="1 of 3 days"):
        main_module.main(dry_run=True, start_date=date(2026, 9, 1), days_ahead=3)

    service.send_to_spring.assert_not_called()


def test_reading_without_text_is_not_sent(monkeypatch):
    day = _day(date(2026, 9, 1))
    day.parts.readings[0].text = ""
    service = _stub_scrape(monkeypatch, [day])

    with pytest.raises(RuntimeError, match="GOSPEL Jo 1,47-51 has no text"):
        main_module.main(dry_run=True, start_date=date(2026, 9, 1), days_ahead=1)

    service.send_to_spring.assert_not_called()
