"""Tests for Vatican parser with saved HTML fixtures (spec §8)."""

from pathlib import Path

from app.models.liturgy import ReadingType
from app.parsers.vatican_parser import VaticanParser

FIXTURES_DIR = Path(__file__).parent / "fixtures" / "vatican"


def _load_fixture(name: str) -> str:
    return (FIXTURES_DIR / name).read_text(encoding="utf-8")


def test_parse_normal_day():
    """Extract readings from Vatican normal day."""
    readings = VaticanParser().parse_readings(_load_fixture("normal_day.html"))

    assert [reading.type for reading in readings] == [
        ReadingType.FIRST_READING,
        ReadingType.GOSPEL,
    ]
    assert readings[0].reference == "2Ts 2,1-3a.14-17"


def test_parse_sunday():
    """Sunday should have second reading."""
    readings = VaticanParser().parse_readings(_load_fixture("sunday.html"))

    assert len(readings) == 3
    assert readings[1].type == ReadingType.SECOND_READING
    assert readings[1].reference == "Rm 11,33-36"


def test_reference_extraction():
    """References like 'Mt 22,34-40' must be extracted cleanly."""
    readings = VaticanParser().parse_readings(_load_fixture("sunday.html"))

    assert readings[-1].reference == "Mt 16,13-20"
