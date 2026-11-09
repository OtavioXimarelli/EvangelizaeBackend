package org.evangelizae.api.reflection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.evangelizae.api.reflection.model.ReflectionUpsertRequest;
import org.evangelizae.api.reflection.repository.DailyReflectionDocument;
import org.evangelizae.api.reflection.repository.DailyReflectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReflectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T02:00:00Z");
    private static final LocalDate DATE = LocalDate.parse("2026-09-24");

    @Mock
    private DailyReflectionRepository repository;

    @Test
    void createsAndUpdatesPreservingCreatedAt() {
        var service = new ReflectionService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        var request = new ReflectionUpsertRequest("Texto", "Autor", "Ref");
        when(repository.findByDate(DATE)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var created = service.upsert(DATE, request);
        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(created.updatedAt()).isEqualTo(NOW);

        var later = NOW.plusSeconds(3600);
        var updating = new ReflectionService(repository, Clock.fixed(later, ZoneOffset.UTC));
        when(repository.findByDate(DATE)).thenReturn(Optional.of(new DailyReflectionDocument(
                DATE.toString(), DATE, "Texto", "Autor", "Ref", NOW, NOW)));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var updated = updating.upsert(DATE, request);
        assertThat(updated.createdAt()).isEqualTo(NOW);
        assertThat(updated.updatedAt()).isEqualTo(later);

        var captor = ArgumentCaptor.forClass(DailyReflectionDocument.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().id()).isEqualTo(DATE.toString());
    }

    @Test
    void rejectsBlankContent() {
        var service = new ReflectionService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.upsert(DATE, new ReflectionUpsertRequest(" ", "Autor", null)))
                .isInstanceOf(InvalidReflectionContentException.class);
        assertThatThrownBy(() -> service.upsert(DATE, new ReflectionUpsertRequest("Texto", "", null)))
                .isInstanceOf(InvalidReflectionContentException.class);
    }

    @Test
    void validatesListRanges() {
        var service = new ReflectionService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.list(LocalDate.parse("2026-09-30"), LocalDate.parse("2026-09-01")))
                .isInstanceOf(InvalidReflectionRequestException.class)
                .hasMessageContaining("from must not be after to");
        assertThatThrownBy(() -> service.list(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")))
                .isInstanceOf(InvalidReflectionRequestException.class)
                .hasMessageContaining("must not exceed 62 days");
    }

    @Test
    void throwsWhenDeletingAbsentReflection() {
        var service = new ReflectionService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.deleteByDate(DATE)).thenReturn(0L);

        assertThatThrownBy(() -> service.delete(DATE))
                .isInstanceOf(ReflectionNotFoundException.class)
                .hasMessageContaining("No reflection for");
    }
}
