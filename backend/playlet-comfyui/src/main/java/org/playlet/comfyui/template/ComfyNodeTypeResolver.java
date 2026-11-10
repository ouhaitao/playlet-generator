package org.playlet.comfyui.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 经 ComfyUI /object_info/{class_type} 解析节点类型，带缓存。
 */
@Component
public class ComfyNodeTypeResolver implements NodeTypeResolver {

    private final RestClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, JsonNode> cache = new ConcurrentHashMap<>();

    public ComfyNodeTypeResolver(@Value("${playlet.comfyui.base-url}") String baseUrl) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = RestClient.builder().baseUrl(base).build();
    }

    private JsonNode info(String classType) {
        return cache.computeIfAbsent(classType, ct -> {
            String body = http.get().uri("/object_info/{ct}", ct).retrieve().body(String.class);
            try {
                JsonNode root = json.readTree(body);
                JsonNode node = root.path(ct);
                if (node.isMissingNode()) {
                    throw new IllegalStateException("object_info missing node: " + ct);
                }
                return node;
            } catch (Exception e) {
                throw new IllegalStateException("failed to parse object_info for " + ct, e);
            }
        });
    }

    @Override
    public Map<String, String> requiredInputTypes(String classType) {
        return inputTypes(info(classType).path("input").path("required"));
    }

    @Override
    public Map<String, String> optionalInputTypes(String classType) {
        return inputTypes(info(classType).path("input").path("optional"));
    }

    @Override
    public List<String> outputTypes(String classType) {
        JsonNode out = info(classType).path("output");
        List<String> types = new ArrayList<>();
        if (out.isArray()) {
            out.forEach(t -> types.add(t.asText("UNKNOWN")));
        }
        return types;
    }

    @Override
    public boolean isOutputNode(String classType) {
        return info(classType).path("output_node").asBoolean(false);
    }

    private Map<String, String> inputTypes(JsonNode spec) {
        Map<String, String> map = new LinkedHashMap<>();
        spec.fields().forEachRemaining(e -> map.put(e.getKey(), typeOf(e.getValue())));
        return map;
    }

    private String typeOf(JsonNode specEntry) {
        if (!specEntry.isArray() || specEntry.isEmpty()) {
            return "UNKNOWN";
        }
        JsonNode first = specEntry.get(0);
        if (first.isTextual()) {
            return first.asText();
        }
        if (first.isArray()) {
            return "COMBO";
        }
        return "UNKNOWN";
    }
}
