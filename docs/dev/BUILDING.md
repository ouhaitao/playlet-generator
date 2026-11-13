# 构建说明（为什么需要本地镜像）

本仓库的**源码真源**在网络共享 `\\170.240.110.32\playlet-generator`（DSH 会话工作区）。
但 **Maven/Java 无法在 UNC 路径上构建**（已实测：`\\170...` 被归一化为 `C:\170...`，报
`Non-readable POM`），且 cmd.exe 不支持 UNC 作为当前目录；SMB 上跑 node_modules /
文件监视器 / SQLite 也都不可靠。

因此构建与验证一律走**本地镜像**：

```
源码真源（共享）  --robocopy-->  本地镜像 %TEMP%\playlet-build  --mvn/npm-->  编译验证
```

## 用法

```powershell
# 1) 同步源码到本地镜像（排除 target/node_modules）
pwsh -File gitExclude\scripts\sync-build.ps1

# 2) 在镜像里构建后端（mvn 已在系统 PATH）
mvn -B -f "$env:TEMP\playlet-build\backend\pom.xml" compile

# 3) 前端同理
cd "$env:TEMP\playlet-build\frontend"; npm install; npm run build
```

环境依赖（均已加入系统 PATH）：mvn 3.9.16、dot（Graphviz 16.1.0）、ffmpeg 9.0.2、node、java 21。
注意：DSH 会话进程若启动早于 PATH 变更，需先从注册表重读 PATH 才能看到这些命令。

## 规则

- **构建产物绝不回写共享**：`target/`、`node_modules/`、`dist/` 只存在于本地镜像。
- **运行时数据目录（SQLite、媒体文件）必须位于运行应用的机器本地盘**，不得放在 SMB 上
  （SQLite 官方不支持网络文件锁，SMB 上有损坏风险）。见 `application.yml` 的
  `playlet.storage.dir`，默认 `./data`（相对应用工作目录）。
  **且不得放在会被 robocopy /MIR 覆盖的镜像目录内**（MIR 会删除源中不存在的目录，
  曾导致整个测试库被清空）。启动时用环境变量指到镜像外：
  `PLAYLET_STORAGE_DIR=$env:TEMP\playlet-data`。sync-build.ps1 已加 `/XD data` 双保险。
- 改完源码后重新跑 `sync-build.ps1` 再构建；镜像是单向的（共享 → 本地），不要反向复制。

## 已踩过的坑（勿再踩）

1. **UTF-8 BOM**：Windows PowerShell 5.1 的 `Set-Content -Encoding utf8` / 某些 IDE 保存会写入
   BOM，javac 报 `非法字符: '\ufeff'`。仓库已加 `.editorconfig`（charset=utf-8 即无 BOM）；
   若再复发，用字节级去 BOM（读全字节、跳过 EF BB BF 写回）。
2. **Spring Boot 4.x 默认 Jackson 3**：RestClient/WebClient 默认消息转换器不认识 Jackson 2 的
   `com.fasterxml...JsonNode`，`.body(JsonNode.class)` 会抛异常。本项目一律
   **String body + 显式 ObjectMapper** 收发（见 `ComfyClient`）。
3. **UNC 路径执行策略**：从 `\\server\share` 直接 `& script.ps1` 会被执行策略拦截
   （"not digitally signed"）。要么内联脚本逻辑，要么先复制到本地再执行。
4. **PATH 变更不生效**：DSH 会话进程启动早于系统 PATH 修改时，`mvn/dot/ffmpeg` 找不到；
   从注册表重读：`$env:Path = (HKLM ...Environment).Path + ';' + (HKCU Environment).Path + ';' + $env:Path`。
5. **Maven 无法在 UNC 构建**：`\\170...` 被归一化为 `C:\170...` 报 Non-readable POM；必须走本地镜像。
6. **`spring-boot:run -pl playlet-api` 用本地仓库旧 jar**：兄弟模块（如 playlet-comfyui）新增类后，
   必须先对整个 reactor `mvn install -DskipTests` 再 `-pl playlet-api spring-boot:run`，
   否则运行时 `ClassNotFoundException`（-pl 单模块不会重编兄弟模块）。
7. **模板占位符必须全部有值**：`WorkflowTemplateLoader.substitute` 对缺值的占位符**保持原样**
   （`{{num:cfg}}` 字面量进提交 payload），ComfyUI 会以 `could not convert string to float` 拒绝。
   后端提交前必须为模板中每个占位符兜底默认值（见 `SdImageBackend.submit`）。
