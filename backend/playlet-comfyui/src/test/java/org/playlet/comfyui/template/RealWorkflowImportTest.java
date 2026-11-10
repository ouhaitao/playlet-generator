package org.playlet.comfyui.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用真实 H3 导出验证提取 + 剪枝 + 校验（需 ComfyUI 在线取 object_info）。
 * 模板路径：src/main/resources/workflows/minimaxh3/minimax_h3_ti2v.json
 */
class RealWorkflowImportTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE_URL =
            System.getenv().getOrDefault("PLAYLET_COMFYUI_BASE_URL", "http://127.0.0.1:28070");

    @Test
    void extractPruneValidateRealH3() throws Exception {
        boolean reachable;
        try {
            String body = RestClient.builder().baseUrl(BASE_URL).build()
                    .get().uri("/system_stats").retrieve().body(String.class);
            reachable = body != null && body.contains("system");
        } catch (Exception e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "ComfyUI not reachable, skipping real-workflow import test");

        NodeTypeResolver types = new ComfyNodeTypeResolver(BASE_URL);
        Path template = Path.of(System.getProperty("template.path",
                "src/main/resources/workflows/minimaxh3/minimax_h3_ti2v.json"));
        Assumptions.assumeTrue(Files.exists(template), "template not found: " + template);

        ObjectNode wf = (ObjectNode) JSON.readTree(Files.readString(template));

        // 1) 全填版自检通过
        List<String> problems = WorkflowValidator.validate(wf, types);
        assertTrue(problems.isEmpty(), "full workflow must validate: " + problems);

        // 2) 提取槽：应含 first_frame（源=LoadImage，source 型）
        List<WorkflowSlot> slots = SlotExtractor.extract(wf, types);
        System.out.println("[import] slots=" + slots.stream().map(WorkflowSlot::name).toList());
        assertTrue(slots.stream().anyMatch(s -> s.name().equals("first_frame")),
                "first_frame slot expected, got: " + slots);

        // 3) 剪掉 first_frame 后：LoadImage 消失、宿主输入消失、其余校验通过
        ObjectNode pruned = WorkflowPruner.prune(wf, slots, Set.of("first_frame"), types);
        WorkflowSlot ff = slots.stream().filter(s -> s.name().equals("first_frame")).findFirst().orElseThrow();
        assertFalse(pruned.has(ff.sourceId()), "LoadImage source must be pruned");
        assertFalse(pruned.path(ff.hostId()).path("inputs").has("first_frame"),
                "host input must be removed");
        List<String> prunedProblems = WorkflowValidator.validate(pruned, types);
        assertTrue(prunedProblems.isEmpty(), "pruned workflow must validate: " + prunedProblems);

        // 4) 剪掉 audio（chain 型）后：VAEDecodeAudio 与其音频 VAE 被删/旁路，视频链完好
        WorkflowSlot audio = slots.stream().filter(s -> s.name().equals("audio")).findFirst().orElse(null);
        if (audio != null) {
            ObjectNode silent = WorkflowPruner.prune(wf, slots, Set.of("audio"), types);
            assertFalse(silent.has(audio.sourceId()), "VAEDecodeAudio must be pruned");
            assertFalse(silent.path(audio.hostId()).path("inputs").has("audio"),
                    "CreateVideo.audio input must be removed");
            // 视频主链仍在：视频 VAE 解码与 SaveVideo 存活
            assertTrue(silent.has("105:10"), "video VAEDecode must survive");
            assertTrue(silent.has("92"), "SaveVideo must survive");
            List<String> silentProblems = WorkflowValidator.validate(silent, types);
            assertTrue(silentProblems.isEmpty(), "silent workflow must validate: " + silentProblems);
        }
    }
}
