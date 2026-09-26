package org.evangelizae.api.liturgy.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface LiturgicalDayMongoRepository
        extends MongoRepository<LiturgicalDayDocument, String>, LiturgicalDayRepository {

    @Override
    Optional<LiturgicalDayDocument> findByDate(LocalDate date);
}
