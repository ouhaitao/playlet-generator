package org.playlet.agent;

import org.playlet.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * 提示词翻译 agent：把中文提示词翻译成英文，供图像/视频生成后端消费。
 *
 * <p>定位（与 {@link PromptOptimizer} 互补）：优化器产出的草稿是<b>给用户看的中文</b>，
 * 经确认门写入槽位（{@code Shot.visualPrompt} / {@code negativePrompt}）；而 ComfyUI
 * 后端的文生图/文生视频模型对中文支持很差，<b>实际生成需要英文</b>。本类在提交生成前
 * 把槽位里的中文翻译成英文，整个流程对用户透明——用户始终看到、编辑的都是中文。
 *
 * <p>为什么在"提交前"翻译而不是切镜时一并产出英文：用户可以随时手改提示词
 * （{@link PromptOptimizer#manualShotDraft}）或驳回重优化，任何在切镜/优化阶段缓存的
 * 英文都会随之失效。提交前按当前槽位实时翻译，天然规避过期问题，且无需新增存储列。
 *
 * <p><b>LLM 不可用即报错，不做降级</b>（与 planner/optimizer 的同一策略，见 DESIGN.md
 * 「模块划分」）。中文提示词原样下发给 SD/H3 类模型只会产出不可控的垃圾图，静默回退
 * 比失败更糟——所以 LLM 挂了就让生成请求直接失败，把问题暴露在用户面前。
 *
 * <p>无需调用 LLM 的两种情况直接原样返回（这不是降级，是本来就不用翻）：
 * 空白文本，以及不含中日韩汉字的纯英文/符号文本。
 */
@Service
public class PromptTranslator {

    private static final Logger log = LoggerFactory.getLogger(PromptTranslator.class);

    /** 含中日韩统一表意文字（基本区 + 扩展 A + 兼容表意）即视作需要翻译的中文。 */
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff\\u3400-\\u4dbf\\uf900-\\ufaff]");

    private static final String SYSTEM_PROMPT = """
            You are a professional translator for AI image/video generation prompts.
            Translate the user's text into English.

            Rules:
            1. Preserve every visual element (subject, action, environment, mood, lighting, composition).
            2. Keep proper nouns (character names, place names) translated consistently; use common English renderings.
            3. Keep the comma-separated tag style if the input uses it; otherwise keep natural descriptive phrasing.
            4. Output ONLY the English translation — no explanations, no markdown, no quotes, no extra text.
            """;

    private final LlmClient llm;

    public PromptTranslator(LlmClient llm) {
        this.llm = llm;
    }

    /**
     * 把提示词翻译成英文。
     *
     * <ul>
     *   <li>null → null；空白 → 原样返回（无需翻译）</li>
     *   <li>不含中日韩汉字（已是英文/纯符号）→ 原样返回，不调用 LLM</li>
     *   <li>含中文 → 调 LLM 翻译；LLM 不可用或输出不可用 → 抛 {@link IllegalStateException}</li>
     * </ul>
     *
     * @param text 待翻译文本（通常是已确认的中文提示词）
     * @return 英文提示词
     * @throws IllegalStateException 需要翻译但 LLM 不可用 / 输出为空 / 输出仍含中文
     */
    public String toEnglish(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || !containsCjk(trimmed)) {
            return text; // 空白 / 已是英文：无需翻译，省一次 LLM 调用
        }

        String out;
        try {
            out = llm.complete(SYSTEM_PROMPT, trimmed);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "提示词翻译失败（LLM 不可用）：" + abbreviate(trimmed) + " — " + e.getMessage(), e);
        }

        String translated = sanitize(out);
        if (translated.isEmpty()) {
            throw new IllegalStateException(
                    "提示词翻译失败（LLM 返回空输出）：" + abbreviate(trimmed));
        }
        if (containsCjk(translated)) {
            throw new IllegalStateException(
                    "提示词翻译失败（LLM 输出仍含中文）：" + abbreviate(translated));
        }
        log.debug("提示词已译英: {} -> {}", abbreviate(trimmed), abbreviate(translated));
        return translated;
    }

    /** 是否含中日韩汉字。 */
    static boolean containsCjk(String s) {
        return s != null && CJK.matcher(s).find();
    }

    /** 清理 LLM 输出：去 markdown 代码块包裹、去首尾引号与空白。 */
    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        // 去掉 ```lang ... ``` 包裹
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) {
                s = s.substring(firstNl + 1);
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence);
            }
            s = s.trim();
        }
        // 去掉整体包裹的引号
        if (s.length() >= 2
                && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }
}
