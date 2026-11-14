package org.playlet.media;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 字幕生成（DESIGN.md §8：分镜表本就含台词，烧字幕几乎免费，1.0 即带上）。
 *
 * <p>时间轴按视频分片的**真实时长**（ffprobe）累加，而非分镜表的名义时长——
 * H3 产物实际时长会对齐帧网格（如 5s → 5.167s），用名义值会让字幕逐镜漂移。
 * 无台词的分片跳过（纯画面镜头是明确支持的形态，§6.1）。
 */
@Component
public class SubtitleBuilder {

    private final FfmpegRunner ffmpeg;

    public SubtitleBuilder(FfmpegRunner ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    /**
     * 按视频分片顺序生成 SRT。
     *
     * @param videos    视频分片（与拼接顺序一致）
     * @param dialogues 与 videos 等长的台词（null/空白 = 该镜无台词）
     * @param outDir    输出目录
     * @param outName   输出文件名（不含扩展名）
     * @return SRT 文件；全部镜头无台词时返回 {@link Optional#empty()}
     */
    public Optional<Path> buildSrt(List<Path> videos, List<String> dialogues,
                                   Path outDir, String outName) {
        if (videos == null || videos.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        double t = 0;
        int index = 1;
        boolean any = false;
        for (int i = 0; i < videos.size(); i++) {
            double dur = ffmpeg.probeDuration(videos.get(i).toString());
            String text = (dialogues != null && i < dialogues.size()) ? dialogues.get(i) : null;
            if (text != null && !text.isBlank()) {
                any = true;
                sb.append(index++).append('\n')
                        .append(timestamp(t)).append(" --> ").append(timestamp(t + dur)).append('\n')
                        .append(text.trim()).append("\n\n");
            }
            t += dur;
        }
        if (!any) {
            return Optional.empty();
        }
        try {
            Files.createDirectories(outDir);
            Path srt = outDir.resolve(outName + ".srt");
            Files.writeString(srt, sb.toString(), StandardCharsets.UTF_8);
            return Optional.of(srt);
        } catch (IOException e) {
            throw new MediaException("write srt failed: " + e.getMessage(), e);
        }
    }

    /** 秒 → SRT 时间戳 HH:MM:SS,mmm。 */
    static String timestamp(double sec) {
        if (sec < 0) {
            sec = 0;
        }
        int h = (int) (sec / 3600);
        int m = (int) ((sec % 3600) / 60);
        int s = (int) (sec % 60);
        int ms = (int) Math.round((sec - Math.floor(sec)) * 1000);
        if (ms >= 1000) {
            ms -= 1000;
            s++;
        }
        return String.format("%02d:%02d:%02d,%03d", h, m, s, ms);
    }
}
