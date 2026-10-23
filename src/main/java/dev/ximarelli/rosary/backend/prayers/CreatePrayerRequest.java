package dev.ximarelli.rosary.backend.prayers;

import dev.ximarelli.rosary.backend.prayers.IntentionTag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePrayerRequest(
        @NotBlank @Size(min = 3, max = 100) String title,
        @NotBlank @Size(min = 10, max = 1000) String description,
        IntentionTag category) {
}
