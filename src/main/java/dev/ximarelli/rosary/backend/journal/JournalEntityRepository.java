package dev.ximarelli.rosary.backend.journal;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JournalEntityRepository extends MongoRepository<JournalEntity, String> {
    List<JournalEntity> findByUserIdOrderByDateDesc(String userId);
}
