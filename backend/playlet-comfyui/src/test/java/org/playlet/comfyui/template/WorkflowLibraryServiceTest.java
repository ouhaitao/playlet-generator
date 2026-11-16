package org.playlet.comfyui.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作流库服务单测（离线，@TempDir 存储，不需要 ComfyUI）：
 * 导入落盘原文 + meta、画布格式拒收、无可填参数拒收、失败回滚、列表/详情/删除权限、
 * 以及 jsonPath 写回语义（含拒绝覆盖连线）。
 */
class WorkflowLibraryServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 合成图：unet→host(+可选 first_frame)→save；含字面量参数与连线引用。 */
    private static final String WF = """
            {
              "unet": {"class_type": "UNETLoader",
                       "_meta": {"title": "加载 UNet"},
                       "inputs": {"unet_name": "m.safetensors", "weight_dtype": "default"}},
              "load": {"class_type": "LoadImage", "inputs": {"image": "a.png"}},
              "host": {"class_type": "Host", "inputs": {
                  "model": ["unet", 0], "first_frame": ["load", 0],
                  "seed": 42, "cfg": 8.0, "enable": true}},
              "save": {"class_type": "SaveImage", "inputs": {"images": ["host", 0]}}
            }
            """;

    private static final String CANVAS = """
            {"last_node_id": 3, "last_link_id": 2, "nodes": [], "links": []}
            """;

    /** 导入时用户填写的模型名（提示词优化 agent 做模型级优化用）。 */
    private static final String MODEL = "test-model";

    private WorkflowLibraryService service(Path storage) {
        WorkflowTemplateLoader loader = new WorkflowTemplateLoader(storage.toString());
        return new WorkflowLibraryService(new WorkflowImporter(), loader);
    }

    @Test
    void importWritesRawJsonAndMeta(@TempDir Path storage) throws Exception {
        WorkflowLibraryService svc = service(storage);
        var report = svc.importWorkflow("mypack/flow1", WorkflowType.IMAGE, MODEL,
                WF.getBytes(StandardCharsets.UTF_8));

        assertTrue(report.ok(), "import must succeed: " + report.problems());
        assertEquals("mypack/flow1", report.name());
        assertEquals(WorkflowType.IMAGE, report.type());
        assertEquals(MODEL, report.model());
        assertEquals(3, report.nodeCount(), "unet/load/host 有字面量参数，save 全是连线");
        assertEquals(6, report.fieldCount(), "2 + 1 + 3");

        Path wfFile = storage.resolve("workflows/mypack/flow1.json");
        Path metaFile = storage.resolve("workflows/mypack/flow1.meta.json");
        assertTrue(Files.exists(wfFile), "raw json must exist");
        assertTrue(Files.exists(metaFile), "meta sidecar must exist");

        // 原文不被改写（不参数化）
        JsonNode written = JSON.readTree(Files.readString(wfFile));
        assertEquals("a.png", written.path("load").path("inputs").path("image").asText());
        assertEquals(42, written.path("host").path("inputs").path("seed").asInt());

        // meta 可被 loader 读回，jsonPath 与默认值正确
        WorkflowMeta meta = new WorkflowTemplateLoader(storage.toString()).loadMeta("mypack/flow1");
        assertNotNull(meta);
        assertEquals(WorkflowType.IMAGE, meta.type());
        assertEquals(MODEL, meta.model());
        WorkflowMeta.FieldParam seed = findField(meta, "host", "seed");
        assertEquals("host/inputs/seed", seed.jsonPath());
        assertEquals("NUMBER", seed.kind());
        assertEquals(42L, ((Number) seed.defaultValue()).longValue());
        WorkflowMeta.FieldParam cfg = findField(meta, "host", "cfg");
        assertEquals(8.0, (Double) cfg.defaultValue(), 1e-9);
        assertEquals("BOOLEAN", findField(meta, "host", "enable").kind());
        // 节点标题取 _meta.title，缺省回落 class_type
        assertEquals("加载 UNet", groupOf(meta, "unet").nodeTitle());
        assertEquals("LoadImage", groupOf(meta, "load").nodeTitle());
        // 连线引用不入参数表：host.model 不在，save 全是连线 → 整个节点不出现
        assertNull(findFieldOrNull(meta, "host", "model"));
        assertTrue(meta.groups().stream().noneMatch(g -> g.nodeId().equals("save")));
    }

    @Test
    void importRecordsVideoType(@TempDir Path storage) throws Exception {
        var report = service(storage).importWorkflow("v/flow", WorkflowType.VIDEO, MODEL,
                WF.getBytes(StandardCharsets.UTF_8));
        assertTrue(report.ok(), report.problems().toString());
        assertEquals(WorkflowType.VIDEO, report.type());
        assertEquals(WorkflowType.VIDEO,
                new WorkflowTemplateLoader(storage.toString()).loadMeta("v/flow").type());
    }

    @Test
    void importRejectsCanvasFormat(@TempDir Path storage) throws Exception {
        var report = service(storage).importWorkflow("bad", WorkflowType.IMAGE, MODEL,
                CANVAS.getBytes(StandardCharsets.UTF_8));
        assertFalse(report.ok());
        assertTrue(report.problems().stream().anyMatch(p -> p.contains("画布格式")),
                "problems: " + report.problems());
        assertFalse(Files.exists(storage.resolve("workflows/bad.json")), "rejected import must not persist");
        assertFalse(Files.exists(storage.resolve("workflows/bad.meta.json")));
    }

    @Test
    void importRejectsWorkflowWithoutLiteralParams(@TempDir Path storage) throws Exception {
        String noParams = """
                {"save": {"class_type": "SaveImage", "inputs": {"images": ["src", 0]}}}
                """;
        var report = service(storage).importWorkflow("empty", WorkflowType.IMAGE, MODEL,
                noParams.getBytes(StandardCharsets.UTF_8));
        assertFalse(report.ok());
        assertFalse(report.problems().isEmpty());
        assertFalse(Files.exists(storage.resolve("workflows/empty.json")),
                "failed import must not leave files behind");
    }

    @Test
    void importRequiresType(@TempDir Path storage) throws Exception {
        var report = service(storage).importWorkflow("flow", null, MODEL, WF.getBytes(StandardCharsets.UTF_8));
        assertFalse(report.ok());
        assertTrue(report.problems().stream().anyMatch(p -> p.contains("类型必填")));
    }

    @Test
    void importRequiresModel(@TempDir Path storage) throws Exception {
        WorkflowLibraryService svc = service(storage);
        // 模型缺失 / 空白均拒收（提示词优化 agent 依赖它做模型级优化）
        for (String missing : new String[]{null, "", "   "}) {
            var report = svc.importWorkflow("flow", WorkflowType.IMAGE, missing,
                    WF.getBytes(StandardCharsets.UTF_8));
            assertFalse(report.ok(), "model=" + missing + " 应被拒收");
            assertTrue(report.problems().stream().anyMatch(p -> p.contains("模型必填")),
                    "problems: " + report.problems());
        }
        assertFalse(Files.exists(storage.resolve("workflows/flow.json")),
                "model 缺失时不得落盘");
    }

    @Test
    void importTrimsModel(@TempDir Path storage) throws Exception {
        var report = service(storage).importWorkflow("flow", WorkflowType.IMAGE, "  anima  ",
                WF.getBytes(StandardCharsets.UTF_8));
        assertTrue(report.ok(), report.problems().toString());
        assertEquals("anima", report.model(), "模型名应去除首尾空白");
        assertEquals("anima", new WorkflowTemplateLoader(storage.toString()).loadMeta("flow").model());
    }

    @Test
    void importKeepsPreviousVersionOnFailure(@TempDir Path storage) throws Exception {
        WorkflowLibraryService svc = service(storage);
        var good = svc.importWorkflow("flow", WorkflowType.IMAGE, MODEL, WF.getBytes(StandardCharsets.UTF_8));
        assertTrue(good.ok(), "first import must succeed: " + good.problems());
        Path wfFile = storage.resolve("workflows/flow.json");
        Path metaFile = storage.resolve("workflows/flow.meta.json");
        String goodWf = Files.readString(wfFile);
        String goodMeta = Files.readString(metaFile);

        // 再导入一个坏版本（同名）→ 拒绝且原文件保留
        var bad = svc.importWorkflow("flow", WorkflowType.VIDEO, MODEL,
                CANVAS.getBytes(StandardCharsets.UTF_8));
        assertFalse(bad.ok());
        assertEquals(goodWf, Files.readString(wfFile), "previous raw json must survive");
        assertEquals(goodMeta, Files.readString(metaFile), "previous meta must survive");
        assertFalse(Files.list(wfFile.getParent()).anyMatch(p -> p.toString().contains(".bak-")),
                "no backup files left behind");
    }

    @Test
    void listShowsUserEntriesWithType(@TempDir Path storage) throws Exception {
        WorkflowLibraryService svc = service(storage);
        svc.importWorkflow("flow", WorkflowType.IMAGE, MODEL, WF.getBytes(StandardCharsets.UTF_8));
        svc.importWorkflow("vflow", WorkflowType.VIDEO, MODEL, WF.getBytes(StandardCharsets.UTF_8));
        List<WorkflowLibraryService.Entry> entries = svc.list();
        assertEquals(WorkflowType.IMAGE, entries.stream()
                .filter(e -> e.name().equals("flow")).findFirst().orElseThrow().type());
        assertEquals(WorkflowType.VIDEO, entries.stream()
                .filter(e -> e.name().equals("vflow")).findFirst().orElseThrow().type());
        assertTrue(entries.stream().filter(e -> e.name().equals("flow") || e.name().equals("vflow"))
                .allMatch(e -> MODEL.equals(e.model())), "列表须透出模型名");
        assertTrue(entries.stream().filter(e -> e.name().equals("flow") || e.name().equals("vflow"))
                .allMatch(e -> e.source().equals("user")));
    }

    @Test
    void getReturnsTypeAndGroups(@TempDir Path storage) throws Exception {
        WorkflowLibraryService svc = service(storage);
        svc.importWorkflow("flow", WorkflowType.IMAGE, MODEL, WF.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> detail = svc.get("flow");
        assertEquals("flow", detail.get("name"));
        assertEquals("user", detail.get("source"));
        assertEquals(WorkflowType.IMAGE, detail.get("type"));
        assertEquals(MODEL, detail.get("model"));
        assertNotNull(detail.get("importedAt"));
        @SuppressWarnings("unchecked")
        List<WorkflowMeta.NodeGroup> groups = (List<WorkflowMeta.NodeGroup>) detail.get("groups");
        assertEquals(3, groups.size());
        // 原文不在详情里返回（前端只需要元数据渲染表单）
        assertFalse(detail.containsKey("workflow"));
    }

    @Test
    void deleteOnlyUserTemplates(@TempDir Path storage) throws Exception {
        WorkflowLibraryService svc = service(storage);
        svc.importWorkflow("flow", WorkflowType.IMAGE, MODEL, WF.getBytes(StandardCharsets.UTF_8));
        assertTrue(Files.exists(storage.resolve("workflows/flow.json")));
        svc.delete("flow");
        assertFalse(Files.exists(storage.resolve("workflows/flow.json")));
        assertFalse(Files.exists(storage.resolve("workflows/flow.meta.json")));
        // 不存在的模板不可删
        assertThrows(WorkflowLibraryService.WorkflowLibraryException.class,
                () -> svc.delete("nope/nope"));
    }

    @Test
    void nameTraversalRejected(@TempDir Path storage) {
        WorkflowLibraryService svc = service(storage);
        assertThrows(WorkflowLibraryService.WorkflowLibraryException.class,
                () -> svc.importWorkflow("../evil", WorkflowType.IMAGE, MODEL, WF.getBytes(StandardCharsets.UTF_8)));
        assertThrows(WorkflowLibraryService.WorkflowLibraryException.class,
                () -> svc.importWorkflow("a/b/c", WorkflowType.IMAGE, MODEL, WF.getBytes(StandardCharsets.UTF_8)));
    }

    // ── jsonPath 写回 ─────────────────────────────────────────────

    @Test
    void applyValueByPathWritesTypedValues(@TempDir Path storage) throws Exception {
        service(storage).importWorkflow("flow", WorkflowType.IMAGE, MODEL, WF.getBytes(StandardCharsets.UTF_8));
        ObjectNode wf = new WorkflowTemplateLoader(storage.toString()).loadRaw("flow");

        WorkflowImporter.applyValueByPath(wf, "host/inputs/seed", 7L);
        WorkflowImporter.applyValueByPath(wf, "host/inputs/cfg", 3.5);
        WorkflowImporter.applyValueByPath(wf, "host/inputs/enable", false);
        WorkflowImporter.applyValueByPath(wf, "load/inputs/image", "b.png");

        assertEquals(7, wf.path("host").path("inputs").path("seed").asInt());
        assertTrue(wf.path("host").path("inputs").path("seed").isIntegralNumber());
        assertEquals(3.5, wf.path("host").path("inputs").path("cfg").asDouble(), 1e-9);
        assertFalse(wf.path("host").path("inputs").path("enable").asBoolean());
        assertEquals("b.png", wf.path("load").path("inputs").path("image").asText());
    }

    @Test
    void applyValueByPathRejectsBadPaths(@TempDir Path storage) throws Exception {
        service(storage).importWorkflow("flow", WorkflowType.IMAGE, MODEL, WF.getBytes(StandardCharsets.UTF_8));
        ObjectNode wf = new WorkflowTemplateLoader(storage.toString()).loadRaw("flow");

        // 连线引用不可覆盖
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowImporter.applyValueByPath(wf, "host/inputs/model", "x"));
        // 不允许新增字段
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowImporter.applyValueByPath(wf, "host/inputs/brand_new", "x"));
        // 节点不存在
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowImporter.applyValueByPath(wf, "nope/inputs/seed", 1L));
        // 路径层级非法
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowImporter.applyValueByPath(wf, "host/seed", 1L));
        // 值类型不支持（Map）
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowImporter.applyValueByPath(wf, "host/inputs/seed", Map.of("a", 1)));
    }

    @Test
    void keysWithDotsStayIntact(@TempDir Path storage) throws Exception {
        String wf = """
                {"105:91": {"class_type": "X.Y", "inputs": {"a.b": "v", "n": 1}}}
                """;
        service(storage).importWorkflow("dotted", WorkflowType.IMAGE, MODEL, wf.getBytes(StandardCharsets.UTF_8));
        WorkflowMeta meta = new WorkflowTemplateLoader(storage.toString()).loadMeta("dotted");
        assertEquals("105:91/inputs/a.b", findField(meta, "105:91", "a.b").jsonPath());

        ObjectNode raw = new WorkflowTemplateLoader(storage.toString()).loadRaw("dotted");
        WorkflowImporter.applyValueByPath(raw, "105:91/inputs/a.b", "z");
        assertEquals("z", raw.path("105:91").path("inputs").path("a.b").asText());
    }

    @Test
    void groupsSortedByNumericNodeId() {
        String wf = """
                {"10": {"class_type": "A", "inputs": {"x": 1}},
                 "2": {"class_type": "B", "inputs": {"y": 2}},
                 "unet": {"class_type": "C", "inputs": {"z": 3}}}
                """;
        List<WorkflowMeta.NodeGroup> groups = WorkflowImporter.extractGroupedParams(readTree(wf));
        assertEquals(List.of("2", "10", "unet"), groups.stream()
                .map(WorkflowMeta.NodeGroup::nodeId).toList());
    }

    private static JsonNode readTree(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private WorkflowMeta.NodeGroup groupOf(WorkflowMeta meta, String nodeId) {
        return meta.groups().stream().filter(g -> g.nodeId().equals(nodeId))
                .findFirst().orElseThrow(() -> new AssertionError("no group " + nodeId));
    }

    private WorkflowMeta.FieldParam findField(WorkflowMeta meta, String nodeId, String inputName) {
        WorkflowMeta.FieldParam f = findFieldOrNull(meta, nodeId, inputName);
        assertNotNull(f, "field not found: " + nodeId + "." + inputName);
        return f;
    }

    private WorkflowMeta.FieldParam findFieldOrNull(WorkflowMeta meta, String nodeId, String inputName) {
        return groupOf(meta, nodeId).fields().stream()
                .filter(f -> f.inputName().equals(inputName)).findFirst().orElse(null);
    }
}
