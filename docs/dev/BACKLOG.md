# 短剧生成平台 —— 待办与交接清单

更新：2026-11-13 17:45（Asia/Shanghai）
定位：**执行级清单**——还剩什么、从哪入手、怎样算完成。设计依据见 `DESIGN.md`，构建/运行坑见 `BUILDING.md`，本文件不重复二者内容，只做索引与增量。

---

## 0. 交接快速上手

**构建/运行**:见 BUILDING.md

**会话策略**：danger-full-access；**永远不要**传 `sandbox_permissions`/`justification`（会报 invalid escalation）。

**当前运行时（写本文件时）**：无后台进程（上一轮冒烟验证完已收尾）。恢复会话后先 `Get-NetTCPConnection -LocalPort 8080/5173` 确认，死了就按上面命令重起。

**库内冒烟数据**（`\gitExclude\playlet-data\playlet.db`）：
- project 3 / shot 3 = 全链样例（v4 首帧 APPROVED → v5 视频 APPROVED，shot=APPROVED）；shot 4 = §6.3 门样例（draft v1 CONFIRMED 已写槽、v2 REJECTED 带意见）。
- **project 4 = 闸门①样例（2026-11-13 新增）**：3 镜（切句回退、时长已对齐 H3 网格 5.167s）、shot 5 走过「逐行改→v2 草稿→批量确认写槽」，项目 state=STORYBOARD_CONFIRMED。
- projects 1/2 是早期脏数据（shot 1/2 有遗留 `firstFrameImage=example.png`），可删。

---

## 1. 已完成（含验证方式）

| 模块 | 状态 | 验证 |
|---|---|---|
| 工作流工具链：剪枝/开关折叠/自动参数化/导入器 + 工作流库 REST + 前端导入页 | ✅ | 26 离线测试 + 真实导入 anima/illustrious/H3 |
| 异步生成 + WS 进度转发 + 崩溃恢复重挂 | ✅ | 真实冒烟 |
| 首帧阶段闸门②：generateFirstFrame → 审核通过 promote 进 input + 回填 firstFrameImage | ✅ | E2E（v4 图 → approve → input 文件存在 → v5 视频 DONE） |
| 闸门③视频审核 → APPROVED；/media 静态服务 | ✅ | E2E + HEAD 200 |
| §6.3 确认门后端：PromptOptimizer（LLM+回退）/ DraftController（optimize/confirm/reject）/ V4 negative 列 / extraParams.negative 进生成链 | ✅ | 8 离线测试 + 真实全链冒烟（门拦截/写槽/重优化/驳回/重复 confirm 400） |
| **①分镜表自动生成 + 闸门①批量确认页**：StoryboardPlanner（LLM 镜头数组 JSON + 回退切句）→ StoryboardService（落 Shot 行、时长 snapDuration 对齐 H3 网格、firstFrameSource 按 mode 推导、逐镜自动出草稿）→ `/api/projects/{id}/storyboard/{generate,table,confirm-all,shots/{sid}/prompt}` → 前端 StoryboardConfirm 页（整表+批量确认+逐行改）+ ProjectDetail ①/② 视图切换；DraftController 事务逻辑抽成 DraftGateService 共用；全确认后 Project.state=STORYBOARD_CONFIRMED，逐行改回退 STORYBOARD | ✅ | 25 新离线测试（StoryboardPlannerTest 13 + StoryboardServiceTest 12）+ 真实冒烟（project 4：建项目→切镜 3 镜→逐行改 v2→批量确认→槽位/negative 落库→状态推进→幂等复确认） |
| 设计地基：令牌（亮默认+暗可切）+ ui 原语 7 件 + AppShell + 四页面组件化 + DraftGate 逐镜门 UI | 🔶 | tsc+vite 绿、dev server 已验证 CSS；**浏览器实点未做**（StoryboardConfirm 页同样未实点） |

测试基线：后端 **59**（comfyui 26 + agent 21 + api 12）全绿；前端 build 绿。

---

## 2. 待办（建议顺序，含完成标准）

### ② 合成 E2E + 入口
- ComposeService/FfmpegRunner **真实素材端到端未测**：先拿 shot 3 的 v5 mp4 手工跑 concat 验证可播；再接 `POST /api/projects/{id}/compose`（进度复用 WS 事件）。
- 前端：项目详情页"合成成片"按钮 + 成片播放器。
- 完成标准：多镜全 APPROVED 项目一键出可播成片文件。

### ③ 对话式建项目（§6.2 前半）
- 后端（playlet-agent）：填槽状态机编排（缺必填槽→针对性提问；LLM 写回；**一次性展示完整简报供改**）+ REST `/api/agent/...`。
- 前端：对话页 + 简报表单。
- **前置：用户配 LLM 三变量**；未配时只做回退表单（直接填简报建项目）。
- 完成标准：≤3 轮对话产出 project + 空 shots 骨架（有 LLM 时）。

### ④ 角色/场景管理页 + 参考图链
- UI：角色/场景增改、绑定音色；参考图三来源（库/上传/ComfyUI 生成）+ 审核（appearance/description 的草稿能力已备）。
- referenceImages 注入：H3/SD 后端消费 GenerationRequest.referenceImages（一致性锚点）。
- 完成标准：角色参考图生成→审核→注入该角色出场镜头的生成请求。

### ⑤ 音频三层：dialogue TTS + bgm（可放 M1.5）
- TTS 后端（ComfyUI TTS-Audio-Suite，音色库查询见 dsh-comfyui-workflows skill）+ TTS_GENERATING/TTS_REVIEW 状态接线 + 审核 UI。
- bgm 素材库上传 + 混音 + 字幕烧录。

### ⑥ 审核循环补全（§7）
- L4 回退改分镜：version reject regenLevel=L4 → shot ROLLED_BACK → 允许改提示词/时长 → 重进门；API + UI 均缺。
- 版本对比页（并排/滑块）。

### ⑦ 视觉精修（7 项契约，清单在 DESIGN.md §3 前端节）
- 时机：M1 闭环后；顺带产开源 README 截图。目标级别"干净的专业工具"，不做营销式华丽。

### ⑧ 开源准备
- README（快速开始/配置变量表/架构/截图）、LICENSE（用户定）。
- 决定 DESIGN.md/BUILDING.md 是否出 gitExclude（建议 DESIGN 进 docs/ 开源，BUILDING 留内部或脱敏）。
- 清理前端残留依赖 zustand / class-variance-authority（未使用）。

---

## 3. 阻塞 / 环境限制（非待办，勿重试）

- illustrious 真生成：缺 `waiIllustriousSDXL` checkpoint。
- LoRA-on 路径：本机无 LoRA 文件（fail-loud 是设计行为）。
- LLM 未配置：agent 全部走回退；①（切镜 LLM 路径）③ 的真效果需真模型验证。
- 用户网络偶断：重试即可。

---

## 4. M1 闭环验收清单

- [x] 梗概 → 切镜表 → 闸门①批量确认（2026-11-13，回退切句路径；LLM 路径待真模型）
- [ ] 每镜 ⓪提示词门 → ②首帧 → ③视频 全 APPROVED
- [ ] 一键合成可播成片
- [ ] （有 LLM 时）对话建项目 ≤3 轮出表
- [ ] 浏览器实点全部页面（含 DraftGate/审核台/StoryboardConfirm 新 UI）
- [ ] README + LICENSE，干净机器可 clone 跑通
