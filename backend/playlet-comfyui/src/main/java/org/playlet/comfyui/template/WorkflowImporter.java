package org.playlet.comfyui.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工作流导入器（DESIGN.md §4.7）：
 * <ol>
 *   <li>读全填版 API-JSON</li>
 *   <li>结构自检（{@link WorkflowValidator}）</li>
 *   <li>现场解析可选链接型输入 → sidecar 清单（{@link SlotExtractor}），写 *.options.json</li>
 *   <li>对 2^k 个槽组合各剪一遍并自检（k≤4 时 ≤16 次）</li>
 * </ol>
 * 任一步失败即拒绝导入，报错带具体问题。
 */
@Component
public class WorkflowImporter {

    private static final Logger log = LoggerFactory.getLogger(WorkflowImporter.class);
    private static final int MAX_COMBINATION_SLOTS = 4;

    private final ObjectMapper json = new ObjectMapper();
    private final NodeTypeResolver types;

    public WorkflowImporter(NodeTypeResolver types) {
        this.types = types;
    }

    /** 导入结果。 */
    public record ImportResult(List<WorkflowSlot> slots, List<String> problems, Path sidecar,
                               List<String> substitutions, List<String> notes) {
    }

    public ImportResult importWorkflow(Path templateJson) throws IOException {
        ObjectNode raw = (ObjectNode) json.readTree(Files.readString(templateJson));

        List<String> problems = new ArrayList<>(WorkflowValidator.validate(raw, types));
        if (!problems.isEmpty()) {
            return new ImportResult(List.of(), problems, null, List.of(), List.of());
        }

        // 自动参数化：硬编码值 → 占位符（规则引擎，见 WorkflowParameterizer）
        WorkflowParameterizer.Result param = WorkflowParameterizer.parameterize(raw);
        ObjectNode wf = param.workflow();

        List<WorkflowSlot> slots = SlotExtractor.extract(wf, types);

        // 2^k 组合自检（对参数化后的工作流）
        if (slots.size() <= MAX_COMBINATION_SLOTS) {
            for (int mask = 0; mask < (1 << slots.size()); mask++) {
                Set<String> disabled = new HashSet<>();
                for (int i = 0; i < slots.size(); i++) {
                    if ((mask & (1 << i)) != 0) {
                        disabled.add(slots.get(i).name());
                    }
                }
                try {
                    ObjectNode pruned = WorkflowPruner.prune(wf, slots, disabled, types);
                    problems.addAll(WorkflowValidator.validate(pruned, types).stream()
                            .map(p -> "combination " + disabled + ": " + p)
                            .toList());
                } catch (WorkflowPruner.PruneException e) {
                    problems.add("combination " + disabled + ": prune failed: " + e.getMessage());
                }
            }
        } else {
            log.warn("workflow {} has {} slots (>{}), skipping combination self-check",
                    templateJson.getFileName(), slots.size(), MAX_COMBINATION_SLOTS);
        }

        if (!problems.isEmpty()) {
            return new ImportResult(slots, problems, null, param.substitutions(), param.notes());
        }

        // 落盘：参数化后的工作流 + sidecar
        json.writerWithDefaultPrettyPrinter().writeValue(templateJson.toFile(), wf);
        Path sidecar = sidecarPath(templateJson);
        json.writerWithDefaultPrettyPrinter().writeValue(sidecar.toFile(), slots);
        log.info("imported {} with {} slot(s), {} substitution(s)",
                templateJson.getFileName(), slots.size(), param.substitutions().size());
        return new ImportResult(slots, List.of(), sidecar, param.substitutions(), param.notes());
    }

    /** 读已存在的 sidecar；不存在返回空。 */
    public List<WorkflowSlot> loadSlots(Path templateJson) throws IOException {
        Path sidecar = sidecarPath(templateJson);
        if (!Files.exists(sidecar)) {
            return List.of();
        }
        return json.readValue(sidecar.toFile(),
                json.getTypeFactory().constructCollectionType(List.class, WorkflowSlot.class));
    }

    private Path sidecarPath(Path templateJson) {
        String name = templateJson.getFileName().toString();
        return templateJson.resolveSibling(name.replaceAll("\\.json$", "") + ".options.json");
    }
}
