package org.playlet.comfyui.backend.image;

import org.playlet.comfyui.client.ComfyClient;
import org.playlet.comfyui.template.NodeTypeResolver;
import org.playlet.comfyui.template.WorkflowTemplateLoader;
import org.springframework.stereotype.Component;

/** illustrious SDXL 图像后端（本地暂无模型，注册但生成会由 ComfyUI 校验拒绝）。 */
@Component
public class IllustriousImageBackend extends SdImageBackend {

    public IllustriousImageBackend(ComfyClient comfy, WorkflowTemplateLoader templates, NodeTypeResolver types) {
        super(comfy, templates, types);
    }

    @Override
    public String id() {
        return "ILLUSTRIOUS";
    }

    @Override
    protected String template() {
        return "illustrious/WAI-ILLUSTRIOUS-SDXL";
    }

    @Override
    protected boolean hasLoraSwitch() {
        return false;
    }
}
