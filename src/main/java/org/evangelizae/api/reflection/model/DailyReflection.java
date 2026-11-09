package org.evangelizae.api.reflection.model;

import java.time.Instant;
import java.time.LocalDate;

public record DailyReflection(
        LocalDate date,
        String text,
        String attribution,
        String reference,
        Instant createdAt,
        Instant updatedAt
) {
}
