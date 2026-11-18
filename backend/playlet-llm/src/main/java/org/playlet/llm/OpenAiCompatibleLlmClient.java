package org.playlet.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容实现（Spring AI）。base-url / api-key / model 全部来自配置变量。
 */
@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final ChatClient chatClient;

    public OpenAiCompatibleLlmClient(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String complete(String systemPrompt, String userMessage) {
        List<ChatMessage> history = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            history.add(ChatMessage.system(systemPrompt));
        }
        return complete(history, userMessage);
    }

    @Override
    public String complete(List<ChatMessage> history, String userMessage) {
        List<Message> messages = new ArrayList<>();
        for (ChatMessage m : history) {
            messages.add(toSpring(m));
        }
        messages.add(new UserMessage(userMessage));
        String out = chatClient.prompt().messages(messages).call().content();
        return out == null ? "" : out;
    }

    private Message toSpring(ChatMessage m) {
        return switch (m.role()) {
            case "system" -> new SystemMessage(m.content());
            case "assistant" -> new AssistantMessage(m.content());
            default -> new UserMessage(m.content());
        };
    }
}
