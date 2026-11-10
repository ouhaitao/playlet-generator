package org.playlet.comfyui.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 剪枝器单测：合成图覆盖
 * ① 源节点删除（LoadImage 挂 first_frame）
 * ② 链节点旁路（LoraLoader 挂 lora，MODEL/CLIP 按类型改接）
 * ③ 孤儿 GC（LoadImage 上游 ImageScale 传递性删除）
 * ④ 旁路歧义拒绝
 */
class WorkflowPrunerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private StubNodeTypeResolver types;

    /**
     * 合成图：
     * unet(UNETLoader) ─model─▶ lora(LoraLoader) ─model─▶ sampler
     * clip(CLIPLoader) ─clip─▶ lora            ─clip─▶ sampler
     * scale(ImageScale) ─image─▶ load(LoadImage) ─image─▶ host.first_frame(optional)
     * load2(LoadImage) ─image─▶ host.lora_image(optional)   [source 型，无上游]
     * host(Host) required: model, clip ; optional: first_frame, lora_image
     * sampler(Sampler) required: model, clip ; SaveVideo 输出节点消费 sampler
     */
    private ObjectNode graph() throws Exception {
        String json = """
                {
                  "unet": {"class_type": "UNETLoader", "inputs": {"unet_name": "m.safetensors"}},
                  "clip": {"class_type": "CLIPLoader", "inputs": {"clip_name": "c.safetensors"}},
                  "lora": {"class_type": "LoraLoader", "inputs": {
                      "lora_name": "l.safetensors", "strength_model": 1.0, "strength_clip": 1.0,
                      "model": ["unet", 0], "clip": ["clip", 0]}},
                  "scale": {"class_type": "ImageScale", "inputs": {"image": ["load", 0], "width": 512}},
                  "load": {"class_type": "LoadImage", "inputs": {"image": "a.png"}},
                  "load2": {"class_type": "LoadImage", "inputs": {"image": "b.png"}},
                  "host": {"class_type": "Host", "inputs": {
                      "model": ["lora", 0], "clip": ["lora", 1],
                      "first_frame": ["scale", 0], "lora_image": ["load2", 0]}},
                  "save": {"class_type": "SaveVideo", "inputs": {"video": ["host", 0]}}
                }
                """;
        return (ObjectNode) JSON.readTree(json);
    }

    @BeforeEach
    void setUp() {
        types = new StubNodeTypeResolver(
                Map.of(
                        "UNETLoader", Map.of("unet_name", "COMBO"),
                        "CLIPLoader", Map.of("clip_name", "COMBO"),
                        "LoraLoader", Map.of("lora_name", "COMBO", "model", "MODEL", "clip", "CLIP"),
                        "ImageScale", Map.of("image", "IMAGE", "width", "INT"),
                        "LoadImage", Map.of("image", "COMBO"),
                        "Host", Map.of("model", "MODEL", "clip", "CLIP"),
                        "SaveVideo", Map.of("video", "VIDEO")),
                Map.of(
                        "Host", Map.of("first_frame", "IMAGE", "lora_image", "IMAGE"),
                        "LoraLoader", Map.of("strength_model", "FLOAT", "strength_clip", "FLOAT")),
                Map.of(
                        "UNETLoader", List.of("MODEL"),
                        "CLIPLoader", List.of("CLIP"),
                        "LoraLoader", List.of("MODEL", "CLIP"),
                        "ImageScale", List.of("IMAGE"),
                        "LoadImage", List.of("IMAGE", "MASK"),
                        "Host", List.of("VIDEO"),
                        "SaveVideo", List.of()),
                Map.of("SaveVideo", true));
    }

    private List<WorkflowSlot> slots() {
        return List.of(
                new WorkflowSlot("first_frame", "host", "first_frame", "scale", 0, "IMAGE", "chain"),
                new WorkflowSlot("lora_image", "host", "lora_image", "load2", 0, "IMAGE", "source"));
    }

    @Test
    void deletesSourceNodeAndLink() throws Exception {
        ObjectNode pruned = WorkflowPruner.prune(graph(), slots(), Set.of("lora_image"), types);
        assertFalse(pruned.has("load2"), "source LoadImage must be deleted");
        assertFalse(pruned.path("host").path("inputs").has("lora_image"), "link must be removed");
        assertTrue(pruned.has("load"), "unrelated LoadImage (upstream of first_frame) must stay");
    }

    @Test
    void deletesChainUpstreamViaGc() throws Exception {
        // first_frame 的源是 ImageScale（chain：有链接型输入 image→load）
        // 关 first_frame：旁路 scale（IMAGE 出 → image 入=load）后删 scale，
        // 然后 load 失去唯一消费端 → GC 删除
        ObjectNode pruned = WorkflowPruner.prune(graph(), slots(), Set.of("first_frame"), types);
        assertFalse(pruned.has("scale"), "ImageScale must be deleted");
        assertFalse(pruned.has("load"), "orphan LoadImage must be GC'd");
        assertFalse(pruned.path("host").path("inputs").has("first_frame"));
    }

    @Test
    void keepsEverythingWhenNothingDisabled() throws Exception {
        ObjectNode pruned = WorkflowPruner.prune(graph(), slots(), Set.of(), types);
        assertEquals(8, pruned.size(), "no pruning when nothing disabled");
    }

    @Test
    void bypassReroutesByType() throws Exception {
        // 构造 lora 作为可选槽：host.model/clip 连到 lora；关 lora 时旁路
        ObjectNode wf = graph();
        // 把 lora 变成可选链：新增槽 lora_slot，host 的 model/clip 改连 unet/clip 直连前先经 lora
        // 这里直接测 bypass：禁用一个以 lora 为源的槽
        List<WorkflowSlot> withLora = List.of(
                new WorkflowSlot("lora", "host", "model", "lora", 0, "MODEL", "chain"));
        // host.model 连 lora:0；旁路 lora 的 MODEL 出 → lora.model 入 = unet:0
        // 但 host.clip 仍连 lora:1 → 需要第二个槽或同槽多输出；本测试只关 model 侧不合法，
        // 故改用完整 lora 槽（双输出旁路）：
        ObjectNode wf2 = (ObjectNode) JSON.readTree(wf.toString());
        List<WorkflowSlot> loraSlot = List.of(
                new WorkflowSlot("lora", "host", "model", "lora", 0, "MODEL", "chain"));
        // 先手工把 host.clip 也改为经 lora:1（已是），并让 bypass 处理双输出：
        // 我们的槽模型是"一个槽 = 一个宿主输入"，lora 双输出需两个槽同时关。
        List<WorkflowSlot> both = List.of(
                new WorkflowSlot("lora_model", "host", "model", "lora", 0, "MODEL", "chain"),
                new WorkflowSlot("lora_clip", "host", "clip", "lora", 1, "CLIP", "chain"));
        ObjectNode pruned = WorkflowPruner.prune(wf2, both, Set.of("lora_model", "lora_clip"), types);
        assertFalse(pruned.has("lora"), "LoraLoader must be deleted after bypass");
        assertEquals("unet", pruned.path("host").path("inputs").path("model").get(0).asText(),
                "host.model must reroute to unet");
        assertEquals("clip", pruned.path("host").path("inputs").path("clip").get(0).asText(),
                "host.clip must reroute to clip loader");
        assertTrue(pruned.has("unet") && pruned.has("clip"), "upstream loaders must survive GC");
    }

    @Test
    void refusesAmbiguousBypass() throws Exception {
        // 双 MODEL 输入的链节点：旁路歧义必须拒绝
        ObjectNode wf = (ObjectNode) JSON.readTree("""
                {
                  "a": {"class_type": "UNETLoader", "inputs": {"unet_name": "x"}},
                  "b": {"class_type": "UNETLoader", "inputs": {"unet_name": "y"}},
                  "mix": {"class_type": "ModelMix", "inputs": {"m1": ["a", 0], "m2": ["b", 0]}},
                  "host": {"class_type": "Host", "inputs": {"model": ["mix", 0], "clip": ["clipx", 0]}},
                  "clipx": {"class_type": "CLIPLoader", "inputs": {"clip_name": "c"}},
                  "save": {"class_type": "SaveVideo", "inputs": {"video": ["host", 0]}}
                }
                """);
        StubNodeTypeResolver t2 = new StubNodeTypeResolver(
                Map.of("UNETLoader", Map.of("unet_name", "COMBO"),
                        "ModelMix", Map.of("m1", "MODEL", "m2", "MODEL"),
                        "CLIPLoader", Map.of("clip_name", "COMBO"),
                        "Host", Map.of("model", "MODEL", "clip", "CLIP"),
                        "SaveVideo", Map.of("video", "VIDEO")),
                Map.of("Host", Map.of()),
                Map.of("UNETLoader", List.of("MODEL"), "ModelMix", List.of("MODEL"),
                        "CLIPLoader", List.of("CLIP"), "Host", List.of("VIDEO"), "SaveVideo", List.of()),
                Map.of("SaveVideo", true));
        List<WorkflowSlot> slot = List.of(
                new WorkflowSlot("mix", "host", "model", "mix", 0, "MODEL", "chain"));
        assertThrows(WorkflowPruner.PruneException.class,
                () -> WorkflowPruner.prune(wf, slot, Set.of("mix"), t2));
    }
}
