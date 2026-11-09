package org.playlet.comfyui.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * ComfyUI 原生 HTTP API 客户端（DESIGN.md §4 ④层之下）。
 * 地址全部来自配置变量，不硬编码。
 *
 * <p>注意：Spring Boot 4.x 默认 Jackson 3，RestClient 默认消息转换器不认识
 * Jackson 2 的 {@link JsonNode}，故一律用 String body + 显式 ObjectMapper 收发。
 */
@Component
public class ComfyClient {

    private final RestClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final String baseUrl;
    private final long pollIntervalMs;
    private final int timeoutSeconds;

    public ComfyClient(
            @Value("${playlet.comfyui.base-url}") String baseUrl,
            @Value("${playlet.comfyui.poll-interval-ms:1500}") long pollIntervalMs,
            @Value("${playlet.comfyui.timeout-seconds:1800}") int timeoutSeconds) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.pollIntervalMs = pollIntervalMs;
        this.timeoutSeconds = timeoutSeconds;
        this.http = RestClient.builder().baseUrl(this.baseUrl).build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** 提交 API 格式工作流，返回 prompt_id。 */
    public String submit(JsonNode workflow) {
        String payload;
        try {
            payload = json.writeValueAsString(Map.of("prompt", workflow));
        } catch (Exception e) {
            throw new ComfyUiException("failed to serialize workflow", e);
        }
        String respBody = http.post()
                .uri("/prompt")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(String.class);
        JsonNode resp = parse(respBody);
        if (resp == null || !resp.has("prompt_id")) {
            throw new ComfyUiException("submit failed: no prompt_id in response: " + respBody);
        }
        return resp.get("prompt_id").asText();
    }

    /** 查询执行状态；未完成返回 null。 */
    public JsonNode history(String promptId) {
        String respBody = http.get()
                .uri("/history/{id}", promptId)
                .retrieve()
                .body(String.class);
        JsonNode resp = parse(respBody);
        if (resp == null || !resp.has(promptId)) {
            return null;
        }
        return resp.get(promptId);
    }

    /** 阻塞等待完成，返回 history 条目；超时抛异常。 */
    public JsonNode awaitCompletion(String promptId) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            JsonNode h = history(promptId);
            if (h != null) {
                return h;
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ComfyUiException("interrupted while waiting for " + promptId, e);
            }
        }
        throw new ComfyUiException("timeout after " + timeoutSeconds + "s waiting for " + promptId);
    }

    /** 从 history 条目提取首个输出文件（filename/subfolder/type）。 */
    public JsonNode firstOutputFile(JsonNode historyEntry) {
        JsonNode outputs = historyEntry.path("outputs");
        for (JsonNode nodeOutputs : outputs) {
            for (String key : new String[]{"images", "videos", "gifs", "audio", "audios"}) {
                JsonNode arr = nodeOutputs.path(key);
                if (arr.isArray() && !arr.isEmpty()) {
                    return arr.get(0);
                }
            }
        }
        return null;
    }

    public ObjectMapper json() {
        return json;
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new ComfyUiException("failed to parse response: " + body, e);
        }
    }
}
