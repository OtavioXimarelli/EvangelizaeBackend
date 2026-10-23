package dev.ximarelli.rosary.backend.checkins;

import java.time.Instant;

public record CommentView(
        String userId,
        String userName,
        String userAvatar,
        String text,
        Instant createdAt) {
}
