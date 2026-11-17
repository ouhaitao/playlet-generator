package org.playlet.domain.model;

import lombok.AllArgsConstructor;

/**
 * @author parry 2026/11/17
 */
@AllArgsConstructor
public enum PromptDraftFieldName {

    VISUAL_PROMPT("visual_prompt"),
    APPEARANCE("appearance"),
    DESCRIPTION("description");

    public final String name;
}
