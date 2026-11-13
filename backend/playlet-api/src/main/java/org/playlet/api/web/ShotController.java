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
import org.springframework.web.bind.annotation.PostMapping;
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
    private final org.playlet.domain.repo.ProjectRepository projects;

    public ShotController(ShotRepository shots, ShotVersionRepository versions,
                          CharacterRefRepository characters, SceneRepository scenes,
                          org.playlet.domain.repo.ProjectRepository projects) {
        this.shots = shots;
        this.versions = versions;
        this.characters = characters;
        this.scenes = scenes;
        this.projects = projects;
    }

    @GetMapping("/api/projects/{projectId}/shots")
    public List<Shot> listShots(@PathVariable Long projectId) {
        return shots.findByProject_IdOrderByOrdAsc(projectId);
    }

    @PostMapping("/api/projects/{projectId}/shots")
    public Shot createShot(@PathVariable Long projectId, @RequestBody Shot body) {
        org.playlet.domain.entity.Project project = projects.findById(projectId)
                .orElseThrow(() -> new NotFoundException("project " + projectId));
        body.setId(null);
        body.setProject(project);
        if (body.getState() == null) {
            body.setState(org.playlet.domain.model.ShotState.PENDING);
        }
        if (body.getFirstFrameSource() == null) {
            body.setFirstFrameSource(project.getMode() == org.playlet.domain.model.ProjectMode.ONE_SHOT
                    && body.getOrd() > 1
                    ? org.playlet.domain.model.FirstFrameSource.PREV_LAST_FRAME
                    : org.playlet.domain.model.FirstFrameSource.REFERENCE);
        }
        body.setCreatedAt(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return shots.save(body);
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
        if (patch.getFirstFrameImage() != null) shot.setFirstFrameImage(patch.getFirstFrameImage());
        if (patch.getState() != null) shot.setState(patch.getState());
        return shots.save(shot);
    }

    @GetMapping("/api/shots/{shotId}/versions")
    public List<ShotVersion> listVersions(@PathVariable Long shotId) {
        return versions.findByShot_IdOrderByVersionNoAsc(shotId);
    }

    @GetMapping("/api/shots/{shotId}")
    public Shot getShot(@PathVariable Long shotId) {
        return shots.findById(shotId)
                .orElseThrow(() -> new NotFoundException("shot " + shotId));
    }

    @GetMapping("/api/projects/{projectId}/characters")
    public List<CharacterRef> listCharacters(@PathVariable Long projectId) {
        return characters.findByProject_Id(projectId);
    }

    @GetMapping("/api/projects/{projectId}/scenes")
    public List<Scene> listScenes(@PathVariable Long projectId) {
        return scenes.findByProject_Id(projectId);
    }
}
