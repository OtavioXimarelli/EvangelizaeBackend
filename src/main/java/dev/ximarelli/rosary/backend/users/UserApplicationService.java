package dev.ximarelli.rosary.backend.users;

import dev.ximarelli.rosary.backend.checkins.CheckIn;
import dev.ximarelli.rosary.backend.checkins.CheckInRepository;
import dev.ximarelli.rosary.backend.checkins.MysteryType;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UserApplicationService {

    private final UserRepository userRepository;
    private final CheckInRepository checkInRepository;

    public UserApplicationService(UserRepository userRepository, CheckInRepository checkInRepository) {
        this.userRepository = userRepository;
        this.checkInRepository = checkInRepository;
    }

    public UserProfile getProfile(String userId) {
        User user = userRepository.findById(userId).orElseThrow();
        return toProfile(user);
    }

    public UserProfile getById(String id) {
        User user = userRepository.findById(id).orElseThrow();
        return toProfile(user);
    }

    public UserStats getStats(String userId) {
        User user = userRepository.findById(userId).orElseThrow();
        List<CheckIn> userCheckIns = checkInRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<FavoriteMystery> favoriteMysteries = buildFavoriteMysteries(userCheckIns);
        Instant lastCheckIn = userCheckIns.stream()
                .map(CheckIn::createdAt)
                .max(Instant::compareTo)
                .orElse(user.lastCheckIn());

        return new UserStats(
                user.currentStreak(),
                user.longestStreak(),
                userCheckIns.size(),
                lastCheckIn,
                favoriteMysteries);
    }

    public UserProfile updateProfile(String userId, String name, String avatarUrl, String bio) {
        User current = userRepository.findById(userId).orElseThrow();
        User updated = current.withProfile(name, avatarUrl, bio);
        return toProfile(userRepository.save(updated));
    }

    public UserSummary toSummary(User user) {
        return new UserSummary(
                user.id(),
                user.name(),
                user.email(),
                user.avatarUrl(),
                user.currentStreak(),
                user.longestStreak(),
                user.totalCheckIns());
    }

    private UserProfile toProfile(User user) {
        return new UserProfile(
                user.id(),
                user.name(),
                user.email(),
                user.avatarUrl(),
                user.bio(),
                user.createdAt());
    }

    private List<FavoriteMystery> buildFavoriteMysteries(List<CheckIn> userCheckIns) {
        return userCheckIns.stream()
                .collect(Collectors.groupingBy(CheckIn::mystery, Collectors.summingInt(ignored -> 1)))
                .entrySet()
                .stream()
                .sorted(Map.Entry.<MysteryType, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(entry -> entry.getKey().name()))
                .map(entry -> new FavoriteMystery(entry.getKey(), entry.getValue()))
                .toList();
    }
}
