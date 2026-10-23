package dev.ximarelli.rosary.backend.prayers;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PrayerRequestRepository extends MongoRepository<PrayerRequest, String> {
    List<PrayerRequest> findByUserId(String userId);
}
