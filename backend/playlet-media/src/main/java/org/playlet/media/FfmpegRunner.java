package org.playlet.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ffmpeg 命令行封装。路径来自配置（playlet.ffmpeg.base-path），不在 PATH 上也能用。
 * 见 DESIGN.md §10.7 / BUILDING.md。
 */
@Component
public class FfmpegRunner {

    private static final Logger log = LoggerFactory.getLogger(FfmpegRunner.class);

    /** ffmpeg 进度行里的 time=HH:MM:SS.xx（合成进度换算百分比用）。 */
    private static final Pattern TIME = Pattern.compile("time=(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");

    /** 合成/转码进度回调（0~100）。 */
    public interface ProgressListener {
        void onProgress(double percent);
    }

    private final String ffmpegPath;
    private final String ffprobePath;

    public FfmpegRunner(@Value("${playlet.ffmpeg.base-path}") String ffmpegBasePath) {
        this.ffmpegPath = ffmpegBasePath + "/ffmpeg";
        this.ffprobePath = ffmpegBasePath + "/ffprobe";
    }

    public String ffmpegPath() {
        return ffmpegPath;
    }

    private String ffprobePath() {
        return ffprobePath;
    }

    /** 执行 ffmpeg 命令（args 不含可执行文件名）。失败抛异常并带 stderr 尾部。 */
    public void run(List<String> args, long timeoutSeconds) {
        run(args, timeoutSeconds, 0, null);
    }

    /**
     * 执行 ffmpeg 命令并汇报进度。
     *
     * @param totalDurationSec 预期总时长（秒）；&gt;0 时按 time= 解析换算百分比推送
     * @param listener         进度回调（可为 null）
     */
    public void run(List<String> args, long timeoutSeconds, double totalDurationSec,
                    ProgressListener listener) {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.addAll(args);
        log.info("ffmpeg {}", String.join(" ", cmd.subList(1, Math.min(cmd.size(), 12))) + " ...");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        try {
            Process proc = pb.start();
            StringBuilder tail = new StringBuilder();
            double lastPercent = -1;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (tail.length() < 4000) {
                        tail.append(line).append('\n');
                    }
                    if (listener != null && totalDurationSec > 0) {
                        double pct = parsePercent(line, totalDurationSec);
                        // 只回传递增且变化 ≥1% 的进度，避免刷屏
                        if (pct >= 0 && pct > lastPercent && (pct - lastPercent) >= 1.0) {
                            lastPercent = pct;
                            listener.onProgress(Math.min(pct, 100));
                        }
                    }
                }
            }
            boolean done = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                throw new MediaException("ffmpeg timeout after " + timeoutSeconds + "s");
            }
            if (proc.exitValue() != 0) {
                throw new MediaException("ffmpeg exit " + proc.exitValue() + ":\n" + tail);
            }
            if (listener != null) {
                listener.onProgress(100);
            }
        } catch (IOException e) {
            throw new MediaException("failed to run ffmpeg at " + ffmpegPath + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MediaException("ffmpeg interrupted", e);
        }
    }

    /** 从一行 ffmpeg 输出解析 time= 并换算成 0~100 百分比；无 time= 返回 -1。 */
    static double parsePercent(String line, double totalDurationSec) {
        if (totalDurationSec <= 0) {
            return -1;
        }
        Matcher m = TIME.matcher(line);
        if (!m.find()) {
            return -1;
        }
        double sec = Integer.parseInt(m.group(1)) * 3600.0
                + Integer.parseInt(m.group(2)) * 60.0
                + Double.parseDouble(m.group(3));
        return sec / totalDurationSec * 100.0;
    }

    /** ffprobe 取时长（秒）。 */
    public double probeDuration(String file) {
        String out = probe(List.of("-show_entries", "format=duration"), file);
        String first = out.lines().map(String::trim).filter(s -> !s.isEmpty()).findFirst().orElse("");
        try {
            return Double.parseDouble(first);
        } catch (NumberFormatException e) {
            throw new MediaException("ffprobe duration unparsable for " + file + ": '" + out + "'", e);
        }
    }

    /** ffprobe 取视频宽高（[width, height]）；无视频流抛异常。 */
    public int[] probeVideoSize(String file) {
        String out = probe(List.of("-select_streams", "v:0", "-show_entries", "stream=width,height"), file);
        Matcher m = Pattern.compile("(\\d+)\\s+(\\d+)").matcher(out.trim());
        if (!m.find()) {
            throw new MediaException("ffprobe found no video stream size for " + file + ": '" + out + "'");
        }
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    }

    /**
     * 是否含音频流。H3 原生音频是可选槽（DESIGN §4.7：静音视频 = 关 audio 槽），
     * 混音前必须探测，否则 [0:a] 引用不存在的流会整条命令失败。
     * 探测本身失败按无音轨处理并记日志——后续合成步骤会响亮失败，不静默出错片。
     */
    public boolean probeHasAudio(String file) {
        ProcessBuilder pb = new ProcessBuilder(ffprobePath(), "-v", "error",
                "-select_streams", "a", "-show_entries", "stream=codec_type",
                "-of", "csv=p=0", file);
        pb.redirectErrorStream(true);
        try {
            Process proc = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return false;
            }
            return proc.exitValue() == 0 && out.toString().contains("audio");
        } catch (IOException e) {
            log.warn("ffprobe audio check failed for {}: {}", file, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 跑一次 ffprobe（-v error -of default=noprint_wrappers=1:nokey=1），返回**全部**输出行。 */
    private String probe(List<String> showArgs, String file) {
        List<String> cmd = new java.util.ArrayList<>(List.of(ffprobePath(), "-v", "error"));
        cmd.addAll(showArgs);
        cmd.addAll(List.of("-of", "default=noprint_wrappers=1:nokey=1", file));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        try {
            Process proc = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            boolean done = proc.waitFor(30, TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                throw new MediaException("ffprobe timeout for " + file);
            }
            if (proc.exitValue() != 0) {
                throw new MediaException("ffprobe exit " + proc.exitValue() + " for " + file
                        + ":\n" + out);
            }
            return out.toString();
        } catch (IOException e) {
            throw new MediaException("failed to run ffprobe for " + file + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MediaException("ffprobe interrupted", e);
        }
    }
}
