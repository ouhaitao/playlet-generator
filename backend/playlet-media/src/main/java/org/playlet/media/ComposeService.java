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
 * <p>M1 实现：concat 拼接（硬切）+ amix 三层混音 + subtitles 烧字幕。
 * 转场（xfade）与逐镜音画对齐在 M2 细化。
 */
@Service
public class ComposeService {

    private static final Logger log = LoggerFactory.getLogger(ComposeService.class);

    private final FfmpegRunner ffmpeg;

    public ComposeService(FfmpegRunner ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    /**
     * 合成成品。
     *
     * @param videoFiles  按镜头顺序的视频文件（含 native 音轨，H3）
     * @param dialogueFiles 与 videoFiles 等长的台词音频（可为 null 表示该镜无台词）
     * @param bgmFile     BGM 文件（可为 null）
     * @param srtFile     字幕文件（可为 null）
     * @param outDir      输出目录
     * @param outName     输出文件名（不含扩展名）
     * @return 成品文件路径
     */
    public Path compose(List<Path> videoFiles, List<Path> dialogueFiles, Path bgmFile,
                        Path srtFile, Path outDir, String outName) {
        if (videoFiles == null || videoFiles.isEmpty()) {
            throw new MediaException("no video segments to compose");
        }
        try {
            Files.createDirectories(outDir);
            Path concatList = outDir.resolve(outName + "_concat.txt");
            StringBuilder sb = new StringBuilder();
            for (Path v : videoFiles) {
                sb.append("file '").append(v.toAbsolutePath().toString().replace("\\", "/"))
                        .append("'\n");
            }
            Files.writeString(concatList, sb.toString(), StandardCharsets.UTF_8);

            Path joined = outDir.resolve(outName + "_joined.mp4");
            ffmpeg.run(List.of("-y", "-f", "concat", "-safe", "0",
                    "-i", concatList.toString(), "-c", "copy", joined.toString()), 600);

            // 混音：native（视频自带）+ dialogue（拼接后的台词轨）+ bgm
            Path mixed = joinDialogue(dialogueFiles, outDir, outName)
                    .map(dlg -> mixWithDialogue(joined, dlg, bgmFile, outDir, outName))
                    .orElse(bgmFile != null ? mixWithBgm(joined, bgmFile, outDir, outName) : joined);

            Path finalOut = outDir.resolve(outName + ".mp4");
            if (srtFile != null) {
                burnSubtitles(mixed, srtFile, finalOut);
            } else if (mixed != joined) {
                Files.move(mixed, finalOut, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(joined, finalOut, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("compose done: {}", finalOut);
            return finalOut;
        } catch (IOException e) {
            throw new MediaException("compose io error: " + e.getMessage(), e);
        }
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

    /** native + dialogue 混音，台词响起时 ducking 压低 native。 */
    private Path mixWithDialogue(Path video, Path dialogue, Path bgm, Path outDir, String outName) {
        Path out = outDir.resolve(outName + "_mixed.mp4");
        List<String> args = new ArrayList<>(List.of("-y", "-i", video.toString(), "-i", dialogue.toString()));
        String filter;
        if (bgm != null) {
            args.addAll(List.of("-i", bgm.toString()));
            // 台词轨 asplit：一路做 sidechain 控制源，一路进混音；native 被 ducking 后三轨混合
            filter = "[1:a]asplit=2[d1][d2];"
                    + "[0:a][d1]sidechaincompress=threshold=0.05:ratio=8:attack=20:release=300[duck];"
                    + "[duck][d2][2:a]amix=inputs=3:duration=first:weights='1 1 0.35'[aout]";
        } else {
            filter = "[1:a]asplit=2[d1][d2];"
                    + "[0:a][d1]sidechaincompress=threshold=0.05:ratio=8:attack=20:release=300[duck];"
                    + "[duck][d2]amix=inputs=2:duration=first[aout]";
        }
        args.addAll(List.of("-c:v", "copy", "-c:a", "aac", out.toString()));
        ffmpeg.run(args, 600);
        return out;
    }

    /** native + bgm 混音（无台词）。 */
    private Path mixWithBgm(Path video, Path bgm, Path outDir, String outName) {
        Path out = outDir.resolve(outName + "_mixed.mp4");
        ffmpeg.run(List.of("-y", "-i", video.toString(), "-i", bgm.toString(),
                "-filter_complex",
                "[1:a]volume=0.35[bg];[0:a][bg]amix=inputs=2:duration=first[aout]",
                "-map", "0:v", "-map", "[aout]", "-c:v", "copy", "-c:a", "aac", out.toString()), 600);
        return out;
    }

    /** 烧字幕（ASS/SRT 皆可，路径转义给 subtitles 滤镜）。 */
    private void burnSubtitles(Path video, Path srt, Path out) {
        String escaped = srt.toAbsolutePath().toString().replace("\\", "/").replace(":", "\\:");
        ffmpeg.run(List.of("-y", "-i", video.toString(),
                "-vf", "subtitles='" + escaped + "'",
                "-c:v", "libx264", "-preset", "fast", "-c:a", "copy", out.toString()), 900);
    }
}
