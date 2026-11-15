# WorkflowLibrary API（`WorkflowLibraryController`）

Base path: `/api/workflows`。工作流模板库（内置 + 用户导入）；导入需 ComfyUI 在线（`playlet.comfyui.base-url`）。

## GET /api/workflows

模板库列表（内置 + 用户导入）。

Response `200`：`Entry[]`

```json
[{ "name": "minimaxh3/minimax_h3_ti2v", "source": "builtin",
   "slots": [{ "name": "audio", "type": "AUDIO", "sourceKind": "chain", "hostId": "105:91" }],
   "importedAt": "2026-11-15T07:39:45Z", "bytes": 8641, "overridden": false }]
```

`sourceKind`：`source`（纯生产节点，无链接型输入）| `chain`（链节点，可旁路改接）。

## GET /api/workflows/{*name}

模板详情（槽位 + 参数化后的 API-JSON 原文）。

Response `200`：

```json
{ "name": "mypack/flow", "source": "user",
  "slots": [{ "name": "vae", "type": "VAE", "sourceKind": "source", "hostId": "144:136" }],
  "workflow": { "<节点id>": { "class_type": "...", "inputs": { "...": "{{prompt}}" } } } }
```

不存在或内置模板加载失败 → `400 { "message": "..." }`。

## POST /api/workflows?name={name}

导入工作流。Body = ComfyUI Dev Mode「Save (API Format)」导出的**全填版** JSON 原文（非 multipart）。

导入流程：结构自检 → 自动参数化 → 提取 sidecar 槽位清单 → 2^k（k≤4）组合剪枝自检 → 原地落盘 `<name>.json` + `<name>.options.json`。

剪枝语义（提交前按槽位开关改写图）：
- 关槽 = 关其源节点；源被必填消费端依赖且无同类型链接可改接时（如与解码链共享的 `VAELoader`）**钉住源节点**，只断可选链接，源去留由孤儿 GC 决定；
- 同类型改接目标不唯一（歧义）→ 拒绝导入，`problems` 报错（fail-loud，不猜）。

Response `200`：`ImportReport`

```json
{ "name": "mypack/flow", "ok": true, "problems": [],
  "slots": [{ "name": "audio", "type": "AUDIO", "sourceKind": "chain", "hostId": "130" }],
  "substitutions": ["144:136.prompt → {{prompt}}", "129.noise_seed → {{num:seed}}"],
  "notes": ["119.vae_name 保持硬编码（工作流级常量）"] }
```

校验/剪枝自检失败 → 仍 `200`，`ok=false` + `problems[]`（如 `combination [vae]: ...`）；模板名不合法 → `400 { "message": "..." }`。

## DELETE /api/workflows/{*name}

删除用户导入的模板（及其 sidecar）。内置模板只读 → `400 { "message": "只能删除用户导入的模板（内置模板只读）: ..." }`。成功 → `204` 无 body。
