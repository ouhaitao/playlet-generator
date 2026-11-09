package org.playlet.domain.entity;

import org.playlet.domain.model.ProjectMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 项目简报：唯一事实源。必填槽位 = synopsis + mode + aspectRatio，其余可空走默认。
 */
@Entity
@Table(name = "project")
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
    private String videoModel = "MINIMAX_H3";

    private Integer targetDurationSec;

    @Column(length = 200)
    private String style;

    private Integer resolutionW;
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

    @Column(nullable = false, length = 32)
    private String createdAt;

    @Column(nullable = false, length = 32)
    private String updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSynopsis() { return synopsis; }
    public void setSynopsis(String synopsis) { this.synopsis = synopsis; }
    public ProjectMode getMode() { return mode; }
    public void setMode(ProjectMode mode) { this.mode = mode; }
    public String getAspectRatio() { return aspectRatio; }
    public void setAspectRatio(String aspectRatio) { this.aspectRatio = aspectRatio; }
    public String getVideoModel() { return videoModel; }
    public void setVideoModel(String videoModel) { this.videoModel = videoModel; }
    public Integer getTargetDurationSec() { return targetDurationSec; }
    public void setTargetDurationSec(Integer targetDurationSec) { this.targetDurationSec = targetDurationSec; }
    public String getStyle() { return style; }
    public void setStyle(String style) { this.style = style; }
    public Integer getResolutionW() { return resolutionW; }
    public void setResolutionW(Integer resolutionW) { this.resolutionW = resolutionW; }
    public Integer getResolutionH() { return resolutionH; }
    public void setResolutionH(Integer resolutionH) { this.resolutionH = resolutionH; }
    public boolean isAudioNative() { return audioNative; }
    public void setAudioNative(boolean audioNative) { this.audioNative = audioNative; }
    public boolean isAudioDialogue() { return audioDialogue; }
    public void setAudioDialogue(boolean audioDialogue) { this.audioDialogue = audioDialogue; }
    public boolean isAudioBgm() { return audioBgm; }
    public void setAudioBgm(boolean audioBgm) { this.audioBgm = audioBgm; }
    public String getBgmAssetPath() { return bgmAssetPath; }
    public void setBgmAssetPath(String bgmAssetPath) { this.bgmAssetPath = bgmAssetPath; }
    public boolean isUpscaleEnabled() { return upscaleEnabled; }
    public void setUpscaleEnabled(boolean upscaleEnabled) { this.upscaleEnabled = upscaleEnabled; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
