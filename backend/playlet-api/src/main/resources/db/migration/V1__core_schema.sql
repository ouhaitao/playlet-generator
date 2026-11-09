-- V1: 核心领域表（项目/角色/场景/分镜/版本/审核）
-- 媒体文件落文件系统，库中只存相对路径。

CREATE TABLE project (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    synopsis         TEXT    NOT NULL,               -- 剧情梗概（必填）
    mode             TEXT    NOT NULL,               -- STORYBOARD | ONE_SHOT
    aspect_ratio     TEXT    NOT NULL,               -- 9:16 / 16:9 / 1:1
    video_model      TEXT    NOT NULL DEFAULT 'MINIMAX_H3',
    target_duration_sec INTEGER,
    style            TEXT,
    resolution_w     INTEGER,
    resolution_h     INTEGER,
    audio_native     INTEGER NOT NULL DEFAULT 1,    -- 原生层开关
    audio_dialogue   INTEGER NOT NULL DEFAULT 1,    -- 台词层开关
    audio_bgm        INTEGER NOT NULL DEFAULT 1,    -- BGM 层开关
    bgm_asset_path   TEXT,
    upscale_enabled  INTEGER NOT NULL DEFAULT 0,
    state            TEXT    NOT NULL DEFAULT 'DRAFT',
    created_at       TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at       TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE character (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id       INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name             TEXT    NOT NULL,
    appearance       TEXT    NOT NULL,
    reference_image  TEXT,                           -- 相对路径；来源：角色库/上传/生成
    ref_source       TEXT,                           -- LIBRARY | UPLOAD | GENERATED
    voice_key        TEXT,                           -- 有台词时必填
    created_at       TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE scene (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id       INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name             TEXT    NOT NULL,
    description      TEXT    NOT NULL,
    reference_image  TEXT,
    ref_source       TEXT,
    created_at       TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE shot (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id       INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    ord              INTEGER NOT NULL,
    visual_prompt    TEXT    NOT NULL,
    duration_sec     REAL    NOT NULL,
    first_frame_source TEXT  NOT NULL,               -- REFERENCE | PREV_LAST_FRAME
    video_model      TEXT,                           -- 空则继承项目默认
    scene_id         INTEGER REFERENCES scene(id),
    dialogue         TEXT,                           -- 选填：无台词 = 纯画面镜头
    speaker_id       INTEGER REFERENCES character(id),
    shot_size        TEXT    DEFAULT '中景',
    camera_move      TEXT    DEFAULT '固定',
    sfx_note         TEXT,
    state            TEXT    NOT NULL DEFAULT 'PENDING',
    created_at       TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE shot_character (
    shot_id          INTEGER NOT NULL REFERENCES shot(id) ON DELETE CASCADE,
    character_id     INTEGER NOT NULL REFERENCES character(id) ON DELETE CASCADE,
    PRIMARY KEY (shot_id, character_id)
);

CREATE TABLE shot_version (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    shot_id          INTEGER NOT NULL REFERENCES shot(id) ON DELETE CASCADE,
    version_no       INTEGER NOT NULL,
    kind             TEXT    NOT NULL,               -- FIRST_FRAME | VIDEO | TTS
    backend          TEXT    NOT NULL,               -- MINIMAX_H3 / IMAGE / TTS ...
    prompt           TEXT,
    seed             INTEGER,
    params_json      TEXT,                           -- 含 extra_params 透传
    artifact_path    TEXT,                           -- 产物相对路径
    duration_sec     REAL,
    review_state     TEXT    NOT NULL DEFAULT 'PENDING',  -- PENDING|APPROVED|REJECTED
    reject_reason    TEXT,
    regen_level      INTEGER,                        -- 触发本次生成的重生成级别 L1~L5
    created_at       TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE review (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    shot_version_id  INTEGER NOT NULL REFERENCES shot_version(id) ON DELETE CASCADE,
    decision         TEXT    NOT NULL,               -- APPROVE | REGENERATE | ROLLBACK
    feedback         TEXT,                           -- 用户不满意理由
    regen_level      INTEGER,
    created_at       TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- 提示词草稿：agent 优化产物，须用户确认后才写入槽位（DESIGN.md §6.3）
CREATE TABLE prompt_draft (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_type       TEXT    NOT NULL,               -- SHOT | CHARACTER | SCENE
    owner_id         INTEGER NOT NULL,
    field_name       TEXT    NOT NULL,               -- visual_prompt | appearance | description
    base_content     TEXT,                           -- 用户原始粗糙描述
    content          TEXT    NOT NULL,               -- agent 优化后的草稿
    version_no       INTEGER NOT NULL,
    state            TEXT    NOT NULL DEFAULT 'DRAFT',  -- DRAFT | CONFIRMED | REJECTED
    feedback         TEXT,                           -- 驳回意见
    confirmed_at     TEXT,
    created_at       TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_draft_owner ON prompt_draft(owner_type, owner_id, field_name, version_no);
CREATE INDEX idx_shot_project ON shot(project_id, ord);
CREATE INDEX idx_version_shot ON shot_version(shot_id, version_no);
