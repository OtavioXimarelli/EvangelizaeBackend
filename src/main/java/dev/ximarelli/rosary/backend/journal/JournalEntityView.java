package dev.ximarelli.rosary.backend.journal;

import dev.ximarelli.rosary.backend.checkins.MysteryType;

import java.time.Instant;
import java.util.List;

public record JournalEntityView(
        String id,
        Instant date,
        String content,
        String mood,
        List<String> tags,
        String intentions,
        MysteryType mystery) {
}
