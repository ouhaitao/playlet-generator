package org.playlet.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.playlet.domain.model.VersionKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 镜头的一次生成产物（版本化）。提示词、种子、参数、后端、产物路径全留档，可回溯可回退。
 */
@Entity
@Table(name = "shot_version")
@Data
public class ShotVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "shot_id")
    @JsonIgnore
    private Shot shot;

    @Column(nullable = false)
    private int versionNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VersionKind kind;

    /** MINIMAX_H3 / IMAGE / TTS / SEEDVR2 ... */
    @Column(nullable = false, length = 40)
    private String backend;

    @Column(length = 8000)
    private String prompt;

    private Long seed;

    /** 含 extra_params 透传（模型私有旋钮） */
    @Column(length = 8000)
    private String paramsJson;

    @Column(length = 500)
    private String artifactPath;

    /** ComfyUI prompt_id（异步生成关联与进度订阅） */
    @Column(length = 64)
    private String comfyPromptId;

    /** GENERATING | DONE | FAILED */
    @Column(nullable = false, length = 16)
    private String genState = "GENERATING";

    @Column(length = 2000)
    private String errorMessage;

    private Double durationSec;

    /** PENDING | APPROVED | REJECTED */
    @Column(nullable = false, length = 16)
    private String reviewState = "PENDING";

    @Column(length = 1000)
    private String rejectReason;

    /** 触发本次生成的重生成级别 L1~L5；null = 首次生成 */
    private Integer regenLevel;

    @Column(nullable = false, length = 32)
    private String createdAt;

}
