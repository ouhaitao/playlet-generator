package org.playlet.comfyui.template;

import java.util.List;
import java.util.Map;

/**
 * 节点类型解析：剪枝/旁路/校验需要知道每个 class_type 的输入输出类型。
 * 生产实现走 ComfyUI /object_info；测试用桩。
 */
public interface NodeTypeResolver {

    /** 必填输入：inputName → 类型名（COMBO 记为 "COMBO"）。 */
    Map<String, String> requiredInputTypes(String classType);

    /** 可选输入：inputName → 类型名。 */
    Map<String, String> optionalInputTypes(String classType);

    /** 输出类型，按槽位顺序。 */
    List<String> outputTypes(String classType);

    /** 是否输出节点（SaveImage/SaveVideo 等，GC 永不删除）。 */
    boolean isOutputNode(String classType);

    /** 该类型是否为可连线类型（非 STRING/INT/FLOAT/BOOLEAN/COMBO 字面量；动态自增输入豁免）。 */
    static boolean isLinkType(String type) {
        if (type == null) {
            return false;
        }
        return !switch (type) {
            case "STRING", "INT", "FLOAT", "BOOLEAN", "COMBO", "UNKNOWN", "COMFY_AUTOGROW_V3" -> true;
            default -> false;
        };
    }
}
