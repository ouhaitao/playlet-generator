package org.playlet.comfyui.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 参数化工作流模板加载器（DESIGN.md §4 ④层）。
 * 模板为 API 格式 JSON，占位符形如 {{name}}，运行时替换为实际值。
 * 模板文件放 classpath:workflows/*.json（从 ComfyUI 导出的 API 格式）。
 */
@Component
public class WorkflowTemplateLoader {

    private final ObjectMapper json = new ObjectMapper();

    /** 加载并替换占位符。values 中 value 为 null 的占位符保持原样（调用方应保证必填都有值）。 */
    public JsonNode load(String templateName, Map<String, String> values) {
        ClassPathResource res = new ClassPathResource("workflows/" + templateName + ".json");
        if (!res.exists()) {
            throw new IllegalStateException("workflow template not found: workflows/" + templateName
                    + ".json —— 请在 ComfyUI 开 Dev Mode → Save (API Format) 导出后放入该目录");
        }
        try (InputStream in = res.getInputStream()) {
            JsonNode root = json.readTree(in);
            substitute(root, values);
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("failed to read template " + templateName, e);
        }
    }

    /** 加载原始模板（不做占位符替换），供剪枝器先改写再替换。 */
    public ObjectNode loadRaw(String templateName) {
        ClassPathResource res = new ClassPathResource("workflows/" + templateName + ".json");
        if (!res.exists()) {
            throw new IllegalStateException("workflow template not found: workflows/" + templateName + ".json");
        }
        try (InputStream in = res.getInputStream()) {
            return (ObjectNode) json.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read template " + templateName, e);
        }
    }

    /** 读模板的 sidecar 槽位清单（classpath:workflows/<name>.options.json）；不存在返回空。 */
    public List<WorkflowSlot> loadSlots(String templateName) {
        ClassPathResource res = new ClassPathResource("workflows/" + templateName + ".options.json");
        if (!res.exists()) {
            return List.of();
        }
        try (InputStream in = res.getInputStream()) {
            return json.readValue(in,
                    json.getTypeFactory().constructCollectionType(List.class, WorkflowSlot.class));
        } catch (IOException e) {
            throw new IllegalStateException("failed to read sidecar for " + templateName, e);
        }
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
