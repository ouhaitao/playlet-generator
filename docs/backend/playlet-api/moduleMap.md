# playlet-api 模块地图

## src/main/java/org/playlet/api/web

- `StoryboardController.java` - 闸门①分镜表 REST：/generate 切镜、/table 整表、/confirm-all 批量确认、/shots/{shotId}/prompt 逐行改（成新草稿）、POST /shots 手动添加分镜（参数用户自填）。
- `DraftController.java` - 提示词草稿确认门 REST（§6.3）：列表/optimize/manual/confirm/reject；POST /translate 中英互译（PromptTranslator，结果仅展示不落库，fail-loud）。
- `GenerationController.java` - 生成触发 REST：generate-image / generate-video（`{workflow, params}` 按 jsonPath 写回提交）；POST /prompt/generate 生成提示词（纯能力接口，无业务耦合，不落库）。
- `GlobalExceptionHandler.java` - 异常 → HTTP 映射：IllegalArgumentException/IllegalStateException → 400 `{message}`，NotFoundException → 404，其余 500。

## src/main/java/org/playlet/api/service

- `StoryboardService.java` - 分镜表业务：切镜（LLM/回退）、整表 Row 组装（草稿态+槽位内容）、批量/逐镜确认（项目状态 STORYBOARD → STORYBOARD_CONFIRMED）、逐行改（manualShotDraft 新草稿）、`addShot` 手动加分镜（提示词直接写槽不产草稿，ord 冲突后续顺延后移，DRAFT → STORYBOARD）。
- `DraftGateService.java` - 草稿确认门事务：optimize/manualShotDraft/confirm（写槽位）/reject（记录意见），闸门①批量确认共用。
- `GenerationService.java` - 生成业务：校验槽位与工作流 → DefaultGenerationBackend 提交 → 异步轮询 ComfyUI history 落 ShotVersion（paramsJson 留档 {workflow, params}），进度经 WebSocket 推送。
