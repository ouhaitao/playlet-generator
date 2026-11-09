package org.playlet.comfyui.backend;

import com.fasterxml.jackson.databind.JsonNode;
import org.playlet.comfyui.client.ComfyClient;
import org.playlet.comfyui.client.ComfyUiException;
import org.playlet.comfyui.template.WorkflowTemplateLoader;
import org.playlet.domain.model.Capability;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MiniMax H3 视频后端（v1 唯一视频后端）。
 *
 * <p>能力声明以**已导出模板实际能跑**为准：当前仅有图生视频模板
 * （minimaxh3/minimax_h3_ti2v，联合音视频 latent → NATIVE_AUDIO）。
 * 首尾帧双约束 / 多图参考 / 文生视频模板尚未导出，故暂不声明对应能力，
 * 避免主流程调用不存在的功能；后续导出模板后在此处增补即可。
 *
 * <p>时长约束：17k+5 帧网格 @24fps。模板内 ComfyMathExpression 已做对齐，
 * 后端传原始秒数即可，validate 仅做过远告警。
 */
@Component
public class MiniMaxH3Backend implements GenerationBackend {

    private static final double FPS = 24.0;
    private static final String TEMPLATE_I2V = "minimaxh3/minimax_h3_ti2v";

    private final ComfyClient comfy;
    private final WorkflowTemplateLoader templates;

    public MiniMaxH3Backend(ComfyClient comfy, WorkflowTemplateLoader templates) {
        this.comfy = comfy;
        this.templates = templates;
    }

    @Override
    public String id() {
        return "MINIMAX_H3";
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(
                Capability.IMAGE_TO_VIDEO,
                Capability.NATIVE_AUDIO
        );
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
    public void validate(GenerationRequest req) {
        if ("VIDEO".equals(req.kind()) && req.firstFrameImage() == null) {
            throw new IllegalArgumentException(
                    "MINIMAX_H3 当前仅支持图生视频：firstFrameImage 必填（模板 " + TEMPLATE_I2V + "）");
        }
        if (req.durationSec() != null) {
            double snapped = snapDuration(req.durationSec());
            if (Math.abs(snapped - req.durationSec()) > 0.75) {
                throw new IllegalArgumentException(
                        "duration " + req.durationSec() + "s 距最近网格点 " + snapped + "s 过远（"
                                + frameGridNote() + "）");
            }
        }
    }

    /** 提交并阻塞等待，返回产物位置。 */
    public GenerationResult generate(GenerationRequest req) {
        validate(req);
        Map<String, String> values = new HashMap<>();
        values.put("prompt", req.prompt() == null ? "" : req.prompt());
        values.put("first_frame", req.firstFrameImage());
        values.put("prefix", "video/playlet_h3");
        values.put("aspect_ratio", toTemplateAspectRatio(req.aspectRatio()));
        values.put("duration", String.valueOf(req.durationSec() == null ? 5.0 : req.durationSec()));
        values.put("seed", String.valueOf(req.seed() == null ? randomSeed() : req.seed()));

        JsonNode workflow = templates.load(TEMPLATE_I2V, values);
        String promptId = comfy.submit(workflow);
        JsonNode history = comfy.awaitCompletion(promptId);
        JsonNode file = comfy.firstOutputFile(history);
        if (file == null) {
            throw new ComfyUiException("no output file in history for " + promptId);
        }
        return new GenerationResult(
                file.path("filename").asText(null),
                file.path("subfolder").asText(""),
                file.path("type").asText("output"),
                req.durationSec());
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

    /** 对齐 17k+5 帧网格（秒）。供 UI/校验参考；模板内表达式也会再对齐一次。 */
    public static double snapDuration(double seconds) {
        double frames = seconds * FPS;
        long k = Math.round((frames - 5) / 17.0);
        if (k < 0) k = 0;
        return (17 * k + 5) / FPS;
    }
}
