package org.playlet.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 成品合成（DESIGN.md §8）：拼接 + 转场 + 三层混音 + 字幕。
 *
 * <p>三层音频：
 * <ul>
 *   <li>native —— 视频自带环境音（H3）</li>
 *   <li>dialogue —— TTS 台词轨</li>
 *   <li>bgm —— 全片连续 BGM 轨</li>
 * </ul>
 * 台词响起时对 native 做 ducking（sidechaincompress）。
 *
 * <p>M1 实现：concat 拼接（硬切，先试 -c copy，编码参数不齐时回退重编码）
 * + amix 三层混音 + subtitles 烧字幕。转场（xfade）与逐镜音画对齐在 M2 细化。
 *
 * <p>健壮性（真实素材 E2E 结论）：
 * <ul>
 *   <li>视频**无音轨**（H3 关 audio 槽 = 静音视频）时混音滤镜不再引用 [0:a]，
 *       只混 dialogue/bgm；两者皆无则跳过混音。</li>
 *   <li>concat demuxer 要求所有分片流参数一致（编码器/分辨率/采样率）；
 *       不一致时 copy 模式响亮失败，自动回退「concat 滤镜 + 统一重编码 libx264/aac」。</li>
 * </ul>
 */
@Service
public class ComposeService {

    private static final Logger log = LoggerFactory.getLogger(ComposeService.class);

    private final FfmpegRunner ffmpeg;

    public ComposeService(FfmpegRunner ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    /** 阶段化进度回调：stage ∈ concat | mix | subtitle；percent 0~100。 */
    public interface ComposeProgress {
        void onStage(String stage, double percent);
    }

    /**
     * 合成成品。
     *
     * @param videoFiles    按镜头顺序的视频文件（含 native 音轨，H3）
     * @param dialogueFiles 与 videoFiles 等长的台词音频（可为 null 表示该镜无台词）
     * @param bgmFile       BGM 文件（可为 null）
     * @param srtFile       字幕文件（可为 null）
     * @param outDir        输出目录
     * @param outName       输出文件名（不含扩展名）
     * @return 成品文件路径
     */
    public Path compose(List<Path> videoFiles, List<Path> dialogueFiles, Path bgmFile,
                        Path srtFile, Path outDir, String outName) {
        return compose(videoFiles, dialogueFiles, bgmFile, srtFile, outDir, outName, null);
    }

    /** 同 {@link #compose(List, List, Path, Path, Path, String)}，带阶段进度回调。 */
    public Path compose(List<Path> videoFiles, List<Path> dialogueFiles, Path bgmFile,
                        Path srtFile, Path outDir, String outName, ComposeProgress progress) {
        if (videoFiles == null || videoFiles.isEmpty()) {
            throw new MediaException("no video segments to compose");
        }
        try {
            Files.createDirectories(outDir);
            // 总时长（ffprobe 求和）→ ffmpeg time= 换算全局进度
            double totalSec = 0;
            for (Path v : videoFiles) {
                if (!Files.exists(v)) {
                    throw new MediaException("video segment missing: " + v);
                }
                totalSec += ffmpeg.probeDuration(v.toString());
            }
            if (totalSec <= 0) {
                throw new MediaException("video segments have zero total duration");
            }

            Path joined = concat(videoFiles, outDir, outName, totalSec, progress);

            // 混音：native（视频自带，可能无音轨）+ dialogue（拼接后的台词轨）+ bgm
            boolean hasNative = ffmpeg.probeHasAudio(joined.toString());
            java.util.Optional<Path> dialogue = joinDialogue(dialogueFiles, outDir, outName);
            Path mixed = mix(joined, hasNative, dialogue.orElse(null), bgmFile,
                    outDir, outName, totalSec, progress);

            Path finalOut = outDir.resolve(outName + ".mp4");
            if (srtFile != null) {
                report(progress, "subtitle", 0);
                burnSubtitles(mixed, srtFile, finalOut, totalSec, progress);
            } else {
                Files.move(mixed, finalOut, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                report(progress, "subtitle", 100);
            }
            log.info("compose done: {} ({} segments, {}s total)", finalOut, videoFiles.size(),
                    String.format("%.2f", totalSec));
            return finalOut;
        } catch (IOException e) {
            throw new MediaException("compose io error: " + e.getMessage(), e);
        }
    }

    /**
     * 拼接：concat demuxer + 流复制（同后端产物参数一致，秒级完成）；
     * 分片参数不齐（混用后端/分辨率不一）时回退 concat 滤镜统一重编码。
     */
    private Path concat(List<Path> videoFiles, Path outDir, String outName,
                        double totalSec, ComposeProgress progress) {
        report(progress, "concat", 0);
        try {
            Path concatList = outDir.resolve(outName + "_concat.txt");
            StringBuilder sb = new StringBuilder();
            for (Path v : videoFiles) {
                sb.append("file '").append(v.toAbsolutePath().toString().replace("\\", "/"))
                        .append("'\n");
            }
            Files.writeString(concatList, sb.toString(), StandardCharsets.UTF_8);

            Path joined = outDir.resolve(outName + "_joined.mp4");
            try {
                ffmpeg.run(List.of("-y", "-f", "concat", "-safe", "0",
                                "-i", concatList.toString(), "-c", "copy", joined.toString()),
                        600, totalSec, pct -> report(progress, "concat", pct));
                return joined;
            } catch (MediaException e) {
                log.warn("concat copy failed ({}), falling back to re-encode", firstLine(e.getMessage()));
                ffmpeg.run(concatFilterArgs(videoFiles, outDir, outName),
                        1800, totalSec, pct -> report(progress, "concat", pct));
                return joined;
            }
        } catch (IOException e) {
            throw new MediaException("concat list write failed: " + e.getMessage(), e);
        }
    }

    /** concat 滤镜重编码参数：scale+pad 统一到首片分辨率、fps/SAR/音轨采样率归一。 */
    private List<String> concatFilterArgs(List<Path> videoFiles, Path outDir, String outName) {
        int[] ref = ffmpeg.probeVideoSize(videoFiles.get(0).toString());
        int w = ref[0] % 2 == 0 ? ref[0] : ref[0] + 1;
        int h = ref[1] % 2 == 0 ? ref[1] : ref[1] + 1;
        List<String> args = new ArrayList<>(List.of("-y"));
        List<Boolean> hasAudio = new ArrayList<>();
        for (Path v : videoFiles) {
            args.add("-i");
            args.add(v.toString());
            hasAudio.add(ffmpeg.probeHasAudio(v.toString()));
        }
        StringBuilder fc = new StringBuilder();
        for (int i = 0; i < videoFiles.size(); i++) {
            fc.append('[').append(i).append(":v]scale=").append(w).append(':').append(h)
                    .append(":force_original_aspect_ratio=decrease,")
                    .append("pad=").append(w).append(':').append(h)
                    .append(":(ow-iw)/2:(oh-ih)/2,setsar=1,fps=24,format=yuv420p[v").append(i).append("];");
            if (Boolean.TRUE.equals(hasAudio.get(i))) {
                fc.append('[').append(i).append(":a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo[a")
                        .append(i).append("];");
            } else {
                // 静音视频（H3 关 audio 槽）：按该片时长生成无声轨，保持 concat 滤镜输入对齐
                double d = ffmpeg.probeDuration(videoFiles.get(i).toString());
                fc.append("aevalsrc=0:channel_layout=stereo:sample_rate=44100:d=")
                        .append(String.format("%.3f", d)).append("[a").append(i).append("];");
            }
        }
        for (int i = 0; i < videoFiles.size(); i++) {
            fc.append("[v").append(i).append("][a").append(i).append(']');
        }
        fc.append("concat=n=").append(videoFiles.size())
                .append(":v=1:a=1[vout][aout]");
        args.addAll(List.of("-filter_complex", fc.toString(),
                "-map", "[vout]", "-map", "[aout]",
                "-c:v", "libx264", "-preset", "fast", "-crf", "20", "-c:a", "aac",
                outDir.resolve(outName + "_joined.mp4").toString()));
        return args;
    }

    /** 把逐镜台词音频拼成一条与视频对齐的轨（无台词处补静音）。 */
    private java.util.Optional<Path> joinDialogue(List<Path> dialogueFiles, Path outDir, String outName) {
        if (dialogueFiles == null || dialogueFiles.stream().allMatch(java.util.Objects::isNull)) {
            return java.util.Optional.empty();
        }
        try {
            // 简化 M1：台词轨按顺序 concat（静音补齐在 M2 按时间轴精确对齐）
            List<Path> present = new ArrayList<>();
            for (Path d : dialogueFiles) {
                if (d != null) present.add(d);
            }
            if (present.isEmpty()) return java.util.Optional.empty();
            Path list = outDir.resolve(outName + "_dlg.txt");
            StringBuilder sb = new StringBuilder();
            for (Path d : present) {
                sb.append("file '").append(d.toAbsolutePath().toString().replace("\\", "/")).append("'\n");
            }
            Files.writeString(list, sb.toString(), StandardCharsets.UTF_8);
            Path out = outDir.resolve(outName + "_dialogue.m4a");
            ffmpeg.run(List.of("-y", "-f", "concat", "-safe", "0", "-i", list.toString(),
                    "-c", "aac", out.toString()), 300);
            return java.util.Optional.of(out);
        } catch (IOException e) {
            throw new MediaException("join dialogue failed: " + e.getMessage(), e);
        }
    }

    /**
     * 三层混音。native 不存在（静音视频）时滤镜链不引用 [0:a]；
     * dialogue/bgm 皆空时直接返回拼接产物（跳过混音）。
     */
    private Path mix(Path joined, boolean hasNative, Path dialogue, Path bgm,
                     Path outDir, String outName, double totalSec, ComposeProgress progress) {
        boolean hasDialogue = dialogue != null;
        boolean hasBgm = bgm != null && Files.exists(bgm);
        // 无额外轨需混入（dialogue/bgm 皆无）：native 已在拼接产物里（或本就是静音视频），
        // 直接返回，避免 amix=inputs=1 的无谓音频重编码。
        if (!hasDialogue && !hasBgm) {
            report(progress, "mix", 100);
            return joined;
        }
        report(progress, "mix", 0);
        Path out = outDir.resolve(outName + "_mixed.mp4");
        List<String> args = new ArrayList<>(List.of("-y", "-i", joined.toString()));
        StringBuilder filter = new StringBuilder();
        List<String> mixInputs = new ArrayList<>();
        int inputs = 1;

        // native（可选）；台词在时做 ducking
        if (hasNative) {
            if (hasDialogue) {
                filter.append("[1:a]asplit=2[d1][d2];")
                        .append("[0:a][d1]sidechaincompress=threshold=0.05:ratio=8:attack=20:release=300[duck];");
                mixInputs.add("[duck]");
                mixInputs.add("[d2]");
            } else {
                mixInputs.add("[0:a]");
            }
        } else if (hasDialogue) {
            // 无 native（静音视频）+ 有台词：台词轨直接进混音（无 ducking 对象）
            mixInputs.add("[1:a]");
        }
        if (hasDialogue) {
            args.addAll(List.of("-i", dialogue.toString()));
            inputs++;
        }
        if (hasBgm) {
            args.add("-i");
            args.add(bgm.toString());
            filter.append('[').append(inputs).append(":a]volume=0.35[bg];");
            mixInputs.add("[bg]");
            inputs++;
        }
        filter.append(String.join("", mixInputs))
                .append("amix=inputs=").append(mixInputs.size())
                .append(":duration=first:dropout_transition=0[aout]");

        args.addAll(List.of("-filter_complex", filter.toString(),
                "-map", "0:v", "-map", "[aout]",
                "-c:v", "copy", "-c:a", "aac", out.toString()));
        ffmpeg.run(args, 600, totalSec, pct -> report(progress, "mix", pct));
        return out;
    }

    /** 烧字幕（ASS/SRT 皆可，路径转义给 subtitles 滤镜）。 */
    private void burnSubtitles(Path video, Path srt, Path out, double totalSec, ComposeProgress progress) {
        String escaped = srt.toAbsolutePath().toString().replace("\\", "/").replace(":", "\\:");
        ffmpeg.run(List.of("-y", "-i", video.toString(),
                        "-vf", "subtitles='" + escaped + "'",
                        "-c:v", "libx264", "-preset", "fast", "-c:a", "copy", out.toString()),
                900, totalSec, pct -> report(progress, "subtitle", pct));
    }

    private static void report(ComposeProgress progress, String stage, double percent) {
        if (progress != null) {
            progress.onStage(stage, percent);
        }
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
