# playlet-comfyui 模块地图

## src/main/java/org/playlet/comfyui/template

- `WorkflowPruner.java` - 提交前图剪枝器：关槽 = 可选链接删除 / 链节点旁路改接 / 纯生产节点被必填消费端钉住，末尾孤儿 GC；另含开关常量折叠 `foldConstantSwitches`。
- `WorkflowParameterizer.java` - 自动参数化规则引擎：硬编码值 → `{{name}}`/`{{num:name}}`/`{{bool:name}}` 占位符（拓扑定正负提示词、Primitive 沿开关/数学表达式追踪命名），工作流级常量保持硬编码并入报告。
- `WorkflowImporter.java` - 导入编排：结构自检 → 参数化 → sidecar 槽位提取 → 2^k 组合剪枝自检 → 原地落盘模板与 `*.options.json`。
- `WorkflowLibraryService.java` - 模板库业务：内置/用户模板的列表、详情、导入（备份回滚）、删除。
