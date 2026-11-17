package org.playlet.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 提示词草稿（DESIGN.md §6.3）：agent 优化产物，须用户确认后才写入槽位。
 * 未确认的草稿不进入生成链路。
 */
@Entity
@Table(name = "prompt_draft")
@Data
public class PromptDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * @see org.playlet.domain.model.PromptDraftOwnerType
     **/
    @Column(nullable = false, length = 16)
    private String ownerType;

    @Column(nullable = false)
    private Long ownerId;

    /**
     * @see org.playlet.domain.model.PromptDraftFieldName
     **/
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

}
