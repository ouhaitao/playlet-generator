# 短剧生成平台 —— 待办与交接清单

更新：2026-11-13 16:00（Asia/Shanghai）
定位：**执行级清单**——还剩什么、从哪入手、怎样算完成。设计依据见 `DESIGN.md`，构建/运行坑见 `BUILDING.md`，本文件不重复二者内容，只做索引与增量。

---

## 0. 交接快速上手

**构建/运行（细节与坑见 BUILDING.md）**
- 共享目录 `\\170.240.110.32\playlet-generator` 不能直接构建，先 robocopy 到本地镜像：
  `robocopy '\\170.240.110.32\playlet-generator\backend' "$env:TEMP\playlet-build\backend" /MIR /XD target node_modules dist .vite data`
- 离线测试：`mvn -B -f "$env:TEMP\playlet-build\backend\pom.xml" test '-Dtest=!H3VideoSpikeTest,!AnimaImageGenerationTest,!ComfyClientSpikeTest,!RealWorkflowImportTest,!WorkflowImportCliTest' '-Dsurefire.failIfNoSpecifiedTests=false'`
- **跑应用前必须整 reactor `mvn install -DskipTests`**（坑 #6：`-pl` 单模块跑会用本地仓库旧 jar）
- 跑应用：`$env:PLAYLET_STORAGE_DIR="$env:TEMP\playlet-data"; mvn -B -pl playlet-api spring-boot:run`（先杀 8080 残留）
- 前端：镜像同上（`/XD node_modules dist .vite`）；`npm run build` 验证；`npm run dev` = 5173，proxy `/api /media /ws` → 8080
- **会话 PATH 会过期**：`$env:Path = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Environment').Path + ';' + (Get-ItemProperty 'HKCU:\Environment').Path + ';' + $env:Path`

**环境地址**：ComfyUI `http://127.0.0.1:28070`（output/input 在 `F:\ai\comfyui\ComfyUI_windows_portable\ComfyUI\`）；**LLM 未配置**（`PLAYLET_LLM_BASE_URL/API_KEY/MODEL` 未设 → agent 全走确定性回退）。

**会话策略**：danger-full-access；**永远不要**传 `sandbox_permissions`/`justification`（会报 invalid escalation）。用户用中文交流；用户会突然结束会话（"我要下班了"），收尾时杀后台 job、留可恢复现场。

**当前运行时（写本文件时）**：后端 8080 = 后台 job `pwsh-00d7519944fe41d4`（日志 `$env:TEMP\playlet-app.log`）；前端 dev 5173 = job `pwsh-7af08cb16f2340f6`（日志 `$env:TEMP\playlet-dev.log`）。恢复会话后先 `Get-NetTCPConnection -LocalPort 8080/5173` 确认存活，死了就按上面命令重起。

**库内冒烟数据**（`$env:TEMP\playlet-data\playlet.db`）：project 3 / shot 3 = 全链样例（v4 首帧 APPROVED → v5 视频 APPROVED，shot=APPROVED）；shot 4 = §6.3 门样例（draft v1 CONFIRMED 已写槽、v2 REJECTED 带意见）。projects 1/2 是早期脏数据（shot 1/2 有遗留 `firstFrameImage=example.png`），可删。

---

## 1. 已完成（含验证方式）

| 模块 | 状态 | 验证 |
|---|---|---|
| 工作流工具链：剪枝/开关折叠/自动参数化/导入器 + 工作流库 REST + 前端导入页 | ✅ | 26 离线测试 + 真实导入 anima/illustrious/H3 |
| 异步生成 + WS 进度转发 + 崩溃恢复重挂 | ✅ | 真实冒烟 |
| 首帧阶段闸门②：generateFirstFrame → 审核通过 promote 进 input + 回填 firstFrameImage | ✅ | E2E（v4 图 → approve → input 文件存在 → v5 视频 DONE） |
| 闸门③视频审核 → APPROVED；/media 静态服务 | ✅ | E2E + HEAD 200 |
| §6.3 确认门后端：PromptOptimizer（LLM+回退）/ DraftController（optimize/confirm/reject）/ V4 negative 列 / extraParams.negative 进生成链 | ✅ | 8 离线测试 + 真实全链冒烟（门拦截/写槽/重优化/驳回/重复 confirm 400） |
| 设计地基：令牌（亮默认+暗可切）+ ui 原语 7 件 + AppShell + 四页面组件化 + DraftGate 逐镜门 UI | 🔶 | tsc+vite 绿、dev server 已验证 CSS；**浏览器实点未做** |

测试基线：后端 **34**（comfyui 26 + agent 8）全绿；前端 build 绿。

---

## 2. 待办（建议顺序，含完成标准）

### ① 分镜表自动生成 + 闸门①批量确认页
- 后端（playlet-agent）：切镜服务——LLM：梗概 → 镜头数组 JSON（粗糙 visual_prompt/duration/景别/运镜/角色/场景）；duration 对齐 FRAME_GRID（H3≈5s/段）；firstFrameSource 按 mode 推导（ONE_SHOT 且 ord>1 → PREV_LAST_FRAME）。**回退形态（无 LLM）**：按句号/换行切句，一句一镜、默认 5s。切镜后逐镜自动 `PromptOptimizer.optimizeShotPrompt` 产草稿。
- 前端：新页 StoryboardConfirm——整表（序/时长/景别/运镜/草稿状态徽章）+ 批量确认（循环 confirm）+ 逐行改（改→新草稿→再确认）；全确认 → 项目 state 推进（Project.state 字段已存在）。
- 完成标准：梗概 → 整表 → 批量确认 → 各 shot.visualPrompt/negative 落槽；离线测试（切镜解析容错 + 回退切句）。

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
- LLM 未配置：agent 全部走回退；①③ 的真效果需真模型验证。
- 用户网络偶断：重试即可。

---

## 4. M1 闭环验收清单

- [ ] 梗概 → 切镜表 → 闸门①批量确认
- [ ] 每镜 ⓪提示词门 → ②首帧 → ③视频 全 APPROVED
- [ ] 一键合成可播成片
- [ ] （有 LLM 时）对话建项目 ≤3 轮出表
- [ ] 浏览器实点全部页面（含 DraftGate/审核台新 UI）
- [ ] README + LICENSE，干净机器可 clone 跑通
