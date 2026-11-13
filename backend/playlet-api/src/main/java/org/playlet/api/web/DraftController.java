package org.playlet.api.web;

import org.playlet.agent.PromptOptimizer;
import org.playlet.domain.entity.CharacterRef;
import org.playlet.domain.entity.Project;
import org.playlet.domain.entity.PromptDraft;
import org.playlet.domain.entity.Scene;
import org.playlet.domain.entity.Shot;
import org.playlet.domain.repo.CharacterRefRepository;
import org.playlet.domain.repo.ProjectRepository;
import org.playlet.domain.repo.PromptDraftRepository;
import org.playlet.domain.repo.SceneRepository;
import org.playlet.domain.repo.ShotRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 提示词草稿确认门（DESIGN.md §6.3）。
 * 草稿（DRAFT）→ 用户确认（CONFIRMED，写入槽位）/ 驳回（REJECTED，带意见回 agent）。
 * 未确认的草稿不进入生成链路。
 *
 * <p>optimize 端点触发 agent 优化：粗糙描述 → 新草稿（LLM 不可用时确定性回退）。
 */
@RestController
@RequestMapping("/api/drafts")
public class DraftController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PromptDraftRepository drafts;
    private final ShotRepository shots;
    private final ProjectRepository projects;
    private final CharacterRefRepository characters;
    private final SceneRepository scenes;
    private final PromptOptimizer optimizer;

    public DraftController(PromptDraftRepository drafts, ShotRepository shots,
                           ProjectRepository projects, CharacterRefRepository characters,
                           SceneRepository scenes, PromptOptimizer optimizer) {
        this.drafts = drafts;
        this.shots = shots;
        this.projects = projects;
        this.characters = characters;
        this.scenes = scenes;
        this.optimizer = optimizer;
    }

    @GetMapping
    public List<PromptDraft> list(@RequestParam String ownerType, @RequestParam Long ownerId) {
        return drafts.findByOwnerTypeAndOwnerIdOrderByVersionNoDesc(ownerType, ownerId);
    }

    /**
     * 触发 agent 优化，产出新草稿（旧未决草稿作废）。
     * body: {"feedback": "上一轮驳回意见，可空"}
     */
    @PostMapping("/optimize")
    @Transactional
    public PromptDraft optimize(@RequestBody Map<String, Object> body) {
        String ownerType = str(body, "ownerType");
        Long ownerId = num(body, "ownerId");
        String fieldName = str(body, "fieldName");
        String feedback = str(body, "feedback");
        if (ownerType == null || ownerId == null || fieldName == null) {
            throw new IllegalArgumentException("ownerType / ownerId / fieldName 必填");
        }
        Project project = projectOf(ownerType, ownerId);
        return switch (ownerType + "." + fieldName) {
            case "SHOT.visual_prompt" -> {
                Shot shot = shots.findById(ownerId)
                        .orElseThrow(() -> new NotFoundException("shot " + ownerId));
                yield optimizer.optimizeShotPrompt(shot, project, feedback);
            }
            case "CHARACTER.appearance" -> {
                CharacterRef c = characters.findById(ownerId)
                        .orElseThrow(() -> new NotFoundException("character " + ownerId));
                yield optimizer.optimizeCharacterAppearance(c, project, feedback);
            }
            case "SCENE.description" -> {
                Scene s = scenes.findById(ownerId)
                        .orElseThrow(() -> new NotFoundException("scene " + ownerId));
                yield optimizer.optimizeSceneDescription(s, project, feedback);
            }
            default -> throw new IllegalArgumentException(
                    "不支持的优化目标：" + ownerType + "." + fieldName
                            + "（仅 SHOT.visual_prompt / CHARACTER.appearance / SCENE.description）");
        };
    }

    /** 确认草稿：状态置 CONFIRMED，并把内容写入对应槽位。 */
    @PostMapping("/{id}/confirm")
    @Transactional
    public PromptDraft confirm(@PathVariable Long id) {
        PromptDraft draft = drafts.findById(id)
                .orElseThrow(() -> new NotFoundException("draft " + id));
        if (!"DRAFT".equals(draft.getState())) {
            throw new IllegalStateException("draft " + id + " already " + draft.getState());
        }
        draft.setState("CONFIRMED");
        draft.setConfirmedAt(LocalDateTime.now().format(TS));
        applyToSlot(draft);
        return drafts.save(draft);
    }

    /** 驳回草稿：状态置 REJECTED，记录意见（agent 据此重新优化）。 */
    @PostMapping("/{id}/reject")
    @Transactional
    public PromptDraft reject(@PathVariable Long id, @RequestBody Map<String, String> body) {
        PromptDraft draft = drafts.findById(id)
                .orElseThrow(() -> new NotFoundException("draft " + id));
        if (!"DRAFT".equals(draft.getState())) {
            throw new IllegalStateException("draft " + id + " already " + draft.getState());
        }
        draft.setState("REJECTED");
        draft.setFeedback(body == null ? null : body.get("feedback"));
        return drafts.save(draft);
    }

    /** 把已确认草稿写入对应实体的槽位（含负向提示）。当前支持 SHOT.visual_prompt。 */
    private void applyToSlot(PromptDraft draft) {
        if (!"SHOT".equals(draft.getOwnerType()) || !"visual_prompt".equals(draft.getFieldName())) {
            return; // 其余槽位（appearance/description）在参考图生成环节消费
        }
        Shot shot = shots.findById(draft.getOwnerId())
                .orElseThrow(() -> new NotFoundException("shot " + draft.getOwnerId()));
        shot.setVisualPrompt(draft.getContent());
        shot.setNegativePrompt(draft.getNegativePrompt());
        shots.save(shot);
    }

    private Project projectOf(String ownerType, Long ownerId) {
        return switch (ownerType) {
            case "SHOT" -> shots.findById(ownerId)
                    .orElseThrow(() -> new NotFoundException("shot " + ownerId)).getProject();
            case "CHARACTER" -> characters.findById(ownerId)
                    .orElseThrow(() -> new NotFoundException("character " + ownerId)).getProject();
            case "SCENE" -> scenes.findById(ownerId)
                    .orElseThrow(() -> new NotFoundException("scene " + ownerId)).getProject();
            default -> throw new IllegalArgumentException("未知 ownerType: " + ownerType);
        };
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Long num(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(v));
    }
}
