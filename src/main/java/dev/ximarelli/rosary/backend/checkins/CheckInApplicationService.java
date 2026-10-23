package dev.ximarelli.rosary.backend.checkins;

import dev.ximarelli.rosary.backend.shared.PagedResult;
import dev.ximarelli.rosary.backend.users.User;
import dev.ximarelli.rosary.backend.users.UserRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class CheckInApplicationService {

    private final CheckInRepository checkInRepository;
    private final UserRepository userRepository;

    public CheckInApplicationService(CheckInRepository checkInRepository, UserRepository userRepository) {
        this.checkInRepository = checkInRepository;
        this.userRepository = userRepository;
    }

    public CheckInView create(String userId, MysteryType mystery, String reflection, List<String> intentions, Boolean isPublic, Integer prayerDuration) {
        User user = userRepository.findById(userId).orElseThrow();
        CheckIn saved = checkInRepository.save(new CheckIn(
                null,
                user.id(),
                user.name(),
                user.avatarUrl(),
                mystery,
                reflection,
                intentions == null ? List.of() : intentions,
                new HashSet<>(),
                new ArrayList<>(),
                isPublic == null || isPublic,
                prayerDuration,
                Instant.now(),
                null));
        return toView(saved, userId);
    }

    public PagedResult<CheckInView> getPublicFeed(int page, int limit, String viewerId) {
        return page(checkInRepository.findByIsPublicTrueOrderByCreatedAtDesc(), page, limit, viewerId);
    }

    public PagedResult<CheckInView> getUserCheckIns(String userId, int page, int limit) {
        return page(checkInRepository.findByUserIdOrderByCreatedAtDesc(userId), page, limit, userId);
    }

    public CheckInView getById(String id, String viewerId) {
        CheckIn checkIn = checkInRepository.findById(id).orElseThrow();
        return toView(checkIn, viewerId);
    }

    public CheckInView getToday(String userId) {
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endOfDay = startOfDay.plus(1, ChronoUnit.DAYS).minusNanos(1);
        return checkInRepository
                .findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(userId, startOfDay, endOfDay)
                .stream()
                .findFirst()
                .map(it -> toView(it, userId))
                .orElse(null);
    }

    public UserCheckInStats getStats(String userId) {
        List<CheckIn> all = checkInRepository.findByUserIdOrderByCreatedAtDesc(userId);
        int publicCount = (int) all.stream().filter(CheckIn::isPublic).count();
        return new UserCheckInStats(all.size(), publicCount);
    }

    public CheckInView toggleAmen(String checkInId, String userId) {
        CheckIn current = checkInRepository.findById(checkInId).orElseThrow();
        Set<String> amens = new HashSet<>(current.amens());
        if (amens.contains(userId)) {
            amens.remove(userId);
        } else {
            amens.add(userId);
        }
        CheckIn updated = checkInRepository.save(new CheckIn(
                current.id(),
                current.userId(),
                current.userName(),
                current.userAvatar(),
                current.mystery(),
                current.reflection(),
                current.intentions(),
                amens,
                current.comments(),
                current.isPublic(),
                current.prayerDuration(),
                current.createdAt(),
                current.updatedAt()));
        return toView(updated, userId);
    }

    public CheckInView addComment(String checkInId, String userId, String text) {
        CheckIn current = checkInRepository.findById(checkInId).orElseThrow();
        User user = userRepository.findById(userId).orElseThrow();
        List<CheckInComment> comments = new ArrayList<>(current.comments());
        comments.add(new CheckInComment(user.id(), user.name(), user.avatarUrl(), text, Instant.now()));

        CheckIn updated = checkInRepository.save(new CheckIn(
                current.id(),
                current.userId(),
                current.userName(),
                current.userAvatar(),
                current.mystery(),
                current.reflection(),
                current.intentions(),
                current.amens(),
                comments,
                current.isPublic(),
                current.prayerDuration(),
                current.createdAt(),
                current.updatedAt()));
        return toView(updated, userId);
    }

    public void delete(String id, String userId) {
        CheckIn current = checkInRepository.findById(id).orElseThrow();
        if (!current.userId().equals(userId)) {
            throw new IllegalArgumentException("Cannot delete another user's check-in");
        }
        checkInRepository.deleteById(id);
    }

    private PagedResult<CheckInView> page(List<CheckIn> list, int page, int limit, String viewerId) {
        int safePage = Math.max(page, 1);
        int safeLimit = Math.max(limit, 1);
        int from = Math.min((safePage - 1) * safeLimit, list.size());
        int to = Math.min(from + safeLimit, list.size());
        List<CheckInView> items = list.subList(from, to).stream().map(it -> toView(it, viewerId)).toList();
        return new PagedResult<>(safePage, safeLimit, list.size(), items);
    }

    private CheckInView toView(CheckIn checkIn, String viewerId) {
        List<CommentView> comments = checkIn.comments().stream()
                .map(it -> new CommentView(it.userId(), it.userName(), it.userAvatar(), it.text(), it.createdAt()))
                .toList();
        return new CheckInView(
                checkIn.id(),
                checkIn.userId(),
                checkIn.userName(),
                checkIn.userAvatar(),
                checkIn.mystery(),
                checkIn.reflection(),
                checkIn.intentions(),
                checkIn.amens().stream().sorted().toList(),
                checkIn.amens().size(),
                comments,
                checkIn.isPublic(),
                checkIn.prayerDuration(),
                checkIn.createdAt(),
                checkIn.amens().contains(viewerId));
    }
}
