"""Parser for the CNBB ``contents/in/date`` response.

The public endpoint returns JSON, while the liturgical metadata and body inside
``content`` are HTML fragments. Keeping both layers here lets the source
adapter remain concerned only with HTTP.
"""

from __future__ import annotations

from datetime import date
import json
import re
import unicodedata

from bs4 import BeautifulSoup, NavigableString, Tag

from app.models.liturgy import (
    Celebration,
    CelebrationType,
    LiturgicalColor,
    LiturgicalParts,
    LiturgicalSeason,
    Reading,
    ReadingType,
)


_COLOR_MAP = {
    "branco": LiturgicalColor.WHITE,
    "branca": LiturgicalColor.WHITE,
    "verde": LiturgicalColor.GREEN,
    "vermelho": LiturgicalColor.RED,
    "vermelha": LiturgicalColor.RED,
    "roxo": LiturgicalColor.PURPLE,
    "roxa": LiturgicalColor.PURPLE,
    "rosa": LiturgicalColor.ROSE,
}


def _clean(value: str) -> str:
    return re.sub(r"\s+", " ", value.replace("\xa0", " ")).strip()


def _fold(value: str) -> str:
    normalized = unicodedata.normalize("NFKD", value)
    return "".join(char for char in normalized if not unicodedata.combining(char)).casefold()


class CnbbParser:
    """Parse a CNBB JSON response (or an equivalent saved HTML fixture)."""

    def parse(
        self, html: str, target_date: date
    ) -> tuple[Celebration, LiturgicalSeason, LiturgicalParts, str | None]:
        """Parse a complete day and verify that the source returned that date."""
        if not html or not html.strip():
            raise ValueError("CNBB returned an empty response")

        content: dict | None = None
        try:
            payload = json.loads(html)
        except json.JSONDecodeError:
            payload = None

        if isinstance(payload, dict):
            possible_content = payload.get("content")
            if not isinstance(possible_content, dict):
                source_error = payload.get("error", "missing content object")
                raise ValueError(f"invalid CNBB response: {source_error}")
            content = possible_content

        if content is not None:
            returned_date = content.get("date")
            if returned_date and returned_date != target_date.isoformat():
                raise ValueError(
                    f"CNBB returned {returned_date} for requested date {target_date.isoformat()}"
                )

            details = str(content.get("details") or "")
            body = str(content.get("body") or "")
            soup = BeautifulSoup(f"<main>{details}<article>{body}</article></main>", "lxml")

            metadata = soup.new_tag("meta")
            metadata["id"] = "cnbb-metadata"
            metadata["data-color"] = str(content.get("color") or "")
            metadata["data-title"] = str(content.get("title") or "")
            soup.main.insert(0, metadata)

            references = self._extract_summary_references(BeautifulSoup(details, "lxml"))
            if not references:
                references = self._extract_summary_references(
                    BeautifulSoup(str(content.get("leituras") or ""), "lxml")
                )
        else:
            soup = BeautifulSoup(html, "lxml")
            references = self._extract_summary_references(soup)

        reference_data = soup.new_tag("script", id="cnbb-reference-data")
        reference_data.string = json.dumps(references, ensure_ascii=False)
        (soup.body or soup).insert(0, reference_data)

        celebration = self._parse_celebration(soup)
        season = self._parse_season(soup)
        readings = self._parse_readings(soup)
        if not readings:
            raise ValueError("CNBB response contains no recognizable readings")
        return celebration, season, LiturgicalParts(readings=readings), self._parse_note(soup)

    def _parse_celebration(self, soup: BeautifulSoup) -> Celebration:
        metadata = soup.find(id="cnbb-metadata")
        color_text = str(metadata.get("data-color", "")) if metadata else ""
        if not color_text:
            stole = soup.find("img", src=re.compile(r"/estolas/", re.I))
            color_text = str(stole.get("src", "")) if stole else soup.get_text(" ")

        folded_color = _fold(color_text)
        color = next((value for name, value in _COLOR_MAP.items() if name in folded_color), None)
        if color is None:
            raise ValueError(f"unknown CNBB liturgical color: {color_text!r}")

        # In the CNBB details fragment the first bold value is the celebration.
        name_tag = soup.find("b")
        name = _clean(name_tag.get_text(" ", strip=True)) if name_tag else ""
        if not name:
            name = str(metadata.get("data-title", "")) if metadata else ""
            name = _clean(name)
        if not name:
            raise ValueError("could not identify the CNBB celebration")

        context = _fold(
            _clean(name_tag.parent.get_text(" ", strip=True)) if name_tag else soup.get_text(" ")
        )
        if "solenidade" in context:
            celebration_type = CelebrationType.SOLEMNITY
        elif re.search(r"\bfesta\b", context):
            celebration_type = CelebrationType.FEAST
        elif "memoria" in context:
            celebration_type = CelebrationType.MEMORIAL
        elif "domingo" in _fold(name):
            celebration_type = CelebrationType.SUNDAY
        else:
            celebration_type = CelebrationType.WEEKDAY

        return Celebration(name=name, type=celebration_type, liturgical_color=color)

    def _parse_season(self, soup: BeautifulSoup) -> LiturgicalSeason:
        metadata = soup.find(id="cnbb-metadata")
        name = _clean(str(metadata.get("data-title", ""))) if metadata else ""
        visible_text = _clean(soup.get_text(" ", strip=True))

        week_match = re.search(r"\b(\d{1,2})\s*[ªºoa]?\s*(?:Semana|Domingo)\b", visible_text, re.I)
        week = int(week_match.group(1)) if week_match else None

        year_match = re.search(r"\bAno\s+([ABC])\b", visible_text, re.I)
        liturgical_year = year_match.group(1).upper() if year_match else None

        if not name:
            season_match = re.search(
                r"\b\d{1,2}\s*[ªºoa]?\s*(?:Semana|Domingo)\s+d[oa]\s+"
                r"([A-ZÀ-Ü][A-Za-zÀ-ÿ ]+?)(?=\s*(?:,|Ano|Leituras|$))",
                visible_text,
            )
            name = _clean(season_match.group(1)) if season_match else ""
        if not name:
            raise ValueError("could not identify the CNBB liturgical season")

        return LiturgicalSeason(name=name, week=week, liturgical_year=liturgical_year)

    def _parse_readings(self, soup: BeautifulSoup) -> list[Reading]:
        boundaries: list[tuple[Tag, ReadingType | None]] = []
        for tag in soup.find_all(True):
            marker_type = self._marker_type(tag)
            if marker_type is not False:
                boundaries.append((tag, marker_type))

        parsed: list[Reading] = []
        for index, (marker, marker_type) in enumerate(boundaries):
            if marker_type is None:  # acclamation is a boundary, not an imported reading
                continue
            end = boundaries[index + 1][0] if index + 1 < len(boundaries) else None
            parsed.append(self._parse_single_reading(marker, end=end, forced_type=marker_type))

        reference_script = soup.find(id="cnbb-reference-data")
        references: list[str] = []
        if reference_script and reference_script.string:
            try:
                loaded = json.loads(reference_script.string)
                if isinstance(loaded, list):
                    references = [_clean(str(item)) for item in loaded if _clean(str(item))]
            except json.JSONDecodeError:
                pass

        # The summary follows the same order as the body and contains the book
        # abbreviation which is omitted from the body's colored verse range.
        for reading, reference in zip(parsed, references, strict=False):
            reading.reference = reference

        result: list[Reading] = []
        for reading in parsed:
            alternatives = self._split_alternative_references(reading.reference)
            if len(alternatives) < 2:
                result.append(reading)
                continue

            first_option = reading.model_copy(update={"reference": alternatives[0]})
            options = [first_option]
            options.extend(Reading(type=reading.type, reference=ref) for ref in alternatives[1:])
            result.append(Reading(type=reading.type, title=reading.title, options=options))
        return result

    def _parse_single_reading(
        self,
        element: Tag,
        *,
        end: Tag | None = None,
        forced_type: ReadingType | None = None,
    ) -> Reading:
        """Parse the content following one semantic reading marker."""
        reading_type = forced_type or self._type_from_text(element.get_text(" ", strip=True))
        if reading_type is None:
            raise ValueError(f"unrecognized CNBB reading marker: {element.get_text(' ', strip=True)!r}")

        reference_tag = self._find_reference_tag(element, end)
        before_reference = self._strings_between(element, reference_tag or end)
        after_reference = self._strings_between(reference_tag or element, end)
        if reference_tag and after_reference:
            embedded_reference = _clean(reference_tag.get_text(" ", strip=True))
            if after_reference[0] == embedded_reference:
                after_reference.pop(0)

        title = None
        if reading_type == ReadingType.PSALM:
            title = "Salmo responsorial"
        else:
            title_pattern = re.compile(r"^(?:Leitura|In[ií]cio|Proclama[cç][aã]o)", re.I)
            title = next((text for text in before_reference if title_pattern.search(text)), None)

        partial_reference = _clean(reference_tag.get_text(" ", strip=True)) if reference_tag else None
        hidden_reference = (
            None if reading_type == ReadingType.PSALM else self._reference_from_hidden_heading(element)
        )
        reference = hidden_reference or partial_reference
        response = (
            self._extract_response(reference_tag or element, end)
            if reading_type == ReadingType.PSALM
            else None
        )
        text = _clean(" ".join(after_reference)) or None

        return Reading(
            type=reading_type,
            reference=reference,
            title=title,
            response=response,
            text=text,
        )

    def _parse_note(self, soup: BeautifulSoup) -> str | None:
        for element in soup.find_all("i"):
            note = _clean(element.get_text(" ", strip=True))
            folded = _fold(note)
            if folded.startswith("hoje") or "omite-se" in folded:
                return note
        return None

    @staticmethod
    def _extract_summary_references(soup: BeautifulSoup) -> list[str]:
        for tag in soup.find_all(["div", "p"]):
            own_text = _clean(" ".join(str(item) for item in tag.find_all(string=True, recursive=False)))
            if not re.match(r"^Leituras?(?:\s*\([^)]*\))?\s*:\s*$", own_text, re.I):
                continue
            references = [
                _clean(sibling.get_text(" ", strip=True))
                for sibling in tag.find_next_siblings()
                if _clean(sibling.get_text(" ", strip=True))
            ]
            if references:
                return references
        return []

    @staticmethod
    def _marker_type(tag: Tag) -> ReadingType | None | bool:
        text = _fold(_clean(tag.get_text(" ", strip=True)))
        if tag.name == "center":
            if text in {"primeira leitura", "1a leitura", "leitura"}:
                return ReadingType.FIRST_READING
            if text in {"segunda leitura", "2a leitura"}:
                return ReadingType.SECOND_READING
            if text == "evangelho":
                return ReadingType.GOSPEL
        if tag.name == "font" and text.startswith("salmo responsorial"):
            return ReadingType.PSALM
        if tag.name == "font" and text.startswith("aclamacao ao evangelho"):
            return None
        return False

    @staticmethod
    def _type_from_text(value: str) -> ReadingType | None:
        folded = _fold(value)
        if "primeira leitura" in folded or folded == "leitura":
            return ReadingType.FIRST_READING
        if "segunda leitura" in folded:
            return ReadingType.SECOND_READING
        if "salmo" in folded:
            return ReadingType.PSALM
        if "evangelho" in folded:
            return ReadingType.GOSPEL
        return None

    @staticmethod
    def _find_reference_tag(start: Tag, end: Tag | None) -> Tag | None:
        for node in start.next_elements:
            if node is end:
                break
            if not isinstance(node, Tag) or node.name != "font":
                continue
            color = str(node.get("color", "")).casefold()
            value = _clean(node.get_text(" ", strip=True))
            if color in {"tomato", "#ff6666"} and re.search(r"\d.*,\s*\d", value):
                return node
        return None

    @staticmethod
    def _strings_between(start: Tag | None, end: Tag | None) -> list[str]:
        if start is None:
            return []
        result: list[str] = []
        for node in start.next_elements:
            if node is end:
                break
            if not isinstance(node, NavigableString):
                continue
            parent = node.parent
            if parent and parent.name in {"script", "style"}:
                continue
            if parent and any(
                "display:none" in str(ancestor.get("style", "")).replace(" ", "").casefold()
                for ancestor in parent.parents
                if isinstance(ancestor, Tag)
            ):
                continue
            value = _clean(str(node))
            if value:
                result.append(value)
        return result

    def _extract_response(self, start: Tag, end: Tag | None) -> str | None:
        pieces: list[str] = []
        collecting = False
        for value in self._strings_between(start, end):
            if not collecting and re.match(r"^R\.\s*", value, re.I):
                collecting = True
                value = re.sub(r"^R\.\s*", "", value, flags=re.I)
            elif collecting and re.fullmatch(r"\d+[a-z]?", value, re.I):
                break
            if collecting and value:
                pieces.append(value)
        return _clean(" ".join(pieces)) or None

    @staticmethod
    def _reference_from_hidden_heading(marker: Tag) -> str | None:
        heading = marker.find_previous("h3", class_="title-leitura")
        if not heading:
            return None
        text = _clean(heading.get_text(" ", strip=True))
        match = re.search(r"\s-\s(.+\d.+)$", text)
        return _clean(match.group(1)) if match else None

    @staticmethod
    def _split_alternative_references(reference: str | None) -> list[str]:
        if not reference:
            return []
        candidates = [_clean(value) for value in re.split(r"\s+ou\s+", reference, flags=re.I)]
        if len(candidates) > 1 and all(
            re.match(r"^[1-3]?[A-Za-zÀ-ÿ]+\s+\d", candidate) for candidate in candidates
        ):
            return candidates
        return [reference]
