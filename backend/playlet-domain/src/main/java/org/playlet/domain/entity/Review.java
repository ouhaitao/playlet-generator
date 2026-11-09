package org.playlet.domain.entity;

import org.playlet.domain.model.ReviewDecision;
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

/** 审核动作：通过 / 重生成 / 回退改分镜。 */
@Entity
@Table(name = "review")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shot_version_id")
    private ShotVersion shotVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReviewDecision decision;

    /** 用户不满意理由（agent 据此建议重生级别） */
    @Column(length = 2000)
    private String feedback;

    private Integer regenLevel;

    @Column(nullable = false, length = 32)
    private String createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ShotVersion getShotVersion() { return shotVersion; }
    public void setShotVersion(ShotVersion shotVersion) { this.shotVersion = shotVersion; }
    public ReviewDecision getDecision() { return decision; }
    public void setDecision(ReviewDecision decision) { this.decision = decision; }
    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }
    public Integer getRegenLevel() { return regenLevel; }
    public void setRegenLevel(Integer regenLevel) { this.regenLevel = regenLevel; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
