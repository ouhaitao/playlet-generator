package org.playlet.comfyui.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动参数化器离线单测（不连 ComfyUI）：
 * 覆盖命名约定、正负提示词拓扑、Primitive 沿开关/数学表达式追踪、开关极性规则。
 */
class WorkflowParameterizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ObjectNode parse(String s) throws Exception {
        return (ObjectNode) JSON.readTree(s);
    }

    @Test
    void namesPositiveNegativeByTopology() throws Exception {
        ObjectNode wf = parse("""
                {
                  "6": {"class_type": "CLIPTextEncode", "inputs": {"text": "good stuff"}},
                  "7": {"class_type": "CLIPTextEncode", "inputs": {"text": "bad stuff"}},
                  "3": {"class_type": "KSampler", "inputs": {
                      "seed": 1, "steps": 20, "cfg": 5,
                      "positive": ["6", 0], "negative": ["7", 0]}}
                }
                """);
        var r = WorkflowParameterizer.parameterize(wf);
        assertEquals("{{prompt}}", r.workflow().path("6").path("inputs").path("text").asText());
        assertEquals("{{negative}}", r.workflow().path("7").path("inputs").path("text").asText());
        assertEquals("{{num:seed}}", r.workflow().path("3").path("inputs").path("seed").asText());
        assertEquals("{{num:steps}}", r.workflow().path("3").path("inputs").path("steps").asText());
    }

    @Test
    void primitiveThroughSwitchFalseBranchAndMathAlias() throws Exception {
        // 35 → sw37.on_false → EmptyLatent.width  ⇒ 35 = width
        // 34 → sw36.on_false → EmptyLatent.height ⇒ 34 = height
        // 111 → math.length → MiniMax.length ⇒ duration（alias）
        ObjectNode wf = parse("""
                {
                  "34": {"class_type": "PrimitiveInt", "inputs": {"value": 1792}},
                  "35": {"class_type": "PrimitiveInt", "inputs": {"value": 1344}},
                  "36": {"class_type": "ComfySwitchNode", "inputs": {
                      "switch": ["38", 0], "on_false": ["34", 0], "on_true": ["35", 0]}},
                  "37": {"class_type": "ComfySwitchNode", "inputs": {
                      "switch": ["38", 0], "on_false": ["35", 0], "on_true": ["34", 0]}},
                  "38": {"class_type": "PrimitiveBoolean", "inputs": {"value": true}},
                  "32": {"class_type": "EmptyLatentImage", "inputs": {
                      "width": ["37", 0], "height": ["36", 0]}},
                  "111": {"class_type": "PrimitiveFloat", "inputs": {"value": 5}},
                  "107": {"class_type": "ComfyMathExpression", "inputs": {
                      "expression": "max(5, round(a*24))", "values.a": ["111", 0]}},
                  "104": {"class_type": "MiniMaxH3ImageToVideo", "inputs": {
                      "length": ["107", 1], "prompt": "x"}}
                }
                """);
        var r = WorkflowParameterizer.parameterize(wf);
        ObjectNode w = r.workflow();
        assertEquals("{{num:height}}", w.path("34").path("inputs").path("value").asText(),
                "34 feeds height via on_false");
        assertEquals("{{num:width}}", w.path("35").path("inputs").path("value").asText(),
                "35 feeds width via on_false");
        assertEquals("{{bool:swap}}", w.path("38").path("inputs").path("value").asText(),
                "boolean controlling width/height switches = swap");
        assertEquals("{{num:duration}}", w.path("111").path("inputs").path("value").asText(),
                "primitive through math length alias = duration");
        assertEquals("{{prompt}}", w.path("104").path("inputs").path("prompt").asText());
    }

    @Test
    void switchNameFromOnTrueBranchAndLoraParams() throws Exception {
        ObjectNode wf = parse("""
                {
                  "30": {"class_type": "LoraLoaderModelOnly", "inputs": {
                      "lora_name": "x.safetensors", "strength_model": 1, "model": ["12", 0]}},
                  "31": {"class_type": "ComfySwitchNode", "inputs": {
                      "switch": true, "on_false": ["12", 0], "on_true": ["30", 0]}},
                  "12": {"class_type": "UNETLoader", "inputs": {"unet_name": "m.safetensors"}},
                  "3": {"class_type": "KSampler", "inputs": {"model": ["31", 0], "seed": 9}}
                }
                """);
        var r = WorkflowParameterizer.parameterize(wf);
        ObjectNode w = r.workflow();
        assertEquals("{{bool:lora}}", w.path("31").path("inputs").path("switch").asText(),
                "switch whose on_true is a LoraLoader = lora");
        assertEquals("{{lora_name}}", w.path("30").path("inputs").path("lora_name").asText());
        assertEquals("{{num:lora_strength}}", w.path("30").path("inputs").path("strength_model").asText());
        // 模型名是工作流级常量，不动
        assertEquals("m.safetensors", w.path("12").path("inputs").path("unet_name").asText());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("unet_name")),
                "unet_name should be reported as policy constant");
    }

    @Test
    void idempotentOnAlreadyParameterized() throws Exception {
        ObjectNode wf = parse("""
                {
                  "3": {"class_type": "KSampler", "inputs": {"seed": "{{num:seed}}", "steps": 20}}
                }
                """);
        var r = WorkflowParameterizer.parameterize(wf);
        assertEquals("{{num:seed}}", r.workflow().path("3").path("inputs").path("seed").asText());
        assertFalse(r.substitutions().stream().anyMatch(s -> s.contains("seed")),
                "already-parameterized value must not be re-substituted");
    }
}
