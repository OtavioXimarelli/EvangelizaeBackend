package org.evangelizae.api.reflection.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyReflectionRepository {

    Optional<DailyReflectionDocument> findByDate(LocalDate date);

    List<DailyReflectionDocument> findByDateBetweenOrderByDate(LocalDate from, LocalDate to);

    DailyReflectionDocument save(DailyReflectionDocument document);

    long deleteByDate(LocalDate date);
}
