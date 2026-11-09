package org.playlet.comfyui;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.playlet.comfyui.backend.MiniMaxH3Backend;
import org.playlet.comfyui.template.WorkflowTemplateLoader;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H3 模板占位符替换单测：验证字符串/数字占位符都正确落地，
 * 且不触碰 ComfyUI（不提交、不占 GPU）。
 */
class H3TemplateTest {

    @Test
    void substitutesTypedPlaceholders() {
        WorkflowTemplateLoader loader = new WorkflowTemplateLoader();
        JsonNode wf = loader.load("minimaxh3/minimax_h3_ti2v", Map.of(
                "prompt", "a test prompt",
                "first_frame", "example.png",
                "prefix", "video/playlet_h3",
                "aspect_ratio", MiniMaxH3Backend.toTemplateAspectRatio("9:16"),
                "duration", "5",
                "seed", "12345"));

        String raw = wf.toString();
        assertFalse(raw.contains("{{"), "placeholders must be substituted: " + raw);

        // 字符串占位符
        assertEquals("a test prompt", wf.path("105:104").path("inputs").path("prompt").asText());
        assertEquals("example.png", wf.path("119").path("inputs").path("image").asText());
        assertEquals("9:16 (Portrait Widescreen)",
                wf.path("115").path("inputs").path("aspect_ratio").asText());

        // 数字占位符：duration 必须是 JSON number（PrimitiveFloat.value）
        JsonNode dur = wf.path("105:111").path("inputs").path("value");
        assertTrue(dur.isNumber(), "duration must be numeric, got: " + dur);
        assertEquals(5L, dur.asLong());

        // 数字占位符：seed 必须是 JSON number（RandomNoise.noise_seed）
        JsonNode seed = wf.path("105:15").path("inputs").path("noise_seed");
        assertTrue(seed.isNumber(), "seed must be numeric, got: " + seed);
        assertEquals(12345L, seed.asLong());
    }

    @Test
    void aspectRatioMapping() {
        assertEquals("1:1 (Square)", MiniMaxH3Backend.toTemplateAspectRatio("1:1"));
        assertEquals("16:9 (Widescreen)", MiniMaxH3Backend.toTemplateAspectRatio("16:9"));
        assertEquals("9:16 (Portrait Widescreen)", MiniMaxH3Backend.toTemplateAspectRatio(null));
    }

    @Test
    void durationSnap() {
        // 5s → 125 帧? 网格 17k+5: k=7 → 124 帧 ≈ 5.1667s
        assertEquals((17 * 7 + 5) / 24.0, MiniMaxH3Backend.snapDuration(5.0), 1e-9);
    }
}
