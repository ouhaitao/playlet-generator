package org.playlet.api.service;

import jakarta.annotation.PostConstruct;
import org.playlet.api.web.ProgressEvent;
import org.playlet.api.web.ProgressEventPublisher;
import org.playlet.comfyui.backend.GenerationBackend;
import org.playlet.comfyui.backend.GenerationRequest;
import org.playlet.comfyui.backend.GenerationResult;
import org.playlet.comfyui.client.ComfyProgressMonitor;
import org.playlet.domain.entity.Project;
import org.playlet.domain.entity.Shot;
import org.playlet.domain.entity.ShotVersion;
import org.playlet.domain.model.ShotState;
import org.playlet.domain.model.VersionKind;
import org.playlet.domain.repo.ShotRepository;
import org.playlet.domain.repo.ShotVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 生成入口（主流程 ⑤b 视频生成），**异步非阻塞**。
 *
 * <p>流程：提交即返回（version.genState=GENERATING）→ 后台线程轮询 history 判定完成
 * （权威）→ 写产物/状态 → WebSocket 广播 completed/failed。ComfyUI /ws 的实时
 * progress 事件经 {@link ComfyProgressMonitor} 转发为百分比推送（WS 断开不影响正确性）。
 *
 * <p>只接受**已确认**的提示词（Shot.visualPrompt 由确认门写入，见 DESIGN.md §6.3）。
 * 崩溃恢复：启动时把残留 GENERATING 行重新挂回轮询（ComfyUI history 仍在）。
 */
@Service
public class GenerationService {

    private static final Logger log = LoggerFactory.getLogger(GenerationService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ShotRepository shots;
    private final ShotVersionRepository versions;
    private final List<GenerationBackend> backends;
    private final ProgressEventPublisher events;
    private final ComfyProgressMonitor monitor;
    private final ExecutorService workers = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "gen-worker");
        t.setDaemon(true);
        return t;
    });

    public GenerationService(ShotRepository shots, ShotVersionRepository versions,
                             List<GenerationBackend> backends,
                             ProgressEventPublisher events, ComfyProgressMonitor monitor) {
        this.shots = shots;
        this.versions = versions;
        this.backends = backends;
        this.events = events;
        this.monitor = monitor;
    }

    /** 崩溃恢复：残留 GENERATING 的版本重新挂回轮询。DB 不可用时不阻断启动。 */
    @PostConstruct
    void recoverInterrupted() {
        try {
            List<ShotVersion> stuck = versions.findAll().stream()
                    .filter(v -> "GENERATING".equals(v.getGenState()) && v.getComfyPromptId() != null)
                    .toList();
            for (ShotVersion v : stuck) {
                log.info("recovering interrupted generation: version={} promptId={}",
                        v.getId(), v.getComfyPromptId());
                GenerationBackend backend = backendFor(v.getBackend());
                if (backend != null) {
                    watch(v.getId(), v.getShot().getId(), v.getComfyPromptId(), backend);
                }
            }
            if (!stuck.isEmpty()) {
                log.info("recovered {} interrupted generation(s)", stuck.size());
            }
        } catch (Exception e) {
            log.warn("generation recovery skipped: {}", e.getMessage());
        }
    }

    /** 提交视频生成，立即返回 GENERATING 状态的版本。 */
    @Transactional
    public ShotVersion generateVideo(Long shotId, Long seedOverride) {
        Shot shot = shots.findById(shotId)
                .orElseThrow(() -> new IllegalArgumentException("shot not found: " + shotId));
        Project project = shot.getProject();

        GenerationBackend backend = backendFor(
                shot.getVideoModel() != null ? shot.getVideoModel() : project.getVideoModel());
        if (backend == null) {
            throw new IllegalArgumentException("no backend for model: " + project.getVideoModel());
        }
        if (shot.getVisualPrompt() == null || shot.getVisualPrompt().isBlank()) {
            throw new IllegalStateException(
                    "shot " + shotId + " 的 visualPrompt 为空——提示词须经确认门写入后才能生成（§6.3）");
        }

        Long seed = seedOverride;
        GenerationRequest req = GenerationRequest.video(
                shot.getVisualPrompt(), shot.getDurationSec(), project.getAspectRatio(),
                shot.getFirstFrameImage(), seed);

        String promptId = backend.submit(req);

        int nextNo = versions.findByShot_IdOrderByVersionNoAsc(shotId).size() + 1;
        ShotVersion version = new ShotVersion();
        version.setShot(shot);
        version.setVersionNo(nextNo);
        version.setKind(VersionKind.VIDEO);
        version.setBackend(backend.id());
        version.setPrompt(shot.getVisualPrompt());
        version.setSeed(seed);
        version.setComfyPromptId(promptId);
        version.setGenState("GENERATING");
        version.setReviewState("PENDING");
        version.setCreatedAt(LocalDateTime.now().format(TS));
        versions.save(version);

        shot.setState(ShotState.VIDEO_GENERATING);
        shots.save(shot);

        watch(version.getId(), shotId, promptId, backend);
        events.publish(ProgressEvent.started(version.getId(), shotId, promptId));
        return version;
    }

    /** 后台监听某次生成：WS 进度转发 + history 轮询判定完成。 */
    private void watch(Long versionId, Long shotId, String promptId, GenerationBackend backend) {
        monitor.listen(promptId, new ComfyProgressMonitor.Listener() {
            @Override
            public void onProgress(double percent) {
                events.publish(ProgressEvent.progress(versionId, shotId, promptId, percent));
            }

            @Override
            public void onError(String message) {
                // WS 报错也走 finalize 统一处理（history 轮询会确认）
                log.warn("comfy reported error for {}: {}", promptId, message);
            }
        });
        workers.submit(() -> {
            try {
                GenerationResult result = backend.awaitResult(promptId);
                finalizeSuccess(versionId, result);
            } catch (Exception e) {
                log.error("generation failed: version={} promptId={}", versionId, promptId, e);
                finalizeFailure(versionId, e.getMessage());
            } finally {
                monitor.unlisten(promptId);
            }
        });
    }

    /** 完成落库。注意：同类自调用不走代理，@Transactional 不生效；
     *  每次 save 各自独立事务，单用户场景可接受。 */
    void finalizeSuccess(Long versionId, GenerationResult result) {
        ShotVersion v = versions.findById(versionId).orElse(null);
        if (v == null) {
            return;
        }
        v.setGenState("DONE");
        v.setArtifactPath(result.subfolder() == null || result.subfolder().isEmpty()
                ? result.filename()
                : result.subfolder() + "/" + result.filename());
        versions.save(v);

        Shot shot = v.getShot();
        shot.setState(ShotState.VIDEO_REVIEW);
        shots.save(shot);

        events.publish(ProgressEvent.completed(versionId, shot.getId(), v.getComfyPromptId(),
                v.getArtifactPath()));
    }

    /** 失败落库（同上：独立事务）。 */
    void finalizeFailure(Long versionId, String message) {
        ShotVersion v = versions.findById(versionId).orElse(null);
        if (v == null) {
            return;
        }
        v.setGenState("FAILED");
        v.setErrorMessage(message == null ? "unknown error" : message);
        versions.save(v);

        Shot shot = v.getShot();
        shot.setState(ShotState.PENDING);
        shots.save(shot);

        events.publish(ProgressEvent.failed(versionId, shot.getId(), v.getComfyPromptId(), message));
    }

    private GenerationBackend backendFor(String id) {
        return backends.stream().filter(b -> b.id().equals(id)).findFirst().orElse(null);
    }
}
