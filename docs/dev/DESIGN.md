# 短剧生成平台 —— 设计文档

> 状态：设计已评审，待进入编码。本文档是讨论成果的固化，不含代码。
> 最后更新：2026-10-30（第 2 轮修订：删除 QQbot 方案、确立能力声明式适配器架构、补全槽位表与音频分层）

## 0.0 配套流程图

`docs/diagrams/` 下三张图（DOT 源码 + PNG + SVG，用仓库内 Graphviz 渲染）：

| 图 | 文件 | 内容 |
|---|---|---|
| 图 1 | `flow-1-main-pipeline` | 端到端主流程与三道人工闸门（对应 §6.2） |
| 图 2 | `flow-2-adapter-capability` | 能力声明式适配器四层架构（对应 §4） |
| 图 3 | `flow-3-review-loop` | 审核—重生成循环与 L1~L5 分级（对应 §7） |

重新渲染：`dot -Tpng <name>.dot -o <name>.png`（另出 `-Tsvg`）。`_check_layout.py` 解析 SVG 校验节点/标签重叠，改动 DOT 后跑一遍。

## 0. 一句话概述

一个**单用户**的短剧生成平台：用户用自然语言描述剧情，agent 通过多轮对话补全"项目简报"并产出**分镜表**，再逐镜头驱动 ComfyUI 生成首帧图与视频，**每段人工审核通过后再生成下一段**，最后由后端 ffmpeg 合成为带字幕、配音、BGM 的成品。

## 1. 已确认的关键约束（决定了整体架构走向）

| 约束 | 结论 | 带来的简化 |
|---|---|---|
| 部署形态 | **外网部署**（playlet-generator + ComfyUI 都在外网）；单用户（用户本人） | **无鉴权、无多租户、无用户体系**，配置写死单用户 |
| 交互端 | 仅 Web 端 | 审核界面只在 Web 做，无需处理 QQ 发图/发视频的媒体接口限制 |
| LLM 位置 | **LLM 在内网**，内网可自由访问公网，但**严格监管、不能做反向隧道** | ComfyUI 在外网本地直连，只有 LLM 需要跨网 |
| LLM 接入 | **只保留 OpenAI 兼容接口一种方案**，base-url 可切换 | `LlmClient` 只需一个实现；QQbot/simple-robot/OneBot **全部删除** |
| 生成节奏 | 串行：审完第 N 段才生成第 N+1 段 | 状态机以"当前活跃镜头"为核心，逻辑简单 |
| 一致性方案 | 预生成角色库 + 用户上传参考图 + ComfyUI 工作流生成参考图 | 不训练 LoRA，靠参考图驱动 |

### 1.1 为什么删除 QQbot 方案（已论证，勿再回头）

原始设想是"系统与 QQ 里那个 LLM-bot 对话来当 LLM"。经核实该路径不成立或不划算：

1. **QQ 官方机器人开放平台没有"调用某个 bot 来对话"的 API**。其模型是反向的：机器人是**被动应答方**，消息发起方永远是真人用户（`openid`）。三条铁律：不支持 bot 对 bot、不支持程序伪装成用户、没有"以用户身份主动发消息给 bot"的接口。
2. **唯一能落地的路径是控制一个真实 QQ 个人号**（OneBot 协议端 NapCat/Lagrange/LLOneBot + simple-robot OneBot 组件）去私聊那个 bot。代价：个人号封号风险、长消息分片重组、QQ 消息**无 request-id** 需自己埋标记、Markdown 格式丢失、秒级延迟。
3. **致命伤**：目标 bot 的后端是一个**完整 agent**（有工具、多步推理、会反问），不是裸的 `POST /chat/completions`。而本项目 agent 层需要**可靠的结构化输出**（槽位填充要 JSON、分镜表要固定 schema）。把程序化 LLM 调用塞进聊天通道，无法保证拿回干净的结构化结果。
4. 内网"严格监管"，额外挂个人小号把内网 LLM 输出中转出去，运营风险画像与既有通道不同。

**结论**：LLM 走 OpenAI 兼容接口，`base-url` 可切换——在局域网时指向内网 LLM，在外网时指向公网 OpenAI 兼容 API（如 DeepSeek 官方 API）。同一份代码只切配置。成本上，短剧生成的开销大头是 GPU 视频生成（一段 5~10s 视频数分钟到十几分钟），LLM 的 token 费用可忽略。

> 保留的备选（未采用）：反转控制——playlet-generator 暴露带鉴权 REST API，由内网 DSH agent 出站调用。复用既有 QQ↔DSH 通道、不新增个人号，但会把 agent 的"大脑"移出本系统，产品形态改变。仅当公网 LLM API 完全不可用时再考虑。

## 2. 环境事实（已侦察核实）

- **可用生成能力**（当前多为 UI 图格式，尚未导出 API 格式）：

  | 环节 | 工作流 / 节点 | 状态 |
  |---|---|---|
  | 文生图（角色/场景/海报） | flux2_klein_text_to_image、hidream_o1_dev | 未提取 |
  | 参考图编辑（换装/换景/修脸） | flux2_klein_image_edit | 未提取 |
  | 文生视频 | wan2.2_14B_t2v、MiniMax H3 T2V | 未提取 |
  | 图生视频（首帧驱动） | wan2.2-ti2v、MiniMax H3 I2V | 未提取 |
  | **首尾帧生视频**（一镜到底关键） | `MinimaxHailuo03FirstLastFrameNode` | 仅 H3 有 |
  | 多图参考生视频（一致性） | `MiniMaxH3ReferenceToVideo`（`<Picture i>`/`<Video k>`/`<Audio j>` 标签） | 仅 H3 有 |
  | 控制视频（姿态/深度） | `Wan22FunControlToVideo` | 仅 Wan2.2 有 |
  | **联合音视频 latent** | `EmptyMiniMaxH3LatentAV`（video+audio，24fps，时长对齐 17k+5 帧网格） | 仅 H3 有 |
  | 画质修复/放大 | SeedVR2_upscale | ✅ 已提取 |
  | 台词/旁白配音（含音色克隆库） | TTS-Audio-Suite 自定义节点 | 已装 |
  | 合成/转场/字幕 | 不走 ComfyUI，后端 ffmpeg 实现 | 待开发 |

## 3. 技术选型（已核实版本，已拍板）

### 后端
- **Spring Boot 4.1.2** + **Spring AI 2.0.2**（已验证 `spring-ai-starter-model-openai:2.0.2` 的 POM 直接依赖 `spring-boot-starter-webclient:4.1.2`，是配套的一对）
- **Java 21 LTS**，Maven 管理
- **数据库：SQLite 文件模式**（单用户零运维；schema 用 Flyway 管理；媒体文件落文件系统，库中只存路径）
- **持久层：Spring Data JPA**
- **ComfyUI 集成：后端直连原生 HTTP/WebSocket API**（`POST /prompt` 提交、`GET /history/{id}` 取结果、`/ws` 收进度、`POST /upload/image` 传参考图）。**不依赖 DeepSeek Harness 插件**。
- **音视频：本地 ffmpeg**，经 `ProcessBuilder` 调用（可选 `bramp/ffmpeg-cli-wrapper` 封装）——拼接、转场、混音（含 ducking）、烧字幕
- **外部工具地址一律变量配置**（不硬编码）：ComfyUI base-url、ffmpeg 可执行文件路径、LLM base-url、媒体存储目录等，全部通过配置项（`application.yml` / 环境变量）注入。ffmpeg 当前未安装（见 §10.7），路径可配置才能指向安装位置；ComfyUI 地址与 LLM 端点同理，换环境只改配置不改代码
- **实时进度：WebSocket（STOMP）或 SSE**，推送生成进度与"审核就绪"事件
- **LLM：`spring-ai-starter-model-openai`**，单一 OpenAI 兼容实现，`base-url` 可配置切换

### 前端（已拍板，2026-11-13 修订）
- **React 18 + TypeScript + Vite + TailwindCSS 3 + TanStack Query**（zustand/cva 为脚手架残留，未使用，待清理）
- **UI 路线 = A（设计地基 + 后期精修）**，明确不引入 antd / Ant Design Pro / 任何整站模板：
  - Pro 是 UmiJS 脚手架而非组件库，接入 = 前端重做；其企业中后台气质、ProTable 分页数据契约、cssinjs 运行时、依赖树与版本迁移成本，均与"单用户本地创作工具"错位。
  - 组件库/模板是单向门：页面照组件 API 写，后期换 = 全量重写，故"最后再接模板"不成立；视觉皮肤才允许延期。
- **分层约定**（换肤/精修只动前两层，页面文件基本不动）：
  1. 令牌层：`src/index.css`（`:root`/`.dark` HSL 变量，亮色默认、暗色可切、localStorage 持久化）+ `tailwind.config.js`（语义色/圆角/阴影/`text-2xs` 映射）。
  2. 原语层：`src/components/ui/`（button/card/input/badge/table/progress/states），手写约 300 行、零额外依赖；复杂交互件（Dialog/Select/Tooltip/Dropdown/Tabs）需要时引 Radix（headless，只管行为与无障碍）或按 shadcn 模式拷源码套本令牌，**不整库引入**。
  3. 页面层只组合原语，禁止裸写样式 class。
- **密度**：紧凑工具风（正文 13px、元信息 11px `text-2xs`、控件 24/28/32px、表格行 28px）。
- **M1 闭环后的视觉精修清单（契约，勿漏项）**：①原生控件替换（file dropzone、Select、Switch）；②微交互（hover elevation/过渡/按压态）；③排版层级（字重/字距节奏）；④去边框化、底色分层+阴影阶；⑤状态语言（StatusDot/左侧色条/时间线）；⑥媒体衬底（MediaFrame：中性底+固定宽高比+圆角内衬）；⑦品牌锚点（logo mark + 主色点缀）。目标级别："干净的专业工具"（Linear/Raycast 式克制精致），不做营销式华丽；顺带产出开源 README 截图。

## 4. 架构：统一主流程 + 能力声明式适配器

### 4.1 决策依据

对比过两种实现方式：①主流程通用、模型差异作为额外配置；②每个模型定制一套流程。**选①，但不是朴素版本**。

**②为什么不行**：主流程里真正与模型相关的只有"提交生成任务"一个环节。其余（对话填槽、分镜表、审核循环、版本管理、ffmpeg 合成、字幕、混音、Web UI）全部与模型无关。为 N 个模型复制 N 套，意味着改一次审核状态机要改 N 处；而视频模型快速迭代（H3 刚出、Wan2.2、还有新的），定制流程追不上。

**朴素①的风险**：若抽象设计成"最低公分母"，模型特有能力会被浪费。

**决定性证据**：`MinimaxHailuo03FirstLastFrameNode` 支持首帧+尾帧双约束，Wan2.2 无对应节点。对一镜到底模式：H3 可做双约束续接（接缝质量高、漂移可控），Wan2.2 只能"上一段末帧当下一段首帧"（单侧约束、漂移更快）。硬编码任一方都会让另一个模型跑不了或浪费能力。同类差异还有 H3 的联合音视频 latent、SigmaShift（video/audio flow shift 分开调）、标签式多参考，以及 Wan2.2 的 FunControlToVideo。

### 4.2 四层结构

```
① 主流程层（模型无关）
   Project/Shot/Version/Review 状态机、分镜表、审核循环、ffmpeg 合成
        ↓ 只发抽象请求："给我 5s 图生视频，首帧=X，提示词=Y"
② 能力层 Capability（每个后端声明自己支持什么）
        ↓ 决定哪些阶段启用、哪些槽位对用户可见
③ 适配器层 Adapter（每个后端一个实现）
   把抽象请求翻译成具体 ComfyUI API-JSON；模型差异全部封死在这里
        ↓
④ 工作流模板层（参数化 API-JSON，属适配器的实现细节）
```

模块划分：

```
domain      领域模型与状态机：项目/角色/场景/分镜(Shot)/镜头版本(Version)/审核(Review)
llm-spi     LlmClient 接口 + OpenAiCompatibleLlmClient（唯一实现，base-url 可切）
agent       槽位填充编排：多轮对话 → 项目简报 → 分镜表
comfyui     ComfyClient(直连原生 API)
            GenerationBackend 接口（能力声明 + 抽象生成请求 → API-JSON）
            ├─ MiniMaxH3Backend
            ├─ Wan22Backend
            └─ SeedVR2Backend（修复放大）
            WorkflowTemplate 模板库（参数化 API-JSON）
media       ffmpeg 合成、三层音频混音、TTS 编排、字幕生成
api         REST + WebSocket
web         前端 SPA
```

### 4.3 能力声明（Capability）

每个生成后端声明其能力集合，**主流程通过询问能力来决定行为，绝不硬编码模型名**：

| 能力 | 含义 | H3 | Wan2.2 |
|---|---|---|---|
| `IMAGE_TO_VIDEO` | 首帧图生视频 | ✅ | ✅ |
| `TEXT_TO_VIDEO` | 文生视频 | ✅ | ✅ |
| `FIRST_LAST_FRAME` | 首尾帧双约束 | ✅ | ❌ |
| `NATIVE_AUDIO` | 视频自带音频 | ✅ | ❌ |
| `AUDIO_REFERENCE` | 音频参考条件 | ✅ | ❌ |
| `REFERENCE_TO_VIDEO` | 多图参考生视频 | ✅ | ❌ |
| `CONTROL_VIDEO` | 姿态/深度控制 | ❌ | ✅ |
| `MAX_DURATION_SEC` | 单次最大时长 | 待实测 | 待实测 |
| `FRAME_GRID` | 时长对齐约束 | 17k+5 帧 @24fps | 无 |
| `SUPPORTED_ASPECT_RATIOS` | 支持画幅 | 待实测 | 待实测 |

### 4.4 能力门控的阶段（capability-gated phases）

阶段序列统一，**启用哪些阶段由能力决定**：

| 阶段 | 启用条件 |
|---|---|
| 首帧图生成 | 通用，恒启用 |
| 视频生成 | 通用，恒启用 |
| **尾帧约束** | 仅 `supports(FIRST_LAST_FRAME)` → H3 启用，Wan2.2 自动跳过 |
| **原生音频处理** | 仅 `supports(NATIVE_AUDIO)` → H3 启用 |
| 画质修复放大 | 项目级开关（SeedVR2） |
| 台词 TTS | 仅该镜有台词 |

结果：一套状态机、一套审核循环、一套 UI；H3 发挥首尾帧与原生音频，Wan2.2 走单帧路径，两者都不被拉平、也不被复制。

### 4.5 模型额外配置的落点

1. **能力驱动 UI**：选了 H3，分镜表才出现"尾帧""原生音频"槽位；选 Wan2.2 则不显示。用户看不到不适用的选项，也填不出非法组合。
2. **扩展参数兜底**：适配器暴露 `extra_params`（JSON），装模型私有旋钮（如 H3 的 video/audio 两个 sigma shift 值）。主流程原样透传，不理解也不校验。
3. **校验在适配器内**：如 H3 时长须对齐帧网格、Wan2.2 不支持原生音频——提交前由适配器校验并给明确错误，不污染主流程。

**可拓展性验收标准**：新增一个视频模型 = 写一个适配器 + 声明能力 + 放工作流模板，**主流程与前端零改动**。

### 4.7 工作流模板：全填版 + sidecar 清单 + 提交前剪枝

ComfyUI 导出的 API-JSON 里所有值都是**硬编码**的。平台复用需要两层机制，各管一个维度：

| 维度 | 机制 | 例 |
|---|---|---|
| **值是什么**（每次生成都变） | 占位符替换 | prompt、seed、duration、first_frame 文件名 |
| **结构在不在**（可选配置开/关） | 提交前剪枝 | 首帧连不连、尾帧连不连、LoRA 开不开、原生音频要不要 |

**导出规范：每个工作流只导一份"全填版"**——所有链接型可选输入都连上、值型旋钮填合理默认。
组合数不再变成文件数（废弃早期的"多模板变体"方案）。

**占位符语法**（`WorkflowTemplateLoader`，剪枝**之后**替换）：

| 语法 | 含义 | 例 |
|---|---|---|
| `{{name}}` | 字符串替换 | `{{prompt}}`、`{{first_frame}}`、`{{aspect_ratio}}`、`{{prefix}}` |
| `{{num:name}}` | 数字替换（含小数点为浮点，否则整数） | `{{num:seed}}`、`{{num:duration}}`、`{{num:width}}` |
| `{{bool:name}}` | 布尔替换 | `{{bool:upscale}}` |

**标准占位符名**（适配器按名取值）：

| 占位符 | 对应节点输入 |
|---|---|
| `prompt` / `negative` | 正向/负向提示词（CLIPTextEncode.text 或 H3 的 prompt） |
| `first_frame` / `last_frame` | LoadImage.image（首/尾帧） |
| `num:seed` | RandomNoise.noise_seed / KSampler.seed |
| `num:duration` | 时长秒（PrimitiveFloat.value 等） |
| `num:width` / `num:height` | EmptyLatentImage 宽高 |
| `num:steps` / `num:cfg` | 采样步数 / CFG（选填旋钮） |
| `aspect_ratio` | ResolutionSelector.aspect_ratio（COMBO 选项文本） |
| `prefix` | SaveImage/SaveVideo.filename_prefix |

**sidecar 清单（`<name>.options.json`）**：导入时由 `WorkflowImporter` 从**这份 JSON 现场解析**
自动生成，与模板成对存放（重导即重解析，清单不手写、不单独维护）。只收**链接型可选输入**；
值型旋钮（steps/cfg/strength）不进清单——不提供值即保留导出默认（占位符机制覆盖）。
清单条目：`name / hostId / inputName / sourceId / sourceOutput / type / sourceKind`。
H3 实测清单：`audio(chain)`、`first_frame(source)`——**原生音频也是可选链接输入**，
"静音视频"= 关 audio 槽，§8 的三层音频开关由此统一进剪枝机制。

**剪枝算法（`WorkflowPruner`，消费端为中心）**：关闭槽 = 关闭其源节点 C。
1. 遍历 C 每个输出槽的每个消费端 (H, I)：
   - I 在 H 上是**可选**输入 → 删除该链接（如 first_frame 关）；
   - I 在 H 上是**必填**输入 → **旁路改接**：H.inputs[I] 改指向 C 的同类型输入链接
     （如 LoRA 关：host.model→unet、host.clip→clip）。同类型输入链接不唯一即**拒绝**，不猜。
2. 删除 C。
3. **孤儿 GC**（先旁路后 GC）：无消费端且非 output_node 的节点迭代删净
   （如 LoadImage 上游的 ImageScale 传递性删除）。
共享同一源节点的多个槽是**耦合组**：关其一即关整个源（LoRA 的 model/clip 双输出同理）。

**能力声明数据驱动**：清单里有 `last_frame` 槽才声明 `FIRST_LAST_FRAME`、有 `audio` 槽才声明
`NATIVE_AUDIO`——"以模板实际能跑为准"从约定变成机器事实，适配器不再手写能力集合。

**导入流程**（`WorkflowImporter`，当前以测试形式运行：
`mvn test -Dtest=WorkflowImportCliTest "-Dimport.template=<路径>"`）：
1. ComfyUI Dev Mode → Save (API Format) 导出**全填版**；
2. 结构自检（链接完整 / required 齐备 / 无悬空 / 无孤儿；`COMFY_AUTOGROW_V3` 动态自增输入豁免）；
3. 提取 sidecar 清单；
4. **2^k 组合剪枝自检**（k≤4，每组合剪一遍再自检）；任一步失败即拒绝导入并列出问题；
5. 写 sidecar 与参数化后的工作流（**导入器原地落盘**）。占位符由
   `WorkflowParameterizer` 规则引擎自动完成（信号：生态命名约定 / multiline+拓扑定正负 /
   加载保存节点规则 / Primitive 源节点沿开关与数学表达式追踪命名（开关极性规则：
   经 on_false 生效的汇点名才是名字）/ 开关名由 on_true 分支源节点类推导 / LoRA 类节点规则），
   已是占位符的值跳过（幂等）；工作流级常量（模型名/采样器等）保持硬编码并列入报告信息区。
   规则未覆盖的节点类不猜：导入报告中列出，人工确认后补规则。

**安全网**：导入时 2^k 自检为主力；`POST /prompt` 的 node_errors 为最后防线（校验不过不执行）。
剪枝器即使有漏网 bug，表现是响亮报错，不是坏视频。

**开关常量折叠（值型可选配置，与剪枝互补）**：部分工作流（anima / illustrious）的可选配置
不是"可选链接输入"而是 **`ComfySwitchNode` + 布尔值**（链接常连，switch 值决定走哪条分支），
如 LoRA 开/关、img2img 开/关、宽高交换。处理机制：
1. 开关走**布尔占位符** `{{bool:lora}}` / `{{bool:img2img}}` / `{{bool:swap}}`，适配器按请求注入
   （lora = extraParams.lora_name 非空；img2img = firstFrameImage 非空；swap 恒 false）；
2. 占位符替换**之后**调 `WorkflowPruner.foldConstantSwitches`：switch 为字面量布尔的开关节点，
   其消费端直连选中分支（true→on_true / false→on_false），删除开关节点；
3. 孤儿 GC：**未选中分支上仅被开关引用的节点随之删除**——关闭的 LoRA 加载器不再进入提交，
   其 `lora_name`（本地可能不存在该文件）不触发校验，`lora=false` 时本地无 LoRA 也能提交；
   `lora=true` 而文件缺失时由 ComfyUI 提交校验响亮拒绝（fail-loud，预期）。
switch 输入仍为链接（非常量）时不折叠，保持原样。

### 4.6 额外收益：模型可逐镜混用（v1 预留）

`video_model` 是 **Shot 级字段**（非项目级硬绑定）。v1 只接 H3，该字段暂只有一个可选值；架构上保留逐镜混用能力——M2 接入第二个后端后，可以大部分镜头用画质优先模型、一镜到底的续接镜头用 H3 的首尾帧；审核不满意时"换模型重roll"也构成重生成分级的一个新维度（L3，v1 预留）。

## 5. 领域模型（分镜/一镜到底统一为"镜头序列"）

视频模型单次只能生成约 5~10 秒，**"一镜到底"物理上不是一次生成**，而是多段续接。因此两种模式统一为**有序镜头（Shot）列表**，差异只在参数：

- **分镜模式**：每镜首帧由图模型从"角色+场景参考图"**独立生成** → 图生视频；镜头间允许跳切，合成时加转场。
- **一镜到底模式**：第 1 镜同上；之后每镜用**上一段末帧**作首帧续生成（若后端 `supports(FIRST_LAST_FRAME)`，则升级为**首尾帧双约束**）；合成时零转场硬接。
- **关键帧重置机制**：续帧链每 N 段累积漂移（色偏/脸崩/糊），允许在任意镜头插入"用参考图重新生成干净首帧"打断漂移链；漂移严重时该帧可先过 SeedVR2 修复 / Flux2 edit 纠偏。

核心实体（编码期细化字段）：
- `Project`：项目简报（题材/风格/画幅/目标时长/梗概/模式/音频三层开关）
- `Character`：角色（外貌、参考图、绑定音色）；来源：预生成角色库 / 用户上传 / ComfyUI 生成
- `Scene`：场景（描述、参考图）
- `Shot`：分镜（序号、时长、景别、运镜、画面描述、台词、说话角色、音效、首帧来源、**video_model**、状态）
- `ShotVersion`：某镜的一次生成产物（提示词、种子、参数、后端、产物路径、审核结果）—— **版本化**
- `Review`：审核动作（通过 / 重生成 / 回退改分镜）

## 6. Agent 工作流：槽位填充状态机 + LLM

**不做纯自由对话**（易漏项、跑偏、每轮带全量上下文成本高）。改为：

1. 结构化的**"项目简报"Schema** 作为唯一事实源（第 5 节实体）。
2. agent 的本质工作是**多轮填槽**：每轮判断哪些必填槽位为空 → 针对性提问；LLM 负责理解自然语言并写回槽位，**流程进度由后端状态机管**，不靠 LLM 记忆。
3. **交互策略**：agent 先推断全部默认值，**一次性展示完整项目简报供用户修改**，而不是逐项追问（交互轮次最少）。
4. **agent 不只是信息收集者，还是提示词优化者**：用户的粗糙描述由 agent 优化为生成级提示词（补景别/运镜/风格前缀/负向提示、对齐模型模板），且**优化后的提示词必须经用户确认，才能写入状态机槽**——未确认的提示词始终是草稿，不进入生成链路。见 §6.3。

### 6.1 槽位表（必填 / 选填）

**① 项目级 Project**

| 槽位 | 必填 | 默认 / 说明 |
|---|---|---|
| 剧情梗概 `synopsis` | ✅ **必填** | 对话的核心产物，agent 主要工作就是问清它 |
| 模式 `mode` | ✅ **必填** | 分镜 / 一镜到底。决定首帧来源与转场策略 |
| 画幅 `aspect_ratio` | ✅ **必填** | 9:16 / 16:9 / 1:1。短剧默认 9:16，联动分辨率 |
| 视频模型 `video_model` | ✅ 必填（带默认） | 默认 H3；Shot 级可覆盖 |
| 目标时长 `target_duration` | 选填 | 默认 60s；agent 据此推算分镜数（÷ 每镜约 5s） |
| 题材风格 `style` | 选填 | 默认"都市现代"；影响提示词模板前缀 |
| 分辨率 `resolution` | 选填 | 按画幅自动（9:16 → 768×1376 等） |
| 音频三层开关 | 选填 | native / dialogue / bgm，见 §8 |
| BGM 素材 | 选填* | 若开启 bgm 层则必填（本地素材库上传） |
| 画质修复开关 | 选填 | 默认关；开启后每镜视频过 SeedVR2 |

**② 角色级 Character**

| 槽位 | 必填 | 说明 |
|---|---|---|
| 名称 `name` | ✅ | |
| 外貌描述 `appearance` | ✅ | 生成参考图的核心输入 |
| 参考图 `reference_image` | ✅ **条件必填** | 必须有，**来源三选一**：预生成角色库 / 用户上传 / ComfyUI 生成 |
| 绑定音色 `voice` | ✅ **条件必填** | **仅当该角色在任一分镜中有台词时必填** |

**③ 场景级 Scene**

| 槽位 | 必填 | 说明 |
|---|---|---|
| 名称 `name` | ✅ | |
| 场景描述 `description` | ✅ | |
| 参考图 `reference_image` | ✅ 条件必填 | 同角色三来源；纯人物特写镜头可无场景参考图 |

**④ 分镜级 Shot**（agent 产出，逐条可改）

| 槽位 | 必填 | 默认 / 说明 |
|---|---|---|
| 序号 `order` | ✅ | 自动 |
| 画面描述 `visual_prompt` | ✅ | 生成首帧图 + 视频的核心 |
| 时长 `duration` | ✅ | 须对齐所选后端的 `FRAME_GRID`（H3 约 5s/段） |
| 首帧来源 `first_frame_source` | ✅ **自动推导** | 分镜=参考图独立生成；一镜到底=上一镜末帧（首镜除外） |
| 视频模型 `video_model` | 选填 | 空则继承项目默认；可逐镜混用 |
| 出场角色 `characters` | 选填 | 空 = 纯空镜；有值则注入参考图保证一致性 |
| 场景 `scene` | 选填 | 关联场景参考图 |
| 台词 `dialogue` | **选填** | 无台词 = 纯画面镜头（明确支持的形态） |
| 说话角色 `speaker` | ✅ 条件必填 | **有台词时必填**，用于取音色 |
| 景别 `shot_size` | 选填 | 远/全/中/近/特写，默认"中景" |
| 运镜 `camera_move` | 选填 | 固定/推/拉/摇/移/跟，默认"固定" |
| 尾帧 `last_frame` | 选填* | **仅当后端 `supports(FIRST_LAST_FRAME)` 时可见** |
| 音效 `sfx` | 选填 | 1.0 仅记录描述，不做生成 |

**最小可用路径**：用户只提供 **剧情梗概 + 模式 + 画幅** 三项，其余由 agent 推断或走默认值即可跑通全流程。填槽状态机的设计目标是**必填项尽量少，条件必填由依赖关系触发**。

### 6.3 提示词优化 + 确认门（agent 的双重职责）

agent 不只是信息收集者，还是**提示词优化者**。用户的粗糙描述（"两个人在雨里吵架"）不能直接进生成链路，需由 agent 优化为生成级提示词：

- 补全**景别 / 运镜 / 光线 / 风格前缀**（对齐项目 `style` 与提示词模板）
- 注入**角色/场景参考描述**与一致性锚点（角色外貌、服装、场景要素）
- 生成**负向提示**（模型常见崩坏项）
- 对齐所选后端的提示词习惯（如 H3 的 `<Picture i>` 参考标签）

**关键约束：优化后的提示词是草稿（PromptDraft），必须经用户确认才写入状态机槽。** 未确认的草稿不进入生成链路。

两阶段槽位模型：

```
用户粗糙描述 ──agent优化──▶ PromptDraft（草稿，独立存储）
        ──用户确认──▶ 写入槽位（confirmed，生成链路只读这里）
        ──用户驳回──▶ 带修改意见回到 agent 重新优化
```

适用范围（所有会进生成链路的文本槽位）：
- 分镜级 `visual_prompt`（逐镜草稿，逐条或批量确认）
- 角色级 `appearance`、场景级 `description`（参考图生成前确认）
- **不适用**：`dialogue` 台词（用户原话即权威，agent 不擅自改写；仅可建议）

**确认的落点**：闸门①（分镜表确认）即批量确认各镜提示词草稿的界面——分镜表展示的就是优化后的提示词，确认 = 写入槽位。逐条修改某镜提示词时，修改结果同样先成为新草稿、再确认。

**与重生成循环的关系**：L2（改提示词/参数）产出的新提示词同样是草稿，**须再次确认后才重新提交生成**——避免 agent 自作主张的改动悄悄进入昂贵的生成环节。

**版本化**：PromptDraft 也留版本（谁改的、改前改后、确认时间），与 ShotVersion 关联，便于回溯"当时是用哪版提示词生成的"。

### 6.2 流程主线

```
对话填槽(项目简报)
  → agent 优化提示词为草稿(PromptDraft)
  → 产出分镜表(展示优化后提示词) → 【闸门①：确认提示词+分镜表 / 逐条改】
      （确认 = 草稿写入槽位；驳回 = 带意见回 agent 重优化）
  → 角色/场景参考图(生成 + 审核)
  → 逐镜头串行:
      首帧图(生成 + 【闸门②:审核】)
      → 视频(生成 + 【闸门③:审核】)
      → 台词 TTS(若有台词；生成 + 审核)
  → 全部通过 → ffmpeg 合成(拼接 + 转场 + 三层混音 + 字幕) → 成品
```

## 7. 审核-再生成循环：版本化 + 分级重生成

"不满意"要拆细，否则重生成成本失控。每次生成保留为一个 `ShotVersion`，审核三选一：**通过 / 重生成 / 回退改分镜**。重生成再分级（agent 应依据用户的不满意理由自动建议级别）：

1. **换种子重roll**（最便宜）
2. **改提示词 / 参数**
3. **换视频模型重roll**（v1 预留：仅接 H3，多模型后启用）
4. **改首帧图**（图生视频时首帧不满意，视频重roll 多少次都没用）
5. **回退改分镜脚本**

**性价比最高的闸门是分镜表确认**（闸门①）：改一行文字比重roll 一段视频便宜一万倍，务必前置。

## 8. 音频：三个独立可选层

音频不是单一枚举，而是**三个可独立开关的层**。"有声音但没台词"就是只开原生层的自然组合，而非特例。

| 层 | 来源 | 可选性 | 说明 |
|---|---|---|---|
| **原生层** `native` | 视频模型自带 | 开关 | H3 的环境音/氛围（`EmptyMiniMaxH3LatentAV` 联合生成）。Wan2.2 恒为空——**若用户想要"有声无台词"，必须用 H3**，agent 填槽时须校验此组合 |
| **台词层** `dialogue` | 分镜台词 → TTS-Audio-Suite | 开关 | 每镜可有可无；角色绑定音色（含音色克隆） |
| **BGM 层** `bgm` | 本地素材库上传 | 开关 | 全片一条连续轨，可掩盖一镜到底的音频接缝 |

**默认策略**：三层默认全开（用 H3 时原生层可用），原生层**保留**。

**混音要点**：
- 原生层与台词层同开时需 **ducking**——台词响起时压低环境音，用 ffmpeg `sidechaincompress` 实现。
- **一镜到底的原生音频有接缝问题**：每段独立生成的环境音在拼接处会突变（音量、音色调性不一致）。用连续 BGM 轨覆盖可显著缓解。
- 三层全关 = 纯静音画面。

**字幕**：分镜表本就含台词，ffmpeg 烧字幕或输出 SRT，几乎免费，1.0 即带上。

## 9. MVP 分期

- **M1（核心闭环）**：Maven 多模块 + React 骨架 → Web 对话建项目 → 分镜表确认 → 角色/场景参考图 → 每镜首帧图生成+审核 → 图生视频+审核 → ffmpeg 合成（BGM + 字幕）。**LLM 用 OpenAI 兼容接口（base-url 指向内网或公网）**。**v1 视频后端仅接 MiniMax H3**（唯一实现 GenerationBackend 的视频适配器）；Wan2.2 适配器与模板在架构上预留（图 2 虚线），不实现。第二个视频后端留到 M2，作为能力声明式适配器可插拔性的验证。

  **实现进展（2026-11-13）**：模板工具链（剪枝/折叠/参数化/导入器 + 工作流库前端导入入口）、异步生成+WS 进度+崩溃恢复、**首帧图生成阶段（闸门②）已打通**：`GenerationService.generateFirstFrame` 按能力声明选图后端（默认 ANIMA，配置 `playlet.generation.default-image-backend`）→ 提交即返 GENERATING → history 轮询完成 → 镜头进 `FIRST_FRAME_REVIEW`。审核通过时 `ReviewController` 经 `ComfyFileStore.promoteToInput` 把产物从 ComfyUI output 复制进 **input 目录**（`playlet/shot_<id>_v<no>.png`）并回填 `Shot.firstFrameImage`——图生视频的 `LoadImage` 直接用此名（H3 模板的 `{{first_frame}}` 占位符）。视频生成前置校验：REFERENCE 来源镜头必须先有已审核通过的首帧图（§7 L4：改视频不如改首帧）。打回首帧会清 `firstFrameImage` 并回 PENDING。

  **实现进展（2026-11-13 续）§6.3 确认门后端已打通**：`playlet-agent.PromptOptimizer`（LLM 优化 + **确定性回退**：LLM 不可用/输出不可解析时规则拼装「风格前缀+景别运镜+原描述+默认负向」，确认门永远有草稿可审）→ `PromptDraft`（DRAFT/CONFIRMED/REJECTED，versionNo 递增，旧未决草稿作废、历史保留）→ `DraftController`：`POST /api/drafts/optimize`（SHOT.visual_prompt / CHARACTER.appearance / SCENE.description）、`/{id}/confirm`（写槽位 **含 negative**）、`/{id}/reject`（带意见回 agent）。负向提示独立成列（V4 迁移：`prompt_draft.negative_prompt` + `shot.negative_prompt`）；生成链路经 `GenerationRequest.extraParams.negative` 消费（SD 系图像后端已支持该键，视频后端忽略）。前端 `DraftGate` 组件挂审核台顶部（ 提示词门 → ① 首帧 → ② 视频）。真实冒烟：optimize→门拦截（槽位不变）→confirm 写槽→re-optimize v2→reject→重复 confirm 400 全绿；离线测试 +8（PromptOptimizerTest）。
- **M2（声音与一致性）**：台词 TTS + 角色音色绑定 + 三层混音（含 ducking）；接入第二个视频后端验证能力声明式适配器；多图参考提升一致性。
- **M3（进阶）**：一镜到底续帧（H3 首尾帧双约束）+ 关键帧重置 + SeedVR2 自动修复链；口型对齐（若 H3 的音频参考验证可行）。

## 10. 待验证的技术风险

1. **ComfyUI 工作流导出**：~~flux2/hidream/wan2.2/minimax 目前均为 UI 图格式~~ **H3 图生视频已导出**（`minimaxh3/minimax_h3_ti2v`，2026-11-09）；首帧图（flux2/hidream）、首尾帧、多图参考模板仍待导出。
2. **公网 LLM API 可用性**：外网部署那台机器需能申请并使用 OpenAI 兼容 API key。若不可用，回退到 §1.1 末尾保留的"反转控制"备选方案。
3. **角色一致性**：靠参考图（首帧图 → 图生视频）的链路效果需实测。
4. **一镜到底续帧**：H3 首尾帧双约束 vs Wan2.2 末帧续接的实际接缝质量与漂移程度需实测——1.0 中风险最高，放 M3。
5. ~~**能力清单待实测项**~~ **已实测（2026-11-09 H3 spike）**：原生音频层**确认存在**（产物含 aac 音轨）；时长网格**确认**（请求 5s → 实际 5.167s = 124 帧 @24fps，17k+5 k=7）；分辨率经 ResolutionSelector（16:9 @0.4MP → 864×480）。单段 5s 视频生成耗时 **741s（约 12.4 分钟）**。
6. **生成时长预算**：实测 H3 单段 5s ≈ 12.4 分钟（含模型加载）。2 分钟短剧 ≈ 24 段 ≈ **5 小时纯生成**——印证串行审核节奏与前置分镜闸门的重要性；也说明 M1 必须把"审核等待"做成非阻塞（提交后可离开页面，进度推送召回）。
7. **ffmpeg 未安装**（2026-10-30 侦察发现）：`ffmpeg` 不在 PATH 上，ComfyUI 便携目录内也未找到。合成模块（拼接/混音/字幕）依赖它，编码前需安装或指定路径。设计上把 ffmpeg 路径做成**可配置项**（不硬编码 PATH 查找），与 Graphviz 同放 `gitExclude/` 之类的非入库目录，避免环境差异导致合成失败。

## 11. 下一步

设计确认后进入编码：先搭 Maven 多模块骨架（domain / llm-spi / comfyui / media / api / agent）+ React 前端脚手架，再按 M1 逐模块实现。**建议首个 spike**：用 ComfyUI 原生 API 跑通"提交一个图生视频工作流 → WebSocket 收进度 → 取回产物"，验证第 4 节适配器层的可行性。
