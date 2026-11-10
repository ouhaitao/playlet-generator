package org.playlet.comfyui.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 导入时结构自检：验证工作流图本身合法（链接完整、required 齐备、无悬空、无孤儿非输出节点）。
 * 剪枝器对每个槽组合剪完后也跑同一套检查。
 */
public final class WorkflowValidator {

    private WorkflowValidator() {
    }

    /** 返回问题列表；空 = 通过。 */
    public static List<String> validate(ObjectNode wf, NodeTypeResolver types) {
        List<String> problems = new ArrayList<>();

        wf.fields().forEachRemaining(entry -> {
            String id = entry.getKey();
            JsonNode node = entry.getValue();
            String classType = node.path("class_type").asText();
            if (classType.isEmpty()) {
                problems.add(id + ": missing class_type");
                return;
            }
            JsonNode inputs = node.path("inputs");

            // required 齐备（链接或字面量都算齐备）；
            // COMFY_AUTOGROW_V3 等动态自增输入（实际输入为 values.a 点号名、数量动态）豁免按名检查
            Map<String, String> req = types.requiredInputTypes(classType);
            req.forEach((name, type) -> {
                if ("COMFY_AUTOGROW_V3".equals(type)) {
                    return;
                }
                JsonNode v = inputs.path(name);
                if (v.isMissingNode() || v.isNull()) {
                    problems.add(id + "." + name + ": required input missing");
                }
            });

            // 链接指向存在且槽位不越界
            inputs.fields().forEachRemaining(in -> {
                if (!WorkflowPruner.isLink(in.getValue())) {
                    return;
                }
                String srcId = in.getValue().get(0).asText();
                int slot = in.getValue().get(1).asInt();
                JsonNode src = wf.path(srcId);
                if (src.isMissingNode()) {
                    problems.add(id + "." + in.getKey() + ": links to missing node " + srcId);
                    return;
                }
                int outCount = types.outputTypes(src.path("class_type").asText()).size();
                if (slot >= outCount) {
                    problems.add(id + "." + in.getKey() + ": output slot " + slot
                            + " out of range (" + outCount + ") on " + srcId);
                }
            });
        });

        // 孤儿非输出节点（全填版不应有，但导出可能带未连线的测试节点）
        java.util.Set<String> consumed = new java.util.HashSet<>();
        wf.fields().forEachRemaining(e -> e.getValue().path("inputs").fields()
                .forEachRemaining(in -> {
                    if (WorkflowPruner.isLink(in.getValue())) {
                        consumed.add(in.getValue().get(0).asText());
                    }
                }));
        wf.fieldNames().forEachRemaining(id -> {
            String classType = wf.path(id).path("class_type").asText();
            if (!consumed.contains(id) && !types.isOutputNode(classType)) {
                problems.add(id + " (" + classType + "): orphan non-output node");
            }
        });

        return problems;
    }
}
