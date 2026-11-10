package org.playlet.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 启动时确保存储目录存在（SQLite 驱动与 Flyway 都不会创建父目录）。
 *
 * <p>实现为 {@link BeanFactoryPostProcessor}：在所有常规 bean（含 flywayInitializer /
 * dataSource）实例化**之前**执行，保证 Flyway 首次连接时目录已就绪。
 *
 * <p>见 BUILDING.md：数据目录必须在运行机器本地盘，不放 SMB。
 */
@Component
public class StorageInitializer implements BeanFactoryPostProcessor, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(StorageInitializer.class);

    private Environment env;

    @Override
    public void setEnvironment(Environment environment) {
        this.env = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        String dir = env.getProperty("playlet.storage.dir", "./data");
        try {
            Path root = Paths.get(dir).toAbsolutePath().normalize();
            Files.createDirectories(root);
            Files.createDirectories(root.resolve("media"));
            log.info("storage dir ready: {}", root);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create storage dir: " + dir, e);
        }
    }
}
