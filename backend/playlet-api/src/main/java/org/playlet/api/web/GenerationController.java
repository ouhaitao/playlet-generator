package org.playlet.api.web;

import org.playlet.agent.PromptOptimizer;
import org.playlet.api.service.GenerationService;
import org.playlet.domain.entity.ShotVersion;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 生成触发 REST API（图片 + 视频）。
 * body = { "workflow": "已导入工作流名", "params": { "3/inputs/seed": 42, ... } }，
 * params 为 jsonPath → 值（用户在审核台按工作流解析元数据填写；seed 也是其中一个普通参数）。
 *
 * <p>另提供 {@code POST /api/prompt/generate}：审核台「生成提示词」按钮的即席能力
 * （见 {@link PromptOptimizer#generatePromptText}），<b>不与任何业务逻辑关联</b>——
 * 不产草稿、不写槽位、不落库，只把 LLM 输出文本返回给前端展示。
 */
@RestController
@RequestMapping("/api")
public class GenerationController {

    private final GenerationService generation;
    private final PromptOptimizer promptOptimizer;

    public GenerationController(GenerationService generation, PromptOptimizer promptOptimizer) {
        this.generation = generation;
        this.promptOptimizer = promptOptimizer;
    }

    /** 触发镜头图片生成（异步，立即返回 GENERATING 版本）。 */
    @PostMapping("/shots/{shotId}/generate-image")
    public ShotVersion generateImage(@PathVariable Long shotId,
                                     @RequestBody Map<String, Object> body) {
        return generation.generateImage(shotId, workflowOf(body), paramsOf(body));
    }

    /** 触发镜头视频生成（异步，立即返回 GENERATING 版本）。 */
    @PostMapping("/shots/{shotId}/generate-video")
    public ShotVersion generateVideo(@PathVariable Long shotId,
                                     @RequestBody Map<String, Object> body) {
        return generation.generateVideo(shotId, workflowOf(body), paramsOf(body));
    }

    /**
     * 生成提示词（审核台「生成提示词」按钮）。把四个字段构造成一条消息发 LLM，原样返回其输出。
     * <b>纯能力接口，不关联任何业务逻辑</b>：不落库、不产草稿、不写槽位。
     * LLM 不可用/输出为空 → 400 + message。
     *
     * body: { "model": "工作流元信息的模型名", "content": "CLIPTextEncode 输入框内容",
     *         "flag": "提示词标识（正面/负面）", "feedback": "用户反馈意见" }
     */
    @PostMapping("/prompt/generate")
    public Map<String, String> generatePrompt(@RequestBody Map<String, String> body) {
        String model = body == null ? null : body.get("model");
        String content = body == null ? null : body.get("content");
        String flag = body == null ? null : body.get("flag");
        String feedback = body == null ? null : body.get("feedback");
        if (flag == null || flag.isBlank()) {
            throw new IllegalArgumentException("flag 必填（提示词标识：正面/负面）");
        }
        String result = promptOptimizer.generatePromptText(model, content, flag, feedback);
        return Map.of("result", result);
    }

    private static String workflowOf(Map<String, Object> body) {
        Object w = body == null ? null : body.get("workflow");
        return w == null ? null : String.valueOf(w);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paramsOf(Map<String, Object> body) {
        Object p = body == null ? null : body.get("params");
        return p instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
