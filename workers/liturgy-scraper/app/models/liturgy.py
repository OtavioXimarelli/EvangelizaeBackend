"""Liturgia Diária contract models (Pydantic).

Wire format: JSON with camelCase keys (e.g. "liturgicalColor"), matching the
Java/Spring Boot consumer. Python attributes stay snake_case.

Spec: "Welcome to Markdown.md" §9-§13, §19, §23.
"""

from datetime import date, datetime
from enum import Enum

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    """Base model: serializes snake_case attributes as camelCase JSON keys."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
    )


class ReadingType(str, Enum):
    """Kind of liturgical text (spec §11)."""

    FIRST_READING = "FIRST_READING"
    SECOND_READING = "SECOND_READING"
    PSALM = "PSALM"
    GOSPEL = "GOSPEL"
    ACCLAMATION = "ACCLAMATION"
    SEQUENCE = "SEQUENCE"


class CelebrationType(str, Enum):
    """Rank of the celebration. Comment shows the CNBB source term."""

    WEEKDAY = "WEEKDAY"  # "Dia de semana" (ferial)
    SUNDAY = "SUNDAY"  # "Domingo"
    MEMORIAL = "MEMORIAL"  # "Memória"
    FEAST = "FEAST"  # "Festa"
    SOLEMNITY = "SOLEMNITY"  # "Solenidade"


class LiturgicalColor(str, Enum):
    """Liturgical color. Comment shows the CNBB source term (PT-BR)."""

    GREEN = "GREEN"  # "verde"
    RED = "RED"  # "vermelho"
    WHITE = "WHITE"  # "branco"
    PURPLE = "PURPLE"  # "roxo"
    ROSE = "ROSE"  # "rosa"


class SourceName(str, Enum):
    CNBB = "CNBB"
    VATICAN_NEWS = "VATICAN_NEWS"


class SourceRole(str, Enum):
    PRIMARY = "PRIMARY"  # CNBB
    VALIDATION = "VALIDATION"  # Vatican News


class ValidationStatus(str, Enum):
    VALID = "VALID"
    WARNING = "WARNING"
    REVIEW_REQUIRED = "REVIEW_REQUIRED"


class Reading(CamelModel):
    """One liturgical reading (spec §10).

    Invariant: either `text` (normal case) or `options` (alternative
    readings, e.g. "Jo 11,19-27 ou Lc 10,38-42") -- never both.
    """

    type: ReadingType
    reference: str | None = None
    title: str | None = None
    response: str | None = None  # psalm refrain ("R. ...")
    text: str | None = None
    options: list["Reading"] | None = None  # forward reference

    @model_validator(mode="after")
    def validate_alternatives(self) -> "Reading":
        """Keep normal readings and groups of alternative readings distinct."""
        if self.options is not None:
            if not self.options:
                raise ValueError("options must contain at least one reading")
            if self.text is not None:
                raise ValueError("a reading cannot contain both text and options")
            if any(option.type != self.type for option in self.options):
                raise ValueError("all options must have the same reading type")
        return self


class Celebration(CamelModel):
    """What is celebrated on the day."""

    name: str  # "São Bartolomeu, Apóstolo" or "21º Domingo do Tempo Comum"
    type: CelebrationType
    liturgical_color: LiturgicalColor


class LiturgicalSeason(CamelModel):
    """Liturgical season context."""

    name: str  # "Tempo Comum", "Quaresma", ...
    week: int | None = None  # 21
    liturgical_year: str | None = None  # "A" | "B" | "C" (from "Ano A")


# --- Stage B: remaining contract models (spec §13) ---


class LiturgicalParts(CamelModel):
    """Container for liturgical parts. Minimal now, antiphons/prayers later (spec §12)."""

    readings: list[Reading]


class SourceInfo(CamelModel):
    """Provenance for one source fetch (spec §14)."""

    name: SourceName
    role: SourceRole
    url: str | None = None
    collected_at: datetime  # Pydantic parses ISO strings automatically
    source_hash: str | None = None  # sha256 of raw HTML
    content_hash: str | None = None  # sha256 of canonical JSON


class Validation(CamelModel):
    """Result of cross-source comparison (spec §19)."""

    status: ValidationStatus
    sources_compared: int
    warnings: list[str] = Field(default_factory=list)


class Period(CamelModel):
    """Date window for the batch. from_ -> JSON 'from' via explicit alias.

    GOTCHA: `from` is reserved in Python (like `class` in Java), so attribute is `from_`.
    to_camel("from_") stays "from_" -> need explicit alias="from".
    """

    from_: date = Field(alias="from")
    to: date


class LiturgicalDay(CamelModel):
    """Aggregate for one calendar day."""

    date: date
    celebration: Celebration
    liturgical_season: LiturgicalSeason
    parts: LiturgicalParts
    sources: list[SourceInfo]
    validation: Validation
    note: str | None = None  # e.g. "Hoje, omite-se a Festa..."


class LiturgyImportRequest(CamelModel):
    """Batch envelope POSTed to Spring: POST /internal/v1/liturgy/import (spec §13, §20)."""

    schema_version: str
    scraper_version: str
    scraped_at: datetime
    period: Period
    days: list[LiturgicalDay]
