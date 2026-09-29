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
            references = self._extract_summary_references(BeautifulSoup(details, "lxml"))
            if not references:
                references = self._extract_summary_references(
                    BeautifulSoup(str(content.get("leituras") or ""), "lxml")
                )

            body = str(content.get("body") or "")
            soup = BeautifulSoup(f"<main>{details}<article>{body}</article></main>", "lxml")

            metadata = soup.new_tag("meta")
            metadata["id"] = "cnbb-metadata"
            metadata["data-color"] = str(content.get("color") or "")
            metadata["data-title"] = str(content.get("title") or "")
            soup.main.insert(0, metadata)
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
        # CNBB's details fragment carries no liturgical note; the <i> elements
        # in the body are italic scripture quotations, not notes.
        return celebration, season, LiturgicalParts(readings=readings), None

    _SECTION_HEADING = re.compile(
        r"^\s*((?:Missa|Outras leituras)\b.{2,60}?)\s*$",
        re.I,
    )

    def _section_plan(
        self, soup: BeautifulSoup, markers: list[Tag]
    ) -> tuple[dict[int, int], dict[int, NavigableString]]:
        """Label each reading marker with its body section, and where it ends.

        Some days publish several reading sets in one body: the multiple Masses
        of Christmas Eve and Christmas Day, and the "Outras leituras próprias à
        escolha" catalogue appended to All Souls. Each set repeats the reading
        markers, so parsing the whole body would publish readings the Church did
        not prescribe for that date. The day's own readings are named in the
        summary, so the section whose verse ranges match the summary is the day's.

        A section ends at the next heading, not at the next marker: CNBB does not
        mark up every reading inside these blocks, so the last reading of a
        section would otherwise absorb the whole block that follows it.
        """
        # Keyed by id(): two Masses print byte-identical markers, and bs4
        # compares Tag by markup, so a set of them collapses to one entry.
        positions = {id(marker): index for index, marker in enumerate(markers)}
        section_of: dict[int, int] = {}
        end_of: dict[int, NavigableString] = {}
        section = 0
        opened: NavigableString | None = None
        previous: int | None = None
        for node in soup.descendants:
            if isinstance(node, NavigableString):
                if node.parent is None or not self._SECTION_HEADING.match(str(node)):
                    continue
                section += 1
                # The text node, not its wrapper: CNBB closes the enclosing
                # <center> only after the next reading marker, so the parent is
                # an ancestor of the content it should cut.
                opened = node
                # The heading can also sit *inside* the marker element that
                # opens the section, in which case that marker belongs to the
                # new section rather than to the one before it.
                for ancestor in node.parents:
                    index = positions.get(id(ancestor))
                    if index is not None:
                        section_of[index] = section
                        previous = index
                        # The heading is inside this marker, so it does not end
                        # it; it opens the section the marker belongs to.
                        opened = None
                        break
                continue
            index = positions.get(id(node))
            if index is None:
                continue
            if previous is not None and opened is not None:
                end_of[previous] = opened
            section_of[index] = section
            previous = index
            opened = None
        return section_of, end_of

    def _section_score(self, wanted: list[str], readings: list[Reading]) -> int:
        found = [self._verse_key(reading.reference or "") for reading in readings]
        return sum(1 for key in wanted if key and any(key in item for item in found))

    @staticmethod
    def _verse_key(reference: str) -> str:
        """Comparable form of a citation: digits and separators only."""
        return re.sub(r"[^0-9,.\-]+", "", reference)

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
            # Easter Sunday is published with an empty title and no season line.
            # Failing here would reject the batch and leave the holiest day of
            # the year permanently unimported, so fall back to the only wording
            # the source does give. Nothing is invented: the celebration name is
            # CNBB's own, and the season is internal import metadata, never shown
            # as a claim on the public contract.
            name_tag = soup.find("b")
            name = _clean(name_tag.get_text(" ", strip=True)) if name_tag else ""
        if not name:
            raise ValueError("could not identify the CNBB liturgical season")

        return LiturgicalSeason(name=name, week=week, liturgical_year=liturgical_year)

    def _parse_readings(self, soup: BeautifulSoup) -> list[Reading]:
        boundaries: list[tuple[Tag, ReadingType | None]] = []
        for tag in soup.find_all(True):
            marker_type = self._marker_type(tag)
            if marker_type is not False:
                boundaries.append((tag, marker_type))
        boundaries = self._drop_nested_markers(boundaries)

        reference_script = soup.find(id="cnbb-reference-data")
        references: list[str] = []
        if reference_script and reference_script.string:
            try:
                loaded = json.loads(reference_script.string)
                if isinstance(loaded, list):
                    references = [_clean(str(item)) for item in loaded if _clean(str(item))]
            except json.JSONDecodeError:
                pass

        section_of, end_of = self._section_plan(soup, [tag for tag, _ in boundaries])
        result = self._parse_section(boundaries, section_of, end_of, references)
        self._require_text(result)
        return result

    def _parse_section(
        self,
        boundaries: list[tuple[Tag, ReadingType | None]],
        section_of: dict[int, int],
        end_of: dict[int, NavigableString],
        references: list[str],
    ) -> list[Reading]:
        """Parse the body section whose readings the day's summary names."""
        by_section: dict[int, list[int]] = {}
        for position in range(len(boundaries)):
            by_section.setdefault(section_of.get(position, 0), []).append(position)
        if len(by_section) < 2:
            return self._parse_markers(boundaries, references, None, {})

        wanted = [self._verse_key(reference) for reference in references]
        scored = [
            (
                self._section_score(
                    wanted, self._parse_markers(boundaries, [], positions, end_of)
                ),
                positions,
            )
            for positions in by_section.values()
        ]
        best_score, best = max(scored, key=lambda item: item[0])
        if not best_score:
            return self._parse_markers(boundaries, references, None, {})
        return self._parse_markers(boundaries, references, best, end_of)

    def _parse_markers(
        self,
        boundaries: list[tuple[Tag, ReadingType | None]],
        references: list[str],
        positions: list[int] | None = None,
        end_of: dict[int, NavigableString] | None = None,
    ) -> list[Reading]:
        selected = list(range(len(boundaries))) if positions is None else list(positions)
        parsed: list[Reading] = []
        for index, position in enumerate(selected):
            marker, marker_type = boundaries[position]
            if marker_type is None:  # acclamation is a boundary, not an imported reading
                continue
            following = selected[index + 1] if index + 1 < len(selected) else position + 1
            end = boundaries[following][0] if following < len(boundaries) else None
            if end_of and position in end_of:
                end = end_of[position]
            parsed.extend(self._parse_single_reading(marker, end=end, forced_type=marker_type))
        return self._attach_references(parsed, references)

    def _attach_references(self, parsed: list[Reading], references: list[str]) -> list[Reading]:
        """Walk the body and the summary together, consuming markers in order.

        The summary lists one citation per reading, but a memorial offering two
        permitted first readings (``Dn 7,9-10.13-14 ou Ap 12,7-12a``) repeats the
        ``PRIMEIRA LEITURA`` marker in the body and gives each option its own
        text. A positional zip would hand the second marker the *next* day's
        citation and shift every later reading, so alternatives are resolved by
        consuming as many body markers as the citation names.
        """
        result: list[Reading] = []
        reading_index = 0
        for reference in references:
            if reading_index >= len(parsed):
                break
            reading = parsed[reading_index]
            alternatives = self._split_alternative_references(reference)
            if len(alternatives) < 2:
                reading.reference = reference
                result.append(reading)
                reading_index += 1
                continue

            available = self._count_matching_markers(parsed, reading_index, reading.type)
            if available >= len(alternatives) and available > 1:
                # Every option has its own body: one reading per alternative.
                for offset, alternative in enumerate(alternatives):
                    candidate = parsed[reading_index + offset]
                    candidate.reference = alternative
                    result.append(candidate)
                reading_index += len(alternatives)
                continue

            # A single body covers every option: keep one group and repeat the text.
            reading.reference = alternatives[0]
            options = [Reading(type=reading.type, reference=reading.reference, text=reading.text)]
            options.extend(
                Reading(type=reading.type, reference=alternative, text=reading.text)
                for alternative in alternatives[1:]
            )
            result.append(Reading(type=reading.type, title=reading.title, options=options))
            reading_index += 1

        # A summary can be shorter than the body. Never borrow a neighbour's
        # citation: each leftover reading keeps the citation its own body
        # carried (the hidden heading or the colored verse range), or none.
        result.extend(parsed[reading_index:])
        return result

    @staticmethod
    def _count_matching_markers(parsed: list[Reading], start: int, reading_type: ReadingType) -> int:
        count = 0
        for reading in parsed[start:]:
            if reading.type is not reading_type:
                break
            count += 1
        return count

    def _require_text(self, readings: list[Reading]) -> None:
        """Invariant: no reading and no option may be published without text."""
        for reading in readings:
            candidates = reading.options or [reading]
            for candidate in candidates:
                if not candidate.text:
                    raise ValueError(
                        f"CNBB returned no text for {reading.type.value} "
                        f"{candidate.reference or '(no citation)'}"
                    )

    @staticmethod
    def _drop_nested_markers(
        boundaries: list[tuple[Tag, ReadingType | None]],
    ) -> list[tuple[Tag, ReadingType | None]]:
        """Keep one boundary per reading heading.

        A heading is sometimes a <center> nested inside the red <font> that
        carries it, and both match. Counting them twice yields a second reading
        with no body, which fails the non-empty-text invariant.
        """
        types = {tag: marker_type for tag, marker_type in boundaries}
        return [
            boundary
            for boundary in boundaries
            if not any(
                ancestor is not boundary[0]
                and types.get(ancestor) is not None
                and types[ancestor] == boundary[1]
                for ancestor in boundary[0].parents
                if isinstance(ancestor, Tag)
            )
        ]

    def _parse_single_reading(
        self,
        element: Tag,
        *,
        end: Tag | None = None,
        forced_type: ReadingType | None = None,
    ) -> list[Reading]:
        """Parse the content following one semantic reading marker.

        A marker can cover more than one body. 2026-12-21 prints a single
        ``PRIMEIRA LEITURA`` marker and then two alternative texts, each with its
        own colored verse range, so the range tags are the real segment
        boundary. Every segment becomes its own Reading.
        """
        reading_type = forced_type or self._type_from_text(element.get_text(" ", strip=True))
        if reading_type is None:
            raise ValueError(f"unrecognized CNBB reading marker: {element.get_text(' ', strip=True)!r}")

        reference_tags = self._find_reference_tags(element, end)
        if not reference_tags:
            reference_tags = [None]

        segments: list[Reading] = []
        for position, reference_tag in enumerate(reference_tags):
            segment_end = reference_tags[position + 1] if position + 1 < len(reference_tags) else end
            segment_start = reference_tags[position - 1] if position else element
            before_reference = self._strings_between(segment_start, reference_tag or segment_end)
            after_reference = self._strings_between(reference_tag or element, segment_end)
            if reference_tag and after_reference:
                embedded_reference = _clean(reference_tag.get_text(" ", strip=True))
                if after_reference[0] == embedded_reference:
                    after_reference.pop(0)

            title = None
            if reading_type == ReadingType.PSALM:
                title = "Salmo responsorial"
            else:
                title_pattern = re.compile(r"^(?:Leitura|In[ií]cio|Proclama[cç][aã]o)", re.I)
                title = next(
                    (text for text in before_reference if title_pattern.search(text)),
                    segments[-1].title if segments else None,
                )

            partial_reference = (
                _clean(reference_tag.get_text(" ", strip=True)) if reference_tag else None
            )
            hidden_reference = (
                None
                if reading_type == ReadingType.PSALM
                else self._reference_from_hidden_heading(element)
            )
            response = (
                self._extract_response(reference_tag or element, segment_end)
                if reading_type == ReadingType.PSALM
                else None
            )
            segments.append(
                Reading(
                    type=reading_type,
                    reference=hidden_reference or partial_reference,
                    title=title,
                    response=response,
                    text=(
                        _clean(" ".join(after_reference)) or None
                        if reading_type == ReadingType.PSALM
                        else self._join_body(after_reference)
                    ),
                )
            )
        return segments

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

    _READING_MARKER = re.compile(
        r"^(?:missa\s+.*?\s+)?(primeira leitura|1a leitura|leitura|segunda leitura|2a leitura|evangelho)$"
    )
    _READING_MARKER_TYPES = {
        "primeira leitura": ReadingType.FIRST_READING,
        "1a leitura": ReadingType.FIRST_READING,
        "leitura": ReadingType.FIRST_READING,
        "segunda leitura": ReadingType.SECOND_READING,
        "2a leitura": ReadingType.SECOND_READING,
        "evangelho": ReadingType.GOSPEL,
    }

    @classmethod
    def _marker_type(cls, tag: Tag) -> ReadingType | None | bool:
        text = _fold(_clean(tag.get_text(" ", strip=True)))
        # Usually the marker is a <center> inside a red <font>, but some days
        # print it as a bare <font> with an empty <center> beside it, and others
        # leave the Mass name in the same element ("Missa da manhã" followed by
        # "PRIMEIRA LEITURA"), so all three shapes have to be recognised.
        if tag.name in {"center", "font"}:
            match = cls._READING_MARKER.match(text)
            if match:
                return cls._READING_MARKER_TYPES[match.group(1)]
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
    def _find_reference_tags(start: Tag, end: Tag | None) -> list[Tag]:
        """Every colored verse range between ``start`` and ``end``, in order."""
        tags: list[Tag] = []
        for node in start.next_elements:
            if node is end:
                break
            if not isinstance(node, Tag) or node.name != "font":
                continue
            color = str(node.get("color", "")).casefold()
            value = _clean(node.get_text(" ", strip=True))
            if color in {"tomato", "#ff6666"} and re.search(r"\d.*,\s*\d", value):
                tags.append(node)
        return tags

    @staticmethod
    def _join_body(values: list[str]) -> str | None:
        """Join a body, cutting at the ``Ou:`` divider that introduces the next one."""
        pieces: list[str] = []
        for value in values:
            if _fold(value).rstrip(":").strip() == "ou":
                break
            pieces.append(value)
        return _clean(" ".join(pieces)) or None

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
