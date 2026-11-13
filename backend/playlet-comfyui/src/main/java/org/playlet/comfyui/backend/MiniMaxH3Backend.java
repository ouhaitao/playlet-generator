package org.playlet.comfyui.backend;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.playlet.comfyui.client.ComfyClient;
import org.playlet.comfyui.client.ComfyUiException;
import org.playlet.comfyui.template.WorkflowPruner;
import org.playlet.comfyui.template.WorkflowSlot;
import org.playlet.comfyui.template.WorkflowTemplateLoader;
import org.playlet.domain.model.Capability;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MiniMax H3 视频后端（v1 唯一视频后端）。
 *
 * <p>提交管线：loadRaw(全填版) → prune(按请求关闭可选槽) → substitute(占位符) → submit。
 * 可选槽由 sidecar 清单（导入时自动生成）驱动：
 * <ul>
 *   <li>first_frame —— 首帧图（源=LoadImage，source 型）</li>
 *   <li>last_frame —— 尾帧图（若全填版连上则存在）</li>
 *   <li>audio —— 原生音频（源=VAEDecodeAudio 链）；关闭即产出静音视频</li>
 * </ul>
 *
 * <p>能力声明数据驱动：清单里有 last_frame 槽才声明 FIRST_LAST_FRAME，有 audio 槽才声明
 * NATIVE_AUDIO——"以模板实际能跑为准"从约定变成机器事实。
 */
@Component
public class MiniMaxH3Backend implements GenerationBackend {

    private static final double FPS = 24.0;
    private static final String TEMPLATE_I2V = "minimaxh3/minimax_h3_ti2v";

    private final ComfyClient comfy;
    private final WorkflowTemplateLoader templates;
    private final org.playlet.comfyui.template.NodeTypeResolver types;
    private Set<Capability> capabilities;

    public MiniMaxH3Backend(ComfyClient comfy, WorkflowTemplateLoader templates,
                            org.playlet.comfyui.template.NodeTypeResolver types) {
        this.comfy = comfy;
        this.templates = templates;
        this.types = types;
    }

    @Override
    public String id() {
        return "MINIMAX_H3";
    }

    @Override
    public Set<Capability> capabilities() {
        if (capabilities != null) {
            return Set.copyOf(capabilities);
        }
        Set<Capability> caps = new HashSet<>(Set.of(Capability.IMAGE_TO_VIDEO));
        List<WorkflowSlot> slots = templates.loadSlots(TEMPLATE_I2V);
        if (hasSlot(slots, "last_frame")) {
            caps.add(Capability.FIRST_LAST_FRAME);
        }
        if (hasSlot(slots, "audio")) {
            caps.add(Capability.NATIVE_AUDIO);
        }
        capabilities = caps;
        return Set.copyOf(caps);
    }

    @Override
    public Optional<Double> maxDurationSec() {
        return Optional.of(10.0);
    }

    @Override
    public String frameGridNote() {
        return "MiniMax H3: 时长对齐 17k+5 帧 @24fps（约 5s/段）";
    }

    @Override
    public double snapDuration(double seconds) {
        return snapToFrameGrid(seconds);
    }

    @Override
    public void validate(GenerationRequest req) {
        if ("VIDEO".equals(req.kind()) && req.firstFrameImage() == null) {
            throw new IllegalArgumentException(
                    "MINIMAX_H3 当前仅支持图生视频：firstFrameImage 必填（模板 " + TEMPLATE_I2V + "）");
        }
        if (req.durationSec() != null) {
            double snapped = snapToFrameGrid(req.durationSec());
            if (Math.abs(snapped - req.durationSec()) > 0.75) {
                throw new IllegalArgumentException(
                        "duration " + req.durationSec() + "s 距最近网格点 " + snapped + "s 过远（"
                                + frameGridNote() + "）");
            }
        }
    }

    @Override
    public String submit(GenerationRequest req) {
        validate(req);
        List<WorkflowSlot> slots = templates.loadSlots(TEMPLATE_I2V);

        // 决定关闭哪些槽
        Set<String> disabled = new HashSet<>();
        if (req.firstFrameImage() == null && hasSlot(slots, "first_frame")) {
            disabled.add("first_frame");
        }
        if (req.lastFrameImage() == null && hasSlot(slots, "last_frame")) {
            disabled.add("last_frame");
        }
        if (!Boolean.TRUE.equals(req.nativeAudio()) && hasSlot(slots, "audio")) {
            disabled.add("audio");
        }

        ObjectNode wf = templates.loadRaw(TEMPLATE_I2V);
        wf = WorkflowPruner.prune(wf, slots, disabled, types);

        Map<String, String> values = new HashMap<>();
        values.put("prompt", req.prompt() == null ? "" : req.prompt());
        if (req.firstFrameImage() != null) values.put("first_frame", req.firstFrameImage());
        if (req.lastFrameImage() != null) values.put("last_frame", req.lastFrameImage());
        values.put("prefix", "video/playlet_h3");
        values.put("aspect_ratio", toTemplateAspectRatio(req.aspectRatio()));
        values.put("duration", String.valueOf(req.durationSec() == null ? 5.0 : req.durationSec()));
        values.put("seed", String.valueOf(req.seed() == null ? randomSeed() : req.seed()));
        templates.substitute(wf, values);

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

    private static boolean hasSlot(List<WorkflowSlot> slots, String name) {
        return slots.stream().anyMatch(s -> s.name().equals(name));
    }

    /** 项目画幅（9:16 等）→ ResolutionSelector 的 COMBO 选项文本。 */
    public static String toTemplateAspectRatio(String ratio) {
        if (ratio == null || ratio.isBlank()) {
            return "9:16 (Portrait Widescreen)";
        }
        return switch (ratio.trim()) {
            case "1:1" -> "1:1 (Square)";
            case "2:3" -> "2:3 (Portrait Photo)";
            case "3:2" -> "3:2 (Photo)";
            case "3:4" -> "3:4 (Portrait Standard)";
            case "4:3" -> "4:3 (Standard)";
            case "9:16" -> "9:16 (Portrait Widescreen)";
            case "16:9" -> "16:9 (Widescreen)";
            case "21:9" -> "21:9 (Ultrawide)";
            default -> throw new IllegalArgumentException("不支持的画幅: " + ratio);
        };
    }

    private static long randomSeed() {
        return Math.abs(new java.util.Random().nextLong() % 1_000_000_000_000L);
    }

    /** 对齐 17k+5 帧网格（秒）。供 UI/校验/切镜参考；模板内表达式也会再对齐一次。 */
    public static double snapToFrameGrid(double seconds) {
        double frames = seconds * FPS;
        long k = Math.round((frames - 5) / 17.0);
        if (k < 0) k = 0;
        return (17 * k + 5) / FPS;
    }
}
