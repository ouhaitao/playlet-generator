package org.playlet.comfyui.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 工作流导入器（简化版）：解析任意 ComfyUI API 格式工作流，按节点分组提取所有
 * 可人工填写的字面量输入参数（跳过连线引用 [nodeId, slot]），为每个参数生成可直接
 * 写回的 jsonPath（如 "3/inputs/seed"），默认值 = 原始工作流中的值。
 *
 * <p>不做参数化/剪枝/组合自检——ComfyUI 节点类型成千上万且随版本演进，全自动解析
 * 不可维护；改为「后端只填值」：原始工作流原文落盘 &lt;name&gt;.json，
 * 解析元数据落盘 &lt;name&gt;.meta.json（{@link WorkflowMeta}）。
 * 生成时按 jsonPath 把用户填的值写回原文（{@link #applyValueByPath}）再提交 ComfyUI。
 *
 * <p>导入不需要 ComfyUI 在线（不查 /object_info）。
 */
@Component
public class WorkflowImporter {

    private static final Logger log = LoggerFactory.getLogger(WorkflowImporter.class);

    private final ObjectMapper json = new ObjectMapper();

    /** 导入结果：解析出的元数据（groups 为空表示没有任何可填参数）+ 问题清单。 */
    public record ImportResult(WorkflowMeta meta, List<String> problems, Path metaFile) {
    }

    /**
     * 解析并落盘：templateJson 为已写入存储目录的原始工作流文件，原地保留原文，
     * 元数据写 sidecar &lt;name&gt;.meta.json。model 为用户填写的模型名（必填，调用方校验）。
     */
    public ImportResult importWorkflow(Path templateJson, WorkflowType type, String model) throws IOException {
        JsonNode raw = json.readTree(Files.readString(templateJson));
        if (!raw.isObject()) {
            return new ImportResult(null, List.of("JSON 根不是对象"), null);
        }
        List<String> problems = new ArrayList<>();
        if (raw.has("nodes") && raw.get("nodes").isArray() && raw.has("links")) {
            problems.add("这是 ComfyUI 画布格式（UI 导出）。请在 ComfyUI 设置里开启 Dev Mode，"
                    + "用「Save (API Format)」导出 API 格式 JSON 后再上传");
            return new ImportResult(null, problems, null);
        }

        String name = stripJsonSuffix(templateJson.getFileName().toString());
        List<WorkflowMeta.NodeGroup> groups = extractGroupedParams(raw);
        if (groups.isEmpty()) {
            problems.add("未提取到任何可人工填写的字面量参数——请确认上传的是 API 格式全填版工作流");
            return new ImportResult(null, problems, null);
        }

        WorkflowMeta meta = new WorkflowMeta(name, type, model, Instant.now().toString(), groups);
        Path metaFile = metaPath(templateJson);
        json.writerWithDefaultPrettyPrinter().writeValue(metaFile.toFile(), meta);
        log.info("imported workflow {} (type={}, model={}, {} node group(s), {} field(s))",
                name, type, model, groups.size(), meta.fieldCount());
        return new ImportResult(meta, List.of(), metaFile);
    }

    /** 读已存在的 meta sidecar；不存在返回 null。 */
    public WorkflowMeta loadMeta(Path templateJson) throws IOException {
        Path metaFile = metaPath(templateJson);
        if (!Files.exists(metaFile)) {
            return null;
        }
        return json.readValue(metaFile.toFile(), WorkflowMeta.class);
    }

    public static Path metaPath(Path templateJson) {
        return templateJson.resolveSibling(stripJsonSuffix(templateJson.getFileName().toString()) + ".meta.json");
    }

    private static String stripJsonSuffix(String fileName) {
        return fileName.replaceAll("\\.json$", "");
    }

    // ── 解析：按节点分组提取字面量参数 ─────────────────────────────

    /** 按节点分组提取所有字面量输入参数（跳过连线引用）。 */
    public static List<WorkflowMeta.NodeGroup> extractGroupedParams(JsonNode workflow) {
        List<WorkflowMeta.NodeGroup> groups = new ArrayList<>();
        if (workflow == null || !workflow.isObject()) {
            return groups;
        }
        Iterator<Map.Entry<String, JsonNode>> nodeIt = workflow.fields();
        while (nodeIt.hasNext()) {
            Map.Entry<String, JsonNode> nodeEntry = nodeIt.next();
            String nodeId = nodeEntry.getKey();
            JsonNode node = nodeEntry.getValue();
            if (node == null || !node.isObject()) {
                continue;
            }
            String classType = node.path("class_type").asText("");
            String title = node.path("_meta").path("title").asText(classType);
            JsonNode inputs = node.path("inputs");
            if (!inputs.isObject()) {
                continue;
            }

            List<WorkflowMeta.FieldParam> fields = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> inputIt = inputs.fields();
            while (inputIt.hasNext()) {
                Map.Entry<String, JsonNode> ie = inputIt.next();
                JsonNode valueNode = ie.getValue();
                if (isLink(valueNode) || !isLiteral(valueNode)) {
                    continue;
                }
                // 路径用原始键名，不做 JSON Pointer 转义
                fields.add(new WorkflowMeta.FieldParam(
                        ie.getKey(), kindOf(valueNode),
                        nodeId + "/inputs/" + ie.getKey(), toJavaValue(valueNode)));
            }
            if (!fields.isEmpty()) {
                fields.sort(Comparator.comparing(WorkflowMeta.FieldParam::inputName));
                groups.add(new WorkflowMeta.NodeGroup(nodeId, classType, title, fields));
            }
        }
        groups.sort(Comparator.comparingInt(g -> parseIntSafe(g.nodeId())));
        return groups;
    }

    // ── 写回：按 jsonPath 填值 ─────────────────────────────────────

    /**
     * 按 jsonPath（如 "3/inputs/seed"）写回新值。
     * 只接受三级路径 节点ID/inputs/字段名，且目标必须已是字面量
     * （不允许覆盖连线、不允许新增字段）——失败 fail-loud 抛 {@link IllegalArgumentException}。
     */
    public static void applyValueByPath(ObjectNode workflow, String jsonPath, Object newValue) {
        if (workflow == null || jsonPath == null) {
            throw new IllegalArgumentException("workflow/jsonPath 不能为空");
        }
        String[] parts = jsonPath.split("/", 3);
        if (parts.length != 3 || !"inputs".equals(parts[1])) {
            throw new IllegalArgumentException("非法参数路径（须为 节点ID/inputs/字段名）: " + jsonPath);
        }
        String nodeId = parts[0];
        String inputName = parts[2];   // 保留原始键名，含点号也不拆

        JsonNode node = workflow.path(nodeId);
        if (!node.isObject()) {
            throw new IllegalArgumentException("节点不存在: " + nodeId + "（路径 " + jsonPath + "）");
        }
        JsonNode inputs = node.path("inputs");
        if (!inputs.isObject()) {
            throw new IllegalArgumentException("节点无 inputs: " + nodeId + "（路径 " + jsonPath + "）");
        }
        JsonNode old = inputs.get(inputName);
        if (old == null) {
            throw new IllegalArgumentException("字段不存在（不允许新增）: " + jsonPath);
        }
        if (isLink(old)) {
            throw new IllegalArgumentException("目标是连线引用，不允许覆盖: " + jsonPath);
        }

        ObjectNode inputObj = (ObjectNode) inputs;
        if (newValue instanceof String s) {
            inputObj.put(inputName, s);
        } else if (newValue instanceof Integer i) {
            inputObj.put(inputName, i);
        } else if (newValue instanceof Long l) {
            inputObj.put(inputName, l);
        } else if (newValue instanceof Double d) {
            inputObj.put(inputName, d);
        } else if (newValue instanceof Float f) {
            inputObj.put(inputName, f.doubleValue());
        } else if (newValue instanceof Boolean b) {
            inputObj.put(inputName, b);
        } else {
            throw new IllegalArgumentException("不支持的参数值类型 "
                    + (newValue == null ? "null" : newValue.getClass().getSimpleName()) + ": " + jsonPath);
        }
    }

    // ── 字面量判定 ─────────────────────────────────────────────────

    /** 连线引用：[源节点ID, 输出槽序号]。 */
    private static boolean isLink(JsonNode v) {
        if (v == null || !v.isArray() || v.size() != 2) {
            return false;
        }
        return v.get(0).isTextual() && v.get(1).isIntegralNumber();
    }

    private static boolean isLiteral(JsonNode v) {
        return v != null && (v.isTextual() || v.isNumber() || v.isBoolean());
    }

    private static Object toJavaValue(JsonNode v) {
        if (v.isTextual()) {
            return v.asText();
        }
        if (v.isIntegralNumber()) {
            return v.asLong();
        }
        if (v.isFloatingPointNumber()) {
            return v.asDouble();
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        return null;
    }

    private static String kindOf(JsonNode v) {
        if (v.isTextual()) {
            return "STRING";
        }
        if (v.isNumber()) {
            return "NUMBER";
        }
        if (v.isBoolean()) {
            return "BOOLEAN";
        }
        return "UNKNOWN";
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }
}
