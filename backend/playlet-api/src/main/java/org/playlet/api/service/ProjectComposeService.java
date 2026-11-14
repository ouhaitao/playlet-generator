package org.playlet.api.service;

import org.playlet.api.web.NotFoundException;
import org.playlet.api.web.ProgressEvent;
import org.playlet.api.web.ProgressEventPublisher;
import org.playlet.comfyui.client.ComfyFileStore;
import org.playlet.domain.entity.Project;
import org.playlet.domain.entity.Shot;
import org.playlet.domain.entity.ShotVersion;
import org.playlet.domain.model.ShotState;
import org.playlet.domain.model.VersionKind;
import org.playlet.domain.repo.ProjectRepository;
import org.playlet.domain.repo.ShotRepository;
import org.playlet.domain.repo.ShotVersionRepository;
import org.playlet.media.ComposeService;
import org.playlet.media.SubtitleBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 成片合成编排（BACKLOG ②「合成 E2E + 入口」/ DESIGN.md §6.2 末环、§8）。
 *
 * <p>职责：把「多镜全 APPROVED」的项目一键合成可播成片——
 * 收集各镜**审核通过**的 VIDEO 版本产物（ComfyUI output）→ 台词轨（M2 接 TTS，
 * 当前恒无）→ BGM（项目配置）→ 按真实分片时长生成 SRT → {@link ComposeService}
 * 拼接混音烧字幕 → 成品落存储目录 compose/project_&lt;id&gt;/final.mp4，
 * 经 /media/store/** 静态服务播放。
 *
 * <p>异步非阻塞（与 GenerationService 同节奏）：POST 提交即返回 COMPOSING，
 * 进度经 WS compose_* 事件推送（concat/mix/subtitle 阶段映射全局百分比），
 * 完成后写回 Project.finalVideoPath/composedAt。失败写 composeError，fail-loud。
 */
@Service
public class ProjectComposeService {

    private static final Logger log = LoggerFactory.getLogger(ProjectComposeService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 合成结果：状态 + 成品相对路径（相对存储根，前端拼 /media/store/ 前缀）。 */
    public record ComposeResult(String state, String finalVideoPath, String message) {}

    public static final String STATE_COMPOSING = "COMPOSING";
    /** 项目状态：成片已合成（Project.state 推进；重新合成不回退）。 */
    public static final String STATE_PROJECT_COMPOSED = "COMPOSED";

    private final ProjectRepository projects;
    private final ShotRepository shots;
    private final ShotVersionRepository versions;
    private final ComfyFileStore comfyFiles;
    private final ComposeService compose;
    private final SubtitleBuilder subtitles;
    private final ProgressEventPublisher events;
    private final Path storageDir;

    /** 正在合成的项目（防重复提交；单用户场景内存足够）。 */
    private final Set<Long> composing = ConcurrentHashMap.newKeySet();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "compose-worker");
        t.setDaemon(true);
        return t;
    });

    public ProjectComposeService(ProjectRepository projects, ShotRepository shots,
                                 ShotVersionRepository versions, ComfyFileStore comfyFiles,
                                 ComposeService compose, SubtitleBuilder subtitles,
                                 ProgressEventPublisher events,
                                 @Value("${playlet.storage.dir}") String storageDir) {
        this.projects = projects;
        this.shots = shots;
        this.versions = versions;
        this.comfyFiles = comfyFiles;
        this.compose = compose;
        this.subtitles = subtitles;
        this.events = events;
        this.storageDir = Paths.get(storageDir).toAbsolutePath().normalize();
    }

    /**
     * 提交合成（异步）：校验通过后立即返回 COMPOSING，后台执行。
     * 校验（400 fail-loud）：项目存在、有分镜、每镜 APPROVED 且有已通过的 VIDEO 产物、未在合成中。
     */
    public ComposeResult submit(Long projectId) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new NotFoundException("project " + projectId));
        if (!composing.add(projectId)) {
            throw new IllegalStateException("项目 " + projectId + " 正在合成中，请稍候");
        }
        try {
            List<Shot> all = shots.findByProject_IdOrderByOrdAsc(projectId);
            if (all.isEmpty()) {
                throw new IllegalStateException("项目 " + projectId + " 尚无分镜，无法合成");
            }
            // 完成标准门（BACKLOG ②）：多镜全 APPROVED 才能一键出片
            for (Shot s : all) {
                if (s.getState() != ShotState.APPROVED) {
                    throw new IllegalStateException("镜头 #" + s.getOrd() + "（id=" + s.getId()
                            + "）尚未通过审核（" + s.getState() + "）——全部分镜 APPROVED 后才能合成");
                }
            }
            // 提交时就把素材清单验完（缺产物立即 400，不留到后台才失败）
            List<Path> videos = collectApprovedVideos(all);
            project.setComposeError(null);
            project.setUpdatedAt(LocalDateTime.now().format(TS));
            projects.save(project);
            events.publish(ProgressEvent.composeStarted(projectId));

            String bgmRel = resolveBgm(project);
            worker.submit(() -> run(projectId, videos, bgmRel));
            return new ComposeResult(STATE_COMPOSING, project.getFinalVideoPath(), null);
        } catch (RuntimeException e) {
            composing.remove(projectId);
            throw e;
        }
    }

    /** 后台执行：SRT → 合成 → 落库 → WS 广播。 */
    private void run(Long projectId, List<Path> videos, String bgmRel) {
        try {
            List<Shot> all = shots.findByProject_IdOrderByOrdAsc(projectId);
            List<String> dialogues = all.stream().map(Shot::getDialogue).toList();

            Path outDir = storageDir.resolve("compose").resolve("project_" + projectId);
            Files.createDirectories(outDir);

            Optional<Path> srt = subtitles.buildSrt(videos, dialogues, outDir, "final");
            Path bgm = bgmRel == null ? null : storageDir.resolve(bgmRel);

            Path out = compose.compose(videos, null /* dialogue TTS 在 BACKLOG ⑤ */, bgm,
                    srt.orElse(null), outDir, "final", (stage, pct) ->
                            events.publish(ProgressEvent.composeProgress(
                                    projectId, stage, globalPercent(stage, pct))));

            String rel = storageDir.relativize(out.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
            Project project = projects.findById(projectId).orElseThrow();
            project.setFinalVideoPath(rel);
            project.setComposedAt(LocalDateTime.now().format(TS));
            project.setComposeError(null);
            project.setState(STATE_PROJECT_COMPOSED);
            project.setUpdatedAt(LocalDateTime.now().format(TS));
            projects.save(project);
            log.info("project {} composed: {}", projectId, rel);
            events.publish(ProgressEvent.composeCompleted(projectId, rel));
        } catch (Exception e) {
            log.error("compose failed: project={}", projectId, e);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            try {
                Project project = projects.findById(projectId).orElse(null);
                if (project != null) {
                    project.setComposeError(msg.length() > 2000 ? msg.substring(0, 2000) : msg);
                    project.setUpdatedAt(LocalDateTime.now().format(TS));
                    projects.save(project);
                }
            } catch (Exception persistEx) {
                log.warn("failed to persist compose error: {}", persistEx.getMessage());
            }
            events.publish(ProgressEvent.composeFailed(projectId, msg));
        } finally {
            composing.remove(projectId);
        }
    }

    /** 各镜已通过的 VIDEO 版本产物 → 绝对路径（ComfyUI output 下，必须存在）。 */
    private List<Path> collectApprovedVideos(List<Shot> ordered) {
        List<Path> videos = new ArrayList<>();
        for (Shot s : ordered) {
            ShotVersion approved = versions.findByShot_IdOrderByVersionNoAsc(s.getId()).stream()
                    .filter(v -> v.getKind() == VersionKind.VIDEO
                            && "APPROVED".equals(v.getReviewState())
                            && "DONE".equals(v.getGenState())
                            && v.getArtifactPath() != null && !v.getArtifactPath().isBlank())
                    .reduce((first, second) -> second) // 多个通过版本时取最新
                    .orElseThrow(() -> new IllegalStateException(
                            "镜头 #" + s.getOrd() + "（id=" + s.getId() + "）没有审核通过的视频产物"));
            Path p = comfyFiles.resolveOutput(approved.getArtifactPath());
            if (!Files.exists(p)) {
                throw new IllegalStateException("镜头 #" + s.getOrd() + " 的视频产物文件不存在（ComfyUI output 被清理？）: "
                        + p);
            }
            videos.add(p);
        }
        return videos;
    }

    /** BGM：audioBgm 开且配置了素材才用；配置了但文件缺失 fail-loud。 */
    private String resolveBgm(Project project) {
        if (!project.isAudioBgm() || project.getBgmAssetPath() == null
                || project.getBgmAssetPath().isBlank()) {
            return null;
        }
        String rel = project.getBgmAssetPath().replace('\\', '/');
        Path p = storageDir.resolve(rel).normalize();
        if (!p.startsWith(storageDir)) {
            throw new IllegalStateException("bgmAssetPath 越界: " + rel);
        }
        if (!Files.exists(p)) {
            throw new IllegalStateException("BGM 素材文件不存在: " + p);
        }
        return rel;
    }

    /** 阶段局部进度 → 全局百分比：concat 0~60，mix 60~85，subtitle 85~100。 */
    static double globalPercent(String stage, double stagePercent) {
        double p = Math.max(0, Math.min(100, stagePercent));
        return switch (stage) {
            case "concat" -> p * 0.60;
            case "mix" -> 60 + p * 0.25;
            default -> 85 + p * 0.15; // subtitle
        };
    }
}
