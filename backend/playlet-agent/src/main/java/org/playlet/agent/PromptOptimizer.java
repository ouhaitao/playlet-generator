package org.playlet.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.playlet.domain.entity.CharacterRef;
import org.playlet.domain.entity.Project;
import org.playlet.domain.entity.PromptDraft;
import org.playlet.domain.entity.Scene;
import org.playlet.domain.entity.Shot;
import org.playlet.domain.model.PromptDraftFieldName;
import org.playlet.domain.model.PromptDraftOwnerType;
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
 *
 * <p>另含一个与镜头/草稿业务无关的即席能力 {@link #generatePromptText}（审核台
 * 「生成提示词」按钮）：把工作流 model + 输入框内容 + 用户标识/反馈拼成一条消息发 LLM，
 * 返回文本给前端展示，不落库。
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

    /** {@link #generatePromptText} 的系统提示：即席提示词生成/优化，不产 JSON、不落库。 */
    private static final String GENERATE_SYSTEM_PROMPT = """
            你是 AI 图像/视频生成提示词专家。用户会给出：目标模型、提示词标识、当前提示词内容、用户反馈意见。

            规则：
            1. 按目标模型的提示词习惯（tag 串或自然语言描述）产出提示词，风格与该模型对齐。
            2. 严格遵守「提示词标识」：标识为负面/负向类时输出该模型可用的负向提示词（排除项），
               标识为正面/正向类时输出正向提示词（画面要素）。
            3. 保留当前提示词内容的语义要素，并针对用户反馈意见做针对性修改；反馈为空时只做质量提升。
            4. 只输出提示词文本本身——不要解释、不要 markdown、不要引号、不要 JSON、不要前后缀说明。
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
        Optimized out = optimizeWithLlm(base, context, feedback, true);
        if (out == null) {
            out = fallbackOptimize(base, project, shot);
        }
        return saveDraft(PromptDraftOwnerType.SHOT.name, shot.getId(), PromptDraftFieldName.VISUAL_PROMPT.name, base, out.prompt(), out.negative());
    }

    /**
     * 用户手改的提示词文本直接成为新草稿（§6.3：逐条修改的结果同样先成为草稿、再确认，
     * 不擅自过 LLM 重优化——用户改的文字就是用户想要的）。负向提示沿用上一版草稿，无则默认。
     *
     * @param shot    目标镜头（槽位现值作为 baseContent 留档）
     * @param content 用户修改后的提示词文本（非空）
     * @return 新草稿（DRAFT）
     */
    @Transactional
    public PromptDraft manualShotDraft(Shot shot, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("修改后的提示词不能为空");
        }
        String negative = drafts
                .findFirstByOwnerTypeAndOwnerIdAndFieldNameOrderByVersionNoDesc(
                    PromptDraftOwnerType.SHOT.name, shot.getId(), PromptDraftFieldName.VISUAL_PROMPT.name)
                .map(PromptDraft::getNegativePrompt)
                .filter(s -> s != null && !s.isBlank())
                .orElse(DEFAULT_NEGATIVE);
        return saveDraft(PromptDraftOwnerType.SHOT.name, shot.getId(), PromptDraftFieldName.VISUAL_PROMPT.name,
                shot.getVisualPrompt(), content.trim(), negative);
    }

    /**
     * 审核台「生成提示词」按钮：与工作流无关业务解耦的纯提示词生成能力。
     *
     * <p>把四个字段（工作流元信息的 model、CLIPTextEncode 输入框当前内容、用户填写的
     * 提示词标识、用户填写的反馈意见）构造成<b>一条消息</b>发给 LLM，返回其输出原文
     * （仅去 markdown 包裹）。<b>不落库、不产草稿、不写槽位</b>——结果仅供前端展示，
     * 由用户自行决定是否填入输入框。
     *
     * <p>与 {@link #optimizeShotPrompt} 的区别：那个是镜头维度的草稿机（走确认门），
     * 这个是工作流参数维度的即席工具（无状态）。LLM 不可用即抛
     * {@link IllegalStateException}，不做确定性回退——回退拼装对"生成/优化提示词"没有意义。
     *
     * @param model    工作流元信息的模型名（如 anima / minimax_h3），可空
     * @param content  CLIPTextEncode 输入框当前内容，可空（空 = 从零生成）
     * @param flag     提示词标识：用户填写的正面/负面等标记，可空
     * @param feedback 用户反馈意见（审核台驳回意见），可空
     * @return LLM 输出的提示词文本（非空）
     * @throws IllegalStateException LLM 不可用 / 输出为空
     */
    public String generatePromptText(String model, String content, String flag, String feedback) {
        StringBuilder user = new StringBuilder();
        user.append("目标模型：").append(blankTo(model, "未知")).append('\n');
        user.append("提示词标识：").append(blankTo(flag, "未指定")).append('\n');
        user.append("当前提示词内容：").append(blankTo(content, "（空，请从零生成）")).append('\n');
        user.append("用户反馈意见：").append(blankTo(feedback, "（无）")).append('\n');
        user.append("请针对上面的模型与标识，给出优化后的提示词。");

        String raw;
        try {
            raw = llm.complete(GENERATE_SYSTEM_PROMPT, user.toString());
        } catch (RuntimeException e) {
            throw new IllegalStateException("提示词生成失败（LLM 不可用）：" + e.getMessage(), e);
        }
        String out = PromptTranslator.sanitize(raw);
        if (out.isEmpty()) {
            throw new IllegalStateException("提示词生成失败（LLM 返回空输出）");
        }
        return out;
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
        return saveDraft(PromptDraftOwnerType.CHARACTER.name, character.getId(), "appearance", base, out.prompt(), out.negative());
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
        return saveDraft(PromptDraftOwnerType.SCENE.name, scene.getId(), "description", base, out.prompt(), out.negative());
    }

    // ── 内部 ──────────────────────────────────────────────

    private Optimized optimizeWithLlm(String base, String context, String feedback) {
        return optimizeWithLlm(base, context, feedback, false);
    }

    /** LLM 优化；任何失败返回 null（调用方走确定性回退）。 */
    private Optimized optimizeWithLlm(String base, String context, String feedback, boolean useChinese) {
        StringBuilder user = new StringBuilder();
        user.append(context).append('\n');
        user.append("原始描述：").append(base).append('\n');
        if (feedback != null && !feedback.isBlank()) {
            user.append("用户上一轮驳回意见（必须针对性修改）：").append(feedback).append('\n');
        }
        if (useChinese) {
            user.append("请生成中文提示词。");
        } else {
            user.append("请生成英文提示词。");
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

    /** 空白（null/全空白）→ 占位文案，用于 {@link #generatePromptText} 拼消息。 */
    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim();
    }

    /** LLM 优化产物。 */
    record Optimized(String prompt, String negative) {}
}
