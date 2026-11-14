package org.playlet.api.web;

/**
 * 推送给前端的事件（/ws/events）。
 *
 * <p>镜头生成事件：started | progress | completed | failed（versionId/shotId 维度）。
 * 项目合成事件（BACKLOG ②）：compose_started | compose_progress | compose_completed |
 * compose_failed（projectId 维度，versionId/shotId 为 null）。
 *
 * @param type          事件类型（见上）
 * @param projectId     项目 id（合成事件必填；镜头事件可为 null）
 * @param versionId     ShotVersion id
 * @param shotId        Shot id
 * @param comfyPromptId ComfyUI prompt_id（进度关联）
 * @param percent       0~100，仅 progress 类有值
 * @param message       描述/错误信息（合成进度时为阶段名 concat|mix|subtitle）
 * @param artifactPath  产物相对路径，仅 completed 类有值（合成为相对存储根目录）
 */
public record ProgressEvent(
        String type,
        Long projectId,
        Long versionId,
        Long shotId,
        String comfyPromptId,
        Double percent,
        String message,
        String artifactPath
) {
    public static ProgressEvent started(Long versionId, Long shotId, String promptId) {
        return new ProgressEvent("started", null, versionId, shotId, promptId, null, null, null);
    }

    public static ProgressEvent progress(Long versionId, Long shotId, String promptId, double percent) {
        return new ProgressEvent("progress", null, versionId, shotId, promptId, percent, null, null);
    }

    public static ProgressEvent completed(Long versionId, Long shotId, String promptId, String artifact) {
        return new ProgressEvent("completed", null, versionId, shotId, promptId, 100.0, null, artifact);
    }

    public static ProgressEvent failed(Long versionId, Long shotId, String promptId, String message) {
        return new ProgressEvent("failed", null, versionId, shotId, promptId, null, message, null);
    }

    public static ProgressEvent composeStarted(Long projectId) {
        return new ProgressEvent("compose_started", projectId, null, null, null, 0.0, null, null);
    }

    public static ProgressEvent composeProgress(Long projectId, String stage, double percent) {
        return new ProgressEvent("compose_progress", projectId, null, null, null, percent, stage, null);
    }

    public static ProgressEvent composeCompleted(Long projectId, String artifact) {
        return new ProgressEvent("compose_completed", projectId, null, null, null, 100.0, null, artifact);
    }

    public static ProgressEvent composeFailed(Long projectId, String message) {
        return new ProgressEvent("compose_failed", projectId, null, null, null, null, message, null);
    }
}
