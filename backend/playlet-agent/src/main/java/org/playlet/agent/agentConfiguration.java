package org.playlet.agent;

import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/**
 * agent配置
 * @author parry 2026/11/18
 */
@Configuration
public class agentConfiguration {
    @Bean
    public ToolCallback animaSkillTool() {
        return SkillsTool.builder()
            .addSkillsResource(new ClassPathResource("skills/anima"))
            .addSkillsResource(new ClassPathResource("skills/minimaxh3"))
            .build();
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ToolCallback animaSkillTool) {
        return builder
            .defaultTools(animaSkillTool)
            // 如果技能引用了 references 下的文件，必须加上这个，否则 Agent 读不到
            .defaultTools(FileSystemTools.builder().build())
            .build();
    }
}
