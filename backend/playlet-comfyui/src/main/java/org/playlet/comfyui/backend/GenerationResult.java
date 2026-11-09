package org.playlet.comfyui.backend;

/**
 * 生成结果：产物在 ComfyUI 服务器上的位置 + 元数据。
 *
 * @param filename  ComfyUI output 文件名
 * @param subfolder 子目录（可空）
 * @param type      output / temp
 * @param durationSec 实际时长（视频/音频）
 */
public record GenerationResult(
        String filename,
        String subfolder,
        String type,
        Double durationSec
) {
}
