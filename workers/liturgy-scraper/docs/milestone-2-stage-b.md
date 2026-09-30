# Milestone 2 — Stage B assignment (saved for later)

Context: Stage A is done — `app/models/liturgy.py` already has `CamelModel`,
the 6 enums, `Reading`, `Celebration`, `LiturgicalSeason`.

Goal: append the remaining contract models (spec §13), following the same
patterns. You write the code; review happens after.

## Patterns to copy (from Stage A)

```python
class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)
```

- Inherit every new model from `CamelModel`
- Required field: `name: str` — optional/nullable: `url: str | None = None`
- Enum field: `role: SourceRole`
- List field: `sources: list[SourceInfo]`
- New imports needed at the top of the file:

```python
from datetime import date, datetime
```

## Models to write (in order)

1. `LiturgicalParts`
   - `readings: list[Reading]`
   - (spec §12: antiphons/prayers will be added here later — keep it minimal now)

2. `SourceInfo`
   - `name: SourceName`
   - `role: SourceRole`
   - `url: str | None = None`
   - `collected_at: datetime`   ← Pydantic parses ISO strings automatically
   - `source_hash: str | None = None`
   - `content_hash: str | None = None`

3. `Validation`
   - `status: ValidationStatus`
   - `sources_compared: int`
   - `warnings: list[str]`      ← default? decide: `= []` is fine here (or use Field(default_factory=list))

4. `Period`
   - `from_: date`   ← GOTCHA: `from` is a reserved word in Python, so the
     attribute is `from_`; `alias_generator=to_camel` turns it into `"from"`
     in JSON automatically. Test this one specifically.
   - `to: date`

5. `LiturgicalDay`
   - `date: date`
   - `celebration: Celebration`
   - `liturgical_season: LiturgicalSeason`
   - `parts: LiturgicalParts`
   - `sources: list[SourceInfo]`
   - `validation: Validation`
   - `note: str | None = None`   ← the "Hoje, omite-se a Festa de Santa Rosa…" note

6. `LiturgyImportRequest` (batch envelope — the JSON POSTed to Spring)
   - `schema_version: str`
   - `scraper_version: str`
   - `scraped_at: datetime`
   - `period: Period`
   - `days: list[LiturgicalDay]`

## Verify

```bash
uv run python -c "from app.models.liturgy import LiturgyImportRequest; print('ok')"
```

Extra credit — prove the `from` alias works:

```bash
uv run python -c "
from datetime import date
from app.models.liturgy import Period
print(Period(from_=date(2026,8,24), to=date(2026,9,6)).model_dump_json(by_alias=True))
"
# expect: {"from":"2026-08-24","to":"2026-09-06"}
```

## Java → Python reminders

| Java | Python here |
|---|---|
| `record X(...)` + Jackson + Bean Validation | `class X(CamelModel)` |
| `@Nullable String` | `str \| None = None` |
| `List<SourceInfo>` | `list[SourceInfo]` |
| `LocalDateTime` / `Instant` | `datetime` (Pydantic parses ISO strings) |
| `LocalDate` | `date` |
| `@JsonProperty` per field | `alias_generator=to_camel` once, on the base |

Wire serialization policy (decided): `model_dump_json(by_alias=True, exclude_none=True)`
