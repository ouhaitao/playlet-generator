package org.playlet.comfyui.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作流库服务单测（离线，Stub 节点类型 + @TempDir 存储）：
 * 导入成功落盘/槽提取、画布格式拒收、校验失败回滚、列表 user/builtin、详情、删除权限。
 */
class WorkflowLibraryServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 合成图：unet→host(+可选 first_frame/ref)→save；LoadImage 会被参数化为 {{first_frame}}。 */
    private static final String WF = """
            {
              "unet": {"class_type": "UNETLoader", "inputs": {"unet_name": "m.safetensors"}},
              "load": {"class_type": "LoadImage", "inputs": {"image": "a.png"}},
              "load2": {"class_type": "LoadImage", "inputs": {"image": "b.png"}},
              "host": {"class_type": "Host", "inputs": {
                  "model": ["unet", 0], "first_frame": ["load", 0], "ref": ["load2", 0]}},
              "save": {"class_type": "SaveImage", "inputs": {"images": ["host", 0]}}
            }
            """;

    private static final String CANVAS = """
            {"last_node_id": 3, "last_link_id": 2, "nodes": [], "links": []}
            """;

    private NodeTypeResolver stubTypes() {
        return new StubNodeTypeResolver(
                Map.of(
                        "UNETLoader", Map.of("unet_name", "COMBO"),
                        "LoadImage", Map.of("image", "COMBO"),
                        "Host", Map.of("model", "MODEL"),
                        "SaveImage", Map.of("images", "IMAGE")),
                Map.of("Host", Map.of("first_frame", "IMAGE", "ref", "IMAGE")),
                Map.of(
                        "UNETLoader", List.of("MODEL"),
                        "LoadImage", List.of("IMAGE", "MASK"),
                        "Host", List.of("IMAGE"),
                        "SaveImage", List.of()),
                Map.of("SaveImage", true));
    }

    private WorkflowLibraryService service(Path storage) {
        WorkflowTemplateLoader loader = new WorkflowTemplateLoader(storage.toString());
        return new WorkflowLibraryService(new WorkflowImporter(stubTypes()), loader, stubTypes());
    }

    @Test
    void importWritesParameterizedJsonAndSidecar(@TempDir Path storage) throws Exception {
        WorkflowLibraryService svc = service(storage);
        var report = svc.importWorkflow("mypack/flow1", WF.getBytes(StandardCharsets.UTF_8));

        assertTrue(report.ok(), "import must succeed: " + report.problems());
        assertEquals("mypack/flow1", report.name());
        // 槽：first_frame + ref（LoadImage 源，source 型）
        assertEquals(2, report.slots().size(), "slots: " + report.slots());
        assertTrue(report.slots().stream().anyMatch(s -> s.name().equals("first_frame")));
        assertTrue(report.slots().stream().anyMatch(s -> s.name().equals("ref")));
        // 自动参数化：LoadImage.image → 占位符，SaveImage.filename_prefix 不存在则跳过
        assertTrue(report.substitutions().stream().anyMatch(s -> s.contains("{{first_frame}}")),
                "subs: " + report.substitutions());

        // 落盘：参数化 JSON + sidecar 都在存储目录
        Path wfFile = storage.resolve("workflows/mypack/flow1.json");
        Path sidecar = storage.resolve("workflows/mypack/flow1.options.json");
        assertTrue(Files.exists(wfFile), "parameterized json must exist");
        assertTrue(Files.exists(sidecar), "sidecar must exist");
        JsonNode written = JSON.readTree(Files.readString(wfFile));
        assertEquals("{{first_frame}}", written.path("load").path("inputs").path("image").asText(),
                "written json must be parameterized");
        // sidecar 可被 loader 读回
        var loader = new WorkflowTemplateLoader(storage.toString());
        assertEquals(2, loader.loadSlots("mypack/flow1").size());
    }

    @Test
    void importRejectsCanvasFormat(@TempDir Path storage) throws Exception {
        var report = service(storage).importWorkflow("bad", CANVAS.getBytes(StandardCharsets.UTF_8));
        assertFalse(report.ok());
        assertTrue(report.problems().stream().anyMatch(p -> p.contains("画布格式")),
                "problems: " + report.problems());
        assertFalse(Files.exists(storage.resolve("workflows/bad.json")), "rejected import must not persist");
    }

    @Test
    void importRollsBackOnValidationFailure(@TempDir Path storage) throws Exception {
        // 缺 model 必填链接 + 悬空链接 → 校验失败
        String broken = """
                {
                  "load": {"class_type": "LoadImage", "inputs": {"image": "a.png"}},
                  "host": {"class_type": "Host", "inputs": {"model": ["nope", 0]}},
                  "save": {"class_type": "SaveImage", "inputs": {"images": ["host", 0]}}
                }
                """;
        WorkflowLibraryService svc = service(storage);
        var report = svc.importWorkflow("broken", broken.getBytes(StandardCharsets.UTF_8));
        assertFalse(report.ok());
        assertFalse(report.problems().isEmpty());
        assertFalse(Files.exists(storage.resolve("workflows/broken.json")),
                "failed import must not leave files behind");
    }

    @Test
    void importKeepsPreviousVersionOnFailure(@TempDir Path storage) throws Exception {
        WorkflowLibraryService svc = service(storage);
        // 先导入一个好版本
        var good = svc.importWorkflow("flow", WF.getBytes(StandardCharsets.UTF_8));
        assertTrue(good.ok(), "first import must succeed: " + good.problems());
        Path wfFile = storage.resolve("workflows/flow.json");
        byte[] goodBytes = Files.readAllBytes(wfFile);

        // 再导入一个坏版本（同名）→ 拒绝且原文件保留
        String broken = """
                {"host": {"class_type": "Host", "inputs": {}}}
                """;
        var bad = svc.importWorkflow("flow", broken.getBytes(StandardCharsets.UTF_8));
        assertFalse(bad.ok());
        assertArrayEqualsBytes(goodBytes, Files.readAllBytes(wfFile),
                "previous good version must survive a rejected re-import");
        assertFalse(Files.list(wfFile.getParent()).anyMatch(p -> p.toString().contains(".bak-")),
                "no backup files left behind");
    }

    private void assertArrayEqualsBytes(byte[] a, byte[] b, String msg) {
        assertEquals(new String(a, StandardCharsets.UTF_8), new String(b, StandardCharsets.UTF_8), msg);
    }

    @Test
    void listShowsUserAndBuiltin(@TempDir Path storage) throws Exception {
        WorkflowLibraryService svc = service(storage);
        svc.importWorkflow("flow", WF.getBytes(StandardCharsets.UTF_8));
        List<WorkflowLibraryService.Entry> entries = svc.list();
        // 用户模板在列，且 classpath 内置模板（anima/illustrious/minimaxh3/spike）也可见
        assertTrue(entries.stream().anyMatch(e -> e.name().equals("flow") && e.source().equals("user")),
                "user entry expected: " + entries.stream().map(WorkflowLibraryService.Entry::name).toList());
        assertTrue(entries.stream().anyMatch(e -> e.source().equals("builtin")),
                "builtin entries expected");
    }

    @Test
    void getReturnsWorkflowAndSlots(@TempDir Path storage) throws Exception {
        WorkflowLibraryService svc = service(storage);
        svc.importWorkflow("flow", WF.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> detail = svc.get("flow");
        assertEquals("flow", detail.get("name"));
        assertEquals("user", detail.get("source"));
        @SuppressWarnings("unchecked")
        List<WorkflowLibraryService.SlotBrief> slots = (List<WorkflowLibraryService.SlotBrief>) detail.get("slots");
        assertEquals(2, slots.size());
        assertNotNull(detail.get("workflow"));
    }

    @Test
    void deleteOnlyUserTemplates(@TempDir Path storage) throws Exception {
        WorkflowLibraryService svc = service(storage);
        svc.importWorkflow("flow", WF.getBytes(StandardCharsets.UTF_8));
        assertTrue(Files.exists(storage.resolve("workflows/flow.json")));
        svc.delete("flow");
        assertFalse(Files.exists(storage.resolve("workflows/flow.json")));
        assertFalse(Files.exists(storage.resolve("workflows/flow.options.json")));
        // 内置模板不可删
        assertThrows(WorkflowLibraryService.WorkflowLibraryException.class,
                () -> svc.delete("anima/anima"));
    }

    @Test
    void nameTraversalRejected(@TempDir Path storage) {
        WorkflowLibraryService svc = service(storage);
        assertThrows(WorkflowLibraryService.WorkflowLibraryException.class,
                () -> svc.importWorkflow("../evil", WF.getBytes(StandardCharsets.UTF_8)));
        assertThrows(WorkflowLibraryService.WorkflowLibraryException.class,
                () -> svc.importWorkflow("a/b/c", WF.getBytes(StandardCharsets.UTF_8)));
    }
}
