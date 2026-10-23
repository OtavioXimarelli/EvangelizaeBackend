package dev.ximarelli.rosary.backend.checkins;

import jakarta.validation.constraints.NotBlank;

public record AddCommentRequest(
        @NotBlank String text) {
}
