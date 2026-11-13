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
import org.playlet.domain.model.Capability;
import org.playlet.domain.model.FirstFrameSource;
import org.playlet.domain.model.ShotState;
import org.playlet.domain.model.VersionKind;
import org.playlet.domain.repo.ShotRepository;
import org.playlet.domain.repo.ShotVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 生成入口（主流程 ⑤ 首帧图生成 + ⑤b 视频生成），**异步非阻塞**。
 *
 * <p>流程：提交即返回（version.genState=GENERATING）→ 后台线程轮询 history 判定完成
 * （权威）→ 写产物/状态 → WebSocket 广播 completed/failed。ComfyUI /ws 的实时
 * progress 事件经 {@link ComfyProgressMonitor} 转发为百分比推送（WS 断开不影响正确性）。
 *
 * <p>只接受**已确认**的提示词（Shot.visualPrompt 由确认门写入，见 DESIGN.md §6.3）。
 * 崩溃恢复：启动时把残留 GENERATING 行重新挂回轮询（ComfyUI history 仍在）。
 *
 * <p>首帧阶段：图后端由能力声明选出（supports TEXT_TO_IMAGE），默认取配置
 * {@code playlet.generation.default-image-backend}；产物审核后由 ReviewController
 * 提升进 ComfyUI input 目录并回填 Shot.firstFrameImage（闸门②）。
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
    private final String defaultImageBackend;
    private final ExecutorService workers = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "gen-worker");
        t.setDaemon(true);
        return t;
    });

    public GenerationService(ShotRepository shots, ShotVersionRepository versions,
                             List<GenerationBackend> backends,
                             ProgressEventPublisher events, ComfyProgressMonitor monitor,
                             @Value("${playlet.generation.default-image-backend:ANIMA}")
                             String defaultImageBackend) {
        this.shots = shots;
        this.versions = versions;
        this.backends = backends;
        this.events = events;
        this.monitor = monitor;
        this.defaultImageBackend = defaultImageBackend;
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
        // 闸门②前置：REFERENCE 来源的镜头必须先有**审核通过**的首帧图
        //（approve 时由 ReviewController 提升进 ComfyUI input 并回填 firstFrameImage）
        if (shot.getFirstFrameSource() == FirstFrameSource.REFERENCE
                && (shot.getFirstFrameImage() == null || shot.getFirstFrameImage().isBlank())) {
            throw new IllegalStateException(
                    "shot " + shotId + " 尚无已审核通过的首帧图——请先生成首帧并通过闸门②（§7 L4：改视频不如改首帧）");
        }

        Long seed = seedOverride;
        GenerationRequest req = GenerationRequest.video(
                shot.getVisualPrompt(), shot.getDurationSec(), project.getAspectRatio(),
                shot.getFirstFrameImage(), seed, project.isAudioNative(),
                negativeExtra(shot));

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

    /**
     * 提交首帧图生成（主流程 ⑤），立即返回 GENERATING 版本。
     * 图后端按能力声明选择（supports TEXT_TO_IMAGE），id 可显式指定（ANIMA/ILLUSTRIOUS）。
     */
    @Transactional
    public ShotVersion generateFirstFrame(Long shotId, Long seedOverride, String backendId) {
        Shot shot = shots.findById(shotId)
                .orElseThrow(() -> new IllegalArgumentException("shot not found: " + shotId));
        Project project = shot.getProject();

        GenerationBackend backend = imageBackend(backendId);
        if (shot.getVisualPrompt() == null || shot.getVisualPrompt().isBlank()) {
            throw new IllegalStateException(
                    "shot " + shotId + " 的 visualPrompt 为空——提示词须经确认门写入后才能生成（§6.3）");
        }
        if (shot.getState() == ShotState.FIRST_FRAME_GENERATING
                || shot.getState() == ShotState.VIDEO_GENERATING) {
            throw new IllegalStateException("shot " + shotId + " 正在生成中（" + shot.getState() + "），请稍候");
        }

        int[] wh = resolveImageSize(project);
        GenerationRequest req = GenerationRequest.image(
                shot.getVisualPrompt(), wh[0], wh[1], seedOverride, negativeExtra(shot));
        String promptId = backend.submit(req);

        int nextNo = versions.findByShot_IdOrderByVersionNoAsc(shotId).size() + 1;
        ShotVersion version = new ShotVersion();
        version.setShot(shot);
        version.setVersionNo(nextNo);
        version.setKind(VersionKind.FIRST_FRAME);
        version.setBackend(backend.id());
        version.setPrompt(shot.getVisualPrompt());
        version.setSeed(seedOverride);
        version.setComfyPromptId(promptId);
        version.setGenState("GENERATING");
        version.setReviewState("PENDING");
        version.setCreatedAt(LocalDateTime.now().format(TS));
        versions.save(version);

        shot.setState(ShotState.FIRST_FRAME_GENERATING);
        shots.save(shot);

        watch(version.getId(), shotId, promptId, backend);
        events.publish(ProgressEvent.started(version.getId(), shotId, promptId));
        return version;
    }

    /** 图后端：显式 id 优先；默认配置 id；再兜底 = 第一个支持 TEXT_TO_IMAGE 的后端。 */
    private GenerationBackend imageBackend(String backendId) {
        String id = backendId == null || backendId.isBlank() ? defaultImageBackend : backendId.trim();
        GenerationBackend b = backendFor(id);
        if (b != null && b.supports(Capability.TEXT_TO_IMAGE)) {
            return b;
        }
        if (b != null) {
            throw new IllegalArgumentException("后端 " + id + " 不支持文生图（TEXT_TO_IMAGE）");
        }
        return backends.stream()
                .filter(x -> x.supports(Capability.TEXT_TO_IMAGE))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "没有可用的图像后端（配置 playlet.generation.default-image-backend=" + id
                                + "，且无后端声明 TEXT_TO_IMAGE）"));
    }

    /**
     * 首帧图分辨率：项目显式分辨率优先；否则按画幅映射到常见基准。
     * anima/illustrious 模板 width/height 均为占位符，必须有值。
     */
    /** 已确认的负向提示 → extraParams（SD 系图像后端消费 negative 键；视频后端忽略）。 */
    private static java.util.Map<String, Object> negativeExtra(Shot shot) {
        String neg = shot.getNegativePrompt();
        return neg == null || neg.isBlank() ? java.util.Map.of() : java.util.Map.of("negative", neg);
    }

    private int[] resolveImageSize(Project project) {
        if (project.getResolutionW() != null && project.getResolutionH() != null) {
            return new int[]{project.getResolutionW(), project.getResolutionH()};
        }
        String ratio = project.getAspectRatio() == null ? "9:16" : project.getAspectRatio();
        return switch (ratio) {
            case "16:9" -> new int[]{1344, 768};
            case "1:1" -> new int[]{1024, 1024};
            case "3:4" -> new int[]{896, 1152};
            case "4:3" -> new int[]{1152, 896};
            default -> new int[]{768, 1344}; // 9:16 竖屏短剧
        };
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
        if (result.durationSec() != null) {
            v.setDurationSec(result.durationSec());
        }
        versions.save(v);

        Shot shot = v.getShot();
        // 闸门②/③：产物就绪 → 进入对应待审状态（首帧图审核通过后才回填 firstFrameImage）
        shot.setState(v.getKind() == VersionKind.FIRST_FRAME
                ? ShotState.FIRST_FRAME_REVIEW
                : ShotState.VIDEO_REVIEW);
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
        // 回退到可重试状态：已有通过审核的首帧图 → 停在首帧待审（可直接重发视频）；
        // 否则 → PENDING（可重发首帧）
        shot.setState(shot.getFirstFrameImage() != null && !shot.getFirstFrameImage().isBlank()
                ? ShotState.FIRST_FRAME_REVIEW
                : ShotState.PENDING);
        shots.save(shot);

        events.publish(ProgressEvent.failed(versionId, shot.getId(), v.getComfyPromptId(), message));
    }

    private GenerationBackend backendFor(String id) {
        return backends.stream().filter(b -> b.id().equals(id)).findFirst().orElse(null);
    }
}
