package org.playlet.domain.model;

/** 项目模式：分镜 / 一镜到底。两者统一为有序镜头序列，差异在首帧来源与转场。 */
public enum ProjectMode {
    /** 分镜：每镜首帧独立生成，镜头间跳切 + 转场 */
    STORYBOARD,
    /** 一镜到底：续帧链，零转场硬接 */
    ONE_SHOT
}
