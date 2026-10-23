package dev.ximarelli.rosary.backend.prayers;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record PrayerRequestView(
        @JsonProperty("_id")
        String id,
        String userId,
        String userName,
        String userAvatar,
        String title,
        String description,
        IntentionTag category,
        int prayingForCount,
        boolean isUserPraying,
        boolean isActive,
        boolean isAnswered,
        Instant answeredAt,
        String testimonial,
        Instant createdAt) {
}
