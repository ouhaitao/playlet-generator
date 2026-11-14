package org.playlet.agent;

import org.junit.jupiter.api.Test;
import org.playlet.llm.LlmClient;
import org.playlet.llm.LlmClient.ChatMessage;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PromptTranslator 离线测试：中文检测跳过 / LLM 翻译 / LLM 不可用或输出不可用时抛异常。
 *
 * <p>策略：与 planner/optimizer 一致——<b>不做降级回退</b>。需要翻译而翻不出来就抛
 * {@link IllegalStateException}，让生成请求失败暴露问题（中文原样下发模型只会出垃圾图）。
 * 空白与纯英文属于"本来就不用翻"，原样返回、不调 LLM。
 *
 * 不触网：LLM 用匿名实现模拟。
 */
class PromptTranslatorTest {

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

    @Test
    void chineseTranslatedByLlm() {
        PromptTranslator t = new PromptTranslator(
                llmReturning("cyberpunk, medium shot, two people arguing in the rain"));
        assertThat(t.toEnglish("赛博朋克, 中景, 两个人在雨里吵架"))
                .isEqualTo("cyberpunk, medium shot, two people arguing in the rain");
    }

    @Test
    void alreadyEnglish_skipsLlm() {
        AtomicInteger calls = new AtomicInteger();
        PromptTranslator t = new PromptTranslator(new LlmClient() {
            @Override
            public String complete(String systemPrompt, String userMessage) {
                calls.incrementAndGet();
                return "should not be used";
            }

            @Override
            public String complete(List<ChatMessage> history, String userMessage) {
                calls.incrementAndGet();
                return "should not be used";
            }
        });
        assertThat(t.toEnglish("rainy night, neon signs, medium shot"))
                .isEqualTo("rainy night, neon signs, medium shot");
        assertThat(calls).hasValue(0);
    }

    @Test
    void nullAndBlank_passThroughWithoutLlm() {
        AtomicInteger calls = new AtomicInteger();
        PromptTranslator t = new PromptTranslator(new LlmClient() {
            @Override
            public String complete(String systemPrompt, String userMessage) {
                calls.incrementAndGet();
                return "x";
            }

            @Override
            public String complete(List<ChatMessage> history, String userMessage) {
                calls.incrementAndGet();
                return "x";
            }
        });
        assertThat(t.toEnglish(null)).isNull();
        assertThat(t.toEnglish("   ")).isEqualTo("   ");
        assertThat(calls).hasValue(0);
    }

    @Test
    void llmUnavailable_throws() {
        PromptTranslator t = new PromptTranslator(llmFailing());
        assertThatThrownBy(() -> t.toEnglish("雨夜，李明在便利店门口徘徊"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("提示词翻译失败")
                .hasMessageContaining("LLM 不可用");
    }

    @Test
    void llmReturnsBlank_throws() {
        PromptTranslator t = new PromptTranslator(llmReturning("   "));
        assertThatThrownBy(() -> t.toEnglish("雨夜街道"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM 返回空输出");
    }

    @Test
    void llmReturnsStillChinese_throws() {
        PromptTranslator t = new PromptTranslator(llmReturning("雨夜 street"));
        assertThatThrownBy(() -> t.toEnglish("雨夜街道"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("输出仍含中文");
    }

    @Test
    void sanitize_stripsFenceAndQuotes() {
        PromptTranslator t = new PromptTranslator(llmReturning("```text\nrainy night, neon\n```"));
        assertThat(t.toEnglish("雨夜霓虹")).isEqualTo("rainy night, neon");

        PromptTranslator t2 = new PromptTranslator(llmReturning("\"rainy night, neon\""));
        assertThat(t2.toEnglish("雨夜霓虹")).isEqualTo("rainy night, neon");
    }

    @Test
    void containsCjk_detectsChineseCharacters() {
        assertThat(PromptTranslator.containsCjk("cyberpunk, 中景, medium shot")).isTrue();
        assertThat(PromptTranslator.containsCjk("pure english tags, 9:16")).isFalse();
        assertThat(PromptTranslator.containsCjk("ひらがなカタカナ")).isFalse(); // 纯假名不在 CJK 表意区
        assertThat(PromptTranslator.containsCjk(null)).isFalse();
    }
}
