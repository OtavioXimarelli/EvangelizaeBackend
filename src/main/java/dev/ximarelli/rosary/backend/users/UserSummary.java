package dev.ximarelli.rosary.backend.users;

public record UserSummary(
        String id,
        String name,
        String email,
        String avatarUrl,
        int currentStreak,
        int longestStreak,
        int totalCheckIns) {
}
