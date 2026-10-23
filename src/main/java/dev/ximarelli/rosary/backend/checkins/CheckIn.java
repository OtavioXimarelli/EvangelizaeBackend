package dev.ximarelli.rosary.backend.checkins;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Set;


@Document(collection = "check_ins")

public record CheckIn(
        @Id String id, String userId,
        String userName,
        String userAvatar,
        MysteryType mystery,
        String reflection,
        List<String> intentions,
        Set<String> amens,
        List<CheckInComment> comments,
        boolean isPublic,
        Integer prayerDuration,
        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt) {
}
