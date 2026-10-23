package dev.ximarelli.rosary.backend.checkins;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface CheckInRepository extends MongoRepository<CheckIn, String> {
    List<CheckIn> findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(String userId, Instant start, Instant end);

    List<CheckIn> findByUserIdOrderByCreatedAtDesc(String userId);

    List<CheckIn> findByIsPublicTrueOrderByCreatedAtDesc();
}
