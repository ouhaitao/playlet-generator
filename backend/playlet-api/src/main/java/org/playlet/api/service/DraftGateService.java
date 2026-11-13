package org.playlet.api.service;

import org.playlet.agent.PromptOptimizer;
import org.playlet.api.web.NotFoundException;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 提示词确认门服务（DESIGN.md §6.3）：optimize / confirm / reject 的事务边界。
 *
 * <p>从 DraftController 抽出成服务，使闸门①（StoryboardService 批量确认）与
 * REST 单条操作走同一套逻辑：确认 = 草稿 CONFIRMED + 写入槽位（visual_prompt/negative）。
 */
@Service
public class DraftGateService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PromptDraftRepository drafts;
    private final ShotRepository shots;
    private final CharacterRefRepository characters;
    private final SceneRepository scenes;
    private final PromptOptimizer optimizer;

    public DraftGateService(PromptDraftRepository drafts, ShotRepository shots,
                            CharacterRefRepository characters, SceneRepository scenes,
                            PromptOptimizer optimizer) {
        this.drafts = drafts;
        this.shots = shots;
        this.characters = characters;
        this.scenes = scenes;
        this.optimizer = optimizer;
    }

    /** 触发 agent 优化，产出新草稿（旧未决草稿作废）。feedback 可空 = 首轮。 */
    @Transactional
    public PromptDraft optimize(String ownerType, Long ownerId, String fieldName, String feedback) {
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

    /** 用户手改文本直接成新草稿（不擅自重优化；闸门①逐行改用）。 */
    @Transactional
    public PromptDraft manualShotDraft(Long shotId, String content) {
        Shot shot = shots.findById(shotId)
                .orElseThrow(() -> new NotFoundException("shot " + shotId));
        return optimizer.manualShotDraft(shot, content);
    }

    /** 确认草稿：状态置 CONFIRMED，并把内容写入对应槽位。重复确认抛 400。 */
    @Transactional
    public PromptDraft confirm(Long draftId) {
        PromptDraft draft = drafts.findById(draftId)
                .orElseThrow(() -> new NotFoundException("draft " + draftId));
        if (!"DRAFT".equals(draft.getState())) {
            throw new IllegalStateException("draft " + draftId + " already " + draft.getState());
        }
        draft.setState("CONFIRMED");
        draft.setConfirmedAt(LocalDateTime.now().format(TS));
        applyToSlot(draft);
        return drafts.save(draft);
    }

    /** 驳回草稿：状态置 REJECTED，记录意见（agent 据此重新优化）。 */
    @Transactional
    public PromptDraft reject(Long draftId, String feedback) {
        PromptDraft draft = drafts.findById(draftId)
                .orElseThrow(() -> new NotFoundException("draft " + draftId));
        if (!"DRAFT".equals(draft.getState())) {
            throw new IllegalStateException("draft " + draftId + " already " + draft.getState());
        }
        draft.setState("REJECTED");
        draft.setFeedback(feedback);
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
}
