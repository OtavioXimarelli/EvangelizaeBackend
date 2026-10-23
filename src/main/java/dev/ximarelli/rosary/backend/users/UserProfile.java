package dev.ximarelli.rosary.backend.users;

import java.time.Instant;

public record UserProfile(
        String id,
        String name,
        String email,
        String avatarUrl,
        String bio,
        Instant createdAt) {
}
