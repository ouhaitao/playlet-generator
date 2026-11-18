# playlet-frontend 模块地图

## 目录结构概览
- `src/components/` - 业务组件
- `src/components/ui/` - 基础 UI 组件库（Button, Input 等），**非必要不读取**
- `src/hooks/` - 自定义 Hooks
- `src/lib/` - 核心工具与 API 封装
- `src/types/` - TypeScript 类型定义

## 核心文件职责

### 基建层 (Infrastructure)
- `src/lib/api.ts` - Axios 请求实例封装，包含全局拦截器和基础 API 方法（含 drafts.translate、prompt.generate、storyboard.addShot）。
- `src/lib/utils.ts` - 全局工具函数（格式化、数据转换等）。
- `src/types/domain.ts` - 核心业务类型定义（包含 Entity, DTO 等；Shot.negativePrompt、ShotVersion.paramsJson、StoryboardAddShotSpec）。

### 逻辑层 (Hooks)
- `src/hooks/useProgressEvents.ts` - 处理长连接/SSE 的进度事件监听 Hook。
- `src/hooks/useTheme.ts` - 主题切换与状态管理 Hook。

### UI 业务组件层 (Components)
- `src/components/AppShell.tsx` - 应用的主框架/布局外壳。
- `src/components/ComposePanel.tsx` - 排版/编排控制面板。
- `src/components/DraftGate.tsx` - 草稿校验与状态拦截组件（提示词确认门 §6.3）：agent 优化/手动修改（manual 新草稿）、槽位负向提示折叠展示、历史版本点击弹框看正/负向提示词。
- `src/components/ProjectCreateDialog.tsx` - 新建项目弹窗。
- `src/components/ProjectDetail.tsx` - 项目详情展示视图。
- `src/components/ReviewPanel.tsx` - 审核/预览面板（闸门②③）：内嵌工作流选择 + 参数表单，提交 `{workflow, params}` 生成图片/视频；版本卡支持复制 artifactPath、只读回放该版工作流参数（paramsJson）、点击图片开原尺寸预览弹框（容器不超浏览器短边）。
- `src/components/ShotAddDialog.tsx` - 手动添加分镜弹窗（闸门①）：参数全用户自填（画面/负向提示词、序、时长、景别、运镜、台词、角色、场景），提交 storyboard.addShot 直接写槽不产草稿。
- `src/components/StoryboardConfirm.tsx` - 分镜确认与操作组件：自动/重新切镜、批量确认、逐行改，另带「手动加分镜」入口（ShotAddDialog）。
- `src/components/WorkflowLibrary.tsx` - 工作流模板库主界面：导入时选 IMAGE/VIDEO 类型并填模型名（必填），列表展示类型/模型/来源。
- `src/components/WorkflowParamPanel.tsx` - 审核台内嵌的工作流选择下拉 + 按解析元数据渲染的参数表单（默认值预填，导出 buildParams 转成 jsonPath→值）；CLIPTextEncode 组排最前、输入框占满整行，`renderFieldExtra` 钩子供审核台在提示词字段下注入按钮区。
- `src/components/PromptFieldTools.tsx` - 审核台 CLIPTextEncode 字段下方工具条：填入分镜正/负向提示词（有内容用 `,` 连接）、生成提示词弹框（标识+反馈意见→/api/prompt/generate，结果可填入）、译英/译中（按字段内容缓存：未变不重调接口，任何变更即失效；结果仅展示）。
- `src/components/WorkflowReadonlyView.tsx` - 只读工作流参数回放：解析版本 paramsJson `{workflow, params}` → 拉工作流元数据 → 按节点分组渲染（值 = params[jsonPath] ?? 默认值），全部输入禁用，CLIPTextEncode 排最前。
