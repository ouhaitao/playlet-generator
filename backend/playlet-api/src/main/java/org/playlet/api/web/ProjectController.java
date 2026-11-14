package org.playlet.api.web;

import org.playlet.api.service.ProjectComposeService;
import org.playlet.domain.entity.Project;
import org.playlet.domain.repo.ProjectRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 项目 REST API（前端 api.projects 对应）。 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ProjectRepository projects;
    private final ProjectComposeService composeService;

    public ProjectController(ProjectRepository projects, ProjectComposeService composeService) {
        this.projects = projects;
        this.composeService = composeService;
    }

    @GetMapping
    public List<Project> list() {
        return projects.findAllByOrderByCreatedAtDesc();
    }

    @GetMapping("/{id}")
    public Project get(@PathVariable Long id) {
        return projects.findById(id).orElseThrow(() -> new NotFoundException("project " + id));
    }

    @PostMapping
    public Project create(@RequestBody Project body) {
        String now = LocalDateTime.now().format(TS);
        body.setId(null);
        body.setCreatedAt(now);
        body.setUpdatedAt(now);
        if (body.getState() == null) {
            body.setState("DRAFT");
        }
        return projects.save(body);
    }

    /**
     * 一键合成成片（BACKLOG ②）。异步：校验通过即返回 COMPOSING，
     * 进度复用 WS /ws/events 的 compose_* 事件；完成后 Project.finalVideoPath
     * 指向成品（/media/store/ 前缀可播）。多镜未全 APPROVED → 400。
     */
    @PostMapping("/{id}/compose")
    public ProjectComposeService.ComposeResult compose(@PathVariable Long id) {
        return composeService.submit(id);
    }
}
