package org.playlet.comfyui.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 参数化工作流模板加载器（DESIGN.md §4 ④层）。
 * 模板为 API 格式 JSON，占位符形如 {{name}}，运行时替换为实际值。
 *
 * <p>查找顺序：存储目录 {@code <playlet.storage.dir>/workflows/<name>.json}（前端导入的模板，
 * 覆盖同名内置模板）→ classpath:workflows/&lt;name&gt;.json（随包内置模板）。
 */
@Component
public class WorkflowTemplateLoader {

    private final ObjectMapper json = new ObjectMapper();
    /** 存储目录下的 workflows 根；null = 仅 classpath（测试用无参构造）。 */
    private final Path storageWorkflows;

    /** 测试/CLI 用：仅 classpath。 */
    public WorkflowTemplateLoader() {
        this.storageWorkflows = null;
    }

    @Autowired
    public WorkflowTemplateLoader(@Value("${playlet.storage.dir}") String storageDir) {
        this.storageWorkflows = storageDir == null ? null : Path.of(storageDir).resolve("workflows");
    }

    /** 存储目录 workflows 根（可能尚不存在）；null = 未配置。 */
    public Path storageWorkflowsDir() {
        return storageWorkflows;
    }

    /** 加载并替换占位符。values 中 value 为 null 的占位符保持原样（调用方应保证必填都有值）。 */
    public JsonNode load(String templateName, Map<String, String> values) {
        try {
            JsonNode root = readTree(templateName, ".json");
            substitute(root, values);
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("failed to read template " + templateName, e);
        }
    }

    /** 加载原始模板（不做占位符替换），供剪枝器先改写再替换。 */
    public ObjectNode loadRaw(String templateName) {
        try {
            return (ObjectNode) readTree(templateName, ".json");
        } catch (IOException e) {
            throw new IllegalStateException("failed to read template " + templateName, e);
        }
    }

    /** 读模板的 sidecar 槽位清单；不存在返回空。 */
    public List<WorkflowSlot> loadSlots(String templateName) {
        try {
            JsonNode sidecar = readTreeOrNull(templateName, ".options.json");
            if (sidecar == null) {
                return List.of();
            }
            return json.readValue(json.treeAsTokens(sidecar),
                    json.getTypeFactory().constructCollectionType(List.class, WorkflowSlot.class));
        } catch (IOException e) {
            throw new IllegalStateException("failed to read sidecar for " + templateName, e);
        }
    }

    private JsonNode readTree(String templateName, String suffix) throws IOException {
        JsonNode node = readTreeOrNull(templateName, suffix);
        if (node == null) {
            throw new IllegalStateException("workflow template not found: " + templateName
                    + " —— 内置模板在 classpath:workflows/，导入模板在 <storage>/workflows/"
                    + "（ComfyUI 开 Dev Mode → Save (API Format) 导出后经前端「工作流库」导入）");
        }
        return node;
    }

    /** 存储目录优先，classpath 兜底；都没有返回 null。suffix 形如 ".json"/".options.json"。 */
    private JsonNode readTreeOrNull(String templateName, String suffix) throws IOException {
        Path fs = resolveStoragePath(templateName, suffix);
        if (fs != null) {
            return json.readTree(Files.readString(fs));
        }
        ClassPathResource res = new ClassPathResource("workflows/" + templateName + suffix);
        if (!res.exists()) {
            return null;
        }
        try (InputStream in = res.getInputStream()) {
            return json.readTree(in);
        }
    }

    /** 存储目录下的模板文件路径；不存在或名字越界返回 null。 */
    public Path resolveStoragePath(String templateName, String suffix) {
        if (storageWorkflows == null || templateName == null) {
            return null;
        }
        Path p = storageWorkflows.resolve(templateName + suffix).normalize();
        if (!p.startsWith(storageWorkflows)) {
            return null; // 防目录穿越
        }
        return Files.exists(p) ? p : null;
    }

    /** 对已剪枝的工作流做占位符替换（剪枝在前、替换在后）。 */
    public void substitute(ObjectNode workflow, Map<String, String> values) {
        substitute((JsonNode) workflow, values);
    }

    /**
     * 占位符语法：
     * <ul>
     *   <li>{@code {{name}}} —— 字符串替换</li>
     *   <li>{@code {{num:name}}} —— 数字替换（含小数点/指数为浮点，否则整数）</li>
     *   <li>{@code {{bool:name}}} —— 布尔替换</li>
     * </ul>
     * 未提供值的占位符保持原样。
     */
    private void substitute(JsonNode node, Map<String, String> values) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                if (v.isTextual()) {
                    String s = v.asText();
                    if (s.startsWith("{{") && s.endsWith("}}")) {
                        JsonNode rep = resolve(s.substring(2, s.length() - 2).trim(), values);
                        if (rep != null) {
                            e.setValue(rep);
                        }
                    }
                } else {
                    substitute(v, values);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                substitute(child, values);
            }
        }
    }

    private JsonNode resolve(String inner, Map<String, String> values) {
        if (inner.startsWith("num:")) {
            String val = values.get(inner.substring(4));
            if (val == null) {
                return null;
            }
            if (val.contains(".") || val.contains("e") || val.contains("E")) {
                return com.fasterxml.jackson.databind.node.DoubleNode.valueOf(Double.parseDouble(val));
            }
            return com.fasterxml.jackson.databind.node.LongNode.valueOf(Long.parseLong(val));
        }
        if (inner.startsWith("bool:")) {
            String val = values.get(inner.substring(5));
            if (val == null) {
                return null;
            }
            return com.fasterxml.jackson.databind.node.BooleanNode.valueOf(Boolean.parseBoolean(val));
        }
        String val = values.get(inner);
        return val == null ? null : TextNode.valueOf(val);
    }
}
