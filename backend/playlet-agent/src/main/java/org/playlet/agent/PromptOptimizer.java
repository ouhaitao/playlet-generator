package org.playlet.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.playlet.domain.entity.CharacterRef;
import org.playlet.domain.entity.Project;
import org.playlet.domain.entity.PromptDraft;
import org.playlet.domain.entity.Scene;
import org.playlet.domain.entity.Shot;
import org.playlet.domain.repo.PromptDraftRepository;
import org.playlet.llm.LlmClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 提示词优化器（DESIGN.md §6.3）：把用户粗糙描述优化为生成级提示词草稿。
 *
 * <p>双重职责中的"优化者"：补景别/运镜/光线/风格前缀、注入角色与场景一致性锚点、
 * 生成负向提示、对齐后端提示词习惯。产物是 {@link PromptDraft}（DRAFT 状态），
 * <b>必须经用户确认（DraftController.confirm）才写入槽位</b>；驳回则带意见回到本类重优化。
 *
 * <p>LLM 不可用/输出不可解析时<b>确定性回退</b>：规则拼装（风格前缀 + 景别运镜 +
 * 角色/场景锚点 + 默认负向），保证确认门永远有草稿可审，主流程不被 LLM 可用性卡死。
 */
@Service
public class PromptOptimizer {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认负向提示（模型常见崩坏项）。 */
    public static final String DEFAULT_NEGATIVE =
            "lowres, bad anatomy, bad hands, extra fingers, missing fingers, extra limbs, "
                    + "fused fingers, malformed hands, watermark, signature, text, logo, "
                    + "jpeg artifacts, blurry, deformed, disfigured, mutation, mutated, "
                    + "ugly, duplicate, morbid, out of frame";

    private static final String SYSTEM_PROMPT = """
            你是短剧生成平台的提示词优化 agent。把用户的粗糙画面描述优化为图像/视频生成模型可用的提示词。

            规则：
            1. 保留用户描述的全部语义要素（人物、动作、环境、情绪），不得删改剧情含义。
            2. 补全：景别（远/全/中/近/特写）、运镜（固定/推/拉/摇/移/跟）、光线、构图。
            3. 注入项目风格前缀与角色/场景一致性锚点（外貌、服装、场景要素），用逗号分隔的短语。
            4. 输出语言与用户描述一致（中文描述输出中文短语串，英文输出英文 tag 串）。
            5. 负向提示列出该画面最可能崩坏的项（手部、文字水印、多余肢体等）。
            6. 只输出一个 JSON 对象，不要 markdown 代码块、不要解释：
               {"prompt": "优化后的提示词", "negative": "负向提示"}
            """;

    private final LlmClient llm;
    private final PromptDraftRepository drafts;

    public PromptOptimizer(LlmClient llm, PromptDraftRepository drafts) {
        this.llm = llm;
        this.drafts = drafts;
    }

    /**
     * 优化某镜的 visual_prompt：产出新草稿（versionNo 递增），旧 DRAFT 草稿自动作废。
     *
     * @param shot     目标镜头（提供当前描述与景别/运镜）
     * @param project  项目（风格/画幅上下文）
     * @param feedback 上一轮驳回意见；null = 首轮
     * @return 新草稿（DRAFT）
     */
    @Transactional
    public PromptDraft optimizeShotPrompt(Shot shot, Project project, String feedback) {
        String base = shot.getVisualPrompt();
        if (base == null || base.isBlank()) {
            throw new IllegalArgumentException("shot " + shot.getId() + " 无原始描述，无法优化");
        }
        String context = buildContext(project, shot);
        Optimized out = optimizeWithLlm(base, context, feedback);
        if (out == null) {
            out = fallbackOptimize(base, project, shot);
        }
        return saveDraft("SHOT", shot.getId(), "visual_prompt", base, out.prompt(), out.negative());
    }

    /** 优化角色外貌描述（参考图生成前确认）。 */
    @Transactional
    public PromptDraft optimizeCharacterAppearance(CharacterRef character, Project project, String feedback) {
        String base = character.getAppearance();
        if (base == null || base.isBlank()) {
            throw new IllegalArgumentException("character " + character.getId() + " 无外貌描述");
        }
        String context = "项目风格：" + style(project) + "。角色名：" + character.getName() + "。";
        Optimized out = optimizeWithLlm(base, context, feedback);
        if (out == null) {
            out = new Optimized(stylePrefix(project) + base + ", 全身立绘, 清晰面部", DEFAULT_NEGATIVE);
        }
        return saveDraft("CHARACTER", character.getId(), "appearance", base, out.prompt(), out.negative());
    }

    /** 优化场景描述（参考图生成前确认）。 */
    @Transactional
    public PromptDraft optimizeSceneDescription(Scene scene, Project project, String feedback) {
        String base = scene.getDescription();
        if (base == null || base.isBlank()) {
            throw new IllegalArgumentException("scene " + scene.getId() + " 无场景描述");
        }
        String context = "项目风格：" + style(project) + "。场景名：" + scene.getName() + "。";
        Optimized out = optimizeWithLlm(base, context, feedback);
        if (out == null) {
            out = new Optimized(stylePrefix(project) + base + ", 空场景, 无人物, 广角", DEFAULT_NEGATIVE);
        }
        return saveDraft("SCENE", scene.getId(), "description", base, out.prompt(), out.negative());
    }

    // ── 内部 ──────────────────────────────────────────────

    /** LLM 优化；任何失败返回 null（调用方走确定性回退）。 */
    private Optimized optimizeWithLlm(String base, String context, String feedback) {
        StringBuilder user = new StringBuilder();
        user.append(context).append('\n');
        user.append("原始描述：").append(base).append('\n');
        if (feedback != null && !feedback.isBlank()) {
            user.append("用户上一轮驳回意见（必须针对性修改）：").append(feedback).append('\n');
        }
        try {
            String raw = llm.complete(SYSTEM_PROMPT, user.toString());
            return parse(raw);
        } catch (Exception e) {
            return null; // LLM 不可用/超时/解析失败 → 回退
        }
    }

    /** 解析 LLM 输出：容忍 markdown 代码块包裹与前后杂音。 */
    static Optimized parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String json = raw.trim();
        int brace = json.indexOf('{');
        int last = json.lastIndexOf('}');
        if (brace < 0 || last <= brace) return null;
        json = json.substring(brace, last + 1);
        try {
            JsonNode node = MAPPER.readTree(json);
            String prompt = node.path("prompt").asText("");
            if (prompt.isBlank()) return null;
            String negative = node.path("negative").asText("");
            return new Optimized(prompt.trim(), negative.isBlank() ? DEFAULT_NEGATIVE : negative.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 确定性回退：风格前缀 + 景别/运镜 + 原始描述 + 默认负向。 */
    private Optimized fallbackOptimize(String base, Project project, Shot shot) {
        StringBuilder sb = new StringBuilder(stylePrefix(project));
        if (shot.getShotSize() != null && !shot.getShotSize().isBlank()) {
            sb.append(shot.getShotSize()).append(", ");
        }
        if (shot.getCameraMove() != null && !shot.getCameraMove().isBlank()
                && !"固定".equals(shot.getCameraMove())) {
            sb.append("镜头").append(shot.getCameraMove()).append(", ");
        }
        sb.append(base.trim());
        return new Optimized(sb.toString(), DEFAULT_NEGATIVE);
    }

    /** 项目/镜头上下文（喂给 LLM 的一致性锚点）。 */
    private String buildContext(Project project, Shot shot) {
        StringBuilder sb = new StringBuilder();
        sb.append("项目风格：").append(style(project)).append("；画幅：").append(project.getAspectRatio()).append("。\n");
        List<String> anchors = new ArrayList<>();
        if (shot.getCharacters() != null) {
            for (CharacterRef c : shot.getCharacters()) {
                anchors.add("角色[" + c.getName() + "]：" + nz(c.getAppearance()));
            }
        }
        if (shot.getScene() != null) {
            anchors.add("场景[" + shot.getScene().getName() + "]：" + nz(shot.getScene().getDescription()));
        }
        if (!anchors.isEmpty()) {
            sb.append("一致性锚点：").append(String.join("；", anchors)).append("。\n");
        }
        sb.append("景别：").append(nz(shot.getShotSize())).append("；运镜：").append(nz(shot.getCameraMove())).append("。");
        return sb.toString();
    }

    /** 落库：先算版本号（历史不丢），再作废同字段旧未决草稿。
     *  已确认/已驳回的历史保留，可经版本链回溯。 */
    private PromptDraft saveDraft(String ownerType, Long ownerId, String fieldName,
                                  String base, String content, String negative) {
        List<PromptDraft> existing = drafts.findByOwnerTypeAndOwnerIdOrderByVersionNoDesc(ownerType, ownerId);
        int next = existing.stream()
                .filter(d -> d.getFieldName().equals(fieldName))
                .mapToInt(PromptDraft::getVersionNo).max().orElse(0) + 1;
        for (PromptDraft old : existing) {
            if (old.getFieldName().equals(fieldName) && "DRAFT".equals(old.getState())) {
                drafts.delete(old);
            }
        }
        PromptDraft draft = new PromptDraft();
        draft.setOwnerType(ownerType);
        draft.setOwnerId(ownerId);
        draft.setFieldName(fieldName);
        draft.setBaseContent(base);
        draft.setContent(content);
        draft.setNegativePrompt(negative);
        draft.setVersionNo(next);
        draft.setState("DRAFT");
        draft.setCreatedAt(LocalDateTime.now().format(TS));
        return drafts.save(draft);
    }

    private static String stylePrefix(Project project) {
        String s = style(project);
        return s.isBlank() ? "" : s + ", ";
    }

    private static String style(Project project) {
        return project.getStyle() == null || project.getStyle().isBlank() ? "都市现代" : project.getStyle();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** LLM 优化产物。 */
    record Optimized(String prompt, String negative) {}
}
