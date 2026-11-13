package org.playlet.api.web;

import org.playlet.comfyui.template.WorkflowLibraryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 工作流模板库 REST API（前端「工作流库」页）。
 * 导入走 JSON body（前端 FileReader 读文本上传），避免 multipart 依赖。
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowLibraryController {

    private final WorkflowLibraryService library;

    public WorkflowLibraryController(WorkflowLibraryService library) {
        this.library = library;
    }

    /** 模板库列表（内置 + 用户导入）。 */
    @GetMapping
    public List<WorkflowLibraryService.Entry> list() {
        return library.list();
    }

    /** 模板详情（槽位 + 参数化后的原文）。 */
    @GetMapping("/{*name}")
    public Map<String, Object> get(@PathVariable String name) throws IOException {
        return library.get(stripLeadingSlash(name));
    }

    /**
     * 导入工作流：body = API 格式 JSON 原文，?name=myflow 指定模板名。
     * 失败（校验/剪枝自检不过）返回 200 + ok=false + problems，前端展示；
     * 模板名不合法等参数错误抛 400。
     */
    @PostMapping
    public WorkflowLibraryService.ImportReport importWorkflow(
            @RequestParam String name,
            @RequestBody byte[] content) throws IOException {
        return library.importWorkflow(name, content);
    }

    /** 删除用户导入的模板。 */
    @DeleteMapping("/{*name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name) throws IOException {
        library.delete(stripLeadingSlash(name));
    }

    private String stripLeadingSlash(String s) {
        return s.startsWith("/") ? s.substring(1) : s;
    }

    /** 模板库业务错误 → 400 + message（前端直接展示）。 */
    @ExceptionHandler(WorkflowLibraryService.WorkflowLibraryException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> onLibraryError(WorkflowLibraryService.WorkflowLibraryException e) {
        return Map.of("message", e.getMessage() == null ? "导入失败" : e.getMessage());
    }
}
