package org.playlet.comfyui;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.playlet.comfyui.backend.video.MiniMaxH3Backend;
import org.playlet.comfyui.client.ComfyClient;
import org.playlet.comfyui.template.WorkflowTemplateLoader;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H3 真实视频生成 spike：提交 minimaxh3/minimax_h3_ti2v 模板，
 * 验证 GPU 生成 → SaveVideo 产物取回整条链路。
 *
 * <p>耗时数分钟（GPU），ComfyUI 不可达时跳过。
 * 首帧用 ComfyUI input 目录已有的 example.png。
 */
class H3VideoSpikeTest {

    private static final String BASE_URL =
            System.getenv().getOrDefault("PLAYLET_COMFYUI_BASE_URL", "http://127.0.0.1:28070");

    @Test
    void generateRealVideo() {
        ComfyClient client = new ComfyClient(BASE_URL, 2000, 900);

        boolean reachable;
        String reachError = null;
        try {
            String body = RestClient.builder().baseUrl(BASE_URL).build()
                    .get().uri("/system_stats").retrieve().body(String.class);
            JsonNode stats = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            reachable = stats != null && stats.has("system");
        } catch (Exception e) {
            reachable = false;
            reachError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        Assumptions.assumeTrue(reachable, "ComfyUI not reachable (" + reachError + ")");

        WorkflowTemplateLoader loader = new WorkflowTemplateLoader();
        JsonNode wf = loader.load("minimaxh3/minimax_h3_ti2v", Map.of(
                "prompt", "STYLE: simple test. A red ball bounces once on a white floor, soft light.",
                "first_frame", "example.png",
                "prefix", "video/spike_h3",
                "aspect_ratio", MiniMaxH3Backend.toTemplateAspectRatio("16:9"),
                "duration", "5",
                "seed", "42"));

        long t0 = System.currentTimeMillis();
        String promptId = client.submit(wf);
        assertNotNull(promptId);
        System.out.println("[h3-spike] submitted promptId=" + promptId);

        JsonNode history = client.awaitCompletion(promptId);
        JsonNode file = client.firstOutputFile(history);
        assertNotNull(file, "output file in history (SaveVideo → images key)");
        String filename = file.path("filename").asText(null);
        assertNotNull(filename);
        long secs = (System.currentTimeMillis() - t0) / 1000;
        System.out.println("[h3-spike] OK output=" + file.path("subfolder").asText("") + "/" + filename
                + " type=" + file.path("type").asText() + " elapsed=" + secs + "s");
        assertTrue(filename.contains("spike_h3"), "prefix honored: " + filename);
    }
}
