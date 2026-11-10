-- V2: 生成状态字段（异步生成 + 进度推送，2026-11-10）
-- gen_state: GENERATING | DONE | FAILED（历史行视为 DONE）
ALTER TABLE shot_version ADD COLUMN comfy_prompt_id TEXT;
ALTER TABLE shot_version ADD COLUMN gen_state TEXT NOT NULL DEFAULT 'DONE';
ALTER TABLE shot_version ADD COLUMN error_message TEXT;
