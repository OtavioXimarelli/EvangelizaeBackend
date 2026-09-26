package org.evangelizae.api.liturgy.repository;

import java.time.LocalDate;
import java.util.Optional;

public interface LiturgicalDayRepository {

    Optional<LiturgicalDayDocument> findByDate(LocalDate date);

    LiturgicalDayDocument save(LiturgicalDayDocument document);
}
