package org.playlet.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 提示词草稿（DESIGN.md §6.3）：agent 优化产物，须用户确认后才写入槽位。
 * 未确认的草稿不进入生成链路。
 */
@Entity
@Table(name = "prompt_draft")
public class PromptDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SHOT | CHARACTER | SCENE */
    @Column(nullable = false, length = 16)
    private String ownerType;

    @Column(nullable = false)
    private Long ownerId;

    /** visual_prompt | appearance | description */
    @Column(nullable = false, length = 40)
    private String fieldName;

    /** 用户原始粗糙描述 */
    @Column(length = 4000)
    private String baseContent;

    /** agent 优化后的草稿 */
    @Column(nullable = false, length = 8000)
    private String content;

    /** agent 产出的负向提示（随草稿一起审核，确认后写入槽位） */
    @Column(length = 4000)
    private String negativePrompt;

    @Column(nullable = false)
    private int versionNo;

    /** DRAFT | CONFIRMED | REJECTED */
    @Column(nullable = false, length = 16)
    private String state = "DRAFT";

    /** 驳回意见 */
    @Column(length = 2000)
    private String feedback;

    @Column(length = 32)
    private String confirmedAt;

    @Column(nullable = false, length = 32)
    private String createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOwnerType() { return ownerType; }
    public void setOwnerType(String ownerType) { this.ownerType = ownerType; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }
    public String getBaseContent() { return baseContent; }
    public void setBaseContent(String baseContent) { this.baseContent = baseContent; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getNegativePrompt() { return negativePrompt; }
    public void setNegativePrompt(String negativePrompt) { this.negativePrompt = negativePrompt; }
    public int getVersionNo() { return versionNo; }
    public void setVersionNo(int versionNo) { this.versionNo = versionNo; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }
    public String getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(String confirmedAt) { this.confirmedAt = confirmedAt; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
