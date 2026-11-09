package org.playlet.api.web;

import org.playlet.api.service.GenerationService;
import org.playlet.domain.entity.ShotVersion;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 生成触发 REST API。 */
@RestController
@RequestMapping("/api")
public class GenerationController {

    private final GenerationService generation;

    public GenerationController(GenerationService generation) {
        this.generation = generation;
    }

    /** 触发镜头视频生成（M1 同步阻塞；进度推送后续接 WebSocket）。 */
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
