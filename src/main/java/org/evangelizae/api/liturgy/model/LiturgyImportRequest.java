package org.evangelizae.api.liturgy.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record LiturgyImportRequest(
        @NotBlank String schemaVersion,
        @NotBlank String scraperVersion,
        @NotNull Instant scrapedAt,
        @NotNull @Valid Period period,
        @NotEmpty List<@NotNull @Valid LiturgicalDay> days
) {
    public record Period(@NotNull LocalDate from, @NotNull LocalDate to) {
    }

    public record LiturgicalDay(
            @NotNull LocalDate date,
            @NotNull @Valid Celebration celebration,
            @NotNull @Valid LiturgicalSeason liturgicalSeason,
            @NotNull @Valid Parts parts,
            @NotEmpty List<@NotNull @Valid Source> sources,
            @NotNull @Valid Validation validation,
            String note
    ) {
    }

    public record Celebration(
            @NotBlank String name,
            @NotNull CelebrationType type,
            @NotNull LiturgicalColor liturgicalColor
    ) {
    }

    public record LiturgicalSeason(
            @NotBlank String name,
            @Min(1) Integer week,
            String liturgicalYear
    ) {
    }

    public record Parts(@NotEmpty List<@NotNull @Valid Reading> readings) {
    }

    public record Reading(
            @NotNull ReadingType type,
            String reference,
            String title,
            String response,
            String text,
            List<@NotNull @Valid Reading> options
    ) {
    }

    public record Source(
            @NotNull SourceName name,
            @NotNull SourceRole role,
            String url,
            @NotNull Instant collectedAt,
            String sourceHash,
            String contentHash
    ) {
    }

    public record Validation(
            @NotNull ValidationStatus status,
            @Min(0) int sourcesCompared,
            @NotNull List<@NotNull String> warnings
    ) {
    }

    public enum ReadingType {
        FIRST_READING,
        SECOND_READING,
        PSALM,
        GOSPEL,
        ACCLAMATION,
        SEQUENCE
    }

    public enum CelebrationType {
        WEEKDAY,
        SUNDAY,
        MEMORIAL,
        FEAST,
        SOLEMNITY
    }

    public enum SourceName {
        CNBB,
        VATICAN_NEWS
    }

    public enum SourceRole {
        PRIMARY,
        VALIDATION
    }

    public enum ValidationStatus {
        VALID,
        WARNING,
        REVIEW_REQUIRED
    }
}
