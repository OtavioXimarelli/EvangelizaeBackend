package dev.ximarelli.rosary.backend.journal;

import dev.ximarelli.rosary.backend.checkins.MysteryType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public record CreateJournalEntityRequest(
        Instant date,
        @NotBlank @Size(max = 5000) String content,
        String mood,
        List<String> tags,
        String intentions,
        MysteryType mystery) {
}
