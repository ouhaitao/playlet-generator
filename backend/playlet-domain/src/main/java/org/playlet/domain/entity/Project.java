package org.playlet.domain.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.playlet.domain.model.ProjectMode;

/**
 * 项目简报：唯一事实源。必填槽位 = synopsis + mode + aspectRatio，其余可空走默认。
 */
@Entity
@Table(name = "project")
@Data
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 8000)
    private String synopsis;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ProjectMode mode;

    @Column(nullable = false, length = 8)
    private String aspectRatio;

    @Column(nullable = false, length = 40)
    private String videoModel = "DEFAULT";

    private Integer targetDurationSec;

    @Column(length = 200)
    private String style;

    @Column(name = "resolution_w")
    private Integer resolutionW;
    @Column(name = "resolution_h")
    private Integer resolutionH;

    /** 音频三层开关：原生层 / 台词层 / BGM 层 */
    @Column(nullable = false)
    private boolean audioNative = true;
    @Column(nullable = false)
    private boolean audioDialogue = true;
    @Column(nullable = false)
    private boolean audioBgm = true;

    @Column(length = 500)
    private String bgmAssetPath;

    @Column(nullable = false)
    private boolean upscaleEnabled = false;

    @Column(nullable = false, length = 24)
    private String state = "DRAFT";

    /** 成片相对存储根目录路径（如 compose/project_3/final.mp4；/media/store/ 前缀访问） */
    @Column(name = "final_video_path", length = 500)
    private String finalVideoPath;

    @Column(name = "composed_at", length = 32)
    private String composedAt;

    /** 最近一次合成失败原因（成功后清空） */
    @Column(name = "compose_error", length = 2000)
    private String composeError;

    @Column(nullable = false, length = 32)
    private String createdAt;

    @Column(nullable = false, length = 32)
    private String updatedAt;

}
