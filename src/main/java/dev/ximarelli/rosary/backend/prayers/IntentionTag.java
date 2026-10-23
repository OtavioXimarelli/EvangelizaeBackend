package dev.ximarelli.rosary.backend.prayers;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum IntentionTag {
    FAMILIA("Família", "family"),
    PAZ("Paz", "peace"),
    SAUDE("Saúde", "health"),
    TRABALHO("Trabalho", "work"),
    ESTUDOS("Estudos", "faith"),
    VOCACAO("Vocação", "love"),
    CONVERSAO("Conversão", "healing"),
    IGREJA("Igreja", "faith"),
    FIEIS_DEFUNTOS("Fiéis Defuntos", "peace"),
    PESSOAL("Pessoal", "gratitude");

    private final String displayName;
    private final String frontendKey;

    IntentionTag(String displayName, String frontendKey) {
        this.displayName = displayName;
        this.frontendKey = frontendKey;
    }

    @JsonValue
    public String displayName() {
        return displayName;
    }

    @JsonCreator
    public static IntentionTag fromApiValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.trim().toLowerCase();
        switch (normalized) {
            case "family":
                return FAMILIA;
            case "peace":
                return PAZ;
            case "health":
                return SAUDE;
            case "gratitude":
                return PESSOAL;
            case "work":
                return TRABALHO;
            case "faith":
                return IGREJA;
            case "love":
                return VOCACAO;
            case "healing":
                return CONVERSAO;
            default:
                break;
        }

        for (IntentionTag intentionTag : values()) {
            if (intentionTag.displayName.equalsIgnoreCase(value)
                    || intentionTag.frontendKey.equalsIgnoreCase(value)
                    || intentionTag.name().equalsIgnoreCase(value)) {
                return intentionTag;
            }
        }

        throw new IllegalArgumentException("Unsupported intention tag value: " + value);
    }
}
