package org.playlet.comfyui;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.playlet.comfyui.client.ComfyClient;
import org.playlet.comfyui.template.WorkflowTemplateLoader;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ComfyUI 集成 spike（DESIGN.md §11 首个 spike）：
 * 验证"提交 API 工作流 → 轮询 history → 取回产物"整条链路，
 * 同时验证 WorkflowTemplateLoader 的 {{占位符}} 替换。
 *
 * <p>使用 CPU-only 的 LoadImage→SaveImage 轻量工作流，秒级完成、不占 GPU。
 * ComfyUI 不可达时跳过（assumeTrue），不使构建失败。
 */
class ComfyClientSpikeTest {

    private static final String BASE_URL =
            System.getenv().getOrDefault("PLAYLET_COMFYUI_BASE_URL", "http://127.0.0.1:28070");

    @Test
    void submitPollAndFetchOutput() {
        ComfyClient client = new ComfyClient(BASE_URL, 800, 120);

        // 可达性前置：不可达则跳过（异常信息写入 skip 消息，便于定位）
        String reachError = null;
        try {
            String body = RestClient.builder().baseUrl(BASE_URL).build()
                    .get().uri("/system_stats").retrieve().body(String.class);
            JsonNode stats = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            Assumptions.assumeTrue(stats != null && stats.has("system"),
                    "unexpected /system_stats body: " + body);
        } catch (Exception e) {
            reachError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        Assumptions.assumeTrue(reachError == null,
                "ComfyUI not reachable at " + BASE_URL + " (" + reachError + "), skipping spike");

        // 模板 + 占位符替换
        WorkflowTemplateLoader loader = new WorkflowTemplateLoader();
        JsonNode workflow = loader.load("spike_load_save", Map.of(
                "image", "example.png",
                "prefix", "spike_test"));

        // 占位符确实被替换
        String raw = workflow.toString();
        assertTrue(!raw.contains("{{"), "placeholders must be substituted, got: " + raw);

        // 提交 → 轮询 → 取产物
        String promptId = client.submit(workflow);
        assertNotNull(promptId, "prompt_id");

        JsonNode history = client.awaitCompletion(promptId);
        assertNotNull(history, "history entry");

        JsonNode file = client.firstOutputFile(history);
        assertNotNull(file, "output file in history");
        String filename = file.path("filename").asText(null);
        assertNotNull(filename, "filename");
        assertTrue(filename.startsWith("spike_test"), "output prefix honored: " + filename);

        System.out.println("[spike] OK promptId=" + promptId + " output=" + filename);
    }
}
