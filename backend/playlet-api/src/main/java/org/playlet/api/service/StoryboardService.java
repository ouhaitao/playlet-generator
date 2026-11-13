package org.playlet.api.service;

import org.playlet.agent.PromptOptimizer;
import org.playlet.agent.StoryboardPlanner;
import org.playlet.api.web.NotFoundException;
import org.playlet.comfyui.backend.GenerationBackend;
import org.playlet.domain.entity.CharacterRef;
import org.playlet.domain.entity.Project;
import org.playlet.domain.entity.PromptDraft;
import org.playlet.domain.entity.Scene;
import org.playlet.domain.entity.Shot;
import org.playlet.domain.model.FirstFrameSource;
import org.playlet.domain.model.ProjectMode;
import org.playlet.domain.model.ShotState;
import org.playlet.domain.repo.CharacterRefRepository;
import org.playlet.domain.repo.ProjectRepository;
import org.playlet.domain.repo.PromptDraftRepository;
import org.playlet.domain.repo.SceneRepository;
import org.playlet.domain.repo.ShotRepository;
import org.playlet.domain.repo.ShotVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分镜表编排服务（BACKLOG ① / DESIGN.md §6.2 闸门①）。
 *
 * <p>职责链：{@link StoryboardPlanner} 切镜（LLM/回退切句）→ 落 Shot 行（时长对齐后端帧网格、
 * firstFrameSource 按模式推导、角色/场景按名字挂接既有引用）→ 逐镜
 * {@link PromptOptimizer#optimizeShotPrompt} 出草稿 → 前端整表展示 →
 * {@link DraftGateService#confirm} 批量确认写槽 → 全确认后推进 {@code Project.state}。
 *
 * <p>项目状态推进：DRAFT → STORYBOARD（已切镜待确认）→ STORYBOARD_CONFIRMED（闸门①通过）。
 * 确认后若逐行改提示词（新草稿未决），状态回退 STORYBOARD，重新批量确认后再次推进。
 */
@Service
public class StoryboardService {

    private static final Logger log = LoggerFactory.getLogger(StoryboardService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 项目状态：已切镜、闸门①未通过。 */
    public static final String STATE_STORYBOARD = "STORYBOARD";
    /** 项目状态：闸门①批量确认完成，分镜表定稿。 */
    public static final String STATE_STORYBOARD_CONFIRMED = "STORYBOARD_CONFIRMED";

    private final ProjectRepository projects;
    private final ShotRepository shots;
    private final ShotVersionRepository versions;
    private final PromptDraftRepository drafts;
    private final CharacterRefRepository characters;
    private final SceneRepository scenes;
    private final StoryboardPlanner planner;
    private final PromptOptimizer optimizer;
    private final DraftGateService gate;
    private final List<GenerationBackend> backends;

    public StoryboardService(ProjectRepository projects, ShotRepository shots,
                             ShotVersionRepository versions, PromptDraftRepository drafts,
                             CharacterRefRepository characters, SceneRepository scenes,
                             StoryboardPlanner planner, PromptOptimizer optimizer,
                             DraftGateService gate, List<GenerationBackend> backends) {
        this.projects = projects;
        this.shots = shots;
        this.versions = versions;
        this.drafts = drafts;
        this.characters = characters;
        this.scenes = scenes;
        this.planner = planner;
        this.optimizer = optimizer;
        this.gate = gate;
        this.backends = backends;
    }

    /** 分镜表一行：镜头字段 + 最新草稿状态（前端闸门①整表渲染用）。 */
    public record Row(
            Long shotId, int ord, double durationSec, String shotSize, String cameraMove,
            String dialogue, String speaker, String scene, String firstFrameSource,
            String shotState, String visualPrompt, String negativePrompt,
            Long draftId, int draftVersion, String draftState, String draftContent,
            String draftNegative, boolean confirmed) {}

    /** 切镜结果：新表 + 是否走了 LLM（false = 回退切句，前端提示建议逐镜润色）。 */
    public record GenerateResult(List<Row> rows, boolean usedLlm, int shotCount) {}

    /** 批量确认结果：本次确认数 + 仍未确认数 + 项目状态。 */
    public record ConfirmResult(int confirmedNow, int stillPending, String projectState) {}

    /**
     * 梗概 → 分镜表（重切会替换全部未生成产物的 PENDING 镜头）。
     *
     * <p>保护：任何镜头已有生成版本（ShotVersion）或状态已越过 PENDING 时拒绝重切，
     * 避免误删已进入生成链路的成果。
     */
    @Transactional
    public GenerateResult generate(Long projectId) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new NotFoundException("project " + projectId));

        List<Shot> existing = shots.findByProject_IdOrderByOrdAsc(projectId);
        for (Shot s : existing) {
            if (s.getState() != ShotState.PENDING
                    || !versions.findByShot_IdOrderByVersionNoAsc(s.getId()).isEmpty()) {
                throw new IllegalStateException(
                        "项目 " + projectId + " 的镜头 " + s.getId() + " 已进入生成流程（"
                                + s.getState() + "），不能重新切镜");
            }
        }
        // 替换旧表：连同其未决/历史草稿一并清掉（旧镜头行没了，草稿成为孤儿）
        for (Shot s : existing) {
            drafts.deleteAll(drafts.findByOwnerTypeAndOwnerIdOrderByVersionNoDesc("SHOT", s.getId()));
            shots.delete(s);
        }

        StoryboardPlanner.Plan plan = planner.plan(project);
        GenerationBackend backend = backendFor(project.getVideoModel());
        Map<String, CharacterRef> charsByName = indexCharacters(projectId);
        Map<String, Scene> scenesByName = indexScenes(projectId);
        String now = LocalDateTime.now().format(TS);

        List<Shot> created = new ArrayList<>();
        int ord = 1;
        for (StoryboardPlanner.ShotSpec spec : plan.shots()) {
            Shot shot = new Shot();
            shot.setProject(project);
            shot.setOrd(ord++);
            shot.setVisualPrompt(spec.visualPrompt()); // 粗糙画面描述 = 优化器的 base
            shot.setDurationSec(backend != null
                    ? backend.snapDuration(spec.durationSec()) : spec.durationSec());
            shot.setShotSize(spec.shotSize());
            shot.setCameraMove(spec.cameraMove());
            shot.setDialogue(spec.dialogue());
            shot.setFirstFrameSource(project.getMode() == ProjectMode.ONE_SHOT && shot.getOrd() > 1
                    ? FirstFrameSource.PREV_LAST_FRAME : FirstFrameSource.REFERENCE);
            shot.setState(ShotState.PENDING);
            shot.setCreatedAt(now);
            // 角色/场景按名字挂接既有引用；对不上名字的忽略（不擅自建角色，参考图环节再补）
            List<CharacterRef> cast = new ArrayList<>();
            for (String name : spec.characters()) {
                CharacterRef c = charsByName.get(name);
                if (c != null && !cast.contains(c)) {
                    cast.add(c);
                }
            }
            shot.setCharacters(cast);
            if (spec.speaker() != null) {
                shot.setSpeaker(charsByName.get(spec.speaker()));
            }
            if (spec.scene() != null) {
                shot.setScene(scenesByName.get(spec.scene()));
            }
            created.add(shots.save(shot));
        }

        // 切完逐镜出草稿（确认门的展示内容就是优化后的提示词，DESIGN §6.3）
        for (Shot shot : created) {
            try {
                optimizer.optimizeShotPrompt(shot, project, null);
            } catch (Exception e) {
                log.warn("shot {} 草稿生成失败（表内显示未决缺草稿，可手动优化）: {}",
                        shot.getId(), e.getMessage());
            }
        }

        project.setState(STATE_STORYBOARD);
        project.setUpdatedAt(LocalDateTime.now().format(TS));
        projects.save(project);

        List<Row> rows = table(projectId);
        return new GenerateResult(rows, plan.usedLlm(), created.size());
    }

    /** 分镜表整表：镜头 + 各自最新 visual_prompt 草稿。 */
    @Transactional(readOnly = true)
    public List<Row> table(Long projectId) {
        if (!projects.existsById(projectId)) {
            throw new NotFoundException("project " + projectId);
        }
        List<Row> rows = new ArrayList<>();
        for (Shot s : shots.findByProject_IdOrderByOrdAsc(projectId)) {
            rows.add(toRow(s));
        }
        return rows;
    }

    /**
     * 闸门①批量确认：逐镜确认最新 DRAFT（写槽位含 negative）。
     * 全部镜头确认后推进项目状态到 STORYBOARD_CONFIRMED。
     */
    @Transactional
    public ConfirmResult confirmAll(Long projectId) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new NotFoundException("project " + projectId));
        List<Shot> all = shots.findByProject_IdOrderByOrdAsc(projectId);
        if (all.isEmpty()) {
            throw new IllegalStateException("项目 " + projectId + " 尚无分镜，请先切镜");
        }
        int confirmedNow = 0;
        int pending = 0;
        for (Shot s : all) {
            PromptDraft latest = drafts
                    .findFirstByOwnerTypeAndOwnerIdAndFieldNameOrderByVersionNoDesc(
                            "SHOT", s.getId(), "visual_prompt")
                    .orElse(null);
            if (latest == null) {
                // 无草稿：槽位已有内容视作已定稿（手工建的镜头），否则补一轮优化再确认
                if (s.getVisualPrompt() == null || s.getVisualPrompt().isBlank()) {
                    gate.optimize("SHOT", s.getId(), "visual_prompt", null);
                    latest = drafts
                            .findFirstByOwnerTypeAndOwnerIdAndFieldNameOrderByVersionNoDesc(
                                    "SHOT", s.getId(), "visual_prompt").orElse(null);
                }
            }
            if (latest != null && "DRAFT".equals(latest.getState())) {
                gate.confirm(latest.getId());
                confirmedNow++;
            } else if (latest != null && "REJECTED".equals(latest.getState())) {
                pending++; // 被驳回的草稿要带意见重优化，批量确认不越过用户意志
            }
            // CONFIRMED / 无草稿但槽位有值 → 已定稿，跳过
        }
        String state = project.getState();
        if (pending == 0) {
            state = STATE_STORYBOARD_CONFIRMED;
            project.setState(state);
            project.setUpdatedAt(LocalDateTime.now().format(TS));
            projects.save(project);
        }
        return new ConfirmResult(confirmedNow, pending, state);
    }

    /**
     * 闸门①逐行改提示词：修改结果成为新草稿（§6.3 不擅自重优化），项目状态回退 STORYBOARD。
     * 前端随后对该行单条确认（POST /api/drafts/{id}/confirm）或再次批量确认。
     */
    @Transactional
    public Row editPrompt(Long projectId, Long shotId, String content) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new NotFoundException("project " + projectId));
        Shot shot = shots.findById(shotId)
                .orElseThrow(() -> new NotFoundException("shot " + shotId));
        if (!shot.getProject().getId().equals(projectId)) {
            throw new NotFoundException("shot " + shotId + " not in project " + projectId);
        }
        PromptDraft draft = gate.manualShotDraft(shotId, content);
        if (STATE_STORYBOARD_CONFIRMED.equals(project.getState())) {
            project.setState(STATE_STORYBOARD);
            project.setUpdatedAt(LocalDateTime.now().format(TS));
            projects.save(project);
        }
        return toRow(shots.findById(shotId).orElseThrow());
    }

    // ── 内部 ──────────────────────────────────────────────

    private Row toRow(Shot s) {
        PromptDraft latest = drafts
                .findFirstByOwnerTypeAndOwnerIdAndFieldNameOrderByVersionNoDesc(
                        "SHOT", s.getId(), "visual_prompt")
                .orElse(null);
        boolean confirmed = latest == null
                ? s.getVisualPrompt() != null && !s.getVisualPrompt().isBlank()
                : "CONFIRMED".equals(latest.getState());
        return new Row(
                s.getId(), s.getOrd(), s.getDurationSec(), s.getShotSize(), s.getCameraMove(),
                s.getDialogue(),
                s.getSpeaker() == null ? null : s.getSpeaker().getName(),
                s.getScene() == null ? null : s.getScene().getName(),
                s.getFirstFrameSource() == null ? null : s.getFirstFrameSource().name(),
                s.getState() == null ? null : s.getState().name(),
                s.getVisualPrompt(), s.getNegativePrompt(),
                latest == null ? null : latest.getId(),
                latest == null ? 0 : latest.getVersionNo(),
                latest == null ? null : latest.getState(),
                latest == null ? null : latest.getContent(),
                latest == null ? null : latest.getNegativePrompt(),
                confirmed);
    }

    private GenerationBackend backendFor(String modelId) {
        if (modelId == null) {
            return null;
        }
        return backends.stream()
                .filter(b -> b.id().equalsIgnoreCase(modelId))
                .findFirst().orElse(null);
    }

    private Map<String, CharacterRef> indexCharacters(Long projectId) {
        Map<String, CharacterRef> m = new HashMap<>();
        for (CharacterRef c : characters.findByProject_Id(projectId)) {
            if (c.getName() != null) {
                m.put(c.getName().trim(), c);
            }
        }
        return m;
    }

    private Map<String, Scene> indexScenes(Long projectId) {
        Map<String, Scene> m = new HashMap<>();
        for (Scene s : scenes.findByProject_Id(projectId)) {
            if (s.getName() != null) {
                m.put(s.getName().trim(), s);
            }
        }
        return m;
    }
}
