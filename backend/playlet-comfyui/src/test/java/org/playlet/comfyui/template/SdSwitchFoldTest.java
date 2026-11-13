package org.playlet.comfyui.template;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SD 模板（anima）开关折叠集成测试：
 * lora=false → 折叠后 LoraLoaderModelOnly 被 GC（本地无 LoRA 文件也能提交校验）；
 * lora=true  → LoRA 节点保留（本地无文件时 ComfyUI 提交校验会拒绝，属预期 fail-loud）。
 */
class SdSwitchFoldTest {

    private static final String BASE_URL =
            System.getenv().getOrDefault("PLAYLET_COMFYUI_BASE_URL", "http://127.0.0.1:28070");

    private static Map<String, String> baseValues() {
        return Map.ofEntries(
                Map.entry("prompt", "a test girl"),
                Map.entry("negative", "bad"),
                Map.entry("prefix", "image/anima"),
                Map.entry("img2img", "false"),
                Map.entry("swap", "false"),
                Map.entry("width", "832"),
                Map.entry("height", "1216"),
                Map.entry("seed", "7"),
                Map.entry("steps", "20"),
                Map.entry("cfg", "5"));
    }

    private NodeTypeResolver resolver() {
        boolean reachable;
        try {
            String body = RestClient.builder().baseUrl(BASE_URL).build()
                    .get().uri("/system_stats").retrieve().body(String.class);
            reachable = body != null && body.contains("system");
        } catch (Exception e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "ComfyUI not reachable, skipping");
        return new ComfyNodeTypeResolver(BASE_URL);
    }

    @Test
    void foldsLoraOffOnRealAnimaTemplate() {
        NodeTypeResolver types = resolver();
        WorkflowTemplateLoader loader = new WorkflowTemplateLoader();

        Map<String, String> values = new java.util.HashMap<>(baseValues());
        values.put("lora", "false");

        ObjectNode wf = loader.loadRaw("anima/anima");
        loader.substitute(wf, values);
        WorkflowPruner.foldConstantSwitches(wf, types);

        assertFalse(wf.toString().contains("{{"), "no leftover placeholders");
        assertFalse(wf.has("30"), "LoraLoaderModelOnly must be GC'd when lora=false");
        assertFalse(wf.has("31"), "lora switch removed");
        assertFalse(wf.has("23"), "img2img switch removed");
        assertTrue(wf.has("32"), "EmptyLatentImage (t2i branch) survives");
        assertFalse(wf.has("22"), "LoadImage (img2i branch) GC'd when img2img=false");
        assertTrue(wf.has("3"), "KSampler survives");
        assertTrue(wf.has("9"), "SaveImage survives");

        var problems = WorkflowValidator.validate(wf, types);
        assertTrue(problems.isEmpty(), "folded t2i workflow must validate: " + problems);
    }

    @Test
    void keepsLoraWhenOn() {
        NodeTypeResolver types = resolver();
        WorkflowTemplateLoader loader = new WorkflowTemplateLoader();

        Map<String, String> values = new java.util.HashMap<>(baseValues());
        values.put("lora", "true");
        values.put("lora_name", "some_lora.safetensors");
        values.put("lora_strength", "0.8");

        ObjectNode wf = loader.loadRaw("anima/anima");
        loader.substitute(wf, values);
        WorkflowPruner.foldConstantSwitches(wf, types);

        assertTrue(wf.has("30"), "LoRA node must survive when lora=true");
        assertFalse(wf.has("31"), "switch still removed after fold");
        // 结构校验不查 COMBO 取值；LoRA 文件存在性由 ComfyUI 提交校验负责（fail-loud）
        var problems = WorkflowValidator.validate(wf, types);
        assertTrue(problems.stream().noneMatch(p -> p.contains("lora")),
                "structural validation must not complain about lora: " + problems);
    }
}
