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

/**
 * ffmpeg 命令行封装。路径来自配置（playlet.ffmpeg.path），不在 PATH 上也能用。
 * 见 DESIGN.md §10.7 / BUILDING.md。
 */
@Component
public class FfmpegRunner {

    private static final Logger log = LoggerFactory.getLogger(FfmpegRunner.class);

    private final String ffmpegPath;

    public FfmpegRunner(@Value("${playlet.ffmpeg.path:ffmpeg}") String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    /** 执行 ffmpeg 命令（args 不含可执行文件名）。失败抛异常并带 stderr 尾部。 */
    public void run(List<String> args, long timeoutSeconds) {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.addAll(args);
        log.info("ffmpeg {}", String.join(" ", cmd.subList(1, Math.min(cmd.size(), 12))) + " ...");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        try {
            Process proc = pb.start();
            StringBuilder tail = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (tail.length() < 4000) {
                        tail.append(line).append('\n');
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
        } catch (IOException e) {
            throw new MediaException("failed to run ffmpeg at " + ffmpegPath + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MediaException("ffmpeg interrupted", e);
        }
    }

    /** ffprobe 取时长（秒）。 */
    public double probeDuration(String file) {
        ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath.replace("ffmpeg", "ffprobe"),
                "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", file);
        pb.redirectErrorStream(true);
        try {
            Process proc = pb.start();
            String out;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.readLine();
            }
            proc.waitFor(30, TimeUnit.SECONDS);
            return Double.parseDouble(out == null ? "0" : out.trim());
        } catch (Exception e) {
            throw new MediaException("ffprobe failed for " + file + ": " + e.getMessage(), e);
        }
    }
}
