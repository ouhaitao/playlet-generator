# Generation API（`GenerationController`）

Base path: `/api`。触发生成（图片 + 视频），**异步**：提交即返回 GENERATING 版本，进度走 WebSocket（见 `ProgressEvent`），完成/失败由后端轮询 ComfyUI history 判定。

参数模型：前端选定已导入的工作流（见 `workflowlibrary.md`）并按解析元数据填写参数，以 `params: {jsonPath: value}` 提交。后端 `DefaultGenerationBackend` 读原始工作流 → 按 jsonPath 写回值（seed 也是其中一个普通参数，不再单独传）→ 提交 ComfyUI。图片与视频共用同一通道，差异只在工作流本身；图生视频的图片地址由用户直接填进视频工作流的图片参数（如 LoadImage.image）。

## POST /api/shots/{shotId}/generate-image

触发镜头图片生成。

Request Body：

```json
{ "workflow": "mypack/imageflow",
  "params": { "3/inputs/seed": 42, "6/inputs/text": "a cat", "5/inputs/width": 768 } }
```

Response `200`：`ShotVersion`（`genState=GENERATING`，`backend=DEFAULT`，`paramsJson` 留档 `{workflow, params}`）。

`workflow` 缺失 → `400`；镜头正在生成中 → `400`；写回失败（路径非法/字段不存在/目标是连线/值类型不支持）→ `400 { "message": "..." }`。

## POST /api/shots/{shotId}/generate-video

触发镜头视频生成。Request / Response 同 `generate-image`（`workflow` 为 type=VIDEO 的工作流）。

## POST /api/prompt/generate

生成提示词（审核台「生成提示词」按钮）。把四个字段构造成**一条消息**发 LLM（能力：`PromptOptimizer.generatePromptText`），原样返回输出文本。**纯能力接口，无业务耦合**：不落库、不产草稿、不写槽位。

Request Body：

```json
{ "model": "anima",              // 工作流元信息的模型名，可空（空→"未知"）
  "content": "1girl, rain",       // CLIPTextEncode 输入框当前内容，可空（空→从零生成）
  "flag": "正面",                 // 提示词标识（正面/负面），必填
  "feedback": "手部崩坏" }        // 用户反馈意见，可空
```

Response `200`：`{ "result": "优化后的提示词文本" }`。

`flag` 缺失 → `400`；LLM 不可用 / 返回空输出 → `400 { "message": "提示词生成失败…" }`。
