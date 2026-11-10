package org.playlet.comfyui.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流剪枝器（DESIGN.md §4.7）：把"可选配置开/关"从多模板变体变成提交前图改写。
 *
 * <p>关闭一个槽（= 关闭其源节点 C）的统一算法，**以消费端为中心**：
 * <ol>
 *   <li>遍历 C 每个输出槽的每个消费端 (H, I)：
 *     <ul>
 *       <li>I 在 H 上是**可选**输入 → 删除该链接（如 first_frame 关）</li>
 *       <li>I 在 H 上是**必填**输入 → **旁路改接**：H.inputs[I] 改指向 C 的同类型输入链接
 *           （如 LoRA 关：host.model→unet、host.clip→clip）。同类型输入链接不唯一即拒绝，不猜</li>
 *     </ul>
 *   </li>
 *   <li>删除 C</li>
 *   <li>孤儿 GC：无消费端且非 output_node 的节点迭代删净（如 LoadImage 上游的 ImageScale）</li>
 * </ol>
 *
 * <p>共享同一源节点的多个槽是**耦合组**：关其一即关整个源（LoRA 的 model/clip 双输出同理）。
 * 占位符替换在调用方于剪枝**之后**进行。
 */
public final class WorkflowPruner {

    private WorkflowPruner() {
    }

    /**
     * 返回剪枝后的工作流副本（不修改入参）。
     *
     * @param workflow      全填版 API-JSON
     * @param slots         sidecar 清单
     * @param disabledSlots 要关闭的槽位名集合
     * @param types         节点类型解析器
     */
    public static ObjectNode prune(ObjectNode workflow, List<WorkflowSlot> slots,
                                   Set<String> disabledSlots, NodeTypeResolver types) {
        ObjectNode wf = workflow.deepCopy();
        Set<String> toDelete = new LinkedHashSet<>();

        for (WorkflowSlot slot : slots) {
            if (!disabledSlots.contains(slot.name()) || toDelete.contains(slot.sourceId())) {
                continue;
            }
            ObjectNode source = node(wf, slot.sourceId());
            if (source == null) {
                throw new PruneException("source node missing: " + slot.sourceId());
            }
            disableNode(wf, slot.sourceId(), types);
            toDelete.add(slot.sourceId());
        }

        toDelete.addAll(findOrphans(wf, toDelete, types));
        for (String id : toDelete) {
            wf.remove(id);
        }
        return wf;
    }

    /** 关闭节点 C：消费端可选→删链接；必填→旁路改接；然后标记删除由调用方统一 GC。 */
    private static void disableNode(ObjectNode wf, String sourceId, NodeTypeResolver types) {
        ObjectNode source = node(wf, sourceId);
        String classType = source.path("class_type").asText();
        List<String> outTypes = types.outputTypes(classType);

        for (int outSlot = 0; outSlot < outTypes.size(); outSlot++) {
            if (!NodeTypeResolver.isLinkType(outTypes.get(outSlot))) {
                continue;
            }
            List<Consumer> consumers = consumersOf(wf, sourceId, outSlot);
            for (Consumer c : consumers) {
                ObjectNode host = node(wf, c.hostId());
                Map<String, String> opt = types.optionalInputTypes(host.path("class_type").asText());
                if (opt.containsKey(c.inputName())) {
                    ((ObjectNode) host.get("inputs")).remove(c.inputName());
                } else {
                    ArrayNode replacement = sameTypeInputLink(source, classType, outTypes.get(outSlot), types);
                    ((ObjectNode) host.get("inputs")).set(c.inputName(), replacement.deepCopy());
                }
            }
        }
    }

    /** C 上类型为 type 的链接型输入；唯一匹配，0 或 >1 都拒绝。 */
    private static ArrayNode sameTypeInputLink(ObjectNode source, String classType, String type,
                                               NodeTypeResolver types) {
        Map<String, String> req = types.requiredInputTypes(classType);
        Map<String, String> opt = types.optionalInputTypes(classType);
        ArrayNode found = null;
        int matches = 0;
        for (Map.Entry<String, String> e : concat(req, opt)) {
            if (!e.getValue().equals(type)) {
                continue;
            }
            JsonNode inLink = source.path("inputs").path(e.getKey());
            if (isLink(inLink)) {
                found = (ArrayNode) inLink;
                matches++;
            }
        }
        if (matches == 0) {
            throw new PruneException("bypass " + classType + " (" + type
                    + "): no same-type input link to reroute to");
        }
        if (matches > 1) {
            throw new PruneException("bypass " + classType + " (" + type
                    + "): ambiguous, " + matches + " same-type input links — refuse to guess");
        }
        return found;
    }

    private record Consumer(String hostId, String inputName) {
    }

    private static List<Consumer> consumersOf(ObjectNode wf, String sourceId, int outSlot) {
        List<Consumer> out = new ArrayList<>();
        wf.fields().forEachRemaining(e -> {
            e.getValue().path("inputs").fields().forEachRemaining(in -> {
                JsonNode v = in.getValue();
                if (isLink(v) && v.get(0).asText().equals(sourceId) && v.get(1).asInt() == outSlot) {
                    out.add(new Consumer(e.getKey(), in.getKey()));
                }
            });
        });
        return out;
    }

    /** 孤儿 GC：无消费端且非 output_node 的节点迭代删净。 */
    private static Set<String> findOrphans(ObjectNode wf, Set<String> seed, NodeTypeResolver types) {
        Set<String> deleted = new HashSet<>(seed);
        boolean changed = true;
        while (changed) {
            changed = false;
            Set<String> consumed = consumedNodeIds(wf, deleted);
            for (Iterator<String> it = wf.fieldNames(); it.hasNext(); ) {
                String id = it.next();
                if (deleted.contains(id)) {
                    continue;
                }
                String ct = wf.path(id).path("class_type").asText();
                if (types.isOutputNode(ct)) {
                    continue;
                }
                if (!consumed.contains(id)) {
                    deleted.add(id);
                    changed = true;
                }
            }
        }
        return deleted;
    }

    private static Set<String> consumedNodeIds(ObjectNode wf, Set<String> deleted) {
        Set<String> consumed = new HashSet<>();
        wf.fields().forEachRemaining(e -> {
            if (deleted.contains(e.getKey())) {
                return;
            }
            e.getValue().path("inputs").fields().forEachRemaining(in -> {
                if (isLink(in.getValue())) {
                    consumed.add(in.getValue().get(0).asText());
                }
            });
        });
        return consumed;
    }

    static boolean isLink(JsonNode v) {
        return v != null && v.isArray() && v.size() == 2 && v.get(0).isTextual() && v.get(1).isInt();
    }

    static ObjectNode node(ObjectNode wf, String id) {
        JsonNode n = wf.get(id);
        return n instanceof ObjectNode o ? o : null;
    }

    private static Iterable<Map.Entry<String, String>> concat(Map<String, String> a, Map<String, String> b) {
        List<Map.Entry<String, String>> all = new ArrayList<>(a.entrySet());
        all.addAll(b.entrySet());
        return all;
    }

    /** 剪枝失败（结构不可安全改写）。 */
    public static class PruneException extends RuntimeException {
        public PruneException(String message) {
            super(message);
        }
    }
}
