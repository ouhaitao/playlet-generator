package org.playlet.api.web;

import org.playlet.api.service.GenerationService;
import org.playlet.domain.entity.ShotVersion;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 生成触发 REST API（首帧图 + 视频）。 */
@RestController
@RequestMapping("/api")
public class GenerationController {

    private final GenerationService generation;

    public GenerationController(GenerationService generation) {
        this.generation = generation;
    }

    /** 触发镜头首帧图生成（异步，立即返回 GENERATING 版本）。body 可选：seed、backend。 */
    @PostMapping("/shots/{shotId}/generate-first-frame")
    public ShotVersion generateFirstFrame(@PathVariable Long shotId,
                                          @RequestBody(required = false) Map<String, Object> body) {
        Long seed = null;
        String backend = null;
        if (body != null && body.get("seed") != null) {
            seed = Long.valueOf(String.valueOf(body.get("seed")));
        }
        if (body != null && body.get("backend") != null) {
            backend = String.valueOf(body.get("backend"));
        }
        return generation.generateFirstFrame(shotId, seed, backend);
    }

    /** 触发镜头视频生成（异步，立即返回 GENERATING 版本）。body 可选：seed。 */
    @PostMapping("/shots/{shotId}/generate-video")
    public ShotVersion generateVideo(@PathVariable Long shotId,
                                     @RequestBody(required = false) Map<String, Object> body) {
        Long seed = null;
        if (body != null && body.get("seed") != null) {
            seed = Long.valueOf(String.valueOf(body.get("seed")));
        }
        return generation.generateVideo(shotId, seed);
    }
}
