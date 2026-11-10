-- V3: 镜头首帧图字段（首帧图生成环节产出后回填；视频生成用它作 first_frame）
ALTER TABLE shot ADD COLUMN first_frame_image TEXT;
