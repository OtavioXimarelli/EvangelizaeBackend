package dev.ximarelli.rosary.backend.auth;

public interface TokenIssuer {
    String issue(String userId);
}
