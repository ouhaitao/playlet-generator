package org.playlet.comfyui.backend;

import java.util.Map;

/**
 * 抽象生成请求：主流程 → 适配器的唯一契约。
 * 主流程不感知模型，只描述"要什么"。
 *
 * @param kind      生成类型：FIRST_FRAME / VIDEO / TTS / UPSCALE
 * @param prompt    已确认的提示词（来自 PromptDraft 确认门，见 DESIGN.md §6.3）
 * @param durationSec 目标时长（秒）
 * @param aspectRatio 画幅，如 9:16 / 16:9 / 1:1；适配器映射到模型可接受的形式
 * @param width     输出宽（像素）；模板用分辨率选择器时可为空
 * @param height    输出高（像素）；同上
 * @param seed      种子；null = 随机
 * @param firstFrameImage 首帧图（ComfyUI input 目录文件名）；图生视频必填
 * @param lastFrameImage  尾帧图；仅 supports(FIRST_LAST_FRAME) 时有意义
 * @param referenceImages 参考图列表（角色/场景一致性）
 * @param nativeAudio 是否保留模型原生音频（H3）；false 时剪枝器关闭 audio 槽产出静音视频
 * @param extraParams 模型私有旋钮（JSON 透传，主流程不理解不校验）
 */
public record GenerationRequest(
        String kind,
        String prompt,
        Double durationSec,
        String aspectRatio,
        Integer width,
        Integer height,
        Long seed,
        String firstFrameImage,
        String lastFrameImage,
        java.util.List<String> referenceImages,
        Boolean nativeAudio,
        Map<String, Object> extraParams
) {

    public static GenerationRequest video(String prompt, double durationSec, String aspectRatio,
                                          String firstFrame, Long seed, Boolean nativeAudio) {
        return new GenerationRequest("VIDEO", prompt, durationSec, aspectRatio, null, null, seed,
                firstFrame, null, java.util.List.of(), nativeAudio, Map.of());
    }

    public static GenerationRequest image(String prompt, int w, int h, Long seed) {
        return new GenerationRequest("FIRST_FRAME", prompt, null, null, w, h, seed,
                null, null, java.util.List.of(), null, Map.of());
    }
}
