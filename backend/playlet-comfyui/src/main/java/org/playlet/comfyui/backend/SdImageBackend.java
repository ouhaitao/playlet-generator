package org.playlet.comfyui.backend;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.playlet.comfyui.client.ComfyClient;
import org.playlet.comfyui.client.ComfyUiException;
import org.playlet.comfyui.template.NodeTypeResolver;
import org.playlet.comfyui.template.WorkflowPruner;
import org.playlet.comfyui.template.WorkflowSlot;
import org.playlet.comfyui.template.WorkflowTemplateLoader;
import org.playlet.domain.model.Capability;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * SD 系图像生成适配器基类（anima / illustrious）。
 *
 * <p>提交管线：loadRaw → substitute（含 bool 开关占位符）→ **foldConstantSwitches**
 * （开关常量折叠：消费端直连选中分支，删除开关节点，GC 未选中分支——关闭的 LoRA
 * 加载器随之删除，其 lora_name 不再进入提交校验）→ submit。
 *
 * <p>开关语义（值型可选配置，与 H3 的链接型可选输入剪枝互补）：
 * <ul>
 *   <li>{@code img2img}：firstFrameImage 非空 → true（VAEEncode 分支），否则 false（EmptyLatent 纯文生图）</li>
 *   <li>{@code lora}：extraParams.lora_name 非空 → true（仅 anima 模板有该开关）</li>
 *   <li>{@code swap}：宽高交换开关，主流程直接传 width/height，恒 false</li>
 * </ul>
 */
public abstract class SdImageBackend implements GenerationBackend {

    protected final ComfyClient comfy;
    protected final WorkflowTemplateLoader templates;
    protected final NodeTypeResolver types;

    protected SdImageBackend(ComfyClient comfy, WorkflowTemplateLoader templates, NodeTypeResolver types) {
        this.comfy = comfy;
        this.templates = templates;
        this.types = types;
    }

    /** classpath 模板名（不含 .json）。 */
    protected abstract String template();

    /** 模板是否含 LoRA 开关。 */
    protected abstract boolean hasLoraSwitch();

    @Override
    public Set<Capability> capabilities() {
        Set<Capability> caps = new HashSet<>(Set.of(Capability.TEXT_TO_IMAGE, Capability.IMAGE_TO_IMAGE));
        if (hasLoraSwitch()) {
            caps.add(Capability.LORA);
        }
        return Set.copyOf(caps);
    }

    @Override
    public Optional<Double> maxDurationSec() {
        return Optional.empty();
    }

    @Override
    public void validate(GenerationRequest req) {
        if (req.prompt() == null || req.prompt().isBlank()) {
            throw new IllegalArgumentException(id() + ": prompt 必填");
        }
        Object lora = req.extraParams() == null ? null : req.extraParams().get("lora_name");
        if (lora != null && !hasLoraSwitch()) {
            throw new IllegalArgumentException(id() + ": 模板无 LoRA 开关，不支持 lora_name");
        }
    }

    @Override
    public String submit(GenerationRequest req) {
        validate(req);
        Map<String, Object> extra = req.extraParams() == null ? Map.of() : req.extraParams();
        Object loraName = extra.get("lora_name");

        Map<String, String> values = new HashMap<>();
        values.put("prompt", req.prompt());
        if (extra.get("negative") != null) values.put("negative", String.valueOf(extra.get("negative")));
        values.put("prefix", "image/" + id().toLowerCase());
        values.put("img2img", String.valueOf(req.firstFrameImage() != null));
        values.put("swap", "false");
        if (hasLoraSwitch()) {
            values.put("lora", String.valueOf(loraName != null));
            if (loraName != null) {
                values.put("lora_name", String.valueOf(loraName));
            }
            if (extra.get("lora_strength") != null) {
                values.put("lora_strength", String.valueOf(extra.get("lora_strength")));
            }
        }
        if (req.width() != null) values.put("width", String.valueOf(req.width()));
        if (req.height() != null) values.put("height", String.valueOf(req.height()));
        if (req.seed() != null) values.put("seed", String.valueOf(req.seed()));
        if (extra.get("steps") != null) values.put("steps", String.valueOf(extra.get("steps")));
        if (extra.get("cfg") != null) values.put("cfg", String.valueOf(extra.get("cfg")));
        if (req.firstFrameImage() != null) values.put("first_frame", req.firstFrameImage());

        ObjectNode wf = templates.loadRaw(template());
        templates.substitute(wf, values);
        WorkflowPruner.foldConstantSwitches(wf, types);
        return comfy.submit(wf);
    }

    @Override
    public GenerationResult awaitResult(String promptId) {
        var history = comfy.awaitCompletion(promptId);
        var file = comfy.firstOutputFile(history);
        if (file == null) {
            throw new ComfyUiException("no output file in history for " + promptId);
        }
        return new GenerationResult(
                file.path("filename").asText(null),
                file.path("subfolder").asText(""),
                file.path("type").asText("output"),
                null);
    }

    /** sidecar 槽位（SD 系当前为空清单，保留接口一致性）。 */
    protected List<WorkflowSlot> slots() {
        return templates.loadSlots(template());
    }
}
