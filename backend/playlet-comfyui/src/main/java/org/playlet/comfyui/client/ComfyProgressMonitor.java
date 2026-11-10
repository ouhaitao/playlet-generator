package org.playlet.comfyui.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ComfyUI /ws 实时进度监听（DESIGN.md §4 ④层之下）。
 *
 * <p>接收三类消息：
 * <ul>
 *   <li>{@code progress}：{value, max, prompt_id} → 百分比</li>
 *   <li>{@code executing}：node=null 表示该 prompt 执行结束</li>
 *   <li>{@code execution_error}：执行失败</li>
 * </ul>
 *
 * <p>连接失败/断开自动重连（指数退避）。本监听器只做传输，完成判定以
 * history 轮询为准（{@link ComfyClient#awaitCompletion}），WS 断了不影响正确性。
 */
@Component
public class ComfyProgressMonitor {

    private static final Logger log = LoggerFactory.getLogger(ComfyProgressMonitor.class);

    /** 进度回调：onProgress(percent) / onFinished() / onError(msg)。 */
    public interface Listener {
        void onProgress(double percent);

        default void onFinished() {
        }

        default void onError(String message) {
        }
    }

    private final String baseUrl;
    private final String clientId = "playlet-" + System.nanoTime();
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "comfy-ws-reconnect");
                t.setDaemon(true);
                return t;
            });

    private final Map<String, Listener> listeners = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile WebSocket socket;
    private long backoffMs = 2000;

    public ComfyProgressMonitor(@Value("${playlet.comfyui.base-url}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @PostConstruct
    void start() {
        connect();
    }

    @PreDestroy
    void stop() {
        closed.set(true);
        scheduler.shutdownNow();
        if (socket != null) {
            socket.abort();
        }
    }

    /** 注册某 prompt_id 的进度监听；同 id 重复注册覆盖。 */
    public void listen(String promptId, Listener listener) {
        listeners.put(promptId, listener);
    }

    public void unlisten(String promptId) {
        listeners.remove(promptId);
    }

    private void connect() {
        if (closed.get()) {
            return;
        }
        String wsUrl = baseUrl.replaceFirst("^http", "ws") + "/ws?clientId=" + clientId;
        http.newWebSocketBuilder().buildAsync(URI.create(wsUrl), new Handler())
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        log.warn("comfyui ws connect failed ({}), retry in {}ms",
                                err.getMessage(), backoffMs);
                        scheduleReconnect();
                    } else {
                        socket = ws;
                        backoffMs = 2000;
                        log.info("comfyui ws connected: {}", wsUrl);
                    }
                });
    }

    private void scheduleReconnect() {
        long delay = backoffMs;
        backoffMs = Math.min(backoffMs * 2, 60_000);
        scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    private class Handler implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String msg = buffer.toString();
                buffer.setLength(0);
                try {
                    handle(msg);
                } catch (Exception e) {
                    log.warn("comfyui ws message parse failed: {}", e.getMessage());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("comfyui ws closed ({} {}), reconnecting", statusCode, reason);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("comfyui ws error: {}", error.getMessage());
            scheduleReconnect();
        }
    }

    private void handle(String raw) throws Exception {
        JsonNode msg = json.readTree(raw);
        String type = msg.path("type").asText("");
        JsonNode data = msg.path("data");
        String promptId = data.path("prompt_id").asText(null);
        if (promptId == null) {
            return;
        }
        Listener l = listeners.get(promptId);
        if (l == null) {
            return;
        }
        switch (type) {
            case "progress" -> {
                double value = data.path("value").asDouble(0);
                double max = data.path("max").asDouble(0);
                if (max > 0) {
                    l.onProgress(Math.min(100.0, value / max * 100.0));
                }
            }
            case "executing" -> {
                // node 为 null 表示整个 prompt 执行完毕
                if (data.path("node").isNull()) {
                    l.onFinished();
                }
            }
            case "execution_error" -> l.onError(data.path("exception_message").asText("execution error"));
            default -> {
                // 其余消息忽略
            }
        }
    }
}
