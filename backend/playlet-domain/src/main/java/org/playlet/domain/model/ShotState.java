package org.playlet.domain.model;

/** 镜头状态机：待生成 → 首帧中 → 首帧待审 → 视频中 → 视频待审 → 通过 / 回退。 */
public enum ShotState {
    PENDING,
    FIRST_FRAME_GENERATING,
    FIRST_FRAME_REVIEW,
    VIDEO_GENERATING,
    VIDEO_REVIEW,
    TTS_GENERATING,
    TTS_REVIEW,
    APPROVED,
    ROLLED_BACK
}
