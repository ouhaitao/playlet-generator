package org.playlet.api.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.playlet.agent.PromptOptimizer;
import org.playlet.agent.StoryboardPlanner;
import org.playlet.api.web.NotFoundException;
import org.playlet.domain.entity.CharacterRef;
import org.playlet.domain.entity.Project;
import org.playlet.domain.entity.PromptDraft;
import org.playlet.domain.entity.Scene;
import org.playlet.domain.entity.Shot;
import org.playlet.domain.entity.ShotVersion;
import org.playlet.domain.model.FirstFrameSource;
import org.playlet.domain.model.ProjectMode;
import org.playlet.domain.model.ShotState;
import org.playlet.domain.repo.CharacterRefRepository;
import org.playlet.domain.repo.ProjectRepository;
import org.playlet.domain.repo.PromptDraftRepository;
import org.playlet.domain.repo.SceneRepository;
import org.playlet.domain.repo.ShotRepository;
import org.playlet.domain.repo.ShotVersionRepository;
import org.playlet.llm.LlmClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * StoryboardService 离线测试（BACKLOG ①完成判据：切镜 → 整表 → 批量确认 → 槽位落库 → 状态推进）。
 * 全部用内存 fake 仓库，不触网、不起 Spring 上下文；LLM 恒不可用（当前环境常态）。
 */
class StoryboardServiceTest {

    // ── 内存 fake 仓库 ─────────────────────────────────────

    static class FakeShots {
        final List<Shot> store = new ArrayList<>();
        long seq = 0;

        Shot save(Shot s) {
            if (s.getId() == null) s.setId(++seq);
            if (!store.contains(s)) store.add(s);
            return s;
        }

        Optional<Shot> findById(Long id) {
            return store.stream().filter(s -> s.getId().equals(id)).findFirst();
        }

        List<Shot> byProject(Long projectId) {
            return store.stream()
                    .filter(s -> s.getProject().getId().equals(projectId))
                    .sorted(Comparator.comparingInt(Shot::getOrd))
                    .toList();
        }

        void delete(Shot s) {
            store.remove(s);
        }
    }

    static class FakeDrafts {
        final List<PromptDraft> store = new ArrayList<>();
        long seq = 0;

        PromptDraft save(PromptDraft d) {
            if (d.getId() == null) d.setId(++seq);
            if (!store.contains(d)) store.add(d);
            return d;
        }

        Optional<PromptDraft> findById(Long id) {
            return store.stream().filter(d -> d.getId().equals(id)).findFirst();
        }

        List<PromptDraft> byOwner(String ownerType, Long ownerId) {
            return store.stream()
                    .filter(d -> d.getOwnerType().equals(ownerType) && d.getOwnerId().equals(ownerId))
                    .sorted(Comparator.comparingInt(PromptDraft::getVersionNo).reversed())
                    .toList();
        }

        Optional<PromptDraft> latest(String ownerType, Long ownerId, String fieldName) {
            return byOwner(ownerType, ownerId).stream()
                    .filter(d -> d.getFieldName().equals(fieldName))
                    .findFirst();
        }

        void delete(PromptDraft d) {
            store.remove(d);
        }
    }

    private FakeShots shots;
    private FakeDrafts drafts;
    private Project project;
    private StoryboardService service;
    private DraftGateService gate;

    private static LlmClient llmDown() {
        return new LlmClient() {
            @Override
            public String complete(String systemPrompt, String userMessage) {
                throw new IllegalStateException("LLM 未配置");
            }

            @Override
            public String complete(List<LlmClient.ChatMessage> history, String userMessage) {
                throw new IllegalStateException("LLM 未配置");
            }
        };
    }

    @BeforeEach
    void setUp() {
        shots = new FakeShots();
        drafts = new FakeDrafts();

        project = new Project();
        project.setId(1L);
        project.setSynopsis("雨夜，李明在便利店门口徘徊。他推门进去，撞见了前女友。");
        project.setMode(ProjectMode.STORYBOARD);
        project.setAspectRatio("9:16");
        project.setStyle("都市现代");
        project.setState("DRAFT");

        ProjectRepository projects = mock(ProjectRepository.class);
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(projects.existsById(1L)).thenReturn(true);
        when(projects.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));

        ShotRepository shotRepo = mock(ShotRepository.class);
        when(shotRepo.findById(anyLong())).thenAnswer(inv -> shots.findById(inv.getArgument(0)));
        when(shotRepo.save(any(Shot.class))).thenAnswer(inv -> shots.save(inv.getArgument(0)));
        when(shotRepo.findByProject_IdOrderByOrdAsc(anyLong()))
                .thenAnswer(inv -> shots.byProject(inv.getArgument(0)));
        org.mockito.Mockito.doAnswer(inv -> {
            shots.delete(inv.getArgument(0));
            return null;
        }).when(shotRepo).delete(any(Shot.class));

        ShotVersionRepository versions = mock(ShotVersionRepository.class);
        when(versions.findByShot_IdOrderByVersionNoAsc(anyLong())).thenReturn(List.<ShotVersion>of());

        PromptDraftRepository draftRepo = mock(PromptDraftRepository.class);
        when(draftRepo.findById(anyLong())).thenAnswer(inv -> drafts.findById(inv.getArgument(0)));
        when(draftRepo.save(any(PromptDraft.class))).thenAnswer(inv -> drafts.save(inv.getArgument(0)));
        when(draftRepo.findByOwnerTypeAndOwnerIdOrderByVersionNoDesc(anyString(), anyLong()))
                .thenAnswer(inv -> drafts.byOwner(inv.getArgument(0), inv.getArgument(1)));
        when(draftRepo.findFirstByOwnerTypeAndOwnerIdAndFieldNameOrderByVersionNoDesc(
                anyString(), anyLong(), anyString()))
                .thenAnswer(inv -> drafts.latest(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        org.mockito.Mockito.doAnswer(inv -> {
            drafts.delete(inv.getArgument(0));
            return null;
        }).when(draftRepo).delete(any(PromptDraft.class));
        org.mockito.Mockito.doAnswer(inv -> {
            Iterable<PromptDraft> batch = inv.getArgument(0);
            for (PromptDraft d : batch) drafts.delete(d);
            return null;
        }).when(draftRepo).deleteAll(any(Iterable.class));

        CharacterRefRepository chars = mock(CharacterRefRepository.class);
        when(chars.findByProject_Id(anyLong())).thenReturn(List.<CharacterRef>of());
        SceneRepository sceneRepo = mock(SceneRepository.class);
        when(sceneRepo.findByProject_Id(anyLong())).thenReturn(List.<Scene>of());

        LlmClient llm = llmDown();
        PromptOptimizer optimizer = new PromptOptimizer(llm, draftRepo);
        gate = new DraftGateService(draftRepo, shotRepo, chars, sceneRepo, optimizer);
        service = new StoryboardService(projects, shotRepo, versions, draftRepo, chars, sceneRepo,
                new StoryboardPlanner(llm), optimizer, gate, List.of());
    }

    // ── 切镜 → 整表 ───────────────────────────────────────

    @Test
    void generate_splitsSynopsisIntoShotsWithDrafts_andAdvancesState() {
        StoryboardService.GenerateResult result = service.generate(1L);

        assertThat(result.usedLlm()).isFalse(); // LLM 不可用 → 回退切句
        assertThat(result.shotCount()).isEqualTo(2);
        assertThat(result.rows()).hasSize(2);

        // 镜头字段：一句一镜、默认 5s（无后端 → snap 原样）、序递增
        List<Shot> created = shots.byProject(1L);
        assertThat(created).hasSize(2);
        assertThat(created.get(0).getOrd()).isEqualTo(1);
        assertThat(created.get(0).getVisualPrompt()).isEqualTo("雨夜，李明在便利店门口徘徊。");
        assertThat(created.get(0).getDurationSec()).isEqualTo(5.0);
        assertThat(created.get(0).getState()).isEqualTo(ShotState.PENDING);
        // STORYBOARD 模式 → 全部 REFERENCE
        assertThat(created).allSatisfy(s ->
                assertThat(s.getFirstFrameSource()).isEqualTo(FirstFrameSource.REFERENCE));

        // 每镜自动出了草稿（优化器回退拼装），整表可见
        for (StoryboardService.Row row : result.rows()) {
            assertThat(row.draftId()).isNotNull();
            assertThat(row.draftState()).isEqualTo("DRAFT");
            assertThat(row.draftContent()).contains("都市现代");
            assertThat(row.confirmed()).isFalse();
        }
        // 槽位尚未写入（未确认不进生成链路）
        assertThat(created).allSatisfy(s -> assertThat(s.getNegativePrompt()).isNull());

        assertThat(project.getState()).isEqualTo(StoryboardService.STATE_STORYBOARD);
    }

    @Test
    void generate_oneShotModeDerivesFirstFrameSourceChain() {
        project.setMode(ProjectMode.ONE_SHOT);
        service.generate(1L);
        List<Shot> created = shots.byProject(1L);
        assertThat(created.get(0).getFirstFrameSource()).isEqualTo(FirstFrameSource.REFERENCE);
        assertThat(created.get(1).getFirstFrameSource()).isEqualTo(FirstFrameSource.PREV_LAST_FRAME);
    }

    @Test
    void generate_rejectsWhenShotsAlreadyGenerated() {
        service.generate(1L);
        shots.byProject(1L).get(0).setState(ShotState.FIRST_FRAME_REVIEW);
        assertThatThrownBy(() -> service.generate(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已进入生成流程");
    }

    @Test
    void generate_replacesPendingTableAndItsDrafts() {
        service.generate(1L);
        long firstDraftCount = drafts.store.size();
        assertThat(firstDraftCount).isEqualTo(2);

        // 改梗概重切：旧镜头 + 旧草稿被替换
        project.setSynopsis("新的故事。新的第二句。新的第三句！");
        StoryboardService.GenerateResult second = service.generate(1L);

        assertThat(second.shotCount()).isEqualTo(3);
        assertThat(shots.byProject(1L)).hasSize(3);
        assertThat(drafts.store).hasSize(3); // 旧草稿清掉了
        assertThat(second.rows()).allSatisfy(r -> assertThat(r.draftState()).isEqualTo("DRAFT"));
    }

    @Test
    void generate_unknownProject_throws404() {
        ProjectRepository projects = mock(ProjectRepository.class);
        when(projects.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> new StoryboardService(projects, null, null, null, null, null,
                null, null, null, List.of()).generate(99L))
                .isInstanceOf(NotFoundException.class);
    }

    // ── 批量确认 ─────────────────────────────────────────

    @Test
    void confirmAll_writesSlotsAndAdvancesProjectState() {
        service.generate(1L);
        StoryboardService.ConfirmResult result = service.confirmAll(1L);

        assertThat(result.confirmedNow()).isEqualTo(2);
        assertThat(result.stillPending()).isZero();
        assertThat(result.projectState()).isEqualTo(StoryboardService.STATE_STORYBOARD_CONFIRMED);
        assertThat(project.getState()).isEqualTo(StoryboardService.STATE_STORYBOARD_CONFIRMED);

        // 槽位落库：visual_prompt（优化后）+ negative 都写进 Shot
        for (Shot s : shots.byProject(1L)) {
            assertThat(s.getVisualPrompt()).contains("都市现代").isNotEqualTo("雨夜，李明在便利店门口徘徊。");
            assertThat(s.getNegativePrompt()).isEqualTo(PromptOptimizer.DEFAULT_NEGATIVE);
        }
        // 草稿全部 CONFIRMED
        assertThat(drafts.store).allSatisfy(d -> assertThat(d.getState()).isEqualTo("CONFIRMED"));
        // 整表状态同步
        assertThat(service.table(1L)).allSatisfy(r -> assertThat(r.confirmed()).isTrue());
    }

    @Test
    void confirmAll_isIdempotent_afterAllConfirmed() {
        service.generate(1L);
        service.confirmAll(1L);
        StoryboardService.ConfirmResult again = service.confirmAll(1L);
        assertThat(again.confirmedNow()).isZero();
        assertThat(again.stillPending()).isZero();
        assertThat(again.projectState()).isEqualTo(StoryboardService.STATE_STORYBOARD_CONFIRMED);
    }

    @Test
    void confirmAll_skipsRejectedDrafts_andKeepsStatePending() {
        service.generate(1L);
        StoryboardService.Row row0 = service.table(1L).get(0);
        gate.reject(row0.draftId(), "画面太暗");

        StoryboardService.ConfirmResult result = service.confirmAll(1L);

        assertThat(result.confirmedNow()).isEqualTo(1); // 另一镜正常确认
        assertThat(result.stillPending()).isEqualTo(1);  // 驳回的不越过用户意志
        assertThat(result.projectState()).isEqualTo(StoryboardService.STATE_STORYBOARD);
        assertThat(project.getState()).isEqualTo(StoryboardService.STATE_STORYBOARD);
        // 驳回镜的槽位未被写入
        Shot s0 = shots.findById(row0.shotId()).orElseThrow();
        assertThat(s0.getNegativePrompt()).isNull();
    }

    @Test
    void confirmAll_emptyStoryboard_throws() {
        assertThatThrownBy(() -> service.confirmAll(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚无分镜");
    }

    // ── 逐行改 → 新草稿 → 再确认 ─────────────────────────

    @Test
    void editPrompt_createsNewDraft_andRollsBackConfirmedState() {
        service.generate(1L);
        service.confirmAll(1L);
        assertThat(project.getState()).isEqualTo(StoryboardService.STATE_STORYBOARD_CONFIRMED);

        StoryboardService.Row edited = service.editPrompt(1L,
                shots.byProject(1L).get(0).getId(), "李明在雨中抬头看霓虹灯牌，特写");

        // 改的结果是新草稿（不写槽位），项目状态回退待确认
        assertThat(edited.draftState()).isEqualTo("DRAFT");
        assertThat(edited.draftContent()).isEqualTo("李明在雨中抬头看霓虹灯牌，特写");
        assertThat(edited.confirmed()).isFalse();
        assertThat(project.getState()).isEqualTo(StoryboardService.STATE_STORYBOARD);
        // 槽位保留已确认版本，直到新草稿再确认
        Shot s0 = shots.findById(edited.shotId()).orElseThrow();
        assertThat(s0.getVisualPrompt()).doesNotContain("霓虹灯牌");

        // 再批量确认 → 新草稿写槽 → 状态重新推进
        StoryboardService.ConfirmResult result = service.confirmAll(1L);
        assertThat(result.confirmedNow()).isEqualTo(1);
        assertThat(result.stillPending()).isZero();
        assertThat(project.getState()).isEqualTo(StoryboardService.STATE_STORYBOARD_CONFIRMED);
        assertThat(shots.findById(edited.shotId()).orElseThrow().getVisualPrompt())
                .isEqualTo("李明在雨中抬头看霓虹灯牌，特写");
    }

    @Test
    void editPrompt_blankContent_throws() {
        service.generate(1L);
        assertThatThrownBy(() -> service.editPrompt(1L, shots.byProject(1L).get(0).getId(), "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── COMPOSED 状态衔接（②合成完成后项目状态推进）──────────

    @Test
    void confirmAll_onComposedProject_keepsComposedState() {
        service.generate(1L);
        service.confirmAll(1L);
        project.setState(ProjectComposeService.STATE_PROJECT_COMPOSED);

        StoryboardService.ConfirmResult again = service.confirmAll(1L);
        assertThat(again.stillPending()).isZero();
        // 重复批量确认不把已成片项目回退成 STORYBOARD_CONFIRMED
        assertThat(again.projectState()).isEqualTo(ProjectComposeService.STATE_PROJECT_COMPOSED);
        assertThat(project.getState()).isEqualTo(ProjectComposeService.STATE_PROJECT_COMPOSED);
    }

    @Test
    void editPrompt_onComposedProject_rollsBackToStoryboard() {
        service.generate(1L);
        service.confirmAll(1L);
        project.setState(ProjectComposeService.STATE_PROJECT_COMPOSED);

        // 改分镜 → 成片与新分镜不一致 → 回退待确认
        service.editPrompt(1L, shots.byProject(1L).get(0).getId(), "改后的画面描述");
        assertThat(project.getState()).isEqualTo(StoryboardService.STATE_STORYBOARD);
    }

    // ── 整表查询 ─────────────────────────────────────────

    @Test
    void table_unknownProject_throws404() {
        ProjectRepository projects = mock(ProjectRepository.class);
        when(projects.existsById(42L)).thenReturn(false);
        assertThatThrownBy(() -> new StoryboardService(projects, null, null, null, null, null,
                null, null, null, List.of()).table(42L))
                .isInstanceOf(NotFoundException.class);
    }
}
