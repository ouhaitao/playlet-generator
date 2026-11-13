package org.playlet.api.web;

import org.playlet.comfyui.client.ComfyFileStore;
import org.playlet.domain.entity.Review;
import org.playlet.domain.entity.Shot;
import org.playlet.domain.entity.ShotVersion;
import org.playlet.domain.model.ReviewDecision;
import org.playlet.domain.model.ShotState;
import org.playlet.domain.model.VersionKind;
import org.playlet.domain.repo.ReviewRepository;
import org.playlet.domain.repo.ShotRepository;
import org.playlet.domain.repo.ShotVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 审核 REST API（闸门②③）：通过 / 打回。
 *
 * <p>闸门②（首帧图通过）时把产物从 ComfyUI output 提升进 input 目录
 * （playlet/shot_&lt;id&gt;_v&lt;no&gt;.png），并回填 Shot.firstFrameImage——
 * 之后图生视频的 LoadImage 直接用这个名字。提升失败（产物被清理等）拒绝通过，fail-loud。
 */
@RestController
@RequestMapping("/api/versions")
public class ReviewController {

    private static final Logger log = LoggerFactory.getLogger(ReviewController.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ShotVersionRepository versions;
    private final ReviewRepository reviews;
    private final ShotRepository shots;
    private final ComfyFileStore files;

    public ReviewController(ShotVersionRepository versions, ReviewRepository reviews,
                            ShotRepository shots, ComfyFileStore files) {
        this.versions = versions;
        this.reviews = reviews;
        this.shots = shots;
        this.files = files;
    }

    @PostMapping("/{id}/approve")
    public ShotVersion approve(@PathVariable Long id) {
        ShotVersion v = versions.findById(id)
                .orElseThrow(() -> new NotFoundException("version " + id));
        if (!"DONE".equals(v.getGenState())) {
            throw new IllegalStateException("version " + id + " 仍在生成中，不能审核");
        }

        Shot shot = v.getShot();
        if (v.getKind() == VersionKind.FIRST_FRAME) {
            // 闸门②：产物提升进 ComfyUI input 目录，回填 firstFrameImage
            try {
                String inputName = files.promoteToInput(v.getArtifactPath(),
                        "shot_" + shot.getId() + "_v" + v.getVersionNo());
                shot.setFirstFrameImage(inputName);
            } catch (IOException e) {
                log.error("promote first frame failed: version={} artifact={}",
                        id, v.getArtifactPath(), e);
                throw new IllegalStateException("首帧图入库失败（无法复制到 ComfyUI input 目录）: "
                        + e.getMessage(), e);
            }
            v.setReviewState("APPROVED");
            versions.save(v);
            record(v, ReviewDecision.APPROVE, null, null);
            // 状态停在 FIRST_FRAME_REVIEW：首帧已通过，等待提交视频生成
            shot.setState(ShotState.FIRST_FRAME_REVIEW);
            shots.save(shot);
            return v;
        }

        v.setReviewState("APPROVED");
        versions.save(v);
        record(v, ReviewDecision.APPROVE, null, null);

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

        // 打回首帧 = 清掉旧 firstFrameImage（若该帧此前通过过），镜头回到待生成
        Shot shot = v.getShot();
        if (v.getKind() == VersionKind.FIRST_FRAME) {
            shot.setFirstFrameImage(null);
            shot.setState(ShotState.PENDING);
            shots.save(shot);
        }
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
