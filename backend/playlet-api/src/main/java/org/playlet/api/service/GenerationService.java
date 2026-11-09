package org.playlet.api.service;

import org.playlet.comfyui.backend.GenerationBackend;
import org.playlet.comfyui.backend.GenerationRequest;
import org.playlet.comfyui.backend.GenerationResult;
import org.playlet.domain.entity.Project;
import org.playlet.domain.entity.Shot;
import org.playlet.domain.entity.ShotVersion;
import org.playlet.domain.model.ShotState;
import org.playlet.domain.model.VersionKind;
import org.playlet.domain.repo.ProjectRepository;
import org.playlet.domain.repo.ShotRepository;
import org.playlet.domain.repo.ShotVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 生成入口（主流程 ⑤b 视频生成）。
 * 只接受**已确认**的提示词（Shot.visualPrompt 由确认门写入，见 DESIGN.md §6.3）。
 * 后端选择：Shot.videoModel 优先，否则项目默认；经 GenerationBackend 能力门控。
 */
@Service
public class GenerationService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ShotRepository shots;
    private final ShotVersionRepository versions;
    private final ProjectRepository projects;
    private final List<GenerationBackend> backends;

    public GenerationService(ShotRepository shots, ShotVersionRepository versions,
                             ProjectRepository projects, List<GenerationBackend> backends) {
        this.shots = shots;
        this.versions = versions;
        this.projects = projects;
        this.backends = backends;
    }

    /** 提交视频生成（阻塞至完成，M1 简化；进度推送后续接 WebSocket）。 */
    @Transactional
    public ShotVersion generateVideo(Long shotId, Long seedOverride) {
        Shot shot = shots.findById(shotId)
                .orElseThrow(() -> new IllegalArgumentException("shot not found: " + shotId));
        Project project = shot.getProject();

        String backendId = shot.getVideoModel() != null ? shot.getVideoModel() : project.getVideoModel();
        GenerationBackend backend = backends.stream()
                .filter(b -> b.id().equals(backendId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no backend: " + backendId));

        if (shot.getVisualPrompt() == null || shot.getVisualPrompt().isBlank()) {
            throw new IllegalStateException(
                    "shot " + shotId + " 的 visualPrompt 为空——提示词须经确认门写入后才能生成（§6.3）");
        }

        Long seed = seedOverride != null ? seedOverride : null;
        GenerationRequest req = GenerationRequest.video(
                shot.getVisualPrompt(),
                shot.getDurationSec(),
                project.getAspectRatio(),
                shot.getFirstFrameSource() == org.playlet.domain.model.FirstFrameSource.REFERENCE
                        ? null   // 首帧图生成环节产出后回填；M1 先走无首帧校验由适配器把关
                        : null,
                seed);

        shot.setState(ShotState.VIDEO_GENERATING);
        shots.save(shot);

        GenerationResult result = backend.generate(req);

        int nextNo = versions.findByShotIdOrderByVersionNoAsc(shotId).size() + 1;
        ShotVersion version = new ShotVersion();
        version.setShot(shot);
        version.setVersionNo(nextNo);
        version.setKind(VersionKind.VIDEO);
        version.setBackend(backend.id());
        version.setPrompt(shot.getVisualPrompt());
        version.setSeed(seed);
        version.setArtifactPath(result.subfolder().isEmpty()
                ? result.filename()
                : result.subfolder() + "/" + result.filename());
        version.setDurationSec(result.durationSec());
        version.setReviewState("PENDING");
        version.setCreatedAt(LocalDateTime.now().format(TS));
        versions.save(version);

        shot.setState(ShotState.VIDEO_REVIEW);
        shots.save(shot);
        return version;
    }
}
