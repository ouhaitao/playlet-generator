package org.playlet.api.web;

import org.playlet.agent.PromptTranslator;
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
 *
 * <p>另提供 {@code POST /translate}：审核台 CLIPTextEncode 输入框的中英互译（能力见
 * {@link PromptTranslator}），结果<b>仅供前端展示、不落库</b>。
 */
@RestController
@RequestMapping("/api/drafts")
public class DraftController {

    private final DraftGateService gate;
    private final org.playlet.domain.repo.PromptDraftRepository drafts;
    private final PromptTranslator translator;

    public DraftController(DraftGateService gate, org.playlet.domain.repo.PromptDraftRepository drafts,
                           PromptTranslator translator) {
        this.gate = gate;
        this.drafts = drafts;
        this.translator = translator;
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
    public PromptDraft optimize(@RequestBody PromptDraft promptDraft) {
        return gate.optimize(promptDraft.getOwnerType(),
            promptDraft.getOwnerId(),
            promptDraft.getFieldName(),
            promptDraft.getFeedback());
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

    /**
     * 翻译提示词（审核台 CLIPTextEncode 输入框用）。能力由 {@link PromptTranslator} 提供：
     * target=EN → {@code toEnglish}，target=ZH → {@code toChinese}。
     *
     * <p>无需翻译的情况（空白、已是目标语言）原样返回；LLM 不可用 → 400 + message。
     * <b>结果不落库</b>，仅供前端展示（前端按输入内容自行缓存复用）。
     *
     * body: {"text": "待翻译文本", "target": "EN|ZH"}
     */
    @PostMapping("/translate")
    public Map<String, String> translate(@RequestBody Map<String, String> body) {
        String text = body == null ? null : body.get("text");
        String target = body == null ? null : body.get("target");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text 必填（待翻译文本为空）");
        }
        String translated;
        if ("ZH".equalsIgnoreCase(target)) {
            translated = translator.toChinese(text);
        } else if (target == null || target.isBlank() || "EN".equalsIgnoreCase(target)) {
            translated = translator.toEnglish(text);
        } else {
            throw new IllegalArgumentException("target 仅支持 EN（译英）/ ZH（译中），收到: " + target);
        }
        return Map.of("translated", translated == null ? "" : translated);
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
