package dev.ximarelli.rosary.backend.journal;

import dev.ximarelli.rosary.backend.checkins.MysteryType;
import dev.ximarelli.rosary.backend.shared.PagedResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class JournalApplicationService {

    private final JournalEntityRepository journalEntityRepository;

    public JournalApplicationService(JournalEntityRepository journalEntityRepository) {
        this.journalEntityRepository = journalEntityRepository;
    }

    public JournalEntityView create(
            String userId,
            Instant date,
            String content,
            String mood,
            List<String> tags,
            String intentions,
            MysteryType mystery) {
        Instant now = Instant.now();
        JournalEntity saved = journalEntityRepository.save(new JournalEntity(
                null,
                userId,
                date != null ? date : now,
                content,
                mood,
                tags == null ? List.of() : tags,
                intentions,
                mystery,
                now,
                now));
        return toView(saved);
    }

    public PagedResult<JournalEntityView> findByUser(String userId, Instant from, Instant to, int page, int limit) {
        List<JournalEntity> filtered = journalEntityRepository.findByUserIdOrderByDateDesc(userId).stream()
                .filter(entry -> from == null || !entry.date().isBefore(from))
                .filter(entry -> to == null || !entry.date().isAfter(to))
                .toList();

        int safePage = Math.max(page, 1);
        int safeLimit = Math.max(limit, 1);
        int fromIndex = Math.min((safePage - 1) * safeLimit, filtered.size());
        int toIndex = Math.min(fromIndex + safeLimit, filtered.size());
        List<JournalEntityView> items = filtered.subList(fromIndex, toIndex).stream().map(this::toView).toList();

        return new PagedResult<>(safePage, safeLimit, filtered.size(), items);
    }

    public JournalEntityView update(
            String id,
            String userId,
            Instant date,
            String content,
            String mood,
            List<String> tags,
            String intentions,
            MysteryType mystery) {
        JournalEntity current = journalEntityRepository.findById(id).orElseThrow();
        if (!current.userId().equals(userId)) {
            throw new IllegalArgumentException("Cannot update another user's journal entry");
        }

        JournalEntity updated = journalEntityRepository.save(new JournalEntity(
                current.id(),
                current.userId(),
                date != null ? date : current.date(),
                content != null ? content : current.content(),
                mood != null ? mood : current.mood(),
                tags != null ? tags : current.tags(),
                intentions != null ? intentions : current.intentions(),
                mystery != null ? mystery : current.mystery(),
                current.createdAt(),
                Instant.now()));

        return toView(updated);
    }

    public void delete(String id, String userId) {
        JournalEntity current = journalEntityRepository.findById(id).orElseThrow();
        if (!current.userId().equals(userId)) {
            throw new IllegalArgumentException("Cannot delete another user's journal entry");
        }
        journalEntityRepository.deleteById(id);
    }

    private JournalEntityView toView(JournalEntity entry) {
        return new JournalEntityView(
                entry.id(),
                entry.date(),
                entry.content(),
                entry.mood(),
                entry.tags(),
                entry.intentions(),
                entry.mystery());
    }
}
