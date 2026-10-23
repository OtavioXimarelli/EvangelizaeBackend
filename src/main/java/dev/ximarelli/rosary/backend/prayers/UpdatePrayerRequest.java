package dev.ximarelli.rosary.backend.prayers;

import dev.ximarelli.rosary.backend.prayers.IntentionTag;
import jakarta.validation.constraints.Size;

public record UpdatePrayerRequest(
        @Size(min = 3, max = 100) String title,
        @Size(min = 10, max = 1000) String description,
        IntentionTag category,
        Boolean isActive) {
}
