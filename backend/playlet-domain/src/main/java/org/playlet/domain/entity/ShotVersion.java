package org.playlet.domain.entity;

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
public class ShotVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "shot_id")
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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public Shot getShot() { return shot; }
    public void setShot(Shot shot) { this.shot = shot; }
    public Long getShotId() { return shot == null ? null : shot.getId(); }
    public int getVersionNo() { return versionNo; }
    public void setVersionNo(int versionNo) { this.versionNo = versionNo; }
    public VersionKind getKind() { return kind; }
    public void setKind(VersionKind kind) { this.kind = kind; }
    public String getBackend() { return backend; }
    public void setBackend(String backend) { this.backend = backend; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public Long getSeed() { return seed; }
    public void setSeed(Long seed) { this.seed = seed; }
    public String getParamsJson() { return paramsJson; }
    public void setParamsJson(String paramsJson) { this.paramsJson = paramsJson; }
    public String getArtifactPath() { return artifactPath; }
    public void setArtifactPath(String artifactPath) { this.artifactPath = artifactPath; }
    public String getComfyPromptId() { return comfyPromptId; }
    public void setComfyPromptId(String comfyPromptId) { this.comfyPromptId = comfyPromptId; }
    public String getGenState() { return genState; }
    public void setGenState(String genState) { this.genState = genState; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Double getDurationSec() { return durationSec; }
    public void setDurationSec(Double durationSec) { this.durationSec = durationSec; }
    public String getReviewState() { return reviewState; }
    public void setReviewState(String reviewState) { this.reviewState = reviewState; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public Integer getRegenLevel() { return regenLevel; }
    public void setRegenLevel(Integer regenLevel) { this.regenLevel = regenLevel; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
