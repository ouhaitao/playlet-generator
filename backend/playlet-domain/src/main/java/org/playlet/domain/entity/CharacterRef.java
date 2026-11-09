package org.playlet.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 角色。参考图来源三选一：预生成角色库 / 用户上传 / ComfyUI 生成。
 * 命名 CharacterRef 以避免与 java.lang.Character 冲突。
 */
@Entity
@Table(name = "character")
public class CharacterRef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 4000)
    private String appearance;

    /** 相对存储目录的路径 */
    @Column(length = 500)
    private String referenceImage;

    /** LIBRARY | UPLOAD | GENERATED */
    @Column(length = 24)
    private String refSource;

    /** 有台词时必填：TTS 音色 key */
    @Column(length = 200)
    private String voiceKey;

    @Column(nullable = false, length = 32)
    private String createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAppearance() { return appearance; }
    public void setAppearance(String appearance) { this.appearance = appearance; }
    public String getReferenceImage() { return referenceImage; }
    public void setReferenceImage(String referenceImage) { this.referenceImage = referenceImage; }
    public String getRefSource() { return refSource; }
    public void setRefSource(String refSource) { this.refSource = refSource; }
    public String getVoiceKey() { return voiceKey; }
    public void setVoiceKey(String voiceKey) { this.voiceKey = voiceKey; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
