package org.playlet.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 媒体静态资源：/media/** → ComfyUI output 目录（生成产物）+ 本地存储目录（合成成品/上传素材）。
 * 路径全部变量配置，不硬编码。
 */
@Configuration
public class MediaWebConfig implements WebMvcConfigurer {

    private final String comfyOutputDir;
    private final String storageDir;

    public MediaWebConfig(
            @Value("${playlet.comfyui.output-dir}")
            String comfyOutputDir,
            @Value("${playlet.storage.dir}") String storageDir) {
        this.comfyOutputDir = comfyOutputDir;
        this.storageDir = storageDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path comfy = Paths.get(comfyOutputDir).toAbsolutePath().normalize();
        Path storage = Paths.get(storageDir).toAbsolutePath().normalize();
        registry.addResourceHandler("/media/comfy/**")
                .addResourceLocations(comfy.toUri().toString());
        registry.addResourceHandler("/media/store/**")
                .addResourceLocations(storage.toUri().toString());
    }
}
