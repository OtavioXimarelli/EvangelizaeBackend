package dev.ximarelli.rosary.backend.checkins;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MysteryType {
    MISTERIOS_GOZOSOS("Mistérios Gozosos", "joyful"),
    MISTERIOS_DOLOROSOS("Mistérios Dolorosos", "sorrowful"),
    MISTERIOS_GLORIOSOS("Mistérios Gloriosos", "glorious"),
    MISTERIOS_LUMINOSOS("Mistérios Luminosos", "luminous");

    private final String displayName;
    private final String frontendKey;

    MysteryType(String displayName, String frontendKey) {
        this.displayName = displayName;
        this.frontendKey = frontendKey;
    }

    @JsonValue
    public String displayName() {
        return displayName;
    }

    @JsonCreator
    public static MysteryType fromApiValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        for (MysteryType mysteryType : values()) {
            if (mysteryType.displayName.equalsIgnoreCase(value)
                    || mysteryType.frontendKey.equalsIgnoreCase(value)
                    || mysteryType.name().equalsIgnoreCase(value)) {
                return mysteryType;
            }
        }

        throw new IllegalArgumentException("Unsupported mystery value: " + value);
    }
}
