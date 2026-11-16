# playlet-frontend 模块地图

## 目录结构概览
- `src/components/` - 业务组件
- `src/components/ui/` - 基础 UI 组件库（Button, Input 等），**非必要不读取**
- `src/hooks/` - 自定义 Hooks
- `src/lib/` - 核心工具与 API 封装
- `src/types/` - TypeScript 类型定义

## 核心文件职责

### 基建层 (Infrastructure)
- `src/lib/api.ts` - Axios 请求实例封装，包含全局拦截器和基础 API 方法。
- `src/lib/utils.ts` - 全局工具函数（格式化、数据转换等）。
- `src/types/domain.ts` - 核心业务类型定义（包含 Entity, DTO 等）。

### 逻辑层 (Hooks)
- `src/hooks/useProgressEvents.ts` - 处理长连接/SSE 的进度事件监听 Hook。
- `src/hooks/useTheme.ts` - 主题切换与状态管理 Hook。

### UI 业务组件层 (Components)
- `src/components/AppShell.tsx` - 应用的主框架/布局外壳。
- `src/components/ComposePanel.tsx` - 排版/编排控制面板。
- `src/components/DraftGate.tsx` - 草稿校验与状态拦截组件。
- `src/components/ProjectCreateDialog.tsx` - 新建项目弹窗。
- `src/components/ProjectDetail.tsx` - 项目详情展示视图。
- `src/components/ReviewPanel.tsx` - 审核/预览面板（闸门②③）：内嵌工作流选择 + 参数表单，提交 `{workflow, params}` 生成图片/视频。
- `src/components/StoryboardConfirm.tsx` - 分镜确认与操作组件。
- `src/components/WorkflowLibrary.tsx` - 工作流模板库主界面：导入时选 IMAGE/VIDEO 类型并填模型名（必填），列表展示类型/模型/来源。
- `src/components/WorkflowParamPanel.tsx` - 审核台内嵌的工作流选择下拉 + 按解析元数据渲染的参数表单（默认值预填，导出 buildParams 转成 jsonPath→值）。
