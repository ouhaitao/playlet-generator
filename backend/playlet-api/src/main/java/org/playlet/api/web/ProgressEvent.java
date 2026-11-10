package org.playlet.api.web;

/**
 * 推送给前端的事件（/ws/events）。
 *
 * @param type          started | progress | completed | failed
 * @param versionId     ShotVersion id
 * @param shotId        Shot id
 * @param comfyPromptId ComfyUI prompt_id（进度关联）
 * @param percent       0~100，仅 progress 有值
 * @param message       描述/错误信息
 * @param artifactPath  产物相对路径，仅 completed 有值
 */
public record ProgressEvent(
        String type,
        Long versionId,
        Long shotId,
        String comfyPromptId,
        Double percent,
        String message,
        String artifactPath
) {
    public static ProgressEvent started(Long versionId, Long shotId, String promptId) {
        return new ProgressEvent("started", versionId, shotId, promptId, null, null, null);
    }

    public static ProgressEvent progress(Long versionId, Long shotId, String promptId, double percent) {
        return new ProgressEvent("progress", versionId, shotId, promptId, percent, null, null);
    }

    public static ProgressEvent completed(Long versionId, Long shotId, String promptId, String artifact) {
        return new ProgressEvent("completed", versionId, shotId, promptId, 100.0, null, artifact);
    }

    public static ProgressEvent failed(Long versionId, Long shotId, String promptId, String message) {
        return new ProgressEvent("failed", versionId, shotId, promptId, null, message, null);
    }
}
