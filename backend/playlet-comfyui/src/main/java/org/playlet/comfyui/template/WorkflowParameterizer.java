package org.playlet.comfyui.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自动参数化器（DESIGN.md §4.7 层 3 的规则引擎化）：把全填版导出中的硬编码值替换为占位符。
 *
 * <p>信号来源（机器可判，见会话结论）：
 * <ol>
 *   <li>生态命名约定：KSampler.seed/steps/cfg、RandomNoise.noise_seed</li>
 *   <li>multiline STRING 提示词 + 拓扑定正负：CLIPTextEncode.text 的消费端槽位
 *       （positive/conditioning → prompt，negative → negative）</li>
 *   <li>加载/保存节点：LoadImage.image 按消费端槽位命名（VAEEncode.pixels → first_frame），
 *       Save*.filename_prefix → prefix，ResolutionSelector.aspect_ratio → aspect_ratio</li>
 *   <li>Primitive* 源节点 = 作者声明的可调旋钮：沿 ComfySwitchNode/ComfyMathExpression
 *       追踪到语义汇点命名；**开关极性规则**：Primitive 经 switch 的 on_false 分支生效时的
 *       汇点名才是它的名字（修复宽高交换镜像拓扑标反问题）</li>
 *   <li>开关字面量布尔：ComfySwitchNode.switch → bool:&lt;名&gt;，名由 on_true 分支源节点类推导
 *       （LoraLoader* → lora；含 VAEEncode/LoadImage → img2img；否则 switch_&lt;id&gt; 并报告）</li>
 *   <li>LoRA 类节点：lora_name / strength_* </li>
 * </ol>
 * 已是占位符（{{...}}）的值跳过（幂等）。工作流级常量（模型名/采样器/调度器等）不动，
 * 列入报告的信息区（policy-constant），不视为错误。
 */
public final class WorkflowParameterizer {

    /** 工作流级常量输入名（策略：保持硬编码，报告信息区列出）。 */
    private static final Set<String> POLICY_CONSTANTS = Set.of(
            "sampler_name", "scheduler", "denoise", "megapixels", "multiple",
            "unet_name", "vae_name", "clip_name", "ckpt_name", "weight_dtype", "expression");

    private WorkflowParameterizer() {
    }

    /** 参数化结果。 */
    public record Result(ObjectNode workflow, List<String> substitutions, List<String> notes) {
    }

    public static Result parameterize(ObjectNode raw) {
        ObjectNode wf = raw.deepCopy();
        List<String> subs = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        Map<String, String> classOf = new HashMap<>();
        wf.fields().forEachRemaining(e -> classOf.put(e.getKey(), e.getValue().path("class_type").asText()));

        wf.fields().forEachRemaining(entry -> {
            String id = entry.getKey();
            String ct = classOf.get(id);
            ObjectNode inputs = (ObjectNode) entry.getValue().path("inputs");
            if (inputs == null || inputs.isMissingNode()) {
                return;
            }
            switch (ct) {
                case "KSampler" -> {
                    put(inputs, "seed", "{{num:seed}}", id, subs);
                    put(inputs, "steps", "{{num:steps}}", id, subs);
                    put(inputs, "cfg", "{{num:cfg}}", id, subs);
                }
                case "RandomNoise" -> put(inputs, "noise_seed", "{{num:seed}}", id, subs);
                case "CLIPTextEncode" -> {
                    String name = textEncodeRole(wf, id);
                    if (name != null) {
                        put(inputs, "text", "{{" + name + "}}", id, subs);
                    }
                }
                case "MiniMaxH3ImageToVideo" -> put(inputs, "prompt", "{{prompt}}", id, subs);
                case "LoadImage" -> {
                    String name = loadImageSlot(wf, id);
                    put(inputs, "image", "{{" + name + "}}", id, subs);
                }
                case "SaveImage", "SaveVideo" -> put(inputs, "filename_prefix", "{{prefix}}", id, subs);
                case "ResolutionSelector" -> put(inputs, "aspect_ratio", "{{aspect_ratio}}", id, subs);
                case "EmptyLatentImage" -> {
                    put(inputs, "width", "{{num:width}}", id, subs);
                    put(inputs, "height", "{{num:height}}", id, subs);
                }
                case "LoraLoader", "LoraLoaderModelOnly" -> {
                    put(inputs, "lora_name", "{{lora_name}}", id, subs);
                    put(inputs, "strength_model", "{{num:lora_strength}}", id, subs);
                    if (inputs.has("strength_clip")) {
                        put(inputs, "strength_clip", "{{num:lora_strength_clip}}", id, subs);
                    }
                }
                case "ComfySwitchNode" -> {
                    JsonNode sw = inputs.path("switch");
                    if (sw.isBoolean()) {
                        String name = switchName(wf, id, classOf, notes);
                        put(inputs, "switch", "{{bool:" + name + "}}", id, subs);
                    }
                }
                default -> {
                    if (ct.startsWith("Primitive")) {
                        String name = primitiveName(wf, id, ct, classOf, notes);
                        String prefix = ct.equals("PrimitiveBoolean") ? "bool:"
                                : ct.equals("PrimitiveString") ? "" : "num:";
                        put(inputs, "value", "{{" + prefix + name + "}}", id, subs);
                    }
                }
            }
            // 信息区：策略常量
            inputs.fieldNames().forEachRemaining(in -> {
                if (POLICY_CONSTANTS.contains(in)) {
                    notes.add(id + "." + in + " 保持硬编码（工作流级常量，策略）");
                }
            });
        });
        return new Result(wf, subs, notes);
    }

    private static void put(ObjectNode inputs, String field, String placeholder, String id,
                            List<String> subs) {
        JsonNode cur = inputs.path(field);
        if (cur.isMissingNode() || (cur.isTextual() && cur.asText().startsWith("{{"))) {
            return; // 已占位符化，幂等
        }
        if (WorkflowPruner.isLink(cur)) {
            return; // 链接是结构（由开关/Primitive 供给），不是字面量值，不替换
        }
        inputs.put(field, placeholder);
        subs.add(id + "." + field + " → " + placeholder);
    }

    /** CLIPTextEncode 正/负向：看输出 0 的消费端槽位。 */
    private static String textEncodeRole(ObjectNode wf, String id) {
        for (List<String> c : List.of(consumerInputs(wf, id, 0))) {
            for (String in : c) {
                if (in.equals("positive") || in.equals("conditioning")) {
                    return "prompt";
                }
                if (in.equals("negative")) {
                    return "negative";
                }
            }
        }
        return null;
    }

    /** LoadImage 槽位名：消费端槽位优先；VAEEncode.pixels → first_frame。 */
    private static String loadImageSlot(ObjectNode wf, String id) {
        List<String> ins = consumerInputs(wf, id, 0);
        for (String in : ins) {
            if (in.equals("first_frame") || in.equals("last_frame") || in.equals("image")) {
                return in;
            }
        }
        for (String[] hc : consumers(wf, id, 0)) {
            String hct = wf.path(hc[0]).path("class_type").asText();
            if (hct.equals("VAEEncode") && hc[1].equals("pixels")) {
                return "first_frame";
            }
        }
        return "image_" + id;
    }

    /** 开关名：on_true 分支源节点类推导。 */
    private static String switchName(ObjectNode wf, String id, Map<String, String> classOf,
                                     List<String> notes) {
        JsonNode onTrue = wf.path(id).path("inputs").path("on_true");
        if (WorkflowPruner.isLink(onTrue)) {
            String srcClass = classOf.get(onTrue.get(0).asText());
            if (srcClass != null && srcClass.startsWith("Lora")) {
                return "lora";
            }
            if (branchContains(wf, onTrue.get(0).asText(), classOf, Set.of("VAEEncode", "LoadImage"), 3)) {
                return "img2img";
            }
        }
        notes.add(id + " switch 名无法推导，使用 switch_" + id);
        return "switch_" + id;
    }

    private static boolean branchContains(ObjectNode wf, String id, Map<String, String> classOf,
                                            Set<String> targets, int depth) {
        if (depth <= 0) {
            return false;
        }
        String ct = classOf.get(id);
        if (ct == null) {
            return false;
        }
        if (targets.contains(ct)) {
            return true;
        }
        JsonNode inputs = wf.path(id).path("inputs");
        for (JsonNode v : inputs) {
            if (WorkflowPruner.isLink(v)
                    && branchContains(wf, v.get(0).asText(), classOf, targets, depth - 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Primitive 命名：沿 switch/math 追踪语义汇点。
     * 开关极性规则：经 switch 的 on_false 分支生效时的汇点名才是名字（on_true 是交换取向，忽略）。
     */
    private static String primitiveName(ObjectNode wf, String id, String ct,
                                          Map<String, String> classOf, List<String> notes) {
        Set<String> names = new LinkedHashSet<>();
        for (String[] hc : consumers(wf, id, 0)) {
            String hct = classOf.get(hc[0]);
            if ("ComfySwitchNode".equals(hct)) {
                if ("switch".equals(hc[1])) {
                    // 作为开关控制源：看该开关输出喂进哪些汇点（宽高交换 → swap）
                    names.addAll(consumerInputs(wf, hc[0], 0));
                } else if ("on_false".equals(hc[1])) {
                    names.addAll(consumerInputs(wf, hc[0], 0));
                }
                // on_true：交换取向，不参与命名
            } else if ("ComfyMathExpression".equals(hct)) {
                names.addAll(consumerInputs(wf, hc[0], 0).stream()
                        .map(WorkflowParameterizer::alias).toList());
                names.addAll(consumerInputs(wf, hc[0], 1).stream()
                        .map(WorkflowParameterizer::alias).toList());
            } else {
                names.add(alias(hc[1]));
            }
        }
        if ("PrimitiveBoolean".equals(ct) && names.equals(Set.of("width", "height"))) {
            return "swap";
        }
        if (names.size() == 1) {
            return names.iterator().next();
        }
        notes.add(id + " primitive 汇点不唯一 " + names + "，使用 prim_" + id);
        return "prim_" + id;
    }

    private static String alias(String name) {
        return "length".equals(name) ? "duration" : name;
    }

    private static List<String> consumerInputs(ObjectNode wf, String srcId, int slot) {
        List<String> out = new ArrayList<>();
        for (String[] hc : consumers(wf, srcId, slot)) {
            out.add(hc[1]);
        }
        return out;
    }

    private static List<String[]> consumers(ObjectNode wf, String srcId, int slot) {
        List<String[]> out = new ArrayList<>();
        wf.fields().forEachRemaining(e -> {
            e.getValue().path("inputs").fields().forEachRemaining(in -> {
                JsonNode v = in.getValue();
                if (WorkflowPruner.isLink(v)
                        && v.get(0).asText().equals(srcId) && v.get(1).asInt() == slot) {
                    out.add(new String[]{e.getKey(), in.getKey()});
                }
            });
        });
        return out;
    }
}
