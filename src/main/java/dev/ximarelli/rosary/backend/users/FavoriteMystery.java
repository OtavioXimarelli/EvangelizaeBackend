package dev.ximarelli.rosary.backend.users;

import dev.ximarelli.rosary.backend.checkins.MysteryType;

public record FavoriteMystery(
        MysteryType mystery,
        int count) {
}
