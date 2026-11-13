package org.playlet.comfyui.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开关常量折叠单测（anima/illustrious 的 LoRA 与 img2img 开关机制）。
 * 折叠后：消费端直连选中分支；开关节点删除；未选中分支的孤立节点（如关闭的 LoRA）被 GC。
 */
class SwitchFoldTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private StubNodeTypeResolver types;

    /**
     * 合成图（anima 拓扑简化）：
     * unet ─model─▶ lora(LoraLoaderModelOnly)
     * sw_lora(ComfySwitchNode) switch=?, on_false=unet:0, on_true=lora:0 ─▶ sampler.model
     * img(LoadImage) ─▶ enc(VAEEncode) ─▶ sw_img.on_true ; empty(EmptyLatent) ─▶ sw_img.on_false
     * sw_img ─▶ sampler.latent_image
     * sampler(KSampler) ─▶ save(SaveImage, output node)
     */
    private ObjectNode graph(String loraSwitch, String imgSwitch) throws Exception {
        String json = """
                {
                  "unet": {"class_type": "UNETLoader", "inputs": {"unet_name": "a.safetensors"}},
                  "lora": {"class_type": "LoraLoaderModelOnly", "inputs": {
                      "lora_name": "missing.safetensors", "strength_model": 1, "model": ["unet", 0]}},
                  "sw_lora": {"class_type": "ComfySwitchNode", "inputs": {
                      "switch": %s, "on_false": ["unet", 0], "on_true": ["lora", 0]}},
                  "img": {"class_type": "LoadImage", "inputs": {"image": "x.png"}},
                  "enc": {"class_type": "VAEEncode", "inputs": {"pixels": ["img", 0]}},
                  "empty": {"class_type": "EmptyLatentImage", "inputs": {"width": 512, "height": 768}},
                  "sw_img": {"class_type": "ComfySwitchNode", "inputs": {
                      "switch": %s, "on_false": ["empty", 0], "on_true": ["enc", 0]}},
                  "sampler": {"class_type": "KSampler", "inputs": {
                      "model": ["sw_lora", 0], "latent_image": ["sw_img", 0]}},
                  "save": {"class_type": "SaveImage", "inputs": {"images": ["sampler", 0]}}
                }
                """.formatted(loraSwitch, imgSwitch);
        return (ObjectNode) JSON.readTree(json);
    }

    @BeforeEach
    void setUp() {
        types = new StubNodeTypeResolver(
                Map.of(
                        "UNETLoader", Map.of("unet_name", "COMBO"),
                        "LoraLoaderModelOnly", Map.of("lora_name", "COMBO", "model", "MODEL"),
                        "ComfySwitchNode", Map.of("switch", "BOOLEAN", "on_false", "LATENT", "on_true", "LATENT"),
                        "LoadImage", Map.of("image", "COMBO"),
                        "VAEEncode", Map.of("pixels", "IMAGE"),
                        "EmptyLatentImage", Map.of("width", "INT", "height", "INT"),
                        "KSampler", Map.of("model", "MODEL", "latent_image", "LATENT"),
                        "SaveImage", Map.of("images", "IMAGE")),
                Map.of(),
                Map.of(
                        "UNETLoader", List.of("MODEL"),
                        "LoraLoaderModelOnly", List.of("MODEL"),
                        "ComfySwitchNode", List.of("LATENT"),
                        "LoadImage", List.of("IMAGE", "MASK"),
                        "VAEEncode", List.of("LATENT"),
                        "EmptyLatentImage", List.of("LATENT"),
                        "KSampler", List.of("IMAGE"),
                        "SaveImage", List.of()),
                Map.of("SaveImage", true));
    }

    @Test
    void foldsLoraOffAndGcsUnreachableLora() throws Exception {
        ObjectNode wf = graph("false", "false");
        WorkflowPruner.foldConstantSwitches(wf, types);
        assertFalse(wf.has("sw_lora"), "switch removed");
        assertFalse(wf.has("sw_img"), "switch removed");
        assertFalse(wf.has("lora"), "unselected LoRA must be GC'd (its lora_name never validated)");
        assertFalse(wf.has("img"), "unselected img2img branch GC'd");
        assertFalse(wf.has("enc"), "unselected VAEEncode GC'd");
        assertEquals("unet", wf.path("sampler").path("inputs").path("model").get(0).asText(),
                "sampler.model rerouted to unet");
        assertEquals("empty", wf.path("sampler").path("inputs").path("latent_image").get(0).asText(),
                "sampler.latent rerouted to empty latent");
        assertTrue(wf.has("unet") && wf.has("save"), "core chain survives");
    }

    @Test
    void foldsLoraOnAndKeepsLora() throws Exception {
        ObjectNode wf = graph("true", "true");
        WorkflowPruner.foldConstantSwitches(wf, types);
        assertFalse(wf.has("sw_lora"));
        assertTrue(wf.has("lora"), "selected LoRA must survive");
        assertEquals("lora", wf.path("sampler").path("inputs").path("model").get(0).asText());
        assertEquals("enc", wf.path("sampler").path("inputs").path("latent_image").get(0).asText());
        assertFalse(wf.has("empty"), "unselected empty latent GC'd");
    }

    @Test
    void leavesNonConstantSwitchAlone() throws Exception {
        // switch 仍是链接（非常量）→ 不折叠
        ObjectNode wf = graph("[\"boolsrc\", 0]", "false");
        WorkflowPruner.foldConstantSwitches(wf, types);
        assertTrue(wf.has("sw_lora"), "non-constant switch must not be folded");
        assertFalse(wf.has("sw_img"), "constant switch still folded");
    }
}
