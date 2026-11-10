package org.playlet.comfyui.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * sidecar 清单提取器：导入时从**这份**全填版 JSON 现场解析可选链接型输入，
 * 生成 {@link WorkflowSlot} 列表（写入 *.options.json，与模板成对存放）。
 *
 * <p>只收**链接型可选输入**（值形如 ["节点id", 槽]）；值型旋钮（steps/cfg/strength）
 * 不进清单——它们由占位符机制覆盖（不提供值即保留导出默认）。
 */
public final class SlotExtractor {

    private SlotExtractor() {
    }

    public static List<WorkflowSlot> extract(ObjectNode workflow, NodeTypeResolver types) {
        List<WorkflowSlot> slots = new ArrayList<>();
        Map<String, Integer> nameCount = new HashMap<>();

        workflow.fields().forEachRemaining(entry -> {
            String hostId = entry.getKey();
            JsonNode node = entry.getValue();
            String classType = node.path("class_type").asText();
            Map<String, String> optional = types.optionalInputTypes(classType);
            JsonNode inputs = node.path("inputs");
            optional.forEach((inputName, type) -> {
                JsonNode v = inputs.path(inputName);
                if (!WorkflowPruner.isLink(v)) {
                    return; // 未连线或值型，跳过
                }
                String sourceId = v.get(0).asText();
                int sourceOutput = v.get(1).asInt();
                JsonNode source = workflow.path(sourceId);
                if (source.isMissingNode()) {
                    throw new IllegalStateException(
                            "slot " + hostId + "." + inputName + " links to missing node " + sourceId);
                }
                String sourceClass = source.path("class_type").asText();
                boolean chain = hasLinkInput(source, sourceClass, types);
                String name = inputName;
                nameCount.merge(name, 1, Integer::sum);
                slots.add(new WorkflowSlot(name, hostId, inputName, sourceId, sourceOutput,
                        type, chain ? "chain" : "source"));
            });
        });

        // 重名槽加宿主 id 前缀
        Map<String, Integer> seen = new HashMap<>();
        List<WorkflowSlot> out = new ArrayList<>();
        for (WorkflowSlot s : slots) {
            String name = s.name();
            if (nameCount.get(name) > 1) {
                name = s.hostId() + "." + name;
            }
            seen.merge(name, 1, Integer::sum);
            out.add(new WorkflowSlot(name, s.hostId(), s.inputName(), s.sourceId(),
                    s.sourceOutput(), s.type(), s.sourceKind()));
        }
        return out;
    }

    private static boolean hasLinkInput(JsonNode sourceNode, String classType, NodeTypeResolver types) {
        Map<String, String> req = types.requiredInputTypes(classType);
        Map<String, String> opt = types.optionalInputTypes(classType);
        JsonNode inputs = sourceNode.path("inputs");
        for (Map.Entry<String, String> e : req.entrySet()) {
            if (NodeTypeResolver.isLinkType(e.getValue()) && WorkflowPruner.isLink(inputs.path(e.getKey()))) {
                return true;
            }
        }
        for (Map.Entry<String, String> e : opt.entrySet()) {
            if (NodeTypeResolver.isLinkType(e.getValue()) && WorkflowPruner.isLink(inputs.path(e.getKey()))) {
                return true;
            }
        }
        return false;
    }
}
