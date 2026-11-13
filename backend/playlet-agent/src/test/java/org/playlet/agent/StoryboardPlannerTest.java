package org.playlet.agent;

import org.junit.jupiter.api.Test;
import org.playlet.agent.StoryboardPlanner.Plan;
import org.playlet.agent.StoryboardPlanner.ShotSpec;
import org.playlet.domain.entity.Project;
import org.playlet.domain.model.ProjectMode;
import org.playlet.llm.LlmClient;
import org.playlet.llm.LlmClient.ChatMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 切镜服务离线测试（BACKLOG ①完成判据：切分-解析容错 + 无 LLM 回退切句）。
 * 不触网：LLM 用匿名实现模拟可用/不可用两种路径。
 */
class StoryboardPlannerTest {

    private static Project project(String synopsis) {
        Project p = new Project();
        p.setId(1L);
        p.setSynopsis(synopsis);
        p.setMode(ProjectMode.STORYBOARD);
        p.setAspectRatio("9:16");
        p.setStyle("都市现代");
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
                throw new IllegalStateException("LLM 未配置");
            }

            @Override
            public String complete(List<ChatMessage> history, String userMessage) {
                throw new IllegalStateException("LLM 未配置");
            }
        };
    }

    // ── 回退切句（无 LLM 环境 = 当前常态）─────────────────

    @Test
    void llmDown_fallsBackToSentenceSplit_oneShotPerSentence_default5s() {
        Plan plan = new StoryboardPlanner(llmFailing())
                .plan(project("雨夜，李明在便利店门口徘徊。他推门进去，撞见了前女友。两人相对无言。"));

        assertThat(plan.usedLlm()).isFalse();
        assertThat(plan.shots()).hasSize(3);
        assertThat(plan.shots().get(0).visualPrompt()).isEqualTo("雨夜，李明在便利店门口徘徊。");
        assertThat(plan.shots()).allSatisfy(s -> {
            assertThat(s.durationSec()).isEqualTo(StoryboardPlanner.DEFAULT_SHOT_SECONDS);
            assertThat(s.shotSize()).isEqualTo("中景");
            assertThat(s.cameraMove()).isEqualTo("固定");
            assertThat(s.characters()).isEmpty();
        });
    }

    @Test
    void fallback_splitsOnNewlinesAndMixedPunctuation() {
        Plan plan = new StoryboardPlanner(llmFailing())
                .plan(project("清晨的街道空无一人！\n她跑过转角？\n身后传来脚步声;"));

        assertThat(plan.shots()).hasSize(3);
        assertThat(plan.shots().get(0).visualPrompt()).isEqualTo("清晨的街道空无一人！");
        assertThat(plan.shots().get(1).visualPrompt()).isEqualTo("她跑过转角？");
        assertThat(plan.shots().get(2).visualPrompt()).isEqualTo("身后传来脚步声;");
    }

    @Test
    void fallback_singleSentenceWithoutPunctuation_isOneShot() {
        Plan plan = new StoryboardPlanner(llmFailing()).plan(project("一个没有标点的长句子"));
        assertThat(plan.shots()).hasSize(1);
        assertThat(plan.shots().get(0).visualPrompt()).isEqualTo("一个没有标点的长句子");
    }

    @Test
    void blankSynopsis_throws() {
        assertThatThrownBy(() -> new StoryboardPlanner(llmFailing()).plan(project("   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无剧情梗概");
    }

    @Test
    void punctuationOnlySynopsis_throws() {
        assertThatThrownBy(() -> new StoryboardPlanner(llmFailing()).plan(project("。！？\n；")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有任何镜头");
    }

    // ── LLM 路径：解析容错 ─────────────────────────────

    @Test
    void llmJsonArray_isParsedWithFields() {
        String json = """
                [
                  {"visual_prompt": "雨夜便利店门口，李明徘徊", "duration_sec": 6,
                   "shot_size": "全景", "camera_move": "推", "characters": ["李明"],
                   "scene": "便利店", "dialogue": "还开门吗", "speaker": "李明"},
                  {"visual_prompt": "推门撞见前女友", "duration_sec": 4,
                   "shot_size": "近景", "camera_move": "固定", "characters": ["李明", "小雅"]}
                ]
                """;
        Plan plan = new StoryboardPlanner(llmReturning(json)).plan(project("…梗概…"));

        assertThat(plan.usedLlm()).isTrue();
        assertThat(plan.shots()).hasSize(2);
        ShotSpec s0 = plan.shots().get(0);
        assertThat(s0.visualPrompt()).isEqualTo("雨夜便利店门口，李明徘徊");
        assertThat(s0.durationSec()).isEqualTo(6.0);
        assertThat(s0.shotSize()).isEqualTo("全景");
        assertThat(s0.cameraMove()).isEqualTo("推");
        assertThat(s0.characters()).containsExactly("李明");
        assertThat(s0.scene()).isEqualTo("便利店");
        assertThat(s0.dialogue()).isEqualTo("还开门吗");
        assertThat(s0.speaker()).isEqualTo("李明");
        ShotSpec s1 = plan.shots().get(1);
        assertThat(s1.characters()).containsExactly("李明", "小雅");
        assertThat(s1.dialogue()).isNull();
    }

    @Test
    void llmOutputWrappedInMarkdownFenceAndNoise_stillParsed() {
        String raw = """
                好的，分镜如下：
                ```json
                [{"visual_prompt": "特写：紧握的拳头", "duration_sec": 3}]
                ```
                以上。
                """;
        Plan plan = new StoryboardPlanner(llmReturning(raw)).plan(project("…"));
        assertThat(plan.usedLlm()).isTrue();
        assertThat(plan.shots()).hasSize(1);
        assertThat(plan.shots().get(0).visualPrompt()).isEqualTo("特写：紧握的拳头");
        // 缺省字段走默认
        assertThat(plan.shots().get(0).shotSize()).isEqualTo("中景");
        assertThat(plan.shots().get(0).cameraMove()).isEqualTo("固定");
    }

    @Test
    void llmGarbage_fallsBackToSentences() {
        Plan plan = new StoryboardPlanner(llmReturning("抱歉，我无法完成这个任务。"))
                .plan(project("他抬头看天。天上乌云密布。"));
        assertThat(plan.usedLlm()).isFalse();
        assertThat(plan.shots()).hasSize(2);
    }

    @Test
    void llmEntriesWithoutVisualPrompt_areDropped() {
        String json = """
                [{"duration_sec": 5},
                 {"visual_prompt": "", "duration_sec": 5},
                 {"visual_prompt": "有效的镜头", "duration_sec": 5}]
                """;
        List<ShotSpec> parsed = StoryboardPlanner.parseShots(json);
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).visualPrompt()).isEqualTo("有效的镜头");
    }

    @Test
    void llmDurationClampedTo3to10_andBadVocabFallsToDefault() {
        String json = """
                [{"visual_prompt": "A", "duration_sec": 99, "shot_size": "大特写镜头", "camera_move": "乱晃"},
                 {"visual_prompt": "B", "duration_sec": 0.5, "shot_size": "特写", "camera_move": "跟拍"},
                 {"visual_prompt": "C"}]
                """;
        List<ShotSpec> parsed = StoryboardPlanner.parseShots(json);
        assertThat(parsed).hasSize(3);
        assertThat(parsed.get(0).durationSec()).isEqualTo(10.0); // 上限夹取
        assertThat(parsed.get(0).shotSize()).isEqualTo("特写");   // "大特写"含词表词
        assertThat(parsed.get(0).cameraMove()).isEqualTo("固定"); // 不在词表 → 默认
        assertThat(parsed.get(1).durationSec()).isEqualTo(3.0);   // 下限夹取
        assertThat(parsed.get(1).cameraMove()).isEqualTo("跟");   // "跟拍"归一
        assertThat(parsed.get(2).durationSec()).isEqualTo(StoryboardPlanner.DEFAULT_SHOT_SECONDS);
    }

    @Test
    void llmSpeakerNotInCharacters_isAppended() {
        String json = """
                [{"visual_prompt": "A", "characters": ["甲"], "dialogue": "台词", "speaker": "乙"}]
                """;
        List<ShotSpec> parsed = StoryboardPlanner.parseShots(json);
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).characters()).containsExactly("甲", "乙");
    }

    @Test
    void parseShots_garbageReturnsNull() {
        assertThat(StoryboardPlanner.parseShots(null)).isNull();
        assertThat(StoryboardPlanner.parseShots("")).isNull();
        assertThat(StoryboardPlanner.parseShots("完全不是 JSON")).isNull();
        assertThat(StoryboardPlanner.parseShots("[]")).isNull();
        assertThat(StoryboardPlanner.parseShots("[{broken")).isNull();
    }

    @Test
    void splitSentences_static_helperHandlesWhitespaceAndEmptyEntries() {
        List<ShotSpec> shots = StoryboardPlanner.splitSentences("  第一句。  \n\n  第二句！  ");
        assertThat(shots).hasSize(2);
        assertThat(shots.get(0).visualPrompt()).isEqualTo("第一句。");
        assertThat(shots.get(1).visualPrompt()).isEqualTo("第二句！");
    }
}
