package org.playlet.comfyui.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
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

/**
 * 工作流模板库管理（前端「工作流库」页的后端）：
 * <ul>
 *   <li>list —— 内置模板（classpath:workflows/**，随包发布）+ 用户模板
 *       （&lt;storage&gt;/workflows/**，前端导入落盘，同名覆盖内置）</li>
 *   <li>importWorkflow —— 收前端上传的 API 格式 JSON 原文 + 类型（IMAGE/VIDEO）+ 模型名
 *       （必填，提示词优化 agent 做模型级优化用）→ 走 {@link WorkflowImporter}
 *       （按节点分组提取字面量参数）→ 原文落盘 &lt;name&gt;.json、
 *       元数据落盘 &lt;name&gt;.meta.json；失败不留脏文件（备份回滚）</li>
 *   <li>get / delete —— 查看模板详情（类型 + 模型 + 解析元数据）、删除用户模板（内置只读）</li>
 * </ul>
 * 导入不需要 ComfyUI 在线（纯 JSON 解析）。
 */
@Service
public class WorkflowLibraryService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowLibraryService.class);
    /** 模板名：相对路径风格（允许一层子目录），字母数字下划线中划线。 */
    private static final java.util.regex.Pattern NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)?");
    private static final long MAX_UPLOAD_BYTES = 8 * 1024 * 1024;

    private final ObjectMapper json = new ObjectMapper();
    private final WorkflowImporter importer;
    private final WorkflowTemplateLoader loader;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public WorkflowLibraryService(WorkflowImporter importer, WorkflowTemplateLoader loader) {
        this.importer = importer;
        this.loader = loader;
    }

    /** 模板库条目。type/model 为 null 表示无解析元数据（如旧式内置模板），不可用于生成。 */
    public record Entry(String name, String source, WorkflowType type, String model,
                        Instant importedAt, long bytes, boolean overridden) {
    }

    /** 导入结果（给前端展示）。 */
    public record ImportReport(String name, boolean ok, List<String> problems,
                               WorkflowType type, String model, int nodeCount, int fieldCount) {
    }

    public List<Entry> list() {
        Set<String> userNames = new TreeSet<>();
        List<Entry> entries = new ArrayList<>();
        Path dir = loader.storageWorkflowsDir();
        if (dir != null && Files.isDirectory(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .filter(p -> !isSidecar(p.getFileName().toString()))
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
                if (isSidecar(rel)) {
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
                entries.set(i, new Entry(e.name(), e.source(), e.type(), e.model(),
                        e.importedAt(), e.bytes(), true));
            }
        }
        entries.sort(Comparator.comparing(Entry::name));
        return entries;
    }

    public ImportReport importWorkflow(String requestedName, WorkflowType type, String model,
                                       byte[] content) throws IOException {
        String name = normalizeName(requestedName);
        if (type == null) {
            return new ImportReport(name, false, List.of("工作流类型必填（IMAGE/VIDEO）"),
                    null, model, 0, 0);
        }
        String trimmedModel = model == null ? "" : model.trim();
        if (trimmedModel.isEmpty()) {
            // 提示词优化 agent 依赖模型名做模型级优化，缺失即隐性降级 → fail-loud
            return new ImportReport(name, false,
                    List.of("模型必填（该工作流使用的模型名，提示词优化 agent 依赖它做模型级优化）"),
                    type, null, 0, 0);
        }
        if (content == null || content.length == 0) {
            return new ImportReport(name, false, List.of("上传内容为空"), type, trimmedModel, 0, 0);
        }
        if (content.length > MAX_UPLOAD_BYTES) {
            return new ImportReport(name, false,
                    List.of("文件过大（>" + MAX_UPLOAD_BYTES / 1024 / 1024 + "MB）"),
                    type, trimmedModel, 0, 0);
        }
        try {
            JsonNode parsed = json.readTree(new String(content, StandardCharsets.UTF_8));
            if (!parsed.isObject()) {
                return new ImportReport(name, false, List.of("JSON 根不是对象"),
                        type, trimmedModel, 0, 0);
            }
        } catch (IOException e) {
            return new ImportReport(name, false, List.of("JSON 解析失败: " + e.getMessage()),
                    type, trimmedModel, 0, 0);
        }

        Path dir = loader.storageWorkflowsDir();
        if (dir == null) {
            return new ImportReport(name, false, List.of("未配置存储目录（playlet.storage.dir）"),
                    type, trimmedModel, 0, 0);
        }
        Path target = dir.resolve(name + ".json");
        Path backup = backupIfExists(target);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            WorkflowImporter.ImportResult result = importer.importWorkflow(target, type, trimmedModel);
            if (!result.problems().isEmpty()) {
                restore(backup, target);
                return new ImportReport(name, false, result.problems(), type, trimmedModel, 0, 0);
            }
            deleteQuietly(backup);
            deleteQuietly(backup == null ? null : WorkflowImporter.metaPath(backup));
            WorkflowMeta meta = result.meta();
            log.info("workflow imported via API: {} (type={}, model={}, {} field(s))",
                    name, type, trimmedModel, meta.fieldCount());
            return new ImportReport(name, true, List.of(), type, trimmedModel,
                    meta.groups().size(), meta.fieldCount());
        } catch (RuntimeException e) {
            restore(backup, target);
            return new ImportReport(name, false, List.of("导入失败: " + e.getMessage()),
                    type, trimmedModel, 0, 0);
        }
    }

    /** 模板详情：类型 + 模型 + 解析元数据（节点分组参数字段，前端据此渲染填写表单）。 */
    public Map<String, Object> get(String name) throws IOException {
        String n = normalizeName(name);
        Path fs = loader.resolveStoragePath(n, ".json");
        String source;
        if (fs != null) {
            source = "user";
        } else {
            try {
                loader.loadRaw(n);
            } catch (IllegalStateException e) {
                throw new WorkflowLibraryException(e.getMessage());
            }
            source = "builtin";
        }
        WorkflowMeta meta = loader.loadMeta(n);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", n);
        out.put("source", source);
        out.put("type", meta == null ? null : meta.type());
        out.put("model", meta == null ? null : meta.model());
        out.put("importedAt", meta == null ? null : meta.importedAt());
        out.put("groups", meta == null ? List.of() : meta.groups());
        return out;
    }

    /** 删除用户模板（及其 meta sidecar）；内置模板只读，拒绝。 */
    public void delete(String name) throws IOException {
        String n = normalizeName(name);
        Path fs = loader.resolveStoragePath(n, ".json");
        if (fs == null) {
            throw new WorkflowLibraryException("只能删除用户导入的模板（内置模板只读）: " + n);
        }
        Files.deleteIfExists(fs);
        Files.deleteIfExists(WorkflowImporter.metaPath(fs));
        log.info("user workflow deleted: {}", n);
    }

    /** 模板库业务错误（400）。 */
    public static class WorkflowLibraryException extends RuntimeException {
        public WorkflowLibraryException(String message) {
            super(message);
        }
    }

    private Entry entry(String name, String source, Path fsPath) {
        WorkflowType type = null;
        String model = null;
        try {
            WorkflowMeta meta = loader.loadMeta(name);
            if (meta != null) {
                type = meta.type();
                model = meta.model();
            }
        } catch (RuntimeException e) {
            log.warn("loadMeta failed for {}: {}", name, e.getMessage());
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
        return new Entry(name, source, type, model, importedAt, bytes, false);
    }

    private static boolean isSidecar(String fileName) {
        return fileName.endsWith(".meta.json") || fileName.endsWith(".options.json");
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
        Path meta = WorkflowImporter.metaPath(target);
        if (Files.exists(meta)) {
            Files.copy(meta, WorkflowImporter.metaPath(backup));
        }
        return backup;
    }

    private void restore(Path backup, Path target) throws IOException {
        deleteQuietly(target);
        deleteQuietly(WorkflowImporter.metaPath(target));
        if (backup != null) {
            Files.move(backup, target);
            Path bm = WorkflowImporter.metaPath(backup);
            if (Files.exists(bm)) {
                Files.move(bm, WorkflowImporter.metaPath(target));
            }
        }
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
