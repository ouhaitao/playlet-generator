package org.playlet.comfyui.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复现 minimax_h3_r2v.json 的导入失败：VAELoader 是纯生产节点（无链接型输入），
 * 同时喂 ref2v 的**可选** vae/audio_vae 与 VAEDecode/VAEDecodeAudio 的**必填** vae。
 * 旧剪枝器对必填消费端一律旁路改接，VAELoader 无同类型链接输入 → PruneException，
 * 导致所有含 vae/audio_vae 的 2^k 组合失败。修复后：必填消费端无改接目标时**钉住**源节点，
 * 只断开被关槽位自己的可选链接，源节点去留交给孤儿 GC。
 *
 * <p>节点类型取自真实 ComfyUI /object_info（192.168.5.201:8188）；图结构忠实于该工作流。
 */
class R2vSharedVaeLoaderPruneTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 忠实于 r2v 的图：119/120 VAELoader 既接 ref2v 可选 vae/audio_vae，又接解码链必填 vae。 */
    private ObjectNode graph() throws Exception {
        return (ObjectNode) JSON.readTree("""
                {
                  "92":  {"class_type": "SaveVideo", "inputs": {"video": ["130", 0],
                          "filename_prefix": "video/x", "format": "auto"}},
                  "119": {"class_type": "VAELoader", "inputs": {"vae_name": "video_vae.safetensors"}},
                  "120": {"class_type": "VAELoader", "inputs": {"vae_name": "audio_vae.safetensors"}},
                  "128": {"class_type": "CLIPLoader", "inputs": {"clip_name": "clip.safetensors"}},
                  "144": {"class_type": "MiniMaxH3ReferenceToVideo", "inputs": {
                          "clip": ["128", 0], "prompt": "", "width": 1344, "height": 768,
                          "length": 124, "ref_image_size": "match",
                          "vae": ["119", 0], "audio_vae": ["120", 0]}},
                  "sam": {"class_type": "SamplerStub", "inputs": {
                          "latent_image": ["144", 1], "conditioning": ["144", 0]}},
                  "121": {"class_type": "VAEDecodeAudio", "inputs": {"samples": ["sam", 0], "vae": ["120", 0]}},
                  "122": {"class_type": "VAEDecode", "inputs": {"samples": ["sam", 0], "vae": ["119", 0]}},
                  "130": {"class_type": "CreateVideo", "inputs": {
                          "images": ["122", 0], "audio": ["121", 0], "fps": 24}}
                }
                """);
    }

    private StubNodeTypeResolver types() {
        return new StubNodeTypeResolver(
                Map.of(
                        "VAELoader", Map.of("vae_name", "COMBO"),
                        "CLIPLoader", Map.of("clip_name", "COMBO"),
                        "VAEDecode", Map.of("samples", "LATENT", "vae", "VAE"),
                        "VAEDecodeAudio", Map.of("samples", "LATENT", "vae", "VAE"),
                        "SamplerStub", Map.of("latent_image", "LATENT", "conditioning", "CONDITIONING"),
                        "CreateVideo", Map.of("images", "IMAGE", "fps", "FLOAT"),
                        "SaveVideo", Map.of("video", "VIDEO", "filename_prefix", "STRING", "format", "COMBO"),
                        "MiniMaxH3ReferenceToVideo", Map.of(
                                "clip", "CLIP", "prompt", "STRING", "width", "INT",
                                "height", "INT", "length", "INT", "ref_image_size", "COMBO")),
                Map.of(
                        "CLIPLoader", Map.of("device", "COMBO"),
                        "CreateVideo", Map.of("audio", "AUDIO", "codec", "COMBO"),
                        "MiniMaxH3ReferenceToVideo", Map.of(
                                "vae", "VAE", "audio_vae", "VAE", "ref_images", "COMFY_AUTOGROW_V3")),
                Map.of(
                        "VAELoader", List.of("VAE"),
                        "CLIPLoader", List.of("CLIP"),
                        "VAEDecode", List.of("IMAGE"),
                        "VAEDecodeAudio", List.of("AUDIO"),
                        "SamplerStub", List.of("LATENT"),
                        "CreateVideo", List.of("VIDEO"),
                        "SaveVideo", List.of("VIDEO"),
                        "MiniMaxH3ReferenceToVideo", List.of("CONDITIONING", "LATENT")),
                Map.of("SaveVideo", true));
    }

    @Test
    void extractsExpectedSlots() throws Exception {
        List<WorkflowSlot> slots = SlotExtractor.extract(graph(), types());
        Set<String> names = new HashSet<>();
        for (WorkflowSlot s : slots) {
            names.add(s.name());
        }
        assertEquals(Set.of("audio", "vae", "audio_vae"), names,
                "r2v 应提取 audio(chain)/vae(source)/audio_vae(source) 三个可选链接槽");
        WorkflowSlot vae = slots.stream().filter(s -> s.name().equals("vae")).findFirst().orElseThrow();
        assertEquals("source", vae.sourceKind(), "VAELoader 无链接型输入 → sourceKind=source");
        assertEquals("119", vae.sourceId());
    }

    @Test
    void allCombinationsPruneAndValidateClean() throws Exception {
        ObjectNode wf = graph();
        StubNodeTypeResolver types = types();
        List<WorkflowSlot> slots = SlotExtractor.extract(wf, types);
        assertTrue(slots.size() <= 4, "self-check applies");

        // 全填版本身必须合法
        assertEquals(List.of(), WorkflowValidator.validate(wf, types), "base graph must validate");

        // 复刻 WorkflowImporter 的 2^k 自检：任一组合抛异常或校验失败即视为回归
        for (int mask = 0; mask < (1 << slots.size()); mask++) {
            Set<String> disabled = new HashSet<>();
            for (int i = 0; i < slots.size(); i++) {
                if ((mask & (1 << i)) != 0) {
                    disabled.add(slots.get(i).name());
                }
            }
            ObjectNode pruned = WorkflowPruner.prune(wf, slots, disabled, types); // 不再抛 PruneException
            List<String> problems = WorkflowValidator.validate(pruned, types);
            assertEquals(List.of(), problems, "combination " + disabled + " must validate clean");
        }
    }

    @Test
    void disablingVaePinsSharedLoaderAndDropsOptionalLink() throws Exception {
        ObjectNode wf = graph();
        StubNodeTypeResolver types = types();
        List<WorkflowSlot> slots = SlotExtractor.extract(wf, types);

        ObjectNode pruned = WorkflowPruner.prune(wf, slots, Set.of("vae"), types);
        // 可选链接断开
        assertFalse(pruned.path("144").path("inputs").has("vae"), "ref2v.vae 可选链接应被移除");
        // 共享 VAELoader 被必填解码链钉住，保留
        assertTrue(pruned.has("119"), "119 VAELoader 被 122.vae 必填依赖，必须钉住保留");
        assertEquals("119", pruned.path("122").path("inputs").path("vae").get(0).asText(),
                "122.vae 必填链接保持指向 119");
        // audio_vae 未关，120/121 不受影响
        assertTrue(pruned.has("120") && pruned.has("121"));
    }

    @Test
    void disablingAudioAndAudioVaeGcsAudioLoader() throws Exception {
        ObjectNode wf = graph();
        StubNodeTypeResolver types = types();
        List<WorkflowSlot> slots = SlotExtractor.extract(wf, types);

        // 关 audio（删 VAEDecodeAudio 121）+ audio_vae（120 失去全部消费端 → GC）
        ObjectNode pruned = WorkflowPruner.prune(wf, slots, Set.of("audio", "audio_vae"), types);
        assertFalse(pruned.path("130").path("inputs").has("audio"), "CreateVideo.audio 可选链接移除");
        assertFalse(pruned.has("121"), "121 VAEDecodeAudio 应被删除");
        assertFalse(pruned.has("120"), "120 audio VAELoader 失去消费端，应被孤儿 GC");
        assertFalse(pruned.path("144").path("inputs").has("audio_vae"));
        // 视频侧不受影响
        assertTrue(pruned.has("119") && pruned.has("122"), "视频 VAE 链保持完整");
    }
}
