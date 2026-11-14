-- V5: 成片合成（BACKLOG ②）：成品相对路径 / 合成时间 / 失败原因
-- 成品落在存储目录 compose/project_<id>/ 下，经 /media/store/** 静态服务。
ALTER TABLE project ADD COLUMN final_video_path TEXT;
ALTER TABLE project ADD COLUMN composed_at TEXT;
ALTER TABLE project ADD COLUMN compose_error TEXT;
