package org.playlet.domain.model;

/** 审核决定：通过 / 重生成 / 回退改分镜。 */
public enum ReviewDecision {
    APPROVE,
    REGENERATE,
    ROLLBACK
}
