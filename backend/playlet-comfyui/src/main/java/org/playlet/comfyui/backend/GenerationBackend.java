package org.playlet.comfyui.backend;

import org.playlet.domain.model.Capability;

import java.util.Set;

/**
 * 生成后端适配器接口（DESIGN.md §4 ③层）。
 * 模型差异全部封死在实现里；主流程只依赖本接口与能力声明，永不出现模型名。
 *
 * <p>可拓展性验收标准：新增一个视频模型 = 写一个实现 + 声明能力 + 放工作流模板，
 * 主流程与前端零改动。
 */
public interface GenerationBackend {

    /** 后端标识，如 MINIMAX_H3 / IMAGE / TTS / SEEDVR2。 */
    String id();

    /** 能力声明：主流程据此决定启用哪些阶段、哪些槽位对用户可见。 */
    Set<Capability> capabilities();

    default boolean supports(Capability c) {
        return capabilities().contains(c);
    }

    /** 单次生成最大时长（秒）；无硬限制返回空。 */
    default java.util.Optional<Double> maxDurationSec() {
        return java.util.Optional.empty();
    }

    /**
     * 时长对齐约束描述（如 H3 的 17k+5 帧 @24fps）；用于提交前校验与 UI 提示。
     * 返回 null 表示无约束。
     */
    default String frameGridNote() {
        return null;
    }

    /**
     * 提交前校验抽象请求（时长对齐、能力匹配、非法组合等）。
     * 不通过抛 {@link IllegalArgumentException}，带明确错误信息。
     */
    default void validate(GenerationRequest req) {
        // 默认无额外校验
    }

    /**
     * 提交生成并阻塞等待完成，返回产物位置。
     * 实现内部完成：模板选择 → 占位符填充 → 提交 → 轮询 → 取产物。
     */
    GenerationResult generate(GenerationRequest req);
}
