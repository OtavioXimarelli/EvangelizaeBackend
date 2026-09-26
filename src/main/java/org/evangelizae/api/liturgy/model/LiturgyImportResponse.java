package org.evangelizae.api.liturgy.model;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record LiturgyImportResponse(
        UUID importId,
        Status status,
        int received,
        int processed,
        int created,
        int updated,
        int unchanged,
        List<Failure> failed
) {
    public enum Status {
        SUCCESS,
        PARTIAL
    }

    public record Failure(LocalDate date, String code, String message) {
    }
}
