package dev.ximarelli.rosary.backend.journal;

import dev.ximarelli.rosary.backend.checkins.MysteryType;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document(collection = "journal_entries")
public record JournalEntity(
        @Id String id,
        String userId,
        Instant date,
        String content,
        String mood,
        List<String> tags,
        String intentions,
        MysteryType mystery,
        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt) {
}
