package org.playlet.api.config;

import org.playlet.api.web.ProgressEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** WebSocket 端点注册：/ws/events → 生成进度广播。 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ProgressEventPublisher publisher;

    public WebSocketConfig(ProgressEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(publisher, "/ws/events").setAllowedOrigins("*");
    }
}
