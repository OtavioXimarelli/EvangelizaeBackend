"""Vatican News ``Palavra do Dia`` HTML parser."""

from __future__ import annotations

import re
import unicodedata

from bs4 import BeautifulSoup, Tag

from app.models.liturgy import Reading, ReadingType


def _clean(value: str) -> str:
    return re.sub(r"\s+", " ", value.replace("\xa0", " ")).strip()


def _fold(value: str) -> str:
    normalized = unicodedata.normalize("NFKD", value)
    return "".join(char for char in normalized if not unicodedata.combining(char)).casefold()


# Specific, numbered books precede their unnumbered counterparts.
_BOOK_PATTERNS: tuple[tuple[str, str], ...] = (
    (r"primeir[oa].*samuel", "1Sm"),
    (r"segund[oa].*samuel", "2Sm"),
    (r"primeir[oa].*reis", "1Rs"),
    (r"segund[oa].*reis", "2Rs"),
    (r"primeir[oa].*cronicas", "1Cr"),
    (r"segund[oa].*cronicas", "2Cr"),
    (r"primeir[oa].*macabeus", "1Mc"),
    (r"segund[oa].*macabeus", "2Mc"),
    (r"primeira carta.*corintios", "1Cor"),
    (r"segunda carta.*corintios", "2Cor"),
    (r"primeira carta.*tessalonicenses", "1Ts"),
    (r"segunda carta.*tessalonicenses", "2Ts"),
    (r"primeira carta.*timoteo", "1Tm"),
    (r"segunda carta.*timoteo", "2Tm"),
    (r"primeira carta.*pedro", "1Pd"),
    (r"segunda carta.*pedro", "2Pd"),
    (r"primeira carta.*joao", "1Jo"),
    (r"segunda carta.*joao", "2Jo"),
    (r"terceira carta.*joao", "3Jo"),
    (r"genesis", "Gn"),
    (r"exodo", "Ex"),
    (r"levitico", "Lv"),
    (r"numeros", "Nm"),
    (r"deuteronomio", "Dt"),
    (r"josue", "Js"),
    (r"juizes", "Jz"),
    (r"\brute\b", "Rt"),
    (r"esdras", "Esd"),
    (r"neemias", "Ne"),
    (r"tobias", "Tb"),
    (r"judite", "Jt"),
    (r"\bester\b", "Est"),
    (r"\bjo\b", "Jó"),
    (r"salmos?", "Sl"),
    (r"proverbios", "Pr"),
    (r"eclesiastes", "Ecl"),
    (r"cantico dos canticos", "Ct"),
    (r"sabedoria", "Sb"),
    (r"eclesiastico", "Eclo"),
    (r"isaias", "Is"),
    (r"jeremias", "Jr"),
    (r"lamentacoes", "Lm"),
    (r"baruc", "Br"),
    (r"ezequiel", "Ez"),
    (r"daniel", "Dn"),
    (r"oseias", "Os"),
    (r"\bjoel\b", "Jl"),
    (r"amos", "Am"),
    (r"abdias", "Ab"),
    (r"jonas", "Jn"),
    (r"miqueias", "Mq"),
    (r"naum", "Na"),
    (r"habacuc", "Hab"),
    (r"sofonias", "Sf"),
    (r"ageu", "Ag"),
    (r"zacarias", "Zc"),
    (r"malaquias", "Ml"),
    (r"mateus", "Mt"),
    (r"marcos", "Mc"),
    (r"lucas", "Lc"),
    (r"evangelho.*joao|segundo joao", "Jo"),
    (r"atos dos apostolos", "At"),
    (r"romanos", "Rm"),
    (r"galatas", "Gl"),
    (r"efesios", "Ef"),
    (r"filipenses", "Fl"),
    (r"colossenses", "Cl"),
    (r"\btito\b", "Tt"),
    (r"filemon", "Fm"),
    (r"hebreus", "Hb"),
    (r"tiago", "Tg"),
    (r"\bjudas\b", "Jd"),
    (r"apocalipse", "Ap"),
)


def _book_abbreviation(title: str) -> str | None:
    folded = _fold(title)
    for pattern, abbreviation in _BOOK_PATTERNS:
        if re.search(pattern, folded):
            return abbreviation
    return None


class VaticanParser:
    """Extract only the readings needed for cross-source comparison."""

    def parse_readings(self, html: str) -> list[Reading]:
        if not html or not html.strip():
            raise ValueError("Vatican News returned an empty response")
        soup = BeautifulSoup(html, "lxml")
        readings: list[Reading] = []

        for section in soup.find_all("section"):
            heading = section.find("h2")
            if not heading:
                continue
            heading_text = _fold(_clean(heading.get_text(" ", strip=True)))
            if heading_text == "leitura do dia":
                readings.extend(self._parse_reading_section(section))
            elif heading_text == "evangelho do dia":
                readings.append(self._parse_single_reading(section, forced_type=ReadingType.GOSPEL))

        if not readings:
            raise ValueError("Vatican News page contains no recognizable readings")
        return readings

    def _parse_reading_section(self, section: Tag) -> list[Reading]:
        paragraphs = self._content_paragraphs(section)
        marker_indexes: list[tuple[int, ReadingType]] = []
        for index, paragraph in enumerate(paragraphs):
            marker = _fold(paragraph.get_text(" ", strip=True))
            if marker in {"primeira leitura", "1a leitura"}:
                marker_indexes.append((index, ReadingType.FIRST_READING))
            elif marker in {"segunda leitura", "2a leitura"}:
                marker_indexes.append((index, ReadingType.SECOND_READING))

        if not marker_indexes:
            return [self._reading_from_paragraphs(paragraphs, ReadingType.FIRST_READING)]

        readings: list[Reading] = []
        for position, (start, reading_type) in enumerate(marker_indexes):
            end = marker_indexes[position + 1][0] if position + 1 < len(marker_indexes) else len(paragraphs)
            readings.append(self._reading_from_paragraphs(paragraphs[start + 1 : end], reading_type))
        return readings

    def _parse_single_reading(
        self, element: Tag, *, forced_type: ReadingType | None = None
    ) -> Reading:
        heading = element.find("h2")
        heading_text = _fold(heading.get_text(" ", strip=True)) if heading else ""
        reading_type = forced_type
        if reading_type is None:
            reading_type = (
                ReadingType.GOSPEL if "evangelho" in heading_text else ReadingType.FIRST_READING
            )
        return self._reading_from_paragraphs(self._content_paragraphs(element), reading_type)

    @staticmethod
    def _content_paragraphs(section: Tag) -> list[Tag]:
        content = section.select_one(".section__content") or section
        return [paragraph for paragraph in content.find_all("p") if _clean(paragraph.get_text(" "))]

    @staticmethod
    def _reading_from_paragraphs(paragraphs: list[Tag], reading_type: ReadingType) -> Reading:
        values = [_clean(paragraph.get_text(" ", strip=True)) for paragraph in paragraphs]
        if len(values) < 2:
            raise ValueError("Vatican reading section is incomplete")

        reference_index = next(
            (
                index
                for index, value in enumerate(values)
                if re.match(r"^(?:[1-3]?[A-Za-zÀ-ÿ]+\s+)?\d+\s*[,.:]\s*\d", value)
            ),
            None,
        )
        if reference_index is None:
            raise ValueError("Vatican reading has no recognizable reference")

        title = _clean(" ".join(values[:reference_index]))
        raw_reference = values[reference_index]
        complete_reference = re.match(
            r"^([1-3]?[A-Za-zÀ-ÿ]+)\s*(\d+\s*[,.:].+)$", raw_reference
        )
        if complete_reference:
            book = complete_reference.group(1)
            passage = complete_reference.group(2)
        else:
            book = _book_abbreviation(title)
            passage = raw_reference
        passage = re.sub(r"\s+", "", passage)
        reference = f"{book} {passage}" if book else passage

        text = _clean(" ".join(values[reference_index + 1 :])) or None
        return Reading(type=reading_type, reference=reference, title=title or None, text=text)
