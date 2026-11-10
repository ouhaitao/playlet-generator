package org.playlet.comfyui.backend;

import org.junit.jupiter.api.Test;
import org.playlet.comfyui.client.ComfyClient;
import org.playlet.comfyui.template.ComfyNodeTypeResolver;
import org.playlet.comfyui.template.WorkflowTemplateLoader;
import org.playlet.domain.model.Capability;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能力声明数据驱动：sidecar 清单决定能力集合。
 * 当前 H3 全填版含 last_frame + audio 槽 → 应声明 FIRST_LAST_FRAME + NATIVE_AUDIO。
 */
class CapabilitiesFromSidecarTest {

    @Test
    void capabilitiesFollowSidecar() {
        MiniMaxH3Backend backend = new MiniMaxH3Backend(
                new ComfyClient("http://127.0.0.1:28070", 1000, 60),
                new WorkflowTemplateLoader(),
                new ComfyNodeTypeResolver("http://127.0.0.1:28070"));
        Set<Capability> caps = backend.capabilities();
        System.out.println("[caps] " + caps);
        assertTrue(caps.contains(Capability.IMAGE_TO_VIDEO));
        assertTrue(caps.contains(Capability.FIRST_LAST_FRAME),
                "sidecar has last_frame slot → FIRST_LAST_FRAME expected, got " + caps);
        assertTrue(caps.contains(Capability.NATIVE_AUDIO),
                "sidecar has audio slot → NATIVE_AUDIO expected, got " + caps);
    }
}
