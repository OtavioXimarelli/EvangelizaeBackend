"""Tests for CNBB parser with saved HTML fixtures (spec §8).

If CNBB changes HTML structure, these tests break -> you fix parser.
Java analogy: @SpringBootTest with MockMvc + fixture files.
"""

from datetime import date
from pathlib import Path

import pytest

from app.parsers.cnbb_parser import CnbbParser
from app.models.liturgy import CelebrationType, LiturgicalColor, ReadingType

FIXTURES_DIR = Path(__file__).parent / "fixtures" / "cnbb"


def _load_fixture(name: str) -> str:
    """Helper to load HTML fixture."""
    return (FIXTURES_DIR / name).read_text(encoding="utf-8")


def test_parse_normal_day():
    """Normal weekday: 1 reading + psalm + gospel."""
    celebration, season, parts, note = CnbbParser().parse(
        _load_fixture("normal_day.json"), date(2026, 8, 25)
    )

    assert celebration.type == CelebrationType.WEEKDAY
    assert celebration.liturgical_color == LiturgicalColor.GREEN
    assert season.name == "Tempo Comum"
    assert season.week == 21
    assert [reading.type for reading in parts.readings] == [
        ReadingType.FIRST_READING,
        ReadingType.PSALM,
        ReadingType.GOSPEL,
    ]
    assert parts.readings[-1].reference == "Mt 23,23-26"
    assert parts.readings[1].response == "O Senhor vem julgar nossa terra."
    assert parts.readings[1].text.startswith("R. O Senhor vem julgar nossa terra.")
    assert note is None


def test_parse_sunday():
    """Sunday: includes second reading."""
    celebration, season, parts, note = CnbbParser().parse(
        _load_fixture("sunday.json"), date(2026, 8, 23)
    )

    assert celebration.type == CelebrationType.SUNDAY
    assert season.liturgical_year == "A"
    assert len(parts.readings) == 4
    assert parts.readings[2].type == ReadingType.SECOND_READING
    # <i> elements are italic scripture quotations, never a liturgical note.
    assert note is None


def test_parse_solemnity():
    """Solemnity: high rank, white/red color."""
    celebration, _, parts, _ = CnbbParser().parse(
        _load_fixture("solemnity.json"), date(2026, 8, 15)
    )

    assert celebration.type == CelebrationType.SOLEMNITY
    assert celebration.liturgical_color == LiturgicalColor.WHITE
    assert parts.readings[0].reference == "Ap 11,19a;12,1-6a.10ab"


def test_parse_alternative_readings():
    """Case 'Jo 11,19-27 ou Lc 10,38-42' -> Reading.options with 2 entries."""
    _, _, parts, _ = CnbbParser().parse(
        _load_fixture("alternatives.json"), date(2026, 7, 29)
    )

    gospel = parts.readings[-1]
    assert gospel.type == ReadingType.GOSPEL
    assert gospel.text is None
    assert gospel.options is not None
    assert [option.reference for option in gospel.options] == ["Jo 11,19-27", "Lc 10,38-42"]
    # Only one body follows the marker, so every option carries that text.
    assert gospel.options[0].text == "Texto da primeira opção do evangelho."
    assert gospel.options[1].text == "Texto da primeira opção do evangelho."


@pytest.mark.parametrize(
    ("fixture", "target", "expected"),
    [
        (
            "alternative_first_reading_2026-09-29.json",
            date(2026, 9, 29),
            ["Dn 7,9-10.13-14", "Ap 12,7-12a"],
        ),
        (
            "alternative_first_reading_2026-12-21.json",
            date(2026, 12, 21),
            ["Ct 2,8-14", "Sf 3,14-18a"],
        ),
        (
            "alternative_first_reading_2027-01-25.json",
            date(2027, 1, 25),
            ["At 22,3-16", "At 9,1-22"],
        ),
    ],
)
def test_alternative_first_readings_are_separate_texts(fixture, target, expected):
    """Two permitted first readings -> two readings, each with its own body.

    These are the three days in a 120-day window that used to produce an empty
    option and reject the whole batch.
    """
    _, _, parts, _ = CnbbParser().parse(_load_fixture(fixture), target)

    first_readings = [
        reading for reading in parts.readings if reading.type == ReadingType.FIRST_READING
    ]
    assert [reading.reference for reading in first_readings] == expected
    assert all(reading.options is None for reading in first_readings)
    assert all(reading.text for reading in first_readings)
    assert len({reading.text for reading in first_readings}) == 2

    gospel = parts.readings[-1]
    assert gospel.type == ReadingType.GOSPEL
    # Pins the off-by-one: a naive positional zip shifts every later citation.
    assert gospel.reference == {
        date(2026, 9, 29): "Jo 1,47-51",
        date(2026, 12, 21): "Lc 1,39-45",
        date(2027, 1, 25): "Mc 16,15-18",
    }[target]
    assert gospel.text


def test_optional_readings_block_is_not_todays_liturgy():
    """2026-11-02 appends a catalogue of readings the day does not use."""
    _, _, parts, _ = CnbbParser().parse(
        _load_fixture("optional_readings_2026-11-02.json"), date(2026, 11, 2)
    )

    assert [reading.reference for reading in parts.readings] == [
        "Jó 19,1.23-27a",
        "Sl 23(24),1-2.3-4ab.5-6 (R. cf. 6)",
        "1Cor 15,20-24a.25-28",
        "Lc 12,35-40",
    ]
    assert all(reading.text for reading in parts.readings)


def test_only_the_days_own_mass_is_parsed():
    """Christmas Day publishes several Masses; the summary names the day Mass."""
    _, _, parts, _ = CnbbParser().parse(
        _load_fixture("multiple_masses_2026-12-25.json"), date(2026, 12, 25)
    )

    assert [reading.reference for reading in parts.readings] == [
        "Is 52,7-10",
        "Sl 97(98),1.2-3ab.3cd-4.5-6 (R. 3cd)",
        "Hb 1,1-6",
        "Jo 1,1-18",
    ]
    assert all(reading.text for reading in parts.readings)


def test_christmas_eve_publishes_three_masses():
    _, _, parts, _ = CnbbParser().parse(
        _load_fixture("multiple_masses_2026-12-24.json"), date(2026, 12, 24)
    )

    assert [reading.reference for reading in parts.readings] == [
        "2Sm 7,1-5.8b-12.14a.16",
        "Sl 88(89),2-3.4-5.27 e 29 (R. 2a)",
        "Lc 1,67-79",
    ]
    assert all(reading.text for reading in parts.readings)


def test_easter_vigil_publishes_only_its_own_readings():
    """2026-04-04 shares its body with the readings of the Masses that follow.

    The body carries seven numbered readings, each with a psalm, and only four
    of them are the Vigil's. A positional walk published fifteen of them.
    """
    _, _, parts, _ = CnbbParser().parse(
        _load_fixture("easter_vigil_2026-04-04.json"), date(2026, 4, 4)
    )

    assert [reading.reference for reading in parts.readings] == [
        "Gn 1,1-2,2",
        "Sl 103(104),1-2a.5-6.10.12.13-14.24.35c (R. cf. 30)",
        "Rm 6,3-11 Sl 117(118) 1-2.16ab-17.22-23",
        "Evangelho: Mt 28,1-10",
    ]
    assert all(reading.text for reading in parts.readings)


def test_easter_sunday_declares_no_season():
    """CNBB publishes Easter Sunday with an empty title and no season line."""
    celebration, season, parts, _ = CnbbParser().parse(
        _load_fixture("easter_sunday_2026-04-05.json"), date(2026, 4, 5)
    )

    assert celebration.type == CelebrationType.SUNDAY
    assert celebration.liturgical_color == LiturgicalColor.WHITE
    # The season falls back to the source's own wording rather than failing the
    # day: a rejected batch would leave Easter permanently unimported.
    assert season.name == celebration.name
    assert season.week is None
    assert season.liturgical_year == "A"
    assert [reading.reference for reading in parts.readings] == [
        "At 10,34a.37-43",
        "Sl 117(118),1-2.16ab-17.22-23 (R. 24)",
        "1Cor 5,6b-8",
        "Jo 20,1-9",
    ]
    assert all(reading.text for reading in parts.readings)


def test_rejects_wrong_response_date():
    with pytest.raises(ValueError, match="returned 2026-08-25"):
        CnbbParser().parse(_load_fixture("normal_day.json"), date(2026, 8, 26))
