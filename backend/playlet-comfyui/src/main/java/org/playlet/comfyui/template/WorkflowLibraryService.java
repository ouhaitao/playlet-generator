package org.playlet.comfyui.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 工作流模板库管理（前端「工作流库」页的后端）：
 * <ul>
 *   <li>list —— 内置模板（classpath:workflows/**，随包发布）+ 用户模板
 *       （&lt;storage&gt;/workflows/**，前端导入落盘，同名覆盖内置）</li>
 *   <li>importWorkflow —— 收前端上传的 API 格式 JSON → 走 {@link WorkflowImporter}
 *       （校验 → 自动参数化 → 槽提取 → 2^k 剪枝自检）→ 落盘到存储目录；失败不留脏文件</li>
 *   <li>get / delete —— 查看模板详情（槽位 + 原文）、删除用户模板（内置只读）</li>
 * </ul>
 * 导入需要 ComfyUI 在线（节点类型经 /object_info 解析），离线时 fail-loud。
 */
@Service
public class WorkflowLibraryService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowLibraryService.class);
    /** 模板名：相对路径风格（允许一层子目录），字母数字下划线中划线。 */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)?");
    private static final long MAX_UPLOAD_BYTES = 8 * 1024 * 1024;

    private final ObjectMapper json = new ObjectMapper();
    private final WorkflowImporter importer;
    private final WorkflowTemplateLoader loader;
    private final NodeTypeResolver types;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public WorkflowLibraryService(WorkflowImporter importer, WorkflowTemplateLoader loader,
                                  NodeTypeResolver types) {
        this.importer = importer;
        this.loader = loader;
        this.types = types;
    }

    /** 模板库条目。 */
    public record Entry(String name, String source, List<SlotBrief> slots, Instant importedAt,
                        long bytes, boolean overridden) {
    }

    /** 槽位摘要（给前端展示）。 */
    public record SlotBrief(String name, String type, String sourceKind, String hostId) {
    }

    /** 导入结果（给前端展示）。 */
    public record ImportReport(String name, boolean ok, List<String> problems,
                               List<SlotBrief> slots, List<String> substitutions,
                               List<String> notes) {
    }

    public List<Entry> list() {
        Set<String> userNames = new TreeSet<>();
        List<Entry> entries = new ArrayList<>();
        Path dir = loader.storageWorkflowsDir();
        if (dir != null && Files.isDirectory(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .filter(p -> !p.getFileName().toString().endsWith(".options.json"))
                        .forEach(p -> {
                            String name = dir.relativize(p).toString().replace('\\', '/');
                            name = name.substring(0, name.length() - ".json".length());
                            userNames.add(name);
                            entries.add(entry(name, "user", p));
                        });
            } catch (IOException e) {
                log.warn("scan user workflows failed", e);
            }
        }

        Set<String> builtinNames = new TreeSet<>();
        try {
            for (Resource res : resolver.getResources("classpath*:workflows/**/*.json")) {
                String uri = res.getURL().toString();
                int idx = uri.indexOf("workflows/");
                String rel = uri.substring(idx + "workflows/".length());
                if (rel.endsWith(".options.json")) {
                    continue;
                }
                String name = rel.substring(0, rel.length() - ".json".length());
                if (name.isEmpty()) {
                    continue;
                }
                builtinNames.add(name);
                if (userNames.contains(name)) {
                    continue; // 用户版覆盖内置版，条目里标 overridden
                }
                entries.add(entry(name, "builtin", null));
            }
        } catch (IOException e) {
            log.warn("scan builtin workflows failed", e);
        }
        // 同名时给用户条目打上 overridden 标记
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if ("user".equals(e.source()) && builtinNames.contains(e.name())) {
                entries.set(i, new Entry(e.name(), e.source(), e.slots(), e.importedAt(),
                        e.bytes(), true));
            }
        }
        entries.sort(Comparator.comparing(Entry::name));
        return entries;
    }

    public ImportReport importWorkflow(String requestedName, byte[] content) throws IOException {
        String name = normalizeName(requestedName);
        if (content == null || content.length == 0) {
            return new ImportReport(name, false, List.of("上传内容为空"), List.of(), List.of(), List.of());
        }
        if (content.length > MAX_UPLOAD_BYTES) {
            return new ImportReport(name, false,
                    List.of("文件过大（>" + MAX_UPLOAD_BYTES / 1024 / 1024 + "MB）"), List.of(), List.of(), List.of());
        }
        ObjectNode raw;
        try {
            JsonNode parsed = json.readTree(new String(content, StandardCharsets.UTF_8));
            if (!parsed.isObject()) {
                throw new IOException("JSON 根不是对象");
            }
            raw = (ObjectNode) parsed;
        } catch (IOException e) {
            return new ImportReport(name, false, List.of("JSON 解析失败: " + e.getMessage()),
                    List.of(), List.of(), List.of());
        }
        // 粗查：UI 画布格式（有 nodes 数组 + links）不是 API 格式，直接拒
        if (raw.has("nodes") && raw.get("nodes").isArray() && raw.has("links")) {
            return new ImportReport(name, false,
                    List.of("这是 ComfyUI 画布格式（UI 导出）。请在 ComfyUI 设置里开启 Dev Mode，"
                            + "用「Save (API Format)」导出 API 格式 JSON 后再上传"),
                    List.of(), List.of(), List.of());
        }

        Path dir = loader.storageWorkflowsDir();
        if (dir == null) {
            return new ImportReport(name, false, List.of("未配置存储目录（playlet.storage.dir）"),
                    List.of(), List.of(), List.of());
        }
        Path target = dir.resolve(name + ".json");
        Path backup = backupIfExists(target);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            WorkflowImporter.ImportResult result = importer.importWorkflow(target);
            if (!result.problems().isEmpty()) {
                restore(backup, target);
                return new ImportReport(name, false, result.problems(),
                        brief(result.slots()), result.substitutions(), result.notes());
            }
            deleteQuietly(backup);
            log.info("workflow imported via API: {} ({} slot(s))", name, result.slots().size());
            return new ImportReport(name, true, List.of(), brief(result.slots()),
                    result.substitutions(), result.notes());
        } catch (RuntimeException e) {
            restore(backup, target);
            return new ImportReport(name, false, List.of("导入失败: " + e.getMessage()),
                    List.of(), List.of(), List.of());
        }
    }

    /** 模板详情：原文 + 槽位。 */
    public Map<String, Object> get(String name) throws IOException {
        String n = normalizeName(name);
        Path fs = loader.resolveStoragePath(n, ".json");
        JsonNode wf;
        String source;
        if (fs != null) {
            wf = json.readTree(Files.readString(fs));
            source = "user";
        } else {
            try {
                wf = loader.loadRaw(n);
            } catch (IllegalStateException e) {
                throw new WorkflowLibraryException(e.getMessage());
            }
            source = "builtin";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", n);
        out.put("source", source);
        out.put("slots", brief(loader.loadSlots(n)));
        out.put("workflow", wf);
        return out;
    }

    /** 删除用户模板（及其 sidecar）；内置模板只读，拒绝。 */
    public void delete(String name) throws IOException {
        String n = normalizeName(name);
        Path fs = loader.resolveStoragePath(n, ".json");
        if (fs == null) {
            throw new WorkflowLibraryException("只能删除用户导入的模板（内置模板只读）: " + n);
        }
        Files.deleteIfExists(fs);
        Files.deleteIfExists(sidecarOf(fs));
        log.info("user workflow deleted: {}", n);
    }

    /** 模板库业务错误（400）。 */
    public static class WorkflowLibraryException extends RuntimeException {
        public WorkflowLibraryException(String message) {
            super(message);
        }
    }

    private Entry entry(String name, String source, Path fsPath) {
        List<SlotBrief> slots = List.of();
        try {
            slots = brief(loader.loadSlots(name));
        } catch (RuntimeException e) {
            log.warn("loadSlots failed for {}: {}", name, e.getMessage());
        }
        Instant importedAt = null;
        long bytes = 0;
        if (fsPath != null) {
            try {
                importedAt = Files.getLastModifiedTime(fsPath).toInstant();
                bytes = Files.size(fsPath);
            } catch (IOException ignored) {
            }
        }
        return new Entry(name, source, slots, importedAt, bytes, false);
    }

    private List<SlotBrief> brief(List<WorkflowSlot> slots) {
        return slots.stream()
                .map(s -> new SlotBrief(s.name(), s.type(), s.sourceKind(), s.hostId()))
                .toList();
    }

    /** 校验并规整模板名；防目录穿越。 */
    private String normalizeName(String requestedName) {
        if (requestedName == null) {
            throw new WorkflowLibraryException("模板名必填");
        }
        String n = requestedName.trim().replace('\\', '/');
        if (n.endsWith(".json")) {
            n = n.substring(0, n.length() - ".json".length());
        }
        if (!NAME.matcher(n).matches()) {
            throw new WorkflowLibraryException(
                    "模板名不合法（仅允许字母数字_-，可带一层目录，如 mypack/myflow）: " + requestedName);
        }
        return n;
    }

    private Path backupIfExists(Path target) throws IOException {
        if (!Files.exists(target)) {
            return null;
        }
        Path backup = target.resolveSibling(target.getFileName() + ".bak-" + System.currentTimeMillis());
        Files.copy(target, backup);
        Path sidecar = sidecarOf(target);
        if (Files.exists(sidecar)) {
            Files.copy(sidecar, sidecarOf(backup));
        }
        return backup;
    }

    private void restore(Path backup, Path target) throws IOException {
        deleteQuietly(target);
        deleteQuietly(sidecarOf(target));
        if (backup != null) {
            Files.move(backup, target);
            Path bs = sidecarOf(backup);
            if (Files.exists(bs)) {
                Files.move(bs, sidecarOf(target));
            }
        }
    }

    private Path sidecarOf(Path templateJson) {
        String n = templateJson.getFileName().toString();
        int cut = n.indexOf(".json");
        return templateJson.resolveSibling(n.substring(0, cut) + ".options.json");
    }

    private void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }
}
