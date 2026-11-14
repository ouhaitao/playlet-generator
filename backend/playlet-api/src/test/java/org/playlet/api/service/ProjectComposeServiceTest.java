package org.playlet.api.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.playlet.api.web.NotFoundException;
import org.playlet.api.web.ProgressEvent;
import org.playlet.api.web.ProgressEventPublisher;
import org.playlet.comfyui.client.ComfyFileStore;
import org.playlet.domain.entity.Project;
import org.playlet.domain.entity.Shot;
import org.playlet.domain.entity.ShotVersion;
import org.playlet.domain.model.ShotState;
import org.playlet.domain.model.VersionKind;
import org.playlet.domain.repo.ProjectRepository;
import org.playlet.domain.repo.ShotRepository;
import org.playlet.domain.repo.ShotVersionRepository;
import org.playlet.media.ComposeService;
import org.playlet.media.SubtitleBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ProjectComposeService 离线测试（BACKLOG ②）。
 *
 * <p>覆盖两类：①同步校验门（提交即抛 400，不触 ffmpeg）——项目/分镜/审核状态/产物存在性；
 * ②异步编排（mock ComposeService 避免真 ffmpeg）——submit 返回 COMPOSING →
 * 后台写回 finalVideoPath → 广播 compose_started/completed。
 */
class ProjectComposeServiceTest {

    @TempDir
    Path tmp;

    ProjectRepository projects;
    ShotRepository shots;
    ShotVersionRepository versions;
    ComfyFileStore comfyFiles;
    ComposeService compose;
    SubtitleBuilder subtitles;
    RecordingPublisher events;
    ProjectComposeService service;
    Project project;
    List<Shot> projectShots = new ArrayList<>();

    /** 记录广播事件并可在 compose 终态时释放 latch。 */
    static class RecordingPublisher extends ProgressEventPublisher {
        final List<ProgressEvent> recorded = new CopyOnWriteArrayList<>();
        volatile CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void publish(ProgressEvent event) {
            recorded.add(event);
            if (event.type().startsWith("compose_completed")
                    || event.type().startsWith("compose_failed")) {
                latch.countDown();
            }
        }

        boolean awaitTerminal(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        List<String> types() {
            return recorded.stream().map(ProgressEvent::type).toList();
        }
    }

    @BeforeEach
    void setUp() {
        projects = mock(ProjectRepository.class);
        shots = mock(ShotRepository.class);
        versions = mock(ShotVersionRepository.class);
        comfyFiles = mock(ComfyFileStore.class);
        compose = mock(ComposeService.class);
        subtitles = mock(SubtitleBuilder.class);
        events = new RecordingPublisher();

        project = new Project();
        project.setId(1L);
        project.setState("STORYBOARD_CONFIRMED");
        project.setAudioBgm(false);

        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(projects.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));
        when(shots.findByProject_IdOrderByOrdAsc(1L)).thenAnswer(inv -> projectShots);

        service = new ProjectComposeService(projects, shots, versions, comfyFiles,
                compose, subtitles, events, tmp.toString());
    }

    private Shot shot(long id, int ord, ShotState state) {
        Shot s = new Shot();
        s.setId(id);
        s.setOrd(ord);
        s.setState(state);
        s.setProject(project);
        return s;
    }

    private ShotVersion video(long shotId, String reviewState, String genState, String artifact) {
        ShotVersion v = new ShotVersion();
        v.setKind(VersionKind.VIDEO);
        v.setReviewState(reviewState);
        v.setGenState(genState);
        v.setArtifactPath(artifact);
        return v;
    }

    /** 让某镜有一个已通过的 VIDEO 产物，且 ComfyUI output 下真实存在文件。 */
    private void giveApprovedVideo(Shot s, String relArtifact) throws Exception {
        Path real = tmp.resolve("comfyout").resolve(relArtifact);
        Files.createDirectories(real.getParent());
        Files.writeString(real, "fake-mp4-bytes");
        when(versions.findByShot_IdOrderByVersionNoAsc(s.getId()))
                .thenReturn(List.of(video(s.getId(), "APPROVED", "DONE", relArtifact)));
        when(comfyFiles.resolveOutput(relArtifact)).thenReturn(real);
    }

    // ── 同步校验门 ────────────────────────────────────────

    @Test
    void submit_unknownProject_throws404() {
        when(projects.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.submit(99L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void submit_noShots_throws() {
        assertThatThrownBy(() -> service.submit(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚无分镜");
    }

    @Test
    void submit_shotNotApproved_throws() {
        projectShots.add(shot(10, 1, ShotState.APPROVED));
        projectShots.add(shot(11, 2, ShotState.VIDEO_REVIEW));
        assertThatThrownBy(() -> service.submit(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未通过审核");
    }

    @Test
    void submit_approvedShotWithoutVideoArtifact_throws() {
        Shot s = shot(10, 1, ShotState.APPROVED);
        projectShots.add(s);
        // APPROVED 但没有通过的 VIDEO 版本
        when(versions.findByShot_IdOrderByVersionNoAsc(10L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.submit(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有审核通过的视频产物");
    }

    @Test
    void submit_videoArtifactFileMissing_throws() {
        Shot s = shot(10, 1, ShotState.APPROVED);
        projectShots.add(s);
        when(versions.findByShot_IdOrderByVersionNoAsc(10L))
                .thenReturn(List.of(video(10L, "APPROVED", "DONE", "video/gone.mp4")));
        when(comfyFiles.resolveOutput("video/gone.mp4")).thenReturn(tmp.resolve("nope.mp4"));
        assertThatThrownBy(() -> service.submit(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("视频产物文件不存在");
    }

    // ── 异步编排（mock 合成，不触 ffmpeg）────────────────

    @Test
    void submit_returnsComposing_thenWorkerWritesFinalPath_andBroadcasts() throws Exception {
        Shot s1 = shot(10, 1, ShotState.APPROVED);
        Shot s2 = shot(11, 2, ShotState.APPROVED);
        s1.setDialogue("第一镜");
        s2.setDialogue("第二镜");
        projectShots.add(s1);
        projectShots.add(s2);
        giveApprovedVideo(s1, "video/s1.mp4");
        giveApprovedVideo(s2, "video/s2.mp4");

        // mock：字幕生成 + 合成产物落 storageDir/compose/project_1/final.mp4
        Path finalPath = tmp.resolve("compose").resolve("project_1").resolve("final.mp4");
        Files.createDirectories(finalPath.getParent());
        Files.writeString(finalPath, "composed");
        when(subtitles.buildSrt(any(), any(), any(), anyString()))
                .thenReturn(Optional.of(tmp.resolve("compose/project_1/final.srt")));
        when(compose.compose(any(), any(), any(), any(), any(), anyString(), any()))
                .thenReturn(finalPath);

        ProjectComposeService.ComposeResult result = service.submit(1L);
        assertThat(result.state()).isEqualTo(ProjectComposeService.STATE_COMPOSING);
        assertThat(events.types()).contains("compose_started");

        // 等后台 worker 跑完
        assertThat(events.awaitTerminal(5, TimeUnit.SECONDS)).isTrue();
        assertThat(project.getFinalVideoPath()).isEqualTo("compose/project_1/final.mp4");
        assertThat(project.getComposedAt()).isNotNull();
        assertThat(project.getComposeError()).isNull();
        // 项目状态推进 COMPOSED（成片即最新内容）
        assertThat(project.getState())
                .isEqualTo(ProjectComposeService.STATE_PROJECT_COMPOSED);
        assertThat(events.types()).contains("compose_completed");
    }

    @Test
    void submit_composeFails_recordsError_andBroadcastsFailed() throws Exception {
        Shot s1 = shot(10, 1, ShotState.APPROVED);
        projectShots.add(s1);
        giveApprovedVideo(s1, "video/s1.mp4");
        when(subtitles.buildSrt(any(), any(), any(), anyString())).thenReturn(Optional.empty());
        when(compose.compose(any(), any(), any(), any(), any(), anyString(), any()))
                .thenThrow(new RuntimeException("ffmpeg boom"));

        service.submit(1L);
        assertThat(events.awaitTerminal(5, TimeUnit.SECONDS)).isTrue();
        assertThat(project.getComposeError()).contains("ffmpeg boom");
        assertThat(project.getFinalVideoPath()).isNull();
        assertThat(events.types()).contains("compose_failed");
    }

    @Test
    void submit_rejectsConcurrentSecondSubmit() throws Exception {
        Shot s1 = shot(10, 1, ShotState.APPROVED);
        projectShots.add(s1);
        giveApprovedVideo(s1, "video/s1.mp4");
        // 合成永不返回 → 项目一直停留在 composing 集合
        when(subtitles.buildSrt(any(), any(), any(), anyString())).thenReturn(Optional.empty());
        CountDownLatch entered = new CountDownLatch(1);
        when(compose.compose(any(), any(), any(), any(), any(), anyString(), any()))
                .thenAnswer(inv -> {
                    entered.countDown();
                    Thread.sleep(3000);
                    return tmp.resolve("compose/project_1/final.mp4");
                });

        service.submit(1L);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> service.submit(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("正在合成中");
    }

    // ── 全局百分比映射 ─────────────────────────────────────

    @Test
    void globalPercent_mapsStagesToRanges() {
        assertThat(ProjectComposeService.globalPercent("concat", 0)).isEqualTo(0.0);
        assertThat(ProjectComposeService.globalPercent("concat", 100)).isCloseTo(60.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(ProjectComposeService.globalPercent("mix", 100)).isCloseTo(85.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(ProjectComposeService.globalPercent("subtitle", 100)).isCloseTo(100.0, org.assertj.core.data.Offset.offset(0.01));
        // 越界夹紧
        assertThat(ProjectComposeService.globalPercent("concat", 500)).isCloseTo(60.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(ProjectComposeService.globalPercent("mix", -10)).isCloseTo(60.0, org.assertj.core.data.Offset.offset(0.01));
    }
}
