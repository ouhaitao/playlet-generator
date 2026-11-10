package org.playlet.api.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 前端事件 WebSocket：/ws/events。广播生成进度/完成/失败。
 * 单用户场景：连接数极少，直接 CopyOnWriteArrayList 管理。
 */
@Component
public class ProgressEventPublisher extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ProgressEventPublisher.class);

    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("ws client connected: {} (total {})", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("ws client closed: {} (total {})", session.getId(), sessions.size());
    }

    /** 广播事件到所有已连接前端。 */
    public void publish(ProgressEvent event) {
        String payload;
        try {
            payload = json.writeValueAsString(event);
        } catch (Exception e) {
            log.error("failed to serialize event", e);
            return;
        }
        for (WebSocketSession s : sessions) {
            if (!s.isOpen()) {
                sessions.remove(s);
                continue;
            }
            try {
                synchronized (s) {
                    s.sendMessage(new TextMessage(payload));
                }
            } catch (IOException e) {
                log.warn("ws send failed for {}: {}", s.getId(), e.getMessage());
                sessions.remove(s);
            }
        }
    }
}
