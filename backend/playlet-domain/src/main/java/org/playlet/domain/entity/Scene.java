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

/** 场景。纯人物特写镜头可无场景参考图。 */
@Entity
@Table(name = "scene")
public class Scene {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "project_id")
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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public Project getProject() { return project; }
    public Long getProjectId() { return project == null ? null : project.getId(); }
    public void setProject(Project project) { this.project = project; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getReferenceImage() { return referenceImage; }
    public void setReferenceImage(String referenceImage) { this.referenceImage = referenceImage; }
    public String getRefSource() { return refSource; }
    public void setRefSource(String refSource) { this.refSource = refSource; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
