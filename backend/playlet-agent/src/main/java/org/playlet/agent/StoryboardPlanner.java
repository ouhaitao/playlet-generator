package org.playlet.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.playlet.domain.entity.Project;
import org.playlet.llm.LlmClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 切镜服务（BACKLOG ②-① / DESIGN.md §6.2）：剧情梗概 → 有序镜头数组。
 *
 * <p>LLM 可用时让模型直接产出镜头数组 JSON（粗糙 visual_prompt / duration / 景别 / 运镜 /
 * 角色 / 场景 / 台词）；LLM 不可用或输出不可解析时<b>确定性回退</b>：按句号/问号/叹号/换行
 * 切句，一句一镜、默认 5s——保证无 LLM 环境（当前常态）也能出整表。
 *
 * <p>本类只做"切"，不落库、不碰 duration 网格对齐（由 api 层 StoryboardService 结合
 * GenerationBackend.snapDuration 完成），也不产提示词草稿（切完逐镜交给
 * {@link PromptOptimizer#optimizeShotPrompt}）。
 */
@Service
public class StoryboardPlanner {

    /** 回退切镜的默认每镜时长（秒）；H3 帧网格约 5s/段。 */
    public static final double DEFAULT_SHOT_SECONDS = 5.0;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 句子边界：中英文句号/问号/叹号/分号，或换行。 */
    private static final Pattern SENTENCE_SPLIT =
            Pattern.compile("(?<=[。！？!?；;])|(?<=\\n)");

    private static final String SYSTEM_PROMPT = """
            你是短剧生成平台的分镜师。把用户的剧情梗概切成有序镜头序列，供 AI 视频生成使用。

            规则：
            1. 每镜是一个可以一次性生成的连续画面（约 5 秒），镜头之间允许跳切。
            2. visual_prompt 写"看得见的画面"：谁、在哪、做什么、什么氛围；不写心理活动与抽象叙述。
            3. duration_sec 为整数秒，3~10 之间，按画面信息量分配。
            4. shot_size 从「远景/全景/中景/近景/特写」中选；camera_move 从「固定/推/拉/摇/移/跟」中选。
            5. characters 列出该镜出场角色名（可空数组）；scene 是场景名（可空）；dialogue 是台词（可空，无台词镜头就是纯画面）。
            6. speaker 是有台词时的说话角色名（必须在 characters 里；无台词则为空）。
            7. 覆盖梗概的全部剧情，不遗漏、不加戏；镜头数通常在 3~30 之间。
            8. 只输出一个 JSON 数组，不要 markdown 代码块、不要解释：
               [{"visual_prompt": "...", "duration_sec": 5, "shot_size": "中景", "camera_move": "固定",
                 "characters": ["..."], "scene": "...", "dialogue": "...", "speaker": "..."}]
            """;

    private final LlmClient llm;

    public StoryboardPlanner(LlmClient llm) {
        this.llm = llm;
    }

    /** 一个镜头的切镜规格（未落库）。 */
    public record ShotSpec(
            String visualPrompt,
            double durationSec,
            String shotSize,
            String cameraMove,
            List<String> characters,
            String scene,
            String dialogue,
            String speaker) {

        static ShotSpec of(String visualPrompt) {
            return new ShotSpec(visualPrompt, DEFAULT_SHOT_SECONDS, "中景", "固定",
                    List.of(), null, null, null);
        }
    }

    /** 切镜结果：镜头序列 + 是否走了回退（前端可提示"未接 LLM，建议逐镜润色"）。 */
    public record Plan(List<ShotSpec> shots, boolean usedLlm) {}

    /**
     * 梗概 → 镜头数组。LLM 失败/输出不可解析时自动回退到切句。
     *
     * @throws IllegalArgumentException 梗概为空，或回退切句后仍然为空（纯标点等）
     */
    public Plan plan(Project project) {
        String synopsis = project.getSynopsis();
        if (synopsis == null || synopsis.isBlank()) {
            throw new IllegalArgumentException("项目 " + project.getId() + " 无剧情梗概，无法切镜");
        }
        List<ShotSpec> llmShots = tryLlm(project, synopsis);
        if (llmShots != null && !llmShots.isEmpty()) {
            return new Plan(llmShots, true);
        }
        List<ShotSpec> fallback = splitSentences(synopsis);
        if (fallback.isEmpty()) {
            throw new IllegalArgumentException("剧情梗概切分后没有任何镜头，请补充内容");
        }
        return new Plan(fallback, false);
    }

    // ── LLM 路径 ─────────────────────────────────────────────

    /** LLM 切镜；任何失败返回 null（调用方走回退）。 */
    private List<ShotSpec> tryLlm(Project project, String synopsis) {
        try {
            StringBuilder user = new StringBuilder();
            user.append("项目风格：")
                    .append(project.getStyle() == null || project.getStyle().isBlank()
                            ? "都市现代" : project.getStyle())
                    .append("；画幅：").append(project.getAspectRatio())
                    .append("；模式：").append(project.getMode() == null ? "STORYBOARD" : project.getMode())
                    .append("。\n");
            if (project.getTargetDurationSec() != null && project.getTargetDurationSec() > 0) {
                user.append("目标总时长约 ").append(project.getTargetDurationSec()).append(" 秒，镜头数据此分配。\n");
            }
            user.append("剧情梗概：\n").append(synopsis);
            String raw = llm.complete(SYSTEM_PROMPT, user.toString());
            return parseShots(raw);
        } catch (Exception e) {
            return null; // LLM 不可用/超时 → 回退
        }
    }

    /**
     * 解析 LLM 输出的镜头数组：容忍 markdown 代码块与前后杂音；
     * 逐镜容错（visual_prompt 缺失/空白的条目丢弃；duration 越界夹取到 3~10；
     * 景别/运镜不在词表内则回默认）。整体不可解析返回 null。
     */
    static List<ShotSpec> parseShots(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = raw.trim();
        int open = json.indexOf('[');
        int close = json.lastIndexOf(']');
        if (open < 0 || close <= open) {
            return null;
        }
        json = json.substring(open, close + 1);
        JsonNode arr;
        try {
            arr = MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
        if (!arr.isArray() || arr.isEmpty()) {
            return null;
        }
        List<ShotSpec> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String vp = text(n, "visual_prompt", "prompt", "description");
            if (vp == null) {
                continue; // 无画面描述的条目直接丢弃
            }
            double duration = clampDuration(n.path("duration_sec").isMissingNode()
                    ? n.path("duration") : n.path("duration_sec"));
            String shotSize = vocab(n.path("shot_size").asText(null), SHOT_SIZES, "中景");
            String cameraMove = vocab(n.path("camera_move").asText(null), CAMERA_MOVES, "固定");
            List<String> characters = new ArrayList<>();
            JsonNode cs = n.path("characters");
            if (cs.isArray()) {
                for (JsonNode c : cs) {
                    String name = c.asText("").trim();
                    if (!name.isEmpty()) {
                        characters.add(name);
                    }
                }
            }
            String scene = trimToNull(n.path("scene").asText(null));
            String dialogue = trimToNull(text(n, "dialogue", "line"));
            String speaker = trimToNull(n.path("speaker").asText(null));
            if (speaker != null && !characters.contains(speaker)) {
                characters.add(speaker); // speaker 必须出场
            }
            out.add(new ShotSpec(vp, duration, shotSize, cameraMove,
                    List.copyOf(characters), scene, dialogue, speaker));
        }
        return out.isEmpty() ? null : List.copyOf(out);
    }

    // ── 回退路径：切句 ─────────────────────────────────────────

    /** 确定性回退：按句号/问号/叹号/分号/换行切句，一句一镜、默认 5s。纯标点碎片不成镜。 */
    static List<ShotSpec> splitSentences(String synopsis) {
        List<ShotSpec> shots = new ArrayList<>();
        for (String part : SENTENCE_SPLIT.split(synopsis)) {
            String s = part.trim();
            if (s.isEmpty() || !MEANINGFUL.matcher(s).find()) {
                continue;
            }
            shots.add(ShotSpec.of(s));
        }
        return shots;
    }

    /** 含至少一个文字/数字才算有意义的镜头内容（过滤纯标点/空白碎片）。 */
    private static final Pattern MEANINGFUL = Pattern.compile("[\\p{L}\\p{N}]");

    // ── 工具 ─────────────────────────────────────────────────

    private static final List<String> SHOT_SIZES = List.of("远景", "全景", "中景", "近景", "特写");
    private static final List<String> CAMERA_MOVES = List.of("固定", "推", "拉", "摇", "移", "跟");

    private static String text(JsonNode n, String... keys) {
        for (String k : keys) {
            String v = trimToNull(n.path(k).asText(null));
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static double clampDuration(JsonNode node) {
        double d = node.asDouble(DEFAULT_SHOT_SECONDS);
        if (Double.isNaN(d) || d <= 0) {
            return DEFAULT_SHOT_SECONDS;
        }
        return Math.min(10.0, Math.max(3.0, d));
    }

    private static String vocab(String value, List<String> allowed, String dflt) {
        if (value == null) {
            return dflt;
        }
        String v = value.trim();
        for (String a : allowed) {
            if (v.contains(a)) {
                return a;
            }
        }
        return dflt;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
