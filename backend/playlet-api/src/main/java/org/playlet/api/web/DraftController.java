package org.playlet.api.web;

import org.playlet.domain.entity.PromptDraft;
import org.playlet.domain.entity.Shot;
import org.playlet.domain.repo.PromptDraftRepository;
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
 */
@RestController
@RequestMapping("/api/drafts")
public class DraftController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PromptDraftRepository drafts;
    private final ShotRepository shots;

    public DraftController(PromptDraftRepository drafts, ShotRepository shots) {
        this.drafts = drafts;
        this.shots = shots;
    }

    @GetMapping
    public List<PromptDraft> list(@RequestParam String ownerType, @RequestParam Long ownerId) {
        return drafts.findByOwnerTypeAndOwnerIdOrderByVersionNoDesc(ownerType, ownerId);
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

    /** 把已确认草稿写入对应实体的槽位。当前支持 SHOT.visual_prompt。 */
    private void applyToSlot(PromptDraft draft) {
        if (!"SHOT".equals(draft.getOwnerType()) || !"visual_prompt".equals(draft.getFieldName())) {
            return; // 其余槽位（appearance/description）在参考图生成环节消费
        }
        Shot shot = shots.findById(draft.getOwnerId())
                .orElseThrow(() -> new NotFoundException("shot " + draft.getOwnerId()));
        shot.setVisualPrompt(draft.getContent());
        shots.save(shot);
    }
}
