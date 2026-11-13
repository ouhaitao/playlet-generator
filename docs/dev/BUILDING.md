# 构建说明

本仓库工作目录在**本地盘** `E:\parry\playlet-generator`（DSH 会话工作区），构建、验证、运行、调试都在这里直接进行。

> 历史备注：早期源码真源在网络共享 `\\170.240.110.32\playlet-generator`，因 Maven/Java 无法在 UNC 路径构建、SMB 上跑 node_modules/文件监视器/SQLite 不可靠，曾用 robocopy 同步到本地镜像 `%TEMP%\playlet-build` 再构建。**该镜像流程已作废**——代码整体迁到本地盘后无需再同步，直接就地构建。

## 用法

```powershell
# 会话 PATH 可能过期，先从注册表重读（见坑 #4）
$env:Path = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Environment').Path + ';' + (Get-ItemProperty 'HKCU:\Environment').Path + ';' + $env:Path

# 后端：离线测试（排除需要真实 ComfyUI/LLM 的 spike）
mvn -B -f E:\parry\playlet-generator\backend\pom.xml test '-Dtest=!H3VideoSpikeTest,!AnimaImageGenerationTest,!ComfyClientSpikeTest,!RealWorkflowImportTest,!WorkflowImportCliTest' '-Dsurefire.failIfNoSpecifiedTests=false'

# 后端：跑应用前必须整 reactor install（坑 #6），再单模块 spring-boot:run
mvn -B -f E:\parry\playlet-generator\backend\pom.xml install -DskipTests
$env:PLAYLET_STORAGE_DIR = "E:\parry\playlet-generator\gitExclude\playlet-data"
mvn -B -f E:\parry\playlet-generator\backend\pom.xml -pl playlet-api spring-boot:run

# 前端
cd E:\parry\playlet-generator\frontend; npm install; npm run build   # tsc -b && vite build
npm run dev   # 5173，proxy /api /media /ws → 127.0.0.1:8080
```

环境依赖（均已加入系统 PATH）：mvn 3.9.16、Java 21.0.10、node v24.14.0、npm 11.21.1、dot（Graphviz 16.1.0）、ffmpeg 9.0.2。

## 规则

- **运行时数据目录（SQLite、媒体文件）放在 `gitExclude\` 下**（git 忽略、不会被误提交）：`PLAYLET_STORAGE_DIR=E:\parry\playlet-generator\gitExclude\playlet-data`。SQLite 官方不支持网络文件锁，务必在本地盘。
- `target/`、`node_modules/`、`dist/` 为构建产物，已在 `.gitignore`。
- 跑应用前先杀 8080/5173 残留：`Get-NetTCPConnection -LocalPort 8080 -State Listen`。

## 已踩过的坑（勿再踩）

1. **UTF-8 BOM**：Windows PowerShell 5.1 的 `Set-Content -Encoding utf8` / 某些 IDE 保存会写入
   BOM，javac 报 `非法字符: '\ufeff'`。仓库已加 `.editorconfig`（charset=utf-8 即无 BOM）；
   若再复发，用字节级去 BOM（读全字节、跳过 EF BB BF 写回）。
2. **Spring Boot 4.x 默认 Jackson 3**：RestClient/WebClient 默认消息转换器不认识 Jackson 2 的
   `com.fasterxml...JsonNode`，`.body(JsonNode.class)` 会抛异常。本项目一律
   **String body + 显式 ObjectMapper** 收发（见 `ComfyClient`）。注：`StoryboardPlanner` 解析
   LLM 的镜头数组用的是本地 `new ObjectMapper()`（Jackson 2，非 Web 转换器），不受此限。
3. **PATH 变更不生效**：DSH 会话进程启动早于系统 PATH 修改时，`mvn/dot/ffmpeg` 找不到；
   从注册表重读（见上「用法」首行）。
4. **`spring-boot:run -pl playlet-api` 用本地仓库旧 jar**：兄弟模块（如 playlet-agent 新增
   `StoryboardPlanner`、playlet-comfyui 新增 `snapDuration`）改动后，必须先对整个 reactor
   `mvn install -DskipTests` 再 `-pl playlet-api spring-boot:run`，否则运行时
   `ClassNotFoundException` / `NoSuchMethodError`（-pl 单模块不会重编兄弟模块）。
5. **模板占位符必须全部有值**：`WorkflowTemplateLoader.substitute` 对缺值的占位符**保持原样**
   （`{{num:cfg}}` 字面量进提交 payload），ComfyUI 会以 `could not convert string to float` 拒绝。
   后端提交前必须为模板中每个占位符兜底默认值（见 `SdImageBackend.submit`）。
6. **PowerShell 输出中文乱码**：`Invoke-RestMethod` 打印中文可能是 mojibake——那是控制台
   编码显示问题，落库内容正确。核对内容用 `[Console]::OutputEncoding = [Text.Encoding]::UTF8`
   或直接查库，别被乱码误导。
