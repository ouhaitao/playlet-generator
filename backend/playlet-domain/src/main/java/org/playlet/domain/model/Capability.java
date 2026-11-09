package org.playlet.domain.model;

/**
 * 生成后端能力声明。主流程通过 supports() 决定启用哪些阶段、哪些槽位对用户可见，
 * 永不硬编码模型名。见 DESIGN.md §4.3。
 */
public enum Capability {
    IMAGE_TO_VIDEO,
    TEXT_TO_VIDEO,
    /** 首尾帧双约束（一镜到底续接质量关键） */
    FIRST_LAST_FRAME,
    /** 视频自带音频（联合音视频 latent） */
    NATIVE_AUDIO,
    AUDIO_REFERENCE,
    REFERENCE_TO_VIDEO,
    CONTROL_VIDEO,
    UPSCALE
}
