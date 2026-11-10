package org.playlet.comfyui.template;

import java.util.List;
import java.util.Map;

/** 测试用节点类型桩：不连 ComfyUI。 */
public class StubNodeTypeResolver implements NodeTypeResolver {

    private final Map<String, Map<String, String>> required;
    private final Map<String, Map<String, String>> optional;
    private final Map<String, List<String>> outputs;
    private final Map<String, Boolean> outputNodes;

    public StubNodeTypeResolver(Map<String, Map<String, String>> required,
                                Map<String, Map<String, String>> optional,
                                Map<String, List<String>> outputs,
                                Map<String, Boolean> outputNodes) {
        this.required = required;
        this.optional = optional;
        this.outputs = outputs;
        this.outputNodes = outputNodes;
    }

    @Override
    public Map<String, String> requiredInputTypes(String classType) {
        return required.getOrDefault(classType, Map.of());
    }

    @Override
    public Map<String, String> optionalInputTypes(String classType) {
        return optional.getOrDefault(classType, Map.of());
    }

    @Override
    public List<String> outputTypes(String classType) {
        return outputs.getOrDefault(classType, List.of());
    }

    @Override
    public boolean isOutputNode(String classType) {
        return outputNodes.getOrDefault(classType, false);
    }
}
