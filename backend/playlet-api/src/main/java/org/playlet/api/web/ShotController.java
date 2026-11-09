package org.playlet.api.web;

import org.playlet.domain.entity.CharacterRef;
import org.playlet.domain.entity.Scene;
import org.playlet.domain.entity.Shot;
import org.playlet.domain.entity.ShotVersion;
import org.playlet.domain.repo.CharacterRefRepository;
import org.playlet.domain.repo.SceneRepository;
import org.playlet.domain.repo.ShotRepository;
import org.playlet.domain.repo.ShotVersionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 分镜 / 角色 / 场景 / 版本 REST API。 */
@RestController
public class ShotController {

    private final ShotRepository shots;
    private final ShotVersionRepository versions;
    private final CharacterRefRepository characters;
    private final SceneRepository scenes;

    public ShotController(ShotRepository shots, ShotVersionRepository versions,
                          CharacterRefRepository characters, SceneRepository scenes) {
        this.shots = shots;
        this.versions = versions;
        this.characters = characters;
        this.scenes = scenes;
    }

    @GetMapping("/api/projects/{projectId}/shots")
    public List<Shot> listShots(@PathVariable Long projectId) {
        return shots.findByProjectIdOrderByOrdAsc(projectId);
    }

    @PatchMapping("/api/projects/{projectId}/shots/{shotId}")
    public Shot updateShot(@PathVariable Long projectId, @PathVariable Long shotId,
                           @RequestBody Shot patch) {
        Shot shot = shots.findById(shotId)
                .orElseThrow(() -> new NotFoundException("shot " + shotId));
        if (!shot.getProject().getId().equals(projectId)) {
            throw new NotFoundException("shot " + shotId + " not in project " + projectId);
        }
        if (patch.getVisualPrompt() != null) shot.setVisualPrompt(patch.getVisualPrompt());
        if (patch.getDialogue() != null) shot.setDialogue(patch.getDialogue());
        if (patch.getDurationSec() > 0) shot.setDurationSec(patch.getDurationSec());
        if (patch.getShotSize() != null) shot.setShotSize(patch.getShotSize());
        if (patch.getCameraMove() != null) shot.setCameraMove(patch.getCameraMove());
        if (patch.getVideoModel() != null) shot.setVideoModel(patch.getVideoModel());
        if (patch.getState() != null) shot.setState(patch.getState());
        return shots.save(shot);
    }

    @GetMapping("/api/shots/{shotId}/versions")
    public List<ShotVersion> listVersions(@PathVariable Long shotId) {
        return versions.findByShotIdOrderByVersionNoAsc(shotId);
    }

    @GetMapping("/api/projects/{projectId}/characters")
    public List<CharacterRef> listCharacters(@PathVariable Long projectId) {
        return characters.findByProjectId(projectId);
    }

    @GetMapping("/api/projects/{projectId}/scenes")
    public List<Scene> listScenes(@PathVariable Long projectId) {
        return scenes.findByProjectId(projectId);
    }
}
