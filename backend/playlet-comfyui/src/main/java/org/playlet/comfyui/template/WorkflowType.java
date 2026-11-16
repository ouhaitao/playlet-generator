package org.playlet.comfyui.template;

/**
 * 工作流类型：区分图片生成与视频生成工作流。
 * 导入时由用户在前端指定，落盘在 &lt;name&gt;.meta.json 中；
 * 审核台按类型过滤工作流下拉框（图生图区只列 IMAGE，视频区只列 VIDEO）。
 */
public enum WorkflowType {
    IMAGE,
    VIDEO
}
