package dev.ximarelli.rosary.backend.auth;

import dev.ximarelli.rosary.backend.auth.TokenIssuer;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class UuidTokenIssuer implements TokenIssuer {

    @Override
    public String issue(String userId) {
        return "boilerplate-" + userId + "-" + UUID.randomUUID();
    }
}
