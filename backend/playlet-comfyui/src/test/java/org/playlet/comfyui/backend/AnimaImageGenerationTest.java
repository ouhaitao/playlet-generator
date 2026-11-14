package org.playlet.comfyui.backend;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.playlet.comfyui.backend.image.AnimaImageBackend;
import org.playlet.comfyui.client.ComfyClient;
import org.playlet.comfyui.template.ComfyNodeTypeResolver;
import org.playlet.comfyui.template.WorkflowTemplateLoader;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * anima 真实文生图验证（本地有模型）：经 SdImageBackend 提交管线
 * （loadRaw → substitute → foldConstantSwitches → submit）产出图像。
 * lora=false（本地无 LoRA 文件）验证折叠后不触发 LoRA 校验。
 */
class AnimaImageGenerationTest {

    private static final String BASE_URL =
            System.getenv().getOrDefault("PLAYLET_COMFYUI_BASE_URL", "http://127.0.0.1:28070");

    @Test
    void generateRealImage() {
        boolean reachable;
        try {
            String body = RestClient.builder().baseUrl(BASE_URL).build()
                    .get().uri("/system_stats").retrieve().body(String.class);
            reachable = body != null && body.contains("system");
        } catch (Exception e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "ComfyUI not reachable, skipping");

        AnimaImageBackend backend = new AnimaImageBackend(
                new ComfyClient(BASE_URL, 1500, 600),
                new WorkflowTemplateLoader(),
                new ComfyNodeTypeResolver(BASE_URL));

        GenerationRequest req = new GenerationRequest(
                "FIRST_FRAME",
                "a red apple on a wooden table, soft window light",
                null, null, 512, 768, 42L,
                null, null, java.util.List.of(), null,
                Map.of("negative", "bad quality", "steps", "12", "cfg", "5"));

        long t0 = System.currentTimeMillis();
        String promptId = backend.submit(req);
        assertNotNull(promptId);
        GenerationResult res = backend.awaitResult(promptId);
        long secs = (System.currentTimeMillis() - t0) / 1000;
        System.out.println("[anima] OK output=" + res.subfolder() + "/" + res.filename()
                + " elapsed=" + secs + "s");
        String full = (res.subfolder() == null || res.subfolder().isEmpty())
                ? res.filename() : res.subfolder() + "/" + res.filename();
        assertTrue(full.startsWith("image/anima"), "prefix honored: " + full);
    }
}
