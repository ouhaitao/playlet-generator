package org.playlet.llm;

import java.util.List;

/**
 * LLM 传输抽象（DESIGN.md §1.1）。v1 唯一实现为 OpenAI 兼容接口，base-url 可切换：
 * 局域网指向内网 LLM，外网指向公网 OpenAI 兼容 API。
 */
public interface LlmClient {

    /**
     * 单轮对话补全。
     *
     * @param systemPrompt 系统提示（槽位 schema、优化规则等）
     * @param userMessage  本轮用户输入 / 当前槽位状态摘要
     * @return 模型文本输出
     */
    String complete(String systemPrompt, String userMessage);

    /** 多轮对话补全（历史消息 + 本轮）。 */
    String complete(List<ChatMessage> history, String userMessage);

    /** 一条对话消息。 */
    record ChatMessage(String role, String content) {
        public static ChatMessage user(String c) { return new ChatMessage("user", c); }
        public static ChatMessage assistant(String c) { return new ChatMessage("assistant", c); }
        public static ChatMessage system(String c) { return new ChatMessage("system", c); }
    }
}
