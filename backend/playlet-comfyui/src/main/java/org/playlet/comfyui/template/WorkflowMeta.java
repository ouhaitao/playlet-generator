package org.playlet.comfyui.template;

import java.util.List;

/**
 * 工作流解析元数据（&lt;name&gt;.meta.json sidecar）：
 * 按节点分组的所有可人工填写字面量参数（跳过连线引用），
 * 每个参数带可直接写回原始工作流的 jsonPath（如 "3/inputs/seed"）与默认值（导入时的原值）。
 *
 * <p>前端审核台据此渲染参数表单；生成时用户填的值按 jsonPath 写回原始工作流后提交 ComfyUI。
 * {@code model} 为导入时用户填写的模型名（如 anima / minimax_h3），
 * 提示词优化 agent 据此做模型级专门优化。
 */
public record WorkflowMeta(
        String name,
        WorkflowType type,
        String model,
        String importedAt,
        List<NodeGroup> groups
) {

    /** 一个节点的参数字段。 */
    public record FieldParam(
            String inputName,
            /** STRING / NUMBER / BOOLEAN */
            String kind,
            /** 写回路径：节点ID/inputs/字段名（原始键名，不做转义） */
            String jsonPath,
            /** 导入时原始工作流中的值（String/Long/Double/Boolean） */
            Object defaultValue
    ) {
    }

    /** 一个节点及其全部字面量参数。 */
    public record NodeGroup(
            String nodeId,
            String classType,
            String nodeTitle,
            List<FieldParam> fields
    ) {
    }

    public int fieldCount() {
        return groups == null ? 0 : groups.stream().mapToInt(g -> g.fields().size()).sum();
    }
}
