# WorkflowLibrary API（`WorkflowLibraryController`）

Base path: `/api/workflows`。工作流模板库（内置 + 用户导入）。导入为纯 JSON 解析，**不需要 ComfyUI 在线**。

导入器只按节点分组提取所有可人工填写的字面量输入参数（跳过连线引用 `[nodeId, slot]`），为每个参数生成可直接写回原文的 jsonPath（如 `3/inputs/seed`）。原文落盘 `<name>.json`，解析元数据落盘 `<name>.meta.json`。生成时用户填的值按 jsonPath 写回原文后提交 ComfyUI（见 `generation.md`）。

`type` 枚举：`IMAGE` | `VIDEO`（导入时由用户指定，区分图片/视频工作流）。`model`：导入时用户填写的模型名（必填，如 `anima` / `minimax_h3`），提示词优化 agent 据此做模型级专门优化。

## GET /api/workflows

模板库列表（内置 + 用户导入）。可选 `?type=IMAGE|VIDEO` 按类型过滤（审核台下拉框用）。

Response `200`：`Entry[]`

```json
[{ "name": "mypack/flow", "source": "user", "type": "IMAGE", "model": "anima",
   "importedAt": "2026-11-16T07:39:45Z", "bytes": 8641, "overridden": false }]
```

`type`/`model` 为 null 表示无解析元数据（旧式内置模板），不可用于生成。

## GET /api/workflows/{*name}

模板详情：类型 + 模型 + 解析后的参数元数据（前端据此渲染填写表单）。

Response `200`：

```json
{ "name": "mypack/flow", "source": "user", "type": "IMAGE", "model": "anima",
  "importedAt": "2026-11-16T07:39:45Z",
  "groups": [{ "nodeId": "3", "classType": "KSampler", "nodeTitle": "K采样器",
    "fields": [{ "inputName": "seed", "kind": "NUMBER",
                 "jsonPath": "3/inputs/seed", "defaultValue": 42 }] }] }
```

`kind`：`STRING` | `NUMBER` | `BOOLEAN`。`defaultValue` = 导入时原文中的值。

不存在或内置模板加载失败 → `400 { "message": "..." }`。

## POST /api/workflows?name={name}&type={IMAGE|VIDEO}&model={model}

导入工作流。Body = ComfyUI Dev Mode「Save (API Format)」导出的 JSON 原文（非 multipart）。`model` 必填（落盘进 meta，供提示词优化 agent 做模型级优化）。

导入流程：JSON 解析 → 画布格式粗检 → 按节点分组提取字面量参数 → 原文落盘 `<name>.json` + 元数据落盘 `<name>.meta.json`（失败自动回滚，同名覆盖前备份）。

Response `200`：`ImportReport`

```json
{ "name": "mypack/flow", "ok": true, "problems": [],
  "type": "IMAGE", "model": "anima", "nodeCount": 12, "fieldCount": 34 }
```

校验失败（画布格式 / 无可填参数 / 类型或模型缺失）→ 仍 `200`，`ok=false` + `problems[]`；模板名不合法等参数错误 → `400 { "message": "..." }`。

## DELETE /api/workflows/{*name}

删除用户导入的模板（及其 `.meta.json`）。内置模板只读 → `400 { "message": "只能删除用户导入的模板（内置模板只读）: ..." }`。成功 → `204` 无 body。
