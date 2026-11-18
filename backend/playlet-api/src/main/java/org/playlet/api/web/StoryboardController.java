package org.playlet.api.web;

import org.playlet.api.service.StoryboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 闸门①分镜表 REST（BACKLOG ①）：
 * POST /generate 切镜出整表；GET /table 整表查询；POST /confirm-all 批量确认；
 * POST /shots/{shotId}/prompt 逐行改（成新草稿）；POST /shots 手动添加分镜（用户填参）。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/storyboard")
public class StoryboardController {

    private final StoryboardService storyboard;

    public StoryboardController(StoryboardService storyboard) {
        this.storyboard = storyboard;
    }

    /** 梗概 → 切镜 → 逐镜出草稿 → 整表返回。已有生成产物的项目拒绝重切（400）。 */
    @PostMapping("/generate")
    public StoryboardService.GenerateResult generate(@PathVariable Long projectId) {
        return storyboard.generate(projectId);
    }

    /** 分镜表整表（序/时长/景别/运镜/草稿状态）。 */
    @GetMapping("/table")
    public List<StoryboardService.Row> table(@PathVariable Long projectId) {
        return storyboard.table(projectId);
    }

    /** 闸门①批量确认：逐镜确认最新 DRAFT；全确认后项目状态推进 STORYBOARD_CONFIRMED。 */
    @PostMapping("/confirm-all")
    public StoryboardService.ConfirmResult confirmAll(@PathVariable Long projectId) {
        return storyboard.confirmAll(projectId);
    }

    /** 逐行改提示词：修改结果成为新草稿（再确认才写槽）。body: {"content": "..."} */
    @PostMapping("/shots/{shotId}/prompt")
    public StoryboardService.Row editPrompt(@PathVariable Long projectId, @PathVariable Long shotId,
                                            @RequestBody Map<String, String> body) {
        return storyboard.editPrompt(projectId, shotId, body == null ? null : body.get("content"));
    }

    /**
     * 手动添加分镜（参数全部由用户填写，不经 LLM）。
     * 提示词直接写槽位、不产草稿；ord 缺省追加表尾，占用已有序号时后续镜头顺延后移。
     *
     * body: {"ord": 2, "durationSec": 5, "shotSize": "中景", "cameraMove": "固定",
     *        "visualPrompt": "必填", "negativePrompt": "可空", "dialogue": "可空",
     *        "speaker": "角色名，可空", "scene": "场景名，可空"}
     */
    @PostMapping("/shots")
    public StoryboardService.Row addShot(@PathVariable Long projectId,
                                        @RequestBody Map<String, Object> body) {
        return storyboard.addShot(projectId, new StoryboardService.AddShotSpec(
                num(body, "ord"), dbl(body, "durationSec"),
                str(body, "shotSize"), str(body, "cameraMove"),
                str(body, "visualPrompt"), str(body, "negativePrompt"),
                str(body, "dialogue"), str(body, "speaker"), str(body, "scene")));
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Integer num(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) return null;
        if (v instanceof Number n) return n.intValue();
        return Integer.valueOf(String.valueOf(v).trim());
    }

    private static Double dbl(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) return null;
        if (v instanceof Number n) return n.doubleValue();
        return Double.valueOf(String.valueOf(v).trim());
    }
}
