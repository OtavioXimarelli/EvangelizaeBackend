from datetime import date
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

import app.main as main_module


def test_dry_run_prints_batch_without_token_or_post(monkeypatch, capsys):
    batch = SimpleNamespace(
        days=[object()],
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
