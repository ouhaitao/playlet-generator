# playlet-agent 模块地图

## src/main/java/org/playlet/agent

- `StoryboardPlanner.java` - 切镜规划：剧情梗概 → LLM 切镜出镜头表（序/时长/景别/运镜/台词）；LLM 不可用时回退「按句切分」（一句一镜、默认 5s）。
- `PromptOptimizer.java` - 提示词优化（§6.3 确认门）：粗糙描述 → 生成级提示词草稿（正向+负向，版本化 PromptDraft）；角色外观/场景氛围优化；`manualShotDraft` 把手改文本直接成新草稿；`generatePromptText(model, content, flag, feedback)` 为审核台「生成提示词」提供**无状态即席能力**——四字段拼一条消息发 LLM、原样返回输出，不落库不耦合业务，失败 fail-loud。
- `PromptTranslator.java` - 提示词中英互译（fail-loud，无降级）：`toEnglish` 生成提交前实时译英；`toChinese` 供审核台译中按钮；已是目标语言/空白原样返回不调 LLM；输出经 sanitize + 语言校验（译中必须含 CJK）。
