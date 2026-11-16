# playlet-comfyui 模块地图

## src/main/java/org/playlet/comfyui/template

- `WorkflowPruner.java` - 提交前图剪枝器：关槽 = 可选链接删除 / 链节点旁路改接 / 纯生产节点被必填消费端钉住，末尾孤儿 GC；另含开关常量折叠 `foldConstantSwitches`。
- `WorkflowParameterizer.java` - 自动参数化规则引擎：硬编码值 → `{{name}}`/`{{num:name}}`/`{{bool:name}}` 占位符（拓扑定正负提示词、Primitive 沿开关/数学表达式追踪命名），工作流级常量保持硬编码并入报告。
- `WorkflowImporter.java` - 简化导入器：按节点分组提取可人工填写的字面量参数（跳过连线），生成可写回的 jsonPath（如 `3/inputs/seed`），落盘原文 + `<name>.meta.json`（含用户填写的类型与模型名）；`applyValueByPath` 按 jsonPath 写回原文（fail-loud，拒覆盖连线/新增字段）。不再参数化/剪枝/自检，不依赖 ComfyUI 在线。
- `WorkflowLibraryService.java` - 模板库业务：内置/用户模板的列表（含 type/model）、详情（type + model + 解析元数据）、导入（IMAGE/VIDEO 类型与模型名均必填——agent 依赖模型名做模型级提示词优化；备份回滚）、删除。

## src/main/java/org/playlet/comfyui/backend

- `DefaultGenerationBackend.java` - 唯一在用的提交通道（图片/视频共用）：读原始工作流 → 按前端传的 jsonPath 写回参数值 → 提交 ComfyUI；`awaitResult` 轮询 history 取产物。不做模型特化/参数推导（旧 image/video 专用 backend 已废弃）。
