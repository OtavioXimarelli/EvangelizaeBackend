from datetime import date, datetime, timezone
import hashlib

import pytest
from pydantic import ValidationError

from app.models.liturgy import (
    Celebration,
    CelebrationType,
    LiturgicalColor,
    LiturgicalDay,
    LiturgicalParts,
    LiturgicalSeason,
    Period,
    Reading,
    ReadingType,
    SourceInfo,
    SourceName,
    SourceRole,
    Validation,
    ValidationStatus,
)
from app.validators.liturgy_validator import (
    calculate_content_hash,
    calculate_source_hash,
    content_hash_for_day,
    cross_validate,
)


def _reading(reading_type: ReadingType, reference: str) -> Reading:
    return Reading(type=reading_type, reference=reference, text="text")


def _day(source_url: str) -> LiturgicalDay:
    return LiturgicalDay(
        date=date(2026, 8, 25),
        celebration=Celebration(
            name="21ª Semana do Tempo Comum",
            type=CelebrationType.WEEKDAY,
            liturgical_color=LiturgicalColor.GREEN,
        ),
        liturgical_season=LiturgicalSeason(name="Tempo Comum", week=21),
        parts=LiturgicalParts(readings=[_reading(ReadingType.GOSPEL, "Mt 23,23-26")]),
        sources=[
            SourceInfo(
                name=SourceName.CNBB,
                role=SourceRole.PRIMARY,
                url=source_url,
                collected_at=datetime.now(timezone.utc),
            )
        ],
        validation=Validation(
            status=ValidationStatus.VALID,
            sources_compared=2,
        ),
    )


def test_period_uses_reserved_from_alias():
    period = Period(from_=date(2026, 8, 24), to=date(2026, 9, 6))

    assert period.model_dump(mode="json", by_alias=True) == {
        "from": "2026-08-24",
        "to": "2026-09-06",
    }


def test_reading_rejects_text_and_options_together():
    with pytest.raises(ValidationError, match="both text and options"):
        Reading(
            type=ReadingType.GOSPEL,
            text="one",
            options=[_reading(ReadingType.GOSPEL, "Mt 1,1")],
        )


def test_hashes_are_deterministic_and_content_hash_ignores_provenance():
    assert calculate_source_hash("abc") == f"sha256:{hashlib.sha256(b'abc').hexdigest()}"
    assert calculate_content_hash({"b": 2, "a": "á"}) == calculate_content_hash(
        {"a": "á", "b": 2}
    )
    assert content_hash_for_day(_day("https://first.example")) == content_hash_for_day(
        _day("https://second.example")
    )


def test_cross_validation_accepts_one_declared_alternative():
    cnbb = [
        _reading(ReadingType.FIRST_READING, "1Jo 4,7-16"),
        Reading(
            type=ReadingType.GOSPEL,
            options=[
                _reading(ReadingType.GOSPEL, "Jo 11,19-27"),
                _reading(ReadingType.GOSPEL, "Lc 10,38-42"),
            ],
        ),
    ]
    vatican = [
        _reading(ReadingType.FIRST_READING, "1Jo 4,7-16"),
        _reading(ReadingType.GOSPEL, "Lc 10,38-42"),
    ]

    validation = cross_validate(cnbb, vatican)

    assert validation.status == ValidationStatus.VALID
    assert validation.sources_compared == 2
    assert validation.warnings == []


def test_cross_validation_marks_mismatch_and_missing_source():
    cnbb = [
        _reading(ReadingType.FIRST_READING, "Ez 37,1-14"),
        _reading(ReadingType.GOSPEL, "Mt 22,34-40"),
    ]
    mismatched = [
        _reading(ReadingType.FIRST_READING, "Ez 37,1-14"),
        _reading(ReadingType.GOSPEL, "Mt 22,35-40"),
    ]

    review = cross_validate(cnbb, mismatched)
    unavailable = cross_validate(cnbb, [])

    assert review.status == ValidationStatus.REVIEW_REQUIRED
    assert review.warnings == [
        "GOSPEL_REFERENCE_MISMATCH: CNBB=Mt 22,34-40 Vatican=Mt 22,35-40"
    ]
    assert unavailable.status == ValidationStatus.WARNING
    assert unavailable.sources_compared == 1

