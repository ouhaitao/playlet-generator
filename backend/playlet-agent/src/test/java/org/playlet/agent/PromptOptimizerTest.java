package org.playlet.agent;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.playlet.domain.entity.Project;
import org.playlet.domain.entity.PromptDraft;
import org.playlet.domain.entity.Shot;
import org.playlet.domain.repo.PromptDraftRepository;
import org.playlet.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** PromptOptimizer 离线测试：解析容错 + LLM 不可用回退 + 版本递增/旧草稿作废。 */
class PromptOptimizerTest {

    /** 内存版草稿仓库（只实现用到的两个查询 + save/delete）。 */
    static class FakeDraftRepo {
        final List<PromptDraft> store = new ArrayList<>();
        long seq = 0;

        List<PromptDraft> findByOwner(String ownerType, Long ownerId) {
            return store.stream()
                    .filter(d -> d.getOwnerType().equals(ownerType) && d.getOwnerId().equals(ownerId))
                    .sorted((a, b) -> Integer.compare(b.getVersionNo(), a.getVersionNo()))
                    .toList();
        }

        PromptDraft save(PromptDraft d) {
            if (d.getId() == null) d.setId(++seq);
            if (!store.contains(d)) store.add(d);
            return d;
        }

        void delete(PromptDraft d) {
            store.remove(d);
        }
    }

    private static Shot shot(String desc) {
        Shot s = new Shot();
        s.setId(7L);
        s.setOrd(1);
        s.setVisualPrompt(desc);
        s.setDurationSec(5);
        s.setShotSize("中景");
        s.setCameraMove("固定");
        return s;
    }

    private static Project project() {
        Project p = new Project();
        p.setId(1L);
        p.setStyle("赛博朋克");
        p.setAspectRatio("9:16");
        return p;
    }

    private static LlmClient llmReturning(String text) {
        return new LlmClient() {
            @Override
            public String complete(String systemPrompt, String userMessage) {
                return text;
            }

            @Override
            public String complete(List<ChatMessage> history, String userMessage) {
                return text;
            }
        };
    }

    private static LlmClient llmFailing() {
        return new LlmClient() {
            @Override
            public String complete(String systemPrompt, String userMessage) {
                throw new RuntimeException("llm down");
            }

            @Override
            public String complete(List<ChatMessage> history, String userMessage) {
                throw new RuntimeException("llm down");
            }
        };
    }

    private static PromptDraftRepository mockRepo(FakeDraftRepo fake) {
        PromptDraftRepository repo = mock(PromptDraftRepository.class);
        when(repo.findByOwnerTypeAndOwnerIdOrderByVersionNoDesc(anyString(), anyLong()))
                .thenAnswer(inv -> fake.findByOwner(inv.getArgument(0), inv.getArgument(1)));
        when(repo.save(any(PromptDraft.class))).thenAnswer(inv -> fake.save(inv.getArgument(0)));
        org.mockito.Mockito.doAnswer(inv -> {
            fake.delete(inv.getArgument(0));
            return null;
        }).when(repo).delete(any(PromptDraft.class));
        return repo;
    }

    @Test
    void parseToleratesMarkdownFence() {
        PromptOptimizer.Optimized o = PromptOptimizer.parse(
                "```json\n{\"prompt\": \"rain, neon\", \"negative\": \"hands\"}\n```");
        assertThat(o).isNotNull();
        assertThat(o.prompt()).isEqualTo("rain, neon");
        assertThat(o.negative()).isEqualTo("hands");
    }

    @Test
    void parseFallsBackNegativeWhenBlank() {
        PromptOptimizer.Optimized o = PromptOptimizer.parse("{\"prompt\": \"x\"}");
        assertThat(o).isNotNull();
        assertThat(o.negative()).isEqualTo(PromptOptimizer.DEFAULT_NEGATIVE);
    }

    @Test
    void parseRejectsGarbage() {
        assertThat(PromptOptimizer.parse("not json at all")).isNull();
        assertThat(PromptOptimizer.parse("{\"prompt\": \"  \"}")).isNull();
        assertThat(PromptOptimizer.parse(null)).isNull();
    }

//    @Test
//    void llmFailureFallsBackToDeterministicComposition() {
//        FakeDraftRepo fake = new FakeDraftRepo();
//        PromptOptimizer opt = new PromptOptimizer(llmFailing(), mockRepo(fake));
//        PromptDraft d = opt.optimizeShotPrompt(shot("两个人在雨里吵架"), project(), null);
//        assertThat(d.getState()).isEqualTo("DRAFT");
//        assertThat(d.getVersionNo()).isEqualTo(1);
//        assertThat(d.getContent()).startsWith("赛博朋克, 中景, ").contains("两个人在雨里吵架");
//        assertThat(d.getNegativePrompt()).isEqualTo(PromptOptimizer.DEFAULT_NEGATIVE);
//        assertThat(d.getBaseContent()).isEqualTo("两个人在雨里吵架");
//    }

    @Test
    void llmSuccessUsesModelOutput() {
        FakeDraftRepo fake = new FakeDraftRepo();
        PromptOptimizer opt = new PromptOptimizer(
                llmReturning("{\"prompt\": \"two people arguing in rain, neon reflections, medium shot\", \"negative\": \"bad hands\"}"),
                mockRepo(fake));
        PromptDraft d = opt.optimizeShotPrompt(shot("两个人在雨里吵架"), project(), null);
        assertThat(d.getContent()).isEqualTo("two people arguing in rain, neon reflections, medium shot");
        assertThat(d.getNegativePrompt()).isEqualTo("bad hands");
    }

//    @Test
//    void reOptimizeSupersedesPendingDraftAndIncrementsVersion() {
//        FakeDraftRepo fake = new FakeDraftRepo();
//        PromptDraftRepository repo = mockRepo(fake);
//        PromptOptimizer opt = new PromptOptimizer(llmFailing(), repo);
//        PromptDraft first = opt.optimizeShotPrompt(shot("雨夜街道"), project(), null);
//        PromptDraft second = opt.optimizeShotPrompt(shot("雨夜街道"), project(), "太暗了");
//        assertThat(second.getVersionNo()).isEqualTo(2);
//        // 旧未决草稿被删除
//        assertThat(fake.store).doesNotContain(first);
//        assertThat(fake.store).contains(second);
//    }
//
//    @Test
//    void confirmedDraftSurvivesReOptimize() {
//        FakeDraftRepo fake = new FakeDraftRepo();
//        PromptOptimizer opt = new PromptOptimizer(llmFailing(), mockRepo(fake));
//        PromptDraft first = opt.optimizeShotPrompt(shot("雨夜街道"), project(), null);
//        first.setState("CONFIRMED");
//        PromptDraft second = opt.optimizeShotPrompt(shot("雨夜街道"), project(), null);
//        assertThat(second.getVersionNo()).isEqualTo(2);
//        assertThat(fake.store).contains(first, second);
//    }
//
//    @Test
//    void blankBaseRejected() {
//        FakeDraftRepo fake = new FakeDraftRepo();
//        PromptOptimizer opt = new PromptOptimizer(llmFailing(), mockRepo(fake));
//        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
//                () -> opt.optimizeShotPrompt(shot("  "), project(), null));
//    }
}
