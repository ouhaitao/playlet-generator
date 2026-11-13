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
 * POST /shots/{shotId}/prompt 逐行改（成新草稿）。
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
}
