package dev.ximarelli.rosary.backend.users;

import dev.ximarelli.rosary.backend.config.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UsersController {

    private final UserApplicationService userService;

    public UsersController(UserApplicationService userService) {
        this.userService = userService;
    }

    @GetMapping("/users/me")
    public UserProfile getMe(@CurrentUser String userId) {
        return userService.getProfile(userId);
    }

    @GetMapping("/users/me/stats")
    public UserStats getMyStats(@CurrentUser String userId) {
        return userService.getStats(userId);
    }

    @PutMapping("/users/me")
    public UserProfile updateMe(
            @CurrentUser String userId,
            @Valid @RequestBody UpdateUserRequest request) {
        return userService.updateProfile(userId, request.name(), request.avatarUrl(), request.bio());
    }

    @GetMapping("/users/{id}")
    public UserProfile getById(@PathVariable String id) {
        return userService.getById(id);
    }
}
