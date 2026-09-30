package org.evangelizae.api.liturgy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.evangelizae.api.liturgy.model.LiturgyImportRequest;
import org.evangelizae.api.liturgy.model.LiturgyImportRequest.Celebration;
import org.evangelizae.api.liturgy.model.LiturgyImportRequest.CelebrationType;
import org.evangelizae.api.liturgy.model.LiturgicalColor;
import org.evangelizae.api.liturgy.model.LiturgyImportRequest.LiturgicalDay;
import org.evangelizae.api.liturgy.model.LiturgyImportRequest.LiturgicalSeason;
import org.evangelizae.api.liturgy.model.LiturgyImportRequest.Parts;
import org.evangelizae.api.liturgy.model.LiturgyImportRequest.Period;
import org.evangelizae.api.liturgy.model.LiturgyImportRequest.Reading;
import org.evangelizae.api.liturgy.model.LiturgyImportRequest.ReadingType;
import org.evangelizae.api.liturgy.model.LiturgyImportRequest.Source;
import org.evangelizae.api.liturgy.model.LiturgyImportRequest.SourceName;
import org.evangelizae.api.liturgy.model.LiturgyImportRequest.SourceRole;
import org.evangelizae.api.liturgy.model.LiturgyImportRequest.Validation;
import org.evangelizae.api.liturgy.model.LiturgyImportRequest.ValidationStatus;
import org.evangelizae.api.liturgy.model.LiturgyImportResponse;
import org.evangelizae.api.liturgy.model.ReadingKind;
import org.evangelizae.api.liturgy.repository.LiturgicalDayDocument;
import org.evangelizae.api.liturgy.repository.LiturgicalDayRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LiturgyServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T02:00:00Z");
    private static final String CONTENT_HASH = "sha256:" + "a".repeat(64);

    @Mock
    private LiturgicalDayRepository repository;

    @Test
    void returnsGroupedLiturgyForTheObserverDate() {
        var document = document(
                LocalDate.parse("2026-09-23"),
                List.of(
                        persistedReading("FIRST_READING", "Primeira leitura"),
                        persistedReading("PSALM", "Salmo"),
                        persistedReading("SECOND_READING", "Segunda leitura"),
                        persistedReading("GOSPEL", "Evangelho"),
                        persistedReading("EXTRA", "Acclamação")
                ),
                Instant.parse("2026-09-24T01:30:00Z")
        );
        when(repository.findByDate(LocalDate.parse("2026-09-23"))).thenReturn(Optional.of(document));

        var result = service().getToday("America/Sao_Paulo", "pt-BR");

        assertThat(result.date()).isEqualTo(LocalDate.parse("2026-09-23"));
        assertThat(result.groups()).extracting("kind").containsExactly(
                ReadingKind.FIRST_READING,
                ReadingKind.PSALM,
                ReadingKind.SECOND_READING,
                ReadingKind.GOSPEL,
                ReadingKind.EXTRA
        );
        assertThat(result.source().provider()).isEqualTo("CNBB");
        assertThat(result.source().fetchedAt()).isEqualTo(Instant.parse("2026-09-24T01:30:00Z"));
    }

    @Test
    void importsAndCreatesADateKeyedDocument() {
        var day = validDay(LocalDate.parse("2026-09-24"));
        when(repository.findByDate(day.date())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service().importBatch(request(day));

        assertThat(response.status()).isEqualTo(LiturgyImportResponse.Status.SUCCESS);
        assertThat(response.received()).isEqualTo(1);
        assertThat(response.created()).isEqualTo(1);
        assertThat(response.updated()).isZero();
        var captor = ArgumentCaptor.forClass(LiturgicalDayDocument.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().id()).isEqualTo("2026-09-24");
        assertThat(captor.getValue().provider()).isEqualTo("CNBB");
        assertThat(captor.getValue().primaryContentHash()).isEqualTo(CONTENT_HASH);
        assertThat(captor.getValue().readings()).extracting(LiturgicalDayDocument.Reading::kind)
                .containsExactly(ReadingKind.FIRST_READING.name(), ReadingKind.GOSPEL.name());
    }

    @Test
    void importsAlternativeReadingsAsExtraItems() {
        var day = validDay(LocalDate.parse("2026-09-24"), List.of(
                new Reading(ReadingType.FIRST_READING, "Jo 11,19-27 ou Lc 10,38-42", "Primeira leitura",
                        null, null, List.of(
                        new Reading(ReadingType.FIRST_READING, "Jo 11,19-27", null, null, "Texto de Jo 11", null),
                        new Reading(ReadingType.FIRST_READING, "Lc 10,38-42", null, null, "Texto de Lc 10", null)
                ))
        ));
        when(repository.findByDate(day.date())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().importBatch(request(day));

        var captor = ArgumentCaptor.forClass(LiturgicalDayDocument.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().readings()).extracting(LiturgicalDayDocument.Reading::kind)
                .containsExactly(ReadingKind.EXTRA.name(), ReadingKind.EXTRA.name());
    }

    @Test
    void rejectsDuplicateDatesBeforeWriting() {
        var day = validDay(LocalDate.parse("2026-09-24"));

        assertThatThrownBy(() -> service().importBatch(request(day, day)))
                .isInstanceOf(InvalidLiturgyImportException.class)
                .hasMessageContaining("Duplicate liturgy date");
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsInvalidContentHashesBeforeWriting() {
        var day = new LiturgicalDay(
                LocalDate.parse("2026-09-24"),
                new Celebration("Quarta-feira", CelebrationType.WEEKDAY, LiturgicalColor.GREEN),
                new LiturgicalSeason("Tempo Comum", 25, "A"),
                new Parts(List.of(
                        new Reading(ReadingType.GOSPEL, "Mt 11,11-15", "Evangelho", null, "Texto", null))),
                List.of(new Source(SourceName.CNBB, SourceRole.PRIMARY, null, NOW, null, "invalid")),
                new Validation(ValidationStatus.VALID, 1, List.of()),
                null
        );

        assertThatThrownBy(() -> service().importBatch(request(day)))
                .isInstanceOf(InvalidLiturgyImportException.class)
                .hasMessageContaining("contentHash is invalid");
        verifyNoInteractions(repository);
    }

    @Test
    void returnsUnavailableWhenTheDocumentDoesNotExist() {
        when(repository.findByDate(LocalDate.parse("2026-09-23"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getToday("America/Sao_Paulo", "pt-BR"))
                .isInstanceOf(LiturgyUnavailableException.class);
    }

    @Test
    void returnsUnavailableWhenTheProviderIsMissing() {
        // The API must never serve a document whose source is unknown. The old
        // fallback labelled it "mongodb", which the UI showed as the source.
        var document = document(
                LocalDate.parse("2026-09-23"),
                List.of(persistedReading("FIRST_READING", "Primeira leitura")),
                Instant.parse("2026-09-24T01:30:00Z"),
                null
        );
        when(repository.findByDate(LocalDate.parse("2026-09-23"))).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service().getToday("America/Sao_Paulo", "pt-BR"))
                .isInstanceOf(LiturgyUnavailableException.class)
                .hasMessageContaining("does not declare its source");
    }

    @Test
    void rejectsUnsupportedLocales() {
        assertThatThrownBy(() -> service().getToday("America/Sao_Paulo", "en-US"))
                .isInstanceOf(InvalidLiturgyRequestException.class)
                .hasMessage("locale must be pt-BR");
    }

    private LiturgyService service() {
        return new LiturgyService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static LiturgyImportRequest request(LiturgicalDay... days) {
        var date = days[0].date();
        return new LiturgyImportRequest(
                "1.0",
                "0.1.0",
                NOW,
                new Period(date, date),
                List.of(days)
        );
    }

    private static LiturgicalDay validDay(LocalDate date) {
        return validDay(date, List.of(
                new Reading(ReadingType.FIRST_READING, "1Cor 2,10b-16", "Primeira leitura", null, "Texto", null),
                new Reading(ReadingType.GOSPEL, "Lc 4,31-37", "Evangelho", null, "Texto do Evangelho", null)
        ));
    }

    private static LiturgicalDay validDay(LocalDate date, List<Reading> readings) {
        return new LiturgicalDay(
                date,
                new Celebration("Quarta-feira", CelebrationType.WEEKDAY, LiturgicalColor.GREEN),
                new LiturgicalSeason("Tempo Comum", 25, "A"),
                new Parts(readings),
                List.of(new Source(
                        SourceName.CNBB,
                        SourceRole.PRIMARY,
                        "https://cnbb.example/" + date,
                        NOW,
                        null,
                        CONTENT_HASH)),
                new Validation(ValidationStatus.VALID, 1, List.of()),
                null
        );
    }

    private static LiturgicalDayDocument document(
            LocalDate date,
            List<LiturgicalDayDocument.Reading> readings,
            Instant fetchedAt
    ) {
        return document(date, readings, fetchedAt, "CNBB");
    }

    private static LiturgicalDayDocument document(
            LocalDate date,
            List<LiturgicalDayDocument.Reading> readings,
            Instant fetchedAt,
            String provider
    ) {
        return new LiturgicalDayDocument(
                date.toString(),
                date,
                "Quarta-feira",
                "GREEN",
                readings,
                "WEEKDAY",
                new LiturgicalDayDocument.Season("Tempo Comum", 25, "A"),
                null,
                List.of(),
                new LiturgicalDayDocument.Validation("VALID", 1, List.of()),
                "0.1.0",
                NOW.minusSeconds(60),
                CONTENT_HASH,
                provider,
                fetchedAt,
                NOW.minusSeconds(3600),
                NOW,
                NOW
        );
    }

    private static LiturgicalDayDocument.Reading persistedReading(String kind, String title) {
        return new LiturgicalDayDocument.Reading(kind, title, kind + " reference", kind + " text", null);
    }
}
