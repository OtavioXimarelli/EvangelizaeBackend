package dev.ximarelli.rosary.backend.prayers;

import dev.ximarelli.rosary.backend.prayers.PrayerRequestView;
import dev.ximarelli.rosary.backend.prayers.IntentionTag;
import dev.ximarelli.rosary.backend.prayers.PrayerRequest;
import dev.ximarelli.rosary.backend.prayers.PrayerRequestRepository;
import dev.ximarelli.rosary.backend.shared.PagedResult;
import dev.ximarelli.rosary.backend.users.User;
import dev.ximarelli.rosary.backend.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class PrayerApplicationService {

    private final PrayerRequestRepository prayerRepository;
    private final UserRepository userRepository;

    public PrayerApplicationService(PrayerRequestRepository prayerRepository, UserRepository userRepository) {
        this.prayerRepository = prayerRepository;
        this.userRepository = userRepository;
    }

    public PrayerRequestView create(String userId, String title, String description, IntentionTag category) {
        User user = userRepository.findById(userId).orElseThrow();
        PrayerRequest saved = prayerRepository.save(new PrayerRequest(
                null,
                user.id(),
                user.name(),
                user.avatarUrl(),
                title,
                description,
                category,
                new HashSet<>(),
                true,
                false,
                null,
                null,
                Instant.now()));
        return toView(saved, userId);
    }

    public PagedResult<PrayerRequestView> findAll(int page, int limit, IntentionTag category, String viewerId) {
        List<PrayerRequest> list = prayerRepository.findAll().stream()
                .filter(PrayerRequest::isActive)
                .filter(it -> category == null || category == it.category())
                .toList();
        return page(list, page, limit, viewerId);
    }

    public PagedResult<PrayerRequestView> findMine(String userId, int page, int limit) {
        return page(prayerRepository.findByUserId(userId), page, limit, userId);
    }

    public PagedResult<PrayerRequestView> testimonials(int page, int limit, String viewerId) {
        List<PrayerRequest> list = prayerRepository.findAll().stream()
                .filter(PrayerRequest::isAnswered)
                .toList();
        return page(list, page, limit, viewerId);
    }

    public PrayerRequestView findById(String id, String viewerId) {
        return toView(prayerRepository.findById(id).orElseThrow(), viewerId);
    }

    public PrayerRequestView update(String id, String userId, String title, String description, IntentionTag category, Boolean isActive) {
        PrayerRequest current = prayerRepository.findById(id).orElseThrow();
        if (!current.userId().equals(userId)) {
            throw new IllegalArgumentException("Cannot update another user's prayer");
        }
        PrayerRequest updated = prayerRepository.save(new PrayerRequest(
                current.id(),
                current.userId(),
                current.userName(),
                current.userAvatar(),
                title != null ? title : current.title(),
                description != null ? description : current.description(),
                category != null ? category : current.category(),
                current.prayingFor(),
                isActive != null ? isActive : current.isActive(),
                current.isAnswered(),
                current.answeredAt(),
                current.testimonial(),
                current.createdAt()));
        return toView(updated, userId);
    }

    public PrayerRequestView togglePrayingFor(String id, String userId) {
        PrayerRequest current = prayerRepository.findById(id).orElseThrow();
        Set<String> prayingFor = new HashSet<>(current.prayingFor());
        if (prayingFor.contains(userId)) {
            prayingFor.remove(userId);
        } else {
            prayingFor.add(userId);
        }
        PrayerRequest updated = prayerRepository.save(new PrayerRequest(
                current.id(),
                current.userId(),
                current.userName(),
                current.userAvatar(),
                current.title(),
                current.description(),
                current.category(),
                prayingFor,
                current.isActive(),
                current.isAnswered(),
                current.answeredAt(),
                current.testimonial(),
                current.createdAt()));
        return toView(updated, userId);
    }

    public PrayerRequestView markAnswered(String id, String userId, String testimonial) {
        PrayerRequest current = prayerRepository.findById(id).orElseThrow();
        if (!current.userId().equals(userId)) {
            throw new IllegalArgumentException("Cannot mark another user's prayer as answered");
        }
        PrayerRequest updated = prayerRepository.save(new PrayerRequest(
                current.id(),
                current.userId(),
                current.userName(),
                current.userAvatar(),
                current.title(),
                current.description(),
                current.category(),
                current.prayingFor(),
                false,
                true,
                Instant.now(),
                testimonial,
                current.createdAt()));
        return toView(updated, userId);
    }

    public void delete(String id, String userId) {
        PrayerRequest current = prayerRepository.findById(id).orElseThrow();
        if (!current.userId().equals(userId)) {
            throw new IllegalArgumentException("Cannot delete another user's prayer");
        }
        prayerRepository.deleteById(id);
    }

    private PagedResult<PrayerRequestView> page(List<PrayerRequest> list, int page, int limit, String viewerId) {
        int safePage = Math.max(page, 1);
        int safeLimit = Math.max(limit, 1);
        int from = Math.min((safePage - 1) * safeLimit, list.size());
        int to = Math.min(from + safeLimit, list.size());
        List<PrayerRequestView> items = list.subList(from, to).stream().map(it -> toView(it, viewerId)).toList();
        return new PagedResult<>(safePage, safeLimit, list.size(), items);
    }

    private PrayerRequestView toView(PrayerRequest prayer, String viewerId) {
        return new PrayerRequestView(
                prayer.id(),
                prayer.userId(),
                prayer.userName(),
                prayer.userAvatar(),
                prayer.title(),
                prayer.description(),
                prayer.category(),
                prayer.prayingFor().size(),
                prayer.prayingFor().contains(viewerId),
                prayer.isActive(),
                prayer.isAnswered(),
                prayer.answeredAt(),
                prayer.testimonial(),
                prayer.createdAt());
    }
}
