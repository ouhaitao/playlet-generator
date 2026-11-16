package org.playlet.comfyui.backend;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.playlet.comfyui.client.ComfyClient;
import org.playlet.comfyui.client.ComfyUiException;
import org.playlet.comfyui.template.WorkflowImporter;
import org.playlet.comfyui.template.WorkflowMeta;
import org.playlet.comfyui.template.WorkflowTemplateLoader;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 默认生成后端（唯一在用的提交通道）：
 * 读已导入的原始工作流 → 把前端传来的参数按 jsonPath（如 "3/inputs/seed"）写回原文 →
 * 提交 ComfyUI 执行。图片与视频共用，不做任何模型特化/参数推导。
 *
 * <p>写回 fail-loud：路径非法、字段不存在、目标是连线引用、值类型不支持均直接抛
 * {@link IllegalArgumentException}（400 透传给前端）。
 *
 * <p>旧的 image/video 专用 backend（Anima/Illustrious/MiniMaxH3 等）已废弃，
 * 由 {@code GenerationService} 之外的调用方自行清理，本类不依赖它们。
 */
@Component
public class DefaultGenerationBackend {

    public static final String ID = "DEFAULT";

    /** 模板名：相对路径风格（允许一层子目录），字母数字下划线中划线；防目录穿越。 */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)?");

    private final ComfyClient comfy;
    private final WorkflowTemplateLoader templates;

    public DefaultGenerationBackend(ComfyClient comfy, WorkflowTemplateLoader templates) {
        this.comfy = comfy;
        this.templates = templates;
    }

    public String id() {
        return ID;
    }

    /**
     * 提交生成：params 为 jsonPath → 值（String/Number/Boolean），null/空 = 全用导入时的默认值。
     * 返回 ComfyUI prompt_id（不等待完成）。
     */
    public String submit(String workflowName, Map<String, Object> params) {
        if (workflowName == null || !NAME.matcher(workflowName).matches()) {
            throw new IllegalArgumentException("工作流名不合法: " + workflowName);
        }
        ObjectNode wf = templates.loadRaw(workflowName);
        if (params != null) {
            params.forEach((path, value) -> WorkflowImporter.applyValueByPath(wf, path, value));
        }
        return comfy.submit(wf);
    }

    /** 读工作流的解析元数据；不存在（未导入/旧模板）返回 null。 */
    public WorkflowMeta meta(String workflowName) {
        if (workflowName == null || !NAME.matcher(workflowName).matches()) {
            throw new IllegalArgumentException("工作流名不合法: " + workflowName);
        }
        return templates.loadMeta(workflowName);
    }

    /** 阻塞等待指定 prompt_id 完成，返回产物位置；超时/失败抛异常。 */
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
}
