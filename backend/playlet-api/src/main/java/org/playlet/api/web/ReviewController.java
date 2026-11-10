package org.playlet.api.web;

import org.playlet.domain.entity.Review;
import org.playlet.domain.entity.Shot;
import org.playlet.domain.entity.ShotVersion;
import org.playlet.domain.model.ReviewDecision;
import org.playlet.domain.model.ShotState;
import org.playlet.domain.repo.ReviewRepository;
import org.playlet.domain.repo.ShotRepository;
import org.playlet.domain.repo.ShotVersionRepository;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** 审核 REST API（闸门②③）：通过 / 打回。 */
@RestController
@RequestMapping("/api/versions")
public class ReviewController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ShotVersionRepository versions;
    private final ReviewRepository reviews;
    private final ShotRepository shots;

    public ReviewController(ShotVersionRepository versions, ReviewRepository reviews,
                            ShotRepository shots) {
        this.versions = versions;
        this.reviews = reviews;
        this.shots = shots;
    }

    @PostMapping("/{id}/approve")
    public ShotVersion approve(@PathVariable Long id) {
        ShotVersion v = versions.findById(id)
                .orElseThrow(() -> new NotFoundException("version " + id));
        if (!"DONE".equals(v.getGenState())) {
            throw new IllegalStateException("version " + id + " 仍在生成中，不能审核");
        }
        v.setReviewState("APPROVED");
        versions.save(v);
        record(v, ReviewDecision.APPROVE, null, null);

        Shot shot = v.getShot();
        shot.setState(ShotState.APPROVED);
        shots.save(shot);
        return v;
    }

    @PostMapping("/{id}/reject")
    public ShotVersion reject(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        ShotVersion v = versions.findById(id)
                .orElseThrow(() -> new NotFoundException("version " + id));
        String feedback = body == null ? null : (String) body.get("feedback");
        Integer level = body == null || body.get("regenLevel") == null
                ? null : Integer.valueOf(String.valueOf(body.get("regenLevel")));
        v.setReviewState("REJECTED");
        v.setRejectReason(feedback);
        versions.save(v);
        record(v, ReviewDecision.REGENERATE, feedback, level);
        return v;
    }

    private void record(ShotVersion v, ReviewDecision decision, String feedback, Integer level) {
        Review r = new Review();
        r.setShotVersion(v);
        r.setDecision(decision);
        r.setFeedback(feedback);
        r.setRegenLevel(level);
        r.setCreatedAt(LocalDateTime.now().format(TS));
        reviews.save(r);
    }
}
