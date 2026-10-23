package dev.ximarelli.rosary.backend.users;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;


@Document(collection = "users")
public record User(
        @Id String id,
        String name,
        @Indexed(unique = true) String email,
        String passwordHash,
        String avatarUrl,
        String bio,
        int currentStreak,
        int longestStreak,
        int totalCheckIns,
        Instant lastCheckIn,
        @CreatedDate Instant createdAt,
        @LastModifiedDate Instant updatedAt) {

    public User withProfile(String newName, String newAvatarUrl, String newBio) {
        return new User(
                id,
                newName != null ? newName : name,
                email,
                passwordHash,
                newAvatarUrl != null ? newAvatarUrl : avatarUrl,
                newBio != null ? newBio : bio,
                currentStreak,
                longestStreak,
                totalCheckIns,
                lastCheckIn,
                createdAt,
                updatedAt);
    }
}
