package org.playlet.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.playlet.api.web.ProgressEvent;
import org.playlet.api.web.ProgressEventPublisher;
import org.playlet.comfyui.backend.DefaultGenerationBackend;
import org.playlet.comfyui.backend.GenerationResult;
import org.playlet.comfyui.client.ComfyProgressMonitor;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 生成入口（图片生成 + 视频生成），**异步非阻塞**。
 *
 * <p>参数模型：前端选定工作流（导入时已解析出元数据）并按元数据表单填写参数，
 * 以 {@code {jsonPath: value}}（如 {@code "3/inputs/seed": 42}）提交。本服务只做
 * 落库 + 交给 {@link DefaultGenerationBackend}（按 jsonPath 写回原始工作流 → 提交 ComfyUI），
 * 不再推导/翻译任何参数——图片与视频走同一条通道，差异只在工作流本身。
 *
 * <p>流程：提交即返回（version.genState=GENERATING）→ 后台线程轮询 history 判定完成
 * （权威）→ 写产物/状态 → WebSocket 广播 completed/failed。ComfyUI /ws 的实时
 * progress 事件经 {@link ComfyProgressMonitor} 转发为百分比推送（WS 断开不影响正确性）。
 * 崩溃恢复：启动时把残留 GENERATING 行重新挂回轮询（ComfyUI history 仍在）。
 */
@Service
public class GenerationService {

    private static final Logger log = LoggerFactory.getLogger(GenerationService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ShotRepository shots;
    private final ShotVersionRepository versions;
    private final DefaultGenerationBackend backend;
    private final ProgressEventPublisher events;
    private final ComfyProgressMonitor monitor;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService workers = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "gen-worker");
        t.setDaemon(true);
        return t;
    });

    public GenerationService(ShotRepository shots, ShotVersionRepository versions,
                             DefaultGenerationBackend backend,
                             ProgressEventPublisher events, ComfyProgressMonitor monitor) {
        this.shots = shots;
        this.versions = versions;
        this.backend = backend;
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
                watch(v.getId(), v.getShot().getId(), v.getComfyPromptId());
            }
            if (!stuck.isEmpty()) {
                log.info("recovered {} interrupted generation(s)", stuck.size());
            }
        } catch (Exception e) {
            log.warn("generation recovery skipped: {}", e.getMessage());
        }
    }

    /**
     * 提交图片生成，立即返回 GENERATING 版本。
     *
     * @param workflow 已导入的工作流名（type=IMAGE）
     * @param params   jsonPath → 值（用户在审核台按解析元数据填写）
     */
    @Transactional
    public ShotVersion generateImage(Long shotId, String workflow, Map<String, Object> params) {
        return submit(shotId, workflow, params, VersionKind.FIRST_FRAME);
    }

    /**
     * 提交视频生成，立即返回 GENERATING 版本。
     * 图生视频所需的图片地址由用户直接填进工作流参数（如 LoadImage.image），后端不推导。
     *
     * @param workflow 已导入的工作流名（type=VIDEO）
     * @param params   jsonPath → 值
     */
    @Transactional
    public ShotVersion generateVideo(Long shotId, String workflow, Map<String, Object> params) {
        return submit(shotId, workflow, params, VersionKind.VIDEO);
    }

    private ShotVersion submit(Long shotId, String workflow, Map<String, Object> params,
                               VersionKind kind) {
        Shot shot = shots.findById(shotId)
                .orElseThrow(() -> new IllegalArgumentException("shot not found: " + shotId));
        if (workflow == null || workflow.isBlank()) {
            throw new IllegalArgumentException("workflow 必填（先在工作流库导入并在审核台选择）");
        }
        if (shot.getState() == ShotState.FIRST_FRAME_GENERATING
                || shot.getState() == ShotState.VIDEO_GENERATING) {
            throw new IllegalStateException("shot " + shotId + " 正在生成中（" + shot.getState() + "），请稍候");
        }
        Map<String, Object> safeParams = params == null ? Map.of() : params;

        String promptId = backend.submit(workflow, safeParams);

        int nextNo = versions.findByShot_IdOrderByVersionNoAsc(shotId).size() + 1;
        ShotVersion version = new ShotVersion();
        version.setShot(shot);
        version.setVersionNo(nextNo);
        version.setKind(kind);
        version.setBackend(backend.id());
        version.setParamsJson(toParamsJson(workflow, safeParams));
        version.setComfyPromptId(promptId);
        version.setGenState("GENERATING");
        version.setReviewState("PENDING");
        version.setCreatedAt(LocalDateTime.now().format(TS));
        versions.save(version);

        shot.setState(kind == VersionKind.FIRST_FRAME
                ? ShotState.FIRST_FRAME_GENERATING : ShotState.VIDEO_GENERATING);
        shots.save(shot);

        watch(version.getId(), shotId, promptId);
        events.publish(ProgressEvent.started(version.getId(), shotId, promptId));
        return version;
    }

    /** 参数留档：{workflow, params}（可回溯可复现）。序列化失败不阻断生成。 */
    private String toParamsJson(String workflow, Map<String, Object> params) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("workflow", workflow);
        doc.put("params", params);
        try {
            String s = json.writeValueAsString(doc);
            return s.length() > 8000 ? s.substring(0, 8000) : s;
        } catch (Exception e) {
            log.warn("serialize params failed: {}", e.getMessage());
            return null;
        }
    }

    /** 后台监听某次生成：WS 进度转发 + history 轮询判定完成。 */
    private void watch(Long versionId, Long shotId, String promptId) {
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
        // 闸门②/③：产物就绪 → 进入对应待审状态（图片审核通过后才回填 firstFrameImage）
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
        // 回退到可重试状态：已有通过审核的图片 → 停在图片待审（可直接重发视频）；
        // 否则 → PENDING（可重发图片）
        shot.setState(shot.getFirstFrameImage() != null && !shot.getFirstFrameImage().isBlank()
                ? ShotState.FIRST_FRAME_REVIEW
                : ShotState.PENDING);
        shots.save(shot);

        events.publish(ProgressEvent.failed(versionId, shot.getId(), v.getComfyPromptId(), message));
    }
}
