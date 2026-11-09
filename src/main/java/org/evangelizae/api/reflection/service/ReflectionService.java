package org.evangelizae.api.reflection.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.evangelizae.api.reflection.model.DailyReflection;
import org.evangelizae.api.reflection.model.ReflectionUpsertRequest;
import org.evangelizae.api.reflection.model.ReflectionsListResponse;
import org.evangelizae.api.reflection.repository.DailyReflectionDocument;
import org.evangelizae.api.reflection.repository.DailyReflectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ReflectionService {

    private static final long MAX_RANGE_DAYS = 62;

    private final DailyReflectionRepository repository;
    private final Clock clock;

    public ReflectionService(DailyReflectionRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public ReflectionsListResponse list(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new InvalidReflectionRequestException("from and to are required");
        }
        if (from.isAfter(to)) {
            throw new InvalidReflectionRequestException("from must not be after to");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new InvalidReflectionRequestException("date range must not exceed 62 days");
        }
        var reflections = repository.findByDateBetweenOrderByDate(from, to).stream()
                .map(this::toResponse)
                .toList();
        return new ReflectionsListResponse(reflections);
    }

    public DailyReflection upsert(LocalDate date, ReflectionUpsertRequest request) {
        validateContent(request);
        var now = Instant.now(clock);
        var existing = repository.findByDate(date);
        var document = new DailyReflectionDocument(
                date.toString(),
                date,
                request.text().trim(),
                request.attribution().trim(),
                normalizeReference(request.reference()),
                existing.map(DailyReflectionDocument::createdAt).orElse(now),
                now
        );
        return toResponse(repository.save(document));
    }

    public void delete(LocalDate date) {
        var deleted = repository.deleteByDate(date);
        if (deleted == 0) {
            throw new ReflectionNotFoundException("No reflection for " + date);
        }
    }

    private void validateContent(ReflectionUpsertRequest request) {
        if (request == null || !StringUtils.hasText(request.text())
                || !StringUtils.hasText(request.attribution())) {
            throw new InvalidReflectionContentException("text and attribution are required");
        }
    }

    private String normalizeReference(String reference) {
        return StringUtils.hasText(reference) ? reference.trim() : null;
    }

    private DailyReflection toResponse(DailyReflectionDocument document) {
        return new DailyReflection(
                document.date(),
                document.text(),
                document.attribution(),
                document.reference(),
                document.createdAt(),
                document.updatedAt()
        );
    }
}
