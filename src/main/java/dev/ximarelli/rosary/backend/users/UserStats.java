package dev.ximarelli.rosary.backend.users;

import java.time.Instant;
import java.util.List;

public record UserStats(
        int currentStreak,
        int longestStreak,
        int totalCheckIns,
        Instant lastCheckIn,
        List<FavoriteMystery> favoriteMysteries) {
}
