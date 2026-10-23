package dev.ximarelli.rosary.backend.prayers;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Set;

@Document(collection = "prayer_requests")
public record PrayerRequest(
        @Id String id,
        String userId,
        String userName,
        String userAvatar,
        String title,
        String description,
        IntentionTag category,
        Set<String> prayingFor,
        boolean isActive,
        boolean isAnswered,
        Instant answeredAt,
        String testimonial,
        @CreatedDate Instant createdAt) {
}
