package org.playlet.api.web;

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
 */
@RestController
@RequestMapping("/api")
public class GenerationController {

    private final GenerationService generation;

    public GenerationController(GenerationService generation) {
        this.generation = generation;
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
