package dev.ximarelli.rosary.backend.checkins;

import dev.ximarelli.rosary.backend.config.CurrentUser;
import dev.ximarelli.rosary.backend.shared.PagedResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class CheckInsController {

    private final CheckInApplicationService checkInService;

    public CheckInsController(CheckInApplicationService checkInService) {
        this.checkInService = checkInService;
    }

    @PostMapping("/checkins")
    @ResponseStatus(HttpStatus.CREATED)
    public CheckInView create(
            @CurrentUser String userId,
            @Valid @RequestBody CreateCheckInRequest request) {
        return checkInService.create(
                userId,
                request.mystery(),
                request.reflection(),
                request.intentions(),
                request.isPublic(),
                request.prayerDuration());
    }

    @GetMapping("/checkins/feed")
    public PagedResult<CheckInView> getFeed(
            @CurrentUser String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit) {
        return checkInService.getPublicFeed(page, limit, userId);
    }

    @GetMapping("/checkins/today")
    public Map<String, Object> getToday(@CurrentUser String userId) {
        CheckInView checkIn = checkInService.getToday(userId);
        return Map.of("hasCheckedIn", checkIn != null, "checkIn", checkIn);
    }

    @GetMapping("/checkins/my")
    public PagedResult<CheckInView> getMine(
            @CurrentUser String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        return checkInService.getUserCheckIns(userId, page, limit);
    }

    @GetMapping("/checkins/stats")
    public UserCheckInStats getStats(@CurrentUser String userId) {
        return checkInService.getStats(userId);
    }

    @GetMapping("/checkins/{id}")
    public CheckInView getById(
            @PathVariable String id,
            @CurrentUser String userId) {
        return checkInService.getById(id, userId);
    }

    @PostMapping("/checkins/{id}/amen")
    public AmenCountResponse toggleAmen(
            @PathVariable String id,
            @CurrentUser String userId) {
        CheckInView updated = checkInService.toggleAmen(id, userId);
        return new AmenCountResponse(updated.amenCount());
    }

    @PostMapping("/checkins/{id}/comments")
    public CheckInView addComment(
            @PathVariable String id,
            @CurrentUser String userId,
            @Valid @RequestBody AddCommentRequest request) {
        return checkInService.addComment(id, userId, request.text());
    }

    @DeleteMapping("/checkins/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable String id,
            @CurrentUser String userId) {
        checkInService.delete(id, userId);
    }
}
