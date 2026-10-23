package dev.ximarelli.rosary.backend.prayers;

import dev.ximarelli.rosary.backend.config.CurrentUser;
import dev.ximarelli.rosary.backend.shared.PagedResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PrayersController {

    private final PrayerApplicationService prayerService;

    public PrayersController(PrayerApplicationService prayerService) {
        this.prayerService = prayerService;
    }

    @PostMapping("/prayers")
    @ResponseStatus(HttpStatus.CREATED)
    public PrayerRequestView create(
            @CurrentUser String userId,
            @Valid @RequestBody CreatePrayerRequest request) {
        return prayerService.create(userId, request.title(), request.description(), request.category());
    }

    @GetMapping("/prayers")
    public PagedResult<PrayerRequestView> findAll(
            @CurrentUser String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) IntentionTag category) {
        return prayerService.findAll(page, limit, category, userId);
    }

    @GetMapping("/prayers/my")
    public PagedResult<PrayerRequestView> getMine(
            @CurrentUser String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        return prayerService.findMine(userId, page, limit);
    }

    @GetMapping("/prayers/testimonials")
    public PagedResult<PrayerRequestView> getTestimonials(
            @CurrentUser String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit) {
        return prayerService.testimonials(page, limit, userId);
    }

    @GetMapping("/prayers/{id}")
    public PrayerRequestView getById(
            @PathVariable String id,
            @CurrentUser String userId) {
        return prayerService.findById(id, userId);
    }

    @PutMapping("/prayers/{id}")
    public PrayerRequestView update(
            @PathVariable String id,
            @CurrentUser String userId,
            @Valid @RequestBody UpdatePrayerRequest request) {
        return prayerService.update(
                id,
                userId,
                request.title(),
                request.description(),
                request.category(),
                request.isActive());
    }

    @PostMapping("/prayers/{id}/pray")
    public PrayerRequestView togglePray(
            @PathVariable String id,
            @CurrentUser String userId) {
        return prayerService.togglePrayingFor(id, userId);
    }

    @PostMapping("/prayers/{id}/answered")
    public PrayerRequestView markAnswered(
            @PathVariable String id,
            @CurrentUser String userId,
            @Valid @RequestBody MarkAnsweredRequest request) {
        return prayerService.markAnswered(id, userId, request.testimonial());
    }

    @DeleteMapping("/prayers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable String id,
            @CurrentUser String userId) {
        prayerService.delete(id, userId);
    }
}
