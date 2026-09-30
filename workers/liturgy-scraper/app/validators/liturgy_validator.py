"""Validation + hashing (spec §14-16, §19).

Two hashes per day:
- source_hash  = SHA256(raw HTML) - for audit
- content_hash = SHA256(canonical JSON of liturgical content) - for change detection

Plus cross-source validation: CNBB (PRIMARY) vs Vatican (VALIDATION).
Java analogy: Domain Service + Bean Validation.
"""

import hashlib
import json
import re

from app.models.liturgy import (
    LiturgicalDay,
    Reading,
    ReadingType,
    Validation,
    ValidationStatus,
)


def calculate_source_hash(raw_html: str) -> str:
    """SHA256 of raw HTML string (spec §14.1).

    Returns:
        "sha256:<hex>" e.g. "sha256:abc123..."
    """
    digest = hashlib.sha256(raw_html.encode("utf-8")).hexdigest()
    return f"sha256:{digest}"


def calculate_content_hash(canonical_data: dict) -> str:
    """SHA256 of canonical JSON (spec §16).

    Must be deterministic: sort_keys=True, separators=(",", ":"), ensure_ascii=False.
    Do NOT include scrapedAt, URLs, versions - only liturgical content.
    """
    canonical = json.dumps(
        canonical_data,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    return f"sha256:{digest}"


def content_hash_for_day(day: LiturgicalDay) -> str:
    """Helper: builds canonical dict from LiturgicalDay and hashes it."""
    canonical_data = day.model_dump(
        mode="json",
        by_alias=True,
        exclude_none=True,
        include={"date", "celebration", "liturgical_season", "parts", "note"},
    )
    return calculate_content_hash(canonical_data)


def cross_validate(
    cnbb_readings: list[Reading],
    vatican_readings: list[Reading],
) -> Validation:
    """Compare CNBB vs Vatican readings (spec §19).

    Compares reference strings (e.g. "Mt 22,34-40") for each type.

    Returns:
        Validation with status VALID/WARNING/REVIEW_REQUIRED and warnings list.
        Example warning: "GOSPEL_REFERENCE_MISMATCH: CNBB=Mt 22,34-40 Vatican=Mt 22,35-40"
    """
    if not cnbb_readings:
        return Validation(
            status=ValidationStatus.REVIEW_REQUIRED,
            sources_compared=1 if vatican_readings else 0,
            warnings=["CNBB_READINGS_UNAVAILABLE"],
        )
    if not vatican_readings:
        return Validation(
            status=ValidationStatus.WARNING,
            sources_compared=1,
            warnings=["VATICAN_READINGS_UNAVAILABLE"],
        )

    cnbb_by_type = _references_by_type(cnbb_readings)
    vatican_by_type = _references_by_type(vatican_readings)
    compared_types = {ReadingType.FIRST_READING, ReadingType.GOSPEL}
    if cnbb_by_type.get(ReadingType.SECOND_READING):
        compared_types.add(ReadingType.SECOND_READING)

    warnings: list[str] = []
    for reading_type in sorted(compared_types, key=lambda value: value.value):
        cnbb_refs = cnbb_by_type.get(reading_type, [])
        vatican_refs = vatican_by_type.get(reading_type, [])
        code = reading_type.value
        if not cnbb_refs:
            warnings.append(f"{code}_MISSING_IN_CNBB")
            continue
        if not vatican_refs:
            warnings.append(f"{code}_MISSING_IN_VATICAN_NEWS")
            continue

        normalized_cnbb = {_normalize_reference(reference) for reference in cnbb_refs}
        normalized_vatican = {_normalize_reference(reference) for reference in vatican_refs}
        # One Vatican reading matching any explicitly allowed CNBB option is valid.
        if not normalized_vatican.issubset(normalized_cnbb):
            warnings.append(
                f"{code}_REFERENCE_MISMATCH: "
                f"CNBB={' | '.join(cnbb_refs)} Vatican={' | '.join(vatican_refs)}"
            )

    return Validation(
        status=ValidationStatus.REVIEW_REQUIRED if warnings else ValidationStatus.VALID,
        sources_compared=2,
        warnings=warnings,
    )


def _references_by_type(readings: list[Reading]) -> dict[ReadingType, list[str]]:
    result: dict[ReadingType, list[str]] = {}
    for reading in readings:
        candidates = reading.options or [reading]
        for candidate in candidates:
            if candidate.reference:
                result.setdefault(reading.type, []).append(candidate.reference)
    return result


def _normalize_reference(reference: str) -> str:
    value = reference.casefold().replace("–", "-").replace("—", "-")
    value = re.sub(r"\bcf\.?", "", value)
    return re.sub(r"[\s.;]", "", value)
