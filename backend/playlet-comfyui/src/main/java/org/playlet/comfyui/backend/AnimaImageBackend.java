package org.playlet.comfyui.backend;

import org.playlet.comfyui.client.ComfyClient;
import org.playlet.comfyui.template.NodeTypeResolver;
import org.playlet.comfyui.template.WorkflowTemplateLoader;
import org.springframework.stereotype.Component;

/** anima 图像后端（v1 默认图像后端，本地有模型）。 */
@Component
public class AnimaImageBackend extends SdImageBackend {

    public AnimaImageBackend(ComfyClient comfy, WorkflowTemplateLoader templates, NodeTypeResolver types) {
        super(comfy, templates, types);
    }

    @Override
    public String id() {
        return "ANIMA";
    }

    @Override
    protected String template() {
        return "anima/anima";
    }

    @Override
    protected boolean hasLoraSwitch() {
        return true;
    }
}
