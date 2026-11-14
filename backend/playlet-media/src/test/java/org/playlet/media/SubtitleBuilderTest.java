package org.playlet.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SubtitleBuilder 离线测试（BACKLOG ②）：时间轴按分片真实时长累加、
 * 无台词镜头跳过、全无台词返回 empty、SRT 格式正确。
 */
class SubtitleBuilderTest {

    @TempDir
    Path tmp;

    private SubtitleBuilder builderWithDurations(double... durations) {
        FfmpegRunner ffmpeg = mock(FfmpegRunner.class);
        when(ffmpeg.probeDuration(anyString())).thenAnswer(inv -> {
            String file = inv.getArgument(0);
            // 只从**文件名去扩展名**取序号（seg0.mp4 → seg0 → 0）；
            // 临时目录名与 .mp4 都含数字，整路径或含扩展名解析会串号
            String base = java.nio.file.Paths.get(file).getFileName().toString();
            int dot = base.indexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            int idx = Integer.parseInt(base.replaceAll("\\D+", ""));
            return durations[idx]; // double → 自动装箱为 Double（勿用 List.of(double[])，会变 List<double[]>）
        });
        return new SubtitleBuilder(ffmpeg);
    }

    @Test
    void timestamp_formatsSrt() {
        assertThat(SubtitleBuilder.timestamp(0)).isEqualTo("00:00:00,000");
        assertThat(SubtitleBuilder.timestamp(5.167)).isEqualTo("00:00:05,167");
        assertThat(SubtitleBuilder.timestamp(3661.5)).isEqualTo("01:01:01,500");
    }

    @Test
    void buildSrt_accumulatesRealDurations_andSkipsSilentShots() throws Exception {
        // 三镜：5.167s（H3 帧网格实测值）+ 2s 无台词 + 3.5s
        SubtitleBuilder b = builderWithDurations(5.167, 2.0, 3.5);
        Optional<Path> srt = b.buildSrt(
                List.of(tmp.resolve("seg0.mp4"), tmp.resolve("seg1.mp4"), tmp.resolve("seg2.mp4")),
                java.util.Arrays.asList("第一句台词", null, "第三句台词"),
                tmp, "final");

        assertThat(srt).isPresent();
        String content = Files.readString(srt.get());
        // 无台词镜头被跳过，序号连续（1、2）
        assertThat(content).contains("1\n00:00:00,000 --> 00:00:05,167\n第一句台词");
        // 第三镜起点 = 5.167 + 2.0 = 7.167（按真实时长累加，不按名义值）
        assertThat(content).contains("2\n00:00:07,167 --> 00:00:10,667\n第三句台词");
        assertThat(srt.get().getFileName().toString()).isEqualTo("final.srt");
    }

    @Test
    void buildSrt_allBlank_returnsEmpty() {
        SubtitleBuilder b = builderWithDurations(5.0);
        Optional<Path> srt = b.buildSrt(
                List.of(tmp.resolve("seg0.mp4")), List.of("  "), tmp, "final");
        assertThat(srt).isEmpty();
    }

    @Test
    void buildSrt_emptyVideos_returnsEmpty() {
        SubtitleBuilder b = builderWithDurations();
        assertThat(b.buildSrt(List.of(), List.of(), tmp, "final")).isEmpty();
    }
}
