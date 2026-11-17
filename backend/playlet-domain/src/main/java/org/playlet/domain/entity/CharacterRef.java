package org.playlet.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 角色。参考图来源三选一：预生成角色库 / 用户上传 / ComfyUI 生成。
 * 命名 CharacterRef 以避免与 java.lang.Character 冲突。
 */
@Entity
@Table(name = "character")
@Data
public class CharacterRef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id")
    @JsonIgnore
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

}
