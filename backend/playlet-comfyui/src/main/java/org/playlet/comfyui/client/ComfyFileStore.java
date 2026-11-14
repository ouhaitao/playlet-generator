package org.playlet.comfyui.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * ComfyUI 文件目录访问：output（生成产物）与 input（LoadImage 素材）。
 *
 * <p>首帧图审核通过（闸门②）后，把产物从 output 复制进 input 目录，
 * 返回 input 相对路径写入 {@code Shot.firstFrameImage}，图生视频的 LoadImage 即用该名。
 * 两个目录都来自配置变量（本机 ComfyUI 与平台同机部署，见 DESIGN.md §4）。
 */
@Component
public class ComfyFileStore {

    private static final Logger log = LoggerFactory.getLogger(ComfyFileStore.class);

    private final Path outputDir;
    private final Path inputDir;

    public ComfyFileStore(
            @Value("${playlet.comfyui.output-dir}") String outputDir,
            @Value("${playlet.comfyui.input-dir}") String inputDir) {
        this.outputDir = Paths.get(outputDir).toAbsolutePath().normalize();
        this.inputDir = Paths.get(inputDir).toAbsolutePath().normalize();
    }

    /** artifactPath（相对 output，如 image/anima_xxx.png）→ 绝对路径。 */
    public Path resolveOutput(String artifactPath) {
        if (artifactPath == null || artifactPath.isBlank()) {
            throw new IllegalArgumentException("artifactPath 为空");
        }
        Path p = outputDir.resolve(artifactPath.replace('\\', '/')).normalize();
        if (!p.startsWith(outputDir)) {
            throw new IllegalArgumentException("artifactPath 越界: " + artifactPath);
        }
        return p;
    }

    /**
     * 把 output 产物复制进 input 目录（子目录 playlet/，避免与手工素材混淆）。
     *
     * @param artifactPath output 相对路径
     * @param baseName     目标文件名（不含扩展名；扩展名沿用产物）
     * @return input 相对路径（写入 Shot.firstFrameImage，即 LoadImage 的 image 参数）
     */
    public String promoteToInput(String artifactPath, String baseName) throws IOException {
        Path src = resolveOutput(artifactPath);
        if (!Files.exists(src)) {
            throw new IOException("产物不存在（ComfyUI output 目录不可达或已被清理）: " + src);
        }
        String fileName = src.getFileName().toString();
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : ".png";
        Path dest = inputDir.resolve("playlet").resolve(sanitize(baseName) + ext);
        Files.createDirectories(dest.getParent());
        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
        String rel = "playlet/" + dest.getFileName();
        log.info("promoted artifact to comfy input: {} -> {}", artifactPath, rel);
        return rel;
    }

    private String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
