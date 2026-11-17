package org.playlet.domain.model;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum PromptDraftOwnerType {

    SHOT("SHOT"),
    CHARACTER("CHARACTER"),
    SCENE("SCENE");

    public final String name;

}
