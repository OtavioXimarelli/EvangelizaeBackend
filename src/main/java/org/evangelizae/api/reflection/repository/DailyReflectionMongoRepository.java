package org.evangelizae.api.reflection.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface DailyReflectionMongoRepository
        extends MongoRepository<DailyReflectionDocument, String>, DailyReflectionRepository {

    @Override
    Optional<DailyReflectionDocument> findByDate(LocalDate date);

    @Override
    List<DailyReflectionDocument> findByDateBetweenOrderByDate(LocalDate from, LocalDate to);

    @Override
    long deleteByDate(LocalDate date);
}
