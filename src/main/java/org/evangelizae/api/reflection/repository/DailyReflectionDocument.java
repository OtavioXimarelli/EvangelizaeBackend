package org.evangelizae.api.reflection.repository;

import java.time.Instant;
import java.time.LocalDate;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("daily_reflections")
public record DailyReflectionDocument(
        @Id String id,
        @Indexed(unique = true) LocalDate date,
        String text,
        String attribution,
        String reference,
        Instant createdAt,
        Instant updatedAt
) {
}
