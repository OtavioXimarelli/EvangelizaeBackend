package org.evangelizae.api.liturgy.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("liturgical_days")
public record LiturgicalDayDocument(
        @Id String id,
        LocalDate date,
        String title,
        String color,
        List<Reading> readings,
        String celebrationType,
        Season season,
        String note,
        List<Source> sources,
        Validation validation,
        String scraperVersion,
        Instant scrapedAt,
        String primaryContentHash,
        String provider,
        Instant fetchedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant lastVerifiedAt
) {
    public record Reading(
            String kind,
            String title,
            String reference,
            String text,
            String refrain
    ) {
    }

    public record Season(String name, Integer week, String liturgicalYear) {
    }

    public record Source(
            String name,
            String role,
            String url,
            Instant collectedAt,
            String sourceHash,
            String contentHash
    ) {
    }

    public record Validation(String status, int sourcesCompared, List<String> warnings) {
    }
}
