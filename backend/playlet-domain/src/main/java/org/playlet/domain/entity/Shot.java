package org.playlet.domain.entity;

import org.playlet.domain.model.FirstFrameSource;
import org.playlet.domain.model.ShotState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

/**
 * 分镜（镜头）。分镜/一镜到底统一为有序镜头序列，差异在 firstFrameSource。
 */
@Entity
@Table(name = "shot")
public class Shot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(nullable = false)
    private int ord;

    @Column(nullable = false, length = 4000)
    private String visualPrompt;

    @Column(nullable = false)
    private double durationSec;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private FirstFrameSource firstFrameSource;

    /** 空则继承项目默认；可逐镜混用（v1 仅 H3） */
    @Column(length = 40)
    private String videoModel;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "scene_id")
    private Scene scene;

    /** 选填：无台词 = 纯画面镜头 */
    @Column(length = 2000)
    private String dialogue;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "speaker_id")
    private CharacterRef speaker;

    @Column(length = 24)
    private String shotSize = "中景";

    @Column(length = 24)
    private String cameraMove = "固定";

    @Column(length = 500)
    private String sfxNote;

    /** 首帧图（ComfyUI input 目录文件名）；首帧图生成环节产出后回填，视频生成用它作 first_frame */
    @Column(name = "first_frame_image", length = 500)
    private String firstFrameImage;

    /** 已确认的负向提示（§6.3 确认门写入；生成链路只读这里） */
    @Column(name = "negative_prompt", length = 4000)
    private String negativePrompt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ShotState state = ShotState.PENDING;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "shot_character",
            joinColumns = @JoinColumn(name = "shot_id"),
            inverseJoinColumns = @JoinColumn(name = "character_id"))
    private List<CharacterRef> characters = new ArrayList<>();

    @Column(nullable = false, length = 32)
    private String createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; }
    public Long getProjectId() { return project == null ? null : project.getId(); }
    public int getOrd() { return ord; }
    public void setOrd(int ord) { this.ord = ord; }
    public String getVisualPrompt() { return visualPrompt; }
    public void setVisualPrompt(String visualPrompt) { this.visualPrompt = visualPrompt; }
    public double getDurationSec() { return durationSec; }
    public void setDurationSec(double durationSec) { this.durationSec = durationSec; }
    public FirstFrameSource getFirstFrameSource() { return firstFrameSource; }
    public void setFirstFrameSource(FirstFrameSource firstFrameSource) { this.firstFrameSource = firstFrameSource; }
    public String getVideoModel() { return videoModel; }
    public void setVideoModel(String videoModel) { this.videoModel = videoModel; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public Scene getScene() { return scene; }
    public void setScene(Scene scene) { this.scene = scene; }
    public Long getSceneId() { return scene == null ? null : scene.getId(); }
    public String getDialogue() { return dialogue; }
    public void setDialogue(String dialogue) { this.dialogue = dialogue; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public CharacterRef getSpeaker() { return speaker; }
    public void setSpeaker(CharacterRef speaker) { this.speaker = speaker; }
    public Long getSpeakerId() { return speaker == null ? null : speaker.getId(); }
    public String getShotSize() { return shotSize; }
    public void setShotSize(String shotSize) { this.shotSize = shotSize; }
    public String getCameraMove() { return cameraMove; }
    public void setCameraMove(String cameraMove) { this.cameraMove = cameraMove; }
    public String getSfxNote() { return sfxNote; }
    public void setSfxNote(String sfxNote) { this.sfxNote = sfxNote; }
    public String getFirstFrameImage() { return firstFrameImage; }
    public void setFirstFrameImage(String firstFrameImage) { this.firstFrameImage = firstFrameImage; }
    public String getNegativePrompt() { return negativePrompt; }
    public void setNegativePrompt(String negativePrompt) { this.negativePrompt = negativePrompt; }
    public ShotState getState() { return state; }
    public void setState(ShotState state) { this.state = state; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public List<CharacterRef> getCharacters() { return characters; }
    public void setCharacters(List<CharacterRef> characters) { this.characters = characters; }
    public List<Long> getCharacterIds() {
        return characters == null ? List.of()
                : characters.stream().map(CharacterRef::getId).toList();
    }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
