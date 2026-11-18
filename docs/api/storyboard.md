# Storyboard API（`StoryboardController`）

Base path: `/api/projects/{projectId}/storyboard`。闸门①分镜表：切镜出整表 → 展示/编辑 → 批量或逐镜确认草稿写槽 → 项目状态推进 STORYBOARD → STORYBOARD_CONFIRMED。

## POST /generate

按项目梗概切镜（LLM 切镜 + 逐镜提示词草稿；LLM 不可用时回退「按句切分」，Response `usedLlm=false`）。重复调用替换当前分镜表（连同草稿）；已有镜头进入生成流程 → `400`。

Response `200`：`{ "rows": [Row], "usedLlm": true }`。

## GET /table

整表查询。Response `200`：`Row[]`。

`Row`：`{ shotId, ord, durationSec, shotSize, cameraMove, dialogue, speaker, scene, visualPrompt, negativePrompt, draftId, draftState, draftVersion, draftContent, draftNegative, confirmed, shotState }`（`negativePrompt`/`draftNegative` 供前端折叠展示负向提示）。

## POST /confirm-all

批量确认所有 DRAFT 草稿（写入槽位）。Response `200`：`{ "confirmedNow": n, "stillPending": n, "projectState": "..." }`。全部确认后 `projectState=STORYBOARD_CONFIRMED`。

## POST /shots/{shotId}/prompt

逐行改提示词：修改结果成为**新草稿**（再确认才写槽）。body：`{ "content": "..." }`。Response `200`：该行 `Row`。

## POST /shots

手动添加分镜（参数全部用户自填，不经 LLM 切镜/优化）。提示词**直接写入槽位**（`visualPrompt`/`negativePrompt`），不产草稿——手工建的镜头「槽位有内容即视作已定稿」。`ord` 缺省/越界追加表尾；占用已有序号时后续镜头顺延后移。项目状态 DRAFT → STORYBOARD。

```json
{ "ord": 2, "durationSec": 5, "shotSize": "中景", "cameraMove": "固定",
  "visualPrompt": "必填", "negativePrompt": "可空", "dialogue": "可空",
  "speaker": "角色名，可空", "scene": "场景名，可空" }
```

Response `200`：新镜头对应的 `Row`。`visualPrompt` 空白 → `400 { "message": "…必填" }`；项目不存在 → `404`。
