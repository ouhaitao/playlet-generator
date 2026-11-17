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

/** 场景。纯人物特写镜头可无场景参考图。 */
@Entity
@Table(name = "scene")
@Data
public class Scene {

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
    private String description;

    @Column(length = 500)
    private String referenceImage;

    /** LIBRARY | UPLOAD | GENERATED */
    @Column(length = 24)
    private String refSource;

    @Column(nullable = false, length = 32)
    private String createdAt;

}
