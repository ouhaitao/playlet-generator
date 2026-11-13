-- §6.3 提示词优化确认门：负向提示独立成列（草稿与已确认槽位各一份）。
-- 草稿的 negative 随草稿审核；确认后写入 shot.negative_prompt，生成链路只读槽位。
ALTER TABLE prompt_draft ADD COLUMN negative_prompt TEXT;
ALTER TABLE shot ADD COLUMN negative_prompt TEXT;
