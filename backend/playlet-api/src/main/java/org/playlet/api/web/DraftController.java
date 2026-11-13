package org.playlet.api.web;

import org.playlet.api.service.DraftGateService;
import org.playlet.domain.entity.PromptDraft;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 提示词草稿确认门（DESIGN.md §6.3）REST 层。
 * 草稿（DRAFT）→ 用户确认（CONFIRMED，写入槽位）/ 驳回（REJECTED，带意见回 agent）。
 * 未确认的草稿不进入生成链路。事务逻辑在 {@link DraftGateService}（闸门①批量确认共用）。
 */
@RestController
@RequestMapping("/api/drafts")
public class DraftController {

    private final DraftGateService gate;
    private final org.playlet.domain.repo.PromptDraftRepository drafts;

    public DraftController(DraftGateService gate, org.playlet.domain.repo.PromptDraftRepository drafts) {
        this.gate = gate;
        this.drafts = drafts;
    }

    @GetMapping
    public List<PromptDraft> list(@RequestParam String ownerType, @RequestParam Long ownerId) {
        return drafts.findByOwnerTypeAndOwnerIdOrderByVersionNoDesc(ownerType, ownerId);
    }

    /**
     * 触发 agent 优化，产出新草稿（旧未决草稿作废）。
     * body: {"ownerType": "SHOT", "ownerId": 1, "fieldName": "visual_prompt", "feedback": "可空"}
     */
    @PostMapping("/optimize")
    public PromptDraft optimize(@RequestBody Map<String, Object> body) {
        return gate.optimize(str(body, "ownerType"), num(body, "ownerId"),
                str(body, "fieldName"), str(body, "feedback"));
    }

    /**
     * 用户手改文本直接成新草稿（§6.3：修改结果同样先成为草稿、再确认，不擅自重优化）。
     * body: {"shotId": 1, "content": "改后的提示词"}
     */
    @PostMapping("/manual")
    public PromptDraft manual(@RequestBody Map<String, Object> body) {
        return gate.manualShotDraft(num(body, "shotId"), str(body, "content"));
    }

    /** 确认草稿：状态置 CONFIRMED，并把内容写入对应槽位。 */
    @PostMapping("/{id}/confirm")
    public PromptDraft confirm(@PathVariable Long id) {
        return gate.confirm(id);
    }

    /** 驳回草稿：状态置 REJECTED，记录意见（agent 据此重新优化）。 */
    @PostMapping("/{id}/reject")
    public PromptDraft reject(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return gate.reject(id, body == null ? null : body.get("feedback"));
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
