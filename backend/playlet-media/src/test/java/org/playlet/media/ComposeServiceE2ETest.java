package org.playlet.media;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ComposeService 真实素材 E2E（BACKLOG ② 完成标准：「多镜全 APPROVED 项目一键出可播成片文件」
 * 的媒体层验证）。用真实 ffmpeg 自造两段带音轨的彩色分片 → concat + 烧字幕 → 断言成片可播。
 *
 * <p>ffmpeg 路径来自系统属性 playlet.ffmpeg.base-path，或环境变量 PLAYLET_FFMPEG_PATH，
 * 或 PATH 上的 ffmpeg；三者皆不可用时整类跳过（离线基线不触 ffmpeg）。
 */
@EnabledIf("ffmpegAvailable")
class ComposeServiceE2ETest {

    static String ffmpegPath;

    @TempDir
    Path tmp;

    @BeforeAll
    static void resolveFfmpeg() {
        String p = System.getProperty("playlet.ffmpeg.base-path");
        if (p == null || p.isBlank()) p = System.getenv("PLAYLET_FFMPEG_PATH");
        if (p == null || p.isBlank()) p = "ffmpeg";
        ffmpegPath = p;
    }

    static boolean ffmpegAvailable() {
        resolveFfmpeg();
        try {
            Process proc = new ProcessBuilder(ffmpegPath, "-version")
                    .redirectErrorStream(true).start();
            boolean done = proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            return done && proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 造一段带正弦音轨的彩色测试分片（模拟 H3 视频产物：含 native 音轨）。 */
    private Path makeSegment(String name, String color, double seconds, int freq) {
        Path out = tmp.resolve(name);
        new FfmpegRunner(ffmpegPath).run(List.of("-y",
                "-f", "lavfi", "-i", "color=c=" + color + ":s=320x240:d=" + seconds + ":r=24",
                "-f", "lavfi", "-i", "sine=frequency=" + freq + ":duration=" + seconds,
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest", out.toString()), 120);
        return out;
    }

    @Test
    void compose_concatsRealSegments_andBurnsSubtitles_playable() throws Exception {
        FfmpegRunner runner = new FfmpegRunner(ffmpegPath);
        ComposeService service = new ComposeService(runner);

        Path seg1 = makeSegment("seg1.mp4", "red", 2.0, 440);
        Path seg2 = makeSegment("seg2.mp4", "blue", 3.0, 660);

        // SRT：按真实分片时长手写（与 SubtitleBuilder 产物同格式）
        Path srt = tmp.resolve("final.srt");
        Files.writeString(srt, "1\n00:00:00,000 --> 00:00:02,000\n第一镜台词\n\n"
                + "2\n00:00:02,000 --> 00:00:05,000\n第二镜台词\n\n");

        List<String> stages = new java.util.ArrayList<>();
        Path out = service.compose(
                List.of(seg1, seg2),
                null, // dialogue TTS 在 BACKLOG ⑤，此处只验拼接+字幕
                null, // bgm
                srt,
                tmp, "final",
                (stage, pct) -> stages.add(stage));

        // 成品存在、非空、可被 ffprobe 解析（可播的最低判据）
        assertThat(Files.exists(out)).isTrue();
        assertThat(Files.size(out)).isGreaterThan(1000);
        double dur = runner.probeDuration(out.toString());
        assertThat(dur).isCloseTo(5.0, org.assertj.core.data.Offset.offset(0.5)); // 2 + 3
        int[] size = runner.probeVideoSize(out.toString());
        assertThat(size[0]).isEqualTo(320);
        assertThat(size[1]).isEqualTo(240);
        // 烧字幕会重编码视频；成品仍应含视频流（已验）+ 音轨
        assertThat(runner.probeHasAudio(out.toString())).isTrue();
        // 进度回调覆盖了 concat 与 subtitle 阶段
        assertThat(stages).contains("concat", "subtitle");
        assertThat(out.getFileName().toString()).isEqualTo("final.mp4");
    }

    @Test
    void compose_silentSegments_noAudio_stillProducesPlayableVideo() throws Exception {
        FfmpegRunner runner = new FfmpegRunner(ffmpegPath);
        ComposeService service = new ComposeService(runner);

        // 无音轨分片（H3 关 audio 槽 = 静音视频）
        Path seg1 = tmp.resolve("silent1.mp4");
        new FfmpegRunner(ffmpegPath).run(List.of("-y",
                "-f", "lavfi", "-i", "color=c=green:s=320x240:d=1.5:r=24",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                "-an", seg1.toString()), 120);
        Path seg2 = tmp.resolve("silent2.mp4");
        new FfmpegRunner(ffmpegPath).run(List.of("-y",
                "-f", "lavfi", "-i", "color=c=yellow:s=320x240:d=1.5:r=24",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                "-an", seg2.toString()), 120);

        Path out = service.compose(List.of(seg1, seg2), null, null, null, tmp, "silent");

        assertThat(Files.exists(out)).isTrue();
        double dur = runner.probeDuration(out.toString());
        assertThat(dur).isCloseTo(3.0, org.assertj.core.data.Offset.offset(0.5));
        int[] size = runner.probeVideoSize(out.toString());
        assertThat(size[0]).isEqualTo(320);
    }

    @Test
    void compose_withBgm_mixesAudio() throws Exception {
        FfmpegRunner runner = new FfmpegRunner(ffmpegPath);
        ComposeService service = new ComposeService(runner);

        Path seg1 = makeSegment("segA.mp4", "purple", 2.0, 300);
        // BGM 素材
        Path bgm = tmp.resolve("bgm.m4a");
        runner.run(List.of("-y", "-f", "lavfi", "-i", "sine=frequency=200:duration=4",
                "-c:a", "aac", bgm.toString()), 60);

        Path out = service.compose(List.of(seg1), null, bgm, null, tmp, "withbgm");

        assertThat(Files.exists(out)).isTrue();
        assertThat(runner.probeHasAudio(out.toString())).isTrue();
        assertThat(runner.probeDuration(out.toString())).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.5));
    }
}
