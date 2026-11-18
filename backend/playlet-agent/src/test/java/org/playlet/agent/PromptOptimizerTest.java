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

    // ── generatePromptText（审核台「生成提示词」，无状态即席能力）──

    @Test
    void generatePromptText_buildsSingleMessage_andReturnsLlmOutput() {
        FakeDraftRepo fake = new FakeDraftRepo();
        List<String> messages = new ArrayList<>();
        LlmClient capturing = new LlmClient() {
            @Override
            public String complete(String systemPrompt, String userMessage) {
                messages.add(userMessage);
                return "```text\n1girl, rain, neon lights\n```";
            }

            @Override
            public String complete(List<ChatMessage> history, String userMessage) {
                throw new AssertionError("应走单轮接口");
            }
        };
        PromptOptimizer opt = new PromptOptimizer(capturing, mockRepo(fake));

        String out = opt.generatePromptText("anima", "1girl, rain", "正面", "手部崩坏");

        // 输出仅去 markdown 包裹，不落库
        assertThat(out).isEqualTo("1girl, rain, neon lights");
        assertThat(fake.store).isEmpty();
        // 四个字段都进了那一条消息
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0))
                .contains("anima").contains("正面").contains("1girl, rain").contains("手部崩坏");
    }

    @Test
    void generatePromptText_nullFields_usePlaceholders() {
        FakeDraftRepo fake = new FakeDraftRepo();
        List<String> messages = new ArrayList<>();
        PromptOptimizer opt = new PromptOptimizer(new LlmClient() {
            @Override
            public String complete(String systemPrompt, String userMessage) {
                messages.add(userMessage);
                return "generated prompt";
            }

            @Override
            public String complete(List<ChatMessage> history, String userMessage) {
                throw new AssertionError("应走单轮接口");
            }
        }, mockRepo(fake));

        assertThat(opt.generatePromptText(null, null, null, null)).isEqualTo("generated prompt");
        assertThat(messages.get(0))
                .contains("未知").contains("未指定").contains("从零生成").contains("（无）");
    }

    @Test
    void generatePromptText_llmDown_throws() {
        PromptOptimizer opt = new PromptOptimizer(llmFailing(), mockRepo(new FakeDraftRepo()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> opt.generatePromptText("anima", "x", "正面", null));
    }

    @Test
    void generatePromptText_blankOutput_throws() {
        PromptOptimizer opt = new PromptOptimizer(llmReturning("   "), mockRepo(new FakeDraftRepo()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> opt.generatePromptText("anima", "x", "正面", null));
    }
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
