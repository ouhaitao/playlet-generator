# Draft API（`DraftController`）

Base path: `/api/drafts`。提示词草稿确认门（DESIGN.md §6.3）：草稿（DRAFT）→ 确认（CONFIRMED，写入槽位）/ 驳回（REJECTED，带意见回 agent 重优化）。未确认的草稿不进入生成链路。

## GET /api/drafts?ownerType=SHOT&ownerId={id}

列某镜头的提示词草稿（版本号倒序）。Response `200`：`PromptDraft[]`（含 `content`、`negativePrompt`、`state`、`versionNo`、`feedback`）。

## POST /api/drafts/optimize

触发 agent 优化，产出新草稿（旧未决草稿作废）。

```json
{ "ownerType": "SHOT", "ownerId": 1, "fieldName": "visual_prompt", "feedback": "可空" }
```

Response `200`：新 `PromptDraft`。

## POST /api/drafts/manual

用户手改文本直接成新草稿（不擅自重优化）。

```json
{ "shotId": 1, "content": "改后的提示词" }
```

Response `200`：新 `PromptDraft`。

## POST /api/drafts/{id}/confirm · POST /api/drafts/{id}/reject

确认写入槽位 / 驳回记录意见。reject body：`{ "feedback": "..." }`。Response `200`：`PromptDraft`。

## POST /api/drafts/translate

翻译提示词（审核台 CLIPTextEncode 输入框的中英互译按钮）。能力：`PromptTranslator.toEnglish` / `toChinese`。**结果仅前端展示、不落库**；前端按字段内容缓存复用——内容未变再点不重复调接口，内容有任何变更（含编辑后又还原）即失效重调。

```json
{ "text": "待翻译文本", "target": "EN" }   // EN=译英，ZH=译中
```

Response `200`：`{ "translated": "..." }`。

`text` 空白 → `400`；`target` 非 EN/ZH → `400`；已是目标语言/无需翻译 → 原样返回；LLM 不可用或输出不合格 → `400 { "message": "提示词翻译失败…" }`（fail-loud，无降级）。
