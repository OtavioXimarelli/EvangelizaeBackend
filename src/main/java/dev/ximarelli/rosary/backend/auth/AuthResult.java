package dev.ximarelli.rosary.backend.auth;

import dev.ximarelli.rosary.backend.users.UserSummary;

public record AuthResult(
        String accessToken,
        UserSummary user) {
}
